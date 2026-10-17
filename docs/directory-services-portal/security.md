# Security

This page summarises the portal's security controls and the settings that affect them.

## Trust model

- The API performs **every directory operation as one service account** (`LDAP_BIND_DN`). The
  portal enforces its own permissions on top of it. Give the service account read access to the
  schema and the exposed namespaces, and write access only where portal users should be able to
  edit.
- User passwords are used only to verify identity through an LDAP bind. They are not stored.
- Because the LDAP server sees only the service account, the portal keeps its own
  [audit log](admin-guide.md#audit-log) of who changed what.

## Authentication

| Control | Implementation |
|---|---|
| Password sign-in | Service-account search with `LDAP_USER_FILTER` (exactly one match), then an LDAP bind as the user |
| Anonymous bind prevention | Empty passwords are rejected before any bind |
| Username validation | Letters, digits and `. _ @ + -`, at most 128 characters; values are filter-escaped |
| Brute-force throttling | `LOGIN_MAX_FAILURES` failures (default 8) per client address and username within `LOGIN_LOCKOUT_WINDOW` (default 15 minutes) → HTTP 429, audited as `auth.lockout` (severity alert). Relies on the real client address, so set `SERVER_FORWARD_HEADERS_STRATEGY=framework` behind a proxy. The counters are in memory, so a restart clears them |
| Notice and consent banner | `ENS_CONSENT_BANNER_ENABLED=true`: no sign-in of any kind succeeds until the browser session has acknowledged the DoD banner. Enforced by the API (403 `consent_required`), audited as `auth.consent-acknowledged`. See [Configuration](configuration.md#notice-and-consent-banner) |
| Certificate-only mode | `PASSWORD_LOGIN_ENABLED=false` |
| Administrator role | `ROLE_ADMIN` from `ADMIN_USERS` / `ADMIN_GROUPS`; required for `/api/admin/**`. Read again from the directory every `GROUP_REFRESH_INTERVAL` (default 5 minutes) |

## Sessions and CSRF

- Spring Security `HttpSession`, cookie `DSP_SESSION`: `HttpOnly`, `SameSite=Strict`, and `Secure`
  over direct TLS or when `ENS_COOKIE_SECURE=true` (always under the `mtls` profile). The session ID
  is rotated at sign-in and the session is invalidated at sign-out.
- **Timeouts and limits** (DISA STIG): an idle timeout (`ENS_SESSION_IDLE_TIMEOUT`, default `30m`),
  a shorter one for administrators if you set `ENS_SESSION_ADMIN_IDLE_TIMEOUT`, an absolute
  lifetime (`ENS_SESSION_ABSOLUTE_TIMEOUT`, default `12h`) and an optional limit on concurrent
  sessions per user (`ENS_SESSION_MAX_PER_USER`; over it the oldest session ends). The web client's
  background polling is marked `X-DSP-Background: 1` and does not keep a session alive. Each expiry
  is audited as `auth.session-expired` with the reason `idle`, `absolute`, `concurrent` or
  `account-removed`.
- **Group refresh**: a signed-in user's groups and administrator status are read again every
  `GROUP_REFRESH_INTERVAL`. Removing someone from `ADMIN_GROUPS` takes effect within that
  interval. If the account was deleted, its sessions end; if the directory is down, the session
  keeps its groups.
- **CSRF defence**: every state-changing `/api/` request (anything except GET, HEAD, OPTIONS and TRACE)
  must carry `X-DSP-Request: 1`, or it is refused with 403 *Missing request verification header*.
  Browsers can't add custom headers to cross-site requests without a CORS preflight, which the API
  never approves. The `SameSite=Strict` cookie is a second layer. Spring Security's own CSRF token
  is turned off because this header check replaces it.
- **Security headers**: the ENS baseline set (Content-Security-Policy without inline scripts,
  HSTS on HTTPS, `nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy:
  strict-origin-when-cross-origin`, `Permissions-Policy`). nginx sends them and hides the API's
  copies on `/api`; the API sends the same set to anyone who reaches it directly. See
  [Configuration → Security headers](configuration.md#security-headers).
- Sessions are held in memory. Run a single API instance.

## Authorization

- Access levels per directory definition are **none**, **read** and **write**. See
  [Permissions](admin-guide.md#permissions).
- **Read-only (white pages)** definitions can never be written through, not even by administrators.
  Write grants on them are rejected.
- **Namespace confinement**: every read and write is limited to entries below the definition's base
  DN, within its scope, that match its filter. DNs are parsed and compared with RFC 4514 rules.
- Writes are limited to the form's writable fields. The server re-checks required fields,
  single-value rules, options (including directory-search options, which are looked up directly),
  formats and patterns (`FormValidator`, which mirrors the client rules in `shared/`).
- Self-service edits are limited to `selfEditable`, non-read-only fields on entries that the
  definition's self match resolves to the signed-in user.
- Metrics and analyses read through the same permission checks. Non-administrators may chart and
  filter only the attributes the directory shows them. Password attributes can never be charted or
  filtered on. Comparisons and quality checks are for administrators only.

## LDAP injection and input handling

- LDAP filters are built with the UnboundID filter API, which escapes every user-supplied value.
  Admin-configured filter templates (`{{username}}`, `{{dn}}`, `{{cn}}` ...) get their values escaped
  with `Filter.encodeValue`.
- DNs are parsed and built with RFC 4514 escaping. Special characters in naming values are escaped.
- Admin-supplied filters and DNs are validated (`@LdapFilter`, `@ValidDn`). Attribute names, slugs,
  page sizes and search lengths are bounded.
- User-supplied regular expressions (metrics transforms, analysis patterns) run with a time budget,
  so a pathological pattern fails only that chart or rule.
- **Password attributes** (`userPassword`, `unicodePwd` and the others) are never returned to the
  browser, and are recorded as `(redacted)` in the audit log.

## X.509 client certificates

Before a certificate is accepted, the API checks that:

1. it is within its validity period
2. it is allowed for TLS client authentication (extended key usage / key usage)
3. when `ENS_X509_ALLOWED_ISSUERS` is set, its issuer is one of those DNs
4. when `X509_TRUSTED_CA_FILE` is set, it chains to a trusted CA and is not revoked
   (`ENS_X509_REVOCATION`). In header mode the chain check is mandatory: a forwarded certificate is
   refused without `X509_TRUSTED_CA_FILE`

**Revocation checking** is configured per sign-in: the JDK's PKIX validator runs with its own
revocation checker, and no global JDK security property is changed. The API fetches OCSP responses
and CRLs itself with fixed limits: http or https only, 5 s to connect and 10 s to read, at most 3
redirects, 1 MB per OCSP response and 32 MB per CRL, and never to link-local, multicast or
cloud-metadata addresses. It checks the target address before connecting, so a host name that
changes its answer between that check and the connection (DNS rebinding) is not ruled out. On an
air-gapped network, use a CRL directory (`ENS_X509_CRL_DIR`) or a local OCSP responder
(`ENS_X509_OCSP_RESPONDER_URL`). With `ENS_X509_REVOCATION_FAIL=closed` (the default), a
certificate whose status can't be determined is refused. See
[Configuration → Revocation checking](configuration.md#revocation-checking).

The API then maps the certificate to **exactly one** directory user, with `X509_USER_FILTER` or the
subject DN. With `X509_REQUIRE_CERTIFICATE_MATCH=true`, the certificate must also be published in
the user's `userCertificate` attribute.

In header mode:

- the certificate header is honoured **only** when the TCP peer is in `X509_TRUSTED_PROXIES`
  **and** the request carries the proxy secret in `X-DSP-Proxy-Secret`, compared in constant time.
  An address alone is not enough: other pods or local processes can share it, and users'
  certificates are public, so whoever can send the header could sign in as anyone. The address
  check uses the actual connection, never `X-Forwarded-For`, so use
  `FORWARD_HEADERS_STRATEGY=framework` or `none`, not `native`
- the API does not start without the proxy secret (`X509_PROXY_SECRET_FILE` or
  `X509_PROXY_SECRET`, at least 32 characters) or `X509_TRUSTED_CA_FILE`
- the bundled nginx always overwrites `X-DSP-Proxy-Secret`, removes or overwrites certificate
  headers sent by clients, forwards only certificates it verified, and sends them only to
  `/api/auth/x509` and `/api/auth/me`. With `X509_EDGE_TERMINATION=true` it passes on the header
  that the TLS proxy in front of it sets
- with the Helm chart's edge termination, only the ingress controller may reach the web pods: the
  chart requires `networkPolicy.enabled` and `networkPolicy.webIngressFrom` (or
  `x509.allowUnrestrictedWebIngress`)
- a refused header is logged and audited as a failed `auth.login` with
  `details.reasonCode` `untrusted_certificate_header` and the connecting `details.peerAddress`

Certificate sign-ins and rejections are logged and audited with the certificate subject, serial and
issuer.

## Network exposure

- The compose files never publish the API. Only the web proxy can reach it, which is why
  `FORWARD_HEADERS_STRATEGY=framework` is safe there.
- The web proxy overwrites `X-Forwarded-For` with the real client address, so clients can't spoof
  their address.
- The native package binds the API to `127.0.0.1` and runs it as the unprivileged `dsp` user under a
  hardened systemd unit (`NoNewPrivileges`, `ProtectSystem=strict`, `ProtectHome`, `PrivateTmp`,
  and write access only to the data directory). Any local process can reach `127.0.0.1`, which is
  why forwarded certificates also need the proxy secret. Its file is readable only by root and
  `dsp`; nginx gets it through an include that only root can read.
- The container images run as unprivileged users (uid 185 / 1001, group 0) on unprivileged ports,
  and are compatible with the OpenShift `restricted` SCC.

- The native package's systemd unit also sets `ProtectKernelTunables`, `ProtectKernelModules`,
  `ProtectControlGroups`, `RestrictSUIDSGID`, `LockPersonality`, an empty capability set,
  `PrivateDevices`, `SystemCallFilter=@system-service` (a denied call fails with `EPERM`),
  `RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX`, `RestrictNamespaces`, `RestrictRealtime` and
  `UMask=0077`, so the files the API creates are private to it.
- The Docker package runs both containers with all capabilities dropped, `no-new-privileges`, a
  read-only root file system and a memory limit. The Helm chart does the same through the pod
  security contexts (`readOnlyRootFilesystem`, no privilege escalation, `RuntimeDefault` seccomp).
- With the Helm chart, the LDAP bind password and the X.509 proxy secret are mounted as files, not
  set as environment variables. The Splunk HEC token and the trust store password are passed as
  environment variables from Secrets.
- The management port (`MANAGEMENT_SERVER_PORT`) serves health and Prometheus without sign-in. It
  is never published by the compose files, the ingress or the route; the native package binds it to
  `127.0.0.1`.

## Supply chain

- Every GitHub Action in the build is pinned to a commit SHA and every base image to a digest.
  Dependabot proposes weekly updates.
- Every push and pull request runs CodeQL (Java and TypeScript), `npm audit --omit=dev
  --audit-level=high`, and a Trivy scan of both images that fails on fixable HIGH or CRITICAL
  findings.
- On a release tag, both images (by digest) and the Helm chart are signed with cosign (keyless,
  GitHub OIDC), and each image gets its SPDX SBOM as a signed attestation. Verify an image before
  you deploy it:

    ```bash
    cosign verify ghcr.io/winllc/directory-services-portal-api@<digest> \
      --certificate-identity-regexp 'https://github.com/winllc/directory-services-portal/.github/workflows/.*' \
      --certificate-oidc-issuer https://token.actions.githubusercontent.com
    ```

    Do the same for `ghcr.io/winllc/directory-services-portal-web` and
    `ghcr.io/winllc/charts/directory-services-portal`, and use `cosign verify-attestation --type
    spdxjson` for the SBOMs. The release download itself is verified with `SHA256SUMS.asc`; see
    [Releases](../releases.md).

## FIPS 140-3 and DISA STIG

See [FIPS 140-3 and DISA STIG](fips-and-stig.md) for what the portal relies on, the settings a STIG
deployment needs, and the options that are not FIPS-appropriate.

## Hardening checklist

- [ ] `DIRECTORY_MODE=ldap` with a dedicated, least-privilege service account
- [ ] The bind password in a file (`LDAP_BIND_PASSWORD_FILE`) rather than the environment, where the
      deployment allows it
- [ ] `ldaps://` or `LDAP_STARTTLS=true`, with `LDAP_TLS_REJECT_UNAUTHORIZED=true`
- [ ] HTTPS in front of the portal and `ENS_COOKIE_SECURE=true`
- [ ] `SERVER_FORWARD_HEADERS_STRATEGY=framework` only when the API is reachable solely through your
      proxy
- [ ] `ADMIN_GROUPS` set to a small, controlled group
- [ ] `SEED_DEMO_CONFIG=false` in production
- [ ] Session limits as your policy requires (DISA STIG suggests `ENS_SESSION_ADMIN_IDLE_TIMEOUT=15m`,
      `ENS_SESSION_MAX_PER_USER=3` and `LOGIN_MAX_FAILURES=3`)
- [ ] For DoD systems: `ENS_CONSENT_BANNER_ENABLED=true`
- [ ] For X.509: `ENS_X509_REVOCATION` other than `off`, and `ENS_X509_ALLOWED_ISSUERS` if only
      some trusted CAs issue user certificates
- [ ] For X.509 in header mode: the proxy secret kept private (`.env` mode `600`, or the secret
      file), `X509_TRUSTED_CA_FILE`, and tight `X509_TRUSTED_PROXIES`
- [ ] With Helm edge termination: `networkPolicy.webIngressFrom` limited to the ingress controller
- [ ] The management port kept off user networks
- [ ] Audit store protected and forwarded (`ENS_AUDIT_*` → SIEM), with `ENS_AUDIT_RETENTION_DAYS` set
      to your policy
- [ ] Images verified with `cosign verify`, and kept current to pick up UBI security updates
