# Reports, lineage and data quality

This page covers the evidence syncd gives operators and auditors, and the tools that keep bad
data out of the targets. All of it is new in syncd 0.4.0; this page describes syncd 0.5.0
(`main` at commit `a9d17c6`), which adds unmanaged entries, Excel exports, PDFs with an embedded
font and tagging, an hourly quality trend and a wider correlation search.

- **Reconciliation reports**: what each publication holds, what the last drift check found, what
  is held or dead-lettered, and (from 0.5.0) the target's unmanaged entries, as text, CSV, JSON,
  Excel or PDF, on demand or on a schedule.
- **Lineage export**: one person's whole story, as JSON or PDF.
- **History retention**: how much entity history the engine keeps, and what it never prunes.
- **Data-quality checks**: what good source data is, checked on every entity.
- **Correlation assistance**: scored suggestions for source records that joined no entity.

The audit trail itself is in the [Admin guide](admin-guide.md). From 0.5.0 its **Audit log** page
filters by time, category, action, actor and outcome (server-side), and exports what it lists as
CSV (`GET /api/v1/audit/csv`, at most 100,000 rows, recorded as `data.exported` with the
filters); the NDJSON export remains the one to check the chain with. Held changes, leaver stages
and group lifecycle, which the report counts, are in
[Change safety and lifecycle](change-safety.md).

## Reconciliation reports

The report is the reconciliation picture as the running engine knows it. **It reads nothing from
the targets**: it gives the last drift check's result and when it was made. Run
`syncd check-drift` first for a fresh one.

- **One summary row per publication**: entities in the view, entries exported, the last drift
  check (when, entries read, drifted, missing, reverted, accepted, flagged, or why it stopped),
  dead letters, held changes by reason, leavers by the stage ahead of them, what a report-mode
  publication would do, the computed groups (with manual members, expired, reviews due) and when
  the publication was last reconciled in full. From 0.5.0 also the last unmanaged-entries pass
  (when, unmanaged and ignored entries, unaccounted and ignored privileged members, why it
  stopped) and the **adopted** count: entries the engine found in the target and took over rather
  than created.
- **One detail row per entry with a difference**, of one of these kinds: `drifted` and
  `missing` (from the last drift check: the DN and the attributes that differ, with their values
  except for sensitive attributes, which are named only), `held` or `would_do` (a held change),
  `dead_letter`, and from 0.5.0 `unmanaged` (an entry no publication exported, with its creation
  time, last sign-in and state) and `unmanaged_privileged` (a member of a privileged group that
  no rule accounts for, with the group). Each has its `since`.

### Unmanaged entries and unaccounted privileged members

New in 0.5.0. An auditor's question, "which accounts have no owner?", has its answer in the
report: per publication, the entries of the kind it manages that its target holds and no
publication exported (made by hand, older than syncd, service accounts), and the members of
privileged groups (`unmanaged.privileged_groups`, and computed groups with `manual_members:
report`) that no rule put there. Each entry comes with its creation time, its last sign-in where
the directory keeps one (the attribute is named: `lastLogonTimestamp` is a sign-in,
`pwdChangedTime` only a password change) and whether it is enabled. The list holds at most 200
entries and 200 members per publication; the counts are whole.

The report gives the last pass, which runs after each scheduled drift check (or `syncd unmanaged`,
or **Check now** on the console's **Unmanaged entries** page). How to set it up, adopt an entry
and act on the alerts is in [Unmanaged entries](operator-guide.md#unmanaged-entries). LDAP and
Active Directory targets have both lists; SCIM and Entra ID have entries only. 389 Directory
Server and PingDirectory were unit-tested only, and no real Entra tenant or SCIM provider was
used. There is no search from an unmanaged entry to the entities it may belong to.

### `syncd report`

```bash
syncd report                                          # every publication, as text
syncd report --publication people_to_ad --format csv --out people_to_ad.csv
syncd report --format json | jq 'select(.row == "summary")'   # one JSON object per line
```

| Format | Use |
|---|---|
| `text` (default) | Reading in a terminal |
| `csv` | Spreadsheets and scripts. RFC 4180, the same 40 columns as the console's CSV |
| `json` | Scripts. JSON lines: one object per row |

The CSV has one header row. The `row` column says whether a line is a publication's `summary` or
a `detail`, and the columns the other kind does not use are empty. 0.5.0 appends seven columns
after 0.4.1's 33 (the 34th to the 40th): `unmanaged_checked_at`, `unmanaged`, `unmanaged_ignored`,
`unmanaged_privileged`, `unmanaged_privileged_ignored`, `unmanaged_error` and `adopted`. Parsers
that read by position are unaffected; the release notes number them 33 to 40.

### Report CSV, Excel and PDF in the console

The **Report CSV**, **Excel** (new in 0.5.0) and **PDF** buttons on an engine's page download the
same report: `POST /api/v1/engines/{id}/reports/reconciliation/export?format=csv|xlsx|pdf[&publication=]`.

- **A `POST`, because it is audited.** Each export is recorded as `data.exported`, with the
  format, the publication and the row count, before a byte is sent. An export the trail cannot
  record is refused. Anyone who may view the engine may export (sessions, and API tokens with
  `engines:read`): it changes nothing but the trail.
- **Always a file**: CSV, an Excel workbook or a PDF, with `Content-Disposition: attachment` and
  `X-Content-Type-Options: nosniff`. The file is named `<engine>-<publication>-<date>.<ext>`.
- **Capped** at `CONSOLE_EXPORTS_MAX_ROWS` (200,000) rows. A capped download carries
  `X-Syncd-Truncated: true`.
- **Formula-injection guard.** In the CSV, a text cell that starts with a tab or a carriage
  return, or whose first character after any white space is `=`, `+`, `-` or `@`, is written with
  a leading apostrophe.
- **CSV** is UTF-8 with a byte-order mark (so Excel reads it as UTF-8), CRLF line ends, RFC 4180
  quoting and times in ISO 8601 UTC.
- **Excel** (`.xlsx`, new in 0.5.0) is a workbook of three sheets: **Report** (the engine, its
  environment, the publication, when and by whom, the banner's text when one is set, and a note),
  **Summary** (one row per publication) and **Entries** (one row per entry with a difference),
  each under a bold, frozen header row with a filter. Numbers are numbers and times are dates in
  UTC. Text is always written as text, never as a formula, so a value that starts with `=` shows
  as typed and is never run (the CSV's apostrophe is not needed). The console writes the file
  itself, without a spreadsheet library. The files were read back with a zip reader in testing,
  not opened in Excel or LibreOffice.
- **PDF** is landscape A4: a cover page (the engine, its environment, the publication, when and by
  whom), the summary table and the detail table, with the header repeated on each page. With a top
  [banner](admin-guide.md#banners) set, its text is printed in every page's header and footer.
  From 0.5.0 the text is Unicode in an embedded subset of DejaVu Sans Mono (Latin, Greek and
  Cyrillic with punctuation and symbols), so it can be searched and copied; a character outside
  it (Chinese, Japanese, Korean, among others) shows as U+FFFD, and there is no right-to-left text
  or shaping. The PDF is tagged (PDF 1.7, a structure tree in reading order with headings, tables
  and header cells, the language and the title; page furniture marked as artifacts), but claims
  no PDF/UA conformance: it was checked with qpdf and poppler, not with veraPDF, PAC, Acrobat or a
  screen reader. The lineage PDF is made the same way.

### Scheduled reports

The **Reports** page (`/reports`) e-mails the report, or writes it to a share, daily, weekly
(Monday) or monthly (the 1st), at an hour in UTC (`/api/v1/reports`). Administrators and
operators create, change, send now and delete their own (administrators everyone's); since 0.4.1
the page asks before it deletes one. Auditors can look. Sessions only: no API token manages
reports.

- A report names an engine, optionally one publication, the format (CSV, PDF, or from 0.5.0
  Excel), the schedule, up to 20 recipients, and/or a directory inside the share.
- Every five minutes (`CONSOLE_REPORTS_POLL_SECONDS`) one console looks for reports that are due,
  under a database lock, so a report goes out once however many consoles run.
- **Every run checks again** that the report's owner is still an active administrator or operator
  and that every recipient is inside the allowed domains. A run that fails a check sends nothing,
  and says why on the page and in the audit trail.
- A file holds at most `CONSOLE_REPORTS_MAX_ROWS` (50,000) rows and 15 MB. The e-mail says when it
  was cut.
- **Writing to a share:** the directory is relative to `CONSOLE_REPORTS_SHARE_DIR`, with no `..`,
  and links are resolved so it cannot point out of the share. The file is written atomically as
  `<engine>-<publication>-<date>.<csv|pdf|xlsx>`. On Kubernetes, mount the share with
  `console.extraVolumes` and `console.extraVolumeMounts`.
- Audited: `report.created`, `report.changed`, `report.deleted`, and `report.sent` with the
  recipients and the row count (a refused run is a failed `report.sent` with the reason).

!!! warning "Recipient domains are required"
    A report sends the engine's picture to the addresses typed, so **nothing can be scheduled to
    an address, or sent,** until `CONSOLE_REPORTS_RECIPIENT_DOMAINS` names your own domains
    (`example.gov`, or `*.mil` for a domain and its subdomains). E-mail also needs a mail server
    (`CONSOLE_SMTP_HOST`). The page says when either is missing.

### E-mail

The console speaks SMTP itself, with no mail library: EHLO, STARTTLS or TLS from the first byte,
AUTH PLAIN or AUTH LOGIN, MAIL, RCPT, DATA. TLS uses the console's own crypto provider, so FIPS
mode applies to it. Scheduled reports use it, and from 0.5.0 so do the mails to a group's
`reviewers` when its review falls due (see [Group lifecycle](change-safety.md#review)), within
the same `CONSOLE_REPORTS_RECIPIENT_DOMAINS`.

- **STARTTLS** by default: the connection is upgraded with STARTTLS (port 587 by default) and the
  server's certificate is checked against the public roots or `CONSOLE_SMTP_CA_FILE`.
- **SMTPS** (implicit TLS, new in 0.5.0): `CONSOLE_SMTP_IMPLICIT_TLS=true` speaks TLS from the first
  byte, with the same certificate check; set `CONSOLE_SMTP_PORT=465` too, since the port's
  default stays 587. `CONSOLE_SMTP_STARTTLS` is not used then.
- **AUTH PLAIN or AUTH LOGIN** (AUTH LOGIN new in 0.5.0), only over TLS: `CONSOLE_SMTP_AUTH` is
  `auto` (AUTH PLAIN when the server offers it or says nothing, else AUTH LOGIN), `plain` or
  `login`. The console refuses to start with a username and neither STARTTLS nor implicit TLS, so
  a password is never sent in plain text.
- `CONSOLE_SMTP_STARTTLS=false` (without implicit TLS) sends in plain text, for a relay on the same
  host or a private network only.
- Mail goes through an **outbox** in the console's database: written when the run decides to send,
  delivered by a sender, retried while the mail server is down (after a minute, doubling to an
  hour), and given up after a day. Giving up raises the [webhook](webhooks.md) event
  `report.failed`.
- Messages are `multipart/mixed`, with the report as a base64 attachment. Header values are kept
  to printable ASCII so no value can inject a header.

### Settings

Set these in `/etc/syncd/console.env`, or in `console.env` of the Helm chart's values. See
[Configuration](configuration.md) for the rest of the console's settings.

| Variable | Default | What |
|---|---|---|
| `CONSOLE_EXPORTS_MAX_ROWS` | `200000` | The most rows one download holds |
| `CONSOLE_REPORTS_MAX_ROWS` | `50000` | The most rows a scheduled report's file holds |
| `CONSOLE_REPORTS_RECIPIENT_DOMAINS` | empty | Domains a scheduled report may be e-mailed to, comma-separated. Required for e-mail |
| `CONSOLE_REPORTS_SHARE_DIR` | unset | A directory (a mounted share) reports may be written to. The console refuses to start if it is not a directory |
| `CONSOLE_REPORTS_POLL_SECONDS` | `300` | How often the console looks for due reports |
| `CONSOLE_REPORTS_MAX_PER_USER` | `20` | Scheduled reports one account may own |
| `CONSOLE_SMTP_HOST` | unset | The mail server (a relay). Turns e-mail on |
| `CONSOLE_SMTP_PORT` | `587` | |
| `CONSOLE_SMTP_STARTTLS` | `true` | Upgrade the connection with STARTTLS. `false` sends in plain text |
| `CONSOLE_SMTP_IMPLICIT_TLS` | `false` | TLS from the first byte (SMTPS, usually port 465). New in 0.5.0 |
| `CONSOLE_SMTP_AUTH` | `auto` | `auto`, `plain` or `login`. New in 0.5.0 |
| `CONSOLE_SMTP_CA_FILE` | none | PEM CA certificates the mail server's certificate may chain to |
| `CONSOLE_SMTP_USERNAME`, `CONSOLE_SMTP_PASSWORD_FILE` | none | The account to authenticate as and a file holding its password, read at each connection. Set both or neither |
| `CONSOLE_SMTP_FROM` | `syncd@<host>` | The sender address |
| `CONSOLE_SMTP_TIMEOUT_SECONDS` | `30` | |

The outbox's timings (`CONSOLE_MAIL_POLL_SECONDS` 30, `CONSOLE_MAIL_RETRY_INITIAL_SECONDS` 60,
`CONSOLE_MAIL_RETRY_MAX_SECONDS` 3600, `CONSOLE_MAIL_GIVE_UP_AFTER_SECONDS` 86400,
`CONSOLE_MAIL_KEEP_DAYS` 14) can be changed but rarely need to be. With the Helm chart's
`networkPolicy.egress.enabled` on, add a rule to `networkPolicy.egress.extra` so the console can
reach the mail server.

## Lineage export

A lineage export is everything the engine knows about one entity, for an auditor's question
such as "why does this person have this account, and what did syncd do to it?":

- each attribute with where it came from: the source record (connector, object, anchor and flow
  expression) or the computed attribute's expression;
- the source records, and the joins and unjoins;
- per publication, what the engine wrote over time: exports, failed exports and drift found,
  each with its time and outcome, oldest first;
- password events: when a password change was delivered to or refused by a target, with the
  outcome only, never a value or a policy message;
- the history, newest first, up to 500 entries, and whether older ones exist.

Sensitive values are masked as in the object explorer, unless revealed.

```bash
syncd lineage person 4f1c...e9 [--format text|json] [--reveal] [--out FILE]
```

The entity id is the 32 hex characters the explorer and `syncd report` show. `--reveal` needs a
control API listener that allows reveals.

In the console, an entity's page in the object explorer has **Lineage JSON** and **PDF** buttons
(`POST /api/v1/engines/{id}/entities/{entity}/lineage/export?view=&format=json|pdf[&reveal=true]`).
Sessions only; anyone who may view the engine may export. The export is audited as
`data.exported` with the entity id, before a byte is sent. A reveal is allowed only to the roles in
`CONSOLE_REVEAL_ROLES` and is recorded as `explore.reveal` with the attributes shown (see
[Revealing sensitive attributes](admin-guide.md#revealing-sensitive-attributes)). The JSON is the
`LineageDocument` of the API's OpenAPI document. The PDF has a cover (engine, environment, view,
entity, time, who, and whether sensitive values are in it) and one table per section, with the
banner on every page.

## History retention

Entity history records what changed, what caused it, and what was exported. Lineage, the
explorer's history and the reconciliation report's `since` times are built from it.

```yaml
engine:
  history:
    keep_days: 30          # entries older than this are pruned; 0 records no history
    keep_per_entity: 100   # at most this many per entity, the newest
    min_per_entity: 10     # the newest ones an entity keeps past the age cut
```

The engine prunes an hour after it starts and every hour after that. `syncd history prune --now`
prunes at once.

1. Entries older than `keep_days` go, except the newest `min_per_entity` of each entity, so an
   entity's story is never empty.
2. Then all but the newest `keep_per_entity` of each entity go.
3. Neither step touches an entity with an open held change (a report-mode would-do counts too)
   or a dead letter in any publication: it keeps every entry until that clears, so the entry that
   explains why a change was planned stays with it. Since 0.4.1 this holds past `keep_per_entity`
   as well as `keep_days`; in 0.4.0 the per-entity cap still applied to such an entity.

So, held and dead-lettered entities aside, the history holds at most `entities × keep_per_entity`
entries, and in a quiet directory about `entities × min_per_entity`. For a million people at about
300 bytes an entry, that is between
3 GB and 30 GB. [Availability and sizing](availability-and-sizing.md) has the rest of the
arithmetic.

| Where | What |
|---|---|
| `syncd status` | `history: N entries, oldest ..., pruned ..., M pruned since start` |
| The console's engine page | The same |
| Prometheus | `syncd_history_entries`, `syncd_history_pruned_total`, `syncd_history_oldest_timestamp_seconds`, `syncd_history_last_prune_timestamp_seconds` |

`min_per_entity` and the exemptions are new in 0.4.0, so an upgraded engine keeps more history
than 0.3.0 did with the same `keep_days`. A publication in report mode for a long time, or a
growing dead-letter queue, keeps its entities' whole history: watch `syncd_history_entries`.

## Data-quality checks

A view's `quality` checks say what good data is: a mail that is an address, a manager who
exists, no two people with one mail. The engine checks every entity as it computes it, keeps
what fails, and reports it per check.

```yaml
views:
  - name: person
    quality:
      - name: mail-format
        check: e.mail.matches("^[^@]+@[^@]+$")
        severity: error
        on_error: quarantine
        message: mail is not an address
      - name: manager-exists
        check: '!has(e.manager) || ref(e.manager) != null'
        severity: warning
      - name: unique-mail
        unique: e.mail
        severity: error
```

| Key | Default | What |
|---|---|---|
| `name` | required | Letters, digits, `-` and `_`. Unique in the view |
| `check` | | An expression over `e` that holds for a good entity. An error evaluating it (an attribute it needs is absent) counts as a failure |
| `unique` | | An expression giving a value no two entities of the view may share, compared as text, ignoring case. Entities without a value are not compared |
| `severity` | `warning` | `error`, `warning` or `info` |
| `on_error` | `report` | `report`, `quarantine`, or `{ skip_publication: [<publication>, ...] }` |
| `message` | the check's name | What a failure says. It is shown in `syncd quality` and the console, so do not put an attribute's value in it |

A check has `check` or `unique`, not both. A check may call rule package functions and read
lookup tables like any rule (see [Computed attributes and rules](rules.md)). One that reads other
entities (`ref()`, `referrers()`) runs with the cross-entity rules.

### When checks run

Checks run right after the entity's computed attributes, in the same computation, so a check runs
again exactly when the entity is recomputed: when any of its inputs changes, at a full sync and
after a configuration change. A `unique` check keeps an index of values per view: every entity
sharing an old or a new value is checked again when one changes or is deleted.

Failures are kept per entity in the store, with the message and since when. An entity that keeps
failing keeps the time it began to. Checks removed from the configuration are dropped when the
engine starts.

### What a failure does

| `on_error` | What happens |
|---|---|
| `report` (the default) | The failure is recorded. Nothing else changes |
| `quarantine` | The entity's exports are held back until the check passes, as a rule with `on_error: quarantine` does. It shows in `syncd quarantine` as `quality <check>` |
| `skip_publication: [ad-users]` | The entity is kept out of those publications while it fails. They must publish this view |

!!! warning "`skip_publication` applies the leaver policy"
    An entity kept out of a publication is out of its scope, **so the publication's leaver
    policy (`deprovision`) applies to an entry already in the target**: it is disabled, moved or
    deleted as for a leaver. A typo in a source can then lock someone out. That is why `report` is
    the default. Prefer `quarantine`, which only stops new changes.

A `unique` check can only report, since whether an entity fails it depends on other entities. A
check that reads other entities cannot quarantine.

### Where failures show

| Where | What |
|---|---|
| `syncd status` | A `quality <view> <check>: N entities fail (<severity>)` line per failing check |
| `syncd quality [view] [--check C]` | Each check with its severity and failing count. **Exits 1 while a check of severity `error` fails**, so it can gate a script |
| `syncd quality <view> --check C --list` | The failing entities: ids, the check, the severity, since when and the message. Never values |
| Prometheus | `syncd_quality_failing{view,check,severity}` |
| Alert | `SyncdQualityErrors`: a check of severity `error` has failed for an hour |
| Webhook | `quality.errors` (new in 0.5.0): a view's entities failing checks of severity `error` went from none to some, or rose by `CONSOLE_WEBHOOKS_QUALITY_ERRORS_RISE` (10; 0 tells only from none to some) since it was last told. An endpoint gets it once the event is added to its list (see [Webhooks](webhooks.md)) |
| The console's API | `GET /api/v1/engines/{id}/quality` and `/quality/entities` (sessions, and tokens with `engines:read`) |

### The console's Data quality page

An engine's page links to **Data quality**. Per view it shows a table of checks with the severity,
the entities failing each now, since when, and two sparklines, filtered by severity: the last 24
hours (new in 0.5.0) and the last seven days. A check opens its failing entities, each linking to
the object explorer.

- **The 24-hour trend** is kept by the engine: per check, the highest failing count in each hour
  of the last day, saved in its store so a restart keeps it.
- **The seven-day trend** is kept by the console: the daily counts (the day's latest and highest
  per engine, view and check).

Each engine's card on the dashboard has a quality tile: how many checks fail with errors, and with
warnings.

In the Computed Attribute Studio, the **Quality check** block writes a check in the expression
editor, tries it on a random sample of the view's entities from the engine (how many fail, and
which), and adds it to the view. Sensitive attributes are not read for the trial.

**Limitations.** `syncd status` and `syncd quality` show the counts now, not the trend: the
24-hour trend is drawn on the console's Data quality page.

## Correlation assistance

A source record that joins no entity waits for review on the console's **Correlation** page. A
record with a typo in its join key used to find nothing, and an operator looked the person up by
hand. Now each waiting record gets scored suggestions. **Nothing joins by itself:** an operator
accepts suggestions, one at a time or in bulk. One operator is enough (no four eyes), since each
join is audited and can be undone with an unjoin.

```yaml
views:
  - name: person
    sources:
      - connector: hr_db
        object: account
        join:
          - { entity: mail, source: source.mail }
    correlation:
      suggest:
        - { entity: employeeNumber, source: source.emp, weight: 1.0 }
        - { entity: sn, source: source.sn, weight: 0.6, similar: true }
        - { entity: dob, source: source.dob, weight: 0.8 }
      accept_at: 0.95             # the default threshold for bulk accepts
      unmatched_alert_after: 7d   # records waiting longer are ageing
      other_views: [contractor]   # also look for the record there (0.5.0)
```

| Key | Default | What |
|---|---|---|
| `suggest` | none | Extra conditions to score: an entity attribute, an expression over the record (`source`), a `weight` (above 0, at most 10; default 1.0) and `similar` (default `false`) |
| `accept_at` | `0.95` | The score from which bulk accepts take a suggestion. Above 0, at most 1 |
| `unmatched_alert_after` | none | Records waiting longer than this are counted as ageing |
| `other_views` | none | New in 0.5.0. Views whose entities a waiting record is also looked for in, by the same conditions on attributes of the same name. What is found there is shown beside the suggestions, with its view; it cannot be accepted as a join, since the record's source is not a source of that view |

### Scoring

The conditions scored are the source's join conditions (weight 1.0, similarity on) and the
view's `suggest` hints. A hint on an attribute a join condition already matches replaces that
condition's weight and similarity. Each condition is matched three ways:

| Match | Scores |
|---|---|
| Exact, under the condition's own comparison | 1.0 |
| Normalized: transliterated and case-folded | 0.9 |
| Similar: a Jaro-Winkler similarity of at least 0.92 between the normalized texts, or from 0.5.0 one typo apart (a character replaced, dropped or added, or two neighbours swapped) when both have at least five characters (only with similarity on) | 0.7 |

The score is the weighted mean over the conditions the record has a value for, so a sparse
record can still reach the threshold on what it has. The top five candidates are returned,
highest first, each with a breakdown per condition (attribute, expression, weight, match,
value). Entities the record was unjoined from, and entities that already have a record from the
same source, are left out.

The work is bounded: exact and normalized matches come from the join index, and similarity
compares at most 200 entities that share the first three characters of the normalized value. From
0.5.0 a second pass compares at most 200 more that share the value's last characters or its middle,
so a typo in the first characters is found too; a bucket of more than 2,000 entities (every
address at one mail domain) is not read in that pass. It never scans the view.

!!! warning "Review bulk accepts after upgrading"
    Because 0.5.0 finds more (a typo at the start of a value, values one edit apart),
    `syncd correlation accept --above` and **Accept all at or above** may take records they left
    in 0.4.1. Run the dry run (without `--yes`), or read the dialog's count, before accepting.

### The unmatched queue

The engine keeps the time each record became unjoined, across restarts.

| Where | What |
|---|---|
| `syncd status` | `view person: unmatched: N records waiting to join (N from hr_db, ...); oldest since ...; N older than 7d` |
| Prometheus | `syncd_unmatched_records{view,source}`, `syncd_unmatched_oldest_seconds{view}`, `syncd_unmatched_ageing{view}` (0 without `unmatched_alert_after`) |
| Alert | `SyncdUnmatchedRecordsAgeing`: records older than `unmatched_alert_after` for an hour |
| Webhook | `correlation.unmatched-ageing` when a view's ageing count rises from 0 |

### Accepting suggestions from the command line

```bash
syncd correlation suggest --view person [--source hr_db/account] [--above 0.9] [--limit 100] [--json]
syncd correlation accept  --view person --above 0.95            # a dry run: prints the decisions
syncd correlation accept  --view person --above 0.95 --yes      # applies them
syncd correlation suggest --view person --json > decisions.json # review or edit, then:
syncd correlation accept  --view person --from decisions.json --yes
```

- `--above` defaults to the view's `accept_at`. `--source` takes `connector` or
  `connector/object`.
- `accept` takes every record whose best suggestion scores at least the threshold **and whose
  second does not**. An ambiguous record is skipped and said so.
- Without `--yes`, `accept` is a dry run that prints the decisions. With it, each decision is
  applied and its outcome printed.
- The decisions are applied in one transaction: each gets its own result (joined, or why not),
  the ones that pass are committed together, and the exports follow. A second decision for the
  same record in one batch is refused.

### Accepting suggestions in the console

The Correlation page shows each waiting record with its suggestions, the score and the breakdown,
and a checkbox per suggestion. Operators and administrators can:

- join one record from its suggestion;
- tick several and choose **Accept selected**;
- choose **Accept all at or above** the view's `accept_at`. A dialog says how many: every record
  shown whose best suggestion is at or above the threshold and whose next one is not. Records with
  two candidates over the threshold are left for a decision by hand.

Reading suggestions needs `engines:read` and is audited as `explore.suggestions`. Accepting needs
`runs:run` and is audited as `correlation.accepted` with the count and how many were refused.

With `other_views`, `syncd correlation suggest` adds an `in view <name>` line per look-alike, and
the Correlation page a second table: a place to look (the person may already be known as a
contractor, say), not something to accept.

**Limitations.**

- Suggestions join within the view only; `other_views` points to look-alikes in other views but
  cannot join to them.
- A hint's expression runs against every source of the view; a source without the field scores
  nothing for that condition.
- Similarity is on text only: numbers and dates match exactly or normalized, never
  approximately.
- `other_views` has no console form field: write it in the configuration.

## Known limitations

- **Reports:** the Excel files were read back with a zip reader in testing, not opened in Excel or
  LibreOffice. The PDFs were checked with qpdf and poppler, not with veraPDF, PAC, Acrobat or a
  screen reader, and claim no PDF/UA conformance; they have no right-to-left text, no shaping and
  no Chinese, Japanese or Korean (such characters show as U+FFFD).
- **Unmanaged entries:** 389 Directory Server and PingDirectory were unit-tested only; no real
  Entra tenant or SCIM provider was used; SCIM and Entra ID targets have no privileged groups;
  there is no search from an unmanaged entry to candidate entities.
- **Data quality:** the 24-hour trend shows on the console only, not in `syncd status` or
  `syncd quality`.
- **Correlation** joins within a view only (`other_views` points, it does not join), and
  similarity is on text only.
