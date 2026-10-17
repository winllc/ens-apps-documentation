# HTTP Route Reference

Every HTTP route served by the portal, taken from the `@Controller`/`@RestController` mappings. The **Access** column shows:

* **Public**: the path matches a `permitAll` pattern in `WebSecurityConfig`;
* **Auth**: any signed-in user;
* a role name: the `@PreAuthorize` annotation on the handler.

!!! danger "Read with the security note"
    Method security is not enabled in this version, so the `@PreAuthorize` roles below are **not enforced at runtime**. Any signed-in user can reach a role-restricted route, and the `/data/**`, `/rest/**` and `/systemNotification/**` routes are reachable without signing in. See [Security → Known security considerations](security.md#known-security-considerations).

Most routes return HTML (Thymeleaf views). Several POST handlers redirect to `/message?messages=<key>&errors=<key>`, which renders the message keys from `messages.properties`.

## Authentication and common pages

| Method | Path | Access | Purpose |
|---|---|---|---|
| GET | `/` | Public | Redirects to `/home` |
| GET | `/home` | Public | Home page |
| GET/POST | `/login` | Public | Login page |
| POST | `/perform_login` | Public | Spring Security login processing (`username`, `password`) |
| POST/GET | `/perform_logout` | Auth | Logout (also ends the AM session) |
| GET | `/additionalParameterLoginHandler` | Public | Additional RSA login prompts |
| POST | `/additionalParameterLoginHandler/submit` | Public | Answer the prompts |
| GET | `/message` | Public | Shows `messages`/`errors` keys |
| GET | `/static/**` | Public | Static assets; `/static/custom/**` serves `custom-resource.location` |
| GET | `/actuator/**` | Public | Spring Boot Actuator (health) |

## End-user self-service

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
| POST | `/user/pinReset/verify` | Public | Verify a passcode for a reset code |
| POST | `/user/pinReset/reset` | Public | Reset the PIN for a reset code |
| GET, POST | `/user/exemption/request` | Public (client certificate required) | Request an MFA exemption |

## Trusted Agent directory and appointments

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

## Token management

| Method | Path | Access | Purpose |
|---|---|---|---|
| GET, POST | `/token/distribute/search` | TRUSTED_AGENT | Find a user to assign a token to |
| GET | `/token/distribute/user/{userId}` | TRUSTED_AGENT | Assignment form |
| POST | `/token/distribute/complete` | TRUSTED_AGENT | Assign the token |
| GET, POST | `/token/lost/search` | TRUSTED_AGENT, HELP_DESK | Find a user with a token |
| GET | `/token/lost/user/{userId}` | TRUSTED_AGENT, HELP_DESK | User's tokens |
| POST | `/token/lost/complete` | TRUSTED_AGENT, HELP_DESK | Mark a token lost (`rsaId`, `tokenSerial`) |
| POST | `/token/pinReset` | TRUSTED_AGENT, HELP_DESK | Reset a user's token PIN |
| GET | `/token/deprovision/search` | TRUSTED_AGENT, HELP_DESK | Deprovision page |
| GET | `/token/deprovision/serial?serial=` | TRUSTED_AGENT, HELP_DESK | Look up a token |
| POST | `/token/deprovision/serial` | TRUSTED_AGENT, HELP_DESK | Deprovision (`tokenSerialNumber`) |

## User search

| Method | Path | Access | Purpose |
|---|---|---|---|
| GET, POST | `/user/search` | TRUSTED_AGENT, HELP_DESK | Search |
| POST | `/user/search/idOnly` | TRUSTED_AGENT, HELP_DESK | Exact User ID or last-name search |
| GET | `/user/search/details/{userId}` | TRUSTED_AGENT, HELP_DESK | User details |
| POST | `/user/search/mfaEnabledGroupToggle/{userId}` | TRUSTED_AGENT, HELP_DESK | Add to or remove from the AD RSA-enabled group (`enabled=true/false`) |
| GET | `/user/search/unlock/{userId}` | TRUSTED_AGENT, HELP_DESK | Clear the AM lockout |
| POST | `/user/search/emergencyTokenCode` | TRUSTED_AGENT, HELP_DESK | Generate an emergency access code |
| POST | `/user/search/clearCachedWindowsPassword` | TRUSTED_AGENT, HELP_DESK | Clear the cached Windows password in AM |

## Sites, shipments and reports

| Method | Path | Access | Purpose |
|---|---|---|---|
| GET | `/sites/list` | TRUSTED_AGENT | Site list |
| GET | `/sites/details/{shippingId}` | Auth | Site details |
| GET, POST | `/sites/add` | TRUSTED_AGENT | Add a site |
| GET | `/sites/edit/{shippingId}` | PMO | Edit form |
| POST | `/sites/edit` | TRUSTED_AGENT | Save an edit |
| POST | `/sites/updateStatus` | TRUSTED_AGENT | Activate or deactivate |
| POST | `/sites/delete/{shippingId}` | PMO | Delete |
| POST | `/sites/addSerialRange/{shippingId}` | PMO | Add a serial range |
| POST | `/sites/removeSerialRange/{shippingId}` | PMO | Remove a serial range |
| GET | `/shipment/list/{filter}` | TRUSTED_AGENT | Shipment list |
| GET, POST | `/shipment/request` | TRUSTED_AGENT | Request a shipment |
| GET | `/shipment/details/{guid}` | TRUSTED_AGENT | Shipment details |
| GET | `/shipment/modify/{guid}`; POST `/shipment/modify` | TRUSTED_AGENT | Modify |
| POST | `/shipment/modify/delete/{guid}` | PMO | Delete |
| POST | `/shipment/updateStatus` | TRUSTED_AGENT | Being worked on or off |
| GET | `/shipment/send/{guid}`; POST `/shipment/send` | TRUSTED_AGENT | Send with a serial range |
| GET | `/shipment/receive/{guid}`; POST `/shipment/receive` | TRUSTED_AGENT | Receive |
| POST | `/shipment/cancel` | TRUSTED_AGENT | Cancel |
| POST | `/shipment/notReceived` | TRUSTED_AGENT | Mark undeliverable |
| GET | `/shipment/print/{guid}` | TRUSTED_AGENT | Printer-friendly label |
| GET | `/report/site/{shippingId}` | TRUSTED_AGENT | Site report |
| GET | `/report/ta/{rsaId}` | PMO | Trusted Agent report |
| GET | `/report/overview` | PMO | Not implemented (always errors) |

## Trusted Agent administration and profile

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

## PMO administration

| Method | Path | Access | Purpose |
|---|---|---|---|
| GET | `/admin` | PMO | Admin page |
| GET | `/roleMapping/search`; POST `/roleMapping/search` | PMO | Search users |
| GET | `/roleMapping/update/{rsaId}`; POST `/roleMapping/update` | PMO | Edit roles |
| GET | `/roleMapping/roleView?roleName=` | PMO | Users in a role |
| GET | `/manualCron` | PMO | Job list |
| POST | `/manualCron/{name}` | PMO | Run a job |
| POST | `/email/sendGroupMessage` | PMO | Group email (`group`, `subject`, `message`, `attachment`) |
| GET | `/user/exemption/review/list` | PMO | Exemption queue |
| GET | `/user/exemption/review/details/{requestId}` | Auth | Request details |
| POST | `/user/exemption/review/submit` | PMO | `action=approve` or `deny`, `comments` |
| POST | `/user/exemption/review/delete` | PMO | Delete a request |

## JSON endpoints

| Method | Path | Access (annotation) | Returns |
|---|---|---|---|
| GET | `/data/tas/all` | Public | DataTables output of all Trusted Agents |
| GET | `/data/tas/active` | Public | Active Trusted Agents |
| GET | `/data/shipments` | TRUSTED_AGENT | All shipments |
| GET | `/data/shipments/active` | TRUSTED_AGENT | Shipments in NEW, BEING_WORKED or SHIPPED |
| GET | `/data/exemptionRequests` | TRUSTED_AGENT | All exemption requests |
| GET | `/data/exemptionRequests/notReviewed` | TRUSTED_AGENT | Pending requests |
| GET | `/data/sites` | TRUSTED_AGENT | Sites |
| GET | `/systemNotification/all` | Public | All notifications |
| GET | `/systemNotification/notExpired` | Public | Active notifications (used by the home page) |
| POST | `/systemNotification/save` | PMO | Create (`message`, `type` 1–3, `expireNum`, `expirePeriod` 1–4) |
| DELETE | `/systemNotification/delete/{id}` | PMO | Delete |
| GET | `/rest/admin/runExpiringCron` | PMO | Run `expiring` |
| GET | `/rest/admin/upcomingAppointmentsCron` | PMO | Run `appointmentupcoming` |
| GET | `/rest/admin/reSyncTrustedAgentsCron` | PMO | Run `resyncta` |

The `/data/**` endpoints use `spring-data-jpa-datatables` and accept the standard DataTables server-side query parameters.

## Connector endpoint

| Method | Path | Access | Purpose |
|---|---|---|---|
| POST | `http://connector:8080/app/connector` | No authentication; internal network only | Hessian endpoint for `RSAAuthManagerServiceInterface` (see [RSA AM Integration](rsa-integration.md)) |

The connector port is not published by the release package's `docker-compose.yml`, and the Helm chart gives the Connector only a cluster-internal Service. Keep it that way: the endpoint has no authentication of its own and performs RSA AM administration with the configured Super Admin account.
