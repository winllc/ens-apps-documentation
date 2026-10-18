## Wave 2 ReadyRoom wrap-up

Everything you asked for checks out, and I found two more bugs, now fixed. The work is three commits on `claude/wave2-followup` in /home/user/wt/w2-rr, on top of f4f3b7f. Nothing is pushed and the worktree is clean. Your WIP commit 476de98 is split into the first two. Once these land, `claude/wip-do-not-merge-wave2-followup` can be deleted.

| Commit | What it does |
|---|---|
| **720b933** Baseline 5: name the base images in FROM lines, so Dependabot updates them | The production Dockerfile kept its digests only in `ARG BUILD_IMAGE=…@sha256` / `ARG RUNTIME_IMAGE=…`. Dependabot can't read those: its first run updated `test/Dockerfile` (#14) but never `/`. The base images are now two named stages, `FROM eclipse-temurin:21-jdk@sha256:… AS jdk` and `FROM …/ubi9/openjdk-21-runtime:latest@sha256:… AS runtime`, and the ARGs default to those stage names. `--build-arg BUILD_IMAGE=/RUNTIME_IMAGE=` still swaps in a mirror, and a small test build showed BuildKit then never pulls the replaced default. |
| **a8ff35d** Dev image builds with the Java 21 JDK again; Dependabot keeps to 21 | `test/Dockerfile` is back on the same `21-jdk` digest as production. `.github/dependabot.yml` now ignores eclipse-temurin major versions; new 21-jdk digests still come through. |
| **ec2f581** Baseline 4: the release bundle's health check follows MANAGEMENT_SERVER_PORT | `deploy/bundle/docker-compose.yml` has its own health check, which replaces the image's. It always called `:8181/actuator/health`, so setting `MANAGEMENT_SERVER_PORT` in `.env` (offered for Prometheus) broke it. It now uses `${MANAGEMENT_SERVER_PORT:-${SERVER_PORT:-8181}}`, like the image's HEALTHCHECK. I checked the expansion with and without the variable. |

## Checks

**Action pins: all correct.** There are 13 distinct action references across `ci.yml` and `codeql.yml`. Each is a full 40-character SHA, and `git ls-remote` shows each SHA is the commit of the tag in its comment. That includes the new bumps:

- checkout v7.0.1
- upload-artifact v7.0.2
- build-push-action v7.4.0
- setup-buildx-action v4.4.1
- setup-java v6.1.0
- setup-gradle v6.4.0
- setup-helm v5.0.1
- metadata-action v6.2.0
- login-action v4.6.0
- sbom-action v0.24.3
- cosign-installer v4.1.2
- codeql init and analyze v4.39.0

CI on f4f3b7f is green (build, helm, image), and so is CodeQL.

The steps that only run on a `v*` tag haven't run yet: chart push, cosign sign/attest, and the release upload. The first release tag will be their first real run.

**Temurin 24 in `test/` was wrong.** It does compile, because `targetCompatibility = 21` produces Java 21 class files. But:
- 24 is a non-LTS release that stopped getting updates when 25 came out.
- Gradle compiles without `--release`, so it compiles against Java 24's class library. Code using an API added in 22–24 would compile and then fail on the Java 21 runtime.
- It didn't match the production Dockerfile, CI's setup-java 21 or the openjdk-21 runtime.

**The changed Dockerfile builds.** I built it once from a scratch copy that only adds the sandbox proxy's CA and proxy settings; the FROM and ARG lines are unchanged.
- The first attempt failed on a Maven Central 429 rate limit (the sandbox, not the Dockerfile); the retry succeeded.
- The jar is built for Java 21 (class version 65). The runtime is RHEL 9.8 with OpenJDK 21.0.12.
- Started against Postgres 16, Flyway applied V1 to the empty database with no notice spam. Liveness was UP after 22 s and `/login` returned 200.
- Afterwards I removed the image, containers and network and ran `docker builder prune -f` (3.3 GB). 8.2 GB is free.

## Test results

- **Full build:** `./gradlew build --no-daemon --max-workers=2` succeeded: **796 tests, 0 failed, 1 skipped** (QueryPerformanceMeasurement, which is manual). The Postgres tests ran too, including FlywayMigrationTest (7), AuditPostgresTest and ConcurrentWritesTest.
- **Helm:** `helm lint --strict` and kubeconform (strict, Kubernetes 1.29) pass with all 6 example values files. The openshift and servicemonitor files each skip one object that has no schema.
- Gradle is stopped.

## Not done in my Wave 2 scope

- **LDAPS with a private CA:** there is no chart or bundle option to add a CA to the JVM trust store. It is only documented (via `JAVA_OPTS` trust store or a mount).
- **Header mode with ingress-nginx:** the proxy secret is added with a `configuration-snippet` annotation, which ingress-nginx disables by default since 1.9. Operators must allow snippets or use another proxy.
- **Header mode on OpenShift:** edge routes don't forward client certificates, so it needs a separate proxy. Servlet mode with a passthrough route works.
- **FIPS statement:** the license library's algorithm list is still a placeholder.
- **CSP and PDF export:** not checked in older browsers (html2pdf's core-js may be blocked). `ENS_CSP_REPORT_ONLY=true` is the escape hatch.
- **Packaged defaults left as they were:** `update-profile-url: https://google.com` and `banner-text: BANNER TEXT`.
- **Commit split:** consent banner and session controls share one commit (289a45b).
- **Outside ReadyRoom:** Dependabot moved product-site nginx from 1.28 (stable) to 1.31 (mainline). I didn't change it.

## Docs edits needed (/home/user/ens-apps-documentation/docs/readyroom/)

**install.md**
- **Prepare PostgreSQL:** rewrite it.
  - ReadyRoom creates and migrates its own schema with Flyway at startup. Hibernate only checks it (`ddl-auto: validate`).
  - There is no psql script and no migration Job; drop the `--single-transaction` warning.
  - The database account must own the schema. Alternatively, a separate migration account (`SPRING_FLYWAY_USER`/`SPRING_FLYWAY_PASSWORD`; Helm `database.migration.*`) owns it, and the app account gets row access only. The grants are in the README under "Database migrations".
  - The table list gains `flyway_schema_history`.
- **Upgrade (bundle):**
  - Remove "run scripts from `migrations/`"; the bundle no longer ships them.
  - Back up with `./backup.sh` instead of the pg_dump line.
  - First upgrade of an existing database: Flyway marks it as version 0, then runs V1. V1 adds missing indexes and constraints and the audit append-only trigger, merges users whose DNs differ only by case, and rebuilds invalid indexes. It can take minutes on a large database if script 001 never ran. Look for `Successfully applied 1 migration` in the log.
- **Upgrade (Helm):**
  - The migration Job and `migrations.enabled` are gone; the new pod migrates as it starts.
  - `metrics.enabled` is now true by default, so the probes move to port 9090.
- **Air-gapped clusters:** `postgres:16` is now used by the embedded database and the backup CronJob, not by a migration Job.
- **Fill in `.env`:**
  - The LDAP default is `SPRING_LDAP_URLS=ldaps://…:636`; `LDAP_HOST`/`LDAP_PORT` only for cleartext.
  - `APPLICATION_SUPERUSERDNS_0` has no packaged default; without it, startup logs a warning.
  - Setting `SPRING_MAIL_HOST` now requires `APPLICATION_NOTIFICATIONSENDEREMAIL`, or startup fails.
  - Mention the new optional blocks: consent, session, X.509, CSP, cookies, JSON logs, management port.
- **Helm values:**
  - `ldap.scheme` defaults to `ldaps`; the port follows the scheme (636 or 389).
  - `ldap.port: 389` with the default scheme fails the render; set `ldap.scheme: ldap` deliberately.
  - New groups: `csp`, `consentBanner`, `session`, `logFormat`, `metrics.*`, `x509.*`, `backup.*`, `database.migration.*`.
- **Connect the LDAP directory:** LDAPS is the default. Password sign-in over `ldap://` without StartTLS logs a startup warning. Explain trusting a private CA for LDAPS.
- **Certificates:** add a client-certificate section:
  - Mapping with `ENS_X509_USER_FILTER` and the placeholders `{upn}` `{edipi}` `{email}` `{uuid}` `{subject}` `{cn}`. Values are escaped and exactly one user must match. Empty keeps today's behaviour: the subject DN is the directory DN.
  - `ENS_X509_ALLOWED_ISSUERS`.
  - Revocation: `ENS_X509_REVOCATION` off/ocsp/crl/ocsp-then-crl, `_OCSP_RESPONDER_URL`, `_CRL_DIR`, `_REVOCATION_FAIL` closed/open, `_REVOCATION_CACHE` 10m. Revocation off with certificates in use logs a warning.
  - Header mode: `ENS_X509_SOURCE=header`, `_HEADER` (X-SSL-Client-Cert), `_TRUSTED_PROXIES`, `_PROXY_SECRET[_FILE]` (32+ characters, sent in X-ENS-Proxy-Secret), `_TRUSTED_CA_FILE`. ReadyRoom refuses to start without the secret and a CA; the Helm render fails likewise.
  - The ingress-nginx annotations and the OpenShift limitation above.
- **Configure email:**
  - SMTP certificates are no longer trusted blindly (no `*`); `SPRING_MAIL_PROPERTIES_MAIL_SMTP_SSL_TRUST` is opt-in.
  - STARTTLS is required; 30 s timeouts.
  - Without a mail host, alerts are recorded but not emailed (with a warning). Drop "points at a development mail host".
  - The health note: Helm probes run on the metrics port, and readiness never includes mail.
- **First start checklist / Verify:**
  - Replace "database script has run" with the Flyway log line.
  - Use `/actuator/health/liveness` and `/readiness`, on `MANAGEMENT_SERVER_PORT` when it is set.
  - Add "backups scheduled" and "consent banner enabled if required".

**configuration.md**
- **Database:** `ddl-auto` is `validate`. Add `spring.flyway.*` (baseline-on-migrate true, baseline-version 0) and `SPRING_FLYWAY_USER`/`PASSWORD`.
- **New sections, names and defaults exactly:**
  - `ENS_CSP_REPORT_ONLY` false.
  - `ENS_CONSENT_BANNER_ENABLED` false, plus `_TITLE`, `_TEXT`, `_FILE` (empty means the DoD standard notice).
  - `ENS_SESSION_IDLE_TIMEOUT` 30m, `ENS_SESSION_ADMIN_IDLE_TIMEOUT` (empty means same as idle), `ENS_SESSION_ABSOLUTE_TIMEOUT` 12h, `ENS_SESSION_MAX_PER_USER` 0 (unlimited). Each must be at least 1 minute.
  - `ENS_COOKIE_SECURE` (replaces `SESSION_COOKIE_SECURE`, which still works). `SERVER_SERVLET_SESSION_TIMEOUT` no longer governs signed-in sessions.
  - The `ENS_X509_*` table from the Certificates item.
- **LDAP:** packaged URL is `ldap://${LDAP_HOST:openldap}:${LDAP_PORT:389}`; the bundle default is ldaps.
- **Email:** no packaged host, user or password; STARTTLS required; 30 s connect/read/write timeouts; `ssl.trust` unset; `notification-sender-email` defaults to empty and must be a valid address.
- **Packaged defaults:** `super-user-dns` is empty (the demo profile and dev compose carry their own). `check-out-after-minutes` is 240, not 2.
- **Validation:** bad settings now stop startup with a message naming the variable (for example `APPLICATION_USERBASEDN`, `APPLICATION_BASE_URL`, `ENS_SESSION_*`, `ENS_AUDIT_SYSLOG_PROTOCOL`).
- **Logging and monitoring:**
  - The application port exposes only `/actuator/health`.
  - `MANAGEMENT_SERVER_PORT` (plain HTTP) adds `/actuator/prometheus` and the probes.
  - `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` gives JSON logs (Helm `logFormat: json`).
  - Health details are shown to ADMIN only.
- **Example production `.env`:** update to match.

**operator-guide.md**
- **Health and monitoring:**
  - Liveness is the app only; readiness adds `db`, `ldap` and `auditStore`, never mail.
  - The metrics are `ens_readyroom_checkins_total{action}`, `ens_readyroom_people_in`, `ens_readyroom_alerts_sent_total`, `ens_readyroom_logoffs_refused_total{reason}`, `ens_auth_attempts_total{method,outcome}`, `ens_audit_events_total{category,outcome}`, `ens_audit_forward_failures_total{output}`, `ens_license_mode{mode}` and `ens_license_days_remaining`, all tagged `application=readyroom`.
  - Helm `metrics.serviceMonitor` and `metrics.allowFrom`.
  - Remove the "info/metrics/env open without authentication" text.
- **Logs:** JSON (ECS) option; the new startup warnings (LDAP cleartext, no mail server, no administrator, revocation off, CSP report-only).
- **Backups:**
  - Bundle: `./backup.sh` (database dump plus license volume, `BACKUP_RETENTION_DAYS` default 14) and `./restore.sh <db.dump> [license.tar.gz]`.
  - Helm: `backup.enabled/schedule/timeZone/retentionDays/persistence` (a pg_dump CronJob to a PVC). The restore steps are in the chart README.
  - Restoring into the same or a newer release migrates forward.
- **Schema changes and migrations:** rewrite for Flyway. Versioned `V<n>__*.sql` in the app; the developer notes are in `db/README.md`.
- **Upgrades / rollback:**
  - Step 4 (run scripts) goes away.
  - Rolling back past a release that added migrations may need the database backup.

**security.md**
- **Authentication diagram:** replace "/actuator/** open" with "health only (Prometheus on the management port)". Add the consent banner step.
- **Client certificates:** mapping, allowlist, revocation, header mode, and the refusal reasons audited on `auth.login` ("certificate revoked", "revocation unknown", "issuer not allowed", and others).
- **Unauthenticated endpoints:** update the table; `/consent` is open.
- **Other protections:**
  - It still says CSRF is disabled; that's already wrong, CSRF is on.
  - Add the security headers and nonce-based CSP, cookie flags, session limits, and the SMTP trust change.
- **New "FIPS 140-3" section** pointing to the README statement.
- **Hardening checklist:** revocation on, consent banner, session limits, `ENS_COOKIE_SECURE`, metrics port kept off the ingress.

**audit-logging.md (What is recorded):** new actions `auth.consent-acknowledged` and `auth.session-expired`, and the certificate refusal reasons on `auth.login` failures.

**releases.md:** add cosign keyless verification of the image, its SPDX attestation, and the chart. The commands are in the README's supply-chain section.

**troubleshooting.md:**
- Line 52 (packaged value 2) is wrong now.
- Line 94: script 001 is replaced by V1, which merges case duplicates automatically.
- Line 100: indexes come from V1.
- Add: "Flyway fails at startup", "a setting fails validation", "a certificate is refused (revoked / unknown)", "header-mode refusals (untrusted address, wrong secret)".

**index.md:** the components/stack rows: no `migrations/` folder and no Job; Flyway plus `ddl-auto: validate`; Spring Boot 4.1.1.