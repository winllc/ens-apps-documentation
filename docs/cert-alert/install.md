# Installation

## At a glance

| Method | Use it for | You run |
|---|---|---|
| [Release package](#install-with-docker-or-podman) | One host with Docker or Podman, including hosts with no internet access. CertAlert and its PostgreSQL run with Compose | `install.sh`, `upgrade.sh` |
| [Helm chart](#install-with-helm) | Kubernetes and OpenShift, with your own PostgreSQL | `helm install`, `helm upgrade` |
| [Demo](#trying-it-out-the-demo) | A read-only look at CertAlert with invented data. **Not for production** | `docker compose -f docker-compose.demo.yml` |

The package and the chart run the same tested image, `ghcr.io/winllc/cert-alert:<version>`.
For every setting referenced here, see [Configuration](configuration.md).

## Prerequisites

### Common

| Requirement | Notes |
|---|---|
| **LDAP directory** | Publishing `icOrgPerson` / `icOrgServer` entries, or equivalents with the attribute names mapped in configuration. Use `ldaps://` for anything but a lab. |
| **Service account** | A read-only account is enough: it reads people, servers and their certificates. It needs write access only if you enable certificate clean-up or editable attributes. |
| **An administrator** | At least one person to name in the admin identifiers, by **uid** (or entry DN). An e-mail address there is ignored. |
| **An ENS license** | An evaluation, or the license issued against your order, from the ENS licensing portal. See [Licensing](../licensing.md). |
| **SMTP server** *(optional)* | For the email round-up and the email alert channel. |
| **Keystores** *(optional)* | A PKCS#12 key store and trust store, if users will sign in with X.509 client certificates. |
| **A private CA trust store** *(optional)* | If the directory, mail server or Splunk uses a certificate from a private CA. |

### For the release package

| Requirement | Notes |
|---|---|
| **Docker or Podman** | Docker Engine 24+ with Compose v2 (`docker compose`, or a standalone `docker-compose` v2; v1 is refused). Or Podman 4.0+ with podman-compose 1.3.0+ (`podman compose` is used when podman-compose is not installed). |
| **Disk** | About 3 GB for the images, plus the database. |
| **sha256sum, gpg** | To verify the download. |

The package includes PostgreSQL (`postgres:17.11-alpine`). You do not need your own.

### For Helm

| Requirement | Notes |
|---|---|
| **Kubernetes 1.25+** or OpenShift 4.12+ | |
| **Helm 3.8+** | For OCI charts. |
| **PostgreSQL** | An external PostgreSQL that you run and back up. The chart can also run one for evaluation (no backups). |
| **A default StorageClass** | Or one you name, for the license PVC (100Mi, `ReadWriteOnce`). |
| **An Ingress controller or Route** | To reach the UI from outside the cluster. For client-certificate sign-in, one that supports TLS passthrough, or a LoadBalancer Service. |

## Getting the release

1. Sign in to the **ENS licensing portal** and open **Downloads** (`/account/downloads`). Any
   account with a non-revoked CertAlert license, evaluation licenses included, can download. The
   page lists every version, with the latest highlighted, and each file's size and SHA-256.
2. Download, into one directory:

    | File | What it is |
    |---|---|
    | `cert-alert-<version>.tar.gz` | The release package, with the images |
    | `cert-alert-<version>.tgz` | The Helm chart (also at `oci://ghcr.io/winllc/charts/cert-alert`) |
    | `sbom-cert-alert.spdx.json`, `sbom-postgres.spdx.json` | SBOMs of the two images |
    | `SHA256SUMS`, `SHA256SUMS.asc` | Checksums, and ENS Solutions' signature over them when the release is signed |

3. Verify them before you unpack anything:

    ```bash
    sha256sum -c SHA256SUMS --ignore-missing     # each file you downloaded
    gpg --verify SHA256SUMS.asc SHA256SUMS       # when signed, with the ENS release key imported
    ```

The files are delivered from ENS Solutions' own storage, not from GitHub Releases. Importing the
release key and verifying the cosign signatures of the image and the chart are described in
[Releases and downloads](../releases.md).

## Install with Docker or Podman

### 1. Unpack and run install.sh

```bash
tar xzf cert-alert-<version>.tar.gz
cd cert-alert-<version>
./install.sh
```

The first run picks the container engine, loads the images, creates `.env` from `env.template`
with a random database password, and lists what you must fill in. It does not start anything
yet.

The engine is, in order: `--engine docker|podman`, the `CONTAINER_ENGINE` environment variable,
`CONTAINER_ENGINE` in `.env`, and otherwise Docker if its daemon answers, else Podman. The
scripts print which engine and compose command they use, and the first run records the engine
in `.env`, so later runs and upgrades keep it.

### 2. Fill in .env

Edit `.env`. Set at least:

| Setting | Example |
|---|---|
| `CERT_ALERT_LDAP_URL` | `ldaps://directory.example.gov:636` |
| `CERT_ALERT_LDAP_BASE` | `dc=example,dc=gov` |
| `CERT_ALERT_LDAP_USER` / `CERT_ALERT_LDAP_PASSWORD` | the service account it binds as |
| `CERT_ALERT_SECURITY_ADMIN_IDENTIFIERS` | `pki-admin,jdoe` (uids, comma-separated) |

A DN has commas of its own: list it as `CERT_ALERT_SECURITY_ADMIN_IDENTIFIERS_0`, `_1`, … instead.

Then, if you use them:

- SMTP (`SPRING_MAIL_*`) and its timeouts (`CERT_ALERT_MAIL_*_TIMEOUT_MS`);
- TLS (`CERT_ALERT_TLS_*`, with the keystores in `tls/`);
- where the UI is published (see [step 4](#4-choose-where-it-listens));
- sessions (`ENS_SESSION_*`) and the notice-and-consent banner (`ENS_CONSENT_BANNER_*`);
- client certificates (`ENS_X509_*`): revocation at sign-in, allowed issuers, mapping, and
  header mode;
- the directory timeouts (`CERT_ALERT_LDAP_*_TIMEOUT_MS`) and the revocation hosts
  (`CERT_ALERT_REVOCATION_ALLOWED_HOSTS`);
- JSON logs (`LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`) and a metrics port
  (`MANAGEMENT_SERVER_PORT`);
- the classification banner, the expiry thresholds and the audit outputs (`ENS_AUDIT_*`).

Every setting is described in `.env`. Any setting from [Configuration](configuration.md) can be
added as its environment variable: `cert-alert.ldap.page-size` is `CERT_ALERT_LDAP_PAGE_SIZE`.

!!! note "Keep .env private"
    `.env` holds passwords. `install.sh` makes it readable by its owner only (`0600`). Values that
    contain spaces, `#` or `$` go in single quotes.

### 3. Add the license

Choose one:

- **The drop-in.** Put the file at `license/license.lic`. Once CertAlert has started,
  `install.sh` copies it into the `ens-license` volume. It never replaces a different license
  already installed, which may be a newer one uploaded on the license screen.
- **`ENS_LICENSE` in `.env`.** The license text itself: the token between the file's `BEGIN` and
  `END` lines, or the whole file base64-encoded (`base64 -w0 license.lic`). It is installed at
  start if no license is installed yet, or if it is a newer revision of the installed one.
- **The license screen.** Upload it at **Administration → License** (`/admin/license`) after the
  first start.

The image sets `ENS_LICENSE_FILE` and `ENS_LICENSE_STATE_DIR` to `/var/lib/ens/certalert`, the
`ens-license` volume; leave them as they are. See [Licensing](../licensing.md) for binding,
expiry and the rest.

### 4. Choose where it listens

A new install publishes the UI on **every interface** (`CERT_ALERT_BIND_ADDRESS=0.0.0.0`), port
8080 (`CERT_ALERT_PORT`). With `CERT_ALERT_TLS_ENABLED=true` it is `https://` on the same port.

| You want | Set |
|---|---|
| TLS in CertAlert, reachable from the network | `CERT_ALERT_TLS_ENABLED=true`, keystore and trust store in `tls/` |
| A reverse proxy on this host | `CERT_ALERT_BIND_ADDRESS=127.0.0.1`, `SERVER_FORWARD_HEADERS_STRATEGY=framework`, `ENS_COOKIE_SECURE=true`. Point the proxy at `http://127.0.0.1:8080` |
| A reverse proxy container on the compose network | `cp docker-compose.no-ports.yml docker-compose.override.yml`. Nothing is published; the proxy joins `cert-alert_default` and forwards to `http://cert-alert:8080`. Needs Docker Compose 2.24+ or podman-compose 1.4.0+ |
| Another port | `CERT_ALERT_PORT`. Rootless Podman needs 1024 or above |

!!! warning "Plain HTTP on 0.0.0.0"
    With TLS off and the UI on any address other than `127.0.0.1`, CertAlert is reachable over
    plain HTTP: directory passwords and session cookies cross the network in clear text.
    `install.sh` and `upgrade.sh` warn every time they run. Turn TLS on, or put a
    TLS-terminating proxy in front and bind to `127.0.0.1`.

### 5. Start it

```bash
./install.sh
```

This time it checks `.env`, starts PostgreSQL and CertAlert, installs `license/license.lic`, and
waits for CertAlert to report healthy. It prints the URL and the license screen's address.

Day to day:

=== "Docker"

    ```bash
    docker compose ps                      # status and health
    docker compose logs -f cert-alert      # application log
    docker compose up -d                   # after editing .env (restart alone does not re-read it)
    docker compose down                    # stop (data kept)
    ```

=== "Podman"

    ```bash
    podman-compose ps                      # status and health
    podman-compose logs -f cert-alert      # application log
    podman-compose up -d                   # after editing .env (restart alone does not re-read it)
    podman-compose down                    # stop (data kept)
    ```

!!! danger "`down -v` deletes your data"
    It deletes the database volume and the `ens-license` volume. The license volume holds the
    installation id, so the installation gets a new id and a bound license must be re-bound.

### 6. Upgrade with upgrade.sh

Unpack the new release next to the old one and point it at the old directory:

```bash
tar xzf cert-alert-<new>.tar.gz
cd cert-alert-<new>
./upgrade.sh --from ../cert-alert-<old>
```

It copies `.env`, `license/`, `tls/`, `email-templates/` and a `docker-compose.override.yml` across
without overwriting anything, lists settings that are new in this release, backs up the database
to `backups/`, loads the new images and restarts on them. The database and license volumes are
kept, and the schema is migrated on start. Migrations are not undone, so going back to an older
release means restoring that backup.

!!! note "What an upgrade keeps, and what changes"
    - **Engine.** It uses `CONTAINER_ENGINE` from `.env`. An `.env` without it uses Docker if its
      daemon answers, so an existing Docker install needs nothing new.
    - **Address and port.** It keeps `CERT_ALERT_BIND_ADDRESS` and `CERT_ALERT_PORT` from `.env`.
      An install on `127.0.0.1` stays there. If `.env` was written for the release that listened
      on `127.0.0.1` by default and does not set the address, the upgrade adds
      `CERT_ALERT_BIND_ADDRESS=127.0.0.1`. `CERT_ALERT_ALLOW_PLAIN_HTTP` is no longer read.
    - **Administrators.** `CERT_ALERT_SECURITY_ADMIN_IDENTIFIERS` now matches uids and entry DNs
      only. Replace any e-mail address with the uid that the startup warning names.
    - **License.** The license, the installation id and the revisions seen are now kept in the
      database. The first start imports them from the license volume, so the id is unchanged.
    - **Sessions** are kept in the database, and the cookie is now `SESSION`: everybody signs in
      once more.
    - **Revocation.** With `ENS_X509_REVOCATION` unset, client certificates are now checked at
      sign-in, soft-fail. `ENS_X509_REVOCATION=off` restores the old behaviour.

    Read the upgrade notes in the package's `README.md` before upgrading.

### Podman: rootless and boot

The same files work with Podman, rootful (`sudo`) or rootless. The compose file names its images
fully, because Podman has no default registry.

- **Health checks need systemd.** Podman runs them from systemd timers, and PostgreSQL must be
  healthy before CertAlert starts. Rootless, log in normally (not with `su`) so that your user
  has a systemd session.
- **Ports from 1024.** Rootless Podman cannot publish 80 or 443 unless
  `net.ipv4.ip_unprivileged_port_start` is lowered. Keep `CERT_ALERT_PORT` at 8080 and put a
  proxy on 443. `install.sh` checks this.
- **Keep it running after logout:** `sudo loginctl enable-linger $USER`.
- **Start at boot.** Podman has no daemon to restart containers. Run `podman-compose up -d` in the
  package directory from a systemd unit (a user unit, with linger, when rootless), or generate
  units with `podman generate systemd --new --files --name <container>`, or write Quadlet
  `.container` files (Podman 4.4+).
- **SELinux.** The `tls/` and `email-templates/` mounts carry `:Z`, so Podman relabels them. The
  database, license and audit data are named volumes and need no labels.
- **File ownership.** CertAlert runs as uid 185. Rootless, files in `tls/` and
  `email-templates/` appear as owned by root inside the container: run
  `chmod -R a+rX tls email-templates`, or `podman unshare chown -R 185:0 tls email-templates`.

Moving an install from Docker to Podman is a new install plus a database restore (each engine
has its own volumes), not an upgrade.

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
  adminIdentifiers: [pki-admin]            # uids (or entry DNs)
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
| `security.adminIdentifiers` | Who administers CertAlert: uids or entry DNs. An address is ignored |
| `replicaCount` | `1`. More than one needs the license state in the database: see [Upgrade](#6-upgrade) |
| `security.ldap.userSearchBase`, `.userSearchFilter`, `.userDnPatterns` | How password sign-in finds a user |
| `database.host`, `.port`, `.name`, `.username`, `.params` | The external PostgreSQL |
| `mail.host`, `.port`, `.username` | SMTP |
| `notifications.email.*`, `alerts.email.*` | The round-up and the operator alerts |
| `tls.*` | TLS in the pod, for client-certificate sign-in |
| `caTruststore.*` | A private CA for the directory, SMTP or Splunk |
| `x509.*` | Client certificates: `source` (`servlet` or `header`), `header`, `trustedProxies`, `proxySecret.*`, `trustedCa.*`, `revocation`, `ocspResponderUrl`, `revocationFail`, `revocationCache`, `allowedIssuers`, `userFilter` |
| `session.*` | `idleTimeout`, `adminIdleTimeout`, `absoluteTimeout`, `maxPerUser`. Empty keeps the defaults |
| `consentBanner.*` | `enabled`, `title`, `text`. For `ENS_CONSENT_BANNER_FILE`, mount the file with `extraVolumes` and pass it in `extraEnv` |
| `banner.*`, `thresholds.*` | Look, expiry windows |
| `revocation.*` | `defaultOcspUrl`, `defaultCrlUrl`, `allowedHosts` (the only hosts CRLs and OCSP answers are fetched from) |
| `metrics.*` | `enabled` (default `true`) gives the actuator its own port, `port` (`9090`), plain HTTP, with health and Prometheus open there and the probes moved to it. `serviceMonitor.*` renders a Prometheus Operator ServiceMonitor; `allowFrom` admits your Prometheus through the NetworkPolicy |
| `logFormat` | `json` (default): ECS JSON logs. `text`: Spring Boot's ordinary lines |
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
| Header-mode proxy secret | `x509.proxySecret.value` | `proxy-secret` |
| Header-mode CA certificates | `x509.trustedCa.value` | `ca.pem` |

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
yet**, the same as the package's `install.sh`, and the chart also passes it to CertAlert as
`ENS_LICENSE`, which installs it into the database if none is there or it is a newer revision. A license uploaded later on the license screen is
never overwritten by a `helm upgrade`. To replace an installed license, upload the new one on the
license screen.

Or give the chart no license, and upload it on the license screen after the first start.

!!! warning "Keep the license PVC"
    The license PVC (`cert-alert-license` for a release named `cert-alert`, mounted at
    `/var/lib/ens`) holds the license, the installation id and the revisions seen, until an
    upgraded installation has imported them into the database. `helm upgrade`, `helm uninstall`
    and an Argo CD prune all keep it (`helm.sh/resource-policy: keep`). Delete it yourself only
    once you are sure the installation id is in the database or no longer wanted.

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

    With `x509.source: servlet` (the default), CertAlert reads the client certificate from its
    own TLS handshake, so **TLS must pass through to the pod**. For TLS at the ingress, use header
    mode (next tab).

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

=== "X.509 client certificates (header mode)"

    The ingress controller terminates TLS, verifies the client certificate and forwards it in a
    header with a shared secret. CertAlert believes it only from `x509.trustedProxies`, with the
    secret, and when it chains to `x509.trustedCa`.

    ```bash
    kubectl -n cert-alert create secret generic cert-alert-x509-proxy \
      --from-literal=proxy-secret="$(openssl rand -hex 32)"
    kubectl -n cert-alert create secret generic dod-cas --from-file=ca.pem=dod-cas.pem
    ```

    ```yaml
    x509:
      source: header
      header: ssl-client-cert
      trustedProxies: "10.42.0.0/16"          # the ingress controller's pods
      proxySecret: {existingSecret: cert-alert-x509-proxy}
      trustedCa: {existingSecret: dod-cas}
      revocation: ocsp-then-crl
    ingress:
      enabled: true
      className: nginx
      annotations:
        nginx.ingress.kubernetes.io/auth-tls-secret: ingress-nginx/dod-cas
        nginx.ingress.kubernetes.io/auth-tls-verify-client: "optional"
        nginx.ingress.kubernetes.io/auth-tls-pass-certificate-to-upstream: "true"
    ```

    The controller must also send `X-ENS-Proxy-Secret` with the secret's value: with
    ingress-nginx, `proxy_set_header X-ENS-Proxy-Secret "<secret>";` in a configuration snippet
    (where your controller allows snippets) or in the controller's `proxy-set-headers` ConfigMap.
    Use `networkPolicy` so only the controller reaches the pod. The chart refuses to render header
    mode without the proxy secret and the CA, and sets `SERVER_FORWARD_HEADERS_STRATEGY=framework`
    whenever its ingress or an edge or reencrypt Route terminates TLS.

    If the controller reaches pods over IPv6, add its IPv6 range to `trustedProxies`: IPv4 ranges
    never match IPv6 addresses.

### 6. Upgrade

```bash
helm upgrade cert-alert oci://ghcr.io/winllc/charts/cert-alert --version X.Y.Z \
  --namespace cert-alert -f cert-alert-values.yaml
```

Back up the database first. With one replica the pod is replaced (`Recreate`); with more, they
are rolled one at a time. The schema is migrated on start, and the license PVC is kept.
Migrations are not undone by `helm rollback`: going back means restoring the backup.

!!! note "Before you upgrade"
    - **Administrators.** `security.adminIdentifiers` now matches a uid or an entry DN only. An
      e-mail address there is ignored; the NOTES and the startup log name the uid to use.
    - **Revocation.** `x509.revocation` and `x509.revocationFail` now default to `""`: client
      certificates are checked live at sign-in, soft-fail. Set `revocation: "off"` to keep the
      check off.
    - **Sessions** are kept in the database: everybody signs in once more after the upgrade.
    - **`strategy`** defaults to `{}`: `Recreate` for one replica, `RollingUpdate` above one.
    - **Several replicas.** The license state moves into the database on the first start,
      imported from the license PVC. Upgrade with one replica first, then set
      `persistence.license.imported: true` and raise `replicaCount`. The chart refuses more than
      one replica without it (or a ReadWriteMany license volume).
    - **Metrics and logs.** `metrics.enabled` is `true` (the actuator and the probes on port
      `9090`, plain HTTP) and `logFormat` is `json`.

### 7. Uninstall

```bash
helm uninstall cert-alert --namespace cert-alert
```

This keeps the license PVC the chart created, a PVC you supplied with
`persistence.license.existingClaim`, and the evaluation database's PVC
(`data-cert-alert-postgresql-0`). Delete them yourself with `kubectl delete pvc` if you mean to.

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

## Trying it out: the demo

!!! danger "Not for production"
    The demo is for showing CertAlert, never for running it. Its sign-in page prints working
    credentials, and it must never be pointed at a real directory.

The CertAlert source repository has a `docker-compose.demo.yml` at its root. It builds the image
from source and runs it in the `demo` profile: one container that generates a directory of
invented people and servers, serves it from memory, and indexes it. Nothing can be changed,
whoever signs in. The sign-in page lists one account per role (`demo.admin`, `demo.project`,
`demo.contact`, `demo.reader`), all with the password `password`.

1. Download an evaluation license for CertAlert from the ENS licensing portal, and save it as
   `license/license.lic` in the repository. Or skip this and upload it later as `demo.admin`.
2. Start it, always with `-p cert-alert-demo` so it never shares volumes with another stack:

    === "Docker"

        ```bash
        docker compose -f docker-compose.demo.yml -p cert-alert-demo up --build
        ```

    === "Podman"

        ```bash
        podman-compose -f docker-compose.demo.yml -p cert-alert-demo up --build
        ```

3. Reach it. **The demo publishes no port.** It listens on 8080 inside its own network,
   `cert-alert-demo_demo`. Either attach a reverse proxy to that network and point it at
   `cert-alert:8080`, or write an override file of your own that binds it to this host only:

    ```yaml
    # demo-ports.yml
    services:
      cert-alert:
        ports: ["127.0.0.1:8080:8080"]
    ```

    Add `-f demo-ports.yml` after `-f docker-compose.demo.yml` and open <http://localhost:8080>.

4. Reset it, license included, with `down -v` and the same `-f` and `-p` options.

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
- The license volume is `/var/lib/ens/certalert` (`ENS_LICENSE_STATE_DIR`, `ENS_LICENSE_FILE`),
  which must be persistent and writable. The license state itself is kept in the database, imported
  from there on the first start after an upgrade.
- Probe `/actuator/health/liveness` and `/actuator/health/readiness` (or `/livez` and `/readyz`
  on the application's port), not the aggregate `/actuator/health`, which turns `DOWN` when the
  directory is unreachable. The image's own `HEALTHCHECK` calls `/livez`. See
  [Health checks](operator-guide.md#health-checks).
- The image's license label (`org.opencontainers.image.licenses`) is `LicenseRef-ENS-Commercial`.
- `.dockerignore` excludes `*.p12`, `*.jks`, `*.pem`, `*.key` and `*.lic`, so key material and
  licenses cannot be baked in. Mount keystores as a volume or Secret.

## First start checklist

1. **Database**: the log shows Flyway applying its migrations to an empty database.
2. **Liveness**: `curl -fsS http://<host>:8080/livez` returns `{"status":"UP"}`. With Helm,
   `helm test cert-alert -n cert-alert` checks it through the Service.
3. **Directory**: `/actuator/health` includes the LDAP indicator. It reports `DOWN` if the
   directory is unreachable (details are shown only to administrators). Readiness does not
   include the directory.
4. **Sign in** with a directory password as one of the admin identifiers (by uid). The account menu should
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
9. **For DoD systems**: the consent banner appears before sign-in
   (`ENS_CONSENT_BANNER_ENABLED=true`), and the log has no warning about `ENS_X509_REVOCATION`
   being off.
10. **Backups** are scheduled: see [Operator guide → Backups](operator-guide.md#backups).

Next: [Configuration](configuration.md) and the [Operator guide](operator-guide.md).
