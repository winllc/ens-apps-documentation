# Operator Guide

Day-to-day operation of a docker-compose deployment. The examples assume the install directory is `/opt/deployment`. `add_node.sh` uses that path as its default, and `install.sh` accepts any path.

## Service overview

| Container | Image (`docker-compose.yaml`) | Restart policy | Health check |
|---|---|---|---|
| `portal` | `docker.artifactory.code.dicelab.net/cio2d/dodiispki/mfa/rsa-operations-portal:latest` | `unless-stopped` | `curl -f http://localhost:8080` |
| `connector` | `…/mfa/rsa-operations-connector:latest` | `unless-stopped` | none |
| `mysql` | `mysql:8.0.25` | `unless-stopped` | `mysqladmin ping -h localhost` (timeout 20s, 10 retries) |

## Start, stop and status

Run all commands from the install directory.

| Task | Command |
|---|---|
| View running services | `docker ps` |
| Start (existing containers) | `docker-compose start` |
| Start and wait for the portal to become healthy | `./start-portal.sh` (runs `docker-compose start`, then polls `docker inspect -f {{.State.Health.Status}} portal`) |
| Stop | `docker-compose stop` or `./stop-portal.sh` |
| Create or recreate containers after changing `dev.env` or `docker-compose.yaml` | `docker-compose up -d` |
| Tear down containers (the data volume is kept) | `docker-compose down` |
| Restart one service | `docker-compose restart portal` (or `connector`, `mysql`) |
| Load images from an offline package | `./init_deployment.sh` (`docker load < services.img`) |

!!! note "`start-portal.sh` loop"
    The wait loop in `start-portal.sh` never increments its counter, so if the portal does not become healthy, the script keeps polling until you interrupt it.

!!! warning "Changing configuration"
    `dev.env` is read when a container is **created**, through `env_file`. After you edit it, run `docker-compose up -d`, which recreates the affected containers. A plain `restart` re-reads the mounted `/application.properties` copy in the portal, but **not** the environment variables (including `JAVA_OPTS`).

## Health and monitoring

| Signal | How to check | Meaning |
|---|---|---|
| Container health | `docker inspect -f '{{.State.Health.Status}}' portal` | `healthy` means the portal answers HTTP on 8080 |
| Spring health | `curl -s http://localhost:8080/actuator/health` | The `/actuator/**` routes are open without sign-in. The portal's RSA health indicator calls the connector's `testConnection()`, which validates or reopens the admin AM session, so `DOWN` usually means the connector cannot reach or authenticate to AM. |
| Scheduled jobs | Sign in as PMO, then **Admin → Manual Run Cron Jobs** | Shows each job's enabled and debug flags and its last-run message (in memory, cleared on restart) |
| Email | Look for `Could not send email` or `Email not sent to:` in the portal log | SMTP failures. Sending is asynchronous, so the UI may still report success. |

## Logs

| Source | Location | Notes |
|---|---|---|
| Portal (Log4j2) | `./logs/rsaapp-portal.log` on the host (container `/var/log`) | Rolled daily or at 10 MB into `./logs/rsaapp-portal-YYYY-MM/app-MM-dd-yyyy-N.log.gz`. Configured by `default_build/portal/log4j2.xml`. |
| Portal console | `docker-compose logs -f portal` | Same events, pattern `HH:mm:ss.SSS [thread] LEVEL logger - msg` |
| Connector | `docker-compose logs -f connector` | Packaged Log4j 1.x configuration logs to stderr at `DEBUG`. `default_build/log4j.xml` (rolling `/var/log/connector.log`, so `./logs/connector.log` on the host) applies only if you select it through `JAVA_OPTS`. |
| MySQL | `docker-compose logs mysql` | |
| All services | `docker-compose logs -f` | Ctrl+C to exit |

To change portal log levels, edit `default_build/portal/log4j2.xml` (for example the `com.winllc.rsaapp.portal` logger) and restart the portal.

## Database backups

### Nightly backup (installed by `install.sh -b`)

`install.sh -b <backup dir>` adds this line to the invoking user's crontab:

```cron
0 1 * * * <install dir>/util/backupdb.sh -u root -p root -d <backup dir>
```

`util/backupdb.sh`:

* runs `docker exec mysql /usr/bin/mysqldump -u <user> --password=<pw> RSAAPP | gzip > <backup>/db/db_backup_<dd_Mon_YYYY_HHMM>.sql.gz`;
* deletes files older than 7 days under `<backup>/db/` (`sudo find … -mtime +7 -exec rm {} \;`).

!!! warning "Check these before relying on the nightly backup"
    * **Credentials are hard-coded as `root`/`root`** in `install.sh`. If `MYSQL_ROOT_PASSWORD` is different, fix the crontab line (`crontab -e`).
    * **Create `<backup>/db/` first.** The script does not create it, and the redirect fails without it.
    * The `find` cleanup runs under `sudo`, so the cron user needs password-less `sudo` for it.

### Manual backup

```bash
mkdir -p /opt/backup/db
/opt/deployment/util/backupdb.sh -u root -p "$MYSQL_ROOT_PASSWORD" -d /opt/backup
# or directly:
docker exec mysql /usr/bin/mysqldump -u root --password="$MYSQL_ROOT_PASSWORD" RSAAPP | gzip > rsaapp_$(date +%F).sql.gz
```

Also keep copies of `dev.env`, `default_build/` (including `ssl/trust.jks`) and `docker-compose.yaml`. `install.sh -b` archives the whole install directory to `<backup>/rsaapp-backup.tar`.

## Restore

`restore.sh -b <backup dir>` restores the newest database dump and the archived install directory:

1. `docker-compose stop`
2. finds the newest `<backup>/db/*.gz` and decompresses it to `rsaapp_backup.sql` with `gzcat`
3. starts `mysql`, waits for `mysqladmin ping`, and pipes the dump into `mysql -u root --password=<MYSQL_ROOT_PASSWORD> RSAAPP`
4. `docker-compose stop mysql`, then `docker-compose down`
5. `tar xvf --overwrite <backup>/rsaapp-backup.tar -C .`
6. `docker load < services.img`, then `docker-compose up -d`

!!! warning "Known script issues: review before use"
    * `gzcat` is not normally present on RHEL/CentOS. Replace it with `zcat` (or `gunzip -c`).
    * The root password is read from `../${1:-dev}.env`, a file in the **parent** of the current directory named after the first argument. When the script is run as documented (`restore.sh -b <dir>`), the first argument is `-b`, so it looks for `../-b.env` and the password comes out empty. Set `DBPASSWORD` in the script by hand before running it.
    * `tar -cvf … "$install_directory"/*` stored absolute-style paths without the leading `/` (for example `opt/deployment/...`), so extracting with `-C .` recreates that tree under the current directory. Check the archive layout (`tar tvf`) and extract with the right `-C`.
    * `install.sh` does not copy `restore.sh` into the install directory during an upgrade. Use the copy from the package.

A manual restore that avoids these issues:

```bash
cd /opt/deployment
docker-compose stop portal connector
gunzip -c /opt/backup/db/db_backup_<stamp>.sql.gz | docker exec -i mysql mysql -u root --password="$MYSQL_ROOT_PASSWORD" RSAAPP
docker-compose start
```

## Upgrades

Use `install.sh -d <install dir> -b <backup dir>` from the new package. [Installation → Upgrade](install.md#upgrade) lists exactly what is copied, preserved and checked. After the upgrade:

1. Add any properties reported as missing to `dev.env`, then run `docker-compose up -d`.
2. Re-apply local `docker-compose.yaml` edits, because the file is overwritten.
3. Verify with the [checks in the install guide](install.md#verification).

For registry-based deployments (the Jenkins zip), run `docker-compose pull && docker-compose up -d` instead of loading `services.img`. Images are tagged with the Git tag and `latest` (see [Build & Release](build-and-release.md)).

## Certificate renewal

| Certificate | Where it lives | Renewal steps |
|---|---|---|
| **RSA AM root CA** (connector trust) | `default_build/ssl/trust.jks` (connector sees it as `/custom/ssl/trust.jks`, portal as `/ssl/trust.jks`) | Export the new root as in [Installation step 1](install.md#1-export-the-am-root-certificate-and-build-trustjks), import it into `trust.jks` (`keytool -import … -alias rsa_am_ca`, using a new alias if you keep the old CA during overlap), and run `docker-compose restart connector`. If the password changed, update `ims.ssl.client.trust.keystore.password` and `com.rsa.ssl.trust.store.password` and run `docker-compose up -d`. |
| **Portal HTTPS (8443)** | Keystore under `default_build/ssl/` (mounted at `/ssl`), referenced by your `server.ssl.*` settings | Replace the keystore (same path and alias, or update `server.ssl.*`), then `docker-compose restart portal`. The repository ships no TLS settings. See [Security](security.md#tls-and-client-certificates). |
| **Client-certificate trust (8443)** | The trust store referenced by `server.ssl.trust-store` (if configured) | Add the new issuing CAs for user PKI certificates and restart the portal. |
| **Command client password** | `com.rsa.cmdclient.user.password` | If AM regenerates it, read it again with `rsautil manage-secrets --action list`, update `dev.env`, and run `docker-compose up -d`. |

## Scaling the database (experimental)

`scripts/add_node.sh -u <ssh user> -n <node1,node2> [-f <dump file>]` tries to build a MySQL **group replication** cluster across hosts. It dumps `RSAAPP`, writes a `MYSQL_COMMAND` with `mysqld` replication flags to `remote.env`, recreates MySQL on each node over SSH, loads the dump, and bootstraps the group. The shipped `docker-compose.yaml` does **not** pass `MYSQL_COMMAND` to the `mysql` service, and the script uses `root`/`root`. Treat it as unfinished and test it before use.

## Routine checklist

| Frequency | Task |
|---|---|
| Daily | Confirm a new `db_backup_*.sql.gz` appeared in `<backup>/db/`. Check `docker ps` health. |
| Daily | Check **Admin → Manual Run Cron Jobs** for failed last-run messages. |
| Weekly | Review `logs/rsaapp-portal.log` for `ERROR` (AM session failures, email failures). |
| Before AM upgrades or certificate changes | Plan the `trust.jks` update and test sign-in afterwards. |
| Quarterly | Test a restore into a scratch environment. |

When something breaks, go to [Troubleshooting](troubleshooting.md).
