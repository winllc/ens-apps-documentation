# Webhooks

On *Admin → Integrations* (`/admin/integrations`) a PMO administrator registers HTTPS URLs that the
Portal POSTs events to, so ITSM and identity-governance tools learn about help-desk requests and token
changes whoever made them: an operator, a user on their own token, or an integration through the
[integration API](api-reference.md#integration-api).

## Events

| Event | When |
|---|---|
| `helpdesk.request.opened`, `.approval-requested`, `.approved`, `.rejected`, `.cancelled`, `.expired`, `.completed`, `.failed` | A [help-desk request](help-desk.md) changed state |
| `token.assigned`, `token.unassigned`, `token.deprovisioned`, `token.pin-reset`, `token.replaced`, `token.emergency-access-issued` | A token changed |
| `user.enabled`, `user.disabled` | An account was enabled or disabled |

A webhook subscribes to events by name, or by `helpdesk.*`, `token.*`, `user.*` or `*`.

The body holds identifiers only: never a request's reason text, a verification detail, a code or a
PIN.

```json
{"id":"<event id>","type":"helpdesk.request.completed","time":"2026-10-21T10:00:00.000Z",
 "data":{"userId":"jdoe","helpDeskRequest":"<id>","action":"pin-reset","status":"COMPLETED","ticket":"INC-1",
         "tokenSerial":"000123456789","requestedBy":"api:servicenow","actor":{"type":"service","id":"servicenow"}}}
```

## Verifying a delivery

| Header | Value |
|---|---|
| `X-Ens-Event` | The event type |
| `X-Ens-Event-Id` | The same on every retry: de-duplicate on it |
| `X-Ens-Delivery` | This delivery |
| `X-Ens-Timestamp` | Unix seconds |
| `X-Ens-Signature` | `v1=<hex HMAC-SHA256 of "<timestamp>.<body>" under the webhook's secret>` |

Recompute the HMAC over the raw body, compare in constant time, and refuse a timestamp more than five
minutes off:

```python
import hashlib, hmac, time

def verify(secret: str, timestamp: str, signature: str, body: bytes) -> bool:
    given = signature.split("=", 1)[1]                      # "v1=<hex>"
    expected = hmac.new(secret.encode(), f"{timestamp}.".encode() + body, hashlib.sha256).hexdigest()
    return hmac.compare_digest(expected, given) and abs(time.time() - int(timestamp)) <= 300
```

The secret (`whsec_...`) is shown once, when the webhook is made or its secret rotated.

## Delivery

- Events come from the audit trail once committed, queued at most once per webhook.
- Each delivery is claimed by the replica that sends it, so no two send it at once. Each webhook gets
  at most 10 deliveries and 20 seconds per run of the job, and none more in that run after one that
  got no answer, so one dead receiver does not hold up the others.
- An attempt is abandoned after `ens.webhooks.deadline` (15 s) however slowly the receiver answers,
  and at most 8 KB of its answer is read.
- A delivery that does not get a 2xx is retried after 30 seconds, then four times longer each time,
  at most 6 hours apart, 8 attempts in all (about a day), then given up (`webhook.delivery-failed`).
- **A receiver may still get an event twice** (its answer lost on the way back, a replica stopping
  mid-send): de-duplicate on `X-Ens-Event-Id`.
- A disabled or deleted webhook's waiting deliveries are cancelled (`CANCELLED` in the delivery log),
  so nothing old is sent if it is enabled again. The page lists the latest deliveries.
- No redirect is followed and no proxy is used.

## Where a webhook may point

Only `https` URLs whose host resolves only to public addresses.

- **Refused**: loopback, unspecified, link-local (169.254.0.0/16 and fe80::/10, where the cloud
  metadata services are), private (10/8, 172.16/12, 192.168/16, fc00::/7), carrier-grade NAT
  (100.64/10), multicast, reserved, documentation and benchmark ranges, IPv6 forms that carry such an
  IPv4 address (IPv4-mapped and -compatible, NAT64, 6to4, ISATAP), and metadata names
  (`metadata.google.internal`, ...).
- **Never allowed, whatever `ens.webhooks.allowed-hosts` says**: the cloud metadata and platform
  services by name or address (169.254.169.254, 169.254.170.2, 169.254.169.253 and .123,
  fd00:ec2::254, 168.63.129.16, 100.100.100.200, 192.0.0.192, also inside an IPv6 form), Teredo
  (2001::/32) and local-use NAT64 (64:ff9b:1::/48).
- The host is resolved and checked again for every delivery, and the connection goes to the address
  that was checked, so a name that later resolves inside the network (DNS rebinding) is refused.
- A receiver inside the network is allowed by name or range in `ens.webhooks.allowed-hosts`. With the
  Helm chart's NetworkPolicy on, allow the receivers with `networkPolicy.egress.webhookCidrs`.

## Signing secrets

Secrets are kept AES-GCM encrypted under a key derived from `rsa.helpdesk.code-key`
(`RSA_HELPDESK_CODE_KEY`, or `-file`), which `install.sh`, `upgrade.sh` and the Helm chart already
set. Webhooks cannot be made without that key, and a node started without it takes no part in
sending: **give every replica the same key**. Changing it makes the stored secrets unreadable: rotate
each webhook's secret afterwards.

## Settings

| Setting | Default | Meaning |
|---|---|---|
| `ens.webhooks.enabled` (`ENS_WEBHOOKS_ENABLED`; Helm `webhooks.enabled`) | `true` | Send events at all |
| `ens.webhooks.allowed-hosts` (`ENS_WEBHOOKS_ALLOWEDHOSTS`; Helm `webhooks.allowedHosts`) | none | Comma-separated host names and CIDR ranges a webhook may reach although internal |
| `ens.webhooks.max-attempts` | `8` | Attempts per event and webhook |
| `ens.webhooks.first-backoff`, `.max-backoff` | `30s`, `6h` | The wait after the first failure, and the longest |
| `ens.webhooks.connect-timeout`, `.response-timeout`, `.deadline` | `5s`, `10s`, `15s` | Per attempt; the deadline bounds the whole attempt |
| `ens.webhooks.endpoint-budget`, `.run-budget` | `20s`, `40s` | Time one webhook, and one run of the job, may take (the job's lease is 60 s) |

## Audit

`webhook.created`, `webhook.deleted`, `webhook.secret-rotated` and `webhook.delivery-failed`.
