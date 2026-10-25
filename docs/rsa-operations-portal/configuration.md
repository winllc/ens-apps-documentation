# Configuration

With the release package, the runtime configuration lives in three files in the install folder, one per service. Each file is given only to its own service:

| File | Created from | Service | Holds |
|---|---|---|---|
| **`.env`** | `env.template` | Portal, which also reads it as its `application.properties` | The Portal settings on this page |
| **`connector.env`** | `connector.env.template` | Connector | `CONNECTOR_AUTH_PASSWORD`, the AM accounts and trust store password as `RSA_*` variables, the Connector's HTTPS settings (`CONNECTOR_TLS_*`), and `JAVA_OPTS` with the other [Connector settings](#connector-rsa-authentication-manager-settings) as `-D` options |
| **`mysql.env`** | `mysql.env.template` | MySQL | `MYSQL_ROOT_PASSWORD`, `MYSQL_DATABASE`, `MYSQL_USER`, `MYSQL_PASSWORD` |

`install.sh` creates each file from its template (mode 600); the templates comment every setting. See [How the settings files are consumed](#how-the-settings-files-are-consumed). With the Helm chart, the same settings come from your values file: [Installation → Write your values](install.md#3-write-your-values) maps each value to its setting below, and any other Portal setting goes in `config`.

This page lists every setting the code reads, grouped by component, with the default packaged in the images. Several of those defaults are development hostnames and credentials that **must** be overridden.

!!! note "Packaged defaults"
    The `application.properties` and `config.properties` packaged in the images hold no passwords, but they do hold development host names and sample role GUIDs. Set every setting the templates mark `CHANGE_ME`.

## How the settings files are consumed

This applies to the release package. The Helm chart sets the same settings as environment variables and `-D` options.

| Consumer | Mechanism (from the package's `docker-compose.yml`) | Effect |
|---|---|---|
| **portal** | `env_file: .env` (and `global.env`, which holds the release version) | Every `key=value` becomes an environment variable. Spring Boot resolves property names from environment variables, and the image's entry point uses `$JAVA_OPTS`. |
| **portal** | `./.env:/application.properties` | The container's working directory is `/`, so Spring Boot also loads `.env` as an external `application.properties`, which overrides the defaults packaged in the image. The Portal runs as uid 1001 in group 0 and reads the file through that group: `util/file-owners.sh`, which `install.sh` and `upgrade.sh` run, makes it mode 640 with group 0. |
| **connector** | `env_file: connector.env` | Environment variables. The Connector reads `CONNECTOR_AUTH_PASSWORD`, the `RSA_*` accounts and `RSA_TLS_VERIFY_HOSTNAME`, the `CONNECTOR_TLS_*`, `CONNECTOR_HTTPS_ADDRESS` and `CONNECTOR_HTTP_ADDRESS` settings and the `CONNECTOR_SERIAL_FILTER*` settings itself; Tomcat's `catalina.sh` honours `JAVA_OPTS`, which carries the other Connector settings as `-D` options. |
| **mysql** | `env_file: mysql.env` | Standard `mysql` image variables: `MYSQL_ROOT_PASSWORD`, `MYSQL_DATABASE`, `MYSQL_USER`, `MYSQL_PASSWORD`. |

The Portal, which users reach, never receives the AM credentials or the MySQL root password. `util/check-env.sh` refuses a `.env` that holds the Connector's `-D` options in `JAVA_OPTS`, any `RSA_*` variable or any `MYSQL_*` setting. So in `.env`, write a Portal setting such as `rsa.helpdesk.code-key` or `rsa.rate-limit.enabled` under its property name, never in its `RSA_...` environment form.

```text
# .env (the Portal; excerpt, values are placeholders)
CONNECTOR_AUTH_PASSWORD=<shared secret>
spring.datasource.url=jdbc:mysql://mysql:3306/RSAAPP
spring.datasource.username=appuser
spring.datasource.password=<app password>
spring.mail.host=smtp.example.org
CONNECTOR_TLS_TRUSTSTORE=/ssl/connector-ca.crt
CONNECTOR_TLS_CLIENTCERTIFICATE=/portal-tls/portal-client.crt
CONNECTOR_TLS_CLIENTKEY=/portal-tls/portal-client.key
rsa.helpdesk.code-key=<added by util/helpdesk-code-key.sh>
...

# connector.env (the Connector)
CONNECTOR_AUTH_PASSWORD=<shared secret>
CONNECTOR_TLS_PRIVATE_KEY_PASSWORD=<set by util/connector-tls.sh>
CONNECTOR_TLS_CLIENT_AUTH=required
RSA_CMDCLIENT_USER=<command client user>
RSA_CMDCLIENT_PASSWORD=<command client password>
RSA_ADMIN_USER_ID=<service account>
RSA_ADMIN_USER_PASSWORD=<its password>
RSA_READONLY_USER_ID=<read-only account>
RSA_READONLY_USER_PASSWORD=<its password>
RSA_TRUSTSTORE_PASSWORD=<trust.jks password>
JAVA_OPTS=-Djava.security.egd=file:/dev/./urandom -Dhttps.protocols=TLSv1.3,TLSv1.2 -Dlogging.config=/log4j2.xml -Dconfig.properties.location=classpath:config.properties -Djava.naming.provider.url=t3s://am-primary.example.org:7002 ...

# mysql.env (MySQL)
MYSQL_ROOT_PASSWORD=<root password>
MYSQL_DATABASE=RSAAPP
MYSQL_USER=appuser
MYSQL_PASSWORD=<app password>
```

Keep `$` and ` #` out of the values: Docker Compose replaces `$name` with a variable, and ` #` starts a comment. `util/check-env.sh` reports both, and any `CHANGE_ME` left in the files.

## Container and JVM settings

| Name | Default | Used by | Description |
|---|---|---|---|
| `JAVA_OPTS` | Portal image: `-Djava.security.egd=file:/dev/./urandom -Dhttps.protocols=TLSv1.3,TLSv1.2 -Djdk.tls.client.protocols=TLSv1.3,TLSv1.2 -Dlogging.config=/log4j2.xml`. Connector image: `-Djava.security.egd=file:/dev/./urandom -Dhttps.protocols=TLSv1.3,TLSv1.2 -Dlogging.config=/log4j2.xml` | portal, connector | JVM options. A `JAVA_OPTS` line in a service's settings file **replaces** that image's default, so keep the default flags when you set it. The Portal's is optional, in `.env`; `env.template` leaves it commented out, so the image default applies. The Connector's is in `connector.env` and carries the RSA settings below (not the passwords), passed as `-D` system properties. The Helm chart builds them from `authManager.*` (`javaOpts`, `connector.javaOpts`). The connector gives system properties precedence (`SYSTEM_PROPERTIES_MODE_OVERRIDE`). A `connector.env` from an older release holds `-Dhttps.protocols=TLSv1.2 -Djdk.tls.client.protocols=TLSv1.2`, which keeps the Connector at TLS 1.2 towards AM; replace them to allow TLS 1.3. |
| `MYSQL_ROOT_PASSWORD` | none | mysql, `util/backupdb.sh`, `upgrade.sh` | Root password of the MySQL container, in `mysql.env`. Read once, when the data volume is first created. The backup scripts read it inside the `mysql` container, so it never appears on a command line. |
| `MYSQL_DATABASE`, `MYSQL_USER`, `MYSQL_PASSWORD` | `RSAAPP`, `appuser`, none | mysql | In `mysql.env`. The database and application user MySQL creates on its first start. `MYSQL_PASSWORD` must equal `spring.datasource.password` in `.env`. |
| `MFAAPP_PROPERTY_FILES_LOCATION` | unset | portal | If set to an existing directory, **every regular file** under it is loaded as a placeholder property source **instead of** `messages.file.location` and `email-description.location`. Startup fails with "No property files loaded from custom location" if the directory has no files. |

### Ports and volumes (release package `docker-compose.yml`)

| Service | Published ports | Volumes (host → container) |
|---|---|---|
| `portal` | `${PORTAL_HTTP_PUBLISH:-8080}:8080`, `${PORTAL_HTTPS_PUBLISH:-8443}:8443`: every interface by default (see [Address and ports](install.md#4-address-and-ports)) | `./default_build/properties:/custom`, `./default_build/static:/static`, `./default_build/email-templates:/resources/email-templates`, `./default_build/ssl:/ssl`, `./portal-tls:/portal-tls` (the Portal's certificate and key for mutual TLS; no other service mounts it), `./default_build/portal/log4j2.xml:/log4j2.xml`, `./logs:/var/log`, `ens-license:/var/lib/ens/mfa-portal` (the license files of release 1.5 and earlier, imported into the database on the first start), `./.env:/application.properties` |
| `connector` | none (reached internally as `https://connector:8443`) | `./default_build:/custom` (the trust store as `/custom/ssl/trust.jks`, the HTTPS certificate in `/custom/connector-tls`), `./logs:/var/log` |
| `mysql` | none | named volume `mysql_data:/var/lib/mysql` |

The Portal also mounts the `ens-audit` volume at `/var/log/ens`, for the optional audit file. The health checks are: Portal `curl -fs http://localhost:8080/login`; Connector `curl -fs http://localhost:8080/app/health` inside its container (`UP` once it has an AM session); MySQL `mysqladmin ping`. The Portal starts after the `connector` has started and once `mysql` passes its health check. MySQL is given 2 minutes to shut down cleanly (`stop_grace_period`).

With Helm, the Portal and the Connector are two Deployments, the license is in the database, and branding files come from ConfigMaps (`branding.*`). See [Installation → Install with Helm](install.md#install-with-helm).

## Portal settings

### Server, sessions and uploads

| Name | Default | Description |
|---|---|---|
| `server.port` | `8080` | HTTP port inside the container. |
| `server.servlet.session.timeout` | not set | The setting up to release 1.5, still read as the idle timeout when `ENS_SESSION_IDLE_TIMEOUT` is not set. Use [`ENS_SESSION_IDLE_TIMEOUT`](#security-headers-banner-and-sessions). |
| `ENS_SESSION_STORE` | `jdbc` | `jdbc`: sessions in the Portal database (Spring Session JDBC), shared by every replica and kept across restarts and upgrades; `memory`: in each Portal's memory, as up to release 1.5. See [Security → Sessions](security.md#sessions). |
| `server.servlet.session.cookie.same-site` | `lax` | The session cookie is `HttpOnly` and `SameSite=Lax`. |
| `spring.servlet.multipart.max-file-size` | `5MB` | Largest file accepted (Trusted Agent CSV upload, group-email attachment). |
| `spring.servlet.multipart.max-request-size` | `5MB` | Largest multipart request. |
| `server.ssl.*` | not set | Port `8443` is published, but the release package configures no TLS by default. Several features read the browser's **client certificate**, so HTTPS with client-certificate negotiation must be configured with the standard Spring Boot `server.ssl.*` properties. In the release package, the `./default_build/ssl` directory is mounted at `/ssl` for keystores. With Helm, set `tls.*` instead. See [Security](security.md#tls-and-client-certificates). |
| `application.dns.name` | `http://localhost:8080` | External base URL of the portal. It prefixes every link in outgoing emails (registration, PIN reset, appointment and shipment details, Trusted Agent directory). |

### Database

| Name | Default | Description |
|---|---|---|
| `spring.datasource.url` | `jdbc:mysql://database.winllc-dev.com/RSAAPP` | JDBC URL. The release package's template sets `jdbc:mysql://mysql:3306/RSAAPP?sslMode=REQUIRED`. The Helm chart builds it from `database.*` (with `sslMode=REQUIRED` by default). |
| `spring.datasource.username` | `appuser` | Database user. The release package's MySQL creates `appuser` (`MYSQL_USER`). |
| `spring.datasource.password` | empty | Database password. |
| `spring.datasource.driver-class-name` | `com.mysql.cj.jdbc.Driver` | JDBC driver. |
| `spring.jpa.hibernate.ddl-auto` | `validate` | Hibernate only checks that the tables match the entities, and the Portal refuses to start if they don't. Flyway creates and changes the tables. |
| `spring.flyway.baseline-on-migrate`, `spring.flyway.baseline-version` | `true`, `0` | A database from release 1.5 or earlier, whose tables Hibernate created, gets a Flyway history at version 0 on its first start; the first migration then adds what is missing. Leave them as they are. |
| `spring.flyway.user`, `spring.flyway.password` | not set (the datasource user) | Optional: run the migrations as a second user with the DDL rights (`CREATE`, `ALTER`, `INDEX`, `REFERENCES`, `DROP`), so `spring.datasource.username` needs only `SELECT`, `INSERT`, `UPDATE` and `DELETE`. Helm: `database.migration.*`. |
| `spring.jpa.open-in-view` | `false` | |
| `spring.jpa.hibernate.naming.physical-strategy` | `org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl` | Tables keep their entity names (for example `TrustedAgent`). |

### Mail

| Name | Default | Description |
|---|---|---|
| `spring.mail.host` | `email.winllc-dev.com` | SMTP relay used by `JavaMailSender`. |
| `spring.mail.port` | `25` | SMTP port. |
| `spring.mail.test-connection` | `true` | Spring Boot checks the SMTP connection at startup. |
| `mail.default.from` | `noreply@winllc-dev.com` | The From address on every email the portal sends. |
| `mail.smtp.host` / `mail.smtp.port` | `email.winllc-dev.com` / `25` | Present in `application.properties`. The portal code does not read them; `spring.mail.*` controls delivery. |
| `mfa.group.email` | `postmaster@winllc-dev.com` | MFA/PMO team mailbox. It receives the lost-token-threshold report and appears in user emails as the contact address. (`GUIDE.md` calls it "the from address", but the From header comes from `mail.default.from`.) |
| `group.email` | empty | Present in `application.properties`. Not read by the code. |

### RSA AM settings used by the portal

| Name | Default | Description |
|---|---|---|
| `token.serial.prefix` | `000` | Prepended to token serials that do not already start with it. Set the same value in the connector. |
| `token.emergencyCode.validityInDays` | `1` | Lifetime of emergency access codes generated from **User Details**. |
| `token.pin.min.length` | `6` | Minimum PIN length accepted during enrollment. |
| `token.pin.max.length` | `6` | Maximum PIN length accepted during enrollment. |
| `role.count` | `3` | Number of `role.mapped.N.*` entries to read. |
| `role.mapped.N.name` | `1=TRUSTED_AGENT`, `2=PMO`, `3=HELP_DESK` | Application role (must be one of `TRUSTED_AGENT`, `PMO`, `HELP_DESK`, `USER`). |
| `role.mapped.N.rsaSecurityGuid` | sample GUIDs | GUID (`ims.…`) of the RSA AM **administrative role** that grants this application role. See [RSA AM Integration](rsa-integration.md#administrative-role-guids). |

### Connector connection

How the Portal reaches the Connector: `connector.url` (`CONNECTOR_URL`), `connector.tls.*` (`CONNECTOR_TLS_TRUSTSTORE`, `CONNECTOR_TLS_CLIENTCERTIFICATE`, `CONNECTOR_TLS_CLIENTKEY`), `CONNECTOR_AUTH_PASSWORD`, `connector.connect-timeout-millis` (5000), `connector.read-timeout-millis` (120000) and `connector.health-*`. They are described with the Connector's own settings in [RSA AM integration → Portal to Connector](rsa-integration.md#portal-to-connector). In a new installation the Portal presents its own certificate to the Connector (mutual TLS): `env.template` sets `CONNECTOR_TLS_CLIENTCERTIFICATE=/portal-tls/portal-client.crt` and `CONNECTOR_TLS_CLIENTKEY=/portal-tls/portal-client.key`, which `util/connector-tls.sh` makes. Keep them as long as `connector.env` has `CONNECTOR_TLS_CLIENT_AUTH=required`.

### Security headers, banner and sessions

These are the ENS platform settings: every ENS application has them, with the same names and defaults. Put them in `.env` (Helm: `consentBanner.*`, `session.*`, or `config`/`extraEnv`).

| Name | Default | Description |
|---|---|---|
| `SERVER_FORWARD_HEADERS_STRATEGY` | unset | `native` behind a TLS-terminating proxy: Tomcat takes the original scheme, host, port and client address from `X-Forwarded-*`, but only on requests from an internal proxy (below), reading `X-Forwarded-For` from right to left; it ignores `Forwarded`. Without it the Portal sees plain HTTP: no HSTS, redirects to `http://`, the audit trail records the proxy's address, and every visitor shares the proxy's per-address rate limit. **Never `framework`**: it believes what a client writes in `Forwarded` or at the front of `X-Forwarded-For`. The Helm chart sets `native` when its ingress (with `tls`) or route (`edge` or `reencrypt`) terminates TLS. See [Security → Behind a proxy](security.md#behind-a-proxy) |
| `SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES` | the private ranges: `10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 100.64.0.0/10, 169.254.0.0/16, 127.0.0.0/8, ::1/128, fc00::/7, fe80::/10` | The proxies whose `X-Forwarded-*` headers count, as comma-separated CIDR ranges, **each with its prefix length** (`/32` for one IPv4 address). A value without a `/` is read as a regular expression and matches no proxy. Narrow it to your proxies' own addresses when a proxy appends to `X-Forwarded-For`. Helm: `forwardedHeaders.internalProxies` |
| `ENS_COOKIE_SECURE` | `false` | `true`: the session cookie is always `Secure`. Without it, it is `Secure` only on requests the Portal itself received over TLS. The Helm chart sets it with the ingress or route as above |
| `ENS_CSP_REPORT_ONLY` | `false` | `true` sends `Content-Security-Policy-Report-Only` instead of enforcing the policy: a stopgap for a page the policy breaks |
| `ENS_CONSENT_BANNER_ENABLED` | `false` | Show the DoD notice-and-consent banner first in every browser session, and allow nothing else until the user clicks **I agree** |
| `ENS_CONSENT_BANNER_TITLE` | `U.S. Government (USG) Information System (IS) Notice and Consent` | Its heading |
| `ENS_CONSENT_BANNER_TEXT` | the standard DoD notice-and-consent text | Plain text; a blank line starts a new paragraph, and paragraphs that start with `- ` form a list |
| `ENS_CONSENT_BANNER_FILE` | none | A file to read the text from instead; wins over `ENS_CONSENT_BANNER_TEXT`. `.env` holds one line per setting, so a text of several paragraphs goes in a file: `default_build/properties/consent.txt` is `/custom/consent.txt` in the Portal container |
| `ENS_SESSION_IDLE_TIMEOUT` | `30m` | A session ends after this long without a request. Durations are Spring durations: `30m`, `15m`, `12h`, `90s` |
| `ENS_SESSION_ADMIN_IDLE_TIMEOUT` | the idle timeout | Idle timeout for users with an administrative role (PMO, Trusted Agent, Help Desk). DISA STIG suggests `15m` |
| `ENS_SESSION_ABSOLUTE_TIMEOUT` | `12h` | A session ends this long after sign-in, whatever its activity |
| `ENS_SESSION_MAX_PER_USER` | `0` (no limit) | Sessions one user may have at once; a sign-in over the limit ends that user's oldest session. DISA STIG suggests `3` |
| `security-banner.text`, `security-banner.background-color`, `security-banner.text-color` | `UNCLASSIFIED // FOR OFFICIAL USE ONLY`, `#007A33`, `#FFFFFF` | Classification banner at the top and bottom of every page. Empty text turns it off; the read-only demo never shows it. Helm: `securityBanner.*` |
| `user.token.test.maxAttempts`, `user.token.test.windowMinutes` | `5`, `15` | *Test My Token* attempts allowed per user ID in the window. Counted in the database, and held even with `rsa.rate-limit.enabled=false` |

!!! warning "Session timeout change from release 1.5"
    Release 1.5 shipped `server.servlet.session.timeout=480`, which Spring reads as 480 seconds: its sessions ended after 8 minutes idle. They now last 30 minutes unless you set `ENS_SESSION_IDLE_TIMEOUT`.

### Health, metrics and logs

| Name | Default | Description |
|---|---|---|
| `MANAGEMENT_SERVER_PORT` | unset | Spring Boot's setting: a port of its own for the actuator. There, without sign-in, are `/actuator/prometheus` and the health endpoints; the Portal's own port then has no actuator. Without it, Prometheus is not exposed at all. Never publish this port to users. The Helm chart sets it (`metrics.enabled`, `metrics.port: 9090`) |
| `LOGGING_STRUCTURED_FORMAT_CONSOLE` | unset (text) | Spring Boot's setting: `ecs` writes one JSON object per console log line (Elastic Common Schema), with the request id as `requestId`. The Helm chart does that by default (`logFormat: json`) |
| `management.endpoint.health.group.readiness.include` | `readinessState,db,connector` | What readiness waits for: the database and the Connector |

Each log line carries the request id: the caller's `X-Request-Id` if it is a plain token, otherwise a new one, returned in the response's `X-Request-Id` and recorded in the request's audit events. The metrics are listed in the [Operator Guide](operator-guide.md#health-and-monitoring).

### Search behaviour

| Name | Default | Description |
|---|---|---|
| `search.useridonly` | `false` | When `true`, user searches (token assignment, lost token, user search) look up an exact User ID, or a last name in the second field, instead of a free-text search over login ID and first, middle and last name (limited to 25 results per identity source). |
| `search.onlyReturnEnabled` | `false` | When `true`, free-text search results include only enabled principals. |

### Token distribution and enrollment

| Name | Default | Description |
|---|---|---|
| `system.user.token.registration.pin.set.start.date` | `2018-07-01` | `yyyy-MM-dd`. Bulk roll-out window. If today is **before** this date, a distributed token gets a random "allowed to set PIN" date between the start and end dates, and the user gets the delayed-PIN email instead of the registration link. The `sendDelayedTokenRegistrationEmails` job sends the link later. |
| `system.user.token.registration.pin.set.end.date` | `2018-08-01` | End of that window. |
| `user.registration.complete.path` | `/user/registration/start` | Path appended to `application.dns.name` in registration emails. |
| `ta.registration.profile.path` | `/ta/profile` | Path used in the Trusted Agent registered email. |
| `ta.profile.defaultWorkingHours` | `8:00-16:00` | Working hours given to newly registered Trusted Agents. |
| `exemptionrequest.url` | `/user/exemption/request` | Target of the **Request Exemption** link in the side menu, shown while the license includes `self-service`. |

### Active Directory (optional)

AD integration adds a user to the "RSA enabled" group when they complete enrollment and removes them when a token is marked lost or deprovisioned. **User Details** shows group membership and can toggle it. Connections are bound with `@ConfigurationProperties(prefix = "active-directory")`.

| Name | Default | Description |
|---|---|---|
| `active-directory.connections-allowed` | `false` | Master switch. When `false`, add and remove operations are logged and skipped. |
| `active-directory.properties[i].rsaSecurityDomainGuid` | sample | RSA AM security domain GUID whose users live in this AD. The user's domain selects the AD connection. |
| `active-directory.properties[i].contextFactory` | `com.sun.jndi.ldap.LdapCtxFactory` | JNDI context factory class. |
| `active-directory.properties[i].providerURL` | `ldap://ad.winllc-dev.com:389` | LDAP URL. |
| `active-directory.properties[i].security.authType` | `simple` | Bind type. |
| `active-directory.properties[i].security.username` | sample DN | Bind DN. |
| `active-directory.properties[i].security.password` | empty | Bind password. |
| `active-directory.properties[i].rsaEnabledGroup` | sample DN | DN of the group whose members must use RSA to sign in to their workstation. |
| `active-directory.properties[i].userSearchBase` | sample DN | Base for `samAccountName` lookups. |
| `active-directory.properties[i].trust-store`, `.trust-store-password` | not set | A JKS or PKCS12 file with the AD CA, for `ldaps://` with a private CA. One store must hold the CAs of every AD instance. Without it, the JVM's default trust store is used. Either way the domain controller's certificate and host name are checked |
| `active-directory.properties[i].connect-timeout-millis`, `.read-timeout-millis` | `5000`, `15000` | Give up on an unreachable or stalled domain controller |
| `active-directory.properties[i].page-size` | `500` | Large searches are paged; keep it at or below AD's `MaxPageSize` (1000) |
| `active-directory.properties[i].ldap-factory-socket` | empty | Optional `java.naming.ldap.factory.socket` class. Leave it empty: with a trust store set, the Portal uses its own `ActiveDirectorySocketFactory`, which checks certificates. |

An instance with a missing setting is left out at startup, and the log says which setting is missing.

!!! note
    `applicationContext.xml` contains a commented-out legacy block that uses `activeDirectory.1.*` and `activeDirectory.2.*` keys. Those keys are not used. Use the `active-directory.properties[i].*` form.

### Scheduled jobs

For every job `<name>` in `expiring`, `appointmentupcoming`, `resyncta`, `lostTokenThresholdMonitor`, `sendDelayedTokenRegistrationEmails` and `tokenPinCheck`:

| Name | Description |
|---|---|
| `cron.<name>.enabled` | `true` lets the job run (on schedule or by hand). |
| `cron.<name>.debug` | `true` runs the job without sending email. |
| `cron.<name>.description` | Label shown on the Manual Cron page. |
| `cron.<name>.cron` | Spring 6-field cron expression (sec min hour day month weekday). |

| Name | Default | Description |
|---|---|---|
| `cron.timezone` | the server's (UTC in the containers) | Time zone the schedules mean, for example `America/New_York`. Helm: `cronTimezone` |
| `cron.lock.maximumHoldMinutes` | `360` | A job's claim in the `CronLock` table left by a node that died is released after this |
| `cron.lock.minimumHoldSeconds` | `120` | A claim is held at least this long, so a node with a slightly late clock does not run the same occurrence again |

| Name | Default |
|---|---|
| `cron.expiring.enabled` / `.debug` / `.cron` | `true` / `false` / `0 30 0 ? * *` |
| `cron.expiring.notification.daysBefore` | `30,14,7,1` (one notice each) |
| `cron.appointmentupcoming.enabled` / `.debug` / `.cron` | `true` / `false` / `0 30 0 ? * *` |
| `cron.appointmentupcoming.remindDaysBefore` | `1` |
| `cron.appointmentupcoming.taDigestDays` | `7` |
| `cron.resyncta.enabled` / `.debug` / `.cron` | `true` / `false` / `0 30 0 ? * *` |
| `cron.lostTokenThresholdMonitor.enabled` / `.debug` / `.cron` | `true` / **`true`** / `0 35 0 ? * *` |
| `cron.lostTokenThresholdMonitor.window.inDays` | `30` |
| `cron.lostTokenThresholdMonitor.tokens.lost` | `3` |
| `cron.sendDelayedTokenRegistrationEmails.enabled` / `.debug` / `.cron` | `true` / `false` / `0 32 0 ? * *` |
| `cron.sendDelayedTokenRegistrationEmails.daysBetweenReminderEmails` | `3` |
| `cron.tokenPinCheck.enabled` / `.debug` / `.cron` | `true` / `false` / `0 30 0 ? * *` |
| `cron.tokenPinCheck.daysSinceLastLogin` | `60` |
| `cron.tokenPinCheck.daysBetweenReminderEmails` | `14` |
| `cron.tokenPinCheck.resetLinkValidDays` | `14` |
| `cron.tokenPinCheck.resetLinkMaxAttempts` | `5` |

`resetLinkValidDays` is how long the reset link in a PIN-check email works, and `resetLinkMaxAttempts` how many tokencodes one link accepts before it refuses more.

The descriptions default to: "Send Expiring Token Reminder Cron", "Send Upcoming Appointments Email Cron", "Re-sync Trusted Agents with Authentication Manager Cron", "Identify Users with multiple lost Tokens", "Send Delayed Token Registration Emails Cron" and "Reset Token Pin Checker". [Architecture](architecture.md#scheduled-jobs) describes what each job does.

### Help-desk controls

The `rsa.helpdesk.*` settings (Helm: `helpDesk.*`, the rest under `config`) decide how operators
verify a user, whose changes need a second operator, how long requests and emergency codes
wait, and the key the e-mailed codes are kept under (`rsa.helpdesk.code-key`, which `install.sh`
and `upgrade.sh` add to `.env`). They are listed, with their defaults, in
[Help-desk requests → Settings](help-desk.md#settings).

### Rate limits

Limits are written as `attempts/duration`, for example `30/10m`, and counted in fixed windows aligned to the clock (UTC), not sliding ones. The
Portal checks these settings at startup and stops with the name of any setting that makes no
sense. In `.env` use the property names: `util/check-env.sh` refuses `RSA_*` variables there,
which are the Connector's. Elsewhere the environment forms are `RSA_RATELIMIT_ENABLED`,
`RSA_RATELIMIT_SIGNIN_PERUSER`, `RSA_RATELIMIT_BACKOFF_BASE` and so on. What they do is in
[Security → Rate limits and uniform answers](security.md#rate-limits-and-uniform-answers).

| Name | Default | Helm | Description |
|---|---|---|---|
| `rsa.rate-limit.enabled` | `true` | `rateLimit.enabled` | Apply the limits, backoff and uniform time. `false` is logged as a warning at every start. The feature caps of Test My Token, the PIN reset link and the token swap hold either way |
| `rsa.rate-limit.sign-in.per-ip`, `.per-user` | `120/10m`, `10/10m` | `rateLimit.signIn.perIp`, `.perUser` | Sign-in attempts per client address and per user ID |
| `rsa.rate-limit.self-service.per-ip`, `.per-user` | `60/10m`, `10/10m` | `rateLimit.selfService.perIp`, `.perUser` | Test My Token, the PIN resets, registration and exemption requests: per client address, and per user ID or token serial |
| `rsa.rate-limit.appointment.per-ip`, `.per-user` | `20/1h`, `3/24h` | `rateLimit.appointment.perIp`, `.perUser` | Appointment bookings and cancellations: per client address, and per requester user ID |
| `rsa.rate-limit.appointment.max-open-per-user` | `2` | `rateLimit.appointment.maxOpenPerUser` | Open appointments (not cancelled, today or later) one user ID may have. More are not booked |
| `rsa.rate-limit.helpdesk-code.per-operator`, `.per-user` | `30/1h`, `10/1h` | `rateLimit.helpdeskCode.perOperator`, `.perUser` | The help desk's code sends and code or tokencode checks |
| `rsa.rate-limit.backoff.free-failures` | `3` | `rateLimit.backoff.freeFailures` | Failures for a user ID or serial before the waiting starts |
| `rsa.rate-limit.backoff.base`, `.max` | `30s`, `15m` | `rateLimit.backoff.base`, `.max` | The first wait, which doubles with each further failure, and the longest |
| `rsa.rate-limit.backoff.forget-after` | `1h` | `rateLimit.backoff.forgetAfter` | After this long without a failure, the count starts again from the next failure (not a rolling hour) |
| `rsa.rate-limit.uniform-response-time` | `1s` | `rateLimit.uniformResponseTime` | How long a failed anonymous step takes at least, whatever the cause. `0` (off) to `10s` |

With Helm, an empty value keeps the default.

### Branding, text and email templates

| Name | Default | Description |
|---|---|---|
| `messages.location` | `classpath:properties/messages` | Basename of the UI message bundle (no extension). |
| `messages.file.location` | `classpath:properties/messages.properties` | The same file loaded as a placeholder source (for `branding.userId`, `link.faqs` and `login.additional.param.*`). |
| `page-descriptions.location` | `classpath:properties/page-descriptions` | Basename of the page-description bundle. |
| `email-description.location` | `classpath:email-templates/*.properties` | Email template files (placeholder source for `email-templates.xml`). |
| `custom-resource.location` | `classpath:/static/custom/` | Location served at `/static/custom/**` (`banner.png`, `header-logo.png`). |

The release package's `docker-compose.yml` mounts the editable copies from `default_build/` at `/custom` (messages and page descriptions), `/static` (images) and `/resources/email-templates` (templates). Those paths are not on the application classpath, so to use the mounted copies, point the properties above at them with `file:` URLs. For example (paths taken from the mounts):

```properties
messages.location=file:/custom/messages
messages.file.location=file:/custom/messages.properties
page-descriptions.location=file:/custom/page-descriptions
custom-resource.location=file:/static/
email-description.location=file:/resources/email-templates/*.properties
```

!!! tip
    The message source reloads at most every 1000 seconds (`setCacheSeconds(1000)`). Placeholder values (`@Value` and the email templates) are read only at startup, so restart the portal after you change email templates.

Keys in `messages.properties` that the layout and controllers depend on:

| Key | Default | Use |
|---|---|---|
| `branding.application.name` | `RSA Operations Portal` | Page title |
| `branding.organization.name` | `New Org` | Footer link text |
| `branding.userId` | `User ID` | Label for the principal ID prompt |
| `link.mfa.homepage`, `link.organization.homepage`, `link.service.desk`, `link.faqs`, `link.contact.us` | `https://google.com` | Footer links (`link.faqs` also appears in emails) |
| `link.homepage.what`, `link.homepage.why` | `https://google.com` | Home page "What?" and "Why?" links |
| `build.version` | `1.0.0` | Footer "Build Version" |
| `login.additional.param.*.message` | see file | Hints shown on the next-tokencode, new-PIN and passcode prompts |
| `user.registration.userAgreement` | Lorem ipsum | Terms the user must accept before enrollment. **Replace it.** |

Email templates are `.properties` files with a `…subject` and `…body` key. They use `java.text.MessageFormat` placeholders (`{0}`, `{1}`…). Example (`user-token-distributed-notification.properties`):

```properties
user.token.distributed.notification.subject=RSA SecurID Token Pin Activation
user.token.distributed.notification.body=Hello {0},\n\n\
Thank you for picking up your RSA token. To complete the enrollment process ... navigate to {1} to set your PIN (6 Digits).\n\n\
If you have any additional questions, please contact your TA {2} or visit our FAQ page at {3}.
```

The placeholder order is fixed by the code, so keep the same `{n}` meanings when you reword a template.

### Logging

| File | Mounted at | Default behaviour |
|---|---|---|
| `default_build/portal/log4j2.xml` | `/log4j2.xml` (selected by `-Dlogging.config=/log4j2.xml`) | Console plus a rolling file `/var/log/rsaapp-portal.log` (host `./logs/`), rolled daily or at 10 MB into `rsaapp-portal-YYYY-MM/app-MM-dd-yyyy-N.log.gz`. Root level `info`. Messages are written with `%enc{%m}{CRLF}`, so their line breaks appear as `\r` and `\n`. |
| `default_build/log4j.xml` | `/custom/log4j.xml` in the connector | Log4j 1.x configuration with a rolling file `/var/log/connector.log` (10 MB × 5). The image does not select it automatically. Pass it through `JAVA_OPTS` if you want it. Without it, the connector uses the packaged `log4j.xml` (stderr, root `DEBUG`). Both use the layout `com.winllc.rsaapp.connector.logging.CrlfSafePatternLayout`. |

In the text logs of both, CR and LF in a message are written as the two characters `\r` and `\n`, so request data in a message (a user ID someone typed) cannot start a line that looks like another entry. A stack trace still follows its message on lines of its own. The Portal's JSON format escapes them anyway. A configuration of your own should keep `%enc{%m}{CRLF}` (Portal) or `CrlfSafePatternLayout` (Connector).

## Connector (RSA Authentication Manager) settings

The connector resolves these through `CommandClientAppContextOverrides.xml` from the file named by **`config.properties.location`** (a JVM system property inside the Connector's `JAVA_OPTS` in `connector.env`: the release package keeps `-Dconfig.properties.location=classpath:config.properties` and passes the settings below as `-D` options), with system properties taking precedence. The packaged `WEB-INF/config.properties` contains development host names and no passwords. Override the host names.

**Accounts and passwords.** The Connector reads these itself, before its application context starts (`ConnectorSecrets`). For each, the first of these wins: the file named by `NAME_FILE` (or by the system property `<property>.file`), then the environment variable `NAME`, then the `-D` option, which still works for a password but logs a warning, because the Java command line is readable by every user on the host.

| Variable (also `<NAME>_FILE`) | Property | Meaning |
|---|---|---|
| `RSA_CMDCLIENT_USER`, `RSA_CMDCLIENT_PASSWORD` | `com.rsa.cmdclient.user`, `com.rsa.cmdclient.user.password` | The Command Client user name and password (from `rsautil manage-secrets --action list`) |
| `RSA_ADMIN_USER_ID`, `RSA_ADMIN_USER_PASSWORD` | `rsa.admin.user.id`, `rsa.admin.user.password` | The service account: an **internal** AM user with the [service account role](rsa-integration.md#least-privilege-am-roles). It makes only the changes no signed-in operator makes |
| `RSA_READONLY_USER_ID`, `RSA_READONLY_USER_PASSWORD` | `rsa.readonly.user.id`, `rsa.readonly.user.password` | Recommended: an internal AM user with a view-only role, for searches and lookups. Unset: the service account does them, and the Connector logs a warning |
| `RSA_TRUSTSTORE_PASSWORD` | `ims.ssl.client.trust.keystore.password`, `com.rsa.ssl.trust.store.password` | Password of `trust.jks` |

`util/move-connector-secrets.sh`, which `install.sh` and `upgrade.sh` run, moves these from the `-D` options of an older `connector.env` into the variables, keeping the original as `connector.env.before-secrets.<time>` (mode 600; delete it once the Connector runs). The Helm chart mounts them from Secrets as files under `/var/run/secrets/rsa`.

The Connector's HTTPS settings (`CONNECTOR_TLS_*`, `CONNECTOR_HTTPS_ADDRESS`, `CONNECTOR_HTTP_ADDRESS`) are in [RSA AM integration → Portal to Connector](rsa-integration.md#portal-to-connector).

**The connection to AM.** Also in `connector.env` (Helm: `connector.extraEnv`), all optional:

| Variable | Meaning |
|---|---|
| `RSA_TLS_VERIFY_HOSTNAME` | `false` stops the Connector checking that AM's certificate names the host in its URLs (it still checks the CA). Only for an AM reached by an IP address or a name its certificate does not have; the Connector warns at every start. See [AM's host name](rsa-integration.md#transport) |
| `CONNECTOR_SERIAL_FILTER_ALLOW` | More classes the Connector accepts in AM's replies, separated by commas: a class, a package (`com.rsa.example.*`) or a package and its subpackages (`.**`) |
| `CONNECTOR_SERIAL_FILTER_LIMITS` | Other limits for those replies: `maxdepth=`, `maxrefs=`, `maxarray=` and `maxbytes=` separated by `;` (defaults 32, 10000000, 1000000 and 268435456) |
| `CONNECTOR_SERIAL_FILTER` | Emergency only: `off`, exactly, reads those replies without the filter, with a warning at every start; any other value stops the Connector |

See [What the Connector accepts](rsa-integration.md#what-the-connector-accepts).

The other settings:

| Name | Default (packaged `config.properties`) | Description |
|---|---|---|
| `config.properties.location` | none (system property) | URL (`file:` or `classpath:`) of the properties file to load. |
| `java.naming.factory.initial` | `weblogic.jndi.WLInitialContextFactory` | JNDI factory for the RSA SDK. |
| `java.naming.provider.url` | dev host | `t3s://<AM primary FQDN>:7002` |
| `ims.httpinvoker.client.provider.url` | dev host | `https://<AM primary FQDN>:7002/ims-ws/httpinvoker/CommandServer`, used for admin (write) commands. Use the name in AM's certificate: the Connector checks it. (`GUIDE.md` spells it `ims.httpinvokder…`, but the code uses `httpinvoker`.) |
| `ims.httpinvoker.client.provider.readonly.url` | dev host | `https://<AM primary or replica FQDN>:7002/ims-ws/httpinvoker/CommandServer`, used for read-only searches. |
| `ims.soap.client.provider.url` | dev host | `https://<AM primary FQDN>:7002/ims-ws/services/CommandServer` (SOAP target in the SDK context). |
| `ims.ssl.client.trust.keystore.filename` | developer workstation path | Path to the trust store holding the AM root CA. With the compose mounts, `default_build/ssl/trust.jks` is visible to the connector as `/custom/ssl/trust.jks`. |
| `com.rsa.ssl.client.id.store.password`, `com.rsa.ssl.client.id.key.password` | empty | Two-way SSL identity keystore and key passwords (only for client-certificate command targets, which the connector does not use). |
| `ims.ssl.client.provider.url` | `t3s://local1:7022` | Two-way SSL provider URL. |
| `ims.ssl.client.identity.keystore.filename` | `client-identity.jks` | Two-way SSL identity keystore. |
| `ims.ssl.client.identity.key.alias` | `client-identity` | Identity key alias. |
| `ims.ssl.client.root.ca.alias` | `rsa_am_ca` | Alias of the AM root CA in the trust store. |
| `rsa.authmanager.realm.name` | `SystemDomain` | Realm to search. The connector fails its session check if the realm is not found. |
| `rsa.authmanager.securityDomain.name` | `SystemDomain` | Security domain the portal manages and searches (sub-domains included). |
| `token.serial.prefix` | `000` | Token serial prefix (keep it equal to the portal value). |

## Example: minimal values to change for a new install

The settings that must change for a new install, by the release package file they go in. The AM accounts are `RSA_*` variables in `connector.env`; the other RSA AM settings are `-D` options in `JAVA_OPTS` there. `CONNECTOR_AUTH_PASSWORD` goes in both `.env` and `connector.env`, with the same value. With Helm, use the values listed in [Installation → Write your values](install.md#3-write-your-values).

=== "connector.env (RSA AM)"

    ```properties
    CONNECTOR_AUTH_PASSWORD=<long random value, the same as in .env>
    RSA_CMDCLIENT_USER=CmdClient_xxxxxxxx
    RSA_CMDCLIENT_PASSWORD=xxxxxxxxxx
    RSA_ADMIN_USER_ID=portal_service
    RSA_ADMIN_USER_PASSWORD=********
    RSA_READONLY_USER_ID=portal_readonly
    RSA_READONLY_USER_PASSWORD=********
    RSA_TRUSTSTORE_PASSWORD=********
    # -D options inside JAVA_OPTS:
    java.naming.provider.url=t3s://am1.example.org:7002
    ims.soap.client.provider.url=https://am1.example.org:7002/ims-ws/services/CommandServer
    ims.httpinvoker.client.provider.url=https://am1.example.org:7002/ims-ws/httpinvoker/CommandServer
    ims.httpinvoker.client.provider.readonly.url=https://am2.example.org:7002/ims-ws/httpinvoker/CommandServer
    ims.ssl.client.trust.keystore.filename=/custom/ssl/trust.jks
    rsa.authmanager.securityDomain.name=SystemDomain
    ```

=== ".env (Portal)"

    ```properties
    CONNECTOR_AUTH_PASSWORD=<long random value, the same as in connector.env>
    CONNECTOR_TLS_TRUSTSTORE=/ssl/connector-ca.crt
    spring.datasource.url=jdbc:mysql://mysql:3306/RSAAPP?sslMode=REQUIRED
    spring.datasource.username=appuser
    spring.datasource.password=********
    spring.mail.host=smtp.example.org
    mail.default.from=noreply@example.org
    mfa.group.email=mfa-team@example.org
    application.dns.name=https://mfa.example.org:8443
    role.mapped.1.rsaSecurityGuid=ims.<trusted agent role guid>
    role.mapped.2.rsaSecurityGuid=ims.<pmo role guid>
    role.mapped.3.rsaSecurityGuid=ims.<help desk role guid>
    ```

=== "mysql.env (MySQL)"

    ```properties
    MYSQL_ROOT_PASSWORD=********
    MYSQL_PASSWORD=********
    ```

The host names, file paths and the trust-store path above are examples. Use the values for your environment.
