# Webhooks

*Administration → Integrations → Webhooks*

The portal sends an HTTPS `POST` (JSON) to each enabled webhook subscribed to an event, for changes
made in the portal, through the [integration API](api-reference.md#integration-api-apiv1) or by an
[import](import-export.md). Administrators add, change, test and delete webhooks; nothing is sent
until one exists.

## Events

| Event | When |
|---|---|
| `entry.changed` | An entry was created, updated (or renamed) or deleted (`data.operation`, the changed attribute names) |
| `group.membership_changed` | `member`, `uniqueMember` or `memberUid` values were added or removed (`data.added`, `data.removed`) |
| `form.submitted` | An entry form was saved: create, update, or a user's own profile (`self_update`) |

A webhook can be limited to some directories. **Send test event** queues a `ping`.

## Verifying a delivery

Each request carries `X-DSP-Event`, `X-DSP-Event-Id`, `X-DSP-Delivery`, `X-DSP-Timestamp` and

```text
X-DSP-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256 of "<t>.<body>" with the webhook's secret>
```

The secret (`whsec_…`) is shown once, when the webhook is created or its secret renewed. Receivers
should recompute the signature over the **raw body**, compare in constant time, and reject timestamps
more than five minutes old:

```python
import hashlib, hmac, time

def verify(secret: str, header: str, body: bytes) -> bool:
    parts = dict(p.split("=", 1) for p in header.split(","))
    t, v1 = parts["t"], parts["v1"]
    expected = hmac.new(secret.encode(), f"{t}.".encode() + body, hashlib.sha256).hexdigest()
    return hmac.compare_digest(expected, v1) and abs(time.time() - int(t)) < 300
```

Delivery is **at least once**: an attempt whose answer was lost is made again with the same
`X-DSP-Event-Id`, so receivers should drop events they have already handled.

## Delivery

- A **2xx** answer delivers. Anything else, including a redirect (never followed), is retried after
  30 s, 2 min, 10 min, 30 min, 1 h, 3 h and 6 h, up to `WEBHOOK_MAX_ATTEMPTS` (8); then the delivery
  fails and `webhook.delivery-failed` is audited.
- Each attempt has an overall deadline, `WEBHOOK_SEND_TIMEOUT` (20 s: name lookup, connect, TLS,
  request and answer together), within `WEBHOOK_CONNECT_TIMEOUT` (5 s) and `WEBHOOK_READ_TIMEOUT`
  (10 s).
- A receiver that gives **no answer at all** (connection refused or timed out, TLS failure, no answer
  within the deadline, or its address refused by the guard below) is backed off as a whole: all of
  that webhook's due deliveries wait as long as the one just tried. A receiver that answers with an
  error status is retried per delivery.
- Deliveries go out every couple of seconds, oldest first, at most 20 attempts per run. One webhook
  gets at most 10 attempts and 20 s of a run, and no attempt starts after `WEBHOOK_RUN_BUDGET`
  (40 s).
- The delivery log (per webhook, with each payload) keeps finished deliveries for
  `WEBHOOK_LOG_RETENTION` (30 days; without the shared database, also at most the last 2,000).
- Webhooks connect directly: no HTTP proxy is used.

## Where a webhook may go (SSRF guard)

- The URL must be `https://` without credentials.
- Every address the host resolves to must be public. Loopback, link-local, private (RFC 1918, IPv6
  unique-local), carrier-grade NAT, multicast, reserved and documentation ranges, and IPv6 forms
  embedding such an IPv4 address, are refused, both when the webhook is saved and again at every
  delivery.
- The delivery connects to exactly the address it checked, so changing DNS answers can't redirect it.
- `WEBHOOK_ALLOWED_HOSTS` (host names or `*.example.com`, `;`-separated) lets named hosts reach
  internal addresses. Cloud metadata addresses (169.254.169.254, fd00:ec2::254, 169.254.170.2,
  100.100.100.200, 192.0.0.192) are refused regardless.
- A NetworkPolicy or firewall must let the API reach the receivers on 443 (the Helm chart's
  `networkPolicy.egress.webhooks`).

## Storage

- **Without the shared database**: `DATA_DIR/webhooks.json` and `webhook-deliveries.json`, both
  readable only by the portal's user. Pending deliveries survive a restart. The outbox file is
  written every couple of seconds, not per change, so a crash loses at most the deliveries queued in
  those seconds; it holds at most 10,000 waiting deliveries, beyond which new ones are dropped,
  logged and audited as `webhook.delivery-dropped`.
- **With the shared database** (`DSP_DATABASE_*`): the `webhooks` document and the
  `dsp_webhook_delivery` table (Flyway migration V3, applied at start), where each due delivery is
  sent by one replica. Up to the current release, the events of an entry change made while the
  database can't be reached are logged and lost. From the next release they are kept in that
  replica's memory (up to 10,000 changes, in order) and queued by the next delivery run that reaches
  the database, with their original `createdAt`; only a replica that stops during the outage loses
  them. Nothing to configure.
- **Signing secrets are stored as they are** (they sign every request), readable only by the portal's
  user. They are never returned by the API again, logged or audited. Back up and protect `DATA_DIR`
  and the database accordingly.
- Changes to webhooks are audited (`webhook.created`, `webhook.updated`, `webhook.secret-rotated`,
  `webhook.deleted`) with the URL's host only.

## Settings

| Variable | Property | Default | Description |
|---|---|---|---|
| `WEBHOOKS_ENABLED` | `portal.integration.webhooks.enabled` | `true` | `false` stops sending (deliveries stay queued) and creating webhooks |
| `WEBHOOK_ALLOWED_HOSTS` | `portal.integration.webhooks.allowed-hosts` | empty | Host names (or `*.example.com`) whose webhooks may reach internal addresses |
| `WEBHOOK_MAX_ATTEMPTS` | `portal.integration.webhooks.max-attempts` | `8` | Attempts per delivery |
| `WEBHOOK_CONNECT_TIMEOUT`, `WEBHOOK_READ_TIMEOUT` | | `5s`, `10s` | Per attempt |
| `WEBHOOK_SEND_TIMEOUT` | | `20s` | The whole attempt |
| `WEBHOOK_RUN_BUDGET` | | `40s` | How long one delivery run starts attempts for |
| `WEBHOOK_LOG_RETENTION` | | `30d` | How long finished deliveries are kept |

Helm: `integrations.webhooks.enabled`, `.allowedHosts`, `.maxAttempts` and `.runBudget`; any other
goes in `api.extraEnv`.

## Administration endpoints

Session and administrator only (the `X-DSP-Request: 1` header applies):

| Method | Path | Description |
|---|---|---|
| GET | `/api/admin/webhooks` | The webhooks (never their secrets) |
| GET | `/api/admin/webhooks/settings` | Event types and limits |
| POST | `/api/admin/webhooks` | Create; the answer holds the secret, once |
| PUT | `/api/admin/webhooks/{id}` | Change |
| POST | `/api/admin/webhooks/{id}/rotate-secret` | New secret, shown once |
| POST | `/api/admin/webhooks/{id}/test` | Queue a `ping` |
| DELETE | `/api/admin/webhooks/{id}` | Delete |
| GET | `/api/admin/webhooks/{id}/deliveries` | The delivery log |
