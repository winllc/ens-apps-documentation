# Security

This page summarises syncd's security controls and the settings that affect them: the
console's sign-in, sessions, roles and API tokens, its HTTPS, the engine's control API,
secrets, password synchronisation, webhooks and other outbound connections, the audit trail,
backups, high availability, the systemd hardening, the container images and Helm chart, and the
supply chain. It describes syncd 0.5.0 (commit `a9d17c6`).

The source repository's `SECURITY.md` says how to report a vulnerability (GitHub's private
vulnerability reporting) and that fixes go to the latest minor version only; its
`docs/SUPPORT.md` says what each kind of version number promises. 0.5.0 also adds a threat model
of password synchronisation, `docs/security/password-sync-threat-model.md`, which is a draft.

## Trust model

- **The engine holds the credentials.** It connects to every source and target with the
  accounts its configuration names. The configuration never holds a secret: each password or
  token is a reference to an environment variable (`password: { env: NAME }`), a file
  (`{ file: /path }`) or, from 0.5.0, a secret store the engine asks itself (`vault`,
  `aws_secret`, `azure_keyvault`; see [Engine secrets](#engine-secrets)). On a packaged install,
  the variables are in `/etc/syncd/engine.env`, readable by root and the `syncd` group only. On
  Kubernetes, they come from a Secret you name (`engine.envFromSecret`).
- **The engine also holds the password keys.** With password synchronisation on, the engine's
  data directory holds the private keys that open every captured password change, and the key
  of the CA that admits capture agents. From 0.4.1 they can be sealed with a passphrase
  (`SYNCD_PASSWORD_KEY_PASSPHRASE_FILE`), and from 0.5.0 that passphrase can itself be wrapped by
  a KMS key; without a passphrase they are files in the clear, protected by their mode (0600)
  only. See [Password synchronisation](#password-synchronisation).
- **The console holds no source or target passwords.** It stores configuration versions
  (references only), accounts, sessions, the audit trail, API tokens (as hashes), the license
  (signed, not secret), and, sealed with the console's master key, each engine's control API
  client key, each webhook's URL and signing secret, the secret values of its environments and,
  from 0.5.0, its configuration signing key.
- **The control API is the engine's front door.** Anyone who can call its commands can run,
  approve, queue password changes, take a backup of the whole store, or read sensitive values,
  and, with the `configure` tier, apply a configuration. Off loopback it requires mutual TLS.
  From 0.5.0 each client certificate gets a tier (`read`, `operate` or `configure`) in
  `api.access`: a new install is **strict**, so only the console's certificate may apply a
  configuration, while an install upgraded from 0.4.1 stays **compatible** (every certificate
  of the CA may do everything) until its administrator opts in. See
  [The engine control API](#the-engine-control-api).
- **Editing configuration is privileged.** A configuration decides which systems the engine
  connects to and which of the host's secret references it sends them. Give the designer role
  accordingly (from 0.5.0 it can be limited to an environment or an engine), and consider
  [configuration approval](#configuration-approval-four-eyes), the
  [pre-publish hook](#the-pre-publish-hook), [signed configurations](#signed-configurations), and
  who may push to a repository the console takes configuration from
  ([Git sync](#git-sync-credentials)).

## Authentication

The console has local accounts; single sign-on through OpenID Connect and, from 0.5.0,
SAML 2.0; from 0.5.0, sign-in with an X.509 client certificate (a CAC or PIV card); and, from
0.4.0, [API tokens](#api-tokens) for other systems. From 0.5.0 a notice-and-consent banner, the
lockout, the password rules and session limits are settings (the
[sign-in baseline](#sign-in-baseline)).

| Control | Implementation |
|---|---|
| First sign-in | The first start creates `admin` with a random 24-character password, printed once and written to `$CONSOLE_DATA_DIR/initial-admin-password` (mode 0600). An installer can supply one with `CONSOLE_ADMIN_PASSWORD` or `CONSOLE_ADMIN_PASSWORD_FILE`; `admin`, empty, and passwords failing the policy are refused at startup. The first sign-in must set a new password, and the file is then deleted |
| Notice and consent | With `ENS_CONSENT_BANNER_ENABLED=true` (from 0.5.0) every sign-in method refuses until the browser has acknowledged the notice (`POST /api/v1/auth/consent`) in the last 15 minutes; the server enforces it, not the page. See [Sign-in baseline](#sign-in-baseline) |
| Forced password change | An account with a one-time password can call nothing but `POST /api/v1/auth/password` (and `GET /api/v1/auth/me`) until it sets a new one. Every other call answers 403 `password_change_required`. From 0.5.0 an account whose password is older than `ENS_PASSWORD_MAX_AGE_DAYS` is held to the same (`user.password_expired` in the trail) |
| Password policy | By default at least 12 characters; not the username and not `admin` (case-insensitive); not the current password. From 0.5.0 `ENS_PASSWORD_MIN_LENGTH`, the minimum of each character class, `ENS_PASSWORD_HISTORY`, `ENS_PASSWORD_MAX_AGE_DAYS` and `ENS_PASSWORD_REJECT_USERNAME` add to it. There is no minimum password lifetime |
| Password hashing | Argon2id (the `argon2` crate's defaults), or PBKDF2-HMAC-SHA512 with 210 000 iterations and a 16-byte salt in FIPS mode, stored as PHC strings. Salts and generated passwords come from aws-lc-rs. In FIPS mode Argon2id hashes are not accepted, so those accounts need a reset |
| Per-address throttle | 20 sign-in attempts per client address in 5 minutes, then 429 `too_many_requests`, audited as `auth.login` denied (`rate limited`). The counters are in memory and reset when the console restarts. Not a setting |
| Account lockout | By default 5 failed password sign-ins lock a local account for 15 minutes, audited with `"locked": true` (an `alert` in the trail); a successful sign-in resets the count. From 0.5.0 `ENS_LOCKOUT_ATTEMPTS`, `ENS_LOCKOUT_WINDOW` and `ENS_LOCKOUT_DURATION` set them (`0` duration: until an administrator unlocks the account on **Users**, or resets its password) |
| Uniform refusals | Every failed sign-in gets the same 401 `sign_in_failed`, whether the account is unknown, disabled, locked, has no local password, or is outside break-glass. An unknown account spends as long as a real password check. The audit trail records the real reason |
| Account recovery | `syncd-console admin reset-password [--user NAME]` on the host issues a one-time password. It needs the console's database settings (`console.env`). It refuses an account that signs in with single sign-on, as the **Users** page does, since a password would let it sign in without the identity provider (and its MFA). `--allow-single-sign-on` gives it one anyway, for recovery while the provider is down; the audit entry (`user.password_reset`) then records `"single_sign_on": true` |
| Single sign-on | OpenID Connect authorization code flow with PKCE, state and nonce; ID token signature (asymmetric algorithms only), issuer, audience and expiry checked. From 0.5.0, SAML 2.0 (the Web Browser SSO profile; signed responses only, with defences against signature wrapping, comment injection, DTDs and replay; tested against Keycloak only). Accounts are bound to the provider's subject or `NameID`, never taken over by username. Role assignments come from a claim or attribute at every sign-in. See [Single sign-on](single-sign-on.md) |
| Certificate sign-in | From 0.5.0, an X.509 client certificate (a CAC or PIV card) on the console's own HTTPS or through a trusted proxy's header: path validation to `ENS_X509_CA_FILE`, then OCSP and the CRL in the configured order, **failing closed** when the status cannot be learned. Tested with test PKIs only, never a real card or the DoD PKI. See [Single sign-on](single-sign-on.md#certificate-sign-in-cacpiv) |
| Break-glass | With OpenID Connect, SAML or certificate sign-in configured, an admin can limit local sign-in to accounts marked break-glass (**Users** → Local sign-in). At least one enabled break-glass console administrator with a password must remain; switching signs out the local accounts that can no longer sign in |
| Disabling an account | Takes effect at the account's next request: its sessions are refused, single sign-on and certificate sign-in cannot sign it in, and its API tokens stop working (within a minute) |

### Sign-in baseline

From 0.5.0 the console has the ENS family's sign-in baseline, under the family's `ENS_*` names.
Each is read at startup from `console.env`; unset, the console behaves as 0.4.1 did. **Users** →
Local sign-in shows the policy in effect, read-only.

| Setting | Default | Meaning |
|---|---|---|
| `ENS_CONSENT_BANNER_ENABLED` | `false` | A notice that must be acknowledged before any sign-in. `ENS_CONSENT_BANNER_TEXT` or `_TEXT_FILE` replace the standard DoD notice. The acknowledgement is audited as `auth.consent`, and the sign-in it leads to carries the same `consent` id and the session's id. Scripts that sign in with a password call `POST /api/v1/auth/consent` first; API tokens are not affected |
| `ENS_SESSION_MAX_PER_USER` | `0` (no limit) | Sessions one account may hold; one more ends its oldest (`auth.session.ended`) |
| `ENS_SESSION_ADMIN_IDLE_TIMEOUT` | unset | A shorter idle limit for console administrators (`admin` on everything) |
| `ENS_LOCKOUT_ATTEMPTS`, `_WINDOW`, `_DURATION` | `5`, `0`, `15` | Failed local sign-ins before a lock, the window they must fall in (`0`: until a sign-in succeeds), and the lock's length (`0`: until an administrator unlocks) |
| `ENS_PASSWORD_MIN_LENGTH` | `12` | At least 8 |
| `ENS_PASSWORD_MIN_UPPERCASE`, `_MIN_LOWERCASE`, `_MIN_DIGITS`, `_MIN_SPECIAL` | `0` | The fewest characters of each kind |
| `ENS_PASSWORD_HISTORY`, `ENS_PASSWORD_MAX_AGE_DAYS` | `0`, `0` | Recent passwords a new one must differ from; days after which a password must be changed |
| `ENS_PASSWORD_REJECT_USERNAME` | `false` | Refuse a password containing the username |
| `ENS_HSTS_MAX_AGE`, `ENS_HSTS_INCLUDE_SUBDOMAINS` | `31536000`, `false` | `Strict-Transport-Security` on the console's own HTTPS (see [CSRF and browser security headers](#csrf-and-browser-security-headers)) |

The lockout and password rules apply to local accounts only: accounts that sign in through an
identity provider or a certificate have no console password. The values a STIG review usually
asks for are in [FIPS 140-3 and DISA STIG](fips-and-stig.md#disa-stig-settings). Most of the
`ENS_*` names were chosen in syncd 0.5.0 and have not yet been compared with the other ENS
products' settings.

### Configuration approval (four eyes)

An admin can require it (**Users** → Configuration approval). A version is then published only
after a designer or admin other than its author approves it. The database enforces it: a
constraint refuses an approval by the author, and a trigger refuses any change to an approval
once given. Versions published before can be published again without one, so a rollback is
never held up. Approvals and changes of the setting are audited (`config.approve`,
`settings.config_approval`).

Approval applies to every way the console publishes: the **Publish** button, the API with an
API token (`configs:publish` can publish, never approve), a promotion (the version then waits
on each target engine) and Git sync's `publish` mode (the version waits for approval).
Approval is the console's gate, so it holds only if configurations reach the engine through the
console alone. From 0.5.0 the engine enforces that: in strict access mode only the console's
certificate may apply a configuration, and with [signed configurations](#signed-configurations)
the engine applies only what the console signed. On an engine still in compatible mode (an
install upgraded from 0.4.1 that has not opted in), `syncd apply-config`, or any holder of a
client certificate, can still apply a configuration directly; the console then reports it
(`config.unpublished-running`). The second pair of eyes must be a designer or admin whose role
reaches the version's engine.

## API tokens

From 0.4.0, scripts, pipelines and SIEM collectors call the console's API with a bearer token
(`Authorization: Bearer sdt_…`) instead of a session. Administrators create and revoke them on
**Integrations**; auditors see the list. The scopes, settings and endpoints are in the
[API reference](api-reference.md#api-tokens).

| Control | Implementation |
|---|---|
| The token | `sdt_` and 32 random bytes (base64url), from aws-lc-rs. Shown once, when it is created. The database keeps its SHA-256 and a four-character prefix; the token is never written to a log, the audit trail or a session |
| Scopes | `engines:read`, `configs:read`, `configs:write`, `configs:publish`, `runs:run`, `audit:read`, `environments:read` (from 0.4.1), `admin`. Each needs a role of the creator, to be granted and at every use |
| Limits | From 0.5.0 a token can be limited to an environment or to named engines. The creator must be an administrator of all the token reaches (everything, for a token without a limit). A limited token does nothing on the console as a whole (no audit pull or export, no Git sync), and an engine outside its limit answers `404` |
| Never more than its creator, now | Each use reads the creator's account and role assignments (a good answer is reused for at most a minute and dropped when the account changes; a refusal never is): a creator that is gone or disabled refuses the token, a demoted creator's token loses what the new roles cannot do, and one whose creator was moved to another environment reaches nothing |
| Endpoints that stay with people | Accounts, settings, tokens, webhooks, changes to environments, approvals (of versions and of WebAssembly modules), simulations, previews, connector checks, the object explorer and lineage, scheduled reports, the license, the support bundle and every sign-in take a session only: a token gets `403 insufficient_scope` |
| Lifetime | At most `CONSOLE_API_MAX_TOKEN_LIFETIME` (`365d`): the expiry of a token created without one, the most that may be asked for, and the age at which any token stops working |
| Rate limit | `CONSOLE_API_REQUESTS_PER_MINUTE` (300) per token, per console process; then `429` with `Retry-After` |
| No session, no cookie | A bearer request never reads or sets the session cookie, so tokens are not exposed to cross-site request forgery |
| Turning tokens off | `CONSOLE_API_TOKENS_ENABLED=false` refuses every token without deleting any |
| Audit | `api.token-created`, `api.token-revoked`, `api.token-refused` (at most once a minute per token or client address), `api.request` for every request that changes something, `api.token-used` once an hour per token for reads. The actor is `api-token:<id>`, of type `service` |

Treat a token like a password: keep it in a secret store or a file readable by the job that uses
it (`syncd config promote --token-file`), give each system its own with the narrowest scopes and
an expiry, and revoke it when the system goes. A token's creator is an account: disabling the
account disables its tokens.

## Sessions

- A session is a random 32-byte token in the cookie `syncd_session`: `HttpOnly`,
  `SameSite=Strict`, `Path=/`, and `Secure` except in development mode (`SYNC_DEV_MODE`). The
  database keeps only the token's SHA-256, so reading the `sessions` table hands out no live
  session. The audit trail names a session by a further hash of that, so one session's events can
  be grouped without naming it.
- A session ends after `CONSOLE_SESSION_IDLE_MINUTES` without activity (default 30) or
  `CONSOLE_SESSION_MAX_HOURS` after sign-in (default 12). Activity extends the idle window.
- Signing out deletes the session. Changing a password signs out the account's other sessions.
- Live status streams re-check their session every 30 seconds and end when it is over.
- From 0.5.0 `ENS_SESSION_MAX_PER_USER` limits the sessions one account may hold (a sign-in
  beyond it ends the oldest), and `ENS_SESSION_ADMIN_IDLE_TIMEOUT` gives console
  administrators a shorter idle limit, in the UI and on the live status stream alike. Both are
  off by default. There is no warning before a session ends.
- A change at an identity provider (or to what a certificate maps to) reaches an account at its
  next sign-in, when its role assignments are set again; disabling the account in the console
  cuts it off at once.
- Browsers keep `Secure` cookies only over HTTPS or on localhost. The console warns at startup
  when it listens beyond loopback over plain HTTP, with no certificate of its own
  (`CONSOLE_TLS_CERT`) and no `https://` `CONSOLE_PUBLIC_URL`, and the sign-in page says so
  when the browser drops the cookie.

## CSRF and browser security headers

- **No CSRF token or request header check.** The defence is the session cookie's
  `SameSite=Strict`: browsers do not send it on requests that another site starts. Calls that
  take a body accept JSON only (`Content-Type: application/json`), which a cross-site form
  cannot send without a CORS preflight, and the console sends no CORS headers. Some calls take
  no body (publish, approve, reset password, sign out), so they rely on `SameSite` alone; use
  a browser that enforces it. API token requests carry no cookie, so they are not exposed.
- **Headers** on every response:

| Header | Value |
|---|---|
| `Content-Security-Policy` | `default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'` (inline styles are allowed because the Monaco editor sets them at run time; scripts only from the console) |
| `X-Content-Type-Options` | `nosniff` |
| `X-Frame-Options` | `DENY` |
| `Referrer-Policy` | `no-referrer` |
| `Cache-Control` | `no-store`, except the UI's hashed assets (cached for good) and its other files (`no-cache`) |

- **HSTS.** From 0.5.0 the console sends `Strict-Transport-Security` on its own HTTPS
  (`CONSOLE_TLS_CERT`), never over plain HTTP: `max-age` from `ENS_HSTS_MAX_AGE` (a year by
  default; `0` sends none), with `includeSubDomains` when `ENS_HSTS_INCLUDE_SUBDOMAINS=true`.
  Behind a TLS proxy the proxy sends it: the shipped nginx configuration now has the line
  (`max-age=31536000`); an ingress controller needs it configured.
- **SAML's cookie.** The cookie that ties a SAML sign-in to the browser that started it is
  `Secure; SameSite=None`, because the provider's answer is a cross-site POST; the request it
  answers is kept for 10 minutes and used once.
- Exports (reports, lineage, the audit trail, the support bundle) are always downloads
  (`Content-Disposition: attachment`). A CSV export guards against formula injection: a cell
  starting with `=`, `+`, `-`, `@`, a tab or a carriage return gets a leading apostrophe.
- Error responses carry a code and a message only. Internal errors answer `internal error`; the
  details go to the console's log.

## Authorization

From 0.5.0 an account holds **role assignments**: each a role and what it applies to. Before
0.5.0 every account had one role on everything; an upgrade gives each account its former role
on everything. Assignments are checked on every API call, before the handler runs and before
the body is read; every operation of the API is declared once with its rule, so none can exist
without one. An API token is checked against its scopes, its limit and its creator's
assignments.

| Role | Can |
|---|---|
| `viewer` | Read engines, status, held changes, dead letters, data quality, configuration versions, diffs and the environments' names, the object explorer and lineage exports, the reconciliation report export, rule previews and the function catalogue |
| `auditor` | What a viewer can, plus the audit trail (its verification, export and outputs), the lists of API tokens, webhooks and their deliveries, scheduled reports, and the environments' values (secret values never) |
| `operator` | What a viewer can, plus runs (reconcile, full sync), approvals of held deletions and changes, group reviews, drift checks, unmanaged-entry checks and adopting an entry, dead-letter retry and discard, joining, unjoining and accepting correlation suggestions, and scheduled reports of their own |
| `designer` | What a viewer can, plus validating, saving, importing, simulating, previewing, approving, publishing and promoting configuration versions, uploading and approving WebAssembly modules (never one's own), connector checks, and **Sync now** for Git |
| `admin` | Everything, including accounts, sign-in settings, configuration approval, banners, registering and removing engines, environments and their values, API tokens, webhooks, the license, everyone's scheduled reports and the support bundle |

| Scope | Written | Covers |
|---|---|---|
| everything | `operator` or `operator@*` | Every engine and environment, and the console itself |
| an environment | `operator@prod` | The engines running in it, and its values |
| engines | `designer@engine:hr-1` (several: `designer@engine:hr-1,hr-2` in a role map) | Those engines, wherever they run; not their environment's values |

- **An engine outside every assignment does not exist** for the caller: `404`, as for an id
  never used, and it is in no list. What the caller's roles could not do on any engine is `403`.
- **Global only, on purpose:** accounts, settings, webhooks, Git sync, registering, moving and
  removing engines, the license, and the audit trail as a whole (its verification, export and
  pull API, because a chain cannot be verified in part). A **console administrator** is an
  account with `admin` on everything; only those manage accounts and settings and count for the
  last-admin and break-glass rules. Views are not a scope: the smallest scope is an engine.
- **Auditors of an environment or an engine** see on the Audit log page the entries recorded
  about it, from 0.5.0 on. Sign-ins, account changes, background tasks' events and everything
  recorded before 0.5.0 carry no engine and show to auditors of everything only.
- **Assignments come from the identity provider** for accounts that sign in through one, or a
  certificate: role maps take scopes (`syncd-prod-ops=operator@prod`), and from 0.5.0 **every
  entry that matches adds its role** (in 0.4.1 the first match won; review maps that relied on
  their order). See [Single sign-on](single-sign-on.md#roles).
- **Each decision is recorded** with the assignment it was made under (`details.assignment`,
  inside the hashed event), and sign-in records carry the account's `roles`.

Before 0.4.1 every role, and any token with `configs:read`, could read the environments' plain
values through the API (#145). From 0.4.1 they need the admin or auditor role, as the console's
pages showed them, or a token with `environments:read`; every role still reads the names
(`GET /api/v1/environment-names`). A configuration rendered for display (the editor's check,
the Compare tab) still shows the plain values it uses, never the secret ones.

Revealing sensitive values is separate: see [Sensitive attributes](#sensitive-attributes-and-reveal).
The per-route roles and token scopes are in the [API reference](api-reference.md#console-rest-api).

## HTTPS for the console

The console serves plain HTTP unless it is given a certificate. For access from other
machines it needs HTTPS, for its `Secure` session cookie: its own, or a TLS proxy in front of
it.

### The console's own HTTPS

With `CONSOLE_TLS_CERT` and `CONSOLE_TLS_KEY`, the console serves HTTPS itself on
`CONSOLE_LISTEN`. Each needs the other.

| Aspect | Behaviour |
|---|---|
| Files | `CONSOLE_TLS_CERT`: a PEM certificate followed by its chain. `CONSOLE_TLS_KEY`: its private key, PEM. Readable by `syncd-console` (on a packaged install, `root:syncd-console`, key mode 0640) |
| Protocols | TLS 1.2 and 1.3 (rustls's safe defaults), on the process crypto provider, so FIPS mode applies. HTTP/1.1 only (ALPN `http/1.1`); no HTTP/2 |
| Client certificates | None requested, unless certificate sign-in is on in direct mode (`ENS_X509_ENABLED=true`, `ENS_X509_MODE=direct`): the console then asks each browser for a certificate, optionally, and judges it only at the sign-in endpoint (path, revocation, the account), so a browser without one still connects |
| Handshakes | 10 seconds each, in a task of its own, so a client that stalls holds up no other connection |
| Startup | A certificate or key that does not load, or a key that does not match, stops the console |
| Renewal | The files are checked every minute and loaded again when either changes, without a restart (certbot's deploy hook, cert-manager). A renewal that does not load (half-written, say) is logged as a warning and the previous certificate kept, until the next check |
| Port | The packaged console does not run as root, so it cannot listen on 443. Use a port such as 8443, or nginx for 443 |
| HSTS | From 0.5.0, sent (`ENS_HSTS_MAX_AGE`, a year by default; see above) |

Set `CONSOLE_PUBLIC_URL` to the `https://` address for single sign-on. See
[Installation](install.md).

### Behind a reverse proxy

Put nginx with TLS in front of the console for port 443, a certificate shared with other
sites, or HTTP/2. The release ships `share/nginx/syncd.conf`, which redirects HTTP to HTTPS,
allows TLS 1.2 and 1.3, sends HSTS (from 0.5.0), sets `X-Forwarded-For` to the connection's
address (replacing whatever the client sent), and passes server-sent events through
unbuffered. For certificate sign-in through it, uncomment its three certificate lines (they
ask for a certificate, leave the verdict to the console, and always set the
`X-SSL-Client-Cert` header so that one a client sends never arrives) and set
`ENS_X509_MODE=proxy` with `ENS_X509_TRUSTED_PROXIES`. On Kubernetes, the Helm chart's ingress
does the same job. See [Installation](install.md).

`CONSOLE_TRUSTED_PROXIES` lists the proxies (addresses or CIDR ranges) whose `X-Forwarded-For`
names the client. The console reads the header from the right, past trusted proxies, to the first
address that is not one. A connection from anywhere else is taken at its own address, whatever
header it sends. The client address goes to the audit log and to the per-address sign-in limit;
without the setting, every request seems to come from the proxy and all users share one sign-in
budget. List only proxies you run.

Keep `CONSOLE_LISTEN` on `127.0.0.1` when nginx runs on the same host.

## Secrets

### Engine secrets

Source and target passwords, SCIM, Entra ID and REST credentials, bridge and Debezium tokens,
the HA lease's database URL and S3 keys are read from where their reference points at the
moment they are needed; they never appear in the configuration, in `GetConfig`, in
configuration exports, in `syncd config diff`, or in a support bundle. A configuration moved
between consoles lists the variables and files the new host must provide.

From 0.5.0 a reference can also name a secret store, which the engine asks itself, over HTTPS
on the process's TLS provider (the FIPS module in a FIPS build) and without a vendor SDK:

| Reference | Store | How the engine signs in (settings in `engine.env`) |
|---|---|---|
| `{ vault: { path, key } }` | HashiCorp Vault, KV 1 or 2, with namespaces | `VAULT_ADDR`, `VAULT_CACERT`, and a token file (`SYNCD_VAULT_TOKEN_FILE`), AppRole (`SYNCD_VAULT_ROLE_ID`, `SYNCD_VAULT_SECRET_ID_FILE`) or Kubernetes auth (`SYNCD_VAULT_KUBERNETES_ROLE`) |
| `{ aws_secret: { id, key } }` | AWS Secrets Manager | The usual `AWS_*` variables, a web identity token (IRSA, EKS Pod Identity), the shared credentials file, or the EC2 instance's role |
| `{ azure_keyvault: { vault, name } }` | Azure Key Vault | A federated token (AKS workload identity) or the VM's managed identity |

- A value read is reused for the reference's `cache_secs` (300 by default) and dropped when a
  connection made with it fails, so a rotated credential is picked up without a restart. Open
  database connections keep their credential until the database closes them; SCIM's bearer
  token and basic password are not read again on a `401`.
- An engine that cannot read a reference at start does not start. Values are never logged.
- **Tested against doubles of Vault, AWS and Azure only**, never against the services
  themselves. Google Secret Manager is not built; Vault's dynamic secrets and certificate auth
  are not supported.

The passphrase of the password keys can be **wrapped by a KMS key**
(`SYNCD_PASSWORD_KEY_PASSPHRASE_KMS`, made with `syncd secrets wrap`): AWS KMS
(`awskms:<key>`), an Azure Key Vault key (`azurekv:https://<vault>/keys/<name>`) or Vault
transit (`vaulttransit:<mount>/<key>`). The KMS is asked at every start; if it cannot be asked,
or does not unwrap the file, the engine does not start: there is no fallback.

### Console secrets and the master key

The console encrypts stored secrets with envelope encryption:

- each secret gets its own random 256-bit data key, and is sealed with AES-256-GCM;
- the data key is wrapped with the master key (AES-256-GCM);
- both carry the secret's location (for example `engine:<name>:client_key`,
  `webhook:<id>:secret`, `environment:<name>:<key>`) as additional data, so a ciphertext copied
  to another row does not open;
- all of it goes through aws-lc-rs, so FIPS mode covers it.

What it seals: each engine's control API client key; each webhook's URL and signing secret;
the values marked secret in the console's environments (see
[Configuration as code](config-as-code.md#environments)); and, from 0.5.0, the private half of
its [configuration signing key](#signed-configurations). None is returned by the API once
saved.

The master key is 32 bytes, from `CONSOLE_MASTER_KEY` (base64) or `$CONSOLE_DATA_DIR/master.key`
(`/var/lib/syncd/console/master.key` on a packaged install), which the console creates (mode 0600)
on first start and logs a warning to back it up. On Kubernetes, the Helm chart generates it into
the `syncd-console` Secret and passes it as `CONSOLE_MASTER_KEY`. **Without it, stored secrets
cannot be decrypted.**

**Wrapped by a KMS key** (from 0.5.0, `CONSOLE_MASTER_KEY_KMS`): `master.key` then holds the
master key wrapped by an AWS KMS key, an Azure Key Vault key or a Vault transit key (written as
for the engine's passphrase, above), and the console asks the KMS to unwrap it at every start.
`syncd-console admin wrap-master-key --kms <key>` wraps an existing plain key file in place, with
the console stopped. If the KMS cannot be reached or does not unwrap the file, the console does
not start: it never falls back to another key, and makes no new one. A wrapped file without the
setting, the setting with a plain file, and `CONSOLE_MASTER_KEY` beside the setting are refused.
Back up the wrapped file and the access to the KMS key; a copy of the plain key kept offline is
the way back if the KMS key is lost. Tested against a double of AWS KMS only, and the Helm chart
has no values for it.

At every start the console checks the key against **one sealed secret of each kind** the
database holds (from 0.5.0): an engine's client key, a webhook's URL, a webhook's signing
secret, a secret environment value and the configuration signing key (the first of each, not
every secret of a kind). 0.4.1 stopped at the first one it found, so a database with secrets
sealed under two keys that 0.4.1 started may now refuse to start.

- if `master.key` is missing while the database holds sealed secrets, it does not create a new key
  over them;
- if the key does not open one of them (the wrong file, or the wrong `CONSOLE_MASTER_KEY`), it
  is refused, and the error names each kind it did not open.

Either way the console does not start, and says to restore the key. To start over on purpose, set
`CONSOLE_NEW_MASTER_KEY=true` (`--new-master-key`; Helm `console.newMasterKey`): the console starts
(with a new key if there is none), and the stored secrets are lost. Engines registered with mTLS
must then be registered again: `syncd-console admin register-engine --update` (or `replace: true`
on `POST /api/v1/engines`) seals a new client key over the one that no longer opens, keeps the
engine's id, and is audited as `engine.re-registered`. Without `--update`, the registration is
refused with that reason rather than reported as done. Webhooks need their URL saved again and a
new secret, secret environment values typing again, and the configuration signing key rotating
(`syncd-console admin config-signing-key`), with its new public key given to every engine that
requires signed configurations.

### Other secret files of the console

None appears in the support bundle. Most are read from their file each time they are used, so
they can be rotated without a restart:

| Setting | For | Read |
|---|---|---|
| `CONSOLE_OIDC_CLIENT_SECRET_FILE` | The single sign-on client secret | Once, at startup: restart the console after rotating it |
| `CONSOLE_PUBLISH_HOOK_SECRET_FILE` | The pre-publish hook's HMAC secret | At each use |
| `CONSOLE_CONFIG_GIT_SSH_KEY_FILE`, `CONSOLE_CONFIG_GIT_TOKEN_FILE` | Git sync's deploy key, or its HTTPS token | At each `git` command |
| `CONSOLE_SMTP_PASSWORD_FILE` | The mail server account's password | At each use |
| `ENS_AUDIT_SPLUNK_TOKEN_FILE` | The Splunk HTTP Event Collector token (or `ENS_AUDIT_SPLUNK_TOKEN`) | When the output connects, and again after a failed send |

### Git sync credentials

With `CONSOLE_CONFIG_GIT_URL` set, the console takes configuration from a Git repository, and with
`CONSOLE_CONFIG_GIT_PUSH_BACK=true` pushes to it. Whoever can push to that branch can propose
configuration, and with `CONSOLE_CONFIG_GIT_AUTO_PUBLISH=publish` publish it through the usual
gates (approval, test cases and golden tests, the ticket pattern, the hook): protect the branch
accordingly. The golden tests come from the same repository, so whoever can push can change
them too.

- Plain `http://` is refused at startup. Give either a deploy key (`ssh://`, with
  `CONSOLE_CONFIG_GIT_KNOWN_HOSTS_FILE` so the server's host key is checked) or a token
  (`https://`), not both.
- The credentials never go on a command line or into the clone: the token reaches `git` as an
  `Authorization` header through git's own configuration variables, and the SSH key and known
  hosts through an SSH configuration in a home directory of git's own under the clone
  (`<data dir>/git/home`).
- **Secrets never come from Git.** An environment file in the repository that holds a value
  marked secret, or a plain value for a key the console keeps as a secret, is refused
  (`config.git-refused`, in the trail with the keys, never the values, and as a webhook event).
- Git sync uses the host's (or image's) `git` and `ssh`, not syncd's crypto module: see
  [FIPS 140-3 and DISA STIG](fips-and-stig.md).

## The engine control API

The engine serves its gRPC control API on `api.listen` (default `127.0.0.1:7443`).

| Listener | Allowed |
|---|---|
| Plain text on a loopback address (no `api.tls`) | Read-only by default: status, the status stream, dead letters, held changes, quality failures, the running configuration, the object explorer and lineage without sensitive values, the reconciliation report, the password public key. `api.local_control: true` also allows commands; `api.local_reveal: true` also allows sensitive values. Any local account on the host can connect. In strict mode it stops at `operate` and may not set passwords |
| mTLS (`api.tls`: `cert_file`, `key_file`, `client_ca_file`) | Clients whose certificate chains to `client_ca_file`, each with the tier `api.access` gives it (below). Required for any address that is not loopback; the engine refuses to start otherwise |
| `api.control_socket` (a Unix socket path) | The engine's own user and root only: the socket is created under a temporary name, set to mode 0600, then moved into place. The packaged unit creates `/run/syncd` for it (`RuntimeDirectory`, mode 0750). In compatible mode it may do everything; in strict mode it reads, operates and sets passwords, and applies no configuration. The console can be registered at it (`unix:/run/syncd/control.sock`, without mTLS) only when it runs as a user that may open it; the packaged console runs as `syncd-console`, so it keeps mTLS |

### Access tiers

From 0.5.0 every RPC belongs to one of three tiers, each including the one before, checked in
one place before the handler runs (`RPC_TIERS` in `engine/crates/api/src/access.rs`; an RPC
missing from the table is refused to everyone):

| Tier | What it allows |
|---|---|
| `read` | The status, the lists (dead letters, held changes, quarantine, data quality, unjoined records, queued password changes), the running configuration, simulations, the reconciliation report |
| `operate` | Runs, approvals, drift and unmanaged-entry checks, adopting an entry, dead-letter actions, joins, group reviews, a person's own password change (the self-service page), revoking a capture agent, backups and HA snapshots, connector checks, schema discovery and previews, and sensitive values (`reveal`) |
| `operate` and `passwords` | Setting a password of the caller's choosing, and deciding who else can: `SubmitPasswords`, adding or binding a capture agent, rotating the password key or the agent CA, and `ForwardPasswordIngest` (the passive engine of a pair speaking for agents) |
| `configure` | `ApplyConfig` (the console's publish, `syncd apply-config`) and `SetLicense` |

`api.access` in `engine.yaml` names who has which tier:

```yaml
api:
  access:
    mode: strict
    configure: [syncd console]         # the console's certificate
    operate: [syncd command line]      # the command line's (cli.crt)
    read: []                           # e.g. a monitoring system's certificate
    passwords: []                      # who may set passwords, besides the control socket
```

- **Identities** are a client certificate's subject common name, one of its URI subject
  alternative names with a `uri:` prefix, or `*` for every certificate the client CA signed. A
  certificate has the highest tier it is listed under.
- **Strict mode**: a certificate gets the tier it is listed under, and nothing, not even the
  status, when it is listed nowhere. Each refusal is logged with the caller and the RPC. Setting
  passwords is a permission of its own: a certificate must also be listed under `passwords`
  (nobody is by default, and the console's certificate does not need to be). The control socket
  may set passwords (it is the engine's own user, who can read the password key itself); the
  plain-text loopback listener may not.
- **Compatible mode**, or no `access` section: every certificate the client CA signed may do
  everything, as before 0.5.0, including `cli.crt`, which the `syncd` group can read. The engine
  warns at every start, `syncd status` says `control API access: COMPATIBLE`, and the console
  says so on the engine's page.
- **A new install is strict** (the installer's `engine.yaml` and the container image's initial
  configuration), so `syncd apply-config` is refused there and configurations go through the
  console. **An upgraded install stays compatible** until `sudo syncdctl api-access strict` on
  the engine's host (which edits `engine.yaml`, validates, restarts, and puts the previous file
  back if the engine does not start strict); then import the running configuration in the
  console, since the engine refuses a publish that would take the `api` section away. The
  command does not edit an `api` section written on one line, and has no way back to compatible
  mode other than the copy it keeps. The Helm chart cannot set `api.access`.
- **Without the console**, a configuration can still be changed by root on the engine's host,
  by editing the file and restarting: the status then says the engine runs the file on the host,
  and the console reports it. There is no `--break-glass` flag on `syncd apply-config`.
- **Commands** are logged with their caller (the address and certificate common name, or the uid
  and pid on the control socket).
- **`Backup`, `PullSnapshot`** and the incremental snapshot calls hand out the whole store, the
  password keys and the agent CA's key (sealed, when a passphrase is set). They need `operate`
  and what a reveal needs (mTLS, the control socket, or `api.local_reveal: true`).
- **The passive engine of an HA pair** refuses commands, naming the active one.
- TLS goes through rustls on the process crypto provider, with its safe default protocol
  versions (TLS 1.2 and 1.3), and TLS handshakes time out after 10 seconds.

Switching an installation to strict mode is in the [Install guide](install.md#control-api-access) and the [Operator guide](operator-guide.md#control-api-access).

### Signed configurations

Tiers decide which certificate may apply a configuration; a signature decides which
configuration. From 0.5.0 the console signs every configuration it publishes, after its gates
passed, with an ECDSA P-256 key of its own (made on first use, sealed with the master key). An
engine with `engine.require_signed_config: true` and the console's public key in
`engine.config_signing_keys` applies nothing else, whoever sends it:

```yaml
engine:
  require_signed_config: true
  config_signing_keys: [/etc/syncd/pki/console-signing.pub]
```

- The signature covers the configuration's SHA-256, its version label and a random value the
  engine issues anew at every start and reload: it is for that text, on that engine, once.
- `sudo syncdctl api-access signing-key --from-console` installs the console's key on a
  single-host install (`--require` then refuses unsigned configurations); with the console
  elsewhere, `syncd-console admin config-signing-key --out FILE` on its host writes the key, and
  admins can read it from `GET /api/v1/settings/config-signing-keys`. On Kubernetes the chart
  mounts it (`engine.configSigning`). `config_signing_keys` without `require_signed_config`
  checks a signature when there is one and still takes unsigned configurations.
- **Rotation** takes no downtime: `--rotate` stages a key, the engines get it beside the old one,
  `--activate` switches; `--replace` makes a new key at once, for one that is lost or exposed
  (engines that require signatures refuse publishes until they have it).
- `syncd status` shows `signed by key <id>` for what runs.
- Signatures do not protect against root on the engine's host, who can edit the configuration
  file, the key file or the engine itself. The console's database and master key hold both the
  client key and the signing key: whoever has them is the console.

### What the console reports

From 0.5.0 the engine's status says who applied the configuration it runs (the caller, when, the
text's SHA-256 and whether it was signed), or that it is the file on the host. The console
compares that with the version it last published to the engine. When they differ (a
configuration applied with `syncd apply-config` or another client, or a file edited on the
host) it records `config.unpublished-running` in its audit trail, raises the
[webhook event](webhooks.md) of the same name, and shows a warning on the engine's page until a
version is published from the console again. It is told once per configuration. Engines before
0.5.0 report no hash and are compared by version label.

### The packaged certificates

The installer creates a CA of the host's own in `/etc/syncd/pki` (ECDSA P-256, valid for 10 years;
nothing outside the host trusts it) and issues:

| File | For | Readable by |
|---|---|---|
| `ca.crt`, `ca.key` | The control API's CA | `ca.key`: root only |
| `engine.crt`, `engine.key` | The engine's server certificate (`localhost`, `127.0.0.1`, `::1`) | `engine.key`: root and the `syncd` group |
| `console-client.crt`, `console-client.key` | The console's client certificate (common name `syncd console`, the `configure` tier in strict mode). The installer registers the engine with it, so the console holds the key sealed in its database | root and the `syncd-console` group |
| `cli.crt`, `cli.key` | The command line's client certificate (`syncd command line`, the `operate` tier in strict mode); also what the passive engine of an HA pair pulls snapshots with, unless `engine.ha.peer.cert_file` names another | root and the `syncd` group |

The packaged `engine.yaml` listens on `127.0.0.1:7443` with mTLS, so the console and the command
line both use certificates even on loopback. `sudo syncd status` (as root or a member of the
`syncd` group) finds `cli.crt` itself; `sudo syncdctl cli-cert` issues it on a host that lacks one.
`--api-cert`, `--api-key` and `--api-ca` (or `SYNCD_API_CERT`, `SYNCD_API_KEY`, `SYNCD_API_CA`) name
other files. `--api-key` is the certificate's private key file, not a token.

Membership of the `syncd` group therefore grants what `cli.crt` may do: in strict mode, reading
and operating the engine (runs, approvals, backups of the whole store, sensitive values), but
not applying a configuration or setting passwords; in compatible mode, full control. Keep it to
administrators either way. On an HA pair in strict mode, the certificate the passive engine
uses must also be listed under `passwords` on both hosts, so that agents' changes can be handed
on; a certificate of its own for the pair (`engine.ha.peer.cert_file`) keeps that power away
from the `syncd` group.

An HA pair on two hosts needs both hosts' certificates from one CA, each engine certificate
naming its host (see [Installation](install.md)): a certificate from that CA reaches either engine.

### Renewing the certificates

`syncdctl status` shows when the first of the control API's certificates expires
(`certificates:`), and warns within 60 days. The console's engine page warns from 60 days
before the console's client certificate for that engine expires (and shows it as expired
after), and the console API returns the date (`certificate_expires_at`).

```sh
sudo syncdctl renew-certs            # new certificates from the same CA
sudo syncdctl renew-certs --new-ca   # a new CA too
```

It issues the engine's certificate and the console's and the command line's client
certificates again (and the CA with `--new-ca`; it warns when the CA itself expires within
400 days), restarts the engine, gives the console its new credentials
(`syncd-console admin register-engine --update`, audited as `engine.update`; from 0.4.1 the
engine keeps its environment, where 0.4.0 moved it back to `default`), and restarts the console. The previous files are kept in `/etc/syncd/pki/before-renewal-<time>` (mode
0700), and put back if the engine does not start with the new ones. A `syncdctl restore` or
rollback that keeps the console database also keeps the current certificates, which the
database's credentials match.

### Certificates on Kubernetes

The Helm chart issues the control API's certificates itself, at install, with Helm's own
functions (ECDSA P-256 keys since chart 0.4.0; an install made with an older chart keeps its RSA
keys until the Secret is deleted), into the `syncd-pki` Secret (for a release named `syncd`;
otherwise `<release>-syncd-pki`):

| Key | For |
|---|---|
| `ca.crt`, `ca.key` | The chart's CA (`syncd control API CA`) |
| `engine.crt`, `engine.key` | The engine's server certificate, for its Service's names, `localhost` and, with `engine.ha.enabled`, each pod |
| `console.crt`, `console.key` | The console's client certificate (`syncd console`) |

They last `pki.days` (3650 by default) and are kept across upgrades and when the release is
uninstalled (`helm.sh/resource-policy: keep`), as the console's Secret is. The engine mounts only
`ca.crt`, `engine.crt` and `engine.key`, and the registration Job only `ca.crt`, `console.crt` and
`console.key` (mode 0440). The certificates' common names are `syncd console` and `syncd
engine`, which the image's initial configuration lists in its strict `api.access` (the engines'
own certificate under `passwords` too, for an HA pair); a release first installed before 0.5.0
keeps the configuration it has, and the chart cannot set `api.access`. The CA key stays in the
Secret, so anyone who can read Secrets in the namespace can issue a certificate with any name,
and so any tier: restrict that with RBAC. To
renew, delete the Secret and run `helm upgrade`; the engine restarts with the new certificates,
and the registration Job updates the console. There is no command-line client certificate:
`kubectl exec … -- syncd status` uses the engine's control socket.

## Password synchronisation

From 0.4.0 the engine delivers password changes to the accounts it manages: from capture points
in the directories (from 0.5.0: an OpenLDAP overlay and a 389 Directory Server plugin, with the
`syncd-capture` agent), from the console's self-service page, and as initial passwords of new
accounts. See [Password synchronisation](password-sync.md#security-properties) for how to run
it, and the source repository's `docs/security/password-sync-threat-model.md` for the threat
model, which is **a draft, not signed off**. **No penetration test** of password
synchronisation has been done; the plan makes both release gates for it.

### Cryptography

| Part | Implementation |
|---|---|
| Envelope | HPKE (RFC 9180, base mode): DHKEM(P-256, HKDF-SHA256), HKDF-SHA256, AES-256-GCM, implemented by syncd on aws-lc-rs primitives (so the FIPS module in a FIPS build). The key's id is the additional data; captured changes and self-service requests use different HPKE `info` strings, so one can never be taken for the other. Checked against the RFC 9180 test vector. Base mode with a static recipient key: no per-change forward secrecy |
| The engine's password keys | A P-256 key pair, created on first start as `password.key` in the data directory (`passwords.key_file`), mode 0600, sealed with the passphrase when one is set (below). Capture points get only the public half (`GetPasswordKey`). From 0.5.0 `syncd password rotate-key` makes a new current key; each previous one is kept beside it as `password.key.<id>` to open what was sealed to it, until it is retired after `passwords.rotation_overlap_days` (7) with nothing queued under it, or by `rotate-key --retire`. Nothing rotates on a schedule; `SyncdPasswordKeyOld` fires after a year |
| The agent CA | A P-256 key and a ten-year self-signed certificate (`password-agent-ca.key`, mode 0600, and `.crt` beside the password key), made with `rcgen` on aws-lc-rs. Its key is sealed like the password key. It issues each agent's client certificate (client authentication only, the subject is the agent's name whatever the request asked, `passwords.ingest.agent_cert_days`, 365 by default) and, at every start, the ingest listener's own server certificate. From 0.5.0 `syncd password rotate-agent-ca` makes a new one; the previous CA stays trusted for the overlap, and agents renew their certificates with the one they hold (`Renew`), keeping their key |
| The keys at rest | From 0.4.1, with `SYNCD_PASSWORD_KEY_PASSPHRASE_FILE` (a file holding the passphrase; `SYNCD_PASSWORD_KEY_PASSPHRASE`, the passphrase itself, is read when the file variable is unset), the key files are **sealed**: PBKDF2-HMAC-SHA256 (600,000 iterations, a random 16-byte salt) derives an AES-256-GCM key from the passphrase, both through aws-lc-rs, so a FIPS build uses its FIPS module. The file's format line and derivation parameters are the additional data. A sealed file without the passphrase, or with the wrong one, stops the engine with an error naming the variable. From 0.5.0 `syncd password rekey` changes the passphrase (the previous one keeps opening files until they are sealed again), and the passphrase file can be wrapped by a KMS key (`SYNCD_PASSWORD_KEY_PASSPHRASE_KMS`). **Without a passphrase the keys are stored in the clear** (the password key as hex, the CA key as PEM), protected by the file mode only: the engine warns at start and `syncd status` says `password keys: stored in the clear`. The status API's `keys_sealed` and the console's engine page say which |
| Enrollment tokens | `syncd password agent add <name> --source <connector>` prints a one-time token, good for an hour. The engine keeps only its SHA-256 and compares in constant time. The agent makes its own key, which never leaves its host, and sends a signing request with the token; the token is then spent, and an earlier certificate of the same agent stops working |
| Agent access | The ingest listener is mutual TLS with the agent CA. Every call but `Enroll` needs a certificate whose fingerprint the engine recorded and has not revoked. `syncd password agent revoke` refuses it at once; the check is the fingerprint, not a CRL. A revoked name cannot be enrolled again. From 0.5.0 **an agent speaks for the sources it is bound to only**: the engine opens each change an agent submits, in memory, and refuses one for another source before it is queued (`syncd_password_changes_refused_total`). An agent enrolled before 0.5.0 is bound to no source and may submit for any until it is bound (`syncd password agent bind`); the engine warns about it at every start |
| Who submitted it | From 0.5.0 each queued change records its submitter: the agent, the person and control API caller of a self-service change, or the control API's caller. `syncd password queue`, the delivery log lines (`submitted_by=`), the entity's history and lineage show it. The DN that made the change in the source directory stays in the agent's own log |
| Setting passwords through the control API | `SubmitPasswords` (`syncd password set`), adding and binding agents and the rotations: in strict mode a certificate must be listed under `api.access.passwords`, or the caller be on the control socket. In compatible mode, any control certificate can (see [The engine control API](#the-engine-control-api)) |
| Passwords in memory | Opened in memory only, just before delivery, and zeroized when dropped. Never logged, never in `Debug` output, status, metrics, lineage or history. The LDAP library's encoding buffers are not zeroized, and there is no locked memory or core-dump suppression in the engine (the agent's sample unit sets `LimitCORE=0`) |
| Echo suppression | A change captured right after syncd wrote the same password is dropped, recognised by a keyed hash kept for 10 minutes, with a random key per process; the agent also drops what the account named in its `ignore_actor` set |
| Writing to targets | Password Modify (RFC 3062), `userPassword` replace, or Active Directory's `unicodePwd`, which is refused unless the connection is LDAPS or StartTLS. From 0.5.0 **clear text to any LDAP directory is refused**: a configuration that delivers passwords, initial passwords or self-service changes to an LDAP directory on another host over `ldap://` without `starttls` no longer validates, unless `passwords: { allow_cleartext: true }` (with a warning at every start and in `syncd status`); loopback and `ldapi://` are exempt, and Active Directory never takes passwords without TLS. SCIM targets get `password` over HTTPS |

- **Seal the keys, and keep the passphrase apart.** Whoever reads the key files and the queued
  changes in the store can open the changes; with the keys sealed, they also need the passphrase.
  Backups and HA snapshots carry every live key file as it is on disk: sealed, or in the clear
  (see [Backups](#backups)). Both engines of an HA pair, and an engine restored from a backup,
  need the passphrase the keys were sealed with; a lost passphrase cannot be recovered (start
  with new keys, and enroll the agents again).
- **Rotate the keys** now and then, and the passphrase when it may have leaked: after a
  retirement, a later theft of the engine's files no longer opens older captures, but copies of
  the old key in backups and snapshots taken before it do, for as long as those are kept.
- **Protect the data directory** as you would the passwords themselves, sealed or not.
- The ingest listener (`passwords.ingest.listen`) is a network port of its own. Open it only to
  the directory servers' hosts (on Kubernetes, `networkPolicy.passwordIngestFrom`). From 0.5.0
  both engines of an HA pair listen on it: the passive one hands each call to the active one.
- **Keep privileged accounts out of publications that take passwords from a less protected
  directory.** Whoever administers a directory with a capture hook can set, in every target
  that takes passwords `from` it, the password of any account joined to one of its records.
- **What was tested**: the OpenLDAP overlay (slapd 2.6.10) and the 389 DS plugin (389-ds-base
  2.4.5) end to end, on Ubuntu 24.04 only, with no SELinux or AppArmor policy for the agent's
  socket. **The Windows password filter, service and installer are compile-checked only**:
  never loaded by LSASS, never run on a domain controller, not signed. An agent can set any
  account of its source; there is no per-agent rate limit; whoever may call
  `ForwardPasswordIngest` can act as any enrolled agent.

### Self-service password change

With `CONSOLE_SELF_SERVICE_ENGINE`, the console's public page `/password` lets people change their
own directory password without a console account.

- The console checks nothing but rate (per client address, as for sign-in), seals the request
  (user name, current and new password) to the engine's password key, and sends it with
  `ChangeOwnPassword` over its control API connection. It keeps nothing of the passwords; the
  audit trail records who, from where, and the outcome (`password.self_service`). From 0.4.1,
  when the named engine is one of an HA pair, the request goes to the member that holds the
  lease.
- The engine checks its policy (`passwords.self_service`: `min_length` 12, `min_classes` 3 of
  lower case, upper case, digits and others; not the current password; not containing the user
  name), finds the person's entry with the source connection's account, and **binds as the
  person** to prove the current password. An empty password is refused outright, never sent as
  an anonymous bind. It then changes the password **as the person**, so the directory's own
  policy, history and minimum age apply. From 0.4.1 the page shows the engine's `min_length`
  and `min_classes` (0.4.0 showed a fixed 12).
- A wrong user name, several matches and a wrong password all get the same answer (`401
  wrong_credentials`). After 5 failures in 15 minutes a user name is refused for the rest of that
  time, whatever the password, on top of the per-address limit.
- The page is public: put it behind the same TLS as the console, and consider who may reach it.
  There is no reset of a forgotten password.
- `ChangeOwnPassword` needs only the `operate` tier, not `api.access.passwords`: the engine
  proves the person's current password before it changes anything. The source people sign in
  to is held to the clear-text rule above.

## The pre-publish hook

Publishing a configuration can be made to carry a change-ticket reference
(`CONSOLE_PUBLISH_TICKET_PATTERN`) and to ask a change-management system first
(`CONSOLE_PUBLISH_HOOK_URL`). Both are off unless set.

- With a hook URL, the console posts `config.publish-requested` (the ticket, note, version,
  engine, a diff summary and who asked) after the approval and test-case checks and before
  anything is recorded or sent to the engine, with the same headers as a webhook:
  `X-Syncd-Signature: t=<time>,v1=<hex HMAC-SHA256 of "<t>.<body>">` under the secret in
  `CONSOLE_PUBLISH_HOOK_SECRET_FILE` (required with a URL, read at each use). The receiver
  verifies it as a [webhook](webhooks.md#verifying-a-delivery) is verified.
- The URL must be `https://`, and its address is held to the
  [webhook address rules](webhooks.md#where-a-webhook-may-go) (`CONSOLE_WEBHOOKS_ALLOWED_HOSTS`,
  `CONSOLE_WEBHOOKS_BLOCK_PRIVATE_NETWORKS`, `CONSOLE_WEBHOOKS_CA_FILE`). Redirects are not
  followed.
- A deny (or a 4xx) refuses the publish with `409` and the reason, and raises `config.rejected`.
  A timeout (`CONSOLE_PUBLISH_HOOK_TIMEOUT`, 10 s), a 5xx or a connection failure refuses it with
  `503 hook_unavailable`, **failing closed**, unless `CONSOLE_PUBLISH_HOOK_ON_ERROR=allow`.
- The ticket, note and decision are kept on the version and in the `config.publish.start` and
  `config.publish` audit events. The hook applies to publishes from the console, the API,
  promotions and Git sync; not to `syncd apply-config` on the engine, which strict access mode
  and [signed configurations](#signed-configurations) refuse.

## Webhooks and other outbound connections

### Webhook signing and address checks

Webhooks are signed with HMAC-SHA256 (`X-Syncd-Signature`, the ENS family's scheme) under a
per-webhook secret (`whsec_` and 32 random bytes) shown once. The URL and the secret are sealed
with the master key. Only administrators see a whole URL; auditors and the audit trail see its
scheme and host.

So that an administrator cannot turn the console into a proxy onto its own network, a webhook's
host is resolved before it is saved and again at every send, and every address is checked:
loopback, link-local and cloud metadata addresses (169.254.0.0/16, fe80::/10, fd00:ec2::254,
100.100.100.200, 168.63.129.16), unspecified, multicast and broadcast addresses, and IPv6 forms
that embed or translate them, are always refused. Private ranges are refused for new webhooks
unless `CONSOLE_WEBHOOKS_ALLOWED_HOSTS` lists the host, or `CONSOLE_WEBHOOKS_BLOCK_PRIVATE_NETWORKS`
is `false`. The post goes to the address that was checked (no DNS rebinding), verifies the
certificate against the URL's host, and never follows a redirect. See
[Webhooks](webhooks.md#where-a-webhook-may-go).

### Connections

| Connection | Protection |
|---|---|
| PostgreSQL, MySQL, SQL Server, MongoDB sources | `tls: disable` (the default), `require` (encrypted, server not verified), or `verify_full` (chain and host name checked, with `ca_file` or the Mozilla root set) |
| LDAP and Active Directory | `ldaps://`, or `starttls: true`, checked against the host's trust store, or from 0.5.0 only against the CAs in the connector's `connection.ca_file` (read at each connection) |
| SCIM 2.0 targets, Entra ID (Microsoft Graph) | HTTPS through rustls on the process provider. SCIM: a bearer token, basic credentials, or OAuth 2.0 client credentials, from 0.5.0 also with a signed assertion (`private_key_jwt`). Entra ID: a client secret, or from 0.5.0 a certificate (a client assertion, PS256 or RS256), a federated token (workload identity) or a managed identity. Only the client secret has run against a real tenant |
| REST/OData sources, Workday, SuccessFactors, UKG Pro | From 0.5.0: HTTPS through rustls on the process provider, with a bearer token, basic credentials, an API key header, OAuth 2.0 (client credentials, `private_key_jwt`, refresh token, JWT and SAML bearer) or a client certificate. Tested against fakes only, never a real tenant |
| Secret stores and KMS | From 0.5.0: Vault, AWS Secrets Manager and KMS (SigV4), Azure Key Vault, over HTTPS through rustls on the process provider; the engine's and the console's own clients, no vendor SDK. Tested against doubles only |
| CSV sources | Files on the engine's host or a mounted share; nothing on the network |
| JDBC bridge | A token (`--token-file` on the bridge) is required unless the bridge is on loopback; beyond loopback it must be `https://` |
| Debezium Server sidecar | The engine listens for its HTTP sink. Beyond loopback a token, which the sink puts in its URL path, is required |
| Password ingest listener | Mutual TLS with the engine's agent CA (see [Password synchronisation](#password-synchronisation)) |
| HA lease and snapshots | The lease's PostgreSQL connection with TLS (`tls_verify`, `ca_file`) when the URL asks for it, or from 0.5.0 a Kubernetes Lease through the API server with the engine's service account token (met only a fake API server so far); snapshots, the password queue and forwarded agent calls over the control API, mTLS |
| S3 backups | HTTPS with SigV4 signing on the process provider; `--s3-ca` or `engine.backup.ca_file` for a private CA |
| Prometheus `/metrics` | Plain HTTP, no authentication, counters and states only. Keep `api.metrics_listen` on loopback or a management network |
| Single sign-on provider | HTTPS (plain HTTP only to loopback), checked against the Mozilla root set plus `CONSOLE_OIDC_CA_FILE`; redirects are not followed; 10-second timeout. A SAML metadata URL the same, with `CONSOLE_SAML_CA_FILE` (its signature is not checked: it is as trusted as its TLS certificate) |
| OCSP responders and CRL distribution points (certificate sign-in) | Plain HTTP by design (the answers are signed and checked), to the addresses the certificates name or `ENS_X509_OCSP_URL` and `ENS_X509_CRL_URL`; `ENS_X509_REVOCATION_TIMEOUT` (10 s); a CRL at most `ENS_X509_CRL_MAX_BYTES` |
| Webhooks and the pre-publish hook | HTTPS only, the address checks above, the Mozilla root set plus `CONSOLE_WEBHOOKS_CA_FILE`, no proxy |
| Splunk HTTP Event Collector | HTTPS, checked against the public roots, unless `ENS_AUDIT_SPLUNK_INSECURE=true` (test systems only) |
| syslog | `ENS_AUDIT_SYSLOG_PROTOCOL`: `tls` (checked against the public roots), or `tcp` and `udp` in plain text |
| SMTP (scheduled reports, group review mail) | STARTTLS by default, or from 0.5.0 TLS from the first byte (`CONSOLE_SMTP_IMPLICIT_TLS`, SMTPS), checked against the public roots or `CONSOLE_SMTP_CA_FILE`; AUTH PLAIN or, from 0.5.0, AUTH LOGIN (`CONSOLE_SMTP_AUTH`), only over TLS. `CONSOLE_SMTP_STARTTLS=false` sends in plain text: a relay on the host or a private network only. Mail goes only to `CONSOLE_REPORTS_RECIPIENT_DOMAINS`, checked again at every run |
| Git sync | `https://` or `ssh://` through the `git` and `ssh` programs (not syncd's crypto module) |

## Sensitive attributes and reveal

`attributes.sensitive` in the engine configuration lists attributes whose values are personal
data (a national ID, a birth date). Their values are withheld, and not searched, in the object
explorer, previews, simulations, connector samples, entity history, lineage exports, correlation
suggestions and the reconciliation report (which names a sensitive attribute that differs, without
its values). Data-quality failures carry the check's message, never a value. What derives from
sensitive attributes is hidden too: computed attributes that read them, source fields that flow
into them, and target attributes built from them. DNs are not masked, so do not build DNs from
sensitive attributes. Golden-file test output masks them as well.

| Layer | Who may reveal |
|---|---|
| Console | Roles in `CONSOLE_REVEAL_ROLES` (default `admin`; `none` for nobody), with a session only, on the engines the account's assignment of that role reaches. From 0.5.0 an entry can limit where its role reveals: `admin,auditor@prod` lets auditors reveal on production's engines only, `operator@engine:lab` on one engine. A reveal is recorded before the values are returned, with the names of the attributes it showed; if the audit trail cannot be written, the reveal is refused. A refused attempt is audited too |
| Engine | Callers on mTLS or the control socket, or on loopback with `api.local_reveal: true`, with the `operate` tier (from 0.5.0; a `read` certificate gets no values). Each reveal is logged with its caller |

A search that reveals is audited with the SHA-256 of its text rather than the text itself, which
may be a sensitive value; an ordinary search is audited with its text. Attributes the running
configuration treats as sensitive stay hidden in a simulation even if the simulated version
drops them.

## Audit trail

From 0.4.0 the console records its trail in the ENS family's format, `ens-audit/1`: one JSON
object per event, with a gapless `seq`, the family's categories (`authentication`,
`authorization`, `account`, `configuration`, `data`, `system`, `audit`), severities (`info`,
`notice`, `warning`, `alert`) and actors (`user`, `service` for an API token, `system`,
`anonymous`). It records every sign-in and refusal, sign-out, account change, settings change,
engine registration, configuration action (save, import, export, approval, simulation, publish,
promotion, Git sync), engine operation, connector check, explorer search, entity view and reveal,
export, token, webhook and environment change, and self-service password change, with the actor,
target, outcome, details, client address and user agent. From 0.5.0 also the acknowledgement of
the consent banner (`auth.consent`), SAML and certificate sign-ins (`auth.saml.login`,
`auth.x509.login`, the latter with the certificate's subject, issuer, serial and fingerprint and
how revocation was checked), sessions ended by the per-account limit (`auth.session.ended`), the license,
WebAssembly module uploads and approvals, and `config.unpublished-running`. See
[Audit logging](../audit-logging.md).

- **Roles in the record (from 0.5.0).** Sign-in records carry the account's `roles` (a list of
  assignments) instead of `role`, `actor.roles` lists them, and each event written for a request
  carries `details.assignment`, the assignment the request was allowed under. **Update SIEM
  parsers** that read `role`.
- **The Audit log page** filters by time, category, action, actor and outcome, server-side, and
  exports what it lists as CSV (`GET /api/v1/audit/csv`, at most 100,000 rows, recorded as
  `data.exported` first). An auditor of an environment or engine sees the entries recorded about
  it; the NDJSON export, the verification and the pull API are for auditors of everything.

- **Append-only.** Triggers on `audit_log` refuse `UPDATE`, `DELETE` and `TRUNCATE`. The only
  deletion they let through is the retention prune, inside its own transaction.
- **Hash-chained.** `hash = SHA-256(prevHash + "\n" + <the event's JSON without its hash>)`,
  over the exact text the console emits; the first event's `prevHash` is 64 zeros. Appends are
  serialised with a transaction-scoped advisory lock. The family's checker,
  `tools/audit/verify_chain.py`, verifies an export, an audit file or the pull API's output
  without the console.
- **Verification.** `GET /api/v1/audit/verify` (admins and auditors, or **Verify chain** on the
  Audit log page) recomputes the chain in place and returns the entries checked, the first broken
  one if any, and the head. A broken chain is itself recorded, as an `alert`.
- **Entries removed from the end.** Removing the newest entries leaves a chain that still
  verifies. So the console writes the chain's head to its log as `audit log head`, at startup and
  every hour when it has changed: keep those lines outside the database. Verifying against a
  recorded head (`anchor_id` and `anchor_hash`) reports whether that entry is still there with
  that hash. The outputs below keep copies outside the database too.
- **Recorded first where it matters.** A publish (`config.publish.start`), a reveal, an export
  (`data.exported`), settings and account changes, and token and webhook changes are refused
  when their record cannot be written; other events log an error and carry on.
- **Entries from before 0.4.0** keep their earlier chain. At the first start on 0.4.0 the console
  records `audit.format-upgraded`, and the first new event continues the old chain's head, so
  verification checks both. They are not in the pull API or exports.

### Outputs and the pull API

Every event is stored in the database first, then sent by one sender per output from its own
cursor: at least once, in order, never lost while a receiver is down. After 15 minutes of failures
the console records `audit.delivery-failed` (an `alert`), and `audit.delivery-recovered` when the
output catches up.

| Output | Setting |
|---|---|
| Standard output (the journal, the pod log) | On by default (`ENS_AUDIT_STDOUT`) |
| A file, rotated daily or at 100 MB | `ENS_AUDIT_FILE`, `ENS_AUDIT_FILE_KEEP` (30 days) |
| Splunk HTTP Event Collector | `ENS_AUDIT_SPLUNK_URL`, `ENS_AUDIT_SPLUNK_TOKEN_FILE`, `_INDEX`, `_SOURCETYPE` |
| syslog (RFC 5424, octet-counted over TCP and TLS) | `ENS_AUDIT_SYSLOG_HOST`, `_PORT`, `_PROTOCOL`, `_FACILITY` |
| The pull API | `GET /api/audit/events?after=<seq>`, with an API token with `audit:read` |

`ENS_AUDIT_RETENTION_DAYS` (0, keep forever) removes events older than that, once a day, but only
events every enabled output has delivered, never the newest; each run is recorded as
`audit.pruned`, and the chain still verifies from the oldest kept event.

The engine itself does not write an audit trail: its control requests, refusals by access tier,
reveals, backups and password deliveries go to its journal with their caller (a delivery with who
submitted the change). From 0.5.0 its status also says who applied the configuration it runs.

## Backups

- **What a backup holds.** An engine backup (`syncd backup`, `engine.backup`) holds the store,
  which carries entity data, the configuration file, and every live password key and agent CA
  key **as they are on disk**: sealed with the passphrase from 0.4.1 when one is set, otherwise in
  the clear. Any password changes still queued are in it too (sealed to the password key). A
  `syncdctl backup --to` adds a dump of the console's database (with the sealed secrets and the
  audit trail), an archive of all of `/etc/syncd` (the service settings, the PKI with its keys,
  `engine.env` with the source and target secrets, and anything else kept there), and, from
  0.4.1, the console's master key. An HA snapshot holds what an engine backup holds.
- **Protect the destination accordingly.** syncd does not encrypt backups itself. Protect a
  directory destination, and the share it is on, as you would the data directory. For S3, ask for server-side
  encryption (`--sse AES256` or `aws:kms[:key]`, `engine.backup.sse`), restrict the bucket's
  policy, and prefer an IAM role (IRSA, EKS Pod Identity) to static keys. S3 credentials come
  from the engine's environment (`AWS_*` in `/etc/syncd/engine.env`), the shared credentials
  file or, from 0.5.0, last, the EC2 instance's role (`AWS_EC2_METADATA_DISABLED=true` skips
  it).
- **The console's master key** is in an off-host backup from 0.4.1 (#141): `syncdctl backup
  --to` writes it as `<name>.console-master.key` beside the database dump, or, when it is set as
  `CONSOLE_MASTER_KEY` in `/etc/syncd/console.env`, says it is in the `/etc/syncd` archive. A
  master key wrapped by a KMS (from 0.5.0) is useless without access to the KMS key.
  `syncdctl restore --from` puts it back (the key it replaces is kept as `master.key.previous`).
  A 0.4.0 off-host backup lacks it: without the key, a restored console database cannot open
  what it sealed. So whoever can read the destination can open every secret the console seals.
  The host-local `syncdctl backup` (`/var/lib/syncd/backups`, root only) has always held it.
- **The password-key passphrase** is kept out of off-host backups from 0.5.0: a passphrase file
  inside `/etc/syncd` is left out of the `syncdctl backup --to` archive, with a warning (0.4.1
  archived it with the rest of `/etc/syncd`). Keep it outside `/etc/syncd`
  (`/etc/syncd-keys/password-key.passphrase`) and back it up apart; a restore needs it.
  `SYNCD_PASSWORD_KEY_PASSPHRASE` in `engine.env`, the passphrase itself, is still in the
  archive, and the command warns about it. Backups taken before the keys were sealed hold them in
  the clear.
- **Integrity.** Every file of a backup is listed in its manifest with its SHA-256; a restore
  refuses a backup whose files do not match, or whose store format is newer than it reads. The
  manifest is not signed: it catches damage, not a deliberate change by someone who can write
  the destination.
- **A restore of sealed keys needs the passphrase** of the engine they came from: without it the
  restored engine does not start. 0.4.0's documentation said the key files were already
  encrypted; they were not, and an unsealed engine's backups still hold them in the clear.

### Support bundle

`syncd support-bundle`, `syncdctl support-bundle` and the console's
`GET /api/v1/admin/support-bundle` (admins, session only, audited as `support.bundle-exported`)
collect what support asks for first: versions, status, the configuration, held changes and dead
letters as counts and error texts, metrics, store statistics, host limits and journals.

- **Redacted.** Every secret in the configuration, and every path to one, becomes `<redacted>`
  (fields named `password`, `secret`, `token`, `key`, `*_file`, and URLs carrying `password=`);
  a test covers every secret field of every connector type. Environment variables are listed
  with their values redacted (`PASSWORD`, `SECRET`, `TOKEN`, `KEY`, `_URL`, `CREDENTIAL`); the
  console's database URL counts as a secret.
- **No entity data**: no attribute values, no DNs, no anchors, no entity ids, no store, no keys
  or certificates.
- The journals can still hold DNs and the names of accounts (in error messages): read the bundle
  before you send it.

## High availability

An [active/passive pair](architecture.md#high-availability) is fenced so that two engines never
write at once:

- the lease is taken or renewed by one statement that only its holder, or a taker of an expired
  lease, can win, with expiry judged by the database's clock (the `file` backend judges by the
  hosts' clocks: keep them in NTP sync). From 0.5.0 the lease can also be a Kubernetes Lease
  object (`engine.ha.lease.kind: kubernetes` in the chart), renewed with the engine's service
  account token under a Role limited to that one Lease; it has met only a fake API server in
  tests;
- every export batch and every password delivery checks the lease first and is refused when it
  is not held for more than another second, counted from when the renewal was sent;
- an engine that loses the lease steps down to passive.

The lease's database URL (`SYNCD_HA_LEASE_URL`) holds a database password: keep it in
`engine.env`, as a secret reference. Its role needs to read and write `syncd_lease` (and to create
it, unless the console's migrations already did). The
passive engine pulls the whole store and key material from the active one over mTLS (from 0.5.0
only the files it lacks), so the two hosts' certificates come from one CA and their control API
port (7443) is open between them only. With sealed password keys, both engines need the same
passphrase. From 0.5.0 the passive engine also follows the queue of sealed password changes
between snapshots, and its ingest listener hands agents' calls to the active engine with the
fingerprint of the certificate the agent presented; in strict mode the certificate it uses for
that must be listed under `api.access.passwords`. An agent enrolled, or a key rotated, after the
last snapshot is lost with the active engine.

## systemd hardening

Both units (`syncd.service`, `syncd-console.service`) run as their own system accounts (`syncd`,
`syncd-console`) and set:

| Setting | Effect |
|---|---|
| `ProtectSystem=strict`, `ReadWritePaths=` | The file system is read-only except the service's own data: `/var/lib/syncd/engine` and `/etc/syncd/engine` (the engine rewrites its configuration when one is published), or `/var/lib/syncd/console` |
| `ProtectHome=yes`, `PrivateTmp=yes`, `PrivateDevices=yes` | No home directories, a private `/tmp`, no devices |
| `NoNewPrivileges=yes`, empty `CapabilityBoundingSet=` and `AmbientCapabilities=` | No privileges can be gained; no capabilities |
| `ProtectKernelTunables`, `ProtectKernelModules`, `ProtectKernelLogs`, `ProtectControlGroups`, `ProtectClock`, `ProtectHostname` | Kernel settings, modules, logs, cgroups, the clock and the host name are out of reach |
| `RestrictSUIDSGID`, `RestrictNamespaces`, `RestrictRealtime`, `LockPersonality`, `MemoryDenyWriteExecute` | No set-uid files, namespaces, real-time scheduling, personality changes or writable executable memory |
| `RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX` | IP and Unix sockets only |
| `SystemCallArchitectures=native`, `SystemCallFilter=@system-service ~@privileged` | A system call allow-list |
| `UMask=0027` (engine), `UMask=0077` (console) | Files the services create are not world-readable |
| `MemoryMax=80%` (engine) | The kernel stops the engine, and systemd restarts it from its last commit, before the host runs out of memory |

`/etc/syncd/console.env` and `/etc/syncd/engine.env` are readable by root and the service's group
only. Backups under `/var/lib/syncd/backups` are readable by root only.

## Container images and the Helm chart

The engine and console images (`deploy/container/Dockerfile`) and the Helm chart
(`deploy/helm/syncd`) are hardened as follows:

| Control | Implementation |
|---|---|
| Base | `gcr.io/distroless/cc-debian12:nonroot`, pinned by digest: glibc and the C++ runtime, no shell, no package manager. The console's image adds Debian's `git` and `ssh` for Git sync. The binaries are stripped |
| User | uid and gid 65532, set in the images (`USER 65532:65532`); CI checks it. The chart also sets `runAsNonRoot`, `runAsUser`/`runAsGroup`/`fsGroup` 65532 |
| File system | `readOnlyRootFilesystem: true` in the chart. Writes go only to volumes: the engine's store and configuration (`/var/lib/syncd`), its control socket (`/run/syncd`, in memory), and `/tmp`; the console's data directory and `/tmp` (`emptyDir`) |
| Privileges | `allowPrivilegeEscalation: false`, all capabilities dropped, seccomp `RuntimeDefault`, no service account token mounted (`automountServiceAccountToken: false`), except the engine's when an HA pair's lease is a Kubernetes Lease (from chart 0.5.0; its Role reaches that one Lease). From chart 0.5.0 `engine.serviceAccount` and `console.serviceAccount` name accounts of the chart's own, for a vault role or a workload identity to be bound to |
| Secrets | The console's database URL, master key and initial admin password come from Secrets, as environment variables; the single sign-on client secret from a Secret of your own, mounted as a file (`console.oidc.existingSecret`); the engine's source and target secrets from a Secret you name (`engine.envFromSecret`); from chart 0.4.1, the password-key passphrase from a Secret of your own, mounted as a file (`engine.passwordKeyPassphrase.existingSecret`, key `passphrase`; from chart 0.5.0 also `previousKey` during a passphrase change); from chart 0.5.0, the license from `console.license.existingSecret`, and the console's public signing key for the engine (`engine.configSigning`). Other secret files (a Splunk token, a Git key) come in through `console.extraVolumes`; for secrets from a vault, mount them as files (the External Secrets Operator or the Secrets Store CSI driver). The chart has no values for the KMS settings |
| Network | The engine listens on `0.0.0.0:7443` (control API, mTLS required) and `0.0.0.0:9464` (metrics, plain HTTP, unauthenticated); the console on `0.0.0.0:8080`. With `networkPolicy.enabled`, one NetworkPolicy per component lets only the console and the registration Job reach 7443, only the namespaces and pods you select reach 9464 (`monitoringNamespaceSelector`, `monitoringPodSelector`) and 8080 (`ingressNamespaceSelector`, `ingressControllerSelector`, `console.extraIngressFrom`), and, for an HA pair, the engines reach each other. From chart 0.4.1, `engine.passwordIngest.enabled` opens the password ingest port (7445 by default) on a Service of its own (`<release>-syncd-engine-ingest`), reached from `networkPolicy.passwordIngestFrom` (from anywhere when that is empty; agents still need a certificate the engine issued); from 0.5.0 both engine pods of a pair answer behind it. Egress stays open unless `networkPolicy.egress.enabled`, which closes it to DNS and the control API plus your `networkPolicy.egress.extra` rules (add the console's database, sources and targets, the identity provider, webhook receivers and the audit outputs). Your CNI must enforce them |
| TLS to browsers | Through the chart's Ingress and your controller. With `console.tls.existingSecret` the console also serves HTTPS itself, so the hop from the controller is encrypted |

Set `console.trustedProxies` to the ingress controller's addresses, so that the audit trail and
the sign-in limit see client addresses.

## Supply chain

- **Dependencies.** `cargo-deny` runs on every change (`deny.toml`): licences on an allow-list,
  yanked crates denied, crates only from crates.io, no git or unknown registries, no wildcard
  versions. `cargo-audit` checks advisories; the few advisories ignored are each justified in
  `deny.toml` or `.cargo/audit.toml`, and CI checks that the code they concern stays unbuilt.
- **Fuzzing.** The decoders of what sources send (`pgoutput`, DirSync, syncrepl) and, from
  0.5.0, of what WebAssembly modules return are fuzzed in CI.
- **Releases.** A release is built in CI from a `v*` tag (Rocky Linux 9, and Rocky Linux 8 for
  the RHEL 8 `.rpm`), after the package lifecycle tests and the upgrade test from the previous
  release pass. **No tag has been pushed yet, so nothing is published so far, and none of 0.5.0's
  release jobs has run.** A release carries the x86_64 and aarch64 bundles, `.deb` and `.rpm`
  (aarch64 only when the repository's arm64 runners are enabled, which from 0.5.0 is the
  default), the RHEL 8 `.rpm`, x86_64 and (from 0.5.0) aarch64 FIPS bundles, and from 0.5.0 the
  `syncd-capture` packages, with:
    - `SHA256SUMS` over every file as published, and its **GPG signature** `SHA256SUMS.asc`, made
      in a protected `release` environment that holds the key. From 0.5.0 the `.rpm` files are
      signed with the same key. Without the key the workflow warns and publishes unsigned, and
      its summary says so. Verify the signature and the checksums before installing (see
      [Installation](install.md));
    - **CycloneDX SBOMs** of the engine, the console and (from 0.5.0) the capture agent, and from
      0.5.0 the images' SPDX SBOMs as release files too;
    - from 0.5.0, `TESTED-<version>.md`: what CI ran the release against, and what was a
      stand-in.

    Inside the bundle, `MANIFEST.sha256` lists every file, and the installer checks it. From 0.5.0
    a signed release is also published to **signed apt and dnf repositories** on GitHub Pages
    (`InRelease` and `repomd.xml.asc` signed with the release key). The packaging scripts fetch
    the pinned nfpm and check its SHA-256.
- **Container images and chart.** A `v*` tag also pushes `ghcr.io/winllc/syncd:<version>`,
  `ghcr.io/winllc/syncd-console:<version>`, their `<version>-fips` variants and the chart
  (`oci://ghcr.io/winllc/charts/syncd`). None is published until a release is tagged.
    - **Multi-arch:** `linux/amd64` and `linux/arm64` (amd64 alone when the arm64 runners are
      off); from 0.5.0 the FIPS images too. Tagged by version only (no `latest`).
    - **Signed:** each image and the chart are signed with cosign (keyless, the release
      workflow's GitHub identity, by digest). Verify with `cosign verify` against the workflow
      identity before mirroring.
    - **SBOM:** an SPDX SBOM of each image, per platform, attached as a signed cosign
      attestation (`cosign verify-attestation --type spdxjson`).
    - **Scanned:** on every pull request CI builds the standard and FIPS images, amd64 and (with
      arm64 runners) arm64, and scans the amd64 ones, standard and FIPS, with Trivy; a fixable
      HIGH or CRITICAL finding fails the build. Accepted risks are listed in `.trivyignore`, each with its reason, who accepted
      it and until when. At 0.5.0 it holds one: CVE-2026-84782 (OpenSSL's DTLS, in the
      distroless base's `libssl3`, which syncd does not link), accepted until 2026-12-08.
    - **Pinned:** the base images are referenced by digest, and Dependabot proposes updates.

    CI also checks that the images run as uid 65532 and that only the FIPS images accept
    `SYNCD_FIPS_MODE`, lints the chart, renders it with every production option on, and installs,
    upgrades, restarts and uninstalls it in a kind cluster (`tests/helm/kind.sh`). Mirror the
    images to your own registry and pin them by digest.

See [Releases](../releases.md) for the suite's release process.

## FIPS 140-3 and DISA STIG

See [FIPS 140-3 and DISA STIG](fips-and-stig.md) for the optional FIPS mode, what it changes, what
is not validated, and the settings a STIG deployment needs.

## Hardening checklist

- [ ] HTTPS for the console: its own certificate (`CONSOLE_TLS_CERT`, `CONSOLE_TLS_KEY`), or the
      shipped nginx configuration with `CONSOLE_LISTEN` on `127.0.0.1`; and `CONSOLE_PUBLIC_URL`
      set to the `https://` address
- [ ] `CONSOLE_TRUSTED_PROXIES` set to your proxy only
- [ ] `SYNC_DEV_MODE` unset in production
- [ ] The initial admin password changed, and further admins named rather than shared
- [ ] Single sign-on (OpenID Connect or SAML) or certificate sign-in with a role map, reviewed
      now that every matching entry adds its role, and local sign-in limited to break-glass
      accounts
- [ ] The sign-in baseline your policy asks for: the consent banner, session limits, lockout and
      password rules (`ENS_*`), and HSTS from the console or the proxy
- [ ] Role assignments scoped to the environments and engines each person works on
- [ ] Configuration approval required where more than one person designs configurations; a
      ticket pattern and pre-publish hook where change management requires them
- [ ] `CONSOLE_REVEAL_ROLES` as narrow as your policy allows (`none` if nobody needs to see values),
      and `attributes.sensitive` listing every personal-data attribute
- [ ] API tokens with the narrowest scopes, limited to an environment or engines where they can
      be, with an expiry, one per system, revoked when unused
- [ ] Webhooks limited with `CONSOLE_WEBHOOKS_ALLOWED_HOSTS`, and private networks left blocked
- [ ] The control API on loopback with mTLS (as packaged), `local_control` and `local_reveal` off,
      and the `syncd` group kept to administrators
- [ ] An upgraded engine switched to strict access (`sudo syncdctl api-access strict`), and,
      where configurations must only come through the console, signed configurations required
      (`engine.require_signed_config`)
- [ ] With password synchronisation: the keys sealed (`SYNCD_PASSWORD_KEY_PASSPHRASE_FILE`, its
      file outside `/etc/syncd` and kept where a restore can find it), the engine's data directory
      readable by the engine only, the ingest listener open only to the directory servers, every
      agent bound to its source, unused agents revoked, the keys rotated now and then, and no
      `passwords.allow_cleartext`
- [ ] The master key (`/var/lib/syncd/console/master.key`) stored securely, with a copy kept
      apart from the backups
- [ ] Off-host backups to a protected destination: S3 with server-side encryption and a narrow
      bucket policy; they hold entity data, the password keys, the engine's secrets and the
      console's master key
- [ ] Source and target connections with `tls: verify_full`, `ldaps://` or `starttls: true`
- [ ] `api.metrics_listen` on loopback or a management network
- [ ] The console database reached over TLS when it is on another host
      (`sslmode=require` or stricter in `CONSOLE_DATABASE_URL`), and its owner role kept private
- [ ] Release signatures and checksums verified before every install and upgrade; image and chart
      signatures verified with cosign
- [ ] The control API certificates renewed before they expire (`syncdctl status` shows when)
- [ ] The audit trail sent off the host (a file collected, Splunk or syslog over TLS, or the pull
      API), and the chain verified regularly
- [ ] On Kubernetes: the images mirrored and pinned by digest, `networkPolicy.enabled`, read
      access to the namespace's Secrets restricted, and the `syncd-console` Secret backed up
