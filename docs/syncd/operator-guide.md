# Operator guide

This guide covers running syncd day to day: the services, starting and stopping them, checking
health, reading logs, the `syncd` and `syncdctl` commands, who may use the control API, held
changes, unmanaged entries, backups, the support bundle, an active/passive pair, monitoring,
certificates, the master key and the password keys. It describes syncd 0.5.0 (`main` at commit
`a9d17c6`). [Running on Kubernetes](#running-on-kubernetes) covers the same for the Helm chart.
Installing and upgrading are in [Installation](install.md); the license is in
[Licensing](licensing.md); what to do when something goes wrong is in
[Troubleshooting](troubleshooting.md).

## What runs where

| Process | systemd unit | Runs as | Listens on |
|---|---|---|---|
| Engine (`syncd run`) | `syncd.service` | `syncd` | Control API (gRPC, mutual TLS): `127.0.0.1:7443`. The control socket `/run/syncd/control.sock`, when `api.control_socket` is set. Prometheus metrics, when `api.metrics_listen` is set (for example `127.0.0.1:9464`). The password agents' ingest listener, when `passwords.ingest` is set (for example `0.0.0.0:7445`; from 0.5.0 on both engines of a pair, the passive one forwarding to the active one). The listeners of any `debezium` connectors |
| Console (`syncd-console serve`) | `syncd-console.service` | `syncd-console` | `127.0.0.1:8080` (`CONSOLE_LISTEN`): plain HTTP, or HTTPS when given `CONSOLE_TLS_CERT` and `CONSOLE_TLS_KEY` |
| nginx *(optional, you install it)* | `nginx.service` | nginx | 443 (HTTPS) and 80 (redirect), in front of the console |
| PostgreSQL | your PostgreSQL service | postgres | The console's database, and the lease of an HA pair. On this host or elsewhere |
| JDBC bridge, Debezium Server *(optional)* | yours | yours | See [Installation](install.md#the-jdbc-bridge) |

There is one engine per host, and it is the only writer of its store, `/var/lib/syncd/engine`. The
console reaches it only through the control API. The console's unit starts after `syncd.service`
and `postgresql.service`. From 0.4.0 two engines on two hosts can form an
[active/passive pair](#high-availability); each still has a store of its own.

The console reaches out too, when you set it up to: the audit outputs (Splunk, syslog), webhook
receivers and the pre-publish hook, a mail relay for scheduled reports and group reviews, a Git
server for Git sync, a SAML identity provider's metadata (when given as a URL) and the OCSP responders and CRLs of
certificate sign-in, and a KMS when its master key is wrapped by one. The engine reaches S3 or a
backup directory for [off-host backups](#off-host-backups), and from 0.5.0 a Vault, AWS Secrets
Manager or Azure Key Vault when its configuration refers to secrets there (`vault`,
`aws_secret`, `azure_keyvault`), or a KMS that wraps its password-key passphrase. Nothing calls a
licensing service: a license is a file checked offline (see [Licensing](licensing.md)).

Both units run with systemd's sandboxing: `ProtectSystem=strict`, `ProtectHome`, `PrivateTmp`,
`NoNewPrivileges`, no capabilities, a system-call filter, and write access only to their own
directories (`/var/lib/syncd/engine` and `/etc/syncd/engine` for the engine, and `/run/syncd`,
which its unit creates and removes when it stops; `/var/lib/syncd/console` for the console). The
engine may use 80% of the host's memory (`MemoryMax=80%`) and 65 536 open files.

## Start, stop and status

```bash
sudo systemctl start syncd syncd-console
sudo systemctl stop syncd-console syncd
sudo systemctl restart syncd               # after editing engine.env or engine.yaml by hand
sudo systemctl restart syncd-console       # after editing console.env
systemctl status syncd syncd-console
sudo syncdctl status                       # releases, services, health, store format, certificates, backups
sudo syncd status                          # what the engine is doing
```

- **Restarts.** Both units restart on failure after 5 seconds (`Restart=on-failure`). systemd's
  default start limit (5 starts in 10 seconds) still stops a real crash loop. `syncdctl` clears
  that limit (`systemctl reset-failed`) before its own starts and restarts. After a crash loop,
  clear it yourself with `systemctl reset-failed syncd` once the cause is fixed.
- **Stopping the engine is safe.** It stops between pages, within seconds; systemd waits up to 90
  seconds (`TimeoutStopSec=90`). A full sync in progress resumes where it stopped at the next
  start, and each source carries on from its last committed checkpoint. A backup under way
  finishes first. The active engine of a pair gives its lease up, so the passive one takes over
  at once.
- **What needs a restart.** Changes to `console.env` and `engine.env`, and hand edits to
  `engine.yaml`. A configuration published from the console needs none: the engine reloads with it
  (see [Admin guide](admin-guide.md)). `engine.cache_mb` takes effect only when syncd restarts.

`syncdctl status` prints:

```text
release:   0.5.0
previous:  0.4.1
engine:    active, control API answers
store:     format 1 (syncd 0.5.0 reads up to 1)
console:   active, http://127.0.0.1:8080 answers
certificates: valid until Nov  4 10:15:00 2036 GMT (ca.crt expires first)
users:     https://syncd.example.com
backups:
  /var/lib/syncd/backups/20261110T101500Z-upgrade-0.4.1-to-0.5.0
```

The `backups:` it lists are the host-local ones. Off-host and scheduled backups show in
`syncd status` (`backups:` line, below). `syncdctl status` says nothing of the license or of
control API access: `sudo syncdctl license status` and `sudo syncdctl api-access status` do (see
[syncdctl commands](#syncdctl-commands)).

## Health checks

| Check | Answers |
|---|---|
| `curl -s http://127.0.0.1:8080/healthz` | `ok` while the console runs. No sign-in needed. It does not check the database or the engine |
| `sudo syncd status` | The engine answers on its control API, with the state of each source and publication. It always answers, even while the engine is busy (in strict mode, to the control socket and to a certificate listed under any tier of `api.access`) |
| `sudo syncdctl status` | Both of the above, as `syncdctl` checks them during installs and upgrades |
| `GET /metrics` | The engine answers scrapes, when `api.metrics_listen` is set. `up{job="syncd"}` in Prometheus |
| The console's engine page | The same status as `syncd status`, live |

`certificates:` names the control API certificate that expires first, and from 60 days before
says `EXPIRES WITHIN 60 DAYS, renew with syncdctl renew-certs` (see [Certificates](#certificates)).

`syncd status` shows, for each source, its mode, state (`starting`, `reading`, `streaming`, `idle`,
`RETRYING`, `FAILED`), the changes received, its checkpoint, restarts, the next try and the last
error (and, for a REST source, `throttled=`; for a PostgreSQL stream whose table was truncated,
`warning: truncated at the source ...; read in full, N rows remain`). For each publication it
shows its target and dead letters, the last drift check and the last unmanaged-entries pass. Then
any unreachable target, deletes held back by a threshold, what the engine is busy with, and
running totals. It also shows:

| Line | What it says |
|---|---|
| `engine 0.5.0  up 3d2h  fips off`, `configuration version ...` | The version, uptime, FIPS mode and the configuration running |
| `configuration applied by CN=syncd console at 10.0.0.5:41234, 2h ago (signed by key ..., sha256 ...)`, or `configuration from the file on this host, not applied through the control API (...)` | Who applied the running configuration, when, and whether the console signed it (new in 0.5.0; see [Control API access](#control-api-access)) |
| `control API access: strict (...)`, or `control API access: COMPATIBLE: every client certificate from the CA may apply configurations and approve changes; ...` | Who may use the control API (new in 0.5.0). `configurations: applied only when the console signed them` follows with `engine.require_signed_config` |
| `license: GRACE (L-0001 for ..., 5000 managed identities, expires ...; packs ...)`, `license: N managed identities on this engine ...`, `license: installation ...` | The license's state, the managed identities counted and the installation id, then any message (new in 0.5.0; see [Licensing](licensing.md)). Absent on the passive engine of a pair |
| `environment prod` | The environment the configuration was rendered for (see [Configuration as code](config-as-code.md)) |
| `package gov-naming 1.2.0 (...)` | The rule packages in use |
| `ha: pair idm, this engine idm1 is ACTIVE (lease postgres, held until in 12s)`, `ha: last snapshot taken 20s ago (1.5 MiB; 64.0 KiB new, 9 of 12 files held already), every 1m0s`, `ha: the passive engine follows the password queue` | An [active/passive pair](#high-availability): this engine's role, the lease, the snapshots and (from 0.5.0) whether the passive engine follows the queued password changes |
| `process: memory ...  cpu ...` | The engine process: memory now, its peak and the limit, CPU time, threads and open files |
| `passwords: N delivered, N failed, N waiting, N dropped (report mode); initial passwords: N set, N refused; last failure: ...` | Password deliveries (see [Password synchronisation](password-sync.md)). `dropped` (new in 0.4.1, shown when not 0): changes not delivered because the publication is in report mode. Never a password |
| `password keys: sealed with the passphrase`, or `password keys: stored in the clear (file mode only; set SYNCD_PASSWORD_KEY_PASSPHRASE_FILE to seal them)` | How the password key and the agents' CA key are stored (see [The password keys](#the-password-keys)). New in 0.4.1 |
| `password key: <id>, made 3d0h ago; still opening for <id> (replaced ..., N queued; ...)`, `password agent CA: expires in ...`, `password agents: the next certificate to expire is dc01's, in ...` | The current password key and any it replaced, the agent CA, and the first agent certificate to expire (new in 0.5.0) |
| `password agents: WARNING: dc01, dc02 bound to no source ...`, `passwords: WARNING: sent in the CLEAR to dir_b (...)` | Agents enrolled before 0.5.0 that are not bound yet, and directories passwords go to over plain LDAP because `passwords.allow_cleartext` allows it (new in 0.5.0) |
| `timers: N pending, next due in 4h` | Persisted timers: effective dates, leaver stages, change windows, canaries, group expiry |
| `history: N entries, oldest 30d ago, pruned 12m ago, N pruned since start` | Entity history kept (see [Entity history](#entity-history)) |
| `backups: to s3://... every 1d0h, last syncd-idm1-... 2h3m ago (4.0 MiB), next in 21h57m` | Scheduled backups, with `FAILED <ago>: <why>` when the last attempt failed |
| `quality people mail-format: 12 entities fail (error)` | Failing data-quality checks |
| `view people: 3 quarantined (exports held back by a failing rule)` | Quarantined entities, followed by up to 20 of them with the rule and its error (see [Quarantined entities](#quarantined-entities)) |
| `view people: unmatched: N records waiting to join (...)` | Records that joined no entity, and how many are older than the view's alert age |
| `leavers: N to disable, N to strip of groups, N to move, N to delete` | Under a publication: leavers by the stage ahead of them |
| `unmanaged: N of M entries (K ignored); privileged: N members of N groups no rule accounts for (K ignored); checked 3h ago` | Under a publication: the last [unmanaged-entries pass](#unmanaged-entries), with `(stopped: ...)` when it could not finish. New in 0.5.0 |
| `held: N changes (...): N window, N modifications; oldest ...` | Under a publication: [held changes](#held-changes) by reason, when the change window opens, a canary soaking, and `release with syncd approve "publication <name>"` when an approval is needed |
| `report mode: N adds, N modifies, N moves, N deletes would be sent` | Under a publication in report mode |

## Logs

Both services log to the journal:

```bash
journalctl -u syncd -u syncd-console -f
journalctl -u syncd -e                         # the engine's log, newest last
journalctl -u syncd -g "did not start" -A 3    # why a published configuration rolled back
journalctl -k -g oom                           # the kernel stopped syncd for memory
journalctl -u syncd-console -o cat | grep '"schema":"ens-audit/1"'   # the console's audit events
```

The level is `info`. To change it, set `RUST_LOG` in the unit (`systemctl edit syncd`, then
`Environment=RUST_LOG=debug` under `[Service]`) and restart. The console leaves out PostgreSQL's
notices below warnings.

**Audit events on standard output.** From 0.4.0 the console writes every audit event as one raw
JSON line (`ens-audit/1`) on standard output, so to the journal, or the pod log on Kubernetes. The
console's own log lines never start with `{`: filter on `"schema":"ens-audit/1"` to pick the
events out for your log collector or SIEM. `ENS_AUDIT_STDOUT=false` in `console.env` turns this
off. The trail is also in the console's database, and can go to an audit file, Splunk or syslog
(`ENS_AUDIT_*`); see [Security](security.md) and [Audit logging](../audit-logging.md).

Useful engine log messages:

| Message | Meaning |
|---|---|
| `store opened` (with `format`) | The engine opened its store at startup |
| `migrating the store`, `store migrated` | An upgrade migrates the store to a newer format |
| `metrics listening` | The metrics listener is up |
| `control request` (with `rpc` and `caller`) | A command through the control API, and who sent it |
| `refused a control request` (with `rpc`, `caller`, `needed`, `granted`), `refused a client certificate that api.access does not list`, `refused a password request: the caller is not listed under api.access.passwords` | Strict control API access refused a call (new in 0.5.0; see [Control API access](#control-api-access)) |
| `revealing sensitive values` / `refused a request to reveal sensitive values` | A caller asked for sensitive attribute values |
| `source failed; restarting it from its checkpoint` | A source failed and will be retried |
| `export target is unreachable; failed exports wait in the dead-letter queue` | A target directory went away |
| `connected to the directory again` | It came back |
| `exceeds the deletion threshold` | A deletion threshold holds deletes back |
| `a canary went first; the rest of the change waits for it to soak`, `the canary passed; releasing the rest of the change`, `the canary failed: ...` | Canary publishing (see [Held changes](#held-changes)) |
| `in report mode: changes are planned and recorded, nothing is written to ...` | A publication in report mode |
| `approved: held changes are released, ...` | `syncd approve` released a publication's held changes |
| `the new configuration did not start; restoring the previous one` | A published configuration rolled back |
| `engine.cache_mb takes effect when syncd restarts` | A published configuration changed `cache_mb` |
| `pruned entity history` | The hourly history prune ran |
| `backup written`, `backup failed` | A scheduled backup |
| `this engine took the lease; taking over`, `reconciling the targets after taking the pair over` | This engine of a pair became the active one |
| `the pair's lease is lost: stopping the sources and the exports; this engine is now the passive one` | This engine could not renew the lease and stepped down |
| `not writing: this engine no longer holds the pair's lease` | The fence stopped an export batch |
| `store snapshot received from the active engine`, `pulling a store snapshot failed`, `taking a store snapshot failed` | Snapshots of a pair |
| `password delivered`, `password change not delivered` | Password deliveries, by account and DN, never the password |
| `the target refused the initial password; the entry is there without it (tried again with a new one, a limited number of times)`, then `the target refused the initial password as often as it is tried; the entry stays without one, held in the dead-letter queue (syncd retry tries again)` | An initial password refused (from 0.5.0 tried three times, then a dead letter; see [Troubleshooting](troubleshooting.md#passwords)) |
| `password agents enrolled before 0.5.0 are bound to no source and may submit changes for any: bind each with ...` | A warning at start until every agent is bound (new in 0.5.0) |
| `the password keys are stored in the clear, protected by file mode only: ...` | A warning at start: no passphrase seals the password keys (see [The password keys](#the-password-keys)). New in 0.4.1 |
| `the password key was stored in the clear and is now sealed with the passphrase`, `the password agent CA key was stored in the clear ...` | The first start with a passphrase sealed the key files in place. New in 0.4.1 |
| `an entry moved or renamed in the target was put back where the publication places it`, `... was found by its id` | Someone moved or renamed a managed entry in the target; the engine found it by the target's id and put it back |
| `rename blocked by an entry this batch renames away; parked until it has moved` | Two entries swap names, or a chain of renames, in one batch |
| `the entry's RDN is taken where it is moved aside; it was given one of its own` | A leaver moved aside into a container where its RDN was taken |
| `container renamed in the source; its entries were read again` | An LDAP source's container was renamed (`container_check_secs`) |
| `removed empty container` / `could not remove an empty container` | `remove_empty_containers` removed a container syncd created |
| `the publication lacked tables the object follows; added them` | A PostgreSQL source completed its publication with `ALTER PUBLICATION ... ADD TABLE` |
| `the publication does not publish truncates: ...` | A PostgreSQL publication made by hand without `truncate` in its `publish` list: a `TRUNCATE` would not reach syncd (new in 0.5.0) |
| `a leaver's disabled entry is taken over by the entity added at its DN, and enabled` | New in 0.5.0: see [Staged leaver policy](change-safety.md#staged-leaver-policy) |
| `computed group review is due: review it (syncd group review <publication> <group>)` | A group's `review_every` fell due (new in 0.5.0; again at a start while it stays due) |
| `password queue: what the old active engine wrote after the snapshot was applied` | After a takeover, the new active engine had the password changes queued since the last snapshot (new in 0.5.0) |
| `configuration file created from the initial one` | A container's first start copied `--initial-config` to the data volume |

Useful console log messages:

| Message | Meaning |
|---|---|
| `console listening` (with `https`) | The console is up, over HTTPS when `https=true` |
| `created a new master key for stored secrets; back it up` | The first start created `master.key` |
| `the master key does not open the stored secrets: engines registered with mTLS must be registered again, webhooks need their URL and secret again, secret environment values must be entered again, and the configuration signing key rotated` | `CONSOLE_NEW_MASTER_KEY=true` started the console with a key that does not open the stored secrets (0.4.0 named the engines only; 0.5.0 adds the signing key) |
| `audit log head` (with `id`, `at`, `hash`) | The newest audit event, logged at start and every hour when it moved. Keep these lines outside the database (the journal, a SIEM): they let **Verify chain** find events removed from the end of the trail |
| `audit log upgraded to ...: the earlier entries keep their chain` | The first start on 0.4.0 continued the trail in the ENS format |
| `audit delivery failed; retrying`, `audit delivery has been failing for N s`, `audit delivery recovered` | An audit output (Splunk, syslog, the file) is down, and is back |
| `webhook pass failed`, `webhooks are off (CONSOLE_WEBHOOKS_ENABLED=false)` | Webhook deliveries (see [Webhooks](webhooks.md)) |
| `mail not sent; will retry`, `mail given up after N tries`, `e-mail is off (CONSOLE_SMTP_HOST is not set); ...` | The mail outbox of scheduled reports |
| `config.git-synced`, `config.git-failed`, `configuration pushed to Git` | Git sync (see [Configuration as code](config-as-code.md)) |
| `TLS certificate loaded again` / `the changed TLS certificate does not load; keeping the previous one` | The console's own HTTPS certificate changed on disk |
| `the console listens on ... over plain HTTP: browsers on other machines will not keep its session cookie` | It listens beyond loopback over plain HTTP, without an HTTPS `CONSOLE_PUBLIC_URL` |
| `development mode: do not use this console in production` | `SYNC_DEV_MODE=true` |
| `single sign-on could not start` | The identity provider could not be reached or is misconfigured |

## The syncd command line

`syncd` on the host works without flags. Run it as root, or as a member of the `syncd` group.

- **Configuration.** Without `-c` (or `SYNCD_CONFIG`), `syncd` uses `./syncd.yaml` if there is
  one, else `/etc/syncd/engine/engine.yaml`.
- **Secrets.** With the packaged configuration, it reads `/etc/syncd/engine.env` as the service
  does, so `test-connection`, `discover` and `backup` find the passwords and S3 keys the
  configuration refers to. Variables already set win, and `--env-file` names another file.
  `SYNCD_FIPS_MODE=true` in that file turns on `--fips`.
- **Control API.** Commands that talk to the running engine use the control socket when the
  configuration sets `api.control_socket` and the socket exists. Otherwise they use mutual TLS
  with the command line's client certificate, `/etc/syncd/pki/cli.crt` and `cli.key`. Install and
  upgrade issue it, `syncdctl renew-certs` renews it, and `sudo syncdctl cli-cert` issues it on a
  host that lacks one. What each may do depends on [control API access](#control-api-access): on
  a host installed with 0.5.0 they read and operate, and `syncd apply-config` is refused.
- **A pair.** Commands sent to the passive engine of a pair are refused with the active
  engine's name, except `status` and a new configuration. Run them on the active one.

### Commands

Running the engine:

| Command | What it does | Needs the running engine |
|---|---|---|
| `syncd status` | The running engine's status (see [Health checks](#health-checks)) | Yes |
| `syncd quarantine` | Lists the entities whose exports a failing rule (`on_error: quarantine`) or a data-quality check holds back, one per line: view, entity id, `since=` (microseconds since the epoch), the attribute or check and its error. `no quarantined entities` when there are none. Reads the store directly, read-only | No (works while it runs) |
| `syncd dlq` | Lists failed exports waiting to be retried: publication, entity id, attempts, last failure, DN, error. Reads the store directly, read-only | No (works while it runs) |
| `syncd retry [--publication P] [--entity ID]` | Exports dead-lettered entities again now: all, one publication's, or one entity (as `syncd dlq` lists it) | Yes |
| `syncd discard --publication P --entity ID` | Removes a dead letter without retrying it | Yes |
| `syncd reconcile [--publication P]` | Reconciles a publication (or all) with its target now | Yes |
| `syncd full-sync` | Recomputes every entity from its sources (after a rule change), then exports | Yes |
| `syncd check-drift [--publication P]` | Reads a publication's entries (or every checked publication's) back from the target now, and acts on drift by its `drift` policy | Yes |
| `syncd unmanaged [--publication P] [--last]` | Looks in a publication's target (or every one's) for [unmanaged entries](#unmanaged-entries) and for members of privileged groups no rule accounts for, and prints them; `--last` prints the last pass's result without reading the target. Writes nothing to the target. New in 0.5.0 | Yes |
| `syncd adopt --publication P --dn DN --entity ID` | Makes an entry already in the target the entry of an entity that has none in the publication yet: moved to the entity's DN when it is elsewhere, given the exported attributes, and managed from then on. LDAP and Active Directory targets. New in 0.5.0 | Yes |
| `syncd held PUBLICATION [--kind K] [--reason R] [--limit N]` | Lists a publication's [held changes](#held-changes), one per line: entity id, reason, kinds of change, `since=`, DN and a summary without values. `--kind`: `add`, `modify`, `move`, `delete`, `disable`, `strip_groups`, `password`. `--reason`: `window`, `overdue`, `modifications`, `additions`, `deletions`, `canary`, `canary_failed`, `report`, `expired`. 100 by default. New in 0.4.0 | Yes |
| `syncd approve "<scope>"` | Releases what waits for an approval, with the scope exactly as `syncd status` shows it. `syncd approve "publication people_out"` plans every held change of the publication again from the entity's current state and applies it once: changes held by a threshold, an overdue window or a failed canary, and deletes over the deletion threshold. Before 0.4.0 it let deletes through only. See [Approving](change-safety.md#approving) | Yes |
| `syncd report [--publication P] [--format text\|csv\|json] [--out FILE]` | The reconciliation report: per publication, a summary (entities, exported, the last drift check, held changes by reason, dead letters, leavers, would-dos, groups), then one row per entry with a difference. Reads nothing from the targets. New in 0.4.0. See [syncd report](reports-and-data-quality.md#syncd-report) | Yes |
| `syncd lineage VIEW ENTITY [--format text\|json] [--reveal] [--out FILE]` | Everything the engine knows about one entity: each attribute with the source record and rule that produced it, its source records and joins, what each publication exported for it over time, its password events (times and outcomes) and its history. The entity id is 32 hex characters, as `syncd report` and the console's explorer show it. Sensitive values only with `--reveal`. New in 0.4.0. See [Lineage export](reports-and-data-quality.md#lineage-export) | Yes |
| `syncd history prune --now` | Prunes entity history now, as the engine does every hour (see [Entity history](#entity-history)). New in 0.4.0 | Yes |
| `syncd quality [VIEW] [--check C] [--list] [--limit N]` | Each data-quality check with how many entities fail it; with `--list`, the failing entities (ids and messages, never values). Exits 1 while a check of severity `error` fails. New in 0.4.0. See [Data-quality checks](reports-and-data-quality.md#data-quality-checks) | Yes |
| `syncd group review PUBLICATION GROUP` | Reviews a computed group: its expiry and review clocks start again now, and an expired group comes back. New in 0.4.0. See [Group lifecycle](change-safety.md#group-lifecycle) | Yes |
| `syncd correlation suggest --view V [--source S] [--above X] [--limit N] [--json]` | Scored suggestions for a view's records that joined no entity. New in 0.4.0. See [Correlation assistance](reports-and-data-quality.md#correlation-assistance) | Yes |
| `syncd correlation accept --view V [--source S] [--above X \| --from FILE] [--limit N] [--yes]` | Joins the records whose best suggestion scores at least `--above` (the view's `correlation.accept_at` by default), or the decisions in a file `suggest --json` wrote. A dry run unless `--yes` | Yes |
| `syncd backup --to DIR\|s3://... [--keep N] [...]` | An [off-host backup](#off-host-backups) of the running engine, without stopping it, with the password keys as they are on disk (sealed or not). New in 0.4.0 | Yes |
| `syncd restore --from DIR\|s3://... [--name NAME] [--replace]` | Restores a backup into the data directory, with the engine stopped (see [Restore](#restoring-an-off-host-backup)). New in 0.4.0 | No: refused while it runs |
| `syncd support-bundle [--out FILE\|-]` | Writes the engine's [support bundle](#support-bundle); `--out -` streams it to standard output (from 0.4.1). New in 0.4.0 | Yes (collects less without it) |
| `syncd password set`, `password change`, `password queue [--limit N]`, `password agent add NAME --source C\|bind NAME --source C\|list\|revoke\|enroll\|renew` | Submit a password change, change a person's own password, list the changes waiting (who submitted each, never a password; new in 0.5.0), and manage the capture agents. From 0.5.0 a new agent needs `--source`, and `bind` binds an agent enrolled before 0.5.0. See [Password synchronisation](password-sync.md) | Yes (`agent enroll` and `agent renew --dir DIR` run on the agent's host) |
| `syncd password rekey --passphrase-file FILE`, `rekey --forget-previous`; `password rotate-key [--retire [--key ID] [--force]]`; `password rotate-agent-ca [--retire]` | Change the password keys' passphrase, rotate the password key, rotate the agents' CA. New in 0.5.0. See [The password keys](#the-password-keys) | `rekey`: no (it works with the engine running or stopped); the rotations: yes |
| `syncd secrets wrap --kms URI [--in FILE] --out FILE` | Wraps a passphrase with a KMS key (`awskms:...`, `azurekv:...`, `vaulttransit:<mount>/<key>`), for `SYNCD_PASSWORD_KEY_PASSPHRASE_KMS`. New in 0.5.0 | No |
| `syncd license show`, `syncd license verify FILE [--installation ID]` | The license as the running engine sees it (state, usage, installation id); a license file checked with this build's keys, without the engine. New in 0.5.0. See [Licensing](licensing.md) | `show`: yes; `verify`: no |
| `syncd version` | The version, the build's FIPS support and the license keys it accepts. New in 0.5.0 | No |
| `syncd store-format [--supported]` | Prints the store's format (`none` without a store); `--supported`, the newest format this `syncd` reads. Works while the engine runs | No |
| `syncd run [--once]` | Runs the engine. This is what the service runs; `--once` does one full pass (read every source, export, reconcile) and exits | Not with the service running: it holds the store |

Configuring and testing (mostly for designers):

| Command | What it does | Needs the running engine |
|---|---|---|
| `syncd validate` | Checks the configuration, compiles every expression, and runs the rule packages' tests, against a throwaway store | No |
| `syncd test [--golden DIR] [--update] [--junit FILE]` | Runs the computed attributes' test cases, the rule packages' tests and the golden-file tests of whole publications (`*.yaml` in `tests/golden` next to the configuration by default, and from 0.4.1 in its subdirectories one level down). The one command for CI. See [Configuration as code](config-as-code.md) | No |
| `syncd config diff A [B] [--running] [--format text\|json\|markdown]` | What changed between two configurations, by meaning, secrets redacted. Exits 0 when they are the same, 1 when they differ, 2 on an error. `--running` compares the running engine's configuration (before) with the file. New in 0.4.0. See [Configuration as code](config-as-code.md) | Only with `--running` |
| `syncd config render FILE --env ENV.yaml` | Prints a configuration rendered for an environment. New in 0.4.0 | No |
| `syncd config promote ENGINE/vN --to ENV --console URL --token-file FILE [--ticket T] [--note N] [--ca FILE]` | Publishes a version to every engine of another environment, through the console's API with an API token that has `configs:publish`, and from 0.4.1 prints each engine's diff summary (`changes: ...`). The golden tests in Git gate it, as any publish. New in 0.4.0. See [Configuration as code](config-as-code.md) | No (needs the console) |
| `syncd apply-config FILE [--version LABEL]` | Sends a new configuration to the engine, which validates it, saves it over its file and reloads, then runs a full sync. The label is shown by `syncd status` (default: the time). Refused in strict [control API access](#control-api-access) (`ApplyConfig needs the configure tier ...`) unless the certificate is listed under `configure`, and with `engine.require_signed_config` whoever sends it: configurations then go through the console | Yes |
| `syncd simulate FILE [--samples N] [--reveal]` | What applying a configuration would send each target, without applying it: the engine dry-runs it on a copy of its state. Lists `N` operations per publication (default 20) | Yes |
| `syncd preview FILE [--view V] [--samples N] [--random] [--live] [--reveal]` | Runs a configuration over a sample of source records (default 10, at most 100, of each view's first source) in a scratch store, and shows the entities and what each publication would write. `--live` reads the sources now instead of the engine's copies | Yes |
| `syncd test-connection CONNECTOR` | Connects with a connector (and binds, for a directory) and shows what answered. Writes nothing | No |
| `syncd discover CONNECTOR [CONTAINER] [--object O] [--samples N] [--random] [--reveal]` | Lists what a connector holds (tables, collections, directory entries); with a container (`schema.table`, a collection, a DN), its fields and a few records (default 5, at most 100). `--object` reads a configured object's records as the engine reads them. Writes nothing | No |

`--reveal` shows the values of sensitive attributes. It needs mutual TLS (the default on an
installed host).

A command that changes something waits up to 10 seconds while the engine is busy with a long
operation (a full sync, a reconciliation, a reload), then is refused: `the engine is busy
(<activity>); try again when it is done`. Scripts should retry on that message.

### Global options

| Option | Variable | Meaning |
|---|---|---|
| `-c`, `--config FILE` | `SYNCD_CONFIG` | The configuration file. Default `./syncd.yaml`, else `/etc/syncd/engine/engine.yaml` |
| `--env-file FILE` | `SYNCD_ENV_FILE` | Environment file with the secrets the configuration refers to. Default `/etc/syncd/engine.env` with the packaged configuration |
| `--env FILE` | `SYNCD_ENVIRONMENT` | An environment file (`environment: prod`, `values: {...}`) that fills the configuration's `{{ env.<name> }}` placeholders: the engine renders its configuration with it at every start, and `validate`, `simulate`, `apply-config` and `config diff` render what they read. New in 0.4.0 |
| `--data-dir DIR` | `SYNCD_DATA_DIR` | Overrides `engine.data_dir`. The service passes `--data-dir /var/lib/syncd/engine`. `syncd restore` restores into it |
| `--initial-config FILE` | `SYNCD_INITIAL_CONFIG` | When the configuration file does not exist yet, creates it as a copy of this one; an existing file is never replaced. The container image uses it to start `/var/lib/syncd/engine.yaml` on the data volume from the image's `/etc/syncd/engine.yaml` |
| `--fips` | `SYNCD_FIPS_MODE` | Uses the FIPS 140-3 crypto provider. Needs a FIPS build |
| `--fips-allow-wasm-functions` | `SYNCD_FIPS_ALLOW_WASM_FUNCTIONS` | In FIPS mode, loads rule packages' custom functions in WebAssembly anyway; they are refused there by default. New in 0.5.0 |
| `--version`, `--help` | | |

`SYNCD_HA_NAME`, `SYNCD_HA_LEASE_URL`, `SYNCD_HA_LEASE_PATH`, `SYNCD_HA_LEASE_TTL_SECS`,
`SYNCD_HA_SNAPSHOT_EVERY_SECS`, `SYNCD_HA_MEMBER` and `SYNCD_HA_ADVERTISE` set up a pair from the
environment (see [High availability](#high-availability)); from 0.4.1 the last two override the
file's `member` and `advertise` when set. 0.5.0 adds `SYNCD_HA_LEASE_KIND`, `SYNCD_HA_LEASE_NAME`
and `SYNCD_HA_LEASE_NAMESPACE` (a Kubernetes Lease) and `SYNCD_HA_PASSWORD_REPLICATION_WAIT_MS`
(2000). `syncdctl ha init` and `ha join` write these into `/etc/syncd/engine.env`.
`SYNCD_PASSWORD_KEY_PASSPHRASE_FILE` (or `SYNCD_PASSWORD_KEY_PASSPHRASE`) seals the password keys,
and from 0.5.0 `SYNCD_PASSWORD_KEY_PASSPHRASE_KMS` says the file holds the passphrase wrapped by a
KMS key (see [The password keys](#the-password-keys)). The engine's environment also takes
`VAULT_ADDR`, `VAULT_CACERT`, `SYNCD_VAULT_*`, and the `AWS_*` and `AZURE_*` variables for
secrets kept in a vault (see [Configuration](configuration.md)).

### Control API options

For every command that needs the running engine (`status`, `reconcile`, `full-sync`, `approve`,
`held`, `report`, `lineage`, `history prune`, `quality`, `group review`, `correlation`,
`check-drift`, `unmanaged`, `adopt`, `retry`, `discard`, `apply-config`, `simulate`, `preview`,
`backup`, `support-bundle`, `license show`, `password set`, `password change`, `password queue`,
`password rotate-key`, `password rotate-agent-ca`, `password agent add`, `bind`, `list` and
`revoke`, and `config diff --running`):

| Option | Variable | Meaning |
|---|---|---|
| `--api ADDR` | `SYNCD_API` | The API's address: `host:port`, or `unix:/path` for a control socket. Default `api.control_socket` when it exists, else `api.listen` (an unspecified address becomes loopback) |
| `--api-ca FILE` | `SYNCD_API_CA` | The CA that signed the API's certificate. Default `ca.crt` next to `api.tls.client_ca_file` |
| `--api-cert FILE` | `SYNCD_API_CERT` | The client certificate. Default `cli.crt`, else `client.crt`, next to `api.tls.client_ca_file` |
| `--api-key FILE` | `SYNCD_API_KEY` | The client certificate's private key file (a PEM file, not a token). Goes with `--api-cert` |

### The control socket

`api.control_socket` is a Unix socket, mode 0600, that serves the whole control API to the
engine's own user and root, without certificates. The installed configuration has it commented
out, and uses mutual TLS instead. To use it on a host, uncomment it in the `api` section of
`/etc/syncd/engine/engine.yaml` and restart the engine:

```yaml
api:
  control_socket: /run/syncd/control.sock
```

The engine's unit creates `/run/syncd` (`RuntimeDirectory=syncd`, mode 0750) and removes it when
the engine stops. `syncd` commands run as root then use the socket. A host installed before 0.3.0
keeps its `engine.yaml`, without the commented line: add it. The container image sets it, for
`kubectl exec`.

In strict [control API access](#control-api-access) the socket reads and operates and applies no
configuration; it may still set passwords and rotate the password keys, since it is the engine's
own user.

### Control API access

New in 0.5.0. Every call of the control API has a tier, each including the one before:

| Tier | What it allows |
|---|---|
| `read` | The status, the lists (dead letters, held changes, quarantine, data quality, unjoined records, queued passwords, agents), the running configuration, simulations, the reconciliation report |
| `operate` | Runs (`reconcile`, `full-sync`, `check-drift`, `unmanaged`), `approve`, `retry`, `discard`, joins, `adopt`, group reviews, a person's own password change, revoking an agent, backups and HA snapshots, connector checks and discovery, previews, and `--reveal` |
| `operate` and `passwords` | Setting passwords and deciding who else can: `syncd password set`, `password agent add` and `bind`, `password rotate-key` and `rotate-agent-ca`, and the passive engine of a pair forwarding what agents sent it |
| `configure` | Applying a configuration (`ApplyConfig`: the console's publish, `syncd apply-config`) and installing a license on an engine |

`api.access` in the host's `engine.yaml` says which client certificate (by common name, or
`uri:` and a URI subject alternative name, or `*`) has which tier:

```yaml
api:
  access:
    mode: strict
    configure: [syncd console]         # console-client.crt
    operate: [syncd command line]      # cli.crt, which the syncd group can read
    read: []                           # a monitoring system's certificate, say
    passwords: []                      # who may set passwords, besides the control socket
```

- **A host installed with 0.5.0 is strict**, and so is the container image's initial
  configuration (with `syncd engine`, the chart's engine certificate, under `operate` and
  `passwords`). `sudo syncd apply-config` is refused there: configurations go through the console,
  with its approvals, tests, ticket and pre-publish hook.
- **A host upgraded from 0.4.x stays compatible** (no `access` section: every certificate of the
  CA may do everything) and says so at every start, in `syncd status` (`control API access:
  COMPATIBLE: ...`) and on the console's engine page. Opt in with
  `sudo syncdctl api-access strict` (`--dry-run` first), on both hosts of a pair; it validates the
  file, restarts the engine, checks the status and puts the previous file back if anything fails,
  keeping a copy in `/etc/syncd/engine/before-api-access-<time>/`. Then import the running
  configuration in the console, so that the next publish carries the same `api` section. The
  command does not edit an `api` section written on one line, and there is no command back to
  compatible: restore the saved copy.
- **Signed configurations.** `sudo syncdctl api-access signing-key --from-console` installs the
  console's public key as `/etc/syncd/pki/console-signing.pub` and names it in
  `engine.config_signing_keys`; with `--require` it also sets `engine.require_signed_config`, and
  the engine then applies only what that console signed for it. With the console on another
  host, write its key with `syncd-console admin config-signing-key --out FILE` there and pass the
  file instead. `syncd status` shows `signed by key <id>` for what runs.
- **A configuration the console did not publish** (`syncd apply-config` in compatible mode, or the
  file edited on the host) is shown in `syncd status`, and the console records
  `config.unpublished-running` in its audit trail, raises the webhook event of that name, and
  warns on the engine's page.
- `sudo syncdctl api-access status` prints the engine's lines on access and on who applied the
  running configuration. Refusals are logged as `refused a control request`.

The engine refuses a published configuration that changes `api`, `engine.require_signed_config`
or `engine.config_signing_keys`: those are changed on the host. See [Security](security.md) for
the reasoning, and [Troubleshooting](troubleshooting.md#the-command-line) for the refusals.

## syncdctl commands

`syncdctl` installs, upgrades, backs up and rolls back syncd. Run it as root.

| Command | Options | What it does |
|---|---|---|
| `install` | `--local-database` or `--database-url URL`; `--listen ADDR`, `--fips`, `--no-start` | First installation ([Installation](install.md#syncdctl-install-options)) |
| `upgrade RELEASE` | `--yes`, `--no-rollback`, `--skip-db-backup`, `--reinstall` | Upgrades a bundle install to a `.tar.gz` or unpacked release ([Upgrade](install.md#upgrade)) |
| `rollback` | `--keep-data`, `--yes` | Back to the previous release, restoring the backup taken before the upgrade ([Rollback](install.md#rollback)) |
| `backup` | `--skip-db-backup`, `--leave-stopped` | A [host-local backup](#host-local-backups): stops syncd briefly to take a consistent copy; prints its directory. `--leave-stopped` leaves the services stopped afterwards |
| `backup --to DIR\|s3://bucket/prefix` | `--keep N`, `--skip-db-backup`; from 0.4.1 `--sse`, `--endpoint`, `--region`, `--s3-ca` | An [off-host backup](#off-host-backups) of the running engine, with the console's database, `/etc/syncd` and (from 0.4.1) the console's master key beside it. Nothing is stopped. From 0.5.0 a password-key passphrase file inside `/etc/syncd` is left out, with a warning. New in 0.4.0 |
| `restore DIR` | `--yes` | Restores a host-local backup: engine store, console files and database, `/etc/syncd` |
| `restore --from DIR\|s3://...` | `--name syncd-<host>-<time>`, `--yes`; from 0.4.1 `--sse`, `--endpoint`, `--region`, `--s3-ca` | Restores an off-host backup, the newest or the named one ([Restoring an off-host backup](#restoring-an-off-host-backup)). New in 0.4.0 |
| `support-bundle [FILE]` | | Writes a [support bundle](#support-bundle). New in 0.4.0 |
| `status` | | Releases, services, health, store format, when the control API's certificates expire, public URL, host-local backups |
| `renew-certs` | `--new-ca`, `--yes` | Renews the control API's certificates, restarts the engine with them and gives the console its new credentials (see [Certificates](#certificates)) |
| `cli-cert` | | Issues the command line's client certificate, if it is missing |
| `api-access strict` | `--dry-run`, `--yes` | Opts a host upgraded from 0.4.x in to strict [control API access](#control-api-access). New in 0.5.0 |
| `api-access signing-key FILE\|--from-console` | `--add`, `--require`, `--dry-run`, `--yes` | Installs the console's configuration signing key; `--require` makes the engine apply only signed configurations; `--add` keeps the keys already there (a rotation). New in 0.5.0 |
| `api-access status` | | The engine's lines on access and on who applied the running configuration. New in 0.5.0 |
| `ha init` | `--name PAIR`, `--lease-url URL` or `--lease-path FILE`, `--advertise HOST[:PORT]`, `--member NAME`, `--ttl SECS`, `--snapshot-every SECS`, `--join-file FILE`, `--dry-run`, `--yes` | Makes this host the first engine of an [active/passive pair](#high-availability) and writes a join file for the second. New in 0.5.0 |
| `ha join FILE` | `--advertise`, `--member`, `--dry-run`, `--yes` | On the second host, with that file. New in 0.5.0 |
| `ha register NAME HOST:PORT` | `--update` | Registers the pair's other engine in this host's console. New in 0.5.0 |
| `ha status` | | The engine's line on the pair. New in 0.5.0 |
| `license install FILE` | | Checks a license file with the release's keys, installs it as `/etc/syncd/license.lic`, sets `CONSOLE_LICENSE_FILE` and restarts the console. A renewal is the same command. New in 0.5.0 (see [Licensing](licensing.md)) |
| `license status` | | The license's state, the managed identities and the installation id. New in 0.5.0 |
| `uninstall` | `--purge`, `--yes` | Removes syncd ([Uninstall](install.md#uninstall)) |
| `version`, `help` | | |

Environment variables it reads:

| Variable | Default | |
|---|---|---|
| `SYNCD_HEALTH_TIMEOUT` | 120 | Seconds to wait for both services to be healthy |
| `SYNCD_KEEP_BACKUPS` | 3 | Host-local backups kept after an upgrade |
| `SYNCD_PG_SOCKET`, `SYNCD_PG_PORT` | `/var/run/postgresql`, 5432 | Where `--local-database` reaches PostgreSQL |

`upgrade`, `rollback` and `uninstall` refuse to run on a host installed from a package; use the
package manager there. Only one `syncdctl` that changes something runs at a time
(`/run/syncdctl.lock`). From 0.5.0 `syncdctl ha init` and `ha join` set up an HA pair on two hosts
(see [An active/passive pair on hosts](install.md#an-activepassive-pair-on-hosts)); they open no
firewall ports. The join file holds the CA's private key, the lease database's URL and, when the
keys are sealed, the password-key passphrase: copy it as a secret and delete it on both hosts
afterwards. `syncdctl license` was not run on a packaged host before the release.

## Held changes

New in 0.4.0. A publication can hold changes rather than apply them at once: outside its change
window, over a modification, add or deletion threshold, while a canary soaks or after it failed,
in report mode, or for an expired group. A held change is never replayed: when its hold ends, the
entity is planned again from its current state. How each guard rail works is in
[Change safety and lifecycle](change-safety.md#held-changes).

| Where | Shows |
|---|---|
| `syncd status` | Under each publication, `held:` with the counts by reason, when the change window opens, a soaking canary, and the approval needed |
| `syncd held <publication>` | Each held change, with `--kind` and `--reason` filters |
| The console's engine page | The **Held changes** card, with **Approve and apply** |
| `syncd_held_changes{publication,reason}` | The count per reason (0 for a reason with none) |
| `SyncdChangesNeedApproval`, `SyncdCanaryFailed` | Alerts for the reasons that need someone |

The reasons `window` and `canary` clear by themselves, and `report` until the publication is
switched to `mode: apply`. `overdue`, `modifications`, `additions`, `deletions` and
`canary_failed` need an approval: find out why first (a source read that changed a column for
everyone, a rule with wider effect than meant), then `syncd approve "publication <name>"`, or
**Approve and apply** in the console. `expired` clears with `syncd group review`. See
[Troubleshooting](troubleshooting.md#held-changes-and-change-safety).

From 0.5.0 held changes are kept in the store and only counted in memory, and a reconciliation or
reload no longer drops a hold written for an entity after the pass went by it: such a change stays
in `syncd held` until it is applied. Approving is not blocked by the license: see
[What publishing is blocked blocks](licensing.md#what-publishing-is-blocked-blocks).

## Unmanaged entries

New in 0.5.0. An **unmanaged entry** is an entry of the kind a publication manages that its target
holds and no publication exported: an account made by hand in a managed OU, one that predates
syncd, a service account. syncd also lists **members of privileged groups that no rule accounts
for** (`unmanaged.privileged_groups`, and every computed group with `manual_members: report`).
These are not the `orphans` of `syncd status`, which are source records that joined no entity.
syncd reads the target for this and writes nothing there.

```yaml
publications:
  - name: people_to_ad
    unmanaged:
      # scheduled: false                     # keep it out of the drift check's schedule
      bases: ["OU=Staff,DC=corp,DC=example,DC=com"]   # where to look, if not where dn says
      ignore:
        - "*,OU=Service Accounts,DC=corp,DC=example,DC=com"
        - "(description=break-glass*)"
      privileged_groups: ["CN=Domain Admins,CN=Users,DC=corp,DC=example,DC=com"]
```

- **When it runs.** After each scheduled drift check of a publication that has `drift` (a
  read-only paged search; `scheduled: false` turns that off), and on demand with
  `syncd unmanaged [--publication P]` or **Check now** on the console's **Unmanaged entries**
  page. `syncd unmanaged --last` prints the last result.
- **What it lists.** Each entry with its creation time, its last sign-in where the directory keeps
  one (the attribute is named: `lastLogonTimestamp` is a sign-in, `pwdChangedTime` only a password
  change) and whether it is enabled; each unaccounted member with its group (nested groups are
  resolved in Active Directory). The list stops at 200 entries and 200 members (`and N more`);
  the counts are whole. Entries `ignore` matches (a DN pattern with `*`, or an LDAP filter in
  parentheses) are counted as ignored, not listed. A result marked `stopped` could not finish,
  and its counts are of what was read. An Entra ID target skips users synchronised from
  on-premises AD and counts them.
- **Adopting.** For an entry that belongs to a person syncd knows but has not exported yet,
  `syncd adopt --publication P --dn '<dn>' --entity <id>` (or **Adopt** on the page, an operator
  action) moves it where the publication places it, updates it and manages it from then on. The
  report counts adopted entries. For anything else: fix the source or join the record, add the
  entry to `ignore`, or remove it in the directory yourself; syncd never removes what it does not
  manage.
- **Targets.** LDAP and Active Directory; SCIM and Entra ID for users only (no privileged groups;
  `ignore` patterns match the user's name). 389 Directory Server and PingDirectory were
  unit-tested only, and no real Entra tenant or SCIM provider was used.

| Where | Shows |
|---|---|
| `syncd status` | `unmanaged: N of M entries (K ignored); privileged: ...; checked ... ago` under the publication |
| `syncd unmanaged`, `syncd report` | The entries and members; the report as rows of kind `unmanaged` and `unmanaged_privileged` (see [Reports](reports-and-data-quality.md#reconciliation-reports)) |
| The console | The **Unmanaged entries** page, with **Check now** and **Adopt** |
| Metrics | `syncd_target_unmanaged`, `syncd_target_unmanaged_ignored`, `syncd_target_unmanaged_privileged`, `syncd_target_unmanaged_checked_timestamp_seconds`, `syncd_target_unmanaged_failed` (all by `publication`) |
| Alerts | `SyncdUnmanagedEntriesGrew`, `SyncdUnmanagedPrivilegedMembersGrew` |

## Entity history

The engine keeps a history entry per change of each entity, which lineage and the console's
explorer show. It prunes it an hour after it starts and every hour after that:

- entries older than `engine.history.keep_days` (30) go, except the newest
  `engine.history.min_per_entity` (10, new in 0.4.0) of each entity;
- then each entity keeps at most `engine.history.keep_per_entity` (100);
- an entity with an open held change or a dead letter keeps every entry, past both, until it
  clears. Before 0.4.1, `keep_per_entity` applied to those entities too.

`syncd history prune --now` prunes at once. What is kept shows in `syncd status` (`history:`), the
console's engine page, and the `syncd_history_*` metrics. The arithmetic for disk is in
[Entity history](availability-and-sizing.md#entity-history), and the settings in
[History retention](reports-and-data-quality.md#history-retention).

## Backups

Two kinds, from 0.4.0:

| | [Off-host backups](#off-host-backups) | [Host-local backups](#host-local-backups) |
|---|---|---|
| Command | `syncdctl backup --to <dir \| s3://...>`, `syncd backup --to ...`, or on a schedule (`engine.backup`) | `syncdctl backup`; every upgrade takes one |
| The engine | Keeps running: a consistent checkpoint of its store | Stopped while its store is copied |
| Where | A directory (a mounted share) or S3 | `/var/lib/syncd/backups` on the host |
| Holds | The store, the configuration file, the password key and agent CA (sealed or not, as on disk); with `syncdctl`, the console's database, `/etc/syncd` and, from 0.4.1, the console's master key | The store, the console's files with the master key, the console's database, `/etc/syncd` |
| Restore | `syncdctl restore --from ...`, or `syncd restore --from ...` | `syncdctl restore <dir>`, `syncdctl rollback` |

### Off-host backups

```bash
sudo syncdctl backup --to s3://backups/syncd/prod --keep 14 --sse AES256   # or --to /mnt/backups
sudo syncdctl backup --to /mnt/backups/syncd                                # a mounted share
```

Nothing is stopped. The engine takes a consistent checkpoint of its store while it runs, and
streams it with its configuration file, its password key and the password agents' CA as one
`syncd-<host>-<time>.tar.zst`, with a manifest beside it (`syncd-<host>-<time>.manifest.json`:
sizes and SHA-256 per file, the syncd version and the store format). `syncdctl` runs `syncd
backup` as the engine's user, and adds beside it, named after it:

- `<name>.console.dump`: the console's database (`pg_dump --format=custom`), dumped as the
  console's user;
- `<name>.etc.tar.gz`: `/etc/syncd`, with the service settings, the PKI and `engine.env` and its
  secrets. From 0.5.0 a password-key passphrase file inside `/etc/syncd` (and its `.previous`) is
  left out, with a warning, since it would travel with the sealed keys it opens: move it to
  `/etc/syncd-keys/` and back it up apart. A passphrase written into `engine.env` itself
  (`SYNCD_PASSWORD_KEY_PASSPHRASE`) cannot be left out, and the backup warns;
- `<name>.console-master.key`: the console's master key (`/var/lib/syncd/console/master.key`),
  without which the restored database's sealed secrets do not open. New in 0.4.1. When
  `CONSOLE_MASTER_KEY` is set in `console.env` instead, the key is in `/etc/syncd`'s archive, and
  `syncdctl` says so.

It ends by listing what the backup holds. `--keep N` removes the oldest backups there beyond `N`.
`--skip-db-backup` leaves the database out. `--sse`, `--endpoint`, `--region` and `--s3-ca`, as for
`syncd backup` below, are passed on to every command that writes a part of the backup (new in
0.4.1; 0.4.0 refused them). Other options are refused.

- **A directory destination** must be writable by the `syncd` user, which writes the archive:
  `sudo install -d -o syncd -g syncd -m 0700 /mnt/backups/syncd`. `syncdctl` writes the other
  files there as root, mode 0600.
- **Fixed in 0.4.1.** With 0.4.0, the upload of `/etc/syncd` to S3 failed on file permissions, and
  so did the console database's dump with `--local-database`; 0.4.0's backups also lack the master
  key. Take a new off-host backup after upgrading.

!!! warning "A backup is as sensitive as the store"
    The archive holds the entity data, and the password key and the agents' CA key as they are on
    disk: sealed when the engine has a passphrase, otherwise in the clear (see
    [The password keys](#the-password-keys)). `syncdctl`'s copy of `/etc/syncd` holds the engine's
    secrets and the control API's CA key, and the master key beside it opens every secret the
    console stores. In a directory these files are root's, mode 0600; in S3 only the bucket's
    access policy and encryption protect them. Protect the destination, and ask S3 for
    server-side encryption (`--sse`). The 0.4.0 documentation said the password key was already
    encrypted on disk; it was not.

**`syncd backup`**, the command underneath, for an engine without `syncdctl` (a container, a
pair):

| Option | Meaning |
|---|---|
| `--to DIR\|s3://bucket/prefix` | Where to write |
| `--keep N` | Remove the oldest backups there beyond `N` |
| `--include-console-db URL` | Also dump the console's database (`pg_dump`) beside the archive |
| `--endpoint URL` | An S3-compatible store (MinIO): `https://minio.example.gov:9000`. Path-style addressing is used with an endpoint. Default `AWS_ENDPOINT_URL_S3`, else AWS by region |
| `--region R` | The bucket's region. Default `AWS_REGION`, else `us-east-1` |
| `--sse MODE` | Server-side encryption: `AES256`, `aws:kms` or `aws:kms:<key id>` |
| `--s3-ca FILE` | PEM CA certificates for an endpoint with a private certificate |

`syncd backup` needs what revealing values needs: the control socket, mutual TLS, or
`api.local_reveal: true`, since the whole store leaves the engine. Without the options,
`AWS_ENDPOINT_URL_S3` (else `AWS_ENDPOINT_URL`) and `AWS_REGION` (else `AWS_DEFAULT_REGION`) in
`/etc/syncd/engine.env` give the endpoint and the region. `syncdctl backup --to` passes the four
S3 options on (it makes a relative `--s3-ca` absolute; the file must be readable by `syncd`).

**S3 credentials** come from the engine's environment, `/etc/syncd/engine.env` on a host, in this
order:

1. `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY` (with `AWS_SESSION_TOKEN` for temporary ones);
2. a web identity token, `AWS_WEB_IDENTITY_TOKEN_FILE` with `AWS_ROLE_ARN`, exchanged with STS
   (what IRSA and EKS Pod Identity set on a pod);
3. the shared credentials file (`AWS_SHARED_CREDENTIALS_FILE` or `~/.aws/credentials`, profile
   `AWS_PROFILE` or `default`);
4. from 0.5.0, the role of the EC2 instance it runs on (the instance metadata service, version
   2), unless `AWS_EC2_METADATA_DISABLED=true`.

Off EC2 with none of the first three, the `no AWS credentials` error now comes after a short wait
for the metadata service: set `AWS_EC2_METADATA_DISABLED=true` in `engine.env` to skip it.
Requests are signed with SigV4, with the FIPS provider in FIPS mode. A large archive goes up as a
multipart upload.

### Scheduled backups

```yaml
engine:
  backup:
    to: s3://backups/syncd/prod   # or a directory
    every_secs: 86400             # a day (the default); at least 60
    keep: 14                      # the default; older ones are removed after each success
    # endpoint: https://minio.example.gov:9000
    # region: eu-west-1
    # sse: aws:kms                # AES256, aws:kms or aws:kms:<key id>
    # ca_file: /etc/syncd/minio-ca.pem
```

The engine takes one `every_secs` after the last success, which it records in its store, so a
restart does not take one at once; the first after a start waits a minute unless one is overdue.
A scheduled backup holds the engine's part only: the console's database and `/etc/syncd` are not
in it. Back those up with `syncdctl backup --to`, or your database's own backups.

| Where | Shows |
|---|---|
| `syncd status`, the console's engine page | `backups: to ... every ..., last ... ago (...), next in ...`, with `FAILED <ago>: <why>` after a failure |
| Metrics | `syncd_backup_last_success_timestamp_seconds`, `syncd_backup_interval_seconds`, `syncd_backup_last_bytes`, `syncd_backup_last_failure_timestamp_seconds`, `syncd_backup_pruned_total` |
| Alert `SyncdBackupStale` | No scheduled backup has succeeded for two intervals |
| Webhook `backup.failed` | An attempt failed |
| The log | `backup written`, `backup failed` |

### Restoring an off-host backup

```bash
sudo syncdctl restore --from s3://backups/syncd/prod             # the newest
sudo syncdctl restore --from s3://backups/syncd/prod --name syncd-idm1-20261108T020000Z
```

`syncdctl restore --from` asks first (`--yes` asks nothing), stops the services, then:

1. restores the engine store with `syncd restore --replace`: the store already there is moved to
   `/var/lib/syncd/engine/replaced-<time>`;
2. restores the console's database from the dump, dropping first the tables the dump does not
   have;
3. restores `/etc/syncd` from its archive, keeping the current `/etc/syncd/pki`, which the
   console's stored credentials match (the previous `/etc/syncd` is kept as
   `/etc/syncd.previous`). A password-key passphrase file inside `/etc/syncd`, which backups leave
   out from 0.5.0, is kept as the host has it; when the host has none, it says to put it back;
4. restores the console's master key from `<name>.console-master.key` (owner `syncd-console`,
   mode 0600); a different key it replaces is kept as `master.key.previous`. A backup taken before
   0.4.1 has none: the console keeps the key it has, which opens the restored database's secrets
   only if it is the same key. New in 0.4.1;
5. starts the services and checks them.

Give it the S3 options the backup was taken with (`--endpoint`, `--region`, `--s3-ca`; `--sse` is
not needed to read). A part it cannot fetch is named (`<name>.<part> was not fetched: ...`), and
the restore goes on without it. In 0.4.0 every fetch of the dump and of `/etc/syncd`'s archive
failed on file permissions, silently.

For the engine alone, run `syncd restore` as the engine's user with the engine stopped:

```bash
sudo systemctl stop syncd
sudo -u syncd syncd restore --from s3://backups/syncd/prod --replace
```

| Option | Meaning |
|---|---|
| `--from DIR\|s3://...` | Where the backups are |
| `--name syncd-<host>-<time>` | The backup to restore; the newest when left out |
| `--replace` | Move a store already in the data directory to `<data_dir>/replaced-<time>/` instead of refusing |
| `--data-dir DIR` (global) | Restore into another directory |
| `--endpoint`, `--region`, `--sse`, `--s3-ca` | As for `syncd backup` |

- **Checked.** It refuses while the engine's control API answers, refuses a backup whose store
  format the installed release cannot read (install that release first), and checks every file
  against the manifest before it puts anything in place.
- **Sealed keys.** The password keys come back as they were backed up. When they are sealed, the
  engine needs the passphrase of the engine they came from; `syncd restore` says so when the
  passphrase it has is missing or does not open them (`the restored password key is sealed:
  ...`). See [The password keys](#the-password-keys).
- **The configuration** in the backup goes beside the live one, as
  `/var/lib/syncd/engine/engine.restored.yaml`, never over it: the host's `api` and `data_dir`
  may differ. `syncd config diff engine.restored.yaml /etc/syncd/engine/engine.yaml` says what
  changed since; publish the one you want.
- **Catching up.** The restored engine resumes its sources from the checkpoints in the backup, so
  changes since are read again where the source still has them, and its next reconciliation brings
  the targets in line (`syncd reconcile` runs one now). Password changes delivered after the backup
  are not sent again.

On Kubernetes, see [Backups on Kubernetes](availability-and-sizing.md#backups-on-kubernetes).

### Host-local backups

```bash
sudo syncdctl backup                  # stops syncd briefly; prints the backup's directory
sudo syncdctl restore /var/lib/syncd/backups/20261108T101500Z-manual-0.4.1
```

A backup is a directory under `/var/lib/syncd/backups`, readable by root only, named
`<UTC time>-manual-<version>` or `<UTC time>-upgrade-<from>-to-<to>`. It holds:

| Part | From |
|---|---|
| `engine/` | The engine store, `/var/lib/syncd/engine`, with the password key and agent CA (copied with reflinks where the file system supports them) |
| `console/` | `/var/lib/syncd/console`, with the master key |
| `etc/` | `/etc/syncd`: configuration, secrets and the control API's certificates and CA key |
| `console.dump` | The console's database, `pg_dump --format=custom` |
| `info` | The version, the console's schema version, the store format and the reason |

- **Consistency.** Both services are stopped while the store is copied, so the copy is consistent.
  They start again afterwards if they were running. Plan for a short outage: the time to copy the
  store.
- **Certificates kept by a rollback.** `syncdctl rollback` restores the console's database only
  when the schema changed. When it keeps the database, it keeps `/etc/syncd/pki` too, since the
  console's stored credentials for the engine match the current certificates (after a
  `renew-certs`, say).
- **Restore** stops the services, puts every part back, always restores the console's database
  (dropping first the tables the dump does not have, then `pg_restore --clean
  --single-transaction`), and starts and checks the services. It warns when the backup was taken
  with another version, and refuses a backup whose store format the installed release cannot read.
- **Protect copies.** A backup holds the console's master key and the secrets in `/etc/syncd`.
- **Retention.** Upgrades keep the newest three backups (`SYNCD_KEEP_BACKUPS`), manual ones
  included. They stay on the host: use off-host backups for disaster recovery.

What else to keep, and how to recover on a new host, is in
[Sizing, availability and recovery](availability-and-sizing.md#disaster-recovery).

## Support bundle

New in 0.4.0. What support asks for first, in one file, with no entity data and no secrets:

```bash
sudo syncdctl support-bundle                 # syncd-support-<host>-<time>.tar.gz here, mode 0600
sudo syncdctl support-bundle /tmp/case-1234.tar.gz
sudo syncd support-bundle --out bundle.tar   # the engine's part alone; .tar is left uncompressed
sudo syncd support-bundle --out - > bundle.tar.zst   # to standard output (new in 0.4.1)
```

With `--out -` the engine streams the `.tar.zst` to standard output and lists what it holds on
standard error, for `kubectl exec` (see [The command line](#the-command-line) on Kubernetes).

| Part | Holds |
|---|---|
| The engine's (`syncd support-bundle`, `syncd-support-<host>-<time>.tar.zst` by default) | Versions, the full `syncd status`, the running configuration with every secret and every path to one replaced by `<redacted>`, held changes and dead letters as counts and error texts (no DN, no entity id), a metrics scrape, the store's own statistics, the data directory's file sizes, the host's and the engine process's limits, the `SYNCD_*`, `AWS_*` and `RUST_LOG` variables with their values redacted, and the newest 10 000 lines of the engine's journal |
| What `syncdctl` adds | The console's version, the installed and previous releases, the console's schema and migration state, `console.env` with every value that may hold a secret redacted, the console's journal, `systemctl status` of both services, and the certificates' expiry |
| The console's (`GET /api/v1/admin/support-bundle`; from 0.4.1 the **Download support bundle** link on the **Users** page) | For a console you reach but whose host you don't: its version and settings (secrets redacted), migrations, and each registered engine's address, environment, certificate expiry and whether it answers. Administrators with a session only (API tokens are refused), recorded as `support.bundle-exported` |

No attribute values, DNs, anchors, certificates or keys are collected. The one part that may name
an entry is the journal, when the log did (a failed export names its DN): `journalctl -u syncd -n
10000` shows what goes in. Look inside before sending it if that matters.

## High availability

New in 0.4.0. Two engines with the same configuration form an **active/passive pair** (setup on
hosts, from 0.5.0 with `syncdctl ha init` and `ha join`:
[Installation](install.md#an-activepassive-pair-on-hosts); on Kubernetes:
[An active/passive pair](install.md#an-active-passive-pair)). How it works and what it cannot do
are in [High availability](availability-and-sizing.md#high-availability).

### Status

```text
ha: pair idm, this engine idm1 is ACTIVE (lease postgres, held until in 12s)
ha: last snapshot taken 20s ago (1.5 MiB; 64.0 KiB new, 9 of 12 files held already), every 1m0s
ha: the passive engine follows the password queue
```

On the passive engine: `ha: pair idm, this engine idm2 is passive; idm1 at idm1.example.gov:7443
is active (lease postgres, expires in 9s)` and `ha: last snapshot received ...`. `NO ENGINE HOLDS
THE LEASE` means neither does, and `LEASE ERROR: ...` follows when the lease cannot be read or
renewed. On the active engine, `ha: THE PASSIVE ENGINE IS NOT FOLLOWING THE PASSWORD QUEUE; N
password changes were accepted without its acknowledgement ...` means a queued password change
would be lost with this engine until the next snapshot (new in 0.5.0).

| Where | Shows |
|---|---|
| `syncd status` on either engine | The `ha:` lines above |
| The console | The two engines as one card with the active one's status and each one's role; the engine page's role line, or an alert when nobody holds the lease |
| Metrics | `syncd_ha_active{pair,name}` (1 on the active engine), `syncd_ha_snapshot_age_seconds`, `syncd_ha_snapshot_interval_seconds`, `syncd_ha_lease_expires_timestamp_seconds`; from 0.5.0 `syncd_ha_snapshot_size_bytes`, `syncd_ha_snapshot_shipped_bytes`, `syncd_ha_snapshot_files`, `syncd_ha_snapshot_files_reused`, and on the active engine `syncd_ha_password_follower_connected` and `syncd_ha_password_changes_unreplicated_total` |
| Alerts | `SyncdHaNoActive`, `SyncdHaSnapshotStale` |
| Webhook | `ha.failover`, told once, by the engine that took over |

The passive engine serves its control API and its metrics, runs no source and writes nothing.
From 0.5.0 it also listens on the password agents' ingest port and forwards what agents send to
the active engine (in strict [control API access](#control-api-access) the certificate it uses
must be listed under `api.access.passwords` on both hosts), and it follows the active engine's
queue of password changes between snapshots (`<data_dir>/ha/password-queue.replica`). A submitted
change waits up to `engine.ha.password_replication_wait_ms` (2000) for the passive engine to have
it; with the passive engine down it is accepted at once and counted in
`syncd_ha_password_changes_unreplicated_total`. From 0.4.1, when `CONSOLE_SELF_SERVICE_ENGINE`
names one engine of a pair, the console sends each self-service change to the member that holds
the lease. Both engines need the same password-key passphrase when the keys are sealed. See
[Password synchronisation](password-sync.md#high-availability).
`syncd status` works on it, and so do `syncd dlq` and `syncd quarantine`, which read its store;
commands are refused with `this engine is the passive one of the pair idm; idm1 (...) is active:
send the command there`. A configuration published to it is saved and loaded like on the active
one.

### The lease

- **`postgres`**: a row per pair in the table `syncd_lease`, taken and renewed in one statement
  that succeeds only for its holder or over an expired row, judged by the database's clock. The
  console's database works (its migrations create the table); in any other, the engine creates
  the table if its role may. Each lease call times out after five seconds.
- **`file`**: the lease in a file on a file system both hosts share, judged by the hosts' clocks:
  keep them in NTP sync. It suits tests and simple setups.
- **`kubernetes`** (new in 0.5.0): a `coordination.k8s.io/v1` Lease object (`lease.name`, the
  pair's name by default; `lease.namespace`, the pod's own), reached with the pod's service
  account, which needs get, create and update on leases. The chart sets it with
  `engine.ha.lease.kind: kubernetes`; changing a pair's lease kind needs both engine pods stopped
  first. A pod that has just started waits one lease duration before it takes over a lease whose
  holder is gone. It was tested against a fake API server only, never a real cluster.
- **Renewal.** The holder renews every third of `ttl_secs` (15 by default). An engine with
  `role: passive` never takes it.

### Fencing

Every export batch, of every target kind, and every password delivery checks first that this
engine holds the lease and that it is not within a second of expiring. An engine that cannot renew
(cut off from the lease's database) stops writing before the other may take over, logs `the
pair's lease is lost`, and steps down to passive. A batch it refused is neither written nor
recorded as written, so the engine that takes over plans it again.

### Snapshots

The active engine takes a snapshot of its store, with its key material and configuration file,
five seconds after it starts and every `snapshot_every_secs` after: 60 by default from 0.5.0 (300
before; a value that is set is kept, so a pair set up before keeps its 300). The passive engine
pulls the newest over the control API (mutual TLS, as the console's calls) every half interval, at
most every 30 seconds, and checks every file. From 0.5.0 a snapshot is a manifest with each
file's SHA-256, and only the files the passive engine lacks are sent; the first is sent whole. The
active engine keeps the newest two as hard links to the store's files in
`<data_dir>/ha/snapshots/`. A snapshot that could not be received whole changes nothing: the
passive engine keeps the one before. On a takeover the newest received snapshot is swapped in for
the passive engine's store, in recorded steps (`ha/swap.json`) that a crash recovers from at the
next start. An agent enrolled or a key rotated after the last snapshot is lost with the active
engine, and so is a WebAssembly module kept next to a hand-written configuration (it is not in
snapshots).

### Failover

When the active engine stops renewing the lease (it died, its host or network went away), the
lease expires after `ttl_secs` and the passive engine takes it: it swaps the newest snapshot in,
reconciles every publication with its target, and starts the sources from the snapshot's
checkpoints. A clean stop gives the lease up at once. After a failover:

1. Find out why the old active engine stopped: its host, its log, or `the pair's lease is lost`
   (it was cut off from the lease for longer than the TTL).
2. On the new active engine, `syncd status`: the sources streaming, the targets up, the dead
   letters where they were. A PostgreSQL replication stream reads its tables in full first after
   a takeover. `syncd reconcile` runs another reconciliation if in doubt. The log says `password
   queue: what the old active engine wrote after the snapshot was applied`, or warns that its
   copy had a gap.
3. Bring the old engine back. It starts passive, pulls a snapshot, and is ready to take over
   again.

**Moving the active role on purpose** (maintenance on its host): stop the active engine cleanly,
`sudo systemctl stop syncd` (or delete its pod). The passive one takes over at once.

**Configurations.** The engine that takes over adopts the configuration file in the snapshot when
its host settings (`engine.data_dir`, `api`, `engine.ha`) are the same as its own, so publishing to
the active engine is enough. Hosts whose host settings differ (another `api.listen`) keep their
own: publish to both.

## Monitoring

### Prometheus metrics

The engine serves Prometheus metrics when the host's engine configuration sets
`api.metrics_listen`. It is in the `api` section, so it can't be set from the console: edit
`/etc/syncd/engine/engine.yaml` and restart syncd.

```yaml
api:
  metrics_listen: 127.0.0.1:9464   # GET /metrics, plain HTTP
```

Metrics carry counters and states only, no attribute values. They're plain HTTP, so keep the
address on loopback or a management network, or put a TLS proxy in front. A port already in use
stops the engine from starting. The passive engine of a pair serves its metrics too.

| Metric | Meaning |
|---|---|
| `syncd_source_up{source,mode}` | 1 while a source reads, streams or waits for its next read |
| `syncd_source_state{source,state}` | 1 for the source's current state (`retrying` after a failure) |
| `syncd_source_restarts_total{source,mode}` | Restarts after failures |
| `syncd_source_events_total{source,mode}` | Changes received |
| `syncd_source_last_event_timestamp_seconds{source,mode}` | When it last sent a change |
| `syncd_source_last_checkpoint_timestamp_seconds{source,mode}` | When its position was last committed |
| `syncd_source_rows_skipped_total{source,mode}` | Rows a full read skipped as bad (CSV sources with `on_error: skip`). New in 0.4.0 |
| `syncd_source_failures_total` | Source failures |
| `syncd_source_throttled_total{source,mode}` | Requests a REST source's service throttled (429, 503), each waited for and tried again. New in 0.5.0 |
| `syncd_target_up{target}` | 0 from an export that failed for lack of a connection until the target answers again |
| `syncd_target_down_since_timestamp_seconds{target}` | When an unreachable target went down |
| `syncd_publication_dead_letters{publication,target}` | Failed exports waiting |
| `syncd_target_unmanaged{publication}`, `syncd_target_unmanaged_ignored`, `syncd_target_unmanaged_privileged`, `syncd_target_unmanaged_checked_timestamp_seconds`, `syncd_target_unmanaged_failed` | The last [unmanaged-entries pass](#unmanaged-entries): entries no publication exported, those `ignore` hides, members of privileged groups no rule accounts for, when it ended, and 1 when it could not finish. New in 0.5.0 |
| `syncd_deletes_held{scope}` | Deletes held back by a threshold, waiting for approval |
| `syncd_threshold_blocks_total` | Threshold holds started (a hold still in place is not counted again) |
| `syncd_held_changes{publication,reason}` | Changes planned but not applied, by reason (`window`, `overdue`, `modifications`, `additions`, `deletions`, `canary`, `canary_failed`, `expired`). New in 0.4.0 |
| `syncd_window_opens_timestamp_seconds{publication}` | When a closed change window next opens. New in 0.4.0 |
| `syncd_canary_failed{publication}` | 1 while a canary failed and the rest of its change waits. New in 0.4.0 |
| `syncd_shadow_planned{publication,kind}` | What a publication in report mode would send, by kind. New in 0.4.0 |
| `syncd_deprovision_pending{publication,stage}` | Leavers waiting for a stage of the staged leaver policy (`disable`, `strip_groups`, `move`, `delete`). New in 0.4.0 |
| `syncd_timers_pending`, `syncd_timers_next_due_timestamp_seconds` | Persisted timers armed, and when the earliest fires. New in 0.4.0 |
| `syncd_groups{publication}`, `syncd_groups_manual_members`, `syncd_groups_expired`, `syncd_groups_review_due` | Computed groups: all, with members added by hand, expired, reviews due. New in 0.4.0 |
| `syncd_unmatched_records{view,source}`, `syncd_unmatched_oldest_seconds{view}`, `syncd_unmatched_ageing{view}` | Records waiting to join an entity, the longest wait, and those older than `correlation.unmatched_alert_after`. New in 0.4.0 |
| `syncd_quality_failing{view,check,severity}` | Entities failing a data-quality check. New in 0.4.0 |
| `syncd_history_entries`, `syncd_history_pruned_total`, `syncd_history_oldest_timestamp_seconds`, `syncd_history_last_prune_timestamp_seconds` | Entity history kept, pruned, its oldest entry and the last prune. New in 0.4.0 |
| `syncd_backup_interval_seconds`, `syncd_backup_last_success_timestamp_seconds`, `syncd_backup_last_bytes`, `syncd_backup_last_failure_timestamp_seconds`, `syncd_backup_pruned_total` | Scheduled backups. New in 0.4.0 |
| `syncd_ha_active{pair,name}`, `syncd_ha_snapshot_age_seconds`, `syncd_ha_snapshot_interval_seconds`, `syncd_ha_lease_expires_timestamp_seconds` | An HA pair. New in 0.4.0 |
| `syncd_ha_snapshot_size_bytes`, `syncd_ha_snapshot_shipped_bytes`, `syncd_ha_snapshot_files`, `syncd_ha_snapshot_files_reused`; `syncd_ha_password_follower_connected`, `syncd_ha_password_changes_unreplicated_total` | The newest snapshot's size, what was sent of it, its files and those not sent again; on the active engine, whether the passive one follows the password queue, and the changes accepted without its acknowledgement. New in 0.5.0 |
| `syncd_password_deliveries_total{publication,outcome}` | Captured password changes since the engine started, per publication: `delivered`, `failed` (refused by the target, or expired), `dropped` (the publication is in report mode). A failure no publication can be named for (a change that does not open) has an empty `publication`. New in 0.4.1 |
| `syncd_password_waiting{publication}` | Password changes waiting for the publication's account to be exported or its target to answer, as of the last delivery pass. New in 0.4.1 |
| `syncd_password_key_age_seconds`, `syncd_password_previous_keys`, `syncd_password_agent_ca_expiry_seconds`, `syncd_password_agent_cert_expiry_seconds` | The current password key's age, keys a rotation replaced that still open, and seconds until the agent CA and the first agent certificate expire. New in 0.5.0 |
| `syncd_password_changes_refused_total`, `syncd_password_agents_unbound` | Changes an agent submitted for a source it is not bound to (refused, never queued), and agents bound to no source (enrolled before 0.5.0). New in 0.5.0 |
| `syncd_license_state{state,source}`, `syncd_license_managed_identities`, `syncd_license_managed_identities_limit`, `syncd_license_expiry_seconds`, `syncd_license_development_key` | The license: 0 licensed or evaluating, 1 warning, 2 grace period, 3 publishing blocked; the managed identities and the tier; seconds to expiry (negative after); 1 for a development key. New in 0.5.0. See [Licensing](licensing.md) |
| `syncd_entra_skipped_synced_users{target}` | Exports to an Entra ID target refused because the user is synchronised from on-premises AD. New in 0.4.0 |
| `syncd_engine_busy`, `syncd_engine_busy_seconds` | A long operation runs (full sync, reconciliation, reload), and for how long; commands are refused meanwhile |
| `syncd_view_quarantined{view}` | Entities whose exports a failing rule holds back, per view (0 for a view with none) |
| `process_resident_memory_bytes`, `syncd_memory_limit_bytes` | Resident memory, and the cgroup's limit (`MemoryMax`, or a container's memory limit) when one is set |
| `process_cpu_seconds_total`, `process_threads`, `process_open_fds` | CPU time used, user and system (its rate is the CPUs in use), threads and open files |
| `syncd_export_operations_total`, `syncd_export_failures_total` | Export results |
| `syncd_entities_changed_total`, `syncd_entities_deleted_total`, `syncd_records_joined_total`, `syncd_records_projected_total`, `syncd_records_unchanged_total`, `syncd_ambiguous_joins_total`, `syncd_rule_errors_total`, `syncd_orphans` | Pipeline counters |
| `syncd_build_info`, `syncd_config_info`, `syncd_start_time_seconds` | The version, the configuration version, and when the engine started |

A scrape job:

```yaml
scrape_configs:
  - job_name: syncd
    static_configs:
      - targets: ["127.0.0.1:9464"]
```

The console has no metrics endpoint; watch it with `/healthz`. It shows the engine's CPU and
memory live, from the engine's status: on each engine's card on the engines dashboard, with a
sparkline of the last minute (amber at 90% of the CPUs the engine may use, or of its memory
limit), and in the **Engine process** section of the live metrics page, charted over 1, 5 or 15
minutes. That history lasts while the page is open; for longer, scrape `/metrics`. Its webhooks
send the same state changes to chat and paging tools (see [Webhooks](webhooks.md)).

### Alert rules

Release bundles and packages ship Prometheus alerting rules in
`/opt/syncd/current/share/monitoring/syncd-alerts.yaml`. Load the file from your Prometheus
configuration's `rule_files`, and check it with `promtool check rules syncd-alerts.yaml`. Only
`SyncdEngineDown` assumes the scrape job is called `syncd` (`up{job="syncd"}`); change it there if
yours is not. Before 0.4.0, `SyncdMemoryNearLimit` selected the job too; it no longer does. The
other rules select on the engine's own metrics.

| Alert | Severity | Fires when |
|---|---|---|
| `SyncdEngineDown` | critical | The engine has not answered scrapes for 5 minutes |
| `SyncdSourceDown` | critical | A source has not been running for 10 minutes |
| `SyncdSourceFlapping` | warning | A source restarted more than 3 times in an hour |
| `SyncdSourceStalled` | critical | A source sent changes, but nothing has been committed for 15 minutes (full reads aside) |
| `SyncdTargetDown` | critical | An export target has been unreachable for 5 minutes |
| `SyncdDeadLetters` | warning | Failed exports have been waiting for 30 minutes |
| `SyncdDeletesHeld` | warning | A deletion threshold holds deletes back |
| `SyncdChangesNeedApproval` | warning | A publication's changes wait for `syncd approve`: a modification or add threshold was crossed, a canary failed, or changes outlived the change window's `max_delay` (new in 0.4.0) |
| `SyncdCanaryFailed` | critical | The entries a canary sent first ended in the dead-letter queue or drifted; the rest of the change is held (new in 0.4.0) |
| `SyncdGroupsExpired` | warning | A publication's computed groups passed their expiry (new in 0.4.0) |
| `SyncdGroupReviewsDue` | warning | A computed group's review (`review_every`) has been due for a day (new in 0.5.0) |
| `SyncdUnmatchedRecordsAgeing` | warning | Records of a view have waited to join an entity longer than its `correlation.unmatched_alert_after`, for an hour (new in 0.4.0) |
| `SyncdQualityErrors` | warning | A data-quality check of severity `error` has failed for an hour (new in 0.4.0) |
| `SyncdQuarantined` | warning | A view has had quarantined entities for 30 minutes |
| `SyncdRuleErrors` | warning | Rules failed in the last 30 minutes |
| `SyncdEngineBusy` | warning | One long operation has run for over an hour |
| `SyncdBackupStale` | warning | No scheduled backup (`engine.backup`) has succeeded for two intervals, or none yet, for 30 minutes (new in 0.4.0) |
| `SyncdHaNoActive` | critical | Neither engine of an HA pair has held the lease for 2 minutes: nothing is exported (new in 0.4.0) |
| `SyncdHaSnapshotStale` | warning | An HA engine's newest store snapshot (taken when active, received when passive) is older than three intervals, for 15 minutes (new in 0.4.0) |
| `SyncdPasswordDeliveryFailures` | warning | Captured password changes failed for a publication in the last 15 minutes: a target refused them, they expired before their account existed, or they could not be opened (new in 0.4.1) |
| `SyncdPasswordChangesDropped` | warning | Captured password changes were thrown away in the last hour because the publication that delivers them is in report mode (new in 0.4.1) |
| `SyncdUnmanagedEntriesGrew` | warning | A publication's target holds more [unmanaged entries](#unmanaged-entries) than a day earlier (no `for:`; it clears by itself after a day, or once the entries are adopted, removed or ignored) (new in 0.5.0) |
| `SyncdUnmanagedPrivilegedMembersGrew` | critical | More members of privileged groups that no rule accounts for than a day earlier (new in 0.5.0) |
| `SyncdPasswordAgentCertExpiring` | warning | A capture agent's certificate expires within 14 days, for an hour: the agent is down, cannot reach the engine, or does not renew by itself (new in 0.5.0) |
| `SyncdPasswordKeyOld` | warning | The password key is more than a year old, for an hour (new in 0.5.0) |
| `SyncdMemoryNearLimit` | warning | Resident memory has been above 90% of the cgroup's limit for 10 minutes |
| `SyncdLicenseWarning` | warning | The license expires within 30 days, the managed identities are near its tier, or the evaluation is ending, for an hour (new in 0.5.0) |
| `SyncdLicenseGracePeriod` | warning | The license is in its 30-day grace period, for 10 minutes (new in 0.5.0) |
| `SyncdLicensePublishingBlocked` | critical | Publishing configuration changes is blocked by the license, for 10 minutes; sync keeps running (new in 0.5.0) |
| `SyncdLicenseDevelopmentKey` | warning | The build accepts the development license key, or the license was signed with it, for an hour (new in 0.5.0) |

The file holds 30 rules. 0.4.0 added the eight rules marked 0.4.0 (its release notes said seven;
0.4.1's correct it), 0.4.1 the two password rules, and 0.5.0 the nine marked 0.5.0: copy the file
again after upgrading, or upgrade the chart with the PrometheusRule on. The file and the chart's
PrometheusRule hold the same rules. The release notes say the two unmanaged-entries rules were not
run through `promtool` before the release: check the file with `promtool check rules` when you
load it.

The thresholds are starting points: tune `for:` and the ages to how often your sources change and
how long your full syncs take. A quiet source commits rarely (syncrepl only sends a new position
with a change), so `SyncdSourceStalled` looks for changes received since the last commit rather
than for an old commit alone. PostgreSQL replication slot retention isn't an engine metric; watch
it with `postgres_exporter` (`pg_replication_slots`). What to do about each alert is in
[Troubleshooting](troubleshooting.md).

## Quarantined entities

A computed attribute with `on_error: quarantine` holds an entity's exports back when it fails for
that entity, rather than writing a wrong value (see [Rules](rules.md)). So does a data-quality
check with `on_error: quarantine` (see
[Data-quality checks](reports-and-data-quality.md#data-quality-checks)). The hold is kept in the store, with the attribute or check, its error and since when, so a restart
keeps it. It ends by itself once the rule evaluates again, or the check passes, for the entity.

| Where | Shows |
|---|---|
| `syncd status` | A count per view, and up to 20 entities with the attribute and the error |
| `syncd quarantine` | All of them, read from the store (works while the engine runs) |
| The console's engine page | **Held back by a failing rule**, with up to 100 of them (`GET /api/v1/engines/{id}/quarantine`) |
| `syncd_view_quarantined{view}`, alert `SyncdQuarantined` | The count per view; the alert fires after 30 minutes |

To release them, fix the source data the rule reads (the next change to the entity releases it),
or fix the rule and publish it (the full sync that follows releases the entities). See
[Troubleshooting](troubleshooting.md).

## Certificates

The engine and the console talk over mutual TLS, even on loopback. At installation `syncdctl`
creates a CA of the host's own and issues the certificates from it, into `/etc/syncd/pki`.
Nothing outside the host trusts this CA.

| File | What | Readable by |
|---|---|---|
| `ca.crt`, `ca.key` | The CA (`syncd control API CA (<hostname>)`) | Certificate: all. Key: root only |
| `engine.crt`, `engine.key` | The engine's control API certificate: `CN=localhost`, for `localhost`, `127.0.0.1` and `::1` | Key: root and the `syncd` group |
| `console-client.crt`, `console-client.key` | The console's client certificate (`CN=syncd console`) | Key: root and the `syncd-console` group |
| `cli.crt`, `cli.key` | The command line's client certificate (`CN=syncd command line`). The passive engine of a pair pulls snapshots with it | Key: root and the `syncd` group |

All are ECDSA P-256 and valid for **10 years** from installation. The engine's configuration names
`engine.crt`, `engine.key` and `ca.crt` (as the client CA) in its `api.tls` section. Upgrades do
not reissue certificates that exist.

**Expiry.** `sudo syncdctl status` shows when the first of them expires, and warns within 60 days.
The console's engine page warns too, when its client certificate for the engine has less than 60
days left, and once it has expired (then the console cannot reach the engine).

**Renewal:**

```bash
sudo syncdctl renew-certs            # new certificates from the same CA
sudo syncdctl renew-certs --new-ca   # a new CA too, when the CA itself expires
```

It asks before it starts (`--yes` asks nothing), then:

1. copies the current certificates and keys to `/etc/syncd/pki/before-renewal-<UTC time>`;
2. issues the engine's certificate and the console's and the command line's client certificates
   (and, with `--new-ca`, the CA first);
3. restarts the engine with them, and waits for it (`SYNCD_HEALTH_TIMEOUT`). If it does not
   start, it puts the previous certificates back, restarts the engine and stops with
   `certificates not renewed`;
4. gives the console its new credentials for the engine:
   `syncd-console admin register-engine --name local --address 127.0.0.1:7443 ... --update`,
   which the audit trail records as `engine.update`;
5. restarts the console and checks both services, then prints when the new certificates expire.

The services are down for a few seconds. Without `--new-ca`, it warns when the CA itself expires
within 400 days, since certificates from it cannot outlive it. It needs the CA's key
(`/etc/syncd/pki/ca.key`): certificates syncdctl did not issue are refused. It renews the
installed `local` engine only: an engine registered in another console, or under another name,
must be registered there again with `--update`.

**A pair on hosts.** `renew-certs` issues a `localhost` engine certificate again: replace it with
one that names the host afterwards, on each host (see
[An active/passive pair on hosts](install.md#an-activepassive-pair-on-hosts)).

The certificate browsers see is yours to renew: nginx's (`/etc/nginx/tls/`), or the console's own
(`CONSOLE_TLS_CERT`), which it picks up within a minute.

## The master key

The console encrypts the secrets it stores with envelope encryption: AES-256-GCM, a data key per
secret, wrapped by a master key. From 0.4.0 that is the client keys of registered engines, the
webhooks' addresses and signing secrets, and the secret values of environments; from 0.5.0 also
the private half of the configuration signing key. API tokens are not among them: the console
keeps only their hashes. The master key is `CONSOLE_MASTER_KEY`
(base64, 32 bytes) if set, else `/var/lib/syncd/console/master.key`, created (mode 0600) at the
first start, which logs `created a new master key for stored secrets; back it up`.

- **Back it up** apart from the database: a database dump without the key cannot be decrypted.
  `syncdctl backup` includes it, and from 0.4.1 so does `syncdctl backup --to`
  (`<name>.console-master.key`), so treat both kinds of backup as secrets, and keep a copy of the
  key apart from them too. Scheduled backups (`engine.backup`) and `syncd backup` hold the
  engine's part only.
- **The console checks it at every start** against the secrets its database holds, from 0.5.0
  one of each kind: an engine's client key, a webhook's URL and its signing secret, a secret
  environment value and the configuration signing key. 0.4.1 opened the first one it found
  (before that, engine client keys only), so a database with secrets sealed under two keys that
  0.4.1 started may now refuse to start, naming what the key does not open (`the master key does
  not open the secrets the database holds (webhook alerts's signing secret)`). It refuses to
  start, and says to restore the key, when:
    - `master.key` is missing while the database holds sealed secrets (a restore that missed it,
      a new data directory);
    - the key, from the file or `CONSOLE_MASTER_KEY`, does not open them.
- **If it is lost for good**, start the console with a new key on purpose:
  `CONSOLE_NEW_MASTER_KEY=true` in `/etc/syncd/console.env` (or `--new-master-key`), then restart.
  The stored secrets are lost, and the console logs that engines registered with mTLS must be
  registered again: register each one again with `syncd-console admin register-engine --update`,
  audited as `engine.re-registered` (for the installed engine, `sudo syncdctl renew-certs` does
  it). Webhooks are listed with their URL marked undecryptable: save each URL again and press
  **New secret**. Enter secret environment values again. Make a new configuration signing key
  (`syncd-console admin config-signing-key --replace`) and install it on the engines that require
  signed configurations. Then remove the setting, so that a later lost key is refused again.
- **Wrapped by a KMS** (new in 0.5.0): stop the console, run `syncd-console admin
  wrap-master-key` with the KMS key (`awskms:...`, `azurekv:...` or `vaulttransit:<mount>/<key>`),
  which wraps `master.key` in place, and start the console with `CONSOLE_MASTER_KEY_KMS` set to the
  same key. The console then cannot start without asking the KMS: there is no fallback. Keep a
  copy of the plain key with your recovery secrets, or rely on the KMS key alone. None of this
  was run against a real AWS account, Azure tenant or Vault before the release, only against
  stand-ins.
- There is no key rotation command.
- **On Kubernetes** the key is in the `syncd-console` Secret (see
  [The master key Secret](#the-master-key-secret)).

## The password keys

With [password synchronisation](password-sync.md) on, the engine keeps two private keys in its
data directory: the password key (`password.key`, or `passwords.key_file`), which opens every
captured password in transit, and the capture agents' CA key (`password-agent-ca.key`, beside it,
with `password-agent-ca.crt`), when `passwords.ingest` is set. Both are mode 0600, owned by the
engine's user. From 0.5.0 a rotation adds files beside them: `password.key.<id>` for keys a
rotation replaced, and the previous agent CA during its overlap. Backups and HA snapshots carry
every key that is live when they are taken, as it is on disk.

**Seal them with a passphrase** (new in 0.4.1; recommended). Without one they are plain files,
protected by their mode only, and anyone who can read a backup can read them. With
`SYNCD_PASSWORD_KEY_PASSPHRASE_FILE`, both files are sealed: PBKDF2-HMAC-SHA256 (600,000
iterations) derives an AES-256-GCM key from the passphrase (FIPS-approved algorithms, through
aws-lc). The 0.4.0 documentation said these files were already encrypted; they were not.

1. Write a long random passphrase to a file the `syncd` user can read, outside `/etc/syncd` and
   outside the data directory (both go into backups: see below; from 0.5.0
   `syncdctl backup --to` leaves a passphrase file inside `/etc/syncd` out, with a warning), and
   keep a copy where you keep other recovery secrets, apart from the backups:

    ```bash
    sudo install -d -m 0750 -o root -g syncd /etc/syncd-keys
    sudo install -m 0640 -o root -g syncd /dev/null /etc/syncd-keys/password-key.passphrase
    head -c 32 /dev/urandom | base64 | sudo tee /etc/syncd-keys/password-key.passphrase >/dev/null
    ```

2. Name it in `/etc/syncd/engine.env`, on a line of its own:
   `SYNCD_PASSWORD_KEY_PASSPHRASE_FILE=/etc/syncd-keys/password-key.passphrase`. On Kubernetes,
   `engine.passwordKeyPassphrase.existingSecret` does this (see
   [Password synchronisation](install.md#password-synchronisation)).
   `SYNCD_PASSWORD_KEY_PASSPHRASE`, the passphrase itself, is read when the file variable is not
   set, for tests and containers that cannot mount a file. From 0.5.0 the file may hold the
   passphrase wrapped by a KMS key instead: write it with `syncd secrets wrap --kms <key> --out
   <file>` and set `SYNCD_PASSWORD_KEY_PASSPHRASE_KMS` to the same key. The engine then cannot
   start without asking the KMS (no fallback); this was tested against stand-ins only.
3. `sudo systemctl restart syncd`. The engine seals both files in place, with the same keys, so
   nothing queued is lost and agents keep their certificates. The log says `the password key was
   stored in the clear and is now sealed with the passphrase`, and `syncd status` says
   `password keys: sealed with the passphrase`.

| Where | Shows |
|---|---|
| `syncd status` | `password keys: sealed with the passphrase`, or `password keys: stored in the clear (...)` |
| The engine's log at start | `the password keys are stored in the clear, protected by file mode only: ...` while they are not sealed |
| The console's engine page | The **Passwords** card: `keys sealed`, or `password keys stored in the clear`, with the deliveries per publication |
| The status API | `PasswordStatus.keys_sealed` |

- **The passphrase travels with the keys.** A sealed key file without the passphrase, or with
  another one, stops the engine at start, naming the variable: `the key is sealed with a
  passphrase, and none is set: set SYNCD_PASSWORD_KEY_PASSPHRASE_FILE ...`, or `the passphrase
  (SYNCD_PASSWORD_KEY_PASSPHRASE_FILE) does not open the sealed key ...`. The files are left as
  they are. Both engines of an HA pair, and a restore, need the passphrase of the engine the keys
  came from.
- **Where to keep it.** Not under `/etc/syncd`, nor under the data directory: `/etc/syncd` is in
  every host-local backup, beside the sealed keys, so a passphrase there would undo the sealing
  for anyone who can read the backups. From 0.5.0 `syncdctl backup --to` leaves such a file out of
  `<name>.etc.tar.gz` and warns; host-local backups (`syncdctl backup`, the upgrade's) still copy
  all of `/etc/syncd`. The source repository's RUNBOOK.md now uses `/etc/syncd-keys/`, as here.
  Back the passphrase up separately: a restore, on this host or a new one, needs it.
- **Backups taken before** sealing still hold the keys in the clear: protect or remove them.
- **A lost passphrase** cannot be recovered: move both key files aside and start the engine, which
  creates new keys. Queued changes are reported failed, and the agents enroll again. There is no
  command to unseal the files.
- **Rolling back to 0.4.0**, which cannot open sealed keys, needs the plain files from before they
  were sealed: see [Rolling back 0.4.1 to 0.4.0](install.md#rolling-back-041-to-040). A 0.4.1
  engine ignores the previous keys and the previous agent CA that 0.5.0 keeps, and cannot read
  0.5.0's agent and queued-password records: rotate nothing, and enroll no agent, until you are
  sure of the upgrade.

### Changing the passphrase and rotating the keys

New in 0.5.0. Run these on the engine's host with `sudo`: they use the control socket (over mutual
TLS in strict mode they need `api.access.passwords`). None changes a password or loses a queued
change, except a forced retirement.

| Command | What it does |
|---|---|
| `sudo syncd password rekey --passphrase-file /etc/syncd-keys/password-key.passphrase.new` | Seals every key file again with the new passphrase; the keys stay the same. The engine may be running or stopped. It keeps the current passphrase as `<passphrase file>.previous`, writes the new one to the passphrase file, then seals each file; the engine still opens with the previous one, so an interrupted run leaves an engine that starts, and running it again finishes it. `--no-install` seals the files only |
| `sudo syncd password rekey --forget-previous` | Removes the `.previous` file, once every engine has started, or received a snapshot, since the change. Until then the engine reminds you at every start |
| `sudo syncd password rotate-key` | Makes a new password key. Capture points seal to it as they learn of it (`syncd-capture` within the hour); the old key keeps opening what was sealed to it, and is retired once `passwords.rotation_overlap_days` (7) have passed and nothing queued needs it. `--retire [--key ID]` retires now; it refuses while queued changes need the key unless `--force`, which loses them |
| `sudo syncd password rotate-agent-ca` | Makes a new agent CA. The previous one stays trusted for the overlap, and agents renew with their next exchange; `syncd password agent list` shows who has not (`of the previous agent CA: not renewed yet`). An agent enrolled with `syncd password agent enroll` renews only with `syncd password agent renew --dir DIR` on its host. `--retire` retires the previous CA now: an agent that did not renew must enroll again |

- **A pair.** Run `rekey` with the same new passphrase on the other host right away. Keys rotated
  after the last snapshot are lost with the active engine.
- **Kubernetes.** The passphrase is a Secret mounted as a file the engine cannot write: `rekey`
  cannot replace it. Change the Secret (the chart's `engine.passwordKeyPassphrase.previousKey`
  keeps the previous passphrase) as the source repository's KUBERNETES.md describes.
- **Keep the overlap** longer than a change can wait: an agent's `spool_ttl_secs` plus
  `passwords.ttl_secs`. After a retirement nothing sealed to the old key opens anywhere.
- **Nothing rotates on a schedule.** `SyncdPasswordKeyOld` fires after a year, and
  `SyncdPasswordAgentCertExpiring` 14 days before an agent's certificate (365 days,
  `passwords.ingest.agent_cert_days`) expires. A renewal keeps the agent's key.
- A backup from before a rotation restores the old key as the current one; agents fetch it again
  by themselves.

## Running on Kubernetes

With the [Helm chart](install.md#install-with-helm), the engine runs in the pod `syncd-engine-0`
(for a release named `syncd`), or the pods `syncd-engine-0` and `-1` as a pair, and the console in
the Deployment `syncd-console`. There is no systemd and no `syncdctl`: Kubernetes restarts the
pods, and Helm installs, upgrades and removes them.

| | Engine | Console |
|---|---|---|
| Kind | StatefulSet, one replica (two with `engine.ha.enabled`) | Deployment (`console.replicas`, 1) |
| Ports | 7443 control API (mutual TLS), 9464 metrics (plain HTTP); Service `syncd-engine`, and the headless `syncd-engine-peers` for a pair. With `engine.passwordIngest.enabled` (0.4.1), the ingest port (7445) on the Service `syncd-engine-ingest`; from 0.5.0 both pods of a pair answer on it | 8080, HTTP or HTTPS with `console.tls`; Service `syncd-console` |
| Probes | Startup: the control API accepts connections (every 5 s, up to 10 minutes, for opening a large store). Readiness: the same. Liveness: `GET /metrics` on 9464 | `GET /healthz`, readiness and liveness |
| Volumes | `data` (the PersistentVolumeClaim) at `/var/lib/syncd`; the `syncd-pki` Secret at `/etc/syncd/pki`; `/run/syncd` (memory) for the control socket; `/tmp`; with `engine.passwordKeyPassphrase`, its Secret at `/run/secrets/syncd-password-key`; `engine.extraVolumes` | `/var/lib/syncd/console` and `/tmp`, both `emptyDir`; `console.extraVolumes` |
| Stopping | 90 s to finish its batch (`engine.terminationGracePeriodSeconds`) | |

### The command line

The image has no shell, but `syncd` is its entry point and finds its configuration
(`SYNCD_CONFIG`) and store by itself. Commands reach the running engine through its control
socket, without certificates:

```bash
kubectl -n syncd exec syncd-engine-0 -- syncd status
kubectl -n syncd exec syncd-engine-0 -- syncd held people_out
kubectl -n syncd exec syncd-engine-0 -- syncd dlq
kubectl -n syncd exec syncd-engine-0 -- syncd retry --publication people_out
kubectl -n syncd exec syncd-engine-0 -- syncd backup --to s3://backups/syncd/prod --keep 14
```

Every command in [The syncd command line](#the-syncd-command-line) works this way, with the
secrets from `engine.envFromSecret` in the pod's environment, except `syncd apply-config` on a
release installed with 0.5.0, whose strict [control API access](#control-api-access) lets the
socket read and operate but not apply a configuration. A release first installed before 0.5.0
keeps its engine file, without `api.access`, and stays compatible until you add the section to
`/var/lib/syncd/engine.yaml` on the data volume: the chart cannot set it. With a pair, run
commands on the active pod (`syncd status` says which). The console's own commands run in
its pod: `kubectl -n syncd exec deploy/syncd-console -- syncd-console admin reset-password --user
admin`.

The images have no `tar`, so `kubectl cp` cannot copy a file out of a pod. From 0.4.1 the engine
streams its [support bundle](#support-bundle) to standard output instead. A pod has no journal, so
the bundle leaves the journal out (its `manifest.json` says why): add the pods' logs.

```bash
kubectl -n syncd exec syncd-engine-0 -- syncd support-bundle --out - > syncd-support.tar.zst
kubectl -n syncd logs syncd-engine-0 --timestamps > engine.log     # and syncd-engine-1 for a pair
kubectl -n syncd logs deploy/syncd-console --timestamps > console.log
kubectl -n syncd logs job/syncd-register --all-containers --timestamps > register.log
```

The registration Job is deleted once it succeeds, so its log is there while it runs or after it
failed. The console's part comes from `GET /api/v1/admin/support-bundle`, or the **Users** page.

Logs go to the containers' standard output: `kubectl -n syncd logs syncd-engine-0`,
`kubectl -n syncd logs deploy/syncd-console`. The console's audit events are JSON lines in its
pod log, for the cluster's log collector (filter on `"schema":"ens-audit/1"`). Set `RUST_LOG` in
`engine.env` or `console.env`.

### The engine's configuration file

The engine's configuration is on its volume, `/var/lib/syncd/engine.yaml`. At the first start it is
a copy of the image's `/etc/syncd/engine.yaml` (`SYNCD_INITIAL_CONFIG`), which has no sources;
after that the console replaces it when it publishes a configuration. The `engine` and `api`
sections stay as the image sets them, and the engine refuses a published configuration that
changes them (and `engine.ha`, which a pair takes from the environment):

| Setting | In the image |
|---|---|
| `engine.data_dir` | `/var/lib/syncd/engine` |
| `api.listen` | `0.0.0.0:7443`, with `api.tls` from `/etc/syncd/pki` (`engine.crt`, `engine.key`, `ca.crt` as the client CA) |
| `api.control_socket` | `/run/syncd/control.sock` |
| `api.metrics_listen` | `0.0.0.0:9464` |
| `api.access` | From 0.5.0: `mode: strict`, `configure: [syncd console]`, `operate: [syncd engine]`, `passwords: [syncd engine]` |

`engine.backup` is not a host setting: publish it from the console like the rest. Nor is
`engine.require_signed_config` one the console can change: the chart's `engine.configSigning`
mounts the console's signing key (new in 0.5.0).

### Monitoring on Kubernetes

Metrics are always on, on port 9464 (`/metrics`, Service port `metrics`). With the Prometheus
Operator:

```yaml
engine:
  metrics:
    serviceMonitor: { enabled: true, labels: { release: kube-prometheus-stack } }
    prometheusRule: { enabled: true, labels: { release: kube-prometheus-stack } }
```

- **The ServiceMonitor** scrapes every 30 seconds (`interval`), and from 0.4.0 sets its targets'
  `job` label to `engine.metrics.job` (`syncd`). Before, the operator named the job after the
  Service (`syncd-engine`); change queries and dashboards that used that.
- **The PrometheusRule** (`engine.metrics.prometheusRule.enabled`, new in 0.4.0) holds the shipped
  alert rules, rendered with the same job, so every rule fires on Kubernetes as on a host. Before
  0.4.0, `SyncdEngineDown` and `SyncdMemoryNearLimit` could never fire with the ServiceMonitor.
  The labels are what your Prometheus's `ruleSelector` looks for. With several releases scraped by
  one Prometheus, enable it on one release only.

The pod's memory limit (`engine.resources.limits.memory`, 4Gi by default) is the engine's
`syncd_memory_limit_bytes`, so `SyncdMemoryNearLimit` works as on a host, and a CPU limit, if you
set one, caps the CPUs the console shows the engine may use.

### Upgrades

`helm upgrade` with the new chart version (see [Upgrade with Helm](install.md#helm-upgrade)).
Back up first: the chart takes no backup. The engine migrates its store when it starts, if the new
release changed its format, and the console migrates its database. `helm rollback` restores no data
(see [Rollback](install.md#on-kubernetes)).

### Certificates on Kubernetes

The chart issues the control API's certificates into the `syncd-pki` Secret, from a CA of its own
(`syncd control API CA`), valid for `pki.days` (3650, ten years). They are ECDSA P-256; an install
made with chart 0.3.0 keeps its RSA ones until the Secret is deleted. The engine's certificate
names the engine's Service (`syncd-engine`, `syncd-engine.<namespace>`, `.svc`,
`.svc.cluster.local`), `localhost`, and with a pair each pod's name. Their common names are
`syncd console` and `syncd engine`, the names `api.access` lists. There is no command-line
certificate: `kubectl exec` uses the control socket.

The console's engine page warns 60 days before the console's client certificate expires. To issue
new ones, delete the Secret and upgrade:

```bash
kubectl -n syncd delete secret syncd-pki
helm upgrade syncd oci://ghcr.io/winllc/charts/syncd --version X.Y.Z -n syncd -f syncd-values.yaml
```

The upgrade issues a new CA and new certificates. The engine pods restart with them (their
template carries a checksum of the Secrets), and the registration Job gives the console its new
credentials (`register-engine --update`). From 0.4.0 the Secret is kept by `helm uninstall`, so a
reinstall keeps the certificates the console already has. `syncdctl renew-certs` does not apply
here.

### The master key Secret

The `syncd-console` Secret holds the console's master key (`master-key`), which seals the
engine's client key and the console's other stored secrets, with the initial admin password. It
is generated at install, kept by upgrades, and kept by `helm uninstall`
(`helm.sh/resource-policy: keep`).

- **Back it up** with the database: `kubectl -n syncd get secret syncd-console -o yaml`, stored as
  a secret.
- **If it is deleted**, the next `helm upgrade` generates a new key, and the console refuses to
  start, saying the master key does not open the secrets the database holds; the registration Job
  refuses too, and `helm upgrade --wait` fails on it. Put the old Secret back (`kubectl apply` the
  backup) and run `helm upgrade` again.
- **If it is lost for good**, upgrade twice:

    ```bash
    helm upgrade syncd ... --reuse-values --set console.newMasterKey=true --wait
    helm upgrade syncd ... --reuse-values --set console.newMasterKey=false
    ```

    The first upgrade sets `CONSOLE_NEW_MASTER_KEY=true` on the console and the registration Job:
    both start with the new key, the secrets sealed with the old one are discarded, and the Job
    registers the engine again with the chart's certificates (`register-engine --update`, audited
    as `engine.re-registered`), keeping its id and configuration versions. Engines registered by
    hand with mTLS must be registered again, and webhooks and secret environment values entered
    again. The second upgrade turns the override off; `helm status` warns while it is on. Before
    0.4.0 the Job did not get `console.env` and failed here, and the engine had to be removed from
    the console first; that is no longer needed.

## Files and paths

| Path | What |
|---|---|
| `/opt/syncd/releases/<version>/` | One directory per installed release. Never edited |
| `/opt/syncd/current`, `previous` | Symlinks to the running release and the one before it |
| `/etc/syncd/console.env` | The console's settings: database URL, listen address, proxy, single sign-on (OIDC, and from 0.5.0 SAML and client certificates), the sign-in policy and consent banner, audit outputs, webhooks, mail, Git sync, `CONSOLE_LICENSE_FILE`. Readable by root and `syncd-console` only |
| `/etc/syncd/engine.env` | Secrets that engine configurations refer to (`password: { env: NAME }`), S3 credentials for backups, `SYNCD_HA_*`, `SYNCD_PASSWORD_KEY_PASSPHRASE_FILE` (and `_KMS`), the Vault, AWS and Azure settings for secrets in a vault, and `SYNCD_FIPS_MODE`. Readable by root and `syncd` only |
| `/etc/syncd/engine/engine.yaml` | The engine's configuration. Publishing a configuration in the console replaces it |
| `/etc/syncd/pki/` | The control API's CA and certificates (see [Certificates](#certificates)), and from 0.5.0 `console-signing.pub`, the console's configuration signing key, when installed |
| `/etc/syncd/engine/before-api-access-<time>/` | The engine's configuration as it was before `syncdctl api-access` changed it. New in 0.5.0 |
| `/etc/syncd/license.lic` | The license `syncdctl license install` installed (root, readable by the `syncd-console` group). New in 0.5.0 |
| `/etc/syncd-keys/` | Where this guide and the source repository's runbook keep the password keys' passphrase, outside the backups of `/etc/syncd`. You create it |
| `/etc/syncd.previous/` | `/etc/syncd` as it was before `syncdctl restore --from` |
| `/var/lib/syncd/engine/` | The engine's store, with `password.key`, `password-agent-ca.key` and `password-agent-ca.crt` when passwords are on (the keys sealed with a passphrase, or in the clear: see [The password keys](#the-password-keys)); from 0.5.0 `password.key.<id>` for keys a rotation replaced, and the previous agent CA during a rotation |
| `/var/lib/syncd/engine/backups/` | A store checkpoint, only while a backup is taken |
| `/var/lib/syncd/engine/restore/`, `replaced-<time>/`, `engine.restored.yaml` | Left by `syncd restore`: the download, the store it moved aside, and the backed-up configuration |
| `/var/lib/syncd/engine/ha/` | `swap.json` and `old-store`, only while an engine of a pair swaps a snapshot in; the received snapshot. From 0.5.0, on the active engine, `snapshots/` (the newest two snapshots as hard links to the store's files; `snapshot.tar.zst` only for a whole pull), and on the passive one `password-queue.replica` |
| `/var/lib/syncd/console/` | The console's master key (`master.key`; `master.key.previous` when `syncdctl restore --from` replaced a different one), until first used `initial-admin-password`, and the Git sync clone (`git/`) |
| `/var/lib/syncd/backups/` | Host-local backups, taken before every upgrade and on request. Root only |
| `/etc/syncd/pki/before-renewal-<time>/` | The certificates replaced by `syncdctl renew-certs`. Root only |
| `/run/syncd/control.sock` | The engine's control socket, when `api.control_socket` is set. `/run/syncd` exists while the engine runs |
| `/opt/syncd/current/share/config/console.env`, `engine.env` | The release's templates, with every setting commented (from 0.4.1, notes on lines of their own). Upgrades don't change yours |
| `/opt/syncd/current/share/nginx/syncd.conf` | An nginx configuration for HTTPS in front of the console |
| `/opt/syncd/current/share/monitoring/syncd-alerts.yaml` | Prometheus alerting rules |
| `/opt/syncd/current/share/doc/INSTALL.md` | The installation notes shipped with the release |
| `/etc/systemd/system/syncd*.service` (bundle), `/usr/lib/systemd/system/syncd*.service` (package) | The units. Change them with `systemctl edit`, not in place |
| `/usr/local/bin/` (bundle), `/usr/bin/` (package) | Links to `syncd`, `syncd-console` and `syncdctl` |

`engine.data_dir`, the `api` section, `engine.ha` and, from 0.5.0, `engine.require_signed_config`,
`engine.config_signing_keys` and `engine.license_file` of `engine.yaml` can only be changed on the
host: the engine refuses a configuration published from the console that changes them. Start a
configuration in the console by importing the running one, which copies them.

## Routine checklist

- **Daily:** `sudo syncd status`, or the console's engine page: no source `RETRYING` or `FAILED`,
  no unreachable target, no deletes or changes held for approval, no failed canary, no entities
  quarantined, dead letters not growing, no password failures or drops, `backups:` not `FAILED`,
  `password keys: sealed with the passphrase`, no agent bound to no source, `license:` not in
  `WARNING`, `GRACE` or `BLOCKED`, and for a pair one engine `ACTIVE` with a recent snapshot and
  the passive engine following the password queue. Alerts quiet.
- **After publishing a configuration:** `syncd status` shows the new `configuration version`, and
  the log has no `did not start`. `syncd_rule_errors_total` is not climbing. A publication in
  report mode: `syncd held <publication> --reason report` lists what it would do.
- **Weekly:** `sudo syncd dlq`, and fix or discard dead letters that keep failing. Look at expired
  groups and reviews due, ageing unmatched records, failing quality checks, and new unmanaged
  entries and privileged members (`sudo syncd unmanaged --last`). Check disk use of `/var/lib/syncd`
  and of PostgreSQL. Verify the console's audit chain (**Audit log** → **Verify chain**), against
  an `audit log head` line kept from the console's log.
- **Monthly:** `sudo syncdctl status` shows the certificates are not near expiry, and
  `sudo syncdctl license status` the license (the License page shows the managed identities
  against the tier). For a pair, move the active role once (stop the active engine cleanly) so a
  failover is known to work.
- **Once, after upgrading from 0.4.x:** install a license within the 60 days (see
  [Licensing](licensing.md)), opt in to strict control API access (`sudo syncdctl api-access
  strict`), and bind every capture agent to its source (`syncd password agent bind`).
- **Before an upgrade:** read the release notes (store format changes, `SYNCD_HEALTH_TIMEOUT`),
  and take an off-host backup.
- **Regularly:** keep the master key and the password-key passphrase off the host, apart from the
  backups; practise a restore (`syncdctl restore --from`) on a test host. Rotate the password key
  before `SyncdPasswordKeyOld` asks (`syncd password rotate-key`): nothing rotates it on a
  schedule.
