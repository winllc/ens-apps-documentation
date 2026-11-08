# User guide

This guide walks through every screen of the syncd web console, for **designers** (who build and
publish configurations) and **operators** (who watch engines and act on what they report). What you
can do depends on your role; each section says which role an action needs. Accounts, roles, sign-in
policy, banners, engine registration, API tokens, webhooks, environments and the audit trail are in
the [Admin guide](admin-guide.md). People who only want to change their own directory password use
the [self-service page](#changing-your-directory-password-password), which needs no console account.

## Roles at a glance

| You can | Role needed |
|---|---|
| Look at everything below: engines, status, held changes, metrics, dead letters, data quality, configuration versions, the Studio, the object explorer, correlation suggestions; download reports and lineage | Any role |
| Edit, check, save, preview, simulate, import, approve and publish configuration; run connector checks; sync from Git | **designer** or **admin** |
| Run reconciliations, full syncs and drift checks; approve held-back deletes and held changes; review groups; retry and discard dead letters; join, unjoin and accept suggestions; schedule reports | **operator** or **admin** |
| See the scheduled reports | **operator**, **auditor** or **admin** |
| Reveal sensitive attribute values | A role listed in `CONSOLE_REVEAL_ROLES` (by default **admin** only) |

A designer cannot run engines, and an operator cannot change configuration. Buttons your role cannot
use are not shown, and the server refuses the request if it is sent anyway. The full matrix is in
[Admin guide → Permissions matrix](admin-guide.md#permissions-matrix).

## Signing in

Open the console's address (normally `https://` and the host name your administrator gives you).
If your organisation set banners, they appear across the top and bottom of every page, the sign-in
page included.

=== "Console account"

    Enter the **Username** and **Password** your administrator gave you, and choose **Sign in**.

    - A new account, or one whose password was reset, has a **one-time password**: the console
      then asks you to choose your own (**Choose a new password**) before anything else. It needs
      at least 12 characters and must not be your username.
    - A failed sign-in always says "sign-in failed; after repeated failures an account locks for
      15 minutes". After 5 failures the account locks for 15 minutes; an administrator can unlock
      it sooner with a password reset. More than 20 attempts from one address in 5 minutes are
      refused with "too many attempts; try again later".

=== "Single sign-on"

    If single sign-on is set up, the page shows **Sign in with &lt;provider&gt;**. You sign in at
    your organisation's identity provider and come back to the page you asked for. Your role comes
    from the provider's groups at each sign-in. If the provider refuses you, or you are in no group
    the console maps to a role, you come back to the sign-in page under **Single sign-on failed**,
    with the reason.

    When your administrator has limited local sign-in to break-glass accounts, the password form
    is folded away behind **Break-glass sign-in**. It is for when the identity provider is down;
    only accounts marked break-glass can use it, and every attempt is audited.

Other messages on the sign-in page:

- **Signing in will not work at this address**: the console's session cookie is kept only over
  HTTPS or on `localhost`. Use the console's `https://` address.
- **Development mode**: a test installation that accepts `admin` / `admin`. Never seen in
  production.

A session ends after **30 minutes** without activity or **12 hours** in all (your administrator may
have changed these). The console then sends you to sign in, and back to the page you were on.
**Sign out** is at the top right.

### Changing your console password

Go to `/change-password` (type it after the console's address): the current password, the new one
twice, **Set password**. Your other sessions are signed out. Accounts that sign in through single
sign-on have no console password; change it at the identity provider.

### Changing your directory password (`/password`)

When your organisation uses syncd's password synchronisation and has turned the page on, anyone
can change their own **directory** password at `/password` on the console's address. You need no
console account, and no role.

1. Enter your **User name** (the one you sign in to your organisation's directory with), your
   **Current password**, and the **New password** twice.
2. Choose **Change password**.

The new password must have at least 12 characters, mix lower case, upper case, digits or symbols,
and not contain your user name (your organisation may have set other rules; the directory's own
rules apply too). A refusal says why. A wrong user name or password always gets the same answer,
and after repeated failures the page refuses your user name for 15 minutes. Once it says **Your
password was changed**, it may take a minute to work everywhere you sign in.

If the page says "Password changes are not available here", it is not turned on: ask your
administrator. How it works is in [Password synchronisation](password-sync.md#people-changing-their-own-password).

## The page layout

- **Header**: the syncd logo (back to **Engines**), the navigation (**Engines**; **Users** for
  administrators; **Audit log** for administrators and auditors; **Reports** for administrators,
  operators and auditors; **Integrations** for administrators and auditors), a **FIPS** badge when
  the console runs in FIPS mode, your name and role, and **Sign out**.
- **Footer**: the console version.
- A page you are not allowed to open says so (for example "Only administrators manage accounts").
  An address that does not exist shows **Page not found** with a link back to the engines.

## Engines (`/`)

A card per registered engine, showing:

- its name and control API address, with a padlock for mutual TLS (open padlock: plain text to an
  engine on the same host), and its environment when it is not `default`;
- a badge: **checking**, **running**, **attention** (a source failed, or a deletion threshold held
  deletes back), **unreachable**, or **passive** for the standby engine of an HA pair;
- the number of **Sources** (and how many failed), **Exports** and **Dead letters**;
- **Data quality**, when the engine has quality checks: how many checks are failing with errors
  (in red) and with warnings (in amber);
- the engine process's **CPU** (a percentage of the CPUs it may use, "of 2") and **Memory**
  (resident memory, and its memory limit when it has one), each with a small chart of the last
  minute. They are shown in amber at 90% or more (memory: of its limit). Engines that do not report
  their process (off Linux) show neither;
- how long the engine has been up, the configuration version it runs (`v3`), and the number of
  deletion thresholds hit.

The two engines of an **HA pair** share one card, headed **Pair &lt;name&gt;**, listing each engine
with its role (**active**, **passive** or **unreachable**; a link to its page) above the active
engine's card. **no active engine** in red means neither holds the lease: nothing is read or
exported.

The cards follow each engine's live status stream, so these update about once a second. CPU is
averaged over the last five seconds or so.

Choose a card to open the engine. Administrators also have **Register engine** here.

## An engine's page (`/engines/{id}`)

The header shows the engine's address, its version, uptime, the configuration it runs and
**FIPS** if it runs in FIPS mode, with buttons to **Metrics**, **Explore**, **Correlation**,
**Data quality**, **Configuration** and **Dead letters**, the report downloads **Report CSV** and
**PDF** (see [Downloading the reconciliation report](#downloading-the-reconciliation-report)), and
**Remove engine** for administrators.

The page follows the engine's **live status stream**: it updates by itself, about once a second.
If the stream drops, a red notice says it is reconnecting and the last status stays on screen.

Notices at the top:

- **Certificate expires soon** (amber, red under 14 days), from 60 days before the console's
  client certificate for the engine expires, or **Certificate expired**, when the console can no
  longer reach the engine. Tell whoever runs the engine host: `sudo syncdctl renew-certs` there
  issues new certificates and gives the console its new ones. See
  [Admin guide → Certificate expiry and renewal](admin-guide.md#certificate-expiry-and-renewal).
- **Target X is unreachable**, with how long it has been down. Exports to it wait in the
  dead-letter queue while sources keep flowing; once it is back, a reconciliation or a retry sends
  them.
- **Target X: N exports refused for users synced from on-premises** (an Entra ID target): Entra
  Connect owns those users' properties, so syncd writes only their groups and licences. The refused
  exports are in the dead-letter queue.
- **HA role** (an engine of a pair): **active**, holding the lease, or **passive**, naming the active
  engine; and how old the newest store snapshot is. A passive engine serves its API only, and
  refuses runs and other commands: use the active engine's page. A red **no engine holds the lease**
  means nothing is read or exported.
- **Busy: …**: the engine is in the middle of a long task (a full sync, for example). Runs and
  other commands are refused until it ends.

### Run (operators)

| Button | What it does |
|---|---|
| **Reconcile all** | Compares each target with what it should hold, and writes the differences |
| **Full sync** | Recomputes every entity from its sources first, then reconciles. Use it after rule changes |
| **Check drift** | Reads the entries back from the targets and acts on what was changed there, by each publication's drift policy |
| **Reconcile** (on a publication's row) | Reconciles that publication only |

Each returns when the engine is done and shows its summary. Every run is recorded in the audit
trail.

### Deletes held back by a deletion threshold

When a change would delete more of a target than a publication's threshold allows, the engine holds
those deletes and the page lists them: the scope, how many deletes of how many objects, and since
when. Check the source first: a mass delete is often a source that came back empty.

Operators can choose **Approve once**, then **Approve and apply**: the held deletes are applied
once, now. The threshold stays in force for later changes.

### Counters, sources and publications

- **Counters**: entities changed, export operations, export failures, orphans, ambiguous joins
  and rule errors since the engine started.
- **Scheduled changes**: how many changes the engine will make at a set time (effective dates in
  rules, the stages of leavers), and when the next is due. The engine keeps these timers across
  restarts.
- **Entity history**: how many history entries are kept, the oldest, and when they were last
  pruned.
- **Backups** (when the engine backs itself up on a schedule): where to, how often, the last one,
  and the last failure in red.
- **Passwords** (with password synchronisation): changes **Delivered**, **Failed** and
  **Waiting**, and **Initial passwords set** and **refused** on new entries, with the last failure.
- **Sources**: each source object with its mode (scrape, delta or stream), its state (**starting**,
  **reading**, **streaming**, **idle**, **retrying** with the time to the next try, **failed** with
  the error), restarts, events read, the last event and its checkpoint. A warning (in amber) says,
  for example, that a CSV file had rows skipped.
- **Publications**: each with its target, its dead letters (a link to the queue), what it holds
  back (**Held**), its **Leavers** and the last **Drift** check:
    - a **Report** badge marks a publication in report mode, which plans every pass and writes
      nothing. Its **Held** column counts what it **would be sent**, by kind (adds, modifies,
      moves, deletes);
    - **Held**: **none** (with when the next change window opens), or **N held**, by kind, and what
      they wait for: **needs approval**, a canary soaking (with the time left), a window opening,
      or the reason;
    - **Leavers**: how many leavers are still **to disable**, **to strip of groups**, **to move** and
      **to delete**, under a staged leaver policy;
    - **Drift**: **none**, how many entries differ (missing, reverted, accepted, flagged), or
      **check stopped**.
- **Drift found in the targets** (when a check found some): the entry, the attribute, the value in
  the target and the value exported, and the action taken. Flagged differences stay in the target
  until someone corrects them there or the publication's drift policy changes. Sensitive values are
  not shown.
- **Held back by a failing rule** (when there are some): entities whose exports a computed
  attribute with **On error: quarantine** holds back. It gives the count per view, then up to 100
  entities with the view, the entity id (a link to the object explorer), the rule, its error and
  since when. Their exports wait until the rule evaluates again: fix the data or the rule. The
  engine keeps them across restarts. `syncd quarantine` on the engine host lists them all.

### Held changes

When a publication has changes planned but not applied, the **Held changes** card lists them, per
publication: how many, since when, and for a canary how many went first and when the rest follows.
Changes are held outside a change window, over a modification or add threshold, while a canary
soaks, when a canary failed, or (in report mode) because nothing is written. The table under each
publication shows up to 50 held changes: the entity (a link to the explorer), the kinds of change,
the **Reason** (`window`, `canary`, `canary_failed`, `report`, `overdue`, `modifications`,
`additions`, `deletions`, `expired`), the change and since when. `syncd held <publication>` on the
engine host lists them all.

A held change is never replayed: when its hold ends, the entity is planned again from its current
state.

When a publication's held changes **need approval** (a threshold, a failed canary, an overdue change
held for approval), operators have **Approve and apply**. It asks for confirmation, then plans every
change held for that publication again from the current state and applies it now, whatever the
window, thresholds and canary say. Deletes held by its deletion threshold go through once too. It is
recorded in the audit trail. Changes that only wait for a window or a soak need nothing: they go
when it opens or ends.

How windows, thresholds, canaries and report mode are set is in
[Change safety and lifecycle](change-safety.md#held-changes).

### Groups

When a publication manages groups with a lifecycle, the **Groups** card lists them per publication
(with how many have manual members, have expired, or are due for review):

| Column | Shows |
|---|---|
| **Group** | Its name and DN |
| **Members** | How many members syncd computed |
| **Manual members** | Members added by hand in the target: **N kept** or **N flagged**, by the group's policy, or **none** |
| **Owner** | The owner written with the members |
| **Expiry** | When the group expires, or **expired** (in red) with what happened (`report`, `empty`, `delete`) |
| **Review** | When the review is due, **due since …**, or when it was last reviewed |

Operators have **Review** on each row: it records a review, which starts the expiry and review
clocks again and brings an expired group back. A due review shows here, in the engine's status and
in a metric only: syncd sends no reminder. See
[Change safety and lifecycle → Group lifecycle](change-safety.md#group-lifecycle).

## Dead letters (`/engines/{id}/dead-letters`)

Exports the target refused: the entry's DN, the publication, the error, the number of attempts and
the last failure. Dead letters are retried with the entry's next change or the next reconciliation.
Up to 500 are listed.

Operators can:

- **Retry all**, or retry one entry (the circular-arrow button);
- **Discard** one (the bin button, then **Discard**): it leaves the queue without being retried; the
  entry's next change exports it again.

## Live metrics (`/engines/{id}/metrics`)

Rates from the live status stream, per second and updated every second, over the last **1 min**,
**5 min** or **15 min**:

- tiles for source events, entity changes, export operations and export failures (the last five
  seconds' average and the window's peak), and the current dead letters and orphans;
- **Engine process**: tiles for **CPU** (a percentage of the CPUs the engine may use, the CPUs in
  use, and the window's peak), **Memory** (resident, against its limit or the host's memory, and its
  peak since it started), **Threads** and **Open files**, and charts of CPU and resident memory
  (in MiB, or GiB from 4 GiB) over the window. CPU at 90% or more, and memory at 90% of a limit,
  are shown in amber. An engine that does not report its process says so instead;
- charts of throughput, source events by source, export failures and dead letters queued;
- a **Sources** table with each source's rate, peak, total events, last event and last checkpoint.

The history is kept only while the console stays open in your browser: reloading the page starts
it again. Long-term metrics come from the engine's Prometheus endpoint; see the
[Operator guide](operator-guide.md).

## Data quality (`/engines/{id}/quality`)

**Data quality** on an engine's page lists, per view, the `quality` checks of the engine's
configuration (any role):

| Column | Shows |
|---|---|
| **Check** | The check's name. Choose it to list the entities failing it |
| **Severity** | **error**, **warning** or **info** |
| **Failing** | How many entities fail it now |
| **Since** | When it started failing |
| **Last 7 days** | A small line of how many failed each day |

**Severity** at the top shows only the checks of one severity. A check's failing entities are
listed under the view's table with the check's message, each a link to the object explorer; when
there are more than shown, `syncd quality <view> --check <check> --list` on the engine host lists
them all. Values are never shown, only entity ids and messages. The page refreshes every
30 seconds.

A check that only reports changes nothing. A check set to quarantine holds the entity's exports
back, and one set to skip publications keeps the entity out of them, so that publication's leaver
policy applies to it. How checks are written and what they do is in
[Reports, lineage and data quality](reports-and-data-quality.md#data-quality-checks).

## Configuration versions (`/engines/{id}/configs`)

An engine's configuration is managed here as **versions**. A version never changes once saved:
every save makes a new one, numbered `v1`, `v2`, … (the engine reports the one it runs). When the
engine runs in an environment other than `default`, the page says so: a version published to it is
rendered with that environment's values (see [Configuration as code](config-as-code.md#environments)).

The list shows each version's status, comment, who saved it and when, who published it and when,
and:

| Column | Shows |
|---|---|
| **Source** | `console`, `git` and the commit (hover for its author), or `promoted from <engine>/v<n>` |
| **Runs in** | The environments whose engines run this text now |
| **Ticket** | The change ticket and note given at publish, and the change hook's answer (**hook: allow**, **hook: deny**, **hook: error-allowed**) |

| Status | Meaning |
|---|---|
| **draft** | Saved, never published |
| **publishing** | Sent to the engine, waiting for it to report that it runs it |
| **published** | The version published most recently |
| **superseded** | Published before, replaced since |

Badges also show **running** (what the engine runs now) and **approved**.

Designers have:

- **Import running**: saves what the engine runs now as a new version, to start managing an engine
  that was configured by hand;
- **Import file**: imports an export file from another console (see
  [Export and import](#export-and-import));
- **New version**: opens the editor on a copy of the latest version (or of what the engine runs,
  when there is none).

When the console takes configuration from Git, a **Git** card above the list shows the repository,
branch and file, what a new commit becomes (a draft version, a validated one or a published one),
the last commit seen, the last sync and the last error. Designers have **Sync now**, which fetches
at once and says how many commits were taken in. See [Configuration as code](config-as-code.md#git-sync).

Choose a version to open it in the editor.

## The configuration editor (`/engines/{id}/configs/{version}`)

The editor shows one version, or a new draft. Anyone can read it; designers can edit it. Editing a
saved version and saving makes a new version; nothing changes on the engine until a version is
published.

It has four tabs: **Visual**, **YAML**, **Compare**, and, for designers on a saved version with no
unsaved edits, **Simulate**. **Visual** and **YAML** edit the same text: switch between them at any
time without losing anything.

### Visual: forms

The left column lists **Settings**, **Connectors**, **Views** and **Publications**; each group has
an add button for designers. A red mark beside an item counts the compiler errors about it, and the
item's form shows them under **The engine does not accept this yet**. For viewers, operators and
auditors every field is read-only.

| Item | The form covers |
|---|---|
| **Engine and attributes** | Multi-valued and sensitive attributes, seconds between full reads and between reconciliations, the longest retry wait, entity history (days and entries per entity), worker shards, store cache, data directory. The control API (`api`) is set on each engine host and is not edited here |
| **Connector** | **Connection** settings for its type (PostgreSQL, MySQL / MariaDB, SQL Server, MongoDB, LDAP directory, Active Directory, Debezium Server, JDBC bridge, CSV / flat file, SCIM 2.0, Entra ID), its **Objects** (what is read and how: scrape, delta or stream; for directory objects read by delta or stream, **Renamed container check (s)**, default 60, 0 off; for a CSV file, its columns, delimiter, encoding, done file and what a bad row does) and **Deletion safety** |
| **View** | **Sources** (each with its join rules and attribute flows), **Computed attributes** (steps, value mode, error policy and test cases; for **assign once**, **Keep for good, by key**), **References**, **Lookup tables**, **Precedence**, and a **Dependency graph** |
| **Publication** | Target directory, view, which entities (a CEL filter), DN and container, object classes and attributes, DN attributes, references, **Create missing containers** (and, with it, **Remove containers left empty**), **Leaving scope** (delete, disable, move, disown, or deprovision in stages, and the deletion thresholds), **Groups** and their **Group policy** (manual members, owner, expiry, review), **Active Directory** (account control, home folder, UPN, sAMAccountName, Exchange addresses), **Existing entries**, **Drift** and **Change safety** (change window, modification and add thresholds, canary, report mode). For SCIM and Entra ID targets: the resource type, how resources are matched, and licences |

New connectors, views and publications start from a template for their type, with placeholders to
fill in. Renaming an item carries the new name over to everything that refers to it. Each change in
a form changes one place in the YAML, so comments, key order and settings the forms do not show
(per-attribute precedence, watched tables mapped with SQL, `passwords`, `quality` checks, rule
`packages`) are kept; the form says when something must be edited in YAML.

Connector checks (designers): the engine runs these against the text being edited, saved or not,
and writes nothing.

- **Test connection** on a connector: connects and reports the server and how long it took, or why
  it cannot connect.
- **Browse tables**, **Browse collections** or **Browse the directory**: lists what the connector
  holds, with fields (types, keys) and sample records. Picking a table, collection or entry with
  **Read this table** (or **Read this collection**, **Read entries below this one**) fills in an
  object, with the key it found as the anchor.
- **Read sample records** on an object (optionally **at random**): reads records exactly as the
  engine would. Their fields are then offered in the attribute flows of the views that read it.

In a view:

- each computed attribute is compiled and its test cases run as you type; a test case can be
  written by hand (the input attributes and the expected value), starting with the attributes the
  rule reads. Reads of attributes the view does not have are flagged (the engine accepts them; they
  read as empty). On a saved, unedited version, **Studio** opens the view's computed attributes in
  the [Computed Attribute Studio](#the-computed-attribute-studio);
- lookup tables can be searched, edited, exported to CSV and imported from CSV (**merge** or
  **replace**);
- the **Dependency graph** shows which source supplies each attribute, what each computed
  attribute reads, and which target attributes are built from them. Hover an item to trace what it
  depends on and what depends on it.

### Visual: data flow

**Data flow**, above the forms, shows the configuration as a graph: source objects → views →
publications → target directories. Choose a node or an edge to open its form. Designers can drag
from an object to a view to add it as a source of the view, drag from a view to a directory to add
a publication, and select a source edge and press **Delete** to take it out of the view. Every
change goes into the YAML as the forms' do. **Forms** goes back to the forms.

### YAML

The configuration as text, in a code editor (Monaco), read-only for roles that cannot edit. The
YAML format is described in [Configuration](configuration.md) and the rule language in
[Computed attributes and rules](rules.md).

### Checking as you type (designers)

A moment after each edit, the engine's own compiler checks the text, rendered for the engine's
environment, and the **Check** panel shows:

- **Compiles: N connectors, N views, N publications**, or **Does not compile** with the errors;
- **All N test cases pass**, or which fail. The rule packages' own tests count too. A version whose
  test cases fail can be saved but not published (the engine refuses it too);
- **Golden tests: N of M publications as expected**, when the console takes configuration from Git
  and the repository has golden files (`tests/golden`). Each publication that differs opens with
  its diff. A failing golden test blocks publishing like a failing test case. The golden files are
  read at the branch head, not at the commit of the version being checked;
- rules whose tests cannot run because they read other entities (shown, not counted as failures);
- **warnings**: likely mistakes that do not stop the configuration, such as an attribute nothing
  sets.

### Packages

On a saved version that uses rule packages, the **Packages** panel (any role) lists each package
with its version, **pinned** when it carries a `pin`, where it comes from (`inline`, or `from` a
file), how many functions and tests it has, and its description. **not read** and an error mean the
file could not be read.

A package read `from` a file is read from the console's Git repository (at the version's commit for
a version from Git, at the branch head otherwise). The console keeps no package files of its own:
without Git sync, write packages inline. **Check for update** compares the file at the branch head
with the version's and says **Up to date with the branch**, or which version the file has now. How
packages are written is in [Computed attributes and rules](rules.md).

### Live preview (designers)

Views and publications have **Preview on sample records**. **Preview** runs the configuration as
edited over a few records the engine has read (5, 10, 25 or 50; optionally **at random**, or **read
sources now** instead of the engine's copies) in a scratch store, and shows the entities its joins,
flows and computed attributes make, where each value came from, and the DN and attributes each
publication gives them. It follows your edits; nothing is applied. Sensitive values are not shown.

### Compare

Side by side: **what the engine runs**, or any version, on the left, and the version or draft you
are editing on the right. **No differences** when they match.

On a saved version, **What changed** above the text says what changed by meaning: per section, the
connectors, views, publications, rules and packages added, removed or changed, with each changed
field's old value struck through and the new one beside it. Secret values show only as
`<redacted> (changed)`. **The same by meaning** says only comments or layout differ. **Rendered
for** picks the environment both sides are rendered for: the engine's own by default, another, or
**as written**. For a package read from a file, it shows the package's name, version, file and
pin, not the functions that changed; `syncd config diff` on a host shows those.

### Saving a version (designers)

**Save as a new version** (beside the editor) takes an optional comment ("What changed and why", up
to 500 characters) and **Save version**. The button is disabled while the text does not compile.

If someone saved a newer version since you started editing, the save is refused so it cannot
silently undo theirs. **Compare** to see the difference, or **Save anyway**.

### Simulate (designers)

On a saved version, **Run simulation** makes the engine run it against a copy of its current state,
the way a reload would (a full sync, then a reconciliation of every publication), and send nothing
to the targets. It reports how many entities change and any rule errors, then per publication:

- the number of **Adds**, **Modifies**, **Renames** and **Deletes**, and whether a deletion
  threshold would hold the deletes back;
- the operations themselves (the first 100 per publication; all are counted);
- **Attribute impact**: for each attribute, how many entries get a new value, the distribution of
  the new values, and samples.

Sensitive values are hidden. If your role may reveal them, **Show sensitive values (audited)** shows
them, and the reveal is recorded. Every simulation is recorded in the audit trail.

### Approve and publish (designers)

On a saved version with no unsaved edits:

- **Approve** marks the version approved by you. You cannot approve your own. When your
  administrator requires approval (four eyes), a version never published before shows **awaiting
  approval** and cannot be published until someone else approves it. A version published before
  can be published again without one, so a rollback is never held up.
- **Publish** opens a confirmation, then sends the version to the engine, rendered for the engine's
  environment. The engine checks it, saves it, reloads with it and runs a full sync so every entry
  follows the new rules. The console waits until the engine reports that it runs the version (up to
  a minute). If the engine cannot start with it, it goes back to its previous configuration and the
  console says **Not published** with the reason.

When your administrator has set a change-ticket pattern or a change hook, the confirmation also
asks for a **Change ticket** (required with a pattern: **Publish** stays disabled until it
matches) and an optional **Note**. With a hook, the change-management system is asked before the
publish goes ahead, and may refuse it with a reason. The ticket, note and answer are kept with the
version. See [Admin guide → The pre-publish hook and change tickets](admin-guide.md#the-pre-publish-hook-and-change-tickets).

**Publish** is disabled while the version is the one running, while any test case or golden test
fails, or while it awaits approval; hover over it for the reason. To roll back, open an earlier
version and publish it.

If the engine accepted a version but did not come back in time, the version shows **publishing**
until the engine reports it running; the versions list settles it.

The console has no **Promote** button: a version is promoted to another environment's engines with
`syncd config promote` or the API (see [Configuration as code](config-as-code.md#promotion)).

### Export and import

To take a configuration to another console (a test system's to production, say):

1. On the source console, open the version and choose **Export** (any role). The browser downloads
   `syncd-config-<engine>-v<n>.json`.
2. On the target console, open the engine's **Configuration** page and choose **Import file**
   (designers).
3. The file becomes a new draft version. The dialog lists what the engine host must provide
   before you publish: the environment variables and files the configuration reads secrets and CA
   certificates from, and the addresses its connectors connect to.
4. Compare the draft with what the engine runs, change what differs between the two systems, and
   publish it.

The `engine` and `api` sections stay with each engine host: the export leaves them out and the
import takes them from the target engine's running configuration, so the target engine must be
reachable. The file holds no secret values, only where secrets come from. It carries a SHA-256 of
the configuration that catches a truncated or edited file; it is not a signature. Within one
console, environments do the same job without files.

## The Computed Attribute Studio

`/engines/{id}/configs/{version}/studio`, opened with **Studio** from a saved version (or from a
view's form). It edits the computed attributes of every view in that version; anyone can open it,
and designers can edit.

- **Left**: each view's computed attributes. Designers can add one with **Computed attribute**.
- **Middle (Rule)**: the attribute's name, its **Value** mode (**recompute** whenever its inputs
  change, **assign once** to keep the first value while the entity exists, **only if empty** when no source
  supplies one; with **assign once**, **Keep for good, by key** names an attribute such as the
  employee number: the values are never given to anyone else, and someone who comes back with the
  same key gets them again), **On error** (**skip**, **default** with a **Default value**, or **quarantine** to
  hold the entity's exports for review), its **Steps** (move them up and down) and its **Output**.
  Each expression can be written as **CEL**, in an editor with completions, hover help and error
  markers, or as **Blocks** (if / then / else). The test cases kept with the rule are listed with
  **passes** or **fails**.
- **Right (Preview input)**: preview every step on a **Real entity** (**Find** one by search, or
  **Random entity**) or on **Hand-written** input. With a result, designers can **Keep as a test
  case**. **Dependencies** shows what the attribute **Reads** and what it is **Read by**, and the
  **Functions** catalog inserts a function at the cursor of the expression last edited. The
  version's rule packages are in the catalog too, under **package &lt;name&gt; &lt;version&gt;**,
  with their descriptions; they are offered as completions, and the preview calls them.
- **Quality check**: write a data-quality check for the view: a **Name**, a **Severity**, the
  **Kind** (**a condition every entity meets**, or **a value no two entities share**), the
  condition or value in the CEL editor, and a **Message**. As you type, the engine tries it on 50
  random entities of the view and says how many fail, with a few of them (links to the explorer).
  Designers can **Add to view**, which writes it into the view's `quality` list, saved with the
  version. A check added here only reports; quarantine and skipping publications are set in the
  YAML.

The preview uses the engine's own rules crate, so it computes what the engine would. **Save as new
version** (designers) asks for a comment and saves the result as a new version, written into the
YAML without disturbing the rest; review and publish it from the configuration page. The rule
language is described in [Computed attributes and rules](rules.md).

## The object explorer (`/engines/{id}/explore`)

Pick a **View**, type any attribute value (a name, uid or mail) and **Search**. The results show a
few attributes of each matching entity and its id, and how many were shown and examined; **More**
reads further. Choose a result to
open the entity:

- **Source records**: each record that joined the entity, by source and anchor;
- **Merged entity**: its attributes and which source each value came from;
- **Targets**: for each publication, the DN it has in the target and what was exported, or **Not
  exported**, and the error if its export failed;
- **History**: what happened to the entity, newest first: created, changed (each attribute before
  and after), deleted, joined, unjoined, exported, export failed, drift, password delivered, and
  what caused it. It is kept after the entity is deleted, for as long as the engine's history
  settings say; the newest entries of each entity are always kept, and so is all of the history of
  an entity with a held change or a dead letter. **Older** loads earlier entries.

**Lineage JSON** and **PDF** (any role) download everything the engine knows about the entity: each
attribute with the source record or rule it came from, the source records, joins and unjoins, what
each publication wrote for it over time (exports, failures, drift), its password deliveries (when
and to which target, never a value) and its history. Sensitive values are left out unless you have
revealed them on the page, in which case the download includes them and the reveal is recorded.
Each download is recorded in the audit trail. See
[Reports, lineage and data quality](reports-and-data-quality.md#lineage-export).

Operators can **Unjoin** a source record from an entity that has more than one: the record leaves
the entity and never joins it again by itself. It then correlates anew: it may join another entity,
become its own, or wait in correlation review.

Sensitive attributes are shown without values, and are not searched. If your role may reveal them,
use **Include sensitive attributes (audited)** on the search, or **Reveal sensitive attributes** on
an entity. Every search (with its text), every entity you open, and every reveal is recorded in the
audit trail.

## Correlation review (`/engines/{id}/correlation`)

Source records that joined no entity, because no join rule matched or because several entities did,
for the chosen **View**. The top says how many are unjoined, and **N waiting too long** counts those
older than the view's `correlation.unmatched_alert_after`.

Each record is a card with its source, anchor, how long it has been unjoined and its attributes. Its
badge is **best &lt;score&gt;** (green at or above the view's threshold) or **no suggestion**. Under
it, each **suggested** entity it may belong to:

| Column | Shows |
|---|---|
| **Score** | From 0 to 1, green at or above the threshold (the view's `correlation.accept_at`, 0.95 by default) |
| The entity's attributes | A few, to compare with the record |
| **Entity** | Its id, a link to the explorer |
| **Breakdown** | Per attribute compared, how it matched: **exact**, **normalized**, **similar**, **none** or **no value** (hover for the source and weight) |

Records unjoined by hand say so (**Unjoined by hand from …**), and those entities are not suggested
again.

Operators can:

- **Join** a record to one of its suggestions, or type an entity id (from the explorer) and
  **Join**;
- tick suggestions (one per record) and **Accept selected (N)**;
- **Accept all at or above &lt;threshold&gt;**: after a confirmation, joins every record shown whose
  best suggestion scores at least the threshold and whose next one does not. Records with two
  candidates over the threshold are left for a decision by hand.

The result says how many records were joined and refused, with the reason for each refusal.
Nothing joins without an operator. A record joined by hand stays joined whatever the join rules
say; a record joined to another entity moves. Each join is recorded in the audit trail and can be
undone with **Unjoin** on the entity's page. **More** loads further records.

Suggestions are made within one view only, compare text only, and do not find a typo in a value's
first three characters. How scoring and hints work is in
[Reports, lineage and data quality](reports-and-data-quality.md#correlation-assistance).

## Reports

### Downloading the reconciliation report

**Report CSV** and **PDF** on an engine's page (any role) download the engine's reconciliation
report: per publication, a summary (entities, entries exported, the last drift check, dead letters,
held changes, leavers, what report mode would do, groups, the last reconciliation), then one row per
entry with a difference. Nothing is read from the targets for it: it is what the engine found at its
last checks. Sensitive attributes are named, never shown.

- A download holds at most 200,000 rows by default; a cut one says so.
- The CSV opens in a spreadsheet as UTF-8. Cells that would start a formula get a leading `'`.
  There is no Excel (`.xlsx`) export.
- The PDF has a cover page and your organisation's top banner in every page's header and footer.
  It uses the PDF standard fonts: characters outside Latin-1 print as `?`. Use the CSV for names in
  other scripts.
- Every download is recorded in the audit trail.

### Scheduled reports (`/reports`)

**Reports** in the navigation (administrators, operators and auditors) lists the reports sent on a
schedule: the engine and publication, the schedule and hour (UTC), the format, where it goes (e-mail
addresses, a share), its owner, the **Next run** and the **Last run** (**sent**, with the time and
row count, or **not sent** with the reason). Notices at the top say when e-mail or the share is not
set up.

Operators and administrators choose **New scheduled report**:

| Field | Meaning |
|---|---|
| **Engine** | The engine reported on |
| **Publication** | One publication, or empty for all of them |
| **Format** | CSV or PDF |
| **Schedule** | **Daily**, **Weekly (Monday)** or **Monthly (the 1st)** |
| **Hour (UTC)** | 0 to 23 |
| **E-mail recipients** | Up to 20 addresses, separated by commas, in the domains your administrator allows (the hint names them) |
| **Write the file to the share** | And the **Directory inside the share**. The file is named `<engine>-<publication>-<date>.<csv\|pdf>` |
| **Enabled** | Untick to pause it (**paused**) |

On your own reports (administrators: on anyone's) the row has **Send now**, **Edit** and **Delete**
(no confirmation). Each run checks again that the owner is still an administrator or operator and
that every recipient is allowed; a run that fails a check sends nothing. A scheduled report's file
holds at most 50,000 rows by default; the mail says when it was cut.

## Errors you may see

| Message | Meaning |
|---|---|
| "the &lt;role&gt; role cannot do this" | Your role does not allow the action |
| "your role may not reveal sensitive attributes" | Your role is not in `CONSOLE_REVEAL_ROLES` |
| "the configuration does not compile" | Fix the errors in the **Check** panel first |
| "not published: N of M test cases fail" | Every test case, package test and golden test must pass before publishing |
| "not published: version N needs approval by someone other than its author" | Four eyes is on; ask another designer or an admin |
| "a publish needs a change ticket reference matching …", "the ticket reference … does not match …" | Your administrator requires a change ticket in that form |
| "not published: the change hook refused version N: …" | The change-management system said no; the reason follows |
| The change hook's error, with status `503` | The change-management system did not answer, and the console refuses to publish without it; try again, or ask your administrator |
| "the configuration cannot be rendered for environment X: …" | The engine's environment lacks a value the version needs (`{{ env.… }}`); ask an administrator to add it on **Integrations** |
| "the engine could not start with vN and went back to …" | The engine refused the configuration; its log says why |
| "the engine accepted vN but did not come back within 60 seconds" | The version shows as **publishing** until the engine reports it |
| "the audit log cannot be written, so this was not done" | The console refuses actions it cannot record; tell your administrator |
| **unreachable** on an engine | The console cannot reach the engine's control API; see [Troubleshooting](troubleshooting.md) |
| A command refused with the active engine's name | The engine is the passive one of an HA pair: use the active engine's page |
