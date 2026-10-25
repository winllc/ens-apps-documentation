# API reference

The portal has two HTTP APIs, both behind the web tier on the portal's own origin
(`https://portal.example.com/api/...`):

- the **integration API** under **`/api/v1`**, for other systems: versioned, described by an OpenAPI
  document, and called with an **API token**;
- the **portal API** under the rest of `/api`, which the web client uses with a signed-in session.
  Scripts can call it too, but it is not versioned.

The audit trail's pull API (`/api/audit/events`) has tokens of its own; see [Audit log](#audit-log).

## Integration API (`/api/v1`)

Other systems search and read directories, change entries and manage group members through it. Every
call goes through the same permission checks, namespace confinement, form validation and audit trail
as the portal's pages.

### The OpenAPI document

Served at **`/api/v1/openapi`** (JSON), or `/api/v1/openapi.yaml` for YAML, without signing in. Its
server is `PORTAL_PUBLIC_URL`. CI generates it with the tests and keeps a copy in the source
repository (`backend/openapi/dsp-api-v1.json`).

### Endpoints and scopes

| Call | Scope |
|---|---|
| `GET /api/v1/directories`, `GET /api/v1/directories/{id}` (the fields of its form) | `entries:read` or `groups:read` |
| `GET /api/v1/directories/{id}/entries?q=&page=&pageSize=`, `GET /api/v1/directories/{id}/entry?dn=` | `entries:read` |
| `POST /api/v1/directories/{id}/entries` `{"parentDn", "values"}`, `PATCH /api/v1/directories/{id}/entry?dn=` `{"values"}` | `entries:write` |
| `DELETE /api/v1/directories/{id}/entry?dn=` | `entries:delete` |
| `GET /api/v1/directories/{id}/members?dn=[&attribute=]` | `groups:read` |
| `POST /api/v1/directories/{id}/members?dn=` `{"add": [], "remove": []}` | `groups:write` |

```bash
curl -H "Authorization: Bearer $DSP_TOKEN" \
  "https://portal.example.com/api/v1/directories/staff/entries?q=smith"

curl -X POST -H "Authorization: Bearer $DSP_TOKEN" -H "Content-Type: application/json" \
  -d '{"add": ["uid=jdoe,ou=people,dc=example,dc=com"], "remove": []}' \
  "https://portal.example.com/api/v1/directories/groups/members?dn=cn=staff,ou=groups,dc=example,dc=com"
```

Member changes are value-level adds and deletes, never a replace of the list (see
[Import and export → Bulk membership](import-export.md#bulk-membership)).

### API tokens

*Administration → Integrations → API tokens*

- **Created by an administrator** with a name, scopes, an expiry (at most `API_TOKEN_MAX_DAYS`,
  default 365), optionally a list of directories, and optionally its own rate limit. The value
  (`dspat_…`) is shown **once**; only its SHA-256 is kept. Tokens can be revoked at once; the list
  shows each token's last use.
- **Never more than its creator.** A token acts as the administrator who created it, with their
  rights *as the directory has them at the time of the call* (read again at most once a minute),
  narrowed by its scopes and directories. If they lose a permission or their entry, so does the
  token. A creator whose account the directory marks disabled or locked (`nsAccountLock: true`,
  Active Directory's ACCOUNTDISABLE flag in `userAccountControl`, or `pwdAccountLockedTime`) makes
  every call fail with `401 token_owner_disabled` until the account is enabled again. Rights a
  single sign-on provider asserted at their sign-in don't carry over.
- **Only for `/api/v1`.** A token opens nothing else, and `/api/v1` accepts nothing but a token (no
  session cookie), which is why it needs no `X-DSP-Request` header. No session is created; the token
  is never logged.
- **Refused**: missing, unknown, expired or revoked tokens get `401` (and an `access.denied` audit
  event); a missing scope gets `403 insufficient_scope`.
- **Rate limit**: `API_RATE_LIMIT_PER_MINUTE` (120) per token unless the token sets its own; over it,
  `429` with `Retry-After`. The count is kept in each API replica's memory, so with several replicas
  a token can make up to the limit on each. An address that presents more than
  `API_FAILED_TOKEN_LIMIT_PER_MINUTE` (30) unknown, expired or revoked tokens a minute gets `429`
  instead of `401` for further bad ones; a request with a valid token is never refused by it.
- **Audited**: `api.token-created`, `api.token-revoked`; a token's changes name it
  (`details.apiClientId`, `authMethod: api-token`).
- **Storage**: `DATA_DIR/api-tokens.json`, or the shared database.

| Variable | Default | Helm | Description |
|---|---|---|---|
| `API_TOKEN_MAX_DAYS` | `365` | `integrations.api.maxTokenDays` | The longest lifetime an administrator may give a token |
| `API_RATE_LIMIT_PER_MINUTE` | `120` | `integrations.api.rateLimitPerMinute` | Requests per minute per token, unless the token sets its own (per replica) |
| `API_FAILED_TOKEN_LIMIT_PER_MINUTE` | `30` | | Bad tokens one address may present a minute before `429` |

Token administration (session, administrator, `X-DSP-Request: 1`):

| Method | Path | Description |
|---|---|---|
| GET | `/api/admin/api-tokens` | The tokens (never their values), with last use |
| GET | `/api/admin/api-tokens/settings` | Scopes and limits |
| POST | `/api/admin/api-tokens` | Create; the answer holds the token, once |
| DELETE | `/api/admin/api-tokens/{id}` | Revoke |

### Webhooks

The portal also posts signed events (`entry.changed`, `group.membership_changed`, `form.submitted`)
to HTTPS receivers. See [Webhooks](webhooks.md).

## Portal API conventions

| Topic | Rule |
|---|---|
| Format | JSON request and response bodies |
| Authentication | Session cookie `DSP_SESSION`, obtained from `POST /api/auth/login`, `POST /api/auth/x509` or [single sign-on](single-sign-on.md) |
| CSRF | Every POST, PUT and DELETE must send `X-DSP-Request: 1` (otherwise 403). Not needed on `/api/v1`, which takes only API tokens |
| Admin | `/api/admin/**` requires an administrator (otherwise 403 *Administrator access required*) |
| Errors | `{"error": "message"}`, with field details for validation errors (400 *Invalid request*) |
| Status codes | 200, 201 (created), 202 (background job), 204 (deleted), 400, 401 (*Authentication required*, or `"code": "session_expired"`), 403, 404, 405, 409 (conflict, e.g. form in use), 429 (throttled / too many tasks), 500, 503 (the audit store can't be written) |
| License errors | 403 with `"code"` `license_required` (unlicensed), `license_read_only` (a write while read-only) or `feature_not_licensed` (with `"feature": "<id>"`) |
| Background polling | Requests that should not count as user activity send `X-DSP-Background: 1`; they don't extend the session's idle time |
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
| GET | `/api/health` | Liveness, kept for existing monitoring: `{"ok": true}` |
| GET | `/api/v1/openapi`, `/api/v1/openapi.yaml` | The integration API's OpenAPI document |
| GET | `/api/info` | `mode`, `connected` (whether the directory answers), `version`, `auth` (`password`, `x509`, `x509AutoLogin`, and `sso`: a list of `{id, label, url}` for each single sign-on button), `loginHint`, `banner`, and `consent` (the consent banner's `title` and `text`, or null). `baseDn` only for signed-in users; `ldapUrl` and `error` only for administrators |
| GET | `/livez`, `/readyz` | Liveness and readiness probes on the API's own port. Not published by the web tier |
| GET | `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness` | Spring Boot's probes (on the management port when `MANAGEMENT_SERVER_PORT` is set). Details only for administrators. Not published by the web tier |
| GET | `/actuator/prometheus` | Prometheus metrics, on the management port only |

## Authentication

| Method | Path | Body / params | Description |
|---|---|---|---|
| POST | `/api/auth/consent` | — | Acknowledge the notice-and-consent banner for this browser session. Returns `{"consentRequired": false}`. Reachable without signing in |
| POST | `/api/auth/login` | `{"username", "password"}` | Password sign-in. Returns `{"user": SessionUser}`. 401 for bad credentials, 429 when throttled, 403 when password sign-in is disabled, 403 `consent_required` when the consent banner has not been acknowledged |
| POST | `/api/auth/x509` | — | Sign in with the presented client certificate. 403 `consent_required` as above |
| POST | `/api/auth/logout` | — | Sign out and invalidate the session. Returns `{"ok": true, "consentRequired": ...}`: whether the banner must be acknowledged again before the next sign-in |
| GET | `/api/auth/me` | — | `{"user": SessionUser or null, "consentRequired": ...}`. With X.509 auto-login enabled, it may sign the user in (only once the banner, if enabled, has been acknowledged), or return `certificateError` |

| GET | `/api/auth/sso/authorize/oidc` | — | Start an OpenID Connect sign-on (a browser redirect); the provider returns to `/api/auth/sso/callback/oidc` |
| GET | `/api/auth/sso/saml/authenticate/saml` | — | Start a SAML sign-on; the provider posts to `/api/auth/sso/saml/acs/saml` |
| GET | `/api/auth/sso/saml/metadata` | — | This service provider's SAML metadata, for the identity provider |

`SessionUser` contains `username`, `dn`, `displayName`, `mail`, `groups`, `isAdmin`, `authMethod`
(`password`, `x509`, `oidc` or `saml`) and `certificateSubject` (certificate sign-ins only). See
[Single sign-on](single-sign-on.md).

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
| POST | `/api/directories/{id}/export` | `q`, `format` (`csv` or `ldif`) | Export the entries matching the search (up to `EXPORT_MAX_ENTRIES`). Audited as `data.exported` |
| GET | `/api/directories/{id}/members` | `dn`, `attribute` | A group's members |
| POST | `/api/directories/{id}/members/export` | `dn`, `attribute`, `format` | Export a group's members |
| POST | `/api/directories/{id}/members` | `dn`; `{"add": [], "remove": []}` | Bulk membership change (up to `BULK_MAX_MEMBERS`) |
| POST | `/api/directories/{id}/import` | `format` (`csv`/`ldif`), `mode` (`create`, `update` or `upsert`, the default), `dryRun`; the file as the body | Import, or check the file with `dryRun=true`. Long imports answer 202 as a background job |

See [Import and export](import-export.md).

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

### License

| Method | Path | Description |
|---|---|---|
| GET | `/api/license` | Any signed-in user: the mode, state, message, evaluation flag and every feature with whether it is enabled. Administrators also get `details` (licensee, edition, dates, size, this installation's id) and the renewal warning |
| POST | `/api/admin/license` | Install a license: the file as the request body (`text/plain`), or the multipart field `file`. Says why a file is refused |

### Audit log

These work whatever the license says.

| Method | Path | Params | Description |
|---|---|---|---|
| GET | `/api/admin/audit` | `q`, `category`, `action`, `outcome`, `severity`, `actor`, `target`, `ip`, `definitionId`, `from`, `to` (`YYYY-MM-DD` or ISO instant), `page`, `pageSize` (1–200, default 50) | Search all retained history, newest first. A search reads at most `ENS_AUDIT_QUERY_MAX_SCAN` events and says when it stopped |
| GET | `/api/admin/audit/actions` | — | The filter choices: categories, outcomes, severities and known actions |
| GET | `/api/admin/audit/export` | `format` (`csv` or `ndjson`) plus the search filters | The current filter's events (up to 100,000), oldest first. Audited as `audit.exported` |
| POST | `/api/admin/audit/verify` | — | Recompute the hash chain. Audited as `audit.verified` |
| GET | `/api/admin/audit/status` | — | Each output's delivery state, the retention setting and the chain's extent |
| GET, POST | `/api/admin/audit/tokens` | `{"name"}` to create | List or create pull-API tokens. A new token's value is shown once |
| DELETE | `/api/admin/audit/tokens/{id}` | — | Revoke a token |
| GET | `/api/audit/events` | `after` (seq), `limit` (1–1000, default 500) | The pull API: `Authorization: Bearer <token>`, no session or CSRF header. NDJSON in seq order, or `{"events": [...], "next": <seq>}` with `Accept: application/json`. See [Audit logging](../audit-logging.md#the-pull-api) |
