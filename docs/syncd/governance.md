# Governance

!!! warning "In development, not in a release yet"
    This page describes the governance layer as it is being built on the
    `ldap-sync-merge-view` branch `claude/modest-mayer-onyb29`, after release 0.6.1. It is
    not in a release, and the Governance pack is not issued in licenses yet. What is built
    is listed below; policies and roles follow.

Governance answers who should have what, and why. The console decides; the engine
carries the decision out and keeps it, so access is enforced, and ends on time, while
the console is down.

- **People** come from one view of one engine. The console keeps a copy of a few
  attributes of each person (the *projection*), refreshed every 15 minutes by default.
- A **system** is a publication of an engine: the target whose access is governed.
- An **entitlement** is something a person can hold in a system: a group membership,
  an attribute value, or a *scope* (a key the publication's filter can test).
- An **assignment** is the decision that a person holds an entitlement, from a date to a
  date, for a reason. The engine fulfils it through a **grant**.

## What is built

| Part | Where |
|---|---|
| Grants the engine enforces: `grants` on a publication, `granted()` and `grants()` in its filter and attributes, `ApplyGrants` and `ListGrantStates`, `syncd grants` | The engine |
| Roles `governance_admin` and `participant`; permissions `govern`, `view_governance`, `participate` | The console |
| The identity source and its projection | Console: Governance page |
| Systems, entitlements, assignments | Console: Governance and Assignments pages; API under `/api/v1/governance` |
| A person's own page | Console: My access (`/my`); API under `/api/v1/my` |
| The entitlement catalog: each system's accounts, groups and holdings, read daily from its target (`ListInventory`, `syncd inventory`) | Console: Catalog page, and "What they hold" on Assignments |
| Access certifications: campaigns, reviewer queues, revocations the engine carries out, sign-off and the evidence report | Console: Access reviews (`/governance/campaigns`) and My reviews (`/my/reviews`) |
| Access requests: approval workflows, requests for oneself or a direct report, approvals, end dates, overrides | Console: Request access, My requests, My approvals, My team; Workflows and Requests for administrators |
| The Directory Services Portal: its group requests decided here (governed mode), and its owners' changes reported here | `governance:delegate` tokens; `POST /api/v1/governance/ingest/dsp` |
| The Governance pack | Licensing |

## Setting a publication up for grants

A publication takes grants only with a `grants` section. Memberships need the subtrees
whose groups may be granted; values and scopes need nothing more.

```yaml
publications:
  - name: people_to_ad
    view: people
    target: corp_ad
    # Active people, and anyone the governance layer grants the account.
    filter: e.status == "active" || granted("account")
    attributes:
      sAMAccountName: e.uid
      eduPersonEntitlement: grants("eduPersonEntitlement")
    grants:
      memberships:
        member_attribute: member          # uniqueMember, memberUid
        bases: ["OU=Groups,DC=corp,DC=example,DC=com"]
        max_per_entity: 500               # 1 to 5,000
```

| Setting | Default | |
|---|---|---|
| `grants.memberships.bases` | none (required on LDAP and Active Directory) | Groups outside them are refused. Not used on SCIM and Entra ID targets, whose groups are named by id |
| `grants.memberships.member_attribute` | `member` | `member`, `uniqueMember` or `memberUid` |
| `grants.memberships.max_per_entity` | 500 | Membership grants beyond it are refused |

A `dsml` target takes no grants: access granted across an air gap is the far side's to
enforce. One of the publication's own computed groups cannot be granted: its filter
decides its members.

### What the engine does with a grant

- It adds a membership a grant gives, and records that it did.
- It removes a membership only when the grant that covered it ends, or when a grant
  with the effect `revoke` names it. **A member someone added by hand is never removed
  otherwise**, not even by a reconciliation.
- A grant's end time is enforced by the engine's own timers.
- A leaver (an entry whose entity left the publication's scope) loses its granted
  memberships with its entry; the grant reads `pending` and applies again if the person
  comes back.
- Each grant succeeds or fails on its own: a missing group fails that grant, not the
  person's export.

`syncd grants` lists the grants and their states; `syncd grants --apply FILE` submits a
JSON file of grants, for tests and recovery.

## Setting the console up

1. Give the people who run governance the role `governance_admin` (scope everything).
   Auditors read governance too.
2. To let every employee reach their own page, make `participant` the default role of
   single sign-on (`CONSOLE_OIDC_DEFAULT_ROLE=participant`, or the SAML or certificate
   equivalent). A participant sees no engine and no configuration.
3. On the **Governance** page, an admin names the engine and the view whose entities are
   the people, and the attributes to keep: login (matched to the console sign-in name,
   without regard to case), display name, mail, manager, status, and up to 20 more. The
   console never asks the engine for sensitive values.
4. Register each governed publication as a system, and its entitlements.
5. On the **Assignments** page, find a person and give them an entitlement, with a
   reason. Its state follows the engine: `pending`, then `active`, or `failed` with the
   engine's reason. Ending it has the engine remove what it added.

A projection pass that would mark more than `max_gone_percent` (10 % by default) of the
people gone stops and changes nothing, and records `governance.projection.halted`: an
engine answering with half its view must not look like half the company leaving.

## The entitlement catalog

To know what people hold now, and not only what governance gave them, the console reads
each system's target once a day: every account, every group and its members, and the
values of attributes that are entitlements. The publication says where to look:

```yaml
grants:
  memberships:
    bases: ["OU=Groups,DC=corp,DC=example,DC=com"]
  inventory:
    account_bases: ["OU=Staff,DC=corp,DC=example,DC=com"]  # default: the dn's fixed end
    group_bases: []                     # default: memberships.bases
    attributes: [title, department]     # kept with each account, at most 20
    entitlement_attributes: [eduPersonEntitlement]
```

`syncd inventory --publication people_to_ad --kind groups` shows what a run will read.
The engine reads the target and never writes it.

Each account is linked to its person: the engine's own export links it (`managed`);
otherwise a correlation rule may match exactly one person (`rule`); otherwise someone
links it on the **Catalog** page (`manual`, kept by later runs). Accounts nobody owns are
listed there for review.

Each holding is **explained** when an open assignment gives it, or when a rule of the
engine's configuration decides the group (a computed group). On go-live, what was found
in place is unexplained; on a person's "What they hold" card, **Adopt** records it as the
baseline (an assignment with origin `import`), without changing the system. Ending an
adopted membership later has the engine remove it once.

A run that would mark more than 20 % of a system's accounts gone marks nothing and is
shown as **suspect** (`governance.inventory.suspect`); a run that cannot read the target
is **failed** and changes nothing.

## Access certifications

A **campaign** asks reviewers to keep or revoke what people hold. A **campaign definition**
says what is reviewed and by whom:

- **Reviewers**: each person's manager, each entitlement's owner (then the system's), or
  each system's owner. When that reviewer is missing or is the person reviewed, the
  person's manager reviews, then the definition's **fallback reviewer**. Nobody reviews
  their own access; a campaign with an item nobody can review does not start.
- **Scope**: some systems or all, privileged entitlements only, only access nothing
  explains, and the people a CEL expression selects, over `login`, `display_name`, `mail`,
  `status` and the projected attributes (for example `e.department == "Finance"`).
  Computed groups are left out unless included, and then can be kept or flagged only: a
  rule would add a revoked member again.
- **Timing**: days to review, reminder days, what an item nobody decides becomes (keep or
  revoke), whether revocations are carried out as each is decided or all at the close, and
  an optional schedule (start every so many days).

Starting a campaign snapshots the [catalog](#the-entitlement-catalog) as the last inventory
read it: run an inventory first. **Preview** gives the counts without starting anything.

What each decision does:

| Decision | Access an assignment explains | Access nothing explains |
|---|---|---|
| Keep | Recorded | Also recorded as an assignment with origin `certification`, so it reads as explained from then on |
| Revoke | The assignments that give it end; the engine removes what it added | The engine removes the group membership once (a revoke grant). Other kinds of access cannot be revoked this way: flag them |
| Flag | Recorded and listed in the report for whoever designs the configuration | The same |

A revocation needs a reason, and so does keeping a privileged entitlement.

When a campaign closes (by hand, or by itself seven days after its due date), undecided
items take the definition's default and queued revocations are carried out. Each
revocation is confirmed twice: by the engine's grant states, then by the next inventory of
its system. If the inventory still finds the access, the revocation is marked failed,
`governance.review.revocation_failed` is recorded, and a task appears under **Needs
attention** on the Access reviews page. **Sign off** is possible once every revocation is
confirmed or failed; after it, the database refuses any change to the campaign's items.

The campaign's report (CSV, Excel or PDF) has one row per item: person, account,
entitlement, system, explained, privileged, reviewer, decision, who decided and when, the
reason, and the revocation's state.

Reviewers are mailed when a campaign starts, on each reminder day while items are
undecided, and their manager once when their task is overdue. Mail goes only to the
allowed recipient domains, and links to the console when `CONSOLE_PUBLIC_URL` is set. The
webhook events `governance.campaign-started` and `governance.review-overdue` are raised as
well.

## Access requests

People ask for access on **Request access** (`/my/request`): for themselves, or, as a
manager, for a direct report. A governance administrator may ask for anyone. Each item goes
through an **approval workflow** (Governance → Workflows): the entitlement's, else its
system's, else the default one. With no workflow at all, requests are refused.

A workflow is a list of steps, each with:

| Field | What |
|---|---|
| Approver | The person's manager, the entitlement's owner, the system's owner, or one named person (`identity:<id>`) |
| Several approvers | Any one of them, or all of them |
| Skip when | A CEL condition over the person (`login`, `mail`, `status`, projected attributes) and `privileged`, `self_request`, `duration_days`, e.g. `!e.privileged && e.duration_days <= 30` |
| Days to decide | Then the approver's manager is added beside them, or the item is rejected |

**Nobody approves a request they made or benefit from.** Such an approver is replaced by
their manager, then by the default workflow's **fallback approver**; the database refuses it
besides. A rejection needs a comment and ends the item; the last approval makes an assignment
(origin `request`) and the engine carries it out. An end date given with the request is
enforced by the engine. An entitlement can require an end date and limit the duration
(Catalog → the entitlement). Removing access one holds needs no approval.

Approvers decide on **My approvals** (approve, reject, or hand to someone else). Requesters
and the people the access is for follow **My requests**, and can cancel what still waits.
Managers see their reports' access on **My team**. Governance administrators see every
request (Governance → Requests) and can approve or reject what waits in place of the
approvers, with a reason, recorded as an override; never on a request they made or benefit
from.

### The Directory Services Portal

- **Governed mode:** the portal asks for groups here on its users' behalf with a token of the
  scope `governance:delegate`, and its owners no longer decide. See
  [Governed mode](../directory-services-portal/configuration.md#governed-mode).
- **Changes its owners make:** add the portal under Governance → Workflows (*Portals
  reporting group changes*) for the system whose groups it changes; the console shows a
  signing secret once. In the portal, add a webhook for `group.membership_changed` to
  `<console>/api/v1/governance/ingest/dsp` with that secret. A member added there becomes an
  assignment with the origin `dsp` (a `conflict` in a group a syncd rule computes, because the
  engine will take the member out again); a member removed there ends it. Repeated deliveries
  are ignored.

## API

| Method and path | Who | What |
|---|---|---|
| `GET /api/v1/governance/settings` | governance readers, `governance:read` | The identity source |
| `PUT /api/v1/governance/settings` | admins | Change it |
| `GET /api/v1/governance/identities`, `/identities/{gid}` | governance readers | People, by login (`q`, `status`, `after`, `limit`) |
| `GET`, `POST /api/v1/governance/systems` | readers; writers, `governance:write` | Systems |
| `GET`, `POST /api/v1/governance/entitlements` | readers; writers | Entitlements |
| `GET`, `POST /api/v1/governance/assignments` | readers; writers | Assignments (`identity`, `open`) |
| `POST /api/v1/governance/assignments/{aid}/end` | writers | End one, with a reason |
| `GET /api/v1/governance/accounts` | readers | Accounts found (`system`, `identity`, `link`, `q`, `gone`) |
| `POST /api/v1/governance/accounts/{acid}/link`, `/unlink` | writers | Link an account to a person by hand, or unlink it |
| `PATCH /api/v1/governance/entitlements/{eid}` | writers | Describe an entitlement: name, description, owner, privileged, requestable |
| `GET /api/v1/governance/entitlements/{eid}/members` | readers | Who holds it, explained or not |
| `GET /api/v1/governance/identities/{gid}/access` | readers | What a person holds everywhere |
| `POST /api/v1/governance/systems/{sid}/inventory` | writers | Run a system's inventory now |
| `GET /api/v1/governance/inventory-runs` | readers | Inventory runs, newest first |
| `POST /api/v1/governance/assignments/adopt` | writers | Adopt up to 1,000 holdings found in place |
| `GET`, `POST /api/v1/governance/campaign-definitions`; `PUT`, `DELETE .../{did}` | readers; writers | Campaign definitions |
| `POST /api/v1/governance/campaign-definitions/{did}/preview` | readers | What a campaign would hold now |
| `GET`, `POST /api/v1/governance/campaigns`; `GET .../{cid}` | readers; writers | Campaigns, and one with its progress by reviewer |
| `POST /api/v1/governance/campaigns/{cid}/reassign`, `/close`, `/sign-off` | writers | Move a reviewer's items, close, sign off |
| `GET /api/v1/governance/campaigns/{cid}/report?format=csv\|xlsx\|pdf` | readers | The evidence report |
| `GET /api/v1/governance/tasks`; `POST .../tasks/{tid}/done` | readers; writers | Tasks for the governance administrators |
| `GET /api/v1/my/reviews`, `/my/reviews/{tid}/items` | every signed-in role | The signed-in person's review tasks and their items |
| `POST /api/v1/my/reviews/{tid}/decisions`, `/delegate`, `/complete`; `GET /api/v1/my/people` | every signed-in role | Decide, hand items to someone else, finish |
| `GET`, `POST /api/v1/governance/workflows`; `PUT`, `DELETE .../{wid}`; `PATCH /api/v1/governance/systems/{sid}` | readers; writers | Workflows, and a system's workflow and owner |
| `GET /api/v1/governance/requests`, `.../{rid}`; `POST .../{rid}/override` | readers; writers | Requests, and overriding their approvers |
| `POST /api/v1/governance/requests`; `GET .../{rid}/state`; `GET /api/v1/governance/entitlements/lookup` | administrators, or `governance:delegate` | Asking on a person's behalf (`on_behalf_of`) |
| `GET`, `POST /api/v1/governance/ingest-sources`; `POST /api/v1/governance/ingest/dsp` | readers, writers; the portal's signature | Portals reporting group changes |
| `GET /api/v1/my/catalog`, `GET`, `POST /api/v1/my/requests`, `GET .../{rid}`, `POST .../{rid}/cancel` | every signed-in role | Asking, and one's requests |
| `GET /api/v1/my/approvals`, `POST .../{tid}/approve`, `/reject`, `/delegate`; `GET /api/v1/my/team` | every signed-in role | Approving, and a manager's team |
| `GET /api/v1/my/identity`, `/my/access` | every signed-in role | The signed-in person, and what they hold |

Writes need the Governance pack. Reads never do: what was recorded stays visible after
a license lapses.

## Audit

| Action | Category |
|---|---|
| `governance.settings.changed` | configuration |
| `governance.system.created`, `governance.entitlement.created` | configuration |
| `governance.assignment.granted`, `governance.assignment.revoked` | authorization |
| `governance.entitlement.updated`, `governance.account.linked`, `governance.account.unlinked` | configuration |
| `governance.campaign_definition.saved`, `governance.campaign_definition.deleted` | configuration |
| `governance.campaign.created`, `governance.campaign.closed`, `governance.campaign.approved` (sign-off) | authorization |
| `governance.review.decided` (one per submission), `governance.review.delegated` | authorization |
| `governance.review.revocation_failed` | system |
| `governance.workflow.updated`, `governance.ingest_source.saved` | configuration |
| `governance.request.created`, `.approved`, `.rejected`, `.cancelled`, `.completed`, `.overridden` | authorization |
| `governance.assignment.conflict` | authorization |
| `governance.projection.halted`, `governance.inventory.completed`, `governance.inventory.suspect` | system |

## What the console stores about people

Until governance is set up, the console's database holds no identity data. Once it is,
it holds, for each person of the identity view: the engine's entity id, login, display
name, mail, the manager reference, a status (active, inactive, gone), the further
attributes named in the settings, and when the person was first and last seen; and for
each change the projection saw, the attributes' old and new values.

## Tested, and not yet

- The engine's grants: unit tests, and an end-to-end script against OpenLDAP 2.6. Not run
  yet against Active Directory, SCIM providers or Microsoft Graph.
- The console: integration tests against PostgreSQL with a stand-in engine (an
  assignment becomes a grant and is delivered; ending it removes it; a participant
  reaches only their own page; a projection that would lose most people stops; an
  inventory fills the catalog, links accounts, adopts found access, and stops when a run
  reads far less).
- The inventory: an end-to-end script against OpenLDAP 2.6; not yet against Active
  Directory, SCIM providers or Microsoft Graph.
- Certifications: integration tests (a manager campaign end to end with the stand-in
  engine, revocations at the close, the clock). A campaign of 200,000 items starts in about
  9 seconds; a page of a reviewer's queue answers in about 25 ms. Not yet an end-to-end
  script with OpenLDAP.
- Requests: integration tests (an approval chain to applied access, no self-approval,
  rejection, a manager for a report, end dates and limits, timeouts, overrides, the portal's
  signed webhook and delegated requests), and the portal's own test of governed mode against a
  stand-in console. Not yet an end-to-end script.
- Not built yet: holding grants in change windows and thresholds, correlation rules in
  the API, policies and roles.
