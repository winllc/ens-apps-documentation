# Sizing, availability and recovery

This page covers how big to make the syncd host or pod, what high availability syncd supports (one
engine, or from 0.4.0 an active/passive pair), how to back it up off the host and recover it after
losing the host or the cluster's volume, and how to go back to the previous release. The backup
commands themselves are in the [Operator guide](operator-guide.md#backups). For the suite-wide
picture, see [Sizing, availability and recovery](../availability-and-sizing.md).

## Sizing

!!! note "Measured by the project's own benchmarks"
    Unlike the other ENS applications, syncd's figures below are measurements: its benchmark
    suite (`docs/phase1/BENCHMARKS.md` in the source repository) runs on a 4 vCPU VM, with every
    person present in two sources (an HR table and a directory). They are not load tests of your
    sources and targets: the speed of an LDAP target or a source database usually sets the pace of
    exports and reads. The 0.4.0 features (held changes, report mode, timers, snapshots) were not
    re-measured: their costs below come from how the code keeps them. Start with these figures,
    watch the signals named here, and adjust.

### What drives the size

| Driver | How the code handles it | Settings |
|---|---|---|
| Entities and source records | Each entity and source record is held in the engine's in-memory hash and link index and its join index. Memory grows with them: about 1.2 GiB per million entities with two source records each | |
| The first full load | Builds the indexes and writes every entity: about twice the steady memory while it runs | |
| Store block cache | Caches the store's decompressed blocks. It matters little while the operating system's page cache holds the store, so leave RAM free for that rather than raising it | `engine.cache_mb` (256; at most 32 768; takes effect when syncd restarts) |
| Store write buffers | Up to 256 MiB in all, whatever the number of views and publications. Flushed to disk as they fill, and on a clean stop | |
| Entity history | An entry per change, a few hundred bytes each: a full load writes one per entity, and a stream of changes one per changed entity. From 0.4.0 each entity keeps a floor of entries past the age cut (see [Entity history](#entity-history)) | `engine.history.keep_days` (30; 0 records none), `keep_per_entity` (100), `min_per_entity` (10) |
| Held changes | One entry in the store per held entity and publication, replaced as the plan changes, and a map of the held entity ids in memory per publication. A change window that holds a day's changes, or a threshold that holds a mass change, holds one per changed entity | Change windows, thresholds, canaries (see [Change safety and lifecycle](change-safety.md)) |
| Report mode | Every change a publication in report mode would make is held as a would-do, in the store and in memory, as above. A report-mode publication over a view not yet in its target holds one per entity in scope: allow memory in proportion for a very large view | `mode: report` |
| Persisted timers | In the store, keyed by due time: one per entity with an effective date or a leaver stage ahead, and a few per publication. Their count is kept in memory, not the timers | |
| Data-quality failures | Per failing entity, the checks it fails with their messages, in memory and in the store | A view's `quality` checks |
| Publishing a configuration | Recomputes every entity a page at a time: a few hundred MiB more, not a copy of every entity | |
| Simulations | Run beside the engine on a copy of its store (hard links), with their own indexes and write buffers. Allow about 0.7 GiB per million entities, and about half the store's size on disk, while one runs. One runs at a time | |
| Backups | A store checkpoint (hard links, little disk) under the data directory while the archive is streamed; no temporary copy of the archive. CPU for the zstd compression | `engine.backup.every_secs` |
| HA snapshots | The active engine keeps its newest snapshot, a compressed full archive, under `<data_dir>/ha/`. The passive engine keeps the newest one it received extracted there, a full store beside its own. Each is a full archive, not a delta: bandwidth between the engines every interval | `engine.ha.snapshot_every_secs` (300) |
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
the newest three are kept. An engine of an HA pair needs room for a snapshot beside its store (the
passive one, a whole extracted store).

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

### Entity history

Entity history is usually the largest part of the store. The engine prunes it an hour after it
starts and every hour after that (`syncd history prune --now` does it at once):

1. entries older than `keep_days` go, except the newest `min_per_entity` of each entity, so an
   entity's story is never empty, and every entry of an entity with an open held change or a dead
   letter, which stay until it clears;
2. then each entity keeps at most its newest `keep_per_entity`, exempt entities included.

```yaml
engine:
  history:
    keep_days: 30          # entries older than this are pruned; 0 records no history
    keep_per_entity: 100   # at most this many per entity, the newest
    min_per_entity: 10     # new in 0.4.0: the newest ones an entity keeps past the age cut
```

So the history holds at most `entities × keep_per_entity` entries, and in a quiet directory about
`entities × min_per_entity`. Each entry is a few hundred bytes (the attributes that changed,
before and after; an export's operations). For a million people at 300 bytes an entry, that is
between 3 GB (the floor) and 30 GB (the cap, reached only with a hundred changes per person inside
`keep_days`).

!!! note "Upgrading from 0.3.0"
    Before 0.4.0 there was no floor: a person with no change for `keep_days` had no history left.
    The first prune after the upgrade keeps up to `min_per_entity` entries for every entity, so
    the store does not shrink as it did, and an entity with a held change or a dead letter keeps
    its whole history until that clears. Lower `min_per_entity` if the disk is tight.

What is kept shows in `syncd status` (`history: N entries, oldest ..., pruned ...`), the
console's engine page, and the metrics `syncd_history_entries`, `syncd_history_pruned_total`,
`syncd_history_oldest_timestamp_seconds` and `syncd_history_last_prune_timestamp_seconds`. See
also [History retention](reports-and-data-quality.md#history-retention).

### The memory limit

The `syncd` service may use 80% of the host's memory (`MemoryMax=80%` in its unit). Past that the
kernel stops syncd, and systemd starts it again from its last commit, rather than the whole host
stalling for memory. Cloud instances usually have no swap, so without a limit that stall can take
the host down. To change the limit, run `systemctl edit syncd` and set `MemoryMax=` under
`[Service]`. Don't remove it.

How to check and adjust:

- **Memory.** `process_resident_memory_bytes` against `syncd_memory_limit_bytes`; the shipped alert
  `SyncdMemoryNearLimit` fires above 90% for 10 minutes. Memory freed after a simulation is not all
  returned to the system. `syncd status` prints memory with its peak since the start, which shows
  how close the first load came to the limit.
- **Held changes and report mode.** `syncd_held_changes` and `syncd_shadow_planned` count what is
  held per publication. A very large count costs memory in proportion: switch a report-mode
  publication to `apply`, or approve or fix what is held.
- **CPU.** `rate(process_cpu_seconds_total[5m])` is the CPUs the engine uses. The console shows
  it live on the engines dashboard and the live metrics page, as a share of the CPUs the engine
  may use (its CPU affinity, capped by `CPUQuota` or a container's CPU limit); threads and open
  files are `process_threads` and `process_open_fds`. A full sync uses every core for its
  recompute; a CPU limit lengthens it.
- **Disk.** The size of `/var/lib/syncd/engine` as entities and history grow; lower
  `engine.history.keep_days` or `min_per_entity` if history dominates.
- **Long operations.** `syncd_engine_busy_seconds`; `SyncdEngineBusy` fires after an hour.
- **Sources keeping up.** `SyncdSourceStalled`, and for PostgreSQL sources the replication slot's
  retained WAL (`pg_replication_slots`), so the database's disk doesn't fill.

### Sizing on Kubernetes

The [Helm chart](install.md#install-with-helm) gives the engine's pod:

| Value | Default | Size it as |
|---|---|---|
| `engine.resources.limits.memory` | `4Gi` | The memory limit above: at least the initial-load peak, about 2.3 GiB per million entities, plus room for simulations. 4Gi covers about 1.5 million entities |
| `engine.resources.requests` | 250m CPU, `512Mi` | Raise the memory request towards the steady figure, so the scheduler places the pod where that memory is |
| `engine.persistence.size` | `10Gi` | The store and its history; the volume also holds the configuration file. Allow about half the store's size again for a simulation's copy. For a pair, each engine has a volume of this size, and needs room for a snapshot as well: about the store's size again |
| `console.resources` | 100m CPU, `128Mi` requested; `512Mi` limit | Enough for the console alone |

The memory limit acts as `MemoryMax` does on a host: past it the kernel stops the engine, and
Kubernetes restarts the pod from its last commit. The engine reports the limit as
`syncd_memory_limit_bytes`. To grow the volume, resize the PersistentVolumeClaim itself, where
its StorageClass allows expansion. Changing `engine.persistence.size` on an installed release
fails, since a StatefulSet's volume claim template cannot be changed.

## High availability

syncd runs as **one engine**, on one host or in one pod, or from 0.4.0 as an **active/passive
pair** of two engines. There is no clustering beyond the pair: never run two engines against the
same sources and targets except as a pair, since each keeps its own store and both would export.

### The active/passive pair

Two engines with the same configuration share a **lease**. The one that holds it is the active
engine: it runs the sources and the exports. The other is passive:

- it serves its control API and its metrics, and its status says who is active;
- it runs no source and writes nothing to any target;
- it keeps a warm copy of the store: every `snapshot_every_secs` (300) the active engine takes a
  consistent checkpoint of its store, with its key material and configuration file, and the
  passive engine pulls it over the control API (mutual TLS);
- when the active engine stops renewing the lease, the lease expires after `ttl_secs` (15) and
  the passive engine takes over.

| Lease backend | Where | Expiry judged by | For |
|---|---|---|---|
| `postgres` | A row per pair in the table `syncd_lease`, taken and renewed in one statement that succeeds only for its holder or over an expired row. The console's database works (its migrations create the table); in another, the engine creates it if its role may | The database's clock | Production, on hosts and on Kubernetes |
| `file` | A file on a file system both hosts share, locked while it is read and written | The hosts' clocks: keep them in NTP sync | Tests and simple setups |

- **TTL.** `engine.ha.lease.ttl_secs`, 15 by default, at least 3. The holder renews every third
  of it. A failover takes at most the TTL, plus the new engine's reconciliation. A shorter TTL
  fails over sooner but makes a short database or network blip enough to step the active engine
  down.
- **Snapshots.** `engine.ha.snapshot_every_secs`, 300 by default, at least 10. The interval is the
  failover window (below): shorter means less to catch up after a takeover, more bandwidth and
  disk I/O.
- **A standby.** `role: passive` makes an engine that never takes the lease and only follows.
- **Fencing.** Every export batch and every password delivery checks first that this engine
  holds the lease and that it is not within a second of expiring. An engine cut off from the lease
  stops writing before the other may take over, and steps down to passive when it finds the lease
  gone.

Setup: on hosts, [An active/passive pair on hosts](install.md#an-activepassive-pair-on-hosts);
on Kubernetes, [An active/passive pair](install.md#an-active-passive-pair). Day-to-day running:
[High availability](operator-guide.md#high-availability) in the Operator guide.

### What happens on failover

1. The active engine stops renewing: it died, its host or its network went away, or it was cut off
   from the lease's database. A clean stop (`systemctl stop`, a pod deleted by a rollout or a
   drain) gives the lease up at once instead.
2. The lease expires after `ttl_secs`, and the passive engine takes it (`this engine took the
   lease; taking over`). The console sends `ha.failover`.
3. It swaps the newest snapshot it received in for its own store, in steps a crash can interrupt
   and the next start finishes or undoes.
4. It reconciles every publication with its target, then starts the sources from the checkpoints
   in the snapshot (for a source whose first read is a full one, the reconciliation follows that
   read).
5. The old engine, when it comes back, finds the lease held and starts passive.

**The failover window.** Changes the old active engine exported after its last snapshot are found
again:

- the sources resume from the snapshot's checkpoints and read again what came since: a syncrepl
  cookie, a change-log number, a binlog position, a resume token, a CSV file;
- a PostgreSQL logical replication stream cannot go back past what its slot confirmed, so after a
  takeover it reads its tables in full first (the slot stays; unchanged rows cost nothing);
- the reconciliation finds what is already in the targets: an LDAP entry already there is updated
  in place, a SCIM or Entra ID resource is adopted.

So nothing is lost, but a takeover after a long interval has more to read and reconcile.

**Configurations.** The engine that takes over adopts the configuration file in the snapshot when
its host settings (`engine.data_dir`, `api`, `engine.ha`) are the same as its own, so publishing
to the active engine is enough. When they differ, it runs its own: publish to both.

### Limitations

- **No etcd or Kubernetes Lease backend.** The lease is in PostgreSQL or a shared file.
- **Each snapshot is a full archive**, not a delta: the passive engine is as fresh as the last
  snapshot.
- **Password changes the old engine had queued after its last snapshot are not delivered again**
  after a failover. The person's next change is.
- **Only the active engine runs the password agents' ingest listener and self-service**, and the
  console's `CONSOLE_SELF_SERVICE_ENGINE` names one engine: it does not follow the lease. See
  [Password synchronisation](password-sync.md#high-availability).
- **`syncdctl` does not set up a pair** on hosts: follow the steps in
  [Installation](install.md#an-activepassive-pair-on-hosts).
- **Not tested on Kubernetes.** The chart's kind test installs a single engine; CI only lints and
  renders the HA templates. The pair is tested end to end on hosts, with a PostgreSQL lease.
- **The lease's database is a dependency.** Without it neither engine can hold the lease, and
  nothing is exported (`SyncdHaNoActive`). Run it with your own replication or failover.

### On Kubernetes, without a pair

The engine is a StatefulSet with one replica, and its store is on a `ReadWriteOnce` volume. If its
node fails, Kubernetes starts the pod again where the volume can be attached, and the engine
carries on from its last commit. A StatefulSet does not replace a pod on a node that stops
answering until the node is known to be gone, so that two engines never share the volume; that
wait, and attaching the volume elsewhere, is your outage. With `engine.ha.enabled`, each engine
has its own volume, and the passive one takes over within the lease's TTL instead. The console's
Deployment can be rescheduled anywhere, as it keeps nothing in its pod.

### What else keeps syncd running

- **Automatic restart.** Both systemd units restart on failure after 5 seconds; on Kubernetes,
  the pods restart, and the engine's liveness probe restarts an engine that stops answering
  `/metrics`. The engine starts again from its last commit; a full sync that was interrupted
  resumes where it stopped.
- **Supervised sources.** A source that fails is restarted from its last committed checkpoint,
  with waits that double from 1 second up to `engine.source_retry_max_secs` (60). Other sources
  keep flowing.
- **Directory failover.** LDAP and Active Directory connections take `failover_urls`, tried in
  order when `url` does not answer (see [Connectors](connectors.md)).
- **Target outages.** When a target directory goes away, the engine keeps reading and committing
  its sources; exports to that target go to the dead-letter queue and are sent again by
  `syncd retry` or the next reconciliation once it answers. With `writeback` on a publication,
  changes made in the target during the outage are written back first.
- **Timers survive restarts.** Effective dates, leaver stages, change windows, canaries and group
  expiry are kept in the store, and acted on at their time whether the engine ran throughout or
  was restarted (or, in a pair, failed over).
- **The console is not in the data path.** If the console or its database is down, the engine
  keeps synchronising; only management (publishing, approvals, the live status page) waits. A
  pair is the exception: its lease is usually in the console's database.
- **The database** is yours: run the console's PostgreSQL with your own replication or failover,
  and point `CONSOLE_DATABASE_URL` (and a pair's `SYNCD_HA_LEASE_URL`) at it.

**Cold standby** is still an option without a pair: a second host with the same release installed
and its services stopped, with backups available to it (see
[Recovering on a new host](#recovering-on-a-new-host)). Never start it while the primary could
still be running, or both engines export.

## Disaster recovery

### What to keep, and where

| Item | Holds | Back it up with |
|---|---|---|
| The engine store, `/var/lib/syncd/engine` | Entities, their source records, checkpoints of each source, what was exported, dead letters, held changes, timers, history; the password key and the agents' CA when passwords are on | `syncd backup --to` or `engine.backup` (the running engine, a consistent checkpoint), or `syncdctl backup` (stops the engine while it copies). A copy of the files taken while the engine runs is not consistent |
| The console's database | Accounts, roles, settings, registered engines, configuration versions, the audit trail, API tokens, webhooks, environments, scheduled reports, the HA lease | `syncdctl backup --to` (`pg_dump` beside the engine's archive), `syncdctl backup`, or your database's own backups |
| The master key, `/var/lib/syncd/console/master.key` (or `CONSOLE_MASTER_KEY`) | Decrypts the stored engine client keys, webhook addresses and secrets, and secret environment values | `syncdctl backup` (host-local), and a copy kept apart from the database dumps. Off-host backups do not include it |
| `/etc/syncd` | `console.env`, `engine.env` (secrets, S3 keys), `engine/engine.yaml`, and the control API's CA and certificates | `syncdctl backup --to` (`<name>.etc.tar.gz`), `syncdctl backup`, or your configuration management. It holds secrets |
| nginx's configuration and certificate, or the console's own (`CONSOLE_TLS_CERT`) | HTTPS for the console | Your configuration management |
| The release | The bundle or package you installed | Keep the download, with `SHA256SUMS` and its signature |
| People, accounts, groups | The source systems and the target directories | Their own backups. The engine reads the sources again |
| The audit trail | Also in your SIEM, when the console sends it there (the journal, `ENS_AUDIT_*`) | The SIEM's own retention |

!!! warning "Backups hold the password key in the clear"
    The engine's password key (`password.key`) and the agents' CA key are plain files in the data
    directory, protected only by their mode (0600). Every backup (host-local, off-host and
    scheduled) and every HA snapshot copies them as they are. Anyone who can read a backup can
    open password changes sealed to that key. Restrict the backup directory or bucket, and turn on
    S3 server-side encryption (`--sse`, `engine.backup.sse`, or the bucket's default); that is
    encryption at rest by the store, not by syncd. The release notes and the source repository's
    INSTALL.md say the key is encrypted on disk and that a restore needs the same master key or
    passphrase; the 0.4.0 code does not encrypt it.

**Keep backups off the host.** From 0.4.0 the engine writes them there itself: a directory on a
mounted share, or S3 (with MinIO and other S3-compatible stores), by hand or on a schedule:

```yaml
engine:
  backup: { to: s3://backups/syncd/prod, every_secs: 86400, keep: 14, sse: aws:kms }
```

A scheduled backup holds the engine's part (the store, its configuration file, the key material).
Add the console's database and `/etc/syncd` with `syncdctl backup --to` from a timer of your own,
or your own database and configuration backups. `SyncdBackupStale` alerts when no scheduled backup
has succeeded for two intervals. Commands and S3 credentials are in
[Backups](operator-guide.md#backups).

On Kubernetes, see [Backups on Kubernetes](#backups-on-kubernetes).

Your recovery point is your backup interval for what syncd owns: configuration versions, accounts
and audit events recorded after the last backup of the console's database are lost (the audit
events stay in your SIEM if they were sent there). The identity data itself is not: after a
restore, the engine reads its sources again and catches up. Password changes delivered after the
backup are not sent again.

### Recovering on a new host

1. Install the **same release** that was running, with `--no-start`, and the same database choice:

    ```bash
    sudo ./syncd-<version>-linux-x86_64/install.sh --local-database --no-start
    ```

2. Restore the backup.

    **From an off-host backup** (`syncdctl backup --to`), with the S3 credentials in
    `/etc/syncd/engine.env` first:

    ```bash
    sudo syncdctl restore --from s3://backups/syncd/prod            # the newest; --name for another
    ```

    This restores the engine store, the console's database from the dump, and `/etc/syncd` from
    its archive, keeping the new host's own PKI (which the new console's database does not
    match: run `sudo syncdctl renew-certs` afterwards, which gives the console the new host's
    credentials). The backed-up configuration is put beside the live one as
    `/var/lib/syncd/engine/engine.restored.yaml`: compare it with `syncd config diff` and publish
    the one you want. Copy `master.key` back from where you keep it, to
    `/var/lib/syncd/console/master.key` (owner `syncd-console`, mode 0600), before the console
    starts.

    **From a host-local backup directory** copied off the old host: copy it to
    `/var/lib/syncd/backups/` on the new host (root only, mode 0700), then:

    ```bash
    sudo syncdctl restore /var/lib/syncd/backups/<backup>
    ```

    If the new host's console database URL differs from the old one's, correct
    `CONSOLE_DATABASE_URL` in the backup's `etc/console.env` first: the restore puts that file
    back before it loads the database dump, and loads the dump into the database it names. This
    puts back the engine store, the console's files with the master key, `/etc/syncd`
    (configuration, secrets, certificates), and the console's database, then starts the services.

3. The backup keeps the old host's numeric owners. If the `syncd` and `syncd-console` accounts
   have other ids on the new host, the services cannot read their files: give them back the owners
   the installer sets, and restart.

    ```bash
    sudo chown -R syncd:syncd /var/lib/syncd/engine /etc/syncd/engine
    sudo chown -R syncd-console:syncd-console /var/lib/syncd/console
    sudo chown root:syncd /etc/syncd/engine.env /etc/syncd/pki/engine.key /etc/syncd/pki/cli.key
    sudo chown root:syncd-console /etc/syncd/console.env /etc/syncd/pki/console-client.key
    sudo systemctl restart syncd syncd-console
    ```

4. Set up nginx and its certificate again, or the console's own certificate
   ([Installation](install.md#https-in-front-of-the-console)), and check `CONSOLE_PUBLIC_URL`;
   update your identity provider's redirect URI if the address changed.
5. Check: `sudo syncdctl status`; `sudo syncd status` shows every source running and no
   unreachable target; sign in to the console; **Audit log** → **Verify chain** reports the chain
   intact.
6. Let the engine catch up: each source carries on from its checkpoint in the restored store, and
   full reads pick up what changed since. `sudo syncd reconcile` compares every target with what
   should be there now.

**Without an engine store backup**, the engine starts empty and reads every source in full, then
exports. Expect the [initial-load](#starting-figures) memory and time. Staged leaver clocks,
held changes and timers start over. **Without the master key**, the console refuses to start over
its sealed secrets. Restore the key; if it is lost for good, start it with
`CONSOLE_NEW_MASTER_KEY=true` and register the engine again (see
[The master key](operator-guide.md#the-master-key)).

**A pair.** The surviving engine is the recovery: it takes over by itself. To rebuild the other
host, install it as in [An active/passive pair on hosts](install.md#an-activepassive-pair-on-hosts)
and start it: it starts passive and pulls a snapshot, with nothing to restore. To restore a pair
from a backup, stop both engines, restore on one, start that one first, then the other.

### Backups on Kubernetes

The chart takes no backups, and `syncdctl` does not run there. Back up three things:

| Item | How |
|---|---|
| The engine's store | `engine.backup` on a schedule, or `kubectl -n syncd exec syncd-engine-0 -- syncd backup --to s3://backups/syncd/prod --keep 14`, without stopping it. Or a VolumeSnapshot of `data-syncd-engine-0`, with the engine stopped so that the copy is consistent: `kubectl -n syncd scale statefulset syncd-engine --replicas=0`, take the snapshot, then scale it back (the next `helm upgrade` sets it back too) |
| The console's database | `pg_dump --format=custom`, or your database's own backups |
| The `syncd-console` Secret | `kubectl -n syncd get secret syncd-console -o yaml`, kept as a secret. It holds the master key |

The `syncd-pki` Secret is worth keeping with them, but need not be: without it the chart issues new
certificates and its registration Job gives the console the new credentials. From chart 0.4.0 it
is kept by `helm uninstall`.

**S3 credentials** reach the engine's pod the way they reach any AWS client:

- **IRSA or EKS Pod Identity:** annotate the engine's service account with the role; the engine
  exchanges the web identity token with STS itself. The chart runs the engine with
  `automountServiceAccountToken: false`: mount the projected token with `engine.extraVolumes` and
  `extraVolumeMounts`, and set `AWS_WEB_IDENTITY_TOKEN_FILE` and `AWS_ROLE_ARN` in `engine.env`
  (or let the EKS webhook inject them, with the token mounted).
- **Static keys:** a Secret with `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY`, as
  `engine.envFromSecret` (the same Secret as the sources' passwords, or another).
- **An S3-compatible store** in the cluster (MinIO): `endpoint: http://minio.minio.svc:9000` in
  `engine.backup`, with `ca_file` from an extra volume for a private CA. With
  `networkPolicy.egress.enabled`, add its port to `egress.extra`.

**Restoring**, into the same release name and namespace:

1. Put the `syncd-console` Secret back (`kubectl apply -f`) before installing, so the chart reuses
   its master key instead of generating one.
2. Restore the console's database into an empty database (drop and create it first, then
   `pg_restore --no-owner`), the one `console.database` names.
3. The engine's store, either way:
    - **From an off-host backup:** scale the engine to 0, then run `syncd restore --from
      s3://backups/syncd/prod --replace` in a pod with the engine's image and the
      `data-syncd-engine-0` claim mounted at `/var/lib/syncd` (a Job, or `kubectl debug`), with
      the same S3 credentials and `SYNCD_CONFIG=/var/lib/syncd/engine.yaml`. Then scale the
      engine back to 1.
    - **From a volume snapshot:** create the PersistentVolumeClaim `data-syncd-engine-0` from the
      snapshot (its `dataSource`) before installing, so the StatefulSet uses it. With the release
      installed, scale the engine to 0 and delete the claim first.
4. `helm install` (or `helm upgrade`) the **same chart version** that took the backups, then check:
   `kubectl -n syncd exec syncd-engine-0 -- syncd status`, sign in, and **Verify chain**.

The volume keeps the files' owner, uid 65532, which is what the images run as, so no `chown` is
needed. The engine then catches up from its checkpoints, as on a host. For a pair, restore into
`data-syncd-engine-0` with both pods scaled down: the other engine pulls a snapshot once the first
holds the lease. This procedure is documented in the source repository's runbook; it is not part
of the chart's CI test.

### Moving from a host to Kubernetes

There is no tested procedure for moving a host install's store and console database into the
chart. The simple way is a fresh start:

1. Install the chart (see [Install with Helm](install.md#install-with-helm)), with the secrets the
   configuration refers to in the engine's Secret.
2. Export the running configuration from the host's console and import it into the new console,
   then publish it there. Its `engine` and `api` sections are the image's, not the host's.
3. Stop the host's engine (`sudo systemctl stop syncd`) before the new engine's first full read,
   so that only one engine exports.

The new engine reads every source in full, with the [initial-load](#starting-figures) memory and
time. Accounts, the audit trail and configuration versions stay in the host's console database;
keep its backups. An off-host backup of the host's engine can be restored into the pod's volume as
in [Backups on Kubernetes](#backups-on-kubernetes), which keeps its checkpoints, held changes and
timers; this is untested across the move, and the restored configuration's `engine` and `api`
sections are the host's, so publish the configuration from the new console afterwards.

### The audit trail after a restore

The console's audit trail is a hash-chained table in its database, restored with the same dump, so
the chain is intact but ends at the backup. Events recorded after the backup are lost from the
database. From 0.4.0 there are second copies outside it: every event is written as a JSON line to
the console's standard output (the journal or the pod log), and can go to an audit file, Splunk or
syslog (`ENS_AUDIT_*`), at least once and in order. Those copies keep the events the restore lost.
The console also logs the chain's head (`audit log head`) every hour when it moved: verifying the
restored trail against the last head logged before the backup shows it is intact up to it; a head
logged after the backup names an event the restore lost. See [Security](security.md) and
[Audit logging](../audit-logging.md).

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
  contents are kept: configuration versions, accounts and audit events written since the upgrade,
  and so are the control API's certificates, which the console's stored credentials match.
- `--keep-data` switches the programs back and restores nothing. It is refused when the newer
  release migrated the store to a format the previous one cannot read.

**From 0.4.0 to 0.3.0**, the console's schema changed (8 to 17), so the rollback restores the
console's database, dropping the tables 0.4.0 added, and loses everything recorded in the console
since the upgrade: configuration versions, accounts, API tokens, webhooks, environments, reports
and audit entries. The store's format did not change, so `--keep-data` is allowed; but 0.3.0
ignores the timers and held changes 0.4.0 keeps in the store, and refuses a configuration with
0.4.0's settings, so publish one without them first. Take an HA pair apart before rolling back.
The details are in [Rolling back 0.4.0 to 0.3.0](install.md#rolling-back-040-to-030).

**Package installs:** install the previous package version with your package manager, then
`sudo syncdctl restore /var/lib/syncd/backups/<time>-upgrade-<old>-to-<new>`. A full restore
always restores the console's database, so anything recorded in the console since the upgrade is
lost.

**Kubernetes:** `helm rollback` puts back the previous images and values only. It restores neither
the engine's volume nor the console's database, nor any Secret. A rollback to a release that
cannot read the store's format leaves the engine refusing to start (`the store has format N,
written by a newer syncd`), and a console older than its database's schema may not start either:
restore the backups taken before the upgrade (see [Backups on Kubernetes](#backups-on-kubernetes)),
then roll back. From chart 0.4.0 to 0.3.0, restore the console's database into an empty database,
turn `engine.ha.enabled` off first if it is on, and publish a configuration without 0.4.0's
settings.

Details are in [Installation](install.md#rollback). The release build tests upgrade, failed
upgrade, rollback, backup and restore on every release, under systemd; still, practise a rollback
on a test installation before you rely on it.
