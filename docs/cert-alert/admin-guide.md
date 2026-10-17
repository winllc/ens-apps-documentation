# Admin guide

This guide is for **application administrators**: people holding `ROLE_ADMIN` inside CertAlert.
It covers everything an administrator can do in the UI and API. Deployment-level tasks
(configuration files, backups, upgrades) are in the [Operator guide](operator-guide.md).

## Becoming an administrator

CertAlert has no user database and no role-management screen. Roles come from configuration and
the directory:

| Role | Who gets it |
|---|---|
| `ROLE_USER` | Everyone who signs in successfully |
| `ROLE_ADMIN` | Anyone matching a value in `cert-alert.security.admin-identifiers` |
| *Project administrator* | Granted per project, in the UI, by an administrator (see [Projects](#projects-and-project-administrators)) |
| *Point of contact* | Derived from the directory's `serverPOC` and contacts added in CertAlert; grants rights over that server |

`admin-identifiers` is matched against any address the directory publishes for a person, their
`uid`, or their entry's DN. Common, display and preferred names, and addresses added in CertAlert,
never count. A DN contains commas, so give it its own YAML list item:

```yaml
cert-alert:
  security:
    admin-identifiers:
      - alice@intelink.ic.gov
      - bob
      - "uid=carol,ou=people,dc=example,dc=gov"
```

Changing administrators means changing configuration and restarting. On a fresh deployment, when
the cache is still empty, a password sign-in resolves roles from the directory entry just bound
against, so the first administrator can run the first sync.

You can tell you are an administrator from the account menu (top right), which reads
**Administrator · via LDAP** or **Administrator · via X509**; readers see **Reader**. The **Admin**
item appears in the navigation bar only for administrators.

The full matrix of who may do what is in [Security → Permissions](security.md#permissions-matrix).

## Syncing the directory

The **Sync directory** button sits in the page header of the **People**, **Servers** and
**Admin** pages, for administrators only. It runs both sweeps (people, then servers) and shows a
badge per job, for example `users: 104 seen, 3 new` (red if the job had errors). Tables refresh
automatically.

Sweeps also run nightly on a schedule. A sweep you trigger is recorded in the audit trail as
yours. For prune, refresh, certificate clean-up and changelog polls, use the
[API](api-reference.md#sync-and-jobs).

When the changelog connector is enabled, the header also shows its state: **Live · change N**,
**N changes behind**, or **Connector stopped**.

## The Admin page (`/admin`)

Administrators only. It has four parts.

### 1. Audit counts

Four cards: **Records kept**, **Last 24 hours**, **Last 7 days**, and **People and jobs** (distinct
actors), plus the span the trail covers. Together they say whether anything is running at all.

### 2. Revocation

Shows revocation counts and the **Refresh revocation statuses** button, which runs the nightly
revocation check now. It downloads every authority's CRL and asks an OCSP responder about
certificates no CRL covers, so on a large directory it takes a while; the button disables itself
while running and reports how long it took.

| Status | Meaning |
|---|---|
| **Revoked** | The authority lists this serial |
| **Not revoked** | The authority was asked and does not list it |
| **Unknown** | Asked, no usable answer (unreachable, unparseable, or nowhere to ask) |
| **Not checked** | Never asked |

A certificate found revoked is recorded in its entry's audit trail and its contacts are notified
once. Configuration (`issuer-directory`, `default-crl-url`, `default-ocsp-url`, …) is described
in [Configuration → Revocation](configuration.md#revocation-cert-alertrevocation).

### 3. Editable directory attributes

CertAlert is normally read-only toward the directory. Here an administrator can name **server
entry attributes** that may be edited from each server's page. Values are not stored in
CertAlert: they are read from the entry when shown and written back with an LDAP modify when
changed.

To make an attribute editable, fill in the form and press **Make editable**:

| Field | Meaning |
|---|---|
| **Directory attribute** | The LDAP attribute name, e.g. `ATOStatus` (required) |
| **Shown as** | Label on the server page; defaults to the attribute name |
| **What it is for** | Help text shown under the control |
| **Kind** | **Free text**, **Drop-down** (one of the listed choices) or **Yes or no** |
| **Values** | **One** or **Several** (Yes/No is always one) |
| **Choices, one per line** | Options for a drop-down |

Existing definitions are listed above the form and can be edited (the form switches to edit mode
with **Cancel**) or removed. Removing a definition only stops it being offered; the directory keeps
whatever values it holds.

Rules worth knowing:

- **Yes or no** writes `TRUE`, and "no" means the attribute is absent.
- Clearing any value removes the attribute from the entry.
- Writes go out as a single replace; the entry is read back so the cached row (for example the
  `ATOStatus` column) updates without waiting for a sweep.
- The directory's schema and ACLs have the last word: a refusal is shown as the directory
  returned it. If CertAlert binds anonymously, the page says it can read but not write.
- **The service account needs write access to exactly these attributes.**
- Only administrators can set values (`PUT /api/v1/servers/{id}/attributes/{defId}`); anyone signed
  in can read them.
- Every write is recorded on the server's audit trail (**Attribute set** / **Attribute cleared**).

### 4. System audit log

Every audit record across every entry, as a searchable, paged table with columns **When**,
**Kind**, **About**, **What happened**, **Who**, **Channel**, **To**. Filters:

| Filter | How |
|---|---|
| **Kind of event** | Any of the event kinds below |
| **About** | People and servers, People, or Servers |
| **Who did it** | Part of a name: a person, or a job (`sync`, `changelog`, `expiry refresh`, `prune`) |
| **Happened between** | Date range, either end optional |
| Column search | **Name or DN**, **What happened** |

Event kinds:

| Kind | Recorded when |
|---|---|
| Discovered | The first sweep sees an entry |
| Pruned | An entry is deleted after going unseen |
| Certificate published / Certificate withdrawn | The directory starts / stops publishing a certificate |
| Certificate deleted from the directory | The clean-up job removed it |
| Certificate revoked | The revocation check found it revoked |
| Certificate status changed | Valid → expiring soon → expired |
| Point of contact added / removed | A server's managed contacts changed |
| Address added / removed | A person's added addresses changed |
| Made a project administrator / No longer a project administrator | Project admin role changed |
| Added to a project / Removed from a project | Project membership changed (recorded on the entry) |
| Attribute set / Attribute cleared | An editable attribute was written |
| Endpoint probed | Someone ran the endpoint probe |
| Alert sent / Alert failed | One record per alert channel per alert |

Only *changes* are recorded: a sweep that finds the directory unchanged writes nothing. Records
outlive what they describe; after a prune the record keeps the entry's DN and name. Retention is
off by default (see [Configuration → Audit trail](configuration.md#audit-trail-cert-alertaudit)).

## Notifications administration

On the **Notifications** page administrators get extra controls:

| Control | Effect |
|---|---|
| **Email the round-up this many days before expiry** + **Save** | Sets how far ahead the daily round-up looks (1–365 days). Everyone sees the value and who set it; only administrators can change it. Overrides `cert-alert.notifications.digest.window`. |
| **Dry run** | Builds every round-up message and sends none, writes nothing, and shows who would hear and the rendered messages (up to 25 shown; the count gives the total). Works whether or not email is enabled. A message that failed to build (for example a typo in a custom template) is shown in red at the top. If nothing would be sent, a note says why. |
| **Run the expiry round-up** | Runs the round-up now: writes notifications and, if email is enabled, sends mail. |

The lead-days setting decides the round-up only. The page and the operator alert channels still
report a certificate crossing into `EXPIRING_SOON` (based on `warning-threshold-days`) regardless.

## Projects and project administrators

Projects group people and servers that belong together (see the [User guide](user-guide.md#projects-projects)).
Who may create, rename, delete and change membership of projects is governed by
`cert-alert.security.contact-editors`: administrators only by default (`ADMIN`), or anyone signed
in (`AUTHENTICATED`).

**Project administrator** is a role only an administrator can grant, whatever `contact-editors`
says. On a project's page each member has a **Make admin** / **Step down** button (administrators
only); a star marks members who run the project. A project administrator:

- may manage the points of contact on **every server in the project**, and run the endpoint probe
  on them;
- is **notified** when those servers' certificates are expiring, on the page and in the round-up.

Making someone a project administrator also makes them a member. Stepping down leaves them a
member; leaving the project gives up the role. Both are recorded on the person's audit trail.

## Managing points of contact and addresses

### Server contacts

On a server's expanded row or details page, the **Points of contact** editor shows the directory's
`serverPOC` (read-only) beside contacts **added here**. Add a person (pick from the list) or type
an email address; an address the directory knows is linked to its owner. Contacts added here
survive sweeps and receive alerts like directory contacts.

Who may edit a given server's contacts:

| May edit | How |
|---|---|
| A point of contact for that server | Named in `serverPOC` by any value that names them, or added here |
| A project administrator of a project containing it | Granted by an administrator |
| An administrator | Always |
| Anyone signed in | Only when `contact-editors: AUTHENTICATED` |

Anyone else receives a 403 and the UI hides the controls.

### Addresses a person answers to

On a person's page, **Addresses this person answers to** lists their directory addresses and
addresses **added here** (personal or **Group address**, such as a team list). An added address
joins the set a `serverPOC` is matched against, so servers named after it become theirs
immediately. A group address can be added to several people; each becomes a contact for the
servers named after it.

Editing addresses is governed by `contact-editors` (administrators by default), because an address
binds a person to every server named after it.

An added address never makes anyone an administrator: `admin-identifiers` is matched only against
what the directory publishes. An address that `admin-identifiers` names cannot be added at all; the
request is refused with a 400 that says so. An address added before it was named there stays: it
still binds servers and grants nothing.

## Directory-writing jobs

Two jobs change data permanently. Both are off by default and both have previews.

| Job | Preview | Run now | Notes |
|---|---|---|---|
| **Prune** (deletes cached entries unseen for `prune.after`) | `GET /api/v1/sync/prune/preview` → `{"prunable": N}` | `POST /api/v1/sync/prune` | The manual call runs regardless of `prune.enabled`. Deletes from CertAlert's cache only; audit records survive |
| **Certificate clean-up** (deletes expired/revoked certificates **from the directory**) | `GET /api/v1/sync/certificate-cleanup/preview` | `POST /api/v1/sync/certificate-cleanup` | Does nothing unless `certificate-cleanup.enabled: true`. Capped at `max-per-run`; revoked first; one audit record per certificate removed. Needs write on the certificate attribute |

!!! danger
    Certificate clean-up removes values from somebody else's system of record and cannot be undone
    from CertAlert. Review the preview, keep the grace periods, and grant the service account write
    on the certificate attribute and nothing else.
