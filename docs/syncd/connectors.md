# Connectors

A connector is one system syncd reads, writes, or both. Sources are SQL databases,
MongoDB, LDAP directories, Active Directory, CSV and other delimited files, Microsoft
Entra ID, and since 0.5.0 REST and OData services and the HR services Workday, SAP
SuccessFactors and UKG Pro, plus any database that Debezium Server can capture or that has
a JDBC driver. Targets are LDAP directories, Active Directory, SaaS applications that
speak SCIM 2.0, and Entra ID.

This page says how each connector reads, what it needs on the source side, and which
settings matter. Every key is listed in [Configuration](configuration.md#connectors-connectors);
the rules that turn source records into entities are in
[Computed attributes and rules](rules.md).

## At a glance

| Type | Reads | Streams changes with | Delta reads by | Checkpoint | Pack |
|---|---|---|---|---|---|
| `postgres` | Tables, views, SQL queries | Logical replication (`pgoutput`) through a replication slot | A column (`updated_at`, a counter) | LSN | Core |
| `mysql` | Tables, views, SQL queries (MySQL 8, MariaDB 10.5+) | The row-based binary log, read as a replica | A column | Binary log file and position | Core |
| `sql_server` | Tables, views, SQL queries (SQL Server 2016+, Azure SQL Managed Instance) | Change data capture, or change tracking | A column (`rowversion`) | LSN, or change tracking version | Enterprise Database |
| `mongodb` | Collections | Change streams (replica set or sharded cluster) | A field | Resume token | Enterprise Database |
| `ldap` | Entries under a base | syncrepl (RFC 4533), or the directory's change log (`cn=changelog`, OpenLDAP access log) | `modifyTimestamp`, or another ordered attribute | Sync cookie, or change number / log time | Core |
| `active_directory` | Entries under a base | DirSync | `uSNChanged` | DirSync cookie, or USN, per domain controller | Core |
| `debezium` | Change events from a Debezium Server sidecar (Oracle, Db2 and others) | Always streams | | Debezium keeps its own offsets | Enterprise Database |
| `jdbc` | Tables and queries through the JDBC bridge (Db2, Informix, Oracle, any JDBC database) | Not supported; use `debezium` | A column | Watermark | Enterprise Database |
| `csv` | A delimited file (CSV, TSV), or the newest file matching a glob | Not supported: every read is a full snapshot (`watch: true` reads again when the file changes) | | None | Core |
| `entra` | Entra ID users and groups, through Microsoft Graph | Delta queries (always streams) | | Delta link | Cloud and SaaS |
| `rest` | Records of a REST or OData service, page by page | Not supported | An "updated since" parameter in the service's own time, or OData delta links | The service's time, or the delta link | Cloud and SaaS |
| `workday`, `successfactors`, `ukg` | Workday (a RaaS report, or the staffing API's workers), SAP SuccessFactors (OData v2 entities), UKG Pro (personnel APIs), through the REST reader | Not supported | The service's "updated since" filter | As `rest` | Cloud and SaaS |
| `scim` | Nothing: a target only | | | | Cloud and SaaS |

Publication targets are `ldap`, `active_directory`, `scim` and `entra` connectors. One
connector can be both a source and a target.

From 0.5.0 each connector type belongs to a license pack: Core is in every license, the
others are named in it. Under a license, a configuration that uses a type the license
leaves out cannot be saved or published (during the evaluation and its grace period
nothing is locked); one that already runs keeps running. Feature packs (Password Sync, High Availability, Extensibility) and
the details are in [Licensing](licensing.md#packs).

## Read modes

Every object has a `mode`:

- **`scrape`** (the default) reads everything in scope every `interval_secs`, or at the
  times of a cron `schedule`. The engine compares each record with what it staged before,
  by anchor and content hash, so only real changes go further. A record not seen in a
  full read is deleted.
- **`delta`** reads what changed since the last read, every `interval_secs`, found by a
  `watermark`. Every `full_interval_secs` (one day by default), or on `schedule`, it reads
  everything instead, which finds deletes. A database read starts from the highest
  watermark seen *before* the previous read, so a row changed while that read ran is read
  again, not missed. A row whose watermark is set before its transaction commits, and
  which commits after a read has passed that value, is only seen at the next full read:
  keep `full_interval_secs` to what that delay may cost.
- **`stream`** follows changes as they happen. On first start it reads everything, then
  follows the change feed from a position taken before the read, so nothing is missed in
  between. Each checkpoint is committed in the same write as the changes before it, so a
  restart resumes where it left off, and a change delivered twice is harmless.

A `csv` object is always `scrape`, an `entra` object always `stream`, and an object of a
REST connector (`rest`, `workday`, `successfactors`, `ukg`) `scrape` or `delta`, never
`stream`.

When a stream's position is no longer available on the server (a purged binary log, CDC
cleanup, an expired resume token, a trimmed change log, a failover to a server with
different counters), the stream starts over with a full read, whose sweep finds the
deletes.

A source that fails is restarted with a wait that doubles from one second up to
`engine.source_retry_max_secs` (60 by default).

Connecting to PostgreSQL, MySQL, SQL Server or MongoDB fails after 10 seconds when the
server does not answer. The limit covers the whole connect, TCP, TLS and login, so a server
that accepts the connection and then never answers (a hung process, a proxy whose backend
is gone) fails the source, which is then restarted, instead of leaving it starting for
good. The error names the server: `postgres: db1:5432: no answer within 10s`. For MySQL the
limit also covers the binary log request and its first event; once a stream is open, a
quiet binary log is normal and has no limit. LDAP and Active Directory connections are
bounded by their `operation_timeout_secs`.

## PostgreSQL

### What it reads

- **Tables and views** (`table: people`, `table: hr.people`) and **SQL queries**
  (`query: SELECT ...`), including joins across tables. Every column becomes an
  attribute.
- **Values.** Integers and booleans arrive typed, `bytea` as bytes, and everything else as
  its PostgreSQL text form, the same text the replication stream carries. So a row from a
  full read and the same row from the stream compare equal. Dates arrive as text unless
  listed under the object's `timestamps`.
- **TLS** through `tls: require` or `verify_full`, for both the query and the replication
  connection.

### Streaming with logical replication

```yaml
- name: hr_db
  type: postgres
  connection:
    host: hr-db.example.com
    user: syncd
    password: { env: HR_DB_PASSWORD }
    dbname: hr
    tls: verify_full
  objects:
    - type: person
      anchor: id
      table: people
      mode: stream
      cdc:
        slot: syncd_hr          # created if missing
        publication: syncd_hr_pub   # created if missing
```

On the source side:

- The server needs `wal_level = logical`, and free replication slots and WAL senders
  (`max_replication_slots`, `max_wal_senders`). Changing `wal_level` needs a server
  restart.
- syncd creates what is missing when the stream first starts:
  `CREATE PUBLICATION <publication> FOR TABLE <tables>` and a `pgoutput` slot with
  `pg_create_logical_replication_slot`. The account needs the PostgreSQL privileges for
  that (the `REPLICATION` attribute for the slot and the replication connection; for the
  publication, what PostgreSQL requires to publish those tables). Otherwise, have a DBA
  create the publication and the slot under the configured names: syncd uses existing
  ones as they are.
- A publication syncd finds already present is checked when the stream starts. Tables
  the object follows (its `table`, or the tables in `cdc.watch`) that the publication
  lacks are added with `ALTER PUBLICATION <publication> ADD TABLE <tables>`, and the
  addition is logged. When the account may not alter it, the stream fails, naming the
  missing tables and the statement for a DBA to run. A publication `FOR ALL TABLES`
  covers everything and is left alone.
- Deletes are matched by the anchor in the row before the change. Make the anchor the
  table's primary key, or set `REPLICA IDENTITY FULL` on the table.
- Each streamed object names its own slot in `cdc.slot`. A slot keeps WAL on the server while syncd is
  stopped or stalled, so watch `pg_replication_slots`, and drop a slot you no longer use.

A row whose large (TOASTed) values did not change arrives without them; syncd then reads
the row again.

**`TRUNCATE`.** Since 0.5.0 a `TRUNCATE` of a followed table **deletes the table's
entries in the targets**; 0.4.1 ignored it. When the transaction commits, the stream
reads the object in full, so rows the same transaction put back stay and the rest are
deleted, subject to the deletion thresholds. Set a `deletion_threshold` on the source
connector to have an emptied table held for approval; `syncd approve "source
<connector>/<object>"` then has the stream read the object in full at once and apply it.
A publication syncd creates publishes truncates. One created by hand without `truncate`
in its `publish` list is warned about at start (a `TRUNCATE` then goes unnoticed until the
next full read); to have it noticed:

```sql
ALTER PUBLICATION syncd_hr_pub SET (publish = 'insert, update, delete, truncate');
```

**An idle stream.** Since 0.5.0 an idle stream reports its position regularly and is no
longer restarted every five minutes; a dropped connection is noticed within about two
minutes. A stream resumed with nothing to read shows `streaming` at once.

### Streaming a query

A `query` object streams by following the base tables the query reads. `cdc.watch` maps
each table to how a changed row finds the query rows it affects; those rows are read again
through the query when the source transaction commits.

```yaml
- type: dept
  anchor: emp
  query: SELECT p.employee_id AS emp, d.code FROM people p JOIN departments d ON d.name = p.department
  mode: stream
  cdc:
    slot: syncd_dept
    publication: syncd_dept_pub
    watch:
      people: employee_id                         # this column is the query's anchor
      departments:                                # SQL returning the affected anchors
        key: name
        anchors: "SELECT employee_id FROM people WHERE department = $1"
```

`$1` is the changed row's `key` column, as text; cast it if needed (`$1::int`). Deletes
need `key` in the old row: the primary key, or `REPLICA IDENTITY FULL`.

## MySQL and MariaDB

`type: mysql` covers MySQL 8 and MariaDB 10.5 and newer.

- **Reads** tables and queries as PostgreSQL does: integers as integers, `BINARY`,
  `VARBINARY` and `BLOB` columns as bytes, everything else as text.
- **Streaming** reads the binary log as a replica would, from the last committed file and
  position. It takes only the keys of changed rows from the log (the anchor of a table
  object, or a watched table's key column of a query object), and when the transaction
  commits reads those rows again through the object's own `SELECT`. Rows found are
  upserted; anchors with no row any more are deleted. So column types are exactly what a
  full read sees.
- Before reading the rows again, a locking read (`LOCK IN SHARE MODE`) waits until the
  transaction holding them has committed, because the binary log can carry a transaction a
  moment before other sessions see it.

```yaml
- name: hr
  type: mysql
  connection:
    host: hr-db.example.com        # port 3306 unless set
    user: syncd
    password: { env: HR_DB_PASSWORD }
    dbname: hr
    tls: verify_full
    ca_file: /etc/syncd/hr-db-ca.pem
  objects:
    - type: person
      anchor: id
      table: people
      mode: stream
      cdc: { server_id: 4101 }     # unique among the server's replicas
    - type: contractor             # where streaming isn't possible
      anchor: id
      table: contractors
      mode: delta
      watermark: updated_at
      interval_secs: 60
      full_interval_secs: 86400
```

On the source side:

- The binary log on, in row format: `log_bin` with `binlog_format=ROW` (the MySQL 8
  defaults). The key columns must be in the row images: `binlog_row_image=FULL`, or
  `MINIMAL` when the key is the primary key.
- The account needs `SELECT`, `REPLICATION SLAVE` and `REPLICATION CLIENT`, and on MySQL
  `LOCK TABLES` (or `UPDATE`) for the locking read. Without it the read goes ahead and
  syncd logs it once.
- `cdc.server_id` must be unique among the server's replicas and other binary log
  readers.
- A position the server has already purged starts over with a full read.
- A binary anchor that is not valid UTF-8 goes through a lossy conversion; select a text
  form of the key instead (`HEX(id)`).

## SQL Server

`type: sql_server` covers SQL Server 2016 and newer and Azure SQL Managed Instance. Tables
and queries read like PostgreSQL's: integers as integers, `bit` as booleans, `binary`,
`varbinary`, `image` and `rowversion` as bytes, everything else as text.

### Change data capture

Streaming polls the change tables of change data capture every `cdc.poll_ms`
(1000 ms by default). It takes the keys of changed rows from them and reads those rows
again through the object's `SELECT`. Change tables hold only committed transactions.
Every capture instance of a table is read, so a second instance made for a schema change
is followed while both exist.

On the server, enable change data capture for the database and each streamed table; SQL
Server Agent runs the capture job:

```sql
EXEC sys.sp_cdc_enable_db;
EXEC sys.sp_cdc_enable_table @source_schema = 'dbo', @source_name = 'people', @role_name = NULL;
```

The account needs `SELECT` on the tables and their captured columns, and membership of the
capture instance's gating role when `@role_name` names one. If the cleanup job has removed
changes after the checkpoint (the retention is three days by default), the stream starts
over with a full read.

```yaml
- name: hr
  type: sql_server
  connection:
    host: hr-db.example.com        # port 1433 unless set
    user: syncd
    password: { env: HR_DB_PASSWORD }
    dbname: hr
    tls: verify_full
    ca_file: /etc/syncd/hr-db-ca.pem
  objects:
    - type: person
      anchor: id
      table: dbo.people
      mode: stream
      cdc: {}                      # cdc: { poll_ms: 1000 } is the default
    - type: contractor             # where change data capture isn't enabled
      anchor: id
      table: dbo.contractors
      mode: delta
      watermark: row_version       # a rowversion column
      interval_secs: 60
      full_interval_secs: 86400
```

### Change tracking

Change tracking needs no SQL Server Agent, so it also works on Express and Azure SQL
Database. Set `cdc: { change_tracking: true }`, and on the server:

```sql
ALTER DATABASE hr SET CHANGE_TRACKING = ON (CHANGE_RETENTION = 2 DAYS, AUTO_CLEANUP = ON);
ALTER TABLE dbo.people ENABLE CHANGE_TRACKING;
GRANT VIEW CHANGE TRACKING ON dbo.people TO syncd;
```

- Change tracking reports only the primary key of changed rows, so the anchor (or a
  watched table's key column) must be part of the table's primary key. The stream checks
  this, and that tracking is on, when it starts.
- The checkpoint is the database's change tracking version. A checkpoint older than a
  table's minimum valid version (the retention passed it, or tracking was switched off and
  on) starts over with a full read. So does switching between change data capture and
  change tracking.

## MongoDB

- **An object reads one collection**, optionally narrowed by `filter` (a query document in
  extended JSON) and `attributes` (the fields to read).
- **Values.** Integers and booleans stay typed; strings and the rest are text (an
  ObjectId as hex, a date as RFC 3339, a UUID in its usual form); BSON binary is bytes.
  An embedded document's fields become `outer_inner` attributes (`name.given` is read as
  `source.name_given`), and an array becomes a multi-valued attribute.
- **Streaming** follows the collection's change stream from the last resume token. It
  takes the `_id`s of changed documents and reads them again through the object's filter:
  found documents are upserted, the others deleted. A document that stops matching the
  filter leaves the view like a deleted one. So streamed objects need `anchor: _id`. A
  resume token the server no longer holds, or the collection being dropped or renamed,
  starts over with a full read.

On the source side, change streams need a replica set or a sharded cluster (MongoDB Atlas
is one). The account needs the `read` role on the database. Credentials go in `user` and
`password`, never in the URL; `mongodb+srv://` deployments need `tls: verify_full` (or
`require`).

```yaml
- name: hr
  type: mongodb
  connection:
    url: mongodb+srv://hr-cluster.example.mongodb.net/?authSource=admin
    user: syncd
    password: { env: HR_DB_PASSWORD }
    dbname: hr
    tls: verify_full                # the Mozilla roots unless ca_file is set
  objects:
    - type: person
      anchor: _id                   # change streams identify documents by _id
      collection: people
      filter: '{"status": "active"}'
      mode: stream
    - type: contractor              # where change streams aren't available
      anchor: _id
      collection: contractors
      attributes: [login, name.given, name.family]
      mode: delta
      watermark: updatedAt
      interval_secs: 60
```

## LDAP directories

An `ldap` object reads the entries under `base` that match `filter` (with `scope`, `sub` by
default), with the listed `attributes` or every user attribute. Full reads use the paged
results control, 500 entries per page. `failover_urls` lists replicas tried in order when
`url` does not answer.

```yaml
- name: corp_ldap
  type: ldap
  connection:
    url: ldaps://ldap1.corp.example.com
    failover_urls: [ldaps://ldap2.corp.example.com]
    bind_dn: cn=syncd,ou=Services,dc=corp,dc=com
    password: { env: CORP_LDAP_PASSWORD }
  objects:
    - type: person
      anchor: entryUUID
      mode: stream                  # syncrepl
      base: ou=People,dc=corp,dc=com
      filter: (objectClass=inetOrgPerson)
      attributes: [uid, mail, employeeNumber, telephoneNumber, title, jpegPhoto]
```

The bind account needs read access to the entries and attributes the objects read.

**A private CA.** An `ldaps://` or StartTLS server's certificate is checked against the
system's trust store unless the connection names a `ca_file` (since 0.5.0): PEM CA
certificates, then the only ones trusted for that connector, such as an Active Directory
domain's own CA. The file is read at each connection, so a renewed CA counts from the
next one, and it serves reads, exports, password delivery and self-service alike. On
Kubernetes, `engine.extraCaCertificates` mounts such files under `/etc/syncd/ca`.

```yaml
connection:
  url: ldaps://dc1.corp.example.com
  ca_file: /etc/syncd/corp-ca.pem
```

**Passwords in the clear.** Since 0.5.0 a configuration that sends passwords (password
delivery, initial passwords, self-service) to a directory on another host over `ldap://`
without `starttls` no longer validates: add TLS, or set
`passwords: { allow_cleartext: true }`. A loopback address and `ldapi://` are exempt.
Active Directory never takes passwords in the clear. A password read from a vault is read
again after a refused bind, so a rotated one is picked up without a restart.

### Containers renamed in a source

Renaming a container (`ou=Contractors` to `ou=Vendors`) changes the DN of every entry
under it, but a directory reports only the container. syncrepl sends nothing for the
entries under it, delta reads miss them (their `modifyTimestamp` and `uSNChanged` do not
change), and DirSync reports the container only. Without help, the entries' `source.dn`
would stay stale until the next full read, which a stream never makes, and anything
matching on a source DN (a group's `member` values, say) would go wrong.

So an `ldap` or `active_directory` object read with `mode: stream` or `delta` and
`scope: sub` has its containers checked every `container_check_secs` (60 by default; `0`
turns it off):

- The check reads the `organizationalUnit`, `container`, `organization`, `locality`,
  `domain`, `nsContainer` and `builtinDomain` entries under the base, with their ids
  (`entryUUID`, `objectGUID` or `nsUniqueId`), and compares their DNs with the last
  check's. A change of spelling alone is not a rename.
- The object's entries under each container renamed since are read again, as updates;
  for nested renames, only those under the outermost one. Each re-read is logged:
  `container renamed in the source; its entries were read again`.
- The last check's DNs are kept as a checkpoint of their own, committed with the entries
  it read again, so a rename made while the engine was stopped is found when it starts.
- The check runs alongside the object's reader on its own connection. Either failing
  restarts both. `syncd run --once` makes no check.

A rename is found within `container_check_secs`, not at once. Containers of other object
classes are not checked; a full read still corrects their entries' DNs. Change log streams
also read the entries below a renamed container themselves; see
[Streaming from a change log](#streaming-from-a-change-log).

### Delta reads

`mode: delta` searches for entries whose `watermark` attribute is at or after the last
value seen (`modifyTimestamp` by default, or another attribute with an ordering rule, such
as `entryCSN`).

### Streaming with syncrepl

`mode: stream` without `changelog` uses the Content Synchronization Operation (RFC 4533):
one long-lived `refreshAndPersist` search per object.

- The server needs it: OpenLDAP's `syncprov` overlay, or 389 Directory Server's content
  synchronisation.
- Entries are identified by `entryUUID`, so the object needs `anchor: entryUUID`.
- Without a cookie the server sends the whole content, treated as a full read. With one it
  sends what changed since, then adds, modifies and deletes as they happen.
- OpenLDAP resumes incrementally only while the backlog fits its `syncprov-sessionlog`.
  Beyond it, the refresh falls back to present mode, which still works but sends a marker
  for every unchanged entry. Size the session log to the longest outage you want to resume
  from cheaply.
- `idle_timeout_secs` (900 by default) reconnects a session that stays silent that long; a
  quiet session is probed every minute, so a dead connection is noticed within about a
  minute.

### Streaming from a change log

For directories without syncrepl, or where it is not wanted, a streamed object can follow
the directory's log of changes instead:

```yaml
- type: person
  base: ou=People,dc=example,dc=com
  anchor: nsUniqueId
  mode: stream
  changelog: { format: changelog }    # or { format: accesslog }; base: to override
  poll_interval_secs: 5
```

| `format` | Directory | Ordered by | Default `base` |
|---|---|---|---|
| `changelog` | 389 Directory Server and Red Hat Directory Server (Retro Changelog plugin), ForgeRock / OpenDJ, Ping Directory, Oracle DSEE, Oracle Unified Directory | `changeNumber` | `cn=changelog` |
| `accesslog` | OpenLDAP with the access log overlay (`slapo-accesslog`); only successful writes are read | `reqStart` | `cn=accesslog` |

- Every `poll_interval_secs` (5 by default) the stream reads the change entries after its
  checkpoint, and reads each changed entry again with the object's base, scope, filter and
  attributes. Any anchor works, not only `entryUUID`.
- An entry still in scope is upserted. One deleted, moved out of the base, or no longer
  matching the filter is deleted. A renamed entry keeps its anchor, so the target entry is
  renamed, not replaced. Entries below a renamed container are read again where they are
  now.
- The bind DN needs read access to the log's entries.
- The checkpoint belongs to the server that logged it. After a failover, or when the
  server has trimmed the checkpoint's own change entry, the stream starts over with a full
  read. Without a checkpoint it reads in full, starting from the newest change number (the
  root DSE's `lastChangeNumber`); an access log has no counter, so reading starts five
  minutes before now.
- An access log write whose `reqStart` is earlier than one already read (a long write that
  commits late) is only seen at the next full read.

Novell / NetIQ eDirectory's change cache is not supported.

## Active Directory

`type: active_directory` reads like `ldap`, with what Active Directory does differently:

- **Full reads** are paged. A multi-valued attribute with more values than the domain
  controller's `MaxValRange` (1 500 by default), such as a large group's `member`, is read
  a range at a time.
- **Delta reads** use `uSNChanged` by default. USNs belong to one domain controller, so a
  checkpoint from another one (after a failover to a `failover_urls` entry) starts with a
  full read.
- **Streaming** uses the DirSync control. DirSync is polled every `poll_interval_secs`
  (10 by default). It reports only the changed attributes, so syncd reads each changed
  object again in full by its `objectGUID`. Deletes arrive as tombstones identified by
  `objectGUID`, so streamed objects need `anchor: objectGUID`. On first start syncd takes
  a cookie for "now", reads everything, then polls from that cookie. A cookie is only
  trusted on the domain controller that issued it.
- **Binary attributes** (`objectGUID`, `objectSid`, `thumbnailPhoto` and others) arrive as
  bytes; rules turn them into text with `guid()`, `sid()`, `hex()` or `base64()`.

```yaml
- name: ad
  type: active_directory
  connection:
    url: ldaps://dc1.corp.example.com
    failover_urls: [ldaps://dc2.corp.example.com]
    bind_dn: svc-syncd@corp.example.com
    password: { env: AD_PASSWORD }
  objects:
    - type: person
      anchor: objectGUID
      mode: stream
      poll_interval_secs: 10
      base: OU=Staff,DC=corp,DC=example,DC=com
      filter: (&(objectClass=user)(!(objectClass=computer)))
      attributes: [sAMAccountName, givenName, sn, title, mail, employeeID]
```

On the source side, DirSync needs the bind account to have the **Replicating Directory
Changes** right on the domain (Domain Admins have it), as for any DirSync client. A change
waits for the next poll, so the delay averages about half the poll interval.

## Debezium Server sidecar

For databases syncd has no driver of its own for (Oracle, Db2 and the others Debezium
captures), a Debezium Server sidecar captures the changes and posts them to a `debezium`
connector through its HTTP sink.

```yaml
- name: erp
  type: debezium
  connection:
    listen: 127.0.0.1:8085          # Debezium Server posts here
    token: { env: DEBEZIUM_TOKEN }  # required beyond loopback
  objects:
    - type: person
      table: HR.PEOPLE              # schema optional, case ignored
      anchor: ID
      mode: stream                  # Debezium objects always stream
      binary: [PHOTO]               # columns Debezium sends as base64
```

- **One listener serves every object** of the connector; each object names the captured
  table its events come from. Snapshot reads, inserts and updates upsert the row after the
  change; deletes delete by the row before it.
- **Delivery.** An event is answered only once the engine has committed it, and the sink
  moves its offsets only past answered events. Nothing is lost when either side stops,
  and a repeat is harmless. While an object's source restarts, its events get `503` and
  are retried. Events of tables no object reads are answered and ignored, with a warning.
- **Security.** The sink sends no credentials of its own, so the `token` goes in the URL
  path it posts to (`http://127.0.0.1:8085/<token>`). It is compared in constant time.
- **Values.** Debezium's JSON carries no schema: list binary columns under `binary`, and
  dates (which Debezium sends as epoch counts) need converting in rules where a target
  needs text.

The sidecar's configuration (a commented example for Oracle with Db2 settings is in
`deploy/debezium/application.properties` in the source repository) must:

- use the HTTP sink, with `debezium.sink.http.url` ending in the token;
- send plain JSON without schemas (`debezium.format.value=json`,
  `debezium.format.value.schemas.enable=false`, and the same for keys);
- set `debezium.source.tombstones.on.delete=false` and
  `debezium.source.decimal.handling.mode=string`;
- keep retrying while syncd is down (`debezium.sink.http.retries=2147483647`), because
  Debezium Server stops when it gives up;
- include each table an object reads in `debezium.source.table.include.list`.

Run Debezium Server with a restart policy. It keeps its own offsets; the checkpoint syncd
stores is the source position of the last event, for the status. Rows deleted while
Debezium re-snapshots from scratch (after lost offsets) are not swept: its snapshot
carries no deletes.

## JDBC bridge

For any other database with a JDBC driver (Db2, Informix, Oracle, ...), a `jdbc` connector
reads through the JDBC bridge, a small Java service (Java 17 or newer) that runs the SQL
syncd builds with the database's own driver and streams the rows back over gRPC.

```yaml
- name: hr
  type: jdbc
  connection:
    bridge: http://127.0.0.1:50071   # https://host:port when the bridge serves TLS
    token: { file: /etc/syncd/bridge-token }
    url: jdbc:db2://db2.example.com:50000/HR
    user: syncd
    password: { env: HR_DB_PASSWORD }
    properties:
      sslConnection: "true"
  objects:
    - type: person
      table: HR.EMPLOYEE
      anchor: EMPNO
      mode: delta                    # or scrape; jdbc objects do not stream
      watermark: LAST_CHANGED
      interval_secs: 300
      full_interval_secs: 86400
```

- **The bridge holds no configuration.** Each request names its database, so one bridge
  serves every `jdbc` connector. Put the drivers' jars in its `drivers/` directory (or
  point `SYNCD_JDBC_DRIVERS` at them) and run it next to syncd with a restart policy:

  ```sh
  bin/syncd-jdbc-bridge --listen 127.0.0.1:50071 --token-file /etc/syncd/bridge-token
  ```

  `--tls-cert` and `--tls-key` (PKCS#8 PEM) make it serve TLS. It listens on loopback
  unless it has a token.
- **Security.** Every request carries the database password, so syncd refuses a bridge
  beyond loopback without `https://` and a `token`.
- **SQL.** syncd keeps to what every database takes: a derived table without `AS`,
  `WHERE 1 = 0` to learn the columns, `MAX()` for the watermark, row limits through JDBC
  rather than `LIMIT`. Column names are the ones the database reports: Db2 and Oracle
  report unquoted names in upper case, so flows read `source.EMPNO`. Anchors and
  watermarks match regardless of case. A table name is passed as written.
- **Values.** Integers (and `DECIMAL`/`NUMERIC` with no scale up to 18 digits), booleans
  and bytes arrive typed; timestamps, dates, other decimals and the rest as text. A
  watermark is sent back to the database as its column's type.
- **Modes.** Scrape or delta only. To stream a database's changes, use a `debezium`
  connector.

!!! note "Packaging"
    The bridge is not in the release bundle or the packages yet: it is built from
    `bridge/jdbc` in the source repository (`gradle installDist`) and run on its own.

## CSV and flat files

Some systems export a CSV or TSV file to a directory, and that file is the only way to
read them. A `csv` connector reads such a file in full (keys in
[Configuration](configuration.md#csv-files-csv)):

```yaml
- name: contractors
  type: csv
  connection:
    path: /var/lib/syncd/in/contractors-*.csv   # the newest matching file
    delimiter: ","            # one character, "\t" for TSV, or auto
    encoding: utf-8           # utf-8, latin1 or utf-16
    header: true
    columns:                  # optional with a header: types for some columns
      - id
      - { name: active, type: bool }
      - { name: contract_end, type: date }
      - { name: groups, type: "multi:;" }
    watch: true               # read again when the file changes
    done_file: .done          # read only once contractors-<date>.csv.done exists
    on_error: fail            # a bad row fails the whole read
  objects:
    - type: contractor
      anchor: id              # the key column
      schedule: "*/15 * * * *"
  deletion_threshold: { percent: 20, count: 200 }
```

- **One file, read in full.** Each object is one file: its own `file`, or the
  connection's `path`. A glob reads the newest matching file, by modification time. A
  file has no change log, so every read is a full snapshot (`mode: scrape` only): the
  engine compares it with the last one and finds what was added, changed or removed. The
  connector's and the publications' deletion thresholds apply as to any full read.
  `interval_secs` or `schedule` say when it is read.
- **Parsing.** The delimiter is one character, or `auto`, which sniffs `,`, `;`, tab and
  `|` from the first lines. UTF-8 (a byte order mark is stripped), Latin-1
  (Windows-1252) and UTF-16 are read. With `header: true` the first row names the
  columns; without it, `columns` names them in order.
- **Typed columns.** Cells are text unless typed: `int`, `bool`, `date`, `timestamp`,
  or `multi:<separator>` for a cell holding several values (split and trimmed). Dates and
  timestamps are read as `timestamp()` reads text. An empty cell is no value.
- **Bad rows.** A row with the wrong number of columns, a value not of its type, or an
  empty key is a bad row. `on_error: fail` (the default) fails the whole read, so a
  half-good file never deletes half a directory. `on_error: skip` skips the row and
  counts it: the source's status shows a warning, `syncd status` prints it, and
  `syncd_source_rows_skipped_total` counts the rows. A clean read clears the warning.
- **Complete files only.** The file is parsed into memory and nothing is sent until the
  end. A file whose size changes between two looks a second apart is still being
  written: the read waits up to a minute, then gives up until next time. With
  `done_file` (`.done` for `<file>.done`, or an absolute path), the file is read only
  once the done file exists, and the done file is deleted after the read. `max_bytes`
  (1 GiB by default) refuses a larger file.
- **Watch.** `watch: true` reads the file again two seconds after it stops changing, or
  when its done file appears, on top of the schedule. Fixed in 0.5.0: a done file that
  appeared just after a read had found it missing could be missed by the watch, leaving
  the file for the next scheduled read; the watch now compares with the file as that read
  judged it.
- **Probes.** `syncd test-connection` names the file, its size and age, whether its done
  file is there, the encoding, the delimiter and the header's columns, reading only the
  first 64 KiB. `syncd discover` lists the files a glob matches, and for one file its
  columns with the kind of values sampled and a few records.

The engine's user must be able to read the inbox, and to delete done files. On Kubernetes
the inbox is mounted through `engine.extraVolumes`.

## REST and OData services

Since 0.5.0. A `rest` connector reads a JSON API page by page and sends each record to the
engine as it arrives, so memory holds one page at a time. It is the reader under the HR
connectors below, which fill in what their service fixes. Keys in
[Configuration](configuration.md#rest-and-hr-services-rest-workday-successfactors-ukg).

!!! warning "Tested against fakes only"
    The REST reader and the Workday, SuccessFactors and UKG Pro presets are built from the
    vendors' published API shapes and tested against fakes of them written in the syncd
    repository (`syncd-fakes rest`, `workday`, `successfactors`, `ukg`). **None has run
    against a real service or tenant.** Nightly tests against real tenants exist, and run
    only once their credentials are configured in the syncd repository; at release they
    were not. Treat these connectors as unverified until you have tried them against your
    own tenant.

```yaml
- name: hr
  type: rest
  connection:
    url: https://api.example.com/v1     # object paths are relative to it
    auth:
      token: { env: HR_TOKEN }           # or basic, oauth2; api_key may go with any
    rate_limit: 10                       # requests per second, over every object
    max_concurrency: 2
  objects:
    - type: person
      anchor: id
      mode: delta
      interval_secs: 60
      full_interval_secs: 86400
      rest:
        path: /people
        records: $.data
        paging: { style: next_link, next: $.links.next }
        fields:
          id: $.id
          givenName: $.name.first
          mail: $.emails[?(@.type=='work')].value
          hired: { path: $.hireDate, type: timestamp }
        delta:
          query: { updated_since: "{since}" }
          modified: $.updatedAt
        deletes:
          tombstone: { path: $.deleted }
```

- **Signing in.** A bearer `token`, `basic` credentials, or `oauth2`, with or without an
  `api_key` header beside it; or a client certificate alone (`tls.client_certificate_file`
  and `tls.client_key`). `oauth2` takes the grants `client_credentials` (with a client
  secret or `private_key_jwt`), `refresh_token`, `jwt_bearer` and `saml2_bearer`. The
  token is renewed a minute before it expires and once after a `401`.
- **Records and fields.** `records` is the JSON path to the array of records in a page;
  `fields` maps each attribute to a JSON path in the record, with a `type` (`string`,
  `int`, `bool`, `timestamp`, `list`). Timestamp fields arrive typed: rules compute with
  them, LDAP targets get GeneralizedTime, `effective()` reads them. Paths are a subset of
  JSONPath, enough to flatten a nested array "where a field is a value"
  (`$.emails[?(@.type=='work')].value`).
- **Paging** in seven styles: `none`, `next_link`, `link_header`, `offset`, `page`,
  `odata` (`@odata.nextLink` or `__next`) and `cursor`.
- **Changes** (`mode: delta`). The object is read in full first and every
  `full_interval_secs` (or on `schedule`); in between, a delta read adds `delta.query`
  with `{since}`: the **service's own time** at the start of the last read (from its
  `Date` header or a body field), less `overlap_secs` (60). The engine's clock is never
  used, so a service whose clock disagrees with the engine's loses nothing. Records read
  again in the overlap with an unchanged `delta.modified` stamp are not sent again.
  `delta.style: odata` follows the service's delta links instead. The position is a
  checkpoint, so a restart resumes without a full read.
- **Deletes.** A full read deletes what it no longer finds, under the source's deletion
  threshold. A delta read finds deletes sooner with `deletes.tombstone` (a field that
  marks a deleted record) or `deletes.endpoint` (a "deleted since" listing).
- **Throttling.** `429` and `503` wait for `Retry-After` (five minutes at most) or a
  growing pause, and are tried again up to `max_retries`, as are server errors and
  answers that do not come. Throttled requests are counted (`throttled=` in
  `syncd status`, the metric `syncd_source_throttled_total`). A read that still fails
  ends where it is: nothing is deleted, and the source is restarted as any source is.
- **Probes.** `syncd test-connection <connector>` signs in and reads the first page of the
  first object; `syncd discover <connector>` lists the objects and samples one. In the
  console, the connector form has the connection fields, the object's path (or report,
  entity, resource), records and read mode; fields, paging, delta and OAuth grants are
  edited in the YAML tab.

Not built: writing to a REST service (it is a source only), queries sent as a `POST`
body, and JSONPath beyond the subset (no recursive descent, slices or other
expressions). A report that comes as one answer, rather than in pages, is held in memory
while it is parsed.

### HR services: Workday, SuccessFactors, UKG Pro

The three presets are the REST reader with their service's paths, paging, delta filter
and token endpoint filled in; anything the configuration sets wins, so each assumption
below can be overridden.

| Type | Connection | Objects | Preset |
|---|---|---|---|
| `workday` | `url` (the tenant's services host), `tenant`; `auth.oauth2` with `grant: refresh_token` (an API client for integrations, `client_auth: basic`) or `jwt_bearer` (an x509 API client; audience `wd2`, subject the integration system user) | `rest.report: <owner>/<report>` (a RaaS custom report, the recommended path), or `rest.resource: workers` (the staffing REST API, full reads only) | Token endpoint `<url>/ccx/oauth2/<tenant>/token`; a report at `/ccx/service/customreport2/<tenant>/<owner>/<report>?format=json`, records `$.Report_Entry`, `mode: delta` by an `Updated_From` prompt; workers at `/ccx/api/staffing/v6/<tenant>/workers`, 100 a page |
| `successfactors` | `url` (the data center's API server), `company_id`; `auth.basic` (sent as `<user>@<company id>`) or `auth.oauth2` with `grant: saml2_bearer` (an assertion from the instance's `/oauth/idp`, or one made elsewhere) | `rest.entity`: one OData v2 entity per object (`PerPerson`, `PerPersonal`, `EmpEmployment`, `EmpJob`, `PerEmail`, ...), joined by the view | `/odata/v2/<entity>`, `$format=json`, records `$.d.results`, OData paging, `mode: delta` by `lastModifiedDateTime` |
| `ukg` | `url` (the tenant's service host); `auth.basic` (a web service account) **and** `auth.api_key` (the customer API key, sent as `US-Customer-Api-Key`) | `rest.resource`: `person-details`, `employment-details`, ..., joined by the view on `employeeId` | `/personnel/v1/<resource>`, `page` and `per_Page` paging (100 a page), `mode: delta` by `changedSince` |

```yaml
- name: workday
  type: workday
  connection:
    url: https://wd2-impl-services1.workday.com
    tenant: acme
    auth:
      oauth2:
        client_id: <API client id>
        grant: refresh_token
        client_auth: basic
        client_secret: { env: WORKDAY_CLIENT_SECRET }
        refresh_token: { env: WORKDAY_REFRESH_TOKEN }
  objects:
    - type: worker
      anchor: Employee_ID
      mode: delta
      interval_secs: 300
      full_interval_secs: 86400
      rest:
        report: ISU_syncd/Workers_for_syncd
        fields:
          Employee_ID: Employee_ID
          givenName: Legal_Name_First_Name
          hired: { path: Hire_Date, type: timestamp }
          leaves: { path: Termination_Date, type: timestamp }
        delta:
          modified: Last_Updated
```

- **Effective dates.** Hire and termination dates are fields of the report (or of
  SuccessFactors' `EmpEmployment`), typed as timestamps, and rules follow them with
  `effective()`, which the engine evaluates again at the instant it changes, whether a
  read happens then or not. `examples/workday-to-openldap.yaml` in the source
  repository has an account appear three days before the hire date and go through a
  staged leaver policy after the termination day, with golden tests at three instants.
  See [Effective dates](rules.md#effective-dates).
- **Not built:** Workday's SOAP `Get_Workers` (a RaaS report covers effective dating and
  incremental reads); SuccessFactors `$expand` (the view joins the entities instead),
  signing a SAML assertion locally and SAP's X.509 grant, and future-dated `EmpJob` rows
  (one record per anchor is kept, the last read); OAuth against UKG's own endpoints (it
  is a setting, never tried, even against a fake).
- **Assumed, and settings:** the report's field names and its `Updated_From` prompt are
  the report author's; SuccessFactors' paging by `__next` on every entity (some need
  `paging=snapshot` or `customPageSize` in `rest.query`); UKG's `changedSince`
  parameter, its `per_Page` spelling and the field names. The syncd repository's
  `docs/progress/phase6/hr-saas-sources.md` lists every assumption. SuccessFactors and
  UKG Pro have no tombstones here: deletes are found by full reads.

Setting up the tenant side (Workday's integration system user and API client,
SuccessFactors' OAuth client, UKG's web service account) is described in the syncd
repository's `docs/connectors/workday.md`, `successfactors.md` and `ukg.md`.

## Binary values and dates from sources

- **Binary data** passes through rules as bytes. Connectors recognise binary columns by
  type (`bytea`, `BLOB`, `varbinary`, BSON and JDBC binary). LDAP and Active Directory use
  a built-in list (`objectGUID`, `objectSid`, `sIDHistory`, `mS-DS-ConsistencyGuid`,
  `thumbnailPhoto`, `jpegPhoto`, `photo`, `userCertificate`, `userSMIMECertificate`, the
  CA and revocation list attributes, and others), any `;binary` attribute, and the
  object's `binary` list for schema extensions. Debezium columns need `binary`. Anchors
  stay text.
- **Dates** arrive as text by default, because typing them for everyone would change
  values flows copy to targets as they are. Rules read the text with `timestamp()`, and a
  comparison with a timestamp reads it automatically. An object's `timestamps` list types
  the listed attributes or columns when a record arrives, so rules get a timestamp and
  LDAP targets get GeneralizedTime. Turning it on for an object already synced makes each
  of its records read as changed once. A REST connector's fields typed `timestamp` arrive
  typed the same way.

See [Dates and times](rules.md#dates-and-times) and [Binary values](rules.md#binary-values).

## LDAP and Active Directory as targets

A publication writes a view's entities to an `ldap` or `active_directory` connector. The
target needs no `objects` unless it is also read. SCIM applications and Entra ID are
targets too; see [SCIM targets](#scim-targets) and
[Microsoft Entra ID](#microsoft-entra-id).

### How the writer works

- **Desired state, minimal operations.** For each entity the engine builds the target entry
  (DN, object classes, attributes) and compares it with what it last exported to that
  target. It sends only the difference: an add, a modify (replace for single-valued
  attributes, add and delete of values for those in `attributes.multi_valued`), a modify
  DN for a rename or move, or a delete.
- **Order.** Parents are written before children. With `create_containers`, missing
  parents are created on demand, as many levels as are missing. With
  `remove_empty_containers` as well, the containers syncd created are deleted once the
  batch has left them empty; see
  [DN template](configuration.md#dn-template).
- **Concurrency.** Up to `max_inflight` entities (32 by default) are written at once over
  a single multiplexed connection, under an adaptive limit.
- **Converging.** An entry that already exists is updated in place, a delete of a missing
  entry succeeds, and a value-level modify that no longer fits the live entry is retried
  as a replace.
- **Failures.** Exports that keep failing go to the dead-letter queue (`syncd dlq`, and
  the console), from where they are retried or discarded.
- **Reconciliation.** Every `engine.reconcile_interval_secs` the engine plans every entity
  of every publication against what it exported, and `syncd reconcile` does it on
  demand. With `drift` set, entries are also read back from the target; see
  [Drift detection](configuration.md#drift-detection-drift).
- **Entries nobody manages.** Since 0.5.0, after each scheduled drift check (and with
  `syncd unmanaged`), the engine lists the target's entries of the publication's object
  classes under its base with a read-only paged search, and reports those no publication
  exported, and members of privileged groups no rule accounts for (through nested groups
  in Active Directory). Nothing is written; `syncd adopt` takes an entry over. See
  [Unmanaged entries](configuration.md#unmanaged-entries-unmanaged).

The bind account needs the rights to add, modify, rename and delete entries under the
publication's DNs (and to create containers, with `create_containers`, and delete them,
with `remove_empty_containers`), and read access there for adoption and drift checks,
and to the entries' ids (below).

### Entries moved or renamed in the target

The engine finds an exported entry by the DN it last wrote it at. When someone else (an
administrator, another tool) moves or renames the entry in the target, that DN no longer
exists. So the writer also records the target's own id of each entry it exports:

| Directory | Id |
|---|---|
| Active Directory | `objectGUID` |
| 389 Directory Server, Red Hat Directory Server | `nsUniqueId` |
| OpenLDAP, Ping, UnboundID and any other | `entryUUID` (RFC 4530) |

The directory is the one the target's `profile` names, or the one detected when it
connects. The id is read after an entry is added, or after the first write of an entry
exported before 0.3.0. It is kept in the exported state, never written to the target or
shown as an exported attribute. Reading it is best effort: a target that does not give it,
or does not let the account read it, works as without it.

An entry not at its DN is then looked for by its id: on Active Directory with a base search
for `<GUID=...>`, elsewhere with a subtree search for `(entryUUID=...)` or
`(nsUniqueId=...)` in the entry's naming context.

| Operation | With the entry found elsewhere |
|---|---|
| Modify, enable, disable | The entry is moved back to the DN the publication places it at (containers created with `create_containers`) and changed there. |
| Rename | The entry is renamed from where it was found. |
| Delete | The entry is deleted where it was found. |
| Add, with a known id (drift reverting a missing entry) | The entry is moved back and converged, not added a second time. |

An entry found nowhere is added again. Each case is logged as a warning naming both DNs.
Two entries with the same id fail the export with both DNs, into the dead-letter queue.
Adoption compares DNs as DNs, so a DN spelt differently (spaces after commas, escapes) is
not taken for one elsewhere.

- An entry exported before 0.3.0 and not written since has no id yet, so a move made
  before its next write is handled as before: a second entry is added, or the export is
  dead-lettered.
- A drift check still reports an entry moved by hand as missing. With `revert`, the revert
  puts it back as above.

### Renames into a DN that is taken

- **Swaps and chains.** When an entry's new DN is held by another entry that the same
  batch renames away (two people swapping names, or A renamed into B's DN while B moves
  on), the writer parks the entry under an RDN of its own, its RDN with `~` and the
  entity's number appended (`CN=Jon Smith~1a`), in its own container. Once the batch's
  other entries are written, it renames the parked ones to their DNs, in rounds while any
  succeeds, so chains complete one link a round. One that still cannot move (the other
  entry's export failed) is renamed back and fails as before. Parking is logged:
  `rename blocked by an entry this batch renames away; parked until it has moved`.
- **Moves aside.** `move_to` and `disable_and_move_to` keep the entry's RDN. When a leaver's
  RDN is already taken in that container (a second `CN=Bob Ray`, a reused `uid`), the
  entry gets an RDN of its own (`uid=jdoe~1a,ou=Disabled`), logged as a warning. The engine
  records it there, so it is not moved again while out of scope; coming back into scope
  renames it to its own RDN again.
- **Anything else** renamed into a taken DN still fails: an entry the batch does not move
  away is a real conflict, such as two entities computing one DN, or an entry nobody
  manages.
- **A replayed rename** counts as already done only when the entry at the new DN has the
  entry's id.

The halves of a swap that reach the writer in different batches still fail the first
time; `syncd retry` puts the dead letters in one batch, which resolves them. A crash while
an entry is parked leaves it at its parked DN, where the replay finds it by its id; without
an id, the export fails with "no such object". While parked, the entry's RDN attribute
holds the parked value.

### Changes of case

A change of case in the attribute an RDN is built from (`jdoe` to `JDoe`) is a rename: the
entry's DN and its RDN attribute both take the new case, and groups naming the entry
follow. OpenLDAP, 389 Directory Server and Active Directory all take it, from a source
modify DN or from a database change. A change of case in a parent only (`ou=Research` to
`ou=research`) renames nothing: parents compare as DNs, case-insensitively, so the entry
stays under the container as it is spelt in the target.

### Target profiles

LDAP leaves some things to each server, such as how an account is disabled. An `ldap`
connector's `connection.profile` names the server; `active_directory` connectors have
their own type.

| Profile | Server | `deprovision: disable` sets |
|---|---|---|
| `generic` (default) | Standard LDAP only | Not available |
| `openldap` | OpenLDAP with the ppolicy overlay | A permanent `pwdAccountLockedTime` (`000001010000Z`) |
| `389ds` (alias `rhds`) | 389 Directory Server, Red Hat Directory Server | `nsAccountLock: TRUE` |
| `unboundid` (aliases `ping`, `pingdirectory`) | Ping Identity Directory Server, UnboundID | `ds-pwp-account-disabled: true` |
| *(type `active_directory`)* | Active Directory | The ACCOUNTDISABLE bit of `userAccountControl`, read first so the other flags stay |
| *(type `scim`)* | A SCIM application | `active: false` |
| *(type `entra`)* | Entra ID | `accountEnabled: false` |

With `deprovision: disable` (or `{ disable_and_move_to: <container> }`), an entity that
leaves scope or is deleted has its account disabled and the entry kept and managed. Changes
while it is out of scope are not written. An entity that comes back is moved back,
updated and enabled again. A marker in the exported state, never written to the target,
records that the entry is disabled.

```yaml
- name: openldap
  type: ldap
  connection:
    url: ldaps://ldap.example.com
    bind_dn: cn=syncd,ou=Services,dc=example,dc=com
    password: { env: TARGET_LDAP_PASSWORD }
    profile: openldap
```

A staged leaver policy (`disable_after`, `strip_groups_after`, `move_to`,
`delete_after`) disables, strips the groups syncd manages, moves and deletes at set times
after the entity left; see [Change safety and lifecycle](change-safety.md#staged-leaver-policy).
Since 0.5.0, `strip_unmanaged_groups` takes the leaver out of groups syncd does not
manage too, except `keep_groups` (LDAP and Active Directory targets; tested on OpenLDAP
only), and an entity added at the DN of a leaver's disabled entry takes the entry over
and enables it (a leaver moved aside is not taken over).

### Active Directory targets

- `create_containers` can create `cn=` parents as `container`, as well as `ou=`, `o=`,
  `l=`, `dc=` and `c=` parents.
- Groups are ordinary publications of a group view with `member` as a reference, or
  computed groups with `object_classes: [group]` and `when_empty: keep`.
- Accounts can be created with a password (below), and captured password changes are
  delivered as `unicodePwd`; see
  [How a change travels](password-sync.md#how-a-change-travels). Both need
  `ldaps://` or `starttls`; a domain whose certificate comes from its own CA needs that
  CA in the connection's `ca_file` (since 0.5.0) or the system trust store.
- Disabling and enabling read the `userAccountControl` the directory holds and set or
  clear ACCOUNTDISABLE on it, so the other flags stay, whether mapped or set by hand.
- `writeback` is refused for Active Directory targets, which have no change log syncd can
  read.

```yaml
- name: people_to_ad
  view: people
  target: ad
  filter: e.status != "terminated"
  dn: CN={cn},OU={department},OU=Synced,DC=corp,DC=example,DC=com
  create_containers: true
  object_classes: [user]
  attributes:
    cn: e.displayName
    department: e.department
    sAMAccountName: e.login
    userPrincipalName: e.login + "@corp.example.com"
    givenName: e.givenName
    sn: e.sn
    userAccountControl: e.accountControl
  references:
    manager: manager
  deprovision: { disable_and_move_to: "OU=Disabled,OU=Synced,DC=corp,DC=example,DC=com" }
```

### Active Directory conveniences

Publishing to Active Directory means writing the same attributes the same way every time.
A publication to an `active_directory` target can say what it means instead, and the
engine writes the attributes (keys in
[Configuration](configuration.md#active-directory-conveniences)):

```yaml
- name: people_to_ad
  view: people
  target: ad
  dn: CN={displayName},OU=Staff,DC=corp,DC=example,DC=com
  object_classes: [user]
  attributes:
    cn: e.displayName
    givenName: e.givenName
    sn: e.sn
    displayName: e.displayName
  sam_account_name: { from: e.login, max_length: 20 }
  user_principal_name: { from: e.login, suffix: corp.example.com }
  exchange:
    proxy_addresses: { primary: e.mail, aliases: e.aliases, keep_existing: true }
    mail_nickname: e.login
    target_address: '"SMTP:" + e.login + "@example.mail.onmicrosoft.com"'
  account_control:
    flags: [NORMAL_ACCOUNT]
    password_never_expires: e.status == "leave"
    must_change_password: "false"
  home:
    directory: '"\\\\files.corp.example.com\\home\\" + e.login'
    drive: "H:"
  drift:
    attributes: { proxyAddresses: revert, pwdLastSet: accept }
```

| Block | Writes |
|---|---|
| `exchange.proxy_addresses` | `proxyAddresses`: one upper-case `SMTP:` primary, then the lower-case `smtp:` aliases sorted, duplicates and the primary itself left out. With `keep_existing` (the default) the attribute is diffed as a set, so `x500:`, `X400:`, `SIP:` and addresses other tools put there stay, and only syncd's own are added and removed. `false` replaces the whole attribute. |
| `exchange.mail_nickname`, `target_address` | `mailNickname`, `targetAddress`, as given. |
| `account_control` | `userAccountControl` from flag names (or an int expression), `NORMAL_ACCOUNT` when unset; `password_never_expires` sets or clears `DONT_EXPIRE_PASSWORD`; `must_change_password` writes `pwdLastSet` `0` (change at next logon) or `-1`. Once the person changes their password the directory keeps its own clock in `pwdLastSet`, so a drift policy of `accept` suits it. |
| `home` | `homeDirectory`, `homeDrive` (a letter and a colon), `profilePath`, `scriptPath`: plain attributes. No share is created. |
| `user_principal_name` | `userPrincipalName` as `<from>@<suffix>`. |
| `sam_account_name` | `sAMAccountName`, cut to `max_length` characters (20, the directory's limit, at most). With `on_conflict: unique` (since 0.5.0), a second holder of a name gets a counter. |

Each block is sugar for the attributes it writes. The planner, drift checks, adoption,
the object explorer and previews see plain attributes, so a drift policy or an `adopt`
match may name them. Drift on `proxyAddresses` compares the address without case and the
prefix with it, so `SMTP:` (the primary) and `smtp:` (an alias) differ. The same
functions are available in rules: `uac()`, `uac_has()`, `uac_set()`, `uac_flags()`,
`proxyAddresses()` and `truncate()`; see [Active Directory values](rules.md#active-directory-values).

The blocks are refused on any other target, and so is mapping an attribute both in
`attributes` and through a block.

!!! note "`sAMAccountName` must be unique"
    The directory enforces it, and by default a duplicate fails the export into the
    dead-letter queue. Since 0.5.0, `on_conflict: unique` lets the publication resolve
    it: the second holder of a name gets a counter from 2 in place of the name's last
    characters (`jsmith`, `jsmith2`), the name is kept in the engine's store, and a
    leaver's name stays taken while the account is in the directory. Turning it on renames
    nobody who has a name already. Uniqueness is among the names syncd claims: accounts it
    does not manage are not searched.

    ```yaml
    sam_account_name: { from: e.login, max_length: 20, on_conflict: unique }
    ```

    Or mint the login once as a computed attribute with `unique()` and
    `mode: assign_once`, then write it with `sam_account_name`:

    ```yaml
    - name: login
      mode: assign_once
      output: >-
        unique("sAMAccountName", [
          truncate(lower(ascii(e.givenName.substring(0, 1) + e.sn)), 20),
          truncate(lower(ascii(e.givenName.substring(0, 1) + e.sn)), 18) + "{n}"
        ])
    ```

### Initial passwords

A publication can give each entry it creates a password, read from the entity or
generated (`passwords.initial`; keys in
[Configuration](configuration.md#publication-passwords), the whole story in
[Password synchronisation](password-sync.md#initial-passwords)). It is set right after a
**fresh** add, and only then: an entry that was already there (adopted, replayed after a
crash, put back after a move) keeps the password it has.

On Active Directory, syncd sets `unicodePwd` as it creates the account:

1. The entry is added with ACCOUNTDISABLE set in its `userAccountControl` (the exported
   value with bit 2, or `514` when none is exported), because Active Directory refuses an
   enabled account without a password.
2. `unicodePwd` is set.
3. `pwdLastSet` is set to `0` when `must_change` is on.
4. `userAccountControl` is set to the exported value, which enables the account when it
   says so. An entry exported disabled stays disabled.

Other directories get the password with RFC 3062 Password Modify, or a `userPassword`
replace with `write: replace`, after the add.

- **TLS is required.** Active Directory takes passwords only over LDAPS or StartTLS. A
  `passwords` block on an Active Directory target whose connection is `ldap://` without
  `starttls` is refused when the configuration is validated, and the writer refuses again
  before anything is sent. Since 0.5.0 other directories on another host are held to the
  same rule unless `passwords.allow_cleartext` is set (see
  [above](#ldap-directories)).
- **A refused password** (the directory's password policy) does not fail the add. The
  entry stays without the password, disabled on Active Directory. It is counted
  (`initial passwords: N set, N refused` in `syncd status`) and logged by DN and
  publication, never by value. Since 0.5.0 it is **tried again**, up to three attempts in
  all (the next one at the entity's next change or reconciliation, or at the latest a
  minute times the refusals so far), and then the entry is
  **held in the dead-letter queue**, where it shows in `syncd dlq`, the console,
  `syncd_publication_dead_letters` and the `SyncdDeadLetters` alert, and `syncd retry`
  tries once more; 0.4.1 only logged it. Entries refused before the upgrade are not
  retried. The number of attempts and the wait are fixed.
- **A change captured later** replaces the initial password.
- An entry a drift check puts back gets an initial password since 0.5.0 (it was re-added
  without one before).
- Telling the person their password is out of scope: the log and the status say that one
  was set, never what it is.

### Write-back after a target outage

When the engine cannot reach an LDAP target, applications may still write to it directly.
A publication's `writeback` writes those changes back to its LDAP source once the target
answers again (keys in [Configuration](configuration.md#write-back-writeback)):

1. The outage begins when an export or a drift read fails for lack of a connection. The
   engine records the time of its last successful operation on the target.
2. Exports to the target are held from then on, in the dead-letter queue, so they do not
   hide the conflicts the write-back looks for.
3. The engine tries a new connection every few seconds, no more often than its reconnect
   wait, which doubles up to a minute.
4. When the target answers, the engine reads the target's change log for the outage: the
   OpenLDAP access log by `reqStart`, or `cn=changelog` by change number.
5. A modify of a listed attribute, on an entry the publication manages, is written to the
   source entry that target entry came from. If the engine has a change of its own pending
   for that attribute, the source's value is kept and reported as a conflict. Adds,
   deletes, renames and unmanaged entries are reported, not replayed; the engine's own
   changes (made as the target's bind DN) are ignored.
6. The hold is lifted and the held exports are retried at once.

Each entity's history records what happened as `written_back`. Keep the engine host and the
directory on NTP: the window is bounded by the engine's clock and filtered by the
directory's timestamps. An outage the engine never noticed (nothing was exported or read
while it lasted) is not replayed; drift detection covers those changes.

The target's bind account needs read access to its change log, and the source connector's
account write access to the mapped attributes.

## SCIM targets

SaaS applications take their users and groups through SCIM 2.0 (RFC 7643, RFC 7644). A
`scim` connector is a publication target like a directory, with the same planner, leaver
stages, drift checks and password delivery, against `/Users` and `/Groups` (keys in
[Configuration](configuration.md#scim-scim)):

```yaml
connectors:
  - name: saas-app
    type: scim
    connection:
      base_url: https://app.example.com/scim/v2
      auth: { token: { file: /run/secrets/scim-token } }
      # or basic: { username: syncd, password: { env: SCIM_PASSWORD } }
      # or oauth2: { token_url: https://login.example.com/oauth2/token, client_id: syncd,
      #              client_secret: { file: /run/secrets/scim-client-secret }, scope: scim }
      # (or, in place of client_secret, a signed assertion:
      #  private_key_jwt: { private_key: { file: /etc/syncd-keys/scim-client.key }, key_id: key-1 })
      rate_limit: 20
publications:
  - name: people_to_saas
    view: people
    target: saas-app
    resource: User
    external_id: e.employeeId
    attributes:
      userName: e.login
      name.givenName: e.givenName
      'emails[type eq "work"].value': e.mail
      active: e.status == "active"
      urn:ietf:params:scim:schemas:extension:enterprise:2.0:User:department: e.department
    deprovision: disable
    groups:
      - { name: engineering, filter: e.department == "Engineering", dn: displayName=Engineering, member_attribute: members }
```

- **Resources, not DNs.** `external_id` gives the resource's `externalId`, by which syncd
  finds it again (`GET /Users?filter=externalId eq "..."`); its id is then remembered.
  There is no `dn`, `object_classes`, container, move, `writeback` or `adopt`. A computed
  group is named `displayName=<name>`, with `members` as its member attribute. A changed
  `external_id` renames the resource.
- **Attribute names are SCIM paths:** `userName`, `name.givenName`, a filtered element of
  a multi-valued attribute (`emails[type eq "work"].value`: created when missing, replaced
  in place, dropped when its value goes), or an extension attribute by its URN. Names
  ignore case. The provider's `/Schemas`, read at connect, type the values: `active`
  becomes a JSON boolean, an integer an integer, a multi-valued attribute an array.
- **Writes.** An add is a `POST`; a change is a `PATCH` when the provider supports it,
  else a read, a merge and a `PUT` with `If-Match` when it supports ETags; a delete is a
  `DELETE`. Disabling is `active: false`. An initial password goes into the `POST`.
- **Resources that already exist.** A `409` on create looks the resource up by
  `externalId`, then `userName`, adopts it and brings it up to date.
- **Throttling.** Requests are paced to `rate_limit` per second. A `429` waits for
  `Retry-After` (a minute at most, three times). A `412` reads again and retries once. A
  lost connection marks the target down until a probe of `/ServiceProviderConfig` finds
  it again.
- **Authentication** is a bearer token, basic credentials, or an OAuth 2.0
  client-credentials token fetched from `token_url` and renewed before it expires.
  Since 0.5.0 the client can prove itself with `private_key_jwt` (RFC 7523: an assertion
  signed with an RSA or P-256 key, `PS256`, `RS256` or `ES256`) in place of a client
  secret. The secret or the key is read where its reference says with every token
  request, so a rotated one is used from the next token; a bearer token or a basic
  password is not read again after a `401`. `base_url` and `token_url` must be
  `https://` unless on loopback.
- **Passwords.** A captured password replaces `password` (see
  [What is built](password-sync.md#what-is-built)). A provider that does not
  announce `changePassword` support is refused at connect when a publication delivers
  passwords to it.
- **Users nobody manages.** Since 0.5.0 the unmanaged-entries pass lists the provider's
  users page by page (`startIndex` and `count`, paced and retried like every request),
  each named by its `userName`, and reports those syncd did not export. There are no
  privileged groups for SCIM.

`syncd test-connection` reports what the provider supports (patch, change password,
ETags, filter) and its resource types. `syncd discover` lists the resource types, and for
one, the attribute paths its schemas declare, with a few resources as records.

## Microsoft Entra ID

An `entra` connector reaches a tenant through Microsoft Graph. It is a target for users,
groups, licences and app roles, and a source of users and groups through delta queries
(keys in [Configuration](configuration.md#entra-id-entra)):

```yaml
connectors:
  - name: entra
    type: entra
    connection:
      tenant: contoso.onmicrosoft.com
      auth: { client_id: 00000000-0000-0000-0000-000000000000, client_secret: { file: /run/secrets/entra-client-secret } }
      cloud: global               # usgov, usgovdod, china
    objects:                      # as a source
      - type: user
        anchor: id
        mode: stream
        poll_interval_secs: 60
        attributes: [id, userPrincipalName, displayName, mail, accountEnabled, onPremisesSyncEnabled, department]
publications:
  - name: people_to_entra
    view: people
    target: entra
    external_id: e.upn            # names the user by userPrincipalName (key: employeeId for the other way)
    attributes:
      displayName: e.displayName
      givenName: e.givenName
      surname: e.sn
      usageLocation: e.country    # needed for a licence to be assignable
      accountEnabled: e.status == "active"
    licenses:
      assign: e.skus              # SKU part numbers, such as ["ENTERPRISEPACK"]
      disabled_plans: [YAMMER_ENTERPRISE]
    passwords:
      initial: { generate: { length: 16 } }
    deprovision: disable
    groups:
      - { name: engineering, filter: e.department == "Engineering", dn: displayName=Engineering, member_attribute: members }
```

**Authentication** is OAuth 2.0 against the cloud's sign-in host; the token is renewed a
minute before it expires. `auth` takes exactly one way of signing in (keys in
[Configuration](configuration.md#entra-id-entra)):

| Method | What the engine sends | Notes |
|---|---|---|
| `client_secret` | The secret, with each token request | Read where its reference says every time, so a rotated secret is used with the next token. |
| `certificate_file` with `private_key_file` or `private_key` (since 0.5.0) | A client assertion, a JWT valid ten minutes, signed with the certificate's key | RSA keys; `PS256` with the SHA-256 thumbprint by default, `assertion_algorithm: RS256` with the SHA-1 thumbprint. Upload the certificate (not the key) to the app registration. The files are read when the connector starts. No EC keys, `x5c` or PFX. |
| `federated_token_file`, or `workload_identity: true` (since 0.5.0) | The token in the file, as the client assertion, accepted through a federated credential of the application | The file is read for every token request. `workload_identity` takes it from `AZURE_FEDERATED_TOKEN_FILE`, and the client id from `AZURE_CLIENT_ID` when `client_id` is unset: what AKS workload identity sets. |
| `managed_identity` (since 0.5.0) | Nothing: the token comes from the Azure VM's instance metadata service | `{ client_id: ... }` picks a user-assigned identity. Grant the identity's service principal the Graph application permissions (with PowerShell or the Graph API, not the portal's app registration pages). |

```yaml
      auth: { client_id: <application id>, certificate_file: /etc/syncd-keys/entra-app.crt, private_key_file: /etc/syncd-keys/entra-app.key }
      # auth: { workload_identity: true }      # AKS workload identity
      # auth: { managed_identity: {} }         # the Azure VM's managed identity
```

**Only the client secret has run against a real tenant.** The certificate, the federated
token and the managed identity are tested against syncd's fake Microsoft Graph, whose
token endpoint checks an assertion as Entra ID documents it. A nightly test signs in to a
real tenant with a certificate once the syncd repository has the credentials for it,
which it did not at release; a federated token and a managed identity cannot be tested
from its CI at all. The console has no form fields for the new methods: write them in the
YAML tab.

The application needs Graph application permissions for what the publications do:
`User.ReadWrite.All`, `Group.ReadWrite.All`, `Directory.ReadWrite.All` for licences,
`AppRoleAssignment.ReadWrite.All` for app roles.

**National clouds.** `cloud` picks the Graph and sign-in hosts:

| `cloud` | Graph | Sign-in |
|---|---|---|
| `global` (default) | `graph.microsoft.com` | `login.microsoftonline.com` |
| `usgov` | `graph.microsoft.us` | `login.microsoftonline.us` |
| `usgovdod` | `dod-graph.microsoft.us` | `login.microsoftonline.us` |
| `china` | `microsoftgraph.chinacloudapi.cn` | `login.chinacloudapi.cn` |

### As a target

- **Users and groups** are named as for SCIM: `external_id` gives the user's
  `userPrincipalName` (or `employeeId` with `key: employeeId`), and a group is
  `displayName=<name>`. Attribute names are Graph property names, typed where Graph is
  strict (`accountEnabled` a boolean, `businessPhones` and `otherMails` arrays).
- **Creating a user** needs `userPrincipalName`, `displayName`, a `mailNickname` (taken
  from the UPN when not mapped) and a password: the publication's `passwords.initial`, or
  a random one nobody keeps, which the person must change. A user or group that already
  exists is adopted by its name and brought up to date.
- **Licences.** `licenses` writes `assignedLicenses` from SKU part numbers
  (`ENTERPRISEPACK`), with `disabled_plans` turned off in each. They are matched with the
  tenant's `/subscribedSkus` and sent as one `assignLicense` with what to add and remove.
  A user needs `usageLocation` for a licence to be assignable.
- **App roles.** `appRoleAssignments` takes `<servicePrincipalId>:<appRoleId>` values,
  compared with the user's assignments and added or removed.
- **Members** are added and removed in `$batch` calls of twenty; `manager` takes a user's
  resource name.
- **Leavers.** Disabling is `accountEnabled: false`; a delete is a `DELETE`. Resources
  have no containers, so the leaver policy cannot move them.
- **Users synced from on-premises.** Before every write to a user's properties (a change,
  `accountEnabled`, a password, a delete), syncd reads `onPremisesSyncEnabled`. A user
  that Entra Connect (or Cloud Sync) syncs from Active Directory is left alone: the write
  is skipped with a warning and counted, and the export still succeeds. Its group
  memberships, licences and app roles, which live in the cloud, are written. A password
  for such a user is refused. The count shows in `syncd status`, on the console's engine
  page and in the metric `syncd_entra_skipped_synced_users{target}`.
- **Throttling.** A `429` waits for `Retry-After` (a minute at most, three times). A lost
  connection marks the target down until a probe finds it again.
- **Users nobody manages.** Since 0.5.0 the unmanaged-entries pass lists the tenant's
  users and reports those syncd did not export (tested against the fake Graph only).
  There are no privileged groups for Entra ID.

### As a source

An object `{ type: user | group, anchor: id, mode: stream, attributes: [...] }` is read
with `/users/delta` (or `/groups/delta`), selecting the listed properties. The first pass
reads everything, as a full read; the delta link is then the checkpoint, polled every
`poll_interval_secs` (30 by default). Changed users and groups come back whole, removed
ones as deletes. Arrays become several values, objects are flattened to `name.field`, and
a group's members are read whole as `members`. `onPremisesSyncEnabled` is a property like
any other, so a view can tell synced users from cloud-only ones.

`syncd test-connection` gets a token, lists the tenant's licence SKUs and repeats the
guard on synced users. `syncd discover` lists `users` and `groups`, and for one, a few
records as the engine reads them.

## Checking a connector

Before publishing, check a connector against the real system. Nothing here writes.

```sh
sudo syncd test-connection hr_db         # connect (and bind), report what answered
sudo syncd discover hr_db                # list tables, collections or directory entries
sudo syncd discover hr_db public.people  # a container's fields and a few records
```

`test-connection` reports a directory's change log and change numbers when its root DSE
publishes them, for a Debezium connector checks that the address can be listened on, and
for a REST connector signs in and reads the first page of the first object.
The console's configuration editor runs the same checks, with a schema browser and sample
records read as the engine would read them, for designers; each check is audited and
sensitive values stay hidden unless revealed.

## Tested servers

The continuous integration suite runs each connector end to end against real servers, or
the stand-ins named below, on Ubuntu 24.04 runners (`tests/e2e` and
`.github/workflows/ci.yml` in the source repository; `docs/TESTED.md` there says which
rows are the real product and which are stand-ins). 0.5.0's CI had not run in GitHub
Actions before the release's own pull request:

| Connector | Tested against | What is covered |
|---|---|---|
| PostgreSQL | PostgreSQL 16 | Logical replication of tables and a query with watched tables, a publication completed with a watched table it lacked, delta reads; since 0.5.0 a `TRUNCATE` whose transaction puts rows back, an emptying `TRUNCATE` held by the deletion threshold and approved, a resumed idle stream showing `streaming` |
| MySQL / MariaDB | The MySQL 8 and MariaDB packages of Ubuntu 24.04 | Binary log streaming and delta reads |
| SQL Server | SQL Server 2019 and 2022 (Microsoft's container images) | Change data capture, change tracking, delta reads, lost history |
| MongoDB | MongoDB 8.0 | Change streams and delta reads |
| LDAP source | OpenLDAP 2.6 | Full reads, delta reads, syncrepl, failover to a second URL, the access log, a container renamed under the base, case-only renames |
| LDAP source | 389 Directory Server 2.4 | Retro Changelog (`cn=changelog`), anchored on `nsUniqueId` |
| Active Directory source | Samba AD DC (4.19 in the benchmarks) | DirSync streaming |
| LDAP target | OpenLDAP 2.6, with and without ppolicy | Adds, renames, moves, case-only renames, containers created and removed when empty, groups, references, adoption, drift, write-back, entries moved by hand put back (`entryUUID`), permanent values, `openldap` profile; since 0.5.0 unmanaged entries and privileged members (`tests/e2e/unmanaged.sh`), a leaver taken out of groups made by hand except `keep_groups`, a rehired person taking over their locked entry |
| LDAP target | 389 Directory Server 2.4 | `389ds` profile: disable and re-enable, case-only renames, entries moved by hand put back (`nsUniqueId`), a move aside into a taken RDN |
| Active Directory target | Samba AD DC | Users and groups, containers, disable and move, group membership following moves, entries moved by hand put back (`objectGUID`), two users swapping names, a move aside into a taken RDN; since 0.5.0 unmanaged entries with a nested privileged group, `sam_account_name.on_conflict: unique` |
| Initial passwords on Active Directory | Samba AD DC over LDAPS and StartTLS, its certificate trusted through `ca_file` (`tests/e2e/ad-passwords.sh`, since 0.5.0) | Users created enabled with the password HR chose (they bind with it) or a generated one they must change; a connector with another CA, or none, refused the connection. The syncd repository's `docs/TESTED.md` still says the Samba row does not cover `unicodePwd`; its CI workflow runs this scenario |
| Debezium | Debezium Server 3.0, with PostgreSQL standing in for Oracle and Db2 | Snapshot, changes, tokens, either side stopping |
| JDBC bridge | PostgreSQL and Apache Derby through their JDBC drivers | Scrape and delta reads, tokens, TLS, the bridge stopping |
| CSV | Files written by the test | A glob, a done file, `watch`, a byte order mark, a sniffed delimiter, quoted and multi-valued cells; a replaced file applying its changes and deletes; skipped rows in the metrics and the status; a partial file not read until its done file appears; the probes |
| SCIM target | Two instances of syncd's fake SCIM provider (`syncd-fakes scim`): one with every feature, one without `PATCH` and with strict ETags | Users with e-mail addresses and booleans, a group with members, a changed row patched, a leaver disabled on one and deleted on the other, a resource that already existed adopted, drift reverted, the probes |
| Entra ID | syncd's fake Microsoft Graph (`syncd-fakes graph`), seeded with SKUs, a synced and a cloud-only user | Users with licences and a group; the synced user's properties skipped and counted (no dead letter) while its group and licence are written; the tenant read back as a source into OpenLDAP, and a change in the tenant following through the delta query; since 0.5.0 signing in with a certificate, and an assertion signed with another key refused |
| SCIM `private_key_jwt` | The fake Graph's token endpoint, standing in for the provider's authorization server | An assertion signed with the registered key buys a token; one signed with another key, or about another client, buys nothing |
| REST source | syncd's fake HR service (`syncd-fakes rest`), whose clock runs an hour behind (`tests/e2e/rest-source.sh`) | A full read in pages, delta reads by the service's own time, a delete by tombstone, `429` and `503` with `Retry-After`, a full read that breaks off and deletes nothing, a restart resuming from the checkpoint |
| Workday, SuccessFactors, UKG Pro | Fakes built from the vendors' published API shapes (`tests/e2e/hr-saas.sh`) | Workday's RaaS report with a refresh token and effective dates; SuccessFactors' OData entities joined into one person; UKG's personnel resources joined the same way |
| Secrets from a vault | A double of HashiCorp Vault (`syncd-fakes vault`), with AppRole (`tests/e2e/secrets-vault.sh`) | Two OpenLDAP connections bound with passwords read from it, both passwords rotated, the connections cut and remade with the new ones without a restart, only references in the configuration, status and logs |
| Active Directory conveniences | `syncd preview` against the running engine | The attributes computed from CSV records; `syncd validate` refusing the blocks on an LDAP target |
| Initial passwords | OpenLDAP with PostgreSQL streaming (`tests/e2e/passwords.sh`) | A person provisioned with an initial password binds with it; a change captured later replaces it; `generate` creates every entry with a password; the counts in `syncd status`; no password in the engine's log; since 0.5.0 a directory on another host over `ldap://` refused unless `allow_cleartext` |

What a connector does when its server fails is also tested without real servers, against
servers the tests run themselves (in the `test` job): one that accepts connections and
never answers, a port nothing listens on, and a scripted LDAP server that answers,
returns a result code, goes silent or drops the connection per request. They cover:

- **LDAP:** a refused connection or rejected bind failing over to the next URL; a silent
  server timing out a connection, a search or a full read; a write that times out or
  loses its connection marking the target down, so the rest of the batch fails at once;
  a result code failing only its own export; reconnecting at most once per wait; a held
  target writing nothing; a probe clearing a target marked down.
- **PostgreSQL, MySQL, SQL Server:** a silent server failing the connect at the 10-second
  limit, naming the server; a refused one failing at once.
- **MongoDB:** a silent server failing the first operation at the limit.

Since 0.5.0 a nightly workflow (`nightly-real.yml`) has jobs against real systems:
Microsoft Active Directory on Windows Server, a test Entra ID tenant, hosted SCIM
applications, PingDirectory, and Workday, SuccessFactors and UKG Pro tenants. Each runs
only once its credentials (or, for Active Directory, a self-hosted runner) are configured
in the syncd repository, and **at release none of them was**: those jobs have never run.
The one real-system job that runs every night needs nothing: the open-source SCIM server
`scim2-server`.

Not tested against a real server:

- **Ping Identity / UnboundID** (`unboundid` profile): only the mapping is unit-tested
  (a PingDirectory job is in the nightly workflow, not yet run).
- **Microsoft Active Directory** itself: Active Directory is tested through Samba AD DC.
- **Microsoft Entra ID** and **real SCIM applications**: the end-to-end tests use syncd's
  fake Graph and fake SCIM provider, which implement the subset syncd uses. Entra ID's
  certificate, federated-token and managed-identity sign-in are tested against the fake
  only.
- **REST services, Workday, SAP SuccessFactors and UKG Pro**: fakes only.
- **Vault, AWS Secrets Manager and Azure Key Vault**: doubles only.
- **Oracle and Db2**: their container images do not fit the CI runners, so PostgreSQL
  (Debezium) and Apache Derby (JDBC) stand in. Debezium's change events have the same
  shape for every database it captures.
- **Red Hat Directory Server, ForgeRock / OpenDJ, Oracle DSEE and Oracle Unified
  Directory**: supported through the same standards (`389ds` profile, `cn=changelog`) but
  not in the suite.
- **Unmanaged entries on 389 Directory Server and Ping**: unit-tested only.

## Not supported

- **Other source families**: Kafka and inbound SCIM are not planned until a customer
  needs them. HR and SaaS services are read through the REST reader since 0.5.0; Workday
  SOAP and SuccessFactors `$expand` are not built.
- **Other targets**: databases, files, REST services, and applications that do not speak
  SCIM 2.0.
- **eDirectory's change cache** as a stream.
- **Entra ID with EC keys, an `x5c` header or a PFX file**: a certificate's key must be
  RSA, in PEM.
- **Password capture**: since 0.5.0 the OpenLDAP overlay and the 389 Directory Server
  plugin capture changes (built from source on the directory's host, tested on Ubuntu
  24.04 only), and the Windows password filter is compile-checked only, never loaded on a
  domain controller; see [Password synchronisation](password-sync.md).
