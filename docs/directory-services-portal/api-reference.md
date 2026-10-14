# API reference

The API is served under `/api` by the Spring Boot backend. The web client is its only built-in consumer,
but scripts can call it too. Behind the bundled nginx, use the portal's own origin
(`https://portal.example.com/api/...`).

## Conventions

| Topic | Rule |
|---|---|
| Format | JSON request and response bodies |
| Authentication | Session cookie `DSP_SESSION`, obtained from `POST /api/auth/login` or `POST /api/auth/x509` |
| CSRF | Every POST, PUT and DELETE must send `X-DSP-Request: 1` (otherwise 403) |
| Admin | `/api/admin/**` requires an administrator (otherwise 403 *Administrator access required*) |
| Errors | `{"error": "message"}`, with field details for validation errors (400 *Invalid request*) |
| Status codes | 200, 201 (created), 202 (background job), 204 (deleted), 400, 401 (*Authentication required*), 403, 404, 405, 409 (conflict, e.g. form in use), 429 (throttled / too many tasks), 500 |
| Background work | `202 {"pending": true, "job": {...}}`; follow it with `/api/jobs/{id}` and `/api/jobs/{id}/result` |
| DN parameters | Entries are addressed with a `dn` query parameter (URL-encoded) |

```bash
# Sign in and list the directories (curl example)
curl -c jar -H 'Content-Type: application/json' -H 'X-DSP-Request: 1' \
     -d '{"username":"alice","password":"password"}' https://portal.example.com/api/auth/login
curl -b jar https://portal.example.com/api/definitions
```

## Public

| Method | Path | Description |
|---|---|---|
| GET | `/api/health` | Liveness: `{"ok": true}` |
| GET | `/api/info` | `mode`, `ldapUrl`, `baseDn`, `connected`, `error`, `version`, `auth` (`password`, `x509`, `x509AutoLogin`), `loginHint`, `banner` |

## Authentication

| Method | Path | Body / params | Description |
|---|---|---|---|
| POST | `/api/auth/login` | `{"username", "password"}` | Password sign-in. Returns `{"user": SessionUser}`. 401 for bad credentials, 429 when throttled, 403 when password sign-in is disabled |
| POST | `/api/auth/x509` | — | Sign in with the presented client certificate |
| POST | `/api/auth/logout` | — | Sign out and invalidate the session |
| GET | `/api/auth/me` | — | `{"user": SessionUser or null}`. With X.509 auto-login enabled, it may sign the user in, or return `certificateError` |

`SessionUser` contains `username`, `dn`, `displayName`, `mail`, `groups`, `isAdmin`, `authMethod`
(`password` or `x509`) and `certificateSubject` (certificate sign-ins only).

## Directories and entries

| Method | Path | Params / body | Description |
|---|---|---|---|
| GET | `/api/definitions` | — | Definitions the user can access, with effective access |
| GET | `/api/definitions/{id}` | — | `{"definition", "form"}`; 403 without access |
| GET | `/api/directories/{id}/entries` | `q` (≤ 256 characters), `page` (1–10000), `pageSize` (1–200), `sort` (attribute), `order` (`asc`/`desc`) | List and search entries |
| GET | `/api/directories/{id}/entry` | `dn` | One entry |
| POST | `/api/directories/{id}/entries` | `{"parentDn", "values": {attr: value or [values]}}` | Create; returns 201 |
| PUT | `/api/directories/{id}/entry` | `dn`; `{"values": {...}}` | Update (and rename when the naming value changes) |
| DELETE | `/api/directories/{id}/entry` | `dn` | Delete; returns 204 |
| GET | `/api/directories/{id}/options/{fieldId}` | `q` (typed text) or repeated `value` (labels for stored values, ≤ 200) | Drop-down options |
| GET | `/api/me/entries` | — | **My profile**: per self-matching definition, the user's entries and self-editable attributes |
| PUT | `/api/me/{id}/entry` | `dn`; `{"values": {...}}` | Self-service update of the user's own entry |

## Metrics

| Method | Path | Params / body | Description |
|---|---|---|---|
| GET | `/api/metrics/sources` | — | Readable definitions and the attributes the user may chart |
| GET | `/api/metrics/summary` | `refresh` | Entry count per readable directory (may return 202) |
| GET | `/api/metrics/overview/{id}` | `refresh` | Generated overview for a definition (may return 202) |
| POST | `/api/metrics/evaluate` | `{"widgets": [MetricWidget, ≤ 48], "refresh", "returnTo"}` | Evaluate ad-hoc widgets (explorer, dashboard editor); may return 202 |
| GET | `/api/metrics/dashboards` | — | Dashboards visible to the user |
| GET | `/api/metrics/dashboards/{id}` | — | One dashboard |
| GET | `/api/metrics/dashboards/{id}/results` | `refresh` | Computed results (may return 202) |

`MetricWidget` fields: `title`, `description`, `definitionId`, `filter`, `kind`
(`count|distribution|completeness|timeline|numeric`), `attribute`, `transform`
(`none|lowercase|uppercase|emailDomain|dnRdn|dnParent|prefix|regex|year|quarter|month|week|day`),
`transformArg`, `splitBy`, `splitTransform`, `attributes`, `limit` (1–100), `includeMissing`, `sort`
(`count|label`), `bucket` (`day|week|month|quarter|year`), `cumulative`, `measure`
(`values|valueCount`), `bins` (2–50), `chart` (`stat|bar|column|stacked|donut|line|area|table`),
`width` (`third|half|two-thirds|full`).

## Background jobs

| Method | Path | Description |
|---|---|---|
| GET | `/api/jobs` | The user's recent jobs |
| GET | `/api/jobs/{id}` | One job (status, progress) |
| GET | `/api/jobs/{id}/result` | `{"job", "result"}` |
| POST | `/api/jobs/{id}/cancel` | Cancel a queued or running job |
| POST | `/api/jobs/seen` | `{"ids": [...]}`: mark notifications as seen |
| DELETE | `/api/jobs/{id}` | Remove a job from the list |

## Administration (`ROLE_ADMIN`)

### Schema

| Method | Path | Description |
|---|---|---|
| GET | `/api/admin/schema` | Merged schema (server + custom) |
| POST | `/api/admin/schema/refresh` | Pull the schema from the directory |
| POST | `/api/admin/schema/import` | `{"text"}` (≤ 2,000,000 characters): import schema definitions |
| GET | `/api/admin/schema/export` | Custom definitions as a `.schema` file |
| PUT | `/api/admin/schema/attribute-types` | `{"definition", "originalOid"}`: save a custom attribute type |
| DELETE | `/api/admin/schema/attribute-types/{oid}` | Delete a custom attribute type |
| PUT | `/api/admin/schema/object-classes` | `{"definition", "originalOid"}`: save a custom object class |
| DELETE | `/api/admin/schema/object-classes/{oid}` | Delete a custom object class |

### Forms

| Method | Path | Description |
|---|---|---|
| GET | `/api/admin/forms` | List forms |
| GET | `/api/admin/forms/{id}` | One form |
| POST | `/api/admin/forms` | Create; returns `{"form", "warnings"}` |
| PUT | `/api/admin/forms/{id}` | Update; returns `{"form", "warnings"}` |
| DELETE | `/api/admin/forms/{id}` | Delete (409 while a definition uses it) |
| POST | `/api/admin/forms/lint` | Check a form without saving: `{"errors", "warnings"}` |
| POST | `/api/admin/forms/preview-options` | `{"source", "q"}`: **Test search** for a directory-search drop-down (options, truncated, filter, duration, error) |

### Directory definitions and directory tools

| Method | Path | Description |
|---|---|---|
| GET | `/api/admin/definitions` | List definitions |
| POST | `/api/admin/definitions` | Create |
| PUT | `/api/admin/definitions/{id}` | Update |
| DELETE | `/api/admin/definitions/{id}` | Delete (also removes its grants) |
| POST | `/api/admin/preview` | `{"baseDn", "scope": "base|one|sub", "filter", "attributes"}`: **Test namespace** |
| GET | `/api/admin/browse` | `base`: child entries for the DN browser |

### Permissions

| Method | Path | Description |
|---|---|---|
| GET | `/api/admin/grants` | `definitionId` (optional): list grants |
| POST | `/api/admin/grants` | `{"definitionId", "subjectType": "user|group", "subject", "subjectLabel", "access": "read|write"}`: create or replace |
| DELETE | `/api/admin/grants/{id}` | Revoke |
| GET | `/api/admin/subjects` | `q`, `type` (`user`/`group`): search users and groups (up to 25 users) |

### Dashboards

| Method | Path | Description |
|---|---|---|
| POST | `/api/admin/dashboards` | `{"name", "description", "shared", "widgets"}` (≤ 48 widgets) |
| PUT | `/api/admin/dashboards/{id}` | Update |
| DELETE | `/api/admin/dashboards/{id}` | Delete |

### Data analysis

| Method | Path | Description |
|---|---|---|
| GET | `/api/admin/analysis` | List analyses |
| GET | `/api/admin/analysis/{id}` | One analysis |
| POST | `/api/admin/analysis` | Create |
| PUT | `/api/admin/analysis/{id}` | Update |
| DELETE | `/api/admin/analysis/{id}` | Delete |
| POST | `/api/admin/analysis/run` | Run an unsaved draft (202 + job) |
| POST | `/api/admin/analysis/{id}/run` | Run a saved analysis (202 + job; joins a running one) |
| GET | `/api/admin/analysis/{id}/result` | Latest stored result: `{"result", "ranBy", "stale"}` |
| GET | `/api/admin/analysis/{id}/findings` | `q`, `rule`, `severity`, `source`, `status`, `page`, `pageSize`: paged findings |
| GET | `/api/admin/analysis/{id}/findings.csv` | Same filters, every match as CSV (audited) |
| GET | `/api/admin/analysis/results/{jobId}/findings` | Findings of a draft run |
| GET | `/api/admin/analysis/results/{jobId}/findings.csv` | CSV of a draft run (audited) |

Analysis body: `name`, `description`, `type` (`compare` or `quality`), `sources` (1–6 entries of
`{definitionId, filter, label, keyAttribute}`), `compare` (`attributes` [`{label, attributes}`],
`ignoreCase`, `normalizeWhitespace`, `ignoreOrder`, `keyIgnoreCase`), and `quality` (`formRules`,
`rules` [`{attribute, type, pattern, format, values, min, max, scope, ignoreCase, severity,
description}`]).

### Audit log

| Method | Path | Params | Description |
|---|---|---|---|
| GET | `/api/admin/audit` | `q`, `action`, `outcome`, `actor`, `target`, `definitionId`, `from`, `to` (`YYYY-MM-DD` or ISO instant), `page` (1–10000), `pageSize` (1–200, default 50) | Search the in-memory window of recent events |
