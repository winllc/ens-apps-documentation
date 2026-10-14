# Security

How CertAlert authenticates people, what each role may do, and how to harden a deployment. All
settings referenced are in [Configuration → Security](configuration.md#security-cert-alertsecurity).

## Authentication

Two methods, in order of preference. No passwords are stored by CertAlert; there is no local
user database.

### Client certificate (X.509)

Requires TLS (`server.ssl.enabled: true`, or `CERT_ALERT_TLS_ENABLED=true`) with
`server.ssl.client-auth: want` and a trust store holding the CAs whose client certificates are
accepted.

1. The browser presents a certificate during the TLS handshake.
2. CertAlert computes the **SHA-256 fingerprint of its DER** and looks it up in
   `cached_certificate`.
3. If it belongs to an **IC Person**, that person is signed in. Certificates belonging to servers
   never authenticate.
4. If no cached certificate matches and `x509.require-known-certificate` is `true` (default), the
   certificate is rejected and the browser is sent to the password form.

This binds sign-in to the exact certificate the directory publishes for the person, not to a name.
Revoking access is a matter of removing the certificate from the directory (and the next sweep or
changelog update).

!!! warning "`client-auth: want`, never `need`"
    With `need`, a browser without a certificate fails the TLS handshake and never reaches the
    password form.

!!! note "First sync before certificate sign-in"
    Fingerprints are matched against the cache, so certificate sign-in works only after the first
    sweep has cached people's certificates.

With `x509.require-known-certificate: false`, an unknown certificate falls back to matching its
subject and `rfc822Name` SANs against directory identifiers. That trusts a name rather than a key
and is off by default.

### Directory password (LDAP bind)

For browsers presenting no accepted certificate, the `/login` form binds to the directory as the
person signing in, either by searching (`ldap.user-search-base` + `ldap.user-search-filter`,
default `(uid={0})`) or by binding to `ldap.user-dn-patterns` directly when anonymous search is not
permitted. Set `cert-alert.security.ldap.enabled: false` to remove the password path entirely.

### Sessions and API calls

- Sign-in creates a normal session; **Sign out** (account menu) logs out to `/login?logout`.
- Unauthenticated calls under `/api/**` get **401** (not a redirect); pages redirect to `/login`.
- CSRF protection is on (Spring Security default); the pages send the token from `_csrf` meta tags.
- There is no API token or HTTP Basic mechanism.

## Roles

| Role | Granted to | May |
|---|---|---|
| `ROLE_USER` | Everyone who authenticates | Read tables, details, metrics, notifications; contextual rights below |
| `ROLE_ADMIN` | People matching `cert-alert.security.admin-identifiers` | Everything |

`admin-identifiers` is matched against any identifier the directory holds for the person (any
address, `uid`, `cn`, `displayName`, `preferredName`). On an empty cache, roles for a password
sign-in are resolved from the directory entry just bound against, so an administrator can run the
first sync.

Two further, **per-server** rights derive from data rather than configuration:

- **Point of contact**: you are named in the server's `serverPOC` by any value that names you, or
  you were added as a contact in CertAlert.
- **Project administrator**: you run a project containing the server (granted by an administrator).

## Permissions matrix

| Action | Reader | Point of contact (that server) | Project admin (servers in project) | Admin |
|---|:-:|:-:|:-:|:-:|
| View People, Servers, details, Metrics, own Notifications | ✓ | ✓ | ✓ | ✓ |
| View an entry's audit history | ✓ | ✓ | ✓ | ✓ |
| View the system audit log (`/admin`) | | | | ✓ |
| Manage a server's contacts | `AUTHENTICATED`¹ | ✓ | ✓ | ✓ |
| Run the endpoint probe | `AUTHENTICATED`¹ | ✓ | ✓ | ✓ |
| Add/remove a person's addresses | `AUTHENTICATED`¹ | `AUTHENTICATED`¹ | `AUTHENTICATED`¹ | ✓ |
| Create/rename/delete projects; add/remove members | `AUTHENTICATED`¹ | `AUTHENTICATED`¹ | `AUTHENTICATED`¹ | ✓ |
| Grant/revoke project administrator | | | | ✓ |
| Read editable attribute values | ✓ | ✓ | ✓ | ✓ |
| Define editable attributes / set values | | | | ✓ |
| Trigger sync, refresh, prune, clean-up, changelog poll | | | | ✓ |
| Run revocation check | | | | ✓ |
| Run / dry-run the round-up | | | | ✓ |
| Change round-up lead days | | | | ✓ |
| `/actuator/health`, `/health/**`, `/info` | public | public | public | public |
| Other actuator endpoints (`metrics`, `loggers`) | | | | ✓ |

¹ Only when `cert-alert.security.contact-editors: AUTHENTICATED` (default `ADMIN`).

Refusals are **403** with a problem-detail body for API calls. The UI hides controls a user cannot
use (it asks the API whether the current user may edit a given server).

## Public paths

`/login`, `/css/**`, `/js/**`, `/img/**`, `/webjars/**`, `/favicon.ico`, `/error`,
`/actuator/health`, `/actuator/health/**`, `/actuator/info`. Everything else requires
authentication.

## Outbound connections

| Destination | Why | Control |
|---|---|---|
| LDAP directory | Sweeps, changelog, password bind, attribute writes, clean-up | `spring.ldap.*` |
| SMTP | Round-up and alert email | `cert-alert.notifications.email.enabled`, `cert-alert.alerts.email.enabled` |
| CRL distribution points / OCSP responders (HTTP or `ldap://`) | Revocation | `cert-alert.revocation.enabled` |
| Server TLS endpoints | Endpoint probe (user-initiated) | `cert-alert.probe.enabled` |

The endpoint probe connects only to the host named by the entry (`serverURL`, then
`icServerAddress`); a caller chooses only the port. The handshake deliberately accepts any chain
(an expired or self-signed certificate is what it reports) and sends no application data; the
result is judged against the directory. Disable it where reaching servers from CertAlert is not
acceptable.

## Directory permissions for the service account

| Need | Access |
|---|---|
| Sweeps | Read people and server entries, including the certificate attribute |
| Changelog connector | Read `cn=changelog` (usually a separate grant) |
| Editable attributes | Write exactly the attributes defined on the Admin page |
| Certificate clean-up | Write the certificate attribute (and nothing else) |

## Hardening checklist

- Use the `postgres` profile with credentials from the environment or a secret.
- Use `ldaps://` for the directory.
- Enable TLS; keep `client-auth: want`; prefer certificate sign-in. Consider
  `ldap.enabled: false` for certificate-only deployments.
- Keep `x509.require-known-certificate: true`.
- Keep `admin-identifiers` short; keep `contact-editors: ADMIN` unless servers' owners are expected
  to curate projects and addresses themselves.
- Never set `cert-alert.security.enabled: false` outside local development.
- Mount keystores read-only; the image's `.dockerignore` already prevents baking in `*.p12`,
  `*.jks`, `*.pem`, `*.key`.
- Set `revocation.allow-unverified-crl: false` once `issuer-directory` is populated.
- Leave `server.error.include-message: never`.
- Configure the classification banner where policy requires it.
- The container runs as non-root uid 185.
