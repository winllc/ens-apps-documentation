# Governance

!!! warning "In development, not in a release yet"
    This page describes the governance layer as it is being built on the
    `ldap-sync-merge-view` branch `claude/modest-mayer-onyb29`, after release 0.6.1. It is
    not in a release, and the Governance pack is not issued in licenses yet. What is built
    is listed below.

Governance answers who should have what, and why. The console decides; the engine
carries the decision out and keeps it, so access is enforced, and ends on time, while
the console is down.

- **People** come from one view of one engine. The console keeps a copy of a few
  attributes of each person (the *projection*), refreshed every 15 minutes by default.
- A **system** is a publication of an engine: the target whose access is governed. A
  **manual system** is one syncd does not write to: its accounts come from CSV imports and
  people make its changes, through tasks or tickets.
- An **entitlement** is something a person can hold in a system: a group membership,
  an attribute value, or a *scope* (a key the publication's filter can test).
- An **assignment** is the decision that a person holds an entitlement, from a date to a
  date, for a reason. The engine fulfils it through a **grant**.
- A **role** bundles entitlements across systems (and other roles): holding the role is
  holding each of them.

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
| Policies: separation of duties and rules over the person, a scan, violations, remediation and exceptions, and the check when access is asked for | Console: Policies, Violations, My policy violations |
| Lifecycle: rules for joiners, movers and leavers; emergency termination and reinstatement | Console: Lifecycle; Terminate on a person's card; the engine's reserved `terminated` scope |
| Manual systems: CSV imports, fulfilment tasks for the system's owner, tickets in ServiceNow, Jira or a webhook receiver | Console: Governance (Import CSV), Fulfilment, My tasks |
| Roles: versions a second person approves, membership rules, requests for roles, certifications of roles, role mining | Console: Roles (`/governance/roles`), Role mining |
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
  each system's owner. For roles, each role's owner reviews either **who holds the role**
  (revoking takes it away; a holder the membership rule gave it to can be kept or flagged
  only) or **what the role holds** (revoking proposes a version without it, which somebody
  other than the reviewer approves), for the roles chosen or every active role. When that reviewer is missing or is the person reviewed, the
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

## Roles

A **role** (Governance → Roles) holds entitlements, in any systems, and may include other
roles (at most five deep; never in a cycle). A person holding the role holds each
entitlement of what it expands to, as an assignment of the origin `role`; one who also holds
an entitlement directly, or through another role, keeps it when the role goes.

| Setting | What |
|---|---|
| Kind | Business (a job, a team) or IT (a technical bundle business roles include) |
| Owner | Approves requests for the role, and reviews it in role certifications |
| Requestable | People can ask for it on Request access |
| Longest duration | Days it is given for; a request then needs an end date within it |
| Approval workflow | The workflow requests for it go through; the default otherwise |

### Versions

What a role holds changes only by a **version**: someone proposes it (**Propose a change**:
entitlements, included roles, a membership rule, a comment) and **somebody else approves
it**. The proposer cannot, and the database refuses it besides. The role's page shows a
proposed version beside the one in effect: what it removes struck through, what it adds
marked new. A role is a draft until its first version is approved.

On approval the change reaches everyone who holds the role: assignments are added for what
it gained and ended for what it lost. When that would end more assignments than the **role
removal limit** (1,000 by default; `role_removal_limit` in the governance settings), the
approval is refused until the approver confirms it. A version whose entitlements alone
violate a policy that blocks requests cannot be approved.

**Retiring** a role ends every assignment of it; a role another active role includes cannot
be retired until that role no longer includes it.

### Membership rules

A version may give the role by a **rule**: CEL over the person `e`, with the attributes rule
policies read (see [Policies](#policies)), for example `e.department == "Sales"`. **Try the
rule** counts who would gain and lose the role, with some of their logins, writing nothing.
On approval the rule applies to everyone; after that, each joiner, mover and returner the
projection sees is looked at once: who matches gains the role, and who no longer matches
loses it after the version's **grace days** (0 by default).

### Giving and asking

A governance administrator gives a role on its page (a person, a reason, an optional end
date), or takes it away. People ask for requestable roles on **Request access**, where they
are listed before entitlements: the request goes through the role's workflow, the
entitlement owner's step is the role's owner, and when it is approved the person holds the
role. Giving up a role one holds needs no approval. Leavers and terminated people lose every
role.

Giving a role, by request or by hand, is checked against the policies over everything the
role expands to, as giving an entitlement is.

### Role or computed group?

Both give access to groups of people. Choose by who decides and how the rule is governed:

| | A role | A computed group |
|---|---|---|
| Lives in | The console | The engine's configuration |
| Changes by | A version a second person approves in the console | A configuration version, through config approval and promotion |
| Holds | Entitlements in any systems, other roles | Membership of one group in one target |
| Given by | Administrators, requests, a membership rule over the projected person | A filter over the view's entity, every time it changes |
| Ends | When taken away, by its end date, or after the rule's grace days | As soon as the filter no longer matches |
| Reviewed in | Role certifications (who holds it, what it holds) | Not reviewed item by item: a rule decides it (kept or flagged only) |
| Use it for | Jobs and teams whose access spans systems, exceptions people ask for, anything an owner should answer for | Groups that must follow an attribute exactly and at once, such as all staff, a department's list, or a location |

A computed group cannot be part of a role: the rule in the configuration already decides who
is in it.

### Role mining

**Role mining** (Governance → Roles → Role mining) proposes roles from what people hold now:

1. For each value of a person attribute (`department` by default) with enough people (5),
   the entitlements most of them (80 %) hold, with the share holding all of them.
2. Sets of two or more entitlements that enough people hold exactly alike.

Each run replaces the candidates nobody acted on. **Make a role** turns a candidate into a
draft role with its first version proposed: nothing changes until somebody else approves it.

## Policies

A **policy** says what nobody should hold (Governance → Policies):

- **Separation of duties** (`sod`): one entitlement of the first set *with* one of the
  second, e.g. creating payments with approving them.
- **Rule** (`rule`): a CEL condition over the person. It reads `e` with these attributes:

| Attribute | What |
|---|---|
| `e.login`, `e.display_name`, `e.mail`, `e.status` | From the identity source |
| `e.<attribute>` | Every attribute the settings keep (`extra_attributes`), e.g. `e.employeeType` |
| `e.entitlements` | A list: what the person holds, as `<system>:<native id>` (`=<value>` for an attribute value), e.g. `"Directory:cn=vpn,ou=Groups,dc=x"` |
| `e.privileged` | The same, only privileged entitlements |
| `e.roles` | The roles the person holds, by name |

  For example `e.employeeType == "contractor" && size(e.privileged) > 0`. A condition that
  does not compile is refused when the policy is saved, with where. **Try it** shows how many
  people a policy would find, and up to 50 of them, without saving anything.

What a person holds is **effective access**: what the inventory found on their accounts, and
open assignments the engine may not have carried out yet.

The **scan** runs after each complete inventory, every night, and on **Scan now**. A new
violation is **open**: its owner gets a task and mail (`governance.violation-opened` webhook
for high and critical policies). One an unexpired exception covers is **excepted**; when the
exception runs out it is open again. One no longer found is **remediated**.

The owner (on **My policy violations**) or a governance administrator either **removes
access** (each entitlement chosen is removed as a certification revocation removes it) or
**grants an exception** until a date, at most the policy's longest exception. Nobody excepts
their own violation.

When access is **asked for**, the request is checked against every enabled policy the
person would newly violate:

| The policy's setting | The request |
|---|---|
| Block | Refused, naming the policy |
| Warn | Goes ahead; every approver sees the warning |
| Require an exception | Gains an approval step for the policy's owner; their approval grants the exception |

A role is checked by everything it expands to. Giving an assignment or a role directly that
would violate a policy is refused unless the call says `override_policy` with a reason,
recorded as `governance.policy.overridden`.

## Lifecycle

The projection notes what happens to each person: **joined**, **changed** (with each
attribute's old and new value), **left**, **returned**. **Lifecycle rules** (Governance →
Lifecycle) act on these, each once per event:

| Action | What happens |
|---|---|
| Review (for movers) | The person's (new) manager reviews the access they keep, in a one-person campaign of everything no rule gives; what is undecided after the grace period (14 days) is revoked |
| Tell the manager (for joiners) | Once the person has an account and nothing of theirs is pending, or after a day, their manager is mailed the accounts by system and what is still being set up. Never a password |
| End assignments (for leavers) | Every assignment ends; the person's open tasks go to their manager (or to the governance administrators); whatever they owned (entitlements, systems, policies, a workflow's fallback) gets a task for a new owner |

A rule may have a condition: CEL over the person as `e`, and for a change
`e.previous_<attribute>` too. For example a mover rule on **changed** with
`e.department != e.previous_department`. Every installation starts with one rule: leavers'
assignments end. **What they did** lists each run.

A person who **returns** gets back what rules give (with roles); access they had asked for is
not restored: they ask again.

### Emergency termination

**Terminate** on a person's card (Governance → Assignments), with a reason, acts everywhere at
once: every assignment ends; in each governed publication where the person has an account the
engine is sent a `terminated` scope grant, which takes the person out of the publication's
scope whatever its filter says, so the leaver policy applies (under a staged policy the
account is disabled at once); and group memberships found in place are revoked. It is recorded
as `governance.identity.terminated` with the severity **alert**, and raises the webhook event
`governance.identity-terminated`.

**Reinstate** ends the `terminated` grants: the engine brings the accounts back as it does for
a rehire. Assignments that ended with the termination stay ended.

A publication must have a `grants` section for the engine to take the grant (see
[Setting a publication up for grants](#setting-a-publication-up-for-grants)).

### Contractors

Contractors are often not in the HR system. The [Directory Services
Portal](../directory-services-portal/configuration.md#entry-expiry) can keep their records in a
container, with an end date and a sponsor who is told before the end and can extend it; syncd
reads the container as a source and ends their accounts on the date with `effective_until`.
`examples/contractors-from-ldap.yaml` in syncd shows the configuration.

## Manual systems and tickets

Some systems syncd does not write to: an application without an API, a mainframe, a SaaS
product an administrator changes by hand. Add one as a **manual system** (Governance → Add a
system → Manual), with an owner and, optionally, a ticket system.

### Importing accounts

**Import CSV** on the system reads a file with a header row and one row per account (or per
account and entitlement):

| Column | |
|---|---|
| `account` | The account's name in the system: required |
| `login` | The person's login, to link the account (never over a link made by hand) |
| `state` | `enabled`, `disabled`, `locked` or empty |
| `entitlement` | What the account holds; several split by `;` |
| anything else | An attribute of the account, which correlation rules can match |

```csv
account,login,state,entitlement,department
jdoe,jdoe,enabled,Payroll Admin;Viewer,Finance
svc-batch,,disabled,Batch,
```

An import is an inventory run: what is in the file is upserted, and accounts, entitlements
and holdings that are not are marked gone, unless the file has far fewer accounts than are
known (more than the system's limit, 10 % by default, would go), in which case nothing is
swept and the run is `suspect`. Files up to 64 MiB and 200,000 rows; the API takes the file
as a `text/csv` body, so a script or a scheduled job can send it.

### Tasks

Access granted on a manual system (by an administrator, an approved request, or a policy's
remediation) is a **task** for the system's owner, or for the governance administrators when
it has none: "Give Payroll Admin to jdoe in Payroll". Access ended is a task to remove it;
ending access whose task to give it is still open cancels that task. The owner sees their
tasks on **My tasks** and marks each done, with a note; the assignment becomes active and
the request completes. The next import that finds the change made also completes the task.
**Fulfilment** lists every task.

### Ticket systems

With a **ticket system** on the system (Fulfilment → Add a ticket system), each task is also
opened as a ticket within a minute:

| Kind | Settings | Done when |
|---|---|---|
| ServiceNow | The instance URL, the table (`sc_task`), the states that mean done (3, 6, 7), a user and password or an OAuth token | The ticket's state is one of them |
| Jira | The site URL, the project key, the issue type (Task), an account email and API token (Cloud) or a personal access token (Data Center) | The status category is Done |
| Webhook | The receiver's URL and a signing secret | The receiver completes the task through the API |

ServiceNow and Jira tickets are read back every ten minutes; a ticket done completes its task
and the request. A webhook receiver gets a `POST` signed as [webhooks](webhooks.md) are
(`X-Syncd-Signature`), with `type: governance.fulfilment-task` and the task; it may answer
`{"id", "url"}` to show its ticket on the task. Ticket systems are held to the same address
rules as webhooks (`https://` only, no private addresses unless allowed). Credentials are
encrypted with the console's master key and never shown again. A ticket that cannot be
opened keeps its error on the task and is tried again ten minutes later.

The ServiceNow and Jira adapters follow those products' documented APIs and are tested
against stand-ins; try them against your instance before relying on them, and add any field
your instance requires (an assignment group, a component) under the adapter's `fields`
mapping.

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
| `GET`, `POST /api/v1/governance/policies`; `PUT`, `DELETE .../{pid}` | readers; writers | Policies |
| `POST /api/v1/governance/policies/simulate`, `.../{pid}/simulate` | readers | Whom a policy would find, writing nothing |
| `POST /api/v1/governance/policies/scan` | writers | Scan now |
| `GET /api/v1/governance/violations`; `POST .../{vid}/remediate`, `/exception` | readers; writers | Violations (`policy`, `state`, `severity`, `identity`) |
| `GET /api/v1/my/violations`; `POST .../{vid}/remediate`, `/exception` | the policy's owner | Their policies' violations |
| `GET`, `POST /api/v1/governance/lifecycle-rules`; `PUT`, `DELETE .../{lid}`; `GET /api/v1/governance/lifecycle-runs` | readers; writers | Lifecycle rules and what they did |
| `POST /api/v1/governance/identities/{gid}/terminate`, `/reinstate` | writers | Emergency termination, with a reason |
| `POST /api/v1/governance/systems/{sid}/import` | writers | A manual system's CSV (`text/csv`) |
| `GET`, `POST /api/v1/governance/ticket-adapters`; `PUT`, `DELETE .../{aid}` | readers; writers | Ticket systems |
| `GET /api/v1/governance/fulfilment-tasks`; `POST .../{tid}/complete` | readers; writers | Tasks of manual systems (`state`, `system_id`) |
| `GET /api/v1/my/tasks`; `POST .../{tid}/complete` | the system's owner | Their tasks |
| `GET`, `POST /api/v1/governance/roles`; `GET`, `PUT .../{rid}`; `POST .../{rid}/retire` | readers; writers | Roles |
| `GET`, `POST /api/v1/governance/roles/{rid}/versions`; `POST .../versions/{ver}/approve` (`confirm_removals`), `/reject` | readers; writers | Versions: propose, approve (never one's own), reject |
| `POST /api/v1/governance/roles/{rid}/simulate`, `.../versions/{ver}/simulate` | readers | Who a membership rule would add and remove, writing nothing |
| `GET`, `POST /api/v1/governance/roles/{rid}/members`; `POST .../members/{maid}/end` | readers; writers | Who holds a role (`all` for ended ones too); give it (`override_policy`), take it |
| `POST /api/v1/governance/role-mining`; `GET /api/v1/governance/role-candidates`; `POST .../{cid}/accept`, `/dismiss` | writers; readers | Role mining |
| `GET /api/v1/my/identity`, `/my/access` | every signed-in role | The signed-in person, and what they hold |
| `GET /api/v1/my/password`; `POST /api/v1/my/password/reset`, `/my/account/unlock` | every signed-in role, after a fresh single sign-on | Reset one's password, unlock one's account ([Password synchronisation](password-sync.md#forgotten-passwords-and-locked-accounts)) |
| `POST /api/v1/governance/identities/{gid}/password-reset-link` | the person's manager, or `reset_passwords` | Mail the person a single-use reset link |
| `POST /api/v1/password-reset/check`, `/password-reset` | anyone with the link | The public reset page |

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
| `governance.policy.created`, `.updated`, `.deleted` | configuration |
| `governance.role.created`, `.updated`, `.proposed`, `.approved`, `.rejected`, `.retired`; `governance.role_mining.ran` | configuration |
| `governance.role.assigned`, `.unassigned`, `.rule_applied` | authorization |
| `governance.violation.detected`, `.remediated`, `.excepted`; `governance.policy.overridden` | authorization |
| `governance.lifecycle.updated` | configuration |
| `governance.lifecycle.triggered`, `governance.identity.reinstated` | authorization |
| `governance.identity.terminated` (severity alert) | authorization |
| `governance.ticket_adapter.saved` | configuration |
| `governance.system.imported` | data |
| `governance.fulfilment.completed` | authorization |
| `governance.fulfilment.ticket_opened` | system |
| `governance.projection.halted`, `governance.inventory.completed`, `governance.inventory.suspect` | system |

## Password reset links

A manager can send each direct report a link to set a new password (**My team**), and so can
a holder of the console permission `reset_passwords` (admins and governance
administrators), once the governance setting `reset_mail_attribute` names a projected
attribute (one of `extra_attributes`) holding an address that does not depend on the
forgotten password. See [Password synchronisation](password-sync.md#forgotten-passwords-and-locked-accounts).

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
- Policies: integration tests (a person with one entitlement of each set is found and one
  with one set only is not; an exception hides a violation until it runs out; remediation
  resolves it; simulation writes nothing; a broken condition is refused; a request is
  blocked, warned or gains the owner's step; a direct assignment needs an override).
- Lifecycle: integration tests (leaver, mover review and its grace period, joiner mail,
  termination and reinstatement), engine tests of the `terminated` scope, and a termination
  case in the engine's end-to-end script against OpenLDAP.
- Manual systems: integration tests (an import fills a system, links by login, sweeps or
  stops as an inventory does, and finds tasks done; owners' tasks; tickets opened, read back
  and closed against stand-ins for ServiceNow, Jira and a webhook receiver). Not yet run
  against real ServiceNow or Jira instances.
- Roles: integration tests (three entitlements in two systems; four eyes through the API
  and in the database; a version reaching its holder; the removal limit; a role's end keeping
  access held directly; includes and a refused cycle; a rule following a mover and a
  joiner; a request for a role; both role certifications; mining nine of ten people holding
  the same three entitlements), and two grants of one group in the engine's end-to-end
  script against OpenLDAP.
- Not built yet: holding grants in change windows and thresholds, correlation rules in
  the API, and roles as policy sets (a role is checked by what it expands to).
