# Admin Guide

In-application administration is done by users with the **PMO** role. The **PMO Activities** menu contains **Register a New TA**, **PMO Activities** (the admin page at `/admin`) and **Exemption Requests** (with a count of pending requests). PMO users also see a badge with the number of new shipment requests.

!!! note
    Roles come from RSA AM administrative roles (see [RSA AM Integration](rsa-integration.md#administrative-role-guids)). The first PMO user must be given the mapped PMO administrative role directly in the RSA AM Security Console. A PMO user who also performs Trusted Agent work (sites, shipment details, token assignment) needs the `TRUSTED_AGENT` role as well.

## Admin page (`/admin`)

| Admin task | Opens |
|---|---|
| Modify the System Notifications that are displayed on the home page | System Notifications dialog |
| Send an email to a group of users | Group Email dialog |
| Upload multiple Trusted Agents with a CSV file | `/ta/upload` |
| Manual Run Cron Jobs | `/manualCron` |
| Manage User Roles | `/roleMapping/search` |
| Integration API tokens and signed webhooks for ITSM and identity-governance tools | `/admin/integrations` |

## Integrations (`/admin/integrations`)

PMO administrators let ITSM and identity-governance tools in, and tell them what happened:

- **API tokens** for the [integration API](api-reference.md#integration-api): a name, a service
  identity (for example `servicenow`), scopes, a validity (1 to 730 days, 90 by default), a request
  limit per minute (60 by default), and the security domains or identity sources whose users the
  token may touch (required for a token that can change anything, unless you deliberately choose
  all of them, which is audited as a warning). The token is shown once. The page shows each token's
  prefix, scopes, expiry, last use and the address it came from, and revokes it.
- **Webhooks**: an `https` URL and the events (`helpdesk.*`, `token.*`, `user.*`, or single events).
  The signing secret is shown once; **rotate** replaces it. The page lists the latest deliveries.
  See [Webhooks](webhooks.md).

Changes an integration asks for are [help-desk requests](help-desk.md): they need a ticket and a
verified user, and a privileged user's request waits for a second operator, who approves it on the
Help-Desk Requests page like any other.

## Managing user roles

**Admin → Manage User Roles**

| Screen | URL | Use |
|---|---|---|
| Search | `/roleMapping/search` | Free-text search of RSA AM users (login ID or name) |
| Update | `/roleMapping/update/{userId}` | Tick or untick **TRUSTED_AGENT**, **PMO** and **HELP_DESK**, then save |
| Role view | `/roleMapping/roleView?roleName=<ROLE>` | List every user who holds a role |

When you save:

* **Adding** a role links the user to the mapped AM administrative role (`LinkAdminRolesPrincipalsCommand`). Adding **TRUSTED_AGENT** also creates a Trusted Agent profile with the user's name and email from AM (registered by `SYSTEM`), or re-activates an existing one.
* **Removing** a role unlinks it in AM. Removing **TRUSTED_AGENT** also marks the Trusted Agent profile inactive.
* The user must sign in again for the change to take effect.
* The link and unlink run in **your own** AM session, so AM applies your permissions and its audit log names you. Your PMO administrative role in AM must be allowed to assign and unassign the mapped administrative roles ([Least-privilege AM roles](rsa-integration.md#least-privilege-am-roles)).

The `role.count` and `role.mapped.N.*` properties define which AM roles back each portal role ([Configuration](configuration.md#rsa-am-settings-used-by-the-portal)).

## Trusted Agents

### Register a Trusted Agent (`/ta/register/start`)

1. Enter the user's **User ID** and search. The portal looks the user up in RSA AM and pre-fills first name, last name and email. If the user is not found, it says the user does not exist.
2. Optionally choose a **site** (agents can also pick their own later) and submit.
3. The portal links the user to the TRUSTED_AGENT AM admin role, creates the profile with default working hours (`ta.profile.defaultWorkingHours`), records `TRUSTED_AGENT_REGISTERED`, and emails the new agent (*ta-registered* template) with a link to their profile.

Errors: *"Trusted Agent with this ID already exists"*, *"User does not exist in RSA Authentication Manager"*, *"Error communicating with RSA Authentication Manager"*.

### Bulk upload (`/ta/upload`)

Upload a CSV file (5 MB maximum) with **no header row**, in the format `User ID,Site Shipping ID (Optional)`:

```csv
jdoe,SITE-001
asmith,
```

Each row goes through the same registration as above. The results page shows the count of successful registrations, and the failures split into *already exists* and *other*.

### Edit, (de)activate or delete

From **Trusted Agent Directory → Details**, PMO users see **Edit TA** (`/ta/modify/user/{id}`) and **TA Report** (`/report/ta/{id}`).

* **Edit**: contact details, desk, working hours, site.
* **Status**: mark the agent active or inactive. This also links or unlinks the TRUSTED_AGENT AM admin role, records `TRUSTED_AGENT_MARKED_ACTIVE` or `TRUSTED_AGENT_MARKED_INACTIVE`, and emails the agent with the *ta-status-changed* template.
* **Delete**: removes the profile, unlinks the TRUSTED_AGENT AM admin role, and records `TRUSTED_AGENT_DELETED`.
* **TA Report**: tokens issued by the agent and the agent's audit events.

The nightly **`resyncta`** job keeps the local Trusted Agent list aligned with the users who hold the TRUSTED_AGENT AM admin role. It adds missing agents, deactivates agents who no longer hold the role, and refreshes their name, middle initial and email.

## Sites

Trusted Agents add and maintain sites. PMO users can also:

* **Edit Site** (`/sites/edit/{shippingId}`);
* **Delete** a site (`SITE_DELETED` audit event);
* manage the **token serial ranges** associated with a site (**Associate New Token Serial Range with Site**, or **Delete** a range). Each site's report and token counts use these ranges.

## Shipments

PMO users fulfil the shipment requests raised by Trusted Agents:

1. Open the request from **Shipments** (new requests are counted in the menu) and choose **Modify**.
2. Click **being worked**. The status becomes `BEING_WORKED`, you become the fulfiller, and the requester is emailed. You can undo this while you are the one working it.
3. Click **Send**, enter the **token serial range** (start and end), and submit. The portal checks the range in RSA AM and refuses an invalid one. The status becomes `SHIPPED` and the requester is emailed.
4. Print the label from the shipment details page if needed.
5. The receiving agent confirms receipt (`RECEIVED`), and you are emailed. If they report it as not received, the status becomes `UNDELIVERABLE`.

PMO users can also **delete** a shipment (`/shipment/modify/delete/{guid}`).

## Exemption requests

**PMO Activities → Exemption Requests** (`/user/exemption/review/list`) lists requests that are not yet reviewed, and can be switched to show all. Open a request to see the user ID, certificate DN, reason and dates, then:

* **Approve** or **Deny** with optional **comments**. The reviewer and the review time are recorded, and the requester is emailed (*user-exemption-request-updated* template). Approved users cannot be assigned a token, and they are excluded from the `USERS_WITHOUT_TOKEN` group email.
* **Delete** the request.

## System notifications

**Admin → Modify the System Notifications** opens a dialog that lists the current notifications and adds new ones:

| Field | Values |
|---|---|
| Message | Free text |
| Type | `Info`, `Alert`, `Critical` |
| Expires in | a number of `Hour(s)`, `Day(s)`, `Week(s)` or `Month(s)` (a month counts as 720 hours) |

Notifications appear on the home page until they expire. You can also delete them from the dialog. They are stored in the `SystemNotification` table, with the creator recorded.

## Group email

**Admin → Send an email to a group of users** sends a plain-text email (optionally with one attachment, 5 MB maximum) from `mail.default.from` to one group:

| Group | Recipients |
|---|---|
| `TRUSTED_AGENTS` | Email addresses of active Trusted Agents |
| `PMO` | Users holding the PMO AM admin role |
| `USERS_WITH_TOKEN` | Every email address on a token enrollment record |
| `ALL_USERS` | Same as `USERS_WITH_TOKEN` |
| `ALL_ADMINS` | `TRUSTED_AGENTS` + `PMO` |
| `ALL_ADMINS_AND_USERS` | `ALL_USERS` + `ALL_ADMINS` |
| `USERS_WITHOUT_TOKEN` | AD users (with a `mail` attribute) who are **not** in the RSA-enabled group, minus users with an approved exemption. Requires AD integration. |

Addresses without an `@` are dropped, and each recipient gets an individual message.

## Scheduled jobs (`/manualCron`)

The **Manual Run Cron Jobs** page lists each job's name, description, enabled and debug flags, and last-run details (for example *"Finished at: …, Message: Successfully Sent 12 emails out of 12"*, prefixed `(DEBUG MODE - NO EMAILS SENT)` in debug mode). **Run** triggers the job immediately, in the background.

| Job | Purpose |
|---|---|
| `expiring` | Token-expiry reminder emails |
| `appointmentupcoming` | Upcoming-appointment reminders to users, and digests to agents |
| `resyncta` | Re-sync Trusted Agents with RSA AM |
| `lostTokenThresholdMonitor` | Report users with too many lost tokens to `mfa.group.email` (debug **on** by default: only logged. From the next release new installations' `.env` turns debug off, and the Portal warns at every start while it is on) |
| `sendDelayedTokenRegistrationEmails` | Send or re-send enrollment links for staged roll-outs |
| `tokenPinCheck` | Initial-PIN and check-PIN emails for tokens that have not been used |

Enabling or disabling a job, debug mode and schedules are set in `.env` (Helm: `config`) ([Configuration → Scheduled jobs](configuration.md#scheduled-jobs)). A disabled job does not run, even when triggered from this page. Last-run details are lost when the portal restarts.

## Reports

| Report | URL | Access |
|---|---|---|
| Trusted Agent report | `/report/ta/{userId}` | PMO |
| Site report | `/report/site/{shippingId}` | Trusted Agent |
| Overview report | `/report/overview` | PMO. Not implemented: it always returns the error page ("Could not generate report"). |

## Branding and content

Text, links, images and email wording are not edited in the UI. Change them in `default_build/` and restart the portal ([Configuration → Branding](configuration.md#branding-text-and-email-templates)).
