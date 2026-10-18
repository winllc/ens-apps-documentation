## Wave 2 RSA follow-up: report

Everything is done and committed on the local branch `claude/wave2-followup` in /home/user/wt/w2-rsa, on top of main fcd2c55. The worktree is clean. Nothing was pushed and I did not start Round 2.

The full `./mvnw -B install` passes, and helm lint plus kubeconform pass for all 6 `ci/*.yaml`. Two things need your attention:
- **The new Trivy gate in CI will fail on the Connector image.** The Portal image is clean. Details are under "Left undone".
- **The image run found a startup bug in my own TLS commit 5e0211a.** It is fixed in 84ace6a.

### 1. Root cause of the 2 `LoggingConfigTest.textLinesHaveTheDateAndTheRequestId` failures
Log4j2 shares one console output manager (`SYSTEM_OUT.false.false`) across all LoggerContexts. The test started its own context and read the line back from a replaced `System.out`. But once an earlier Spring test had logged in the same JVM, the shared manager was still writing to the original stream, so the captured output was empty. This only happened in the full suite.

The fix (55d89d1) formats the event through the configured `LogToConsole` appender's layout (`layout.toByteArray(event)`) in a private LoggerContext, and asserts on that. I reproduced it with `-Dtest=ActuatorSecurityTest,LoggingConfigTest -Dsurefire.runOrder=alphabetical`: 2 failures before, passing after.

### 2. Commits (fcd2c55..HEAD)
- **55d89d1** Fix LoggingConfigTest when other tests have logged first.
- **cfcab08** Readiness group is `readinessState,db,connector`, and `validate-group-membership=false` is gone, so the group is checked again. `RSAAuthManagerService` no longer implements `HealthIndicator`; that removes the duplicate `authManagerService` entry. The Connector check is now only the `connector` bean from #12.
- **5e0211a** RSA-15 (Portal part):
  - TLSv1.3 and TLSv1.2 in the image's `JAVA_OPTS`, Helm `javaOpts` and `env.template`.
  - `util/split-env.sh` recognises both the old and the new default options.
  - README sections "TLS versions" and "FIPS 140-3"; the ENS license library's FIPS result is still a placeholder.
- **84ace6a** Fixes 5e0211a. Setting `server.ssl.enabled-protocols` in `application.properties` turned HTTPS on, because `server.ssl.enabled` defaults to true. A Portal without a key store (the release default, and behind a proxy) failed with "SSL is enabled but no trust material is configured".
  - New `configuration.ServerTls` sets TLS 1.3/1.2 only when HTTPS is on and the operator named no protocols.
  - `ServerTlsTest` starts a real Tomcat with a test certificate and checks both handshakes, that an operator setting wins, and that nothing turns HTTPS on.
- **0f443ba** SAST and dependency fixes:
  - SpotBugs 4.10.4.1 with FindSecBugs 1.14.0 runs in `verify`. Only security findings count (`spotbugs/security-include.xml`), and a high-priority one fails the build. False positives are in `spotbugs/exclude.xml` with reasons:
    - CSRF off on the stateless bearer-token audit API.
    - The in-memory key store password in `ConnectorTls`.
  - `LoginController` is now `@GetMapping("/login")`; it was `@RequestMapping`, which answered every method.
  - Tomcat 11.0.26, Jackson 2.21.7 and 3.1.7 are pinned ahead of the Boot BOM. They fix the 13 HIGH/CRITICAL findings Trivy reported in Boot 4.1.1's versions.
- **97d6309** Supply chain:
  - Every action pinned to a commit SHA with the version in a comment.
  - Trivy 0.75.0 (sha256-checked) scans `rsaapp-portal:latest` and `rsaapp-connector:latest` on every push and PR, before publishing, with `--severity HIGH,CRITICAL --ignore-unfixed --exit-code 1 --format table`.
  - On release tags, cosign keyless (cosign-installer v3.10.1, cosign v2.6.1, `id-token: write`):
    - signs both images by the registry digest;
    - attaches each image's SPDX SBOM with `cosign attest --type spdxjson`;
    - signs the Helm chart by the digest `helm push` prints.
  - `.github/dependabot.yml`: maven, docker for the three Dockerfiles, github-actions; weekly, minor and patch grouped.
  - README "Releases" now has the cosign verify commands and the CI checks.
- **9c55bb7** RSA-16: the Jenkinsfile now does `docker login --password-stdin`.
- **645cc15** RSA-16: new `util/portal-files.sh` gives the non-root Portal its files before it starts.
  - It runs a root container of the Portal image (`compose run --no-deps --user 0`) that hands the `ens-license` and `ens-audit` volumes and `logs/rsaapp-portal*` to 1001:0, and lets group 0 write to `logs/`. The Connector's files are left alone.
  - `deploy/release/install.sh`, `upgrade.sh` and `scripts/install.sh` call it; `scripts/install-podman.sh` does the same on the host.
  - I tried it on a root-owned copy of an old layout; uid 1001 could write everything afterwards.
  - The release README and the Helm `podSecurityContext` comment are updated.

### 3. Test results
- **Full `./mvnw -B install`: BUILD SUCCESS.** api 3, mock 40, connector 95, portal 650 tests; no failures or errors.
- **SpotBugs:** 0 high and 35 medium findings, which are reported but don't fail the build. ENTITY_LEAK 9, USO_UNSAFE_METHOD_SYNCHRONIZATION 4 (+2 static), UNSAFE_HASH_EQUALS 3, URLCONNECTION_SSRF_FD 3, WEAK_TRUST_MANAGER 3, LDAP_INJECTION 2, PATH_TRAVERSAL_IN 2, SPRING_FILE_DISCLOSURE 2, UNVALIDATED_REDIRECT 2, SQL_INJECTION_SPRING_JDBC 1, HRS_REQUEST_PARAMETER_TO_HTTP_HEADER 1, PREDICTABLE_RANDOM 1. Details are in `rsaapp-portal/target/spotbugsXml.xml`.
- **Helm:** `helm lint --strict` and kubeconform 1.29 (skipping Route and ServiceMonitor) pass for default, embedded-db, existing-secrets, ingress, openshift and tls-passthrough.
- **Portal image** (built with prebuilt artifacts, run against mysql:8.4.11):
  - It starts as uid 1001 and `/login` returns 200 with CSP and all the other headers. The cookie is `HttpOnly; SameSite=Lax`, and there is no HSTS on plain HTTP.
  - ECS JSON logs carry `requestId` from `X-Request-Id`; the file log reads `... [smoke-42] ...`.
  - On management port 9090, liveness is 200 and readiness is 503 because no Connector was running, as expected. `ens_*` metrics are present. `/actuator/prometheus` on 8080 redirects to login.
  - **Trivy on the Portal image: 0 HIGH/CRITICAL.**
- Test containers, images and network are removed and the builder was pruned. Disk is at 2.3 GB free now because of other agents.

### 4. Left undone, or for you to decide
- **The Connector image fails the new Trivy gate: 28 findings (21 HIGH, 7 CRITICAL), all in `app.war`.**
  - Spring 3.2.18 and Spring Security 3.2.10, including CVE-2022-22965 and CVE-2016-1000027. These versions are tied to the RSA SDK.
  - commons-beanutils 1.9.4 (fix 1.11.0) and commons-compress 1.19 (fix 1.21).
  - So once Actions run, the image job will stop at "Scan the images" until this is resolved. Options: bump the two commons libraries on the Connector side, and/or accept the Spring 3.2 findings with justified, dated `.trivyignore` entries. That risk decision belongs to the Connector's owner, so I added no ignores and didn't touch `rsaapp-connector/**`.
- **The Connector Dockerfile's base is not digest-pinned** (`BASE_TAG=9.8`). That is in rsaapp-connector, so I left it. The UBI 9.8 digest the Portal and mock use is still current.
- **The Connector runs as `tomcat` (from #12) but writes `/var/log/connector.log` into the `./logs` bind mount it shares with the Portal.** On existing hosts that folder is root:root 755, so the Connector's file log probably can't be written. Not handled; it's a Connector/#12 matter.
- **Dependabot can't update the ARG-based `FROM` digest.** It has to be updated by hand; the comment and README say how.
- **GitHub Actions can't run** (billing), so the CI, cosign and Dependabot changes are only YAML-validated. The `imagetools --format '{{.Manifest.Digest}}'` call was tested locally.
- **No LICENSE file was added.** There was no "MIT" anywhere, and COPYRIGHT already states the code is proprietary.
- **The FIPS section has a placeholder** for the ENS license library's result under FIPS mode.

### 5. Operator-facing changes and the upgrade path
- **Database:** Flyway owns the schema now and `ddl-auto` is `validate`.
  - On the first start an existing database (release ≤1.5) gets a baseline at version 0, then V1. V1 adds missing tables, columns and indexes, and converts enum columns to TINYINT.
  - That conversion copies `AuditEvent` once, so a large audit table makes the first start take minutes.
  - Optional separate migration user: `SPRING_FLYWAY_USER`/`PASSWORD` (Helm `database.migration.*`).
- **MySQL 8.4.11:** `upgrade.sh` moves root and `MYSQL_USER` from `mysql_native_password` to `caching_sha2_password`, keeping their passwords. It stops and names any other such account, then does a slow shutdown. A backup is taken first, and it is the only way back.
- **Non-root Portal (uid 1001):** the installers fix ownership with `util/portal-files.sh`. Anyone who copies files into the volumes or `logs/` by hand must run it.
- **TLS:** 1.3 and 1.2 everywhere. An operator's own `JAVA_OPTS` that still says `TLSv1.2` alone keeps the old pin.
- **Image signatures:** they can be verified with cosign; the commands are in the README.

### 6. Docs edits needed in /home/user/ens-apps-documentation/docs/rsa-operations-portal/
- **configuration.md**
  - Database: `ddl-auto` is now `validate`; `spring.flyway.baseline-on-migrate=true` and `baseline-version=0`; optional `SPRING_FLYWAY_USER`/`PASSWORD`.
  - New settings: `ENS_CSP_REPORT_ONLY`, `ENS_COOKIE_SECURE`, `SERVER_FORWARD_HEADERS_STRATEGY=framework`, `ENS_CONSENT_BANNER_ENABLED`/`TITLE`/`TEXT`/`FILE`, `ENS_SESSION_IDLE_TIMEOUT` (30m), `ENS_SESSION_ADMIN_IDLE_TIMEOUT` (30m), `ENS_SESSION_ABSOLUTE_TIMEOUT` (12h), `ENS_SESSION_MAX_PER_USER` (0), `MANAGEMENT_SERVER_PORT`, `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`.
  - `JAVA_OPTS` default is now TLSv1.3,TLSv1.2. Portal HTTPS gets 1.3/1.2 automatically when `server.ssl.*` is configured.
  - Logging row: log lines now include the request id.
  - Helm keys: `metrics.*`, `logFormat`, `backup.*`, `consentBanner.*`, `session.*`, `database.migration.*`.
- **install.md**
  - MySQL 8.0 → 8.4 throughout (lines 18, 56, 65, 229; line 400 `mysql:8.0.46` → `8.4.11`).
  - "Hibernate creates the tables" → Flyway (lines 144, 237), including the grants for the optional migration user.
  - Upgrade: Flyway takeover of existing databases (slow first start on a big AuditEvent table), the MySQL 8.4 path with native-password handling and the backup, and the non-root ownership hand-over.
  - Verify downloads and images with cosign.
- **operator-guide.md**
  - MySQL 8.4 (line 11).
  - Health and monitoring: liveness/readiness (db + connector), management port 9090, Prometheus and the `ens_*` metrics table, ServiceMonitor.
  - Logs: JSON/ECS and `requestId`.
  - Backups: `util/backupdb.sh` options `-d`/`-k`/`-g`/`-a` and restore, plus the Helm `backup.*` CronJob.
  - Upgrades: Flyway, MySQL 8.4, uid 1001.
- **security.md**
  - CSP policy and report-only; HSTS, nosniff, X-Frame-Options, Referrer-Policy and Permissions-Policy.
  - Cookies (`Secure`/`HttpOnly`/`SameSite=Lax`); forwarded headers.
  - Consent banner; session limits.
  - TLS 1.3/1.2; FIPS 140-3 statement.
  - Non-root image.
  - Supply chain: SpotBugs/FindSecBugs, Trivy gate, cosign signatures and SBOM attestations.
- **troubleshooting.md**
  - Flyway failed migration: `flyway repair` or delete the failed row, then restart. A validate failure names the missing column.
  - MySQL 8.4 ERROR 1524 (native password).
  - A page script blocked by CSP: use `ENS_CSP_REPORT_ONLY=true` temporarily.
  - "Session expired" messages and the session limits.
  - Portal can't write its license or logs after an upgrade: run `util/portal-files.sh`.
  - Readiness DOWN when the Connector is unreachable.
- **architecture.md:** line 93 `ddl-auto=update` → Flyway; health groups and the management port; the request-id filter.
- **index.md:** MySQL 8.0 → 8.4 (lines 35, 65); Hibernate `ddl-auto=update` → Flyway (line 54); the stack line is stale (Spring Boot 3.0.6 → 4.1.1).
- **admin-guide.md:** audit actions `auth.consent-acknowledged` and `auth.session-expired`; the consent banner and session limits from an admin's view.
- **api-reference.md:**
  - `/login` is GET only (it was GET/POST).
  - The `/actuator/**` row: health is public with details for PMO; Prometheus only on the management port.
  - The `/consent` page.
