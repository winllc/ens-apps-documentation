# FIPS 140-3 and DISA STIG

This page says what CertAlert relies on for FIPS 140-3, what has not been tested in FIPS mode, and
which settings a DISA STIG deployment needs. The suite-wide picture is in
[FIPS 140-3 and DISA STIG](../fips-and-stig.md).

!!! note "No ENS validation"
    ENS Solutions does not supply or claim a FIPS 140-3 validated cryptographic module. Whether a
    deployment meets FIPS 140-3 depends on the Java runtime, the operating system and their
    validated modules, which you choose and configure.

## FIPS 140-3

### What CertAlert relies on

CertAlert implements no cryptography of its own. Every hash, signature check, key exchange and
cipher goes through the Java runtime's security providers (JCA and JSSE). BouncyCastle is used by
the tests only and is not in the image.

The image is built on Red Hat UBI 9 (`ubi9/openjdk-21-runtime`) with Red Hat's OpenJDK 21. On a
RHEL host in FIPS mode (`fips-mode-setup --enable`, or an OpenShift cluster installed with FIPS),
Red Hat's OpenJDK in the container sees the kernel's FIPS flag and switches to its FIPS
configuration. CertAlert has no setting of its own for this.

| Connection or operation | Notes |
|---|---|
| HTTPS from browsers | Only when CertAlert terminates TLS itself (`CERT_ALERT_TLS_ENABLED=true`): TLS 1.2 and 1.3 only. Otherwise your proxy or ingress does |
| LDAPS to the directory | `ldaps://` in the release package's `env.template`; the Helm chart has no default URL |
| SMTP | STARTTLS when you set `SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE=true` (Helm: `mail.starttls`); CertAlert sets no default |
| TLS to PostgreSQL | With `sslmode=verify-full` in the JDBC URL (Helm: `database.params`). The release package's own PostgreSQL is reached unencrypted on the bundle's internal network |
| TLS to Splunk HEC and syslog | Optional |
| CRL and OCSP fetches, and their signature checks | The nightly revocation check and, when on, the check at sign-in. OCSP answers are checked by the JDK's path validator. SHA-1 appears only in the OCSP request's CertID (RFC 6960), which names a certificate and is not a signature |
| The endpoint probe | Opens a TLS connection to a server and reports the certificate it presents. It trusts every chain on purpose, because it relies on none of them |
| SHA-256 | Certificate fingerprints (and with them certificate sign-in), the audit hash chain, and the stored hashes of audit API tokens |
| Ed25519 | The license signature: see [The license check in FIPS mode](#the-license-check-in-fips-mode) |

The jar also carries the UnboundID LDAP SDK, which only the read-only demo mode
(`cert-alert.demo.enabled`, off by default) uses, for its generated directory and certificates.
Leave demo mode off in production.

!!! warning "Not tested in FIPS mode"
    - **CertAlert itself** has no CI job that runs it on a Java runtime in FIPS mode. The license
      library's own FIPS test is the only FIPS-mode test.
    - **PostgreSQL.** The release package and the Helm chart's evaluation database run
      `postgres:17.11-alpine`, which is not built on UBI. For a FIPS deployment, use a PostgreSQL
      server you have configured for FIPS (with Helm, an external server), reached with
      `sslmode=verify-full`.

### Options that are not FIPS-appropriate

| Setting | Default | Why | Instead |
|---|---|---|---|
| `cert-alert.revocation.allow-unverified-crl` | **`true`** | Believes a CRL whose signature could not be checked | Set `false`, and give `issuer-directory` the issuing CAs' certificates |
| `ENS_AUDIT_SPLUNK_INSECURE=true` | `false` | Skips the Splunk HEC certificate check (warned at startup) | Add Splunk's CA to the JVM trust store |
| `ENS_AUDIT_SYSLOG_PROTOCOL` `udp` or `tcp` | `tcp` | Audit events in plain text | `tls` |
| `ldap://` directory URLs | | Passwords and directory data in plain text | `ldaps://` |
| SMTP without STARTTLS | | Mail, and its credentials, in plain text | `SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE=true` |
| A PostgreSQL URL without `sslmode=verify-full` | | Unencrypted, or encrypted without checking the server | `sslmode=verify-full` |
| `ENS_X509_REVOCATION=off`, `ENS_X509_REVOCATION_FAIL=open` | `off`, `closed` | Revoked or unchecked certificates sign in (STIG) | `ocsp`, `crl` or `ocsp-then-crl`, with `closed` |
| `ENS_X509_SOURCE=header`, or a Helm ingress or edge or reencrypt Route that terminates TLS | | The browser's TLS ends at the proxy, so it is the proxy's cryptographic module that has to be validated | Terminate TLS in CertAlert, or validate the proxy |
| Key stores and trust stores in a format or with algorithms the FIPS provider does not accept | | They fail to load in FIPS mode | PKCS#12 files made with approved algorithms (AES, SHA-256), or as your provider requires |

`cert-alert.revocation.allow-unverified-crl` is the one item in this table that is on by
default: the repository's README lists it under "not appropriate on a FIPS system, and off by
default", but its default in the code is `true`.

### The license check in FIPS mode

!!! warning "Current state: the license can't be verified on a Java runtime in FIPS mode"
    CertAlert checks its license file with the ENS license library 1.0.0, which verifies an
    Ed25519 signature. Red Hat's OpenJDK in FIPS mode does not offer Ed25519 signatures, so every
    license is refused ("The signature could not be checked: Ed25519 Signature not available")
    and CertAlert stays **unlicensed**: only sign-in, the license screen and the audit log work.

    Don't work around it with `-Dcom.redhat.fips=false`: that takes the whole JVM out of FIPS mode.

It changes when ENS Solutions both issues license files that also carry an ECDSA P-256 signature
(which needs ENS's production P-256 key, not yet created) and ships a CertAlert release with
license library 1.1.0. See [Licensing → FIPS 140-3](../licensing.md#fips-140-3).

## DISA STIG settings

CertAlert implements the ENS platform baseline's headers, banner, session and certificate
controls. Most of it is on by default; these settings need a decision for a STIG deployment. Each
is described in [Configuration](configuration.md#security-headers-banner-and-sessions) or, for
certificates, in [Configuration → Client certificates](configuration.md#client-certificates-ensx509).

| Control | Setting | Default | STIG deployment |
|---|---|---|---|
| Notice and consent banner | `ENS_CONSENT_BANNER_ENABLED` | `false` | `true`. The standard DoD text is the default; use `ENS_CONSENT_BANNER_FILE` for your site's own |
| Idle timeout | `ENS_SESSION_IDLE_TIMEOUT` | `30m` | Your policy's value |
| Administrator idle timeout | `ENS_SESSION_ADMIN_IDLE_TIMEOUT` | the idle timeout | `15m` is the suggested value |
| Absolute session lifetime | `ENS_SESSION_ABSOLUTE_TIMEOUT` | `12h` | Your policy's value |
| Concurrent sessions per user | `ENS_SESSION_MAX_PER_USER` | `0` (no limit) | `3` is the suggested value |
| Certificate revocation at sign-in | `ENS_X509_REVOCATION` | `off` | `ocsp`, `crl` or `ocsp-then-crl` whenever certificates sign in; `ENS_X509_CRL_DIR` on an air-gapped network |
| Unknown revocation status | `ENS_X509_REVOCATION_FAIL` | `closed` | Keep `closed` |
| Issuers allowed to sign in | `ENS_X509_ALLOWED_ISSUERS` | any trusted CA | Your issuing CAs, if the trust store holds others |
| Certificate-only sign-in | `cert-alert.security.ldap.enabled` | `true` | `false` where only CAC/PIV sign-in is allowed |
| Failed password sign-ins | The directory's password policy | | CertAlert adds no lockout of its own: a password sign-in is a bind as the user, so the directory's lockout applies. Repeated failures for one name are audited at `alert` severity |
| Secure session cookie | `ENS_COOKIE_SECURE` | `false` | `true` whenever TLS ends at a proxy that does not send `X-Forwarded-Proto` |
| Security headers and CSRF | always on | | `ENS_CSP_REPORT_ONLY` must stay `false` |
| Audit trail | `ENS_AUDIT_*` | stored in the database, stdout | Forward to your SIEM, and set `ENS_AUDIT_RETENTION_DAYS` to your retention policy ([Audit logging](../audit-logging.md)) |
| Revocation fetches | `CERT_ALERT_REVOCATION_ALLOWED_HOSTS` | anywhere a certificate names, except link-local and metadata addresses | Your PKI's hosts |
| Classification banner | `CERT_ALERT_BANNER_ENABLED`, `CERT_ALERT_BANNER_TEXT` | off | Your system's marking |

Already in place without settings: every sign-in, refusal, session end and change is audited with
the user and client address; certificate sign-in binds to the exact certificate the directory
publishes; the management port, when set, serves Prometheus apart from the application port; the
Helm chart runs CertAlert as non-root (uid 185) with a read-only root file system, no
capabilities and the `RuntimeDefault` seccomp profile.
