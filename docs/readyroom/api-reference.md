# API reference

ReadyRoom has two kinds of HTTP endpoints:

- the **check-in API** under `/api/check`, called by the [Windows client](windows-client.md), and
- **JSON endpoints** under `/api/...` used by the web pages. These are internal to the UI rather
  than a published integration API, so their shapes can change between versions.

Every endpoint requires a signed-in session, a client certificate or (where noted) a Kerberos
ticket, except `POST /api/check/out` and `/actuator/health`. A `POST`, `PUT` or `DELETE` outside
`/api/check/**` also needs the CSRF token, in the header named by the page's `_csrf_header` meta
tag (value in `_csrf`). The base URL is `https://<host>:8181` with TLS on (`http://` while it is
off), or your ingress or route host with Helm. Timestamps in JSON records use the pattern
`MM/dd/yyyy HH:mm a z`.

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

"Signed in" means any authenticated user. `A/M` means the `ADMIN` or `MANAGER` role.

### Users

| Method and path | Access | Description |
|---|---|---|
| `GET /api/users/search?search=&page=1&size=10&sort=id&dir=asc` | Signed in | Directory users whose `cn` contains `search` (all users when empty), with today's status. Only presence and directory fields for people whose details the caller may not see (see [Security](security.md#authorization)). |
| `GET /api/users/usersearch?search=` | Signed in | Directory users whose `cn` contains `search`. Used by the manager pickers. |
| `GET /api/users/group/{groupDn}` | A member or manager of the group, or `ADMIN` | `{"data": [...statuses]}` for the group's members, presence only for people whose details the caller may not see. |
| `GET /api/users/reports` | Signed in | `{"data": [...]}`: the caller's direct reports, with status. |
| `GET /api/users/checkinoutrecords/{dn}?page=1&size=10&sort=timestamp&dir=desc` | Whoever may see the user's details | The user's records, paged, newest first. |
| `GET /api/users/managers/{dn}` | Whoever may see the user's details | The user's additional managers, as a list of objects with a `dn`. |
| `POST /api/users/managers/update` | Signed in | Body: a JSON array of DNs. Replaces the **caller's own** additional managers. |

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
| `POST /api/event/update` | Signed in | Sets the **caller's own** status for each day from `fromDate` up to, but not including, `toDate` (ISO zoned date-times). Body: `{"fromDate","toDate","status","lateArrivalTime"}`. `status` is a `UserStatusEnum` name. `lateArrivalTime` (ISO time) is used with `LATE_ARRIVAL`. |
| `GET /api/settings/calendar/all?from=&to=` | A/M | Organization calendar events. |
| `POST /api/settings/calendar/update` | A/M | Body `{"fromDate","toDate","title","holiday"}`. Adds one event per day in the range (end exclusive). |
| `DELETE /api/settings/calendar/delete/{id}` | A/M | Deletes one calendar event. |

### Notifications, org chart and session

| Method and path | Access | Description |
|---|---|---|
| `GET /api/notifications/my` | Signed in | The caller's notifications that are still unread. |
| `POST /api/notifications/markAllAsRead` | Signed in | Marks all of the caller's notifications as read. |
| `GET /api/orgnodes/hierarchy` | Signed in | The org chart tree, with presence statistics. |
| `GET /api/orgnodes/users/{orgName}` | Signed in | Directory users whose duty sub-organization attribute equals `orgName`, with status. Presence only for people whose details the caller may not see. |
| `POST /api/system/updateSystemTime` | Signed in | Body `{"dateTime": "<ISO zoned date-time>"}`. Sets the day viewed for the rest of the session. |

### Status values

`UserStatusEnum` values, with whether a user can choose them on their calendar and whether they
excuse an absence alert:

| Value | Name shown | User can choose | Excuses absence |
|---|---|:-:|:-:|
| `STANDARD` | normal | ✓ | (clears a status) |
| `OUT_OF_OFFICE` | Out of Office | ✓ | ✓ |
| `WORK_FROM_HOME` | Work From Home | ✓ | ✓ |
| `TDY` | TDY | ✓ | ✓ |
| `SCHEDULED_LEAVE` | Scheduled Leave | ✓ | ✓ |
| `EARLY_LEAVE` | Early Leave | ✓ | – |
| `LATE_ARRIVAL` | Late Arrival | ✓ | – (sets the expected time) |
| `UNSCHEDULED_LEAVE` | Unscheduled Leave | manager response | – |
| `ABSENT_EXCUSED` | Absent Excused | manager response | ✓ |
| `ABSENT_UNEXCUSED` | Absent Unexcused | manager response | – |
| `ABSENT` | Absent | manager response | – |

## Web pages

| Path | Access | Page |
|---|---|---|
| `/`, `/app` | – | Redirect to `/app/home` |
| `/login` | Open | Sign-in form (`POST /login` processes it; `POST /logout` signs out) |
| `/app/home` | Signed in | Home |
| `/app/groups` | Signed in | Group trees |
| `/app/users/{groupDn}` | A member or manager of the group, or `ADMIN` | Group members |
| `POST /app/group/report` | A/M who manages the group (`ADMIN`: any) | Group report (`groupDn`, `fromDate`, `toDate`) |
| `/app/user/search` | Signed in | User search |
| `/app/user/details/{dn}` | Signed in | A person's details. Times, notes, the audit log and the calendar only for the person themselves, `ADMIN` and the people responsible for them (see [Security](security.md#authorization)); anyone else sees presence and directory fields. |
| `POST /app/user/details` | ADMIN | Change a role (`dn`, `role`) |
| `/app/user/reports` | Signed in | My Reports |
| `/app/orgchart` | Signed in | Org chart |
| `/app/orgchart/details/{orgName}` | Signed in | Members of one organization. Not linked from the current UI. |
| `/app/metrics` | A/M | Metrics |
| `/app/profile` (GET, POST) | Signed in | My Profile (`notes`, `loginTime`) |
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
