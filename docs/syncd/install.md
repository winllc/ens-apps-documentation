# Installation

## At a glance

| Method | Use it for | You run |
|---|---|---|
| [Offline bundle](#install-from-the-bundle) | Any Linux distribution with systemd (glibc 2.34 or newer), including hosts with no internet access | `install.sh`, then `syncdctl` |
| [Package](#install-from-a-package) | Debian and Ubuntu (`.deb`); RHEL, Rocky, Alma 9 and newer and Fedora (`.rpm`); RHEL, Rocky, Alma 8 (`.el8.rpm`) | `apt` or `dnf`, then `syncdctl install` |
| [Helm chart](#install-with-helm) | Kubernetes, from the container images (amd64) | `helm install` |

syncd has two parts:

- **The engine** (`syncd`). It reads the sources, merges identities into views and exports them
  to the target directories. It keeps its state in a store of its own on local disk.
- **The web console** (`syncd-console`), with its PostgreSQL database.

On a host, they run as two systemd services. Every form of a release installs the same files,
and `syncdctl` does the rest: service accounts, configuration, the control API's certificates,
the services, and registering the engine in the console.

On Kubernetes, the engine is a StatefulSet with one replica and the console a Deployment. The
chart issues the certificates and registers the engine.

!!! note "No license to add"
    There is no ENS license check yet (licensing is on the roadmap), so there is no license to
    add. See [Licensing](../licensing.md).

For the settings referred to here, see [Configuration](configuration.md). Day-to-day running is in
the [Operator guide](operator-guide.md).

## Prerequisites

The requirements for the [Helm chart](#install-with-helm) are listed with it.

| Requirement | Notes |
|---|---|
| **Linux with systemd** | x86_64 or aarch64. The published releases are x86_64 builds. |
| **glibc 2.34 or newer** | Releases are built on Rocky Linux 9, so they run on RHEL 9, Ubuntu 22.04, Debian 12 and anything newer. A release built elsewhere needs at least the build host's glibc. |
| **RHEL, Rocky or Alma 8** | Use the `.el8.rpm`. It is built on Rocky Linux 8, and its build checks that the binaries need nothing newer than RHEL 8 has. RHEL 8's default PostgreSQL is 10, too old for the console: `sudo dnf module enable postgresql:16` first. |
| **PostgreSQL 13 or newer** | For the console. On the same host, or elsewhere. |
| **`openssl`** | Issues the control API's certificates at install time. The packages depend on it. |
| **PostgreSQL client tools** | `pg_dump`, `pg_restore` and `psql`, for backups. Upgrades refuse to run without them unless you pass `--skip-db-backup`. The `.deb` recommends `postgresql-client`; the `.rpm` recommends `postgresql`. |
| **`curl`** *(recommended)* | For the health checks. Without it, `syncdctl` falls back to a bash TCP check. |
| **Disk** | Room for the engine store, its history, and backups: see [Sizing](availability-and-sizing.md#sizing). An upgrade checks that `/var/lib/syncd/backups` has room for a backup before it stops anything. |
| **Java 17 or newer** *(only for the JDBC bridge)* | To read databases syncd has no driver of its own for (Db2, Informix, Oracle, ...). See [The JDBC bridge](#the-jdbc-bridge). |
| **The source and target systems** | Network access from the engine's host to the databases and directories it reads and writes, and the accounts it uses. See [Connectors](connectors.md). |

## Getting the release

Releases are published as GitHub releases of the syncd source repository. The release build
(the `Package` workflow) runs on every `v*` tag: it builds the bundle and the packages on Rocky
Linux 9 and Rocky Linux 8, runs the install, upgrade, rollback, backup and uninstall lifecycle on
each, checks the tag against the version in the source, and only then publishes the files.

!!! warning "Nothing is published yet"
    The source repository has no release tags yet, so no GitHub release, container image or
    chart has been published, for 0.3.0 or any earlier version. Until one is, build from source:
    `scripts/package.sh` (and `scripts/package-el8.sh`) for the bundle and packages, `docker
    build` for the images (below), and `helm install` from `deploy/helm/syncd` for the chart.
    The names and addresses on this page are where each release will be published once it is
    tagged.

| File | For |
|---|---|
| `syncd-<version>-linux-x86_64.tar.gz` | The offline bundle: any distribution with systemd and glibc 2.34+, including hosts without internet access |
| `syncd-<version>-linux-x86_64.tar.gz.sha256` | The bundle's checksum |
| `syncd_<version>_amd64.deb` | Debian and Ubuntu, through apt |
| `syncd-<version>-1.x86_64.rpm` | RHEL, Rocky and Alma 9 and newer, and Fedora, through dnf |
| `syncd-<version>-1.el8.x86_64.rpm` | RHEL, Rocky and Alma 8, through dnf |
| `SHA256SUMS` | The checksums of the four files above |

The RHEL 8 package is new in 0.3.0. A GitHub release's notes will be the release notes from the
source repository (`docs/releases/<version>.md`).

Verify what you downloaded before you install it:

```bash
sha256sum -c SHA256SUMS --ignore-missing
sha256sum -c syncd-0.3.0-linux-x86_64.tar.gz.sha256      # the bundle alone
```

Inside the bundle, every file is listed in `MANIFEST.sha256`, and the installer checks the release
against it before it copies anything.

### Container images and the Helm chart

From 0.3.0, a second workflow (`Container`) builds two images and the Helm chart on every push,
installs them in a kind cluster, and, on a `v*` tag only, pushes them to the GitHub container
registry. None has been pushed yet (see above):

| What | Where |
|---|---|
| The engine's image | `ghcr.io/winllc/syncd:<version>` |
| The console's image | `ghcr.io/winllc/syncd-console:<version>` (with the web UI) |
| The Helm chart | `oci://ghcr.io/winllc/charts/syncd`, version `<version>` |

- **Tags.** Each release is tagged with its version only, for example `0.3.0`. There is no
  `latest`. The chart's version and `appVersion` are the release's.
- **Platforms.** amd64 only. There is no arm64 image and no FIPS image.
- **Base.** Distroless (`gcr.io/distroless/cc-debian12:nonroot`): no shell and no package
  manager. Both images run as uid and gid 65532, and work on a read-only root file system.
- **Not signed.** The images and the chart carry no cosign signatures or SBOMs, and the chart is
  not attached to the GitHub release. Pin the versions you have checked, or mirror them (see
  [Air-gapped clusters](#air-gapped-clusters)).

**Images you build yourself**, from the source repository:

```bash
docker build -f deploy/container/Dockerfile --target engine -t registry.example.com/syncd:0.3.0 .
docker build -f deploy/container/Dockerfile --target console -t registry.example.com/syncd-console:0.3.0 .
```

Push them to a registry your cluster can pull from, then set `image.engine.repository`,
`image.console.repository` and their `tag` in the chart's values, and install the chart from the
checkout: `helm install syncd ./deploy/helm/syncd ...` in place of the `oci://` address below.

!!! note "Not yet in the ENS licensing portal"
    Unlike the other ENS applications, syncd releases are not yet delivered through the
    **Downloads** page of the ENS licensing portal, and `SHA256SUMS` is not signed with the ENS
    release key. [Releases and downloads](../releases.md) describes the portal and the signatures
    for the other products.

**Builds you make yourself.** `scripts/package.sh` in the source repository builds the bundle, the
`.deb` and the `.rpm` into `dist/`, and `scripts/package-el8.sh` the RHEL 8 `.rpm`. A FIPS build
(`deploy/packaging/build.sh --fips --packages`) has `-fips` in its name; the published releases
are not FIPS builds. See [FIPS 140-3 and DISA STIG](fips-and-stig.md).

## Install from the bundle

```bash
sha256sum -c syncd-0.3.0-linux-x86_64.tar.gz.sha256
tar -xzf syncd-0.3.0-linux-x86_64.tar.gz
sudo ./syncd-0.3.0-linux-x86_64/install.sh --local-database
```

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
| `--fips` | Runs both services with the FIPS 140-3 module (`SYNCD_FIPS_MODE=true` in both environment files). Refused unless the release is a FIPS build |
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
sudo apt install ./syncd_0.3.0_amd64.deb            # Debian, Ubuntu
sudo dnf install ./syncd-0.3.0-1.x86_64.rpm         # RHEL, Rocky, Alma 9+, Fedora
sudo dnf install ./syncd-0.3.0-1.el8.x86_64.rpm     # RHEL, Rocky, Alma 8
sudo syncdctl install --local-database              # or --database-url ...
```

The package unpacks the release to `/opt/syncd/releases/<version>`, installs the units in
`/usr/lib/systemd/system`, links the commands into `/usr/bin`, creates the service accounts, and
says how to finish. `syncdctl install`, with the [same options](#syncdctl-install-options), then
does the rest as with the bundle.

A host installed from a package is upgraded and removed with the package manager. `syncdctl
upgrade`, `rollback` and `uninstall` refuse to run there and say so.

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

From 0.3.0 the console serves HTTPS itself when it is given a certificate. In
`/etc/syncd/console.env`:

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
  audit log records their addresses.
- **New files on upgrade.** A host installed before 0.3.0 keeps its `console.env`, so the
  commented `CONSOLE_TLS_CERT` and `CONSOLE_TLS_KEY` lines of a new install's file are not in it.
  Add the settings yourself.

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
`X-Forwarded-For` header names the client. With it, the audit log records users' own addresses,
and the sign-in limit (20 attempts per address in 5 minutes) applies to each user. Without it,
every request seems to come from nginx: the audit log shows `127.0.0.1`, and all users share one
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
`user.password_reset` in the audit log. From 0.3.0 it refuses an account that signs in through
single sign-on, as the **Users** page does: a password would let it sign in without the identity
provider and its MFA. To give one anyway, while the provider is unavailable say, add
`--allow-single-sign-on`; the audit record then notes `single_sign_on: true`. On Kubernetes, run
the same command in the console's pod (see [Install with Helm](#install-with-helm)). Next steps: add accounts and roles, and set up
[single sign-on](single-sign-on.md) if you use it ([Admin guide](admin-guide.md)).

## Registering engines

The installer registers the engine on the same host as `local`. A console can manage more engines:
an administrator registers each one under **Engines** → **Register engine**, with:

| Field | |
|---|---|
| Name | 1 to 64 letters, digits, `.`, `_` or `-` |
| Control API address | `host:port`, as in the engine's `api.listen`; or, from 0.3.0, `unix:` and the absolute path of its control socket (`unix:/run/syncd/control.sock`, as in `api.control_socket`) |
| Engine CA certificate, console client certificate and key (PEM) | For mutual TLS. Required unless the address is on loopback or a `unix:` socket, and refused with a `unix:` address. The key is stored encrypted with the console's master key and never shown again |

The console checks that the engine answers before it saves it, and records `engine.register` in
the audit log. On the host, `syncd-console admin register-engine --name N --address HOST:PORT
--server-ca FILE --client-cert FILE --client-key FILE` does the same; it does nothing when an
engine with that name is registered already. With `--update` it replaces that engine's address
and credentials instead, keeping its configuration versions, and records `engine.update`:
`syncdctl renew-certs` and the Helm chart's registration Job use it.

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
    `syncdctl renew-certs` renews them for `localhost` only.

## Install with Helm

New in 0.3.0. The chart (`deploy/helm/syncd` in the source repository, to be published as
`oci://ghcr.io/winllc/charts/syncd` once a release is tagged) runs the two [container images](#container-images-and-the-helm-chart):

- **The engine** is a StatefulSet with **one replica**. Its store and its configuration file are
  on a volume. A second engine would need a store of its own, so the chart has no replica count
  for it.
- **The console** is a Deployment. It keeps its data in a PostgreSQL that you provide.
- **The chart issues the control API's certificates** (mutual TLS between the console and the
  engine) from a CA of its own, and generates the console's master key and the initial `admin`
  password. All of them are kept across upgrades.
- **A Job registers the engine in the console** after each install and upgrade.

| Requirement | Notes |
|---|---|
| **Kubernetes on amd64 nodes** | There is no arm64 image. The CI test runs on kind |
| **Helm 3.8 or newer** | For OCI charts. CI uses Helm 3.16 |
| **PostgreSQL 13 or newer** | For the console, outside the chart: the chart has no bundled database |
| **A StorageClass** | The cluster's default, or one you name, for the engine's volume (`ReadWriteOnce`, 10 GiB by default) |
| **An Ingress controller** *(recommended)* | To reach the console from outside the cluster, with TLS |
| **Prometheus Operator** *(optional)* | For the ServiceMonitor |
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
helm install syncd oci://ghcr.io/winllc/charts/syncd --version 0.3.0 \
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
examples on these pages use those names.

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
| `image.engine.repository`, `.tag`, `.pullPolicy` | `ghcr.io/winllc/syncd`, the chart's `appVersion`, `IfNotPresent` | The engine's image |
| `image.console.repository`, `.tag`, `.pullPolicy` | `ghcr.io/winllc/syncd-console`, the chart's `appVersion`, `IfNotPresent` | The console's image, also used by the registration Job |
| `imagePullSecrets` | `[]` | For a private registry or mirror |
| `podSecurityContext`, `securityContext` | uid and gid 65532, `fsGroup` 65532, `runAsNonRoot`, seccomp `RuntimeDefault`; no privilege escalation, read-only root file system, every capability dropped | Both components and the Job |
| `engine.name` | The release's name | The name the console knows the engine by |
| `engine.envFromSecret` | | A Secret whose keys become the engine's environment variables: the secrets its configuration refers to |
| `engine.env` | `[]` | More environment variables, for example `RUST_LOG` |
| `engine.persistence.size`, `.storageClass`, `.accessModes` | `10Gi`, the default class, `[ReadWriteOnce]` | The engine's volume (see [Sizing](availability-and-sizing.md#sizing)) |
| `engine.resources` | Requests 250m CPU and 512Mi; memory limit 4Gi | The engine's pod. The memory limit is the engine's `syncd_memory_limit_bytes` |
| `engine.terminationGracePeriodSeconds` | `90` | Time the engine has to finish its batch and stop, as `TimeoutStopSec` on a host |
| `engine.nodeSelector`, `.tolerations`, `.affinity`, `.podAnnotations` | | Scheduling, annotations |
| `engine.service.type` | `ClusterIP` | The Service with the control API (`api`, 7443) and metrics (`metrics`, 9464) |
| `engine.metrics.serviceMonitor.enabled`, `.interval`, `.labels` | `false`, `30s`, `{}` | A Prometheus Operator ServiceMonitor scraping `/metrics` (see [Monitoring on Kubernetes](operator-guide.md#monitoring-on-kubernetes)) |
| `console.replicas` | `1` | The console's pods |
| `console.database.url` | | The console's PostgreSQL URL, kept in the chart's `syncd-console` Secret |
| `console.database.existingSecret`, `.existingSecretKey` | , `url` | Or a Secret of your own holding the URL, which keeps it out of Helm's values |
| `console.adminPassword` | Generated (24 characters) | The initial `admin` password, which must be changed at the first sign-in |
| `console.trustedProxies` | | `CONSOLE_TRUSTED_PROXIES`: the ingress controller's addresses or CIDR ranges, comma-separated, whose `X-Forwarded-For` names the client |
| `console.env` | `[]` | Any other console setting, as its environment variable (below) |
| `console.resources` | Requests 100m CPU and 128Mi; memory limit 512Mi | The console's pods |
| `console.nodeSelector`, `.tolerations`, `.affinity`, `.podAnnotations` | | Scheduling, annotations |
| `console.service.type`, `.port` | `ClusterIP`, `8080` | The console's Service, plain HTTP |
| `console.ingress.enabled`, `.className`, `.annotations`, `.host`, `.tls` | `false`, , `{}`, `syncd.example.com`, `[]` | An Ingress for one host, path `/` |
| `pki.days` | `3650` | How long the CA and the certificates the chart issues are valid |

`values.yaml` in the chart lists them all. There is no value for the engine's configuration: it
comes from the console, as on a host.

**Other console settings**, such as single sign-on or session lengths, go in `console.env` as
the variables in [Configuration](configuration.md). Take secrets from a Secret of your own:

```yaml
console:
  env:
    - name: CONSOLE_PUBLIC_URL
      value: https://syncd.example.com      # also the base of the single sign-on redirect URI
    - name: CONSOLE_OIDC_ISSUER
      value: https://idp.example.com/realms/ens
    - name: CONSOLE_OIDC_CLIENT_ID
      value: syncd
    - name: CONSOLE_OIDC_CLIENT_SECRET
      valueFrom:
        secretKeyRef: {name: syncd-oidc, key: client-secret}
```

### 4. What the chart keeps

| Secret or volume | Holds | Kept |
|---|---|---|
| `syncd-console` | `master-key` (the console's master key, which seals the engine's client key in the console's database), `admin-password`, and `database-url` when given inline | Generated at install. Kept by every upgrade, and by `helm uninstall` (`helm.sh/resource-policy: keep`). A reinstall reuses it |
| `syncd-pki` | `ca.crt`, `ca.key`, `engine.crt`, `engine.key`, `console.crt`, `console.key` | Issued at install, kept by upgrades while it holds `ca.crt` and `ca.key`. Deleted by `helm uninstall` |
| `data-syncd-engine-0` (PersistentVolumeClaim) | The engine's store (`/var/lib/syncd/engine`) and its configuration file (`/var/lib/syncd/engine.yaml`) | Kept by `helm uninstall`, as a StatefulSet's volumes are |

The console's pods keep nothing of their own: their data directory is an `emptyDir`, and the
master key comes from the Secret as `CONSOLE_MASTER_KEY`.

!!! warning "Back up the `syncd-console` Secret"
    Its master key opens the engine's client key in the console's database. If the Secret is lost
    and the chart generates a new one, the console refuses to start over the sealed secrets (see
    [The master key](operator-guide.md#the-master-key)).

!!! note "Render the chart with Helm, against the cluster"
    The chart reads the existing Secrets with `lookup` to keep the master key and the
    certificates. `helm template`, and tools that render the chart that way, cannot see the cluster
    and generate new ones each time. Install and upgrade with `helm install` and `helm upgrade`.

### 5. Reaching the console

The console's Service is plain HTTP on 8080. Users reach it through an Ingress with TLS
(`console.ingress.*`, as in step 1); TLS ends at the Ingress. The console's own HTTPS
(`CONSOLE_TLS_CERT`) is not wired into the chart: it has no value for mounting a certificate into
the console's pod.

- **`console.trustedProxies`.** Set it to the ingress controller's pod range, so that the audit log
  and the sign-in limit see each user's address rather than the controller's.
- **`CONSOLE_PUBLIC_URL`.** Set it in `console.env` for single sign-on (above).
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

The engine's Service is `ClusterIP`. The chart has no NetworkPolicy: its metrics port (9464) is
plain HTTP to anything in the cluster, and its control API (7443) needs a client certificate from
the chart's CA. Add a NetworkPolicy of your own if your cluster needs one.

### 6. Upgrade { #helm-upgrade }

```bash
helm upgrade syncd oci://ghcr.io/winllc/charts/syncd --version X.Y.Z \
  --namespace syncd -f syncd-values.yaml --wait
```

Unlike `syncdctl upgrade`, the chart takes no backup and does not roll back by itself. Back up
first (see [Backups on Kubernetes](availability-and-sizing.md#backups-on-kubernetes)):

- the engine's volume, with a volume snapshot;
- the console's database, with `pg_dump`;
- the `syncd-console` Secret.

On upgrade, the engine migrates its store if the new release changed its format (see
[Store format](#store-format)), the console migrates its database, and the registration Job runs
again. The engine pod has 10 minutes to open its store and listen (its startup probe) before
Kubernetes restarts it. `helm rollback` and its limits are in [Rollback](#rollback).

0.3.0 is the first release with a chart, so there is no earlier chart to upgrade from. A host
install does not move to Kubernetes by itself: see
[Moving from a host to Kubernetes](availability-and-sizing.md#moving-from-a-host-to-kubernetes).

### 7. Uninstall { #helm-uninstall }

```bash
helm uninstall syncd -n syncd
```

This removes the pods, Services, Ingress and the `syncd-pki` Secret. It keeps the `syncd-console`
Secret (the master key) and the engine's volume, so a later `helm install` with the same release
name picks up the same master key and store. It issues new certificates, and its registration Job
gives the console its new credentials for the engine. Drop the console's database, and delete the
Secret and the PersistentVolumeClaim, only when you no longer need them:

```bash
kubectl -n syncd delete pvc data-syncd-engine-0
kubectl -n syncd delete secret syncd-console
```

### Air-gapped clusters

1. On a connected host, get the chart: `helm pull oci://ghcr.io/winllc/charts/syncd --version
   0.3.0` gives `syncd-0.3.0.tgz`.
2. Copy `ghcr.io/winllc/syncd:0.3.0` and `ghcr.io/winllc/syncd-console:0.3.0` into your registry,
   for example with `skopeo copy` or `docker pull`, `docker tag` and `docker push`. The chart uses
   no other image.
3. Install from the file, with your registry:

```bash
helm install syncd ./syncd-0.3.0.tgz --namespace syncd -f syncd-values.yaml \
  --set image.engine.repository=registry.example.gov/winllc/syncd \
  --set image.console.repository=registry.example.gov/winllc/syncd-console
```

Add `imagePullSecrets` if your registry needs them.

### What the chart does not do yet

- **arm64 images, a FIPS image, a bundled PostgreSQL.**
- **High availability.** One engine per store, as on hosts (see
  [High availability](availability-and-sizing.md#high-availability)).
- **Alert rules.** The chart renders no PrometheusRule; load the shipped rules yourself (see
  [Monitoring on Kubernetes](operator-guide.md#monitoring-on-kubernetes)).

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

On Kubernetes, upgrade with `helm upgrade` (see [Upgrade with Helm](#helm-upgrade)). On a host:

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
  hourly. `syncdctl` also clears systemd's start limit before its own restarts, so an upgrade
  followed at once by `renew-certs` no longer fails with `Start request repeated too quickly`.

### Bundle installs

Either command upgrades:

```bash
sudo syncdctl upgrade syncd-0.3.0-linux-x86_64.tar.gz
sudo ./syncd-0.3.0-linux-x86_64/install.sh        # on a host that runs syncd
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

### Package installs

Upgrade with the package manager:

```bash
sudo apt install ./syncd_0.3.0_amd64.deb
sudo dnf upgrade ./syncd-0.3.0-1.x86_64.rpm          # RHEL 8: ./syncd-0.3.0-1.el8.x86_64.rpm
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
  contents are kept: configuration versions, accounts and the audit log written since the upgrade,
  and so are the control API's certificates in `/etc/syncd/pki`, which the console's stored
  credentials for the engine match. (Before 0.3.0, a rollback after `syncdctl renew-certs` put the
  old certificates back, and the console could no longer reach the engine.)
- `--keep-data` switches the programs back and restores nothing. It is refused when the previous
  release cannot read the engine store, because the newer release migrated it to a newer format.
- `--yes` asks no questions.

After a rollback there is no previous release to go back to again. On a package install, rollback
is refused: install the older package, then `syncdctl restore` the backup named
`<time>-upgrade-<old>-to-<new>`. More on recovery is in
[Sizing, availability and recovery](availability-and-sizing.md#rolling-back-an-upgrade).

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
  backup taken before the upgrade; `syncdctl restore` refuses a backup whose store the installed
  release cannot read.
- A large store can take longer to migrate than the upgrade waits for the engine to be healthy.
  For such an upgrade, set `SYNCD_HEALTH_TIMEOUT` as the release notes suggest.

Stores written by 0.2.1 and older record no format: they are format 1, which is still the current
format in 0.3.0. 0.3.0 starts recording it.

## Uninstall

```bash
sudo syncdctl uninstall               # keeps /etc/syncd and /var/lib/syncd
sudo syncdctl uninstall --purge       # removes them, the backups and the service accounts too
```

Both stop and disable the services, remove the units, the command links and `/opt/syncd`. `--yes`
asks no questions. Neither drops the console's database: drop it yourself
(`DROP DATABASE syncd_console`) if you no longer need it.

On Kubernetes, see [Uninstall with Helm](#helm-uninstall).

Package installs are removed with the package manager (`apt remove syncd`, `dnf remove syncd`).
That stops and disables the services; `/etc/syncd` and `/var/lib/syncd` stay.

## First start checklist

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
- [ ] You have taken a first backup: `sudo syncdctl backup`.

On Kubernetes: the engine pod `syncd-engine-0` is ready and `kubectl -n syncd exec syncd-engine-0
-- syncd status` answers; the registration Job finished (`syncd-register` is deleted once it succeeds; a failed one stays,
for `kubectl logs`); you signed in through the Ingress and
changed the password; the `syncd-console` Secret is backed up; the engine Secret holds the
configuration's secrets; the ServiceMonitor or a scrape job of your own reads port 9464.
