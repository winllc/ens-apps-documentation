# Governance

!!! warning "In development, not in a release yet"
    This page describes the governance layer as it is being built on the
    `ldap-sync-merge-view` branch `claude/modest-mayer-onyb29`, after release 0.6.1. It is
    not in a release, and the Governance pack is not issued in licenses yet. What is built
    is listed below; certifications, access requests, policies and roles follow.

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
| `GET /api/v1/my/identity`, `/my/access` | every signed-in role | The signed-in person, and what they hold |

Writes need the Governance pack. Reads never do: what was recorded stays visible after
a license lapses.

## Audit

| Action | Category |
|---|---|
| `governance.settings.changed` | configuration |
| `governance.system.created`, `governance.entitlement.created` | configuration |
| `governance.assignment.granted`, `governance.assignment.revoked` | authorization |
| `governance.projection.halted` | system |

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
  reaches only their own page; a projection that would lose most people stops).
- Not built yet: holding grants in change windows and thresholds, the entitlement
  catalog read from the targets, certifications, requests, policies and roles.
