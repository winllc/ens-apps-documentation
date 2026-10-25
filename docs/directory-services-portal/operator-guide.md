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
    ./portal.sh restore <file>     # replace the portal data with a backup
    ./portal.sh version
    ```
    `up` waits up to about three minutes for the API container's health check to become
    `healthy`, and fails with a pointer to `./portal.sh logs api` if it does not.

>>>

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
  answers (`connected`); the LDAP URL and the error text are shown to administrators only.

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
| `audit/` | The audit store: `audit-YYYY-MM.jsonl` (hash-chained `ens-audit/1` events), `audit-state.json` (output cursors), `audit-api-tokens.json` (pull API token hashes) | Appended and synced to disk in the request that caused the event. Only the retention job (`ENS_AUDIT_RETENTION_DAYS`) removes events |
| `analysis-results/<id>.json` | Latest result of each saved analysis | Re-creatable by running the analysis again |
| License directory (`/var/lib/ens/directory-portal`) | The license file and the installation id | Not in `DATA_DIR`. Losing it gives the installation a new id; bound licenses must then be re-bound |

Directory data stays on your LDAP server and must be backed up there.

=== "Docker package"

    ```bash
    ./portal.sh backup                              # portal-backup-<YYYYmmdd-HHMMSS>.tar.gz
    ./portal.sh backup /backups/dsp-$(date +%F).tar.gz
    ./portal.sh restore /backups/dsp-2026-10-01.tar.gz   # asks for confirmation, stops the API meanwhile
    ```
    The backup runs `tar` over `/data` in a one-off API container while the portal keeps running,
    so it includes the configuration, the audit store and analysis results. The data volume is
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

!!! tip "Audit retention"
    By default the audit store keeps every event. Set `ENS_AUDIT_RETENTION_DAYS` to have a daily
    job remove older events once every enabled output (Splunk HEC, syslog) has delivered them; it
    never removes the newest event and records `audit.pruned`. The audit page searches all
    retained history. Treat the audit files as sensitive: they hold changed values and the names
    people tried to sign in with.

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
    `directory-services-portal`. See
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
| LDAPS fails with a certificate error | Private CA not in the JVM trust store, or host name mismatch | Add a trust store (see [LDAPS with a private CA](configuration.md#ldaps-with-a-private-ca); with Helm, `ldap.truststore`). Use `LDAP_TLS_REJECT_UNAUTHORIZED=false` only for testing |
| "Invalid username or password" for valid users | `LDAP_USER_FILTER`/`LDAP_USER_SEARCH_BASE` finds zero or more than one entry, or the login name contains disallowed characters | Check the audit log: `Unknown user` versus `Wrong password`. Test the filter with `ldapsearch` |
| HTTP 429 "Too many failed login attempts" | `LOGIN_MAX_FAILURES` failures (8 by default) for the same client address and username within `LOGIN_LOCKOUT_WINDOW` (15 minutes) | Wait for the window to end, or, without a shared database, restart the API (the counters are in memory; with one they are in `dsp_login_failure`). Make sure `SERVER_FORWARD_HEADERS_STRATEGY` is set behind a proxy, so the API doesn't see every user as the proxy's address |
| Signed in but immediately signed out, or a 401 loop over HTTP | `ENS_COOKIE_SECURE=true` while users reach the portal over plain HTTP | Use HTTPS, or set `ENS_COOKIE_SECURE=false` |
| Users are signed out sooner than before | The idle timeout default is now `30m`; administrators may have a shorter `ENS_SESSION_ADMIN_IDLE_TIMEOUT`; `ENS_SESSION_ABSOLUTE_TIMEOUT` ends every session 12 hours after sign-in | Set the session values you want (see [Configuration](configuration.md#sessions-and-sign-in-lockout)). The audit log's `auth.session-expired` gives the reason |
| 403 `consent_required` on sign-in | The consent banner is enabled and this browser session has not acknowledged it | Users click **I agree**. A script must `POST /api/auth/consent` first |
| Entry changes fail with 503 | The audit store is not writable, or its disk has under 1 MB free | Free disk space in `DATA_DIR` (or `AUDIT_DIR`), and check the directory's permissions |
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
| Demo OpenLDAP is missing entries you expected | The demo LDIF is loaded only into an empty database | `./portal.sh down`, `docker volume rm directory-services-portal_ldap-data`, then `./portal.sh up` (destroys the demo directory's data) |
| Users lose sessions after a deploy | Without a shared database, sessions are in memory | Expected. Schedule restarts outside working hours, or give the API a shared database (`DSP_DATABASE_URL`), which keeps sessions across restarts |
| No `/actuator/prometheus` | `MANAGEMENT_SERVER_PORT` is not set, or you are asking the main port | Set it, and scrape that port |
| SELinux denies nginx → API (native) | `httpd_can_network_connect` is off | `setsebool -P httpd_can_network_connect 1` |
