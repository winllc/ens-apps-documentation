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
and the ENS licensing portal.

## For administrators: the audit page

Each application has an **Audit log** page for its administrators:

| Application | Audit page | Who can open it |
|---|---|---|
| CertAlert | `/admin/audit` | Administrators (ADMIN) |
| Directory Services Portal | `/admin/audit` | Administrators |
| ReadyRoom | `/app/admin/audit` | Administrators (ADMIN) |
| RSA Operations Portal | `/admin/audit` | PMO |
| ENS licensing portal | `/admin/audit` | ENS staff administrators. Customers see their own account's events on their account page. |

The page lists events newest first. You can filter by:
- time;
- category, action, outcome and severity;
- user, target and source address;
- free text.

Open an event to see every field. From the page you can also:
- **Export** the current filter as CSV, for a spreadsheet, or NDJSON, for another tool or for
  [checking the chain](#checking-integrity). Exports are themselves audited.
- **Verify integrity**: recompute the trail's hash chain and say whether any event has been changed,
  removed or inserted.
- **Delivery status**: see whether each output (Splunk, syslog) is keeping up, how far behind it is, and
  its last error.
- **API tokens**: create and revoke the read-only tokens a SIEM uses to pull events.

The audit page stays available while the application is read-only or unlicensed, so the trail can always
be reviewed and exported.

## What is recorded

| Category | Examples |
|---|---|
| `authentication` | `auth.login` (success and failure, with the method: password, LDAP, smart card, API token; a refused client certificate gives its reason, such as `certificate revoked` or `revocation unknown`), `auth.logout`, `auth.lockout`, `auth.consent-acknowledged` (the notice-and-consent banner, when it is on), `auth.session-expired` (with the reason `idle`, `absolute` or `concurrent`), `auth.rate-limited` (the first refusal in a rate-limit window or backoff, RSA Operations Portal), `auth.self-service-failed` (a failed anonymous self-service step with the cause the visitor is not told, RSA Operations Portal), password changes and resets |
| `authorization` | `access.denied`: every refused request, with the reason (role, not licensed, read-only, feature not licensed, CSRF). Repeats within a minute are counted in one event. |
| `account` | Users and accounts created, changed, disabled or deleted; roles and permissions granted or revoked; a person's data erased (`person.erased`, ReadyRoom); help-desk requests (`helpdesk.*`, RSA Operations Portal) |
| `configuration` | `settings.changed` with each changed setting's old and new value; branding, templates, schedules, connectors |
| `license` | `license.installed`, `license.rejected`, `license.mode-changed`, `license.limit-exceeded` |
| `data` | The product's own objects created, changed or deleted; `data.exported` for every report or download, a person's data export included; bulk uploads; `data.purged` for each retention run (ReadyRoom); status requests (`calendar.request-created`, `-approved`, `-rejected`, `-cancelled`, ReadyRoom); every Authentication Manager change before and after it is made (`am.change-attempt`, `am.change-outcome`), `token.expired-unassigned` and `appointment.request-refused` (RSA Operations Portal). Free-text notes are never recorded: a profile note change is recorded as `notesChanged` |
| `system` | `system.started`, `system.stopped`, and `job.completed` / `job.failed` / `job.skipped` for each scheduled job run |
| `audit` | The trail itself: `audit.exported`, `audit.verified`, `audit.token-created`, `audit.token-revoked`, `audit.pruned`, `audit.delivery-failed`, `audit.delivery-recovered` |

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
  `appointment.request-refused` (`unknown_user`, `user_has_token`, `too_many_open` or
  `authmanager_error`) hold the causes the visitor is not told. See
  [Rate limits and uniform answers](rsa-operations-portal/security.md#rate-limits-and-uniform-answers).

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
| `seq` | Position in this installation's trail: 1, 2, 3, ... with no gaps. |
| `time` | When it happened, UTC. |
| `product`, `version`, `host`, `installationId` | Which installation recorded it. |
| `category`, `action` | What happened. Action ids are stable and never renamed. |
| `outcome` | `success`, `failure` or `denied`. |
| `severity` | `info`, `notice` (changes), `warning` (failures, denials) or `alert` (lockouts, a broken chain, audit delivery failing). |
| `actor` | Who: `type` is `user`, `service` (API token, client certificate), `system` (scheduler) or `anonymous`. On a failed sign-in, `id` is the name that was tried. |
| `source` | Client address and user agent. |
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

## Sending events to Splunk or another SIEM

There are four ways to get events out. Use whichever suits your network; they can be combined.

| Way | Best for | Setting |
|---|---|---|
| **Splunk HTTP Event Collector** | Splunk reachable from the application | `ENS_AUDIT_SPLUNK_URL`, `ENS_AUDIT_SPLUNK_TOKEN` |
| **Syslog** (RFC 5424, UDP, TCP or TLS) | Any SIEM, Splunk Connect for Syslog, rsyslog | `ENS_AUDIT_SYSLOG_HOST` |
| **Audit file or container log** | A Splunk Universal Forwarder (UF) or Docker logging driver already on the host | `ENS_AUDIT_FILE`, `ENS_AUDIT_STDOUT` |
| **Pull API** | A collector that reaches in, rather than the application reaching out | API token from the audit page |

**HEC and syslog delivery is reliable:**
- Every event is stored in the application first.
- A background sender delivers events from the store and remembers how far it has got, across restarts.
- When Splunk or the syslog server is unreachable, events queue in the application and are sent once it
  is back. Requests are never slowed or refused because of it.
- If delivery has been failing for 15 minutes, the application records an `audit.delivery-failed`
  alert; the audit page's delivery status shows the backlog.

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
| `ENS_AUDIT_SYSLOG_PROTOCOL` | `tcp` | `udp`, `tcp` or `tls` |
| `ENS_AUDIT_SYSLOG_FACILITY` | `local0` | Syslog facility |
| `ENS_AUDIT_RETENTION_DAYS` | `0` (keep forever) | Remove events older than this. Only events every enabled output has delivered are removed, and each removal is itself audited. |
| `ENS_AUDIT_QUERY_MAX_SCAN` | `2000000` | Directory Services Portal only, which keeps its trail in files: the most events one search or export on the audit page reads. The page says when a search stopped at this limit. |

Splunk's certificate is checked against the JVM trust store for the Java applications, and Node's
(plus `NODE_EXTRA_CA_CERTS`) for the licensing portal. Add your internal CA there.

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
- **Availability:** the API works while the application is read-only or unlicensed.

## Retention

By default nothing is removed. To limit how long events are kept, set `ENS_AUDIT_RETENTION_DAYS`. A
daily job then removes older events, with three safeguards:
- it only removes events that every enabled output has already delivered;
- it never removes the newest event;
- it records an `audit.pruned` event with the count and range.

Keep the application's audit trail at least as long as your policy requires if Splunk is not your
system of record.

CertAlert also keeps its own `cert-alert.audit.retention.*` settings, which apply while
`ENS_AUDIT_RETENTION_DAYS` is `0`; its retention job runs daily at 03:30 UTC. See
[CertAlert configuration](cert-alert/configuration.md#audit-trail).
