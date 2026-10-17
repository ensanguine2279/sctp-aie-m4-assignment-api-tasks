# Dockerfile Walkthrough

## Build Stage

```bash
COPY .mvn/ .mvn & COPY mvnw pom.xml ./

```

* Copies only the Maven Wrapper executable scripts (`mvnw`), configuration folder (`.mvn/`), and the project's dependency definition (`pom.xml`) into the container image.


* Why split this from `COPY src ./src`: Docker caches each build step (layer). If your Java code in `src/` changes but `pom.xml` stays the same, Docker skips re-downloading all your libraries and reuses the cached layer from previous builds.



```bash
RUN chmod +x mvnw

```

* Grants executable permissions (`+x`) to the `mvnw` shell script inside the Linux container environment.


* Why it is needed: File permission bits can sometimes be lost or altered when committing files across different operating systems (e.g., Windows git checkouts), resulting in a permission denied error when Docker tries to run `./mvnw`.



```bash
RUN ./mvnw dependency:go-offline

```

* Tells Maven to resolve and download all plugins and dependencies listed in `pom.xml` into the container's local Maven repository (`~/.m2/repository`).


* This downloads all heavy JAR dependencies (Spring Boot, Spring Security, JJWT, PostgreSQL driver, etc.) into a dedicated Docker layer before your source code (`src/`) is copied.



```bash
RUN ./mvnw clean package -DskipTests

```

Tests are explicitly skipped during the container build process.

* Build Time: Executing test suites (especially integration tests) on every docker build significantly slows down image creation during development or deployment.


* Hermetic Environment: Tests during container packaging may require mock database connections, external configurations, or active network resources that are not present inside the isolated Docker build context.


* Keys: The security tests use `TestJwtKeys` to generate throwaway RSA key pairs dynamically, so they never need a key file on disk, and no key material is copied into the build.



Separate Test Execution from Container Packaging

* In CI/CD pipelines, testing and packaging are split into two separate pipeline stages. By verifying test suites before invoking `docker build`, this ensures that broken code never reaches the Docker packaging step while keeping container compilation fast.



## Runtime Stage

```bash
RUN addgroup -S spring && adduser -S spring -G spring

```

`addgroup -S spring`: Creates a system group (`-S`) named `spring`.

`adduser -S spring -G spring`: Creates a system user (`-S`) named `spring` and assigns it to the `spring` group (`-G spring`).

Note: The `-S` flag is Alpine Linux notation for "system account" (unprivileged, no home directory password prompt).

```bash
USER spring:spring

```

* Switches the container's execution context from `root` (the default user in Docker) to the newly created `spring` user and `spring` group.


* Any subsequent instructions in the Dockerfile (like `ENTRYPOINT` or `CMD`) will run with `spring` user permissions rather than system administrator (`root`) privileges.



### Why is this important

By default, processes inside a Docker container execute as `root`. If an attacker exploits a vulnerability in the Spring Boot application (e.g., remote code execution or arbitrary file read):

As `root`: The attacker gains full privileges inside the container, making it easier to compromise internal files, modify system processes, or perform container escape attacks to compromise the host kernel.

As `spring` (Non-Root): The process is constrained by Least Privilege. The attacker cannot modify system binaries, install software packages, or access protected OS files inside the container.

**Note on the mounted key:** The Tasks API reads its RSA public key from a file mounted at runtime. Unlike the private key in the Users Service (which requires mode `600`), `public.pem` is world-readable (`644`), meaning the non-root `spring` user can read the mounted key directly without requiring a `--user` override. The Tasks API's `PemKeyLoader` only accepts an X.509 public key (`BEGIN PUBLIC KEY`) and strictly refuses files containing private keys (`BEGIN PRIVATE KEY` or `BEGIN RSA PRIVATE KEY`) to prevent key misuse.

```bash
"-XX:+UseG1GC"

```

* Explicitly enables the Garbage-First (G1) Garbage Collector.


* G1 GC is designed for multi-threaded applications with medium-to-large heaps. It minimizes stop-the-world garbage collection pauses by processing memory regions concurrently in small increments, maintaining low latency for web applications under concurrent traffic.



```bash
"-XX:MaxRAMPercentage=75.0"

```

* Dynamically sets the maximum Java Heap Size (`-Xmx`) to 75% of the container's available RAM.


* Standard JVM flags like `-Xmx512m` hardcode heap sizes, which can lead to issues in containerized environments. By using `MaxRAMPercentage`, the JVM automatically calculates its heap dynamically based on the memory limit assigned to the Docker container (e.g., via `deploy.resources.limits.memory` in Docker Compose or Kubernetes).


* Why 75% instead of 100%?: The remaining 25% of allocated memory is reserved for off-heap JVM overhead (Metaspace, thread stacks, garbage collection structures, and native memory allocations). Reserving this headroom prevents the Linux kernel Out-Of-Memory (OOM) Killer from abruptly terminating the container.



```bash
ENTRYPOINT ["java", "-XX:+UseG1GC", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]

```

* Exec Form vs. Shell Form Syntax: Notice the use of bracketed array syntax (`["java", ...]`) rather than a single string.


* Exec Form (`ENTRYPOINT ["java", ...]`) — Used Here: Runs `java` directly as `PID 1` inside the container. This allows OS termination signals (`SIGTERM`, `SIGINT`) sent by Docker or Kubernetes to pass directly to the Java process, enabling graceful application shutdown (closing database connection pools, completing active requests).



Shell Form (`ENTRYPOINT java -jar app.jar`): Spawns `/bin/sh -c` as `PID 1`, wrapping `Java` as a child process. This blocks OS signals from reaching the JVM, causing the container to forcibly terminate after timeout limits.

### Keys are not part of the image

Nothing in this Dockerfile copies a key. The build stage copies only `.mvn/`, `mvnw`, `pom.xml`, and `src`, and the runtime stage copies only the built JAR. The public key reaches the container at runtime as a mounted read-only file (`/run/secrets/jwt_public_key` under Compose), so the image can be pushed and shared without baking secret path assumptions into the image layers. As a safety net against keys or local configurations ending up in the build context by accident, add a `.dockerignore` next to the Dockerfile:

```
*.pem
.env

```

## Building the Image

Run the `docker build` command from the root directory of the Tasks API (where the `Dockerfile` is located):

```bash
docker build -t task-service:test .

```

* `-t task-service:test`: Tags the built image with the name `task-service` and tag `test` (can be anything meaningful).
* `.`: Specifies the current directory as the build context.



Check that the image exists in the local Docker engine registry:

```bash
docker images | grep task-service

```

## Running the Container

You can run the container in isolation using a shell command or a script like `run-container.sh`. Since the database isn't running yet, pass dummy environment variables to verify that the JVM boots. The service also needs its RSA public key, which is mounted as a read-only file, the same way Compose mounts its secret:

```bash
set -euo pipefail

docker run --rm -p 8077:8077 \
  -e DB_HOST=localhost \
  -e DB_PORT=5433 \
  -e DB_NAME=taskflow-tasks \
  -e DB_USER=postgres \
  -e DB_PASSWORD=postgres \
  -e JWT_PUBLIC_KEY_PATH=/run/secrets/jwt_public_key \
  -v "$HOME/.taskflow/keys/public.pem:/run/secrets/jwt_public_key:ro" \
  task-service:test

```

* `-e`: The `errexit` option tells the shell to exit immediately if any command fails (returns a non-zero exit status).


* `-u`: Exit if an unset/uninitialized variable is referenced (prevents accidental empty string bugs).


* `-o pipefail`: Exits if any command in a pipeline fails (e.g., `command1 | command2` exits if command1 fails, whereas standard `set -e` only checks `command2`).


* `--rm`: Automatically deletes the container instance when it stops.


* `-p 8077:8077`: Maps port 8077 of your host machine to port 8077 inside the container.


* `-e KEY=VALUE`: Passes environment variables to satisfy `application.properties` / `application.yml`.


* `-v host:container:ro`: Mounts the public key file read-only inside the container. `JWT_PUBLIC_KEY_PATH` is the path inside the container, not on your host machine.


* Note: Unlike the Users Service, `--user "$(id -u):$(id -g)"` is optional here because `public.pem` is world-readable (`644`), allowing the non-root `spring` user inside the container to read it directly.



### Context: Verification Relationship with Users Service

For system context, the Tasks API relies exclusively on `public.pem` to verify RS256 signatures on incoming JWTs issued by the Users Service (`user-service`). The Tasks API contains no token-issuing logic, no private key loader, and no password encoders. Both services must share identical `JWT_ISSUER` (`user-service`) and `JWT_AUDIENCE` (`taskflow-api`) configurations.

### What Will Happen

1. Spring Boot Boots Up: The JRE will initialize with the JVM flags specified in your Dockerfile, read the passed `-e` environment variables, and start the application context.


2. Terminal Feedback: You will see the Spring Boot ASCII banner and logger initialization statements in your console output.


3. Database Connection Timeout (Expected): Since `DB_HOST=localhost` points inside the isolated container where no PostgreSQL instance exists, HikariCP will log connection retries and eventually throw a connection exception.


4. Clean Exit: Once you stop the process with `Ctrl + C`, Docker will clean up and delete the container instance automatically because of the `--rm` flag.



### Checking the key

The public key is loaded when `JwtService` is initialized. If the mounted key is missing or unreadable, the log shows `Could not load public key from /run/secrets/jwt_public_key`, followed by the cause (`NoSuchFileException` or `AccessDeniedException`). If a private key is accidentally mounted instead of a public key, `PemKeyLoader` rejects it immediately with an exception stating that the file contains a private key. If `jwt.public-key-path` is not configured, startup fails immediately with `Could not resolve placeholder 'jwt.public-key-path'`. None of these messages leaks key contents. To check key loading and end-to-end authentication against real tokens, use the Compose setup (`docker-compose.yml`) or the `./smoke-test.sh` script instead.