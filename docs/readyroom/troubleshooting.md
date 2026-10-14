# Troubleshooting

Problems are grouped by where they show up. Most have a matching line in the server log or in the
workstation log (`%ProgramData%\InOutWorker\logs\inoutworker-yyyyMMdd.log`). For background, see
[Operations](operator-guide.md) and [Windows client](windows-client.md).

## Server will not start

| Error | Cause and fix |
|---|---|
| `Could not resolve placeholder 'application.banner-text'` | `application.banner-text` has no default. Set it. |
| `application.windows-auth.enabled is true but application.windows-auth.service-principal is not set` | Set `service-principal`, or turn Windows sign-in off. |
| `... keytab-location is not set` / `Windows sign-in keytab ... does not exist or cannot be read` | Mount the keytab and make it readable by the container user. The image runs as user `185`, and `/etc/in-n-out` is group `0`, mode `0750`. |
| Keystore or truststore errors | Check the path (`classpath:` or `file:`), password and `PKCS12` type in `server.ssl.*`. |
| Database connection refused | Check `spring.datasource.*` and that PostgreSQL accepts connections from the server. |
| Port in use | Another process holds `server.port` (8444). The local test CA (`step-ca`) uses 8443. |

## Sign-in and access

| Symptom | Cause and fix |
|---|---|
| The browser goes to the login page although a certificate is installed | The certificate is not issued by a CA in the server truststore, or the browser did not offer it. Check the truststore, then restart the browser so it asks again. |
| Signed in, but every page says *Unauthorized* | The certificate subject DN (or the DN found through Windows sign-in) is not a directory entry, so the session has no roles. Make the subject equal the LDAP DN, or set `lookup-on-dn-attribute` and `user-dn-attribute`. |
| Login form says the credentials are wrong | The user is searched under `user-base-dn` by `uid`, `cn` or `mail`. Check the base DN, and that exactly the expected entry matches. |
| No **Log out** in the user menu | Expected for certificate sessions. The certificate would sign you straight back in. |
| A user cannot open a group | Non-admin users can open only groups they are a member of in the directory (`uniqueMember`). Memberships are cached for 300 s. |
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
| `Logout has no sessionId` | The morning's login post failed, so the logout cannot be attributed. Fix the login failure. |

| Symptom on the server | Cause and fix |
|---|---|
| Status stays `AWAY` all evening | No logoff event arrived (the PC was shut down, or the session ended without event 4647 or a remote disconnect). The auto check-out job checks the person out once the lock is older than `check-out-after-minutes`. |
| People show `OUT` minutes after locking | `check-out-after-minutes` is too low. The packaged value is `2`, while the built-in default is `240`. |
| Check-outs with an empty DN in `check_in_out_records` | Logouts posted without a usable `sessionId`. |
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
| `not a user account` | A service or host principal was presented. |

On the client, `The server redirected instead of accepting Windows sign-in` means
`application.windows-auth.enabled` is off on that server. To inspect the keytab:
`klist -kte /etc/in-n-out/http.keytab`.

## Absence alerts

| Symptom | Cause and fix |
|---|---|
| No alerts at all | Look for `End SendAbsentNotificationsCron` in the log. On weekends and holidays the job does nothing. With the built-in defaults, the first run is 30 minutes after startup. |
| A late person was not alerted | They have no expected time (no preferred login time, no check-in history in 30 days and no late arrival), they have an excusable status, they were already alerted today, or they have no `user_records` row yet (the directory refresh creates one). |
| Alert created but no email | `Failed to send notification to ...` (SMTP), `Manager does not have an email address`, or `Invalid email address` in the log. |
| The wrong people are alerted | Recipients are the directory manager (`user-ldap-manager-id-attribute` / `manager-ldap-id-attribute`) plus the person's own *Additional Managers*. Group managers are **not** alerted. |
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
| Duplicate users that differ only by DN case | Run `db/migrations/001_indexes_and_unique_user_dn.sql`, which merges them and adds a unique index. |

## Performance

| Symptom | Cause and fix |
|---|---|
| Pages slow | Turn on `application.diagnostics.request-metrics` to count SQL statements and LDAP operations per request. Make sure the migration's indexes exist. |
| Requests hang on the directory | The connect and read timeouts under `spring.ldap.base-environment` must be set (5 s and 30 s in the packaged configuration). Check pool exhaustion: the pool holds at most 20 connections by default. |
| Database connections exhausted | Keep `spring.jpa.open-in-view: false`. |

## Test stack

| Symptom | Cause and fix |
|---|---|
| `docker compose up` fails with container-name conflicts | `docker-compose.yml` and `docker-compose.app.yml` use the same container names. Run only one of them. |
| The app container is up, but `https://localhost:8444` does not answer | At the current commit, `test/application.yml` sets `server.port: 8181` with SSL off, while the compose file publishes `8444:8444`. Align the two. |
| Controller tests return 404 for every request | `@SpringBootApplication` is in `com.winllc.innoutwork.config`, so `@WebMvcTest` slices do not scan controllers. Import the controller explicitly, as the existing tests do, and use `subjectPrincipalRegex("(.*)")` in test security configurations. |
| Testcontainers tests are skipped | Docker is not available. That is expected; they need Docker. |
