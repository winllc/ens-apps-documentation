# Installation

You install the Directory Services Portal from a release, in one of two ways:

- **The release package.** A signed download for a single host: the Docker package (images,
  compose files, settings template and scripts), or the native package for a Linux host without
  containers.
- **The Helm chart.** For Kubernetes and OpenShift, published with every release.

Both run the same two components: the **API** (Spring Boot, port 3001) and the **web tier**
(nginx serving the web client and forwarding `/api` to the API). Browsers only talk to the web
tier.

## Prerequisites

### For every installation

| Component | Requirement |
|---|---|
| LDAP server | Any LDAPv3 server reachable over `ldap://` or `ldaps://` (optional StartTLS) |
| Service account | An LDAP account that can read the schema (root DSE `subschemaSubentry`) and the namespaces you expose, and write wherever portal users should be able to edit |
| Administrators | The users (`ADMIN_USERS`) or groups (`ADMIN_GROUPS`) who will administer the portal |
| License | An ENS license file (`.lic`). For a trial, download an evaluation license from the ENS licensing portal. See [Licensing](../licensing.md) |
| Release | The release download from ENS Solutions, verified as described in [Releases](../releases.md) |

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

### For the release package

| Package | Requirement |
|---|---|
| Docker | Linux with Docker Engine 20.10+ and Docker Compose v2 (or Podman with Compose v2), and about 2 GB of disk |
| Native | Linux with systemd and Java 21 or later; nginx is optional (the installer configures it if it is installed) |

### For Helm

| Component | Requirement |
|---|---|
| Cluster | Kubernetes 1.25 or later, or OpenShift 4 |
| Helm | Helm 3.8 or later (OCI registries) |
| Storage | A StorageClass for two small ReadWriteOnce volumes (license: 100 Mi, data: 2 Gi by default) |
| Images | Access to `ghcr.io/winllc`, or a registry mirror for an air-gapped cluster |

## Choosing a method

| Method | Use it for | You run |
|---|---|---|
| [Docker package](#install-from-the-release-package) | One host with Docker, including sites without internet access | `install.sh`, `portal.sh`, `upgrade.sh` |
| [Native package](#native-package) | One Linux host without containers | `install.sh`, `systemctl` |
| [Helm chart](#install-with-helm) | Kubernetes and OpenShift | `helm install`, `helm upgrade` |

## Install from the release package

### Download and verify

Each release comes as these files (see [Releases](../releases.md)):

| File | What it is |
|---|---|
| `directory-services-portal-<version>.tar.gz` | The Docker package, with the images |
| `directory-services-portal-<version>-native.tar.gz` | The native (systemd) package |
| `directory-services-portal-<version>.tgz` | The Helm chart |
| `sbom-*.spdx.json` | SBOMs of the two images |
| `SHA256SUMS`, `SHA256SUMS.asc` | Checksums, and ENS Solutions' signature over them |

Verify the download before you unpack it:

```bash
gpg --verify SHA256SUMS.asc SHA256SUMS
sha256sum -c --ignore-missing SHA256SUMS
```

### Docker package

Unpack the package and run the installer:

```bash
tar xzf directory-services-portal-<version>.tar.gz
cd directory-services-portal-<version>
./install.sh --http 8080          # plain HTTP on port 8080, e.g. behind your TLS proxy
vi .env                           # LDAP connection and administrators
./portal.sh up
```

Choose how the portal is published:

=== "Plain HTTP"

    ```bash
    ./install.sh --http 8080
    ```
    Adds `docker-compose.publish.yml`, which publishes the web tier on `WEB_BIND:WEB_PORT`. Put a
    TLS-terminating proxy in front of it, and set `ENS_COOKIE_SECURE=true` in `.env`.

=== "HTTPS + X.509"

    ```bash
    ./install.sh --https 443
    ```
    Adds `docker-compose.mtls.yml`: nginx serves HTTPS on `HTTPS_PORT` with
    `certs/server.pem`, `certs/server.key` and `certs/ca.pem`, and the installer sets
    `X509_ENABLED=true` and `ENS_COOKIE_SECURE=true`. Plain HTTP on 8080 redirects to HTTPS. nginx
    forwards a verified certificate to the API together with `X509_PROXY_SECRET` from `.env`; the
    API accepts a forwarded certificate only with that secret, and checks it against
    `certs/ca.pem` too.

=== "Nothing published"

    ```bash
    ./install.sh
    ```
    Only `docker-compose.yml`. Attach your own reverse proxy to the compose network and route it
    to `web:8080`.

=== "Demo"

    ```bash
    ./install.sh --demo --http            # built-in in-memory demo directory
    ```
    The demo directory needs no LDAP server. It has demo users (`admin`, `alice`, `bob`, `erin`,
    `dave`, `frank`, password `password`) and example forms, directories and dashboards. Entries
    are lost when the API stops. The demo needs a license too.

`install.sh` options:

| Option | Meaning |
|---|---|
| `--http [PORT]` | Publish plain HTTP on PORT (default 8080) |
| `--https [PORT]` | Publish HTTPS on PORT (default 8443), X.509 sign-in available; needs `./certs` |
| `--demo` | Built-in demo directory (no LDAP server), demo users and dashboards |
| `--demo-openldap` | A demo OpenLDAP container (`docker-compose.openldap.yml`) loaded with the demo organisation, when the package includes its image |
| `--force-env` | Rewrite `.env` (the old one is kept as `.env.bak-<time>`) |
| `--start` | Run `./portal.sh up` afterwards |
| `--skip-verify` | Don't check `SHA256SUMS` |

The installer checks for Docker and Compose v2, verifies the checksums, and loads the images
from `images/`. It writes `.env` with mode `600`, generates a random `X509_PROXY_SECRET` in it
(also into an existing `.env` that has none), and validates the result with
`docker compose config`. It **never overwrites an existing `.env`** unless you pass
`--force-env`.

!!! tip "Certificate files for `--https`"
    | File | Content |
    |---|---|
    | `certs/server.pem` | Server certificate (with chain) |
    | `certs/server.key` | Its private key, readable by uid 1001 (`chmod 644`, or `chgrp 0` + `chmod 640`) |
    | `certs/ca.pem` | CA(s) that issue user certificates |

    For a test, run `scripts/generate-dev-certs.sh certs` from the package. It creates a
    throw-away CA, a server certificate for `localhost`, and user certificates (`certs/alice.p12`,
    password `password`).

#### Fill in `.env`

Every setting is in `.env`, with comments. After a change, run `./portal.sh up`. The first
section chooses the compose files (`COMPOSE_FILE`) and ports. Set at least the directory
connection and the administrators (see [Connecting to your directory](#connecting-to-your-directory)).
All settings are described in [Configuration](configuration.md).

`.env` holds the bind password and the generated `X509_PROXY_SECRET`, so keep it private (mode
`600`). Leave `X509_PROXY_SECRET` as generated; to change it, set a new value of at least 32
characters (for example `openssl rand -hex 32`) and run `./portal.sh up`.

The `.env` written by this release also has these sections; they are described in
[Configuration](configuration.md):

| Section | Settings |
|---|---|
| Sessions and lockout | `ENS_SESSION_IDLE_TIMEOUT` (`30m`), `ENS_SESSION_ADMIN_IDLE_TIMEOUT`, `ENS_SESSION_ABSOLUTE_TIMEOUT` (`12h`), `ENS_SESSION_MAX_PER_USER` (`0`), `GROUP_REFRESH_INTERVAL` (`5m`), `LOGIN_MAX_FAILURES` (`8`), `LOGIN_LOCKOUT_WINDOW` (`15m`) |
| Headers and cookies | `ENS_COOKIE_SECURE`, `ENS_CSP_REPORT_ONLY` |
| Consent banner | `ENS_CONSENT_BANNER_ENABLED`, `_TITLE`, `_TEXT`, `_FILE` |
| X.509 revocation | `ENS_X509_REVOCATION`, `ENS_X509_OCSP_RESPONDER_URL`, `ENS_X509_CRL_DIR`, `ENS_X509_REVOCATION_FAIL`, `ENS_X509_REVOCATION_CACHE`, `ENS_X509_ALLOWED_ISSUERS` |
| Logs and monitoring | `LOGGING_STRUCTURED_FORMAT_CONSOLE` and `MANAGEMENT_SERVER_PORT`, both commented out |
| Memory | `API_MEMORY_LIMIT` (`1536m`), `WEB_MEMORY_LIMIT` (`256m`) |

An `.env` carried over from an earlier release keeps its old settings and lacks these lines; the
defaults then apply, except that an old `SESSION_TTL_MINUTES` keeps setting the idle timeout.

#### License

Install the license in either of these ways:

- **License screen.** Start the portal and sign in as an administrator. You land on
  *Administration > License* (`/admin/license`). Upload the file there.
- **Command line.** Put the file at `license/license.lic` before you run `./install.sh`. The
  installer copies it into the license volume if that has no license yet. To install or replace
  a license later, run `./portal.sh license /path/to/license.lic`.

The license is kept in the `ens-license` volume, together with this installation's id. **Keep
that volume.** `docker compose down -v` deletes it, and a new installation id means re-binding
the license on the ENS licensing portal.

#### First start

```bash
./portal.sh up
./portal.sh status                # containers, health, URL
```

Other commands:

```bash
./portal.sh logs api              # follow the API log
./portal.sh backup                # portal-backup-<date>.tar.gz: configuration, audit log, analysis results
./portal.sh restore <file>
./portal.sh down                  # stop (data is kept in the portal-data volume)
```

#### Upgrade

Unpack the new package next to the running one and run its `upgrade.sh` with the old
directory:

```bash
tar xzf directory-services-portal-<new-version>.tar.gz
cd directory-services-portal-<new-version>
./upgrade.sh ../directory-services-portal-<old-version>
```

`upgrade.sh` copies `.env`, `license/` and `certs/` across (it never overwrites), verifies the
package and loads its images, generates `X509_PROXY_SECRET` if the old `.env` has none, and backs
up the portal data to `backups/`. It then points `.env`
at the new images (keeping a `.env.bak-<time>`) and restarts with `./portal.sh up`. The data
volume and the license volume are reused. To go back, run `./portal.sh up` in the old directory
and, if needed, `./portal.sh restore` the backup.

### Native package

For a Linux host with systemd, Java 21+ and, optionally, nginx:

```bash
tar xzf directory-services-portal-<version>-native.tar.gz
cd directory-services-portal-<version>-native
sudo ./install.sh                     # add --start to start it right away
sudo vi /etc/directory-services-portal/portal.env
printf '%s' 'the-password' | sudo tee /etc/directory-services-portal/ldap-bind-password >/dev/null
sudo systemctl restart directory-services-portal
```

The installer verifies `SHA256SUMS`, creates the system user `dsp` and installs:

| Installed to | Purpose |
|---|---|
| `/opt/directory-services-portal/api/directory-services-portal.jar` | The API, run by systemd as user `dsp` on `127.0.0.1:3001` |
| `/opt/directory-services-portal/web` | The web client (static files) |
| `/etc/directory-services-portal/portal.env` | Settings (mode `640`, `root:dsp`); kept on upgrades. A new `portal.env` sets `LDAP_BIND_PASSWORD_FILE` and `MANAGEMENT_SERVER_ADDRESS=127.0.0.1` |
| `/etc/directory-services-portal/ldap-bind-password` | The LDAP service account's password (mode `640`, `root:dsp`), read through `LDAP_BIND_PASSWORD_FILE`. Created empty if missing and never overwritten; write the password into it. An upgraded `portal.env` that still sets `LDAP_BIND_PASSWORD` keeps working |
| `/etc/directory-services-portal/proxy-secret` | The X.509 proxy secret (mode `640`, `root:dsp`), generated once and kept. The API reads it through `X509_PROXY_SECRET_FILE`, set in the systemd unit |
| `/etc/directory-services-portal/nginx-proxy-secret.conf` | The copy of the proxy secret that nginx includes (mode `600`, `root`), rewritten from `proxy-secret` on every run |
| `/var/lib/directory-services-portal` | Data: configuration, audit log, analysis results (back this up) |
| `/var/lib/ens/directory-portal` | The ENS license and this installation's id; keep it |
| `/etc/systemd/system/directory-services-portal.service` | The systemd unit, hardened: `ProtectSystem=strict` with `ReadWritePaths` limited to the data and license directories, no capabilities, a system-call filter, `UMask=0077` and more (see [Security](security.md#network-exposure)) |
| `/etc/nginx/conf.d/directory-services-portal.conf` (or `sites-enabled/`) | Serves the client on port 8080 and proxies `/api` to `127.0.0.1:3001` (only when nginx is installed) |

- **License:** sign in as an administrator and upload it under *Administration > License*, or
  copy it in with
  `sudo install -m 640 -o dsp -g dsp license.lic /var/lib/ens/directory-portal/license.lic`.
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

- **Upgrade:** run the new package's `install.sh`. Settings and data are kept. It generates the
  X.509 proxy secret if there is none yet; restart the API afterwards
  (`systemctl restart directory-services-portal`).

## Install with Helm

The chart installs two Deployments: the API (one replica, `Recreate` strategy) and the web tier.
The web tier's Service, `<release>-directory-services-portal`, is what users reach. The API keeps
two PersistentVolumeClaims: the **license volume** (`/var/lib/ens`) and the **data volume**
(`/data`: forms, definitions, grants, dashboards, analyses and the audit store). There is no
database.

### Install

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

### Key values

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
| `x509.revocation.*`, `x509.allowedIssuers` | `mode` (`off`), `ocspResponderUrl`, `crlDir`, `fail` (`closed`), `cache` (`10m`), and `egress` (ports 80 and 443) for OCSP and CRL fetches under a NetworkPolicy |
| `persistence.license`, `persistence.data` | The two volumes: `size`, `storageClass`, `accessModes`, `existingClaim` |
| `ingress.*`, `route.*`, `service.*` | How users reach the portal |
| `networkPolicy.*` | Optional NetworkPolicies for both workloads; required for edge-terminated X.509 sign-in |
| `api.*`, `web.*` | Images, resources, probes, scheduling, `extraEnv`, `extraEnvFrom` |
| `global.imageRegistry`, `imagePullSecrets` | Registry mirror and pull secrets |

Every setting from the Docker package's `env.template` has a value; the
[Configuration](configuration.md) page describes what each one does. Anything else goes in
`api.extraEnv`.

!!! note "One API replica"
    The API is the single writer of its configuration, audit store and license state, and keeps
    sessions in memory. Run one replica (`api.replicaCount` accepts only 0 or 1). The web tier is
    stateless and can run more replicas.

### Secrets

Secrets are never put in a ConfigMap. Each one is either inline in your values (the chart
stores it in its own Secret) or taken from a Secret you create:

| Secret | Inline value | Existing Secret |
|---|---|---|
| LDAP bind password (mounted into the API pod as a file, `LDAP_BIND_PASSWORD_FILE`) | `ldap.bindPassword.value` | `ldap.bindPassword.existingSecret`, `.existingSecretKey` |
| Splunk HEC token | `audit.splunk.token.value` | `audit.splunk.token.existingSecret`, `.existingSecretKey` |
| LDAPS trust store password | `ldap.truststore.password.value` | `ldap.truststore.password.existingSecret`, `.existingSecretKey` |
| ENS license | `license.file` (`--set-file`) | `license.existingSecret` (key `license.lic`) |
| Web server certificate | `tls.certificate`, `tls.key`, `tls.clientCa` | `tls.existingSecret` (keys `server.pem`, `server.key`, `ca.pem`) |
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

### License

Without a license, only sign-in and the license screen work. You can:

- **Upload it on the license screen.** Sign in as an administrator. You land on
  *Administration > License* (`/admin/license`).
- **Give it to the chart.** Use `--set-file license.file=./license.lic`, or a Secret with the key
  `license.lic` in `license.existingSecret`. An init container copies it into the license volume
  **only if no license is installed there yet**. A later `helm upgrade` therefore never
  overwrites a newer license uploaded on the license screen.

The license screen shows the **installation id**, which you enter on the ENS licensing portal to
bind a license to this installation. It is also in the API's startup log:

```bash
kubectl logs -n dsp deploy/dsp-directory-services-portal-api -c api | grep License:
```

!!! warning "Keep the license volume"
    The license PVC (`<release>-directory-services-portal-license`) holds the installation id.
    `helm upgrade` keeps it. If it is lost, the installation gets a new id and bound licenses
    must be re-bound. For installations you might reinstall, create the PVCs yourself and set
    `persistence.license.existingClaim` and `persistence.data.existingClaim`.

### Ingress, OpenShift and HTTPS

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

### X.509 smart-card sign-in

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

### Upgrade

```bash
helm upgrade dsp oci://ghcr.io/winllc/charts/directory-services-portal --version X.Y.Z -n dsp -f my-values.yaml
```

The old API pod stops before the new one starts. The PVCs, and with them the license, the
installation id and the data, are kept. Back up the data volume before you upgrade.

With `x509.enabled`, the upgrade generates the X.509 proxy secret if the release has none yet,
and restarts both workloads. Before upgrading an edge-terminated setup (`x509.enabled` without
`tls.enabled`), set `x509.trustedCa`, `networkPolicy.enabled` and `networkPolicy.webIngressFrom`
(or `x509.allowUnrestrictedWebIngress`): otherwise the upgrade stops with a message saying so.

### Uninstall

```bash
helm uninstall dsp -n dsp
```

This deletes the PVCs the chart created: the license (with the installation id), the data and
the optional audit file volume. Back them up first if you may reinstall. PVCs given with
`existingClaim` are never touched.

## Connecting to your directory

For any installation that isn't a demo, set at least these values: in `.env` (Docker package),
`portal.env` (native package), or the chart values shown next to them.

| Setting | Helm value | Example |
|---|---|---|
| `DIRECTORY_MODE` | `directory.mode` | `ldap` |
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
