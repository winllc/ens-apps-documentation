# Architecture

## Components

```mermaid
flowchart TB
    subgraph Browser
        UI["React 19 single-page app<br/>React Router · TanStack Query<br/>shared/ validation + DN helpers"]
    end
    subgraph web["web container (nginx, UBI 9, uid 1001)"]
        NG["Static files /usr/share/nginx/html<br/>SPA fallback to index.html<br/>/api/ reverse proxy"]
    end
    subgraph api["api container (Spring Boot 4, Java 21, uid 185)"]
        SEC["Spring Security<br/>session · CSRF header filter · ROLE_ADMIN"]
        CTRL["REST controllers /api/**"]
        SVC["Services<br/>Auth · X509Auth · Entry · Permission · FormValidator<br/>Schema · DirectoryOptions"]
        MET["Metrics engine<br/>Analysis service"]
        JOBS["JobService<br/>metrics pool · analysis pool"]
        STORE["ConfigStore<br/>AnalysisResultStore · AuditLog"]
        LDAPGW["LdapDirectory gateway<br/>UnboundID connection pool"]
        EMB["EmbeddedDirectory<br/>(memory mode only)"]
    end
    LDAP[(LDAP server)]
    FS[("DATA_DIR")]

    UI -->|"same origin, DSP_SESSION cookie,<br/>X-DSP-Request: 1"| NG
    NG -->|"API_URL"| SEC --> CTRL --> SVC
    CTRL --> MET --> JOBS
    SVC --> LDAPGW
    MET --> LDAPGW
    SVC --> STORE
    MET --> STORE
    STORE --> FS
    LDAPGW -->|"ldap:// ldaps:// StartTLS"| LDAP
    LDAPGW -.->|loopback| EMB
```

| Component | Responsibility | Source |
|---|---|---|
| Web client | Pages for directories, entries, profile, metrics and admin. Client-side validation mirrors the server | `web/src`, `shared/src` |
| nginx | Serves the build, falls back to `index.html` for client routes, proxies `/api/`, and sets `X-Forwarded-*`, `X-DSP-Proxy-Secret` and (mTLS) `X-SSL-Client-Cert` | `web/nginx` |
| Security | Session-based authentication, custom CSRF header filter, admin role on `/api/admin/**`, JSON 401/403 errors | `config/SecurityConfig`, `config/RequestHeaderCsrfFilter` |
| Auth | Finds the user with the service account, verifies the password with an LDAP bind, resolves groups and admin status, throttles failures | `service/AuthService` |
| X.509 auth | Extracts the certificate (servlet, or a header from the authenticated proxy), validates it, and maps it to one user | `x509/*` |
| Entries | List, search, sort, page, CRUD, rename, namespace enforcement, self-service | `service/EntryService`, `service/FormValidator` |
| Permissions | Effective access per definition | `service/PermissionService` |
| Schema | Pull `subschemaSubentry`, parse imports, merge custom definitions, lint forms | `schema/*`, `ldap/SchemaMapper` |
| Metrics | Widget evaluation, paged scans shared between charts that read the same directory, transforms, caching, overviews | `metrics/*`, `service/DataSources` |
| Analysis | Comparisons and quality checks, findings paging, stored latest results | `analysis/*` |
| Jobs | Background execution, progress, cancellation, deduplication, retention | `jobs/*` |
| Config store | Single JSON document with atomic writes; with a shared database, a versioned document in `dsp_document` | `store/ConfigStore`, `store/DemoConfigSeeder` |
| Audit | The `ens-audit/1` store (hash-chained monthly JSON Lines files with a single synchronized writer, or `dsp_audit_event` with a shared database), the `ens.audit` logger, delivery to Splunk HEC and syslog, retention, and searches over the files | `audit/*` |
| LDAP gateway | Pooled service-account connections (UnboundID SDK), TLS options, timeouts | `ldap/LdapDirectory` |
| Shared database | Only with `DSP_DATABASE_URL`: the connection pool (Flyway migrations), Spring Session JDBC with an allow-list for session attributes, cluster locks (ShedLock on `dsp_shedlock`), the versioned shared documents, and the election of the one instance that runs the audit forwarders | `cluster/*`, `audit/JdbcAuditStore` |
| Embedded directory | UnboundID in-memory server on a loopback port with demo schema and data | `ldap/EmbeddedDirectory`, `resources/demo/*` |

## Request flow: editing an entry

```mermaid
sequenceDiagram
    participant B as Browser
    participant N as nginx (web)
    participant A as API
    participant L as LDAP server
    participant F as DATA_DIR

    B->>N: PUT /api/directories/{id}/entry?dn=...<br/>Cookie DSP_SESSION, X-DSP-Request: 1
    N->>A: proxy (X-Forwarded-For = client address)
    A->>A: CSRF header check, session → SessionUser
    A->>A: effective access ≥ write? definition not read-only?
    A->>L: read current entry (service account)
    A->>A: DN inside namespace (base DN, scope, filter)?
    A->>A: FormValidator: writable fields, required, formats, options
    A->>L: modify (only changed attributes), modDN if the naming value changed
    A->>F: append audit event (before/after), fsync
    A-->>B: 200 updated entry
```

## Sign-in

1. `POST /api/auth/login` with username and password.
2. The API checks that the consent banner, if enabled, has been acknowledged, then the throttle
   (`LOGIN_MAX_FAILURES` per client address and username in `LOGIN_LOCKOUT_WINDOW`) and the
   username's characters.
3. The API searches `LDAP_USER_SEARCH_BASE` with `LDAP_USER_FILTER` as the service account. Exactly
   one entry must match.
4. The API binds as that DN with the password (empty passwords are rejected beforehand).
5. The API reads `cn`, `displayName`, `mail`, the username attribute and `memberOf`, and runs
   `LDAP_GROUP_FILTER` under `LDAP_GROUP_SEARCH_BASE`.
6. The API sets the admin flag from `ADMIN_USERS` / `ADMIN_GROUPS`.
7. The API rotates the session ID and stores the `SessionUser` in the HTTP session.

Certificate sign-in (`POST /api/auth/x509`, or automatically through `GET /api/auth/me`) replaces
steps 1–4 with certificate validation and mapping. See [Security](security.md#x509-client-certificates).
Single sign-on replaces them with the identity provider's answer, mapped to exactly one entry by
`SSO_*_USER_FILTER`; see [Single sign-on](single-sign-on.md).

A call to the integration API (`/api/v1`) does not sign in: the API token is checked on every call,
and the call runs as the token's creator, with their rights read again from the directory. See
[API reference → API tokens](api-reference.md#api-tokens).

## Persistence

| Data | Store | With a shared database (`DSP_DATABASE_URL`) | Lifetime |
|---|---|---|---|
| Directory entries | LDAP server | LDAP server | Permanent (the embedded demo server is in memory only) |
| Forms, definitions, grants, custom schema, schema cache, dashboards, analyses | `DATA_DIR/portal-config.json` | `dsp_document`, a versioned document | Permanent |
| Latest analysis results | `DATA_DIR/analysis-results/<id>.json` | `dsp_document` | Until the next run |
| Audit events | `AUDIT_DIR` or `DATA_DIR/audit/audit-YYYY-MM.jsonl` | `dsp_audit_event`, the same hash chain | Append-only; kept until `ENS_AUDIT_RETENTION_DAYS` removes them (default: never) |
| License and installation id | `ENS_LICENSE_STATE_DIR` (the license volume) | `ens_license_*` | Permanent |
| Sessions | API memory | `spring_session` (Spring Session JDBC) | `ENS_SESSION_IDLE_TIMEOUT` idle, `ENS_SESSION_ABSOLUTE_TIMEOUT` at most, or (without a shared database) until restart |
| Login throttle | API memory | `dsp_login_failure` | `LOGIN_LOCKOUT_WINDOW` (15 minutes) |
| Background tasks and results | API memory | Progress and results in `dsp_job`; the task runs on the instance that started it | `JOBS_RETAIN_MINUTES`, or until restart |
| Metric cache | API memory | Each instance's memory | `METRICS_CACHE_SECONDS` |
| Directory-search drop-down cache | API memory | Each instance's memory | per field, 30 s by default |
| API tokens (SHA-256 hashes) | `DATA_DIR/api-tokens.json` | The shared database | Until revoked or expired |
| Webhooks (with signing secrets, encrypted with `DSP_SECRETS_KEY` from the next release) and the delivery outbox and log | `DATA_DIR/webhooks.json`, `webhook-deliveries.json` | `dsp_document` and `dsp_webhook_delivery` | `WEBHOOK_LOG_RETENTION` for finished deliveries |
| Single sign-on requests in flight | API memory | `dsp_sso_request` | Ten minutes |
| Per-address limits: failed API tokens, single sign-on starts (next release) | API memory | `dsp_rate_limit` (Flyway V4); a replica counts in its own memory while the database fails | A minute |

Without a shared database, sessions, tasks and caches live in memory, and the configuration and
audit stores have a single writer, so run **one** API instance. With one, the API creates its
tables itself (Flyway) and several instances can run. See
[High availability](availability-and-sizing.md#high-availability).

## Background jobs

```mermaid
stateDiagram-v2
    [*] --> Queued: submit (≤ JOBS_MAX_ACTIVE_PER_USER)
    Queued --> Running: worker free (metrics or analysis pool)
    Running --> Succeeded
    Running --> Failed
    Running --> Cancelled: POST /api/jobs/{id}/cancel
    Queued --> Cancelled
    Succeeded --> [*]: after JOBS_RETAIN_MINUTES
    Failed --> [*]
    Cancelled --> [*]
```

- Metric requests wait up to `METRICS_WAIT_MILLIS`. A quick result returns `200`; otherwise the
  API returns `202 {"pending": true, "job": {...}}` and the client follows the job.
- Analyses always return `202` with a job.
- Identical requests (same dashboard version, overview or analysis) join the running job instead of
  starting a new one. A recent result is reused for 15 minutes, or for `METRICS_CACHE_SECONDS` if
  that is longer.
- Jobs belong to the user who started them. Other users get `404` for them.

## Code layout

This section and the next describe the source code, for engineers reviewing the product. You do
not need the source to install or run the portal: install it from a release, as described in
[Installation](install.md).

| Path | Contents |
|---|---|
| `backend/` | Spring Boot service, package `com.winllc.dsp`: `ldap/`, `schema/`, `service/`, `web/` (controllers, DTOs, validation), `store/`, `x509/`, `sso/` (single sign-on), `integration/` (the `/api/v1` API and its tokens), `webhooks/`, `transfer/` (import and export), `metrics/`, `analysis/`, `jobs/`, `audit/`, `cluster/`, `license/`, `config/` |
| `backend/src/main/resources` | `application.yml`, `application-mtls.yml`, `demo/` (ACME schema LDIF, demo data, demo `portal-config.json`) |
| `shared/` | TypeScript types, DN utilities, validation and schema helpers used by the client |
| `web/` | React client: `src/pages` (user, metrics, admin), `src/components` (including SVG charts), `nginx/` |
| `deploy/openldap/` | Demo OpenLDAP image (`slapd.conf` template, ACME schema, LDIF) |
| `deploy/package/` | Templates for the docker and native deployment packages |
| `scripts/` | Release packaging, and `generate-dev-certs.sh` (also shipped in the Docker package) |

## Testing

Each release is tested before it is published. In the source tree, `npm test` runs the backend JUnit + MockMvc tests against the embedded LDAP server, then the web
Vitest tests. The backend tests cover sign-in and throttling, CSRF, permission enforcement
(including white pages and namespace confinement), CRUD and renames, form validation,
directory-search drop-downs, schema pull/import/export, the audit log, metrics, jobs, analyses,
banners and X.509 parsing, validation and mapping.
