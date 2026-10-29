# Installation

This page covers a full ReadyRoom deployment: the server and its PostgreSQL database, the LDAP
connection, TLS and client certificates, email, Windows sign-in, and the Windows client on each
workstation. Every setting mentioned here is described in [Configuration](configuration.md).

!!! danger "Use your own key material"
    TLS is off by default. ReadyRoom contains no keystore or truststore: with TLS on, it refuses to
    start until you supply your own server keystore and, while client certificates are requested
    (`client-auth: want`, the default), your own truststore. The Helm chart does not render with
    `tls.enabled` and no `tls.truststore.secretName`, unless `tls.clientAuth` is `none`. Put only the
    CAs that issue your users' certificates in the truststore. See [Certificates](#certificates).

## At a glance

| Method | Runs on | Use it for |
|---|---|---|
| [Release package](#install-from-the-release-package) | One Linux host with Docker or Podman | Production on one host, air-gapped sites included: the images and PostgreSQL 16 are in the package. Configured in `.env`; upgraded with `upgrade.sh`. |
| [Helm chart](#install-with-helm) | Kubernetes or OpenShift | Production on a cluster, against your own PostgreSQL. Configured with Helm values; upgraded with `helm upgrade`. |
| [Demo](#trying-it-out-the-demo) | A workstation with the ReadyRoom source | A read-only look around with seeded data. Never for production. |

## Prerequisites

### Common to both methods

| Requirement | Notes |
|---|---|
| PostgreSQL | ReadyRoom keeps its own records in PostgreSQL; it is the only database supported. The release package includes PostgreSQL 16. With Helm, use your own server (an embedded one is available for evaluation only). |
| LDAP directory | Must hold the people (by default `objectclass=inetOrgPerson`) and the groups (`groupOfUniqueNames` with `uniqueMember`; an optional `owner` is treated as the group's manager). Needs a read-only bind account that can search users and groups. The paged results control is used if it is available. Active Directory works. |
| SMTP server | Used to email absence alerts. Optional: see [Configure email](#configure-email). |
| License | An ENS Solutions license file (`.lic`) from your account on the ENS licensing portal: an evaluation, or the license issued for your order. See [Licensing](../licensing.md). Without one, ReadyRoom offers only sign-in and its license screen. |
| Server certificate | A PKCS#12 keystore for the HTTPS listener, if ReadyRoom terminates TLS itself. Needed for client certificates and the Windows client. |
| Client certificate trust | A PKCS#12 truststore holding only the CA(s) that issue the users' client certificates. Required with TLS on, unless certificate sign-in is turned off. |
| Windows workstations | Windows PowerShell 5.1 and Task Scheduler. Each user needs either a client certificate in `CurrentUser\My` or, with Windows sign-in turned on, a domain account on a domain-joined PC. |

### For the release package

- A Linux host with 2 CPUs, 4 GB RAM and 10 GB disk, and one container engine:
    - **Docker** Engine 24 or later with Docker Compose 2.24 or later (the `docker compose` plugin, or
      a v2 `docker-compose`; the Python `docker-compose` v1 cannot read the package), or
    - **Podman** 4.4 or later with **podman-compose 1.0.6 or later** (EPEL's version for RHEL 8 and
      9; `pip install --user podman-compose` where your distribution's package is older), or
      `podman compose`. Rootful or rootless. With podman-compose 1.0.x, write `.env` values without
      quotes: Podman keeps quotes as part of the value. Podman runs health checks from systemd timers, so the host needs systemd.
- No internet access: the container images are in the package.

### For the Helm chart

- Kubernetes 1.25 or later, or OpenShift 4, and Helm 3.
- For an upgrade from a release that kept the license on a volume: a storage class for that small
  `ReadWriteOnce` volume (100 Mi). See [License](#helm-license).
- Access to `ghcr.io/winllc`, or a registry mirror (see [Air-gapped clusters](#air-gapped-clusters)).
- For client certificates and the Windows client: an ingress controller that supports TLS
  passthrough (ingress-nginx with `--enable-ssl-passthrough`, or an OpenShift route), or a
  `LoadBalancer` Service.

## Get the release

1. Sign in to the ENS licensing portal and open **Downloads** (`/account/downloads`). It lists every
   ReadyRoom version, the latest highlighted, with each file's size and SHA-256 sum. Any account that
   holds a ReadyRoom license that is not revoked, evaluation licenses included, can download. The
   files come from ENS Solutions' own storage, never from GitHub Releases.
2. Download, into one directory, `SHA256SUMS`, `SHA256SUMS.asc` and what you need:
   `readyroom-<version>.tar.gz` (the release package) or `readyroom-helm-<version>.tgz` (the chart).
3. Verify them:

    ```bash
    sha256sum -c SHA256SUMS --ignore-missing   # every file you downloaded must say OK
    gpg --verify SHA256SUMS.asc SHA256SUMS     # when the release is signed; needs the ENS release key
    ```

See [Releases and downloads](../releases.md) for the release key and the image signatures.

## Install from the release package

### 1. Unpack and run `install.sh`

The package's scripts (`install.sh`, `upgrade.sh`, `backup.sh`, `restore.sh` and `compose.sh`) work
with Docker or Podman. Each chooses the engine from `--engine docker|podman`, then
`CONTAINER_ENGINE`, and otherwise uses Docker if its daemon answers, else Podman. It prints the
engine and compose command it chose. `COMPOSE_COMMAND` overrides the compose command, for example
`COMPOSE_COMMAND="podman compose"`.

=== "Docker"

    ```bash
    tar xzf readyroom-1.2.3.tar.gz
    cd readyroom-1.2.3
    ./install.sh
    ```

=== "Podman"

    ```bash
    tar xzf readyroom-1.2.3.tar.gz
    cd readyroom-1.2.3
    ./install.sh --engine podman
    ```

The first run loads the images from `images/`, creates `.env` from `env.template` with a generated
database password, creates the `license/`, `tls/` and `kerberos/` directories, and lists the lines
you must edit. It then stops.

### 2. Fill in `.env`

Replace every `CHANGE_ME`:

| Setting | What |
|---|---|
| `APPLICATION_BASE_URL` | The address users reach ReadyRoom at. Absence emails link to it. |
| `SPRING_LDAP_URLS`, `LDAP_USERNAME`, `LDAP_PASSWORD` | Your directory (`ldaps://...:636` in the template) and the service account. For plain `ldap://`, which is not recommended and logs a warning, remove `SPRING_LDAP_URLS` and set `LDAP_HOST` and `LDAP_PORT` instead. |
| `APPLICATION_USERBASEDN`, `APPLICATION_GROUPS_0_GROUPSBASEDN` | Where users and group trees are. |
| `APPLICATION_SUPERUSERDNS_0` | At least one administrator, by full DN. None is packaged; without one, startup logs a warning and nobody can administer ReadyRoom or reach the license screen. |
| `SPRING_MAIL_*`, `APPLICATION_NOTIFICATIONSENDEREMAIL` | Your SMTP server and the From address. With `SPRING_MAIL_HOST` set, `APPLICATION_NOTIFICATIONSENDEREMAIL` is required, or startup fails. |

The template also lists optional blocks, commented out: the consent banner, session lifetimes,
reverse-proxy settings, client certificates (`ENS_X509_*`), audit outputs (`ENS_AUDIT_*`), JSON
logs, the management port, statuses that need approval, privacy and retention, reports, the public
API and webhooks. From the next release it also has a *Single sign-on* block (`ENS_SSO_*`, OpenID
Connect and SAML 2.0): the issuer and the SAML metadata must be `https://` (or the metadata a file in
`tls/`), and SAML needs an `https://` `APPLICATION_BASE_URL`; see
[Single sign-on](single-sign-on.md). See [Configuration](configuration.md).

`.env` holds the database, directory and mail passwords. `install.sh` makes it mode 600; keep it
private. List settings are numbered (`APPLICATION_SUPERUSERDNS_0`, `_1`, ...). Any other setting
from [Configuration](configuration.md) can be added as an environment variable: the property name
upper-cased, with dots and dashes dropped or turned into underscores
(`application.check-out-after-minutes` becomes `APPLICATION_CHECKOUTAFTERMINUTES`).

The optional pieces are switched on with `COMPOSE_FILE` in `.env`:

| File | Turns on | Files it needs |
|---|---|---|
| `docker-compose.tls.yml` | TLS with your own keystore and truststore, and client certificates | `tls/keystore.p12` and `tls/truststore.p12`, both required |
| `docker-compose.windows-auth.yml` | Windows (Kerberos) sign-in | `kerberos/krb5.conf`, `kerberos/http.keytab` |

```bash
COMPOSE_FILE=docker-compose.yml:docker-compose.tls.yml:docker-compose.windows-auth.yml
```

podman-compose does not read `COMPOSE_FILE` from `.env`, so run everyday commands through
`./compose.sh`, which passes it on. The bind mounts carry `:Z`, so SELinux relabels them.

The container runs as user 185, group 0. Make the files in `tls/` and `kerberos/` readable by
group 0:

=== "Docker"

    ```bash
    chgrp 0 tls/*.p12 kerberos/http.keytab && chmod 0640 tls/*.p12 kerberos/http.keytab
    ```

=== "Podman"

    Rootful, as for Docker. Rootless, your own group is group 0 in the container, so `chmod` alone
    is enough:

    ```bash
    chmod 0640 tls/*.p12 kerberos/http.keytab
    ```

`install.sh` and `upgrade.sh` stop if `SSL_ENABLED=true` but `docker-compose.tls.yml` is not in
`COMPOSE_FILE`, or if it is and `tls/keystore.p12` or `tls/truststore.p12` is missing. When a reverse
proxy terminates TLS for browsers and `SSL_ENABLED` stays `false`, set `ENS_COOKIE_SECURE=true`
(the older name `SESSION_COOKIE_SECURE` still works) so the session cookie is only sent over HTTPS.

### 3. Address and port

ReadyRoom is published on the host at `READYROOM_BIND_ADDRESS:READYROOM_PORT` from `.env`. The
container itself always listens on 8181; do not set `SERVER_PORT`.

| `.env` | Default | Effect |
|---|---|---|
| `READYROOM_BIND_ADDRESS` | `0.0.0.0` | Every interface. `127.0.0.1` keeps it to this host, for a reverse proxy on the same host that terminates TLS (proxy to `http://127.0.0.1:8181`). One of the host's own addresses publishes it on that interface only. |
| `READYROOM_PORT` | `8181` | The host port. Keep it at 1024 or above for rootless Podman, unless `net.ipv4.ip_unprivileged_port_start` is lowered. |

After a change, run `./compose.sh up -d`.

!!! warning "Plain HTTP on every interface"
    Until TLS is on ([Certificates](#certificates)), the default `0.0.0.0:8181` serves plain HTTP to
    the network, and `install.sh` warns about it. Turn TLS on, or put a TLS reverse proxy in front
    and set `READYROOM_BIND_ADDRESS=127.0.0.1`.

### 4. License

Supply the license in one of these ways. See [Licensing](../licensing.md) for the rest.

- **Upload it** on the license screen after the first start: `<APPLICATION_BASE_URL>/app/admin/license`,
  signed in as an administrator.
- **`ENS_LICENSE` in `.env`:** the license itself, either the token between its `BEGIN` and `END`
  lines or the whole file base64-encoded (`base64 -w0 license.lic`). It is installed at start if no
  license is installed yet, or if it is a newer revision of the installed one.
- **The `license/` drop-in:** save the file as `license/license.lic`. `install.sh` and `upgrade.sh`
  set `ENS_LICENSE` in `.env` from it before starting ReadyRoom, which installs it as above. Without
  one, they say where to upload it.

ReadyRoom keeps the license, this installation's id and the highest revision seen of each license in
its database (the `ens_license_*` tables), so the database backup covers them. Earlier releases kept
them in files on the license volume: `ENS_LICENSE_FILE` (`/var/lib/ens/readyroom/license.lic`) and
`ENS_LICENSE_STATE_DIR` (`/var/lib/ens/readyroom`). The first start of a release that keeps them in
the database imports those files once, while the database holds no installation id yet.

### 5. First start

Run `./install.sh` again (with `--engine podman` if you chose Podman). It starts ReadyRoom with the
compose command (`docker compose up -d` or `podman-compose up -d`), after setting `ENS_LICENSE` from
`license/license.lic` if you supplied one. The first start takes a minute or two while ReadyRoom creates its schema
(Flyway; see [Prepare PostgreSQL](#prepare-postgresql)).

```bash
./compose.sh ps                   # app becomes "healthy"
./compose.sh logs -f app          # application log
./compose.sh up -d                # apply changes to .env
./compose.sh down                 # stop; the volumes are kept
```

With Docker, `docker compose ...` in the same directory does the same.

The container's health check calls `/actuator/health` on port 8181, or on `MANAGEMENT_SERVER_PORT`
when that is set in `.env`. It includes the mail server unless `MANAGEMENT_HEALTH_MAIL_ENABLED=false`
(see [Configure email](#configure-email)).

| Volume | Holds |
|---|---|
| `readyroom_postgres_data` | The database, which also holds the license and the installation id |
| `readyroom_ens-license` | An earlier release's license files, imported on the first start (see [License](#4-license)) |
| `readyroom_ens-audit` | The audit log file, when `ENS_AUDIT_FILE` is set |

The volumes have the same names under Docker and Podman. The containers are `readyroom-app-1` and
`readyroom-postgres-1` with Docker, `readyroom_app_1` and `readyroom_postgres_1` with podman-compose.

!!! warning "Never run `down -v`"
    It deletes the volumes, the database with the license and the installation id among them. A
    license bound to the old id then stops working.

`install.sh` can be run again at any time. It never overwrites `.env`, the database or an installed
license.

### Podman

- **Rootless:** the data is in named volumes, which need no ownership changes. Run
  `loginctl enable-linger <user>` once, so the containers keep running after you log out, and
  `systemctl --user enable podman-restart.service`, so the containers (`restart: unless-stopped`)
  start again after a reboot.
- **SELinux** (RHEL, Fedora): the bind mounts of `tls/` and `kerberos/` carry `:Z`. Docker on hosts
  without SELinux ignores it.
- **Starting at boot** without `podman-restart.service`: generate units with
  `podman generate systemd --new --files --name readyroom_app_1` (and `readyroom_postgres_1`), or
  write Quadlet `.container` files. Keep the volume names above either way.
- **Moving from Docker to Podman** is a backup and restore, not an upgrade: the engines keep separate
  volumes. Run `./backup.sh` with Docker, stop it (`docker compose down`), run
  `./install.sh --engine podman` with the same `.env`, then `./restore.sh --engine podman ...`.

### 6. Upgrade

Back up first, from the current release's directory, then unpack the new release next to it and run
its `upgrade.sh`, naming the current directory. `upgrade.sh` does not back up for you.

```bash
cd readyroom-1.2.3 && ./backup.sh && cd ..       # the database and the license volume, into backups/

tar xzf readyroom-1.2.4.tar.gz
cd readyroom-1.2.4
./upgrade.sh ../readyroom-1.2.3                  # add --engine podman for a Podman installation
```

`upgrade.sh` copies `.env`, `license/`, `tls/` and `kerberos/` across without overwriting anything,
loads the new images, lists any new settings in `env.template` that your `.env` does not mention,
and recreates the containers. The volumes are kept, and ReadyRoom migrates its schema on start
(Flyway). Run ReadyRoom from the new directory from then on.

If `.env` has `SSL_ENABLED=true`, `COMPOSE_FILE` must include `docker-compose.tls.yml` and
`tls/keystore.p12` and `tls/truststore.p12` must be your own: the image contains neither, and
`upgrade.sh` stops otherwise.

!!! note "Upgrading to the next release"
    - **Revocation.** Client certificates are checked at sign-in when `.env` does not set
      `ENS_X509_REVOCATION`: OCSP, then the CRL, soft-fail (a revoked certificate is refused; one no
      responder answers for still signs in, with a warning). A `.env` that sets
      `ENS_X509_REVOCATION` keeps it, `off` included. If your `.env` sets
      `ENS_X509_REVOCATION_FAIL=closed` without a mode, that is honoured: make sure ReadyRoom reaches
      your OCSP responders and CRLs, or set `ENS_X509_CRL_DIR`, or `ENS_X509_REVOCATION=off`. See
      [Revocation from the next release](#revocation-from-the-next-release).
    - **Single sign-on.** An `http://` `ENS_SSO_SAML_IDP_METADATA` or `ENS_SSO_OIDC_ISSUER_URI`, and
      SAML with an `http://` `APPLICATION_BASE_URL`, stop startup.
    - **`env.template`** has a *Single sign-on* block with the `ENS_SSO_*` settings; copy it into
      an older `.env` to use single sign-on. `upgrade.sh` lists the new settings.
    - **Signing out of the identity provider.** A new `.env` sets `ENS_SSO_OIDC_END_SESSION=true`
      and `ENS_SSO_SAML_SINGLE_LOGOUT=true`; an upgraded `.env` keeps signing out as before until you
      add them (register `<base>/signed-out` at the OIDC provider; for SAML set the SP key and
      certificate and re-import the SP metadata). See
      [Single sign-on → Signing out](single-sign-on.md#signing-out).
    - **Rate limits** are counted in the database (Flyway `V11`, a new table), once for all
      replicas.
    - **Webhook proxy.** `APPLICATION_WEBHOOKS_PROXY` (with `APPLICATION_WEBHOOKS_PROXYCREDENTIALS`
      or `_PROXYCREDENTIALSFILE`) is new; unset, webhooks connect directly as before. See
      [Webhooks → Through an egress proxy](webhooks.md#through-an-egress-proxy).

!!! note "Upgrading to the release with Docker and Podman support"
    - `.env` is copied as it is, so an existing installation keeps its `READYROOM_BIND_ADDRESS` and
      `READYROOM_PORT`: one published on `127.0.0.1` stays so. A `.env` without
      `READYROOM_BIND_ADDRESS` publishes on `0.0.0.0`, as earlier releases did. `upgrade.sh` prints
      the address in use.
    - An existing Docker installation needs no changes: the scripts find Docker, and the project,
      container and volume names are the same. The PostgreSQL image is now named
      `docker.io/library/postgres:16`, the same image as `postgres:16`.

!!! note "Upgrading to the release with the license in the database"
    Keep the license volume mounted for the first start (the package's compose file always mounts
    it): that start imports the license and the installation id into the database. Everyone signs
    in again once.

!!! note "The first upgrade to a release with Flyway"
    Flyway finds tables but no `flyway_schema_history`, records the existing schema as version 0, and
    runs `V1`. `V1` adds the missing indexes and constraints and the audit trail's append-only
    trigger, merges user rows whose DNs differ only by letter case, and rebuilds an index an
    interrupted script left invalid. On a large check-in table where the old
    `001_indexes_and_unique_user_dn.sql` never ran, building the indexes can take minutes. The log
    shows `Successfully applied 1 migration`. There is nothing to run by hand.

Then re-run `Install-InOutWorker.ps1` from the new `windows-client/` on every workstation (see
[Deploy the Windows client](#deploy-the-windows-client)).

## Install with Helm

The chart is published as `oci://ghcr.io/winllc/charts/readyroom`, with the same version as the
ReadyRoom release. Each release download also contains it as `readyroom-helm-<version>.tgz`. The
chart's `README.md` lists every value.

### 1. Create the secrets

Keep passwords out of your values file by creating Secrets first:

```bash
kubectl create namespace readyroom
kubectl -n readyroom create secret generic readyroom-secrets \
    --from-literal=db-password='...' \
    --from-literal=ldap-password='...' \
    --from-literal=smtp-password='...'
```

Every secret value can also be given inline (`database.password`, `ldap.bindPassword`,
`smtp.password`, ...), and the chart then stores it in a Secret of its own. Secrets never go into a
ConfigMap.

### 2. Write your values

```yaml
# readyroom-values.yaml
publicUrl: https://readyroom.example.com

database:
  host: postgres.example.com
  name: readyroom
  username: readyroom
  existingSecret: readyroom-secrets
  existingSecretKey: db-password

ldap:
  host: dc1.example.com            # or url: ldaps://dc1.example.com:636
  bindDn: CN=readyroom-svc,OU=Service Accounts,DC=example,DC=com
  existingSecret: readyroom-secrets
  existingSecretKey: ldap-password
  userBaseDn: DC=example,DC=com
  userFilter: (&(objectCategory=person)(objectClass=user))   # Active Directory
  groupBaseDns:
    - OU=Groups,DC=example,DC=com

superUserDns:
  - CN=Jane Admin,OU=Users,DC=example,DC=com

smtp:
  host: smtp.example.com
  username: readyroom
  existingSecret: readyroom-secrets
  existingSecretKey: smtp-password
  from: readyroom@example.com

timezone: America/New_York
```

Write each DN on its own line, as above. In the flow form `[CN=a,OU=b]`, YAML splits a DN at its
commas.

| Value | Matches `.env` |
|---|---|
| `publicUrl` | `APPLICATION_BASE_URL` (defaults to the ingress or route host) |
| `database.*` | `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD` |
| `ldap.scheme` (`ldaps`), `ldap.host`, `ldap.port`, `ldap.url`, `ldap.bindDn` | `SPRING_LDAP_URLS`, `LDAP_USERNAME`. The port follows the scheme (636 or 389). `ldap.port: 389` with the default `ldaps` scheme fails the render: set `ldap.scheme: ldap` deliberately for plain LDAP |
| `ldap.userBaseDn`, `ldap.userFilter`, `ldap.groupBaseDns` | `APPLICATION_USERBASEDN`, `APPLICATION_USERLDAPFILTER`, `APPLICATION_GROUPS_n_GROUPSBASEDN` |
| `ldap.attributes.*` | `APPLICATION_USERLDAP*ATTRIBUTE`, `APPLICATION_MANAGERLDAPIDATTRIBUTE` |
| `superUserDns` | `APPLICATION_SUPERUSERDNS_n` |
| `orgChart.groupsBaseDn` | `APPLICATION_DUTYSUBORGGROUPSBASEDN` |
| `smtp.*` | `SPRING_MAIL_*`, `APPLICATION_NOTIFICATIONSENDEREMAIL`, `MANAGEMENT_HEALTH_MAIL_ENABLED` |
| `tls.*` | `SSL_ENABLED`, `SERVER_SSL_*` |
| `windowsAuth.*` | `APPLICATION_WINDOWSAUTH_*` |
| `audit.*` | `ENS_AUDIT_*` (see [Audit logging](../audit-logging.md)) |
| `csp.reportOnly`, `consentBanner.*`, `session.*` | `ENS_CSP_REPORT_ONLY`, `ENS_CONSENT_BANNER_*`, `ENS_SESSION_*` |
| `x509.*` | `ENS_X509_*` (see [Client certificates](#client-certificates-cacpiv)) |
| `database.migration.*` | `SPRING_FLYWAY_USER`, `SPRING_FLYWAY_PASSWORD` |
| `metrics.enabled` (`true`), `metrics.port` (`9090`), `metrics.serviceMonitor.*`, `metrics.allowFrom` | `MANAGEMENT_SERVER_PORT`: Prometheus and the probes on a port of their own |
| `logFormat` (`json`) | `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`; `text` for plain lines |
| `backup.*` | A database backup CronJob (off by default; see [Operations](operator-guide.md#backups)) |
| `events.requestStatuses`, `events.maxRangeDays` | `APPLICATION_EVENTS_REQUESTSTATUSES` (empty list: the default), `APPLICATION_EVENTS_MAXRANGEDAYS` |
| `privacy.coworkerVisibility` (`full`) | `APPLICATION_PRIVACY_COWORKERVISIBILITY`; the chart's schema refuses other values |
| `retention.eventsDays` (`0`), `retention.schedule` | `APPLICATION_RETENTION_EVENTSDAYS`, `APPLICATION_RETENTION_CRON`; the schema refuses an `eventsDays` between 1 and 30 |
| `bannerText`, `timezone`, `checkOutAfterMinutes`, `updateProfileUrl`, `logLevel`, `javaOpts` | The settings under "Other" |

Anything else from [Configuration](configuration.md) goes in `extraEnv`, as an environment variable
named the same way as in `.env`.

### 3. Install

```bash
helm install readyroom oci://ghcr.io/winllc/charts/readyroom --version 1.2.3 \
    -n readyroom -f readyroom-values.yaml
kubectl -n readyroom rollout status deploy/readyroom
```

The first start takes a minute or two while ReadyRoom creates its schema with Flyway; see
[Prepare PostgreSQL](#prepare-postgresql). The startup probe allows up to 10 minutes. Check the
release with `helm test readyroom -n readyroom`.

The chart runs one replica (`replicaCount: 1`) by default. Sessions, scheduled-job locks and the
license are kept in the database, so several replicas can run once the license volume is off (see
[License](#helm-license)); the chart's README, "Several replicas", has the values. While the volume is
mounted, the chart refuses more than one replica and updates with the `Recreate` strategy.

### 4. License { #helm-license }

Either upload the license on the license screen after the first start, or supply it at install
time:

```bash
helm install ... --set-file license.file=./license.lic
# or, from a Secret with the key license.lic:
kubectl -n readyroom create secret generic readyroom-license --from-file=license.lic
helm install ... --set license.existingSecret=readyroom-license
```

The chart passes it to ReadyRoom as `ENS_LICENSE`, which is installed only when no usable license
is installed yet, or when it is a newer revision. A license you upload later on the license screen
is never overwritten by `helm upgrade`.

ReadyRoom keeps the license and the installation id in its database: **back up the database**. The
license PersistentVolumeClaim (`readyroom-license`, mounted at `/var/lib/ens`, `persistence.license`)
is read only on the first start of a release that keeps them in the database, to import what an
earlier release kept there:

- **An upgrade** keeps the claim mounted for that start. After it, set
  `persistence.license.enabled: false` and `persistence.license.imported: true`; without `imported`,
  the chart refuses to turn the volume off while the claim exists.
- **A new installation** can start with the volume off, and set `license.newInstallationId: true`
  for its first start only. Without the volume the chart sets `ENS_LICENSE_EXPECT_INSTALLATION_ID=true`,
  so ReadyRoom never makes up a new installation id behind an existing installation's back.

### 5. Expose ReadyRoom

Choose how users and the Windows client reach ReadyRoom:

| Setup | Browsers | Client certificates and the Windows client |
|---|---|---|
| Ingress or route with edge TLS, `tls.enabled: false` | Username and password | No |
| TLS passthrough (ingress-nginx or route), `tls.enabled: true` | Certificate or password | Yes |
| `service.type: LoadBalancer`, `tls.enabled: true` | Certificate or password | Yes |

ReadyRoom does not accept a client certificate forwarded in an HTTP header. To use client
certificates, ReadyRoom must terminate TLS itself, so traffic must reach the pod unchanged.

=== "Ingress (browsers only)"

    ```yaml
    ingress:
      enabled: true
      className: nginx
      hosts:
        - host: readyroom.example.com
          paths:
            - path: /
              pathType: Prefix
      tls:
        - secretName: readyroom-ingress-tls
          hosts: [readyroom.example.com]
    ```

    With `ingress.tls` set (and no `ssl-passthrough` annotation), or a route with `edge` or
    `reencrypt` termination, the chart sets `ENS_COOKIE_SECURE=true` and
    `SERVER_FORWARD_HEADERS_STRATEGY=framework` itself.

=== "Ingress with TLS passthrough"

    The ingress-nginx controller must run with `--enable-ssl-passthrough`.

    ```yaml
    tls:
      enabled: true
      keystore:
        secretName: readyroom-tls
        existingSecret: readyroom-tls
      truststore:
        secretName: readyroom-tls
        existingSecret: readyroom-tls
    ingress:
      enabled: true
      className: nginx
      annotations:
        nginx.ingress.kubernetes.io/ssl-passthrough: "true"
        nginx.ingress.kubernetes.io/backend-protocol: HTTPS
      hosts:
        - host: readyroom.example.com
          paths:
            - path: /
              pathType: Prefix
    ```

=== "LoadBalancer"

    ```yaml
    tls:
      enabled: true
      keystore:
        secretName: readyroom-tls
        existingSecret: readyroom-tls
      truststore:
        secretName: readyroom-tls
        existingSecret: readyroom-tls
    service:
      type: LoadBalancer
      externalTrafficPolicy: Local
    ```

    ReadyRoom answers on `https://<address>:8181`.

=== "OpenShift"

    ```yaml
    openshift:
      enabled: true          # the restricted SCC assigns the user and group ids
    route:
      enabled: true
      host: readyroom.apps.example.com
      tls:
        termination: passthrough   # or edge, for browsers only (then tls.enabled: false)
    tls:
      enabled: true
      keystore:
        secretName: readyroom-tls
        existingSecret: readyroom-tls
      truststore:
        secretName: readyroom-tls
        existingSecret: readyroom-tls
    ```

See [Certificates](#certificates) for the `readyroom-tls` Secret. Keep `tls.clientAuth` at `want`:
the Kubernetes probes present no certificate, so `need` makes them fail.

### 6. Upgrade

Back up the database first. Then:

```bash
helm upgrade readyroom oci://ghcr.io/winllc/charts/readyroom --version 1.2.4 \
    -n readyroom -f readyroom-values.yaml
```

The license claim and the database are kept. The new pod migrates the schema as it starts (Flyway;
see the release package's [Upgrade](#6-upgrade) for the first upgrade to a release with Flyway). The
migration Job and the `migrations.enabled` value are gone.

!!! note "Notes for upgrades"
    - **To the release with the license in the database:** keep your values. The first start
      imports the license claim; then see [License](#helm-license) to turn it off. Everyone signs in
      again once.
    - **With `tls.enabled`:** set `tls.truststore.secretName` (or `tls.clientAuth: none`); the chart
      does not render without it.
    - **With `metrics.enabled`** (`true` by default): the probes move to the management port, 9090,
      and the log lines become JSON unless `logFormat: text`.
    - **To the next release:** client-certificate revocation is checked unless
      `x509.revocation.mode` is set. Empty, the new default, is `ocsp-then-crl`, soft-fail; values
      that set `mode` (including `"off"`) keep it, and a release that relied on the chart's old
      default `"off"` moves to checking. If your values set `x509.revocation.fail: closed` without a
      mode, that is kept too: make sure the pods reach your OCSP responders and CRLs (with
      `networkPolicy.enabled`, ports 80 and 443 are now opened for them) or set `crlConfigMap`.
      Single sign-on refuses an `http://` `sso.saml.idpMetadataUrl` or `sso.oidc.issuerUri`, and
      SAML an `http://` `publicUrl`, at startup. Rate limits are counted in the database (Flyway
      `V11`), so with `replicaCount` N a token gets its rate, not N times it.
      `webhooks.proxy.address` (with `webhooks.proxy.credentials`) sends webhooks through an egress
      proxy; unset, they connect directly. `sso.oidc.endSession` and `sso.saml.singleLogout` default
      to `false`, so signing out is unchanged until you turn them on.

### 7. Uninstall

```bash
helm uninstall readyroom -n readyroom
```

The license claim is kept (`helm.sh/resource-policy: keep`); delete it yourself once you no longer
want it. Your PostgreSQL database, which holds the license and the installation id, is not touched.
With the embedded database, its claim (`data-readyroom-postgresql-0`) is kept until you delete it.

### Air-gapped clusters

Get the chart from the release download (`readyroom-helm-<version>.tgz`), or on a connected machine:

```bash
helm pull oci://ghcr.io/winllc/charts/readyroom --version 1.2.3
```

Mirror the images into your registry, keeping their paths:

| Image | Mirror as |
|---|---|
| `ghcr.io/winllc/in-n-out-work:<version>` | `<registry>/winllc/in-n-out-work:<version>` |
| `postgres:16` (the embedded database and the backup CronJob) | `<registry>/postgres:16` |
| `busybox:1.36.1` (license init container) | `<registry>/busybox:1.36.1` |

The release package's `images/` archive holds the ReadyRoom and PostgreSQL images (`docker load` or
`podman load`, then tag and push). Then point the chart at your registry:

```yaml
global:
  imageRegistry: registry.example.internal:5000
imagePullSecrets:
  - name: mirror-pull
```

### Evaluation with the embedded database

```yaml
database:
  embedded:
    enabled: true
```

This runs PostgreSQL 16 in the release, with a generated password and a volume of its own.

!!! warning "Evaluation only"
    The embedded database is one pod with no backups. Use your own PostgreSQL server for
    production.

## Trying it out: the demo

The ReadyRoom source repository has a `docker-compose.demo.yml` at its root. It builds ReadyRoom from
source and runs it in read-only demo mode, with its own PostgreSQL and OpenLDAP, seeded mock users
and groups, and check-in history. The login page lists the seeded accounts to sign in as, one per
role.

!!! warning "Not for production"
    The demo's credentials are on its login page, so anything those accounts can see is readable by
    anyone who reaches it. Keep only the mock data in it.

From the next release, demo mode (`demo.enabled`) does not run the scheduled jobs that write
attendance data: the directory sync, auto check-out, absence alerts and scheduled reports (each logs
once that it is skipping), and the retention purge. Webhooks have nothing to send. The jobs that
look after the server's own state keep running: the audit trail (and its retention), the clean-up
of ended browser sessions, the license mode check and the service heartbeat. Demo mode and Windows
sign-in (`application.windows-auth.enabled`) cannot both be on: startup stops, naming both
settings. Signing in still creates the account's `user_record` row on first request.

1. Download an evaluation license for ReadyRoom from the ENS licensing portal and save it as
   `license/license.lic` in the checkout **before the first start**: the demo reads it then only. The
   demo is read-only, so its license screen refuses uploads; for a license added later, reset the demo
   with `down -v` and start it again.
2. Start it. Use `-p readyroom-demo`: without it, the demo would share its project, network and
   volumes with the repository's other compose file.

    === "Docker"

        ```bash
        docker compose -f docker-compose.demo.yml -p readyroom-demo up --build
        ```

    === "Podman"

        ```bash
        podman-compose -f docker-compose.demo.yml -p readyroom-demo up --build
        ```

3. Reach it. The demo **publishes no ports**: ReadyRoom listens on 8181 inside the demo network
   only. Either attach a reverse proxy to the `readyroom-demo_demo` network and point it at
   `app:8181`, or write an override file bound to `127.0.0.1`:

    ```yaml
    # demo-ports.yml
    services:
      app:
        ports: ["127.0.0.1:8181:8181"]
    ```

    ```bash
    docker compose -f docker-compose.demo.yml -f demo-ports.yml -p readyroom-demo up --build
    ```

    Then open <http://localhost:8181>.

To reset it, `docker compose -f docker-compose.demo.yml -p readyroom-demo down -v` deletes its
volumes, and the next `up` seeds from scratch. If it comes up with no data, read the seeders' logs:
`docker compose -f docker-compose.demo.yml -p readyroom-demo logs demo-seed-ldap demo-seed-db`.

## Prepare PostgreSQL

With the release package, PostgreSQL (16) is already set up. With Helm, use your own server.

1. **Create a database and an account that owns it** (Helm, with your own server):

    ```sql
    CREATE ROLE readyroom LOGIN PASSWORD 'change-me';
    CREATE DATABASE readyroom OWNER readyroom;
    ```

2. **Start ReadyRoom.** It creates and migrates its own schema with Flyway when it starts, before it
   serves anything. The migrations ship inside the application and run in version order, each once
   per database, recorded in `flyway_schema_history`. Hibernate only checks the result
   (`ddl-auto: validate`): ReadyRoom does not start against a schema that does not match. There is
   no script to run and no migration Job. The tables:

    | Table | Holds |
    |---|---|
    | `check_in_out_records` | Every check-in, lock, unlock and check-out event, with the session id (which no page or API shows, apart from the check-in's own answer) and the user's organization attributes at that moment |
    | `user_records` | One row per user DN, unique regardless of letter case: role, notes, favorite groups, delegates (and whether they await review), preferred and average login time, and directory metadata |
    | `user_event_records` | Per-day statuses (work from home, leave, late arrival, and so on) |
    | `status_requests` | Requests for statuses that need approval, with their notes and decisions |
    | `attendance_summaries`, `attendance_summary_statuses`, `retention_state` | Monthly summaries of purged check-ins and statuses, and the retention job's position |
    | `notification_records` | Absence alerts and managers' responses |
    | `permission_records` | Per-user group grants |
    | `group_records` | Alternate managers assigned to a group |
    | `global_calendar_records` | Organization calendar events and holidays |
    | `org_parse_rules` | Org-chart parse rules |
    | `audit_events`, `audit_chain_head`, `audit_output_state`, `audit_api_tokens` | The audit trail (`ens-audit/1`, append-only, enforced by a trigger), the chain's newest sequence number and hash, how far each audit output has delivered, and the pull API's tokens |
    | `flyway_schema_history` | The migrations applied |

    If a migration fails, ReadyRoom does not start and nothing of that migration is applied: each runs
    in one transaction. Fix the cause the log names and start it again.

3. **Optional: a separate migration account.** By default ReadyRoom migrates with its own account
   (`DB_USERNAME`), which therefore owns the schema. To limit that account to reading and writing
   rows, let a second account own and migrate the schema: `SPRING_FLYWAY_USER` and
   `SPRING_FLYWAY_PASSWORD` (Helm: `database.migration.username` and `.password` or
   `.existingSecret`). Create the database owned by the migration account, then, connected as the
   migration account, before ReadyRoom first starts:

    ```sql
    REVOKE CREATE ON SCHEMA public FROM PUBLIC;            -- PostgreSQL 14 and older; 15+ already does this
    GRANT USAGE ON SCHEMA public TO readyroom;
    ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO readyroom;
    ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO readyroom;
    ```

    For an existing database whose tables the application's account owns, first hand them over as a
    superuser (`REASSIGN OWNED BY readyroom TO readyroom_owner;`), then run the statements above and
    `GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO readyroom;` and
    `GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO readyroom;`.

## Connect the LDAP directory

Set the connection and describe your directory:

| Setting | `.env` | Helm value |
|---|---|---|
| Server | `SPRING_LDAP_URLS=ldaps://ldap.example.com:636` (the template's default), or `LDAP_HOST` and `LDAP_PORT` for plain LDAP | `ldap.host` with `ldap.scheme` (`ldaps` by default), or `ldap.url` |
| Bind account | `LDAP_USERNAME`, `LDAP_PASSWORD` | `ldap.bindDn`, `ldap.bindPassword` or `ldap.existingSecret` |
| Users | `APPLICATION_USERBASEDN`, `APPLICATION_USERLDAPFILTER` | `ldap.userBaseDn`, `ldap.userFilter` |
| Group trees | `APPLICATION_GROUPS_0_GROUPSBASEDN`, `_1_`, ... | `ldap.groupBaseDns` |
| Administrators | `APPLICATION_SUPERUSERDNS_0`, `_1`, ... | `superUserDns` |
| Attributes | `APPLICATION_USERLDAPORGANIZATIONATTRIBUTE` (`o`), `...LOCATIONATTRIBUTE` (`l`), `...EMPLOYEETYPEATTRIBUTE` (`employeeType`), `...DUTYSUBORGANIZATIONATTRIBUTE` (`departmentNumber`), `APPLICATION_USERLDAPMANAGERIDATTRIBUTE` (`title`), `APPLICATION_MANAGERLDAPIDATTRIBUTE` (`street`) | `ldap.attributes.*` |
| Org chart | `APPLICATION_DUTYSUBORGGROUPSBASEDN` | `orgChart.groupsBaseDn` |

LDAPS is the default. The directory's certificate must be trusted by the JVM; a certificate from a
public CA is. For a private CA there is no chart or release-package setting: give the JVM a trust
store that holds every CA it must trust, with `JAVA_OPTS` (Helm: `javaOpts`, and the file through
`extraVolumes` and `extraVolumeMounts`), for example
`-Djavax.net.ssl.trustStore=/path/truststore.p12 -Djavax.net.ssl.trustStorePassword=...`. Keep
the default `-XX:MaxRAMPercentage=75.0` when you set it. Password sign-in over `ldap://` without
StartTLS sends passwords in clear text, and ReadyRoom logs a warning at startup.

Things to check in the directory:

- **The user's DN must match how they sign in.** A client certificate's full subject DN is used as
  the username and looked up directly as an LDAP DN. If certificate subjects do not equal directory
  DNs, set `lookup-on-dn-attribute: true` and `user-dn-attribute` to an attribute whose value is the
  certificate subject (`APPLICATION_LOOKUPONDNATTRIBUTE` and `APPLICATION_USERDNATTRIBUTE`; with
  Helm, in `extraEnv`). ReadyRoom then searches for that attribute under the user base DN instead.
- **Form login** finds the user under the user base DN by `uid`, `cn` or `mail` (the filter is
  `(|(uid={0})(cn={0})(mail={0}))`) and then binds with the password the user typed.
- **Groups** are found under each group base DN as `groupOfUniqueNames` entries. Members come from
  `uniqueMember`, and the group's directory manager comes from `owner`.
- **Reporting lines.** A user reports to the manager whose manager-id attribute (`street`) equals
  the user's own manager-id attribute (`title`).
- **The org chart** is built from the distinct values of the duty sub-organization attribute,
  optionally narrowed by `duty-sub-org-filter`. Leave the org chart's group base DN empty to turn
  it off.
- **Timeouts and paging.** The packaged configuration sets a 5 s connect timeout and a 30 s read
  timeout. Large searches are paged at `application.ldap.page-size` (500), which is below the
  default size limits of Active Directory (1000) and OpenLDAP (500).

## Certificates

By default ReadyRoom serves plain HTTP on port 8181. That is enough for browsers with username and
password sign-in behind a proxy, ingress or route that terminates TLS. With TLS turned on, ReadyRoom
serves HTTPS on the same port and **requests, but does not require,** a client certificate
(`client-auth: want`). A browser or the Windows client that presents a certificate is signed in
with it. One that does not is sent to the login form.

1. **Server keystore.** Create a PKCS#12 file holding the server's private key and certificate chain.
   The certificate's subject alternative name must match the host name that browsers and the
   Windows client's `BaseUrl` use. If you plan to turn on Windows sign-in, that is also the name
   registered as the service principal.
2. **Truststore.** Create a PKCS#12 truststore holding only the CA certificate(s) that issue user
   certificates. Only certificates that chain to these CAs are accepted, and any certificate they
   issue with a user's DN signs in as that user. Both files are required with TLS on: ReadyRoom
   contains no keystore or truststore.
3. **User certificates.** Issue each user a client certificate whose subject DN equals their LDAP
   DN (or set up `lookup-on-dn-attribute`, described above). Install it with its private key in
   the user's `CurrentUser\My` store on the workstation.
4. **Give ReadyRoom the files.**

    === "Release package"

        Copy them to `tls/keystore.p12` and `tls/truststore.p12`, make them readable by group 0
        (see [Fill in `.env`](#2-fill-in-env)), and set in `.env`:

        ```bash
        COMPOSE_FILE=docker-compose.yml:docker-compose.tls.yml
        SSL_ENABLED=true
        SERVER_SSL_KEYSTOREPASSWORD=...
        SERVER_SSL_TRUSTSTOREPASSWORD=...
        APPLICATION_BASE_URL=https://readyroom.example.com:8181
        ```

        Then `./compose.sh up -d`.

    === "Helm"

        ```bash
        kubectl -n readyroom create secret generic readyroom-tls \
            --from-file=keystore.p12 --from-file=truststore.p12 \
            --from-literal=keystore-password='...' --from-literal=truststore-password='...'
        ```

        ```yaml
        tls:
          enabled: true
          keystore:
            secretName: readyroom-tls          # the file
            existingSecret: readyroom-tls      # the password (key keystore-password)
          truststore:
            secretName: readyroom-tls
            existingSecret: readyroom-tls
        ```

        Then make TLS reach the pod unchanged: see [Expose ReadyRoom](#5-expose-readyroom).

### Client certificates (CAC/PIV)

Client-certificate sign-in is on whenever ReadyRoom terminates TLS and asks for certificates
(`SSL_ENABLED=true`, `SERVER_SSL_CLIENTAUTH=want`), or whenever certificates come from a proxy
(`ENS_X509_SOURCE=header`). The browser pages and the Windows client's calls apply the same rules.
Each refusal is audited as a failed `auth.login` (`details.method` `x509`) with its reason, for
example `certificate revoked`, `revocation unknown`, `issuer not allowed`,
`no single directory user matches`, `proxy secret missing or wrong` or
`certificate not issued by a trusted CA`.

**Who a certificate signs in as.** By default its subject DN must be the user's directory DN. With
`ENS_X509_USER_FILTER` (Helm: `x509.userFilter`), ReadyRoom searches for the user with an LDAP filter
under `user-base-dn`, together with `user-ldap-filter`; exactly one entry must match, or nobody signs
in. Values are LDAP-filter-escaped.

| Placeholder | Value |
|---|---|
| `{upn}` | The subject alternative name otherName UserPrincipalName (OID 1.3.6.1.4.1.311.20.2.3) |
| `{edipi}` | The DoD EDIPI: the digits before `@` of a UPN `1234567890@mil`, or else the trailing `.NNNNNNNNNN` of the CN (`LAST.FIRST.MI.1234567890`) |
| `{email}` | The rfc822Name subject alternative name, or else the subject's emailAddress |
| `{uuid}` | The UUID of a `urn:uuid:` URI subject alternative name (the PIV FASC-N UUID), without `urn:uuid:` |
| `{subject}`, `{cn}` | The subject DN (RFC 2253), and its most specific CN |

For example `(userPrincipalName={upn})` for Active Directory and CAC, or
`(|(employeeNumber={edipi})(mail={email}))`.

| Env var | Default | Helm | Purpose |
|---|---|---|---|
| `ENS_X509_USER_FILTER` | none: the subject DN is the directory DN | `x509.userFilter` | The LDAP filter template that finds the user |
| `ENS_X509_ALLOWED_ISSUERS` | none: any trusted CA | `x509.allowedIssuers` | Issuer DNs whose certificates may sign in, `;` separated; others are refused even when a trusted CA chains to them |
| `ENS_X509_REVOCATION` | `off`; from the next release unset, meaning `ocsp-then-crl`, soft-fail | `x509.revocation.mode` (`"off"`; from the next release `""`) | `off`, `ocsp`, `crl` or `ocsp-then-crl`. With certificates in use and revocation `off`, startup logs that DISA STIG requires it. See [Revocation from the next release](#revocation-from-the-next-release) |
| `ENS_X509_OCSP_RESPONDER_URL` | the certificate's AIA | `x509.revocation.ocspResponderUrl` | A responder to ask instead, for example a local OCSP relay |
| `ENS_X509_CRL_DIR` | none | `x509.revocation.crlConfigMap` | A directory of CRL files (DER or PEM), re-read when they change. With it set, distribution points are never fetched: the air-gapped option |
| `ENS_X509_REVOCATION_FAIL` | `closed`; from the next release unset: `open` while `ENS_X509_REVOCATION` is unset, `closed` once a mode is set | `x509.revocation.fail` (`closed`; from the next release `""`) | `closed` refuses a certificate whose status can't be determined; `open` allows it with a warning |
| `ENS_X509_REVOCATION_CACHE` | `10m` | `x509.revocation.cache` | How long a good or revoked answer is kept, per issuer and serial |

#### Revocation from the next release

From the next release, revocation is checked by default. With `ENS_X509_REVOCATION` unset (the Helm
chart's `x509.revocation.mode` defaults to `""`, and the package's `env.template` leaves it commented
out), ReadyRoom asks OCSP and then the CRL (`ocsp-then-crl`) for every certificate sign-in,
**soft-fail**:

- a certificate its CA has revoked is refused (audited as `certificate revoked`);
- one that no OCSP responder or CRL answers for (an unreachable responder, an air-gapped site
  without `ENS_X509_CRL_DIR`) still signs in, with a WARN naming it.

Setting `ENS_X509_REVOCATION` explicitly keeps its meaning: `off` turns checking off (startup warns
that STIG requires it), and a chosen mode **fails closed** unless `ENS_X509_REVOCATION_FAIL=open`.
`ENS_X509_REVOCATION_FAIL` set to `closed` without a mode is honoured too. For DISA STIG, set
`ENS_X509_REVOCATION=ocsp-then-crl` (or `ocsp`, `crl`) with `ENS_X509_REVOCATION_FAIL=closed`.

!!! warning "Air-gapped sites"
    Without a reachable responder or `ENS_X509_CRL_DIR`, the soft-fail default lets certificates
    sign in unchecked, with a WARN each time. Where a firewall silently drops the traffic, a
    certificate sign-in can also wait up to the 5 s connect timeout per responder and distribution
    point before it is allowed (an unknown answer is not cached). Drop the CAs' CRLs into
    `ENS_X509_CRL_DIR` (Helm `x509.revocation.crlConfigMap`), point `ENS_X509_OCSP_RESPONDER_URL`
    at a local responder, or set `ENS_X509_REVOCATION=off` to avoid that.

With the Helm chart's NetworkPolicy on and certificate sign-in on, egress to ports 80 and 443 is
opened for OCSP and CRLs unless the mode is `off` (or `crl` with `crlConfigMap`).

Network fetches for revocation time out after 5 s (connect) and 10 s (response), follow no
redirects, are capped at 1 MB (OCSP) and 20 MB (CRL), and never go to a link-local or cloud metadata
address. The JDK's PKIX validator judges every answer, configured for that one check. The issuer's
certificate must be known: in the chain the client sent, in `ENS_X509_TRUSTED_CA_FILE`, or in the TLS
trust store.

**Certificates from a TLS-terminating proxy** (`ENS_X509_SOURCE=header`). The proxy (an ingress or a
load balancer) verifies the client certificate and forwards it as URL-encoded PEM, as nginx's
`$ssl_client_escaped_cert` sends it, in `ENS_X509_HEADER`, together with a shared secret in
`X-ENS-Proxy-Secret`. ReadyRoom takes the certificate only from an address in
`ENS_X509_TRUSTED_PROXIES` with the right secret, compared in constant time, checks that it chains to
`ENS_X509_TRUSTED_CA_FILE` (or the TLS trust store) and is valid, and otherwise ignores it and audits
the refusal. A certificate on the connection itself is not used in this mode. ReadyRoom refuses to
start in header mode without a secret of 32 characters or more, or without a CA; the Helm chart
refuses to render likewise. Let nothing but the proxy reach ReadyRoom's port.

| Env var | Default | Helm | Purpose |
|---|---|---|---|
| `ENS_X509_SOURCE` | `servlet` | `x509.source` | `servlet` (ReadyRoom terminates TLS) or `header` |
| `ENS_X509_HEADER` | `X-SSL-Client-Cert` | `x509.header` | The header the certificate arrives in (ingress-nginx: `ssl-client-cert`) |
| `ENS_X509_TRUSTED_PROXIES` | `127.0.0.1/32;::1/128` | `x509.trustedProxies` (`10.0.0.0/8;172.16.0.0/12;192.168.0.0/16`) | Addresses (CIDRs) allowed to send it, `;` or `,` separated |
| `ENS_X509_PROXY_SECRET_FILE` / `ENS_X509_PROXY_SECRET` | none | `x509.proxySecret.*` | The secret the proxy sends, 32 characters or more; the file wins. Required in header mode |
| `ENS_X509_TRUSTED_CA_FILE` | none | `x509.trustedCa.*` | PEM certificates of the CAs that issue user certificates: header mode's chain check (required there unless the TLS trust store is configured), and issuers for revocation |

For nginx in front of ReadyRoom:

```nginx
ssl_client_certificate /etc/nginx/user-cas.pem;
ssl_verify_client optional;
proxy_set_header X-SSL-Client-Cert $ssl_client_escaped_cert;
proxy_set_header X-ENS-Proxy-Secret "<the secret>";
```

!!! warning "Header mode with ingress-nginx and OpenShift"
    - **ingress-nginx:** the proxy secret header is added with a `configuration-snippet` annotation,
      which ingress-nginx disables by default since version 1.9. Allow snippets on your controller,
      or use another proxy.
    - **OpenShift:** an edge route does not forward client certificates, so header mode needs a
      separate proxy. Servlet mode with a passthrough route works.

## Configure email

Absence alerts are sent through Spring Mail. Set the SMTP host, port, username and password, the
From address, and the public URL, which becomes the link in each email. See
[Configuration](configuration.md#email-and-notifications).

| Setting | `.env` | Helm value |
|---|---|---|
| Server | `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT` | `smtp.host`, `smtp.port` |
| Account | `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD` | `smtp.username`, `smtp.password` or `smtp.existingSecret` |
| AUTH and STARTTLS (on by default) | `SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH`, `..._STARTTLS_ENABLE` | `smtp.auth`, `smtp.starttls` |
| Trust a mail server's certificate without checking it (opt-in; unset by default) | `SPRING_MAIL_PROPERTIES_MAIL_SMTP_SSL_TRUST` | `smtp.sslTrust` |
| From address | `APPLICATION_NOTIFICATIONSENDEREMAIL` | `smtp.from` |
| Link in each email | `APPLICATION_BASE_URL` | `publicUrl` |

No mail server is packaged. Without `SPRING_MAIL_HOST`, absence alerts are recorded but not
emailed, and startup logs a warning. With it, `APPLICATION_NOTIFICATIONSENDEREMAIL` is required.
STARTTLS is required by default: nothing, credentials included, is sent to a server that doesn't
offer it. The mail server's certificate is checked against the JVM's trust store; earlier releases
trusted any certificate. Sends time out after 30 seconds.

!!! note "Health and mail"
    The aggregate `/actuator/health` includes the mail server. If it cannot be reached, ReadyRoom
    reports `DOWN` and the release package's container shows as unhealthy, although everything
    except email works. Set `MANAGEMENT_HEALTH_MAIL_ENABLED=false` (Helm: `smtp.healthCheck: false`)
    if you do not use email. The liveness and readiness groups never include the mail server; the
    Helm chart's probes use them, on the management port (9090) while `metrics.enabled` is `true`.

Set the time zone deliberately. `APPLICATION_TIMEZONE` sets the JVM default time zone, which
decides what "today" means for every page and job, and `SPRING_JACKSON_TIMEZONE` the zone dates are
shown in. Set both to the same zone in `.env`; the Helm value `timezone` sets both. See
[Configuration](configuration.md#time-zone).

## Windows (Kerberos) sign-in

Optional. If workstations should check in with the user's Windows logon instead of, or as well as,
a certificate, follow [Security: Windows sign-in](security.md#windows-kerberos-sign-in). It needs a
service account, an SPN and a keytab from a domain admin. Treat the keytab like a password: never
bake it into an image or put it in a ConfigMap.

=== "Release package"

    Put `krb5.conf` and `http.keytab` in `kerberos/` (the keytab readable by group 0, as in [Fill in `.env`](#2-fill-in-env)), and set in
    `.env`:

    ```bash
    COMPOSE_FILE=docker-compose.yml:docker-compose.tls.yml:docker-compose.windows-auth.yml
    APPLICATION_WINDOWSAUTH_ENABLED=true
    APPLICATION_WINDOWSAUTH_SERVICEPRINCIPAL=HTTP/readyroom.example.com@EXAMPLE.COM
    ```

=== "Helm"

    ```bash
    kubectl -n readyroom create secret generic readyroom-keytab --from-file=http.keytab
    ```

    ```yaml
    windowsAuth:
      enabled: true
      servicePrincipal: HTTP/readyroom.example.com@EXAMPLE.COM
      keytab:
        secretName: readyroom-keytab
      krb5Conf: |                  # optional: checking a ticket needs only the keytab
        [libdefaults]
          default_realm = EXAMPLE.COM
    ```

Check the keytab inside the container with `klist -kte /etc/in-n-out/http.keytab`
(`./compose.sh exec app ...` or `kubectl -n readyroom exec deploy/readyroom -- ...`). The server's
clock must be within 5 minutes of the domain controllers'.

## Deploy the Windows client

The release package contains the client in `windows-client/`. Copy that folder to each workstation
(or to a share). From an **elevated** PowerShell prompt in it:

```powershell
.\Install-InOutWorker.ps1 -BaseUrl "https://readyroom.example.com:8181/api/check"
# or sign in with the Windows logon, falling back to the certificate:
.\Install-InOutWorker.ps1 -BaseUrl "https://readyroom.example.com:8181/api/check" -Authentication Auto
```

`BaseUrl` is ReadyRoom's HTTPS address with TLS on: port 8181 with the release package or a Helm
`LoadBalancer` Service, or the passthrough ingress or route host (`https://readyroom.example.com/api/check`).
Its host name must match the server certificate, and the SPN for Windows sign-in.

The installer:

1. copies `Update-WorkStatus.ps1` to `C:\Program Files\InOutWorker`, writes
   `inoutworker.config.json` there (only if you passed settings), and makes the folder writable by
   SYSTEM and Administrators only;
2. prepares `%ProgramData%\InOutWorker` with its `logs\` and `sessions\` folders, writable by SYSTEM
   and Administrators only, except that each user may add and rewrite their own session file;
3. registers five hidden tasks under `\InOutWork\`: **Login**, **Lock**, **Unlock**, **Logout** and
   **Disconnect**.

Re-run it on every workstation after each upgrade: without arguments it keeps the configuration,
and it corrects the permissions an older client left on `%ProgramData%\InOutWorker`.

For mass deployment, configuration files, parameters and what each task does, see
[Windows client](windows-client.md).

## First start checklist

- [ ] The license screen, `<public URL>/app/admin/license`, shows a valid license. Sign in as an
      administrator (`superUserDns` / `APPLICATION_SUPERUSERDNS_*`) to open it. It also shows this
      installation's id (for example `RR-7F3K-29QD`), which you enter on the ENS licensing portal to
      bind a license. See [Licensing](../licensing.md).
- [ ] The database is in your backups: it holds the license and the installation id.
- [ ] The schema is migrated: the log shows `Successfully applied 1 migration` on the first start
      (or `Schema "public" is up to date` later).
- [ ] Backups are scheduled: `./backup.sh` from cron (release package), or `backup.enabled` and
      license-volume snapshots (Helm). See [Operations](operator-guide.md#backups).
- [ ] For DoD systems: the consent banner is on (`ENS_CONSENT_BANNER_ENABLED=true`), and, with
      certificates in use, revocation checking (`ENS_X509_REVOCATION`).
- [ ] Audit events reach your SIEM, if you configured one. See [Audit logging](../audit-logging.md).

## Verify the installation

- [ ] The probes return `{"status":"UP"}`: `/actuator/health/liveness` and
      `/actuator/health/readiness` (readiness covers the database, the directory and the audit
      store), on port 8181, or on `MANAGEMENT_SERVER_PORT` when it is set (Helm: 9090, through
      `kubectl port-forward`). For example
      `curl -k https://readyroom.example.com:8181/actuator/health/readiness`, or
      `helm test readyroom -n readyroom` (Helm).
- [ ] The startup log (`./compose.sh logs app`, or `kubectl -n readyroom logs deploy/readyroom`)
      shows `Default time zone set to ...` with the zone you expect, and
      `LDAP connection pooling enabled`.
- [ ] Browsing to ReadyRoom with a client certificate opens **Home**. Without one, the **Sign in**
      page accepts directory credentials.
- [ ] A super user sees **Metrics** and **Settings** in the sidebar.
- [ ] **Groups** lists the trees under each configured group base DN.
- [ ] On a workstation, `& 'C:\Program Files\InOutWorker\Update-WorkStatus.ps1' -Action login -WhatIf`
      reports the URL and the sign-in method it would use.
- [ ] `Get-ScheduledTask -TaskPath "\InOutWork\"` lists five tasks.
- [ ] After a real logon, `%LOCALAPPDATA%\InOutWorker\logs\inoutworker-<yyyyMMdd>.log` in the
      user's profile contains `Server accepted 'login'`, and the user's status on **Home** is `IN`.
- [ ] After a logoff, `%ProgramData%\InOutWorker\logs\inoutworker-<yyyyMMdd>.log` contains
      `Server accepted 'logout'`.
- [ ] After the first scheduled directory refresh (5 minutes after startup by default), the log
      shows `Directory refresh: N scanned, N created, ...` when it created or updated user records.
      If nothing changed, the line is logged only at `debug`.
