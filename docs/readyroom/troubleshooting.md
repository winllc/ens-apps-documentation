# Troubleshooting

Problems are grouped by where they show up. Most have a matching line in the server log or in a
workstation log: `%LOCALAPPDATA%\InOutWorker\logs\inoutworker-yyyyMMdd.log` in the user's profile
for login, lock, unlock and remote disconnects, and `%ProgramData%\InOutWorker\logs\` for the
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
| Port in use (release package) | Another process holds the host port `READYROOM_PORT` (8181). Choose another host port in `.env`; ReadyRoom inside the container stays on 8181. |

## Sign-in and access

| Symptom | Cause and fix |
|---|---|
| The browser goes to the login page although a certificate is installed | The certificate is not issued by a CA in the server truststore, or the browser did not offer it. Check the truststore, then restart the browser so it asks again. |
| A browser with a trusted certificate is sent to the login form, and the audit log has a failed `auth.login` with the reason *unknown user* | The certificate subject DN is not a directory entry, so the certificate does not sign in. Make the subject equal the LDAP DN, set `ENS_X509_USER_FILTER`, or set `lookup-on-dn-attribute` and `user-dn-attribute`. |
| A certificate is refused with `certificate revoked` or `revocation unknown` | The certificate is revoked, or its status couldn't be determined with `ENS_X509_REVOCATION_FAIL=closed`: the OCSP responder or CRL distribution point didn't answer, or the issuer's certificate isn't known. Check the log (`Refusing ... revocation status unknown`), and on an air-gapped network use `ENS_X509_CRL_DIR` or `ENS_X509_OCSP_RESPONDER_URL`. |
| A certificate is refused with `issuer not allowed` or `no single directory user matches` | Its issuer isn't in `ENS_X509_ALLOWED_ISSUERS`, or `ENS_X509_USER_FILTER` matched no entry or more than one. |
| Header mode: the log shows `Ignoring the client certificate in <header> from <address>: <reason>` | The request didn't come from an address in `ENS_X509_TRUSTED_PROXIES`, or carried no or the wrong `X-ENS-Proxy-Secret`. With ingress-nginx, the secret is added with a `configuration-snippet` annotation, which the controller disables by default since 1.9. |
| Users must click **I agree** before signing in | The consent banner is on (`ENS_CONSENT_BANNER_ENABLED`). Expected. |
| Users are signed out sooner than before | The session lifetimes `ENS_SESSION_*` (idle `30m`, absolute `12h`). The audit log's `auth.session-expired` gives the reason. |
| Login form says the credentials are wrong | The user is searched under `user-base-dn` by `uid`, `cn` or `mail`. Check the base DN, and that exactly the expected entry matches. |
| No **Log out** in the user menu | Expected for certificate sessions. The certificate would sign you straight back in. |
| A user cannot open a group | Only admins can open any group. Everyone else can open groups they are a member of in the directory (`uniqueMember`) or manage. Memberships are cached for 300 s. |
| A manager sees only the status of some people, without times or notes | Expected. Details are shown only for the people they are responsible for: members of groups they manage, their direct reports, and the people they are a delegate of. The `MANAGER` role alone does not grant them. See [Security → Authorization](security.md#authorization). |
| Coworkers show only `IN` or `OUT`, or no status at all | `APPLICATION_PRIVACY_COWORKERVISIBILITY` is `status` or `none`. Expected. People see themselves, their reports and the people they are a delegate of in full whatever it says. |
| A user can no longer set Work From Home, TDY, Out of Office or Scheduled Leave on their calendar; it says *(needs approval)* | Expected since the approvals release: these statuses are requested and approved. Remove a status from `APPLICATION_EVENTS_REQUESTSTATUSES` to let people set it themselves again. |
| A request is not in anyone's **Waiting for your approval** | Only the person's directory manager, their delegates and administrators decide requests; group managers don't. Check the person's **Manager** field and **Delegates** card, or have an administrator decide it. Nobody decides their own request. |
| `POST /api/event/update` returns `403` | The caller tried to record a status only managers record on their own calendar, or a status on someone else's calendar without being their manager, delegate or an administrator. |
| `POST /api/event/update` returns `400` with `range_too_long`, `invalid_range`, `invalid_status`, `invalid_time` or `invalid_dn` | The request body is wrong; the `message` says how. Ranges are limited to `APPLICATION_EVENTS_MAXRANGEDAYS` (366) days. |
| A user can't change their delegates | Expected: only administrators set them, on the person's details page. |
| The log shows `N people have delegates (alternate managers) chosen before administrators managed them` | Review them under **Administration → Delegates**: **Keep**, **Change** or **Remove**. |
| An admin's role change does not show | Roles are loaded at sign-in. The user needs a new session. |
| Metrics or Settings are missing from the sidebar | They need the `ADMIN` or `MANAGER` role. Check `super-user-dns`, or set the role on the user's details page. |

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
| `Configuration error: BaseUrl ...` (exit 1) | Set `BaseUrl` in `inoutworker.config.json` or with `-BaseUrl`. The built-in default is a lab address. |
| `'login' posts to an authenticated endpoint and no client certificate is available.` (exit 2) | No valid certificate with a private key in `CurrentUser\My`. |
| `POST to ... failed (HTTP 401)` (exit 3) | The server did not accept the certificate or the ticket. |
| A TLS trust error (exit 3) | The workstation does not trust the server's CA, or the host name does not match the server certificate. |
| `Logout has no sessionId` | The morning's login post failed, so the logout cannot be attributed. The server refuses it with `401`. Fix the login failure. |
| `... failed (HTTP 401) [unknown_session] ...` on a logout | The `sessionId` is unknown, already used (for example by a remote disconnect earlier in the same logon) or older than 7 days. Nothing is recorded. |
| `... failed (HTTP 429) [rate_limited] ...` on a logout | See the server-side row below. |

| Symptom on the server | Cause and fix |
|---|---|
| Status stays `AWAY` all evening | No logoff event arrived: the PC was shut down, or the session ended without event 4647 (the Logout task) or a remote disconnect (the Disconnect task). The auto check-out job checks the person out once the lock is older than `check-out-after-minutes`. |
| People show `OUT` minutes after locking | `check-out-after-minutes` is too low. The default is `240`; check `APPLICATION_CHECKOUTAFTERMINUTES` (Helm: `checkOutAfterMinutes`). |
| Logouts missing, and the audit log has failed `auth.login` events with the reason *unknown session* | Logouts without a usable `sessionId` are refused and not stored. Look at the workstation logs for why the login failed or the session file is missing. |
| Logouts get `429` (`rate_limited`), and the log shows `... sent more than N logoffs with an unusable session id this minute; refusing them with 429` | More than `application.logoff.refused-per-minute` (30) logouts with a stale or missing session id came from one address within a minute. They would have been refused anyway: a logout with a usable `sessionId` is never limited. Fix the workstations sending them. |
| `IN` although the user only unlocked | Expected: the first unlock of the day is stored as `CHECK_IN`. |

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
| No alerts at all | Look for `End SendAbsentNotificationsCron` in the log. On weekends and holidays the job does nothing. With the built-in defaults, the first run is 30 minutes after startup. |
| A late person was not alerted | They have no expected time (no preferred login time, no check-in history in 30 days and no late arrival), they have an excusable status, they were already alerted today, or they have no `user_records` row yet (the directory refresh creates one). |
| Alert created but no email | `Failed to send notification to ...` (SMTP), `Manager does not have an email address`, or `Invalid email address` in the log. |
| The wrong people are alerted | Recipients are the directory manager (`user-ldap-manager-id-attribute` / `manager-ldap-id-attribute`) plus the person's delegates, which administrators set. Group managers are **not** alerted. |
| Someone with a pending leave request was alerted | Expected: a request excuses nothing until it is approved. |
| Request emails don't arrive | No mail server is configured, or sending failed (`Failed to send "<subject>" to ...` in the log). Request emails are sent once and not retried. |
| The email link points to `localhost` | Set `application.application-base-url`. |
| Alerts arrive an hour off | `application.timezone` is not set, or does not match the users' zone. The job uses the JVM default time zone. |

## Data looks wrong

| Symptom | Cause and fix |
|---|---|
| Times are shifted, or "today" starts at the wrong hour | Set `application.timezone` (and `spring.jackson.time-zone`) explicitly. An unknown zone id silently becomes GMT. The startup log shows the zone in effect. |
| An admin sees yesterday's data | They chose another day with **System Date**. Pick today again, or start a new session. |
| A directory change (new group, new member, new title) is not visible | Caches: users, groups and the org chart are refreshed after `cache-duration-refresh-minutes` (60) and expire after `cache-duration-expiration-minutes` (120). Evict groups from **Settings → Group Cache**. `user_records` metadata is refreshed hourly. |
| An organization is missing from the org chart | Its value matches a parse rule's prefix but not its regex. Look for `Parse rule ... did not match` in the log. Or `duty-sub-org-filter` excludes it. |
| A group tree is cut short, or the log shows a paging warning | Results were truncated at `application.ldap.max-pages`, or the directory's size limit is below `page-size`. |
| A group is missing from **Groups** | Its base DN is wrong (`Groups base DN not found, returning none` or `Skipping top level group ...` in the log), or the entry is not a `groupOfUniqueNames`. |
| Old days show no check-in times or statuses, and the group report has a *Before &lt;date&gt;: monthly summaries* table | Retention (`APPLICATION_RETENTION_EVENTSDAYS`) has purged those days and kept monthly summaries. See [Operations → Retention](operator-guide.md#retention). |
| An erased person reappears with an empty record | They are still in the directory: ReadyRoom recreates them at sign-in and at the hourly directory refresh. Remove or disable them in the directory. |
| Duplicate users that differ only by DN case | Releases with Flyway merge them automatically: the `V1` migration merges user rows whose DNs differ only by letter case, and the user DN is unique regardless of case. The log shows `Merging N duplicate user record(s)`. |

## Performance

| Symptom | Cause and fix |
|---|---|
| Pages slow | Turn on `application.diagnostics.request-metrics` to count SQL statements and LDAP operations per request. The indexes come from the `V1` migration; check that it was applied (`flyway_schema_history`). |
| Requests hang on the directory | The connect and read timeouts under `spring.ldap.base-environment` must be set (5 s and 30 s in the packaged configuration). Check pool exhaustion: the pool holds at most 20 connections by default. |
| Database connections exhausted | Keep `spring.jpa.open-in-view: false`. |

## Reaching the server

| Symptom | Cause and fix |
|---|---|
| The app container is healthy, but `https://<host>:8181` does not answer | TLS is off by default, so ReadyRoom speaks plain HTTP: use `http://`, or turn TLS on (`SSL_ENABLED=true` with `docker-compose.tls.yml`, or `tls.enabled` in Helm). |
| `http://<host>:8181` returns an empty or garbled reply | TLS is on, so the port speaks HTTPS. Use `https://`. |
| Certificate sign-in or the Windows client fails behind an ingress | TLS must reach the pod unchanged. Use TLS passthrough or a `LoadBalancer` Service. See [Installation → Expose ReadyRoom](install.md#5-expose-readyroom). |
