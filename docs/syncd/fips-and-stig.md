# FIPS 140-3 and DISA STIG

This page says what syncd relies on for FIPS 140-3, how its optional FIPS mode is turned on and
what it changes, what has not been tested or validated, and which settings a DISA STIG
deployment needs. It describes syncd 0.5.0 (commit `a9d17c6`). The suite-wide picture is in
[FIPS 140-3 and DISA STIG](../fips-and-stig.md).

!!! note "No ENS validation"
    ENS Solutions does not supply or claim a FIPS 140-3 validated cryptographic module, and
    syncd has not been validated or certified. syncd's FIPS build links the AWS-LC FIPS
    module; its validation status is AWS's to state. Whether a deployment meets FIPS 140-3
    depends on that module, the operating system, the TLS proxy and the database server you
    choose and configure.

## FIPS 140-3

### What syncd relies on

syncd is written in Rust and does not use the operating system's OpenSSL or a Java runtime.
Its cryptography goes through **aws-lc-rs**, AWS's Rust binding of AWS-LC, in both programs:

- **TLS** is rustls with the aws-lc-rs provider. `engine/crates/crypto` installs one provider
  for the whole process at startup, and the control API (server and client), the console's
  connections to engines and to the OpenID Connect provider, the connectors and, from 0.5.0, the
  clients of Vault, AWS and Azure (secrets and KMS) are built on it. The capture agent
  (`syncd-capture`, from 0.5.0) installs the same provider.
- **Hashing, encryption and randomness** in the console also use aws-lc-rs: SHA-256 for
  session tokens, API tokens, the audit hash chain, PKCE challenges and explorer search digests;
  HMAC-SHA256 for webhook and pre-publish hook signatures; AES-256-GCM for envelope encryption
  under the master key; PBKDF2-HMAC-SHA512 for local passwords in FIPS mode; random bytes for
  tokens, signing secrets, salts, keys and generated passwords.
- **Password synchronisation** in the engine uses aws-lc-rs too: P-256 key generation and
  ECDH, HKDF-SHA256 (built from aws-lc-rs's HMAC-SHA256) and AES-256-GCM for the HPKE envelopes;
  the agent CA's and agents' certificates are made with `rcgen` on aws-lc-rs; enrollment tokens
  are hashed with SHA-256. From 0.4.1 the password key and the agent CA's key can be sealed at
  rest with PBKDF2-HMAC-SHA256 and AES-256-GCM, from aws-lc-rs too. See
  [HPKE in FIPS mode](#hpke-in-fips-mode).
- **S3 backups** are signed with SigV4 (SHA-256 and HMAC-SHA256 from aws-lc-rs) by syncd's own
  client, over rustls on the process provider.
- **ID token signatures** (single sign-on) are checked by `jsonwebtoken` on its aws-lc-rs
  backend. Only asymmetric algorithms are accepted (RSA, RSA-PSS, ECDSA P-256 and P-384,
  EdDSA).
- **From 0.5.0, signatures of the console's own making and checking** go through aws-lc-rs
  too: SAML response signatures (RSA and ECDSA with SHA-256 or better; SHA-1 refused; the XML
  canonicalization around them is the console's own code), the certificate path of a
  certificate sign-in (rustls-webpki with the process provider's algorithms, no SHA-1) and its
  OCSP and CRL signatures, the configuration signing key (ECDSA P-256), the **license file's
  signature** (ECDSA P-256 with SHA-256, verified offline, so the license check works in FIPS
  mode), and the Entra ID and SCIM client assertions (RSA or P-256). OCSP requests name the
  certificate by SHA-1 hashes of its issuer's name and key, as RFC 6960 has it: an identifier, not
  a signature.

The **FIPS build** turns on the `fips` feature (`aws-lc-fips-sys` 0.14.3, which needs CMake and
Go to build). The FIPS module is then linked into the whole process. ADR-0006 in the source
repository records the decision.

| Connection or operation | Notes |
|---|---|
| Browsers to the console, the console's own HTTPS | With `CONSOLE_TLS_CERT` and `CONSOLE_TLS_KEY`, the console terminates TLS itself, with rustls on the process provider (TLS 1.2 and 1.3): in FIPS mode, the FIPS module and its approved cipher suites. The certificate's key is generated outside syncd (by your CA, certbot or cert-manager) |
| Browsers to the console, through a proxy | TLS ends at your proxy. With the shipped nginx configuration, that is nginx with the host's OpenSSL (TLS 1.2 and 1.3), not syncd's module: validate the proxy, or the host's OpenSSL in FIPS mode. On Kubernetes, it is your ingress controller's TLS |
| Console to engine (control API) | mTLS through rustls on the process provider, on loopback in the packaged layout |
| Console to its PostgreSQL | `sqlx` with rustls on aws-lc-rs, when `CONSOLE_DATABASE_URL` asks for TLS. The packaged `--local-database` setup uses the local Unix socket, with no TLS |
| Console to the OpenID Connect provider, a SAML metadata URL | rustls on the process provider |
| Console to OCSP responders and CRL distribution points (certificate sign-in) | Plain HTTP by design; the answers' signatures are checked with aws-lc-rs |
| Browsers' client certificates (certificate sign-in, direct mode) | The console's own HTTPS asks for them; the path is validated at sign-in with rustls-webpki on the process provider. In proxy mode the proxy's TLS takes the certificate: validate the proxy |
| Engine and console to Vault, AWS Secrets Manager and KMS, Azure Key Vault (from 0.5.0) | rustls on the process provider; SigV4 and the Azure client assertion from aws-lc-rs |
| Console to webhook receivers, the pre-publish hook, Splunk HEC, a syslog server over TLS, the mail server (STARTTLS) | rustls on the process provider |
| Console to its Git repository | **Not syncd's module**: the `git` and `ssh` programs of the host, or of the console image (Debian's), with their own TLS and SSH cryptography |
| Engine to sources and targets | rustls with aws-lc-rs for PostgreSQL, MySQL and MariaDB, SQL Server, MongoDB, LDAP and Active Directory, SCIM 2.0 and Entra ID (Microsoft Graph), and an `https://` JDBC bridge, when TLS is configured for them |
| Engine to S3, and to the HA lease's PostgreSQL | rustls on the process provider |
| Capture agents to the engine's ingest listener, and HA snapshot pulls | Mutual TLS through rustls on the process provider. The `syncd-capture` agent in the FIPS bundle takes `--fips` (or `SYNCD_FIPS=true`: not `SYNCD_FIPS_MODE`, which the engine and the console read) |
| The capture hooks (OpenLDAP overlay, 389 DS plugin, Windows filter) | No cryptography: they hand the clear password to the agent on the same host, over a Unix socket (a named pipe on Windows) |
| Control API certificates | Issued by `syncdctl` at install time and by `syncdctl renew-certs` with the host's `openssl` command (ECDSA P-256 keys); on Kubernetes, by the Helm chart with Helm's own Go cryptography (ECDSA P-256 keys since chart 0.4.0). Either way, key generation happens outside syncd's module |
| The agent CA, agents' and the ingest listener's certificates | Generated by the engine with `rcgen` on aws-lc-rs (P-256): in FIPS mode, the FIPS module. An agent's key is generated by the agent (`syncd password agent enroll`, the same code) |
| Local account passwords | Argon2id normally; PBKDF2-HMAC-SHA512 (210 000 iterations) in FIPS mode |
| SHA-256 | Session and API tokens and enrollment tokens (only their hash is stored), the audit chain, PKCE, search digests, the export file and backup checksums |
| HMAC-SHA256 | Webhook and pre-publish hook signatures (`X-Syncd-Signature`), SigV4 for S3, AWS Secrets Manager and KMS |
| AES-256-GCM | Stored secrets (engine client keys, webhook URLs and secrets, secret environment values, the configuration signing key), under the master key; the HPKE envelopes of password changes; the sealed password keys (from 0.4.1) |
| ECDSA P-256 with SHA-256 | From 0.5.0: the console's configuration signatures and the engine's check of them; the license's signature |
| PBKDF2-HMAC-SHA256 | From 0.4.1, the key that seals the password key and the agent CA's key at rest, derived from `SYNCD_PASSWORD_KEY_PASSPHRASE_FILE`'s passphrase (600,000 iterations, a random 16-byte salt) |
| ECDH P-256, HKDF-SHA256 | The HPKE key encapsulation and key schedule of password changes |

Non-security hashing is unaffected by FIPS mode: the engine detects record changes with xxh3.

### Turning FIPS mode on

FIPS mode needs **both** a FIPS build **and** the runtime switch. The host's own FIPS mode
(`fips-mode-setup --enable`) does not switch syncd on: syncd does not read the kernel's FIPS
flag.

1. **Get a FIPS build.** From 0.4.0 a release carries an x86_64 FIPS bundle
   (`syncd-<version>-linux-x86_64-fips.tar.gz`), and from 0.5.0 an aarch64 one too
   (`syncd-<version>-linux-aarch64-fips.tar.gz`); the images have `<version>-fips` variants, from
   0.5.0 for `linux/amd64` and `linux/arm64` (the arm64 ones only while the repository's arm64
   runners are on). No release has been tagged and published yet, and the arm64 and aarch64 FIPS
   jobs have never run in CI, so for now build it yourself:

    ```sh
    scripts/package.sh --fips                      # bundle, .deb and .rpm, with -fips in the name
    deploy/packaging/build.sh --fips --packages    # the same, called directly
    docker build -f deploy/container/Dockerfile --target engine \
      --build-arg FEATURES=syncd/fips,syncd-console/fips -t syncd:0.5.0-fips .   # the FIPS image
    ```

    The bundle carries a `FIPS` marker file and, from 0.5.0, a FIPS build of the capture agent
    (`bin/syncd-capture`). There is no FIPS `.deb` or `.rpm` on a release (they would have the
    standard package's name); the `syncd-capture` packages are standard builds too.

2. **Install** with `--fips`:

    ```sh
    sudo ./install.sh --local-database --fips     # or: sudo syncdctl install ... --fips
    ```

    The installer refuses `--fips` on a build without the marker. It writes
    `SYNCD_FIPS_MODE=true` to both `/etc/syncd/engine.env` and `/etc/syncd/console.env`. Upgrades
    keep it.

3. **Or set it by hand** on an existing FIPS build: `SYNCD_FIPS_MODE=true` in both files (or
   `--fips` on `syncd` and `syncd-console`), then restart both services. The `syncd` command
   line reads `engine.env` too, so its commands run in the same mode.
4. **The capture agent** on a directory server, from the FIPS bundle, takes `--fips` or
   `SYNCD_FIPS=true` (its own variable, not `SYNCD_FIPS_MODE`).
5. **On Kubernetes**, use the `-fips` image tags for both components and set
   `SYNCD_FIPS_MODE=true` in `engine.env` and `console.env` in the chart's values. The standard
   images refuse the variable at startup.

At startup each program installs its provider:

- On a standard build, FIPS mode is a startup error ("FIPS mode requested, but this is not a
  FIPS build"). There is no silent fallback.
- On a FIPS build, the provider must report that it runs in FIPS mode, or startup fails ("the
  provider does not report FIPS operation").

`syncd status` shows `fips on` or `fips off`, as does the console's engine status
(`fips_mode`), and `GET /api/v1/system` says whether the console runs in FIPS mode.

!!! warning "The configuration setting in ADR-0006 does not exist"
    ADR-0006 and the plan describe a configuration setting `security.fips_mode = true`. The
    code has no such setting: FIPS mode is the `--fips` flag or `SYNCD_FIPS_MODE=true`, as
    above.

### What changes in FIPS mode

| Area | Standard | FIPS mode |
|---|---|---|
| TLS provider | aws-lc-rs default provider | The FIPS provider; a test checks that every cipher suite it enables is FIPS-approved (so no ChaCha20-Poly1305) |
| New local passwords | Argon2id | PBKDF2-HMAC-SHA512, 210 000 iterations |
| Existing Argon2id hashes | Accepted | **Refused**: those accounts cannot sign in until their password is reset |
| PBKDF2 hashes | Accepted | Accepted |
| Single sign-on accounts | | Unaffected: they have no local password |
| Password synchronisation, webhooks, API tokens, backups, SAML and certificate sign-in, signed configurations, the license check | aws-lc-rs | The same algorithms, from the FIPS module |
| Custom functions in WebAssembly (from 0.5.0) | Allowed | **Refused** (a configuration whose packages carry a module does not validate, and the console refuses the upload), unless `SYNCD_FIPS_ALLOW_WASM_FUNCTIONS=true` (or `--fips-allow-wasm-functions`) on both the engine and the console. A module's own code runs outside the validated module and could carry cryptography of its own; allow them only for modules that do no security-relevant cryptography. Modules get no host functions either way |

**Switching an existing installation to FIPS mode** therefore needs local passwords reset. Before
switching, make sure an admin will be able to get in:

```sh
sudo runuser -u syncd-console -- env -C / \
  $(sudo grep -E '^[A-Za-z_][A-Za-z0-9_]*=' /etc/syncd/console.env) \
  CONSOLE_DATA_DIR=/var/lib/syncd/console \
  /opt/syncd/current/bin/syncd-console --fips admin reset-password --user admin
```

The one-time password it prints is hashed with PBKDF2, and must be changed at the first sign-in.
That admin can then reset the other local accounts (**Users** → Reset password).

### What is not validated or tested

!!! warning "Not tested in FIPS mode"
    - **syncd itself.** The CI `fips` job builds the FIPS module and runs the crypto crate's
      tests (`cargo test -p syncd-crypto --features fips`): the provider reports FIPS
      operation and enables only approved cipher suites. The release workflow checks that the
      FIPS bundle's `syncd` accepts `SYNCD_FIPS_MODE=true`, and the image workflow that the FIPS
      images accept it and the standard ones refuse it (from 0.5.0 also the FIPS bundle's
      capture agent, and the aarch64 bundle and arm64 binaries). No end-to-end suite runs the
      engine or the console as a FIPS build: no connector, password synchronisation, webhook,
      backup, HA pair, SAML or certificate sign-in, license check or WebAssembly function is
      tested in FIPS mode.
    - **The aarch64 FIPS bundle and the arm64 FIPS images** are new in 0.5.0, and their CI jobs
      have not run yet.
    - **Argon2id** comes from the `argon2` crate, not from AWS-LC. It is not used in FIPS mode.
    - **The XML canonicalization of SAML, and the DER parsing of OCSP responses and CRLs**, are
      the console's own code around the module's primitives, outside the module's validation.

Outside syncd, and outside what it can vouch for:

- **nginx** or an ingress controller, when one terminates browsers' TLS;
- the certificate tooling that generates the console's own HTTPS key, and **Helm**, which
  generates the control API's keys on Kubernetes;
- the **distroless base image** (Debian 12's glibc and C++ runtime), which takes no part in
  syncd's cryptography;
- **PostgreSQL**, the console's database server;
- the **JDBC bridge** (Java) and a **Debezium Server** sidecar (Java), when used: their
  cryptography is the Java runtime's;
- the **host's `openssl`**, which generates the control API's keys at install time;
- **`git` and `ssh`**, which Git sync runs for the console;
- the **directory servers** and their capture hooks (C code that does no cryptography), and the
  **Windows password filter**, which is compile-checked only;
- **Vault, AWS and Azure**, at the far end of the secret and KMS clients;
- a **TLS proxy** that takes browsers' client certificates for certificate sign-in in proxy
  mode;
- **S3's server-side encryption**, and the mail, Splunk, syslog and webhook receivers at the far
  end of the console's connections.

### HPKE in FIPS mode

Password changes travel and wait sealed with HPKE (RFC 9180, base mode): DHKEM(P-256,
HKDF-SHA256), HKDF-SHA256, AES-256-GCM. syncd implements the HPKE construction itself
(`engine/crates/password`) from aws-lc-rs primitives, so in a FIPS build every primitive (P-256
key generation and ECDH, HMAC-SHA256 under HKDF, AES-256-GCM, random numbers) comes from the FIPS
module. The construction around them is syncd's own code, checked against the RFC 9180 test
vector; it is not part of the module's validation, and it has not been tested in FIPS mode. The
same holds for the self-service requests the console seals, and for what the `syncd-capture`
agent seals on a directory server.

From 0.4.1 the engine's password key and the agent CA's key can be sealed at rest with a
passphrase (`SYNCD_PASSWORD_KEY_PASSPHRASE_FILE`): PBKDF2-HMAC-SHA256 with 600,000 iterations
derives an AES-256-GCM key, both FIPS-approved algorithms and both from aws-lc-rs, so from the
FIPS module in a FIPS build. The sealed-file format around them is syncd's own code, and like the
rest it has not been tested in FIPS mode. Without a passphrase the keys are stored in the clear,
protected by their file mode (0600) only, and backups carry them as they are; choose a long
random passphrase, since its strength bounds the keys'. From 0.5.0 the passphrase can be
changed (`syncd password rekey`), wrapped by a KMS key (`SYNCD_PASSWORD_KEY_PASSPHRASE_KMS`),
and the password key and the agent CA rotated (`syncd password rotate-key`,
`rotate-agent-ca`). See [Security](security.md#password-synchronisation) and
[Password synchronisation](password-sync.md).

### Options that are not FIPS-appropriate

| Setting | Default | Why | Instead |
|---|---|---|---|
| `tls: disable` on a PostgreSQL, MySQL, SQL Server or MongoDB connector | **`disable`** | Credentials and data in plain text | `tls: verify_full`, with `ca_file` for a private CA |
| `tls: require` on those connectors | | Encrypted, but the server is not checked | `tls: verify_full` |
| `ldap://` without `starttls: true` | | Bind passwords and directory data in plain text | `ldaps://`, or `starttls: true` (with `connection.ca_file` for a private CA, from 0.5.0) |
| `passwords: { allow_cleartext: true }` (from 0.5.0) | off | Synchronised passwords sent to an LDAP directory on another host in plain text | TLS to every directory that takes passwords |
| The control API on loopback without `api.tls`, especially with `api.local_control` or `api.local_reveal` | Packaged: mTLS on loopback | Commands and sensitive values in plain text, to any local account | `api.tls` (as packaged), or `api.control_socket` |
| `api.access` in compatible mode (from 0.5.0) | New installs strict; upgraded installs compatible | Every certificate of the control API's CA may apply a configuration and set passwords | `sudo syncdctl api-access strict`, and signed configurations |
| `api.metrics_listen` beyond loopback | off | Plain HTTP | Loopback, a management network, or a TLS proxy |
| A JDBC bridge at `http://` | | Database credentials and rows in plain text (allowed on loopback only) | `https://` with `ca_file` |
| A Debezium sink listener | | Plain HTTP, with the token in the URL path | Keep it on loopback |
| `CONSOLE_DATABASE_URL` to another host without `sslmode=require` (or stricter) | | Console data, including password hashes and the audit log, in plain text | TLS to a PostgreSQL server you have configured for FIPS |
| The console on plain HTTP beyond loopback, with no certificate of its own and no TLS proxy | Packaged: `127.0.0.1` | Session cookies and passwords in plain text (browsers also drop the `Secure` cookie) | `CONSOLE_TLS_CERT` and `CONSOLE_TLS_KEY`, or a TLS proxy |
| `SYNC_DEV_MODE=true` | off | `admin`/`admin`, cookies without `Secure`, the password change skippable | Leave it off |
| `ENS_AUDIT_SPLUNK_INSECURE=true` | off | The audit trail sent to Splunk without checking its certificate | A certificate the public roots trust, or a TLS proxy |
| `ENS_AUDIT_SYSLOG_PROTOCOL` `udp` or `tcp` | `tcp` | The audit trail in plain text | `tls` |
| `CONSOLE_SMTP_STARTTLS=false` | on | Report mail and the mail account's password in plain text | STARTTLS (the default), or `CONSOLE_SMTP_IMPLICIT_TLS=true` |
| `ENS_X509_REVOCATION_FAIL_OPEN=true`, or `ENS_X509_REVOCATION=none` | off; `ocsp,crl` | Certificates whose revocation is unknown, or not checked, are accepted | Leave both at their defaults; mirror the CRLs (`ENS_X509_CRL_URL`) where responders cannot be reached |
| `SYNCD_FIPS_ALLOW_WASM_FUNCTIONS=true` (from 0.5.0) | off | WebAssembly modules run code outside the validated module | Leave it off, or allow only reviewed modules that do no security-relevant cryptography |
| Git sync (`CONSOLE_CONFIG_GIT_URL`) | off | TLS or SSH by the host's `git` and `ssh`, outside syncd's module | Configure the host's OpenSSL and OpenSSH for FIPS, or publish without Git sync |

### The license check in FIPS mode

From 0.5.0 syncd checks a signed license file (see [Licensing](licensing.md)). The check is
offline: the license's signature is ECDSA over P-256 with SHA-256, verified against public keys
compiled into the release, through aws-lc-rs, so in a FIPS build it is the FIPS module that
verifies it and the check works in FIPS mode the same way as outside it. Nothing about licensing
changes in FIPS mode. The check has not been run in FIPS mode end to end.

## DISA STIG settings

From 0.5.0 the console has the ENS platform baseline's notice-and-consent banner, session limits,
configurable lockout and password policy, HSTS, and certificate (CAC/PIV) sign-in, under the
family's `ENS_*` names (see [Security](security.md#sign-in-baseline)). Unset, each keeps 0.4.1's
behaviour, so an upgrade changes nothing until they are set. This table lists what to decide for
a STIG deployment. The console settings are environment variables in `/etc/syncd/console.env`;
see [Configuration](configuration.md).

| Control | Setting | Default | STIG deployment |
|---|---|---|---|
| Notice and consent banner (AC-8) | `ENS_CONSENT_BANNER_ENABLED`, `ENS_CONSENT_BANNER_TEXT` or `_TEXT_FILE` | off; the standard DoD notice when on | `true`. Enforced by the server for every sign-in method, and audited (`auth.consent`, then the session it led to) |
| Classification marking | **Users** → Banners (top and bottom, shown on every page and the sign-in page) | off | Your system's marking, for example `UNCLASSIFIED//FOUO`. Colours below 4.5:1 contrast are refused. The top banner's text is also printed on every page of a PDF report |
| Idle timeout (AC-11, AC-12) | `CONSOLE_SESSION_IDLE_MINUTES`; `ENS_SESSION_ADMIN_IDLE_TIMEOUT` for console administrators | `30`; unset | `15`, and `10` for administrators, or your policy's values |
| Absolute session lifetime | `CONSOLE_SESSION_MAX_HOURS` | `12` | Your policy's value |
| Concurrent sessions per user (AC-10) | `ENS_SESSION_MAX_PER_USER` | `0` (no limit) | `3`, or your policy's value; a sign-in beyond it ends the oldest session |
| Failed password sign-ins (AC-7) | `ENS_LOCKOUT_ATTEMPTS`, `ENS_LOCKOUT_WINDOW`, `ENS_LOCKOUT_DURATION` | `5`, `0`, `15` (minutes); 20 attempts per address in 5 minutes, not a setting | `3`, `15`, `0` (locked until an administrator unlocks the account) |
| Password complexity and lifetime (IA-5(1)) | `ENS_PASSWORD_MIN_LENGTH`, `_MIN_UPPERCASE`, `_MIN_LOWERCASE`, `_MIN_DIGITS`, `_MIN_SPECIAL`, `ENS_PASSWORD_HISTORY`, `ENS_PASSWORD_MAX_AGE_DAYS`, `ENS_PASSWORD_REJECT_USERNAME` | 12 characters minimum; no complexity, history or expiry | `15`, `1` of each class, history `5`, `60` days, `true`; better, single sign-on or certificates, with local sign-in limited to break-glass accounts. A minimum password lifetime and a minimum of changed characters are not enforced |
| CAC/PIV sign-in (IA-2(12), IA-5(2)) | `ENS_X509_ENABLED`, `ENS_X509_CA_FILE`, `ENS_X509_REVOCATION`, `ENS_X509_REVOCATION_FAIL_OPEN` | off | On, with the issuing CAs you accept, revocation `ocsp,crl`, and fail-open left `false`. Tested with test PKIs only: try it with a real card and your PKI first ([Single sign-on](single-sign-on.md#certificate-sign-in-cacpiv)) |
| Organisational sign-in and MFA (IA-2, IA-8) | `CONSOLE_OIDC_*`, `CONSOLE_SAML_*` | off | Through your OpenID Connect or SAML provider, with its MFA; SAML is tested against Keycloak only ([Single sign-on](single-sign-on.md)) |
| Local sign-in | **Users** → Local sign-in | everyone | Break-glass accounts only, once single sign-on or certificate sign-in works |
| Least privilege | role assignments scoped to environments or engines, `CONSOLE_REVEAL_ROLES` | roles on everything; `admin` may reveal | Assign the narrowest role at the narrowest scope; `CONSOLE_REVEAL_ROLES=none` if nobody needs sensitive values |
| Separation of duties | **Users** → Configuration approval; `api.access` strict and `engine.require_signed_config` on the engine | off; strict on new installs, compatible on upgraded ones | Approval required where more than one designer works; strict access and signed configurations, so that only what the console's gates passed reaches the engine |
| Secure session cookie | `SYNC_DEV_MODE` | off (cookie `Secure`) | Keep development mode off |
| Transport encryption to browsers (SC-8) | The console's own HTTPS (`CONSOLE_TLS_CERT`, `CONSOLE_TLS_KEY`), or nginx `ssl_protocols`; `ENS_HSTS_MAX_AGE`, `ENS_HSTS_INCLUDE_SUBDOMAINS` | Plain HTTP on loopback; TLS 1.2 and 1.3 with either; HSTS for a year on the console's own HTTPS | Either. The console's protocols are not configurable: where a narrower list is required, use nginx (or an ingress). The shipped nginx file sends HSTS |
| Audit trail format (AU-2, AU-12) | the console's audit trail | `ens-audit/1`, hash-chained, in PostgreSQL | The family's format: categories, severities (lockouts, a broken chain and failing delivery are `alert`), actors with their role assignments, client address and user agent; sign-ins, refusals, lockouts, consent and ended sessions are recorded. Verify it with **Verify chain** or `tools/audit/verify_chain.py` over an export |
| Audit off-loading to a SIEM | `ENS_AUDIT_STDOUT`, `ENS_AUDIT_FILE`, `ENS_AUDIT_SPLUNK_*`, `ENS_AUDIT_SYSLOG_*`; the pull API | stdout (the journal) on; the others off | At least one off-host output: Splunk HEC, syslog over `tls`, a collected audit file, or the pull API (`GET /api/audit/events`) with an API token scoped `audit:read`. Delivery is at least once and in order; events queue while a receiver is down, and 15 minutes of failures raise an `audit.delivery-failed` alert in the trail. See [Audit logging](../audit-logging.md) |
| Audit retention | `ENS_AUDIT_RETENTION_DAYS` | `0` (keep forever) | Your policy's period. Only events every enabled output has delivered are pruned, and each prune is recorded (`audit.pruned`) |
| Audit integrity | the hash chain, the hourly `audit log head` lines | on | Keep the head lines (or an output) outside the database, and verify the chain against a recorded head regularly |
| Non-person accounts | API tokens | none until created | One token per system, with the narrowest scopes, limited to an environment or engines where it can be, and an expiry (`CONSOLE_API_MAX_TOKEN_LIFETIME`, `365d` at most by default); each stops when its creator is disabled. Every state-changing token request is audited |
| Client addresses in the audit log | `CONSOLE_TRUSTED_PROXIES` | none | Your proxy's address |
| FIPS 140-3 | `SYNCD_FIPS_MODE` with a FIPS build | off | See [Turning FIPS mode on](#turning-fips-mode-on) |

The values above are those the console's own documentation proposes for a review against the
DISA Application Security and Development STIG, mapped to NIST SP 800-53 control families, not
to STIG rule ids: check them against the STIG release you are assessed with. Most of the `ENS_*`
names were chosen in syncd 0.5.0 and have not yet been compared with the other ENS products'.

Remaining gaps, as of 0.5.0: no minimum password lifetime or minimum of changed characters, no
warning before a session ends, SAML tested against Keycloak only (no encrypted assertions,
signed requests, single logout or metadata signatures), certificate sign-in tested with test
PKIs only (no delta or indirect CRLs; the delegated OCSP responder's own revocation is not
checked), the per-address sign-in limit fixed, and no STIG rule mapping.

### Host settings for the systemd deployment

Already in place on a packaged install, without settings:

- both services run as unprivileged system accounts, with the systemd sandbox described in
  [Security](security.md#systemd-hardening): read-only file system apart from their data, no
  capabilities, no new privileges, a system call filter, private `/tmp` and devices;
- `/etc/syncd/*.env` readable by root and the service's group only; private keys in
  `/etc/syncd/pki` readable by root and the service that uses them; the CA key by root only;
- backups under `/var/lib/syncd/backups` readable by root only;
- the console on `127.0.0.1:8080` and the control API on `127.0.0.1:7443` with mTLS;
- `/run/syncd` created by systemd for the engine's control socket (mode 0750), when you turn
  the socket on, and removed when the engine stops;
- logs to the journal (`journalctl -u syncd -u syncd-console`).

For a STIG host, also:

- keep the host's own STIG settings (SELinux enforcing on RHEL, with
  `httpd_can_network_connect` for nginx as [Installation](install.md) describes; the
  firewall opening HTTPS only);
- forward the journal to your log collection, since the engine's control requests and reveals
  are logged there;
- run PostgreSQL configured to your database STIG, with TLS if it is on another host;
- keep the `syncd` group (which can read the command line's client key, and so operate the
  engine, or control it fully in compatible mode) to administrators;
- protect the `syncdctl` backups, which hold the console's master key (off-host too, from 0.4.1)
  and the secrets in `/etc/syncd`, and keep a copy of the master key apart from them; an engine
  backup or off-host backup also holds the entity data and the password keys (in the clear unless
  they are sealed), so encrypt its destination (S3 server-side encryption, an encrypted share);
- with password synchronisation, seal the keys with a passphrase kept outside `/etc/syncd`
  (from 0.5.0 an off-host backup leaves a passphrase file found there out, with a warning, and a
  restore then needs it from where you keep it), keep the engine's data directory to the
  engine's user, open the ingest listener only to the directory servers' hosts, bind every
  capture agent to its source, and rotate the keys now and then (`SyncdPasswordKeyOld` fires
  after a year);
- switch an upgraded engine to strict control API access (`sudo syncdctl api-access strict`), and
  require signed configurations where only the console may change what the engine does;
- renew the control API's certificates before they expire (`syncdctl status` shows when;
  `syncdctl renew-certs`), and choose `--new-ca` when your policy limits the CA's lifetime.

### Containers and the Helm chart

The chart's defaults already meet the usual Kubernetes STIG and benchmark checks for workloads:

- non-root (uid and gid 65532, `runAsNonRoot`), a read-only root file system, no privilege
  escalation, all capabilities dropped, seccomp `RuntimeDefault`, and no service account token
  mounted;
- a distroless base, with no shell or package manager;
- resource requests and memory limits on both containers;
- secrets in Kubernetes Secrets, not in the values or the images.

Decide, for a STIG cluster:

- **Images:** a tagged release's images and chart are signed with cosign, carry SPDX SBOMs as
  signed attestations, and were scanned with Trivy in CI (see
  [Security](security.md#supply-chain)); none is published until a release is tagged. Verify the
  signatures, mirror the images into a registry you trust, scan them yourself, and pin them by
  digest (`image.engine.repository`, `image.engine.tag`, and the same for `console`).
- **FIPS:** the `<version>-fips` images, amd64 and, from 0.5.0, arm64 (above).
- **TLS to browsers:** at your ingress controller (`console.ingress.tls`), configured to your
  policy, with HSTS if required. Set `console.tls.existingSecret` so the console serves HTTPS on
  the hop from the controller too, and `console.trustedProxies` to the controller's addresses.
- **Network:** `networkPolicy.enabled` makes NetworkPolicies that let only the console (and the
  registration Job, and the other engine of an HA pair) reach the engine's port 7443, and only the
  monitoring namespace you select its port 9464 (and, from chart 0.4.1, the sources in
  `networkPolicy.passwordIngestFrom` the password ingest port, with `engine.passwordIngest`). Turn on `networkPolicy.egress.enabled` to close
  egress too, and list what the pods may reach in `networkPolicy.egress.extra`.
- **Secrets:** the `syncd-pki` Secret (for a release named `syncd`) holds the control API's
  CA key, the `syncd-console` Secret the master key, and with password synchronisation your
  `engine.passwordKeyPassphrase.existingSecret` the passphrase that seals the password keys. Restrict who can read Secrets in the namespace, use
  encryption at rest for Secrets, and back up the console Secret.
- **Logs:** collect the pods' logs: the engine's control requests and reveals, and the console's
  audit trail, which it writes to its log as one JSON line per event (filter on
  `"schema":"ens-audit/1"`), or send the trail to Splunk or syslog with the `ENS_AUDIT_*`
  variables.
- **Banners, sessions and sign-in:** as above, in the console's environment (the `console.env`
  list in the chart's values, for example `CONSOLE_SESSION_IDLE_MINUTES`, `ENS_CONSENT_BANNER_ENABLED`
  or `ENS_X509_ENABLED`); the chart has no values of its own for them. Files they name (the
  notice's text, the allowed issuers, a SAML provider's metadata) come in through
  `console.extraVolumes`.
- **Control API access:** the image's initial configuration is strict; a release first installed
  before 0.5.0 keeps its configuration, and the chart cannot set `api.access`: edit the engine's
  file on its volume. `engine.configSigning` mounts the console's signing key for signed
  configurations.
