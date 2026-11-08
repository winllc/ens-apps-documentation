# Configuration

syncd has two programs, and each is configured in its own way:

| Program | Configured by | Where (package install) |
|---|---|---|
| `syncd`, the engine | One YAML document: connectors, views, rules and publications, plus the engine's own settings. Secrets are never written in it; it names environment variables or files that hold them. | `/etc/syncd/engine/engine.yaml`, with the secrets in `/etc/syncd/engine.env` |
| `syncd-console`, the web console | Environment variables (or the matching command-line flags). | `/etc/syncd/console.env` |

The container images and the Helm chart configure the same two programs; see
[Container images and Helm](#container-images-and-helm) for what differs there.

This page is the reference for both: every key, one line each. The guide pages explain how
and why:

| Topic | Guide |
|---|---|
| Sources and targets, the Active Directory conveniences, initial passwords on create | [Connectors](connectors.md) |
| The rule language, effective dates, rule packages | [Computed attributes and rules](rules.md) |
| Change windows, thresholds, canaries, report mode, held changes, the staged leaver policy, group lifecycle | [Change safety and lifecycle](change-safety.md) |
| Captured passwords, capture agents, self-service, initial passwords | [Password synchronisation](password-sync.md) |
| Semantic diff, environments, Git sync, promotion, golden tests | [Configuration as code](config-as-code.md) |
| Reconciliation reports, lineage, history retention, data-quality checks, correlation assistance | [Reports, lineage and data quality](reports-and-data-quality.md) |
| High availability, backups and recovery | [Sizing, availability and recovery](availability-and-sizing.md) |

## Files

| Path | What | Owner and mode |
|---|---|---|
| `/etc/syncd/engine/engine.yaml` | The engine configuration. Publishing a configuration in the console, or `syncd apply-config`, replaces it. | `syncd:syncd`, `0640` |
| `/etc/syncd/engine.env` | Secrets the engine configuration refers to (`password: { env: NAME }`), `SYNCD_FIPS_MODE`, and the other [engine variables](#engine-environment-variables-and-options). A systemd `EnvironmentFile`. | `root:syncd`, `0640` |
| `/etc/syncd/console.env` | The console's settings: database URL, listen address, its own HTTPS certificate, proxies, single sign-on, the audit trail's outputs, API tokens, webhooks, reports and mail, the pre-publish hook and Git sync. A systemd `EnvironmentFile`. | `root:syncd-console`, `0640` |
| `/etc/syncd/pki/` | The control API's CA, the engine's certificate, and the client certificates of the console and the command line. Issued by the installer. | |

`syncdctl install` writes each file only if it does not exist yet, so an upgrade keeps
your changes. The release's own copies are in `/opt/syncd/current/share/config/`, so you
can see what a new release added to the templates. The `.env` files are `KEY=value` lines
with no quotes needed. After changing one, restart the service it belongs to:

```sh
sudo systemctl restart syncd           # after engine.env
sudo systemctl restart syncd-console   # after console.env
```

!!! warning "No comments after a value"
    systemd reads everything after the `=` as the value, a `#` included. When you
    uncomment a line of the templates, remove the comment at its end too:
    `CONSOLE_PUBLISH_HOOK_ON_ERROR=refuse   # or allow` would set the value
    `refuse   # or allow`, and the console would refuse to start.

## The engine configuration file

### Structure

A configuration is one YAML document. Unknown keys are refused everywhere, so a misspelt
key is an error, not a silently ignored setting.

| Key | Type | Default | What |
|---|---|---|---|
| `version` | integer | required | Must be `1`. |
| `engine` | map | see [Engine](#engine-engine) | Data directory, shards, intervals, history, cache, backups, high availability. |
| `api` | map | see [Control API](#control-api-api) | The engine's control API (gRPC), used by the console and the `syncd` command line. |
| `attributes` | map | empty | Schema hints shared by every view and connector. |
| `connectors` | list | empty | The systems syncd reads and writes, and the objects it reads from each. |
| `views` | list | empty | Merged views: which sources feed them, how records join, attribute flows, precedence, computed attributes, correlation hints, data-quality checks. |
| `publications` | list | empty | What each view writes to a directory, a SCIM application or Entra ID. |
| `passwords` | map | see [Passwords](#passwords-passwords) | The engine's password key, how long a captured change may wait, the capture agents' listener, self-service. |
| `packages` | list | empty | Rule packages: shared functions and lookup tables; see [Packages](#packages-packages). |

```yaml
version: 1
engine: { ... }
api: { ... }
attributes: { ... }
packages: [ ... ]
connectors: [ ... ]
views: [ ... ]
publications: [ ... ]
passwords: { ... }
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

Secret settings are:

- `password` (databases, LDAP, Active Directory, MongoDB, JDBC);
- `token` (Debezium, JDBC bridge, SCIM bearer token);
- `auth.basic.password` and `auth.oauth2.client_secret` (SCIM);
- `auth.client_secret` (Entra ID);
- `engine.ha.lease.url` (the lease database's URL, which carries its password).

The JDBC connector refuses a URL or driver property that carries a password in plain text.
`syncd config diff` never prints a secret setting's value, nor any `*_file` path.

### Environment placeholders

A string value anywhere in the configuration may hold `{{ env.<name> }}`, or
`{{ env.<name> | default "x" }}`, filled from an environment file. One base configuration
then serves several installations. The engine renders the file with
`--env FILE` (`SYNCD_ENVIRONMENT`) at every start, and `syncd config render` prints the
result. The console fills the placeholders from its own Environments when it publishes.
See [Environments](config-as-code.md#environments).

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
  # Uncomment for administration on this host without certificates: `syncd` commands
  # run as root (or as syncd) use the socket, which only the engine's own user and root
  # can open.
  # control_socket: /run/syncd/control.sock
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
  the [user guide](user-guide.md). With Git sync, versions can also come from commits;
  see [Configuration as code](config-as-code.md).
- **From the command line.** `syncd apply-config new.yaml` does the same through the
  control API. `syncd simulate new.yaml` shows what applying it would send each target,
  without applying it. `syncd config diff old.yaml new.yaml` (or `--running`) says what
  changed, by meaning.
- **Host-only settings.** `engine.data_dir`, `engine.ha` and the whole `api` section can
  only be changed in the file on the engine host. The engine refuses a configuration sent
  through the control API that changes them, so a mistake there cannot leave the engine
  unreachable. Start a configuration in the console by importing the running one, which
  copies them; an exported configuration file leaves them out.
- **Checks.** `syncd validate -c file.yaml` checks structure and compiles every
  expression. `syncd test -c file.yaml` runs the test cases of every computed attribute
  and every rule package, and with `--golden DIR` the golden-file tests. A configuration
  whose tests fail can be saved in the console but not published, and the engine refuses
  to apply it.
- **Warnings.** A rule that reads an attribute nothing in its view sets (a misspelt
  `e.departmnet`), or a lookup table the view does not have, is reported as a warning by
  `syncd validate`, in the reply to `apply-config`, in the log at start and in the
  console. It is not an error: the rule reads the attribute as absent.
- **Rolling back to 0.3.0.** syncd 0.3.0 refuses settings it does not know. Before a
  rollback that keeps the store, publish a configuration without the settings new in
  0.4.0.

### Spans of time

Settings that take a span of time as text (`soak`, `max_delay`, the leaver stages,
`expires_after`, `review_every`, `unmatched_alert_after`) are written in whole units:
`0d`, `7d`, `90d`, `12h`, `30m`, `45s`, or combined, larger units first, each once
(`1d12h`). `0d` means at once.

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
| `history.min_per_entity` | integer | `10` | The newest entries of each entity kept past `keep_days`, so no entity's story is empty. Every entry of an entity with an open held change or a dead letter is kept, whatever its age, until it clears. See [History retention](reports-and-data-quality.md#history-retention). |
| `cache_mb` | integer | `256` | MiB of memory caching the store's decompressed blocks. At most `32768`. Takes effect when syncd restarts. See [Availability and sizing](availability-and-sizing.md). |
| `backup` | map | unset | Scheduled backups of the running engine; [below](#scheduled-backups-enginebackup). |
| `ha` | map | unset | An active/passive pair; [below](#high-availability-engineha). Host-only. |

```yaml
engine:
  data_dir: /var/lib/syncd/engine
  shards: 0
  scrape_interval_secs: 3600
  reconcile_interval_secs: 86400
  history: { keep_days: 30, keep_per_entity: 100, min_per_entity: 10 }
  cache_mb: 256
```

### Scheduled backups (`engine.backup`)

The engine takes a consistent checkpoint of its store while it runs, archives it with its
configuration and password key, and writes it to a directory or to S3. `syncd backup`
takes one on request. See [Backups](operator-guide.md#backups).

| Key | Type | Default | What |
|---|---|---|---|
| `to` | text | required | A directory, or `s3://bucket/prefix`. |
| `every_secs` | integer | `86400` | Seconds between backups. At least `60`. |
| `keep` | integer | `14` | Backups kept at `to`; older ones are removed after each success. At least `1`. |
| `endpoint` | URL | unset | An S3-compatible endpoint (MinIO): `https://minio.example.gov:9000`. Path-style addressing is used with it. |
| `region` | text | `AWS_REGION`, else `us-east-1` | The bucket's region. |
| `sse` | text | unset | Server-side encryption: `AES256`, `aws:kms` or `aws:kms:<key id>`. |
| `ca_file` | path | public roots | PEM CA certificates for an endpoint without a public certificate. |

S3 credentials come from the engine's environment, never from the configuration; see the
`AWS_*` [variables](#engine-environment-variables-and-options). The status and the
metrics say when the last backup succeeded, and the `SyncdBackupStale` alert fires when
none has for two intervals. A restore needs the same master key or passphrase the engine
had.

```yaml
engine:
  backup:
    to: s3://idm-backups/syncd/prod
    every_secs: 21600
    keep: 28
    sse: aws:kms
```

### High availability (`engine.ha`)

Two engines run the same configuration and share a lease. The holder runs the sources and
the exports. The other serves its control API, keeps a warm copy of the store from the
active engine's snapshots, and takes over when the lease expires. See
[High availability](availability-and-sizing.md#high-availability).

| Key | Type | Default | What |
|---|---|---|---|
| `name` | text | required | The pair's name: the lease's key, the same on both engines. 1 to 64 letters, digits, `.`, `_` or `-`. |
| `member` | text | `SYNCD_HA_MEMBER`, else the host name | This engine's name in the pair. |
| `role` | `auto`, `active`, `passive` | `auto` | `auto` competes for the lease. `passive` never takes it: a standby that only follows. `active` is `auto` by another name. |
| `lease.backend` | `postgres`, `file` | required | Where the lease is. |
| `lease.url` | secret | | `postgres`: the database URL. The console's database works. |
| `lease.tls_verify` | boolean | `false` | `postgres`: check the server's certificate. Without it TLS encrypts only. |
| `lease.ca_file` | path | | `postgres`: PEM CA certificates for `tls_verify`. |
| `lease.path` | path | | `file`: the lease file, on a file system both engines share. |
| `lease.ttl_secs` | integer | `15` | How long the lease holds without a renewal; renewed every third of it. At least `3`. A failover takes at most this, plus the new engine's reconciliation. |
| `advertise` | `host:port` | `SYNCD_HA_ADVERTISE`, else `api.listen` | Where the other engine reaches this one's control API, recorded in the lease. |
| `snapshot_every_secs` | integer | `300` | Seconds between the store snapshots the active engine takes for the passive one. At least `10`. The failover window: what was exported since the last snapshot is found again by the reconciliation after a takeover. |
| `peer.address` | `host:port` | the address in the lease | The other engine's control API, for snapshot pulls. |
| `peer.ca_file`, `peer.cert_file`, `peer.key_file` | path | `api.tls`'s files | The CA the peer's certificate chains to, and this engine's client certificate and key. `cert_file` and `key_file` go together. |

`engine.ha` can also come from `SYNCD_HA_*` variables, for hosts whose configuration file
the console publishes (the Helm chart does this). A file with an `engine.ha` of its own
keeps it.

!!! note "Limits"
    The lease is in PostgreSQL or a file: there is no etcd or Kubernetes Lease backend.
    Each snapshot is a full archive, not a delta. Password changes the old engine queued
    after its last snapshot are not delivered again after a failover. `syncdctl` does not
    set up a pair.

## Control API (`api`)

The console and the `syncd` command line talk to the engine through its control API
(gRPC). The whole section is host-only.

| Key | Type | Default | What |
|---|---|---|---|
| `enabled` | boolean | `true` | Serve the control API. |
| `listen` | `host:port` | `127.0.0.1:7443` | Where it listens. Plain text is only allowed on a loopback address; any other address needs `tls`. The engines of an HA pair reach each other here. |
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
    The packaged configuration has `control_socket: /run/syncd/control.sock` commented
    out. The `syncd` unit creates `/run/syncd` for it (`RuntimeDirectory=syncd`, mode
    `0750`) and removes it when the engine stops, so uncommenting the line and restarting
    the engine is enough. The rest of the file system stays read-only to the engine apart
    from `/var/lib/syncd/engine` and `/etc/syncd/engine` (`ProtectSystem=strict`); a socket
    anywhere else needs that directory given to the service with `systemctl edit syncd`.
    The console can also register an engine at its socket, as `unix:/run/syncd/control.sock`,
    when both run on the same host.

## Attribute hints (`attributes`)

| Key | Type | Default | What |
|---|---|---|---|
| `multi_valued` | list of names | empty | Attributes diffed as sets on export (LDAP add and delete of values) instead of replaced, and always read as a list in rules: one value is a one-element list, an absent attribute an empty list. Matched ignoring case. `proxyAddresses` is added by itself when a publication's `exchange.proxy_addresses` keeps existing values. |
| `sensitive` | list of names | empty | Attributes whose values the console's object explorer, previews and simulations hide unless the user may reveal them (each reveal is audited). Names of view attributes, source fields or target attributes. What derives from them is hidden too: computed attributes that read them, source fields that flow into them, and target attributes built from them. DNs are not masked, so do not build DNs from sensitive attributes. List an initial password read from a source here. |

```yaml
attributes:
  multi_valued: [memberOf, mail, member]
  sensitive: [birthDate, nationalId, initialPassword]
```

Who may reveal is set in the console by `CONSOLE_REVEAL_ROLES`.

## Packages (`packages`)

Rule packages hold functions, lookup tables and their tests, versioned and shared between
configurations. Rules call `<namespace>.<function>(...)`, the namespace being the name
with `-` as `_`. See [Rule packages](rules.md#rule-packages) for the file format and how
calls work.

| Key | Type | Default | What |
|---|---|---|---|
| `name` | text | required | Letters, digits and `-` (or `_`), starting with a letter. A namespace is used once: `a-b` and `a_b` clash. `e`, `source` and `group` are reserved. |
| `version` | text | required | The version the configuration expects (semantic versioning, `1.2.0`). A file read from `from` must declare the same. One version of each package per configuration. |
| `from` | path | unset | A package file, relative to the configuration file's directory. |
| `pin` | text | unset | `sha256:` and the file's 64 hex digits: a file with other content is refused. Only with `from`. |
| `description`, `functions`, `lookups`, `tests`, `requires` | | | The package written inline, as in a package file, instead of `from`. |

```yaml
packages:
  - name: gov-naming
    version: 1.2.0
    from: packages/gov-naming.yaml
    pin: "sha256:3f2a..."
```

## Connectors (`connectors`)

A connector is one system syncd reads, writes, or both. Each has a type, connection
settings that match the type, the objects it reads, and an optional deletion threshold.

| Key | Type | Default | What |
|---|---|---|---|
| `name` | text | required | Unique; views and publications refer to it. |
| `type` | enum | required | `postgres`, `mysql` (MySQL 8 and MariaDB 10.5+), `sql_server` (SQL Server 2016+ and Azure SQL Managed Instance), `mongodb`, `ldap`, `active_directory`, `debezium`, `jdbc`, `csv` (a delimited file), `scim` (a SCIM 2.0 application, as a target), `entra` (Microsoft Entra ID through Microsoft Graph). |
| `connection` | map | required | The settings for the type, below. Settings of another type are refused. |
| `objects` | list | empty | The object types read from it. A connector used only as a target needs none; a `scim` connector takes none. |
| `deletion_threshold` | map | unset | Safety limit for deletes detected in one import run: `percent` and/or `count`. A run that deletes more than either is held until an operator approves it. |

```yaml
deletion_threshold:
  percent: 5      # more than 5% of the source's records
  count: 500      # or more than 500 records
```

Publication targets are `ldap`, `active_directory`, `scim` and `entra` connectors. See
[Connectors](connectors.md) for what each does.

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
| `starttls` | boolean | `false` | Upgrade an `ldap://` connection to TLS. An Active Directory target that takes passwords needs `ldaps://` or `starttls`. |
| `max_inflight` | integer | `32` | Operations in flight per connection: the ceiling of the writer's adaptive limit. 1 to 256. |
| `operation_timeout_secs` | integer | `60` | Seconds a bind, search or write may take before it fails and the connection counts as down. 1 to 3600. |
| `idle_timeout_secs` | integer | `900` | Seconds a persistent search (syncrepl) may stay silent before the session is reconnected. A quiet session is probed every minute. Must be at least `operation_timeout_secs`. |
| `profile` | `generic`, `openldap`, `389ds` (alias `rhds`), `unboundid` (aliases `ping`, `pingdirectory`) | `generic` | `ldap` connectors only: the directory server, for what LDAP leaves to each server, such as how `deprovision: disable` disables an account. Refused on `active_directory` connectors. See [Target profiles](connectors.md#target-profiles). |

There is no `ca_file`: the server's certificate is checked against the system's trust
store.

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

### CSV files (`csv`)

A delimited file read in full; each object is one file and its `anchor` the key column.
See [CSV and flat files](connectors.md#csv-and-flat-files).

| Key | Type | Default | What |
|---|---|---|---|
| `path` | path or glob | required | The file, or a glob over its directory (`/in/contractors-*.csv`): the newest match is read. An object's `file` replaces it for that object. |
| `delimiter` | text | `,` | One character (`","`, `";"`, `"|"`, `"\t"` for TSV), or `auto` to sniff among those four. |
| `encoding` | `utf-8`, `latin1`, `utf-16` | `utf-8` | `utf-8` strips a byte order mark. `latin1` (aliases `iso-8859-1`, `windows-1252`, `cp1252`). `utf-16` by its byte order mark, little-endian without one. |
| `header` | boolean | `true` | The first row names the columns. Without it, `columns` names them in order. |
| `columns` | list | empty | Names, or `{ name, type }` with `string`, `int`, `bool`, `date`, `timestamp` or `multi:<separator>`. With `header`, the names must match the header's. |
| `quote` | text | `"` | The quote character. |
| `watch` | boolean | `false` | Read the file again two seconds after it stops changing, on top of the object's `interval_secs` or `schedule`. |
| `done_file` | text | unset | Read only when this file exists (`.done` for `<file>.done`, or an absolute path), and delete it after the read. |
| `max_bytes` | integer | `1073741824` (1 GiB) | A larger file is refused. |
| `on_error` | `fail`, `skip` | `fail` | What a bad row does: fail the whole read, or skip the row, counted and reported. |

### SCIM (`scim`)

A SCIM 2.0 application, as a publication target. See [SCIM targets](connectors.md#scim-targets).

| Key | Type | Default | What |
|---|---|---|---|
| `base_url` | URL | required | The provider's base, `https://app.example.com/scim/v2`. `https://` unless on loopback. |
| `auth.token` | secret | | A bearer token. |
| `auth.basic` | map | | `{ username, password }`; `password` is a secret. |
| `auth.oauth2` | map | | OAuth 2.0 client credentials: `token_url` (`https://` unless on loopback), `client_id`, `client_secret` (a secret), optional `scope`. |
| `tls.ca_file` | path | unset | PEM CA certificates trusted besides the Mozilla root set. |
| `rate_limit` | integer | `20` | Requests per second at most. At least `1`. |
| `page_size` | integer | `100` | Resources per page when listing. 1 to 1000. |
| `timeout_secs` | integer | `30` | Seconds one request may take. 1 to 3600. |

`auth` takes exactly one of `token`, `basic` and `oauth2`.

### Entra ID (`entra`)

Microsoft Entra ID through Microsoft Graph: a target for users, groups, licences and app
roles, and a source of users and groups. See [Microsoft Entra ID](connectors.md#microsoft-entra-id).

| Key | Type | Default | What |
|---|---|---|---|
| `tenant` | text | required | The tenant's domain (`contoso.onmicrosoft.com`) or id. |
| `auth.client_id` | text | required | The application registered in the tenant. |
| `auth.client_secret` | secret | required | Its client secret. A certificate is not supported. |
| `cloud` | `global`, `usgov`, `usgovdod`, `china` | `global` | The national cloud, which picks the Graph and sign-in hosts. |
| `endpoint` | URL | the cloud's (`https://graph.microsoft.com/v1.0`) | Another Graph endpoint, for a test double. |
| `token_endpoint` | URL | the cloud's | Another token endpoint, for a test double. |
| `tls.ca_file` | path | unset | PEM CA certificates trusted besides the Mozilla root set. |
| `timeout_secs` | integer | `30` | Seconds one request may take. 1 to 3600. |

### Objects

Each entry of `objects` is one object type the connector reads. The keys that apply depend
on the connector type:

| Key | Applies to | Default | What |
|---|---|---|---|
| `type` | all | required | The object type name views refer to (`person`, `group`). For `entra`, `user` or `group`. |
| `anchor` | all | required | The source attribute or column holding the immutable identifier. For `csv`, the key column; for `entra`, `id`. |
| `mode` | all | `scrape` | `scrape`, `delta` or `stream`; see [Read modes](#read-modes). |
| `table` | databases, Debezium, JDBC | | A table or view (`dbo.people`, `HR.EMPLOYEE`). For Debezium, the captured table whose events are this object's. |
| `query` | databases, JDBC | | A SQL query instead of a table; its columns become attributes. |
| `cdc` | `postgres`, `mysql`, `sql_server` | | Change capture for `mode: stream`; see [Change capture](#change-capture-cdc). |
| `base` | LDAP, AD | | Search base DN. |
| `filter` | LDAP, AD, MongoDB | `(objectClass=*)` for LDAP | LDAP: a search filter. MongoDB: a query document in extended JSON (`'{"status": "active"}'`). |
| `scope` | LDAP, AD | `sub` | `base`, `one` or `sub`. |
| `attributes` | LDAP, AD, MongoDB, Entra | empty (all user attributes, or whole documents) | Attributes or fields to read. For `entra`, the properties selected. |
| `collection` | MongoDB | | The collection to read. |
| `file` | `csv` | the connection's `path` | The file or glob this object reads, so one connector can read several files of one inbox. |
| `changelog` | `ldap` with `mode: stream` | | Follow the directory's change log instead of syncrepl; see [LDAP change logs](#ldap-change-logs-changelog). |
| `poll_interval_secs` | streams that poll | `10` (DirSync), `5` (change logs), `30` (Entra delta queries) | Seconds between polls. Only for Active Directory streams, `changelog` streams and `entra` objects. |
| `interval_secs` | scrape, delta | `engine.scrape_interval_secs` | Seconds between reads (delta: between reads of what changed). Not for streams. |
| `schedule` | scrape, delta | unset | When full reads start, as a [cron schedule](#schedules). Replaces `interval_secs` (scrape) or `full_interval_secs` (delta); setting both is refused. Not for streams. |
| `schedule_zone` | with `schedule` | `UTC` | The schedule's time zone, an IANA name (`Europe/Paris`). |
| `full_interval_secs` | delta | one day | Seconds between full reads, which also find deletes. |
| `container_check_secs` | `ldap`, `active_directory` with `mode: stream` or `delta` | `60` | Seconds between checks for containers renamed under `base`, whose entries are then read again; `0` turns the check off. Only objects with `scope: sub` are checked. See [Containers renamed in a source](connectors.md#containers-renamed-in-a-source). |
| `watermark` | delta | `modifyTimestamp` (LDAP), `uSNChanged` (AD); required for databases and MongoDB | What marks a change: a column or field that grows with every change, or a directory attribute. |
| `binary` | all | empty | Attributes or columns that hold binary data beyond those the connector recognises by type: LDAP attributes the built-in list misses, and Debezium columns, which arrive as base64 text. |
| `timestamps` | all | empty | Attributes or columns to send as timestamps instead of text. Listed values are read as rules' `timestamp()` reads text, so rules get a timestamp and LDAP targets GeneralizedTime. A value that does not read as one stays text. |

An object sets exactly one of `table`, `query` (databases, JDBC, Debezium `table` only),
`base` (LDAP and Active Directory) or `collection` (MongoDB), and only the options that
belong to it. A `csv` object sets none of them (or `file`); an `entra` object sets only
`type`, `anchor: id`, `mode: stream`, `attributes` and `poll_interval_secs`.

#### Read modes

| Mode | What it does | Allowed for |
|---|---|---|
| `scrape` | A full read every `interval_secs` (or on `schedule`), diffed against what is staged. Records not seen are deleted. | Every type except `debezium` and `entra`. The only mode for `csv`. |
| `delta` | Every `interval_secs`, reads what changed since the last read by the `watermark`; every `full_interval_secs` (or on `schedule`) reads everything, which finds deletes. | Every type except `debezium`, `csv` and `entra`. |
| `stream` | Follows changes as they happen, from a checkpoint committed with the changes. | PostgreSQL, MySQL, SQL Server (with `cdc`), MongoDB, LDAP (syncrepl or `changelog`), Active Directory (DirSync), Debezium (required), Entra ID (delta queries, required). Not `jdbc` or `csv`. |

Anchor rules for streams, checked by validation:

- an `ldap` object streamed with syncrepl needs `anchor: entryUUID`;
- an `active_directory` object streamed with DirSync needs `anchor: objectGUID`;
- a MongoDB object streamed with a change stream needs `anchor: _id`;
- an `entra` object needs `anchor: id`.

How each type reads, and what it needs on the source side, is in
[Connectors](connectors.md).

#### Change capture (`cdc`)

| Key | Database | Default | What |
|---|---|---|---|
| `slot` | PostgreSQL (required) | | The logical replication slot, created if missing. |
| `publication` | PostgreSQL (required) | | The PostgreSQL publication, created if missing. Tables the object follows that an existing publication lacks are added to it, or the stream fails naming them. |
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
| `correlation` | map | unset | How records that joined no entity are scored for suggestions, and when waiting ones are alerted on; [below](#correlation-assistance-correlation) and [the guide](reports-and-data-quality.md#correlation-assistance). |
| `quality` | list | empty | Data-quality checks run on every entity; [below](#data-quality-checks-quality) and [the guide](reports-and-data-quality.md#data-quality-checks). |

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
| `permanent` | map | unset | `{ key: <attribute> }`: the values belong to that key for good, are never given to another entity, and come back to an entity that returns with the same key. Needs `mode: assign_once`; the key is another attribute of the view. See [Permanent values](rules.md#permanent-values). |
| `tests` | list | empty | Test cases: `input`, optional `lookups`, `expect`; see [Test cases](rules.md#test-cases). |

Computed attributes are evaluated in dependency order; a cycle is refused.

### Lookup tables

```yaml
lookups:
  dept_codes:
    Engineering: ENG
    Sales: SLS
```

Rules read them with `lookup("dept_codes", e.department, "GEN")`, and a package's tables
as `lookup("gov_naming.titles", ...)`. See [Lookup tables](rules.md#lookup-tables).

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

### Correlation assistance (`correlation`)

Records that joined no entity get scored suggestions, which an operator accepts in bulk.
Nothing joins without an operator. See
[Correlation assistance](reports-and-data-quality.md#correlation-assistance).

| Key | Type | Default | What |
|---|---|---|---|
| `suggest` | list | empty | Conditions scored beside the join rules' own, each `{ entity, source, weight, similar }`: an entity attribute, CEL over `source` giving the record's value, a weight above 0 and at most 10 (default `1`), and whether a value within a Jaro-Winkler similarity of 0.92 also counts, at 0.7. A hint on an attribute a join rule matches replaces that condition's weight and similarity. |
| `accept_at` | number | `0.95` | The score from which `syncd correlation accept --above` and the console's **Accept all at or above** take a suggestion by default. Above 0, at most 1. |
| `unmatched_alert_after` | span | unset | Unjoined records older than this are counted as ageing: in the status, the metric, the `SyncdUnmatchedRecordsAgeing` alert and the `correlation.unmatched-ageing` event. |

```yaml
correlation:
  suggest:
    - { entity: mail, source: lower(source.mail), weight: 2 }
    - { entity: sn, source: source.lastName, similar: true }
  unmatched_alert_after: 3d
```

### Data-quality checks (`quality`)

Checks run as each entity is computed, so they run again exactly when its inputs change.
Failures are kept per entity. See
[Data-quality checks](reports-and-data-quality.md#data-quality-checks).

| Key | Type | Default | What |
|---|---|---|---|
| `name` | text | required | Letters, digits, `-` and `_`; unique within the view. |
| `check` | CEL over `e` | | A condition that holds for a good entity. An error evaluating it counts as a failure. |
| `unique` | CEL over `e` | | Instead of `check`: a value no two entities of the view may share, compared as text ignoring case. Entities without a value are not compared. |
| `severity` | `info`, `warning`, `error` | `warning` | |
| `on_error` | `report`, `quarantine`, `{ skip_publication: [names] }` | `report` | `report` only records the failure. `quarantine` holds the entity's exports until the check passes. `skip_publication` keeps the entity out of those publications of this view while it fails, so **their leaver policy applies to an entry already in the target**. A `unique` check can only report. |
| `message` | text | the check's name | What a failure says. Shown in `syncd quality` and the console, so keep attribute values out of it. |

```yaml
quality:
  - name: mail-format
    check: e.mail.matches("^[^@]+@[^@]+$")
    severity: error
    message: mail is not an address
  - name: unique-mail
    unique: e.mail
    severity: error
```

## Publications (`publications`)

A publication writes a view's entities to a target: an LDAP directory, Active Directory, a
SCIM application or Entra ID.

| Key | Type | Default | What |
|---|---|---|---|
| `name` | text | required | Unique. |
| `view` | text | required | The view to publish. |
| `target` | text | required | An `ldap`, `active_directory`, `scim` or `entra` connector. |
| `filter` | CEL over `e` | all entities | Which entities are in scope. Entities leaving scope are deprovisioned. |
| `dn` | template | required for directories | The entry's DN; see [DN template](#dn-template). Not used for SCIM and Entra targets. |
| `object_classes` | list | required for directories | The entry's object classes. Not used for SCIM and Entra targets. |
| `resource` | text | `User` | SCIM and Entra targets: the resource type (`User`, `Group`, or for SCIM one of the provider's resource types). See [below](#scim-and-entra-id-publications). |
| `external_id` | CEL over `e` | | SCIM and Entra targets, required: the value that names the resource, in place of `dn`. |
| `key` | `userPrincipalName`, `employeeId` | `userPrincipalName` | Entra targets: the user property `external_id` gives. |
| `licenses` | map | unset | Entra targets: the Microsoft 365 licences each user holds. |
| `attributes` | map | required | Target attribute to a CEL expression over `e`. For SCIM, SCIM paths; for Entra, Graph property names. |
| `dn_attributes` | map | empty | Values only the DN template uses, not exported: name to CEL over `e`. |
| `references` | map | empty | Target attribute to a reference attribute of the view. Each value is exported as the DN (or resource name) the referenced entity has in this target; values whose entity is not exported there are left out. |
| `deprovision` | see below | `delete` | What happens to an entry whose entity leaves scope or is deleted; see [Deprovisioning](#deprovisioning) and the [staged leaver policy](change-safety.md#staged-leaver-policy). |
| `deletion_threshold` | map | unset | `percent` and/or `count`, as for connectors, for the deletes this publication makes. |
| `modification_threshold` | map | unset | `percent` and/or `count`: a pass that modifies or moves more existing entries has its modifications held. See [Thresholds](change-safety.md#modification-and-add-thresholds). |
| `add_threshold` | map | unset | The same for adds, as a share of the entries exported. See [Thresholds](change-safety.md#modification-and-add-thresholds). |
| `canary` | map | unset | A few entities first, the rest after a soak. See [Canary publishing](change-safety.md#canary-publishing). |
| `change_window` | map | unset | When changes may be applied; held outside it. See [Change windows](change-safety.md#change-windows). |
| `mode` | `apply`, `report` | `apply` | `report` plans every pass and writes nothing. See [Report mode](change-safety.md#report-mode). |
| `create_containers` | boolean | `false` | Create the missing parents of an entry, as many levels as are missing, instead of failing the export. Directories only. |
| `remove_empty_containers` | boolean | `false` | Delete a container this publication created once the last entry has left it, and its parents in turn when syncd created them too. Needs `create_containers`. |
| `groups` | list | empty | Computed groups; below. |
| `group_policy` | map | unset | Defaults for the computed groups' lifecycle; see [Group lifecycle](#group-lifecycle) and [the guide](change-safety.md#group-lifecycle). |
| `adopt` | map | unset | Take over entries already in the target; below. Directories only: SCIM and Entra adopt by themselves. |
| `drift` | map | unset | Read the target back and act on changes made there; below. |
| `writeback` | map | unset | After a target outage, write changes made in the target back to an LDAP source; below. |
| `passwords` | map | unset | Passwords this publication delivers: captured ones and the initial password of new entries; see [Publication passwords](#publication-passwords). |
| `exchange`, `account_control`, `home`, `user_principal_name`, `sam_account_name` | map | unset | Active Directory conveniences; see [below](#active-directory-conveniences). |

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

syncd records every container it creates, per target, whether or not
`remove_empty_containers` is set. With it set, a container is deleted once an entry that
syncd moves or deletes leaves it empty, deepest first and after the rest of the batch
is written, so a container another entry moves into is kept. A container syncd did not
create, or one that still holds anything (entries syncd does not manage, or those of
other publications), is never deleted. A failure to delete one is logged and fails no
export. It is off by default because an empty OU can still matter: delegated rights on
it, or Group Policy linked to it on Active Directory.

- Containers created before 0.3.0, or by an export that then failed, are not recorded, so
  they are never removed.
- Containers left empty by someone else moving or deleting entries in the target stay.
- `syncd simulate` does not show container removals.

### Deprovisioning

| Value | What happens to an entry whose entity leaves scope or is deleted |
|---|---|
| `delete` | The entry is deleted. |
| `{ move_to: <DN> }` | The entry is moved under this fixed container. |
| `disown` | The entry is left in place and no longer managed. |
| `disable` | The account is disabled and the entry kept and managed. An entity that comes back into scope is enabled again. |
| `{ disable_and_move_to: <DN> }` | The account is disabled and moved under this fixed container. |
| `{ disable_after, strip_groups_after, move_to, delete_after }` | A staged policy, below. |

The containers must be fixed DNs (no placeholders). `disable`, `disable_and_move_to` and a
staged `disable_after` need a target that knows how to disable an account: an
`active_directory` connector, an `ldap` connector with a `profile`, a `scim` target
(`active: false`) or an `entra` target (`accountEnabled: false`). See
[Target profiles](connectors.md#target-profiles). SCIM and Entra targets cannot move
entries, so `move_to` and `disable_and_move_to` are refused for them.

```yaml
deprovision: { disable_and_move_to: "OU=Disabled,OU=Synced,DC=corp,DC=example,DC=com" }
```

#### Staged leaver policy

Each stage is a [span](#spans-of-time) after the entity left scope or was deleted. The
clocks are kept in the engine's store, never written to the target. An entity that comes
back cancels what is still to come and is moved back, enabled and put back in its groups.
See [Staged leaver policy](change-safety.md#staged-leaver-policy).

| Key | What |
|---|---|
| `disable_after` | Disable the account, the way the target does it. |
| `strip_groups_after` | Take the entry out of every group syncd manages: the publication's computed groups, and the reference attributes of other publications that list it. |
| `move_to` | Move the entry under this fixed container, with the first of the two stages above, or at once when neither is set. |
| `delete_after` | Delete the entry. The deletion threshold still applies. Must not come before `disable_after` or `strip_groups_after`. |

At least one stage is required.

```yaml
deprovision:
  disable_after: 0d
  strip_groups_after: 7d
  move_to: "OU=Leavers,DC=corp,DC=example,DC=com"
  delete_after: 90d
```

An entry recreated under a new entity is not re-enabled, and memberships in groups syncd
does not manage are not stripped.

### Change safety

Changes that wait are **held**. A held change is never replayed: when its hold ends, the
entity is planned again from its current state. See
[Change safety and lifecycle](change-safety.md) for how holds end and how to approve them.

| Key | Type | Default | What |
|---|---|---|---|
| `change_window.allow` | list | empty (always open) | [Change windows](change-safety.md#change-windows): when changes may be applied: entries such as `"Mon-Fri 18:00-06:00"`, `"Sat"`, `"22:00-04:00"`. Days are English names or their first three letters; a range of days may wrap the week. Times are `HH:MM` or `HH:MM:SS`; `24:00` ends a day; a time range past midnight runs into the next day. |
| `change_window.timezone` | IANA name | `UTC` | The time zone of `allow`. The window keeps its local times across clock changes. |
| `change_window.immediate` | list | `[disable, password]` | Kinds of change applied at once even outside the window: `add`, `modify`, `move`, `delete`, `disable`, `strip_groups`, `password`. Captured passwords are never held, whatever this says. |
| `change_window.max_delay` | span | unset | A change held longer than this is overdue. |
| `change_window.on_overdue` | `apply`, `hold` | `apply` | An overdue change is applied anyway, or held for `syncd approve`. `hold` needs `max_delay`. |
| `modification_threshold` | `{ percent, count }` | unset | [Thresholds](change-safety.md#modification-and-add-thresholds): a pass (or a stream of small batches) that modifies or moves more existing entries than this has its modifications held until `syncd approve` or a pass under the threshold. `percent` is 0 to 100. |
| `add_threshold` | `{ percent, count }` | unset | The same for adds, as a share of the entries exported. |
| `canary.count`, `canary.percent` | integer, number | | [Canary publishing](change-safety.md#canary-publishing): how many entities go first, in order of entity id: a count, or a share of the publication's exported entries (whichever is larger when both are set). At least one is required. |
| `canary.soak` | span | required | How long the first entities are watched (dead letters, drift) before the rest follow. Longer than `0s`. If any goes wrong, the rest becomes `canary_failed` and needs an approval. |
| `canary.on` | `{ percent, count }` | every pass | Only a pass that changes more than this gets a canary. |
| `mode` | `apply`, `report` | `apply` | [Report mode](change-safety.md#report-mode): the publication is planned on every pass and nothing is written. Each plan is recorded as a would-do; switching to `apply` makes the next reconciliation apply them. |

```yaml
change_window:
  timezone: Europe/London
  allow: ["Mon-Fri 18:00-06:00", "Sat", "Sun"]
  max_delay: 2d
modification_threshold: { percent: 10, count: 500 }
add_threshold: { count: 200 }
canary: { count: 10, soak: 30m, on: { count: 100 } }
```

Held changes are counted in memory per publication, so report mode over a very large
view costs memory in proportion. A canary's drift check follows the publication's
`drift` policy, reverts included.

### Computed groups (`groups`)

A group entry kept in the target with every exported entry of the publication that matches
a predicate as a member.

| Key | Type | Default | What |
|---|---|---|---|
| `name` | text | required | Unique within the publication. |
| `filter` | CEL over `e` | required | Who is a member: `e.department == "Engineering"`. |
| `dn` | DN | required | The group entry's fixed DN (no placeholders). For SCIM and Entra targets, `displayName=<name>`. |
| `object_classes` | list | `[groupOfNames]` | |
| `member_attribute` | text | `member` | The attribute holding member DNs. `members` for SCIM and Entra targets. |
| `attributes` | map | empty | Other attributes of the group entry, with literal values. Must not set the member attribute, `objectClass` or the owner attribute. |
| `when_empty` | `delete`, `keep` | `delete` | `delete` removes an empty group and adds it again with its first member (`groupOfNames` must have a member). `keep` keeps it with no members, for classes that allow that, such as Active Directory's `group`. |
| `manual_members` | `revert`, `keep`, `report` | `group_policy`'s, else `revert` | A member added in the target that the filter does not give: removed, kept (and counted), or kept and counted as drift. |
| `owner` | DN | unset | The group's owner, written with the members. |
| `owner_from` | CEL over `group` | unset | Instead of `owner`: an expression over `group.name`, `group.dn` and `group.members`. No lookup tables. |
| `owner_attribute` | text | `group_policy`'s, else `owner` | The attribute the owner is written to. Not the member attribute or `objectClass`. |
| `expires` | date | unset | When the group expires: `2027-01-01` (midnight UTC) or an RFC 3339 timestamp. |
| `expires_after` | span | `group_policy`'s | Instead of `expires`: this long after syncd created the group, or after its last review. |
| `on_expiry` | `report`, `empty`, `delete` | `group_policy`'s, else `report` | At expiry: report it, remove every member, or delete the entry. Needs an expiry. `delete` needs a `deprovision` that deletes (`delete`, or a staged `delete_after`) and is subject to the deletion threshold. |
| `review_every` | span | `group_policy`'s, else never | A review is due this long after creation or the last review (`syncd group review`, the console's **Review** button). |

```yaml
groups:
  - name: engineering
    filter: e.department == "Engineering"
    dn: cn=engineering,ou=Groups,dc=example,dc=com
    attributes: { description: Everyone in engineering }
    owner: uid=jlee,ou=People,dc=example,dc=com
    expires_after: 365d
    on_expiry: report
```

Groups whose members come from a group view in the source are published as an ordinary
publication of that view, with the member attribute as a reference.

#### Group lifecycle

`group_policy` sets defaults for every computed group of the publication; a group's own
setting wins. See [Group lifecycle](change-safety.md#group-lifecycle).

| Key | What |
|---|---|
| `manual_members` | `revert`, `keep` or `report`. |
| `owner_attribute` | The attribute owners are written to. |
| `expires_after` | Span after creation or the last review. |
| `on_expiry` | `report`, `empty` or `delete`. |
| `review_every` | Span between reviews. |

`review_every` has no timer and sends no mail: a due review shows in the status, the
metric and the console only. Reviews are per group. An `on_expiry: empty` group whose
object class needs a member (`groupOfNames`) dead-letters.

### Adopting existing entries (`adopt`)

Before adding the entry of an entity it has not exported yet, the writer looks under `base`
for an entry whose `match` attributes have the values the entity exports. One found is
moved to the entity's DN if it is elsewhere, given the publication's missing object
classes and updated, and is managed like any exported entry from then on. More than one
found fails the export (a dead letter naming them). Attributes the publication does not
map are left as they are. Not for SCIM and Entra targets, which adopt a resource that
already exists by themselves.

| Key | Type | What |
|---|---|---|
| `match` | list, required | Exported target attributes that identify an existing entry (`employeeNumber`, `sAMAccountName`); all must match. Attributes the Active Directory conveniences write count. |
| `base` | DN, required | Where existing entries are looked for (subtree). A fixed DN. |
| `filter` | LDAP filter | Candidates must also match it, such as `(objectClass=person)`. |

### Drift detection (`drift`)

The engine reads each exported entry back from the target and compares the attributes it
manages with what it last wrote.

| Key | Type | Default | What |
|---|---|---|---|
| `interval_secs` | integer | `engine.scrape_interval_secs` | Seconds between checks. `syncd check-drift` and the console check on demand. |
| `default` | `flag`, `revert`, `accept` | `flag` | The policy for attributes not listed, and for missing entries (where `accept` counts as `flag`). |
| `attributes` | map | empty | Exported target attribute to its policy. Attributes the Active Directory conveniences write count. |

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

The target must have a change log, so write-back is refused for Active Directory, SCIM and
Entra targets. See [Connectors](connectors.md#write-back-after-a-target-outage).

```yaml
writeback:
  source: corp_ldap
  attributes:
    telephoneNumber: telephoneNumber
    mobile: mobile
  changelog: { format: accesslog }
```

### Publication passwords

A publication's `passwords` says which passwords it delivers to its entries' accounts. See
[A publication's `passwords` block](password-sync.md#a-publications-passwords-block) and
[Initial passwords](password-sync.md#initial-passwords), and for Active Directory accounts
created with a password, [Initial passwords on Active Directory](connectors.md#initial-passwords).

| Key | Type | Default | What |
|---|---|---|---|
| `from` | list | empty | Connectors of the view whose captured password changes this target receives. Never the publication's own target. May be empty when only `initial` is wanted. |
| `write` | `password_modify`, `replace` | `password_modify` | How a directory other than Active Directory takes a password: the RFC 3062 Password Modify operation, or a `userPassword` replace. Active Directory always takes `unicodePwd`. |
| `initial.from` | CEL over `e` | | The password a new entry is created with, read from the entity. An entity without a value is created without one. |
| `initial.generate` | `{ length, classes }` | | Instead of `from`: a random password, `length` 8 to 256 (default `16`), with at least one character of each of the first `classes` of lower case, upper case, digits and other characters (1 to 4, default `4`). |
| `initial.must_change` | boolean | `false` | Active Directory only: `pwdLastSet` 0, so the person changes the password at the next logon. |

`passwords` needs `from` or `initial`, and `initial` exactly one of `from` and `generate`.
On an Active Directory target the connection must be `ldaps://` or use `starttls`; a
`passwords` block over plain `ldap://` is refused when the configuration is validated.

```yaml
passwords:
  from: [corp_ad]
  initial:
    generate: { length: 16, classes: 4 }
    must_change: true
```

### Active Directory conveniences

Publications to an `active_directory` target can say what they mean, and the engine writes
the attributes. Each block stands for the target attributes it names; mapping the same
attribute in `attributes` too is refused. See
[Active Directory conveniences](connectors.md#active-directory-conveniences).

| Key | Writes |
|---|---|
| `exchange.proxy_addresses` | `proxyAddresses` from `{ primary, aliases, keep_existing }`: CEL for the primary address and the aliases; one `SMTP:` primary and `smtp:` aliases. With `keep_existing` (default `true`) the attribute is diffed as a set, so addresses other tools put there stay. |
| `exchange.mail_nickname`, `exchange.target_address` | `mailNickname`, `targetAddress`, from CEL. |
| `account_control.flags` | `userAccountControl`: a list of flag names (`[NORMAL_ACCOUNT, DONT_EXPIRE_PASSWORD]`) or CEL giving an int. `NORMAL_ACCOUNT` when unset. |
| `account_control.password_never_expires` | CEL giving a bool: sets or clears `DONT_EXPIRE_PASSWORD`. |
| `account_control.must_change_password` | CEL giving a bool: `pwdLastSet` `0` when true, `-1` when false. |
| `home.directory`, `home.profile_path`, `home.script_path` | `homeDirectory`, `profilePath`, `scriptPath`, from CEL. No share is created. |
| `home.drive` | `homeDrive`: a drive letter and a colon (`H:`), the same for everyone. |
| `user_principal_name` | `userPrincipalName` from `{ from, suffix }`: CEL for the part before the `@`, and a domain of the forest. |
| `sam_account_name` | `sAMAccountName` from `{ from, max_length }`: CEL for the login, cut to `max_length` (at most and by default 20). |

`sam_account_name` has no `on_conflict: unique`: a duplicate dead-letters. Mint the login
once as a computed attribute with `unique()` instead.

### SCIM and Entra ID publications

A SCIM or Entra target names resources, not entries in a tree. See
[SCIM targets](connectors.md#scim-targets) and
[Microsoft Entra ID](connectors.md#microsoft-entra-id).

- `external_id` names the resource and replaces `dn`. `dn`, `object_classes`,
  `create_containers`, a `deprovision` that moves, `writeback` and `adopt` are refused.
- `resource` is `User` or `Group` (SCIM also takes the provider's other resource types).
- Entra: `key` is `userPrincipalName` or `employeeId`. `licenses` takes `assign` (CEL
  giving SKU part numbers, such as `["ENTERPRISEPACK"]`) and `disabled_plans` (service
  plan names turned off in each); it writes `assignedLicenses`, which must not also be in
  `attributes`.
- Computed groups are named `displayName=<name>` and their member attribute is `members`.
- `resource`, `external_id`, `key` and `licenses` are refused on directory targets.

```yaml
- name: contractors_to_entra
  view: contractors
  target: entra
  external_id: e.upn
  attributes:
    displayName: e.cn
    usageLocation: '"US"'
    accountEnabled: e.active
  licenses: { assign: '["ENTERPRISEPACK"]', disabled_plans: [YAMMER_ENTERPRISE] }
  deprovision: disable
```

## Passwords (`passwords`)

The engine's side of password synchronisation. Delivery is on when at least one
publication has `passwords`. See
[The engine's `passwords` block](password-sync.md#the-engines-passwords-block),
[Capture agents](password-sync.md#capture-agents) and
[People changing their own password](password-sync.md#people-changing-their-own-password).

| Key | Type | Default | What |
|---|---|---|---|
| `key_file` | path | `password.key` in the data directory | The engine's password key, created on first start, readable by the engine's user only. Backups carry it. |
| `ttl_secs` | integer | `86400` | How long a captured change may wait, sealed, for its account to be provisioned or its target to answer. Then it is dropped and counted as failed. At least `1`. |
| `ingest.listen` | `host:port` | | Where capture agents enroll and submit changes: a listener of its own, with mutual TLS against the engine's agent CA. Off unless `ingest` is set. Needs a publication that delivers passwords. |
| `ingest.names` | list | required with `ingest` | Host names and addresses agents reach the listener by; its certificate is issued for them. |
| `ingest.agent_cert_days` | integer | `365` | How long an agent's certificate is valid; it enrolls again after that. |
| `self_service.source` | text | | The LDAP or Active Directory source people sign in to on the console's self-service page. A publication must take passwords `from` it. |
| `self_service.object` | text | | Its object type whose entries are the people. |
| `self_service.login` | text | | The attribute people type as their user name (`uid`, `sAMAccountName`, `mail`). |
| `self_service.min_length` | integer | `12` | The shortest new password. At least `8`. |
| `self_service.min_classes` | integer | `3` | How many of lower case, upper case, digits and other characters it needs. At most `4`. |

```yaml
passwords:
  ttl_secs: 86400
  ingest:
    listen: 0.0.0.0:7444
    names: [syncd.corp.example.com]
  self_service:
    source: corp_ldap
    object: person
    login: uid
```

The console's self-service page also needs `CONSOLE_SELF_SERVICE_ENGINE`. There is no
rotation of the password key or of the agent CA.

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
`drift.interval_secs`), not on cron schedules. Change windows use their own format; see
[Change safety](#change-safety).

## Validation rules

`syncd validate` and the console report every problem at once. Beyond the per-key rules
above, a configuration is refused when:

- `version` is not `1`;
- `api.listen` is not an address and port, or is not on loopback and `tls` is not set;
- `engine.reconcile_interval_secs` is `0` or `engine.cache_mb` is above 32768;
- `engine.backup` or `engine.ha` has a value out of range (an interval, a `to` that is not
  a directory or `s3://` URL, a lease without its `url` or `path`);
- a connector's connection settings do not match its type, or an object's source
  (`table`, `query`, `base`, `collection`, `file`) does not fit the connector type;
- a `csv` connector has no object, a typed column has an unknown type, an object's anchor
  is not one of `columns`, a file without a header has no `columns`, or an object is not
  `mode: scrape`;
- a `scim` connector has objects, or its `auth` does not have exactly one way; an `entra`
  connector has no `client_secret`, or an object that is not `user` or `group` with
  `anchor: id` and `mode: stream`; a `base_url`, `token_url` or `endpoint` beyond
  loopback is not `https://`;
- a streamed database `table` or `query` object has no `cdc`, or a streamed `query`
  object has no `cdc.watch`;
- a delta database or MongoDB object has no `watermark`; `watermark` or
  `full_interval_secs` is set on an object that is not `delta`; `interval_secs` is set on
  a stream; an interval is `0`;
- a `debezium` object is not `mode: stream`, or a `jdbc` object is;
- `schedule` is set on a stream, together with the interval it replaces, or
  `schedule_zone` is set without it;
- `container_check_secs` is set on an object that is not an LDAP or Active Directory
  object read with `mode: stream` or `delta`;
- a view has no source that projects, a source neither projects nor joins, a join rule
  mixes `entity`/`source` with `all`, precedence names a connector that is not a source,
  or a reference names an unknown view;
- a computed attribute uses `on_error: default` without `default`, or has `permanent`
  without `mode: assign_once`, or with a key that is not another attribute of the view;
- a quality check has both or neither of `check` and `unique`, a `unique` check does not
  only report, or `skip_publication` names a publication of another view; a correlation
  hint's weight or `accept_at` is out of range;
- a publication names an unknown view or a target that cannot be written to, its DN
  template uses an unknown placeholder, `object_classes` is empty for a directory, a
  group's DN is not fixed, a drift policy names an attribute it does not export,
  `adopt.match` names one it does not export, or `remove_empty_containers` is set without
  `create_containers`;
- a change window does not parse, `on_overdue: hold` has no `max_delay`, a threshold has
  neither `percent` nor `count`, or a canary has no `count` or `percent` or a `soak` of
  `0s`;
- a staged `deprovision` has no stage or a stage after `delete_after`; a group sets both
  `expires` and `expires_after`, or both `owner` and `owner_from`, or `on_expiry: delete`
  with a `deprovision` that never deletes;
- a publication's `passwords` takes them from a connector that is not a source of its
  view, or from its own target, or writes to Active Directory over plain `ldap://`;
  `must_change` is set for a target that is not Active Directory;
- an Active Directory convenience is set on another target, or writes an attribute the
  publication also maps; a flag name is unknown;
- a SCIM or Entra publication has no `external_id`, or has `dn`, `object_classes`,
  containers, a moving `deprovision`, `writeback` or `adopt`;
- a package is listed twice, has a bad version or pin, or a `requires` the listed version
  does not meet;
- every expression is also compiled: syntax and type errors, unknown functions,
  `unique()` outside computed attributes, more than one `unique()` scope in one computed
  attribute, package functions called with the wrong arguments or calling themselves, and
  cycles between computed attributes.

## Engine environment variables and options

The `syncd` command reads these; a flag wins over its variable.

| Variable | Flag | Default | What |
|---|---|---|---|
| `SYNCD_CONFIG` | `-c`, `--config` | `./syncd.yaml`, else `/etc/syncd/engine/engine.yaml` | The configuration file. |
| `SYNCD_ENV_FILE` | `--env-file` | `/etc/syncd/engine.env` with the packaged configuration | Environment file with the secrets the configuration refers to. Variables already set win. |
| `SYNCD_ENVIRONMENT` | `--env` | unset | An environment file (`environment: prod`, `values: {...}`) that fills the configuration's `{{ env.<name> }}` placeholders. The daemon renders the configuration with it at every start; `validate`, `simulate`, `apply-config` and `config diff` render what they read. See [Configuration as code](config-as-code.md). |
| `SYNCD_INITIAL_CONFIG` | `--initial-config` | unset | When the configuration file does not exist yet, create it as a copy of this one. The container image uses it to seed a configuration on the data volume from a read-only file. |
| `SYNCD_DATA_DIR` | `--data-dir` | `engine.data_dir` | Overrides the data directory. |
| `SYNCD_FIPS_MODE` | `--fips` | off | Use the FIPS 140-3 crypto provider; set to `true`. Requires a FIPS build: the standard builds and images refuse it. See [FIPS 140-3 and STIG](fips-and-stig.md). |
| `SYNCD_API` | `--api` | `api.control_socket` if it exists here, else `api.listen` | How commands reach the running engine: `host:port`, or `unix:/path`. |
| `SYNCD_API_CA` | `--api-ca` | `ca.crt` next to `api.tls.client_ca_file`, else that file | CA that signed the API's certificate. |
| `SYNCD_API_CERT` | `--api-cert` | `cli.crt`, else `client.crt`, next to `api.tls.client_ca_file` | Client certificate for mutual TLS. |
| `SYNCD_API_KEY` | `--api-key` | `cli.key` / `client.key` next to it | The client certificate's private key file (not a token). |
| `SYNCD_HA_NAME` | | unset | Sets `engine.ha` from the environment, with this pair name, when the file has none. Needs `SYNCD_HA_LEASE_URL` or `SYNCD_HA_LEASE_PATH`. |
| `SYNCD_HA_LEASE_URL` | | unset | With `SYNCD_HA_NAME`: a PostgreSQL lease at this URL. |
| `SYNCD_HA_LEASE_PATH` | | unset | With `SYNCD_HA_NAME`: a file lease at this path, when there is no URL. |
| `SYNCD_HA_LEASE_TTL_SECS` | | `15` | With `SYNCD_HA_NAME`: `lease.ttl_secs`. |
| `SYNCD_HA_SNAPSHOT_EVERY_SECS` | | `300` | With `SYNCD_HA_NAME`: `snapshot_every_secs`. |
| `SYNCD_HA_MEMBER` | | the host name | This engine's name in the pair, when `engine.ha.member` is not set. |
| `SYNCD_HA_ADVERTISE` | | `api.listen` | Where the other engine reaches this one, when `engine.ha.advertise` is not set. |
| `SYNCD_CONSOLE` | `--console` | unset | `syncd config promote`: the console's address. |
| `SYNCD_CONSOLE_TOKEN_FILE` | `--token-file` | unset | `syncd config promote`: a file holding an API token with `configs:publish`. |
| `SYNCD_AGENT_TOKEN` | `--token` | unset | `syncd password agent enroll`: the one-time token `syncd password agent add` printed. |
| `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_SESSION_TOKEN` | | unset | S3 credentials for backups. |
| `AWS_WEB_IDENTITY_TOKEN_FILE`, `AWS_ROLE_ARN`, `AWS_ROLE_SESSION_NAME` | | unset | S3 credentials from a Kubernetes service account token exchanged with STS (IRSA, EKS Pod Identity). `AWS_ENDPOINT_URL_STS` names another STS endpoint. |
| `AWS_SHARED_CREDENTIALS_FILE`, `AWS_PROFILE` | | `~/.aws/credentials`, `default` | S3 credentials from a credentials file, when none of the above is set. |
| `AWS_REGION`, `AWS_DEFAULT_REGION` | | `us-east-1` | The bucket's region, when `engine.backup.region` is not set. |
| `AWS_ENDPOINT_URL_S3`, `AWS_ENDPOINT_URL` | | AWS | An S3-compatible endpoint, when `engine.backup.endpoint` is not set. |
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
| `CONSOLE_LISTEN` | `--listen` | `127.0.0.1:8080` | Where the console listens. It serves plain HTTP unless `CONSOLE_TLS_CERT` is set. For access from other machines it needs HTTPS: its own, or a TLS reverse proxy in front of it. |
| `CONSOLE_TLS_CERT` | `--tls-cert` | unset | Serve HTTPS with this certificate: a PEM file, the certificate followed by its chain. Needs `CONSOLE_TLS_KEY`. Readable by `syncd-console`. |
| `CONSOLE_TLS_KEY` | `--tls-key` | unset | The certificate's private key, PEM. Needs `CONSOLE_TLS_CERT`. |
| `CONSOLE_DATA_DIR` | `--data-dir` | `./console-data`; the unit sets `/var/lib/syncd/console` | Local files: the master key, the initial admin password, and with Git sync the clone (`git/`). |
| `CONSOLE_UI_DIR` | `--ui-dir` | unset; the unit sets `/opt/syncd/current/share/console-ui` | The built web UI, served at `/`. Without it only the API is served. |
| `CONSOLE_MASTER_KEY` | `--master-key` | read from, or created in, `<data dir>/master.key` | Master key for stored secrets (base64, 32 bytes). Back it up: stored secrets cannot be decrypted without it. The console refuses to start when the key does not open the secrets the database holds, or when `master.key` is missing while the database holds secrets. |
| `CONSOLE_NEW_MASTER_KEY` | `--new-master-key` | off | Set to `true` to start anyway when the master key does not open the stored secrets, creating a new key if there is none. Those secrets are lost: every engine registered with mutual TLS must be registered again (`syncd-console admin register-engine --update`). Only for starting over on purpose; restore `master.key` instead when you can. |
| `CONSOLE_ADMIN_PASSWORD` | `--admin-password` | generated | Initial admin password for a production install. `admin` and empty passwords are refused. |
| `CONSOLE_ADMIN_PASSWORD_FILE` | `--admin-password-file` | unset | A file holding the initial admin password, instead. |
| `CONSOLE_SESSION_IDLE_MINUTES` | `--session-idle-minutes` | `30` | Minutes a session stays valid without activity. |
| `CONSOLE_SESSION_MAX_HOURS` | `--session-max-hours` | `12` | Hours a session stays valid at most. |
| `CONSOLE_REVEAL_ROLES` | `--reveal-roles` | `admin` | Roles that may reveal sensitive attributes (each reveal is audited), comma-separated, or `none`. |
| `CONSOLE_TRUSTED_PROXIES` | `--trusted-proxies` | empty | Reverse proxies whose `X-Forwarded-For` names the client: addresses or CIDR ranges, comma-separated (`127.0.0.1` for nginx on the same host). The client address goes to the audit log and the per-address sign-in limit. Only list proxies you run. |
| `CONSOLE_PUBLIC_URL` | `--public-url` | unset | The console's address as users reach it, through the proxy (`https://syncd.example.com`). Needed for single sign-on; with an `https://` value, the startup warning about plain-HTTP remote access is not shown. |
| `CONSOLE_SELF_SERVICE_ENGINE` | `--self-service-engine` | unset | The registered engine (by name) that changes people's own passwords, for the public self-service page (`/password`). The page is off unless set; the engine needs `passwords.self_service`. See [Password synchronisation](password-sync.md). |
| `SYNC_DEV_MODE` | `--dev` | off | Development mode: `admin`/`admin` is allowed, the password change can be skipped, cookies work over plain HTTP, and the UI shows a warning banner. Never in production. Note the name: `SYNC_`, not `SYNCD_`. |
| `SYNCD_FIPS_MODE` | `--fips` | off | Use the FIPS 140-3 crypto provider; passwords are hashed with PBKDF2 instead of Argon2id. |
| `RUST_LOG` | | `info` | Log level filter. |

Session cookies carry `Secure` outside development mode, so browsers send them over HTTPS,
or to localhost, only. The console warns at startup when it listens beyond loopback over
plain HTTP, with no certificate and no `https://` `CONSOLE_PUBLIC_URL`. See
[Install](install.md) for setting up its own HTTPS, and for the nginx configuration the
packages ship.

With a certificate:

- a certificate or key that does not load refuses the start;
- the two files are checked every minute and read again when they change, so a renewal
  (certbot, cert-manager) takes effect without a restart. A renewal that does not load is
  logged, and the previous certificate kept;
- each TLS handshake has 10 seconds, and a client that stalls holds up no other;
- the console does not run as root, so on a package install it cannot listen on port 443
  itself: use a port such as 8443, or nginx in front.

The settings in the sections below are off, or at safe defaults, until set. Each has a
flag named after it: drop `CONSOLE_` or `ENS_`, lower-case it and use `-` for `_`
(`ENS_AUDIT_FILE` is `--audit-file`, `CONSOLE_SMTP_HOST` is `--smtp-host`). Settings
marked *(hidden)* are left out of `--help`. A setting that cannot work (a file that does
not exist, a hook URL that is not `https://`) refuses the start with a message naming it.

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

### Audit trail

The console keeps the suite's `ens-audit/1` trail in its database and writes it to the
outputs below as well. The settings are the family's `ENS_AUDIT_*`, the same in every
product; [Audit logging](../audit-logging.md#settings) explains them.

| Variable | Default | What |
|---|---|---|
| `ENS_AUDIT_STDOUT` | `true` | One JSON line per event on standard output: the journal, or the pod log. `false` turns it off. |
| `ENS_AUDIT_FILE` | unset | Also append each event to this file (`/var/log/syncd/audit.jsonl`), rotated daily or at `ENS_AUDIT_FILE_MAX_BYTES`. |
| `ENS_AUDIT_FILE_KEEP` | `30` | Days of rotated files to keep. |
| `ENS_AUDIT_FILE_MAX_BYTES` | `104857600` (100 MB) | *(hidden)* The size at which the file is rotated. |
| `ENS_AUDIT_SPLUNK_URL` | unset | Splunk HTTP Event Collector base URL, `https://splunk.example.com:8088`. |
| `ENS_AUDIT_SPLUNK_TOKEN`, `ENS_AUDIT_SPLUNK_TOKEN_FILE` | unset | The HEC token, or a file holding it. |
| `ENS_AUDIT_SPLUNK_INDEX` | the token's default | The index. |
| `ENS_AUDIT_SPLUNK_SOURCETYPE` | `ens:audit` | The sourcetype. |
| `ENS_AUDIT_SPLUNK_INSECURE` | `false` | Skip Splunk's certificate checks. Test systems only. |
| `ENS_AUDIT_SYSLOG_HOST` | unset | Syslog server (RFC 5424, the event JSON as the message). |
| `ENS_AUDIT_SYSLOG_PORT` | `514`, or `6514` for TLS | |
| `ENS_AUDIT_SYSLOG_PROTOCOL` | `tcp` | `udp`, `tcp` or `tls`. |
| `ENS_AUDIT_SYSLOG_FACILITY` | `local0` | |
| `ENS_AUDIT_RETENTION_DAYS` | `0` (keep forever) | Remove events older than this, once every enabled output has delivered them; never the newest. |
| `ENS_AUDIT_DELIVERY_ALERT_SECONDS` | `900` | *(hidden)* How long an output may keep failing before `audit.delivery-failed` is recorded. |

### API tokens

API tokens (`sdt_…`) are made on the **Integrations** page. See
[API tokens](api-reference.md#api-tokens).

| Variable | Default | What |
|---|---|---|
| `CONSOLE_API_TOKENS_ENABLED` | `true` | `false` refuses every token, without deleting any. |
| `CONSOLE_API_MAX_TOKEN_LIFETIME` | `365d` | The longest a token lives (`<n>d`, `<n>h`): the expiry of a token created without one, the most that may be asked for, and the age at which any token stops working. `0` removes the limit. |
| `CONSOLE_API_REQUESTS_PER_MINUTE` | `300` | Requests a token may make per minute; beyond it, `429` with `Retry-After`. `0` turns the limit off. |
| `CONSOLE_API_CREATOR_CACHE_SECONDS` | `60` | *(hidden)* How long a check that the token's creator is still allowed is reused. |

### Webhooks

Signed HTTPS posts in JSON, Slack, Teams or PagerDuty format, added on the
**Integrations** page. Nothing is sent until an administrator adds one. See
[Webhooks](webhooks.md#settings).

| Variable | Default | What |
|---|---|---|
| `CONSOLE_WEBHOOKS_ENABLED` | `true` | `false` queues and posts nothing. |
| `CONSOLE_WEBHOOKS_ALLOWED_HOSTS` | empty (any host the address rules allow) | Hosts a webhook may go to: names, `*.suffix` or CIDR ranges, comma-separated. A host listed here may be on a private network. |
| `CONSOLE_WEBHOOKS_BLOCK_PRIVATE_NETWORKS` | unset | Private addresses (10/8, 172.16/12, 192.168/16, 100.64/10, fc00::/7). Unset: refused for webhooks saved from now on, while a webhook saved earlier keeps its address. `true`: refused for every webhook. `false`: allowed. |
| `CONSOLE_WEBHOOKS_CA_FILE` | unset | PEM CA certificates receivers may use besides the public roots. |
| `CONSOLE_WEBHOOKS_ALLOW_LOOPBACK` | `false` | *(hidden)* Let webhooks go to loopback addresses. Test systems only. |
| `CONSOLE_WEBHOOKS_POLL_SECONDS` | `30` | *(hidden)* How often the sender looks for due deliveries. |
| `CONSOLE_WEBHOOKS_WATCH_SECONDS` | `10` | *(hidden)* How often the engines' status is read for state-change events. |
| `CONSOLE_WEBHOOKS_CONNECT_TIMEOUT_SECONDS` | `5` | *(hidden)* |
| `CONSOLE_WEBHOOKS_READ_TIMEOUT_SECONDS` | `10` | *(hidden)* |
| `CONSOLE_WEBHOOKS_DELIVERY_TIMEOUT_SECONDS` | `30` | *(hidden)* All of one try. |
| `CONSOLE_WEBHOOKS_RUN_BUDGET_SECONDS` | `40` | *(hidden)* A pass of the sender starts no try after this. |
| `CONSOLE_WEBHOOKS_ENDPOINT_TRIES_PER_RUN` | `10` | *(hidden)* Tries one endpoint gets in a pass. |
| `CONSOLE_WEBHOOKS_ENDPOINT_BUDGET_SECONDS` | `20` | *(hidden)* Time one endpoint gets in a pass. |
| `CONSOLE_WEBHOOKS_RETRY_INITIAL_SECONDS` | `60` | *(hidden)* The wait after the first failed try; doubled after each. |
| `CONSOLE_WEBHOOKS_RETRY_MAX_SECONDS` | `3600` | *(hidden)* The longest wait between tries. |
| `CONSOLE_WEBHOOKS_GIVE_UP_AFTER_SECONDS` | `86400` | *(hidden)* A delivery this old is given up (`failed`). |
| `CONSOLE_WEBHOOKS_KEEP_DAYS` | `14` | *(hidden)* Days delivered and failed deliveries are kept. |

### Pre-publish hook

A change-ticket reference each publish must carry, and a change-management system asked
before each publish. See
[The pre-publish hook](admin-guide.md#the-pre-publish-hook-and-change-tickets).

| Variable | Default | What |
|---|---|---|
| `CONSOLE_PUBLISH_TICKET_PATTERN` | unset | A publish must carry a ticket matching this regular expression (`^(CHG|INC)-[0-9]{4,}$`). |
| `CONSOLE_PUBLISH_HOOK_URL` | unset | An `https://` URL posted to before each publish, with a signed request; its answer allows or refuses the publish. Needs `CONSOLE_PUBLISH_HOOK_SECRET_FILE`. |
| `CONSOLE_PUBLISH_HOOK_SECRET_FILE` | unset | A file holding the secret the posts are signed with (`X-Syncd-Signature`), read at each use. |
| `CONSOLE_PUBLISH_HOOK_TIMEOUT` | `10s` | How long the hook may take to answer (`10s`, `1m`, or bare seconds). |
| `CONSOLE_PUBLISH_HOOK_ON_ERROR` | `refuse` | What a hook that cannot answer means: `refuse` the publish (`503`), or `allow` it. |

### Git sync

Commits on a branch become configuration versions; versions saved in the console can be
pushed back. Off unless `CONSOLE_CONFIG_GIT_URL` is set. Needs `git` on the host (the
packages recommend it; the console image has it). See
[Git sync](config-as-code.md#git-sync).

| Variable | Default | What |
|---|---|---|
| `CONSOLE_CONFIG_GIT_URL` | unset | The repository: `https://...`, `ssh://git@...` (or `git@host:path`), or `file://`. Not plain `http://`. The console image cannot use `file://`. |
| `CONSOLE_CONFIG_GIT_BRANCH` | `main` | |
| `CONSOLE_CONFIG_GIT_PATH` | `syncd.yaml` | The configuration file, a relative path inside the repository. |
| `CONSOLE_CONFIG_GIT_ENV_PATH` | unset | A directory of environment files (`<name>.env.yaml`) in the repository. A file with a secret value is refused. |
| `CONSOLE_CONFIG_GIT_SSH_KEY_FILE` | unset | The private key for an `ssh://` URL (a deploy key). |
| `CONSOLE_CONFIG_GIT_TOKEN_FILE` | unset | A file holding the token for an `https://` URL. Not with `SSH_KEY_FILE`. |
| `CONSOLE_CONFIG_GIT_KNOWN_HOSTS_FILE` | the user's default file | The `known_hosts` file the server's SSH host key must be in (strict checking). |
| `CONSOLE_CONFIG_GIT_POLL` | `60s` | How often the repository is fetched. |
| `CONSOLE_CONFIG_GIT_AUTO_PUBLISH` | `off` | What a new commit does besides becoming a version: `off` (a draft), `validate` (a draft with its test cases run) or `publish` (published when valid and approved). |
| `CONSOLE_CONFIG_GIT_PUSH_BACK` | `false` | Commit a version saved or published in the console to the branch, as the console user, and push it. A rejected push is reported, never forced. |
| `CONSOLE_CONFIG_GIT_ENGINES` | every engine | The engines the repository is for, by name, comma-separated. |
| `CONSOLE_CONFIG_GIT_BINARY` | `git` | *(hidden)* The `git` program. |

### Exports and scheduled reports

Reconciliation reports are downloaded from the console or scheduled on its **Reports**
page. See [Scheduled reports](reports-and-data-quality.md#scheduled-reports) and
[Settings](reports-and-data-quality.md#settings).

| Variable | Default | What |
|---|---|---|
| `CONSOLE_EXPORTS_MAX_ROWS` | `200000` | The most rows one download holds; a capped download carries `X-Syncd-Truncated: true`. |
| `CONSOLE_REPORTS_RECIPIENT_DOMAINS` | empty (no e-mail) | Domains a scheduled report may be e-mailed to (`example.gov`, `*.mil`), comma-separated. Nothing is e-mailed without it. |
| `CONSOLE_REPORTS_SHARE_DIR` | unset | A directory scheduled reports may be written to (a mounted share). |
| `CONSOLE_REPORTS_MAX_ROWS` | `50000` | The most rows a scheduled report's file holds; the mail says when it was cut. |
| `CONSOLE_REPORTS_MAX_BYTES` | `15728640` (15 MB) | *(hidden)* The largest file a scheduled report sends. |
| `CONSOLE_REPORTS_POLL_SECONDS` | `300` | *(hidden)* How often due reports are looked for. |
| `CONSOLE_REPORTS_MAX_PER_USER` | `20` | *(hidden)* Scheduled reports one account may own. |

### Mail

The console sends scheduled reports through an SMTP relay of its own, from an outbox in
its database: a mail server that is down loses nothing. Off unless `CONSOLE_SMTP_HOST` is
set. See [E-mail](reports-and-data-quality.md#e-mail).

| Variable | Default | What |
|---|---|---|
| `CONSOLE_SMTP_HOST` | unset | The mail server. Turns e-mail on. |
| `CONSOLE_SMTP_PORT` | `587` | |
| `CONSOLE_SMTP_STARTTLS` | `true` | Upgrade the connection with STARTTLS; the server's certificate is checked. `false` sends in plain text: a relay on the host or a private network only. |
| `CONSOLE_SMTP_CA_FILE` | unset | PEM CA certificates the server's certificate may chain to, besides the public roots. |
| `CONSOLE_SMTP_USERNAME` | unset | The account to authenticate as (AUTH PLAIN). Needs `CONSOLE_SMTP_PASSWORD_FILE` and STARTTLS. |
| `CONSOLE_SMTP_PASSWORD_FILE` | unset | A file holding its password, read at each connection. |
| `CONSOLE_SMTP_FROM` | `syncd@<host name>` | The sender address (`syncd@example.gov`, or `syncd <syncd@example.gov>`). |
| `CONSOLE_SMTP_TIMEOUT_SECONDS` | `30` | *(hidden)* How long one SMTP exchange may take. |
| `CONSOLE_MAIL_POLL_SECONDS` | `30` | *(hidden)* How often the outbox looks for mail to send. |
| `CONSOLE_MAIL_RETRY_INITIAL_SECONDS` | `60` | *(hidden)* The wait after the first failed try; doubled after each. |
| `CONSOLE_MAIL_RETRY_MAX_SECONDS` | `3600` | *(hidden)* The longest wait between tries. |
| `CONSOLE_MAIL_GIVE_UP_AFTER_SECONDS` | `86400` | *(hidden)* A message this old is given up, and its report raises `report.failed`. |
| `CONSOLE_MAIL_KEEP_DAYS` | `14` | *(hidden)* Days sent and failed messages are kept. |

The SMTP client speaks STARTTLS and AUTH PLAIN only: there is no SMTPS (implicit TLS on
port 465) and no AUTH LOGIN.

### The packaged console.env

The release's template, `/opt/syncd/current/share/config/console.env`. An upgrade does not
change `/etc/syncd/console.env`; compare it with the template to see the settings a release
added. None of the new ones is needed.

```sh
# The console's database. The installer filled this in.
CONSOLE_DATABASE_URL=@DATABASE_URL@

# Where the console listens. It serves plain HTTP unless given a certificate below. For
# access from other machines it needs HTTPS (session cookies are marked Secure outside
# development mode, so browsers send them over HTTPS only, or to localhost): its own, or
# a TLS reverse proxy in front of it (an nginx configuration is in
# /opt/syncd/current/share/nginx/syncd.conf). See INSTALL.md, "Reaching the console from
# other machines". Behind a proxy on this host, keep the console on 127.0.0.1.
CONSOLE_LISTEN=@LISTEN@

# The console's own HTTPS: a certificate (PEM, followed by its chain) and its key,
# readable by syncd-console. A renewed certificate is picked up within a minute.
#CONSOLE_TLS_CERT=/etc/syncd/console-tls/fullchain.pem
#CONSOLE_TLS_KEY=/etc/syncd/console-tls/privkey.pem

# The address users reach the console at, through the proxy (needed for single sign-on).
#CONSOLE_PUBLIC_URL=https://syncd.example.com

# Proxies whose X-Forwarded-For names the client (addresses or CIDR ranges, comma-
# separated), so the audit log and the sign-in limit see users' own addresses rather
# than the proxy's. Only list proxies you run: anyone else could claim any address.
#CONSOLE_TRUSTED_PROXIES=127.0.0.1

# Single sign-on (OpenID Connect); see INSTALL.md.
#CONSOLE_OIDC_ISSUER=https://login.example.com/realms/corp
#CONSOLE_OIDC_CLIENT_ID=syncd-console
#CONSOLE_OIDC_CLIENT_SECRET_FILE=/etc/syncd/oidc-client-secret
#CONSOLE_OIDC_ROLE_MAP=syncd-admins=admin,syncd-operators=operator

# The audit trail (ens-audit/1) goes to the database, and to these outputs besides: one
# JSON line per event on stdout (the journal) unless ENS_AUDIT_STDOUT=false, an audit
# file rotated daily or at 100 MB, Splunk's HTTP Event Collector, syslog (RFC 5424).
# Events queue while a receiver is down. See console/README.md, "Audit trail".
#ENS_AUDIT_FILE=/var/log/syncd/audit.jsonl
#ENS_AUDIT_FILE_KEEP=30
#ENS_AUDIT_SPLUNK_URL=https://splunk.example.com:8088
#ENS_AUDIT_SPLUNK_TOKEN_FILE=/etc/syncd/splunk-hec-token
#ENS_AUDIT_SPLUNK_INDEX=ens_audit
#ENS_AUDIT_SYSLOG_HOST=syslog.example.com
#ENS_AUDIT_SYSLOG_PROTOCOL=tls      # udp, tcp or tls (port 514, or 6514 for tls)
#ENS_AUDIT_RETENTION_DAYS=365       # 0 (the default) keeps events forever

# API tokens (Integrations in the console), for a SIEM or a pipeline. See
# console/README.md, "API tokens".
#CONSOLE_API_TOKENS_ENABLED=true
#CONSOLE_API_MAX_TOKEN_LIFETIME=365d   # 0 for no limit
#CONSOLE_API_REQUESTS_PER_MINUTE=300   # per token; 0 for no limit

# Outbound webhooks (Integrations in the console): signed HTTPS posts to a Slack channel,
# a Teams workflow, PagerDuty or an integration of your own. Nothing is sent until an
# administrator adds one. See console/README.md, "Webhooks".
#CONSOLE_WEBHOOKS_ENABLED=true
#CONSOLE_WEBHOOKS_ALLOWED_HOSTS=hooks.slack.com,*.webhook.office.com,events.pagerduty.com,relay.example.gov
#CONSOLE_WEBHOOKS_BLOCK_PRIVATE_NETWORKS=true   # unset: refused for new webhooks; false: allowed
#CONSOLE_WEBHOOKS_CA_FILE=/etc/syncd/receivers-ca.pem   # a private CA some receivers use

# Scheduled reconciliation reports (Reports in the console), e-mailed or written to a
# share. Nothing is e-mailed until the recipient domains and a mail server are set. See
# console/README.md, "Exports and reports".
#CONSOLE_REPORTS_RECIPIENT_DOMAINS=example.gov,*.example.mil
#CONSOLE_REPORTS_SHARE_DIR=/srv/syncd-reports
#CONSOLE_SMTP_HOST=relay.example.gov
#CONSOLE_SMTP_PORT=587
#CONSOLE_SMTP_STARTTLS=true
#CONSOLE_SMTP_FROM=syncd@example.gov
#CONSOLE_SMTP_USERNAME=syncd
#CONSOLE_SMTP_PASSWORD_FILE=/etc/syncd/smtp-password

# The pre-publish hook: a change ticket a publish must carry, and a change-management
# system asked before each publish. See console/README.md, "Pre-publish hook".
#CONSOLE_PUBLISH_TICKET_PATTERN=^(CHG|INC)-[0-9]{4,}$
#CONSOLE_PUBLISH_HOOK_URL=https://itsm.example.gov/syncd
#CONSOLE_PUBLISH_HOOK_SECRET_FILE=/etc/syncd/publish-hook-secret
#CONSOLE_PUBLISH_HOOK_TIMEOUT=10s
#CONSOLE_PUBLISH_HOOK_ON_ERROR=refuse   # or allow

# Configuration as code (console/README.md, "Git sync"): commits on a branch become
# configuration versions, optionally published; versions saved here pushed back. Needs
# the git package on this host.
#CONSOLE_CONFIG_GIT_URL=ssh://git@git.example.gov/idm/syncd.git
#CONSOLE_CONFIG_GIT_BRANCH=main
#CONSOLE_CONFIG_GIT_PATH=syncd.yaml
#CONSOLE_CONFIG_GIT_ENV_PATH=environments
#CONSOLE_CONFIG_GIT_SSH_KEY_FILE=/etc/syncd/git-deploy-key
#CONSOLE_CONFIG_GIT_KNOWN_HOSTS_FILE=/etc/syncd/git-known-hosts
#CONSOLE_CONFIG_GIT_TOKEN_FILE=/etc/syncd/git-token   # for https://, instead of the key
#CONSOLE_CONFIG_GIT_POLL=60s
#CONSOLE_CONFIG_GIT_AUTO_PUBLISH=off   # off | validate | publish
#CONSOLE_CONFIG_GIT_PUSH_BACK=false
#CONSOLE_CONFIG_GIT_ENGINES=dev        # the engines the repository is for; all when unset

#SYNCD_FIPS_MODE=true       # only with a FIPS build of syncd
```

`syncdctl install` fills in the database URL and the listen address, and
`syncdctl install --fips` adds `SYNCD_FIPS_MODE=true` to both `console.env` and
`engine.env`. Remember to drop the trailing comment of a line you uncomment (see
[Files](#files)).

## Container images and Helm

The images (`ghcr.io/winllc/syncd` and `ghcr.io/winllc/syncd-console`) and the Helm chart
(`oci://ghcr.io/winllc/charts/syncd`) run the same two programs with the settings on this
page. This section is a reference for what differs; [Install](install.md) has the
procedure. They are published when a release is tagged; none has been tagged yet, so build
them from the source repository until then.

The images are built for `linux/amd64` and `linux/arm64`. The `<version>-fips` images
(amd64 only) are the FIPS build, which takes `SYNCD_FIPS_MODE=true`; the standard images
refuse it.

### The engine image

| Path or variable | What |
|---|---|
| `/etc/syncd/engine.yaml` | The image's initial configuration, below. Read-only. |
| `/var/lib/syncd` | The data volume: the store in `engine/`, and the configuration the engine runs with, `engine.yaml`. |
| `SYNCD_CONFIG=/var/lib/syncd/engine.yaml` | The configuration file, on the data volume, since the engine rewrites it when the console publishes a configuration. |
| `SYNCD_INITIAL_CONFIG=/etc/syncd/engine.yaml` | Copied to `SYNCD_CONFIG` when that file does not exist yet. |
| `SYNCD_DATA_DIR=/var/lib/syncd/engine` | The store. |
| Ports | `7443` (control API, mutual TLS), `9464` (metrics and health checks). |

The image's `engine.yaml`:

```yaml
version: 1

engine:
  data_dir: /var/lib/syncd/engine

api:
  # The control API, for the console, with mutual TLS. Mount the certificates at
  # /etc/syncd/pki (the Helm chart issues them).
  listen: 0.0.0.0:7443
  tls:
    cert_file: /etc/syncd/pki/engine.crt
    key_file: /etc/syncd/pki/engine.key
    client_ca_file: /etc/syncd/pki/ca.crt
  # `kubectl exec ... -- syncd status` and the other commands use the socket, without
  # certificates.
  control_socket: /run/syncd/control.sock
  # Prometheus metrics, and the health checks.
  metrics_listen: 0.0.0.0:9464
```

`engine.data_dir` and `api` are host-only, as on a package install, so the console cannot
change them. To set them otherwise, mount a file of your own at `/etc/syncd/engine.yaml`
before the first start: once the copy on the data volume exists, that copy is the one the
engine reads. The chart uses the image's file as it is, and sets `engine.ha` through the
`SYNCD_HA_*` variables. There is no `engine.env` in a container: the secrets a
configuration refers to (`{ env: NAME }`) are ordinary environment variables of the
container, which the chart takes from a Secret (`engine.envFromSecret`).

### The console image

| Variable | Value |
|---|---|
| `CONSOLE_LISTEN` | `0.0.0.0:8080` |
| `CONSOLE_UI_DIR` | `/usr/share/syncd/console` |
| `CONSOLE_DATA_DIR` | `/var/lib/syncd/console` |

Every other [console setting](#console-settings) is an environment variable of the
container. The image serves plain HTTP unless given `CONSOLE_TLS_CERT` and
`CONSOLE_TLS_KEY`; with the chart, set `console.tls.existingSecret` instead and the
probes follow. The image includes `git` and `ssh` for Git sync: `https://` and `ssh://`
repositories work, `file://` ones do not (there is no shell in the image).

Both images run as uid and gid `65532`, need no root and work with a read-only root file
system. Their base images are pinned by digest.

### Helm chart values

The chart's `values.yaml`, by key. The chart's version is the release's (`0.4.0`).

| Key | Default | What |
|---|---|---|
| `image.engine.repository`, `image.console.repository` | `ghcr.io/winllc/syncd`, `ghcr.io/winllc/syncd-console` | The images. |
| `image.*.tag` | the chart's `appVersion` | `<version>-fips` for the FIPS images. |
| `image.*.pullPolicy` | `IfNotPresent` | |
| `imagePullSecrets` | `[]` | |
| `podSecurityContext`, `securityContext` | uid and gid `65532`, non-root, `RuntimeDefault` seccomp, read-only root file system, no capabilities | Used for both. |
| `engine.name` | the release name | The name the console knows the engine by. An HA pair is registered as `<name>-0` and `<name>-1`. |
| `engine.envFromSecret` | empty | A Secret whose keys become the engine's environment variables: the secrets the configuration refers to. |
| `engine.env` | `[]` | More environment variables (`RUST_LOG`, `SYNCD_FIPS_MODE`, `AWS_*` for backups). |
| `engine.extraVolumes`, `engine.extraVolumeMounts` | `[]` | More volumes on the engine's pod: a CSV inbox, a CA bundle for a source's TLS. |
| `engine.persistence.size` | `10Gi` | The store's volume; size it as [Availability and sizing](availability-and-sizing.md) says. |
| `engine.persistence.storageClass` | the cluster's default | |
| `engine.persistence.accessModes` | `[ReadWriteOnce]` | |
| `engine.resources` | requests `250m` CPU, `512Mi`; limit `4Gi` | |
| `engine.terminationGracePeriodSeconds` | `90` | Time to finish the batch and stop. |
| `engine.service.type` | `ClusterIP` | Ports `7443` and `9464`. |
| `engine.ha.enabled` | `false` | An active/passive pair: two engine pods, a headless Service, the lease in the console's database, anti-affinity and a PodDisruptionBudget. |
| `engine.ha.leaseTtlSeconds` | `15` | `SYNCD_HA_LEASE_TTL_SECS`. |
| `engine.ha.snapshotEverySeconds` | `300` | `SYNCD_HA_SNAPSHOT_EVERY_SECS`. |
| `engine.ha.antiAffinity` | `soft` | `soft` prefers two nodes; `hard` requires them. Ignored when `engine.affinity` is set. |
| `engine.ha.podDisruptionBudget.enabled` | `true` | A drain evicts one engine at a time. |
| `engine.metrics.job` | `syncd` | The `job` label the ServiceMonitor gives the engine's metrics, which the alert rules select by. |
| `engine.metrics.serviceMonitor.enabled` | `false` | A ServiceMonitor for the Prometheus operator (`interval` `30s`, `labels`). |
| `engine.metrics.prometheusRule.enabled` | `false` | The shipped alert rules as a PrometheusRule, with `labels` for the Prometheus `ruleSelector`. One release per Prometheus is enough. |
| `engine.nodeSelector`, `tolerations`, `affinity`, `podAnnotations` | empty | |
| `console.replicas` | `1` | |
| `console.database.url` | empty | The console's PostgreSQL URL, kept in a Secret the chart makes. This or `existingSecret` is required. |
| `console.database.existingSecret`, `existingSecretKey` | empty, `url` | A Secret of your own holding the URL, instead. |
| `console.adminPassword` | generated | The initial admin password, changed at first sign-in; `helm status` says how to read a generated one. |
| `console.trustedProxies` | empty | `CONSOLE_TRUSTED_PROXIES`: the ingress controller's addresses or ranges. |
| `console.env` | `[]` | More environment variables, for the console and the registration Job alike: the audit outputs, API tokens, webhooks, the pre-publish hook, Git sync, other single sign-on settings. |
| `console.newMasterKey` | `false` | `true` for one upgrade, after the master key was lost: the console and the registration Job start anyway, the stored secrets are discarded and the engine is registered again. Set it back afterwards. |
| `console.publicUrl` | `https://<ingress.host>` when the ingress is on | `CONSOLE_PUBLIC_URL`. |
| `console.oidc.issuer`, `clientId` | empty | Single sign-on; off while `issuer` is empty. |
| `console.oidc.existingSecret`, `existingSecretKey` | empty, `client-secret` | A Secret of your own holding the client secret, mounted as `CONSOLE_OIDC_CLIENT_SECRET_FILE`. |
| `console.oidc.roleClaim`, `roleMap`, `defaultRole` | empty | `CONSOLE_OIDC_ROLE_CLAIM`, `_ROLE_MAP`, `_DEFAULT_ROLE`. |
| `console.oidc.ca.configMap`, `ca.secret`, `ca.key` | empty, empty, `ca.crt` | CA certificates for the provider, from one key of a ConfigMap or a Secret (`CONSOLE_OIDC_CA_FILE`). |
| `console.tls.existingSecret` | empty | A `kubernetes.io/tls` Secret: the console serves HTTPS itself, and the probes, the Service's port name and the Ingress backend follow. Set the controller's backend-protocol annotation on the ingress. |
| `console.extraVolumes`, `console.extraVolumeMounts` | `[]` | More volumes on the console's pod: a hook secret, a Git deploy key. |
| `console.resources` | requests `100m` CPU, `128Mi`; limit `512Mi` | |
| `console.service.type`, `port` | `ClusterIP`, `8080` | |
| `console.ingress.enabled` | `false` | An Ingress for the console, with `className`, `annotations`, `host` (`syncd.example.com`) and `tls`. |
| `console.nodeSelector`, `tolerations`, `affinity`, `podAnnotations` | empty | |
| `pki.days` | `3650` | Validity of the control API certificates the chart issues. |
| `networkPolicy.enabled` | `false` | NetworkPolicies: the engine's control API from the console and the registration Job only, its metrics from the monitoring selectors, the console's port from the ingress controller selectors. |
| `networkPolicy.monitoringNamespaceSelector`, `monitoringPodSelector` | empty | Where Prometheus runs. Empty means from anywhere, for that port. |
| `networkPolicy.ingressNamespaceSelector`, `ingressControllerSelector` | empty | Where the ingress controller runs. |
| `networkPolicy.console.extraIngressFrom` | `[]` | More `from` entries for the console's port. |
| `networkPolicy.egress.enabled` | `false` | Limit egress to DNS and the engine's control API, plus `egress.extra`. |
| `networkPolicy.egress.extra` | `[]` | More egress rules, applied to all three: the sources and targets, the database, the identity provider, webhook receivers. |

What the chart makes on its own:

- **Secrets, generated at install and kept across upgrades and `helm uninstall`:** the
  console's master key (`CONSOLE_MASTER_KEY`, so the console's data directory needs no
  volume) and initial admin password, in `<fullname>-console`; and a CA with the engine's
  and the console's control API certificates (ECDSA P-256), in `<fullname>-pki`. An
  install made with an older chart keeps its RSA certificates until the PKI Secret is
  deleted. Deleting `<fullname>-pki` and upgrading issues new certificates. To start over
  completely, delete both Secrets and the engine's volume.
- **The engine** as a StatefulSet of one, with its store on a volume, the certificates at
  `/etc/syncd/pki` and the control socket in `/run/syncd`. With `engine.ha.enabled`, a
  StatefulSet of two, each with its own volume, that start together. Turning HA on or off
  in an existing release changes fields Kubernetes does not update in place: delete the
  StatefulSet without its pods first, and the PKI Secret too.
- **A registration Job** after each install and upgrade, which registers the engine (or
  each engine of a pair) in the console with the chart's certificates, with `--update`.
- **A PrometheusRule** with `engine.metrics.prometheusRule.enabled`, and
  **NetworkPolicies** with `networkPolicy.enabled`.

The chart's HA templates are linted and rendered in CI, but no test installs a pair on
Kubernetes or fails one over.

## Complete example

An HR database and a corporate directory merged into a people view and published to
OpenLDAP, from `examples/hr-to-openldap.yaml` in the source repository (comments
shortened). The source repository has further examples for MySQL, SQL Server, MongoDB,
Oracle through Debezium, Db2 through the JDBC bridge, a CSV inbox, Active Directory with
the conveniences, a SCIM application and Entra ID; [Connectors](connectors.md) shows their
connector sections.

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
