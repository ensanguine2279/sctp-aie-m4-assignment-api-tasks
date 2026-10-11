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
    x = a.parse_args(); now = int(time.time()); exp = now + x.exp_offset
    claims = {"iss": x.iss, "aud": [x.aud], "sub": x.sub, "iat": min(now, exp - 3600), "exp": exp,
              "roles": [r for r in x.roles.split(",") if r]}
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
    roles = p.get("roles")
    if not isinstance(roles, list) or not all(isinstance(r, str) and r.startswith("ROLE_") for r in roles):
        bad.append(f"roles is {roles}")
    print("; ".join(bad) if bad else f"alg=RS256 iss={iss} aud={got} sub={p['sub']} roles={roles}")
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