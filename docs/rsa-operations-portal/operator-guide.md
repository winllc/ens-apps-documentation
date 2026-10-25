# Operator Guide

Day-to-day operation of the RSA Operations Portal, installed from the release package or with the Helm chart. Release package examples assume the install folder is `/opt/rsa-operations-portal` and run from it. Helm examples assume a release named `rsa-portal` in the namespace `rsa-portal`, as in [Installation](install.md).

## Service overview

| Component | Release package (`docker-compose.yml`) | Helm chart | Health check |
|---|---|---|---|
| Portal | Service and container `portal`, image `ghcr.io/winllc/rsa-operations-portal:<version>`, `restart: unless-stopped` | Deployment `rsa-portal-rsa-operations-portal`, `replicaCount` replicas (default 1); `RollingUpdate`, or `Recreate` while a ReadWriteOnce license volume is mounted | Package: `curl -f http://localhost:8080/login`. Helm: startup and liveness probes on `/actuator/health/liveness`, readiness on `/actuator/health/readiness`, on the management port (9090) |
| Connector | Service and container `connector`, image `ghcr.io/winllc/rsa-operations-portal-connector:<version>`. HTTPS on 8443 inside the network; plain HTTP on 8080 inside the container only | Deployment `rsa-portal-rsa-operations-portal-connector`, one replica at most (multi-step sign-ins are in its memory), Service port 8443 | `curl http://localhost:8080/app/health` inside the container: `UP` once it has an AM session. Helm: readiness on that; startup and liveness on the HTTPS port |
| MySQL | Service and container `mysql`, the MySQL 8.4 image in the package | Your own MySQL server (an embedded StatefulSet for evaluations only) | `mysqladmin ping` |

## Start, stop and status

=== "Release package"

    Run these in the install folder:

    | Task | Command |
    |---|---|
    | View the services and their health | `docker compose ps` |
    | Start (existing containers) | `docker compose start` |
    | Stop | `docker compose stop` |
    | Create or recreate containers after changing `.env`, `connector.env` or `mysql.env` | `docker compose up -d` |
    | Remove the containers (the volumes are kept) | `docker compose down` |
    | Restart one service | `docker compose restart portal` (or `connector`, `mysql`) |

    !!! warning "Changing configuration"
        The settings files (`.env` for the Portal, `connector.env` for the Connector, `mysql.env` for MySQL) are read when a container is **created**, through `env_file`. After you edit one, run `docker compose up -d`, which recreates the affected containers. A plain `restart` re-reads the mounted `/application.properties` copy of `.env` in the Portal, but **not** the environment variables (including `JAVA_OPTS`). After editing any of them, run `util/check-env.sh .env`, which checks all three.

    !!! danger "`docker compose down -v` deletes your data"
        It removes the `mysql_data` and `ens-license` volumes: the database, the license and the installation id.

=== "Helm"

    | Task | Command |
    |---|---|
    | View the pods | `kubectl -n rsa-portal get pods` |
    | Restart the Portal | `kubectl -n rsa-portal rollout restart deploy/rsa-portal-rsa-operations-portal` |
    | Restart the Connector | `kubectl -n rsa-portal rollout restart deploy/rsa-portal-rsa-operations-portal-connector` |
    | Stop the Portal (data is kept) | `kubectl -n rsa-portal scale deploy/rsa-portal-rsa-operations-portal --replicas=0` |
    | Change settings | Edit your values file and run `helm upgrade` (see [Upgrades](#upgrades)) |

    Sessions, rate limits, job locks and the license are in the database, so a restart or rolling update signs nobody out. The Portal can run more than one replica once the license volume is no longer mounted; the Connector stays at one. See [High availability](availability-and-sizing.md#high-availability).

## Health and monitoring

| Signal | How to check | Meaning |
|---|---|---|
| Container or pod health | `docker compose ps`, or `kubectl -n rsa-portal get pods` | Package: the Portal answers HTTP on 8080, and the Connector has an AM session. Helm: the pods are ready |
| Liveness | `/actuator/health/liveness` | The Portal itself is running |
| Readiness | `/actuator/health/readiness` | The Portal can serve: the database (`db`) and the Connector (`connector`) answer. `connector` is `DOWN` when the Connector can't be reached over HTTPS (address, certificate, shared secret) or can't open an AM session |
| Connector | `docker compose exec connector curl -s http://localhost:8080/app/health` | `UP` or `DOWN`, checked against AM at most every 30 seconds. The reason for `DOWN` is in the Connector's log |
| Scheduled jobs | Sign in as PMO, then **Admin → Manual Run Cron Jobs** | Shows each job's enabled and debug flags and its last-run message (in the memory of the Portal replica serving the page, cleared on restart). Each run is also audited as `job.completed` or `job.failed` |
| Email | Look for `Could not send email` or `Email not sent to:` in the Portal log | SMTP failures. Sending is asynchronous, so the UI may still report success. |

The health endpoints need no sign-in; their details (database, Connector, disk) are shown to PMO users only. Without `MANAGEMENT_SERVER_PORT` they are on the Portal's port 8080 (Helm: through `kubectl port-forward`).

**Prometheus.** Set `MANAGEMENT_SERVER_PORT` (for example `9090`; the Helm chart does, with `metrics.enabled`) and scrape `/actuator/prometheus` there, without sign-in. The Portal's own port then has no actuator. Never publish the management port to users: `docker-compose.yml` does not; add a `ports:` entry bound to an internal address if Prometheus scrapes from another host. With the Prometheus Operator, set `metrics.serviceMonitor.enabled`; with `networkPolicy.enabled`, admit your Prometheus with `metrics.allowFrom`. Besides the JVM, HTTP and database pool metrics:

| Metric | Meaning |
|---|---|
| `ens_license_mode{mode}` | 1 for the license's current mode (`full`, `read_only`, `unlicensed`) |
| `ens_license_days_remaining` | Days until the license expires (in grace, until grace ends); -1 for perpetual or none |
| `ens_audit_events_total{category,outcome}` | Audit events recorded |
| `ens_audit_forward_failures_total{output}` | Failed deliveries to Splunk HEC or syslog |
| `ens_auth_attempts_total{method,outcome}` | Sign-in attempts (`method=securid`; `success`, `failure`) |
| `ens_rsa_logins_total{outcome}` | Sign-ins: `success`, `failure`, `locked`, `disabled`, `error` |
| `ens_rsa_am_call_seconds{operation,outcome}` | Calls to AM through the Connector, timed; `success`, `rejected` (AM refused) or `error` (unreachable, timeout) |
| `ens_rsa_pin_resets_total{method}` | PINs reset: `agent`, `reset_link`, `certificate`, `windows_password` |
| `ens_rsa_emergency_codes_total` | Emergency access codes issued |

Suggested alerts: readiness failing; `ens_rsa_am_call_seconds_count{outcome="error"}` rising; `ens_license_mode{mode="full"}` at 0 or `ens_license_days_remaining` under 30; `ens_audit_forward_failures_total` rising; no new database backup in a day.

## Logs

=== "Release package"

    | Source | Location | Notes |
    |---|---|---|
    | Portal (Log4j2) | `./logs/rsaapp-portal.log` in the install folder (container `/var/log`) | Rolled daily or at 10 MB into `./logs/rsaapp-portal-YYYY-MM/app-MM-dd-yyyy-N.log.gz`. Configured by `default_build/portal/log4j2.xml`. |
    | Portal console | `docker compose logs -f portal` | Same events, with the date, time, thread, level, logger and the request id. `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` writes JSON lines instead. With `ENS_AUDIT_STDOUT=true` (the default) the audit events appear here too, as raw `ens-audit/1` JSON lines |
    | Connector | `docker compose logs -f connector` | Packaged Log4j 1.x configuration logs to stderr at `DEBUG`. `default_build/log4j.xml` (rolling `/var/log/connector.log`, so `./logs/connector.log` on the host) applies only if you select it through the Connector's `JAVA_OPTS` in `connector.env`. A configuration of your own should keep the layout `CrlfSafePatternLayout` ([Configuration → Logging](configuration.md#logging)). |
    | MySQL | `docker compose logs mysql` | |
    | All services | `docker compose logs -f` | Ctrl+C to exit |

    To change Portal log levels, edit `default_build/portal/log4j2.xml` (for example the `com.winllc.rsaapp.portal` logger) and run `docker compose restart portal`. Keep `%enc{%m}{CRLF}` in its patterns.

=== "Helm"

    ```bash
    kubectl -n rsa-portal logs -f deploy/rsa-portal-rsa-operations-portal
    kubectl -n rsa-portal logs -f deploy/rsa-portal-rsa-operations-portal-connector
    ```

    Both components log to standard output. The Portal's lines are JSON (Elastic Common Schema) unless `logFormat: text`. Collect the logs with your cluster's log shipper.

At startup the Portal logs the help-desk and rate-limit settings in effect (`Help desk: ...`, `Rate limits (per client address / per user ID or token serial): ...`). It stops with `Help-desk settings: ...` or `Rate-limit settings: ...` naming any setting that makes no sense, and warns while `rsa.helpdesk.verification.required=false`, `rsa.helpdesk.dual-control.privileged-roles` is empty or `rsa.rate-limit.enabled=false`. User IDs and token serials that people type are escaped before they are logged, and in the text logs of the Portal and the Connector a line break in a message is written as `\r` or `\n`, so every entry stays on its own line (a stack trace keeps its lines).

The Connector warns at every start while one of its protections is off. Each warning names the setting, so look for them after an upgrade:

| Warning starts with | Means | To clear it |
|---|---|---|
| `Mutual TLS is off (CONNECTOR_TLS_CLIENT_AUTH=...)` | Any client that reaches port 8443 gets as far as the shared secret | Release package: `./util/connector-tls.sh --mutual`, `./util/file-owners.sh`, `docker compose up -d`. Helm: `connector.tls.mutual=true` ([Mutual TLS](rsa-integration.md#mutual-tls)) |
| `RSA_TLS_VERIFY_HOSTNAME=false` | AM's certificate is checked against the CA only, not the host name | Use a name AM's certificate has in the AM URLs and remove the setting |
| `CONNECTOR_SERIAL_FILTER=off` | AM's replies are read without the deserialization filter | Add the class from the refusal warning to `CONNECTOR_SERIAL_FILTER_ALLOW` and remove the setting |

`Refused a reply from Authentication Manager: ...` at WARN means the Connector refused a reply from AM, and the command failed: see [What the Connector accepts](rsa-integration.md#what-the-connector-accepts). On the Portal, `rsa.helpdesk.code-key is not set` at start means the help desk's e-mailed codes do not survive a restart ([The e-mailed code's key](help-desk.md#the-e-mailed-codes-key)).

The request id in each Portal log line is the caller's `X-Request-Id` (from a proxy or load balancer, if it is a plain token) or a new one. It is returned in the response's `X-Request-Id` and recorded in the request's audit events, so a log line, a user's report and an audit event can be matched.

## Database backups

=== "Release package"

    `upgrade.sh` backs up the database before every upgrade, to `backups/RSAAPP-before-<version>-<time>.sql.gz` in the install folder.

    For regular backups, schedule `util/backupdb.sh`:

    ```cron
    17 1 * * * /opt/rsa-operations-portal/util/backupdb.sh -d /backup/rsa-portal -k 30 -g backup@example.com
    ```

    It dumps the database while the Portal runs (`mysqldump --single-transaction --routines --triggers`, so the dump is consistent and nothing is locked), as root with the root password the MySQL container already has, so no password goes on a command line or into a crontab. It writes `DIR/db/RSAAPP-<time>.sql.gz` (mode 600) and deletes its own backups older than the retention. It needs no `sudo`, only a user who may use Docker.

    | Option (environment variable) | Default | Meaning |
    |---|---|---|
    | `-d DIR` (`BACKUP_DIR`) | `backups` in the install folder | The backups go to `DIR/db` |
    | `-k DAYS` (`BACKUP_KEEP_DAYS`) | `7` | Delete this script's backups older than that; `0` keeps them all |
    | `-g RECIPIENT` (`BACKUP_GPG_RECIPIENT`) | none | Encrypt to this gpg key (its public key in the keyring of the user running the script): `.sql.gz.gpg` |
    | `-a RECIPIENT` (`BACKUP_AGE_RECIPIENT`) | none | Encrypt with age to this recipient (`age1...`) or file of recipients: `.sql.gz.age` |
    | `-c CONTAINER` (`MYSQL_CONTAINER`) | `mysql` | The MySQL container |
    | `-n DATABASE` (`BACKUP_DATABASE`) | the container's `MYSQL_DATABASE`, else `RSAAPP` | The database |
    | `-u USER` | root | Dump as this MySQL user; its password from standard input or `BACKUP_MYSQL_PASSWORD`, never the command line |

    `CONTAINER_ENGINE=podman` uses podman instead of docker.

    Also keep copies of `.env`, `connector.env`, `mysql.env`, `license/`, `default_build/` (including `ssl/trust.jks`), the certificates for HTTPS between the Portal and the Connector (`connector-tls-ca/`, `default_build/connector-tls/`, `portal-tls/`). They hold secrets: keep the copies private. The license and the installation id are in the database, so the dump has them; the `ens-license` volume is read only on the first start of the release that moved them there.

=== "Helm"

    The database is your own MySQL server. Back it up with its usual tools, for example:

    ```bash
    mysqldump -h mysql.example.com -u appuser -p --single-transaction --routines --triggers RSAAPP | gzip > rsaapp_$(date +%F).sql.gz
    ```

    Or turn on the chart's backup CronJob, which works with an external or the embedded MySQL:

    | Value | Default | Meaning |
    |---|---|---|
    | `backup.enabled` | `false` | A CronJob `<release>-rsa-operations-portal-backup` that dumps the database with `mysqldump --single-transaction --routines --triggers` as `database.username`, and gzips it |
    | `backup.schedule`, `backup.timeZone` | `17 1 * * *`, the controller's | When it runs |
    | `backup.keepDays` | `7` | Dumps older than this are deleted; `0` keeps every dump |
    | `backup.sslMode` | `REQUIRED` | `mysqldump --ssl-mode`: `REQUIRED`, `PREFERRED` or `DISABLED` |
    | `backup.gpg.publicKeySecret`, `.publicKeySecretKey` | `""`, `public.asc` | A Secret with an ASCII-armored gpg public key: each dump is encrypted to it (`.sql.gz.gpg`) |
    | `backup.persistence.*` | `10Gi`, ReadWriteOnce | The PVC the dumps go to. The chart's PVC is kept by `helm uninstall`; or name your own in `existingClaim` |
    | `backup.image.*` | the embedded MySQL's image | The image with `mysqldump`, `gzip` and `gpg`. Use the same major version as the server |

    The PVC is in the cluster, so copy the dumps off it to your backup storage as well. Also keep your values file and the Secrets it names. The license and the installation id are in the database, so the dumps have them; the license PVC `rsa-portal-rsa-operations-portal-license` is read only on the first start of the release that moved them there.

    The embedded MySQL (`database.embedded.enabled`) has no high availability. Use it for evaluations only.

## Restore

=== "Release package"

    ```bash
    cd /opt/rsa-operations-portal
    docker compose stop portal
    gunzip -c /backup/rsa-portal/db/RSAAPP-<time>.sql.gz \
      | docker exec -i mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot RSAAPP'
    docker compose start portal
    ```

    For an encrypted backup, decrypt it first: `gpg --decrypt FILE.sql.gz.gpg | gunzip | ...`, or `age --decrypt -i KEY FILE.sql.gz.age | gunzip | ...`.

=== "Helm"

    Scale the Portal to 0, restore the dump into your MySQL server with its usual tools, then scale the Portal back to its `replicaCount`:

    ```bash
    kubectl -n rsa-portal scale deploy/rsa-portal-rsa-operations-portal --replicas=0
    gunzip -c RSAAPP-<time>.sql.gz | kubectl -n rsa-portal exec -i <a pod with the mysql client> -- \
      sh -c 'MYSQL_PWD=... mysql -h <database host> -u <user> RSAAPP'
    kubectl -n rsa-portal scale deploy/rsa-portal-rsa-operations-portal --replicas=1    # or your replicaCount
    ```

After restoring an older backup, read [Disaster recovery](availability-and-sizing.md#disaster-recovery) about the audit trail's sequence numbers.

## Upgrades

Back up the database first.

=== "Release package"

    Verify and unpack the new release, then run its `upgrade.sh` against the install folder:

    ```bash
    tar -xzf rsa-operations-portal-<new version>.tar.gz
    cd rsa-operations-portal-<new version>
    ./upgrade.sh /opt/rsa-operations-portal
    ```

    [Installation → Upgrade with upgrade.sh](install.md#upgrade-with-upgradesh) lists exactly what is replaced, kept and checked. After the upgrade:

    1. Add any settings that `upgrade.sh` reported as new to the file it names (`.env`, `connector.env` or `mysql.env`), then run `docker compose up -d`.
    2. Verify with the [checks in the install guide](install.md#verification).
    3. If `upgrade.sh` split a single `.env`, delete the `.env.before-split.*` copy once the Portal runs: it still holds every secret.
    4. If `upgrade.sh` said that mutual TLS between the Portal and the Connector is off, turn it on: `./util/connector-tls.sh --mutual`, `./util/file-owners.sh`, `docker compose up -d`.
    5. Check the Connector's log for `Refused a reply from Authentication Manager` and for a TLS failure to AM naming its host ([Installation → upgrade notes](install.md#upgrade-with-upgradesh)).

=== "Helm"

    ```bash
    helm upgrade rsa-portal oci://ghcr.io/winllc/charts/rsa-operations-portal --version <new version> \
      -n rsa-portal -f my-values.yaml
    ```

    The chart's version is the release version. The license PVC is kept. See [Installation → Upgrade](install.md#7-upgrade).

The Portal updates its database schema itself on the first start of the new version (Flyway). Going back to an older release means restoring the backup as well: see [Rolling back an upgrade](availability-and-sizing.md#rolling-back-an-upgrade).

What changes for operators and users with the release that brought help-desk requests and rate limits is listed in [Installation → Upgrade with upgrade.sh](install.md#upgrade-with-upgradesh), under *To the release with help-desk requests and rate limits*.

## Certificate renewal

| Certificate | Where it lives | Renewal steps |
|---|---|---|
| **RSA AM root CA** (Connector trust) | Release package: `default_build/ssl/trust.jks` (the Connector sees it as `/custom/ssl/trust.jks`). Helm: the Secret in `authManager.trustStore.existingSecret`. | Export the new root as in [Installation](install.md#export-the-am-root-certificate-and-build-trustjks) and import it into `trust.jks` (`keytool -import … -alias rsa_am_ca`, using a new alias if you keep the old CA during overlap). AM's new certificate must also name the host in the Connector's AM URLs. Release package: `docker compose restart connector`. Helm: replace the Secret, then restart the Connector Deployment. If the password changed, update `RSA_TRUSTSTORE_PASSWORD` in `connector.env` (Helm: `authManager.trustStore.password` or its Secret) and run `docker compose up -d` or `helm upgrade`. |
| **Connector HTTPS and mutual TLS** (Portal to Connector) | Release package: `default_build/connector-tls/`, the Portal's client certificate in `portal-tls/`, and the installation's CA in `connector-tls-ca/`. Helm: the Secret `<release>-rsa-operations-portal-connector-tls`, or yours in `connector.tls.existingSecret` | Release package: `util/connector-tls.sh` renews the certificates it made, the Portal's included, when 60 days are left; `install.sh` and `upgrade.sh` run it, and `util/connector-tls.sh --renew` renews them now. Then `./util/file-owners.sh` (the Portal reads its new key through group 0) and `docker compose up -d`. The CA lasts 10 years and the Connector's certificate 5. Your own certificates are left alone: renew them yourself. Helm: the chart's certificates last `connector.tls.validityDays` (3650); with `existingSecret`, cert-manager or you renew them, then restart both Deployments. |
| **Portal HTTPS (8443)** | Release package: a keystore under `default_build/ssl/` (mounted at `/ssl`), referenced by your `server.ssl.*` settings in `.env`. Helm: the Secret in `tls.keystore.existingSecret`. | Replace the keystore (same path and alias, or update the settings), then restart the Portal. See [Security](security.md#tls-and-client-certificates). With Helm and edge termination, renew the ingress or route certificate instead. |
| **Client-certificate trust (8443)** | The trust store referenced by `server.ssl.trust-store` (Helm: `tls.truststore.existingSecret`) | Add the new issuing CAs for user PKI certificates and restart the Portal. |
| **Help-desk code key** | `rsa.helpdesk.code-key` in `.env` (Helm: the chart's Secret, key `helpdesk-email-code-key`, or `helpDesk.emailCodeKey.existingSecret`) | No expiry. To change it, replace the value (`openssl rand -hex 32`) and run `docker compose up -d`, or update the Secret and restart the Portal. Only codes sent in the last few minutes then fail; the operator sends a new one. |
| **Command client password** | `RSA_CMDCLIENT_PASSWORD` in `connector.env` (Helm: `authManager.commandClient`) | If AM regenerates it, read it again with `rsautil manage-secrets --action list`, update `connector.env` and run `docker compose up -d`, or update the Secret and restart the Connector. |

## Routine checklist

| Frequency | Task |
|---|---|
| Daily | Confirm a new database backup exists. Check `docker compose ps` or the pods' health. |
| Monthly | Check the Connector's certificate dates if you supply your own (`openssl x509 -enddate -noout -in default_build/connector-tls/tls.crt`). |
| Daily | Check **Admin → Manual Run Cron Jobs** for failed last-run messages. |
| Weekly | Review the Portal log for `ERROR` (AM session failures, email failures), and the Connector's for `Refused a reply from Authentication Manager`. |
| Before AM upgrades or certificate changes | Plan the `trust.jks` update and test sign-in afterwards. |
| Quarterly | Test a restore into a scratch environment. |

When something breaks, go to [Troubleshooting](troubleshooting.md).
