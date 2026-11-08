# Change safety and lifecycle

A publication can say **when** its changes are applied, **how many** may go at once, and **which
go first**. It can also run in a mode that writes nothing. Changes that wait are **held**. The
engine can also act at a set time: an attribute that applies from a start date, a leaver who is
disabled at once and deleted ninety days later, a group that expires. All of this is new in syncd
0.4.0.

Every setting on this page goes on a publication (or, for effective dates, in a rule).
[Configuration](configuration.md) lists each key in one line; this page explains how they work
together. The rule functions `effective()` and `effective_until()` are in
[Computed attributes and rules](rules.md).

| Setting | What it does | Held as |
|---|---|---|
| `change_window` | Changes are applied only at the times you allow | `window`, `overdue` |
| `modification_threshold`, `add_threshold` | A pass that changes or adds too many entries waits for an approval | `modifications`, `additions` |
| `deletion_threshold` | As before, for deletes. Its holds now show among the held changes | `deletions` |
| `canary` | A few entities go first; the rest follows after a soak | `canary`, `canary_failed` |
| `mode: report` | Nothing is written; every planned change is recorded | `report` |
| `deprovision: { disable_after, ... }` | A leaver goes through stages, each at a set time | (timers) |
| `groups[].expires`, `group_policy` | Groups have owners, expire and are reviewed | `expired` |

## Held changes

A planned change that is not applied at once is held. The engine keeps one held change per
entity and publication in its store, with the reason, the kinds of change (`add`, `modify`,
`move`, `delete`, `disable`, `strip_groups`, `password`), the DN, a summary of the operations
without their values, when it was first held and when it was last planned. Held changes survive
a restart.

**A held change is never replayed.** When its hold ends (the window opens, the canary has
soaked, someone approves), the entity is planned again from its current state, and whatever that
plan says is applied. A person who changed department twice while the window was closed gets
one change, to the second department. An entity that no longer needs a change loses its hold.

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
| `syncd held <publication> [--kind K] [--reason R] [--limit N]` | The held changes, one per line, in entity id order |
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
`publication <name>`), and API tokens need `runs:run`.

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
  a fresh one when it is planned again. See [Password synchronisation](password-sync.md).
- When the window opens, the held entities are planned again and applied as one pass, through
  the thresholds and the canary.
- A change held longer than `max_delay` is applied anyway (`on_overdue: apply`) or becomes
  `overdue` and waits for an approval (`on_overdue: hold`).

The console's publication form has the window under **Change safety**, with the other settings
on this page.

## Modification and add thresholds

```yaml
    deletion_threshold:     { percent: 5,  count: 50 }
    modification_threshold: { percent: 20, count: 500 }   # modifies, moves, disables, strips
    add_threshold:          { percent: 50, count: 1000 }
```

They have the same shape as the deletion threshold: `percent` of the publication's exported
entries, `count`, or both. More than either crosses the threshold. At least one must be set, and
`percent` is between 0 and 100.

- A **reconciliation** judges its whole pass at once. Over a threshold, that class of change is
  held (`modifications` or `additions`) until `syncd approve`, or until a later pass stays under
  the threshold, which applies them.
- A **stream of changes** is judged batch by batch, counting what is held already and what went
  through since the sources were last quiet. So a mass change that arrives in small pieces is
  caught too.
- Deletes still go through `deletion_threshold`. Its holds now also show among the held changes
  as `deletions` when a reconciliation finds them.
- Writes to computed group entries are not counted and not held.

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
and the canary runs at the opening.

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
window.

Writes to computed group entries are never held by a window or counted by a threshold; the
entries of the people in them are.

`syncd simulate`, the console's preview and golden-file tests ([Configuration as
code](config-as-code.md)) leave change safety out: they show what a configuration would do, not
when.

## Persisted timers

The engine keeps due times in its store and acts at each one, whether it ran throughout or was
restarted in between. A stage falls due at its time by the engine's clock, not after an uptime.
A timer found overdue after a stop fires at the next start, once the initial export is done.
`syncd run --once` fires the timers due before its final reconciliation.

Timers drive effective dates, leaver stages, change window openings and `max_delay`, canary
soaks and group expiry. A timer that fires and finds nothing to do (a start date moved later, a
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
      move_to: "OU=Leavers,DC=corp,DC=example,DC=com"
      delete_after: 90d                          # hard delete
```

| Key | What |
|---|---|
| `disable_after` | Disable the account the way the target does it: `userAccountControl` on Active Directory, the profile's lock on LDAP, `active: false` on SCIM, `accountEnabled: false` on Entra ID. Needs a target that can disable (an LDAP target needs a `profile`) |
| `strip_groups_after` | Take the entry out of the publication's computed groups, and out of the reference attributes of other publications that list it. The entry itself is not changed |
| `move_to` | Move the entry under this fixed container, with the earliest of the disable and the strip, or at once when neither is set |
| `delete_after` | Delete the entry. The deletion threshold still applies, and `remove_empty_containers` as for any delete |

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

| Where | What |
|---|---|
| `syncd status` | `leavers: N to disable, N to strip of groups, N to move, N to delete` under the publication. A leaver waiting for several stages counts in each |
| The console | A **Leavers** column on the engine page. The publication form offers **is deprovisioned in stages** with the four fields |
| Prometheus | `syncd_deprovision_pending{publication,stage}`, with `stage` one of `disable`, `strip_groups`, `move`, `delete` |
| Reports | The leaver counts are columns of the reconciliation report |

**Limitations.** An entry recreated under a new entity is not re-enabled. Memberships in groups
syncd does not manage (groups in the target that list the entry but that no publication writes)
are not stripped.

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

### Manual members and owners

The drift check now reads the group entries back too. A leaver being stripped of its groups is
never taken for a manual member. Kept members survive a full write of the group. A kept member
removed by hand is forgotten at the next check, and changing `keep` or `report` back to `revert`
removes the recorded members at the next check. Members missing from the target and a changed
owner follow the publication's `drift.default`.

An `owner_from` that depends on the members is evaluated again as members change. It cannot read
lookup tables or other entities. One that gives nothing, or fails, writes no owner and is logged.

### Expiry

At expiry the policy applies once:

- **`report`**: nothing changes in the target. The group shows as a held change with the reason
  `expired`, which needs no approval and which `syncd approve` does not clear. A review does.
- **`empty`**: every member is removed and the entry is kept, whatever `when_empty` says.
  Members joining meanwhile are not written. This suits object classes that allow an empty group,
  such as Active Directory's `group`.
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

| Where | What |
|---|---|
| `syncd status` | `groups: N (N with manual members, N expired, N reviews due)` and a line per group with its owner, expiry and review |
| The console | The engine page's **Groups** card, and the publication form's **Group policy** section and per-group **Lifecycle** fields |
| Prometheus | `syncd_groups`, `syncd_groups_manual_members`, `syncd_groups_expired`, `syncd_groups_review_due` (all by `publication`), and `syncd_held_changes{reason="expired"}` |
| Alerts and webhooks | `SyncdGroupsExpired`; the webhook event `group.expired` when a publication's expired count rises from 0 |

**Limitations.**

- `review_every` has no timer and sends no mail. A due review shows in the status, the metric
  and the console only.
- Reviews are per group: there is no "review every group of the publication".
- An `on_expiry: empty` group whose object class needs a member (`groupOfNames`) is refused by
  the directory and dead-letters. Use `delete`, or an object class that allows an empty group.

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

- **Held changes** are counted in memory per publication, so report mode over a very large view
  costs memory in proportion, as the dead-letter queue does.
- **A canary's drift check** follows the publication's drift policy, reverts included. There is
  no stricter "flag only" check for canaries.
- **Leavers:** an entry recreated under a new entity is not re-enabled, and memberships in groups
  syncd does not manage are not stripped.
- **Groups:** `review_every` has no timer and sends no mail; reviews are per group; an
  `on_expiry: empty` group whose object class needs a member dead-letters.
- **Rollback:** syncd 0.3.0 ignores the timers and held changes that 0.4.0 keeps, and refuses the
  0.4.0 settings (see the warning under [Persisted timers](#persisted-timers)).
