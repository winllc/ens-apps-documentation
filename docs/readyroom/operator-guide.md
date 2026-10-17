# Operations

This guide covers running ReadyRoom in production: starting and stopping it, checking its health,
reading its logs, looking after the database, the scheduled jobs, certificate rotation, monitoring
the workstation agents, and upgrades.

## Runtime overview

| Item | Value |
|---|---|
| Process | One Java 21 process in a container: the `app` service of the release package, or the `readyroom` Deployment of the Helm chart |
| Listener | Port **8181**: plain HTTP, or HTTPS once TLS is turned on. With `MANAGEMENT_SERVER_PORT` set (the Helm chart sets 9090), a separate plain-HTTP management port for the probes and Prometheus. |
| State | PostgreSQL (all records) and in-memory Caffeine caches (directory data). The server keeps no files of its own. |
| Sessions | HTTP sessions are held in memory. A restart signs out password users. Certificate users are signed in again on their next request. |
| Outbound | LDAP, PostgreSQL, SMTP. With Windows sign-in enabled, a domain controller is **not** contacted: tickets are checked with the keytab alone. |

!!! warning "Run a single instance"
    The scheduled jobs have no cluster coordination: every instance runs every job. With two
    instances, auto check-outs and absence alerts can be produced twice. The release package runs one
    container, and the Helm chart runs one replica; more are not supported. See
    [High availability](availability-and-sizing.md#high-availability).

## Start and stop

=== "Release package"

    Run these in the release directory (`readyroom-<version>/`):

    ```bash
    docker compose up -d              # start, or apply a changed .env
    docker compose ps                 # status; app becomes "healthy"
    docker compose logs -f app
    docker compose restart app
    docker compose stop               # stop, keep data
    ```

    `docker compose restart` does not re-read `.env`; run `docker compose up -d` after editing it.
    Never run `docker compose down -v`: it deletes the database and license volumes.

=== "Helm"

    ```bash
    kubectl -n readyroom get pods
    kubectl -n readyroom logs -f deploy/readyroom
    kubectl -n readyroom rollout restart deploy/readyroom
    kubectl -n readyroom scale deploy/readyroom --replicas=0    # stop, keep data
    kubectl -n readyroom scale deploy/readyroom --replicas=1
    ```

    Change settings in your values file and run `helm upgrade`. The names are for a release named
    `readyroom` in namespace `readyroom`.

At startup, check for these log lines:

```text
Default time zone set to America/New_York (from application.timezone=America/New_York)
LDAP connection pooling enabled for <context source bean>
Windows sign-in enabled for /api/check/in, /api/check/lock, /api/check/unlock as HTTP/...   (only if enabled)
```

The application does not start (it fails with a clear message) if `application.banner-text` is
missing, if Windows sign-in is enabled without a service principal or a readable keytab, if TLS
is on without a keystore, or without a truststore while client certificates are requested, if
header mode for client certificates lacks its proxy secret or CA, if a database migration fails,
or if a setting has an invalid value (the message names the variable).

It starts, with a warning, when: the directory is reached over `ldap://` without StartTLS; no mail
server is configured; no administrator is configured; certificates are in use with revocation
checking off; or `ENS_CSP_REPORT_ONLY` is on.

## Health and monitoring

| Path | Includes |
|---|---|
| `/actuator/health/liveness` | ReadyRoom's own state only, so an outage elsewhere never restarts it |
| `/actuator/health/readiness` | Also the database (`db`), the directory (`ldap`) and whether the audit trail can be written (`auditStore`). Never the mail server |
| `/actuator/health` | The aggregate, including the mail server unless `MANAGEMENT_HEALTH_MAIL_ENABLED=false` |

All three are open without sign-in and show the status only; component details are shown to `ADMIN`
users. Without `MANAGEMENT_SERVER_PORT` they are on port 8181. With it, the actuator moves to that
port, plain HTTP, and port 8181 has none.

- **Release package:** the container's health check calls `/actuator/health` on port 8181, or on
  `MANAGEMENT_SERVER_PORT` when that is set in `.env`.
- **Helm:** the startup and liveness probes use liveness and the readiness probe uses readiness, on
  the management port (9090) while `metrics.enabled` is `true` (the default). Tune them with
  `probes.*`.

```bash
curl -sk https://readyroom.example.com:8181/actuator/health/readiness
```

**Prometheus.** Set `MANAGEMENT_SERVER_PORT` (the Helm chart does, on 9090, with `metrics.enabled`)
and scrape `/actuator/prometheus` there, without sign-in. Unset, no metrics are exposed at all.
Never publish that port through an ingress, route or the host. With the Prometheus Operator, set
`metrics.serviceMonitor.enabled`; with `networkPolicy.enabled`, admit your Prometheus with
`metrics.allowFrom`. Besides Spring Boot's JVM, HTTP, database pool and Tomcat metrics, all tagged
`application="readyroom"`:

| Metric | Type | Meaning |
|---|---|---|
| `ens_license_mode{mode}` | gauge | 1 for the license mode in effect (`full`, `read_only`, `unlicensed`), 0 for the others |
| `ens_license_days_remaining` | gauge | Days until the license (or its grace period) ends; no value for a perpetual license |
| `ens_audit_events_total{category,outcome}` | counter | Audit events stored |
| `ens_audit_forward_failures_total{output}` | counter | Failed deliveries to an audit output (`splunk`, `syslog`) |
| `ens_auth_attempts_total{method,outcome}` | counter | Sign-ins by method (`ldap`, `x509`, `sso`, `api-token`, `session-id`) and outcome, the Windows client's included |
| `ens_readyroom_checkins_total{action}` | counter | The Windows client's events recorded (`in`, `lock`, `unlock`, `out`) |
| `ens_readyroom_people_in` | gauge | People whose latest event today is a check-in or unlock (refreshed at most once a minute) |
| `ens_readyroom_alerts_sent_total` | counter | Absence alert emails sent |
| `ens_readyroom_logoffs_refused_total{reason}` | counter | Logoffs refused: `unknown_session`, `rate_limited` |

Suggested alerts: readiness failing; `ens_readyroom_checkins_total` flat during working hours;
`ens_license_mode{mode="full"}` at 0, or `ens_license_days_remaining` under 30;
`ens_audit_forward_failures_total` rising; no new backup in a day.

### Request diagnostics

To find slow pages, set `application.diagnostics.request-metrics: true` and restart. Every request
then logs:

```text
GET /app/home -> 200: <n> SQL statement(s), <n> LDAP operation(s), <n>ms
```

Turn it off again afterwards.

## Logs

ReadyRoom logs to standard output through Spring Boot's default logging. There is no log file
unless you configure one (for example with `logging.file.name`). Read it with
`docker compose logs app` (release package) or `kubectl -n readyroom logs deploy/readyroom` (Helm).
`LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` writes each line as one JSON object (Elastic Common Schema);
the Helm chart does so by default (`logFormat: json`). The audit lines on stdout (logger `ens.audit`)
are raw `ens-audit/1` JSON either way.

| Level for `com.winllc.innoutwork` | What is logged |
|---|---|
| `info` (default) | Job summaries, forced check-outs (as a count), notifications sent, permission grants and revocations, org-chart rebuilds, directory-refresh changes, denied page access (`Access denied for GET /app/...`), and unexpected errors with stack traces. |
| `debug` | Each status event recorded, each forced check-out, authorization decisions, per-query detail and timings. |

Useful messages:

| Message | Meaning |
|---|---|
| `MarkInactiveCron checked out N inactive user(s) of M locked, in Xms` | The auto check-out job ran and checked people out. |
| `End SendAbsentNotificationsCron. Checked N user(s), sent M notification(s) in Xms` | One run of the absence-alert job (logged on working days). |
| `Notification <id> about <dn> sent to <email>` | An absence email was sent. |
| `Failed to send notification to <email>` | SMTP failure. A stack trace follows. |
| `Manager does not have an email address: <dn>` / `Manager not found in LDAP: <dn>` | An alert recipient could not be emailed or found. |
| `Directory refresh: N scanned, N created, N updated, ...` | The hourly directory refresh changed user records. |
| `RefreshUserRecordsCron failed; the next run will retry` | The directory refresh failed. A stack trace follows. |
| `Built org chart <name> with N top-level orgs (N nodes total) in Xms` | The org chart was rebuilt from the directory. |
| `Parse rule '<name>' (<regex>) did not match org value '<value>'` | A value was left out of the org chart. Fix the rule. |
| `Serving stale cached ... after refresh failure` | A background cache refresh from LDAP failed. The previous data is still being shown. |
| `<user> pinned their session system time to <date>` | Someone is viewing another day, which explains reports of "wrong-looking" data. |
| `Windows sign-in refused for <principal>: <reason>` | See [Troubleshooting](troubleshooting.md#windows-sign-in). |
| `<address> sent more than N logoffs with an unusable session id this minute; refusing them with 429 (application.logoff.refused-per-minute)` (`WARN`) | One client address had more than `refused-per-minute` logouts refused within a minute. See [Troubleshooting](troubleshooting.md#check-ins-not-arriving). |

## Scheduled jobs

All jobs run inside the application on a 3-thread scheduler (`spring.task.scheduling.pool.size`).
Each job waits for its previous run to finish before the configured delay starts, so runs never
overlap.

| Job (class) | Delay (packaged / built-in) | First run (packaged / built-in) | What it does |
|---|---|---|---|
| **Auto check-out** (`MarkInactiveCron`) | 60 s / 30 min (`inactive-cron.fixed-rate`) | 60 s / 30 min | For each user whose **latest event today is a screen lock** older than `check-out-after-minutes`, writes a `CHECK_OUT` timestamped at lock time + that many minutes, flagged `forced = true`. |
| **Absence alerts** (`SendAbsentNotificationsCron`) | 5 min / 30 min (`send-absent-notification-cron.fixed-rate`) | immediately / 30 min | On working days, raises at most one alert per person per day for users not in by their expected time plus the grace period. See [Administration](admin-guide.md#absence-notifications). |
| **Directory refresh** (`RefreshUserRecordsCron`) | 1 h / 1 h (`refresh-user-records-cron.fixed-rate`) | 5 min / 5 min | Reads every user from the directory and creates or updates `user_records` (DN, email, phone, organization, employee type, location, branch, duty sub-organization) in batches of changed rows. Set `refresh-user-records-cron.enabled: false` to pause it. |

The directory refresh also matters for alerts: the absence job only considers people who have a
`user_records` row, and the refresh creates one for every directory user.

!!! tip "Set the job timings for your organization"
    The release package's `.env` (`APPLICATION_CHECKOUTAFTERMINUTES`) and the Helm chart
    (`checkOutAfterMinutes`) check people out after **240 minutes** locked. The job delays keep
    the packaged values above (every minute and every 5 minutes) unless you set them. Adjust both
    to suit your organization.

In-memory caches are refreshed as they are used, not on a schedule: an entry is reloaded in the
background after `cache-duration-refresh-minutes` and discarded after
`cache-duration-expiration-minutes`. Group trees can be evicted by hand from **Settings**.

## Database

### Backups

=== "Release package"

    `./backup.sh`, in the release directory, backs up the database and the license volume while
    ReadyRoom keeps running:

    ```bash
    ./backup.sh                 # into ./backups
    ./backup.sh /mnt/backups    # or a directory of your own
    ```

    It writes, with a UTC timestamp, `readyroom-db-<stamp>.dump` (`pg_dump`, custom format) and
    `readyroom-license-<stamp>.tar.gz` (the license and this installation's id), and removes dumps
    older than `BACKUP_RETENTION_DAYS` (default 14; `0` keeps every one). Schedule it with cron, and
    copy the files off the host: a backup on the same disk does not survive the disk.

    ```cron
    17 2 * * * cd /opt/readyroom-1.2.3 && ./backup.sh >> backups/backup.log 2>&1
    ```

    To restore, `./restore.sh` stops ReadyRoom, replaces the database (and, if given, the license
    volume) with the backup's in one transaction, and starts it again:

    ```bash
    ./restore.sh backups/readyroom-db-20261017T021700Z.dump [backups/readyroom-license-20261017T021700Z.tar.gz]
    ```

    Everything recorded since that backup is lost. Restore into the same or a newer release, never an
    older one: on start, Flyway brings an older backup's schema up to date.

    Also keep `.env`, `license/`, `tls/` and `kerberos/`.

=== "Helm"

    With `backup.enabled`, a CronJob runs `pg_dump` (custom format, with the database image, so the
    version matches) into a PVC and deletes dumps older than `backup.retentionDays`. It works with
    your PostgreSQL or the embedded one, but is no substitute for your database's own backups and
    point-in-time recovery.

    | Value | Default | Meaning |
    |---|---|---|
    | `backup.enabled` | `false` | Turn on the CronJob `readyroom-backup` |
    | `backup.schedule`, `backup.timeZone` | `17 2 * * *`, the controller's | When it runs |
    | `backup.retentionDays` | `14` | Dumps older than this are deleted |
    | `backup.activeDeadlineSeconds` | `3600` | The longest one run may take |
    | `backup.persistence.*` | `10Gi`, ReadWriteOnce | The PVC the dumps go to, or `existingClaim` |

    To back up now: `kubectl -n readyroom create job --from=cronjob/readyroom-backup readyroom-backup-now`.
    The chart's `README.md` gives the restore steps. The license PVC (the installation id) is not
    in the dumps: snapshot it too, and copy the dumps off the cluster as well. Also keep your values
    file and the Secrets it names.

Everything that cannot be rebuilt from the directory is in the database: attendance history,
statuses, roles, notes, additional managers, group managers, notifications and responses, the
organization calendar and parse rules, and the audit trail. Take a backup before every upgrade.

### Retention

**ReadyRoom never deletes old attendance data.** The audit trail is pruned only when
`ENS_AUDIT_RETENTION_DAYS` is set (see [Audit logging](../audit-logging.md#retention)).
`check_in_out_records` grows by one row for every logon,
lock, unlock and logoff on every workstation. 5,000 users over 90 days make about 1.2 million
rows. `notification_records` and `user_event_records` also grow.
If you need a retention policy, apply it with SQL on a schedule of your own, after a backup. For
example:

```sql
-- Example only, not part of ReadyRoom: delete raw events older than two years.
DELETE FROM check_in_out_records WHERE timestamp < now() - interval '2 years';
```

Keep in mind that a person's average arrival is computed from the last 30 days, the Home page and
My Reports look back 30 days, and agent coverage looks back 90 days.

### Schema changes and migrations

Flyway owns the schema. The migrations are versioned `V<n>__<description>.sql` files inside the
application; at every start, before it serves anything, ReadyRoom applies the ones the database
does not have yet (recorded in `flyway_schema_history`), each in one transaction. Hibernate then
only validates the schema (`ddl-auto: validate`). Nothing is run by hand, and neither the release
package nor the Helm chart carries scripts. The current release has one migration, `V1__baseline`;
see [Installation → Prepare PostgreSQL](install.md#prepare-postgresql). Developer notes on adding a
migration are in `db/README.md` in the source.

## Certificate and key rotation

| What | How |
|---|---|
| **Server certificate** | Replace the keystore (release package: `tls/keystore.p12`; Helm: the `tls.keystore` Secret) and its password if it changed, then restart. Keep the host name that agents use in `BaseUrl`. |
| **Client CA** | Add the new CA to the truststore (`tls/truststore.p12`, or the `tls.truststore` Secret) **before** issuing certificates from it, then restart. Remove the old CA only after every user certificate has been reissued. |
| **User certificates** | Issue the renewed certificate with the same subject DN, which must remain the user's directory DN. The agent picks the newest valid certificate automatically unless `CertificateThumbprint` pins an old one. Clear or update that setting. Expired certificates are ignored. |
| **Kerberos keytab** | Re-export it with `ktpass` whenever the service account's password changes (`ktpass` itself sets a new password), replace `kerberos/http.keytab` or the keytab Secret, and restart. Check it with `klist -kte <keytab>` (the tools are in the container image). |
| **LDAP, database and SMTP passwords** | Release package: update `.env` and run `docker compose up -d`. Helm: update the Secret or values and run `helm upgrade` (or `kubectl rollout restart` for a changed Secret). |

To restart after replacing a file: `docker compose up -d --force-recreate app` (release package), or
`kubectl -n readyroom rollout restart deploy/readyroom` (Helm).

## Monitoring the client fleet

ReadyRoom shows agent health from the events it receives:

| Where | What it shows |
|---|---|
| **Metrics → Agent coverage** | The percentage of all users whose agent reported in the last 7 days, the number that **stopped** (reported before, but not in the last 7 days), and the number with **no activity in 90 days**. |
| **Home → Team agents** (anyone with reports) | Which of your reports' agents stopped reporting, and when each last reported. |
| **My Reports → Attendance by report** | When each report's agent last reported, with a **Quiet** badge after a week of silence. |
| **Home → Workstation agent** (every user) | *Not reporting* when nothing has arrived from the user's PC for 7 days. |

On the workstation itself, check the task's *Last Run Result* (the script's exit code), and the
logs: the Logout task's in `%ProgramData%\InOutWorker\logs\`, and the other tasks' in the user's
`%LOCALAPPDATA%\InOutWorker\logs\`. See [Windows client](windows-client.md#exit-codes).

A quick database check of the most recent event per user:

```sql
SELECT dn, max(timestamp) AS last_event
FROM check_in_out_records
WHERE dn IS NOT NULL
GROUP BY dn
ORDER BY last_event;
```

Every record names a user: a logout without a usable `sessionId` is refused with `401` and not
stored, and audited as a failed `auth.login` with the reason *unknown session*. Rows with
`dn IS NULL` can only have been written by an earlier version.

## Upgrades

1. Read the release's changes and note any new configuration property.
2. **Back up the database and the license volume**: `./backup.sh` (release package), or your
   database's backups and a license PVC snapshot (Helm).
3. Install the new release. ReadyRoom migrates its schema on start (Flyway).

    === "Release package"

        Unpack the new release next to the current one and run
        `./upgrade.sh ../readyroom-<old-version>` in the new directory. See
        [Installation → Upgrade](install.md#6-upgrade).

    === "Helm"

        ```bash
        helm upgrade readyroom oci://ghcr.io/winllc/charts/readyroom --version X.Y.Z \
            -n readyroom -f readyroom-values.yaml
        ```

4. Verify: readiness is `UP`, the log shows the migrations applied (or the schema up to date),
   the startup log lines look right, Home loads, and a test check-in arrives.
5. Re-run `Install-InOutWorker.ps1` from the new release's `windows-client/` folder on every
   workstation. Without arguments it keeps the existing `inoutworker.config.json`, installs the new
   script, re-registers the five tasks, and corrects the folder permissions an older client left on
   `%ProgramData%\InOutWorker`.

To roll back, see [Rolling back an upgrade](availability-and-sizing.md#rolling-back-an-upgrade).
Rolling back past a release that added migrations needs the database backup taken before the
upgrade.

## Runbook

| Symptom | First checks |
|---|---|
| Nobody's status updates | Is the server up (`/actuator/health/readiness`)? Are agents reaching it (workstation log; Metrics → Agent coverage)? Did the server certificate or truststore change? |
| One person's status never updates | Their workstation log and the task's *Last Run Result*. Is their certificate valid and issued with their directory DN? |
| People show `OUT` shortly after locking | `check-out-after-minutes` is too low. The default is 240. |
| No absence emails | SMTP errors in the log, the manager's email attribute, `application-base-url`, whether today is a weekend or holiday, and whether the person has an expected time. |
| Pages slow or hanging | LDAP response times. Turn on `request-metrics`. The timeouts are 5 s connect and 30 s read. |
| Group changes in the directory not visible | Cache lifetimes. Evict the group in **Settings → Group Cache**. Membership-based access is cached for 300 s. |
| "Wrong" data for one admin | They may have pinned another day with **System Date**. Look for `pinned their session system time` in the log. |

More detail on each problem is in [Troubleshooting](troubleshooting.md).
