# Operator guide

This guide covers running the portal day to day: starting and stopping it, checking health,
reading logs, backing up its data, monitoring, upgrading, and troubleshooting.

## What runs where

| Process | Docker package | Native package | Helm chart | Listens on |
|---|---|---|---|---|
| Web tier (nginx + static client) | `web` container (uid 1001) | Host nginx, site `directory-services-portal.conf` | Deployment `<release>-directory-services-portal-web` | 8080 (HTTP); 8443 with HTTPS |
| API (Spring Boot) | `api` container (uid 185) | systemd unit `directory-services-portal` as user `dsp` | Deployment `<release>-directory-services-portal-api` (one replica) | 3001 (never published by the compose files or the chart; `127.0.0.1` in the native package) |
| Demo OpenLDAP (optional) | `openldap` container (`install.sh --demo-openldap`) | n/a | n/a | 389 (not published) |

!!! warning "Run exactly one API instance"
    Sessions, login throttling state and background tasks are held in the API's memory. Running
    several API replicas behind a load balancer breaks sign-in and task tracking. If you need to
    scale out, sessions would first need an external store (for example Spring Session with
    Redis or JDBC), which the portal does not include.

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

| Endpoint | Auth | Returns | Use for |
|---|---|---|---|
| `GET /api/health` | none | `{"ok":true}` while the API process serves requests. Does **not** contact LDAP | Liveness / container health check |
| `GET /api/info` | none | `mode`, `ldapUrl` (ldap mode), `baseDn`, `connected`, `error`, `version`, sign-in options, `loginHint`, `banner` | Readiness: pings the directory, and `connected:false` plus `error` reveal LDAP problems |

The Docker package's `docker-compose.yml` defines this API health check:

```yaml
healthcheck:
  test: ["CMD-SHELL", "curl -sf http://localhost:3001/api/health >/dev/null || exit 1"]
  interval: 15s
  timeout: 5s
  retries: 5
  start_period: 40s
```

The Helm chart probes `/api/health` on the API (startup, liveness and readiness) and `/` on the
web tier. Tune them with `api.startupProbe`, `api.livenessProbe`, `api.readinessProbe` and the
matching `web.*` values.

Example readiness probe through the web tier:

```bash
curl -s https://portal.example.com/api/info | jq '{connected, error, mode, version}'
```

## Logs

| Source | Where |
|---|---|
| API application log | Container stdout (`./portal.sh logs api`; with Helm, `kubectl logs deploy/<release>-directory-services-portal-api -c api`) or `journalctl -u directory-services-portal` |
| Audit events | `audit` logger in the API log (one line per event), **and** `audit-YYYY-MM.jsonl` files |
| nginx access log | Web container stdout (`$remote_addr ... "$request" $status ...`) |
| nginx error log | Web container stderr |
| Entrypoint | `dsp-entrypoint: rendered ...` lines on web container start |

Useful API log messages:

| Message | Meaning |
|---|---|
| `Loaded portal configuration from .../portal-config.json` | Config store read at startup |
| `Seeded demo forms, directories, permissions, dashboards and analyses` | Demo config was written into an empty store |
| `Certificate sign-in: <subject> (serial <n>) -> <dn>` | Successful X.509 sign-in |
| `Certificate sign-in rejected for <subject> (issuer <issuer>): <reason>` | Rejected certificate |
| `Ignoring X-SSL-Client-Cert header from untrusted address <ip>` | Certificate header from a peer not in `X509_TRUSTED_PROXIES` |
| `Group lookup failed: ...` | `LDAP_GROUP_FILTER` search failed; the user signs in with `memberOf` groups only |

Forward the `audit` logger to your SIEM with your usual log shipper.

## Data and backups

All portal state outside LDAP lives in `DATA_DIR`:

| Path | Content | Notes |
|---|---|---|
| `portal-config.json` | Forms, directory definitions, grants, custom schema, schema cache, dashboards, analyses | Written atomically on every admin change |
| `audit/audit-YYYY-MM.jsonl` | Audit trail, one JSON object per line | Append-only and synced to disk before the request returns. The portal never rewrites or deletes these files |
| `analysis-results/<id>.json` | Latest result of each saved analysis | Re-creatable by running the analysis again |

Directory data stays on your LDAP server and must be backed up there.

=== "Docker package"

    ```bash
    ./portal.sh backup                              # portal-backup-<YYYYmmdd-HHMMSS>.tar.gz
    ./portal.sh backup /backups/dsp-$(date +%F).tar.gz
    ./portal.sh restore /backups/dsp-2026-10-01.tar.gz   # asks for confirmation, stops the API meanwhile
    ```
    The backup runs `tar` over `/data` in a one-off API container, so it includes the
    configuration, the audit log and analysis results. The data volume is
    `directory-services-portal_portal-data`.

=== "Native"

    ```bash
    sudo tar -C /var/lib/directory-services-portal -czf dsp-backup-$(date +%F).tar.gz .
    ```
    Restore: stop the service, extract into `/var/lib/directory-services-portal`, restore
    ownership to `dsp:dsp`, then start the service.

=== "Helm"

    The data is on the API's data PVC (`<release>-directory-services-portal-data`, mounted at
    `/data`). Back it up with your volume snapshot tool, or copy it out of the running pod:

    ```bash
    kubectl exec -n dsp deploy/dsp-directory-services-portal-api -c api -- \
      tar -cf - -C /data . | gzip > dsp-backup-$(date +%F).tar.gz
    ```
    Also keep your values file, the Secrets it names, and the license PVC
    (`<release>-directory-services-portal-license`), which holds the installation id.

!!! tip "Audit retention"
    The audit files grow without limit. Archiving and deleting old monthly files is up to you. The
    audit page searches only the newest `AUDIT_MEMORY_EVENTS` events (reloaded from the files at
    startup). Treat the files as sensitive: they hold changed values and the names people tried
    to sign in with.

## Monitoring

There is no metrics or actuator endpoint. Monitor the portal with:

- **Liveness**: `GET /api/health` returns HTTP 200.
- **Directory connectivity**: `GET /api/info` returns `connected: true`.
- **Container health**: `./portal.sh status`, or `kubectl get pods` with Helm.
- **Logs**: alert on `ERROR` lines in the API log, on repeated `auth.login` failures in the
  `audit` logger, and on `Too many failed attempts` denials.
- **Disk usage** of `DATA_DIR` (audit files).
- **Memory**: the container image runs Java with `-XX:MaxRAMPercentage=75.0`. Large metric scans
  and analyses (up to `METRICS_MAX_ENTRIES` entries per source) need heap. Raise the container
  memory or set `JAVA_TOOL_OPTIONS=-Xmx...`.

## Upgrades

=== "Docker package"

    1. `./portal.sh backup`
    2. Unpack the new package next to the old one.
    3. In the new directory: `./upgrade.sh ../directory-services-portal-<old-version>`.

    `upgrade.sh` copies `.env`, `license/` and `certs/` across, loads the new images, backs up the
    data to `backups/` and restarts. The data and license volumes are reused, because the compose
    project name stays `directory-services-portal`. See
    [Installation → Upgrade](install.md#upgrade).

=== "Native"

    Run the new package's `sudo ./install.sh`, then `systemctl restart directory-services-portal`.
    `portal.env` and `/var/lib/directory-services-portal` are kept. The web client directory is
    replaced.

=== "Helm"

    Back up the data volume, then:

    ```bash
    helm upgrade dsp oci://ghcr.io/winllc/charts/directory-services-portal --version X.Y.Z \
      -n dsp -f my-values.yaml
    ```
    The old API pod stops before the new one starts. The PVCs are kept.

Each release ships images rebuilt on the latest Red Hat UBI security updates, so stay on a
current release.

Older `portal-config.json` files without `dashboards` or `analyses` are upgraded automatically on
load.

!!! note
    A restart cancels running background tasks (analyses and slow metrics). Saved analysis
    results on disk are kept. Users are signed out, because sessions are held in memory.

## Troubleshooting runbook

| Symptom | Likely cause | Action |
|---|---|---|
| API exits at startup with `Invalid banner configuration: ...` | `BANNER_TEXT` longer than 200 characters, or an invalid `BANNER_FOREGROUND`/`BANNER_BACKGROUND` | Fix the value (hex, `rgb()`/`hsl()` or a CSS color name) |
| API exits with `Could not read .../portal-config.json` | Corrupt or hand-edited config store | Restore from backup, or fix the JSON |
| API exits with `Invalid LDAP connection settings for <url>` | Malformed `LDAP_URL` | Use `ldap://host:port` or `ldaps://host:port` |
| API exits with `Cannot read trusted CA file ...` / `No certificates found in ...` | `X509_TRUSTED_CA_FILE` path or content wrong | Mount a PEM bundle and check the path |
| Login page shows **Directory unreachable** / `/api/info` has `connected:false` | LDAP server down, firewall, wrong URL, TLS failure, bad service account | Read `error` from `/api/info`. Test with `ldapsearch` from the API host. Check `LDAP_BIND_DN`/`LDAP_BIND_PASSWORD` |
| LDAPS fails with a certificate error | Private CA not in the JVM trust store, or host name mismatch | Add a trust store (see [LDAPS with a private CA](configuration.md#ldaps-with-a-private-ca); with Helm, `ldap.truststore`). Use `LDAP_TLS_REJECT_UNAUTHORIZED=false` only for testing |
| "Invalid username or password" for valid users | `LDAP_USER_FILTER`/`LDAP_USER_SEARCH_BASE` finds zero or more than one entry, or the login name contains disallowed characters | Check the audit log: `Unknown user` versus `Wrong password`. Test the filter with `ldapsearch` |
| HTTP 429 "Too many failed login attempts" | 8 failures for the same client address and username within 15 minutes | Wait 15 minutes. Make sure `FORWARD_HEADERS_STRATEGY` is set behind a proxy, so the API doesn't see every user as the proxy's address |
| Signed in but immediately signed out, or a 401 loop over HTTP | `COOKIE_SECURE=true` while users reach the portal over plain HTTP | Use HTTPS, or set `COOKIE_SECURE=false` |
| 403 `Missing request verification header` | A client or proxy strips the `X-DSP-Request` header, or a script calls the API without it | Send `X-DSP-Request: 1` on POST/PUT/DELETE |
| 502 Bad Gateway from the web tier | API not running, or nginx can't resolve `API_URL` | `./portal.sh logs api`. Check `NGINX_LOCAL_RESOLVERS`; in Kubernetes, use a fully qualified `API_URL` |
| Web container exits with `no nameserver in /etc/resolv.conf; set NGINX_LOCAL_RESOLVERS` | Unusual DNS setup | Set `NGINX_LOCAL_RESOLVERS` |
| nginx can't read `server.key` (mTLS) | nginx runs as uid 1001 / group 0 | `chgrp 0 server.key && chmod 0640 server.key` (or `0644` for tests) |
| "Certificate not accepted" on the login page | The certificate is expired, not for client auth, has an untrusted chain, matches no user, matches more than one user, or isn't registered on the account | Read the reason in the API log (`Certificate sign-in rejected ...`) and the audit log. Adjust `X509_USER_FILTER`/`X509_MAPPING` |
| Certificate header ignored | Proxy address not in `X509_TRUSTED_PROXIES`, or `FORWARD_HEADERS_STRATEGY=native` | Add the proxy CIDR; use `framework` or `none` |
| 431 or 400 errors with large cookies or certificates | Headers over 32 KB | Raise `MAX_HTTP_REQUEST_HEADER_SIZE`, and `large_client_header_buffers` in nginx |
| Schema import fails with 413 | Body over nginx `client_max_body_size 5m` | Split the import, or raise the limit in your proxy |
| A chart or analysis is marked partial (truncated) | Directory larger than `METRICS_MAX_ENTRIES` | Narrow with a filter, or raise the limit (watch heap) |
| HTTP 429 "You already have N computations running" | `JOBS_MAX_ACTIVE_PER_USER` reached | Wait or cancel tasks from the bell menu, or raise the limit |
| Demo OpenLDAP is missing entries you expected | The demo LDIF is loaded only into an empty database | `./portal.sh down`, `docker volume rm directory-services-portal_ldap-data`, then `./portal.sh up` (destroys the demo directory's data) |
| Users lose sessions after a deploy | Sessions are in memory | Expected. Schedule restarts outside working hours |
| SELinux denies nginx → API (native) | `httpd_can_network_connect` is off | `setsebool -P httpd_can_network_connect 1` |
