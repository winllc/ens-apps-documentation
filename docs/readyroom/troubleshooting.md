# Troubleshooting

Problems are grouped by where they show up. Most have a matching line in the server log or in a
workstation log: `%LOCALAPPDATA%\InOutWorker\logs\inoutworker-yyyyMMdd.log` in the user's profile
for login, lock, unlock and remote disconnects and reconnects, and `%ProgramData%\InOutWorker\logs\` for the
SYSTEM logout task. For background, see [Operations](operator-guide.md) and
[Windows client](windows-client.md).

## Server will not start

| Error | Cause and fix |
|---|---|
| `Could not resolve placeholder 'application.banner-text'` | `application.banner-text` has no default. Set it. |
| `application.windows-auth.enabled is true but application.windows-auth.service-principal is not set` | Set `service-principal`, or turn Windows sign-in off. |
| `... keytab-location is not set` / `Windows sign-in keytab ... does not exist or cannot be read` | Mount the keytab and make it readable by the container user. The image runs as user `185`, and `/etc/in-n-out` is group `0`, mode `0750`. |
| `server.ssl.enabled is true but no key store is configured. ...` | TLS is on, but no keystore is set; none is packaged. Release package: put it in `tls/keystore.p12` and add `docker-compose.tls.yml` to `COMPOSE_FILE`. Helm: set `tls.keystore.secretName`. Otherwise set `SERVER_SSL_KEYSTORE` and `SERVER_SSL_KEYSTOREPASSWORD`. |
| `server.ssl.client-auth is want but no trust store is configured, ...` | TLS is on and client certificates are requested, but no truststore is set; ReadyRoom does not fall back to Java's default CAs. Supply one holding only the CAs that issue your users' certificates (`tls/truststore.p12`, `tls.truststore.secretName`, or `SERVER_SSL_TRUSTSTORE` and its password), or set `client-auth` to `none` to turn certificate sign-in off. |
| `install.sh` or `upgrade.sh`: `SSL_ENABLED=true needs your own keystore and truststore: ...` | `SSL_ENABLED=true`, but `docker-compose.tls.yml` is not in `COMPOSE_FILE`. Add it, and put `tls/keystore.p12` and `tls/truststore.p12` in place. The scripts also stop if either file is missing. |
| Helm: `tls.truststore.secretName is required when tls.enabled with clientAuth ...` | The chart does not render with `tls.enabled` and no truststore. Set `tls.truststore.secretName`, or `tls.clientAuth: none`. |
| Keystore or truststore errors | Release package: check that `tls/keystore.p12` and `tls/truststore.p12` exist, are readable by group 0, and match `SERVER_SSL_KEYSTOREPASSWORD` and `SERVER_SSL_TRUSTSTOREPASSWORD`, and that `docker-compose.tls.yml` is in `COMPOSE_FILE`. Helm: check the `tls.keystore` and `tls.truststore` Secrets and their password keys. |
| Database connection refused | Release package: check `docker compose ps` shows `postgres` healthy. Helm: check `database.*` and that PostgreSQL accepts connections from the pod. |
| Flyway fails at startup (`Migration ... failed`, `Validate failed`) | A migration could not be applied, or the schema doesn't match what ReadyRoom expects. Nothing of the failed migration was applied: each runs in one transaction. Fix the cause the log names (often the database account's rights: it, or `SPRING_FLYWAY_USER`, must own the schema) and start again. Never switch `ddl-auto` to `update`. |
| A setting fails validation (the message names it, for example `APPLICATION_USERBASEDN`, `APPLICATION_BASE_URL`, `ENS_SESSION_IDLE_TIMEOUT`, `ENS_AUDIT_SYSLOG_PROTOCOL`) | Fix the value the message names. Durations are Spring durations of at least one minute (`30m`, `12h`). |
| Mail is configured but `APPLICATION_NOTIFICATIONSENDEREMAIL` is missing or not an address | With `SPRING_MAIL_HOST` set, the From address is required. Set it, or remove the mail host. |
| Header mode refuses to start (proxy secret or CA missing) | `ENS_X509_SOURCE=header` needs `ENS_X509_PROXY_SECRET_FILE` or `ENS_X509_PROXY_SECRET` (32 characters or more) and `ENS_X509_TRUSTED_CA_FILE` (or the TLS trust store). The Helm chart refuses to render likewise. |
| `application.retention.events-days (APPLICATION_RETENTION_EVENTSDAYS) must be 0 (keep forever) or at least 31 ...` | Retention below 31 days is refused, because the home page, My Reports and the average login time read the last 30 days. |
| `application.events.request-statuses (APPLICATION_EVENTS_REQUESTSTATUSES) cannot list STANDARD ...`, or a bad `APPLICATION_PRIVACY_COWORKERVISIBILITY`, `APPLICATION_RETENTION_CRON` or `APPLICATION_EVENTS_MAXRANGEDAYS` | Fix the value the message names: visibility is `full`, `status` or `none`; the cron has six fields; the range is 1 to 3660 days. |
| Helm does not render: `database.embedded.enabled needs database.password or database.existingSecret: ...` (0.0.11) | The chart no longer generates the embedded database's password. Set `database.password` or `database.existingSecret`. For a release whose earlier chart generated it, `helm upgrade` against the cluster keeps it; where the chart is rendered without the cluster (`helm template`, Argo CD), set the password the release already uses. See [Installation](install.md#evaluation-with-the-embedded-database). |
| `application.end-of-day.at (APPLICATION_ENDOFDAY_AT) must be empty or a time of day, HH:mm`, or `application.events.max-view-days (APPLICATION_EVENTS_MAXVIEWDAYS) must be at least 1` / `at most 3660` (0.0.11) | Fix the value the message names. |
| The log has a `WARNING` from migration `V33`: `pg_trgm could not be created (...); the delegate lookups read the whole table as before` (0.0.11) | Not an error: ReadyRoom starts. The database account may not create the `pg_trgm` extension, so two indexes were not added. Add them later with the statements in [Installation → Prepare PostgreSQL](install.md#prepare-postgresql). |
| Startup stops during a migration with a socket or read timeout on a very large database (0.0.11) | One statement of the migration ran longer than `DB_SOCKET_TIMEOUT` (600 s). Nothing of that migration was applied. Raise `DB_SOCKET_TIMEOUT` (Helm `database.socketTimeout`) for the upgrade, or set it to `0`, and start again. |
| Port in use (release package) | Another process holds the host port `READYROOM_PORT` (8181). Choose another host port in `.env`; ReadyRoom inside the container stays on 8181. |
| Startup stops naming `ENS_SSO_SAML_IDP_METADATA` or `ENS_SSO_OIDC_ISSUER_URI` | The value is `http://`. From 0.0.3 single sign-on reads its trust material over `https://` only (or, for SAML, from a file). Serve it over https, or download the SAML metadata to a file and use `file:/path`. See [Single sign-on](single-sign-on.md#settings) |
| Startup stops with SAML on and an `http://` `APPLICATION_BASE_URL` (from 0.0.3) | SAML needs HTTPS as the browser sees it. Set `APPLICATION_BASE_URL` (Helm `publicUrl`) to the `https://` address users reach, behind `SSL_ENABLED=true` or a TLS-terminating proxy |
| Startup stops naming demo mode and `application.windows-auth.enabled` (from 0.0.3) | Demo mode and Windows sign-in cannot both be on. Turn one of them off |

## Sign-in and access

| Symptom | Cause and fix |
|---|---|
| The browser goes to the login page although a certificate is installed | The certificate is not issued by a CA in the server truststore, or the browser did not offer it. Check the truststore, then restart the browser so it asks again. |
| A browser with a trusted certificate is sent to the login form, and the audit log has a failed `auth.login` with the reason *unknown user* | The certificate subject DN is not a directory entry, so the certificate does not sign in. Make the subject equal the LDAP DN, set `ENS_X509_USER_FILTER`, or set `lookup-on-dn-attribute` and `user-dn-attribute`. |
| Certificate sign-in takes several seconds, and the log has WARNs about revocation status unknown | Revocation is on by default (from 0.0.3) and soft-fail: ReadyRoom tries the OCSP responder and the CRL distribution points, and a firewall that silently drops the traffic makes each attempt wait up to 5 s before the certificate is allowed. Provide CRLs with `ENS_X509_CRL_DIR` (Helm `x509.revocation.crlConfigMap`), a local responder with `ENS_X509_OCSP_RESPONDER_URL`, open the egress, or set `ENS_X509_REVOCATION=off` |
| A certificate is refused with `certificate revoked` or `revocation unknown` | The certificate is revoked, or its status couldn't be determined with `ENS_X509_REVOCATION_FAIL=closed`: the OCSP responder or CRL distribution point didn't answer, or the issuer's certificate isn't known. Check the log (`Refusing ... revocation status unknown`), and on an air-gapped network use `ENS_X509_CRL_DIR` or `ENS_X509_OCSP_RESPONDER_URL`. |
| A certificate is refused with `issuer not allowed` or `no single directory user matches` | Its issuer isn't in `ENS_X509_ALLOWED_ISSUERS`, or `ENS_X509_USER_FILTER` matched no entry or more than one. |
| Header mode: the log shows `Ignoring the client certificate in <header> from <address>: <reason>` | The request didn't come from an address in `ENS_X509_TRUSTED_PROXIES`, or carried no or the wrong `X-ENS-Proxy-Secret`. With ingress-nginx, the secret is added with a `configuration-snippet` annotation, which the controller disables by default since 1.9. |
| Users must click **I agree** before signing in | The consent banner is on (`ENS_CONSENT_BANNER_ENABLED`). Expected. |
| Users are signed out sooner than before | The session lifetimes `ENS_SESSION_*` (idle `30m`, absolute `12h`). The audit log's `auth.session-expired` gives the reason. Reason `account`: the account was disabled, locked out, expired or removed in the directory, or the person's ReadyRoom role changed (`details.account` says which); see [Security → Sessions](security.md#notice-and-consent-banner-and-sessions). |
| Certificate or single sign-on refused with `account disabled in the directory`, `account locked out in the directory`, `account expired in the directory` or `account not in the directory, or no longer matching its filters` | The person's directory account is not usable. Enable or unlock it, or extend its expiry, in the directory. |
| Single sign-on answers *ReadyRoom could not reach the directory to sign you in* (HTTP `503`), the log saying `Single sign-on (oidc) not decided: the directory did not answer; answering 503` (or `saml2`) | The directory did not answer while the identity provider's user was looked up, even after `application.ldap.sign-in-retries` more tries. The person was not refused; they sign in again in a few seconds. If it keeps happening, check the directory's health, `application.ldap.pool-max-size` and the connect timeout. |
| Login form says the credentials are wrong | The user is searched under `user-base-dn` by `uid`, `cn` or `mail`. Check the base DN, and that exactly the expected entry matches. |
| Login form says *Too many failed sign-ins. Wait a minute, then try again.* (the address ends in `/login?throttled`); the audit log has a failed `auth.login` with the reason `too many failed sign-ins` (0.0.11) | More than `application.login.username-failures-per-minute` (5) failed password sign-ins for that username, or `application.login.address-failures-per-minute` (30) from that client address, within a minute. It clears when that minute is over; the right password is refused until then. `details.limitedBy` in the audit event says which limit. If everyone is refused at once, ReadyRoom sees every browser at a reverse proxy's address: set `SERVER_FORWARD_HEADERS_STRATEGY=framework`. Repeated events for one username from many addresses are someone guessing that person's password. |
| Someone named in `application.super-user-dns` signs in as `USER` | Before 0.0.11, an entry written with spaces after its commas (`CN=Jane Doe, OU=Users, ...`) or a trailing space never matched; upgrade, or write the DN without them. Otherwise the entry is not the person's directory DN: compare it with the DN on their details page. |
| No **Log out** in the user menu | Expected for certificate sessions. The certificate would sign you straight back in. |
| A user cannot open a group | Only admins can open any group. Everyone else can open groups they are a member of in the directory (the group's member attribute, `uniqueMember` by default) or manage. Memberships are cached for 300 s. |
| With Active Directory, nobody has groups: **Groups** is empty, group reports have no members, group managers get no absence alerts | ReadyRoom reads `groupOfUniqueNames` entries with `uniqueMember` unless told otherwise. From 0.0.11 set `APPLICATION_LDAP_GROUPOBJECTCLASS=group` and `APPLICATION_LDAP_GROUPMEMBERATTRIBUTE=member` (Helm `ldap.groupObjectClass`, `ldap.groupMemberAttribute`). |
| Clicking a person or a group whose name holds a comma, `/`, `#` or `?` gives *Not found* or `400` | Fixed in 0.0.11: the pages now pass the DN as a query parameter (`/app/user/details?dn=...`). Upgrade. |
| A manager's new report, or the members of a group they now own, show without details for a moment | From 0.0.11, what the directory says about whom a viewer is responsible for is kept for a minute. It shows within a minute. |
| A manager sees only the status of some people, without times or notes | Expected. Details are shown only for the people they are responsible for: members of groups they manage, their direct reports, and the people they are a delegate of. The `MANAGER` role alone does not grant them. See [Security → Authorization](security.md#authorization). |
| Coworkers show only `IN` or `OUT`, or no status at all | `APPLICATION_PRIVACY_COWORKERVISIBILITY` is `status` or `none`. Expected. People see themselves, their reports and the people they are a delegate of in full whatever it says. |
| A user can no longer set Work From Home, Temporary Duty (TDY), Training, Out of Office or Scheduled Leave on their calendar; it says *(needs approval)* | Expected since the approvals release: these statuses are requested and approved. Remove a status from `APPLICATION_EVENTS_REQUESTSTATUSES` to let people set it themselves again. |
| A request is not in anyone's **Waiting for your approval** | Only the person's directory manager, their delegates and administrators decide requests; group managers don't. Check the person's **Manager** field and **Delegates** card, or have an administrator decide it. Nobody decides their own request. |
| `POST /api/event/update` returns `403` | The caller tried to record a status only managers record on their own calendar, or a status on someone else's calendar without being their manager, delegate or an administrator. |
| `POST /api/event/update` returns `400` with `range_too_long`, `invalid_range`, `invalid_status`, `invalid_time` or `invalid_dn` | The request body is wrong; the `message` says how. Ranges are limited to `APPLICATION_EVENTS_MAXRANGEDAYS` (366) days. |
| `POST /api/event/update` returns `400` `past_day`, or the calendar refuses *normal* on an earlier day | Expected: people cannot clear their own past days. Their manager, a delegate or an administrator corrects them on the person's calendar. |
| A Late Arrival or Early Leave someone set does not show on their calendar, and their manager got an *Approval needed* email | Expected when the days picked include one before today: it became a request, and reaches the calendar once approved. "Today" is the person's own date (their time zone, from their day start), not the **System Date**. |
| Someone with a Late Arrival or Early Leave shows as not accounted for, or *No record* on the org chart | Expected: these statuses are the person's own word and excuse nothing. Only a check-in, a status that excuses an absence or one a manager recorded accounts for the day. A late arrival still moves the time they are expected by. |
| A user can't change their delegates | Expected: only administrators set them, on the person's details page. |
| A group manager no longer sees the **Group Managers** card | Expected: only administrators set a group's alternate managers. |
| `ADMIN` is missing from the role list on a person's page, or saving it answers `400` `admin_not_grantable` | Expected: `ADMIN` is never given from the UI. Name administrators in `application.super-user-dns`. |
| Changing an administrator's role says *... is the only administrator left: name another first.* | The last administrator cannot lose `ADMIN`. Name another in `application.super-user-dns` first. **Administration → Privacy** lists the administrators. |
| The log shows `N people have delegates (alternate managers) chosen before administrators managed them` | Review them under **Administration → Delegates**: **Keep**, **Change** or **Remove**. |
| An admin's role change does not show | Roles are loaded at sign-in. A browser session whose role changed is ended within `ENS_SESSION_ACCOUNT_CHECK_INTERVAL` (`5m`), and the new role applies from the next sign-in. |
| Metrics is missing from the sidebar | It needs the `ADMIN` or `MANAGER` role. Check `super-user-dns`, or set the role on the user's details page. |
| Settings is missing from the sidebar for a manager | Expected: Settings is for administrators only. |
| *My Reports*, Home's team section or a group report says *The directory could not be read* (a group report's CSV or Excel download answers `503`) | The directory did not answer. Check ReadyRoom's directory connection (`spring.ldap.urls`, the bind account, timeouts) and try again; nothing is wrong with the reporting-line attributes. |
| A page says *The directory did not answer* / *The directory could not be read* (HTTP `503`), or an API call or download answers `503` with `Retry-After` and `{"error":"directory_unavailable"}`; the log says `<method> <path>: the directory did not answer (...); answering 503` (`WARN`) | The page needed people from the directory (a person's page, a group's people, the org chart, approvals, delegates, group managers, scheduled report recipients, an erasure, an export), and the directory did not answer even after `application.ldap.read-retries` more tries. Nothing was changed and nobody was left out; try again in a few seconds. If it keeps happening, check the directory's health, `application.ldap.pool-max-size` and the connect timeout. Earlier releases showed such pages with people missing, or refused a delegate as *Not in the directory*. |

## Check-ins not arriving

Work through these in order:

1. **Is the task running?** `Get-ScheduledTask -TaskPath "\InOutWork\" | Get-ScheduledTaskInfo`.
   Check *LastRunTime* and *LastTaskResult*, which is the [exit code](windows-client.md#exit-codes).
2. **Read the workstation log** for the action.
3. **Try it by hand:**
   `& 'C:\Program Files\InOutWorker\Update-WorkStatus.ps1' -Action lock`.
4. **Check the server.** At `debug`, each event is logged as `Recorded <ACTION> for <dn> at <time>`.

| Workstation log | Cause and fix |
|---|---|
| `Configuration error: BaseUrl is not configured.` or `... is not a valid URL.` (exit 1) | Set `BaseUrl` in `inoutworker.config.json`, or re-run the installer with `-BaseUrl`. From 0.0.11 the client has no built-in address (it used to fall back to a lab address). |
| The installer stops with `No BaseUrl is configured. Pass -BaseUrl, ...` | From 0.0.11 the installer changes nothing when the resulting configuration would have no `BaseUrl`. Pass `-BaseUrl`. |
| `'login' posts to an authenticated endpoint and no client certificate is available.` (exit 2) | No valid certificate with a private key in `CurrentUser\My`. |
| `POST to ... failed (HTTP 401)` (exit 3) | The server did not accept the certificate or the ticket. |
| `POST to ... failed (HTTP 500) [internal_error] ...` (exit 3) | From 0.0.11: the server could not store the event (a database error, or a statement or lock timeout). The event is lost; the server log has the cause, with a stack trace (`Request POST .../api/check/... failed`). Earlier servers answered `200`, and the workstation log said `Server accepted` for an event that was not recorded. |
| `Not sending 'lock': a later logon, lock or unlock of this user has started.` | Not a fault: a newer event of the same user overtook this one, which would have undone it. |
| `POST to ... failed (HTTP 503) [directory_unavailable] ...` (exit 3) | The directory did not answer while the server checked who signed in, even after `application.ldap.sign-in-retries` more tries: a time-out, or no free pooled connection during a burst of check-ins. The person was not refused. The agent sent the event again after the server's `Retry-After`, up to `Retries` times (3), and gave up; the next logon, lock or unlock goes through once the directory answers. Check the server log for `the directory did not answer`, the directory's health, `application.ldap.pool-max-size` and the connect timeout. |
| A TLS trust error (exit 3) | The workstation does not trust the server's CA, or the host name does not match the server certificate. |
| `Logout has no sessionId` | The login post failed, and no unlock was recorded as a check-in since, so the logout cannot be attributed. The server refuses it with `401`. Fix the login failure. |
| `... failed (HTTP 401) [unknown_session] ...` on a logout | The `sessionId` is unknown, already used or older than 7 days. Nothing is recorded. A workstation kept logged on for longer gets a fresh `sessionId` with each day's first unlock, which the server records as the check-in; earlier clients and servers did not, so upgrade the server and re-run the installer on such workstations. |
| `... failed (HTTP 429) [rate_limited] ...` on a logout | See the server-side row below. |

| Symptom on the server | Cause and fix |
|---|---|
| Status stays `AWAY` all evening | No logoff event arrived: the PC was shut down, or the session ended without event 4647 (the Logout task). A remote session left disconnected is reported as a lock (the Disconnect task) and shows `AWAY` like a locked screen. The auto check-out job checks the person out once the lock is older than `check-out-after-minutes`; a lock left in the evening (after about 20:00 with the default 240 minutes) is checked out after midnight. Earlier releases left such a lock `AWAY` for good. |
| People show `OUT` minutes after locking | `check-out-after-minutes` is too low. The default is `240`; check `APPLICATION_CHECKOUTAFTERMINUTES` (Helm: `checkOutAfterMinutes`). |
| Someone who never locked or logged off shows `OUT` the next morning, with no check-out time; the audit log has `record.auto-checked-out` with the reason `end of day` | Expected from 0.0.11: a session left open is closed at the end of the person's day (`APPLICATION_ENDOFDAY_AT`, or the end of their day). Reports show *No logoff recorded* for that day. `APPLICATION_ENDOFDAY_CHECKOUT=false` keeps such sessions `IN` until the next event, as before. |
| The check-out time on Home or in a user table is hours earlier than before the upgrade | Expected from 0.0.11: it is the time of the screen lock that the automatic check-out followed, as the reports always showed, not the time the check-out was written (`check-out-after-minutes` later). |
| Someone shows *No record* on a day whose only record is a check-out in the small hours | Expected from 0.0.11: that record closes the day before's session (an evening lock checked out after midnight, or a locked PC restarted overnight). Without a check-in the day has no record. |
| Someone who works on two PCs stays `AWAY`, or their day ends at a morning lock on the other PC | Until both the server and the workstations run 0.0.11, a lock on one PC can be hidden by, or mixed up with, events on the other. Re-run the installer on the workstations so the client sends its workstation's name. |
| After a restart of a single replica, people locked before it are checked out late | By the time the service was down, at most `APPLICATION_HA_MAXCHECKOUTPOSTPONEMENT` (24 h). Up to 0.0.10 it was the whole `check-out-after-minutes` again. |
| Nobody is checked out automatically any more | See [Operations → Runbook](operator-guide.md#runbook): `ens_readyroom_checkout_last_success_seconds`, and `job.failed` events for `MarkInactiveCron`. |
| Logouts missing, and the audit log has failed `auth.login` events with the reason *unknown session* | Logouts without a usable `sessionId` are refused and not stored. Look at the workstation logs for why the login failed or the session file is missing. |
| Logouts get `429` (`rate_limited`), and the log shows `... sent more than N logoffs with an unusable session id this minute; refusing them with 429` | More than `application.logoff.refused-per-minute` (30) logouts with a stale or missing session id came from one address within a minute. They would have been refused anyway: a logout with a usable `sessionId` is never limited. Fix the workstations sending them. |
| `IN` although the user only unlocked | Expected: an unlock is stored as `CHECK_IN` when the user has no check-in yet that day. |

## Windows sign-in

The server logs why each refused Windows sign-in failed. The ticket itself is never logged.

| Server log | Cause |
|---|---|
| `offered NTLM, which is not supported` | Windows had no Kerberos ticket for the URL: `BaseUrl` uses an IP address or a name other than the registered SPN, or the PC cannot reach a domain controller. |
| `Kerberos validation not successful (Checksum failed)` | The keytab does not match the account's current key, so re-export it. Or the principal differs from the one clients request. |
| `Clock skew too great` | The server and the domain controller clocks differ by more than 5 minutes. |
| `no single directory user has sAMAccountName=...` | No directory user, or more than one, has that account name. Check `account-attribute`, `user-base-dn` and `user-ldap-filter`. |
| `realm not allowed` | The user is from another domain. Add it to `allowed-realms` if that is intended. |
| `not a user account` | A service or host principal, or a computer account (`PC01$`), was presented. |

On the client, `The server redirected instead of accepting Windows sign-in` means
`application.windows-auth.enabled` is off on that server. To inspect the keytab:
`klist -kte /etc/in-n-out/http.keytab`.

## Absence alerts

| Symptom | Cause and fix |
|---|---|
| No alerts at all | Look for `End SendAbsentNotificationsCron` in the log. Nobody is alerted about on a day that is not one of their working days (Monday to Friday unless set otherwise), before their start date, or on a holiday. With the built-in defaults, the first run is 30 minutes after startup. |
| A late person was not alerted | They have no expected time (no preferred login time, no check-in history in 30 days and no late arrival), they have an excusable status or one a manager recorded (*Absent*, *Absent Unexcused*, *Unscheduled Leave*), they were already alerted today, or they have no `user_records` row yet (the directory refresh creates one). Nobody is alerted about someone whose directory entry is gone or whose account is disabled or expired (`No absence alert about ...`), or who has no directory manager, no delegate and no group manager (`no directory manager, delegate or group manager for ...` at `WARN`). |
| Alert created but no email | `Failed to send "Accountability Notification for ..." to ...` (SMTP), `Manager does not have an email address`, or `Invalid email address` in the log. From 0.0.11 the alert e-mail job, which runs every 30 seconds, sends the email again, for up to a day, while the alert is unanswered (`Sent N absence alert e-mail(s) that had not gone out`). `The mail server is not answering: no more absence alert e-mails this run` (`WARN`) means it could not be reached, timed out or refused the sign-in: the alerts are recorded and their emails go out once it answers. |
| Alerts stop for everyone after one person | Before 0.0.11, a delegate or a group's alternate manager stored as something that is not a DN ended every run at the same person. Upgrade. From 0.0.11 that recipient is left out (`Not alerting ... not a distinguished name ReadyRoom can use`), and a person whose check fails is logged (`Absence check for <dn> failed; going on with the others`) while the run goes on. |
| An old unanswered alert is no longer in the bell or on Home | From 0.0.11 they list the newest 50 alerts that may still be answered (`application.events.alert-answer-days`, 14). An older one still opens from its email link. |
| The log shows `No absence alert about <dn> this run, tried again next run` (`WARN`) | The directory could not be read to find the person's manager, or one of the people the alert goes to (a delegate, a group manager, the person's groups, the manager's address). Nothing was saved, so the alert is neither sent to the others alone nor counted as sent; the next run tries again. |
| Alerts stay in the bell after **Mark All Read** | Expected: marking read clears the red dot only. An alert stays until someone answers it. |
| The wrong people are alerted | Recipients are the directory manager (`user-ldap-manager-id-attribute` / `manager-ldap-id-attribute`), the person's delegates, which administrators set, and the managers of every group the person is a member of in the directory (each group's `owner` and its alternate managers). A large group with an owner, such as an all-staff group, makes that owner a recipient for everyone in it: remove the owner or the membership in the directory, or the alternate managers on the group's page. |
| Someone with a pending leave request was alerted | Expected: a request excuses nothing until it is approved. |
| Request emails don't arrive | No mail server is configured, or sending failed (`Failed to send "<subject>" to ...` in the log). Request emails are sent once and not retried. |
| The email link points to `localhost` | Set `application.application-base-url`. |
| Alerts arrive an hour off, or on the wrong day | Each person is checked in their own time zone: their profile's, else their site's (`APPLICATION_REPORTS_SITETIMEZONES`), else `application.timezone`. Check the zone and day start under *Time zone* on their profile, and **Settings → Days and Time Zones**. |
| Someone who works part-time or at weekends is alerted on their day off, or not alerted on their working day | Their working days are not set. Their manager, a delegate or an administrator sets them on their details page (**Working days**). |
| A night shift is split across two days | The site's day start is midnight. Set a day start before the shift begins for that site (`APPLICATION_SITEDAYSTARTS`, for example `Plant 2=18:00`). See [Configuration](configuration.md#time-zone-and-day-start). |

## Data looks wrong

| Symptom | Cause and fix |
|---|---|
| Times are shifted, or "today" starts at the wrong hour | Set `application.timezone` (and `spring.jackson.time-zone`) explicitly. An unknown zone id silently becomes GMT. The startup log shows the zone in effect. |
| An admin sees yesterday's data | They chose another day with **System Date**. Pick today again, or start a new session. |
| A directory change (new group, new member, new title) is not visible | Caches: users, groups and the org chart are refreshed after `cache-duration-refresh-minutes` (60) and expire after `cache-duration-expiration-minutes` (120). Evict groups from **Settings → Group Cache**. `user_records` metadata is refreshed hourly. |
| An organization is missing from the org chart | Its value matches a parse rule's prefix but not its regex. Look for `Parse rule ... did not match` in the log. Or `duty-sub-org-filter` excludes it. |
| A group tree is cut short, or the log shows a paging warning | Results were truncated at `application.ldap.max-pages`, or the directory's size limit is below `page-size`. |
| A group is missing from **Groups** | Its base DN is wrong (`Groups base DN not found, returning none` or `Skipping top level group ...` in the log), or the entry is not of the groups' object class (`groupOfUniqueNames` unless `application.ldap.group-object-class` says otherwise). |
| A calendar says *The calendar could not be loaded* | The server refused the read, for example a range over `application.events.max-view-days` (400) days, or the directory did not answer. Before 0.0.11 the profile, person and Settings calendars stayed empty in browsers east of UTC; upgrade. |
| A holiday added on Settings landed a day early | Before 0.0.11, in a browser east of UTC. Delete it and add it again after upgrading. |
| A group report lists someone who has left the directory, or shows an employee type, organization or location that changed in the directory within the last hour | From 0.0.11 a group report takes these from each member's user record, which the hourly directory refresh updates. The member list itself still comes from the directory. |
| The log says `RefreshUserRecordsCron: the directory did not answer (...); nothing changed, the next run will retry` (`WARN`), and the audit trail has a `job.failed` for it | The hourly directory refresh could not list the directory's users. No user record was changed. The refresh never removes or deactivates anyone, and does not count anyone out of the license, whether the directory answers or not. |
| A status request says *The directory did not answer, so nobody was e-mailed about it* | The request is saved; the directory did not answer for the person's manager when it was made, so neither the manager nor the administrators were e-mailed (earlier releases e-mailed the administrators and said the person had no manager). Whoever decides it sees it on their **Approvals** page. |
| **Groups** says *The directory did not answer for part of this tree; try again* | The directory stopped answering part way through the tree, or for a whole top-level tree. What is shown may lack groups; it is not cached, so reloading reads it again once the directory answers. |
| Every directory search fails against Active Directory, the hourly refresh included, with a `PartialResultException` | The base DN is the domain root and referrals are not ignored. Leave `spring.ldap.referral` at `ignore` and `spring.ldap.template.ignore-partial-result-exception` at `true` (the defaults), or use a base DN below the domain root. |
| A scheduled report stopped arriving, its alternate managers no longer apply, or a favorite leads nowhere, after a group was renamed or moved | ReadyRoom keeps these under the group's DN. **Check** under *Stored Group DNs* on Settings lists the stored DNs the directory no longer has; set them up again under the new DN. |
| After **Evict** on Settings, another replica still shows the old group | Each other replica drops it within `application.cache-invalidation-poll-millis` (10 s). If the message said the eviction could not be passed on, the others keep their copy until it expires. |
| A profile column (organization, location, ...) changed in the directory but not in ReadyRoom right after sign-in | Expected: sign-in fills only empty columns. The hourly directory refresh brings changed values. |
| Old days show no check-in times or statuses, and the group report has a *Before &lt;date&gt;: monthly summaries* table | Retention (`APPLICATION_RETENTION_EVENTSDAYS`) has purged those days and kept monthly summaries. See [Operations → Retention](operator-guide.md#retention). |
| An erased person reappears with an empty record | They are still in the directory: ReadyRoom recreates them at sign-in and at the hourly directory refresh. Remove or disable them in the directory. |
| Duplicate users that differ only by DN case | Releases with Flyway merge them automatically: the `V1` migration merges user rows whose DNs differ only by letter case, and the user DN is unique regardless of case. The log shows `Merging N duplicate user record(s)`. |

## Audit trail

| Symptom | Cause and fix |
|---|---|
| **Audit log** shows a red warning, *N audit event(s) could not be added to the chain*; the log has `could not be chained and is quarantined`; `ens_audit_quarantined` is above 0 (from 0.0.6) | A stored audit event could never become part of the hash chain: its stored form is malformed, its id is already in the trail, or the database refuses one of its values. ReadyRoom set it aside in `audit_quarantine`, exactly as stored, and chained an `audit.chain-failed` event (severity `alert`) in its place, so every other event is recorded and chained as usual and **Verify integrity** still reports the chain intact. Nothing needs to be done for the trail to work. Investigate how the event came to be stored that way: a database edited or restored by hand, or a fault in the release that recorded it. The **Quarantined events** list on **Audit log** shows each one's stored body, the reason and its SHA-256, which the `audit.chain-failed` event's `details.bodySha256` matches. Keep the rows with your audit records: the table cannot be changed or emptied by the application's account (a trigger refuses UPDATE and DELETE). |
| **Verify integrity** reports the chain broken at a `seq`, with *seq N to M are missing* or *its hash is not the chain head's* (0.0.11) | Events are missing from the start of the trail without audit retention having pruned them, or from its end. Up to 0.0.10 such a trail verified as intact. Rows were deleted by other means than retention (by hand, or by a partial restore): compare with your SIEM's copy and your backups. See [Audit logging](../audit-logging.md#what-verify-integrity-checks). |
| The SIEM has a batch of audit events twice after its syslog collector restarted (0.0.11) | Syslog over `tcp` and `tls` has no delivery acknowledgement. When a connection turns out to be broken, ReadyRoom sends the batch last written on it again, so up to one batch can repeat: de-duplicate on `id`. Before 0.0.11 that batch was lost without an error. Where no event may be missing, use Splunk HEC or the pull API. |
| Recent events missing from **Audit log**, exports or the SIEM, and `ens_audit_pending` keeps rising (from 0.0.6) | Events are waiting to be chained, and the chainer keeps failing on something that is not an event's fault (the log says `Could not chain the recorded audit events`, with the database error). It retries with backoff; fix the database problem and they are chained. Nothing is lost meanwhile. |

## Performance

| Symptom | Cause and fix |
|---|---|
| Pages slow | Turn on `application.diagnostics.request-metrics` to count SQL statements and LDAP operations per request. The indexes come from the `V1` migration; check that it was applied (`flyway_schema_history`). |
| Requests hang on the directory | The connect and read timeouts under `spring.ldap.base-environment` must be set (5 s and 30 s in the packaged configuration). Check pool exhaustion: by default the pool holds one connection per request and job thread (`application.ldap.pool-max-size`); a `-D` value of `com.sun.jndi.ldap.connect.pool.maxsize` overrides it. |
| Sign-ins answered `503` with `Retry-After`, the log saying `Sign-in could not ... the directory did not answer` | A directory read at sign-in timed out or found no free pooled connection, three times in a row. Before 0.0.6 such a sign-in was refused with `401` as if the person did not exist. Check the directory and the pool size (above). |
| Database connections exhausted | Keep `spring.jpa.open-in-view: false`. Each replica opens at most `DB_POOL_SIZE` (20) connections; raise it within PostgreSQL's `max_connections` divided by the number of replicas. `FATAL: sorry, too many clients already` from PostgreSQL means the replicas together ask for more than `max_connections`. |
| A page or API call answers `500`, and the log has `canceling statement due to statement timeout` or `canceling statement due to lock timeout` (0.0.11) | PostgreSQL cut a statement off after `DB_STATEMENT_TIMEOUT` (570 s) or a lock wait after `DB_LOCK_TIMEOUT` (120 s). Find what holds the lock or makes the statement slow (`pg_stat_activity`, `pg_locks`); a scheduled job that hits either tries again on its next run. Raise the limits only if such statements are expected. |
| The log has `Job <name> has held its lock since before ... and seems stuck` (`ERROR`, 0.0.11) | A scheduled job's run lasted 30 times its lock time. Its lock lapses so the job can run again. Look for what the run waits on (the database, the directory, the mail server) and restart that replica if the run never ends. |

## Webhooks

| Symptom | Cause and fix |
|---|---|
| A webhook shows *disabled* though nobody disabled it; the audit log has `webhook.disabled` by `system` (0.0.11) | Its receiver gave no HTTP answer to 10 tries in a row, about a day. Fix the receiver or the network path, then choose **Enable** on **Administration → Integrations**. Events raised while it was disabled are not sent. |
| Deliveries show as failed with `expired: not delivered within 72 hours of being queued` (0.0.11) | They waited 3 days behind an endpoint that did not answer and were given up. They are not sent again. |
| A receiver gets `status.changed` events with `forced` `true` around midnight | Automatic check-outs: of screens locked in the evening, and from 0.0.11 of sessions left open at the end of the person's day. |

## License

| Symptom | Cause and fix |
|---|---|
| The license screen says *That license was not installed. This license starts on ...* (or *expired on ...*) (0.0.11) | A license that is not yet valid, or has expired, is refused while the installed one is in full effect, so a working installation is not left unlicensed. Upload next term's license on or after its start date. See [Licensing](../licensing.md#when-an-upload-is-refused). |
| The license message ends with *(This server's clock is behind a time this installation has already seen, so the license is checked as of ...)* (0.0.11) | The host's clock was set back by more than a day. Correct the clock. See [Licensing → The clock](../licensing.md#the-clock). |

## Reaching the server

| Symptom | Cause and fix |
|---|---|
| The app container is healthy, but `https://<host>:8181` does not answer | TLS is off by default, so ReadyRoom speaks plain HTTP: use `http://`, or turn TLS on (`SSL_ENABLED=true` with `docker-compose.tls.yml`, or `tls.enabled` in Helm). |
| `http://<host>:8181` returns an empty or garbled reply | TLS is on, so the port speaks HTTPS. Use `https://`. |
| Certificate sign-in or the Windows client fails behind an ingress | Either TLS reaches the pod unchanged (TLS passthrough or a `LoadBalancer` Service), or the ingress verifies the client certificate and forwards it in a header (`x509.source: header`, with the proxy secret and the trusted CA). An edge-terminating ingress with neither passes no certificate on. See [Installation → Expose ReadyRoom](install.md#5-expose-readyroom). |
