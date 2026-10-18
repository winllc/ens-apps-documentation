# Configuration

The API's defaults are packaged in its `application.yml`. You change them with environment
variables. The tables below list each environment variable, the Spring property
it maps to, its default and its meaning. Defaults are the values used when the variable is unset.

Where to set them:

| Deployment | File |
|---|---|
| Docker deployment package | `.env` next to the compose files, applied with `./portal.sh up` |
| Native package | `/etc/directory-services-portal/portal.env`, applied with `systemctl restart directory-services-portal` |
| Helm chart | Your values file, applied with `helm upgrade`. Most settings have a named value (see [Installation → Key values](install.md#key-values) and the chart's `README.md`); any other goes in `api.extraEnv` as its environment variable |

!!! note "Lists of DNs"
    `ADMIN_USERS`, `ADMIN_GROUPS` and `ENS_X509_ALLOWED_ISSUERS` are separated by `;` (or
    newlines), because DNs contain commas. `X509_TRUSTED_PROXIES` accepts `;`, `,` or whitespace.

!!! note "Durations"
    Settings whose default is written like `30m` or `12h` take a Spring Boot duration: `90s`,
    `30m`, `1h`, `12h`. A plain number means milliseconds, so always give the unit.

## Directory connection

| Variable | Property | Default | Description |
|---|---|---|---|
| `DIRECTORY_MODE` | `portal.directory-mode` | `memory` | `memory` starts the embedded demo LDAP server; `ldap` connects to `LDAP_URL` |
| `LDAP_URL` | `portal.ldap.url` | `ldap://localhost:389` | `ldap://` or `ldaps://` URL of the directory server |
| `LDAP_STARTTLS` | `portal.ldap.start-tls` | `false` | Upgrade plain `ldap://` connections with StartTLS |
| `LDAP_TLS_REJECT_UNAUTHORIZED` | `portal.ldap.tls-reject-unauthorized` | `true` | Verify the server certificate against the JVM trust store and check the host name. `false` trusts any certificate (testing only) |
| `LDAP_TIMEOUT_MS` | `portal.ldap.timeout-ms` | `10000` | Connect and response timeout in milliseconds |
| `LDAP_POOL_SIZE` | `portal.ldap.pool-size` | `10` | Maximum connections in the service-account pool |
| `LDAP_BIND_DN` | `portal.ldap.bind-dn` | *(empty)* | Service account used for every read and write. The portal enforces its own permissions on top |
| `LDAP_BIND_PASSWORD` | `portal.ldap.bind-password` | *(empty)* | Password of the service account |
| `LDAP_BIND_PASSWORD_FILE` | `portal.ldap.bind-password-file` | *(empty)* | A file holding the password. When set and not empty, it is read instead of `LDAP_BIND_PASSWORD`. Preferred: anyone who can inspect the process or container can read its environment. The Helm chart always uses it; the native package sets `/etc/directory-services-portal/ldap-bind-password` |
| `LDAP_BASE_DN` | `portal.base-dn` | `dc=example,dc=com` | Default suffix. Used by the DN browser, and as the user/group search base when those are empty |

`LDAP_TLS_REJECT_UNAUTHORIZED=false` is not appropriate for FIPS or DISA STIG deployments. The API
logs a warning at startup while it is set.

!!! info "Memory mode"
    In `memory` mode, the `LDAP_URL`, bind DN and password are ignored. The API binds to the
    embedded server with its own service account, forces the base DN to `dc=example,dc=com`,
    defaults `ADMIN_USERS` to `admin` and `ADMIN_GROUPS` to
    `cn=directory-admins,ou=groups,dc=example,dc=com`, and shows a demo login hint unless you
    set `LOGIN_HINT`.

### LDAPS with a private CA

Certificates are verified against the **JVM default trust store**. For a private CA, provide a
trust store that holds every CA the API must trust:

=== "Docker package"

    Put the trust store in the data volume and point Java at it in `.env`:

    ```ini
    JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStore=/data/truststore.p12 -Djavax.net.ssl.trustStorePassword=changeit
    ```

=== "Native package"

    Put the trust store where user `dsp` can read it, and set in `portal.env`:

    ```ini
    JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStore=/etc/directory-services-portal/truststore.p12 -Djavax.net.ssl.trustStorePassword=changeit
    ```

=== "Helm"

    Put the trust store in a Secret and set `ldap.truststore.existingSecret` (and the password's
    Secret in `ldap.truststore.password`). See [Installation → Secrets](install.md#secrets).

`JAVA_TOOL_OPTIONS` is listed in the package `env.template` and can also carry heap settings such as `-Xmx2g`.
The container image starts Java with `-XX:MaxRAMPercentage=75.0`.

## Users, groups and administrators

| Variable | Property | Default | Description |
|---|---|---|---|
| `LDAP_USER_SEARCH_BASE` | `portal.user-search-base` | *(empty → `LDAP_BASE_DN`)* | Where users are searched at sign-in (subtree) |
| `LDAP_USER_FILTER` | `portal.user-filter` | `(&(objectClass=inetOrgPerson)(uid={{username}}))` | Filter to find the user. `{{username}}` is replaced with the escaped login name. Exactly one match is required |
| `LDAP_USERNAME_ATTRIBUTE` | `portal.username-attribute` | `uid` | Login name attribute (`uid`, `sAMAccountName`, ...). Its value becomes the canonical username |
| `LDAP_GROUP_SEARCH_BASE` | `portal.group-search-base` | *(empty → `LDAP_BASE_DN`)* | Where groups are searched (subtree) |
| `LDAP_GROUP_FILTER` | `portal.group-filter` | `(|(member={{dn}})(uniqueMember={{dn}}))` | Group lookup, with placeholders `{{dn}}` and `{{username}}`. `memberOf` on the user entry is always honoured as well. Empty disables the search |
| `ADMIN_USERS` | `portal.admin-users` | *(empty)* | Usernames that are portal administrators (`;` separated, case-insensitive) |
| `ADMIN_GROUPS` | `portal.admin-groups` | *(empty)* | Group DNs whose members are portal administrators (`;` separated) |

Users sign in with their own directory password. The portal finds their DN with the service
account, then verifies the password with an LDAP bind as that DN. Login names may contain only
letters, digits and `. _ @ + -` (at most 128 characters). Empty passwords are rejected before any
bind.

=== "OpenLDAP / 389-DS"

    ```ini
    LDAP_USER_SEARCH_BASE=ou=people,dc=example,dc=com
    LDAP_USER_FILTER=(&(objectClass=inetOrgPerson)(uid={{username}}))
    LDAP_USERNAME_ATTRIBUTE=uid
    LDAP_GROUP_SEARCH_BASE=ou=groups,dc=example,dc=com
    LDAP_GROUP_FILTER=(|(member={{dn}})(uniqueMember={{dn}}))
    ```

=== "Active Directory"

    ```ini
    LDAP_URL=ldaps://dc1.corp.example.com:636
    LDAP_BASE_DN=dc=corp,dc=example,dc=com
    LDAP_USER_FILTER=(&(objectClass=user)(sAMAccountName={{username}}))
    LDAP_USERNAME_ATTRIBUTE=sAMAccountName
    LDAP_GROUP_FILTER=(member={{dn}})
    ```
    This is an illustration built from the documented placeholders. The portal also reads
    `memberOf` from the user entry.

## Server and proxy

| Variable | Property | Default | Description |
|---|---|---|---|
| `PORT` | `server.port` | `3001` (`8443` with the `mtls` profile) | HTTP port of the API |
| `SERVER_ADDRESS` | `server.address` | *(all interfaces)* | Standard Spring Boot bind address. The native package sets `127.0.0.1` |
| `ENS_COOKIE_SECURE` | `ens.cookie.secure` | `false` | Always mark the `DSP_SESSION` cookie `Secure`. Set to `true` whenever browsers reach the portal over HTTPS through a TLS-terminating proxy. Over direct TLS (the `mtls` profile) the cookie is `Secure` anyway. `COOKIE_SECURE` is the older name and still works |
| `SERVER_FORWARD_HEADERS_STRATEGY` | `server.forward-headers-strategy` | `none` | Spring Boot's own setting. `framework` honours `X-Forwarded-*` from a reverse proxy that overwrites them; the compose files, the native package and the Helm chart set it. With X.509 header mode use `framework` or `none`, never `native`. `FORWARD_HEADERS_STRATEGY` is the older name and still works |
| `ENS_CSP_REPORT_ONLY` | `ens.csp.report-only` | `false` | `true` sends `Content-Security-Policy-Report-Only` instead of `Content-Security-Policy`. Set it on the API **and** the web tier. An escape hatch for a page the policy breaks; report the page and turn it off again |
| `MAX_HTTP_REQUEST_HEADER_SIZE` | `server.max-http-request-header-size` | `32KB` | Largest request header block. Leaves room for a forwarded client certificate and large SSO cookies |
| `DATA_DIR` | `portal.data-dir` | `./data` | Where `portal-config.json`, `audit/` and `analysis-results/` are stored. The container image sets `/data` |
| `SEED_DEMO_CONFIG` | `portal.seed-demo-config` | *(unset → `true` in memory mode, else `false`)* | Seed the example forms, definitions, grants, dashboards and analyses into an **empty** store |
| `SPRING_PROFILES_ACTIVE` | Spring profile | *(none)* | `mtls` makes the API terminate mutual TLS itself (see [below](#api-terminates-mutual-tls-servlet-mode)) |

The session cookie is always named `DSP_SESSION`, and is `HttpOnly` and `SameSite=Strict`. The
platform baseline asks for `Lax`; the portal keeps `Strict` because browsers only ever reach the
API through the same origin.

## Sessions and sign-in lockout

| Variable | Property | Default | Description |
|---|---|---|---|
| `ENS_SESSION_IDLE_TIMEOUT` | `ens.session.idle-timeout` | `30m` | Idle timeout. `SESSION_TTL_MINUTES` (a number of minutes), the older name, is used when this is not set |
| `ENS_SESSION_ADMIN_IDLE_TIMEOUT` | `ens.session.admin-idle-timeout` | *(empty: the idle timeout)* | A shorter idle timeout for administrators. DISA STIG suggests `15m` |
| `ENS_SESSION_ABSOLUTE_TIMEOUT` | `ens.session.absolute-timeout` | `12h` | A session ends this long after sign-in, whatever its activity |
| `ENS_SESSION_MAX_PER_USER` | `ens.session.max-per-user` | `0` (no limit) | Concurrent sessions per user. Signing in once more ends the **oldest** session. DISA STIG suggests `3` |
| `GROUP_REFRESH_INTERVAL` | `portal.group-refresh-interval` | `5m` | How often a signed-in user's groups and administrator status are read again from the directory, checked on their next request. `0`: only at sign-in. If the directory can't be reached, the session keeps its groups; if the account no longer exists, its sessions end |
| `LOGIN_MAX_FAILURES` | `portal.login.max-failures` | `8` | Failed password sign-ins for one user name from one client address before further attempts are refused (HTTP 429, audited as `auth.lockout`). DISA STIG suggests `3` |
| `LOGIN_LOCKOUT_WINDOW` | `portal.login.lockout-window` | `15m` | The window the failures are counted in, from the first failure. The refusal lasts until the window ends |

- **Idle time is what the user does.** The web client marks its background polling (task progress,
  license and audit status) with the header `X-DSP-Background: 1`, which does not count as activity.
  An open but unattended tab therefore still times out.
- **Expiry.** An expired session answers `401` with `"code": "session_expired"`, and the browser
  shows the sign-in page with the reason. Each expiry is audited as `auth.session-expired` with the
  reason `idle`, `absolute`, `concurrent` or `account-removed`. A session ended by the concurrent
  limit gets a plain 401 on its next request.
- **Lockout counters are held in memory.** A restart clears them.

!!! warning "The idle timeout default changed from 480 minutes to 30 minutes"
    - **New installs** get `30m`.
    - **Docker package upgrades** keep their `.env`. One that still sets `SESSION_TTL_MINUTES=480`
      keeps 480 minutes until you replace it with `ENS_SESSION_IDLE_TIMEOUT`.
    - **Helm upgrades** that never set `server.sessionTtlMinutes` change to `30m`. A release that
      set it keeps its value: the deprecated value still wins over `server.session.idleTimeout`.

## Notice and consent banner

For DoD systems (DISA STIG), the portal can show the standard USG notice-and-consent banner before
sign-in.

| Variable | Property | Default | Description |
|---|---|---|---|
| `ENS_CONSENT_BANNER_ENABLED` | `ens.consent-banner.enabled` | `false` | Show and enforce the banner |
| `ENS_CONSENT_BANNER_TITLE` | `ens.consent-banner.title` | *(empty: `U.S. Government (USG) Information System (IS) Notice and Consent`)* | Banner title |
| `ENS_CONSENT_BANNER_TEXT` | `ens.consent-banner.text` | *(empty: the standard DoD notice-and-consent text)* | Plain text. Blank lines separate paragraphs; paragraphs starting with `- ` are list items |
| `ENS_CONSENT_BANNER_FILE` | `ens.consent-banner.file` | *(empty)* | Read the text from this file instead; it wins over `ENS_CONSENT_BANNER_TEXT`. The API does not start if it can't read the file |

The banner is enforced by the API, not just shown by the web client:

- While it is enabled, no sign-in of any kind succeeds (password, certificate or certificate
  auto-login) until this browser session has acknowledged the banner with **I agree**. A sign-in
  without it is refused with `403` and `"code": "consent_required"`, and audited as an
  `auth.login` denial with `details.reasonCode` `consent_required`.
- The acknowledgement is kept in the session and audited as `auth.consent-acknowledged`. Signing
  out ends the session, so the banner comes before every sign-in.
- `POST /api/auth/consent` and the banner text in `/api/info` are reachable without signing in.
- Exempt, because they are not browser sessions: the audit pull API (`/api/audit/**`, bearer
  tokens), the health probes (`/actuator/health/**`, `/livez`, `/readyz`, `/api/health`) and the
  Prometheus endpoint on the management port.

## Security headers

The web tier (nginx) sends these headers on the client's pages and in place of the API's own on
`/api`, so each header appears once. The API sends the same set on its own responses, for anyone
who reaches it directly.

| Header | Value |
|---|---|
| `Content-Security-Policy` | `default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'; object-src 'none'`. `Content-Security-Policy-Report-Only` instead with `ENS_CSP_REPORT_ONLY=true` |
| `Strict-Transport-Security` | `max-age=31536000; includeSubDomains`. nginx: always with the mTLS template, and with the plain-HTTP template when a proxy in front sends `X-Forwarded-Proto: https`. API: on secure requests (direct TLS, or behind a proxy with `SERVER_FORWARD_HEADERS_STRATEGY=framework`) |
| `X-Content-Type-Options` | `nosniff` |
| `X-Frame-Options` | `DENY` |
| `Referrer-Policy` | `strict-origin-when-cross-origin` |
| `Permissions-Policy` | `camera=(), microphone=(), geolocation=(), payment=()` |

The web client has no inline scripts or event handlers, so the policy needs no nonce.

## Health, metrics and logs

| Variable | Default | Description |
|---|---|---|
| `MANAGEMENT_SERVER_PORT` | *(unset)* | Spring Boot's own setting. A separate port for the health probes and Prometheus (`/actuator/prometheus`), both open without sign-in. Unset: there is no Prometheus endpoint, and the probes are on the main port. Never publish this port to users |
| `MANAGEMENT_SERVER_ADDRESS` | *(all interfaces; the native package sets `127.0.0.1`)* | Spring Boot's own setting: the address the management port listens on |
| `LOGGING_STRUCTURED_FORMAT_CONSOLE` | *(unset: text)* | Spring Boot's own setting. `ecs` writes log lines as JSON in the Elastic Common Schema. The Helm chart sets it by default (`logFormat: json`). Audit events on stdout stay `ens-audit/1` JSON lines either way |

The probes and the metric names are listed in the
[Operator guide](operator-guide.md#health-checks).

## Login page and banners

| Variable | Property | Default | Description |
|---|---|---|---|
| `PASSWORD_LOGIN_ENABLED` | `portal.password-login-enabled` | `true` | Allow username/password sign-in. `false` makes the portal certificate-only |
| `LOGIN_HINT` | `portal.login-hint` | *(empty)* | Optional text on the login page (help-desk contact, demo credentials, ...) |
| `BANNER_TEXT` | `portal.banner.text` | *(empty: off)* | Fixed banner at the top and bottom of every page, including the login page. At most 200 characters |
| `BANNER_FOREGROUND` | `portal.banner.foreground` | `#ffffff` | Banner text color |
| `BANNER_BACKGROUND` | `portal.banner.background` | `#007a33` | Banner background color |

Colors can be `#rgb`, `#rgba`, `#rrggbb`, `#rrggbbaa`, `rgb()`/`rgba()`/`hsl()`/`hsla()` or a CSS
color name. Any other value, or a banner text longer than 200 characters, **stops the API at
startup** with `Invalid banner configuration: ...` naming the setting.

```ini
BANNER_TEXT="UNCLASSIFIED"
BANNER_FOREGROUND="#ffffff"
BANNER_BACKGROUND="#007a33"
```

## Audit log

| Variable | Property | Default | Description |
|---|---|---|---|
| `AUDIT_DIR` | `portal.audit.dir` | *(empty → `$DATA_DIR/audit`)* | Where the audit store goes: hash-chained `ens-audit/1` files, one per month (`audit-YYYY-MM.jsonl`) |
| `ENS_AUDIT_RETENTION_DAYS` | `ens.audit.retention-days` | `0` (keep everything) | Days of audit history to keep. A daily job removes older events that every enabled output has delivered, never the newest, and records `audit.pruned` |
| `ENS_AUDIT_QUERY_MAX_SCAN` | `ens.audit.query-max-scan` | `2000000` | Most events one search or export on the audit page reads. The page says when a search stopped at this limit |
| `AUDIT_MEMORY_EVENTS` | `portal.audit.memory-events` | `10000` | **No longer used.** Accepted so that existing `.env` files and Helm values (`audit.memoryEvents`) keep working. The audit page searches all retained history |

The other audit settings (`ENS_AUDIT_STDOUT`, `ENS_AUDIT_FILE`, Splunk HEC and syslog) are the same
in every ENS application. They are described in [Audit logging](../audit-logging.md#settings).

## Metrics and data analysis

| Variable | Property | Default | Description |
|---|---|---|---|
| `METRICS_MAX_ENTRIES` | `portal.metrics.max-entries` | `100000` | Most entries one chart or analysis source reads. Larger results are marked partial/truncated |
| `METRICS_CACHE_SECONDS` | `portal.metrics.cache-seconds` | `60` | How long computed charts are reused. `0` disables the cache; **Refresh** always recomputes |
| `METRICS_WAIT_MILLIS` | `portal.metrics.wait-millis` | `2000` | Metrics that take longer continue as a background task and notify when ready |
| `ANALYSIS_MAX_ISSUES` | `portal.metrics.max-issues` | `50000` | Most individual findings an analysis run keeps. Counts always cover every entry |

## Background tasks

| Variable | Property | Default | Description |
|---|---|---|---|
| `JOBS_METRICS_THREADS` | `portal.jobs.metrics-threads` | `4` | Worker threads for background metrics |
| `JOBS_ANALYSIS_THREADS` | `portal.jobs.analysis-threads` | `2` | Worker threads for analyses (separate pool, so a long analysis never holds up dashboards) |
| `JOBS_MAX_ACTIVE_PER_USER` | `portal.jobs.max-active-per-user` | `5` | Tasks one user may have running or queued at once |
| `JOBS_RETAIN_MINUTES` | `portal.jobs.retain-minutes` | `1440` | How long finished tasks and their results are kept in memory |
| *(none in `application.yml`)* | `portal.jobs.max-jobs` | `500` | Upper bound on tasks kept in memory (minimum 50). Can be set through Spring's relaxed binding, for example `PORTAL_JOBS_MAX_JOBS` |

## X.509 client-certificate sign-in

| Variable | Property | Default | Description |
|---|---|---|---|
| `X509_ENABLED` | `portal.x509.enabled` | `false` (`true` with the `mtls` profile) | Offer certificate sign-in |
| `X509_SOURCE` | `portal.x509.source` | `servlet` | `servlet`: the API terminates mutual TLS. `header`: a trusted reverse proxy terminates it and forwards the certificate |
| `X509_HEADER` | `portal.x509.header` | `X-SSL-Client-Cert` | Header with the client certificate in header mode. Accepts PEM, URL-encoded PEM (nginx `$ssl_client_escaped_cert`, AWS ALB), base64 DER and Envoy `x-forwarded-client-cert` |
| `X509_TRUSTED_PROXIES` | `portal.x509.trusted-proxies` | `127.0.0.1/32;::1/128` | Addresses/CIDRs allowed to send the header. Checked against the actual TCP peer, never `X-Forwarded-For`. The header is refused from any other peer, and from these too unless the request carries the proxy secret. Refusals are logged and audited |
| `X509_PROXY_SECRET_FILE` | `portal.x509.proxy-secret-file` | *(empty)* | File holding the proxy secret. When set, it is read instead of `X509_PROXY_SECRET` (preferred) |
| `X509_PROXY_SECRET` | `portal.x509.proxy-secret` | *(empty)* | Header mode: the secret the portal's web tier sends in `X-DSP-Proxy-Secret` with every request. At least 32 characters (for example `openssl rand -hex 32`), the same value for the API and the web tier. The certificate header is refused without it |
| `X509_TRUSTED_CA_FILE` | `portal.x509.trusted-ca-file` | *(empty)*; with `mtls`: `TLS_CLIENT_CA_FILE` | PEM bundle of CAs. When set, the API validates the chain itself. Required in header mode |
| `X509_CHECK_REVOCATION` | `portal.x509.check-revocation` | `false` | **Deprecated**; use `ENS_X509_REVOCATION`. `true` means `ocsp-then-crl`, always failing closed, even while `ENS_X509_REVOCATION` is `off` |
| `X509_MAPPING` | `portal.x509.mapping` | `filter` | `filter`: search with `X509_USER_FILTER`. `subject-dn`: the certificate subject DN is the user's entry DN |
| `X509_USER_FILTER` | `portal.x509.user-filter` | `(&(objectClass=inetOrgPerson)(uid={{cn}}))` | The search that finds the user. Placeholders are in the table below. Values are filter-escaped. It must match exactly one user: none, or more than one, signs nobody in |
| `X509_REQUIRE_CERTIFICATE_MATCH` | `portal.x509.require-certificate-match` | `false` | Also require the presented certificate to be published in the user's `userCertificate` attribute |
| `X509_AUTO_LOGIN` | `portal.x509.auto-login` | `false` | Sign users in automatically when their browser presents a valid certificate. After an explicit sign-out, automatic sign-in stays off until the user clicks **Sign in with certificate** |
| `ENS_X509_ALLOWED_ISSUERS` | `ens.x509.allowed-issuers` | *(empty: any trusted issuer)* | Issuer DNs separated by `;`. Certificates from other issuers are refused, even if a trusted CA chains to them |

Placeholders in `X509_USER_FILTER`. Each can be written `{{name}}` or, as in the other ENS
applications, `{name}`:

| Placeholder | Value |
|---|---|
| `cn`, `uid` | The subject's CN and UID |
| `email` | The rfc822Name SAN, else the subject's emailAddress |
| `upn` | The SAN otherName UserPrincipalName (OID 1.3.6.1.4.1.311.20.2.3), as on CAC/PIV cards |
| `edipi` | The DoD EDIPI: the digits before `@` in a UPN such as `1234567890@mil`, else the trailing `.1234567890` of a CN such as `LAST.FIRST.MI.1234567890` |
| `uuid` | From a SAN URI `urn:uuid:…` (the PIV FASC-N UUID), without the `urn:uuid:` prefix |
| `subject`, `serial` | The full subject DN, and the serial number |

Examples: `(uid={{cn}})`, `(mail={email})`, `(userPrincipalName={upn})`,
`(&(objectClass=user)(employeeID={edipi}))`.

Before a certificate is mapped to a user, the API checks that:

- the certificate is within its validity period
- the certificate is allowed for TLS client authentication (extended key usage / key usage)
- when `ENS_X509_ALLOWED_ISSUERS` is set, its issuer is one of those DNs
- when `X509_TRUSTED_CA_FILE` is set, the certificate chains to one of those CAs, and is not revoked
  (see [Revocation checking](#revocation-checking)). In header mode this check is required: without
  `X509_TRUSTED_CA_FILE` a forwarded certificate is refused

Group membership, administrator status and permissions work exactly as they do for password sign-ins.
The license must include `smart-card-sign-in`; without it the login page does not offer
certificate sign-in.

### Revocation checking

DISA STIG requires revocation checking for CAC/PIV sign-in. The default stays `off` so that
upgrades don't change behaviour, but while certificate sign-in is enabled with revocation off, the
API logs a warning at startup.

| Variable | Property | Default | Description |
|---|---|---|---|
| `ENS_X509_REVOCATION` | `ens.x509.revocation` | `off` | `off`, `ocsp`, `crl` or `ocsp-then-crl` (CRLs when OCSP gives no answer). Needs `X509_TRUSTED_CA_FILE`: the API does not start without it |
| `ENS_X509_OCSP_RESPONDER_URL` | `ens.x509.ocsp-responder-url` | *(empty: the certificate's AIA responder)* | Ask this responder instead, for example a local OCSP relay on an air-gapped network |
| `ENS_X509_CRL_DIR` | `ens.x509.crl-dir` | *(empty)* | A directory of CRL files (DER or PEM), read again when they change. The air-gapped option: with it set, CRL distribution points are **not** fetched |
| `ENS_X509_REVOCATION_FAIL` | `ens.x509.revocation-fail` | `closed` | `closed`: when the status can't be determined, refuse the sign-in. `open`: allow it and log a warning |
| `ENS_X509_REVOCATION_CACHE` | `ens.x509.revocation-cache` | `10m` | How long a good or revoked answer is kept, per issuer and serial |

How the checks work:

- They are configured per sign-in: the JDK's PKIX validator runs with its own revocation checker.
  No global JDK security property is changed.
- OCSP responses must be signed by the issuing CA or a responder it delegated to, and CRLs by the
  issuing CA. There is no setting to trust a relay that signs responses with its own certificate.
- The API fetches OCSP responses and CRLs itself, over http or https only: 5 s to connect, 10 s to
  read, at most 3 redirects (to http or https only), OCSP responses up to 1 MB and CRLs up to 32 MB.
  It never connects to link-local, multicast or cloud-metadata addresses. LDAP distribution points
  are skipped. Fetched CRLs are reused until their next update, at most a day.
- A refusal is audited as an `auth.login` failure with the reason `certificate revoked` or
  `revocation unknown` (`details.reasonCode` `certificate_revoked` or `revocation_unknown`).
- With the Helm chart and `networkPolicy.enabled`, the API's egress to OCSP responders and CRL
  servers is opened by `x509.revocation.egress` (ports 80 and 443 by default).

### Proxy terminates mutual TLS (header mode, recommended)

Browsers connect to the web tier, so this is how certificate sign-in works with the separate web
and API services. The bundled `templates-mtls` nginx configuration:

- listens with HTTPS on 8443 and redirects plain HTTP on 8080 to it
- requests a certificate with `ssl_verify_client optional` (depth 3, TLS 1.2/1.3), so password
  sign-in keeps working
- forwards `$ssl_client_escaped_cert` in `X509_HEADER` **only** when verification succeeded and
  **only** to `/api/auth/x509` and `/api/auth/me`. Every other request gets an empty header
- always overwrites any client-supplied copy of that header, and removes `X-SSL-Client-Cert` when
  it is not the header in use
- sends `X-DSP-Proxy-Secret` with the proxy secret on every request to the API, replacing any value
  a client sent

```ini
X509_ENABLED=true
X509_SOURCE=header
X509_HEADER=X-SSL-Client-Cert
X509_TRUSTED_PROXIES=10.0.0.0/8;172.16.0.0/12;192.168.0.0/16   # compose network
X509_PROXY_SECRET_FILE=/run/secrets/dsp-proxy-secret            # or X509_PROXY_SECRET
X509_TRUSTED_CA_FILE=/certs/ca.pem
X509_USER_FILTER=(&(objectClass=inetOrgPerson)(uid={{cn}}))
FORWARD_HEADERS_STRATEGY=framework
COOKIE_SECURE=true
```

The web tier must have the same proxy secret as the API (see [Web tier](#web-tier-nginx)). In
header mode the API refuses to start without the proxy secret or `X509_TRUSTED_CA_FILE`, with an
error that starts `Refusing to start: certificate sign-in through a proxy (X509_ENABLED=true,
X509_SOURCE=header) is not secure as configured:` and names what is missing. A proxy other than the
bundled nginx must send `X-DSP-Proxy-Secret` with every request to the API, and remove any copy of
it and of the certificate header that a client sends.

### API terminates mutual TLS (servlet mode)

Activate the `mtls` Spring profile (`application-mtls.yml`) and provide PEM files:

| Variable | Description |
|---|---|
| `TLS_CERT_FILE` | Server certificate (chain) |
| `TLS_KEY_FILE` | Server private key |
| `TLS_CLIENT_CA_FILE` | CA certificate(s) that issue user certificates |

The profile enables TLS on `PORT` (default **8443**) with `client-auth: want`, forces the cookie to
`Secure`, sets `X509_ENABLED` to `true` by default, and validates chains again in the portal
against `TLS_CLIENT_CA_FILE` (which also enables optional revocation checking).

For example, in the native package's `portal.env` (the files must be readable by user `dsp`):

```ini
SPRING_PROFILES_ACTIVE=mtls
TLS_CERT_FILE=/etc/directory-services-portal/certs/server.pem
TLS_KEY_FILE=/etc/directory-services-portal/certs/server.key
TLS_CLIENT_CA_FILE=/etc/directory-services-portal/certs/ca.pem
```

The Docker package (`install.sh --https`) and the Helm chart (`tls.enabled`) use the web-tier setup
above instead. In the native package, the nginx site proxies plain HTTP to the API on
`127.0.0.1:3001`, so change or remove that site when the API serves TLS itself.

Use servlet mode when clients connect to the API directly. A browser goes through it only if the
web tier passes TLS straight through to the API.

!!! note "TLS 1.3"
    With TLS 1.3, Java cannot request a client certificate after the handshake. The certificate
    must be presented when the connection is established, which is how `client-auth: want`
    works. Tomcat logs a warning about this at startup.

## Web tier (nginx)

The `web` image renders `${NGINX_ENVSUBST_TEMPLATE_DIR}/*.template` into
`/opt/app-root/etc/dsp.d/` at start. Only `${API_URL}`, `${HTTPS_PORT_SUFFIX}`,
`${NGINX_LOCAL_RESOLVERS}` and the proxy headers built from the X.509 settings below are
substituted. The rendered files hold the proxy secret, so only their owner can read them.

| Variable | Default | Description |
|---|---|---|
| `API_URL` | `http://api:3001` | Where `/api/` is forwarded (`scheme://host:port`, no path). Resolved at request time, so the web container starts even if the API isn't up yet |
| `NGINX_ENVSUBST_TEMPLATE_DIR` | `/etc/nginx/templates` | `/etc/nginx/templates-mtls` for HTTPS with X.509 client certificates |
| `HTTPS_PORT_SUFFIX` | *(empty)* | Port in HTTP→HTTPS redirects, for example `:8443`; empty for 443 |
| `NGINX_LOCAL_RESOLVERS` | First `nameserver` in `/etc/resolv.conf` | DNS server used to resolve `API_URL` (Docker `127.0.0.11`, Podman aardvark-dns). IPv6 addresses go in brackets. The container exits if none is found |
| `X509_PROXY_SECRET_FILE` / `X509_PROXY_SECRET` | *(empty)* | The proxy secret, the same value as the API's; the file is preferred. Sent to the API in `X-DSP-Proxy-Secret`. Letters, digits and `. _ ~ + / = -` only. Empty: `X-DSP-Proxy-Secret` is removed |
| `X509_HEADER` | `X-SSL-Client-Cert` | The header the API reads the client certificate from (letters, digits and `-`) |
| `X509_EDGE_TERMINATION` | `false` | `true` when a TLS proxy in front of the web tier verifies client certificates and always sets `X509_HEADER` itself: plain HTTP then passes that header on. Nothing else may then reach the web tier |
| `ENS_CSP_REPORT_ONLY` | `false` | `true` sends `Content-Security-Policy-Report-Only` instead of `Content-Security-Policy`. Set the same value on the API |

Behaviour of the bundled server configuration:

| Setting | Value |
|---|---|
| Listen | `8080` (HTTP); `8443 ssl` in the mTLS template |
| Request body limit | `client_max_body_size 5m` (schema imports) |
| Header buffers | `large_client_header_buffers 4 32k` (matches the API's 32 KB) |
| `/api/` proxy | `proxy_read_timeout 120s`; overwrites `X-Forwarded-For` with `$remote_addr`, sets `X-Forwarded-Proto` and `X-Forwarded-Host`; sets `X-DSP-Proxy-Secret`. On plain HTTP, removes `X-SSL-Client-Cert` and `X509_HEADER` from client requests, unless `X509_EDGE_TERMINATION=true`. In the mTLS template, sets `X509_HEADER` to the verified certificate |
| Security headers | The set in [Security headers](#security-headers). On `/api/` the API's copies are hidden, so each header appears once |
| Caching | `/assets/` expires after 1 year; the app shell (`/` → `index.html`) is never cached |
| Logs | Access log to stdout, error log to stderr |

The container exits at start with a `dsp-entrypoint:` error when the proxy secret contains other
characters, `X509_PROXY_SECRET_FILE` cannot be read, `X509_HEADER` is not a header name, or
`X509_EDGE_TERMINATION` or `ENS_CSP_REPORT_ONLY` is neither `true` nor `false`.

!!! tip "Kubernetes"
    nginx doesn't apply DNS search domains, so give `API_URL` a fully qualified service name
    (for example `http://dsp-api.portal.svc.cluster.local:3001`).

### Docker package deployment settings (`.env`)

| Variable | Default (from installer) | Description |
|---|---|---|
| `COMPOSE_FILE` | `docker-compose.yml` plus overlays chosen by `install.sh` | `:`-separated compose files: `docker-compose.publish.yml`, `docker-compose.mtls.yml`, `docker-compose.openldap.yml` |
| `COMPOSE_PROJECT_NAME` | `directory-services-portal` | Compose project (and volume name prefix) |
| `API_IMAGE`, `WEB_IMAGE`, `OPENLDAP_IMAGE` | Package image names | Images to run |
| `WEB_BIND` | `0.0.0.0` | Host address to publish on |
| `WEB_PORT` | `8080` | HTTP port (publish overlay) |
| `HTTPS_PORT` | `8443` | HTTPS port (mTLS overlay) |
| `HTTPS_PORT_SUFFIX` | `:<HTTPS_PORT>`, empty for 443 | Port shown in redirects |
| `API_MEMORY_LIMIT` | `1536m` | Memory limit of the API container. The JVM's heap is 75% of it (`-XX:MaxRAMPercentage=75.0`) |
| `WEB_MEMORY_LIMIT` | `256m` | Memory limit of the web container |
| `JAVA_TOOL_OPTIONS` | *(empty)* | Extra JVM options for the API |
| `X509_PROXY_SECRET` | 64 hex characters, generated by `install.sh` | The X.509 proxy secret, given to both the API and the web container. To change it, set a new value (at least 32 characters) and run `./portal.sh up` |

The packaged compose file fixes `DATA_DIR=/data`, `PORT=3001` and
`SERVER_FORWARD_HEADERS_STRATEGY=framework` for the API, and passes everything else from `.env`.
The web container gets `ENS_CSP_REPORT_ONLY`, `X509_PROXY_SECRET`, `X509_HEADER` and
`X509_EDGE_TERMINATION` from `.env`; the mTLS overlay fixes the last two (`X-SSL-Client-Cert`,
`false`).

Both containers run with all capabilities dropped, `no-new-privileges`, a read-only root file
system (with tmpfs for the paths they write) and the memory limits above. The Docker package does
not use `LDAP_BIND_PASSWORD_FILE`: the bind password is in `.env`, which `install.sh` writes with
mode `600`.

## Configuration store

The portal's own configuration is not set through environment variables. Administrators edit it in
the UI, and it is persisted as one JSON document: `$DATA_DIR/portal-config.json`.

| Key | Content |
|---|---|
| `version` | Store format version (`1`) |
| `customSchema` | Attribute types and object classes defined or overridden in the portal |
| `schemaCache` | The last schema pulled from the directory |
| `forms` | Form definitions |
| `definitions` | Directory definitions |
| `grants` | Permission grants |
| `dashboards` | Metric dashboards |
| `analyses` | Saved analyses, including a summary of their last run |

The API reads the file once, at startup, and keeps it in memory. Updates are serialized, applied to
a copy, and written atomically (a temporary file plus rename). If the file can't be parsed at
startup, the API fails with `Could not read .../portal-config.json`. Because the API never reads the
file again while it runs, only one API process may use a data directory; see
[High availability](availability-and-sizing.md#high-availability).

Other files in `DATA_DIR`:

| Path | Content |
|---|---|
| `audit/audit-YYYY-MM.jsonl` | The audit store: hash-chained `ens-audit/1` events, one file per month (unless `AUDIT_DIR` is set) |
| `audit/audit-state.json` | How far each audit output (Splunk HEC, syslog) has delivered |
| `audit/audit-api-tokens.json` | The pull API's tokens, stored as SHA-256 hashes |
| `audit/legacy/` | Audit files in the portal's earlier format, moved here unchanged when the store was upgraded (only on installations that had them) |
| `analysis-results/<analysis-id>.json` | Latest result of each saved analysis |

## Complete example (`.env.example`)

The repository's `.env.example`, for running the API from the source tree. The Docker package's
`.env` (written from its `env.template`) and the native package's `portal.env` list the same
settings with comments, plus the deployment settings above.

??? example "`.env.example` from the repository"
    ```ini
    # --- Directory connection -------------------------------------------------
    # "memory" (default) runs the embedded demo LDAP server; "ldap" connects to a real server.
    DIRECTORY_MODE=ldap
    LDAP_URL=ldap://localhost:389
    LDAP_STARTTLS=false
    LDAP_TLS_REJECT_UNAUTHORIZED=true
    LDAP_TIMEOUT_MS=10000
    LDAP_POOL_SIZE=10
    # Service account used for all directory operations (the portal enforces its own permissions).
    LDAP_BIND_DN=cn=admin,dc=example,dc=com
    LDAP_BIND_PASSWORD=change-me
    LDAP_BASE_DN=dc=example,dc=com

    # --- Users & groups --------------------------------------------------------
    LDAP_USER_SEARCH_BASE=ou=people,dc=example,dc=com
    # {{username}} is replaced with the (escaped) login name.
    LDAP_USER_FILTER=(&(objectClass=inetOrgPerson)(uid={{username}}))
    LDAP_USERNAME_ATTRIBUTE=uid
    LDAP_GROUP_SEARCH_BASE=ou=groups,dc=example,dc=com
    # {{dn}} / {{username}} placeholders. memberOf on the user entry is also honoured.
    LDAP_GROUP_FILTER=(|(member={{dn}})(uniqueMember={{dn}}))

    # Portal administrators. Separate multiple values with ";" (DNs contain commas).
    ADMIN_USERS=admin
    ADMIN_GROUPS=cn=directory-admins,ou=groups,dc=example,dc=com

    # --- Server ----------------------------------------------------------------
    PORT=3001
    DATA_DIR=./data
    # Sessions: idle timeout, administrators' idle timeout (empty: the same), absolute lifetime,
    # concurrent sessions per user (0: no limit). SESSION_TTL_MINUTES is the older idle setting.
    ENS_SESSION_IDLE_TIMEOUT=30m
    ENS_SESSION_ADMIN_IDLE_TIMEOUT=
    ENS_SESSION_ABSOLUTE_TIMEOUT=12h
    ENS_SESSION_MAX_PER_USER=0
    GROUP_REFRESH_INTERVAL=5m
    LOGIN_MAX_FAILURES=8
    LOGIN_LOCKOUT_WINDOW=15m
    # Largest request header block the API accepts (forwarded client certificate + cookies).
    MAX_HTTP_REQUEST_HEADER_SIZE=32KB
    # Always mark the session cookie Secure (TLS terminated at a proxy); COOKIE_SECURE is the older name.
    ENS_COOKIE_SECURE=true
    # "true": Content-Security-Policy-Report-Only instead of Content-Security-Policy (escape hatch).
    ENS_CSP_REPORT_ONLY=false
    # "framework" behind a reverse proxy that overwrites X-Forwarded-* (Spring Boot's own setting;
    # FORWARD_HEADERS_STRATEGY is the older name).
    SERVER_FORWARD_HEADERS_STRATEGY=none
    # Seed example forms/definitions on first start (defaults to true only in memory mode).
    SEED_DEMO_CONFIG=false

    # --- Audit log ------------------------------------------------------------------
    # The audit store: hash-chained ens-audit/1 JSONL files, one per month. Empty: $DATA_DIR/audit.
    # (AUDIT_MEMORY_EVENTS is accepted but no longer used.)
    AUDIT_DIR=
    AUDIT_MEMORY_EVENTS=10000
    # Each event as one raw JSON line on stdout (Docker logging drivers, journald).
    ENS_AUDIT_STDOUT=true
    # Also write a rolling file (daily or at 100 MB), e.g. for the Splunk Universal Forwarder.
    # Empty: off.
    #ENS_AUDIT_FILE=/var/log/ens/directory-portal/audit.jsonl
    ENS_AUDIT_FILE=
    # Days of rolled files to keep.
    ENS_AUDIT_FILE_KEEP=30
    # Days of audit history to keep; 0 keeps everything. Only events every enabled output has
    # delivered are pruned (daily), never the newest.
    ENS_AUDIT_RETENTION_DAYS=0
    # Splunk HTTP Event Collector, e.g. https://splunk.example:8088 (empty: off). Give the token here
    # or in a file (ENS_AUDIT_SPLUNK_TOKEN_FILE).
    ENS_AUDIT_SPLUNK_URL=
    ENS_AUDIT_SPLUNK_TOKEN=
    ENS_AUDIT_SPLUNK_TOKEN_FILE=
    ENS_AUDIT_SPLUNK_INDEX=
    ENS_AUDIT_SPLUNK_SOURCETYPE=ens:audit
    # true skips the HEC certificate check: test setups only (a warning is logged at startup).
    ENS_AUDIT_SPLUNK_INSECURE=false
    # Syslog (RFC 5424, octet-counted over tcp/tls), e.g. siem.example (empty: off).
    ENS_AUDIT_SYSLOG_HOST=
    # Default 514 (udp, tcp) or 6514 (tls).
    ENS_AUDIT_SYSLOG_PORT=
    # udp, tcp or tls.
    ENS_AUDIT_SYSLOG_PROTOCOL=tcp
    ENS_AUDIT_SYSLOG_FACILITY=local0

    # --- Metrics and data analysis ------------------------------------------------
    # Most entries one chart or analysis source reads; larger results are marked partial.
    METRICS_MAX_ENTRIES=100000
    # How long computed charts are reused (0 disables the cache; Refresh always recomputes).
    METRICS_CACHE_SECONDS=60
    # Most individual findings an analysis run keeps; they are paged on the server (the counts always cover every entry).
    ANALYSIS_MAX_ISSUES=50000
    # Metrics taking longer than this continue in the background and notify when ready.
    METRICS_WAIT_MILLIS=2000

    # --- Background tasks (analyses, slow metrics) -----------------------------------
    JOBS_METRICS_THREADS=4
    JOBS_ANALYSIS_THREADS=2
    # Tasks one user may have running or queued at once.
    JOBS_MAX_ACTIVE_PER_USER=5
    # How long finished tasks and their results are kept.
    JOBS_RETAIN_MINUTES=1440
    # Optional text on the login page (demo credentials, help-desk contact, ...).
    LOGIN_HINT=
    # Optional fixed banner at the top and bottom of every page (e.g. a classification or
    # environment marking). Off while BANNER_TEXT is empty. Colors: #hex, rgb()/hsl() or a CSS name.
    BANNER_TEXT=
    BANNER_FOREGROUND=#ffffff
    BANNER_BACKGROUND=#007a33

    # --- X.509 client-certificate sign-in --------------------------------------
    PASSWORD_LOGIN_ENABLED=true
    X509_ENABLED=false
    # "servlet": the portal terminates mutual TLS (SPRING_PROFILES_ACTIVE=mtls with TLS_CERT_FILE,
    #            TLS_KEY_FILE, TLS_CLIENT_CA_FILE);
    # "header":  a reverse proxy terminates mutual TLS and forwards the certificate.
    X509_SOURCE=servlet
    X509_HEADER=X-SSL-Client-Cert
    # Only these peers may send the certificate header (";" separated addresses or CIDRs).
    X509_TRUSTED_PROXIES=127.0.0.1/32;::1/128
    # Header mode: the secret the proxy sends in X-DSP-Proxy-Secret with every request (at least 32
    # characters, e.g. openssl rand -hex 32). The header is refused without it. Prefer the file.
    X509_PROXY_SECRET_FILE=
    X509_PROXY_SECRET=
    # PEM bundle of CAs the portal validates chains against (required in header mode).
    X509_TRUSTED_CA_FILE=
    X509_CHECK_REVOCATION=false
    # "filter" (search with X509_USER_FILTER) or "subject-dn" (subject DN is the user's entry DN).
    X509_MAPPING=filter
    # Placeholders: {{cn}} {{uid}} {{email}} {{upn}} {{subject}} {{serial}}
    X509_USER_FILTER=(&(objectClass=inetOrgPerson)(uid={{cn}}))
    # Require the certificate to be published in the user's userCertificate attribute.
    X509_REQUIRE_CERTIFICATE_MATCH=false
    X509_AUTO_LOGIN=false

    # --- ENS Solutions license --------------------------------------------------
    # Without a license only sign-in and the license screen (Administration > License) work, in demo
    # mode (DIRECTORY_MODE=memory) too. Upload the license there; it is written to ENS_LICENSE_FILE.
    # ENS_LICENSE_STATE_DIR keeps this installation's id (to bind a license to) and must be writable
    # and persistent. Defaults: /etc/ens/directory-portal.lic and /var/lib/ens/directory-portal; the
    # container image uses /var/lib/ens/directory-portal for both, and `npm run dev` backend/data/ens-license.
    # Environment variables only (not Spring properties).
    ENS_LICENSE_STATE_DIR=./data/ens-license
    ENS_LICENSE_FILE=./data/ens-license/license.lic
    ```
