# Security

This page summarises syncd's security controls and the settings that affect them: the
console's sign-in, sessions, roles and API tokens, its HTTPS, the engine's control API,
secrets, password synchronisation, webhooks and other outbound connections, the audit trail,
backups, high availability, the systemd hardening, the container images and Helm chart, and the
supply chain.

## Trust model

- **The engine holds the credentials.** It connects to every source and target with the
  accounts its configuration names. The configuration never holds a secret: each password or
  token is a reference to an environment variable (`password: { env: NAME }`) or a file
  (`{ file: /path }`). On a packaged install, the variables are in `/etc/syncd/engine.env`,
  readable by root and the `syncd` group only. On Kubernetes, they come from a
  Secret you name (`engine.envFromSecret`).
- **The engine also holds the password key.** With password synchronisation on, the engine's
  data directory holds the private key that opens every captured password change, and the key
  of the CA that admits capture agents. From 0.4.1 both can be sealed with a passphrase
  (`SYNCD_PASSWORD_KEY_PASSPHRASE_FILE`); without one they are files in the clear, protected by
  their mode (0600) only, as in 0.4.0. See [Password synchronisation](#password-synchronisation).
- **The console holds no source or target passwords.** It stores configuration versions
  (references only), accounts, sessions, the audit trail, API tokens (as hashes), and, sealed
  with the console's master key, each engine's control API client key, each webhook's URL and
  signing secret, and the secret values of its environments.
- **The control API is the engine's front door.** Anyone who can call its commands can run,
  approve, reload a configuration, queue password changes, take a backup of the whole store, or
  read sensitive values. Off loopback it requires mutual TLS. Restrict who holds a client
  certificate issued by its CA to the console, the host's administrators and the other engine of
  an HA pair.
- **Editing configuration is privileged.** A configuration decides which systems the engine
  connects to and which of the host's secret references it sends them. Give the designer role
  accordingly, and consider [configuration approval](#configuration-approval-four-eyes), the
  [pre-publish hook](#the-pre-publish-hook), and who may push to a repository the console takes
  configuration from ([Git sync](#git-sync-credentials)).

## Authentication

The console has local accounts, optionally single sign-on through OpenID Connect, and, from
0.4.0, [API tokens](#api-tokens) for other systems. It has no SAML and no client-certificate
(X.509, CAC/PIV) sign-in.

| Control | Implementation |
|---|---|
| First sign-in | The first start creates `admin` with a random 24-character password, printed once and written to `$CONSOLE_DATA_DIR/initial-admin-password` (mode 0600). An installer can supply one with `CONSOLE_ADMIN_PASSWORD` or `CONSOLE_ADMIN_PASSWORD_FILE`; `admin`, empty, and passwords failing the policy are refused at startup. The first sign-in must set a new password, and the file is then deleted |
| Forced password change | An account with a one-time password can call nothing but `POST /api/v1/auth/password` (and `GET /api/v1/auth/me`) until it sets a new one. Every other call answers 403 `password_change_required` |
| Password policy | At least 12 characters; not the username and not `admin` (case-insensitive); not the current password. There are no complexity, history or expiry rules |
| Password hashing | Argon2id (the `argon2` crate's defaults), or PBKDF2-HMAC-SHA512 with 210 000 iterations and a 16-byte salt in FIPS mode, stored as PHC strings. Salts and generated passwords come from aws-lc-rs. In FIPS mode Argon2id hashes are not accepted, so those accounts need a reset |
| Per-address throttle | 20 sign-in attempts per client address in 5 minutes, then 429 `too_many_requests`, audited as `auth.login` denied (`rate limited`). The counters are in memory and reset when the console restarts |
| Account lockout | 5 consecutive wrong passwords lock the account for 15 minutes, audited with `"locked": true` (an `alert` in the trail). A successful sign-in resets the count. These numbers are fixed |
| Uniform refusals | Every failed sign-in gets the same 401 `sign_in_failed`, whether the account is unknown, disabled, locked, has no local password, or is outside break-glass. An unknown account spends as long as a real password check. The audit trail records the real reason |
| Account recovery | `syncd-console admin reset-password [--user NAME]` on the host issues a one-time password. It needs the console's database settings (`console.env`). It refuses an account that signs in with single sign-on, as the **Users** page does, since a password would let it sign in without the identity provider (and its MFA). `--allow-single-sign-on` gives it one anyway, for recovery while the provider is down; the audit entry (`user.password_reset`) then records `"single_sign_on": true` |
| Single sign-on | OpenID Connect authorization code flow with PKCE, state and nonce; ID token signature (asymmetric algorithms only), issuer, audience and expiry checked. Accounts are bound to the provider's subject, never taken over by username. Roles come from a claim at every sign-in. See [Single sign-on](single-sign-on.md) |
| Break-glass | With single sign-on configured, an admin can limit local sign-in to accounts marked break-glass (**Users** → Local sign-in). At least one enabled break-glass admin with a password must remain; switching signs out the local accounts that can no longer sign in |
| Disabling an account | Takes effect at the account's next request: its sessions are refused, single sign-on cannot sign it in, and its API tokens stop working (within a minute) |

### Configuration approval (four eyes)

An admin can require it (**Users** → Configuration approval). A version is then published only
after a designer or admin other than its author approves it. The database enforces it: a
constraint refuses an approval by the author, and a trigger refuses any change to an approval
once given. Versions published before can be published again without one, so a rollback is
never held up. Approvals and changes of the setting are audited (`config.approve`,
`settings.config_approval`).

Approval applies to every way the console publishes: the **Publish** button, the API with an
API token (`configs:publish` can publish, never approve), a promotion (the version then waits
on each target engine) and Git sync's `publish` mode (the version waits for approval). The
engine's control API is not gated by approval: `syncd apply-config`, or any holder of a client
certificate, can still apply a configuration directly.

## API tokens

From 0.4.0, scripts, pipelines and SIEM collectors call the console's API with a bearer token
(`Authorization: Bearer sdt_…`) instead of a session. Administrators create and revoke them on
**Integrations**; auditors see the list. The scopes, settings and endpoints are in the
[API reference](api-reference.md#api-tokens).

| Control | Implementation |
|---|---|
| The token | `sdt_` and 32 random bytes (base64url), from aws-lc-rs. Shown once, when it is created. The database keeps its SHA-256 and a four-character prefix; the token is never written to a log, the audit trail or a session |
| Scopes | `engines:read`, `configs:read`, `configs:write`, `configs:publish`, `runs:run`, `audit:read`, `environments:read` (from 0.4.1), `admin`. Each needs a role of the creator, to be granted and at every use |
| Never more than its creator, now | Each use reads the creator's account (a good answer is reused for at most a minute and dropped when the account changes; a refusal never is): a creator that is gone or disabled refuses the token, and a demoted creator's token loses what the new role cannot do |
| Endpoints that stay with people | Accounts, settings, tokens, webhooks, changes to environments, approvals, simulations, previews, connector checks, the object explorer and lineage, scheduled reports, the support bundle and single sign-on take a session only: a token gets `403 insufficient_scope` |
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
- There is no limit on concurrent sessions per account, and no separate idle timeout for
  admins.
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

- Neither the console (also when it serves HTTPS itself) nor the shipped nginx configuration
  sends `Strict-Transport-Security`. If your policy requires HSTS, put a proxy in front that
  adds it (nginx, or your ingress controller).
- Exports (reports, lineage, the audit trail, the support bundle) are always downloads
  (`Content-Disposition: attachment`). A CSV export guards against formula injection: a cell
  starting with `=`, `+`, `-`, `@`, a tab or a carriage return gets a leading apostrophe.
- Error responses carry a code and a message only. Internal errors answer `internal error`; the
  details go to the console's log.

## Authorization

Every account has one role. Roles are checked on every API call; an API token is checked
against its scopes and its creator's role.

| Role | Can |
|---|---|
| `viewer` | Read engines, status, held changes, dead letters, data quality, configuration versions, diffs and the environments' names, the object explorer and lineage exports, the reconciliation report export, rule previews and the function catalogue |
| `auditor` | What a viewer can, plus the audit trail (its verification, export and outputs), the lists of API tokens, webhooks and their deliveries, scheduled reports, and the environments' values (secret values never) |
| `operator` | What a viewer can, plus runs (reconcile, full sync), approvals of held deletions and changes, group reviews, drift checks, dead-letter retry and discard, joining, unjoining and accepting correlation suggestions, and scheduled reports of their own |
| `designer` | What a viewer can, plus validating, saving, importing, simulating, previewing, approving, publishing and promoting configuration versions, connector checks, and **Sync now** for Git |
| `admin` | Everything, including accounts, sign-in settings, configuration approval, banners, registering and removing engines, environments and their values, API tokens, webhooks, everyone's scheduled reports and the support bundle |

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
| Client certificates | None requested: the console has no certificate sign-in |
| Handshakes | 10 seconds each, in a task of its own, so a client that stalls holds up no other connection |
| Startup | A certificate or key that does not load, or a key that does not match, stops the console |
| Renewal | The files are checked every minute and loaded again when either changes, without a restart (certbot's deploy hook, cert-manager). A renewal that does not load (half-written, say) is logged as a warning and the previous certificate kept, until the next check |
| Port | The packaged console does not run as root, so it cannot listen on 443. Use a port such as 8443, or nginx for 443 |
| HSTS | Not sent (see above) |

Set `CONSOLE_PUBLIC_URL` to the `https://` address for single sign-on. See
[Installation](install.md).

### Behind a reverse proxy

Put nginx with TLS in front of the console for port 443, a certificate shared with other
sites, HTTP/2 or HSTS. The release ships `share/nginx/syncd.conf`, which redirects HTTP to
HTTPS, allows TLS 1.2 and 1.3, sets `X-Forwarded-For` to the connection's address (replacing
whatever the client sent), and passes server-sent events through unbuffered. On Kubernetes, the
Helm chart's ingress does the same job. See [Installation](install.md).

`CONSOLE_TRUSTED_PROXIES` lists the proxies (addresses or CIDR ranges) whose `X-Forwarded-For`
names the client. The console reads the header from the right, past trusted proxies, to the first
address that is not one. A connection from anywhere else is taken at its own address, whatever
header it sends. The client address goes to the audit log and to the per-address sign-in limit;
without the setting, every request seems to come from the proxy and all users share one sign-in
budget. List only proxies you run.

Keep `CONSOLE_LISTEN` on `127.0.0.1` when nginx runs on the same host.

## Secrets

### Engine secrets

Source and target passwords, SCIM and Entra ID credentials, bridge and Debezium tokens, the HA
lease's database URL and S3 keys are read from environment variables or files at the moment they
are needed; they never appear in the configuration, in `GetConfig`, in configuration exports, in
`syncd config diff`, or in a support bundle. A configuration moved between consoles lists the
variables and files the new host must provide.

### Console secrets and the master key

The console encrypts stored secrets with envelope encryption:

- each secret gets its own random 256-bit data key, and is sealed with AES-256-GCM;
- the data key is wrapped with the master key (AES-256-GCM);
- both carry the secret's location (for example `engine:<name>:client_key`,
  `webhook:<id>:secret`, `environment:<name>:<key>`) as additional data, so a ciphertext copied
  to another row does not open;
- all of it goes through aws-lc-rs, so FIPS mode covers it.

What it seals: each engine's control API client key; each webhook's URL and signing secret; and
the values marked secret in the console's environments (see
[Configuration as code](config-as-code.md#environments)). None is returned by the API once saved.

The master key is 32 bytes, from `CONSOLE_MASTER_KEY` (base64) or `$CONSOLE_DATA_DIR/master.key`
(`/var/lib/syncd/console/master.key` on a packaged install), which the console creates (mode 0600)
on first start and logs a warning to back it up. On Kubernetes, the Helm chart generates it into
the `syncd-console` Secret and passes it as `CONSOLE_MASTER_KEY`. **Without it, stored secrets
cannot be decrypted.** There is no KMS or Vault integration.

At every start the console checks the key against a secret the database holds: an engine's
client key, or else a webhook's URL, or else a secret environment value. (0.4.0 looked at
engines' client keys only, so a console holding only webhooks or secret values started with the
wrong key; from 0.4.1 it does not.)

- if `master.key` is missing while the database holds sealed secrets, it does not create a new key
  over them;
- if the key does not open the one it tries (the wrong file, or the wrong `CONSOLE_MASTER_KEY`), it
  is refused, and the error names that secret.

Either way the console does not start, and says to restore the key. To start over on purpose, set
`CONSOLE_NEW_MASTER_KEY=true` (`--new-master-key`; Helm `console.newMasterKey`): the console starts
(with a new key if there is none), and the stored secrets are lost. Engines registered with mTLS
must then be registered again: `syncd-console admin register-engine --update` (or `replace: true`
on `POST /api/v1/engines`) seals a new client key over the one that no longer opens, keeps the
engine's id, and is audited as `engine.re-registered`. Without `--update`, the registration is
refused with that reason rather than reported as done. Webhooks need their URL saved again and a
new secret, and secret environment values typing again.

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
| Plain text on a loopback address (no `api.tls`) | Read-only by default: status, the status stream, dead letters, held changes, quality failures, the running configuration, the object explorer and lineage without sensitive values, the reconciliation report, the password public key. `api.local_control: true` also allows commands; `api.local_reveal: true` also allows sensitive values. Any local account on the host can connect |
| mTLS (`api.tls`: `cert_file`, `key_file`, `client_ca_file`) | Everything, to clients whose certificate chains to `client_ca_file`. Required for any address that is not loopback; the engine refuses to start otherwise |
| `api.control_socket` (a Unix socket path) | Everything, to the engine's own user and root only: the socket is created under a temporary name, set to mode 0600, then moved into place. The packaged unit creates `/run/syncd` for it (`RuntimeDirectory`, mode 0750). The console can be registered at it (`unix:/run/syncd/control.sock`, without mTLS) only when it runs as a user that may open it; the packaged console runs as `syncd-console`, so it keeps mTLS |

- **Commands** are the calls that change something or reach out from the engine: runs,
  approvals, drift checks, dead-letter retry and discard, `ApplyConfig`, `SimulateConfig`,
  connector tests and schema discovery, previews, correlation review, suggestions and joins,
  group reviews, history prunes, and the password calls (`SubmitPasswords`, agents,
  `ChangeOwnPassword`). Each is logged with its caller (the address and certificate common name,
  or the uid and pid on the control socket).
- **`Backup` and `PullSnapshot`** hand out the whole store, the password key and the agent CA's
  key (sealed, when a passphrase is set). They need what a reveal needs (mTLS, the control
  socket, or `api.local_reveal: true`); `Backup` on the loopback listener also needs
  `api.local_control: true`.
- **The engine does not check which certificate a client presents** beyond its chain to
  `client_ca_file`: any certificate that CA issued has full access, including backups and
  password submission. Use a CA dedicated to the control API, as the installer does.
- **The passive engine of an HA pair** refuses commands, naming the active one.
- TLS goes through rustls on the process crypto provider, with its safe default protocol
  versions (TLS 1.2 and 1.3), and TLS handshakes time out after 10 seconds.

### The packaged certificates

The installer creates a CA of the host's own in `/etc/syncd/pki` (ECDSA P-256, valid for 10 years;
nothing outside the host trusts it) and issues:

| File | For | Readable by |
|---|---|---|
| `ca.crt`, `ca.key` | The control API's CA | `ca.key`: root only |
| `engine.crt`, `engine.key` | The engine's server certificate (`localhost`, `127.0.0.1`, `::1`) | `engine.key`: root and the `syncd` group |
| `console-client.crt`, `console-client.key` | The console's client certificate. The installer registers the engine with it, so the console holds the key sealed in its database | root and the `syncd-console` group |
| `cli.crt`, `cli.key` | The command line's client certificate; also what the passive engine of an HA pair pulls snapshots with | root and the `syncd` group |

The packaged `engine.yaml` listens on `127.0.0.1:7443` with mTLS, so the console and the command
line both use certificates even on loopback. `sudo syncd status` (as root or a member of the
`syncd` group) finds `cli.crt` itself; `sudo syncdctl cli-cert` issues it on a host that lacks one.
`--api-cert`, `--api-key` and `--api-ca` (or `SYNCD_API_CERT`, `SYNCD_API_KEY`, `SYNCD_API_CA`) name
other files. `--api-key` is the certificate's private key file, not a token.

Membership of the `syncd` group therefore grants full control of the engine. Keep it to
administrators.

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
`console.key` (mode 0440). The CA key stays in the Secret, so anyone who can read Secrets in the
namespace can issue a certificate with full control of the engine: restrict that with RBAC. To
renew, delete the Secret and run `helm upgrade`; the engine restarts with the new certificates,
and the registration Job updates the console. There is no command-line client certificate:
`kubectl exec … -- syncd status` uses the engine's control socket.

## Password synchronisation

From 0.4.0 the engine delivers password changes to the accounts it manages: from capture agents,
from the console's self-service page, and as initial passwords of new accounts. See
[Password synchronisation](password-sync.md#security-properties) for how to run it.

### Cryptography

| Part | Implementation |
|---|---|
| Envelope | HPKE (RFC 9180, base mode): DHKEM(P-256, HKDF-SHA256), HKDF-SHA256, AES-256-GCM, implemented by syncd on aws-lc-rs primitives (so the FIPS module in a FIPS build). The key's id is the additional data; captured changes and self-service requests use different HPKE `info` strings, so one can never be taken for the other. Checked against the RFC 9180 test vector |
| The engine's password key | A P-256 key pair, created on first start as `password.key` in the data directory (`passwords.key_file`), mode 0600, sealed with the passphrase when one is set (below). Capture points get only the public half (`GetPasswordKey`) |
| The agent CA | A P-256 key and a ten-year self-signed certificate (`password-agent-ca.key`, mode 0600, and `.crt` beside the password key), made with `rcgen` on aws-lc-rs. Its key is sealed like the password key. It issues each agent's client certificate (client authentication only, the subject is the agent's name whatever the request asked, `passwords.ingest.agent_cert_days`, 365 by default) and, at every start, the ingest listener's own server certificate |
| The keys at rest | From 0.4.1, with `SYNCD_PASSWORD_KEY_PASSPHRASE_FILE` (a file holding the passphrase; `SYNCD_PASSWORD_KEY_PASSPHRASE`, the passphrase itself, is read when the file variable is unset), both key files are **sealed**: PBKDF2-HMAC-SHA256 (600,000 iterations, a random 16-byte salt) derives an AES-256-GCM key from the passphrase, both through aws-lc-rs, so a FIPS build uses its FIPS module. The file's format line and derivation parameters are the additional data. Plain files from before are sealed in place at the next start, with the same keys. A sealed file without the passphrase, or with the wrong one, stops the engine with an error naming the variable. **Without a passphrase the keys are stored in the clear** (the password key as hex, the CA key as PEM), protected by the file mode only, as in 0.4.0: the engine warns at start and `syncd status` says `password keys: stored in the clear`. The status API's `keys_sealed` and the console's engine page say which |
| Enrollment tokens | `syncd password agent add <name>` (control access) prints a one-time token, good for an hour. The engine keeps only its SHA-256 and compares in constant time. The agent makes its own key, which never leaves its host, and sends a signing request with the token; the token is then spent, and an earlier certificate of the same agent stops working |
| Agent access | The ingest listener is mutual TLS with the agent CA. Every call but `Enroll` needs a certificate whose fingerprint the engine recorded and has not revoked. `syncd password agent revoke` refuses it at once; the check is the fingerprint, not a CRL. A revoked name cannot be enrolled again |
| Passwords in memory | Opened in memory only, just before delivery, and zeroized when dropped. Never logged, never in `Debug` output, status, metrics, lineage or history. The LDAP library's encoding buffers are not zeroized, and there is no locked memory or core-dump suppression |
| Echo suppression | A change captured right after syncd wrote the same password is dropped, recognised by a keyed hash kept for 10 minutes, with a random key per process |
| Writing to targets | Password Modify (RFC 3062), `userPassword` replace, or Active Directory's `unicodePwd`, which is refused unless the connection is LDAPS or StartTLS; since 0.4.0 an AD target with `passwords` over `ldap://` without `starttls` is refused when the configuration is validated. SCIM targets get `password` over HTTPS |

- **No rotation yet.** There is one password key and one agent CA. A new key file makes changes
  still queued under the old key unreadable (they are counted as failed), and a new agent CA
  means enrolling every agent again. There is no command to change the passphrase either.
- **Seal the keys, and keep the passphrase apart.** Whoever reads the key files and the queued
  changes in the store can open the changes; with the keys sealed, they also need the passphrase.
  Backups and HA snapshots carry both key files as they are on disk: sealed, or in the clear
  (see [Backups](#backups)). Both engines of an HA pair, and an engine restored from a backup,
  need the passphrase the keys were sealed with; a lost passphrase cannot be recovered (start
  with new keys, and enroll the agents again). A rollback to 0.4.0 cannot open sealed keys:
  restore the plain files from the upgrade's backup first.
- **Protect the data directory** as you would the passwords themselves, sealed or not.
- The ingest listener (`passwords.ingest.listen`) is a network port of its own. Open it only to
  the hosts agents run on (on Kubernetes, `networkPolicy.passwordIngestFrom`). With HA, only the
  active engine listens on it.
- There are no capture plugins yet (the Windows password filter, the 389 and OpenLDAP plugins),
  and no threat model or penetration test of password synchronisation has been published.

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
  promotions and Git sync; not to `syncd apply-config` on the engine.

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
| LDAP and Active Directory | `ldaps://`, or `starttls: true`, checked against the host's trust store |
| SCIM 2.0 targets, Entra ID (Microsoft Graph) | HTTPS through rustls on the process provider. SCIM: a bearer token, basic credentials, or OAuth 2.0 client credentials. Entra ID: a client secret (no certificate credential yet) |
| CSV sources | Files on the engine's host or a mounted share; nothing on the network |
| JDBC bridge | A token (`--token-file` on the bridge) is required unless the bridge is on loopback; beyond loopback it must be `https://` |
| Debezium Server sidecar | The engine listens for its HTTP sink. Beyond loopback a token, which the sink puts in its URL path, is required |
| Password ingest listener | Mutual TLS with the engine's agent CA (see [Password synchronisation](#password-synchronisation)) |
| HA lease and snapshots | The lease's PostgreSQL connection with TLS (`tls_verify`, `ca_file`) when the URL asks for it; snapshots pulled over the control API, mTLS |
| S3 backups | HTTPS with SigV4 signing on the process provider; `--s3-ca` or `engine.backup.ca_file` for a private CA |
| Prometheus `/metrics` | Plain HTTP, no authentication, counters and states only. Keep `api.metrics_listen` on loopback or a management network |
| Single sign-on provider | HTTPS (plain HTTP only to loopback), checked against the Mozilla root set plus `CONSOLE_OIDC_CA_FILE`; redirects are not followed; 10-second timeout |
| Webhooks and the pre-publish hook | HTTPS only, the address checks above, the Mozilla root set plus `CONSOLE_WEBHOOKS_CA_FILE`, no proxy |
| Splunk HTTP Event Collector | HTTPS, checked against the public roots, unless `ENS_AUDIT_SPLUNK_INSECURE=true` (test systems only) |
| syslog | `ENS_AUDIT_SYSLOG_PROTOCOL`: `tls` (checked against the public roots), or `tcp` and `udp` in plain text |
| SMTP (scheduled reports) | STARTTLS by default, checked against the public roots or `CONSOLE_SMTP_CA_FILE`; AUTH PLAIN only after STARTTLS. `CONSOLE_SMTP_STARTTLS=false` sends in plain text: a relay on the host or a private network only. No implicit TLS (SMTPS). Reports go only to `CONSOLE_REPORTS_RECIPIENT_DOMAINS`, checked again at every run |
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
| Console | Roles in `CONSOLE_REVEAL_ROLES` (default `admin`; `none` for nobody), with a session only. A reveal is recorded before the values are returned, with the names of the attributes it showed; if the audit trail cannot be written, the reveal is refused. A refused attempt is audited too |
| Engine | Callers on mTLS or the control socket, or on loopback with `api.local_reveal: true`. Each reveal is logged with its caller |

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
target, outcome, details, client address and user agent. See [Audit logging](../audit-logging.md).

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

The engine itself does not write an audit trail: its control requests, reveals, backups and
password deliveries go to its journal with their caller.

## Backups

- **What a backup holds.** An engine backup (`syncd backup`, `engine.backup`) holds the store,
  which carries entity data, the configuration file, and the password key and the agent CA's key
  **as they are on disk**: sealed with the passphrase from 0.4.1 when one is set, otherwise in
  the clear. Any password changes still queued are in it too (sealed to the password key). A
  `syncdctl backup --to` adds a dump of the console's database (with the sealed secrets and the
  audit trail), an archive of all of `/etc/syncd` (the service settings, the PKI with its keys,
  `engine.env` with the source and target secrets, and anything else kept there), and, from
  0.4.1, the console's master key. An HA snapshot holds what an engine backup holds.
- **Protect the destination accordingly.** syncd does not encrypt backups itself. Protect a
  directory destination, and the share it is on, as you would the data directory. For S3, ask for server-side
  encryption (`--sse AES256` or `aws:kms[:key]`, `engine.backup.sse`), restrict the bucket's
  policy, and prefer an IAM role (IRSA, EKS Pod Identity) to static keys. S3 credentials come
  from the engine's environment (`AWS_*` in `/etc/syncd/engine.env`) or the shared credentials
  file; the instance metadata service is not asked.
- **The console's master key** is in an off-host backup from 0.4.1 (#141): `syncdctl backup
  --to` writes it as `<name>.console-master.key` beside the database dump, or, when it is set as
  `CONSOLE_MASTER_KEY` in `/etc/syncd/console.env`, says it is in the `/etc/syncd` archive.
  `syncdctl restore --from` puts it back (the key it replaces is kept as `master.key.previous`).
  A 0.4.0 off-host backup lacks it: without the key, a restored console database cannot open
  what it sealed. So whoever can read the destination can open every secret the console seals.
  The host-local `syncdctl backup` (`/var/lib/syncd/backups`, root only) has always held it.
- **The password-key passphrase** travels with an off-host backup if its file is under
  `/etc/syncd` (the runbook's example is `/etc/syncd/engine/password-key.passphrase`), since the
  whole directory is archived. Keep it outside `/etc/syncd`, or accept that the destination then
  holds what opens the keys. Backups taken before the keys were sealed hold them in the clear.
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
  hosts' clocks: keep them in NTP sync);
- every export batch and every password delivery checks the lease first and is refused when it
  is not held for more than another second, counted from when the renewal was sent;
- an engine that loses the lease steps down to passive.

The lease's database URL (`SYNCD_HA_LEASE_URL`) holds a database password: keep it in
`engine.env`, as a secret reference. Its role needs to read and write `syncd_lease` (and to create
it, unless the console's migrations already did). The
passive engine pulls the whole store and key material from the active one over mTLS, so the two
hosts' certificates come from one CA and their control API port (7443) is open between them
only. With sealed password keys, both engines need the same passphrase.

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
| Privileges | `allowPrivilegeEscalation: false`, all capabilities dropped, seccomp `RuntimeDefault`, no service account token mounted (`automountServiceAccountToken: false`) |
| Secrets | The console's database URL, master key and initial admin password come from Secrets, as environment variables; the single sign-on client secret from a Secret of your own, mounted as a file (`console.oidc.existingSecret`); the engine's source and target secrets from a Secret you name (`engine.envFromSecret`); from chart 0.4.1, the password-key passphrase from a Secret of your own, mounted as a file (`engine.passwordKeyPassphrase.existingSecret`, key `passphrase`). Other secret files (a Splunk token, a Git key) come in through `console.extraVolumes` |
| Network | The engine listens on `0.0.0.0:7443` (control API, mTLS required) and `0.0.0.0:9464` (metrics, plain HTTP, unauthenticated); the console on `0.0.0.0:8080`. With `networkPolicy.enabled`, one NetworkPolicy per component lets only the console and the registration Job reach 7443, only the namespaces and pods you select reach 9464 (`monitoringNamespaceSelector`, `monitoringPodSelector`) and 8080 (`ingressNamespaceSelector`, `ingressControllerSelector`, `console.extraIngressFrom`), and, for an HA pair, the engines reach each other. From chart 0.4.1, `engine.passwordIngest.enabled` opens the password ingest port (7445 by default) on a Service of its own (`<release>-syncd-engine-ingest`), reached from `networkPolicy.passwordIngestFrom` (from anywhere when that is empty; agents still need a certificate the engine issued). Egress stays open unless `networkPolicy.egress.enabled`, which closes it to DNS and the control API plus your `networkPolicy.egress.extra` rules (add the console's database, sources and targets, the identity provider, webhook receivers and the audit outputs). Your CNI must enforce them |
| TLS to browsers | Through the chart's Ingress and your controller. With `console.tls.existingSecret` the console also serves HTTPS itself, so the hop from the controller is encrypted |

Set `console.trustedProxies` to the ingress controller's addresses, so that the audit trail and
the sign-in limit see client addresses.

## Supply chain

- **Dependencies.** `cargo-deny` runs on every change (`deny.toml`): licences on an allow-list,
  yanked crates denied, crates only from crates.io, no git or unknown registries, no wildcard
  versions. `cargo-audit` checks advisories; the few advisories ignored are each justified in
  `deny.toml` or `.cargo/audit.toml`, and CI checks that the code they concern stays unbuilt.
- **Fuzzing.** The decoders of what sources send (`pgoutput`, DirSync, syncrepl) are fuzzed in CI.
- **Releases.** A release is built in CI from a `v*` tag (Rocky Linux 9, and Rocky Linux 8 for
  the RHEL 8 `.rpm`), after the package lifecycle tests pass. **No tag has been pushed yet, so
  nothing is published so far.** A release carries the x86_64 bundle, `.deb` and `.rpm`, an
  x86_64 FIPS bundle, the RHEL 8 `.rpm`, and the aarch64 bundle, `.deb` and `.rpm` (only when the
  repository's arm64 runners are enabled), with:
    - `SHA256SUMS` over every file, and its **GPG signature** `SHA256SUMS.asc`, made in a
      protected `release` environment that holds the key. Without the key the workflow warns and
      publishes unsigned, and its summary says so. Verify the signature and the checksums before
      installing (see [Installation](install.md));
    - **CycloneDX SBOMs** of the engine and the console (`syncd-<version>.cdx.json`,
      `syncd-console-<version>.cdx.json`).

    Inside the bundle, `MANIFEST.sha256` lists every file, and the installer checks it. There are
    no signed apt or dnf repositories: the packages are on the release only. The packaging
    scripts fetch the pinned nfpm and check its SHA-256.
- **Container images and chart.** A `v*` tag also pushes `ghcr.io/winllc/syncd:<version>`,
  `ghcr.io/winllc/syncd-console:<version>`, their `<version>-fips` variants and the chart
  (`oci://ghcr.io/winllc/charts/syncd`). None is published until a release is tagged.
    - **Multi-arch:** `linux/amd64` and `linux/arm64` (amd64 alone when the arm64 runners are
      off); the FIPS images are amd64 only. Tagged by version only (no `latest`).
    - **Signed:** each image and the chart are signed with cosign (keyless, the release
      workflow's GitHub identity, by digest). Verify with `cosign verify` against the workflow
      identity before mirroring.
    - **SBOM:** an SPDX SBOM of each image, per platform, attached as a signed cosign
      attestation (`cosign verify-attestation --type spdxjson`). The image SBOMs are
      attestations, not release files.
    - **Scanned:** on every pull request CI builds the standard, FIPS and (with arm64 runners)
      arm64 images and scans the amd64 ones with Trivy; a fixable HIGH or CRITICAL finding fails
      the build. Accepted risks are listed in `.trivyignore`, each with its reason, who accepted it
      and until when. At 0.4.1 it still holds one: CVE-2026-84782 (OpenSSL's DTLS, in the distroless
      base's `libssl3`, which syncd does not link), accepted until 2026-12-08.
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
- [ ] Single sign-on with a role map, and local sign-in limited to break-glass accounts
- [ ] Configuration approval required where more than one person designs configurations; a
      ticket pattern and pre-publish hook where change management requires them
- [ ] `CONSOLE_REVEAL_ROLES` as narrow as your policy allows (`none` if nobody needs to see values),
      and `attributes.sensitive` listing every personal-data attribute
- [ ] API tokens with the narrowest scopes and an expiry, one per system, revoked when unused
- [ ] Webhooks limited with `CONSOLE_WEBHOOKS_ALLOWED_HOSTS`, and private networks left blocked
- [ ] The control API on loopback with mTLS (as packaged), `local_control` and `local_reveal` off,
      and the `syncd` group kept to administrators
- [ ] With password synchronisation: the keys sealed (`SYNCD_PASSWORD_KEY_PASSPHRASE_FILE`, its
      file outside `/etc/syncd` and kept where a restore can find it), the engine's data directory
      readable by the engine only, the ingest listener open only to agent hosts, unused agents
      revoked
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
