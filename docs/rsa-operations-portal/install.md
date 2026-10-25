# Installation

This page covers a new installation of the RSA Operations Portal, preparing RSA Authentication Manager (AM), the first start, verification and upgrades.

Every installation has three components:

| Component | Role |
|---|---|
| Portal | The web application: UI and business logic. |
| Connector | The service the Portal calls for everything that involves RSA AM. It is the only component that connects to AM. |
| MySQL 8.4 LTS | The Portal's database. |

## At a glance

| Method | Runs on | Use it for |
|---|---|---|
| [Release package](#install-from-the-release-package) | One Linux server with Docker or Podman | Production on one server, offline included: the images and MySQL are in the package. Settings in `.env`, `connector.env` and `mysql.env`; upgraded with `upgrade.sh`, which backs up the database first. |
| [Helm chart](#install-with-helm) | Kubernetes or OpenShift | Production on a cluster, against your own MySQL 8.4. Settings in Helm values and Secrets; upgraded with `helm upgrade`. |
| [Demo](#trying-it-out-the-demo) | A workstation with the Portal's source | A read-only look around against a mock AM, with sample data. Never for production. |

Both installation methods run the same container images, which CI builds, tests and publishes for each release. Building the images yourself is not an installation method.

## Prerequisites

### Common

| Item | Requirement |
|---|---|
| Network: Connector → RSA AM | TCP 7002 (HTTPS command server and `t3s`) on the primary, plus the replica if it serves the read-only URL |
| Network: Portal → SMTP relay | TCP 25 by default (`spring.mail.port`). The Portal checks the relay when it starts. |
| Network: Portal → AD (optional) | The LDAP URL in `active-directory.properties[n].providerURL` |
| Network: users → Portal | HTTP or HTTPS to the Portal's address (8080 and 8443 with the release package, unless you change them) |
| ENS license | A `.lic` file for the RSA Operations Portal (`mfa-portal`) from the ENS licensing portal. See [Licensing](../licensing.md). You can install it after the first start. |

You also need, from RSA AM:

* the AM **root CA certificate**, imported into a Java keystore `trust.jks` ([steps](#export-the-am-root-certificate-and-build-trustjks));
* the **Command Client** user name and password ([steps](#get-the-command-client-credentials));
* an **internal AM user for the Connector's service account**, with the custom [service account role](rsa-integration.md#least-privilege-am-roles), not Super Admin;
* recommended: an **internal AM user with a view-only role** for the Connector's searches and lookups ([read-only role](rsa-integration.md#least-privilege-am-roles));
* the **GUIDs of the AM administrative roles** that grant Trusted Agent, PMO and Help Desk access ([steps](#find-the-administrative-role-guids)).

### For the release package

| Item | Requirement |
|---|---|
| Operating system | Linux on x86-64 |
| Docker | Docker Engine 23 or later and the Docker Compose plugin (`docker compose`, v2) |
| Podman (instead of Docker) | Podman 4.6 or later (RHEL 8.9+ or 9.3+), rootful or rootless, with podman-compose 1.4.0 or later (`pip install podman-compose` where the distribution's package is older), or `podman compose` with docker-compose v2 as its provider. The host needs systemd, which runs Podman's health checks. |
| Ports | 8080 (HTTP) and 8443 (HTTPS) free on the server, or others set in `.env` ([Address and ports](#4-address-and-ports)) |
| Image access | None. The images are in the package's `images/` folder. |
| Tools | `sha256sum` and `gpg` to verify the download |

### For Helm

| Item | Requirement |
|---|---|
| Cluster | Kubernetes 1.25 or later, or OpenShift 4.12 or later |
| Helm | Helm 3.8 or later (OCI registry support) |
| Storage | A default StorageClass, or one you name: a 100Mi ReadWriteOnce volume for the license volume while it is on ([License](#helm-license)), and 8Gi for the embedded MySQL if you use it |
| Database | An external MySQL 8.4 server. The embedded MySQL is for evaluations only. |
| Image access | Pull access to `ghcr.io/winllc/rsa-operations-portal` and `ghcr.io/winllc/rsa-operations-portal-connector`, or a mirror of them ([air-gapped](#air-gapped-clusters)) |
| Ingress | An ingress controller or OpenShift Routes, to reach the Portal from outside the cluster |

## Get the release

1. Sign in to the ENS licensing portal and open **Downloads** (`/account/downloads`). It lists every version of the Portal, the latest highlighted, with each file's size and SHA-256 sum. Any account that holds an RSA Operations Portal license that is not revoked, evaluation licenses included, can download. The files come from ENS Solutions' own storage, never from GitHub Releases.
2. Download, into one directory, the files you need:

    | File | What it is |
    |---|---|
    | `rsa-operations-portal-<version>.tar.gz` | The release package |
    | `rsa-operations-portal-<version>.tgz` | The Helm chart |
    | `sbom-*.spdx.json` | Software bill of materials for each image |
    | `SHA256SUMS`, `SHA256SUMS.asc` | Checksums and their signature |

3. Verify them:

    ```bash
    sha256sum -c SHA256SUMS --ignore-missing   # every file you downloaded must say OK
    gpg --verify SHA256SUMS.asc SHA256SUMS     # when the release is signed; needs the ENS release key
    ```

See [Releases and downloads](../releases.md) for the release key and the image signatures.

## Install from the release package

### 1. Unpack

```bash
tar -xzf rsa-operations-portal-<version>.tar.gz
cd rsa-operations-portal-<version>
```

The package holds:

| Entry | Purpose |
|---|---|
| `install.sh` | First install |
| `upgrade.sh` | Upgrade an existing installation to this version |
| `docker-compose.yml` | The Portal, Connector and MySQL services, pinned to this version's images |
| `env.template` | The Portal's settings, commented. `install.sh` copies it to `.env`. |
| `connector.env.template` | The Connector's settings: the AM connection and credentials. `install.sh` copies it to `connector.env`. |
| `mysql.env.template` | MySQL's settings: the root and application user passwords. `install.sh` copies it to `mysql.env`. |
| `license/` | Where you can put `license.lic` for `install.sh` and `upgrade.sh` to give to the Portal |
| `default_build/` | Branding, page text and e-mail templates the Portal mounts. `ssl/` holds the trust store. |
| `util/` | `compose.sh` (the compose command, Docker or Podman), `systemd-unit.sh` (start at boot), `check-env.sh` (checks `.env`, `connector.env` and `mysql.env`), `split-env.sh` (splits a `.env` from an older release), `move-connector-secrets.sh` (moves AM passwords out of an older `connector.env`'s `JAVA_OPTS`), `connector-tls.sh` (the certificates for HTTPS and mutual TLS between the Portal and the Connector), `helpdesk-code-key.sh` (the key for the help desk's e-mailed codes), `file-owners.sh` (gives the Portal and the Connector their files) and `backupdb.sh` (database backup) |
| `images/` | The Portal, Connector and MySQL images (`docker save` archive, which Podman loads too) |
| `README.md`, `GUIDE.md` | The package's quick start and the full administration guide |

### 2. Run install.sh

`install.sh`, `upgrade.sh` and the `util/` scripts work with Docker or Podman. They choose the engine from `--engine docker|podman`, then `CONTAINER_ENGINE`, and otherwise use Docker if its daemon answers, else Podman; when both work and only Podman runs this installation, they stay with Podman. They print the engine and compose command they chose. `COMPOSE_COMMAND` names a compose command of your own, for example `COMPOSE_COMMAND="podman compose"`.

=== "Docker"

    ```bash
    ./install.sh /opt/rsa-operations-portal
    ```

=== "Podman"

    ```bash
    ./install.sh --engine podman /opt/rsa-operations-portal
    ```

`install.sh` copies the package to the install folder, loads the images and creates `.env`, `connector.env` and `mysql.env` from their templates (each mode 600). Then it stops and tells you what to fill in; the certificates and keys come on the [second run](#7-first-start). Run `./install.sh` without a folder to install in the unpacked folder instead.

The templates turn on mutual TLS between the Portal and the Connector: `connector.env` has `CONNECTOR_TLS_CLIENT_AUTH=required`, and `.env` names the Portal's certificate and key in `portal-tls/`. Leave those lines as they are.

### 3. Fill in the settings

In `/opt/rsa-operations-portal`, replace every `CHANGE_ME` in the three settings files. Each service receives only its own file. [Configuration](configuration.md) describes every setting. At a minimum:

`mysql.env` (MySQL):

- [ ] `MYSQL_ROOT_PASSWORD`, `MYSQL_PASSWORD` (`MYSQL_DATABASE` is `RSAAPP` and `MYSQL_USER` is `appuser`)

`.env` (the Portal):

- [ ] `spring.datasource.password`, the same as `MYSQL_PASSWORD` in `mysql.env`
- [ ] `CONNECTOR_AUTH_PASSWORD`, any long random value: the Portal uses it to authenticate to the Connector
- [ ] `role.mapped.1.rsaSecurityGuid` (TRUSTED_AGENT), `role.mapped.2.rsaSecurityGuid` (PMO), `role.mapped.3.rsaSecurityGuid` (HELP_DESK)
- [ ] `spring.mail.host`, `mail.smtp.host`, `mail.default.from`, `mfa.group.email` (from the next release, a new `.env` has `cron.lostTokenThresholdMonitor.debug=false`, so the nightly lost-token report goes to `mfa.group.email`: set it to the MFA team's address)
- [ ] With an OpenID Connect provider (next release): the new `.env` has `ENS_SSO_OIDC_END_SESSION=true`, so allow `<application.dns.name>/` as a post-logout redirect URI at the provider, or set it to `false`
- [ ] `application.dns.name`: the URL users will reach

`connector.env` (the Connector):

- [ ] `CONNECTOR_AUTH_PASSWORD`, the same value as in `.env`
- [ ] `RSA_CMDCLIENT_USER`, `RSA_CMDCLIENT_PASSWORD`: the Command Client credentials
- [ ] `RSA_ADMIN_USER_ID`, `RSA_ADMIN_USER_PASSWORD`: the service account
- [ ] Recommended: `RSA_READONLY_USER_ID`, `RSA_READONLY_USER_PASSWORD`: the read-only account
- [ ] `RSA_TRUSTSTORE_PASSWORD`: the password of `trust.jks`
- [ ] In `JAVA_OPTS`, the other AM settings ([RSA Authentication Manager settings](#rsa-authentication-manager-settings)). Keep `-Dconfig.properties.location=classpath:config.properties` as it is, and keep passwords out of `JAVA_OPTS`: the Java command line is readable by every user on the host.

Never put the AM settings or the MySQL root password in `.env`: the Portal is the service users reach. `check-env.sh` refuses a `.env` that holds them.

Keep `$` and ` #` out of the values. Docker Compose replaces `$name` with a variable, so a password `$ecret` arrives empty, and ` #` starts a comment. `check-env.sh` reports both.

Put the AM trust store at `/opt/rsa-operations-portal/default_build/ssl/trust.jks` ([steps](#export-the-am-root-certificate-and-build-trustjks)).

Optionally, customise branding in `default_build/properties/messages.properties` and the images in `default_build/static/`. See [Configuration](configuration.md#branding-text-and-email-templates).

### 4. Address and ports

The compose file publishes the Portal's container ports 8080 and 8443 as set in `.env`. Each value is `[address:]port`:

```bash
PORTAL_HTTP_PUBLISH=8080      # the Portal's 8080 (HTTP, or HTTPS once server.ssl.* is set)
PORTAL_HTTPS_PUBLISH=8443     # the container's 8443
```

| Value | Publishes on |
|---|---|
| `8080` (a bare port, the default) | Every interface, IPv4 and IPv6 |
| `127.0.0.1:8080` | This server only, for a reverse proxy on it |
| `0.0.0.0:8080` | Every IPv4 interface |
| `[::1]:8080` | One IPv6 address, in brackets |

Change them and run `./util/compose.sh up -d`. Rootless Podman cannot publish ports below 1024 (the defaults are above), unless `net.ipv4.ip_unprivileged_port_start` is lowered.

!!! warning "Plain HTTP on every interface"
    On anything but a loopback address, the Portal is reachable from the network over plain HTTP unless HTTPS is on (`server.ssl.*` in `.env`) or a TLS proxy is in front. `install.sh` and `upgrade.sh` warn about it.

**Behind a reverse proxy on the compose network** (`rsa-operations-portal_portal-net`, reaching `portal:8080` itself), set both values to `127.0.0.1:<port>`, or publish nothing at all with a `docker-compose.override.yml` beside `docker-compose.yml`, which upgrades keep:

```yaml
services:
  portal:
    ports: !reset []
```

`!reset` needs Docker Compose 2.24 or podman-compose 1.4.0. See [Security → Behind a proxy](security.md#behind-a-proxy) for the forwarded headers.

### 5. License

Optional at this point. Choose one, or upload the license on the license screen after the first start (see [License](#license)):

* **`license/license.lic`:** save the file there, and `install.sh` and `upgrade.sh` set `ENS_LICENSE` in `.env` from it (`util/license-env.sh`) before starting the Portal.
* **`ENS_LICENSE` in `.env`:** the license's text, the token between its `BEGIN` and `END` lines or the whole file base64-encoded (`base64 -w0 license.lic`). It is installed when no usable license is installed yet, or when it is a newer revision of the installed one.

### 6. Database

Nothing to do. On its first start, the MySQL container creates the database `MYSQL_DATABASE` and the user `MYSQL_USER` with `MYSQL_PASSWORD`, from `mysql.env`. The Portal then creates its tables itself, by applying its Flyway migrations, and checks that they match what it expects.

MySQL reads the `MYSQL_*` settings only when its data volume is new. Without `MYSQL_ROOT_PASSWORD` a new volume never initialises, and MySQL restarts with `Database is uninitialized and password option is not specified`. `check-env.sh` reports a missing setting.

### 7. First start

```bash
cd /opt/rsa-operations-portal
./install.sh                      # with --engine podman if you chose Podman
```

The second run:

1. adds a random key for the help desk's e-mailed codes to `.env` as `rsa.helpdesk.code-key`, unless one is set (`util/helpdesk-code-key.sh`; see [Help-desk requests](help-desk.md#the-e-mailed-codes-key));
2. moves AM passwords out of `JAVA_OPTS` in `connector.env`, if any are there;
3. runs `util/connector-tls.sh`, which makes a CA of this installation's own, the Connector's certificate in `default_build/connector-tls/` and the Portal's client certificate in `portal-tls/` (see [RSA AM integration](rsa-integration.md#portal-to-connector));
4. checks `.env`, `connector.env` and `mysql.env` with `util/check-env.sh`. Among its warnings: an AM URL whose host is an IP address or a name without a domain, which the Connector's [host name check](rsa-integration.md#transport) would refuse;
5. says where the Portal will listen, and warns about plain HTTP on the network;
6. runs `util/file-owners.sh`, which, among other things, makes `.env` and the Portal's key `portal-tls/portal-client.key` mode 640 with group 0, so that the Portal (uid 1001, group 0) can read them and no other user on the host can;
7. starts the services (`docker compose up -d` or `podman-compose up -d`) and waits up to 300 seconds (`WAIT_SECONDS`) for the Portal to report healthy. Before starting, it sets `ENS_LICENSE` in `.env` from `license/license.lic` if there is one, and the Portal installs it as it starts. Open `http://<server>:8080`.

Startup order: `mysql` must pass its `mysqladmin ping` health check, and `connector` must have started, before `portal` starts. On start, the Connector signs in to AM with the service account (and the read-only account, if set) and looks up the configured realm and security domain. If AM can't be reached, the Connector starts anyway and tries again on the next request; until then its health check reports `DOWN`. If a lookup fails, the Connector's AM session check fails (see [Troubleshooting](troubleshooting.md)).

Day to day, in the install folder:

```bash
./util/compose.sh ps                  # status (docker compose ps, or podman-compose ps)
./util/compose.sh logs -f portal      # logs (also under logs/)
./util/compose.sh stop                # stop; ./util/compose.sh up -d to start again
```

| Volume | Holds |
|---|---|
| `rsa-operations-portal_mysql_data` | The database, which also holds the license and the installation id |
| `rsa-operations-portal_ens-license` | The license files of release 1.5 and earlier, imported into the database on the first start |
| `rsa-operations-portal_ens-audit` | The audit file, when `ENS_AUDIT_FILE` is set |

`./util/compose.sh down -v` deletes them.

### Podman

* **Start at boot.** Podman has no daemon to start the containers again after a reboot. Run `./util/systemd-unit.sh` once after the install. As root it adds `/etc/systemd/system/rsa-operations-portal.service`; as another user (rootless), `~/.config/systemd/user/rsa-operations-portal.service`, and that user's services must outlive their sessions: `loginctl enable-linger <user>` (as root, once). The unit runs `util/compose.sh up -d` and `stop`. `podman generate systemd` and Quadlet units are alternatives.
* **Rootless.** The containers run as their own users (the Portal uid 1001, the Connector 998, MySQL 999) inside your user namespace, and the volumes are kept in your home. `util/file-owners.sh` fixes the owners of `logs/`, `.env` and `portal-tls/` from inside a container, so no root is needed on the host; `podman unshare ls -ln logs` shows them as the containers see them. Rootless port forwarding may show the Portal every client as coming from one address, which affects the audit trail and the rate limits: run Podman as root, or put a proxy in front that sets `X-Forwarded-For` ([Security → Behind a proxy](security.md#behind-a-proxy)).
* **SELinux** (RHEL, Fedora): the compose file labels the folders it mounts (`:z` where both the Portal and the Connector mount them, `:Z` where one does), so they work in enforcing mode.

### Upgrade with upgrade.sh

Verify and unpack the new release anywhere, and point `upgrade.sh` at the installation:

```bash
tar -xzf rsa-operations-portal-<new version>.tar.gz
cd rsa-operations-portal-<new version>
./upgrade.sh /opt/rsa-operations-portal
```

It stays with the engine that runs the installation (`--engine` to choose). `upgrade.sh`:

1. loads the new images; nothing changes if they do not load;
2. backs up the database to `backups/RSAAPP-before-<version>-<time>.sql.gz` in the install folder (skip with `--no-backup`);
3. keeps where the Portal listens: if `.env` sets neither `PORTAL_HTTP_PUBLISH` nor `PORTAL_HTTPS_PUBLISH`, it writes there how the running Portal is published (else what the old `docker-compose.yml` published), and says so;
4. updates the release files: `docker-compose.yml`, `env.template`, `connector.env.template`, `mysql.env.template`, the scripts, `util/`, `default_build/portal/` and `default_build/log4j.xml`;
5. splits a `.env` from a release that kept every setting in that one file (see below), and moves AM passwords out of `connector.env`'s `JAVA_OPTS` into `RSA_*` variables (`util/move-connector-secrets.sh`, keeping the original as `connector.env.before-secrets.<time>`);
6. adds a key for the help desk's e-mailed codes to `.env` unless one is set (`util/helpdesk-code-key.sh`);
7. makes the certificates for HTTPS between the Portal and the Connector if the installation has none (`util/connector-tls.sh`), adds `CONNECTOR_TLS_TRUSTSTORE` to `.env`, and rewrites an explicit `http://connector:8080` address to HTTPS. It moves the Portal's client certificate and key from `default_build/ssl`, which the Connector mounts too, to `portal-tls/`, and points the settings in `.env` that name them there. It leaves mutual TLS as the installation has it, and says when it is off;
8. lists, for `.env`, `connector.env` and `mysql.env`, the settings that are new in the file's template and missing from it, except the three mutual TLS settings, which go together on both sides (below), and checks the three files with `util/check-env.sh`, which warns about an AM reached by an IP address or a short name;
9. when the running MySQL is 8.0, prepares the move to 8.4 (below);
10. gives the Portal and the Connector their files (`util/file-owners.sh`), restarts on the new version and waits for the Portal to report healthy.

It keeps `.env`, `connector.env`, `mysql.env`, `license/`, a `docker-compose.override.yml`, your branding in `default_build/` (`properties`, `static`, `email-templates`, `ssl`), the certificates for HTTPS to the Connector (`connector-tls-ca/`, `default_build/connector-tls/`, `portal-tls/`), the logs, the database and the installed license. If `license/license.lic` is there, `upgrade.sh` sets `ENS_LICENSE` in `.env` from it, installed unless a different usable license, or a newer revision of it, is installed already.

The Portal updates its database schema itself on the first start of the new version (Flyway migrations). A database from release 1.5 or earlier, whose tables Hibernate created, is taken over: its tables, data and ids are kept, and what is missing is added. On a large audit table that first start takes longer. To roll back, see [Rolling back an upgrade](availability-and-sizing.md#rolling-back-an-upgrade).

!!! note "To the next release"
    Nothing needs doing to keep signing in and serving. `.env` is kept, so the settings below stay as they were unless you add them:

    - **Single sign-on and administrative roles.** With an identity provider configured, a role `ENS_SSO_ROLES_*` maps a group to now comes only through the provider; a passcode sign-in alone no longer gets it. `ENS_SSO_REQUIRE_FOR_ADMIN_ROLES=false` keeps the old behaviour. See [Single sign-on](single-sign-on.md#how-a-sign-in-goes).
    - **`ENS_SSO_OIDC_END_SESSION`** is off for an existing installation (a warning at every start while OpenID Connect is configured). Allow `<APPLICATION_DNS_NAME>/` as a post-logout redirect URI at the provider, then set it to `true`.
    - **`cron.lostTokenThresholdMonitor.debug=false`** is in new installations' `.env` only. The report still only logs on an upgraded one, with a warning at every start; add the line once `mfa.group.email` is right.
    - **Site details** need the trusted agent role; the **audit CSV export** neutralises formulas; the **Connector's console lines** start with a timestamp (`upgrade.sh` replaces `default_build/log4j.xml`).

!!! note "To the release with Docker and Podman support"
    An installation on Docker stays on Docker and needs nothing done. A Portal published without an address keeps a bare port and stays on IPv4 and IPv6; one on `127.0.0.1` (or `0.0.0.0`) keeps that address; one that published nothing gets `127.0.0.1`. New installations get `8080` and `8443`. The package's MySQL image is now named in full (`docker.io/library/mysql:<version>`), which Docker treats as the same image as before.

!!! note "To the release with sessions and the license in the database"
    The first start copies the license, the installation id and the license history from the `ens-license` volume into the database (tables `ens_license_*`), and the installation keeps its id. The compose file keeps the volume mounted for that start; after it, the volume is not read, so back up the database instead. If the volume is not mounted for that start, the Portal stops and says so rather than take a new installation id. Sessions are now kept in the database too: sessions open during this upgrade end once, and later upgrades sign nobody out.

??? note "From a release with MySQL 8.0"
    This release runs MySQL 8.4 LTS (8.0 reached its end of life in April 2026). MySQL upgrades the database in place on its first start, which takes a few minutes; there is no way back to 8.0 except the backup `upgrade.sh` takes first, so do not skip it. Before restarting anything, `upgrade.sh` moves root and the Portal's database user from `mysql_native_password`, which 8.4 no longer loads, to `caching_sha2_password`, keeping their passwords, and shuts MySQL 8.0 down cleanly. If another account still uses `mysql_native_password`, it stops and names it: move that account (`ALTER USER ... IDENTIFIED WITH caching_sha2_password BY '<its password>'`) or drop it, and run `upgrade.sh` again. A `my.cnf` of your own must not use options 8.4 removed, such as `default_authentication_plugin`.

??? note "HTTPS to the Connector and the AM passwords"
    Nothing needs editing: `upgrade.sh` makes the certificates and moves the passwords. Once the Connector runs, delete `connector.env.before-secrets.*`: it holds the old passwords. The Portal and the Connector must be upgraded together; the release package always does that.

??? note "To the release with the hardened Connector and the help desk's code key"
    Read this before upgrading production.

    * **Test against a staging AM first.** The Connector now reads AM's replies through a deserialization filter, an allow-list of the RSA SDK's classes built against the SDK and the mock AM. A real AM may send a class the list does not have, which fails that command. Upgrade a staging installation that reaches a staging AM, sign in, change a user, let the scheduled jobs run, and watch `./util/compose.sh logs connector` for `Refused a reply from Authentication Manager`. For an RSA class the warning names the `CONNECTOR_SERIAL_FILTER_ALLOW` setting to add to `connector.env`. If production refuses a reply all the same, `CONNECTOR_SERIAL_FILTER=off` in `connector.env` reads the replies without the filter, with a warning at every start, until that class is added; then remove it. See [What the Connector accepts](rsa-integration.md#what-the-connector-accepts).
    * **AM's host name is checked.** Besides the CA, the Connector now checks that AM's certificate names the host in the `ims.httpinvoker.*` URLs in `connector.env`. An installation that reaches AM by an IP address or a short name usually stops connecting, since the certificate names only the fully qualified name. `upgrade.sh` warns about such a URL (through `util/check-env.sh`). Before upgrading, put the name in the certificate in the URLs, or have the certificate reissued with the name you use. Where neither is possible, `RSA_TLS_VERIFY_HOSTNAME=false` in `connector.env` turns the name check off; the Connector then warns at every start.
    * **Mutual TLS stays as it was.** New installations have mutual TLS between the Portal and the Connector; an upgraded one keeps what it had. When it is off, `upgrade.sh` says so and the Connector warns at every start. To turn it on, run `./util/connector-tls.sh --mutual`, `./util/file-owners.sh` and `./util/compose.sh up -d` in the install folder ([Mutual TLS](rsa-integration.md#mutual-tls)).
    * **The Portal's client certificate moves.** An earlier release kept the Portal's certificate and key for mutual TLS in `default_build/ssl`, which the Connector mounts too. `upgrade.sh` moves them to `portal-tls/`, which only the Portal mounts, and changes `/ssl/portal-client.crt` and `.key` in `.env` to `/portal-tls/...`. A key of your own named elsewhere under `/ssl/` stays where it is, and `util/check-env.sh` warns: move it to `portal-tls/` and name it `/portal-tls/...` in `CONNECTOR_TLS_CLIENTKEY`.
    * **File modes.** `.env` and the Portal's client key are now mode 640 with group 0, which the Portal reads through (`util/file-owners.sh`). If you copy them into place yourself, run `./util/file-owners.sh` before starting the Portal.
    * **The help desk's e-mailed codes** are kept under a server key. `upgrade.sh` adds a random one to `.env` (`rsa.helpdesk.code-key`) unless one is set. A code e-mailed in the minutes before the upgrade no longer verifies (codes last 10 minutes): the operator sends a new one.
    * **AM permissions.** Operators' role grants and revokes, clearing cached Windows passwords and emergency access codes now run in the operator's own AM session. Before the upgrade, give the mapped AM roles the [additional permissions](rsa-integration.md#least-privilege-am-roles), or those actions fail for operators whose roles lack them.
    * **`CONNECTOR_HTTPS_ADDRESS`** is new and optional: the Connector's HTTPS port still listens on every interface unless it names one.
    * **Logs.** Line breaks in log messages are written as `\r` and `\n` in the Portal's and the Connector's text logs. A `log4j2.xml` or `log4j.xml` of your own should keep `%enc{%m}{CRLF}` or `CrlfSafePatternLayout` ([Configuration → Logging](configuration.md#logging)).

??? note "To the release with help-desk requests and rate limits"
    * **Help-desk requests.** Resetting a PIN, issuing an emergency access code, marking a token lost, deprovisioning an assigned token and clearing a cached Windows password are now [help-desk requests](help-desk.md): a ticket and reason, a verified user, a second operator for privileged users, and an e-mail to the user. Unlocking an account stays one click. Tell your Trusted Agents and Help Desk staff before the upgrade. The settings are `rsa.helpdesk.*`.
    * **E-mails.** The help-desk notice replaces the PIN-reset and lost-token e-mails. Their templates, `user-token-pin-reset-notification.properties` and `user-token-lost-notification.properties`, are no longer used; a customized copy in `default_build/email-templates` can be removed. Customize the notice through the `helpdesk.notice.*` keys in `messages.properties` instead.
    * **Generic self-service messages.** Sign-in, Test My Token, the Windows-password PIN reset, registration and exemption requests now give one answer whatever the cause (see [Security](security.md#rate-limits-and-uniform-answers)). In `messages.properties`, the keys `user.exemption.request.confirmationSent` and `user.exemption.request.noEmail` are replaced by `user.exemption.request.received`: move a customized text to the new key.
    * **Rate limits** apply from the first start (`rsa.rate-limit.*`), counted in the database. The in-memory attempt limiter is gone: Test My Token's and the PIN reset link's limits, and the token swap's, are counted in the database too.
    * **Behind a proxy**, use `SERVER_FORWARD_HEADERS_STRATEGY=native`, never `framework`, and check `SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES` ([Security → Behind a proxy](security.md#behind-a-proxy)). Without the right setting every visitor shares the proxy's per-address limit.
    * **Database.** Flyway runs `V2__helpdesk_requests`, `V3__rate_limits` and `V4__helpdesk_run_started` on the first start, adding `HelpDeskRequest`, `RateLimitWindow` and `RateLimitBackoff`.

??? note "From a release with a single `.env`"
    Older releases kept every setting in `.env` and gave it to every container, so the Portal received the AM credentials and the MySQL root password. `upgrade.sh` splits it with `util/split-env.sh`, and nothing needs editing:

    * `JAVA_OPTS`, with the AM settings, and a copy of `CONNECTOR_AUTH_PASSWORD` move to `connector.env`. JVM options of your own in the old `JAVA_OPTS` (`-Xmx…`) are kept for the Portal too, in `.env`.
    * The `MYSQL_*` settings move to `mysql.env`.
    * The original is kept as `.env.before-split.<time>` (mode 600), and the script prints what it moved. Running it again changes nothing.

    After such an upgrade:

    1. Once the Portal runs, delete `.env.before-split.*`: it still holds every secret.
    2. The Portal held the Command Client and service-account passwords before the upgrade. If it may have been compromised, change them in AM, then in `connector.env`, and run `./util/compose.sh up -d`.

## Install with Helm

The chart is `oci://ghcr.io/winllc/charts/rsa-operations-portal`, with the same version as the release. It is also in the release download as `rsa-operations-portal-<version>.tgz`. The chart installs:

* the Portal as a Deployment, one replica by default (see [Replicas](#replicas)), and a PVC for the license files of release 1.5 and earlier;
* the Connector as a second Deployment, with its own settings, the AM trust store, its HTTPS certificate and a NetworkPolicy that admits only the Portal;
* optionally, an embedded MySQL StatefulSet for evaluations.

The chart's `README.md` lists every value. Each setting in the three templates (`env.template`, `connector.env.template`, `mysql.env.template`) has a value.

### 1. Create the Secrets

Create a namespace and the Secrets the chart reads. You can also give each secret inline in your values; the chart then puts it in a Secret of its own. Never put secrets in a ConfigMap.

```bash
kubectl create namespace rsa-portal

# the AM trust store and its password
kubectl -n rsa-portal create secret generic rsa-am-trust \
  --from-file=trust.jks --from-literal=password=<trust store password>

# the Command Client, the service account and the read-only account
kubectl -n rsa-portal create secret generic rsa-am-credentials \
  --from-literal=cmdclient-user=<command client user> \
  --from-literal=cmdclient-password=<command client password> \
  --from-literal=admin-user-id=<service account user id> \
  --from-literal=admin-user-password=<service account password> \
  --from-literal=readonly-user-id=<read-only user id> \
  --from-literal=readonly-user-password=<read-only password>

# the database password
kubectl -n rsa-portal create secret generic rsa-portal-db --from-literal=password=<database password>
```

The Portal-to-Connector password and the key for the help desk's e-mailed codes need no Secret: the chart generates each once and keeps it across upgrades. To set your own, use `connector.auth.existingSecret` and `helpDesk.emailCodeKey.existingSecret`.

The Connector receives its AM host settings as `-D` options in `JAVA_OPTS`, and the passwords as files: the chart mounts them from the Secrets under `/var/run/secrets/rsa`, so none is on the Java command line. Tomcat's start script splits and evaluates `JAVA_OPTS`, so keep spaces, quotes and `$` out of `connector.extraJavaOpts`.

The chart also makes a CA and the certificates for HTTPS and mutual TLS between the Portal and the Connector, in the Secret `<release>-rsa-operations-portal-connector-tls`, and keeps them across upgrades. A new installation has mutual TLS on. To use your own certificates (a cert-manager Certificate, for example), set `connector.tls.existingSecret`, and `connector.tls.clientSecret` for the Portal's; see [RSA AM integration](rsa-integration.md#portal-to-connector).

Set `authManager.host` (and `.readOnlyHost`) to the name in AM's certificate, usually its fully qualified name: the Connector checks it, and the notes after `helm install` warn about an IP address or a name without a domain.

### 2. Prepare the database

Use an external MySQL 8.4 server. Create the database and its user before the first install:

```sql
CREATE DATABASE IF NOT EXISTS `RSAAPP`;
CREATE USER 'appuser'@'%' IDENTIFIED BY '<password>';
GRANT ALL PRIVILEGES ON RSAAPP.* TO 'appuser'@'%';
```

The Portal creates and changes its tables itself on start, by applying its Flyway migrations. No schema job is needed. To give the Portal's user only `SELECT`, `INSERT`, `UPDATE` and `DELETE`, create a second user with the DDL rights (`CREATE`, `ALTER`, `INDEX`, `REFERENCES`, `DROP`) for the migrations and set `database.migration.username` and its password. The chart connects with `jdbc:mysql://<database.host>:3306/RSAAPP?sslMode=REQUIRED`, so the server must accept TLS; set `database.params` or `database.url` otherwise.

!!! warning "Embedded MySQL: evaluation only"
    `database.embedded.enabled=true` runs the MySQL image the release package ships as a single-replica StatefulSet. It creates the database and user from `database.name`, `database.username` and `database.password` on its first start. It has no backups and no high availability. Do not use it in production.

### 3. Write your values

A minimal `my-values.yaml`:

```yaml
database:
  host: mysql.example.com
  existingSecret: rsa-portal-db
mail:
  host: smtp.example.com
  from: rsa-portal@example.com
  mfaGroupEmail: mfa-team@example.com
publicUrl: https://rsa-portal.example.com
authManager:
  host: am-primary.example.com
  readOnlyHost: am-replica.example.com      # optional
  commandClient: {existingSecret: rsa-am-credentials}
  adminUser: {existingSecret: rsa-am-credentials}
  readOnlyUser: {existingSecret: rsa-am-credentials}
  trustStore: {existingSecret: rsa-am-trust, passwordSecret: rsa-am-trust}
roles:
  trustedAgentGuid: ims.xxxxxxxx
  pmoGuid: ims.xxxxxxxx
  helpDeskGuid: ims.xxxxxxxx
```

The key values:

| Value | Release package setting |
|---|---|
| `database.host`, `.port`, `.name`, `.username`, `.existingSecret` | `spring.datasource.url`, `.username`, `.password` |
| `authManager.host`, `.port`, `.readOnlyHost` | `java.naming.provider.url`, `ims.soap.client.provider.url`, `ims.httpinvoker.client.provider.url`, `ims.httpinvoker.client.provider.readonly.url` |
| `authManager.commandClient.*` | `RSA_CMDCLIENT_USER`, `RSA_CMDCLIENT_PASSWORD` |
| `authManager.adminUser.*` | `RSA_ADMIN_USER_ID`, `RSA_ADMIN_USER_PASSWORD` (the service account) |
| `authManager.readOnlyUser.{id,password,existingSecret,idKey,passwordKey}` | `RSA_READONLY_USER_ID`, `RSA_READONLY_USER_PASSWORD` (recommended) |
| `authManager.trustStore.*` | `trust.jks`, `RSA_TRUSTSTORE_PASSWORD` |
| `connector.tls.*`, `connector.service.port` (`8443`), `networkPolicy.connector.enabled` (`true`) | HTTPS between the Portal and the Connector; see [RSA AM integration](rsa-integration.md#portal-to-connector) |
| `database.migration.*` | `spring.flyway.user`, `spring.flyway.password` |
| `authManager.realm`, `.securityDomain` | `rsa.authmanager.realm.name`, `rsa.authmanager.securityDomain.name` (default `SystemDomain`) |
| `roles.trustedAgentGuid`, `.pmoGuid`, `.helpDeskGuid` | `role.mapped.1-3.rsaSecurityGuid` |
| `mail.host`, `.port`, `.from`, `.mfaGroupEmail` | `spring.mail.host`, `mail.smtp.host`, `mail.default.from`, `mfa.group.email` |
| `publicUrl` | `application.dns.name` (default: the first ingress or route host) |
| `activeDirectory.*` | `active-directory.*` |
| `cronTimezone`, `securityBanner.*` | `cron.timezone`, `security-banner.*` |
| `consentBanner.*`, `session.*` | `ENS_CONSENT_BANNER_*`, `ENS_SESSION_*` (see [Configuration](configuration.md#security-headers-banner-and-sessions)) |
| `helpDesk.verificationRequired`, `.verificationMethods`, `.privilegedRoles`, `.approverRoles`, `.requestTtl`, `.emailCodeKey` | `rsa.helpdesk.*` (see [Help-desk requests](help-desk.md#settings)); `emailCodeKey` is generated once and kept unless you give a value or a Secret. The other `rsa.helpdesk.*` settings go in `config` |
| `connector.extraEnv` | The Connector's `RSA_TLS_VERIFY_HOSTNAME` and `CONNECTOR_SERIAL_FILTER_*` (see [RSA AM integration](rsa-integration.md#what-the-connector-accepts)) |
| `rateLimit.*` | `rsa.rate-limit.*` (see [Configuration → Rate limits](configuration.md#rate-limits)). An empty value keeps the default |
| `forwardedHeaders.internalProxies` | `SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES`: CIDR ranges, each with its prefix length; the chart's schema refuses others. Empty: the private ranges |
| `metrics.enabled` (`true`), `metrics.port` (`9090`), `metrics.serviceMonitor.*`, `metrics.allowFrom` | `MANAGEMENT_SERVER_PORT`: Prometheus and the probes on a port of their own, the `metrics` port of the Service |
| `logFormat` (`json`) | `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`; `text` for plain lines |
| `backup.*` | A database backup CronJob (off by default; see [Operator Guide](operator-guide.md#database-backups)) |
| `audit.*` | `ENS_AUDIT_*` (see [Audit logging](../audit-logging.md)) |
| `config` | Any other Portal setting, for example `cron.expiring.notification.daysBefore` |

`helm install` stops with a message when `database.host`, the database password, `mail.host` or the trust store Secret is missing. The Connector does not start without `trust.jks`.

### 4. Install

```bash
helm install rsa-portal oci://ghcr.io/winllc/charts/rsa-operations-portal --version <version> \
  -n rsa-portal -f my-values.yaml
kubectl -n rsa-portal rollout status deploy/rsa-portal-rsa-operations-portal --timeout=10m
helm test rsa-portal -n rsa-portal
```

The first start creates the database tables and can take a few minutes; the startup probe allows 10. `helm test` calls the Portal's health endpoint. Until a license is installed, the Portal shows only its license screen.

### 5. License { #helm-license }

Upload the license on the license screen after the first start, or give it to the chart:

```bash
helm upgrade --install rsa-portal oci://ghcr.io/winllc/charts/rsa-operations-portal --version <version> \
  -n rsa-portal -f my-values.yaml --set-file license.file=./license.lic
```

Or create a Secret with the key `license.lic` and set `license.existingSecret`. The chart passes the license as `ENS_LICENSE`: it is installed only when no usable license is installed yet, or when it is a newer revision of the installed one. A license uploaded later on the license screen is otherwise kept.

The license, the installation id and the license history are kept in the database (tables `ens_license_*`), shared by every replica: **back up the database**. The PVC `<release>-rsa-operations-portal-license` (`persistence.license`, mounted at `/var/lib/ens/mfa-portal`) holds the license files of release 1.5 and earlier:

* **An upgrade** keeps it mounted (`persistence.license.enabled` is `true` by default), and the first start copies its license, installation id and license history into the database. After that start the volume is not read: set `persistence.license.enabled=false` for rolling updates and more than one replica.
* **Without the volume** the chart sets `ENS_LICENSE_EXPECT_INSTALLATION_ID=true`: the Portal never makes an installation id of its own, and does not start without one from the database or the volume. A new installation without the volume sets `license.allowNewInstallationId=true` for its first start only; it is honoured only while the database holds no license state, and logged as an error at every start until set back to `false`.

### 6. Ingress, OpenShift and client certificates

**Ingress with TLS** (edge termination at the controller):

```yaml
ingress:
  enabled: true
  className: nginx
  annotations:
    nginx.ingress.kubernetes.io/proxy-body-size: 6m   # bulk uploads are up to 5 MB
  hosts:
    - host: rsa-portal.example.com
      paths: [{path: /, pathType: Prefix}]
  tls:
    - secretName: rsa-portal-tls
      hosts: [rsa-portal.example.com]
```

When the ingress or Route terminates TLS, the chart sets `SERVER_FORWARD_HEADERS_STRATEGY=native` and `ENS_COOKIE_SECURE=true`, so the Portal sees each client's own address, for its audit trail and its rate limits. If the ingress controller runs with `use-forwarded-headers` or `compute-full-forwarded-for`, set `forwardedHeaders.internalProxies` to the controller's and the load balancer's ranges. See [Security → Behind a proxy](security.md#behind-a-proxy).

**OpenShift**: set `openshift.enabled: true`, so the restricted SCC assigns the user and group ids, and use a Route instead of an Ingress. The chart annotates the Route with `haproxy.router.openshift.io/set-forwarded-headers: replace` (in `route.annotations`), so the router replaces `X-Forwarded-For` instead of appending to it: keep that annotation.

```yaml
openshift:
  enabled: true
route:
  enabled: true
  host: rsa-portal.apps.example.com
```

**Client certificates.** Sign-in uses SecurID passcodes. Some features also read the browser's X.509 client certificate: token enrollment records it, and PIN reset with a certificate, exemption requests and appointment cancellation need it. The Portal reads the certificate only from its own TLS session, never from a header. Behind edge termination, those features see no certificate. To use them, let the Portal terminate TLS itself and pass TLS through:

```bash
kubectl -n rsa-portal create secret generic rsa-portal-tls-keystore \
  --from-file=keystore.p12 --from-literal=keystore-password=<password>
kubectl -n rsa-portal create secret generic rsa-portal-user-cas \
  --from-file=truststore.p12 --from-literal=truststore-password=<password>
```

```yaml
tls:
  enabled: true          # HTTPS on 8443 in the Portal
  clientAuth: want
  keystore: {existingSecret: rsa-portal-tls-keystore, passwordSecret: rsa-portal-tls-keystore}
  truststore: {existingSecret: rsa-portal-user-cas, passwordSecret: rsa-portal-user-cas}
ingress:
  enabled: true
  className: nginx
  annotations:
    nginx.ingress.kubernetes.io/ssl-passthrough: "true"   # the controller needs --enable-ssl-passthrough
  hosts:
    - host: rsa-portal.example.com
      paths: [{path: /, pathType: Prefix}]
```

On OpenShift use `route.tls.termination: passthrough`. Without an ingress controller, use `service.type: LoadBalancer`.

With TLS passed through, the ingress controller or router hides the client's address, so all clients share its per-address rate limit; the per-user limits and backoff still apply. Raise `rateLimit.*.perIp`, or use a `LoadBalancer` Service with `externalTrafficPolicy: Local`, which keeps client addresses.

### 7. Upgrade

```bash
helm upgrade rsa-portal oci://ghcr.io/winllc/charts/rsa-operations-portal --version <new version> \
  -n rsa-portal -f my-values.yaml
```

Back up the database first. The Portal updates its schema on the first start of the new version, and `helm rollback` restores the previous images and settings, not the previous database. The license PVC and the database are kept. The [release notes for the release package](#upgrade-with-upgradesh) apply to Helm too.

!!! note "To the next release"
    With single sign-on configured, a role `sso.roles` maps a group to now comes only through the identity provider, because `sso.requireForAdminRoles` defaults to `null` (unset); a release that set `requireForAdminRoles: false` explicitly keeps the old behaviour. `sso.oidcEndSession` (default `false`) signs operators out of the OpenID Connect provider too; allow `<publicUrl>/` as a post-logout redirect URI first. The lost-token report still only logs unless `config` sets `cron.lostTokenThresholdMonitor.debug=false`.

!!! note "To the release with sessions and the license in the database"
    The license PVC stays mounted, and the first start copies the license and the installation id into the database; then you can turn the volume off ([License](#helm-license)). Sessions open during that upgrade end once; later upgrades sign nobody out.

??? note "To the release with help-desk requests and rate limits"
    The upgrade brings the values `helpDesk.*`, `rateLimit.*` and `forwardedHeaders.internalProxies`, sets `SERVER_FORWARD_HEADERS_STRATEGY=native` (it was `framework`) behind a TLS-terminating ingress or route, and adds the `set-forwarded-headers: replace` annotation to the Route.

??? note "To the release with the hardened Connector"
    * It keeps mutual TLS as the release has it. A release from before this default has it off: the notes and the Connector warn, and `connector.tls.mutual=true` turns it on (with `connector.tls.existingSecret`, also `connector.tls.clientSecret`). The chart records the setting in the `rsa-operations-portal/connector-mutual-tls` annotation of its TLS Secret and ConfigMap; an upgrade that can read neither that nor the running Connector, such as `helm template --is-upgrade` or a changed name override, stops and asks for `connector.tls.mutual`.
    * It checks AM's host name: if the notes warn that the Connector reaches AM by an IP address or a short name, use the certificate's name in `authManager.host`, have the certificate reissued, or set `RSA_TLS_VERIFY_HOSTNAME=false` in `connector.extraEnv`.
    * It filters AM's replies: test it against a staging AM first and watch the Connector's log for `Refused a reply from Authentication Manager` (see the release package notes, with `connector.extraEnv` for the settings).
    * It generates `helpDesk.emailCodeKey`: a help-desk e-mail code sent in the minutes before the upgrade no longer verifies, so the operator sends a new one.

??? note "To the release with HTTPS between the Portal and the Connector"
    The upgrade creates the TLS Secret, switches the Portal's Connector address to `https://...:8443`, mounts the AM passwords as files and adds the Connector NetworkPolicy. Both pods roll once. On a cluster without NetworkPolicy support, set `networkPolicy.connector.enabled=false`. With `metrics.enabled` (the default), the probes move to the management port, and the Portal's log lines become JSON unless `logFormat: text`.

### 8. Uninstall

```bash
helm uninstall rsa-portal -n rsa-portal
```

This keeps the license PVC (`helm.sh/resource-policy: keep`) and the embedded MySQL's data PVC (`data-rsa-portal-rsa-operations-portal-mysql-0`); delete them yourself once you no longer need them. The license and the installation id are in the database, which uninstall does not touch.

### Replicas

Sessions, rate limits, scheduled-job locks and the license are kept in the database, so `replicaCount` can be more than 1 once the license volume is off (`persistence.license.enabled=false`) or is ReadWriteMany; the chart refuses otherwise. While a ReadWriteOnce license volume is mounted, the Portal is updated with the `Recreate` strategy; otherwise with a rolling update that is never without a ready Portal. With more than one replica, give the ingress cookie affinity (ingress-nginx: `nginx.ingress.kubernetes.io/affinity: cookie`): a help-desk emergency access code is shown only by the replica that made it. The Connector runs exactly one replica.

### Air-gapped clusters

Use the chart from the release download, or pull it on a connected machine:

```bash
helm pull oci://ghcr.io/winllc/charts/rsa-operations-portal --version <version>
```

Mirror the images to your registry, keeping their paths, and point the chart at it with `global.imageRegistry`:

```bash
for image in winllc/rsa-operations-portal:<version> winllc/rsa-operations-portal-connector:<version>; do
  skopeo copy docker://ghcr.io/$image docker://registry.example.com/$image
done
# only for the embedded MySQL
skopeo copy docker://docker.io/library/mysql:8.4.11 docker://registry.example.com/library/mysql:8.4.11

helm install rsa-portal ./rsa-operations-portal-<version>.tgz -n rsa-portal -f my-values.yaml \
  --set global.imageRegistry=registry.example.com
```

The release package's `images/` archive holds the same images: `docker load` (or `podman load`) them, then tag and push them to your registry.

## Trying it out: the demo

The Portal's source repository has a `docker-compose.demo.yaml` at its root. It builds the Portal, the Connector and a mock AM from source and runs a read-only demo with sample sites, shipments, Trusted Agents, appointments and exemption requests. The sign-in credentials are on its login page. Nothing a visitor does changes the database or AM, and the scheduled jobs do not run.

!!! warning "Not for production"
    The demo's credentials are public, so anything those accounts can see is readable by anyone who reaches it. It runs against a mock AM only.

1. Get a license. Without one the demo shows only the license screen, and the read-only demo takes no uploads. Download an evaluation license for the RSA Operations Portal from the ENS licensing portal and save it as `license/license.lic` beside the compose file.
2. Start it, giving the license as `ENS_LICENSE`; the Portal installs it as it starts. Use `-p rsa-demo`: without it, the demo would share its project and volumes with the repository's other compose files.

    ```bash
    ENS_LICENSE="$(base64 -w0 license/license.lic)" \
      docker compose -f docker-compose.demo.yaml -p rsa-demo up --build
    ```

3. Reach it. The demo **publishes no ports**: the Portal listens on 8080 inside the demo network only. Either attach a reverse proxy to the `rsa-demo_demo-net` network and forward to `portal:8080`, or write an override file bound to `127.0.0.1`:

    ```yaml
    # demo-ports.yaml
    services:
      portal:
        ports: ["127.0.0.1:8080:8080"]
    ```

    ```bash
    docker compose -f docker-compose.demo.yaml -f demo-ports.yaml -p rsa-demo up --build
    ```

    Then open <http://localhost:8080>.

`docker compose -f docker-compose.demo.yaml -p rsa-demo down` resets the data, the license included: give `ENS_LICENSE` again on the next start. Every start loads the same sample data. The demo needs about 1 GB of memory once running; on a Mac, give the Docker or Podman VM 4 GB if you can, since the build compiles the code.

## RSA Authentication Manager setup

These steps apply to both methods.

### Export the AM root certificate and build trust.jks

The Connector must trust AM's TLS certificate.

1. In a browser, open `https://<am-server>:7002`. An *Error 404* page appears.
2. Open the page's certificate, go to **Certification Path**, select the **top (root)** certificate, then **View Certificate → Details → Copy to File**.
3. Export it as **DER encoded binary X.509 (.CER)** with the name `am_root.cer`.
4. Import it with the Java `keytool`:

    ```bash
    keytool -import -keystore trust.jks -storepass <truststore_password> \
            -file am_root.cer -alias rsa_am_ca -trustcacerts
    ```

    `keytool` creates the trust store if it does not exist yet.

5. Release package: place it at **`default_build/ssl/trust.jks`** in the install folder. Helm: put it in a Secret (`authManager.trustStore.existingSecret`). The Connector sees it as `/custom/ssl/trust.jks` either way.
6. Use `<truststore_password>` for `RSA_TRUSTSTORE_PASSWORD` in `connector.env` (Helm: `authManager.trustStore.password` or `.passwordSecret`).

The alias `rsa_am_ca` matches the default `ims.ssl.client.root.ca.alias`.

### Get the Command Client credentials

AM generates a random Command Client user and password at installation. On the **AM host**:

```bash
cd RSA_AM_HOME/utils
./rsautil manage-secrets --action list
# enter Operations Console credentials when prompted
```

Copy the values shown as **Command Client User Name** and **Command Client User Password** into `RSA_CMDCLIENT_USER` and `RSA_CMDCLIENT_PASSWORD` in `connector.env` (Helm: `authManager.commandClient`).

### Find the administrative role GUIDs

For each AM administrative role that should map to a Portal role:

1. Sign in to the AM **Security Console**.
2. Go to **Administration → Administrative Roles → Manage Existing**.
3. Click the role name and choose **View**.
4. In the browser address bar, find `guid=ims.xxxxx…`. The `ims.xxxxx…` value is the GUID.

Set them as `role.mapped.1.rsaSecurityGuid` (TRUSTED_AGENT), `role.mapped.2.rsaSecurityGuid` (PMO) and `role.mapped.3.rsaSecurityGuid` (HELP_DESK). With Helm: `roles.trustedAgentGuid`, `roles.pmoGuid` and `roles.helpDeskGuid`.

### RSA Authentication Manager settings

In the release package the accounts are `RSA_*` variables in `connector.env`, and the rest are `-D` options inside `JAVA_OPTS` there. The Helm chart builds them from `authManager.*`.

| Setting | Value |
|---|---|
| `RSA_CMDCLIENT_USER`, `RSA_CMDCLIENT_PASSWORD` | The Command Client user name and password |
| `RSA_ADMIN_USER_ID`, `RSA_ADMIN_USER_PASSWORD` | The service account: an internal AM user with the [service account role](rsa-integration.md#least-privilege-am-roles) |
| `RSA_READONLY_USER_ID`, `RSA_READONLY_USER_PASSWORD` | Recommended: an internal AM user with the [read-only role](rsa-integration.md#least-privilege-am-roles) |
| `RSA_TRUSTSTORE_PASSWORD` | Password of `trust.jks` |
| `java.naming.provider.url` | `t3s://<AM primary FQDN>:7002` |
| `ims.soap.client.provider.url` | `https://<AM primary FQDN>:7002/ims-ws/services/CommandServer` |
| `ims.httpinvoker.client.provider.url` | `https://<AM primary FQDN>:7002/ims-ws/httpinvoker/CommandServer`. Use the name AM's certificate has: the Connector checks it |
| `ims.httpinvoker.client.provider.readonly.url` | The same URL on the primary or a replica |
| `RSA_TLS_VERIFY_HOSTNAME` | Optional: `false` turns off the check of AM's host name, for an AM reached by a name its certificate lacks, with a warning at every start |
| `ims.ssl.client.trust.keystore.filename` | `/custom/ssl/trust.jks` |
| `rsa.authmanager.realm.name`, `rsa.authmanager.securityDomain.name` | Realm and security domain the Portal manages (default `SystemDomain`) |

## Other setup

* **E-mail.** The Portal checks the SMTP relay at startup and does not start without it. Set `spring.mail.host` and `mail.smtp.host` (Helm: `mail.host`), `mail.default.from` and `mfa.group.email`.
* **Active Directory** (optional). The Portal can keep users in an "RSA enabled" AD group while they hold a token. See [Configuration](configuration.md). With Helm, set `activeDirectory.enabled` and one entry in `activeDirectory.instances` per RSA security domain.
* **Audit logging.** Every event is kept in the database and shown on the Audit Log page. Extra outputs (stdout, a file, Splunk, syslog) are set with the `ENS_AUDIT_*` settings (Helm: `audit.*`). See [Audit logging](../audit-logging.md).
* **Branding.** Used while the license includes the branding feature. Release package: edit `default_build/`. Helm: supply ConfigMaps through `branding.*`.

## License

The Portal needs an ENS Solutions license. Download an evaluation or your organization's license from the ENS licensing portal. See [Licensing](../licensing.md).

* **License screen:** `/admin/license`, for PMO users (*PMO Activities > Open License*). Upload the `.lic` file there. It takes effect at once, without a restart. Until a license is installed, only sign-in and the license screen work.
* **Installation id:** shown on the license screen. Enter it on the ENS licensing portal to bind a license to this installation.
* **At install time:** `license/license.lic` or `ENS_LICENSE` in `.env` (release package; see [step 5](#5-license)), or `license.file` or `license.existingSecret` (Helm; see [License](#helm-license)). `ENS_LICENSE` is installed when no usable license is installed yet, or when it is a newer revision.
* **Where it is kept:** in the database, tables `ens_license_*`: back it up. Release 1.5 and earlier kept it in files, `ENS_LICENSE_FILE` (`/var/lib/ens/mfa-portal/license.lic`) and `ENS_LICENSE_STATE_DIR` (`/var/lib/ens/mfa-portal`), on the `ens-license` volume or the license PVC; the first start of a later release copies them into the database.

## First start checklist

- [ ] The trust store holds the AM root certificate, and its password is set.
- [ ] The Command Client, service-account and (recommended) read-only credentials are set, and the AM roles are in place ([least-privilege AM roles](rsa-integration.md#least-privilege-am-roles)).
- [ ] The three role GUIDs are set.
- [ ] The database password is set, and the database accepts the Portal's user.
- [ ] The SMTP relay is reachable from the Portal.
- [ ] `application.dns.name` (Helm: `publicUrl`) is the address users reach.
- [ ] The license is installed, and you noted the installation id.
- [ ] Release package: `.env`, `connector.env` and `mysql.env` are mode 600.
- [ ] The database is in your backups: it holds the license and the installation id.

## Verification

| Check | Command or action | Expected |
|---|---|---|
| Services running | `./util/compose.sh ps` / `kubectl -n rsa-portal get pods` | `portal`, `connector`, `mysql` up and `portal` healthy / both Deployments ready |
| Portal responds | `curl -I http://localhost:8080/login` (Helm: through `kubectl port-forward`) | `200` |
| Health | `curl http://localhost:8080/actuator/health/readiness` (with `MANAGEMENT_SERVER_PORT` set, on that port; Helm: port 9090) | `{"status":"UP"}`. Readiness includes the database (`db`) and the Connector (`connector`), which is `DOWN` when the Connector can't be reached over HTTPS or can't open an AM admin session. |
| Connector | `./util/compose.sh exec connector curl -s http://localhost:8080/app/health` | `UP` |
| Helm test | `helm test rsa-portal -n rsa-portal` | `Succeeded` |
| Logs | `./util/compose.sh logs portal` or `logs/rsaapp-portal.log` / `kubectl -n rsa-portal logs deploy/rsa-portal-rsa-operations-portal` | No startup exceptions (datasource, Flyway, SMTP `test-connection`, Connector TLS), and a `License:` line. The Connector log has no warning that `rsa.readonly.user.id` is not set |
| License | `/admin/license` as a PMO user | The license state, licensee and features; the installation id |
| Sign-in | Browse to `/login` and sign in with an AM user and SecurID passcode | Home page. Users holding a mapped admin role see the **Trusted Agent Activities**, **Token Management** or **PMO Activities** menus. |

!!! tip "Bootstrap the first PMO user"
    The Portal derives roles **only** from AM administrative roles. To get the first administrator, assign the AM administrative role whose GUID is in `role.mapped.2.rsaSecurityGuid` (PMO) to a user in the AM Security Console. That user can then install the license and manage roles inside the Portal ([Admin Guide](admin-guide.md#managing-user-roles)).
