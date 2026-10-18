# Sizing, availability and recovery

This page covers how big to make the portal, what high availability it supports, how to recover
it after losing a host or cluster, and how to go back to the previous release after an upgrade.
Backups themselves are in the [Operator guide](operator-guide.md#data-and-backups).

## Sizing

!!! warning "Unbenchmarked starting points"
    ENS has not load-tested the portal. The figures below are the shipped defaults and the
    reasoning behind them, not measured capacities. Start with them, watch the metrics named
    here, and adjust.

### What drives the size

| Driver | Why it matters | Settings |
|---|---|---|
| Metric scans and analyses | The only work that reads many entries at once. Each source reads up to `METRICS_MAX_ENTRIES` entries (100,000 by default), streamed from LDAP in pages of 500, and analyses keep up to `ANALYSIS_MAX_ISSUES` findings. Several run at once: `JOBS_METRICS_THREADS` (4) plus `JOBS_ANALYSIS_THREADS` (2). Heap is what limits this | `METRICS_MAX_ENTRIES`, `ANALYSIS_MAX_ISSUES`, `JOBS_*` |
| Finished tasks kept in memory | Results of finished tasks stay in memory for `JOBS_RETAIN_MINUTES` (24 hours), up to 500 tasks | `JOBS_RETAIN_MINUTES`, `PORTAL_JOBS_MAX_JOBS` |
| LDAP connections | Every request and every running scan takes a connection from the service account's pool for its duration | `LDAP_POOL_SIZE` (10) |
| Interactive users | Browsing and editing are single LDAP searches and writes; sessions are small objects in memory | `LDAP_POOL_SIZE`, CPU |
| Audit store | Grows with every sign-in, change and refused request, and is kept until `ENS_AUDIT_RETENTION_DAYS` removes it | `ENS_AUDIT_RETENTION_DAYS`, data volume size |
| Directory size | Matters only through the scans above. Browsing pages through search results; it does not load whole directories | `METRICS_MAX_ENTRIES` |

### Starting figures

| Component | Docker package | Helm chart | Notes |
|---|---|---|---|
| API memory | `API_MEMORY_LIMIT=1536m` | `api.resources`: request `768Mi`, limit `1536Mi` | The JVM's heap is 75% of the limit (`-XX:MaxRAMPercentage=75.0`) |
| API CPU | No limit | request `250m`, no limit | Scans and analyses are CPU-bound while they run |
| Web tier | `WEB_MEMORY_LIMIT=256m` | request `50m` / `64Mi`, limit `256Mi` | Static files and a proxy |
| Data volume | Docker volume, no size | `persistence.data.size: 2Gi` | Configuration, audit store, analysis results |
| License volume | Docker volume | `persistence.license.size: 100Mi` | A few small files |
| LDAP pool | `LDAP_POOL_SIZE=10` | `ldap.poolSize: 10` | |

How to adjust:

- **Heap.** Watch `jvm_memory_used_bytes{area="heap"}` (Prometheus) while the largest analysis
  runs. If it nears the maximum, or the API is killed for running out of memory, raise the memory
  limit, or lower `METRICS_MAX_ENTRIES` or the job threads. Raising `METRICS_MAX_ENTRIES` above
  100,000 needs more heap; how much depends on how many attributes your forms and charts read.
- **LDAP pool.** If `ens_dsp_ldap_pool_available` sits at 0 while requests are slow, raise
  `LDAP_POOL_SIZE`, keeping within what your directory server allows the service account.
- **Audit disk.** An event is a few hundred bytes for a sign-in; an entry change holds the changed
  values and an entry delete holds every attribute the entry had, so those are larger. Measure
  your own rate: the current month's `audit-YYYY-MM.jsonl` size divided by its line count, times
  the events you expect a day. Readiness fails, and directory writes are refused, before the disk
  is full (under 10 MB and 1 MB free respectively), so leave headroom.

## High availability

**The API runs as exactly one instance.** It is the single writer of its data directory:

| State | Where it lives | What a second API process would do |
|---|---|---|
| Configuration (`portal-config.json`) | Read once at startup, held in memory, rewritten whole on each change | Each process keeps its own copy. A change saved on one is not seen by the other, and is overwritten the next time the other saves |
| Audit store | Hash-chained files appended by one synchronized writer in the process | Both processes append with their own sequence numbers and hashes, forking the chain. **Verify integrity** then reports it broken |
| License state and installation id | Files on the license volume | Shared, but each process checks the license on its own |
| Sessions, lockout counters | Process memory | A user whose requests reach the other process is signed out; lockout counts are split between processes |
| Background tasks and their results | Process memory | A task started on one process can't be followed or fetched on the other |

The packaging enforces this: the Helm chart accepts `api.replicaCount` of `0` or `1` only and
uses the `Recreate` strategy, so an upgrade never runs two API pods at once; the Docker and
native packages run one API. There is no external session store or shared configuration database
to switch on.

What you can do:

- **Web tier: several replicas.** It is stateless. With Helm, raise `web.replicaCount`.
- **Automatic restart.** Docker restarts the containers (`restart: unless-stopped`), systemd
  restarts the API after 5 seconds (`Restart=on-failure`), and Kubernetes restarts a pod whose
  liveness probe fails and reschedules it if its node fails. Rescheduling to another node needs
  storage that can attach the ReadWriteOnce volumes there; node-local storage can't.
- **Cold standby.** Keep a second host or cluster with the same release installed and the API
  stopped, and copy backups to it. To fail over, restore the latest backup there and start it,
  following [Disaster recovery](#disaster-recovery). Never start the standby while the primary
  could still be running against the same or replicated data.

Every restart or failover signs users out and cancels running background tasks. Expect about the
time the API takes to start (the Helm startup probe allows up to 3 minutes) plus, for a failover,
the time to restore.

## Disaster recovery

### What to keep, and where

| Item | Holds | Back it up with |
|---|---|---|
| Data directory (`/data`, the `portal-data` volume, `/var/lib/directory-services-portal`, or the data PVC) | Configuration, audit store, analysis results | `./portal.sh backup`, `tar`, or volume snapshots ([Operator guide](operator-guide.md#data-and-backups)) |
| License directory (`ens-license` volume, `/var/lib/ens/directory-portal`, or the license PVC) | The license file and **this installation's id** | A copy of the directory or a snapshot. Keep the `.lic` file you received as well |
| Settings | `.env` and `certs/` (Docker); `portal.env`, `ldap-bind-password`, `proxy-secret` and any TLS files (native); your values file and the Secrets it names (Helm) | Your configuration management. They hold secrets: keep the copies private |
| The release | The package you installed, or the image digests and chart version | Keep the download, or mirror the images |
| Directory data | Entries | Your LDAP server's own backups. The portal holds none |

Your recovery point is your backup interval: configuration changes and audit events after the
last backup are lost. Audit events already forwarded to Splunk or syslog are still there.

### Recovering on a new host or cluster

1. **Install the same release** as the one that was running, from the release package or the
   chart, but do not start it yet (`./install.sh` without `--start`; the native `install.sh`
   without `--start`; or `helm install` with `api.replicaCount: 0`).
2. **Restore the settings**: `.env` and `certs/`, `portal.env` and its secret files, or your
   values file and Secrets.
3. **Restore the license directory.** This keeps the installation id, so a license bound to it
   keeps working. If it is lost:
    - an unbound license works again once you install the `.lic` file on the license screen;
    - a bound license is refused by the new installation id. Note the new id on the license
      screen and ask ENS Solutions to re-bind or re-issue the license.
4. **Restore the data directory** from the latest backup: `./portal.sh restore <file>` (Docker;
   it starts the API when done), `tar -x` into `/var/lib/directory-services-portal` and
   `chown -R dsp:dsp` (native), or restore the PVC from its snapshot (Helm).
5. **Start the portal** (`./portal.sh up`, `systemctl start`, or `api.replicaCount: 1`) and wait
   until it is ready.
6. **Check it**:
    - the license screen shows the expected state and installation id;
    - **Audit log → Verify integrity** reports the chain intact (a partial last line from a
      backup taken while the portal was busy is logged and ignored);
    - sign in as a user and as an administrator, and open a directory.
7. **Re-point audit consumers.** Events recorded after the restore continue from the restored
   store's newest sequence number, so they reuse numbers the SIEM may already have received from
   the lost period. De-duplicate on `id`, never on `seq`. A SIEM that pulls with the pull API
   remembers the last `seq` it read; reset its cursor to the restored store's newest sequence
   number (`headSeq` in `GET /api/admin/audit/status`), or it skips new events until the numbers
   pass its old cursor. Splunk HEC and syslog outputs resume from the cursor saved in the backup
   and resend what followed it, which Splunk can de-duplicate on `id`.

## Rolling back an upgrade

Take a backup before every upgrade. `upgrade.sh` does this for the Docker package; for the native
package and Helm, do it yourself.

=== "Docker package"

    `upgrade.sh` leaves the old package directory as it was and writes its backup to
    `backups/pre-upgrade-<version>-<time>.tar.gz` in the **new** directory.

    ```bash
    cd directory-services-portal-<new-version>
    ./portal.sh down
    cd ../directory-services-portal-<old-version>     # its .env still names the old images
    ./portal.sh restore ../directory-services-portal-<new-version>/backups/pre-upgrade-<new-version>-<time>.tar.gz
    ./portal.sh up
    ```

    Both directories use the same compose project name, so they share the data and license
    volumes.

    The old images must still be loaded (`docker image ls`); if they were pruned, run the old
    package's `./install.sh` again to load them.

=== "Native"

    Run the previous package's `sudo ./install.sh`. It replaces the API jar and the web client and
    keeps `portal.env`, its secret files and the data. Then stop the service, restore the data
    backup you took before the upgrade into `/var/lib/directory-services-portal`, restore
    ownership to `dsp:dsp`, and start the service.

=== "Helm"

    ```bash
    helm history dsp -n dsp
    helm rollback dsp <revision> -n dsp
    ```
    `helm rollback` restores the previous chart's manifests and values; the old API pod stops
    before the new one starts. It does **not** roll back the data on the PVCs: restore the data
    volume from the snapshot or backup you took before the upgrade.

**Why restore the data too.** A newer release may change what it writes, for example a new
configuration field or audit details. An older release ignores configuration fields it doesn't
know and drops them the next time it saves. Running an older release on data written by a newer
one is not tested, so restore the pre-upgrade backup. Configuration changes and audit events made
since the upgrade are then lost from the portal; forwarded audit events stay in your SIEM.
