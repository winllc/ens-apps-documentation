# Single sign-on

People can sign in to the syncd console through your organisation's identity provider, by
**OpenID Connect** (the authorization code flow with PKCE) or, from syncd 0.5.0, by **SAML 2.0**,
and from 0.5.0 with an **X.509 client certificate** (a CAC or PIV card). Each is off until it is
configured: OpenID Connect with `CONSOLE_OIDC_ISSUER`, SAML with a provider's metadata
(`CONSOLE_SAML_IDP_METADATA_FILE` or `_URL`), certificates with `ENS_X509_ENABLED=true`. They can
be used together, and local accounts keep working alongside them; an admin can then limit local
sign-in to [break-glass accounts](#break-glass-accounts). The sign-in page shows a "Sign in with
&lt;label&gt;" button for each method that is configured.

This page describes the code at syncd 0.5.0 (commit `a9d17c6`). Single sign-on is for people in a
browser: other systems call the API with [API tokens](#api-tokens-and-single-sign-on).

!!! warning "What SAML and certificate sign-in were tested with"
    SAML was tested against **Keycloak only** (26.4.2) and against responses signed by an
    independent implementation; not against AD FS, Entra ID, Okta, Shibboleth or a government
    provider. Certificate sign-in was tested with **test PKIs only**: no real CAC or PIV card, no
    card middleware, no DoD PKI, and no nginx in front. A second security review of the XML,
    OCSP and CRL code is advised, and the Keycloak browser test was not run again after scoped
    roles were added. Try a first deployment with your own provider and a real card before you
    rely on it.

With the [notice-and-consent banner](security.md#sign-in-baseline) on, every method on this page
refuses to start (403, "acknowledge the notice on the sign-in page before signing in") until the
browser has acknowledged the notice; the sign-in page does that when the person presses **I
acknowledge and consent**.

## How a sign-in goes

The rest of this section, and the next five, are about OpenID Connect; SAML and certificates
have sections of their own below ([SAML 2.0](#saml-20),
[Certificate sign-in](#certificate-sign-in-cacpiv)).

```mermaid
sequenceDiagram
    participant B as Browser
    participant C as Console
    participant P as Identity provider

    B->>C: GET /api/v1/auth/oidc/login?next=/engines/1
    C->>P: discovery and keys (cached for an hour)
    C->>C: store state, nonce, PKCE verifier (10 minutes)
    C-->>B: 303 to the authorization endpoint<br/>Set-Cookie syncd_oidc=state
    B->>P: sign in (MFA, as the provider requires)
    P-->>B: 303 to /api/v1/auth/oidc/callback?code&state
    B->>C: callback, with the syncd_oidc cookie
    C->>C: state matches the cookie and an unused request?
    C->>P: token endpoint: code + PKCE verifier (client secret, HTTP Basic)
    P-->>C: ID token
    C->>C: check signature, issuer, audience, expiry, nonce
    C->>C: roles from the role claim; find or create the account
    C-->>B: 303 to /engines/1<br/>Set-Cookie syncd_session
```

1. The console sends the browser to the provider with `response_type=code`, the configured
   scopes, a random `state` and `nonce`, and an S256 PKCE challenge. The request's state,
   nonce, verifier and destination are kept in the database for 10 minutes and used once.
2. A short-lived cookie (`syncd_oidc`, `HttpOnly`, `SameSite=Lax`, path `/api/v1/auth/oidc`,
   10 minutes) ties the sign-in to the browser that started it. A callback that comes back to
   another browser is refused.
3. The console exchanges the code at the token endpoint, authenticating with the client id and
   secret by HTTP Basic (`client_secret_basic`), and checks the ID token.
4. It reads the role assignments and the username from the ID token's claims, finds or creates
   the account, starts a session and redirects to where the user was going: a path within the
   console only (another site, or an `/api/` path, becomes `/`).

The console reads claims from the **ID token only**. It does not call the userinfo endpoint or
use the access token, so the role claim must be in the ID token.

## Who a sign-in signs in

- **The account is bound to the provider's subject**: `<issuer>|<sub>`. The first sign-in
  creates the account. It has no console password, and its role assignments are set from the
  provider at every sign-in: what the role map gives for the groups in that sign-in's token, and
  nothing kept from before.
- **The username** is the `CONSOLE_OIDC_USERNAME_CLAIM` claim (default `preferred_username`),
  else `email`, else `sub`. It is fixed when the account is created: a later change at the
  provider does not rename the account, because the subject identifies it.
- **The display name** comes from the `name` claim, refreshed at each sign-in.
- **A local account with the same username is never taken over.** The sign-in is refused
  ("a console account named ... already exists and is not linked to this identity"). The API
  has no way to rename or delete an account, so either pick a username claim that does not
  collide (`email`, for example) or keep such names apart from the start.
- **A disabled account stays out**, whatever the provider says.
- Accounts that sign in through single sign-on (OpenID Connect or SAML) or a certificate cannot
  change a password in the console or be marked break-glass, and an admin cannot reset their
  password on the **Users** page. The lockout and password settings do not apply to them.
  `syncd-console admin reset-password` on the host refuses them too, since a password would let
  the account sign in without the provider (and its MFA). With `--allow-single-sign-on` it gives
  one anyway, for recovery while the provider is down; the audit log records that it did. Before
  0.3.0 the command line gave such an account a password without asking.

## What is checked

- **At startup** (the console refuses to start otherwise): the issuer is `https://`, or
  `http://` to a loopback address; the client id and secret are set; `CONSOLE_PUBLIC_URL` is set
  and `https://` (or loopback); `CONSOLE_OIDC_DEFAULT_ROLE` and every entry of
  `CONSOLE_OIDC_ROLE_MAP` read (a real role, and a scope that is a name); `CONSOLE_OIDC_CA_FILE`
  reads.
- **At the first sign-in**, and every hour after: the discovery document at
  `<issuer>/.well-known/openid-configuration` must name the same issuer, and its authorization,
  token and key (`jwks_uri`) endpoints must be `https://` (or loopback). A token signed with a
  key the console has not seen makes it fetch the keys again, so key rotation needs nothing.
- **The ID token**:
    - signed with one of the provider's published keys, by an asymmetric algorithm: RS256,
      RS384, RS512, PS256, PS384, PS512, ES256, ES384 or EdDSA. HMAC (`HS*`) and `none` are
      refused;
    - `iss` is the discovered issuer, `aud` contains the client id, `exp` has not passed (60
      seconds of clock skew allowed), and `sub` is present;
    - `nonce` is the one this sign-in sent.
- **The roles**: the role claim must match an entry of the role map, or a default role must be
  set. Otherwise the sign-in is refused ("has no console role").
- Connections to the provider use TLS through the console's crypto provider (FIPS mode applies),
  trusting the Mozilla root set plus `CONSOLE_OIDC_CA_FILE`. They time out after 10 seconds and
  follow no redirects.

A refusal sends the browser back to the sign-in page with the reason (`/login?sso_error=...`);
internal errors say only "single sign-on failed on the console; see its log".

## Signing out

**Sign out** ends the console session only. The console does not call the provider's
end-session endpoint, and has no SAML single logout, so the next "Sign in with ..." may go
straight through on the provider's own session.

A change at the provider (a user removed from a group) takes effect at the user's next sign-in.
A session that is already open keeps its role assignments until it ends
(`CONSOLE_SESSION_IDLE_MINUTES`, default 30, or `CONSOLE_SESSION_MAX_HOURS`, default 12;
`ENS_SESSION_ADMIN_IDLE_TIMEOUT` can shorten the first for console administrators). To cut
someone off at once, disable the account in the console (**Users**).

## Endpoints to register with the provider

For OpenID Connect (for SAML, see [SAML 2.0](#saml-20)):

| What | Value |
|---|---|
| Client type | Confidential web application, authorization code flow |
| Redirect (callback) URI | `<CONSOLE_PUBLIC_URL>/api/v1/auth/oidc/callback`, for example `https://syncd.example.com/api/v1/auth/oidc/callback` |
| Client authentication | Client secret, sent by HTTP Basic (`client_secret_basic`) |
| PKCE | S256 (the console always sends it) |
| Scopes | `openid profile email`, plus what your provider needs to put the role claim in the ID token |
| Front-channel or back-channel logout URI | None: the console has none |

The console must be reached over HTTPS for its `Secure` session cookie to be kept: its own
(`CONSOLE_TLS_CERT` and `CONSOLE_TLS_KEY`), or a TLS proxy in front of it. `CONSOLE_PUBLIC_URL`
is the address users reach, so with the console's own HTTPS on a port other than 443 it includes
the port: `CONSOLE_PUBLIC_URL=https://syncd.example.com:8443`, and the redirect URI is then
`https://syncd.example.com:8443/api/v1/auth/oidc/callback`. See [Installation](install.md) and
[Security](security.md#https-for-the-console).

## Settings

The OpenID Connect settings. In `/etc/syncd/console.env`, then
`sudo systemctl restart syncd-console`. Each also has a
command-line flag (`syncd-console --help`).

| Setting | Default | Meaning |
|---|---|---|
| `CONSOLE_OIDC_ISSUER` | unset (single sign-on off) | The issuer URL. HTTPS unless on loopback. A trailing `/` is ignored |
| `CONSOLE_OIDC_CLIENT_ID` | | The client id. Required with an issuer; also the expected audience |
| `CONSOLE_OIDC_CLIENT_SECRET` | | The client secret |
| `CONSOLE_OIDC_CLIENT_SECRET_FILE` | | A file holding the client secret instead (a trailing newline is dropped). The secret variable wins when both are set |
| `CONSOLE_PUBLIC_URL` | | The console's address as users reach it, for example `https://syncd.example.com`, or `https://syncd.example.com:8443` for the console's own HTTPS on that port. Required for single sign-on: the redirect URI is built from it |
| `CONSOLE_OIDC_SCOPES` | `openid profile email` | Scopes requested, space-separated |
| `CONSOLE_OIDC_ROLE_CLAIM` | `groups` | The ID token claim with the user's groups or roles: a string or an array of strings |
| `CONSOLE_OIDC_ROLE_MAP` | empty | Claim values to console roles, each with a scope if wanted: `syncd-admins=admin,syncd-ops=operator@prod`. See [Roles](#roles) |
| `CONSOLE_OIDC_DEFAULT_ROLE` | unset | The role for users no entry matches, with a scope if wanted (`viewer@test`). Unset: they are refused |
| `CONSOLE_OIDC_USERNAME_CLAIM` | `preferred_username` | The claim used as the console username; `email`, then `sub`, when it is missing |
| `CONSOLE_OIDC_LABEL` | `single sign-on` | The button says "Sign in with &lt;label&gt;" |
| `CONSOLE_OIDC_CA_FILE` | | PEM CA certificates for a provider whose certificate is not from a public CA |

### On Kubernetes

From chart 0.4.0 the Helm chart has single sign-on values of its own, under `console.oidc`. The
client secret comes from a Secret of your own, mounted into the console as a file
(`CONSOLE_OIDC_CLIENT_SECRET_FILE`), never from the chart's values:

```sh
kubectl create secret generic syncd-oidc -n syncd --from-literal=client-secret=...
```

```yaml
console:
  publicUrl: https://syncd.example.com      # https://<console.ingress.host> when empty and the ingress is on
  oidc:
    issuer: https://login.example.com/realms/staff
    clientId: syncd-console
    existingSecret: syncd-oidc
    existingSecretKey: client-secret        # the default
    roleClaim: groups
    roleMap: syncd-admins=admin,syncd-operators=operator
    defaultRole: ""                         # unset: users no entry matches are refused
    ca:                                     # for a provider without a public certificate
      configMap: ""                         # or secret: ...
      key: ca.crt
```

| Value | Setting |
|---|---|
| `console.publicUrl` | `CONSOLE_PUBLIC_URL`; `https://<console.ingress.host>` when empty and the ingress is enabled |
| `console.oidc.issuer` | `CONSOLE_OIDC_ISSUER`. Single sign-on is off while it is empty |
| `console.oidc.clientId` | `CONSOLE_OIDC_CLIENT_ID` |
| `console.oidc.existingSecret`, `.existingSecretKey` | A Secret and key holding the client secret, mounted as `CONSOLE_OIDC_CLIENT_SECRET_FILE` |
| `console.oidc.roleClaim`, `.roleMap`, `.defaultRole` | `CONSOLE_OIDC_ROLE_CLAIM`, `CONSOLE_OIDC_ROLE_MAP`, `CONSOLE_OIDC_DEFAULT_ROLE` |
| `console.oidc.ca.configMap` or `.ca.secret`, with `.ca.key` (`ca.crt`) | A PEM CA bundle from a ConfigMap or a Secret, mounted as `CONSOLE_OIDC_CA_FILE` |

- The chart refuses to render an `issuer` without `clientId`, `existingSecret`, or a public URL
  (`console.publicUrl`, or the ingress's host).
- Scopes, the username claim and the button's label have no values of their own: set
  `CONSOLE_OIDC_SCOPES`, `CONSOLE_OIDC_USERNAME_CLAIM` and `CONSOLE_OIDC_LABEL` in `console.env`.
- Register the redirect URI `<console.publicUrl>/api/v1/auth/oidc/callback` with the provider.
- With the console's own HTTPS (`console.tls.existingSecret`) behind the ingress, the public URL
  is still the ingress's address.
- Set `console.trustedProxies` to the ingress controller's addresses.

Releases of the chart before 0.4.0 had no such values: the settings went in `console.env`, with
the client secret as `CONSOLE_OIDC_CLIENT_SECRET` from a `secretKeyRef`. That still works, but the
chart could not mount a CA file then; `console.oidc.ca` (or `console.extraVolumes`) now can.

The chart has no values of its own for SAML, certificate sign-in, the consent banner or the
sign-in policy: set `CONSOLE_SAML_*`, `ENS_X509_*` and the `ENS_*` settings in `console.env`, and
mount their files (the provider's metadata, the allowed issuers, the notice's text) with
`console.extraVolumes` and `console.extraVolumeMounts`. Certificate sign-in in direct mode needs
the console's own HTTPS (`console.tls.existingSecret`) and a path on which the browser's TLS
reaches the console (TLS passthrough, or a `LoadBalancer` Service); behind an ingress controller
that terminates TLS, use proxy mode, with `ENS_X509_TRUSTED_PROXIES` listing the controller's pod
addresses. Neither has been tried on a cluster.

## Roles

The console's roles are `admin`, `designer`, `operator`, `viewer` and `auditor`, and from 0.5.0
each can be given on everything, on one environment or on named engines (see
[Security](security.md#authorization)). The same role map syntax serves OpenID Connect
(`CONSOLE_OIDC_ROLE_MAP`), SAML (`CONSOLE_SAML_ROLE_MAP`) and certificates (`ENS_X509_ROLE_MAP`). How scopes work in the console is in the [Admin guide](admin-guide.md#scopes).

- A role map is a list of `value=role` entries, separated by commas, or by semicolons when a
  value contains commas (group DNs):

    ```sh
    CONSOLE_OIDC_ROLE_MAP=syncd-admins=admin,syncd-designers=designer,syncd-operators=operator,syncd-auditors=auditor
    CONSOLE_OIDC_ROLE_MAP=cn=syncd-ops,ou=groups,dc=example,dc=com=operator;cn=staff,dc=example,dc=com=viewer
    ```

    The role is after the last `=`.
- **A role can carry a scope** (from 0.5.0): `operator@prod` on the environment `prod`,
  `designer@engine:hr-1` on the engine `hr-1` (between commas, `hr-team=designer@engine:hr-1,hr-2`
  names two engines), and `admin` or `admin@*` on everything:

    ```sh
    CONSOLE_OIDC_ROLE_MAP=syncd-admins=admin,syncd-prod-ops=operator@prod,syncd-test-ops=operator@test,hr-designers=designer@engine:hr-1
    ```

- **Every entry whose value the user's claim contains adds its role** (from 0.5.0). Someone in
  `syncd-ops` and `syncd-auditors` holds both roles. **In 0.4.1 the first match won**: a map that
  relied on its order to hold someone to the first entry's role must be rewritten so that only
  that entry matches them, or the person gets more at their next sign-in.
- Values are compared exactly (case matters) for OpenID Connect and SAML; certificate role maps
  ignore case.
- With no match, the default role (`CONSOLE_OIDC_DEFAULT_ROLE`, `CONSOLE_SAML_DEFAULT_ROLE`,
  `ENS_X509_DEFAULT_ROLE`, each with a scope if wanted) applies; without it the sign-in is
  refused and audited.
- An entry that cannot be read (an unknown role, a scope that is not a name) stops the console at
  startup.
- The assignments are written to the account at every sign-in, so assignments an admin sets in
  the console for such an account last only until its next sign-in. Manage them at the provider.
- An HA pair is two registrations (`<name>-0`, `<name>-1`): scope it by environment, or name both
  engines. Views are not a scope.

### Moving from 0.4.1

Review every role map before the upgrade. In 0.4.1, `syncd-admins=admin,staff=viewer` gave an
admin who is also in `staff` the admin role only; in 0.5.0 they get both, which changes nothing
since admin includes viewer. But `contractors=viewer,staff=operator`, written so that contractors
in `staff` stayed viewers, now makes them operators too: give contractors a group of their own
that `staff` does not include, or scope the operator entry.

## Break-glass accounts

Once single sign-on (OpenID Connect or SAML) or certificate sign-in works, an admin can limit
local sign-in to break-glass accounts (**Users** → Local sign-in, or `PUT /api/v1/settings/sign-in`).
Everyone else then signs in through the provider or with a certificate; the sign-in page keeps the
local form behind "Break-glass sign-in".

- Limiting needs one of those methods configured and at least one enabled console administrator
  (`admin` on everything) with a local password marked break-glass. While it is limited, the last
  such admin cannot be unmarked, demoted, limited to a scope or disabled.
- Switching signs out the local accounts that can no longer sign in. Their attempts get the usual
  refusal; the audit log records why.
- Keep the break-glass password somewhere safe: it is the way in when the provider is down.
  `syncd-console admin reset-password --user NAME` on the host recovers it.

## Provider walkthroughs

Replace `syncd.example.com` with your console's address. Each ends with the same settings in
`console.env`:

```sh
CONSOLE_PUBLIC_URL=https://syncd.example.com
CONSOLE_OIDC_ISSUER=<issuer>
CONSOLE_OIDC_CLIENT_ID=<client id>
CONSOLE_OIDC_CLIENT_SECRET_FILE=/etc/syncd/oidc-client-secret
CONSOLE_OIDC_ROLE_MAP=<value>=admin,<value>=operator
CONSOLE_OIDC_LABEL=<provider name>
```

Make the secret file readable by the console only:
`sudo install -m 0640 -o root -g syncd-console /dev/stdin /etc/syncd/oidc-client-secret`, paste the
secret, then press Ctrl-D.

### Microsoft Entra ID

1. **App registrations** → New registration. Platform **Web**, redirect URI
   `https://syncd.example.com/api/v1/auth/oidc/callback`.
2. **Certificates & secrets** → New client secret. Keep the value.
3. Roles, either:
    - **App roles** (recommended): define roles such as `syncd.admin` and `syncd.operator`, assign
      users or groups to them in Enterprise applications, and set
      `CONSOLE_OIDC_ROLE_CLAIM=roles` with `CONSOLE_OIDC_ROLE_MAP=syncd.admin=admin,syncd.operator=operator`;
    - or **Token configuration** → Add groups claim (security groups, in the ID token). Entra sends
      group object IDs, so map those: `CONSOLE_OIDC_ROLE_MAP=<object id>=admin,...`. A user in
      very many groups gets a reference instead of the list, and is then refused: prefer app roles.
4. Issuer: `https://login.microsoftonline.com/<tenant id>/v2.0`. Client id: the application
   (client) id.
5. Entra's `preferred_username` is the user's sign-in name. Restrict who may use the application
   with **Assignment required** on the enterprise application.

### Okta

1. **Applications** → Create App Integration → OIDC, **Web Application**. Grant type
   Authorization Code. Sign-in redirect URI
   `https://syncd.example.com/api/v1/auth/oidc/callback`. Client authentication: client secret.
2. Assign the people or groups who may sign in.
3. Put a `groups` claim in the ID token:
    - with the org authorization server (issuer `https://<your org>.okta.com`), set the app's
      groups claim filter (for example, name `groups`, groups that start with `syncd-`);
    - with a custom authorization server (issuer `https://<your org>.okta.com/oauth2/default`),
      add a `groups` claim to the ID token in its claims, filtered the same way.
4. `CONSOLE_OIDC_ROLE_MAP=syncd-admins=admin,syncd-operators=operator`.

### Keycloak

CI signs in through a real Keycloak (`console/web/e2e/sso.sh` in the source), checking a deep link
through the round trip, the session cookie after the cross-site redirect, a second sign-in through
Keycloak's own session, and the refusal of a user in no mapped group; from 0.5.0 it also signs in
through Keycloak by SAML. Keycloak is the only provider the console is tested against.

1. In your realm, **Clients** → Create client. Type OpenID Connect, client id `syncd-console`.
   Turn on **Client authentication**, keep **Standard flow**. Valid redirect URI
   `https://syncd.example.com/api/v1/auth/oidc/callback`. Optionally set PKCE method S256 in the
   client's advanced settings.
2. **Credentials** tab: copy the client secret.
3. Groups in the ID token: **Client scopes** → `syncd-console-dedicated` → Add mapper → **Group
   Membership**, token claim name `groups`, **Add to ID token** on. Turn **Full group path** off to
   get `syncd-admins` rather than `/syncd-admins`, or map the full paths.
4. Issuer: `https://keycloak.example.com/realms/<realm>`.
5. `CONSOLE_OIDC_ROLE_MAP=syncd-admins=admin,syncd-operators=operator`.

## SAML 2.0

From 0.5.0 the console is a SAML 2.0 service provider (the Web Browser SSO profile), beside
OpenID Connect or instead of it, for identity providers that offer nothing else (AD FS, many
government ones). It sends an `AuthnRequest` by redirect and takes the provider's signed
`Response` at its assertion consumer service. Every setting is in the [Configuration guide](configuration.md#saml-sign-in).

1. Register the console at the provider. Its metadata is at
   `<CONSOLE_PUBLIC_URL>/api/v1/auth/saml/metadata` once the settings below are in; or by hand:
   entityID `<CONSOLE_PUBLIC_URL>/api/v1/auth/saml/metadata`, assertion consumer service
   `<CONSOLE_PUBLIC_URL>/api/v1/auth/saml/acs` with the HTTP-POST binding. The provider must
   **sign** the assertion or the response (SHA-256 or better), must not encrypt the assertion,
   and must not require signed requests.
2. Have it release an attribute with the user's groups (and, if you like, one for the username
   and one for the name).
3. Set, in `console.env`, and restart the console:

| Setting | Default | Meaning |
|---|---|---|
| `CONSOLE_SAML_IDP_METADATA_FILE` | | The provider's metadata, in a file. Turns SAML sign-in on. Read at startup: restart the console when the provider's signing certificate changes |
| `CONSOLE_SAML_IDP_METADATA_URL` | | Or its URL: fetched at the first sign-in, every hour, and when a response verifies with none of the known certificates. HTTPS unless on loopback |
| `CONSOLE_SAML_IDP_ENTITY_ID` | | The provider's entityID, when the metadata describes several (a federation's file) |
| `CONSOLE_PUBLIC_URL` | | Required, and HTTPS (or loopback): the SAML cookie is `Secure; SameSite=None` |
| `CONSOLE_SAML_ENTITY_ID` | the metadata address | The console's entityID |
| `CONSOLE_SAML_ROLE_ATTRIBUTE` | `groups` | The attribute, by its `Name`, with the user's groups |
| `CONSOLE_SAML_ROLE_MAP` | | Attribute values to roles, as in [Roles](#roles) |
| `CONSOLE_SAML_DEFAULT_ROLE` | unset | The role when nothing matches, with a scope if wanted. Unset: such users are refused |
| `CONSOLE_SAML_USERNAME_ATTRIBUTE` | the `NameID` | The attribute used as the console username |
| `CONSOLE_SAML_DISPLAY_NAME_ATTRIBUTE` | `displayName` | The attribute with the person's name |
| `CONSOLE_SAML_LABEL` | | The button says "Sign in with &lt;label&gt;" |
| `CONSOLE_SAML_CA_FILE` | | PEM CA certificates for the metadata URL, when it has no public certificate |
| `CONSOLE_SAML_CLOCK_SKEW_SECONDS` | `60` | How far the provider's clock may be off, at most 600 |

- Accounts work as with OpenID Connect: created at the first sign-in, bound to the provider and
  the `NameID` (not the username), no console password, role assignments read from the assertion
  again at every sign-in, and a local account's name is never taken over.
- Attributes are matched by `Name`, exactly; `FriendlyName` is not used.
- The provider's certificates are trusted because they are in the metadata you configured: their
  own expiry and issuer are not checked (the usual SAML model). A metadata file is as trusted as
  the console's settings; a metadata URL is as trusted as its TLS certificate (metadata
  signatures are not checked).
- **Not supported**, each refused with a message that says so: encrypted assertions, signed
  requests, responses the console did not ask for (sign-in started at the provider's portal), and
  single logout. RSA-PSS signatures are not accepted.

### What a SAML response must pass

XML signatures are where SAML service providers have been broken, so what is accepted is narrow.
Each refusal has a test with a fixed response.

- **The console's own XML reader**, strict: no DOCTYPE (so no entity expansion and no external
  entities), no processing instructions, UTF-8 only, limits on depth and size. The same tree is
  what the signature is checked over and what the account is read from. There is no C XML
  library; the signature arithmetic is aws-lc-rs's (the FIPS module in a FIPS build).
- **Signed, always**: the response or its assertion must carry a signature that verifies with a
  certificate from the provider's metadata; with both signed, both must verify. The certificate
  inside the message (`KeyInfo`) is never used.
- **Signature wrapping**: a signature covers only the element that carries it and that it
  references by ID. The response must hold exactly one assertion, as its own child; a second
  assertion anywhere, an ID used twice, or a signature in any other place refuses the response.
  Only the verified assertion is read.
- **Algorithms**: RSA (2048 bits or more) with SHA-256, SHA-384 or SHA-512, and ECDSA
  P-256/SHA-256 and P-384/SHA-384. SHA-1, DSA and HMAC are refused. Only the enveloped-signature
  transform and exclusive canonicalization are accepted.
- **Comment injection**: a signed element that holds an XML comment is refused.
- **Audience and replay**: the assertion must be from the configured provider, for this console
  (audience), delivered to it (recipient, destination), within its validity, and in answer to
  the request this browser started: the request's ID is kept for 10 minutes and used once, and a
  cookie ties the answer to the browser. Each accepted assertion's ID is remembered until it
  expires, so an assertion is accepted once, across console instances.

## Certificate sign-in (CAC/PIV)

From 0.5.0 people can sign in with an X.509 client certificate: a CAC or PIV card through the
browser, or any certificate from an issuer you allow. The sign-in page gets a **Sign in with a
certificate (CAC/PIV)** button. The certificate reaches the console in one of two ways:

- **Direct** (`ENS_X509_MODE=direct`, the default): the console serves HTTPS itself
  (`CONSOLE_TLS_CERT`) and asks each browser for a certificate when it connects. A browser that
  has none, or whose user declines, still reaches the console and can sign in another way.
  Browsers ask for the card's PIN once per connection, at connect time.
- **Proxy** (`ENS_X509_MODE=proxy`): a TLS-terminating proxy asks for the certificate and passes
  it in a request header. The console reads the header **only** on connections whose own address
  is in `ENS_X509_TRUSTED_PROXIES`; from any other address it is ignored. The proxy must set the
  header on every request, so that one a client sends never arrives. The shipped nginx
  configuration has the three lines, commented out (see
  [Security](security.md#behind-a-reverse-proxy)).

Either way the console checks the certificate itself, at the sign-in endpoint:

1. **The path**: it chains to one of the allowed issuers (`ENS_X509_CA_FILE`), every certificate
   on the path is within its validity, and the certificate is for client authentication
   (rustls-webpki, with the process crypto provider's algorithms; no SHA-1).
2. **Revocation**: every certificate on the path below the allowed issuer is checked with OCSP
   and, when that gives no answer, with the issuer's CRL (`ENS_X509_REVOCATION`, `ocsp,crl` by
   default; `ocsp`, `crl` or `none` otherwise). A certificate whose status **cannot be learned**
   (no responder or CRL named, the responder down, an answer that does not verify) is
   **refused**: the check fails closed, unless `ENS_X509_REVOCATION_FAIL_OPEN=true`. A
   certificate known to be revoked is refused either way.
3. **The account**: a name from the certificate becomes the console username, and the role map
   gives the role assignments. The account is created at the first sign-in like a single sign-on
   account: no console password, the roles read from the certificate at every sign-in, and a local
   account's name is never taken over.

| Setting | Default | Meaning |
|---|---|---|
| `ENS_X509_ENABLED` | `false` | Offer certificate sign-in |
| `ENS_X509_MODE` | `direct` | `direct` or `proxy`, as above. Direct needs `CONSOLE_TLS_CERT` |
| `ENS_X509_CA_FILE` | | The allowed issuers, PEM: the issuing CAs (the DoD ID CAs, say) to allow exactly those, or a root for everything under it. Required. Trusted as given (their own revocation is not checked); intermediates the browser does not send must be in it |
| `ENS_X509_TRUSTED_PROXIES` | | Proxy mode: the proxies whose header is believed (addresses or CIDR ranges). Required in proxy mode |
| `ENS_X509_CERT_HEADER` | `X-SSL-Client-Cert` | Proxy mode: the header with the certificate (URL-encoded PEM, nginx's `$ssl_client_escaped_cert`; PEM with spaces for line ends; or base64 DER) |
| `ENS_X509_VERIFY_HEADER` | | Proxy mode: a header that must say `SUCCESS`, for proxies that verify the certificate too |
| `ENS_X509_USERNAME_RULE` | `upn` | `upn` (the user principal name of a PIV authentication certificate), `edipi` (the ten-digit DoD ID number), `cn` or `email` |
| `ENS_X509_ROLE_MAP` | | Certificates to roles, case ignored. An entry's value is the username, `OU=<unit>`, `O=<organization>` or `issuer=<the issuer's common name>`: `OU=USAF=operator@prod,1234567890=admin` |
| `ENS_X509_DEFAULT_ROLE` | | The role when nothing matches, with a scope if wanted. Unset: such certificates are refused |
| `ENS_X509_REVOCATION` | `ocsp,crl` | What is asked, in order |
| `ENS_X509_REVOCATION_FAIL_OPEN` | `false` | Accept a certificate whose status cannot be learned. Leave it `false` |
| `ENS_X509_OCSP_URL` | | A responder to ask instead of the one each certificate names |
| `ENS_X509_OCSP_NONCE` | `send` | `send` a nonce and accept an answer without one, `require` one, or `off` |
| `ENS_X509_CRL_URL` | | A CRL address to use instead of the certificates' distribution points |
| `ENS_X509_CRL_CACHE` | `1h` | The longest a fetched CRL is used, never past its next update |
| `ENS_X509_CRL_MAX_BYTES` | `67108864` | The largest CRL read |
| `ENS_X509_REVOCATION_TIMEOUT` | `10s` | How long a responder or CRL server may take |
| `ENS_X509_LABEL` | `a certificate (CAC/PIV)` | The button says "Sign in with &lt;label&gt;" |

- **OCSP**: the response must be signed by the certificate's issuer or by a responder certificate
  that issuer signed for OCSP signing, with SHA-256 or better, be about this certificate, be
  current, and carry the nonce that was sent if it carries one. The delegated responder's own
  revocation is not checked.
- **CRL**: signed by the certificate's issuer (which must be allowed to sign CRLs), current,
  complete, and for the certificate's own distribution point. Delta, indirect and partial CRLs are
  not supported.
- The console must reach the responders and CRL distribution points (plain HTTP, by design: the
  answers are signed), or the ones you configure. On a network that cannot, mirror the CRLs and
  set `ENS_X509_CRL_URL`, or every sign-in fails closed.
- A refusal tells the person why (expired, revoked, not from an allowed issuer). The per-address
  sign-in limit applies; the lockout and password settings do not.

The settings are also listed in the [Configuration guide](configuration.md#certificate-sign-in-cacpiv), and the sign-in policy (consent banner, lockout, passwords) in the [Admin guide](admin-guide.md#sign-in-policy).

## API tokens and single sign-on

[API tokens](api-reference.md#api-tokens) are for other systems, not people, and do not go
through the identity provider.

- **An account that signs in through single sign-on or a certificate can create tokens** when
  its roles allow (an administrator of all the token reaches creates them on **Integrations**).
  The token then acts with no more than that account's role assignments, within its own limit.
- **The creator's assignments are the ones stored on the account**, which each sign-in writes. A
  change at the provider (a user removed from the admins' group) therefore reaches a token only
  when its creator next signs in. To stop a person's tokens at once, disable the account in the
  console (they stop within a minute) or revoke the tokens.
- No API token can sign in through any of these methods or call the sign-in endpoints, and a token
  carries no session. The consent banner does not apply to tokens.
- **Prefer tokens to local accounts for automation.** With local sign-in limited to break-glass
  accounts, a script has no password account to sign in as; a token needs none.

The public self-service password page (`/password`) is not behind single sign-on either: people
prove who they are with their current directory password. See
[Security](security.md#self-service-password-change).

## Multi-factor authentication

The console has no multi-factor authentication of its own for local accounts. Require it at the
identity provider (conditional access, an authentication policy), or from 0.5.0 use certificate
sign-in with a CAC or PIV card (the card and its PIN), and limit local sign-in to break-glass
accounts so that everyone else goes through one of them.

## Audit

Every sign-in is recorded in the audit trail (`ens-audit/1`, category `authentication`):
`auth.oidc.login`, from 0.5.0 `auth.saml.login` and `auth.x509.login`, each as `success` with the
role assignments it gave (`roles`, a list, from 0.5.0; 0.4.1 recorded one `role`: update SIEM
parsers), or `denied` with the reason (the provider refused, the state or request did not match
this browser, the token or response was not valid, no role matched, the account is disabled, a
local account has that name, the certificate was expired, revoked or of an issuer not allowed).
A certificate sign-in also records the certificate's subject, issuer, serial number and SHA-256
fingerprint, and how revocation was checked. With the consent banner on, `auth.consent` is
recorded, and the sign-in it leads to carries the same `consent` id and the session's id. Changes
to who may sign in locally are recorded as `settings.local_sign_in`. See [Security](security.md#audit-trail) and
[Audit logging](../audit-logging.md).
