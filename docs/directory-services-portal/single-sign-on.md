# Single sign-on

People can sign in to the portal through your organization's identity provider, by OpenID Connect
(OIDC) or SAML 2.0. Each protocol is **off until configured**. Password and certificate sign-in work
as before alongside it; `PASSWORD_LOGIN_ENABLED=false` leaves only single sign-on and certificates.
The login page shows one button per configured provider.

## How a sign-in is mapped

The provider asserts an identity: an OIDC claim, or a SAML attribute or the NameID.
`SSO_OIDC_USER_FILTER` or `SSO_SAML_USER_FILTER`, with `{{value}}` replaced by that identity
(filter-escaped), must match **exactly one** directory entry; none or several refuses the sign-in.

Groups, administrator status (`ADMIN_USERS`, `ADMIN_GROUPS`) and permissions then come from the
directory, exactly as for a password sign-in, and are read again every `GROUP_REFRESH_INTERVAL`. The
provider's own groups make someone an administrator **only** when one of them is listed in
`SSO_OIDC_ADMIN_GROUPS` or `SSO_SAML_ADMIN_GROUPS` (`;`-separated, compared case-insensitively).
Nothing else the provider asserts grants a role.

**Choose an identity claim that can't be changed or reused.** The mapping is only as strong as the
claim. Prefer an immutable one held in the directory, such as Entra ID's `oid` (stored in an
attribute such as `employeeID` or `msDS-cloudExtensionAttribute1`), Okta's `sub`, or AD FS's
ImmutableID or objectGUID-derived claims, over `preferred_username`, UPNs or e-mail addresses, which
can be renamed or handed to someone else. An `email` claim is used only when `email_verified` is
true. Also restrict who may use the application at the provider (Entra *Assignment required*, Okta
assignments, AD FS access control policy).

!!! info "From the next release: a warning for a claim that can be changed"
    The API logs a WARN at startup when `SSO_OIDC_USERNAME_CLAIM` is a claim a provider lets be
    changed (`preferred_username`, `email`, `upn`, `unique_name`, `name`, ...). Sign-in is
    unchanged. The API's default stays `preferred_username`, so that upgraded installations keep
    signing in; new release-package `.env` files get `SSO_OIDC_USERNAME_CLAIM=sub`. The Helm
    chart's default stays `preferred_username` too, because chart defaults apply to upgraded
    releases: set it yourself on a new install.

### Moving to an immutable claim

1. Put each user's immutable id into a directory attribute the service account can read: Entra ID
   `oid` into, for example, `employeeID` or `msDS-cloudExtensionAttribute1` through Entra Connect
   or Cloud Sync; Okta `sub` into an attribute your provisioning writes.
2. Check one user: the attribute holds exactly the value the provider sends (decode an ID token,
   or read the claim in the provider's token preview).
3. Set `SSO_OIDC_USERNAME_CLAIM` (`oid`, `sub`) and `SSO_OIDC_USER_FILTER` to search that
   attribute, for example `(&(objectClass=user)(employeeID={{value}}))`, and restart (Helm:
   `sso.oidc.usernameClaim` and `sso.oidc.userFilter`).

Signed-in sessions are not affected. Users without the attribute get `sso_error=no_account` until
it is filled in, so fill it for everyone first. The startup WARN then goes away.

## What is checked

=== "OpenID Connect"

    The authorization code flow with PKCE (S256, confidential clients too), `state` and `nonce`. The
    ID token's signature against the provider's JWKS, its issuer, audience (the client id) and expiry,
    with 60 seconds of clock skew. The issuer must be `https://` (http only for localhost), and its
    discovery document must name the same issuer.

=== "SAML 2.0"

    Requests go by HTTP-Redirect. The response or every assertion must be signed with a key from the
    identity provider's metadata. Issuer, audience (the entity id), recipient,
    `NotBefore`/`NotOnOrAfter` (with `SSO_SAML_CLOCK_SKEW`) and `InResponseTo` (this browser's request)
    are checked. **Identity-provider-initiated** (unsolicited) responses are refused, and each
    assertion id is accepted only once.

Sign-in requests in flight are kept on the server under a one-time handle in a short-lived `DSP_SSO`
cookie, because the provider's answer never carries the `SameSite=Strict` session cookie. SAML's
cross-site POST needs that cookie to be `SameSite=None; Secure`, so **SAML requires HTTPS** (set
`ENS_COOKIE_SECURE=true` behind a TLS proxy). The provider's tokens are not kept: the session holds
only the portal user.

- The [notice and consent banner](configuration.md#notice-and-consent-banner), when on, must be
  acknowledged before a sign-on starts.
- Every sign-on and refusal is audited as `auth.login` with `method` `oidc` or `saml` (refusals with
  a `reasonCode`); the user is sent back to `/login?sso_error=<code>` with a message.
- **Sign-out ends the portal session only**, not the provider's.
- Administrator rights granted by `SSO_*_ADMIN_GROUPS` are not re-checked with the provider during a
  session. Such a session ends after `SSO_IDP_ADMIN_SESSION_LIFETIME` (or the absolute timeout, if
  shorter), so a removal at the provider takes effect by then. Rights from the directory are read
  again every `GROUP_REFRESH_INTERVAL` as usual.
- The web tier drops `Forwarded`, `X-Forwarded-Prefix` and `X-Forwarded-Port` from clients and sends
  `PUBLIC_HOST` as `X-Forwarded-Host` when set.

## Endpoints to register with the provider

`<portal>` is `PORTAL_PUBLIC_URL`:

| | |
|---|---|
| OIDC redirect URI | `https://<portal>/api/auth/sso/callback/oidc` |
| SAML entity id and service provider metadata | `https://<portal>/api/auth/sso/saml/metadata` |
| SAML assertion consumer service (HTTP-POST) | `https://<portal>/api/auth/sso/saml/acs/saml` |

The web tier already forwards `/api/`, so nothing changes in nginx.

## Settings

| Variable | Default | Description |
|---|---|---|
| `PORTAL_PUBLIC_URL` | empty | The address users open, e.g. `https://portal.example.com`. The redirect URI, SAML entity id and assertion consumer are built from it, never from the request's `Host`. **Required for SAML** (unless `SSO_SAML_ENTITY_ID` is set), recommended for OIDC. Give the web tier `PUBLIC_HOST` (its host[:port]) too |
| `SSO_IDP_ADMIN_SESSION_LIFETIME` | `1h` | A session whose administrator rights come only from the provider's groups ends this long after sign-in |
| `SSO_START_LIMIT_PER_MINUTE` (next release) | `30` | Sign-ons one client address may start per minute, per API replica, the address as resolved with `SERVER_FORWARD_HEADERS_STRATEGY`. Each start keeps a sign-in request for 10 minutes; further starts are sent back to `/login?sso_error=busy` with `Retry-After`, and the login page says to wait a minute |
| `SSO_MAX_PENDING_REQUESTS` (next release) | `10000` | Sign-in requests in flight kept in memory at most, without the shared database (with it they are rows, and expired ones are deleted every minute). When full, new starts get `sso_error=busy`. Expired requests are now removed every minute (was five) |
| `SSO_OIDC_ISSUER_URI` | empty | The issuer |
| `SSO_OIDC_CLIENT_ID` | empty | With the issuer, turns OpenID Connect on |
| `SSO_OIDC_CLIENT_SECRET_FILE` / `SSO_OIDC_CLIENT_SECRET` | empty | The client secret (prefer the file); empty makes a public client |
| `SSO_OIDC_SCOPES` | `openid profile email` | |
| `SSO_OIDC_USERNAME_CLAIM` | `preferred_username` (next release's package `env.template`: `sub`) | The claim mapped through the user filter; `email` is used only when `email_verified` is true. From the next release the API warns at startup about a claim a provider lets be changed; see [Moving to an immutable claim](#moving-to-an-immutable-claim) |
| `SSO_OIDC_USER_FILTER` | `(&(objectClass=inetOrgPerson)(uid={{value}}))` | Must match exactly one entry |
| `SSO_OIDC_GROUPS_CLAIM` | `groups` | The claim listing the person's groups |
| `SSO_OIDC_ADMIN_GROUPS` | empty | Provider groups that grant administrator (`;`-separated) |
| `SSO_OIDC_LABEL` | `Sign in with single sign-on` | Button text |
| `SSO_SAML_METADATA` | empty | The identity provider's metadata: an `https://` URL or a file. Turns SAML on |
| `SSO_SAML_ENTITY_ID` | `https://<portal>/api/auth/sso/saml/metadata` | This service provider's entity id |
| `SSO_SAML_USERNAME_ATTRIBUTE` | empty (the NameID) | The attribute mapped through the user filter |
| `SSO_SAML_USER_FILTER` | `(&(objectClass=inetOrgPerson)(uid={{value}}))` | |
| `SSO_SAML_GROUPS_ATTRIBUTE` | empty | The attribute listing the person's groups |
| `SSO_SAML_ADMIN_GROUPS` | empty | Provider groups that grant administrator |
| `SSO_SAML_LABEL` | `Sign in with SAML single sign-on` | Button text |
| `SSO_SAML_CLOCK_SKEW` | `2m` | Tolerated clock difference (at most `10m`) |
| `SSO_SAML_SIGNING_KEY_FILE` / `SSO_SAML_SIGNING_CERT_FILE` | empty | PKCS#8 PEM key and certificate, for providers that require signed requests |

In the Helm chart these are `server.publicUrl` and the `sso.*` values (`sso.oidc.issuerUri`,
`sso.oidc.clientSecret.existingSecret`, `sso.saml.metadataUrl` or `sso.saml.metadataXml`,
`sso.saml.signing.existingSecret`, ...); with its NetworkPolicy on, `networkPolicy.egress.sso` must
reach the provider. The release bundle's `env.template` and the native package's `portal.env` list
the same variables.

## Provider walkthroughs

### Microsoft Entra ID (OIDC)

1. **App registrations > New registration**; platform *Web*, redirect URI as above.
   **Certificates & secrets** > new client secret.
2. **Token configuration** > add the groups claim (*Security groups*, emitted as *Group ID*) if
   administrators come from an Entra group.
3. Set `SSO_OIDC_ISSUER_URI=https://login.microsoftonline.com/<tenant-id>/v2.0`,
   `SSO_OIDC_CLIENT_ID=<application (client) id>`, and the secret.
4. **Enterprise applications** > the app > **Properties**: *Assignment required* = Yes, then assign
   the users or groups.
5. Map users by `oid`: `SSO_OIDC_USERNAME_CLAIM=oid` with
   `SSO_OIDC_USER_FILTER=(&(objectClass=user)(employeeID={{value}}))` when the object id is synced
   into that attribute. `preferred_username` with `(&(objectClass=user)(userPrincipalName={{value}}))`
   works but follows renames. `SSO_OIDC_ADMIN_GROUPS=<object id of the group>`.

Users in more than 200 groups get no groups claim (Entra's overage rule): give them administrator
rights through `ADMIN_GROUPS` instead.

### Okta (OIDC)

1. **Applications > Create App Integration > OIDC, Web Application**; sign-in redirect URI as above;
   assign the users who should reach the portal.
2. `SSO_OIDC_ISSUER_URI=https://<org>.okta.com/oauth2/default` (or your custom authorization
   server), the client id and secret.
3. For administrator groups, add a `groups` claim to the authorization server (with a filter, for
   example *Starts with* `portal-`) and list the group names in `SSO_OIDC_ADMIN_GROUPS`.
4. Map users by `sub` (the Okta user id, stored in a directory attribute) where you can.
   `preferred_username` (the Okta login) or a verified `email`
   (`SSO_OIDC_USERNAME_CLAIM=email`, `SSO_OIDC_USER_FILTER=(&(objectClass=inetOrgPerson)(mail={{value}}))`)
   also work but follow renames.

### AD FS (SAML, or OIDC on AD FS 2016 and later)

1. **AD FS Management > Relying Party Trusts > Add**, *Import data about the relying party published
   online*: `https://<portal>/api/auth/sso/saml/metadata`.
2. Claim issuance policy: *Send LDAP Attributes as Claims*, User-Principal-Name to *UPN* and
   Token-Groups (unqualified names) to *Group*.
3. Set `SSO_SAML_METADATA=https://<adfs>/FederationMetadata/2007-06/FederationMetadata.xml`,
   `SSO_SAML_USERNAME_ATTRIBUTE=http://schemas.xmlsoap.org/ws/2005/05/identity/claims/upn`,
   `SSO_SAML_USER_FILTER=(&(objectClass=user)(userPrincipalName={{value}}))`, and for administrator
   groups `SSO_SAML_GROUPS_ATTRIBUTE=http://schemas.xmlsoap.org/claims/Group` with
   `SSO_SAML_ADMIN_GROUPS=Portal Admins`.
4. AD FS signs assertions by default. If it requires signed requests, give the portal a key
   (`SSO_SAML_SIGNING_*`) and re-import the metadata.

OIDC on AD FS: **Application Groups** > *Server application accessing a web API*;
`SSO_OIDC_ISSUER_URI=https://<adfs>/adfs`, `SSO_OIDC_USERNAME_CLAIM=upn`.

## Multi-factor authentication

Password sign-in has no second factor of its own. For MFA, use single sign-on (the provider's MFA and
conditional access apply) or certificate sign-in (CAC/PIV), and set `PASSWORD_LOGIN_ENABLED=false`.

## Upgrading

Nothing changes until `SSO_*` is set. The login page gets a button per provider, and
`/api/auth/sso/**` is new; the web tier already forwards `/api/`.

From the next release:

- Single sign-on starts are limited per client address (`SSO_START_LIMIT_PER_MINUTE`, `30` per
  replica), and in-memory sign-in requests are capped (`SSO_MAX_PENDING_REQUESTS`, `10000`). A
  refused start lands on the login page with "wait a minute". Behind a proxy, keep
  `SERVER_FORWARD_HEADERS_STRATEGY=framework` so the limit sees each client's address.
- A startup WARN for an `SSO_OIDC_USERNAME_CLAIM` that can be changed. Nothing stops; move to an
  immutable claim when you can.
