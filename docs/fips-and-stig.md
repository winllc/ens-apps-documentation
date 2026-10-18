# FIPS 140-3 and DISA STIG

This page gives the suite-wide picture: where the applications get their cryptography, what
changes in FIPS mode, the state of the license check, and which DISA STIG controls each
application implements. Each product's own page has its settings in full:

- [CertAlert](cert-alert/fips-and-stig.md)
- [Directory Services Portal](directory-services-portal/fips-and-stig.md)
- [ReadyRoom](readyroom/fips-and-stig.md)
- [RSA Operations Portal](rsa-operations-portal/fips-and-stig.md)

!!! note "No ENS validation"
    ENS Solutions does not supply or claim a FIPS 140-3 validated cryptographic module, and none of
    the applications has been validated or certified. Whether a deployment meets FIPS 140-3
    depends on the Java runtime, the operating system and their validated modules, which you choose
    and configure.

## FIPS 140-3

### Where the cryptography comes from

No application's own code implements cryptography. TLS, certificate and revocation checking,
hashing, random numbers and the license signature check go through the Java runtime's providers
(JCA and JSSE). The Directory Services Portal's web tier uses the operating system's OpenSSL, through
nginx. CertAlert, the Directory Services Portal and ReadyRoom use BouncyCastle in their tests only;
it is not in their images. The RSA Connector also carries RSA's Authentication Manager SDK
(`am-client`) and the WebLogic client (`wlfullclient`), which RSA supplies; what cryptography they
use is RSA's to state.

| Product | Image base | Java |
|---|---|---|
| CertAlert | Red Hat UBI 9, `ubi9/openjdk-21-runtime` | Red Hat OpenJDK 21 |
| Directory Services Portal | `ubi9/openjdk-21-runtime` (API), `ubi9/nginx-126` (web tier). The native package uses the host's Java 21 | Red Hat OpenJDK 21 |
| ReadyRoom | `ubi9/openjdk-21-runtime` | Red Hat OpenJDK 21 |
| RSA Operations Portal | `ubi9/ubi` with `java-17-openjdk-headless`, for the Portal and the Connector | Red Hat OpenJDK 17 |

**Turning FIPS mode on.** Run the images on a RHEL host with FIPS mode enabled
(`fips-mode-setup --enable`), or on an OpenShift cluster installed in FIPS mode. At start-up, Red
Hat's OpenJDK asks NSS whether the system is in FIPS mode, which follows the kernel's FIPS flag, and
if so replaces its providers with its FIPS configuration (`SunPKCS11-NSS-FIPS` over the NSS FIPS
module, with the system crypto policy). The applications need no setting of their own for this.
Running `update-crypto-policies --set FIPS` inside a container does not switch it on; the host's
FIPS mode does.

**What the applications use:**

- SHA-256 for the audit hash chain and the stored hashes of audit API tokens, and (CertAlert) the
  certificate fingerprints it shows;
- SHA-1 only to name a certificate in an OCSP request (the CertID of RFC 6960), which is an
  identifier, not a signature;
- the runtime's `SecureRandom` for session ids, CSRF tokens, CSP nonces and API tokens;
- Ed25519 to check the license signature: see [The license check](#the-license-check).

**Key stores and trust stores** must be in a form the FIPS provider can open: in RHEL's FIPS mode,
PKCS#12 files made with approved algorithms (AES, SHA-256), or as your provider requires.

**Databases.** The release packages, and the Helm charts' embedded databases, run images that are
not built on UBI: `postgres:17.11-alpine` (CertAlert), `postgres:16` (ReadyRoom) and `mysql:8.4.11`
(RSA Operations Portal). For a FIPS deployment, use a database server you have configured for FIPS
(with Helm, an external server). The Directory Services Portal has no database.

### TLS settings

No application sets a cipher list: the JDK's (or, for nginx, OpenSSL's) defaults and the system
crypto policy apply. Where an application sets TLS protocols, it allows TLS 1.2 and 1.3:

- CertAlert's own HTTPS (`server.ssl.enabled-protocols: TLSv1.3,TLSv1.2`);
- the Directory Services Portal's nginx in its client-certificate (`mtls`) configuration;
- the RSA Connector's HTTPS (its `server.xml`) and its TLS to Authentication Manager
  (`-Dhttps.protocols=TLSv1.3,TLSv1.2`).

The exception is the RSA Operations Portal, whose image and chart pin the Portal's outgoing TLS to
1.2 in `JAVA_OPTS` (`-Dhttps.protocols=TLSv1.2 -Djdk.tls.client.protocols=TLSv1.2`). ReadyRoom sets no
protocols, so the JDK's defaults apply.

| Connection | Default |
|---|---|
| Browsers | TLS at the application (its key store) or at your proxy or ingress |
| Directory | `ldaps://` in the CertAlert, Directory Services Portal and ReadyRoom templates (CertAlert's chart has no default URL). The RSA Operations Portal's optional Active Directory connection takes the URL you give |
| Mail | ReadyRoom requires STARTTLS by default. CertAlert uses STARTTLS when you set `SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE=true`. The RSA Operations Portal's templates configure no TLS for SMTP |
| Database | RSA Operations Portal: `sslMode=REQUIRED`. CertAlert and ReadyRoom: the PostgreSQL JDBC driver's default unless you add `sslmode` to the URL (CertAlert's chart: `database.params`, for example `sslmode=verify-full`) |
| Splunk HEC and syslog | The certificate is checked against the JVM trust store. Syslog defaults to `tcp`, which is plain text; use `tls` |
| Portal to Connector (RSA) | HTTPS on 8443, with optional mutual TLS |

### Options that are not FIPS-appropriate

Use these in test setups only.

| Setting | Products | What it does |
|---|---|---|
| `ENS_AUDIT_SPLUNK_INSECURE=true` | All | Skips the Splunk HEC certificate check |
| `ENS_AUDIT_SYSLOG_PROTOCOL` `udp` or `tcp` (`tcp` is the default) | All | Audit events in plain text. Use `tls` |
| `ldap://` directory URLs, without StartTLS | All | Passwords and directory data in plain text |
| `LDAP_TLS_REJECT_UNAUTHORIZED=false` | Directory Services Portal | Trusts any directory server certificate |
| `cert-alert.revocation.allow-unverified-crl` | CertAlert (**`true` by default**) | Believes a CRL whose signature could not be checked. Set `false` and configure `issuer-directory` |
| `SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE=false`, or `SPRING_MAIL_PROPERTIES_MAIL_SMTP_SSL_TRUST` | ReadyRoom | Mail in plain text, or a mail server certificate that isn't checked |
| `CONNECTOR_HTTP_ADDRESS=0.0.0.0` with an `http://` `CONNECTOR_URL` | RSA Operations Portal | Plain HTTP between the Portal and the Connector (demo stack only) |
| `ENS_X509_REVOCATION=off`, `ENS_X509_REVOCATION_FAIL=open` | CertAlert, Directory Services Portal, ReadyRoom | Revoked or unchecked certificates sign in |
| A PostgreSQL URL without `sslmode=verify-full` | CertAlert, ReadyRoom | Unencrypted, or encrypted without checking the server |
| `ENS_X509_SOURCE=header` (CertAlert, ReadyRoom), or TLS ended at an ingress or edge Route in front of any product | See the setting | The browser's TLS ends at the proxy, so the proxy's cryptographic module is the one that has to be validated |

CertAlert's server-endpoint probe trusts every certificate chain on purpose: it reads and reports
the certificates a server presents, and relies on none of them.

### Not tested in FIPS mode

No application has a CI job that runs it on a Java runtime in FIPS mode. The only FIPS-mode test is
the license library's own (below). In particular:

- **RSA Operations Portal:** the RSA Authentication Manager SDK (`am-client` 8.8, and the WebLogic
  client it uses for `t3s`), and the Connector's start script, which uses `openssl` to build its
  key store;
- **ReadyRoom:** Windows (Kerberos) sign-in, and the Windows client on workstations with Windows'
  FIPS policy;
- **Directory Services Portal:** the native package on a FIPS-enabled host's own Java;
- **CertAlert:** the UnboundID LDAP SDK in its jar, used only by the demo mode;
- **every product:** the whole application, end to end, on a FIPS-enabled host.

### The license check

!!! warning "Current state: licenses can't be verified on a Java runtime in FIPS mode"
    All four applications carry license library 1.0.0, which checks only the license's Ed25519
    signature. Red Hat's OpenJDK in FIPS mode has no Ed25519 signatures, so on a FIPS-enabled host
    every license is refused and the application is **unlicensed**. Don't work around it with
    `-Dcom.redhat.fips=false`: that takes the whole JVM out of FIPS mode.

It changes when both of these have happened:

1. ENS Solutions creates its production ECDSA P-256 signing key (not yet done), so that license
   files carry an ECDSA P-256 signature next to the Ed25519 one;
2. each application ships a release with license library 1.1.0, which checks the P-256 signature
   through the runtime's FIPS provider when Ed25519 is not available.

Files issued before step 1 will need to be re-issued for use in FIPS mode. The details, and the
results of the library's FIPS tests, are in [Licensing → FIPS 140-3](licensing.md#fips-140-3).

## DISA STIG

All four applications implement the same ENS platform baseline: a notice-and-consent banner,
session limits, security headers, an audit trail, and (where certificates sign in) revocation
checking. Most of it is on by default. The settings that need a decision are below; each
product's page has the full table.

| Control | CertAlert | Directory Services Portal | ReadyRoom | RSA Operations Portal |
|---|---|---|---|---|
| Notice and consent banner (`ENS_CONSENT_BANNER_ENABLED`, off by default) | Yes | Yes | Yes | Yes |
| Idle, administrator idle and absolute timeouts, sessions per user (`ENS_SESSION_*`: `30m`, the idle value, `12h`, no limit) | Yes | Yes | Yes | Yes |
| Failed sign-in lockout | The directory's password policy | `LOGIN_MAX_FAILURES` (8) in `LOGIN_LOCKOUT_WINDOW` (15m), plus the directory's own | The directory's password policy | Authentication Manager's lockout policy |
| Client-certificate revocation checking (`ENS_X509_REVOCATION`, off by default) | Yes | Yes | Yes | Not applicable: SecurID sign-in |
| Allowed certificate issuers (`ENS_X509_ALLOWED_ISSUERS`) | Yes | Yes | Yes | Not applicable |
| Security headers, CSRF protection (`ENS_CSP_REPORT_ONLY` must stay `false`) | Yes | Yes | Yes | Yes |
| Secure session cookie behind a TLS proxy (`ENS_COOKIE_SECURE`) | Yes | Yes | Yes | Yes |
| Hash-chained audit trail, forwarded to a SIEM ([Audit logging](audit-logging.md)) | Yes | Yes | Yes | Yes |
| Prometheus metrics on a separate management port (`MANAGEMENT_SERVER_PORT`) | Yes | Yes | Yes | Yes |
| Helm chart: non-root, read-only root file system, no capabilities | Yes | Yes | Yes | Yes |

Settings worth fixing for every STIG deployment:

- `ENS_CONSENT_BANNER_ENABLED=true` (the standard DoD text is the default);
- `ENS_SESSION_ADMIN_IDLE_TIMEOUT=15m` and `ENS_SESSION_MAX_PER_USER=3` are the suggested values;
- `ENS_X509_REVOCATION` set to `ocsp`, `crl` or `ocsp-then-crl` wherever certificates sign in, with
  `ENS_X509_REVOCATION_FAIL=closed`;
- `ENS_COOKIE_SECURE=true` whenever TLS ends at a proxy;
- the audit trail forwarded to your SIEM, and `ENS_AUDIT_RETENTION_DAYS` set to your policy;
- the management port kept off every ingress, route and published port.

### Supply chain

| Product | Images and chart signed with cosign | SPDX SBOM | CI vulnerability scan (Trivy) | Release download |
|---|---|---|---|---|
| CertAlert | Yes, on release tags | In the download, and attested on the image | Yes | `SHA256SUMS`, signed with GPG |
| Directory Services Portal | Yes, on release tags | In the download, and attested on the images | Yes | `SHA256SUMS`, signed with GPG |
| ReadyRoom | Yes, on release tags | In the download, and attested on the image | Yes | `SHA256SUMS`, signed with GPG |
| RSA Operations Portal | No | In the download | No | `SHA256SUMS`, signed with GPG |

The download's `SHA256SUMS.asc` exists only when ENS's release signing key is set on the
product's repository. How to check the signatures is in
[Releases and downloads](releases.md#verifying-the-images-and-the-chart).
