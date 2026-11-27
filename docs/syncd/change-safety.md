# Change safety and lifecycle

A publication can say **when** its changes are applied, **how many** may go at once, and **which
go first**. It can also run in a mode that writes nothing. Changes that wait are **held**. The
engine can also act at a set time: an attribute that applies from a start date, a leaver who is
disabled at once and deleted ninety days later, a group that expires. All of this is new in syncd
0.4.0. This page describes syncd 0.7.0 (tag `v0.7.0`, commit `6de2fa1`). 0.5.0 added group
reviews that reach a person, `placeholder_member`, leavers stripped of groups syncd does not
manage, unique `sAMAccountName`s, and held changes kept in the store; 0.5.1 checks deletion
thresholds as strictly as the others, follows `effective()` dates in group filters and join
rules, and has truncates in MySQL and Debezium sources go through the deletion threshold. Each
is marked where it is described. 0.5.2 and 0.5.4 change nothing here. 0.5.5 makes the guards
hold more: a leaver's disable or move aside counts under the deletion threshold, drift reverts
pass through the thresholds, the change window and the canary, a publication over its threshold
holds only its own deprovisions, an overdue change no longer releases the rest of the window,
groups follow their members' writes, and an entity added where a gone entity's entry is takes
that entry over. 0.6.0 adds the `dsml` target (see [Air-gapped synchronisation](air-gap.md)),
which takes the settings on this page except those that read the target back; 0.6.1 changes
nothing here. 0.7.0 adds database table, REST and SDK connector targets (see
[Connectors](connectors.md#sql-tables-as-targets)), which take the settings on this page
except moves (a staged leaver policy without `move_to`), `strip_unmanaged_groups` and computed
groups; and memberships granted by the console's governance layer, whose last member in a
`groupOfNames` or `groupOfUniqueNames` group gives way to `group_policy.placeholder_member`
(see [Group lifecycle](#group-lifecycle)).

Every setting on this page goes on a publication (or, for effective dates, in a rule).
[Configuration](configuration.md) lists each key in one line; this page explains how they work
together. The rule functions `effective()` and `effective_until()` are in
[Computed attributes and rules](rules.md).

| Setting | What it does | Held as |
|---|---|---|
| `change_window` | Changes are applied only at the times you allow | `window`, `overdue` |
| `modification_threshold`, `add_threshold` | A pass that changes or adds too many entries waits for an approval | `modifications`, `additions` |
| `deletion_threshold` | As before, for deletes and (since 0.5.5) a leaver's disable or move aside. Its holds now show among the held changes | `deletions` |
| `canary` | A few entities go first; the rest follows after a soak | `canary`, `canary_failed` |
| `mode: report` | Nothing is written; every planned change is recorded | `report` |
| `deprovision: { disable_after, ... }` | A leaver goes through stages, each at a set time | (timers) |
| `groups[].expires`, `group_policy` | Groups have owners, expire and are reviewed | `expired` |
| `sam_account_name: { on_conflict: unique }` | A second holder of a login gets a counter instead of a dead letter (new in 0.5.0) | |

## Held changes

A planned change that is not applied at once is held. The engine keeps one held change per
entity and publication in its store, with the reason, the kinds of change (`add`, `modify`,
`move`, `delete`, `disable`, `strip_groups`, `password`), the DN, a summary of the operations
without their values, when it was first held and when it was last planned. Held changes survive
a restart. From 0.5.0 they are kept in the store and only counted in memory, which saves memory
for a publication with many of them (40 MiB less at 475,000 held changes, in the release's
measurement) and makes passes over it slower (18 to 35% in a debug build). Since 0.5.1 those
counts cannot wrap round: in 0.5.0 a hold whose removal was counted twice (its write dropped
with a failed batch) could turn a count into a huge number that every threshold read as
crossed.

**A held change is never replayed.** When its hold ends (the window opens, the canary has
soaked, someone approves), the entity is planned again from its current state, and whatever that
plan says is applied. A person who changed department twice while the window was closed gets
one change, to the second department. An entity that no longer needs a change loses its hold.

**Changes during long operations.** From 0.5.0 source changes that arrive during a
reconciliation, a full sync or a reload are taken in between its pages and go through the same
guards, instead of waiting for it to end. A pass also no longer drops a hold that was written for
an entity after the pass had gone by it (0.4.1 could lose such a hold): the change stays in
`syncd held` until it is applied. Source changes still wait during the end-of-pass checks of
thresholds and canaries, the release of held changes, the reconciliation `syncd approve` runs, a
full read's deletes, the first export after an initial load, drift checks, dead-letter retries
and write-back. See
[Troubleshooting](troubleshooting.md#the-engine-at-work).

| Reason | Why it is held | How it ends |
|---|---|---|
| `window` | Outside the change window | By itself, when the window opens |
| `overdue` | Held longer than `max_delay`, with `on_overdue: hold` | An approval |
| `modifications` | The pass crossed `modification_threshold` | An approval, or a later pass under the threshold |
| `additions` | The pass crossed `add_threshold` | An approval, or a later pass under the threshold |
| `deletions` | The pass crossed `deletion_threshold` | An approval, or a later pass under the threshold |
| `canary` | Waiting for the canary entries to soak | By itself, at the end of a clean soak |
| `canary_failed` | A canary entry dead-lettered or drifted | An approval |
| `report` | The publication is in report mode | Switching to `mode: apply` |
| `expired` | A computed group expired with `on_expiry: report` | A review of the group, not an approval |

### Where held changes show

| Where | What |
|---|---|
| `syncd status` | A `held:` line under the publication: the count by kind and reason and the oldest, when the window opens next, the canary under way, and the approval to give. A report-mode publication prints `report mode: N adds, M modifies, K moves, L deletes would be sent` instead |
| `syncd held <publication> [--kind K] [--reason R] [--limit N]` | The held changes, one per line, in entity id order. `--kind` is one of `add`, `modify`, `move`, `delete`, `disable`, `strip_groups`, `password`; `--reason` one of the reasons above (`window`, `overdue`, `modifications`, `additions`, `deletions`, `canary`, `canary_failed`, `expired`, `report`). `--limit` is 100 by default. (The 0.4.0 help left out `password` and `expired`, which it already accepted) |
| The console's engine page | A **Held** column per publication, and a **Held changes** card with the list and an **Approve and apply** button when an approval is needed |
| Prometheus | `syncd_held_changes{publication,reason}` (every reason, so a hold that clears reads 0), `syncd_window_opens_timestamp_seconds{publication}`, `syncd_canary_failed{publication}`, `syncd_shadow_planned{publication,kind}` (report mode) |
| Alerts | `SyncdChangesNeedApproval` (warning: changes held as `modifications`, `additions`, `deletions`, `overdue` or `canary_failed`) and `SyncdCanaryFailed` (critical). See [Alert rules](operator-guide.md#alert-rules) |
| Webhooks | `hold.created` and `hold.released`. See [Webhooks](webhooks.md) |
| Reports | Held changes and would-dos are rows of the [reconciliation report](reports-and-data-quality.md) |

### Approving

```bash
syncd held people_to_ad --reason modifications     # look first
syncd approve "publication people_to_ad"           # then release
```

An approval releases every held change of the publication except report-mode would-dos and
expired groups. The entities are planned again from their current state and applied as one pass,
window, thresholds and canary notwithstanding. The publication is then reconciled, so deletes
held by its deletion threshold go through once too. The approval is used up by that release:
the thresholds stay in force for later changes.

In the console, an operator or administrator uses **Approve and apply** on the Held changes card.
It is the same approval (`POST /api/v1/engines/{id}/approvals` with the scope
`publication <name>`), and API tokens need `runs:run`. From 0.5.0 an operator's role may be
limited to an environment or to engines: it approves only there.

Since 0.5.5 an approval, like any other control command, waits at most ten seconds while the
engine is busy (releasing held changes, a full sync, a drift check), then is refused with
`the engine is busy (...); try again when it is done`; it was queued behind the work.

!!! note "Approvals under the license"
    A license that blocks publishing (see [Licensing](licensing.md#what-publishing-is-blocked-blocks))
    never blocks approving or rejecting held changes, dead-letter retries and discards, or joins:
    held changes can always be released. Nor does it block publishing again a version that ran on the
    engine before: a rollback is always allowed, and is audited as one.

## Change windows

```yaml
publications:
  - name: people_to_ad
    change_window:
      timezone: Europe/London          # an IANA name; UTC by default
      allow:                           # when changes may be applied
        - "Mon-Fri 18:00-06:00"        # overnight, past midnight
        - "Sat"                        # the whole day
        - "Sun 00:00-12:00"
      immediate: [disable, password]   # applied at once anyway (the default)
      max_delay: 7d                    # held longer than this: applied anyway
      on_overdue: apply                # or hold, for an approval
```

| Key | Default | What |
|---|---|---|
| `timezone` | `UTC` | The zone of the times in `allow`, as an IANA name (`Europe/London`, `America/New_York`) |
| `allow` | none (always open) | When changes may be applied. Each entry is days, a time range, or both |
| `immediate` | `[disable, password]` | Kinds of change applied at once even outside the window: `add`, `modify`, `move`, `delete`, `disable`, `strip_groups`, `password` |
| `max_delay` | none | A change held longer than this is overdue. A span: `7d`, `12h`, `1d12h` |
| `on_overdue` | `apply` | `apply` applies an overdue change, window or not. `hold` keeps it for an approval. `hold` needs `max_delay` |

Writing `allow` entries:

| Entry | Means |
|---|---|
| `"Sat"`, `"Saturday"` | The whole day. Days are English names or their first three letters, in any case |
| `"Mon-Fri 18:00-06:00"` | Each of those days from 18:00, on past midnight to 06:00 the next morning (so Friday night runs into Saturday) |
| `"Fri-Mon 20:00-23:00"` | A range of days may wrap the week |
| `"Mon,Wed,Fri 09:00-17:00"` | A list of days, with no spaces |
| `"22:00-04:00"` | A time range alone means every day |
| `"Sun 00:00-24:00"` | `24:00` ends a day. Times are `HH:MM` or `HH:MM:SS` |

The window follows the zone across clock changes. A start the clocks skip opens when they have
moved on; a start that happens twice opens the first time. Validation names the entry at fault.

What happens outside the window:

- Every planned change of the publication is held as `window`, unless all its kinds are in
  `immediate`. A change that touches nothing in the target is never held.
- **`disable` is immediate by default, so a leaver is locked on time.** Take it off the list
  only if a late lock is acceptable.
- **Passwords are never held**, whatever `immediate` says. Captured passwords are delivered as
  they come. An initial password rides with its add: a held add holds its password too, and gets
  a fresh one when it is planned again. From 0.5.0 an initial password an LDAP or Active Directory
  target refuses is tried again, three attempts in all, and then the entry is held in the
  dead-letter queue (see [Troubleshooting](troubleshooting.md#passwords)). See
  [Password synchronisation](password-sync.md).
- When the window opens, the held entities are planned again and applied as one pass, through
  the thresholds and the canary.
- A change held longer than `max_delay` is applied anyway (`on_overdue: apply`) or becomes
  `overdue` and waits for an approval (`on_overdue: hold`). Since 0.5.5 only the overdue changes
  go through the closed window, each judged against `max_delay` on its own; 0.5.4's overdue
  release let through every change the window held.

The console's publication form has the window under **Change safety**, with the other settings
on this page.

## Modification and add thresholds

```yaml
    deletion_threshold:     { percent: 5,  count: 50 }
    modification_threshold: { percent: 20, count: 500 }   # modifies, moves, strips
    add_threshold:          { percent: 50, count: 1000 }
```

They have the same shape as the deletion threshold: `percent` of the publication's exported
entries, `count`, or both. More than either crosses the threshold. At least one must be set, and
`percent` is between 0 and 100. Since 0.5.1 validation holds `deletion_threshold` to the same
rule, on a publication and on a source connector alike: an empty one (`{}`), which nothing could
ever cross, or a `percent` outside 0 to 100 is refused. 0.5.0 accepted both, so check a
configuration with `syncd validate` and the new binary before the upgrade.

- A **reconciliation** judges its whole pass at once. Over a threshold, that class of change is
  held (`modifications` or `additions`) until `syncd approve`, or until a later pass stays under
  the threshold, which applies them.
- A **stream of changes** is judged batch by batch, counting what is held already and what went
  through since the sources were last quiet. So a mass change that arrives in small pieces is
  caught too.
- Deletes still go through `deletion_threshold`. Its holds now also show among the held changes
  as `deletions` when a reconciliation finds them.
- **Leavers count as deletes** (since 0.5.5). A leaver's entry disabled or moved aside
  (`disable`, `move_to`, `disable_and_move_to`, and the stages of a staged policy that disable or
  move) counts under `deletion_threshold`, not `modification_threshold`: a mass disable from a
  bad read is the same mistake as a mass delete. 0.5.4 counted them as modifications.
- **Each publication on its own** (since 0.5.5). A batch that crosses one publication's deletion
  threshold holds that publication's deprovisions only; the batch's changes to other
  publications go through. 0.5.4 held the whole batch.
- **A pass with no deletes clears the hold** (since 0.5.5). A reconciliation of a held publication
  that finds nothing to delete clears its deletion hold, and drops an approval given for it.
- Writes to computed group entries are not counted and not held.

**A mass delete put right at the source** (since 0.5.5). When a source came back empty and its
deletes were held, fix the source: the next full read, or the next reconciliation of the held
publication, clears the hold. Records restored at the source come back as new entities, and each
takes over the entry its gone entity left at its DN (see
[Staged leaver policy](#staged-leaver-policy)): the entry is updated, not added again, and the
gone entity's record is never deleted over it. In 0.5.4, approving the hold after such a restore
could delete the live entry the new entity had taken.

**A source taken out of a view** (since 0.5.1). At the full sync that follows the publish,
the view's entities lose their records of that source and are computed again without them; an
entity left with no projecting source is deleted. Both go through the deletion thresholds like
any other delete. 0.5.0 left those records on the entities.

**A truncated PostgreSQL table** (new in 0.5.0). A `TRUNCATE` of a table a PostgreSQL stream
follows is read as a full read that finds every row gone (rows the same transaction put back
excepted), so its entities are deleted in the targets, subject to the deletion thresholds; 0.4.1
ignored a `TRUNCATE`. Give the source connector a `deletion_threshold` to have an emptied table
held for approval as `source <connector>/<object>`. The stream has no next full read to clear the
hold, so `syncd approve "source <connector>/<object>"` reads the table in full at once and applies
what it finds gone; after repairing the table, the same command finds nothing gone and clears the
hold. The PostgreSQL publication must publish truncates (`truncate` in its `publish` list,
PostgreSQL's default): the engine warns at start when one made by hand leaves it out.

**Truncates in other sources** (since 0.5.1):

- **MySQL and MariaDB.** A `TRUNCATE`, `DROP TABLE` or `RENAME TABLE` (or `ALTER TABLE ...
  RENAME`) of a table a stream follows makes the stream read the object in full, so rows no
  longer there are deleted, subject to the deletion thresholds. A dropped table fails the read,
  and the stream with it, until the table is back. 0.5.0 did not notice these statements.
- **Debezium.** A truncate event deletes every row staged from the table, as a full read that
  finds nothing would, under the source connector's `deletion_threshold`. 0.5.0 logged it and
  left the rows for a full read, which a Debezium object never makes. **Known limitation:** a
  Debezium truncate held by the threshold cannot be released with `syncd approve`, because the
  object never reads in full; its deletes wait for the next truncate.

## Canary publishing

```yaml
    canary:
      count: 25                        # or percent: 1 (how many entities go first)
      soak: 15m                        # how long they are watched
      on: { percent: 5, count: 100 }   # only for a pass that changes more than this
```

When a pass changes more entities than `on` (every pass without `on`), the thresholds are checked
first. Then the first `count` (or `percent` of the exported entries; at least one) of the
remaining changes, in entity id order, are applied, and the rest are held as `canary`. Changes
arriving during the soak wait with the rest.

At the end of `soak` the engine checks the entries that went first: none may be in the
dead-letter queue and, when the publication has `drift`, none may have drifted when read back.

- **Clean:** the rest is planned again and applied.
- **Not clean:** the rest becomes `canary_failed` with a summary of what failed.
  `syncd_canary_failed` reads 1 and `SyncdCanaryFailed` fires. Check `syncd dlq` and the entries
  in the target, then fix the source or approve.

A canary only runs while the change window is open. Outside it, the window holds the whole pass
and the canary runs at the opening. Since 0.5.5 a canary whose timer a stopped run had taken is
armed again at the next start; it held every change of its publication until an approval.

## Report mode

```yaml
    mode: report        # apply by default
```

The publication is planned on every pass exactly as in apply mode, but nothing is written. Each
plan is recorded as a would-do (reason `report`), replacing the entity's earlier one, and nothing
is recorded as exported.

- The engine still reads the target, for adoption and drift checks, but a guard refuses every
  write for the publication, and drift checks write no reverts.
- Captured passwords are not delivered to it (they are dropped and counted). Initial passwords
  are not generated.
- `syncd status` prints the would-do counts, `syncd held` lists them, the console shows a
  **Report** badge and the list, and `syncd_shadow_planned{publication,kind}` counts them.
- **Switching to `mode: apply`** makes the next reconciliation apply everything for real.
  Switching back to `report` stops writes at once and leaves the existing entries alone.
- An approval does not release would-dos.

Use it to try a new publication against a production directory, or to watch what a changed rule
would do for a week before it writes.

## How the guards combine

For each batch or pass the engine applies report mode first, then the thresholds, then the
canary, and the change window last: what the thresholds let through may still wait for the
window. Since 0.5.5 the reverts a drift check writes go through the same guards; 0.5.4 wrote them
at once.

Writes to computed group entries are never held by a window or counted by a threshold; the
entries of the people in them are. Since 0.5.5 a member joins or leaves a group when its own
change is written (at once when it has nothing of its own to write): a held delete, a change
waiting for a window or a canary, a failed export or a quarantined entity no longer moves it in
or out of groups meanwhile.

`syncd simulate`, the console's preview and golden-file tests ([Configuration as
code](config-as-code.md)) leave change safety out: they show what a configuration would do, not
when.

## Persisted timers

The engine keeps due times in its store and acts at each one, whether it ran throughout or was
restarted in between. A stage falls due at its time by the engine's clock, not after an uptime.
A timer found overdue after a stop fires at the next start, once the initial export is done.
`syncd run --once` fires the timers due before its final reconciliation.

Timers drive effective dates, leaver stages, change window openings and `max_delay`, canary
soaks and group expiry, and from 0.5.0 group reviews and the retries of a refused initial
password. A timer that fires and finds nothing to do (a start date moved later, a
rehire) does nothing, and the next one is armed from what the rules say then.

| Where | What |
|---|---|
| `syncd status` | `timers: N pending, next due in ...` |
| The console's engine page | The same line |
| Prometheus | `syncd_timers_pending`, `syncd_timers_next_due_timestamp_seconds` |

!!! warning "Rolling back to 0.3.0"
    syncd 0.3.0 opens a 0.4.0 store but ignores its timers and knows nothing of held changes:
    staged leaver steps, effective dates, change windows, canaries and group expiry stop. It also
    refuses settings it does not know, so publish a configuration without the 0.4.0 settings
    before rolling back with the store kept. See [Installation](install.md).

Timers and held changes survive the upgrades. syncd 0.4.1 kept the 0.4.0 store format; 0.5.0
migrates the store to format 2 at its first start (each view keeps its own links from records to
entities), and a 0.4.1 engine refuses the migrated store, so a rollback from 0.5.0 to 0.4.1
restores the backup taken before the upgrade (`syncdctl rollback`): timers and held changes go
back to what they were then. 0.5.1 keeps format 2, and a 0.5.0 engine opens a 0.5.1 store. 0.5.2
to 0.7.0 keep it too. 0.7.0 keeps the console's grants in column families of their own, which
0.6.1 opens and leaves alone: after a rollback to 0.6.1 with the store kept, granted access is
no longer enforced, and holds again on 0.7.0. A `dsml` target (0.6.0) keeps the exports waiting for its next file beside
the data, which 0.5.5 ignores: before rolling such an engine back to 0.5.5, run `syncd reconcile`,
which writes them out. See [Installation](install.md).

## Effective dates

A rule can give a value that applies from, or until, an instant:

```yaml
views:
  - name: person
    computed:
      - name: status
        output: 'effective(timestamp(e.startDate) - duration("3d"), "active", "pending")'
publications:
  - name: people_to_ad
    filter: 'effective(timestamp(e.startDate) - duration("3d"), true, false)'
    attributes:
      mail: 'effective_until(e.contractEnd, e.mail, null)'
```

With this, the account appears in the target three days before the start date, at that instant,
whether or not anything else about the person changed. The engine notes the next instant each
entity's rules change their result and arms a timer for it. A full recompute at that instant
gives the same values, so the result depends on the engine's time, not on when it ran. All rules
of one pass share one `now()`.

Since 0.5.1 a computed group's `filter` and a view's join rules follow `effective()` dates on
time too: an entity joins or leaves the group at the instant its filter's answer changes, and a
record whose join rule gives other keys from an instant is correlated again then (an unjoined
record tries to join; a joined one is checked against its entity). 0.5.0 evaluated them again
only when something else about the entity or the record changed.

The functions, their arguments and examples are in [Computed attributes and rules](rules.md).

## Staged leaver policy

`deprovision` written as a mapping of any of these keys is a policy in stages. Each stage is a
time after the entity left the publication's scope or was deleted.

```yaml
publications:
  - name: people_to_ad
    deprovision:
      disable_after: 0d                          # at once
      strip_groups_after: 7d                     # out of every group syncd manages
      strip_unmanaged_groups: true               # and the others (0.5.0; LDAP and AD)
      keep_groups: ["OU=Distribution Lists,DC=corp,DC=example,DC=com"]
      move_to: "OU=Leavers,DC=corp,DC=example,DC=com"
      delete_after: 90d                          # hard delete
```

| Key | What |
|---|---|
| `disable_after` | Disable the account the way the target does it: `userAccountControl` on Active Directory, the profile's lock on LDAP, `active: false` on SCIM, `accountEnabled: false` on Entra ID. Needs a target that can disable (an LDAP target needs a `profile`). Since 0.5.5 counted under the deletion threshold |
| `strip_groups_after` | Take the entry out of the publication's computed groups, and out of the reference attributes of other publications that list it. The entry itself is not changed |
| `move_to` | Move the entry under this fixed container, with the earliest of the disable and the strip, or at once when neither is set. Since 0.5.5 counted under the deletion threshold |
| `delete_after` | Delete the entry. The deletion threshold still applies, and `remove_empty_containers` as for any delete |
| `strip_unmanaged_groups` | New in 0.5.0. With the strip, also take the entry out of the groups in the target that syncd does not manage (made by hand or by another tool): every group of the entry's naming context that lists it as `member` or `uniqueMember`, except `keep_groups` and the computed groups of the publications to the same target. Needs `strip_groups_after`. LDAP and Active Directory targets. Off by default |
| `keep_groups` | New in 0.5.0. Groups `strip_unmanaged_groups` never touches: a group's DN, or a container whose groups are all kept |

- Spans are `0d`, `7d`, `90d`, `12h`, `30m`, `45s` or combinations such as `1d12h` (whole
  units, largest first). At least one stage is needed, and `disable_after` and
  `strip_groups_after` must not come after `delete_after`.
- The older forms (`delete`, `disown`, `disable`, `{ move_to }`, `{ disable_and_move_to }`) are
  unchanged.
- **The clocks are in the store, never in the target.** The engine records when the entity
  left, with its exported entry, and each stage falls due at that time plus its span, whether
  the engine ran throughout or was restarted. A stage found overdue after a stop is applied at
  the next plan. Each stage is applied once.
- A stage whose write fails goes to the dead-letter queue and is retried like any other export.
- With a change window, `disable` is immediate by default, but a strip, a move and a delete wait
  for the window unless listed in `immediate`.
- **A rehire** (the entity comes back into scope) moves the entry back, updates and enables it,
  puts it back in its groups and clears the clocks. Leaving again starts new clocks.
- **An entry recreated under a new entity** (new in 0.5.0): an entity added at the DN of a leaver's
  disabled entry takes the entry over and enables it, and the log says `a leaver's disabled entry
  is taken over by the entity added at its DN, and enabled`. 0.4.1 converged onto the entry and
  left it disabled. Since 0.5.5 this goes for the entry of any gone entity, not only a leaver's
  (a record re-keyed, or restored at the source after a held mass delete): the new entity's plan
  is made against the entry, and the gone entity's record goes without deleting it. A leaver
  moved aside (`move_to`) is not taken over.
- **Groups syncd does not manage** keep the leaver unless `strip_unmanaged_groups` is set. A
  group that cannot lose its last member (a `groupOfNames` must have one) is left as it is, with a
  warning.

| Where | What |
|---|---|
| `syncd status` | `leavers: N to disable, N to strip of groups, N to move, N to delete` under the publication. A leaver waiting for several stages counts in each |
| The console | A **Leavers** column on the engine page. The publication form offers **is deprovisioned in stages** with the four fields |
| Prometheus | `syncd_deprovision_pending{publication,stage}`, with `stage` one of `disable`, `strip_groups`, `move`, `delete` |
| Reports | The leaver counts are columns of the reconciliation report |

**Limitations.** Stripping groups syncd does not manage was tested on OpenLDAP only. A leaver's
entry that was moved aside is not taken over by a new entity at its old DN.

## Unique `sAMAccountName`

New in 0.5.0. Active Directory refuses a second entry with a `sAMAccountName` already taken, and
before 0.5.0 the second person's export went to the dead-letter queue. With `on_conflict: unique`
the second holder gets a counter from 2, in place of the name's last characters when both do not
fit in `max_length`:

```yaml
publications:
  - name: people_to_ad
    sam_account_name: { from: e.uid, max_length: 20, on_conflict: unique }
```

- The name is kept in the engine's store as an attribute of the entity and claimed among the
  publications of the same target that use the option. A name stays while `from` gives the same
  value, and stays taken while its entry is in the directory, after the entity is gone too (a
  disabled leaver keeps their name).
- Names already written are claimed by their entries first, so turning the option on renames
  nobody.
- `max_length` must be at least 2. Without the option (`on_conflict: fail`, the default) a
  duplicate still dead-letters.
- **Limitation:** uniqueness is among the names syncd claims; the directory is not searched for
  them, so a name taken by an account made outside syncd is not known to it, and the directory
  refuses the write as before.

## Group lifecycle

Computed groups have publication-wide defaults under `group_policy`, and every setting can be
overridden on a group.

```yaml
publications:
  - name: people_to_ad
    deprovision: { disable_after: 0d, delete_after: 90d }
    group_policy:
      manual_members: revert        # revert | keep | report
      owner_attribute: owner
      expires_after: 180d           # from the creation or the last review
      on_expiry: report             # report | empty | delete
      review_every: 90d
      reviewers: [it-ops@example.gov]   # told when a review falls due (0.5.0)
    groups:
      - name: engineering
        filter: e.department == "Engineering"
        dn: cn=engineering,ou=Groups,dc=example,dc=gov
        manual_members: keep
        owner: cn=it-ops,ou=Groups,dc=example,dc=gov
      - name: project-x
        filter: e.project == "X"
        dn: cn=project-x,ou=Groups,dc=example,dc=gov
        owner_from: 'size(group.members) > 50 ? "cn=it-ops,ou=Groups,dc=example,dc=gov" : "cn=team-leads,ou=Groups,dc=example,dc=gov"'
        expires: 2027-01-01
        on_expiry: empty
```

| Key | Default | What |
|---|---|---|
| `manual_members` | `revert` | A member in the target that the filter does not produce: `revert` removes it, `keep` leaves it and keeps it through full writes, `report` leaves it and counts it as drift |
| `owner` | none | The owner, a fixed DN, written with the members |
| `owner_from` | none | The owner as an expression over `group` (`group.name`, `group.dn`, `group.members`), instead of `owner` |
| `owner_attribute` | `owner` | Where the owner is written. Not the member attribute |
| `expires` | none | A date (`2027-01-01`, the start of that day in UTC) or an RFC 3339 timestamp |
| `expires_after` | none | A span from the group's creation by the engine, or from its last review. Not with `expires` |
| `on_expiry` | `report` | `report`, `empty` or `delete` |
| `review_every` | none | A review is due this long after the creation or the last review |
| `reviewers` | none | New in 0.5.0. Mail addresses the console writes to when the group's review falls due. Needs `review_every` |
| `placeholder_member` | none | New in 0.5.0. A fixed DN written to the group while it has no other member and its entry is kept (`on_expiry: empty`, or `when_empty: keep`), for object classes that must have a member (`groupOfNames`, `groupOfUniqueNames`). Taken out again with the first real member. LDAP and Active Directory targets. From 0.7.0 `group_policy`'s also serves groups the console grants members of (below) |

### Manual members and owners

The drift check now reads the group entries back too. A leaver being stripped of its groups is
never taken for a manual member. Kept members survive a full write of the group. A kept member
removed by hand is forgotten at the next check, and changing `keep` or `report` back to `revert`
removes the recorded members at the next check. Members missing from the target and a changed
owner follow the publication's `drift.default`. Since 0.5.5 the check compares member DNs
unescaped (`\,` and `\2C` alike, a multi-valued RDN's values in any order), so a member the
directory spells in hex is no longer taken for a manual member and removed; manual members
already recorded are re-keyed at the first start. It also skips a group with changes not yet
written.

An `owner_from` that depends on the members is evaluated again as members change. It cannot read
lookup tables or other entities. One that gives nothing, or fails, writes no owner and is logged.

### Expiry

At expiry the policy applies once:

- **`report`**: nothing changes in the target. The group shows as a held change with the reason
  `expired`, which needs no approval and which `syncd approve` does not clear. A review does.
- **`empty`**: every member is removed and the entry is kept, whatever `when_empty` says.
  Members joining meanwhile are not written. This suits object classes that allow an empty group,
  such as Active Directory's `group`. A `groupOfNames` or `groupOfUniqueNames` must have a member:
  with `placeholder_member` it holds that DN while emptied; without one the directory refuses the
  write and the group dead-letters. From 0.5.0 that combination (or `when_empty: keep` on such a
  group without a placeholder) is warned about at validation and at engine start; it will be an
  error in a later release.
- **`delete`**: the entry is deleted, subject to the deletion threshold, and not added again
  while the group stays expired. Validation refuses it unless the publication's `deprovision`
  deletes (`delete`, or a staged policy with `delete_after`).

### Review

```bash
syncd group review people_to_ad project-x
```

A review restarts the expiry and review clocks, clears the expired state and brings an expired
group back (members restored, or the entry added again). In the console, operators use the
**Review** button on the engine page's Groups card. It is recorded in the group's history and
audited as `group.reviewed`; API tokens need `runs:run`.

**A due review reaches a person** (new in 0.5.0). When `review_every` falls due, a timer fires
and:

- the engine logs `computed group review is due: review it (syncd group review <publication>
  <group>)`, and again at a start while it stays due;
- the console raises the webhook event `group.review-due` (whether or not anyone is named), and
  mails the group's `reviewers` (or the publication's `group_policy.reviewers`) once each time the
  review falls due. The mail needs the console's mail settings, and the addresses must be inside
  `CONSOLE_REPORTS_RECIPIENT_DOMAINS`: one outside is left out and named in the console's log;
- `SyncdGroupReviewsDue` fires once a review has been due for a day.

Nothing changes in the target when a review falls due.

| Where | What |
|---|---|
| `syncd status` | `groups: N (N with manual members, N expired, N reviews due)` and a line per group with its owner, expiry and review |
| The console | The engine page's **Groups** card, and the publication form's **Group policy** section and per-group **Lifecycle** fields |
| Prometheus | `syncd_groups`, `syncd_groups_manual_members`, `syncd_groups_expired`, `syncd_groups_review_due` (all by `publication`), and `syncd_held_changes{reason="expired"}` |
| Alerts and webhooks | `SyncdGroupsExpired`, `SyncdGroupReviewsDue` (0.5.0); the webhook events `group.expired` when a publication's expired count rises from 0, and `group.review-due` (0.5.0) |

**Limitations.**

- Reviews are per group: there is no "review every group of the publication".
- An `on_expiry: empty` group whose object class needs a member (`groupOfNames`) and that has no
  `placeholder_member` is refused by the directory and dead-letters. Set `placeholder_member`, use
  `delete`, or an object class that allows an empty group.
- `placeholder_member` has no console form field: write it in the configuration.

### Groups the console grants members of

From 0.7.0 the console's [governance](governance.md) layer can grant memberships of groups a
publication does not compute (`grants.memberships`; see
[Grants](configuration.md#grants-grants)). Such a group is not computed, so the lifecycle
above does not apply to it, but the same rule of LDAP holds: a `groupOfNames` or
`groupOfUniqueNames` group must keep a member. When the grant of its last member ends, the
engine writes the publication's `group_policy.placeholder_member` in that member's place, in
one modify, and takes the placeholder out again, in the same modify, when a member is granted.
Without a placeholder the directory refuses the removal and the grant fails, its error saying
how to set one: set `group_policy.placeholder_member` on a publication whose granted groups
can empty. A `memberUid` group takes no placeholder. On such a publication the placeholder is
checked as a fixed DN even when the publication computes no groups.

## Worked examples

### A production Active Directory publication

Changes go overnight and at weekends. A leaver is locked at once, whatever the time. A rule
mistake that touches a fifth of the directory waits for a person, and any large change goes to
25 people first.

```yaml
publications:
  - name: people_to_ad
    # ... view, target, dn, attributes ...
    deletion_threshold:     { percent: 2,  count: 50 }
    modification_threshold: { percent: 20, count: 2000 }
    add_threshold:          { count: 500 }
    canary: { count: 25, soak: 30m, on: { count: 200 } }
    change_window:
      timezone: America/New_York
      allow: ["Mon-Fri 19:00-06:00", "Sat", "Sun"]
      max_delay: 3d
      on_overdue: hold
    deprovision:
      disable_after: 0d
      strip_groups_after: 14d
      move_to: "OU=Leavers,DC=corp,DC=example,DC=com"
      delete_after: 90d
```

At 10:00 on a Tuesday, HR moves a department of 40 people. The modifies are held as `window`; the
leaver disabled in the same batch is not. At 19:00 the window opens and the 40 entities are planned
again from their current state. The pass is under the thresholds and under the canary's `on`, so
it is applied at once. Had a broken join touched 3,000 people, the pass would have been held as
`modifications`, `SyncdChangesNeedApproval` would fire, and nothing would be written until
`syncd approve`.

### Trying a new publication in report mode

```yaml
  - name: people_to_scim
    mode: report
```

Publish it, let it run for a few days, then check what it would do:

```bash
syncd status                                   # report mode: 1200 adds, 0 modifies, ...
syncd held people_to_scim --kind delete        # would it delete anything?
syncd report --publication people_to_scim --format csv --out would-do.csv
```

When the would-dos look right, publish the configuration with `mode: apply`. The next
reconciliation applies them all, through any thresholds and canary the publication has.

### A joiner three days early, a contractor until their end date

```yaml
  - name: people_to_ad
    filter: 'effective(timestamp(e.startDate) - duration("3d"), true, false)'
    attributes:
      description: 'effective_until(e.contractEnd, "Contractor", "Contract ended")'
```

Each person appears in Active Directory three days before their start date, at that instant, and
their description changes at their contract end, without a change in the source and across
restarts. A person who leaves scope then goes through the staged leaver policy.

### A time-boxed project group

```yaml
    groups:
      - name: project-apollo
        filter: e.project == "Apollo"
        dn: cn=project-apollo,ou=Groups,dc=example,dc=gov
        object_classes: [group]
        owner: cn=apollo-leads,ou=Groups,dc=example,dc=gov
        expires_after: 90d
        on_expiry: empty
```

Ninety days after syncd created it, the group is emptied and `SyncdGroupsExpired` fires. If the
project carries on, `syncd group review people_to_ad project-apollo` puts the members back and
starts another ninety days.

## Known limitations

- **Held changes** are kept in the store from 0.5.0 but still counted in memory per
  publication, and passes over a publication with very many of them are slower.
- **Long operations:** source changes still wait during the end-of-pass checks of thresholds and
  canaries, the release of held changes, `syncd approve`'s reconciliation, a full read's deletes,
  the first export after an initial load, drift checks, dead-letter retries and write-back.
- **A canary's drift check** follows the publication's drift policy, reverts included. There is
  no stricter "flag only" check for canaries.
- **Leavers:** a leaver moved aside is not taken over by a new entity at its old DN;
  `strip_unmanaged_groups` was tested on OpenLDAP only.
- **Groups:** reviews are per group; an `on_expiry: empty` group whose object class needs a member
  still dead-letters without `placeholder_member`; `placeholder_member` has no console form field.
- **`sAMAccountName`:** `on_conflict: unique` is uniqueness among the names syncd claims, not
  checked in the directory.
- **Initial passwords:** the number of attempts (three) and the delay between them are fixed.
- **Truncates in Debezium sources:** a truncate held by the deletion threshold cannot be released
  by approving it; its deletes wait for the next truncate.
- **Rollback:** syncd 0.3.0 ignores the timers and held changes that 0.4.0 keeps, and refuses the
  0.4.0 settings (see the warning under [Persisted timers](#persisted-timers)); 0.4.1 refuses the
  store 0.5.0 migrates, so a rollback from 0.5.0 restores the backup taken before the upgrade.
