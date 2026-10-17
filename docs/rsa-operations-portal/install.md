# Installation

This page covers a new installation of the RSA Operations Portal, preparing RSA Authentication Manager (AM), the first start, verification and upgrades.

There are two supported ways to install the Portal:

* the **release package**, a signed download that runs the Portal with Docker Compose on one Linux server;
* the **Helm chart**, for Kubernetes and OpenShift.

Both run the same container images, which CI builds, tests and publishes for each release. Building the images yourself is not an installation method.

Every installation has three components:

| Component | Role |
|---|---|
| Portal | The web application: UI and business logic. |
| Connector | The service the Portal calls for everything that involves RSA AM. It is the only component that connects to AM. |
| MySQL 8.0 | The Portal's database. |

## Prerequisites

### Common

| Item | Requirement |
|---|---|
| Network: Connector → RSA AM | TCP 7002 (HTTPS command server and `t3s`) on the primary, plus the replica if it serves the read-only URL |
| Network: Portal → SMTP relay | TCP 25 by default (`spring.mail.port`). The Portal checks the relay when it starts. |
| Network: Portal → AD (optional) | The LDAP URL in `active-directory.properties[n].providerURL` |
| Network: users → Portal | HTTP or HTTPS to the Portal's address (8080 and 8443 with the release package) |
| ENS license | A `.lic` file for the RSA Operations Portal (`mfa-portal`) from the ENS licensing portal. See [Licensing](../licensing.md). You can install it after the first start. |

You also need, from RSA AM:

* the AM **root CA certificate**, imported into a Java keystore `trust.jks` ([steps](#export-the-am-root-certificate-and-build-trustjks));
* the **Command Client** user name and password ([steps](#get-the-command-client-credentials));
* an **internal AM user with Super Admin** permissions for `rsa.admin.user.id`;
* the **GUIDs of the AM administrative roles** that grant Trusted Agent, PMO and Help Desk access ([steps](#find-the-administrative-role-guids)).

### For the release package

| Item | Requirement |
|---|---|
| Operating system | Linux on x86-64 |
| Software | Docker Engine 23 or later and the Docker Compose plugin (`docker compose`). `install.sh` checks for both. |
| Ports | 8080 (HTTP) and 8443 (HTTPS) free on the server |
| Image access | None. The images are in the package's `images/` folder. |
| Tools | `sha256sum` and `gpg` to verify the download |

### For Helm

| Item | Requirement |
|---|---|
| Cluster | Kubernetes 1.25 or later, or OpenShift 4.12 or later |
| Helm | Helm 3.8 or later (OCI registry support) |
| Storage | A default StorageClass, or one you name: a 100Mi ReadWriteOnce volume for the license, and 8Gi for the embedded MySQL if you use it |
| Database | An external MySQL 8.0 server. The embedded MySQL is for evaluations only. |
| Image access | Pull access to `ghcr.io/winllc/rsa-operations-portal` and `ghcr.io/winllc/rsa-operations-portal-connector`, or a mirror of them ([air-gapped](#air-gapped-clusters)) |
| Ingress | An ingress controller or OpenShift Routes, to reach the Portal from outside the cluster |

## Choosing a method

| | Release package | Helm chart |
|---|---|---|
| Runs on | One Linux server with Docker | Kubernetes or OpenShift |
| Database | MySQL container in the package | External MySQL 8.0 (embedded MySQL for evaluations only) |
| Settings | `.env`, `connector.env` and `mysql.env`, created from their templates | Helm values; secrets in Kubernetes Secrets |
| Offline install | Yes, the images are in the package | Yes, with the chart from the release download and mirrored images |
| Upgrade | `upgrade.sh` | `helm upgrade` |
| Backups | `upgrade.sh` backs up the database; `util/backupdb.sh` | Your database's own backups |

## Install from the release package

### 1. Download and verify

ENS Solutions sends you links to the release files. See [Releases and downloads](../releases.md). Verify them before you install:

```bash
gpg --verify SHA256SUMS.asc SHA256SUMS   # needs the ENS release signing key, imported once
sha256sum -c SHA256SUMS                  # every file listed must say OK
```

The release files are:

| File | What it is |
|---|---|
| `rsa-operations-portal-<version>.tar.gz` | The release package |
| `rsa-operations-portal-<version>.tgz` | The Helm chart (not needed for this method) |
| `sbom-*.spdx.json` | Software bill of materials for each image |
| `SHA256SUMS`, `SHA256SUMS.asc` | Checksums and their signature |

### 2. Unpack

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
| `license/` | Where you can put `license.lic` for `install.sh` and `upgrade.sh` to copy into the Portal |
| `default_build/` | Branding, page text and e-mail templates the Portal mounts. `ssl/` holds the trust store. |
| `util/` | `check-env.sh` (checks `.env`, `connector.env` and `mysql.env`), `split-env.sh` (splits a `.env` from an older release) and `backupdb.sh` (database backup) |
| `images/` | The Portal, Connector and MySQL images (`docker save` archive) |
| `README.md`, `GUIDE.md` | The package's quick start and the full administration guide |

### 3. Run install.sh

```bash
./install.sh /opt/rsa-operations-portal
```

`install.sh` copies the package to the install folder, loads the images and creates `.env`, `connector.env` and `mysql.env` from their templates (each mode 600). Then it stops and tells you what to fill in. Run `./install.sh` without a folder to install in the unpacked folder instead.

### 4. Fill in the settings

In `/opt/rsa-operations-portal`, replace every `CHANGE_ME` in the three settings files. Each service receives only its own file. [Configuration](configuration.md) describes every setting. At a minimum:

`mysql.env` (MySQL):

- [ ] `MYSQL_ROOT_PASSWORD`, `MYSQL_PASSWORD` (`MYSQL_DATABASE` is `RSAAPP` and `MYSQL_USER` is `appuser`)

`.env` (the Portal):

- [ ] `spring.datasource.password`, the same as `MYSQL_PASSWORD` in `mysql.env`
- [ ] `CONNECTOR_AUTH_PASSWORD`, any long random value: the Portal uses it to authenticate to the Connector
- [ ] `role.mapped.1.rsaSecurityGuid` (TRUSTED_AGENT), `role.mapped.2.rsaSecurityGuid` (PMO), `role.mapped.3.rsaSecurityGuid` (HELP_DESK)
- [ ] `spring.mail.host`, `mail.smtp.host`, `mail.default.from`, `mfa.group.email`
- [ ] `application.dns.name`: the URL users will reach

`connector.env` (the Connector):

- [ ] `CONNECTOR_AUTH_PASSWORD`, the same value as in `.env`
- [ ] In `JAVA_OPTS`, the AM settings ([RSA Authentication Manager settings](#rsa-authentication-manager-settings)). Keep `-Dconfig.properties.location=classpath:config.properties` as it is.

Never put the AM settings or the MySQL root password in `.env`: the Portal is the service users reach. `check-env.sh` refuses a `.env` that holds them.

Keep `$` and ` #` out of the values. Docker Compose replaces `$name` with a variable, so a password `$ecret` arrives empty, and ` #` starts a comment. `check-env.sh` reports both.

Put the AM trust store at `/opt/rsa-operations-portal/default_build/ssl/trust.jks` ([steps](#export-the-am-root-certificate-and-build-trustjks)).

Optionally, customise branding in `default_build/properties/messages.properties` and the images in `default_build/static/`. See [Configuration](configuration.md#branding-text-and-email-templates).

### 5. License

Optional at this point. Save the license as `/opt/rsa-operations-portal/license/license.lic`, and `install.sh` copies it into the Portal on the first start. Or upload it on the license screen afterwards. See [License](#license).

### 6. Database

Nothing to do. On its first start, the MySQL container creates the database `MYSQL_DATABASE` and the user `MYSQL_USER` with `MYSQL_PASSWORD`, from `mysql.env`. The Portal then creates its tables itself (Hibernate `ddl-auto=update`).

MySQL reads the `MYSQL_*` settings only when its data volume is new. Without `MYSQL_ROOT_PASSWORD` a new volume never initialises, and MySQL restarts with `Database is uninitialized and password option is not specified`. `check-env.sh` reports a missing setting.

### 7. First start

```bash
cd /opt/rsa-operations-portal
./install.sh
```

The second run checks `.env`, `connector.env` and `mysql.env` with `util/check-env.sh`, starts the services with `docker compose up -d` and waits up to 300 seconds (`WAIT_SECONDS`) for the Portal to report healthy. It then copies `license/license.lic` into the Portal if there is one. Open `http://<server>:8080`.

Startup order: `mysql` must pass its `mysqladmin ping` health check, and `connector` must have started, before `portal` starts. On start, the Connector signs in to AM with `rsa.admin.user.id` and looks up the configured realm and security domain. If either lookup fails, the Connector's AM session check fails (see [Troubleshooting](troubleshooting.md)).

Day to day, in the install folder:

```bash
docker compose ps                  # status
docker compose logs -f portal      # logs (also under logs/)
docker compose stop                # stop; docker compose start to start again
```

The database lives in the Docker volume `rsa-operations-portal_mysql_data` and the license in `rsa-operations-portal_ens-license`. `docker compose down -v` deletes both.

### Upgrade with upgrade.sh

Verify and unpack the new release anywhere, and point `upgrade.sh` at the installation:

```bash
tar -xzf rsa-operations-portal-<new version>.tar.gz
cd rsa-operations-portal-<new version>
./upgrade.sh /opt/rsa-operations-portal
```

`upgrade.sh`:

1. loads the new images; nothing changes if they do not load;
2. backs up the database to `backups/RSAAPP-before-<version>-<time>.sql.gz` in the install folder (skip with `--no-backup`);
3. updates the release files: `docker-compose.yml`, `env.template`, `connector.env.template`, `mysql.env.template`, the scripts, `util/`, `default_build/portal/` and `default_build/log4j.xml`;
4. splits a `.env` from a release that kept every setting in that one file (see below);
5. lists, for `.env`, `connector.env` and `mysql.env`, the settings that are new in the file's template and missing from it, and checks the three files;
6. restarts on the new version and waits for the Portal to report healthy.

It keeps `.env`, `connector.env`, `mysql.env`, `license/`, your branding in `default_build/` (`properties`, `static`, `email-templates`, `ssl`), the logs, the database and the installed license (the `ens-license` volume). An installation from before the `ens-license` volume kept its license in `license/license.lic`; `upgrade.sh` copies it into the volume.

The Portal updates its database tables itself on the first start of the new version. To roll back, restore the backup `upgrade.sh` made and run the previous release's `upgrade.sh`. See [Operator Guide](operator-guide.md).

**From a release with a single `.env`.** Older releases kept every setting in `.env` and gave it to every container, so the Portal received the AM credentials and the MySQL root password. `upgrade.sh` splits it with `util/split-env.sh`, and nothing needs editing:

* `JAVA_OPTS`, with the AM settings, and a copy of `CONNECTOR_AUTH_PASSWORD` move to `connector.env`. JVM options of your own in the old `JAVA_OPTS` (`-Xmx…`) are kept for the Portal too, in `.env`.
* The `MYSQL_*` settings move to `mysql.env`.
* The original is kept as `.env.before-split.<time>` (mode 600), and the script prints what it moved. Running it again changes nothing.

After such an upgrade:

1. Once the Portal runs, delete `.env.before-split.*`: it still holds every secret.
2. The Portal held the Command Client and SuperAdmin passwords before the upgrade. If it may have been compromised, change them in AM, then in `connector.env`, and run `docker compose up -d`.

## Install with Helm

The chart is `oci://ghcr.io/winllc/charts/rsa-operations-portal`, with the same version as the release. It is also in the release download as `rsa-operations-portal-<version>.tgz`. The chart installs:

* the Portal as a Deployment with one replica, and a PVC for the license;
* the Connector as a second Deployment, with its own settings and the AM trust store;
* optionally, an embedded MySQL StatefulSet for evaluations.

The chart's `README.md` lists every value. Each setting in the three templates (`env.template`, `connector.env.template`, `mysql.env.template`) has a value.

### 1. Create the Secrets

Create a namespace and the Secrets the chart reads. You can also give each secret inline in your values; the chart then puts it in a Secret of its own. Never put secrets in a ConfigMap.

```bash
kubectl create namespace rsa-portal

# the AM trust store and its password
kubectl -n rsa-portal create secret generic rsa-am-trust \
  --from-file=trust.jks --from-literal=password=<trust store password>

# the Command Client and the Super Admin user
kubectl -n rsa-portal create secret generic rsa-am-credentials \
  --from-literal=cmdclient-user=<command client user> \
  --from-literal=cmdclient-password=<command client password> \
  --from-literal=admin-user-id=<super admin user id> \
  --from-literal=admin-user-password=<super admin password>

# the database password
kubectl -n rsa-portal create secret generic rsa-portal-db --from-literal=password=<database password>
```

The Portal-to-Connector password needs no Secret: the chart generates one and keeps it across upgrades. To set your own, use `connector.auth.existingSecret`.

The Connector receives its AM settings as `-D` options in `JAVA_OPTS`, as in the release package's `connector.env`. Tomcat's start script splits and evaluates `JAVA_OPTS`, so keep spaces, quotes, `` ` `` and `$` out of the AM passwords.

### 2. Prepare the database

Use an external MySQL 8.0 server. Create the database and its user before the first install:

```sql
CREATE DATABASE IF NOT EXISTS `RSAAPP`;
CREATE USER 'appuser'@'%' IDENTIFIED BY '<password>';
GRANT ALL PRIVILEGES ON RSAAPP.* TO 'appuser'@'%';
```

The Portal creates and updates its tables itself on start (Hibernate `ddl-auto=update`). No schema job is needed. The chart connects with `jdbc:mysql://<database.host>:3306/RSAAPP?sslMode=REQUIRED`, so the server must accept TLS; set `database.params` or `database.url` otherwise.

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
| `authManager.commandClient.*` | `com.rsa.cmdclient.user`, `com.rsa.cmdclient.user.password` |
| `authManager.adminUser.*` | `rsa.admin.user.id`, `rsa.admin.user.password` |
| `authManager.trustStore.*` | `trust.jks`, `ims.ssl.client.trust.keystore.password`, `com.rsa.ssl.trust.store.password` |
| `authManager.realm`, `.securityDomain` | `rsa.authmanager.realm.name`, `rsa.authmanager.securityDomain.name` (default `SystemDomain`) |
| `roles.trustedAgentGuid`, `.pmoGuid`, `.helpDeskGuid` | `role.mapped.1-3.rsaSecurityGuid` |
| `mail.host`, `.port`, `.from`, `.mfaGroupEmail` | `spring.mail.host`, `mail.smtp.host`, `mail.default.from`, `mfa.group.email` |
| `publicUrl` | `application.dns.name` (default: the first ingress or route host) |
| `activeDirectory.*` | `active-directory.*` |
| `cronTimezone`, `securityBanner.*` | `cron.timezone`, `security-banner.*` |
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

### 5. License

Upload the license on the license screen after the first start, or give it to the chart:

```bash
helm upgrade --install rsa-portal oci://ghcr.io/winllc/charts/rsa-operations-portal --version <version> \
  -n rsa-portal -f my-values.yaml --set-file license.file=./license.lic
```

Or create a Secret with the key `license.lic` and set `license.existingSecret`. An init container copies the license into the license volume **only when no license is installed there yet**, as `install.sh` does. A license uploaded later on the license screen is never overwritten by `helm upgrade`.

The license, the installation id and the license history live on the PVC `<release>-rsa-operations-portal-license`, mounted at `/var/lib/ens/mfa-portal`. **Keep this PVC.** If it is lost, the installation gets a new id, and a license bound to the old id must be re-bound on the ENS licensing portal.

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

**OpenShift**: set `openshift.enabled: true`, so the restricted SCC assigns the user and group ids, and use a Route instead of an Ingress:

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

### 7. Upgrade

```bash
helm upgrade rsa-portal oci://ghcr.io/winllc/charts/rsa-operations-portal --version <new version> \
  -n rsa-portal -f my-values.yaml
```

Back up the database first. The Portal updates its tables on the first start of the new version, and `helm rollback` restores the previous images and settings, not the previous database. The license PVC, and with it the installation id and an uploaded license, is kept.

Keep `replicaCount: 1`. The license volume is single-writer, and sign-in sessions are held in the Portal's memory. The Portal Deployment uses the `Recreate` strategy, so the old pod releases the volume before the new one starts.

### 8. Uninstall

```bash
helm uninstall rsa-portal -n rsa-portal
```

This deletes the license PVC the chart created, and with it the installation id. It does not delete the embedded MySQL's data PVC (`data-rsa-portal-rsa-operations-portal-mysql-0`); delete that yourself. To keep the license volume independent of the release, create a PVC yourself and set `persistence.license.existingClaim`.

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
skopeo copy docker://docker.io/library/mysql:8.0.46 docker://registry.example.com/library/mysql:8.0.46

helm install rsa-portal ./rsa-operations-portal-<version>.tgz -n rsa-portal -f my-values.yaml \
  --set global.imageRegistry=registry.example.com
```

The release package's `images/` archive holds the same images: `docker load` them, then `docker tag` and `docker push` them to your registry.

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
6. Use `<truststore_password>` for `ims.ssl.client.trust.keystore.password` and `com.rsa.ssl.trust.store.password` (Helm: `authManager.trustStore.password` or `.passwordSecret`).

The alias `rsa_am_ca` matches the default `ims.ssl.client.root.ca.alias`.

### Get the Command Client credentials

AM generates a random Command Client user and password at installation. On the **AM host**:

```bash
cd RSA_AM_HOME/utils
./rsautil manage-secrets --action list
# enter Operations Console credentials when prompted
```

Copy the values shown as **Command Client User Name** and **Command Client User Password** into `com.rsa.cmdclient.user` and `com.rsa.cmdclient.user.password` (Helm: `authManager.commandClient`).

### Find the administrative role GUIDs

For each AM administrative role that should map to a Portal role:

1. Sign in to the AM **Security Console**.
2. Go to **Administration → Administrative Roles → Manage Existing**.
3. Click the role name and choose **View**.
4. In the browser address bar, find `guid=ims.xxxxx…`. The `ims.xxxxx…` value is the GUID.

Set them as `role.mapped.1.rsaSecurityGuid` (TRUSTED_AGENT), `role.mapped.2.rsaSecurityGuid` (PMO) and `role.mapped.3.rsaSecurityGuid` (HELP_DESK). With Helm: `roles.trustedAgentGuid`, `roles.pmoGuid` and `roles.helpDeskGuid`.

### RSA Authentication Manager settings

In the release package these are `-D` options inside `JAVA_OPTS` in `connector.env`. The Helm chart builds them from `authManager.*`.

| Setting | Value |
|---|---|
| `java.naming.provider.url` | `t3s://<AM primary FQDN>:7002` |
| `com.rsa.cmdclient.user`, `com.rsa.cmdclient.user.password` | The Command Client user name and password |
| `ims.soap.client.provider.url` | `https://<AM primary FQDN>:7002/ims-ws/services/CommandServer` |
| `ims.httpinvoker.client.provider.url` | `https://<AM primary FQDN>:7002/ims-ws/httpinvoker/CommandServer` |
| `ims.httpinvoker.client.provider.readonly.url` | The same URL on the primary or a replica |
| `ims.ssl.client.trust.keystore.filename` | `/custom/ssl/trust.jks` |
| `ims.ssl.client.trust.keystore.password`, `com.rsa.ssl.trust.store.password` | Password of `trust.jks` |
| `rsa.admin.user.id`, `rsa.admin.user.password` | Internal AM user with Super Admin permissions |
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
* **Where it is kept:** `/var/lib/ens/mfa-portal` in the Portal container: the Docker volume `rsa-operations-portal_ens-license` (release package) or the license PVC (Helm). Keep it; it holds the installation id.
* **From the command line** (release package): `docker compose cp license/license.lic portal:/var/lib/ens/mfa-portal/license.lic`.

## First start checklist

- [ ] The trust store holds the AM root certificate, and its password is set.
- [ ] The Command Client and Super Admin credentials are set.
- [ ] The three role GUIDs are set.
- [ ] The database password is set, and the database accepts the Portal's user.
- [ ] The SMTP relay is reachable from the Portal.
- [ ] `application.dns.name` (Helm: `publicUrl`) is the address users reach.
- [ ] The license is installed, and you noted the installation id.
- [ ] Release package: `.env`, `connector.env` and `mysql.env` are mode 600. Helm: the license PVC is bound and you know its name.

## Verification

| Check | Command or action | Expected |
|---|---|---|
| Services running | `docker compose ps` / `kubectl -n rsa-portal get pods` | `portal`, `connector`, `mysql` up and `portal` healthy / both Deployments ready |
| Portal responds | `curl -I http://localhost:8080/login` (Helm: through `kubectl port-forward`) | `200` |
| Health | `curl http://localhost:8080/actuator/health` | `{"status":"UP"}`. The Portal's `RSAAuthManagerService` health indicator reports `DOWN` when the Connector cannot open an AM admin session. |
| Helm test | `helm test rsa-portal -n rsa-portal` | `Succeeded` |
| Logs | `docker compose logs portal` or `logs/rsaapp-portal.log` / `kubectl -n rsa-portal logs deploy/rsa-portal-rsa-operations-portal` | No startup exceptions (datasource, SMTP `test-connection`, Hessian), and a `License:` line |
| License | `/admin/license` as a PMO user | The license state, licensee and features; the installation id |
| Sign-in | Browse to `/login` and sign in with an AM user and SecurID passcode | Home page. Users holding a mapped admin role see the **Trusted Agent Activities**, **Token Management** or **PMO Activities** menus. |

!!! tip "Bootstrap the first PMO user"
    The Portal derives roles **only** from AM administrative roles. To get the first administrator, assign the AM administrative role whose GUID is in `role.mapped.2.rsaSecurityGuid` (PMO) to a user in the AM Security Console. That user can then install the license and manage roles inside the Portal ([Admin Guide](admin-guide.md#managing-user-roles)).
