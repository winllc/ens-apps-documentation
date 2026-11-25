# Security

How CertAlert authenticates people, what each role may do, and how to harden a deployment. All
settings referenced are in [Configuration → Security](configuration.md#security-cert-alertsecurity).

## Authentication

Three methods for people: a client certificate, the directory password and, when configured,
single sign-on through an identity provider. Integrations use [API tokens](#api-tokens). No passwords
are stored by CertAlert; there is no local user database.

### Client certificate (X.509)

The certificate reaches CertAlert in one of two ways (`ENS_X509_SOURCE`):

- **`servlet`** (the default): CertAlert terminates TLS itself (`CERT_ALERT_TLS_ENABLED=true`) with
  `server.ssl.client-auth: want` and a trust store holding the CAs whose client certificates are
  accepted at the handshake.
- **`header`**: a proxy in front terminates TLS, verifies the client certificate and forwards it
  in a header. See [Behind a TLS-terminating proxy](#behind-a-tls-terminating-proxy-header-mode).

Then, on every request that presents a certificate (a certificate is not remembered by the
session):

1. **Issuer.** With `ENS_X509_ALLOWED_ISSUERS` set, a certificate from any other issuer is refused
   (`issuer not allowed`).
2. **Live revocation.** With `ENS_X509_REVOCATION` on (`ocsp`, `crl` or `ocsp-then-crl`), the
   certificate's status is checked before it is matched to anybody. A revoked certificate is
   refused (`certificate revoked`); one whose status cannot be found is refused
   (`revocation unknown`) unless `ENS_X509_REVOCATION_FAIL=open`. Good and revoked answers are
   reused for `ENS_X509_REVOCATION_CACHE` (10 minutes), no answer for 30 seconds. From 0.0.4 that
   doubles while the certificate stays unanswered, up to 2 minutes, and requests for a certificate
   that is already being checked wait for that check rather than asking again.
3. **Fingerprint.** CertAlert computes the **SHA-256 fingerprint of its DER** and looks it up in
   `cached_certificate`. If it belongs to an **IC Person**, that person is signed in, unless
   step 4 refuses it. Certificates belonging to servers never authenticate. From 0.0.4 a
   certificate that **more than one person** publishes (a regular and a privileged account
   carrying one card, a shared mailbox certificate) is refused
   (`certificate published by more than one person`): nothing says which of them is holding it.
   Earlier releases signed in whichever of them the database returned first.
4. **Cached state.** A published certificate that the nightly
   [revocation check](configuration.md#revocation-cert-alertrevocation) has found **revoked**, or
   whose validity dates do not cover the present moment, is refused even though the directory
   still publishes it (`certificate revoked`, `certificate expired`, `certificate not yet valid`).
5. **No match.** If no cached certificate matches and `x509.require-known-certificate` is `true`
   (default), the certificate is rejected and the browser is sent to the password form. With it
   `false`, see [Certificates the directory does not publish](#certificates-the-directory-does-not-publish).

This binds sign-in to the exact certificate the directory publishes for the person, not to a name.
Revoking access is a matter of removing the certificate from the directory (and the next sweep or
changelog update), or of its CA revoking it.

Each refusal is recorded as a failed `auth.login` (`details.method` `x509`) with its reason and
the certificate's fingerprint in `details.fingerprint`; a revoked certificate's refusal has
`alert` severity. A successful certificate sign-in is recorded once per session.

!!! warning "Revocation at sign-in is soft-fail by default"
    With `ENS_X509_REVOCATION` unset, step 2 runs as `ocsp-then-crl` but soft-fail: a certificate
    found revoked is refused, and one no responder or CRL answers for signs in with a warning. DISA
    STIG requires hard-fail: set the mode explicitly, which makes `ENS_X509_REVOCATION_FAIL`
    default to `closed`. With `ENS_X509_REVOCATION=off`, step 2 is skipped and step 4 uses the
    status the last nightly check cached: a certificate whose status is *Not checked* or *Unknown*
    still signs in, and a newly revoked one signs in until the next check; startup logs a warning
    while certificate sign-in is on and revocation is off.

**The revocation check at sign-in** needs the certificate's issuer. It is taken from the chain
the browser presented, `ENS_X509_TRUSTED_CA_FILE`, `cert-alert.revocation.issuer-directory` or
the TLS trust store, whichever holds a certificate whose key verifies it. OCSP answers are checked
by the JDK's path validator; a CRL counts only if it is signed by the issuer and not past its next
update. Fetches have 5 s to connect, 10 s to answer and 20 s in all, are subject to the same
[fetch policy](configuration.md#revocation-cert-alertrevocation) as the nightly check, and share
its CRL cache. `ENS_X509_OCSP_RESPONDER_URL` sends every OCSP request to one responder;
`ENS_X509_CRL_DIR` reads CRLs from files and fetches nothing. From 0.0.4 the check runs before
the database transaction that matches the certificate to a person, so a slow responder holds no
database connection; a sign-in that needs a CRL the nightly check is downloading waits for it no
longer than a fetch of its own would take; and under soft-fail the warning is logged once per
answer, not on every request.

!!! warning "`client-auth: want`, never `need`"
    With `need`, a browser without a certificate fails the TLS handshake and never reaches the
    password form.

!!! note "First sync before certificate sign-in"
    Fingerprints are matched against the cache, so certificate sign-in works only after the first
    sweep has cached people's certificates.

#### Certificates the directory does not publish

With `x509.require-known-certificate: false`, an unknown certificate falls back to matching the
`uid` and email address in its subject, and its `rfc822Name` SANs, against each person's `uid` and
the addresses the directory publishes for them. The subject's common name is never matched, nor are
addresses added in CertAlert. A certificate whose values name more than one person signs in nobody.
This trusts a name rather than a key and is off by default.

`ENS_X509_USER_FILTER` replaces that matching with an LDAP filter, for example `(uid={edipi})`
for a directory keyed on the DoD EDIPI. The placeholders are in
[Configuration → Client certificates](configuration.md#client-certificates-ensx509). The search
must find exactly one entry that CertAlert has synced, or nobody signs in. Fingerprint matching
stays the first and preferred way.

#### Behind a TLS-terminating proxy (header mode)

With `ENS_X509_SOURCE=header`, a proxy (an ingress or load balancer) terminates TLS, verifies the
client certificate and forwards it as URL-encoded PEM in `ENS_X509_HEADER`
(`X-SSL-Client-Cert`), with a shared secret in `X-ENS-Proxy-Secret`. CertAlert believes the
header only when:

- the connection itself comes from an address in `ENS_X509_TRUSTED_PROXIES` (never an address a
  forwarded-for header claims);
- it carries the secret, compared in constant time (from 0.0.4 a secret file is read again when
  its modification time or size changes, so it can be rotated without a restart);
- the certificate chains to a CA in `ENS_X509_TRUSTED_CA_FILE`, is in date, and is not restricted
  to a use other than client authentication. From 0.0.4 a chain that validated is reused for the
  same header for a minute, never past the expiry of a certificate in it or a change to the CA
  file.

Anything else is refused and audited as a failed `auth.login` (`certificate header not from a
trusted proxy`, `certificate not trusted`). In header mode a certificate from CertAlert's own TLS
handshake is ignored. Let nothing but the proxy reach CertAlert's port, and have the proxy set
both headers itself and drop any copy a client sends. With nginx:

```nginx
ssl_verify_client optional;
ssl_client_certificate /etc/nginx/dod-cas.pem;
proxy_set_header X-SSL-Client-Cert  $ssl_client_escaped_cert;
proxy_set_header X-ENS-Proxy-Secret "<the secret>";
proxy_set_header X-Forwarded-Proto  $scheme;
```

!!! warning "Trusted proxies: the proxy's addresses only"
    `ENS_X509_TRUSTED_PROXIES` also decides whose `X-Forwarded-For` hops are believed for the
    client's address (see [The client's address behind a proxy](#the-clients-address-behind-a-proxy)),
    so list the proxy's own addresses and nothing wider. From 0.0.4 the Helm chart's
    `x509.trustedProxies` is empty by default (it was `10.0.0.0/8,172.16.0.0/12,192.168.0.0/16`),
    and the chart refuses to render header mode until it names the ingress controller's pod
    network. IPv4 ranges match only IPv4 addresses: if your ingress controller reaches pods over
    IPv6, list its IPv6 range.

### Directory password (LDAP bind)

For browsers presenting no accepted certificate, the `/login` form binds to the directory as the
person signing in, either by searching (`ldap.user-search-base` + `ldap.user-search-filter`,
default `(uid={0})`) or by binding to `ldap.user-dn-patterns` directly when anonymous search is not
permitted. Set `cert-alert.security.ldap.enabled: false` to remove the password path entirely.

The sign-in is resolved to the cached person at the DN the bind used, and to nobody else. An entry
the cache does not hold yet (not swept yet, or one the sweep does not reach) signs in under the
username typed, without a cached person, and is never matched to someone else by name.

### Single sign-on (OIDC and SAML)

Off until configured. The identity provider says who somebody is; CertAlert then matches that value
to exactly one directory entry, by uid or DN (never an e-mail address or a name), and refuses anybody
the directory does not hold. Roles come from the directory as for any other sign-in, plus the
provider's own `admin-groups` and `auditor-groups`. OIDC uses the authorization code flow with PKCE
and checks the ID token's signature, issuer, audience, authorized party, expiry and nonce. SAML
requires a signed response or assertion that answers a request this browser started, once. Every
address given to the provider is built from `cert-alert.sso.public-url`, never from request headers.
From 0.0.3, an Entra ID sign-in whose groups were left out of the token (group overage)
is refused rather than signed in without the roles its groups grant, and
`cert-alert.sso.oidc.provider-logout` also signs the person out at an OIDC provider, and
`cert-alert.sso.saml.single-logout` does SAML single logout both ways (signed messages, a
`LogoutRequest` from the provider honoured only for the NameID the session signed in with). From
0.0.4 the SAML identity provider's metadata is read again every hour, and at once (at most every
five minutes) when a response's signature does not verify, so a rolled signing certificate is
followed without a restart. See [Single sign-on and roles](single-sign-on.md).

### API tokens

`Authorization: Bearer cat_…` on `/api/v1/**` authenticates an integration. Tokens are created by
administrators with scopes and an expiry (a year at most by default), stored as SHA-256, shown once,
re-checked against the creator's live directory entry on every use (fail-closed on disabled or
locked accounts), rate-limited per token, and never kept in a session; no token can manage tokens or
webhooks. Token requests have no session and need no CSRF token. From 0.0.4 a write that a token's
scopes do not cover is audited as denied under the token (`api.request` with outcome `denied`, and
`access.denied` with the actor `api-token:<id>`); earlier releases recorded it as a successful
request. See [API reference → API tokens](api-reference.md#api-tokens).

### Notice and consent banner

With `ENS_CONSENT_BANNER_ENABLED=true`, every request from a browser session that has not pressed
**I agree** is sent to `/consent` before any sign-in filter runs, so no sign-in of any kind, form
or certificate, succeeds first. An API call gets a 401 instead. The agreement is kept in the
session, so signing out shows the banner again. It is audited as `auth.consent-acknowledged`.

`/consent` is open to anybody, so from 0.0.4 an agreement is recorded once per session (a session
that has already agreed is sent on without a new record), and one client address may agree at most
10 times a minute, counted on each replica. Beyond that the agreement is refused with `429` rather
than accepted unrecorded. The address is the client's as CertAlert sees it: behind a proxy that is
not in `ENS_X509_TRUSTED_PROXIES` it is the proxy's, shared by every browser.

Exempt: `/consent` itself, the static files, `/favicon.ico`, `/error`, the health endpoints
(`/actuator/health/**`, `/livez`, `/readyz`), `/actuator/prometheus` on the management port, and
the SIEM pull API (`/api/audit/**`, bearer tokens).

### Sessions and API calls

- Sign-in creates a session; **Sign out** (account menu) logs out to `/login?logout`. The first
  signed-in request of every session gives it a new id.
- Sessions end after `ENS_SESSION_IDLE_TIMEOUT` (`30m`) idle, or `ENS_SESSION_ADMIN_IDLE_TIMEOUT`
  for administrators, and `ENS_SESSION_ABSOLUTE_TIMEOUT` (`12h`) after sign-in. With
  `ENS_SESSION_MAX_PER_USER` set, signing in beyond it ends the person's oldest session. An ended
  session goes to `/login?expired`; an API call in one gets a 401. Each is audited as
  `auth.session-expired` with its reason, `idle`, `absolute` or `concurrent`.
- For someone signing in with a certificate, an ended session means a new one on the next request
  (and, with the consent banner on, agreeing to it again).
- From 0.0.4, somebody else signing in on a session that is already signed in (a password over
  another person's live session, or another certificate with that session's cookie) takes the
  session over: its limits, its place in `ENS_SESSION_MAX_PER_USER` and its audit records are the
  new person's from then on. Earlier releases kept the first person's idle limit and name.
- With `cert-alert.session.store=jdbc`, the default with PostgreSQL that the chart and the release
  package use, sessions are kept in the database (`spring_session`): any replica serves any
  request, the session controls are enforced across replicas, and a restart or rolling update signs
  nobody out. Stored attributes are read back through an allow-list of the types CertAlert keeps;
  anything else (a deserialization gadget such as `java.util.PriorityQueue`) is refused, logged,
  and reads as absent. A client certificate's authentication is kept without the certificate.
  `memory` keeps sessions in the process, for one instance only.
- Unauthenticated calls under `/api/**` get **401** (not a redirect); pages redirect to `/login`.
- CSRF protection is on; the pages send the token from the `_csrf` meta tags, and the tables send
  it with their own requests.
- Two kinds of bearer token exist, and neither opens a session: [API tokens](#api-tokens) on
  `/api/v1/**`, scoped and never more than their creator, and the SIEM pull API's tokens on
  `/api/audit/**`, which read the audit trail and nothing else. There is no HTTP Basic. From 0.0.4
  a wrong or revoked token on the pull API is recorded as a failed `auth.login` (`details.method`
  `api-token`, with the client's address and never the token), collapsed per address.
- Every `403` is audited as `access.denied`. From 0.0.4 the first refusal is recorded at once;
  identical ones (same actor, path and reason) in the next 60 seconds are one more event with
  `details.count` and `details.collapsed`. Earlier releases wrote one event when the minute ended.

### The client's address behind a proxy

The audit trail's `source.ip`, an API token's last-used address and the per-address limits use the
connection's own address unless `SERVER_FORWARD_HEADERS_STRATEGY=framework` is set. With it, from
0.0.4, the client's address is the rightmost `X-Forwarded-For` hop that is not a trusted proxy:
starting from the connection's address, the header is followed leftwards only while the address in
hand is in `ENS_X509_TRUSTED_PROXIES`. A connection from anywhere else is the client, whatever it
says. Earlier releases took the leftmost value, which a client behind an appending proxy can
write, and so could choose the address the trail recorded.

- `ENS_X509_TRUSTED_PROXIES` is loopback by default. Until it names the proxy in front, the trail
  records the proxy's address.
- Name the proxy's addresses and nothing wider: a client inside a trusted range is passed over for
  the hop to its left, which it wrote.
- Only `X-Forwarded-Proto` and `-Port` are believed as sent. `Forwarded`, `X-Forwarded-Host` and
  `X-Forwarded-Prefix` are ignored.

What to set for the release package and the Helm chart is in
[Configuration → The client's address behind a proxy](configuration.md#the-clients-address-behind-a-proxy).

### Security headers and cookies

- **Content-Security-Policy** on every response: `default-src 'self'; script-src 'self';
  style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self' data:;
  connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self';
  object-src 'none'`. No page has an inline script. `ENS_CSP_REPORT_ONLY=true` sends it as
  report-only, an escape hatch only.
- `Strict-Transport-Security: max-age=31536000; includeSubDomains` on secure requests,
  `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`,
  `Referrer-Policy: strict-origin-when-cross-origin`, and
  `Permissions-Policy: camera=(), microphone=(), geolocation=(), payment=()`.
- The session cookie is `HttpOnly` and `SameSite=Lax`, and `Secure` on any request that arrived
  over TLS; `ENS_COOKIE_SECURE=true` makes it `Secure` always.
- CertAlert's own HTTPS allows TLS 1.2 and 1.3 only.

## Roles

| Role | Granted to | May |
|---|---|---|
| `ROLE_USER` | Everyone who authenticates | Read tables, the certificate inventory, details, metrics, notifications; keep saved views; contextual rights below |
| `ROLE_AUDITOR` | `cert-alert.security.auditor-identifiers`, a directory group in `security.groups.auditor`, or a single sign-on provider's `auditor-groups` | Read everything an administrator reads, and change nothing |
| `ROLE_ADMIN` | `cert-alert.security.admin-identifiers`, a directory group in `security.groups.admin`, or a single sign-on provider's `admin-groups` | Everything |

Somebody who is both an auditor and an administrator is an administrator. An auditor is refused
(`403`, audited as `access.denied`) on every request but `GET`, `HEAD` and `OPTIONS`, with a short
list of exceptions that only read or concern the session; see
[Single sign-on and roles](single-sign-on.md#the-auditor-changes-nothing).

`admin-identifiers` (and `auditor-identifiers`) is matched against two things only: the person's `uid` and their entry DN.
An address is never matched, not even one the directory publishes: many directories let people
edit their own `mail`, so matching it would let anybody take an administrator's address and the
role with it. An address in the list is ignored, and startup logs a warning naming the uid or DN
to list instead (see [Configuration](configuration.md#security-cert-alertsecurity)). It is never
matched against `cn`, `displayName` or `preferredName` either, which two people can share. A DN
contains commas, so name one as a YAML list item rather than in the comma-separated
`CERT_ALERT_SECURITY_ADMIN_IDENTIFIERS`.

On an empty cache, roles for a password sign-in are resolved from the directory entry just bound
against (its DN and `uid`, never its addresses or the username typed), so an administrator can run
the first sync.

Two further, **per-server** rights derive from data rather than configuration:

- **Point of contact**: you are named in the server's `serverPOC` by any value that names you, or
  you were added as a contact in CertAlert.
- **Project administrator**: you run a project containing the server (granted by an administrator).

## Permissions matrix

| Action | Reader | Point of contact (that server) | Project admin (servers in project) | Auditor | Admin |
|---|:-:|:-:|:-:|:-:|:-:|
| View People, Servers, Certificates, details, Metrics, own Notifications | ✓ | ✓ | ✓ | ✓ | ✓ |
| Keep saved views of the Certificates page | ✓ | ✓ | ✓ | ✓ | ✓ |
| Export the inventory (CSV, Excel; auditors from 0.0.3, audited as `inventory.exported`) | ✓ | ✓ | ✓ | ✓ | ✓ |
| View an entry's audit history | ✓ | ✓ | ✓ | ✓ | ✓ |
| View the Admin page, the audit log (`/admin/audit`) and the license screen | | | | ✓ | ✓ |
| View Integrations (API tokens, webhooks, deliveries) and scheduled reports | | | | ✓ | ✓ |
| Verify the audit chain | | | | ✓ | ✓ |
| Create or revoke API tokens; add, change or delete webhooks; manage scheduled reports | | | | | ✓ |
| Manage a server's contacts (one at a time or in bulk) | `AUTHENTICATED`¹ | ✓ | ✓ | | ✓ |
| Run the endpoint probe | `AUTHENTICATED`¹ | ✓ | ✓ | | ✓ |
| Add/remove a person's addresses | `AUTHENTICATED`¹ | `AUTHENTICATED`¹ | `AUTHENTICATED`¹ | | ✓ |
| Create/rename/delete projects; add/remove members | `AUTHENTICATED`¹ | `AUTHENTICATED`¹ | `AUTHENTICATED`¹ | | ✓ |
| Grant/revoke project administrator | | | | | ✓ |
| Read editable attribute values | ✓ | ✓ | ✓ | ✓ | ✓ |
| Define editable attributes / set values | | | | | ✓ |
| Trigger sync, refresh, prune, clean-up, changelog poll | | | | | ✓ |
| View job runs started by hand (`/api/v1/jobs`) | | | | ✓ | ✓ |
| View sweep runs and the prune and clean-up previews (`/api/v1/sync/runs`, `/sync/prune/preview`, `/sync/certificate-cleanup/preview`; any signed-in user before 0.0.4) | | | | ✓ | ✓ |
| See the changelog connector's last error (`lastError` in `/api/v1/changelog`; the status itself is open to everyone) | | | | ✓ | ✓ |
| Run revocation check | | | | | ✓ |
| Run / dry-run the round-up | | | | | ✓ |
| Change round-up lead days | | | | | ✓ |
| `/actuator/health`, `/health/**`, `/info`, `/livez`, `/readyz` | public | public | public | public | public |
| Other actuator endpoints (`metrics`, `loggers`) on the application's port | | | | | ✓ |

¹ Only when `cert-alert.security.contact-editors: AUTHENTICATED` (default `ADMIN`). An auditor is
refused these whatever `contact-editors` says.

Refusals are **403** with a problem-detail body for API calls. The UI hides controls a user cannot
use (it asks the API whether the current user may edit a given server).

## Public paths

`/login`, `/consent`, `/css/**`, `/js/**`, `/img/**`, `/webjars/**`, `/favicon.ico`, `/error`,
`/actuator/health`, `/actuator/health/**`, `/actuator/info`, `/livez`, `/readyz` and the OpenAPI
document `/api/v1/openapi.yaml`. With single sign-on on, also `/oauth2/authorization/**`,
`/login/oauth2/code/**`, `/saml2/authenticate/**`, `/login/saml2/sso/**` and
`/saml2/service-provider-metadata/**`. On the management port (`MANAGEMENT_SERVER_PORT`), health and
`/actuator/prometheus` are open; never publish that port beyond your monitoring network.
`/api/audit/**` takes a SIEM bearer token instead of a session, and `/api/v1/**` accepts an API
token as well as a session. Everything else requires signing in.

## Outbound connections

| Destination | Why | Control |
|---|---|---|
| LDAP directory | Sweeps, changelog, password bind, attribute writes, clean-up | `spring.ldap.*` |
| SMTP | Round-up and alert email | `cert-alert.notifications.email.enabled`, `cert-alert.alerts.email.enabled` |
| CRL distribution points / OCSP responders (HTTP or `ldap://`) | The nightly revocation check, and the check at sign-in | `cert-alert.revocation.enabled`, `ENS_X509_REVOCATION`, and the fetch policy (`cert-alert.revocation.allowed-hosts`) |
| Server TLS endpoints | Endpoint probe (user-initiated) | `cert-alert.probe.enabled` |
| Splunk HEC, syslog | Audit outputs | `ENS_AUDIT_SPLUNK_*`, `ENS_AUDIT_SYSLOG_*` |
| Identity provider | OIDC discovery document and signing keys; SAML metadata (when given as a URL; from 0.0.4 read again every `cert-alert.sso.saml.metadata-refresh`, an hour) | `cert-alert.sso.*` |
| Webhook receivers (HTTPS) | Expiry alerts and job results, signed | Admin > Integrations; `cert-alert.webhooks.*` (see [Webhooks](webhooks.md#where-a-webhook-may-go)) |

Every distribution point and responder address comes out of a certificate, so fetches never go to
link-local or cloud metadata addresses, go to loopback only when it is named in `allowed-hosts`,
follow at most three redirects (http and https only), and are limited in size and time. With
`allowed-hosts` set, nothing else is fetched from. See
[Configuration → Revocation](configuration.md#revocation-cert-alertrevocation).

`serverURL` is free text from the directory. From 0.0.4 a server's page links it (the **Open**
button) only when it is an `http://` or `https://` address, so a value with another scheme cannot
become a link that runs in CertAlert's origin.

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

## FIPS 140-3 and DISA STIG

See [FIPS 140-3 and DISA STIG](fips-and-stig.md).

## Hardening checklist

- Use the `postgres` profile with credentials from the environment or a secret, and
  `sslmode=verify-full` to a PostgreSQL outside the release package.
- Use `ldaps://` for the directory.
- Enable TLS; keep `client-auth: want`; prefer certificate sign-in. Consider
  `ldap.enabled: false` for certificate-only deployments.
- With certificates in use: `ENS_X509_REVOCATION` other than `off`, `ENS_X509_REVOCATION_FAIL`
  left `closed`, and `ENS_X509_ALLOWED_ISSUERS` if only some trusted CAs issue user certificates.
- For DoD systems: `ENS_CONSENT_BANNER_ENABLED=true`, and session limits as your policy requires
  (DISA STIG suggests `ENS_SESSION_ADMIN_IDLE_TIMEOUT=15m` and `ENS_SESSION_MAX_PER_USER=3`).
- Behind a TLS-terminating proxy: `SERVER_FORWARD_HEADERS_STRATEGY=framework`, and
  `ENS_COOKIE_SECURE=true` if the proxy does not send `X-Forwarded-Proto`. In the release package,
  `CERT_ALERT_BIND_ADDRESS=127.0.0.1`. Never set `CERT_ALERT_ALLOW_PLAIN_HTTP=true` outside a lab.
- Behind any proxy, set `ENS_X509_TRUSTED_PROXIES` (Helm `x509.trustedProxies`) to the proxy's own
  addresses: the compose network's subnet, or the ingress controller's pod network. Never a whole
  private range.
- In header mode, let only the proxy reach CertAlert, and keep the proxy secret in a file or
  Secret.
- Keep the management port off every ingress, route and published port.
- Set `cert-alert.revocation.allowed-hosts` to your PKI's hosts where you can.
- Keep `x509.require-known-certificate: true`.
- Keep `admin-identifiers` short; keep `contact-editors: ADMIN` unless servers' owners are expected
  to curate projects and addresses themselves.
- Never set `cert-alert.security.enabled: false` outside local development. From 0.0.3 the
  application refuses to start with it unless the `dev` profile is active.
- Keep keystores out of the image: in the release package's `tls/` directory (mounted
  read-only), or in Kubernetes Secrets with Helm.
- Set `revocation.allow-unverified-crl: false` once `issuer-directory` is populated.
- Leave prune's safeguards as they are (`cert-alert.ldap.prune.max-share`), and keep people who
  share one certificate out of certificate sign-in: such a certificate is refused.
- Leave `server.error.include-message: never`.
- Configure the classification banner where policy requires it.
- With single sign-on, map on a claim people cannot change, and serve CertAlert over HTTPS (SAML
  requires it).
- Before creating webhooks, set `cert-alert.webhooks.secret-key` (the same on every replica; new
  installations of 0.0.3 and later get one generated), keep it with your backups, and narrow
  their destinations with `allowed-hosts`. Leave `block-private-networks` unset or `true`, and
  name internal receivers in `allowed-hosts` instead of setting it to `false`.
- With OIDC single sign-on, turn on `cert-alert.sso.oidc.provider-logout` (from 0.0.3) so signing
  out also ends the session at the provider; with SAML, `cert-alert.sso.saml.single-logout` with a
  signing key.
- Give API tokens the fewest scopes and the shortest expiry that serve the integration; revoke the
  ones nobody uses (the Integrations page shows each token's last use).
- The container runs as non-root uid 185.
