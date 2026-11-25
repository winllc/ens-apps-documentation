# Operator guide

This guide covers running the portal day to day: starting and stopping it, checking health,
reading logs, backing up its data, monitoring, upgrading, and troubleshooting.

## What runs where

| Process | Docker package | Native package | Helm chart | Listens on |
|---|---|---|---|---|
| Web tier (nginx + static client) | `web` container (uid 1001) | Host nginx, site `directory-services-portal.conf` | Deployment `<release>-directory-services-portal-web` | 8080 (HTTP); 8443 with HTTPS |
| API (Spring Boot) | `api` container (uid 185) | systemd unit `directory-services-portal` as user `dsp` | Deployment `<release>-directory-services-portal-api` (one replica; more with a shared database) | 3001 (never published by the compose files or the chart; `127.0.0.1` in the native package). The management port (`MANAGEMENT_SERVER_PORT`, 9090 in the Helm chart) when set |
| Demo OpenLDAP (optional) | `openldap` container (`install.sh --demo-openldap`) | n/a | n/a | 389 (not published) |

!!! warning "One API instance unless it has a shared database"
    Without `DSP_DATABASE_URL`, the API is the single writer of its data directory: the
    configuration store, the hash-chained audit store and the license state are files, and
    sessions, lockout counters and background tasks are held in memory. A second API process on the
    same data would fork the audit chain and lose configuration changes, so the Helm chart refuses
    `api.replicaCount` above 1 without `database.url`. With a shared PostgreSQL database, that state
    moves into it and several API instances can run. See
    [Without a shared database](availability-and-sizing.md#without-a-shared-database-one-api-instance)
    and [With a shared database](availability-and-sizing.md#with-a-shared-database-several-api-instances).

## Start, stop and status

=== "Docker package (`portal.sh`)"

    ```bash
    ./portal.sh up                 # start or update (applies .env changes), waits until the API is healthy
    ./portal.sh down               # stop and remove the containers (data is kept)
    ./portal.sh restart            # restart the services, wait until healthy
    ./portal.sh status             # containers, health and where to open the portal
    ./portal.sh logs [service]     # follow the logs (api, web or openldap); last 200 lines first
    ./portal.sh backup [file]      # archive the portal data
    ./portal.sh restore <file>     # replace the portal data with a backup (the archive is checked first)
    ./portal.sh version
    ```
    `up` waits up to about three minutes for the API container's health check to become
    `healthy`, and fails with a pointer to `./portal.sh logs api` if it does not.

=== "Native (systemd)"

    ```bash
    sudo systemctl start directory-services-portal
    sudo systemctl stop directory-services-portal
    sudo systemctl restart directory-services-portal   # after editing portal.env
    systemctl status directory-services-portal
    journalctl -u directory-services-portal -f
    ```
    The unit restarts the API on failure after 5 seconds (`Restart=on-failure`).

=== "Helm"

    For a release named `dsp` in namespace `dsp`:

    ```bash
    kubectl get pods -n dsp
    kubectl logs -n dsp deploy/dsp-directory-services-portal-api -c api -f
    kubectl rollout restart -n dsp deploy/dsp-directory-services-portal-api
    kubectl scale -n dsp deploy/dsp-directory-services-portal-api --replicas=0   # stop the API, keep data
    kubectl scale -n dsp deploy/dsp-directory-services-portal-api --replicas=1
    ```
    Change settings in your values file and run `helm upgrade` (see [Upgrades](#upgrades)).

Changes to environment variables take effect only after the API restarts. Changes made in the
admin UI (forms, definitions, grants, dashboards, analyses and custom schema) take effect
immediately and need no restart.

## Health checks

The API uses Spring Boot's probes. They need no sign-in and no license.

| Endpoint | Answers |
|---|---|
| `/actuator/health/liveness`, also `/livez` | The API itself is running |
| `/actuator/health/readiness`, also `/readyz` | The API can serve: the directory answers (`ldap`), the audit log is writable (`auditLog`) and the data disk has at least 10 MB free (`diskSpace`). `503` otherwise |
| `/actuator/health` | Both. Components and error details only for a signed-in administrator |
| `/api/health` | Kept for existing monitoring: always `{"ok":true}` while the API runs. Liveness only; it does not contact LDAP |

- **Where they are.** On the main port (3001) by default. With `MANAGEMENT_SERVER_PORT` set, the
  `/actuator/health/**` probes move to that port, open without sign-in; `/livez`, `/readyz` and
  `/api/health` stay on the main port.
- **Not through the web tier.** The web tier publishes only `/api`, so browsers can't reach the
  actuator paths. Check readiness on the API host or pod.
- **`/api/info` is no longer a readiness check.** Anonymous callers get only whether the directory
  answers (`connected`); the LDAP URL and the error text are shown to administrators only. From
  v0.0.9 it asks the directory at most every 10 seconds and answers from the last check in between,
  so `connected` can lag a change by that long.

Where each deployment checks readiness:

| Deployment | Check |
|---|---|
| Docker package | The API container's health check: `curl -sf http://localhost:3001/readyz` every 15 s. `./portal.sh up` waits until it is healthy, and says so if the directory, the audit log or the data disk is the problem |
| Image `HEALTHCHECK` | Readiness on port 3001 |
| Helm | Startup and liveness probes on liveness, the readiness probe on readiness. On the management port (`metrics.port`, 9090) while `metrics.enabled` is `true`; otherwise `/livez` and `/readyz` on 3001. Tune them with `api.startupProbe`, `api.livenessProbe` and `api.readinessProbe`. The web tier is probed with `GET /` |

```bash
# On the API host, or in the API container or pod
curl -s http://localhost:3001/readyz
# {"status":"UP"} or HTTP 503 {"status":"DOWN"}
```

A pod or container is not ready while LDAP is unreachable, the audit store can't be written, or
the data disk has under 10 MB free.

From v0.0.9, while a directory can't be reached, requests that need it fail at once instead of each
waiting for the connect timeout (`LDAP_TIMEOUT_MS`): after a failed attempt to connect, calls to
that directory fail with the same error for 5 seconds, then one request tries again. The portal's
other pages stay responsive meanwhile, and it reconnects by itself within seconds of the directory
coming back.

## Logs

| Source | Where |
|---|---|
| API application log | Container stdout (`./portal.sh logs api`; with Helm, `kubectl logs deploy/<release>-directory-services-portal-api -c api`) or `journalctl -u directory-services-portal` |
| Audit events | The `ens.audit` logger on stdout, one raw `ens-audit/1` JSON line per event (unless `ENS_AUDIT_STDOUT=false`), **and** the audit store in `AUDIT_DIR`. See [Audit logging](../audit-logging.md) |
| nginx access log | Web container stdout (`$remote_addr ... "$request" $status ...`) |
| nginx error log | Web container stderr |
| Entrypoint | `dsp-entrypoint: rendered ...` lines on web container start |

`LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` writes the API's log lines as JSON in the Elastic Common
Schema. The Helm chart does this by default (`logFormat: json`; `text` turns it off). The audit
lines are `ens-audit/1` JSON either way.

Useful API log messages:

| Message | Meaning |
|---|---|
| `Loaded portal configuration from .../portal-config.json` | Config store read at startup |
| `Seeded demo forms, directories, permissions, dashboards and analyses` | Demo config was written into an empty store |
| `License: ...` | The license state and this installation's id, at startup |
| `Certificate sign-in: <subject> (serial <n>) -> <dn>` | Successful X.509 sign-in |
| `Certificate sign-in rejected for <subject> (issuer <issuer>): <reason>` | Rejected certificate |
| `Ignoring X-SSL-Client-Cert header from untrusted address <ip>` | Certificate header from a peer not in `X509_TRUSTED_PROXIES`; refused and audited |
| `Ignoring <header> header from <ip>: X-DSP-Proxy-Secret is missing or wrong (X509_PROXY_SECRET)` | Certificate header from a trusted address without the right proxy secret; refused and audited |
| `Cannot read X509_PROXY_SECRET_FILE <path>: <error>` | The API cannot read the proxy secret file, so it refuses every forwarded certificate |
| `Group lookup failed: ...` | `LDAP_GROUP_FILTER` search failed; the user signs in with `memberOf` groups only |
| `The account of <user> is disabled or locked (...); ending its session` | From v0.0.9: the directory marks a signed-in user's account disabled or locked, so the session ended (audited as `auth.session-expired`, reason `account-disabled`) |
| `Single sign-on starts are limited to <n> a minute per client address (SSO_START_LIMIT_PER_MINUTE), and WEB_TRUSTED_PROXIES is not set: ...` (WARN at startup) | From v0.0.9: single sign-on is on and `WEB_TRUSTED_PROXIES` is unset. Harmless when nothing sits in front of the web tier; behind an ingress controller, a load balancer or a TLS proxy, set it ([Configuration](configuration.md#behind-another-proxy)) |
| `Job <id> failed: the replica running it stopped` | From v0.0.9, with the shared database: a background task of an API instance that stopped was marked failed |
| `Closed <n> group access request(s) nobody answered in time` | From v0.0.11: the request expiry job closed requests ([Scheduled jobs](#scheduled-jobs)) |
| `Removed <n> group member(s) whose end date had passed` | From v0.0.11: the membership expiry job removed members |
| `Could not remove <member> from <group> after its end date <date>: ...` (WARN) | From v0.0.11: the directory refused to remove a member whose end date passed (for example the last member of a group that requires one). Audited as a failed `group.membership-expired`; tried again a day later |
| A warning naming DISA STIG at startup | Certificate sign-in is on with `ENS_X509_REVOCATION=off` |
| A warning naming FIPS at startup | `LDAP_TLS_REJECT_UNAUTHORIZED=false` or `ENS_AUDIT_SPLUNK_INSECURE=true` is set |

## Data and backups

Without a shared database, all portal state outside LDAP lives in `DATA_DIR`, and the license and
the installation id live in a separate directory or volume, as below. With `DSP_DATABASE_URL`, the
first start imports these files into PostgreSQL and then no longer reads or writes them: back up
the database instead (it holds the configuration, the audit store, the license and the
installation id).

| Path | Content | Notes |
|---|---|---|
| `portal-config.json` | Forms, directory definitions, grants, custom schema, schema cache, dashboards, analyses | Read at startup, written atomically on every admin change |
| `audit/` | The audit store: `audit-YYYY-MM.jsonl` (hash-chained `ens-audit/1` events), `audit-state.json` (output cursors), `audit-api-tokens.json` (pull API token hashes) and, from v0.0.9 once retention has run, `audit-pruned` (how far retention has deleted) | Appended and synced to disk in the request that caused the event. Only the retention job (`ENS_AUDIT_RETENTION_DAYS`) removes events. Back up and restore the directory as a whole: **Verify integrity** checks the oldest event against `audit-pruned` |
| `analysis-results/<id>.json` | Latest result of each saved analysis | Re-creatable by running the analysis again |
| `api-tokens.json` | [API tokens](api-reference.md#api-tokens) (SHA-256 hashes only) | Owner-readable only |
| `webhooks.json`, `webhook-deliveries.json` | [Webhooks](webhooks.md), **with their signing secrets**, and pending deliveries | Owner-readable only. Protect the backups that contain them. From v0.0.4 the secrets can be encrypted with `DSP_SECRETS_KEY`: back the key up apart from these files |
| `group-access-requests.json` | [Group access](admin-guide.md#group-access) requests: open ones, and closed ones for a year (at most 5,000) | The audit trail keeps every request and decision for good |
| `group-membership-expiries.json` (v0.0.11) | The end dates of the group memberships the portal granted for a limited time | Without it the portal no longer knows those end dates: the members stay in their groups until someone removes them. Restore it together with the rest of `DATA_DIR` |
| License directory (`/var/lib/ens/directory-portal`) | The license file, the installation id and, from v0.0.9, `latest-time-seen` (the latest time this installation has seen, so that a clock set back doesn't bring an expired license back) | Not in `DATA_DIR`. Losing it gives the installation a new id; bound licenses must then be re-bound |

Directory data stays on your LDAP server and must be backed up there.

=== "Docker package"

    ```bash
    ./portal.sh backup                              # portal-backup-<YYYYmmdd-HHMMSS>.tar.gz
    ./portal.sh backup /backups/dsp-$(date +%F).tar.gz
    ./portal.sh restore /backups/dsp-2026-10-01.tar.gz   # asks for confirmation, stops the API meanwhile
    ```
    The backup runs `tar` over `/data` in a one-off API container while the portal keeps running,
    so it includes the configuration, the audit store and analysis results. From v0.0.9 it is
    written to `<file>.part` and renamed when complete, so a backup that failed part-way (a full
    disk) leaves no file that looks like a good archive. `restore` checks the archive before it
    asks or stops anything (`<file> is not a complete backup (gzip-compressed tar); nothing was
    changed`), extracts it next to the data, and replaces the data only once the whole archive is
    out. If that fails, the data is left as it was and the API is started again (`Could not
    restore <file> (see above); the API was started again`). The data volume is
    `directory-services-portal_portal-data`. The backup does **not** include the license volume
    (`directory-services-portal_ens-license`); keep a copy of your license file, and see
    [Disaster recovery](availability-and-sizing.md#disaster-recovery) for the installation id.

=== "Native"

    ```bash
    sudo tar -C /var/lib/directory-services-portal -czf dsp-backup-$(date +%F).tar.gz .
    sudo tar -C /var/lib/ens/directory-portal -czf dsp-license-$(date +%F).tar.gz .
    ```
    Restore: stop the service, extract into `/var/lib/directory-services-portal` (and the license
    directory), restore ownership to `dsp:dsp`, then start the service.

=== "Helm"

    The data is on the API's data PVC (`<release>-directory-services-portal-data`, mounted at
    `/data`). Back it up with your volume snapshot tool, or copy it out of the running pod:

    ```bash
    kubectl exec -n dsp deploy/dsp-directory-services-portal-api -c api -- \
      tar -cf - -C /data . | gzip > dsp-backup-$(date +%F).tar.gz
    ```
    Also keep your values file, the Secrets it names, and the license PVC
    (`<release>-directory-services-portal-license`), which holds the installation id. The chart
    has no backup CronJob.

The audit store is appended to while the backup runs, so a backup taken while the portal is busy
can end in the middle of an event line. After a restore, the API closes that partial line at
startup, logs `Audit file ... ended in a partial line (interrupted write); it is ignored`, and
continues the chain from the last complete event. For a backup without that, stop the API first
(`./portal.sh down`, `systemctl stop`, or scale the Deployment to 0).

From v0.0.9 an event that could not be written completely while the API runs (a full disk) is
removed from the month file again, so the next event starts on its own line and **Verify
integrity** is not thrown off by the fragment. The pull API, searches, verification and retention
read the month files as a stream, so a SIEM that pulls the whole history from the start does not
need memory the size of a month file.

!!! tip "Audit retention"
    By default the audit store keeps every event. Set `ENS_AUDIT_RETENTION_DAYS` to have a daily
    job remove older events once every enabled output (Splunk HEC, syslog) has delivered them; it
    never removes the newest event and records `audit.pruned`. The audit page searches all
    retained history. Treat the audit files as sensitive: they hold changed values and the names
    people tried to sign in with.

## Scheduled jobs

The API runs this work by itself; there is nothing to schedule. With several API instances (a
shared database), audit retention, the license check and the two group access jobs each run on one
instance at a time, under a cluster lock.

| Job | Runs | What it does |
|---|---|---|
| Audit retention | Daily at 03:17 (the API's time zone), only with `ENS_AUDIT_RETENTION_DAYS` above 0 | Removes older audit events every enabled output has delivered, never the newest; records `audit.pruned` and how far it deleted |
| License check | Every minute, at startup and after an upload | Records `license.mode-changed` when the mode changes |
| Expired sessions and single sign-on requests | Every minute | Removes them |
| Group access: request expiry (v0.0.11) | Every 15 minutes, first 2 minutes after the API starts | Closes open requests nobody answered within the definition's **Requests expire after (days)** as *expired*, and records `group.access-expired` for each. Does nothing while no definition sets it (the default) |
| Group access: membership expiry (v0.0.11) | Every hour, first 3 minutes after the API starts | Removes members whose end date has passed (dates are whole days in UTC, so a member is removed within the hour after their last day), with the usual `entry.update`, the `group.membership_changed` webhook and a `group.membership-expired` event. A member the directory won't let go is recorded as a failed `group.membership-expired` and tried again a day later |

!!! warning "The group access jobs pause unless the license is in full effect"
    Both run only while the license is valid (or in its grace period) and covers group access
    (`directory-editor` or `self-service-profile`). While the portal is read-only or unlicensed,
    requests don't expire and **no member is removed at their end date**. Nothing is lost: the
    first run after the license is back in effect catches up, so memberships that ended meanwhile
    are removed then. Watch `ens_license_mode` and renew in time. See
    [Admin guide → Group access](admin-guide.md#group-access).

## Monitoring

**Prometheus.** Set `MANAGEMENT_SERVER_PORT` (the Helm chart does, on 9090, while
`metrics.enabled` is `true`) and scrape `/actuator/prometheus` on that port. Without it there is no
Prometheus endpoint. Never publish the management port to users: the Docker package publishes it
only if you add a compose override (bind it to `127.0.0.1`), the native package listens on
`127.0.0.1` (`MANAGEMENT_SERVER_ADDRESS`), and the chart exposes it as the `metrics` port of the
API's Service only, never through the ingress or route. With the Prometheus Operator, set
`metrics.serviceMonitor.enabled: true`; with `networkPolicy.enabled`, admit your Prometheus with
`metrics.allowFrom`.

Besides the JVM's and Tomcat's metrics:

| Metric | Meaning |
|---|---|
| `ens_license_mode{mode}` | 1 for the mode in effect (`full`, `read_only`, `unlicensed`), 0 for the others |
| `ens_license_days_remaining` | Days until the license, or its grace period, ends. NaN for a perpetual license or none |
| `ens_audit_events_total{category,outcome}` | Audit events stored |
| `ens_audit_forward_failures_total{output}` | Failed deliveries to `splunk` or `syslog` |
| `ens_auth_attempts_total{method,outcome}` | Sign-ins (`password`, `x509`) by outcome (`success`, `failure`, `denied`) |
| `ens_dsp_ldap_operation_seconds{operation,outcome}` | LDAP operations (`search`, `scan`, `get`, `add`, `modify`, `rename`, `delete`, `bind`, `schema`, `ping`), timed; `failure` when one failed |
| `ens_dsp_ldap_pool_available` | Idle connections in the service account's pool |
| `ens_dsp_entry_writes_total{operation,outcome}` | Entry `create`, `update` and `delete` through the portal (`success`, `failure`, `denied`) |

Suggested alerts:

- readiness failing (`/readyz` or the pod's ready condition);
- `ens_license_mode{mode="full"}` is 0, or `ens_license_days_remaining` under 30;
- `ens_audit_forward_failures_total` increasing;
- `ens_auth_attempts_total{outcome="failure"}` rising sharply;
- `ens_dsp_ldap_pool_available` at 0 for minutes at a time (raise `LDAP_POOL_SIZE`, see
  [Sizing](availability-and-sizing.md#sizing));
- disk usage of `DATA_DIR` (the audit store grows with activity);
- `ERROR` lines in the API log.

**Memory.** The container image runs Java with `-XX:MaxRAMPercentage=75.0`. Large metric scans
and analyses (up to `METRICS_MAX_ENTRIES` entries per source) need heap. Raise the container
memory (`API_MEMORY_LIMIT` in the Docker package, `api.resources` in Helm) or set
`JAVA_TOOL_OPTIONS=-Xmx...`.

## Upgrades

=== "Docker package"

    1. `./portal.sh backup`
    2. Unpack the new package next to the old one.
    3. In the new directory: `./upgrade.sh ../directory-services-portal-<old-version>`.

    `upgrade.sh` copies `.env`, `license/` and `certs/` across, loads the new images, generates
    `X509_PROXY_SECRET` if `.env` has none, backs up the data to `backups/` and restarts. The data
    and license volumes are reused, because the compose project name stays
    `directory-services-portal`. With the shared database (`DSP_DATABASE_URL` in `.env`), that
    backup holds none of the configuration, audit store or license state, and the new version may
    update the database's tables as it starts: back the database up first (`pg_dump`). From
    v0.0.9 `upgrade.sh` warns about it and, run from a terminal, asks before going on. See
    [Installation → Upgrade](install.md#upgrade).

=== "Native"

    Run the new package's `sudo ./install.sh`, then `systemctl restart directory-services-portal`.
    `portal.env`, `proxy-secret` and `/var/lib/directory-services-portal` are kept; the proxy secret
    is generated if there is none. The web client directory is replaced.

=== "Helm"

    Back up the data volume, then:

    ```bash
    helm upgrade dsp oci://ghcr.io/winllc/charts/directory-services-portal --version X.Y.Z \
      -n dsp -f my-values.yaml
    ```
    The old API pod stops before the new one starts. The PVCs are kept. With `x509.enabled`, see
    [Installation → Upgrade](install.md#upgrade_1) for the proxy secret and what edge termination
    needs.

Each release ships images rebuilt on the latest Red Hat UBI security updates, so stay on a
current release.

Older `portal-config.json` files without `dashboards` or `analyses` are upgraded automatically on
load.

### Upgrading to the platform-baseline release

The release that adds the ENS platform baseline (sessions, the consent banner, probes and metrics,
revocation checking) changes these things operators notice:

| Change | What to do |
|---|---|
| The idle timeout default is now `30m` (was 480 minutes) | Docker: an `.env` that still sets `SESSION_TTL_MINUTES=480` keeps 480; replace it with `ENS_SESSION_IDLE_TIMEOUT`. Helm: releases that never set `server.sessionTtlMinutes` change to `30m`. See [Configuration](configuration.md#sessions-and-sign-in-lockout) |
| Readiness includes the directory, the audit log and the data disk | A container or pod is not ready while LDAP is unreachable, the audit store can't be written, or the data disk has under 10 MB free. `./portal.sh up` says which |
| A directory write needs a writable audit store | With a full or unwritable audit disk (under 1 MB free), entry changes are refused with 503 instead of going through unaudited |
| Helm: probes move to the management port | With `metrics.enabled` (the default) the probes and Prometheus are on port 9090 |
| Helm: API logs become JSON | Set `logFormat: text` to keep plain text |
| Helm: the bind password is mounted as a file | `LDAP_BIND_PASSWORD_FILE`; nothing to change in your values |
| `X509_CHECK_REVOCATION=true` becomes `ocsp-then-crl`, failing closed | Move to `ENS_X509_REVOCATION` |
| Startup warnings | Certificate sign-in without revocation checking, and `LDAP_TLS_REJECT_UNAUTHORIZED=false`, now log a DISA STIG or FIPS warning |
| `/api/info` says less to anonymous callers | Monitoring that read `connected` or `error` from it should use `/readyz` instead |
| `AUDIT_MEMORY_EVENTS` is no longer used | Nothing to do; the audit page searches all retained history |

### Upgrading to the release with single sign-on, the integration API and import and export

Nothing needs changing: without `SSO_*` the portal signs in as before, no API token or webhook
exists until an administrator creates one, and every new setting has a safe default.

| Change | What to do |
|---|---|
| [Single sign-on](single-sign-on.md) (OIDC, SAML 2.0), off until configured | The login page gets a button per provider; `/api/auth/sso/**` is new (the web tier already forwards `/api/`) |
| [Integration API](api-reference.md#integration-api-apiv1) (`/api/v1`, API tokens) and its OpenAPI document (`/api/v1/openapi`) | Tokens are stored as SHA-256 in `DATA_DIR/api-tokens.json`, or in the shared database |
| [Webhooks](webhooks.md) | HTTPS only, never to private, loopback, link-local or metadata addresses unless the host is in `WEBHOOK_ALLOWED_HOSTS`. Alone, kept in `DATA_DIR/webhooks.json` and `webhook-deliveries.json` (the first holds the signing secrets); with the shared database, in the database (table `dsp_webhook_delivery`, Flyway V3). A NetworkPolicy or firewall must let the API reach the receivers on 443 (Helm `networkPolicy.egress.webhooks`) |
| [Import and export](import-export.md) on directory pages | The web tier's upload limit is now `MAX_BODY_SIZE` (default `5m`, as before); raise it with `IMPORT_MAX_BYTES`. The native package's nginx site keeps `client_max_body_size 5m`; edit it there |
| [Accessibility](accessibility.md) | Darker secondary text, underlined links in text, 3:1 form-field borders, a skip link and a title per page |
| New variables | The release bundle's `env.template` lists `SSO_*`, `API_*`, `WEBHOOK*`, `EXPORT_*`, `IMPORT_*`, `BULK_MAX_MEMBERS` and `MAX_BODY_SIZE`; `upgrade.sh` keeps your `.env`, so add them only if you use them |

### Upgrading to v0.0.4

What changes for an upgraded installation, and what to do (nothing, unless a line says so):

| Change | What to do |
|---|---|
| `DIRECTORY_MODE=memory` (the built-in demo directory) needs `DEMO_MODE=true`, or an API listening on loopback only (`SERVER_ADDRESS=127.0.0.1`, as the native package does); otherwise the API refuses to start (ERROR in the log) | Docker package: `install.sh` (also run by `upgrade.sh`) adds `DEMO_MODE=true` to an `.env` that sets `DIRECTORY_MODE=memory` and has no `DEMO_MODE` line. Helm: the chart sets it for `directory.mode: memory`. `docker-compose.demo.yml` sets it. A bare jar or image run with the default mode must now set `DIRECTORY_MODE=ldap` or `DEMO_MODE=true`. New in `env.template`: `DEMO_MODE=false` |
| Docker package: plain HTTP on this host only for new installs | A new `.env` without `--bind` gets `WEB_BIND=127.0.0.1` (with `--https`, `0.0.0.0` and `ENS_COOKIE_SECURE=true` as before); `--bind 0.0.0.0` publishes plain HTTP on every interface. An existing `.env` is never rewritten: it keeps its `WEB_BIND` (none: every interface), and `install.sh` warns when plain HTTP is published beyond the host or `ENS_COOKIE_SECURE` is not `true` |
| Native package: nginx listens on `NGINX_LISTEN` | New installs: `127.0.0.1:8080`. An upgraded `portal.env` without it keeps `8080` (every interface) |
| Single sign-on starts are limited per client address | `SSO_START_LIMIT_PER_MINUTE` (`30`, and `600` from v0.0.9; across replicas with the shared database, else per replica); in-memory sign-in requests are capped at `SSO_MAX_PENDING_REQUESTS` (`10000`), and expired ones are removed every minute (was five). A refused start lands on the login page with "wait a minute" |
| A startup WARN when the API is evidently behind a proxy (`X509_SOURCE=header`, or a loopback `SERVER_ADDRESS`) while `SERVER_FORWARD_HEADERS_STRATEGY` is `none` | The per-address limits then see only the proxy's address. Set it to `framework` (every package and the chart do) |
| A startup WARN when `SSO_OIDC_USERNAME_CLAIM` is a claim a provider lets be changed | Sign-in is unchanged. Move to `sub` or Entra ID's `oid` when you can ([how](single-sign-on.md#moving-to-an-immutable-claim)). New release-package `.env` files get `SSO_OIDC_USERNAME_CLAIM=sub`; the Helm chart's default stays `preferred_username`: set it yourself |
| Self-service matching by an attribute is refused while users can edit either attribute in self service, or when the user's value matches more than one entry | Check definitions that match by `mail` or similar: make the field not self-editable, or match on an attribute users can't change. The form linter warns about it. See [Directory definitions](admin-guide.md#directory-definitions) |
| Webhooks with the shared database: an entry change's deliveries are written in the transaction of its audit event, so both are stored or neither is; a change made while the database can't store them is answered `503` | Nothing to configure. See [Webhooks → Storage](webhooks.md#storage) |
| Webhook signing secrets can be stored encrypted: `DSP_SECRETS_KEY` or `DSP_SECRETS_KEY_FILE` | An upgrade gets no key: secrets stay as they are, with a WARN at every start. To encrypt them, add `DSP_SECRETS_KEY=$(openssl rand -base64 32)` to `.env` (native: a `secrets-key` file and `DSP_SECRETS_KEY_FILE`, as `install.sh` prints; Helm: `integrations.webhooks.secretsKey.value` or `.existingSecret`) and restart. New installations get one. Keep a copy apart from your backups; every replica needs the same; the API refuses to start with another key, or with none once secrets are encrypted. No re-keying. See [Webhooks → Encrypted signing secrets](webhooks.md#encrypted-signing-secrets) |
| Failed API tokens and single sign-on starts are counted in the database | With the shared database, Flyway V4 adds `dsp_rate_limit` at start, and `API_FAILED_TOKEN_LIMIT_PER_MINUTE` and `SSO_START_LIMIT_PER_MINUTE` then hold across replicas. Alone, nothing changes |
| Signing out at the identity provider: `SSO_OIDC_PROVIDER_LOGOUT`, `SSO_SAML_SINGLE_LOGOUT` | `false` on an upgrade (a startup WARN while OIDC is on and its setting is `false`); `true` in new `.env` files and new Helm releases. Before turning them on, register `https://<portal>/login?signed_out` as a post-logout redirect URI at the OIDC provider (not with Okta, which needs `id_token_hint`), or set the SAML signing key and certificate and give the identity provider the metadata again. See [Single sign-on → Signing out](single-sign-on.md#signing-out) |

### Upgrading to v0.0.9, v0.0.10 and v0.0.11

Nothing has to change before these upgrades. What is different afterwards, and what to check:

| Release | Change | What to do |
|---|---|---|
| v0.0.9 | The single sign-on start limit's default rises from 30 to 600 a minute per client address | A Docker package `.env` or a native `portal.env` written by v0.0.4 to v0.0.8 sets `SSO_START_LIMIT_PER_MINUTE=30` and keeps it. Behind a proxy in front of the web tier every user shares that limit: raise it to `600` (or remove the line), or set `WEB_TRUSTED_PROXIES`. Helm releases get the new default unless `api.extraEnv` sets the variable |
| v0.0.9 | New `WEB_TRUSTED_PROXIES` (Helm `web.trustedProxies`): the proxies in front of the web tier whose `X-Forwarded-For` names the client | Unset, as after an upgrade, nothing changes: everyone behind such a proxy still has its address, in the audit trail and for the per-address limits. While single sign-on is on, the API logs a WARN at startup. Set it when an ingress controller, a load balancer or a TLS proxy is in front ([how](configuration.md#behind-another-proxy)) |
| v0.0.9 | Sessions of accounts the directory disables or locks end within `GROUP_REFRESH_INTERVAL` (5 minutes), as those of deleted accounts did | Nothing. Expect `auth.session-expired` events with the reason `account-disabled` |
| v0.0.9 | A write through a definition is refused (400) when the entry would no longer match the definition's filter afterwards | Nothing. Integrations and imports that wrote such entries now get 400 where they got an entry they could not read back |
| v0.0.9 | An update that renames an entry makes its other changes first, then the rename. If the directory refuses the rename, the other changes stay and the answer has `"code": "partially_saved"` | Integrations that rename through `PATCH /api/v1/directories/{id}/entry` should handle that code ([API reference](api-reference.md#directories-and-entries)) |
| v0.0.9 | `WEBHOOKS_ENABLED=false` no longer queues the changes made meanwhile | If you turned sending off expecting the events later: they are not sent. Deliveries queued before stay queued |
| v0.0.9 | SAML: the identity provider's metadata is read again every `SSO_SAML_METADATA_REFRESH` (`1h`), and at once when a response's signature fails | Nothing. A signing certificate the provider rolls is picked up without a restart. When `SSO_SAML_METADATA` is a URL, the API must stay able to reach it (with a NetworkPolicy, `networkPolicy.egress.sso`); a failed read keeps the last good copy |
| v0.0.9 | With the shared database: background tasks of an instance that stops are marked failed about 30 seconds later, and Flyway V12 adds a column to `ens_license_installation` at the first start | Nothing. The migration is additive, so instances still on the previous release keep working during a rolling upgrade |
| v0.0.9 | Docker package: `./portal.sh backup` writes `<file>.part` first; `./portal.sh restore` checks the archive before stopping anything; `upgrade.sh` warns when `DSP_DATABASE_URL` is set, and asks before going on when run from a terminal | With the shared database, back it up (`pg_dump`) before `upgrade.sh`. A scripted upgrade (no terminal) is not asked |
| v0.0.9 | License: a clock set back more than a day no longer brings an expired license back; an upload that is not yet valid or already expired is refused while the installed license is in full effect (`NOT_IN_EFFECT`) | Nothing. Upload next term's license once its start date has come. An `ENS_LICENSE` that is refused this way is logged at WARN (`ENS_LICENSE (...) was refused: ...`). If the license store can't be read when the API starts (the database is still starting), `ENS_LICENSE` is now tried again 30 seconds later instead of only at the next restart |
| v0.0.10 | Directories over `ldaps://` or StartTLS trust the CAs in the trust store given to the JVM (Helm `ldap.truststore`; `-Djavax.net.ssl.trustStore` in `JAVA_TOOL_OPTIONS`). Before, only the JDK's own `cacerts` counted and such directories failed with `certificate_unknown` | If you set `LDAP_TLS_REJECT_UNAUTHORIZED=false` to get past that, set it back to `true` after the upgrade. The trust store must hold the directory's CA as a trusted certificate and be given with its password: see [LDAPS certificate errors](#ldaps-certificate-errors) |
| v0.0.11 | [Group access](admin-guide.md#group-access): owners manage owners, requests expire, open groups, memberships with an end date, adding members from a list, deciding several requests at once, member export, past requests | Nothing: every new setting is off or empty until an administrator sets it on a definition, and stored definitions and requests are read as before |
| v0.0.11 | End dates of memberships are kept in `DATA_DIR/group-membership-expiries.json`, or with the shared database in the new table `dsp_group_membership_expiry` (Flyway V13, created at the first start) | Nothing to configure. It is part of the data directory backup, or of the database's |
| v0.0.11 | Two [scheduled jobs](#scheduled-jobs) close expired requests (every 15 minutes) and remove members after their end date (hourly). They pause unless the license is in full effect | Renew the license in time where end dates matter |
| v0.0.11 | New audit actions `group.access-expired`, `group.membership-end-date` and `group.membership-expired`; new `details.via` values on `entry.update` (`group-owners`, `group-join`, `group-membership-expired`); the `group.membership_changed` webhook is also sent when a membership ends, with no user as its actor | Update SIEM searches and webhook receivers that list actions or expect an actor ([Webhooks](webhooks.md#events)) |
| v0.0.11 | Metrics: the home page, the overviews and the dashboards keep their results in the browser until **Refresh** (**Refresh counts** on the home page) | Tell users that the numbers are as old as the time shown with them. `METRICS_CACHE_SECONDS` now matters only after a browser reload |

To go back to the previous release, see
[Rolling back an upgrade](availability-and-sizing.md#rolling-back-an-upgrade).

!!! note
    A restart cancels running background tasks (analyses and slow metrics). Saved analysis
    results are kept. Without a shared database, users are signed out, because sessions are held
    in memory; with one, sessions are in the database and a restart signs nobody out.

## Troubleshooting runbook

| Symptom | Likely cause | Action |
|---|---|---|
| API exits at startup with `Invalid banner configuration: ...` | `BANNER_TEXT` longer than 200 characters, or an invalid `BANNER_FOREGROUND`/`BANNER_BACKGROUND` | Fix the value (hex, `rgb()`/`hsl()` or a CSS color name) |
| API exits with `Refusing to start: DIRECTORY_MODE=memory runs the embedded demo directory ...` (v0.0.4) | `DIRECTORY_MODE` is `memory` (its default) without `DEMO_MODE=true`, and the API listens beyond loopback | For a real directory set `DIRECTORY_MODE=ldap` and `LDAP_URL`; for a demo set `DEMO_MODE=true` (or `SERVER_ADDRESS=127.0.0.1` to keep it to this host) |
| Single sign-on lands back on the login page with "wait a minute" (`sso_error=busy`, v0.0.4) | More than `SSO_START_LIMIT_PER_MINUTE` sign-on starts from one client address in a minute (600 from v0.0.9; 30 before, and still 30 where an older `.env` or `portal.env` sets it), or `SSO_MAX_PENDING_REQUESTS` sign-in requests in flight | Wait a minute. If everybody gets it, every user has the same address: check `SERVER_FORWARD_HEADERS_STRATEGY=framework` on the API, and, behind an ingress controller, a load balancer or a TLS proxy in front of the web tier, set `WEB_TRUSTED_PROXIES` ([how](configuration.md#behind-another-proxy)); the startup log warns about both. Raise `SSO_START_LIMIT_PER_MINUTE` if an old settings file still says `30` |
| API exits with `Refusing to start: webhook signing secrets are stored encrypted with key ..., but DSP_SECRETS_KEY is ...` (v0.0.4) | `DSP_SECRETS_KEY` is not set, or is another key than the one the stored secrets were encrypted with (for example a replica without it, or a `.env` replaced without carrying the key over) | Set `DSP_SECRETS_KEY` (or `DSP_SECRETS_KEY_FILE`) to the key they were stored with, the same on every replica. See [Webhooks → Encrypted signing secrets](webhooks.md#encrypted-signing-secrets) |
| Signing out lands on the provider's error page about the redirect URI (v0.0.4) | `SSO_OIDC_PROVIDER_LOGOUT=true` and `https://<portal>/login?signed_out` is not registered at the provider, or the provider requires `id_token_hint` (Okta), which the portal does not send | Register the address, or set `SSO_OIDC_PROVIDER_LOGOUT=false` |
| API exits with `Could not read .../portal-config.json` | Corrupt or hand-edited config store | Restore from backup, or fix the JSON |
| API exits with `Invalid LDAP connection settings for <url>` | Malformed `LDAP_URL` | Use `ldap://host:port` or `ldaps://host:port` |
| API exits with `Cannot read trusted CA file ...` / `No certificates found in ...` | `X509_TRUSTED_CA_FILE` path or content wrong | Mount a PEM bundle and check the path |
| API exits with `Refusing to start: certificate sign-in through a proxy (X509_ENABLED=true, X509_SOURCE=header) is not secure as configured: ...` | Header mode without the proxy secret, with one under 32 characters or an unreadable secret file, or without a readable `X509_TRUSTED_CA_FILE` | Set what the message names (the packages and the chart generate the secret), or turn certificate sign-in off with `X509_ENABLED=false` |
| Web container exits with `dsp-entrypoint: the proxy secret may contain only ...`, `cannot read X509_PROXY_SECRET_FILE`, `X509_HEADER must be a header name` or `X509_EDGE_TERMINATION must be true or false` | Invalid web-tier X.509 setting | Use letters, digits and `. _ ~ + / = -` for the secret (for example `openssl rand -hex 32`), mount the file readable by uid 1001 or group 0, and fix the named setting |
| `helm install` / `helm upgrade` fails with `x509.enabled needs x509.trustedCa ...` | Certificate sign-in without the CA that issues user certificates | Set `x509.trustedCa`, or use `tls.enabled` with `ca.pem` |
| `helm install` / `helm upgrade` fails with `x509.enabled without tls.enabled (edge termination) trusts the certificate header ...` | Edge termination with nothing keeping other pods away from the web pods | Set `networkPolicy.enabled: true` and `networkPolicy.webIngressFrom` to the ingress controller's pods, or `x509.allowUnrestrictedWebIngress: true` if other controls ensure this |
| API exits with `Refusing to start: certificate revocation checking (ENS_X509_REVOCATION) needs X509_TRUSTED_CA_FILE ...` | Revocation checking without the CA bundle | Set `X509_TRUSTED_CA_FILE` (Helm: `x509.trustedCa`), or set `ENS_X509_REVOCATION=off` |
| API exits with `Cannot read ENS_CONSENT_BANNER_FILE ...` | The banner text file is missing or not readable by the API's user | Fix the path or permissions, or use `ENS_CONSENT_BANNER_TEXT` |
| Container or pod not ready; `./portal.sh up` says the API is not ready | `/readyz` is `DOWN`: the directory doesn't answer, the audit store can't be written, or the data disk has under 10 MB free | Read the API log for the failing part. Free disk space in `DATA_DIR`, fix the audit directory's permissions, or fix the directory connection |
| Login page shows **Directory unreachable** / `/api/info` has `connected:false` | LDAP server down, firewall, wrong URL, TLS failure, bad service account | Sign in as an administrator and read `error` from `/api/info`, or read the API log. Test with `ldapsearch` from the API host. Check `LDAP_BIND_DN` and the bind password (`LDAP_BIND_PASSWORD_FILE` or `LDAP_BIND_PASSWORD`) |
| LDAPS or StartTLS fails with `certificate_unknown` or `PKIX path building failed: ... unable to find valid certification path to requested target` | The directory's CA is not in the trust store the JVM uses, or the release is older than v0.0.10, which ignored the given trust store for directories | Upgrade to v0.0.10 or later, and give the JVM a trust store that holds the CA. See [LDAPS certificate errors](#ldaps-certificate-errors) |
| LDAPS or StartTLS fails with `the trustAnchors parameter must be non-empty` | Java opened the trust store but found no trusted certificate in it: a PKCS12 store read without its password, or one built with `openssl pkcs12 -export -nokeys` | Give the password, or rebuild the store with `keytool -importcert`. See [LDAPS certificate errors](#ldaps-certificate-errors) |
| LDAPS or StartTLS fails although the CA is trusted | The certificate doesn't name the host in `LDAP_URL` (the host name is checked too), or it has expired | Use the name the certificate was issued for in `LDAP_URL`, or have the certificate reissued. Use `LDAP_TLS_REJECT_UNAUTHORIZED=false` only for testing |
| "Invalid username or password" for valid users | `LDAP_USER_FILTER`/`LDAP_USER_SEARCH_BASE` finds zero or more than one entry, or the login name contains disallowed characters | Check the audit log: `Unknown user` versus `Wrong password`. Test the filter with `ldapsearch` |
| HTTP 429 "Too many failed login attempts" | `LOGIN_MAX_FAILURES` failures (8 by default) for the same client address and username within `LOGIN_LOCKOUT_WINDOW` (15 minutes) | Wait for the window to end, or, without a shared database, restart the API (the counters are in memory; with one they are in `dsp_login_failure`). Make sure `SERVER_FORWARD_HEADERS_STRATEGY` is set behind a proxy, and `WEB_TRUSTED_PROXIES` when another proxy is in front of the web tier, so the API doesn't see every user as the proxy's address |
| The audit log shows the same source address for every user | A proxy in front of the web tier (an ingress controller, a load balancer, a TLS proxy) that is not in `WEB_TRUSTED_PROXIES` | Set `WEB_TRUSTED_PROXIES` (Helm `web.trustedProxies`) to the address it connects from. See [Configuration → Behind another proxy](configuration.md#behind-another-proxy) |
| Web container exits with `dsp-entrypoint: WEB_TRUSTED_PROXIES must be IP addresses or CIDRs ...` or `WEB_TRUSTED_PROXIES: <value> is not an address or CIDR of a proxy` | A host name, or a catch-all such as `0.0.0.0/0`, in `WEB_TRUSTED_PROXIES` | List addresses or CIDRs only, separated by commas or spaces, and only those of proxies |
| Signed in but immediately signed out, or a 401 loop over HTTP | `ENS_COOKIE_SECURE=true` while users reach the portal over plain HTTP | Use HTTPS, or set `ENS_COOKIE_SECURE=false` |
| Users are signed out sooner than before | The idle timeout default is now `30m`; administrators may have a shorter `ENS_SESSION_ADMIN_IDLE_TIMEOUT`; `ENS_SESSION_ABSOLUTE_TIMEOUT` ends every session 12 hours after sign-in | Set the session values you want (see [Configuration](configuration.md#sessions-and-sign-in-lockout)). The audit log's `auth.session-expired` gives the reason |
| A user is signed out in the middle of a session, or within minutes of a single sign-on or certificate sign-in; `auth.session-expired` with the reason `account-disabled` | From v0.0.9: the directory marks the account disabled or locked (`nsAccountLock: true`, Active Directory's ACCOUNTDISABLE flag, or `pwdAccountLockedTime`), for example after too many wrong passwords elsewhere | Enable or unlock the account in the directory |
| 403 `consent_required` on sign-in | The consent banner is enabled and this browser session has not acknowledged it | Users click **I agree**. A script must `POST /api/auth/consent` first |
| Entry changes fail with 503 | The audit store is not writable, or its disk has under 1 MB free | Free disk space in `DATA_DIR` (or `AUDIT_DIR`), and check the directory's permissions |
| Saving an entry answers *The other changes were saved, but the entry was not renamed: ... The entry is now `<dn>`.* (`"code": "partially_saved"`) | From v0.0.9: the directory refused the rename (a changed naming value) after the entry's other changes were made | The other changes are saved and audited (`entry.update` with `details.partial`). Fix what the directory objects to (for example a name already taken) and change the naming value again |
| Creating or saving an entry answers 400 *... (it would not match the directory's filter `<filter>`)* | From v0.0.9: with these values the entry would fall outside the definition's filter, where nobody could read it through the definition | Correct the value the filter tests, or widen the definition's filter (*Admin → Directory definitions*) |
| 403 `Missing request verification header` | A client or proxy strips the `X-DSP-Request` header, or a script calls the API without it | Send `X-DSP-Request: 1` on POST/PUT/DELETE |
| 502 Bad Gateway from the web tier | API not running, or nginx can't resolve `API_URL` | `./portal.sh logs api`. Check `NGINX_LOCAL_RESOLVERS`; in Kubernetes, use a fully qualified `API_URL` |
| Web container exits with `no nameserver in /etc/resolv.conf; set NGINX_LOCAL_RESOLVERS` | Unusual DNS setup | Set `NGINX_LOCAL_RESOLVERS` |
| nginx can't read `server.key` (mTLS) | nginx runs as uid 1001 / group 0 | `chgrp 0 server.key && chmod 0640 server.key` (or `0644` for tests) |
| "Certificate not accepted" on the login page | The certificate is expired, not for client auth, has an untrusted chain, matches no user, matches more than one user, or isn't registered on the account | Read the reason in the API log (`Certificate sign-in rejected ...`) and the audit log. Adjust `X509_USER_FILTER`/`X509_MAPPING` |
| Certificate header ignored | Proxy address not in `X509_TRUSTED_PROXIES`, `SERVER_FORWARD_HEADERS_STRATEGY=native`, or the request lacks the proxy secret | Add the proxy CIDR; use `framework` or `none`; give the proxy the API's secret |
| Certificate sign-in fails with `certificate revoked` or `revocation unknown` in the audit log | The certificate is revoked, or the OCSP responder and CRLs could not be reached or checked while `ENS_X509_REVOCATION_FAIL=closed` | Check the API log for the fetch error. On an air-gapped network, use `ENS_X509_CRL_DIR` or `ENS_X509_OCSP_RESPONDER_URL`. With Helm and `networkPolicy.enabled`, check `x509.revocation.egress` |
| Certificate sign-in fails with *The client certificate was not forwarded by the portal's proxy* | The API and the web tier have different proxy secrets; the web tier was not restarted after the secret changed; in the native package, the HTTPS server's `/api` location lacks the `nginx-proxy-secret.conf` include; or the proxy's address is not in `X509_TRUSTED_PROXIES` | Give both the same secret and restart both (`./portal.sh up`; native: `install.sh`, then restart the API). The audit log's failed `auth.login` has `reasonCode` `untrusted_certificate_header` and the connecting `peerAddress` |
| 431 or 400 errors with large cookies or certificates | Headers over 32 KB | Raise `MAX_HTTP_REQUEST_HEADER_SIZE`, and `large_client_header_buffers` in nginx |
| Schema import fails with 413 | Body over nginx `client_max_body_size 5m` | Split the import, or raise the limit in your proxy |
| A chart or analysis is marked partial (truncated) | Directory larger than `METRICS_MAX_ENTRIES` | Narrow with a filter, or raise the limit (watch heap) |
| HTTP 429 "You already have N computations running" | `JOBS_MAX_ACTIVE_PER_USER` reached | Wait or cancel tasks from the bell menu, or raise the limit |
| A background task failed with *The server running it stopped before it finished* | The API instance running it was restarted or stopped (an upgrade, a crash, a pod eviction) | Run it again. From v0.0.9, with the shared database, such tasks are marked failed about 30 seconds after the instance stops and no longer count against `JOBS_MAX_ACTIVE_PER_USER` |
| A metrics page shows old numbers | From v0.0.11 the home page, the overviews and the dashboards keep their results until the user refreshes them; the time they were computed is shown with them | **Refresh** on the page (**Refresh counts** on the metrics home page) |
| A member is still in a group after their end date | The membership expiry job runs hourly and removes a member within the hour after their last day (UTC). It pauses unless the license is in full effect, and a member the directory won't let go (the last member of a group that requires one) is tried again only a day later | Check the license mode. Search the audit log for a failed `group.membership-expired` and the API log for `Could not remove ...`; remove the member by hand, or add another member first |
| Group access requests don't expire | **Requests expire after (days)** is empty on the definition (the default), or the license is not in full effect | Set it under *Admin → Directory definitions → Group access*; check the license |
| Webhook receivers got nothing for changes made while `WEBHOOKS_ENABLED=false` | From v0.0.9 changes made while sending is off are not queued | Expected. Have the receiver read the entries again, for example through the [integration API](api-reference.md#integration-api-apiv1) |
| `./portal.sh restore` stops with `<file> is not a complete backup (gzip-compressed tar); nothing was changed` | The archive is truncated or not a backup. Nothing was stopped or deleted | Use another backup |
| The license upload is refused with `"state": "NOT_IN_EFFECT"` | From v0.0.9: the uploaded license is not yet valid, or already expired, while the installed one is in full effect, which therefore stays in force | Upload it once its start date has come |
| The license message ends *(This server's clock is behind a time this installation has already seen, so the license is checked as of `<date>`.)* | From v0.0.9: the host's clock was set back more than a day | Correct the clock |
| Demo OpenLDAP is missing entries you expected | The demo LDIF is loaded only into an empty database | `./portal.sh down`, `docker volume rm directory-services-portal_ldap-data`, then `./portal.sh up` (destroys the demo directory's data) |
| Users lose sessions after a deploy | Without a shared database, sessions are in memory | Expected. Schedule restarts outside working hours, or give the API a shared database (`DSP_DATABASE_URL`), which keeps sessions across restarts |
| No `/actuator/prometheus` | `MANAGEMENT_SERVER_PORT` is not set, or you are asking the main port | Set it, and scrape that port |
| SELinux denies nginx → API (native) | `httpd_can_network_connect` is off | `setsebool -P httpd_can_network_connect 1` |

### LDAPS certificate errors

The error is in the API log, on *Admin → LDAP directories* (**Unreachable**, with the error), and
for administrators in `error` of `/api/info`. The directory server's own log usually shows the
same failed handshake as the TLS alert `certificate_unknown`.

| Error | Meaning | Fix |
|---|---|---|
| `certificate_unknown`, or `PKIX path building failed: ... unable to find valid certification path to requested target` | The API does not trust the CA that issued the directory server's certificate | Up to v0.0.9 the API ignored the trust store given to the JVM for directories and trusted the JDK's own `cacerts` only: upgrade to v0.0.10 or later. From v0.0.10: the CA is not in the trust store, or the JVM is not using the store you think (below) |
| `the trustAnchors parameter must be non-empty` | Java opened the trust store and found **no trusted certificate** in it | The store was read without its password (a PKCS12 file then opens, but empty): set `-Djavax.net.ssl.trustStorePassword`, or with Helm `ldap.truststore.password`. Or the store was built with `openssl pkcs12 -export -nokeys`, which Java does not read as trusted certificates: rebuild it with `keytool -importcert` |
| `keystore password was incorrect` | The password given is not the store's | A wrong password is a different error from a missing one. Give the store's password |

Check the trust store, with its password:

```bash
keytool -list -keystore truststore.p12 -storepass '<store-password>'
```

Every CA must appear as a `trustedCertEntry`. `Your keystore contains 0 entries` means the file
holds no trusted certificate (or `-storepass` was left out); rebuild it:

```bash
keytool -importcert -noprompt -alias corp-ca -file corp-ca.pem \
  -keystore truststore.p12 -storetype PKCS12 -storepass '<store-password>'
```

Then check what the JVM is given:

- **Docker and native packages**: `JAVA_TOOL_OPTIONS` in `.env` or `portal.env` with both
  `-Djavax.net.ssl.trustStore=<path>` and `-Djavax.net.ssl.trustStorePassword=<password>`; the API
  log's `Picked up JAVA_TOOL_OPTIONS: ...` line shows what the JVM got. In the Docker package the
  path is the one inside the API container (`/data/...`). The file must be readable by the API's
  user (uid 185 in the container, `dsp` in the native package).
- **Helm**: `ldap.truststore.existingSecret`, `.key` (the file's key in the Secret, default
  `truststore.p12`), `.type` (`PKCS12`) and `ldap.truststore.password`. See
  [Installation → Secrets](install.md#secrets).
- **A path that doesn't exist is not an error for Java.** It then falls back to the JDK's own
  `cacerts` without saying so, and the private CA is not trusted: check the path when the first
  error persists.
- One trust store serves every directory, and it replaces the JDK's `cacerts`: it must hold the
  CA of each directory, and of every other TLS server the API calls (Splunk HEC, the identity
  provider, webhook receivers).

See [Configuration → LDAPS with a private CA](configuration.md#ldaps-with-a-private-ca).
