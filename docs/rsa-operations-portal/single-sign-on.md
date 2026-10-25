# Single sign-on

Operators (PMO, Trusted Agents, help desk) can sign in with the organization's identity provider,
by OpenID Connect (OIDC) or SAML 2.0. Single sign-on is **off until an identity provider is
configured**; the passcode sign-in works as before either way, and the self-service pages for end
users do not use it.

## How a sign-in goes

1. The operator clicks **Sign in with &lt;provider&gt;** on the sign-in page.
2. The identity provider proves who the operator is. Its groups decide the Portal roles, through the
   [role mapping](#settings) and nothing else. A sign-in whose groups map to no role is refused
   (`no_mapped_role`); so is an answer without the user ID claim.
3. That is not yet a sign-in: the sign-in page then asks for the **SecurID passcode of the same user
   ID**, which opens the operator's own Authentication Manager session. A passcode of another user is
   refused (`sso_user_mismatch`), and so is one entered more than `ENS_SSO_PENDING_TIMEOUT`
   (5 minutes) after the identity provider's answer.

The Connector makes the operator's changes in that session, so Authentication Manager still checks
the operator's own permissions and names the operator in its log, and every
[help-desk rule](help-desk.md) (identity verification, a second operator's approval, the audit record
before the change) applies exactly as to a passcode sign-in.

!!! note "Administrative roles without single sign-on"
    With an identity provider configured, a passcode-only sign-in still gets the roles Authentication
    Manager grants, unless `ENS_SSO_REQUIRE_FOR_ADMIN_ROLES=true` (default `false`). With it, a
    passcode sign-in alone gets the user role only, and administrative roles come only through single
    sign-on.

## What is checked

- **OpenID Connect**: the authorization code flow with PKCE (S256), for confidential clients too, and
  a nonce. The ID token's signature (the provider's published keys), issuer, audience, `azp`, expiry
  and issue time are checked, with `ENS_SSO_CLOCK_SKEW` (60 s) of tolerance; the access token is not
  kept. A registration without an issuer URI or without the `openid` scope stops the start.
- **SAML 2.0**: the response or every assertion must be signed with the asserting party's
  certificate; issuer, audience, destination, recipient, `InResponseTo` and validity times are
  checked (`ENS_SSO_CLOCK_SKEW`). Only an answer to a request this Portal sent is accepted, once:
  requests are kept in the database (table `SsoSamlRequest`, five minutes) by RelayState, because the
  identity provider's POST arrives without the session cookie; the answer that uses one deletes it,
  so a replay is refused. IdP-initiated sign-in is refused. Requests go by HTTP-Redirect (the
  Content-Security-Policy forbids the self-submitting form of HTTP-POST).
- **No single logout**: signing out of the Portal ends the Portal session and the operator's
  Authentication Manager session, not the identity provider's.

## Settings

The identity providers are Spring Boot's own settings; the registration id (letters and digits) is
part of the URLs to register with the provider.

| Setting | Meaning |
|---|---|
| `APPLICATION_DNS_NAME` | Required with single sign-on: the Portal's public address (`https://rsa-portal.example.com`). Every URL given to an identity provider is built from it, never from the request's `Host` or `X-Forwarded-*` headers: the redirect URI, the SAML entity id and Assertion Consumer Service |
| `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_<ID>_CLIENT_ID`, `_CLIENT_SECRET`, `_CLIENT_NAME`, `_SCOPE` | An OpenID Connect client. `_SCOPE` must include `openid` (for example `openid,profile`, plus what your provider needs to send the groups claim). Redirect URI: `<APPLICATION_DNS_NAME>/login/oauth2/code/<id>` |
| `SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_<ID>_ISSUER_URI` | Required: the provider's issuer; its discovery document is read at startup |
| `SPRING_SECURITY_SAML2_RELYINGPARTY_REGISTRATION_<ID>_ASSERTINGPARTY_METADATA_URI` | A SAML asserting party by its metadata (read at startup). Or `_ASSERTINGPARTY_ENTITY_ID`, `_ASSERTINGPARTY_SINGLESIGNON_URL` and `_ASSERTINGPARTY_VERIFICATION_CREDENTIALS_0_CERTIFICATE_LOCATION=file:/path/idp.crt` |
| `SPRING_SECURITY_SAML2_RELYINGPARTY_REGISTRATION_<ID>_ASSERTINGPARTY_SINGLESIGNON_BINDING` | `REDIRECT` (required when the metadata lists POST first) |
| `SPRING_SECURITY_SAML2_RELYINGPARTY_REGISTRATION_<ID>_SIGNING_CREDENTIALS_0_PRIVATE_KEY_LOCATION`, `_CERTIFICATE_LOCATION` | Optional: sign the requests (`_ASSERTINGPARTY_SINGLESIGNON_SIGN_REQUEST=true`) |
| `ENS_SSO_ROLES_PMO_0`, `_1`, ...; `ENS_SSO_ROLES_TRUSTED_AGENT_n`; `ENS_SSO_ROLES_HELP_DESK_n` | The groups (exact values, one per variable: a DN with commas is one group) that grant each role |
| `ENS_SSO_OIDC_USER_ID_CLAIM`, `ENS_SSO_OIDC_GROUPS_CLAIM` | `preferred_username`, `groups`: the claims with the Authentication Manager user ID and the groups |
| `ENS_SSO_SAML_USER_ID_ATTRIBUTE`, `ENS_SSO_SAML_GROUPS_ATTRIBUTE` | Empty (the NameID), `groups` |
| `ENS_SSO_LABELS_<ID>` | The button's label (default: the client name, or the id) |
| `ENS_SSO_REQUIRE_FOR_ADMIN_ROLES` | `false`. `true`: administrative roles only through single sign-on; a passcode sign-in alone gets the user role only |
| `ENS_SSO_PENDING_TIMEOUT`, `ENS_SSO_CLOCK_SKEW` | `5m`, `60s` |

Addresses for the identity provider, where `<id>` is the registration id:

| | |
|---|---|
| OIDC redirect URI | `<APPLICATION_DNS_NAME>/login/oauth2/code/<id>` |
| SAML service provider metadata (also its entity id) | `<APPLICATION_DNS_NAME>/saml2/service-provider-metadata/<id>` |
| SAML Assertion Consumer Service | `<APPLICATION_DNS_NAME>/login/saml2/sso/<id>` (Spring Boot's `_ENTITY_ID` and `_ACS_LOCATION` override them) |

### Helm

The chart sets all of these from `sso.*`, and `APPLICATION_DNS_NAME` from `publicUrl` or the ingress
or route host; it refuses single sign-on without one.

```yaml
sso:
  oidc:
    - id: agency
      clientName: Agency sign-in       # the button: "Sign in with Agency sign-in"
      issuerUri: https://login.example.com/realms/agency
      clientId: rsa-portal
      existingSecret: rsa-portal-oidc  # or clientSecret
      scopes: [openid, profile]
  saml:
    - id: adfs
      label: Agency ADFS
      metadataUri: https://adfs.example.com/FederationMetadata/2007-06/FederationMetadata.xml
  roles:
    pmo: ["CN=RSA PMO,OU=Groups,DC=example,DC=com"]
    trustedAgent: []
    helpDesk: []
  requireForAdminRoles: false
```

## Identity providers

Keycloak, Microsoft Entra ID, Okta and ADFS work with these settings.

- **Entra ID** sends group object ids in `groups`: map those.
- **ADFS** needs a claim rule that sends the groups and the user ID (for example `sAMAccountName` as
  NameID).
- The user ID the provider sends must be the operator's **Authentication Manager user ID**, because
  the passcode that follows must belong to the same user.

## Audit

- `auth.sso-verified` when the identity provider's answer is accepted (user ID, provider, mapped
  roles).
- A failed `auth.login` with `details.method` `oidc` or `saml` and the reason (`invalid_id_token`,
  `invalid_signature`, `invalid_in_response_to`, `no_mapped_role`, ...) when it is not.
- `auth.login` with `details.method` `oidc+securid` or `saml+securid` and `details.idp` when the
  sign-in completes, or a failure with `sso_user_mismatch` or `sso_expired` (an alert).

## Upgrading

Single sign-on is new and off until an identity provider is configured. Flyway adds the
`SsoSamlRequest` table (`V6`) with the release's other migrations.
