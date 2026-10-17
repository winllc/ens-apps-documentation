# Configuration

ReadyRoom is a Spring Boot application. Its defaults are in the `application.yml` packaged in the
image, and you override them with environment variables or JVM options. The application's own settings live under the `application.` prefix and are bound to
`ApplicationProperties` and a few related classes. Everything else is a standard Spring Boot
property.

## How to supply configuration

| Install method | Where | Notes |
|---|---|---|
| Release package | `.env` in the release directory | Made from `env.template` by `install.sh`. Add any setting below as its environment variable, then run `docker compose up -d`. |
| Helm chart | Your values file | Most settings have a named value (see [Installation](install.md#2-write-your-values) and the chart's `README.md`). Any other setting goes in `extraEnv` as its environment variable. Apply with `helm upgrade`. |

| Form | Example | Notes |
|---|---|---|
| Environment variables | `APPLICATION_USERBASEDN=ou=Users,dc=example,dc=com` | Spring's relaxed binding: upper-case the name, replace `.` with `_`, drop `-`. List items use an index: `APPLICATION_SUPERUSERDNS_0_`, `APPLICATION_GROUPS_0_GROUPSBASEDN`. |
| JVM options | `JAVA_OPTS="-Dapplication.timezone=America/Chicago"` | The image passes `$JAVA_OPTS` to `java`. Keep the default `-XX:MaxRAMPercentage=75.0` when you set it. |

!!! warning "Override every secret"
    The packaged `application.yml` contains development passwords for the database, the LDAP bind
    and SMTP. Override them all in production. See [Security](security.md).

In the tables below, **Packaged** is the value in the packaged `application.yml`, and **Built-in**
is the value the code uses when the property is not set anywhere. A dash means there is none.

## Server and TLS

| Property | Env var | Packaged | Description |
|---|---|---|---|
| `server.port` | `SERVER_PORT` | `8181` | Listener port, for HTTP or, with TLS on, HTTPS. Leave it at 8181: the release package and the Helm chart expect it. The package publishes it on the host as `READYROOM_PORT`. |
| `server.ssl.enabled` | `SSL_ENABLED` | `false` | Serve HTTPS. Client-certificate sign-in, and so the Windows client, needs it. Helm: `tls.enabled`. |
| `server.ssl.key-store` | `SERVER_SSL_KEYSTORE` | – | Server keystore, for example `file:/path/server.p12`. Required with TLS on: none is packaged. The release package and the Helm chart set it for you. |
| `server.ssl.key-store-password` | `SERVER_SSL_KEYSTOREPASSWORD` | – | Keystore password. |
| `server.ssl.key-store-type` | `SERVER_SSL_KEYSTORETYPE` | `PKCS12` | |
| `server.ssl.client-auth` | `SERVER_SSL_CLIENTAUTH` | `want` | `want` asks for a client certificate but allows form login without one. `need` would refuse every connection without a certificate, including the logoff call and browsers that use the login form. `none` turns certificate sign-in off, and with it the need for a truststore. |
| `server.ssl.trust-store` | `SERVER_SSL_TRUSTSTORE` | – | CAs whose client certificates are accepted. Required with TLS on while `client-auth` is `want` or `need`: none is packaged, and ReadyRoom does not fall back to Java's default CAs. Put only the CAs that issue your users' certificates in it. |
| `server.ssl.trust-store-password` | `SERVER_SSL_TRUSTSTOREPASSWORD` | – | |
| `server.ssl.trust-store-type` | `SERVER_SSL_TRUSTSTORETYPE` | `PKCS12` | |
| `server.servlet.session.cookie.same-site` | – | `lax` | The session cookie is not sent with another site's posts or scripted requests, but is sent when someone follows a link from elsewhere, such as an absence notification email. |
| `server.servlet.session.cookie.http-only` | – | `true` | |
| `server.servlet.session.cookie.secure` | `SESSION_COOKIE_SECURE` | the value of `SSL_ENABLED` | Mark the session cookie `Secure`. Set `SESSION_COOKIE_SECURE=true` when a reverse proxy terminates TLS for browsers and ReadyRoom itself serves HTTP. |

With TLS on, ReadyRoom refuses to start if no keystore is set, or if no truststore is set while
`client-auth` is `want` or `need`. The error names the settings to add (for example
`server.ssl.enabled is true but no key store is configured.`).

## Database

| Property | Packaged | Description |
|---|---|---|
| `spring.datasource.url` | `jdbc:postgresql://localhost:5432/appdb` | JDBC URL. |
| `spring.datasource.username` | `appuser` | |
| `spring.datasource.password` | `secret123` | |
| `spring.datasource.driver-class-name` | `org.postgresql.Driver` | |
| `spring.jpa.hibernate.ddl-auto` | `update` | Hibernate creates and alters tables. Indexes and constraints come from `db/migrations/` (see [Installation](install.md#prepare-postgresql)). |
| `spring.jpa.open-in-view` | `false` | Keep it off. Otherwise a request holds its database connection while it waits on LDAP, and a slow directory can exhaust the pool. |
| `spring.jpa.show-sql` | `false` | |
| `spring.jpa.properties.hibernate.dialect` | `org.hibernate.dialect.PostgreSQLDialect` | |
| `spring.jpa.properties.hibernate.jdbc.batch_size` | `50` | Lets the directory refresh write changed rows in batches. |
| `spring.jpa.properties.hibernate.order_inserts` / `order_updates` | `true` | |

## LDAP connection

| Property | Packaged | Built-in | Description |
|---|---|---|---|
| `spring.ldap.urls` | `ldap://localhost:1389` | – | Directory URL(s). |
| `spring.ldap.username` | `cn=admin,dc=winllc,dc=com` | – | Bind DN for searches. |
| `spring.ldap.password` | `adminpassword` | – | Bind password. |
| `spring.ldap.base-environment."[com.sun.jndi.ldap.connect.timeout]"` | `5000` | – | Connect timeout in ms. Without it, an unresponsive directory holds requests indefinitely. |
| `spring.ldap.base-environment."[com.sun.jndi.ldap.read.timeout]"` | `30000` | – | Read timeout in ms. |
| `application.ldap.pooled` | `true` | `true` | Reuse JNDI connections. Password checks on the login form never use the pool. |
| `application.ldap.page-size` | `500` | `500` | Entries per page for large searches. Keep it below the server's size limit (Active Directory 1000, OpenLDAP 500). `0` turns paging off for directories without the paged results control. |
| `application.ldap.max-pages` | – | `1000` | The most pages one paged search reads before it logs a warning and returns what it has. This protects against a directory or proxy that keeps returning a paging cookie. |
| `application.ldap.group-membership-cache-seconds` | `300` | `300` | How long a user's group memberships are cached. Permission checks read them on every request, so a directory change can take this long to show. `0` disables the cache. |

The JDK connection pool is configured by system properties that ReadyRoom sets at startup, unless
you pass them with `-D`:

| System property | Default set by ReadyRoom |
|---|---|
| `com.sun.jndi.ldap.connect.pool.protocol` | `plain ssl` (pools `ldaps://` connections too) |
| `com.sun.jndi.ldap.connect.pool.maxsize` | `20` |
| `com.sun.jndi.ldap.connect.pool.timeout` | `300000` (5 minutes idle) |

## Directory mapping

| Property | Packaged | Built-in | Description |
|---|---|---|---|
| `application.user-base-dn` | `dc=winllc,dc=com` | – | Base DN for every user search, the login form's user search and the org chart scan. **Required.** |
| `application.user-ldap-filter` | `(objectclass=inetOrgPerson)` | `(objectclass=inetOrgPerson)` | Identifies user entries. It is used alone to list and count users, and combined into an AND for search, reports, direct reports and Windows sign-in. Parentheses are added if you leave them out, and a blank value falls back to the default. Example: `(&(objectclass=inetOrgPerson)(!(employeeType=CONTRACTOR)))`. |
| `application.groups[].groups-base-dn` | `ou=Groups,dc=winllc,dc=com`, `ou=Companies,dc=winllc,dc=com` | none | One entry per top-level group tree shown on the **Groups** page. Groups are `groupOfUniqueNames` entries. |
| `application.super-user-dns` | `CN=user3,OU=Users,DC=winllc,DC=com` | none | DNs that always get the `ADMIN` role (matched without regard to case). |
| `application.user-ldap-organization-attribute` | `o` | `organization` | Organization of a user. |
| `application.user-ldap-employee-type-attribute` | `employeeType` | `employeeType` | Employee type. Used in metrics and org-chart breakdowns. |
| `application.user-ldap-location-attribute` | `l` | `location` | Location. |
| `application.user-ldap-branch-attribute` | – | `branch` | Branch. |
| `application.user-ldap-email-attribute` | – | `mail` | Email address. Absence emails go to the manager's value of this attribute. |
| `application.user-ldap-phone-attribute` | – | `telephoneNumber` | Phone number. |
| `application.user-ldap-manager-id-attribute` | `title` | `title` | On a **user**: the id of the person they report to. |
| `application.manager-ldap-id-attribute` | `street` | `Email` | On a **manager**: their own id. A user reports to the manager whose value here equals the user's `user-ldap-manager-id-attribute`. |
| `application.user-ldap-duty-sub-organization-attribute` | `departmentNumber` | `dutySubOrganization` | The flat attribute the org chart is built from (for example `RYS34B`). |
| `application.duty-sub-org-filter` | `(departmentNumber=*)` | blank | Extra filter ANDed into the org-chart scan. |
| `application.duty-sub-org-groups-base-dn` | `ou=Groups,dc=winllc,dc=com` | blank | Bound to the configuration, but not read by any code in the current version. |
| `application.organization-name` | – | `TOP` | Name of the root node of the org chart. |
| `application.lookup-on-dn-attribute` | – | `false` | When `true`, a user is looked up by searching `user-base-dn` for `user-dn-attribute=<DN>` instead of reading the DN directly. Use it when certificate subjects are not directory DNs. |
| `application.user-dn-attribute` | – | blank | The attribute used when `lookup-on-dn-attribute` is `true`. |

### Directory caches

Users, group trees, the user count and the org chart are kept in Caffeine loading caches (at most
5000 entries each).

| Property | Built-in | Description |
|---|---|---|
| `application.cache-duration-refresh-minutes` | `60` | An entry older than this is reloaded in the background the next time it is read. The stale value is served until the reload finishes. |
| `application.cache-duration-expiration-minutes` | `120` | An entry older than this is discarded and loaded again in the foreground. |

Admins and managers can evict cached group trees from **Settings** (see
[Administration](admin-guide.md#group-cache)).

## Time zone

| Property | Packaged | Built-in | Description |
|---|---|---|---|
| `application.timezone` | `America/New_York` | `UTC` | Sets the JVM default time zone at startup. Every stored timestamp, every "today" and every job's working-day check uses it. The log prints `Default time zone set to <zone> (from application.timezone=...)`. |
| `spring.jackson.time-zone` | `America/New_York` | – | Time zone for JSON serialization. Keep it the same as `application.timezone`. |
| `spring.jackson.date-format` | `yyyy-MM-dd HH:mm a z` | – | Default JSON date format. |

!!! warning "Always set `application.timezone`"
    The same key is also read through `ApplicationProperties.timeZone` (`TimeZoneConfig`), whose
    built-in default is `ETC`. That is not a valid zone id, so Java would treat it as GMT. Set the
    property explicitly so both start-up paths agree. An unrecognized zone id silently becomes GMT.

## Attendance and scheduled jobs

| Property | Packaged | Built-in | Description |
|---|---|---|---|
| `application.check-out-after-minutes` (`checkOutAfterMinutes`) | `2` | `240` | How long someone's latest event of the day can be a screen lock before they are automatically checked out. The packaged value of 2 minutes is a development setting. |
| `application.extra-time-before-absent-notification-minutes` | – | `60` | Grace period after a person's expected login time before an absence alert is raised. The help dialog shows this value. |
| `application.inactive-cron.fixed-rate` | `60000` | `1800000` | Milliseconds between auto check-out runs. It is used as a fixed **delay** after each run finishes, so runs never overlap. |
| `application.inactive-cron.initial-delay` | `60000` | `1800000` | Delay before the first auto check-out run, in ms. |
| `application.send-absent-notification-cron.fixed-rate` | `300000` | `1800000` | Milliseconds between absence-alert runs (also a fixed delay). |
| `application.send-absent-notification-cron.initial-delay` | `1` | `1800000` | Delay before the first absence-alert run, in ms. |
| `application.refresh-user-records-cron.enabled` | `true` | `true` | Turns the directory-to-database refresh on or off without removing its schedule. |
| `application.refresh-user-records-cron.fixed-rate` | `3600000` | `3600000` | Milliseconds between refreshes (fixed delay). |
| `application.refresh-user-records-cron.initial-delay` | `300000` | `300000` | Delay after startup before the first refresh, in ms. |
| `spring.task.scheduling.pool.size` | `3` | – | One thread per job, so a slow run of one job does not delay the others. |
| `application.logoff.session-max-age-hours` | `168` | `168` | Env var `APPLICATION_LOGOFF_SESSIONMAXAGEHOURS`. How long after a check-in its `sessionId` can identify the Windows client's logout (a week, so a workstation left logged on over a weekend still records its logout). A later logout, or a second one with the same id, is refused. |
| `application.logoff.refused-per-minute` | `30` | `30` | Env var `APPLICATION_LOGOFF_REFUSEDPERMINUTE`. Refused logouts without a sign-in (unknown, used or expired `sessionId`) allowed from one client address per minute. Past it, the next ones get `429` and are no longer audited one by one. A logout with a usable `sessionId` is never counted. `0` turns the limit off. |

What each job does is described in [Operations](operator-guide.md#scheduled-jobs).

## Email and notifications

| Property | Packaged | Built-in | Description |
|---|---|---|---|
| `spring.mail.host` | `mailcow.winllc-dev.com` | – | SMTP host. |
| `spring.mail.port` | `587` | – | SMTP port. |
| `spring.mail.username` / `spring.mail.password` | dev values | – | SMTP credentials. |
| `spring.mail.properties.mail.smtp.auth` | `true` | – | |
| `spring.mail.properties.mail.smtp.starttls.enable` | `true` | – | |
| `spring.mail.properties.mail.smtp.ssl.trust` | `"*"` | – | Trusts any SMTP server certificate. Set it to your mail host's name in production. |
| `application.notification-sender-email` | `user1@winllc-dev.com` | `noreply@test.com` | From address of absence emails. |
| `application.application-base-url` | `http://localhost:8181` | `https://localhost` | Base of the link in absence emails (`<base>/app/notifications/id/<id>`). Set it to the URL users really use. |

## User interface

| Property | Packaged | Built-in | Description |
|---|---|---|---|
| `application.banner-text` | `BANNER TEXT` | **none, required** | Text of the banner at the top and bottom of every page (for example a classification marking). The application does not start without it. |
| `application.banner-color` | – | `darkgreen` | Banner background color (any CSS color). |
| `application.banner-text-color` | – | `white` | Bound, but not applied by the current page layout. |
| `application.logofile` | – | `/images/logos/readyroom_logo_inverse_1.svg` | Image shown in the sidebar. Use light, transparent artwork, ideally SVG. Files in the server's `/external-resources/` directory are served at `/external/**`, so a mounted logo can be referenced as `/external/<file>`. |
| `application.update-profile-url` | `https://google.com` | `https://google.com` | Target of the **Help Desk** link on **My Profile**, where users report wrong directory data. |
| `application.groups-initially-expanded` | – | `false` | Expand the group and org-chart trees when the page loads. |
| `application.calendar-status-event-color` | – | `blue` | Calendar color of excusable statuses (for example work from home or leave). |
| `application.calendar-absent-status-event-color` | – | `purple` | Calendar color of non-excusable statuses (for example absent or late arrival). |
| `application.calendar-activity-event-color` | – | `green` | Calendar color of "Online: ..." activity entries. |
| `application.calendar-global-event-color` | – | `orange` | Calendar color of organization calendar events. |
| `application.attribute-update-request-url-mappings` | – | empty map | Bound, but not read by any code in the current version. |

## Windows (Kerberos) sign-in

Turned off by default. These settings apply only to `POST /api/check/in`, `/lock` and `/unlock`.
For the setup procedure, see [Security](security.md#windows-kerberos-sign-in).

| Property | Env var | Packaged | Built-in | Description |
|---|---|---|---|---|
| `application.windows-auth.enabled` | `APPLICATION_WINDOWSAUTH_ENABLED` | `false` | `false` | Turns Windows sign-in on. |
| `application.windows-auth.service-principal` | `APPLICATION_WINDOWSAUTH_SERVICEPRINCIPAL` | – | blank | For example `HTTP/readyroom.example.com@EXAMPLE.COM`. The app does not start without it when sign-in is enabled. |
| `application.windows-auth.keytab-location` | `APPLICATION_WINDOWSAUTH_KEYTABLOCATION` | – | blank | Path to the keytab. The app does not start if the file is missing or unreadable. |
| `application.windows-auth.account-attribute` | `APPLICATION_WINDOWSAUTH_ACCOUNTATTRIBUTE` | `sAMAccountName` | `sAMAccountName` | Directory attribute holding the Windows account name. It must match exactly one user under `user-base-dn` that also matches `user-ldap-filter`. |
| `application.windows-auth.strip-realm` | `APPLICATION_WINDOWSAUTH_STRIPREALM` | `true` | `true` | Match `jdoe` rather than `jdoe@EXAMPLE.COM`. Set it to `false` when matching `userPrincipalName`. |
| `application.windows-auth.allowed-realms` | `APPLICATION_WINDOWSAUTH_ALLOWEDREALMS` | – | the service principal's realm | Realms whose users may sign in. |
| `application.windows-auth.krb5-config-location` | `APPLICATION_WINDOWSAUTH_KRB5CONFIGLOCATION` | – | system `/etc/krb5.conf` | Use a different `krb5.conf`. |
| `application.windows-auth.debug` | `APPLICATION_WINDOWSAUTH_DEBUG` | – | `false` | Log the JDK's Kerberos detail while the keytab is loaded. |

## Logging, monitoring and diagnostics

| Property | Packaged | Description |
|---|---|---|
| `logging.level.root` | `info` | |
| `logging.level.com.winllc.innoutwork` | `info` | At `info`: records written by jobs, permission grants and revocations, report generation, org-chart rebuilds and job summaries. Set it to `debug` for per-query detail, timings and authorization decisions. |
| `management.endpoints.web.exposure.include` | `health` | Actuator endpoints exposed under `/actuator`. `health` needs no authentication and shows the status only. Any other endpoint you add needs an `ADMIN` sign-in; see [Security](security.md#unauthenticated-endpoints). |
| `application.diagnostics.request-metrics` | `false` | When `true`, every request logs `GET /app/home -> 200: <n> SQL statement(s), <n> LDAP operation(s), <n>ms`. Use it for measuring, not for normal running. |

## Example: production settings

A release package `.env` for a site with LDAPS, TLS and its own job timings. The settings
`install.sh` writes (database, ports) are left out. With Helm, the same settings go in your values
file (named values where the chart has them, `extraEnv` for the rest).

```bash
# .env in the release directory
COMPOSE_FILE=docker-compose.yml:docker-compose.tls.yml
APPLICATION_BASE_URL=https://readyroom.example.com:8181

# TLS: tls/keystore.p12 and tls/truststore.p12
SSL_ENABLED=true
SERVER_SSL_KEYSTOREPASSWORD=...
SERVER_SSL_TRUSTSTOREPASSWORD=...

SPRING_LDAP_URLS=ldaps://ldap.example.com:636
LDAP_USERNAME=cn=readyroom-svc,ou=Service,dc=example,dc=com
LDAP_PASSWORD=...

SPRING_MAIL_HOST=smtp.example.com
SPRING_MAIL_PORT=587
SPRING_MAIL_USERNAME=readyroom@example.com
SPRING_MAIL_PASSWORD=...
SPRING_MAIL_PROPERTIES_MAIL_SMTP_SSL_TRUST=smtp.example.com
APPLICATION_NOTIFICATIONSENDEREMAIL=readyroom@example.com

APPLICATION_TIMEZONE=America/Chicago
SPRING_JACKSON_TIMEZONE=America/Chicago
APPLICATION_BANNERTEXT=UNCLASSIFIED
APPLICATION_BANNERCOLOR=green
APPLICATION_USERBASEDN=ou=People,dc=example,dc=com
APPLICATION_GROUPS_0_GROUPSBASEDN=ou=Teams,dc=example,dc=com
APPLICATION_SUPERUSERDNS_0=cn=Jane Admin,ou=People,dc=example,dc=com
APPLICATION_USERLDAPMANAGERIDATTRIBUTE=managerId
APPLICATION_MANAGERLDAPIDATTRIBUTE=employeeNumber
APPLICATION_USERLDAPDUTYSUBORGANIZATIONATTRIBUTE=departmentNumber
APPLICATION_DUTYSUBORGFILTER=(departmentNumber=*)
APPLICATION_CHECKOUTAFTERMINUTES=240
APPLICATION_INACTIVECRON_FIXEDRATE=300000
APPLICATION_INACTIVECRON_INITIALDELAY=60000
APPLICATION_SENDABSENTNOTIFICATIONCRON_FIXEDRATE=900000
APPLICATION_SENDABSENTNOTIFICATIONCRON_INITIALDELAY=60000
APPLICATION_UPDATEPROFILEURL=https://helpdesk.example.com/
```

The attribute names `managerId` and `employeeNumber` above are examples. Use whichever attributes
hold reporting lines in your directory.

## Windows client parameters

The workstation agent is configured by `inoutworker.config.json` and command-line parameters. The
file is read from the path given with `-ConfigPath`, or else from the script's own folder, where the
installer writes it. Explicit parameters win over the file, and the file wins over the script's
built-in defaults. The Logout task also passes `-UserSid`, the SID of the user whose session ended,
from event 4647. The
full reference, including the installer's parameters, is on the [Windows client](windows-client.md#configuration)
page. In summary:

| Setting | Built-in default | Description |
|---|---|---|
| `BaseUrl` | `https://192.168.1.29:8444/api/check` (a lab address) | Server check-in API, for example `https://readyroom.example.com:8181/api/check`. Always set it. |
| `Authentication` | `Certificate` | `Certificate`, `Windows` or `Auto`. |
| `CertificateThumbprint` | blank | Use this client certificate. |
| `CertificateSubjectContains` | blank | Otherwise, use the first certificate whose subject contains this text. |
| `SkipCertificateCheck` | `false` | Turns off server TLS validation. For labs only. |
