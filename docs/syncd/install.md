# Installation

## At a glance

| Method | Use it for | You run |
|---|---|---|
| [Offline bundle](#install-from-the-bundle) | Any Linux distribution with systemd (glibc 2.34 or newer), x86_64 or aarch64, including hosts with no internet access | `install.sh`, then `syncdctl` |
| [Package](#install-from-a-package) | Debian and Ubuntu (`.deb`); RHEL, Rocky, Alma 9 and newer and Fedora (`.rpm`); RHEL, Rocky, Alma 8 (`.el8.rpm`) | `apt` or `dnf`, then `syncdctl install` |
| [Helm chart](#install-with-helm) | Kubernetes, from the container images (amd64 and arm64; FIPS images for amd64) | `helm install` |

syncd has two parts:

- **The engine** (`syncd`). It reads the sources, merges identities into views and exports them
  to the target directories. It keeps its state in a store of its own on local disk.
- **The web console** (`syncd-console`), with its PostgreSQL database.

On a host, they run as two systemd services. Every form of a release installs the same files,
and `syncdctl` does the rest: service accounts, configuration, the control API's certificates,
the services, and registering the engine in the console.

On Kubernetes, the engine is a StatefulSet with one replica, or two as an
[active/passive pair](#an-active-passive-pair), and the console a Deployment. The chart issues
the certificates and registers the engine.

From 0.4.0 two engines can also run as an active/passive pair on two hosts (see
[An active/passive pair on hosts](#an-activepassive-pair-on-hosts)). How the pair works is in
[High availability](availability-and-sizing.md#high-availability).

!!! note "No license to add"
    There is no ENS license check yet (licensing is on the roadmap), so there is no license to
    add. See [Licensing](../licensing.md).

For the settings referred to here, see [Configuration](configuration.md). Day-to-day running is in
the [Operator guide](operator-guide.md).

## Prerequisites

The requirements for the [Helm chart](#install-with-helm) are listed with it.

| Requirement | Notes |
|---|---|
| **Linux with systemd** | x86_64 or aarch64. Releases are built for both; the aarch64 files are only there when the release was built with the arm64 runners (see [Getting the release](#getting-the-release)). The FIPS bundle is x86_64 only |
| **glibc 2.34 or newer** | Releases are built on Rocky Linux 9, so they run on RHEL 9, Ubuntu 22.04, Debian 12 and anything newer. A release built elsewhere needs at least the build host's glibc. |
| **RHEL, Rocky or Alma 8** | Use the `.el8.rpm` (x86_64 only). It is built on Rocky Linux 8, and its build checks that the binaries need nothing newer than RHEL 8 has. RHEL 8's default PostgreSQL is 10, too old for the console: `sudo dnf module enable postgresql:16` first. |
| **PostgreSQL 13 or newer** | For the console. On the same host, or elsewhere. |
| **`openssl`** | Issues the control API's certificates at install time. The packages depend on it. |
| **PostgreSQL client tools** | `pg_dump`, `pg_restore` and `psql`, for backups. Upgrades refuse to run without them unless you pass `--skip-db-backup`. The `.deb` recommends `postgresql-client`; the `.rpm` recommends `postgresql`. |
| **`curl`** *(recommended)* | For the health checks. Without it, `syncdctl` falls back to a bash TCP check. |
| **`git`** *(recommended)* | For the console's Git sync (see [Configuration as code](config-as-code.md)). From 0.4.0 the packages recommend it. Not needed otherwise |
| **Disk** | Room for the engine store, its history, and backups: see [Sizing](availability-and-sizing.md#sizing). An upgrade checks that `/var/lib/syncd/backups` has room for a backup before it stops anything. |
| **Java 17 or newer** *(only for the JDBC bridge)* | To read databases syncd has no driver of its own for (Db2, Informix, Oracle, ...). See [The JDBC bridge](#the-jdbc-bridge). |
| **The source and target systems** | Network access from the engine's host to the databases and directories it reads and writes, and the accounts it uses. See [Connectors](connectors.md). |

## Getting the release

Releases are published as GitHub releases of the syncd source repository
(`https://github.com/winllc/ldap-sync-merge-view/releases`), one per version tag. The release
build (the `Package` workflow) runs on every `v*` tag: it builds the bundles and the packages on
Rocky Linux 9 and Rocky Linux 8, runs the install, upgrade, rollback, backup and uninstall
lifecycle on each, checks the tag against the version in the source, and only then publishes the
files, with `SHA256SUMS` and its signature.

!!! warning "Nothing is published yet"
    The source repository has no release tags yet, so no GitHub release, container image or
    chart has been published, for 0.4.0 or any earlier version. Until one is, build from source:
    `scripts/package.sh` (and `scripts/package-el8.sh`) for the bundle and packages, `docker
    build` for the images (below), and `helm install` from `deploy/helm/syncd` for the chart.
    The names and addresses on this page are where each release will be published once it is
    tagged.

| File | For |
|---|---|
| `syncd-<version>-linux-x86_64.tar.gz`, `syncd-<version>-linux-aarch64.tar.gz` | The offline bundle, for x86_64 and aarch64 (arm64): any distribution with systemd and glibc 2.34+, including hosts without internet access. Each with its `.sha256` |
| `syncd-<version>-linux-x86_64-fips.tar.gz` | The FIPS bundle: the same release built with the FIPS 140-3 validated module, for `syncdctl install --fips`. x86_64 only, with its `.sha256` |
| `syncd_<version>_amd64.deb`, `syncd_<version>_arm64.deb` | Debian and Ubuntu, through apt |
| `syncd-<version>-1.x86_64.rpm`, `syncd-<version>-1.aarch64.rpm` | RHEL, Rocky and Alma 9 and newer, and Fedora, through dnf |
| `syncd-<version>-1.el8.x86_64.rpm` | RHEL, Rocky and Alma 8, through dnf (x86_64 only) |
| `syncd-<version>.cdx.json`, `syncd-console-<version>.cdx.json` | The software bill of materials of the engine and of the console (CycloneDX JSON) |
| `SHA256SUMS` | The checksums of every file above |
| `SHA256SUMS.asc` | The release signing key's GPG signature over `SHA256SUMS` |

- **aarch64.** The aarch64 bundle and packages, and the arm64 images, are built only when the
  source repository's variable `ARM64_RUNNERS` is `true`. Without it the release is x86_64 only,
  and the release workflow's summary says so.
- **FIPS.** The FIPS bundle and the FIPS images are x86_64 (amd64) only. There are no FIPS
  `.deb` or `.rpm` packages. See [FIPS 140-3 and DISA STIG](fips-and-stig.md).
- **No package repositories.** There are no signed apt or dnf repositories: install the
  downloaded files.
- A GitHub release's notes are the release notes from the source repository
  (`docs/releases/<version>.md`).

### Verifying a release

New in 0.4.0. Check what you downloaded before you install it:

```bash
# 1. The signature: SHA256SUMS came from the release signing key (import the key once)
gpg --import syncd-release.asc
gpg --verify SHA256SUMS.asc SHA256SUMS

# 2. The checksums: each file you downloaded matches what was signed
sha256sum -c SHA256SUMS --ignore-missing
```

- **Both must succeed.** `gpg` reports a good signature from the syncd release key. Check the
  key's fingerprint against the one you were given separately, never against anything in the
  download itself. The public key (`syncd-release.asc`) is given to customers by the publisher;
  it is not one of the release's files.
- **A release without `SHA256SUMS.asc`** was published without the signing key (the release
  workflow's summary says so). Its checksums still show that a file is complete, not who built
  it.
- **The bundle alone:** `sha256sum -c syncd-0.4.0-linux-x86_64.tar.gz.sha256`. Inside the bundle,
  every file is listed in `MANIFEST.sha256`, and the installer checks the release against it
  before it copies anything, so a damaged archive is refused even without these steps.
- **SBOMs.** The two CycloneDX files list the crates and the web console's packages each
  binary was built from, for your vulnerability scanner. They are covered by `SHA256SUMS`. The
  images carry SPDX SBOMs of their own as signed attestations (below), not as release files.

### Container images and the Helm chart

A second workflow (`Container`) builds the images and the Helm chart on every push, scans the
images, installs them in a kind cluster, and, on a `v*` tag only, pushes them to the GitHub
container registry and signs them. None has been pushed yet (see above):

| What | Where |
|---|---|
| The engine's image | `ghcr.io/winllc/syncd:<version>`, and `ghcr.io/winllc/syncd:<version>-fips` |
| The console's image | `ghcr.io/winllc/syncd-console:<version>` (with the web UI, `git` and `ssh`), and `<version>-fips` |
| The Helm chart | `oci://ghcr.io/winllc/charts/syncd`, version `<version>` |

- **Tags.** Each release is tagged with its version, for example `0.4.0`, and its FIPS variant
  `0.4.0-fips`. There is no `latest`. The chart's version and `appVersion` are the release's.
- **Platforms.** A tag holds `linux/amd64`, and `linux/arm64` when the release was built with
  `ARM64_RUNNERS` set. `docker buildx imagetools inspect ghcr.io/winllc/syncd:<version>` lists a
  tag's platforms. The `-fips` tags are amd64 only.
- **FIPS images** are the same release built with the FIPS 140-3 module. Set
  `SYNCD_FIPS_MODE=true` in both containers to turn the mode on (see
  [FIPS images](#fips-images)). The standard images refuse `SYNCD_FIPS_MODE` at startup (`not a
  FIPS build`), so the mode is never a silent downgrade.
- **Base.** Distroless (`gcr.io/distroless/cc-debian12:nonroot`), pinned by digest in the
  Dockerfile: no shell and no package manager. Both images run as uid and gid 65532, and work on
  a read-only root file system.
- **Scanned.** Every build scans the images with Trivy, and a fixable HIGH or CRITICAL finding
  fails it. Accepted findings are listed, each with its reason and an end date, in the source
  repository's `.trivyignore`.
- **Signed.** On a release tag, each image and the chart are signed with cosign: keyless
  signatures (Sigstore), made with the release workflow's GitHub identity, always by digest. Each
  image also carries its SPDX SBOM (one per platform) as a signed attestation. The chart is not
  attached to the GitHub release.

### Verifying the images and the chart

```bash
# A tag's digest (the image index: every platform)
docker buildx imagetools inspect ghcr.io/winllc/syncd:0.4.0

# The image's signature, and its SBOM attestations
cosign verify ghcr.io/winllc/syncd@<digest> \
  --certificate-identity-regexp 'https://github.com/winllc/ldap-sync-merge-view/.github/workflows/.*' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
cosign verify-attestation --type spdxjson ghcr.io/winllc/syncd@<digest> \
  --certificate-identity-regexp 'https://github.com/winllc/ldap-sync-merge-view/.github/workflows/.*' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com

# The chart (its digest is the one `helm pull` prints)
cosign verify ghcr.io/winllc/charts/syncd@<digest> \
  --certificate-identity-regexp 'https://github.com/winllc/ldap-sync-merge-view/.github/workflows/.*' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
```

Do the same for `ghcr.io/winllc/syncd-console` and the `-fips` tags. The release workflow's
summary lists every digest it signed. cosign contacts Sigstore's public services to verify a
keyless signature, so on an air-gapped network, verify on a connected machine before you mirror
the images (see [Air-gapped clusters](#air-gapped-clusters)).

**Images you build yourself**, from the source repository:

```bash
docker build -f deploy/container/Dockerfile --target engine -t registry.example.com/syncd:0.4.0 .
docker build -f deploy/container/Dockerfile --target console -t registry.example.com/syncd-console:0.4.0 .
# The FIPS variant
docker build -f deploy/container/Dockerfile --target engine -t registry.example.com/syncd:0.4.0-fips \
  --build-arg FEATURES=syncd/fips,syncd-console/fips .
```

The build is for the host's platform. Push the images to a registry your cluster can pull from,
then set `image.engine.repository`, `image.console.repository` and their `tag` in the chart's
values, and install the chart from the checkout: `helm install syncd ./deploy/helm/syncd ...` in
place of the `oci://` address below.

!!! note "Not in the ENS licensing portal"
    Unlike the other ENS applications, syncd releases are not delivered through the
    **Downloads** page of the ENS licensing portal, and `SHA256SUMS` is signed with syncd's own
    release key, not the ENS release key. [Releases and downloads](../releases.md) describes the
    portal and the signatures for the other products.

**Builds you make yourself.** `scripts/package.sh` in the source repository builds the bundle, the
`.deb` and the `.rpm` into `dist/`, for the host's architecture (on an aarch64 host,
`syncd-<version>-linux-aarch64.tar.gz`, `syncd_<version>_arm64.deb` and
`syncd-<version>-1.aarch64.rpm`), and `scripts/package-el8.sh` the RHEL 8 `.rpm`. A FIPS build
(`deploy/packaging/build.sh --fips --packages`) has `-fips` in its name and a `FIPS` marker file.
See [FIPS 140-3 and DISA STIG](fips-and-stig.md).

## Install from the bundle

```bash
sha256sum -c syncd-0.4.0-linux-x86_64.tar.gz.sha256
tar -xzf syncd-0.4.0-linux-x86_64.tar.gz
sudo ./syncd-0.4.0-linux-x86_64/install.sh --local-database
```

On aarch64, use `syncd-0.4.0-linux-aarch64.tar.gz`. For FIPS mode, use
`syncd-0.4.0-linux-x86_64-fips.tar.gz` and add `--fips`.

`install.sh` runs `bin/syncdctl install` with the options you give it. On a host where syncd is
already installed (`/etc/syncd/console.env` exists), it runs `syncdctl upgrade` instead (see
[Upgrade](#upgrade)).

### The console's database

Choose one:

- **`--local-database`** sets the database up on this host's PostgreSQL server:
    - it creates a role named `syncd-console` and a database named `syncd_console`;
    - the console signs in through the local socket with peer authentication, so no password is
      stored;
    - it runs `psql` as the `postgres` system account against `/var/run/postgresql`, port 5432.
      Set `SYNCD_PG_SOCKET` and `SYNCD_PG_PORT` for another socket directory or port.
- **`--database-url`** uses a database server elsewhere. Create the database and its account
  first, then pass its URL:

    ```bash
    sudo ./install.sh --database-url 'postgres://syncd:secret@db.example.com/syncd_console?sslmode=require'
    ```

The URL is written to `CONSOLE_DATABASE_URL` in `/etc/syncd/console.env`, which only root and the
`syncd-console` account can read.

### syncdctl install options

| Option | Effect |
|---|---|
| `--local-database` | Creates the console's role and database on this host's PostgreSQL (above) |
| `--database-url URL` | Uses this PostgreSQL database for the console |
| `--listen ADDR` | The console's address. Default `127.0.0.1:8080`. For other machines, give the console a certificate or put nginx in front (see [HTTPS in front of the console](#https-in-front-of-the-console)) |
| `--fips` | Runs both services with the FIPS 140-3 module (`SYNCD_FIPS_MODE=true` in both environment files). Refused unless the release is a FIPS build (the FIPS bundle) |
| `--no-start` | Installs everything but starts nothing. Start later with `systemctl enable --now syncd syncd-console` |

One of `--local-database` and `--database-url` is required. `install` is refused on a host that
already has syncd; use `syncdctl upgrade` there.

### What the installer does

1. Checks the release against its `MANIFEST.sha256`.
2. Creates the `syncd` and `syncd-console` system accounts (no login shell, home
   `/var/lib/syncd`).
3. Copies the release to `/opt/syncd/releases/<version>` and points `/opt/syncd/current` at it.
   Links `syncd`, `syncd-console` and `syncdctl` into `/usr/local/bin`.
4. With `--local-database`, creates the role and database.
5. Writes the configuration to `/etc/syncd`: `console.env`, `engine.env` and
   `engine/engine.yaml`. Files that already exist are kept.
6. Issues the control API's certificates from a CA of the host's own, in `/etc/syncd/pki`. The
   engine and the console talk to each other over mutual TLS, even on loopback.
7. Installs the systemd units, enables and starts both services, and waits for both to be
   healthy (120 seconds; `SYNCD_HEALTH_TIMEOUT` changes it).
8. Registers the engine in the console, as `local` at `127.0.0.1:7443`.

At the end it prints the console's address and the `admin` account's one-time password (see
[First sign-in](#first-sign-in)).

## Install from a package

```bash
sudo apt install ./syncd_0.4.0_amd64.deb            # Debian, Ubuntu (arm64: syncd_0.4.0_arm64.deb)
sudo dnf install ./syncd-0.4.0-1.x86_64.rpm         # RHEL, Rocky, Alma 9+, Fedora (aarch64: syncd-0.4.0-1.aarch64.rpm)
sudo dnf install ./syncd-0.4.0-1.el8.x86_64.rpm     # RHEL, Rocky, Alma 8
sudo syncdctl install --local-database              # or --database-url ...
```

The package unpacks the release to `/opt/syncd/releases/<version>`, installs the units in
`/usr/lib/systemd/system`, links the commands into `/usr/bin`, creates the service accounts, and
says how to finish. `syncdctl install`, with the [same options](#syncdctl-install-options), then
does the rest as with the bundle. From 0.4.0 the packages recommend `git`, which the console's Git
sync needs; apt installs recommended packages by default, dnf when its `install_weak_deps` is on.

A host installed from a package is upgraded and removed with the package manager. `syncdctl
upgrade`, `rollback` and `uninstall` refuse to run there and say so. There are no FIPS packages:
use the FIPS bundle for FIPS mode.

## HTTPS in front of the console

The console serves plain HTTP on `127.0.0.1:8080`. Its session cookie is `Secure`, so browsers
keep it only over HTTPS, or on `localhost`. Opened at `http://<server>:8080` from another machine,
sign-in cannot stick, and the sign-in page says so. To use the console from other machines, give
it HTTPS, in one of two ways:

| | [The console's own HTTPS](#the-consoles-own-https) | [nginx in front](#nginx-in-front-of-the-console) |
|---|---|---|
| Port | 1024 or above (for example 8443): the console doesn't run as root | 443 |
| Certificate | PEM files the console reads, picked up again within a minute when renewed | nginx's |
| HTTP/2, a certificate shared with other sites | No (HTTP/1.1 only) | Yes |
| Extra software | None | nginx |

### The console's own HTTPS

The console serves HTTPS itself when it is given a certificate. In `/etc/syncd/console.env`:

```bash
CONSOLE_LISTEN=0.0.0.0:8443
CONSOLE_TLS_CERT=/etc/syncd/console-tls/fullchain.pem    # the certificate, followed by its chain
CONSOLE_TLS_KEY=/etc/syncd/console-tls/privkey.pem
CONSOLE_PUBLIC_URL=https://syncd.example.com:8443         # for single sign-on
```

```bash
sudo chown root:syncd-console /etc/syncd/console-tls/*
sudo chmod 0640 /etc/syncd/console-tls/privkey.pem
sudo firewall-cmd --permanent --add-port=8443/tcp && sudo firewall-cmd --reload   # RHEL
sudo systemctl restart syncd-console
```

- **Both or neither.** `CONSOLE_TLS_CERT` and `CONSOLE_TLS_KEY` go together; the console refuses
  to start with one alone. It also refuses a file it cannot read, a certificate file with no
  certificate in it, and a key that does not go with the certificate, and names the file.
- **Renewals** (certbot's deploy hook copying the new files there, say) are picked up within a
  minute, without a restart, and logged as `TLS certificate loaded again`. A renewal that does
  not load is logged (`the changed TLS certificate does not load; keeping the previous one`), and
  the previous certificate stays in use.
- **Connections.** Each TLS handshake has 10 seconds, and a client that stalls holds up no other.
  The console offers HTTP/1.1 only.
- **`CONSOLE_TRUSTED_PROXIES`** is not needed: clients connect to the console directly, and the
  audit trail records their addresses.
- **Older hosts.** A host installed before 0.3.0 keeps its `console.env`, so the commented
  `CONSOLE_TLS_CERT` and `CONSOLE_TLS_KEY` lines of a new install's file are not in it. Add the
  settings yourself.

### nginx in front of the console

For port 443, a certificate shared with other sites, or HTTP/2, put nginx with TLS in front of the
console. The release ships an nginx configuration in `/opt/syncd/current/share/nginx/syncd.conf`.

Below, replace `syncd.example.com` and `192.0.2.10` with the server's name and address.

**1. Install nginx** and open HTTPS in the firewall.

=== "RHEL, Rocky, Alma 8 and 9"

    ```bash
    sudo dnf install -y nginx
    sudo setsebool -P httpd_can_network_connect 1     # SELinux: let nginx reach the console
    sudo firewall-cmd --permanent --add-service=https --add-service=http
    sudo firewall-cmd --reload
    ```

=== "Ubuntu, Debian"

    ```bash
    sudo apt-get install -y nginx
    sudo rm -f /etc/nginx/sites-enabled/default
    sudo ufw allow 'Nginx Full'                        # if ufw is enabled
    ```

**2. Create a certificate.** With a certificate from your CA, copy it and its key to
`/etc/nginx/tls/syncd.crt` and `/etc/nginx/tls/syncd.key`. For a self-signed one, list every name
and address people will type (browsers check `subjectAltName`):

```bash
sudo install -d -m 0750 /etc/nginx/tls
sudo openssl req -x509 -newkey rsa:2048 -nodes -days 825 \
  -subj "/CN=syncd.example.com" \
  -addext "subjectAltName=DNS:syncd.example.com,IP:192.0.2.10" \
  -keyout /etc/nginx/tls/syncd.key -out /etc/nginx/tls/syncd.crt
sudo chmod 600 /etc/nginx/tls/syncd.key
```

**3. Install the nginx configuration**, with your names in `server_name`:

```bash
sudo cp /opt/syncd/current/share/nginx/syncd.conf /etc/nginx/conf.d/syncd.conf
sudo sed -i 's/server_name syncd.example.com;/server_name syncd.example.com 192.0.2.10;/' \
  /etc/nginx/conf.d/syncd.conf
sudo nginx -t && sudo systemctl enable --now nginx && sudo systemctl reload nginx
```

The shipped configuration redirects port 80 to HTTPS, allows TLS 1.2 and 1.3, accepts request
bodies up to 2 MB (configuration imports), sets `X-Forwarded-For` to the client's address
(replacing whatever the client sent), and turns buffering off with a one-hour read timeout, for the
live status stream (server-sent events).

**4. Tell the console about the proxy** in `/etc/syncd/console.env`, then restart it:

```bash
CONSOLE_LISTEN=127.0.0.1:8080                   # reachable through nginx only
CONSOLE_PUBLIC_URL=https://syncd.example.com    # also the base of the single sign-on redirect URI
CONSOLE_TRUSTED_PROXIES=127.0.0.1               # believe nginx's X-Forwarded-For
```

```bash
sudo systemctl restart syncd-console
```

`CONSOLE_TRUSTED_PROXIES` lists the proxies (addresses or CIDR ranges, comma-separated) whose
`X-Forwarded-For` header names the client. With it, the audit trail records users' own addresses,
and the sign-in limit (20 attempts per address in 5 minutes) applies to each user. Without it,
every request seems to come from nginx: the audit trail shows `127.0.0.1`, and all users share one
sign-in budget. List only proxies you run; a request from anywhere else is taken at its own
address, whatever header it sends.

**5. Check it:**

```bash
curl -k -o /dev/null -w '%{http_code}\n' https://syncd.example.com/   # 200
sudo syncdctl status                                                   # users: https://...
```

Then open `https://syncd.example.com`. With a self-signed certificate the browser warns once;
accept it, or import `/etc/nginx/tls/syncd.crt` into the clients' trusted certificates (Windows:
`certmgr.msc`, Trusted Root Certification Authorities; macOS: Keychain Access, Always Trust; Linux:
copy it to `/usr/local/share/ca-certificates/` and run `update-ca-certificates`).

!!! warning "Listening beyond loopback without HTTPS"
    If `CONSOLE_LISTEN` is not a loopback address, the console has no certificate of its own, and
    `CONSOLE_PUBLIC_URL` is not an `https://` address, the console logs a warning at startup:
    browsers on other machines will not keep its session cookie. It still starts.

## First sign-in

1. Open the console (through nginx, or `http://127.0.0.1:8080` on the host itself).
2. Sign in as `admin` with the one-time password the installer printed. It is also in
   `/var/lib/syncd/console/initial-admin-password` (mode 0600, readable by `syncd-console` only)
   until it has been used.
3. Set a new password. Nothing else works until you do. A password needs at least 12 characters,
   and may not be the username or `admin`. Once it is changed, the file is deleted.

An installer can supply the first password instead, with `CONSOLE_ADMIN_PASSWORD` or
`CONSOLE_ADMIN_PASSWORD_FILE` in `console.env` before the first start. `admin` and empty passwords
are refused, and so is one that breaks the password rule: the console does not start.

If the account locked, or the password is lost, issue a new one-time password on the host:

```bash
sudo runuser -u syncd-console -- env -C / \
  $(sudo grep -E '^[A-Za-z_][A-Za-z0-9_]*=' /etc/syncd/console.env) \
  CONSOLE_DATA_DIR=/var/lib/syncd/console \
  /opt/syncd/current/bin/syncd-console admin reset-password --user admin
```

It prints the password, unlocks the account, signs out its sessions and records
`user.password_reset` in the audit trail. It refuses an account that signs in through single
sign-on, as the **Users** page does: a password would let it sign in without the identity
provider and its MFA. To give one anyway, while the provider is unavailable say, add
`--allow-single-sign-on`; the audit record then notes `single_sign_on: true`. On Kubernetes, run
the same command in the console's pod (see [Install with Helm](#install-with-helm)). Next steps:
add accounts and roles, and set up [single sign-on](single-sign-on.md) if you use it
([Admin guide](admin-guide.md)).

## Registering engines

The installer registers the engine on the same host as `local`. A console can manage more engines:
an administrator registers each one under **Engines** → **Register engine**, with:

| Field | |
|---|---|
| Name | 1 to 64 letters, digits, `.`, `_` or `-` |
| Control API address | `host:port`, as in the engine's `api.listen`; or `unix:` and the absolute path of its control socket (`unix:/run/syncd/control.sock`, as in `api.control_socket`) |
| Engine CA certificate, console client certificate and key (PEM) | For mutual TLS. Required unless the address is on loopback or a `unix:` socket, and refused with a `unix:` address. The key is stored encrypted with the console's master key and never shown again |

The console checks that the engine answers before it saves it, and records `engine.register` in
the audit trail. On the host, `syncd-console admin register-engine --name N --address HOST:PORT
--server-ca FILE --client-cert FILE --client-key FILE` does the same. With an engine of that name
registered already:

- **Without `--update`** it does nothing (`engine N is registered already`). From 0.4.0 it fails
  instead when the stored client key no longer opens with the console's master key (the key was
  replaced), and says why: `engine N is registered, but this console's master key cannot open its
  stored client key`. `POST /api/v1/engines` answers the same, with `400`.
- **With `--update`** (`replace: true` through the API) it replaces that engine's address and
  credentials, keeping its id and configuration versions, and records `engine.update`. When the
  stored key could not be opened, it seals the new one over it and records
  `engine.re-registered`. `syncdctl renew-certs` and the Helm chart's registration Job use it.

A `unix:` address only works for a console on the engine's host, running as a user that may open
the socket. The socket is mode 0600, owned by the engine's user, so the packaged
`syncd-console` account cannot open it: the installer keeps registering the engine at
`127.0.0.1:7443` with mutual TLS.

The engine's page in the console warns when the console's client certificate for the engine has
less than 60 days left, and when it has expired. See
[Certificates](operator-guide.md#certificates) for renewing them.

!!! note "Engines on other hosts"
    The installed engine's control API listens on `127.0.0.1:7443`, and its certificate (from the
    host's own CA) names only `localhost`, `127.0.0.1` and `::1`. For a console on another host to
    manage it, the engine needs an `api.listen` that host can reach, and a certificate for the name
    the console uses. `api` can only be changed in the engine's file on its host (see
    [Configuration](configuration.md)). syncdctl does not issue such certificates, and
    `syncdctl renew-certs` renews them for `localhost` only. The engines of an
    [active/passive pair](#an-activepassive-pair-on-hosts) need such certificates too.

## An active/passive pair on hosts

New in 0.4.0. Two engines on two hosts, with the same configuration, share a lease in PostgreSQL
(the console's database works) or in a file on a shared file system. The one holding it runs the
sources and the exports; the other keeps a warm copy of the store from snapshots and takes over
when the lease expires. How it works, its failover window and its limits are in
[High availability](availability-and-sizing.md#high-availability); running it is in the
[Operator guide](operator-guide.md#high-availability).

`syncdctl` does not set up a pair. On two hosts installed from the bundle or the packages:

1. **Install both as usual.** `syncdctl` gives each host a CA of its own and an engine certificate
   for `localhost`; a pair needs more:
    - Make the second host trust the first's CA: copy `/etc/syncd/pki/ca.crt` and `ca.key` from
      the first host over the second's, and run `sudo syncdctl renew-certs` there. It issues the
      second host's console and command-line certificates from that CA.
    - On each host, replace `/etc/syncd/pki/engine.crt` and `engine.key` with a certificate from
      that CA that names the host: from your PKI, or with `openssl`, with
      `subjectAltName=DNS:idm1.example.gov` and `extendedKeyUsage=serverAuth`.
      `renew-certs` issues a `localhost` one again, so repeat this after each renewal.
    - The command line's `cli.crt` is what a passive engine pulls snapshots with.
2. **Configure both engines** in `/etc/syncd/engine/engine.yaml`: `api.listen: 0.0.0.0:7443`
   with `api.tls`, and the same `engine.ha`:

    ```yaml
    engine:
      data_dir: /var/lib/syncd/engine
      ha:
        name: idm                          # the pair: both engines use the same name
        lease:
          backend: postgres                # or file, with path: on a file system both hosts share
          url: { env: SYNCD_HA_LEASE_URL } # postgres://user:password@db.example.gov/syncd_console
          # tls_verify: true               # check the database's certificate (ca_file: ...)
          ttl_secs: 15                     # at least 3
        snapshot_every_secs: 300           # at least 10
        # role: passive                    # a standby that never takes the lease
        # member: idm1                     # this engine's name in the pair; the host name by default
        # advertise: idm1.example.gov:7443 # where the other engine reaches this one's API
    ```

    Put `SYNCD_HA_LEASE_URL` in `/etc/syncd/engine.env` on both hosts. Set
    `SYNCD_HA_ADVERTISE=<this host's name>:7443` there too when the host name is not the name in
    the engine's certificate.
3. **Open 7443 between the two hosts**, so each engine reaches the other's control API.
4. **Start the first engine, then the second.** The first takes the lease; the second starts
   passive and pulls a snapshot.
5. **Register both engines in the console**, each at its host's address with mutual TLS (see
   [Registering engines](#registering-engines)). The console shows them as one pair.

Both hosts need the same console master key or passphrase for the password key store, which the
snapshots carry as it is on disk. `engine.ha` can only be changed on the host: the engine refuses a
configuration published from the console that changes it, as it does `engine.data_dir` and `api`.

The pair can also come from the environment alone, with a configuration file that says nothing
of it: `SYNCD_HA_NAME` (the pair), `SYNCD_HA_LEASE_URL` or `SYNCD_HA_LEASE_PATH`, and optionally
`SYNCD_HA_LEASE_TTL_SECS` and `SYNCD_HA_SNAPSHOT_EVERY_SECS`. `SYNCD_HA_MEMBER` and
`SYNCD_HA_ADVERTISE` override `member` and `advertise` either way. A file with an `engine.ha` of
its own keeps it. On Kubernetes, `engine.ha.enabled` does all of this (see
[An active/passive pair](#an-active-passive-pair)).

## Install with Helm

The chart (`deploy/helm/syncd` in the source repository, to be published as
`oci://ghcr.io/winllc/charts/syncd` once a release is tagged) runs the two
[container images](#container-images-and-the-helm-chart):

- **The engine** is a StatefulSet with **one replica**, or two with `engine.ha.enabled` (an
  [active/passive pair](#an-active-passive-pair)). Its store and its configuration file are on a
  volume, one per engine.
- **The console** is a Deployment. It keeps its data in a PostgreSQL that you provide.
- **The chart issues the control API's certificates** (mutual TLS between the console and the
  engine) from a CA of its own, and generates the console's master key and the initial `admin`
  password. All of them are kept across upgrades and uninstalls.
- **A Job registers the engine in the console** after each install and upgrade.

| Requirement | Notes |
|---|---|
| **Kubernetes on amd64 or arm64 nodes** | arm64 needs a release built with arm64 images (see [Container images](#container-images-and-the-helm-chart)); the FIPS images are amd64 only. The CI test runs on kind |
| **Helm 3.8 or newer** | For OCI charts. CI uses Helm 3.16 |
| **PostgreSQL 13 or newer** | For the console, outside the chart: the chart has no bundled database. An HA pair keeps its lease there too |
| **A StorageClass** | The cluster's default, or one you name, for the engine's volume (`ReadWriteOnce`, 10 GiB by default) |
| **An Ingress controller** *(recommended)* | To reach the console from outside the cluster, with TLS |
| **Prometheus Operator** *(optional)* | For the ServiceMonitor and the PrometheusRule |
| **A CNI that enforces NetworkPolicies** *(optional)* | For `networkPolicy.enabled` |
| **Network access from the engine's pod** | To the databases and directories it reads and writes |

### 1. Install the chart

Create the console's database, as for a host install:

```sql
CREATE ROLE syncd_console LOGIN PASSWORD '...';
CREATE DATABASE syncd_console OWNER syncd_console;
```

Create the namespace, and a Secret with the secrets the engine's configuration will refer to
(`password: { env: HR_DB_PASSWORD }`). Its keys become the engine's environment variables. It can
be added later too.

```bash
kubectl create namespace syncd
kubectl -n syncd create secret generic syncd-engine-env \
  --from-literal=HR_DB_PASSWORD='...' --from-literal=TARGET_LDAP_PASSWORD='...'
kubectl -n syncd create secret generic syncd-console-db \
  --from-literal=url='postgres://syncd_console:...@db.example.com:5432/syncd_console?sslmode=require'
```

Write your values to a file, for example `syncd-values.yaml`:

```yaml
engine:
  envFromSecret: syncd-engine-env
console:
  database:
    existingSecret: syncd-console-db       # key url
  trustedProxies: 10.0.0.0/8               # the ingress controller's pods
  ingress:
    enabled: true
    className: nginx
    host: syncd.example.com
    tls:
      - secretName: syncd-tls
        hosts: [syncd.example.com]
```

Then install:

```bash
helm install syncd oci://ghcr.io/winllc/charts/syncd --version 0.4.0 \
  --namespace syncd -f syncd-values.yaml --wait
```

Until a release is published (see [Getting the release](#getting-the-release)), install from a
checkout of the source repository, with images you built and pushed:

```bash
helm install syncd ./deploy/helm/syncd --namespace syncd -f syncd-values.yaml --wait \
  --set image.engine.repository=registry.example.com/syncd \
  --set image.console.repository=registry.example.com/syncd-console
```

`console.database.url` or `console.database.existingSecret` is required. The chart stops with
`console.database.url or console.database.existingSecret is required` if both are missing.

The resources are named after the release: `<release>-syncd-...`, or `<release>-...` when the
release's name contains `syncd`. For a release named `syncd`, as here, they are the StatefulSet
`syncd-engine` (pod `syncd-engine-0`), the Deployment and Service `syncd-console`, the Service
`syncd-engine`, the Secrets `syncd-console` and `syncd-pki`, and the Job `syncd-register`. The
examples on these pages use those names. [Production values](#production-values) covers single
sign-on, the console's own HTTPS, extra volumes, NetworkPolicies, the alert rules and an HA pair.

### 2. First sign-in

`helm status syncd -n syncd` prints how to read the initial `admin` password:

```bash
kubectl -n syncd get secret syncd-console -o jsonpath='{.data.admin-password}' | base64 -d; echo
```

Sign in with it; it must be changed then, as on a host (see [First sign-in](#first-sign-in)).
Without an Ingress, use a port-forward:

```bash
kubectl -n syncd port-forward svc/syncd-console 8080:8080     # then http://127.0.0.1:8080
```

(With `console.tls.existingSecret`, open `https://127.0.0.1:8080` instead.)

The engine starts with no sources and waits for the console to publish a configuration. It is
registered in the console under the release's name (`syncd` here), not `local`. Configure it in
the console as on any other install.

To issue a new one-time password (a locked or lost `admin` account), run the console's own command
in its pod. The image has no shell, but the pod has the console's settings:

```bash
kubectl -n syncd exec deploy/syncd-console -- syncd-console admin reset-password --user admin
```

### 3. Key values

| Value | Default | What it sets |
|---|---|---|
| `image.engine.repository`, `.tag`, `.pullPolicy` | `ghcr.io/winllc/syncd`, the chart's `appVersion`, `IfNotPresent` | The engine's image. `<version>-fips` for the FIPS image |
| `image.console.repository`, `.tag`, `.pullPolicy` | `ghcr.io/winllc/syncd-console`, the chart's `appVersion`, `IfNotPresent` | The console's image, also used by the registration Job |
| `imagePullSecrets` | `[]` | For a private registry or mirror |
| `podSecurityContext`, `securityContext` | uid and gid 65532, `fsGroup` 65532, `runAsNonRoot`, seccomp `RuntimeDefault`; no privilege escalation, read-only root file system, every capability dropped | Both components and the Job |
| `engine.name` | The release's name | The name the console knows the engine by |
| `engine.envFromSecret` | | A Secret whose keys become the engine's environment variables: the secrets its configuration refers to, and S3 keys for backups |
| `engine.env` | `[]` | More environment variables, for example `RUST_LOG` or `SYNCD_FIPS_MODE` |
| `engine.extraVolumes`, `.extraVolumeMounts` | `[]` | More volumes on the engine's pod: a CA bundle, a CSV inbox (see [Extra volumes](#extra-volumes)) |
| `engine.persistence.size`, `.storageClass`, `.accessModes` | `10Gi`, the default class, `[ReadWriteOnce]` | The engine's volume, one per engine (see [Sizing](availability-and-sizing.md#sizing)) |
| `engine.resources` | Requests 250m CPU and 512Mi; memory limit 4Gi | The engine's pod. The memory limit is the engine's `syncd_memory_limit_bytes` |
| `engine.terminationGracePeriodSeconds` | `90` | Time the engine has to finish its batch and stop, as `TimeoutStopSec` on a host |
| `engine.nodeSelector`, `.tolerations`, `.affinity`, `.podAnnotations` | | Scheduling, annotations. `affinity` replaces the HA pair's anti-affinity |
| `engine.service.type` | `ClusterIP` | The Service with the control API (`api`, 7443) and metrics (`metrics`, 9464) |
| `engine.ha.enabled` | `false` | Two engine pods as an [active/passive pair](#an-active-passive-pair) |
| `engine.ha.leaseTtlSeconds`, `.snapshotEverySeconds` | `15`, `300` | The lease's TTL and the snapshot interval |
| `engine.ha.antiAffinity` | `soft` | `soft` prefers two nodes for the pair; `hard` requires them |
| `engine.ha.podDisruptionBudget.enabled` | `true` | A drain evicts one engine of the pair at a time |
| `engine.metrics.job` | `syncd` | The `job` label the ServiceMonitor gives the engine's metrics, and the job the PrometheusRule selects |
| `engine.metrics.serviceMonitor.enabled`, `.interval`, `.labels` | `false`, `30s`, `{}` | A Prometheus Operator ServiceMonitor scraping `/metrics` (see [Monitoring on Kubernetes](operator-guide.md#monitoring-on-kubernetes)) |
| `engine.metrics.prometheusRule.enabled`, `.labels` | `false`, `{}` | The shipped alert rules as a PrometheusRule (see [Alert rules](#alert-rules-as-a-prometheusrule)) |
| `console.replicas` | `1` | The console's pods |
| `console.database.url` | | The console's PostgreSQL URL, kept in the chart's `syncd-console` Secret |
| `console.database.existingSecret`, `.existingSecretKey` | , `url` | Or a Secret of your own holding the URL, which keeps it out of Helm's values |
| `console.adminPassword` | Generated (24 characters) | The initial `admin` password, which must be changed at the first sign-in |
| `console.trustedProxies` | | `CONSOLE_TRUSTED_PROXIES`: the ingress controller's addresses or CIDR ranges, comma-separated, whose `X-Forwarded-For` names the client |
| `console.publicUrl` | `https://<console.ingress.host>` when the Ingress is enabled | `CONSOLE_PUBLIC_URL`: the console's address as users reach it, the base of the single sign-on redirect URI |
| `console.oidc.*` | Off | [Single sign-on](#single-sign-on) |
| `console.tls.existingSecret` | | A `kubernetes.io/tls` Secret: the console serves HTTPS itself (see [The console's own HTTPS on Kubernetes](#the-consoles-own-https-on-kubernetes)) |
| `console.extraVolumes`, `.extraVolumeMounts` | `[]` | More volumes on the console's pod: a Git deploy key, a HEC token, a CA |
| `console.env` | `[]` | Any other console setting, as its environment variable (below). The registration Job gets them too |
| `console.newMasterKey` | `false` | Starts the console and the registration Job with a new master key when the old one is lost (see [The master key Secret](operator-guide.md#the-master-key-secret)). For one upgrade only |
| `console.resources` | Requests 100m CPU and 128Mi; memory limit 512Mi | The console's pods |
| `console.nodeSelector`, `.tolerations`, `.affinity`, `.podAnnotations` | | Scheduling, annotations |
| `console.service.type`, `.port` | `ClusterIP`, `8080` | The console's Service: HTTP, or HTTPS with `console.tls` |
| `console.ingress.enabled`, `.className`, `.annotations`, `.host`, `.tls` | `false`, , `{}`, `syncd.example.com`, `[]` | An Ingress for one host, path `/` |
| `networkPolicy.*` | Off | [NetworkPolicies](#networkpolicies) |
| `pki.days` | `3650` | How long the CA and the certificates the chart issues are valid |

`values.yaml` in the chart lists them all, and the source repository's
`tests/helm/all-options.yaml` turns every production option on at once (CI renders it). There is
no value for the engine's configuration: it comes from the console, as on a host.

**Other console settings**, such as session lengths, the audit outputs, webhooks or Git sync, go in
`console.env` as the variables in [Configuration](configuration.md). Take secrets from a Secret of
your own, mounted as a file with `console.extraVolumes`, never from Helm's values:

```yaml
console:
  env:
    - { name: CONSOLE_SESSION_IDLE_MINUTES, value: "30" }
    - { name: ENS_AUDIT_SPLUNK_URL, value: https://splunk.example.com:8088 }
    - { name: ENS_AUDIT_SPLUNK_TOKEN_FILE, value: /etc/syncd/secrets/splunk-hec-token }
```

Before 0.4.0 single sign-on went in `console.env` too. It now has values of its own
(`console.oidc.*`, `console.publicUrl`); don't set the same variables both ways.

### 4. What the chart keeps

| Secret or volume | Holds | Kept |
|---|---|---|
| `syncd-console` | `master-key` (the console's master key, which seals the engine's client key, webhook addresses and secrets, and secret environment values in the console's database), `admin-password`, and `database-url` when given inline | Generated at install. Kept by every upgrade, and by `helm uninstall` (`helm.sh/resource-policy: keep`). A reinstall reuses it |
| `syncd-pki` | `ca.crt`, `ca.key`, `engine.crt`, `engine.key`, `console.crt`, `console.key` | Issued at install, kept by upgrades while it holds `ca.crt` and `ca.key`. From 0.4.0 also kept by `helm uninstall`, so a reinstall reuses the certificates the console has. New certificates are ECDSA P-256; an install made with chart 0.3.0 keeps its RSA ones until the Secret is deleted |
| `data-syncd-engine-0` (and `data-syncd-engine-1` for a pair) (PersistentVolumeClaim) | The engine's store (`/var/lib/syncd/engine`) and its configuration file (`/var/lib/syncd/engine.yaml`) | Kept by `helm uninstall`, as a StatefulSet's volumes are |

The console's pods keep nothing of their own: their data directory is an `emptyDir` (the Git sync
clone is made again after a restart), and the master key comes from the Secret as
`CONSOLE_MASTER_KEY`.

!!! warning "Back up the `syncd-console` Secret"
    Its master key opens the engine's client key and the other sealed secrets in the console's
    database. If the Secret is lost and the chart generates a new one, the console refuses to
    start over the sealed secrets (see [The master key](operator-guide.md#the-master-key)).

!!! note "Render the chart with Helm, against the cluster"
    The chart reads the existing Secrets with `lookup` to keep the master key and the
    certificates. `helm template`, and tools that render the chart that way, cannot see the cluster
    and generate new ones each time. Install and upgrade with `helm install` and `helm upgrade`.

### 5. Reaching the console

The console's Service is plain HTTP on 8080, unless the console has its own certificate
([below](#the-consoles-own-https-on-kubernetes)). Users reach it through an Ingress with TLS
(`console.ingress.*`, as in step 1).

- **`console.trustedProxies`.** Set it to the ingress controller's pod range, so that the audit
  trail and the sign-in limit see each user's address rather than the controller's.
- **`console.publicUrl`.** `https://<console.ingress.host>` when the Ingress is enabled; set it
  otherwise, for single sign-on.
- **The controller's settings.** The nginx configuration shipped for hosts turns buffering off
  with a one-hour read timeout, for the live status stream (server-sent events), and accepts
  bodies up to 2 MB, for configuration imports. The chart sets no Ingress annotations; with
  ingress-nginx, the equivalents are:

    ```yaml
    console:
      ingress:
        annotations:
          nginx.ingress.kubernetes.io/proxy-buffering: "off"
          nginx.ingress.kubernetes.io/proxy-read-timeout: "3600"
          nginx.ingress.kubernetes.io/proxy-body-size: 2m
    ```

    These are a suggestion that mirrors the shipped nginx configuration; the chart's CI test does
    not use an Ingress.

The engine's Service is `ClusterIP`. Its metrics port (9464) is plain HTTP, and its control API
(7443) needs a client certificate from the chart's CA. To limit who may connect to either, turn on
the chart's [NetworkPolicies](#networkpolicies).

### Production values

New in 0.4.0, all off by default.

#### Single sign-on

The console's OpenID Connect settings are under `console.oidc`. The client secret comes from a
Secret of your own, mounted as a file (`CONSOLE_OIDC_CLIENT_SECRET_FILE`), never from Helm's
values:

```bash
kubectl -n syncd create secret generic syncd-oidc --from-literal=client-secret='...'
```

```yaml
console:
  oidc:
    issuer: https://login.example.com/realms/corp
    clientId: syncd-console
    existingSecret: syncd-oidc               # key client-secret (existingSecretKey)
    roleClaim: groups
    roleMap: syncd-admins=admin,syncd-ops=operator
    defaultRole: viewer
    # ca: { configMap: corp-ca }             # or secret: ...; key ca.crt by default
```

- Register the console with the provider with the redirect URI
  `<console.publicUrl>/api/v1/auth/oidc/callback`.
- For a provider whose certificate is not public, put its CA (PEM) in a ConfigMap or a Secret and
  name it in `console.oidc.ca.configMap` or `console.oidc.ca.secret` (key `console.oidc.ca.key`,
  `ca.crt` by default). The console gets it as `CONSOLE_OIDC_CA_FILE`.
- Other `CONSOLE_OIDC_*` settings (scopes, username claim, the button's label) go in
  `console.env`.
- The chart refuses an `issuer` without `clientId`, without `existingSecret`, or without a public
  URL, and a CA from both a ConfigMap and a Secret.

See [Single sign-on](single-sign-on.md) for the settings themselves.

#### The console's own HTTPS on Kubernetes

By default the console speaks plain HTTP on 8080 and the ingress controller terminates TLS. To
encrypt that hop too, or without an Ingress, give the console a `kubernetes.io/tls` Secret
(`tls.crt`, `tls.key`; a cert-manager `Certificate` makes one):

```yaml
console:
  tls:
    existingSecret: syncd-console-tls
  ingress:
    annotations:
      nginx.ingress.kubernetes.io/backend-protocol: HTTPS
```

- The console then serves HTTPS on 8080 (`CONSOLE_TLS_CERT`, `CONSOLE_TLS_KEY`), and reloads
  the files when cert-manager renews them.
- The probes use `scheme: HTTPS`, and the container's, the Service's and the Ingress backend's
  port is called `https` instead of `http`.
- The ingress controller must be told to speak HTTPS to the backend, which is
  controller-specific: with ingress-nginx the annotation above; with Traefik a `ServersTransport`
  or the `traefik.ingress.kubernetes.io/service.serversscheme: https` annotation; with HAProxy
  `haproxy.org/server-ssl: "true"`.
- `kubectl port-forward` then needs `https://` too.

#### Extra volumes

`engine.extraVolumes` and `engine.extraVolumeMounts`, and the `console.*` pair, add volumes and
mounts to the pods: a CA bundle for a source's or target's TLS, a Kerberos keytab, an ODBC
configuration, a Git deploy key, a Splunk HEC token. The root file systems are read-only, so
anything the processes must find on disk comes in this way:

```yaml
engine:
  extraVolumes:
    - name: corp-ca
      configMap: { name: corp-ca }
  extraVolumeMounts:
    - name: corp-ca
      mountPath: /etc/syncd/ca
      readOnly: true
```

The inbox of a `csv` connector comes in the same way: a PersistentVolumeClaim (or an NFS share)
the exporting system writes to, mounted where the connector's `path` points. The engine only reads
it, and deletes a `done_file` once a file is read, so the mount is read-write only when done files
are used:

```yaml
engine:
  extraVolumes:
    - name: inbox
      persistentVolumeClaim: { claimName: syncd-inbox }
  extraVolumeMounts:
    - name: inbox
      mountPath: /var/lib/syncd/in
      readOnly: false
```

The connector itself is in [Connectors](connectors.md). The console's Git sync takes its deploy
key and `known_hosts` the same way, through `console.extraVolumes`, with the
`CONSOLE_CONFIG_GIT_*` settings in `console.env` (see [Configuration as code](config-as-code.md)).
`file://` repositories do not work in the image, which has no shell.

#### NetworkPolicies

`networkPolicy.enabled=true` adds one NetworkPolicy per component. Your CNI must enforce them;
kind's does not, so the chart's CI test checks the objects, not the isolation.

- **The engine** accepts its control API (7443) from the console's and the registration Job's
  pods only (and, in a pair, from the other engine), and its metrics (9464) from
  `networkPolicy.monitoringNamespaceSelector` and `monitoringPodSelector` (both in one `from`
  entry; from anywhere while both are empty).
- **The console** accepts its port (8080) from `networkPolicy.ingressNamespaceSelector` and
  `ingressControllerSelector`, plus `networkPolicy.console.extraIngressFrom` (more `from`
  entries, an `ipBlock` for instance); from anywhere while all are empty.
- **The registration Job** accepts nothing.

```yaml
networkPolicy:
  enabled: true
  monitoringNamespaceSelector: { matchLabels: { kubernetes.io/metadata.name: monitoring } }
  ingressNamespaceSelector: { matchLabels: { kubernetes.io/metadata.name: ingress-nginx } }
```

Kubelet probes are not subject to NetworkPolicies on the common CNIs (Calico, Cilium), so the
engine's liveness probe on 9464 keeps working with a narrow monitoring selector.

**Egress stays open** by default, on purpose: the engine's sources and targets, and the console's
PostgreSQL and identity provider, are wherever you run them. `networkPolicy.egress.enabled=true`
closes it: every pod may then reach DNS (53), the console and the Job may reach the engine's
control API, the engines of a pair each other's, and nothing else until you add the rest in
`networkPolicy.egress.extra`, a list of NetworkPolicy egress rules applied to all three
components:

```yaml
networkPolicy:
  enabled: true
  egress:
    enabled: true
    extra:
      - to: [{ ipBlock: { cidr: 10.1.0.0/16 } }]        # the database, the directories
        ports: [{ port: 5432, protocol: TCP }, { port: 636, protocol: TCP }]
      - to: [{ ipBlock: { cidr: 0.0.0.0/0, except: [10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16] } }]
        ports: [{ port: 443, protocol: TCP }]           # the identity provider, webhooks, SCIM, Entra, S3
```

With egress closed, also allow:

- the console's database, for the console, the Job, and the engines of a pair (their lease);
- the receivers of the console's webhooks and pre-publish hook, which connect directly, without
  a proxy, and its Git server and mail relay;
- for the engine: a `scim` target and its OAuth token endpoint; for an `entra` connector, the
  cloud's Graph and login hosts on 443 (`graph.microsoft.com` and `login.microsoftonline.com`
  for the global cloud; `graph.microsoft.us`, `dod-graph.microsoft.us` and
  `login.microsoftonline.us` for the US Government clouds; `microsoftgraph.chinacloudapi.cn` and
  `login.chinacloudapi.cn` for China); an S3 or MinIO backup destination.

A source the engine cannot reach shows as `SyncdSourceDown`; a database the console cannot reach
keeps it from starting; a webhook receiver it cannot reach shows as a retried delivery
(`connection failed`) on the Integrations page; a pre-publish hook it cannot reach refuses the
publish. Check `kubectl describe networkpolicy` first when one of these appears after turning
egress off.

#### Alert rules as a PrometheusRule

```yaml
engine:
  metrics:
    serviceMonitor: { enabled: true, labels: { release: kube-prometheus-stack } }
    prometheusRule: { enabled: true, labels: { release: kube-prometheus-stack } }
```

`engine.metrics.prometheusRule.enabled` renders the shipped alert rules (the same file the
packages ship) as a PrometheusRule. The labels are whatever your Prometheus's
`serviceMonitorSelector` and `ruleSelector` look for. The ServiceMonitor sets its targets' `job`
label to `engine.metrics.job` (`syncd`), and the rules are rendered with that job, so
`SyncdEngineDown` fires for the engine the ServiceMonitor scrapes. Change `engine.metrics.job` if
`syncd` is taken; the rules follow. With several releases scraped by one Prometheus, enable the
PrometheusRule on one of them: its rules match every engine. See
[Monitoring on Kubernetes](operator-guide.md#monitoring-on-kubernetes).

#### An active/passive pair { #an-active-passive-pair }

```bash
helm upgrade syncd oci://ghcr.io/winllc/charts/syncd -n syncd --reuse-values \
  --set engine.ha.enabled=true
```

`engine.ha.enabled` runs two engine pods, `syncd-engine-0` and `syncd-engine-1`, each with its own
data volume, as an active/passive pair:

- **The lease** is a row in the console's PostgreSQL database (table `syncd_lease`, created by
  the console's migrations), reached with the console's database URL. The pair is set from the
  environment (`SYNCD_HA_*`), so the configuration the console publishes is the same for both
  pods.
- **Each pod by name.** A headless Service, `syncd-engine-peers`, gives each pod a name
  (`syncd-engine-0.syncd-engine-peers.<namespace>.svc`). The lease records the active one's; the
  passive one pulls snapshots from it with the chart's engine certificate, which names both pods.
- **Registration.** The Job registers both pods in the console, as `<engine name>-0` and `-1`. The
  console shows them as one pair, with the active one's status. An engine registered before under
  `<engine name>` alone stays registered, with its configuration versions, until you remove it
  under **Engines**.
- **Scheduling.** A PodAntiAffinity on the host name keeps the two pods apart:
  `engine.ha.antiAffinity: soft` (the default) prefers two nodes, `hard` requires them (a
  one-node cluster then runs one). `engine.affinity`, when set, replaces it. A
  PodDisruptionBudget lets a drain evict one engine at a time.
- **Timing.** `engine.ha.leaseTtlSeconds` (15) and `engine.ha.snapshotEverySeconds` (300). A pod
  stopped by a rollout or a drain hands the lease over at once.

**On an existing release**, the StatefulSet's `serviceName` and `podManagementPolicy` change with
`engine.ha.enabled`, and Kubernetes does not update them in place. Before the upgrade:

```bash
kubectl -n syncd delete statefulset syncd-engine --cascade=orphan   # the pods and the volume stay
kubectl -n syncd delete secret syncd-pki    # certificates from an older chart don't name the pods
```

Then upgrade. The chart issues new certificates, and the registration Job gives the console the
new ones. The same applies when turning HA off again.

!!! warning "Not tested on Kubernetes"
    The chart's kind test installs a single engine: a pair on Kubernetes isn't installed or
    failed over by any test. CI only lints and renders the HA templates. The pair itself is
    tested end to end on hosts. Try a failover on a test cluster before you rely on it.

#### FIPS images

```yaml
image:
  engine: { tag: 0.4.0-fips }
  console: { tag: 0.4.0-fips }
engine:
  env:
    - { name: SYNCD_FIPS_MODE, value: "true" }
console:
  env:
    - { name: SYNCD_FIPS_MODE, value: "true" }
```

`kubectl -n syncd exec syncd-engine-0 -- syncd status` then shows `fips on`. The FIPS images are
amd64 only. The registration Job gets `console.env` too, so it runs in FIPS mode with the console.
See [FIPS 140-3 and DISA STIG](fips-and-stig.md).

### 6. Upgrade { #helm-upgrade }

```bash
helm upgrade syncd oci://ghcr.io/winllc/charts/syncd --version X.Y.Z \
  --namespace syncd -f syncd-values.yaml --wait
```

Unlike `syncdctl upgrade`, the chart takes no backup and does not roll back by itself. Back up
first (see [Backups on Kubernetes](availability-and-sizing.md#backups-on-kubernetes)):

- the engine: `kubectl -n syncd exec syncd-engine-0 -- syncd backup --to s3://...`, or a volume
  snapshot of its volume;
- the console's database, with `pg_dump`;
- the `syncd-console` Secret (and the `syncd-pki` Secret).

On upgrade, the engine migrates its store if the new release changed its format (see
[Store format](#store-format)), the console migrates its database, and the registration Job runs
again. The engine pod has 10 minutes to open its store and listen (its startup probe) before
Kubernetes restarts it. `helm rollback` and its limits are in [Rollback](#rollback). What changes
from chart 0.3.0 to 0.4.0 is in [Upgrading from 0.3.0 to 0.4.0](#upgrading-from-030-to-040).

A host install does not move to Kubernetes by itself: see
[Moving from a host to Kubernetes](availability-and-sizing.md#moving-from-a-host-to-kubernetes).

### 7. Uninstall { #helm-uninstall }

```bash
helm uninstall syncd -n syncd
```

This removes the pods, Services, Ingress, NetworkPolicies and the Job. It keeps the
`syncd-console` Secret (the master key), the `syncd-pki` Secret (the certificates, from 0.4.0) and
the engine's volume, so a later `helm install` with the same release name carries on where it
was: the console's database still holds the engine registered with the client certificate from
that Secret. To start over, delete them too, and drop the console's database:

```bash
kubectl -n syncd delete secret syncd-console syncd-pki
kubectl -n syncd delete pvc data-syncd-engine-0          # and data-syncd-engine-1 for a pair
```

Deleting the PKI Secret alone makes the next install or upgrade issue new certificates; deleting
the console Secret alone loses the master key (see
[The master key Secret](operator-guide.md#the-master-key-secret)).

### Air-gapped clusters

1. On a connected host, get the chart: `helm pull oci://ghcr.io/winllc/charts/syncd --version
   0.4.0` gives `syncd-0.4.0.tgz`. Verify it and the images with cosign there (see
   [Verifying the images and the chart](#verifying-the-images-and-the-chart)).
2. Copy `ghcr.io/winllc/syncd:0.4.0` and `ghcr.io/winllc/syncd-console:0.4.0` (or the `-fips`
   tags) into your registry, for example with `skopeo copy --all` (every platform) or `docker
   pull`, `docker tag` and `docker push`. The chart uses no other image.
3. Install from the file, with your registry:

```bash
helm install syncd ./syncd-0.4.0.tgz --namespace syncd -f syncd-values.yaml \
  --set image.engine.repository=registry.example.gov/winllc/syncd \
  --set image.console.repository=registry.example.gov/winllc/syncd-console
```

Add `imagePullSecrets` if your registry needs them.

### What the chart does not do yet

- **A bundled PostgreSQL.**
- **A FIPS image for arm64.** The FIPS images are amd64 only.
- **A tested HA pair.** The pair's templates are linted and rendered, not installed or failed
  over (above). Its lease is in PostgreSQL only: there is no Kubernetes Lease or etcd backend.

## The JDBC bridge

syncd reads PostgreSQL, MySQL, SQL Server and MongoDB with drivers of its own. For any other
database with a JDBC driver (Db2, Informix, Oracle, ...), a `jdbc` connector reads through the
JDBC bridge, a separate Java program that runs the SQL syncd builds and streams the rows back over
gRPC. It is not part of the release bundle or the packages: it is built from `bridge/jdbc` in the
source repository and run separately.

1. **Build** it with Java 17+ and Gradle 8: `gradle installDist` (or `gradle distTar` for a
   `.tar`).
2. **Add the drivers**: put the databases' JDBC jars (`db2jcc4.jar`, `ojdbc11.jar`, ...) in its
   `drivers/` directory, or point `SYNCD_JDBC_DRIVERS` at the directory holding them.
3. **Run** it as a service next to syncd, with a restart policy. syncd retries its reads while the
   bridge is away.

    ```bash
    bin/syncd-jdbc-bridge --listen 127.0.0.1:50071 --token-file /etc/syncd/bridge-token
    ```

| Option | |
|---|---|
| `--listen HOST:PORT` | Where to listen. `127.0.0.1:50071` by default |
| `--token-file FILE` | A shared secret every call must carry (`SYNCD_BRIDGE_TOKEN` also works). Required to listen beyond loopback |
| `--tls-cert FILE`, `--tls-key FILE` | Serve TLS. The key is PKCS#8 PEM |

`JAVA` picks the Java runtime and `JAVA_OPTS` adds JVM options. The bridge logs to standard error
and holds no configuration of its own: each request names its database. Every request carries the
database's password, so a bridge on another machine must serve TLS and require a token; syncd
refuses a `jdbc` connector whose bridge is beyond loopback otherwise. The connector's settings are
in [Connectors](connectors.md).

## The Debezium sidecar

To stream changes from Oracle or Db2 as they happen, a `debezium` connector receives change events
from [Debezium Server](https://debezium.io/documentation/reference/stable/operations/debezium-server.html),
run beside syncd. Debezium Server is not shipped with syncd; you install and run it. The source
repository has a starting `application.properties` in `deploy/debezium/`:

- the sink is HTTP, to the address the `debezium` connector listens on (for example
  `http://127.0.0.1:8085/<token>`; the token goes in the path, as the HTTP sink sends no other
  credentials);
- it retries for ever (`debezium.sink.http.retries=2147483647`, every second), because syncd
  answers an event only once it has committed it;
- events are plain JSON, decimals as text, with no tombstones;
- Debezium keeps its own position in `offsets.dat` and `schema-history.dat`.

Run it with a restart policy (systemd `Restart=always`, or a container with
`--restart unless-stopped`). See [Connectors](connectors.md) for the connector.

## Upgrade

On Kubernetes, upgrade with `helm upgrade` (see [Upgrade with Helm](#helm-upgrade)). On a host,
upgrade as in [Bundle installs](#bundle-installs) or [Package installs](#package-installs). Read
what changes first.

### Upgrading from 0.3.0 to 0.4.0

Upgrade as usual, with `syncdctl upgrade`, `apt` or `dnf`, or `helm upgrade` with chart 0.4.0. The
upgrade takes its local backup first, as before. What to know:

- **The store's format does not change** (format 1). 0.4.0 adds column families to the store on
  its first start (persisted timers, held changes, data-quality results and others). That is not
  a format change: 0.3.0 opens whatever column families exist. See [Rollback](#rollback) for what
  0.3.0 then ignores.
- **The console's database gains tables** (schema 8 to 17, migrations 0009 to 0017): the audit
  trail's new format, API tokens, webhooks, the pre-publish hook, environments, Git sync,
  scheduled reports and their mail outbox, the HA lease, and the data-quality daily rollup. A
  rollback to 0.3.0 therefore restores the console's database.
- **The audit trail continues in the ENS format** (`ens-audit/1`). Entries written before the
  upgrade keep their chain. At the first start the console records `audit.format-upgraded`, and
  the first new event continues the old chain's head, so **Verify chain** checks both. See
  [Security](security.md).
- **Audit events on standard output.** The console writes every audit event as one JSON line on
  standard output, so to the journal (`journalctl -u syncd-console`) or the pod log.
  `ENS_AUDIT_STDOUT=false` in `console.env` turns that off.
- **`syncd-console admin register-engine` refuses to register a name again** when the stored
  client key no longer opens with the master key, and says why, instead of answering `registered
  already`. `--update` seals a new key over it, audited as `engine.re-registered` (see
  [Registering engines](#registering-engines)). `POST /api/v1/engines` behaves the same, with
  `replace: true`.
- **Active Directory passwords over plain LDAP are refused at validation.** A publication with a
  `passwords` block whose Active Directory target is reached over `ldap://` without `starttls`
  now fails `syncd validate` and the upgrade's configuration check. Before, it failed when a
  password was written. Switch the target to `ldaps://` or `starttls: true` first (see
  [Password synchronisation](password-sync.md)).
- **Entity history keeps more.** Past `keep_days`, the newest `min_per_entity` entries of each
  entity (default 10) stay, and so does every entry of an entity with an open held change or a
  dead letter. Allow for it on disk: see [Entity history](availability-and-sizing.md#entity-history).
- **The shipped alert rules.** `SyncdMemoryNearLimit` no longer selects `job="syncd"`; only
  `SyncdEngineDown` needs the scrape job's name. Eight rules are new (see
  [Alert rules](operator-guide.md#alert-rules)). Load the new file into Prometheus.
- **Helm: the ServiceMonitor's job label.** The ServiceMonitor now sets its targets' `job` label
  to `engine.metrics.job` (`syncd`). Before, the operator named the job after the Service
  (`<release>-syncd-engine`). Change queries and dashboards that use the old label.
- **Helm: the PKI Secret is kept on `helm uninstall`**, as the console's Secret already was. To
  start over, delete both Secrets and the engine's volume (see [Uninstall](#helm-uninstall)).
  New certificates are ECDSA P-256; an existing install keeps its RSA ones until its PKI Secret
  is deleted.
- **Helm: the registration Job gets `console.env`** too. A `SYNCD_FIPS_MODE` or a database
  option set there now applies to it.
- **Helm: turning on `engine.ha.enabled`** in an existing release needs the StatefulSet deleted
  without its pods, and the PKI Secret deleted, first (see
  [An active/passive pair](#an-active-passive-pair)). The same applies when turning HA off.
- **Files kept.** `/etc/syncd/console.env` stays as you left it. The release's template,
  `/opt/syncd/current/share/config/console.env`, has commented lines for the new settings:
  `ENS_AUDIT_*`, `CONSOLE_API_*`, `CONSOLE_WEBHOOKS_*`, `CONSOLE_REPORTS_*`, `CONSOLE_SMTP_*`,
  `CONSOLE_PUBLISH_*` and `CONSOLE_CONFIG_GIT_*`. None is needed.
- **New files.** The engine's data directory may hold `backups/` (a store checkpoint while a
  backup is taken), `restore/` and `replaced-<time>/` (left by `syncd restore`), and
  `ha/swap.json` and `ha/old-store` (only while an HA engine swaps a snapshot in). `syncd restore`
  puts the backed-up configuration beside the live one as `engine.restored.yaml`. With Git sync
  on, the console keeps its clone under its data directory (`git/`).
- **Packages** now recommend `git`, which Git sync needs. The console's image includes `git` and
  `ssh`.
- **No new ports by default.** The engines of an HA pair reach each other's control API (7443).
- **New in 0.4.0, nothing to do:** change windows, thresholds, canaries and report mode;
  persisted timers and effective dates; API tokens, webhooks, reports and the pre-publish hook;
  configuration as code; off-host and scheduled backups; the support bundle; new connectors. All
  are off until configured.

### Upgrading from 0.2.1 to 0.3.0

Upgrade as below, with `syncdctl upgrade`, `apt` or `dnf`. What to know first:

- **The store's format does not change** (format 1). `syncd store-format` prints it, and from
  0.3.0 `syncdctl` refuses a rollback or restore that the release it would run could not read.
- **The console's database gains a table** (schema 7 to 8, for banners). A rollback to 0.2.1
  therefore restores the console database backed up before the upgrade, and changes made in the
  console since (configuration versions, accounts, audit entries) are lost.
- **Back up the master key, and check it is in place.** The 0.3.0 console refuses to start when
  `/var/lib/syncd/console/master.key` is missing, or does not open the secrets in its database,
  instead of creating a new key (see [The master key](operator-guide.md#the-master-key)).
- **`syncd-console admin reset-password` refuses single sign-on accounts** unless given
  `--allow-single-sign-on` (see [First sign-in](#first-sign-in)).
- **Database connects time out.** Connecting to PostgreSQL, MySQL or SQL Server now fails after
  10 seconds when the server accepts the connection and never answers, and the source is
  retried. Before, it waited forever.
- **Entries moved or renamed in a target by someone else are put back** where the publication
  places them, found by the target's own id (`objectGUID`, `nsUniqueId` or `entryUUID`). Before,
  a second copy was added, or the export was dead-lettered. If people move managed entries by
  hand on purpose, change the publication's DN template instead, or the engine moves them back.
- **LDAP sources read with `stream` or `delta` check their containers** for renames every 60
  seconds (`container_check_secs`; 0 turns it off). See [Connectors](connectors.md).
- **`/run/syncd`.** The engine's unit now creates it (`RuntimeDirectory`), for the optional
  control socket. `syncdctl` and the packages reload the units.
- **Files kept.** `console.env` and `engine.yaml` are kept as they are, so the new commented
  settings of a fresh install (`CONSOLE_TLS_CERT`, `CONSOLE_TLS_KEY`, `api.control_socket`) are
  not added to them. Add the ones you want by hand.
- **New in 0.3.0, nothing to do:** `syncdctl renew-certs` and certificate expiry in
  `syncdctl status`; the console's own HTTPS; quarantined entities kept across restarts
  (`syncd quarantine`); live engine CPU and memory in the console; the audit log's head recorded
  hourly; password synchronisation (off until configured). `syncdctl` also clears systemd's
  start limit before its own restarts, so an upgrade followed at once by `renew-certs` no longer
  fails with `Start request repeated too quickly`.

### Bundle installs

Either command upgrades:

```bash
sudo syncdctl upgrade syncd-0.4.0-linux-x86_64.tar.gz
sudo ./syncd-0.4.0-linux-x86_64/install.sh        # on a host that runs syncd
```

The new release's own `syncdctl` runs the upgrade:

1. It checks the archive against its `.sha256` (when it is next to it), the release against its
   manifest, and that it is newer than the installed one.
2. It checks that the new programs run on this host and that the new engine accepts the current
   configuration (`syncd validate`).
3. It checks there is enough disk space for a backup.
4. It stops both services and backs up the engine store, the console's files, the console's
   database (with `pg_dump`) and `/etc/syncd`.
5. It switches `/opt/syncd/current` to the new release and starts the services. The console
   updates its database schema when it starts, and the engine migrates its store if the format
   changed (see [Store format](#store-format)).
6. It waits up to 120 seconds for both services to be healthy (`SYNCD_HEALTH_TIMEOUT` changes
   this). If they are not, it rolls back to the previous release and restores the backup by
   itself.

The services are down from step 4 until the health checks pass, typically less than a minute.
Afterwards, the engine catches up with its sources from its checkpoints.

| Option | Effect |
|---|---|
| `--no-rollback` | Leaves a failed upgrade in place for investigation |
| `--skip-db-backup` | Skips the database backup when `pg_dump` is not installed |
| `--reinstall` | Installs the same version again |
| `--yes` | Asks no questions |

Only one `syncdctl` runs at a time. The last three backups are kept (`SYNCD_KEEP_BACKUPS` changes
this), and so are the current and the previous release; older releases are removed.

**A pair on hosts.** Upgrade the passive engine's host first, then the active one's: stopping the
active engine hands the lease to the upgraded one at once. Both engines should run the same
release.

### Package installs

Upgrade with the package manager:

```bash
sudo apt install ./syncd_0.4.0_amd64.deb
sudo dnf upgrade ./syncd-0.4.0-1.x86_64.rpm          # RHEL 8: ./syncd-0.4.0-1.el8.x86_64.rpm
```

Before the new files are unpacked, the installed release stops syncd and takes the same backup.
Afterwards the new release starts the services and checks they are healthy. Unlike a bundle
upgrade, a package upgrade does not check the configuration or the disk space first, and does not
roll back by itself: a failed health check fails the package's configuration step and names the
backup. To go back, install the previous package version, then run
`sudo syncdctl restore <backup>`.

## Rollback

```bash
sudo syncdctl rollback
```

This goes back to the previous release (bundle installs only):

- The engine store and `/etc/syncd` are restored from the backup taken before the upgrade. The
  engine picks up changes made since then when it next reads its sources.
- The console's database is restored only if the newer release changed its schema. Otherwise its
  contents are kept: configuration versions, accounts and the audit trail written since the
  upgrade, and so are the control API's certificates in `/etc/syncd/pki`, which the console's
  stored credentials for the engine match. From 0.4.0, a restore first drops the tables the newer
  release added, so the database comes back as it was when it was dumped.
- `--keep-data` switches the programs back and restores nothing. It is refused when the previous
  release cannot read the engine store, because the newer release migrated it to a newer format.
- `--yes` asks no questions.

After a rollback there is no previous release to go back to again. On a package install, rollback
is refused: install the older package, then `syncdctl restore` the backup named
`<time>-upgrade-<old>-to-<new>`. More on recovery is in
[Sizing, availability and recovery](availability-and-sizing.md#rolling-back-an-upgrade).

### Rolling back 0.4.0 to 0.3.0

- **The console's database is restored**, since 0.4.0 changed its schema. Everything recorded in
  the console since the upgrade is lost: configuration versions, accounts, API tokens, webhooks,
  environments, scheduled reports and audit entries. Export what you need first.
- **A full rollback restores the engine store** taken before the upgrade, so what 0.4.0 kept in
  it is gone, and the engine catches up from its checkpoints.
- **With the store kept** (`syncdctl rollback --keep-data`, or `helm rollback`), 0.3.0 opens it,
  but does not act on what 0.4.0 keeps there: staged leaver steps, effective dates, change
  windows, canaries and group expiry wait on timers it ignores, and it knows nothing of held
  changes. A change held by 0.4.0 is applied, or not, by 0.3.0's own rules.
- **0.3.0 refuses settings it doesn't know.** Before rolling back with the store kept, publish a
  configuration without the settings new in 0.4.0 (change windows, thresholds, canaries,
  `mode`, `deprovision`, `passwords.initial`, `group_policy`, `quality`, `packages`,
  `correlation`, `engine.backup`, `engine.ha`, `min_per_entity`, the new connectors, ...).
  Otherwise 0.3.0 refuses to start with it.
- **Take the pair apart first.** 0.3.0 has no HA: stop the passive engine (or turn
  `engine.ha.enabled` off on Kubernetes, as in
  [An active/passive pair](#an-active-passive-pair)) and remove `engine.ha` before rolling back.
- **Audit outputs.** Events sent to Splunk, syslog or the audit file since the upgrade stay
  there, but are gone from the restored database.

### On Kubernetes

```bash
helm rollback syncd -n syncd          # the previous revision
```

`helm rollback` goes back to the previous images and values. It restores no data: the engine's
volume, the console's database and the Secrets stay as the newer release left them, and the
registration Job does not run (it runs after installs and upgrades only). That is enough when the
newer release changed neither the store's format nor the console's schema. Otherwise, restore the
backups taken before the upgrade first: the older engine refuses a store format it cannot read.
See [Backups on Kubernetes](availability-and-sizing.md#backups-on-kubernetes).

From chart 0.4.0 to 0.3.0, the console's schema changed: restore the console's database dump
into an empty database (drop and create it first). `pg_restore --clean` alone leaves the tables
0.4.0 added. Publish a configuration without 0.4.0's settings first, as above, since the
configuration file on the engine's volume is kept.

## Store format

The engine store has a format: a number for the layout of its data. `syncdctl status` shows it,
and `syncd store-format` prints it (`--supported`: the newest format this `syncd` reads).

- A release that changes the format says so in its release notes. When its engine first starts on
  an older store, it migrates the store before anything else, and logs each step
  (`migrating the store`, then `store migrated`).
- A migration that stops part way (a crash, a power cut) starts again at the next start; the store
  keeps its old format until the migration completes.
- A store migrated by a newer release cannot be read by an older one. The older engine refuses to
  start: `the store has format N, written by a newer syncd`. `syncdctl rollback` restores the
  backup taken before the upgrade; `syncdctl restore` and `syncd restore` refuse a backup whose
  store the installed release cannot read.
- A large store can take longer to migrate than the upgrade waits for the engine to be healthy.
  For such an upgrade, set `SYNCD_HEALTH_TIMEOUT` as the release notes suggest.

Stores written by 0.2.1 and older record no format: they are format 1, which is still the current
format in 0.4.0. 0.3.0 started recording it. New column families, such as those 0.4.0 adds, are
not a format change.

## Uninstall

```bash
sudo syncdctl uninstall               # keeps /etc/syncd and /var/lib/syncd
sudo syncdctl uninstall --purge       # removes them, the backups and the service accounts too
```

Both stop and disable the services, remove the units, the command links and `/opt/syncd`. `--yes`
asks no questions. Neither drops the console's database (nor the `syncd_lease` table of a pair):
drop it yourself (`DROP DATABASE syncd_console`) if you no longer need it. Off-host backups stay
where they were written.

On Kubernetes, see [Uninstall with Helm](#helm-uninstall).

Package installs are removed with the package manager (`apt remove syncd`, `dnf remove syncd`).
That stops and disables the services; `/etc/syncd` and `/var/lib/syncd` stay.

## First start checklist

- [ ] You verified the download: `gpg --verify SHA256SUMS.asc SHA256SUMS` against the release
      key's fingerprint, and `sha256sum -c SHA256SUMS --ignore-missing`.
- [ ] `sudo syncdctl status` shows the engine `active, control API answers` and the console
      `active, http://127.0.0.1:8080 answers`.
- [ ] The console is served over HTTPS (its own certificate, or nginx with
      `CONSOLE_TRUSTED_PROXIES`), and `console.env` sets `CONSOLE_PUBLIC_URL`.
- [ ] `sudo syncdctl status` shows when the control API's certificates expire.
- [ ] You signed in as `admin` and changed the one-time password.
- [ ] `/var/lib/syncd/console/master.key` is backed up somewhere safe (see
      [Operator guide](operator-guide.md#the-master-key)).
- [ ] The secrets your configuration refers to are in `/etc/syncd/engine.env`, and
      `sudo syncd test-connection <connector>` connects for each connector.
- [ ] Monitoring is set up: `api.metrics_listen` and the shipped alert rules (see
      [Operator guide](operator-guide.md#monitoring)).
- [ ] The audit trail reaches your SIEM, through the journal or the `ENS_AUDIT_*` outputs.
- [ ] Backups leave the host: `engine.backup` on a schedule, or `sudo syncdctl backup --to
      <dir or s3://...>`, and you have taken a first one.

On Kubernetes: the images and the chart are verified with cosign; the engine pod `syncd-engine-0`
is ready and `kubectl -n syncd exec syncd-engine-0 -- syncd status` answers; the registration Job
finished (`syncd-register` is deleted once it succeeds; a failed one stays, for `kubectl logs`);
you signed in through the Ingress and changed the password; the `syncd-console` Secret is backed
up; the engine Secret holds the configuration's secrets; the ServiceMonitor (or a scrape job of
your own) reads port 9464 and the PrometheusRule is loaded; `engine.backup` writes to S3.
