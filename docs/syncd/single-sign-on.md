# Single sign-on

People can sign in to the syncd console through your organisation's identity provider, by
**OpenID Connect** (the authorization code flow with PKCE). It is off until
`CONSOLE_OIDC_ISSUER` is set. Local accounts keep working alongside it, and an admin can then
limit local sign-in to [break-glass accounts](#break-glass-accounts). The sign-in page shows a
"Sign in with &lt;label&gt;" button when it is configured.

syncd supports **OpenID Connect only**. SAML 2.0 and client-certificate (X.509) sign-in are not
supported; see [SAML identity providers](#saml-identity-providers). Single sign-on is for people
in a browser: other systems call the API with [API tokens](#api-tokens-and-single-sign-on).

## How a sign-in goes

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
    C->>C: role from the role claim; find or create the account
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
4. It reads the role and the username from the ID token's claims, finds or creates the
   account, starts a session and redirects to where the user was going: a path within the
   console only (another site, or an `/api/` path, becomes `/`).

The console reads claims from the **ID token only**. It does not call the userinfo endpoint or
use the access token, so the role claim must be in the ID token.

## Who a sign-in signs in

- **The account is bound to the provider's subject**: `<issuer>|<sub>`. The first sign-in
  creates the account. It has no console password, and its role is set from the provider at
  every sign-in.
- **The username** is the `CONSOLE_OIDC_USERNAME_CLAIM` claim (default `preferred_username`),
  else `email`, else `sub`. It is fixed when the account is created: a later change at the
  provider does not rename the account, because the subject identifies it.
- **The display name** comes from the `name` claim, refreshed at each sign-in.
- **A local account with the same username is never taken over.** The sign-in is refused
  ("a console account named ... already exists and is not linked to this identity"). The API
  has no way to rename or delete an account, so either pick a username claim that does not
  collide (`email`, for example) or keep such names apart from the start.
- **A disabled account stays out**, whatever the provider says.
- Accounts that sign in through single sign-on cannot change a password in the console or be
  marked break-glass, and an admin cannot reset their password on the **Users** page.
  `syncd-console admin reset-password` on the host refuses them too, since a password would let
  the account sign in without the provider (and its MFA). With `--allow-single-sign-on` it gives
  one anyway, for recovery while the provider is down; the audit log records that it did. Before
  0.3.0 the command line gave such an account a password without asking.

## What is checked

- **At startup** (the console refuses to start otherwise): the issuer is `https://`, or
  `http://` to a loopback address; the client id and secret are set; `CONSOLE_PUBLIC_URL` is set
  and `https://` (or loopback); `CONSOLE_OIDC_DEFAULT_ROLE` and every role in
  `CONSOLE_OIDC_ROLE_MAP` are real roles; `CONSOLE_OIDC_CA_FILE` reads.
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
- **The role**: the role claim must match the role map, or a default role must be set.
  Otherwise the sign-in is refused ("has no console role").
- Connections to the provider use TLS through the console's crypto provider (FIPS mode applies),
  trusting the Mozilla root set plus `CONSOLE_OIDC_CA_FILE`. They time out after 10 seconds and
  follow no redirects.

A refusal sends the browser back to the sign-in page with the reason (`/login?sso_error=...`);
internal errors say only "single sign-on failed on the console; see its log".

## Signing out

**Sign out** ends the console session only. The console does not call the provider's
end-session endpoint, so the next "Sign in with ..." may go straight through on the provider's
own session.

A change at the provider (a user removed from a group) takes effect at the user's next sign-in.
A session that is already open keeps its role until it ends (`CONSOLE_SESSION_IDLE_MINUTES`,
default 30, or `CONSOLE_SESSION_MAX_HOURS`, default 12). To cut someone off at once, disable the
account in the console (**Users**).

## Endpoints to register with the provider

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

In `/etc/syncd/console.env`, then `sudo systemctl restart syncd-console`. Each also has a
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
| `CONSOLE_OIDC_ROLE_MAP` | empty | Claim values to console roles: `syncd-admins=admin,syncd-ops=operator`. See [Roles](#roles) |
| `CONSOLE_OIDC_DEFAULT_ROLE` | unset | The role for users no entry matches. Unset: they are refused |
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

## Roles

The console's roles are `admin`, `designer`, `operator`, `viewer` and `auditor` (see
[Security](security.md#authorization)).

- `CONSOLE_OIDC_ROLE_MAP` is a list of `value=role` entries, separated by commas, or by
  semicolons when a value contains commas (group DNs):

    ```sh
    CONSOLE_OIDC_ROLE_MAP=syncd-admins=admin,syncd-designers=designer,syncd-operators=operator,syncd-auditors=auditor
    CONSOLE_OIDC_ROLE_MAP=cn=syncd-ops,ou=groups,dc=example,dc=com=operator;cn=staff,dc=example,dc=com=viewer
    ```

    The role is after the last `=`.
- **The first entry, in the order written, whose value the user's claim contains wins.** Put the
  most privileged roles first.
- Values are compared exactly (case matters).
- With no match, `CONSOLE_OIDC_DEFAULT_ROLE` applies; without it the sign-in is refused and
  audited.
- The role is written to the account at every sign-in, so a role an admin sets in the console for
  such an account lasts only until the next sign-in. Manage it at the provider.

## Break-glass accounts

Once single sign-on works, an admin can limit local sign-in to break-glass accounts (**Users** →
Local sign-in, or `PUT /api/v1/settings/sign-in`). Everyone else then signs in through the
provider; the sign-in page keeps the local form behind "Break-glass sign-in".

- Limiting needs single sign-on configured and at least one enabled admin with a local password
  marked break-glass. While it is limited, the last such admin cannot be unmarked, demoted or
  disabled.
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
Keycloak's own session, and the refusal of a user in no mapped group.

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

## SAML identity providers

The console has no SAML support. An identity provider that speaks only SAML can be used only
through a broker that signs users in with SAML and presents OpenID Connect to the console (for
example, Keycloak with the SAML provider as an identity provider of its realm). To the console
that broker is an ordinary OpenID Connect provider, configured as above. This set-up has not been
tested with syncd.

## API tokens and single sign-on

[API tokens](api-reference.md#api-tokens) are for other systems, not people, and do not go
through the identity provider.

- **An account that signs in through single sign-on can create tokens** when its role allows (an
  admin creates them on **Integrations**). The token then acts with no more than that account's
  role.
- **The creator's role is the one stored on the account**, which single sign-on writes at each
  sign-in. A change at the provider (a user removed from the admins' group) therefore reaches a
  token only when its creator next signs in. To stop a person's tokens at once, disable the account
  in the console (they stop within a minute) or revoke the tokens.
- No API token can sign in through single sign-on or call the sign-in endpoints, and a token
  carries no session.
- **Prefer tokens to local accounts for automation.** With local sign-in limited to break-glass
  accounts, a script has no password account to sign in as; a token needs none.

The public self-service password page (`/password`) is not behind single sign-on either: people
prove who they are with their current directory password. See
[Security](security.md#self-service-password-change).

## Multi-factor authentication

The console has no multi-factor authentication of its own. Require it at the identity provider
(conditional access, an authentication policy), and limit local sign-in to break-glass accounts so
that everyone else goes through it.

## Audit

Every single sign-on is recorded in the audit trail (`ens-audit/1`, category `authentication`) as
`auth.oidc.login`: `success` with the role it gave, or `denied` with the reason (the provider
refused, the state did not match this browser, the token was not valid, no role matched, the
account is disabled, a local account has that name). Changes to who may sign in locally are
recorded as `settings.local_sign_in`. See [Security](security.md#audit-trail) and
[Audit logging](../audit-logging.md).
