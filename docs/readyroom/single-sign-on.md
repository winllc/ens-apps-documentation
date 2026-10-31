# Single sign-on

Browsers can sign in to ReadyRoom through your identity provider instead of the directory password
form: OpenID Connect (authorization code flow with PKCE) and SAML 2.0 Web SSO, each **off until
enabled**. Both can be on at once; the sign-in page then shows a button for each next to the form,
which keeps working, as do client certificates.

The Windows client is not affected: its check-in API never goes to the identity provider. Demo mode
does not offer single sign-on.

## Who signs in, and with which role

Whoever the identity provider signs in must match **exactly one** directory user by the protocol's
**user filter**: an LDAP filter with the provider's claims (OIDC) or attributes (SAML) as
`{placeholders}`, the same way `ENS_X509_USER_FILTER` maps a certificate. The values are escaped. No
match, or several, is refused, and so is a directory user whose account is disabled, locked out or
expired: the account is checked live, as for certificates, and the refusal is audited as a failed
sign-in with the reason (for example `account disabled in the directory`). A directory that cannot
be read lets the sign-in go on, from the user cache.

That directory user then gets exactly the roles any other sign-in would: `USER`, a role an
administrator assigned, `ADMIN` for `application.super-user-dns`. The provider's groups add `MANAGER`
or `ADMIN` **only** through `ENS_SSO_ROLE_MAPPING`; a group not listed there grants nothing, whatever
it is called.

The browser session that follows is checked again every `ENS_SESSION_ACCOUNT_CHECK_INTERVAL`
(`5m`): a disabled, locked out, expired or missing account, or a changed ReadyRoom role (the
stored role or `application.super-user-dns`; the provider's group grants do not count), ends it.
See [Security](security.md#notice-and-consent-banner-and-sessions).

```text
ENS_SSO_ROLE_MAPPING=ReadyRoom Admins=ADMIN;ReadyRoom Managers=MANAGER
```

Pairs are separated by `;` or new lines and matched ignoring case; the last `=` separates the role,
so DN-style group names work. Only `MANAGER` and `ADMIN` may be mapped.

**Map on an immutable claim the identity provider controls**, such as the UPN or an employee id, not
one users can set themselves. An OIDC filter using `{email}` signs in only when the ID token says
`email_verified: true`; Entra ID's `email` claim, for example, is not verified.

## Endpoints to register at the identity provider

`<base>` is `APPLICATION_BASE_URL`. Single sign-on requires it to be the absolute URL users reach;
startup stops while it is the packaged default. Every address below, and the SAML entity id (the
audience assertions must name), is built from it and never from a request's `Host`, `Forwarded` or
`X-Forwarded-Host`, which a client can set.

| | |
|---|---|
| OIDC redirect URI | `<base>/login/oauth2/code/oidc` |
| SAML entity id and metadata | `<base>/saml2/service-provider-metadata/saml` (download it for the provider) |
| SAML assertion consumer service (HTTP-POST) | `<base>/login/saml2/sso/saml` |
| OIDC post-logout redirect URI (next release, with `ENS_SSO_OIDC_END_SESSION`) | `<base>/signed-out` |
| SAML single logout service (next release, HTTP-Redirect, with `ENS_SSO_SAML_SINGLE_LOGOUT`; named in the SP metadata) | `<base>/logout/saml2/slo` |

`ENS_SSO_SAML_ENTITY_ID`, when set, must be an absolute URI.

## Settings

| Variable | Default | Purpose |
|---|---|---|
| `ENS_SSO_ROLE_MAPPING` | empty | Provider group to role, as above |
| `ENS_SSO_CLOCK_SKEW` | `60s` | How far the provider's clock may be off for token and assertion times (at most 5 minutes) |
| `ENS_SSO_OIDC_ENABLED` | `false` | OpenID Connect |
| `ENS_SSO_OIDC_ISSUER_URI` | | The issuer. Its discovery document is read on first use, not at startup, so a provider outage does not stop ReadyRoom. From the next release `https://` only, and the endpoints and key set the discovery document names must be `https://` too; `http://` stops startup |
| `ENS_SSO_OIDC_CLIENT_ID` | | Also the audience ID tokens must name |
| `ENS_SSO_OIDC_CLIENT_SECRET`, `ENS_SSO_OIDC_CLIENT_SECRET_FILE` | empty | Empty: a public client (PKCE only). The file wins |
| `ENS_SSO_OIDC_SCOPES` | `openid,profile,email` | |
| `ENS_SSO_OIDC_USER_FILTER` | required | For example `(userPrincipalName={upn})` |
| `ENS_SSO_OIDC_GROUPS_CLAIM` | `groups` | |
| `ENS_SSO_OIDC_DISPLAY_NAME` | `Sign in with single sign-on` | The button |
| `ENS_SSO_OIDC_END_SESSION` (next release) | `false` (the bundle's `env.template` sets `true` for new installs) | Signing out also ends the provider's session (RP-initiated logout). See [Signing out](#signing-out) |
| `ENS_SSO_SAML_ENABLED` | `false` | SAML 2.0 |
| `ENS_SSO_SAML_IDP_METADATA` | | The provider's metadata: `https://...` (read on first use and every 12 hours, keeping the last good copy) or `file:/path`. From the next release `http://` stops startup, and a redirect of the metadata may not leave https |
| `ENS_SSO_SAML_ENTITY_ID` | `{baseUrl}/saml2/service-provider-metadata/saml` | This service provider's entity id |
| `ENS_SSO_SAML_USER_FILTER` | required | `{nameid}` and attribute names, for example `(userPrincipalName={http://schemas.xmlsoap.org/ws/2005/05/identity/claims/upn})` |
| `ENS_SSO_SAML_GROUPS_ATTRIBUTE` | `http://schemas.microsoft.com/ws/2008/06/identity/claims/groups` | |
| `ENS_SSO_SAML_SP_KEY_FILE`, `ENS_SSO_SAML_SP_CERT_FILE` | empty | PEM, PKCS#8 RSA key and certificate: signs requests and decrypts encrypted assertions, if the provider wants either; from the next release, also signs single logout messages |
| `ENS_SSO_SAML_DISPLAY_NAME` | `Sign in with SAML single sign-on` | The button |
| `ENS_SSO_SAML_SINGLE_LOGOUT` (next release) | `false` (the bundle's `env.template` sets `true` for new installs) | SAML single logout; needs the SP key and certificate. See [Signing out](#signing-out) |

A missing required setting, a filter with no placeholder, or a role mapping to anything but `MANAGER`
or `ADMIN` stops startup with the setting's name.

!!! info "From the next release: trust material over HTTPS only"
    The OIDC issuer and the SAML metadata are what every ID token and assertion is verified against,
    so they are read only over `https://` (or, for SAML, from a file), never `http://`, where anyone
    on the network path could swap the keys. An `http://` value stops startup, naming the setting.
    Serve the metadata over https, or download it to a file: with the release package, put it in
    `tls/` (which `docker-compose.tls.yml` mounts at `/etc/in-n-out/tls`) and set
    `ENS_SSO_SAML_IDP_METADATA=file:/etc/in-n-out/tls/<name>.xml`; with Helm, use
    `sso.saml.metadataConfigMap`. And with SAML on, startup stops unless `APPLICATION_BASE_URL`
    (Helm `publicUrl`) is `https://`: the browser-binding cookie is `SameSite=None; Secure` and is
    never sent over HTTP, so SAML could not have worked there anyway.

With Helm these are the `sso.*` values (`sso.roleMapping`, `sso.oidc.issuerUri`,
`sso.oidc.existingSecret`, `sso.saml.idpMetadataUrl` or `sso.saml.metadataConfigMap`,
`sso.saml.spKeySecret`, and from the next release `sso.oidc.endSession` and `sso.saml.singleLogout`,
...). With the release package, add the `ENS_SSO_*` variables to `.env`;
from the next release the package's `env.template` has a commented *Single sign-on* block with all of
them (copy it into an older `.env`), and its `README.md` a *Single sign-on* section.

## Provider walkthroughs

### Microsoft Entra ID

**OIDC.** Register an app (web platform) with the redirect URI and create a client secret; issuer
`https://login.microsoftonline.com/<tenant id>/v2.0`. Add the `upn` optional claim to the ID token
(*Token configuration*) and use `(userPrincipalName={upn})`, or, where the directory holds the Entra
object id (for example AD's `msDS-ExternalDirectoryObjectId`, `User_<oid>`),
`(msDS-ExternalDirectoryObjectId=User_{oid})`: `oid` never changes. Do not map on
`preferred_username` (Entra documents it as a display hint that can change) nor on `email`, which is
not verified.

For groups, add the *groups* claim (security groups) to the ID token. Entra sends group object ids,
so map those (`<object id>=ADMIN`). A user in more than 200 groups gets no `groups` claim (the overage
case): assign the app's groups explicitly ("Groups assigned to the application") or rely on
ReadyRoom's own roles.

**SAML.** Create an enterprise application, upload the service provider metadata, use the *App
Federation Metadata Url* as `ENS_SSO_SAML_IDP_METADATA`, and filter on the `upn` or `name` attribute.

### Okta

**OIDC.** A Web app integration with the redirect URI; issuer `https://<org>.okta.com` (or an
authorization server's); add a `groups` claim filtered to the ReadyRoom groups.

**SAML.** An app integration with the assertion consumer URL and entity id above, NameID or an
attribute carrying the user's login, and a `groups` attribute statement; the metadata URL is on
*Sign On*.

### ADFS

**SAML.** Add a relying party trust from the service provider metadata URL (or file); claim rules
sending UPN (and *Token-Groups* as `http://schemas.microsoft.com/ws/2008/06/identity/claims/groups`
or *Group*); metadata `https://<adfs>/FederationMetadata/2007-06/FederationMetadata.xml`.

**OIDC (ADFS 2016 and later).** An application group with a server application; issuer
`https://<adfs>/adfs`; filter on `{upn}`.

## What is checked

- **OIDC**: the state, the nonce, PKCE (always, also with a client secret), the ID token's signature
  against the issuer's published keys, its issuer, audience and times. Access and refresh tokens are
  not kept.
- **SAML**: the response's or assertion's signature against the metadata's certificates, issuer,
  audience, destination and recipient, and times. The response must answer a request this browser
  started (`InResponseTo`; identity-provider-initiated sign-in is refused), and each assertion is used
  once, across replicas (`sso_assertion_seen`).
- The SAML answer is a cross-site POST, on which browsers leave out the `SameSite=Lax` session
  cookie. The request it answers is kept in the database by its RelayState (`sso_saml_request`, ten
  minutes) and tied to the browser by a cookie of its own (`ENS_SAML_<relay state>`,
  `SameSite=None; Secure`, sent only to the assertion consumer service). **SAML needs HTTPS.** From
  the next release, startup stops when SAML is on and `APPLICATION_BASE_URL` is not `https://`.
- ReadyRoom asks the provider's HTTP-Redirect sign-in endpoint (the POST binding would need a
  self-submitting form, which the Content-Security-Policy blocks).
- The consent banner, when on, must be acknowledged before either method starts.

## Audit

Every sign-in is `auth.login` with `method` `oidc` or `saml2` and `idpRoles`, the roles the provider's
groups granted through `ENS_SSO_ROLE_MAPPING`. Every refusal is a failed `auth.login` with the reason
(the provider's error, the check that failed, `no single directory user matches`, ...).

## Signing out

Up to the current release, signing out ends the ReadyRoom session only, and the identity provider's
stays open: on a shared desk, the next person pressing the single sign-on button is signed in as the
last one. From the next release two settings end it too. Both are off unless set, so an upgrade
changes nothing; the release bundle's `env.template` turns them on for new installs.

- **OpenID Connect**, `ENS_SSO_OIDC_END_SESSION=true` (Helm `sso.oidc.endSession`): after
  ReadyRoom's own sign-out, the browser goes on to the provider's `end_session_endpoint` (from its
  discovery document; `https://` only) with `id_token_hint`, `client_id` and
  `post_logout_redirect_uri` `<base>/signed-out`, which you register at the provider (Entra ID: add
  it to the app's redirect URIs; Okta: *Sign-out redirect URIs*). The provider then sends the browser
  back to the sign-in page. A provider with no `end_session_endpoint` is logged once (WARN) and
  signing out stays local.
- **SAML**, `ENS_SSO_SAML_SINGLE_LOGOUT=true` (Helm `sso.saml.singleLogout`), with
  `ENS_SSO_SAML_SP_KEY_FILE` and `ENS_SSO_SAML_SP_CERT_FILE` (Helm `sso.saml.spKeySecret`; the
  `LogoutRequest` must be signed) and an identity provider whose metadata names an HTTP-Redirect
  `SingleLogoutService`:
    - signing out sends the provider a signed `LogoutRequest` naming the sign-in's NameID (with its
      format and qualifiers) and session index, kept from the sign-in for this; the provider's signed
      `LogoutResponse` comes back to `<base>/logout/saml2/slo` and is checked against the request
      this browser sent before landing on the sign-in page;
    - a `LogoutRequest` the provider starts (the person signed out of another application) is
      accepted there too, signature and NameID checked, and signs this browser out.

    The SP metadata then names that endpoint (HTTP-Redirect only), so **re-import the SP metadata at
    the provider**. Without the key, or with a provider offering no HTTP-Redirect single logout,
    startup logs a WARN and signing out stays local. Sessions signed in before the upgrade kept no
    NameID and sign out locally.

The sign-out form is a POST, and the Content-Security-Policy's `form-action 'self'` also applies to
the redirects that follow it, so ReadyRoom does not redirect the form to the provider: it signs out,
then sends the browser to its own page `<base>/signed-out`, which continues to the provider by a
plain navigation (and shows a **Continue** button). The provider's address waits for that page in
the new, signed-out session, for one use; the page never takes an address from the request. Both are
audited as `auth.logout`, as before.

## Not included

Demo mode does not offer single sign-on.
