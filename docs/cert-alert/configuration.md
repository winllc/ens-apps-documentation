# Configuration

CertAlert is a Spring Boot application. All settings live in `application.yml` (packaged in the
jar) and can be overridden in any of the usual Spring Boot ways: an external `application.yml`,
`--property=value` command-line arguments, or environment variables.

!!! tip "Environment variables"
    Some settings name an explicit environment variable in `application.yml` (shown in the
    **Env var** column below). Every other property can still be set from the environment through
    Spring Boot's relaxed binding: upper-case it and replace `.` and `-` with `_`. For example
    `cert-alert.ldap.changelog.enabled` → `CERT_ALERT_LDAP_CHANGELOG_ENABLED`, and
    `cert-alert.security.admin-identifiers` → `CERT_ALERT_SECURITY_ADMIN_IDENTIFIERS`
    (comma-separated for a list). The Docker Compose file uses exactly these forms.

Durations accept Spring Boot's formats (`10s`, `5m`, `6h`, `30d`). Cron expressions are Spring's
six-field form (`second minute hour day-of-month month day-of-week`) and are **evaluated in UTC**.

## Profiles

| Profile | File | What it does |
|---|---|---|
| *(none)* | `application.yml` | H2 file database at `./data/cert-alert`, LDAP at `ldap://localhost:389`. For trying the jar or image out. |
| `postgres` | `application-postgres.yml` | PostgreSQL datasource from `CERT_ALERT_DB_*`. **Use this for any real deployment.** |
| `dev` | `application-dev.yml` | In-memory H2, embedded sample LDAP on port 18389, H2 console, no scheduled jobs, all actuator endpoints, DEBUG logging. Only works from source (`./gradlew bootRun`). |

Activate with `SPRING_PROFILES_ACTIVE=postgres` or `--spring.profiles.active=postgres`.

## Database

| Property | Env var | Default | Description |
|---|---|---|---|
| `spring.datasource.url` | `CERT_ALERT_DB_URL` *(postgres profile)* | `jdbc:h2:file:./data/cert-alert;DB_CLOSE_DELAY=-1;AUTO_SERVER=TRUE`; postgres profile: `jdbc:postgresql://localhost:5432/certalert` | JDBC URL |
| `spring.datasource.username` | `CERT_ALERT_DB_USER` *(postgres)* | `sa`; postgres: `certalert` | Database user |
| `spring.datasource.password` | `CERT_ALERT_DB_PASSWORD` *(postgres)* | empty | Database password |
| `spring.datasource.driver-class-name` | | `org.h2.Driver`; postgres: `org.postgresql.Driver` | JDBC driver |
| `spring.datasource.hikari.maximum-pool-size` | `CERT_ALERT_DB_POOL_SIZE` *(postgres)* | `10` | Connection pool size |
| `spring.flyway.enabled` | | `true` | Flyway migrates the schema on startup (`classpath:db/migration`) |
| `spring.jpa.hibernate.ddl-auto` | | `validate` | Hibernate only checks the mapping against the schema |
| `spring.jpa.properties.hibernate.jdbc.batch_size` | | `50` | JDBC batch size for sync writes |
| `spring.jpa.properties.hibernate.order_by.default_null_ordering` | | `last` | Makes "soonest to expire" sort identically on H2 and PostgreSQL |

!!! warning "Do not change `ddl-auto` or `default_null_ordering`"
    Flyway owns the schema. `validate` makes a drifted mapping fail at boot. The null-ordering
    setting keeps entries with no certificate at the end of expiry-sorted tables on both databases.

## LDAP connection

| Property | Env var | Default | Description |
|---|---|---|---|
| `spring.ldap.urls` | `CERT_ALERT_LDAP_URL` | `ldap://localhost:389` | Directory URL(s); use `ldaps://` in production |
| `spring.ldap.base` | `CERT_ALERT_LDAP_BASE` | empty | Base DN; search bases below are relative to it |
| `spring.ldap.username` | `CERT_ALERT_LDAP_USER` | empty | Service account bind DN (empty = anonymous) |
| `spring.ldap.password` | `CERT_ALERT_LDAP_PASSWORD` | empty | Service account password |
| `spring.ldap.base-environment.*` | | none | Extra JNDI properties for every connection, e.g. `java.naming.ldap.attributes.binary` |

### Scraping (`cert-alert.ldap`)

| Property | Env var | Default | Description |
|---|---|---|---|
| `page-size` | | `500` | Entries per LDAP page |
| `paged` | | `true` | Use the paged-results control. Turn off only for servers without it |
| `batch-size` | | `200` | Entries written per database transaction |
| `count-limit` | | `0` | Client-side cap on entries per search; `0` = none |
| `search-timeout` | | `10m` | Per-search time limit |
| `binary-certificate-option` | `CERT_ALERT_LDAP_BINARY_CERTIFICATE_OPTION` | `true` | Request `userCertificate;binary` (RFC 4522). Set `false` for virtual directories (e.g. Radiant Logic FID 7.4) that return nothing for the qualified name |

!!! note "Renamed certificate attribute with the binary option off"
    If you map the certificate to a non-standard attribute name *and* turn
    `binary-certificate-option` off, declare it binary so it is not decoded as text:

    ```yaml
    spring:
      ldap:
        base-environment:
          java.naming.ldap.attributes.binary: myCertificateAttribute
    ```

### People mapping (`cert-alert.ldap.user`)

| Property | Default | Description |
|---|---|---|
| `search-base` | `""` | Relative to `spring.ldap.base`; empty = whole tree |
| `search-filter` | `(objectClass=icOrgPerson)` | Which entries are people |
| `email-precedence` | `[icEmail, mail, internetEmail, siprnetEmail, niprnetEmail]` | Which address is shown as primary. All addresses are indexed regardless |
| `additional-email-attributes` | `[]` | Extra attributes holding addresses (e.g. `alternateMail`). Values split on commas; non-addresses dropped and logged |
| `uid` | `uid` | |
| `common-name` | `cn` | |
| `display-name` | `displayName` | |
| `preferred-name` | `preferredName` | |
| `given-name` | `givenName` | |
| `surname` | `sn` | |
| `mail` | `mail` | |
| `ic-email` | `icEmail` | |
| `internet-email` | `internetEmail` | |
| `niprnet-email` | `niprnetEmail` | |
| `siprnet-email` | `siprnetEmail` | |
| `telephone-number` | `telephoneNumber` | |
| `title` | `title` | |
| `employee-type` | `employeeType` | |
| `rank` | `rank` | |
| `country-of-affiliation` | `countryOfAffiliation` | |
| `duty-organization` | `dutyOrganization` | |
| `duty-sub-organization` | `dutySubOrganization` | Not listed in `application.yml` but bound with this default |
| `admin-organization` | `adminOrganization` | |
| `ic-member` | `isICMember` | |
| `ic-networks` | `icNetworks` | |
| `resource-security-mark` | `resourceSecurityMark` | |
| `organization` | `o` | |
| `organizational-unit` | `ou` | |
| `certificate` | `userCertificate` | |

### Server mapping (`cert-alert.ldap.server`)

| Property | Env var | Default | Description |
|---|---|---|---|
| `search-base` | | `""` | Relative to `spring.ldap.base` |
| `search-filter` | | `(objectClass=icOrgServer)` | Which entries are servers |
| `require-email-poc` | `CERT_ALERT_LDAP_REQUIRE_EMAIL_POC` | `true` | A `serverPOC` value must be an email address; non-addresses are dropped and logged. Set `false` for directories that hold names (as the FSD spec says). Comma-separated values are split either way |
| `server-poc` | | `serverPOC` | Point-of-contact attribute |
| `common-name` | | `cn` | |
| `uid` | | `uid` | |
| `given-name` | | `givenName` | |
| `description` | | `description` | |
| `server-url` | | `serverURL` | Also the first choice of host for the endpoint probe |
| `ic-server-address` | | `icServerAddress` | Fallback probe host |
| `ato-status` | | `ATOStatus` | |
| `life-cycle-status` | | `lifeCycleStatus` | |
| `employee-type` | | `employeeType` | |
| `country-of-affiliation` | | `countryOfAffiliation` | |
| `duty-organization` | | `dutyOrganization` | |
| `duty-sub-organization` | | `dutySubOrganization` | Bound with this default; not in `application.yml` |
| `admin-organization` | | `adminOrganization` | |
| `ic-member` | | `isICMember` | |
| `ic-networks` | | `icNetworks` | |
| `resource-security-mark` | | `resourceSecurityMark` | |
| `organization` | | `o` | |
| `organizational-unit` | | `ou` | |
| `certificate` | | `userCertificate` | |

!!! warning
    A wrong attribute name yields an empty column, not an error. Confirm the mapping against your
    directory before the first sweep.

## Scheduled jobs

All schedules are UTC. See [Operator guide → Scheduled jobs](operator-guide.md#scheduled-jobs).

### Sweeps (`cert-alert.ldap.sync`)

| Property | Default | Description |
|---|---|---|
| `enabled` | `true` | **Master switch for the scheduler.** `false` disables *every* scheduled job (sweeps, refresh, revocation, round-up, prune, clean-up, audit retention); jobs can then only be triggered through the API |
| `users-cron` | `0 0 2 * * *` | IC Person sweep |
| `servers-cron` | `0 0 4 * * *` | IC Non-Person Entity sweep |
| `refresh-cron` | `0 15 * * * *` | Re-evaluate cached expiry (no LDAP reads) |

### Prune (`cert-alert.ldap.prune`)

| Property | Default | Description |
|---|---|---|
| `enabled` | `false` | Whether the scheduled prune deletes stale entries |
| `cron` | `0 0 6 * * SUN` | Prune schedule |
| `after` | `30d` | How long an entry may go unseen by sweeps before deletion. Keep well beyond the sweep interval |

### Certificate clean-up (`cert-alert.ldap.certificate-cleanup`)

The only job that **writes to the directory**: it deletes finished certificates from entries.

| Property | Default | Description |
|---|---|---|
| `enabled` | `false` | Whether finished certificates are deleted (also gates the manual API call) |
| `cron` | `0 0 3 * * SAT` | Schedule |
| `remove-expired` | `true` | Delete certificates expired for longer than `expired-after` |
| `expired-after` | `90d` | Grace period after expiry |
| `remove-revoked` | `true` | Delete revoked certificates |
| `revoked-after` | `7d` | Grace period after a revocation is found |
| `max-per-run` | `500` | Cap on removals per run; revoked certificates go first |

### Changelog connector (`cert-alert.ldap.changelog`)

| Property | Default | Description |
|---|---|---|
| `enabled` | `false` | Follow the directory's changelog continuously |
| `auto-start` | `true` | Start the polling loop with the application. Set `false` on all but one instance |
| `base-dn` | `cn=changelog` | Changelog suffix |
| `filter` | `(&(objectClass=changeLogEntry)(changeNumber>={0}))` | Search filter; `{0}` is the next change number |
| `poll-interval` | `10s` | Wait after a poll that found nothing |
| `batch-size` | `500` | Most changes read per poll |
| `start-from` | `LATEST` | `LATEST` or `BEGINNING`, used when no position is stored |
| `full-sync-on-first-run` | `true` | With no stored position, run a full sweep before following |
| `full-sync-on-gap` | `true` | Run a full sweep when the changelog has been trimmed past the stored position |
| `max-backoff` | `5m` | Ceiling on back-off after errors |
| `change-number-attribute` | `changeNumber` | |
| `target-dn-attribute` | `targetDN` | |
| `change-type-attribute` | `changeType` | |
| `new-rdn-attribute` | `newRDN` | |
| `new-superior-attribute` | `newSuperior` | |
| `first-change-number-attribute` | `firstChangeNumber` | Read from the root DSE / changelog bounds |
| `last-change-number-attribute` | `lastChangeNumber` | |

## Expiry thresholds (`cert-alert`)

| Property | Default | Description |
|---|---|---|
| `warning-threshold-days` | `30` | Inside this window a certificate is `EXPIRING_SOON` |
| `critical-threshold-days` | `7` | Inside this window alert severity becomes `CRITICAL` |

## Credentials and risk

| Property | Default | Description |
|---|---|---|
| `cert-alert.credentials.pair-window` | `7d` | How far apart a person's signing and encryption certificates may be issued and still count as one renewal (one credential) |
| `cert-alert.risk.max-subject-alt-names` | `20` | More SANs than this flags **Many names** |
| `cert-alert.risk.max-domains` | `3` | Names spanning more registrable domains than this flags **Many domains** |

## Revocation (`cert-alert.revocation`)

| Property | Env var | Default | Description |
|---|---|---|---|
| `enabled` | | `true` | Whether revocation is checked at all |
| `cron` | | `0 30 5 * * *` | Nightly check, after both sweeps |
| `issuer-directory` | | unset | Directory of CA certificates (PEM or DER). Needed for CRL signature verification and for OCSP |
| `default-ocsp-url` | `CERT_ALERT_DEFAULT_OCSP_URL` | unset | Responder for certificates that name none. The certificate's own always wins |
| `default-crl-url` | `CERT_ALERT_DEFAULT_CRL_URL` | unset | Distribution point for certificates that name none. Prefer this over the OCSP fallback |
| `workers` | | `8` | Concurrent OCSP requests (`1` = one at a time) |
| `allow-unverified-crl` | | `true` | Believe a CRL whose signature could not be checked. `false` makes those results *Unknown* |
| `crl-cache-ttl` | | `6h` | Ceiling on how long a fetched CRL is reused (also bounded by its `nextUpdate`) |
| `max-crl-bytes` | | `16777216` | Larger CRLs are refused |
| `connect-timeout` | | `10s` | Connecting to a distribution point |
| `read-timeout` | | `30s` | Downloading; also sets the JVM OCSP timeout unless `-Dcom.sun.security.ocsp.timeout` is given |

## Endpoint probe (`cert-alert.probe`)

| Property | Default | Description |
|---|---|---|
| `enabled` | `true` | `false` removes the Endpoint card and refuses the API |
| `default-port` | `443` | Port offered when the entry's URL names none |
| `connect-timeout` | `5s` | TCP connect timeout |
| `read-timeout` | `5s` | TLS handshake timeout |

## Notifications (`cert-alert.notifications`)

Per-person notifications and the daily expiry round-up email.

| Property | Env var | Default | Description |
|---|---|---|---|
| `enabled` | | `true` | Whether per-person notifications are written at all (alert channels are unaffected) |
| `repeat-after` | | `7d` | Do not repeat the same transition notification for the same certificate within this |
| `digest.enabled` | | `true` | Whether the scheduled round-up runs |
| `digest.cron` | | `0 0 7 * * *` | Round-up schedule |
| `digest.window` | | `30d` | Initial lead time. **Overridden** once an administrator sets it on the Notifications page (stored in `notification_setting`); clamped to 1–365 days |
| `digest.include-expired` | | `true` | Include already-expired certificates in the round-up |
| `email.enabled` | | `false` | Send the round-up by email. Requires `spring.mail.*` |
| `email.from` | | `cert-alert@localhost` | Sender address |
| `email.subject-prefix` | | `"[cert-alert] "` | Subject prefix |
| `email.max-per-run` | | `500` | Cap on messages per run |
| `email.dry-run` | `CERT_ALERT_EMAIL_DRY_RUN` | `false` | Build every message and send none; write no notifications |
| `email.template-directory` | `CERT_ALERT_NOTIFICATIONS_EMAIL_TEMPLATE_DIRECTORY` | **absent** | Directory of override templates (`<dir>/email/expiring-user.html` etc.). Must be absent, not empty, to be off |
| `retention.enabled` | | `false` | Delete *read* notifications older than `retention.after` (runs after each round-up) |
| `retention.after` | | `90d` | Age threshold for that deletion |

### Email templates

Packaged templates live in `templates/email/` in the jar:

| Template | Content |
|---|---|
| `expiring-user.html` / `.txt` | The recipient's own certificates |
| `expiring-server.html` / `.txt` | Certificates on servers the recipient is a contact for |

Only files present in the override directory are used; the rest fall back to the packaged ones.
Templates are read once and cached, so changes take effect on restart. The model is
`digest.recipientName`, `digest.headline`, `digest.entries` and each entry's fields; start by
copying the packaged template you want to change.

## Operator alert channels (`cert-alert.alerts`)

Alert channels tell a fixed list of operators about **every** alert as it happens. They are
separate from per-person notifications.

| Property | Default | Description |
|---|---|---|
| `logging.enabled` | `true` | Write alerts to the application log (`WARN`, or `ERROR` for critical) |
| `email.enabled` | `false` | Email every alert. Requires `spring.mail.*` |
| `email.from` | `cert-alert@localhost` | Sender |
| `email.to` | `[]` | Recipients. If empty, the email channel logs a warning and skips |
| `email.subject-prefix` | `"[cert-alert] "` | Subject prefix |

### SMTP (`spring.mail.*`)

Both email features use Spring Boot's standard mail settings, for example:

```yaml
spring:
  mail:
    host: smtp.example.gov
    port: 25
    # username / password / properties.mail.smtp.starttls.enable as your relay requires
```

## Security (`cert-alert.security`)

See [Security](security.md) for how these interact.

| Property | Default | Description |
|---|---|---|
| `enabled` | `true` | `false` removes the application's security configuration. Local development only |
| `admin-identifiers` | `[]` | Values granting `ROLE_ADMIN`, matched against any identifier the directory holds for a person (address, uid, cn, …) |
| `contact-editors` | `ADMIN` | `ADMIN` or `AUTHENTICATED`. Who may edit a person's added addresses and create/edit projects; also lets anyone signed in manage any server's contacts when `AUTHENTICATED` |
| `x509.enabled` | `true` | Accept client certificates |
| `x509.require-known-certificate` | `true` | Match the client certificate by SHA-256 fingerprint against the cache. `false` falls back to matching subject / `rfc822Name` SANs against directory entries |
| `ldap.enabled` | `true` | Offer the directory-password sign-in |
| `ldap.user-search-base` | `""` | Where to search for the username |
| `ldap.user-search-filter` | `(uid={0})` | How to search for it |
| `ldap.user-dn-patterns` | `[]` | Bind directly to these DN patterns instead of searching, e.g. `["uid={0},ou=people"]` |

## TLS (`server.ssl`)

| Property | Env var | Default | Description |
|---|---|---|---|
| `server.port` | `SERVER_PORT` | `8080` | Listening port (image sets `SERVER_PORT=8080`) |
| `server.ssl.enabled` | `CERT_ALERT_TLS_ENABLED` | `false` | Serve HTTPS (required for X.509 sign-in) |
| `server.ssl.client-auth` | | `want` | Request but do not require a client certificate. Do **not** set `need`: it makes the password form unreachable |
| `server.ssl.key-store` | `CERT_ALERT_KEYSTORE` | empty | e.g. `file:/etc/cert-alert/tls/keystore.p12` |
| `server.ssl.key-store-password` | `CERT_ALERT_KEYSTORE_PASSWORD` | empty | |
| `server.ssl.key-store-type` | `CERT_ALERT_KEYSTORE_TYPE` | `PKCS12` | |
| `server.ssl.trust-store` | `CERT_ALERT_TRUSTSTORE` | empty | CAs whose client certificates are accepted at the handshake |
| `server.ssl.trust-store-password` | `CERT_ALERT_TRUSTSTORE_PASSWORD` | empty | |
| `server.ssl.trust-store-type` | `CERT_ALERT_TRUSTSTORE_TYPE` | `PKCS12` | |

## Audit trail (`cert-alert.audit`)

| Property | Default | Description |
|---|---|---|
| `enabled` | `true` | `false` stops new records being written; existing ones stay readable |
| `retention.enabled` | `false` | Trim old records on a schedule |
| `retention.after` | `365d` | Records older than this are removed |
| `retention.cron` | `0 30 3 * * SUN` | Trim schedule |

## Classification banner (`cert-alert.banner`)

| Property | Env var | Default | Description |
|---|---|---|---|
| `enabled` | `CERT_ALERT_BANNER_ENABLED` | `false` | Show fixed bars at the top and bottom of every page (nothing shows until `text` is set) |
| `text` | `CERT_ALERT_BANNER_TEXT` | empty | Marking text, e.g. `UNCLASSIFIED//FOUO` |
| `text-color` | `CERT_ALERT_BANNER_TEXT_COLOR` | `#ffffff` | Hex colour or CSS keyword; invalid values fall back to the default |
| `background` | `CERT_ALERT_BANNER_BACKGROUND` | `#1d273b` | Same rules |
| `height` | `CERT_ALERT_BANNER_HEIGHT` | `1.5rem` | Number + `px`, `rem`, `em`, `pt` or `vh`; invalid falls back |

## Server, actuator, logging

| Property | Default | Description |
|---|---|---|
| `server.shutdown` | `graceful` | Finish in-flight requests on SIGTERM |
| `spring.lifecycle.timeout-per-shutdown-phase` | `30s` | Graceful shutdown limit |
| `server.error.include-message` | `never` | Error pages never echo exception messages |
| `management.endpoints.web.exposure.include` | `health,info,metrics,loggers` | Exposed actuator endpoints |
| `management.endpoint.health.show-details` | `when-authorized` | Health details only for authorised (admin) callers |
| `management.endpoint.health.probes.enabled` | `true` | Enables `/actuator/health/liveness` and `/readiness` |
| `logging.level.com.winllc.certalert` | `INFO` | Application log level |
| `JAVA_OPTS` *(image env)* | `-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError` | JVM options in the container |

## Example configurations

=== "Production (external application.yml)"

    ```yaml
    spring:
      profiles:
        active: postgres
      mail:
        host: smtp.example.gov
        port: 25

    server:
      ssl:
        enabled: true
        client-auth: want
        key-store: file:/etc/cert-alert/tls/keystore.p12
        key-store-password: ${CERT_ALERT_KEYSTORE_PASSWORD}
        trust-store: file:/etc/cert-alert/tls/truststore.p12
        trust-store-password: ${CERT_ALERT_TRUSTSTORE_PASSWORD}

    cert-alert:
      banner:
        enabled: true
        text: "UNCLASSIFIED//FOUO"
        background: "#006400"
      security:
        admin-identifiers: [alice@example.gov, bob@example.gov]
        ldap:
          user-search-base: ou=people
      ldap:
        user:
          search-base: ou=people
        server:
          search-base: ou=servers
        changelog:
          enabled: true
      revocation:
        issuer-directory: /etc/cert-alert/issuers
        default-crl-url: http://pki.example.gov/ca.crl
      notifications:
        email:
          enabled: true
          from: cert-alert@example.gov
          template-directory: /etc/cert-alert/templates
      alerts:
        email:
          enabled: true
          from: cert-alert@example.gov
          to: [pki-ops@example.gov]
    ```

=== "Environment variables (container)"

    ```bash
    SPRING_PROFILES_ACTIVE=postgres
    CERT_ALERT_DB_URL=jdbc:postgresql://db:5432/certalert
    CERT_ALERT_DB_USER=certalert
    CERT_ALERT_DB_PASSWORD=...
    CERT_ALERT_LDAP_URL=ldaps://directory.example.gov:636
    CERT_ALERT_LDAP_BASE=dc=example,dc=gov
    CERT_ALERT_LDAP_USER=cn=cert-alert,ou=services,dc=example,dc=gov
    CERT_ALERT_LDAP_PASSWORD=...
    CERT_ALERT_SECURITY_ADMIN_IDENTIFIERS=alice@example.gov
    CERT_ALERT_SECURITY_LDAP_USER_SEARCH_BASE=ou=people
    CERT_ALERT_LDAP_CHANGELOG_ENABLED=true
    CERT_ALERT_TLS_ENABLED=true
    CERT_ALERT_KEYSTORE=file:/etc/cert-alert/tls/keystore.p12
    CERT_ALERT_KEYSTORE_PASSWORD=...
    CERT_ALERT_TRUSTSTORE=file:/etc/cert-alert/tls/truststore.p12
    CERT_ALERT_TRUSTSTORE_PASSWORD=...
    CERT_ALERT_BANNER_ENABLED=true
    CERT_ALERT_BANNER_TEXT=UNCLASSIFIED
    ```

=== "Certificate-only sign-in"

    ```yaml
    cert-alert:
      security:
        ldap:
          enabled: false        # no password path at all
        x509:
          require-known-certificate: true
    server:
      ssl:
        enabled: true
        client-auth: want
    ```

=== "Directory-writing jobs on"

    ```yaml
    cert-alert:
      ldap:
        prune:
          enabled: true
          after: 30d
        certificate-cleanup:
          enabled: true
          expired-after: 90d
          revoked-after: 7d
          max-per-run: 500
    ```

    Check `GET /api/v1/sync/prune/preview` and `GET /api/v1/sync/certificate-cleanup/preview`
    first. The service account needs write access to the certificate attribute for clean-up.
