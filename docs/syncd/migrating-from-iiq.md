# Migrating from IdentityIQ

This page describes syncd 0.7.0 (tag `v0.7.0`, commit `6de2fa1`), the first release with the
import, with the rest of [Governance](governance.md). The review before the release changed
nothing in the import itself; it held the parallel run to a scoped governance
administrator's people (see [Delegated administration](governance.md#delegated-administration)).

!!! warning "Tested on hand-written exports only"
    The import has been tested on hand-written exports, written to IdentityIQ 8.x's
    documented shape; no real export has been imported. Plan for the first real run to need
    changes.

syncd's governance layer takes over what an IdentityIQ (IIQ) deployment decides: who holds
what, why, who approved it and when it was reviewed. The move is made with one command,
which converts what it can from an IIQ export and reports the rest, and a comparison that
shows whether syncd would provision what IIQ did before anything is switched over.

## The import

Export the objects from IIQ's console (`export -clean <file> <Class>`, or all classes
into one `<sailpoint>` file), write a mapping file, and run:

```sh
syncd-console governance import-iiq --file iiq-export.xml --map mapping.yaml --dry-run
```

```yaml
# mapping.yaml
applications:                 # IIQ application -> syncd governance system
  Active Directory: Directory
  Workday: HR
login_attribute: name         # the IIQ identity attribute that is the person's login
unmapped_applications: manual # unmapped applications become manual systems (default: skip)
```

| Option | |
|---|---|
| `--dry-run` | Everything in one transaction, rolled back: the report without a change |
| `--strict` | Exit non-zero when anything needs a person's work (for a pipeline) |
| `--archive-dir DIR` | Past certifications, access requests and audit events as CSV and NDJSON files, kept for retention (not loaded) |
| `--apply-links` | Where IIQ links an account to another person than the catalog does, link it as IIQ does |
| `--report-dir DIR` | Where `import-report.md` and `import-report.json` go (the current directory) |

The import reads the file as a stream (an export may be several gigabytes) and never
resolves its `DOCTYPE`: a declaration with entities is refused. Run it again as often as
needed: each record keeps the IIQ object's id, so a second run updates what the first made
and makes nothing twice.

### What it converts

| IIQ | syncd | Left for a person |
|---|---|---|
| Applications | The mapped governance systems (owner), or manual systems | Connector settings and aggregation rules: configure the engine's sources and publications |
| Managed attributes | Entitlements matched by system and value: description, owner, requestable | Values the catalog does not hold yet: run the system's inventory, then import again |
| IT roles | Roles with a **proposed** version of the values their profiles list | Profiles with compound filters |
| Business roles | Roles with their required roles included, and a membership rule from a selector of attribute values | Selectors written as rules, scripts or filters |
| Role assignments | Role assignments, held once the role's version is approved | People or roles not found |
| Links | Compared with the catalog's account links (`--apply-links` to follow IIQ) | Accounts not in the catalog |
| SOD policies | Separation-of-duties policies (warn at request) | Advanced and rule-based policies |
| Certification definitions | Campaign definitions: manager, application owner, entitlement owner | Other types |
| Workgroups | Workgroups and their members | |
| Workflows, rules, forms, e-mail templates, quick links, task definitions | Nothing | Listed by name, type and size: rewrite by hand (rules as CEL) |

Every imported role version waits for a person to approve it on the **Roles** page (its
proposer is recorded as `iiq-import`); a business role is approved after the IT roles it
includes. The import is recorded in the audit trail as `governance.iiq.imported`, with the
counts per class.

## The parallel run

**Parallel run** (`/governance/parallel-run`, `GET /api/v1/governance/reports/parallel-run`)
shows, per system:

- **Explained by nothing imported**: access in the target that no assignment or rule
  explains. This is what IIQ grants by means the import did not capture (a rule, a
  workflow, a manual change). Each one becomes an assignment (adopt it on the person's
  page), a rule, or a removal.
- **Imported, absent from the target**: imported assignments whose access the target does
  not hold. This is what syncd would add at cutover.
- **Totals**: holdings, unexplained, imported assignments, missing.

Each list shows up to 500 rows; the totals count them all. A governance administrator scoped
to some people sees only theirs.

## Cutover

1. **Report mode.** Configure syncd's publications for the systems IIQ provisions, in
   report mode: they compute everything and write nothing (see
   [Change safety](change-safety.md)). Run each system's inventory.
2. **Import.** Run the import with `--dry-run`, fix the mapping, then run it for real and
   approve the imported role versions.
3. **Compare.** Work the parallel run's two lists down to an agreed number: adopt or
   explain access, fix roles and rules, or accept the difference in writing.
4. **Freeze IIQ.** Stop provisioning from IIQ (disable its provisioning workflows and
   scheduled tasks); people keep requesting nothing there.
5. **Final import.** Take a final export and import it again (it updates; it does not
   duplicate), and check the parallel run once more.
6. **Go live.** Switch the publications from report mode to live, one system at a time,
   watching held changes and dead letters.
7. **Keep IIQ read-only** for an agreed period, for auditors and for comparison, then
   retire it. The archive files from `--archive-dir` keep its history.

## Tested, and not yet

Tested on hand-written fixtures covering each conversion, the dry run, a second import that
makes nothing new, both hostile `DOCTYPE`s (an external entity and nested expansion), and
the parallel run. Not run on a real IdentityIQ export, and not on an export of several
gigabytes.
