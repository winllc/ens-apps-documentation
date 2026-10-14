# Operations

This guide covers running ReadyRoom in production: starting and stopping it, checking its health,
reading its logs, looking after the database, the scheduled jobs, certificate rotation, monitoring
the workstation agents, and upgrades.

## Runtime overview

| Item | Value |
|---|---|
| Process | One Java 21 process (`java $JAVA_OPTS -jar app.jar`) |
| Listener | HTTPS on `server.port` (default **8444**). There is no separate management port. |
| State | PostgreSQL (all records) and in-memory Caffeine caches (directory data). The server keeps no files of its own. |
| Sessions | HTTP sessions are held in memory. A restart signs out password users. Certificate users are signed in again on their next request. |
| Outbound | LDAP, PostgreSQL, SMTP. With Windows sign-in enabled, a domain controller is **not** contacted: tickets are checked with the keytab alone. |

!!! warning "Run a single instance"
    The scheduled jobs have no cluster coordination: every instance runs every job. With two
    instances, auto check-outs and absence alerts can be produced twice. Run one instance, or accept
    that duplication.

## Start and stop

=== "Jar / systemd"

    ```bash
    java $JAVA_OPTS -jar /opt/readyroom/in-n-out-work-1.0-SNAPSHOT.jar \
      --spring.config.additional-location=file:/etc/readyroom/application.yml
    ```

    Stop it with `SIGTERM` (for example `systemctl stop`). The repository does not include a
    systemd unit file.

=== "Docker"

    ```bash
    docker start readyroom
    docker stop readyroom
    docker restart readyroom
    docker logs -f readyroom
    ```

=== "Test stack (Compose)"

    ```bash
    cd test
    docker compose -f docker-compose.app.yml up -d --build
    docker compose -f docker-compose.app.yml logs -f app
    docker compose -f docker-compose.app.yml down
    ```

At startup, check for these log lines:

```text
Default time zone set to America/New_York (from application.timezone=America/New_York)
LDAP connection pooling enabled for <context source bean>
Windows sign-in enabled for /api/check/in, /api/check/lock, /api/check/unlock as HTTP/...   (only if enabled)
```

The application does not start (it fails with a clear message) if `application.banner-text` is
missing, or if Windows sign-in is enabled without a service principal or a readable keytab.

## Health and monitoring

Spring Boot Actuator exposes `health`, `info`, `metrics` and `env` under `/actuator` **without
authentication**:

```bash
curl -sk https://readyroom.example.com:8444/actuator/health
curl -sk https://readyroom.example.com:8444/actuator/metrics
curl -sk https://readyroom.example.com:8444/actuator/metrics/jvm.memory.used
```

`/actuator/health` returns the overall status. Spring Boot includes its auto-configured health
indicators for the configured database, LDAP and mail connections, so a broken dependency turns the
status to `DOWN`. Details are not shown with the default settings. If the
endpoints are reachable from untrusted networks, reduce
`management.endpoints.web.exposure.include` to `health`. See
[Security](security.md#unauthenticated-endpoints).

### Request diagnostics

To find slow pages, set `application.diagnostics.request-metrics: true` and restart. Every request
then logs:

```text
GET /app/home -> 200: <n> SQL statement(s), <n> LDAP operation(s), <n>ms
```

Turn it off again afterwards.

## Logs

ReadyRoom logs to standard output through Spring Boot's default logging. There is no log file
unless you configure one (for example with `logging.file.name`). With Docker, use `docker logs`.

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

!!! tip "Packaged values are development values"
    The packaged `application.yml` checks people out after **2 minutes** locked and runs the jobs
    every minute and every 5 minutes. Set `check-out-after-minutes` (built-in default 240) and the
    job delays to suit your organization.

In-memory caches are refreshed as they are used, not on a schedule: an entry is reloaded in the
background after `cache-duration-refresh-minutes` and discarded after
`cache-duration-expiration-minutes`. Group trees can be evicted by hand from **Settings**.

## Database

### Backups

ReadyRoom has no built-in backup. Back up the PostgreSQL database with your usual tooling, for
example:

```bash
pg_dump -Fc -h db.example.com -U readyroom readyroom > readyroom-$(date +%F).dump
# restore into an empty database:
pg_restore -d readyroom_restore readyroom-$(date +%F).dump
```

Everything that cannot be rebuilt from the directory is in the database: attendance history,
statuses, roles, notes, additional managers, group managers, notifications and responses, the
organization calendar and parse rules. Take a backup before every upgrade and before running a
migration script.

### Retention

**ReadyRoom never deletes old data.** `check_in_out_records` grows by one row for every logon,
lock, unlock and logoff on every workstation. The project's performance seed models 5,000 users
over 90 days as about 1.2 million rows. `notification_records` and `user_event_records` also grow.
If you need a retention policy, apply it with SQL on a schedule of your own, after a backup. For
example:

```sql
-- Example only, not part of ReadyRoom: delete raw events older than two years.
DELETE FROM check_in_out_records WHERE timestamp < now() - interval '2 years';
```

Keep in mind that a person's average arrival is computed from the last 30 days, the Home page and
My Reports look back 30 days, and agent coverage looks back 90 days.

### Schema changes and migrations

Hibernate (`ddl-auto: update`) adds new tables and columns at startup but does not create indexes,
does not drop anything, and does not change constraints. Indexes and constraints come from the
numbered scripts in `db/migrations/`, which you run yourself with `psql` (see
[Installation](install.md#2-prepare-postgresql)). Currently there is one script:
`001_indexes_and_unique_user_dn.sql`. It is safe to run again.

### Query performance

`db/perf/seed_volume.sql` fills an **empty, throwaway** database with realistic volume (by default
5,000 users over 90 days). To seed PostgreSQL in a container, measure the main queries before and
after the migration, and write a report to `build/reports/perf/query-performance.md` (needs
Docker):

```bash
PERF_MEASURE=1 ./gradlew test --tests '*QueryPerformanceMeasurement'
```

## Certificate and key rotation

| What | How |
|---|---|
| **Server certificate** | Replace the PKCS#12 file at `server.ssl.key-store` (and its password if it changed), then restart. Keep the host name that agents use in `BaseUrl`. |
| **Client CA** | Add the new CA to the truststore at `server.ssl.trust-store` **before** issuing certificates from it, then restart. Remove the old CA only after every user certificate has been reissued. |
| **User certificates** | Issue the renewed certificate with the same subject DN, which must remain the user's directory DN. The agent picks the newest valid certificate automatically unless `CertificateThumbprint` pins an old one. Clear or update that setting. Expired certificates are ignored. |
| **Kerberos keytab** | Re-export it with `ktpass` whenever the service account's password changes (`ktpass` itself sets a new password), replace the mounted file, and restart. Check it with `klist -kte <keytab>` (the tools are in the container image). |
| **LDAP, database and SMTP passwords** | Update the environment variables or the external configuration file, then restart. |

## Monitoring the client fleet

ReadyRoom shows agent health from the events it receives:

| Where | What it shows |
|---|---|
| **Metrics → Agent coverage** | The percentage of all users whose agent reported in the last 7 days, the number that **stopped** (reported before, but not in the last 7 days), and the number with **no activity in 90 days**. |
| **Home → Team agents** (anyone with reports) | Which of your reports' agents stopped reporting, and when each last reported. |
| **My Reports → Attendance by report** | When each report's agent last reported, with a **Quiet** badge after a week of silence. |
| **Home → Workstation agent** (every user) | *Not reporting* when nothing has arrived from the user's PC for 7 days. |

On the workstation itself, check the task's *Last Run Result* (the script's exit code), and the
log in `%ProgramData%\InOutWorker\logs\`. See [Windows client](windows-client.md#exit-codes).

A quick database check of the most recent event per user:

```sql
SELECT dn, max(timestamp) AS last_event
FROM check_in_out_records
WHERE dn IS NOT NULL
GROUP BY dn
ORDER BY last_event;
```

Rows with `dn IS NULL` are check-outs posted without a usable `sessionId`, usually because the
login post on that machine failed.

## Upgrades

1. Read the release's changes and note any new `db/migrations/` script or configuration property.
2. **Back up the database.**
3. Build the new jar or image (see [Installation](install.md#1-build-the-server)).
4. Stop the old instance and start the new one. Hibernate adds any new tables and columns.
5. Run any new migration scripts with `psql -v ON_ERROR_STOP=1 -f ...`, without
   `--single-transaction`. For `001`, deploy the application version that expects the unique DN
   with or after the migration.
6. Verify: health is `UP`, the startup log lines look right, Home loads, and a test check-in
   arrives.
7. If the Windows client changed, re-run `Install-InOutWorker.ps1` on workstations. Without
   arguments it keeps the existing `inoutworker.config.json` and re-registers the tasks.

To roll back, restore the previous jar or image. Columns added by Hibernate do no harm to an older
version. Restore the database backup only if a migration changed data you need back.

## Runbook

| Symptom | First checks |
|---|---|
| Nobody's status updates | Is the server up (`/actuator/health`)? Are agents reaching it (workstation log; Metrics → Agent coverage)? Did the server certificate or truststore change? |
| One person's status never updates | Their workstation log and the task's *Last Run Result*. Is their certificate valid and issued with their directory DN? |
| People show `OUT` shortly after locking | `check-out-after-minutes` is too low. The packaged value is 2. |
| No absence emails | SMTP errors in the log, the manager's email attribute, `application-base-url`, whether today is a weekend or holiday, and whether the person has an expected time. |
| Pages slow or hanging | LDAP response times. Turn on `request-metrics`. The timeouts are 5 s connect and 30 s read. |
| Group changes in the directory not visible | Cache lifetimes. Evict the group in **Settings → Group Cache**. Membership-based access is cached for 300 s. |
| "Wrong" data for one admin | They may have pinned another day with **System Date**. Look for `pinned their session system time` in the log. |

More detail on each problem is in [Troubleshooting](troubleshooting.md).
