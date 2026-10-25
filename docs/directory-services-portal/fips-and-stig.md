# FIPS 140-3 and DISA STIG

This page says what the portal relies on for FIPS 140-3, how its license check works in FIPS mode,
and which settings a DISA STIG deployment needs. The suite-wide picture is in
[FIPS 140-3 and DISA STIG](../fips-and-stig.md).

!!! note "No ENS validation"
    ENS Solutions does not supply or claim a FIPS 140-3 validated cryptographic module. Whether a
    deployment meets FIPS 140-3 depends on the Java runtime, the operating system and their
    validated modules, which you choose and configure.

## FIPS 140-3

### What the portal relies on

The portal contains no cryptographic implementation of its own:

- the **API** uses the Java runtime's providers (JCA and JSSE);
- the **web tier** uses the operating system's OpenSSL, through nginx.

The container images are built on Red Hat UBI 9 (`ubi9/openjdk-21-runtime` for the API,
`ubi9/nginx-126` for the web tier). On a RHEL host with FIPS mode enabled (`fips=1`), Red Hat's OpenJDK
and OpenSSL in these images see the kernel's FIPS flag and switch to their FIPS modes. The native
package uses the host's Java 21: on a FIPS-enabled RHEL host, install RHEL's
`java-21-openjdk-headless`.

In those modes, these operations go through the runtime's FIPS providers:

| Operation | Where |
|---|---|
| TLS to the directory (LDAPS or StartTLS) | API |
| TLS to Splunk HEC and to syslog over TLS | API |
| HTTPS, and client-certificate verification | nginx, or the API with the `mtls` profile |
| Certificate path validation, OCSP response and CRL signature checks | API |
| The audit hash chain (SHA-256) | API |
| The license signature (Ed25519 or ECDSA P-256) | API; see [The license check in FIPS mode](#the-license-check-in-fips-mode) |

OCSP requests identify a certificate by a SHA-1 hash of its issuer's name and key, as RFC 6960
responders expect. That is an identifier, not a signature. Bouncy Castle is used by the tests only;
it is not in the shipped API.

!!! warning "Not tested in FIPS mode"
    None of the portal's CI jobs runs it on a Java runtime or OpenSSL in FIPS mode. Its copy of the
    license library's tests simulates a runtime without Ed25519; for the library's checks on real
    FIPS-mode runtimes, see [Licensing → FIPS 140-3](../licensing.md#fips-140-3).

### Options that are not FIPS-appropriate

Use these in test setups only. The API logs a warning at startup while one is on.

| Setting | What it does | Instead |
|---|---|---|
| `LDAP_TLS_REJECT_UNAUTHORIZED=false` | Trusts any directory server certificate | Give the API a trust store with the directory's CA ([Configuration](configuration.md#ldaps-with-a-private-ca)) |
| `ENS_AUDIT_SPLUNK_INSECURE=true` | Trusts any Splunk HEC certificate | Add Splunk's CA to the JVM trust store |

### The license check in FIPS mode

The API carries ENS license library 1.2.0 and trusts two ENS production keys: Ed25519 (key id
`dabdc531a28916c5`) and ECDSA P-256 (key id `e0a2e4ff9c4f5bfc`). A license file may carry either
signature or both. The API accepts it if any signature by a trusted key verifies with an algorithm
the Java runtime provides, trying Ed25519 first. A Java runtime in FIPS mode has no Ed25519, so
there the P-256 signature is the one that counts.

- **A license file signed with both keys** works on every host, FIPS or not.
- **A license file issued before ENS enabled P-256 signing** carries only the Ed25519 signature. It
  keeps working on hosts without FIPS mode. On a FIPS host the API refuses it
  (`UNSUPPORTED_ALGORITHM`: "signed only with Ed25519, which this Java runtime does not provide")
  and the portal stays **unlicensed**: only sign-in, the license screen and the audit log work.
  Before moving to a FIPS host, download the license again from your account on the ENS portal (a
  re-signed copy of the same license, with the P-256 signature) and install it as usual
  (`./portal.sh license <file>`, the license screen, or the license volume or PVC).

Don't work around a refused license with `-Dcom.redhat.fips=false`: that takes the whole JVM out
of FIPS mode. See [Licensing → FIPS 140-3](../licensing.md#fips-140-3) for which runtimes provide
which algorithm.

## DISA STIG settings

The portal implements the ENS platform baseline. Most of it is on by default; these settings need
a decision for a STIG deployment. Each is described in [Configuration](configuration.md).

| Control | Setting | Default | STIG deployment |
|---|---|---|---|
| Notice and consent banner before sign-in | `ENS_CONSENT_BANNER_ENABLED` | `false` | `true`. The standard DoD text is the default; replace it with `ENS_CONSENT_BANNER_FILE` if your site has its own |
| Idle timeout | `ENS_SESSION_IDLE_TIMEOUT` | `30m` | Your policy's value |
| Administrator idle timeout | `ENS_SESSION_ADMIN_IDLE_TIMEOUT` | the idle timeout | `15m` is the suggested value |
| Absolute session lifetime | `ENS_SESSION_ABSOLUTE_TIMEOUT` | `12h` | Your policy's value |
| Concurrent sessions per user | `ENS_SESSION_MAX_PER_USER` | `0` (no limit) | `3` is the suggested value |
| Failed sign-ins before lockout | `LOGIN_MAX_FAILURES`, `LOGIN_LOCKOUT_WINDOW` | `8`, `15m` | `3` in `15m` is the suggested value. The lockout is per user name and client address, held in memory; the directory's own lockout policy still applies to binds |
| Certificate revocation checking | `ENS_X509_REVOCATION` | `off` | `ocsp`, `crl` or `ocsp-then-crl`, with `ENS_X509_REVOCATION_FAIL=closed`. The API warns at startup while it is off with certificate sign-in enabled |
| Accepted certificate issuers | `ENS_X509_ALLOWED_ISSUERS` | any trusted CA | Your DoD issuing CAs, if the trust bundle holds others |
| Certificate-only sign-in | `PASSWORD_LOGIN_ENABLED` | `true` | `false` where only CAC/PIV sign-in is allowed |
| Secure session cookie | `ENS_COOKIE_SECURE` | `false` | `true` whenever TLS ends at a proxy |
| Security headers | always on | | CSP, HSTS on HTTPS, `nosniff`, `DENY` framing. `ENS_CSP_REPORT_ONLY` must stay `false` |
| Audit trail | `ENS_AUDIT_*` | stored locally, stdout | Forward to your SIEM, and set `ENS_AUDIT_RETENTION_DAYS` to your retention policy ([Audit logging](../audit-logging.md)) |
| Directory TLS | `LDAP_URL`, `LDAP_STARTTLS`, `LDAP_TLS_REJECT_UNAUTHORIZED` | `ldaps://` in the packaged templates | `ldaps://` or StartTLS, with certificate checking on |
| Service account password | `LDAP_BIND_PASSWORD_FILE` | the Helm chart and native package use it | Keep the password out of the environment where you can |

Already in place without settings: every sign-in, sign-out, lockout, session expiry, refused
request and change is audited with the user and client address; the containers run as non-root
with read-only root file systems and no capabilities; the native package's systemd unit is
sandboxed (see [Security](security.md#network-exposure)); images and the chart are signed (see
[Security → Supply chain](security.md#supply-chain)).
