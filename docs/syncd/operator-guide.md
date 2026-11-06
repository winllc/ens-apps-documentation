# Operator guide

This guide covers running syncd day to day: the services, starting and stopping them, checking
health, reading logs, the `syncd` and `syncdctl` commands, backups, monitoring, certificates and the
master key. Installing and upgrading are in [Installation](install.md); what to do when something
goes wrong is in [Troubleshooting](troubleshooting.md).

## What runs where

| Process | systemd unit | Runs as | Listens on |
|---|---|---|---|
| Engine (`syncd run`) | `syncd.service` | `syncd` | Control API (gRPC, mutual TLS): `127.0.0.1:7443`. Prometheus metrics, when `api.metrics_listen` is set (for example `127.0.0.1:9464`). The listeners of any `debezium` connectors |
| Console (`syncd-console serve`) | `syncd-console.service` | `syncd-console` | `127.0.0.1:8080`, plain HTTP (`CONSOLE_LISTEN`) |
| nginx *(you install it)* | `nginx.service` | nginx | 443 (HTTPS) and 80 (redirect), in front of the console |
| PostgreSQL | your PostgreSQL service | postgres | The console's database. On this host or elsewhere |
| JDBC bridge, Debezium Server *(optional)* | yours | yours | See [Installation](install.md#the-jdbc-bridge) |

There is one engine per host, and it is the only writer of its store, `/var/lib/syncd/engine`. The
console reaches it only through the control API. The console's unit starts after `syncd.service`
and `postgresql.service`.

Both units run with systemd's sandboxing: `ProtectSystem=strict`, `ProtectHome`, `PrivateTmp`,
`NoNewPrivileges`, no capabilities, a system-call filter, and write access only to their own
directories (`/var/lib/syncd/engine` and `/etc/syncd/engine` for the engine,
`/var/lib/syncd/console` for the console). The engine may use 80% of the host's memory
(`MemoryMax=80%`) and 65 536 open files.

## Start, stop and status

```bash
sudo systemctl start syncd syncd-console
sudo systemctl stop syncd-console syncd
sudo systemctl restart syncd               # after editing engine.env or engine.yaml by hand
sudo systemctl restart syncd-console       # after editing console.env
systemctl status syncd syncd-console
sudo syncdctl status                       # releases, services, health, store format, backups
sudo syncd status                          # what the engine is doing
```

- **Restarts.** Both units restart on failure after 5 seconds (`Restart=on-failure`).
- **Stopping the engine is safe.** It stops between pages, within seconds; systemd waits up to 90
  seconds (`TimeoutStopSec=90`). A full sync in progress resumes where it stopped at the next
  start, and each source carries on from its last committed checkpoint.
- **What needs a restart.** Changes to `console.env` and `engine.env`, and hand edits to
  `engine.yaml`. A configuration published from the console needs none: the engine reloads with it
  (see [Admin guide](admin-guide.md)). `engine.cache_mb` takes effect only when syncd restarts.

`syncdctl status` prints:

```text
release:   0.2.1
previous:  0.2.0
engine:    active, control API answers
store:     format 1 (syncd 0.2.1 reads up to 1)
console:   active, http://127.0.0.1:8080 answers
users:     https://syncd.example.com
backups:
  /var/lib/syncd/backups/20261024T101500Z-upgrade-0.2.0-to-0.2.1
```

## Health checks

| Check | Answers |
|---|---|
| `curl -s http://127.0.0.1:8080/healthz` | `ok` while the console runs. No sign-in needed. It does not check the database or the engine |
| `sudo syncd status` | The engine answers on its control API, with the state of each source and publication. It always answers, even while the engine is busy |
| `sudo syncdctl status` | Both of the above, as `syncdctl` checks them during installs and upgrades |
| `GET /metrics` | The engine answers scrapes, when `api.metrics_listen` is set. `up{job="syncd"}` in Prometheus |
| The console's engine page | The same status as `syncd status`, live |

`syncd status` shows, for each source, its mode, state (`starting`, `reading`, `streaming`, `idle`,
`RETRYING`, `FAILED`), the changes received, its checkpoint, restarts, the next try and the last
error. For each publication it shows its target and dead letters, and the last drift check. Then
any unreachable target, deletes held back by a threshold, what the engine is busy with, and
running totals.

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

Useful console log messages:

| Message | Meaning |
|---|---|
| `console listening` | The console is up |
| `created a new master key for stored secrets; back it up` | The first start created `master.key` |
| `the console listens on ... over plain HTTP: browsers on other machines will not keep its session cookie` | It listens beyond loopback without an HTTPS `CONSOLE_PUBLIC_URL` |
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
- **Control API.** Commands that talk to the running engine use the command line's client
  certificate, `/etc/syncd/pki/cli.crt` and `cli.key`. Install and upgrade issue it, and
  `sudo syncdctl cli-cert` issues it on a host that lacks one.

### Commands

| Command | What it does | Needs the running engine |
|---|---|---|
| `syncd status` | The running engine's status | Yes |
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

`api.control_socket` (a Unix socket, mode 0600, serving the whole API to the engine's own user) is
an engine setting; the installed configuration does not set it, and uses mutual TLS instead.

## syncdctl commands

`syncdctl` installs, upgrades, backs up and rolls back syncd. Run it as root.

| Command | Options | What it does |
|---|---|---|
| `install` | `--local-database` or `--database-url URL`; `--listen ADDR`, `--fips`, `--no-start` | First installation ([Installation](install.md#syncdctl-install-options)) |
| `upgrade RELEASE` | `--yes`, `--no-rollback`, `--skip-db-backup`, `--reinstall` | Upgrades a bundle install to a `.tar.gz` or unpacked release ([Upgrade](install.md#upgrade)) |
| `rollback` | `--keep-data`, `--yes` | Back to the previous release, restoring the backup taken before the upgrade ([Rollback](install.md#rollback)) |
| `backup` | `--skip-db-backup`, `--leave-stopped` | Stops syncd briefly to take a consistent backup; prints its directory. `--leave-stopped` leaves the services stopped afterwards |
| `restore DIR` | `--yes` | Restores a backup: engine store, console files and database, `/etc/syncd` |
| `status` | | Releases, services, health, store format, public URL, backups |
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
sudo syncdctl restore /var/lib/syncd/backups/20261024T101500Z-manual-0.2.1
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
| `process_resident_memory_bytes`, `syncd_memory_limit_bytes` | Resident memory, and the cgroup's limit (`MemoryMax`) when one is set |
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

The console has no metrics endpoint; watch it with `/healthz`.

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
| `SyncdRuleErrors` | warning | Rules failed in the last 30 minutes |
| `SyncdEngineBusy` | warning | One long operation has run for over an hour |
| `SyncdMemoryNearLimit` | warning | Resident memory has been above 90% of the cgroup's limit for 10 minutes |

The thresholds are starting points: tune `for:` and the ages to how often your sources change and
how long your full syncs take. A quiet source commits rarely (syncrepl only sends a new position
with a change), so `SyncdSourceStalled` looks for changes received since the last commit rather
than for an old commit alone. PostgreSQL replication slot retention isn't an engine metric; watch
it with `postgres_exporter` (`pg_replication_slots`). What to do about each alert is in
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
`engine.crt`, `engine.key` and `ca.crt` (as the client CA) in its `api.tls` section.

**Renewal.** There is no renewal command, and upgrades do not reissue certificates that exist:
`syncdctl` issues the CA and the engine's and console's certificates only when `ca.crt` is missing,
and `cli-cert` issues the command line's only when it is missing. Note also that registering an
engine copies the CA, the console's client certificate and its key into the console's database
(the key encrypted). The console uses those copies, not the files: after replacing the
certificates, remove the engine in the console and register it again with the new files (see
[Registering engines](install.md#registering-engines)). ENS has not published a tested replacement
procedure; practise on a test installation first.

The certificate in front of the console, for browsers, is nginx's (`/etc/nginx/tls/`), and is
yours to renew.

## The master key

The console encrypts the secrets it stores (today, the client keys of registered engines) with
envelope encryption: AES-256-GCM, a data key per secret, wrapped by a master key. The master key is
`CONSOLE_MASTER_KEY` (base64, 32 bytes) if set, else `/var/lib/syncd/console/master.key`, created
(mode 0600) at the first start, which logs `created a new master key for stored secrets; back it
up`.

- **Back it up** apart from the database: a database dump without the key cannot be decrypted.
  `syncdctl backup` includes it, so treat backups as secrets.
- **If it is lost**, the console creates a new one at its next start and can no longer open the
  stored engine keys. Remove each engine in the console and register it again.
- There is no key rotation command.

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
  no unreachable target, no deletes held back, dead letters not growing. Alerts quiet.
- **After publishing a configuration:** `syncd status` shows the new `configuration version`, and
  the log has no `did not start`. `syncd_rule_errors_total` is not climbing.
- **Weekly:** `sudo syncd dlq`, and fix or discard dead letters that keep failing. Check disk use
  of `/var/lib/syncd` and of PostgreSQL. Verify the console's audit chain (**Audit log** →
  **Verify chain**).
- **Before an upgrade:** read the release notes (store format changes, `SYNCD_HEALTH_TIMEOUT`),
  and take and copy off a backup.
- **Regularly:** copy backups and the master key off the host; practise a restore on a test host.
