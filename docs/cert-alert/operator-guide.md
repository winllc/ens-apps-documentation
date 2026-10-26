# Operator guide

Day-to-day running of a CertAlert deployment: starting and stopping, health, logs, the scheduled
jobs, backups, monitoring, upgrades and scaling. For in-application administration (buttons and
screens) see the [Admin guide](admin-guide.md); for symptoms and fixes see
[Troubleshooting](troubleshooting.md).

## Start and stop

=== "Release package"

    Run these in the release directory (`cert-alert-<version>/`):

    ```bash
    docker compose up -d             # start, or apply a changed .env
    docker compose ps                # status and health
    docker compose logs -f cert-alert
    docker compose restart cert-alert
    docker compose stop              # stop, keep data
    docker compose down              # remove containers, keep volumes
    ```

    `docker compose restart` does not re-read `.env`. After editing `.env`, run
    `docker compose up -d`.

    !!! danger "`docker compose down -v` deletes your data"
        It removes the database volume and the `ens-license` volume, and with it the
        installation id.

=== "Helm"

    ```bash
    kubectl -n cert-alert get pods
    kubectl -n cert-alert logs -f deploy/cert-alert
    kubectl -n cert-alert rollout restart deploy/cert-alert
    kubectl -n cert-alert scale deploy/cert-alert --replicas=0   # stop, keep data
    kubectl -n cert-alert scale deploy/cert-alert --replicas=1   # start again
    ```

    Change settings in your values file and run `helm upgrade` (see [Upgrades](#upgrades)).
    The names above are for a release named `cert-alert`.

Shutdown is graceful (`server.shutdown: graceful`, 30s per phase): in-flight requests finish and
the changelog connector stops between changes rather than mid-batch. In the container the JVM is
PID 1 and receives `SIGTERM` directly.

## Health checks

| Endpoint | Access | Use |
|---|---|---|
| `/actuator/health/liveness`, or `/livez` | public | **Liveness probe**: the process alone (`livenessState`) |
| `/actuator/health/readiness`, or `/readyz` | public | **Readiness probe**: the database (`db`) and a writable audit log (`auditLog`, the `ENS_AUDIT_FILE` output when it is set). Not the directory |
| `/actuator/health` | public (details for admins only) | Aggregate, **includes the LDAP indicator**: `DOWN` when the directory is unreachable |
| `/actuator/info` | public | Application info |
| `/actuator/metrics`, `/actuator/loggers` | `ROLE_ADMIN` | JVM / HTTP metrics; runtime log levels |
| `/actuator/prometheus` | public, **management port only** | Prometheus metrics; see [Monitoring](#monitoring) |

`/livez` and `/readyz` are always on the application's port. With `MANAGEMENT_SERVER_PORT` set
(the Helm chart's default, `9090`), the `/actuator/**` endpoints move to that port, plain HTTP,
where health and Prometheus are open without signing in. Never publish it beyond your monitoring
network.

The directory is left out of readiness on purpose: everything is served from the cache, and
certificates sign in from it. To make a directory outage take CertAlert out of service, set
`MANAGEMENT_ENDPOINT_HEALTH_GROUP_READINESS_INCLUDE=readinessState,db,auditLog,ldap`.

!!! warning "Probe liveness/readiness, not the aggregate"
    The aggregate `/actuator/health` turns `DOWN` whenever the directory is unreachable. That is
    no reason to restart a process that is still serving cached data. Point orchestrator probes at
    the liveness and readiness endpoints.

    Both install methods already do this:

    - The release package's `docker-compose.yml` checks `/livez` on port 8080, over HTTP and
      then HTTPS, so it keeps working once TLS is on. `docker compose ps` shows the result. The
      image's own `HEALTHCHECK` calls `/livez` too.
    - The Helm chart sets liveness, readiness and startup probes on the liveness and readiness
      endpoints, on the metrics port when `metrics.enabled` (the default) and otherwise on the
      application's port. Tune them with `livenessProbe.*`, `readinessProbe.*` and
      `startupProbe.*` in your values. The startup probe allows 10 minutes, for first-start
      migrations.

## Logs

CertAlert logs to standard output (no file appender is configured). The application logger
`com.winllc.certalert` is at `INFO`. Change it with `logging.level.com.winllc.certalert` and a
restart, or at runtime through the standard Spring Boot `/actuator/loggers` endpoint
(`POST /actuator/loggers/com.winllc.certalert` with `{"configuredLevel":"DEBUG"}`), which requires
an administrator's session. From the next release, every level change made through `loggers` is
recorded in the audit trail as `logging.level-changed` (category `configuration`: who, which
logger, the old and the new level) before it takes effect; if the trail cannot be written, the
level is left as it was.

`LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` writes the log as one ECS JSON object per line, for a log
collector; the Helm chart does so by default (`logFormat: json`). The audit trail's own stdout
lines (logger `ens.audit`) are raw `ens-audit/1` JSON either way; filter on
`"schema":"ens-audit/1"` to separate them.

!!! note "Calling the API from scripts"
    Apart from the SIEM pull API's bearer tokens (`/api/audit/events`), CertAlert has no HTTP
    Basic or token authentication: callers sign in with a client certificate or through the
    `/login` form, and `POST`/`PUT`/`DELETE` requests carry Spring Security's CSRF token (the pages
    read it from the `_csrf` meta tags), so a script fetches a page in the same session first to
    obtain it. `GET` requests need only the session. See [API reference](api-reference.md#conventions).

Log lines worth knowing:

| Log message (abridged) | Meaning |
|---|---|
| `Skipping the scheduled <JOB> job: it is running, or has just run, on this replica or another` | Overlap guard: the job's lock is held, by a run that outlasted its interval or by another replica |
| `Scheduled <JOB> job failed` | A job threw; the scheduler stays alive for the next firing |
| `The expiry digest failed` | The round-up threw |
| `Expiry digest: more than N certificate(s) expire in the next D day(s); ...` | The read of certificates about to expire stopped at `digest.scan-limit`; the rest wait for a later run |
| `Expiry digest: more than N expired certificate(s) are still published by entries holding nothing current; ...` | The read of expired certificates stopped at `digest.scan-limit`; older ones are left out |
| `Expiry digest: round-ups that no longer apply were left in place, ...` | A run stopped at `digest.scan-limit`, so it did not clear earlier round-ups |
| `... has N 'serverPOC' value(s) that are not email addresses, ignoring them` | `require-email-poc` dropped values; fix at source or set it `false` |
| `Email alerts are enabled but no recipients are configured` | `cert-alert.alerts.email.to` is empty |
| `Rejecting client certificate ...: the directory publishes no such certificate` | X.509 sign-in failed the fingerprint match |
| `Refusing client certificate '...' published by '...': its authority revoked it` (or `it expired on ...`, `it is not valid until ...`) | X.509 sign-in refused a published certificate that is revoked or outside its validity dates |
| `[...] name N different directory entries; matching none of them` | With `require-known-certificate: false`, a certificate's `uid` and addresses name more than one person, so it signs in nobody |
| `Refused to add <address> to <dn> for <user>: it is named in cert-alert.security.admin-identifiers` | Someone tried to add an administrator's identifier as a person's address |
| `Email templates will be read from <dir> before the packaged ones` | Template override directory in use |
| `X.509 sign-in is on and ENS_X509_REVOCATION is off: ...` | Startup: certificates sign in without a live revocation check. DISA STIG requires one |
| `ENS_X509_REVOCATION_FAIL=open: ...` | Startup: a certificate whose revocation status cannot be found signs in anyway |
| `Refusing client certificate '...': revoked (...)`, `...: its revocation status is unknown: ...`, `...: its issuer '...' is not in ENS_X509_ALLOWED_ISSUERS` | A certificate refused at sign-in |
| `Could not send the <kind> email "..." to ... (attempt N); trying again at ...` | The mail server refused or did not answer; the message stays in the outbox |
| `Gave up on the <kind> email "..." to ... after N attempt(s) over 3d: ...` | A message reached `give-up-after` and will not be sent |
| `Email is queued but no mail server is configured (spring.mail.host); it waits until one is` | Email is enabled without `SPRING_MAIL_HOST` |
| `Removed N read notification(s)` | Notification retention ran |
| Alert summaries at `WARN` / `ERROR` | The logging alert channel (`ERROR` = critical severity) |

## Scheduled jobs

All jobs run inside the application process, in UTC, and are guarded against overlapping
themselves by a lock in the database (`shedlock`), which a run started by hand takes too, on this
replica or another. Directory jobs are recorded in the `sync_run` table and readable at
`GET /api/v1/sync/runs`.

| Job | Default cron | Config | Default state | What it does |
|---|---|---|---|---|
| **users** | `0 0 2 * * *` | `cert-alert.ldap.sync.users-cron` | on | Sweep IC Persons |
| **servers** | `0 0 4 * * *` | `cert-alert.ldap.sync.servers-cron` | on | Sweep IC Non-Person Entities |
| **refresh** | `0 15 * * * *` | `cert-alert.ldap.sync.refresh-cron` | on | Re-evaluate expiry status of cached certificates; no LDAP reads |
| **revocation** | `0 30 5 * * *` | `cert-alert.revocation.cron` | on | CRL/OCSP check of current certificates |
| **expiry round-up** | `0 0 7 * * *` | `cert-alert.notifications.digest.cron` | on | Per-person round-up (+ email if enabled), then notification retention |
| **certificate clean-up** | `0 0 3 * * SAT` | `cert-alert.ldap.certificate-cleanup.cron` | **off** | Delete expired/revoked certificates from the directory |
| **prune** | `0 0 6 * * SUN` | `cert-alert.ldap.prune.cron` | **off** | Delete cached entries unseen for `prune.after` |
| **audit retention** | `0 30 3 * * *` (daily) | `cert-alert.audit.retention.cron` | **off** | Prune audit events older than `ENS_AUDIT_RETENTION_DAYS`, or, while that is `0`, older than `cert-alert.audit.retention.after` with `retention.enabled`. Only events every output has delivered |
| **changelog connector** | continuous | `cert-alert.ldap.changelog.*` | **off** | Follow `cn=changelog` |
| **mail outbox** | every 30 s | `cert-alert.mail.outbox.*` | on | Send queued email and retry failures; see [When the mail server is down](#when-the-mail-server-is-down) |

!!! danger "`cert-alert.ldap.sync.enabled: false` stops everything"
    The single scheduler bean is conditional on `cert-alert.ldap.sync.enabled`. Setting it to
    `false` disables **all** scheduled jobs above (not just the sweeps), including revocation and
    the round-up. Use it only when you intend to drive jobs through the API.

Every directory job run has a status: `RUNNING`, `COMPLETED`, `COMPLETED_WITH_ERRORS` or
`FAILED`, with counts (entries seen/created, certificates cached/removed, alerts raised, entries
pruned, errors) and duration.

```bash
curl -s -b cookies.txt https://cert-alert/api/v1/sync/runs?limit=10 | jq '.[] | {job,status,startedAt,errors,message}'
```

### Running jobs on demand

Administrators can run any job immediately through the API (see
[API reference](api-reference.md#sync-and-jobs)) or, for the main ones, from the UI:

| Job | UI | API |
|---|---|---|
| Both sweeps | **Sync directory** button (People / Servers / Admin page headers) | `POST /api/v1/sync` |
| Users / servers sweep | | `POST /api/v1/sync/users`, `/sync/servers` |
| Refresh | | `POST /api/v1/sync/refresh` |
| Revocation | Admin → **Refresh revocation statuses** | `POST /api/v1/revocation/check` |
| Round-up | Notifications → **Run the expiry round-up** / **Dry run** | `POST /api/v1/notifications/digest[?dryRun=true]` |
| Prune | | `POST /api/v1/sync/prune` |
| Certificate clean-up | | `POST /api/v1/sync/certificate-cleanup` |
| Changelog poll | | `POST /api/v1/changelog/poll` |

!!! warning "Manual prune is not gated by `prune.enabled`"
    `POST /api/v1/sync/prune` calls the prune service directly; the `enabled` flag only governs
    the schedule. Check `GET /api/v1/sync/prune/preview` before calling it. Manual certificate
    clean-up, by contrast, does nothing unless `certificate-cleanup.enabled` is `true`.

## The changelog connector

When `cert-alert.ldap.changelog.enabled: true`:

- On the **first** start (no row in `changelog_cursor`) it runs a full sweep, then follows from
  the directory's latest change (`start-from: LATEST`). Later restarts resume from the stored
  position.
- It re-reads each changed entry rather than replaying the LDIF; applying is idempotent.
- Each poll takes a lock in the database, so with several replicas one polls at a time.
- If the directory has trimmed changes past the stored position, it triggers a full sweep
  (`full-sync-on-gap`) and counts it in `gapsDetected`.
- Status: `GET /api/v1/changelog` (position, lag, counters, last error). Pages show a header
  badge: **Live · change N**, **N changes behind**, or **Connector stopped**.
- The service account needs read access to `cn=changelog`, which is usually a separate grant from
  read access to the data tree.

## Backups

All state CertAlert owns is in the database, the license and the installation id included. The
directory remains the source of truth for people, servers and certificates, but the following
exist **only** in CertAlert's database:

| Table | Content |
|---|---|
| `server_contact` | Points of contact added in CertAlert |
| `user_email_alias` | Addresses added to people (personal and group) |
| `project`, `project_user`, `project_server`, `project_admin` | Projects, membership, project administrators |
| `notification`, `notification_setting` | Notifications and the round-up lead-days setting |
| `audit_event`, `audit_chain_head` | The audit trail (`ens-audit/1`, hash-chained) and the chain's newest sequence number and hash |
| `audit_delivery`, `audit_api_token` | How far each audit output (Splunk HEC, syslog) has delivered; the pull API's tokens |
| `outbound_email` | Email waiting to be sent, and what became of recent messages |
| `server_attribute_definition`, `server_attribute_option` | Editable-attribute definitions (the values themselves live in the directory) |
| `changelog_cursor` | Changelog position |
| `sync_run` | Job run log |
| `ens_license_installation`, `ens_license_file`, `ens_license_revision` | The installation id, the license and the license revisions seen |

Everything else (`directory_user`, `directory_server`, `cached_certificate`, identifiers, POCs)
can be rebuilt by a sweep.

!!! note "No scheduled backup is included"
    The release package and the Helm chart have no backup CronJob. Up to the current release the
    only automatic backup is the one `upgrade.sh` takes before an upgrade, and the commands below
    are scheduled with your own tools. From the next release the release package adds
    `backup.sh` and `restore.sh`, and the chart an optional pre-upgrade backup hook; see
    [Backup and restore scripts](#backup-and-restore-scripts-next-release).

**Keep the configuration with the dumps.** The database does not hold `.env`, the chart's values or
its Secrets, and in particular not the webhook secret key (`CERT_ALERT_WEBHOOKS_SECRET_KEY`,
`webhooks.secretKey`), without which the webhooks' stored secrets and URLs cannot be read. Back it
up as carefully as the dumps.

=== "Release package"

    The database runs in the `postgres` service (PostgreSQL 17). Run these in the release
    directory:

    ```bash
    # back up (custom format, compressed), while CertAlert runs
    docker compose exec -T postgres pg_dump -U certalert -Fc certalert > cert-alert-$(date +%F).dump
    ```

    A nightly crontab line, for example:

    ```cron
    15 1 * * * cd /opt/cert-alert-<version> && docker compose exec -T postgres pg_dump -U certalert -Fc certalert > /backup/cert-alert/cert-alert-$(date +\%F).dump
    ```

    Use the user and database names from `CERT_ALERT_DB_USER` and `CERT_ALERT_DB_NAME` if you
    changed them. `pg_dump` takes a consistent snapshot without stopping the application.

    **The upgrade backup.** `upgrade.sh` (and `install.sh --upgrade`) writes
    `backups/cert-alert-before-<version>-<time>.sql.gz` (mode 600) before it upgrades, unless
    you pass `--no-backup`. It is a plain SQL dump, restored with `psql` (below).

    **The license volume** (`cert-alert_ens-license`, mounted at `/var/lib/ens/certalert`) is read
    only on the first start of the release that moved the license and the installation id into
    the database, which imports them from it; after that the database dump holds them. Keep the
    `.lic` file you received. Also back up the configuration: `.env`, `license/`, `tls/` and
    `email-templates/`.

=== "Helm"

    The database is your own PostgreSQL. Back it up with your usual tools, for example:

    ```bash
    pg_dump -h postgres.example.gov -U certalert -Fc certalert > cert-alert-$(date +%F).dump
    ```

    Also keep your values file and the Secrets it names. The license and the installation id are
    in the database; the license PVC is only the import source for the first start of the release
    that moved them there, see [Install with Helm](install.md#4-license).

    The chart's evaluation database (`database.embedded.enabled`) is not backed up. Do not keep
    data you need in it.

### Backup and restore scripts (next release)

=== "Release package"

    The release package's directory gains two scripts:

    ```bash
    ./backup.sh                              # backups/cert-alert-<UTC time>.sql.gz
    ./backup.sh --keep 14                    # ... and keep the newest 14 of these
    ./backup.sh --out /srv/backups/ca.sql.gz # somewhere else
    ./restore.sh backups/<file>.sql.gz       # asks first; --yes not to
    ```

    `backup.sh` takes a consistent `pg_dump` (one transaction) while CertAlert keeps running,
    gzipped and readable by its owner only. Run it from cron or a systemd timer, for example
    `15 2 * * * cd /opt/cert-alert-<version> && ./backup.sh --keep 14`. `upgrade.sh` takes its
    pre-upgrade backup (`backups/cert-alert-before-<version>-*.sql.gz`) through `backup.sh`.

    `restore.sh FILE` asks first (or `--yes`), stops CertAlert, empties the schema and loads the
    dump in one transaction (a dump that does not load leaves the database as it was), then starts
    CertAlert again, which migrates an older dump forward. To go back to an earlier release,
    restore the dump its upgrade took from the earlier release's directory with that release's own
    `restore.sh`, then run `./install.sh` there.

=== "Helm"

    Back up an external PostgreSQL with your own tooling. `upgradeBackup.enabled=true` adds a
    `pre-upgrade` hook Job that `pg_dump`s the database onto a volume before `helm upgrade` changes
    anything, and stops the upgrade if the dump fails. It is off by default.

    | Value | Default | Description |
    |---|---|---|
    | `upgradeBackup.enabled` | `false` | The hook. For the embedded database, or an external one given `database.password` or `database.existingSecret` |
    | `upgradeBackup.persistence.existingClaim` | `""` | A PVC of your own; otherwise `<release>-cert-alert-upgrade-backups` is created (`size` `8Gi`) and kept on uninstall |
    | `upgradeBackup.keep` | `5` | Dumps kept, newest first |
    | `upgradeBackup.sslMode` | `prefer` | `PGSSLMODE` for an external database (`disable` for the embedded one) |
    | `upgradeBackup.activeDeadlineSeconds` | `1800` | The most the dump may take; `helm upgrade --timeout` must allow it too |
    | `upgradeBackup.image` | the embedded database's | `pg_dump` must be at least the server's major version (PostgreSQL 17 by default) |

    - **Turning it on.** The upgrade that turns it on creates the volume, and the first dump is
      taken at the next upgrade. With `existingClaim` it is taken from the first upgrade on. Under
      Argo CD the hook runs as a PreSync hook at every sync and needs `existingClaim`; turn it on
      after the first sync, when the database exists.
    - **Why it is off by default.** On an existing release it would need a volume that can bind
      (a default storage class), a dump that fits in `helm upgrade --timeout` (5 minutes by
      default), a `pg_dump` as new as an external server and, with `networkPolicy` on, an external
      database that admits the Job's pod. Any of these could stop an upgrade that works today.
    - **A failed dump stops the upgrade** before anything is applied. The Job is kept for its log
      (`kubectl logs job/<release>-cert-alert-upgrade-backup`); `helm upgrade --no-hooks` skips it.
    - **Restoring one.** Scale CertAlert to zero, load the dump into an emptied schema in one
      transaction from a pod that mounts the volume, and `helm rollback` to the revision it was
      taken before. The chart's `README.md` ("Backups and upgrades") has the commands. The webhook
      secret key must be the one the dump was taken with.

    Your own database backups remain what to rely on; the hook is the undo for one upgrade.

Test a restore before you need one: into a second installation, or with `./restore.sh` on a copy
of the host.

### Restore

Stop CertAlert first, so nothing writes while the database is replaced, and restore into the same
release that made the backup (or an older backup into a newer release, which migrates it on start;
never the other way round).

=== "Release package"

    ```bash
    docker compose stop cert-alert

    # a custom-format dump (pg_dump -Fc)
    docker compose exec -T postgres pg_restore -U certalert -d certalert --clean --if-exists < cert-alert-YYYY-MM-DD.dump

    # or a plain SQL dump, such as upgrade.sh's: into an empty database
    docker compose exec -T postgres dropdb -U certalert certalert
    docker compose exec -T postgres createdb -U certalert certalert
    gunzip -c backups/cert-alert-before-<version>-<time>.sql.gz | docker compose exec -T postgres psql -U certalert -d certalert

    docker compose up -d
    ```

=== "Helm"

    ```bash
    kubectl -n cert-alert scale deploy/cert-alert --replicas=0
    pg_restore -h postgres.example.gov -U certalert -d certalert --clean --if-exists cert-alert-YYYY-MM-DD.dump
    kubectl -n cert-alert scale deploy/cert-alert --replicas=1
    ```

After a restore, run **Sync directory** to bring the cached directory data up to date, and read
[Disaster recovery](availability-and-sizing.md#disaster-recovery) about the audit trail.

## When the mail server is down

Nothing is sent from inside a database transaction. A round-up's messages and the alert
channel's are written to the `outbound_email` table with the work that produced them, and sent
once that has committed. When the mail server is down:

- a message stays queued and is tried again a minute later, then two, four and so on up to an
  hour apart, each failure logged as a warning with the mail server's reason;
- a newer round-up to the same address replaces one still waiting;
- after three days a message is given up on, with an error in the log;
- the Notifications page says the round-up's messages "could not be sent and are queued to try
  again", with the reason, rather than reporting them as sent;
- a notification is marked emailed only when its message actually goes.

Watch `ens_certalert_mail_outbox{state="pending"}` and `{state="failed"}`. The settings are in
[Configuration → Mail outbox](configuration.md#mail-outbox-cert-alertmailoutbox).

## Monitoring

### Prometheus metrics

With `MANAGEMENT_SERVER_PORT` set (Helm: `metrics.enabled`, the default), Prometheus scrapes
`/actuator/prometheus` on that port. With Helm, `metrics.serviceMonitor.enabled` renders a
ServiceMonitor, and `metrics.allowFrom` admits your Prometheus through the NetworkPolicy. Without
a management port, Prometheus is not served at all. Besides Spring Boot's JVM, HTTP, database pool
and Tomcat metrics:

| Metric | Type | Meaning |
|---|---|---|
| `ens_license_mode{mode}` | gauge | 1 for the current mode (`FULL`, `READ_ONLY`, `UNLICENSED`), 0 for the others |
| `ens_license_days_remaining` | gauge | Days until the license expires or its grace ends; NaN for a perpetual license |
| `ens_audit_events_total{category,outcome}` | counter | Events written to the audit trail |
| `ens_audit_forward_failures_total{output}` | counter | Failed attempts to forward them (`splunk`, `syslog`) |
| `ens_auth_attempts_total{method,outcome}` | counter | Sign-ins and refusals: `x509` (once per session) or `ldap`; `success` or `failure` |
| `ens_certalert_certificates{status}` | gauge | Cached certificates by state: `none`, `valid`, `expiring_soon`, `expired` |
| `ens_certalert_job_last_success_seconds{job}` | gauge | When each job last finished without failing (Unix time), since startup |
| `ens_certalert_digest_truncated_total` | counter | Round-ups that stopped at `digest.scan-limit` |
| `ens_certalert_revocation_unknown` | gauge | Cached certificates whose authority gave no revocation answer |
| `ens_certalert_mail_outbox{state}` | gauge | Email `pending` in the outbox, and `failed` (given up on) |

The counts are read from the database at most once a minute.

### Other checks

- **Liveness/readiness** probes as above; **aggregate health** for directory reachability.
- **Job runs**: alert if the most recent `USERS`/`SERVERS` run in `GET /api/v1/sync/runs` is not
  `COMPLETED` or is older than ~25 hours.
- **Changelog**: alert on growing `lag`, non-null `lastError`, or `running: false`
  (`GET /api/v1/changelog`).
- **Revocation**: `GET /api/v1/revocation` returns counts; a large *Not checked* or *Unknown*
  count means the check is not reaching the authorities.
- **Directory health and certificate posture**: `GET /api/v1/metrics` (what the Metrics page shows).
- **Operator alerts**: enable `cert-alert.alerts.email` with a team list, or scrape the log for
  alert lines.
- **JVM**: `/actuator/metrics` (admin only), or Prometheus.

## Upgrades

1. Back up the database.
2. Install the new release:

    === "Release package"

        Unpack the new release next to the old one, then run
        `./upgrade.sh --from ../cert-alert-<old>`. It backs up the database to `backups/`, copies
        your settings across and restarts on the new images. See
        [Upgrade with upgrade.sh](install.md#6-upgrade-with-upgradesh).

    === "Helm"

        ```bash
        helm upgrade cert-alert oci://ghcr.io/winllc/charts/cert-alert --version X.Y.Z \
          --namespace cert-alert -f cert-alert-values.yaml
        ```

        The chart's version is the application's version. See [Upgrade](install.md#6-upgrade).

3. On start, Flyway applies any new migrations; Hibernate `validate` then checks the mapping. A
   failure here stops startup; restore and investigate rather than editing the schema.
4. Some features read certificate details at **parse time**, so rows cached by an older version
   lack them until the next sweep re-reads them. For example, key usage (*Use not known* on a
   person's credentials), revocation endpoints, and risky-name flags (the migration backfills
   these from stored names). Run **Sync directory** after an upgrade to refresh them.

Migrations shipped: `V1` (directory tables) through `V28`, and `V29` from the next release. The
later ones:

| Migration | What it does |
|---|---|
| `V17__create_user_additional_email` | People's additional addresses read from the directory (`directory_user_email`) |
| `V18__ens_audit_format` | Moves the audit trail to the shared `ens-audit/1` format in the same `audit_event` table, and adds `audit_chain_head`, `audit_delivery` and `audit_api_token`. Rows written before it are numbered and chained by the application at its first start, oldest first, before it writes anything new; on a large audit table that first start takes longer |
| `V19__create_outbound_email` | The email outbox: a message is stored in the same transaction as whatever decided to send it, and sent after that commits, retried until it goes or is old enough to give up on |
| `V20__check_attribute_type_without_an_in_list` | Recreates the check on `server_attribute_definition.type` in a form the embedded H2 database keeps enforcing. No data changes; on PostgreSQL it changes nothing in effect |
| `V21__create_shedlock` | `shedlock`: the database lock every scheduled job, outbox and manual run takes, so one replica runs each |
| `V22__create_job_run` | `job_run`: jobs started by hand, so any replica can answer `GET /api/v1/jobs/{id}` |
| `V23__create_spring_session` | `spring_session`, `spring_session_attributes`: sessions in the database |
| `V24__create_saml_authn_request` | `saml_authn_request`: SAML sign-in requests waiting for their answer (ten minutes, used once) |
| `V25__ens_license_state` | `ens_license_*`: the license, installation id and revisions, imported from the license volume at the first start |
| `V26__create_api_token_and_webhook` | `api_token`, `webhook_endpoint`, `webhook_delivery`: [API tokens](api-reference.md#api-tokens) and [webhooks](webhooks.md) |
| `V27__create_saved_view_and_scheduled_report` | `saved_view`, `scheduled_report`, and three attachment columns on `outbound_email`: [saved views and scheduled reports](exports-and-reports.md) |
| `V28__webhook_url_encrypted_and_report_owner_dn` | A wider, encryptable webhook URL and a scheduled report owner's DN |
| `V29__webhook_private_networks_allowed` (next release) | `webhook_endpoint.private_networks_allowed`, set for every webhook that exists at the upgrade, so it keeps a private address until its URL changes (see [Webhooks → Private networks](webhooks.md#private-networks)) |

Migrations are written **expand-only**: tables, nullable or defaulted columns and indexes are
added in a release, and anything dropped, renamed or narrowed goes at least one release after the
code stops using it. So with several replicas, the old pods keep serving on the migrated schema
during a rolling update. `V21` to `V29` are all expand-only. `helm rollback` and starting an older
release still do not undo a migration: the way back is the backup taken before the upgrade.

### Upgrading to the platform-baseline release

| Change | What to do |
|---|---|
| Revocation at sign-in (`ENS_X509_REVOCATION`) stays `off` | Turn it on; startup logs a warning while certificate sign-in is on without it |
| Address ranges match within their own family | IPv4 ranges in `ENS_X509_TRUSTED_PROXIES` and `CERT_ALERT_REVOCATION_ALLOWED_HOSTS` no longer match IPv6 addresses. If a header-mode proxy connects over IPv6, add its IPv6 range, for example `fd00::/8` |
| Helm: `metrics.enabled` is `true` and `logFormat` is `json` by default | The probes move to port `9090`, plain HTTP; logs become ECS JSON. Set `metrics.enabled: false` or `logFormat: text` to keep the old behaviour |
| The release package warns about plain HTTP on every interface | Set `CERT_ALERT_BIND_ADDRESS=127.0.0.1` behind a proxy, or turn TLS on |
| Email goes through the outbox | Mail that cannot be sent is retried for three days instead of being lost |
| The audit retention job runs daily | It ran on Sundays before the `ens-audit/1` trail (`V18`); it still does nothing unless retention is configured |

Migrations are not undone. Going back to an older release means restoring the backup taken before
the upgrade; see [Rolling back an upgrade](availability-and-sizing.md#rolling-back-an-upgrade).

### Upgrading to the next release

These changes come with the next release. Nothing stops working on the upgrade; the ones marked
*new installs* change the default only for an installation made with the next release.

| Change | What to do |
|---|---|
| Release package: plain HTTP to other hosts is refused on *new installs* | A new `.env` has `CERT_ALERT_ALLOW_PLAIN_HTTP=false`, and `install.sh` will not start CertAlert with TLS off on an address other than `127.0.0.1` until `.env` turns TLS on, sets `CERT_ALERT_BIND_ADDRESS=127.0.0.1` behind your proxy, or sets `CERT_ALERT_ALLOW_PLAIN_HTTP=true` (warned about at every run). An upgraded `.env` that does not mention the setting is only warned about: add `CERT_ALERT_ALLOW_PLAIN_HTTP=true` to say so, or move to TLS. An `.env` that sets `false` itself is held to it. See [Choose where it listens](install.md#4-choose-where-it-listens) |
| Webhooks: private addresses refused for new webhooks | `block-private-networks` is unset by default: existing webhooks keep their address (migration `V29`, named in a startup warning) until their URL changes. List internal receivers in `CERT_ALERT_WEBHOOKS_ALLOWED_HOSTS`, which may now name private hosts and ranges; `false` keeps the old rule for every webhook. See [Webhooks → Private networks](webhooks.md#private-networks) |
| Webhook secret key generated on *new installs* | An upgrade without `CERT_ALERT_WEBHOOKS_SECRET_KEY` (Helm `webhooks.secretKey`) keeps storing secrets unencrypted, with the warning. Set one (`openssl rand -base64 32`) to encrypt them at the next start, and back it up |
| OIDC: Entra ID group overage refused | A sign-in whose token names `_claim_names.groups` instead of carrying the groups is refused (`/login?sso_error=groups`, a failed `auth.login`). Send only the groups assigned to the application; see [Single sign-on](single-sign-on.md#microsoft-entra-id-oidc) |
| OIDC: signing out at the provider | `CERT_ALERT_SSO_OIDC_PROVIDER_LOGOUT` (Helm `sso.oidc.providerLogout`) is off on upgrade, with a startup warning while OIDC is on, and on for *new installs*. Register `<public-url>/login?logout` as a post-logout redirect URI before turning it on |
| Webhooks: optional egress proxy | New and unset: webhooks keep connecting directly. `CERT_ALERT_WEBHOOKS_PROXY_HOST` and `_PORT` (Helm `webhooks.proxy.host` and `.port`) send them through an HTTP forward proxy with `CONNECT` to the address CertAlert resolved and checked, so the host still needs DNS for the receivers. A proxy setting that cannot work stops the start. See [Webhooks → Through an egress proxy](webhooks.md#through-an-egress-proxy) |
| SAML single logout | `CERT_ALERT_SSO_SAML_SINGLE_LOGOUT` (Helm `sso.saml.singleLogout`) is off on upgrade, with a startup warning while SAML is on, and on for *new installs*. It needs the service provider's signing key and an identity provider with a `SingleLogoutService`; give the provider the service provider metadata again so it learns `<public-url>/logout/saml2/slo`. See [SAML single logout](single-sign-on.md#saml-single-logout-next-release) |
| Auditors may export the inventory | `POST /api/v1/certificates/export` is open to the auditor role, audited as `inventory.exported` |
| `cert-alert.security.enabled=false` needs the `dev` profile | The application refuses to start with it otherwise. It was never meant for anything but local development |
| Log-level changes are audited | Changing a level through `/actuator/loggers` records `logging.level-changed` (category `configuration`) |
| Sign-in log lines stay on one line | Certificate subjects and issuers, identity-provider usernames and sign-in identifiers are logged with line breaks as spaces and other control characters as `?`. A log search for an exact subject containing such characters no longer matches it verbatim |
| Backups | The release package adds `backup.sh` and `restore.sh`; the chart adds `upgradeBackup` (off). See [Backup and restore scripts](#backup-and-restore-scripts-next-release) |
| Migration `V29` | Adds `webhook_endpoint.private_networks_allowed` on the first start (expand-only) |

### Upgrading to the release with single sign-on, the public API and exports

Nothing to do by hand: the migrations above run on the first start, and every new feature is off or
inert until someone uses it.

| Change | What to do |
|---|---|
| [Single sign-on](single-sign-on.md) and the auditor role are new, and off | Nothing changes until `cert-alert.sso.*`, `auditor-identifiers` or `security.groups` is set. Single sign-on needs `CERT_ALERT_SSO_PUBLIC_URL`; SAML needs HTTPS |
| A **Certificates** page, exports, saved views and [scheduled reports](exports-and-reports.md) | Exports are audited (`inventory.exported`). Scheduled reports cannot be created until `CERT_ALERT_EXPORTS_REPORT_RECIPIENT_DOMAINS` (Helm `exports.reportRecipientDomains`) names your domains |
| [API tokens](api-reference.md#api-tokens), the OpenAPI document and [webhooks](webhooks.md) | Nothing changes until an administrator creates a token or a webhook on **Admin > Integrations**. `/api/v1/**` now also accepts `Authorization: Bearer cat_…`; a proxy or WAF that strips `Authorization` must let it through. `/api/v1/openapi.yaml` is served without signing in |
| Tokens are checked against the creator's live entry | The service account must read people's lock attributes and the lockout policy (see [API tokens](api-reference.md#api-tokens)). On OpenLDAP with `olcPPolicyDefault`, set `cert-alert.ldap.lockout.assumed-duration` or a locked-out creator's tokens stay refused until an administrator clears the lock |
| Webhooks post from CertAlert to the receivers | Set `CERT_ALERT_WEBHOOKS_SECRET_KEY` (Helm `webhooks.secretKey`) before creating any, the same on every replica. With the chart's NetworkPolicy on, `networkPolicy.egress.web` must reach the receivers; an HTTP proxy is not used |
| New audit actions | `api.*`, `webhook.*`, `report.*` and `inventory.exported` |
| Page markup changed for [accessibility](accessibility.md) | Custom CSS that targeted `h2.page-title` or `h3.card-title` needs the new elements |

## Scaling

- **Replicas.** Each scheduled job, the same job started by hand, and each changelog poll takes
  a lock in the database (`shedlock`), so it runs on one replica at a time, and sessions are kept
  in the database. The Helm chart can run more than one replica (`replicaCount`, default 1:
  `Recreate` with one, `RollingUpdate` and a PodDisruptionBudget above one, once the license is
  imported). The release package runs one container. See
  [High availability](availability-and-sizing.md#high-availability).
- **Directory size**: sweeps are paged (`page-size`), streamed, and written in batches
  (`batch-size`), so memory does not grow with the directory. They have not been load-tested at
  100,000 certificates; see [Sizing](availability-and-sizing.md#sizing) for starting figures. Raise
  `search-timeout` if a single page search is slow.
- **Memory**: the heap follows the container limit (`MaxRAMPercentage=75`).
- **Database pool**: `CERT_ALERT_DB_POOL_SIZE` (default 10).
- **Revocation throughput**: `cert-alert.revocation.workers` (default 8) concurrent OCSP requests.

## Routine checklist

| Frequency | Task |
|---|---|
| Daily | Check last sweep runs succeeded; glance at the Notifications round-up status |
| Weekly | Review the audit log (`/admin/audit`) for unexpected activity, and **Verify integrity**; check revocation *Unknown* counts and the mail outbox |
| Monthly | Review `serverPOC` warnings in logs; confirm the round-up lead days are still right |
| Per release | Back up, run `upgrade.sh` or `helm upgrade`, run Sync directory |
