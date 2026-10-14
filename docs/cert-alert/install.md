# Installation

This page covers getting CertAlert running: from source, as a container image, and with the
bundled Docker Compose demo. For every setting referenced here see
[Configuration](configuration.md).

## Prerequisites

| Requirement | Notes |
|---|---|
| **Java 21** | Needed only to build or run the jar directly. The Gradle toolchain is pinned to Java 21. The container image carries its own JRE. |
| **Docker / BuildKit** *(container route)* | The Dockerfile uses a BuildKit cache mount (`RUN --mount=type=cache`), the default in current Docker. |
| **PostgreSQL** *(production)* | Any version supported by the bundled PostgreSQL driver; the compose demo uses `postgres:17-alpine`. H2 is the default for trying the application out only. |
| **LDAP directory** | Publishing `icOrgPerson` / `icOrgServer` entries (or equivalents, with attribute names mapped in configuration). A read-only service account is sufficient unless you enable certificate clean-up or editable attributes. |
| **SMTP server** *(optional)* | For the email round-up and email alert channel. |
| **Keystores** *(optional)* | A PKCS#12 key store and trust store if you want TLS and X.509 client-certificate sign-in. |

## Option 1: try it locally with the `dev` profile

The `dev` profile runs against an **embedded sample directory** (four people, four servers, a
mix of valid, expiring and expired certificates) and an in-memory H2 database, so no LDAP server
or database is needed.

```bash
./gradlew bootRun --args='--spring.profiles.active=dev'
```

Open <http://localhost:8080/users> and sign in as **`alice` / `password`** (everyone in the sample
uses that password; alice is the configured administrator). Then press **Sync directory**.

!!! note "What the dev profile changes"
    - In-memory H2 (`jdbc:h2:mem:cert-alert`) and the H2 console at `/h2-console`.
    - Embedded LDAP on port `18389`, base `dc=example,dc=test`, loaded from `dev-directory.ldif`.
    - **Scheduled jobs are off** (`cert-alert.ldap.sync.enabled: false`); sync from the UI.
    - All actuator endpoints exposed, health details always shown, `DEBUG` logging.
    - Tiny LDAP page and batch sizes so the paging loop is exercised.

    The embedded directory is a `developmentOnly` dependency and is **not** in the built jar or
    the container image, so the image cannot run the `dev` profile.

## Option 2: build from source

```bash
./gradlew test    # run the test suite
./gradlew build   # compile, test, and produce the executable jar
java -jar build/libs/cert-alert-0.0.1-SNAPSHOT.jar
```

Run it against a real directory and PostgreSQL by activating the `postgres` profile and
supplying credentials through the environment:

```bash
export CERT_ALERT_LDAP_URL=ldaps://directory.example.gov:636
export CERT_ALERT_LDAP_BASE=dc=example,dc=gov
export CERT_ALERT_LDAP_USER='cn=cert-alert,ou=services,dc=example,dc=gov'
export CERT_ALERT_LDAP_PASSWORD=...
export CERT_ALERT_DB_URL=jdbc:postgresql://localhost:5432/certalert
export CERT_ALERT_DB_USER=certalert
export CERT_ALERT_DB_PASSWORD=...
export CERT_ALERT_SECURITY_ADMIN_IDENTIFIERS=alice@example.gov
java -jar build/libs/cert-alert-0.0.1-SNAPSHOT.jar --spring.profiles.active=postgres
```

!!! warning "Name at least one administrator"
    `cert-alert.security.admin-identifiers` is empty by default. Without an administrator nobody
    can trigger the first sync from the UI. See [Security](security.md#roles).

Flyway creates and migrates the schema on startup. Hibernate is set to `validate`, so a mapping
that drifts from the migrations fails at boot rather than silently altering tables.

## Option 3: container image

### Pull a published image

CI builds the image on every push, starts it, waits for `/actuator/health/liveness` to answer,
and only then publishes it to the GitHub Container Registry:

```bash
docker pull ghcr.io/winllc/cert-alert:latest        # the default branch
docker pull ghcr.io/winllc/cert-alert:sha-1a2b3c4   # one commit, forever
```

| Tag | Published when | Moves? |
|---|---|---|
| `sha-<short>` | every push | never: **deploy this one** |
| `<branch>` | every push to that branch | yes |
| `latest` | pushes to the default branch | yes |
| `<version>`, `<major>.<minor>` | a `v*` git tag | `<major>.<minor>` moves |

Nothing is published from a pull request.

### Build it yourself

```bash
docker build -t cert-alert:latest .
```

The Dockerfile is a three-stage build (build the jar, split it into layers, assemble a runtime
image) on Red Hat's Universal Base Image. The base images are build arguments so a real build can
pin them:

```bash
docker build \
  --build-arg UBI_JDK_IMAGE=registry.access.redhat.com/ubi9/openjdk-21:1.23-1 \
  --build-arg UBI_JRE_IMAGE=registry.access.redhat.com/ubi9/openjdk-21-runtime:1.23-1 \
  -t cert-alert:latest .
```

| Build argument | Default | Purpose |
|---|---|---|
| `UBI_JDK_IMAGE` | `registry.access.redhat.com/ubi9/openjdk-21:latest` | Build-stage image |
| `UBI_JRE_IMAGE` | `registry.access.redhat.com/ubi9/openjdk-21-runtime:latest` | Runtime image |
| `RUN_TESTS` | `false` | `true` runs `./gradlew build` (with tests) instead of `bootJar -x test` |
| `VERSION` | `0.0.1-SNAPSHOT` | OCI `version` label |
| `REVISION` | `unknown` | OCI `revision` label |

### Image facts

- Runs as **uid 185** (the base image's own user), with files owned by `185:0` and group
  permissions equal to owner permissions, so it starts under OpenShift's arbitrary-uid policy.
- Exposes **8080** (HTTP) and **8443** (intended for TLS). The application listens on
  `SERVER_PORT`, which the image sets to `8080`.
- `JAVA_OPTS` defaults to `-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError`, so the heap
  follows the container's memory limit.
- The JVM is PID 1 (exec form), receives `SIGTERM` directly and shuts down gracefully.
- `/app/data` exists for the default H2 file database; a PostgreSQL deployment writes nothing
  there.
- A Docker `HEALTHCHECK` curls `http://localhost:${SERVER_PORT}/actuator/health` every 30s.
  See the note on probes in the [Operator guide](operator-guide.md#health-checks).
- `.dockerignore` excludes `*.p12`, `*.jks`, `*.pem` and `*.key` so key material cannot be baked
  in. Mount keystores as a volume or secret.

### Run it

```bash
docker run --rm -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=postgres \
  -e CERT_ALERT_DB_URL=jdbc:postgresql://db:5432/certalert \
  -e CERT_ALERT_DB_USER=certalert \
  -e CERT_ALERT_DB_PASSWORD=... \
  -e CERT_ALERT_LDAP_URL=ldaps://directory.example.gov:636 \
  -e CERT_ALERT_LDAP_BASE=dc=example,dc=gov \
  -e CERT_ALERT_LDAP_USER='cn=cert-alert,ou=services,dc=example,dc=gov' \
  -e CERT_ALERT_LDAP_PASSWORD=... \
  -e CERT_ALERT_SECURITY_ADMIN_IDENTIFIERS=alice@example.gov \
  cert-alert:latest
```

## Option 4: Docker Compose (demo stack)

`docker-compose.yml` brings up three services and starts the application only once the other
two are healthy:

| Service | Image | Purpose | Host port |
|---|---|---|---|
| `postgres` | `postgres:17-alpine` | Database `certalert` / user `certalert` | not published |
| `directory` | built from `docker/389ds/Dockerfile` | Stand-in **389 Directory Server** with the IC FSD schema, generated people and servers, and the retro changelog enabled | `1389` → `3389` |
| `cert-alert` | built from the repo `Dockerfile` | The application (`postgres` profile) | `${CERT_ALERT_PORT:-8181}` → `8080` |

```bash
docker compose up --build
```

Sign in at <http://localhost:8181> as **`alice` / `password`** and press **Sync directory**.

!!! danger "Demo only"
    The stand-in directory serves plaintext LDAP and every account has a default password. It is
    for trying CertAlert out, not for deployment.

### Compose variables

| Variable | Default | Effect |
|---|---|---|
| `CERT_ALERT_PORT` | `8181` | Host port for the application (container still listens on 8080) |
| `CERT_ALERT_DB_PASSWORD` | `certalert` | PostgreSQL password (both services) |
| `CERT_ALERT_LDAP_URL` | `ldap://directory:3389` | Directory the application reads |
| `CERT_ALERT_LDAP_BASE` | `dc=example,dc=test` | Suffix (also the stand-in's suffix) |
| `CERT_ALERT_LDAP_USER` | `cn=cert-alert,ou=services,dc=example,dc=test` | Service account DN |
| `CERT_ALERT_LDAP_PASSWORD` | `cert-alert` | Service account password (also set on the stand-in) |
| `CERT_ALERT_LDAP_ADMIN_PASSWORD` | `directory-manager` | Stand-in `cn=Directory Manager` password |
| `CERT_ALERT_DUMMY_USERS` | `100` | People generated on the stand-in's first boot |
| `CERT_ALERT_DUMMY_SERVERS` | `40` | Servers generated on first boot |
| `CERT_ALERT_LDAP_USER_SEARCH_BASE` | `ou=people` | Password sign-in search base |
| `CERT_ALERT_ADMINS` | `alice@intelink.ic.gov` | Administrator identifiers |
| `GIT_REVISION` | `unknown` | Image `REVISION` build argument |

The compose file also sets `CERT_ALERT_LDAP_REQUIRE_EMAIL_POC=false`, because the generated data
writes `serverPOC` both as addresses and as names.

=== "Larger data set"

    ```bash
    CERT_ALERT_DUMMY_USERS=5000 CERT_ALERT_DUMMY_SERVERS=1500 docker compose up --build
    ```

    Data is generated only when the `ldap-data` volume is empty. Reset with `docker compose down -v`.

=== "Different host port"

    ```bash
    CERT_ALERT_PORT=9090 docker compose up --build
    ```

=== "A real directory"

    ```bash
    CERT_ALERT_LDAP_URL=ldaps://directory.example.gov:636 \
    CERT_ALERT_LDAP_BASE=dc=example,dc=gov \
    CERT_ALERT_LDAP_USER='cn=cert-alert,ou=services,dc=example,dc=gov' \
    CERT_ALERT_LDAP_PASSWORD=... \
    docker compose up --build
    ```

    The stand-in keeps running; nothing reads it.

=== "Try the changelog connector"

    Add `CERT_ALERT_LDAP_CHANGELOG_ENABLED: "true"` to the `cert-alert` service environment. The
    stand-in publishes `cn=changelog` and grants the service account read access to it.

### The stand-in directory

- On first boot (empty `/data` volume) it creates a `userRoot` backend, generates an LDIF with
  `scripts/generate-directory-data.sh` (or loads `*.ldif` files mounted at `/bootstrap`, in name
  order), imports it offline with `ldif2db`, enables the retro changelog and grants the service
  account read access to `cn=changelog`.
- Access control (`docker/389ds/bootstrap/00-base.ldif`): the service account reads everything
  except `userPassword`, and may write the managed attributes under `ou=servers` and
  `userCertificate`.
- Query it from the host:

    ```bash
    ldapsearch -x -H ldap://localhost:1389 \
      -D 'cn=cert-alert,ou=services,dc=example,dc=test' -w cert-alert \
      -b 'dc=example,dc=test' '(objectClass=icOrgServer)' cn serverPOC
    ```

- It serves plain LDAP only; X.509 sign-in still needs keystores of your own.

### Generating directory data yourself

`scripts/generate-directory-data.sh` needs only bash and openssl:

```bash
./scripts/generate-directory-data.sh --users 500 --servers 200 > directory.ldif
ldapadd -c -x -H ldap://localhost:1389 -D 'cn=Directory Manager' -w directory-manager -f directory.ldif
```

| Option | Default | Meaning |
|---|---|---|
| `--users N` | `100` | People to generate |
| `--servers N` | `40` | Servers to generate |
| `--base DN` | `dc=example,dc=test` | Directory suffix |
| `--out FILE` | stdout | Output file |
| `--no-certificates` | off | Skip certificate minting (much faster for large volumes) |

Output is deterministic for the same arguments except the certificates, which are minted against
the clock across every state (valid, warning window, critical window, expired, absent) with a mix
of RSA-2048/4096, EC P-256 and some RSA-1024/SHA-1.

## First start checklist

1. **Database**: the log shows Flyway applying migrations `V1` … `V17` on an empty database.
2. **Liveness**: `curl -fsS http://localhost:8080/actuator/health/liveness` returns `{"status":"UP"}`.
3. **Directory**: `/actuator/health` includes the LDAP indicator; it reports `DOWN` if the
   directory is unreachable (details are shown only to administrators).
4. **Sign in** with a directory password as one of the `admin-identifiers`. The account menu
   should read *Administrator*.
5. **Populate the cache**: press **Sync directory** on the People or Servers page (or
   `POST /api/v1/sync`). Badges show entries seen and certificates cached per job. Alternatively
   wait for the nightly sweeps (02:00 and 04:00 UTC).
6. **Verify**: the People and Servers stat cards show totals; `GET /api/v1/sync/runs` shows
   `COMPLETED` runs.
7. **X.509 sign-in** works only after the first sync, because client certificates are matched
   against certificates already cached from the directory.

Next: [Configuration](configuration.md) and the [Operator guide](operator-guide.md).
