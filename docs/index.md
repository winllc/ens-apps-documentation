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
where customers can also download a free 30-day evaluation. See [Licensing](licensing.md) for what each license unlocks and where the
file goes.

## Audit logging

Every application keeps the same tamper-evident audit trail. Administrators can search and export it
in the application, and it can be sent to Splunk or any other SIEM. See [Audit logging](audit-logging.md).

## FIPS 140-3 and DISA STIG

The applications take their cryptography from the Java runtime (and, for the Directory Services
Portal's web tier, nginx and OpenSSL) on Red Hat UBI 9 images, and share one platform baseline
of DISA STIG controls. ENS Solutions does not claim FIPS 140-3 validation, and the license check does
not yet work on a Java runtime in FIPS mode. See [FIPS 140-3 and DISA STIG](fips-and-stig.md).

## Sizing, availability and recovery

Each application runs as one instance, with restarts, database high availability and a cold
standby instead of active replicas. See [Sizing, availability and recovery](availability-and-sizing.md)
for sizing, what to back up, disaster recovery and rollback across the suite.

## How each section is organised

| Guide | Read it if you… |
|---|---|
| **Overview** | want to know what the application does and how it is put together |
| **Install guide** | are installing the application for the first time, from the release package or the Helm chart, or upgrading it |
| **Configuration guide** | need the full list of settings and environment variables |
| **Operator guide** | keep the application running: start/stop, health, logs, backups, upgrades |
| **Admin guide** | administer the application from inside it: roles, permissions, settings |
| **User guide** | use the application day to day |

Most sections also include **Architecture**, **Security**, **API reference** and
**Troubleshooting** pages. Every section has a **Sizing, availability and recovery** page and a **FIPS 140-3 and DISA
STIG** page. The RSA Operations Portal adds an **RSA AM integration** page. ReadyRoom adds a
**Windows client** page.

## At a glance

| | CertAlert | Directory Services Portal | ReadyRoom | RSA Operations Portal |
|---|---|---|---|---|
| Server | Spring Boot 4 / Java 21 | Spring Boot 4 / Java 21 + React | Spring Boot 4 / Java 21 | Spring Boot 4 / Java 17 + Tomcat 9 connector |
| Data store | PostgreSQL | JSON configuration and audit files | PostgreSQL | MySQL 8.4 |
| Directory | LDAP (IC FSD schema) | LDAP (any schema) | LDAP | RSA Authentication Manager |
| Sign-in | X.509 or directory password | X.509 or directory password | X.509, LDAP password, Kerberos (client) | RSA AM user ID + SecurID passcode |
| Install | Release package (Compose) or Helm chart | Release package (Compose or native systemd) or Helm chart | Release package (Compose) or Helm chart, plus the Windows agent | Release package (Compose) or Helm chart |

!!! note "Source of truth"
    These pages were written from each application's source code. Where the code and a
    repository README or on-screen text disagree, the pages follow the code and say so.
