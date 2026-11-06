# Configuration

syncd has two programs, and each is configured in its own way:

| Program | Configured by | Where (package install) |
|---|---|---|
| `syncd`, the engine | One YAML document: connectors, views, rules and publications, plus the engine's own settings. Secrets are never written in it; it names environment variables or files that hold them. | `/etc/syncd/engine/engine.yaml`, with the secrets in `/etc/syncd/engine.env` |
| `syncd-console`, the web console | Environment variables (or the matching command-line flags). | `/etc/syncd/console.env` |

This page is the reference for both. For the how-to detail of each source and target, see
[Connectors](connectors.md). For the rule language used in flows, computed attributes and
publication mappings, see [Computed attributes and rules](rules.md).

## Files

| Path | What | Owner and mode |
|---|---|---|
| `/etc/syncd/engine/engine.yaml` | The engine configuration. Publishing a configuration in the console, or `syncd apply-config`, replaces it. | `syncd:syncd`, `0640` |
| `/etc/syncd/engine.env` | Secrets the engine configuration refers to (`password: { env: NAME }`), and `SYNCD_FIPS_MODE`. A systemd `EnvironmentFile`. | `root:syncd`, `0640` |
| `/etc/syncd/console.env` | The console's settings: database URL, listen address, proxies, single sign-on. A systemd `EnvironmentFile`. | `root:syncd-console`, `0640` |
| `/etc/syncd/pki/` | The control API's CA, the engine's certificate, and the client certificates of the console and the command line. Issued by the installer. | |

`syncdctl install` writes each file only if it does not exist yet, so an upgrade keeps
your changes. The `.env` files are `KEY=value` lines with no quotes needed. After changing
one, restart the service it belongs to:

```sh
sudo systemctl restart syncd           # after engine.env
sudo systemctl restart syncd-console   # after console.env
```

## The engine configuration file

### Structure

A configuration is one YAML document. Unknown keys are refused everywhere, so a misspelt
key is an error, not a silently ignored setting.

| Key | Type | Default | What |
|---|---|---|---|
| `version` | integer | required | Must be `1`. |
| `engine` | map | see [Engine](#engine-engine) | Data directory, shards, intervals, history, cache. |
| `api` | map | see [Control API](#control-api-api) | The engine's control API (gRPC), used by the console and the `syncd` command line. |
| `attributes` | map | empty | Schema hints shared by every view and connector. |
| `connectors` | list | empty | The systems syncd reads and writes, and the objects it reads from each. |
| `views` | list | empty | Merged views: which sources feed them, how records join, attribute flows, precedence, computed attributes. |
| `publications` | list | empty | What each view writes to an LDAP or Active Directory target. |

```yaml
version: 1
engine: { ... }
api: { ... }
attributes: { ... }
connectors: [ ... ]
views: [ ... ]
publications: [ ... ]
```

Names of connectors, views and publications must be unique. Object types must be unique
within a connector.

### Secret references

A password or token is never written in the configuration. A secret setting names where
to read it:

| Form | Reads |
|---|---|
| `{ env: NAME }` | The environment variable `NAME`. |
| `{ file: /path/to/file }` | The file's contents, with a trailing newline (`\n` or `\r\n`) removed. |

```yaml
password: { env: CORP_LDAP_PASSWORD }
token: { file: /etc/syncd/bridge-token }
```

On a package install, define the variables in `/etc/syncd/engine.env`. The `syncd`
service reads that file, and so does the `syncd` command on the host when it uses the
packaged configuration, so `syncd test-connection` finds the same passwords. Variables
already set in the environment win; `--env-file` (or `SYNCD_ENV_FILE`) names another file.
A file named in `{ file: ... }` must be readable by the `syncd` user.

Secret settings are: `password` (databases, LDAP, Active Directory, MongoDB, JDBC) and
`token` (Debezium, JDBC bridge). The JDBC connector refuses a URL or driver property that
carries a password in plain text.

### The packaged configuration

The packages install this `/etc/syncd/engine/engine.yaml`:

```yaml
version: 1

engine:
  # systemd passes --data-dir /var/lib/syncd/engine, which takes precedence.
  data_dir: /var/lib/syncd/engine

# Control API, on loopback with mutual TLS. The installer issued these certificates
# from a CA of its own (/etc/syncd/pki) and registered this engine in the console.
api:
  listen: 127.0.0.1:7443
  tls:
    cert_file: /etc/syncd/pki/engine.crt
    key_file: /etc/syncd/pki/engine.key
    client_ca_file: /etc/syncd/pki/ca.crt
```

and this `/etc/syncd/engine.env`, which defines nothing until you add your secrets:

```sh
# LDAP_BIND_PASSWORD=...
# SYNCD_FIPS_MODE=true      # only with a FIPS build of syncd
```

The `syncd` unit starts the engine as
`syncd -c /etc/syncd/engine/engine.yaml --data-dir /var/lib/syncd/engine run`, so the data
directory is fixed whatever a configuration says.

### Changing the configuration

- **From the console.** Designers edit configuration versions, which the console checks
  with the engine's own compiler and whose rule test cases it runs. Publishing sends the
  version to the engine, which validates it, writes it over `engine.yaml` and reloads. See
  the [user guide](user-guide.md).
- **From the command line.** `syncd apply-config new.yaml` does the same through the
  control API. `syncd simulate new.yaml` shows what applying it would send each target,
  without applying it.
- **Host-only settings.** `engine.data_dir` and the whole `api` section can only be
  changed in the file on the engine host. The engine refuses a configuration sent through
  the control API that changes them, so a mistake there cannot leave the engine
  unreachable. Start a configuration in the console by importing the running one, which
  copies them; an exported configuration file leaves them out.
- **Checks.** `syncd validate -c file.yaml` checks structure and compiles every
  expression. `syncd test -c file.yaml` runs the test cases of every computed attribute.
  A configuration whose tests fail can be saved in the console but not published, and the
  engine refuses to apply it.
- **Warnings.** A rule that reads an attribute nothing in its view sets (a misspelt
  `e.departmnet`), or a lookup table the view does not have, is reported as a warning by
  `syncd validate`, in the reply to `apply-config`, in the log at start and in the
  console. It is not an error: the rule reads the attribute as absent.

## Engine (`engine`)

| Key | Type | Default | What |
|---|---|---|---|
| `data_dir` | path | `./data` | The engine's store. Overridden by `--data-dir` (or `SYNCD_DATA_DIR`); the packaged unit passes `/var/lib/syncd/engine`. Host-only. |
| `shards` | integer | `0` | Worker shards for recompute and export planning. `0` means one per CPU. |
| `scrape_interval_secs` | integer | `3600` | Seconds between full reads of scrape-mode objects, and between full export reconciliations unless `reconcile_interval_secs` is set. Also the default drift check interval. |
| `reconcile_interval_secs` | integer | `scrape_interval_secs` | Seconds between full export reconciliations, which plan every entity of every publication against what was exported. At least `1`. On a large store this is the engine's longest routine work, so it can run less often than full reads. |
| `source_retry_max_secs` | integer | `60` | Longest wait before restarting a source that failed. Waits double from one second up to this. |
| `history.keep_days` | integer | `30` | Days of entity history to keep: what changed, what caused it, what was exported. `0` records no history. |
| `history.keep_per_entity` | integer | `100` | History entries kept per entity (the newest). |
| `cache_mb` | integer | `256` | MiB of memory caching the store's decompressed blocks. At most `32768`. Takes effect when syncd restarts. See [Availability and sizing](availability-and-sizing.md). |

```yaml
engine:
  data_dir: /var/lib/syncd/engine
  shards: 0
  scrape_interval_secs: 3600
  reconcile_interval_secs: 86400
  history: { keep_days: 30, keep_per_entity: 100 }
  cache_mb: 256
```

## Control API (`api`)

The console and the `syncd` command line talk to the engine through its control API
(gRPC). The whole section is host-only.

| Key | Type | Default | What |
|---|---|---|---|
| `enabled` | boolean | `true` | Serve the control API. |
| `listen` | `host:port` | `127.0.0.1:7443` | Where it listens. Plain text is only allowed on a loopback address; any other address needs `tls`. |
| `tls.cert_file` | path | | PEM certificate chain the API presents. |
| `tls.key_file` | path | | Its private key. |
| `tls.client_ca_file` | path | | PEM CA certificates that client certificates must chain to. With `tls`, every client needs a certificate (mutual TLS). |
| `local_control` | boolean | `false` | Allow commands that change something (runs, approvals, dead-letter actions) on the plain-text loopback listener. With `tls` they are always allowed, to clients with a valid certificate. |
| `local_reveal` | boolean | `false` | Allow requests for sensitive values (`reveal` in the object explorer, previews and simulations) on the plain-text loopback listener, where any local account can call. With `tls` they are always allowed. |
| `control_socket` | path | unset | A Unix socket where the engine also serves the whole API, commands and reveals included, to its own user only (the socket is mode `0600`). `syncd` commands on the host use it when it exists. |
| `metrics_listen` | `host:port` | unset | Serve Prometheus metrics over plain HTTP at `GET /metrics` (`127.0.0.1:9464`). Off unless set. Counters and states only, no attribute values. See the [operator guide](operator-guide.md). |

With `tls` set, the engine logs each command with the client certificate's name. The
packaged configuration uses mutual TLS on loopback with certificates from the installer's
own CA; `sudo syncdctl cli-cert` issues a command-line certificate on a host that lacks
one.

!!! note "A control socket on a package install"
    The packaged configuration does not set `control_socket`. The `syncd` unit makes the
    file system read-only apart from `/var/lib/syncd/engine` and `/etc/syncd/engine`
    (`ProtectSystem=strict`, `ReadWritePaths=`). To use a socket in another directory,
    such as `/run/syncd/control.sock`, also give the service that directory with
    `systemctl edit syncd` (for example `RuntimeDirectory=syncd`).

## Attribute hints (`attributes`)

| Key | Type | Default | What |
|---|---|---|---|
| `multi_valued` | list of names | empty | Attributes diffed as sets on export (LDAP add and delete of values) instead of replaced, and always read as a list in rules: one value is a one-element list, an absent attribute an empty list. Matched ignoring case. |
| `sensitive` | list of names | empty | Attributes whose values the console's object explorer, previews and simulations hide unless the user may reveal them (each reveal is audited). Names of view attributes, source fields or target attributes. What derives from them is hidden too: computed attributes that read them, source fields that flow into them, and target attributes built from them. DNs are not masked, so do not build DNs from sensitive attributes. |

```yaml
attributes:
  multi_valued: [memberOf, mail, member]
  sensitive: [birthDate, nationalId]
```

Who may reveal is set in the console by `CONSOLE_REVEAL_ROLES`.

## Connectors (`connectors`)

A connector is one system syncd reads, writes, or both. Each has a type, connection
settings that match the type, the objects it reads, and an optional deletion threshold.

| Key | Type | Default | What |
|---|---|---|---|
| `name` | text | required | Unique; views and publications refer to it. |
| `type` | enum | required | `postgres`, `mysql` (MySQL 8 and MariaDB 10.5+), `sql_server` (SQL Server 2016+ and Azure SQL Managed Instance), `mongodb`, `ldap`, `active_directory`, `debezium`, `jdbc`. |
| `connection` | map | required | The settings for the type, below. Settings of another type are refused. |
| `objects` | list | empty | The object types read from it. A connector used only as a target needs none. |
| `deletion_threshold` | map | unset | Safety limit for deletes detected in one import run: `percent` and/or `count`. A run that deletes more than either is held until an operator approves it. |

```yaml
deletion_threshold:
  percent: 5      # more than 5% of the source's records
  count: 500      # or more than 500 records
```

Only `ldap` and `active_directory` connectors can be publication targets.

### Database connections (`postgres`, `mysql`, `sql_server`)

| Key | Type | Default | What |
|---|---|---|---|
| `host` | text | required | Server host name or address. |
| `port` | integer | `5432` PostgreSQL, `3306` MySQL, `1433` SQL Server | |
| `user` | text | required | |
| `password` | secret | unset | |
| `dbname` | text | required | The database. |
| `tls` | `disable`, `require`, `verify_full` | `disable` | `require` encrypts without verifying the server (as in libpq). `verify_full` checks the certificate chain and host name. |
| `ca_file` | path | Mozilla root set | PEM CA certificates trusted for `verify_full`. Refused with any other `tls` mode. |

### LDAP and Active Directory connections (`ldap`, `active_directory`)

| Key | Type | Default | What |
|---|---|---|---|
| `url` | text | required | `ldap://` or `ldaps://`. |
| `failover_urls` | list | empty | Other servers holding the same data (replicas, other domain controllers), tried in order when `url` does not answer. |
| `bind_dn` | text | required | The bind identity (a DN, or `user@domain` for Active Directory). |
| `password` | secret | required | |
| `starttls` | boolean | `false` | Upgrade an `ldap://` connection to TLS. |
| `max_inflight` | integer | `32` | Operations in flight per connection: the ceiling of the writer's adaptive limit. 1 to 256. |
| `operation_timeout_secs` | integer | `60` | Seconds a bind, search or write may take before it fails and the connection counts as down. 1 to 3600. |
| `idle_timeout_secs` | integer | `900` | Seconds a persistent search (syncrepl) may stay silent before the session is reconnected. A quiet session is probed every minute. Must be at least `operation_timeout_secs`. |
| `profile` | `generic`, `openldap`, `389ds` (alias `rhds`), `unboundid` (aliases `ping`, `pingdirectory`) | `generic` | `ldap` connectors only: the directory server, for what LDAP leaves to each server, such as how `deprovision: disable` disables an account. Refused on `active_directory` connectors. See [Target profiles](connectors.md#target-profiles). |

### MongoDB connections (`mongodb`)

| Key | Type | Default | What |
|---|---|---|---|
| `url` | text | required | `mongodb://host1,host2/?replicaSet=rs0` or `mongodb+srv://cluster.example.com`, **without credentials**. Other options, such as `authSource`, stay in the URL. |
| `user` | text | unset | |
| `password` | secret | unset | |
| `dbname` | text | required | The database holding the collections. |
| `tls` | `disable`, `require`, `verify_full` | `disable` | A `mongodb+srv://` URL refuses `disable`. |
| `ca_file` | path | Mozilla root set | For `verify_full` only. |

### Debezium connections (`debezium`)

| Key | Type | Default | What |
|---|---|---|---|
| `listen` | `host:port` | required | Where syncd listens for the Debezium Server HTTP sink (`127.0.0.1:8085`). |
| `token` | secret | unset | A shared secret the sink puts in its URL path (`http://127.0.0.1:8085/<token>`). Required unless `listen` is a loopback address. |

### JDBC bridge connections (`jdbc`)

| Key | Type | Default | What |
|---|---|---|---|
| `bridge` | URL | required | The bridge: `http://127.0.0.1:50071`, or `https://host:port` when it serves TLS. A bridge beyond loopback needs `https://` and a `token`, because the database password travels to it. |
| `token` | secret | unset | The bridge's token (its `--token-file`). Required unless the bridge is on loopback. |
| `ca_file` | path | Mozilla root set | CA certificates trusted for an `https://` bridge. |
| `url` | text | required | The JDBC URL the bridge connects with: `jdbc:db2://db2.example.com:50000/HR`. Must start with `jdbc:` and must not carry a password. |
| `user` | text | unset | |
| `password` | secret | unset | |
| `operation_timeout_secs` | integer | `900` | Seconds one call to the bridge (the next batch of rows, a describe) may take before it fails. 1 to 86400. |
| `properties` | map | empty | Driver properties (`sslConnection: "true"`, `currentSchema: HR`). A `password` or `pwd` property is refused. |

### Objects

Each entry of `objects` is one object type the connector reads. The keys that apply depend
on the connector type:

| Key | Applies to | Default | What |
|---|---|---|---|
| `type` | all | required | The object type name views refer to (`person`, `group`). |
| `anchor` | all | required | The source attribute or column holding the immutable identifier. |
| `mode` | all | `scrape` | `scrape`, `delta` or `stream`; see [Read modes](#read-modes). |
| `table` | databases, Debezium, JDBC | | A table or view (`dbo.people`, `HR.EMPLOYEE`). For Debezium, the captured table whose events are this object's. |
| `query` | databases, JDBC | | A SQL query instead of a table; its columns become attributes. |
| `cdc` | `postgres`, `mysql`, `sql_server` | | Change capture for `mode: stream`; see [Change capture](#change-capture-cdc). |
| `base` | LDAP, AD | | Search base DN. |
| `filter` | LDAP, AD, MongoDB | `(objectClass=*)` for LDAP | LDAP: a search filter. MongoDB: a query document in extended JSON (`'{"status": "active"}'`). |
| `scope` | LDAP, AD | `sub` | `base`, `one` or `sub`. |
| `attributes` | LDAP, AD, MongoDB | empty (all user attributes, or whole documents) | Attributes or fields to read. |
| `collection` | MongoDB | | The collection to read. |
| `changelog` | `ldap` with `mode: stream` | | Follow the directory's change log instead of syncrepl; see [LDAP change logs](#ldap-change-logs-changelog). |
| `poll_interval_secs` | streams that poll | `10` (DirSync), `5` (change logs) | Seconds between polls. Only for Active Directory streams and `changelog` streams. |
| `interval_secs` | scrape, delta | `engine.scrape_interval_secs` | Seconds between reads (delta: between reads of what changed). Not for streams. |
| `schedule` | scrape, delta | unset | When full reads start, as a [cron schedule](#schedules). Replaces `interval_secs` (scrape) or `full_interval_secs` (delta); setting both is refused. Not for streams. |
| `schedule_zone` | with `schedule` | `UTC` | The schedule's time zone, an IANA name (`Europe/Paris`). |
| `full_interval_secs` | delta | one day | Seconds between full reads, which also find deletes. |
| `watermark` | delta | `modifyTimestamp` (LDAP), `uSNChanged` (AD); required for databases and MongoDB | What marks a change: a column or field that grows with every change, or a directory attribute. |
| `binary` | all | empty | Attributes or columns that hold binary data beyond those the connector recognises by type: LDAP attributes the built-in list misses, and Debezium columns, which arrive as base64 text. |
| `timestamps` | all | empty | Attributes or columns to send as timestamps instead of text. Listed values are read as rules' `timestamp()` reads text, so rules get a timestamp and LDAP targets GeneralizedTime. A value that does not read as one stays text. |

An object sets exactly one of `table`, `query` (databases, JDBC, Debezium `table` only),
`base` (LDAP and Active Directory) or `collection` (MongoDB), and only the options that
belong to it.

#### Read modes

| Mode | What it does | Allowed for |
|---|---|---|
| `scrape` | A full read every `interval_secs` (or on `schedule`), diffed against what is staged. Records not seen are deleted. | Every type except `debezium`. |
| `delta` | Every `interval_secs`, reads what changed since the last read by the `watermark`; every `full_interval_secs` (or on `schedule`) reads everything, which finds deletes. | Every type except `debezium`. |
| `stream` | Follows changes as they happen, from a checkpoint committed with the changes. | PostgreSQL, MySQL, SQL Server (with `cdc`), MongoDB, LDAP (syncrepl or `changelog`), Active Directory (DirSync), Debezium (required). Not `jdbc`. |

Anchor rules for streams, checked by validation:

- an `ldap` object streamed with syncrepl needs `anchor: entryUUID`;
- an `active_directory` object streamed with DirSync needs `anchor: objectGUID`;
- a MongoDB object streamed with a change stream needs `anchor: _id`.

How each type reads, and what it needs on the source side, is in
[Connectors](connectors.md).

#### Change capture (`cdc`)

| Key | Database | Default | What |
|---|---|---|---|
| `slot` | PostgreSQL (required) | | The logical replication slot, created if missing. |
| `publication` | PostgreSQL (required) | | The PostgreSQL publication, created if missing. |
| `server_id` | MySQL (required) | | The replica id this stream uses, unique among the server's replicas and other binlog readers. |
| `poll_ms` | SQL Server | `1000` | How often the change tables are read, in milliseconds. At least `1`. |
| `change_tracking` | SQL Server | `false` | Follow change tracking instead of change data capture. |
| `watch` | all three, `query` objects | empty | The base tables a query reads, and how a changed row maps to the anchors of the query rows it affects. Required for a streamed `query` object; refused on a `table` object. |

A setting that belongs to another database is refused (`slot` on a MySQL object, for
example). SQL Server needs no required settings: write `cdc: {}`.

Each `watch` entry maps a table name to either:

- a column name: that column of the changed row is the query's anchor
  (`people: employee_id`); or
- `{ key: <column>, anchors: <SQL> }`: SQL returning the affected anchors, with the changed
  row's `key` column as `$1` (text; cast it, such as `$1::int`).

```yaml
- type: dept
  anchor: emp
  query: SELECT p.employee_id AS emp, d.code FROM people p JOIN departments d ON d.name = p.department
  mode: stream
  cdc:
    slot: syncd_dept
    publication: syncd_dept_pub
    watch:
      people: employee_id
      departments: { key: name, anchors: "SELECT employee_id FROM people WHERE department = $1" }
```

#### LDAP change logs (`changelog`)

For `ldap` objects with `mode: stream`, as an alternative to syncrepl:

| Key | Default | What |
|---|---|---|
| `format` | required | `changelog`: numbered entries under `cn=changelog` (389 Directory Server and Red Hat Directory Server Retro Changelog, ForgeRock / OpenDJ, Ping Directory, Oracle DSEE and Oracle Unified Directory). `accesslog`: OpenLDAP's access log overlay. |
| `base` | `cn=changelog` or `cn=accesslog` | Where the log's entries are. |

Active Directory objects stream with DirSync and do not take `changelog`. The same
`changelog` map is used by a publication's [`writeback`](#write-back-writeback).

## Views (`views`)

A view merges records from one or more sources into entities.

| Key | Type | Default | What |
|---|---|---|---|
| `name` | text | required | Unique. |
| `sources` | list | required (at least one) | Which connector objects feed the view; below. At least one must project. |
| `precedence` | map | source order | Which source wins for each attribute; below. |
| `computed` | list | empty | Computed attributes; below and in [rules](rules.md). |
| `lookups` | map | empty | Inline lookup tables: table name to key to value. |
| `references` | map | empty | Attributes whose values identify other entities; below. |

### Sources

| Key | Type | Default | What |
|---|---|---|---|
| `connector` | text | required | A connector name. |
| `object` | text | required | One of its object types. Each connector and object pair appears once per view. |
| `project` | boolean | `false` | Whether a record that joins no entity creates a new one. Authoritative sources (the HR database) project; others usually only join. |
| `join` | list | empty | Ordered join rules. The first that yields exactly one candidate wins; several candidates are an ambiguous join, held for review. A source must project, join, or both. |
| `flows` | map | empty | View attribute to a CEL expression over `source`, the staged record. |

A join rule is either one condition or several that must all match the same entity:

| Key | Default | What |
|---|---|---|
| `entity` | | View attribute to match against (one condition). |
| `source` | | CEL over `source` producing the value to look up (one condition). |
| `case_insensitive` | `true` | Compare ignoring case. |
| `normalize` | `false` | Compare transliterated, lower-cased letters and digits only (`Zoë O'Neil` matches `zoe oneil`); implies case-insensitive. |
| `all` | | Instead of `entity` and `source`: a list of conditions, each with `entity`, `source`, `case_insensitive` and `normalize`. |
| `when` | | CEL over `source`: the rule only applies to records for which it holds. |

```yaml
sources:
  - connector: hr_db
    object: person
    project: true
    flows:
      employeeId: source.employee_id
      givenName: source.given
      sn: source.sn
  - connector: corp_ldap
    object: person
    join:
      - entity: employeeId
        source: source.employeeNumber
      - all:
          - { entity: givenName, source: source.givenName, normalize: true }
          - { entity: sn, source: source.sn, normalize: true }
        when: '!has(source.employeeNumber)'
    flows:
      uid: source.uid
      mail: source.mail
```

A flow whose input attribute is absent gives the view attribute no value from that source.
`unique()` is refused in flows and join rules: it belongs in computed attributes.

### Precedence

| Key | Type | What |
|---|---|---|
| `precedence.default` | list of connector names | Connector priority for attributes without their own entry. When empty, the order of `sources`. |
| `precedence.attributes` | map | Attribute to its own list of connector names. |

For each attribute, the highest-ranked source that has a value wins. Sources a list leaves
out follow, in `sources` order. Every name must be a source of the view.

```yaml
precedence:
  default: [hr_db, corp_ldap]
  attributes:
    title: [corp_ldap, hr_db]
```

!!! note
    Precedence is a priority list only. Other survivorship policies described in the
    product plan (most recent, union, a custom reducer) are not built.

### Computed attributes

| Key | Type | Default | What |
|---|---|---|---|
| `name` | text | required | The attribute it sets. Unique within the view. |
| `mode` | `recompute`, `assign_once`, `only_if_empty` | `recompute` | Value lifecycle; see [Value modes](rules.md#value-modes). |
| `steps` | list | empty | `let` and `when` steps before the output; see [Multi-step transforms](rules.md#multi-step-transforms). |
| `output` | CEL | required | The final value. May read `e` and step variables. |
| `on_error` | `skip`, `default`, `quarantine` | `skip` | What a failed evaluation does; see [Error policies](rules.md#error-policies). |
| `default` | text | unset | The fallback for `on_error: default`, which requires it. |
| `tests` | list | empty | Test cases: `input`, optional `lookups`, `expect`; see [Test cases](rules.md#test-cases). |

Computed attributes are evaluated in dependency order; a cycle is refused.

### Lookup tables

```yaml
lookups:
  dept_codes:
    Engineering: ENG
    Sales: SLS
```

Rules read them with `lookup("dept_codes", e.department, "GEN")`. See
[Lookup tables](rules.md#lookup-tables).

### References

`references` maps an attribute of the view to how its values find other entities.
Publications export a reference as the DN of the referenced entity in the target, and
computed attributes can follow it with `ref()` and friends.

| Key | Type | Default | What |
|---|---|---|---|
| `view` | text | the declaring view | The view holding the referenced entities. |
| `match` | text | required | The attribute of the referenced entity that a reference value equals (`employeeId`, or a source DN flowed into the view for LDAP `manager` or `member` values). |
| `case_insensitive` | boolean | `false` | Compare ignoring case. |

```yaml
references:
  manager: { match: employeeId }                 # within the same view
  member: { view: people, match: employeeId }    # a group view pointing at people
```

## Publications (`publications`)

A publication writes a view's entities to an LDAP or Active Directory target.

| Key | Type | Default | What |
|---|---|---|---|
| `name` | text | required | Unique. |
| `view` | text | required | The view to publish. |
| `target` | text | required | An `ldap` or `active_directory` connector. |
| `filter` | CEL over `e` | all entities | Which entities are in scope. Entities leaving scope are deprovisioned. |
| `dn` | template | required | The entry's DN; see [DN template](#dn-template). |
| `object_classes` | list | required, not empty | The entry's object classes. |
| `attributes` | map | required | Target attribute to a CEL expression over `e`. |
| `dn_attributes` | map | empty | Values only the DN template uses, not exported: name to CEL over `e`. |
| `references` | map | empty | Target attribute to a reference attribute of the view. Each value is exported as the DN the referenced entity has in this target; values whose entity is not exported there are left out. |
| `deprovision` | see below | `delete` | What happens to an entry whose entity leaves scope or is deleted. |
| `deletion_threshold` | map | unset | `percent` and/or `count`, as for connectors, for the deletes this publication makes. |
| `create_containers` | boolean | `false` | Create the missing parents of an entry, as many levels as are missing, instead of failing the export. |
| `groups` | list | empty | Computed groups; below. |
| `adopt` | map | unset | Take over entries already in the target; below. |
| `drift` | map | unset | Read the target back and act on changes made there; below. |
| `writeback` | map | unset | After a target outage, write changes made in the target back to an LDAP source; below. |

A publication's `references` need exactly one publication of the referenced view to the
same target, since that is where the referenced entity's DN comes from. A target attribute
cannot be both in `attributes` and in `references`.

Publication expressions read only the entity `e`; `ref()` and the other cross-entity
functions are for computed attributes. Values of attributes listed in
`attributes.multi_valued` are diffed value by value; other attributes are replaced.

### DN template

- `{attr}` is replaced by the value of an exported attribute or a `dn_attributes` entry,
  escaped for an RDN (RFC 4514).
- `{dn:attr}` is replaced by a value that is itself a DN, or the RDNs of one, as it is.
  The value is checked to be a DN; one that is not leaves the entity unexported and
  counts as a rule error. The `dn:` prefix ignores case.
- Every placeholder must name an exported attribute or a `dn_attributes` entry. A name
  cannot be both.

```yaml
dn: uid={uid},{dn:container}
dn_attributes:
  container: '"ou=" + escapeRdn(e.department) + ",ou=People,dc=example,dc=com"'
create_containers: true
```

A changed DN value moves the entry (a modify DN), so it keeps its identity. With
`create_containers`, the missing parents are created with the class their RDN calls for:

| RDN | Object class |
|---|---|
| `ou=` | `organizationalUnit` |
| `o=` | `organization` |
| `l=` | `locality` |
| `dc=` | `domain` |
| `c=` | `country` |
| `cn=` | `container` (Active Directory targets only) |

Up to 16 levels are created. Any other RDN type fails the export with a dead letter.
Containers are created, never deleted.

### Deprovisioning

| Value | What happens to an entry whose entity leaves scope or is deleted |
|---|---|
| `delete` | The entry is deleted. |
| `{ move_to: <DN> }` | The entry is moved under this fixed container. |
| `disown` | The entry is left in place and no longer managed. |
| `disable` | The account is disabled and the entry kept and managed. An entity that comes back into scope is enabled again. |
| `{ disable_and_move_to: <DN> }` | The account is disabled and moved under this fixed container. |

The containers must be fixed DNs (no placeholders). `disable` and `disable_and_move_to`
need a target that knows how to disable an account: an `active_directory` connector, or an
`ldap` connector with a `profile`. See [Target profiles](connectors.md#target-profiles).

```yaml
deprovision: { disable_and_move_to: "OU=Disabled,OU=Synced,DC=corp,DC=example,DC=com" }
```

### Computed groups (`groups`)

A group entry kept in the target with every exported entry of the publication that matches
a predicate as a member.

| Key | Type | Default | What |
|---|---|---|---|
| `name` | text | required | Unique within the publication. |
| `filter` | CEL over `e` | required | Who is a member: `e.department == "Engineering"`. |
| `dn` | DN | required | The group entry's fixed DN (no placeholders). |
| `object_classes` | list | `[groupOfNames]` | |
| `member_attribute` | text | `member` | The attribute holding member DNs. |
| `attributes` | map | empty | Other attributes of the group entry, with literal values. Must not set the member attribute or `objectClass`. |
| `when_empty` | `delete`, `keep` | `delete` | `delete` removes an empty group and adds it again with its first member (`groupOfNames` must have a member). `keep` keeps it with no members, for classes that allow that, such as Active Directory's `group`. |

```yaml
groups:
  - name: engineering
    filter: e.department == "Engineering"
    dn: cn=engineering,ou=Groups,dc=example,dc=com
    attributes: { description: Everyone in engineering }
```

Groups whose members come from a group view in the source are published as an ordinary
publication of that view, with the member attribute as a reference.

### Adopting existing entries (`adopt`)

Before adding the entry of an entity it has not exported yet, the writer looks under `base`
for an entry whose `match` attributes have the values the entity exports. One found is
moved to the entity's DN if it is elsewhere, given the publication's missing object
classes and updated, and is managed like any exported entry from then on. More than one
found fails the export (a dead letter naming them). Attributes the publication does not
map are left as they are.

| Key | Type | What |
|---|---|---|
| `match` | list, required | Exported target attributes that identify an existing entry (`employeeNumber`, `sAMAccountName`); all must match. |
| `base` | DN, required | Where existing entries are looked for (subtree). A fixed DN. |
| `filter` | LDAP filter | Candidates must also match it, such as `(objectClass=person)`. |

### Drift detection (`drift`)

The engine reads each exported entry back from the target and compares the attributes it
manages with what it last wrote.

| Key | Type | Default | What |
|---|---|---|---|
| `interval_secs` | integer | `engine.scrape_interval_secs` | Seconds between checks. `syncd check-drift` and the console check on demand. |
| `default` | `flag`, `revert`, `accept` | `flag` | The policy for attributes not listed, and for missing entries (where `accept` counts as `flag`). |
| `attributes` | map | empty | Exported target attribute to its policy. |

| Policy | What it does |
|---|---|
| `flag` | Reports the change (status, entity history) and leaves it until the engine next writes the attribute. |
| `revert` | Writes the engine's value back; a missing entry is added back. |
| `accept` | Keeps the target's value until the value the engine computes changes. Attributes the DN is built from cannot be accepted and count as `flag`. |

```yaml
drift:
  interval_secs: 3600
  default: flag
  attributes: { title: revert, mail: accept }
```

### Write-back (`writeback`)

After an outage of the target, the changes applications made in the target meanwhile are
written back to an LDAP source, once the engine reaches the target again. Only modifies of
the listed attributes are replayed, on the source entry each target entry came from. Adds,
deletes and renames are reported, not replayed. An attribute the source also changed
meanwhile keeps the source's value and is reported as a conflict.

| Key | Type | What |
|---|---|---|
| `source` | text, required | An LDAP source connector of the publication's view. |
| `object` | text | Its object type, when the view reads more than one object from it. |
| `attributes` | map, required | Target attribute to source attribute. Not `objectClass` or `dn`. |
| `changelog` | map, required | The target's change log: `{ format: accesslog }` or `{ format: changelog }`, with optional `base`. |

The target must have a change log, so write-back is refused for Active Directory targets.
See [Connectors](connectors.md#write-back-after-a-target-outage).

```yaml
writeback:
  source: corp_ldap
  attributes:
    telephoneNumber: telephoneNumber
    mobile: mobile
  changelog: { format: accesslog }
```

## Schedules

An object's `schedule` is a cron expression of five fields: minute, hour, day of month,
month, day of week.

- Each field takes `*`, a number, a range (`1-5`), a step (`*/15`, `9-17/2`, `5/20`) and
  lists of those (`0,30`).
- Months and days of the week also take their names (`jan` to `dec`, `sun` to `sat`).
  Day of the week `0` or `7` is Sunday.
- Shorthands: `@hourly`, `@daily` (or `@midnight`), `@weekly`, `@monthly`, `@yearly`
  (or `@annually`).
- When both the day of month and the day of week are restricted, a day matching either
  matches, as in cron.
- Times are local to `schedule_zone` (UTC by default). A time a clock change skips does
  not happen that day; a time that happens twice happens once, the first time.
- A schedule that never fires (`0 0 30 2 *`) is refused, and every mistake names its
  field.

When reads run:

- an object never read, or one whose scheduled time passed while the engine was stopped,
  reads at once when the engine starts;
- a read still running at a scheduled time skips it;
- `syncd run --once` reads at once, whatever the schedule.

```yaml
objects:
  - type: person
    anchor: id
    table: people
    schedule: "0 2 * * *"          # 02:00 every day
    schedule_zone: Europe/Paris
  - type: contractor
    anchor: id
    table: contractors
    mode: delta
    watermark: updated_at
    interval_secs: 300             # what changed, every 5 minutes
    schedule: "30 1 * * sun"       # in full, Sunday 01:30
```

Export reconciliations and drift checks run on intervals (`reconcile_interval_secs`,
`drift.interval_secs`), not on cron schedules.

## Validation rules

`syncd validate` and the console report every problem at once. Beyond the per-key rules
above, a configuration is refused when:

- `version` is not `1`;
- `api.listen` is not an address and port, or is not on loopback and `tls` is not set;
- `engine.reconcile_interval_secs` is `0` or `engine.cache_mb` is above 32768;
- a connector's connection settings do not match its type, or an object's source
  (`table`, `query`, `base`, `collection`) does not fit the connector type;
- a streamed database `table` or `query` object has no `cdc`, or a streamed `query`
  object has no `cdc.watch`;
- a delta database or MongoDB object has no `watermark`; `watermark` or
  `full_interval_secs` is set on an object that is not `delta`; `interval_secs` is set on
  a stream; an interval is `0`;
- a `debezium` object is not `mode: stream`, or a `jdbc` object is;
- `schedule` is set on a stream, together with the interval it replaces, or
  `schedule_zone` is set without it;
- a view has no source that projects, a source neither projects nor joins, a join rule
  mixes `entity`/`source` with `all`, precedence names a connector that is not a source,
  or a reference names an unknown view;
- a computed attribute uses `on_error: default` without `default`;
- a publication names an unknown view or a target that is not an LDAP directory, its DN
  template uses an unknown placeholder, `object_classes` is empty, a group's DN is not
  fixed, a drift policy names an attribute it does not export, or `adopt.match` names one
  it does not export;
- every expression is also compiled: syntax and type errors, unknown functions,
  `unique()` outside computed attributes, more than one `unique()` scope in one computed
  attribute, and cycles between computed attributes.

## Engine environment variables and options

The `syncd` command reads these; a flag wins over its variable.

| Variable | Flag | Default | What |
|---|---|---|---|
| `SYNCD_CONFIG` | `-c`, `--config` | `./syncd.yaml`, else `/etc/syncd/engine/engine.yaml` | The configuration file. |
| `SYNCD_ENV_FILE` | `--env-file` | `/etc/syncd/engine.env` with the packaged configuration | Environment file with the secrets the configuration refers to. Variables already set win. |
| `SYNCD_DATA_DIR` | `--data-dir` | `engine.data_dir` | Overrides the data directory. |
| `SYNCD_FIPS_MODE` | `--fips` | off | Use the FIPS 140-3 crypto provider; set to `true`. Requires a FIPS build. See [FIPS 140-3 and STIG](fips-and-stig.md). |
| `SYNCD_API` | `--api` | `api.control_socket` if it exists here, else `api.listen` | How commands reach the running engine: `host:port`, or `unix:/path`. |
| `SYNCD_API_CA` | `--api-ca` | `ca.crt` next to `api.tls.client_ca_file`, else that file | CA that signed the API's certificate. |
| `SYNCD_API_CERT` | `--api-cert` | `cli.crt`, else `client.crt`, next to `api.tls.client_ca_file` | Client certificate for mutual TLS. |
| `SYNCD_API_KEY` | `--api-key` | `cli.key` / `client.key` next to it | The client certificate's private key file (not a token). |
| `RUST_LOG` | | `info` | Log level filter (`debug`, `syncd=debug`). |

Any other variable in `engine.env` is there for `{ env: NAME }` references.

## Console settings

The console reads its settings from environment variables, or from the matching flags of
`syncd-console serve`; `syncd-console --help` lists them. On a package install they live in
`/etc/syncd/console.env`; the `syncd-console` unit also sets `CONSOLE_DATA_DIR` and
`CONSOLE_UI_DIR`.

| Variable | Flag | Default | What |
|---|---|---|---|
| `CONSOLE_DATABASE_URL` | `--database-url` | required | PostgreSQL connection string for the console database. The installer fills it in. |
| `CONSOLE_LISTEN` | `--listen` | `127.0.0.1:8080` | Where the console listens. It serves plain HTTP: put a TLS reverse proxy in front of it for access from other machines. |
| `CONSOLE_DATA_DIR` | `--data-dir` | `./console-data`; the unit sets `/var/lib/syncd/console` | Local files: the master key and the initial admin password. |
| `CONSOLE_UI_DIR` | `--ui-dir` | unset; the unit sets `/opt/syncd/current/share/console-ui` | The built web UI, served at `/`. Without it only the API is served. |
| `CONSOLE_MASTER_KEY` | `--master-key` | read from, or created in, `<data dir>/master.key` | Master key for stored secrets (base64, 32 bytes). Back it up: stored secrets cannot be decrypted without it. |
| `CONSOLE_ADMIN_PASSWORD` | `--admin-password` | generated | Initial admin password for a production install. `admin` and empty passwords are refused. |
| `CONSOLE_ADMIN_PASSWORD_FILE` | `--admin-password-file` | unset | A file holding the initial admin password, instead. |
| `CONSOLE_SESSION_IDLE_MINUTES` | `--session-idle-minutes` | `30` | Minutes a session stays valid without activity. |
| `CONSOLE_SESSION_MAX_HOURS` | `--session-max-hours` | `12` | Hours a session stays valid at most. |
| `CONSOLE_REVEAL_ROLES` | `--reveal-roles` | `admin` | Roles that may reveal sensitive attributes (each reveal is audited), comma-separated, or `none`. |
| `CONSOLE_TRUSTED_PROXIES` | `--trusted-proxies` | empty | Reverse proxies whose `X-Forwarded-For` names the client: addresses or CIDR ranges, comma-separated (`127.0.0.1` for nginx on the same host). The client address goes to the audit log and the per-address sign-in limit. Only list proxies you run. |
| `CONSOLE_PUBLIC_URL` | `--public-url` | unset | The console's address as users reach it, through the proxy (`https://syncd.example.com`). Needed for single sign-on; with an `https://` value, the startup warning about plain-HTTP remote access is not shown. |
| `SYNC_DEV_MODE` | `--dev` | off | Development mode: `admin`/`admin` is allowed, the password change can be skipped, cookies work over plain HTTP, and the UI shows a warning banner. Never in production. Note the name: `SYNC_`, not `SYNCD_`. |
| `SYNCD_FIPS_MODE` | `--fips` | off | Use the FIPS 140-3 crypto provider; passwords are hashed with PBKDF2 instead of Argon2id. |
| `RUST_LOG` | | `info` | Log level filter. |

Session cookies carry `Secure` outside development mode, so browsers send them over HTTPS,
or to localhost, only. The console warns at startup when it listens beyond loopback over
plain HTTP without an `https://` `CONSOLE_PUBLIC_URL`. See [Install](install.md) for the
nginx configuration the packages ship.

### Single sign-on

Single sign-on is OpenID Connect only; it is off unless `CONSOLE_OIDC_ISSUER` is set. See
[Single sign-on](single-sign-on.md) for setting it up.

| Variable | Flag | Default | What |
|---|---|---|---|
| `CONSOLE_OIDC_ISSUER` | `--oidc-issuer` | unset | The provider's issuer URL (discovery at `<issuer>/.well-known/openid-configuration`). HTTPS unless on loopback. Turns single sign-on on. |
| `CONSOLE_OIDC_CLIENT_ID` | `--oidc-client-id` | unset | The client id. |
| `CONSOLE_OIDC_CLIENT_SECRET` | `--oidc-client-secret` | unset | The client secret. |
| `CONSOLE_OIDC_CLIENT_SECRET_FILE` | `--oidc-client-secret-file` | unset | A file holding the client secret, instead. |
| `CONSOLE_OIDC_SCOPES` | `--oidc-scopes` | `openid profile email` | Scopes requested. Add what your provider needs for the role claim. |
| `CONSOLE_OIDC_ROLE_CLAIM` | `--oidc-role-claim` | `groups` | The ID token claim holding the user's groups or roles (a string or a list). |
| `CONSOLE_OIDC_ROLE_MAP` | `--oidc-role-map` | empty | Claim values to console roles, first match wins: `syncd-admins=admin,syncd-designers=designer`. Separate entries with `;` when values contain commas (group DNs). |
| `CONSOLE_OIDC_DEFAULT_ROLE` | `--oidc-default-role` | unset | Role for users no mapping matches; unset, they are refused. |
| `CONSOLE_OIDC_USERNAME_CLAIM` | `--oidc-username-claim` | `preferred_username` | The claim used as the console username; `email`, then `sub`, when it is missing. |
| `CONSOLE_OIDC_LABEL` | `--oidc-label` | `single sign-on` | The sign-in button says "Sign in with <label>". |
| `CONSOLE_OIDC_CA_FILE` | `--oidc-ca-file` | unset | PEM CA certificates for a provider without a public certificate. |

The redirect URI to register with the provider is
`<CONSOLE_PUBLIC_URL>/api/v1/auth/oidc/callback`.

### The packaged console.env

```sh
# The console's database. The installer filled this in.
CONSOLE_DATABASE_URL=postgres://...

# Where the console listens (syncdctl install --listen; 127.0.0.1:8080 by default).
CONSOLE_LISTEN=127.0.0.1:8080

# The address users reach the console at, through the proxy (needed for single sign-on).
#CONSOLE_PUBLIC_URL=https://syncd.example.com

# Proxies whose X-Forwarded-For names the client.
#CONSOLE_TRUSTED_PROXIES=127.0.0.1

# Single sign-on (OpenID Connect).
#CONSOLE_OIDC_ISSUER=https://login.example.com/realms/corp
#CONSOLE_OIDC_CLIENT_ID=syncd-console
#CONSOLE_OIDC_CLIENT_SECRET_FILE=/etc/syncd/oidc-client-secret
#CONSOLE_OIDC_ROLE_MAP=syncd-admins=admin,syncd-operators=operator

#SYNCD_FIPS_MODE=true       # only with a FIPS build of syncd
```

`syncdctl install --fips` adds `SYNCD_FIPS_MODE=true` to both `console.env` and
`engine.env`.

## Complete example

An HR database and a corporate directory merged into a people view and published to
OpenLDAP, from `examples/hr-to-openldap.yaml` in the source repository (comments
shortened). The source repository has further examples for MySQL, SQL Server, MongoDB,
Oracle through Debezium and Db2 through the JDBC bridge; [Connectors](connectors.md)
shows their connector sections.

```yaml
version: 1

engine:
  data_dir: ./data
  shards: 0

attributes:
  multi_valued: [memberOf, mail]
  # sensitive: [birthDate, nationalId]

connectors:
  - name: hr_db
    type: postgres
    connection:
      host: 127.0.0.1
      port: 54329
      user: postgres
      dbname: postgres
    objects:
      - type: person
        anchor: id
        table: people
        mode: stream
        cdc:
          slot: syncd_hr
          publication: syncd_hr_pub
    deletion_threshold:
      percent: 5
      count: 500

  - name: corp_ldap
    type: ldap
    connection:
      url: ldap://127.0.0.1:3891
      bind_dn: cn=admin,dc=corp,dc=com
      password:
        env: CORP_LDAP_PASSWORD
    objects:
      - type: person
        anchor: entryUUID
        base: ou=People,dc=corp,dc=com
        filter: (objectClass=inetOrgPerson)
        attributes: [uid, mail, employeeNumber, telephoneNumber, title]

  - name: openldap
    type: ldap
    connection:
      url: ldap://127.0.0.1:3890
      bind_dn: cn=admin,dc=example,dc=com
      password:
        env: TARGET_LDAP_PASSWORD
      # profile: openldap

views:
  - name: people
    sources:
      - connector: hr_db
        object: person
        project: true
        flows:
          employeeId: source.employee_id
          givenName: source.given
          sn: source.sn
          department: source.department
          title: source.title
          status: source.status
          manager: source.manager_employee_id
      - connector: corp_ldap
        object: person
        join:
          - entity: employeeId
            source: source.employeeNumber
        flows:
          uid: source.uid
          mail: source.mail
          telephoneNumber: source.telephoneNumber
          title: source.title
    precedence:
      default: [hr_db, corp_ldap]
    references:
      manager: { match: employeeId }
    lookups:
      dept_codes:
        Engineering: ENG
        Sales: SLS
    computed:
      - name: displayName
        output: e.givenName + " " + e.sn
        tests:
          - input: { givenName: Jane, sn: Doe }
            expect: Jane Doe
      - name: departmentNumber
        output: lookup("dept_codes", e.department, "GEN")
        tests:
          - input: { department: Engineering }
            expect: ENG
          - input: { department: Legal }
            expect: GEN

publications:
  - name: people_to_openldap
    view: people
    target: openldap
    filter: e.status == "active"
    dn: uid={uid},ou=People,dc=example,dc=com
    object_classes: [inetOrgPerson]
    attributes:
      uid: e.uid
      cn: e.displayName
      givenName: e.givenName
      sn: e.sn
      mail: e.mail
      title: e.title
      employeeNumber: e.employeeId
      departmentNumber: e.departmentNumber
    references:
      manager: manager
    deprovision: delete
    deletion_threshold:
      percent: 5
    # adopt:
    #   match: [employeeNumber]
    #   base: ou=People,dc=example,dc=com
    # drift:
    #   interval_secs: 3600
    #   default: flag
    #   attributes: { title: revert, mail: accept }
```
