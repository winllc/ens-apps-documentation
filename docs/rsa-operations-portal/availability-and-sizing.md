# Sizing, availability and recovery

This page covers how big to make the RSA Operations Portal, what high availability it supports, how
to recover it after losing a server or cluster, and how to go back to the previous release.
Backups themselves are in the [Operator Guide](operator-guide.md#database-backups).

## Sizing

!!! warning "Unbenchmarked starting points"
    ENS has not load-tested the Portal or the Connector. The figures below are the shipped defaults
    and the reasoning behind them, not measured capacities. Start with them, watch the metrics
    named here, and adjust.

### What drives the size

| Driver | Why it matters | Settings and limits |
|---|---|---|
| Calls to Authentication Manager | Almost every page an operator or user opens calls AM through the Connector. Their latency is usually AM's, not the Portal's | `ens_rsa_am_call_seconds`; `connector.read-timeout-millis` (120 s) |
| Concurrent operators and self-service users | Each request holds a Portal thread and, while it waits for AM, a Connector thread | Connector Tomcat `maxThreads` 150 on 8443 (fixed in its `server.xml`) |
| The nightly jobs | Between 00:30 and 00:35 the jobs search AM for expiring and unused tokens and send email: the busiest minutes for AM and the SMTP relay | `cron.<job>.cron`, `cron.timezone` |
| Email | Sent asynchronously on a pool of 50 to 500 threads | The relay's own limits |
| Database growth | Small tables, except `AuditEvent`, which grows with every sign-in and change until `ENS_AUDIT_RETENTION_DAYS` removes old events | `ENS_AUDIT_RETENTION_DAYS` |
| AM searches | Free-text user searches return at most 25 results per identity source | `search.useridonly` |
| Failed anonymous steps | Each answers no sooner than the uniform response time (1 s), and holds its request thread that long. Each limited attempt also reads and writes a row in the rate-limit tables | `rsa.rate-limit.uniform-response-time`, `rsa.rate-limit.*` |

### Starting figures

| Component | Helm chart default | Release package | Notes |
|---|---|---|---|
| Portal | request `250m` CPU, `768Mi`; limit `1536Mi` | No limit set | The chart's `javaOpts` sets `-XX:MaxRAMPercentage=75`, so the heap follows the limit |
| Connector | request `100m` CPU, `512Mi`; limit `1Gi` | No limit set | The same heap rule |
| Embedded MySQL (evaluation only) | request `100m`, `512Mi`; limit `1Gi`; `8Gi` volume | The MySQL container, no limit | In production, size your MySQL server for the audit table's growth |
| License volume | `100Mi` | Docker volume | A few small files |
| Backup PVC | `10Gi` (`backup.persistence.size`) | `util/backupdb.sh` writes to the host | Dumps compress well; keep `keepDays` of them |

How to adjust:

- **Heap.** Watch `jvm_memory_used_bytes{area="heap"}` for the Portal and the Connector. If either
  nears its maximum, or a container is killed for running out of memory, raise its memory limit.
- **AM latency.** If `ens_rsa_am_call_seconds` grows with the number of users, AM (or the network
  to it) is the limit, not the Portal. A read-only replica can serve searches:
  `ims.httpinvoker.client.provider.readonly.url` (Helm: `authManager.readOnlyHost`).
- **Audit table.** Set `ENS_AUDIT_RETENTION_DAYS` to your retention policy once a SIEM keeps the
  events, and watch the database's size.

## High availability

**Run one Portal.** The Helm chart accepts `replicaCount` of `0` or `1` only, and uses the
`Recreate` strategy so an upgrade never runs two Portal pods at once. The reasons are in the code:

| State | Where it lives | With a second Portal |
|---|---|---|
| Sign-in sessions, and an emergency access code waiting to be revealed | The Portal's memory | A user whose requests reach the other Portal is signed out, and an operator loses a code not yet revealed |
| Rate limits, backoff and the feature caps (Test My Token, PIN reset links, token swap) | The `RateLimitWindow` and `RateLimitBackoff` tables | Safe: every node counts together |
| Help-desk requests | The `HelpDeskRequest` table; every node expires overdue ones | Safe: a request claimed for running is not run twice |
| License state and installation id | Files on a ReadWriteOnce volume | The second pod can't mount the volume on another node |
| Scheduled jobs | Claimed per occurrence in the `CronLock` table | Safe: one node runs each occurrence |
| Audit trail | The database, under a row lock on `AuditChainHead` | Safe: the chain stays consistent |

The jobs, the audit trail, the rate limits and the help-desk requests were built to be safe with
several Portal nodes, but the sessions and the license volume are not, so more than one Portal is
not supported.

**The Connector** is stateless in the sense that it stores nothing, and the chart allows more than
one (`connector.replicaCount`). Each replica keeps its own AM sessions. A multi-step sign-in (next
tokencode, new PIN) is held for 4 minutes in the memory of the Connector that started it, so with
several replicas a later step can reach a replica that doesn't know it, and the sign-in fails.
More than one Connector replica has not been tested; the release package and the chart's default
run one.

What you can do:

- **Automatic restart.** Docker restarts the containers (`restart: unless-stopped`); Kubernetes
  restarts a pod whose liveness probe fails and reschedules it if its node fails, provided the
  license volume can be attached on another node.
- **The database.** Run MySQL with your own high-availability setup (Helm: an external server).
  The embedded MySQL has none.
- **Authentication Manager.** Point the read-only URL at a replica for searches. Writes and sign-ins
  go to the primary (`ims.httpinvoker.client.provider.url`).
- **Cold standby.** Keep a second server or cluster with the same release installed and stopped,
  and copy the database backups and the settings to it. To fail over, follow
  [Disaster recovery](#disaster-recovery). Never run both against the same database.

Every restart or failover signs users out.

## Disaster recovery

### What to keep, and where

| Item | Holds | Back it up with |
|---|---|---|
| The database `RSAAPP` | Trusted Agents, sites, shipments, appointments, enrollments, exemptions, notifications, the audit trail and its chain head | `util/backupdb.sh`, the chart's backup CronJob, or your MySQL backups ([Operator Guide](operator-guide.md#database-backups)) |
| The license volume (`rsa-operations-portal_ens-license`, or the license PVC) | The license file and **this installation's id** | A copy of the volume, or a snapshot. Keep the `.lic` file you received as well |
| Settings | `.env`, `connector.env`, `mysql.env` (release package); your values file and the Secrets it names (Helm) | Your configuration management. They hold secrets |
| AM trust and Connector certificates | `default_build/ssl/` (`trust.jks`, `connector-ca.crt`, `portal-client.*`), `default_build/connector-tls/`, `connector-tls-ca/` | A copy with the settings. If lost, `util/connector-tls.sh` makes new ones; `trust.jks` must be rebuilt from AM's root certificate |
| Branding | `default_build/properties`, `static`, `email-templates` (release package); the `branding.*` ConfigMaps (Helm) | A copy |
| The release | The package you installed, or the image digests and chart version | Keep the download, or mirror the images |
| Users, tokens, PINs, administrative roles | RSA Authentication Manager | AM's own backup and replication. The Portal holds none of it |

Your recovery point is your backup interval: changes made after the last database backup are
lost. Audit events already forwarded to Splunk or syslog are still there.

### Recovering on a new server (release package)

1. Install the **same release** as the one that was running: unpack it and run
   `./install.sh /opt/rsa-operations-portal`. It stops after creating the settings files.
2. Restore `.env`, `connector.env`, `mysql.env`, `default_build/` and `connector-tls-ca/` from your
   copies, replacing what `install.sh` created.
3. Restore the `rsa-operations-portal_ens-license` volume from your copy before the Portal's first
   start, keeping the files' owner: the Portal runs as uid 1001 and must be able to write there.
   If it is lost:
    - an unbound license works again once you upload the `.lic` file on the license screen;
    - a bound license is refused by the new installation id. Note the new id on the license
      screen and ask ENS Solutions to re-bind or re-issue the license.
4. Start MySQL alone (`docker compose up -d mysql`), wait until it is healthy, and load the backup:
   `gunzip -c RSAAPP-<time>.sql.gz | docker exec -i mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot RSAAPP'`.
5. Start everything: run `./install.sh` again in the install folder (it checks the settings and
   waits for the Portal), or `docker compose up -d`.
6. Check: readiness is `UP`; the Connector reports `UP`; the license screen shows the expected state
   and installation id; **Audit Log → Verify integrity** reports the chain intact; sign in as a PMO
   user and as a Trusted Agent.

With Helm: install the same chart version into the new cluster with your values and Secrets and
`replicaCount: 0`, restore the database into your MySQL server and the license PVC from its
snapshot (or let the init container install the `.lic` file into a new one), then set
`replicaCount: 1`.

### The audit trail after a restore

The audit trail and its chain head are restored together from the same dump, so the chain is
intact, but it ends at the backup. Events recorded afterwards continue from the restored sequence
number, reusing numbers your SIEM may already have from the lost period:

- de-duplicate on `id`, never on `seq`;
- a SIEM that pulls with the pull API remembers the last `seq` it read: reset its cursor to the
  restored trail's newest sequence number, or it skips new events until the numbers pass its old
  cursor;
- Splunk HEC and syslog outputs resume from the cursors saved in the database
  (`AuditOutputState`) and resend what followed them, which Splunk can de-duplicate on `id`.

## Rolling back an upgrade

!!! warning "Not tested by ENS"
    The outlines below follow from how the releases are built, but ENS has not run them as a
    procedure. Practise a rollback on a test installation before you rely on it.

A rollback always needs the database backup taken before the upgrade: `upgrade.sh` writes it to
`backups/RSAAPP-before-<version>-<time>.sql.gz` in the install folder; with Helm, take it
yourself. The new release migrates the schema on its first start (Flyway), and an older release
is not built to run on a newer schema.

**From MySQL 8.4 back to a release with MySQL 8.0.** MySQL upgrades its data files in place, and
8.0 can't start on data 8.4 has upgraded. Going back means a new, empty MySQL 8.0 data volume and
the pre-upgrade dump:

1. `docker compose down` in the install folder. Do **not** use `-v`: that would delete the license
   volume too.
2. Keep a copy of the current database first (`util/backupdb.sh`), in case you need to go forward
   again.
3. Remove only the database volume: `docker volume rm rsa-operations-portal_mysql_data`.
4. Put the previous release's files back in the install folder (its `docker-compose.yml`, its images
   loaded with `docker load -i images/...`), keeping your settings files.
5. Start MySQL alone. It creates an empty database and the application user from `mysql.env`.
   Load the pre-upgrade dump into it, then start the Portal and the Connector.

**Settings.** Releases before HTTPS to the Connector expect `http://connector:8080` and the AM
passwords as `-D` options. `upgrade.sh` kept the originals as `.env.before-split.*` and
`connector.env.before-secrets.*` where it changed them; a rollback across that change needs the
matching settings for the older Connector.

**Helm.** `helm rollback <release> <revision>` restores the previous manifests and values, but not
the database: restore the pre-upgrade backup into your MySQL server first, with the Portal scaled to
0. The license PVC is kept either way.
