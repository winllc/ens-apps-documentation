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
| Brute-force throttling | 8 failures per client address and username within 15 minutes → HTTP 429. Relies on the real client address, so set `FORWARD_HEADERS_STRATEGY` behind a proxy |
| Certificate-only mode | `PASSWORD_LOGIN_ENABLED=false` |
| Administrator role | `ROLE_ADMIN` from `ADMIN_USERS` / `ADMIN_GROUPS`; required for `/api/admin/**` |

## Sessions and CSRF

- Spring Security `HttpSession`, cookie `DSP_SESSION`: `HttpOnly`, `SameSite=Strict`, and `Secure` when
  `COOKIE_SECURE=true` (always under the `mtls` profile). The session ID is rotated at sign-in and
  the session is invalidated at sign-out.
- **CSRF defence**: every state-changing `/api/` request (anything except GET, HEAD, OPTIONS and TRACE)
  must carry `X-DSP-Request: 1`, or it is refused with 403 *Missing request verification header*.
  Browsers can't add custom headers to cross-site requests without a CORS preflight, which the API
  never approves. The `SameSite=Strict` cookie is a second layer.
- Response headers from the API: `X-Frame-Options: DENY` and `Referrer-Policy: same-origin`. nginx
  adds `X-Content-Type-Options: nosniff` (and HSTS in the mTLS configuration).
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
3. when `X509_TRUSTED_CA_FILE` is set, it chains to a trusted CA, and revocation is checked through
   OCSP or CRL distribution points when `X509_CHECK_REVOCATION=true`. In header mode this check is
   mandatory: a forwarded certificate is refused without `X509_TRUSTED_CA_FILE`

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

## Hardening checklist

- [ ] `DIRECTORY_MODE=ldap` with a dedicated, least-privilege service account
- [ ] `ldaps://` or `LDAP_STARTTLS=true`, with `LDAP_TLS_REJECT_UNAUTHORIZED=true`
- [ ] HTTPS in front of the portal and `COOKIE_SECURE=true`
- [ ] `FORWARD_HEADERS_STRATEGY=framework` only when the API is reachable solely through your proxy
- [ ] `ADMIN_GROUPS` set to a small, controlled group
- [ ] `SEED_DEMO_CONFIG=false` in production
- [ ] For X.509 in header mode: the proxy secret kept private (`.env` mode `600`, or the secret
      file), `X509_TRUSTED_CA_FILE`, and tight `X509_TRUSTED_PROXIES`
- [ ] With Helm edge termination: `networkPolicy.webIngressFrom` limited to the ingress controller
- [ ] Audit files protected, archived and forwarded (`audit` logger → SIEM)
- [ ] Images rebuilt regularly to pick up UBI security updates
