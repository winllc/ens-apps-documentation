# Sizing, availability and recovery

This page covers how big to make ReadyRoom and its database, what high availability it supports,
how to recover it after losing a host or cluster, and how to go back to the previous release.
Backups themselves are in [Operations](operator-guide.md#backups).

## Sizing

!!! warning "Unbenchmarked starting points"
    ENS has not load-tested ReadyRoom and publishes no capacity figures. The figures below are the
    shipped defaults and the reasoning behind them, not measured capacities. Start with them, watch
    the signals named here, and adjust.

### What drives the size

| Driver | How the code handles it | Settings |
|---|---|---|
| Workstations | Every logon, lock, unlock and logoff is one request from the Windows client and one row in `check_in_out_records`. Logons bunch up at the start of the working day | |
| Database growth | `check_in_out_records` grows by one row per workstation event, and by default nothing is deleted: 5,000 users over 90 days make about 1.2 million rows. With `APPLICATION_RETENTION_EVENTSDAYS` set, older rows are summarised by month and deleted. The audit trail grows too, until `ENS_AUDIT_RETENTION_DAYS` removes old events | [Retention](operator-guide.md#retention), `APPLICATION_RETENTION_EVENTSDAYS`, `ENS_AUDIT_RETENTION_DAYS` |
| The directory | Users, group trees and the org chart are cached (at most 5,000 entries each) and refreshed in the background; group memberships are cached for 300 s. LDAP connections are pooled, at most 20 by default | `application.cache-duration-*`, `application.ldap.group-membership-cache-seconds`, `com.sun.jndi.ldap.connect.pool.maxsize` |
| Scheduled jobs | Auto check-out every minute, absence alerts every 5 minutes, the directory refresh hourly, and, when set, the retention purge daily (one day at a time, 500 people per transaction) | `application.inactive-cron.*`, `application.send-absent-notification-cron.*`, `application.refresh-user-records-cron.*`, `application.retention.*` |
| Database connections | Spring Boot's connection pool; requests release their connection when the service call ends (`open-in-view: false`) | `spring.datasource.hikari.maximum-pool-size` (Spring Boot's setting) |

`db/perf/seed_volume.sql` and the manual `QueryPerformanceMeasurement` test in the source tree fill a
throwaway database with 5,000 users over 90 days and time the main queries. They are tools for
measuring on your own hardware; ENS has published no results from them.

### Starting figures

| Component | Shipped default | Notes |
|---|---|---|
| ReadyRoom memory | Helm: request `768Mi`, limit `1536Mi`. The heap is 75% of the limit (`-XX:MaxRAMPercentage=75.0`) | The release package sets no limit |
| ReadyRoom CPU | Helm: request `250m`, no limit | |
| Embedded PostgreSQL (evaluation only) | request `100m` / `256Mi`, limit `1Gi`, `8Gi` volume | In production, size your PostgreSQL for `check_in_out_records`' growth |
| Backup PVC | `10Gi` (`backup.persistence.size`) | Holds `retentionDays` (14) of dumps |
| License volume | `100Mi` | A few small files |

How to adjust:

- **Heap.** Watch `jvm_memory_used_bytes{area="heap"}` (Prometheus, on the management port). If it
  nears the maximum, raise the memory limit.
- **Slow pages.** Turn on `application.diagnostics.request-metrics` to see SQL statements and LDAP
  operations per request, and check the directory's response times first: the LDAP timeouts are 5 s
  (connect) and 30 s (read).
- **Database.** Watch its size, and set `APPLICATION_RETENTION_EVENTSDAYS` if it grows more than
  you want ([Retention](operator-guide.md#retention)).

## High availability

**Run one instance.** The scheduled jobs (auto check-out, absence alerts, the directory refresh) have
no cluster coordination: every instance would run every job, so auto check-outs and absence alerts
could be produced twice. Sessions are held in memory, and the license volume is single-writer. The
Helm chart runs one replica with the `Recreate` strategy; the release package runs one container.
More are not supported.

What you can do:

- **Automatic restart.** Docker restarts the container; Kubernetes restarts a pod whose liveness
  probe fails and reschedules it if its node fails, provided the license volume can be attached on
  another node. Liveness checks ReadyRoom's own state only, so a database or directory outage never
  restarts it.
- **The database.** Run PostgreSQL with your own high-availability setup (with Helm, an external
  server).
- **Cold standby.** Keep a second host or cluster with the same release installed and stopped, and
  copy the backups to it. To fail over, follow [Disaster recovery](#disaster-recovery). Never run
  both at once.

**What a workstation does while ReadyRoom is down.** The Windows client posts each event when it
happens; an event that fails is logged on the workstation (exit code 3) and is not recorded by the
server. A logoff can still be recorded after an outage while its logon's session id is valid (7
days by default). See [Windows client](windows-client.md).

## Disaster recovery

### What to keep, and where

| Item | Holds | Back it up with |
|---|---|---|
| The PostgreSQL database | Attendance history and monthly summaries, statuses and status requests, roles, notes, delegates and group managers, notifications, the calendar, parse rules and the audit trail | `./backup.sh`, the Helm backup CronJob, or your database's backups ([Operations](operator-guide.md#backups)) |
| The license volume (`readyroom_ens-license`, or the license PVC) | The license file and **this installation's id** | `./backup.sh` (release package), or a PVC snapshot. Keep the `.lic` file you received as well |
| Settings | `.env`, `license/`, `tls/`, `kerberos/` (release package); your values file and the Secrets it names (Helm) | Your configuration management. They hold secrets |
| The release | The package you installed, or the image digests and chart version | Keep the download, or mirror the images |
| People and groups | The LDAP directory | The directory's own backups |

Your recovery point is your backup interval: check-ins and changes after the last backup are lost.
Audit events already forwarded to a SIEM are still there.

### Recovering on a new host (release package)

1. Unpack the **same release** as the one that was running and restore `.env`, `license/`, `tls/`
   and `kerberos/` into its directory.
2. Run `./install.sh` to load the images and start ReadyRoom on an empty database.
3. Restore the latest backup:
   `./restore.sh backups/readyroom-db-<stamp>.dump backups/readyroom-license-<stamp>.tar.gz`. It
   stops ReadyRoom, replaces the database and the license volume, and starts it again.
4. If the license volume's backup is lost, the installation has a new id:
    - an unbound license works again once you upload the `.lic` file on the license screen;
    - a bound license is refused by the new id. Note it on the license screen and ask ENS Solutions
      to re-bind or re-issue the license.
5. Check: `docker compose ps` shows it healthy; readiness is `UP`; the license screen shows the
   expected state and installation id; **Audit log → Verify integrity** reports the chain intact;
   sign in, and a test check-in from a workstation arrives.

With Helm: install the same chart version into the new cluster with your values and Secrets and
`replicaCount: 0`, restore the database into your PostgreSQL (the chart's `README.md` gives the
steps for a dump from the backup PVC) and the license PVC from its snapshot, then set
`replicaCount: 1`.

### The audit trail after a restore

The trail and its chain head are restored together from the same dump, so the chain is intact, but
it ends at the backup. Events recorded afterwards continue from the restored sequence number,
reusing numbers your SIEM may already have from the lost period:

- de-duplicate on `id`, never on `seq`;
- a SIEM that pulls with the pull API remembers the last `seq` it read: reset its cursor to the
  restored trail's newest sequence number, or it skips new events until the numbers pass its old
  cursor;
- Splunk HEC and syslog outputs resume from the positions saved in the database and resend what
  followed them, which Splunk can de-duplicate on `id`.

## Rolling back an upgrade

Migrations are not undone, and `restore.sh` restores only into the same or a newer release. A
rollback therefore needs the backup taken before the upgrade, restored by the **older** release.

=== "Release package"

    ```bash
    cd readyroom-<new>
    docker compose stop app
    cd ../readyroom-<old>
    docker compose up -d                       # the old release's images and compose file
    ./restore.sh backups/readyroom-db-<stamp-before-upgrade>.dump
    ```

    The volumes are shared between the two directories, so the old release starts on the newer
    schema until `restore.sh` replaces the database: if it refuses to start, run `restore.sh` first
    (it starts ReadyRoom when it is done). The old images must still be loaded (`docker image ls`).

=== "Helm"

    Scale the Deployment to 0, restore the pre-upgrade dump into your PostgreSQL, then
    `helm rollback readyroom <revision> -n readyroom`. `helm rollback` restores the previous
    manifests and values, not the database. The license PVC is kept.

!!! warning "Not tested by ENS"
    These rollback steps follow from how the releases are built, but ENS has not run them as a
    procedure. Practise a rollback on a test installation before you rely on it.

Check-ins and changes recorded since the upgrade are lost; forwarded audit events stay in your SIEM.
