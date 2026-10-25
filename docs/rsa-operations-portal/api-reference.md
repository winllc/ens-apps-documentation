# API and HTTP route reference

The Portal has two kinds of HTTP interface:

- the **integration API** under **`/api/v1`**, for ITSM and identity-governance tools: JSON over
  HTTPS, versioned, described by an OpenAPI document and called with an **API token**;
- the **pages and routes** the browser uses, listed after it, taken from the
  `@Controller`/`@RestController` mappings.

The audit trail has its own pull API with its own tokens (see [License and audit log](#license-and-audit-log)).

## Integration API

### The OpenAPI document

Served at **`/api/v1/openapi.json`** without signing in (it does not show the Portal's version). The
server URL in it is `application.dns.name`. CI keeps a copy of each build's.

### API tokens

A PMO administrator makes tokens on *Admin → Integrations* (`/admin/integrations`):

- a name, a **service identity** (for example `servicenow`), scopes, a validity (1 to 730 days, 90 by
  default) and a request limit per minute (60 by default);
- the **security domains and/or identity sources** whose users it may read and change. A token that
  can ask for changes must have such a list, or be given *all security domains and identity sources*
  deliberately, which the audit trail records as a warning. Read-only tokens may be left
  unrestricted.

The token (`ensapi_...`) is shown **once**, on the page that answers the form, sent with
`Cache-Control: no-store`; only its SHA-256 is kept. The page shows each token's prefix, scopes,
expiry, last use and the address it came from, and revokes it. A caller sends
`Authorization: Bearer <token>`.

| Scope | Allows |
|---|---|
| `users:read` | `GET /api/v1/users/{userId}`, `GET /api/v1/users/{userId}/tokens` |
| `tokens:read` | `GET /api/v1/tokens/{serial}`: assigned user, enabled, new-PIN and next-tokencode modes, expiry, last login |
| `helpdesk:read` | `GET /api/v1/helpdesk/requests/{id}` for requests its service identity opened |
| `tokens:assign` | `POST /api/v1/tokens/{serial}/assignment` (help-desk action `token-assign`) |
| `tokens:unassign` | `POST /api/v1/tokens/{serial}/unassignment` (`token-deprovision`, or `token-lost` with `"lost":true`) |
| `tokens:pin-reset` | Help-desk action `pin-reset` |
| `users:status` | `POST /api/v1/users/{userId}/enable`, `/disable` (`user-enable`, `user-disable`) |

**Whose accounts a token may touch.** An integration's changes run as the Connector's service
account, so Authentication Manager does not limit them to a security domain or identity source the
way it limits an operator's own session. The Portal therefore checks the token's list before anything
is read or changed: when the request is opened and again when it runs, and for the user and token
lookups (another domain's user or token is "not found"); an unassigned token counts by its own
security domain. Also give the Connector's service account an Authentication Manager administrative
role scoped to only the security domains and identity sources the integrations need.

### Changes are help-desk requests

Every change goes through the [help-desk rules](help-desk.md); the API is not a way around them.

1. `POST /api/v1/helpdesk/requests` (or one of the shortcuts above) with `action`, `userId`, `serial`
   (for token actions), `ticket` and `reason`: `201` and the request, `VERIFYING`.
2. Verify the user: `POST /api/v1/helpdesk/requests/{id}/verification/start` with
   `{"method":"email-code"}` sends the code to the user, and
   `POST /api/v1/helpdesk/requests/{id}/verification` with `{"method":"email-code","code":"..."}` (or
   `{"method":"tokencode","serial":...,"tokencode":...}` where a tokencode counts) checks what the user
   read back. An integration cannot use `in-person`, which is an operator's attestation. Disabling an
   account needs no verification; everything else does while
   `rsa.helpdesk.verification.required` is true. One service identity may therefore disable at most
   `ens.api.disable-cap` accounts (`10/1h` by default, a rolling window, all its tokens together)
   without a second operator; past it, each further disable answers `202` and waits for approval, and
   `api.disable-cap-exceeded` is recorded as an alert.
3. `POST /api/v1/helpdesk/requests/{id}/submit`: `200` with the completed (or failed) request, or
   **`202` with the request `PENDING_APPROVAL`** when the user is privileged. A second operator
   approves it on the Portal's Help-Desk Requests page (an integration never approves); then
   `POST /api/v1/helpdesk/requests/{id}/run`. `POST .../{id}/cancel` withdraws it. The request's `next`
   field lists the calls that apply now.

```bash
curl -H "Authorization: Bearer ensapi_..." https://rsa-portal.example.com/api/v1/tokens/000123456789

curl -X POST -H "Authorization: Bearer ensapi_..." -H "Content-Type: application/json" \
  -d '{"action":"pin-reset","userId":"jdoe","serial":"000123456789","ticket":"INC-1","reason":"Forgot PIN"}' \
  https://rsa-portal.example.com/api/v1/helpdesk/requests
```

The change is made in Authentication Manager on the Connector's service account, after its
`am.change-attempt` is committed. The audit trail's actor is `{"type":"service","id":"<identity>"}`,
with `details.serviceIdentity`, and the Connector's log names `for=service:<identity>`. The user gets
the help-desk e-mail, which says the integration made the change. Emergency access codes and clearing
the cached Windows password are not offered through the API.

### Errors, limits and sessions

- Errors are JSON, `{"error":"...","message":"..."}`: `400`; `401` (no valid token,
  `WWW-Authenticate: Bearer`); `403` (a scope, the license, the read-only demo); `404` (including
  another identity's request); `409` (a help-desk step that cannot be taken now; `error` is the
  reason's key); `429` with `Retry-After` (the token's limit, or the code limits of
  [Rate limits](security.md#rate-limits-and-uniform-answers)); `502` (Authentication Manager or the
  Connector failed).
- Each token's limit is counted in the database, across replicas.
- Calls keep no session and set no cookie. CSRF protection is off on `/api/v1` only, where nothing
  runs without a bearer token, so a signed-in browser's cookie is no use there.
- Audited: `api.token-created` (a warning when a token that can change something is given all
  security domains), `api.token-revoked`, `api.disable-cap-exceeded`; `access.denied` with
  `api_token_missing`, `api_token_invalid`, `api_token_expired`, `api_token_revoked` or
  `api_scope_required`.

| Setting | Default | Meaning |
|---|---|---|
| `ens.api.disable-cap` (`ENS_API_DISABLECAP`) | `10/1h` | Account disables one service identity may make in a rolling window without a second operator |

The Portal also posts signed events to HTTPS receivers; see [Webhooks](webhooks.md).

## Pages and routes

The **Access** column shows:

* **Public**: the path matches a `permitAll` pattern in `WebSecurityConfig`;
* **Auth**: any signed-in user;
* a role name: the `@PreAuthorize` annotation on the handler.

Method security is on, so the server enforces every role below. Every POST, PUT and DELETE needs the CSRF token (forms carry it; AJAX calls send it as a header), except the audit pull API and the integration API, which take a bearer token and no session. While the [consent banner](security.md#notice-and-consent-banner) is on, a browser session reaches nothing but `/consent` until it is acknowledged. See [Security](security.md).

Most routes return HTML (Thymeleaf views). Several POST handlers redirect to `/message?messages=<key>&errors=<key>`, which renders the message keys from `messages.properties`.

The POSTs anyone can reach without signing in (sign-in, Test My Token, the PIN resets, registration, exemption requests, appointment booking and cancelling) are rate-limited: over a limit they answer `429 Too Many Requests` with `Retry-After`, a short page for browsers or `{"error":"rate_limited","retryAfter":N}` for clients that ask for JSON. See [Security → Rate limits and uniform answers](security.md#rate-limits-and-uniform-answers).

### Authentication and common pages

| Method | Path | Access | Purpose |
|---|---|---|---|
| GET | `/` | Public | Redirects to `/home` |
| GET | `/home` | Public | Home page |
| GET/POST | `/login` | Public | Login page |
| POST | `/perform_login` | Public | Spring Security login processing (`username`, `password`) |
| POST/GET | `/perform_logout` | Auth | Logout (also ends the AM session) |
| GET | `/signed-out` | Public (next release) | After sign-out with `ENS_SSO_OIDC_END_SESSION=true`: says the operator is signed out of the Portal and goes on to the OpenID Connect provider's `end_session_endpoint` (a page refresh and a **Continue** button). See [Single sign-on → Signing out](single-sign-on.md#signing-out) |
| GET | `/additionalParameterLoginHandler` | Public | Additional RSA login prompts |
| POST | `/additionalParameterLoginHandler/submit` | Public | Answer the prompts |
| GET | `/message` | Public | Shows `messages`/`errors` keys |
| GET | `/static/**` | Public | Static assets; `/static/custom/**` serves `custom-resource.location` |
| GET, POST | `/consent` | Public | The notice-and-consent banner and its **I agree** |
| GET | `/oauth2/authorization/<id>`, `/login/oauth2/code/<id>` | Public, with an OIDC provider configured | Start a [single sign-on](single-sign-on.md), and its redirect URI |
| GET, POST | `/saml2/authenticate/<id>`, `/login/saml2/sso/<id>`, `/saml2/service-provider-metadata/<id>` | Public, with a SAML provider configured | Start a single sign-on, the Assertion Consumer Service, and the Portal's metadata |
| GET | `/api/v1/openapi.json` | Public | The integration API's OpenAPI document |
| GET | `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness` | Public (details for PMO) | Health probes. On the management port instead when `MANAGEMENT_SERVER_PORT` is set |
| GET | `/actuator/prometheus` | Management port only, no sign-in | Prometheus metrics |

### End-user self-service

| Method | Path | Access | Purpose |
|---|---|---|---|
| GET, POST | `/user/token/test` | Public | Test a passcode |
| GET, POST | `/user/registration/start` | Public | Enrollment step 1 (`?userId=` pre-fills) |
| POST | `/user/registration/complete` | Public | Enrollment step 2 (set PIN) |
| GET | `/user/pinReset/splash` | Public | PIN reset options |
| GET, POST | `/user/pinReset/certReset` | Public | Certificate-based reset: look up the user's resettable tokens |
| POST | `/user/pinReset/certReset/complete` | Public | Reset the selected token (`rsaId`, `tokenSerial`) |
| GET | `/user/pinReset/passwordReset` | Public | Windows-password reset form (`?noFooter`) |
| POST | `/user/pinReset/passwordReset/submit` | Public | Submit username, password and token serial |
| GET | `/user/pinReset/start/{resetCode}` | Public | PIN-check email link |
| POST | `/user/pinReset/verify` | Public | Verify the current tokencode of the reset code's token (`tokencode`, 6 to 8 digits, no PIN; with `resetCode`). Each reset code accepts at most `cron.tokenPinCheck.resetLinkMaxAttempts` codes |
| POST | `/user/pinReset/reset` | Public | Reset the PIN for a reset code |
| GET, POST | `/user/exemption/request` | Public (client certificate required) | Request an MFA exemption |
| GET, POST | `/user/exemption/confirm/{code}` | Public | Confirm an exemption request from the emailed link |
| GET, POST | `/swaptoken` | Auth | Swap to a replacement token |

### Trusted Agent directory and appointments

| Method | Path | Access | Purpose |
|---|---|---|---|
| GET | `/ta/list` | Public | Directory |
| GET | `/ta/details/{rsaId}` | Public | Trusted Agent details |
| GET | `/appointment/schedule/{taRsaId}` | Public | Booking form |
| POST | `/appointment/schedule` | Public | Create an appointment |
| GET | `/appointment/details/{appointmentId}` | Public | Appointment details |
| POST | `/appointment/confirm` | TRUSTED_AGENT | Confirm (assigned agent only) |
| POST | `/appointment/cancel` | Public | Cancel (assigned agent, or requester with a matching certificate) |
| POST | `/appointment/updateNotes` | TRUSTED_AGENT | Update notes |
| GET | `/rest/appointment/appointmentTimesForTaOnDate/{MM-dd-yyyy}/{taRsaId}` | Public | JSON list of booked times |

### Token management

| Method | Path | Access | Purpose |
|---|---|---|---|
| GET, POST | `/token/distribute/search` | TRUSTED_AGENT | Find a user to assign a token to |
| GET | `/token/distribute/user/{userId}` | TRUSTED_AGENT | Assignment form |
| POST | `/token/distribute/complete` | TRUSTED_AGENT | Assign the token |
| GET, POST | `/token/lost/search` | TRUSTED_AGENT, HELP_DESK | Find a user with a token |
| GET | `/token/lost/user/{userId}` | TRUSTED_AGENT, HELP_DESK | User's tokens |
| POST | `/token/lost/complete` | TRUSTED_AGENT, HELP_DESK | Redirects to a new help-desk request to mark the token lost (`rsaId`, `tokenSerial`) |
| POST | `/token/pinReset` | TRUSTED_AGENT, HELP_DESK | Redirects to a new help-desk request to reset the PIN |
| GET | `/token/deprovision/search` | TRUSTED_AGENT, HELP_DESK | Deprovision page |
| GET | `/token/deprovision/serial?serial=` | TRUSTED_AGENT, HELP_DESK | Look up a token |
| POST | `/token/deprovision/serial` | TRUSTED_AGENT, HELP_DESK | Deprovision a token assigned to nobody (`tokenSerialNumber`); for an assigned token, redirects to a new help-desk request |

### User search

| Method | Path | Access | Purpose |
|---|---|---|---|
| GET, POST | `/user/search` | TRUSTED_AGENT, HELP_DESK | Search |
| POST | `/user/search/idOnly` | TRUSTED_AGENT, HELP_DESK | Exact User ID or last-name search |
| GET | `/user/search/details/{userId}` | TRUSTED_AGENT, HELP_DESK | User details |
| POST | `/user/search/mfaEnabledGroupToggle/{userId}` | TRUSTED_AGENT, HELP_DESK | Add to or remove from the AD RSA-enabled group (`enabled=true/false`) |
| POST | `/user/search/unlock/{userId}` | TRUSTED_AGENT, HELP_DESK | Clear the AM lockout (one click, not a help-desk request) |
| POST | `/user/search/emergencyTokenCode` | TRUSTED_AGENT, HELP_DESK | Redirects to a new help-desk request for an emergency access code |
| POST | `/user/search/clearCachedWindowsPassword` | TRUSTED_AGENT, HELP_DESK | Redirects to a new help-desk request to clear the cached Windows password |

### Help-desk requests

See [Help-desk requests](help-desk.md). Every step is checked on the server; `{id}` is the request's public id.

| Method | Path | Access | Purpose |
|---|---|---|---|
| GET | `/helpdesk/new?action=&userId=&serial=` | TRUSTED_AGENT, HELP_DESK | Start a request. `action` is `pin-reset`, `emergency-access`, `token-lost`, `token-deprovision` or `clear-windows-password` |
| POST | `/helpdesk/new` | TRUSTED_AGENT, HELP_DESK | Open it, with a ticket and a reason |
| GET | `/helpdesk/request/{id}` | TRUSTED_AGENT, HELP_DESK, PMO | The request's page |
| POST | `/helpdesk/request/{id}/verify/{method}/start` | TRUSTED_AGENT, HELP_DESK | Start a verification's first step, such as sending the e-mail code |
| POST | `/helpdesk/request/{id}/verify/{method}` | TRUSTED_AGENT, HELP_DESK | Check a code or tokencode, or record an in-person check |
| POST | `/helpdesk/request/{id}/submit` | TRUSTED_AGENT, HELP_DESK | Run the change, or send it for approval. The operator who opened it |
| POST | `/helpdesk/request/{id}/run` | TRUSTED_AGENT, HELP_DESK | Run an approved request. The operator who opened it |
| POST | `/helpdesk/request/{id}/reveal` | TRUSTED_AGENT, HELP_DESK | Show an emergency access code, once |
| POST | `/helpdesk/request/{id}/cancel` | TRUSTED_AGENT, HELP_DESK, PMO | Cancel a request that has not run. The operator who opened it |
| GET | `/helpdesk/requests` | TRUSTED_AGENT, HELP_DESK, PMO | Requests waiting for your approval, and your own |
| POST | `/helpdesk/request/{id}/approve`, `/reject` | TRUSTED_AGENT, HELP_DESK, PMO | Decide a request: an approver role from `rsa.helpdesk.dual-control.approver-roles`, not the operator who asked and not the user. A rejection needs a comment |

### Sites, shipments and reports

| Method | Path | Access | Purpose |
|---|---|---|---|
| GET | `/sites/list` | TRUSTED_AGENT | Site list |
| GET | `/sites/details/{shippingId}` | Auth; TRUSTED_AGENT from the next release, like the site list and the shipment pages that link there | Site details |
| GET, POST | `/sites/add` | TRUSTED_AGENT | Add a site |
| GET | `/sites/edit/{shippingId}` | PMO | Edit form |
| POST | `/sites/edit` | PMO | Save an edit |
| POST | `/sites/updateStatus` | PMO | Activate or deactivate |
| POST | `/sites/delete/{shippingId}` | PMO | Delete |
| POST | `/sites/addSerialRange/{shippingId}` | PMO | Add a serial range |
| POST | `/sites/removeSerialRange/{shippingId}` | PMO | Remove a serial range |
| GET | `/shipment/list/{filter}` | TRUSTED_AGENT | Shipment list |
| GET, POST | `/shipment/request` | TRUSTED_AGENT | Request a shipment |
| GET | `/shipment/details/{guid}` | TRUSTED_AGENT | Shipment details |
| GET | `/shipment/modify/{guid}`; POST `/shipment/modify` | TRUSTED_AGENT | Modify |
| POST | `/shipment/modify/delete/{guid}` | PMO | Delete |
| POST | `/shipment/updateStatus` | PMO | Being worked on or off |
| GET | `/shipment/send/{guid}`; POST `/shipment/send` | PMO | Send with a serial range |
| GET | `/shipment/receive/{guid}`; POST `/shipment/receive` | TRUSTED_AGENT | Receive |
| POST | `/shipment/cancel` | TRUSTED_AGENT | Cancel |
| POST | `/shipment/notReceived` | TRUSTED_AGENT | Mark undeliverable |
| GET | `/shipment/print/{guid}` | TRUSTED_AGENT | Printer-friendly label |
| GET | `/report/site/{shippingId}` | TRUSTED_AGENT | Site report |
| GET | `/report/ta/{rsaId}` | PMO | Trusted Agent report |
| GET | `/report/overview` | PMO | Not implemented (always errors) |

### Trusted Agent administration and profile

| Method | Path | Access | Purpose |
|---|---|---|---|
| GET, POST | `/ta/profile` | TRUSTED_AGENT | Own profile |
| GET | `/ta/register/start` | PMO | Registration page |
| POST | `/ta/register/search` | PMO | Look up a user in AM |
| POST | `/ta/register/submit` | PMO | Register |
| GET | `/ta/modify/user/{rsaId}`; POST `/ta/modify` | PMO | Edit |
| POST | `/ta/modify/status` | PMO | Active or inactive |
| POST | `/ta/modify/delete/{rsaId}` | PMO | Delete |
| GET, POST | `/ta/upload` | PMO | CSV bulk upload (`file`) |

### PMO administration

| Method | Path | Access | Purpose |
|---|---|---|---|
| GET | `/admin` | PMO | Admin page |
| GET | `/roleMapping/search`; POST `/roleMapping/search` | PMO | Search users |
| GET | `/roleMapping/update/{rsaId}`; POST `/roleMapping/update` | PMO | Edit roles |
| GET | `/roleMapping/roleView?roleName=` | PMO | Users in a role |
| GET | `/manualCron` | Auth | Job list (the menu entry is shown to PMO) |
| POST | `/manualCron/{name}` | PMO | Run a job |
| POST | `/email/sendGroupMessage` | PMO | Group email (`group`, `subject`, `message`, `attachment`) |
| GET | `/user/exemption/review/list` | PMO | Exemption queue |
| GET | `/user/exemption/review/details/{requestId}` | PMO | Request details |
| POST | `/user/exemption/review/submit` | PMO | `action=approve` or `deny`, `comments` |
| POST | `/user/exemption/review/delete` | PMO | Delete a request |

### License and audit log

| Method | Path | Access | Purpose |
|---|---|---|---|
| GET | `/admin/license` | Auth (the screen is for PMO) | License screen |
| POST | `/admin/license` | PMO | Upload a license file |
| GET | `/license/status` | Auth | License state as JSON; PMO users also get the installation id and file |
| GET | `/admin/audit` | PMO | Audit Log page (search and filters) |
| GET | `/admin/audit/events/{seq}` | PMO | One event |
| GET | `/admin/audit/export` | PMO | Export the current filter as CSV or NDJSON (audited). From the next release, a CSV value starting with `=`, `+`, `-`, `@`, a tab or a carriage return gets a leading `'`; NDJSON keeps every value exactly |
| POST | `/admin/audit/verify` | PMO | Verify the hash chain |
| POST | `/admin/audit/tokens`, `/admin/audit/tokens/{id}/revoke` | PMO | Create or revoke a pull-API token |
| GET | `/admin/integrations` | PMO | API tokens and webhooks. `POST /admin/integrations/tokens`, `/tokens/{id}/revoke`, `/webhooks`, `/webhooks/{id}/rotate`, `/webhooks/{id}/delete` |
| GET | `/api/audit/events?after=<seq>&limit=<n>` | Bearer token | The audit pull API: no session, no CSRF token, works in every license mode. See [Audit logging](../audit-logging.md#the-pull-api) |

### JSON endpoints

| Method | Path | Access | Returns |
|---|---|---|---|
| GET | `/data/tas/all` | PMO | DataTables output of all Trusted Agents |
| GET | `/data/tas/active` | Public | Active Trusted Agents |
| GET | `/data/shipments` | TRUSTED_AGENT | All shipments |
| GET | `/data/shipments/active` | TRUSTED_AGENT | Shipments in NEW, BEING_WORKED or SHIPPED |
| GET | `/data/exemptionRequests` | PMO | All exemption requests |
| GET | `/data/exemptionRequests/notReviewed` | PMO | Pending requests |
| GET | `/data/sites` | TRUSTED_AGENT | Sites |
| GET | `/systemNotification/all` | PMO | All notifications |
| GET | `/systemNotification/notExpired` | Public | Active notifications (used by the home page) |
| POST | `/systemNotification/save` | PMO | Create (`message`, `type` 1–3, `expireNum`, `expirePeriod` 1–4) |
| DELETE | `/systemNotification/delete/{id}` | PMO | Delete |
| POST | `/rest/admin/runExpiringCron` | PMO | Run `expiring` |
| POST | `/rest/admin/upcomingAppointmentsCron` | PMO | Run `appointmentupcoming` |
| POST | `/rest/admin/reSyncTrustedAgentsCron` | PMO | Run `resyncta` |

The `/data/**` endpoints use `spring-data-jpa-datatables` and accept the standard DataTables server-side query parameters.

### Connector endpoint

| Method | Path | Access | Purpose |
|---|---|---|---|
| POST | `https://connector:8443/app/connector` | HTTP Basic with the shared secret (`CONNECTOR_AUTH_PASSWORD`), and the Portal's client certificate where mutual TLS is on (new installations) | Hessian endpoint for `RSAAuthManagerServiceInterface` (see [RSA AM Integration](rsa-integration.md#portal-to-connector)) |
| GET, HEAD | `http://localhost:8080/app/health` (inside the Connector container) | None | `UP` (200) or `DOWN` (503) |

The Connector's ports are not published by the release package's `docker-compose.yml`, and the Helm chart gives the Connector only a cluster-internal Service that, by default, only the Portal's pods may reach. Keep it that way: the endpoint holds the service account's AM session.
