# Operator guide

This guide covers running syncd day to day: the services, starting and stopping them, checking
health, reading logs, the `syncd` and `syncdctl` commands, backups, monitoring, certificates and the
master key. [Running on Kubernetes](#running-on-kubernetes) covers the same for the Helm chart.
Installing and upgrading are in [Installation](install.md); what to do when something goes wrong
is in [Troubleshooting](troubleshooting.md).

## What runs where

| Process | systemd unit | Runs as | Listens on |
|---|---|---|---|
| Engine (`syncd run`) | `syncd.service` | `syncd` | Control API (gRPC, mutual TLS): `127.0.0.1:7443`. The control socket `/run/syncd/control.sock`, when `api.control_socket` is set. Prometheus metrics, when `api.metrics_listen` is set (for example `127.0.0.1:9464`). The listeners of any `debezium` connectors |
| Console (`syncd-console serve`) | `syncd-console.service` | `syncd-console` | `127.0.0.1:8080` (`CONSOLE_LISTEN`): plain HTTP, or HTTPS when given `CONSOLE_TLS_CERT` and `CONSOLE_TLS_KEY` |
| nginx *(optional, you install it)* | `nginx.service` | nginx | 443 (HTTPS) and 80 (redirect), in front of the console |
| PostgreSQL | your PostgreSQL service | postgres | The console's database. On this host or elsewhere |
| JDBC bridge, Debezium Server *(optional)* | yours | yours | See [Installation](install.md#the-jdbc-bridge) |

There is one engine per host, and it is the only writer of its store, `/var/lib/syncd/engine`. The
console reaches it only through the control API. The console's unit starts after `syncd.service`
and `postgresql.service`.

Both units run with systemd's sandboxing: `ProtectSystem=strict`, `ProtectHome`, `PrivateTmp`,
`NoNewPrivileges`, no capabilities, a system-call filter, and write access only to their own
directories (`/var/lib/syncd/engine` and `/etc/syncd/engine` for the engine, and `/run/syncd`,
which its unit creates and removes when it stops; `/var/lib/syncd/console` for the console). The engine may use 80% of the host's memory
(`MemoryMax=80%`) and 65 536 open files.

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
  default start limit (5 starts in 10 seconds) still stops a real crash loop. From 0.3.0,
  `syncdctl` clears that limit (`systemctl reset-failed`) before its own starts and restarts, so
  an upgrade followed by `renew-certs` no longer fails with `Start request repeated too quickly`.
  After a crash loop, clear it yourself with `systemctl reset-failed syncd` once the cause is
  fixed.
- **Stopping the engine is safe.** It stops between pages, within seconds; systemd waits up to 90
  seconds (`TimeoutStopSec=90`). A full sync in progress resumes where it stopped at the next
  start, and each source carries on from its last committed checkpoint.
- **What needs a restart.** Changes to `console.env` and `engine.env`, and hand edits to
  `engine.yaml`. A configuration published from the console needs none: the engine reloads with it
  (see [Admin guide](admin-guide.md)). `engine.cache_mb` takes effect only when syncd restarts.

`syncdctl status` prints:

```text
release:   0.3.0
previous:  0.2.1
engine:    active, control API answers
store:     format 1 (syncd 0.3.0 reads up to 1)
console:   active, http://127.0.0.1:8080 answers
certificates: valid until Nov  4 10:15:00 2036 GMT (ca.crt expires first)
users:     https://syncd.example.com
backups:
  /var/lib/syncd/backups/20261107T101500Z-upgrade-0.2.1-to-0.3.0
```

## Health checks

| Check | Answers |
|---|---|
| `curl -s http://127.0.0.1:8080/healthz` | `ok` while the console runs. No sign-in needed. It does not check the database or the engine |
| `sudo syncd status` | The engine answers on its control API, with the state of each source and publication. It always answers, even while the engine is busy |
| `sudo syncdctl status` | Both of the above, as `syncdctl` checks them during installs and upgrades |
| `GET /metrics` | The engine answers scrapes, when `api.metrics_listen` is set. `up{job="syncd"}` in Prometheus |
| The console's engine page | The same status as `syncd status`, live |

`certificates:` names the control API certificate that expires first, and from 60 days before
says `EXPIRES WITHIN 60 DAYS, renew with syncdctl renew-certs` (see [Certificates](#certificates)).

`syncd status` shows, for each source, its mode, state (`starting`, `reading`, `streaming`, `idle`,
`RETRYING`, `FAILED`), the changes received, its checkpoint, restarts, the next try and the last
error. For each publication it shows its target and dead letters, and the last drift check. Then
any unreachable target, deletes held back by a threshold, what the engine is busy with, and
running totals. From 0.3.0 it also shows:

- **the engine process**, on one line: memory now, its peak and the limit (when there is one),
  CPU time and its average since the engine started, threads and open files. For example
  `process: memory 200.0 MiB (peak 300.0 MiB, limit 2.0 GiB)  cpu 30.0s (12.5% of 4 CPUs since
  start)  12 threads  40 open files`;
- **quarantined entities**, per view (`view people: 3 quarantined (exports held back by a failing
  rule)`), followed by up to 20 of them with the rule and its error (see
  [Quarantined entities](#quarantined-entities)).

## Logs

Both services log to the journal:

```bash
journalctl -u syncd -u syncd-console -f
journalctl -u syncd -e                         # the engine's log, newest last
journalctl -u syncd -g "did not start" -A 3    # why a published configuration rolled back
journalctl -k -g oom                           # the kernel stopped syncd for memory
```

The level is `info`. To change it, set `RUST_LOG` in the unit (`systemctl edit syncd`, then
`Environment=RUST_LOG=debug` under `[Service]`) and restart. The console leaves out PostgreSQL's
notices below warnings.

Useful engine log messages:

| Message | Meaning |
|---|---|
| `store opened` (with `format`) | The engine opened its store at startup |
| `migrating the store`, `store migrated` | An upgrade migrates the store to a newer format |
| `metrics listening` | The metrics listener is up |
| `control request` (with `rpc` and `caller`) | A command through the control API, and who sent it |
| `revealing sensitive values` / `refused a request to reveal sensitive values` | A caller asked for sensitive attribute values |
| `source failed; restarting it from its checkpoint` | A source failed and will be retried |
| `export target is unreachable; failed exports wait in the dead-letter queue` | A target directory went away |
| `connected to the directory again` | It came back |
| `exceeds the deletion threshold` | A deletion threshold holds deletes back |
| `the new configuration did not start; restoring the previous one` | A published configuration rolled back |
| `engine.cache_mb takes effect when syncd restarts` | A published configuration changed `cache_mb` |
| `an entry moved or renamed in the target was put back where the publication places it`, `... was found by its id` | Someone moved or renamed a managed entry in the target; the engine found it by the target's id and put it back |
| `rename blocked by an entry this batch renames away; parked until it has moved` | Two entries swap names, or a chain of renames, in one batch |
| `the entry's RDN is taken where it is moved aside; it was given one of its own` | A leaver moved aside into a container where its RDN was taken |
| `container renamed in the source; its entries were read again` | An LDAP source's container was renamed (`container_check_secs`) |
| `removed empty container` / `could not remove an empty container` | `remove_empty_containers` removed a container syncd created |
| `the publication lacked tables the object follows; added them` | A PostgreSQL source completed its publication with `ALTER PUBLICATION ... ADD TABLE` |
| `configuration file created from the initial one` | A container's first start copied `--initial-config` to the data volume |

Useful console log messages:

| Message | Meaning |
|---|---|
| `console listening` (with `https`) | The console is up, over HTTPS when `https=true` |
| `created a new master key for stored secrets; back it up` | The first start created `master.key` |
| `the master key does not open the stored secrets: engines registered with mTLS must be registered again` | `CONSOLE_NEW_MASTER_KEY=true` started the console with a key that does not open the stored secrets |
| `audit log head` (with `id`, `at`, `hash`) | The newest audit entry, logged at start and every hour when it moved. Keep these lines outside the database (the journal, a SIEM): they let **Verify chain** find entries removed from the end of the log |
| `TLS certificate loaded again` / `the changed TLS certificate does not load; keeping the previous one` | The console's own HTTPS certificate changed on disk |
| `the console listens on ... over plain HTTP: browsers on other machines will not keep its session cookie` | It listens beyond loopback over plain HTTP, without an HTTPS `CONSOLE_PUBLIC_URL` |
| `development mode: do not use this console in production` | `SYNC_DEV_MODE=true` |
| `single sign-on could not start` | The identity provider could not be reached or is misconfigured |

The console's own audit log (sign-ins, account changes, publishes, reveals and engine commands) is
in its database, not in the journal; see [Security](security.md).

## The syncd command line

`syncd` on the host works without flags. Run it as root, or as a member of the `syncd` group.

- **Configuration.** Without `-c` (or `SYNCD_CONFIG`), `syncd` uses `./syncd.yaml` if there is
  one, else `/etc/syncd/engine/engine.yaml`.
- **Secrets.** With the packaged configuration, it reads `/etc/syncd/engine.env` as the service
  does, so `test-connection` and `discover` find the passwords the configuration refers to.
  Variables already set win, and `--env-file` names another file. `SYNCD_FIPS_MODE=true` in that
  file turns on `--fips`.
- **Control API.** Commands that talk to the running engine use the control socket when the
  configuration sets `api.control_socket` and the socket exists. Otherwise they use mutual TLS
  with the command line's client certificate, `/etc/syncd/pki/cli.crt` and `cli.key`. Install and
  upgrade issue it, `syncdctl renew-certs` renews it, and `sudo syncdctl cli-cert` issues it on a
  host that lacks one.

### Commands

| Command | What it does | Needs the running engine |
|---|---|---|
| `syncd status` | The running engine's status | Yes |
| `syncd quarantine` | Lists the entities whose exports a failing rule (`on_error: quarantine`) holds back, one per line: view, entity id, `since=` (microseconds since the epoch), the computed attribute and its error. `no quarantined entities` when there are none. Reads the store directly, read-only. New in 0.3.0 | No (works while it runs) |
| `syncd dlq` | Lists failed exports waiting to be retried: publication, entity id, attempts, last failure, DN, error. Reads the store directly, read-only | No (works while it runs) |
| `syncd retry [--publication P] [--entity ID]` | Exports dead-lettered entities again now: all, one publication's, or one entity (as `syncd dlq` lists it) | Yes |
| `syncd discard --publication P --entity ID` | Removes a dead letter without retrying it | Yes |
| `syncd reconcile [--publication P]` | Reconciles a publication (or all) with its target now | Yes |
| `syncd full-sync` | Recomputes every entity from its sources (after a rule change), then exports | Yes |
| `syncd approve "<scope>"` | Lets deletes held back by a deletion threshold through once, with the scope exactly as `syncd status` shows it, e.g. `syncd approve "publication people_out"` | Yes |
| `syncd check-drift [--publication P]` | Reads a publication's entries (or every checked publication's) back from the target now, and acts on drift by its `drift` policy | Yes |
| `syncd apply-config FILE [--version LABEL]` | Sends a new configuration to the engine, which validates it, saves it over its file and reloads, then runs a full sync. The label is shown by `syncd status` (default: the time) | Yes |
| `syncd simulate FILE [--samples N] [--reveal]` | What applying a configuration would send each target, without applying it: the engine dry-runs it on a copy of its state. Lists `N` operations per publication (default 20) | Yes |
| `syncd preview FILE [--view V] [--samples N] [--random] [--live] [--reveal]` | Runs a configuration over a sample of source records (default 10, at most 100, of each view's first source) in a scratch store, and shows the entities and what each publication would write. `--live` reads the sources now instead of the engine's copies | Yes |
| `syncd validate` | Checks the configuration and compiles every expression, against a throwaway store | No |
| `syncd test` | Runs the test cases attached to computed attributes | No |
| `syncd test-connection CONNECTOR` | Connects with a connector (and binds, for a directory) and shows what answered. Writes nothing | No |
| `syncd discover CONNECTOR [CONTAINER] [--object O] [--samples N] [--random] [--reveal]` | Lists what a connector holds (tables, collections, directory entries); with a container (`schema.table`, a collection, a DN), its fields and a few records (default 5, at most 100). `--object` reads a configured object's records as the engine reads them. Writes nothing | No |
| `syncd store-format [--supported]` | Prints the store's format (`none` without a store); `--supported`, the newest format this `syncd` reads. Works while the engine runs | No |
| `syncd run [--once]` | Runs the engine. This is what the service runs; `--once` does one full pass (read every source, export, reconcile) and exits | Not with the service running: it holds the store |

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
| `--data-dir DIR` | `SYNCD_DATA_DIR` | Overrides `engine.data_dir`. The service passes `--data-dir /var/lib/syncd/engine` |
| `--initial-config FILE` | `SYNCD_INITIAL_CONFIG` | When the configuration file does not exist yet, creates it as a copy of this one; an existing file is never replaced. The container image uses it to start `/var/lib/syncd/engine.yaml` on the data volume from the image's `/etc/syncd/engine.yaml`. New in 0.3.0 |
| `--fips` | `SYNCD_FIPS_MODE` | Uses the FIPS 140-3 crypto provider. Needs a FIPS build |
| `--version`, `--help` | | |

### Control API options

For `status`, `reconcile`, `full-sync`, `approve`, `check-drift`, `retry`, `discard`,
`apply-config`, `simulate` and `preview`:

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

From 0.3.0 the engine's unit creates `/run/syncd` (`RuntimeDirectory=syncd`, mode 0750) and
removes it when the engine stops; before, the engine could not create the socket there. `syncd`
commands run as root then use the socket. A host installed before 0.3.0 keeps its `engine.yaml`,
without the commented line: add it. The container image sets it, for `kubectl exec`.

## syncdctl commands

`syncdctl` installs, upgrades, backs up and rolls back syncd. Run it as root.

| Command | Options | What it does |
|---|---|---|
| `install` | `--local-database` or `--database-url URL`; `--listen ADDR`, `--fips`, `--no-start` | First installation ([Installation](install.md#syncdctl-install-options)) |
| `upgrade RELEASE` | `--yes`, `--no-rollback`, `--skip-db-backup`, `--reinstall` | Upgrades a bundle install to a `.tar.gz` or unpacked release ([Upgrade](install.md#upgrade)) |
| `rollback` | `--keep-data`, `--yes` | Back to the previous release, restoring the backup taken before the upgrade ([Rollback](install.md#rollback)) |
| `backup` | `--skip-db-backup`, `--leave-stopped` | Stops syncd briefly to take a consistent backup; prints its directory. `--leave-stopped` leaves the services stopped afterwards |
| `restore DIR` | `--yes` | Restores a backup: engine store, console files and database, `/etc/syncd` |
| `status` | | Releases, services, health, store format, when the control API's certificates expire, public URL, backups |
| `renew-certs` | `--new-ca`, `--yes` | Renews the control API's certificates, restarts the engine with them and gives the console its new credentials (see [Certificates](#certificates)). New in 0.3.0 |
| `cli-cert` | | Issues the command line's client certificate, if it is missing |
| `uninstall` | `--purge`, `--yes` | Removes syncd ([Uninstall](install.md#uninstall)) |
| `version`, `help` | | |

Environment variables it reads:

| Variable | Default | |
|---|---|---|
| `SYNCD_HEALTH_TIMEOUT` | 120 | Seconds to wait for both services to be healthy |
| `SYNCD_KEEP_BACKUPS` | 3 | Backups kept after an upgrade |
| `SYNCD_PG_SOCKET`, `SYNCD_PG_PORT` | `/var/run/postgresql`, 5432 | Where `--local-database` reaches PostgreSQL |

`upgrade`, `rollback` and `uninstall` refuse to run on a host installed from a package; use the
package manager there. Only one `syncdctl` that changes something runs at a time
(`/run/syncdctl.lock`).

## Backups

```bash
sudo syncdctl backup                  # stops syncd briefly; prints the backup's directory
sudo syncdctl restore /var/lib/syncd/backups/20261107T101500Z-manual-0.3.0
```

A backup is a directory under `/var/lib/syncd/backups`, readable by root only, named
`<UTC time>-manual-<version>` or `<UTC time>-upgrade-<from>-to-<to>`. It holds:

| Part | From |
|---|---|
| `engine/` | The engine store, `/var/lib/syncd/engine` (copied with reflinks where the file system supports them) |
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
  (`pg_restore --clean --single-transaction`), and starts and checks the services. It warns when
  the backup was taken with another version, and refuses a backup whose store format the installed
  release cannot read.
- **Protect copies.** A backup holds the console's master key and the secrets in `/etc/syncd`.
  Copy backups off the host for disaster recovery, and protect the copies accordingly.
- **Retention.** Upgrades keep the newest three backups (`SYNCD_KEEP_BACKUPS`), manual ones
  included. Nothing schedules backups: run `syncdctl backup` from a timer of your own if you want
  them regularly.

What else to keep, and how to recover on a new host, is in
[Sizing, availability and recovery](availability-and-sizing.md#disaster-recovery).

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
stops the engine from starting.

| Metric | Meaning |
|---|---|
| `syncd_source_up{source,mode}` | 1 while a source reads, streams or waits for its next read |
| `syncd_source_state{source,state}` | 1 for the source's current state (`retrying` after a failure) |
| `syncd_source_restarts_total{source,mode}` | Restarts after failures |
| `syncd_source_events_total{source,mode}` | Changes received |
| `syncd_source_last_event_timestamp_seconds{source,mode}` | When it last sent a change |
| `syncd_source_last_checkpoint_timestamp_seconds{source,mode}` | When its position was last committed |
| `syncd_source_failures_total` | Source failures |
| `syncd_target_up{target}` | 0 from an export that failed for lack of a connection until the target answers again |
| `syncd_target_down_since_timestamp_seconds{target}` | When an unreachable target went down |
| `syncd_publication_dead_letters{publication,target}` | Failed exports waiting |
| `syncd_deletes_held{scope}` | Deletes held back by a threshold, waiting for approval |
| `syncd_threshold_blocks_total` | Threshold holds started (a hold still in place is not counted again) |
| `syncd_engine_busy`, `syncd_engine_busy_seconds` | A long operation runs (full sync, reconciliation, reload), and for how long; commands are refused meanwhile |
| `syncd_view_quarantined{view}` | Entities whose exports a failing rule (`on_error: quarantine`) holds back, per view (0 for a view with none). New in 0.3.0 |
| `process_resident_memory_bytes`, `syncd_memory_limit_bytes` | Resident memory, and the cgroup's limit (`MemoryMax`, or a container's memory limit) when one is set |
| `process_cpu_seconds_total`, `process_threads`, `process_open_fds` | CPU time used, user and system (its rate is the CPUs in use), threads and open files. New in 0.3.0 |
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
minutes. That history lasts while the page is open; for longer, scrape `/metrics`.

### Alert rules

Release bundles and packages ship Prometheus alerting rules in
`/opt/syncd/current/share/monitoring/syncd-alerts.yaml`. Load the file from your Prometheus
configuration's `rule_files`, and check it with `promtool check rules syncd-alerts.yaml`. The rules
assume the scrape job is called `syncd`; change `job="syncd"` in them if yours is not.

| Alert | Severity | Fires when |
|---|---|---|
| `SyncdEngineDown` | critical | The engine has not answered scrapes for 5 minutes |
| `SyncdSourceDown` | critical | A source has not been running for 10 minutes |
| `SyncdSourceFlapping` | warning | A source restarted more than 3 times in an hour |
| `SyncdSourceStalled` | critical | A source sent changes, but nothing has been committed for 15 minutes (full reads aside) |
| `SyncdTargetDown` | critical | An export target has been unreachable for 5 minutes |
| `SyncdDeadLetters` | warning | Failed exports have been waiting for 30 minutes |
| `SyncdDeletesHeld` | warning | A deletion threshold holds deletes back |
| `SyncdQuarantined` | warning | A view has had quarantined entities for 30 minutes (new in 0.3.0) |
| `SyncdRuleErrors` | warning | Rules failed in the last 30 minutes |
| `SyncdEngineBusy` | warning | One long operation has run for over an hour |
| `SyncdMemoryNearLimit` | warning | Resident memory has been above 90% of the cgroup's limit for 10 minutes |

The thresholds are starting points: tune `for:` and the ages to how often your sources change and
how long your full syncs take. A quiet source commits rarely (syncrepl only sends a new position
with a change), so `SyncdSourceStalled` looks for changes received since the last commit rather
than for an old commit alone. PostgreSQL replication slot retention isn't an engine metric; watch
it with `postgres_exporter` (`pg_replication_slots`). What to do about each alert is in
[Troubleshooting](troubleshooting.md).

## Quarantined entities

A computed attribute with `on_error: quarantine` holds an entity's exports back when it fails for
that entity, rather than writing a wrong value (see [Rules](rules.md)). From 0.3.0 the hold is
kept in the store, with the attribute, its error and since when, so a restart keeps it. It ends
by itself once the rule evaluates again for the entity.

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
| `cli.crt`, `cli.key` | The command line's client certificate (`CN=syncd command line`) | Key: root and the `syncd` group |

All are ECDSA P-256 and valid for **10 years** from installation. The engine's configuration names
`engine.crt`, `engine.key` and `ca.crt` (as the client CA) in its `api.tls` section. Upgrades do
not reissue certificates that exist.

**Expiry.** `sudo syncdctl status` shows when the first of them expires, and warns within 60 days.
The console's engine page warns too, when its client certificate for the engine has less than 60
days left, and once it has expired (then the console cannot reach the engine).

**Renewal** (new in 0.3.0):

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
   which the audit log records as `engine.update`;
5. restarts the console and checks both services, then prints when the new certificates expire.

The services are down for a few seconds. Without `--new-ca`, it warns when the CA itself expires
within 400 days, since certificates from it cannot outlive it. It needs the CA's key
(`/etc/syncd/pki/ca.key`): certificates syncdctl did not issue are refused. It renews the
installed `local` engine only: an engine registered in another console, or under another name,
must be registered there again with `--update`.

The certificate browsers see is yours to renew: nginx's (`/etc/nginx/tls/`), or the console's own
(`CONSOLE_TLS_CERT`), which it picks up within a minute.

## The master key

The console encrypts the secrets it stores (today, the client keys of registered engines) with
envelope encryption: AES-256-GCM, a data key per secret, wrapped by a master key. The master key is
`CONSOLE_MASTER_KEY` (base64, 32 bytes) if set, else `/var/lib/syncd/console/master.key`, created
(mode 0600) at the first start, which logs `created a new master key for stored secrets; back it
up`.

- **Back it up** apart from the database: a database dump without the key cannot be decrypted.
  `syncdctl backup` includes it, so treat backups as secrets.
- **The console checks it at every start** (from 0.3.0) against a secret its database holds. It
  refuses to start, and says to restore the key, when:
    - `master.key` is missing while the database holds sealed secrets (a restore that missed it,
      a new data directory). Before 0.3.0 it created a new key over them;
    - the key, from the file or `CONSOLE_MASTER_KEY`, does not open them.
- **If it is lost for good**, start the console with a new key on purpose:
  `CONSOLE_NEW_MASTER_KEY=true` in `/etc/syncd/console.env` (or `--new-master-key`), then restart.
  The stored secrets are lost, and the console logs that engines registered with mTLS must be
  registered again: register each one again with `syncd-console admin register-engine --update`
  (for the installed engine, `sudo syncdctl renew-certs` does it), or remove it and register it in
  **Engines**. Then remove the setting, so that a later lost key is refused again.
- There is no key rotation command.
- **On Kubernetes** the key is in the `syncd-console` Secret (see
  [Running on Kubernetes](#running-on-kubernetes)).

## Running on Kubernetes

With the [Helm chart](install.md#install-with-helm), the engine runs in the pod `syncd-engine-0`
(for a release named `syncd`) and the console in the Deployment `syncd-console`. There is no
systemd and no `syncdctl`: Kubernetes restarts the pods, and Helm installs, upgrades and removes
them.

| | Engine | Console |
|---|---|---|
| Kind | StatefulSet, one replica | Deployment (`console.replicas`, 1) |
| Ports | 7443 control API (mutual TLS), 9464 metrics (plain HTTP); Service `syncd-engine` | 8080 plain HTTP; Service `syncd-console` |
| Probes | Startup: the control API accepts connections (every 5 s, up to 10 minutes, for opening a large store). Readiness: the same. Liveness: `GET /metrics` on 9464 | `GET /healthz`, readiness and liveness |
| Volumes | `data` (the PersistentVolumeClaim) at `/var/lib/syncd`; the `syncd-pki` Secret at `/etc/syncd/pki`; `/run/syncd` (memory) for the control socket; `/tmp` | `/var/lib/syncd/console` and `/tmp`, both `emptyDir` |
| Stopping | 90 s to finish its batch (`engine.terminationGracePeriodSeconds`) | |

### The command line

The image has no shell, but `syncd` is its entry point and finds its configuration
(`SYNCD_CONFIG`) and store by itself. Commands reach the running engine through its control
socket, without certificates:

```bash
kubectl -n syncd exec syncd-engine-0 -- syncd status
kubectl -n syncd exec syncd-engine-0 -- syncd quarantine
kubectl -n syncd exec syncd-engine-0 -- syncd dlq
kubectl -n syncd exec syncd-engine-0 -- syncd retry --publication people_out
kubectl -n syncd exec syncd-engine-0 -- syncd reconcile
kubectl -n syncd exec syncd-engine-0 -- syncd test-connection hr_db
```

Every command in [The syncd command line](#the-syncd-command-line) works this way, with the
secrets from `engine.envFromSecret` in the pod's environment. The console's own commands run in
its pod: `kubectl -n syncd exec deploy/syncd-console -- syncd-console admin reset-password --user
admin`.

Logs go to the containers' standard output: `kubectl -n syncd logs syncd-engine-0`,
`kubectl -n syncd logs deploy/syncd-console`. Set `RUST_LOG` in `engine.env` or `console.env`.

### The engine's configuration file

The engine's configuration is on its volume, `/var/lib/syncd/engine.yaml`. At the first start it is
a copy of the image's `/etc/syncd/engine.yaml` (`SYNCD_INITIAL_CONFIG`), which has no sources;
after that the console replaces it when it publishes a configuration. The `engine` and `api`
sections stay as the image sets them, and the engine refuses a published configuration that
changes them:

| Setting | In the image |
|---|---|
| `engine.data_dir` | `/var/lib/syncd/engine` |
| `api.listen` | `0.0.0.0:7443`, with `api.tls` from `/etc/syncd/pki` (`engine.crt`, `engine.key`, `ca.crt` as the client CA) |
| `api.control_socket` | `/run/syncd/control.sock` |
| `api.metrics_listen` | `0.0.0.0:9464` |

### Monitoring on Kubernetes

Metrics are always on, on port 9464 (`/metrics`, Service port `metrics`). With the Prometheus
Operator, set `engine.metrics.serviceMonitor.enabled=true`, with the `labels` your Prometheus
selects ServiceMonitors by. It scrapes every 30 seconds (`interval`).

The chart has no alert rules. Load `deploy/packaging/monitoring/syncd-alerts.yaml` from the source
repository (the same file the packages ship) into Prometheus, or wrap its `groups` in a
PrometheusRule. The rules match `job="syncd"`, but a ServiceMonitor's job is the Service's name,
`syncd-engine` for a release named `syncd`: change `job="syncd"` in the rules to match, or relabel
the job.

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
(`syncd control API CA`), valid for `pki.days` (3650, ten years). The engine's certificate names
the engine's Service (`syncd-engine`, `syncd-engine.<namespace>`, `.svc`, `.svc.cluster.local`) and
`localhost`. There is no command-line certificate: `kubectl exec` uses the control socket.

The console's engine page warns 60 days before the console's client certificate expires. To issue
new ones, delete the Secret and upgrade:

```bash
kubectl -n syncd delete secret syncd-pki
helm upgrade syncd oci://ghcr.io/winllc/charts/syncd --version X.Y.Z -n syncd -f syncd-values.yaml
```

The upgrade issues a new CA and new certificates. The engine pod restarts with them (its template
carries a checksum of the Secrets), and the registration Job gives the console its new credentials
(`register-engine --update`). `syncdctl renew-certs` does not apply here.

### The master key Secret

The `syncd-console` Secret holds the console's master key (`master-key`), which seals the
engine's client key in the console's database, with the initial admin password. It is generated
at install, kept by upgrades, and kept by `helm uninstall` (`helm.sh/resource-policy: keep`).

- **Back it up** with the database: `kubectl -n syncd get secret syncd-console -o yaml`, stored as
  a secret.
- **If it is deleted**, the next `helm upgrade` generates a new key, and the console refuses to
  start, saying the master key does not open the secrets the database holds. Put the old Secret
  back (`kubectl apply` the backup), then restart the console
  (`kubectl -n syncd rollout restart deploy/syncd-console`).
- **If it is lost for good**, add `CONSOLE_NEW_MASTER_KEY=true` to `console.env` and upgrade. The
  console then starts, but the registration Job does not: it gets only the database URL and the
  master key, not `console.env`, so it still refuses the key and fails. In the console, remove the
  engine under **Engines** (this also removes its configuration versions in the console; export
  the configuration first), then remove `CONSOLE_NEW_MASTER_KEY` and run `helm upgrade` again.
  With no sealed secrets left, the Job registers the engine afresh.

## Files and paths

| Path | What |
|---|---|
| `/opt/syncd/releases/<version>/` | One directory per installed release. Never edited |
| `/opt/syncd/current`, `previous` | Symlinks to the running release and the one before it |
| `/etc/syncd/console.env` | The console's settings: database URL, listen address, proxy, single sign-on. Readable by root and `syncd-console` only |
| `/etc/syncd/engine.env` | Secrets that engine configurations refer to (`password: { env: NAME }`), and `SYNCD_FIPS_MODE`. Readable by root and `syncd` only |
| `/etc/syncd/engine/engine.yaml` | The engine's configuration. Publishing a configuration in the console replaces it |
| `/etc/syncd/pki/` | The control API's CA and certificates (see [Certificates](#certificates)) |
| `/var/lib/syncd/engine/` | The engine's store |
| `/var/lib/syncd/console/` | The console's master key (`master.key`) and, until first used, `initial-admin-password` |
| `/var/lib/syncd/backups/` | Backups, taken before every upgrade and on request. Root only |
| `/etc/syncd/pki/before-renewal-<time>/` | The certificates replaced by `syncdctl renew-certs`. Root only |
| `/run/syncd/control.sock` | The engine's control socket, when `api.control_socket` is set. `/run/syncd` exists while the engine runs |
| `/opt/syncd/current/share/nginx/syncd.conf` | An nginx configuration for HTTPS in front of the console |
| `/opt/syncd/current/share/monitoring/syncd-alerts.yaml` | Prometheus alerting rules |
| `/opt/syncd/current/share/doc/INSTALL.md` | The installation notes shipped with the release |
| `/etc/systemd/system/syncd*.service` (bundle), `/usr/lib/systemd/system/syncd*.service` (package) | The units. Change them with `systemctl edit`, not in place |
| `/usr/local/bin/` (bundle), `/usr/bin/` (package) | Links to `syncd`, `syncd-console` and `syncdctl` |

`engine.data_dir` and the `api` section of `engine.yaml` can only be changed on the host: the
engine refuses a configuration published from the console that changes them. Start a configuration
in the console by importing the running one, which copies them.

## Routine checklist

- **Daily:** `sudo syncd status`, or the console's engine page: no source `RETRYING` or `FAILED`,
  no unreachable target, no deletes held back, no entities quarantined, dead letters not growing.
  Alerts quiet.
- **After publishing a configuration:** `syncd status` shows the new `configuration version`, and
  the log has no `did not start`. `syncd_rule_errors_total` is not climbing.
- **Weekly:** `sudo syncd dlq`, and fix or discard dead letters that keep failing. Check disk use
  of `/var/lib/syncd` and of PostgreSQL. Verify the console's audit chain (**Audit log** →
  **Verify chain**), against an `audit log head` line kept from the console's log.
- **Monthly:** `sudo syncdctl status` shows the certificates are not near expiry.
- **Before an upgrade:** read the release notes (store format changes, `SYNCD_HEALTH_TIMEOUT`),
  and take and copy off a backup.
- **Regularly:** copy backups and the master key off the host; practise a restore on a test host.
