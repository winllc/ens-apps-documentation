# Webhooks

CertAlert can tell other systems what happened by posting signed HTTPS requests: to an integration
of your own, to a Slack channel or to a Microsoft Teams workflow. Webhooks are managed on
**Admin > Integrations** (`/admin/integrations`): administrators add, change, test and delete them;
auditors can look.

Nothing is sent until an administrator adds a webhook.

## Adding a webhook

An administrator enters an `https://` URL, picks the events and a format, and is shown the
endpoint's **signing secret** (`whsec_…`) once. **New secret** replaces it (the new one is shown
once); **Send a test** posts a `webhook.test` event. The page shows each endpoint's delivery log.

| Event | When |
|---|---|
| `certificate.expiring` | A certificate moved into its warning window (the same alert the e-mail channel sends) |
| `certificate.expired` | A certificate expired |
| `job.completed`, `job.failed` | Any run of a job, scheduled or started by hand, ended |
| `webhook.test` | **Send a test** on the Integrations page |

| Format | Body |
|---|---|
| **CertAlert JSON** | `{"id","type","createdAt","summary","data":{…}}`, for an integration |
| **Slack** | An incoming-webhook `text` message |
| **Microsoft Teams** | An Adaptive Card message, for a Teams workflow's "When a webhook request is received" |

**The URL is a secret too.** A Slack or Teams webhook URL is its own credential, so only
administrators see a webhook's whole URL. Auditors, and the audit trail, see its scheme and host
(`https://hooks.slack.com/[redacted]`).

## Verifying a delivery

Each post carries:

```text
X-CertAlert-Event: certificate.expiring
X-CertAlert-Delivery: 3f0b9c1e-…        # the event id; the same on every retry: de-duplicate on it
X-CertAlert-Timestamp: 1792569600
X-CertAlert-Signature: t=1792569600,v1=5f2c…   # hex HMAC-SHA256 of "<t>.<raw body>" under the secret
```

Check the HMAC over the **raw bytes** of the body, compare in constant time, and refuse a timestamp
more than five minutes from now:

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

=== "Java"

    ```java
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256"));
    String expected = HexFormat.of().formatHex(mac.doFinal((t + "." + body).getBytes(UTF_8)));
    boolean ok = MessageDigest.isEqual(expected.getBytes(US_ASCII), given.getBytes(US_ASCII))
            && Math.abs(Instant.now().getEpochSecond() - t) <= 300;
    ```

Answer 2xx quickly and do the work afterwards; a receiver slower than the read timeout is retried.
Delivery is **at least once**, so de-duplicate on `X-CertAlert-Delivery`.

## Delivery

An event is written to `webhook_delivery` in the same transaction as whatever raised it, and posted
after the commit by one replica (claimed in the database, under a lock), with a 5-second connect and
10-second read timeout.

- A **2xx** answer is delivered. Anything else (a timeout, a 5xx, a redirect, which is never
  followed) is retried after `retry-initial` (1 minute), doubling up to `retry-max` (1 hour), until
  the delivery is `give-up-after` (a day) old.
- Delivered and failed rows are kept for `keep` (14 days).
- **A dead receiver holds up nobody else.** A pass of the sender starts no try after `run-budget`
  (40 seconds); one endpoint gets at most `endpoint-tries-per-run` (10) tries and `endpoint-budget`
  (20 seconds) of a pass. A try an endpoint does not answer at all puts that endpoint's other waiting
  deliveries back to the same retry time, and the endpoint is left alone for the rest of the pass.
  What a pass does not reach waits for the next one (`poll-interval`, 30 seconds).
- A watchdog closes the connection at `delivery-timeout` (30 seconds), whatever it is waiting on.

## Where a webhook may go

- `https://` only, with no credentials in the URL.
- Before saving, and again at every send, the host is resolved and every address it resolves to is
  checked. Never allowed: loopback; link-local (169.254.0.0/16 and fe80::/10, so never a cloud
  metadata service); the other metadata addresses (fd00:ec2::254, 100.100.100.200, 168.63.129.16);
  unspecified, multicast or broadcast addresses; an IPv6 address carrying one of those
  (IPv4-compatible, 6to4, NAT64); Teredo (2001::/32); the local-use NAT64 prefix (64:ff9b:1::/48);
  and IPv4-mapped or translated (::ffff:0:0/96) addresses.
- The post goes to the address that was checked, with TLS SNI and certificate host-name verification
  against the URL's host and the JVM's trust store. A name that later resolves somewhere internal
  (DNS rebinding) is refused at the send.
- Narrow it further with `cert-alert.webhooks.allowed-hosts` (names, `*.suffix`, CIDR ranges) and
  `cert-alert.webhooks.block-private-networks: true`.
- **Webhooks connect directly.** An outbound HTTP proxy is not used. With the Helm chart's
  NetworkPolicy on, `networkPolicy.egress.web` must reach the receivers.

## Secrets at rest

The signing secret has to be kept to sign with, and the URL to send to. Set
`cert-alert.webhooks.secret-key` to a base64 AES key (`openssl rand -base64 32`) and both are stored
AES-GCM encrypted. Without one they are stored as they are, and a warning is logged at startup.

- Helm: `webhooks.secretKey`, or `webhooks.existingSecret` / `webhooks.existingSecretKey`.
  Release package: `CERT_ALERT_WEBHOOKS_SECRET_KEY` in `.env`.
- Secrets and URLs saved before the key was set are encrypted at the next start (once, under the job
  lock, safe with several replicas).
- **Changing the key** makes the stored secrets unreadable: press **New secret** and save the URL
  again on each webhook afterwards.
- **Give every replica the same key.** A replica started without it leaves deliveries to encrypted
  webhooks to a replica that has it, and logs a warning each pass naming those webhooks.

## Settings

| Property (`cert-alert.webhooks.`) | Env var | Default | Description |
|---|---|---|---|
| `enabled` | `CERT_ALERT_WEBHOOKS_ENABLED` | `true` | `false` queues and posts nothing |
| `allowed-hosts` | `CERT_ALERT_WEBHOOKS_ALLOWED_HOSTS` | empty | Hosts a webhook may go to: names, `*.suffix` or CIDR ranges. Empty: any host the address rules allow |
| `block-private-networks` | `CERT_ALERT_WEBHOOKS_BLOCK_PRIVATE_NETWORKS` | `false` | Refuse private addresses as well |
| `secret-key` | `CERT_ALERT_WEBHOOKS_SECRET_KEY` | none | Base64 AES key that encrypts signing secrets and URLs in the database |
| `connect-timeout`, `read-timeout` | | `5s`, `10s` | Per try |
| `delivery-timeout` | | `30s` | All of one delivery; the connection is closed at it |
| `poll-interval` | | `30s` | How often the sender looks for due deliveries |
| `run-budget`, `endpoint-tries-per-run`, `endpoint-budget` | | `40s`, `10`, `20s` | Limits of one pass |
| `retry-initial`, `retry-max`, `give-up-after` | | `1m`, `1h`, `1d` | Backoff and when to give up |
| `keep` | | `14d` | How long delivered and failed rows are kept |

The Helm chart sets the first four from `webhooks.enabled`, `webhooks.allowedHosts`,
`webhooks.blockPrivateNetworks` and `webhooks.secretKey`.

## Audit

Recorded in the [audit trail](../audit-logging.md): `webhook.created`, `webhook.changed`,
`webhook.deleted`, `webhook.secret-rotated`, and `webhook.secrets-encrypted` when stored secrets are
encrypted at a start after the key was set. URLs appear in the trail as scheme and host only.

!!! info "Not built yet"
    A ServiceNow or Jira connector that opens one ticket per certificate. A webhook to a ticketing
    system's inbound REST endpoint, de-duplicating on the certificate's fingerprint, covers most of
    it today.
