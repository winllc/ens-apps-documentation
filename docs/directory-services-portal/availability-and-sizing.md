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
| Finished tasks kept in memory | Results of finished tasks stay in memory for `JOBS_RETAIN_MINUTES` (24 hours), up to 500 tasks. With the shared database, progress and results are shared through it | `JOBS_RETAIN_MINUTES`, `PORTAL_JOBS_MAX_JOBS` |
| LDAP connections | Every request and every running scan takes a connection from the service account's pool for its duration. Each API instance has its own pool | `LDAP_POOL_SIZE` (10) |
| Interactive users | Browsing and editing are single LDAP searches and writes; sessions are small, in memory or in the shared database | `LDAP_POOL_SIZE`, CPU |
| Audit store | Grows with every sign-in, change and refused request, and is kept until `ENS_AUDIT_RETENTION_DAYS` removes it. In the data volume, or in the shared database | `ENS_AUDIT_RETENTION_DAYS`, data volume or database size |
| Directory size | Matters only through the scans above. Browsing pages through search results; it does not load whole directories | `METRICS_MAX_ENTRIES` |

### Starting figures

| Component | Docker package | Helm chart | Notes |
|---|---|---|---|
| API memory | `API_MEMORY_LIMIT=1536m` | `api.resources`: request `768Mi`, limit `1536Mi` | The JVM's heap is 75% of the limit (`-XX:MaxRAMPercentage=75.0`) |
| API CPU | No limit | request `250m`, no limit | Scans and analyses are CPU-bound while they run |
| Web tier | `WEB_MEMORY_LIMIT=256m` | request `50m` / `64Mi`, limit `256Mi` | Static files and a proxy |
| Data volume | Docker volume, no size | `persistence.data.size: 2Gi` | Configuration, audit store, analysis results. With the shared database, only read once, to import them |
| License volume | Docker volume | `persistence.license.size: 100Mi` | A few small files. With the shared database, only read once, to import them |
| LDAP pool | `LDAP_POOL_SIZE=10` | `ldap.poolSize: 10` | |

How to adjust:

- **Heap.** Watch `jvm_memory_used_bytes{area="heap"}` (Prometheus) while the largest analysis
  runs. If it nears the maximum, or the API is killed for running out of memory, raise the memory
  limit, or lower `METRICS_MAX_ENTRIES` or the job threads. Raising `METRICS_MAX_ENTRIES` above
  100,000 needs more heap; how much depends on how many attributes your forms and charts read.
- **LDAP pool.** If `ens_dsp_ldap_pool_available` sits at 0 while requests are slow, raise
  `LDAP_POOL_SIZE`, keeping within what your directory server allows the service account.
- **Audit disk** (without the shared database; with it, watch the database's size instead). An
  event is a few hundred bytes for a sign-in; an entry change holds the changed
  values and an entry delete holds every attribute the entry had, so those are larger. Measure
  your own rate: the current month's `audit-YYYY-MM.jsonl` size divided by its line count, times
  the events you expect a day. Readiness fails, and directory writes are refused, before the disk
  is full (under 10 MB and 1 MB free respectively), so leave headroom.

## High availability

The web tier is stateless and can always have several replicas (Helm: `web.replicaCount`). The API
runs as one instance unless it has a shared PostgreSQL database. For the suite-wide picture, see
[Sizing, availability and recovery](../availability-and-sizing.md).

### Without a shared database: one API instance

Without `DSP_DATABASE_URL` nothing changes from earlier releases: sessions, the sign-in lockout and
background tasks are in the API's memory, and configuration and the audit store are files in
`DATA_DIR`, so a second API process would not share them and would fork the audit chain. The Helm
chart keeps one API replica with the `Recreate` strategy and refuses `api.replicaCount` above 1
without `database.url`. Every restart signs users out and cancels running background tasks.

### With a shared database: several API instances

Set `DSP_DATABASE_URL` (`jdbc:postgresql://host:5432/dsp`; PostgreSQL 13 or later),
`DSP_DATABASE_USERNAME` and `DSP_DATABASE_PASSWORD_FILE` (or `DSP_DATABASE_PASSWORD`). The API
creates and upgrades its tables itself; the role needs `CREATE` on its schema.

**Shared through the database:**

- **Sessions** (Spring Session JDBC), with the same `DSP_SESSION` cookie, timeouts and per-user limit
  across instances.
- **The sign-in lockout** (`dsp_login_failure`), whichever instance a request reaches.
- **Configuration**, as a versioned document: changes made on different instances at once are
  applied one after the other, never lost. Other instances see a change within
  `DSP_CONFIG_REFRESH` (`2s`).
- **The audit store**, one hash chain in `dsp_audit_event`, with the audit API tokens and forwarder
  cursors.
- **Background tasks' progress and results**, so the task list, results, cancelling and dismissing
  work from any instance.
- **Single sign-on** requests in flight and used SAML assertion ids.
- **The license**, the installation id and the revisions seen (`ens_license_*`): an upload on the
  license screen reaches every instance.

**One instance at a time:** the Splunk and syslog forwarders run on one elected instance (another
takes over within seconds when it stops); audit retention and the license-mode check run under a
cluster lock.

**Per instance:** a background task runs on the instance that started it, and is lost if that
instance stops. Each instance has its own LDAP pool and `DSP_DATABASE_POOL_SIZE` (10) database
connections.

**Moving an existing installation to the database.** Set the `DSP_DATABASE_*` variables and restart
the single instance with its data and license volumes still mounted. The first start imports
`portal-config.json`, the audit files (the chain carries on from the same head), the audit API
tokens and forwarder cursors, the saved analysis results and the license state, and logs an
`Imported ...` line for each. Then add instances. Don't go back to running without the database
afterwards: the files would miss everything since, and the license volume's old license would
return.

**Helm.** Upgrade in two steps: first add `database.*` with one replica and the data volume, then
set `api.replicaCount` above 1 and `persistence.data.enabled: false`. With more than one replica
the chart renders a PodDisruptionBudget (`api.podDisruptionBudget.minAvailable`, 1) and, with the
database and no ReadWriteOnce volume mounted, a `RollingUpdate` strategy (`maxUnavailable: 0`), so
upgrades and node drains don't interrupt the portal. It refuses `api.replicaCount` above 1 without
`database.url`, or with a ReadWriteOnce data or audit-file volume.

**Docker and native packages.** Both run one API instance. Their settings (`.env`, `portal.env`)
take the same `DSP_DATABASE_*` variables, which move the state into the database; the packages
contain no PostgreSQL.

### Restarts and the database

Docker restarts the containers (`restart: unless-stopped`), systemd restarts the native API after
5 seconds (`Restart=on-failure`), and Kubernetes restarts a pod whose liveness probe fails and
reschedules it if its node fails. With one replica, rescheduling to another node needs storage
that can attach the ReadWriteOnce volumes there, and an outage lasts about as long as the API
takes to start (the Helm startup probe allows up to 3 minutes). With the shared database, its
availability is yours to provide: run PostgreSQL with your own high-availability setup.

## Disaster recovery

### What to keep, and where

| Item | Holds | Back it up with |
|---|---|---|
| The shared PostgreSQL database, if you use one | Configuration, the audit store, sessions, background task results, the license and **this installation's id** | Your database's backups. `./portal.sh backup` and `upgrade.sh` don't include it |
| Data directory (`/data`, the `portal-data` volume, `/var/lib/directory-services-portal`, or the data PVC) | Without the database: configuration, audit store, analysis results. With it: only what the first start imported | `./portal.sh backup`, `tar`, or volume snapshots ([Operator guide](operator-guide.md#data-and-backups)) |
| License directory (`ens-license` volume, `/var/lib/ens/directory-portal`, or the license PVC) | Without the database: the license file and **this installation's id**. With it: only what the first start imported | A copy of the directory or a snapshot. Keep the `.lic` file you received as well |
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
3. **Restore the license directory** (without the shared database; with it, the database holds
   the installation id). This keeps the installation id, so a license bound to it keeps working.
   If it is lost:
    - an unbound license works again once you install the `.lic` file on the license screen;
    - a bound license is refused by the new installation id. Note the new id on the license
      screen and ask ENS Solutions to re-bind or re-issue the license.
4. **Restore the data.** With the shared database, restore PostgreSQL from its backup, including
   the `ens_license_*` tables: without them, and without a license volume, the installation gets
   a new id. Without the database, restore the data directory from the latest backup:
   `./portal.sh restore <file>` (Docker; it starts the API when done), `tar -x` into
   `/var/lib/directory-services-portal` and `chown -R dsp:dsp` (native), or restore the PVC from
   its snapshot (Helm).
5. **Start the portal** (`./portal.sh up`, `systemctl start`, or your `api.replicaCount`) and
   wait until it is ready.
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
    `helm rollback` restores the previous chart's manifests and values. It does **not** roll back
    the data: restore the data volume, or with the shared database PostgreSQL, from the backup you
    took before the upgrade. With the shared database, scale the API to 0 before restoring it.

**Why restore the data too.** A newer release may change what it writes, for example a new
configuration field or audit details. An older release ignores configuration fields it doesn't
know and drops them the next time it saves. Running an older release on data written by a newer
one is not tested, so restore the pre-upgrade backup. Configuration changes and audit events made
since the upgrade are then lost from the portal; forwarded audit events stay in your SIEM.
