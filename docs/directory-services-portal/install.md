# Installation

## At a glance

| Method | Use it for | You run |
|---|---|---|
| [Docker or Podman package](#docker-package) | One host with Docker or Podman, including hosts with no internet access | `install.sh`, `portal.sh`, `upgrade.sh` |
| [Native package](#install-the-native-package) | One Linux host with systemd and no containers | `install.sh`, `systemctl` |
| [Helm chart](#install-with-helm) | Kubernetes and OpenShift | `helm install`, `helm upgrade` |
| [Demo](#trying-it-out-the-demo) | A look at the portal with its built-in demo directory. **Not for production** | `docker compose -f docker-compose.demo.yml` |

Every method runs the same two components: the **API** (Spring Boot, port 3001) and the **web
tier** (nginx serving the web client and forwarding `/api` to the API). Browsers only talk to the
web tier.

## Prerequisites

### For every installation

| Component | Requirement |
|---|---|
| LDAP server | Any LDAPv3 server reachable over `ldap://` or `ldaps://` (optional StartTLS) |
| Service account | An LDAP account that can read the schema (root DSE `subschemaSubentry`) and the namespaces you expose, and write wherever portal users should be able to edit |
| Administrators | The users (`ADMIN_USERS`) or groups (`ADMIN_GROUPS`) who will administer the portal |
| License | An ENS license file (`.lic`). For a trial, download an evaluation license from the ENS licensing portal. See [Licensing](../licensing.md) |

### Directory servers: what is tested

The portal talks plain LDAPv3 through the UnboundID LDAP SDK and makes no server-specific calls.
Metrics and analyses read entries with the Simple Paged Results control (500 entries a page),
marked non-critical, so a server without it still answers up to its own size limit. This is what
the portal's own tests and CI actually run against:

| Directory server | Status | What runs |
|---|---|---|
| UnboundID in-memory directory server (the embedded demo directory, `DIRECTORY_MODE=memory`) | **Tested** on every build | The whole backend test suite: sign-in, groups, permissions, entry CRUD and renames, schema pull, metrics, analyses, X.509 mapping. Also CI's image smoke test |
| OpenLDAP (`slapd` from Ubuntu's package, the demo image in `deploy/openldap`) | Built for the demo only | Not run by the automated tests or CI. Used by `install.sh --demo-openldap` and the source tree's compose demo |
| OpenLDAP or 389 Directory Server in production | **Not tested** | The configuration examples are illustrations built from the documented settings |
| Microsoft Active Directory | **Not tested** | The configuration example (`sAMAccountName`, `memberOf`) is an illustration. AD-specific behaviour, such as setting `unicodePwd`, has not been tested |
| Others (Oracle, OpenDJ/PingDS, eDirectory, ApacheDS) | **Not tested** | |
| LDAPS and StartTLS | **Not covered by automated tests** | The embedded directory listens on plain LDAP only |

If you run the portal against a directory that isn't tested, try it on a test instance of that
directory first: sign-in with your user and group filters, schema pull, and one create, update,
rename and delete through a form.

### For each method

| Method | Requirement |
|---|---|
| Docker | Linux with Docker Engine 20.10+ and Docker Compose v2 (`docker compose`; `docker-compose` 1.29 is used, best effort, when v2 is missing), and about 2 GB of disk |
| Podman | Linux with Podman 4.0+ and podman-compose 1.0.6+ (EPEL's version for RHEL 8 and 9; `podman compose` is used when podman-compose is not installed), rootful or rootless, and about 2 GB of disk. With podman-compose 1.0.x, write `.env` values without quotes: Podman keeps quotes as part of the value |
| Native | Linux with systemd and Java 21 or later; nginx is optional (the installer configures it if it is installed) |
| Helm | Kubernetes 1.25+ or OpenShift 4, Helm 3.8+ (OCI registries), a StorageClass for the license (100 Mi) and data (2 Gi) volumes, and access to `ghcr.io/winllc` or a mirror. PostgreSQL 13+ only for [several API replicas](#several-api-replicas) |

## Getting the release

1. Sign in to the **ENS licensing portal** and open **Downloads** (`/account/downloads`). Any
   account with a non-revoked license for the portal, evaluation licenses included, can
   download. The page lists every version, with the latest highlighted, and each file's size and
   SHA-256.
2. Download what you need, into one directory:

    | File | What it is |
    |---|---|
    | `directory-services-portal-<version>.tar.gz` | The Docker/Podman package, with the images |
    | `directory-services-portal-<version>-native.tar.gz` | The native (systemd) package |
    | `directory-services-portal-<version>.tgz` | The Helm chart |
    | `sbom-*.spdx.json` | SBOMs of the two images |
    | `SHA256SUMS`, `SHA256SUMS.asc` | Checksums, and ENS Solutions' signature over them when the release is signed |

3. Verify them before you unpack anything:

    ```bash
    sha256sum -c SHA256SUMS --ignore-missing     # each file you downloaded
    gpg --verify SHA256SUMS.asc SHA256SUMS       # when signed, with the ENS release key imported
    ```

The files are delivered from ENS Solutions' own storage, not from GitHub Releases. Importing the
release key and verifying the image signatures are described in
[Releases and downloads](../releases.md).

## Install with Docker or Podman { #docker-package }

### 1. Unpack and run install.sh

```bash
tar xzf directory-services-portal-<version>.tar.gz
cd directory-services-portal-<version>
./install.sh                      # plain HTTP on 0.0.0.0:8080 (next release: 127.0.0.1:8080)
```

The installer picks the container engine, verifies `SHA256SUMS`, loads the images from
`images/`, writes `.env` (mode `600`) with a random `X509_PROXY_SECRET`, and validates the
compose configuration. It **never overwrites an existing `.env`** unless you pass `--force-env`.
From the next release a new `.env` also gets a random `DSP_SECRETS_KEY`, the key webhook signing
secrets are stored encrypted with (`--force-env` carries the previous one over): **keep a copy of
`.env` with, but apart from, your backups**, since the stored secrets can't be read without it. See
[Webhooks → Encrypted signing secrets](webhooks.md#encrypted-signing-secrets-next-release).

The engine is, in order: `--engine docker|podman`, the `CONTAINER_ENGINE` environment variable,
`CONTAINER_ENGINE` in `.env`, and otherwise Docker if its daemon answers, else Podman. The
installer records the engine in `.env`, and `portal.sh` and `upgrade.sh` keep using it. Each
engine has its own containers and volumes: to move an installed portal to the other one, use
`./portal.sh backup` and `./portal.sh restore`.

### 2. Choose how it is published

By default the web tier is published over plain HTTP on **every interface**
(`WEB_BIND=0.0.0.0`), port 8080 (`WEB_PORT`). Choose another way with the installer's options:

!!! info "From the next release: plain HTTP on this host only by default"
    A new `.env` written by `./install.sh` without `--bind` has `WEB_BIND=127.0.0.1`: plain HTTP
    carries directory passwords and the session cookie in clear, so it is not offered to the
    network unless you choose it. To serve the network, put a TLS-terminating proxy on this host
    in front of it (and set `ENS_COOKIE_SECURE=true` in `.env`), or use `--https`, which publishes
    on every interface (`0.0.0.0`) and sets `ENS_COOKIE_SECURE=true` as before. `--bind 0.0.0.0`
    publishes plain HTTP on every interface, for a TLS proxy on another host; the installer warns
    about it. An existing `.env` is never rewritten: an upgrade keeps its `WEB_BIND` (an `.env`
    without one keeps every interface), and `install.sh` warns whenever plain HTTP is published
    beyond the host, or `ENS_COOKIE_SECURE` is not `true` there.

=== "Plain HTTP"

    ```bash
    ./install.sh                      # 0.0.0.0:8080 (next release: 127.0.0.1:8080)
    ./install.sh --bind 127.0.0.1     # this host only, for a TLS proxy on the same host
    ./install.sh --bind 0.0.0.0       # every interface: only behind a TLS proxy elsewhere
    ./install.sh --http 8081          # another port
    ```
    Put a TLS-terminating proxy in front, and set `ENS_COOKIE_SECURE=true` in `.env`.

=== "HTTPS + X.509"

    ```bash
    ./install.sh --https 8443          # HTTPS only
    ./install.sh --https 8443 --http   # HTTPS, and HTTP on 8080 redirecting to it
    ```
    Adds `docker-compose.mtls.yml`: nginx serves HTTPS on `WEB_BIND:HTTPS_PORT` with
    `certs/server.pem`, `certs/server.key` and `certs/ca.pem`, and asks browsers for an optional
    client certificate. The installer sets `X509_ENABLED=true` and `ENS_COOKIE_SECURE=true`. nginx
    forwards a verified certificate to the API together with `X509_PROXY_SECRET`; the API accepts
    a forwarded certificate only with that secret, and checks it against `certs/ca.pem` too.

=== "Nothing published"

    ```bash
    ./install.sh --no-publish
    ```
    Uses `docker-compose.no-publish.yml` instead of `docker-compose.yml`: the same services with
    no published port. Attach your reverse proxy to the `directory-services-portal_default`
    network and forward to `http://web:8080`.

!!! warning "Plain HTTP on 0.0.0.0"
    With the default bind, anyone who can reach the host can reach the portal, and passwords and
    session cookies cross the network in clear text. The installer warns about it. Put a TLS
    proxy in front, use `--https`, or set `WEB_BIND=127.0.0.1` in `.env` and run `./portal.sh up`.

`install.sh` options:

| Option | Meaning |
|---|---|
| `--http [PORT]` | Plain HTTP on PORT (default 8080, the default). With `--https` too, it redirects to HTTPS |
| `--https [PORT]` | HTTPS on PORT (default 8443), X.509 sign-in available; needs `./certs`. Without `--http`, only HTTPS is published |
| `--bind ADDRESS` | Host address to publish on (default `0.0.0.0`; `127.0.0.1` for this host only). From the next release the default is `127.0.0.1` for plain HTTP and `0.0.0.0` with `--https` |
| `--no-publish` | Publish nothing; route your own proxy on the compose network to `web:8080` |
| `--engine ENGINE` | `docker` or `podman` |
| `--demo` | The built-in demo directory (no LDAP server), demo users and dashboards. It is published like any install, so add `--bind 127.0.0.1`. From the next release it also sets `DEMO_MODE=true`, which the built-in directory requires |
| `--demo-openldap` | A demo OpenLDAP container (`docker-compose.openldap.yml`), when the package includes its image |
| `--force-env` | Rewrite `.env` (the old one is kept as `.env.bak-<time>`) |
| `--start` | Run `./portal.sh up` afterwards |
| `--skip-verify` | Don't check `SHA256SUMS` |

!!! tip "Certificate files for `--https`"
    | File | Content |
    |---|---|
    | `certs/server.pem` | Server certificate (with chain) |
    | `certs/server.key` | Its private key, readable by uid 1001 (`chmod 644`, or `chgrp 0` + `chmod 640`; rootless Podman: `podman unshare chown 1001:0 certs/server.key` + `chmod 640`) |
    | `certs/ca.pem` | CA(s) that issue user certificates |

    For a test, run `scripts/generate-dev-certs.sh certs` from the package. It creates a
    throw-away CA, a server certificate for `localhost`, and user certificates (`certs/alice.p12`,
    password `password`).

### 3. Fill in .env

Every setting is in `.env`, with comments. After a change, run `./portal.sh up`. The first
section chooses the compose files (`COMPOSE_FILE`), the engine and the ports. Set at least the
directory connection and the administrators (see
[Connecting to your directory](#connecting-to-your-directory)). All settings are described in
[Configuration](configuration.md).

`.env` holds the bind password and the generated `X509_PROXY_SECRET`, so keep it private (mode
`600`). Leave `X509_PROXY_SECRET` as generated; to change it, set a new value of at least 32
characters (for example `openssl rand -hex 32`) and run `./portal.sh up`.

### 4. Add the license

Choose one:

- **The drop-in.** Put the file at `license/license.lic` before you run `./install.sh`. The
  installer sets `ENS_LICENSE` in `.env` from it (below). To install a license later, run
  `./portal.sh license /path/to/license.lic`: it sets `ENS_LICENSE` too and applies it at once.
  Without the shared database it also writes the file into the `ens-license` volume, replacing any
  license there; with it, it restarts the API, and a different license already installed is kept
  (upload that one on the license screen).
- **`ENS_LICENSE` in `.env`.** The license text itself: the token between the file's `BEGIN` and
  `END` lines, or the whole file base64-encoded (`base64 -w0 license.lic`). It is installed at
  start if no license is installed yet, or if it is a newer revision of the installed one.
- **The license screen.** Start the portal and sign in as an administrator. You land on
  *Administration > License* (`/admin/license`). Upload the file there.

The license is kept in the `ens-license` volume (`/var/lib/ens/directory-portal` in the API
container), together with this installation's id. The image sets `ENS_LICENSE_FILE` and
`ENS_LICENSE_STATE_DIR` there; don't change them. **Keep that volume.** `down -v` deletes it,
and a new installation id means re-binding the license on the ENS licensing portal. See
[Licensing](../licensing.md) for the rest.

### 5. Start it

```bash
./portal.sh up
./portal.sh status                # containers, health, URL
```

`portal.sh` uses the engine in `.env`, so the same commands work with Docker and Podman:

```bash
./portal.sh logs api              # follow the API log
./portal.sh backup                # portal-backup-<date>.tar.gz: configuration, audit log, analysis results
./portal.sh restore <file>
./portal.sh license <file.lic>    # install or replace the license
./portal.sh down                  # stop (data is kept in the portal-data volume)
```

### 6. Upgrade { #upgrade }

Unpack the new package next to the running one and run its `upgrade.sh` with the old
directory:

```bash
tar xzf directory-services-portal-<new-version>.tar.gz
cd directory-services-portal-<new-version>
./upgrade.sh ../directory-services-portal-<old-version>
```

`upgrade.sh` copies `.env`, `license/` and `certs/` across (it never overwrites), verifies the
package and loads its images, generates `X509_PROXY_SECRET` if the old `.env` has none, and backs
up the portal data to `backups/`. It then points `.env` at the new images (keeping a
`.env.bak-<time>`) and restarts with `./portal.sh up`. The data volume and the license volume are
reused. To go back, run `./portal.sh up` in the old directory and, if needed,
`./portal.sh restore` the backup. Options: `--no-backup`, `--no-start`, `--skip-verify`,
`--engine`.

!!! note "What an upgrade keeps"
    - **Ports and addresses.** `WEB_BIND`, `WEB_PORT` and `HTTPS_PORT` are not changed, so an
      installation bound to `127.0.0.1` stays local. Only new installations get the `0.0.0.0`
      default. From the next release, new installations get `127.0.0.1` for plain HTTP instead;
      an `.env` without `WEB_BIND` still publishes on every interface, and `install.sh` warns when
      plain HTTP is published beyond the host or `ENS_COOKIE_SECURE` is not `true`.
    - **From a package before Podman support.** There, `docker-compose.yml` published nothing and
      `docker-compose.publish.yml` published `WEB_PORT`. `upgrade.sh` rewrites `COMPOSE_FILE`:
      without `docker-compose.publish.yml` it uses `docker-compose.no-publish.yml` (still nothing
      published); with it, the file is dropped (the same `WEB_BIND:WEB_PORT`). It also adds
      `CONTAINER_ENGINE=docker`. Nothing needs doing by hand.
    - **Demo directory (next release).** An `.env` that sets `DIRECTORY_MODE=memory` and has no
      `DEMO_MODE` line gets `DEMO_MODE=true` added by `install.sh` (which `upgrade.sh` runs), so a
      demo keeps starting.
    - **New settings** are not written into an existing `.env` (except `X509_PROXY_SECRET`): they
      take their defaults, and older names keep working (`SESSION_TTL_MINUTES`, `COOKIE_SECURE`,
      `X509_CHECK_REVOCATION`). Compare your `.env` with the package's `env.template` to adopt
      them.
    - **Webhook secrets key (next release).** An `.env` from an earlier package is not given a
      `DSP_SECRETS_KEY`: webhook signing secrets stay unencrypted, with a warning, and
      `install.sh` says so. Add `DSP_SECRETS_KEY=$(openssl rand -base64 32)` to `.env` to encrypt
      them at the next start, and keep a copy apart from your backups.
    - **Signing out at the identity provider (next release).** A new `.env` sets
      `SSO_OIDC_PROVIDER_LOGOUT=true` and `SSO_SAML_SINGLE_LOGOUT=true`; an upgraded one without
      them signs out as before. See [Single sign-on → Signing out](single-sign-on.md#signing-out).
    - **Revocation.** An `.env` from an earlier package sets `ENS_X509_REVOCATION=off` and
      `ENS_X509_REVOCATION_FAIL=closed`, which keep revocation checking off; the installer says
      so. Empty both for the default live, soft-fail check.

### Podman: rootless and boot

- **Ports from 1024.** Rootless Podman can't publish ports below 1024; the defaults, 8080 and
  8443, are fine. For 443, run `sudo sysctl net.ipv4.ip_unprivileged_port_start=443` (persist it
  in `/etc/sysctl.d/`), or use a proxy. The installer warns about it.
- **Keep it running after logout** (rootless): `sudo loginctl enable-linger $USER`.
- **Start at boot.** Podman has no daemon to restart the containers. Run `./portal.sh up` from a
  systemd unit (for rootless, a user unit, `systemctl --user`, with `WantedBy=default.target`):

    ```ini
    [Unit]
    Description=Directory Services Portal
    Wants=network-online.target
    After=network-online.target

    [Service]
    Type=oneshot
    RemainAfterExit=yes
    WorkingDirectory=/opt/directory-services-portal
    ExecStart=/opt/directory-services-portal/portal.sh up
    ExecStop=/opt/directory-services-portal/portal.sh down

    [Install]
    WantedBy=multi-user.target
    ```

    `podman generate systemd --new` or Quadlet units work too, but then systemd, not
    `./portal.sh`, owns the containers.
- **SELinux** (RHEL, Fedora): the certificate files in `./certs` are mounted with `:Z`/`:z`, so
  Podman relabels them. Nothing else is bind-mounted.
- **Health checks** run from systemd timers; `./portal.sh up` also runs the API's check itself
  while it waits, so hosts without systemd work as well.
- **cgroups v1.** On a host still on cgroups v1, rootless Podman ignores the memory limits, and
  says so.

## Install the native package

### 1. Install

For a Linux host with systemd, Java 21+ and, optionally, nginx:

```bash
tar xzf directory-services-portal-<version>-native.tar.gz
cd directory-services-portal-<version>-native
sudo ./install.sh                     # add --start to start it right away
sudo vi /etc/directory-services-portal/portal.env
printf '%s' 'the-password' | sudo tee /etc/directory-services-portal/ldap-bind-password >/dev/null
sudo systemctl restart directory-services-portal
```

### 2. What it installs

The installer verifies `SHA256SUMS`, creates the system user `dsp` and installs:

| Installed to | Purpose |
|---|---|
| `/opt/directory-services-portal/api/directory-services-portal.jar` | The API, run by systemd as user `dsp` on `127.0.0.1:3001` |
| `/opt/directory-services-portal/web` | The web client (static files) |
| `/etc/directory-services-portal/portal.env` | Settings (mode `640`, `root:dsp`); kept on upgrades. A new `portal.env` sets `LDAP_BIND_PASSWORD_FILE` and `MANAGEMENT_SERVER_ADDRESS=127.0.0.1` |
| `/etc/directory-services-portal/ldap-bind-password` | The LDAP service account's password (mode `640`, `root:dsp`), read through `LDAP_BIND_PASSWORD_FILE`. Created empty if missing and never overwritten; write the password into it. An upgraded `portal.env` that still sets `LDAP_BIND_PASSWORD` keeps working |
| `/etc/directory-services-portal/proxy-secret` | The X.509 proxy secret (mode `640`, `root:dsp`), generated once and kept. The API reads it through `X509_PROXY_SECRET_FILE`, set in the systemd unit |
| `/etc/directory-services-portal/secrets-key` (next release) | The key webhook signing secrets are stored encrypted with (`DSP_SECRETS_KEY_FILE` in the new `portal.env`; mode `640`, `root:dsp`), generated by a new installation only and never replaced. Back it up apart from the data |
| `/etc/directory-services-portal/nginx-proxy-secret.conf` | The copy of the proxy secret that nginx includes (mode `600`, `root`), rewritten from `proxy-secret` on every run |
| `/var/lib/directory-services-portal` | Data: configuration, audit log, analysis results (back this up) |
| `/var/lib/ens/directory-portal` | The ENS license and this installation's id; keep it |
| `/etc/systemd/system/directory-services-portal.service` | The systemd unit, hardened: `ProtectSystem=strict` with `ReadWritePaths` limited to the data and license directories, no capabilities, a system-call filter, `UMask=0077` and more (see [Security](security.md#network-exposure)) |
| `/etc/nginx/conf.d/directory-services-portal.conf` (or `sites-enabled/`) | Serves the client on port 8080 and proxies `/api` to `127.0.0.1:3001` (only when nginx is installed). From the next release it listens on `NGINX_LISTEN` from `portal.env`: see below |

!!! info "From the next release: nginx listens on this host only by default"
    A new `portal.env` sets `NGINX_LISTEN=127.0.0.1:8080`, and `install.sh` writes it into the
    nginx site: plain HTTP on this host only, for a TLS proxy on it (set `ENS_COOKIE_SECURE=true`
    in `portal.env` then). `NGINX_LISTEN=8080` listens on every interface, for a TLS proxy on
    another host; the installer warns about plain HTTP reachable from the network. The value is
    `PORT`, `ADDRESS:PORT` or `[IPv6]:PORT`; run `install.sh` again after changing it. A
    `portal.env` from an earlier release has no `NGINX_LISTEN` and keeps listening on every
    interface (`8080`), as before, and the installer says so.

### 3. License, HTTPS and the rest

- **License:** sign in as an administrator and upload it under *Administration > License*, or
  copy it in with
  `sudo install -m 640 -o dsp -g dsp license.lic /var/lib/ens/directory-portal/license.lic`.
  With the shared database (`DSP_DATABASE_URL`) that file is read only on the first start; after
  that, upload the license or set `ENS_LICENSE` (the file, base64) in `portal.env` and restart.
- **Logs:** `journalctl -u directory-services-portal -f`.
- **HTTPS:** terminate TLS in nginx (add a `listen 443 ssl` server, or put a proxy in front) and
  set `ENS_COOKIE_SECURE=true`.
- **Prometheus:** set `MANAGEMENT_SERVER_PORT` (for example `9090`) in `portal.env`. It listens on
  `127.0.0.1` only (`MANAGEMENT_SERVER_ADDRESS`).
- **SELinux:** with SELinux enforcing, run `setsebool -P httpd_can_network_connect 1` so that
  nginx can reach the API.
- **X.509 sign-in through nginx:** use `X509_SOURCE=header` with
  `X509_TRUSTED_PROXIES=127.0.0.1/32` and `X509_TRUSTED_CA_FILE` (required). Any local process can
  reach `127.0.0.1:3001`, so the API accepts a forwarded certificate only with the proxy secret.
  The `location` that proxies `/api` in your HTTPS server must include the secret, as the packaged
  site does, and set the certificate header itself:

    ```nginx
    include /etc/directory-services-portal/nginx-proxy-secret.conf;
    proxy_set_header X-SSL-Client-Cert $ssl_client_escaped_cert;
    ```

    To change the secret, write a new value to `proxy-secret`, run `install.sh` again (it rewrites
    nginx's copy and reloads nginx), and restart the API. See
    [Configuration](configuration.md#x509-client-certificate-sign-in).

### 4. Upgrade

Run the new package's `sudo ./install.sh`. Settings and data are kept. It generates the X.509
proxy secret if there is none yet; restart the API afterwards
(`systemctl restart directory-services-portal`). From the next release, an upgraded `portal.env`
without `NGINX_LISTEN` keeps nginx on every interface; add `NGINX_LISTEN=127.0.0.1:8080` and run
`install.sh` again to keep plain HTTP to this host. An upgraded `portal.env` without
`DSP_SECRETS_KEY_FILE` keeps storing webhook signing secrets unencrypted; `install.sh` prints how to
create `secrets-key` and add the setting.

## Install with Helm

The chart installs two Deployments: the API (one replica by default) and the web tier. The web
tier's Service, `<release>-directory-services-portal`, is what users reach. The API keeps two
PersistentVolumeClaims: the **license volume** (`/var/lib/ens`) and the **data volume** (`/data`:
forms, definitions, grants, dashboards, analyses and the audit store). It needs no database
unless you run [several API replicas](#several-api-replicas).

### 1. Install

Write a values file with your directory connection:

```yaml
# my-values.yaml
ldap:
  url: ldaps://ldap.corp.example:636
  bindDn: cn=portal,ou=system,dc=corp,dc=example
  bindPassword:
    existingSecret: dsp-secrets
    existingSecretKey: ldap-bind-password
  baseDn: dc=corp,dc=example
  userSearchBase: ou=people,dc=corp,dc=example
  groupSearchBase: ou=groups,dc=corp,dc=example
admin:
  users: [jdoe]
  groups: [cn=directory-admins,ou=groups,dc=corp,dc=example]
```

Then install the chart of your release:

```bash
kubectl create namespace dsp
kubectl create secret generic dsp-secrets -n dsp --from-literal=ldap-bind-password='...'
helm install dsp oci://ghcr.io/winllc/charts/directory-services-portal --version X.Y.Z \
  -n dsp -f my-values.yaml
helm test dsp -n dsp
```

The chart version is the release version, and by default the chart runs the images of that
same version. The chart's README lists every value. Run
`helm show readme oci://ghcr.io/winllc/charts/directory-services-portal --version X.Y.Z`.

### 2. Key values { #key-values }

| Value | Purpose |
|---|---|
| `ldap.*`, `admin.*` | Directory connection, users and groups, administrators (the `LDAP_*` and `ADMIN_*` settings) |
| `directory.mode` | `ldap`, or `memory` for the built-in demo directory (evaluation only) |
| `server.session.*` | `idleTimeout` (`30m`), `adminIdleTimeout`, `absoluteTimeout` (`12h`), `maxPerUser` (`0`). The deprecated `server.sessionTtlMinutes`, when set, still wins over `idleTimeout` |
| `server.groupRefreshInterval`, `server.login.*` | Group refresh (`5m`); `maxFailures` (`8`) and `lockoutWindow` (`15m`) for the sign-in lockout |
| `server.consentBanner.*` | `enabled`, `title`, `text`: the DoD notice-and-consent banner |
| `server.*` (other) | Secure cookies (`cookieSecure: auto`), `cspReportOnly`, login hint, page banner, header size |
| `metrics.enabled`, `metrics.port` | `true`, `9090`: Prometheus and the probes on a management port of their own, the `metrics` port of the API's Service (never the ingress or route) |
| `metrics.serviceMonitor.*`, `metrics.allowFrom` | A Prometheus Operator ServiceMonitor; NetworkPolicy peers admitted to the metrics port |
| `logFormat` | `json` (ECS JSON log lines, the default) or `text` |
| `metrics.maxEntries` and the rest of `metrics.*`, `analysis.*`, `jobs.*` | Metrics, analysis and background-task limits |
| `audit.*` | Audit outputs: stdout, a rolling file, Splunk HEC, syslog, retention (see [Audit logging](../audit-logging.md)) |
| `x509.*`, `tls.*` | Certificate sign-in, and HTTPS in the web pod |
| `x509.revocation.*`, `x509.allowedIssuers` | `mode` and `fail` (empty: OCSP, then the CRL, soft-fail), `ocspResponderUrl`, `crlDir`, `cache` (`10m`), and `egress` (ports 80 and 443) for OCSP and CRL fetches under a NetworkPolicy |
| `api.replicaCount`, `database.*` | `1`; more needs the shared PostgreSQL (`database.url`). See [Several API replicas](#several-api-replicas) |
| `persistence.license`, `persistence.data` | The two volumes: `size`, `storageClass`, `accessModes`, `existingClaim` |
| `ingress.*`, `route.*`, `service.*` | How users reach the portal |
| `networkPolicy.*` | Optional NetworkPolicies for both workloads; required for edge-terminated X.509 sign-in |
| `api.*`, `web.*` | Images, resources, probes, scheduling, `extraEnv`, `extraEnvFrom` |
| `global.imageRegistry`, `imagePullSecrets` | Registry mirror and pull secrets |

Every setting from the Docker package's `env.template` has a value; the
[Configuration](configuration.md) page describes what each one does. Anything else goes in
`api.extraEnv`.

### 3. Secrets { #secrets }

Secrets are never put in a ConfigMap. Each one is either inline in your values (the chart
stores it in its own Secret) or taken from a Secret you create:

| Secret | Inline value | Existing Secret |
|---|---|---|
| LDAP bind password (mounted into the API pod as a file, `LDAP_BIND_PASSWORD_FILE`) | `ldap.bindPassword.value` | `ldap.bindPassword.existingSecret`, `.existingSecretKey` |
| Splunk HEC token | `audit.splunk.token.value` | `audit.splunk.token.existingSecret`, `.existingSecretKey` |
| LDAPS trust store password | `ldap.truststore.password.value` | `ldap.truststore.password.existingSecret`, `.existingSecretKey` |
| ENS license | `license.file` (`--set-file`) | `license.existingSecret` (key `license.lic`) |
| Web server certificate | `tls.certificate`, `tls.key`, `tls.clientCa` | `tls.existingSecret` (keys `server.pem`, `server.key`, `ca.pem`) |
| Webhook secrets key (next release; `DSP_SECRETS_KEY_FILE`, mounted at `/etc/dsp/secrets/key`) | `integrations.webhooks.secretsKey.value`, or *(generated by a new installation with `.generate: true`, the default, into the chart's Secret under `secrets-key`, kept across upgrades; never by an upgrade or `helm template`)* | `integrations.webhooks.secretsKey.existingSecret`, `.existingSecretKey` (default key `secrets-key`) |
| X.509 proxy secret | *(generated with `x509.enabled` into `<release>-directory-services-portal-x509-proxy`, kept across upgrades)* | `x509.proxySecret.existingSecret`, `.existingSecretKey` (default key `proxy-secret`) |

The chart keeps the generated X.509 proxy secret by reading it back from the cluster. Rendering
without access to the cluster (`helm template`, Argo CD) would generate a new one each time, so
there, create the Secret yourself and set `x509.proxySecret.existingSecret`:

```bash
kubectl create secret generic dsp-x509-proxy -n dsp --from-literal=proxy-secret="$(openssl rand -hex 32)"
```

For LDAPS with a private CA, put a PKCS12 trust store in a Secret and set
`ldap.truststore.existingSecret`. It replaces the JVM's default trust store, so include every CA
the API must trust:

```bash
keytool -importcert -noprompt -alias corp-ca -file corp-ca.pem -keystore truststore.p12 \
  -storetype PKCS12 -storepass 'store-pass'
kubectl create secret generic dsp-truststore -n dsp --from-file=truststore.p12 \
  --from-literal=truststore-password='store-pass'
```

```yaml
ldap:
  truststore:
    existingSecret: dsp-truststore
    password:
      existingSecret: dsp-truststore
      existingSecretKey: truststore-password
```

### 4. License

Without a license, only sign-in and the license screen work. You can:

- **Upload it on the license screen.** Sign in as an administrator. You land on
  *Administration > License* (`/admin/license`).
- **Give it to the chart.** Use `--set-file license.file=./license.lic`, or a Secret with the key
  `license.lic` in `license.existingSecret`. An init container copies it into the license volume
  **only if no license is installed there yet**. A later `helm upgrade` therefore never
  overwrites a newer license uploaded on the license screen. Pods without the license volume
  (several replicas) get it as `ENS_LICENSE` instead.

The license screen shows the **installation id**, which you enter on the ENS licensing portal to
bind a license to this installation. It is also in the API's startup log:

```bash
kubectl logs -n dsp deploy/dsp-directory-services-portal-api -c api | grep License:
```

!!! warning "Keep the license volume"
    The license PVC (`<release>-directory-services-portal-license`) holds the installation id. It
    carries `helm.sh/resource-policy: keep`, so neither `helm upgrade` nor `helm uninstall`
    deletes it. If it is lost, the installation gets a new id and bound licenses must be
    re-bound. For installations you might reinstall, create the PVCs yourself and set
    `persistence.license.existingClaim` and `persistence.data.existingClaim`.

### 5. Ingress, OpenShift and HTTPS

=== "Ingress"

    ```yaml
    ingress:
      enabled: true
      className: nginx
      hosts:
        - host: portal.corp.example
          paths: [{path: /, pathType: Prefix}]
      tls:
        - secretName: portal-tls
          hosts: [portal.corp.example]
    ```
    Secure session cookies switch on automatically when `ingress.tls` is set. Schema imports
    can be up to 5 MB, so raise the controller's body size limit if it is lower
    (ingress-nginx: `nginx.ingress.kubernetes.io/proxy-body-size: 5m`).

=== "OpenShift"

    ```yaml
    openshift:
      enabled: true        # the restricted SCC assigns the user, group and fsGroup
    route:
      enabled: true
      host: portal.apps.ocp.corp.example
      tls:
        termination: edge
    ```
    Both images run under an arbitrary UID in group 0.

=== "LoadBalancer"

    ```yaml
    service:
      type: LoadBalancer
      externalTrafficPolicy: Local    # keeps client addresses
    ```

### 6. X.509 smart-card sign-in

The API takes the user's certificate from a header set by a trusted proxy. The license must
include `smart-card-sign-in`. Two setups are supported. In both, the API accepts that header only
from the web pods' addresses (`x509.trustedProxies`) together with the proxy secret the web pods
send with every request (see [Secrets](#secrets)). It checks the chain itself against
`x509.trustedCa` (or `tls`'s `ca.pem`), which is required: the chart does not render without it.

**TLS passthrough to the web pod (recommended).** The web pod serves HTTPS on port 8443 and asks
browsers for a certificate. The request is optional, so password sign-in keeps working. nginx
verifies the certificate against `ca.pem` and forwards it to the API, which checks the chain
again.

```bash
kubectl create secret generic dsp-web-tls -n dsp \
  --from-file=server.pem --from-file=server.key --from-file=ca.pem
```

```yaml
tls:
  enabled: true
  existingSecret: dsp-web-tls
x509:
  enabled: true
  userFilter: (&(objectClass=inetOrgPerson)(userPrincipalName={{upn}}))
```

Then bring TLS to the pod unchanged, in one of these ways:

- ingress-nginx with `--enable-ssl-passthrough` and the annotation
  `nginx.ingress.kubernetes.io/ssl-passthrough: "true"`;
- an OpenShift Route with `route.tls.termination: passthrough`;
- `service.type: LoadBalancer`, with `service.httpsPort: 443`.

Set `tls.httpsPortSuffix` (for example `":8443"`) if browsers reach HTTPS on a port other
than 443.

**Edge termination at the ingress.** The ingress controller verifies the certificate and
forwards it in its own header. Set `x509.header` to that header name, and give the API the CA
with `x509.trustedCa`. The web tier passes that header on and removes any other certificate
header. With ingress-nginx:

```yaml
x509:
  enabled: true
  header: ssl-client-cert
  trustedCa:
    existingSecret: dsp-client-ca
    existingSecretKey: ca.crt
ingress:
  enabled: true
  className: nginx
  annotations:
    nginx.ingress.kubernetes.io/auth-tls-secret: dsp/dsp-client-ca
    nginx.ingress.kubernetes.io/auth-tls-verify-client: "optional"
    nginx.ingress.kubernetes.io/auth-tls-pass-certificate-to-upstream: "true"
networkPolicy:
  enabled: true
  webIngressFrom:
    - namespaceSelector:
        matchLabels:
          kubernetes.io/metadata.name: ingress-nginx
```

!!! warning
    With edge termination, anything that reaches the web pods without going through the ingress
    controller could send the header with a user's certificate, which is public. The chart
    refuses to render this setup unless `networkPolicy.enabled` is `true` and
    `networkPolicy.webIngressFrom` lists the ingress controller's pods, as above. If other
    controls already ensure that only the ingress controller reaches the web pods (a
    cluster-wide policy, a service mesh), set `x509.allowUnrestrictedWebIngress: true` instead.
    Keep `auth-tls-pass-certificate-to-upstream: "true"`, so that the controller always sets the
    header itself and replaces any copy a client sends. Use `optional`, never `optional_no_ca`.
    An OpenShift Route can't forward client certificates, so use passthrough there.

### 7. Upgrade { #upgrade_1 }

```bash
helm upgrade dsp oci://ghcr.io/winllc/charts/directory-services-portal --version X.Y.Z -n dsp -f my-values.yaml
```

With one replica, the old API pod stops before the new one starts (`Recreate`). The PVCs, and
with them the license, the installation id and the data, are kept. Back up the data volume
before you upgrade.

With `x509.enabled`, the upgrade generates the X.509 proxy secret if the release has none yet,
and restarts both workloads. Before upgrading an edge-terminated setup (`x509.enabled` without
`tls.enabled`), set `x509.trustedCa`, `networkPolicy.enabled` and `networkPolicy.webIngressFrom`
(or `x509.allowUnrestrictedWebIngress`): otherwise the upgrade stops with a message saying so.

!!! note "Defaults that changed"
    - `x509.revocation.mode` and `.fail` now default to empty: with `x509.enabled`, client
      certificates are checked live at sign-in, soft-fail. Set `fail: closed` for DISA STIG, or
      `mode: "off"` to keep the check off. `helm upgrade --reuse-values` keeps the old `off`.
    - The license PVC is now kept on `helm uninstall`.
    - Nothing changes for single sign-on or the database until you set `sso.*` or `database.*`.

!!! note "Next release"
    - With `directory.mode: memory` the chart sets `DEMO_MODE=true`, which the API requires for the
      built-in demo directory.
    - `sso.oidc.usernameClaim` keeps its default `preferred_username`, because chart defaults
      apply to upgraded releases too, and the API now warns at startup about it. Set an immutable
      claim yourself (`sub`, or `oid` for Entra ID): see
      [Single sign-on](single-sign-on.md#moving-to-an-immutable-claim).
    - **Webhook secrets key.** A new installation that can see the cluster gets a random
      `DSP_SECRETS_KEY` (`integrations.webhooks.secretsKey.generate`); an upgraded release gets none
      and keeps storing webhook secrets unencrypted (a startup warning) until you set
      `integrations.webhooks.secretsKey.value` or `.existingSecret`. Keep a copy of the key.
    - **Sign-out.** `sso.oidc.providerLogout` and `sso.saml.singleLogout` are empty: `true` on a new
      installation, and what the release had on an upgrade (signing out as before).
    - **Shared limits.** With `database.url` the upgrade adds `dsp_rate_limit` (Flyway V4), so the
      per-address limits hold across replicas.
    - Rendering without cluster access (`helm template`, Argo CD) generates no key and sets neither
      sign-out setting: give them explicitly there.

### 8. Uninstall

```bash
helm uninstall dsp -n dsp
```

This deletes the data and audit-file PVCs the chart created; back them up first if you may
reinstall. The license PVC is kept: delete it with `kubectl delete pvc` when you mean to. PVCs
given with `existingClaim` are never touched.

### Several API replicas

More than one API pod needs a shared PostgreSQL (13 or later), which holds sessions, the
sign-in lockout, configuration, the audit store, background jobs and the license state. Move an
existing installation in two steps, so that the first start can import the data and license
volumes:

1. Keep one replica and the data volume, and add the database:

    ```yaml
    database:
      url: jdbc:postgresql://postgres.db.svc:5432/dsp
      username: dsp
      password:
        existingSecret: dsp-database      # key: database-password
    ```

    Check the API log for its `Imported ...` lines.

2. Drop the data volume and scale out:

    ```yaml
    api:
      replicaCount: 3
    persistence:
      data:
        enabled: false
    ```

The chart refuses `replicaCount` above 1 without `database.url`, or with a ReadWriteOnce data or
audit-file volume. Pods without the license volume refuse to start while the database has no
installation id, so always start with one replica first. Don't turn the database off again
afterwards. The chart's README has the details.

### Air-gapped clusters

1. Get the chart: the release download includes `directory-services-portal-<version>.tgz`, or
   run `helm pull oci://ghcr.io/winllc/charts/directory-services-portal --version X.Y.Z` on a
   connected host.
2. Mirror the two images, with the same tags, into your registry:
   `ghcr.io/winllc/directory-services-portal-api:X.Y.Z` and
   `ghcr.io/winllc/directory-services-portal-web:X.Y.Z`. You can also load them from the Docker
   package's `images/` directory.
3. Point the chart at the mirror:

    ```yaml
    global:
      imageRegistry: registry.corp.example:5000   # registry.corp.example:5000/winllc/directory-services-portal-api
    imagePullSecrets:
      - name: corp-registry
    ```

4. Install from the file: `helm install dsp ./directory-services-portal-X.Y.Z.tgz -n dsp -f my-values.yaml`.

The license init container and the `helm test` pod use the same two images, so nothing else
needs to be mirrored.

## Trying it out: the demo

!!! danger "Not for production"
    The demo's credentials are public, printed on its sign-in page, and `admin` can change
    everything in the portal. Use it to look around, never to run the portal.

The portal's source repository has a `docker-compose.demo.yml` at its root. It builds the web tier
and the API from source and runs the API in memory mode (`DIRECTORY_MODE=memory`,
`SEED_DEMO_CONFIG=true`, and from the next release `DEMO_MODE=true`): an embedded demo directory,
with example forms, directories and permissions. Sign in as `admin` (administrator), or `alice`, `bob`, `erin`, `dave` or `frank`,
all with the password `password`.

1. Download an evaluation license for the portal from the ENS licensing portal, and save it as
   `license/license.lic` in the repository. Or skip this and upload it later as `admin`.
2. Start it, always with `-p dsp-demo` so it never shares volumes with another stack:

    === "Docker"

        ```bash
        docker compose -f docker-compose.demo.yml -p dsp-demo up --build
        ```

    === "Podman"

        ```bash
        podman-compose -f docker-compose.demo.yml -p dsp-demo up --build
        ```

3. Reach it. **The demo publishes no port.** The web tier listens on 8080 inside the
   `dsp-demo_demo` network. Either attach a reverse proxy to that network and point it at
   `web:8080`, or write an override file of your own that binds it to this host only:

    ```yaml
    # demo-ports.yml
    services:
      web:
        ports: ["127.0.0.1:8080:8080"]
    ```

    Add `-f demo-ports.yml` after `-f docker-compose.demo.yml` and open <http://localhost:8080>.

4. Reset it, license included, with `down -v` and the same `-f` and `-p` options. A restart
   alone brings back the demo directory as shipped, and keeps forms, permissions and the audit
   trail.

From a release, `./install.sh --demo --bind 127.0.0.1` runs the same demo directory with the
Docker or Podman package.

## Connecting to your directory

For any installation that isn't a demo, set at least these values: in `.env` (Docker package),
`portal.env` (native package), or the chart values shown next to them.

| Setting | Helm value | Example |
|---|---|---|
| `DIRECTORY_MODE` | `directory.mode` | `ldap`. From the next release the API refuses to start with the default, `memory` (the built-in demo directory, whose administrator password is public), unless `DEMO_MODE=true` or it listens on loopback only |
| `LDAP_URL` | `ldap.url` | `ldaps://ldap.example.com:636` |
| `LDAP_BIND_DN` | `ldap.bindDn` | `cn=portal,ou=system,dc=example,dc=com` |
| `LDAP_BIND_PASSWORD_FILE` or `LDAP_BIND_PASSWORD` | `ldap.bindPassword` | (a Secret, mounted as a file) |
| `LDAP_BASE_DN` | `ldap.baseDn` | `dc=example,dc=com` |
| `LDAP_USER_SEARCH_BASE` | `ldap.userSearchBase` | `ou=people,dc=example,dc=com` |
| `LDAP_GROUP_SEARCH_BASE` | `ldap.groupSearchBase` | `ou=groups,dc=example,dc=com` |
| `ADMIN_USERS` | `admin.users` | `jdoe` |
| `ADMIN_GROUPS` | `admin.groups` | `cn=directory-admins,ou=groups,dc=example,dc=com` |
| `ENS_COOKIE_SECURE` | `server.cookieSecure` | `true` whenever browsers use HTTPS |

Separate several administrators with `;` (DNs contain commas); in Helm, use a list. For
`ldaps://` with a private CA, see
[Configuration → LDAPS with a private CA](configuration.md#ldaps-with-a-private-ca), or
`ldap.truststore` in Helm. All settings are described in [Configuration](configuration.md).

The portal has no database and sends no email. Audit events can also go to Splunk or syslog;
see [Audit logging](../audit-logging.md).

## First start checklist

1. Start the portal (`./portal.sh up`, `systemctl start directory-services-portal`, or
   `helm install`).
2. Open the portal and sign in as a user listed in `ADMIN_USERS` or a member of `ADMIN_GROUPS`.
3. Install the license on *Administration > License*, if you didn't give one to the installer
   or the chart. Note the installation id if your license is to be bound.
4. The configuration store starts empty (unless `SEED_DEMO_CONFIG=true`, or the API is in memory
   mode). Continue with the [Administrator guide](admin-guide.md#getting-started):
    1. **Admin → Schema → Pull from directory**
    2. **Admin → Forms**: create a form for an object class
    3. **Admin → Directory definitions**: publish the form in a namespace
    4. **Admin → Permissions**: grant users or groups access
5. Back up the data and the license volumes.

## Verification

| Check | Command / action | Expected |
|---|---|---|
| API is up | `curl -s http://<portal>/api/health` | `{"ok":true}` |
| API is ready | `curl -s http://localhost:3001/readyz` on the API host or in the API container (`docker compose exec api ...`, `kubectl exec ...`) | `{"status":"UP"}`: the directory answers, the audit log is writable, the disk has room |
| Directory reachable | `curl -s http://<portal>/api/info` | JSON with `"connected":true`, `mode`, `version` |
| Images are ENS's | `cosign verify ghcr.io/winllc/directory-services-portal-api@<digest> ...` (see [Security → Supply chain](security.md#supply-chain)) | `Verification for ... The following checks were performed` |
| Proxy works | Open `http://<portal>/` | The login page, with any configured banner and login hint |
| Sign-in | Sign in as an administrator | The **Admin** section appears in the sidebar |
| License | *Administration > License* | The license state, licensee and features; the installation id |
| Docker package | `./portal.sh status` | `api` healthy, plus the URL to open |
| Native package | `systemctl status directory-services-portal` and `journalctl -u directory-services-portal -f` | Active (running) |
| Helm | `kubectl get pods -n dsp` and `helm test dsp -n dsp` | Both pods `Running` and ready; the test pod succeeds |

If `/api/info` shows `"connected": false`, sign in as an administrator: its `error` field then
contains the LDAP error. See the
[troubleshooting runbook](operator-guide.md#troubleshooting-runbook).
