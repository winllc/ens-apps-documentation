# Architecture

syncd has two programs. They are installed together on one Linux host as systemd services,
or run on Kubernetes from their container images with the Helm chart (see
[Running on Kubernetes](#running-on-kubernetes)):

- **`syncd`**, the engine: it reads sources, merges their records into views, and writes
  the results to LDAP directories, SCIM 2.0 applications and Entra ID. It keeps its state in
  an embedded RocksDB store. It also delivers password changes, backs itself up, and can run
  as one of an [active/passive pair](#high-availability).
- **`syncd-console`**, the web console: accounts, roles, the audit trail, configuration
  versions, API tokens, webhooks, reports, Git sync, and the REST API and UI. It keeps its own
  data in PostgreSQL and drives the engine through the engine's gRPC control API.

The engine runs without the console. The `syncd` command line talks to the same control API.

## Components

```mermaid
flowchart LR
    subgraph Sources
        DB[("PostgreSQL · MySQL / MariaDB<br/>SQL Server · MongoDB")]
        DIR[("LDAP directories<br/>Active Directory")]
        CSV[("CSV / flat files<br/>(an inbox)")]
        ENS[("Entra ID<br/>(delta queries)")]
        DBZ["Debezium Server sidecar<br/>(HTTP sink)"]
        JDBC["JDBC bridge<br/>(Java, gRPC)"]
    end
    subgraph Engine["syncd (engine host)"]
        ING["Source tasks<br/>scrape · stream · delta"]
        LOOP["Run loop<br/>batches, commits, timers,<br/>long operations"]
        PIPE["Pipeline<br/>staging → correlation → merge →<br/>computed attributes → quality →<br/>export planning → change safety"]
        WR["Target writers<br/>LDAP · SCIM · Entra (Graph)"]
        PW["Password delivery<br/>(HPKE envelopes)"]
        STORE[("RocksDB store<br/>data_dir")]
        API["Control API<br/>gRPC, mTLS or loopback"]
        PIN["Password ingest<br/>gRPC, agent mTLS"]
        MET["/metrics<br/>Prometheus, plain HTTP"]
    end
    subgraph Console["syncd-console"]
        SRV["Axum server<br/>REST /api/v1 · SSE · OpenAPI"]
        BG["Background tasks<br/>status watcher · webhooks ·<br/>audit outputs · Git sync · reports"]
        UI["React UI<br/>(static files)"]
        PG[("PostgreSQL<br/>accounts, versions, audit trail,<br/>tokens, webhooks, HA lease")]
    end
    TGT[("Targets<br/>AD · OpenLDAP · 389 DS · Ping/UnboundID<br/>SCIM 2.0 apps · Entra ID")]
    AG["Password capture agents"]
    NGX["nginx or an ingress (TLS)"]
    BR["Browser"]
    CLI["syncd CLI"]
    OIDC["OpenID Connect provider"]
    OUT["Webhook receivers ·<br/>Splunk HEC · syslog · SMTP"]
    GIT[("Git repository")]
    BK[("Backups<br/>directory or S3")]

    DB --> ING
    DIR --> ING
    CSV --> ING
    ENS --> ING
    DBZ -->|"HTTP POST"| ING
    JDBC -->|gRPC| ING
    ING --> LOOP --> PIPE --> WR -->|"LDAPS / StartTLS · HTTPS"| TGT
    PW -->|"password writes"| TGT
    LOOP <--> STORE
    PIPE <--> STORE
    PW <--> STORE
    API --> LOOP
    API -.read-only lookups.-> STORE
    AG -->|"sealed changes"| PIN --> STORE
    STORE -.->|"syncd backup"| BK
    BR -->|HTTPS| NGX -->|"HTTP"| SRV
    BR -.->|"HTTPS, the console's own certificate"| SRV
    SRV --> UI
    SRV --> PG
    BG --> PG
    SRV -->|"gRPC, mTLS"| API
    BG -->|"status, every 10 s"| API
    BG --> OUT
    BG <--> GIT
    CLI -->|"gRPC, mTLS or control socket"| API
    SRV -->|"authorization code + PKCE"| OIDC
```

| Component | Responsibility | Source |
|---|---|---|
| Source connectors | Read objects from a source in full (`scrape`), as a stream of changes (`stream`: PostgreSQL logical replication, MySQL binlog, SQL Server change data capture, MongoDB change streams, LDAP syncrepl, AD DirSync, LDAP changelogs), or by a watermark (`delta`). Full reads run on an interval or a cron `schedule`. A CSV file is read in full, on a schedule or when it changes; Entra ID users and groups are read through Graph delta queries. Streaming sources send a checkpoint at each transaction boundary. A database connect (TCP, TLS and login) fails after 10 seconds without an answer, so a hung server restarts the source rather than stalling it | `engine/crates/connectors` |
| Wire decoders | Decoders of what sources send (`pgoutput`, DirSync, syncrepl), fuzzed in CI | `engine/crates/wire` |
| Run loop | One loop owns the engine. It batches changes from every source, plans and applies exports, fires the persisted timers, and commits each batch with its source checkpoints. It also runs the long operations and the commands that come from the control API | `engine/bin/syncd/src/run.rs` |
| Pipeline | Staging, correlation, precedence merge, computed attributes and rule packages, data-quality checks, groups and their lifecycle, export planning, change safety (windows, thresholds, canaries, report mode), leaver stages, drift decisions | `engine/crates/engine` |
| Rules | The CEL rule compiler, function library, rule packages and computed-attribute transforms. The console's previews link the same crate | `engine/crates/rules` |
| Store | RocksDB column families for staging, entities, links, exported state, checkpoints, the dead-letter queue, history, held changes, timers, the password queue and the rest (see [The store](#the-store)) | `engine/crates/store` |
| Target writers | Apply planned operations to a target: LDAP (parent entries first, a bounded number of entities in flight per connection), SCIM 2.0 (`POST`, `PATCH` or `PUT` with ETags, paced and waiting out `429`) and Entra ID (Microsoft Graph, members in `$batch`, licences, app roles). Every batch checks the HA lease first | `engine/crates/connectors` (`ldap.rs`, `scim.rs`, `entra.rs`, `target.rs`) |
| Password synchronisation | The engine's password key, HPKE envelopes, the agent CA, enrollment, the self-service policy, and delivery of captured changes and initial passwords to targets | `engine/crates/password`, `engine/bin/syncd` |
| Control API | gRPC service `syncd.v1.EngineControl`: status, the status stream, runs, approvals, held changes, dead letters, quarantine, data quality, configuration, previews, the object explorer, lineage, reports, correlation, passwords, backups and HA snapshots | `engine/crates/api`, `proto/syncd/v1/engine.proto` |
| Password ingest | gRPC service `syncd.v1.PasswordIngest` on a listener of its own, for capture agents | `engine/crates/api/src/ingest.rs` |
| High availability | The lease (PostgreSQL or a file), the fence, the lease controller, store snapshots and the swap | `engine/crates/ha` |
| Backups | Backup archives with a manifest, to a directory or S3 (SigV4), restore, and the support bundle | `engine/crates/backup` |
| Metrics | Prometheus text format at `GET /metrics` on `api.metrics_listen`, off unless set | `engine/crates/api/src/metrics.rs` |
| Process statistics | The engine's own CPU time, the CPUs it may use, resident and peak memory, its cgroup's memory limit, threads and open files, read from `/proc` and the cgroup with each status (Linux only). The console's dashboard and metrics page chart them live | `engine/crates/api/src/process.rs` |
| Crypto | Installs the process-wide TLS crypto provider: aws-lc-rs, or its FIPS module | `engine/crates/crypto` |
| Console server | Accounts, sessions, roles, single sign-on, API tokens, the audit trail and its outputs, configuration versions, environments, promotion, the pre-publish hook, the proxy to each engine's control API, the REST API and its OpenAPI document | `console/server` |
| Console background tasks | The status watcher and webhook sender, the audit outputs and retention, Git sync, scheduled reports and the mail outbox, the data-quality daily rollup | `console/server/src/webhooks`, `audit`, `gitsync.rs`, `reports`, `mail.rs` |
| Console UI | The single-page application, served by the console server from `CONSOLE_UI_DIR` | `console/web` |
| `syncdctl` | Install, upgrade with rollback, backup (local or off-host), restore, the support bundle, status, renewing the control API certificates, uninstall | `deploy/packaging/syncdctl` |
| Container images and Helm chart | The engine and console images (multi-arch, and a FIPS variant), and the chart that runs them on Kubernetes | `deploy/container`, `deploy/helm/syncd` |

## The pipeline

```mermaid
flowchart LR
    E["Source event<br/>(record or delete)"] --> S["Staging<br/>staging/&lt;source&gt;"]
    S --> C{"Correlation"}
    C -->|"linked already"| M
    C -->|"join rule matches one entity"| M
    C -->|"projects"| N["New entity"] --> M
    C -->|"no match, or several"| O["Unjoined<br/>(correlation review,<br/>suggestions)"]
    M["Merge<br/>inbound flows, precedence"] --> CA["Computed attributes<br/>dependency order"]
    CA --> Q["Data-quality checks<br/>quality/&lt;view&gt;"]
    Q --> P["Export planning<br/>diff against exported/&lt;publication&gt;"]
    P --> G{"Change safety"}
    G -->|"now"| W["Target writer"]
    G -->|"window, threshold,<br/>canary, report mode"| H["Held<br/>held/&lt;publication&gt;"]
    H -.->|"timer or approval:<br/>planned again"| P
    W -->|success| X["Record exported state"]
    W -->|failure| D["Dead-letter queue<br/>dlq/&lt;publication&gt;"]
```

1. **Ingest.** Each source object runs in its own task and sends events to the run loop
   over a bounded channel (up to 10 000 changes). When the loop is busy, the channel fills
   and streaming sources stop reading rather than buffering without limit.
2. **Staging.** A record is stored by its anchor (the immutable key the object names) in
   the source's staging space. A full read is diffed against what is staged, so records
   that are gone become deletes.
3. **Correlation.** A staged record that already has a link keeps its entity. Otherwise the
   view's join rules are tried in order. A record that matches one entity joins it; a source
   that projects creates a new entity; a record that matches none (or several) waits
   unjoined, and when it became unjoined is kept (`unjoined/<view>`) so the queue's age can be
   reported. Operators can join or unjoin records by hand, or accept scored suggestions, and
   those decisions are kept in the store (`pinned_joins`, `exclusions`).
4. **Merge.** Any change recomputes the entity from all its contributors: inbound attribute
   flows, then per-attribute precedence between sources.
5. **Computed attributes.** Rules compiled from CEL run in dependency order. Calls to rule
   package functions are expanded by the compiler. Rules that read other entities
   (references, `referrers()`, groups) are re-evaluated across entities, up to 16 rounds per
   change. When a rule with `on_error: quarantine` fails, the entity's exports are held back.
   The hold is recorded in the store (`quarantine/<view>`), so it lasts across restarts, and
   ends once the rule evaluates again for the entity. All rules of one pass share one `now()`.
6. **Data-quality checks.** A view's `quality` checks run as the entity is computed, so they
   run again exactly when its inputs change. Failures are kept per entity
   (`quality/<view>`). A check can only record the failure, hold the entity's exports back, or
   keep it out of some publications. See
   [Reports, lineage and data quality](reports-and-data-quality.md).
7. **Export planning.** For each publication, the entity is projected into a target entry
   (DN template, object classes, attribute mapping; for SCIM and Entra ID, a resource name)
   and diffed against the last exported entry. The result is the smallest set of operations:
   add, modify, rename or delete, and the leaver stages that are due.
8. **Change safety.** Report mode, the modification, add and deletion thresholds, a canary
   and the change window decide whether each plan is written now or **held**. See
   [Timers and held changes](#timers-and-held-changes).
9. **Target writer.** Each entity's operations are applied in order. An LDAP target takes
   parent entries first, and up to `max_inflight` entities (32 by default) at once over one
   multiplexed connection. A successful export updates the exported state; a failed one goes
   to the dead-letter queue.

The CPU-bound, read-only stages (recomputing entities, planning exports) run on
`engine.shards` worker threads (one per CPU by default). The run loop's own thread applies
their results, so writes to the store stay single-threaded and ordered.

## Timers and held changes

Since 0.4.0 the engine can act at a set time, not only when something changes.

- **Persisted timers.** Due times are kept in the store (`timers`, ordered by due time, and
  `timer_keys`, so arming the same entity again replaces its timer). The run loop looks every
  second for timers that are due and acts on them, whether the engine ran throughout or was
  restarted in between: a timer fires at its due time, not after an uptime. Each fires once,
  removed in the same commit as what it did. Timers are not fired before the initial export.
- **What arms them.** A rule's effective date (`effective()`, `effective_until()`), the next
  stage of a leaver (`deprovision: { disable_after, strip_groups_after, move_to,
  delete_after }`), a change window's next opening or a held change's `max_delay`, a canary's
  soak, and a group's expiry. A recompute timer recomputes the entity with the rules' `now()`
  at the firing time and plans it again for every publication of its view; a window or canary
  timer releases held changes; an expiry timer acts on the group.
- **Leaver clocks** live with the exported entry in the store (`syncd-left-at`,
  `syncd-stripped`, beside `syncd-disabled`) and are never written to the target. A stage falls
  due at the time it left plus the stage's delay, by the engine's clock, so a timer and a full
  recompute at the same instant agree.
- **Held changes.** A planned export that is not applied now is written to `held/<publication>`:
  one entry per entity, keyed by entity id and replaced as the plan changes, with the reason
  (`window`, `overdue`, `modifications`, `additions`, `deletions`, `canary`, `canary_failed` or
  `report`), the kinds of change, the DN, a summary of the operations without values, and when
  it was first held. The engine also counts them in memory per publication.
- **A held change is never replayed.** When its hold ends (the window opens, the canary has
  soaked, an operator approves) the entity is planned again from its current state, and what
  that plan says is applied. An entity that no longer needs a change loses its hold.
- `overdue`, the thresholds and `canary_failed` need an approval (`syncd approve`, or the
  console's **Approve and apply**); the others clear by themselves. Report mode records every
  plan as a would-do and writes nothing.

`syncd status`, `syncd held`, the console's Held changes card, `ListHeld` and the
`syncd_held_changes`, `syncd_timers_*` and `syncd_deprovision_pending` metrics show them. See
[Change safety and lifecycle](change-safety.md) for the settings.

## Password synchronisation

From 0.4.0 the engine delivers password changes to the accounts it manages. A password is
never in the clear outside memory: it is sealed where it is captured and opened only by the
engine, just before it is written to a target.

```mermaid
sequenceDiagram
    participant A as Capture agent<br/>(or the console's self-service page)
    participant I as Ingest listener<br/>(or control API)
    participant Q as Store<br/>password_queue
    participant D as Delivery
    participant T as Targets

    A->>I: GetPublicKey (agent certificate)
    I-->>A: P-256 public key and its id
    A->>A: seal with HPKE: source, object, anchor,<br/>sequence, capture time, password
    A->>I: Submit(sealed changes)
    I->>Q: queue as received, still sealed
    I-->>A: queued
    D->>Q: due changes (at once, then every 30 s)
    D->>D: open in memory, route: anchor → entity →<br/>publications taking that source → DN
    D->>T: Password Modify, userPassword or unicodePwd
    D->>Q: record where it went, or drop it
```

- **The engine's password key** is a P-256 key pair, created on first start in the data
  directory (`password.key`, `passwords.key_file`), mode 0600. From 0.4.1, with
  `SYNCD_PASSWORD_KEY_PASSPHRASE_FILE`, it and the agent CA's key are sealed at rest
  (PBKDF2-HMAC-SHA256, 600,000 iterations, AES-256-GCM, on aws-lc-rs); without it they are in the
  clear, protected by the file mode only, and the engine warns at start. Capture points fetch the
  public half (`GetPasswordKey`); they can seal, never open. See
  [Security](security.md#cryptography).
- **The envelope** is HPKE (RFC 9180, base mode): DHKEM(P-256, HKDF-SHA256), HKDF-SHA256,
  AES-256-GCM, on aws-lc-rs, with the key's id as additional data. A self-service request is
  sealed with a different HPKE `info`, so it can never be taken for a captured change.
- **Capture points:**
    - **Agents** enroll on the ingest listener (`passwords.ingest.listen`) with a one-time token
      and get a client certificate from the engine's **agent CA** (`password-agent-ca.key` and
      `.crt` beside the password key, created on first start, ten years). They submit with
      `Submit`. See [API reference](api-reference.md#password-ingest-api-grpc).
    - **The console's self-service page** seals the person's request to the password key and
      sends it with `ChangeOwnPassword` (from 0.4.1, to the member of an HA pair that holds the
      lease). The engine checks its policy, binds as the person to
      prove the current password, changes it in the source directory as the person, then queues
      it for the other targets.
    - **`syncd password set`** stands in for a capture point in scripts and tests.
    - **Initial passwords** (`passwords.initial`) are generated or read from the entity and set
      as an entry is created.
- **The queue** (`password_queue`) keeps changes sealed, as they came, until they are delivered
  or reach `passwords.ttl_secs` (a day by default): a change for an account not provisioned yet,
  or a target that is down, waits. Per account only the newest change is delivered; one no newer
  than the last delivered (`password_sequences`) is dropped. syncd's own write coming back from
  a directory is recognised for 10 minutes by a keyed hash and dropped.
- **A refusal** by a target (its password policy, access rights) is reported and not retried.
  `syncd status` and the status API count deliveries, failures, dropped (report mode) and
  waiting changes, never with password material; from 0.4.1 also per publication, and as the
  metrics `syncd_password_deliveries_total` and `syncd_password_waiting`.
- **Agents** are kept in `password_agents`: the token's SHA-256, the certificate's fingerprint,
  revocation, when last seen and how many changes each submitted. Revocation checks the
  fingerprint, not a CRL.

There are no capture plugins yet (the Windows password filter, the 389 and OpenLDAP plugins),
and the password key and agent CA cannot be rotated (nor, from 0.4.1, their passphrase changed). See
[Password synchronisation](password-sync.md) and [Security](security.md#password-synchronisation).

## Renames and moves

Since 0.3.0, the engine follows entries that move, whoever moves them.

- **Target ids.** The writer reads each entry's id in the target after it adds the entry, or
  at its first write if it was exported before ids were kept: `objectGUID` on Active
  Directory, `nsUniqueId` on 389 Directory Server, `entryUUID` elsewhere. The id is kept in
  the exported state (as `syncd-target-id`) and never written to the target. Reading it is
  best effort: a target that does not give it works as before.
- **Entries moved or renamed in the target by someone else.** When an entry is not at the DN
  last written, the writer looks for it by its id. On Active Directory that is a base search
  for `<GUID=…>`; elsewhere a subtree search of the entry's naming context. A modify moves the
  entry back to the DN the publication places it at and changes it there; a rename or delete
  acts where the entry was found. Each is logged as a warning naming both DNs. Only an entry
  found nowhere is added again. Two entries with the same id fail the export (dead letter).
- **Renames into a DN that is taken.** When two entries of one batch swap DNs, or rename in a
  chain, the writer parks a blocked entry under an RDN of its own (its RDN with `~` and the
  entity's number, for example `CN=Jon Smith~1a`). Once the rest of the batch is written, it
  renames the parked entries to their DNs, in rounds. An entry moved aside (`move_to`,
  `disable_and_move_to`) into a container where its RDN is taken gets such an RDN for good,
  and the engine records it there. Any other rename into a taken DN fails, as a real
  conflict.
- **Containers renamed in an LDAP source.** A directory reports only the container, not the
  entries under it. So an LDAP object read with `mode: stream` or `mode: delta` and subtree
  scope has its containers checked every `container_check_secs` (60 by default; 0 turns it
  off). The entries under a container renamed since the last check are read again, as
  updates. The last check's DNs are kept as a checkpoint of their own
  (`<connector>/<object>#containers`), so a rename made while the engine was stopped is found
  at the next start.
- **Case-only renames** (`jdoe` to `JDoe`) are renames, tested on OpenLDAP and 389.
- **Containers left empty.** The writer records each container it creates
  (`containers/<target>` in the store). With a publication's `remove_empty_containers` (off
  by default, needs `create_containers`), a plan that moves or deletes an entry lists the
  containers it leaves, and the writer deletes those that are empty once the batch is
  written, deepest first. A container syncd did not create, or that holds anything, stays. A
  failure to delete one is logged and fails no export.

See [Configuration](configuration.md) for the settings, and
[Troubleshooting](troubleshooting.md) for the log messages.

## The store

The store is a RocksDB database in `engine.data_dir` (`/var/lib/syncd/engine` on a packaged
install), with LZ4 compression and a shared block cache of `engine.cache_mb`.

| Column family | Key | Value |
|---|---|---|
| `meta` | `attr/<id>`, `source/<id>`, settings | Attribute and source registries, the store format, the running configuration version, full-sync progress, when the last scheduled backup succeeded |
| `staging/<source id>` | anchor | Staged record |
| `entities/<view>` | entity id | Merged entity |
| `links` | source id and anchor | Entity id |
| `exported/<publication>` | entity id | Last exported entry, with the entry's target id (`syncd-target-id`) when known, and the leaver clocks (`syncd-left-at`, `syncd-stripped`, `syncd-disabled`) |
| `checkpoints` | source name | Opaque source position; for an LDAP object whose containers are checked, also the containers' DNs (`<connector>/<object>#containers`) |
| `unique/<scope>` | claimed value | Entity id |
| `permanent/<view>/<attribute>` | normalized key | The entity that has (or last had) the key of a `permanent` computed attribute, and its values. Kept when the entity is deleted, so an entity that comes back with the key gets them again |
| `quarantine/<view>` | entity id | The rule with `on_error: quarantine` that failed, its error, and since when |
| `quality/<view>` | entity id | The data-quality checks the entity fails, their messages (never a value), and since when |
| `held/<publication>` | entity id | A planned change not applied: held by a window, a threshold or a canary, or what a report-mode publication would do |
| `timers` | due time, kind and key | Nothing: a persisted timer, in due order |
| `timer_keys` | kind and key | The timer's due time, so arming it again replaces it |
| `group_lifecycle/<publication>` | group id | When the engine created the computed group, last reviewed it, and when it expired |
| `group_manual/<publication>/<group>` | member DN | A member of a computed group kept although its filter does not produce it: when first and last seen |
| `unjoined/<view>` | source id and anchor | When the record became unjoined, for the age of the unmatched queue |
| `containers/<target>` | normalized DN | A container the writer created, for `remove_empty_containers` |
| `dlq/<publication>` | entity id | Last failed export of that entity |
| `pinned_joins` | source and anchor | Entity an operator joined the record to |
| `exclusions` | link key and entity id | A record an operator kept from joining that entity |
| `history` | entity id, time, sequence | The entity's change history (`engine.history`) |
| `password_queue` | received time and a random suffix | A sealed password change waiting for delivery, and the publications it was delivered to |
| `password_sequences` | source, object type and anchor | The sequence of the last password change delivered for the account |
| `password_agents` | agent name | The agent's enrollment token (its SHA-256), certificate fingerprint, revocation and activity |

Every write goes through one batch that commits atomically, together with any attribute
names first seen in it. A crash cannot leave a record that refers to an unknown attribute,
or a checkpoint ahead of the data it covers.

The layout has a version, the **store format**. A newer release that changes it migrates the
store when it first starts; an older release refuses a store written by a newer one. Neither
0.4.0 nor 0.4.1 changes the format (1). 0.4.0 it adds the column families above that 0.3.0 did not have, on first
start. 0.3.0 opens a store that has them, but ignores what they hold (timers, held changes,
quality results, group lifecycles). See [Installation](install.md) for upgrades and rollbacks.

History keeps at most `engine.history.keep_per_entity` entries per entity (100 by default) for
`keep_days` (30 by default). Since 0.4.0 the newest `min_per_entity` entries of each entity (10 by
default) stay past `keep_days`. Every entry of an entity with an open held change or a dead letter
stays until it clears: past `keep_days`, and from 0.4.1 past `keep_per_entity` too (0.4.0 still
cut such an entity's entries to `keep_per_entity`). The prune runs every hour, or at once
with `syncd history prune --now`.

The data directory also holds the password key and the agent CA (`password.key`,
`password-agent-ca.key`, `password-agent-ca.crt`; the two keys sealed when a passphrase is set), and, while they are in use, `backups/` (a
store checkpoint while a backup is taken), `restore/` and `replaced-<time>/` (left by
`syncd restore`), and `ha/` (a snapshot received from the active engine, and the swap's
progress).

## Checkpoints and the commit protocol

```mermaid
sequenceDiagram
    participant S as Source
    participant L as Run loop
    participant T as LDAP target
    participant R as RocksDB

    S->>L: changes, then Checkpoint(position)
    L->>L: stage, correlate, merge, compute, plan
    L->>T: apply planned operations
    T-->>L: results
    L->>R: one batch: staged records, entities, exported state,<br/>dead letters, checkpoint
    R-->>L: committed
    L-->>S: acknowledge position
    S->>S: confirm upstream (e.g. PostgreSQL LSN)
```

- A streaming source acknowledges a position upstream only after the batch holding it has
  committed. After a crash the engine resumes from the last committed checkpoint and
  replays what followed. Exports are applied before the commit that records them, so a
  replayed batch exports again rather than losing anything.
- Commits are grouped: a batch is flushed at 5 000 pending writes or 2 000 entity changes,
  and a checkpoint waits at most 50 ms for a group commit while events keep arriving.
- Exports go out only while the engine may write: the engine of an HA pair checks its lease
  before every export batch (see [High availability](#high-availability)).
- A restart does not re-read the sources. The engine rebuilds its in-memory indexes from the
  store (in parallel) and continues from its checkpoints.

## The run loop and long operations

The run loop handles source changes, timers and commands one at a time. These are **long
operations** that keep it busy:

| Operation | When |
|---|---|
| Full sync | `syncd full-sync`, the console's run of that kind, and every configuration reload. It recomputes every entity a page at a time (8 192 entities), then reconciles |
| Reconciliation | Every `engine.reconcile_interval_secs` (by default `engine.scrape_interval_secs`, one hour), and on demand. It plans every entity of every publication against what was exported |
| A full read's deletes | When a scrape or delta full read finds records gone, applied a page at a time |
| Configuration reload | `ApplyConfig` (publishing from the console, or `syncd apply-config`) |

While one runs, source changes wait (they are not lost), and a command waits up to 10
seconds and is then refused as busy, naming what the engine is doing. Status always
answers. A stop request is checked between pages, so the engine stops within a page of
work. A full sync that is interrupted records how far it got and resumes at the next start.

The metrics `syncd_engine_busy` and `syncd_engine_busy_seconds` show a long operation. See
[Operator guide](operator-guide.md) and [Troubleshooting](troubleshooting.md).

### Reloading a configuration

`ApplyConfig` compiles the new YAML, saves it over the configuration file (so the engine needs
write access to it), reloads the engine in-process, and runs a full sync. If the new
configuration does not start, the previous file is put back. `engine.data_dir` and the `api`
section can only be changed on the engine host; a published configuration that changes them
is refused.

## The dead-letter queue

- An export that fails is recorded in `dlq/<publication>` with the error, one entry per
  entity (the latest failure), and is not recorded as exported.
- The next change to the entity, or the next reconciliation, exports it again. Operators
  can retry dead letters now, or discard one (a later change or reconciliation still exports
  the entity).
- When a target is unreachable, every export to it fails at once into the queue rather than
  waiting on a dead connection. The engine tries a new connection on a backoff (one second,
  doubling to a minute). `syncd_target_up` and `syncd_publication_dead_letters` show this.
- The writer converges on the common drift cases instead of failing: an entry that already
  exists is updated in place, a delete of a missing entry succeeds, and a value-level modify
  that no longer fits the live entry is retried as a replace.

## Reconciliation and drift

- **Reconciliation** compares every entity with its exported state and fixes the target
  where they differ. It works from the store's record of what was exported, not from the
  target.
- **Drift detection** (a publication's `drift` section, every `interval_secs` or with
  `syncd check-drift`) reads each exported entry back from the target and compares the
  attributes the engine manages with what it last wrote. Per attribute, the policy is `flag`
  (report it), `revert` (write the engine's value back) or `accept` (keep the target's value
  until the engine computes a different one). Missing entries are flagged or reverted. An
  entry moved by hand is reported as missing; reverting it moves it back by its target id
  rather than adding a second one.
- **Adoption** (`adopt`) takes over entries the target already has instead of adding
  duplicates. It compares DNs as DNs, not as text.

## Writeback

A publication's `writeback` covers an outage of an LDAP target. While the engine cannot reach
the target, exports to it are held (they go to the dead-letter queue). When it answers again,
the engine reads the target's change log (OpenLDAP access log, or `cn=changelog`) for exactly
the outage, and writes modifications of the listed attributes back to the LDAP source the
entry came from. An attribute the source also changed meanwhile keeps the source's value and
is reported as a conflict. Then the hold is lifted and the dead letters are retried. The
outage's start is kept in the store, so a restart part way through still writes the changes
back. See [Connectors](connectors.md).

## Simulations and previews

- **Simulate** (`SimulateConfig`) dry-runs a candidate configuration on a hard-linked copy of
  the store, through a reload's own steps, with recorders in place of the targets. It reports
  what each target would receive. One runs at a time.
- **Preview** (`PreviewConfig`) runs a candidate over a sample of source records in a scratch
  store, and returns the entities it makes and what each publication would write.
- **Connector checks** (`TestConnector`, `DiscoverSchema`) connect with one connector of a
  candidate configuration and list what it holds. They write nothing.

None of these writes to a target or to the engine's state.

## High availability

From 0.4.0, two engines with the same configuration can run as an **active/passive pair**
(`engine.ha`, or the `SYNCD_HA_*` variables). Without it, an engine runs on its own as before:
one engine per store.

```mermaid
flowchart LR
    subgraph A["Engine idm1 (active)"]
        A1["Sources and exports"]
        A2[("Store")]
        A3["Snapshot every<br/>snapshot_every_secs"]
    end
    subgraph B["Engine idm2 (passive)"]
        B1["Control API and metrics<br/>no sources, no exports"]
        B2[("Store<br/>(swapped in at takeover)")]
    end
    L[("Lease<br/>syncd_lease row in PostgreSQL,<br/>or a file")]
    T[("Sources and targets")]
    A1 <--> T
    A1 -->|"renew every TTL/3"| L
    B1 -.->|"read: who is active?"| L
    B1 -->|"PullSnapshot, mTLS"| A3
    A3 --- A2
    B1 --> B2
```

- **The lease.** The engine that holds it is active and runs the sources and exports. In
  PostgreSQL it is one row per pair in `syncd_lease`, taken or renewed by a single statement
  that succeeds only for its holder or over an expired row, with expiry judged by the database's
  clock (the console's database works; its migration creates the table). The `file` backend
  keeps it in a file on a shared file system, under a lock, judged by the hosts' clocks. The
  holder renews every third of `ttl_secs` (15 by default) and gives the lease up on a clean stop.
- **The passive engine** serves its control API and metrics on its own store, runs no source,
  writes nothing, and refuses commands with the active engine's name. It keeps a warm copy: it
  pulls the active engine's newest **snapshot** (`PullSnapshot`, which needs what a reveal needs)
  every half interval. A snapshot is a backup archive of the store checkpoint, the key material
  and the configuration file, checked against its manifest when received.
- **Fencing.** Every export batch of every target kind and every password delivery asks first
  whether this engine holds the lease with more than a second to go. The lease's time is counted
  from when the renewal was sent, so a slow answer cannot stretch it. An engine that cannot renew
  stops writing before the other may take over, and steps down to passive when it finds the
  lease gone. A refused batch is neither written nor recorded, so the engine that takes over
  plans it again.
- **Takeover.** When the lease expires, the passive engine takes it, swaps the newest snapshot
  in for its store (in recorded steps under `ha/`, so a crash part way is rolled back or
  finished at the next start), reconciles every publication, and starts the sources from the
  snapshot's checkpoints. They re-read what the old engine read after the snapshot; a
  PostgreSQL logical replication stream, which cannot go back past what its slot confirmed,
  reads its tables in full first. The reconciliation finds what the old engine already exported
  (an LDAP entry already there is updated in place, a SCIM or Entra ID resource adopted).
- **The console** shows the two engines of a pair as one card, with the active one's status,
  and sends the `ha.failover` webhook once, from the engine that took over.

There is no etcd or Kubernetes Lease backend, each snapshot is a full archive rather than a
delta, and password changes the old engine queued after its last snapshot are not delivered
again after a failover. See [Availability and sizing](availability-and-sizing.md) and
[Installation](install.md).

## Backups

`syncd backup --to <directory | s3://bucket/prefix>` asks the running engine for a backup
(`Backup`, which needs what a reveal needs): a RocksDB checkpoint of the store (hard-linked, so
consistent at one instant while the engine goes on writing), the configuration file, the
password key and the agent CA, streamed as one `tar.zst` with a manifest of every file's size and
SHA-256. Nothing is staged on the engine's disk but the checkpoint. `engine.backup` takes one on a
schedule (`every_secs`, `keep`). S3 is reached with syncd's own SigV4 client on the process's
crypto provider, with optional server-side encryption (`AES256`, `aws:kms`).

`syncd restore` (with the engine stopped) verifies every file against the manifest, refuses a
store format newer than it reads, and puts the backed-up configuration beside the live one as
`engine.restored.yaml`, never over it. `syncdctl backup --to ...` adds the console's database and
`/etc/syncd`. See [Installation](install.md) and [Security](security.md#backups).

## The console

```mermaid
sequenceDiagram
    participant B as Browser or pipeline
    participant N as nginx
    participant C as syncd-console
    participant P as PostgreSQL
    participant H as Change hook
    participant E as syncd

    B->>N: POST /api/v1/engines/1/configs/7/publish<br/>{ticket, note}, cookie or bearer token
    N->>C: proxy (X-Forwarded-For)
    C->>P: session or token, role (and scope)
    C->>C: ticket pattern? approved by someone else, if required?<br/>test cases pass?
    C->>C: render the version for the engine's environment
    C->>H: config.publish-requested, signed (when a hook is set)
    H-->>C: allow, or deny with a reason
    C->>P: audit config.publish.start (must be written)
    C->>E: ApplyConfig(yaml, "v7") over mTLS
    E->>E: compile, save, reload, full sync
    C->>E: GetStatus until config_version = v7
    C->>P: mark v7 published, audit config.publish,<br/>queue config.published webhooks
    C-->>B: 200
```

The console server is one process. It listens on `CONSOLE_LISTEN` (`127.0.0.1:8080`; `0.0.0.0:8080`
in the container image):

- **over plain HTTP** by default, with nginx (or a Kubernetes ingress) in front of it to
  terminate TLS, as in the diagram above;
- **over HTTPS itself** when `CONSOLE_TLS_CERT` and `CONSOLE_TLS_KEY` name a PEM certificate
  (with its chain) and key. TLS is rustls on the process crypto provider, TLS 1.2 and 1.3,
  HTTP/1.1. Each handshake runs in a task of its own with a 10-second limit, so a client
  that stalls holds up no other. The files are checked every minute and loaded again when
  they change, so a renewal (certbot, cert-manager) needs no restart; a renewal that does
  not load is logged and the previous certificate kept. A certificate that does not load at
  startup stops the console. See [Security](security.md#https-for-the-console).

It serves:

- the REST API at `/api/v1`, the audit pull API at `/api/audit/events`, and the OpenAPI
  document at `/api/openapi.json` (see [API reference](api-reference.md));
- `GET /healthz`, which answers `ok`;
- the UI's static files, with `index.html` for the UI's own routes, including the public
  self-service password page (`/password`).

### Background tasks

The console process also runs these tasks. Each keeps its state in the database, so a restart
resumes where it was, and several consoles on one database share the work without doing it twice
(rows claimed with `FOR UPDATE SKIP LOCKED`, or an advisory lock):

| Task | Does | See |
|---|---|---|
| Status watcher | Reads every engine's status every 10 seconds; raises webhook events on each change; keeps the data-quality daily rollup (`quality_daily`) | [Webhooks](webhooks.md) |
| Webhook sender | Posts queued deliveries, signed, with retries for a day | [Webhooks](webhooks.md) |
| Audit outputs | One sender per output (stdout, file, Splunk HEC, syslog), each from its own cursor; the daily retention prune | [Audit trail](#the-audit-trail) |
| Git sync | Polls the repository, turns new commits into configuration versions, pushes console versions back | [Configuration as code](#configuration-as-code) |
| Scheduled reports and mail | Runs reports that are due, writes them to a share or queues them in the mail outbox, and sends the mail over SMTP with STARTTLS | [Reports, lineage and data quality](reports-and-data-quality.md) |
| Housekeeping | Deletes ended sessions and old single sign-on requests every ten minutes; logs the audit chain's head every hour | |

### The console database

PostgreSQL 13 or newer. The console applies its migrations (`console/server/migrations`) every
time it starts. 0.4.0 takes the schema from 8 to 17.

| Table | Holds | Migration |
|---|---|---|
| `users` | Accounts: username, display name, role, password hash (PHC string, or none for single sign-on accounts), forced change, disabled, failed sign-ins, lock time, OIDC subject, break-glass flag | `0001_init`, `0004_oidc`, `0005_local_sign_in` |
| `sessions` | The SHA-256 of each session token, its account, last activity and end | `0001_init` |
| `audit_log` | The append-only, hash-chained audit trail in `ens-audit/1`: `seq`, the event's fields, `prev_hash` and `hash`. Triggers refuse `UPDATE`, `DELETE` and `TRUNCATE`, except the retention prune | `0001_init`, `0009_audit_ens` |
| `audit_output_cursor` | How far each audit output has delivered, and its last error | `0009_audit_ens` |
| `engines` | Registered engines: name, `host:port` or `unix:<socket path>`, the server CA and client certificate, the client key sealed with the master key, and the environment | `0002_engines`, `0013_environments` |
| `engine_configs` | Configuration versions per engine: YAML, comment, author, source (`console`, `git`, `promotion`), publication, approval, pending publish, ticket, note and the hook's decision. A trigger refuses changes to a saved version; a constraint refuses an approval by the author; another trigger makes an approval final | `0003_engine_configs`, `0006_config_approval`, `0007_publish_pending`, `0012_publish_hook`, `0013_environments` |
| `oidc_logins` | Single sign-on requests in flight: state, nonce, PKCE verifier, where to go next | `0004_oidc` |
| `settings` | One row: who may sign in locally, configuration approval, the banners, and the installation id of the audit trail | `0005_local_sign_in`, `0006_config_approval`, `0008_banners`, `0009_audit_ens` |
| `api_tokens` | API tokens: the SHA-256, a display prefix, scopes, creator, expiry, last use, revocation | `0010_api_tokens` |
| `webhook_endpoint`, `webhook_delivery`, `webhook_engine_state` | Webhooks (the URL and signing secret sealed with the master key), their deliveries, and what the status watcher last saw of each engine | `0011_webhooks` |
| `environments` | Each environment's values; secret ones sealed with the master key | `0013_environments` |
| `config_git_state` | Git sync's last commit, sync time and error | `0014_config_git` |
| `scheduled_reports`, `mail_outbox` | Scheduled reports, and mail waiting to be sent | `0015_reports` |
| `syncd_lease` | The HA lease of each engine pair, when the console's database holds it | `0016_syncd_lease` |
| `quality_daily` | Per engine, view and check, the day's count of failing entities | `0017_quality_daily` |

At startup, and then every hour when it has changed, the console writes the head of the audit
chain (the newest entry's id, time and hash) to its own log, as `audit log head`. That copy
lives outside the database, so a verification against it finds entries removed from the end
of the log. See [Security](security.md#audit-trail).

Also at startup, the console checks its master key against a secret the database holds (an
engine's sealed client key). A missing key file is not replaced by a new key while such
secrets exist, and a key that does not open them is refused: the console does not start
unless `CONSOLE_NEW_MASTER_KEY=true`. See
[Security](security.md#console-secrets-and-the-master-key).

### The engine control API

The console holds one gRPC client per registered engine. An engine off loopback must be
registered with mTLS (the engine's CA, and a client certificate and key); plain text is
allowed only to a loopback address, or to the engine's control socket (`unix:/run/syncd/control.sock`)
when the console runs on the engine's host as a user that may open it. The installer registers
the local engine at `127.0.0.1:7443` with mTLS, using certificates from a CA of the host's own;
`syncdctl renew-certs` issues new ones and updates the registration
(`syncd-console admin register-engine --update`). On Kubernetes the Helm chart issues the
certificates and a Job registers the engine (both engines of an HA pair). See
[Security](security.md#the-engine-control-api).

The console reads each mTLS client certificate's expiry and returns it with the engine
(`certificate_expires_at`); the engine's page warns from 60 days before it.

Each operation the console proxies (runs, approvals, drift checks, dead-letter actions, joins
and unjoins, group reviews, publishes) is checked against the caller's role (and an API token's
scope) and recorded in the audit trail with its outcome.

### The live status stream

`GET /api/v1/engines/{id}/status/stream` is a server-sent events stream. The console opens the
engine's `WatchStatus` gRPC stream at one-second intervals and passes each snapshot on as a
`status` event; an engine that goes away ends it with one `error` event. Every 30 seconds the
console checks that the session is still valid (not signed out, expired, idle, or disabled)
and ends the stream otherwise. The shipped nginx configuration turns off proxy buffering and
keeps the stream open for up to an hour.

Each status also carries the engine process's CPU and memory (`process`, on Linux). The console
computes CPU in use from the CPU time over the last five statuses, as a share of the CPUs the
engine may use, and shows it with memory (against the cgroup's limit, when there is one) on the
engines dashboard and each engine's metrics page.

### Configuration as code

- **Rendering.** A configuration may hold `{{ env.<name> }}` placeholders (with an optional
  `| default`). A value that is one placeholder takes the environment value's type. The engine
  renders them from an environment file (`--env`, `SYNCD_ENVIRONMENT`); the console renders a
  version for each engine's environment (its **Environments**, with secret values sealed with
  the master key) when it publishes it, and with secret values masked everywhere it shows one.
  A value the environment lacks refuses the publish, naming it.
- **Semantic diff.** `syncd config diff` and the console's Compare tab tell what changed by
  meaning (per connector, view, rule and publication field), with secrets redacted.
- **Git sync.** The console clones the repository under its data directory (`git/`) with the
  `git` binary and polls it. Each new commit that touches the configuration file or the
  environment files becomes a version on each engine the repository is for, then is left as a
  draft, validated, or published through the usual gates (`CONSOLE_CONFIG_GIT_AUTO_PUBLISH`).
  Secret values in environment files are refused. With push-back on, versions saved in the
  console are committed back. Credentials (an SSH deploy key, or a token sent as an HTTP header)
  are given to git through its own configuration, never on the command line.
- **Promotion** publishes a version proven in one environment to every engine of another,
  rendered for each, through each engine's gates.

See [Configuration as code](config-as-code.md).

### The audit trail

Every event is one row of `audit_log` and one JSON object in the ENS family's format,
`ens-audit/1`, hash-chained with a gapless `seq`. It is stored first; then one sender per
output delivers it from its own cursor, at least once and in order, so an output that is down
holds up nothing and loses nothing.

```mermaid
flowchart LR
    REQ["A request or a task<br/>(sign-in, publish, export, ...)"] --> AL[("audit_log<br/>ens-audit/1, hash chain")]
    AL --> SO["stdout<br/>(journal, pod log)"]
    AL --> FI["Rotated file"]
    AL --> SP["Splunk HEC"]
    AL --> SY["syslog<br/>RFC 5424, UDP, TCP or TLS"]
    AL --> PULL["Pull API<br/>GET /api/audit/events"]
    AL --> EXP["NDJSON export"]
```

Events from before 0.4.0 keep their earlier chain; the first new event continues its head.
`ENS_AUDIT_RETENTION_DAYS` prunes only what every enabled output has delivered. See
[Security](security.md#audit-trail) and [Audit logging](../audit-logging.md).

## Running on Kubernetes

The release workflow publishes two container images and a Helm chart when a release is tagged
(`v*`). No release has been tagged yet, so none is published so far: build the images yourself
from the source (`docker build -f deploy/container/Dockerfile --target engine`, or
`--target console`; `--build-arg FEATURES=syncd/fips,syncd-console/fips` for the FIPS variant)
and install the chart from `deploy/helm/syncd`.

| Artifact | Where a tagged release publishes it |
|---|---|
| Engine image | `ghcr.io/winllc/syncd:<version>`, and `ghcr.io/winllc/syncd:<version>-fips` |
| Console image | `ghcr.io/winllc/syncd-console:<version>`, and `ghcr.io/winllc/syncd-console:<version>-fips` |
| Helm chart | `oci://ghcr.io/winllc/charts/syncd`, the same version as the images |

Both images are built from one Dockerfile (`deploy/container/Dockerfile`) on
`gcr.io/distroless/cc-debian12:nonroot`, pinned by digest: no shell, no package manager, running
as uid and gid 65532. The console's image also carries `git` and `ssh` for Git sync. A tag's
images are built for `linux/amd64` and `linux/arm64` (arm64 only when the repository's arm64
runners are enabled), the `-fips` images for amd64 only. All of them, and the chart, are signed
with cosign; see [Security](security.md#supply-chain).

```mermaid
flowchart LR
    BR["Browser"] -->|HTTPS| ING["Ingress<br/>(your controller, TLS)"]
    ING -->|"HTTP, or HTTPS with console.tls"| CON["Console Deployment<br/>syncd-console, port 8080"]
    CON --> PG[("PostgreSQL<br/>(yours)")]
    CON -->|"gRPC, mTLS, port 7443"| ENG["Engine StatefulSet<br/>1 replica, or 2 with engine.ha"]
    ENG <--> PVC[("Volume per pod<br/>/var/lib/syncd")]
    ENG -.->|"HA lease"| PG
    JOB["Registration Job<br/>(post-install, post-upgrade)"] --> PG
    PROM["Prometheus"] -->|"/metrics, port 9464"| ENG
    ENG --> SRC[("Sources and targets")]
```

Resource names below are for a release named `syncd`. The chart names them `<release>-syncd-…`,
or `<release>-…` when the release name contains `syncd`.

| Part | What the chart makes |
|---|---|
| Engine | A StatefulSet with one replica: one engine per store. Its volume (`data-syncd-engine-0`) holds the store (`/var/lib/syncd/engine`) and the configuration file it runs (`/var/lib/syncd/engine.yaml`). The file starts as a copy of the image's (`SYNCD_INITIAL_CONFIG`), and the console replaces it when it publishes. The image fixes the `engine` and `api` sections: the control API on `0.0.0.0:7443` with mTLS, the control socket at `/run/syncd/control.sock` (an in-memory volume), metrics on `0.0.0.0:9464`. Secrets the configuration refers to come from a Secret you name (`engine.envFromSecret`) |
| HA pair | With `engine.ha.enabled`: two replicas started in parallel, a headless Service naming each pod, engine certificates naming them, the lease in the console's database, a soft or hard anti-affinity, a PodDisruptionBudget, and the registration of both pods |
| Console | A Deployment (`console.replicas`, 1 by default) on port 8080, plain HTTP, or HTTPS with `console.tls.existingSecret`, with an optional Ingress. Its data directory is an `emptyDir`: the master key comes from a Secret |
| Certificates | The `syncd-pki` Secret: a CA of the chart's own, the engine's server certificate (named for its Service, and for each pod of an HA pair) and the console's client certificate, ECDSA P-256, issued by Helm at install (`pki.days`, ten years by default), kept across upgrades and when the release is uninstalled |
| Console secrets | The `syncd-console` Secret: the master key (32 random bytes) and the initial admin password, generated at install, kept across upgrades, and kept when the release is uninstalled |
| Registration | A Job after each install and upgrade runs `syncd-console admin register-engine --update` with the chart's certificates, and with `console.env` |
| Single sign-on | `console.oidc.*` and `console.publicUrl`; see [Single sign-on](single-sign-on.md#on-kubernetes) |
| Monitoring | An optional `ServiceMonitor` (its targets' job label is `engine.metrics.job`, `syncd`) and an optional PrometheusRule with the shipped alert rules (`engine.metrics.prometheusRule.enabled`) |
| NetworkPolicies | Optional (`networkPolicy.enabled`): 7443 from the console and the registration Job only, 9464 from your monitoring namespace, 8080 from your ingress controller; egress open unless `networkPolicy.egress.enabled` |
| Extra volumes | `engine.extraVolumes` and `console.extraVolumes` with their mounts: a CSV inbox, a CA bundle |

Probes: the engine's startup and readiness probes connect to the control API port, and its
liveness probe fetches `/metrics`; the console's probes fetch `/healthz`. The PostgreSQL server
for the console is yours; the chart does not bundle one. Upgrades and rollbacks are `helm
upgrade` and `helm rollback`; a store or database that the newer release migrated must be
restored from backups before rolling back. A pair on Kubernetes is not installed or failed over
by any test yet: CI only renders the HA templates. See [Installation](install.md) and
[Security](security.md#container-images-and-the-helm-chart).

## Tech stack

| Layer | Technology |
|---|---|
| Engine | Rust 1.97 (edition 2024), Tokio, mimalloc |
| Engine store | RocksDB (`rocksdb` crate 0.25, LZ4), embedded; no external broker |
| Rules | CEL syntax (parsed by the `cel` crate), compiled into closures by syncd's own compiler |
| Database sources | `tokio-postgres` and `pgwire-replication` (PostgreSQL, with syncd's own `pgoutput` decoder), `mysql_async` (MySQL and MariaDB binlog), `tiberius` (SQL Server), the MongoDB Rust driver; Debezium Server over HTTP; a JDBC bridge in Java 17 over gRPC for other databases |
| File sources | The `csv` crate, with syncd's own delimiter sniffing and encodings (UTF-8, Latin-1, UTF-16) |
| Directory sources and targets | `ldap3` 0.12 with syncd's DirSync and syncrepl controls; target profiles for Active Directory, OpenLDAP, 389 Directory Server and Ping/UnboundID |
| SaaS targets | SCIM 2.0 and Microsoft Graph (Entra ID) clients of syncd's own on `reqwest`, with bearer, basic or OAuth 2.0 client-credentials authentication |
| Password synchronisation | HPKE (RFC 9180) implemented by syncd on aws-lc-rs primitives; agent certificates with `rcgen` on aws-lc-rs |
| Backups | `tar` over zstd; S3 through syncd's own SigV4 client (no AWS SDK) |
| Control API | gRPC (`tonic` 0.14, `prost`), mTLS through rustls |
| TLS and cryptography | rustls 0.23 with aws-lc-rs; optional FIPS build with the AWS-LC FIPS module |
| Metrics | Prometheus text format from the engine's own HTTP listener; alert rules shipped |
| Console server | Rust, Axum 0.8, `sqlx` 0.8 on PostgreSQL 13+, `tokio-rustls` (its own HTTPS), `utoipa` (OpenAPI), `jsonwebtoken` and `reqwest` (OpenID Connect, webhooks, Splunk), `argon2`; its own SMTP client; the `git` binary for Git sync |
| Console UI | React 19, TypeScript, Vite, TanStack Router and Query, Tailwind CSS 4, Monaco editor, React Flow (`@xyflow/react`), `openapi-fetch` with types generated from the OpenAPI document |
| Packaging | systemd units; `.tar.gz` bundle (x86_64 and aarch64, and an x86_64 FIPS bundle), `.deb` and `.rpm` (nfpm), RHEL 8 `.rpm`; `syncdctl` (bash) |
| Containers | Images on `gcr.io/distroless/cc-debian12:nonroot` pinned by digest (`linux/amd64`, `linux/arm64`, and amd64 FIPS), built from `rust:1.97.0-bookworm` and `node:22-bookworm-slim`; a Helm 3 chart (`deploy/helm/syncd`), published to GHCR as an OCI chart |
| Front proxy | Optional: the console serves HTTPS itself, or behind the shipped nginx configuration or a Kubernetes ingress |
| CI | GitHub Actions: fmt and clippy, tests, the console UI with a Playwright smoke test and a Keycloak sign-in, end-to-end suites per source and target (with fake SCIM, Graph and S3 services), password synchronisation, HA and backup, golden-file tests, benchmarks, fuzzing, the FIPS build, `cargo-deny`, `cargo-audit`, CycloneDX SBOMs, package lifecycle tests, Trivy scans of the images, and the chart installed, upgraded and uninstalled in a kind cluster. A release signs `SHA256SUMS` with GPG and the images and chart with cosign |

## Code layout

This section describes the source code, for engineers reviewing the product. You do not need
the source to install or run syncd: install it from a release, as described in
[Installation](install.md).

| Path | Contents |
|---|---|
| `engine/bin/syncd` | The daemon and command line (`run`, `status`, `reconcile`, `full-sync`, `dlq`, `held`, `quarantine`, `quality`, `approve`, `retry`, `discard`, `apply-config`, `check-drift`, `test-connection`, `discover`, `simulate`, `config diff`, `config render`, `config promote`, `test`, `report`, `lineage`, `history`, `group`, `correlation`, `password`, `backup`, `restore`, `support-bundle`, ...), the run loop, HA and scheduled backups |
| `engine/bin/syncd-fakes` | Fake SCIM, Graph and S3 services for the tests |
| `engine/crates/api` | The control API server and client, the password ingest service, the status board, the explorer, reports, the metrics listener, process statistics |
| `engine/crates/backup` | Backup archives, destinations (directory, S3 with SigV4), restore |
| `engine/crates/config` | The YAML configuration model, its validation, rendering for an environment, the semantic diff and secret redaction |
| `engine/crates/core` | Values, records, attribute maps, attribute-level diffs |
| `engine/crates/rules` | The CEL compiler, function library, rule packages and computed-attribute transforms |
| `engine/crates/store` | The RocksDB store and its format migrations |
| `engine/crates/engine` | Correlation and suggestions, merge, computed attributes, data quality, groups and their lifecycle, export planning, change safety, timers, leaver stages, drift, writeback |
| `engine/crates/connectors` | Source connectors (CSV and Entra ID included), the LDAP, SCIM and Entra ID targets, connector probes |
| `engine/crates/ha` | The HA lease (PostgreSQL, file), the fence, the controller, snapshots |
| `engine/crates/password` | The password key, HPKE, envelopes, the agent CA and tokens, self-service requests and policy, generated passwords |
| `engine/crates/wire` | Decoders of `pgoutput`, DirSync and syncrepl (with fuzz targets) |
| `engine/crates/crypto` | The crypto provider and FIPS mode |
| `console/server` | The console server and its migrations |
| `console/web` | The console UI, its OpenAPI types and its browser tests |
| `proto/syncd` | gRPC definitions: `v1/engine.proto` (the control API and the password ingest service) and `bridge/v1/jdbc.proto` (the JDBC bridge) |
| `bridge/jdbc` | The JDBC bridge (Java, Gradle) |
| `deploy/packaging` | The release build, systemd units, `syncdctl`, nginx configuration, alert rules, package tests |
| `deploy/container` | The Dockerfile of both images, and the engine's initial configuration in its image |
| `deploy/helm/syncd` | The Helm chart |
| `tests/helm` | The chart's test in a kind cluster, and a values file with every production option |
| `tools/audit` | `verify_chain.py`, the ENS family's audit chain checker |
| `docs/adr` | Architecture decision records |
