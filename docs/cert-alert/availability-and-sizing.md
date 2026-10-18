# Sizing, availability and recovery

This page covers how big to make CertAlert and its database, what high availability it supports,
how to recover it after losing a host or cluster, and how to go back to the previous release.
Backups themselves are in the [Operator guide](operator-guide.md#backups).

## Sizing

!!! warning "Unbenchmarked starting points"
    ENS has not load-tested CertAlert. In particular, **no test with 100,000 certificates has been
    run**. The figures below are the shipped defaults and conservative starting points worked out
    from how the code reads and stores data, not measured capacities. Start with them, watch the
    signals named here, and adjust.

### What drives the size

| Driver | How the code handles it | Settings |
|---|---|---|
| Directory entries and certificates | Sweeps read the directory in pages (`page-size`, 500), stream the entries and write them in batches (`batch-size`, 200), so memory does not grow with the directory. Time does: a sweep's duration grows with the number of entries | `cert-alert.ldap.page-size`, `.batch-size`, `.search-timeout` (10m) |
| Revocation checks | Up to `workers` (8) OCSP requests at once. CRLs are fetched up to `max-crl-bytes` (16 MB) each and kept for `crl-cache-ttl` (6h), so memory grows with the number of issuing CAs whose CRLs are cached | `cert-alert.revocation.*` |
| The expiry round-up | Reads at most `digest.scan-limit` (10,000) certificates per run, and sends at most `email.max-per-run` (500) emails | `cert-alert.notifications.digest.scan-limit`, `.email.max-per-run` |
| Database rows | One `cached_certificate` row per certificate (parsed fields, not the certificate itself), plus people, servers, identifiers and contacts. The audit trail grows with every sign-in, change and job until retention removes it | `ENS_AUDIT_RETENTION_DAYS` |
| Database connections | One pool for the web requests, the jobs and the changelog connector | `CERT_ALERT_DB_POOL_SIZE` (10) |
| Interactive users | Tables are paged on the server; a page is a few database queries | |

### Starting figures

| Component | Shipped default | Conservative start for about 100,000 certificates (unbenchmarked) |
|---|---|---|
| CertAlert memory | Helm: request `768Mi`, limit `1536Mi`. The heap is 75% of the limit (`-XX:MaxRAMPercentage=75.0`) | Limit `3Gi`, request `1.5Gi`. More if many large CRLs are cached |
| CertAlert CPU | Helm: request `250m`, no limit | Request `1`, no limit: sweeps and revocation runs are bursts |
| PostgreSQL | Helm embedded (evaluation only): request `100m` / `256Mi`, limit `1Gi`, `8Gi` volume | 2 vCPU, 4 GiB memory, 20 GiB disk with room for the audit trail to grow |
| Database pool | `CERT_ALERT_DB_POOL_SIZE=10` | 10; raise it only if requests wait for connections while jobs run |

How to check and adjust:

- **Sweep duration.** `GET /api/v1/sync/runs` gives each run's duration. A sweep must finish well
  before the next one starts (the users sweep runs at 02:00 and the servers sweep at 04:00 by
  default); a run that outlasts its interval is skipped with `Skipping the scheduled <JOB> job`.
  For large directories, consider the changelog connector, which applies only what changed.
- **Heap.** The JVM exits on running out of memory (`-XX:+ExitOnOutOfMemoryError`), and the
  container restarts. If that happens during a sweep or the revocation run, raise the memory
  limit.
- **Database.** Watch its disk use and the audit table's growth; set `ENS_AUDIT_RETENTION_DAYS`
  once a SIEM keeps the events.

## High availability

**Run one instance.** The scheduled jobs (sweeps, revocation checks, the round-up) and the
changelog connector run inside the process, guarded per process, and there is no lock shared
between instances: a second instance would run every job twice. The license volume is
single-writer too. The Helm chart accepts `replicaCount` of `0` or `1` only and uses the `Recreate`
strategy, so an upgrade never runs two pods at once; the release package runs one container.

What you can do:

- **Automatic restart.** Docker restarts the container (`restart: unless-stopped`); Kubernetes
  restarts a pod whose liveness probe fails and reschedules it if its node fails, provided the
  license volume can be attached on another node.
- **The database.** Run PostgreSQL with your own high-availability setup (with Helm, an external
  server). Losing the database stops CertAlert; losing CertAlert loses nothing that is not in the
  database or the license volume.
- **Cold standby.** Keep a second host or cluster with the same release installed and stopped, and
  copy database backups and the license volume to it. To fail over, follow
  [Disaster recovery](#disaster-recovery). Never run both at once.

Every restart or failover signs users out. Jobs missed while CertAlert was down run at their next
scheduled time, or on demand from the Admin page.

## Disaster recovery

### What to keep, and where

| Item | Holds | Back it up with |
|---|---|---|
| The PostgreSQL database | Everything CertAlert owns that the directory doesn't ([list](operator-guide.md#backups)), including the audit trail | `pg_dump`, or your database's backups |
| The license volume (`cert-alert_ens-license`, or the license PVC) | The license file and **this installation's id** | A copy of the volume, or a snapshot. Keep the `.lic` file you received as well |
| Settings | `.env`, `tls/`, `email-templates/`, `license/` (release package); your values file and the Secrets it names (Helm) | Your configuration management. They hold secrets |
| The release | The package you installed, or the image digests and chart version | Keep the download, or mirror the images |
| People, servers, certificates | The LDAP directory | The directory's own backups. CertAlert re-reads them on the next sweep |

Your recovery point is your backup interval: contacts, addresses, projects and settings changed
after the last backup are lost, and so are audit events that were not forwarded to a SIEM.

### Recovering on a new host (release package)

1. Unpack the **same release** as the one that was running and restore `.env`, `tls/`,
   `email-templates/` and `license/` into its directory. Don't start it yet.
2. Restore the `cert-alert_ens-license` volume from your copy, keeping the files' owner (the
   container runs as uid 185). This keeps the installation id. If it is lost:
    - an unbound license works again once you upload the `.lic` file on the license screen, or
      leave it in `license/license.lic` for `install.sh` to copy in;
    - a bound license is refused by the new installation id. Note the new id on the license
      screen and ask ENS Solutions to re-bind or re-issue the license.
3. Load the images and create the containers: `./install.sh`. Then stop CertAlert alone
   (`docker compose stop cert-alert`) and restore the database as in
   [Operator guide → Restore](operator-guide.md#restore).
4. Start it: `docker compose up -d`.
5. Check: `docker compose ps` shows it healthy; the license screen shows the expected state and
   installation id; **Admin → Audit log → Verify integrity** reports the chain intact; sign in as
   an administrator; run **Sync directory** and check the run completes.

With Helm: install the same chart version into the new cluster with your values and Secrets and
`replicaCount: 0`, restore the database into your PostgreSQL and the license PVC from its snapshot
(or let the init container install the `.lic` file into a new one), then set `replicaCount: 1`.

### The audit trail after a restore

The trail (`audit_event`) and its chain head (`audit_chain_head`) are restored together from the
same dump, so the chain is intact, but it ends at the backup. Events recorded afterwards continue
from the restored sequence number, reusing numbers your SIEM may already have from the lost period:

- de-duplicate on `id`, never on `seq`;
- a SIEM that pulls with the pull API remembers the last `seq` it read: reset its cursor to the
  restored trail's newest sequence number, or it skips new events until the numbers pass its old
  cursor;
- Splunk HEC and syslog outputs resume from the positions saved in the database
  (`audit_delivery`) and resend what followed them, which Splunk can de-duplicate on `id`.

## Rolling back an upgrade

Database migrations are not undone, and an older release is not built to run on a newer schema.
A rollback therefore always restores the database backup taken before the upgrade.

=== "Release package"

    `upgrade.sh` leaves the old release's directory as it was and writes the backup to
    `backups/cert-alert-before-<version>-<time>.sql.gz` in the new release's directory. Both
    directories use the same compose project name (`cert-alert`), so they share the volumes.

    ```bash
    cd cert-alert-<new>
    docker compose stop cert-alert
    docker compose exec -T postgres dropdb -U certalert certalert
    docker compose exec -T postgres createdb -U certalert certalert
    gunzip -c backups/cert-alert-before-<new>-<time>.sql.gz | docker compose exec -T postgres psql -U certalert -d certalert
    docker compose down
    cd ../cert-alert-<old>
    docker compose up -d
    ```

    The old images must still be loaded (`docker image ls`); if they were removed, load them from
    the old release's `images/` directory first.

=== "Helm"

    Scale the Deployment to 0, restore the pre-upgrade backup into your PostgreSQL, then roll
    back:

    ```bash
    kubectl -n cert-alert scale deploy/cert-alert --replicas=0
    pg_restore -h postgres.example.gov -U certalert -d certalert --clean --if-exists cert-alert-before-upgrade.dump
    helm history cert-alert -n cert-alert
    helm rollback cert-alert <revision> -n cert-alert
    ```

    `helm rollback` restores the previous chart's manifests and values, not the database. The
    license PVC is kept.

Configuration, contacts and audit events recorded since the upgrade are lost from CertAlert;
forwarded audit events stay in your SIEM. Run **Sync directory** after the rollback.
