# Audit logging

Every application in the suite keeps an audit trail of who did what, from where, and whether it worked:
- sign-ins;
- refused requests;
- changes to users, roles, settings and data;
- the license;
- scheduled jobs;
- exports.

The trail is the same in every product: one event format, one set of settings, one Splunk add-on. The
applications covered are CertAlert, the Directory Services Portal, ReadyRoom, the RSA Operations Portal,
syncd (from 0.4.0; its console's trail, `product` `syncd`) and the ENS licensing portal.

Where this page says a behaviour arrived in CertAlert 0.0.4, the first CertAlert release package
with it is 0.0.5: 0.0.4 has no download (see
[Releases and downloads](releases.md#versions-without-a-download)).

!!! note "syncd"
    syncd's console joined the format in 0.4.0. Entries written before the upgrade keep their older
    chain; at the first start the console records `audit.format-upgraded`, and the first new event
    continues the old chain's head, so **Verify integrity** checks both. Exports and the pull API carry
    only `ens-audit/1` events. Its audit page lists events, filters them by time, category, action,
    actor and outcome (from 0.5.0), verifies the chain (also against a recorded head, which finds
    entries removed from the end), shows each output's delivery and exports NDJSON and, from 0.5.0,
    CSV. From 0.5.0 sign-in events carry `roles` (a list of assignments) instead of `role`, and
    events carry `details.assignment`, the role assignment that allowed the action: update SIEM
    parsers that read `role`. Auditors whose role covers one environment or engine see its
    entries. From 0.5.1 it records `access.denied` for a refused request, `wasm_module.upload`,
    `wasm_module.approve` and `wasm_module.download` are in the ENS action table, and retention
    records `audit.pruned` with the deletion, so **Verify integrity** flags a gap no `audit.pruned`
    explains. Pull-API tokens are made on its **Integrations** page with
    the `audit:read` scope. See [syncd's admin guide](syncd/admin-guide.md).

## For administrators: the audit page

Each application has an **Audit log** page for its administrators:

| Application | Audit page | Who can open it |
|---|---|---|
| CertAlert | `/admin/audit` | Administrators (ADMIN) |
| Directory Services Portal | `/admin/audit` | Administrators |
| ReadyRoom | `/app/admin/audit` | Administrators (ADMIN) |
| RSA Operations Portal | `/admin/audit` | PMO |
| syncd | `/audit` (**Audit log**) | Admins and auditors |
| ENS licensing portal | `/admin/audit` | ENS staff administrators. Customers see their own account's events on their account page. |

The page lists events newest first. You can filter by:
- time;
- category, action, outcome and severity;
- user, target and source address;
- free text.

Open an event to see every field. From the page you can also:
- **Export** the current filter as CSV, for a spreadsheet, or NDJSON, for another tool or for
  [checking the chain](#checking-integrity). Exports are themselves audited. The RSA Operations
  Portal's CSV (from 0.0.2) gives a value starting with `=`, `+`, `-`, `@`, a tab or a carriage
  return a leading `'`, so that a spreadsheet does not run it as a formula; NDJSON keeps every value
  exactly.
- **Verify integrity**: recompute the trail's hash chain and say whether any event has been changed,
  removed or inserted, at the start and the end of the trail included (see
  [What Verify integrity checks](#what-verify-integrity-checks)).
- **Delivery status**: see whether each output (Splunk, syslog) is keeping up, how far behind it is, and
  its last error.
- **API tokens**: create and revoke the read-only tokens a SIEM uses to pull events.

The audit page stays available while the application is read-only or unlicensed, so the trail can always
be reviewed and exported.

## What is recorded

| Category | Examples |
|---|---|
| `authentication` | `auth.login` (success and failure, with the method: password, LDAP, smart card, API token; a refused client certificate gives its reason, such as `certificate revoked` or `revocation unknown`; a wrong or revoked [pull-API](#the-pull-api) token is a failure with method `api-token`; ReadyRoom from 0.0.11 records a password sign-in refused by its limit on failed sign-ins as a failure `too many failed sign-ins`; the RSA Operations Portal from 0.0.3 records a sign-in that failed because the Connector or Authentication Manager could not answer as `service_error`), `auth.logout`, `auth.lockout`, `auth.consent-acknowledged` (the notice-and-consent banner, when it is on; CertAlert from 0.0.4 records it once per session), `auth.session-expired` (with the reason `idle`, `absolute` or `concurrent`; in ReadyRoom also `account`, and `erased` for the sessions an erasure ends; in the Directory Services Portal also `account-removed` and, from 0.0.9, `account-disabled`), `auth.rate-limited` (the first refusal in a rate-limit window or backoff, RSA Operations Portal), `auth.self-service-failed` (a failed anonymous self-service step with the cause the visitor is not told, RSA Operations Portal), password changes and resets |
| `authorization` | `access.denied`: every refused request, with the reason (role, not licensed, read-only, feature not licensed, CSRF). The first is recorded at once; repeats within the next minute are counted in one more event (see [Repeated refusals](#repeated-refusals)). |
| `account` | Users and accounts created, changed, disabled or deleted; roles and permissions granted or revoked (ReadyRoom no longer records `account.permission-granted` or `-revoked`: its per-user group grants were removed, and these actions remain only in older events); a person's data erased (`person.erased`, ReadyRoom, with what it deleted and stopped: `webhookDeliveries`, `apiTokensRevoked`, `webhooksDisabled`, `sessionsEnded`); help-desk requests (`helpdesk.*`, RSA Operations Portal); group access requests and membership end dates (`group.access-*`, `group.membership-*`, Directory Services Portal, [below](#directory-services-portal-group-access)) |
| `configuration` | `settings.changed` with each changed setting's old and new value; branding, templates, schedules, connectors |
| `license` | `license.installed`, `license.rejected`, `license.mode-changed`, `license.limit-exceeded` |
| `data` | The product's own objects created, changed or deleted; `data.exported` for every report or download, a person's data export included; bulk uploads; `data.purged` for each retention run (ReadyRoom); status requests (`calendar.request-created`, `-approved`, `-rejected`, `-cancelled`, ReadyRoom; a request the retention purge closes is `-cancelled` with actor `system` and reason `retention`); a person's working days or start date changed (`person.schedule-changed`, ReadyRoom, with the old and new values); an administrator's correction to a check-in record (`record.voided`, `record.annotated`, `record.added`, ReadyRoom, with the reason or the note as the event's `reason`, the record, its time and its day); an automatic check-out made for another cause than a lock reaching its time limit (`record.auto-checked-out`, ReadyRoom, with the cause as the event's `reason`: `early leave at HH:mm`, or from 0.0.11 `end of day` for a session left open at the end of its day, and with `details.checkedOutAt` and `details.lockedAt` or `details.lastSeenAt`); a scheduled report sent (ReadyRoom) is a `data.exported` with its owner as the actor and `details.via` `scheduled`; every Authentication Manager change before and after it is made (`am.change-attempt`, `am.change-outcome`), `token.expired-unassigned` and `appointment.request-refused` (RSA Operations Portal). Free-text notes are never recorded: a profile note change is recorded as `notesChanged`. The exception is ReadyRoom's corrections to check-in records, whose events keep the administrator's reason or note |
| `system` | `system.started`, `system.stopped`, and `job.completed` / `job.failed` / `job.skipped` for each scheduled job run. From 0.0.3 the RSA Operations Portal records `job.failed`, not `job.completed`, for a job that ran but could do none of its work (the Connector unreachable, every e-mail refused), so an alert on `job.failed` covers those nights too |
| `audit` | The trail itself: `audit.exported`, `audit.verified`, `audit.token-created`, `audit.token-revoked`, `audit.pruned`, `audit.delivery-failed`, `audit.delivery-recovered`; `audit.chain-failed` (ReadyRoom, from 0.0.6: an event that could not be added to the chain was set aside, and this event takes its place in it, outcome `failure`, severity `alert`, naming the event's id, its recorded time and the SHA-256 of its stored form) |

**RSA Operations Portal: Authentication Manager changes and help-desk requests.**

- `am.change-attempt` is stored and committed **before** every change the Portal makes in RSA
  Authentication Manager. If it can't be stored, the change is not made (fail closed). A change
  asked for inside a database transaction that has already recorded an event is refused as well,
  since its attempt could not be committed first.
  `am.change-outcome` follows with `success`, or `failure` and the reason. Both carry the actor (the
  operator; the user, for their own token; or `system` for scheduled jobs), the target user,
  `details.tokenSerial`, `details.operation`, `details.amAccount` (`operator` or
  `service-account`), the help-desk `details.ticket` and `details.reason`,
  `details.verificationMethod` and `details.verificationResult`, the client address, and
  `details.attemptId`, which links the two.
- Help-desk requests: `helpdesk.request-opened`, `helpdesk.verification-code-sent`,
  `helpdesk.verification-succeeded`, `helpdesk.verification-failed` (an alert when it closes the
  request), `helpdesk.approval-requested`, `helpdesk.request-approved`, `helpdesk.request-rejected`,
  `helpdesk.request-cancelled`, `helpdesk.request-expired`, `helpdesk.request-completed`,
  `helpdesk.request-failed` and `helpdesk.emergency-code-revealed`, each with
  `details.helpDeskRequest`. See [Help-desk requests](rsa-operations-portal/help-desk.md).
- `token.expired-unassigned`: an expired token taken back from a user, in the Trusted Agent's own AM
  session, before the agent assigns a new one.
- `auth.rate-limited` (with `details.flow`, `details.limit` and `details.retryAfterSeconds`),
  `auth.self-service-failed` (with `details.flow` and the reason) and
  `appointment.request-refused` (`unknown_user`, `user_has_token`, `too_many_open`,
  `authmanager_error` and, from 0.0.3, `slot_not_available` when someone else booked the time at
  the same moment, or `error`) hold the causes the visitor is not told. See
  [Rate limits and uniform answers](rsa-operations-portal/security.md#rate-limits-and-uniform-answers).

### Directory Services Portal: group access

Group access (from 0.0.7) records its requests in the `account` category; the membership change
itself is always the usual `entry.update`.

| Action | Recorded when | Details |
|---|---|---|
| `group.access-requested`, `group.access-cancelled` | Someone asks to join a group, or withdraws the request | The group as the target |
| `group.access-approved`, `group.access-rejected` | An owner decides a request | The group as the target |
| `group.access-expired` (from 0.0.11) | The portal closes a request nobody answered within the definition's number of days. Actor `system` | `details.request`, `details.requester`, `details.afterDays` |
| `group.membership-end-date` (from 0.0.11) | An owner sets, changes or removes a membership's end date, or adds members with one | `details.member` with `until.old` and `until.new`; for several members added at once, `details.members`, `details.count` and `until.new` |
| `group.membership-expired` (from 0.0.11) | The portal removes a member whose end date has passed. Actor `system`. A removal the directory refused is recorded with outcome `failure`, and tried again a day later | `details.member`, `details.until` |

On a membership's `entry.update`, `details.via` says why someone without write access to the
directory could make the change:

| `details.via` | The change was made by |
|---|---|
| `group-access-request` | An approved request |
| `group-owner` | An owner adding or removing a member |
| `group-leave` | A member leaving |
| `group-join` (from 0.0.11) | Someone joining an open group, without approval |
| `group-owners` (from 0.0.11) | An owner changing the group's owners |
| `group-membership-expired` (from 0.0.11) | The portal, after the member's end date |

An owner's CSV export of a group's members (from 0.0.11) is a `data.exported`. SIEM rules that
list the `group.*` actions or the `details.via` values need the new ones added.

### Repeated refusals

A page that polls an address it may not use, or a client that retries a wrong token, would fill the
trail with identical events. CertAlert, the Directory Services Portal, ReadyRoom and the RSA
Operations Portal therefore collapse them:

- The **first** refusal is recorded at once, with `details.count` `1`. (CertAlert before 0.0.4
  recorded nothing until the minute was over.)
- Identical refusals in the next 60 seconds are only counted. When the minute is over, they are
  recorded as **one more event** with `details.count` (how many were counted) and
  `details.collapsed` `true`. ReadyRoom adds `details.windowStart`, the Directory Services Portal
  `details.firstAt` and `details.lastAt`, and the RSA Operations Portal `details.window.start` and
  `details.window.end`.
- "Identical" means the same actor, the same path and the same reason. The RSA Operations Portal,
  from 0.0.3, collapses an anonymous caller's refusals by client address and reason, whatever the
  path.
- From CertAlert 0.0.4, the Directory Services Portal 0.0.9, ReadyRoom 0.0.11 and the RSA
  Operations Portal 0.0.3, each instance keeps at most **10,000** such counts open at once. Beyond
  that, a new refusal is collapsed per actor and reason whatever its path, and its events carry
  `details.anyPath` `true`. A client walking through made-up paths can no longer use up the
  application's memory, and from then on it gets one event a minute rather than one per path.

To count refusals in a SIEM, add up `details.count`; do not count events. Counts are kept by each
instance, so with several replicas each records its own events.

A wrong or revoked token on the [pull API](#the-pull-api) is collapsed the same way, per client
address. So are, in ReadyRoom, the Windows client's failed sign-ins. From 0.0.3 the RSA Operations
Portal also limits integration API calls refused for want of a valid token, per client address
(`rsa.rate-limit.sign-in.per-ip`): past the limit they are answered `429` and no longer recorded
one by one.

**Secrets are never recorded.** That covers passwords, tokens, keys, license file contents, session ids,
one-time and emergency codes, PINs and SecurID tokencodes. Where one would appear, the event says
`[redacted]`.

## The event format

Each event is one JSON object, `ens-audit/1`, identical in every product and in every output:

```json
{"schema":"ens-audit/1","id":"6f1c...","seq":1234,"time":"2026-10-16T02:10:11.123Z",
 "product":"certalert","version":"1.4.0","host":"certalert-7d9f","installationId":"3f2a...",
 "category":"authentication","action":"auth.login","outcome":"failure","severity":"warning",
 "actor":{"type":"anonymous","id":"jdoe","name":null,"roles":[]},
 "source":{"ip":"10.1.2.3","userAgent":"Mozilla/5.0 ..."},
 "target":null,"reason":"bad credentials","details":{"method":"password"},
 "requestId":"...","session":null,"prevHash":"9b0e...","hash":"41c7..."}
```

| Field | Meaning |
|---|---|
| `id` | Unique id. Use it to drop duplicates: outputs deliver at least once. |
| `seq` | Position in this installation's trail: 1, 2, 3, ... with no gaps. The trail's order. |
| `time` | When it happened, UTC. Close to `seq` order but not strictly in it: events recorded at almost the same moment can be numbered in a different order from their times (in ReadyRoom from 0.0.6, by up to about a second). Sort by `seq`. |
| `product`, `version`, `host`, `installationId` | Which installation recorded it. |
| `category`, `action` | What happened. Action ids are stable and never renamed. |
| `outcome` | `success`, `failure` or `denied`. |
| `severity` | `info`, `notice` (changes), `warning` (failures, denials) or `alert` (lockouts, a broken chain, audit delivery failing). |
| `actor` | Who: `type` is `user`, `service` (API token, client certificate), `system` (scheduler) or `anonymous`. On a failed sign-in, `id` is the name that was tried. |
| `source` | Client address and user agent. Behind a reverse proxy or ingress this is the proxy's address unless the application is set up to take the client's from `X-Forwarded-For`. Two applications changed how in this round of releases: CertAlert, from 0.0.4 and with `SERVER_FORWARD_HEADERS_STRATEGY=framework`, takes the rightmost hop that is not in `ENS_X509_TRUSTED_PROXIES`, and records the proxy's address until that list names the proxy in front; the Directory Services Portal, from 0.0.9, takes it from the proxies in `WEB_TRUSTED_PROXIES`. See [CertAlert](cert-alert/configuration.md) and [Directory Services Portal](directory-services-portal/configuration.md) configuration. |
| `target` | What was acted on: `type`, `id`, `name`. |
| `reason`, `details` | Why it failed; what changed (`<field>.old` / `<field>.new`), counts, filters. |
| `session` | A hash of the session id, so one session's events can be grouped without exposing it. |
| `prevHash`, `hash` | The integrity chain. |

### Checking integrity

Each event's `hash` is the SHA-256 of the previous event's hash, a newline, and the event's own JSON
without its `hash` field:
- changing, removing or inserting an event breaks the chain from that point;
- the application's **Verify integrity** button reports where.

An auditor can check an export or audit file independently, without access to the application. Use
`tools/audit/verify_chain.py` in this repository:

```bash
python3 tools/audit/verify_chain.py audit-export.ndjson
# INTACT: certalert seq 1..48211, 48211 events, from the first event
```

After retention has removed old events, the check starts from the oldest event still kept and says so.
The script sees only the file it is given: it cannot tell whether events are missing before the
first line or after the last. The application's own check can (below).

The check orders events by `seq` and never compares times, so it works the same for every product and
release, including ReadyRoom's from 0.0.6, which adds events to the chain shortly after
recording them (see [Sending events](#sending-events-to-splunk-or-another-siem)).

### What Verify integrity checks

**Verify integrity** on the audit page walks every event the application still holds and checks
three things. This section covers CertAlert, the Directory Services Portal, ReadyRoom and the RSA
Operations Portal; for syncd's check see the note at the top of this page.

1. **The start.** The oldest event must be `seq` 1, chained from 64 zeros, or the event right after
   the last one [retention](#retention) deleted, chained to that event's hash. Retention records
   that `seq` and hash each time it deletes. Events removed from the start of the trail any other
   way (rows deleted from the database, a month file removed) are reported, for example "seq 1 to
   4711 are missing; retention has pruned nothing", or "… pruned only up to seq 3000". The wording
   differs a little between the applications.
2. **Every link.** Each event follows the one before it without a gap, names its hash as
   `prevHash`, and hashes to what it says. A changed event is reported at its own `seq`, a removed
   one at the next.
3. **The end.** In CertAlert, ReadyRoom and the RSA Operations Portal the walk must reach the
   chain head: the `seq` and hash of the newest event, which the application keeps in a row of its
   own, apart from the events. Events removed from the end are reported, for example "seq 9001 to
   9050 are missing (the chain head is at seq 9050)".

| Application | The start is checked | The end is checked | Where retention records how far it deleted |
|---|---|---|---|
| CertAlert | From 0.0.4 | From 0.0.4 | The database, with the chain head |
| Directory Services Portal | From 0.0.9 | Not independently (below) | The file `audit-pruned` next to the audit store's monthly files, or the shared database |
| ReadyRoom | From 0.0.11 | From 0.0.11 | The database, with the chain head |
| RSA Operations Portal | From 0.0.3 | In earlier releases too | The database, with the chain head |

Before these releases an application accepted whatever its oldest event was as "the first one
after pruning", so events deleted from the start by hand still verified as intact; CertAlert and
ReadyRoom did not notice events deleted from the end either.

The **Directory Services Portal** keeps no separate record of its newest event: the head is the
last event in the store (read from the files at start, or from the database). Its check compares
the walk with that, so events removed from the end of its store are not reported, except from the
files while the portal that wrote them is still running. Forward its trail to a SIEM or pull it
with the API if the end of the trail must be provable: a `seq` the SIEM holds and the portal does
not is the evidence.

**An installation that had already pruned before upgrading** has no recorded hash for the last
event deleted. Nothing needs doing:

- CertAlert, ReadyRoom and the RSA Operations Portal: the first start of the new release notes the
  `seq` before the oldest event as where retention stopped, provided the trail holds an
  `audit.pruned` event. The oldest event's `prevHash` is taken as given until retention next
  deletes; from then on the hash is recorded and checked.
- Directory Services Portal: the oldest event is accepted as long as an `audit.pruned` event is in
  the trail; the record is written the next time retention deletes.

If **Verify integrity** reports missing events at the start right after the upgrade, the trail
begins after `seq` 1 and holds no `audit.pruned` event: its first events were removed by something
other than retention. Keep the result and the oldest export or SIEM copy you have, and compare
them; the application cannot restore what was removed.

Because the check now covers both ends, **never trim the trail by hand** to save space. Set
[`ENS_AUDIT_RETENTION_DAYS`](#retention), which records what it deletes. Restore the audit store
only as a whole (see
[Sizing, availability and recovery](availability-and-sizing.md#backups-and-disaster-recovery)).

## Sending events to Splunk or another SIEM

There are four ways to get events out. Use whichever suits your network; they can be combined.

| Way | Best for | Setting |
|---|---|---|
| **Splunk HTTP Event Collector** | Splunk reachable from the application | `ENS_AUDIT_SPLUNK_URL`, `ENS_AUDIT_SPLUNK_TOKEN` |
| **Syslog** (RFC 5424, UDP, TCP or TLS) | Any SIEM, Splunk Connect for Syslog, rsyslog | `ENS_AUDIT_SYSLOG_HOST` |
| **Audit file or container log** | A Splunk Universal Forwarder (UF) or Docker logging driver already on the host | `ENS_AUDIT_FILE`, `ENS_AUDIT_STDOUT` |
| **Pull API** | A collector that reaches in, rather than the application reaching out | API token from the audit page |

**HEC and syslog delivery is queued and retried:**
- Every event is stored in the application first. ReadyRoom, from 0.0.6, stores it with the
  action it records and adds it to the hash chain within about a second; only then is it on the audit
  page, in exports and the pull API, and sent to any output (stdout, the audit file, HEC, syslog).
- A background sender delivers events from the store and remembers how far it has got, across restarts.
- When Splunk or the syslog server is unreachable, events queue in the application and are sent once it
  is back. Requests are never slowed or refused because of it.
- If delivery has been failing for 15 minutes, the application records an `audit.delivery-failed`
  alert; the audit page's delivery status shows the backlog.

**How sure each way is.** Splunk HEC answers every batch with success or an error, and the pull
API hands the collector the `seq` it has reached, so with either of them no event goes missing
unnoticed. Syslog has no acknowledgement in any of its three protocols. For CertAlert, the
Directory Services Portal, ReadyRoom and the RSA Operations Portal:

| Protocol | What can be lost | What can arrive twice |
|---|---|---|
| `udp` | Any datagram, silently | Nothing |
| `tcp`, `tls` | Events written just before the collector or the network dropped the connection, which the sender cannot always tell | Up to one batch after a reconnect (same event `id`) |

From CertAlert 0.0.4, the Directory Services Portal 0.0.9, ReadyRoom 0.0.11 and the RSA Operations
Portal 0.0.3, the `tcp` and `tls` sender no longer trusts a connection it has not checked:

- before each batch it checks that the collector has not closed the connection, and it opens a new
  one when the old one has been unused for 30 seconds (a firewall may have dropped it);
- when a connection turns out to be broken, the batch last written on it is sent again on the next
  one, so a collector restart can repeat up to one batch. De-duplicate on `id`;
- a batch the collector does not take within 30 seconds (10 seconds in the Directory Services
  Portal) fails, the connection is closed, and the batch is retried. A collector that stops
  reading no longer stalls delivery for good.

Earlier releases kept one long-lived connection and counted a batch as delivered once it was
written, so the first batch after a collector restart or an idle disconnect could be lost with no
error and no backlog. Syslog over `tcp` or `tls` is still **best effort**: where no event may be
missing, use Splunk HEC or the pull API, or compare `seq` in the SIEM for gaps. Retention counts a
syslog batch as delivered once it is written, so set
[`ENS_AUDIT_RETENTION_DAYS`](#retention) with that in mind.

### Settings

Set these in the application's environment: the release bundle's `.env`, or the container's
environment. Spring-based applications also accept them as `ens.audit.*` properties.

| Variable | Default | Meaning |
|---|---|---|
| `ENS_AUDIT_STDOUT` | `true` | Write each event as a raw JSON line on the `ens.audit` log stream |
| `ENS_AUDIT_FILE` | *(off)* | Also append to this file, for example `/var/log/ens/<product>/audit.jsonl`. Rotated daily or at 100 MB. |
| `ENS_AUDIT_FILE_KEEP` | `30` | Days of rotated files to keep |
| `ENS_AUDIT_SPLUNK_URL` | *(off)* | HEC base URL, for example `https://splunk.example.com:8088` |
| `ENS_AUDIT_SPLUNK_TOKEN` / `ENS_AUDIT_SPLUNK_TOKEN_FILE` | | HEC token, or a file holding it |
| `ENS_AUDIT_SPLUNK_INDEX` | *(token's default)* | Index to write to |
| `ENS_AUDIT_SPLUNK_SOURCETYPE` | `ens:audit` | Sourcetype |
| `ENS_AUDIT_SPLUNK_INSECURE` | `false` | Skip TLS certificate checks. **Test systems only.** |
| `ENS_AUDIT_SYSLOG_HOST` | *(off)* | Syslog server |
| `ENS_AUDIT_SYSLOG_PORT` | `514` (`6514` for TLS) | Port |
| `ENS_AUDIT_SYSLOG_PROTOCOL` | `tcp` | `udp`, `tcp` or `tls`. None is acknowledged: see [how sure each way is](#sending-events-to-splunk-or-another-siem) |
| `ENS_AUDIT_SYSLOG_FACILITY` | `local0` | Syslog facility |
| `ENS_AUDIT_RETENTION_DAYS` | `0` (keep forever) | Remove events older than this. Only events every enabled output has delivered are removed, and each removal is itself audited. |
| `ENS_AUDIT_CHAIN_MILLIS` | `500` | ReadyRoom only, from 0.0.6: how often, in milliseconds, each instance looks for recorded events still to be added to the chain. See [ReadyRoom configuration](readyroom/configuration.md#audit-trail). |
| `ENS_AUDIT_QUERY_MAX_SCAN` | `2000000` | Directory Services Portal only, which keeps its trail in files: the most events one search or export on the audit page reads. The page says when a search stopped at this limit. |

Splunk's certificate is checked against the JVM trust store for the Java applications, Node's
(plus `NODE_EXTRA_CA_CERTS`) for the licensing portal, and the public root certificates for syncd.
syncd's console reads the settings from `/etc/syncd/console.env` (or the Helm chart's `console.env`)
and also takes `ENS_AUDIT_DELIVERY_ALERT_SECONDS` (`900`), the delay before `audit.delivery-failed`. Add your internal CA there.

### Splunk setup

1. **Install the add-on.** `tools/splunk/TA-ens-audit` in this repository is a Splunk add-on:
   - it parses the `ens:audit` sourcetype (JSON fields, timestamps);
   - it maps events onto the CIM **Authentication** and **Change** data models, plus access-denied events
     tagged `access`;
   - it defines event types such as `ens_audit_authentication` and `ens_audit_change`.

   Copy it to `$SPLUNK_HOME/etc/apps/` on your search heads and on any indexer or heavy forwarder that
   receives events, then restart Splunk.
2. **Choose a way in:**

    === "HTTP Event Collector"

        Create a HEC token (Settings → Data inputs → HTTP Event Collector) with sourcetype `ens:audit` and
        your audit index, then set on each application:

        ```bash
        ENS_AUDIT_SPLUNK_URL=https://splunk.example.com:8088
        ENS_AUDIT_SPLUNK_TOKEN_FILE=/run/secrets/splunk-hec-token
        ENS_AUDIT_SPLUNK_INDEX=ens_audit
        ```

    === "Universal Forwarder"

        Set `ENS_AUDIT_FILE=/var/log/ens/<product>/audit.jsonl`. The release bundles keep `/var/log/ens`
        on the `ens-audit` volume. Point the forwarder at it:

        ```ini
        # inputs.conf on the forwarder
        [monitor:///var/lib/docker/volumes/<project>_ens-audit/_data/*/audit*.jsonl]
        sourcetype = ens:audit
        index = ens_audit
        ```

        Or read container logs with Docker's `splunk` logging driver. The `ens.audit` lines are raw JSON;
        filter on `"schema":"ens-audit/1"`.

    === "Syslog"

        Set `ENS_AUDIT_SYSLOG_HOST` (and `_PROTOCOL=tls`, `_PORT` as needed) to Splunk Connect for
        Syslog, rsyslog or syslog-ng in front of Splunk, or to a Splunk TCP/UDP input with sourcetype
        `ens:audit:syslog`. The add-on strips the syslog header and treats the rest as `ens:audit`.

    === "Pull API"

        On the application's audit page, create an API token and save it to a file only Splunk can read.
        Then enable a copy of the add-on's scripted input in `local/inputs.conf`:

        ```ini
        [script://$SPLUNK_HOME/etc/apps/TA-ens-audit/bin/ens_audit_pull.py https://certalert.example.com $SPLUNK_HOME/etc/apps/TA-ens-audit/local/certalert.token]
        disabled = false
        interval = 60
        sourcetype = ens:audit
        index = ens_audit
        ```

        The script remembers where it got to, so each run reads only new events.

3. **Search:**

    ```spl
    index=ens_audit sourcetype=ens:audit
    | stats count by product, category, ens_action, outcome

    tag=authentication action=failure app=certalert
    | stats count by user, src

    eventtype=ens_audit_integrity severity=alert
    ```

    The add-on keeps the product's own action id as `ens_action`, because the CIM uses `action` for the
    result (`success`/`failure` for sign-ins; `created`/`modified`/`deleted` for changes).

### The pull API

```
GET /api/audit/events?after=<seq>&limit=<1-1000>
Authorization: Bearer <API token>
```

- **Response:** events with `seq` greater than `after`, oldest first, as NDJSON. With
  `Accept: application/json` it returns `{"events":[...],"next":<seq>}` instead.
- **Paging:** start at `after=0`, then pass the last `seq` you received.
- **Tokens:** read-only, shown once when created, revocable on the audit page. Creating and revoking them
  is audited.
- **A wrong or revoked token** is answered `401` and recorded as a failed sign-in: `auth.login`,
  outcome `failure`, reason `invalid or revoked token`, `details.method` `api-token`, with the
  client's address and never the token. Repeats from one address are
  [collapsed](#repeated-refusals), so a collector left running with a revoked token costs a couple
  of events a minute, not one per request. ReadyRoom already recorded these; CertAlert does from
  0.0.4, the Directory Services Portal from 0.0.9 and the RSA Operations Portal from 0.0.3. Before
  that, in those three, a guessed or revoked token left no trace. A request with no token at all
  is answered `401` and not recorded. To alert on token guessing in Splunk:

    ```spl
    sourcetype=ens:audit ens_action=auth.login outcome=failure details.method=api-token
    | stats sum(details.count) AS attempts by product, src
    ```

- **Availability:** the API works while the application is read-only or unlicensed.

## Retention

By default nothing is removed. To limit how long events are kept, set `ENS_AUDIT_RETENTION_DAYS`. A
daily job then removes older events, with three safeguards:
- it only removes events that every enabled output has already delivered;
- it never removes the newest event;
- it records an `audit.pruned` event with the count and range;
- it records how far it deleted (the last `seq` removed and that event's hash), in the same step as
  the deletion, so that [Verify integrity](#what-verify-integrity-checks) can tell retention's
  deletions from any other. From CertAlert 0.0.4, the Directory Services Portal 0.0.9, ReadyRoom
  0.0.11 and the RSA Operations Portal 0.0.3.

Retention is the only supported way to shorten the trail. Rows or files deleted by hand are
reported by **Verify integrity** as missing events.

Keep the application's audit trail at least as long as your policy requires if Splunk is not your
system of record.

CertAlert also keeps its own `cert-alert.audit.retention.*` settings, which apply while
`ENS_AUDIT_RETENTION_DAYS` is `0`; its retention job runs daily at 03:30 UTC. See
[CertAlert configuration](cert-alert/configuration.md#audit-trail).
