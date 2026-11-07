# API reference

syncd has three programmable interfaces:

| Interface | Served by | Used by | Authentication |
|---|---|---|---|
| [Console REST API](#console-rest-api) (`/api/v1`) | `syncd-console` | The console UI, and scripts | The console's session cookie |
| [Engine control API](#engine-control-api-grpc) (gRPC, `syncd.v1.EngineControl`) | `syncd` | The console, the `syncd` command line | Mutual TLS, or the loopback listener and the control socket |
| [Prometheus metrics](#prometheus-metrics) (`GET /metrics`) | `syncd` | Prometheus | None (plain HTTP) |

## Console REST API

### The OpenAPI document

The console serves an OpenAPI 3 document, generated from the server's code, at
`GET /api/openapi.json`, without signing in. It describes every route below with its request and
response schemas. The UI's TypeScript types are generated from it, and a console test fails when the
committed copy (`console/web/openapi.json` in the source) no longer matches the server.

### Authentication

The API has **no API tokens and no other credentials**: it accepts only the `syncd_session` cookie
that signing in sets. A script signs in as a console account, keeps the cookie, and sends it:

```sh
# Sign in (a local account) and keep the session cookie
curl -sS -c cookies.txt -H 'Content-Type: application/json' \
  -d '{"username":"ops-bot","password":"..."}' \
  https://syncd.example.com/api/v1/auth/login

# The live status of engine 1
curl -sS -b cookies.txt https://syncd.example.com/api/v1/engines/1/status

# Start a reconciliation (needs the operator role); returns when it is done
curl -sS -b cookies.txt -H 'Content-Type: application/json' \
  -d '{"kind":"reconcile"}' \
  https://syncd.example.com/api/v1/engines/1/runs

# Sign out
curl -sS -b cookies.txt -X POST https://syncd.example.com/api/v1/auth/logout
```

- The cookie is `Secure` outside development mode, so call the API through the HTTPS proxy.
- Give a script its own local account with the narrowest role. With local sign-in limited to
  break-glass accounts, only those can sign in with a password, so automation then has no account
  to use: plan for this before limiting local sign-in.
- Sessions end after 30 idle minutes and 12 hours (`CONSOLE_SESSION_IDLE_MINUTES`,
  `CONSOLE_SESSION_MAX_HOURS`). The per-address limit (20 sign-ins in 5 minutes) and the account
  lockout apply to scripts too.
- A new account must change its one-time password before anything else works: sign in once and call
  `POST /api/v1/auth/password`.

### Conventions

- Requests and responses are JSON (`Content-Type: application/json` on requests with a body). The
  largest body is 2 MB; a configuration may be at most 1 MiB.
- Errors are `{"error": "<code>", "message": "<text>"}`:

| Status | `error` | When |
|---|---|---|
| 400 | `bad_request` | The request is not valid (for example `anchor_id` without `anchor_hash`), or the engine refused it as invalid |
| 401 | `unauthenticated` | No session, or it has ended |
| 401 | `sign_in_failed` | Any failed sign-in, whatever the reason |
| 403 | `forbidden` | The role cannot do this, or the engine refused the call on its listener |
| 403 | `password_change_required` | The account must set a new password first |
| 404 | `not_found` | No such engine, version, entity or endpoint |
| 409 | `conflict` | A name in use, a newer version saved meanwhile, the last admin or break-glass account, a simulation already running |
| 429 | `too_many_requests` | Over the sign-in limit |
| 503 | `engine_unavailable` | The engine cannot be reached, failed, or is busy with a long operation |
| 500 | `internal` | Anything else; details go to the console's log only |

- Lists are paged with a cursor: `before` / `next_before` (audit log, entity history) or `after` /
  `next_after` (entities), with `limit` (default 50 or 100, at most 500).
- `reveal=true` (or `"reveal": true` in a body) asks for sensitive attribute values. It needs a role
  in `CONSOLE_REVEAL_ROLES` (default `admin`), is audited, and is refused (and audited) for other
  roles. See [Security](security.md#sensitive-attributes-and-reveal).
- Calls that act on an engine go through to its control API; they wait for it, and are refused as
  busy while it runs a long operation.

**Roles** in the tables below: *any* is every signed-in role. Admins can do everything.

### Public

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/v1/system` | Version, development and FIPS mode, the single sign-on label, who may sign in locally, whether configuration approval is required, and the banners |
| GET | `/api/openapi.json` | The OpenAPI document |
| GET | `/healthz` | `ok` while the console runs |

### Sign-in and accounts

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

| Method | Path | Role | Purpose |
|---|---|---|---|
| GET | `/api/v1/settings/sign-in` | admin | Who may sign in locally, whether single sign-on is configured, the break-glass accounts |
| PUT | `/api/v1/settings/sign-in` | admin | `local_sign_in`: `everyone` or `break_glass` |
| PUT | `/api/v1/settings/config-approval` | admin | Configuration approval: `off` or `required` |
| PUT | `/api/v1/settings/banners` | admin | The top and bottom banners: text (up to 200 characters), background and text colours (at least 4.5:1 contrast) |

### Engines

| Method | Path | Role | Purpose |
|---|---|---|---|
| GET | `/api/v1/engines` | any | Registered engines, with `certificate_expires_at`: when the console's client certificate for the engine expires (mTLS only) |
| POST | `/api/v1/engines` | admin | Register an engine: `name`, `address` (`host:port` of `api.listen`, or `unix:` and the absolute path of the engine's `api.control_socket`), and for mTLS `server_ca_pem`, `client_cert_pem`, `client_key_pem` (required off loopback, refused with `unix:`; the key is stored encrypted and never returned). Checks that it answers unless `verify` is false. `replace: true` replaces the address and credentials of an engine with that name (after its certificates were renewed), keeping its id and configuration versions, instead of answering 409 |
| DELETE | `/api/v1/engines/{id}` | admin | Remove the registration (the engine keeps running) |
| GET | `/api/v1/engines/{id}/status` | any | The engine's status: sources, publications, targets, counters, holds, what it is busy with, quarantined entities per view (`quarantined`), and the engine process's CPU and memory (`process`: CPU seconds, CPUs it may use, resident and peak memory, cgroup memory limit, host memory, threads, open files; absent off Linux) |
| GET | `/api/v1/engines/{id}/status/stream` | any | Server-sent events: a `status` event every second, an `error` event if the engine goes away. Ends when the session does |
| GET | `/api/v1/engines/{id}/dead-letters` | any | Failed exports waiting (`publication`, `limit`) |
| GET | `/api/v1/engines/{id}/quarantine` | any | Entities whose exports a failing rule (`on_error: quarantine`) holds back, with the attribute, its error and since when (`view`, `limit` up to 500); `truncated` when there are more |
| POST | `/api/v1/engines/{id}/runs` | operator | `{"kind": "reconcile" \| "full_sync", "publication": ...}`; returns when done |
| POST | `/api/v1/engines/{id}/approvals` | operator | Let deletes held back by a threshold through once (`scope`, as the status lists it) |
| POST | `/api/v1/engines/{id}/drift-check` | operator | Read a publication's entries (or every checking publication's) back from the target now |
| POST | `/api/v1/engines/{id}/dead-letters/retry` | operator | Export dead letters again now (all, a publication's, or one entity's) |
| POST | `/api/v1/engines/{id}/dead-letters/discard` | operator | Remove one dead letter without retrying it |

Registrations are audited as `engine.register`, replacements as `engine.update`. Operator calls are
audited with their request and outcome (`engine.run`,
`engine.approve_deletions`, `engine.check_drift`, `engine.dead_letters.retry`,
`engine.dead_letters.discard`).

### Configuration versions

| Method | Path | Role | Purpose |
|---|---|---|---|
| POST | `/api/v1/configs/validate` | designer | Compile a configuration with the engine's compiler and run its computed attributes' test cases, without saving |
| GET | `/api/v1/engines/{id}/configs` | any | The engine's versions, newest first, with their status (`draft`, `publishing`, `published`, `superseded`) |
| POST | `/api/v1/engines/{id}/configs` | designer | Save a new version (`yaml`, `comment`, `latest_seen`). It must compile; 409 if a newer version was saved since `latest_seen` |
| GET | `/api/v1/engines/{id}/configs/running` | any | What the engine runs now |
| POST | `/api/v1/engines/{id}/configs/import` | designer | Save what the engine runs as a new version |
| GET | `/api/v1/engines/{id}/configs/{version}` | any | One version, with its YAML |
| GET | `/api/v1/engines/{id}/configs/{version}/diff` | any | Unified diff against `against` (a version number, or `running`; default the previous version) |
| POST | `/api/v1/engines/{id}/configs/{version}/simulate` | designer | Dry-run the version on the engine: what each target would receive (`samples`, `reveal`). Nothing is applied |
| POST | `/api/v1/engines/{id}/configs/{version}/approve` | designer | Approve a version for publishing, as someone other than its author |
| POST | `/api/v1/engines/{id}/configs/{version}/publish` | designer | Publish: the engine validates, saves, reloads and runs a full sync. Returns once the engine runs it (up to 60 seconds); needs an approval when approval is required and the version was never published |
| GET | `/api/v1/engines/{id}/configs/{version}/export` | any | An export file of the version (without the `engine` and `api` sections), with a SHA-256 of the configuration |
| POST | `/api/v1/engines/{id}/configs/import-file` | designer | Import an export file as a new draft, keeping this engine's `engine` and `api` sections; returns what the host must provide |
| POST | `/api/v1/engines/{id}/connectors/test` | designer | Connect with one connector of a candidate configuration (`yaml`, `connector`) |
| POST | `/api/v1/engines/{id}/connectors/discover` | designer | List what a connector holds, a container's fields, or sample records (`reveal` for sensitive values) |
| POST | `/api/v1/engines/{id}/preview` | designer | Run a candidate configuration over a sample of source records: the entities it makes and what each publication would write |

Saves, imports, exports, approvals, simulations, previews, connector checks and publishes are
audited (`config.*`, `connector.*`).

### Object explorer

| Method | Path | Role | Purpose |
|---|---|---|---|
| GET | `/api/v1/engines/{id}/views/{view}/entities` | any | Search a view's entities (`text`, `attribute`, `after`, `limit`, `random`, `reveal`) |
| GET | `/api/v1/engines/{id}/views/{view}/entities/{entity}` | any | One entity: merged attributes and where each came from, its source records, what each target received |
| GET | `/api/v1/engines/{id}/views/{view}/entities/{entity}/history` | any | The entity's history, newest first |
| GET | `/api/v1/engines/{id}/views/{view}/unjoined` | any | Correlation review: records that joined no entity, with the entities their join keys match. Needs the engine's command access (mTLS, as packaged) |
| POST | `/api/v1/engines/{id}/views/{view}/joins` | operator | Join a source record to an entity, whatever the join rules say |
| POST | `/api/v1/engines/{id}/views/{view}/unjoins` | operator | Take a record off its entity and keep it from joining that entity again |

Every search and entity view is audited (`explore.search`, `explore.entity`, `explore.history`,
`explore.unjoined`, `explore.reveal`, `correlation.join`, `correlation.unjoin`).

### Rules

| Method | Path | Role | Purpose |
|---|---|---|---|
| POST | `/api/v1/rules/preview` | any | Run a computed attribute's steps on given input, with the engine's own rules crate, and its test cases |
| GET | `/api/v1/rules/functions` | any | The function catalogue for the CEL editor |

See [Computed attributes and rules](rules.md).

### Audit log

| Method | Path | Role | Purpose |
|---|---|---|---|
| GET | `/api/v1/audit` | auditor | The audit log, newest first (`before`, `limit` up to 500) |
| GET | `/api/v1/audit/verify` | auditor | Recompute the hash chain: `entries`, `first_broken` (an id, or null), and `head` (`id`, `at`, `hash` of the newest entry). With `anchor_id` and `anchor_hash` (a head recorded earlier, such as an `audit log head` line in the console's log), also `anchor`: `found` (the entry is still there) and `matches` (with that hash). Give both or neither |

See [Security](security.md#audit-log).

## Engine control API (gRPC)

The engine serves the gRPC service `syncd.v1.EngineControl`, defined in `proto/syncd/v1/engine.proto`
(shipped in the source; there is no server reflection). The console and the `syncd` command line are
its clients. It listens on `api.listen` (default `127.0.0.1:7443`), and optionally on a Unix socket
(`api.control_socket`).

| Access | Calls |
|---|---|
| Always (read-only) | `GetStatus`, `WatchStatus`, `ListDeadLetters`, `ListQuarantined`, `GetConfig`, `SearchEntities`, `GetEntity`, `GetEntityHistory` |
| Commands: mTLS, the control socket, or `api.local_control: true` on loopback | `StartRun`, `ApproveDeletions`, `CheckDrift`, `RetryDeadLetters`, `DiscardDeadLetter`, `ApplyConfig`, `SimulateConfig`, `TestConnector`, `DiscoverSchema`, `PreviewConfig`, `ListUnjoined`, `JoinRecord`, `UnjoinRecord` |
| Sensitive values (`reveal`): mTLS, the control socket, or `api.local_reveal: true` on loopback | The explorer calls, `ListUnjoined`, `SimulateConfig`, `DiscoverSchema`, `PreviewConfig` |

| RPC | Purpose |
|---|---|
| `GetStatus` | A snapshot: version, FIPS mode, start time, counters, sources, publications, targets, orphans, held deletes, configuration version, views, what the engine is busy with, quarantined entities per view (`quarantined`), and the process's CPU and memory (`process`, Linux only) |
| `WatchStatus` | Snapshots every `interval_ms` (at least 100 ms; 0 means one second) until the client disconnects |
| `ListDeadLetters` | Failed exports waiting to be retried |
| `ListQuarantined` | Entities whose exports a failing rule (`on_error: quarantine`) holds back, with the attribute, its error and since when (`view`, or every view; `limit`, 0 meaning 1 000) |
| `StartRun` | Run a reconciliation (`RUN_KIND_RECONCILE`, optionally one publication) or a full sync (`RUN_KIND_FULL_SYNC`) now; returns when done |
| `ApproveDeletions` | Let deletes held by a deletion threshold through once. A publication is reconciled at once; a source applies them at its next full read |
| `CheckDrift` | Read a publication's entries (or every checked publication's) back from the target and act by policy |
| `RetryDeadLetters`, `DiscardDeadLetter` | Export dead letters again now; remove one without retrying |
| `GetConfig` | The running configuration (YAML; secrets appear only as references) and its version label |
| `ApplyConfig` | Validate, save and reload with a new configuration, then run a full sync. The answer comes before the reload |
| `SimulateConfig` | Dry-run a configuration on a copy of the store: what each target would receive. One at a time |
| `TestConnector`, `DiscoverSchema` | Connect with one connector of a candidate configuration; list what it holds. Write nothing |
| `PreviewConfig` | Run a candidate over a sample of records in a scratch store |
| `SearchEntities`, `GetEntity`, `GetEntityHistory` | The read-only object explorer |
| `ListUnjoined`, `JoinRecord`, `UnjoinRecord` | Correlation review and decisions by hand |

Errors are gRPC statuses: `PERMISSION_DENIED` for a command or reveal on a listener that does not
allow it, `UNAVAILABLE` when the engine is busy with a long operation (a command waits up to 10
seconds first), has too many commands waiting, or is stopping, `INVALID_ARGUMENT`, `NOT_FOUND`,
and `INTERNAL` for a command that failed.

The `syncd` command line wraps these calls (`syncd status`, `dlq`, `reconcile`, `full-sync`,
`approve`, `retry`, `discard`, `check-drift`, `apply-config`, ...). `syncd quarantine` reads the
store directly (read-only), not through the API. On the engine's host the command line uses the
control socket when it is configured, without a certificate; on Kubernetes,
`kubectl exec syncd-engine-0 -- syncd status` (for a release named `syncd`) does the same. See
[Operator guide](operator-guide.md). For its certificates, see
[Security](security.md#the-engine-control-api).

The source also defines `syncd.bridge.v1.JdbcBridge` (`proto/syncd/bridge/v1/jdbc.proto`), the
protocol between the engine and the JDBC bridge. It is internal to that connector; see
[Connectors](connectors.md).

## Prometheus metrics

When the engine's host configuration sets `api.metrics_listen` (for example `127.0.0.1:9464`), the
engine serves `GET /metrics` there in the Prometheus text format, over plain HTTP, without
authentication. It carries counters and states only, no attribute values or configuration. It cannot
be set from the console. The engine's container image sets it to `0.0.0.0:9464`, and the Helm
chart's `engine.metrics.serviceMonitor.enabled` adds a `ServiceMonitor` for it.

Added in 0.3.0:

| Metric | Type | Meaning |
|---|---|---|
| `syncd_view_quarantined{view}` | gauge | Entities whose exports a failing rule (`on_error: quarantine`) holds back; 0 for views with none |
| `process_cpu_seconds_total` | counter | User and system CPU time of the engine process; its rate is the CPUs in use |
| `process_threads` | gauge | Threads of the engine process |
| `process_open_fds` | gauge | Open file descriptors of the engine process |

The shipped alert rules gain `SyncdQuarantined` (`syncd_view_quarantined > 0` for 30 minutes,
warning). The process metrics, like `process_resident_memory_bytes` and
`syncd_memory_limit_bytes`, are served on Linux only. All the metrics, the alert rules and how to
scrape them are in the [Operator guide](operator-guide.md).
