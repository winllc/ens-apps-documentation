# TA-ens-audit

A Splunk add-on for ENS application audit events (`ens-audit/1`). These come from CertAlert, the
Directory Services Portal, ReadyRoom, the RSA Operations Portal, the ENS licensing portal and the
syncd console (identity synchronisation and governance).

- **Parsing:** sourcetype `ens:audit` (JSON) and `ens:audit:syslog` (RFC 5424, renamed to `ens:audit`).
- **CIM mapping:** **Authentication** (`tag=authentication`), **Change** (`tag=change`), and access
  denials (`tag=access`). The product's own action id is kept as `ens_action`.
- **Event types:** `ens_audit`, `ens_audit_authentication`, `ens_audit_access_denied`,
  `ens_audit_change`, `ens_audit_integrity`, and `ens_audit_governance` (syncd's actions that start
  with `governance.`: requests, reviews, assignments, policies, roles, reports).
- **Pull input:** `bin/ens_audit_pull.py`, a scripted input that pulls from an application's
  `/api/audit/events` API with a read-only token (syncd's is `/api/v1/audit/events`: give the base
  URL with `/api/v1`, and a token with the `audit:read` scope). It is disabled by default; see
  `default/inputs.conf`.

**Install:** copy this directory to `$SPLUNK_HOME/etc/apps/` on search heads and on any indexer or heavy
forwarder that receives the events, then restart Splunk. For a packaged app, run
`tar czf TA-ens-audit.tgz TA-ens-audit` from its parent directory.

Full setup: the **Audit logging** page of the ENS applications documentation.
