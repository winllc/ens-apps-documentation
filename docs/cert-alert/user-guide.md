# User guide

A walkthrough of every CertAlert screen for everyone who signs in. Administrator-only controls are
marked; they are described fully in the [Admin guide](admin-guide.md).

## Concepts in one minute

- CertAlert reads **people** (IC Persons) and **servers** (IC Non-Person Entities) from the
  directory and caches every certificate they publish. Nothing you see is fetched live from the
  directory; it is as current as the last sweep (or the changelog connector, if enabled).
- Every certificate has a **status**:

    | Status | Meaning |
    |---|---|
    | **Valid** | Expires beyond the warning window (30 days by default) |
    | **Expiring soon** | Expires within the warning window |
    | **Expired** | Past its *not after* date |
    | **None** | (entries only) Publishes no certificate |

- An entry's status is a **roll-up** of the certificates it still stands on. Old, already-expired
  certificates left behind by a correct renewal are ignored while something newer is still valid.
  A **person** needs every current certificate (their signing *and* encryption certificate), so
  the worse one counts. A **server** needs one good certificate, so the best one counts.
- A server's **points of contact** come from the directory's `serverPOC` plus contacts **added
  here**. They are who gets told about that server's certificates.

## Signing in

Some deployments first show a **notice and consent** page (the DoD banner). Read it and press
**I agree**; nothing signs you in until you do, and it is shown again for every new session.

CertAlert then offers two ways in:

1. **Client certificate**: if your browser presents a certificate that the directory publishes for
   you, you are signed in without a form.
2. **Directory password**: otherwise you see the **Sign in** page. Enter your directory username
   and password; they are checked by binding to the directory and never stored.

Messages on the sign-in page:

| Message | Meaning |
|---|---|
| *No client certificate was accepted…* | Always shown on the form: no usable certificate was presented |
| *That username and password were not accepted by the directory.* | Bind failed |
| *You have been signed out.* | After **Sign out** |
| *Your session has ended. Sign in again to carry on.* | Your session timed out after a period of inactivity, reached its maximum length, or was ended because you signed in elsewhere |

Your name, role (**Administrator** or **Reader**) and method (**via X509** / **via LDAP**) appear
in the account menu at the top right, together with your directory DN and **Sign out**.

!!! note "Classification banner"
    Some deployments show a coloured bar with a classification marking across the top and bottom
    of every page. It is set by the operators.

## Navigation

| Item | Page |
|---|---|
| **People** | `/users`: the people table (also the home page `/`) |
| **Servers** | `/servers`: the servers table |
| **Projects** | `/projects` |
| **Metrics** | `/metrics` |
| **Admin** | `/admin` (administrators only) |
| Bell icon | `/notifications`, with your unread count |

## People (`/users`)

### Status cards

Across the top: **Total**, **Valid**, **Expiring soon**, **Expired**: how many people are in each
rolled-up state.

### Filters

| Filter | What it does |
|---|---|
| **Certificates** | Any · Has an expired certificate · No expired certificate · Expiring soon · Valid only · Holds no certificate |
| **Show expired** switch | Off by default: people whose certificates have all lapsed are hidden. Turn on to include them |
| **Expiring within** N days | The *next* certificate runs out within N days |
| **Last certificate expires** from / to | The day the *last* of their certificates runs out, as a date range |
| **Point of contact name** | People a `serverPOC` value containing this text could name: matches every address and form of their name |
| **Risky names** | Any · Anything flagged · Wildcard · Broad wildcard · Many names · Many domains · Bare hostname |
| **Project** | Only members of one project |

A line under the filters summarises what is applied.

### The table

Columns: **Name**, **UID**, **Email**, **Title**, **Duty organization**, **Certs**, **Status**,
**Expires** (soonest), **Last expires**, **Last synced** (Employee type, Country and DN are hidden
but searchable). It opens **soonest to expire first**. The search box searches all columns,
including hidden ones; the footer has per-column search boxes for Name, UID, Email and Duty
organization.

- Click a **name** to open the person's [details page](#person-details-usersid).
- The **link icon** next to the email opens the Servers table filtered to servers this person is a
  point of contact for.
- Click **+** to expand a row: extra attributes (DN, employee type, admin organization, telephone,
  IC networks…), the cached certificates in detail, and the last ten audit records with a link to
  all of them.

## Servers (`/servers`)

The same layout as People, for servers.

### Filters

| Filter | What it does |
|---|---|
| **Certificates** | **Hide expired** (default) · Any, expired included · Has an expired certificate · Holds one, none expired · Expiring soon · Valid only · Holds no certificate |
| **Only mine** switch | Servers you are a point of contact for (from the directory or added here). Shown only if your account has a directory entry |
| **Expiring within** N days | Next certificate expiring within N days |
| **Point of contact** | A contact whose name or address contains this text: searches `serverPOC`, contacts added here, and the identifiers of linked people |
| **Responsible for** | Appears when you followed a person's link; shows whose servers you are looking at, with a clear button |
| **Last certificate expires** | Date range |
| **Risky names** | As on People |
| **Project** | Servers in one project |

A link can override the default with `?certState=`; the Metrics page uses this to show servers
holding risky names, lapsed ones included.

### The table

Columns: **Name**, **URL**, **IP address**, **Points of contact**, **Lifecycle**, **ATO**, **Duty
organization**, **Certs**, **Status**, **Expires**, **Last expires**, **Last synced**, **DN**.

The **Points of contact** cell shows the first contact, a **+n more** count, and **+n added** for
contacts added in CertAlert. Hover the count to see everyone. Expanding a row shows details,
certificates, the contacts editor and recent audit records.

## Person details (`/users/{id}`)

| Card | Contents |
|---|---|
| Header | Name, DN, rolled-up status badge, and **Servers they are the contact for** |
| **IC Person** | Directory attributes |
| **Addresses** | The five schema addresses (`mail`, `icEmail`, `internetEmail`, `niprnetEmail`, `siprnetEmail`) |
| **Organization** | Duty organization / sub-organization, admin organization, country, IC member, last synced, … |
| **Addresses this person answers to** | Directory addresses plus addresses **added here**. If you are allowed, add another address or a team list (tick **Group address**) and press **Add**. An administrator's identifier cannot be added |
| **Known to servers as** | Every value a `serverPOC` could use to name this person |
| **Projects** | Projects they are in; green badges mark projects they run |
| **Current credentials** | The signing and encryption certificates currently held, issue gap, and flags |
| **Certificates** | Every cached certificate, soonest to expire first |
| **Audit records** | The entry's full history (searchable, filter by **Kind**) |

### Current credentials flags

| Flag | Meaning |
|---|---|
| **No signing certificate** | Nothing current signs |
| **No encryption certificate** | Nothing current can be encrypted to |
| **Issued apart** | Signing and encryption certificates issued more than the pair window (7 days) apart: one was renewed, the other was not |
| **Use not known** | Cached before key usage was read; the next sweep fills it in |

### Certificate details

Each certificate shows **Status**, **Serial**, **Valid from / to**, **Key** (algorithm and size),
**Use** (Signing, Encryption, Signing and encryption, Other, Unspecified) with key-usage bits,
**SANs** with a count and any risk badges, **Revocation** status (with date, reason and detail),
and the **SHA-256** fingerprint.

Risk badges:

| Badge | Meaning |
|---|---|
| **Wildcard** | One key answers for every name under a domain |
| **Broad wildcard** | A wildcard over a shared suffix such as `*.gov` or a bare `*` (shown red) |
| **Many names** | More SANs than the configured threshold (20) |
| **Many domains** | Names span more registrable domains than the threshold (3) |
| **Bare hostname** | A name with no domain, e.g. `web01` |

## Server details (`/servers/{id}`)

| Card | Contents |
|---|---|
| Header | Name, DN, status, and **Open** (the server's URL) |
| **IC Non-Person Entity** / **Organization** | Directory attributes |
| **Points of contact** | Directory `serverPOC` (read-only) and contacts **added here**; editor if you may manage this server |
| **Directory attributes** | Attributes an administrator made editable; only shown if any are defined. Administrators can change values here; they are written to the directory |
| **Projects** | Projects the server is in |
| **Endpoint** | The endpoint probe (see below) |
| **Certificates**, **Current credentials**, **Audit records** | As for people |

### Managing contacts

If you are a point of contact for the server, run a project containing it, or are an
administrator, you can add contacts: start typing a name and pick a person, or type an email
address, and press Enter. Remove a contact added here with its close button. Each contact shows
whether it is linked to a directory entry, where alerts go, and who added it.

### Endpoint probe

Shown to people who may manage the server, when the entry has a `serverURL` or `icServerAddress`
and the probe is enabled. Choose a **Port** (defaults to the URL's port, or 443) and press
**Check**. CertAlert opens a TLS connection, reads what the server presents and compares it with
the newest certificate the directory publishes.

| Finding | Meaning |
|---|---|
| **Serving the current certificate** | All good |
| **Serving a superseded certificate** | It is published, but an older one: the renewal was never installed |
| **Certificate not published by the directory** | The directory has never held what is being served |
| **Nothing published to compare with** | The entry publishes no certificate |
| **Presented certificate has expired** / **is not valid yet** | The served certificate's own dates |
| **Name does not match the certificate** | The host is not a name on the certificate |
| **Self-signed** | Issuer equals subject |
| **No intermediate certificates sent** | Only the leaf was sent |

The result also shows the presented certificate (subject, issuer, serial, dates, names, key,
chain length), protocol and cipher. Nothing is stored; the audit trail records that the probe was
run and by whom.

## Projects (`/projects`)

A project is a name, a description, and a set of people and servers: "what these are for", which
the directory cannot say.

- The list shows each project with its description, number of people and servers, and creation
  date.
- **New project**: enter a name and description and press **Create** (administrators by default;
  anyone if the deployment allows it).
- Click a project to open it (`/projects/{id}`): its **People** and **Servers** cards, **Find a
  person** / **Find a server** boxes to add members, close buttons to remove them, and links to
  **Its people in the table** / **Its servers in the table** (the search tables filtered to the
  project).
- A star marks **project administrators**. Administrators see **Make admin** / **Step down**.
  Project administrators manage contacts on the project's servers and are told when their
  certificates are expiring.

Removing an entry from a project, or deleting a project, changes nothing about the entry itself.

## Notifications (`/notifications`)

Reached through the **bell** in the navigation bar, which shows your unread count.

The table lists your notifications with **When**, **Severity**, **Kind**, **What**, **About**
and **Email** columns, paged with **Previous** / **Next**. Kinds:

| Kind | When it appears |
|---|---|
| **Certificate status** | A certificate you are responsible for crossed into *expiring soon* or *expired*. Not repeated for the same certificate and state within a week |
| **Expiry summary** | The daily round-up: everything of yours expiring within the configured number of days. You have **one** of these; it is updated when something changes (and becomes unread again), and removed when everything in it has been dealt with |
| **Certificate revoked** | The revocation check found one of your certificates revoked |

Mark one read from its row, or all with **Mark all read**.

You are responsible for a certificate if it is your own, or if it belongs to a server where you
are a point of contact (directory or added here, including via a group address), or to a server in
a project you run. A person's signing/encryption pair is reported as **one** line, dated by the
sooner of the two. A certificate that has been replaced by a newer one with the same subject and
use drops out of the round-up.

The settings card at the top shows **Email the round-up this many days before expiry** and who set
it. If email sending is off, the card says so: the round-up then appears only on this page.
Administrators also see **Save**, **Dry run** and **Run the expiry round-up** (see the
[Admin guide](admin-guide.md#notifications-administration)).

## Metrics (`/metrics`)

Directory-wide reporting, all computed by the database:

| Card | Shows |
|---|---|
| Top cards | **Certificates cached**, **Valid**, **Expiring soon**, **Expired** |
| **Issued against expiring** | Bar chart, one bar per month for a year either side of today: certificates issued (left) and expiring (right). Bars right of the marker are renewals not yet done |
| **Key algorithms and sizes** | Count per key algorithm and size |
| **Signature digests** | Count per hash (for weak-hash reports) |
| **Falling due** | Already expired, within 7 / 30 / 90 days, within a year |
| **Notifications** | People told, still unread, emailed, channel deliveries, deliveries that failed |
| **The directory** | IC Persons and IC Non-Person Entities (and how many publish no certificate), projects |
| **Risky names** | Certificates flagged per risk, with a link to **the servers holding them** |
| **Issuance** | Issued in the last 30 / 90 days and year; average validity (over the last year) |
| **Issuing authorities** | Counts by issuer |
| **Revocation** | Counts by state, recent revocations, reasons, staleness of the oldest answer |
| **People by organization** / **Servers by organization** | Breakdown by duty organization, sub-organization and employee type; *not stated* where the directory has no value |
| **Signature algorithms** | Counts per signature algorithm |

## Error pages

| Page | When |
|---|---|
| Not found | The entry is not in the cache (the directory may have stopped publishing it). **Back to the directory** returns to People |
| Error | Anything else, worded by HTTP status. A 401 offers a sign-in link |

Need more? See [Troubleshooting](troubleshooting.md).
