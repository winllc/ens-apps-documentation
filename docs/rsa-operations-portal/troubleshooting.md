# Troubleshooting

Start with `docker compose ps`, `docker compose logs -f` and `logs/rsaapp-portal.log` in the install folder (Helm: `kubectl -n rsa-portal get pods` and `kubectl logs`). Then find the symptom below.

## Startup

??? failure "`install.sh` stops because Docker or Docker Compose is missing"
    The installer needs Docker Engine 23 or later and the Docker Compose plugin (`docker compose version` must work). Install the plugin from your Docker packages; the old `docker-compose` v1 binary is not used.

??? failure "`install.sh` or `upgrade.sh` stops after checking the settings"
    `util/check-env.sh` found a `CHANGE_ME`, a missing setting or settings file, or a value with `$` or ` #` in it. Fix the setting it names in the file it names (`.env`, `connector.env` or `mysql.env`) and run the script again.

??? failure "`check-env.sh`: `.env holds the Connector's Authentication Manager settings in JAVA_OPTS` or `.env holds MySQL's settings (MYSQL_*)`"
    The Portal's `.env` must not hold the AM settings or the MySQL root password. If there is no `connector.env` yet, `util/split-env.sh .env` moves them to `connector.env` and `mysql.env` and keeps a copy of the original as `.env.before-split.<time>`. If `connector.env` exists already, remove them from `.env` yourself. Then run the script again.

??? failure "`check-env.sh`: `CONNECTOR_AUTH_PASSWORD differs between .env and connector.env`"
    The Connector would refuse every request from the Portal. Set the same value in both files and run the script again.

??? failure "Portal container keeps restarting; log shows a datasource or Hibernate error"
    * Release package: check that `spring.datasource.url` points at the MySQL service (host `mysql`, schema `RSAAPP`), and that `spring.datasource.password` in `.env` equals `MYSQL_PASSWORD` in `mysql.env`.
    * MySQL reads `MYSQL_DATABASE`, `MYSQL_USER` and `MYSQL_PASSWORD` only when the `mysql_data` volume is new. If you changed them later, change the user's password in MySQL as well. See [Installation → Database](install.md#6-database).
    * Helm: check `database.*`, and that the database and user exist on your server ([Prepare the database](install.md#2-prepare-the-database)). The chart connects with `sslMode=REQUIRED`.

??? failure "Portal fails at startup with a mail connection error"
    `spring.mail.test-connection=true` makes Spring Boot check the SMTP server at startup. Fix `spring.mail.host` and `spring.mail.port`, or allow the Docker host or the cluster to reach the relay. Set it to `false` only if you accept finding mail problems later.

??? failure "Portal fails with `No property files loaded from custom location`"
    `MFAAPP_PROPERTY_FILES_LOCATION` points at a directory that exists but has no files. Populate the directory or unset the variable.

??? failure "`Could not load country CSV` in the log; empty country list on the site forms"
    The countries are read from the absolute path `/resources/countries.csv`, which the portal image provides. Make sure no volume hides `/resources`. The release package mounts only `/resources/email-templates`.

??? failure "Connector errors such as `Could not find Security Domain: …` or `RsaNoRealmFoundException`"
    The connector logs in with the service account (`RSA_ADMIN_USER_ID`) and then searches for `rsa.authmanager.realm.name` and `rsa.authmanager.securityDomain.name` (default `SystemDomain`). Correct the names, and confirm the service account is an **internal** AM user whose role can view realms and security domains in that scope ([service account role](rsa-integration.md#least-privilege-am-roles)).

??? failure "Connector log: `rsa.readonly.user.id is not set`"
    Searches and lookups run as the service account. Create a view-only AM user and set `RSA_READONLY_USER_ID` and `RSA_READONLY_USER_PASSWORD` in `connector.env` (Helm: `authManager.readOnlyUser`).

??? failure "Portal fails with `connector.tls.client-certificate and connector.tls.client-key go together` or `Could not load the connector TLS settings`"
    Set both `CONNECTOR_TLS_CLIENTCERTIFICATE` and `CONNECTOR_TLS_CLIENTKEY`, or neither; and check that `CONNECTOR_TLS_TRUSTSTORE` names a readable PEM file, or a PKCS12/JKS store with the right password.

## RSA Authentication Manager connectivity

??? failure "Readiness is DOWN, or every sign-in fails with *Could not login with provided credentials*"
    The Portal's `connector` health indicator calls the Connector's `/app/health`, which reports whether the Connector has an AM admin session. Check in order:

    1. **Connector running**: `docker compose ps` and `docker compose logs connector` (Helm: the Connector Deployment's pod and logs). `docker compose exec connector curl -s http://localhost:8080/app/health` says `UP` or `DOWN`; the reason is in the Connector's log.
    2. **Portal to Connector**: the Portal calls `https://connector:8443/app/connector` (`CONNECTOR_URL`). In the Portal's log, a certificate error (`PKIX path building failed`, `No subject alternative names matching`) means the Connector's certificate does not chain to `CONNECTOR_TLS_TRUSTSTORE` or lacks the host name: run `util/connector-tls.sh` (release package) or check `connector.tls.existingSecret` (Helm). A 401 means `CONNECTOR_AUTH_PASSWORD` differs between `.env` and `connector.env`. With mutual TLS, a handshake failure means the Portal's client certificate is missing or not from `CONNECTOR_TLS_CLIENT_CA`. With Helm and a NetworkPolicy, only the Portal's pods may reach the Connector.
    3. **Network to AM**: from the host or a pod, `curl -vk https://<am>:7002/ims-ws/httpinvoker/CommandServer` must connect.
    4. **TLS trust to AM**: `PKIX path building failed` or `unable to find valid certification path` in the Connector's log means `trust.jks` is missing, unreadable or does not contain AM's root CA. Check `ims.ssl.client.trust.keystore.filename` (`/custom/ssl/trust.jks` in both install methods) and `RSA_TRUSTSTORE_PASSWORD`. Rebuild it as in [Installation](install.md#export-the-am-root-certificate-and-build-trustjks).
    5. **Command client credentials**: authentication errors to the command server mean `RSA_CMDCLIENT_USER` or `RSA_CMDCLIENT_PASSWORD` is wrong. Read them again with `rsautil manage-secrets --action list`.
    6. **System properties**: the connector loads its other settings from `config.properties.location`, or from `-D` options in `JAVA_OPTS` in `connector.env`. If neither is set, it uses the packaged `config.properties`, whose host names are development ones.

??? failure "Operations fail and the user is suddenly sent to the login page"
    The connector runs operator actions **as the signed-in user's AM session**. When that AM session has expired, the connector raises `RsaUserSessionEndedException`, and the portal logs the user out. Sign in again. If it happens often, compare the AM session lifetime with the portal's `ENS_SESSION_IDLE_TIMEOUT` and `ENS_SESSION_ABSOLUTE_TIMEOUT`.

??? failure "An operator's role change, emergency code or *Clear cached Windows password* fails with a permission error"
    These run in the operator's own AM session. The operator's AM administrative role needs the matching permission: see [Least-privilege AM roles](rsa-integration.md#least-privilege-am-roles).

??? failure "*User does not exist in RSA Authentication Manager* for a user who exists"
    Searches are limited to the configured security domain (sub-domains included) and the realm's identity sources. Check `rsa.authmanager.securityDomain.name` and `rsa.authmanager.realm.name`. Free-text search returns at most 25 results per identity source. Refine the search, or enable `search.useridonly`.

??? failure "Token serial not found"
    Serials are normalized with `token.serial.prefix` (default `000`). Make sure the prefix matches your token serial format and is the same in the portal and the connector settings.

## Sign-in and roles

??? failure "A user signs in but sees no admin menus"
    Roles come from AM administrative roles mapped by `role.mapped.N.rsaSecurityGuid`. Check that the GUIDs are correct ([how to find them](install.md#find-the-administrative-role-guids)) and that the user holds the AM admin role. The user must sign in again after any change.

??? failure "*Sign-in failed. Check your user ID and passcode*, and the user is sure the passcode is right"
    The page gives one message whatever the cause. The audit log has the cause: open **Audit Log**, filter on `auth.login` with outcome `failure` and the user ID, and read the `reason`. A locked AM account is recorded as an alert; a Trusted Agent or Help Desk user can **Unlock** it from **User Search → User Details**. Re-enable a disabled account in AM. After three failures for one user ID, the next attempts must also wait (30 seconds, doubling up to 15 minutes).

??? failure "*Too many attempts. Wait ... and try again* (HTTP 429)"
    A rate limit or the backoff refused the request. The first refusal is audited as `auth.rate-limited`, with `details.flow`, `details.limit` and `details.retryAfterSeconds`. If many users are refused at once, check that the Portal sees each client's own address: behind a TLS-terminating proxy it needs `SERVER_FORWARD_HEADERS_STRATEGY=native` and the right `SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES` (see [Security → Behind a proxy](security.md#behind-a-proxy)), and with TLS passthrough all clients share one address. Raise `rsa.rate-limit.*.per-ip` if your users really share an address.

??? failure "Every user's audit events show the same client address"
    The Portal sees the proxy's address. Set `SERVER_FORWARD_HEADERS_STRATEGY=native` (not `framework`) and `ENS_COOKIE_SECURE=true`, make the proxy replace `X-Forwarded-For`, and check that `SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES` covers the proxy, as CIDR ranges with a prefix length (`10.0.0.5/32`, not `10.0.0.5`).

??? failure "Self-service says *Your request has been received*, but no e-mail arrives"
    Appointment booking and exemption requests answer the same whatever the outcome. The audit trail says what happened: `appointment.request-refused` (`unknown_user`, `user_has_token`, `too_many_open`, `authmanager_error`) or `auth.self-service-failed` with `details.flow` and the reason (for example `unknown_user` or `no_email`).

## Help-desk requests

??? failure "*None of the verification methods set for this Portal can verify the user for this change*"
    For a PIN reset, an emergency access code or a lost token, a tokencode does not count, and the e-mail code is offered only while the Portal can send mail and the user has an address. Add `in-person` or `email-code` to `rsa.helpdesk.verification.methods`, or verify the user in person.

??? failure "The request asks to verify the user again"
    More than `rsa.helpdesk.verification.valid-for` (15 minutes) passed between the verification and running the request or sending it for approval.

??? failure "A request is waiting for approval and nobody can approve it"
    The approver must hold one of `rsa.helpdesk.dual-control.approver-roles` (Trusted Agent or Help Desk by default), and may not be the operator who asked or the user. The license must include the change's feature (`help-desk`, or `trusted-agents` for lost and deprovisioned tokens). A request not approved and run within `rsa.helpdesk.dual-control.request-ttl` (24 hours) expires.

??? failure "A request shows *failed* although nobody saw an error"
    It was still running one hour after its change began, for example because the Portal stopped during the change. Look up `am.change-outcome` for the request in the audit trail to see whether the change was made in AM.

??? failure "The Portal does not start: `Help-desk settings: ...` or `Rate-limit settings: ...`"
    A setting makes no sense; the message names it. For example `tokencode` alone in `rsa.helpdesk.verification.methods`, `entra-verified-id` (not available), or a limit not written as `attempts/duration`.

## Features that need a client certificate

??? failure "*Could not verify your PKI certificate. Certificate must be presented to proceed.*"
    Certificate-based PIN reset and exemption requests read the TLS client certificate. Serve the portal over HTTPS on 8443 with client-certificate negotiation enabled (`server.ssl.*`), and do not terminate TLS in front of it. See [Security → TLS and client certificates](security.md#tls-and-client-certificates).

??? failure "*We were unable to match your previous RSA Enrollment with your PKI Credentials…*"
    The certificate DN must match the DN stored at the user's last enrollment, or an existing certificate-to-user mapping (`CertTokenMapping`). A PIN-check email link records none. Enrollments done over plain HTTP were stored as `NOCERT`. Use the Windows-password option or ask a Trusted Agent or Help Desk user to reset the PIN.

## Email

??? failure "No emails arrive"
    * Look for `Could not send email`, `Email not sent to:` or `Could not send … email` in the portal log.
    * Check `spring.mail.host` and `spring.mail.port` and the relay's acceptance of `mail.default.from`.
    * For job emails, check that the job's `cron.<name>.debug` is `false`. `lostTokenThresholdMonitor` ships with **debug on**.
    * Users without an email address in AM are skipped.

??? failure "Links in emails point to `http://localhost:8080`"
    Set `application.dns.name` to the external portal URL and recreate the portal (`docker compose up -d`). With Helm, set `publicUrl` and run `helm upgrade`.

??? failure "The *Request Exemption* menu link is missing"
    It is shown while the license includes the `self-service` feature. Its target is `exemptionrequest.url` (default `/user/exemption/request`).

## Scheduled jobs

??? failure "A job never runs, or **Run** on the Manual Cron page does nothing"
    `cron.<name>.enabled` must be `true`. A disabled job is skipped even when run by hand. Last-run details are kept in memory and reset on restart.

??? failure "Job times seem off"
    The schedules are Spring 6-field cron expressions evaluated in `cron.timezone`, by default the container's time zone (UTC). Set `cron.timezone` (Helm: `cronTimezone`) to the zone the schedules should mean. The default jobs run around 00:30–00:35.

??? failure "A job is skipped"
    The jobs need the `scheduled-jobs` license feature and are skipped while the license is read-only. A job still running when its next time comes is skipped too, and so is an occurrence another Portal node has claimed in `CronLock`.

## Backups and restore

??? failure "Nightly backup files are missing"
    * The cron user must be allowed to use Docker (`util/backupdb.sh` runs `mysqldump` in the `mysql` container). It needs no `sudo`.
    * With `-g` or `-a`, the gpg public key must be in that user's keyring, or the age recipient must be valid.
    * Run the line by hand to see errors: `crontab -l | grep backupdb`.
    * Helm: `kubectl -n rsa-portal get cronjob,jobs` and the backup Job's logs. The CronJob connects as `database.username` with `backup.sslMode`.

??? failure "A restore fails with an access-denied error"
    Run `mysql` inside the container with the container's own root password, as shown in [Operator Guide → Restore](operator-guide.md#restore).

## Upgrades

??? failure "Upgrade finished but the portal is not running"
    If `util/check-env.sh` found a problem in the settings, `upgrade.sh` stopped **before restarting**. Fix the settings it names, then run `./upgrade.sh` again. `upgrade.sh` also lists the settings that are new in each template; add the ones you need to the file it names. If the Portal did not become healthy in time, check `docker compose logs portal connector`.

??? failure "PIN-reset or lost-token e-mails stopped after the upgrade"
    The help-desk notice replaces them; their templates are no longer used. Customize the notice with the `helpdesk.notice.*` keys in `messages.properties`.

??? failure "A customized exemption-request message is no longer shown"
    `user.exemption.request.confirmationSent` and `user.exemption.request.noEmail` were replaced by `user.exemption.request.received`. Move your text to the new key.

??? failure "Local changes to `docker-compose.yml` disappeared"
    `upgrade.sh` replaces `docker-compose.yml` with the new release's copy. Keep your settings in `.env`, `connector.env` and `mysql.env`, not in `docker-compose.yml`.

## Getting more detail

* Raise the portal log level in `default_build/portal/log4j2.xml` and restart the portal.
* The connector's packaged Log4j configuration already logs at `DEBUG` to stderr: `docker compose logs -f connector` (Helm: `kubectl logs` on the Connector Deployment).
* For RSA-side errors, check the AM Security Console's administration and authentication activity monitors. Operator actions run as the signed-in user, so they appear under that user's name.
