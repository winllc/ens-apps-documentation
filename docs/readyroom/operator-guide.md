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
Database timeouts for <pool>: statement_timeout 570s, lock_timeout 120s (0: none)
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
| `/actuator/health/readiness` | Also the database (`db`), the directory (`ldap`) and whether the audit trail can be written (`auditStore`; from the next release its details also give `pending`, the audit events stored but not yet chained, and `oldestPending`, and `quarantined`, `newestQuarantined` and an `attention` flag for events set aside because they could not be chained; those keep it UP, since restarting or removing replicas would not help). Never the mail server |
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
| `ens_audit_pending` (next release) | gauge | Audit events recorded and not yet chained; normally near 0 |
| `ens_audit_quarantined` (next release) | gauge | Audit events that could not be chained and were set aside; see [Troubleshooting](troubleshooting.md#audit-trail) |
| `ens_auth_attempts_total{method,outcome}` | counter | Sign-ins by method (`ldap`, `x509`, `sso`, `api-token`, `session-id`) and outcome, the Windows client's included |
| `ens_readyroom_checkins_total{action}` | counter | The Windows client's events recorded (`in`, `lock`, `unlock`, `out`) |
| `ens_readyroom_people_in` | gauge | People whose latest event today is a check-in or unlock (refreshed at most once a minute) |
| `ens_readyroom_alerts_sent_total` | counter | Absence alert emails sent |
| `ens_readyroom_checkout_last_success_seconds` (from 0.0.11) | gauge | When the automatic check-out last ran without an error on that replica, in seconds since the epoch; `0` before its first run. The job runs on one replica at a time, so take the maximum over the replicas |
| `ens_readyroom_logoffs_refused_total{reason}` | counter | Logoffs refused: `unknown_session`, `rate_limited` |
| `ens_readyroom_requests_total{outcome}` | counter | Status requests `created`, `approved`, `rejected`, `cancelled` |
| `ens_readyroom_retention_purged_total{kind}` | counter | Records the retention purge deleted: `checkin`, `status`, `notification`, `request` |
| `ens_readyroom_webhook_deliveries_total{outcome}` | counter | Webhook delivery tries: `delivered`, `retry` (tried again later), `failed` (given up) |

Suggested alerts: readiness failing; `ens_readyroom_checkins_total` flat during working hours;
the newest `ens_readyroom_checkout_last_success_seconds` over the replicas more than a few minutes
old (`time() - max(ens_readyroom_checkout_last_success_seconds) > 600`; the job is paused, and the
value stands still, while the license does not include attendance tracking or is not in full effect);
`ens_license_mode{mode="full"}` at 0, or `ens_license_days_remaining` under 30;
`ens_audit_forward_failures_total` rising; `ens_audit_quarantined` above 0, or `ens_audit_pending` rising for
minutes (next release); `ens_readyroom_webhook_deliveries_total{outcome="failed"}` rising; no new backup in a day.

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
| `End SendAbsentNotificationsCron. Checked N user(s) on N day boundary(ies), sent M notification(s) in Xms` | One run of the absence-alert job. M counts the people alerted about, not people with nobody to alert. |
| `Absence check for <dn> failed; going on with the others` (`ERROR`), then `SendAbsentNotificationsCron could not check N user(s) this run; see the errors above` (`WARN`) | From 0.0.11: one person's unexpected error no longer ends the run for everyone after them. The run's `job.completed` audit event counts them as `failed`. Twenty such failures in a row still fail the run. |
| `Not alerting <dn> about <dn>: not a distinguished name ReadyRoom can use` (`WARN`) | A delegate or a group's alternate manager is stored as something that is not a DN, or is longer than 255 characters. That recipient is left out; the others are alerted. Correct the delegates or the group's managers. |
| `no directory manager, delegate or group manager for <dn>; no absence alert sent` (`WARN`) | The person is late, but has no directory manager, no delegate and no group with a manager, or none of them is found in the directory. Logged once a person a day; tried again at each run. Fix the reporting-line attributes or set a delegate. |
| `No absence alert about <dn> today: <reason>` | The person's directory entry is gone, or their account is disabled or expired, so nobody is alerted about them. |
| `No absence alert about <dn> this run, tried again next run: <reason>` (`WARN`) | The directory could not be read to find who to alert. Nothing was saved; the next run tries again. |
| `Notification <id> about <dn> sent to <email>` | An absence email was sent. |
| `Failed to send "Accountability Notification for <name>" to <address>` | SMTP failure. A stack trace follows. The alert e-mail job sends it again, within a day of the alert, while the alert is unanswered. |
| `Sent N absence alert e-mail(s) that had not gone out` | The alert e-mail job sent the emails of alerts recorded since its last run, or ones the mail server had not taken before. |
| `The mail server is not answering: no more absence alert e-mails this run; the alerts are recorded, and their e-mails are sent by a later run` (`WARN`) | The mail server could not be reached, timed out or refused the sign-in. The alert e-mail job stops that run and tries again 30 seconds later. Check `SPRING_MAIL_*` and the mail server. |
| `Manager does not have an email address: <dn>` / `Manager not found in LDAP: <dn>` | An alert recipient could not be emailed or found. |
| `Directory refresh: N scanned, N created, N updated, ...` | The hourly directory refresh changed user records. |
| `RefreshUserRecordsCron failed; the next run will retry` | The directory refresh failed. A stack trace follows. |
| `RefreshUserRecordsCron: the directory did not answer (<reason>); nothing changed, the next run will retry` (`WARN`) | The directory refresh could not list the directory's users. No record was changed; the refresh never removes, deactivates or counts out anyone. |
| `<method> <path>: the directory did not answer (<reason>); answering 503` (`WARN`) | A page or API call needed people from the directory, which did not answer after `application.ldap.read-retries` more tries. The user got `503` with `Retry-After: 5`; nothing was changed. |
| `Scheduled report <id> (<name>) put off: the directory did not answer for its recipients (<reason>)` (`WARN`) | The run is tried again; the report is not sent to some of its recipients only. |
| `Nobody e-mailed that request <id> of <dn> was <what>: the directory did not answer for who decides it` (`WARN`) | The request is saved and waits on its approvers' **Approvals** page; nobody was e-mailed in their place. |
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
| `Webhook endpoint <id> (<name>) disabled: no answer to 10 tries in a row, about a day; last: ...` (`WARN`) | From 0.0.11: the receiver gave no HTTP answer to 10 tries in a row, and the webhook was disabled (audited as `webhook.disabled`, actor `system`). Nothing is queued for it until an administrator chooses **Enable** on **Administration → Integrations**. |
| `N webhook deliveries given up: not delivered within 72 hours of being queued` (`WARN`) | From 0.0.11: deliveries that waited 3 days were marked failed (*expired*). See [Webhooks → Delivery](webhooks.md#delivery). |
| `Forced check-out for <dn> at <time>: end of day` (or `early leave at 15:00`) | An automatic check-out made before the usual limit, with the reason. Audited as `record.auto-checked-out`. |
| `ReadyRoom was not running from <time> to <time> (last instance up: <host>): events the Windows client sent then were lost. Absence alerts wait until <time> for people expected in by then.` (`WARN`) | An outage of the whole service was recorded. See [Availability](availability-and-sizing.md#high-availability). |
| `Job <name> has held its lock since before <time> and seems stuck; no longer extending it, so another replica can run the job` (`ERROR`) | From 0.0.11: a run has lasted 30 times the job's lock time (an hour for the automatic check-out). Its lock lapses and the job runs again, on this or another replica. Look for a database or directory that stopped answering, and restart the replica if the run never ends. |
| `More than N failed sign-ins a minute for one address (from <address>): sign-in refused for the rest of the minute` (or `for one username`) (`WARN`) | From 0.0.11: password sign-in was throttled. See [Security](security.md#ldap-username-and-password). |
| `Database timeouts for <pool>: statement_timeout 570s, lock_timeout 120s (0: none)` | Startup: the limits set on each database connection. See [Configuration](configuration.md#statement-and-lock-timeouts). |

## Scheduled jobs

All jobs run inside the application on a 16-thread scheduler (`spring.task.scheduling.pool.size`;
8 threads up to 0.0.10), a thread for each job with room to spare, so a slow run of one never
delays another. Each job waits for its previous run to finish before the configured delay starts,
so runs never overlap. With several replicas, each run holds a lock in the database (ShedLock,
table `shedlock`), so a job runs on one replica at a time; a replica that finds the lock held skips
that run. Expired sessions are also deleted once a minute, on one replica.

A lock is kept alive while its run lasts, but from 0.0.11 for at most 30 times the job's lock time
(an hour for the automatic check-out with the packaged one-minute delay). After that the lock
lapses and the job runs again, so a run that hangs, for example on a database connection lost
without a reset, no longer stops the job on every replica. `DB_SOCKET_TIMEOUT`,
`DB_STATEMENT_TIMEOUT` and `DB_LOCK_TIMEOUT` end such a run sooner (see
[Configuration → Database](configuration.md#database)).

| Job (class) | Delay (packaged / built-in) | First run (packaged / built-in) | What it does |
|---|---|---|---|
| **Auto check-out** (`MarkInactiveCron`) | 60 s / 30 min (`inactive-cron.fixed-rate`) | 60 s / 30 min | For each user whose **latest event is a screen lock** older than `check-out-after-minutes`, writes a `CHECK_OUT` timestamped at lock time + that many minutes, flagged `forced = true`. It looks back two days beyond that limit, and further for an outage of the whole service, so a lock left in the evening is checked out after midnight and a lock left before a long outage on the first run after it. A lock at or after the time of leaving of the person's *Early Leave* for that day is checked out on the next run instead, a second after the lock (`record.auto-checked-out` in the audit log); the early leaves are read in one query a run. From 0.0.11 it also keeps locks apart by workstation and closes sessions left open at the end of the day; see [Automatic check-out](#automatic-check-out). |
| **Absence alerts** (`SendAbsentNotificationsCron`) | 5 min / 30 min (`send-absent-notification-cron.fixed-rate`) | immediately / 30 min | Checks each person on their own day (their time zone and day start), on their working days only, and raises at most one alert per person per day for users not in by their expected time plus the grace period. From 0.0.11 it only records the alerts; the next job sends their emails. See [Administration](admin-guide.md#absence-notifications). |
| **Alert e-mails** (`SendAlertEmailsJob`, from 0.0.11) | 30 s | 30 s | Sends the emails of the absence alerts recorded in the last day that have not gone out and that nobody has answered, and marks each once the mail server has taken it (`notification_records.emailed_at`). A mail server that cannot be reached, times out or refuses the sign-in stops the run at that email; the next run tries again. One run sends for at most two minutes. Paused, like the absence alerts, without their license feature. Audited as `job.completed` (`sent`) only for a run that sent something. |
| **Directory refresh** (`RefreshUserRecordsCron`) | 1 h / 1 h (`refresh-user-records-cron.fixed-rate`) | 5 min / 5 min | Reads every user from the directory and creates or updates `user_records` (DN, email, phone, organization, employee type, location, branch, duty sub-organization) in batches of changed rows. Set `refresh-user-records-cron.enabled: false` to pause it. A sign-in only fills a record's empty columns, from the user cache, so changed directory values reach the record through this refresh. |
| **Retention purge** (`RetentionService`) | `0 17 2 * * *`, 02:17 daily (`application.retention.cron`) | – | Only with `application.retention.events-days` above 0: summarises and deletes old check-ins, statuses, requests and absence alerts, and deletes void check-ins. See [Retention](#retention). |
| **Audit retention** (`AuditRetention`) | `0 23 3 * * *`, 03:23 daily | – | Only with `ENS_AUDIT_RETENTION_DAYS` above 0: prunes delivered audit events. |

The directory refresh also matters for alerts: the absence job only considers people who have a
`user_records` row, and the refresh creates one for every directory user.

Besides these, webhook deliveries are sent every 5 seconds and scheduled reports checked every 5
minutes, each on one replica at a time (see [Webhooks](webhooks.md#delivery) and
[Reports](exports.md#scheduled-reports)). The service heartbeat, which every replica updates every
15 seconds, runs on a thread of its own from 0.0.11, so busy job threads are never taken for an
outage of the service.

### Automatic check-out

What the auto check-out job does beyond the plain limit, from 0.0.11:

- **Several workstations.** The Windows client sends its workstation's computer name with each
  event, and ReadyRoom keeps it with the record. A lock on one workstation is then not hidden by a
  later lock or logoff on another: it is still checked out automatically, and the forced check-out
  names the same workstation. A later check-in or unlock on any workstation means the person is
  back. A logoff is taken back to a lock only on its own workstation: someone who locks PC A at
  08:15 and logs off PC B at 17:00 left at 17:00. Events from a client that sends no name (a
  client older than 0.0.11) are read exactly as before.
- **Sessions left open.** A person whose last event is a check-in or an unlock (a PC left
  unlocked, or a lock the client could not send) used to stay `IN` until their next event, often
  the next day. The job now closes such a session at the cut-off of its day: `APPLICATION_ENDOFDAY_AT`
  (`HH:mm` in the person's own day), or, with that empty (the default), the end of their day, a
  second before their next day starts. It writes a forced check-out on the same workstation,
  audited as `record.auto-checked-out` with the reason `end of day`. When the person left is not
  known, so reports show no departure for that day (*No logoff recorded*, as before), while their
  status reads `OUT` with no check-out time. An administrator can void it like any automatic
  check-out, and it is not written again. The job looks for such sessions every five minutes.
  `APPLICATION_ENDOFDAY_CHECKOUT=false` keeps them open, as up to 0.0.10.
- **Outages.** A session locked before an outage of the whole service is checked out
  `check-out-after-minutes` after the lock plus the time the service was down since, because its
  unlock may have been lost: a two-minute restart postpones it by two minutes. Up to 0.0.10 the
  whole limit started again at the outage's end. The postponement is at most
  `APPLICATION_HA_MAXCHECKOUTPOSTPONEMENT` (`24h`; `0` never postpones).
- **Limits on a run.** The job reads the locked sessions, and writes each batch of 500 check-outs,
  in a transaction of at most 300 seconds. A run that fails is audited as `job.failed` and tried
  again a minute later. `ens_readyroom_checkout_last_success_seconds` says when it last ran
  without an error.

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
    older than `BACKUP_RETENTION_DAYS` (default 14; `0` keeps every one). Each file gets its name
    only once it is complete, so a run cut short leaves nothing that looks like a backup. The files
    are readable by their owner only. A directory the script creates is too; from 0.0.11 one that
    already exists keeps its permissions, so a shared or NFS directory you do not own works, and
    an off-host copy agent keeps its access. Schedule it with cron, and
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

    From 0.0.11 the script reads the license archive through before it changes anything, and stops
    with *is not a complete .tar.gz; nothing was changed* if it is truncated. It unpacks the archive
    beside the volume's contents and replaces them only once all of it has been read. If that step
    still fails, the message says that the database was restored and the license volume was not.

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
  (first check-in to last check-out each day, as the reports count it), days per status, and the
  month's working days for the person (their working days from their start date, less holidays,
  counted when the purge first reaches the month; months the purge finished before the next
  release have no count). A later run adds to a month already summarised; nothing is counted twice;
- **check-in records an administrator voided**, without a summary (they count for nothing), once
  they are a day older than the cutoff;
- **absence alerts**, by the day they were raised, and **status requests**, once their last day is
  past, without a summary. A request still waiting for a decision is first closed as `CANCELLED`,
  audited as `calendar.request-cancelled` (actor `system`, reason `retention`).

A person's day is their own, as everywhere else: in their profile's time zone, else their site's,
else the application's, from their site's day start, else the application's. The purge walks the
days once per zone and day start in use and summarises each person's records in their own days, up
to the start of the cutoff day where they are. It works one
day at a time, oldest first, 500 people per transaction, so it never holds a table for long. A lock
on the single row of `retention_state` stops two instances counting the same day twice. Each run is
audited as `data.purged` (actor `system`) with its counts (`requestsCancelled` and `voidCheckIns`
among them) and cutoff, and
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
| `V14__alert_answered_by_request` (next release) | `notification_records.status_response_request_id`: the approved status request that answered an absence alert (its days' alerts take the request's status as their answer). A request the retention purge or an erasure deletes clears the link and leaves the answer. One new column only |
| `V16__work_schedules` (next release) | `user_records.working_days` (`MTWTF..`; empty for Monday to Friday), `user_records.start_date`, `user_records.created_at`, and `attendance_summaries.working_days`, for [working days and start dates](admin-guide.md#working-days-and-start-dates). `created_at` is filled for existing people with the earliest of their check-ins and monthly summaries (empty for someone with neither). Nobody's schedule changes |
| `V17__record_corrections` (next release) | `check_in_out_records`: `voided_at`, `voided_by`, `void_reason`, `note`, `noted_by`, `noted_at`, `added_by`, `added_at`, `added_reason`, for [corrections to check-in records](admin-guide.md#correcting-check-in-records); and `notification_records.original_response`, `original_response_by_dn`, `original_response_date`, the alert answer an approved request superseded. New empty columns only: existing records are neither void nor corrected |
| `V18__audit_pending` (next release) | `audit_pending`: audit events stored with the action they record and waiting, normally under a second, for their place in the hash chain, which a chainer on each replica gives them in batches. Also `audit_quarantine`, where an event that can never be chained through its own fault is set aside, exactly as stored, with an `audit.chain-failed` event in its place in the chain; append-only (a trigger refuses UPDATE and DELETE). New tables only: `audit_events`, its rows and `audit_chain_head` are unchanged, and the trail verifies as before |
| `V19__training_status_and_leave_time` | Lets the status columns hold `TRAINING` (the four checks that list the statuses are replaced), and adds `leave_at_time`, an early leave's time of leaving, to `user_event_records` and `status_requests`. Empty on existing rows |
| `V20__status_recorded_by_decider` | `user_event_records.recorded_by_decider`: a status a manager, delegate or administrator recorded. `false` on existing days, which are read as before |
| `V21__last_sign_in` | `user_records.last_sign_in_at`: when each person last signed in to the web pages, shown in the page header. Empty until their next sign-in |
| `V22__check_in_machine` (0.0.11) | `check_in_out_records.machine`: the workstation's computer name the Windows client sends with each event, so a lock on one workstation is not hidden by a later lock or logoff on another. One new empty column: existing records, and clients that do not send it, are read as before |
| `V23__alerts_awaiting_answer_index` (0.0.11) | `ix_notifications_awaiting_for_dn`, an index of the unanswered alerts by recipient and time, for the bell and Home's **Awaiting your response**. A new index only |
| `V30__audit_pruned_through` (0.0.11) | `audit_chain_head.pruned_through_seq` and `pruned_through_hash`: how far audit retention has pruned, which **Verify integrity** now checks the oldest retained event against. An installation that has already pruned starts from the event before its oldest one. Two new columns and one `UPDATE` of the single chain head row |
| `V32__ens_license_latest_time_seen` (0.0.11) | `ens_license_installation.latest_seen_ms`: the latest time this installation has seen, so that setting the clock back does not bring an expired license back. Empty until the first license check after the upgrade |
| `V33__delegate_lookup_trigram_indexes` (0.0.11) | `ix_user_records_alt_managers_trgm` and `ix_group_records_alt_managers_trgm`: trigram indexes on the delegates and on groups' alternate managers, so finding whose delegate or which groups' alternate manager a person is no longer reads the whole table. Creates the `pg_trgm` extension; where it cannot, the migration logs a `WARNING`, adds nothing and succeeds. See [Installation → Prepare PostgreSQL](install.md#prepare-postgresql). New indexes only |

There is no V15, and no V24 to V29 or V31. V2 to V4 change only the schema, plus one `UPDATE` on `user_records`, so they are
quick; V6 to V11, V13, V14, V17, V18 and V20 to V22 add tables and columns only; V12 adds two columns and fills them
with one `UPDATE` each; V16 adds columns and fills `user_records.created_at` with one `UPDATE` that
reads each person's earliest check-in record and monthly summary; V19 replaces four checks and
validates them against the existing rows; V23 and V33 build indexes over `notification_records`,
`user_records` and `group_records`; V30 and V32 add columns, V30 with one `UPDATE` of the single
chain head row.

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
   `%ProgramData%\InOutWorker`. From 0.0.11 it stops, changing nothing, on a workstation whose
   configuration has no `BaseUrl`: pass `-BaseUrl` there.

### Upgrading to the approvals and retention release

| Change | What to do |
|---|---|
| People can no longer set Out of Office, Work From Home, Temporary Duty (TDY) or Scheduled Leave themselves (or Absent Excused through the API): the calendar sends them as requests | Tell your users and managers. Statuses already on calendars, future ones included, keep their effect; nothing is converted to a request. To let people set one again without approval, leave it out of `APPLICATION_EVENTS_REQUESTSTATUSES` |
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

New tables and columns (Flyway `V11` to `V14` and `V16` to `V20`; there is no `V15`), added at the
first start, in that order. Sign-in keeps working for every installation that kept its settings;
check the points below.

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
| Each person's day is their own: in their time zone (profile, else site, else `APPLICATION_TIMEZONE`), from their site's day start, else `APPLICATION_DAYSTART` (midnight) | Check-ins, the absence alert, Home, My Reports, the org chart, group reports, downloads and the public API's event `date` now follow each person's zone, not only the times on reports. Where sites work in other zones, set `APPLICATION_REPORTS_SITETIMEZONES`; where a site's shifts cross midnight, give it a day start (`APPLICATION_SITEDAYSTARTS`; Helm `dayStart`, `siteDayStarts`). **Settings → Days and Time Zones** shows the result. See [Configuration](configuration.md#time-zone-and-day-start). No migration |
| Per-person working days and start dates; leave left out of the 30-day attendance | Nobody's schedule changes: everyone keeps Monday to Friday. Set part-timers' and weekend workers' working days on their details pages (managers, delegates or administrators). Attendance figures may read higher once leave days are left out. See [Administration](admin-guide.md#working-days-and-start-dates) |
| Administrators correct check-in records (void, note, add), audited as `record.voided`, `record.annotated` and `record.added` | Tell administrators that the person sees each correction and its reason, and is e-mailed when a record is voided or added. Correct records only once every replica runs the new release: a replica of the previous release does not know a record is void. See [Administration](admin-guide.md#correcting-check-in-records) |
| People are e-mailed when a manager, delegate or administrator records a status that does not excuse the day (Absent, Absent Unexcused, Unscheduled Leave, Late Arrival, Early Leave), on their calendar or by answering an absence alert | Tell managers and users. Needs a mail server, with the same settings as absence alerts |
| An alert answer that an approved request superseded is kept and shown beside the request | Only for answers superseded from this release on. Earlier ones keep the old answer only in their `absence.alert-superseded` audit event |
| The personal data export adds `statusHistory` and `recordCorrections` from the audit trail, void records with their corrections, and the person's working days and start date | Update anything that reads the export. See [Privacy → Export](privacy.md#export) |
| *TDY* is shown as *Temporary Duty* (TDY), and *Training* (`TRAINING`) is a new status, treated as Temporary Duty: requested and approved by default, it excuses an absence and stays a working day (`V19` lets the status columns hold it) | Tell users and managers. Codes are unchanged in the API, webhooks, configuration and the database. If you set `APPLICATION_EVENTS_REQUESTSTATUSES`, add `TRAINING` to it for Training to need approval (otherwise people set it themselves, and startup warns). During a rolling update an older replica cannot read a Training day: let the update finish before people use it |
| An *Early Leave* can carry the time the person leaves (optional; `leave_at_time`, `V19`) | Nothing to do. It is shown on calendars, on **Approvals**, in the person's notice e-mail, in the API's absences (`leaveAtTime`) and in the personal data export (`leaveAtTime`), and changes nothing else: an early leave still excuses nothing |
| The user tables (Users, My Reports, the org chart's people) show a calendar status by its name (*Work From Home*, *Temporary Duty*) instead of its code (`WORK_FROM_HOME`, `TDY`) | Nothing to do: the people export's **Status today** column and the API keep the codes |
| A lock at or after an *Early Leave*'s time of leaving is checked out at once, not after `check-out-after-minutes` | Tell managers. Audited as `record.auto-checked-out` |
| An absence alert can be answered with any status that accounts for the day, Work From Home, Temporary Duty, Training and the other leave and work-elsewhere statuses included, listed by name in two groups; a status a manager, delegate or administrator records is their word on the day whatever it is (`recorded_by_decider`, `V20`) | Tell managers. Existing calendar days are read as before |
| Request e-mails and `GET /api/v1/absences/requests` carry a late arrival's and an early leave's time | Update anything that reads the leave-request items (`loginByTime`, `leaveAtTime`) |
| Audit events are added to the hash chain in the background (`V18`) | Nothing to do; a rolling update is safe. An audited action no longer waits for the one audit chain head row, so audited actions stop queuing behind each other. Each event is stored with its action as before, and joins the trail, the audit page, exports, the pull API and the outputs within about a second (`ENS_AUDIT_CHAIN_MILLIS`, see [Configuration](configuration.md#audit-trail)). Events already stored are untouched and verify as before. While old and new replicas run side by side, both add to the same chain under the same row lock, so it stays gapless. Each event's line goes to the stdout and audit file of the replica that chained it, not necessarily the one that recorded it (its `host` still names that one). A [rollback](availability-and-sizing.md#rolling-back-an-upgrade) restores the pre-upgrade backup, as for any release. If you instead start the previous release on this database, stop every replica of this one first (stopping chains what is waiting) and check that `SELECT count(*) FROM audit_pending` returns 0: the previous release does not read that table, so events left in it would stay out of the trail |

### Upgrading to 0.0.11

New columns and indexes (Flyway `V22`, `V23`, `V30`, `V32` and `V33`), added at the first start.
No setting is required for an installation that kept its settings, except the embedded database's
password with Helm; check the points below.

| Change | What to do |
|---|---|
| **Helm, embedded database:** `database.password` or `database.existingSecret` is required; the chart no longer generates a password, and a render without one fails | A release whose earlier chart generated the password keeps it across `helm upgrade`. Where the chart is rendered without access to the cluster (`helm template`, Argo CD, `--dry-run=client`), set `database.password` to the password the release already uses. See [Installation](install.md#evaluation-with-the-embedded-database) |
| The database pool is 20 connections a replica (`DB_POOL_SIZE`; Helm `database.poolSize`), 10 before | Keep the number of replicas times the pool size under PostgreSQL's `max_connections` (100 by default) |
| Database connections have a socket timeout (`DB_SOCKET_TIMEOUT`, 600 s), a statement timeout (`DB_STATEMENT_TIMEOUT`, 570 s) and a lock timeout (`DB_LOCK_TIMEOUT`, 120 s) | Nothing for most installations. A statement cut off fails its request (`500`) or its job's run, which is tried again. Migrations are not held to the statement and lock timeouts, but the socket timeout applies to them: raise it for the upgrade if one statement may run longer. A whole `SPRING_DATASOURCE_URL` of your own gets no socket timeout unless you add `tcpKeepAlive=true&socketTimeout=600`. Behind PgBouncer in transaction pooling, set the two PostgreSQL timeouts on the role. See [Configuration](configuration.md#statement-and-lock-timeouts) |
| `V33` creates the `pg_trgm` extension for two new indexes | Nothing where the database account owns the database (PostgreSQL 13 or later). Where it may not create the extension, the migration logs a `WARNING` and succeeds without the indexes; add them later with the statements in [Installation → Prepare PostgreSQL](install.md#prepare-postgresql) |
| Sessions left open (a check-in or unlock last, no lock or logoff) are checked out at the end of the person's day; the reports show no departure for such a day | Tell managers: someone who left their PC unlocked reads `OUT` once their day is over, not `IN` until the next morning. The first runs after the upgrade close the sessions left open over the last two days. `APPLICATION_ENDOFDAY_AT` (Helm `endOfDay.at`) sets an earlier cut-off; `APPLICATION_ENDOFDAY_CHECKOUT=false` (`endOfDay.checkOut: false`) keeps the earlier behaviour. Webhook receivers get a `status.changed` (`OUT`, `forced` `true`) for each |
| Home, the user tables, the people export and the public API's people give the same check-out time as the reports: the lock an automatic check-out followed, not the time the check-out was written. A past day that ended with a screen lock reads `OUT`, checked out at the lock. A day whose only records close the day before's session (an evening lock's check-out after midnight) reads no record, not `OUT` | Tell managers that check-out times on Home and in the user tables may read earlier than before, and update anything that compared them |
| Locks are kept apart by workstation, once the client sends its name (`V22`) | Re-run the installer on the workstations (step 5 above). An older client keeps working, without the name, and its events are read as before |
| An outage of the whole service postpones a locked session's check-out by the time down, not by the whole limit again (`APPLICATION_HA_MAXCHECKOUTPOSTPONEMENT`, `24h`) | Nothing to do. With one replica, people locked before a restart are checked out about on time |
| Absence alert emails are sent by a job of their own, every 30 seconds | Nothing to do. An alert's email follows the alert by up to half a minute, and a mail server that does not answer no longer delays finding who is absent |
| Password sign-in is throttled: 30 failed sign-ins a minute per client address, 5 per username (`APPLICATION_LOGIN_*`; Helm `login.*`) | Behind a reverse proxy that terminates TLS, set `SERVER_FORWARD_HEADERS_STRATEGY=framework` (the Helm chart does for an edge ingress or route), or every browser shares the proxy's address and its limit. See [Security](security.md#ldap-username-and-password) |
| Groups' object class and member attribute are settings (`APPLICATION_LDAP_GROUPOBJECTCLASS`, `APPLICATION_LDAP_GROUPMEMBERATTRIBUTE`; Helm `ldap.groupObjectClass`, `ldap.groupMemberAttribute`) | With Active Directory, set them to `group` and `member`: group trees, group reports, group managers' alerts and group-based visibility then work. Once set, the managers of people's groups start receiving their absence alerts: tell them. Unset, nothing changes |
| An administrator named in `application.super-user-dns` with spaces after the commas, or a trailing space, now signs in as `ADMIN` | Review the list: an entry that never matched starts to |
| Calendar reads are limited to `APPLICATION_EVENTS_MAXVIEWDAYS` (400) days | Update scripts that read `/api/event/...` or `/api/settings/calendar/all` over longer ranges: they get `400` `range_too_long` |
| An unexpected error answers `500` (a framework refusal its own `400`, `404` or `405`), with JSON under `/api`; such errors used to answer `200` with the error page | Update scripts and monitors that took any `200` for success. The Windows client took such an answer for an accepted check-in; it now logs the failure (exit code 3) |
| The name search behind the delegate and group-manager pickers (`GET /api/users/usersearch`) needs 3 characters and answers each match's `dn` and `cn` only, at most `application.ldap.search-max-results` | Update scripts that called it |
| Pages link to a person as `/app/user/details?dn=<DN>` and to a group as `/app/users?group=<DN>` | Nothing to do: bookmarks and old e-mail links with the DN in the path still open. People whose DN holds a comma in the name (`CN=Smith\, John`), a `/`, `#` or `?` can now be opened from the tables |
| A webhook that gives no HTTP answer to 10 tries in a row (about a day) is disabled, and a delivery still waiting after 3 days is given up | Watch for `webhook.disabled` with actor `system` in the audit trail, or the `WARN` in the log, and enable the webhook again on **Administration → Integrations** once its receiver answers. See [Webhooks](webhooks.md#delivery) |
| The bell and Home's **Awaiting your response** list the newest 50 alerts that can still be answered (`application.events.alert-answer-days`) | Tell managers of large groups: older unanswered alerts no longer show there. Each still opens from its e-mail link |
| **Verify integrity** on **Audit log** also fails when events are missing from the start of the trail without retention having pruned them, or from its end | A trail that someone deleted rows from by hand, which verified as intact before, is now reported broken, with the first missing `seq` |
| Syslog over `tcp` and `tls` reconnects when the collector has closed the connection and sends the last batch again | A collector restart can repeat up to one batch: de-duplicate on `id`. Where no event may be missing, use Splunk HEC or the pull API |
| License: an upload that has not started yet, or has expired, is refused while the installed license is in full effect; a clock set back more than a day no longer brings an expired license back | Upload next term's license on or after its start date. Keep the host's clock right |
| Windows client: no built-in server address; the installer keeps settings it is not given; the logon, lock and unlock tasks start a run for every event; a `sessionId` is never written into a session file another account created | Re-run `Install-InOutWorker.ps1` on every workstation, with `-BaseUrl` where the configuration has none. See [Windows client](windows-client.md#installation) |
| Release package: `backup.sh` leaves an existing backup directory's permissions alone; `restore.sh` checks the license archive first | If you relied on `backup.sh` to make a directory of your own private, set its mode yourself (`chmod 0700`) |

## Runbook

| Symptom | First checks |
|---|---|
| Nobody's status updates | Is the server up (`/actuator/health/readiness`)? Are agents reaching it (workstation log; Metrics → Agent coverage)? Did the server certificate or truststore change? |
| One person's status never updates | Their workstation log and the task's *Last Run Result*. Is their certificate valid and issued with their directory DN? |
| People show `OUT` shortly after locking | `check-out-after-minutes` is too low. The default is 240. |
| Nobody is checked out automatically any more; `ens_readyroom_checkout_last_success_seconds` is old | The audit trail has `job.failed` events for `MarkInactiveCron`, or the log has a database error or `Job MarkInactiveCron has held its lock ... and seems stuck`. Check the database connection and the timeouts (`DB_SOCKET_TIMEOUT`, `DB_STATEMENT_TIMEOUT`, `DB_LOCK_TIMEOUT`), and that the license is in full effect: the job is paused without one. From 0.0.11 a run that hangs gives up its lock after an hour at most, and the job runs again. |
| Someone shows `OUT` in the morning with no check-out time, though they never logged off | Expected from 0.0.11: their session was left open and was closed at the end of their day. See [Automatic check-out](#automatic-check-out). |
| A password sign-in says *Too many failed sign-ins. Wait a minute, then try again.* | Password sign-in is throttled per client address and per username. See [Troubleshooting](troubleshooting.md#sign-in-and-access). |
| No absence emails | SMTP errors in the log, the manager's email attribute, `application-base-url`, whether today is one of the person's working days (their schedule, on their own date) or a holiday, and whether the person has an expected time. |
| Pages slow or hanging | LDAP response times. Turn on `request-metrics`. The timeouts are 5 s connect and 30 s read. |
| Group changes in the directory not visible | Cache lifetimes. Evict the group in **Settings → Group Cache**. Membership-based access is cached for 300 s. |
| Recent audit events missing from **Audit log**, exports or the SIEM | They join the trail within about a second. If they stay out, readiness's `auditStore` details show `pending` and `oldestPending` (signed in as `ADMIN`), and the log says `Could not chain the recorded audit events`, with the database error. Nothing is lost meanwhile: the events stay stored and are chained once the cause is fixed. |
| **Audit log** warns that audit events could not be added to the chain; `ens_audit_quarantined` above 0 | Each was set aside in `audit_quarantine` with an `audit.chain-failed` event in its place; the trail stays intact and everything else is chained. See [Troubleshooting → Audit trail](troubleshooting.md#audit-trail). |
| "Wrong" data for one admin | They may have pinned another day with **System Date**. Look for `pinned their session system time` in the log. |

More detail on each problem is in [Troubleshooting](troubleshooting.md).
