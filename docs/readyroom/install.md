# Installation

This page covers a full ReadyRoom deployment: the server and its PostgreSQL database, the LDAP
connection, TLS and client certificates, email, Windows sign-in, and the Windows client on each
workstation. You install the server in one of two ways: the **release package** (Docker Compose on
one host) or the **Helm chart** (Kubernetes or OpenShift). Every setting mentioned here is described
in [Configuration](configuration.md).

!!! danger "Use your own key material"
    The application ships with development keystores (`winhal.p12`, `truststore.p12`) and their
    passwords as built-in defaults, so it can start with TLS and nothing configured. Treat them as
    compromised. For any real deployment, supply your own server keystore and client-certificate
    truststore, as described in [Certificates](#certificates).

## Prerequisites

### Common to both methods

| Requirement | Notes |
|---|---|
| PostgreSQL | ReadyRoom keeps its own records in PostgreSQL; it is the only database supported. The release package includes PostgreSQL 16. With Helm, use your own server (an embedded one is available for evaluation only). |
| LDAP directory | Must hold the people (by default `objectclass=inetOrgPerson`) and the groups (`groupOfUniqueNames` with `uniqueMember`; an optional `owner` is treated as the group's manager). Needs a read-only bind account that can search users and groups. The paged results control is used if it is available. Active Directory works. |
| SMTP server | Used to email absence alerts. Optional: see [Configure email](#configure-email). |
| License | An ENS Solutions license file (`.lic`) from your account on the ENS licensing portal: an evaluation, or the license issued for your order. See [Licensing](../licensing.md). Without one, ReadyRoom offers only sign-in and its license screen. |
| Server certificate | A PKCS#12 keystore for the HTTPS listener, if ReadyRoom terminates TLS itself. Needed for client certificates and the Windows client. |
| Client certificate trust | A PKCS#12 truststore holding the CA(s) that issue the users' client certificates. |
| Windows workstations | Windows PowerShell 5.1 and Task Scheduler. Each user needs either a client certificate in `CurrentUser\My` or, with Windows sign-in turned on, a domain account on a domain-joined PC. |

### For the release package

- A Linux host with Docker Engine 24 or later and the Docker Compose plugin (`docker compose`).
- 2 CPUs, 4 GB RAM and 10 GB disk.
- No internet access is needed: the container images are in the package.

### For the Helm chart

- Kubernetes 1.25 or later, or OpenShift 4, and Helm 3.
- A storage class for a small `ReadWriteOnce` volume (the license, 100 Mi).
- Access to `ghcr.io/winllc`, or a registry mirror (see [Air-gapped clusters](#air-gapped-clusters)).
- For client certificates and the Windows client: an ingress controller that supports TLS
  passthrough (ingress-nginx with `--enable-ssl-passthrough`, or an OpenShift route), or a
  `LoadBalancer` Service.

## Choosing a method

| | Release package | Helm chart |
|---|---|---|
| Runs on | One Linux host with Docker | Kubernetes or OpenShift |
| Database | Bundled PostgreSQL 16 container | Your PostgreSQL (embedded one for evaluation only) |
| Air-gapped | Yes, images included | Yes, with a registry mirror |
| Configuration | `.env` file | Helm values |
| Database scripts | Run by hand when release notes say so | Run by a Job on every install and upgrade |
| Upgrade | `upgrade.sh` | `helm upgrade` |

## Install from the release package

### 1. Download and verify

ENS Solutions sends you links to the release files. Check the signature and checksums before you
install, as described in [Releases and downloads](../releases.md):

```bash
gpg --verify SHA256SUMS.asc SHA256SUMS
sha256sum -c SHA256SUMS --ignore-missing
```

### 2. Unpack and run `install.sh`

```bash
tar xzf readyroom-1.2.3.tar.gz
cd readyroom-1.2.3
./install.sh
```

The first run loads the images from `images/`, creates `.env` from `env.template` with a generated
database password, creates the `license/`, `tls/` and `kerberos/` directories, and lists the lines
you must edit. It then stops.

### 3. Fill in `.env`

Replace every `CHANGE_ME`:

| Setting | What |
|---|---|
| `APPLICATION_BASE_URL` | The address users reach ReadyRoom at. Absence emails link to it. |
| `LDAP_HOST`, `LDAP_PORT`, `LDAP_USERNAME`, `LDAP_PASSWORD` | Your directory and the service account. For LDAPS, set `SPRING_LDAP_URLS=ldaps://...` instead of host and port. |
| `APPLICATION_USERBASEDN`, `APPLICATION_GROUPS_0_GROUPSBASEDN` | Where users and group trees are. |
| `APPLICATION_SUPERUSERDNS_0` | At least one administrator, by full DN. |
| `SPRING_MAIL_*`, `APPLICATION_NOTIFICATIONSENDEREMAIL` | Your SMTP server and the From address. |

`.env` holds the database, directory and mail passwords. `install.sh` makes it mode 600; keep it
private. List settings are numbered (`APPLICATION_SUPERUSERDNS_0`, `_1`, ...). Any other setting
from [Configuration](configuration.md) can be added as an environment variable: the property name
upper-cased, with dots and dashes dropped or turned into underscores
(`application.check-out-after-minutes` becomes `APPLICATION_CHECKOUTAFTERMINUTES`).

The optional pieces are switched on with `COMPOSE_FILE` in `.env`:

| File | Turns on | Files it needs |
|---|---|---|
| `docker-compose.tls.yml` | TLS with your own keystore, and client certificates | `tls/keystore.p12`, `tls/truststore.p12` |
| `docker-compose.windows-auth.yml` | Windows (Kerberos) sign-in | `kerberos/krb5.conf`, `kerberos/http.keytab` |

```bash
COMPOSE_FILE=docker-compose.yml:docker-compose.tls.yml:docker-compose.windows-auth.yml
```

The container runs as user 185, group 0. Make the files in `tls/` and `kerberos/` readable by
group 0: `chgrp 0 <file> && chmod 0640 <file>`.

### 4. License

Optionally save the license as `license/license.lic`. `install.sh` copies it into the license volume
when that volume holds no license yet. You can also upload it on the license screen after the first
start.

### 5. First start

Run `./install.sh` again. It starts ReadyRoom with `docker compose up -d`, and copies the license if
you supplied one. The first start takes a minute or two while ReadyRoom creates its tables.

```bash
docker compose ps                 # app becomes "healthy"
docker compose logs -f app        # application log
```

Then run the database script once the tables exist; see [Prepare PostgreSQL](#prepare-postgresql).

What is kept where:

| Docker volume | Holds |
|---|---|
| `readyroom_postgres_data` | The database |
| `readyroom_ens-license` | The license, the installation id and the licensing state |

!!! warning "Never run `docker compose down -v`"
    It deletes both volumes. Without the license volume the installation gets a new id, and a license
    bound to the old one stops working.

`install.sh` can be run again at any time. It never overwrites `.env`, the database or an installed
license.

### 6. Upgrade

Back up the database, then unpack the new release next to the current one and run its `upgrade.sh`,
naming the current directory:

```bash
docker compose exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" "$POSTGRES_DB"' > readyroom-backup.sql

tar xzf readyroom-1.2.4.tar.gz
cd readyroom-1.2.4
./upgrade.sh ../readyroom-1.2.3
```

`upgrade.sh` copies `.env`, `license/`, `tls/` and `kerberos/` across without overwriting anything,
loads the new images, lists any new settings in `env.template` that your `.env` does not mention,
and recreates the containers. The database and license volumes are kept, and ReadyRoom updates its
schema on start. Run any script from `migrations/` that the release notes name. Run ReadyRoom from
the new directory from then on.

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
| `ldap.host`, `ldap.port`, `ldap.url`, `ldap.bindDn` | `LDAP_HOST`, `LDAP_PORT`, `SPRING_LDAP_URLS`, `LDAP_USERNAME` |
| `ldap.userBaseDn`, `ldap.userFilter`, `ldap.groupBaseDns` | `APPLICATION_USERBASEDN`, `APPLICATION_USERLDAPFILTER`, `APPLICATION_GROUPS_n_GROUPSBASEDN` |
| `ldap.attributes.*` | `APPLICATION_USERLDAP*ATTRIBUTE`, `APPLICATION_MANAGERLDAPIDATTRIBUTE` |
| `superUserDns` | `APPLICATION_SUPERUSERDNS_n` |
| `orgChart.groupsBaseDn` | `APPLICATION_DUTYSUBORGGROUPSBASEDN` |
| `smtp.*` | `SPRING_MAIL_*`, `APPLICATION_NOTIFICATIONSENDEREMAIL`, `MANAGEMENT_HEALTH_MAIL_ENABLED` |
| `tls.*` | `SSL_ENABLED`, `SERVER_SSL_*` |
| `windowsAuth.*` | `APPLICATION_WINDOWSAUTH_*` |
| `audit.*` | `ENS_AUDIT_*` (see [Audit logging](../audit-logging.md)) |
| `bannerText`, `timezone`, `checkOutAfterMinutes`, `updateProfileUrl`, `logLevel`, `javaOpts` | The settings under "Other" |

Anything else from [Configuration](configuration.md) goes in `extraEnv`, as an environment variable
named the same way as in `.env`.

### 3. Install

```bash
helm install readyroom oci://ghcr.io/winllc/charts/readyroom --version 1.2.3 \
    -n readyroom -f readyroom-values.yaml
kubectl -n readyroom rollout status deploy/readyroom
```

The first start takes a minute or two while ReadyRoom creates its tables. The startup probe allows
up to 10 minutes. A Job named `readyroom-migrate-<revision>` then runs the database scripts; see
[Prepare PostgreSQL](#prepare-postgresql). Check the release with `helm test readyroom -n readyroom`.

The chart runs one replica. ReadyRoom's license volume and its scheduled jobs (directory sync,
auto check-out, absence emails) are single-writer, so more replicas are not supported.

### 4. License

Either upload the license on the license screen after the first start, or supply it at install
time:

```bash
helm install ... --set-file license.file=./license.lic
# or, from a Secret with the key license.lic:
kubectl -n readyroom create secret generic readyroom-license --from-file=license.lic
helm install ... --set license.existingSecret=readyroom-license
```

An init container copies it into the license volume **only when no license is installed there
yet**. A license you upload later on the license screen is never overwritten by `helm upgrade`.

The license, the installation id and the licensing state live on a PersistentVolumeClaim
(`readyroom-license`, mounted at `/var/lib/ens`). **Keep it.** Without it the installation gets a
new id, and a license bound to the old one stops working. `helm uninstall` deletes the claim with the
release. To keep it independent of the release, create the claim yourself and set
`persistence.license.existingClaim`.

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

The license claim and the database are kept. ReadyRoom updates its schema on start, and the
migration Job runs the database scripts again; they skip what is already done.

### 7. Uninstall

```bash
helm uninstall readyroom -n readyroom
```

This deletes the license claim, and with it the installation id, unless you used
`persistence.license.existingClaim`. Your PostgreSQL database is not touched. With the embedded
database, its claim (`data-readyroom-postgresql-0`) is kept until you delete it.

### Air-gapped clusters

Get the chart from the release download (`readyroom-helm-<version>.tgz`), or on a connected machine:

```bash
helm pull oci://ghcr.io/winllc/charts/readyroom --version 1.2.3
```

Mirror the images into your registry, keeping their paths:

| Image | Mirror as |
|---|---|
| `ghcr.io/winllc/in-n-out-work:<version>` | `<registry>/winllc/in-n-out-work:<version>` |
| `postgres:16` (migration Job and embedded database) | `<registry>/postgres:16` |
| `busybox:1.36.1` (license init container) | `<registry>/busybox:1.36.1` |

The release package's `images/` archive holds the ReadyRoom and PostgreSQL images (`docker load`,
then tag and push). Then point the chart at your registry:

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

## Prepare PostgreSQL

With the release package, PostgreSQL is already set up. With Helm, use your own server.

1. **Create a database and an owner account** (Helm, with your own server):

    ```sql
    CREATE ROLE readyroom LOGIN PASSWORD 'change-me';
    CREATE DATABASE readyroom OWNER readyroom;
    ```

2. **Start ReadyRoom once.** Hibernate (`spring.jpa.hibernate.ddl-auto: update`) creates the tables
   and columns:

    | Table | Holds |
    |---|---|
    | `check_in_out_records` | Every check-in, lock, unlock and check-out event, with the session id and the user's organization attributes at that moment |
    | `user_records` | One row per user DN: role, notes, favorite groups, additional managers, preferred and average login time, and directory metadata |
    | `user_event_records` | Per-day statuses (work from home, leave, late arrival, and so on) |
    | `notification_records` | Absence alerts and managers' responses |
    | `permission_records` | Per-user group grants |
    | `group_records` | Additional managers assigned to a group |
    | `global_calendar_records` | Organization calendar events and holidays |
    | `org_parse_rules` | Org-chart parse rules |

3. **Run the database script.** Hibernate does not create indexes or constraints.
   `001_indexes_and_unique_user_dn.sql` adds the indexes that the DN and date lookups need, and makes
   the user DN unique regardless of case. Before it adds the constraint, it merges any user rows
   whose DNs differ only by case.

    === "Release package"

        Back up the database, then, from the release directory:

        ```bash
        docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1' \
            < migrations/001_indexes_and_unique_user_dn.sql
        ```

    === "Helm"

        The chart does this for you. On every install and upgrade, a Job waits until ReadyRoom has
        created its tables and then runs every script with `psql`. Follow it with:

        ```bash
        kubectl -n readyroom logs -f job/readyroom-migrate-1   # the number is the release revision
        ```

        The log ends with `Migration 001 complete.` Set `migrations.enabled: false` to run the
        scripts yourself instead.

    !!! warning "Do not use `--single-transaction`"
        The indexes are built with `CREATE INDEX CONCURRENTLY`, so the application can keep running
        and writing while they build, and that cannot happen inside a transaction. Only the short
        merge step takes a lock, and only on `user_records`. You can run the script again safely:
        existing indexes are skipped, and an invalid index left by an interrupted run is dropped and
        rebuilt. If the tables do not exist yet, the script stops with an error.

## Connect the LDAP directory

Set the connection and describe your directory:

| Setting | `.env` | Helm value |
|---|---|---|
| Server | `LDAP_HOST`, `LDAP_PORT`, or `SPRING_LDAP_URLS=ldaps://ldap.example.com:636` | `ldap.host`, `ldap.port`, or `ldap.url` |
| Bind account | `LDAP_USERNAME`, `LDAP_PASSWORD` | `ldap.bindDn`, `ldap.bindPassword` or `ldap.existingSecret` |
| Users | `APPLICATION_USERBASEDN`, `APPLICATION_USERLDAPFILTER` | `ldap.userBaseDn`, `ldap.userFilter` |
| Group trees | `APPLICATION_GROUPS_0_GROUPSBASEDN`, `_1_`, ... | `ldap.groupBaseDns` |
| Administrators | `APPLICATION_SUPERUSERDNS_0`, `_1`, ... | `superUserDns` |
| Attributes | `APPLICATION_USERLDAPORGANIZATIONATTRIBUTE` (`o`), `...LOCATIONATTRIBUTE` (`l`), `...EMPLOYEETYPEATTRIBUTE` (`employeeType`), `...DUTYSUBORGANIZATIONATTRIBUTE` (`departmentNumber`), `APPLICATION_USERLDAPMANAGERIDATTRIBUTE` (`title`), `APPLICATION_MANAGERLDAPIDATTRIBUTE` (`street`) | `ldap.attributes.*` |
| Org chart | `APPLICATION_DUTYSUBORGGROUPSBASEDN` | `orgChart.groupsBaseDn` |

For LDAPS, the directory's certificate must be trusted by the JVM. A certificate from a public CA
is.

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
2. **Truststore.** Create a PKCS#12 truststore holding the CA certificate(s) that issue user
   certificates. Only certificates that chain to these CAs are accepted.
3. **User certificates.** Issue each user a client certificate whose subject DN equals their LDAP
   DN (or set up `lookup-on-dn-attribute`, described above). Install it with its private key in
   the user's `CurrentUser\My` store on the workstation.
4. **Give ReadyRoom the files.**

    === "Release package"

        Copy them to `tls/keystore.p12` and `tls/truststore.p12`, make them readable by group 0
        (`chgrp 0 tls/*.p12 && chmod 0640 tls/*.p12`), and set in `.env`:

        ```bash
        COMPOSE_FILE=docker-compose.yml:docker-compose.tls.yml
        SSL_ENABLED=true
        SERVER_SSL_KEYSTOREPASSWORD=...
        SERVER_SSL_TRUSTSTOREPASSWORD=...
        APPLICATION_BASE_URL=https://readyroom.example.com:8181
        ```

        Then `docker compose up -d`.

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

## Configure email

Absence alerts are sent through Spring Mail. Set the SMTP host, port, username and password, the
From address, and the public URL, which becomes the link in each email. See
[Configuration](configuration.md#email-and-notifications).

| Setting | `.env` | Helm value |
|---|---|---|
| Server | `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT` | `smtp.host`, `smtp.port` |
| Account | `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD` | `smtp.username`, `smtp.password` or `smtp.existingSecret` |
| AUTH and STARTTLS (on by default) | `SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH`, `..._STARTTLS_ENABLE` | `smtp.auth`, `smtp.starttls` |
| Trusted mail server certificates (default `*`, any) | `SPRING_MAIL_PROPERTIES_MAIL_SMTP_SSL_TRUST` | `smtp.sslTrust` |
| From address | `APPLICATION_NOTIFICATIONSENDEREMAIL` | `smtp.from` |
| Link in each email | `APPLICATION_BASE_URL` | `publicUrl` |

The packaged configuration points at a development mail host, so always set the server.

!!! note "Health and mail"
    `/actuator/health` includes the mail server. If it cannot be reached, ReadyRoom reports `DOWN`
    and the release package's container shows as unhealthy, although everything except email
    works. Set `MANAGEMENT_HEALTH_MAIL_ENABLED=false` (Helm: `smtp.healthCheck: false`) if you do
    not use email. The Helm chart's liveness and readiness probes never include the mail server.

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

    Put `krb5.conf` and `http.keytab` in `kerberos/` (keytab: `chgrp 0`, `chmod 0640`), and set in
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
(`docker compose exec app ...` or `kubectl -n readyroom exec deploy/readyroom -- ...`). The server's
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

1. copies `Update-WorkStatus.ps1` to `C:\Program Files\InOutWorker` and writes
   `inoutworker.config.json` there (only if you passed settings);
2. creates `%ProgramData%\InOutWorker` and grants `BUILTIN\Users` Modify on it;
3. registers four hidden tasks under `\InOutWork\`: **Login**, **Lock**, **Unlock** and **Logout**.

For mass deployment, configuration files, parameters and what each task does, see
[Windows client](windows-client.md).

## First start checklist

- [ ] The license screen, `<public URL>/app/admin/license`, shows a valid license. Sign in as an
      administrator (`superUserDns` / `APPLICATION_SUPERUSERDNS_*`) to open it. It also shows this
      installation's id (for example `RR-7F3K-29QD`), which you enter on the ENS licensing portal to
      bind a license. See [Licensing](../licensing.md).
- [ ] You know where the license volume is (`readyroom_ens-license`, or the `readyroom-license`
      claim) and it is in your backups, together with the database.
- [ ] The database script has run (release package: by hand; Helm: the migration Job's log ends with
      `Migration 001 complete.`).
- [ ] Audit events reach your SIEM, if you configured one. See [Audit logging](../audit-logging.md).

## Verify the installation

- [ ] The health endpoint returns `{"status":"UP"}`:
      `curl -k https://readyroom.example.com:8181/actuator/health` (release package), or
      `helm test readyroom -n readyroom` (Helm).
- [ ] The startup log (`docker compose logs app`, or `kubectl -n readyroom logs deploy/readyroom`)
      shows `Default time zone set to ...` with the zone you expect, and
      `LDAP connection pooling enabled`.
- [ ] Browsing to ReadyRoom with a client certificate opens **Home**. Without one, the **Sign in**
      page accepts directory credentials.
- [ ] A super user sees **Metrics** and **Settings** in the sidebar.
- [ ] **Groups** lists the trees under each configured group base DN.
- [ ] On a workstation, `& 'C:\Program Files\InOutWorker\Update-WorkStatus.ps1' -Action login -WhatIf`
      reports the URL and the sign-in method it would use.
- [ ] `Get-ScheduledTask -TaskPath "\InOutWork\"` lists four tasks.
- [ ] After a real logon, `%ProgramData%\InOutWorker\logs\inoutworker-<yyyyMMdd>.log` contains
      `Server accepted 'login'`, and the user's status on **Home** is `IN`.
- [ ] After the first scheduled directory refresh (5 minutes after startup by default), the log
      shows `Directory refresh: N scanned, N created, ...` when it created or updated user records.
      If nothing changed, the line is logged only at `debug`.
