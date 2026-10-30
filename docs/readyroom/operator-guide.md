# Operations

This guide covers running ReadyRoom in production: starting and stopping it, checking its health,
reading its logs, looking after the database, the scheduled jobs, certificate rotation, monitoring
the workstation agents, and upgrades.

## Runtime overview

| Item | Value |
|---|---|
| Process | One Java 21 process in a container: the `app` service of the release package, or each replica of the `readyroom` Deployment of the Helm chart |
| Listener | Port **8181**: plain HTTP, or HTTPS once TLS is turned on. With `MANAGEMENT_SERVER_PORT` set (the Helm chart sets 9090), a separate plain-HTTP management port for the probes and Prometheus. |
| State | PostgreSQL (all records) and in-memory Caffeine caches (directory data). The server keeps no files of its own. |
| Sessions | HTTP sessions are kept in the database (Spring Session JDBC), so any replica serves any request and a restart or rolling update signs nobody out. |
| Outbound | LDAP, PostgreSQL, SMTP. With Windows sign-in enabled, a domain controller is **not** contacted: tickets are checked with the keytab alone. |

!!! note "Several replicas"
    Sessions, scheduled-job locks and the license are in the database, so the Helm chart can run
    more than one replica (`replicaCount`, default 1) once the license volume is off. The release
    package runs one container. See
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
    kubectl -n readyroom scale deploy/readyroom --replicas=1    # or your replicaCount
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
| `ens_readyroom_requests_total{outcome}` | counter | Status requests `created`, `approved`, `rejected`, `cancelled` |
| `ens_readyroom_retention_purged_total{kind}` | counter | Records the retention purge deleted: `checkin`, `status`, `notification`, `request` |
| `ens_readyroom_webhook_deliveries_total{outcome}` | counter | Webhook delivery tries: `delivered`, `retry` (tried again later), `failed` (given up) |

Suggested alerts: readiness failing; `ens_readyroom_checkins_total` flat during working hours;
`ens_license_mode{mode="full"}` at 0, or `ens_license_days_remaining` under 30;
`ens_audit_forward_failures_total` rising; `ens_readyroom_webhook_deliveries_total{outcome="failed"}`
rising; no new backup in a day.

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
| `info` (default) | Job summaries, forced check-outs (as a count), notifications sent, org-chart rebuilds, directory-refresh changes, denied page access (`Access denied for GET /app/...`), and unexpected errors with stack traces. |
| `debug` | Each status event recorded, each forced check-out, authorization decisions, per-query detail and timings. |

Useful messages:

| Message | Meaning |
|---|---|
| `MarkInactiveCron checked out N inactive user(s) of M locked, in Xms` | The auto check-out job ran and checked people out. |
| `End SendAbsentNotificationsCron. Checked N user(s), sent M notification(s) in Xms` | One run of the absence-alert job (logged on working days). M counts the people alerted about, not people with nobody to alert. |
| `no directory manager or delegate for <dn>; no absence alert sent` (`WARN`) | The person is late, but has no directory manager and no delegate, or none of them is found in the directory. Logged once a person a day; tried again at each run. Fix the reporting-line attributes or set a delegate. |
| `No absence alert about <dn> today: <reason>` | The person's directory entry is gone, or their account is disabled or expired, so nobody is alerted about them. |
| `No absence alert about <dn> this run, tried again next run: <reason>` (`WARN`) | The directory could not be read to find who to alert. Nothing was saved; the next run tries again. |
| `Notification <id> about <dn> sent to <email>` | An absence email was sent. |
| `Failed to send notification to <email>` | SMTP failure. A stack trace follows. The next run of the alert job sends it again, the same day, while the alert is unanswered. |
| `Sent N absence alert e-mail(s) that had not gone out` | The alert job sent today's alert emails the mail server had not taken before. |
| `Manager does not have an email address: <dn>` / `Manager not found in LDAP: <dn>` | An alert recipient could not be emailed or found. |
| `Directory refresh: N scanned, N created, N updated, ...` | The hourly directory refresh changed user records. |
| `RefreshUserRecordsCron failed; the next run will retry` | The directory refresh failed. A stack trace follows. |
| `Built org chart <name> with N top-level orgs (N nodes total) in Xms` | The org chart was rebuilt from the directory. |
| `Parse rule '<name>' (<regex>) did not match org value '<value>'` | A value was left out of the org chart. Fix the rule. |
| `Serving stale cached ... after refresh failure` | A background cache refresh from LDAP failed. The previous data is still being shown. |
| `<user> pinned their session system time to <date>` | Someone is viewing another day, which explains reports of "wrong-looking" data. |
| `Windows sign-in refused for <principal>: <reason>` | See [Troubleshooting](troubleshooting.md#windows-sign-in). |
| `<address> sent more than N logoffs with an unusable session id this minute; refusing them with 429 (application.logoff.refused-per-minute)` (`WARN`) | One client address had more than `refused-per-minute` logouts refused within a minute. See [Troubleshooting](troubleshooting.md#check-ins-not-arriving). |
| `Status requests: [...] need approval; [...] are set by the person; [...] only managers and administrators record` | Startup: the status classes in effect. |
| `Statuses [...] stop or delay absence alerts but are not in application.events.request-statuses: people set them without approval` (`WARN`) | Startup: people can set those statuses without approval. Late Arrival is always named while people set it themselves: it delays their alert to the time they enter, within `application.events.late-arrival-latest-time` and `late-arrival-max-days`. |
| `N people have delegates (alternate managers) chosen before administrators managed them; ...` | Startup: delegates waiting for review under **Administration → Delegates**. |
| `Retention: check-in records, statuses, status requests and absence alerts older than N days are summarised by month and deleted (...)`, or `... are kept forever (...)` | Startup: the retention setting in effect. |
| `Retention purge: before <date> (N days kept), N check-in record(s), ... deleted over N day(s); N monthly summary write(s), in Xms` | One run of the retention purge. |
| `Mail "<subject>" sent to <address>` / `Failed to send "<subject>" to <address>` | A status request email was sent, or failed (a stack trace follows). Request emails are not retried. |
| `LDAP referrals: <value> for <bean>` | Startup: what a directory referral does (`spring.ldap.referral`, default `ignore`). See [Configuration](configuration.md#ldap-connection). |
| `Webhook endpoint <id> (<name>) has failed N tries in a row since it last took a delivery; last: ...` (`WARN`) | Every 5 failed tries in a row. Check the receiver, or disable the webhook on **Administration → Integrations**. Counted per sending replica, from its start. |
| `Webhook endpoint <id> (<name>) took a delivery again after N failed tries` | The receiver recovered. |

## Scheduled jobs

All jobs run inside the application on a 3-thread scheduler (`spring.task.scheduling.pool.size`).
Each job waits for its previous run to finish before the configured delay starts, so runs never
overlap. With several replicas, each run holds a lock in the database (ShedLock, table
`shedlock`), so a job runs on one replica at a time; a replica that finds the lock held skips that
run. Expired sessions are also deleted once a minute, on one replica.

| Job (class) | Delay (packaged / built-in) | First run (packaged / built-in) | What it does |
|---|---|---|---|
| **Auto check-out** (`MarkInactiveCron`) | 60 s / 30 min (`inactive-cron.fixed-rate`) | 60 s / 30 min | For each user whose **latest event is a screen lock** older than `check-out-after-minutes`, writes a `CHECK_OUT` timestamped at lock time + that many minutes, flagged `forced = true`. It looks back two days beyond that limit, so a lock left in the evening is checked out after midnight and a lock left during an outage is caught up. |
| **Absence alerts** (`SendAbsentNotificationsCron`) | 5 min / 30 min (`send-absent-notification-cron.fixed-rate`) | immediately / 30 min | On working days, raises at most one alert per person per day for users not in by their expected time plus the grace period. See [Administration](admin-guide.md#absence-notifications). |
| **Directory refresh** (`RefreshUserRecordsCron`) | 1 h / 1 h (`refresh-user-records-cron.fixed-rate`) | 5 min / 5 min | Reads every user from the directory and creates or updates `user_records` (DN, email, phone, organization, employee type, location, branch, duty sub-organization) in batches of changed rows. Set `refresh-user-records-cron.enabled: false` to pause it. A sign-in only fills a record's empty columns, from the user cache, so changed directory values reach the record through this refresh. |
| **Retention purge** (`RetentionService`) | `0 17 2 * * *`, 02:17 daily (`application.retention.cron`) | – | Only with `application.retention.events-days` above 0: summarises and deletes old check-ins, statuses, requests and absence alerts. See [Retention](#retention). |
| **Audit retention** (`AuditRetention`) | `0 23 3 * * *`, 03:23 daily | – | Only with `ENS_AUDIT_RETENTION_DAYS` above 0: prunes delivered audit events. |

The directory refresh also matters for alerts: the absence job only considers people who have a
`user_records` row, and the refresh creates one for every directory user.

!!! note "Demo mode (next release)"
    In demo mode (`demo.enabled`) the jobs that write attendance data do not run: the directory
    refresh, auto check-out, absence alerts and scheduled reports (each logs once that it is
    skipping), and the retention purge. Audit retention, the clean-up of ended sessions, the
    license mode check and the service heartbeat keep running.

!!! tip "Set the job timings for your organization"
    The release package's `.env` (`APPLICATION_CHECKOUTAFTERMINUTES`) and the Helm chart
    (`checkOutAfterMinutes`) check people out after **240 minutes** locked. The job delays keep
    the packaged values above (every minute and every 5 minutes) unless you set them. Adjust both
    to suit your organization.

In-memory caches are refreshed as they are used, not on a schedule: an entry is reloaded in the
background after `cache-duration-refresh-minutes` and discarded after
`cache-duration-expiration-minutes`. Group trees can be evicted by hand from **Settings**, on every
replica: each reads the evictions others recorded every `application.cache-invalidation-poll-millis`
(10 s).

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
    volume) with the backup's, and starts it again:

    ```bash
    ./restore.sh backups/readyroom-db-20261017T021700Z.dump [backups/readyroom-license-20261017T021700Z.tar.gz]
    ```

    Everything recorded since that backup is lost. Restore into the same or a newer release, never an
    older one: on start, Flyway brings an older backup's schema up to date. The script replaces the
    whole `public` schema, not only what the dump holds, so no table a newer release created survives
    to stop Flyway at startup with "relation already exists". It replays the dump as the database's
    owner in one transaction, committed only once the whole dump was read and every statement
    succeeded: a failed or truncated restore leaves the database unchanged, and the script stops with
    ReadyRoom stopped (`./compose.sh up -d` starts it again).

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
    The chart's `README.md` gives the restore steps. Like `restore.sh`, they replace the whole
    `public` schema in one transaction, as the account that owns ReadyRoom's tables and the database
    (`database.username`, or `database.migration.username` with a migration account), and on any
    error leave the database unchanged. The dumps include the license and the
    installation id, which are in the database (`ens_license_*`); the license PVC is only read on
    the first start of the release that moved them there. Copy the dumps off the cluster as well.
    Also keep your values file and the Secrets it names.

Everything that cannot be rebuilt from the directory is in the database: attendance history,
statuses, roles, notes, additional managers, group managers, notifications and responses, the
organization calendar and parse rules, the audit trail, and the license and installation id. Take a backup before every upgrade.

### Retention

**By default ReadyRoom keeps everything.** `check_in_out_records` grows by one row for every
logon, lock, unlock and logoff on every workstation: 5,000 users over 90 days make about 1.2
million rows. `user_event_records`, `status_requests` and `notification_records` grow too.

Set `APPLICATION_RETENTION_EVENTSDAYS` (Helm: `retention.eventsDays`) to keep them for that many
days: `0` (the default) keeps them forever; otherwise at least 31, because the home page, My
Reports and the average login time read the last 30 days. The purge then runs on
`APPLICATION_RETENTION_CRON` (default `0 17 2 * * *`, 02:17 daily, in ReadyRoom's time zone) and
deletes what is older than the cutoff:

- **check-in records and calendar statuses**, after rolling them up into one summary per person
  per month (`attendance_summaries` and `attendance_summary_statuses`): days checked in, time in
  (first check-in to last check-out each day, as the reports count it) and days per status. A later
  run adds to a month already summarised; nothing is counted twice;
- **absence alerts**, by the day they were raised, and **status requests**, once their last day is
  past, without a summary. A request still waiting for a decision is first closed as `CANCELLED`,
  audited as `calendar.request-cancelled` (actor `system`, reason `retention`).

A person's day is their own, as in the reports and exports: in their profile's time zone, else
their site's, else the application's. The purge walks the days once per zone in use and summarises
each person's records in their zone, up to the start of the cutoff day where they are. It works one
day at a time, oldest first, 500 people per transaction, so it never holds a table for long. A lock
on the single row of `retention_state` stops two instances counting the same day twice. Each run is
audited as `data.purged` (actor `system`) with its counts (`requestsCancelled` among them) and
cutoff, and
counted in `ens_readyroom_retention_purged_total{kind}`; a failed run is `job.failed`, and the next
run carries on. **Administration → Privacy** shows the setting, the cutoff and the last purge.

**What reports show for purged periods.** Before the cutoff there are no per-day records: the
group report shows no check-in times, durations or statuses for those days, and adds a
*Before &lt;date&gt;: monthly summaries* table with each member's days checked in, time in and days
per status. Calendars show nothing for purged days. The home page, My Reports and metrics read at
most the last 30 days, which retention never reaches.

**What retention does not touch:** the monthly summaries (kept until a person is erased), the user
records, and the audit trail, which has its own retention, `ENS_AUDIT_RETENTION_DAYS` (see
[Audit logging](../audit-logging.md#retention)). Backups keep purged data until they expire.

For what ReadyRoom keeps about people and for how long, see
[Privacy, DPIA and works council](privacy.md).

### Schema changes and migrations

Flyway owns the schema. The migrations are versioned `V<n>__<description>.sql` files inside the
application; at every start, before it serves anything, ReadyRoom applies the ones the database
does not have yet (recorded in `flyway_schema_history`), each in one transaction. Hibernate then
only validates the schema (`ddl-auto: validate`). Nothing is run by hand, and neither the release
package nor the Helm chart carries scripts. Developer notes on adding a migration are in
`db/README.md` in the source.

| Migration | What it does |
|---|---|
| `V1__baseline` | The schema as earlier releases left it, with missing indexes and constraints, the audit trail's append-only trigger, and duplicate user records merged. See [Installation → Prepare PostgreSQL](install.md#prepare-postgresql) |
| `V2__status_requests` | `status_requests`, for statuses that need approval |
| `V3__admin_managed_delegates` | Widens the delegates column to 2000 characters, and flags delegates people chose for themselves for an administrator's review |
| `V4__retention_summaries` | `attendance_summaries`, `attendance_summary_statuses` and `retention_state`, for retention |
| `V5__logoff_client_address` | `check_in_out_records.client_address`: the logoff that cannot sign in is bound to the address its logon came from. Earlier check-ins are marked as usable from any address until they expire or are used |
| `V6__high_availability` | `shedlock`, `spring_session`, `spring_session_attributes`, `service_heartbeat` and `service_outage`, for several replicas |
| `V7__single_sign_on` | `sso_saml_request` and `sso_assertion_seen`, for [SAML single sign-on](single-sign-on.md) across replicas |
| `V8__ens_license_state` | `ens_license_*`: the license, installation id and revisions in the database, imported from the license volume at the first start |
| `V9__public_api` | `api_tokens`, `webhook_endpoints` (signing secrets encrypted) and `webhook_deliveries`, for the [public API](api-reference.md#public-api-apiv1) and [webhooks](webhooks.md). New tables only |
| `V10__reports` | `user_records.time_zone` and `report_schedules`, for [time zones and scheduled reports](exports.md) |
| `V11__rate_limit_windows` (next release) | `rate_limit_windows`: the rate limits' counts (public API tokens, refused logoffs), shared by every replica. A new table only |
| `V12__alert_read_and_emailed` (next release) | `notification_records.read_at` (an alert marked read, which no longer counts as answered) and `notification_records.emailed_at` (when its email went out, so the next alert run resends today's unsent ones). Existing alerts count as emailed, and ones marked read before stay off the list |
| `V13__cache_invalidations` (next release) | `cache_invalidations`: the group cache evictions made on Settings, which every replica applies. A new table only |

V2 to V4 change only the schema, plus one `UPDATE` on `user_records`, so they are quick; V6 to V11
and V13 add tables and columns only; V12 adds two columns and fills them with one `UPDATE` each.

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
2. **Back up the database and the license volume.** Release package: `upgrade.sh` does this first
   (below). Helm: your database's backups and a license PVC snapshot.
3. Install the new release. ReadyRoom migrates its schema on start (Flyway).

    === "Release package"

        Unpack the new release next to the current one and run
        `./upgrade.sh ../readyroom-<old-version>` in the new directory. It first runs the old
        release's `backup.sh`, into that directory's `backups/`, and stops without changing
        anything if the backup fails. See [Installation → Upgrade](install.md#6-upgrade).

    === "Helm"

        ```bash
        helm upgrade readyroom oci://ghcr.io/winllc/charts/readyroom --version X.Y.Z \
            -n readyroom -f readyroom-values.yaml
        ```

4. Verify: readiness is `UP`, the log shows the migrations applied (or the schema up to date),
   the startup log lines look right, Home loads, and a test check-in arrives.
5. Re-run `Install-InOutWorker.ps1` from the new release's `windows-client/` folder on every
   workstation. Without arguments it keeps the existing `inoutworker.config.json`, installs the new
   script, re-registers the six tasks, and corrects the folder permissions an older client left on
   `%ProgramData%\InOutWorker`.

### Upgrading to the approvals and retention release

| Change | What to do |
|---|---|
| People can no longer set Out of Office, Work From Home, TDY or Scheduled Leave themselves (or Absent Excused through the API): the calendar sends them as requests | Tell your users and managers. Statuses already on calendars, future ones included, keep their effect; nothing is converted to a request. To let people set one again without approval, leave it out of `APPLICATION_EVENTS_REQUESTSTATUSES` |
| Only administrators set delegates; `POST /api/users/managers/update` is removed | Review the delegates people chose themselves under **Administration → Delegates**. Until then they keep receiving alerts and may now also approve requests |
| `POST /api/event/update` returns `{status, fromDate, toDate, outcome, requestId?, message?}`, answers bad input with `400` instead of `500`, accepts plain `yyyy-MM-dd` dates, and refuses ranges over 366 days | Update scripts that call it |
| Profile note changes are audited as `notesChanged`, without the text | Events from earlier releases keep the old and new text until audit retention prunes them |
| New settings keep the old behaviour: coworker visibility `full`, retention `0` (forever) | Decide on [coworker visibility and retention](configuration.md#statuses-requests-and-privacy) |
| New menu entries: **Approvals**, **My data**, and **Delegates** and **Privacy** for admins | |

To roll back, see [Rolling back an upgrade](availability-and-sizing.md#rolling-back-an-upgrade).
Rolling back past a release that added migrations needs the database backup taken before the
upgrade.

### Upgrading to the release with single sign-on, the public API and exports

Nothing to do by hand: Flyway adds the tables above, and nothing existing changes.

| Change | What to do |
|---|---|
| [Single sign-on](single-sign-on.md), off until enabled | Set `APPLICATION_BASE_URL` to the absolute URL users reach before enabling it; SAML needs HTTPS |
| The [public API](api-reference.md#public-api-apiv1) at `/api/v1` | Reachable once an administrator creates a token; nothing but its OpenAPI document is reachable without one. Put `/api/v1` behind the same ingress rules as the rest of the application |
| [Webhooks](webhooks.md) stay off until `APPLICATION_WEBHOOKS_SECRETKEY` is set | Helm: `webhooks.enabled: true` with `webhooks.secretKey` (a value or your Secret); release package: set it in `.env`. With the chart's NetworkPolicy on, egress to port 443 is then opened for them (`webhooks.ports`). Use the same key on every replica and keep it across upgrades |
| Owners of tokens, webhooks and scheduled reports are checked live in the directory | The bind account must read the account-status attributes listed in [API reference → Tokens](api-reference.md#tokens) |
| Site time zones | Set `APPLICATION_REPORTS_SITETIMEZONES` where people work in more than one zone; until then reports look as before |
| Calendar colours | The organization calendar's and requested statuses' default colours are darker ([Accessibility](accessibility.md)); colours you configured are kept |

### Upgrading to the next release

New tables and columns (Flyway `V11` to `V13`), added at the first start. Sign-in keeps working
for every installation that kept its settings; check the points below.

| Change | What to do |
|---|---|
| Client-certificate revocation is checked by default: with `ENS_X509_REVOCATION` unset (Helm `x509.revocation.mode` now defaults to `""`), OCSP then CRL, soft-fail | A revoked certificate is refused (audited `certificate revoked`); one no responder answers for still signs in, with a WARN. An installation that set `ENS_X509_REVOCATION` (or `x509.revocation.mode`) keeps exactly what it set, `off` included. `ENS_X509_REVOCATION_FAIL` now defaults to `open` while the mode is unset and to `closed` once a mode is set; `closed` set without a mode is honoured, so make sure ReadyRoom reaches your OCSP responders and CRL distribution points, or set `ENS_X509_CRL_DIR`, or set the mode to `off`. With the chart's NetworkPolicy on and certificate sign-in on, egress to ports 80 and 443 is opened unless the mode is `off` (or `crl` with `crlConfigMap`). Where a firewall silently drops the traffic, a sign-in can wait up to 5 s per responder and distribution point; see [Revocation from the next release](install.md#revocation-from-the-next-release) |
| Single sign-on trust material over HTTPS only | `ENS_SSO_SAML_IDP_METADATA` must be `https://` or a file, and `ENS_SSO_OIDC_ISSUER_URI` (with the endpoints and key set its discovery document names) `https://`; `http://` stops startup naming the setting. Serve the metadata over https or download it to a file |
| SAML needs an `https://` `APPLICATION_BASE_URL` | With `ENS_SSO_SAML_ENABLED=true` and an `http://` `APPLICATION_BASE_URL` (Helm `publicUrl`), startup stops instead of warning. SAML could not have worked there: its browser-binding cookie is never sent over HTTP |
| Demo mode runs no writing jobs, and refuses Windows sign-in | Production installations (demo mode off) are not affected |
| Release package: `env.template` has a *Single sign-on* block | Copy the `ENS_SSO_*` lines into an older `.env` to use single sign-on |
| Rate limits hold across replicas | A public API token's requests a minute and refused logoffs per client address (`application.logoff.refused-per-minute`) are counted in the database for all replicas together. With N replicas a token now gets its rate, not up to N times it, so an integration that relied on the multiplied rate may see `429` sooner: raise its token's rate if so. See [Availability](availability-and-sizing.md#high-availability) |
| Webhooks through an egress proxy | New and unset: webhooks keep connecting directly. `APPLICATION_WEBHOOKS_PROXY` (`host:port`; Helm `webhooks.proxy.address`), with `APPLICATION_WEBHOOKS_PROXYCREDENTIALS` or `APPLICATION_WEBHOOKS_PROXYCREDENTIALSFILE`, sends them through an HTTP proxy. See [Webhooks → Through an egress proxy](webhooks.md#through-an-egress-proxy) |
| **Settings** is for administrators only | People with only the `MANAGER` role no longer see it: the organization calendar, parse rules and group cache apply to everyone |
| Only administrators set a group's alternate managers, each someone in the directory; `POST /api/permissions/update` is removed | Groups' managers no longer see the **Group Managers** card. Access to groups follows directory membership, as it already did. Update scripts that called the endpoint |
| `ADMIN` is never given from a person's page, and the last administrator keeps it | Name administrators in `application.super-user-dns`. **Administration → Privacy** lists them |
| *Late Arrival* and *Early Leave* no longer count as accounted for; one set on a range reaching a day before today is a request, and *normal* there is refused (`400` `past_day`) | Tell managers that the accounted-for figures, the 30-day attendance and the org chart may read lower for people who set these without checking in. Update scripts that call `POST /api/event/update` |
| **Mark All Read** marks alerts read without answering them | Alerts stay under **Awaiting your response** and in the bell until answered. Alerts marked read before the upgrade stay off the list |
| Erasure also deletes webhook deliveries about the person, revokes their API tokens, disables their webhooks and ends their sessions | See [Privacy → Erasure](privacy.md#erasure) |
| LDAP referrals are ignored explicitly (`spring.ldap.referral`, default `ignore`), and so is the partial result they leave | Nothing to do: an Active Directory searched from its domain root now returns its entries instead of failing. `follow` chases referrals instead (the other servers must accept the same bind account) |
| Signing out of the identity provider too | `ENS_SSO_OIDC_END_SESSION` and `ENS_SSO_SAML_SINGLE_LOGOUT` (Helm `sso.oidc.endSession`, `sso.saml.singleLogout`) default to `false`, so an upgraded installation signs out exactly as before; the release bundle's `env.template` sets both to `true` for new installs. Before turning them on, register `<base>/signed-out` as a post-logout redirect URI at the OIDC provider; for SAML set the SP key and certificate and re-import the SP metadata at the IdP. See [Single sign-on → Signing out](single-sign-on.md#signing-out) |

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
