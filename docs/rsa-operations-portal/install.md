# Installation

This page covers a new installation from the deployment package, preparing RSA Authentication Manager (AM), the first start, verification and upgrades. To build the images yourself, see [Build & Release](build-and-release.md).

## Prerequisites

| Item | Requirement | Where it comes from |
|---|---|---|
| Operating system | CentOS/RHEL 7.x | README |
| Software | `docker` and the **`docker-compose`** binary (v1 CLI; `install.sh` exits if `docker-compose` is not on the `PATH`) | README, `install.sh` |
| Java on the host | Needed only for the upgrade property check (`util/propdiff.sh` runs `java -classpath util PropDiff`) | `util/propdiff.sh` |
| SELinux | `install.sh` warns "SELINUX is Enabled, this can cause issues" when it is on | `install.sh` |
| `crontab` | Used when `-b` is given, to schedule the nightly DB backup | `install.sh` |
| Network: portal host → RSA AM | TCP 7002 (HTTPS command server and `t3s`) on the primary, plus the replica if it serves the read-only URL | connector configuration |
| Network: portal host → SMTP relay | TCP 25 by default | `spring.mail.port` |
| Network: portal host → AD (optional) | LDAP URL from `active-directory.properties[i].providerURL` | AD configuration |
| Network: users → portal | TCP 8080 (HTTP) and/or 8443 (HTTPS) | `docker-compose.yaml` |
| Image access | For the offline package, nothing. The images are inside `services.img`. For the Jenkins zip, pull access to `docker.artifactory.code.dicelab.net/cio2d/dodiispki/mfa/*`. | packaging |

You also need, from RSA AM:

* the AM **root CA certificate**, imported into a Java keystore `trust.jks` ([steps](#1-export-the-am-root-certificate-and-build-trustjks));
* the **command client** user name and password ([steps](#2-get-the-command-client-credentials));
* an **internal AM user with Super Admin** permissions for `rsa.admin.user.id`;
* the **GUIDs of the AM administrative roles** that grant Trusted Agent, PMO and Help Desk access ([steps](#3-find-the-administrative-role-guids)).

## Deployment package contents

`prepare_deployment_package.sh` (also run by the GitLab CI `job` stage) produces `rsaapp-deployment_<MM-DD-YYYY>.tar.gz` containing:

| Entry | Purpose |
|---|---|
| `services.img` | `docker save` archive of every image in `docker-compose.yaml` |
| `docker-compose.yaml` | Service definitions (portal, connector, mysql) |
| `dev.env` | Environment and properties file (see [Configuration](configuration.md)) |
| `default_build/` | Email templates, logging configuration, message and page-description properties, logo and banner images |
| `mysql/` | `01-databases.sql` (creates DB `RSAAPP` and user `appuser`) |
| `install.sh`, `init_deployment.sh`, `restore.sh`, `start-portal.sh`, `stop-portal.sh`, `add-node.sh` | Operational scripts |
| `util/` | `backupdb.sh`, `propdiff.sh`, `PropDiff.class` |
| `README`, `COPYRIGHT` | |

!!! note
    In the repository the scripts live in `scripts/`, and the add-node script is named `add_node.sh`, while `prepare_deployment_package.sh` packages files from the current directory and names `add-node.sh`. Whoever builds the package must stage the files accordingly. See [Build & Release](build-and-release.md#offline-deployment-package).

## New installation

### 1. Export the AM root certificate and build `trust.jks`

The connector must trust AM's TLS certificate. The README procedure:

1. In a browser, open `https://<am-server>:7002`. An *Error 404* page appears.
2. Open the page's certificate, go to **Certification Path**, select the **top (root)** certificate, then **View Certificate → Details → Copy to File**.
3. Export it as **DER encoded binary X.509 (.CER)** with the name `am_root.cer`.
4. Copy `am_root.cer` to the host and import it with the Java `keytool`:

    ```bash
    keytool -import -keystore trust.jks -storepass <truststore_password> \
            -file am_root.cer -alias rsa_am_ca -trustcacerts
    ```

5. Place the keystore at **`default_build/ssl/trust.jks`** in the deployment directory, and record `<truststore_password>` for `ims.ssl.client.trust.keystore.password` and `com.rsa.ssl.trust.store.password`.

The alias `rsa_am_ca` matches the default `ims.ssl.client.root.ca.alias`. `default_build/ssl/` is not part of the repository, so create it.

### 2. Get the command client credentials

AM generates a random command client user and password at installation. On the **AM host**:

```bash
cd RSA_AM_HOME/utils
./rsautil manage-secrets --action list
# enter Operations Console credentials when prompted
```

Copy the values shown as **Command Client User Name** and **Command Client User Password** into `com.rsa.cmdclient.user` and `com.rsa.cmdclient.user.password`.

### 3. Find the administrative role GUIDs

For each AM administrative role that should map to a portal role:

1. Sign in to the AM **Security Console**.
2. Go to **Administration → Administrative Roles → Manage Existing**.
3. Click the role name and choose **View**.
4. In the browser address bar, find `guid=ims.xxxxx…`. The `ims.xxxxx…` value is the GUID.

Set them as `role.mapped.1.rsaSecurityGuid` (TRUSTED_AGENT), `role.mapped.2.rsaSecurityGuid` (PMO) and `role.mapped.3.rsaSecurityGuid` (HELP_DESK).

### 4. Run the installer

```bash
tar -xzvf rsaapp-deployment_<MM-DD-YYYY>.tar.gz -C /tmp/rsaapp-pkg
cd /tmp/rsaapp-pkg
./install.sh -d /opt/deployment
```

```text
Usage (printed when run without arguments):
Required: -d <path to install directory>
Optional: -b <path to backup directory>
```

On a **new install** (the `-d` directory does not exist yet), `install.sh`:

1. creates the directory and copies the whole package into it;
2. runs `chmod -R 777 <dir>/mysql`;
3. changes into the directory and runs `docker-compose down`;
4. loads the images with `docker load < services.img`;
5. prints: *"Before starting portal, update the variables in dev.env. Once correct values are added, start the Portal with: docker-compose up -d"*.

If you passed `-b <backup dir>`, it also installs a root-crontab entry for a nightly database backup at 01:00 (see [Operator Guide](operator-guide.md#database-backups)).

### 5. Configure `dev.env`

Edit `/opt/deployment/dev.env`. At a minimum, set the values from the README checklist, which are covered in [Configuration](configuration.md):

- [ ] `JAVA_OPTS` (keep the default flags, and add the RSA settings as `-D` options and `-Dconfig.properties.location=…` as your deployment requires)
- [ ] `java.naming.provider.url`
- [ ] `com.rsa.cmdclient.user`, `com.rsa.cmdclient.user.password`
- [ ] `rsa.admin.user.id`, `rsa.admin.user.password`
- [ ] `ims.soap.client.provider.url`, `ims.httpinvoker.client.provider.url`, `ims.httpinvoker.client.provider.readonly.url`
- [ ] `ims.ssl.client.trust.keystore.filename`, `ims.ssl.client.trust.keystore.password`, `com.rsa.ssl.trust.store.password`
- [ ] `rsa.authmanager.securityDomain.name` (default `SystemDomain`)
- [ ] `role.mapped.1-3.rsaSecurityGuid`
- [ ] `spring.mail.host`, `mail.default.from`, `mfa.group.email`
- [ ] `spring.datasource.url`, `spring.datasource.username`, `spring.datasource.password`, `MYSQL_ROOT_PASSWORD`
- [ ] `application.dns.name` (the URL users will reach)

Optionally, customise branding in `default_build/properties/messages.properties` and the images in `default_build/static/`. See [Configuration](configuration.md#branding-text-and-email-templates).

### 6. Initialise the database

The portal needs the schema `RSAAPP` and an application user. Hibernate creates the tables on first start (`ddl-auto=update`). The shipped `docker-compose.yaml` has the init-script mount **commented out**:

```yaml
    volumes:
      - mysql_data:/var/lib/mysql
      #- ./mysql:/docker-entrypoint-initdb.d
```

Choose one of these before the **first** start, while the `mysql_data` volume is still empty:

=== "Use the shipped init script"

    1. Edit `mysql/01-databases.sql` and replace the sample password in `CREATE USER 'appuser'@'%' IDENTIFIED BY '…'` with your own. Use the same password in `spring.datasource.password`.
    2. Uncomment `- ./mysql:/docker-entrypoint-initdb.d` in `docker-compose.yaml`.

    The script also runs `GRANT ALL PRIVILEGES ON *.* TO 'appuser'@'%'`. Narrow that grant to `RSAAPP.*` if your policy requires it.

=== "Create it by hand after MySQL starts"

    ```bash
    docker-compose up -d mysql
    docker exec -it mysql mysql -uroot -p
    ```
    ```sql
    CREATE DATABASE IF NOT EXISTS `RSAAPP`;
    CREATE USER 'appuser'@'%' IDENTIFIED BY '<password>';
    GRANT ALL PRIVILEGES ON RSAAPP.* TO 'appuser'@'%';
    ```

### 7. First start

```bash
cd /opt/deployment
docker-compose up -d
docker ps
docker-compose logs -f      # Ctrl+C to stop following
```

Startup order: `mysql` must pass its `mysqladmin ping` health check and `connector` must have started before `portal` starts. On start, the connector logs in to AM with `rsa.admin.user.id` and looks up the configured realm and security domain. If either lookup fails, the connector's AM session check fails (see [Troubleshooting](troubleshooting.md)).

## Verification

| Check | Command or action | Expected |
|---|---|---|
| Containers running | `docker ps` | `portal`, `connector`, `mysql` up; `portal` healthy |
| Portal responds | `curl -I http://localhost:8080/` | `302` redirect to `/home` |
| Actuator health | `curl http://localhost:8080/actuator/health` | `{"status":"UP"}`. The portal's `RSAAuthManagerService` health indicator reports `DOWN` when the connector cannot open an AM admin session. |
| Logs | `tail -f logs/rsaapp-portal.log` | No startup exceptions (datasource, SMTP `test-connection`, Hessian) |
| Sign-in | Browse to `/login` and sign in with an AM user and SecurID passcode | Home page. Users holding a mapped admin role see the **Trusted Agent Activities**, **Token Management** or **PMO Activities** menus. |
| Public pages | `/ta/list`, `/user/token/test` | Render without sign-in |

!!! tip "Bootstrap the first PMO user"
    The portal derives roles **only** from AM administrative roles. To get the first administrator, assign the AM administrative role whose GUID is in `role.mapped.2.rsaSecurityGuid` (PMO) to a user in the AM Security Console. That user can then manage roles inside the portal ([Admin Guide](admin-guide.md#managing-user-roles)).

## Upgrade

```bash
tar -xzvf rsaapp-deployment_<new-date>.tar.gz -C /tmp/rsaapp-new
cd /tmp/rsaapp-new
./install.sh -d /opt/deployment -b /opt/backup
```

When the install directory **exists**, `install.sh`:

1. **Backs up** (only with `-b`): creates `<backup>/rsaapp-backup.tar` from `<install dir>/*` and runs `util/backupdb.sh -u root -p root -d <backup>` to write `<backup>/db/db_backup_<date>.sql.gz`.
2. **Copies** the new `services.img`, `docker-compose.yaml`, `mysql/`, `init_deployment.sh`, `install.sh`, `README`, `COPYRIGHT`, `util/`, `default_build/portal/` and `default_build/log4j.xml` into the install directory. It does **not** overwrite `dev.env`, `default_build/properties`, `default_build/static`, `default_build/email-templates` or `default_build/ssl`.
3. Runs `docker-compose down` and then `docker load < services.img`.
4. **Property check**: compares the package's `dev.env` with the installed `dev.env` (`util/propdiff.sh`) and prints any properties missing from the installed file. If there are any, it stops and asks you to add them and then run `docker-compose up -d`. Otherwise it runs `docker-compose up -d` and `docker ps`.
5. With `-b`, replaces any existing `backupdb.sh` crontab line with `0 1 * * * <dir>/util/backupdb.sh -u root -p root -d <backup>`.

!!! warning "Before upgrading"
    * `install.sh` and `backupdb.sh` use the database credentials **`root`/`root`**. If your `MYSQL_ROOT_PASSWORD` differs, edit `DBUSER`/`DBPASSWORD` in `install.sh` first, or take the DB backup by hand ([Operator Guide](operator-guide.md#database-backups)).
    * `backupdb.sh` writes into `<backup>/db/`, which it does not create. Run `mkdir -p <backup>/db` first.
    * If `java` is not installed on the host, `propdiff.sh` fails and the installer reports "No properties difference found". Compare `dev.env` by hand.
    * Because `docker-compose.yaml` is overwritten, re-apply any local edits (for example, the uncommented MySQL init mount).

Rollback is covered in [Operator Guide → Restore](operator-guide.md#restore).
