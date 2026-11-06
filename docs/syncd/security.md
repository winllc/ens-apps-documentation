# Security

This page summarises syncd's security controls and the settings that affect them: the
console's sign-in, sessions and roles, the engine's control API, secrets, the audit log,
the systemd hardening and the supply chain.

## Trust model

- **The engine holds the credentials.** It connects to every source and target with the
  accounts its configuration names. The configuration never holds a secret: each password or
  token is a reference to an environment variable (`password: { env: NAME }`) or a file
  (`{ file: /path }`). On a packaged install, the variables are in `/etc/syncd/engine.env`,
  readable by root and the `syncd` group only.
- **The console holds no source or target passwords.** It stores configuration versions
  (references only), accounts, sessions, the audit log, and each engine's control API client
  key, which is encrypted with the console's master key.
- **The control API is the engine's front door.** Anyone who can call its commands can run,
  approve, reload a configuration, or read sensitive values. Off loopback it requires mutual
  TLS. Restrict who holds a client certificate issued by its CA to the console and the host's
  administrators.
- **Editing configuration is privileged.** A configuration decides which systems the engine
  connects to and which of the host's secret references it sends them. Give the designer role
  accordingly, and consider [configuration approval](#configuration-approval-four-eyes).

## Authentication

The console has local accounts and, optionally, single sign-on through OpenID Connect. It has
no SAML, no client-certificate (X.509, CAC/PIV) sign-in and no API tokens.

| Control | Implementation |
|---|---|
| First sign-in | The first start creates `admin` with a random 24-character password, printed once and written to `$CONSOLE_DATA_DIR/initial-admin-password` (mode 0600). An installer can supply one with `CONSOLE_ADMIN_PASSWORD` or `CONSOLE_ADMIN_PASSWORD_FILE`; `admin`, empty, and passwords failing the policy are refused at startup. The first sign-in must set a new password, and the file is then deleted |
| Forced password change | An account with a one-time password can call nothing but `POST /api/v1/auth/password` (and `GET /api/v1/auth/me`) until it sets a new one. Every other call answers 403 `password_change_required` |
| Password policy | At least 12 characters; not the username and not `admin` (case-insensitive); not the current password. There are no complexity, history or expiry rules |
| Password hashing | Argon2id (the `argon2` crate's defaults), or PBKDF2-HMAC-SHA512 with 210 000 iterations and a 16-byte salt in FIPS mode, stored as PHC strings. Salts and generated passwords come from aws-lc-rs. In FIPS mode Argon2id hashes are not accepted, so those accounts need a reset |
| Per-address throttle | 20 sign-in attempts per client address in 5 minutes, then 429 `too_many_requests`, audited as `auth.login` denied (`rate limited`). The counters are in memory and reset when the console restarts |
| Account lockout | 5 consecutive wrong passwords lock the account for 15 minutes, audited with `"locked": true`. A successful sign-in resets the count. These numbers are fixed |
| Uniform refusals | Every failed sign-in gets the same 401 `sign_in_failed`, whether the account is unknown, disabled, locked, has no local password, or is outside break-glass. An unknown account spends as long as a real password check. The audit log records the real reason |
| Account recovery | `syncd-console admin reset-password [--user NAME]` on the host issues a one-time password. It needs the console's database settings (`console.env`) |
| Single sign-on | OpenID Connect authorization code flow with PKCE, state and nonce; ID token signature (asymmetric algorithms only), issuer, audience and expiry checked. Accounts are bound to the provider's subject, never taken over by username. Roles come from a claim at every sign-in. See [Single sign-on](single-sign-on.md) |
| Break-glass | With single sign-on configured, an admin can limit local sign-in to accounts marked break-glass (**Users** → Local sign-in). At least one enabled break-glass admin with a password must remain; switching signs out the local accounts that can no longer sign in |
| Disabling an account | Takes effect at the account's next request: its sessions are refused, and single sign-on cannot sign it in. The last enabled admin cannot be disabled or demoted |

### Configuration approval (four eyes)

An admin can require it (**Users** → Configuration approval). A version is then published only
after a designer or admin other than its author approves it. The database enforces it: a
constraint refuses an approval by the author, and a trigger refuses any change to an approval
once given. Versions published before can be published again without one, so a rollback is
never held up. Approvals and changes of the setting are audited (`config.approve`,
`settings.config_approval`).

The engine's control API is not gated by approval: `syncd apply-config`, or any holder of a
client certificate, can still apply a configuration directly.

## Sessions

- A session is a random 32-byte token in the cookie `syncd_session`: `HttpOnly`,
  `SameSite=Strict`, `Path=/`, and `Secure` except in development mode (`SYNC_DEV_MODE`). The
  database keeps only the token's SHA-256, so reading the `sessions` table hands out no live
  session.
- A session ends after `CONSOLE_SESSION_IDLE_MINUTES` without activity (default 30) or
  `CONSOLE_SESSION_MAX_HOURS` after sign-in (default 12). Activity extends the idle window.
- Signing out deletes the session. Changing a password signs out the account's other sessions.
- Live status streams re-check their session every 30 seconds and end when it is over.
- There is no limit on concurrent sessions per account, and no separate idle timeout for
  admins.
- Browsers keep `Secure` cookies only over HTTPS or on localhost. The console warns at startup
  when it listens beyond loopback without an `https://` `CONSOLE_PUBLIC_URL`, and the sign-in
  page says so when the browser drops the cookie.

## CSRF and browser security headers

- **No CSRF token or request header check.** The defence is the session cookie's
  `SameSite=Strict`: browsers do not send it on requests that another site starts. Calls that
  take a body accept JSON only (`Content-Type: application/json`), which a cross-site form
  cannot send without a CORS preflight, and the console sends no CORS headers. Some calls take
  no body (publish, approve, reset password, sign out), so they rely on `SameSite` alone; use
  a browser that enforces it.
- **Headers** on every response:

| Header | Value |
|---|---|
| `Content-Security-Policy` | `default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'` (inline styles are allowed because the Monaco editor sets them at run time; scripts only from the console) |
| `X-Content-Type-Options` | `nosniff` |
| `X-Frame-Options` | `DENY` |
| `Referrer-Policy` | `no-referrer` |
| `Cache-Control` | `no-store`, except the UI's hashed assets (cached for good) and its other files (`no-cache`) |

- Neither the console nor the shipped nginx configuration sends `Strict-Transport-Security`.
  Add it in nginx if your policy requires it.
- Error responses carry a code and a message only. Internal errors answer `internal error`; the
  details go to the console's log.

## Authorization

Every account has one role. Roles are checked on every API call.

| Role | Can |
|---|---|
| `viewer` | Read engines, status, dead letters, configuration versions and diffs, the object explorer, rule previews and the function catalogue |
| `auditor` | What a viewer can, plus the audit log and its verification |
| `operator` | What a viewer can, plus runs (reconcile, full sync), deletion approvals, drift checks, dead-letter retry and discard, and joining or unjoining records |
| `designer` | What a viewer can, plus validating, saving, importing, simulating, previewing, approving and publishing configuration versions, and connector checks |
| `admin` | Everything, including accounts, sign-in settings, configuration approval, banners, and registering and removing engines |

Revealing sensitive values is separate: see [Sensitive attributes](#sensitive-attributes-and-reveal).
The per-route roles are in the [API reference](api-reference.md#console-rest-api).

## Behind a reverse proxy

The console serves plain HTTP. For access from other machines, put nginx with TLS in front of it;
the release ships `share/nginx/syncd.conf`, which redirects HTTP to HTTPS, allows TLS 1.2 and 1.3,
sets `X-Forwarded-For` to the connection's address (replacing whatever the client sent), and
passes server-sent events through unbuffered. See [Installation](install.md).

`CONSOLE_TRUSTED_PROXIES` lists the proxies (addresses or CIDR ranges) whose `X-Forwarded-For`
names the client. The console reads the header from the right, past trusted proxies, to the first
address that is not one. A connection from anywhere else is taken at its own address, whatever
header it sends. The client address goes to the audit log and to the per-address sign-in limit;
without the setting, every request seems to come from the proxy and all users share one sign-in
budget. List only proxies you run.

Keep `CONSOLE_LISTEN` on `127.0.0.1` when nginx runs on the same host.

## Secrets

### Engine secrets

Source and target passwords, bridge and Debezium tokens are read from environment variables or
files at the moment they are needed; they never appear in the configuration, in `GetConfig`, or in
configuration exports. A configuration moved between consoles lists the variables and files the
new host must provide.

### Console secrets and the master key

The console encrypts stored secrets (today: each engine's control API client key) with envelope
encryption:

- each secret gets its own random 256-bit data key, and is sealed with AES-256-GCM;
- the data key is wrapped with the master key (AES-256-GCM);
- both carry the secret's location (for example `engine:<name>:client_key`) as additional data, so
  a ciphertext copied to another row does not open;
- all of it goes through aws-lc-rs, so FIPS mode covers it.

The master key is 32 bytes, from `CONSOLE_MASTER_KEY` (base64) or `$CONSOLE_DATA_DIR/master.key`
(`/var/lib/syncd/console/master.key` on a packaged install), which the console creates (mode 0600)
on first start and logs a warning to back it up. **Without it, stored secrets cannot be
decrypted.** Backups taken by `syncdctl` include it, so protect them accordingly. There is no KMS or
Vault integration.

Client keys are never returned by the API.

## The engine control API

The engine serves its gRPC control API on `api.listen` (default `127.0.0.1:7443`).

| Listener | Allowed |
|---|---|
| Plain text on a loopback address (no `api.tls`) | Read-only by default: status, the status stream, dead letters, the running configuration, the object explorer without sensitive values. `api.local_control: true` also allows commands; `api.local_reveal: true` also allows sensitive values. Any local account on the host can connect |
| mTLS (`api.tls`: `cert_file`, `key_file`, `client_ca_file`) | Everything, to clients whose certificate chains to `client_ca_file`. Required for any address that is not loopback; the engine refuses to start otherwise |
| `api.control_socket` (a Unix socket path) | Everything, to the engine's own user and root only: the socket is created under a temporary name, set to mode 0600, then moved into place |

- **Commands** are the calls that change something or reach out from the engine: runs, deletion
  approvals, drift checks, dead-letter retry and discard, `ApplyConfig`, `SimulateConfig`, connector
  tests and schema discovery, previews, correlation review, and joins and unjoins. Each is logged with
  its caller (the address and certificate common name, or the uid and pid on the control socket).
- **The engine does not check which certificate a client presents** beyond its chain to
  `client_ca_file`: any certificate that CA issued has full access. Use a CA dedicated to the
  control API, as the installer does.
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
| `cli.crt`, `cli.key` | The command line's client certificate | root and the `syncd` group |

The packaged `engine.yaml` listens on `127.0.0.1:7443` with mTLS, so the console and the command
line both use certificates even on loopback. `sudo syncd status` (as root or a member of the
`syncd` group) finds `cli.crt` itself; `sudo syncdctl cli-cert` issues it on a host that lacks one.
`--api-cert`, `--api-key` and `--api-ca` (or `SYNCD_API_CERT`, `SYNCD_API_KEY`, `SYNCD_API_CA`) name
other files. `--api-key` is the certificate's private key file, not a token.

Membership of the `syncd` group therefore grants full control of the engine. Keep it to
administrators.

## Sensitive attributes and reveal

`attributes.sensitive` in the engine configuration lists attributes whose values are personal
data (a national ID, a birth date). Their values are withheld, and not searched, in the object
explorer, previews, simulations, connector samples and entity history. What derives from them is
hidden too: computed attributes that read them, source fields that flow into them, and target
attributes built from them. DNs are not masked, so do not build DNs from sensitive attributes.

| Layer | Who may reveal |
|---|---|
| Console | Roles in `CONSOLE_REVEAL_ROLES` (default `admin`; `none` for nobody). A reveal is recorded before the values are returned, with the names of the attributes it showed; if the audit log cannot be written, the reveal is refused. A refused attempt is audited too |
| Engine | Callers on mTLS or the control socket, or on loopback with `api.local_reveal: true`. Each reveal is logged with its caller |

A search that reveals is audited with the SHA-256 of its text rather than the text itself, which
may be a sensitive value; an ordinary search is audited with its text. Attributes the running
configuration treats as sensitive stay hidden in a simulation even if the simulated version
drops them.

## Audit log

The console records every sign-in and refusal, sign-out, account change, settings change, engine
registration, configuration save, import, export, approval, simulation and publish, engine
operation, connector check, explorer search, entity view and reveal, with the actor, target,
outcome (`success`, `failure` or `denied`), details and client address.

- **Append-only.** Triggers on `audit_log` refuse `UPDATE`, `DELETE` and `TRUNCATE`.
- **Hash-chained.** Each row stores SHA-256 of the previous row's hash followed by its own
  canonical content (time in microseconds, actor, action, target, outcome, details text, client
  address). Appends are serialised with a transaction-scoped advisory lock.
- **Verification.** `GET /api/v1/audit/verify` (admins and auditors) recomputes the chain from the
  first row and returns the number of entries checked and the id of the first entry whose hash does
  not match, if any. This finds history edited past the triggers, by someone with direct database
  access.
- **Recorded first where it matters.** A publish (`config.publish.start`), a reveal, and settings
  and account changes are refused when their record cannot be written; other events log an error
  and carry on.

The audit log lives only in the console's database. syncd does not yet write the ENS audit format
or forward to a SIEM; see [Audit logging](../audit-logging.md) for the suite-wide picture. Keep the
database backed up (`syncdctl backup`), and restrict who can connect to it as its owner.

The engine itself does not write an audit log: its control requests and reveals go to its journal
with their caller.

## Other connections

| Connection | Protection |
|---|---|
| PostgreSQL, MySQL, SQL Server, MongoDB sources | `tls: disable` (the default), `require` (encrypted, server not verified), or `verify_full` (chain and host name checked, with `ca_file` or the Mozilla root set) |
| LDAP and Active Directory | `ldaps://`, or `starttls: true`, checked against the host's trust store |
| JDBC bridge | A token (`--token-file` on the bridge) is required unless the bridge is on loopback; beyond loopback it must be `https://` |
| Debezium Server sidecar | The engine listens for its HTTP sink. Beyond loopback a token, which the sink puts in its URL path, is required |
| Prometheus `/metrics` | Plain HTTP, no authentication, counters and states only. Keep `api.metrics_listen` on loopback or a management network |
| Single sign-on provider | HTTPS (plain HTTP only to loopback), checked against the Mozilla root set plus `CONSOLE_OIDC_CA_FILE`; redirects are not followed; 10-second timeout |

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

## Supply chain

- **Dependencies.** `cargo-deny` runs on every change (`deny.toml`): licences on an allow-list,
  yanked crates denied, crates only from crates.io, no git or unknown registries, no wildcard
  versions. `cargo-audit` checks advisories; the few advisories ignored are each justified in
  `deny.toml` or `.cargo/audit.toml`, and CI checks that the code they concern stays unbuilt.
- **SBOM.** CI produces a CycloneDX SBOM (`cargo cyclonedx --all-features`) as a build artifact. It
  is not attached to releases.
- **Fuzzing.** The decoders of what sources send (`pgoutput`, DirSync, syncrepl) are fuzzed in CI.
- **Releases.** A release is built in CI from a `v*` tag (Rocky Linux 9, and Rocky Linux 8 for the
  RHEL 8 `.rpm`), after the package lifecycle tests pass. It carries a `SHA256SUMS` for the bundle,
  `.deb`, `.rpm` and RHEL 8 `.rpm`, and a `.sha256` for the bundle. Inside the bundle,
  `MANIFEST.sha256` lists every file, and the installer checks it. Releases are not signed (no GPG
  or cosign signature yet): get the checksums from the release page and verify them before
  installing. The packaging scripts fetch the pinned nfpm and check its SHA-256.

See [Releases](../releases.md) for the suite's release process.

## FIPS 140-3 and DISA STIG

See [FIPS 140-3 and DISA STIG](fips-and-stig.md) for the optional FIPS mode, what it changes, what
is not validated, and the settings a STIG deployment needs.

## Hardening checklist

- [ ] HTTPS in front of the console (the shipped nginx configuration), `CONSOLE_LISTEN` on
      `127.0.0.1`, and `CONSOLE_PUBLIC_URL` set to the `https://` address
- [ ] `CONSOLE_TRUSTED_PROXIES` set to your proxy only
- [ ] `SYNC_DEV_MODE` unset in production
- [ ] The initial admin password changed, and further admins named rather than shared
- [ ] Single sign-on with a role map, and local sign-in limited to break-glass accounts
- [ ] Configuration approval required where more than one person designs configurations
- [ ] `CONSOLE_REVEAL_ROLES` as narrow as your policy allows (`none` if nobody needs to see values),
      and `attributes.sensitive` listing every personal-data attribute
- [ ] The control API on loopback with mTLS (as packaged), `local_control` and `local_reveal` off,
      and the `syncd` group kept to administrators
- [ ] The master key (`/var/lib/syncd/console/master.key`) and `syncdctl` backups stored securely and
      apart
- [ ] Source and target connections with `tls: verify_full`, `ldaps://` or `starttls: true`
- [ ] `api.metrics_listen` on loopback or a management network
- [ ] The console database reached over TLS when it is on another host
      (`sslmode=require` or stricter in `CONSOLE_DATABASE_URL`), and its owner role kept private
- [ ] Release checksums verified before every install and upgrade
