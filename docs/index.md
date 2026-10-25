# ENS Applications Documentation

Documentation for the four applications in the ENS suite. Each application has the same core
set of guides, so you can find the same kind of information in the same place for every one.

<div class="grid cards" markdown>

-   **CertAlert**

    ---

    Sweeps an LDAP directory for the certificates people and servers publish, caches the
    parsed detail behind searchable tables, checks revocation, and warns points of contact
    before certificates expire.

    [:octicons-arrow-right-24: CertAlert](cert-alert/index.md)

-   **Directory Services Portal**

    ---

    A web front end for LDAP directories with custom schemas: admin-defined forms,
    namespace-scoped directory definitions, per-user and per-group permissions, self-service
    profiles, metrics and data-quality analysis.

    [:octicons-arrow-right-24: Directory Services Portal](directory-services-portal/index.md)

-   **ReadyRoom**

    ---

    Attendance and presence tracking. A PowerShell agent on each Windows workstation reports
    logon, lock, unlock and logoff. The server turns those reports into daily status, group
    rollups, an org chart and absence alerts for managers.

    [:octicons-arrow-right-24: ReadyRoom](readyroom/index.md)

-   **RSA Operations Portal**

    ---

    Day-to-day RSA SecurID token operations in front of RSA Authentication Manager:
    enrollment, testing, PIN resets, lost tokens, shipments, appointments and exemptions.

    [:octicons-arrow-right-24: RSA Operations Portal](rsa-operations-portal/index.md)

</div>

## Licensing

Each application needs a license file from ENS Solutions, issued through the ENS licensing portal,
where customers can also take a free 30-day evaluation. See [Licensing](licensing.md) for what each
license unlocks and where the file goes.

## Releases and downloads

Releases are downloaded from the licensing portal's **Downloads** page: every version of each
product you hold a license for, an evaluation included. See [Releases and downloads](releases.md)
for the files and how to verify them.

## Audit logging

Every application keeps the same tamper-evident audit trail. Administrators can search and export it
in the application, and it can be sent to Splunk or any other SIEM. See [Audit logging](audit-logging.md).

## FIPS 140-3 and DISA STIG

The applications take their cryptography from the Java runtime (and, for the Directory Services
Portal's web tier, nginx and OpenSSL) on Red Hat UBI 9 images, and share one platform baseline
of DISA STIG controls. ENS Solutions does not claim FIPS 140-3 validation. The license check works
on a Java runtime in FIPS mode with a license file that carries an ECDSA P-256 signature; older files
need re-signing first. See [FIPS 140-3 and DISA STIG](fips-and-stig.md).

## Sizing, availability and recovery

Each application can run as several replicas on Kubernetes, with sessions, job locks and the
license state in its database (the RSA Operations Portal's Connector stays at one). See
[Sizing, availability and recovery](availability-and-sizing.md) for sizing, what to back up,
disaster recovery and rollback across the suite.

## How each section is organised

| Guide | Read it if you… |
|---|---|
| **Overview** | want to know what the application does and how it is put together |
| **Install guide** | are installing the application for the first time, from the release package (Docker or Podman) or the Helm chart, trying the demo, or upgrading |
| **Configuration guide** | need the full list of settings and environment variables |
| **Operator guide** | keep the application running: start/stop, health, logs, backups, upgrades |
| **Admin guide** | administer the application from inside it: roles, permissions, settings |
| **User guide** | use the application day to day |

Most sections also include **Architecture**, **Security**, **API reference** and
**Troubleshooting** pages. Every section has **Single sign-on**, **Webhooks**, **Accessibility**,
**Sizing, availability and recovery** and **FIPS 140-3 and DISA STIG** pages. The RSA Operations Portal adds an **RSA AM integration** page. ReadyRoom adds a
**Windows client** page.

## At a glance

| | CertAlert | Directory Services Portal | ReadyRoom | RSA Operations Portal |
|---|---|---|---|---|
| Server | Spring Boot 4 / Java 21 | Spring Boot 4 / Java 21 + React | Spring Boot 4 / Java 21 | Spring Boot 4 / Java 17 + Tomcat 9 connector |
| Data store | PostgreSQL | JSON configuration and audit files, or PostgreSQL for several instances | PostgreSQL | MySQL 8.4 |
| Directory | LDAP (IC FSD schema) | LDAP (any schema) | LDAP | RSA Authentication Manager |
| Sign-in | X.509, directory password, or single sign-on (OIDC, SAML) | X.509, directory password, or single sign-on (OIDC, SAML) | X.509, LDAP password, single sign-on (OIDC, SAML), Kerberos (client) | RSA AM user ID + SecurID passcode; operators can start with single sign-on (OIDC, SAML) |
| Install | Release package (Docker or Podman Compose) or Helm chart | Release package (Docker or Podman Compose, or native systemd) or Helm chart | Release package (Docker or Podman Compose) or Helm chart, plus the Windows agent | Release package (Docker or Podman Compose) or Helm chart |
| Public API | `/api/v1` with scoped API tokens, OpenAPI document | `/api/v1` with scoped API tokens, OpenAPI document | Read-only `/api/v1` with scoped API tokens, OpenAPI document | `/api/v1` with scoped API tokens; changes are help-desk requests |
| Webhooks | Expiry alerts and job results (JSON, Slack, Teams), HMAC-signed | Entry, membership and form events, HMAC-signed | Absence alerts and status changes, HMAC-signed | Help-desk, token and account events, HMAC-signed |
| Exports | Certificate inventory as CSV or Excel, saved views, scheduled reports | Entries and group members as CSV or LDIF, import with a dry run | Group reports and people lists as CSV or Excel, scheduled reports | Trusted Agent and site reports; the audit log as CSV or NDJSON |
| Accessibility | WCAG 2.1 AA target, draft VPAT | WCAG 2.1 A/AA checks, draft VPAT | WCAG 2.1 AA target, draft VPAT | WCAG A/AA checks, draft VPAT |
| Demo | `docker-compose.demo.yml` | `docker-compose.demo.yml` | `docker-compose.demo.yml` | `docker-compose.demo.yaml` |

!!! note "Source of truth"
    These pages were written from each application's source code. Where the code and a
    repository README or on-screen text disagree, the pages follow the code and say so.
