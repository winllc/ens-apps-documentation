# Connectors

A connector is one system syncd reads, writes, or both. Sources are SQL databases,
MongoDB, LDAP directories and Active Directory, plus any database that Debezium Server can
capture or that has a JDBC driver. Targets are LDAP directories and Active Directory.

This page says how each connector reads, what it needs on the source side, and which
settings matter. Every key is listed in [Configuration](configuration.md#connectors-connectors);
the rules that turn source records into entities are in
[Computed attributes and rules](rules.md).

## At a glance

| Type | Reads | Streams changes with | Delta reads by | Checkpoint |
|---|---|---|---|---|
| `postgres` | Tables, views, SQL queries | Logical replication (`pgoutput`) through a replication slot | A column (`updated_at`, a counter) | LSN |
| `mysql` | Tables, views, SQL queries (MySQL 8, MariaDB 10.5+) | The row-based binary log, read as a replica | A column | Binary log file and position |
| `sql_server` | Tables, views, SQL queries (SQL Server 2016+, Azure SQL Managed Instance) | Change data capture, or change tracking | A column (`rowversion`) | LSN, or change tracking version |
| `mongodb` | Collections | Change streams (replica set or sharded cluster) | A field | Resume token |
| `ldap` | Entries under a base | syncrepl (RFC 4533), or the directory's change log (`cn=changelog`, OpenLDAP access log) | `modifyTimestamp`, or another ordered attribute | Sync cookie, or change number / log time |
| `active_directory` | Entries under a base | DirSync | `uSNChanged` | DirSync cookie, or USN, per domain controller |
| `debezium` | Change events from a Debezium Server sidecar (Oracle, Db2 and others) | Always streams | | Debezium keeps its own offsets |
| `jdbc` | Tables and queries through the JDBC bridge (Db2, Informix, Oracle, any JDBC database) | Not supported; use `debezium` | A column | Watermark |

Only `ldap` and `active_directory` connectors can be publication targets. One connector
can be both a source and a target.

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
  of its records read as changed once.

See [Dates and times](rules.md#dates-and-times) and [Binary values](rules.md#binary-values).

## LDAP and Active Directory as targets

A publication writes a view's entities to an `ldap` or `active_directory` connector. The
target needs no `objects` unless it is also read.

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

Not yet built: stripping the group memberships of disabled entries, hard deletes after a
grace period, and re-enabling an entry recreated under a new entity.

### Active Directory targets

- `create_containers` can create `cn=` parents as `container`, as well as `ou=`, `o=`,
  `l=`, `dc=` and `c=` parents.
- Groups are ordinary publications of a group view with `member` as a reference, or
  computed groups with `object_classes: [group]` and `when_empty: keep`.
- syncd does not set passwords: password synchronisation is not built yet. The end-to-end
  configuration creates users with `userAccountControl` `544` (a normal account that does
  not require a password), mapped from a computed attribute.
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

## Checking a connector

Before publishing, check a connector against the real system. Nothing here writes.

```sh
sudo syncd test-connection hr_db         # connect (and bind), report what answered
sudo syncd discover hr_db                # list tables, collections or directory entries
sudo syncd discover hr_db public.people  # a container's fields and a few records
```

`test-connection` reports a directory's change log and change numbers when its root DSE
publishes them, and for a Debezium connector checks that the address can be listened on.
The console's configuration editor runs the same checks, with a schema browser and sample
records read as the engine would read them, for designers; each check is audited and
sensitive values stay hidden unless revealed.

## Tested servers

The continuous integration suite runs each connector end to end against real servers on
Ubuntu 24.04 runners (`tests/e2e` and `.github/workflows/ci.yml` in the source
repository):

| Connector | Tested against | What is covered |
|---|---|---|
| PostgreSQL | PostgreSQL 16 | Logical replication of tables and a query with watched tables, a publication completed with a watched table it lacked, delta reads |
| MySQL / MariaDB | The MySQL 8 and MariaDB packages of Ubuntu 24.04 | Binary log streaming and delta reads |
| SQL Server | SQL Server 2019 and 2022 (Microsoft's container images) | Change data capture, change tracking, delta reads, lost history |
| MongoDB | MongoDB 8.0 | Change streams and delta reads |
| LDAP source | OpenLDAP 2.6 | Full reads, delta reads, syncrepl, failover to a second URL, the access log, a container renamed under the base, case-only renames |
| LDAP source | 389 Directory Server 2.4 | Retro Changelog (`cn=changelog`), anchored on `nsUniqueId` |
| Active Directory source | Samba AD DC (4.19 in the benchmarks) | DirSync streaming |
| LDAP target | OpenLDAP 2.6, with and without ppolicy | Adds, renames, moves, case-only renames, containers created and removed when empty, groups, references, adoption, drift, write-back, entries moved by hand put back (`entryUUID`), permanent values, `openldap` profile |
| LDAP target | 389 Directory Server 2.4 | `389ds` profile: disable and re-enable, case-only renames, entries moved by hand put back (`nsUniqueId`), a move aside into a taken RDN |
| Active Directory target | Samba AD DC | Users and groups, containers, disable and move, group membership following moves, entries moved by hand put back (`objectGUID`), two users swapping names, a move aside into a taken RDN |
| Debezium | Debezium Server 3.0, with PostgreSQL standing in for Oracle and Db2 | Snapshot, changes, tokens, either side stopping |
| JDBC bridge | PostgreSQL and Apache Derby through their JDBC drivers | Scrape and delta reads, tokens, TLS, the bridge stopping |

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

Not tested against a real server:

- **Ping Identity / UnboundID** (`unboundid` profile): only the mapping is unit-tested.
- **Microsoft Active Directory** itself: Active Directory is tested through Samba AD DC.
- **Oracle and Db2**: their container images do not fit the CI runners, so PostgreSQL
  (Debezium) and Apache Derby (JDBC) stand in. Debezium's change events have the same
  shape for every database it captures.
- **Red Hat Directory Server, ForgeRock / OpenDJ, Oracle DSEE and Oracle Unified
  Directory**: supported through the same standards (`389ds` profile, `cn=changelog`) but
  not in the suite.

## Not supported

- **Other source families**: files, HR or SaaS APIs, Kafka, Entra ID and inbound SCIM
  are not planned until a customer needs them.
- **Targets other than LDAP and Active Directory**: syncd publishes to directories only.
- **eDirectory's change cache** as a stream.
- **Password synchronisation** to or from any directory is not built yet.
