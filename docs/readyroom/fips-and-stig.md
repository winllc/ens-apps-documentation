# FIPS 140-3 and DISA STIG

This page says what ReadyRoom relies on for FIPS 140-3, what has not been tested in FIPS mode, and
which settings a DISA STIG deployment needs. The suite-wide picture is in
[FIPS 140-3 and DISA STIG](../fips-and-stig.md).

!!! note "No ENS validation"
    ENS Solutions does not supply or claim a FIPS 140-3 validated cryptographic module. Whether a
    deployment meets FIPS 140-3 depends on the Java runtime, the operating system and their
    validated modules, which you choose and configure.

## FIPS 140-3

### What ReadyRoom relies on

ReadyRoom implements no cryptography of its own. TLS, certificate and revocation checking, hashing
and random numbers all come from the Java runtime's security providers, through the standard
JCA/JSSE interfaces. BouncyCastle is used by the tests only and is not in the image.

The image is built on Red Hat UBI 9 (`ubi9/openjdk-21-runtime`) with Red Hat's OpenJDK 21. On a RHEL
host in FIPS mode (`fips-mode-setup --enable`, or an OpenShift cluster installed with FIPS), Red
Hat's OpenJDK in the container sees the kernel's FIPS flag and switches to its FIPS configuration:
the NSS FIPS module through `SunPKCS11-NSS-FIPS`, and the system crypto policy.

| Connection or operation | Notes |
|---|---|
| HTTPS from browsers and the Windows client | Only when ReadyRoom terminates TLS itself (`SSL_ENABLED=true`); otherwise your proxy or ingress does. ReadyRoom sets no protocol or cipher list: the JDK's defaults and the crypto policy apply |
| LDAPS to the directory | `ldaps://` is the default in the release package's `.env` and the Helm chart (`ldap.scheme`) |
| SMTP with STARTTLS | Required by default |
| TLS to PostgreSQL | The packaged URL sets no `sslmode`, so the PostgreSQL JDBC driver's default applies. To require a verified connection, give a whole `SPRING_DATASOURCE_URL` with the driver's `sslmode=verify-full` and `sslrootcert` parameters |
| TLS to Splunk HEC and syslog | Optional |
| OCSP and CRL fetches, and the certificate path check | The JDK's PKIX validator. SHA-1 appears only in the OCSP request's CertID (RFC 6960), which names a certificate and is not a signature |
| SHA-256 | The audit hash chain, the pull-API token hashes and the session id fingerprints |
| `SecureRandom` | Session ids, CSRF tokens, CSP nonces and tokens |
| Ed25519 or ECDSA P-256 | The license signature: see [The license check in FIPS mode](#the-license-check-in-fips-mode) |

!!! warning "Not tested in FIPS mode"
    - **ReadyRoom itself.** None of its CI jobs runs it on a Java runtime in FIPS mode. Its copy
      of the license library's tests simulates a runtime without Ed25519; for the library's checks
      on real FIPS-mode runtimes, see [Licensing → FIPS 140-3](../licensing.md#fips-140-3).
    - **Windows (Kerberos) sign-in** uses the JDK's Kerberos with the keytab's encryption types; it
      has not been run on a Java runtime in FIPS mode.
    - **The Windows client** runs in Windows PowerShell and uses Windows' own TLS. It asks for TLS
      1.2 or 1.1 (`Update-WorkStatus.ps1`); OpenJDK 21 refuses TLS 1.1 by default, so a connection
      to ReadyRoom's own TLS port uses TLS 1.2; a proxy in front decides for itself. FIPS on the workstations is Windows' own policy, and the client has
      not been tested with it.
    - **PostgreSQL.** The release package and the Helm chart's embedded database use the official
      `postgres:16` image, which is not built on UBI. For a FIPS deployment, use a PostgreSQL server
      you have configured for FIPS (with Helm, an external server).

### Options that are not FIPS-appropriate

These weaken what FIPS mode protects and should stay off in a FIPS deployment.

| Setting | Why | Instead |
|---|---|---|
| `ENS_AUDIT_SPLUNK_INSECURE=true` | Skips the Splunk HEC certificate check | Add Splunk's CA to the JVM trust store |
| `SPRING_MAIL_PROPERTIES_MAIL_SMTP_SSL_TRUST` (a host name, or `*`) | Trusts the mail server's certificate without checking it | Give the JVM your mail server's CA |
| `SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE=false` | Mail, and its credentials, in clear text | Keep STARTTLS required |
| `ldap://` directory URLs | Passwords and directory data in clear text (warned at startup) | `ldaps://` |
| `SSL_ENABLED=false` with no TLS-terminating proxy in front | Everything in clear text | TLS at ReadyRoom or at the proxy |
| `-SkipCertificateCheck` on the Windows client | Trusts any server certificate | Trust ReadyRoom's CA on the workstations |
| Keystores and trust stores (`SERVER_SSL_KEYSTORE`, `SERVER_SSL_TRUSTSTORE`) in a format or with algorithms the FIPS provider does not accept | They fail to load in FIPS mode | PKCS#12 files made with FIPS-approved algorithms (AES, SHA-256), or as your provider requires |

### The license check in FIPS mode

ReadyRoom carries ENS license library 1.2.0, which checks ECDSA P-256 signatures as well as
Ed25519, and trusts two ENS production keys: Ed25519 (key id `dabdc531a28916c5`) and ECDSA P-256
(key id `e0a2e4ff9c4f5bfc`). A license file may carry both signatures. ReadyRoom accepts it if any
signature by a trusted key verifies with an algorithm the Java runtime provides, trying Ed25519
first. A Java runtime in FIPS mode has no Ed25519, so there only the P-256 signature counts.

!!! warning "Licenses issued before ENS enabled P-256 signing"
    Such a license is Ed25519-only. It keeps working on a runtime that is not in FIPS mode. On a
    FIPS host it is refused, the license screen says the file is signed only with an algorithm this
    runtime does not provide, and ReadyRoom stays **unlicensed**: only sign-in, the license screen,
    the audit log and a person's data export and erasure work.

    Before moving to a FIPS host, get a re-signed copy of the same license that carries a P-256
    signature: download it again from the ENS licensing portal, or ask ENS for it. Then upload it
    on the license screen.

    Don't work around a refused license with `-Dcom.redhat.fips=false`: that takes the whole JVM
    out of FIPS mode.

See [Licensing → FIPS 140-3](../licensing.md#fips-140-3) for which runtimes provide which
algorithm.

## DISA STIG settings

ReadyRoom implements the ENS platform baseline's headers, banner and session controls. Most of it is
on by default; these settings need a decision for a STIG deployment. Each is described in
[Configuration](configuration.md#security-headers-banner-and-sessions) or, for certificates, in
[Installation](install.md#client-certificates-cacpiv).

| Control | Setting | Default | STIG deployment |
|---|---|---|---|
| Notice and consent banner | `ENS_CONSENT_BANNER_ENABLED` | `false` | `true`. The standard DoD text is the default; use `ENS_CONSENT_BANNER_FILE` for your site's own |
| Idle timeout | `ENS_SESSION_IDLE_TIMEOUT` | `30m` | Your policy's value |
| Administrator idle timeout | `ENS_SESSION_ADMIN_IDLE_TIMEOUT` | the idle timeout | `15m` is the suggested value |
| Absolute session lifetime | `ENS_SESSION_ABSOLUTE_TIMEOUT` | `12h` | Your policy's value |
| Concurrent sessions per user | `ENS_SESSION_MAX_PER_USER` | `0` (no limit) | `3` is the suggested value |
| Certificate revocation | `ENS_X509_REVOCATION` | `off` | `ocsp`, `crl` or `ocsp-then-crl` whenever certificates sign in; `ENS_X509_CRL_DIR` on an air-gapped network |
| Unknown revocation status | `ENS_X509_REVOCATION_FAIL` | `closed` | Keep `closed` |
| Issuers allowed to sign in | `ENS_X509_ALLOWED_ISSUERS` | any trusted CA | The CAs that issue your users' certificates, if the trust store holds others |
| Failed sign-ins | The directory's password policy | | ReadyRoom adds no lockout of its own: a password sign-in is a bind as the user, so the directory's lockout applies |
| Secure session cookie | `ENS_COOKIE_SECURE` | the value of `SSL_ENABLED` | `true` whenever TLS ends at a proxy |
| Security headers and CSRF | always on | | `ENS_CSP_REPORT_ONLY` must stay `false` |
| Directory connection | `SPRING_LDAP_URLS` (Helm: `ldap.scheme`) | `ldaps://` in the release package and the chart | Keep `ldaps://`, with a read-only bind account |
| Mail | `SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE` | `true`, and required | Keep it; leave `SPRING_MAIL_PROPERTIES_MAIL_SMTP_SSL_TRUST` unset |
| Audit trail | `ENS_AUDIT_*` | stored in the database, stdout | Forward to your SIEM, and set `ENS_AUDIT_RETENTION_DAYS` to your retention policy ([Audit logging](../audit-logging.md)) |
| Classification banner | `application.banner-text`, `application.banner-color` ([Configuration](configuration.md#user-interface)) | required | Your system's marking |

Already in place without settings: every sign-in, refusal, session end and change is audited with
the user and client address; the management port, when set, serves Prometheus apart from the
application port; the Helm chart runs ReadyRoom as non-root (uid 185) with a read-only root file
system, no capabilities and the `RuntimeDefault` seccomp profile.
