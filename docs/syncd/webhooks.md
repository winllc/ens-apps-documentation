# Webhooks

From 0.4.0, the syncd console can tell other systems what happened by posting signed HTTPS
requests: to an integration of your own, to a Slack channel, to a Microsoft Teams workflow or to
PagerDuty. Webhooks are managed on **Integrations** (`/integrations`): administrators add, change,
test and delete them; auditors can look. Webhooks belong to the whole console: from 0.5.0, when
roles can be limited to an environment or an engine, managing them takes `admin` on everything
and seeing them `auditor` (or `admin`) on everything. They are for people with a session only: no
[API token](api-reference.md#api-tokens) can see or change them. This page describes syncd 0.5.0
(commit `a9d17c6`).

Nothing is sent until an administrator adds a webhook. The engine sends nothing itself: the
console watches each registered engine and posts on its behalf.

## Adding a webhook

An administrator enters a name, an `https://` URL, the events and a format, and is shown the
endpoint's **signing secret** (`whsec_…`) once; for PagerDuty the page asks for the routing key
instead (see [PagerDuty](#pagerduty)). **New secret** replaces it: from 0.4.1 with a generated one
(shown once) or one the receiver already has (16 to 200 characters without spaces). **Send a
test** posts a `webhook.test` event, whether the webhook subscribes to it or not.
The page shows each webhook's pending and recently failed deliveries, its last error and its
delivery log.

| Event | When |
|---|---|
| `engine.down`, `engine.up` | The console cannot reach an engine's control API; it answers again |
| `source.down`, `source.up` | A source failed to read (and is retrying) or stopped; it reads again |
| `target.down`, `target.up` | A target refused connections, as of its last operation; it takes operations again |
| `run.completed`, `run.failed` | A run ended: a full sync, a reconciliation (scheduled or asked for), a reload, a drift check (scheduled or asked for), an approval or a dead-letter retry; `failed` when a source failed to read meanwhile (see [`run.failed` is a heuristic](#runfailed-is-a-heuristic)) |
| `dead_letters.rising` | A publication's dead letters crossed 1, 10, 100, 1 000, ... |
| `quarantine.rising` | A view's quarantined entities crossed 1, 10, 100, ... |
| `quality.errors` | From 0.5.0: a view's entities failing data-quality checks of severity `error` (summed over its checks) went from none to some, or rose by `CONSOLE_WEBHOOKS_QUALITY_ERRORS_RISE` (10) since last told. A falling count lowers the mark it is measured from (see [Reports, lineage and data quality](reports-and-data-quality.md)) |
| `hold.created` | Changes were held by a change window, a modification, add or deletion threshold, a canary or report mode (see [Change safety and lifecycle](change-safety.md)) |
| `hold.released` | A publication has no held changes any more |
| `password.delivery-failed` | An engine could not deliver a password change (see [Password synchronisation](password-sync.md)) |
| `group.expired` | A publication's computed groups expired: the count of expired groups rose from 0 |
| `group.review-due` | From 0.5.0: a computed group's review fell due (`review_every` after its creation or its last review). Told once each time it falls due. The group's `reviewers` are also mailed (see [Group reviews by mail](#group-reviews-by-mail)) |
| `correlation.unmatched-ageing` | A view's unjoined records older than its `correlation.unmatched_alert_after` rose from 0 |
| `report.failed` | A scheduled report's mail could not be delivered after a day of retries (see [Reports, lineage and data quality](reports-and-data-quality.md)) |
| `backup.failed` | An engine's scheduled backup (`engine.backup`) failed |
| `ha.failover` | An engine of an HA pair (`engine.ha`) took the pair's lease over from the other engine. Told once, by the engine that took over |
| `config.published` | A configuration version was published to an engine (with its change ticket, when one was given) |
| `config.approval-requested` | A version was saved while four-eyes approval is required |
| `config.approved` | A version was approved |
| `config.rejected` | A publish was refused by the pre-publish hook (see [Security](security.md#the-pre-publish-hook)) |
| `config.git-refused` | Git sync refused a commit: an environment file in Git holds a secret value (see [Configuration as code](config-as-code.md)) |
| `config.promoted` | A version was promoted to another environment's engines |
| `config.unpublished-running` | From 0.5.0: an engine runs a configuration the console did not publish, applied by another client of its control API or edited on its host. Told once per configuration (see [Security](security.md#what-the-console-reports)) |
| `license.state-changed` | From 0.5.0: the [license](licensing.md)'s state changed: a warning, the grace period, publishing blocked, or licensed again. Not tied to an engine. Told once per change |
| `webhook.test` | **Send a test** on the Integrations page |

Event ids are stable: an event is never renamed, only added.

### Where the engine events come from

The `engine.*`, `source.*`, `target.*`, `run.*`, `dead_letters.*`, `quarantine.*`, `quality.*`,
`hold.*`, `password.*`, `group.*`, `correlation.*`, `backup.*` and `ha.*` events, and
`config.unpublished-running`, come from the console's **status watcher**. It reads every registered engine's status every ten seconds and raises an event
on each change worth telling (edge-triggered), so one change of state sends one event, not one
per poll. A rising count is told once per order of magnitude it crosses.

- What the watcher last saw is kept in the database (`webhook_engine_state`), so a console
  restart fires nothing again.
- The first look at an engine is a baseline: nothing fires for what is already so, except
  `engine.down` for an engine that cannot be reached, a configuration the console did not
  publish, and a group review that is due (something to do, not a change of state).
- Several consoles on one database compare under a row lock, so each change fires once.

The other `config.*` events are raised where they happen (a publish, an approval, a Git sync
pass, a promotion), in the same database transaction. `license.state-changed` is raised when the
console assesses the license and finds its state changed; its first look is a baseline unless the
state is already a problem.

### Group reviews by mail

From 0.5.0 a computed group can name who reviews it (`reviewers` on the group, or
`group_policy.reviewers` for a publication's groups: mail addresses, beside `review_every` in the
engine's configuration). When the review falls due the console mails them, through the mail
settings scheduled reports use and under the same rule: only to addresses in
`CONSOLE_REPORTS_RECIPIENT_DOMAINS` (others are left out and named in the console's log; without
mail settings nothing is sent and the log says so). The mail names the engine, the publication,
the group and its entry, and says how to review it. It is queued in the same transaction that
records the review as told, so it is sent once each time the review falls due, across console
restarts. `group.review-due` is raised whether or not anyone is named.

## Formats

| Format | Body |
|---|---|
| **syncd JSON** (`json`) | `{"id","type","createdAt","summary","engine":{"id","name"},"data":{…}}`, for an integration of your own. `engine` is `null` for events about the console itself |
| **Slack** (`slack`) | An incoming-webhook message with `text` and `blocks` |
| **Microsoft Teams** (`teams`) | An Adaptive Card message, for a Teams workflow's "When a webhook request is received" |
| **PagerDuty** (`pagerduty`) | An Events API v2 event, for `https://events.pagerduty.com/v2/enqueue` |

**The URL is a secret too.** A Slack or Teams webhook URL is its own credential, so only
administrators see a webhook's whole URL. Auditors, and the audit trail, see its scheme and host
(`https://hooks.slack.com/[redacted]`).

### syncd JSON

`id` is random and the same on every retry. `createdAt` is UTC with milliseconds. `summary` is one
sentence. `data` depends on the event:

```json
{"id":"3f0b9c1e-0000-4000-8000-000000000001","type":"target.down",
 "createdAt":"2026-11-07T10:00:00.000Z","summary":"target ad-users is down",
 "engine":{"id":1,"name":"prod"},"data":{"target":"ad-users"}}
```

| Event | `data` |
|---|---|
| `engine.down` | `error` |
| `engine.up` | `configVersion` |
| `source.down`, `source.up` | `source`, and `error` when it is down |
| `target.down`, `target.up` | `target` |
| `run.completed`, `run.failed` | `activity` (what ended), `elapsedMs`, `sourceFailures` (how many source failures were counted while it ran), and from 0.4.1 `runs` (how many runs ended since the watcher's last look; the event is about the last of them) |
| `dead_letters.rising` | `publication`, `count`, `previous` |
| `quarantine.rising` | `view`, `count`, `previous` |
| `quality.errors` | `view`, `count` (entities failing checks of severity `error`), `previous` (the count last told; 0 when it rose from none) |
| `hold.created` | `publication`, `total`, `adds`, `modifies`, `deletes`, `moves`, `reasons` (each reason with its count), `needsApproval`, `detail` |
| `hold.released` | `publication`, `released` |
| `password.delivery-failed` | `failed` (new failures), `totalFailed`, `error` (never any password material) |
| `group.expired` | `publication`, `expired` |
| `group.review-due` | `publication`, `group`, `dn`, `due_micros`, `reviewers` |
| `correlation.unmatched-ageing` | `view`, `count`, `oldestMicros` |
| `report.failed` | `report` (its id), `name`, `owner`, `publication`, `recipients`, `error` |
| `backup.failed` | `error`, `failedAtMicros` |
| `ha.failover` | `pair`, `from`, `to` |
| `config.published` | `version`, `publishedBy`, `ticket`, `note`, ... |
| `config.promoted` | `from`, `to`, `promotedBy`, `ticket`, and `results`: each target engine's outcome |
| `config.unpublished-running` | `runningSha256`, `runningVersion`, `appliedBy`, `appliedAtMicros`, `throughApi` (applied through the control API, or the file on the host), `publishedVersion` (what the console last published) |
| `license.state-changed` | `state`, `previous`, `licenseId`, `managedIdentities`, `limit`, `graceEnds`, `publishingBlocked`, `messages`; `engine` is `null` |
| `webhook.test` | `webhook`, `sentBy` |

A `hold.created` example:

```json
{"id":"…","type":"hold.created","createdAt":"2026-11-07T18:00:02.511Z",
 "summary":"publication people_ad: 412 changes held (modifications): 412 modifications of 5000 entries exceed the modification threshold (5%); held until approved",
 "engine":{"id":1,"name":"prod"},
 "data":{"adds":0,"deletes":0,"detail":"412 modifications of 5000 entries exceed the modification threshold (5%); held until approved","modifies":412,
         "moves":0,"needsApproval":true,"publication":"people_ad","reasons":{"modifications":412},"total":412}}
```

### Slack and Teams

The heading is `syncd <engine>: <title>` (`syncd: <title>` for console events), followed by the
summary. Slack gets it as `text` (for notifications) and as a header, a section and a context line
naming the engine, the event id and the time:

```json
{"text":"syncd prod: target down: target ad-users is down",
 "blocks":[{"type":"header","text":{"type":"plain_text","text":"syncd prod: target down","emoji":false}},
           {"type":"section","text":{"type":"mrkdwn","text":"target ad-users is down"}},
           {"type":"context","elements":[{"type":"mrkdwn","text":"engine *prod* · `target.down` · 2026-11-07T10:00:00.000Z"}]}]}
```

Teams gets an Adaptive Card (version 1.4) in a `message` with the heading, the summary, and a
fact set of the engine, the event and the time.

### PagerDuty

- **The endpoint's secret is the integration's routing key.** From 0.4.1 the Integrations page
  asks for it as soon as a PagerDuty webhook is added, and its **Routing key** button changes it;
  it is sealed with the master key and never shown again. Through the API it is
  `POST /api/v1/admin/webhooks/{id}/rotate-secret` with `{"secret": "<routing key>"}`. (In 0.4.0
  the page could only generate a random `whsec_` secret, which PagerDuty cannot use.)
- The down, failed, rising, hold, password, expiry, ageing, report, backup and failover events,
  and from 0.5.0 `quality.errors`, `group.review-due`, `config.unpublished-running` and
  `license.state-changed`, **trigger** an incident: `error` for `engine.down`, `source.down`,
  `target.down` and `run.failed`; `warning` for the others.
- The matching up, completed and released events **resolve** it. They are paired on a
  `dedup_key` of the engine and what the event is about (`syncd:<engine>:target:<name>`,
  `syncd:<engine>:hold:<publication>`, `syncd:<engine>:engine`, ...), so `target.up` resolves
  the incident `target.down` opened.
- The other `config.*` events and `webhook.test` trigger an `info` incident of their own, which
  nothing resolves. Nothing resolves the 0.5.0 events either: resolve them in PagerDuty.

```json
{"routing_key":"<routing key>","event_action":"trigger","dedup_key":"syncd:prod:target:ad-users",
 "payload":{"summary":"syncd prod: target down: target ad-users is down","source":"prod",
            "severity":"error","timestamp":"2026-11-07T10:00:00.000Z","component":"target:ad-users",
            "group":"syncd","class":"target.down","custom_details":{"target":"ad-users"}}}
```

A resolve carries only `routing_key`, `event_action: resolve` and `dedup_key`.

## Verifying a delivery

Each post is `Content-Type: application/json`, with the user agent `syncd-console/<version>`, and
carries:

```text
X-Syncd-Event: target.down
X-Syncd-Delivery: 3f0b9c1e-…        # the event id; the same on every retry: de-duplicate on it
X-Syncd-Timestamp: 1792569600
X-Syncd-Signature: t=1792569600,v1=5f2c…   # hex HMAC-SHA256 of "<t>.<raw body>" under the secret
```

This is the scheme the other ENS products use. Each try is signed afresh, so `t` is the time of the
try, not of the event. Check the HMAC over the **raw bytes** of the body, compare in constant time,
and refuse a timestamp more than five minutes from now:

=== "Python"

    ```python
    import hashlib, hmac, time

    def verify(secret: str, header: str, body: bytes, tolerance: int = 300) -> bool:
        parts = dict(p.split("=", 1) for p in header.split(","))
        t, given = int(parts["t"]), parts["v1"]
        if abs(time.time() - t) > tolerance:
            return False
        expected = hmac.new(secret.encode(), f"{t}.".encode() + body, hashlib.sha256).hexdigest()
        return hmac.compare_digest(expected, given)
    ```

=== "Shell"

    ```sh
    # t and v1 from X-Syncd-Signature, the body saved as received
    printf '%s.' "$t" | cat - body.json | openssl dgst -sha256 -hmac "$SECRET" | awk '{print $NF}'
    # must equal v1
    ```

Answer 2xx quickly and do the work afterwards: a receiver slower than the read timeout is
retried. Delivery is **at least once**, so de-duplicate on `X-Syncd-Delivery`. The source ships a
complete receiver, `tests/e2e/webhook-receiver.py`.

## Delivery

An event is written to `webhook_delivery`, one row per subscribed webhook, in the same transaction
as whatever raised it (the status change, the publish, the test). The sender posts it after the
commit. Rows are claimed in the database (`FOR UPDATE SKIP LOCKED`), so several consoles on one
database post each delivery once.

- A **2xx** answer is delivered. Anything else is retried: a timeout, a 5xx, a redirect (which is
  never followed), or an address the rules below refuse.
- **Retries for a day.** The first retry waits a minute, then the wait doubles up to an hour, until
  the delivery is a day old; then it is `failed`.
- Each try has a 5-second connect and 10-second read timeout, and a watchdog closes the connection
  at 30 seconds, whatever it is waiting on.
- **A dead receiver holds up nobody else.** A pass of the sender starts no try after 40 seconds.
  One webhook gets at most 10 tries and 20 seconds of a pass. A try a webhook does not answer at
  all puts that webhook's other waiting deliveries back to the same retry time, and the webhook is
  left alone for the rest of the pass. What a pass does not reach waits for the next one (every 30
  seconds, or at once when something new is raised).
- A disabled webhook keeps its queue. Nothing is posted to it until it is enabled again.
- Deliveries are posted in the order they were raised, per webhook.

### The delivery log

Each webhook's deliveries are listed on the Integrations page, newest first, and from
`GET /api/v1/admin/webhooks/{id}/deliveries` (admins and auditors): the event id and type, the state
(`pending`, `delivered` or `failed`), the tries, the next and last try, the last HTTP status and
error, and the event itself (in the JSON format). Delivered and failed rows are kept for 14 days.

## Where a webhook may go

The console must not become a way into its own network, so a webhook's address is checked:

- `https://` only, with no credentials in the URL.
- **Before saving, and again at every send**, the host is resolved and every address it resolves
  to is checked. Never allowed:
    - loopback;
    - link-local (169.254.0.0/16 and fe80::/10, so never a cloud metadata service);
    - the other metadata addresses (fd00:ec2::254, 100.100.100.200, 168.63.129.16);
    - unspecified (including 0.0.0.0/8), multicast or broadcast addresses;
    - an IPv6 address carrying one of those (IPv4-compatible, 6to4, the NAT64 prefix);
    - Teredo (2001::/32), the local-use NAT64 prefix (64:ff9b:1::/48), and IPv4-mapped or
      translated (::ffff:0:0/96) addresses.
- The post goes to the address that was checked, with TLS SNI and certificate host-name
  verification against the URL's host. A name that later resolves somewhere internal (DNS
  rebinding) is refused at the send.
- The receiver's certificate must chain to the public roots built into the console (the Mozilla
  set, not the host's trust store), or to a CA in `CONSOLE_WEBHOOKS_CA_FILE`.
- **Private addresses** (10/8, 172.16/12, 192.168/16, 100.64/10, fc00::/7) are governed by
  `CONSOLE_WEBHOOKS_BLOCK_PRIVATE_NETWORKS` and `CONSOLE_WEBHOOKS_ALLOWED_HOSTS`:

    | `CONSOLE_WEBHOOKS_BLOCK_PRIVATE_NETWORKS` | Private addresses |
    |---|---|
    | Unset (the default) | Refused for webhooks saved from now on. A webhook saved while the setting was `false` keeps its address until its URL is changed (`private_networks_allowed` in the list) |
    | `true` | Refused for every webhook |
    | `false` | Allowed for every webhook. A warning is logged at every start |

- **A receiver inside your network**, such as a chat bridge or a ticketing relay, is let in by
  `CONSOLE_WEBHOOKS_ALLOWED_HOSTS`: a host it names, or addresses all inside a CIDR range it
  lists, may be private whatever the setting above says. Loopback, link-local and the metadata
  addresses stay refused whatever is listed.
- **Narrow it further** with `CONSOLE_WEBHOOKS_ALLOWED_HOSTS` (names, `*.suffix`, CIDR ranges,
  comma-separated): once set, a webhook may go only to the hosts it lists.
- **Webhooks connect directly.** `HTTPS_PROXY` is not used, and there is no egress proxy setting.
  With the Helm chart's NetworkPolicy egress closed (`networkPolicy.egress.enabled`), add a rule
  in `networkPolicy.egress.extra` that lets the console reach the receivers on 443.

The [pre-publish hook](security.md#the-pre-publish-hook) is held to the same rules.

## Secrets at rest

The signing secret has to be kept to sign with, and the URL to send to. Both are stored sealed
with the console's master key, as the engines' client keys are (AES-256-GCM, a data key per
secret, bound to the webhook's id), and never returned after the secret is shown. See
[Security](security.md#console-secrets-and-the-master-key).

- There is no key of the webhooks' own: protecting and backing up the master key covers them.
- From 0.4.1 a console started with another master key refuses to start when the database holds
  webhooks, as it does for engines' client keys (0.4.0 started, when no engine was registered with
  mTLS). With `CONSOLE_NEW_MASTER_KEY=true` it starts, lists the webhooks with the URL marked as
  one that cannot be decrypted, and their deliveries fail with that reason. Save the URL again and
  set a new secret on each, and give the receivers the new secret.

## Settings

In `/etc/syncd/console.env` (or the Helm chart's `console.env` list), then restart the console.

| Variable | Default | Meaning |
|---|---|---|
| `CONSOLE_WEBHOOKS_ENABLED` | `true` | `false` queues and posts nothing, and starts no status watcher |
| `CONSOLE_WEBHOOKS_ALLOWED_HOSTS` | empty | Hosts a webhook may go to: names, `*.suffix` or CIDR ranges, comma-separated. Empty: any host the address rules allow. A host listed here may be on a private network |
| `CONSOLE_WEBHOOKS_BLOCK_PRIVATE_NETWORKS` | unset | Unset: private addresses refused for webhooks saved from now on, existing ones keep theirs. `true`: refused for all. `false`: allowed for all. See [Where a webhook may go](#where-a-webhook-may-go) |
| `CONSOLE_WEBHOOKS_CA_FILE` | none | PEM CA certificates receivers may use besides the public roots (an internal relay with a private CA) |
| `CONSOLE_WEBHOOKS_QUALITY_ERRORS_RISE` | `10` | From 0.5.0: after `quality.errors` was told for a view, it is told again each time the count rises by this many; `0`: only when it goes from none to some |

The timings above can be changed but rarely need to be: `CONSOLE_WEBHOOKS_POLL_SECONDS` (30),
`_WATCH_SECONDS` (10, the status watcher), `_CONNECT_TIMEOUT_SECONDS` (5),
`_READ_TIMEOUT_SECONDS` (10), `_DELIVERY_TIMEOUT_SECONDS` (30), `_RUN_BUDGET_SECONDS` (40),
`_ENDPOINT_TRIES_PER_RUN` (10), `_ENDPOINT_BUDGET_SECONDS` (20), `_RETRY_INITIAL_SECONDS` (60),
`_RETRY_MAX_SECONDS` (3600), `_GIVE_UP_AFTER_SECONDS` (86400) and `_KEEP_DAYS` (14).
`CONSOLE_WEBHOOKS_ALLOW_LOOPBACK` lets webhooks go to loopback addresses, for test systems only.

The Helm chart has no webhook values of its own: set these in `console.env`.

## Administration endpoints

Session only (API tokens are refused). From 0.5.0, "admin" and "auditor" here mean the role on
everything: an administrator or auditor of one environment or engine gets `403`.

| Method | Path | Access | Purpose |
|---|---|---|---|
| `GET` | `/api/v1/admin/webhooks` | admin, auditor | The webhooks (whole URLs for admins, `https://<host>/[redacted]` for auditors) with their pending and recently failed deliveries and last error |
| `GET` | `/api/v1/admin/webhooks/catalog` | admin, auditor | The events, the formats and the settings that apply |
| `POST` | `/api/v1/admin/webhooks` | admin | Add: `{"name", "url", "format", "events": [...], "enabled"}`. The URL is resolved and checked first. The answer holds the secret, once |
| `PUT` | `/api/v1/admin/webhooks/{id}` | admin | Change. Fields left out keep their value; a new URL is checked anew |
| `DELETE` | `/api/v1/admin/webhooks/{id}` | admin | Delete, with the delivery log |
| `POST` | `/api/v1/admin/webhooks/{id}/rotate-secret` | admin | A new secret (`{}`), or one of your own (`{"secret": "..."}`, 16 to 200 characters without spaces: a PagerDuty routing key) |
| `POST` | `/api/v1/admin/webhooks/{id}/test` | admin | Post a `webhook.test` event; `202` with the delivery's id |
| `GET` | `/api/v1/admin/webhooks/{id}/deliveries?limit=` | admin, auditor | The delivery log, newest first (50 by default, at most 500) |

## Audit

Recorded in the [audit trail](../audit-logging.md): `webhook.created`, `webhook.changed`,
`webhook.deleted`, `webhook.secret-rotated` and `webhook.test-sent`, with the URL as scheme and host
only. A delivery given up is logged as a warning in the console's log.

## Known limitations

### `run.failed` is a heuristic

The engine does not report a run's outcome as such. A run is reported as failed when the engine's
source-failure counter rose while it ran. So a source that failed during a reconciliation for a
reason unrelated to it makes the run `failed`, and a run that failed in another way is
`completed`.

From 0.4.1 the engine counts the runs that end (`runs` in its status), and the watcher tells one
event each time it sees the count rise: also for a run that started and ended between two of its
ten-second polls, which 0.4.0 missed. When several runs ended between two polls, one event tells
of the last of them, with how many ended (`runs`, and "(N runs ended since the last look)" in the
summary). Because scheduled reconciliations and drift checks are runs too, expect a
`run.completed` at least every reconciliation interval. An engine from before 0.4.1 has no count:
the watcher then sees a run only when a poll finds the engine busy with it, as in 0.4.0. For
alerting, prefer `source.down`, `target.down` and `dead_letters.rising`, or the engine's
[Prometheus alerts](api-reference.md#prometheus-metrics).

### Other limitations

- Webhooks cannot go through an HTTP proxy.
- Webhooks are global: there is no webhook limited to an environment or an engine, and every
  webhook may subscribe to every engine's events.
- Webhooks are a console feature: an engine run without a console sends none.

## Without webhooks

Other ways to follow the engines, which also work without the console's webhooks:

| Need | Use |
|---|---|
| Alerting on failures | The engine's Prometheus metrics at `GET /metrics` and the shipped alert rules, routed through Alertmanager. See [Operator guide](operator-guide.md) and [API reference](api-reference.md#prometheus-metrics) |
| A live feed of an engine's state | The console's server-sent events stream, `GET /api/v1/engines/{id}/status/stream`, or the engine's gRPC `WatchStatus` stream. See [API reference](api-reference.md) |
| A record of who did what | The audit trail: its outputs (file, Splunk, syslog) or the pull API with an API token. See [Audit logging](../audit-logging.md) |
