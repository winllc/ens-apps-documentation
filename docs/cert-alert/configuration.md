# Configuration

CertAlert is a Spring Boot application. Its defaults live in the `application.yml` packaged in
the image. You override them with environment variables:

| Install method | Where you set them |
|---|---|
| Release package | `.env` in the release directory (made from `env.template` by `install.sh`). Any setting below can be added as its environment variable. Run `docker compose up -d` after a change. |
| Helm chart | Your values file. Most settings have a named value (see the chart's `README.md`); any other goes in `extraEnv` as its environment variable. Run `helm upgrade` after a change. |

!!! tip "Environment variables"
    Some settings name an explicit environment variable in `application.yml` (shown in the
    **Env var** column below). Every other property can still be set from the environment through
    Spring Boot's relaxed binding: upper-case it and replace `.` and `-` with `_`. For example
    `cert-alert.ldap.changelog.enabled` → `CERT_ALERT_LDAP_CHANGELOG_ENABLED`, and
    `cert-alert.security.admin-identifiers` → `CERT_ALERT_SECURITY_ADMIN_IDENTIFIERS`
    (comma-separated for a list). The release package's `.env` uses exactly these forms.

Durations accept Spring Boot's formats (`10s`, `5m`, `6h`, `30d`). Cron expressions are Spring's
six-field form (`second minute hour day-of-month month day-of-week`) and are **evaluated in UTC**.

## Profiles

| Profile | File | What it does |
|---|---|---|
| *(none)* | `application.yml` | H2 file database at `./data/cert-alert`, LDAP at `ldap://localhost:389`. Not used by the release package or the Helm chart. |
| `postgres` | `application-postgres.yml` | PostgreSQL datasource from `CERT_ALERT_DB_*`. The release package and the Helm chart both run this profile. |

The release package and the chart set `SPRING_PROFILES_ACTIVE=postgres` for you. Do not change it.

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

**TLS to PostgreSQL.** The release package's PostgreSQL is reachable only on the bundle's internal
Docker network, and the connection to it is not encrypted. Reach a PostgreSQL anywhere else over
TLS: add `?sslmode=verify-full` to `CERT_ALERT_DB_URL` (with Helm, `database.params:
sslmode=verify-full`), and `&sslrootcert=<path>` for a private CA. `sslmode=require` encrypts
without checking who answered. With no `sslmode`, the PostgreSQL driver tries TLS and falls back
to plain text without saying so.

## LDAP connection

| Property | Env var | Default | Description |
|---|---|---|---|
| `spring.ldap.urls` | `CERT_ALERT_LDAP_URL` | `ldap://localhost:389` | Directory URL(s); use `ldaps://` in production |
| `spring.ldap.base` | `CERT_ALERT_LDAP_BASE` | empty | Base DN; search bases below are relative to it |
| `spring.ldap.username` | `CERT_ALERT_LDAP_USER` | empty | Service account bind DN (empty = anonymous) |
| `spring.ldap.password` | `CERT_ALERT_LDAP_PASSWORD` | empty | Service account password |
| `spring.ldap.base-environment.*` | | none | Extra JNDI properties for every connection, e.g. `java.naming.ldap.attributes.binary` |
| `spring.ldap.base-environment.[com.sun.jndi.ldap.connect.timeout]` | `CERT_ALERT_LDAP_CONNECT_TIMEOUT_MS` | `10000` | Milliseconds to wait for a connection to the directory (sweeps, changelog, sign-in) |
| `spring.ldap.base-environment.[com.sun.jndi.ldap.read.timeout]` | `CERT_ALERT_LDAP_READ_TIMEOUT_MS` | `60000` | Milliseconds to wait for each answer: one page of a sweep, one bind. Raise it for a directory that takes longer than a minute to return a page |

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
| `auto-start` | `true` | Start the polling loop with the application. Each poll takes a lock in the database, so with several replicas it runs on one at a time |
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
| `connect-timeout` | | `10s` | Connecting to a distribution point or responder |
| `read-timeout` | | `30s` | Each wait for one to answer |
| `max-fetch-time` | | `60s` | The whole of one download, redirects included |
| `allowed-hosts` | `CERT_ALERT_REVOCATION_ALLOWED_HOSTS` | empty | When set, the only hosts CRLs and OCSP answers are fetched from, comma-separated: host names, `*.example.gov` (the domain and everything under it), or IP addresses and CIDR ranges such as `10.20.0.0/16` |

**What a certificate can make CertAlert fetch.** Distribution points and responder addresses come
out of certificates, so every fetch is limited:

- link-local and cloud metadata addresses are never fetched from (`169.254.0.0/16`, `fe80::/10`,
  `fd00:ec2::254`, `100.100.100.200`, `0.0.0.0/8`), whatever `allowed-hosts` says;
- loopback is fetched from only when its host is named in `allowed-hosts`;
- the hosts of `default-ocsp-url` and `default-crl-url` are always allowed;
- at most three redirects are followed, to `http` or `https` only, and each hop is checked the
  same way; an OCSP request follows none;
- a refused address is logged as a warning and the result is *Unknown*.

Address ranges match only addresses of their own family: an IPv4 range never matches an IPv6
address. An IPv4-mapped IPv6 address (`::ffff:10.0.0.1`) counts as the IPv4 address it carries.
The same rules apply to the revocation check at sign-in
([Client certificates](#client-certificates-ensx509)).

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
| `digest.include-expired` | | `true` | Include already-expired certificates in the round-up. They are read separately from the ones about to expire, so they cannot crowd those out. An expired certificate on an entry that holds a current one is left out |
| `digest.scan-limit` | | `10000` | The most certificates one run reads of each kind: those about to expire (read first) and, with `include-expired`, expired ones still relied on. A run that reaches it logs a `WARN` naming which read it was, and leaves earlier round-ups in place rather than clearing them |
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

Both email features use Spring Boot's standard mail settings. The release package's
`env.template` lists them:

| Env var | Helm | Description |
|---|---|---|
| `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT` | `mail.host`, `mail.port` | The mail server. No host: no mail |
| `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD` | `mail.username`, `mail.password` | If the server needs a sign-in |
| `SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH` | `mail.smtpAuth` | `true` to sign in |
| `SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE` | `mail.starttls` | `true` to use STARTTLS. CertAlert sets no STARTTLS default of its own |
| `CERT_ALERT_MAIL_CONNECT_TIMEOUT_MS` | | `30000`. Milliseconds to wait for a connection (`mail.smtp.connectiontimeout`, and `smtps`) |
| `CERT_ALERT_MAIL_READ_TIMEOUT_MS` | | `30000`. Each answer from the server (`mail.smtp.timeout`) |
| `CERT_ALERT_MAIL_WRITE_TIMEOUT_MS` | | `30000`. Each write to it (`mail.smtp.writetimeout`) |

The mail server's certificate is checked against the JVM's trust store; for a private CA, see
[Installation → Directory](install.md#directory).

### Mail outbox (`cert-alert.mail.outbox`)

Every email, round-ups and alerts alike, is written to the `outbound_email` table in the same
transaction as whatever decided to send it, and sent once that has committed. A mail server that
is slow holds no transaction open, and one that is down loses nothing: a message that cannot be
sent is tried again, a minute later and then twice as long each time.

| Property | Default | Description |
|---|---|---|
| `poll-interval` | `30s` | How often waiting mail is looked at |
| `retry-initial` | `1m` | Wait after the first failure; it doubles after each further one |
| `retry-max` | `1h` | Longest wait between two attempts |
| `give-up-after` | `3d` | How long a message keeps being tried before it is given up on, with an error in the log |
| `keep` | `7d` | How long sent, given-up and replaced messages stay in the table |

A newer round-up to the same address replaces one still waiting, so a mail server that comes back
after a weekend delivers the latest round-up rather than three. A notification is marked emailed
when its message is actually sent, not when it is queued.

## Security (`cert-alert.security`)

See [Security](security.md) for how these interact.

| Property | Default | Description |
|---|---|---|
| `enabled` | `true` | `false` removes the application's security configuration. Local development only |
| `admin-identifiers` | `[]` | Values granting `ROLE_ADMIN`, matched against a person's `uid` and entry DN only. An e-mail address is ignored, with a startup warning naming the uid to use, and `cn`, `displayName` and `preferredName` never match. A DN contains commas: name it as a YAML list item, or as `CERT_ALERT_SECURITY_ADMIN_IDENTIFIERS_0`, `_1`, … with `CERT_ALERT_SECURITY_ADMIN_IDENTIFIERS` itself unset |
| `contact-editors` | `ADMIN` | `ADMIN` or `AUTHENTICATED`. Who may edit a person's added addresses and create/edit projects; also lets anyone signed in manage any server's contacts when `AUTHENTICATED` |
| `x509.enabled` | `true` | Accept client certificates |
| `x509.require-known-certificate` | `true` | Match the client certificate by SHA-256 fingerprint against the cache. A published certificate is still refused when revoked or outside its validity dates. `false` falls back to matching the subject's `uid` and email address and its `rfc822Name` SANs against a person's `uid` and published addresses (never the CN) |
| `ldap.enabled` | `true` | Offer the directory-password sign-in |
| `ldap.user-search-base` | `""` | Where to search for the username |
| `ldap.user-search-filter` | `(uid={0})` | How to search for it |
| `ldap.user-dn-patterns` | `[]` | Bind directly to these DN patterns instead of searching, e.g. `["uid={0},ou=people"]` |

## Client certificates (`ens.x509`)

The ENS platform baseline's client-certificate settings, the same in every ENS product that signs
in with certificates. They add to `cert-alert.security.x509` above. See
[Security → Client certificate](security.md#client-certificate-x509) for how they fit together.

| Env var | Default | Helm | Description |
|---|---|---|---|
| `ENS_X509_REVOCATION` | `off` | `x509.revocation` | `off`, `ocsp`, `crl` or `ocsp-then-crl`: checked live for every certificate presented, before it is matched to anybody. Off by default so an upgrade changes nothing; with certificate sign-in on and revocation off, startup logs a warning that DISA STIG requires it |
| `ENS_X509_OCSP_RESPONDER_URL` | none | `x509.ocspResponderUrl` | Ask this responder instead of the one the certificate names, for example a local OCSP relay |
| `ENS_X509_CRL_DIR` | none | (`extraEnv`, with `extraVolumes`) | A directory of CRL files (DER or PEM), re-read when they change. With it set, no distribution point is fetched: the air-gapped option |
| `ENS_X509_REVOCATION_FAIL` | `closed` | `x509.revocationFail` | `closed` refuses a certificate whose status cannot be found; `open` allows it, with a warning (and a startup warning) |
| `ENS_X509_REVOCATION_CACHE` | `10m` | `x509.revocationCache` | How long a good or revoked answer is reused, per issuer and serial number. No answer is reused for 30 seconds |
| `ENS_X509_ALLOWED_ISSUERS` | none: any issuer the trust store accepts | `x509.allowedIssuers` | Issuer DNs separated by `;`. A certificate from any other issuer is refused. Compared as names, so spacing and case don't matter |
| `ENS_X509_USER_FILTER` | none | `x509.userFilter` | An LDAP filter that finds the holder of a certificate the directory does not publish, with `{upn}`, `{edipi}`, `{email}` and `{uuid}` filled in, for example `(uid={edipi})`. Used only with `cert-alert.security.x509.require-known-certificate: false`, instead of the uid and address matching |
| `ENS_X509_SOURCE` | `servlet` | `x509.source` | `servlet`: the certificate comes from the TLS handshake CertAlert terminates. `header`: a proxy terminates TLS and forwards it |
| `ENS_X509_HEADER` | `X-SSL-Client-Cert` | `x509.header` | Header mode: the header holding the certificate, URL-encoded PEM as nginx's `$ssl_client_escaped_cert` sends it (ingress-nginx: `ssl-client-cert`) |
| `ENS_X509_TRUSTED_PROXIES` | `127.0.0.1/32,::1/128` | `x509.trustedProxies` (`10.0.0.0/8,172.16.0.0/12,192.168.0.0/16`) | Header mode: the addresses (CIDRs) the header is accepted from, separated by `,`, `;` or spaces. IPv4 ranges never match IPv6 addresses |
| `ENS_X509_PROXY_SECRET_FILE` / `ENS_X509_PROXY_SECRET` | none | `x509.proxySecret.*` | Header mode: the secret, at least 32 characters, that the proxy sends in `X-ENS-Proxy-Secret`. The file wins |
| `ENS_X509_TRUSTED_CA_FILE` | none | `x509.trustedCa.*` | PEM certificates of the CAs a forwarded certificate must chain to. Also searched for an issuer when checking revocation |

| Placeholder | Value |
|---|---|
| `{upn}` | The subject alternative name otherName UserPrincipalName |
| `{edipi}` | The digits of a `1234567890@mil` UPN, or else the trailing number of a `LAST.FIRST.MI.1234567890` common name |
| `{email}` | The rfc822Name subject alternative name, or else the subject's emailAddress |
| `{uuid}` | The `urn:uuid:` subject alternative name URI (the PIV FASC-N UUID) |

Every value is LDAP-escaped where it is put in. A filter naming a value the certificate does not
carry is not searched with, and a search that finds no entry, more than one, or one CertAlert has
not synced signs nobody in.

**CertAlert refuses to start in header mode** without a proxy secret of 32 characters or more,
without `ENS_X509_TRUSTED_CA_FILE`, or with `SERVER_FORWARD_HEADERS_STRATEGY=native` (use
`framework`). The Helm chart refuses to render header mode without `x509.proxySecret` and
`x509.trustedCa`.

## TLS (`server.ssl`)

| Property | Env var | Default | Description |
|---|---|---|---|
| `server.port` | `SERVER_PORT` | `8080` | Listening port (image sets `SERVER_PORT=8080`) |
| `server.ssl.enabled` | `CERT_ALERT_TLS_ENABLED` | `false` | Serve HTTPS (required for X.509 sign-in) |
| `server.ssl.enabled-protocols` | | `TLSv1.3,TLSv1.2` | TLS 1.2 and 1.3 only |
| `server.ssl.client-auth` | | `want` | Request but do not require a client certificate. Do **not** set `need`: it makes the password form unreachable |
| `server.ssl.key-store` | `CERT_ALERT_KEYSTORE` | empty | e.g. `file:/etc/cert-alert/tls/keystore.p12` |
| `server.ssl.key-store-password` | `CERT_ALERT_KEYSTORE_PASSWORD` | empty | |
| `server.ssl.key-store-type` | `CERT_ALERT_KEYSTORE_TYPE` | `PKCS12` | |
| `server.ssl.trust-store` | `CERT_ALERT_TRUSTSTORE` | empty | CAs whose client certificates are accepted at the handshake. Required with TLS on (the Helm chart refuses to render without one) |
| `server.ssl.trust-store-password` | `CERT_ALERT_TRUSTSTORE_PASSWORD` | empty | |
| `server.ssl.trust-store-type` | `CERT_ALERT_TRUSTSTORE_TYPE` | `PKCS12` | |
| (release package) | `CERT_ALERT_BIND_ADDRESS`, `CERT_ALERT_PORT` | `0.0.0.0`, `8080` | The host interface and port the UI is published on. `127.0.0.1` keeps it to the host, behind a reverse proxy of your own. With TLS off and every interface published, `install.sh` and `upgrade.sh` warn that passwords and cookies would cross the network in clear text |
| `server.forward-headers-strategy` | `SERVER_FORWARD_HEADERS_STRATEGY` | unset | `framework` behind a proxy that terminates TLS and sets `X-Forwarded-*` itself: redirects stay on https, HSTS and the `Secure` cookie follow the browser's request, and the audit trail records the client's address. The Helm chart sets it whenever its ingress or an edge or reencrypt Route terminates TLS. Never `native` with header mode |

## Security headers, banner and sessions

The ENS platform baseline, the same in every ENS product. See
[Security](security.md#security-headers-and-cookies) for what each does.

| Env var | Default | Helm | Description |
|---|---|---|---|
| `ENS_CSP_REPORT_ONLY` | `false` | (`extraEnv`) | `true` sends `Content-Security-Policy-Report-Only` instead of enforcing the policy: an escape hatch for a page the policy breaks, not a way to run |
| `ENS_COOKIE_SECURE` | `false` | (`extraEnv`) | Mark the session cookie `Secure` on every request. Without it, the cookie is `Secure` on any request that arrived over TLS, directly or through a proxy with `SERVER_FORWARD_HEADERS_STRATEGY=framework`. Set it for a proxy that terminates TLS and does not say so |
| `ENS_SESSION_IDLE_TIMEOUT` | `30m` | `session.idleTimeout` | A session nobody has used for this long ends. `SERVER_SERVLET_SESSION_TIMEOUT` still works as the same setting, and wins if both are set |
| `ENS_SESSION_ADMIN_IDLE_TIMEOUT` | empty: the idle timeout | `session.adminIdleTimeout` | The same for an administrator's session. DISA STIG suggests `15m` |
| `ENS_SESSION_ABSOLUTE_TIMEOUT` | `12h` | `session.absoluteTimeout` | A session ends this long after sign-in, however busy |
| `ENS_SESSION_MAX_PER_USER` | `0` (no limit) | `session.maxPerUser` | Sessions one person may hold at once. Signing in beyond it ends their oldest session. DISA STIG suggests `3` |
| `ENS_CONSENT_BANNER_ENABLED` | `false` | `consentBanner.enabled` | Show the DoD notice-and-consent banner before sign-in, enforced by the server |
| `ENS_CONSENT_BANNER_TITLE` | empty: `U.S. Government (USG) Information System (IS) Notice and Consent` | `consentBanner.title` | Its heading |
| `ENS_CONSENT_BANNER_TEXT` | empty: the standard DoD notice-and-consent text | `consentBanner.text` | Plain text: blank lines separate paragraphs, lines starting `- ` make a list. Shown as written, never as markup |
| `ENS_CONSENT_BANNER_FILE` | none | (`extraEnv`, with `extraVolumes`) | Read the text from a file instead; wins over `_TEXT`. Read at startup: a file that can't be read stops CertAlert starting. In the release package, put it in `tls/` (mounted at `/etc/cert-alert/tls`) |

Durations are Spring durations (`15m`, `12h`, `90s`).

## Audit trail

CertAlert keeps the shared `ens-audit/1` trail in the database (`audit_event`). Its outputs and
retention are the `ENS_AUDIT_*` settings in [Audit logging](../audit-logging.md#settings); the
Helm chart's `audit.*` values set them. Two CertAlert settings remain:

| Property | Default | Description |
|---|---|---|
| `cert-alert.audit.enabled` | `true` | `false` stops the records of entry changes only. Security events (sign-ins, refusals, settings, the license, jobs) are always recorded |
| `cert-alert.audit.retention.enabled` | `false` | Prune old events on a schedule, while `ENS_AUDIT_RETENTION_DAYS` is `0` |
| `cert-alert.audit.retention.after` | `365d` | With `retention.enabled`: events older than this are pruned |
| `cert-alert.audit.retention.cron` | `0 30 3 * * *` | Daily, at 03:30 UTC |

`ENS_AUDIT_RETENTION_DAYS` above `0` takes precedence over `retention.enabled` and `after`.
Either way, pruning removes only events every forwarding output has delivered, and never the
newest. CertAlert has no `ENS_AUDIT_QUERY_MAX_SCAN`: the audit page's search runs in the
database, and **Export** takes up to 100,000 events of the current filter.

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
| `management.endpoints.web.exposure.include` | `health,info,metrics,loggers,prometheus` | Exposed actuator endpoints. Prometheus is served only on the management port |
| `management.server.port` (`MANAGEMENT_SERVER_PORT`) | unset | Give the actuator a port of its own, for example `9090`, plain HTTP. There, health and `/actuator/prometheus` are open without signing in. Unset, Prometheus is not served at all and the actuator stays on the application's port. The Helm chart sets it (`metrics.enabled`, default `true`, `metrics.port` `9090`); the release package publishes no port for it |
| `management.endpoint.health.show-details` | `when-authorized` | Health details only for administrators |
| `management.endpoint.health.probes.enabled` | `true` | Enables `/actuator/health/liveness` and `/readiness`, and `/livez` and `/readyz` on the application's port (which stay there when the actuator moves) |
| `management.endpoint.health.group.liveness.include` | `livenessState` | The process alone |
| `management.endpoint.health.group.readiness.include` | `readinessState,db,auditLog` | The database and a writable audit log. Not the directory: add `ldap` (`MANAGEMENT_ENDPOINT_HEALTH_GROUP_READINESS_INCLUDE=readinessState,db,auditLog,ldap`) to make a directory outage take CertAlert out of service |
| `logging.level.com.winllc.certalert` | `INFO` | Application log level |
| `logging.structured.format.console` (`LOGGING_STRUCTURED_FORMAT_CONSOLE`) | unset | `ecs` writes the application log as one ECS JSON object per line. The Helm chart does so by default (`logFormat: json`). The audit trail's stdout lines are raw `ens-audit/1` JSON either way |
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
        admin-identifiers: [alice, bob]
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
    CERT_ALERT_SECURITY_ADMIN_IDENTIFIERS=alice
    CERT_ALERT_SECURITY_LDAP_USER_SEARCH_BASE=ou=people
    CERT_ALERT_LDAP_CHANGELOG_ENABLED=true
    CERT_ALERT_TLS_ENABLED=true
    CERT_ALERT_KEYSTORE=file:/etc/cert-alert/tls/keystore.p12
    CERT_ALERT_KEYSTORE_PASSWORD=...
    CERT_ALERT_TRUSTSTORE=file:/etc/cert-alert/tls/truststore.p12
    CERT_ALERT_TRUSTSTORE_PASSWORD=...
    CERT_ALERT_BANNER_ENABLED=true
    CERT_ALERT_BANNER_TEXT=UNCLASSIFIED
    ENS_CONSENT_BANNER_ENABLED=true
    ENS_SESSION_ADMIN_IDLE_TIMEOUT=15m
    ENS_SESSION_MAX_PER_USER=3
    ENS_X509_REVOCATION=ocsp-then-crl
    ENS_X509_ALLOWED_ISSUERS='CN=DOD ID CA-70,OU=PKI,OU=DoD,O=U.S. Government,C=US'
    ENS_AUDIT_RETENTION_DAYS=400
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
