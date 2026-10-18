# Security

This page describes the Portal's and the Connector's security controls as the code on the current
release implements them, and the settings that change them.

## Authentication

* **Form login** at `/login`, which posts to `/perform_login`. On success the user goes to `/home`. On failure the user is redirected to `/login?error=true`, `/login?error=locked` (`RsaAccountLockedException`) or `/login?error=disabled` (`RsaAccountDisabledException`).
* Credentials are the user's **RSA AM User ID and SecurID passcode**. `CustomAuthenticationProvider` sends them to AM through the Connector. If AM asks for more input (next tokencode, new PIN, new PIN confirmation, passcode, principal ID, new static passcode), the user is redirected to `/additionalParameterLoginHandler` to answer each prompt.
* The Portal keeps no passwords. Failed sign-ins and account lockout are AM's: its own lockout policy applies. Each sign-in is audited as `auth.login`, and a locked account is recorded as an alert.
* The authenticated principal is an `RSAAuthenticationToken` that carries the AM **session ID** and **principal GUID**. The Connector uses them to run the operator's changes in the operator's own AM session, so AM enforces that user's permissions and its audit log names them (see [RSA AM integration](rsa-integration.md#who-runs-each-command)).
* **Logout** is `/perform_logout`. It deletes `JSESSIONID` and also logs the AM session out (`CustomLogoutSuccessHandler`).
* If AM reports the user's session has ended during an operation, `GlobalControllerExceptionHandler` logs the user out and redirects to `/login`.
* **Attempt limits on public self-service.** *Test My Token* allows `user.token.test.maxAttempts` (5) attempts per user ID in `user.token.test.windowMinutes` (15). One PIN reset link accepts at most `cron.tokenPinCheck.resetLinkMaxAttempts` (5) tokencodes. Reaching either limit is audited as `auth.lockout`. The counters are held in memory, per Portal process.

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
| User Search and User Details (unlock, emergency code, clear cached Windows password, AD group toggle) | | | ✔ | ✔ | |
| Mark a token lost, reset a user's PIN, deprovision a token | | | ✔ | ✔ | |
| Assign (distribute) a token | | | | ✔ | |
| Site list and add; shipments (list, details, request, receive, cancel, not received, print label, modify); site report; My TA Profile; confirm appointments and edit their notes | | | | ✔ | |
| Edit a site, change its status, delete it, add or remove serial ranges | | | | | ✔ |
| Work shipments (status) and send them; delete shipments | | | | | ✔ |
| Register, edit, (de)activate and delete Trusted Agents; bulk CSV upload; TA report; overview report | | | | | ✔ |
| Admin page: system notifications, group email, run scheduled jobs, role management, license, audit log | | | | | ✔ |
| Review, approve, deny and delete exemption requests | | | | | ✔ |

!!! note
    `PMO` does not include `TRUSTED_AGENT`. A PMO user who also needs the Trusted Agent pages must also hold the Trusted Agent role.

The license also gates features (`self-service`, `trusted-agents`, `help-desk`, `shipments`, `scheduled-jobs`, `branding`, `reports`, `bulk-upload`, `group-email`); see [Licensing](../licensing.md).

## CSRF and browser security headers

* **CSRF protection is on** (Spring Security's default). Thymeleaf adds the token to every form, and `static/js/app.js` sends it as a header on non-GET AJAX calls. A request without it is refused with 403 and audited with the reason `csrf_failure`. The audit pull API, which takes a bearer token and no session, is the only exception.
* Every response carries `Content-Security-Policy` (scripts, styles, images, fonts and connections from the Portal itself only; no framing; forms post only to the Portal), `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: strict-origin-when-cross-origin` and `Permissions-Policy: camera=(), microphone=(), geolocation=(), payment=()`. Requests over HTTPS also get `Strict-Transport-Security: max-age=31536000; includeSubDomains`. The pages run no inline script: each page's script is a file under `static/js/pages`.
* `ENS_CSP_REPORT_ONLY=true` sends the policy as `Content-Security-Policy-Report-Only`: a stopgap for a page the policy breaks.
* The session cookie is `HttpOnly` and `SameSite=Lax`. It is `Secure` on requests the Portal itself received over TLS, or always with `ENS_COOKIE_SECURE=true`.
* Behind a proxy, load balancer, ingress or route that terminates TLS, set `SERVER_FORWARD_HEADERS_STRATEGY=framework` so the Portal sees the original scheme, host and client address, and `ENS_COOKIE_SECURE=true`. The Helm chart sets both when its ingress (with `tls`) or route (`edge` or `reencrypt`) terminates TLS. Set it only when every request comes through that proxy and the proxy replaces the `X-Forwarded-*` headers.

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
| Mutual TLS (optional) | `CONNECTOR_TLS_CLIENT_AUTH=required` on the Connector: only clients with a certificate from `CONNECTOR_TLS_CLIENT_CA` connect. The Portal then presents `CONNECTOR_TLS_CLIENTCERTIFICATE` and `CONNECTOR_TLS_CLIENTKEY` |
| Network | Plain HTTP on 8080 listens on the Connector container's loopback address only, for its health check (`CONNECTOR_HTTP_ADDRESS`; `0.0.0.0` is for the demo only). With Helm, a NetworkPolicy admits only the Portal's pods to the Connector, even when `networkPolicy.enabled` is `false` (`networkPolicy.connector.enabled`, default `true`) |

* The release package makes a CA of the installation's own and the certificates (`util/connector-tls.sh`, run by `install.sh` and `upgrade.sh`); the Helm chart makes them with Helm's `genCA`/`genSignedCert`, or takes yours from `connector.tls.existingSecret`. See [RSA AM integration](rsa-integration.md#portal-to-connector).
* The Connector's Hessian endpoint rebuilds only the classes the service interface exchanges (`AllowListHessianServiceExporter`). Hessian otherwise instantiates any class a request names.
* `GET` or `HEAD` `/app/health` needs no credentials and answers only `UP` or `DOWN`. Tomcat's error pages show neither stack traces nor the server version.

## Secrets

* **AM credentials stay with the Connector.** The release package keeps settings in three files, each given only to its own service: the AM settings and credentials in `connector.env`, the MySQL root password in `mysql.env`, the Portal's settings in `.env`. `install.sh` makes them mode 600; keep them that way and out of version control. `util/check-env.sh` refuses a `.env` that holds Connector or MySQL settings.
* **No passwords on the command line.** The Connector reads the Command Client, service-account, read-only-account and trust-store passwords from `RSA_*` variables or from files (`RSA_ADMIN_USER_PASSWORD_FILE` and so on), not from `-D` options in `JAVA_OPTS`, which every user on the host can read. A `-D` password still works, with a warning. The Helm chart mounts them from Secrets as files under `/var/run/secrets/rsa`.
* **No sample credentials.** The `application.properties` and `config.properties` packaged in the images hold no passwords.
* **Upgrades from older layouts.** `upgrade.sh` splits a single `.env` (keeping `.env.before-split.<time>`) and moves `-D` passwords out of `connector.env` (keeping `connector.env.before-secrets.<time>`). Both copies hold the old secrets, mode 600: delete them once the new version runs.
* With Helm, keep secrets in Kubernetes Secrets (`existingSecret` values), not in a values file you commit.

## Least privilege in Authentication Manager

The Connector's service account no longer needs Super Admin. Give it, and the optional read-only account, the custom AM administrative roles in [RSA AM integration → Least-privilege AM roles](rsa-integration.md#least-privilege-am-roles). Operators' changes run under their own AM roles, so those roles need the permissions for what each operator does in the Portal.

## Other controls

* **Active Directory over LDAPS** verifies the domain controller's certificate against the trust store in `active-directory.properties[n].trust-store`, or the JVM's default, and JNDI checks the host name. Earlier releases trusted any certificate.
* **Audit trail.** Every sign-in, denial, change and export is recorded in the `ens-audit/1` format in the database, hash-chained. A sign-in, role, settings or license change that cannot be audited does not happen. See [Audit logging](../audit-logging.md).
* **Containers.** The Helm chart runs the Portal, the Connector and the embedded MySQL with all capabilities dropped, no privilege escalation, a read-only root file system and the `RuntimeDefault` seccomp profile.
* Email bodies include absolute links built from `application.dns.name`. Set it to the HTTPS URL.

## FIPS 140-3 and DISA STIG

See [FIPS 140-3 and DISA STIG](fips-and-stig.md).
