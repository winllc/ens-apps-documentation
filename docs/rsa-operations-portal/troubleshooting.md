# Troubleshooting

Start with `docker ps`, `docker-compose logs -f` and `logs/rsaapp-portal.log`. Then find the symptom below.

## Startup

??? failure "`install.sh` prints `Error: docker-compose is not installed.`"
    The installer checks for the **`docker-compose`** binary. Install docker-compose v1, or provide a `docker-compose` wrapper that calls `docker compose`.

??? failure "Portal container keeps restarting; log shows a datasource or Hibernate error"
    * Check that `spring.datasource.url` points at the compose MySQL service (host `mysql`, schema `RSAAPP`). The packaged default is a development host name.
    * Check that schema `RSAAPP` and the application user exist. The MySQL init-script mount is commented out in `docker-compose.yaml`, so a fresh volume has neither. See [Installation → Initialise the database](install.md#6-initialise-the-database).
    * Init scripts in `/docker-entrypoint-initdb.d` run only when the `mysql_data` volume is **empty**. If the volume was created without them, create the DB and user by hand.

??? failure "Portal fails at startup with a mail connection error"
    `spring.mail.test-connection=true` makes Spring Boot check the SMTP server at startup. Fix `spring.mail.host` and `spring.mail.port`, or allow the Docker host to reach the relay. Set it to `false` only if you accept finding mail problems later.

??? failure "Portal fails with `No property files loaded from custom location`"
    `MFAAPP_PROPERTY_FILES_LOCATION` points at a directory that exists but has no files. Populate the directory or unset the variable.

??? failure "`Could not load country CSV` in the log; empty country list on the site forms"
    The countries are read from the absolute path `/resources/countries.csv`, which the portal image provides. Make sure no volume hides `/resources`. The compose file mounts only `/resources/email-templates`.

??? failure "Connector errors such as `Could not find Security Domain: …` or `RsaNoRealmFoundException`"
    The connector logs in with `rsa.admin.user.id` and then searches for `rsa.authmanager.realm.name` and `rsa.authmanager.securityDomain.name` (default `SystemDomain`). Correct the names, and confirm the admin user is an **internal** AM user with Super Admin rights.

## RSA Authentication Manager connectivity

??? failure "`/actuator/health` is DOWN, or every sign-in fails with *Could not login with provided credentials*"
    The portal health indicator asks the connector to open or validate its admin AM session. Check in order:

    1. **Connector running**: `docker ps`, `docker-compose logs connector`.
    2. **Name resolution**: the portal calls the fixed URL `http://connector:8080/app/connector`. The connector service must be named `connector` and share the `portal-net` network.
    3. **Network to AM**: from the host, `curl -vk https://<am>:7002/ims-ws/httpinvoker/CommandServer` must connect.
    4. **TLS trust**: `PKIX path building failed` or `unable to find valid certification path` means `trust.jks` is missing, unreadable or does not contain AM's root CA. Check `ims.ssl.client.trust.keystore.filename` (for example `/custom/ssl/trust.jks` with the compose mounts) and both trust store passwords. Rebuild it as in [Installation step 1](install.md#1-export-the-am-root-certificate-and-build-trustjks).
    5. **Command client credentials**: authentication errors to the command server mean `com.rsa.cmdclient.user` or `com.rsa.cmdclient.user.password` is wrong. Read them again with `rsautil manage-secrets --action list`. The README spells the password key `com.rsa.cmdclient.password`; the packaged file uses `…user.password`.
    6. **System properties**: the connector loads its settings from `config.properties.location`, or from `-D` options in `JAVA_OPTS`. If neither is set, it silently uses the packaged development `config.properties`.

??? failure "Operations fail and the user is suddenly sent to the login page"
    The connector runs operator actions **as the signed-in user's AM session**. When that AM session has expired, the connector raises `RsaUserSessionEndedException`, and the portal logs the user out. Sign in again. If it happens often, compare the AM session lifetime with the portal's `server.servlet.session.timeout`.

??? failure "*User does not exist in RSA Authentication Manager* for a user who exists"
    Searches are limited to the configured security domain (sub-domains included) and the realm's identity sources. Check `rsa.authmanager.securityDomain.name` and `rsa.authmanager.realm.name`. Free-text search returns at most 25 results per identity source. Refine the search, or enable `search.useridonly`.

??? failure "Token serial not found"
    Serials are normalized with `token.serial.prefix` (default `000`). Make sure the prefix matches your token serial format and is the same in the portal and the connector settings.

## Sign-in and roles

??? failure "A user signs in but sees no admin menus"
    Roles come from AM administrative roles mapped by `role.mapped.N.rsaSecurityGuid`. Check that the GUIDs are correct ([how to find them](install.md#3-find-the-administrative-role-guids)) and that the user holds the AM admin role. The user must sign in again after any change.

??? failure "Login redirects to `/login?error=locked` or `/login?error=disabled`"
    The AM account is locked out or disabled. A Trusted Agent or Help Desk user can **Unlock** it from **User Search → User Details**. Re-enable a disabled account in AM.

## Features that need a client certificate

??? failure "*Could not verify your PKI certificate. Certificate must be presented to proceed.*"
    Certificate-based PIN reset and exemption requests read the TLS client certificate. Serve the portal over HTTPS on 8443 with client-certificate negotiation enabled (`server.ssl.*`), and do not terminate TLS in front of it. See [Security → TLS and client certificates](security.md#tls-and-client-certificates).

??? failure "*We were unable to match your previous RSA Enrollment with your PKI Credentials…*"
    The certificate DN must match the DN stored at the user's last enrollment, or the DN recorded when they opened a PIN-check email link. Enrollments done over plain HTTP were stored as `NOCERT`. Use the Windows-password option or ask a Trusted Agent or Help Desk user to reset the PIN.

## Email

??? failure "No emails arrive"
    * Look for `Could not send email`, `Email not sent to:` or `Could not send … email` in the portal log.
    * Check `spring.mail.host` and `spring.mail.port` and the relay's acceptance of `mail.default.from`.
    * For job emails, check that the job's `cron.<name>.debug` is `false`. `lostTokenThresholdMonitor` ships with **debug on**.
    * Users without an email address in AM are skipped.

??? failure "Links in emails point to `http://localhost:8080`"
    Set `application.dns.name` to the external portal URL and recreate the portal (`docker-compose up -d`).

??? failure "The *Request Exemption* menu link goes nowhere"
    Set `exemptionrequest.url` (for example `/user/exemption/request`).

## Scheduled jobs

??? failure "A job never runs, or **Run** on the Manual Cron page does nothing"
    `cron.<name>.enabled` must be `true`. A disabled job is skipped even when run by hand. Last-run details are kept in memory and reset on restart.

??? failure "Job times seem off"
    The schedules are Spring 6-field cron expressions evaluated in the container's time zone. The default jobs run around 00:30–00:35.

## Backups and restore

??? failure "Nightly backup files are missing"
    * `backupdb.sh` writes to `<backup>/db/` without creating it, so create it.
    * The crontab line uses MySQL `root`/`root`. Edit it if your root password differs.
    * Run the line by hand to see errors: `crontab -l | grep backupdb`.

??? failure "`restore.sh` fails with `gzcat: command not found` or an access-denied error"
    Use `zcat`, and set `DBPASSWORD` by hand. See [Operator Guide → Restore](operator-guide.md#restore) for the known issues and a manual procedure.

## Upgrades

??? failure "Upgrade finished but the portal is not running"
    If `install.sh` found properties missing from your `dev.env`, it printed them and stopped **without starting** the stack. Add them and run `docker-compose up -d`. If `java` is missing on the host, the property comparison is skipped, so compare `dev.env` against the package copy by hand.

??? failure "Local changes to `docker-compose.yaml` disappeared"
    `install.sh` overwrites `docker-compose.yaml` on upgrade. Re-apply your changes.

## Getting more detail

* Raise the portal log level in `default_build/portal/log4j2.xml` and restart the portal.
* The connector's packaged Log4j configuration already logs at `DEBUG` to stderr: `docker-compose logs -f connector`.
* For RSA-side errors, check the AM Security Console's administration and authentication activity monitors. Operator actions run as the signed-in user, so they appear under that user's name.
