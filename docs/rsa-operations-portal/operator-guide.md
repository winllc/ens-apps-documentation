# Operator Guide

Day-to-day operation of the RSA Operations Portal, installed from the release package or with the Helm chart. Release package examples assume the install folder is `/opt/rsa-operations-portal` and run from it. Helm examples assume a release named `rsa-portal` in the namespace `rsa-portal`, as in [Installation](install.md).

## Service overview

| Component | Release package (`docker-compose.yml`) | Helm chart | Health check |
|---|---|---|---|
| Portal | Service and container `portal`, image `ghcr.io/winllc/rsa-operations-portal:<version>`, `restart: unless-stopped` | Deployment `rsa-portal-rsa-operations-portal`, one replica, `Recreate` strategy | Package: `curl -f http://localhost:8080/login`. Helm: startup, liveness and readiness probes on the actuator endpoints. |
| Connector | Service and container `connector`, image `ghcr.io/winllc/rsa-operations-portal-connector:<version>` | Deployment `rsa-portal-rsa-operations-portal-connector` (stateless) | `/app/connector` answers `401` once the webapp is deployed |
| MySQL | Service and container `mysql`, the MySQL 8.0 image in the package | Your own MySQL server (an embedded StatefulSet for evaluations only) | `mysqladmin ping` |

## Start, stop and status

=== "Release package"

    Run these in the install folder:

    | Task | Command |
    |---|---|
    | View the services and their health | `docker compose ps` |
    | Start (existing containers) | `docker compose start` |
    | Stop | `docker compose stop` |
    | Create or recreate containers after changing `.env` | `docker compose up -d` |
    | Remove the containers (the volumes are kept) | `docker compose down` |
    | Restart one service | `docker compose restart portal` (or `connector`, `mysql`) |

    !!! warning "Changing configuration"
        `.env` is read when a container is **created**, through `env_file`. After you edit it, run `docker compose up -d`, which recreates the affected containers. A plain `restart` re-reads the mounted `/application.properties` copy in the Portal, but **not** the environment variables (including `JAVA_OPTS`). Run `util/check-env.sh` after editing `.env`.

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

    Keep `replicaCount: 1` for the Portal. Its license volume is single-writer, and sign-in sessions are held in memory.

## Health and monitoring

| Signal | How to check | Meaning |
|---|---|---|
| Container or pod health | `docker compose ps`, or `kubectl -n rsa-portal get pods` | `healthy` (or ready) means the Portal answers HTTP on 8080 |
| Spring health | `curl -s http://localhost:8080/actuator/health` (Helm: through `kubectl port-forward`) | The `/actuator/**` routes are open without sign-in. The Portal's RSA health indicator calls the Connector's `testConnection()`, which validates or reopens the admin AM session, so `DOWN` usually means the Connector cannot reach or authenticate to AM. |
| Scheduled jobs | Sign in as PMO, then **Admin → Manual Run Cron Jobs** | Shows each job's enabled and debug flags and its last-run message (in memory, cleared on restart) |
| Email | Look for `Could not send email` or `Email not sent to:` in the Portal log | SMTP failures. Sending is asynchronous, so the UI may still report success. |

## Logs

=== "Release package"

    | Source | Location | Notes |
    |---|---|---|
    | Portal (Log4j2) | `./logs/rsaapp-portal.log` in the install folder (container `/var/log`) | Rolled daily or at 10 MB into `./logs/rsaapp-portal-YYYY-MM/app-MM-dd-yyyy-N.log.gz`. Configured by `default_build/portal/log4j2.xml`. |
    | Portal console | `docker compose logs -f portal` | Same events, pattern `HH:mm:ss.SSS [thread] LEVEL logger - msg` |
    | Connector | `docker compose logs -f connector` | Packaged Log4j 1.x configuration logs to stderr at `DEBUG`. `default_build/log4j.xml` (rolling `/var/log/connector.log`, so `./logs/connector.log` on the host) applies only if you select it through `JAVA_OPTS`. |
    | MySQL | `docker compose logs mysql` | |
    | All services | `docker compose logs -f` | Ctrl+C to exit |

    To change Portal log levels, edit `default_build/portal/log4j2.xml` (for example the `com.winllc.rsaapp.portal` logger) and run `docker compose restart portal`.

=== "Helm"

    ```bash
    kubectl -n rsa-portal logs -f deploy/rsa-portal-rsa-operations-portal
    kubectl -n rsa-portal logs -f deploy/rsa-portal-rsa-operations-portal-connector
    ```

    Both components log to standard output. Collect the logs with your cluster's log shipper.

## Database backups

=== "Release package"

    `upgrade.sh` backs up the database before every upgrade, to `backups/RSAAPP-before-<version>-<time>.sql.gz` in the install folder.

    For regular backups, run `util/backupdb.sh` from the install folder:

    ```bash
    util/backupdb.sh -d /opt/backup
    ```

    It runs `mysqldump` of `RSAAPP` in the `mysql` container, with the root password the container was started with, and writes `/opt/backup/db/db_backup_<dd_Mon_YYYY_HHMM>.sql.gz`. It then deletes files older than 7 days under `/opt/backup/db/` (with `sudo find`, so the user needs password-less `sudo` for it). To run it nightly, add a crontab line of your own:

    ```cron
    0 1 * * * cd /opt/rsa-operations-portal && util/backupdb.sh -d /opt/backup
    ```

    Also keep copies of `.env`, `license/` and `default_build/` (including `ssl/trust.jks`).

=== "Helm"

    The database is your own MySQL server. Back it up with its usual tools, for example:

    ```bash
    mysqldump -h mysql.example.com -u appuser -p --single-transaction --routines RSAAPP | gzip > rsaapp_$(date +%F).sql.gz
    ```

    Also keep your values file, the Secrets it names, and the license PVC `rsa-portal-rsa-operations-portal-license`, which holds the installation id.

    The embedded MySQL (`database.embedded.enabled`) has no backups. Use it for evaluations only.

## Restore

=== "Release package"

    ```bash
    cd /opt/rsa-operations-portal
    docker compose stop portal connector
    gunzip -c /opt/backup/db/db_backup_<stamp>.sql.gz | \
      docker compose exec -T mysql sh -c 'exec mysql -uroot --password="$MYSQL_ROOT_PASSWORD" RSAAPP'
    docker compose start
    ```

=== "Helm"

    Scale the Portal to 0, restore the dump into your MySQL server with its usual tools, then scale the Portal back to 1.

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

    1. Add any settings that `upgrade.sh` reported as new to `.env`, then run `docker compose up -d`.
    2. Verify with the [checks in the install guide](install.md#verification).

=== "Helm"

    ```bash
    helm upgrade rsa-portal oci://ghcr.io/winllc/charts/rsa-operations-portal --version <new version> \
      -n rsa-portal -f my-values.yaml
    ```

    The chart's version is the release version. The license PVC is kept. See [Installation → Upgrade](install.md#7-upgrade).

The Portal updates its database tables itself on the first start of the new version. Going back to an older release means restoring the backup as well.

## Certificate renewal

| Certificate | Where it lives | Renewal steps |
|---|---|---|
| **RSA AM root CA** (Connector trust) | Release package: `default_build/ssl/trust.jks` (the Connector sees it as `/custom/ssl/trust.jks`). Helm: the Secret in `authManager.trustStore.existingSecret`. | Export the new root as in [Installation](install.md#export-the-am-root-certificate-and-build-trustjks) and import it into `trust.jks` (`keytool -import … -alias rsa_am_ca`, using a new alias if you keep the old CA during overlap). Release package: `docker compose restart connector`. Helm: replace the Secret, then restart the Connector Deployment. If the password changed, update `ims.ssl.client.trust.keystore.password` and `com.rsa.ssl.trust.store.password` (Helm: `authManager.trustStore.password` or its Secret) and run `docker compose up -d` or `helm upgrade`. |
| **Portal HTTPS (8443)** | Release package: a keystore under `default_build/ssl/` (mounted at `/ssl`), referenced by your `server.ssl.*` settings in `.env`. Helm: the Secret in `tls.keystore.existingSecret`. | Replace the keystore (same path and alias, or update the settings), then restart the Portal. See [Security](security.md#tls-and-client-certificates). With Helm and edge termination, renew the ingress or route certificate instead. |
| **Client-certificate trust (8443)** | The trust store referenced by `server.ssl.trust-store` (Helm: `tls.truststore.existingSecret`) | Add the new issuing CAs for user PKI certificates and restart the Portal. |
| **Command client password** | `com.rsa.cmdclient.user.password` (Helm: `authManager.commandClient`) | If AM regenerates it, read it again with `rsautil manage-secrets --action list`, update `.env` and run `docker compose up -d`, or update the Secret and restart the Connector. |

## Routine checklist

| Frequency | Task |
|---|---|
| Daily | Confirm a new database backup exists. Check `docker compose ps` or the pods' health. |
| Daily | Check **Admin → Manual Run Cron Jobs** for failed last-run messages. |
| Weekly | Review the Portal log for `ERROR` (AM session failures, email failures). |
| Before AM upgrades or certificate changes | Plan the `trust.jks` update and test sign-in afterwards. |
| Quarterly | Test a restore into a scratch environment. |

When something breaks, go to [Troubleshooting](troubleshooting.md).
