# API reference

Everything under `/api/v1` is both the API CertAlert's own pages use and its **public API** for
other systems. A script or integration calls the same endpoints with an **API token**; a browser
calls them with its session. The SIEM pull API for the audit trail (`/api/audit/events`) is separate
and has tokens of its own (see [Audit logging](../audit-logging.md#the-pull-api)).

## Public API

### The OpenAPI document

Every installation serves an OpenAPI 3.1 document at **`/api/v1/openapi.yaml`**, without signing in
(it describes the product, not your data). It lists every endpoint a token may call and the scope the
server enforces for it; a test in CertAlert's build holds the document to the code. Load it into
your API client or code generator rather than copying paths from this page.

### Calling the API with a token

```bash
curl -H "Authorization: Bearer cat_…" \
  "https://certalert.example.gov/api/v1/certificates?status=EXPIRING_SOON&expiresWithinDays=30&size=500"

curl -H "Authorization: Bearer cat_…" \
  "https://certalert.example.gov/api/v1/summary/expiry?ownerType=SERVER"

# Start a directory sync (needs jobs:run) and poll the run it returns
curl -X POST -H "Authorization: Bearer cat_…" https://certalert.example.gov/api/v1/sync
curl -H "Authorization: Bearer cat_…" https://certalert.example.gov/api/v1/jobs/<id>

# Download the filtered inventory as Excel (a POST, because every export is audited)
curl -X POST -H "Authorization: Bearer cat_…" -o inventory.xlsx \
  "https://certalert.example.gov/api/v1/certificates/export?format=xlsx&status=EXPIRED"
```

The read paths an integration usually wants:

| Method | Path | Scope | What |
|---|---|---|---|
| `GET` | `/api/v1/certificates` | `certificates:read` | Every certificate with its owner, filtered and paged (`?status=EXPIRING_SOON&expiresWithinDays=30&issuer=DigiCert&keyAlgorithm=RSA&keySizeMax=2047&hashAlgorithm=SHA-1&ownerType=SERVER&q=web&page=0&size=100&sort=notAfter,asc`) |
| `GET` | `/api/v1/certificates/{id}` | `certificates:read` | One certificate |
| `POST` | `/api/v1/certificates/export?format=csv\|xlsx` | `certificates:read` | The same, as a file; see [Exports](exports-and-reports.md) |
| `GET` | `/api/v1/summary/expiry` | `certificates:read` | Counts by state, expiring within 7/30/60/90 days, revoked, by owner type; takes the same filters |
| `GET` | `/api/v1/servers`, `/api/v1/servers/{id}` | `certificates:read` | Servers, `?q=&certificateStatus=` |
| `GET` | `/api/v1/people`, `/api/v1/people/{id}` | `people:read` | People, `?q=&certificateStatus=` |
| `GET` | `/api/v1/jobs`, `/api/v1/jobs/{id}` | `jobs:read` | Job runs |
| `POST` | `/api/v1/sync`, `/api/v1/revocation/check`, `/api/v1/notifications/digest`, ... | `jobs:run` | Start a job: `202` and a run to poll (see [Sync and jobs](#sync-and-jobs)) |

Lists answer `{"content":[...],"page":0,"size":100,"totalElements":…,"totalPages":…}`. `size` is
capped at `cert-alert.api.max-page-size` (500).

### API tokens

Administrators create and revoke tokens on **Admin > Integrations** (`/admin/integrations`);
auditors can see the list.

- **Created with** a name, one or more scopes and, optionally, an expiry date. The token (`cat_`
  and 43 characters) is shown **once**, in the response that creates it. CertAlert keeps only its
  SHA-256; the token is never written to a log, the audit trail or the session.
- **Never more than its creator, now.** Each scope needs a role: reads need a signed-in user,
  `jobs:read` an auditor or administrator, the rest an administrator. Only the roles **the
  directory** gives the creator count (`admin-identifiers`, `auditor-identifiers` or a directory
  group in `security.groups`), never an identity provider's groups, so an administrator by IdP
  group alone can create read-only tokens only. On **every use** the creator's entry is read from
  the live directory: an entry that is gone, disabled or locked, or a directory that cannot be read,
  refuses the token (`401`); otherwise the token does no more than the creator's roles allow today.
  A good answer is reused for at most `cert-alert.api.creator-roles-cache-for` (a minute); a refusal
  never is.
- **Disabled or locked** means `nsAccountLock: true`; Active Directory's ACCOUNTDISABLE flag in
  `userAccountControl`; or a lockout (AD `msDS-User-Account-Control-Computed` or `lockoutTime`,
  password-policy `pwdAccountLockedTime`, 389 `accountUnlockTime`). Lockouts **fail closed**: a lockout
  counts as lapsed only where a known, non-zero lockout length shows it has. Where the length cannot
  be found (for example OpenLDAP with `olcPPolicyDefault` and no `pwdPolicySubentry`), the account is
  locked unless `cert-alert.ldap.lockout.assumed-duration` is set to the real lockout length.
- **The service account must be able to read** people's entries and the lock attributes
  (`nsAccountLock`, `userAccountControl`, `msDS-User-Account-Control-Computed`, `lockoutTime`,
  `pwdAccountLockedTime`, `pwdPolicySubentry`, `accountUnlockTime`, and `memberOf` where groups grant
  roles) and the lockout policy (`pwdLockoutDuration`, AD's domain `lockoutDuration`). A refused read
  refuses the token.
- **A year at most.** `cert-alert.api.max-lifetime` (`365d`) is the expiry of a token created without
  one, the most that may be asked for, and the age at which any token stops working; `0` removes it.
- **Refused** (`401`, `WWW-Authenticate: Bearer error="invalid_token"`) when unknown, revoked, past
  its expiry, out-ranked, or when `cert-alert.api.tokens-enabled` is `false`.
- **Out of scope** (`403`, `error="insufficient_scope"`) for an endpoint its scopes do not cover. No
  token, whatever its scopes, can create or revoke tokens, configure webhooks, read a person's
  notifications or use the DataTables endpoints: those stay with people.
- **Rate-limited** per token, **per replica**: `cert-alert.api.requests-per-minute` (300); beyond it,
  `429` with `Retry-After`.
- **No session, no CSRF.** A request with `Authorization: Bearer` on `/api/v1/**` goes through a
  filter chain of its own that keeps and reads no session, and has no CSRF check. The same endpoints
  called with a session cookie still need the CSRF token. A proxy or WAF that strips
  `Authorization` must let it through for the API to work.
- **Audited**: `api.token-created`, `api.token-revoked`, `api.token-refused` (at most once a minute
  per token, or per client address for a value that is no token at all), `api.request` for every
  request that changes something, and `api.token-used` once per token per
  `cert-alert.api.use-audit-interval` (an hour) for its reads. The actor is `api-token:<id>`.

### Scopes

| Scope | Needs the creator to be | Allows |
|---|---|---|
| `certificates:read` | signed in | The certificate inventory and its export, expiry summaries, servers and their certificates, contacts and attributes, projects, revocation counts, server stats |
| `people:read` | signed in | People, their certificates and addresses, people stats |
| `jobs:read` | auditor or administrator | Job runs, sync history, changelog status |
| `jobs:run` | administrator | Start the sweeps, refresh, changelog poll, revocation check and round-up (implies `jobs:read`) |
| `inventory:write` | administrator | Points of contact, projects and their members, server attribute values |
| `admin` | administrator | Everything above, plus pruning, certificate clean-up, project administrators, the endpoint probe, the audit trail and its export, attribute definitions and the license |

The full list of which method and path needs which scope is in the OpenAPI document.

### Token settings

| Property (`cert-alert.api.`) | Env var | Helm | Default | Description |
|---|---|---|---|---|
| `tokens-enabled` | `CERT_ALERT_API_TOKENS_ENABLED` | `api.tokensEnabled` | `true` | `false` refuses every token (`401`) without deleting any |
| `requests-per-minute` | `CERT_ALERT_API_REQUESTS_PER_MINUTE` | `api.requestsPerMinute` | `300` | Per token, per replica; `0` turns the limit off |
| `max-lifetime` | `CERT_ALERT_API_MAX_LIFETIME` | `api.maxLifetime` | `365d` | The longest a token lives; `0` removes the limit |
| `use-audit-interval` | | | `1h` | A token's reads are audited once per interval |
| `max-page-size` | | | `500` | The largest `size` a list returns |
| `creator-roles-cache-for` | | | `1m` | How long a creator's directory check is reused |

### Token administration

| Method | Path | Access | Purpose |
|---|---|---|---|
| `GET` | `/api/v1/admin/api-tokens` | admin, auditor | The tokens, without their values |
| `GET` | `/api/v1/admin/api-tokens/scopes` | admin, auditor | The scopes, what each allows and the role it needs |
| `POST` | `/api/v1/admin/api-tokens` | admin (session only) | Create: `{"name", "scopes": [...], "expiresAt"}`; the answer holds the token, once |
| `DELETE` | `/api/v1/admin/api-tokens/{id}` | admin (session only) | Revoke |

## Conventions

- **Authentication**: an API token (above), or a session established by a client certificate,
  the `/login` form or [single sign-on](single-sign-on.md). There is no HTTP Basic. With the consent
  banner on, a session that has not agreed to it gets `401` from `/api/**`.
- **Sessions** end after the idle or absolute limit; a call in an ended session gets `401`.
- **CSRF**: with a session, `POST`, `PUT` and `DELETE` require Spring Security's CSRF token in the
  header named by the page's `_csrf_header` meta tag (value in `_csrf`). Token calls need none.
- **Auditors**: a session with `ROLE_AUDITOR` is refused (`403`) on every `POST`, `PUT` and `DELETE`
  except the DataTables queries, audit-chain verification and their own saved views. See
  [Single sign-on and roles](single-sign-on.md#the-auditor-changes-nothing).
- **Unauthenticated** API calls get `401`; **forbidden** ones get `403`.
- **Errors** are RFC 7807 problem details (`application/problem+json`) with a `detail` message.
- **Null fields are omitted** from JSON responses.
- **Access** column: *user* = anyone signed in; *admin* = `ROLE_ADMIN`; *auditor* =
  `ROLE_AUDITOR`; *editors* = `cert-alert.security.contact-editors` (admins by default, or anyone
  signed in); *server* = a point of contact for that server, a project administrator of a project
  containing it, or an admin (or anyone when `contact-editors: AUTHENTICATED`).

## Certificate inventory, saved views and reports

| Method | Path | Access | Purpose |
|---|---|---|---|
| `GET` | `/api/v1/certificates`, `/api/v1/certificates/{id}` | user | The inventory, filtered and paged, or one certificate |
| `POST` | `/api/v1/certificates/export?format=csv\|xlsx` | user | Download the filtered inventory; audited as `inventory.exported` |
| `GET` | `/api/v1/summary/expiry` | user | Counts over the same filters |
| `GET` | `/api/v1/servers`, `/api/v1/servers/{id}` | user | Servers with their certificate state |
| `GET` | `/api/v1/people`, `/api/v1/people/{id}` | user | People with their certificate state |
| `GET`, `POST` | `/api/v1/saved-views` | user (auditors too) | Your saved views; save one (`{"page", "name", "query"}`, the query string of the filters; the same name replaces it) |
| `DELETE` | `/api/v1/saved-views/{id}` | user (auditors too) | Delete one of yours |
| `POST` | `/api/v1/servers/contacts/bulk` | user, checked per server | `{"serverIds": [...], "userId": 42}` or `{"serverIds": [...], "email": "..."}`: add one point of contact to many servers. Answers `added`, `alreadyContact`, `refused` and `notFound` |
| `GET` | `/api/v1/admin/reports` | admin, auditor | Scheduled reports |
| `POST` | `/api/v1/admin/reports` | admin | Create one |
| `PUT` | `/api/v1/admin/reports/{id}/enabled` | admin | `{"enabled": false}` pauses, `true` resumes |
| `POST` | `/api/v1/admin/reports/{id}/send` | admin | Send now |
| `DELETE` | `/api/v1/admin/reports/{id}` | admin | Delete |

See [Exports, saved views and scheduled reports](exports-and-reports.md).

## Webhooks

| Method | Path | Access | Purpose |
|---|---|---|---|
| `GET` | `/api/v1/admin/webhooks` | admin, auditor | Webhooks (auditors see each URL's scheme and host only) |
| `GET` | `/api/v1/admin/webhooks/events` | admin, auditor | The events a webhook can subscribe to |
| `POST` | `/api/v1/admin/webhooks` | admin (session only) | Add one; the answer holds its signing secret, once |
| `PUT` | `/api/v1/admin/webhooks/{id}` | admin (session only) | Change one |
| `POST` | `/api/v1/admin/webhooks/{id}/secret` | admin (session only) | New signing secret, shown once |
| `POST` | `/api/v1/admin/webhooks/{id}/test` | admin (session only) | Queue a `webhook.test` event |
| `DELETE` | `/api/v1/admin/webhooks/{id}` | admin (session only) | Delete |
| `GET` | `/api/v1/admin/webhooks/{id}/deliveries` | admin, auditor | The delivery log |

See [Webhooks](webhooks.md) for events, signatures and the address rules.

## Sync and jobs

| Method | Path | Access | Purpose |
|---|---|---|---|
| `POST` | `/api/v1/sync` | admin | Run both sweeps (users, then servers); the run's `result` is a list of results |
| `POST` | `/api/v1/sync/users` | admin | Sweep IC Persons |
| `POST` | `/api/v1/sync/servers` | admin | Sweep IC Non-Person Entities |
| `POST` | `/api/v1/sync/refresh` | admin | Re-evaluate cached expiry (no LDAP) |
| `POST` | `/api/v1/sync/prune` | admin | Remove entries unseen past `prune.after` (**not** gated by `prune.enabled`) |
| `GET` | `/api/v1/sync/prune/preview` | user | `{"prunable": N}` |
| `POST` | `/api/v1/sync/certificate-cleanup` | admin | Delete finished certificates from the directory (no-op unless enabled) |
| `GET` | `/api/v1/sync/certificate-cleanup/preview` | user | What that would delete |
| `GET` | `/api/v1/sync/runs?limit=20` | user | Run log, newest first (`limit` 1–100) |
| `GET` | `/api/v1/changelog` | user | Connector status |
| `POST` | `/api/v1/changelog/poll` | admin | Read one changelog batch now; the run's `result` is the status |
| `GET` | `/api/v1/jobs/{id}` | admin, auditor | A job started by hand: `state` and, once finished, `result` or `error` |
| `GET` | `/api/v1/jobs` | admin, auditor | Recent runs started by hand |

The `POST` job endpoints (and `POST /api/v1/notifications/digest` without `dryRun`) answer
**202 Accepted** at once with a run, and the job runs in the background:

```json
{"id":"7c0e…","job":"directory sync","state":"QUEUED","finished":false,
 "requestedAt":"…","statusUrl":"/api/v1/jobs/7c0e…"}
```

`GET /api/v1/jobs/{id}` (also in the `Location` header) gives `state`: `QUEUED`, `RUNNING`,
`SUCCEEDED` with `result` (what the endpoint answered before this release), `FAILED` with
`error`, or `SKIPPED` when the same job was already running, scheduled or by hand, on this replica
or another. Runs are kept in `job_run` for `cert-alert.cluster.run-retention` (7 days), so any
replica can answer; a run whose replica stops is marked `FAILED` within
`cert-alert.cluster.stale-run-after` (5 minutes). A script that read the counts from the `POST`
response must now poll `statusUrl` and read `result`.

Sync result (`SyncResponse`, a run's `result`):

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
| `GET` | `/api/v1/audit/summary` | admin, auditor | Counts (total, last day, last week, distinct actors) and span |

DataTables audit filters: `action` (one or more audit kinds), `actor`, `subject` (name or DN),
`from`, `to` (ISO dates).

The `ens-audit/1` trail, behind the **Audit log** page:

| Method | Path | Access | Purpose |
|---|---|---|---|
| `GET` | `/api/v1/admin/audit/events` | admin, auditor | Search the trail (paged, filtered) |
| `GET` | `/api/v1/admin/audit/events/{seq}` | admin, auditor | One event in full |
| `GET` | `/api/v1/admin/audit/export` | admin, auditor | The current filter as CSV or NDJSON, up to 100,000 events; recorded as `audit.exported` |
| `POST` | `/api/v1/admin/audit/verify` | admin, auditor | Recompute the hash chain |
| `GET` | `/api/v1/admin/audit/delivery` | admin, auditor | Each output's delivery position, backlog and last error |
| `GET`, `POST`, `DELETE` | `/api/v1/admin/audit/tokens`, `/api/v1/admin/audit/tokens/{id}` | admin (auditors may list) | List, create and revoke SIEM pull-API tokens (`ensat_`), which are separate from API tokens |
| `GET` | `/api/audit/events?after=<seq>&limit=<1-1000>` | bearer token | The SIEM pull API: NDJSON, or `{"events":[…],"next":<seq>}` with `Accept: application/json`. Works while unlicensed. See [Audit logging](../audit-logging.md#the-pull-api) |

## Sign-in, consent and health

| Path | Access |
|---|---|
| `/login` (`GET`, `POST`) | public: the password form |
| `/oauth2/authorization/oidc`, `/login/oauth2/code/oidc` | public, when OIDC is on: start a single sign-on and its redirect URI |
| `/saml2/authenticate/saml`, `/login/saml2/sso/saml`, `/saml2/service-provider-metadata/saml` | public, when SAML is on: start a single sign-on, the assertion consumer and the service provider's metadata |
| `/api/v1/openapi.yaml` | public: the OpenAPI document |
| `/consent` (`GET`, `POST`) | public: the notice-and-consent banner and **I agree**, when it is on |
| `/livez`, `/readyz` | public, always on the application's port |
| `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness`, `/actuator/info` | public (health details only for admins) |
| `/actuator/metrics`, `/actuator/loggers` | admin |
| `/actuator/prometheus` | public, on the management port (`MANAGEMENT_SERVER_PORT`) only |

With `MANAGEMENT_SERVER_PORT` set, the `/actuator/**` paths move to that port.
