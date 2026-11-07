# FIPS 140-3 and DISA STIG

This page says what syncd relies on for FIPS 140-3, how its optional FIPS mode is turned on and
what it changes, what has not been tested or validated, and which settings a DISA STIG
deployment needs. The suite-wide picture is in [FIPS 140-3 and DISA STIG](../fips-and-stig.md).

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
  connections to engines and to the OpenID Connect provider, and the connectors are built on it.
- **Hashing, encryption and randomness** in the console also use aws-lc-rs: SHA-256 for
  session tokens, the audit hash chain, PKCE challenges and explorer search digests;
  AES-256-GCM for envelope encryption under the master key; PBKDF2-HMAC-SHA512 for local
  passwords in FIPS mode; random bytes for tokens, salts, keys and generated passwords.
- **ID token signatures** (single sign-on) are checked by `jsonwebtoken` on its aws-lc-rs
  backend. Only asymmetric algorithms are accepted (RSA, RSA-PSS, ECDSA P-256 and P-384,
  EdDSA).

The **FIPS build** turns on the `fips` feature (`aws-lc-fips-sys` 0.14.3, which needs CMake and
Go to build). The FIPS module is then linked into the whole process. ADR-0006 in the source
repository records the decision.

| Connection or operation | Notes |
|---|---|
| Browsers to the console, the console's own HTTPS | With `CONSOLE_TLS_CERT` and `CONSOLE_TLS_KEY`, the console terminates TLS itself, with rustls on the process provider (TLS 1.2 and 1.3): in FIPS mode, the FIPS module and its approved cipher suites. The certificate's key is generated outside syncd (by your CA, certbot or cert-manager) |
| Browsers to the console, through a proxy | TLS ends at your proxy. With the shipped nginx configuration, that is nginx with the host's OpenSSL (TLS 1.2 and 1.3), not syncd's module: validate the proxy, or the host's OpenSSL in FIPS mode. On Kubernetes, it is your ingress controller's TLS |
| Console to engine (control API) | mTLS through rustls on the process provider, on loopback in the packaged layout |
| Console to its PostgreSQL | `sqlx` with rustls on aws-lc-rs, when `CONSOLE_DATABASE_URL` asks for TLS. The packaged `--local-database` setup uses the local Unix socket, with no TLS |
| Console to the OpenID Connect provider | rustls on the process provider |
| Engine to sources and targets | rustls with aws-lc-rs for PostgreSQL, MySQL and MariaDB, SQL Server, MongoDB, LDAP and Active Directory, and an `https://` JDBC bridge, when TLS is configured for them |
| Control API certificates | Issued by `syncdctl` at install time and by `syncdctl renew-certs` with the host's `openssl` command (ECDSA P-256 keys); on Kubernetes, by the Helm chart with Helm's own Go cryptography (RSA keys). Either way, key generation happens outside syncd's module |
| Local account passwords | Argon2id normally; PBKDF2-HMAC-SHA512 (210 000 iterations) in FIPS mode |
| SHA-256 | Session tokens (only their hash is stored), the audit chain, PKCE, search digests, the export file checksum |
| AES-256-GCM | Stored secrets (engine client keys), under the master key |

Non-security hashing is unaffected by FIPS mode: the engine detects record changes with xxh3.

### Turning FIPS mode on

FIPS mode needs **both** a FIPS build **and** the runtime switch. The host's own FIPS mode
(`fips-mode-setup --enable`) does not switch syncd on: syncd does not read the kernel's FIPS
flag.

1. **Build** a FIPS release yourself; the release workflow builds standard builds only (and no
   release has been tagged and published yet).

    ```sh
    scripts/package.sh --fips                      # bundle, .deb and .rpm, with -fips in the name
    deploy/packaging/build.sh --fips --packages    # the same, called directly
    ```

    The bundle carries a `FIPS` marker file.

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
      operation and enables only approved cipher suites. No end-to-end suite runs the engine or
      the console as a FIPS build, and no connector is tested against a source or target in
      FIPS mode.
    - **Published releases.** The release workflow builds standard builds only. A FIPS build
      is one you make with `--fips`.
    - **Container images.** There is no FIPS image. The Dockerfile builds without the `fips`
      feature, so setting `SYNCD_FIPS_MODE=true` (the `engine.env` or `console.env` list in
      the chart's values) on the standard images stops them at startup. A FIPS image means changing the
      Dockerfile's build to `--features fips` (and adding CMake and Go to its build stage); that
      has not been done or tested.
    - **Argon2id** comes from the `argon2` crate, not from AWS-LC. It is not used in FIPS mode.

Outside syncd, and outside what it can vouch for:

- **nginx** or an ingress controller, when one terminates browsers' TLS;
- the certificate tooling that generates the console's own HTTPS key, and **Helm**, which
  generates the control API's keys on Kubernetes;
- the **distroless base image** (Debian 12's glibc and C++ runtime), which takes no part in
  syncd's cryptography;
- **PostgreSQL**, the console's database server;
- the **JDBC bridge** (Java) and a **Debezium Server** sidecar (Java), when used: their
  cryptography is the Java runtime's;
- the **host's `openssl`**, which generates the control API's keys at install time.

Password synchronisation, whose payload encryption the plan describes, is not built yet.

### Options that are not FIPS-appropriate

| Setting | Default | Why | Instead |
|---|---|---|---|
| `tls: disable` on a PostgreSQL, MySQL, SQL Server or MongoDB connector | **`disable`** | Credentials and data in plain text | `tls: verify_full`, with `ca_file` for a private CA |
| `tls: require` on those connectors | | Encrypted, but the server is not checked | `tls: verify_full` |
| `ldap://` without `starttls: true` | | Bind passwords and directory data in plain text | `ldaps://`, or `starttls: true` |
| The control API on loopback without `api.tls`, especially with `api.local_control` or `api.local_reveal` | Packaged: mTLS on loopback | Commands and sensitive values in plain text, to any local account | `api.tls` (as packaged), or `api.control_socket` |
| `api.metrics_listen` beyond loopback | off | Plain HTTP | Loopback, a management network, or a TLS proxy |
| A JDBC bridge at `http://` | | Database credentials and rows in plain text (allowed on loopback only) | `https://` with `ca_file` |
| A Debezium sink listener | | Plain HTTP, with the token in the URL path | Keep it on loopback |
| `CONSOLE_DATABASE_URL` to another host without `sslmode=require` (or stricter) | | Console data, including password hashes and the audit log, in plain text | TLS to a PostgreSQL server you have configured for FIPS |
| The console on plain HTTP beyond loopback, with no certificate of its own and no TLS proxy | Packaged: `127.0.0.1` | Session cookies and passwords in plain text (browsers also drop the `Secure` cookie) | `CONSOLE_TLS_CERT` and `CONSOLE_TLS_KEY`, or a TLS proxy |
| `SYNC_DEV_MODE=true` | off | `admin`/`admin`, cookies without `Secure`, the password change skippable | Leave it off |

### The license check in FIPS mode

syncd has no license check yet: licensing is on the roadmap (Phase 5). Nothing about licensing
changes in FIPS mode. See [Licensing](../licensing.md).

## DISA STIG settings

syncd does not implement the ENS platform baseline's consent banner, session limits or
certificate sign-in that the Java products have. This table lists what it does have, and what
to decide for a STIG deployment. The console settings are environment variables in
`/etc/syncd/console.env`; see [Configuration](configuration.md).

| Control | Setting | Default | STIG deployment |
|---|---|---|---|
| Notice and consent banner | none | | **Not available**: there is no click-through banner before sign-in. If your system needs one, put it in front of the console (at the proxy or the identity provider) |
| Classification marking | **Users** → Banners (top and bottom, shown on every page and the sign-in page) | off | Your system's marking, for example `UNCLASSIFIED//FOUO`. Colours below 4.5:1 contrast are refused |
| Idle timeout | `CONSOLE_SESSION_IDLE_MINUTES` | `30` | Your policy's value; `15` is common. There is no separate administrator timeout |
| Absolute session lifetime | `CONSOLE_SESSION_MAX_HOURS` | `12` | Your policy's value |
| Concurrent sessions per user | none | no limit | **Not available** |
| Failed password sign-ins | fixed | 5 failures lock an account for 15 minutes; 20 attempts per address in 5 minutes | Not configurable. Prefer single sign-on, where the provider's lockout applies |
| Password complexity and lifetime | fixed | 12 characters minimum; no complexity, history or expiry | Use single sign-on, and limit local sign-in to break-glass accounts |
| Multi-factor and CAC/PIV sign-in | through the identity provider | | syncd has no certificate sign-in. Enforce MFA or CAC/PIV at an OpenID Connect provider ([Single sign-on](single-sign-on.md)) |
| Local sign-in | **Users** → Local sign-in | everyone | Break-glass accounts only, once single sign-on works |
| Least privilege | roles, `CONSOLE_REVEAL_ROLES` | `admin` may reveal | Assign the narrowest role; `CONSOLE_REVEAL_ROLES=none` if nobody needs sensitive values |
| Separation of duties | **Users** → Configuration approval | off | Required, where more than one designer works |
| Secure session cookie | `SYNC_DEV_MODE` | off (cookie `Secure`) | Keep development mode off |
| Transport encryption to browsers | The console's own HTTPS (`CONSOLE_TLS_CERT`, `CONSOLE_TLS_KEY`), or nginx `ssl_protocols` | Plain HTTP on loopback; TLS 1.2 and 1.3 with either | Either. The console sends no HSTS and its protocols are not configurable: where HSTS or a narrower protocol list is required, use nginx (or an ingress) and add it there |
| Audit trail | the console's audit log | on, in PostgreSQL | Back up the database, collect the hourly `audit log head` lines from the journal, verify the chain against the latest of them (`GET /api/v1/audit/verify?anchor_id=…&anchor_hash=…`) regularly, and export or collect the log by your own means: syncd does not forward to a SIEM yet ([Audit logging](../audit-logging.md)) |
| Client addresses in the audit log | `CONSOLE_TRUSTED_PROXIES` | none | Your proxy's address |
| FIPS 140-3 | `SYNCD_FIPS_MODE` with a FIPS build | off | See [Turning FIPS mode on](#turning-fips-mode-on) |

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
- keep the `syncd` group (which can read the command line's client key, and so control the
  engine) to administrators;
- protect and store apart the console's master key and the `syncdctl` backups, which hold it
  and the secrets in `/etc/syncd`;
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

- **Images:** published images are not signed and have no image SBOM (see
  [Security](security.md#supply-chain)), and none is published until a release is tagged.
  Build them, or mirror them, into a registry you trust, scan them, and pin them by digest
  (`image.engine.repository`, `image.engine.tag`, and the same for `console`).
- **FIPS:** there is no FIPS image (above).
- **TLS to browsers:** at your ingress controller (`console.ingress.tls`), configured to your
  policy, with HSTS if required. Inside the cluster the console serves plain HTTP; set
  `console.trustedProxies` to the controller's addresses.
- **Network:** the chart makes no NetworkPolicy. Allow only the console (and the registration
  Job) to reach the engine's port 7443, and only Prometheus its port 9464.
- **Secrets:** the `syncd-pki` Secret (for a release named `syncd`) holds the control API's
  CA key, and the `syncd-console` Secret the master key. Restrict who can read Secrets in the namespace, use
  encryption at rest for Secrets, and back up the console Secret.
- **Logs:** collect the pods' logs (the engine's control requests and reveals, and the console's
  `audit log head` lines).
- **Banners and sessions:** as above, in the console's environment (the `console.env` list in the
  chart's values, for example `CONSOLE_SESSION_IDLE_MINUTES`).
