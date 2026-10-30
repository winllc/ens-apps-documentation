# Privacy, DPIA and works council

ReadyRoom records when people sign in to, lock, unlock and sign out of their workstations, and
the statuses on their calendars. This page sets out, for a data protection impact assessment
(DPIA), a works council or a privacy review, what ReadyRoom stores about people, where, who can
see it, how long it is kept, and how it is exported and erased. It describes the software as it is
built; it is not legal advice.

The settings named here are described in
[Configuration → Statuses, requests and privacy](configuration.md#statuses-requests-and-privacy).

## What is stored, and where

Everything below is in ReadyRoom's PostgreSQL database unless the table says otherwise. People's
names, mail addresses, phone numbers, groups and managers come from your LDAP directory, which
remains the source of truth; ReadyRoom copies some of them, as listed.

### In the database

| Table | One row per | What it holds |
|---|---|---|
| `user_records` | Person. The hourly directory refresh creates one for every directory user, whether or not they ever sign in | DN; notes (free text, up to 255 characters, entered by the person); preferred login time (set by the person) and average login time (computed from the last 30 days); role; favourite groups; delegate DNs, and whether they await an administrator's review; organization, employee type, location, branch, duty sub-organization, phone and mail address, copied from the directory |
| `permission_records` | Group access granted inside ReadyRoom by an earlier release | The person and the group DN. No longer written or read: kept only so that the person's export lists them and erasure deletes them |
| `check_in_out_records` | Workstation event (logon, lock, unlock, logoff) | DN, time and action, and whether it was an automatic check-out; the Windows user id; the person's organization, employee type, location, branch and duty sub-organization at the time; the logoff session id (a credential, never exported) |
| `user_event_records` | Day with a calendar status | DN, date, status, late-arrival time, and the request it came from |
| `status_requests` | Request for a status that needs approval | Requester DN, status, dates, late-arrival time; the requester's note and the approver's comment (up to 500 characters each); state; who decided or cancelled it, and when |
| `notification_records` | Absence alert, one per recipient | Who it is about, who it went to, date, expected arrival time, when it was read and e-mailed, and the answer with who gave it and when (and the approved request that gave it, if one did) |
| `attendance_summaries`, `attendance_summary_statuses` | Person and month, once retention has summarised it | Days checked in, minutes in, days per status |
| `group_records` | Group with alternate managers set in ReadyRoom | The group DN and the managers' DNs |
| `audit_events` | Audited action | Actor DN, name and roles, source IP address and browser, target DN and name, action, outcome and details, and a hash of the session id. See [Audit logging](../audit-logging.md) |
| `audit_api_tokens` | SIEM pull API token | Who created and revoked it |
| `api_tokens`, `webhook_endpoints` | Public API token or webhook | The administrator who created it, and who revoked or disabled it |
| `webhook_deliveries` | Webhook delivery, sent or waiting, kept `APPLICATION_WEBHOOKS_DELIVERYLOGDAYS` (30) days | The event as sent: a `status.changed` or `absence.alert` names the person by DN and name, with the status and times |

!!! note "Free text"
    Profile notes, request notes and approvers' comments are free text. People can write anything
    in them, including sensitive reasons such as health, and nothing in ReadyRoom checks what they
    write: tell people what not to put there. Request emails and audit events never carry these
    texts.

### Outside the database

| Where | What |
|---|---|
| ReadyRoom's memory | Browser sessions; cached directory data (users, group trees, org chart), reloaded after 60 minutes and discarded after 120 by default |
| Application logs (container output, or your log collector) | Lines that name people by DN, for example notifications sent and delegates changed. Kept as long as your platform keeps logs |
| Audit outputs, when configured | Copies of the audit events on stdout, in a file, in Splunk or in syslog |
| Email | Absence alerts carry the person's name, the date and the expected arrival time. Request emails carry the person's name, the status and the dates, never the notes |
| Webhook receivers, when configured | Each [webhook](webhooks.md) an administrator adds receives the events it subscribes to: `status.changed` carries the person's DN and name with each check-in, lock, unlock and check-out and each calendar status; `absence.alert` the person, the date and the expected time. What the receiver keeps is up to its owner |
| Workstations | The Windows client's session file (`%ProgramData%\InOutWorker\sessions\<SID>.json`: the last logon's session id and Windows user id) and its daily logs (`%ProgramData%\InOutWorker\logs` and `%LOCALAPPDATA%\InOutWorker\logs`). The client does not delete its logs. See [Windows client](windows-client.md) |
| Database backups | `./backup.sh` or the Helm backup CronJob, kept 14 days by default, or your own backups |

## Who can see what

| Who | What they see |
|---|---|
| The person | All of their own data, and **My data** to download it |
| Their directory manager and their delegates | Their times, notes, check-in history and calendar, and their absence alerts. They decide the person's status requests, and may record a status for them |
| Managers of a group the person belongs to (the group's directory owner, or an alternate manager set in ReadyRoom) | Their times, notes, check-in history and calendar. Group managers with the `MANAGER` role can run group reports, which include check-in times and statuses. Group managers do not decide requests |
| Administrators (`ADMIN`) | Everything, including the audit log, any person's export, and erasure. **Administration → Privacy** lists who they are |
| The `MANAGER` role on its own | Organization-wide counts on the metrics page, never names |
| Coworkers | Name and directory details, and what `application.privacy.coworker-visibility` allows: by default whether the person is in and the day's status; with `status`, only in or out; with `none`, no status. Never times, notes or history |
| Recipients of an absence alert | That alert, and the answer to it |
| A status request and its note | The requester, the people who may decide it, and administrators |
| Outside ReadyRoom | Operators of the SIEM or log platform that receives audit copies or logs; recipients of emails; the systems webhooks are sent to |

Delegates are set only by administrators. The full rules are in
[Security → Authorization](security.md#authorization).

## How long it is kept

| Data | Kept for | Setting |
|---|---|---|
| Check-ins, calendar statuses, status requests, absence alerts | Forever by default; otherwise the number of days set (at least 31) | `APPLICATION_RETENTION_EVENTSDAYS` |
| Monthly summaries of purged check-ins and statuses | Until the person is erased. They have no retention of their own | – |
| User records | Until the person is erased. A person still in the directory gets an empty record back at their next sign-in or the next hourly directory refresh | – |
| Audit events | Forever by default; otherwise the number of days set, once every output has delivered them | `ENS_AUDIT_RETENTION_DAYS` |
| Database backups | 14 days by default | `BACKUP_RETENTION_DAYS`, Helm `backup.retentionDays` |
| Logs, SIEM copies, sent email, workstation logs | As your platform, SIEM, mail system and workstation management keep them | – |

A person's days are counted in their own time zone (their profile's, else their site's, else the
application's), as in the reports, so the cutoff falls at the start of the cutoff day where they
are. A status request still waiting for a decision when it is purged is first closed as cancelled,
audited as `calendar.request-cancelled` with reason `retention`, so it does not vanish undecided.
How the retention purge works, and what reports show for purged periods, is in
[Operations → Retention](operator-guide.md#retention).

## Export

- **The person:** **My data** (`/app/privacy`, in the avatar menu) shows what is kept, who sees it
  and for how long, and downloads it as a JSON file (`readyroom-personal-data/1`): profile, group
  access granted in ReadyRoom, whose delegate and which groups' alternate manager they are,
  check-ins (without the logoff session id), calendar statuses, status requests with their notes
  and comments, absence alerts about them, and monthly summaries.
- **An administrator, on the person's behalf:** **Administration → Privacy**, the same file.
- Each download is audited as `data.exported`, with who asked for it.
- **Audit events are not in the export.** An administrator exports them from the audit log page,
  filtered by actor or target.

Export works in every license state.

## Erasure

An administrator erases a person's data from **Administration → Privacy** (see
[Administration → Privacy](admin-guide.md#privacy-retention-export-and-erasure)). Nobody can erase
themselves. In one transaction it deletes the person's:

- user record and group access;
- check-ins, calendar statuses and status requests;
- absence alerts about them;
- monthly summaries;
- webhook deliveries about them, sent, given up or still waiting, so nothing naming them is sent
  afterwards;

and removes them from other people's delegates and from groups' alternate managers. It also stops
what still acts for them: the API tokens they created are revoked and the webhooks they created
disabled (audited as `api.token-revoked` and `webhook.disabled` with `details.via` `erasure`, by the
erasing administrator), and their browser sessions are ended (`auth.session-expired`, reason
`erased`), so no open session of theirs recreates their account.

Absence alerts sent to them, as a manager or delegate, are about other people: the date, the
expected time and the answer are that report's attendance record, in the report's own export and
in My Reports. So they are kept, with the person's DN replaced by `(erased)` as the recipient and,
on every copy of an alert they answered (an answer is copied to every manager and delegate told),
as the one who answered.

The erasure is audited as `person.erased`, with the counts (`absenceAlerts` and
`webhookDeliveries` removed, `apiTokensRevoked`, `webhooksDisabled` and `sessionsEnded`, and
`absenceAlertsKeptAnonymised` for the alerts sent to them that were kept) and whether the person is
still in the directory. Before erasing, the page lists these too: the webhook deliveries about the
person, their active API tokens and webhooks, and their open sessions.
Erasure works in every license state, including read-only.

### What erasure does not remove

- **Audit events.** The audit trail is append-only and hash-chained, so it cannot be edited. Its
  events name people by DN. Profile note changes are recorded without their text since the release
  that added status approvals; events from earlier releases still hold the old and new note text
  until audit retention removes them.
- **The person's DN in other people's records**, where they decided a request or, as an
  administrator, answered an alert sent to someone else.
- **Their revoked API tokens and disabled webhooks**, which still name them as owner. An
  administrator can delete the webhook.
- **Backups**, until they expire.
- **Logs, SIEM copies, sent email, and webhook deliveries already received** by their receivers.
- **Workstation files**: the client's session file and logs.
- **The LDAP directory.** ReadyRoom never writes to it.

!!! warning "Remove the person from the directory first"
    If the person is still in the directory, ReadyRoom creates their account again, empty, at their
    next sign-in or the next hourly directory refresh, and their workstation goes on reporting
    check-ins. The erasure page says when this applies.

## Checklist for a review

- Decide the coworker visibility: `full`, `status` or `none`.
- Decide how long to keep check-ins and statuses (`APPLICATION_RETENTION_EVENTSDAYS`), and the
  audit trail (`ENS_AUDIT_RETENTION_DAYS`) once your SIEM keeps the events.
- Decide which statuses need approval (`APPLICATION_EVENTS_REQUESTSTATUSES`).
- Set how long backups, logs and SIEM copies are kept, and the workstation logs.
- Review who holds the `ADMIN` role (the **Administrators** card on **Administration → Privacy**
  lists `application.super-user-dns` and stored roles alike) and who the delegates are.
- Review which webhooks send personal data where (**Administration → Integrations**).
- Tell people what is recorded, who sees it, and how to use **My data**.
