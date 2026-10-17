# Authentication (tasks API)

The tasks API **verifies** tokens and never issues them. Tokens come from the users service, which signs them with an RSA private key (**RS256**). The tasks API holds only the matching **public key**, so it has no code that can create or sign a token.

```
 client ──login──▶ users service ──signs with private.pem──▶ JWT
 client ──JWT────▶ tasks API ──verifies with public.pem──▶ 200 / 401 / 403

```

This document covers the tasks API side. The token format, the key pair and the signing rules are described in the users service's `README-authentication.md`.

## Design decisions

| Decision | Why |
| --- | --- |
| **Verify only, public key only** | A leaked tasks API config or image cannot mint tokens. `PemKeyLoader` has no private-key code path, and it refuses a file that contains a private key.

 |
| **Key loaded once at startup, from a file path** | A missing or malformed key stops the app immediately with `Could not load public key from ...`, rather than failing on the first request.

 |
| **No default for `jwt.public-key-path**` | A misconfigured deployment must fail loudly. It must never start with the wrong key or no key.

 |
| **Issuer and audience are checked on every token** | A token signed with the right key but meant for another purpose is rejected.

 |
| **30 seconds of clock skew allowed** | Absorbs small clock differences between services.

 |
| **Verification is pinned to an RSA public key** | HS256 tokens keyed with the public key (algorithm confusion) and unsigned `alg: none` tokens are rejected. Both are covered by tests and by the smoke test.

 |
| **Only `ROLE_*` roles are read from the token** | Anything else in `roles` is ignored. A missing or malformed claim means no authorities, never an error.

 |
| **The `Bearer` scheme is case-insensitive** | `bearer`, `BEARER` and `Bearer` all work (RFC 7235). The token is trimmed.

 |
| **Ownership uses the `uid` claim, and a refusal is a 404** | A plain user may read and complete only tasks whose `userId` is their own id. Another user's task gets the same 404 as one that does not exist, so ids cannot be probed. ADMIN and MANAGER may access any task.

 |
| **Request DTOs prevent parameter tampering** | `POST` uses `CreateTaskRequest` and `PUT` uses `UpdateTaskRequest` with `@Valid` DTOs. Client-supplied `id` or `userId` overrides on update are completely ignored.

 |
| **Stateless sessions, CSRF disabled** | The API uses bearer tokens and never sets a session cookie, so there is nothing for a cross-site request to hijack.

 |
| **Every failure uses the app's error format** | `RestAuthenticationEntryPoint`, `RestAccessDeniedHandler`, and `GlobalExceptionHandler` produce JSON responses carrying `{"message", "timestamp"}`. A 401 carries `WWW-Authenticate: Bearer`.

 |

## What the tasks API accepts

| Claim | Requirement |
| --- | --- |
| header `alg` | `RS256`<br> |
| `iss` | equals `jwt.issuer`<br> |
| `aud` | contains `jwt.audience` (the users service issues it as an array)

 |
| `sub` | present and not blank

 |
| `uid` | present, a JSON number, a positive whole number (the user's database id)

 |
| `exp` | present, and not more than 30 seconds in the past

 |
| `roles` | optional list of strings. Only entries starting with `ROLE_` are used.

 |

A token is rejected for any of these reasons:

| Reason | Cause |
| --- | --- |
| `EXPIRED` | past `exp` plus the 30s skew

 |
| `BAD_SIGNATURE` | signed with a different key, or the payload was changed

 |
| `MALFORMED` | not a valid JWT, or any other failure (wrong algorithm, missing subject, expiry or valid `uid`, wrong issuer or audience)

 |
| `EMPTY` | the token is blank

 |

Wrong issuer, wrong audience, HS256 and unsigned tokens all report `MALFORMED`. The client sees a 401 with the message for that reason (for example `Token has expired`).

Tokens cannot be revoked. A token stays valid until it expires, so keep the lifetime short on the users service.

## Access rules

| Endpoint | Request DTO | Access Rule |
| --- | --- | --- |
| `GET /api/tasks` | N/A | `ADMIN` or `MANAGER`<br> |
| `GET /api/tasks/{id}` | N/A | Any authenticated user; plain users only for their own task (other tasks return 404)

 |
| `GET /api/tasks/user/{userId}` | N/A | `ADMIN`, `MANAGER`, or the user themselves (`@userSecurity.isSelf(#userId, authentication)`)

 |
| `POST /api/tasks` | `CreateTaskRequest` (`@Valid`)

 | `ADMIN` or `MANAGER`<br> |
| `PUT /api/tasks/{id}` | `UpdateTaskRequest` (`@Valid`)

 | `ADMIN` or `MANAGER`<br> |
| `PUT /api/tasks/{id}/complete` | N/A | Any authenticated user; plain users only for their own task (other tasks return 404)

 |
| `DELETE /api/tasks/{id}` | N/A | `ADMIN` or `MANAGER`<br> |

There is no public endpoint: everything else in `SecurityConfig` is `anyRequest().authenticated()`, and the role rules are `@PreAuthorize` annotations on the controller.

**Ownership.** Two routes are open to any signed-in user and add an ownership rule on top of the role rules: `GET /api/tasks/{id}` and `PUT /api/tasks/{id}/complete`. ADMIN and MANAGER may use them on any task. Anyone else may use them only on a task whose `userId` equals the `uid` in their token. Otherwise the response is a **404**, identical to a task that does not exist, and nothing is changed. `TaskAccessPolicy` holds the rule, and it refuses when in doubt (unexpected principal, task with no owner).

The list route `GET /api/tasks/user/{userId}` has a *user* id in its path, so it uses `@userSecurity.isSelf(#userId, authentication)`, which compares that id with the token's `uid` and needs no lookup. **Never use `isSelf` on a path that holds a task id**: a task id is not a user id, and comparing them lets one user's id open another user's task. Task routes read the owner from the task instead, through `TaskAccessPolicy`.

A missing or invalid token gets a 401. An authenticated user without the role gets a 403.

## Configuration

| Property | Environment variable | Default |
| --- | --- | --- |
| `jwt.public-key-path` | `JWT_PUBLIC_KEY_PATH` | none (required)

 |
| `jwt.issuer` | `JWT_ISSUER` | `user-service`<br> |
| `jwt.audience` | `JWT_AUDIENCE` | `taskflow-api`<br> |
| `server.port` | `APP_PORT` | `8077`<br> |

The tasks API must use **exactly the same issuer and audience** as the users service. If they differ, every token is rejected with a 401 that looks like a signature problem.

There is no `jwt.secret` and no `jwt.expiration-ms`. Delete `JWT_SECRET_ENV` from any old `.env`.

## Keys

The tasks API needs only `public.pem`, the public half of the pair created for the users service. The file must be an X.509 public key, whose first line is `-----BEGIN PUBLIC KEY-----`. This is what `openssl rsa -pubout` produces.

* `public.pem` is not secret, but never give the tasks API `private.pem`. It refuses to start with a private key.


* A `-----BEGIN RSA PUBLIC KEY-----` file is PKCS#1 and is rejected. Regenerate it from the private key with `openssl rsa -pubout -in private.pem -out public.pem`.


* If the key pair is regenerated or rotated on the users service, replace `public.pem` here and restart. Existing tokens stop working.



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

* Compose does not expand `~`, so the default uses `${HOME}`.


* `public.pem` is created world-readable, so the non-root `spring` user can read it.


* The database is published on host port `5433` by default so it does not clash with the users database (host port `5422`) or a local Postgres on `5432`.


* `JWT_ISSUER_ENV` and `JWT_AUDIENCE_ENV` must be the same as in the users service's `.env`.



## Testing

| Test | What it covers |
| --- | --- |
| `PemKeyLoaderTest` | Key loading, including a missing file, an empty file, a private key, a PKCS#1 key, and a corrupt body

 |
| `JwtServiceTest` | Accepted and rejected tokens (expired beyond and within skew, wrong signer, tampered payload, wrong issuer or audience, HS256, unsigned, missing subject/expiry/uid, garbage, empty), role filtering, and audience as an array

 |
| `JwtAuthFilterTest` | No header, non-Bearer scheme, valid token, case-insensitive scheme matching, token trimming, and entry point error recording

 |
| `RestAccessDeniedHandlerTest` | Verifies that 403 access denied exceptions are dispatched cleanly to `GlobalExceptionHandler`<br> |
| `SecurityEndToEndTest` | Full HTTP security filter flow: 200, 401 (none, expired, wrong key, HS256, garbage), 403, and Spring 404/405 error shape preservation

 |
| `TaskControllerSecurityTest` | Matrix of 8 routes x 5 caller types (`ROLE_USER`, `ROLE_MANAGER`, `ROLE_ADMIN`, `NO_ROLES`, anonymous). Asserts `taskService` is never touched on denied calls

 |
| `TaskControllerTest` | Request body validation, `@Valid` DTO constraints, missing field error messages, 400/404/415 mapping, and stripping client-supplied `id` or `userId`<br> |
| `TaskControllerOwnershipTest` | Ownership checks on `GET /{id}`, `PUT /{id}/complete`, and `GET /api/tasks/user/{userId}` across owners, non-owners, managers, and admins. Verifies non-owner queries return exact 404 missing task representation

 |
| `TaskAccessPolicyTest` | Task ownership authorization policy in isolation, including fail-closed scenarios

 |
| `UserSecurityTest` | `isSelf` rule validation comparing requested user IDs with token `uid` claims

 |
| `TaskServiceImplTest` | Business logic mocking repository interactions, missing task exceptions, and immutability of task ownership/ID

 |
| `smoke-test.sh` | End-to-end stack verification against live users and tasks containers

 |

Tests never use your real keys. `TestJwtKeys` generates a throwaway RSA pair per run and registers the properties through `@DynamicPropertySource`. **Every `@SpringBootTest` needs the same registration**, plus H2 settings (`spring.datasource.driver-class-name=org.h2.Driver`), because the main properties use PostgreSQL. Spring Boot 4 test annotations use `@MockitoBean` from `org.springframework.test.context.bean.override.mockito` and `@AutoConfigureMockMvc` from `org.springframework.boot.webmvc.test.autoconfigure`.

Run unit and integration tests:

```bash
mvn clean test

```

### Smoke test

The users stack must already be running. Run it from the tasks project root, on the host:

```bash
chmod +x smoke-test.sh
./smoke-test.sh --up --restart

```

It checks that:

1. Missing/garbage tokens get 401 with `WWW-Authenticate: Bearer`.


2. Real user service tokens pass contract checks (`RS256`, issuer, audience, `uid`, roles).


3. Tampered, unsigned, HS256-forged, and wrong-key tokens are rejected with 401.


4. Invalid audience, invalid issuer, or tokens expired beyond the 30-second skew are rejected.


5. Ownership workflows create throwaway users, verify read/complete isolation (404 for non-owners), and clean up.


6. With `--restart`, pre-existing tokens remain valid after restarting `tasks-service`.



## Troubleshooting

| Symptom | Likely cause |
| --- | --- |
| `Could not resolve placeholder 'jwt.public-key-path'` | `JWT_PUBLIC_KEY_PATH` is missing in environment or `@DynamicPropertySource` omitted in `@SpringBootTest`<br> |
| `Could not load public key ... NoSuchFileException` | Wrong path, or `public.pem` is not mounted into the container volume

 |
| `Could not load public key ... File contains a private key` | `private.pem` was mounted instead of `public.pem`<br> |
| `Could not load public key ... PKCS#1` | Public key starts with `BEGIN RSA PUBLIC KEY`. Convert to X.509 using `openssl rsa -pubout`<br> |
| `docker compose up` fails on secret mount | Host key file missing at `${HOME}/.taskflow/keys/public.pem` or path specified in `JWT_PUBLIC_KEY_FILE`<br> |
| Every token rejected with 401 after key or config change | Issuer or audience mismatch between services, or public key does not match users service private key

 |
| `Unable to determine Dialect without JDBC metadata` | Database unreachable (wrong host/port, container down, or incorrect credentials)

 |
| `400 Bad Request` with `Malformed request body` | Sent invalid primitive types or malformed JSON instead of valid DTO structures

 |
| A failed `@PreAuthorize` returns 500 | `GlobalExceptionHandler` missing its `AccessDeniedException` handler

 |

## Known limitations

* **Revocation:** Tokens remain valid until expiration; no real-time blacklist or revocation endpoint exists.


* **Key rotation:** Uses a single static public key. Key rotation requires updating `public.pem` and restarting the application (JWKS / `kid` support is planned for future iterations).


* **Schema:** `spring.jpa.hibernate.ddl-auto=create` recreates tables on startup. Transition to `update` or Flyway before production deployment.