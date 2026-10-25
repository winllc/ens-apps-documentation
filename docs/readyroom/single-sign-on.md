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
match, or several, is refused.

That directory user then gets exactly the roles any other sign-in would: `USER`, a role an
administrator assigned, `ADMIN` for `application.super-user-dns`. The provider's groups add `MANAGER`
or `ADMIN` **only** through `ENS_SSO_ROLE_MAPPING`; a group not listed there grants nothing, whatever
it is called.

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
| `ENS_SSO_SAML_ENABLED` | `false` | SAML 2.0 |
| `ENS_SSO_SAML_IDP_METADATA` | | The provider's metadata: `https://...` (read on first use and every 12 hours, keeping the last good copy) or `file:/path`. From the next release `http://` stops startup, and a redirect of the metadata may not leave https |
| `ENS_SSO_SAML_ENTITY_ID` | `{baseUrl}/saml2/service-provider-metadata/saml` | This service provider's entity id |
| `ENS_SSO_SAML_USER_FILTER` | required | `{nameid}` and attribute names, for example `(userPrincipalName={http://schemas.xmlsoap.org/ws/2005/05/identity/claims/upn})` |
| `ENS_SSO_SAML_GROUPS_ATTRIBUTE` | `http://schemas.microsoft.com/ws/2008/06/identity/claims/groups` | |
| `ENS_SSO_SAML_SP_KEY_FILE`, `ENS_SSO_SAML_SP_CERT_FILE` | empty | PEM, PKCS#8 RSA key and certificate: signs requests and decrypts encrypted assertions, if the provider wants either |
| `ENS_SSO_SAML_DISPLAY_NAME` | `Sign in with SAML single sign-on` | The button |

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
`sso.saml.spKeySecret`, ...). With the release package, add the `ENS_SSO_*` variables to `.env`;
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

## Not included

Sign-out ends the ReadyRoom session only, not the one at the identity provider (no single logout).
