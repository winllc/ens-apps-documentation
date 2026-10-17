# Troubleshooting

Start with `docker compose ps`, `docker compose logs -f` and `logs/rsaapp-portal.log` in the install folder (Helm: `kubectl -n rsa-portal get pods` and `kubectl logs`). Then find the symptom below.

## Startup

??? failure "`install.sh` stops because Docker or Docker Compose is missing"
    The installer needs Docker Engine 23 or later and the Docker Compose plugin (`docker compose version` must work). Install the plugin from your Docker packages; the old `docker-compose` v1 binary is not used.

??? failure "`install.sh` or `upgrade.sh` stops after checking `.env`"
    `util/check-env.sh` found a `CHANGE_ME`, a missing setting, or a value with `$` or ` #` in it. Fix the setting it names in `.env` and run the script again.

??? failure "Portal container keeps restarting; log shows a datasource or Hibernate error"
    * Release package: check that `spring.datasource.url` points at the MySQL service (host `mysql`, schema `RSAAPP`), and that `spring.datasource.password` equals `MYSQL_PASSWORD`.
    * MySQL reads `MYSQL_DATABASE`, `MYSQL_USER` and `MYSQL_PASSWORD` only when the `mysql_data` volume is new. If you changed them later, change the user's password in MySQL as well. See [Installation → Database](install.md#6-database).
    * Helm: check `database.*`, and that the database and user exist on your server ([Prepare the database](install.md#2-prepare-the-database)). The chart connects with `sslMode=REQUIRED`.

??? failure "Portal fails at startup with a mail connection error"
    `spring.mail.test-connection=true` makes Spring Boot check the SMTP server at startup. Fix `spring.mail.host` and `spring.mail.port`, or allow the Docker host or the cluster to reach the relay. Set it to `false` only if you accept finding mail problems later.

??? failure "Portal fails with `No property files loaded from custom location`"
    `MFAAPP_PROPERTY_FILES_LOCATION` points at a directory that exists but has no files. Populate the directory or unset the variable.

??? failure "`Could not load country CSV` in the log; empty country list on the site forms"
    The countries are read from the absolute path `/resources/countries.csv`, which the portal image provides. Make sure no volume hides `/resources`. The release package mounts only `/resources/email-templates`.

??? failure "Connector errors such as `Could not find Security Domain: …` or `RsaNoRealmFoundException`"
    The connector logs in with `rsa.admin.user.id` and then searches for `rsa.authmanager.realm.name` and `rsa.authmanager.securityDomain.name` (default `SystemDomain`). Correct the names, and confirm the admin user is an **internal** AM user with Super Admin rights.

## RSA Authentication Manager connectivity

??? failure "`/actuator/health` is DOWN, or every sign-in fails with *Could not login with provided credentials*"
    The portal health indicator asks the connector to open or validate its admin AM session. Check in order:

    1. **Connector running**: `docker compose ps` and `docker compose logs connector` (Helm: the Connector Deployment's pod and logs).
    2. **Name resolution**: in the release package the portal calls `http://connector:8080/app/connector`, so the service must stay named `connector` on the `portal-net` network. The Helm chart points the Portal at its Connector Service for you.
    3. **Network to AM**: from the host or a pod, `curl -vk https://<am>:7002/ims-ws/httpinvoker/CommandServer` must connect.
    4. **TLS trust**: `PKIX path building failed` or `unable to find valid certification path` means `trust.jks` is missing, unreadable or does not contain AM's root CA. Check `ims.ssl.client.trust.keystore.filename` (`/custom/ssl/trust.jks` in both install methods) and both trust store passwords. Rebuild it as in [Installation](install.md#export-the-am-root-certificate-and-build-trustjks).
    5. **Command client credentials**: authentication errors to the command server mean `com.rsa.cmdclient.user` or `com.rsa.cmdclient.user.password` is wrong. Read them again with `rsautil manage-secrets --action list`. The package's `GUIDE.md` spells the password key `com.rsa.cmdclient.password`; the packaged file uses `…user.password`.
    6. **System properties**: the connector loads its settings from `config.properties.location`, or from `-D` options in `JAVA_OPTS`. If neither is set, it silently uses the packaged development `config.properties`.

??? failure "Operations fail and the user is suddenly sent to the login page"
    The connector runs operator actions **as the signed-in user's AM session**. When that AM session has expired, the connector raises `RsaUserSessionEndedException`, and the portal logs the user out. Sign in again. If it happens often, compare the AM session lifetime with the portal's `server.servlet.session.timeout`.

??? failure "*User does not exist in RSA Authentication Manager* for a user who exists"
    Searches are limited to the configured security domain (sub-domains included) and the realm's identity sources. Check `rsa.authmanager.securityDomain.name` and `rsa.authmanager.realm.name`. Free-text search returns at most 25 results per identity source. Refine the search, or enable `search.useridonly`.

??? failure "Token serial not found"
    Serials are normalized with `token.serial.prefix` (default `000`). Make sure the prefix matches your token serial format and is the same in the portal and the connector settings.

## Sign-in and roles

??? failure "A user signs in but sees no admin menus"
    Roles come from AM administrative roles mapped by `role.mapped.N.rsaSecurityGuid`. Check that the GUIDs are correct ([how to find them](install.md#find-the-administrative-role-guids)) and that the user holds the AM admin role. The user must sign in again after any change.

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
    Set `application.dns.name` to the external portal URL and recreate the portal (`docker compose up -d`). With Helm, set `publicUrl` and run `helm upgrade`.

??? failure "The *Request Exemption* menu link goes nowhere"
    Set `exemptionrequest.url` (for example `/user/exemption/request`).

## Scheduled jobs

??? failure "A job never runs, or **Run** on the Manual Cron page does nothing"
    `cron.<name>.enabled` must be `true`. A disabled job is skipped even when run by hand. Last-run details are kept in memory and reset on restart.

??? failure "Job times seem off"
    The schedules are Spring 6-field cron expressions evaluated in the container's time zone. The default jobs run around 00:30–00:35.

## Backups and restore

??? failure "Nightly backup files are missing"
    * The crontab line must run `util/backupdb.sh` from the install folder (`cd /opt/rsa-operations-portal && util/backupdb.sh -d <backup dir>`), because it uses the `mysql` container.
    * The clean-up step uses `sudo find`, so the cron user needs password-less `sudo` for it.
    * Run the line by hand to see errors: `crontab -l | grep backupdb`.

??? failure "A restore fails with an access-denied error"
    Run `mysql` inside the container with the container's own root password, as shown in [Operator Guide → Restore](operator-guide.md#restore).

## Upgrades

??? failure "Upgrade finished but the portal is not running"
    If `util/check-env.sh` found a problem in `.env`, `upgrade.sh` stopped **before restarting**. Fix the settings it names, then run `./upgrade.sh` again. `upgrade.sh` also lists the settings that are new in `env.template`; add the ones you need. If the Portal did not become healthy in time, check `docker compose logs portal connector`.

??? failure "Local changes to `docker-compose.yml` disappeared"
    `upgrade.sh` replaces `docker-compose.yml` with the new release's copy. Keep your settings in `.env`, not in `docker-compose.yml`.

## Getting more detail

* Raise the portal log level in `default_build/portal/log4j2.xml` and restart the portal.
* The connector's packaged Log4j configuration already logs at `DEBUG` to stderr: `docker compose logs -f connector` (Helm: `kubectl logs` on the Connector Deployment).
* For RSA-side errors, check the AM Security Console's administration and authentication activity monitors. Operator actions run as the signed-in user, so they appear under that user's name.
