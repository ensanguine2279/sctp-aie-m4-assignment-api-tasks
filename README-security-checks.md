# Security checks

The tasks API runs its security checks in two CI jobs (`.github/workflows/ci.yml`): `dependency-check`
and `security`. They run in parallel with the tests, and **nothing is released, built, pushed or deployed
unless both pass**. The users service runs the same checks with the same files.

| Objective | Check | Where |
|---|---|---|
| Scan dependencies for known vulnerabilities | OWASP Dependency-Check 13.0.0, fails on CVSS 7.0 or higher. Data from the NVD API when the `NVD_API_KEY` secret is set, otherwise from Dependency-Check's daily NVD mirror | `dependency-check` job, plugin in `pom.xml` |
| Scan the Docker image (optional) | Trivy, fails on fixable HIGH or CRITICAL CVEs in the base image and the jars | `security` job, step *Vulnerability scan* |
| No secrets committed | gitleaks over the **whole git history** (passwords, tokens, API keys, private keys) | `security` job, step *Secret scan* |
| No secrets committed | `scripts/security-check.sh`: key files, private keys (PEM or base64), default passwords in properties and compose files, key handling in `.dockerignore` and `Dockerfile`. For this service it also fails on any token-signing code in `src/main`, because the tasks API only verifies tokens | `security` job, step *Key and config hygiene* |
| No secrets in the image | The same script over the built image's files, plus its environment variables | `security` job, step *Check the image for keys* |
| Run the checks in CI | All of the above on every push to `main` | `ci.yml` |

## Running the checks locally

From the project root:

```bash
# Secrets and key handling (seconds)
./scripts/security-check.sh

# Secrets in the whole git history (install: https://github.com/gitleaks/gitleaks)
gitleaks git --redact --verbose .

# Dependencies, without an NVD API key: uses Dependency-Check's daily mirror of the NVD data
mvn dependency-check:check \
  -DnvdDatafeedUrl='https://dependency-check.github.io/DependencyCheck_Builder/nvd_cache/nvdcve-{0}.json.gz'
# or, with a key (free from https://nvd.nist.gov/developers/request-an-api-key), straight from the NVD API:
#   export NVD_API_KEY=...
#   mvn dependency-check:check
# The first run downloads the whole database and takes a while; later runs only fetch changes.
# report: target/dependency-check-report.html

# Container image (install: https://trivy.dev). --pull fetches the newest base image, as CI does.
docker build --pull -t taskflow-tasks:local .
trivy image --severity HIGH,CRITICAL --ignore-unfixed taskflow-tasks:local
```

## Where the secrets actually live

| Secret | Where it is kept |
|---|---|
| RS256 public key (not secret, but the only key this service holds) | `~/.taskflow/keys/public.pem` on the host, mounted read-only as the `jwt_public_key` Docker secret. The private key never comes near this service. |
| Database password | `.env` (git-ignored), passed to compose. `.env.example` lists the names only. |
| Docker Hub token, SSH key, NVD API key (optional) | GitHub repository secrets |

`.gitignore` excludes `*.pem` and `.env`, and `.dockerignore` keeps both out of the image build.

## Findings from the first runs

<!-- Fill in after the first CI run: one row per finding, and what was done about it. -->

| Check | Finding | Resolution |
|---|---|---|
| Dependency-Check | | |
| Trivy | | |
| gitleaks | | |
| `security-check.sh` | | |

## Handling findings

- **Dependency-Check or Trivy fails:** upgrade the library or base image. Upgrading the Spring Boot parent is usually the cleanest fix, because it updates Tomcat and the other managed libraries together. If a finding is a false positive, suppress it in `dependency-check-suppressions.xml` (or `.trivyignore`) with a reason and an expiry date.
- **gitleaks fails:** treat the secret as leaked even after deleting it, because it stays in the history. Revoke or rotate it first, then remove it. A reviewed finding in an old commit goes into `.gitleaksignore` (by fingerprint, with a comment). A pattern that is never a secret goes into `.gitleaks.toml` as a narrow allowlist entry.
- **`security-check.sh` fails:** it prints the file and line and what to change.
