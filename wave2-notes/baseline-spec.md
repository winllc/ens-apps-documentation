# ENS platform baseline v1 (Wave 2): the shared spec every app implements

The readiness review recommended building these once, in a shared "platform starter". The apps don't share a build or a package registry, and the UI layers differ: Thymeleaf in three apps, React in DSP. So each app implements the baseline itself, to **this one spec**. Use the same setting names, defaults, behaviour, audit event names and metric names everywhere. An operator who has configured one app should be able to configure the next from the same table.

Map each `ENS_*` environment variable to a property under the app's own prefix where the app has one, but **the environment variable names below must work exactly as written**. Prefer a shared `ens.*` property namespace bound with `@ConfigurationProperties("ens")` in each app, so Spring's relaxed binding gives these names automatically.

## 1. Security headers and cookies
- **Content-Security-Policy**, on every HTML response:
  - `default-src 'self'; script-src 'self'[ 'nonce-…']; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'; object-src 'none'`
  - Inline `<script>` blocks must be moved to static files, or given a per-request nonce (Thymeleaf: a request attribute `cspNonce` and `th:attr="nonce=${cspNonce}"`).
  - Inline event handlers (`onclick=`) must go; bind them in JS.
  - Don't use `'unsafe-inline'` or `'unsafe-eval'` for scripts.
  - `ENS_CSP_REPORT_ONLY` (default `false`): `true` sends `Content-Security-Policy-Report-Only` instead. This is an escape hatch for a site that finds a page the tests missed.
- **Other headers:**
  - `Strict-Transport-Security: max-age=31536000; includeSubDomains` on secure requests. A request is secure when TLS is direct, or when it arrives behind a proxy with `server.forward-headers-strategy` set.
  - `X-Content-Type-Options: nosniff`
  - `X-Frame-Options: DENY`
  - `Referrer-Policy: strict-origin-when-cross-origin`
  - `Permissions-Policy: camera=(), microphone=(), geolocation=(), payment=()`
- **Session cookie:** `HttpOnly`, `SameSite=Lax`, and `Secure` when TLS is on, or always when `ENS_COOKIE_SECURE=true` (for TLS terminated at a proxy).
- **Forwarded headers:** document `SERVER_FORWARD_HEADERS_STRATEGY=framework` (Boot's own setting) for running behind a TLS-terminating proxy. Helm charts set it whenever an ingress or route terminates TLS.

## 2. Notice-and-consent banner (DISA STIG)
| Setting | Default | |
|---|---|---|
| `ENS_CONSENT_BANNER_ENABLED` | `false` | Show the banner before sign-in |
| `ENS_CONSENT_BANNER_TITLE` | `U.S. Government (USG) Information System (IS) Notice and Consent` | |
| `ENS_CONSENT_BANNER_TEXT` | the standard DoD notice-and-consent text (the 5-bullet "You are accessing a U.S. Government (USG) Information System…" text) | Plain text; blank lines separate paragraphs |
| `ENS_CONSENT_BANNER_FILE` | — | Read the text from a file instead (wins over `_TEXT`) |

- **Enforced server-side, not just in the UI.** While enabled, no sign-in of any kind succeeds until this browser session has acknowledged the banner. That covers form, X.509 and any auto sign-in. The banner page and its POST are reachable without signing in.
- **Acknowledging it:** an explicit "I agree" button. It is recorded in the session and audited as `auth.consent-acknowledged` (category AUTHENTICATION, success, with the client address).
- **APIs:** machine endpoints that are not browser sessions are exempt, and the app documents which. Examples: ReadyRoom's agent API `/api/check/**`, audit pull APIs with bearer tokens, health and metrics.
- **The default DoD text, exactly:**
  > You are accessing a U.S. Government (USG) Information System (IS) that is provided for USG-authorized use only. By using this IS (which includes any device attached to this IS), you consent to the following conditions:
  > - The USG routinely intercepts and monitors communications on this IS for purposes including, but not limited to, penetration testing, COMSEC monitoring, network operations and defense, personnel misconduct (PM), law enforcement (LE), and counterintelligence (CI) investigations.
  > - At any time, the USG may inspect and seize data stored on this IS.
  > - Communications using, or data stored on, this IS are not private, are subject to routine monitoring, interception, and search, and may be disclosed or used for any USG-authorized purpose.
  > - This IS includes security measures (e.g., authentication and access controls) to protect USG interests--not for your personal benefit or privacy.
  > - Notwithstanding the above, using this IS does not constitute consent to PM, LE or CI investigative searching or monitoring of the content of privileged communications, or work product, related to personal representation or services by attorneys, psychotherapists, or clergy, and their assistants. Such communications and work product are private and confidential. See User Agreement for details.

## 3. Session controls
| Setting | Default | |
|---|---|---|
| `ENS_SESSION_IDLE_TIMEOUT` | `30m` | Idle timeout, as a Spring `Duration` |
| `ENS_SESSION_ADMIN_IDLE_TIMEOUT` | same as idle | Shorter idle timeout for administrators (STIG suggests `15m`) |
| `ENS_SESSION_ABSOLUTE_TIMEOUT` | `12h` | A session ends this long after sign-in, whatever its activity |
| `ENS_SESSION_MAX_PER_USER` | `0` (no limit) | Concurrent sessions per user. Over the limit, the **oldest** session is expired (STIG suggests `3`) |

- **Expiry:** an expired session goes to sign-in (APIs get 401). It is audited as `auth.session-expired`, with reason `idle`, `absolute` or `concurrent`, where the app can tell.
- **Existing settings:** where an app already has its own idle-timeout setting, keep it working as an alias, and document the `ENS_` name as the one to use.

## 4. Observability
- **Metrics:**
  - Add `io.micrometer:micrometer-registry-prometheus`.
  - Expose `/actuator/prometheus` **only on the management port**: `MANAGEMENT_SERVER_PORT` (Boot's own setting). It is unset by default, and then Prometheus is not exposed at all.
  - On the management port, `health` and `prometheus` are open without sign-in. That port is never published by the ingress or route.
  - The main port keeps exposing only `health`, exactly as today.
- **Health:**
  - `/actuator/health/liveness`: app state only.
  - `/actuator/health/readiness`: includes the things the app can't serve without, such as the database, the directory (LDAP), the audit log being writable, and the RSA Connector.
  - Show details only to authorized users (`when-authorized`) on the main port.
- **Business metrics** (Micrometer names, `ens_` prefix, snake_case once rendered by Prometheus):
  - `ens_license_mode{mode}` (gauge 1 for the current mode), `ens_license_days_remaining` (gauge).
  - `ens_audit_events_total{category,outcome}` (counter), `ens_audit_forward_failures_total{output}` (counter, if the app forwards audit).
  - `ens_auth_attempts_total{method,outcome}` (counter).
  - App-specific gauges named `ens_<app>_*`, where `<app>` is `certalert`, `dsp`, `readyroom` or `rsa`. Examples: certificates by status, last successful job time, people checked in, Auth Manager call latency (a timer).
- **Logs:** structured JSON through Boot's built-in support, `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`, documented. Helm adds a value `logFormat: text|json` (default `json` in the chart). The release bundle documents it in env.template, commented.
- **Helm:**
  - With `metrics.enabled` (default `true`): set `MANAGEMENT_SERVER_PORT` (default `9090`), add a `metrics` port to the Service, and point the probes at the management port.
  - `metrics.serviceMonitor.enabled` (default `false`) renders a `monitoring.coreos.com/v1` ServiceMonitor.
  - The NetworkPolicy, when enabled, admits the metrics port from `metrics.allowFrom` (a list of NetworkPolicy peers).

## 5. Supply chain, in each repo's `.github/`
- **Dependabot** (`.github/dependabot.yml`): weekly updates, grouped minor/patch, for:
  - the build system (gradle, maven, npm, as applicable);
  - docker (`directory` per Dockerfile);
  - github-actions.
- **Pinning:**
  - Pin every GitHub Action to a full commit SHA, with the version in a trailing comment (`uses: actions/checkout@<sha> # v4.2.2`). Look up the SHAs with `git ls-remote https://github.com/<owner>/<action> refs/tags/<tag>`.
  - Pin every base image by digest, with the tag kept for readability (`FROM registry/image:tag@sha256:…`). Get digests with `docker buildx imagetools inspect` or `skopeo`. Dependabot keeps digests current.
- **Image scan gate:** Trivy runs on each built image in CI, on every push and PR, with `--severity HIGH,CRITICAL --ignore-unfixed --exit-code 1`.
  - Pin Trivy itself by version.
  - Accepted risks go in `.trivyignore` with a comment giving the reason and a review date. Keep them few and justified.
  - Check locally (install trivy into scratch) that the gate passes on your images before pushing. Fix what can be fixed: bump packages, `dnf update` / `microdnf update` in the image if the repo does that, upgrade dependencies.
- **SAST:**
  - Public repos (cert-alert, directory-services-portal, in-n-out-work): a CodeQL workflow (`github/codeql-action` init/analyze, Java plus JavaScript where present) on push, PR and weekly.
  - Private repo (rsa-operations-portal): SpotBugs with FindSecBugs in the Maven build, failing on high-priority security findings. Suppress false positives in an exclude filter with reasons.
- **Signing and attestation, on release tags only:**
  - Cosign keyless signing (GitHub OIDC; `id-token: write`) of every pushed image **by digest**.
  - An SPDX SBOM attestation (`cosign attest --type spdxjson`) per image, from the SBOMs the release job already makes, or syft.
  - Sign the Helm chart OCI artifact too.
  - The release notes or README say how to verify: `cosign verify ghcr.io/winllc/<image>@<digest> --certificate-identity-regexp 'https://github.com/winllc/<repo>/.github/workflows/.*' --certificate-oidc-issuer https://token.actions.githubusercontent.com`.
- **Licensing labels:** replace any "MIT" in OCI labels, Chart.yaml annotations or package metadata with `LicenseRef-ENS-Commercial`, and point to the EULA. The license library has a `LICENSE`/EULA if one exists; otherwise add a short `LICENSE` stating the software is proprietary to ENS Solutions and use requires a license.

## 6. FIPS statement
Each app's own docs or README get a short "FIPS 140-3" note. It states what the app relies on (the platform JDK's providers on a FIPS-enabled RHEL/UBI host, where the image is UBI-based) and which options are not FIPS-appropriate (trust-all toggles and the like). The results of the license-library FIPS test go in later; leave a clear placeholder heading only if needed, and say so in your report.

## 7. Rules for every agent
- Keep each app's existing conventions, and match its code style and comment density.
- Every behaviour change gets tests.
- The full suite must pass, and the Helm chart must lint and render with kubeconform, as CI does.
- Report every new setting (name, default, meaning) and every behaviour change that operators or users will notice, so the docs can be written from your report.

## 8. X.509 / CAC-PIV (CertAlert, DSP and ReadyRoom; not RSA)
The same semantics and environment variables in every app that signs in with client certificates.

### Revocation checking
| Setting | Default | |
|---|---|---|
| `ENS_X509_REVOCATION` | `off` | `off`, `ocsp`, `crl` or `ocsp-then-crl` |
| `ENS_X509_OCSP_RESPONDER_URL` | — | Override the AIA responder, e.g. a local OCSP relay on an air-gapped network |
| `ENS_X509_CRL_DIR` | — | A directory of CRL files (DER or PEM), read and refreshed when they change. This is the air-gapped option. With it set, CRL distribution points are **not** fetched over the network |
| `ENS_X509_REVOCATION_FAIL` | `closed` | `closed`: when the status can't be determined, refuse. `open`: allow and log a WARN |
| `ENS_X509_REVOCATION_CACHE` | `10m` | How long a good or revoked answer is cached, per certificate serial and issuer |

- **Implementation:**
  - Use the JDK PKIX validator with a `PKIXRevocationChecker`, or an explicit OCSP client plus CRL store, with **per-check configuration**. Never flip global JDK security properties per request.
  - Bound network fetches: connect and read timeouts of 5s and 10s, a maximum CRL size, no redirects to non-http(s) schemes, and block link-local and cloud-metadata addresses.
- **Upgrade safety:** the default stays `off`. But when X.509 sign-in is enabled and revocation is `off`, log one WARN at startup saying STIG requires revocation checking.
- **Audit:** a refusal for revocation is audited as `auth.login` failure with reason `certificate revoked` or `revocation unknown`.

### Identity mapping from a certificate
Wherever an app maps a certificate to a directory entry with a template or filter, support these placeholders, in addition to what the app supports today:
| Placeholder | Value |
|---|---|
| `{upn}` | The SAN otherName UserPrincipalName (OID 1.3.6.1.4.1.311.20.2.3) |
| `{edipi}` | The DoD EDIPI: the digits before `@` in a UPN of the form `1234567890@mil`, or else the trailing `.NNNNNNNNNN` of the subject CN (`LAST.FIRST.MI.1234567890`) |
| `{email}` | The rfc822Name SAN, or else the subject emailAddress |
| `{uuid}` | The SAN URI `urn:uuid:…`, the PIV FASC-N UUID |

- **Escaping:** values must be LDAP-filter-escaped (or DN-escaped) where they are inserted.
- **Matching:** a mapping that finds zero or more than one entry signs nobody in.
- **Issuer allowlist:** `ENS_X509_ALLOWED_ISSUERS`, an optional list of issuer DNs. When set, certificates from other issuers are refused, even if a trusted CA chains to them.

### Certificates forwarded by a TLS-terminating proxy
For apps that today accept client certificates only when they terminate TLS themselves (CertAlert, ReadyRoom):
- **Settings:** add a header mode like DSP's.
  - `ENS_X509_SOURCE`: `servlet` (default) or `header`.
  - `ENS_X509_HEADER`: default `X-SSL-Client-Cert`, URL-encoded PEM as nginx `$ssl_client_escaped_cert` sends it.
  - `ENS_X509_TRUSTED_PROXIES`: CIDRs.
  - `ENS_X509_PROXY_SECRET_FILE` / `ENS_X509_PROXY_SECRET`: at least 32 characters, sent in header `X-ENS-Proxy-Secret`, compared in constant time.
- **Rules:**
  - The header is accepted only when the peer is a trusted proxy **and** the secret matches.
  - The app refuses to start in header mode without a secret, or without a trust store / CA file to validate the forwarded chain.
  - Refusals are audited.
- **DSP** already has this under its own names (`X509_*`, `X-DSP-Proxy-Secret`). Leave DSP's names as they are.
