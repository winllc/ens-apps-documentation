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
| `/actuator/health/liveness` | public | **Liveness probe**. Is the process alive? |
| `/actuator/health/readiness` | public | **Readiness probe**. Can it take traffic? |
| `/actuator/health` | public (details for admins only) | Aggregate, **includes the LDAP indicator**: `DOWN` when the directory is unreachable |
| `/actuator/info` | public | Application info |
| `/actuator/metrics`, `/actuator/loggers` | `ROLE_ADMIN` | JVM / HTTP metrics; runtime log levels |

!!! warning "Probe liveness/readiness, not the aggregate"
    The aggregate `/actuator/health` turns `DOWN` whenever the directory is unreachable. That is
    no reason to restart a process that is still serving cached data. Point orchestrator probes at
    the liveness and readiness endpoints.

    Both install methods already do this:

    - The release package's `docker-compose.yml` checks `/actuator/health/liveness`, over HTTP
      and then HTTPS, so it keeps working once TLS is on. `docker compose ps` shows the result.
    - The Helm chart sets liveness, readiness and startup probes on the liveness and readiness
      endpoints. Tune them with `livenessProbe.*`, `readinessProbe.*` and `startupProbe.*` in
      your values. The startup probe allows 10 minutes, for first-start migrations.

## Logs

CertAlert logs to standard output (Spring Boot defaults; no file appender is configured). The
application logger `com.winllc.certalert` is at `INFO`. Change it with
`logging.level.com.winllc.certalert` and a restart, or at runtime through the standard Spring
Boot `/actuator/loggers` endpoint (`POST /actuator/loggers/com.winllc.certalert` with
`{"configuredLevel":"DEBUG"}`), which requires an administrator's session.

!!! note "Calling the API from scripts"
    CertAlert has no HTTP Basic or token authentication: callers sign in with a client
    certificate or through the `/login` form, and `POST`/`PUT`/`DELETE` requests carry Spring
    Security's CSRF token (the pages read it from the `_csrf` meta tags), so a script fetches a
    page in the same session first to obtain it. `GET` requests need only the session. See [API reference](api-reference.md#conventions).

Log lines worth knowing:

| Log message (abridged) | Meaning |
|---|---|
| `Skipping the scheduled <JOB> job: the previous run has not finished` | Overlap guard: a run outlasted its interval |
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
| `Removed N read notification(s)` | Notification retention ran |
| Alert summaries at `WARN` / `ERROR` | The logging alert channel (`ERROR` = critical severity) |

## Scheduled jobs

All jobs run inside the application process, in UTC, and are guarded against overlapping
themselves. Directory jobs are recorded in the `sync_run` table and readable at
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
| **audit retention** | `0 30 3 * * SUN` | `cert-alert.audit.retention.cron` | **off** | Delete audit records older than `retention.after` |
| **changelog connector** | continuous | `cert-alert.ldap.changelog.*` | **off** | Follow `cn=changelog` |

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
- If the directory has trimmed changes past the stored position, it triggers a full sweep
  (`full-sync-on-gap`) and counts it in `gapsDetected`.
- Status: `GET /api/v1/changelog` (position, lag, counters, last error). Pages show a header
  badge: **Live · change N**, **N changes behind**, or **Connector stopped**.
- The service account needs read access to `cn=changelog`, which is usually a separate grant from
  read access to the data tree.

## Backups

All state CertAlert owns is in the database. The directory remains the source of truth for people,
servers and certificates, but the following exist **only** in CertAlert's database:

| Table | Content |
|---|---|
| `server_contact` | Points of contact added in CertAlert |
| `user_email_alias` | Addresses added to people (personal and group) |
| `project`, `project_user`, `project_server`, `project_admin` | Projects, membership, project administrators |
| `notification`, `notification_setting` | Notifications and the round-up lead-days setting |
| `audit_event` | The audit trail |
| `server_attribute_definition`, `server_attribute_option` | Editable-attribute definitions (the values themselves live in the directory) |
| `changelog_cursor` | Changelog position |
| `sync_run` | Job run log |

Everything else (`directory_user`, `directory_server`, `cached_certificate`, identifiers, POCs)
can be rebuilt by a sweep.

=== "Release package"

    The database runs in the `postgres` service. Run these in the release directory:

    ```bash
    docker compose exec -T postgres pg_dump -U certalert -Fc certalert > cert-alert-$(date +%F).dump
    docker compose exec -T postgres pg_restore -U certalert -d certalert --clean < cert-alert-YYYY-MM-DD.dump
    ```

    `upgrade.sh` also writes a backup to `backups/` before it upgrades.

    Also back up the configuration: `.env`, `license/`, `tls/` and `email-templates/`.

=== "Helm"

    The database is your own PostgreSQL. Back it up with your usual tools, for example:

    ```bash
    pg_dump -h postgres.example.gov -U certalert -Fc certalert > cert-alert-$(date +%F).dump
    ```

    Also keep your values file and the Secrets it names. The license PVC holds the installation
    id; see [Install with Helm](install.md#4-license).

    The chart's evaluation database (`database.embedded.enabled`) is not backed up. Do not keep
    data you need in it.

## Monitoring

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
- **JVM**: `/actuator/metrics` (admin only).

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

Migrations shipped: `V1` (directory tables) through `V17` (user additional email).

## Scaling

- **One instance.** The scheduled jobs and the changelog connector run inside the process, and
  the overlap guard on jobs is per process; there is no distributed lock. A second instance would
  run every job twice. The release package runs one container, and the Helm chart supports only
  `replicaCount: 1` (with the `Recreate` strategy, so the old pod stops before the new one
  starts). Scale up, not out.
- **Directory size**: sweeps are paged (`page-size`), streamed, and written in batches
  (`batch-size`); built for 100,000+ entries. Raise `search-timeout` if a single page search is
  slow.
- **Memory**: the heap follows the container limit (`MaxRAMPercentage=75`).
- **Database pool**: `CERT_ALERT_DB_POOL_SIZE` (default 10).
- **Revocation throughput**: `cert-alert.revocation.workers` (default 8) concurrent OCSP requests.

## Routine checklist

| Frequency | Task |
|---|---|
| Daily | Check last sweep runs succeeded; glance at the Notifications round-up status |
| Weekly | Review Admin → System audit log for unexpected activity; check revocation *Unknown* counts |
| Monthly | Review `serverPOC` warnings in logs; confirm the round-up lead days are still right |
| Per release | Back up, run `upgrade.sh` or `helm upgrade`, run Sync directory |
