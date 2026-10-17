## Wave 2: RSA Connector hardening (RSA-03, RSA-04, RSA-18, RSA-15)

All four findings are fixed on `claude/wave2-connector` in `/home/user/wt/w2-rsa-conn`, as 7 commits on top of `85fdd1b`. Nothing is pushed. The full suite passes, and so do `helm lint --strict` and kubeconform. One thing to know before merging: the Portal and Connector must be upgraded together, because the Hessian interface changed.

**Commits.** I amended the WIP commit into the RSA-03 commit; there was no other history rewrite.
- `362bbd7` RSA-03: HTTPS between the Portal and the Connector.
- `2be2f80` RSA-04: operators' changes run as the operator, a separate read-only account, passwords read from files.
- `dbfca26` RSA-18 (Connector part): `/app/health`.
- `79eae2f` RSA-03 follow-up: Tomcat gets the key in a PKCS12 store built at startup. With a PEM key it logged "insecure" on every start.
- `62bf4e4` RSA-15 (Connector part): TLS 1.3 allowed towards AM.
- `4942132` and `85f3d13` follow-ups: the Jenkins deployment-package installer, the podman installer and the podman connector unit now run the new tools.

**Tests**
- Full `mvn test`: BUILD SUCCESS. api 3, mock 40, connector 95, portal 604; 742 tests, 0 failures.
- `helm lint --strict` and kubeconform 1.29 (`-skip Route`) are clean for all 6 `ci/*.yaml`.
- I also built the Connector image and ran it against the mock AM:
  - HTTPS works; TLS 1.3 negotiates and 1.2 is accepted.
  - Port 8080 is unreachable from outside the container.
  - Mutual TLS refuses a client without a certificate.
  - It runs on a read-only root filesystem with passwords from files.
  - `/app/health` answers UP/DOWN.
  - The Portal's real Hessian client (from `AppConfig`) ran login, lookup, an as-operator command and the health indicator over mutual TLS.
- Images and containers are cleaned up.

### RSA-03: the Portal-to-Connector hop is now HTTPS
- **Connector:**
  - The image ships its own `tomcat/server.xml` and `setenv.sh`: HTTPS on 8443, TLS 1.2 and 1.3.
  - Plain HTTP on 8080 now listens on 127.0.0.1 only, for health checks.
  - With no certificate it starts with a throwaway self-signed one, which the Portal refuses.
  - `openssl` was added to the image.
- **Portal:**
  - The default URL is now `https://connector:8443/app/connector`.
  - New `ConnectorTls` checks the certificate and host name. There is no trust-all option.
  - A plain `http` URL logs a warning.
- **Release bundle:**
  - New `util/connector-tls.sh` (run by `install.sh` and `upgrade.sh`) makes a CA for the installation and the certificates. Their layout is in the upgrade notes below.
  - It renews its own certificates when 60 days are left (`--renew` forces it) and never touches certificates it did not make.
  - It adds the trust-store setting to `.env` and rewrites an explicit `http://connector:8080` address to https.
  - Generated certificates are git-, docker- and bundle-ignored.
- **Helm:**
  - The chart makes the CA and certificates with `genCA` / `genSignedCert` in `<release>-rsa-operations-portal-connector-tls`, reused across upgrades via `lookup`.
  - The Service port is 8443.
  - Each pod mounts only its own keys.
  - The Connector NetworkPolicy is on by default (ingress from Portal pods only), with the reason given in `values.yaml`.
- **Tests:** `ConnectorTlsTest` (12) uses certificates keytool makes at test time. A trusted CA and a PKCS12 trust store work. An impostor certificate, the default JVM trust store, a host-name mismatch and a missing client certificate under mutual TLS are all refused.

New settings:

| Where | Setting | Default | Meaning |
|---|---|---|---|
| Portal | `connector.url` (`CONNECTOR_URL`) | `https://connector:8443/app/connector` | The Connector's address |
| Portal | `connector.tls.truststore` (`CONNECTOR_TLS_TRUSTSTORE`) | Java's default trust store | CA to check the Connector against: PEM, or PKCS12/JKS. The template sets `/ssl/connector-ca.crt` |
| Portal | `connector.tls.truststore-password` | — | Password of a PKCS12/JKS trust store |
| Portal | `connector.tls.client-certificate`, `connector.tls.client-key`, `connector.tls.client-key-password` | unset | The Portal's certificate for mutual TLS |
| Connector | `CONNECTOR_TLS_CERTIFICATE`, `CONNECTOR_TLS_PRIVATE_KEY` | `/custom/connector-tls/tls.crt`, `/custom/connector-tls/tls.key` | The Connector's certificate and key |
| Connector | `CONNECTOR_TLS_PRIVATE_KEY_PASSWORD` | set by the script | Password of the encrypted key |
| Connector | `CONNECTOR_TLS_CLIENT_AUTH` | `none` | `required` turns on mutual TLS |
| Connector | `CONNECTOR_TLS_CLIENT_CA` | `/custom/connector-tls/ca.crt` | CA for client certificates |
| Connector | `CONNECTOR_HTTP_ADDRESS` | `127.0.0.1` | `0.0.0.0` reopens plain HTTP; demo opt-out only |
| Helm | `connector.tls.existingSecret` | `""` (chart makes the CA) | Your own `tls.crt`/`tls.key`/`ca.crt`, e.g. from cert-manager |
| Helm | `connector.tls.mutual`, `connector.tls.clientSecret` | `false`, `""` | Mutual TLS; client certificate when using `existingSecret` |
| Helm | `connector.tls.validityDays` | `3650` | Validity of the chart-made certificates |
| Helm | `connector.service.port` | `8443` (was 8080) | Connector Service port |
| Helm | `networkPolicy.connector.enabled` | `true` | Only Portal pods may reach the Connector |

### RSA-04: operator attribution, least privilege, passwords from files
- **Interface change:** the role grant and revoke calls and `clearCachedWindowsPassword` now take the operator's `AppAuthenticationToken`. The Portal passes it from the user-role, trusted-agent and user-search code.
- **Who runs what:**
  - The operator's own AM session runs role grants and revokes, clearing cached Windows passwords, and emergency-code generation, as well as the token, PIN and account changes that already did.
  - The service account runs only work with no operator: the trusted-agent re-sync job, expired-token cleanup, users swapping or setting the PIN of their own token, and PIN resets with a certificate or reset code.
  - The `RSAAuthManagerService` javadoc lists which is which.
- **Bug fixed along the way:** running a command as the operator returned a copy, so results were lost. Emergency codes generated as the operator would have come back null.
- **Read-only account:** new `rsa.readonly.user.id` / `.password`. If unset, the service account is used and a WARN is logged.
- **Passwords from files:** a new listener reads the Command Client, admin, read-only and trust-store credentials. A file (`NAME_FILE`, or `-D<property>.file`) wins over an `RSA_*` variable, which wins over a `-D` option; a `-D` password logs a warning.
  - The template moves them out of `JAVA_OPTS`, and new `util/move-connector-secrets.sh` (run by `install.sh` and `upgrade.sh`) does the same to an existing `connector.env`.
  - Helm mounts them from the Secrets as files at `/var/run/secrets/rsa`; none is on the command line.
- **Tests:** the mock AM now records which user's session ran each command. Tests check:
  - Operator commands run as the operator.
  - Swaps, cleanup and scheduled role changes run as the service account.
  - Searches use the read-only account when one is configured.
  - Passwords are read from files.
  - The precedence order holds.
  - The Portal passes the operator through.

New settings:
- **Connector:** `RSA_CMDCLIENT_USER`/`_PASSWORD`, `RSA_ADMIN_USER_ID`/`_PASSWORD`, `RSA_READONLY_USER_ID`/`_PASSWORD`, `RSA_TRUSTSTORE_PASSWORD`. Each password also has a `_FILE` form.
- **Helm:** `authManager.readOnlyUser.{id,password,existingSecret,idKey,passwordKey}`.

### RSA-18 (Connector part): health
- **Connector:** `GET`/`HEAD /app/health` needs no credentials. It answers 200 `UP` when an AM admin session exists or can be opened, and 503 `DOWN` otherwise. The result is cached for 30 s, and the reason goes to the log only. Tomcat error pages no longer show stack traces or the version.
- **Checks:** the compose files (production, local, pipeline), the podman unit and the Helm readiness probe use `curl http://localhost:8080/app/health`. Liveness and startup check the HTTPS port.
- **Portal:** new `ConnectorHealthIndicator` bean, named `connector`, ready for the other agent's readiness group. It uses the same TLS settings. `connector.health-url` and `connector.health-timeout-millis` (default 5000) override the address and the wait.

### RSA-15 (Connector part): TLS 1.3
`-Djdk.tls.client.protocols=TLSv1.2` is removed from the Connector Dockerfile, `connector.env.example` and Helm `connector.javaOpts`. The Connector now offers `-Dhttps.protocols=TLSv1.3,TLSv1.2`. I could not confirm that AM 8.x's admin API supports TLS 1.3, so both are offered and an AM without 1.3 still gets 1.2. Existing `connector.env` files keep their 1.2-only flags; the README says to replace them.

### Upgrade notes
- **Release bundle:** `upgrade.sh` makes the TLS material and edits the settings itself, so the switch to HTTPS needs no manual edit:
  - `connector-tls-ca/` holds the CA. Its key is never mounted.
  - `default_build/connector-tls/` holds the Connector's certificate and an encrypted key, whose password goes into `connector.env`.
  - `default_build/ssl/` gets `connector-ca.crt` and `portal-client.*`.
  - It adds `CONNECTOR_TLS_TRUSTSTORE` to `.env`.
  - It moves `-D` AM credentials into `RSA_*` variables, keeping `connector.env.before-secrets.<time>` (chmod 600). That file holds the old passwords; operators should delete it once the Connector runs.
  - If `openssl` is missing on the host, it uses the one in the Connector image.
  - The documented opt-out (`CONNECTOR_HTTP_ADDRESS=0.0.0.0` plus an http `CONNECTOR_URL`) is used only by `docker-compose.demo.yaml`, which has no certificate files.
- **Helm:** `helm upgrade` creates the TLS Secret, switches `CONNECTOR_URL` to https:8443, mounts the passwords as files and adds the Connector NetworkPolicy. Pods roll once. On clusters without NetworkPolicy support, set `networkPolicy.connector.enabled=false`.
- **AM-side change for operators:** operators' AM roles now need these permissions, because those actions run as the operator:
  - Assign/unassign administrative roles, for PMO.
  - Clear cached Windows credentials, for TA and Help Desk.
  - Emergency-access generation, for TA and Help Desk.

### AM role definitions for the docs
I could not check the exact checkbox labels; verify them against the AM 8.8 Security Console.
- **Service account role** (scope: the managed security domain and its identity sources). It needs:
  - View users, realms, security domains, administrative roles and sessions.
  - SecurID tokens: view; edit (set PIN, require new PIN); replace; resynchronize, for the tokencode check; unassign.
  - Assign/unassign the Trusted Agent administrative role only, for the re-sync job.
  - It does not need: adding or deleting users, assigning tokens, emergency access, enabling or unlocking accounts, Windows credentials, or system settings.
- **Read-only role** (same scope): view-only on users and AM principal attributes, tokens, administrative-role membership, realms, security domains and sessions.

### Not done
- **CI kubeconform:** I did not touch CI. The chart renders no cert-manager `Certificate`; you point `existingSecret` at one instead, so `-skip Route` is still enough.
- **Portal's own JVM flags:** the Portal's `JAVA_OPTS` (Dockerfile, Helm `javaOpts`, `dev.env.example`) still pins TLS 1.2. That is the Portal part of RSA-15.
- **Untested against a real cluster or AM:**
  - Helm `lookup` persistence; `helm template` makes new certificates each time.
  - TLS 1.3 against a real AM.
- **Local-only workaround:** for the image test I built with a local copy of the Dockerfile that trusts the sandbox's egress CA. The committed Dockerfile is not affected.

### Docs edits needed in `/home/user/ens-apps-documentation/docs/rsa-operations-portal/`
- **`rsa-integration.md`:**
  - Diagram (line 7) and Transport table (line 18): now HTTPS on 8443 with certificate checks, optional mutual TLS, and the URL is configurable via `connector.url`.
  - Sessions (line 26): admin session uses `rsa.admin.user.id`; read-only session uses `rsa.readonly.user.id`.
  - Operations: list what runs as the operator and what runs as the service account.
  - Checklist (line 89): replace "Super Admin" with the service-account role plus the read-only user; add the new operator permissions.
  - New section: the `/app/health` endpoint.
- **`install.md`:**
  - Lines 36, 212–217, 469: drop Super Admin in favour of the custom roles.
  - Line 127 and 458: credentials are now `RSA_*` variables, not `-D` options in `JAVA_OPTS`.
  - Line 225: Helm mounts the passwords as files.
  - Line 274: add `authManager.readOnlyUser`.
  - `install.sh` and `upgrade.sh` now run the certificate tool; describe the files it creates.
  - Line 505: mention the `connector` health indicator alongside the existing one.
  - Helm: `connector.tls.*`, `networkPolicy.connector.enabled`, Service port 8443.
- **`configuration.md`:**
  - Lines 24 and 42: `JAVA_OPTS` and the TLS-flags note.
  - Line 52: Connector reached at `connector:8443` over HTTPS; health check is `/app/health`.
  - Lines 251 and 267: Super Admin wording.
  - New tables for the Portal `connector.tls.*` / `connector.health-*` settings and the Connector `CONNECTOR_TLS_*`, `CONNECTOR_HTTP_ADDRESS` and `RSA_*` / `_FILE` settings.
  - Precedence of password sources: file, then variable, then `-D`.
- **`security.md`:**
  - Line 58: the hop is now TLS 1.2/1.3 with certificate and host-name checks, optional mutual TLS, plain HTTP on loopback only, and the NetworkPolicy on by default.
  - Line 7: list the newly operator-attributed actions.
  - Note passwords are no longer on the command line.
  - Note `/app/health` is unauthenticated but reveals only UP or DOWN, and Tomcat error reports are hidden.