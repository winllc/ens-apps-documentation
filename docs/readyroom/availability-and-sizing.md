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
| Database connections | Spring Boot's connection pool, one per replica; requests release their connection when the service call ends (`open-in-view: false`) | `spring.datasource.hikari.maximum-pool-size` (Spring Boot's setting) |

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
| License volume | `100Mi` | Read only on the first start after upgrading from a release that kept the license in files |

How to adjust:

- **Heap.** Watch `jvm_memory_used_bytes{area="heap"}` (Prometheus, on the management port). If it
  nears the maximum, raise the memory limit.
- **Slow pages.** Turn on `application.diagnostics.request-metrics` to see SQL statements and LDAP
  operations per request, and check the directory's response times first: the LDAP timeouts are 5 s
  (connect) and 30 s (read).
- **Database.** Watch its size, and set `APPLICATION_RETENTION_EVENTSDAYS` if it grows more than
  you want ([Retention](operator-guide.md#retention)).

## High availability

ReadyRoom can run as several replicas behind one Service, against one PostgreSQL database. A
rolling update then keeps one replica serving throughout, so the Windows client's events, which it
sends once and never retries, are not lost during an upgrade. For the suite-wide picture, see
[Sizing, availability and recovery](../availability-and-sizing.md).

**Shared through the database:**

| State | How |
|---|---|
| Browser sessions | Spring Session JDBC |
| Scheduled jobs | Each job (the directory sync, auto check-out, absence alerts, the retention purge, audit retention and session clean-up) holds a lock (`shedlock`) while it runs, so it runs on one replica at a time. A replica that finds the lock held skips that run. A lock left by a replica that died lapses within the job's interval |
| Audit forwarding (Splunk, syslog) | Every replica runs a forwarder; each batch is delivered under a lock, so none goes out twice |
| License, installation id, revisions seen | The `ens_license_*` tables. An upload on one replica is in force on all within seconds |
| License mode changes | Each replica notices; the change is recorded once |

**Per replica**, by design: the access-denial summaries (each replica collapses and records its
own), the limit on refused logoffs per client address (`application.logoff.refused-per-minute`, so
with N replicas up to N times as many refusals a minute), and the caches of directory users and
groups, revocation answers and CRLs.

**Helm.** Several replicas need the license volume off, after the first start of this release has
imported it into the database:

```yaml
replicaCount: 2
persistence:
  license:
    enabled: false
    imported: true
```

With more than one replica the chart adds a PodDisruptionBudget (`podDisruptionBudget.minAvailable`,
1) and prefers to put the replicas on different nodes. The strategy is `RollingUpdate` (`maxSurge`
1, `maxUnavailable` 0). Each pod leaves the Service before it stops (`preStopSleepSeconds`, 10),
then finishes requests and any running job (`terminationGracePeriodSeconds`, 60). While the
license is on a ReadWriteOnce volume, the chart refuses `replicaCount` above 1 and uses `Recreate`,
so an upgrade is a short outage. `audit.file.persistence` is refused with several replicas: forward
the audit trail to syslog or Splunk instead.

**Release package.** It runs one ReadyRoom container, as before, so an upgrade is a short outage.

**Automatic restart and the database.** Docker restarts the container; Kubernetes restarts a pod
whose liveness probe fails and reschedules it if its node fails. Liveness checks ReadyRoom's own
state only, so a database or directory outage never restarts it. Run PostgreSQL with your own
high-availability setup (with Helm, an external server).

**Outages and absence alerts.** Every replica updates a heartbeat in the database every 15 seconds.
When the whole service was down (with one replica, every upgrade), the gap is recorded
(`service_outage`), because check-ins sent during it were lost. Absence alerts about people whose
check-in time plus grace fell after the outage began wait until `application.ha.outage-grace`
(`APPLICATION_HA_OUTAGEGRACE`, `30m`; `0` turns this off) after its end, and a session last seen
locked before the outage is checked out automatically only `checkOutAfterMinutes` after its end.
With two replicas and rolling updates there is no such gap.

**What a workstation does while ReadyRoom is down.** The Windows client posts each event when it
happens; an event that fails is logged on the workstation (exit code 3) and is not recorded by the
server. A logoff can still be recorded after an outage while its logon's session id is valid (7
days by default). See [Windows client](windows-client.md).

## Disaster recovery

### What to keep, and where

| Item | Holds | Back it up with |
|---|---|---|
| The PostgreSQL database | Attendance history and monthly summaries, statuses and status requests, roles, notes, delegates and group managers, notifications, the calendar, parse rules, the audit trail, sessions, and the license and **this installation's id** | `./backup.sh`, the Helm backup CronJob, or your database's backups ([Operations](operator-guide.md#backups)) |
| The license volume (`readyroom_ens-license`, or the license PVC) | The license state of a release that kept it in files, imported into the database on the first start after upgrading, and not read after that | `./backup.sh` (release package), or a PVC snapshot. Keep the `.lic` file you received as well |
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
4. The installation id is in the database, so the restore keeps it. If no database backup
   survives, the installation has a new id:
    - an unbound license works again once you upload the `.lic` file on the license screen;
    - a bound license is refused by the new id. Note it on the license screen and ask ENS Solutions
      to re-bind or re-issue the license.
5. Check: `docker compose ps` shows it healthy; readiness is `UP`; the license screen shows the
   expected state and installation id; **Audit log → Verify integrity** reports the chain intact;
   sign in, and a test check-in from a workstation arrives.

With Helm: install the same chart version into the new cluster with your values and Secrets and
`replicaCount: 0`, restore the database into your PostgreSQL (the chart's `README.md` gives the
steps for a dump from the backup PVC; the database holds the license and the installation id), then
set `replicaCount` back to what you ran.

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
