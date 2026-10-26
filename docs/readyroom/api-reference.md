# API reference

ReadyRoom has three kinds of HTTP endpoints:

- the **public API** under **`/api/v1`**: versioned, read-only, described by an OpenAPI document and
  called with an **API token**, for HR, payroll and workforce systems;
- the **check-in API** under `/api/check`, called by the [Windows client](windows-client.md);
- **JSON endpoints** under the rest of `/api/...`, used by the web pages. These are internal to the
  UI, so their shapes can change between versions: integrate through `/api/v1` instead.

ReadyRoom also sends signed [webhooks](webhooks.md) for absence alerts and status changes, and the
audit trail has its own pull API (see [Audit logging](../audit-logging.md#the-pull-api)).

## Public API (`/api/v1`)

### The OpenAPI document

Served at `/api/v1/openapi` (JSON) and `/api/v1/openapi.yaml` without a token, with the public base
URL (`APPLICATION_BASE_URL`) as its server. The build checks the document against the code. Load it
into your API client or code generator.

### Endpoints

| Endpoint | Scope | What |
|---|---|---|
| `GET /api/v1/people?q=&location=&organization=&page=&size=` | `people:read` | Tracked people and their status today |
| `GET /api/v1/attendance/events?from=&to=&dn=&page=&size=` | `attendance:read` | Check-ins, locks, unlocks and check-outs, oldest first |
| `GET /api/v1/absences?from=&to=&dn=&page=&size=` | `absences:read` | Days with leave, telework, an absence or another status |
| `GET /api/v1/absences/requests?from=&to=&dn=&state=&page=&size=` | `absences:read` | Leave requests covering the range, with their decision (no notes) |

- Dates are `YYYY-MM-DD`, both ends included, at most `APPLICATION_API_MAXRANGEDAYS` (366) days.
- Pages count from 1 and hold up to `APPLICATION_API_MAXPAGESIZE` (500) items.
- Times are ISO-8601 with an offset, in each person's [time zone](exports.md#time-zones).
- Errors are `{"error": "...", "message": "..."}`.
- The API serves `GET`s only, and answers in the license states the features it reads are licensed
  in (attendance, calendar).

```bash
curl -H "Authorization: Bearer ensapi_..." \
  "https://readyroom.example.com/api/v1/attendance/events?from=2026-10-01&to=2026-10-31&size=500"

curl -H "Authorization: Bearer ensapi_..." \
  "https://readyroom.example.com/api/v1/absences?from=2026-10-01&to=2026-10-31&dn=CN%3DJane%20Doe%2COU%3DUsers%2CDC%3Dexample%2CDC%3Dcom"
```

### Tokens

Administrators create and revoke tokens on *Administration → Integrations*
(`/app/admin/integrations`).

- **Created with** a name, its scopes (`people:read`, `attendance:read`, `absences:read`), an expiry
  (1 to `APPLICATION_API_MAXTOKENDAYS`, 366, days) and a rate limit (requests a minute, up to
  `APPLICATION_API_MAXRATEPERMINUTE`). The token (`ensapi_...`) is shown **once**, in the page that
  creates it (never through the session); only its SHA-256 is stored, and it is never logged.
- **Never sees more than its creator may see now.** A token acts for the administrator who created
  it; their roles are loaded again on every call, within the token's scopes. A token whose creator is
  no longer an administrator, or has left the directory, is refused. Revoked and expired tokens stop
  at once.
- **Every call checks the owner live in the directory** (not the user cache): their entry must still
  exist and match `application.user-ldap-filter` and `APPLICATION_LDAP_ACCOUNTSTATUS_FILTER`, must not
  be disabled (Active Directory `userAccountControl` bit 0x2, 389/OpenLDAP `nsAccountLock=true`) or
  expired (Active Directory `accountExpires`), and must not be locked out, where any doubt means
  locked (Active Directory `msDS-User-Account-Control-Computed` or `lockoutTime` with the domain's
  `lockoutDuration`; OpenLDAP `pwdAccountLockedTime` with the policy's `pwdLockoutDuration`, or
  `APPLICATION_LDAP_ACCOUNTSTATUS_LOCKOUTDURATION` for the default policy; 389 `accountUnlockTime`).
  The owner must also still be an administrator. When the directory cannot be read, the token is
  refused. A usable account is remembered for at most
  `APPLICATION_LDAP_ACCOUNTSTATUS_CACHESECONDS` (60); refusals are never remembered. The same check
  applies to webhook owners and to scheduled reports' owners and recipients.
- **The bind account (`LDAP_USERNAME`) must be able to read** `userAccountControl`, `lockoutTime`,
  `accountExpires` and `msDS-User-Account-Control-Computed` (Active Directory), `nsAccountLock` and
  `accountUnlockTime` (389), `pwdAccountLockedTime`, `pwdPolicySubentry` and the policies'
  `pwdLockoutDuration` (OpenLDAP), and the domain root's `lockoutDuration` (Active Directory).
- **Bearer tokens only.** The API's security chain accepts bearer tokens and nothing else: it reads
  and creates no session, so it needs no CSRF token, and a browser's session cookie signs nothing in
  on it.
- **Rate limit** per token; past it, `429` with `Retry-After`. From the next release the counts are
  kept in the database (table `rate_limit_windows`, Flyway `V11`), once for all replicas: with N
  replicas behind the Service a token gets its rate, not N times it, and the one request that goes
  over is audited once. Window times are the database's clock. If the database cannot count for a
  moment, each replica counts in its own memory meanwhile (a WARN at most once a minute) rather than
  refuse requests. The same holds for `application.logoff.refused-per-minute` per client address.
  Up to the current release the counts are in each replica's memory, so a token may make up to N
  times its rate.
- **Audited**: `api.token-created` and `api.token-revoked` (with scopes, expiry and owner); each read
  as `data.exported` (`details.via` `api`, the actor the token); refused tokens as a failed
  `auth.login` (method `public-api-token`, with the reason); and a token's first request over its
  limit in a minute as `access.denied` (`rate_limited`).

### Settings

| Variable | Default | Helm | |
|---|---|---|---|
| `APPLICATION_API_MAXTOKENDAYS` | `366` | `api.maxTokenDays` | The longest a token may be valid for |
| `APPLICATION_API_DEFAULTRATEPERMINUTE` | `120` | `api.defaultRatePerMinute` | The rate a new token is offered |
| `APPLICATION_API_MAXRATEPERMINUTE` | `1200` | `api.maxRatePerMinute` | The most any token may be given |
| `APPLICATION_API_MAXRANGEDAYS` | `366` | | The longest date range one call covers |
| `APPLICATION_API_MAXPAGESIZE` | `500` | | The largest page |
| `APPLICATION_LDAP_ACCOUNTSTATUS_FILTER` | (none) | | An extra LDAP filter a usable account must match |
| `APPLICATION_LDAP_ACCOUNTSTATUS_LOCKOUTDURATION` | (unset) | | The default password policy's lockout length, where the directory does not say |
| `APPLICATION_LDAP_ACCOUNTSTATUS_CACHESECONDS` | `60` | | How long a usable account is remembered (the maximum; `0` turns it off) |
| `APPLICATION_LDAP_ACCOUNTSTATUS_BUILTINCHECKS` | `true` | | `false` turns the built-in attribute checks off (the filters still apply) |
| `APPLICATION_LDAP_ACCOUNTSTATUS_ACTIVEDIRECTORY` | `auto` | | `auto` (an entry with the objectClass `user`), `true` or `false` |

Put `/api/v1` behind the same ingress rules as the rest of the application.

## Check-in API

### `POST /api/check/{action}`

| `action` | Stored as | Authentication |
|---|---|---|
| `in` | `CHECK_IN` | Client certificate, or Kerberos when Windows sign-in is enabled |
| `lock` | `LOCK` | Same as `in` |
| `unlock` | `UNLOCK`, or `CHECK_IN` if it is the user's first record of the day | Same as `in` |
| `out` | `CHECK_OUT` | **None**: identified by `sessionId`. A signed-in call is accepted too. |

Any other `action` gets `400` (`invalid_action`).

**Request body** (`application/json`):

```json
{
  "sessionId": "8f6c0f3e-5d1b-4b8e-9d7a-3c1e2f4a5b6c",
  "windowsUserId": "jdoe"
}
```

| Field | Used for |
|---|---|
| `sessionId` | **`out` only.** The `sessionId` from the answer to this logon's `in`. An `out` without a sign-in is recorded under the DN of that check-in, provided no logout has used the id yet and the check-in is younger than `application.logoff.session-max-age-hours` (168, 7 days). `in`, `lock` and `unlock` ignore it. |
| `windowsUserId` | Stored with the record for information. With Kerberos sign-in it is replaced by the account name from the verified ticket. |

**How the DN is chosen:** `in`, `lock`, `unlock` and a signed-in `out` are recorded under the
authenticated principal's DN (certificate subject, or the directory user matched to the Kerberos
account), whatever the body says. A signed-in `out` whose `sessionId` is still usable and belongs to
someone else is refused. An `out` without a sign-in takes the DN of the check-in its `sessionId`
names. No record is ever stored without a DN.

On a signed-in call, the user's organization, employee type, location, branch and duty
sub-organization are copied onto the record.

**Response:** the stored record. Only the answer to `in` carries `sessionId`:

```json
{
  "id": 1234,
  "dn": "cn=Jane Doe,ou=Users,dc=example,dc=com",
  "timestamp": "03/04/2026 08:02 AM EST",
  "action": "CHECK_IN",
  "windowsUserId": "jdoe",
  "sessionId": "8f6c0f3e-5d1b-4b8e-9d7a-3c1e2f4a5b6c"
}
```

The Windows client saves `sessionId` from the `in` response and sends it back on `out`. No other
response or page shows it.

**Errors** are JSON, `{"error": "...", "message": "..."}`, except the `403`:

| Status | `error` | When |
|---|---|---|
| `400` | `invalid_action` | `action` is not `in`, `lock`, `unlock` or `out`. |
| `401` | `not_signed_in` | `in`, `lock` or `unlock` reached the API without a directory user. |
| `401` | `unknown_session` | An `out` without a sign-in whose `sessionId` is missing, unknown, already used or older than `session-max-age-hours`. Nothing is stored. |
| `403` | – | A signed-in `out` whose `sessionId` is still usable and belongs to another user (access denied). |
| `429` | `rate_limited` | More than `application.logoff.refused-per-minute` (30) logouts without a sign-in were refused from this client address within a minute. `Retry-After: 60`. A logout with a usable `sessionId` is never refused this way. |

=== "curl with a client certificate"

    ```bash
    curl --cert user.crt --key user.key --cacert ca.crt \
      -H 'Content-Type: application/json' \
      -d '{"windowsUserId":"jdoe"}' \
      https://readyroom.example.com:8181/api/check/in
    ```

=== "curl, logout"

    ```bash
    curl --cacert ca.crt -H 'Content-Type: application/json' \
      -d '{"sessionId":"8f6c0f3e-5d1b-4b8e-9d7a-3c1e2f4a5b6c","windowsUserId":"jdoe"}' \
      https://readyroom.example.com:8181/api/check/out
    ```

=== "PowerShell with Windows sign-in"

    ```powershell
    Invoke-RestMethod -Method Post -Uri https://readyroom.example.com:8181/api/check/in `
      -ContentType application/json -Body '{"windowsUserId":"jdoe"}' `
      -UseDefaultCredentials -MaximumRedirection 0
    ```

### `GET /api/check/records`

The records for the day being viewed in the session (today unless the session date was changed),
paged. `ADMIN` only. The records do not include `sessionId`.

| Parameter | Default | |
|---|---|---|
| `page` | `0` | Zero-based page |
| `size` | `10` | |
| `sortField`, `sortDir` | none | For example `timestamp`, `desc` |

Response: `{"data": [...records], "last_page": n, "total": n, "page": n}`.

## UI endpoints

These serve the web pages and can change between versions; use the [public API](#public-api-apiv1)
for integrations. Every one needs a signed-in session, a client certificate or (where noted) a
Kerberos ticket, except `POST /api/check/out` and `/actuator/health`. A `POST`, `PUT` or `DELETE`
outside `/api/check/**` also needs the CSRF token, in the header named by the page's `_csrf_header`
meta tag (value in `_csrf`). The base URL is `https://<host>:8181` with TLS on (`http://` while it
is off), or your ingress or route host with Helm. Timestamps in JSON records use the pattern
`MM/dd/yyyy HH:mm a z`.

"Signed in" means any authenticated user. `A/M` means the `ADMIN` or `MANAGER` role.

### Users

| Method and path | Access | Description |
|---|---|---|
| `GET /api/users/search?search=&page=1&size=10&sort=id&dir=asc` | Signed in | Directory users whose `cn` contains `search` (all users when empty), with today's status. For people whose details the caller may not see, only directory fields and what `application.privacy.coworker-visibility` allows (see [Security](security.md#authorization)). |
| `GET /api/users/usersearch?search=` | Signed in | Directory users whose `cn` contains `search`. Used by the manager pickers. |
| `GET /api/users/group/{groupDn}` | A member or manager of the group, or `ADMIN` | `{"data": [...statuses]}` for the group's members, limited by the coworker visibility setting for people whose details the caller may not see. |
| `GET /api/users/reports` | Signed in | `{"data": [...]}`: the caller's direct reports, with status. |
| `GET /api/users/checkinoutrecords/{dn}?page=1&size=10&sort=timestamp&dir=desc` | Whoever may see the user's details | The user's records, paged, newest first. |
| `GET /api/users/managers/{dn}` | Whoever may see the user's details | The user's delegates (alternate managers), as a list of objects with a `dn`. |
| `POST /api/users/managers/update/{dn}` | `ADMIN` | Body: a JSON array of DNs, each someone else in the directory. Replaces that person's delegates. `400` (`invalid_delegate`, `invalid_person`) for a bad list. |

`POST /api/users/managers/update`, with which people set their own additional managers, has been
removed: only administrators set delegates.

### Groups and permissions

| Method and path | Access | Description |
|---|---|---|
| `POST /api/groups/favoriteGroup` | A/M | Body `{"groupDn": "...", "selected": true}`. Adds or removes a favorite for the caller. |
| `GET /api/groups/managers/{groupDn}` | A/M who may open the group | The group's additional managers. |
| `POST /api/groups/managers/update/{groupDn}` | A/M who manages the group (`ADMIN`: any) | Body: a JSON array of DNs. Replaces the group's additional managers. |
| `GET /api/groups/hierarchy` | A/M | Returns nothing in the current version. |
| `POST /api/permissions/update` | A/M who manages the group (`ADMIN`: any) | Body `{"groupDn": "...", "userDn": "...", "selected": true}`. Grants or revokes a per-user group permission record. See the note in [Administration](admin-guide.md#groups-and-access). |

### Status calendar and organization calendar

| Method and path | Access | Description |
|---|---|---|
| `GET /api/event/all?dn=&from=&to=` | Whoever may see the user's details | A user's status entries between two ISO instants, as calendar events. |
| `GET /api/event/allWithLogins?dn=&from=&to=` | Whoever may see the user's details | The same, plus "Online: ..." periods and organization calendar events. |
| `POST /api/event/update` | Signed in | Puts `status` on each day from `fromDate` up to, but not including, `toDate` (ISO zoned date-times, or plain `yyyy-MM-dd`), at most `application.events.max-range-days` (366) days. Body: `{"fromDate","toDate","status","lateArrivalTime","note","dn"}`. See below. |
| `GET /api/event/statuses` | Signed in | The statuses the caller may put on their own calendar: `[{"status","name","needsApproval"}]`. |
| `GET /api/settings/calendar/all?from=&to=` | A/M | Organization calendar events. |
| `POST /api/settings/calendar/update` | A/M | Body `{"fromDate","toDate","title","holiday"}`. Adds one event per day in the range (end exclusive). |
| `DELETE /api/settings/calendar/delete/{id}` | A/M | Deletes one calendar event. |

**What `POST /api/event/update` does** depends on whose calendar and which status:

| Case | Result |
|---|---|
| The caller's own calendar, a status the person sets (`STANDARD`, `EARLY_LEAVE`, `LATE_ARRIVAL`) | Set at once: `{"status","fromDate","toDate","outcome":"set"}` |
| The caller's own calendar, a requested status (by default `OUT_OF_OFFICE`, `WORK_FROM_HOME`, `TDY`, `SCHEDULED_LEAVE`, `ABSENT_EXCUSED`) | A request is created: `"outcome":"requested"`, with `requestId` and a `message`. `note` (up to 500 characters) goes with it |
| The caller's own calendar, a status only managers record (`ABSENT`, `ABSENT_UNEXCUSED`, `UNSCHEDULED_LEAVE`) | `403` |
| Someone else's calendar (`dn`) | Set at once, any status, if the caller is the person's directory manager, one of their delegates or `ADMIN`; otherwise `403` |

Bad input is answered with `400` and `{"error","message"}`: `range_too_long`, `invalid_range`,
`invalid_status`, `invalid_time` or `invalid_dn`. Earlier releases answered these with `500`, and
returned the request body instead of the result.

### Status requests

All need the `calendar` license feature. Who may do what is checked on every call.

| Method and path | Access | Description |
|---|---|---|
| `POST /api/requests` | Signed in | Ask for a status on one's own days. Body: `status`, `fromDate`, `toDate` (the day after the last), optional `lateArrivalTime` and `note` (500 characters, shown to approvers). `201` with the request. `400` `not_a_request_status` for a status that is not a requested one |
| `GET /api/requests/mine` | Signed in | The caller's requests |
| `GET /api/requests/pending` | Signed in | Pending requests the caller may decide |
| `GET /api/requests/{id}` | The requester, whoever may decide it, `ADMIN` | One request |
| `POST /api/requests/{id}/approve`, `/reject` | The requester's directory manager, a delegate or `ADMIN`; never the requester | Optional body `{"comment": "..."}`. `409` (`not_pending`) once decided |
| `POST /api/requests/{id}/cancel` | The requester or `ADMIN` | A pending request, or an approved one until its last day has passed (`409` `not_cancellable` otherwise). Cancelling an approved one takes its days from today on off the calendar |

A request's `state` is `REQUESTED`, `APPROVED`, `REJECTED` or `CANCELLED`.

### Notifications, org chart and session

| Method and path | Access | Description |
|---|---|---|
| `GET /api/notifications/my` | Signed in | The caller's notifications that are still unread. |
| `POST /api/notifications/markAllAsRead` | Signed in | Marks all of the caller's notifications as read. |
| `GET /api/orgnodes/hierarchy` | Signed in | The org chart tree, with presence statistics (headcounts only with coworker visibility `none`, except for `ADMIN` and `MANAGER`). |
| `GET /api/orgnodes/users/{orgName}` | Signed in | Directory users whose duty sub-organization attribute equals `orgName`, with status, limited by the coworker visibility setting for people whose details the caller may not see. |
| `POST /api/system/updateSystemTime` | Signed in | Body `{"dateTime": "<ISO zoned date-time>"}`. Sets the day viewed for the rest of the session. |

### Status values

`UserStatusEnum` values, with who puts them on a person's calendar (with the default
`application.events.request-statuses`) and whether they excuse an absence alert:

| Value | Name shown | Class | Excuses absence |
|---|---|---|:-:|
| `STANDARD` | normal | Set by the person | (clears a status) |
| `OUT_OF_OFFICE` | Out of Office | Requested | ✓ once approved |
| `WORK_FROM_HOME` | Work From Home | Requested | ✓ once approved |
| `TDY` | TDY | Requested | ✓ once approved |
| `SCHEDULED_LEAVE` | Scheduled Leave | Requested | ✓ once approved |
| `EARLY_LEAVE` | Early Leave | Set by the person | – |
| `LATE_ARRIVAL` | Late Arrival | Set by the person | – (sets the expected time) |
| `UNSCHEDULED_LEAVE` | Unscheduled Leave | Recorded by managers | – |
| `ABSENT_EXCUSED` | Absent Excused | Requested | ✓ once approved |
| `ABSENT_UNEXCUSED` | Absent Unexcused | Recorded by managers | – |
| `ABSENT` | Absent | Recorded by managers | – |

A manager, delegate or administrator can record any of them for someone else, at once.

## Web pages

| Path | Access | Page |
|---|---|---|
| `/`, `/app` | – | Redirect to `/app/home` |
| `/login` | Open | Sign-in form (`POST /login` processes it; `POST /logout` signs out) |
| `/signed-out` (next release) | Open | After signing out with `ENS_SSO_OIDC_END_SESSION` or `ENS_SSO_SAML_SINGLE_LOGOUT`: continues to the identity provider (and shows a **Continue** button); also the OIDC post-logout redirect URI. See [Single sign-on → Signing out](single-sign-on.md#signing-out) |
| `/logout/saml2/slo` (next release) | Open | SAML single logout service (HTTP-Redirect): the provider's `LogoutResponse` and `LogoutRequest` |
| `/oauth2/authorization/oidc`, `/login/oauth2/code/oidc` | Open, with OIDC on | Start a single sign-on, and its redirect URI. See [Single sign-on](single-sign-on.md) |
| `/saml2/authenticate/saml`, `/login/saml2/sso/saml`, `/saml2/service-provider-metadata/saml` | Open, with SAML on | Start a single sign-on, the assertion consumer service, and this service provider's metadata |
| `/api/v1/openapi`, `/api/v1/openapi.yaml` | Open | The public API's OpenAPI document |
| `/app/home` | Signed in | Home |
| `/app/groups` | Signed in | Group trees |
| `/app/users/{groupDn}` | A member or manager of the group, or `ADMIN` | Group members |
| `POST /app/group/report` | A/M who manages the group (`ADMIN`: any) | Group report (`groupDn`, `fromDate`, `toDate`) |
| `POST /app/export/group-report` | A/M who manages the group (`ADMIN`: any) | The group report as a file (`groupDn`, `fromDate`, `toDate`, `format` `csv` or `xlsx`, `zones` `person`, the default, or `mine` for the viewer's zone). See [Reports, exports and time zones](exports.md) |
| `POST /app/export/people` | Signed in | A people list as a file (`search` or `group`, `format`) |
| `/app/reports/schedules` | A/M | Scheduled reports. `POST` creates one; `POST .../{id}/send` sends now; `POST .../{id}/delete` deletes |
| `/app/admin/integrations` | ADMIN | API tokens and webhooks. `POST .../tokens`, `.../tokens/{id}/revoke`, `.../webhooks`, `.../webhooks/{id}/disable`, `/enable`, `/delete`, `/ping` |
| `/app/user/search` | Signed in | User search |
| `/app/user/details/{dn}` | Signed in | A person's details. Times, notes, the audit log and the calendar only for the person themselves, `ADMIN` and the people responsible for them (see [Security](security.md#authorization)); anyone else sees presence and directory fields. |
| `POST /app/user/details` | ADMIN | Change a role (`dn`, `role`) |
| `/app/user/reports` | Signed in | My Reports |
| `/app/orgchart` | Signed in | Org chart |
| `/app/orgchart/details/{orgName}` | Signed in | Members of one organization. Not linked from the current UI. |
| `/app/metrics` | A/M | Metrics |
| `/app/profile` (GET, POST) | Signed in | My Profile (`notes`, `loginTime`) |
| `/app/approvals` | Signed in (`calendar` feature) | Approvals: requests waiting for the caller, the request form, the caller's requests. `POST /app/approvals/request`, `/app/approvals/{id}/approve`, `/reject`, `/cancel` do the same as the API |
| `/app/privacy`, `GET /app/privacy/export` | Signed in, any license state | My data, and the caller's own data as JSON (`readyroom-personal-data/1`) |
| `/app/admin/delegates` | ADMIN | Delegates review. `POST /app/admin/delegates/keep` and `/clear` keep or remove a person's delegates |
| `/app/admin/privacy?dn=` | ADMIN, any license state | Retention status, and a person's data inventory. `GET /app/admin/privacy/export?dn=` downloads it; `POST /app/admin/privacy/erase` erases it (one-time token and typed name) |
| `/app/notifications/id/{id}` | The recipient, or `ADMIN` | One notification |
| `POST /app/notifications/update` | Recipient only | Respond to a notification (`notificationId`, `response`) |
| `/app/settings` | A/M | Settings |
| `POST /app/settings/orgparserules` | A/M | Save a parse rule (`id`, `orgName`, `orgParseRegex`) |
| `POST /app/settings/orgparserules/delete/{id}` | A/M | Delete a parse rule |
| `POST /app/settings/cache/groups/evict` | A/M | Evict a cached group (`dn`) |
| `POST /app/settings/cache/groups/evictall` | A/M | Evict every cached group |
| `/actuator/health` (and its groups `/liveness` and `/readiness`) | Open | Spring Boot Actuator health, status only; details for `ADMIN`. Any other Actuator endpoint you expose on the application's port needs `ADMIN`. With `MANAGEMENT_SERVER_PORT` set, the actuator is on that port instead |
| `/actuator/prometheus` | Open, management port only | Prometheus metrics; exposed only when `MANAGEMENT_SERVER_PORT` is set |
| `GET`, `POST /consent` | Open | The notice-and-consent banner and its **I agree**, when `ENS_CONSENT_BANNER_ENABLED` is on |
