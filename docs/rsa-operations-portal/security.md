# Security

## Authentication

* **Form login** at `/login`, which posts to `/perform_login`. On success the user goes to `/home`. On failure the user is redirected to `/login?error=true`, `/login?error=locked` (`RsaAccountLockedException`) or `/login?error=disabled` (`RsaAccountDisabledException`).
* Credentials are the user's **RSA AM User ID and SecurID passcode**. `CustomAuthenticationProvider` sends them to AM through the connector. If AM asks for more input (next tokencode, new PIN, new PIN confirmation, passcode, principal ID, new static passcode), the user is redirected to `/additionalParameterLoginHandler` to answer each prompt.
* The authenticated principal is an `RSAAuthenticationToken` that carries the AM **session ID** and **principal GUID**. The connector uses them to run administrative commands as that user, so AM enforces the user's own permissions.
* **Logout** is `/perform_logout`. It deletes `JSESSIONID` and also logs the AM session out (`CustomLogoutSuccessHandler`).
* If AM reports the user's session has ended during an operation, `GlobalControllerExceptionHandler` logs the user out and redirects to `login`.
* Session timeout: `server.servlet.session.timeout=480`.

## Roles

| Role (`AuthorityRole`) | Admin role? | How it is granted | Typical holder |
|---|---|---|---|
| `TRUSTED_AGENT` | yes | User holds the AM admin role in `role.mapped.N.rsaSecurityGuid` where `role.mapped.N.name=TRUSTED_AGENT` | Staff who issue tokens at a site |
| `PMO` | yes | Mapped AM admin role (`PMO`) | MFA program office and administrators |
| `HELP_DESK` | yes | Mapped AM admin role (`HELP_DESK`) | Service desk |
| `USER` | no | Default when none of the user's AM admin roles is mapped | Everyone else |

A user can hold several roles. Roles are evaluated at login. After a role change in AM, or in **Manage User Roles**, the user must sign in again.

### Feature access matrix

This matrix reflects the `@PreAuthorize` annotations and the menu visibility (`sec:authorize`) in the templates. "Public" means the URL pattern is `permitAll` in `WebSecurityConfig`.

| Feature | Public | USER | HELP_DESK | TRUSTED_AGENT | PMO |
|---|:-:|:-:|:-:|:-:|:-:|
| Home, Trusted Agent directory and details, schedule an appointment | ✔ | ✔ | ✔ | ✔ | ✔ |
| Test My Token, Reset My PIN, token enrollment (`/user/registration/**`), exemption request | ✔ | ✔ | ✔ | ✔ | ✔ |
| User Search and User Details (unlock, emergency code, clear cached Windows password, AD group toggle) | | | ✔ | ✔ | |
| Mark token lost, reset a user's PIN, deprovision a token | | | ✔ | ✔ | |
| Assign (distribute) a token | | | | ✔ | |
| Sites (list, add, edit, status), shipments (list, request, receive, cancel, not received, print label), site report, My TA Profile, confirm appointments | | | | ✔ | |
| Edit a site, delete a site, add or remove site serial ranges | | | | | ✔ |
| Work and send shipments (UI), delete shipments | | | | | ✔ |
| Register, edit, (de)activate, delete Trusted Agents; bulk CSV upload; TA report | | | | | ✔ |
| Admin page: system notifications, group email, manual cron, role management | | | | | ✔ |
| Review, approve, deny and delete exemption requests | | | | | ✔ |

!!! note
    The PMO menu does not include the Trusted Agent pages. A PMO user who also needs those pages must also hold `TRUSTED_AGENT`. The UI shows the "work shipment" and "send shipment" buttons only to PMO, but the server-side annotations on `/shipment/updateStatus` and `/shipment/send/**` require `TRUSTED_AGENT`.

## TLS and client certificates

* The release package publishes **8080** (HTTP) and **8443**, but configures **no** `server.ssl.*` settings by default. Configure TLS on 8443 in `.env` with the standard Spring Boot properties, using keystores placed in `default_build/ssl/` (mounted at `/ssl`). Do not expose 8080 outside the host once HTTPS is in place. With Helm, set `tls.*` and pass TLS through to the pod, or terminate it at the ingress (then the client-certificate features see no certificate). See [Installation](install.md#6-ingress-openshift-and-client-certificates).
* Several public features read the browser's **X.509 client certificate** from the servlet attribute `jakarta.servlet.request.X509Certificate` (`CertUtil`):

    | Feature | How the certificate DN is used |
    |---|---|
    | Token enrollment (`/user/registration/complete`) | Stored on the enrollment record (`NOCERT` if no certificate was presented) |
    | PIN reset with certificate (`/user/pinReset/certReset`) | Must match the DN recorded at the user's last enrollment or by an emailed PIN-check link (`CertTokenMapping`). Required. |
    | PIN-check link (`/user/pinReset/start/{code}`) | Records a certificate-to-user mapping for later self-service reset |
    | Exemption request (`/user/exemption/request`) | Required. The request is tied to the DN. |
    | Appointment cancel by the requester | Allowed only when the DN matches the DN recorded when the appointment was booked |

  These features need TLS terminated **by the portal** with client-certificate negotiation enabled (for example, `server.ssl.client-auth=want` and a trust store holding the user PKI CAs). A TLS-terminating proxy in front of the portal will not pass the certificate through this attribute.
* The connector reaches AM over TLS and trusts AM through `trust.jks`. The portal-to-connector Hessian hop is plain HTTP on the internal Docker network.

## Known security considerations

The following points were found by reading the source. Assess them for your environment.

!!! danger "Method-level authorization is not enabled"
    The controllers and REST endpoints rely on `@PreAuthorize("hasAuthority(...)")`. In Spring Security 6 (Spring Boot 3), these annotations take effect only when method security is switched on with `@EnableMethodSecurity`. `WebSecurityConfig` imports `EnableGlobalMethodSecurity` but **does not apply** any method-security annotation, and no other configuration class does. As written:

    * any **authenticated** user can call the role-restricted URLs directly (the menus are only hidden);
    * URL patterns marked `permitAll`, namely **`/data/**`** (DataTables JSON for Trusted Agents, shipments, sites and exemption requests), **`/rest/**`** (including `/rest/admin/runExpiringCron`, `/rest/admin/upcomingAppointmentsCron`, `/rest/admin/reSyncTrustedAgentsCron`) and **`/systemNotification/**`** (including `POST /save` and `DELETE /delete/{id}`), are reachable **without signing in**.

    Recommended fix: add `@EnableMethodSecurity` to `WebSecurityConfig`, and narrow the `permitAll` list to the public pages only.

!!! warning "CSRF protection is disabled"
    `http.csrf().disable()` is set. Every state-changing form (token assignment, PIN reset, role changes, deletes) is open to cross-site request forgery from a signed-in user's browser.

!!! warning "Actuator is public"
    `/actuator/**` is `permitAll`. Spring Boot 3 exposes only `health` over HTTP by default. Do not enable more endpoints without restricting this pattern.

!!! warning "Secrets"
    * The `application.properties` and connector `config.properties` packaged in the images contain sample credentials. Override every one, and rotate anything that was ever deployed with them.
    * The release package's `.env` holds every secret in plain text. `install.sh` makes it mode 600; keep it that way and keep it out of version control.
    * With Helm, keep secrets in Kubernetes Secrets (`existingSecret` values), not in a values file you commit.

!!! warning "LDAP trust-all socket factory"
    `ActiveDirectorySocketFactory` disables certificate validation for LDAPS. Use it only if you accept that risk, or use a factory that validates certificates.

!!! info "Other observations"
    * `install.sh` runs `chmod -R 777` on the `mysql/` directory.
    * The appointment REST endpoint `/rest/appointment/appointmentTimesForTaOnDate/{date}/{taRsaId}` and the Trusted Agent directory are public by design.
    * Email bodies include absolute links built from `application.dns.name`. Set it to the HTTPS URL.
