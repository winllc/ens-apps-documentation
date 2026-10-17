# Configuration

With the release package, the runtime configuration lives in three files in the install folder, one per service. Each file is given only to its own service:

| File | Created from | Service | Holds |
|---|---|---|---|
| **`.env`** | `env.template` | Portal, which also reads it as its `application.properties` | The Portal settings on this page |
| **`connector.env`** | `connector.env.template` | Connector | `CONNECTOR_AUTH_PASSWORD`, and `JAVA_OPTS` with the [Connector settings](#connector-rsa-authentication-manager-settings) as `-D` options |
| **`mysql.env`** | `mysql.env.template` | MySQL | `MYSQL_ROOT_PASSWORD`, `MYSQL_DATABASE`, `MYSQL_USER`, `MYSQL_PASSWORD` |

`install.sh` creates each file from its template (mode 600); the templates comment every setting. See [How the settings files are consumed](#how-the-settings-files-are-consumed). With the Helm chart, the same settings come from your values file: [Installation → Write your values](install.md#3-write-your-values) maps each value to its setting below, and any other Portal setting goes in `config`.

This page lists every setting the code reads, grouped by component, with the default packaged in the images. Several of those defaults are development hostnames and credentials that **must** be overridden.

!!! danger "Packaged sample credentials"
    The `application.properties` and `config.properties` packaged in the images contain sample passwords for the datasource, the AD bind account, the RSA admin user and the command client. Treat them as public and override every one of them.

## How the settings files are consumed

This applies to the release package. The Helm chart sets the same settings as environment variables and `-D` options.

| Consumer | Mechanism (from the package's `docker-compose.yml`) | Effect |
|---|---|---|
| **portal** | `env_file: .env` (and `global.env`, which holds the release version) | Every `key=value` becomes an environment variable. Spring Boot resolves property names from environment variables, and the image's entry point uses `$JAVA_OPTS`. |
| **portal** | `./.env:/application.properties` | The container's working directory is `/`, so Spring Boot also loads `.env` as an external `application.properties`, which overrides the defaults packaged in the image. |
| **connector** | `env_file: connector.env` | Environment variables. Tomcat's `catalina.sh` honours `JAVA_OPTS`, which carries the Connector settings as `-D` options. |
| **mysql** | `env_file: mysql.env` | Standard `mysql` image variables: `MYSQL_ROOT_PASSWORD`, `MYSQL_DATABASE`, `MYSQL_USER`, `MYSQL_PASSWORD`. |

The Portal, which users reach, never receives the AM credentials or the MySQL root password. `util/check-env.sh` refuses a `.env` that holds the Connector's `-D` options in `JAVA_OPTS` or any `MYSQL_*` setting.

```text
# .env (the Portal; excerpt, values are placeholders)
CONNECTOR_AUTH_PASSWORD=<shared secret>
spring.datasource.url=jdbc:mysql://mysql:3306/RSAAPP
spring.datasource.username=appuser
spring.datasource.password=<app password>
spring.mail.host=smtp.example.org
...

# connector.env (the Connector)
CONNECTOR_AUTH_PASSWORD=<shared secret>
JAVA_OPTS=-Djava.security.egd=file:/dev/./urandom -Dhttps.protocols=TLSv1.2 -Djdk.tls.client.protocols=TLSv1.2 -Dlogging.config=/log4j2.xml -Dconfig.properties.location=classpath:config.properties -Djava.naming.provider.url=t3s://am-primary.example.org:7002 ...

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
| `JAVA_OPTS` | `-Djava.security.egd=file:/dev/./urandom -Dhttps.protocols=TLSv1.2 -Djdk.tls.client.protocols=TLSv1.2 -Dlogging.config=/log4j2.xml` (set in both images) | portal, connector | JVM options. A `JAVA_OPTS` line in a service's settings file **replaces** that image's default, so keep the default flags when you set it. The Portal's is optional, in `.env`; `env.template` leaves it commented out, so the image default applies. The Connector's is in `connector.env` and carries the RSA settings below, passed as `-D` system properties. The Helm chart builds them from `authManager.*`. The connector gives system properties precedence (`SYSTEM_PROPERTIES_MODE_OVERRIDE`). |
| `MYSQL_ROOT_PASSWORD` | none | mysql, `util/backupdb.sh`, `upgrade.sh` | Root password of the MySQL container, in `mysql.env`. Read once, when the data volume is first created. The backup scripts read it inside the `mysql` container, so it never appears on a command line. |
| `MYSQL_DATABASE`, `MYSQL_USER`, `MYSQL_PASSWORD` | `RSAAPP`, `appuser`, none | mysql | In `mysql.env`. The database and application user MySQL creates on its first start. `MYSQL_PASSWORD` must equal `spring.datasource.password` in `.env`. |
| `MFAAPP_PROPERTY_FILES_LOCATION` | unset | portal | If set to an existing directory, **every regular file** under it is loaded as a placeholder property source **instead of** `messages.file.location` and `email-description.location`. Startup fails with "No property files loaded from custom location" if the directory has no files. |

### Ports and volumes (release package `docker-compose.yml`)

| Service | Published ports | Volumes (host → container) |
|---|---|---|
| `portal` | `8080:8080`, `8443:8443` | `./default_build/properties:/custom`, `./default_build/static:/static`, `./default_build/email-templates:/resources/email-templates`, `./default_build/ssl:/ssl`, `./default_build/portal/log4j2.xml:/log4j2.xml`, `./logs:/var/log`, `ens-license:/var/lib/ens/mfa-portal`, `./.env:/application.properties` |
| `connector` | none (reached internally as `connector:8080`) | `./default_build:/custom`, `./logs:/var/log` |
| `mysql` | none | named volume `mysql_data:/var/lib/mysql` |

The Portal's container health check is `curl -fs http://localhost:8080/login`. The Portal starts after the `connector` has started and once `mysql` passes `mysqladmin ping`.

With Helm, the Portal and the Connector are two Deployments, the license is on a PVC, and branding files come from ConfigMaps (`branding.*`). See [Installation → Install with Helm](install.md#install-with-helm).

## Portal settings

### Server, sessions and uploads

| Name | Default | Description |
|---|---|---|
| `server.port` | `8080` | HTTP port inside the container. |
| `server.servlet.session.timeout` | `480` | Session timeout. With no unit suffix Spring Boot reads this as seconds (8 minutes). |
| `spring.servlet.multipart.max-file-size` | `5MB` | Largest file accepted (Trusted Agent CSV upload, group-email attachment). |
| `spring.servlet.multipart.max-request-size` | `5MB` | Largest multipart request. |
| `server.ssl.*` | not set | Port `8443` is published, but the release package configures no TLS by default. Several features read the browser's **client certificate**, so HTTPS with client-certificate negotiation must be configured with the standard Spring Boot `server.ssl.*` properties. In the release package, the `./default_build/ssl` directory is mounted at `/ssl` for keystores. With Helm, set `tls.*` instead. See [Security](security.md#tls-and-client-certificates). |
| `application.dns.name` | `http://localhost:8080` | External base URL of the portal. It prefixes every link in outgoing emails (registration, PIN reset, appointment and shipment details, Trusted Agent directory). |

### Database

| Name | Default | Description |
|---|---|---|
| `spring.datasource.url` | `jdbc:mysql://database.winllc-dev.com/RSAAPP` | JDBC URL. In the release package the MySQL host name is `mysql`: `jdbc:mysql://mysql:3306/RSAAPP`. The Helm chart builds it from `database.*`. |
| `spring.datasource.username` | `appuser` | Database user. The release package's MySQL creates `appuser` (`MYSQL_USER`). |
| `spring.datasource.password` | sample value | Database password. |
| `spring.datasource.driver-class-name` | `com.mysql.cj.jdbc.Driver` | JDBC driver. |
| `spring.jpa.hibernate.ddl-auto` | `update` | Hibernate creates and updates tables automatically. |
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
| `exemptionrequest.url` | unset | Target of the **Request Exemption** link in the side menu (read with `environment.getProperty`). Set it, for example to `/user/exemption/request`, or the link has no target. |

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
| `active-directory.properties[i].security.password` | sample | Bind password. |
| `active-directory.properties[i].rsaEnabledGroup` | sample DN | DN of the group whose members must use RSA to sign in to their workstation. |
| `active-directory.properties[i].userSearchBase` | sample DN | Base for `samAccountName` lookups. |
| `active-directory.properties[i].ldap-factory-socket` | empty | Optional `java.naming.ldap.factory.socket` class. The code ships `com.winllc.rsaapp.portal.security.ActiveDirectorySocketFactory`, which **trusts all certificates**, so use it only knowingly. |

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

| Name | Default |
|---|---|
| `cron.expiring.enabled` / `.debug` / `.cron` | `true` / `false` / `0 30 0 ? * *` |
| `cron.expiring.notification.expireInDays` | `720` |
| `cron.appointmentupcoming.enabled` / `.debug` / `.cron` | `true` / `false` / `0 30 0 ? * *` |
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
| `default_build/portal/log4j2.xml` | `/log4j2.xml` (selected by `-Dlogging.config=/log4j2.xml`) | Console plus a rolling file `/var/log/rsaapp-portal.log` (host `./logs/`), rolled daily or at 10 MB into `rsaapp-portal-YYYY-MM/app-MM-dd-yyyy-N.log.gz`. Root level `info`. |
| `default_build/log4j.xml` | `/custom/log4j.xml` in the connector | Log4j 1.x configuration with a rolling file `/var/log/connector.log` (10 MB × 5). The image does not select it automatically. Pass it through `JAVA_OPTS` if you want it. Without it, the connector uses the packaged `log4j.xml` (stderr, root `DEBUG`). |

## Connector (RSA Authentication Manager) settings

The connector resolves these through `CommandClientAppContextOverrides.xml` from the file named by **`config.properties.location`** (a JVM system property inside the Connector's `JAVA_OPTS` in `connector.env`: the release package keeps `-Dconfig.properties.location=classpath:config.properties` and passes the settings below as `-D` options), with system properties taking precedence. The packaged `WEB-INF/config.properties` contains development values. Override all of them.

| Name | Default (packaged `config.properties`) | Description |
|---|---|---|
| `config.properties.location` | none (system property) | URL (`file:` or `classpath:`) of the properties file to load. |
| `java.naming.factory.initial` | `weblogic.jndi.WLInitialContextFactory` | JNDI factory for the RSA SDK. |
| `java.naming.provider.url` | dev host | `t3s://<AM primary FQDN>:7002` |
| `com.rsa.cmdclient.user` | sample | Command client user name (from `rsautil manage-secrets --action list`). |
| `com.rsa.cmdclient.user.password` | sample | Command client password. The package's `GUIDE.md` writes this key as `com.rsa.cmdclient.password`, but the packaged file uses `com.rsa.cmdclient.user.password`. |
| `ims.httpinvoker.client.provider.url` | dev host | `https://<AM primary FQDN>:7002/ims-ws/httpinvoker/CommandServer`, used for admin (write) commands. (`GUIDE.md` spells it `ims.httpinvokder…`, but the code uses `httpinvoker`.) |
| `ims.httpinvoker.client.provider.readonly.url` | dev host | `https://<AM primary or replica FQDN>:7002/ims-ws/httpinvoker/CommandServer`, used for read-only searches. |
| `ims.soap.client.provider.url` | dev host | `https://<AM primary FQDN>:7002/ims-ws/services/CommandServer` (SOAP target in the SDK context). |
| `ims.ssl.client.trust.keystore.filename` | developer workstation path | Path to the trust store holding the AM root CA. With the compose mounts, `default_build/ssl/trust.jks` is visible to the connector as `/custom/ssl/trust.jks`. |
| `ims.ssl.client.trust.keystore.password` | none in file (listed in `GUIDE.md`) | Trust store password. |
| `com.rsa.ssl.trust.store.password` | `changeit` | Trust store password used by the SDK. (The package's `GUIDE.md` writes `com.rsa.ssltrust.store.password`.) |
| `com.rsa.ssl.client.id.store.password` | `password` | Two-way SSL identity keystore password (only for client-certificate command targets, which the connector does not use by default). |
| `com.rsa.ssl.client.id.key.password` | `password` | Two-way SSL private key password. |
| `ims.ssl.client.provider.url` | `t3s://local1:7022` | Two-way SSL provider URL. |
| `ims.ssl.client.identity.keystore.filename` | `client-identity.jks` | Two-way SSL identity keystore. |
| `ims.ssl.client.identity.key.alias` | `client-identity` | Identity key alias. |
| `ims.ssl.client.root.ca.alias` | `rsa_am_ca` | Alias of the AM root CA in the trust store. |
| `rsa.admin.user.id` | sample | **Internal** RSA AM user with Super Admin rights. The connector logs in as this user for its admin and read-only sessions. |
| `rsa.admin.user.password` | sample | Its password. |
| `rsa.authmanager.realm.name` | `SystemDomain` | Realm to search. The connector fails its session check if the realm is not found. |
| `rsa.authmanager.securityDomain.name` | `SystemDomain` | Security domain the portal manages and searches (sub-domains included). |
| `token.serial.prefix` | `000` | Token serial prefix (keep it equal to the portal value). |

## Example: minimal values to change for a new install

The settings that must change for a new install, by the release package file they go in. The RSA AM settings are `-D` options in `JAVA_OPTS` in `connector.env`. `CONNECTOR_AUTH_PASSWORD` goes in both `.env` and `connector.env`, with the same value. With Helm, use the values listed in [Installation → Write your values](install.md#3-write-your-values).

=== "connector.env (RSA AM)"

    ```properties
    CONNECTOR_AUTH_PASSWORD=<long random value, the same as in .env>
    # -D options inside JAVA_OPTS:
    java.naming.provider.url=t3s://am1.example.org:7002
    com.rsa.cmdclient.user=CmdClient_xxxxxxxx
    com.rsa.cmdclient.user.password=xxxxxxxxxx
    rsa.admin.user.id=portal_superadmin
    rsa.admin.user.password=********
    ims.soap.client.provider.url=https://am1.example.org:7002/ims-ws/services/CommandServer
    ims.httpinvoker.client.provider.url=https://am1.example.org:7002/ims-ws/httpinvoker/CommandServer
    ims.httpinvoker.client.provider.readonly.url=https://am2.example.org:7002/ims-ws/httpinvoker/CommandServer
    ims.ssl.client.trust.keystore.filename=/custom/ssl/trust.jks
    ims.ssl.client.trust.keystore.password=********
    com.rsa.ssl.trust.store.password=********
    rsa.authmanager.securityDomain.name=SystemDomain
    ```

=== ".env (Portal)"

    ```properties
    CONNECTOR_AUTH_PASSWORD=<long random value, the same as in connector.env>
    spring.datasource.url=jdbc:mysql://mysql:3306/RSAAPP
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
