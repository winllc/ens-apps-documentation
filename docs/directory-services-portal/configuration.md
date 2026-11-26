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
| `DIRECTORY_MODE` | `portal.directory-mode` | `memory` | `memory` starts the embedded demo LDAP server; `ldap` connects to `LDAP_URL`. From v0.0.4 `memory` starts only with `DEMO_MODE=true` or an API that listens on a loopback address only (`SERVER_ADDRESS=127.0.0.1`); otherwise the API refuses to start and logs why at ERROR. A bare jar or image run with the default must set `DIRECTORY_MODE=ldap` or `DEMO_MODE=true` |
| `DEMO_MODE` (v0.0.4) | `portal.demo-mode` | `false` | `true` says this installation is a demo: the built-in directory's credentials (`admin`/`password`) are public. Required for `DIRECTORY_MODE=memory`. `install.sh --demo`, `docker-compose.demo.yml` and the chart with `directory.mode: memory` set it; the package's `env.template` has `DEMO_MODE=false` |
| `LDAP_URL` | `portal.ldap.url` | `ldap://localhost:389` | `ldap://` or `ldaps://` URL of the directory server. From v0.0.6, several servers of the same directory (replicas), separated by commas or spaces: they are tried in order, the first that answers is used, and the pool goes back to the first once it answers again. Failover needs no license feature |
| `LDAP_SERVER_SELECTION` (v0.0.6) | `portal.ldap.server-selection` | `failover` | `failover` (the servers of `LDAP_URL` in order) or `round-robin` (in turn; every URL must then use the same scheme) |
| `DIRECTORY_NAME` (v0.0.6) | `portal.directory-name` | `Directory` | The directory's display name; its id is `default`. See [Several directories](#several-directories) |
| `LDAP_STARTTLS` | `portal.ldap.start-tls` | `false` | Upgrade plain `ldap://` connections with StartTLS |
| `LDAP_TLS_REJECT_UNAUTHORIZED` | `portal.ldap.tls-reject-unauthorized` | `true` | Verify the server certificate and check the host name, for `ldaps://` and StartTLS alike. The certificate must chain to a CA in the trust store given to the JVM, or in the JDK's own `cacerts` when none is given; see [LDAPS with a private CA](#ldaps-with-a-private-ca). `false` trusts any certificate (testing only) |
| `LDAP_TIMEOUT_MS` | `portal.ldap.timeout-ms` | `10000` | Connect and response timeout in milliseconds. From v0.0.9, while the directory can't be reached, requests don't each wait for it: after a failed attempt to connect, every call to that directory fails at once with the same error for 5 seconds, then one request tries again while the others still fail at once |
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

For `ldaps://` and StartTLS, the directory server's certificate must chain to a CA the JVM trusts:
the CAs in the **trust store given to the JVM** (`-Djavax.net.ssl.trustStore`), or the JDK's own
`cacerts` when none is given. A directory whose certificate comes from a private (corporate) CA
needs a trust store that holds that CA.

!!! warning "Before v0.0.10 the given trust store was not used for LDAPS"
    Up to v0.0.9 the API checked directory certificates against the JDK's `cacerts` only, whatever
    `-Djavax.net.ssl.trustStore` (or the Helm chart's `ldap.truststore`) said, so a directory whose
    CA was only in that trust store failed with `certificate_unknown` / `PKIX path building
    failed`. Upgrade to v0.0.10 or later; nothing else changes, and the settings below stay the same.

**1. Build the trust store** with `keytool`, one `-importcert` per CA (the issuing CA and its
root; with [several directories](#several-directories), every directory's CA, because one trust
store serves them all):

```bash
keytool -importcert -noprompt -alias corp-ca -file corp-ca.pem \
  -keystore truststore.p12 -storetype PKCS12 -storepass '<store-password>'
keytool -list -keystore truststore.p12 -storepass '<store-password>'
# corp-ca, ..., trustedCertEntry,
```

Each CA must be listed as a `trustedCertEntry`. Don't build the file with
`openssl pkcs12 -export -nokeys`: Java opens such a file but finds no trusted certificate in it.
The trust store **replaces** the JDK's `cacerts`: if the API also reaches servers with public
certificates over TLS (Splunk HEC, a SAML metadata URL, an OIDC provider, webhook receivers), add
their CAs too.

**2. Give it to the JVM, with its password.** A PKCS12 trust store read without its password
opens, but empty.

=== "Docker package"

    Put the trust store in the data volume and point Java at it in `.env`, then `./portal.sh up`:

    ```ini
    JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStore=/data/truststore.p12 -Djavax.net.ssl.trustStorePassword=<store-password>
    ```

=== "Native package"

    Put the trust store where user `dsp` can read it, set in `portal.env`, and restart the
    service:

    ```ini
    JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStore=/etc/directory-services-portal/truststore.p12 -Djavax.net.ssl.trustStorePassword=<store-password>
    ```

=== "Helm"

    Put the trust store in a Secret and set `ldap.truststore.existingSecret`, and the password
    in `ldap.truststore.password` (`.value`, or `.existingSecret` and `.existingSecretKey`). The
    chart mounts the file and passes `-Djavax.net.ssl.trustStore`, `-Djavax.net.ssl.trustStoreType`
    (`ldap.truststore.type`, `PKCS12`) and the password to the JVM in `JAVA_OPTS`. See
    [Installation → Secrets](install.md#secrets).

`JAVA_TOOL_OPTIONS` is listed in the package `env.template` and can also carry heap settings such as `-Xmx2g`.
The JVM prints its value in the API log at startup (`Picked up JAVA_TOOL_OPTIONS: ...`), the
trust store's password included; a trust store holds only CA certificates, which are public.
The container image starts Java with `-XX:MaxRAMPercentage=75.0`.

If the directory still isn't reached, the error (in the API log, and for administrators on
*Admin → LDAP directories* and in `error` of `/api/info`) says which step is wrong: see
[Operator guide → LDAPS certificate errors](operator-guide.md#ldaps-certificate-errors).

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

## Several directories (v0.0.6) { #several-directories }

One portal can serve several LDAP directories (several forests, a directory per enclave). It needs
the license feature `multi-directory` (Enterprise); without it only the first directory is used and
the others are listed as disabled. The older `LDAP_*` settings above still work unchanged: they
describe one directory, with the id `default`.

Each directory is a numbered block of environment variables, `PORTAL_DIRECTORIES_<n>_<SETTING>`
with `n` from 0 (or the list `portal.directories` in a Spring configuration file). The first is
the **primary** directory. When the list is set, `LDAP_URL`, `LDAP_BIND_*`, `LDAP_BASE_DN`, the user
and group settings and `ADMIN_USERS` / `ADMIN_GROUPS` are not used. Settings a block leaves out take
the same defaults as the `LDAP_*` settings.

| Setting (`PORTAL_DIRECTORIES_0_...`) | Property (`portal.directories[0].`) | Default | Description |
|---|---|---|---|
| `ID` | `id` | *(required)* | 1-32 lower-case letters, digits, `-` and `_`, unique. It appears in URLs, audit records and webhook payloads, so don't change it once used. `default` is the id stored on everything saved before; when no directory has that id it stands for the first |
| `NAME` | `name` | the id | Shown on the login page and to administrators |
| `URLS` | `urls` | *(required)* | One or more `ldap://` / `ldaps://` URLs of the same directory, separated by commas or spaces (a YAML list in a file) |
| `SERVERSELECTION` | `server-selection` | `failover` | `failover` or `round-robin` |
| `BINDDN` | `bind-dn` | *(empty)* | Service account |
| `BINDPASSWORD`, `BINDPASSWORDFILE` | `bind-password`, `bind-password-file` | *(empty)* | Its password, or (preferred) a file holding it |
| `STARTTLS`, `TLSREJECTUNAUTHORIZED` | `start-tls`, `tls-reject-unauthorized` | `false`, `true` | As `LDAP_STARTTLS`, `LDAP_TLS_REJECT_UNAUTHORIZED` |
| `TIMEOUTMS`, `POOLSIZE` | `timeout-ms`, `pool-size` | `10000`, `10` | As `LDAP_TIMEOUT_MS`, `LDAP_POOL_SIZE`; each directory has its own pool |
| `BASEDN` | `base-dn` | `dc=example,dc=com` | As `LDAP_BASE_DN` |
| `USERSEARCHBASE`, `USERFILTER`, `USERNAMEATTRIBUTE` | `user-search-base`, `user-filter`, `username-attribute` | as the `LDAP_*` defaults | How users are found at sign-in |
| `GROUPSEARCHBASE`, `GROUPFILTER` | `group-search-base`, `group-filter` | as the `LDAP_*` defaults | How their groups are found |
| `ADMINUSERS`, `ADMINGROUPS` | `admin-users`, `admin-groups` | *(empty)* | This directory's users and groups who administer the (whole) portal, `;` separated |
| `X509`, `X509USERFILTER` | `x509`, `x509-user-filter` | `true`, *(`X509_USER_FILTER`)* | Whether certificate sign-in looks for users here, and with which filter |
| `SSO`, `SSOUSERFILTER` | `sso`, `sso-user-filter` | `true`, *(the provider's)* | Whether single sign-on looks for users here, and with which filter (must contain `{{value}}`) |

```ini
PORTAL_DIRECTORIES_0_ID=default
PORTAL_DIRECTORIES_0_NAME=Corporate
PORTAL_DIRECTORIES_0_URLS=ldaps://dc1.corp.example.com:636,ldaps://dc2.corp.example.com:636
PORTAL_DIRECTORIES_0_BINDDN=CN=svc-portal,OU=Service,DC=corp,DC=example,DC=com
PORTAL_DIRECTORIES_0_BINDPASSWORDFILE=/run/secrets/corp-bind-password
PORTAL_DIRECTORIES_0_BASEDN=DC=corp,DC=example,DC=com
PORTAL_DIRECTORIES_0_USERFILTER=(&(objectClass=user)(sAMAccountName={{username}}))
PORTAL_DIRECTORIES_0_USERNAMEATTRIBUTE=sAMAccountName
PORTAL_DIRECTORIES_0_ADMINGROUPS=CN=Portal Admins,OU=Groups,DC=corp,DC=example,DC=com
PORTAL_DIRECTORIES_1_ID=enclave-b
PORTAL_DIRECTORIES_1_NAME=Enclave B
PORTAL_DIRECTORIES_1_URLS=ldaps://ldap1.b.example.com ldaps://ldap2.b.example.com
PORTAL_DIRECTORIES_1_BINDDN=cn=portal,ou=system,o=enclave-b
PORTAL_DIRECTORIES_1_BINDPASSWORDFILE=/run/secrets/enclave-b-bind-password
PORTAL_DIRECTORIES_1_BASEDN=o=enclave-b
PORTAL_DIRECTORIES_1_X509=false
```

The API checks the list at startup and **refuses to start** when an id is missing, malformed or used
twice, a directory has no URL, a URL is not `ldap://` or `ldaps://`, round robin mixes schemes, a
filter doesn't parse or a password file can't be read. The message names the setting, never a
password. It logs each directory (id, name, servers, failover or round robin) at startup, and a WARN
for each directory the license leaves disabled.

What belongs to which directory:

- Every **directory definition** belongs to one directory, chosen in the definition editor; all
  reading and writing through it happens there.
- A **signed-in user** is a directory plus a DN: the same user name in two directories is two
  people. With several enabled directories the login page asks which one; see
  [User guide → Signing in](user-guide.md#signing-in).
- **Grants** name the directory of the user or group they grant to (the definition's unless the
  administrator picks another). **Administrators** come from each directory's `ADMINUSERS` /
  `ADMINGROUPS` and administer the whole portal.
- **Certificate sign-in and single sign-on** search every enabled directory whose `X509` / `SSO`
  setting is on, in configured order. The first directory with a matching account wins and must
  hold exactly one (two there refuse the sign-in). If later directories also match, the user signs
  in to the first; the API logs a WARN naming the others and the sign-in's audit record lists them
  in `details.alsoMatched`. Reorder the directories, turn a directory's setting off, or narrow its
  filter to change which one wins.
- Forms, the custom schema, dashboards and analyses are shared; each directory's server schema is
  read and cached separately.

**Moving from `LDAP_*` to the list:** copy the settings into block 0 and give it the id `default`
(any id works: `default` then stands for the first directory). Existing definitions and grants keep
working.

## Server and proxy

| Variable | Property | Default | Description |
|---|---|---|---|
| `PORT` | `server.port` | `3001` (`8443` with the `mtls` profile) | HTTP port of the API |
| `SERVER_ADDRESS` | `server.address` | *(all interfaces)* | Standard Spring Boot bind address. The native package sets `127.0.0.1` |
| `ENS_COOKIE_SECURE` | `ens.cookie.secure` | `false` | Always mark the `DSP_SESSION` cookie `Secure`. Set to `true` whenever browsers reach the portal over HTTPS through a TLS-terminating proxy. Over direct TLS (the `mtls` profile) the cookie is `Secure` anyway. `COOKIE_SECURE` is the older name and still works |
| `SERVER_FORWARD_HEADERS_STRATEGY` | `server.forward-headers-strategy` | `none` | Spring Boot's own setting. `framework` honours `X-Forwarded-*` from a reverse proxy that overwrites them; the compose files, the native package and the Helm chart set it. With X.509 header mode use `framework` or `none`, never `native`. `FORWARD_HEADERS_STRATEGY` is the older name and still works. With `none` behind a proxy, the per-address limits (sign-in lockout, bad-token and single sign-on start limits) see only the proxy's address, so one client can get everybody behind it refused. From v0.0.4 the API logs a startup WARN when it is evidently behind a proxy (`X509_SOURCE=header`, or `SERVER_ADDRESS` on loopback) while this is `none`. A proxy in front of the web tier hides the clients the same way unless it is listed in `WEB_TRUSTED_PROXIES` |
| `WEB_TRUSTED_PROXIES` (v0.0.9) | *(environment only)* | *(empty)* | A setting of the [web tier](#web-tier-nginx): the addresses or CIDRs of the proxies in front of it whose `X-Forwarded-For` names the client. The API reads it only to decide whether to warn: while single sign-on is on and it is unset, the API logs a startup WARN, because behind such a proxy every user would share one per-address limit. Give it to the API and the web tier alike (the packages and the chart do) |
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
| `GROUP_REFRESH_INTERVAL` | `portal.group-refresh-interval` | `5m` | How often a signed-in user's groups and administrator status are read again from the directory, checked on their next request. `0`: only at sign-in. If the directory can't be reached, the session keeps its groups; if the account no longer exists, its sessions end. From v0.0.9 they also end when the directory marks the account disabled or locked (`nsAccountLock: true`, Active Directory's ACCOUNTDISABLE flag in `userAccountControl`, or `pwdAccountLockedTime`), as for [API tokens](api-reference.md#api-tokens): within this interval, on the user's next request |
| `LOGIN_MAX_FAILURES` | `portal.login.max-failures` | `8` | Failed password sign-ins for one user name from one client address before further attempts are refused (HTTP 429, audited as `auth.lockout`). DISA STIG suggests `3` |
| `LOGIN_LOCKOUT_WINDOW` | `portal.login.lockout-window` | `15m` | The window the failures are counted in, from the first failure. The refusal lasts until the window ends |

- **Idle time is what the user does.** The web client marks its background polling (task progress,
  license and audit status) with the header `X-DSP-Background: 1`, which does not count as activity.
  An open but unattended tab therefore still times out.
- **Expiry.** An expired session answers `401` with `"code": "session_expired"`, and the browser
  shows the sign-in page with the reason. Each expiry is audited as `auth.session-expired` with the
  reason `idle`, `absolute`, `concurrent`, `account-removed` or (from v0.0.9) `account-disabled`. A
  session ended by the concurrent limit gets a plain 401 on its next request.
- **Lockout counters are held in memory** without a shared database, so a restart clears them.
  With `DSP_DATABASE_URL` they are in the database and count across instances, like the sessions.

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
| `METRICS_CACHE_SECONDS` | `portal.metrics.cache-seconds` | `60` | How long the server reuses computed charts. `0` disables the cache; **Refresh** always recomputes. From v0.0.11 the web client keeps the results of the metrics home page, the overviews and the dashboards itself until the user clicks **Refresh** (or reloads the browser page), so for those pages this setting only matters after a reload; the explorer and the dashboard editor's preview use it as before |
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

With the shared database, from v0.0.9, the tasks of an API instance that stops (an upgrade, a crash)
are marked failed about 30 seconds later ("The server running it stopped before it finished") and
no longer count against `JOBS_MAX_ACTIVE_PER_USER`. See
[High availability](availability-and-sizing.md#with-a-shared-database-several-api-instances).

## Single sign-on

OpenID Connect and SAML 2.0, each off until configured: OIDC turns on with `SSO_OIDC_ISSUER_URI` and
`SSO_OIDC_CLIENT_ID`, SAML with `SSO_SAML_METADATA`. `PORTAL_PUBLIC_URL` (property
`portal.sso.public-url`) is required for SAML and recommended for OIDC; give the web tier
`PUBLIC_HOST` too. `PASSWORD_LOGIN_ENABLED=false` leaves only single sign-on and certificates.
Every `SSO_*` variable, the addresses to register and provider walkthroughs are in
[Single sign-on](single-sign-on.md#settings). v0.0.4 adds `SSO_START_LIMIT_PER_MINUTE`
(`30`; `600` from v0.0.9) and `SSO_MAX_PENDING_REQUESTS` (`10000`), and warns at startup about an
`SSO_OIDC_USERNAME_CLAIM` that a provider lets be changed; with the shared database the start
limit is counted across replicas. It also adds `SSO_OIDC_PROVIDER_LOGOUT` and
`SSO_SAML_SINGLE_LOGOUT` (sign out at the provider too; `false` unless set, `true` in new
installations); see [Single sign-on → Settings](single-sign-on.md#settings) and
[Signing out](single-sign-on.md#signing-out). v0.0.9 adds `SSO_SAML_METADATA_REFRESH` (`1h`): how
long the identity provider's SAML metadata is used before it is read again.

## Integration API, webhooks, import and export

| Variable | Property | Default | Description |
|---|---|---|---|
| `API_TOKEN_MAX_DAYS` | `portal.integration.api.max-token-days` | `365` | The longest lifetime an administrator may give an API token |
| `API_RATE_LIMIT_PER_MINUTE` | `portal.integration.api.rate-limit-per-minute` | `120` | Requests per minute per token, unless the token sets its own; counted per API replica |
| `API_FAILED_TOKEN_LIMIT_PER_MINUTE` | `portal.integration.api.failed-token-limit-per-minute` | `30` | Bad tokens one client address may present a minute before getting 429. From v0.0.4 counted across replicas with the shared database (`dsp_rate_limit`) |
| `DSP_SECRETS_KEY`, `DSP_SECRETS_KEY_FILE` (v0.0.4) | `portal.secrets.key`, `portal.secrets.key-file` | empty; new installations get one | The key webhook signing secrets are stored encrypted with: 32 random bytes in base64 (`openssl rand -base64 32`). Every replica needs the same key; keep a copy apart from your backups. See [Webhooks → Encrypted signing secrets](webhooks.md#encrypted-signing-secrets) |
| `WEBHOOKS_ENABLED` | `portal.integration.webhooks.enabled` | `true` | `false` stops sending and creating webhooks. Deliveries already queued stay queued. From v0.0.9, changes made while it is `false` are not queued (they are never sent, also not after it is turned on again), and finished deliveries are still removed after `WEBHOOK_LOG_RETENTION` |
| `WEBHOOK_ALLOWED_HOSTS` | `portal.integration.webhooks.allowed-hosts` | *(empty)* | Host names (or `*.example.com`, `;`-separated) whose webhooks may reach internal addresses |
| `WEBHOOK_MAX_ATTEMPTS` | `portal.integration.webhooks.max-attempts` | `8` | Attempts per delivery |
| `WEBHOOK_CONNECT_TIMEOUT`, `WEBHOOK_READ_TIMEOUT`, `WEBHOOK_SEND_TIMEOUT` | `portal.integration.webhooks.*` | `5s`, `10s`, `20s` | Per attempt; the send timeout bounds the whole attempt |
| `WEBHOOK_RUN_BUDGET` | `portal.integration.webhooks.run-budget` | `40s` | How long one delivery run starts attempts for |
| `WEBHOOK_LOG_RETENTION` | `portal.integration.webhooks.log-retention` | `30d` | How long finished deliveries are kept |
| `EXPORT_MAX_ENTRIES` | `portal.integration.transfer.export-max-entries` | `10000` | The most entries one export holds |
| `IMPORT_MAX_BYTES` | `portal.integration.transfer.import-max-bytes` | `5242880` | The largest import file; raise the web tier's `MAX_BODY_SIZE` with it |
| `IMPORT_MAX_ROWS` | `portal.integration.transfer.import-max-rows` | `5000` | The most rows one import holds |
| `BULK_MAX_MEMBERS` | `portal.integration.transfer.bulk-max-members` | `5000` | The most members one bulk change adds and removes |

Helm: `integrations.api.*`, `integrations.webhooks.*` and `integrations.transfer.*`. See the
[API reference](api-reference.md#integration-api-apiv1), [Webhooks](webhooks.md) and
[Import and export](import-export.md).

## Entry expiry (next release) { #entry-expiry }

For entries that end on a date, such as contractors and guests. In the form, give the end
date field the format **Date** (`yyyy-mm-dd`; a generalized time such as `20261231000000Z` is
read too), and have a field holding the sponsor's DN (the format **Distinguished name**, or a
directory drop down of DNs). On the directory definition, under **Entry expiry**, choose:

| Setting | Default | What |
|---|---|---|
| End date field | *(off)* | The form's date field |
| Sponsor field | | The form's DN field naming the sponsor |
| Days of warning | `14` | From this many days before the end, the sponsor is told daily |
| Longest extension | `180` | A sponsor may set the end date at most this many days from today |

Each day at 06:07 UTC, on one replica, the sponsor of every entry ending within the days of
warning is mailed, the [`entry.expiring`](webhooks.md) webhook event is sent, and the notice is
audited as `entry.expiring`. Sponsors find their entries under **Sponsored entries** and move
the end date out, never in and no further than the longest extension, without write access to
the definition; the change is audited as `entry.update` with `details.via =
"sponsor-renewal"`.

Mail needs an SMTP server:

| Variable | Default | Description |
|---|---|---|
| `SMTP_HOST` | *(empty: no mail)* | The mail server; without it, notices are only audited and sent as webhook events |
| `SMTP_PORT` | `587` | |
| `SMTP_USERNAME`, `SMTP_PASSWORD` | *(empty)* | When the server needs a sign-in |
| `SMTP_STARTTLS` | `true` | |
| `MAIL_FROM` | `directory-portal@localhost` | The sender |

With the Helm chart, set them in `api.extraEnv`. The mail server is not part of the portal's
health checks.

## Governed mode (next release) { #governed-mode }

With the ENS identity governance console (syncd's console, see
[syncd Governance](../syncd/governance.md#access-requests)) deciding who holds which group,
the portal hands its group requests to the console's approval workflows instead of the
groups' owners.

| Variable | Property | Default | Description |
|---|---|---|---|
| `GOVERNANCE_URL` | `portal.governance.url` | *(empty: off)* | The console's address, e.g. `https://syncd.example.com` |
| `GOVERNANCE_TOKEN`, `GOVERNANCE_TOKEN_FILE` | `portal.governance.token`, `.token-file` | *(empty)* | A console API token with the scope `governance:delegate` |
| `GOVERNANCE_SYSTEMS` | `portal.governance.systems` | *(empty)* | `dir-a=<system id>;dir-b=<system id>`, or `*=<system id>`: the governance system each directory's groups belong to. A directory not listed stays ungoverned |
| `GOVERNANCE_PORTAL_URL` | `portal.governance.portal-url` | `GOVERNANCE_URL` | Where people follow their requests in the console |

Helm: `integrations.governance.url`, `.portalUrl`, `.systems` (a map) and `.token`
(`value`, or `existingSecret` and `existingSecretKey`).

In a governed directory:

- A request is made in the console on the user's behalf (their login name must be their
  login in the console's identity source), and the portal keeps its record with the
  console's request id. The `group.access-requested` audit event says `governed` and
  `governanceRequest`.
- Owners' approve and reject answer 409 with a link to the governance portal; governed
  requests are not listed under *To review*.
- Groups the console does not let people ask for (not requestable, or computed by a syncd
  rule) are closed to requests. Lookups are cached for five minutes; when the console cannot
  be reached, groups show as closed.
- *My requests* mirrors each open governed request's state from the console and links to it.
  A governed request is withdrawn in the governance portal.
- Owners still manage members directly. To report those changes to the console, add a
  [webhook](webhooks.md) for `group.membership_changed` to the console's
  `/api/v1/governance/ingest/dsp`, with the secret the console gives when the portal is added
  under *Governance → Workflows*.

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
`${NGINX_LOCAL_RESOLVERS}`, the proxy headers built from the X.509 settings below and the
directives built from `WEB_TRUSTED_PROXIES` are substituted. The rendered files hold the proxy secret, so only their owner can read them.

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
| `MAX_BODY_SIZE` | `5m` | The largest request body (schema and entry imports), an nginx size such as `10m`. Raise it with the API's `IMPORT_MAX_BYTES`. The container exits if it is not a size |
| `PUBLIC_HOST` | *(empty)* | The host[:port] users open (the host of `PORTAL_PUBLIC_URL`), sent to the API as `X-Forwarded-Host` instead of the browser's `Host`. Set it with single sign-on. The container exits if it is not a host name with an optional port |
| `WEB_TRUSTED_PROXIES` (v0.0.9) | *(empty)* | Addresses or CIDRs, separated by commas or spaces, of the proxies **in front of the web tier** (an ingress controller's pod CIDR, a load balancer, a TLS proxy on the Docker host as the web container sees it) whose `X-Forwarded-For` names the client. nginx then takes the client's address from that header (`set_real_ip_from`, `real_ip_header X-Forwarded-For`, `real_ip_recursive on`), for its access log and for what it forwards to the API. See [Behind another proxy](#behind-another-proxy) |

Behaviour of the bundled server configuration:

| Setting | Value |
|---|---|
| Listen | `8080` (HTTP); `8443 ssl` in the mTLS template |
| Request body limit | `client_max_body_size` from `MAX_BODY_SIZE` (default `5m`; schema and entry imports). The native package's nginx site keeps `5m`; edit it there |
| Header buffers | `large_client_header_buffers 4 32k` (matches the API's 32 KB) |
| `/api/` proxy | `proxy_read_timeout 120s`; overwrites `X-Forwarded-For` with `$remote_addr` (the connecting peer, or behind a proxy listed in `WEB_TRUSTED_PROXIES` the client that proxy names), sets `X-Forwarded-Proto` and `X-Forwarded-Host`; sets `X-DSP-Proxy-Secret`. On plain HTTP, removes `X-SSL-Client-Cert` and `X509_HEADER` from client requests, unless `X509_EDGE_TERMINATION=true`. In the mTLS template, sets `X509_HEADER` to the verified certificate |
| Security headers | The set in [Security headers](#security-headers). On `/api/` the API's copies are hidden, so each header appears once |
| Caching | `/assets/` expires after 1 year; the app shell (`/`, every client route and `/index.html`) is sent with `Cache-Control: no-cache`, so a browser picks up a new version's files after an upgrade |
| Logs | Access log to stdout, error log to stderr |

The container exits at start with a `dsp-entrypoint:` error when the proxy secret contains other
characters, `X509_PROXY_SECRET_FILE` cannot be read, `X509_HEADER` is not a header name,
`X509_EDGE_TERMINATION` or `ENS_CSP_REPORT_ONLY` is neither `true` nor `false`, or
`WEB_TRUSTED_PROXIES` holds something that is not an address or CIDR, or a catch-all such as
`0.0.0.0/0`.

!!! tip "Kubernetes"
    nginx doesn't apply DNS search domains, so give `API_URL` a fully qualified service name
    (for example `http://dsp-api.portal.svc.cluster.local:3001`).

### Behind another proxy

From v0.0.9. When an ingress controller, a load balancer or a TLS-terminating proxy sits in front of
the web tier, the web tier sees that proxy's address on every connection. Unless told otherwise it
forwards that address to the API as the client's, so every user has the same address: in the audit
trail, for the sign-in lockout (`LOGIN_MAX_FAILURES`), for the failed API token limit and for the
single sign-on start limit, which they then all share.

Set `WEB_TRUSTED_PROXIES` to the addresses that proxy connects from, as the web tier sees them
(the first field of the web tier's access log):

| Deployment | Where | Typical value |
|---|---|---|
| Docker package | `WEB_TRUSTED_PROXIES` in `.env`, then `./portal.sh up` | For a proxy on the same host, the compose network's gateway, for example `172.18.0.1` (read it from `./portal.sh logs web`) |
| Native package | `WEB_TRUSTED_PROXIES` in `portal.env`, then run `install.sh` again (it writes the directives into the nginx site and reloads nginx) and restart the API, which reads `portal.env` at start | `127.0.0.1` for a proxy on the same host |
| Helm | `web.trustedProxies` (a list); the chart gives it to the web pods and the API | The ingress controller's pod CIDR, or the load balancer's addresses |

- **List only proxies.** `X-Forwarded-For` from a listed address is believed, so anything that can
  connect from one can name any client. A catch-all (`/0`) is refused: the web container exits,
  the chart's schema rejects it, and the native `install.sh` ignores the entry and says so. With
  Helm, `networkPolicy.webIngressFrom` keeps everything but the ingress controller away from the
  web pods.
- **Unset**, as on an upgrade, nothing changes: every user behind the proxy keeps the proxy's
  address. While single sign-on is on, the API then logs a WARN at startup that names
  `WEB_TRUSTED_PROXIES`.
- It does not replace `SERVER_FORWARD_HEADERS_STRATEGY=framework` on the API, which makes the API
  use the address the web tier forwards.

### Docker package deployment settings (`.env`)

| Variable | Default (from installer) | Description |
|---|---|---|
| `COMPOSE_FILE` | `docker-compose.yml` plus overlays chosen by `install.sh` | `:`-separated compose files. First `docker-compose.yml` (HTTP published on `WEB_BIND:WEB_PORT`) or `docker-compose.no-publish.yml` (nothing published), then optionally `docker-compose.mtls.yml` and `docker-compose.openldap.yml` |
| `CONTAINER_ENGINE` | The engine `install.sh` used | `docker` or `podman`. The `CONTAINER_ENGINE` environment variable wins |
| `COMPOSE_PROJECT_NAME` | `directory-services-portal` | Compose project (and volume name prefix) |
| `API_IMAGE`, `WEB_IMAGE`, `OPENLDAP_IMAGE` | Package image names | Images to run |
| `WEB_BIND` | `127.0.0.1` for plain HTTP, `0.0.0.0` with `--https` (before v0.0.4: `0.0.0.0`) | Host address to publish on. `0.0.0.0` is every interface; `127.0.0.1` keeps it to this host. An `.env` without `WEB_BIND` publishes on every interface. From v0.0.4 `install.sh` warns when plain HTTP is published beyond this host, or `ENS_COOKIE_SECURE` is not `true` there |
| `WEB_PORT` | `8080` | HTTP port (`docker-compose.yml`) |
| `HTTPS_PORT` | `8443` | HTTPS port (mTLS overlay) |
| `HTTPS_PORT_SUFFIX` | `:<HTTPS_PORT>`, empty for 443 | Port shown in redirects |
| `API_MEMORY_LIMIT` | `1536m` | Memory limit of the API container. The JVM's heap is 75% of it (`-XX:MaxRAMPercentage=75.0`) |
| `WEB_MEMORY_LIMIT` | `256m` | Memory limit of the web container |
| `JAVA_TOOL_OPTIONS` | *(empty)* | Extra JVM options for the API, for example the [trust store for LDAPS](#ldaps-with-a-private-ca) |
| `WEB_TRUSTED_PROXIES` | *(empty)* | Proxies in front of the web tier whose `X-Forwarded-For` names the client; given to the web container and the API. See [Behind another proxy](#behind-another-proxy) |
| `X509_PROXY_SECRET` | 64 hex characters, generated by `install.sh` | The X.509 proxy secret, given to both the API and the web container. To change it, set a new value (at least 32 characters) and run `./portal.sh up` |

The packaged compose file fixes `DATA_DIR=/data`, `PORT=3001` and
`SERVER_FORWARD_HEADERS_STRATEGY=framework` for the API, and passes everything else from `.env`.

The native package's `portal.env` has one deployment setting of its own from v0.0.4:
`NGINX_LISTEN`, where the packaged nginx site listens (plain HTTP). A new `portal.env` sets
`127.0.0.1:8080` (this host only); `8080` alone is every interface. `install.sh` writes it into
the site, so run it again after a change. A `portal.env` without it keeps `8080`, every interface.
From v0.0.9 `install.sh` also reads `WEB_TRUSTED_PROXIES` from `portal.env` and writes it into the
site the same way.

In the Docker package, the web container gets `ENS_CSP_REPORT_ONLY`, `WEB_TRUSTED_PROXIES`, `X509_PROXY_SECRET`,
`X509_HEADER` and `X509_EDGE_TERMINATION` from `.env`; the mTLS overlay fixes the last two (`X-SSL-Client-Cert`,
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
| `version` | Store format version: `2` from v0.0.6 (definitions and grants name their directory; a version 1 document is upgraded at the first start, giving them `default`, and written back once). Back up `DATA_DIR`, or the database, before upgrading if you may roll back |
| `customSchema` | Attribute types and object classes defined or overridden in the portal |
| `schemaCache` | The last schema pulled from the (primary) directory |
| `schemaCaches` | v0.0.6: the last schema pulled from each other directory, by id |
| `forms` | Form definitions |
| `definitions` | Directory definitions, each with its `directory` (v0.0.6) and, for a directory of groups, its [group access](admin-guide.md#group-access) settings (`groups`) |
| `grants` | Permission grants, each with the `directory` of its subject (v0.0.6) |
| `dashboards` | Metric dashboards |
| `analyses` | Saved analyses, including a summary of their last run |

The API reads the file once, at startup, and keeps it in memory. Updates are serialized, applied to
a copy, and written atomically (a temporary file plus rename). If the file can't be parsed at
startup, the API fails with `Could not read .../portal-config.json`. Because the API never reads the
file again while it runs, only one API process may use a data directory. With a shared database
(`DSP_DATABASE_URL`) the configuration is a versioned document in PostgreSQL instead: the first
start imports this file, which is then no longer read or written. See
[High availability](availability-and-sizing.md#with-a-shared-database-several-api-instances).

Other files in `DATA_DIR`:

| Path | Content |
|---|---|
| `audit/audit-YYYY-MM.jsonl` | The audit store: hash-chained `ens-audit/1` events, one file per month (unless `AUDIT_DIR` is set) |
| `audit/audit-state.json` | How far each audit output (Splunk HEC, syslog) has delivered |
| `audit/audit-api-tokens.json` | The pull API's tokens, stored as SHA-256 hashes |
| `audit/legacy/` | Audit files in the portal's earlier format, moved here unchanged when the store was upgraded (only on installations that had them) |
| `audit/audit-pruned` | From v0.0.9: how far audit retention has deleted (the last sequence number and that event's hash), which **Verify integrity** checks the oldest retained event against. Present once retention has run |
| `analysis-results/<analysis-id>.json` | Latest result of each saved analysis |
| `group-access-requests.json` | [Group access](admin-guide.md#group-access) requests: open ones, and closed ones for a year (at most 5,000) |
| `group-membership-expiries.json` | From v0.0.11: the end dates of the group memberships the portal granted for a limited time |

With the shared database all of these are in the database instead (the group access requests as a
shared document, the end dates in the table `dsp_group_membership_expiry`). The other files of the data
directory are listed in [Operator guide → Data and backups](operator-guide.md#data-and-backups).

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
