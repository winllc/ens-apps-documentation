# Installation

You can run the Directory Services Portal in several ways:

| Option | Use it for | Needs |
|---|---|---|
| [Demo quick start](#demo-quick-start) | Trying the portal, development | Java 21, Node.js 20+ |
| [Build from source and run](#building-from-source) | A simple non-container install | Java 21, Node.js 20+, a web server |
| [Docker Compose from the source tree](#docker-compose-from-the-source-tree) | Container deployment, demo with OpenLDAP | Docker / Podman with Compose |
| [Docker deployment package](#docker-deployment-package) | Production hosts without the source tree or internet access | Docker Engine 20.10+, Compose v2 |
| [Native deployment package](#native-deployment-package) | Production hosts without containers | Linux with systemd, Java 21+, optionally nginx |

## Prerequisites

| Component | Requirement |
|---|---|
| Java | 21 or later (the backend ships the Maven wrapper `backend/mvnw`, so Maven is not needed) |
| Node.js | 20 or later, with npm (to build the web client) |
| LDAP server | Any LDAPv3 server reachable over `ldap://` or `ldaps://` (optional StartTLS). Not needed for the demo |
| Service account | An LDAP account that can read the schema (root DSE `subschemaSubentry`) and the namespaces you expose, and write wherever portal users should be able to edit |
| Container runtime | Docker or Podman with Compose v2, for the container options |
| Disk | About 2 GB for the Docker deployment package |

!!! note "Network access for image builds"
    Building the `web` and `api` images applies the latest Red Hat updates (`yum update` /
    `microdnf update`), so the build needs access to the UBI repositories
    (`cdn-ubi.redhat.com`) and `registry.access.redhat.com`.

## Demo quick start

The demo needs no LDAP server. In `memory` mode (the default, `DIRECTORY_MODE=memory`), the API
starts an **embedded LDAP server** (UnboundID in-memory directory) on a loopback port. The portal
talks to it over the LDAP protocol, exactly as it would to a production server.

```bash
git clone <repository-url> directory-services-portal
cd directory-services-portal
npm install
npm run dev            # API on :3001, web client on http://localhost:5173 (proxies /api)
```

Open <http://localhost:5173>. The demo directory loads:

- the standard schema and a non-standard `acme*` schema (`acmePerson`, `acmeDevice`,
  `acmePartner`)
- about ten people, plus groups and devices
- example forms, directory definitions, permissions, two dashboards (*Workforce*, *IT assets and
  groups*) and two analyses (*Staff vs. white pages*, *People data quality*)

Every demo password is `password`:

| User | What they see |
|---|---|
| `admin` | Administrator: everything, including the admin pages |
| `erin` | HR group: can edit Staff Directory |
| `dave` | Helpdesk group: can edit Devices |
| `alice` | Engineering: can view Staff, has a user grant to view Devices, self-service |
| `bob` | Can view Partners; otherwise only White Pages and Groups |
| `frank` | External partner (`ou=partners`) |

!!! warning
    The embedded directory is held in memory, so changes to directory entries are lost when the
    API stops. Portal configuration (forms, definitions and so on) is still written to
    `DATA_DIR` (default `./data`).

## Building from source

```bash
npm install
npm run build          # builds web/dist and backend/target/directory-services-portal.jar
npm start              # API on :3001, web client on http://localhost:8080 via "vite preview"
```

`npm start` is a convenience. In a real deployment, serve `web/dist` from any web server that:

1. routes `/api/` on the **same origin** to the API (default `http://localhost:3001`), and
2. falls back to `index.html` for every other path that is not a static file (client-side routes
   such as `/d/staff` or `/admin/forms`).

`web/nginx/templates/default.conf.template` in the source tree is a ready-made nginx example. See
[Configuration → Web tier](configuration.md#web-tier-nginx).

Run the API on its own:

```bash
cd backend && ./mvnw spring-boot:run                     # development
java -jar backend/target/directory-services-portal.jar   # built jar
```

Other useful scripts from the root `package.json`:

| Script | Does |
|---|---|
| `npm run dev` | API (`./mvnw spring-boot:run`) and Vite dev server together |
| `npm run build` | Web bundle and Spring Boot jar (tests skipped) |
| `npm run build:api` | Spring Boot jar only |
| `npm start` | Built jar plus `vite preview` |
| `npm test` | Backend JUnit tests, then web Vitest tests |
| `npm run typecheck` | TypeScript checks for `shared` and `web` |
| `npm run package` | Deployment packages (`scripts/package.sh`) |

## Docker Compose from the source tree

`docker-compose.yml` in the repository root starts three services:

| Service | Image | Port | Published on host |
|---|---|---|---|
| `web` | `web/Dockerfile`: nginx (UBI 9 `nginx-126`) serving the React client and proxying `/api` to `API_URL` | 8080 | No (commented-out mapping `8181:8080`) |
| `api` | `backend/Dockerfile`: the Spring Boot API (UBI 9 `openjdk-21-runtime`) | 3001 | No (commented-out `3001:3001`) |
| `openldap` | `deploy/openldap`: Ubuntu 24.04 `slapd` with the demo organisation and ACME schema, built locally for amd64 and arm64 | 389 | No (commented-out `1389:389`) |

```bash
docker compose up --build
```

**No service publishes a port on the host.** To open the portal, either attach your reverse
proxy to the compose network and route to `web:8080`, or uncomment the `ports` entry of the `web`
service and open <http://localhost:8181>.

The compose stack uses the same demo credentials as the built-in demo (`admin` / `password`, and
so on) and seeds the example configuration (`SEED_DEMO_CONFIG=true`). It also sets
`COOKIE_SECURE=false` (plain HTTP) and `FORWARD_HEADERS_STRATEGY=framework`.

Named volumes:

| Volume | Mounted at | Holds |
|---|---|---|
| `portal-data` | `/data` in `api` | `portal-config.json`, `audit/`, `analysis-results/` |
| `ldap-data` | `/var/lib/ldap` in `openldap` | the OpenLDAP database |

!!! note "Reloading the demo LDIF"
    `deploy/openldap/ldif` is loaded only into an empty directory. After changing it, or if you
    started the stack from an older version, run `docker compose down -v` to start over.

### HTTPS with X.509 client certificates (mTLS overlay)

`docker-compose.mtls.yml` switches the web tier to HTTPS with optional client-certificate
verification. nginx verifies the certificate against `certs/ca.pem` and forwards verified
certificates to the API in `X-SSL-Client-Cert`. Password sign-in keeps working for browsers that
have no certificate.

```bash
scripts/generate-dev-certs.sh certs        # throw-away CA, server cert, admin/alice/bob .p12
docker compose -f docker-compose.yml -f docker-compose.mtls.yml up --build
# open https://localhost:8443, import certs/alice.p12 (password "password") into the browser,
# then choose "Sign in with certificate"
```

The overlay:

- sets `NGINX_ENVSUBST_TEMPLATE_DIR=/etc/nginx/templates-mtls` and `HTTPS_PORT_SUFFIX=:8443` on
  `web`, mounts `certs/server.pem`, `certs/server.key` and `certs/ca.pem` into `/etc/nginx/tls/`,
  and publishes `8443:8443`
- sets `COOKIE_SECURE=true`, `X509_ENABLED=true`, `X509_SOURCE=header`,
  `X509_HEADER=X-SSL-Client-Cert`, `X509_TRUSTED_PROXIES=10.0.0.0/8;172.16.0.0/12;192.168.0.0/16`,
  `X509_TRUSTED_CA_FILE=/certs/ca.pem` and `X509_USER_FILTER=(&(objectClass=inetOrgPerson)(uid={{cn}}))`
  on `api`

!!! warning "Key file permissions"
    nginx runs unprivileged (uid 1001, group 0), so the mounted `server.key` must be readable by
    that user or group. For example, run `chgrp 0 server.key && chmod 0640 server.key`. The dev
    script makes `server.key` world-readable (`0644`).

`generate-dev-certs.sh [out-dir] [users...]` creates `ca.pem`/`ca.key`, a `server.pem`/`server.key`
for `localhost`/`127.0.0.1`, and for each user (default `admin alice bob`) a client certificate
with `CN=<user>`, `email:<user>@example.com` and a `.p12` with password `password`. It is for
development only.

For the alternative where the API terminates mutual TLS itself, see
[Configuration → X.509](configuration.md#x509-client-certificate-sign-in).

## Deployment packages

`scripts/package.sh` (or `npm run package`) builds a standalone archive with everything a host
needs to run the portal, without the source tree, build tools or internet access.

```bash
scripts/package.sh                          # dist/directory-services-portal-<version>-docker.tar.gz
scripts/package.sh --with-openldap          # ... including the demo OpenLDAP image
scripts/package.sh --target native          # jar + web client + systemd unit + nginx site
scripts/package.sh --target all             # both
scripts/package.sh --registry reg.example.com/dsp --push --no-images   # images in your registry
```

| Option | Meaning |
|---|---|
| `--target docker|native|all` | What to package (default `docker`) |
| `--version VERSION` | Package and image version (default: `package.json` version plus the git revision, with `-dirty` for uncommitted changes) |
| `--output DIR` | Where to write the archives (default `dist/`) |
| `--with-openldap` | Also include the demo OpenLDAP image (docker) |
| `--registry PREFIX` | Name images `PREFIX/directory-services-portal-{api,web,openldap}:VERSION` |
| `--no-images` | Don't embed images; the host pulls them from `--registry` (requires `--registry`) |
| `--push` | Push the images to `--registry` after building |
| `--skip-build` | Package images / build output that already exist |
| `--platform PLATFORM` | Build images for another platform, for example `linux/arm64` (needs buildx) |
| `--keep` | Keep the unpacked staging directory next to the archive |

Environment variables: `DOCKER_BUILD_ARGS` (extra `docker build` arguments, such as proxy build
args), and `API_DOCKERFILE` / `WEB_DOCKERFILE` (alternative Dockerfiles, for example ones that add a
corporate CA).

Each package contains its own README, a `VERSION` file and `SHA256SUMS`, which the installers
verify. The archive also gets a `.sha256` file.

### Docker deployment package

Requirements: Linux with Docker Engine 20.10+ and Docker Compose v2, and about 2 GB of disk.

```bash
tar xzf directory-services-portal-<version>-docker.tar.gz
cd directory-services-portal-<version>
./install.sh --http 8080          # plain HTTP on port 8080, e.g. behind your TLS proxy
vi .env                           # LDAP connection and administrators
./portal.sh up
```

=== "Plain HTTP"

    ```bash
    ./install.sh --http 8080
    ```
    Adds `docker-compose.publish.yml`, which publishes the web tier on `WEB_BIND:WEB_PORT`. Put a
    TLS-terminating proxy in front of it.

=== "HTTPS + X.509"

    ```bash
    ./install.sh --https 443
    ```
    Adds `docker-compose.mtls.yml`: nginx serves HTTPS on `HTTPS_PORT` with
    `certs/server.pem`, `certs/server.key` and `certs/ca.pem`, and the installer sets
    `X509_ENABLED=true` and `COOKIE_SECURE=true`. Plain HTTP on 8080 redirects to HTTPS.

=== "Nothing published"

    ```bash
    ./install.sh
    ```
    Only `docker-compose.yml`. Attach your own reverse proxy to the compose network and route it
    to `web:8080`.

=== "Demo"

    ```bash
    ./install.sh --demo --http            # built-in in-memory demo directory
    ./install.sh --demo-openldap --http   # bundled OpenLDAP (package built with --with-openldap)
    ```

`install.sh` options:

| Option | Meaning |
|---|---|
| `--http [PORT]` | Publish plain HTTP on PORT (default 8080) |
| `--https [PORT]` | Publish HTTPS on PORT (default 8443), X.509 sign-in available; needs `./certs` |
| `--demo` | Built-in demo directory (no LDAP server), demo users and dashboards |
| `--demo-openldap` | Demo with the bundled OpenLDAP container (if the package includes it) |
| `--force-env` | Rewrite `.env` (the old one is kept as `.env.bak-<time>`) |
| `--start` | Run `./portal.sh up` afterwards |
| `--skip-verify` | Don't check `SHA256SUMS` |

The installer checks for Docker and Compose v2, verifies checksums, and loads the images from
`images/` (or reports that they will be pulled). It writes `.env` with mode `600` and validates
the result with `docker compose config`. It **never overwrites an existing `.env`** unless you pass
`--force-env`.

!!! tip "Certificate files for `--https`"
    | File | Content |
    |---|---|
    | `certs/server.pem` | Server certificate (with chain) |
    | `certs/server.key` | Its private key, readable by uid 1001 (`chmod 644`, or `chgrp 0` + `chmod 640`) |
    | `certs/ca.pem` | CA(s) that issue user certificates |

    For a test, run `scripts/generate-dev-certs.sh certs`.

### Native deployment package

For a Linux host with systemd, Java 21+ and, optionally, nginx:

```bash
tar xzf directory-services-portal-<version>-native.tar.gz
cd directory-services-portal-<version>-native
sudo ./install.sh                     # add --start to start it right away
sudo vi /etc/directory-services-portal/portal.env
sudo systemctl restart directory-services-portal
```

| Installed to | Purpose |
|---|---|
| `/opt/directory-services-portal/api/directory-services-portal.jar` | The API, run by systemd as system user `dsp` on `127.0.0.1:3001` |
| `/opt/directory-services-portal/web` | The web client (static files) |
| `/etc/directory-services-portal/portal.env` | Settings (mode `640`, `root:dsp`); kept on upgrades |
| `/var/lib/directory-services-portal` | Data: configuration, audit log, analysis results (back this up) |
| `/etc/systemd/system/directory-services-portal.service` | The systemd unit (hardened: `ProtectSystem=strict`, `ReadWritePaths` limited to the data directory) |
| `/etc/nginx/conf.d/directory-services-portal.conf` (or `sites-enabled/`) | Serves the client on port 8080 and proxies `/api` to `127.0.0.1:3001` (only when nginx is installed) |

- For HTTPS, terminate TLS in nginx (add a `listen 443 ssl` server, or put a proxy in front) and
  set `COOKIE_SECURE=true`.
- With SELinux enforcing, run `setsebool -P httpd_can_network_connect 1` so that nginx can reach
  the API.
- For X.509 sign-in through nginx, use `X509_SOURCE=header` with
  `X509_TRUSTED_PROXIES=127.0.0.1/32`. See [Configuration](configuration.md#x509-client-certificate-sign-in).

## Connecting to your directory

For any non-demo installation, set at least these values (in `.env`, `portal.env` or the
environment):

```ini
DIRECTORY_MODE=ldap
LDAP_URL=ldaps://ldap.example.com:636
LDAP_BIND_DN=cn=portal,ou=system,dc=example,dc=com
LDAP_BIND_PASSWORD=change-me
LDAP_BASE_DN=dc=example,dc=com
LDAP_USER_SEARCH_BASE=ou=people,dc=example,dc=com
LDAP_GROUP_SEARCH_BASE=ou=groups,dc=example,dc=com
ADMIN_USERS=jdoe
ADMIN_GROUPS=cn=directory-admins,ou=groups,dc=example,dc=com
COOKIE_SECURE=true
```

All settings are described in [Configuration](configuration.md).

## First start

1. Start the stack (`./portal.sh up`, `systemctl start directory-services-portal`, or
   `docker compose up`).
2. Open the portal and sign in as a user listed in `ADMIN_USERS` or a member of `ADMIN_GROUPS`.
3. The configuration store starts empty (unless `SEED_DEMO_CONFIG=true`, or the API is in memory
   mode). Continue with the [Administrator guide](admin-guide.md#getting-started):
    1. **Admin → Schema → Pull from directory**
    2. **Admin → Forms**: create a form for an object class
    3. **Admin → Directory definitions**: publish the form in a namespace
    4. **Admin → Permissions**: grant users or groups access

## Verification

| Check | Command / action | Expected |
|---|---|---|
| API is up | `curl -s http://localhost:3001/api/health` (inside the API container or host) | `{"ok":true}` |
| Directory reachable | `curl -s http://<portal>/api/info` | JSON with `"connected":true`, `mode`, `baseDn`, `version` |
| Proxy works | Open `http://<portal>/` | The login page, with any configured banner and login hint |
| Sign-in | Sign in as an administrator | The **Admin** section appears in the sidebar |
| Package stack | `./portal.sh status` | `api` healthy, plus the URL to open |
| Native | `systemctl status directory-services-portal` and `journalctl -u directory-services-portal -f` | Active (running) |

If `/api/info` shows `"connected": false`, its `error` field contains the LDAP error. See the
[troubleshooting runbook](operator-guide.md#troubleshooting-runbook).
