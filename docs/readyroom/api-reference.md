# API reference

ReadyRoom has two kinds of HTTP endpoints:

- the **check-in API** under `/api/check`, called by the [Windows client](windows-client.md), and
- **JSON endpoints** under `/api/...` used by the web pages. These are internal to the UI rather
  than a published integration API, so their shapes can change between versions.

Every endpoint requires a signed-in session, a client certificate or (where noted) a Kerberos
ticket, except `POST /api/check/out` and `/actuator/**`. The base URL is
`https://<host>:8444`. Timestamps in JSON records use the pattern `MM/dd/yyyy HH:mm a z`.

## Check-in API

### `POST /api/check/{action}`

| `action` | Stored as | Authentication |
|---|---|---|
| `in` | `CHECK_IN` | Client certificate, or Kerberos when Windows sign-in is enabled |
| `lock` | `LOCK` | Same as `in` |
| `unlock` | `UNLOCK`, or `CHECK_IN` if it is the user's first record of the day | Same as `in` |
| `out` | `CHECK_OUT` | **None** |

Any other `action` fails with an error.

**Request body** (`application/json`):

```json
{
  "sessionId": "8f6c0f3e-5d1b-4b8e-9d7a-3c1e2f4a5b6c",
  "windowsUserId": "jdoe"
}
```

| Field | Used for |
|---|---|
| `sessionId` | **`out` only.** The server looks up the first record with this session id and records the check-out under that record's DN. For `out`, the request's own identity is ignored once a match is found. |
| `windowsUserId` | Stored with the record for information. With Kerberos sign-in it is replaced by the account name from the verified ticket. |

**How the DN is chosen:** the authenticated principal's DN (certificate subject, or the directory
user matched to the Kerberos account). If the body's `sessionId` matches an existing record, that
record's DN is used instead. An anonymous `out` with no matching `sessionId` is stored with no DN.

The user's organization, employee type, location, branch and duty sub-organization are copied
onto the record from the signed-in user.

**Response:** the stored record:

```json
{
  "id": 1234,
  "dn": "cn=Jane Doe,ou=Users,dc=example,dc=com",
  "timestamp": "03/04/2026 08:02 AM EST",
  "sessionId": "8f6c0f3e-5d1b-4b8e-9d7a-3c1e2f4a5b6c",
  "action": "CHECK_IN",
  "windowsUserId": "jdoe",
  "organization": "WinLLC",
  "employeeType": "FT",
  "location": "New York",
  "branch": "North",
  "dutySubOrganization": "RYS34B",
  "forced": false
}
```

The Windows client saves `sessionId` from the `in` response and sends it back on `out`.

=== "curl with a client certificate"

    ```bash
    curl --cert user.crt --key user.key --cacert ca.crt \
      -H 'Content-Type: application/json' \
      -d '{"windowsUserId":"jdoe"}' \
      https://readyroom.example.com:8444/api/check/in
    ```

=== "curl, logout"

    ```bash
    curl --cacert ca.crt -H 'Content-Type: application/json' \
      -d '{"sessionId":"8f6c0f3e-5d1b-4b8e-9d7a-3c1e2f4a5b6c","windowsUserId":"jdoe"}' \
      https://readyroom.example.com:8444/api/check/out
    ```

=== "PowerShell with Windows sign-in"

    ```powershell
    Invoke-RestMethod -Method Post -Uri https://readyroom.example.com:8444/api/check/in `
      -ContentType application/json -Body '{"windowsUserId":"jdoe"}' `
      -UseDefaultCredentials -MaximumRedirection 0
    ```

### `GET /api/check/records`

The records for the day being viewed in the session (today unless the session date was changed),
paged. Any signed-in user can call it.

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
| `GET /api/users/search?search=&page=1&size=10&sort=id&dir=asc` | Signed in | Directory users whose `cn` contains `search` (all users when empty), with today's status. |
| `GET /api/users/usersearch?search=` | Signed in | Directory users whose `cn` contains `search`. Used by the manager pickers. |
| `GET /api/users/group/{groupDn}` | A/M, or a member of the group | `{"data": [...statuses]}` for the group's members. |
| `GET /api/users/reports` | Signed in | `{"data": [...]}`: the caller's direct reports, with status. |
| `GET /api/users/checkinoutrecords/{dn}?page=1&size=10&sort=timestamp&dir=desc` | A/M, the user themselves, or a manager of one of the user's groups | The user's records, paged, newest first. |
| `GET /api/users/managers/{dn}` | Signed in | The user's additional managers, as a list of objects with a `dn`. |
| `POST /api/users/managers/update` | Signed in | Body: a JSON array of DNs. Replaces the **caller's own** additional managers. |

### Groups and permissions

| Method and path | Access | Description |
|---|---|---|
| `POST /api/groups/favoriteGroup` | A/M | Body `{"groupDn": "...", "selected": true}`. Adds or removes a favorite for the caller. |
| `GET /api/groups/managers/{groupDn}` | A/M | The group's additional managers. |
| `POST /api/groups/managers/update/{groupDn}` | A/M | Body: a JSON array of DNs. Replaces the group's additional managers. |
| `GET /api/groups/hierarchy` | A/M | Returns nothing in the current version. |
| `POST /api/permissions/update` | A/M | Body `{"groupDn": "...", "userDn": "...", "selected": true}`. Grants or revokes a per-user group permission record. See the note in [Administration](admin-guide.md#groups-and-access). |

### Status calendar and organization calendar

| Method and path | Access | Description |
|---|---|---|
| `GET /api/event/all?dn=&from=&to=` | Signed in | A user's status entries between two ISO instants, as calendar events. |
| `GET /api/event/allWithLogins?dn=&from=&to=` | Signed in | The same, plus "Online: ..." periods and organization calendar events. |
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
| `GET /api/orgnodes/users/{orgName}` | Signed in | Directory users whose duty sub-organization attribute equals `orgName`, with status. |
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
| `/app/users/{groupDn}` | A/M, or a member of the group | Group members |
| `POST /app/group/report` | A/M | Group report (`groupDn`, `fromDate`, `toDate`) |
| `/app/user/search` | Signed in | User search |
| `/app/user/details/{dn}` | Signed in | A person's details. The audit log and calendar need A/M, self or group manager. |
| `POST /app/user/details` | ADMIN | Change a role (`dn`, `role`) |
| `/app/user/reports` | Signed in | My Reports |
| `/app/orgchart` | Signed in | Org chart |
| `/app/orgchart/details/{orgName}` | Signed in | Members of one organization. Not linked from the current UI. |
| `/app/metrics` | A/M | Metrics |
| `/app/profile` (GET, POST) | Signed in | My Profile (`notes`, `loginTime`) |
| `/app/notifications/id/{id}` | Signed in | One notification |
| `POST /app/notifications/update` | Recipient only | Respond to a notification (`notificationId`, `response`) |
| `/app/settings` | A/M | Settings |
| `POST /app/settings/orgparserules` | A/M | Save a parse rule (`id`, `orgName`, `orgParseRegex`) |
| `POST /app/settings/orgparserules/delete/{id}` | A/M | Delete a parse rule |
| `POST /app/settings/cache/groups/evict` | A/M | Evict a cached group (`dn`) |
| `POST /app/settings/cache/groups/evictall` | A/M | Evict every cached group |
| `/actuator/health`, `/info`, `/metrics`, `/env` | Open | Spring Boot Actuator |
