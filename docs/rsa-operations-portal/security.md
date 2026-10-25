# Security

This page describes the Portal's and the Connector's security controls as the code on the current
release implements them, and the settings that change them.

## Authentication

* **Form login** at `/login`, which posts to `/perform_login`. On success the user goes to `/home`. On any failure the user is redirected to `/login?error=true`, which shows one message whatever the cause: *Sign-in failed. Check your user ID and passcode (PIN + tokencode).* The page no longer says that an account is locked or disabled; the cause is in the audit trail.
* Credentials are the user's **RSA AM User ID and SecurID passcode**. `CustomAuthenticationProvider` sends them to AM through the Connector. If AM asks for more input (next tokencode, new PIN, new PIN confirmation, passcode, principal ID, new static passcode), the user is redirected to `/additionalParameterLoginHandler` to answer each prompt.
* The Portal keeps no passwords. AM's own lockout policy applies to every passcode. In front of it, the Portal [rate-limits sign-in and backs off](#rate-limits-and-uniform-answers) per client address and per user ID. Each sign-in is audited as `auth.login` with its reason, and a locked account is recorded as an alert.
* The authenticated principal is an `RSAAuthenticationToken` that carries the AM **session ID** and **principal GUID**. The Connector uses them to run the operator's changes in the operator's own AM session, so AM enforces that user's permissions and its audit log names them (see [RSA AM integration](rsa-integration.md#who-runs-each-command)).
* **Logout** is `/perform_logout`. It deletes `JSESSIONID` and also logs the AM session out (`CustomLogoutSuccessHandler`).
* If AM reports the user's session has ended during an operation, `GlobalControllerExceptionHandler` logs the user out and redirects to `/login`.
* **Rate limits on public self-service**, and the feature caps of Test My Token, the PIN reset link and the token swap: see [Rate limits and uniform answers](#rate-limits-and-uniform-answers).
* **Single sign-on** for operators (OIDC with PKCE and a nonce, or SAML 2.0 with signed answers to a request this Portal sent, used once; IdP-initiated sign-in refused), off until an identity provider is configured. The provider's groups decide the Portal roles through `ENS_SSO_ROLES_*` only, and the operator must then enter the SecurID passcode of the same user ID, which opens their own AM session. Every URL given to the provider is built from `APPLICATION_DNS_NAME`. No single logout. With a provider configured, a passcode-only sign-in still gets the roles AM grants unless `ENS_SSO_REQUIRE_FOR_ADMIN_ROLES=true`. From the next release, with that setting unset (the new default), a role that `ENS_SSO_ROLES_*` maps a group to comes only through the identity provider, so its conditional access cannot be bypassed with a passcode alone; and `ENS_SSO_OIDC_END_SESSION=true` signs the operator out of an OpenID Connect provider too (SAML single logout is not supported). See [Single sign-on](single-sign-on.md).
* **Integration API tokens** (`ensapi_...`, SHA-256 hashed, shown once, scoped, expiring, rate-limited across replicas, limited to named security domains or identity sources) authenticate `/api/v1` only, with no session. Every change they ask for is a [help-desk request](help-desk.md) under the same rules as an operator's, and runs on the Connector's service account. See [API and HTTP route reference](api-reference.md#integration-api).

### Notice and consent banner

`ENS_CONSENT_BANNER_ENABLED=true` shows the DoD notice-and-consent banner before anything else in each browser session. Until the user clicks **I agree**, the session reaches only the banner: no page, no sign-in and no self-service (Test My Token, PIN reset, registration, exemption requests). The server enforces it: forms sent before it go back to the banner, and AJAX calls get 403. The acknowledgement lasts for the browser session and is audited as `auth.consent-acknowledged`. Exempt, because they are not browser sessions: the actuator health and Prometheus endpoints, and the audit pull API (`/api/audit/events`). See [Configuration](configuration.md#security-headers-banner-and-sessions).

### Sessions

| Setting | Default | Meaning |
|---|---|---|
| `ENS_SESSION_IDLE_TIMEOUT` | `30m` | A session ends after this long without a request |
| `ENS_SESSION_ADMIN_IDLE_TIMEOUT` | the idle timeout | Idle timeout for users with an administrative role (PMO, Trusted Agent, Help Desk). DISA STIG suggests `15m` |
| `ENS_SESSION_ABSOLUTE_TIMEOUT` | `12h` | A session ends this long after sign-in, whatever its activity |
| `ENS_SESSION_MAX_PER_USER` | `0` (no limit) | Sessions one user may have at once; signing in once more ends the oldest. DISA STIG suggests `3` |

An ended session's next request goes to the sign-in page ("Your session has ended"); an AJAX call gets 401. Each end is audited as `auth.session-expired` with `details.reason` `idle`, `absolute` or `concurrent`. The older setting `server.servlet.session.timeout` is still read when `ENS_SESSION_IDLE_TIMEOUT` is not set. Release 1.5 shipped `server.servlet.session.timeout=480`, which Spring reads as 480 seconds, so its sessions ended after 8 minutes idle; they now last 30 minutes unless you set `ENS_SESSION_IDLE_TIMEOUT`.

Sessions are kept in the Portal database (`ENS_SESSION_STORE=jdbc`, the default: Spring Session JDBC, tables `SPRING_SESSION` and `SPRING_SESSION_ATTRIBUTES`), shared by every Portal replica and kept across restarts and upgrades; `memory` keeps them in the Portal's memory, as releases up to 1.5 did. Session attributes are read back only through an allow-list of the classes the Portal keeps in a session, with depth and size limits, and the refused class is named in the log; an attribute that cannot be read counts as absent, so the user is at worst signed out. No passcode, PIN or emergency access code is written to the session table: emergency access codes stay in the memory of the replica that made them (see [High availability](availability-and-sizing.md#high-availability)).

## Authorization

### Roles

| Role (`AuthorityRole`) | Admin role? | How it is granted | Typical holder |
|---|---|---|---|
| `TRUSTED_AGENT` | yes | User holds the AM admin role in `role.mapped.N.rsaSecurityGuid` where `role.mapped.N.name=TRUSTED_AGENT` | Staff who issue tokens at a site |
| `PMO` | yes | Mapped AM admin role (`PMO`) | MFA program office and administrators |
| `HELP_DESK` | yes | Mapped AM admin role (`HELP_DESK`) | Service desk |
| `USER` | no | Default when none of the user's AM admin roles is mapped | Everyone else |

A user can hold several roles. Roles are evaluated at sign-in. After a role change in AM, or in **Manage User Roles**, the user must sign in again.

### How rules are enforced

* **Method security is on** (`@EnableMethodSecurity`), so every `@PreAuthorize` rule on a controller is enforced by the server, not only by hiding menu entries.
* **URL rules** in `WebSecurityConfig` back them up: `/rest/admin/**` and the system-notification list, save and delete need `PMO`; `/appointment/confirm` and `/appointment/updateNotes` need `TRUSTED_AGENT`; everything not listed as public needs a signed-in user.
* **Public pages** (no sign-in): the home page, the Trusted Agent directory and details (`/data/tas/active` lists active agents only), scheduling an appointment and its details and cancel link, Test My Token, PIN reset, token registration, exemption request and confirmation, the current system notifications (`GET /systemNotification/notExpired`), the login pages and the consent banner.
* **Every 403** is audited as `access.denied` with its reason: role, `license_required`, `license_read_only`, `feature_not_licensed`, `csrf_failure` or `demo_read_only`.

### Feature access matrix

From the `@PreAuthorize` annotations on the controllers. "Public" means the URL is reachable without signing in.

| Feature | Public | USER | HELP_DESK | TRUSTED_AGENT | PMO |
|---|:-:|:-:|:-:|:-:|:-:|
| Home, Trusted Agent directory and details, schedule an appointment, appointment details and cancel | ✔ | ✔ | ✔ | ✔ | ✔ |
| Test My Token, Reset My PIN, token registration, exemption request and confirmation | ✔ | ✔ | ✔ | ✔ | ✔ |
| Swap to a replacement token | | ✔ | ✔ | ✔ | ✔ |
| User Search and User Details (unlock, AD group toggle) | | | ✔ | ✔ | |
| Open and run [help-desk requests](help-desk.md): reset a user's PIN, emergency access code, mark a token lost, deprovision an assigned token, clear the cached Windows password | | | ✔ | ✔ | |
| Deprovision a token assigned to nobody | | | ✔ | ✔ | |
| See **Help-Desk Requests**; approve or reject a request (an approver role from `rsa.helpdesk.dual-control.approver-roles`, never one's own request or account) | | | ✔ | ✔ | ✔¹ |
| Assign (distribute) a token | | | | ✔ | |
| Site list, site details (from the next release; before, any signed-in user) and add; shipments (list, details, request, receive, cancel, not received, print label, modify); site report; My TA Profile; confirm appointments and edit their notes | | | | ✔ | |
| Edit a site, change its status, delete it, add or remove serial ranges | | | | | ✔ |
| Work shipments (status) and send them; delete shipments | | | | | ✔ |
| Register, edit, (de)activate and delete Trusted Agents; bulk CSV upload; TA report; overview report | | | | | ✔ |
| Admin page: system notifications, group email, run scheduled jobs, role management, license, audit log | | | | | ✔ |
| Review, approve, deny and delete exemption requests | | | | | ✔ |

¹ PMO users can open the list and decide a request only once `PMO` is added to `rsa.helpdesk.dual-control.approver-roles`; the menu entry is shown to Trusted Agents and Help Desk users.

!!! note
    `PMO` does not include `TRUSTED_AGENT`. A PMO user who also needs the Trusted Agent pages must also hold the Trusted Agent role.

The license also gates features (`self-service`, `trusted-agents`, `help-desk`, `shipments`, `scheduled-jobs`, `branding`, `reports`, `bulk-upload`, `group-email`); see [Licensing](../licensing.md).

## CSRF and browser security headers

* **CSRF protection is on** (Spring Security's default). Thymeleaf adds the token to every form, and `static/js/app.js` sends it as a header on non-GET AJAX calls. A request without it is refused with 403 and audited with the reason `csrf_failure`. The two exceptions take a bearer token and keep no session, so a browser's cookie is no use on them: the audit pull API (`/api/audit/**`) and the integration API (`/api/v1/**`).
* Every response carries `Content-Security-Policy` (scripts, styles, images, fonts and connections from the Portal itself only; no framing; forms post only to the Portal), `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: strict-origin-when-cross-origin` and `Permissions-Policy: camera=(), microphone=(), geolocation=(), payment=()`. Requests over HTTPS also get `Strict-Transport-Security: max-age=31536000; includeSubDomains`. The pages run no inline script: each page's script is a file under `static/js/pages`.
* `ENS_CSP_REPORT_ONLY=true` sends the policy as `Content-Security-Policy-Report-Only`: a stopgap for a page the policy breaks.
* The session cookie is `HttpOnly` and `SameSite=Lax`. It is `Secure` on requests the Portal itself received over TLS, or always with `ENS_COOKIE_SECURE=true`.
* Behind a proxy, load balancer, ingress or route that terminates TLS, set `SERVER_FORWARD_HEADERS_STRATEGY=native` and `ENS_COOKIE_SECURE=true`, never `framework`. See [Behind a proxy](#behind-a-proxy).

## Rate limits and uniform answers

The steps anyone can reach without signing in are rate-limited, back off after repeated failures,
and give one answer whatever went wrong. The server enforces all of it.

**What is limited.** POSTs to sign-in (`/perform_login` and the follow-up prompts), Test My Token,
the three PIN resets (Windows password, certificate, and the e-mailed link's tokencode check and
reset), registration (both steps), exemption requests, and appointment booking and cancelling. The
help desk's e-mailed codes and tokencode checks are limited per operator and per user.

**How it is counted.** Each attempt counts against two limits: one for the client address, and one
for each user ID or token serial it names. The counts are kept in fixed windows in the Portal
database (`RateLimitWindow`, `RateLimitBackoff`), so every Portal node counts together. User IDs,
serials and addresses are stored only as SHA-256 hashes, and expired rows are deleted every 10
minutes. Paths are matched as Spring Security and Spring MVC match them, so a percent-encoded path
counts the same. Once a client's address is refused, the user IDs and serials it sends stop being
counted, which keeps the tables bounded.

**Fixed windows.** The windows are aligned to the clock (UTC) and do not slide: a `10m` limit
counts from :00, :10, :20 and so on, a `1h` limit from each full hour, and a `24h` limit from
midnight UTC. When a window ends the full allowance is available again, so up to twice the
allowance can arrive in a short time around a window's end. `Retry-After` says when the current
window ends. The one exception is the PIN reset link's cap (below), a single window that starts
when the link is issued.

**Over a limit** the answer is `429 Too Many Requests` with `Retry-After` (in seconds) and
`Cache-Control: no-store`, and Authentication Manager is not asked. Browsers get a short page;
clients that ask for JSON get `{"error":"rate_limited","retryAfter":N}`. The first refusal in each
window is audited as `auth.rate-limited` with `details.flow`, `details.limit` and
`details.retryAfterSeconds`.

**Backoff**, per user ID and per token serial, in every flow: the first three failures are free.
After that, each failure makes the next attempt wait 30 seconds, then twice as long each time, up to
15 minutes. A success clears it. The count starts again only after an hour
(`rsa.rate-limit.backoff.forget-after`) with no failure: this is not a rolling hour, so failures
keep adding up as long as each comes within an hour of the one before. Someone who fails on
purpose can make a user ID wait, but never for more than 15 minutes at a time. Authentication
Manager's own lockout policy still applies.

**Feature caps.** Three limits that belong to their features are counted in the database too, and
hold even with `rsa.rate-limit.enabled=false`:

- Test My Token: `user.token.test.maxAttempts` (5) attempts per user ID per
  `user.token.test.windowMinutes` (15), in fixed windows;
- the PIN reset link: `cron.tokenPinCheck.resetLinkMaxAttempts` (5) tokencodes per link, over the
  link's whole life from its issue;
- the token swap: 5 tokencodes per user per 15 minutes, in fixed windows, each counted before the
  code is checked;
  the right code clears the count.

Reaching the first two is audited as `auth.lockout`. In the read-only demo, whose database takes no
writes, these caps are counted in each node's memory and the other limits are off.

**One answer whatever the cause:**

| Step | What everyone gets |
|---|---|
| Sign-in | *Sign-in failed. Check your user ID and passcode (PIN + tokencode).* |
| Test My Token | One message for a wrong passcode, an unknown user, or a locked or disabled account |
| Windows-password PIN reset | One message for an unknown user, a wrong password, a serial that is not the user's, and a user with no tokens |
| Registration | One message for an unknown user, no token waiting for a PIN, a PIN already set, a wrong code, and a locked or disabled account. PIN rules and directory messages are still shown once the code has been accepted. The second step looks up nothing unless that browser proved the user ID with a tokencode in the first |
| Exemption request | *Your request has been received*, whether the request was saved and its confirmation e-mail sent, the user ID is unknown, or the account has no e-mail address. A certificate whose request was already reviewed is still told so |
| Appointment booking | *Your request has been received* in every case. The details and the link to change or cancel go by e-mail to the address Authentication Manager has for the user, so only that user learns whether a booking was made |

The cause is recorded only in the audit trail: `auth.login` with its `reason`;
`auth.self-service-failed` with `details.flow` and the reason (for example `bad_password`,
`no_tokens`, `serial_not_users`, `wrong_tokencode`, `no_token_waiting`, `pin_already_set`,
`account_locked`, `unknown_user` or `no_email`); and `appointment.request-refused` with
`unknown_user`, `user_has_token`, `too_many_open` or `authmanager_error`.

A failed step answers no sooner than `rsa.rate-limit.uniform-response-time` (1 second) after it
began, plus up to a tenth of that at random, so an unknown user and a wrong value take as long as
each other. Appointment booking and exemption requests always take this long.

**No CAPTCHA.** Portal deployments are often air-gapped, and CAPTCHA services need the internet and
send visitors' data to a third party. The limits above take their place.

**Known limits:**

- A **successful** sign-in or self-service step is not padded to the uniform time.
- Backoff belongs to the user ID, so someone who fails on purpose can make a user wait, up to 15
  minutes at a time.
- Every client must have its own address: behind a proxy, see [Behind a proxy](#behind-a-proxy).

The settings, with their defaults, are in
[Configuration → Rate limits](configuration.md#rate-limits).

## Behind a proxy

When TLS ends at a proxy, load balancer, ingress or route in front of the Portal, set
`SERVER_FORWARD_HEADERS_STRATEGY=native` and `ENS_COOKIE_SECURE=true`. With `native`, Tomcat takes
the original scheme, host, port and client address from `X-Forwarded-Proto`, `-Host`, `-Port` and
`-For`, but only on requests from an internal proxy (`SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES`, by
default the private ranges). It reads `X-Forwarded-For` from right to left: the first address that
is not an internal proxy is the client. It ignores `Forwarded`.

Without it, the Portal sees plain http (no HSTS, redirects to `http://`), the audit trail records
the proxy's address, and every visitor shares the proxy's per-address rate limit.

!!! danger "Never `framework`"
    `SERVER_FORWARD_HEADERS_STRATEGY=framework` believes a `Forwarded` header and the left-most
    `X-Forwarded-For` entry, both of which the client writes. A client could then choose the address
    the audit trail records, and get a fresh rate limit with every request.

`SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES` (Helm: `forwardedHeaders.internalProxies`) takes
comma-separated CIDR ranges, **each with its prefix length** (`/32` for one IPv4 address, `/128` for
one IPv6 address). Tomcat reads a value without a `/` as a regular expression, so
`10.0.0.5, 10.0.0.6` would match no proxy at all; the Helm chart's schema refuses such a value.

**A proxy that appends.** Entries a client writes at the front of `X-Forwarded-For` count only when
every entry after them is an internal proxy. That matters when a proxy **appends** to the header the
client sent, rather than replacing it, and clients' own addresses are in the private ranges: the
client's real address is then skipped as an "internal proxy", and the one it wrote counts. Narrow
`SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES` to the proxies' own addresses, or make the proxy replace
the header.

| Topology | What it needs |
|---|---|
| No proxy: clients reach the Portal directly | Nothing. Leave `SERVER_FORWARD_HEADERS_STRATEGY` unset, so nobody's `X-Forwarded-*` headers count |
| Helm, ingress-nginx terminating TLS (`ingress.tls`) | The chart sets `native` and `ENS_COOKIE_SECURE=true`. ingress-nginx replaces `X-Forwarded-For` with the client's address by default. If the controller runs with `use-forwarded-headers` or `compute-full-forwarded-for` (for example behind a cloud load balancer), set `forwardedHeaders.internalProxies` to the controller's and the load balancer's ranges. If the pod network is not in the private ranges, add it |
| Helm, OpenShift Route `edge` or `reencrypt` | The chart sets `native` and `ENS_COOKIE_SECURE=true`, and annotates the Route with `haproxy.router.openshift.io/set-forwarded-headers: replace`, so the router replaces `X-Forwarded-For` instead of appending (its default). Keep that annotation |
| Release package or a server, behind nginx, Apache httpd, HAProxy, an F5 or another reverse proxy | Set `SERVER_FORWARD_HEADERS_STRATEGY=native` and `ENS_COOKIE_SECURE=true` in `.env`. Make the proxy replace `X-Forwarded-For` with the client's address (nginx: `proxy_set_header X-Forwarded-For $remote_addr;`; HAProxy: `http-request set-header X-Forwarded-For %[src]`) and set `X-Forwarded-Proto`. If it appends instead (nginx `$proxy_add_x_forwarded_for`, httpd `mod_proxy`), set `SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES` to the proxy's own address as a CIDR range (`/32`). Only the proxy may reach the Portal's port |
| TLS passed through to the Portal (Helm `tls.enabled`, for client certificates) | Neither setting. Whatever forwards the connection (ingress-nginx's ssl-passthrough, an OpenShift passthrough route) hides the client's address, so all clients share its per-address limit; the per-user limits and backoff still apply. Raise `rsa.rate-limit.*.per-ip`, or expose the Service so that client addresses are kept (a load balancer with `externalTrafficPolicy: Local`) |

## Actuator and monitoring

| Endpoint | Who can call it |
|---|---|
| `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness` | Anyone. Health details (database, Connector, disk) only for PMO users |
| `/actuator/prometheus` | Only on the management port (`MANAGEMENT_SERVER_PORT`), without sign-in. Not exposed at all without that port |
| Any other `/actuator/**` | PMO users. Only `health` and `prometheus` are exposed, so there is nothing else to call |

Never publish the management port to users. The release package's `docker-compose.yml` does not publish it; the Helm chart puts it on the Service as `metrics` and never on the ingress or route.

## TLS and client certificates

* The release package publishes **8080** (HTTP) and **8443**, but configures **no** `server.ssl.*` settings by default. Configure TLS on 8443 in `.env` with the standard Spring Boot properties, using keystores placed in `default_build/ssl/` (mounted at `/ssl`). Do not expose 8080 outside the host once HTTPS is in place. With Helm, set `tls.*` and pass TLS through to the pod, or terminate it at the ingress (then the client-certificate features see no certificate). See [Installation](install.md#6-ingress-openshift-and-client-certificates).
* Several public features read the browser's **X.509 client certificate** from the servlet attribute `jakarta.servlet.request.X509Certificate` (`CertUtil`):

    | Feature | How the certificate DN is used |
    |---|---|
    | Token enrollment (`/user/registration/complete`) | Stored on the enrollment record (`NOCERT` if no certificate was presented) |
    | PIN reset with certificate (`/user/pinReset/certReset`) | Must match the DN recorded at the user's last enrollment, or an existing certificate-to-user mapping (`CertTokenMapping`). Required. |
    | PIN-check link (`/user/pinReset/start/{code}`) | Not required, and no certificate mapping is recorded; a DN, if presented, goes only into the audit record. The reset needs the emailed link and the code the link's token shows now (no PIN). One link accepts at most `cron.tokenPinCheck.resetLinkMaxAttempts` (5) codes; reaching the limit is audited as an `auth.lockout` alert with `details.feature=pin-reset-link`. A reset is audited as `token.pin-reset-by-user` with the token serial and `details.method=reset-link`. |
    | Exemption request (`/user/exemption/request`) | Required. The request is tied to the DN. |
    | Appointment cancel by the requester | Allowed only when the DN matches the DN recorded when the appointment was booked |

  These features need TLS terminated **by the Portal** with client-certificate negotiation enabled (for example, `server.ssl.client-auth=want` and a trust store holding the user PKI CAs). The Portal reads no certificate header, so a TLS-terminating proxy in front of it hides the certificate. The Portal does not check client certificates for revocation; the platform baseline's X.509 revocation settings apply to CertAlert, the Directory Services Portal and ReadyRoom only.

## The Portal-to-Connector hop

The Connector holds the AM administrator's session, so the hop to it is protected in four ways:

| Control | What it does |
|---|---|
| HTTPS | The Portal calls `https://connector:8443/app/connector` (TLS 1.2 or 1.3) and checks the Connector's certificate and host name against `CONNECTOR_TLS_TRUSTSTORE`. There is no setting that turns the check off. A plain `http` `CONNECTOR_URL` logs a warning |
| Shared secret | Every request carries HTTP Basic credentials: user `connector.auth.user` (default `portal`) and `CONNECTOR_AUTH_PASSWORD`, the same value in the Portal's and the Connector's settings. The Connector compares them and rejects every request while no password is set |
| Mutual TLS (on in new installations) | `CONNECTOR_TLS_CLIENT_AUTH=required` on the Connector: only clients with a certificate from `CONNECTOR_TLS_CLIENT_CA` connect, and the Portal presents `CONNECTOR_TLS_CLIENTCERTIFICATE` and `CONNECTOR_TLS_CLIENTKEY`. Without it, any client that reaches port 8443 gets as far as the shared secret. A new installation has it, from the release package's templates or the Helm chart; an upgrade keeps an installation as it was, and while it is off the Connector logs a warning at every start. See [Mutual TLS](rsa-integration.md#mutual-tls) |
| Network | Plain HTTP on 8080 listens on the Connector container's loopback address only, for its health check (`CONNECTOR_HTTP_ADDRESS`; `0.0.0.0` is for the demo only). HTTPS on 8443 listens on every interface unless `CONNECTOR_HTTPS_ADDRESS` names one. With Helm, a NetworkPolicy admits only the Portal's pods to the Connector, even when `networkPolicy.enabled` is `false` (`networkPolicy.connector.enabled`, default `true`) |

* The release package makes a CA of the installation's own and the certificates (`util/connector-tls.sh`, run by `install.sh` and `upgrade.sh`); the Helm chart makes them with Helm's `genCA`/`genSignedCert`, or takes yours from `connector.tls.existingSecret`. See [RSA AM integration](rsa-integration.md#portal-to-connector).
* The Connector's Hessian endpoint rebuilds only the classes the Portal sends: the parameter types of `RSAAuthManagerServiceInterface` and what their fields hold, each named on a list, and nothing else (`AllowListHessianServiceExporter`, ending in a deny-all). Hessian otherwise instantiates any class a request names, and its own fallback list would allow all of `java.*` but a few classes. A class not on the list arrives as a map of its fields, never as an instance of itself. The [exact list](rsa-integration.md#what-the-connector-accepts) is in RSA AM integration.
* `GET` or `HEAD` `/app/health` needs no credentials and answers only `UP` or `DOWN`. Tomcat's error pages show neither stack traces nor the server version.

## The Connector-to-AM hop

The Connector reaches AM over HTTPS with the RSA SDK, which on its own trusts any certificate from the CA in `trust.jks` and reads AM's replies with Java serialization. The Connector adds two checks:

| Control | What it does |
|---|---|
| Host name | AM's certificate must name the host in the `ims.httpinvoker.*` URLs (a DNS name, or an IP address it lists), as well as chain to the CA in `trust.jks`. A certificate for another name fails the connection. `RSA_TLS_VERIFY_HOSTNAME=false` turns the name check off for an AM reached by an IP address or a name its certificate lacks; the Connector then warns at every start that any server with a certificate from that CA can pose as AM. `util/check-env.sh` and the Helm chart's notes warn about an AM URL whose host is an IP address or a name without a domain |
| Deserialization filter | AM's replies are read through a JEP 290 filter, on those replies only: an allow-list of the SDK's commands, data classes and exceptions and the Java types they hold, and limits on nesting depth, object references, array length and size. Anything else is refused, the command fails, and the Connector logs `Refused a reply from Authentication Manager` at WARN. `CONNECTOR_SERIAL_FILTER_ALLOW` and `CONNECTOR_SERIAL_FILTER_LIMITS` extend the list and the limits; `CONNECTOR_SERIAL_FILTER=off` is an emergency switch. See [What the Connector accepts](rsa-integration.md#what-the-connector-accepts) |

Both are on by default. A JVM without serialization filters (not the image's Java 17) stops the Connector rather than read AM's replies unfiltered, and so does an RSA SDK whose internals are not what the Connector expects.

## Secrets

* **AM credentials stay with the Connector.** The release package keeps settings in three files, each given only to its own service: the AM settings and credentials in `connector.env`, the MySQL root password in `mysql.env`, the Portal's settings in `.env`. `install.sh` makes them mode 600. The Portal runs as uid 1001 in group 0 and reads `.env` as its `application.properties`, so `util/file-owners.sh` (run by `install.sh` and `upgrade.sh`) makes `.env` and the Portal's mutual-TLS key (`portal-tls/portal-client.key`) mode 640 with group 0: no other user on the host may read them. Keep the files out of version control. `util/check-env.sh` refuses a `.env` that holds Connector or MySQL settings, including any `RSA_*` variable.
* **The Portal's client key stays with the Portal.** The Connector mounts all of `default_build`, so the Portal's key for mutual TLS is kept in `portal-tls/`, which only the Portal mounts. `util/connector-tls.sh` moves a key an earlier release left in `default_build/ssl`, with the settings naming it, and `util/check-env.sh` warns about a `CONNECTOR_TLS_CLIENTKEY` under `/ssl/`.
* **The help desk's e-mailed codes** are kept only as an HMAC under `rsa.helpdesk.code-key`, never in clear or as a plain hash. See [Help-desk requests → The e-mailed code's key](help-desk.md#the-e-mailed-codes-key).
* **No passwords on the command line.** The Connector reads the Command Client, service-account, read-only-account and trust-store passwords from `RSA_*` variables or from files (`RSA_ADMIN_USER_PASSWORD_FILE` and so on), not from `-D` options in `JAVA_OPTS`, which every user on the host can read. A `-D` password still works, with a warning. The Helm chart mounts them from Secrets as files under `/var/run/secrets/rsa`.
* **No sample credentials.** The `application.properties` and `config.properties` packaged in the images hold no passwords.
* **Upgrades from older layouts.** `upgrade.sh` splits a single `.env` (keeping `.env.before-split.<time>`) and moves `-D` passwords out of `connector.env` (keeping `connector.env.before-secrets.<time>`). Both copies hold the old secrets, mode 600: delete them once the new version runs.
* With Helm, keep secrets in Kubernetes Secrets (`existingSecret` values), not in a values file you commit.

## Least privilege in Authentication Manager

The Connector's service account no longer needs Super Admin. Give it, and the optional read-only account, the custom AM administrative roles in [RSA AM integration → Least-privilege AM roles](rsa-integration.md#least-privilege-am-roles). Operators' changes run under their own AM roles, so those roles need the permissions for what each operator does in the Portal.

## Webhooks

Webhooks are the Portal's only connections to addresses an administrator chooses. They are `https`
only, and every address the host resolves to must be public unless the host or range is in
`ens.webhooks.allowed-hosts`; cloud metadata and platform services, Teredo and local-use NAT64 are
refused whatever that says. The host is checked again at every delivery and the connection goes to
the address that was checked, with no redirects and a 15-second deadline. Bodies hold identifiers
only, and are signed with HMAC-SHA256 over a timestamp and the body. Signing secrets are AES-GCM
encrypted under a key derived from `rsa.helpdesk.code-key`. See [Webhooks](webhooks.md).

## Other controls

* **Active Directory over LDAPS** verifies the domain controller's certificate against the trust store in `active-directory.properties[n].trust-store`, or the JVM's default, and JNDI checks the host name. Earlier releases trusted any certificate.
* **Audit trail.** Every sign-in, denial, change and export is recorded in the `ens-audit/1` format in the database, hash-chained. A sign-in, role, settings or license change that cannot be audited does not happen.
* **Every Authentication Manager change is recorded before it is made.** One service wraps the Connector client: it commits `am.change-attempt` before each change, and if that write fails the change is not made (fail closed). A change asked for inside a database transaction that has already recorded an audit event is refused too (`AuditOrderException`): that transaction holds the audit chain until it ends, so the attempt could not be committed first, and recording it with that transaction would lose it if the transaction rolled back after the change. No flow in the Portal does this; the check guards future code. After the change it writes `am.change-outcome`. Both carry the actor (the operator; the user, for their own token; or the system and service account for jobs), the target user, the token serial, the operation, which AM account made it, the help-desk ticket, reason and verification, the client address, and an attempt id linking the two. Tests keep this the only path: every method of the Connector client must be classified as a read, a sign-in or a change, and no other class may call the client. See [Audit logging](../audit-logging.md).
* **Help-desk changes** need a ticket, a verified user, a second operator for privileged users, and notify the user. See [Help-desk requests](help-desk.md).
* **Logging.** User IDs and token serials that people type are escaped before they are logged. In the Portal's and the Connector's text logs, CR and LF in a message are written as `\r` and `\n`, so request data in a message cannot start a line that looks like another entry; a stack trace keeps its own lines. JSON logs escape them anyway. A log configuration of your own should keep this: `%enc{%m}{CRLF}` in the Portal's `log4j2.xml`, and the layout `com.winllc.rsaapp.connector.logging.CrlfSafePatternLayout` in the Connector's `log4j.xml`.
* **Containers.** The Helm chart runs the Portal, the Connector and the embedded MySQL with all capabilities dropped, no privilege escalation, a read-only root file system and the `RuntimeDefault` seccomp profile.
* Email bodies include absolute links built from `application.dns.name`. Set it to the HTTPS URL.

## FIPS 140-3 and DISA STIG

See [FIPS 140-3 and DISA STIG](fips-and-stig.md).
