I've implemented spec sections 1–8 and DSP-08 to DSP-13 plus DSP-17 on `claude/wave2-platform`, in 11 commits. Nothing is pushed and no PR is open. The final checks pass: 190 backend tests and 38 web tests (7 files), typecheck, a clean `npm audit`, the CI Helm job, and the Trivy gate on both images.

## Commits (on f70599e)
| Commit | Area |
|---|---|
| e1a2857 | Spec 1 / DSP-11: security headers and cookies |
| 4aeacc4 | Spec 2: consent banner (replaces your WIP commit b292123) |
| 595142e | Spec 3 / DSP-08: sessions, lockout, group refresh, audit check before writes |
| 7666469 | Spec 4 / DSP-10: probes, metrics, JSON logs, trimmed `/api/info` |
| 6ce4144 | Spec 8 / DSP-09: revocation and certificate mapping |
| b1b81b0 | DSP-17: bind password file, systemd and compose hardening |
| 513b497 | Spec 6 / DSP-13: FIPS note, trust-all warnings |
| cec8cd9 | Spec 5 / DSP-12: supply chain |
| 5a6aa0b, 95ccc0e | Small README/env.template wording follow-ups |

## What changed, per area
- **Headers and cookies.**
  - The API sends the baseline headers from `config/SecurityConfig.java`; the CSP is `Report-Only` when `ENS_CSP_REPORT_ONLY` is set.
  - nginx sends the same set in both templates and in the native site (via `scripts/package.sh`), and hides the API's copies so each header appears once.
  - HSTS: the plain-HTTP config sends it only when a proxy sends `X-Forwarded-Proto: https`; the mTLS config always sends it.
  - Shared settings live in `config/EnsProperties.java`; I tested that the `ENS_*` names also bind from real environment variables.
- **Consent banner.** `service/ConsentService.java`, `POST /api/auth/consent`, `web/src/components/ConsentNotice.tsx`. Password, certificate and certificate auto-login sign-ins are refused with 403 `consent_required` until acknowledged. Exempt: `/api/audit/**`, the health paths and Prometheus.
- **Sessions (DSP-08).**
  - `service/SessionService.java` (also a session listener) and `config/SessionControlFilter.java`. Expiry is audited as `auth.session-expired` with reason `idle`, `absolute`, `concurrent` or `account-removed`.
  - The web client marks its polling with `X-DSP-Background: 1`; otherwise an open tab would never time out.
  - Groups and admin status are re-read on request when due. If the directory is down, the session keeps its groups; if the account was deleted, the session ends.
  - `requireWritable()` now runs before every add, rename, modify and delete in `EntryService`, and "writable" includes at least 1 MB free.
- **Observability (DSP-10).**
  - Actuator liveness and readiness (readiness covers `ldap`, `auditLog` and `diskSpace`). Prometheus is only served when `MANAGEMENT_SERVER_PORT` is set.
  - Metrics: all the spec's `ens_*` names, including the three DSP ones.
  - Anonymous `/api/info` now only says whether the directory answers. Signed-in users also get the base DN; admins also get the LDAP URL and error text.
  - The web client used `/api/info` for the login page, the banners, the header's base DN and status, and the DN browser; I adjusted those.
  - Helm, the compose health check, the image `HEALTHCHECK` and CI now use readiness.
- **X.509 (DSP-09).**
  - Global JDK properties removed. `x509/RevocationChecker.java` fetches the OCSP response or CRLs itself and hands them to the JDK validator for that one check.
  - `RevocationFetcher.java` enforces the spec's limits: 5 s / 10 s timeouts, 1 MB OCSP, 32 MB CRL, at most 3 redirects, http(s) only, and no link-local, multicast or cloud-metadata addresses.
  - The issuer allowlist is enforced; `{edipi}` and `{uuid}` are added, and single-brace `{upn}` works too.
  - Tested against a local BouncyCastle OCSP responder and CRL server.
- **DSP-17.**
  - `LDAP_BIND_PASSWORD_FILE`: Helm mounts the Secret as a file; the native package uses `/etc/directory-services-portal/ldap-bind-password`, created empty by `install.sh`.
  - systemd unit hardening, exactly the five directives you listed, plus `SystemCallErrorNumber=EPERM`, `RestrictNamespaces`, `RestrictRealtime` and `AmbientCapabilities=`. I ran it under real systemd: the API became ready, created its files 0600, and logged no errors.
  - Compose hardening on api and web in both compose files; `license-init` gets the same in the dev file. With these images, both containers started healthy and sign-in worked through the web tier.

## New settings
| Name | Default | Meaning |
|---|---|---|
| `ENS_CSP_REPORT_ONLY` | `false` | Send CSP as Report-Only (API and web image) |
| `ENS_COOKIE_SECURE` | `false` | Always mark cookies Secure (`COOKIE_SECURE` is an alias) |
| `SERVER_FORWARD_HEADERS_STRATEGY` | `none` | Boot's own name; `FORWARD_HEADERS_STRATEGY` is an alias |
| `ENS_CONSENT_BANNER_ENABLED` / `_TITLE` / `_TEXT` / `_FILE` | `false` / DoD title / DoD text / — | Banner; the file wins over the text, and the API won't start if it can't read the file |
| `ENS_SESSION_IDLE_TIMEOUT` | `30m` | Idle timeout (`SESSION_TTL_MINUTES` is an alias) |
| `ENS_SESSION_ADMIN_IDLE_TIMEOUT` | same as idle | Admin idle timeout |
| `ENS_SESSION_ABSOLUTE_TIMEOUT` | `12h` | Maximum session length |
| `ENS_SESSION_MAX_PER_USER` | `0` | Sessions per user; over the limit the oldest ends |
| `GROUP_REFRESH_INTERVAL` | `5m` | How often groups and admin status are re-read (`0` = only at sign-in) |
| `LOGIN_MAX_FAILURES` / `LOGIN_LOCKOUT_WINDOW` | `8` / `15m` | Password lockout |
| `MANAGEMENT_SERVER_PORT` | unset | Separate port for health and Prometheus |
| `LOGGING_STRUCTURED_FORMAT_CONSOLE` | unset | `ecs` for JSON log lines |
| `ENS_X509_REVOCATION` | `off` | `off`, `ocsp`, `crl` or `ocsp-then-crl`; needs `X509_TRUSTED_CA_FILE` |
| `ENS_X509_OCSP_RESPONDER_URL` / `ENS_X509_CRL_DIR` | — | Override responder / directory of CRL files |
| `ENS_X509_REVOCATION_FAIL` / `_CACHE` | `closed` / `10m` | Undetermined status / answer cache |
| `ENS_X509_ALLOWED_ISSUERS` | — | Issuer DNs separated by `;` |
| `LDAP_BIND_PASSWORD_FILE` | — | File with the bind password; wins when set and not empty |
| `API_MEMORY_LIMIT` / `WEB_MEMORY_LIMIT` | `1536m` / `256m` | Docker package memory limits |
| `MANAGEMENT_SERVER_ADDRESS` | `127.0.0.1` (native package only) | Keeps the management port local |

**Helm values:** `server.cspReportOnly`, `server.consentBanner.*`, `server.session.*`, `server.groupRefreshInterval`, `server.login.*`, `metrics.enabled` (`true`), `metrics.port` (`9090`), `metrics.serviceMonitor.*`, `metrics.allowFrom`, `logFormat` (`json`), `x509.revocation.*` (including egress ports 80/443 for OCSP and CRL fetches), `x509.allowedIssuers`.

## Behaviour and upgrade notes
- **Idle timeout default:** 480 minutes → **30m**.
  - Docker upgrades keep their `.env`, which still says `SESSION_TTL_MINUTES=480`, so they keep 480.
  - Helm installs that never set `server.sessionTtlMinutes` change to 30m; anyone who set it keeps their value.
  - New installs get 30m.
- **Readiness:** a pod or container is not ready while LDAP is unreachable, the audit store can't be written, or the data disk has under 10 MB. `./portal.sh up` reports that.
- **Helm on upgrade:** API logs become JSON unless `logFormat: text`; probes move to port 9090; the bind password becomes a file mount.
- **Audit:** a directory write with a full or unwritable audit disk now gets 503 instead of going through unaudited.
- **Concurrent limit:** the evicted session just gets a plain 401 on its next request.
- **Revocation:**
  - `X509_CHECK_REVOCATION=true` becomes `ocsp-then-crl`, always failing closed, even if `ENS_X509_REVOCATION=off`.
  - X.509 sign-in with revocation off logs a STIG warning at startup.
  - `LDAP_TLS_REJECT_UNAUTHORIZED=false` now logs a FIPS/STIG warning too.

## Trivy and npm audit
- **Trivy 0.75.0** (same version as the other agents), with the CI flags:
  - The API jar first had 3 CRITICAL and 5 HIGH, in Tomcat 11.0.24 and Jackson 3.1.5. The pom now overrides to Tomcat 11.0.27 and Jackson 3.1.7.
  - After that, both images have **0 fixable HIGH/CRITICAL** and `.trivyignore` has **no entries**.
  - Unfixed OS findings, which the gate ignores: 18 HIGH in api, 30 HIGH in web.
  - I removed the images and ran `docker builder prune -f`.
- **npm audit:** 0 vulnerabilities with `--omit=dev`, and 0 overall. vitest 3 → 5; `npm audit fix` updated shell-quote (via concurrently) and source-map-js. CI now runs the `--audit-level=high` gate.
- **Other supply-chain pieces:**
  - Every action is pinned by SHA and every base image by digest, with readable tags.
  - Added Dependabot and a CodeQL workflow (java-kotlin and javascript-typescript).
  - Release tags now cosign-sign both images and the chart and attach the SBOM attestations; the README shows how to verify.
  - Added a `LICENSE` file; the images, chart, pom and package files are labelled `LicenseRef-ENS-Commercial`.

## Not done, or worth a decision
- The lockout counters are configurable but still in memory, so a restart clears them; I didn't persist them.
- The fetcher checks the target address before connecting, which leaves a small DNS-rebinding window.
- There is no setting to trust a local OCSP relay that signs with its own certificate; responses must be signed by the CA or its delegated responder.
- I kept the session cookie at `SameSite=Strict` rather than the spec's `Lax`, since it's stricter.
- Cross-app consistency to agree on:
  - `{uuid}` returns the bare UUID, without `urn:uuid:`.
  - `ENS_X509_ALLOWED_ISSUERS` is separated by `;`.
  - `ens_license_mode` uses `full`, `read_only` and `unlicensed`.
- The README has a placeholder heading for license verification under FIPS; the license library checks signatures with Ed25519.
- The demo OpenLDAP image is pinned by digest but isn't built or scanned in CI.
- The Docker package doesn't use the password file option.
- The earlier in-n-out-work message didn't apply; I touched nothing outside this worktree.

## Docs edits needed in `/home/user/ens-apps-documentation/docs/directory-services-portal/`
- **configuration.md**
  - "Server, sessions and proxy": replace `SESSION_TTL_MINUTES` / `COOKIE_SECURE` / `FORWARD_HEADERS_STRATEGY` with the `ENS_` and Boot names, keeping the old ones as aliases. Add the session, lockout and group-refresh rows and the 480 → 30m note.
  - Add "Notice and consent banner", "Security headers", and "Health, metrics and logs" sections (`MANAGEMENT_SERVER_PORT`, ECS logs).
  - "Directory connection": add `LDAP_BIND_PASSWORD_FILE`.
  - "X.509 client-certificate sign-in": the revocation table, allowed issuers, and the `{edipi}`, `{uuid}` and `{upn}` placeholders.
  - "Docker package deployment settings": add the memory limits.
- **operator-guide.md**
  - "Health checks": `/actuator/health/liveness|readiness`, `/livez`, `/readyz`; the readiness compose check; Helm probes on 9090. `/api/info` is no longer a readiness check.
  - "Monitoring": the Prometheus metric list and ServiceMonitor; drop "There is no metrics or actuator endpoint".
  - "Logs": the ECS option.
  - "Upgrades": the upgrade notes above.
- **security.md**
  - "Sessions and CSRF": timeouts, concurrent limit, group refresh; the new header set (nginx sends it and hides the API's copies).
  - "Authentication": consent banner and configurable lockout.
  - "X.509 client certificates": per-check revocation and fetch limits.
  - "Hardening checklist": systemd and compose hardening, FIPS note, trust-all warnings, signed images and chart with cosign verification.
- **api-reference.md**
  - "Public": trimmed `/api/info` and its `consent` field, plus the actuator paths.
  - "Authentication": `POST /api/auth/consent`, `consentRequired` in `/me` and in the logout answer, 403 `consent_required`, 401 `session_expired`, the `X-DSP-Background` header.
- **install.md**
  - Docker "Fill in .env": the new variables. Native package: the `ldap-bind-password` file.
  - Helm "Key values" and "Secrets": metrics, logFormat, session, consentBanner, revocation, and the bind password mounted as a file.
  - "Verification": `cosign verify`.
- **user-guide.md**: "Signing in" covers the consent banner and session-expiry messages; "Errors you may see" covers session expired and consent required.
- **admin-guide.md**: "Audit log" adds the `auth.session-expired` and `auth.consent-acknowledged` actions.