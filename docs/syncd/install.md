# Installation

## At a glance

| Method | Use it for | You run |
|---|---|---|
| [Offline bundle](#install-from-the-bundle) | Any Linux distribution with systemd (glibc 2.34 or newer), including hosts with no internet access | `install.sh`, then `syncdctl` |
| [Package](#install-from-a-package) | Debian and Ubuntu (`.deb`); RHEL, Rocky, Alma 9 and newer and Fedora (`.rpm`); RHEL, Rocky, Alma 8 (`.el8.rpm`) | `apt` or `dnf`, then `syncdctl install` |

syncd runs as two systemd services on one Linux host:

- `syncd`: the engine. It reads the sources, merges identities into views and exports them to
  the target directories. It keeps its state in a store of its own on local disk.
- `syncd-console`: the web console, with its PostgreSQL database.

Every form of a release installs the same files, and `syncdctl` does the rest: service accounts,
configuration, the control API's certificates, the services, and registering the engine in the
console.

!!! note "What syncd does not have yet"
    There are no container images and no Helm chart: syncd is installed on a host, as systemd
    services. There is no ENS license check yet (licensing is on the roadmap), so there is no
    license to add. See [Licensing](../licensing.md).

For the settings referred to here, see [Configuration](configuration.md). Day-to-day running is in
the [Operator guide](operator-guide.md).

## Prerequisites

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

| File | For |
|---|---|
| `syncd-<version>-linux-x86_64.tar.gz` | The offline bundle: any distribution with systemd and glibc 2.34+, including hosts without internet access |
| `syncd-<version>-linux-x86_64.tar.gz.sha256` | The bundle's checksum |
| `syncd_<version>_amd64.deb` | Debian and Ubuntu, through apt |
| `syncd-<version>-1.x86_64.rpm` | RHEL, Rocky and Alma 9 and newer, and Fedora, through dnf |
| `syncd-<version>-1.el8.x86_64.rpm` | RHEL, Rocky and Alma 8, through dnf |
| `SHA256SUMS` | The checksums of the four files above |

Verify what you downloaded before you install it:

```bash
sha256sum -c SHA256SUMS --ignore-missing
sha256sum -c syncd-0.2.1-linux-x86_64.tar.gz.sha256      # the bundle alone
```

Inside the bundle, every file is listed in `MANIFEST.sha256`, and the installer checks the release
against it before it copies anything.

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
sha256sum -c syncd-0.2.1-linux-x86_64.tar.gz.sha256
tar -xzf syncd-0.2.1-linux-x86_64.tar.gz
sudo ./syncd-0.2.1-linux-x86_64/install.sh --local-database
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
| `--listen ADDR` | The console's address. Default `127.0.0.1:8080`. For other machines, put nginx in front rather than listening beyond loopback (see [HTTPS in front of the console](#https-in-front-of-the-console)) |
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
sudo apt install ./syncd_0.2.1_amd64.deb            # Debian, Ubuntu
sudo dnf install ./syncd-0.2.1-1.x86_64.rpm         # RHEL, Rocky, Alma 9+, Fedora
sudo dnf install ./syncd-0.2.1-1.el8.x86_64.rpm     # RHEL, Rocky, Alma 8
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
sign-in cannot stick, and the sign-in page says so. To use the console from other machines, put
nginx with TLS in front of it. The release ships an nginx configuration in
`/opt/syncd/current/share/nginx/syncd.conf`.

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
    If `CONSOLE_LISTEN` is not a loopback address and `CONSOLE_PUBLIC_URL` is not an `https://`
    address, the console logs a warning at startup: browsers on other machines will not keep its
    session cookie. It still starts.

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
`user.password_reset` in the audit log. Next steps: add accounts and roles, and set up
[single sign-on](single-sign-on.md) if you use it ([Admin guide](admin-guide.md)).

## Registering engines

The installer registers the engine on the same host as `local`. A console can manage more engines:
an administrator registers each one under **Engines** → **Register engine**, with:

| Field | |
|---|---|
| Name | 1 to 64 letters, digits, `.`, `_` or `-` |
| Control API address | `host:port`, as in the engine's `api.listen` |
| Engine CA certificate, console client certificate and key (PEM) | For mutual TLS. Required unless the address is on loopback. The key is stored encrypted with the console's master key and never shown again |

The console checks that the engine answers before it saves it, and records `engine.register` in
the audit log. On the host, `syncd-console admin register-engine --name N --address HOST:PORT
--server-ca FILE --client-cert FILE --client-key FILE` does the same; it does nothing when an
engine with that name is registered already.

!!! note "Engines on other hosts"
    The installed engine's control API listens on `127.0.0.1:7443`, and its certificate (from the
    host's own CA) names only `localhost`, `127.0.0.1` and `::1`. For a console on another host to
    manage it, the engine needs an `api.listen` that host can reach, and a certificate for the name
    the console uses. `api` can only be changed in the engine's file on its host (see
    [Configuration](configuration.md)). syncdctl does not issue such certificates.

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

### Bundle installs

Either command upgrades:

```bash
sudo syncdctl upgrade syncd-0.2.1-linux-x86_64.tar.gz
sudo ./syncd-0.2.1-linux-x86_64/install.sh        # on a host that runs syncd
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
sudo apt install ./syncd_0.2.1_amd64.deb
sudo dnf upgrade ./syncd-0.2.1-1.x86_64.rpm          # RHEL 8: ./syncd-0.2.1-1.el8.x86_64.rpm
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
  contents are kept: configuration versions, accounts and the audit log written since the upgrade.
- `--keep-data` switches the programs back and restores nothing. It is refused when the previous
  release cannot read the engine store, because the newer release migrated it to a newer format.
- `--yes` asks no questions.

After a rollback there is no previous release to go back to again. On a package install, rollback
is refused: install the older package, then `syncdctl restore` the backup named
`<time>-upgrade-<old>-to-<new>`. More on recovery is in
[Sizing, availability and recovery](availability-and-sizing.md#rolling-back-an-upgrade).

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

Stores written by 0.2.1 and older record no format: they are format 1, the current format.

## Uninstall

```bash
sudo syncdctl uninstall               # keeps /etc/syncd and /var/lib/syncd
sudo syncdctl uninstall --purge       # removes them, the backups and the service accounts too
```

Both stop and disable the services, remove the units, the command links and `/opt/syncd`. `--yes`
asks no questions. Neither drops the console's database: drop it yourself
(`DROP DATABASE syncd_console`) if you no longer need it.

Package installs are removed with the package manager (`apt remove syncd`, `dnf remove syncd`).
That stops and disables the services; `/etc/syncd` and `/var/lib/syncd` stay.

## First start checklist

- [ ] `sudo syncdctl status` shows the engine `active, control API answers` and the console
      `active, http://127.0.0.1:8080 answers`.
- [ ] nginx serves the console over HTTPS, and `console.env` sets `CONSOLE_PUBLIC_URL` and
      `CONSOLE_TRUSTED_PROXIES`.
- [ ] You signed in as `admin` and changed the one-time password.
- [ ] `/var/lib/syncd/console/master.key` is backed up somewhere safe (see
      [Operator guide](operator-guide.md#the-master-key)).
- [ ] The secrets your configuration refers to are in `/etc/syncd/engine.env`, and
      `sudo syncd test-connection <connector>` connects for each connector.
- [ ] Monitoring is set up: `api.metrics_listen` and the shipped alert rules (see
      [Operator guide](operator-guide.md#monitoring)).
- [ ] You have taken a first backup: `sudo syncdctl backup`.
