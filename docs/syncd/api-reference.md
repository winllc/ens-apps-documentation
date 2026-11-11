# API reference

syncd has these programmable interfaces:

| Interface | Served by | Used by | Authentication |
|---|---|---|---|
| [Console REST API](#console-rest-api) (`/api/v1`) | `syncd-console` | The console UI, scripts, pipelines | The console's session cookie, or an [API token](#api-tokens) |
| [Audit pull API](#audit-trail) (`GET /api/audit/events`) | `syncd-console` | A SIEM collector | An API token with `audit:read`, or an admin's or auditor's session |
| [Engine control API](#engine-control-api-grpc) (gRPC, `syncd.v1.EngineControl`) | `syncd` | The console, the `syncd` command line, the other engine of an HA pair | Mutual TLS, or the loopback listener and the control socket |
| [Password ingest API](#password-ingest-api-grpc) (gRPC, `syncd.v1.PasswordIngest`) | `syncd`, on `passwords.ingest.listen` | Password capture agents | Mutual TLS with the engine's agent CA, or a one-time token to enroll |
| [Prometheus metrics](#prometheus-metrics) (`GET /metrics`) | `syncd` | Prometheus | None (plain HTTP) |

The console also sends [webhooks](webhooks.md) to other systems.

## Console REST API

### The OpenAPI document

The console serves an OpenAPI 3 document, generated from the server's code, at
`GET /api/openapi.json`, without signing in. It describes every route below with its request and
response schemas, and two security schemes: `session` (the cookie) and `api_token` (a bearer
token). Each operation an API token may call names the scope it needs under `api_token`;
operations without it take a session only. The UI's TypeScript types are generated from the
document, and a console test fails when the committed copy (`console/web/openapi.json` in the
source) no longer matches the server. Load it into your API client or code generator rather than
copying paths from this page.

### Authentication

From 0.4.0 the API takes either of:

- **An API token** (`Authorization: Bearer sdt_…`), for scripts, pipelines and SIEM collectors.
  See [API tokens](#api-tokens).
- **The `syncd_session` cookie** that signing in sets, for the browser.

A request with `Authorization: Bearer` is checked as a token request only: it never reads or sets
the session cookie. Some endpoints stay with people (accounts, settings, tokens, webhooks,
changes to environments, approvals, simulations, previews, connector checks, the object
explorer, scheduled reports, the support bundle): a valid token there gets
`403 insufficient_scope`.

## API tokens

Other systems call the console's API with a token instead of a session: a SIEM pulling the audit
trail, a pipeline promoting configuration between environments, a monitoring script reading
status. Administrators create and revoke tokens on **Integrations** (`/integrations`); auditors
see the list.

### Calling the API with a token

```sh
# The pull API for the audit trail (audit:read)
curl -H "Authorization: Bearer sdt_…" "https://syncd.example.com/api/audit/events?after=0&limit=1000"

# Engines and one engine's status (engines:read)
curl -H "Authorization: Bearer sdt_…" https://syncd.example.com/api/v1/engines
curl -H "Authorization: Bearer sdt_…" https://syncd.example.com/api/v1/engines/1/status

# Start a reconciliation (runs:run); returns when it is done
curl -X POST -H "Authorization: Bearer sdt_…" -H 'Content-Type: application/json' \
  --data '{"kind":"reconcile"}' https://syncd.example.com/api/v1/engines/1/runs

# Promote a published version to another environment (configs:publish)
curl -X POST -H "Authorization: Bearer sdt_…" -H 'Content-Type: application/json' \
  --data '{"engine":"dev","version":7,"to":"prod","ticket":"CHG-12345"}' \
  https://syncd.example.com/api/v1/configs/promote
```

`syncd config promote dev/v7 --to prod --console https://syncd.example.com --token-file FILE`
makes the last call from the command line (see [Configuration as code](config-as-code.md)).

### How tokens work

- **Created with** a name, one or more scopes and, optionally, an expiry. The token (`sdt_` and 43
  characters: 32 random bytes, base64url) is shown **once**, in the response that creates it. The
  console keeps only its SHA-256 and its first four characters (for the list); the token is never
  written to a log, the audit trail or the session.
- **Never more than its creator, now.** Each scope needs a role of the creator (below), both to be
  granted and, at every use, to work. On **every use** the creator's account is read: an account
  that is gone or disabled refuses the token (`401`). Otherwise the token does no more than the
  creator's role allows today, so a demoted creator's token keeps its reads and loses its writes.
  A good answer is reused for a minute (and dropped when the account changes); a refusal never is.
- **A year at most.** `CONSOLE_API_MAX_TOKEN_LIFETIME` (`365d`) is the expiry of a token created
  without one, the most that may be asked for, and the age at which any token stops working; `0`
  removes the limit.
- **Refused** (`401`, `WWW-Authenticate: Bearer error="invalid_token"`) when unknown, revoked,
  past its expiry, when its creator is gone or disabled, or when `CONSOLE_API_TOKENS_ENABLED` is
  `false`.
- **Out of scope** (`403`, `WWW-Authenticate: Bearer error="insufficient_scope"`) for an endpoint
  its scopes do not cover, one its creator's role no longer allows, or one that stays with people.
  No token, whatever its scopes, can create or revoke tokens, manage accounts, settings, webhooks,
  environments or scheduled reports, sign in through single sign-on, approve a configuration, simulate or
  preview one, test connectors, use the object explorer, or download the support bundle.
- **Rate-limited** per token: `CONSOLE_API_REQUESTS_PER_MINUTE` (300). Beyond it, `429
  rate_limited` with `Retry-After` (seconds). The count is kept in each console process, so with
  several console replicas each allows that many.
- **Audited**: `api.token-created`, `api.token-revoked`, `api.token-refused` (at most once a
  minute per token, or per client address for a value that is no token at all), `api.request` for
  every request that changes something, and `api.token-used` once per token per hour for its
  reads. The actor is `api-token:<id>`, of type `service`.

### Scopes

| Scope | Needs the creator to be | Allows |
|---|---|---|
| `engines:read` | any role | The engines, their status, dead letters, quarantine, held changes, data quality, correlation suggestions, and the reconciliation report export |
| `configs:read` | any role | Configuration versions, the running configuration, diffs, exports, packages, the environments' names and the Git sync status |
| `configs:write` | designer or admin | Validate and save a configuration version, import one, start a Git sync (not publish) |
| `configs:publish` | designer or admin | Publish a version, and promote one. Approval, when required, stays with people |
| `runs:run` | operator or admin | Start runs and drift checks, approve held deletions and changes, review groups, accept correlation suggestions, retry and discard dead letters |
| `audit:read` | auditor or admin | The audit log, its verification, the pull API, the export and the outputs' status |
| `environments:read` | auditor or admin | From 0.4.1: the environments with their plain values, the names of their secret ones, and their engines. Before 0.4.1 `configs:read` read them; a script that reads values with a `configs:read` token needs this scope now |
| `admin` | admin | Everything above |

The tables below give each endpoint's scope in the **Token** column; `—` means session only.

### Token settings

| Variable | Default | Meaning |
|---|---|---|
| `CONSOLE_API_TOKENS_ENABLED` | `true` | `false` refuses every token (`401`) without deleting any |
| `CONSOLE_API_MAX_TOKEN_LIFETIME` | `365d` | The longest a token lives (`<n>d`, `<n>h`); `0` removes the limit |
| `CONSOLE_API_REQUESTS_PER_MINUTE` | `300` | Per token; `0` turns the limit off |

### Token administration

| Method | Path | Access | Purpose |
|---|---|---|---|
| GET | `/api/v1/admin/api-tokens` | admin, auditor | The tokens, newest first, without their values: name, prefix, scopes, creator, created, expiry, last use, revoked |
| GET | `/api/v1/admin/api-tokens/scopes` | admin, auditor | The scopes, what each allows and the role it needs |
| POST | `/api/v1/admin/api-tokens` | admin (session only) | Create: `{"name", "scopes": [...], "expires_at"}`; the answer holds the token, once |
| DELETE | `/api/v1/admin/api-tokens/{id}` | admin (session only) | Revoke; the next use is refused |

## Signing in with a session

A browser, or a script that has no token, signs in as a console account and keeps the cookie:

```sh
curl -sS -c cookies.txt -H 'Content-Type: application/json' \
  -d '{"username":"ops-bot","password":"..."}' \
  https://syncd.example.com/api/v1/auth/login
curl -sS -b cookies.txt https://syncd.example.com/api/v1/engines/1/status
curl -sS -b cookies.txt -X POST https://syncd.example.com/api/v1/auth/logout
```

- The cookie is `Secure` outside development mode, so call the API over HTTPS.
- Sessions end after 30 idle minutes and 12 hours (`CONSOLE_SESSION_IDLE_MINUTES`,
  `CONSOLE_SESSION_MAX_HOURS`). The per-address limit (20 sign-ins in 5 minutes) and the account
  lockout apply to scripts too.
- A new account must change its one-time password before anything else works: sign in once and
  call `POST /api/v1/auth/password`.
- Prefer an API token for automation. With local sign-in limited to break-glass accounts, only
  those can sign in with a password, so a script then has no account to sign in as.

## Conventions

- Requests and responses are JSON (`Content-Type: application/json` on requests with a body). The
  largest body is 2 MB; a configuration may be at most 1 MiB. Exports and the pull API answer
  CSV, PDF, NDJSON or `tar.zst` as each says.
- Errors are `{"error": "<code>", "message": "<text>"}`:

| Status | `error` | When |
|---|---|---|
| 400 | `bad_request` | The request is not valid, the engine refused it as invalid, or a publish lacks a ticket matching `CONSOLE_PUBLISH_TICKET_PATTERN` |
| 401 | `unauthenticated` | No session, or it has ended |
| 401 | `sign_in_failed` | Any failed sign-in, whatever the reason |
| 401 | `invalid_token` | An API token that is unknown, revoked, expired, turned off, or whose creator is gone or disabled. With `WWW-Authenticate: Bearer error="invalid_token"` |
| 401 | `wrong_credentials` | A self-service password change with a wrong user name or current password (the same answer for both) |
| 403 | `forbidden` | The role cannot do this, or the engine refused the call on its listener |
| 403 | `insufficient_scope` | An API token outside its scopes, beyond its creator's role now, or on an endpoint for people. With `WWW-Authenticate: Bearer error="insufficient_scope"` |
| 403 | `password_change_required` | The account must set a new password first |
| 404 | `not_found` | No such engine, version, entity, webhook, report or endpoint |
| 409 | `conflict` | A name in use, a newer version saved meanwhile, the last admin or break-glass account, a simulation already running, a publish the pre-publish hook refused (with its reason), a promotion of a version never published |
| 429 | `too_many_requests` | Over the sign-in or self-service limit |
| 429 | `rate_limited` | An API token over its requests per minute, with `Retry-After` |
| 503 | `engine_unavailable` | The engine cannot be reached, failed, or is busy with a long operation |
| 503 | `hook_unavailable` | The pre-publish hook did not answer, and `CONSOLE_PUBLISH_HOOK_ON_ERROR` is `refuse` |
| 500 | `internal` | Anything else; details go to the console's log only |

- Lists are paged with a cursor: `before` / `next_before` (audit log, entity history), `after` /
  `next_after` (entities), or `after` / `next` (held changes, correlation suggestions, the pull
  API), with `limit`.
- `reveal=true` (or `"reveal": true` in a body) asks for sensitive attribute values. It needs a role
  in `CONSOLE_REVEAL_ROLES` (default `admin`), is audited, and is refused (and audited) for other
  roles. See [Security](security.md#sensitive-attributes-and-reveal).
- Calls that act on an engine go through to its control API; they wait for it, and are refused as
  busy while it runs a long operation. The passive engine of an HA pair refuses commands, naming
  the active one.

**Roles** in the tables below: *any* is every signed-in role. Admins can do everything.

### Public

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/v1/system` | Version, development and FIPS mode, the single sign-on label, who may sign in locally, whether configuration approval is required, the banners, and the publish policy (the ticket pattern, whether a pre-publish hook is asked) |
| GET | `/api/v1/self-service` | Whether the self-service password page is on, and from 0.4.1 the engine's policy for a new password (`min_length`, `min_classes`; absent when the engine cannot be asked; read at most once a minute) |
| POST | `/api/v1/self-service/password` | A person changes their own directory password: `{"login", "current_password", "new_password"}`. See [Self-service password change](#self-service-password-change) |
| GET | `/api/openapi.json` | The OpenAPI document |
| GET | `/healthz` | `ok` while the console runs |

### Sign-in and accounts

Session only.

| Method | Path | Role | Purpose |
|---|---|---|---|
| POST | `/api/v1/auth/login` | none | Sign in with a local account (`username`, `password`); sets the cookie, returns the account |
| POST | `/api/v1/auth/logout` | signed in | End the session |
| GET | `/api/v1/auth/me` | signed in | The account: role, pending password change, whether it may reveal |
| POST | `/api/v1/auth/password` | signed in | Change the password (`current_password`, `new_password`); the one call open during a forced change. Signs out the account's other sessions |
| POST | `/api/v1/auth/password/skip` | signed in | Development mode only: keep the current password |
| GET | `/api/v1/auth/oidc/login` | none | Start single sign-on (`next`: where to go after); 404 when it is not configured |
| GET | `/api/v1/auth/oidc/callback` | none | Where the identity provider sends the browser back. See [Single sign-on](single-sign-on.md) |
| GET | `/api/v1/users` | admin | List accounts |
| POST | `/api/v1/users` | admin | Create a local account (`username`, `display_name`, `role`); returns its one-time password, shown once |
| PATCH | `/api/v1/users/{id}` | admin | Change the display name, role, disabled flag or break-glass flag |
| POST | `/api/v1/users/{id}/reset-password` | admin | Issue a new one-time password (local accounts only) |

### Settings

Session only.

| Method | Path | Role | Purpose |
|---|---|---|---|
| GET | `/api/v1/settings/sign-in` | admin | Who may sign in locally, whether single sign-on is configured, the break-glass accounts |
| PUT | `/api/v1/settings/sign-in` | admin | `local_sign_in`: `everyone` or `break_glass` |
| PUT | `/api/v1/settings/config-approval` | admin | Configuration approval: `off` or `required` |
| PUT | `/api/v1/settings/banners` | admin | The top and bottom banners: text (up to 200 characters), background and text colours (at least 4.5:1 contrast) |

### Engines

| Method | Path | Role | Token | Purpose |
|---|---|---|---|---|
| GET | `/api/v1/engines` | any | `engines:read` | Registered engines, with their environment and `certificate_expires_at` (when the console's client certificate for the engine expires; mTLS only) |
| POST | `/api/v1/engines` | admin | — | Register an engine: `name`, `address` (`host:port` of `api.listen`, or `unix:` and the absolute path of the engine's `api.control_socket`), `environment` (`default` for a new engine; from 0.4.1, with `replace`, the engine keeps its environment unless one is given), and for mTLS `server_ca_pem`, `client_cert_pem`, `client_key_pem` (the key is stored encrypted and never returned). Checks that it answers unless `verify` is false. `replace: true` replaces the address and credentials of an engine with that name, keeping its id and versions. A name whose stored client key the master key no longer opens is refused with that reason unless `replace` is set (audited as `engine.re-registered`) |
| PATCH | `/api/v1/engines/{id}` | admin | — | Move an engine to another environment (`{"environment": "prod"}`). What it runs is not touched: the next publish renders for the new environment |
| DELETE | `/api/v1/engines/{id}` | admin | — | Remove the registration (the engine keeps running) |
| GET | `/api/v1/engines/{id}/status` | any | `engines:read` | The engine's status: sources, publications (with held changes, report mode, leavers by stage, groups), targets, counters, timers, history, backups, HA role, password delivery (from 0.4.1 also per publication, and `keys_sealed`), quarantined entities, unmatched records per view, rule packages, the environment, the process's CPU and memory (Linux only), and from 0.4.1 `runs` (runs ended since the engine started, and the last one) and `self_service` (the self-service password policy) |
| GET | `/api/v1/engines/{id}/status/stream` | any | — | Server-sent events: a `status` event every second, an `error` event if the engine goes away. Ends when the session does |
| GET | `/api/v1/engines/{id}/dead-letters` | any | `engines:read` | Failed exports waiting (`publication`, `limit`) |
| GET | `/api/v1/engines/{id}/quarantine` | any | `engines:read` | Entities whose exports a failing rule (`on_error: quarantine`) holds back (`view`, `limit` up to 500) |
| GET | `/api/v1/engines/{id}/held` | any | `engines:read` | A publication's held changes: what a change window, a threshold or a canary holds, or what report mode would write (`publication`, `kind`, `reason`, `after`, `limit`). See [Change safety and lifecycle](change-safety.md) |
| POST | `/api/v1/engines/{id}/runs` | operator | `runs:run` | `{"kind": "reconcile" \| "full_sync", "publication": ...}`; returns when done |
| POST | `/api/v1/engines/{id}/approvals` | operator | `runs:run` | `{"scope": ...}`, as the status lists it. Lets deletes over a deletion threshold through once; with `publication <name>`, also the changes its change window (overdue), its modification or add threshold, or a failed canary holds |
| POST | `/api/v1/engines/{id}/groups/review` | operator | `runs:run` | `{"publication", "group"}`: a computed group's expiry and review clocks start again, and an expired group comes back |
| POST | `/api/v1/engines/{id}/drift-check` | operator | `runs:run` | Read a publication's entries (or every checking publication's) back from the target now |
| POST | `/api/v1/engines/{id}/dead-letters/retry` | operator | `runs:run` | Export dead letters again now (all, a publication's, or one entity's) |
| POST | `/api/v1/engines/{id}/dead-letters/discard` | operator | `runs:run` | Remove one dead letter without retrying it |

Registrations are audited as `engine.register`, replacements as `engine.update`. Operator calls are
audited with their request and outcome (`engine.run`, `engine.approve_deletions`,
`engine.check_drift`, `engine.dead_letters.retry`, `engine.dead_letters.discard`, and the group
review).

### Correlation

| Method | Path | Role | Token | Purpose |
|---|---|---|---|---|
| GET | `/api/v1/engines/{id}/views/{view}/unjoined` | any | — | Correlation review: records that joined no entity, with the entities their join keys match |
| GET | `/api/v1/engines/{id}/correlation/suggestions` | any | `engines:read` | Correlation assistance: a view's unjoined records, each with scored candidate entities (`view`, `source`, `after`, `limit`, `reveal`). Nothing is joined |
| POST | `/api/v1/engines/{id}/correlation/accept` | operator | `runs:run` | `{"view", "decisions": [...]}`: join several records as decided, in one transaction on the engine, with an outcome per decision. Audited as `correlation.accepted` |
| POST | `/api/v1/engines/{id}/views/{view}/joins` | operator | — | Join a source record to an entity, whatever the join rules say |
| POST | `/api/v1/engines/{id}/views/{view}/unjoins` | operator | — | Take a record off its entity and keep it from joining that entity again |

See [Reports, lineage and data quality](reports-and-data-quality.md) for the suggestions.

### Configuration versions

| Method | Path | Role | Token | Purpose |
|---|---|---|---|---|
| POST | `/api/v1/configs/validate` | designer | `configs:write` | Compile a configuration (`yaml`, optional `environment` to render it for) with the engine's compiler and run its test cases, without saving |
| GET | `/api/v1/engines/{id}/configs` | any | `configs:read` | The engine's versions, newest first, with their status (`draft`, `publishing`, `published`, `superseded`), source (`console`, `git`, `promotion`), ticket, note and hook decision, and the environments that run the same text |
| POST | `/api/v1/engines/{id}/configs` | designer | `configs:write` | Save a new version (`yaml`, `comment`, `latest_seen`). It must compile; 409 if a newer version was saved since `latest_seen` |
| GET | `/api/v1/engines/{id}/configs/running` | any | `configs:read` | What the engine runs now |
| POST | `/api/v1/engines/{id}/configs/import` | designer | `configs:write` | Save what the engine runs as a new version |
| GET | `/api/v1/engines/{id}/configs/{version}` | any | `configs:read` | One version, with its YAML |
| GET | `/api/v1/engines/{id}/configs/{version}/diff` | any | `configs:read` | Against `against` (a version number, or `running`; default the previous version): the unified diff and the semantic diff (`semantic`, `summary`), rendered for the engine's environment, or `environment=<name>`, or `environment=` for the texts as saved. Secret values are masked |
| GET | `/api/v1/engines/{id}/configs/{version}/packages` | any | `configs:read` | The version's rule packages, with their functions |
| POST | `/api/v1/engines/{id}/configs/{version}/packages/check` | any | `configs:read` | Compare each package read from a file with the file at the Git branch head now |
| POST | `/api/v1/engines/{id}/configs/{version}/simulate` | designer | — | Dry-run the version on the engine: what each target would receive (`samples`, `reveal`). Nothing is applied |
| POST | `/api/v1/engines/{id}/configs/{version}/approve` | designer | — | Approve a version for publishing, as someone other than its author |
| POST | `/api/v1/engines/{id}/configs/{version}/publish` | designer | `configs:publish` | Publish, with an optional body `{"ticket", "note"}`: rendered for the engine's environment, checked against the ticket pattern, approval, test cases and the pre-publish hook, then the engine validates, saves, reloads and runs a full sync. Returns once the engine runs it (up to 60 seconds). From 0.4.1, with Git sync, the golden-file tests in the repository (`tests/golden` beside the configuration, at the branch head) run too, as the editor's check runs them, and a failure refuses the publish (409); 0.4.0 ran them only from the editor's **Publish** button. This covers Git auto-publish and promotion as well |
| POST | `/api/v1/configs/promote` | designer | `configs:publish` | `{"engine", "version", "to", "ticket", "note"}`: publish a version published on one engine to every engine of another environment, each through its own gates. The answer lists each engine's outcome; 409 when the version was never published or no engine runs in that environment |
| GET | `/api/v1/engines/{id}/configs/{version}/export` | any | `configs:read` | An export file of the version (without the `engine` and `api` sections), with a SHA-256 of the configuration |
| POST | `/api/v1/engines/{id}/configs/import-file` | designer | `configs:write` | Import an export file as a new draft, keeping this engine's `engine` and `api` sections; returns what the host must provide |
| POST | `/api/v1/engines/{id}/connectors/test` | designer | — | Connect with one connector of a candidate configuration (`yaml`, `connector`) |
| POST | `/api/v1/engines/{id}/connectors/discover` | designer | — | List what a connector holds, a container's fields, or sample records (`reveal` for sensitive values) |
| POST | `/api/v1/engines/{id}/preview` | designer | — | Run a candidate configuration over a sample of source records: the entities it makes and what each publication would write |

Saves, imports, exports, approvals, simulations, previews, connector checks, publishes and
promotions are audited (`config.*`, `connector.*`). See [Configuration as code](config-as-code.md)
for environments, promotion and Git sync, and [Security](security.md#the-pre-publish-hook) for the
pre-publish hook.

### Environments and Git sync

| Method | Path | Role | Token | Purpose |
|---|---|---|---|---|
| GET | `/api/v1/environment-names` | any | `configs:read` | From 0.4.1: the environments' names (those with saved values, and those an engine runs in), for the Compare tab and the publish dialog |
| GET | `/api/v1/environments` | admin, auditor | `environments:read` | The environments with their values, and the engines that run in each. Before 0.4.1 any role, or `configs:read`, could read them |
| GET | `/api/v1/environments/{name}` | admin, auditor | `environments:read` | One environment. A secret value shows as `{"secret": true}`, never its value |
| PUT | `/api/v1/environments/{name}` | admin | — | Replace its values: `{"values": {"hr_host": {"value": "..."}, "hr_password_file": {"value": "...", "secret": true}}}`. A secret value is sealed with the master key; a `PUT` that leaves its value out keeps it. Audited as `environment.changed` (the keys, never the values) |
| DELETE | `/api/v1/environments/{name}` | admin | — | Remove its values; refused while an engine runs in it |
| GET | `/api/v1/config-git` | any | `configs:read` | What the console last saw of the Git repository: last commit, sync time, last error |
| POST | `/api/v1/config-git/sync` | designer | `configs:write` | Fetch and take in new commits now; 409 when Git sync is not configured |

### Object explorer and lineage

Session only.

| Method | Path | Role | Purpose |
|---|---|---|---|
| GET | `/api/v1/engines/{id}/views/{view}/entities` | any | Search a view's entities (`text`, `attribute`, `after`, `limit`, `random`, `reveal`) |
| GET | `/api/v1/engines/{id}/views/{view}/entities/{entity}` | any | One entity: merged attributes and where each came from, its source records, what each target received |
| GET | `/api/v1/engines/{id}/views/{view}/entities/{entity}/history` | any | The entity's history, newest first |
| POST | `/api/v1/engines/{id}/entities/{entity}/lineage/export` | any | `?view=&format=json\|pdf[&reveal=true]`: everything the engine knows about the entity, as a file. Audited as `data.exported` (and `explore.reveal` with `reveal`) |

Every search and entity view is audited (`explore.search`, `explore.entity`, `explore.history`,
`explore.unjoined`, `explore.reveal`, `correlation.join`, `correlation.unjoin`).

### Data quality

| Method | Path | Role | Token | Purpose |
|---|---|---|---|---|
| GET | `/api/v1/engines/{id}/quality` | any | `engines:read` | Per view, each check's failing entities now and its trend over the last seven days |
| GET | `/api/v1/engines/{id}/quality/entities` | any | `engines:read` | The entities failing a view's checks, or one check (`view`, `check`, `limit`): ids, check, severity and message, never values |
| POST | `/api/v1/engines/{id}/quality/try` | any | — | Try a check (`view`, `expr` or `unique`, `sample`) on a random sample of entities. Sensitive attributes are not read |

### Reports and exports

| Method | Path | Role | Token | Purpose |
|---|---|---|---|---|
| POST | `/api/v1/engines/{id}/reports/reconciliation/export` | any | `engines:read` | `?format=csv\|pdf[&publication=]`: the reconciliation report as a file, at most `CONSOLE_EXPORTS_MAX_ROWS` rows (`X-Syncd-Truncated: true` when capped). Audited as `data.exported` first |
| GET | `/api/v1/reports` | admin, operator, auditor | — | The scheduled reports with their next and last run |
| GET | `/api/v1/reports/settings` | admin, operator, auditor | — | Whether mail is configured, the recipient domains, whether a share is set |
| POST | `/api/v1/reports` | admin, operator | — | Add a scheduled report |
| PUT | `/api/v1/reports/{id}` | its owner, admin | — | Replace its definition |
| DELETE | `/api/v1/reports/{id}` | its owner, admin | — | Delete it |
| POST | `/api/v1/reports/{id}/send` | its owner, admin | — | Run it now, with the same checks as a scheduled run |

See [Reports, lineage and data quality](reports-and-data-quality.md).

### Rules

| Method | Path | Role | Token | Purpose |
|---|---|---|---|---|
| POST | `/api/v1/rules/preview` | any | — | Run a computed attribute's steps on given input, with the engine's own rules crate, and its test cases |
| GET | `/api/v1/rules/functions` | any | — | The function catalogue for the CEL editor, with the configuration's package functions |

See [Computed attributes and rules](rules.md).

### Audit trail

The console's trail is in the ENS format, `ens-audit/1` (see [Audit logging](../audit-logging.md)).

| Method | Path | Role | Token | Purpose |
|---|---|---|---|---|
| GET | `/api/v1/audit` | auditor | `audit:read` | The audit log for the Audit log page, newest first (`before`, `limit` up to 500), including entries from before 0.4.0 |
| GET | `/api/v1/audit/verify` | auditor | `audit:read` | Recompute the hash chain (the entries from before 0.4.0, then the `ens-audit/1` chain): `entries`, `first_broken`, `head`, `legacy_entries`, `first_seq`. With `anchor_id` and `anchor_hash` (a head recorded earlier), also `anchor`: `found` and `matches`. A broken chain is recorded as `audit.verified` (alert) |
| GET | `/api/audit/events` | auditor | `audit:read` | **The pull API**, at the path every ENS product serves it on: `?after=<seq>&limit=<1-1000>` (default 100). Events with `seq` above `after`, oldest first, as NDJSON (`application/x-ndjson`); with `Accept: application/json` as `{"events":[...],"next":<seq>}`. Start at `after=0`, then pass the last `seq` received. It needs only the database, so it works while the console is otherwise degraded |
| GET | `/api/v1/audit/events` | auditor | `audit:read` | The same, under `/api/v1` |
| GET | `/api/v1/audit/export` | auditor | `audit:read` | `?from=&to=` (RFC 3339, both optional): the trail as an NDJSON download, newest first, for `tools/audit/verify_chain.py`. Recorded as `data.exported` first, and refused when it cannot be |
| GET | `/api/v1/audit/outputs` | auditor | `audit:read` | Each output's delivery position, backlog, last delivery, `failing_since` and last error |

Events from before 0.4.0 are in `/api/v1/audit` but not in the pull API or the export. The family's
Splunk add-on (`TA-ens-audit`, scripted input `ens_audit_pull.py`) pulls from
`/api/audit/events`. See [Security](security.md#audit-trail).

### Webhooks

The webhook administration endpoints (`/api/v1/admin/webhooks`, session only) are listed in
[Webhooks](webhooks.md#administration-endpoints).

### Administration

| Method | Path | Role | Token | Purpose |
|---|---|---|---|---|
| GET | `/api/v1/admin/support-bundle` | admin | — | The console's part of a support bundle, as `tar.zst`: `console.json` (version, modes, settings), `migrations.txt`, `environment.txt` (the console's variables, secret values redacted), `engines.json` (each engine, its address, environment, certificate expiry and whether it answers). No credentials, no entity data. Recorded as `support.bundle-exported` |

### Self-service password change

With `CONSOLE_SELF_SERVICE_ENGINE` naming a registered engine that has `passwords.self_service`,
the public page `/password` lets people change their own directory password without a console
account. `POST /api/v1/self-service/password` takes `{"login", "current_password",
"new_password"}`:

| Answer | When |
|---|---|
| 200 | Changed in the directory people sign in to, and queued for delivery to the others |
| 400 | The new password does not meet the engine's policy (the reason, in words) or the directory refused it |
| 401 `wrong_credentials` | A wrong user name or current password: the same answer for both |
| 404 | Self-service is off |
| 429 | Over the per-address limit, or the user name is held off after 5 failures in 15 minutes |
| 503 | The engine cannot be reached or could not change it now |

The console seals the request to the engine's password key and keeps nothing of it; each attempt is
audited as `password.self_service` with its outcome. From 0.4.1, when the named engine is one of an
HA pair, the request goes to the member that holds the lease. See
[Password synchronisation](password-sync.md) and [Security](security.md#password-synchronisation).

## Engine control API (gRPC)

The engine serves the gRPC service `syncd.v1.EngineControl`, defined in `proto/syncd/v1/engine.proto`
(shipped in the source; there is no server reflection). The console, the `syncd` command line and
the other engine of an HA pair are its clients. It listens on `api.listen` (default
`127.0.0.1:7443`), and optionally on a Unix socket (`api.control_socket`).

| Access | Calls |
|---|---|
| Always (read-only) | `GetStatus`, `WatchStatus`, `ListDeadLetters`, `ListQuarantined`, `ListQuality`, `ListHeld`, `GetConfig`, `SearchEntities`, `GetEntity`, `GetEntityHistory`, `GetLineage`, `ReconciliationReport`, `GetPasswordKey`, `ListPasswordAgents` |
| Commands: mTLS, the control socket, or `api.local_control: true` on loopback | `StartRun`, `ApproveDeletions`, `CheckDrift`, `RetryDeadLetters`, `DiscardDeadLetter`, `ApplyConfig`, `SimulateConfig`, `TestConnector`, `DiscoverSchema`, `PreviewConfig`, `ListUnjoined`, `JoinRecord`, `UnjoinRecord`, `SuggestJoins`, `AcceptJoins`, `ReviewGroup`, `PruneHistory`, `SubmitPasswords`, `AddPasswordAgent`, `RevokePasswordAgent`, `ChangeOwnPassword` |
| Sensitive values (`reveal`): mTLS, the control socket, or `api.local_reveal: true` on loopback | The explorer calls, `GetLineage`, `ListUnjoined`, `SuggestJoins`, `SimulateConfig`, `DiscoverSchema`, `PreviewConfig` |
| The whole store: what a reveal needs | `PullSnapshot` (on the active engine of a pair) |
| The whole store: what a reveal needs, and command access | `Backup` |

| RPC | Purpose |
|---|---|
| `GetStatus` | A snapshot: version, FIPS mode, start time, counters, sources, publications (held changes, report mode, leavers by stage, groups), targets, orphans, held deletes, configuration version and environment, views (unmatched records), timers, history, backups, HA, password delivery, rule packages, what the engine is busy with, quarantined entities per view, and the process's CPU and memory (Linux only). From 0.4.1 also `runs` (`RunsStatus`: runs ended since the engine started, and the last one's activity, start, end and source failures), `self_service` (the self-service policy, when it is on), and in `passwords` (`PasswordStatus`) `keys_sealed` and per-publication counts (`publications`: delivered, failed, dropped, waiting) |
| `WatchStatus` | Snapshots every `interval_ms` (at least 100 ms; 0 means one second) until the client disconnects |
| `ListDeadLetters` | Failed exports waiting to be retried |
| `ListQuarantined` | Entities whose exports a failing rule (`on_error: quarantine`) holds back |
| `ListQuality` | Entities failing a view's data-quality checks (`quality`), with the check's message |
| `ListHeld` | Changes planned but not applied: held by a change window, a threshold or a canary, or what a report-mode publication would write (filters: kind, reason; cursor: the last entity id) |
| `StartRun` | Run a reconciliation (`RUN_KIND_RECONCILE`, optionally one publication) or a full sync (`RUN_KIND_FULL_SYNC`) now; returns when done |
| `ApproveDeletions` | For a scope as the status lists it: let deletes held by a deletion threshold through once, and for `publication <name>` release its held changes that need approval |
| `CheckDrift` | Read a publication's entries (or every checked publication's) back from the target and act by policy |
| `RetryDeadLetters`, `DiscardDeadLetter` | Export dead letters again now; remove one without retrying |
| `GetConfig` | The running configuration (YAML; secrets appear only as references) and its version label |
| `ApplyConfig` | Validate, save and reload with a new configuration, then run a full sync. The answer comes before the reload |
| `SimulateConfig` | Dry-run a configuration on a copy of the store: what each target would receive. One at a time |
| `TestConnector`, `DiscoverSchema` | Connect with one connector of a candidate configuration; list what it holds. Write nothing |
| `PreviewConfig` | Run a candidate over a sample of records in a scratch store |
| `SearchEntities`, `GetEntity`, `GetEntityHistory` | The read-only object explorer |
| `GetLineage` | Everything the engine knows about one entity: each attribute with its source record and rule, the source records, joins, what each publication exported and when, password events (times and outcomes only) and history |
| `PruneHistory` | Prune entity history now, as the hourly prune does |
| `ReconciliationReport` | The reconciliation picture as a stream of rows: a summary per publication, then one row per entry with a difference. Nothing is read from the targets |
| `ListUnjoined`, `JoinRecord`, `UnjoinRecord` | Correlation review and decisions by hand |
| `SuggestJoins`, `AcceptJoins` | Correlation assistance: scored candidates for unjoined records (never joins); several joins in one transaction, with an outcome per decision |
| `ReviewGroup` | Review a computed group: its clocks start again, and an expired group comes back |
| `GetPasswordKey` | The engine's public password key (an uncompressed P-256 point), its id and suite, which capture points seal changes to |
| `SubmitPasswords` | Queue sealed password changes for delivery |
| `AddPasswordAgent`, `ListPasswordAgents`, `RevokePasswordAgent` | A one-time enrollment token (and the agent CA's certificate) for a capture agent; the agents; revoking one |
| `ChangeOwnPassword` | A person's own password change, sealed to the password key (the console's self-service page). `UNAUTHENTICATED` for a wrong user name or password, `FAILED_PRECONDITION` for the policy, `RESOURCE_EXHAUSTED` after too many failures |
| `Backup` | A consistent backup of the running engine (store checkpoint, configuration file, password key and agent CA, as they are on disk: sealed when a passphrase is set) as a streamed `tar.zst`, its manifest last |
| `PullSnapshot` | The active engine's newest store snapshot, for the passive engine of an HA pair |

Errors are gRPC statuses: `PERMISSION_DENIED` for a command or reveal on a listener that does not
allow it, `UNAVAILABLE` when the engine is busy with a long operation (a command waits up to 10
seconds first), has too many commands waiting, is stopping, or is the passive engine of a pair,
`FAILED_PRECONDITION` for password calls when no publication delivers passwords, `INVALID_ARGUMENT`,
`NOT_FOUND`, and `INTERNAL` for a command that failed.

The `syncd` command line wraps these calls (`syncd status`, `dlq`, `held`, `reconcile`,
`full-sync`, `approve`, `retry`, `discard`, `check-drift`, `apply-config`, `group review`,
`correlation suggest`, `lineage`, `report`, `password ...`, `backup`, ...). `syncd quarantine`
reads the store directly (read-only), not through the API. On the engine's host the command line
uses the control socket when it is configured, without a certificate; on Kubernetes,
`kubectl exec syncd-engine-0 -- syncd status` (for a release named `syncd`) does the same. See
[Operator guide](operator-guide.md). For its certificates, see
[Security](security.md#the-engine-control-api).

The source also defines `syncd.bridge.v1.JdbcBridge` (`proto/syncd/bridge/v1/jdbc.proto`), the
protocol between the engine and the JDBC bridge. It is internal to that connector; see
[Connectors](connectors.md).

## Password ingest API (gRPC)

Password capture agents use a listener of their own, not the control API. It is on when the engine
configuration sets `passwords.ingest.listen` (for example `0.0.0.0:7445`), which needs at least one
publication delivering passwords. The service is `syncd.v1.PasswordIngest`, in the same proto file.

| RPC | Who may call | Purpose |
|---|---|---|
| `Enroll` | Anyone with a one-time token | `{"name", "token", "csr_pem"}`: the agent sends a PKCS#10 request for its own key with the token `syncd password agent add` printed. The answer is a client certificate (subject: the agent's name, client authentication only, valid `passwords.ingest.agent_cert_days`), the agent CA's certificate and the expiry. The token is spent, and a certificate from an earlier enrollment of the same agent stops working |
| `GetPublicKey` | Enrolled agents | The engine's password key, as `GetPasswordKey` |
| `Submit` | Enrolled agents | Queue sealed password changes; the answer counts those queued |

- TLS is mutual with the engine's **agent CA**, which also issues the listener's own certificate
  (for the names in `passwords.ingest.names`). A client certificate is asked for, not required, so
  enrolling works without one. Every other call needs a certificate whose fingerprint the engine
  recorded at enrollment and has not revoked; the handshake already refuses a certificate the
  agent CA did not issue.
- A change is sealed with HPKE (RFC 9180, base mode: DHKEM(P-256, HKDF-SHA256), HKDF-SHA256,
  AES-256-GCM) to the engine's key, with the key's id as additional data. A change sealed to
  another key is refused, so the agent fetches the key again.
- The sealed plaintext's layout is internal to syncd. Rather than writing an agent of your own,
  run `syncd password agent enroll` once and `syncd password set --agent-dir DIR ...` from a
  script.

See [Password synchronisation](password-sync.md) for the agents and the commands, and
[Security](security.md#password-synchronisation) for the cryptography.

## Prometheus metrics

When the engine's host configuration sets `api.metrics_listen` (for example `127.0.0.1:9464`), the
engine serves `GET /metrics` there in the Prometheus text format, over plain HTTP, without
authentication. It carries counters and states only, no attribute values or configuration. It cannot
be set from the console. The engine's container image sets it to `0.0.0.0:9464`, and the Helm
chart's `engine.metrics.serviceMonitor.enabled` adds a `ServiceMonitor` for it, whose targets get
the job label `engine.metrics.job` (`syncd`).

Added in 0.3.0:

| Metric | Type | Meaning |
|---|---|---|
| `syncd_view_quarantined{view}` | gauge | Entities whose exports a failing rule (`on_error: quarantine`) holds back; 0 for views with none |
| `process_cpu_seconds_total` | counter | User and system CPU time of the engine process; its rate is the CPUs in use |
| `process_threads` | gauge | Threads of the engine process |
| `process_open_fds` | gauge | Open file descriptors of the engine process |

Added in 0.4.0:

| Metric | Type | Meaning |
|---|---|---|
| `syncd_held_changes{publication,reason}` | gauge | Changes planned but not applied, by why they wait (`window`, `overdue`, `modifications`, `additions`, `deletions`, `canary`, `canary_failed`, `report`) |
| `syncd_window_opens_timestamp_seconds{publication}` | gauge | When the publication's change window next opens, while it is closed |
| `syncd_canary_failed{publication}` | gauge | 1 while a canary failed and the rest of its change waits for approval |
| `syncd_shadow_planned{publication,kind}` | gauge | Operations a report-mode publication would send |
| `syncd_deprovision_pending{publication,stage}` | gauge | Leavers waiting for this stage of a staged leaver policy |
| `syncd_timers_pending` | gauge | Persisted timers armed (effective dates, leaver stages, windows, canaries) |
| `syncd_timers_next_due_timestamp_seconds` | gauge | When the earliest timer fires |
| `syncd_groups{publication}` | gauge | Computed groups of the publication |
| `syncd_groups_manual_members{publication}` | gauge | Computed groups holding members their filter does not produce |
| `syncd_groups_expired{publication}` | gauge | Computed groups past their expiry and not reviewed since |
| `syncd_groups_review_due{publication}` | gauge | Computed groups whose review is due |
| `syncd_unmatched_records{view,source}` | gauge | Source records waiting to join an entity |
| `syncd_unmatched_oldest_seconds{view}` | gauge | How long the longest-waiting unjoined record has waited |
| `syncd_unmatched_ageing{view}` | gauge | Unjoined records older than `correlation.unmatched_alert_after` |
| `syncd_quality_failing{view,check,severity}` | gauge | Entities failing a data-quality check |
| `syncd_history_entries` | gauge | Entity history entries kept, as of the last prune |
| `syncd_history_oldest_timestamp_seconds` | gauge | When the oldest history entry kept was written |
| `syncd_history_last_prune_timestamp_seconds` | gauge | When history was last pruned |
| `syncd_history_pruned_total` | counter | History entries pruned since the engine started |
| `syncd_backup_interval_seconds` | gauge | Seconds between scheduled backups |
| `syncd_backup_last_success_timestamp_seconds` | gauge | When the last scheduled backup succeeded |
| `syncd_backup_last_failure_timestamp_seconds` | gauge | When a scheduled backup last failed |
| `syncd_backup_last_bytes` | gauge | The compressed size of the last successful backup |
| `syncd_backup_pruned_total` | counter | Backups removed by `engine.backup.keep` since the engine started |
| `syncd_ha_active{pair,name}` | gauge | 1 when this engine holds its pair's lease, 0 when passive |
| `syncd_ha_lease_expires_timestamp_seconds{pair,name}` | gauge | When the lease expires unless its holder renews it |
| `syncd_ha_snapshot_age_seconds{pair,name}` | gauge | Age of the newest store snapshot, taken (active) or received (passive) |
| `syncd_ha_snapshot_interval_seconds{pair,name}` | gauge | Seconds between snapshots |
| `syncd_source_rows_skipped_total{source,mode}` | counter | Rows a full read skipped as bad (CSV sources with `on_error: skip`) |
| `syncd_entra_skipped_synced_users{target}` | gauge | Exports to an Entra ID target refused because the user is synced from on-premises |

Added in 0.4.1:

| Metric | Type | Meaning |
|---|---|---|
| `syncd_password_deliveries_total{publication,outcome}` | counter | Captured password changes since the engine started, per publication: `delivered`, `failed` (refused by the target, or expired) or `dropped` (the publication is in report mode). Every outcome of every publication delivering passwords is served, 0 until it moves. Failures no publication can be named for (a change that does not open, or from a source no publication delivers from) have an empty `publication` |
| `syncd_password_waiting{publication}` | gauge | Changes waiting for the publication's target (the account not there yet, or the target unreachable), as of the last delivery pass |

0.4.1 adds two alert rules, in both rule files and the chart's PrometheusRule:
`SyncdPasswordDeliveryFailures` (a publication's failed deliveries rose in 15 minutes) and
`SyncdPasswordChangesDropped` (changes were dropped in the last hour because the publication is
in report mode), both `warning`.

In 0.4.0 the shipped alert rules gained eight (the 0.4.0 release notes said seven): `SyncdChangesNeedApproval`, `SyncdCanaryFailed`,
`SyncdGroupsExpired`, `SyncdUnmatchedRecordsAgeing`, `SyncdQualityErrors`, `SyncdBackupStale`,
`SyncdHaNoActive` and `SyncdHaSnapshotStale`. `SyncdMemoryNearLimit` no longer selects
`job="syncd"`; only `SyncdEngineDown` needs the scrape job's name. On Kubernetes,
`engine.metrics.prometheusRule.enabled` installs them as a PrometheusRule. The process metrics,
like `process_resident_memory_bytes` and `syncd_memory_limit_bytes`, are served on Linux only.
All the metrics, the alert rules and how to scrape them are in the
[Operator guide](operator-guide.md).
