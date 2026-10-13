# Authentication (tasks API)

The tasks API **verifies** tokens and never issues them. Tokens come from the users service, which signs them with an RSA private key (**RS256**). The tasks API holds only the matching **public key**, so it has no code that can create or sign a token.

```
 client ──login──▶ users service ──signs with private.pem──▶ JWT
 client ──JWT────▶ tasks API ──verifies with public.pem──▶ 200 / 401 / 403
```

This document covers the tasks API side. The token format, the key pair and the signing rules are described in the users service's `README-authentication.md`.

## Design decisions

| Decision | Why |
|---|---|
| **Verify only, public key only** | A leaked tasks API config or image cannot mint tokens. `PemKeyLoader` has no private-key code path, and it refuses a file that contains a private key. |
| **Key loaded once at startup, from a file path** | A missing or malformed key stops the app immediately with `Could not load public key from ...`, rather than failing on the first request. |
| **No default for `jwt.public-key-path`** | A misconfigured deployment must fail loudly. It must never start with the wrong key or no key. |
| **Issuer and audience are checked on every token** | A token signed with the right key but meant for another purpose is rejected. |
| **30 seconds of clock skew allowed** | Absorbs small clock differences between services. |
| **Verification is pinned to an RSA public key** | HS256 tokens keyed with the public key (algorithm confusion) and unsigned `alg: none` tokens are rejected. Both are covered by tests and by the smoke test. |
| **Only `ROLE_*` roles are read from the token** | Anything else in `roles` is ignored. A missing or malformed claim means no authorities, never an error. |
| **The `Bearer` scheme is case-insensitive** | `bearer`, `BEARER` and `Bearer` all work (RFC 7235). The token is trimmed. |
| **Ownership uses the `uid` claim, and a refusal is a 404** | A plain user may read and complete only tasks whose `userId` is their own id. Another user's task gets the same 404 as one that does not exist, so ids cannot be probed. ADMIN and MANAGER may access any task. |
| **Stateless sessions, CSRF disabled** | The API uses bearer tokens and never sets a session cookie, so there is nothing for a cross-site request to hijack. |
| **Every 401 and 403 uses the app's error format** | `RestAuthenticationEntryPoint` and `RestAccessDeniedHandler` pass the failure to `GlobalExceptionHandler`. Bodies are `{"message", "timestamp"}`. A 401 carries `WWW-Authenticate: Bearer`. |

## What the tasks API accepts

| Claim | Requirement |
|---|---|
| header `alg` | `RS256` |
| `iss` | equals `jwt.issuer` |
| `aud` | contains `jwt.audience` (the users service issues it as an array) |
| `sub` | present and not blank |
| `uid` | present, a JSON number, a positive whole number (the user's database id) |
| `exp` | present, and not more than 30 seconds in the past |
| `roles` | optional list of strings. Only entries starting with `ROLE_` are used. |

A token is rejected for any of these reasons:

| Reason | Cause |
|---|---|
| `EXPIRED` | past `exp` plus the 30s skew |
| `BAD_SIGNATURE` | signed with a different key, or the payload was changed |
| `MALFORMED` | not a valid JWT, or any other failure (wrong algorithm, missing subject, expiry or valid `uid`, wrong issuer or audience) |
| `EMPTY` | the token is blank |

Wrong issuer, wrong audience, HS256 and unsigned tokens all report `MALFORMED`. The client sees a 401 with the message for that reason (for example `Token has expired`).

Tokens cannot be revoked. A token stays valid until it expires, so keep the lifetime short on the users service.

## Access rules

| Endpoint | Rule |
|---|---|
| `GET /api/tasks` | `ADMIN` or `MANAGER` |
| `GET /api/tasks/{id}` | any authenticated user, but a plain user only for their own task (any other task is a 404) |
| `GET /api/tasks/user/{userId}` | `ADMIN` or `MANAGER`, or the user themselves (any other user gets a 403) |
| `POST /api/tasks` | `ADMIN` or `MANAGER` |
| `PUT /api/tasks/{id}` | `ADMIN` or `MANAGER` |
| `PUT /api/tasks/{id}/complete` | any authenticated user, but a plain user only for their own task (any other task is a 404, and nothing changes) |
| `DELETE /api/tasks/{id}` | `ADMIN` |

There is no public endpoint: everything else in `SecurityConfig` is `anyRequest().authenticated()`, and the role rules are `@PreAuthorize` annotations on the controller.

**Ownership.** Two routes are open to any signed-in user and add an ownership rule on top of the role rules: `GET /api/tasks/{id}` and `PUT /api/tasks/{id}/complete`. ADMIN and MANAGER may use them on any task. Anyone else may use them only on a task whose `userId` equals the `uid` in their token. Otherwise the response is a **404**, identical to a task that does not exist, and nothing is changed. `TaskAccessPolicy` holds the rule, and it refuses when in doubt (unexpected principal, task with no owner).

The list route `GET /api/tasks/user/{userId}` has a *user* id in its path, so it uses `@userSecurity.isSelf(#userId, authentication)`, which compares that id with the token's `uid` and needs no lookup. **Never use `isSelf` on a path that holds a task id**: a task id is not a user id, and comparing them lets one user's id open another user's task. Task routes read the owner from the task instead, through `TaskAccessPolicy`.

A missing or invalid token gets a 401. An authenticated user without the role gets a 403.

## Configuration

| Property | Environment variable | Default |
|---|---|---|
| `jwt.public-key-path` | `JWT_PUBLIC_KEY_PATH` | none (required) |
| `jwt.issuer` | `JWT_ISSUER` | `user-service` |
| `jwt.audience` | `JWT_AUDIENCE` | `taskflow-api` |
| `server.port` | `APP_PORT` | `8077` |

The tasks API must use **exactly the same issuer and audience** as the users service. If they differ, every token is rejected with a 401 that looks like a signature problem.

There is no `jwt.secret` and no `jwt.expiration-ms`. Delete `JWT_SECRET_ENV` from any old `.env`.

## Keys

The tasks API needs only `public.pem`, the public half of the pair created for the users service (see its `AUTHENTICATION.md` for how to generate it). The file must be an X.509 public key, whose first line is `-----BEGIN PUBLIC KEY-----`. This is what `openssl rsa -pubout` produces.

- `public.pem` is not secret, but never give the tasks API `private.pem`. It refuses to start with a private key.
- A `-----BEGIN RSA PUBLIC KEY-----` file is PKCS#1 and is rejected. Regenerate it from the private key with `openssl rsa -pubout -in private.pem -out public.pem`.
- If the key pair is regenerated or rotated on the users service, replace `public.pem` here and restart. Existing tokens stop working.

### Run locally (`mvn`)

`mvn` does not see `.env` or Docker Compose variables. Export the path yourself:

```bash
export JWT_PUBLIC_KEY_PATH=$HOME/.taskflow/keys/public.pem
mvn spring-boot:run
```

### Run with Docker Compose

The tasks API has its own stack (`tasks-db`, `tasks-service`, network `tasks-network`, volume `tasks_db_data`). Copy `.env.example` to `.env`, then:

```bash
docker compose config            # check the resolved host key path
docker compose up --build -d
docker compose logs -f tasks-service
```

The public key is mounted as a Compose secret, read-only, so it never enters the image:

```yaml
tasks-service:
  environment:
    - JWT_PUBLIC_KEY_PATH=/run/secrets/jwt_public_key   # path inside the container
  secrets:
    - jwt_public_key

secrets:
  jwt_public_key:
    file: ${JWT_PUBLIC_KEY_FILE:-${HOME}/.taskflow/keys/public.pem}   # path on the host
```

- Compose does not expand `~`, so the default uses `${HOME}`.
- `public.pem` is created world-readable, so the non-root `spring` user can read it. If you tighten it to mode `600`, add `user: "${LOCAL_UID:-1000}:${LOCAL_GID:-1000}"` to the service, as in the users stack.
- The database is published on host port `5433` by default so it does not clash with the users database (host port `5422`) or a local Postgres on `5432`.
- The published port mapping must match `APP_PORT`, because `server.port` follows that variable.
- `JWT_ISSUER_ENV` and `JWT_AUDIENCE_ENV` must be the same as in the users service's `.env`.

## Testing

| Test | What it covers |
|---|---|
| `PemKeyLoaderTest` | key loading, including a missing file, an empty file, a private key, a PKCS#1 key and a corrupt body |
| `JwtServiceTest` | accepted and rejected tokens (expired beyond and within the skew, wrong signer, tampered payload, wrong issuer or audience, HS256, unsigned, missing subject or expiry, garbage, empty), role filtering, a token shaped like the users service's (audience as an array), and fail-fast construction |
| `JwtAuthFilterTest` | no header, non-Bearer scheme, valid token, case-insensitive scheme, trimming, and a rejected token recorded for the entry point |
| `RestAccessDeniedHandlerTest` | the handler passes the exception to the resolver |
| `SecurityEndToEndTest` | the full HTTP flow through the real filter chain: 200, 401 (none, expired, wrong key, HS256, garbage), 403, and Spring's own 404 and 405 keeping their status. It asserts the error body shape. |
| `TaskControllerSecurityTest` | the `@PreAuthorize` role rules: every route called as `ROLE_USER`, `ROLE_MANAGER`, `ROLE_ADMIN`, a token with no roles and no token, with the expected 200, 201, 403 or 401. Denied calls must never reach the service. |
| `TaskControllerTest` | request and response behaviour with `TaskService` mocked: created and updated tasks, exact validation messages for invalid bodies, 404, 400 and 415 mapping, and that a client-supplied `id` or `userId` is ignored |
| `TaskServiceImplTest` | the task logic with a mocked repository: not-found handling, and that an update never changes the id or owner |
| `TaskControllerOwnershipTest` | owner, another user, manager and admin against the read, complete and list routes, that a refused caller never changes the task, that another user's task looks exactly like a missing one, and two regressions for the task-id versus user-id mix-up |
| `TaskAccessPolicyTest` | the task ownership rule on its own, including the fail-closed cases and a task id that equals the caller's id |
| `UserSecurityTest` | `isSelf`, the user-id check behind the list route |
| `smoke-test.sh` | the same rules against the real running stack, using a token from the real users service |

Tests never use your real keys. `TestJwtKeys` generates a throwaway RSA pair per run and registers the properties through `@DynamicPropertySource`. **Every `@SpringBootTest` needs the same registration**, plus H2 settings, because the main properties use PostgreSQL.

Run the unit and integration tests with `mvn clean test`.

### Smoke test

The users stack must already be running, because the script logs in on it. Run it from the tasks project root, on the host:

```bash
chmod +x smoke-test.sh
./smoke-test.sh --up --restart
```

It reads `.env` and `~/.taskflow/keys/`. It checks that:

1. no token and a garbage token get a 401 in the app's error format, with `WWW-Authenticate: Bearer`;
2. a real login token matches the shared contract (RS256, issuer, audience, roles) and is accepted (`GET /api/tasks` returns 200, an unknown task returns 404);
3. tampered, unsigned, HS256-forged and wrong-key tokens get a 401;
4. if `private.pem` is readable, correctly signed tokens with the wrong audience, wrong issuer or expired beyond the skew get a 401, and a role-less token gets a 403. A control token with correct claims must get a 200, which proves the other results are not false passes;
5. ownership: it creates two throwaway users on the users service, has an admin create a task owned by the first, and checks that the owner can read, complete and list it, the other user gets 404 for both and cannot change it, an admin can read it, and the owner still cannot edit, delete or list tasks. The throwaway users are always deleted;
6. with `--restart`, a token issued before restarting `tasks-service` still works.

Settings it understands: `USERS_URL` (default `http://localhost:8022`), `TASKS_URL` (default from `APP_PORT_ENV`), `SMOKE_ADMIN_USERNAME` and `SMOKE_ADMIN_PASSWORD` (default to `ADMIN_USERNAME_ENV` / `ADMIN_PASSWORD_ENV`, then `admin01` / `abcd1234`), and `JWT_PUBLIC_KEY_FILE` / `JWT_PRIVATE_KEY_FILE`. It exits non-zero if any check fails. Run it after any change to security config, keys or Docker setup.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| `Could not resolve placeholder 'jwt.public-key-path'` | `JWT_PUBLIC_KEY_PATH` is not set in the environment that started the app, or the test lacks `@DynamicPropertySource` |
| `Could not load public key ... NoSuchFileException` | wrong path, or the key is not mounted into the container |
| `Could not load public key ... File contains a private key` | the tasks API was given `private.pem`. Give it `public.pem`. |
| `Could not load public key ... PKCS#1` | the file begins `BEGIN RSA PUBLIC KEY`. Regenerate it with `openssl rsa -pubout`. |
| `docker compose up` fails before starting, naming the secret file | `public.pem` does not exist at the host path. Run `docker compose config` to see it, then fix the path or set `JWT_PUBLIC_KEY_FILE`. |
| Every token rejected with 401 after a change | the issuer or audience differs from the users service, or the public key no longer pairs with the users service's private key |
| `Unable to determine Dialect without JDBC metadata` | the database is unreachable (not running, wrong credentials, or the database does not exist) |
| No response on `localhost:8077` | the port mapping does not match `APP_PORT`. Check the log line `Tomcat started on port ...`. |
| A failed `@PreAuthorize` returns 500 | `GlobalExceptionHandler` lost its `AccessDeniedException` handler |

## Known limitations

- **Revocation:** a token stays valid until it expires. There is no logout or deny list.
- **Key rotation:** there is one static public key. Rotating means replacing `public.pem` and restarting. Supporting rotation needs a `kid` header and a JWKS endpoint on the users service.
- **Ownership covers read, complete and list only:** ADMIN and MANAGER create, update and delete tasks for anyone, and the `userId` on create is supplied by the client.
- **Schema:** `spring.jpa.hibernate.ddl-auto=create` recreates all tables on every start. Switch to `update` or a migration tool before keeping real data.