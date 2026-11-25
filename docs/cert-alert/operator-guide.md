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
an administrator's session. From 0.0.3, every level change made through `loggers` is
recorded in the audit trail as `logging.level-changed` (category `configuration`: who, which
logger, the old and the new level) before it takes effect; if the trail cannot be written, the
level is left as it was.

`LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` writes the log as one ECS JSON object per line, for a log
collector; the Helm chart does so by default (`logFormat: json`). The audit trail's own stdout
lines (logger `ens.audit`) are raw `ens-audit/1` JSON either way; filter on
`"schema":"ens-audit/1"` to separate them.

!!! note "Calling the API from scripts"
    A script calls `/api/v1/**` with an [API token](api-reference.md#api-tokens)
    (`Authorization: Bearer cat_…`), which needs no session and no CSRF token; the SIEM pull API
    (`/api/audit/events`) has bearer tokens of its own. There is no HTTP Basic. A script that
    uses a browser session instead (a client certificate or the `/login` form, as the `curl -b`
    examples on this page do) sends Spring Security's CSRF token with every `POST`, `PUT` and
    `DELETE`; the pages carry it in the `_csrf` meta tags, so the script fetches a page in the
    same session first. See [API reference](api-reference.md#conventions).

Log lines worth knowing:

| Log message (abridged) | Meaning |
|---|---|
| `Skipping the scheduled <JOB> job: it is running, or has just run, on this replica or another` | Overlap guard: the job's lock is held, by a run that outlasted its interval or by another replica |
| `Scheduled <JOB> job failed` | A job threw; the scheduler stays alive for the next firing |
| `<JOB> sync failed`, with `SizeLimitExceededException` | From 0.0.4: the directory cut the search short at its size limit, so the sweep failed rather than ending early. See [Troubleshooting](troubleshooting.md#syncing) |
| `Failed to write a batch of N <JOB> entries; writing them one at a time: ...` | From 0.0.4: a batch could not be written, and is written again an entry at a time |
| `Failed to write <JOB> entry '<dn>'` | That entry could not be written and was skipped; it counts as one error on the run. Its neighbours were written |
| `Prune: left N unseen <kind> entr(ies) of M in place: ...` | From 0.0.4: the prune did not delete that kind. The line says whether no sweep has completed without errors since they were last seen, or they are more than `prune.max-share` |
| `The expiry digest failed` | The round-up threw |
| `Expiry digest: more than N certificate(s) expire in the next D day(s); ...` | The read of certificates about to expire stopped at `digest.scan-limit`; the rest wait for a later run |
| `Expiry digest: more than N expired certificate(s) are still published by entries holding nothing current; ...` | The read of expired certificates stopped at `digest.scan-limit`; older ones are left out |
| `Expiry digest: round-ups that no longer apply were left in place, ...` | A run stopped at `digest.scan-limit`, so it did not clear earlier round-ups |
| `Expiry digest: stopped at the N message cap for one run; M message(s) were not sent this run. ...` | From 0.0.4, on every run where `email.max-per-run` binds. The soonest expiries were mailed; the longest-expired wait |
| `... has N 'serverPOC' value(s) that are not email addresses, ignoring them` | `require-email-poc` dropped values; fix at source or set it `false` |
| `Email alerts are enabled but no recipients are configured` | `cert-alert.alerts.email.to` is empty |
| `Rejecting client certificate ...: the directory publishes no such certificate` | X.509 sign-in failed the fingerprint match |
| `Refusing client certificate '...' published by '...': its authority revoked it` (or `it expired on ...`, `it is not valid until ...`) | X.509 sign-in refused a published certificate that is revoked or outside its validity dates |
| `Refusing client certificate '...': N different directory entries publish it, so it cannot say which of them is signing in` | From 0.0.4: more than one person publishes the certificate presented, so it signs in nobody (`certificate published by more than one person`) |
| `[...] name N different directory entries; matching none of them` | With `require-known-certificate: false`, a certificate's `uid` and addresses name more than one person, so it signs in nobody |
| `Refused to add <address> to <dn> for <user>: it is named in cert-alert.security.admin-identifiers` | Someone tried to add an administrator's identifier as a person's address |
| `Email templates will be read from <dir> before the packaged ones` | Template override directory in use |
| `X.509 sign-in is on and ENS_X509_REVOCATION is off: ...` | Startup: certificates sign in without a live revocation check. DISA STIG requires one |
| `ENS_X509_REVOCATION_FAIL=open: ...` | Startup: a certificate whose revocation status cannot be found signs in anyway |
| `Refusing client certificate '...': revoked (...)`, `...: its revocation status is unknown: ...`, `...: its issuer '...' is not in ENS_X509_ALLOWED_ISSUERS` | A certificate refused at sign-in |
| `Allowing client certificate '...' although no OCSP responder or CRL answered for it (soft-fail; ...)` | Soft-fail let a certificate in without a revocation answer. From 0.0.4 it is logged once per answer, not on every request |
| `ENS_X509_TRUSTED_PROXIES entry '...' is not an address or CIDR` | Startup, with `SERVER_FORWARD_HEADERS_STRATEGY=framework`: that entry is ignored when the client's address is worked out |
| `Could not read the SAML identity provider's metadata from ... again; keeping the copy read at ...` | From 0.0.4: a scheduled re-read of the metadata failed. The last copy that worked stays in use, and the read is tried again a minute later |
| `The clock reads ..., before ..., the latest time this installation has seen: licenses are checked as of ...` | From 0.0.4: the host's clock is more than a day behind a time CertAlert has already recorded. Correct the clock |
| `Could not send the <kind> email "..." to ... (attempt N); trying again at ...` | The mail server refused or did not answer; the message stays in the outbox |
| `Gave up on the <kind> email "..." to ... after N attempt(s) over 3d: ...` | A message reached `give-up-after` and will not be sent |
| `Email is queued but no mail server is configured (spring.mail.host); it waits until one is` | Email is enabled without `SPRING_MAIL_HOST` |
| `Removed N read notification(s)` | Notification retention ran |
| Alert summaries at `WARN` / `ERROR` | The logging alert channel (`ERROR` = critical severity) |

## Scheduled jobs

All jobs run inside the application process, in UTC, and are guarded against overlapping
themselves by a lock in the database (`shedlock`), which a run started by hand takes too, on this
replica or another. Directory jobs are recorded in the `sync_run` table and readable at
`GET /api/v1/sync/runs` by administrators and auditors (from 0.0.4; any signed-in user before).

From 0.0.4 the jobs below, the webhook outbox, the audit flush and the session sweep share eight
scheduler threads on each replica (`CERT_ALERT_SCHEDULER_THREADS`, Helm `schedulerThreads`), so a
long sweep no longer holds up the mail outbox, the hourly refresh or the others. Earlier releases
ran them all on one thread. See [Configuration → Scheduler threads](configuration.md#scheduler-threads).

| Job | Default cron | Config | Default state | What it does |
|---|---|---|---|---|
| **users** | `0 0 2 * * *` | `cert-alert.ldap.sync.users-cron` | on | Sweep IC Persons |
| **servers** | `0 0 4 * * *` | `cert-alert.ldap.sync.servers-cron` | on | Sweep IC Non-Person Entities |
| **refresh** | `0 15 * * * *` | `cert-alert.ldap.sync.refresh-cron` | on | Re-evaluate expiry status of cached certificates; no LDAP reads. From 0.0.4 it also escalates: a certificate alerted on as a `WARNING` is alerted on once more, as `CRITICAL`, when it comes inside `critical-threshold-days` |
| **revocation** | `0 30 5 * * *` | `cert-alert.revocation.cron` | on | CRL/OCSP check of current certificates |
| **expiry round-up** | `0 0 7 * * *` | `cert-alert.notifications.digest.cron` | on | Per-person round-up (+ email if enabled), then notification retention |
| **certificate clean-up** | `0 0 3 * * SAT` | `cert-alert.ldap.certificate-cleanup.cron` | **off** | Delete expired/revoked certificates from the directory |
| **prune** | `0 0 6 * * SUN` | `cert-alert.ldap.prune.cron` | **off** | Delete cached entries unseen for `prune.after`, once a clean sweep has missed them and never more than `prune.max-share` of a kind; see [What a prune will not delete](#what-a-prune-will-not-delete) |
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

What the statuses mean for a sweep, from 0.0.4:

- **`errors` counts entries.** A batch that cannot be written is written again one entry at a
  time, so only the entry that cannot be stored is skipped, and it is named in the log
  (`Failed to write <JOB> entry '<dn>'`). Earlier releases dropped the whole batch of 200 and
  counted batches. Values longer than their database column are cut to fit (an address longer
  than an address column is dropped), and a DN that appears twice in a batch is written once, so
  an over-long title or description no longer costs an entry.
- **`FAILED` with `SizeLimitExceededException`** means the directory cut the search short at its
  size limit. Some servers count that limit across all the pages of a search (389 Directory Server
  has `nsslapd-pagedsizelimit` and OpenLDAP `size.prtotal` for this; check your server's
  documentation). Earlier releases
  ended such a sweep as a success after the first few thousand entries. Raise the service
  account's size limit in the directory. A `count-limit` set above `0` still ends quietly at that
  limit.
- Only a sweep that ends `COMPLETED` lets the prune delete what it did not see.

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

### What a prune will not delete

From 0.0.4 a prune, scheduled or started by hand, looks at people and servers separately and
deletes the entries of a kind that have gone unseen for `prune.after` only when:

- a sweep of that kind has finished `COMPLETED`, with no errors, since the newest of them was last
  seen. A month of failed or short sweeps (an expired bind password, a size limit, a lapsed
  license) therefore deletes nothing;
- they are at most `prune.max-share` (10%) of that kind.

Otherwise that kind is left in place, the log says why
(`Prune: left N unseen user entr(ies) of M in place: ...`), and the run is recorded as
`COMPLETED_WITH_ERRORS` with one error for each kind left. `GET /api/v1/sync/prune/preview` still
counts every unseen entry, whether or not a prune would delete it now. To prune after a genuine
large removal from the directory, run the sweeps until they complete without errors, and raise
`cert-alert.ldap.prune.max-share` for that one run if more than a tenth of a kind has gone. A
pruned entry takes its added contacts, addresses and project memberships with it, which no sweep
brings back.

### What certificate clean-up waits for

- `revoked-after` (7 days) is counted from the check that first found the certificate revoked.
  Before 0.0.4 it was counted from the latest nightly check, so a revoked certificate that was
  still current was never old enough to remove. The upgrade starts the count for certificates
  already revoked at their last check.
- A certificate a run could not remove (its entry is gone from the directory, or the directory
  refused the modify) goes behind everything not yet tried in the next run, so such certificates
  cannot use up `max-per-run` every week.

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
    The release package and the Helm chart have no backup CronJob. Before 0.0.3 the only
    automatic backup is the one `upgrade.sh` takes before an upgrade, and the commands below
    are scheduled with your own tools. From 0.0.3 the release package adds
    `backup.sh` and `restore.sh`, and the chart an optional pre-upgrade backup hook; see
    [Backup and restore scripts](#backup-and-restore-scripts).

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

### Backup and restore scripts

From 0.0.3.

=== "Release package"

    The release package's directory has two scripts:

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
- a round-up, like a background pass of the outbox, stops after three failures in a row and
  leaves the rest queued for the next pass (from 0.0.4; before, a round-up waited out every
  message's timeout while holding its lock);
- a newer round-up to the same address replaces one still waiting;
- after three days a message is given up on, with an error in the log;
- the Notifications page says the round-up's messages "could not be sent and are queued to try
  again", with the reason, rather than reporting them as sent;
- a notification is marked emailed only when its message actually goes.

Watch `ens_certalert_mail_outbox{state="pending"}` and `{state="failed"}`. The settings are in
[Configuration → Mail outbox](configuration.md#mail-outbox-cert-alertmailoutbox).

### The round-up's message cap

A round-up sends at most `cert-alert.notifications.email.max-per-run` (500) messages, one per
address. From 0.0.4, where that binds:

- messages go out most urgent first: the soonest to expire, then what has expired, most recently
  first. Earlier releases mailed the longest-expired first, so above the cap the same people were
  mailed every day and nobody with an upcoming expiry was;
- the Notifications page says so after **Run the expiry round-up** (*Stopped at the 500 message cap
  for one run …; N message(s) were not sent this run*), the result carries the number as
  `notMailed`, and a `WARN` is logged on every run the cap binds;
- everybody is still told on the Notifications page.

Raise `max-per-run`, or remove long-expired certificates that nobody needs from the directory.

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
  `COMPLETED` or is older than ~25 hours. The call needs an administrator or auditor, or an API
  token with `jobs:read`. A `PRUNE` run that is `COMPLETED_WITH_ERRORS` left a kind of entry in
  place; see [What a prune will not delete](#what-a-prune-will-not-delete).
- **Changelog**: alert on growing `lag`, non-null `lastError`, or `running: false`
  (`GET /api/v1/changelog`).
- **Revocation**: `GET /api/v1/revocation` returns counts; a large *Not checked* or *Unknown*
  count means the check is not reaching the authorities or, from 0.0.4, that an authority is
  serving a CRL past its `nextUpdate` or the configured `default-crl-url` belongs to another CA.
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

Migrations shipped: `V1` (directory tables) through `V28`; `V29` from 0.0.3; and `V30` to `V32`,
`V37` and `V39` from 0.0.4 (the numbers between are not used). The later ones:

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
| `V29__webhook_private_networks_allowed` (0.0.3) | `webhook_endpoint.private_networks_allowed`, set for every webhook that exists at the upgrade, so it keeps a private address until its URL changes (see [Webhooks → Private networks](webhooks.md#private-networks)) |
| `V30__cache_certificate_revocation_found_at` (0.0.4) | `cached_certificate.revocation_found_at`: when a certificate was first found revoked, which the clean-up's `revoked-after` is counted from. Certificates already revoked take their last check |
| `V31__cache_certificate_last_alert_severity` (0.0.4) | `cached_certificate.last_alert_severity`, for the escalation to `CRITICAL`. Certificates expiring more than 7 days ahead are taken to have had their `WARNING`, so they escalate in their turn; those already inside 7 days are left alone, so the upgrade sends no burst of alerts |
| `V32__cache_certificate_cleanup_attempted_at` (0.0.4) | `cached_certificate.cleanup_attempted_at`: when the clean-up last tried and could not remove a certificate |
| `V37__audit_pruned_through` (0.0.4) | `audit_chain_head.pruned_through_seq` and `.pruned_through_hash`: how far audit retention has pruned, which **Verify integrity** now checks. An installation that has already pruned starts from the sequence number before its oldest event |
| `V39__ens_license_latest_time_seen` (0.0.4) | `ens_license_installation.latest_seen_ms`: the latest time the installation has seen, so a clock set back does not bring an expired license back |

Migrations are written **expand-only**: tables, nullable or defaulted columns and indexes are
added in a release, and anything dropped, renamed or narrowed goes at least one release after the
code stops using it. So with several replicas, the old pods keep serving on the migrated schema
during a rolling update. `V21` to `V39` are all expand-only. `helm rollback` and starting an older
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

### Upgrading to 0.0.5

0.0.3 and 0.0.4 were not published as release packages: the release package after 0.0.2 is 0.0.5,
and it brings the changes of all three at once. On this site, *from 0.0.3* and *from 0.0.4* say
which version a change came in; 0.0.5 itself changes nothing in the application. Two changes need
a decision before or right after the upgrade. The ones marked *new installs* change the default
only for an installation made with the new release.

**From 0.0.4: check before you upgrade**

| Change | What to do |
|---|---|
| Helm: `x509.trustedProxies` defaults to `""` (loopback) instead of every private range | Set it to the ingress controller's or router's pod network, for example `10.42.0.0/16`. With `x509.source: header` the chart **does not render** without it. See [Install → Upgrade](install.md#6-upgrade) |
| The client's address behind a proxy comes from `ENS_X509_TRUSTED_PROXIES` | With `SERVER_FORWARD_HEADERS_STRATEGY=framework`, the address in the audit trail and an API token's last-used address is the rightmost `X-Forwarded-For` hop that is not a trusted proxy, no longer the leftmost one, which a client could write. Until the list names the proxy in front (the compose network's subnet for the release package, `x509.trustedProxies` with Helm), the trail records the proxy's address. See [Configuration → The client's address behind a proxy](configuration.md#the-clients-address-behind-a-proxy) |

**From 0.0.4: behaviour that changes without a setting**

| Change | What to do |
|---|---|
| Scheduled jobs share eight threads | Nothing. `CERT_ALERT_SCHEDULER_THREADS` (Helm `schedulerThreads`) changes it; several jobs can now hold database connections at once, so watch `hikaricp_connections_pending` and raise `CERT_ALERT_DB_POOL_SIZE` if requests wait |
| A sweep the directory cuts short at its size limit fails | Before, it ended as a success after the entries below the limit. If a sweep now ends `FAILED` with `SizeLimitExceededException`, raise the service account's size limit in the directory; see [Troubleshooting](troubleshooting.md#syncing) |
| A failed sweep batch is written an entry at a time | A run's `errors` now counts entries, not batches of 200, and over-long values are cut to fit. Entries that used to be skipped with their batch every night are written at the next sweep |
| Prune deletes only after a clean sweep, and at most `prune.max-share` (10%) of a kind | A prune that would have deleted more now leaves that kind in place and ends `COMPLETED_WITH_ERRORS`. See [What a prune will not delete](#what-a-prune-will-not-delete) |
| Certificate clean-up counts `revoked-after` from when the revocation was found | Revoked certificates that are still current now become removable. With clean-up on, check `GET /api/v1/sync/certificate-cleanup/preview` after the upgrade |
| A revocation survives an unanswered check | A night with the CRL host or responder unreachable no longer turns *Revoked* into *Unknown* and announces the revocation again when the authority answers |
| An out-of-date CRL, or another authority's, no longer answers *Not revoked* | Certificates not on a list past its `nextUpdate`, or covered only by a list that names another issuer, become *Unknown* at the next revocation check. Expect the *Unknown* count to rise where an authority publishes late or `default-crl-url` is another CA's; see [Configuration → Revocation](configuration.md#revocation-cert-alertrevocation) |
| An expiring certificate escalates to `CRITICAL` | One more alert per certificate, on every alert channel, as a notification and as a `certificate.expiring` webhook with `severity` `CRITICAL`, when the hourly refresh finds it inside `critical-threshold-days`. Certificates already inside the window at the upgrade are not alerted on |
| No alert for a certificate already renewed | An old certificate that lapses beside its renewal no longer raises an operator alert or a notification. The status change is still in the entry's history |
| The round-up: one message per address, soonest expiries first, the cap reported | See [The round-up's message cap](#the-round-ups-message-cap). A person whose signing or encryption certificate lapsed and was not renewed stays in the round-up although the other half is current. A round-up about a single credential is no longer marked unread every night; it is marked unread once more, on the first run after the upgrade |
| A client certificate that more than one person publishes is refused | Before, it signed in whichever of them the database returned first. Give each person a certificate of their own, or have those people use the password form or single sign-on; see [Troubleshooting](troubleshooting.md#signing-in) |
| `GET /api/v1/sync/runs`, `/sync/prune/preview` and `/sync/certificate-cleanup/preview` need an administrator or auditor | A monitoring script that read them as an ordinary user gets `403`: give it an auditor, or an API token with `jobs:read` (runs) or `admin` (previews). `GET /api/v1/changelog` stays open and leaves `lastError` out for everybody else |
| The table endpoints (`/api/v1/datatables/*`) return at most 500 rows a page | A script that posted `length=-1` for every row gets `400`. Use the paged [public API](api-reference.md#public-api) instead |
| The consent banner records an agreement once per session, at most 10 a minute from one address | Behind a proxy that is not in `ENS_X509_TRUSTED_PROXIES`, every browser shares the proxy's address and that limit: set the trusted proxies (first table) |
| A license not yet valid, or expired, is not installed over one in full effect | Upload next term's license on or after its start date. See [Install → Add the license](install.md#3-add-the-license) |
| A clock set back does not bring an expired license back | Nothing, with a correct clock. A host whose clock is more than a day behind a time CertAlert has recorded has its license checked as of that time less a day, and the license screen says the clock is behind |
| **Verify integrity** checks both ends of the audit trail | The first retained event must be the one right after the last event retention pruned, and the newest must be the chain head. A trail whose events were deleted by other means than CertAlert's retention is now reported as broken; see [Troubleshooting](troubleshooting.md#audit-trail) |
| `access.denied` is recorded at the first refusal | The first refusal is written at once with `details.count` 1; identical ones in the next 60 seconds are one more event with their count and `details.collapsed`. A SIEM rule that summed `details.count` still gets the total |
| A wrong or revoked SIEM pull-API token is recorded | As a failed `auth.login` with `details.method` `api-token` and the client's address, never the token; repeats from one address are collapsed as for `access.denied`. Before, it left no trace |
| Syslog over `tcp` and `tls` reconnects, and may repeat a batch | After a collector restart the last batch written is sent again, so de-duplicate on the event `id`. Where no event may be missing, use Splunk HEC or the pull API |
| SAML: the identity provider's metadata is read again every hour | A rolled signing certificate is picked up without a restart. `cert-alert.sso.saml.metadata-refresh: 0` reads it once, as before |
| A disabled webhook's queued deliveries are given up on | Before, they were still posted for up to a day. Disabling or renaming a webhook no longer needs its host to resolve |
| Audit outputs wait out their backoff; the export is consistent | While Splunk or syslog is down, a new event no longer ends the wait after a failed delivery, so there is one attempt and one warning per backoff rather than one per event. **Export** on the audit page no longer repeats or drops events recorded while it runs |
| Expired sessions nobody signed in to are removed in bulk | Nothing. A `spring_session` table that grew under traffic without cookies (health checks against pages, scanners) shrinks again |
| API tokens are narrowed only by what the live directory says | Tokens that stopped working after their creator signed in during a directory outage stay narrowed: create new ones. See [Troubleshooting](troubleshooting.md#signing-in) |
| The **Open** button on a server's page needs an `http://` or `https://` `serverURL` | Any other value is shown as text only |

**From 0.0.3**

| Change | What to do |
|---|---|
| Release package: plain HTTP to other hosts is refused on *new installs* | A new `.env` has `CERT_ALERT_ALLOW_PLAIN_HTTP=false`, and `install.sh` will not start CertAlert with TLS off on an address other than `127.0.0.1` until `.env` turns TLS on, sets `CERT_ALERT_BIND_ADDRESS=127.0.0.1` behind your proxy, or sets `CERT_ALERT_ALLOW_PLAIN_HTTP=true` (warned about at every run). An upgraded `.env` that does not mention the setting is only warned about: add `CERT_ALERT_ALLOW_PLAIN_HTTP=true` to say so, or move to TLS. An `.env` that sets `false` itself is held to it. See [Choose where it listens](install.md#4-choose-where-it-listens) |
| Webhooks: private addresses refused for new webhooks | `block-private-networks` is unset by default: existing webhooks keep their address (migration `V29`, named in a startup warning) until their URL changes. List internal receivers in `CERT_ALERT_WEBHOOKS_ALLOWED_HOSTS`, which may now name private hosts and ranges; `false` keeps the old rule for every webhook. See [Webhooks → Private networks](webhooks.md#private-networks) |
| Webhook secret key generated on *new installs* | An upgrade without `CERT_ALERT_WEBHOOKS_SECRET_KEY` (Helm `webhooks.secretKey`) keeps storing secrets unencrypted, with the warning. Set one (`openssl rand -base64 32`) to encrypt them at the next start, and back it up |
| OIDC: Entra ID group overage refused | A sign-in whose token names `_claim_names.groups` instead of carrying the groups is refused (`/login?sso_error=groups`, a failed `auth.login`). Send only the groups assigned to the application; see [Single sign-on](single-sign-on.md#microsoft-entra-id-oidc) |
| OIDC: signing out at the provider | `CERT_ALERT_SSO_OIDC_PROVIDER_LOGOUT` (Helm `sso.oidc.providerLogout`) is off on upgrade, with a startup warning while OIDC is on, and on for *new installs*. Register `<public-url>/login?logout` as a post-logout redirect URI before turning it on |
| Webhooks: optional egress proxy | New and unset: webhooks keep connecting directly. `CERT_ALERT_WEBHOOKS_PROXY_HOST` and `_PORT` (Helm `webhooks.proxy.host` and `.port`) send them through an HTTP forward proxy with `CONNECT` to the address CertAlert resolved and checked, so the host still needs DNS for the receivers. A proxy setting that cannot work stops the start. See [Webhooks → Through an egress proxy](webhooks.md#through-an-egress-proxy) |
| SAML single logout | `CERT_ALERT_SSO_SAML_SINGLE_LOGOUT` (Helm `sso.saml.singleLogout`) is off on upgrade, with a startup warning while SAML is on, and on for *new installs*. It needs the service provider's signing key and an identity provider with a `SingleLogoutService`; give the provider the service provider metadata again so it learns `<public-url>/logout/saml2/slo`. See [SAML single logout](single-sign-on.md#saml-single-logout) |
| Auditors may export the inventory | `POST /api/v1/certificates/export` is open to the auditor role, audited as `inventory.exported` |
| `cert-alert.security.enabled=false` needs the `dev` profile | The application refuses to start with it otherwise. It was never meant for anything but local development |
| Log-level changes are audited | Changing a level through `/actuator/loggers` records `logging.level-changed` (category `configuration`) |
| Sign-in log lines stay on one line | Certificate subjects and issuers, identity-provider usernames and sign-in identifiers are logged with line breaks as spaces and other control characters as `?`. A log search for an exact subject containing such characters no longer matches it verbatim |
| Backups | The release package adds `backup.sh` and `restore.sh`; the chart adds `upgradeBackup` (off). See [Backup and restore scripts](#backup-and-restore-scripts) |
| Migrations `V29` to `V32`, `V37` and `V39` | Run on the first start; all add columns only (expand-only). The list is under [Upgrades](#upgrades) |

After the upgrade, run **Sync directory** and look at the result: sweeps that used to end early or
drop batches now say so.

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
- **Scheduler threads**: `CERT_ALERT_SCHEDULER_THREADS` (default 8, from 0.0.4), per replica. More
  threads let more jobs run at once and draw on the same database pool.
- **Revocation throughput**: `cert-alert.revocation.workers` (default 8) concurrent OCSP requests.

## Routine checklist

| Frequency | Task |
|---|---|
| Daily | Check last sweep runs are `COMPLETED`, without errors; glance at the Notifications round-up status |
| Weekly | Review the audit log (`/admin/audit`) for unexpected activity, and **Verify integrity**; check revocation *Unknown* counts and the mail outbox |
| Monthly | Review `serverPOC` warnings in logs; confirm the round-up lead days are still right |
| Per release | Back up, run `upgrade.sh` or `helm upgrade`, run Sync directory |
