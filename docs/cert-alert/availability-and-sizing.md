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
| Database connections | One pool per replica, for its web requests, jobs and changelog polls | `CERT_ALERT_DB_POOL_SIZE` (10) |
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
  default); a run that finds the same job still running, on this replica or another, logs
  `Not running <job>: it is already running` and stands down.
  For large directories, consider the changelog connector, which applies only what changed.
- **Heap.** The JVM exits on running out of memory (`-XX:+ExitOnOutOfMemoryError`), and the
  container restarts. If that happens during a sweep or the revocation run, raise the memory
  limit.
- **Database.** Watch its disk use and the audit table's growth; set `ENS_AUDIT_RETENTION_DAYS`
  once a SIEM keeps the events.
- **With Prometheus** (on the management port): `jvm_memory_used_bytes{area="heap"}` for the
  heap, `ens_certalert_job_last_success_seconds{job}` for jobs that have stopped finishing, and
  `hikaricp_connections_pending` for requests waiting on the database pool.

## High availability

CertAlert can run as more than one replica against one PostgreSQL database. For the suite-wide
picture, see [Sizing, availability and recovery](../availability-and-sizing.md).

**Shared through the database:**

- **Jobs.** Each scheduled job, and the same job started by hand, takes a lock (`shedlock`), so it
  runs on one replica at a time; the others log that it is already running. A scheduled run keeps
  its lock for `cert-alert.cluster.scheduled-lock-at-least-for` (10 minutes) after it finishes, so
  a replica whose schedule fires late does not run the same firing again. Jobs started by hand are
  recorded in `job_run`, so any replica can report on them.
- **Work items.** Each mail is claimed (`SENDING`, with a 10-minute lease) before it is sent, so it
  is never sent twice; the changelog connector's polls, each audit output's deliveries, audit
  retention and the expired-session sweep are claimed the same way.
- **Sessions**, with `cert-alert.session.store=jdbc`, the default in the `postgres` profile that the
  chart and the release package use (`spring_session`). Any replica serves any request, the session
  controls apply across replicas, and a restart or rolling update signs nobody out.
- **SAML sign-in requests** (`saml_authn_request`). OIDC keeps its state in the session.
- **The license**, this installation's id and the revisions seen (`ens_license_*`). A license
  uploaded on one replica is in force on the others within five seconds.

**Per replica**, by design:

| State | Effect with two replicas |
|---|---|
| Repeated sign-in failures for one name (the `alert` after five) | Up to five per replica before the alert |
| Collapsed `access.denied` and certificate sign-in failures | Up to one event a minute per replica |
| License mode changes (`license.mode-changed`) | Recorded once by each replica that notices |
| `ens_certalert_job_last_success_seconds` | Set where the job ran; the PrometheusRule takes the `max` |
| Directory groups, CRLs, sign-in revocation answers | Each replica fills its own cache |

**Helm.** `replicaCount: 2` or more gives a `RollingUpdate` strategy with `maxUnavailable: 0` and
`minReadySeconds`, a PodDisruptionBudget (`minAvailable: 1`), a soft preference for spreading the
pods across nodes and a short `preStop` pause. With one replica nothing changes: `Recreate`, no
budget. To scale an existing installation, upgrade with one replica first, so the first start
imports the license and installation id from the license volume; then set
`persistence.license.imported: true` and raise `replicaCount`. The chart refuses more than one
replica without it (or a ReadWriteMany license volume), and refuses `session.store: memory` above
one replica.

**Release package.** It runs one CertAlert container, restarted by Docker or Podman
(`restart: unless-stopped`). Sessions are in the database, so a restart signs nobody out.

**The database** is the one shared dependency: run PostgreSQL with your own high-availability
setup (with Helm, an external server). Losing it stops every replica. Jobs missed while CertAlert
was down run at their next scheduled time, or on demand from the Admin page.

## Disaster recovery

### What to keep, and where

| Item | Holds | Back it up with |
|---|---|---|
| The PostgreSQL database | Everything CertAlert owns that the directory doesn't ([list](operator-guide.md#backups)), including the audit trail, the license and **this installation's id** | `pg_dump`, or your database's backups |
| The license volume (`cert-alert_ens-license`, or the license PVC) | The license state of a release that kept it in files, imported into the database on the first start after upgrading, and not read after that | Keep it until that import is done. Keep the `.lic` file you received as well |
| Settings | `.env`, `tls/`, `email-templates/`, `license/` (release package); your values file and the Secrets it names (Helm) | Your configuration management. They hold secrets |
| The release | The package you installed, or the image digests and chart version | Keep the download, or mirror the images |
| People, servers, certificates | The LDAP directory | The directory's own backups. CertAlert re-reads them on the next sweep |

Your recovery point is your backup interval: contacts, addresses, projects and settings changed
after the last backup are lost, and so are audit events that were not forwarded to a SIEM.

### Recovering on a new host (release package)

1. Unpack the **same release** as the one that was running and restore `.env`, `tls/`,
   `email-templates/` and `license/` into its directory. Don't start it yet.
2. Load the images and create the containers: `./install.sh`. Then stop CertAlert alone
   (`docker compose stop cert-alert`) and restore the database as in
   [Operator guide → Restore](operator-guide.md#restore). The database holds the license and the
   installation id, so restoring it keeps them. If no database backup survives:
    - an unbound license works again once you upload the `.lic` file on the license screen, or
      leave it in `license/license.lic` for `install.sh` to copy in;
    - a bound license is refused by the new installation id. Note the new id on the license
      screen and ask ENS Solutions to re-bind or re-issue the license.
3. Start it: `docker compose up -d`.
4. Check: `docker compose ps` shows it healthy; the license screen shows the expected state and
   installation id; **Admin → Audit log → Verify integrity** reports the chain intact; sign in as
   an administrator; run **Sync directory** and check the run completes.

With Helm: install the same chart version into the new cluster with your values and Secrets and
`replicaCount: 0`, restore the database into your PostgreSQL (with it the license and the
installation id), then set `replicaCount` back to what you ran. Above one replica, keep
`persistence.license.imported: true` in your values.

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
