#!/usr/bin/env bash
# Smoke test for the tasks API. Run from the tasks project root, on the host.
#
#   ./smoke-test.sh                 test an already running stack
#   ./smoke-test.sh --up            rebuild and start the tasks stack first
#   ./smoke-test.sh --up --restart  also restart tasks-service and re-check an earlier token
#
# The users service must already be running: this script logs in on it to get a real token.
# It reads .env, ~/.taskflow/keys/public.pem and (optionally) private.pem.
# Exits non-zero if any check fails.
set -uo pipefail

DO_UP=false
DO_RESTART=false
for arg in "$@"; do
  case "$arg" in
    --up) DO_UP=true ;;
    --restart) DO_RESTART=true ;;
    -h|--help) sed -n '2,10p' "$0"; exit 0 ;;
    *) echo "Unknown option: $arg" >&2; exit 2 ;;
  esac
done

if [[ -f .env ]]; then set -a; . ./.env; set +a; fi

APP_PORT="${APP_PORT_ENV:-8077}"
TASKS_URL="${TASKS_URL:-http://localhost:${APP_PORT}}"
USERS_URL="${USERS_URL:-http://localhost:8022}"
ISSUER="${JWT_ISSUER_ENV:-user-service}"
AUDIENCE="${JWT_AUDIENCE_ENV:-taskflow-api}"
ADMIN_USER="${SMOKE_ADMIN_USERNAME:-${ADMIN_USERNAME_ENV:-admin01}}"
ADMIN_PASS="${SMOKE_ADMIN_PASSWORD:-${ADMIN_PASSWORD_ENV:-abcd1234}}"
PUBLIC_KEY="${JWT_PUBLIC_KEY_FILE:-$HOME/.taskflow/keys/public.pem}"
PRIVATE_KEY="${JWT_PRIVATE_KEY_FILE:-$HOME/.taskflow/keys/private.pem}"

for tool in curl python3 openssl; do
  command -v "$tool" >/dev/null || { echo "Missing required tool: $tool" >&2; exit 2; }
done

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# ---------- token helper ----------
cat > "$TMP/forge.py" <<'PY'
import argparse, base64, hmac, hashlib, json, subprocess, sys, time

def b64e(b): return base64.urlsafe_b64encode(b).rstrip(b"=").decode()
def b64d(s): return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))
def enc(o): return b64e(json.dumps(o, separators=(",", ":")).encode())
def split(t):
    h, p, s = t.split(".")
    return json.loads(b64d(h)), json.loads(b64d(p)), s
def rs256(keyfile, signing_input):
    out = subprocess.run(["openssl", "dgst", "-sha256", "-sign", keyfile],
                         input=signing_input.encode(), capture_output=True, check=True).stdout
    return b64e(out)

cmd = sys.argv[1]
if cmd == "tamper":            # genuine header and signature, altered payload
    h, p, s = split(sys.argv[2]); p["sub"] = "mallory"
    print(f"{enc(h)}.{enc(p)}.{s}")
elif cmd == "hs256":           # algorithm confusion: HMAC keyed with the public key file
    _, p, _ = split(sys.argv[2]); key = open(sys.argv[3], "rb").read()
    si = f"{enc({'alg': 'HS256', 'typ': 'JWT'})}.{enc(p)}"
    print(si + "." + b64e(hmac.new(key, si.encode(), hashlib.sha256).digest()))
elif cmd == "none":            # unsigned token
    _, p, _ = split(sys.argv[2])
    print(f"{enc({'alg': 'none', 'typ': 'JWT'})}.{enc(p)}.")
elif cmd == "signed":          # properly RS256-signed token with chosen claims
    a = argparse.ArgumentParser(); a.add_argument("cmd"); a.add_argument("key")
    a.add_argument("--sub", default="smoke"); a.add_argument("--iss", required=True)
    a.add_argument("--aud", required=True); a.add_argument("--exp-offset", type=int, default=3600)
    a.add_argument("--roles", default="ROLE_ADMIN")
    a.add_argument("--uid", type=int, default=1)
    a.add_argument("--no-uid", action="store_true")
    x = a.parse_args(); now = int(time.time()); exp = now + x.exp_offset
    claims = {"iss": x.iss, "aud": [x.aud], "sub": x.sub, "iat": min(now, exp - 3600), "exp": exp,
              "roles": [r for r in x.roles.split(",") if r]}
    if not x.no_uid: claims["uid"] = x.uid
    si = f"{enc({'alg': 'RS256', 'typ': 'JWT'})}.{enc(claims)}"
    print(si + "." + rs256(x.key, si))
elif cmd == "check":           # does a token match the shared contract?
    h, p, _ = split(sys.argv[2]); iss, aud = sys.argv[3], sys.argv[4]; bad = []
    if h.get("alg") != "RS256": bad.append(f"alg is {h.get('alg')}")
    if p.get("iss") != iss: bad.append(f"iss is {p.get('iss')}")
    got = p.get("aud"); got = [got] if isinstance(got, str) else (got or [])
    if aud not in got: bad.append(f"aud is {got}")
    for c in ("sub", "iat", "exp"):
        if c not in p: bad.append(f"missing {c}")
    uid = p.get("uid")
    if isinstance(uid, bool) or not isinstance(uid, int) or uid <= 0: bad.append(f"uid is {uid}")
    roles = p.get("roles")
    if not isinstance(roles, list) or not all(isinstance(r, str) and r.startswith("ROLE_") for r in roles):
        bad.append(f"roles is {roles}")
    print("; ".join(bad) if bad else f"alg=RS256 iss={iss} aud={got} sub={p['sub']} uid={uid} roles={roles}")
    sys.exit(1 if bad else 0)
PY
forge() { python3 "$TMP/forge.py" "$@"; }

# ---------- tiny test framework ----------
PASS=0; FAIL=0; STATUS=""; BODY=""; HEADERS=""
ok()   { echo "  PASS  $1"; PASS=$((PASS + 1)); }
bad()  { echo "  FAIL  $1"; FAIL=$((FAIL + 1)); }
note() { echo "  SKIP  $1"; }
section() { echo; echo "== $1"; }

req() {  # METHOD URL [extra curl args]
  local method="$1" url="$2"; shift 2
  STATUS="$(curl -s -o "$TMP/body" -D "$TMP/headers" -w '%{http_code}' -X "$method" "$@" "$url" || true)"
  BODY="$(cat "$TMP/body" 2>/dev/null || true)"
  HEADERS="$(tr -d '\r' < "$TMP/headers" 2>/dev/null || true)"
}
expect_status() {  # description expected
  if [[ "$STATUS" == "$2" ]]; then ok "$1 -> $2"; else bad "$1: expected $2, got $STATUS (${BODY:0:160})"; fi
}
with_token() { req GET "$1" -H "Authorization: Bearer $2"; }

wait_ready() {  # up and enforcing security = answers 401 without a token
  local code i
  for i in $(seq 1 60); do
    code="$(curl -s -o /dev/null -w '%{http_code}' "$TASKS_URL/api/tasks" || true)"
    [[ "$code" == "401" ]] && return 0
    sleep 2
  done
  return 1
}

# ---------- start the stack ----------
if $DO_UP; then
  section "Starting the tasks stack"
  docker compose up --build -d || { echo "docker compose up failed" >&2; exit 1; }
fi
if ! wait_ready; then
  echo "The tasks API did not answer 401 at $TASKS_URL/api/tasks." >&2
  echo "Check: docker compose ps, and docker compose logs tasks-service" >&2
  exit 1
fi

# ---------- 1. no token ----------
section "Requests without a valid token"
req GET "$TASKS_URL/api/tasks"
expect_status "no token" 401
grep -qi '^www-authenticate: bearer' <<<"$HEADERS" && ok "401 carries WWW-Authenticate: Bearer" || bad "401 is missing WWW-Authenticate: Bearer"
{ grep -q '"message"' <<<"$BODY" && grep -q '"timestamp"' <<<"$BODY"; } && ok "401 body has message and timestamp" || bad "401 body is not the app error format: ${BODY:0:160}"
with_token "$TASKS_URL/api/tasks" "garbage"; expect_status "garbage token" 401

# ---------- 2. real token from the users service ----------
section "Login on the users service"
req POST "$USERS_URL/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}"
TOKEN="$(grep -oE 'eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+' <<<"$BODY" | head -1 || true)"
if [[ "$STATUS" != "200" || -z "$TOKEN" ]]; then
  bad "login as $ADMIN_USER at $USERS_URL (status $STATUS): ${BODY:0:160}"
  echo "Is the users stack running? Are SMOKE_ADMIN_USERNAME / SMOKE_ADMIN_PASSWORD right?" >&2
  echo; echo "Result: $PASS passed, $FAIL failed"; exit 1
fi
ok "login returned a JWT"
if detail="$(forge check "$TOKEN" "$ISSUER" "$AUDIENCE")"; then ok "token matches the contract ($detail)"; else bad "token breaks the contract: $detail"; fi

# ---------- 3. real token accepted ----------
section "Real token against the tasks API"
with_token "$TASKS_URL/api/tasks" "$TOKEN";        expect_status "GET /api/tasks as admin" 200
with_token "$TASKS_URL/api/tasks/999999" "$TOKEN"; expect_status "GET /api/tasks/999999 (authenticated, no such task)" 404

# ---------- 4. forgeries that need no key ----------
section "Forged tokens (no key needed)"
with_token "$TASKS_URL/api/tasks" "$(forge tamper "$TOKEN")";  expect_status "tampered payload" 401
with_token "$TASKS_URL/api/tasks" "$(forge none "$TOKEN")";    expect_status "unsigned (alg none)" 401
if [[ -r "$PUBLIC_KEY" ]]; then
  with_token "$TASKS_URL/api/tasks" "$(forge hs256 "$TOKEN" "$PUBLIC_KEY")"; expect_status "HS256 keyed with the public key" 401
else
  note "HS256 forgery: $PUBLIC_KEY not readable"
fi

# ---------- 5. tokens signed with a key ----------
section "Signed tokens with wrong claims"
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$TMP/other.pem" >/dev/null 2>&1
with_token "$TASKS_URL/api/tasks" "$(forge signed "$TMP/other.pem" --iss "$ISSUER" --aud "$AUDIENCE")"
expect_status "valid claims signed by an unrelated key" 401

if [[ -r "$PRIVATE_KEY" ]]; then
  # Control: proves the forging helper works, so the 401s below are for the right reason.
  with_token "$TASKS_URL/api/tasks" "$(forge signed "$PRIVATE_KEY" --iss "$ISSUER" --aud "$AUDIENCE")"
  expect_status "control: correct claims signed with the real key" 200
  with_token "$TASKS_URL/api/tasks" "$(forge signed "$PRIVATE_KEY" --iss "$ISSUER" --aud "$AUDIENCE" --roles ROLE_USER)"
  expect_status "real key, ROLE_USER only, on an admin/manager route" 403
  with_token "$TASKS_URL/api/tasks" "$(forge signed "$PRIVATE_KEY" --iss "$ISSUER" --aud "$AUDIENCE" --roles "ADMIN,FACTOR_PASSWORD")"
  expect_status "real key, roles without the ROLE_ prefix" 403
  with_token "$TASKS_URL/api/tasks" "$(forge signed "$PRIVATE_KEY" --iss "$ISSUER" --aud "$AUDIENCE" --no-uid)"
  expect_status "real key, no uid claim" 401
  with_token "$TASKS_URL/api/tasks" "$(forge signed "$PRIVATE_KEY" --iss "$ISSUER" --aud "wrong-audience")"
  expect_status "real key, wrong audience" 401
  with_token "$TASKS_URL/api/tasks" "$(forge signed "$PRIVATE_KEY" --iss "wrong-issuer" --aud "$AUDIENCE")"
  expect_status "real key, wrong issuer" 401
  with_token "$TASKS_URL/api/tasks" "$(forge signed "$PRIVATE_KEY" --iss "$ISSUER" --aud "$AUDIENCE" --exp-offset -120)"
  expect_status "real key, expired beyond the 30s skew" 401
  with_token "$TASKS_URL/api/tasks" "$(forge signed "$PRIVATE_KEY" --iss "$ISSUER" --aud "$AUDIENCE" --exp-offset -5)"
  expect_status "real key, expired within the 30s skew" 200
else
  note "checks that sign with the real key: $PRIVATE_KEY not readable"
fi

# ---------- 6. ownership ----------
section "Task ownership (two throwaway users, one task)"
stamp="$(date +%s)"
OWNER_NAME="smk${stamp}a"; OTHER_NAME="smk${stamp}b"; SMOKE_PASS="Smoke-pass-1"
OWNER_ID=""; OTHER_ID=""; TASK_ID=""

json_id() { sed -n 's/.*"id"[[:space:]]*:[[:space:]]*\([0-9]*\).*/\1/p' <<<"$1" | head -1; }
create_user() {  # name -> prints the new id
  req POST "$USERS_URL/api/users" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$1\",\"password\":\"$SMOKE_PASS\",\"name\":\"Smoke Test\",\"role\":\"USER\"}"
  json_id "$BODY"
}
login_token() {  # name -> prints a JWT
  req POST "$USERS_URL/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$1\",\"password\":\"$SMOKE_PASS\"}"
  grep -oE 'eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+' <<<"$BODY" | head -1 || true
}

OWNER_ID="$(create_user "$OWNER_NAME")"
OTHER_ID="$(create_user "$OTHER_NAME")"
if [[ -z "$OWNER_ID" || -z "$OTHER_ID" ]]; then
  bad "could not create the two test users on the users service (admin POST /api/users)"
else
  OWNER_TOKEN="$(login_token "$OWNER_NAME")"
  OTHER_TOKEN="$(login_token "$OTHER_NAME")"
  if [[ -z "$OWNER_TOKEN" || -z "$OTHER_TOKEN" ]]; then
    bad "the two test users could not log in"
  else
    if detail="$(forge check "$OWNER_TOKEN" "$ISSUER" "$AUDIENCE")"; then ok "a plain user's token matches the contract ($detail)"; else bad "a plain user's token breaks the contract: $detail"; fi

    # An admin creates a task owned by the first user.
    req POST "$TASKS_URL/api/tasks" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
      -d "{\"title\":\"Smoke task\",\"priority\":\"LOW\",\"completed\":false,\"userId\":$OWNER_ID}"
    expect_status "admin creates a task owned by the first user" 201
    TASK_ID="$(json_id "$BODY")"

    if [[ -z "$TASK_ID" ]]; then
      bad "the create response has no task id: ${BODY:0:160}"
    else
      T="$TASKS_URL/api/tasks/$TASK_ID"
      with_token "$T" "$OWNER_TOKEN";  expect_status "owner can read their task" 200
      with_token "$T" "$OTHER_TOKEN";  expect_status "another user gets 404 for it" 404
      with_token "$T" "$TOKEN";        expect_status "an admin can read it" 200

      # The list of one user's tasks: that user, or an admin or manager.
      LIST_OWNER="$TASKS_URL/api/tasks/user/$OWNER_ID"
      with_token "$LIST_OWNER" "$OWNER_TOKEN"; expect_status "owner can list their own tasks" 200
      if grep -Eq "\"id\"[[:space:]]*:[[:space:]]*$TASK_ID([^0-9]|\$)" <<<"$BODY"; then ok "...and the list contains the task"; else bad "the owner's list does not contain task $TASK_ID: ${BODY:0:160}"; fi
      with_token "$LIST_OWNER" "$OTHER_TOKEN"; expect_status "another user cannot list someone else's tasks" 403
      with_token "$TASKS_URL/api/tasks/user/$OTHER_ID" "$OWNER_TOKEN"; expect_status "owner cannot list another user's tasks" 403
      with_token "$LIST_OWNER" "$TOKEN";       expect_status "an admin can list any user's tasks" 200

      req PUT "$T/complete" -H "Authorization: Bearer $OTHER_TOKEN"
      expect_status "another user cannot complete it (404)" 404
      with_token "$T" "$TOKEN"
      if grep -Eq '"completed"[[:space:]]*:[[:space:]]*false' <<<"$BODY"; then ok "...and the task is still not completed"; else bad "the task was changed by a user who does not own it: ${BODY:0:160}"; fi

      req PUT "$T/complete" -H "Authorization: Bearer $OWNER_TOKEN"
      expect_status "owner can complete their task" 200
      if grep -Eq '"completed"[[:space:]]*:[[:space:]]*true' <<<"$BODY"; then ok "...and it is now completed"; else bad "completing did not set completed: ${BODY:0:160}"; fi

      # Owning a task does not lift the role rules.
      req PUT "$T" -H "Authorization: Bearer $OWNER_TOKEN" -H 'Content-Type: application/json' \
        -d '{"title":"Edited","priority":"HIGH","completed":false}'
      expect_status "owner (plain user) cannot edit the task" 403
      req DELETE "$T" -H "Authorization: Bearer $OWNER_TOKEN"
      expect_status "owner (plain user) cannot delete the task" 403
      with_token "$TASKS_URL/api/tasks" "$OWNER_TOKEN"
      expect_status "plain user cannot list all tasks" 403

      req DELETE "$T" -H "Authorization: Bearer $TOKEN"
      expect_status "admin deletes the smoke task" 200
    fi
  fi
fi
# Always remove the throwaway users, even after a failure.
for uid_to_delete in "$OWNER_ID" "$OTHER_ID"; do
  [[ -n "$uid_to_delete" ]] && curl -s -o /dev/null -X DELETE "$USERS_URL/api/users/$uid_to_delete" -H "Authorization: Bearer $TOKEN" || true
done

# ---------- 6. restart ----------
if $DO_RESTART; then
  section "Restart tasks-service"
  docker compose restart tasks-service >/dev/null || bad "docker compose restart failed"
  if wait_ready; then
    with_token "$TASKS_URL/api/tasks" "$TOKEN"; expect_status "token issued before the restart still works" 200
  else
    bad "tasks API did not come back after the restart"
  fi
fi

echo
echo "Result: $PASS passed, $FAIL failed"
[[ "$FAIL" -eq 0 ]]