# Sizing, availability and recovery

This page summarises, for the whole suite, how big to make each application, what high
availability it supports, what to back up, and how to recover or roll back. Each product's own
page has the details:

- [CertAlert](cert-alert/availability-and-sizing.md)
- [Directory Services Portal](directory-services-portal/availability-and-sizing.md)
- [ReadyRoom](readyroom/availability-and-sizing.md)
- [RSA Operations Portal](rsa-operations-portal/availability-and-sizing.md)

## Sizing

!!! warning "Unbenchmarked"
    ENS has not load-tested any of the applications and publishes no capacity figures. In
    particular, CertAlert has not been tested with 100,000 certificates. The figures below are the
    Helm charts' defaults; the product pages explain what drives each application's size, which
    metrics to watch, and (for CertAlert) conservative starting points for a large directory.

| Product | Application (Helm default) | What makes it grow |
|---|---|---|
| CertAlert | request `250m` CPU, `768Mi`; limit `1536Mi` | Directory sweeps and revocation runs (time, and cached CRLs in memory); one database row per certificate; the audit trail |
| Directory Services Portal | API: request `250m`, `768Mi`; limit `1536Mi`. Web tier: request `50m`, `64Mi`; limit `256Mi` | Metric scans and analyses (up to `METRICS_MAX_ENTRIES`, 100,000 entries, per source) in the API's heap; the audit store on disk |
| ReadyRoom | request `250m`, `768Mi`; limit `1536Mi` | Workstation events, and the `check_in_out_records` table, which ReadyRoom never prunes; the audit trail |
| RSA Operations Portal | Portal: request `250m`, `768Mi`; limit `1536Mi`. Connector: request `100m`, `512Mi`; limit `1Gi` | Calls to Authentication Manager, whose latency is usually AM's; the audit table |

With the charts' defaults the Java heap is 75% of the memory limit (`-XX:MaxRAMPercentage=75`), so
raising the limit raises the heap. The product pages name the metrics to watch.

## High availability

**Every application can run as several replicas** behind one Service or load balancer, with
the Helm chart. Sessions, scheduled-job locks, single sign-on requests in progress and the license
state (installation id, license, revisions seen) are kept in the database, so any replica serves any
request, each scheduled job runs on one replica at a time, and a restart or rolling update signs
nobody out. Each product page lists exactly what is shared and what stays per replica.

| Product | Several replicas | What stays on one |
|---|---|---|
| CertAlert | `replicaCount` | Nothing that matters: per-replica caches, sign-in failure counts and collapsed audit events |
| Directory Services Portal | `api.replicaCount`, with a shared PostgreSQL (`DSP_DATABASE_URL`, `database.url` in the chart); without it the API stays one instance, as before. The web tier (`web.replicaCount`) is stateless | Background tasks run on the instance that started them (their progress is shared) |
| ReadyRoom | `replicaCount` | Per-replica caches and limits; two replicas also keep events flowing through an upgrade |
| RSA Operations Portal | `replicaCount` for the Portal | **The Connector runs as one replica** (the chart refuses more), and emergency access codes are shown only by the replica that made them, so the Portal needs session affinity |

With more than one replica each chart uses a RollingUpdate strategy (a new pod ready before an old
one goes) and a PodDisruptionBudget (`podDisruptionBudget.minAvailable`, default 1). The license
volume is ReadWriteOnce, so above one replica the chart needs it turned off (once the first start
of this release has copied the license state into the database) or ReadWriteMany; it refuses
otherwise. Run that first start with one replica. The Compose release package runs one instance
of each application.

Also:

- **Automatic restart**: Docker's restart policy, systemd (the Directory Services Portal's native
  package), or Kubernetes probes and rescheduling.
- **Database high availability** is yours: run PostgreSQL (CertAlert, ReadyRoom, and the Directory
  Services Portal when it has a database) or MySQL (RSA Operations Portal) with your own
  replication or failover, and point the application at it.
- **Cold standby** remains an option for a site without Kubernetes: a second host with the same
  release installed and stopped, with backups copied to it. Never run it while the primary could
  still be running.

## Backups and disaster recovery

| Product | What to back up | Backup tooling in the release |
|---|---|---|
| CertAlert | The PostgreSQL database; the license volume | None scheduled. `upgrade.sh` writes a database dump before it upgrades; otherwise use `pg_dump` or your database's backups |
| Directory Services Portal | The data directory (configuration, audit store, analysis results); the license directory | `./portal.sh backup` and `./portal.sh restore` (Docker package; the license volume is not included). Native and Helm: `tar` or volume snapshots |
| ReadyRoom | The PostgreSQL database; the license volume | `./backup.sh` (database and license volume, kept 14 days) and `./restore.sh`. Helm: a backup CronJob, off by default (`backup.enabled`) |
| RSA Operations Portal | The MySQL database; the license volume; the AM trust store and the Connector's certificates | `util/backupdb.sh`. Helm: a backup CronJob, off by default (`backup.enabled`) |

For every product, also keep the settings (`.env` files, or your values file and the Secrets it
names), the release you installed (or the image digests and chart version), and the license file
you received. Directory data, and for the RSA Operations Portal everything in Authentication
Manager, are backed up by those systems, not by the applications.

**The installation id** is in the database (for the Directory Services Portal without a database,
in the license volume), next to the license and the revisions seen. Restore it with the data, and
a license bound to the installation keeps working. If it is lost, the application gets a new id: an unbound license
works again once you upload the file, but a bound license is refused, and ENS Solutions has to
re-bind or re-issue it.

**Recovering** follows the same order everywhere: install the **same release** that was running,
restore the settings and the license volume, restore the data, start, and check the license screen,
**Verify integrity** on the audit page, and a sign-in.

**The audit trail after a restore** ends at the backup. Events recorded afterwards reuse sequence
numbers your SIEM may already have from the lost period, so de-duplicate on `id`, never on `seq`,
and reset a pull-API collector's cursor to the restored trail's newest sequence number. See
[Audit logging](audit-logging.md).

## Rolling back an upgrade

No application undoes its own data changes on a downgrade, and an older release is not built to
run on data a newer one has written. Every rollback therefore means:

1. a backup taken **before** the upgrade. CertAlert's, the Directory Services Portal's and the RSA
   Operations Portal's `upgrade.sh` write one; for ReadyRoom, run `./backup.sh` first; with Helm and
   the native package, take it yourself;
2. stopping the new release, restoring that backup, and starting the previous release (or
   `helm rollback`, which restores the previous manifests and values but never the data).

Anything recorded since the upgrade is lost from the application; forwarded audit events stay in
your SIEM. ENS has not tested these rollbacks as procedures: practise one on a test installation
before you rely on it.
