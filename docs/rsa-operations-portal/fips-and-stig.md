# FIPS 140-3 and DISA STIG

This page says what the RSA Operations Portal relies on for FIPS 140-3, what has not been tested in
FIPS mode, and which settings a DISA STIG deployment needs. The suite-wide picture is in
[FIPS 140-3 and DISA STIG](../fips-and-stig.md).

!!! note "No ENS validation"
    ENS Solutions does not supply or claim a FIPS 140-3 validated cryptographic module. Whether a
    deployment meets FIPS 140-3 depends on the Java runtime, the operating system and their
    validated modules, which you choose and configure.

## FIPS 140-3

### What the Portal and the Connector rely on

Both images are built on Red Hat UBI 9 (`ubi9/ubi`) with Red Hat's OpenJDK 17
(`java-17-openjdk-headless`) from the UBI repositories. Neither contains a cryptographic
implementation of its own: TLS and signatures come from the Java runtime's providers. On a RHEL
host with FIPS mode enabled, Red Hat's OpenJDK in the containers sees the kernel's FIPS flag and
switches to its FIPS providers.

| Connection or operation | Component | Notes |
|---|---|---|
| HTTPS from browsers (`server.ssl.*`, Helm `tls.*`) | Portal | Only when the Portal terminates TLS itself; otherwise your ingress or proxy does |
| HTTPS to the Connector | Portal and Connector | TLS 1.2 or 1.3. The Portal's image pins its own outgoing TLS to 1.2 (`JAVA_OPTS`) |
| TLS to Authentication Manager | Connector, through the RSA AM SDK | See the warning below |
| TLS to MySQL | Portal | `sslMode=REQUIRED` in the template and the chart |
| LDAPS to Active Directory | Portal | Optional |
| TLS to Splunk HEC and syslog | Portal | Optional |
| The audit hash chain (SHA-256) | Portal | |
| The Connector's PKCS12 key store, built at each start | Connector, with `openssl` | From the PEM certificate and key; see the warning below |

!!! warning "Not tested in FIPS mode"
    - **The RSA AM SDK** (`am-client` 8.8 and the WebLogic client it uses for `t3s`) has not been
      run on a Java runtime in FIPS mode. Whether it works there is RSA's to state for your AM
      release.
    - **The Connector's start script** uses `openssl` to make the PKCS12 key store Tomcat reads, and a
      throwaway self-signed certificate when none is given. Neither has been run on a FIPS-enabled
      host.
    - **MySQL.** The release package runs the official `mysql` image, which is not built on UBI.
      For a FIPS deployment, use a MySQL server you have configured for FIPS (with Helm, an external
      server).
    - **SMTP.** The Portal sends mail to `spring.mail.host` on port 25 by default; no TLS for SMTP is
      configured in the templates.

### Options that are not FIPS-appropriate

| Setting | What it does | Instead |
|---|---|---|
| `ENS_AUDIT_SPLUNK_INSECURE=true` | Trusts any Splunk HEC certificate (logged as a warning) | Add Splunk's CA to the JVM trust store |
| `CONNECTOR_HTTP_ADDRESS=0.0.0.0` with an `http://` `CONNECTOR_URL` | Plain HTTP between the Portal and the Connector (the demo stack only; the Portal logs a warning) | HTTPS, the default |
| A `spring.datasource.url` without `sslMode=REQUIRED` | The database connection may be unencrypted | Keep `sslMode=REQUIRED` |

### The license check in FIPS mode

!!! warning "Current state: the license can't be verified on a Java runtime in FIPS mode"
    The Portal checks its license file with the ENS license library 1.0.0, which verifies an
    Ed25519 signature. Red Hat's OpenJDK in FIPS mode does not offer Ed25519 signatures, so every
    license is refused and the Portal stays **unlicensed**: only sign-in, the license screen and
    the audit log work.

    Don't work around it with `-Dcom.redhat.fips=false`: that takes the whole JVM out of FIPS mode.

It changes when ENS Solutions both issues license files that also carry an ECDSA P-256 signature
(which needs ENS's production P-256 key, not yet created) and ships a Portal release with license
library 1.1.0. See [Licensing → FIPS 140-3](../licensing.md#fips-140-3).

## DISA STIG settings

The Portal implements the ENS platform baseline's headers, banner and session controls. Most of
it is on by default; these settings need a decision for a STIG deployment. Each is described in
[Configuration](configuration.md#security-headers-banner-and-sessions).

| Control | Setting | Default | STIG deployment |
|---|---|---|---|
| Notice and consent banner | `ENS_CONSENT_BANNER_ENABLED` | `false` | `true`. The standard DoD text is the default; use `ENS_CONSENT_BANNER_FILE` for your site's own |
| Idle timeout | `ENS_SESSION_IDLE_TIMEOUT` | `30m` | Your policy's value |
| Administrator idle timeout | `ENS_SESSION_ADMIN_IDLE_TIMEOUT` | the idle timeout | `15m` is the suggested value |
| Absolute session lifetime | `ENS_SESSION_ABSOLUTE_TIMEOUT` | `12h` | Your policy's value |
| Concurrent sessions per user | `ENS_SESSION_MAX_PER_USER` | `0` (no limit) | `3` is the suggested value |
| Failed sign-ins | Authentication Manager's lockout policy | | The Portal adds no lockout of its own for sign-in; AM enforces it on every passcode |
| Secure session cookie | `ENS_COOKIE_SECURE` | `false` | `true` whenever TLS ends at a proxy |
| Security headers, CSRF, method security | always on | | `ENS_CSP_REPORT_ONLY` must stay `false` |
| Audit trail | `ENS_AUDIT_*` | stored in the database, stdout | Forward to your SIEM, and set `ENS_AUDIT_RETENTION_DAYS` to your retention policy ([Audit logging](../audit-logging.md)) |
| Least privilege in AM | `RSA_ADMIN_USER_ID`, `RSA_READONLY_USER_ID` | | The [custom roles](rsa-integration.md#least-privilege-am-roles), not Super Admin |
| Secrets off the command line | `RSA_*` variables or `_FILE` | | No AM password in `JAVA_OPTS` |
| Classification banner | `security-banner.*` | `UNCLASSIFIED // FOR OFFICIAL USE ONLY` | Your system's marking |

The platform baseline's X.509 revocation and certificate-mapping settings do not apply to this
product: users sign in with SecurID, and the client-certificate features (PIN reset with a
certificate, exemption requests) rely on the certificate check of the TLS server that terminates
the connection.

Already in place without settings: every sign-in, denial, session end and change is audited with
the user and client address; operators' AM changes run in their own AM sessions, so AM's audit log
names them; the Helm chart runs every container as non-root with a read-only root file system and
no capabilities.
