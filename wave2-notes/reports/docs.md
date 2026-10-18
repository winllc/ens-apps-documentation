The Wave 2 docs are on `claude/wave2-docs` in /home/user/wt/w2-docs, in five commits on master 23b6087, not pushed. `mkdocs build --strict` passes at the branch head with no warnings and no missing anchors. The first three commits do not build strictly on their own: their product FIPS pages link to `../fips-and-stig.md`, which only arrives in the last commit, so merge the branch as a whole. I did not touch `wave2-notes/` or any code repository. I deleted the scratchpad `site/`; the venv (about 147 MB) is still at /tmp/claude-0/-home-user/d439962b-e839-5efd-b1dd-58c48afa5765/scratchpad/venv.

## Commits and pages

1. **5d67200, Directory Services Portal (DSP-20).**
   - Changed: configuration, operator-guide, security, api-reference, install, user-guide, admin-guide, architecture and index.
   - Added: availability-and-sizing.md and fips-and-stig.md.
   - Fixed: `AUDIT_MEMORY_EVENTS` is now marked as no longer used, and the "audit files grow without limit" text now points to `ENS_AUDIT_RETENTION_DAYS`.
   - White-pages needs the directory-editor feature: confirmed in the code. The tested-directory matrix shows only the UnboundID in-memory directory as tested.
2. **0972b93, RSA Operations Portal (Connector work and RSA-17).**
   - Rewritten: security and rsa-integration. The rsa-integration page now covers the Connector link, who runs each AM command, least-privilege AM roles and an AM compatibility matrix.
   - Changed: index, architecture, configuration, install, operator-guide, troubleshooting, api-reference and admin-guide.
   - Added: availability-and-sizing.md and fips-and-stig.md.
3. **519458a, CertAlert (CA-19).**
   - Operator guide: backups, restore, and migrations up to V19. It states that no scheduled backup is shipped; only `upgrade.sh` writes a dump.
   - Also changed: architecture, install and index.
   - Added: availability-and-sizing.md, with sizing labelled unbenchmarked and the disaster-recovery runbook.
4. **8eb93ec, ReadyRoom.**
   - Every item in the agent's "docs edits needed" list is done. The audit-logging and releases items went into commit 5.
   - Changed: install, configuration, operator-guide, security, troubleshooting, index and api-reference.
   - Added: availability-and-sizing.md and fips-and-stig.md.
   - The bundle health check is documented as following `MANAGEMENT_SERVER_PORT`, as on the `claude/wave2-followup` branch.
5. **98ac014, cross-product.**
   - New suite pages, both linked from index.md and the top-level nav:
     - docs/fips-and-stig.md: where the cryptography comes from, TLS defaults, options that are not FIPS-appropriate, what is untested, the license check, a STIG controls matrix and the supply chain.
     - docs/availability-and-sizing.md.
   - licensing.md:
     - New FIPS 140-3 section. It describes the current state: library 1.0.0 checks Ed25519 only, so on a FIPS-mode JVM the app is unlicensed.
     - It says dual-signed files need ENS's P-256 key (not yet created) plus library 1.1.0, and that `-Dcom.redhat.fips=false` is not a fix.
     - The ENS keys section now covers `LICENSE_SIGNING_KEY_P256`, `keygen --alg p256`, Re-sign and `verify --alg p256`, all checked against ens-products-page caf76f8.
   - releases.md: cosign verification for CertAlert, DSP and ReadyRoom. RSA main does not sign images or the chart.
   - audit-logging.md: adds `auth.consent-acknowledged`, `auth.session-expired` (reasons idle, absolute, concurrent), certificate refusal reasons, and `ENS_AUDIT_QUERY_MAX_SCAN` (DSP only).
   - index.md "At a glance": RSA is now Spring Boot 4 / Java 17 with a Tomcat 9 connector on MySQL 8.4.

## Claims I could not verify from code

- **RSA AM roles and compatibility:**
  - The least-privilege role permission labels are not checked against the AM 8.8 Security Console; the page carries a warning saying so.
  - Only the mock AM has been tested. Real AM 8.x and TLS 1.3 to a real AM are untested.
- **Untested in FIPS mode:**
  - the AM SDK (`am-client` and `wlfullclient`) and the Connector's openssl start script;
  - ReadyRoom Kerberos sign-in and the Windows client;
  - the DSP native package on the host's Java;
  - every app end to end, since no product has a FIPS CI job (only the license library has one, in products-page).
- **Untested procedures and figures:**
  - Rollback procedures for all products are described as "not tested by ENS".
  - All sizing figures are unbenchmarked; no 100,000-certificate test has been run for CertAlert.
- **Existing behaviour I documented (worth knowing):**
  - The RSA Portal's image and chart pin its outgoing TLS to 1.2.
  - RSA main has no cosign signing and no Trivy scan, and the Connector base image is not pinned to a digest.
  - The ReadyRoom Windows client offers TLS 1.2 or 1.1; the JDK refuses 1.1.
  - DSP lockout counters are held in memory, and its revocation fetches have a DNS-rebinding window.
  - CertAlert's `allow-unverified-crl` defaults to `true`, yet its README says the unsafe options are "off by default".

## Waiting on the agent lists

- **CertAlert:** I left out its baseline features:
  - health (/livez), the consent banner, sessions, X.509 header mode and revocation, metrics;
  - outbox mail settings;
  - its STIG table and FIPS page. The suite FIPS page says these "will be documented with its next update".
  - Also unresolved: main's audit retention schedule is daily "0 30 3 * * *", but the current docs say Sunday.
- **RSA Portal:** the README's FIPS statement and any follow-ups from that agent.
- **ReadyRoom:** `claude/wave2-followup` is not merged yet. Until it is, the documented bundle health-check behaviour runs ahead of main; check it after the merge.
- **License:** the ready-made "Statement for product documentation" (licensing/README.md in ens-products-page) should be published only once the production P-256 key exists and apps ship with library 1.1.0. Until then the pages describe the current state.