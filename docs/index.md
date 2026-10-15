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

Each application runs its core features without a license; a license file from ENS Solutions
turns on the rest. See [Licensing](licensing.md) for what each license unlocks and where the
file goes.

## How each section is organised

| Guide | Read it if you… |
|---|---|
| **Overview** | want to know what the application does and how it is put together |
| **Install guide** | are deploying the application for the first time |
| **Configuration guide** | need the full list of settings and environment variables |
| **Operator guide** | keep the application running: start/stop, health, logs, backups, upgrades |
| **Admin guide** | administer the application from inside it: roles, permissions, settings |
| **User guide** | use the application day to day |

Most sections also include **Architecture**, **Security**, **API reference** and
**Troubleshooting** pages. The RSA Operations Portal adds an **RSA AM integration** page and a
**Build & release** page. ReadyRoom adds a **Windows client** page.

## At a glance

| | CertAlert | Directory Services Portal | ReadyRoom | RSA Operations Portal |
|---|---|---|---|---|
| Server | Spring Boot 4 / Java 21 | Spring Boot 4 / Java 21 + React | Spring Boot 4 / Java 21 | Spring Boot 3 + Tomcat connector |
| Data store | H2 or PostgreSQL | JSON config file | PostgreSQL | MySQL |
| Directory | LDAP (IC FSD schema) | LDAP (any schema) | LDAP | RSA Authentication Manager |
| Sign-in | X.509 or directory password | X.509 or directory password | X.509, LDAP password, Kerberos (client) | RSA AM user ID + SecurID passcode |
| Deployment | Container / Compose | Containers / Compose / packages | Container / jar + Windows agent | docker-compose install package |

!!! note "Source of truth"
    These pages were written from each application's source code. Where the code and a
    repository README or on-screen text disagree, the pages follow the code and say so.
