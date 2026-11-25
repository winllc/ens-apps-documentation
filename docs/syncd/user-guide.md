# User guide

This guide walks through every screen of the syncd web console, for **designers** (who build and
publish configurations) and **operators** (who watch engines and act on what they report). What you
can do depends on your roles; each section says which role an action needs. Accounts, roles, sign-in
policy, banners, engine registration, API tokens, webhooks, environments and the audit trail are in
the [Admin guide](admin-guide.md); the license and the **License** page are in
[Licensing](licensing.md). People who only want to change their own directory password use the
[self-service page](#changing-your-directory-password-password), which needs no console account.

It describes syncd 0.6.1 (tag `v0.6.1`, commit `59cbf19`). New screens since 0.4.1 are
[Unmanaged entries](#unmanaged-entries-enginesidunmanaged),
[Custom functions](#custom-functions-enginesidfunctions), the **Promote** dialog and the
**License** page; 0.5.0 also added a message of the day and your previous sign-in on the
[Engines](#engines) page. What 0.5.1 changes on screen: editors ask before you leave with unsaved
changes, **Disable** and **Reset password** ask first, the Studio checks for a newer version before
it saves, lists of DNs and change windows take one value per line, and the **Engines** page polls
instead of streaming. 0.5.4 stops calling a rule that reads other entities broken (see
[Rules that read other entities](#rules-that-read-other-entities)). 0.5.5 gives up a request
after ten minutes, with a message, refuses a one-time password after seven days, refuses a PDF
report of more than 20,000 rows, and refuses a second publish to an engine at once rather than
waiting for the first (see [Errors you may see](#errors-you-may-see)). 0.6.0 adds the
**DSML files (air gap)** connector type, with its form and **Browse the files** (see
[Visual: forms](#visual-forms) and [Air-gapped synchronisation](air-gap.md)). 0.6.1 changes
nothing on screen.

## Roles at a glance

| You can | Role needed |
|---|---|
| Look at everything below: engines, status, held changes, metrics, dead letters, data quality, unmanaged entries, custom function modules, configuration versions, the Studio, the object explorer, correlation suggestions; download reports and lineage | Any role |
| Edit, check, save, preview, simulate, import, approve, publish and promote configuration; run connector checks; upload and approve modules of custom functions; sync from Git | **designer** or **admin** |
| Run reconciliations, full syncs, drift checks and unmanaged-entry checks; adopt an entry; approve held-back deletes and held changes; review groups; retry and discard dead letters; join, unjoin and accept suggestions; schedule reports | **operator** or **admin** |
| See the scheduled reports | **operator**, **auditor** or **admin** |
| Reveal sensitive attribute values | A role listed in `CONSOLE_REVEAL_ROLES` (by default **admin** only) |

Since 0.5.0 an account can hold several roles, and each can be limited to one environment or one
engine (`operator on prod`, `designer on engine hr-1`): you see only the engines your roles reach,
and on each engine's pages only the actions your roles allow there. An engine outside your roles
does not appear anywhere: its page says "No engine with id N", as for an id never used. Git sync's card and **Sync
now** need a role on everything.

A designer cannot run engines, and an operator cannot change configuration, unless they hold both
roles. Buttons your roles cannot use are not shown, and the server refuses the request if it is sent
anyway. The full matrix is in [Admin guide → Permissions matrix](admin-guide.md#permissions-matrix).

## Signing in

Open the console's address (normally `https://` and the host name your administrator gives you).
If your organisation set banners, they appear across the top and bottom of every page, the sign-in
page included.

When your administrator has turned on the **notice and consent** banner, the sign-in page first
shows the notice (**Notice and consent**) and nothing else: read it and choose **I acknowledge and
consent**. The console refuses any sign-in, by password, single sign-on or certificate, without
that acknowledgement; it stands for about a quarter of an hour and serves one sign-in, and is
recorded in the audit trail with the session it led to.

=== "Console account"

    Enter the **Username** and **Password** your administrator gave you, and choose **Sign in**.

    - A new account, or one whose password was reset, has a **one-time password**: the console
      then asks you to choose your own (**Choose a new password**) before anything else. Since
      0.5.5 a one-time password not used within seven days is refused ("this one-time password
      has expired: ask an administrator for a new one"). The hint
      under the new password gives your organisation's rules: by default at least 12 characters
      and not your username. The same page comes up when your password is older than your
      organisation allows.
    - A failed sign-in always says "sign-in failed; after repeated failures an account locks for
      15 minutes", whatever the lockout settings. By default, 5 failures lock the account for 15
      minutes; your administrator may have set other limits, or a lock that lasts until an
      administrator unlocks the account. More than 20 attempts from one address in 5 minutes are
      refused with "too many attempts; try again later". The **Engines** page tells you after
      you sign in how many attempts on your account were refused since your previous sign-in.

=== "Single sign-on or certificate"

    If single sign-on is set up, the page shows **Sign in with &lt;provider&gt;** (one button per
    provider, OpenID Connect or SAML). You sign in at your organisation's identity provider and
    come back to the page you asked for. With certificate sign-in, **Sign in with a certificate
    (CAC/PIV)** (or the label your administrator chose) signs you in with the certificate your
    browser presented, such as your smart card's. Your roles come from the provider's groups, or
    from the certificate, at each sign-in. If the provider refuses you, or nothing maps to a role,
    you come back to the sign-in page under **Single sign-on failed** (or **Certificate sign-in
    failed**), with the reason.

    When your administrator has limited local sign-in to break-glass accounts, the password form
    is folded away behind **Break-glass sign-in**. It is for when the identity provider is down;
    only accounts marked break-glass can use it, and every attempt is audited.

Other messages on the sign-in page:

- **Signing in will not work at this address**: the console's session cookie is kept only over
  HTTPS or on `localhost`. Use the console's `https://` address.
- **Development mode**: a test installation that accepts `admin` / `admin`. Never seen in
  production.

A session ends after **30 minutes** without activity or **12 hours** in all (your administrator may
have changed these, and may have set a shorter idle limit for administrators, or a number of
sessions per account beyond which a new sign-in ends your oldest). Only what you do counts as
activity: since 0.5.1 a page that refreshes itself, left open, no longer keeps the session alive.
The console then sends you to sign in, and back to the page you were on; whatever the tab had
fetched is cleared, so the next account to sign in there does not see your pages. Unsaved edits are
lost when the session ends. **Sign out** is at the top right; it ends the console session, not the
identity provider's. If the console does not confirm the sign-out, a **Not signed out** notice says
so and you are still signed in: try again.

### Changing your console password

Go to `/change-password` (type it after the console's address): the current password, the new one
twice, **Set password**. The hint under the new password gives the rules (length, the kinds of
character asked for, the username); your organisation may also refuse one of your recent passwords.
Your other sessions are signed out. A wrong current password counts toward your account's lockout,
as a failed sign-in does (since 0.5.1). Accounts that sign in through single sign-on or a certificate
have no console password; change it at the identity provider.

### Changing your directory password (`/password`)

When your organisation uses syncd's password synchronisation and has turned the page on, anyone
can change their own **directory** password at `/password` on the console's address. You need no
console account, and no role.

1. Enter your **User name** (the one you sign in to your organisation's directory with), your
   **Current password**, and the **New password** twice.
2. Choose **Change password**.

The hint under **New password** gives your organisation's rules: by default at least 12
characters, mixing three of lower case, upper case, digits and symbols, and not containing your user
name (the directory's own rules apply too). A refusal says why. A wrong user name or password always
gets the same answer, and after repeated failures the page refuses your user name for 15 minutes. Once it says **Your
password was changed**, it may take a minute to work everywhere you sign in.

If the page says "Password changes are not available here", it is not turned on: ask your
administrator. How it works is in [Password synchronisation](password-sync.md#people-changing-their-own-password).

## The page layout

- **Header**: the syncd logo (back to **Engines**), the navigation (**Engines**; **Users** and
  **License** for administrators of everything; **Audit log** for administrators and auditors;
  **Reports** for administrators, operators and auditors; **Integrations** for administrators and
  auditors), a **FIPS** badge when the console runs in FIPS mode, your name and your role (or
  how many roles you hold, listed on hover), and **Sign out**. On a narrow window the header wraps
  onto several lines.
- **License banner**: while the license needs attention, a banner under the header says so on every
  page (red once it blocks publishing configuration changes: sync keeps running). See
  [Licensing](licensing.md).
- **Footer**: the console version.
- Each browser tab is titled after the page's heading, as "&lt;Page&gt; · syncd console". From the
  keyboard, the first Tab reaches **Skip to main content**, which jumps past the banners and the
  navigation. In a code editor (the YAML, the comparison, the Studio's expressions), Tab indents:
  press **Escape, then Tab**, to leave the editor, as the hint under each editor says.
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

The cards are refreshed every ten seconds (since 0.5.1; 0.5.0 held a live status stream open per
card, which could take every connection the browser allows to the console and leave the page
none). The small charts place each point by its time over the last minute. CPU is averaged over the
last five seconds or so.

Above the cards, when an administrator has set one, is the **Message of the day**, with who posted
it and when. Under it a line gives **Your previous sign-in**: when, how (with a password, through
single sign-on, with a certificate) and from which address, or "This is your first sign-in to this
console." When attempts to sign in to your account with its password were refused since then (a
wrong password, the account locked or disabled), a **Refused sign-in attempts** warning says how
many: if they were not yours, tell an administrator.

Above the cards, a line gives the **managed identities** counted against the license's tier (or
"no license: evaluation"), with the license's state when it needs attention. Administrators of
everything also see a notice for each console setting that names an engine no longer registered
(after a rename, say).

Choose a card to open the engine. Administrators also have **Register engine** here.

## An engine's page (`/engines/{id}`)

The header shows the engine's address, its version, uptime, the configuration it runs and
**FIPS** if it runs in FIPS mode, with buttons to **Metrics**, **Explore**, **Correlation**,
**Data quality**, **Unmanaged entries**, **Configuration**, **Dead letters** and **Custom
functions**, the report downloads **Report CSV**, **Excel** and **PDF** (see
[Downloading the reconciliation report](#downloading-the-reconciliation-report)), and **Remove
engine** for administrators of everything.

The page follows the engine's **live status stream**: it updates by itself, about once a second.
If the stream drops, a red notice says it is reconnecting and the last status stays on screen,
refreshed every ten seconds until the stream is back. Since 0.5.1 the page opens a new stream by
itself after the engine restarts (waiting longer each time, up to a minute), where 0.5.0's stayed
down until the page was reloaded.

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
- **The engine's control API is in compatible mode** (an engine upgraded from 0.4.x): any client
  certificate of its CA may apply a configuration and approve held changes without the console.
  Whoever runs the engine host opts in to strict access; see
  [Admin guide → Engines](admin-guide.md#engines).
- **The engine runs a configuration this console did not publish**: it was applied around the
  console (through the control API, or by editing the file on the engine's host). Compare the
  version the console last published with what runs, and publish the version that should run.
- A line saying who applied the running configuration, whether it was **signed**, and the control
  API's access mode.
- **License**: what the running configuration uses that the license leaves out, or the license's
  problems.

### Run (operators)

| Button | What it does |
|---|---|
| **Reconcile all** | Compares each target with what it should hold, and writes the differences |
| **Full sync** | Recomputes every entity from its sources first, then reconciles. Use it after rule changes |
| **Check drift** | Reads the entries back from the targets and acts on what was changed there, by each publication's drift policy |
| **Reconcile** (on a publication's row) | Reconciles that publication only |

Each returns when the engine is done and shows its summary. Every run is recorded in the audit
trail, and its end raises the `run.completed` or `run.failed` [webhook](webhooks.md) event.

### Deletes held back by a deletion threshold

When a change would delete more of a target than a publication's threshold allows (since 0.5.5,
counting leavers it would disable or move aside), the engine holds those deletes and the page
lists them: the scope, how many deletes of how many objects, and since
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
- **Passwords** (with password synchronisation): changes **Delivered**, **Failed**, **Dropped
  (report mode)** and **Waiting**, and **Initial passwords set** and **refused** on new entries, with
  the last failure (since 0.5.0 an initial password the directory refuses is tried again up to
  three times and then goes to the dead-letter queue); then a table of each publication's deliveries (**Delivered**, **Failed**,
  **Dropped**, **Waiting**). A badge beside the title says whether the engine's password keys are
  sealed (**keys sealed**) or not (**password keys stored in the clear**: whoever runs the engine
  host can seal them with `SYNCD_PASSWORD_KEY_PASSPHRASE_FILE`; see
  [Password synchronisation](password-sync.md)).
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
clocks again and brings an expired group back. Since 0.5.0 a review that falls due also reaches
someone: the group's `reviewers` get a mail from the console (when mail is set up and their addresses
are in the allowed domains), and the `group.review-due` webhook
event and the `SyncdGroupReviewsDue` alert fire. See
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
| **Last 24 hours** | A small line of how many failed each hour, from the engine's own hourly trend, with the peak |
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

## Unmanaged entries (`/engines/{id}/unmanaged`)

New in 0.5.0. **Unmanaged entries** on an engine's page lists, per publication, what the engine's
last unmanaged-entries pass found in the target that syncd does not manage: entries no publication
exported, and members of privileged groups that no rule accounts for. These are entries of the
*target*; the engine card's "orphans" are source records that joined no entity. The targets are read,
never written, except by **Adopt**. The pass runs after each scheduled drift check of a publication
with `drift` (unless its `unmanaged: { scheduled: false }`), or with **Check now**.

Each publication's card says how many of the entries read are unmanaged and how many an
`unmanaged.ignore` pattern hides (and the same for privileged groups' members), when it was checked
and under which bases, then:

| Column | Shows |
|---|---|
| **Entry** | The DN |
| **Class** | Its object class |
| **Created** | When the directory created it, or **unknown** |
| **Last sign-in** | Where the directory keeps one, with the attribute it came from |
| **State** | **enabled**, **disabled** or **locked**, or **unknown** |

and, for privileged groups, the **Privileged group**, the **Member no rule accounts for**, and
whether **The member is** a managed or an unmanaged entry (**through a nested group** when it is; in
Active Directory nested groups are resolved). Long lists show the first entries and how many more
there are; `syncd unmanaged` on the engine host lists them. A pass that stopped says why, and its
counts are partial. **Not checked yet** means no pass has run.

Operators have:

- **Check now**: reads the targets again (recorded as `engine.check_unmanaged`);
- **Adopt** on an entry: asks for the **Entity id** (32 hex digits, as the object explorer shows it)
  of an entity that has no entry in that publication yet. The entry becomes that entity's entry: it
  is moved to where the publication places the entity, given the attributes the publication
  exports, and managed from then on. Recorded as `engine.adopt_entry`; `syncd adopt` does the same
  on the host.

An entry whose person is in a source but joined nothing is a matter for
[correlation review](#correlation-review-enginesidcorrelation); to stop listing a service account,
add it to the publication's `unmanaged.ignore`. Unmanaged entries are listed for LDAP and Active
Directory targets, and for SCIM and Entra ID users (no privileged groups there). The release notes
say 389 Directory Server and Ping were unit-tested only, and no real Entra tenant or SCIM provider
was used.

## Custom functions (`/engines/{id}/functions`)

New in 0.5.0. **Custom functions** on an engine's page lists the WebAssembly modules uploaded for that
engine, which a rule package can name by their SHA-256 (see
[Computed attributes and rules → Custom functions in WebAssembly](rules.md#custom-functions-in-webassembly)):
each module's name, the start of its **sha256**, its size, how many **Functions** it declares, who
uploaded it and when, and its **Approval** (**approved by …** or **waiting for review**).

1. A designer of the engine chooses **Upload a module**, gives a **Name** (lower-case letters,
   digits, `-` and `_`; uploads of one name are reviewed against the one before), the **Module
   (.wasm)** file (at most 1 MiB) and an optional **Comment**, and **Upload**. The console checks the
   module as the engine would (no imports, the expected exports, a manifest declaring its functions,
   the FIPS policy) and keeps it by its SHA-256 (`wasm_module.upload`).
2. Someone else who edits the engine's configuration opens the module: its sha256, size, uploader,
   its **Functions** with their signatures, the **Changes** from the previous upload of the same
   name, and **Download** for the module itself. **Approve** records the approval
   (`wasm_module.approve`). The uploader cannot approve their own module (the database refuses it
   too), and neither an approval nor a module can be changed afterwards.
3. A package of the configuration names the module by its hash, without `from`:
   `wasm: { sha256: "…" }` (the review shows the line to copy). The console fills the approved module
   in when it checks, previews and publishes the configuration. A module not uploaded or not yet
   approved makes the configuration fail its check.

With Git sync, a module can instead sit in the repository beside its package file (`from`), and is
reviewed with the change that adds it. When the license does not include the Extensibility pack,
custom functions are locked (see [Licensing](licensing.md)).

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

- **Promote**: publishes a version that runs on one engine to every engine of another environment
  (see [Promoting a version](#promoting-a-version)). It shows once some version of the engine has
  been published;
- **Import running**: saves what the engine runs now as a new version, to start managing an engine
  that was configured by hand. Since 0.5.1 the environment's secret values in it go back to being
  placeholders (`{{ env.name }}`), so the version holds none, and the bytes of custom-function
  modules are left out (each module's pin stays; the module store fills them in again);
- **Import file**: imports an export file from another console (see
  [Export and import](#export-and-import));
- **New version**: opens the editor on a copy of the latest version (or of what the engine runs,
  when there is none).

When the console takes configuration from Git, a **Git** card above the list shows the repository,
branch and file, what a new commit becomes (a draft version, a validated one or a published one),
the last commit seen, the last sync and the last error. The repository holds every engine's
configuration, so the card shows only to accounts with a role on everything; designers on everything
have **Sync now**, which fetches at once and says how many commits were taken in. See
[Configuration as code](config-as-code.md#git-sync).

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
| **Connector** | **Connection** settings for its type (PostgreSQL, MySQL / MariaDB, SQL Server, MongoDB, LDAP directory, Active Directory, Debezium Server, JDBC bridge, CSV / flat file, SCIM 2.0, Entra ID, new in 0.5.0 the REST / OData API, Workday, SAP SuccessFactors and UKG Pro sources, whose fields, paging and delta settings are edited in the YAML, and new in 0.6.0 **DSML files (air gap)**), its **Objects** (what is read and how: scrape, delta or stream; for directory objects read by delta or stream, **Renamed container check (s)**, default 60, 0 off; for a CSV file, its columns, delimiter, encoding, done file and what a bad row does; for DSML files, **Base DN**, **Scope**, **Filter** and **Attributes**, read as the files arrive) and **Deletion safety**. A DSML connector's **Connection** has the **Location** (a directory on the engine's host, or `sftp://`, which brings up **Host keys**, **Password**, **Private key (Ed25519, ECDSA)**, **Key passphrase** and **Timeout (seconds)**), the **File name prefix**, the **Directory server the files are for**, **Seconds of changes per file**, **Done files**, **Snapshots at full reconciliations** and **Largest file (bytes)**, and under **Reading** the two **Files read** settings (which names are read, and what becomes of a file once read), **Seconds between looks**, **A file missing from the sequence**, **Require checksums** and **An empty snapshot empties the source** |
| **View** | **Sources** (each with its join rules and attribute flows), **Computed attributes** (steps, value mode, error policy and test cases; for **assign once**, **Keep for good, by key**), **References**, **Lookup tables**, **Precedence**, and a **Dependency graph** |
| **Publication** | Target directory, view, which entities (a CEL filter), DN and container, object classes and attributes, DN attributes, references, **Create missing containers** (and, with it, **Remove containers left empty**), **Leaving scope** (delete, disable, move, disown, or deprovision in stages, and the deletion thresholds), **Groups** and their **Group policy** (manual members, owner, expiry, review), **Active Directory** (account control, home folder, UPN, sAMAccountName, Exchange addresses), **Existing entries**, **Drift** and **Change safety** (change window, modification and add thresholds, canary, report mode). For SCIM and Entra ID targets: the resource type, how resources are matched, and licences |

New connectors, views and publications start from a template for their type, with placeholders to
fill in. A connector type the license does not include is marked **(locked: &lt;pack&gt; pack)** in
the list and cannot be added; a connector of such a type already in the configuration shows
**Locked by the license**: a configuration that uses it cannot be saved or published (see
[Licensing](licensing.md#packs)). Renaming an item carries the new name over to everything that refers to it. Each change in
a form changes one place in the YAML, so comments, key order and settings the forms do not show
(per-attribute precedence, watched tables mapped with SQL, `passwords`, `quality` checks, rule
`packages`) are kept; the form says when something must be edited in YAML.

- **Lists of values** are typed comma-separated, except where a value holds commas itself: since
  0.5.1 **Groups never touched** (a list of DNs) and a change window's **When changes may be
  applied** take one value per line, and any list written in the YAML whose values hold commas is
  shown one per line too. 0.5.0 split a DN at its commas.
- **Secrets** are references, never values: an environment variable, a file, or, shown in the forms
  since 0.5.1, a secret store (**HashiCorp Vault** with its path and key, **AWS Secrets Manager**
  with the secret and an optional JSON key, **Azure Key Vault** with the vault and secret name).
  The store's other settings stay as written in the YAML, and a reference left behind when you pick
  another kind comes back if you pick its kind again.

Connector checks (designers): the engine runs these against the text being edited, saved or not,
and writes nothing.

- **Test connection** on a connector: connects and reports the server and how long it took, or why
  it cannot connect.
- **Browse tables**, **Browse collections** or **Browse the directory**: lists what the connector
  holds, with fields (types, keys) and sample records. Picking a table, collection or entry with
  **Read this table** (or **Read this collection**, **Read entries below this one**) fills in an
  object, with the key it found as the anchor.
- **Browse the files**, for a CSV or (since 0.6.0) a DSML connector: lists the files in its
  directory. For DSML they come newest first, and a file shows the entries it carries whole (a
  snapshot's entries, a file of changes' adds). **Test connection** on a DSML connector signs in
  to the SFTP server, if there is one, lists the directory and says how many files are there.
- **Read sample records** on an object (optionally **at random**): reads records exactly as the
  engine would. Their fields are then offered in the attribute flows of the views that read it.
  A DSML object's sample shows its entries as the newest files carry them.

In a view:

- each computed attribute is compiled and its test cases run as you type (except one that reads
  other entities: see [Rules that read other entities](#rules-that-read-other-entities)); a test case can be
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
a publication, and select a source edge and press **Delete** to take it out of the view. Since 0.5.0
**Delete** on a selected node no longer takes it off the canvas (it never took it out of the
configuration). Every change
goes into the YAML as the forms' do. **Forms** goes back to the forms.

Under the graph, **Connections** lists the same edges as text, each with **Open** and, for designers,
**Remove**, and two forms that make the edits the canvas takes by dragging: **Add a source** (an
object and a view, **Add source**) and **Publish** (a view and a directory, **Publish view**). They
are for the keyboard, for a screen reader, and for a window too narrow for the canvas.

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
- **Golden tests: N of M publications as expected (at commit …)**, when the console takes
  configuration from Git and the repository has golden files (`tests/golden`). Each publication
  that differs opens with its diff. A failing golden test blocks publishing like a failing test
  case. A saved, unedited version is checked against the golden files of **its own Git commit**
  (the commit Git sync took it from, or the branch head when it was saved); an edited text, like any
  draft, against the branch head. A version saved before 0.5.0 has no commit: it is checked at the
  branch head, and a note says so;
- what the configuration uses that the license does not include, naming the connector or setting
  and the pack: such a configuration does not compile here, cannot be saved and cannot be published;
- rules whose tests cannot run because they read other entities (shown, not counted as failures);
- **warnings**: likely mistakes that do not stop the configuration, such as an attribute nothing
  sets. Since 0.5.4 also a reference nothing sets, and an attribute a rule reads of another view's
  entities that nothing in that view sets: both leave a rule such as a computed `memberOf` empty
  (see [Validation rules](configuration.md#validation-rules)).

### Packages

On a saved version that uses rule packages, the **Packages** panel (any role) lists each package
with its version, **pinned** when it carries a `pin`, where it comes from (`inline`, or `from` a
file), how many functions and tests it has, and its description. A package that carries custom
functions has a **WebAssembly** badge and a line naming its module (`from` a file, or from the
engine's module store), the start of its SHA-256 and its size. **not read** and an error mean the
file could not be read.

A package read `from` a file is read from the console's Git repository at the version's commit
(kept with each version since 0.5.0; an older version is read at the branch head). The console keeps
no package files of its own: without Git sync, write packages inline (its module store holds
WebAssembly modules only; see [Custom functions](#custom-functions-enginesidfunctions)).
**Check for update** compares the file at the branch head with the version's and says **Up to date
with the branch**, or which version the file has now. How packages are written is in
[Computed attributes and rules](rules.md).

### Live preview (designers)

Views and publications have **Preview on sample records**. **Preview** runs the configuration as
edited over a few records the engine has read (5, 10, 25 or 50; optionally **at random**, or **read
sources now** instead of the engine's copies) in a scratch store, and shows the entities its joins,
flows and computed attributes make, where each value came from, and the DN and attributes each
publication gives them. It follows your edits; nothing is applied. Sensitive values are not shown.

A view's second and later sources are joined as the edited join rules say, also where the running
engine does not join them yet (a new view, a renamed one, a new join rule): the preview looks among
the engine's copies of those sources for the records the rules join to the sampled entities, at most
3 per sampled record among the first 200,000 records of each source, with a note when it stopped
there. Other sources of the view that project and have no join rules are sampled like the first.
Before 0.5.0 such a preview showed only the first source's records.

### Compare

Side by side: **what the engine runs**, or any version, on the left, and the version or draft you
are editing on the right. **No differences** when they match. What the engine runs shows the
environment's secret values as `<secret name>`, whatever **Rendered for** says (0.5.0 masked only
the values of the environment the comparison was rendered for, none **as written**). By default a saved version is
compared with the version before it, and, since 0.5.1, a draft or an unsaved edit with what it
started from; a draft or edit is compared as text only.

On a saved version, **What changed** above the text says what changed by meaning: per section, the
connectors, views, publications, rules and packages added, removed or changed, with each changed
field's old value struck through and the new one beside it. Secret values show only as
`<redacted> (changed)`. **The same by meaning** says only comments or layout differ. **Rendered
for** picks the environment both sides are rendered for: the engine's own by default, another, or
**as written**. Each side's packages are read at that side's own Git commit (and modules from the
engine's module store), as `syncd config diff` reads them from files: a package whose version changed
reads `gov-naming 1.0.0 -> 1.1.0`, with the functions that changed, and a WebAssembly module shows
its pin, size and the signatures added or removed, never its bytes. A package that cannot be read on
a side (a version older than 0.5.0 whose file has moved on, a module not approved) is compared by
name, version, file and pin, and **How packages were compared** above the changes says which and
why.

### Saving a version (designers)

**Save as a new version** (beside the editor) takes an optional comment ("What changed and why", up
to 500 characters) and **Save version**. The button is disabled while the text does not compile.

If someone saved a newer version since you started editing, the save is refused so it cannot
silently undo theirs. **Compare** to see the difference, or **Save anyway**. Since 0.5.1 the
[Studio](#the-computed-attribute-studio) checks the same way.

Leaving the editor (a link, the browser's Back button) with unsaved edits asks **Leave without
saving?** first (since 0.5.1): **Stay**, or **Leave and discard**. The browser also asks before the
tab is closed or reloaded.

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

- **Approve** marks the version approved by you. You cannot approve your own: since 0.5.1 that
  includes one saved with an API token you created, and one from a Git commit whose author's e-mail
  address (or the part before the `@`) is your username. When your
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
fails, or while it awaits approval; hover over it for the reason. The server checks the test cases
and golden tests again on every publish, also one through the API, Git sync or promotion. Since
0.5.1 golden tests that cannot be read from Git count as failing, so the publish is refused, saying
why. To roll back, open an earlier version and publish it.

Since 0.5.1 one publish to an engine runs at a time: a second one waits for the first to be
recorded, and is refused ("another publish to … is still under way") after 150 seconds.

The license is checked at publish too: a version that uses a connector or feature outside the
license's packs is refused, and so is any version never published to the engine once the license's
grace period is over ("not published: publishing configuration changes is blocked by the license").
Publishing again a version that was published to that engine before, a rollback, is always allowed,
and recorded as such. Sync keeps running either way. See [Licensing](licensing.md).

The console signs every configuration it publishes. An engine that requires signed configurations
applies nothing else, so on such an engine the console is the only way to change the configuration.

If the engine accepted a version but did not come back in time, the version shows **publishing**
until the engine reports it running; the versions list settles it.

### Promoting a version

**Promote** on an engine's **Configuration** page (new in 0.5.0) publishes a version that runs on one
engine to every engine of another environment, rendered for that environment, through the same API
and the same gate as `syncd config promote` (see [Configuration as code](config-as-code.md#promotion)).
In the **Promote a version** dialog:

1. pick **From engine** (this one, preselected), its **Version** (only a published version can be
   promoted) and **To environment** (every engine running in it gets the version);
2. give a **Change ticket** (required, and checked against the pattern, when your administrator set
   one) and an optional **Note**;
3. choose **Preview**: a dry run that checks your roles on every engine of the target environment
   and shows, per engine, what would happen (`would_publish`, or `running` when the engine runs that
   text already) and a one-line summary of what changes there, as the command prints it. Nothing is
   saved, published or recorded;
4. choose **Promote** (it stays disabled until a preview succeeded; changing a field asks for a new
   one). The dialog then shows each engine's outcome (`published`, `pending_approval`, `running` or
   `failed`, with the reason) and how many engines published.

Each target engine gets the text as a version of its own, and the usual checks apply there: your
roles on every engine of the environment (a role that misses one promotes nothing), the license,
approval by someone else, the test cases, the golden tests and the change hook. The preview does
not run the tests or the hook.

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
import takes them from the target engine's running configuration (since 0.5.1 with its secret
values put back as placeholders), so the target engine must be reachable. Since 0.5.1 a version
written with `{{ env.* }}` placeholders can be exported and imported, the placeholders kept as
written for the target console's own environments, and an import is checked as a save is
(rendered for the engine's environment, packages and modules read, the license and the size
limit; a module written into the file is refused). The file holds no secret values, only where
secrets come from. It carries a SHA-256 of
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
  with their descriptions; they are offered as completions, and the preview calls them. A package's
  custom functions in WebAssembly are listed with their signatures
  (`checksum.luhn_digit(digits: string) -> string`), marked **(WebAssembly)**, and run in the preview
  and the tests as the engine runs them (with the default limits, whatever the engine's
  `engine.wasm_functions` says). When the license does not include them, the catalog says they are
  **Locked**.
- An attribute whose name is not a CEL identifier, such as `mail-alias`, is completed as
  `e["mail-alias"]`, and one the view does not have is flagged.
- **Quality check**: write a data-quality check for the view: a **Name**, a **Severity**, the
  **Kind** (**a condition every entity meets**, or **a value no two entities share**), the
  condition or value in the CEL editor, and a **Message**. As you type, the engine tries it on 50
  random entities of the view and says how many fail, with a few of them (links to the explorer).
  Designers can **Add to view**, which writes it into the view's `quality` list, saved with the
  version. A check added here only reports; quarantine and skipping publications are set in the
  YAML.

The preview uses the engine's own rules crate, so it computes what the engine would. **Save as new
version** (designers) asks for a comment and saves the result as a new version, written into the
YAML without disturbing the rest; review and publish it from the configuration page. Since 0.5.1,
as in the editor, the save is refused when someone saved a newer version since the Studio opened:
**Compare** shows the newest version against what the Studio would save, and **Save anyway** saves
regardless. Leaving the Studio with unsaved edits asks first, and a test case's number and boolean
inputs stay numbers and booleans when you edit the rule. The rule language is described in
[Computed attributes and rules](rules.md).

### Rules that read other entities

A computed attribute that reads other entities, with `ref()`, `refs()`, `reports()` or
`referrers()`, needs the rest of the configuration: the view's references and the entities they
find. The preview has neither, so it does not run such a rule. Since 0.5.4 the Studio and the
visual editor say so with a note, **Reads other entities** (*ref(), refs(), reports() and
referrers() need the rest of the configuration, so this rule is not previewed or tested here. The
configuration's check compiles it with the view's references*), and its test cases are marked
**not run here**. Errors in such a rule show in the **Check** panel
([Checking as you type](#checking-as-you-type-designers)), which compiles the whole configuration.
Before 0.5.4 both showed **Does not compile** for such a rule, although the configuration
compiled.

The **Functions** catalog lists `referrers(e, "reference")` with the group membership example,
`referrers(e, "member").map(g, g.dn)`: a person's groups, when the groups' view declares `member`
as a reference to the people's view and flows the groups' DNs from `source.dn`. See
[Computing memberOf from groups](rules.md#computing-memberof-from-groups).

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
audit trail; a search that reveals is recorded with a digest of its text instead. Since 0.5.1 such a
search also stays out of the address bar and the browser's history (Back still returns to it), so
its text is not in a bookmark or a shared link; other searches are in the address, to share.

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

When the view lists `correlation.other_views`, a second table under a record shows entities of those
views it **looks like**, with the **View**, the **Score**, a few attributes, the **Entity** and the
**Breakdown**. The record's source is no source of those views, so it cannot join them: the table is
a place to look, not a suggestion to accept.

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

Suggestions are joins within one view only (other views are only pointed to) and compare text only.
Since 0.5.0 they also find a typo in a value's first characters, and values one typo apart (a
character replaced, dropped or added, or two neighbours swapped) from five characters on, so
**Accept all** may take records it left before: check the count its confirmation gives, and the
suggestions shown, before you accept. How scoring and hints work is in
[Reports, lineage and data quality](reports-and-data-quality.md#correlation-assistance).

## Access reviews (`/my/reviews`)

When an [access certification](governance.md#access-certifications) names you as a
reviewer, you are mailed, and **My access** says reviews are waiting. **My reviews** lists
your tasks, one per campaign, with how many items you have decided and when they are due.

1. Open a task. Each item is one person holding one entitlement in one system, with why
   they hold it: assigned, a rule (a computed group), or *nothing explains it* (found in
   place).
2. Group the items by person or by entitlement, and tick **Undecided only** to see what is
   left.
3. Select items (the box in the header selects every item shown) and choose **Keep**,
   **Revoke** or **Flag**. A revocation needs a reason, and so does keeping a privileged
   entitlement. Computed groups can be kept or flagged, not revoked.
4. To let someone else decide, select items and choose **Hand to someone else**. Nobody can
   be handed their own access.
5. When every item is decided, choose **Done**.

Until the campaign closes you can change a decision, unless the revocation was already
carried out. Everything works from the keyboard: Tab to the boxes, Space to tick them.

## Reports

### Downloading the reconciliation report

**Report CSV**, **Excel** and **PDF** on an engine's page (any role) download the engine's
reconciliation report: per publication, a summary (entities, entries exported and, since 0.5.0,
**adopted**, the last drift check, dead letters, held changes, leavers, what report mode would do,
groups, the last reconciliation, the last unmanaged-entries pass), then one row per entry with a
difference, the unmanaged entries among them. Nothing is read from the targets for it: it is what
the engine found at its last checks. Sensitive attributes are named, never shown.

- A download holds at most 200,000 rows by default; a cut one says so. Since 0.5.5 a **PDF** of
  more than 20,000 rows is refused: download the CSV or Excel file, or one publication at a time.
- The CSV opens in a spreadsheet as UTF-8. Cells that would start a formula get a leading `'`.
  Since 0.5.0 it has seven more columns at the end: six for the unmanaged-entries pass, and
  `adopted` last.
- The Excel file (`.xlsx`, new in 0.5.0) is a workbook of three sheets: **Report** (what the PDF's
  cover says), **Summary** (one row per publication) and **Entries** (one row per entry with a
  difference), each with a bold, frozen header row and a filter. Counts are numbers and times are
  dates (UTC); text is always text, never a formula.
- The PDF has a cover page and your organisation's top banner in every page's header and footer.
  Since 0.5.0 its text is set in an embedded DejaVu Sans Mono, which covers Latin, Greek and
  Cyrillic; other characters (Chinese, Japanese, Korean, Arabic, Hebrew, emoji) print as the
  replacement character `�`. The PDF is tagged (language, title, headings, tables), with no claim
  of PDF/UA conformance. Use the CSV or Excel file for names in other scripts.
- Every download is recorded in the audit trail.

### Scheduled reports (`/reports`)

**Reports** in the navigation (administrators, operators and auditors) lists the reports sent on a
schedule for the engines your roles reach: the engine and publication, the schedule and hour (UTC), the format, where it goes (e-mail
addresses, a share), its owner, the **Next run** and the **Last run** (**sent**, with the time and
row count, or **not sent** with the reason). Notices at the top say when e-mail or the share is not
set up.

Operators and administrators choose **New scheduled report**:

| Field | Meaning |
|---|---|
| **Engine** | The engine reported on |
| **Publication** | One publication, or empty for all of them |
| **Format** | CSV, Excel (.xlsx) or PDF |
| **Schedule** | **Daily**, **Weekly (Monday)** or **Monthly (the 1st)** |
| **Hour (UTC)** | 0 to 23 |
| **E-mail recipients** | Up to 20 addresses, separated by commas, in the domains your administrator allows (the hint names them) |
| **Write the file to the share** | And the **Directory inside the share**. The file is named `<engine>-<publication>-<date>.<csv\|xlsx\|pdf>` |
| **Enabled** | Untick to pause it (**paused**) |

On your own reports (administrators: on anyone's) the row has **Send now**, **Edit** and **Delete**
(after a confirmation). Each run checks again that the owner is still an administrator or operator
and that every recipient is allowed; a run that fails a check sends nothing. A scheduled report's file
holds at most 50,000 rows by default (a PDF, since 0.5.5, at most 20,000); the mail says when it
was cut. Since 0.5.5 a run that failed because the engine or the share did not answer is tried
again 15 minutes later, until the next scheduled run.

## Errors you may see

| Message | Meaning |
|---|---|
| "the &lt;role&gt; role cannot do this", "none of your roles (…) can do this" | Your roles do not allow the action |
| "none of your roles (…) allows this on …" | Your roles allow it on other engines or environments, not on this one |
| "this needs a role that covers everything; yours (…) are limited to an environment or to engines" | The action is for roles on everything (accounts, settings, webhooks, Git sync, the trail as a whole) |
| "acknowledge the notice on the sign-in page before signing in" | The consent notice must be acknowledged again (an acknowledgement lasts about a quarter of an hour and serves one sign-in) |
| "your role may not reveal sensitive attributes" | Your role is not in `CONSOLE_REVEAL_ROLES` |
| "the configuration does not compile" | Fix the errors in the **Check** panel first |
| "not published: N of M test cases fail" | Every test case, package test and golden test must pass before publishing |
| "not published: version N needs approval by someone other than its author" | Four eyes is on; ask another designer or an admin |
| "… needs the &lt;pack&gt; pack, which the license does not include" | The configuration uses a connector or feature the license leaves out; it cannot be saved or published until the license includes the pack (see [Licensing](licensing.md)) |
| "not published: publishing configuration changes is blocked by the license (…)" | The license's grace period is over. Sync keeps running, and a version published to the engine before can still be published again; ask an administrator to install a license |
| "promoting to environment X publishes to every engine in it, and your roles (…) do not allow publishing to …" | **Promote** needs the right to publish on every engine of the target environment. Since 0.5.1 engines your roles do not reach are counted ("1 other engine"), not named |
| "a version is approved by someone other than its author (who saved it, created the API token that saved it, or wrote its Git commit)" | You are accountable for this version (since 0.5.1); ask another designer or an admin |
| "a PDF holds at most 20000 rows, and this report has N: download it as CSV or Excel, or one publication at a time" | Since 0.5.5 a PDF report holds at most 20,000 rows |
| "The console did not answer within 10 minutes. What was asked may still be under way: look again before trying it once more." | Since 0.5.5 a request from a console page is given up after ten minutes. A long one (a promotion to many engines) may still finish: check its outcome before you repeat it |
| "golden tests: cannot be read from Git, so not run: …" (among the failures of "not published: N of M test cases fail") | The version's golden tests could not be read (its commit is gone from the console's clone, or Git did not answer): the publish is refused until they can be |
| "not published: another publish to X is still under way; try again once it has finished" | Another publish to the engine had not finished within two seconds (since 0.5.5; 0.5.4 waited up to 150). Try again once its outcome shows |
| "this version's author is not known to the console, so nobody can approve it as someone else: save it again in the console, and have another person approve that version" | Since 0.5.5: the version came from a Git commit whose author matches no account, or from an API token whose creator is gone, so four eyes cannot be checked |
| "refused: a change made with the console's session must come from the console's own pages" | Since 0.5.1 a change made with your session from another site (a link or form elsewhere) is refused; use the console's own pages |
| "a publish needs a change ticket reference matching …", "the ticket reference … does not match …" | Your administrator requires a change ticket in that form |
| "not published: the change hook refused version N: …" | The change-management system said no; the reason follows |
| The change hook's error, with status `503` | The change-management system did not answer, or (since 0.5.1) gave an answer that is neither an allow nor a deny, and the console refuses to publish without one; try again, or ask your administrator |
| "the configuration cannot be rendered for environment X: …" | The engine's environment lacks a value the version needs (`{{ env.… }}`); ask an administrator to add it on **Integrations** |
| "the engine could not start with vN and went back to …" | The engine refused the configuration; its log says why |
| "the engine accepted vN but did not come back within 60 seconds" | The version shows as **publishing** until the engine reports it |
| "the audit log cannot be written, so this was not done" | The console refuses actions it cannot record; tell your administrator |
| **unreachable** on an engine | The console cannot reach the engine's control API; see [Troubleshooting](troubleshooting.md) |
| A command refused with the active engine's name | The engine is the passive one of an HA pair: use the active engine's page |
