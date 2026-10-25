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
| `status.changed` | A check-in, lock, unlock or check-out (`kind` `presence`: `status` `IN`, `AWAY` or `OUT`, `action`, `at`, `forced`), or a status put on someone's calendar (`kind` `calendar`: `status`, `statusName`, `dates`, `source` `request`, `self` or `manager`) | As described |
| `ping` | An administrator presses **Send test** | `message` |

A webhook acts for the administrator who added it: while they are no longer one, nothing is sent to
it. The same live account check as for [API tokens](api-reference.md#tokens) applies to webhook
owners.

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
  endpoint, least recently served first, at most `APPLICATION_WEBHOOKS_PERENDPOINTMAX` (20) tries and
  `APPLICATION_WEBHOOKS_PERENDPOINTBUDGET` (20 s) each.
- An endpoint that gives no HTTP answer at all is paused with backoff (30 seconds, then 2, 10 and 30
  minutes, 1, 2, 4 and 8 hours) until any answer, so a slow or dead receiver cannot hold the others
  up.
- Anything but a 2xx answer (a redirect included) is retried after 30 seconds, 2, 10 and 30 minutes,
  then 1, 2, 4 and 8 hours, up to `APPLICATION_WEBHOOKS_MAXATTEMPTS` (8) tries, then marked failed.
- The Integrations page shows each webhook's recent deliveries (state, tries, last status, error);
  the log is kept `APPLICATION_WEBHOOKS_DELIVERYLOGDAYS` (30) days.

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
- Webhooks connect directly, without a proxy; the JVM's trust store applies. With the Helm chart's
  NetworkPolicy on, egress to port 443 is opened for webhooks (`webhooks.ports`).

## Settings

| Variable | Default | |
|---|---|---|
| `APPLICATION_WEBHOOKS_SECRETKEY` | (none: webhooks off) | The key the signing secrets are encrypted with in the database (AES-256-GCM), 32 characters or more, **the same on every replica and across upgrades** (Helm: `webhooks.enabled` and `webhooks.secretKey`; never generated). If it changes, existing webhooks must be added again |
| `APPLICATION_WEBHOOKS_ALLOWEDCIDRS` | (none) | Internal ranges webhooks may be sent to (Helm `webhooks.allowedCidrs`) |
| `APPLICATION_WEBHOOKS_MAXATTEMPTS` | `8` | Tries per delivery |
| `APPLICATION_WEBHOOKS_DELIVERYLOGDAYS` | `30` | Days delivered and failed deliveries are kept |
| `APPLICATION_WEBHOOKS_CONNECTTIMEOUT`, `_READTIMEOUT` | `5s`, `10s` | |
| `APPLICATION_WEBHOOKS_ATTEMPTTIMEOUT`, `_RUNBUDGET`, `_PERENDPOINTMAX`, `_PERENDPOINTBUDGET` | `30s`, `40s`, `20`, `20s` | A try's overall deadline, and one run's limits |

## Audit

`webhook.created`, `webhook.disabled`, `webhook.enabled` and `webhook.deleted`; the secret appears
only as its first characters.
