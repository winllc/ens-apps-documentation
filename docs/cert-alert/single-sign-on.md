# Single sign-on and roles

CertAlert can sign people in through your identity provider, by OpenID Connect (OIDC) or SAML 2.0,
alongside the client certificate and the directory password. This page covers setting that up and
how people get the **user**, **auditor** and **administrator** roles: from configuration, from
directory groups, or from the identity provider's groups.

Both protocols are **off until configured**. With neither on, signing in works exactly as before.
The sign-in page shows one button for each provider that is on.

## Who a single sign-on signs in

The provider names the person by a claim (OIDC) or by the NameID or an attribute (SAML). CertAlert
matches that value to a directory entry the same way a password sign-in finds one:

- as a **uid** (`match: UID`, the default), or as the **DN** of the entry (`match: DN`);
- in the cached directory first, then in the directory itself under
  `cert-alert.security.ldap.user-search-base` (or `user-dn-patterns`).

It is never matched as an e-mail address or a name: an `email` claim is not used, and a uid that
looks like an address must be the entry's real uid. Somebody the directory does not hold is
refused. The provider says who somebody is; the directory says whether CertAlert serves them.

**Which claim to match on.** Prefer one the person cannot change: an immutable identifier the
provider takes from the directory, such as an on-premises `samAccountName`, `uid` or DN synchronised
into the provider, or an `employeeID`. `preferred_username` and similar claims are often editable by
the person or a tenant administrator.

## What is checked

=== "OpenID Connect"

    - The authorization code flow, always with PKCE (S256), with or without a client secret.
    - The ID token's signature against the provider's published keys (`jws-algorithm`, `RS256` by
      default), its issuer (exactly `issuer-uri`), audience (the client id), authorized party,
      expiry and nonce, with `clock-skew` allowed.
    - The provider's discovery document is read when first needed, so a provider that is down does
      not stop CertAlert starting.
    - No access or refresh token is kept.

=== "SAML 2.0"

    - The response or its assertion must be signed by a key in the identity provider's metadata;
      unsigned is refused.
    - The issuer must be that provider; the audience and destination this service provider; the
      conditions in date, with `clock-skew` allowed.
    - Every response must answer (`InResponseTo`) a request CertAlert sent and has not seen answered.
      Requests wait in the database (`saml_authn_request`) for ten minutes and are used once, so a
      replayed or identity-provider-initiated response is refused.
    - The request is bound to the browser that started it by a cookie (`CERTALERT_SAML_BINDING`:
      HttpOnly, Secure, SameSite=None, sent only to the assertion consumer). **SAML therefore needs
      HTTPS.**

Sign-ins and refusals are recorded in the audit trail as `auth.login` with method `oidc` or `saml`,
and the reason for a refusal. The account menu shows **via OIDC** or **via SAML**.

!!! note "No single logout"
    Signing out ends the CertAlert session only. It does not sign the person out of the identity
    provider, and signing out at the provider does not end the CertAlert session.

With the [notice and consent banner](security.md#notice-and-consent-banner) on, a single sign-on is
held at the banner like any other sign-in.

## The public address

`cert-alert.sso.public-url` (`CERT_ALERT_SSO_PUBLIC_URL`, Helm `sso.publicUrl`) is **required** when
either provider is enabled, for example `https://certalert.example.gov`. The OIDC redirect URI, the
SAML entity id (unless set) and the assertion consumer are built from it, never from the request: a
`Host`, `Forwarded` or `X-Forwarded-Host` header a client sends cannot change them. CertAlert refuses
to start with a missing or templated value.

With `SERVER_FORWARD_HEADERS_STRATEGY=framework` (set by the chart behind an ingress), CertAlert
believes only `X-Forwarded-Proto`, `-Port` and `-For`; `Forwarded`, `X-Forwarded-Host` and
`X-Forwarded-Prefix` are ignored. Have the proxy overwrite `X-Forwarded-For` and `X-Forwarded-Proto`
rather than append to a client's.

Addresses to give the provider:

| | OIDC | SAML |
|---|---|---|
| Redirect URI / assertion consumer | `<public-url>/login/oauth2/code/oidc` | `<public-url>/login/saml2/sso/saml` (HTTP-POST) |
| Service provider metadata | | `<public-url>/saml2/service-provider-metadata/saml` |
| Entity id | | `<public-url>/saml2/service-provider-metadata/saml`, unless `entity-id` is set |

## Settings

```yaml
cert-alert:
  sso:
    public-url: https://certalert.example.gov
    oidc:
      enabled: true
      display-name: Sign in with Entra ID
      issuer-uri: https://login.microsoftonline.com/<tenant-id>/v2.0
      client-id: <application (client) id>
      client-secret: <secret>          # empty for a public client: PKCE alone
      scopes: [openid, profile]
      username-claim: preferred_username
      match: UID                       # or DN
      groups-claim: groups
      admin-groups: [5b0f1c2e-1d0e-4c8b-9a77-2f2a6c0d9e11]   # this provider's groups only
      auditor-groups: []
      jws-algorithm: RS256
      clock-skew: 2m
    saml:
      enabled: true
      display-name: Sign in with ADFS
      metadata-location: https://adfs.example.gov/FederationMetadata/2007-06/FederationMetadata.xml
      entity-id: ""                    # empty: <public-url>/saml2/service-provider-metadata/saml
      signing-key-location: file:/etc/cert-alert/saml/signing.key          # optional
      signing-certificate-location: file:/etc/cert-alert/saml/signing.crt
      username-attribute: ""           # empty: the NameID
      match: UID
      groups-attribute: http://schemas.microsoft.com/ws/2008/06/identity/claims/groups
      admin-groups: []
      auditor-groups: [CONTOSO\CertAlert Auditors]
      clock-skew: 2m
```

| Property (`cert-alert.sso.`) | Default | Description |
|---|---|---|
| `public-url` | none | Required with either provider: the address people reach CertAlert at |
| `oidc.enabled`, `saml.enabled` | `false` | Turn each protocol on |
| `oidc.display-name`, `saml.display-name` | `Single sign-on` | The button's text |
| `oidc.issuer-uri`, `oidc.client-id`, `oidc.client-secret` | none | The OIDC client. An empty secret makes a public client (PKCE alone) |
| `oidc.scopes` | `openid`, `profile` | Scopes asked for |
| `oidc.username-claim` | `preferred_username` | The claim matched to the directory |
| `oidc.groups-claim` | empty | The claim listing the person's groups at the provider |
| `oidc.jws-algorithm` | `RS256` | The ID token's signature algorithm |
| `saml.metadata-location` | none | The identity provider's metadata: an `https://` URL or `file:/path` |
| `saml.entity-id` | empty | This service provider's entity id, an absolute URI; empty uses the metadata address above |
| `saml.signing-key-location`, `saml.signing-certificate-location` | none | PEM (PKCS#8) key and certificate, for a provider that requires signed requests |
| `saml.username-attribute` | empty (the NameID) | The attribute matched to the directory |
| `saml.groups-attribute` | empty | The attribute listing the person's groups |
| `*.match` | `UID` | `UID` or `DN`: what the value is matched against |
| `*.admin-groups`, `*.auditor-groups` | empty | This provider's groups that make an administrator or an auditor |
| `*.clock-skew` | `2m` | Tolerated clock difference |

Every setting has an environment variable (`CERT_ALERT_SSO_OIDC_ISSUER_URI`, ...; list items as
`CERT_ALERT_SSO_OIDC_ADMIN_GROUPS_0`, `_1`, ...) in the release package's `env.template`, and a Helm
value under `sso` (`sso.publicUrl`, `sso.oidc.*`, `sso.saml.*`). In Helm the SAML metadata is
`sso.saml.metadataUrl`, or `sso.saml.metadataConfigMap` for a saved copy; the OIDC client secret can
come from `sso.oidc.existingSecret`, and the SAML signing pair from `sso.saml.signingSecret`.

## Provider walkthroughs

### Microsoft Entra ID (OIDC)

1. **App registrations → New registration.** Platform **Web**, redirect URI
   `https://<host>/login/oauth2/code/oidc`. Note the application (client) id and the directory
   (tenant) id; create a client secret.
2. **Token configuration.** Add the optional claim `preferred_username`; to use groups, add the
   groups claim (security groups). Entra ID sends group **object ids**, so list those in
   `admin-groups` / `auditor-groups`.
3. Set `issuer-uri: https://login.microsoftonline.com/<tenant-id>/v2.0`. `preferred_username` is
   the user principal name: use it with `match: UID` only where your directory's uids are the UPNs.
   Otherwise add a claim carrying the on-premises `samAccountName` or DN (directory extension
   attributes, or claims mapping) and name it in `username-claim`.

### Okta (OIDC)

1. **Applications → Create App Integration → OIDC, Web Application.** Sign-in redirect URI
   `https://<host>/login/oauth2/code/oidc`; grant type Authorization Code (PKCE is used
   automatically).
2. `issuer-uri` is the authorization server's issuer, for example
   `https://<org>.okta.com/oauth2/default`.
3. For a uid, add a custom claim to the ID token (**Security → API → Authorization Servers →
   Claims**), for example `uid` = `user.login` or a profile attribute holding the directory uid, and
   set `username-claim: uid`. For groups, add a `groups` claim with a filter and set
   `groups-claim: groups`; Okta sends group names, which go in `admin-groups` / `auditor-groups`.

### ADFS (SAML)

1. **Add Relying Party Trust**, importing `https://<host>/saml2/service-provider-metadata/saml` (or
   enter it by hand: SAML 2.0 WebSSO, the assertion consumer and entity id above).
2. **Claim issuance.** Send the LDAP attribute holding the uid (for example `SAM-Account-Name`) as
   **Name ID** and leave `username-attribute` empty, or as an attribute you name in
   `username-attribute`. For a DN, send `distinguishedName` and set `match: DN`. For groups, send
   *Token-Groups - Qualified by Domain Name* or the group DNs as the attribute in
   `groups-attribute`, and list the ones that grant a role in `admin-groups` / `auditor-groups`.
3. ADFS signs the assertion with its token-signing certificate, which is in its metadata
   (`https://<adfs>/FederationMetadata/2007-06/FederationMetadata.xml`). Where ADFS requires signed
   requests, set `signing-key-location` and `signing-certificate-location`.
4. For an ADFS CertAlert cannot reach, save the metadata and use
   `metadata-location: file:/path/to/metadata.xml` (Helm: `sso.saml.metadataConfigMap`).

## Roles

| Role | May |
|---|---|
| `ROLE_USER` | Read the search tables, the certificate inventory and the cached certificate detail |
| `ROLE_AUDITOR` | Read everything an administrator reads (the Admin page, Integrations, the audit log and its export, the license screen) and change nothing at all |
| `ROLE_ADMIN` | Also run the jobs, change settings, manage API tokens, webhooks and scheduled reports, and reach the management endpoints |

Everyone who signs in gets `ROLE_USER`. The other two come from any of three sources:

| Source | Administrator | Auditor |
|---|---|---|
| Configuration, by uid or entry DN | `cert-alert.security.admin-identifiers` | `cert-alert.security.auditor-identifiers` |
| A directory group, from `memberOf` on the person's entry | `cert-alert.security.groups.admin` | `cert-alert.security.groups.auditor` |
| The identity provider's groups (single sign-on only) | `cert-alert.sso.<provider>.admin-groups` | `cert-alert.sso.<provider>.auditor-groups` |

Somebody who is both an auditor and an administrator is an administrator.

`admin-identifiers` and `auditor-identifiers` are matched against a person's **uid** and the **DN**
of their entry only, never an e-mail address or a name; see
[Security → Roles](security.md#roles) for why. A DN contains commas, so give it its own YAML list
item, or use indexed variables (`CERT_ALERT_SECURITY_AUDITOR_IDENTIFIERS_0`, `_1`, ...).

### The auditor changes nothing

An auditor is refused (403, recorded as `access.denied`) on every request that could change
something: anything but `GET`, `HEAD` and `OPTIONS`, whatever the endpoint. This is one rule ahead of
all the others rather than one per endpoint, so an endpoint added later is covered too. It includes
what an ordinary user may do, such as editing contacts with `contact-editors: AUTHENTICATED` or
marking a notification read.

The only exceptions read or concern the session: the search tables (which `POST` their query),
verifying the audit chain, saved views (an auditor keeps views of their own), signing in and out, and
agreeing to the consent banner. The pages hide what an auditor cannot use, and the account menu reads
**Auditor**.

### Roles from directory groups

```yaml
cert-alert:
  security:
    auditor-identifiers:
      - uid=inspector,ou=people,dc=example,dc=gov
    groups:
      admin:
        - cn=CertAlert Admins,ou=groups,dc=example,dc=gov   # a directory group's DN
      auditor:
        - cn=CertAlert Auditors,ou=groups,dc=example,dc=gov
      directory: true             # read memberOf at sign-in
      member-of-attribute: memberOf
      cache-for: 5m               # a certificate is checked on every request
```

| Property (`cert-alert.security.groups.`) | Default | Description |
|---|---|---|
| `admin`, `auditor` | empty | Group DNs whose members get the role. Only groups named here grant anything |
| `directory` | `true` | Read the person's groups from the directory when they sign in, however they sign in |
| `member-of-attribute` | `memberOf` | The attribute on the person's entry that lists their groups |
| `cache-for` | `5m` | How long a certificate sign-in's groups are remembered |

Group names and DNs are compared without regard to case or spacing after commas. From the
environment, use indexed variables, for example `CERT_ALERT_SECURITY_GROUPS_ADMIN_0`; in Helm,
`security.groups.admin` and `security.groups.auditor`. OpenLDAP's `memberOf` overlay and 389
Directory Server's MemberOf plugin maintain the attribute; a directory without it grants nothing by
group. A group change counts at the person's next sign-in, or within `cache-for` for a client
certificate.

A provider's groups grant a role only through that provider's own `admin-groups` and
`auditor-groups`. They are never matched against `security.groups`, so a group somebody can create at
the provider, named like a directory group's DN, grants nothing.

!!! note "Roles for API tokens"
    An [API token](api-reference.md#api-tokens) counts only the roles the **directory** gives its
    creator (`admin-identifiers`, `auditor-identifiers` or `security.groups`), never an identity
    provider's groups. Give people who create tokens their administrator role in the directory.

## Upgrading

Nothing changes until `cert-alert.sso.*`, `auditor-identifiers` or `security.groups` is set;
certificate and password sign-in work as before. Turning single sign-on on needs
`CERT_ALERT_SSO_PUBLIC_URL`; SAML needs HTTPS.
