# Configuration

All runtime configuration for a deployment lives in one file, **`dev.env`**, in the deployment directory. Both application containers and the MySQL container read it, and it is mounted into them in several ways (see [How dev.env is consumed](#how-devenv-is-consumed)). This page lists every setting the code reads, grouped by component, with the default that ships in the source.

!!! warning "`dev.env` is not in the repository"
    `docker-compose*.yaml`, `install.sh` and `prepare_deployment_package.sh` all expect a `dev.env` file, but none is committed to the repository. Build it from the tables below. The defaults shown here are the values in `rsaapp-portal/src/main/resources/application.properties` and `rsaapp-connector/src/main/resources/config.properties`. Several of those defaults are development hostnames and credentials that **must** be overridden.

!!! danger "Committed sample credentials"
    The committed `application.properties`, `config.properties` and `mysql/01-databases.sql` contain sample passwords for the datasource, the AD bind account, the RSA admin user, the command client and the MySQL application user. Treat them as public and never reuse them in a deployment.

## How `dev.env` is consumed

| Consumer | Mechanism (from `docker-compose.yaml` and the Dockerfiles) | Effect |
|---|---|---|
| **portal** | `env_file: dev.env` | Every `key=value` becomes an environment variable. Spring Boot resolves property names from environment variables, and the image's `ENTRYPOINT` uses `$JAVA_OPTS`. |
| **portal** | `./dev.env:/application.properties` | The container's working directory is `/`, so Spring Boot also loads `dev.env` as an external `application.properties`, which overrides the defaults packaged in the jar. |
| **connector** | `env_file: dev.env` | Environment variables. Tomcat's `catalina.sh` honours `JAVA_OPTS`. |
| **connector** | `./dev.env:/properties/dev.env` | Readable as a properties file by the RSA SDK configuration once `config.properties.location` points at it (see [Connector settings](#connector-rsa-authentication-manager-settings)). |
| **mysql** | `env_file: dev.env` | Standard `mysql` image variables such as `MYSQL_ROOT_PASSWORD`. |

```text
# dev.env skeleton (illustrative — values are placeholders)
JAVA_OPTS=-Djava.security.egd=file:/dev/./urandom -Dhttps.protocols=TLSv1.2 -Djdk.tls.client.protocols=TLSv1.2 -Dlogging.config=/log4j2.xml -Dconfig.properties.location=file:/properties/dev.env ...
MYSQL_ROOT_PASSWORD=<root password>

# --- RSA AM (connector) ---
java.naming.provider.url=t3s://am-primary.example.org:7002
com.rsa.cmdclient.user=CmdClient_xxxxxxxx
com.rsa.cmdclient.user.password=<from rsautil manage-secrets>
...

# --- Portal ---
spring.datasource.url=jdbc:mysql://mysql:3306/RSAAPP
spring.datasource.username=appuser
spring.datasource.password=<app password>
spring.mail.host=smtp.example.org
...
```

## Container and JVM settings

| Name | Default | Used by | Description |
|---|---|---|---|
| `JAVA_OPTS` | `-Djava.security.egd=file:/dev/./urandom -Dhttps.protocols=TLSv1.2 -Djdk.tls.client.protocols=TLSv1.2 -Dlogging.config=/log4j2.xml` (set in both Dockerfiles) | portal, connector | JVM options. Because `dev.env` is an `env_file` for both containers, a `JAVA_OPTS` line there **replaces** the Dockerfile default in both. Keep the default flags when you override it. The README says the deployment's `JAVA_OPTS` "contains multiple duplicates" of the RSA variables below, passed as `-D` system properties. The connector gives system properties precedence (`SYSTEM_PROPERTIES_MODE_OVERRIDE`). |
| `MYSQL_ROOT_PASSWORD` | none | mysql, `restore.sh`, `docker-compose.local.yaml` health check | Root password of the MySQL container. **Note:** `install.sh`, `add_node.sh` and the nightly backup cron line use the hard-coded credentials `root`/`root`. See [Operator Guide](operator-guide.md#database-backups). |
| `MYSQL_COMMAND` | none | `add_node.sh` (written to `remote.env`) | Intended to carry a `mysqld` command line for group replication. The shipped `docker-compose.yaml` does not reference it. |
| `MFAAPP_PROPERTY_FILES_LOCATION` | unset | portal | If set to an existing directory, **every regular file** under it is loaded as a placeholder property source **instead of** `messages.file.location` and `email-description.location`. Startup fails with "No property files loaded from custom location" if the directory has no files. |

### Ports and volumes (`docker-compose.yaml`)

| Service | Published ports | Volumes (host → container) |
|---|---|---|
| `portal` | `8080:8080`, `8443:8443` | `./default_build/properties:/custom`, `./default_build/static:/static`, `./default_build/email-templates:/resources/email-templates`, `./default_build/ssl:/ssl`, `./default_build/portal/log4j2.xml:/log4j2.xml`, `./logs:/var/log`, `./dev.env:/application.properties` |
| `connector` | none (reached internally as `connector:8080`) | `./default_build:/custom`, `./dev.env:/properties/dev.env`, `./logs:/var/log` |
| `mysql` | none | named volume `mysql_data:/var/lib/mysql`; `./mysql:/docker-entrypoint-initdb.d` is present but **commented out** |

The portal's container health check is `curl -f http://localhost:8080`. The portal starts after the `connector` has started and once `mysql` passes `mysqladmin ping`.

## Portal settings

### Server, sessions and uploads

| Name | Default | Description |
|---|---|---|
| `server.port` | `8080` | HTTP port inside the container. |
| `server.servlet.session.timeout` | `480` | Session timeout. With no unit suffix Spring Boot reads this as seconds (8 minutes). |
| `spring.servlet.multipart.max-file-size` | `5MB` | Largest file accepted (Trusted Agent CSV upload, group-email attachment). |
| `spring.servlet.multipart.max-request-size` | `5MB` | Largest multipart request. |
| `server.ssl.*` | not set | Port `8443` is published but the repository configures no TLS. Several features read the browser's **client certificate**, so HTTPS with client-certificate negotiation must be configured with the standard Spring Boot `server.ssl.*` properties. The `./default_build/ssl` directory is mounted at `/ssl` for keystores. See [Security](security.md#tls-and-client-certificates). |
| `application.dns.name` | `http://localhost:8080` | External base URL of the portal. It prefixes every link in outgoing emails (registration, PIN reset, appointment and shipment details, Trusted Agent directory). |

### Database

| Name | Default | Description |
|---|---|---|
| `spring.datasource.url` | `jdbc:mysql://database.winllc-dev.com/RSAAPP` | JDBC URL. In the compose deployment the MySQL host name is `mysql`, for example `jdbc:mysql://mysql:3306/RSAAPP`. |
| `spring.datasource.username` | `appuser` | Database user (`mysql/01-databases.sql` creates `appuser`). |
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
| `mfa.group.email` | `postmaster@winllc-dev.com` | MFA/PMO team mailbox. It receives the lost-token-threshold report and appears in user emails as the contact address. (The README calls it "the from address", but the From header comes from `mail.default.from`.) |
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
| `cron.tokenPinCheck.minutesSinceLastLoginThreshold` | `15` |
| `cron.tokenPinCheck.minutesSinceLastEmailSentThreshold` | `5` |

The descriptions default to: "Send Expiring Token Reminder Cron", "Send Upcoming Appointments Email Cron", "Re-sync Trusted Agents with Authentication Manager Cron", "Identify Users with multiple lost Tokens", "Send Delayed Token Registration Emails Cron" and "Reset Token Pin Checker". [Architecture](architecture.md#scheduled-jobs) describes what each job does.

### Branding, text and email templates

| Name | Default | Description |
|---|---|---|
| `messages.location` | `classpath:properties/messages` | Basename of the UI message bundle (no extension). |
| `messages.file.location` | `classpath:properties/messages.properties` | The same file loaded as a placeholder source (for `branding.userId`, `link.faqs` and `login.additional.param.*`). |
| `page-descriptions.location` | `classpath:properties/page-descriptions` | Basename of the page-description bundle. |
| `email-description.location` | `classpath:email-templates/*.properties` | Email template files (placeholder source for `email-templates.xml`). |
| `custom-resource.location` | `classpath:/static/custom/` | Location served at `/static/custom/**` (`banner.png`, `header-logo.png`). |

`docker-compose.yaml` mounts the editable copies from `default_build/` at `/custom` (messages and page descriptions), `/static` (images) and `/resources/email-templates` (templates). Those paths are not on the application classpath, so to use the mounted copies, point the properties above at them with `file:` URLs. For example (paths taken from the mounts):

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

The connector resolves these through `CommandClientAppContextOverrides.xml` from the file named by **`config.properties.location`** (a JVM system property, for example `-Dconfig.properties.location=file:/properties/dev.env` inside `JAVA_OPTS`), with system properties taking precedence. The packaged `WEB-INF/config.properties` contains development values. Override all of them.

| Name | Default (packaged `config.properties`) | Description |
|---|---|---|
| `config.properties.location` | none (system property) | URL (`file:` or `classpath:`) of the properties file to load. |
| `java.naming.factory.initial` | `weblogic.jndi.WLInitialContextFactory` | JNDI factory for the RSA SDK. |
| `java.naming.provider.url` | dev host | `t3s://<AM primary FQDN>:7002` |
| `com.rsa.cmdclient.user` | sample | Command client user name (from `rsautil manage-secrets --action list`). |
| `com.rsa.cmdclient.user.password` | sample | Command client password. The README writes this key as `com.rsa.cmdclient.password`, but the packaged file uses `com.rsa.cmdclient.user.password`. |
| `ims.httpinvoker.client.provider.url` | dev host | `https://<AM primary FQDN>:7002/ims-ws/httpinvoker/CommandServer`, used for admin (write) commands. (The README spells it `ims.httpinvokder…`, but the code uses `httpinvoker`.) |
| `ims.httpinvoker.client.provider.readonly.url` | dev host | `https://<AM primary or replica FQDN>:7002/ims-ws/httpinvoker/CommandServer`, used for read-only searches. |
| `ims.soap.client.provider.url` | dev host | `https://<AM primary FQDN>:7002/ims-ws/services/CommandServer` (SOAP target in the SDK context). |
| `ims.ssl.client.trust.keystore.filename` | developer workstation path | Path to the trust store holding the AM root CA. With the compose mounts, `default_build/ssl/trust.jks` is visible to the connector as `/custom/ssl/trust.jks`. |
| `ims.ssl.client.trust.keystore.password` | none in file (listed in README) | Trust store password. |
| `com.rsa.ssl.trust.store.password` | `changeit` | Trust store password used by the SDK. (The README writes `com.rsa.ssltrust.store.password`.) |
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

The README lists the settings that must change for a new install:

=== "RSA AM (connector)"

    ```properties
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

=== "Portal"

    ```properties
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

=== "MySQL"

    ```properties
    MYSQL_ROOT_PASSWORD=********
    ```

The host names, file paths and the trust-store path above are examples. Use the values for your environment.
