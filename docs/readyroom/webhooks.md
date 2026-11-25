# Webhooks

ReadyRoom sends signed webhooks for absence alerts and status changes. An administrator sets them
up on *Administration → Integrations* (`/app/admin/integrations`), next to the
[public API](api-reference.md#public-api-apiv1)'s tokens.

**Webhooks are off** until `APPLICATION_WEBHOOKS_SECRETKEY` is set (Helm: `webhooks.enabled: true`
with `webhooks.secretKey`).

## Events

Each webhook is an HTTPS `POST` of JSON:

```json
{"id": "4c1e...", "type": "status.changed", "createdAt": "2026-10-21T12:02:11Z",
 "data": {"person": {"dn": "CN=Jane Doe,OU=Users,DC=example,DC=com", "name": "Jane Doe"},
          "kind": "presence", "status": "IN", "action": "CHECK_IN", "at": "2026-10-21T08:02:11-04:00", "forced": false}}
```

| Event | When | `data` |
|---|---|---|
| `absence.alert` | Someone is not in by their expected time plus the grace period, and their managers were alerted | `person`, `date`, `expectedBy`, `managersNotified` |
| `status.changed` | A check-in, lock, unlock or check-out (`kind` `presence`: `status` `IN`, `AWAY` or `OUT`, `action`, `at`, `forced`; `forced` is `true` for an automatic check-out, which from 0.0.11 includes the one that closes a session left open at the end of the person's day), or a status put on someone's calendar (`kind` `calendar`: `status`, `statusName`, `dates`, `source` `request`, `self` or `manager`) | As described |
| `ping` | An administrator presses **Send test** | `message` |

A webhook acts for the administrator who added it: while they are no longer one, nothing is sent to
it. The same live account check as for [API tokens](api-reference.md#tokens) applies to webhook
owners.

Events name people: `status.changed` and `absence.alert` carry the person's DN and name. Treat the
receiver as a recipient of personal data (see [Privacy](privacy.md#outside-the-database)). Erasing a
person deletes the deliveries about them, waiting ones included, and disables the webhooks they
created.

## Verifying a delivery

Each delivery carries:

| Header | Value |
|---|---|
| `X-ReadyRoom-Event` | The event type |
| `X-ReadyRoom-Delivery` | The event id. Delivery is at least once, so de-duplicate on it |
| `X-ReadyRoom-Timestamp` | Unix seconds |
| `X-ReadyRoom-Signature` | `t=<timestamp>,v1=<hex>`: HMAC-SHA256, keyed with the endpoint's signing secret, of `<timestamp>.<raw body>` |

The signing secret (`whsec_...`) is shown once, when the webhook is added. To verify: recompute the
HMAC over the exact bytes received, compare in constant time, and refuse a timestamp more than five
minutes from your clock.

**Rotating the secret.** If a secret leaks, **Rotate secret** on the Integrations page gives the
webhook a new one, shown once, and keeps the webhook, its URL, its events and its delivery log.
Every try from then on, retries of deliveries already queued included, is signed with the new
secret, so the old one stops verifying at once: give the receiver the new secret straight away.

```python
import hashlib, hmac, time

def verify(secret: str, header: str, body: bytes) -> bool:
    parts = dict(p.split("=", 1) for p in header.split(","))
    t, v1 = parts["t"], parts["v1"]
    expected = hmac.new(secret.encode(), f"{t}.".encode() + body, hashlib.sha256).hexdigest()
    return hmac.compare_digest(expected, v1) and abs(time.time() - int(t)) <= 300
```

## Delivery

- Deliveries are queued in the database once the change commits, and sent within seconds by one
  replica at a time; each is claimed just before it is sent, so no two replicas send it at once.
- A try is cut off after `APPLICATION_WEBHOOKS_ATTEMPTTIMEOUT` (30 s) altogether, whatever the
  receiver does, the host's DNS lookup included.
- A run starts no try after `APPLICATION_WEBHOOKS_RUNBUDGET` (40 s). Due deliveries are taken per
  endpoint, least recently served first, for at most `APPLICATION_WEBHOOKS_PERENDPOINTBUDGET` (20 s)
  each, in batches of `APPLICATION_WEBHOOKS_PERENDPOINTMAX` (20) tries. From 0.0.11 an endpoint
  whose whole batch was delivered gets the next batch in the same run, within its time, so a
  receiver that keeps up is not held to 20 deliveries a run; one that refuses a delivery or does
  not answer gets no more than one batch.
- An endpoint that gives no HTTP answer at all is paused with backoff (30 seconds, then 2, 10 and 30
  minutes, 1, 2, 4 and 8 hours) until any answer, so a slow or dead receiver cannot hold the others
  up.
- **A webhook that does not answer is disabled** (from 0.0.11). After 10 tries in a row with no
  HTTP answer, about a day with the pauses between them, ReadyRoom disables the webhook. It is
  audited as `webhook.disabled` with the actor `system` (the reason
  `no answer to 10 tries in a row`, the last error in `details.lastError`) and logged at `WARN`.
  Nothing is queued for it from then on, so events raised while it is disabled are not sent
  later. **Enable** on the Integrations page turns it back on and ends its pause, so what was
  still queued for it is tried again. An answer of any kind, an error status included, is an
  answer: such a webhook is retried and is not disabled.
- **A delivery that waits too long is given up** (from 0.0.11). A delivery still waiting 3 days
  after it was queued is marked failed, with the error
  `expired: not delivered within 72 hours of being queued`, and then leaves the log with the rest.
  The limit is longer when `APPLICATION_WEBHOOKS_MAXATTEMPTS` is raised so far that its retries
  take more than two days: the retries' backoff plus a day. This is checked hourly.
- Anything but a 2xx answer (a redirect included) is retried after 30 seconds, 2, 10 and 30 minutes,
  then 1, 2, 4 and 8 hours, up to `APPLICATION_WEBHOOKS_MAXATTEMPTS` (8) tries, then marked failed.
- Deliveries are not held back behind a retry, so they can arrive out of order: order by
  `createdAt` (and, for a `status.changed`, by `data.at`); a retried delivery can arrive after newer
  ones.
- The Integrations page shows each webhook's recent deliveries (state, tries, last status, error);
  the log is kept `APPLICATION_WEBHOOKS_DELIVERYLOGDAYS` (30) days. A disabled webhook carries a
  *disabled* badge and an **Enable** button.
- With several replicas, a webhook deleted on one replica is still listed on the others for up to
  30 seconds. From 0.0.11 an event raised meanwhile is still queued for every other webhook; up to
  0.0.10 it could be queued for none.
- Each try is counted in `ens_readyroom_webhook_deliveries_total{outcome}` (`delivered`, `retry`
  for one tried again later, `failed` for one given up). A webhook that has failed 5 tries in a row
  is logged at `WARN` (`Webhook endpoint <id> (<name>) has failed 5 tries in a row since it last
  took a delivery; ...`), again every 5 more, and at `INFO` once it takes a delivery again. The
  count is kept by the replica sending, and starts again after a restart.

## Where webhooks may go

- `https://` only, with no user name or password in the URL.
- Every address the host resolves to must be public: loopback, private (RFC 1918, unique local),
  link-local, shared (100.64/10), reserved, documentation, benchmarking, broadcast and multicast
  addresses are refused, including IPv4 ones inside IPv6 (mapped, NAT64, 6to4).
- An internal receiver is allowed by listing its range in `APPLICATION_WEBHOOKS_ALLOWEDCIDRS` (comma
  separated, for example `10.20.0.0/16`). Cloud metadata addresses (169.254.169.254, fd00:ec2::254,
  100.100.100.200, 169.254.170.2) are refused whatever it says.
- The check runs when a webhook is saved and again on every delivery, and the connection is made to
  the address just checked (TLS still verifies the certificate against the host name), so a name that
  later resolves somewhere internal (DNS rebinding) is not sent to.
- Webhooks connect directly unless an [egress proxy](#through-an-egress-proxy) is set (next
  release); the JVM's proxy settings are not used, and its trust store applies. With the Helm
  chart's NetworkPolicy on, egress to port 443 is opened for webhooks (`webhooks.ports`), and to the
  proxy's port when `webhooks.proxy.address` is set.

## Through an egress proxy

!!! info "From the next release"
    Up to the current release webhooks always connect directly. The proxy setting is new and unset:
    webhooks keep connecting directly until you set it.

Where the only way out is an HTTP proxy, set `APPLICATION_WEBHOOKS_PROXY` to its `host:port` (Helm
`webhooks.proxy.address`).

- Each delivery still resolves and checks the endpoint's address first, exactly as above, and then
  asks the proxy to tunnel (`CONNECT`) to that checked address, written as an IP literal, never to
  the name, so the proxy cannot be steered somewhere else. TLS runs inside the tunnel, end to end
  with the endpoint and verified against its name.
- **ReadyRoom still needs DNS for the endpoints' names** on its own host or pod, even behind the
  proxy.
- A proxy that filters by name must therefore allow `CONNECT` to the endpoints' addresses (or inspect
  the TLS SNI, which carries the name).
- Only a `200` opens the tunnel: a proxy's error or redirect fails the try, which is retried as
  usual.
- A proxy that asks for Basic authentication gets `user:password` from
  `APPLICATION_WEBHOOKS_PROXYCREDENTIALS`, or from the file named by
  `APPLICATION_WEBHOOKS_PROXYCREDENTIALSFILE`, which wins (Helm `webhooks.proxy.credentials.value`,
  or `.existingSecret` with `.existingSecretKey`, default key `webhook-proxy-credentials`). They are
  sent to the proxy only and never logged.

## Settings

| Variable | Default | |
|---|---|---|
| `APPLICATION_WEBHOOKS_SECRETKEY` | (none: webhooks off) | The key the signing secrets are encrypted with in the database (AES-256-GCM), 32 characters or more, **the same on every replica and across upgrades** (Helm: `webhooks.enabled` and `webhooks.secretKey`; never generated). If it changes, existing webhooks must be added again |
| `APPLICATION_WEBHOOKS_ALLOWEDCIDRS` | (none) | Internal ranges webhooks may be sent to (Helm `webhooks.allowedCidrs`) |
| `APPLICATION_WEBHOOKS_PROXY` (next release) | (none: direct) | `host:port` of an HTTP proxy webhooks are tunnelled through (Helm `webhooks.proxy.address`) |
| `APPLICATION_WEBHOOKS_PROXYCREDENTIALS`, `_PROXYCREDENTIALSFILE` (next release) | (none) | `user:password` for that proxy, or a file holding it (the file wins); never logged (Helm `webhooks.proxy.credentials.*`) |
| `APPLICATION_WEBHOOKS_MAXATTEMPTS` | `8` | Tries per delivery |
| `APPLICATION_WEBHOOKS_DELIVERYLOGDAYS` | `30` | Days delivered and failed deliveries are kept |
| `APPLICATION_WEBHOOKS_CONNECTTIMEOUT`, `_READTIMEOUT` | `5s`, `10s` | |
| `APPLICATION_WEBHOOKS_ATTEMPTTIMEOUT`, `_RUNBUDGET`, `_PERENDPOINTMAX`, `_PERENDPOINTBUDGET` | `30s`, `40s`, `20`, `20s` | A try's overall deadline, and one run's limits. `_PERENDPOINTMAX` is the size of a batch: an endpoint whose batch was all delivered gets another within `_PERENDPOINTBUDGET` |

## Audit

`webhook.created`, `webhook.disabled`, `webhook.enabled`, `webhook.deleted` and
`webhook.secret-rotated`; the secret appears only as its first characters, and a rotation names the
previous one's too (`details.previousSecretPrefix`). A webhook disabled by an erasure carries
`details.via` `erasure`. One that ReadyRoom disabled because its receiver did not answer has the
actor `system` and the reason `no answer to 10 tries in a row`.
