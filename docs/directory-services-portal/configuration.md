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
    `ADMIN_USERS` and `ADMIN_GROUPS` are separated by `;` (or newlines), because DNs contain
    commas. `X509_TRUSTED_PROXIES` accepts `;`, `,` or whitespace.

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
| `LDAP_BASE_DN` | `portal.base-dn` | `dc=example,dc=com` | Default suffix. Used by the DN browser, and as the user/group search base when those are empty |

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

## Server, sessions and proxy

| Variable | Property | Default | Description |
|---|---|---|---|
| `PORT` | `server.port` | `3001` (`8443` with the `mtls` profile) | HTTP port of the API |
| `SERVER_ADDRESS` | `server.address` | *(all interfaces)* | Standard Spring Boot bind address. The native package sets `127.0.0.1` |
| `SESSION_TTL_MINUTES` | `server.servlet.session.timeout` | `480` | Idle session timeout in minutes |
| `COOKIE_SECURE` | `server.servlet.session.cookie.secure` | `false` | Mark the `DSP_SESSION` cookie `Secure`. Set to `true` whenever browsers reach the portal over HTTPS, including through a proxy |
| `FORWARD_HEADERS_STRATEGY` | `server.forward-headers-strategy` | `none` | `framework` or `native` to honour `X-Forwarded-*` from a reverse proxy. Compose and the packages use `framework`. With X.509 header mode use `framework` or `none`, never `native` |
| `MAX_HTTP_REQUEST_HEADER_SIZE` | `server.max-http-request-header-size` | `32KB` | Largest request header block. Leaves room for a forwarded client certificate and large SSO cookies |
| `DATA_DIR` | `portal.data-dir` | `./data` | Where `portal-config.json`, `audit/` and `analysis-results/` are stored. The container image sets `/data` |
| `SEED_DEMO_CONFIG` | `portal.seed-demo-config` | *(unset → `true` in memory mode, else `false`)* | Seed the example forms, definitions, grants, dashboards and analyses into an **empty** store |
| `SPRING_PROFILES_ACTIVE` | Spring profile | *(none)* | `mtls` makes the API terminate mutual TLS itself (see [below](#api-terminates-mutual-tls-servlet-mode)) |

The session cookie is always named `DSP_SESSION`, `HttpOnly` and `SameSite=Strict`.

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
| `AUDIT_DIR` | `portal.audit.dir` | *(empty → `$DATA_DIR/audit`)* | Where the monthly `audit-YYYY-MM.jsonl` files go |
| `AUDIT_MEMORY_EVENTS` | `portal.audit.memory-events` | `10000` | How many of the newest events **Admin → Audit log** can search. Older events stay in the files |

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
| `X509_CHECK_REVOCATION` | `portal.x509.check-revocation` | `false` | Check OCSP / CRL distribution points during chain validation (needs `X509_TRUSTED_CA_FILE`) |
| `X509_MAPPING` | `portal.x509.mapping` | `filter` | `filter`: search with `X509_USER_FILTER`. `subject-dn`: the certificate subject DN is the user's entry DN |
| `X509_USER_FILTER` | `portal.x509.user-filter` | `(&(objectClass=inetOrgPerson)(uid={{cn}}))` | Placeholders `{{cn}}`, `{{uid}}`, `{{email}}` (rfc822 SAN, else the emailAddress attribute), `{{upn}}` (Microsoft UPN SAN), `{{subject}}`, `{{serial}}`. Values are filter-escaped. It must match exactly one user |
| `X509_REQUIRE_CERTIFICATE_MATCH` | `portal.x509.require-certificate-match` | `false` | Also require the presented certificate to be published in the user's `userCertificate` attribute |
| `X509_AUTO_LOGIN` | `portal.x509.auto-login` | `false` | Sign users in automatically when their browser presents a valid certificate. After an explicit sign-out, automatic sign-in stays off until the user clicks **Sign in with certificate** |

Before a certificate is mapped to a user, the API checks that:

- the certificate is within its validity period
- the certificate is allowed for TLS client authentication (extended key usage / key usage)
- when `X509_TRUSTED_CA_FILE` is set, the certificate chains to one of those CAs, and is not revoked
  when `X509_CHECK_REVOCATION=true`. In header mode this check is required: without
  `X509_TRUSTED_CA_FILE` a forwarded certificate is refused

Group membership, administrator status and permissions work exactly as they do for password sign-ins.

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

Behaviour of the bundled server configuration:

| Setting | Value |
|---|---|
| Listen | `8080` (HTTP); `8443 ssl` in the mTLS template |
| Request body limit | `client_max_body_size 5m` (schema imports) |
| Header buffers | `large_client_header_buffers 4 32k` (matches the API's 32 KB) |
| `/api/` proxy | `proxy_read_timeout 120s`; overwrites `X-Forwarded-For` with `$remote_addr`, sets `X-Forwarded-Proto` and `X-Forwarded-Host`; sets `X-DSP-Proxy-Secret`. On plain HTTP, removes `X-SSL-Client-Cert` and `X509_HEADER` from client requests, unless `X509_EDGE_TERMINATION=true`. In the mTLS template, sets `X509_HEADER` to the verified certificate |
| Security headers | `X-Content-Type-Options nosniff`, `X-Frame-Options DENY`, `Referrer-Policy same-origin`; plus `Strict-Transport-Security max-age=31536000` in the mTLS template |
| Caching | `/assets/` expires after 1 year; the app shell (`/` → `index.html`) is never cached |
| Logs | Access log to stdout, error log to stderr |

The container exits at start with a `dsp-entrypoint:` error when the proxy secret contains other
characters, `X509_PROXY_SECRET_FILE` cannot be read, `X509_HEADER` is not a header name, or
`X509_EDGE_TERMINATION` is neither `true` nor `false`.

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
| `JAVA_TOOL_OPTIONS` | *(empty)* | Extra JVM options for the API |
| `X509_PROXY_SECRET` | 64 hex characters, generated by `install.sh` | The X.509 proxy secret, given to both the API and the web container. To change it, set a new value (at least 32 characters) and run `./portal.sh up` |

The packaged compose file fixes `DATA_DIR=/data`, `PORT=3001` and
`FORWARD_HEADERS_STRATEGY=framework` for the API, and passes everything else from `.env`. The web
container gets `X509_PROXY_SECRET`, `X509_HEADER` and `X509_EDGE_TERMINATION` from `.env`; the mTLS
overlay fixes the last two (`X-SSL-Client-Cert`, `false`).

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

Updates are serialized, applied to a copy, and written atomically (a temporary file plus rename).
If the file can't be parsed at startup, the API fails with `Could not read .../portal-config.json`.

Other files in `DATA_DIR`:

| Path | Content |
|---|---|
| `audit/audit-YYYY-MM.jsonl` | Audit events (unless `AUDIT_DIR` is set) |
| `analysis-results/<analysis-id>.json` | Latest result of each saved analysis |

## Complete example (`.env.example`)

??? example "`.env.example` from the repository"
    ```ini
    DIRECTORY_MODE=ldap
    LDAP_URL=ldap://localhost:389
    LDAP_STARTTLS=false
    LDAP_TLS_REJECT_UNAUTHORIZED=true
    LDAP_TIMEOUT_MS=10000
    LDAP_POOL_SIZE=10
    LDAP_BIND_DN=cn=admin,dc=example,dc=com
    LDAP_BIND_PASSWORD=change-me
    LDAP_BASE_DN=dc=example,dc=com

    LDAP_USER_SEARCH_BASE=ou=people,dc=example,dc=com
    LDAP_USER_FILTER=(&(objectClass=inetOrgPerson)(uid={{username}}))
    LDAP_USERNAME_ATTRIBUTE=uid
    LDAP_GROUP_SEARCH_BASE=ou=groups,dc=example,dc=com
    LDAP_GROUP_FILTER=(|(member={{dn}})(uniqueMember={{dn}}))

    ADMIN_USERS=admin
    ADMIN_GROUPS=cn=directory-admins,ou=groups,dc=example,dc=com

    PORT=3001
    DATA_DIR=./data
    SESSION_TTL_MINUTES=480
    MAX_HTTP_REQUEST_HEADER_SIZE=32KB
    COOKIE_SECURE=true
    FORWARD_HEADERS_STRATEGY=none
    SEED_DEMO_CONFIG=false

    AUDIT_DIR=
    AUDIT_MEMORY_EVENTS=10000

    METRICS_MAX_ENTRIES=100000
    METRICS_CACHE_SECONDS=60
    ANALYSIS_MAX_ISSUES=50000
    METRICS_WAIT_MILLIS=2000

    JOBS_METRICS_THREADS=4
    JOBS_ANALYSIS_THREADS=2
    JOBS_MAX_ACTIVE_PER_USER=5
    JOBS_RETAIN_MINUTES=1440
    LOGIN_HINT=
    BANNER_TEXT=
    BANNER_FOREGROUND=#ffffff
    BANNER_BACKGROUND=#007a33

    PASSWORD_LOGIN_ENABLED=true
    X509_ENABLED=false
    X509_SOURCE=servlet
    X509_HEADER=X-SSL-Client-Cert
    X509_TRUSTED_PROXIES=127.0.0.1/32;::1/128
    X509_PROXY_SECRET_FILE=
    X509_PROXY_SECRET=
    X509_TRUSTED_CA_FILE=
    X509_CHECK_REVOCATION=false
    X509_MAPPING=filter
    X509_USER_FILTER=(&(objectClass=inetOrgPerson)(uid={{cn}}))
    X509_REQUIRE_CERTIFICATE_MATCH=false
    X509_AUTO_LOGIN=false
    ```
