## CertAlert Wave 2 follow-up: main is fixed locally, nothing pushed

Branch `claude/wave2-followup` in `/home/user/wt/w2-certalert` has 8 commits on top of origin/main `411c244`. With them, both CI test jobs pass locally, Helm passes, and the image job's start check and Trivy scan pass.

### Root causes

1. **build: the test JVM ran out of heap** (my Wave 2 change). Every test class with its own `@DynamicPropertySource` directory or its own properties gets a Spring context that no other class uses. Spring keeps up to 32 of these, each with Hibernate, a connection pool and threads. Wave 2 added enough of them to fill Gradle's 512 MB test heap.
   - Run 39063361949 (Gradle 8): the worker died part-way through `ProjectTest`, after 448 of 655 tests.
   - Run 39071370474 (Gradle 9): "Java heap space", 21 failures.
   - I reproduced it locally. A heap histogram at the point it gave up held 32 HikariPools and 32 SessionFactoryImpls.
2. **test-postgres: "too many clients already"** (same cause). 32 pools of 10 connections each is more than PostgreSQL's default `max_connections=100`. 232 of 645 tests failed.
3. **FetchPolicyTest and RevocationFetcherTest failed in both jobs** (my Wave 2 change). Spring Security's `IpAddressMatcher` compares leading bits without checking whether an address is IPv4 or IPv6.
   - On GitHub runners `localhost` also resolves to `::1`, which the matcher put inside `0.0.0.0/8` (a never-fetch range). So a CRL host named `localhost` was refused.
   - It is also a security hole: `a00::1` matched an allowed `10.0.0.0/8`, and an IPv6 peer could match `ENS_X509_TRUSTED_PROXIES=10.0.0.0/8`.
4. **H2 2.4.240 bug, exposed by fix 1.** A `CHECK (type IN (...))` constraint stays tied to the connection that created it. Once that connection closes, every insert into `server_attribute_definition` fails.
   - In tests this happens when the context that ran Flyway is closed.
   - It is also a real bug on the default H2 database: about 30 minutes after the first start, when the pool retires Flyway's connection, until a restart.
   - H2 2.5.252 no longer has the bug, but Spring Boot 4.1.1 still manages 2.4.240.
5. **image job would have failed** (my Wave 2 change; it never ran on main because it waits for build).
   - `.trivyignore` was never committed, and Trivy 0.75.0 stops with "ignore file not found".
   - The image also had fixable findings: Tomcat 11.0.24 (3 CRITICAL), Jackson 2.21.5 and 3.1.5 (HIGH), and pcre2 in the base image (HIGH).
6. **DataTables 3 broke every table** (#12). DataTables 3 no longer uses jQuery for its requests, so the `$.ajaxSetup` CSRF header never reached them. Every POST to `/api/v1/datatables/*` got 403 and the page redirected to `/login`. CI can't see this because the server was right to refuse.
7. **Gradle 9 (#13): nothing to fix in the build scripts.** `--warning-mode all` shows no Gradle deprecations, and `junit-platform-launcher` (which Gradle 9 needs) was already declared. The only warnings are the existing javac `[removal]` ones for Spring Security's `DelegatingAuthenticationEntryPoint` in `SecurityConfig`. I added the wrapper's distribution checksum, which the Dependabot bump lacked.
8. **jQuery 4 (#11) and Tabler 1.6.1: no breakage.** None of the jQuery APIs removed in 4.0 are used, and the asset paths are unchanged. I checked this in a browser (below).

### Commits (none pushed, no PR)

1. `371ee81` Match address ranges within their own family. Adds `net/AddressRange` (literals only, no DNS lookups), used by `FetchPolicy` and `ForwardedCertificates`. Tests: `AddressRangeTest`, `TrustedProxyTest`, and new `FetchPolicyTest` cases that fail without the fix on any host.
2. `2c254f9` Check the Gradle distribution against its published checksum (`distributionSha256Sum` for 9.8.1).
3. `a18014f` README: finish the FIPS 140-3 statement, without the license check.
   - It says the license check is not supported in FIPS mode yet: licenses are Ed25519, untested on FIPS-mode providers, and the ens-license 1.1.0 ECDSA P-256 co-signature isn't adopted.
   - It adds two not-for-FIPS items: PostgreSQL without `sslmode=verify-full`, and a proxy that terminates TLS (header mode, or an ingress/edge Route).
   - The "FIPS test results for the license library" heading stays as a marked placeholder.
4. `8919b47` Keep H2 checking attribute types once the migrating connection closes. Adds migration `V20__check_attribute_type_without_an_in_list.sql` (same rule, written as a `CASE`) and `H2SchemaTest`, which fails without V20.
5. `ed43a00` Keep the test run inside its heap and PostgreSQL's connection limit.
   - `src/test/resources/spring.properties` caps the context cache at 8.
   - The test profile's Hikari pool is 5 connections, minimum idle 1.
   - The test JVM heap is 1g.
6. `db5c34a` Send the CSRF token with DataTables 3's own table requests (`static/js/directory-table.js`).
7. `f2063db` Take Tomcat 11.0.27, Jackson 3.1.7 and Jackson 2.21.8 ahead of Spring Boot 4.1.1, which is the newest Boot. These are overrides in `build.gradle.kts`, to remove once Boot manages these versions.
8. `b3dc5ac` Add the `.trivyignore` the image scan reads. It holds only the 3 pcre2 CVEs (fix 10.40-7.el9_8 not yet in the pinned UBI 1.24 base, which is the newest Red Hat has published). Each entry expires on 2026-11-17, after which the scan fails again.

I did not downgrade Gradle, DataTables or jQuery.

### Test results (final runs, after all commits)

- **CI "build"** (`./gradlew build`, H2): 656 tests, 0 failures, 1 skipped (the skip was already there).
- **CI "test-postgres"** (`./gradlew test` on the CI's own `postgres:17.11-alpine` digest, `max_connections=100`, fresh database): 656 tests, 0 failures, 1 skipped. Peak was 19 connections.
- **Helm:** `lint --strict` and kubeconform pass for all 6 `ci/*.yaml` files (Helm v3.16.4, as CI).
- **Image:** starts in 16–26 s. `/actuator/health/liveness` is UP, `/login` and `/livez` return 200, and the license directory is writable. Trivy 0.75.0 with CI's flags exits 0.
- **Browser check** (headless Chromium, rendered people and servers pages, jQuery 4.0.0, DataTables 3.0.4, Tabler 1.6.1):
  - Before the fix: the table request had no token, got 403, and the page went to `/login`.
  - After: rows draw, row expand works (contacts and audit trail attach), search redraws through the server, a plain jQuery POST keeps the token, and the console is clean.

### Not done, and caveats

- **The Dockerfile's Gradle build stage could not run here.** The sandbox's TLS proxy isn't trusted inside `docker build`. I built the image from the host-built jar with an otherwise identical scratch Dockerfile, so CI will be the first to run Gradle 9.8.1 inside the UBI build stage.
- The ens-license 1.1.0 adoption and the license-library FIPS test results are still to do.
- Version overrides and `.trivyignore` entries need follow-up: Dependabot may not track `extra[...]` overrides.
- The javac removal warnings in `SecurityConfig` remain.
- Gradle daemons: I ran with `--no-daemon` and a private daemon registry, and stopped only my own. A plain `./gradlew --stop` from another agent killed one of my runs, so I didn't use it.
- Cleaned up: my containers, the image and its build cache, the Trivy cache, the PostgreSQL image I pulled, scratch files, and the worktree's `build/` and `.gradle/`.

### Operator-facing changes in this follow-up

- **Migration V20:** recreates `ck_server_attribute_definition_type`. No data change; on PostgreSQL it is a no-op in effect.
- **IPv4 ranges no longer match IPv6 addresses** in `CERT_ALERT_REVOCATION_ALLOWED_HOSTS` and `ENS_X509_TRUSTED_PROXIES`. The Helm default `x509.trustedProxies` is IPv4-only (`10.0.0.0/8,172.16.0.0/12,192.168.0.0/16`). Header-mode sites whose proxy connects over IPv6 must add IPv6 ranges, e.g. `fd00::/8`.
- **Tomcat 11.0.27 and Jackson 3.1.7 / 2.21.8** ship in the jar.
- **The tables work again** under DataTables 3.

### Docs edits needed (`/home/user/ens-apps-documentation/docs/cert-alert/`, which predates all of Wave 2)

- **configuration.md**
  - Revocation:
    - `cert-alert.revocation.allowed-hosts` / `CERT_ALERT_REVOCATION_ALLOWED_HOSTS`: default empty, meaning anywhere except link-local and metadata addresses (never) and loopback (only when named). Entries are names, `*.domain`, or CIDRs matched within their own family.
    - `max-fetch-time` is 60 s.
    - Fetches use 5 s / 10 s timeouts, follow at most 3 redirects (http/https only), and have bounded sizes.
  - Timeouts:
    - LDAP: `CERT_ALERT_LDAP_CONNECT_TIMEOUT_MS=10000` and `CERT_ALERT_LDAP_READ_TIMEOUT_MS=60000`.
    - Mail: `CERT_ALERT_MAIL_{CONNECT,READ,WRITE}_TIMEOUT_MS=30000`.
  - Mail outbox: retries 1 min, doubling up to 1 h, for 3 days.
  - TLS: 1.2 and 1.3 only.
  - Web and cookies: `ENS_CSP_REPORT_ONLY`, `ENS_COOKIE_SECURE`, `SERVER_FORWARD_HEADERS_STRATEGY=framework`.
  - Sessions:
    - `ENS_SESSION_IDLE_TIMEOUT` (default 30m)
    - `ENS_SESSION_ADMIN_IDLE_TIMEOUT`
    - `ENS_SESSION_ABSOLUTE_TIMEOUT` (default 12h)
    - `ENS_SESSION_MAX_PER_USER` (default 0)
  - Consent banner: `ENS_CONSENT_BANNER_ENABLED`, `_TITLE`, `_TEXT`, `_FILE`.
  - Client certificates (`ENS_X509_*`):
    - revocation: `ENS_X509_REVOCATION`, `_OCSP_RESPONDER_URL`, `_CRL_DIR`, `_REVOCATION_FAIL`, `_REVOCATION_CACHE`
    - issuers and mapping: `ENS_X509_ALLOWED_ISSUERS`, `ENS_X509_USER_FILTER` (`{upn}`, `{edipi}`, `{email}`, `{uuid}`)
    - header mode: `ENS_X509_SOURCE`, `_HEADER`, `_TRUSTED_PROXIES`, `_PROXY_SECRET[_FILE]`, `_TRUSTED_CA_FILE`
    - `ENS_X509_USER_FILTER` and `ENS_X509_TRUSTED_CA_FILE` are names not in the spec.
  - Line 360: actuator exposure now includes `prometheus`.
  - `MANAGEMENT_SERVER_PORT`.
  - `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`.
- **security.md**
  - Security headers and CSP, cookie hardening, consent banner, session controls.
  - X.509 revocation, issuer allowlist, identity mapping, header mode with proxy secret. A certificate sign-in is now recorded once per session.
  - Public paths: `/livez`, `/readyz`, the consent page; Prometheus only on the management port.
  - Outbound connections: the revocation fetch policy, and the IPv4/IPv6 note.
  - A new FIPS 140-3 section, including "license check not supported in FIPS mode yet".
  - Hardening checklist.
- **install.md**
  - New Helm values: `x509.*`, `allowedHosts`, `session.*`, `consentBanner.*`, `metrics.*` (`metrics.enabled` defaults to true, which moves the probes to port 9090 over plain HTTP), `serviceMonitor`, `allowFrom`, `logFormat` (default json).
  - Helm header mode fails to render without the proxy-secret Secret and the CA.
  - Release bundle: the new `env.template` entries; `install.sh` now warns when plain HTTP is exposed; bind address in `docker-compose.ports.yml`; `HEALTHCHECK` on `/livez`.
  - Image license label is now `LicenseRef-ENS-Commercial`; cosign verification.
- **operator-guide.md**
  - Health: liveness and readiness groups (readiness = db + audit log).
  - JSON logs.
  - Prometheus metrics: `ens_license_mode`, `ens_license_days_remaining`, `ens_audit_events_total`, `ens_audit_forward_failures_total`, `ens_auth_attempts_total`, `ens_certalert_certificates{status}`, `ens_certalert_job_last_success_seconds{job}`, `ens_certalert_digest_truncated_total`, `ens_certalert_revocation_unknown`.
  - What happens when the mail server is down (the outbox).
  - Upgrades: migrations V19 (`outbound_email`) and V20; Helm probe and port change; trusted-proxy IPv6 note; revocation stays off by default (a startup warning is logged).
- **troubleshooting.md**
  - Revocation host refused (loopback, link-local, allowlist, address family).
  - Sign-in refusals: revoked, revocation unknown, issuer not allowed, untrusted proxy.
  - Mail waiting in the outbox.
  - `/login?expired`.
  - The CSP report-only escape hatch.
- **user-guide.md:** the consent banner before sign-in, and the session-expired message.
- **index.md / architecture.md**
  - Tech stack: Gradle 9.8.1 (needs JDK 17+ to run Gradle; the project toolchain is still 21), DataTables 3 (no longer needs jQuery), jQuery 4, Tabler 1.6.1.
  - Mail outbox and observability components.
  - The note that table requests carry the CSRF header themselves.
  - No user-visible UI change from DataTables 3 once fixed.
