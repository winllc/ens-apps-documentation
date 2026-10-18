# Help-desk requests

Every change a Trusted Agent or Help Desk operator makes to a user's authenticator is a
**help-desk request**. A request needs a ticket and a reason, a verified user, a second operator's
approval when the user is privileged, and it ends with an e-mail to the user. The server checks
every step, whatever the browser sends.

## The five changes

| Change | Where it starts | License feature | Tokencode accepted as verification |
|---|---|---|:-:|
| Reset the PIN (the user sets a new one at the next sign-in) | **User Details** → **Reset Pin** | `help-desk` | No |
| Issue an emergency access code | **User Details** | `help-desk` | No |
| Mark a token lost (unassign it) | **User Lost Token**, **User Details** | `trusted-agents` | No |
| Deprovision a token that is assigned to a user (unassign and reset it) | **Deprovision Token** | `trusted-agents` | Yes |
| Clear the cached Windows password | **User Details** | `help-desk` | Yes |

Nothing changes on the first click any more: the buttons open a request at `/helpdesk/new`. The
old addresses (`POST /token/pinReset`, `/user/search/emergencyTokenCode`,
`/user/search/clearCachedWindowsPassword`, `/token/lost/complete`, and `/token/deprovision/serial`
for an assigned token) only redirect there, so a stale page or a hand-made request changes nothing.
A token in stock, assigned to nobody, is still deprovisioned at once.

**Not help-desk requests:**

- **Unlocking an account** does not change its authenticator. It stays one click on **User
  Details**, recorded as `user.unlocked`.
- **Self-service.** Users who reset their own PIN (with a certificate, a Windows password, or the
  e-mailed link and the token's code), set a PIN at registration, or swap to a replacement token
  prove who they are in that flow.

An operator acting on their own account goes through a help-desk request, which always needs a
second operator.

## How a request goes

Requests are opened by users with the `TRUSTED_AGENT` or `HELP_DESK` role. **Help-Desk Requests**
in the menu (`/helpdesk/requests`) lists the requests waiting for your approval and your own.

1. **Ticket and reason.** Both are required: a ticket or reference of up to 100 characters, and a
   reason of up to 1,000.
2. **Verify the user's identity** with one of the [methods](#verifying-the-user) before anything
   changes. Five wrong codes close the request. After verification, the operator has 15 minutes
   to run the request or send it for approval; after that, the user is verified again.
3. **Approval**, when the user is [privileged](#approval-by-a-second-operator) or it is the
   operator's own account. The request waits for a different operator to approve it; then the
   operator who asked runs it.
4. **The change**, in the operator's own Authentication Manager session. It is recorded before and
   after it is made, as `am.change-attempt` and `am.change-outcome`, with the ticket, the reason and
   how the user was verified (see [Audit logging](../audit-logging.md)).
5. **The user is told** by e-mail what changed, when, which agent made it, the ticket, and whom to
   contact if it was not them. This needs a mail server and an e-mail address for the user.

A request's state is one of: *verifying the user*, *verified, ready*, *waiting for approval*,
*approved, ready to run*, *running*, *done*, *failed*, *rejected*, *cancelled* or *expired*. The
operator who asked can cancel a request until it runs.

## Verifying the user

The operator verifies the user with one of the methods in `rsa.helpdesk.verification.methods`,
shown in that order:

| Method | What happens |
|---|---|
| `email-code` | An 8-digit code goes to the address Authentication Manager has for the user, through the Portal's mail relay, and the user reads it back. Offered only while the Portal can send mail and the user has an address. The code is valid for 10 minutes, at most 3 are sent per request, and only its SHA-256 hash is kept |
| `tokencode` | The user reads the code one of their own tokens shows now, and Authentication Manager checks it against that token, without a PIN, a session or a change. Offered when the user has an enabled token. Never ask for the PIN |
| `in-person` | The operator confirms that the user is there in person and that they checked a government-issued photo ID, and says which ID. The text is recorded |

!!! warning "A tokencode proves possession, not identity"
    A tokencode shows only that the caller holds the token. It counts **only for deprovisioning a
    token and clearing the cached Windows password**, never for a PIN reset, an emergency access
    code or a lost token: someone holding a stolen token could otherwise have its PIN reset and set
    their own, and for an emergency code or a lost token the caller does not have the token anyway.
    For those three, it is neither offered nor accepted. While verification is required, configuring
    `tokencode` as the only method, or no method, stops the Portal at startup.

**Microsoft Entra Verified ID** is not available. The method id `entra-verified-id` is reserved for
it, and the Portal refuses to start with it in the list.

Sending codes and checking codes or tokencodes are rate-limited per operator and per user
(`rsa.rate-limit.helpdesk-code.*`); over a limit the operator gets 429 and nothing is sent. See
[Security → Rate limits](security.md#rate-limits-and-uniform-answers).

With `rsa.helpdesk.verification.required=false`, step 2 is skipped and the change is recorded with
`verificationMethod: none`. The Portal logs a warning about it at every start.

## Approval by a second operator

A request needs approval when the user is **privileged**, or when it is the operator's own account.
By default (`rsa.helpdesk.dual-control.privileged-roles`) a user is privileged if they hold the PMO,
Trusted Agent or Help Desk role, or any Authentication Manager administrative role the Portal can
see (`ANY_ADMIN_ROLE`). Administrative role GUIDs (`ims....`) can be listed too. If the user's roles
can't be read, the user is treated as privileged.

The request then waits on **Help-Desk Requests** (`/helpdesk/requests`) under *Waiting for your
approval*, with who asked, the change, the ticket, the reason, how the user was verified, why
approval is needed and when it expires. An approver:

- holds one of `rsa.helpdesk.dual-control.approver-roles` (Trusted Agent or Help Desk by default;
  PMO can be added);
- is not the operator who asked, and is never the user the request is about;
- needs the license feature of the change.

**Approve** or **Reject**, with an optional comment; a rejection needs one. Once approved, the
operator who asked runs it from the request's page.

**Expiry.** A request that was never sent for approval or run expires 24 hours after it was opened.
A request waiting for approval, or approved but not run, expires 24 hours after it was sent for
approval (`rsa.helpdesk.dual-control.request-ttl`). A request that is already running is not expired
by that limit: it is marked failed only if it is still running one hour after its change began, for
example because the Portal stopped during the change. The `am.change-outcome` event then says
whether the change was made.

## Emergency access codes

The code is shown once, behind an explicit **Reveal the code** step, on a page sent with
`Cache-Control: no-store`. Until then it is held only in the operator's own session, for at most
`rsa.helpdesk.emergency-code.reveal-within` (5 minutes by default, 30 at most). It is never written
to the database, a log or the audit trail. Revealing it is recorded as
`helpdesk.emergency-code-revealed`. Leaving or reloading the page does not bring the code back: a new
code needs a new request. The code itself stays valid in Authentication Manager for
`token.emergencyCode.validityInDays` days.

## The notice to the user

After the change, the user gets an e-mail saying what changed, when, which agent made it, the
ticket, and whom to contact if they did not ask for it. It replaces the earlier PIN-reset and
lost-token e-mails, whose templates (`user-token-pin-reset-notification.properties`,
`user-token-lost-notification.properties`) are no longer used. Its text is the `helpdesk.notice.*`
keys in `messages.properties`, which you can override like any other message (see
[Configuration → Branding, text and email templates](configuration.md#branding-text-and-email-templates)).
Whether it was sent is shown on the request as *User e-mailed*.

## Settings

The Portal checks these at startup and stops with the name of a setting that makes no sense. In the
release package they go in `.env`; with Helm, the five with a value below go under `helpDesk`, and
the others under `config`.

| Setting | Env var | Helm | Default | Meaning |
|---|---|---|---|---|
| `rsa.helpdesk.verification.required` | `RSA_HELPDESK_VERIFICATION_REQUIRED` | `helpDesk.verificationRequired` | `true` | Verify the user before every change. `false` skips verification and is logged as a warning at every start |
| `rsa.helpdesk.verification.methods` | `RSA_HELPDESK_VERIFICATION_METHODS` | `helpDesk.verificationMethods` | `email-code,tokencode,in-person` | The methods operators may use, in the order shown. While verification is required, an empty list or `tokencode` alone is refused at startup |
| `rsa.helpdesk.verification.valid-for` | `RSA_HELPDESK_VERIFICATION_VALIDFOR` | `config` | `15m` | How long after verification the request may be run or sent for approval |
| `rsa.helpdesk.verification.max-attempts` | `RSA_HELPDESK_VERIFICATION_MAXATTEMPTS` | `config` | `5` | Wrong codes before the request is closed (1 to 20) |
| `rsa.helpdesk.verification.email-code.length`, `.valid-for`, `.max-sends` | `RSA_HELPDESK_VERIFICATION_EMAILCODE_*` | `config` | `8`, `10m`, `3` | The e-mailed code: 6 to 10 digits; 1 to 10 sends per request |
| `rsa.helpdesk.dual-control.privileged-roles` | `RSA_HELPDESK_DUALCONTROL_PRIVILEGEDROLES` | `helpDesk.privilegedRoles` | `PMO,TRUSTED_AGENT,HELP_DESK,ANY_ADMIN_ROLE` | Whose changes need approval: Portal roles, `ANY_ADMIN_ROLE`, or administrative role GUIDs. Empty: only an operator's own account, logged as a warning |
| `rsa.helpdesk.dual-control.approver-roles` | `RSA_HELPDESK_DUALCONTROL_APPROVERROLES` | `helpDesk.approverRoles` | `TRUSTED_AGENT,HELP_DESK` | Who may approve. `PMO` may be added; it may not be empty |
| `rsa.helpdesk.dual-control.request-ttl` | `RSA_HELPDESK_DUALCONTROL_REQUESTTTL` | `helpDesk.requestTtl` | `24h` | How long a request waits for approval and to be run |
| `rsa.helpdesk.emergency-code.reveal-within` | `RSA_HELPDESK_EMERGENCYCODE_REVEALWITHIN` | `config` | `5m` | How long an emergency access code waits to be revealed; at most `30m` |

## What is recorded

Each step is audited with `details.helpDeskRequest`, the change, the ticket, the reason, the token
serial and the verification:

| Action | When |
|---|---|
| `helpdesk.request-opened` | A request is opened |
| `helpdesk.verification-code-sent` | An e-mail code is sent |
| `helpdesk.verification-succeeded`, `helpdesk.verification-failed` | A code, tokencode or in-person check. A failure that closes the request is an alert |
| `helpdesk.approval-requested` | The request waits for approval, with why |
| `helpdesk.request-approved`, `helpdesk.request-rejected` | A second operator decided, with their comment |
| `helpdesk.request-cancelled`, `helpdesk.request-expired` | The request was cancelled, or its time ran out |
| `helpdesk.request-completed`, `helpdesk.request-failed` | The change was made, or failed |
| `helpdesk.emergency-code-revealed` | An emergency access code was shown |
| `am.change-attempt`, `am.change-outcome` | Before and after the change in Authentication Manager. If the attempt can't be recorded, the change is not made |

The change itself is also recorded as before (`token.pin-reset`, `token.marked-lost`,
`token.deprovisioned`, `token.emergency-code-issued`, `user.windows-password-cleared`), and appears in
the user's **Activity Log**.
