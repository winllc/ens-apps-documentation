# Sizing, availability and recovery

This page covers how big to make the syncd host, what high availability syncd supports (today, a
single host), how to recover it after losing the host, and how to go back to the previous release.
Backups themselves are in the [Operator guide](operator-guide.md#backups). For the suite-wide
picture, see [Sizing, availability and recovery](../availability-and-sizing.md).

## Sizing

!!! note "Measured by the project's own benchmarks"
    Unlike the other ENS applications, syncd's figures below are measurements: its benchmark
    suite (`docs/phase1/BENCHMARKS.md` in the source repository) runs on a 4 vCPU VM, with every
    person present in two sources (an HR table and a directory). They are not load tests of your
    sources and targets: the speed of an LDAP target or a source database usually sets the pace of
    exports and reads. Start with them, watch the signals named here, and adjust.

### What drives the size

| Driver | How the code handles it | Settings |
|---|---|---|
| Entities and source records | Each entity and source record is held in the engine's in-memory hash and link index and its join index. Memory grows with them: about 1.2 GiB per million entities with two source records each | |
| The first full load | Builds the indexes and writes every entity: about twice the steady memory while it runs | |
| Store block cache | Caches the store's decompressed blocks. It matters little while the operating system's page cache holds the store, so leave RAM free for that rather than raising it | `engine.cache_mb` (256; at most 32 768; takes effect when syncd restarts) |
| Store write buffers | Up to 256 MiB in all, whatever the number of views and publications. Flushed to disk as they fill, and on a clean stop | |
| Entity history | An entry per change: a full load writes one per entity, and a stream of changes one per changed entity | `engine.history.keep_days` (30; 0 records none), `keep_per_entity` (100) |
| Publishing a configuration | Recomputes every entity a page at a time: a few hundred MiB more, not a copy of every entity | |
| Simulations | Run beside the engine on a copy of its store (hard links), with their own indexes and write buffers. Allow about 0.7 GiB per million entities, and about half the store's size on disk, while one runs. One runs at a time | |
| The queue between sources and the engine | Up to 10 000 changes, each a whole record. A few tens of MiB for people; much more for sources that send large groups (thousands of members per entry) while the engine is busy | |
| Large deletes, dead letters, groups, renames | Read a page at a time: about 20 MiB for a full read that finds 400 000 records gone, a few MiB for 400 000 dead letters, a group of 400 000 members, or 800 000 references to a renamed entity | |
| CPU | Recompute and export planning run on every core; loads, merges and commits on one | `engine.shards` (0: one per core) |

### Starting figures

| Entities | Engine memory (steady) | Engine memory (initial load peak) | Store on disk |
|---|---|---|---|
| 1 million | about 1.1 GiB | about 2.3 GiB | about 0.25 GB, plus history |
| 3 million | about 3.4 GiB | about 6 GiB | about 0.8 GB, plus history |

Add, on the same host if they run there: the console (a small Rust server), PostgreSQL for the
console, nginx, and the JDBC bridge's JVM. Disk for `/var/lib/syncd/backups` must hold copies of
the engine store and the console's files: an upgrade refuses to start without room for one, and
the newest three are kept.

How long the long operations take, at a million entities on 4 vCPU (they grow linearly with the
store):

| Operation | Time |
|---|---|
| Full sync (recompute every entity) | about 9 s |
| Reconciliation | about 6 s |
| Reload that changed every entity (publishing a configuration) | about 2 minutes |
| Restart (rebuilding the indexes, in parallel) | about 20 s for 3 million entities |
| Streaming a change end to end | about 22 000 changes/s, p99 about 0.1 s, in the benchmark harness |

While a full sync, reconciliation or reload runs, commands are refused and source changes wait
until it ends (they aren't lost).

### The memory limit

The `syncd` service may use 80% of the host's memory (`MemoryMax=80%` in its unit). Past that the
kernel stops syncd, and systemd starts it again from its last commit, rather than the whole host
stalling for memory. Cloud instances usually have no swap, so without a limit that stall can take
the host down. To change the limit, run `systemctl edit syncd` and set `MemoryMax=` under
`[Service]`. Don't remove it.

How to check and adjust:

- **Memory.** `process_resident_memory_bytes` against `syncd_memory_limit_bytes`; the shipped alert
  `SyncdMemoryNearLimit` fires above 90% for 10 minutes. Memory freed after a simulation is not all
  returned to the system.
- **Disk.** The size of `/var/lib/syncd/engine` as entities and history grow; lower
  `engine.history.keep_days` if history dominates.
- **Long operations.** `syncd_engine_busy_seconds`; `SyncdEngineBusy` fires after an hour.
- **Sources keeping up.** `SyncdSourceStalled`, and for PostgreSQL sources the replication slot's
  retained WAL (`pg_replication_slots`), so the database's disk doesn't fill.

## High availability

syncd runs on **one host**, as one engine. There is no active/passive pair, no shared lease and no
clustering yet: active/passive failover with checkpoint shipping is on the roadmap, not in the
current release. Do not run two engines against the same sources and targets: each keeps its own
store and both would export.

What it has instead:

- **Automatic restart.** Both systemd units restart on failure after 5 seconds. The engine starts
  again from its last commit; a full sync that was interrupted resumes where it stopped.
- **Supervised sources.** A source that fails is restarted from its last committed checkpoint,
  with waits that double from 1 second up to `engine.source_retry_max_secs` (60). Other sources
  keep flowing.
- **Directory failover.** LDAP and Active Directory connections take `failover_urls`, tried in
  order when `url` does not answer (see [Connectors](connectors.md)).
- **Target outages.** When a target directory goes away, the engine keeps reading and committing
  its sources; exports to that target go to the dead-letter queue and are sent again by
  `syncd retry` or the next reconciliation once it answers. With `writeback` on a publication,
  changes made in the target during the outage are written back first.
- **The console is not in the data path.** If the console or its database is down, the engine
  keeps synchronising; only management (publishing, approvals, the live status page) waits.
- **The database** is yours: run the console's PostgreSQL with your own replication or failover,
  and point `CONSOLE_DATABASE_URL` at it.

**Cold standby** is an option: a second host with the same release installed and its services
stopped, with backups copied to it (see [Recovering on a new host](#recovering-on-a-new-host)).
Never start it while the primary could still be running, or both engines export.

## Disaster recovery

### What to keep, and where

| Item | Holds | Back it up with |
|---|---|---|
| The engine store, `/var/lib/syncd/engine` | Entities, their source records, checkpoints of each source, what was exported, dead letters, held deletes, history | `syncdctl backup` (stops the engine while it copies). A copy taken while the engine runs is not consistent |
| The console's database | Accounts, roles, settings, registered engines, configuration versions, the audit log | `syncdctl backup` (`pg_dump`), or your database's own backups |
| The master key, `/var/lib/syncd/console/master.key` (or `CONSOLE_MASTER_KEY`) | Decrypts the stored client keys of registered engines | `syncdctl backup`, and a copy kept apart from the database dumps |
| `/etc/syncd` | `console.env`, `engine.env` (secrets), `engine/engine.yaml`, and the control API's CA and certificates | `syncdctl backup`, or your configuration management. It holds secrets |
| nginx's configuration and certificate | HTTPS in front of the console | Your configuration management |
| The release | The bundle or package you installed | Keep the download |
| People, accounts, groups | The source systems and the target directories | Their own backups. The engine reads the sources again |

Backups stay on the host, under `/var/lib/syncd/backups`, until you copy them elsewhere: copy them
off the host for disaster recovery.

Your recovery point is your backup interval for what syncd owns: configuration versions, accounts
and audit events recorded after the last backup are lost. The identity data itself is not: after a
restore, the engine reads its sources again and catches up.

### Recovering on a new host

1. Install the **same release** that was running, with `--no-start`, and the same database choice:

    ```bash
    sudo ./syncd-<version>-linux-x86_64/install.sh --local-database --no-start
    ```

2. Copy the backup directory to `/var/lib/syncd/backups/` on the new host (root only, mode 0700).
   If the new host's console database URL differs from the old one's, correct
   `CONSOLE_DATABASE_URL` in the backup's `etc/console.env` first: the restore puts that file back
   before it loads the database dump, and loads the dump into the database it names.
3. Restore it. This puts back the engine store, the console's files with the master key,
   `/etc/syncd` (configuration, secrets, certificates), and the console's database, then starts
   the services:

    ```bash
    sudo syncdctl restore /var/lib/syncd/backups/<backup>
    ```

    The backup keeps the old host's numeric owners. If the `syncd` and `syncd-console` accounts
    have other ids on the new host, the services cannot read their files: give them back the
    owners the installer sets, and restart.

    ```bash
    sudo chown -R syncd:syncd /var/lib/syncd/engine /etc/syncd/engine
    sudo chown -R syncd-console:syncd-console /var/lib/syncd/console
    sudo chown root:syncd /etc/syncd/engine.env /etc/syncd/pki/engine.key /etc/syncd/pki/cli.key
    sudo chown root:syncd-console /etc/syncd/console.env /etc/syncd/pki/console-client.key
    sudo systemctl restart syncd syncd-console
    ```

4. Set up nginx and its certificate again ([Installation](install.md#https-in-front-of-the-console)),
   and check `CONSOLE_PUBLIC_URL`; update your identity provider's redirect URI if the address
   changed.
5. Check: `sudo syncdctl status`; `sudo syncd status` shows every source running and no
   unreachable target; sign in to the console; **Audit log** → **Verify chain** reports the chain
   intact.
6. Let the engine catch up: each source carries on from its checkpoint in the restored store, and
   full reads pick up what changed since. `sudo syncd reconcile` compares every target with what
   should be there now.

**Without an engine store backup**, the engine starts empty and reads every source in full, then
exports. Expect the [initial-load](#starting-figures) memory and time. **Without the master key**,
the console cannot open the stored engine client keys: remove the engine in the console and
register it again.

### The audit trail after a restore

The console's audit log is a hash-chained table in its database, restored with the same dump, so
the chain is intact but ends at the backup. Events recorded after the backup are lost: syncd has no
SIEM forwarder or audit export yet, so there is no second copy elsewhere. See
[Security](security.md) and [Audit logging](../audit-logging.md).

## Rolling back an upgrade

Every upgrade takes a backup before it changes anything. A failed upgrade of a bundle install rolls
back by itself; to go back after a successful one:

```bash
sudo syncdctl rollback
```

- The previous release runs again, and the engine store and `/etc/syncd` are restored from the
  backup taken before the upgrade. The engine picks up changes made since when it next reads its
  sources.
- The console's database is restored only if the newer release changed its schema. Otherwise its
  contents are kept: configuration versions, accounts and audit events written since the upgrade.
  The 0.2.1 release has no console schema changes since 0.2.0.
- `--keep-data` switches the programs back and restores nothing. It is refused when the newer
  release migrated the store to a format the previous one cannot read.

**Package installs:** install the previous package version with your package manager, then
`sudo syncdctl restore /var/lib/syncd/backups/<time>-upgrade-<old>-to-<new>`. A full restore
always restores the console's database, so anything recorded in the console since the upgrade is
lost.

Details are in [Installation](install.md#rollback). The release build tests upgrade, failed
upgrade, rollback, backup and restore on every release, under systemd; still, practise a rollback
on a test installation before you rely on it.
