# Reports, lineage and data quality

This page covers the evidence syncd gives operators and auditors, and the tools that keep bad
data out of the targets. All of it is new in syncd 0.4.0.

- **Reconciliation reports**: what each publication holds, what the last drift check found, and
  what is held or dead-lettered, as text, CSV, JSON or PDF, on demand or on a schedule.
- **Lineage export**: one person's whole story, as JSON or PDF.
- **History retention**: how much entity history the engine keeps, and what it never prunes.
- **Data-quality checks**: what good source data is, checked on every entity.
- **Correlation assistance**: scored suggestions for source records that joined no entity.

The audit trail itself is in the [Admin guide](admin-guide.md). Held changes, leaver stages and
group lifecycle, which the report counts, are in [Change safety and lifecycle](change-safety.md).

## Reconciliation reports

The report is the reconciliation picture as the running engine knows it. **It reads nothing from
the targets**: it gives the last drift check's result and when it was made. Run
`syncd check-drift` first for a fresh one.

- **One summary row per publication**: entities in the view, entries exported, the last drift
  check (when, entries read, drifted, missing, reverted, accepted, flagged, or why it stopped),
  dead letters, held changes by reason, leavers by the stage ahead of them, what a report-mode
  publication would do, the computed groups (with manual members, expired, reviews due) and when
  the publication was last reconciled in full.
- **One detail row per entry with a difference**, of one of these kinds: `drifted` and
  `missing` (from the last drift check: the DN and the attributes that differ, with their values
  except for sensitive attributes, which are named only), `held` or `would_do` (a held change),
  and `dead_letter`. Each has its `since`.

### `syncd report`

```bash
syncd report                                          # every publication, as text
syncd report --publication people_to_ad --format csv --out people_to_ad.csv
syncd report --format json | jq 'select(.row == "summary")'   # one JSON object per line
```

| Format | Use |
|---|---|
| `text` (default) | Reading in a terminal |
| `csv` | Spreadsheets and scripts. RFC 4180, the same 33 columns as the console's CSV |
| `json` | Scripts. JSON lines: one object per row |

The CSV has one header row. The `row` column says whether a line is a publication's `summary` or
a `detail`, and the columns the other kind does not use are empty.

### Report CSV and PDF in the console

The **Report CSV** and **PDF** buttons on an engine's page download the same report:
`POST /api/v1/engines/{id}/reports/reconciliation/export?format=csv|pdf[&publication=]`.

- **A `POST`, because it is audited.** Each export is recorded as `data.exported`, with the
  format, the publication and the row count, before a byte is sent. An export the trail cannot
  record is refused. Anyone who may view the engine may export (sessions, and API tokens with
  `engines:read`): it changes nothing but the trail.
- **Always a file**: `text/csv` or `application/pdf`, `Content-Disposition: attachment` and
  `X-Content-Type-Options: nosniff`. The file is named `<engine>-<publication>-<date>.<ext>`.
- **Capped** at `CONSOLE_EXPORTS_MAX_ROWS` (200,000) rows. A capped download carries
  `X-Syncd-Truncated: true`.
- **Formula-injection guard.** A text cell that starts with a tab or a carriage return, or whose
  first character after any white space is `=`, `+`, `-` or `@`, is written with a leading
  apostrophe.
- **CSV** is UTF-8 with a byte-order mark (so Excel reads it as UTF-8), CRLF line ends, RFC 4180
  quoting and times in ISO 8601 UTC.
- **PDF** is landscape A4: a cover page (the engine, its environment, the publication, when and by
  whom), the summary table and the detail table, with the header repeated on each page. With a top
  [banner](admin-guide.md#banners) set, its text is printed in every page's header and footer.

There is no Excel (`.xlsx`) export: spreadsheets open the CSV.

### Scheduled reports

The **Reports** page (`/reports`) e-mails the report, or writes it to a share, daily, weekly
(Monday) or monthly (the 1st), at an hour in UTC (`/api/v1/reports`). Administrators and
operators create, change, send now and delete their own (administrators everyone's). Auditors can
look. Sessions only: no API token manages reports.

- A report names an engine, optionally one publication, the format (CSV or PDF), the schedule, up
  to 20 recipients, and/or a directory inside the share.
- Every five minutes (`CONSOLE_REPORTS_POLL_SECONDS`) one console looks for reports that are due,
  under a database lock, so a report goes out once however many consoles run.
- **Every run checks again** that the report's owner is still an active administrator or operator
  and that every recipient is inside the allowed domains. A run that fails a check sends nothing,
  and says why on the page and in the audit trail.
- A file holds at most `CONSOLE_REPORTS_MAX_ROWS` (50,000) rows and 15 MB. The e-mail says when it
  was cut.
- **Writing to a share:** the directory is relative to `CONSOLE_REPORTS_SHARE_DIR`, with no `..`,
  and links are resolved so it cannot point out of the share. The file is written atomically as
  `<engine>-<publication>-<date>.<csv|pdf>`. On Kubernetes, mount the share with
  `console.extraVolumes` and `console.extraVolumeMounts`.
- Audited: `report.created`, `report.changed`, `report.deleted`, and `report.sent` with the
  recipients and the row count (a refused run is a failed `report.sent` with the reason).

!!! warning "Recipient domains are required"
    A report sends the engine's picture to the addresses typed, so **nothing can be scheduled to
    an address, or sent,** until `CONSOLE_REPORTS_RECIPIENT_DOMAINS` names your own domains
    (`example.gov`, or `*.mil` for a domain and its subdomains). E-mail also needs a mail server
    (`CONSOLE_SMTP_HOST`). The page says when either is missing.

### E-mail

The console speaks SMTP itself, with no mail library: EHLO, STARTTLS, AUTH PLAIN, MAIL, RCPT,
DATA. TLS uses the console's own crypto provider, so FIPS mode applies to it.

- **STARTTLS only.** The connection is upgraded with STARTTLS (port 587 by default) and the
  server's certificate is checked against the public roots or `CONSOLE_SMTP_CA_FILE`. There is
  no SMTPS (implicit TLS, port 465).
- **AUTH PLAIN only**, and only after STARTTLS. There is no AUTH LOGIN. The console refuses to
  start with a username and `CONSOLE_SMTP_STARTTLS=false`, so a password is never sent in plain
  text.
- `CONSOLE_SMTP_STARTTLS=false` sends in plain text, for a relay on the same host or a private
  network only.
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
| `CONSOLE_SMTP_CA_FILE` | none | PEM CA certificates the mail server's certificate may chain to |
| `CONSOLE_SMTP_USERNAME`, `CONSOLE_SMTP_PASSWORD_FILE` | none | The account to authenticate as (AUTH PLAIN) and a file holding its password, read at each connection. Set both or neither |
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

1. Entries older than `keep_days` go, except:
    - the newest `min_per_entity` of each entity, so an entity's story is never empty;
    - every entry of an entity with an open held change or a dead letter in any publication, until
      it clears, so the entry that explains why a change was planned stays with it.
2. Then all but the newest `keep_per_entity` of each entity go, exemptions included.

So the history holds at most `entities × keep_per_entity` entries, and in a quiet directory about
`entities × min_per_entity`. For a million people at about 300 bytes an entry, that is between
3 GB and 30 GB. [Availability and sizing](availability-and-sizing.md) has the rest of the
arithmetic.

| Where | What |
|---|---|
| `syncd status` | `history: N entries, oldest ..., pruned ..., M pruned since start` |
| The console's engine page | The same |
| Prometheus | `syncd_history_entries`, `syncd_history_pruned_total`, `syncd_history_oldest_timestamp_seconds`, `syncd_history_last_prune_timestamp_seconds` |

`min_per_entity` and the exemptions are new in 0.4.0, so an upgraded engine keeps more history
than 0.3.0 did with the same `keep_days`.

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
| The console's API | `GET /api/v1/engines/{id}/quality` and `/quality/entities` (sessions, and tokens with `engines:read`) |

### The console's Data quality page

An engine's page links to **Data quality**. Per view it shows a table of checks with the severity,
the entities failing each now, since when, and a sparkline of the last seven days, filtered by
severity. A check opens its failing entities, each linking to the object explorer. The console
keeps the daily counts itself (the day's latest and highest per engine, view and check), which the
sparkline reads.

Each engine's card on the dashboard has a quality tile: how many checks fail with errors, and with
warnings.

In the Computed Attribute Studio, the **Quality check** block writes a check in the expression
editor, tries it on a random sample of the view's entities from the engine (how many fail, and
which), and adds it to the view. Sensitive attributes are not read for the trial.

**Limitations.** There is no `quality.errors` webhook event. The engine's status has no 24-hour
trend; the console's daily counts give the trend.

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
```

| Key | Default | What |
|---|---|---|
| `suggest` | none | Extra conditions to score: an entity attribute, an expression over the record (`source`), a `weight` (above 0, at most 10; default 1.0) and `similar` (default `false`) |
| `accept_at` | `0.95` | The score from which bulk accepts take a suggestion. Above 0, at most 1 |
| `unmatched_alert_after` | none | Records waiting longer than this are counted as ageing |

### Scoring

The conditions scored are the source's join conditions (weight 1.0, similarity on) and the
view's `suggest` hints. A hint on an attribute a join condition already matches replaces that
condition's weight and similarity. Each condition is matched three ways:

| Match | Scores |
|---|---|
| Exact, under the condition's own comparison | 1.0 |
| Normalized: transliterated and case-folded | 0.9 |
| Similar: a Jaro-Winkler similarity of at least 0.92 between the normalized texts (only with similarity on) | 0.7 |

The score is the weighted mean over the conditions the record has a value for, so a sparse
record can still reach the threshold on what it has. The top five candidates are returned,
highest first, each with a breakdown per condition (attribute, expression, weight, match,
value). Entities the record was unjoined from, and entities that already have a record from the
same source, are left out.

The work is bounded: exact and normalized matches come from the join index, and similarity
compares at most 200 entities that share the first three characters of the normalized value. It
never scans the view.

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

**Limitations.**

- Suggestions do not look across views.
- A hint's expression runs against every source of the view; a source without the field scores
  nothing for that condition.
- Similarity is on text only: numbers and dates match exactly or normalized, never
  approximately.
- A typo in a value's first three characters is not found by similarity. The other conditions
  still score.

## Known limitations

- **Reports** have no Excel (`.xlsx`) export: spreadsheets open the CSV. The summary has no
  "adopted" count; it has the exported count and the last check's `missing` instead.
- **The built-in SMTP client** speaks STARTTLS and AUTH PLAIN only: no SMTPS (implicit TLS) and no
  AUTH LOGIN.
- **Data quality:** there is no `quality.errors` webhook event, and the engine's status has no
  24-hour trend (the console's daily counts give it).
- **Correlation** doesn't suggest across views. Similarity is on text only, and a typo in a
  value's first three characters isn't found by similarity.
