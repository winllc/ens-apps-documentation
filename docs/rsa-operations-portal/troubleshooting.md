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

??? failure "`check-env.sh`: `.env holds the Connector's Authentication Manager accounts (RSA_*)`"
    `.env` takes no `RSA_*` variables: they are the Connector's. Move the AM accounts to `connector.env`. A Portal setting written in its environment form, such as `RSA_HELPDESK_CODE_KEY` or `RSA_RATELIMIT_ENABLED`, goes in `.env` under its property name instead (`rsa.helpdesk.code-key`, `rsa.rate-limit.enabled`).

??? failure "`check-env.sh`: `connector.env requires a client certificate (CONNECTOR_TLS_CLIENT_AUTH=required), but .env has no CONNECTOR_TLS_CLIENTCERTIFICATE and CONNECTOR_TLS_CLIENTKEY`"
    The Connector would refuse the Portal. Run `./util/connector-tls.sh --mutual`, which adds the Portal's certificate and key to `.env`, then `./util/file-owners.sh` and the script again. Keep the two sides of mutual TLS together: see [Mutual TLS](rsa-integration.md#mutual-tls).

??? warning "`check-env.sh` or `upgrade.sh`: `mutual TLS between the Portal and the Connector is off`"
    The installation is from before mutual TLS was the default. Turn it on: `./util/connector-tls.sh --mutual`, `./util/file-owners.sh`, `docker compose up -d`.

??? warning "`check-env.sh`: `the Portal's key for mutual TLS ... is in default_build/ssl, which the Connector can read`, or `the Portal's /portal-tls/... does not exist`"
    The Connector mounts all of `default_build`, so the Portal's key belongs in `portal-tls/`. `util/connector-tls.sh` moves `portal-client.key` and `.crt` there and fixes `.env`; move a key of your own yourself and name it `/portal-tls/...` in `CONNECTOR_TLS_CLIENTKEY`. A missing file: run `util/connector-tls.sh`, or the Portal does not start.

??? warning "`check-env.sh` or the Helm notes: `the Connector reaches Authentication Manager at <host>, an IP address` (or `a name without a domain`)"
    The Connector checks that AM's certificate names the host in its URLs, and the certificate usually names only its fully qualified name. Put that name in the `ims.httpinvoker.*` URLs (Helm: `authManager.host`, `.readOnlyHost`), have the certificate reissued with the name you use, or set `RSA_TLS_VERIFY_HOSTNAME=false` in `connector.env` (Helm: `connector.extraEnv`). Otherwise the Connector cannot reach AM.

??? failure "The Portal does not start: `rsa.helpdesk.code-key must be at least 32 characters` or `rsa.helpdesk.code-key-file ... cannot be read`"
    The help desk's code key is too short, or its file is missing, unreadable or holds less than 32 characters. Use `openssl rand -hex 32` for a new key; changing it only makes codes sent in the last few minutes fail. See [Help-desk requests → The e-mailed code's key](help-desk.md#the-e-mailed-codes-key).

??? failure "The Connector does not start: `CONNECTOR_SERIAL_FILTER takes only off` or `RSA_TLS_VERIFY_HOSTNAME is true or false`"
    `CONNECTOR_SERIAL_FILTER` accepts only `off`, exactly; leave it unset to keep the filter. `RSA_TLS_VERIFY_HOSTNAME` accepts only `true` or `false`.

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
    2. **Portal to Connector**: the Portal calls `https://connector:8443/app/connector` (`CONNECTOR_URL`). In the Portal's log, a certificate error (`PKIX path building failed`, `No subject alternative names matching`) means the Connector's certificate does not chain to `CONNECTOR_TLS_TRUSTSTORE` or lacks the host name: run `util/connector-tls.sh` (release package) or check `connector.tls.existingSecret` (Helm). A 401 means `CONNECTOR_AUTH_PASSWORD` differs between `.env` and `connector.env`. With mutual TLS, a handshake failure means the Portal's client certificate is missing or not from `CONNECTOR_TLS_CLIENT_CA`: check `CONNECTOR_TLS_CLIENTCERTIFICATE` and `CONNECTOR_TLS_CLIENTKEY` in `.env` and that the Portal can read the key (`./util/file-owners.sh`). With Helm and a NetworkPolicy, only the Portal's pods may reach the Connector.
    3. **Network to AM**: from the host or a pod, `curl -vk https://<am>:7002/ims-ws/httpinvoker/CommandServer` must connect.
    4. **TLS trust to AM**: `PKIX path building failed` or `unable to find valid certification path` in the Connector's log means `trust.jks` is missing, unreadable or does not contain AM's root CA. Check `ims.ssl.client.trust.keystore.filename` (`/custom/ssl/trust.jks` in both install methods) and `RSA_TRUSTSTORE_PASSWORD`. Rebuild it as in [Installation](install.md#export-the-am-root-certificate-and-build-trustjks). `TLS to Authentication Manager at <host>:<port> failed: No subject alternative ...` means AM's certificate does not name the host in the URLs: use a name it has (usually its fully qualified name), or, where impossible, `RSA_TLS_VERIFY_HOSTNAME=false` ([AM's host name](rsa-integration.md#transport)).
    5. **Command client credentials**: authentication errors to the command server mean `RSA_CMDCLIENT_USER` or `RSA_CMDCLIENT_PASSWORD` is wrong. Read them again with `rsautil manage-secrets --action list`.
    6. **System properties**: the connector loads its other settings from `config.properties.location`, or from `-D` options in `JAVA_OPTS` in `connector.env`. If neither is set, it uses the packaged `config.properties`, whose host names are development ones.

??? failure "A command fails and the Connector logs `Refused a reply from Authentication Manager`"
    The Connector's deserialization filter refused AM's reply. The warning names the class or the limit:

    * an RSA class (`com.rsa...`): add it to `CONNECTOR_SERIAL_FILTER_ALLOW` in `connector.env` (Helm: `connector.extraEnv`) as the warning shows, and restart the Connector;
    * a limit (a very large search): raise it in `CONNECTOR_SERIAL_FILTER_LIMITS` as the warning shows, and restart the Connector;
    * any other class: AM is not known to send it, and the reply may be an attack. Check that the AM URLs reach the real AM, and ask RSA support before allowing it.

    Until then, in an emergency, `CONNECTOR_SERIAL_FILTER=off` reads the replies without the filter, with a warning at every start; remove it once the class is allowed. See [What the Connector accepts](rsa-integration.md#what-the-connector-accepts).

??? failure "Operations fail and the user is suddenly sent to the login page"
    The connector runs operator actions **as the signed-in user's AM session**. When that AM session has expired, the connector raises `RsaUserSessionEndedException`, and the portal logs the user out. Sign in again. If it happens often, compare the AM session lifetime with the portal's `ENS_SESSION_IDLE_TIMEOUT` and `ENS_SESSION_ABSOLUTE_TIMEOUT`. From 0.0.3 this happens only when AM answers that the session no longer exists; before, an unreachable AM or a locked service account signed operators out too, and now shows an error instead.

??? failure "Connector log: `Could not open the admin session on Auth Manager; trying again in ... s at the earliest`"
    From 0.0.3. A connect to AM failed: AM is unreachable, or the service account's (`RSA_ADMIN_USER_ID`) or the read-only account's password is wrong, or the account is locked or expired; the cause follows in the line. The Connector waits 5 seconds before the next attempt, doubling up to 30 while it keeps failing, and until then answers every request and its health check with that failure, so readiness is `DOWN`. Fix the cause; the next successful connect ends the wait. A wrong password is no longer tried on every request, so it does not lock the account by itself.

??? failure "A user enrolling a token or testing it gets an error instead of *wrong code* while AM is down"
    Intended from 0.0.3: the Connector reports a check that AM could not answer as a failure. Before, the user was told the code was wrong.

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
    The page gives one message whatever the cause. The audit log has the cause: open **Audit Log**, filter on `auth.login` with outcome `failure` and the user ID, and read the `reason`. A locked AM account is recorded as an alert; a Trusted Agent or Help Desk user can **Unlock** it from **User Search → User Details**. Re-enable a disabled account in AM. After three failures for one user ID, the next attempts must also wait (30 seconds, doubling up to 15 minutes). From 0.0.3 the reason `service_error` means the Connector or AM could not answer: check [RSA Authentication Manager connectivity](#rsa-authentication-manager-connectivity). Such a failure adds no wait for the user ID; before 0.0.3 it was recorded as `bad_credentials` and counted.

??? failure "*Too many attempts. Wait ... and try again* (HTTP 429)"
    A rate limit or the backoff refused the request. The first refusal is audited as `auth.rate-limited`, with `details.flow`, `details.limit` and `details.retryAfterSeconds`. If many users are refused at once, check that the Portal sees each client's own address: behind a TLS-terminating proxy it needs `SERVER_FORWARD_HEADERS_STRATEGY=native` and the right `SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES` (see [Security → Behind a proxy](security.md#behind-a-proxy)), and with TLS passthrough all clients share one address. Raise `rsa.rate-limit.*.per-ip` if your users really share an address.

??? failure "Every user's audit events show the same client address"
    The Portal sees the proxy's address. Set `SERVER_FORWARD_HEADERS_STRATEGY=native` (not `framework`) and `ENS_COOKIE_SECURE=true`, make the proxy replace `X-Forwarded-For`, and check that `SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES` covers the proxy, as CIDR ranges with a prefix length (`10.0.0.5/32`, not `10.0.0.5`).

??? failure "Self-service says *Your request has been received*, but no e-mail arrives"
    Appointment booking and exemption requests answer the same whatever the outcome. The audit trail says what happened: `appointment.request-refused` (`unknown_user`, `user_has_token`, `too_many_open`, `authmanager_error`, and from 0.0.3 `slot_not_available`, when someone else booked the time at the same moment, or `error`, with the cause in the Portal log) or `auth.self-service-failed` with `details.flow` and the reason (for example `unknown_user` or `no_email`).

??? failure "An integration gets `429` (`rate_limited`) from `/api/v1` although its token's limit is not reached"
    From 0.0.3, calls without a valid token are counted per client address against `rsa.rate-limit.sign-in.per-ip` (120 in 10 minutes). The integration, or something else behind the same address, keeps calling with a missing, expired or revoked token: the audit trail has `access.denied` with `api_token_expired`, `api_token_revoked` or `api_token_invalid` and that address, then `auth.rate-limited` with `details.flow` `api`. Give the integration a new token on *Admin → Integrations*; calls with a valid token are not counted and work at once.

??? warning "After the upgrade to 0.0.2, an operator signing in with the passcode alone has no PMO, trusted agent or help-desk menu"
    With single sign-on configured, a role that `ENS_SSO_ROLES_*` maps a group to now comes only through the identity provider. The operator signs in with the provider's button on the sign-in page, then the passcode. To keep the old behaviour, set `ENS_SSO_REQUIRE_FOR_ADMIN_ROLES=false` (Helm `sso.requireForAdminRoles: false`); the Portal then warns at every start. See [Single sign-on](single-sign-on.md#how-a-sign-in-goes).

??? warning "Signing out ends at the identity provider with an error about the redirect URI (from 0.0.2)"
    `ENS_SSO_OIDC_END_SESSION=true`, but the provider does not allow `<APPLICATION_DNS_NAME>/` as a post-logout redirect URI, or it requires `id_token_hint`, which the Portal cannot send (Okta). Allow the URI at the provider, or set `ENS_SSO_OIDC_END_SESSION=false`. The Portal session and the Authentication Manager session have already ended at that point. See [Signing out](single-sign-on.md#signing-out).

??? warning "With SAML, signing out ends only the Portal session although `ENS_SSO_SAML_SINGLE_LOGOUT=true` (from 0.0.2)"
    The Portal says at every start which part is missing: its signing key (`SPRING_SECURITY_SAML2_RELYINGPARTY_REGISTRATION_<ID>_SIGNING_CREDENTIALS_0_*`, Helm `sso.saml[].signingSecret`), or the asserting party's SingleLogoutService by HTTP-Redirect (in its metadata, or `..._ASSERTINGPARTY_SINGLELOGOUT_URL` with `_BINDING=REDIRECT`, Helm `sso.saml[].singleLogoutUrl`). A POST-only SingleLogoutService is not used. Once both are there, refresh the relying party at the asserting party so it knows `<APPLICATION_DNS_NAME>/logout/saml2/slo`. See [SAML single logout](single-sign-on.md#saml-single-logout).

## Help-desk requests

??? note "**Assign Token** or **Remove From Group** opens a help-desk request instead of making the change"
    Intended from 0.0.3 when the user is privileged (`rsa.helpdesk.dual-control.privileged-roles`) or is the operator's own account: the change needs a ticket, a verified user and a second operator's approval. See [Help-desk requests → Tokens and the RSA-enabled group](help-desk.md#tokens-and-the-rsa-enabled-group). A Help Desk user without the Trusted Agent role cannot open a `token-assign` request.

??? failure "*None of the verification methods set for this Portal can verify the user for this change*"
    For a PIN reset, an emergency access code or a lost token, a tokencode does not count, and the e-mail code is offered only while the Portal can send mail and the user has an address. Add `in-person` or `email-code` to `rsa.helpdesk.verification.methods`, or verify the user in person.

??? failure "The request asks to verify the user again"
    More than `rsa.helpdesk.verification.valid-for` (15 minutes) passed between the verification and running the request or sending it for approval. Once sent for approval, a request is no longer bound by those 15 minutes, only by `rsa.helpdesk.dual-control.request-ttl`.

??? failure "The e-mailed code is refused although the user read it correctly"
    Check the Portal's log for `rsa.helpdesk.code-key is not set`. Without a key the Portal makes one for each run, so a code sent before a restart, or by another Portal replica, cannot be checked. Set `rsa.helpdesk.code-key` in `.env`, the same on every Portal (`install.sh` and `upgrade.sh` add one; Helm generates `helpDesk.emailCodeKey`), and send a new code. A code sent just before an upgrade to the release with the key, or before the key changed, is refused once too.

??? failure "A request is waiting for approval and nobody can approve it"
    The approver must hold one of `rsa.helpdesk.dual-control.approver-roles` (Trusted Agent or Help Desk by default), and may not be the operator who asked or the user. The license must include the change's feature (`help-desk`, or `trusted-agents` for lost and deprovisioned tokens). A request not approved and run within `rsa.helpdesk.dual-control.request-ttl` (24 hours) of being sent for approval expires.

??? failure "A request shows *failed* although nobody saw an error"
    It was still running one hour after its change began, for example because the Portal stopped during the change. Look up `am.change-outcome` for the request in the audit trail to see whether the change was made in AM.

??? failure "The Portal does not start: `Help-desk settings: ...` or `Rate-limit settings: ...`"
    A setting makes no sense; the message names it. For example `tokencode` alone, or no method, in `rsa.helpdesk.verification.methods` while verification is required; `entra-verified-id`, which this release always refuses; or a limit not written as `attempts/duration`.

??? failure "A change fails with `AuditOrderException`: `Cannot commit the audit event ... before the action it records`"
    The Authentication Manager change was asked for inside a database transaction that had already recorded an audit event, so its `am.change-attempt` could not be committed first, and the change was refused (fail closed). No flow in the Portal does this; it points at a code change or extension. Make the change before recording in that transaction, or outside it.

## Features that need a client certificate

??? failure "*Could not verify your PKI certificate. Certificate must be presented to proceed.*"
    Certificate-based PIN reset and exemption requests read the TLS client certificate. Serve the portal over HTTPS on 8443 with client-certificate negotiation enabled (`server.ssl.*`), and do not terminate TLS in front of it. See [Security → TLS and client certificates](security.md#tls-and-client-certificates).

??? failure "*We were unable to match your previous RSA Enrollment with your PKI Credentials…*"
    The certificate DN must match the DN stored at the user's last enrollment, or an existing certificate-to-user mapping (`CertTokenMapping`). A PIN-check email link records none. Enrollments done over plain HTTP were stored as `NOCERT`. Use the Windows-password option or ask a Trusted Agent or Help Desk user to reset the PIN.

## Appointments, exemptions, shipments and sites

??? failure "The booking form refuses the time: *Choose one of the times offered...*, *That time has passed...* or *That time has just been booked...*"
    From 0.0.3 the server takes only a time the schedule page offers: in the future (by the server's clock and time zone), on a quarter hour within the Trusted Agent's working hours, and not already booked. A hand-made request, or a page left open while someone else took the time, gets these messages. If the hours offered are wrong, the agent corrects them on **My TA Profile**.

??? failure "The Trusted Agent's details or booking page shows an error for an agent who exists"
    The agent is deactivated. From 0.0.3 the public pages neither show nor book a deactivated agent; Trusted Agents and PMO users still see the details. PMO reactivates the agent on **Edit TA**, or the nightly `resyncta` job does once the agent holds the Trusted Agent role in AM again.

??? failure "PMO cannot approve an exemption request: *The account owner has not confirmed this request yet*"
    From 0.0.3 a request can be approved or denied only after its account owner has opened the confirmation link e-mailed to the address AM has for the user ID. The *All* list marks such requests **Not confirmed**. If the e-mail never arrived or its link has expired, the user submits the request again, which sends a new link; an unconfirmed request is deleted by data retention after `ENS_RETENTION_EXEMPTION_REQUESTS_DAYS`.

??? failure "*Someone else changed this at the same time, so nothing was saved* (HTTP 409) on a shipment"
    From 0.0.3 a shipment changed by someone else since the page was opened is not overwritten. Nothing was saved and nobody was e-mailed. Open the shipment again and repeat the change if it still applies.

??? failure "*Enter the first and last serial numbers of the range as digits, the first no higher than the last.*"
    From 0.0.3 a site's serial range is checked before it is stored. A range stored by an earlier release with other characters is skipped with `Skipping malformed token serial range of site ...` in the Portal log, so the site's pages and report work without it: delete it on the site's page and add it again.

## Email

??? failure "No emails arrive"
    * Look for `Could not send email`, `Email not sent to:` or `Could not send … email` in the portal log.
    * Check `spring.mail.host` and `spring.mail.port` and the relay's acceptance of `mail.default.from`.
    * For job emails, check that the job's `cron.<name>.debug` is `false`. `lostTokenThresholdMonitor` ships with **debug on** (from 0.0.2 with debug off, and the startup log warns while it is on). From 0.0.2, check also that `mfa.group.email` is set: without it the report goes to nobody, and the startup log says so.
    * Users without an email address in AM are skipped.
    * From 0.0.3 a job that had e-mail to send and sent none ends as `Failed: ...` on **Admin → Manual Run Cron Jobs** and `job.failed` in the audit trail. A relay that accepts the connection and then stalls fails each send after `spring.mail.properties.mail.smtp.timeout` (30 seconds).
    * A group e-mail that answers *Could not send email.* had no recipients, could not look its recipients up, or was held back by the license; nothing was sent or recorded.

??? failure "Links in emails point to `http://localhost:8080`"
    Set `application.dns.name` to the external portal URL and recreate the portal (`docker compose up -d`). With Helm, set `publicUrl` and run `helm upgrade`.

??? failure "The *Request Exemption* menu link is missing"
    It is shown while the license includes the `self-service` feature. Its target is `exemptionrequest.url` (default `/user/exemption/request`).

## Scheduled jobs

??? failure "A job shows `Failed: ...` on the Manual Cron page, or the audit trail has `job.failed`"
    From 0.0.3 a job fails when it could not do its work: the Connector or AM could not be reached (`Could not run: ...`), or it had items to do and none was done (the message is its summary, for example `Sent 0 emails, 5 failed`). Check the Connector's health and the SMTP relay, then **Run** the job again: `expiring` and `appointmentupcoming` catch up what the missed night left and send nothing twice, and the other jobs record a reminder only when it was sent.

??? failure "`sendDelayedTokenRegistrationEmails` fails with `Stopped after N emails: could not ask Authentication Manager about ... users`"
    A look-up of a batch of users failed, and the job stops rather than wait out the Connector's timeout for each batch. The reminders already sent are recorded; the rest go out on the next run. If the Connector is older than the Portal, the call does not exist there: upgrade both to the same release.

??? failure "A user no longer gets registration reminders"
    From 0.0.3 the reminders stop `cron.sendDelayedTokenRegistrationEmails.maxDaysOfReminders` (30) days after the date the token may be registered; `0` removes the limit. A user AM no longer has is skipped, with `No registration reminder for <user>: Authentication Manager does not have the user` in the Portal log, and counted as failed in the job's message.

??? failure "A job never runs, or **Run** on the Manual Cron page does nothing"
    `cron.<name>.enabled` must be `true`. A disabled job is skipped even when run by hand. Last-run details are kept in the memory of each Portal replica and reset on restart.

??? failure "Job times seem off"
    The schedules are Spring 6-field cron expressions evaluated in `cron.timezone`, by default the container's time zone (UTC). Set `cron.timezone` (Helm: `cronTimezone`) to the zone the schedules should mean. The default jobs run around 00:30–00:35.

??? failure "A job is skipped"
    The jobs need the `scheduled-jobs` license feature and are skipped while the license is read-only. A job still running when its next time comes is skipped too, and so is an occurrence another Portal node has claimed in `CronLock`.

## License

??? failure "*That license was not installed. This license starts on ...; the license installed now is still in force.*"
    From 0.0.3 a license that is not yet valid, or has already expired, is refused while the installed license is in full effect, so that it cannot replace a working one. Upload next term's license on or after its start date. The refusal is audited as `license.rejected`.

??? failure "*The license was not installed: the audit log cannot be written.*"
    From 0.0.3 the upload checks the audit log before it changes anything. Check the database, and the Portal log for the cause, then upload again. *License installed, but the audit log could not record it* means the license is in place and only its audit event is missing.

??? failure "The license message ends with *This server's clock is behind a time this installation has already seen...*"
    From 0.0.3 the Portal records the latest time it has seen (at most hourly) and checks the license no earlier than a day before it, so setting the clock back does not bring an expired license back. Correct the server's clock (NTP). If the clock ran ahead for a while and was corrected since, the message stays until the clock passes the recorded time; contact ENS Solutions if a valid license is refused because of it.

??? failure "With `ENS_CONSENT_BANNER_ENABLED=true` and no valid license, the browser loops between the banner and the License screen"
    Fixed in 0.0.3: the banner can be acknowledged on an unlicensed Portal. On an earlier release, give the license as `ENS_LICENSE` (Helm `license.file`), or turn the banner off until the license is installed.

??? failure "A license in `ENS_LICENSE` was not installed at start: `ENS_LICENSE could not be written to ...`"
    The database could not be read or written at that moment. From 0.0.3 the Portal tries again on a license check at least 30 seconds later, and the log line says so; before, it needed a restart.

## Audit

??? failure "**Verify integrity** reports `Broken at seq N: missing (seq ... to ...; retention has pruned nothing)` after the upgrade to 0.0.3"
    From 0.0.3 the oldest event kept must be `seq` 1, or the event right after the last one retention deleted. The trail starts later than that: events were deleted from the start of `AuditEvent` by other means than the Portal's retention (by hand, or by a partial restore). Earlier releases accepted any oldest event as the anchor and reported such a trail as intact. The events are gone from the database; look for them in your SIEM or in a backup, and record the finding.

??? note "Failed `auth.login` events with `details.method` `api-token`"
    From 0.0.3 a wrong or revoked token presented to the audit pull API (`/api/audit/events`) is recorded, with the source address and never the token. A SIEM still configured with a revoked token shows up like this: give it a new token on the Audit Log page.

??? note "The syslog collector received a batch of audit events twice"
    Syslog over `tcp` and `tls` has no acknowledgement. From 0.0.3 the Portal checks the connection before each batch, opens a new one after 30 seconds unused, and sends the last batch again when a connection turns out broken, so a collector restart can repeat up to one batch: de-duplicate on `id`. A batch the collector does not take within 30 seconds fails and is retried. Where no event may be missing, use Splunk HEC or the pull API.

## Backups and restore

??? failure "Nightly backup files are missing"
    * The cron user must be allowed to use Docker (`util/backupdb.sh` runs `mysqldump` in the `mysql` container). It needs no `sudo`.
    * With `-g` or `-a`, the gpg public key must be in that user's keyring, or the age recipient must be valid.
    * Run the line by hand to see errors: `crontab -l | grep backupdb`.
    * Helm: `kubectl -n rsa-portal get cronjob,jobs` and the backup Job's logs. The CronJob connects as `database.username` with `backup.sslMode`.

??? failure "A restore fails with an access-denied error"
    Run `mysql` inside the container with the container's own root password, as shown in [Operator Guide → Restore](operator-guide.md#restore).

## Upgrades

??? failure "`upgrade.sh` stops: `MySQL is not running and has no container to start ... nothing was changed`"
    From 0.0.3 `upgrade.sh` needs MySQL running for the backup and its checks, and starts a stopped container itself. After `./util/compose.sh down` there is no container: in the install folder run `./util/compose.sh up -d mysql`, wait until it is healthy, and run `upgrade.sh` again.

??? failure "`upgrade.sh` stops: `MySQL did not become healthy`, `the database backup failed` or `the database backup is incomplete`, each with `nothing was changed`"
    The installation is as it was. Check the MySQL container's log (`docker logs mysql`), the free space under `backups/` in the install folder, and that `MYSQL_ROOT_PASSWORD` in `mysql.env` is still the root password. Then run `upgrade.sh` again.

??? failure "After the upgrade the site report fails, or `sendDelayedTokenRegistrationEmails` fails every night"
    The Portal is 0.0.3 and the Connector is older: these two call Connector methods that 0.0.3 added. Run both on the same release (release package: `./util/compose.sh ps` shows the images; Helm: remove an `image.tag` or `connector.image.tag` override).

??? failure "Upgrade finished but the portal is not running"
    If `util/check-env.sh` found a problem in the settings, `upgrade.sh` stopped **before restarting**. Fix the settings it names, then run `./upgrade.sh` again. `upgrade.sh` also lists the settings that are new in each template; add the ones you need to the file it names. If the Portal did not become healthy in time, check `docker compose logs portal connector`.

??? failure "`helm upgrade` stops: `connector.tls.mutual is required for this upgrade`"
    The chart found no record of whether mutual TLS between the Portal and the Connector is on: no `rsa-operations-portal/connector-mutual-tls` annotation on its TLS Secret or ConfigMap, and no Connector Deployment to read, for example after a name override changed or with `helm template --is-upgrade`. Set `connector.tls.mutual=true` (the default for new installations) or `false`.

??? failure "After the upgrade the Connector no longer reaches AM"
    Look in the Connector's log. `TLS to Authentication Manager at <host>:<port> failed` means AM's certificate does not name the host in the URLs, which the Connector now checks; see *Readiness is DOWN* below. `Refused a reply from Authentication Manager` means the new deserialization filter refused a reply; see *A command fails and the Connector logs ...* below.

??? failure "PIN-reset or lost-token e-mails stopped after the upgrade"
    The help-desk notice replaces them; their templates are no longer used. Customize the notice with the `helpdesk.notice.*` keys in `messages.properties`.

??? failure "A customized exemption-request message is no longer shown"
    `user.exemption.request.confirmationSent` and `user.exemption.request.noEmail` were replaced by `user.exemption.request.received`. Move your text to the new key.

??? failure "Local changes to `docker-compose.yml` disappeared"
    `upgrade.sh` replaces `docker-compose.yml` with the new release's copy. Keep your settings in `.env`, `connector.env` and `mysql.env`, not in `docker-compose.yml`.

## Getting more detail

* Raise the portal log level in `default_build/portal/log4j2.xml` and restart the portal.
* Line breaks inside a log message appear as `\r` and `\n` in both components' text logs; that is intended, so that request data cannot forge a log line.
* The connector's packaged Log4j configuration already logs at `DEBUG` to stderr: `docker compose logs -f connector` (Helm: `kubectl logs` on the Connector Deployment).
* For RSA-side errors, check the AM Security Console's administration and authentication activity monitors. Operator actions run as the signed-in user, so they appear under that user's name.
