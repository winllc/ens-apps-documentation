# Installation

You can install CertAlert in two ways:

- **The release package.** A signed download that runs CertAlert and its PostgreSQL with Docker
  Compose. The container images are inside it, so it installs on a host with no internet access.
- **The Helm chart.** For Kubernetes and OpenShift. It is published with every release, at the
  same version as the application.

Both run the same tested image, `ghcr.io/winllc/cert-alert:<version>`. For every setting
referenced here, see [Configuration](configuration.md).

## Prerequisites

### Common

| Requirement | Notes |
|---|---|
| **LDAP directory** | Publishing `icOrgPerson` / `icOrgServer` entries, or equivalents with the attribute names mapped in configuration. Use `ldaps://` for anything but a lab. |
| **Service account** | A read-only account is enough: it reads people, servers and their certificates. It needs write access only if you enable certificate clean-up or editable attributes. |
| **An administrator** | At least one person to name in the admin identifiers: an address the directory publishes for them, or their uid. Not a common name. |
| **An ENS license** | An evaluation, or the license issued against your order, from the ENS licensing portal. See [Licensing](../licensing.md). |
| **SMTP server** *(optional)* | For the email round-up and the email alert channel. |
| **Keystores** *(optional)* | A PKCS#12 key store and trust store, if users will sign in with X.509 client certificates. |
| **A private CA trust store** *(optional)* | If the directory, mail server or Splunk uses a certificate from a private CA. |

### For the release package

| Requirement | Notes |
|---|---|
| **Docker Engine 24+** | With the Compose plugin (`docker compose version`). |
| **Disk** | About 3 GB for the images, plus the database. |
| **gpg and sha256sum** | To verify the download. |

The package includes PostgreSQL (`postgres:17.11-alpine`). You do not need your own.

### For Helm

| Requirement | Notes |
|---|---|
| **Kubernetes 1.25+** or OpenShift 4.12+ | |
| **Helm 3.8+** | For OCI charts. |
| **PostgreSQL** | An external PostgreSQL that you run and back up. The chart can also run one for evaluation (no backups). |
| **A default StorageClass** | Or one you name, for the license PVC (100Mi, `ReadWriteOnce`). |
| **An Ingress controller or Route** | To reach the UI from outside the cluster. For client-certificate sign-in, one that supports TLS passthrough, or a LoadBalancer Service. |

## Choosing a method

| | Release package | Helm chart |
|---|---|---|
| Runs on | One Docker host | Kubernetes or OpenShift |
| Database | Included (PostgreSQL in the bundle) | Your PostgreSQL; evaluation-only one included |
| Air-gapped | Yes: the images are in the download | Yes: mirror the images, set `global.imageRegistry` |
| Settings | `.env` (from `env.template`) | Helm values |
| Upgrade | `./upgrade.sh --from <old dir>` | `helm upgrade` |

## Install from the release package

### 1. Download and verify

ENS Solutions sends you links to `cert-alert-<version>.tar.gz`, the SBOMs, `SHA256SUMS` and
`SHA256SUMS.asc`. Verify them before you unpack anything, as described in
[Releases and downloads](../releases.md):

```bash
gpg --verify SHA256SUMS.asc SHA256SUMS
sha256sum -c SHA256SUMS --ignore-missing
```

### 2. Unpack and run install.sh

```bash
tar xzf cert-alert-<version>.tar.gz
cd cert-alert-<version>
./install.sh
```

The first run loads the images, creates `.env` from `env.template` with a random database
password, and lists what you must fill in. It does not start anything yet.

### 3. Fill in .env

Edit `.env`. Set at least:

| Setting | Example |
|---|---|
| `CERT_ALERT_LDAP_URL` | `ldaps://directory.example.gov:636` |
| `CERT_ALERT_LDAP_BASE` | `dc=example,dc=gov` |
| `CERT_ALERT_LDAP_USER` / `CERT_ALERT_LDAP_PASSWORD` | the service account it binds as |
| `CERT_ALERT_SECURITY_ADMIN_IDENTIFIERS` | `pki-admin@example.gov,jdoe` |

Then, if you use them: SMTP (`SPRING_MAIL_*`), TLS (`CERT_ALERT_TLS_*`, with the keystores in
`tls/`), the classification banner and the expiry thresholds. Every setting is described in
`.env`. Any setting from [Configuration](configuration.md) can be added as its environment
variable: `cert-alert.ldap.page-size` is `CERT_ALERT_LDAP_PAGE_SIZE`.

!!! note "Keep .env private"
    `.env` holds passwords. `install.sh` makes it readable by its owner only (`0600`). Values that
    contain spaces, `#` or `$` go in single quotes.

### 4. The license

Put your license at `license/license.lic`. When CertAlert has started, `install.sh` copies it into
the `ens-license` volume. It never replaces a different license already installed there, which
may be a newer one uploaded on the license screen.

Or skip this and upload the license on the license screen after the first start.

### 5. First start

```bash
./install.sh
```

This time it checks `.env`, starts PostgreSQL and CertAlert, installs `license/license.lic`, and
waits for CertAlert to report healthy. It prints the URL and the license screen's address.

The UI is on port 8080 of the host. `CERT_ALERT_PORT` moves it, and
`CERT_ALERT_BIND_ADDRESS=127.0.0.1` keeps it to the host, behind a reverse proxy of your own.
With `CERT_ALERT_TLS_ENABLED=true` it is `https://` on the same port.

Day to day:

```bash
docker compose ps                      # status and health
docker compose logs -f cert-alert      # application log
docker compose up -d                   # after editing .env (restart alone does not re-read it)
docker compose down                    # stop (data kept)
```

!!! danger "`docker compose down -v` deletes your data"
    It deletes the database volume and the `ens-license` volume. The license volume holds the
    installation id, so the installation gets a new id and a bound license must be re-bound.

### 6. Upgrade with upgrade.sh

Before upgrading, check that `CERT_ALERT_SECURITY_ADMIN_IDENTIFIERS` in the old `.env` names each
administrator by an address the directory publishes for them or by their uid. A common name,
display name or preferred name, or an address added in CertAlert, does not grant `ROLE_ADMIN`.

Unpack the new release next to the old one and point it at the old directory:

```bash
tar xzf cert-alert-<new>.tar.gz
cd cert-alert-<new>
./upgrade.sh --from ../cert-alert-<old>
```

It copies `.env`, `license/`, `tls/` and `email-templates/` across without overwriting anything,
lists settings that are new in this release, backs up the database to `backups/`, loads the new
images and restarts on them. The database and license volumes are kept. The schema is migrated
on start. Migrations are not undone, so going back to an older release means restoring that
backup.

## Install with Helm

### 1. Install the chart

Write your values to a file, for example `cert-alert-values.yaml`:

```yaml
ldap:
  url: ldaps://directory.example.gov:636
  base: dc=example,dc=gov
  bindDn: cn=cert-alert,ou=services,dc=example,dc=gov
  existingSecret: cert-alert-secrets        # key ldap-password
database:
  host: postgres.example.gov
  existingSecret: cert-alert-secrets        # key db-password
security:
  adminIdentifiers: [pki-admin@example.gov]
```

Create the Secret, then install:

```bash
kubectl create namespace cert-alert
kubectl -n cert-alert create secret generic cert-alert-secrets \
  --from-literal=ldap-password='...' --from-literal=db-password='...'

helm install cert-alert oci://ghcr.io/winllc/charts/cert-alert --version X.Y.Z \
  --namespace cert-alert -f cert-alert-values.yaml \
  --set-file license.file=./license.lic
```

`ldap.url`, `ldap.base` and `database.host` are required. The chart stops with a clear message if
one is missing.

### 2. Key values

| Value | What it sets |
|---|---|
| `ldap.url`, `ldap.base`, `ldap.bindDn` | The directory and the service account |
| `security.adminIdentifiers` | Who administers CertAlert |
| `security.ldap.userSearchBase`, `.userSearchFilter`, `.userDnPatterns` | How password sign-in finds a user |
| `database.host`, `.port`, `.name`, `.username`, `.params` | The external PostgreSQL |
| `mail.host`, `.port`, `.username` | SMTP |
| `notifications.email.*`, `alerts.email.*` | The round-up and the operator alerts |
| `tls.*` | TLS in the pod, for client-certificate sign-in |
| `caTruststore.*` | A private CA for the directory, SMTP or Splunk |
| `banner.*`, `thresholds.*`, `revocation.*` | Look, expiry windows, default OCSP and CRL |
| `audit.*` | Audit logging to stdout, a file, Splunk or syslog. See [Audit logging](../audit-logging.md). |
| `extraEnv`, `extraEnvFrom` | Any other setting, as its environment variable |

Every setting in the release package's `env.template` has a value in the chart. The chart's
`README.md` lists them all.

### 3. Secrets

Never put passwords in a ConfigMap. Each secret value takes either an inline value, which the
chart writes to its own Secret, or an `existingSecret` and `existingSecretKey`:

| Secret | Inline value | Default key with `existingSecret` |
|---|---|---|
| Database password | `database.password` | `db-password` |
| LDAP bind password | `ldap.bindPassword` | `ldap-password` |
| SMTP password | `mail.password` | `mail-password` |
| Key store password | `tls.keystore.password` | `keystore-password` |
| Trust store password | `tls.truststore.password` | `truststore-password` |
| Private CA trust store password | `caTruststore.password` | `ca-truststore-password` |
| Splunk HEC token | `audit.splunk.token` | `splunk-hec-token` |

The key store and trust store files themselves always come from Secrets you create
(`tls.keystore.existingSecret`, `tls.truststore.existingSecret`).

### 4. License

Give the chart your license with `--set-file license.file=./license.lic`, or with a Secret of your
own that holds `license.lic`:

```bash
kubectl -n cert-alert create secret generic cert-alert-license --from-file=license.lic=./license.lic
helm upgrade --install cert-alert ... --set license.existingSecret=cert-alert-license
```

An init container copies it into the license volume **only if no license is installed there
yet**, the same as the package's `install.sh`. A license uploaded later on the license screen is
never overwritten by a `helm upgrade`. To replace an installed license, upload the new one on the
license screen.

Or give the chart no license, and upload it on the license screen after the first start.

!!! warning "Keep the license PVC"
    The chart stores the license, the installation id and the license revisions seen in a PVC,
    `cert-alert-license` for a release named `cert-alert`, mounted at `/var/lib/ens`. `helm upgrade` keeps it.
    `helm uninstall` deletes it, and a reinstall then gets a new installation id. To keep it
    across uninstalls, create the PVC yourself and set `persistence.license.existingClaim`.

### 5. Reaching it: Ingress, OpenShift and mTLS

With no Ingress or Route, use a port-forward:

```bash
kubectl -n cert-alert port-forward svc/cert-alert 8080:8080    # then http://localhost:8080/
```

=== "Ingress"

    ```yaml
    ingress:
      enabled: true
      className: nginx
      hosts:
        - host: cert-alert.example.gov
          paths: [{path: /, pathType: Prefix}]
      tls:
        - secretName: cert-alert-tls
          hosts: [cert-alert.example.gov]
    ```

    TLS ends at the Ingress. Users sign in with their directory password.

=== "OpenShift"

    ```yaml
    openshift:
      enabled: true          # the restricted SCC assigns the uid and fsGroup
    route:
      enabled: true
      host: cert-alert.apps.example.gov
      tls:
        termination: edge
    ```

    The image runs as any uid in group 0, so it works under the restricted SCC. Use an external
    PostgreSQL on OpenShift: the evaluation database is not supported there.

=== "X.509 client certificates (passthrough)"

    CertAlert reads the client certificate from its own TLS handshake. It does not accept a
    certificate forwarded in a header, so **TLS must pass through to the pod**. Edge termination
    at an Ingress or Route works only for password sign-in.

    ```bash
    kubectl -n cert-alert create secret generic cert-alert-tls-stores \
      --from-file=keystore.p12 --from-file=truststore.p12
    kubectl -n cert-alert create secret generic cert-alert-tls-passwords \
      --from-literal=keystore-password='...' --from-literal=truststore-password='...'
    ```

    ```yaml
    tls:
      enabled: true
      keystore:
        existingSecret: cert-alert-tls-stores
        passwordExistingSecret: cert-alert-tls-passwords
      truststore:
        existingSecret: cert-alert-tls-stores
        passwordExistingSecret: cert-alert-tls-passwords
    ```

    Then pass TLS through with one of:

    - ingress-nginx, started with `--enable-ssl-passthrough`, and the annotations
      `nginx.ingress.kubernetes.io/ssl-passthrough: "true"` and
      `nginx.ingress.kubernetes.io/backend-protocol: HTTPS`;
    - an OpenShift Route with `route.tls.termination: passthrough`;
    - `service.type: LoadBalancer` (with `externalTrafficPolicy: Local` to keep client addresses).

    The key store's certificate must be issued for the name users browse to.

### 6. Upgrade

```bash
helm upgrade cert-alert oci://ghcr.io/winllc/charts/cert-alert --version X.Y.Z \
  --namespace cert-alert -f cert-alert-values.yaml
```

Back up the database first, and check that `security.adminIdentifiers` names each administrator
by an address the directory publishes for them or by their uid, not by a common name. The pod is
replaced (only one replica runs), the schema is migrated on start, and the license PVC is kept with the installed license and the installation id.
Migrations are not undone by `helm rollback`: going back means restoring the backup.

### 7. Uninstall

```bash
helm uninstall cert-alert --namespace cert-alert
```

This deletes the license PVC the chart created, and with it the installation id. It does not
delete a PVC you supplied with `persistence.license.existingClaim`, or the evaluation database's
PVC (`data-cert-alert-postgresql-0`). Delete those yourself if you mean to.

### Evaluation database

For a quick evaluation, the chart can run PostgreSQL for you:

```yaml
database:
  embedded:
    enabled: true
```

!!! danger "Evaluation only, no backups"
    It is a single PostgreSQL pod (the same `postgres:17.11-alpine` the release package uses) with
    a PVC. Nothing backs it up or makes it highly available. Use your own PostgreSQL for anything
    you need to keep.

### Air-gapped clusters

1. On a connected host, get the chart: `helm pull oci://ghcr.io/winllc/charts/cert-alert --version X.Y.Z`,
   or use `cert-alert-X.Y.Z.tgz` from the release download.
2. Mirror the image `ghcr.io/winllc/cert-alert:X.Y.Z` (and `postgres:17.11-alpine` for the
   evaluation database) into your registry. The release package's `images/` tar holds both
   (`docker load -i`).
3. Install from the file and point every image at your mirror:

```bash
helm install cert-alert ./cert-alert-X.Y.Z.tgz --namespace cert-alert \
  -f cert-alert-values.yaml --set global.imageRegistry=registry.example.gov
```

The chart then uses `registry.example.gov/winllc/cert-alert:X.Y.Z` (and
`registry.example.gov/library/postgres:17.11-alpine`). Add `imagePullSecrets` if your registry
needs them. The `helm test` pod and the license init container use the application image, so
nothing else needs mirroring.

## Setup for both methods

### Directory

- Point CertAlert at the directory with the URL, the search base and the service account.
- The directory's CA must be one the JVM trusts. For a private CA, add a trust store: in the
  package, put it in `tls/` and add `-Djavax.net.ssl.trustStore=/etc/cert-alert/tls/ca-truststore.p12
  -Djavax.net.ssl.trustStorePassword=... -Djavax.net.ssl.trustStoreType=PKCS12` to `JAVA_OPTS`
  (keep the default heap options); with Helm, set `caTruststore.existingSecret`.
- If `serverPOC` holds names rather than addresses, set `CERT_ALERT_LDAP_REQUIRE_EMAIL_POC=false`
  (`ldap.requireEmailPoc: false`).
- If entries sync but no certificates do, the directory may have no `userCertificate;binary`
  (a virtual directory, such as Radiant Logic FID 7.4): set
  `CERT_ALERT_LDAP_BINARY_CERTIFICATE_OPTION=false` (`ldap.binaryCertificateOption: false`).
- For a directory that allows no search, bind straight to a DN shape:
  `CERT_ALERT_SECURITY_LDAP_USER_DN_PATTERNS=uid={0},ou=people`.

### Database

- The package runs PostgreSQL for you. With Helm, give it your own (`database.*`).
- Any PostgreSQL version supported by the bundled driver works; the package uses 17.
- Flyway creates and migrates the schema on start. Hibernate is set to `validate`, so a mapping
  that drifts from the migrations fails at boot rather than silently altering tables.
- The database password is read once, when PostgreSQL first creates its volume. Changing it later
  means `ALTER ROLE` in the database as well.

### Certificates and TLS

- With TLS on, CertAlert serves `https://` on port 8080 (the same port), and asks for a client
  certificate with `client-auth: want`. A browser without a certificate still reaches the
  password form.
- The trust store holds the CAs whose client certificates are accepted. TLS does not start
  without one.
- Being in the trust store only gets a certificate as far as CertAlert: it must also be a
  certificate the directory publishes for a person. See [Security](security.md).

### Email

- Set the SMTP server (`SPRING_MAIL_*`, or `mail.*` in Helm).
- Turn on the daily round-up with `CERT_ALERT_NOTIFICATIONS_EMAIL_ENABLED=true` and a `FROM`
  address. `CERT_ALERT_EMAIL_DRY_RUN=true` builds every message and sends none, for a first look
  at who would hear what.
- Operator alerts go to a fixed list: `CERT_ALERT_ALERTS_EMAIL_ENABLED`, `_FROM` and `_TO`.
- Your own templates go in `email-templates/email/` next to the package's compose file (and set
  `CERT_ALERT_NOTIFICATIONS_EMAIL_TEMPLATE_DIRECTORY=/etc/cert-alert/templates`), or in a ConfigMap
  named in `notifications.email.templatesConfigMap`.

### Image facts

- Runs as **uid 185** (the base image's own user), with files owned by `185:0` and group
  permissions equal to owner permissions, so it starts under OpenShift's arbitrary-uid policy.
  The Helm chart also runs it with a read-only root filesystem.
- The application listens on **8080** (`SERVER_PORT`), for HTTP and, with TLS on, HTTPS.
- `JAVA_OPTS` defaults to `-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError`, so the heap
  follows the container's memory limit. Setting `JAVA_OPTS` replaces the default, so keep those
  two options.
- The JVM is PID 1, receives `SIGTERM` directly and shuts down gracefully.
- The license lives in `/var/lib/ens/certalert` (`ENS_LICENSE_STATE_DIR`, `ENS_LICENSE_FILE`),
  which must be persistent and writable.
- Probe `/actuator/health/liveness` and `/actuator/health/readiness`, not the aggregate
  `/actuator/health`, which turns `DOWN` when the directory is unreachable. See
  [Health checks](operator-guide.md#health-checks).
- `.dockerignore` excludes `*.p12`, `*.jks`, `*.pem`, `*.key` and `*.lic`, so key material and
  licenses cannot be baked in. Mount keystores as a volume or Secret.

## First start checklist

1. **Database**: the log shows Flyway applying migrations `V1` … `V17` on an empty database.
2. **Liveness**: `curl -fsS http://<host>:8080/actuator/health/liveness` returns
   `{"status":"UP"}`. With Helm, `helm test cert-alert -n cert-alert` checks it through the
   Service.
3. **Directory**: `/actuator/health` includes the LDAP indicator. It reports `DOWN` if the
   directory is unreachable (details are shown only to administrators).
4. **Sign in** with a directory password as one of the admin identifiers. The account menu should
   read *Administrator*.

    !!! warning "Name at least one administrator"
        The admin identifiers are empty by default. Without an administrator, nobody can install
        the license or run the first sync. See [Security](security.md#roles).

5. **License**: open the license screen, `/admin/license` (**Administration → License**). It shows
   whether a license is installed, and this installation's id. Until one is installed, only
   sign-in and this screen work, and administrators are sent here. Upload the license if you did
   not give it to the installer. To bind a license to this installation, enter the id on the ENS
   licensing portal. See [Licensing](../licensing.md).
6. **Populate the cache**: press **Sync directory** on the People or Servers page (or
   `POST /api/v1/sync`). Badges show entries seen and certificates cached per job. Or wait for the
   nightly sweeps (02:00 and 04:00 UTC).
7. **Verify**: the People and Servers stat cards show totals, and `GET /api/v1/sync/runs` shows
   `COMPLETED` runs.
8. **X.509 sign-in** works only after the first sync, because client certificates are matched
   against certificates already cached from the directory.

Next: [Configuration](configuration.md) and the [Operator guide](operator-guide.md).
