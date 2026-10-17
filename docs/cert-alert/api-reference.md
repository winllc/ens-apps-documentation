# API reference

CertAlert's UI is built on a JSON API under `/api/v1`. The same endpoints can be used by scripts
and integrations.

## Conventions

- **Authentication**: a session established by a client certificate or by the `/login` form (see
  [Security](security.md#authentication)). There is no token or HTTP Basic authentication.
- **CSRF**: `POST`, `PUT` and `DELETE` require Spring Security's CSRF token in the header named by
  the page's `_csrf_header` meta tag (value in `_csrf`).
- **Unauthenticated** API calls get `401`; **forbidden** ones get `403`.
- **Errors** are RFC 7807 problem details (`application/problem+json`) with a `detail` message.
- **Null fields are omitted** from JSON responses.
- **Access** column: *user* = anyone signed in; *admin* = `ROLE_ADMIN`; *editors* =
  `cert-alert.security.contact-editors` (admins by default, or anyone signed in); *server* = a
  point of contact for that server, a project administrator of a project containing it, or an
  admin (or anyone when `contact-editors: AUTHENTICATED`).

## Sync and jobs

| Method | Path | Access | Purpose |
|---|---|---|---|
| `POST` | `/api/v1/sync` | admin | Run both sweeps (users, then servers); returns a list of results |
| `POST` | `/api/v1/sync/users` | admin | Sweep IC Persons |
| `POST` | `/api/v1/sync/servers` | admin | Sweep IC Non-Person Entities |
| `POST` | `/api/v1/sync/refresh` | admin | Re-evaluate cached expiry (no LDAP) |
| `POST` | `/api/v1/sync/prune` | admin | Remove entries unseen past `prune.after` (**not** gated by `prune.enabled`) |
| `GET` | `/api/v1/sync/prune/preview` | user | `{"prunable": N}` |
| `POST` | `/api/v1/sync/certificate-cleanup` | admin | Delete finished certificates from the directory (no-op unless enabled) |
| `GET` | `/api/v1/sync/certificate-cleanup/preview` | user | What that would delete |
| `GET` | `/api/v1/sync/runs?limit=20` | user | Run log, newest first (`limit` 1–100) |
| `GET` | `/api/v1/changelog` | user | Connector status |
| `POST` | `/api/v1/changelog/poll` | admin | Read one changelog batch now; returns status |

Sync result (`SyncResponse`):

```json
{
  "job": "USERS",
  "entriesSeen": 104, "entriesCreated": 3,
  "certificatesCached": 7, "certificatesRemoved": 1,
  "alertsRaised": 2, "entriesPruned": 0,
  "errors": 0, "durationMillis": 1840
}
```

Run log rows add `id`, `status` (`RUNNING`, `COMPLETED`, `COMPLETED_WITH_ERRORS`, `FAILED`),
`startedAt`, `finishedAt` and `message`. `job` is one of `USERS`, `SERVERS`, `REFRESH`, `PRUNE`,
`REVOCATION`, `CERTIFICATE_CLEANUP`.

Changelog status fields: `enabled`, `running`, `lastChangeNumber`, `firstAvailableNumber`,
`lastAvailableNumber`, `lag`, `changesApplied`, `changesIgnored`, `gapsDetected`, `errors`,
`startedAt`, `lastPolledAt`, `lastChangeAt`, `lastError`.

## Directory data

| Method | Path | Access | Purpose |
|---|---|---|---|
| `GET` | `/api/v1/stats/users` | user | `{"total": N, "byStatus": {"NONE":…, "VALID":…, "EXPIRING_SOON":…, "EXPIRED":…}}` |
| `GET` | `/api/v1/stats/servers` | user | Same, for servers |
| `GET` | `/api/v1/users/{id}/certificates` | user | Cached certificate detail for a person |
| `GET` | `/api/v1/servers/{id}/certificates` | user | Cached certificate detail for a server |
| `GET` | `/api/v1/users/search?q=` | user | People matching, for pickers |
| `GET` | `/api/v1/servers/search?q=` | user | Servers matching, for pickers |
| `GET` | `/api/v1/metrics` | user | Everything the Metrics page shows |

### DataTables endpoints

`POST /api/v1/datatables/users` and `POST /api/v1/datatables/servers` take a DataTables request
body (paging, ordering, global and column search) and these query parameters:

| Parameter | Users | Servers | Meaning |
|---|:-:|:-:|---|
| `certificateStatus` | ✓ | ✓ | One or more of `NONE`, `VALID`, `EXPIRING_SOON`, `EXPIRED` |
| `expired` | ✓ | ✓ | Has (`true`) / has no (`false`) expired certificate |
| `hideExpired` | ✓ | ✓ | Hide entries whose certificates have all lapsed |
| `hasCertificates` | ✓ | ✓ | Publishes any certificate |
| `expiringWithinDays` | ✓ | ✓ | Next certificate expires within N days |
| `latestExpiryFrom`, `latestExpiryTo` | ✓ | ✓ | ISO dates bounding the last certificate's expiry |
| `poc` | ✓ | ✓ | Point-of-contact text (partial match) |
| `pocEmail` | | ✓ | Literal point-of-contact value |
| `pocUserId` | | ✓ | Servers this person is a point of contact for |
| `risk` | ✓ | ✓ | `any`, or `WILDCARD`, `BROAD_WILDCARD`, `MANY_NAMES`, `MANY_DOMAINS`, `BARE_HOSTNAME` |
| `projectId` | ✓ | ✓ | Members of a project |

## Endpoint probe

| Method | Path | Access | Purpose |
|---|---|---|---|
| `POST` | `/api/v1/servers/{id}/probe?port=` | server | TLS-connect to the server's host and compare certificates |

Response fields: `host`, `port`, `reachable`, `error`, `protocol`, `cipherSuite`, `elapsedMillis`,
`presented`, `chain`, `expected`, `findings[]` (`name`, `label`, `severity`, `problem`),
`problem`, `checkedAt`. Refused when `cert-alert.probe.enabled` is `false`.

## Revocation

| Method | Path | Access | Purpose |
|---|---|---|---|
| `GET` | `/api/v1/revocation` | user | Counts and whether OCSP is possible here |
| `POST` | `/api/v1/revocation/check` | admin | Run the revocation check now |

## Points of contact and addresses

| Method | Path | Access | Purpose |
|---|---|---|---|
| `GET` | `/api/v1/servers/{id}/contacts` | user | Directory `serverPOC` values and contacts added here, plus whether the caller may edit |
| `POST` | `/api/v1/servers/{id}/contacts` | server | Add `{"userId": 42}` **or** `{"email": "team@example.gov"}` (exactly one) |
| `DELETE` | `/api/v1/servers/{id}/contacts/{contactId}` | server | Remove an added contact |
| `GET` | `/api/v1/users/{id}/addresses` | user | Directory addresses and addresses added here |
| `POST` | `/api/v1/users/{id}/addresses` | editors | Add `{"address": "...", "kind": "PERSONAL"|"GROUP", "label": "..."}` (`kind` defaults to `PERSONAL`). `400` when the address is named in `cert-alert.security.admin-identifiers` |
| `DELETE` | `/api/v1/users/{id}/addresses/{aliasId}` | editors | Remove one |

## Projects

| Method | Path | Access | Purpose |
|---|---|---|---|
| `GET` | `/api/v1/projects` | user | Every project with people/server counts |
| `POST` | `/api/v1/projects` | editors | Create: `{"name": "...", "description": "..."}` (name ≤ 255, description ≤ 2000) |
| `PUT` | `/api/v1/projects/{id}` | editors | Rename / redescribe |
| `DELETE` | `/api/v1/projects/{id}` | editors | Delete the grouping only |
| `POST` / `DELETE` | `/api/v1/projects/{id}/users/{userId}` | editors | Add / remove a person |
| `POST` / `DELETE` | `/api/v1/projects/{id}/servers/{serverId}` | editors | Add / remove a server |
| `POST` / `DELETE` | `/api/v1/projects/{id}/admins/{userId}` | admin | Grant / revoke project administrator |

## Editable server attributes

| Method | Path | Access | Purpose |
|---|---|---|---|
| `GET` | `/api/v1/admin/server-attributes` | admin | Definitions |
| `POST` | `/api/v1/admin/server-attributes` | admin | Define one (201) |
| `PUT` | `/api/v1/admin/server-attributes/{id}` | admin | Change a definition |
| `DELETE` | `/api/v1/admin/server-attributes/{id}` | admin | Stop offering it (204); directory values untouched |
| `GET` | `/api/v1/servers/{id}/attributes` | user | Values this entry holds, read from the directory |
| `PUT` | `/api/v1/servers/{id}/attributes/{definitionId}` | admin | Write values to the directory entry |

Definition body:

```json
{
  "ldapAttribute": "ATOStatus",
  "name": "ATO status",
  "description": "shown under the control",
  "type": "CHOICE",
  "multiValued": false,
  "options": ["Production", "Staging", "Development"],
  "displayOrder": 1
}
```

`type` is `TEXT`, `CHOICE` or `BOOLEAN`.

## Notifications

| Method | Path | Access | Purpose |
|---|---|---|---|
| `GET` | `/api/v1/notifications?page=&size=` | user | Your notifications |
| `GET` | `/api/v1/notifications/unread-count` | user | What the bell shows |
| `POST` | `/api/v1/notifications/{id}/read` | user | Mark one read |
| `POST` | `/api/v1/notifications/read-all` | user | Mark all read |
| `POST` | `/api/v1/notifications/digest[?dryRun=true]` | admin | Run the round-up now, or rehearse it |
| `GET` | `/api/v1/notifications/settings` | user | Lead days and who set them |
| `PUT` | `/api/v1/notifications/settings` | admin | `{"leadDays": 45}` (1–365) |

Settings response: `leadDays`, `configuredLeadDays`, `fromConfiguration`, `minimumLeadDays`,
`maximumLeadDays`, `updatedAt`, `updatedBy`, `emailEnabled`, `editable`.

The round-up (real or dry run) returns `certificates`, `peopleTold`, `emailsSent`, `dryRun`,
`emailEnabled`, `note` (why nothing would be sent, where that applies), `failures` (messages that
could not be built) and `messages` (rendered messages, up to 25 on a dry run).

## Audit

| Method | Path | Access | Purpose |
|---|---|---|---|
| `GET` | `/api/v1/users/{id}/audit?page=&size=` | user | A person's history |
| `GET` | `/api/v1/servers/{id}/audit?page=&size=` | user | A server's history |
| `POST` | `/api/v1/datatables/audit?subjectType=&subjectId=` | user | One entry's history as a DataTables table (`subjectType` is `USER` or `SERVER`) |
| `POST` | `/api/v1/datatables/audit` (no subject) | admin | The whole trail |
| `GET` | `/api/v1/audit/summary` | admin | Counts (total, last day, last week, distinct actors) and span |

DataTables audit filters: `action` (one or more audit kinds), `actor`, `subject` (name or DN),
`from`, `to` (ISO dates).

## Actuator

| Path | Access |
|---|---|
| `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness`, `/actuator/info` | public (health details only for admins) |
| `/actuator/metrics`, `/actuator/loggers` | admin |
