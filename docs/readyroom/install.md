# Installation

This page covers a full ReadyRoom deployment: the server, its PostgreSQL database, the LDAP
connection, TLS and client certificates, the optional product page, and the Windows agent on each
workstation. Every setting mentioned here is described in [Configuration](configuration.md).

!!! danger "Do not deploy the committed credentials"
    The repository contains development key material so the test stack works out of the box:
    PKCS#12 keystores under `src/main/resources/` (`winhal.p12`, `truststore.p12`, `localhost.p12`,
    `desktop.p12`), their passwords in `application.yml`, and the local CA's signing keys and
    password under `test/step-ca-data/`. Treat all of it as compromised. Generate new key material
    for any real deployment, and supply production settings through environment variables or an
    external configuration file rather than editing the packaged `application.yml`.

## Prerequisites

| Requirement | Notes |
|---|---|
| JDK 21 | Needed to build. The runtime needs Java 21, either on the host or in the provided container image. |
| PostgreSQL | The PostgreSQL JDBC driver is the only database driver included, and the configured dialect is PostgreSQL. The test stack uses `postgres:16`. |
| LDAP directory | Must hold the people (by default `objectclass=inetOrgPerson`) and the groups (`groupOfUniqueNames` with `uniqueMember`; an optional `owner` is treated as the group's manager). Needs a bind account that can search users and groups. The paged results control is used if it is available. |
| Server certificate | A PKCS#12 keystore for the HTTPS listener. |
| Client certificate trust | A PKCS#12 truststore holding the CA(s) that issue the users' client certificates. |
| SMTP server | Used to email absence alerts. |
| Windows workstations | Windows PowerShell 5.1 and Task Scheduler. Each user needs either a client certificate in `CurrentUser\My` or, with Windows sign-in turned on, a domain account on a domain-joined PC. |
| Docker (optional) | For the container image, the product page and the local test stack. |

## 1. Build the server

=== "Jar"

    ```bash
    ./gradlew clean bootJar
    ls build/libs/
    ```

    The executable jar is written to `build/libs/` (the Gradle project is
    `in-n-out-work`, version `1.0-SNAPSHOT`). Run the tests with `./gradlew test`. Tests that need
    PostgreSQL in a container (Testcontainers) are skipped when Docker is not available.

=== "Container image (root Dockerfile)"

    The root `Dockerfile` packages a jar you have **already built**. It copies `build/libs/*.jar`
    into a Red Hat UBI 9 OpenJDK 21 runtime image with `krb5-workstation` installed:

    ```bash
    ./gradlew clean bootJar
    docker build -t in-n-out-work .
    ```

    !!! warning
        `COPY build/libs/*.jar app.jar` fails if `build/libs/` contains more than one jar, for
        example the `-plain.jar` that Gradle may also produce. Delete any extra jars before running
        `docker build`. The root `Dockerfile` also declares `EXPOSE 8443`, but the application
        listens on `server.port` (default **8444**). Publish the port the application really
        uses.

=== "Container image (multi-stage)"

    `test/Dockerfile` compiles the application from source (`eclipse-temurin:21-jdk`) and then
    copies only the boot jar into the UBI 9 runtime image. It runs as user `185` and exposes
    `8444`. The build context is the repository root:

    ```bash
    docker build -f test/Dockerfile -t in-n-out-work .
    ```

Both images start the app with `java $JAVA_OPTS -jar /app/app.jar`. Set `JAVA_OPTS` to pass JVM
options such as heap size or `-D` system properties.

## 2. Prepare PostgreSQL

1. Create a database and an owner account for ReadyRoom. The defaults in `application.yml` are
   database `appdb`, user `appuser` and password `secret123`. Use your own values.

    ```sql
    CREATE ROLE readyroom LOGIN PASSWORD 'change-me';
    CREATE DATABASE readyroom OWNER readyroom;
    ```

2. Point the application at it with `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` and
   `SPRING_DATASOURCE_PASSWORD`. See [Configuration](configuration.md#database).
3. **Start the application once.** Hibernate (`spring.jpa.hibernate.ddl-auto: update`) creates the
   tables and columns:

    | Table | Holds |
    |---|---|
    | `check_in_out_records` | Every check-in, lock, unlock and check-out event, with the session id and the user's organization attributes at that moment |
    | `user_records` | One row per user DN: role, notes, favorite groups, additional managers, preferred and average login time, and directory metadata |
    | `user_event_records` | Per-day statuses (work from home, leave, late arrival, and so on) |
    | `notification_records` | Absence alerts and managers' responses |
    | `permission_records` | Per-user group grants |
    | `group_records` | Additional managers assigned to a group |
    | `global_calendar_records` | Organization calendar events and holidays |
    | `org_parse_rules` | Org-chart parse rules |

4. **Back up the database, then run the migration script.** Hibernate does not create indexes or
   constraints. `db/migrations/001_indexes_and_unique_user_dn.sql` adds the indexes that the DN
   and date lookups need, and makes the user DN unique regardless of case. Before it adds the
   constraint, it merges any user rows whose DNs differ only by case.

    === "psql"

        ```bash
        psql "postgresql://USER:PASSWORD@HOST:5432/DB" -v ON_ERROR_STOP=1 \
          -f db/migrations/001_indexes_and_unique_user_dn.sql
        ```

    === "PostgreSQL in a container"

        ```bash
        docker exec -i POSTGRES_CONTAINER psql -U USER -d DB -v ON_ERROR_STOP=1 \
          < db/migrations/001_indexes_and_unique_user_dn.sql
        ```

    !!! warning "Do not use `--single-transaction`"
        The indexes are built with `CREATE INDEX CONCURRENTLY`, so the application can keep
        running and writing while they build, and that cannot happen inside a transaction. Only
        the short merge step takes a lock, and only on `user_records`. You can run the script
        again safely: existing indexes are skipped, and an invalid index left by an interrupted
        run is dropped and rebuilt. If the tables do not exist yet, the script stops with an
        error.

## 3. Connect the LDAP directory

Set the connection with the standard Spring LDAP properties, and describe your directory with the
`application.*` properties:

```yaml
spring:
  ldap:
    urls: ldaps://ldap.example.com:636
    username: cn=readyroom-svc,ou=Service,dc=example,dc=com
    password: ${LDAP_BIND_PASSWORD}

application:
  user-base-dn: ou=Users,dc=example,dc=com
  user-ldap-filter: (objectclass=inetOrgPerson)
  groups:
    - groups-base-dn: ou=Groups,dc=example,dc=com
  super-user-dns:
    - cn=Jane Admin,ou=Users,dc=example,dc=com
  user-ldap-organization-attribute: o
  user-ldap-location-attribute: l
  user-ldap-employee-type-attribute: employeeType
  user-ldap-duty-sub-organization-attribute: departmentNumber
  user-ldap-manager-id-attribute: title      # on a user: the id of their manager
  manager-ldap-id-attribute: street          # on a manager: their own id
```

Things to check in the directory:

- **The user's DN must match how they sign in.** A client certificate's full subject DN is used as
  the username and looked up directly as an LDAP DN. If certificate subjects do not equal directory
  DNs, set `lookup-on-dn-attribute: true` and `user-dn-attribute` to an attribute whose value is the
  certificate subject. ReadyRoom then searches for that attribute under `user-base-dn` instead.
- **Form login** finds the user under `user-base-dn` by `uid`, `cn` or `mail` (the filter is
  `(|(uid={0})(cn={0})(mail={0}))`) and then binds with the password the user typed.
- **Groups** are found under each `groups[].groups-base-dn` as `groupOfUniqueNames` entries. Members
  come from `uniqueMember`, and the group's directory manager comes from `owner`.
- **Reporting lines.** A user reports to the manager whose `manager-ldap-id-attribute` value equals
  the user's `user-ldap-manager-id-attribute` value.
- **The org chart** is built from the distinct values of `user-ldap-duty-sub-organization-attribute`,
  optionally narrowed by `duty-sub-org-filter`.
- **Timeouts and paging.** The packaged configuration sets a 5 s connect timeout and a 30 s read
  timeout. Large searches are paged at `application.ldap.page-size` (500), which is below the
  default size limits of Active Directory (1000) and OpenLDAP (500).

## 4. Certificates

ReadyRoom terminates TLS itself. The packaged configuration enables HTTPS on port 8444 and
**requests, but does not require,** a client certificate (`server.ssl.client-auth: want`). A browser
or agent that presents a certificate is signed in with it. One that does not is sent to the login
form.

1. **Server keystore.** Create a PKCS#12 file holding the server's private key and certificate chain.
   The certificate's subject alternative name must match the host name that browsers and the
   agent's `BaseUrl` use. If you plan to turn on Windows sign-in, that is also the name registered
   as the service principal.
2. **Truststore.** Create a PKCS#12 truststore holding the CA certificate(s) that issue user
   certificates. Only certificates that chain to these CAs are accepted.
3. **User certificates.** Issue each user a client certificate whose subject DN equals their LDAP
   DN (or set up `lookup-on-dn-attribute`, described above). Install it with its private key in
   the user's `CurrentUser\My` store on the workstation.
4. **Point the server at the files**, kept outside the image and the jar:

    ```bash
    SERVER_SSL_KEYSTORE=file:/etc/readyroom/server.p12
    SERVER_SSL_KEYSTOREPASSWORD=...
    SERVER_SSL_TRUSTSTORE=file:/etc/readyroom/truststore.p12
    SERVER_SSL_TRUSTSTOREPASSWORD=...
    ```

!!! tip "Lab certificates"
    `test/` includes a `step-ca` certificate authority (`smallstep/step-ca:0.28.4`, port 8443) and
    sample certificates under `test/step-ca-data/certs/`, for development only.

## 5. Configure email

Absence alerts are sent through Spring Mail. Set `spring.mail.host`, `port`, `username`,
`password`, the `application.notification-sender-email` used as the From address, and
`application.application-base-url`, which becomes the link in each email. See
[Configuration](configuration.md#email-and-notifications).

## 6. Run the server

=== "Jar"

    ```bash
    export SPRING_CONFIG_ADDITIONAL_LOCATION=file:/etc/readyroom/application.yml
    java -jar in-n-out-work-1.0-SNAPSHOT.jar
    ```

=== "Container"

    ```bash
    docker run -d --name readyroom \
      -p 8444:8444 \
      -v /etc/readyroom:/app/config:ro \
      -e SPRING_CONFIG_ADDITIONAL_LOCATION=file:/app/config/application.yml \
      -e APPLICATION_TIMEZONE=America/New_York \
      in-n-out-work
    ```

    `SPRING_CONFIG_ADDITIONAL_LOCATION` takes precedence over the `application.yml` packaged in
    the jar. This is how `test/docker-compose.app.yml` supplies its configuration.

=== "Local test stack"

    For development only. It runs the backing services and seeds mock users and records:

    ```bash
    cd test && docker compose up -d      # PostgreSQL, OpenLDAP, phpLDAPadmin, Adminer, step-ca
    ./seed-mock-data.sh                   # mock LDAP users/groups and check-in history
    cd .. && ./gradlew bootRun            # https://localhost:8444
    ```

    | Service | Port |
    |---|---|
    | PostgreSQL | 5432 |
    | LDAP / LDAPS | 1389 / 1636 |
    | phpLDAPadmin | 8081 |
    | Adminer | 8082 |
    | step-ca | 8443 |

    Seeded users have the password `password` (for example `alice`). `test/application.yml` makes
    *Alice Adams* a super user. To run the app in a container too, use
    `docker compose -f docker-compose.app.yml up --build`. That file reuses the same container
    names, so do not run both compose files at once.

    !!! note
        At the current commit, `test/application.yml` sets `server.port: 8181` with SSL disabled,
        while `docker-compose.app.yml` publishes `8444:8444`. Make the two match before you use
        the containerized stack.

Set the time zone deliberately: `application.timezone` sets the JVM default time zone, which
decides what "today" means for every page and job. See [Configuration](configuration.md#time-zone).

### Optional: Windows (Kerberos) sign-in

If workstations should check in with the user's Windows logon instead of, or as well as, a
certificate, follow [Security: Windows sign-in](security.md#windows-kerberos-sign-in). It needs a
service account, an SPN and a keytab from a domain admin. Mount the keytab read-only and never bake
it into the image.

## 7. Optional: product page

`product-site/` is a static page that introduces ReadyRoom, served by `nginx-unprivileged` on
container port 8080. Each **Open the app** link goes to `/launch`, which redirects to `APP_URL`.

```bash
cd product-site && docker compose up -d --build
```

| Variable | Default | Purpose |
|---|---|---|
| `PRODUCT_SITE_PORT` | `80` | Host port for the page |
| `APP_URL` | `https://$host:8444/` | Where `/launch` redirects. `$host` is the host name the visitor used. |

The container runs read-only and has a health check on `/healthz`.

## 8. Deploy the Windows client

On each workstation, from an **elevated** PowerShell prompt in the repository's `powershell\`
folder:

```powershell
.\Install-InOutWorker.ps1 -BaseUrl "https://readyroom.example.com:8444/api/check"
# or sign in with the Windows logon, falling back to the certificate:
.\Install-InOutWorker.ps1 -BaseUrl "https://readyroom.example.com:8444/api/check" -Authentication Auto
```

The installer:

1. copies `Update-WorkStatus.ps1` to `C:\Program Files\InOutWorker` and writes
   `inoutworker.config.json` there (only if you passed settings);
2. creates `%ProgramData%\InOutWorker` and grants `BUILTIN\Users` Modify on it;
3. registers four hidden tasks under `\InOutWork\`: **Login**, **Lock**, **Unlock** and **Logout**.

For mass deployment, configuration files, parameters and what each task does, see
[Windows client](windows-client.md).

## 9. Verify the installation

- [ ] `curl -k https://readyroom.example.com:8444/actuator/health` returns `{"status":"UP"}`.
- [ ] The startup log shows `Default time zone set to ...` with the zone you expect, and
      `LDAP connection pooling enabled`.
- [ ] Browsing to `https://<host>:8444/` with a client certificate opens **Home**. Without one,
      the **Sign in** page accepts directory credentials.
- [ ] A super user (listed in `super-user-dns`) sees **Metrics** and **Settings** in the sidebar.
- [ ] **Groups** lists the trees under each configured `groups-base-dn`.
- [ ] On a workstation, `& 'C:\Program Files\InOutWorker\Update-WorkStatus.ps1' -Action login -WhatIf`
      reports the URL and the sign-in method it would use.
- [ ] `Get-ScheduledTask -TaskPath "\InOutWork\"` lists four tasks.
- [ ] After a real logon, `%ProgramData%\InOutWorker\logs\inoutworker-<yyyyMMdd>.log` contains
      `Server accepted 'login'`, and the user's status on **Home** is `IN`.
- [ ] After the first scheduled directory refresh (5 minutes after startup by default), the log
      shows `Directory refresh: N scanned, N created, ...` when it created or updated user records.
      If nothing changed, the line is logged only at `debug`.
