# User guide

This guide walks through every screen available to signed-in users. What you see depends on the
permissions an administrator has given you. Administrator-only screens are described in the
[Administrator guide](admin-guide.md).

## Signing in

Open the portal's address in your browser. The login page shows the portal name and any notice
your organisation has configured (for example help-desk contact details), and, if configured, a coloured banner at the top and
bottom of every page.

If your organisation has turned on the **notice and consent banner** (the U.S. Government
information system notice), the login page shows it first. Read it and choose **I agree**; you
can't sign in until you do. You see it again before each sign-in.

=== "Username and password"

    Enter your **directory** username and password. These are the same credentials as your LDAP
    / directory account; the portal has no separate passwords.

    - After repeated failed attempts (8 within 15 minutes unless your administrator has changed
      it), further attempts from your address are refused for that username for a while ("Too many
      failed login attempts. Try again later.").
    - If the portal cannot reach the directory, the page shows **Directory unreachable**.

=== "Single sign-on"

    If your organisation has set it up, the login page shows a button such as **Sign in with single
    sign-on**. You sign in at your organisation's identity provider and come back signed in, as long
    as the directory holds exactly one account for you. **Sign out** ends your portal session only,
    not your session at the identity provider.

=== "Certificate (smart card)"

    If certificate sign-in is enabled, choose **Sign in with certificate** and let the browser
    present your client certificate (for example a CAC/PIV smart card).

    - Some sites sign you in automatically when your browser presents a valid certificate. After
      you sign out, automatic sign-in stays off until you click **Sign in with certificate** again.
    - If your certificate is refused, **Certificate not accepted** explains why. For example, it
      has expired, it wasn't issued by a trusted authority, or no directory account matches it.

Your session ends:

- after a period of inactivity (30 minutes by default; administrators may have a shorter limit).
  Leaving the portal open in a tab doesn't keep you signed in;
- a fixed time after you signed in, whatever you are doing (12 hours by default);
- when you sign in somewhere else, if your organisation limits how many sessions you may have:
  the oldest one ends.

The sign-in page then tells you why you were signed out. Use the **Sign out** button in the top
bar to end your session yourself.

## Layout

| Area | Contents |
|---|---|
| Sidebar | **Home**, **My profile**, **Metrics**, then one item per directory you can read. Administrators also get the **Admin** section |
| Top bar | The bell for **background tasks**, your name, and **Sign out** |
| Banners | Optional fixed top and bottom banner (for example a classification marking) |

On narrow screens, the sidebar opens from the menu button.

## Home

The home page lists the **Directories** you have access to, each with its icon and description.
Read-only directories carry a **Read only** badge ("White pages: read only for everyone"). A
**My profile** card links to your own entries. If no directory has been set up yet, you see
*No directories yet*.

## Browsing a directory

Choose a directory in the sidebar (address `/d/<slug>`).

- **Search**: type in the search box to match the directory's quick-search attributes (substring
  match).
- **Sort**: click a column heading. Click again to reverse the order. The default sort is the
  directory's title attribute.
- **Paging**: 25 entries per page, with previous/next buttons.
- Click the first column of a row to open the entry.
- **New ...** (for example *New employee*) appears when you may edit the directory.

Search, sort and page are kept in the address, so you can bookmark or share a view.

## Viewing an entry

The entry page shows the entry's title, its DN (with a **Copy DN** button) and a **Details** card
with the form's fields, grouped by section. Drop-down values are shown with their option labels.
For example, a manager's DN appears as the person's name.

- **Show raw attributes** displays the entry in LDIF-like form (`dn:` plus every
  `attribute: value` returned). Password attributes are never sent to the browser.
- **Edit** and **Delete** appear when you have edit access.
- **History** (administrators only) opens the audit log filtered to this entry.

## Creating an entry

1. In the directory, click **New ...**.
2. If the directory allows several containers, choose one in **Create in** (the parent DN for the
   new entry).
3. Fill in the form. Required fields are marked. Fields may be free text, text areas or drop-downs,
   and can hold one value or several.
4. Click save. The entry is named from the form's naming attribute (for example `uid=jdoe,...`).

Drop-downs work in one of these ways:

- a fixed list, which may also accept your own values
- a list loaded from the directory
- **search as you type**: start typing (usually at least 2 characters) and pick from the matches.
  Each word you type must match one of the configured attributes (such as name, user ID or e-mail).

Values are validated in the browser and again on the server: required fields, single-value fields,
formats (e-mail, phone, URL, number, DN), patterns, maximum length and drop-down options. Errors
are shown next to the field.

## Editing and deleting

- **Edit** opens the form with the current values. Only attributes you changed are sent to the
  directory.
- Changing the value of the naming attribute (for example `uid`) **renames** the entry.
- **Delete** asks for confirmation (*Delete entry?*) and then removes the entry from the directory.

You can only read and write entries inside the directory's namespace. The portal rejects anything
outside it. Read-only (white pages) directories can never be edited, not even by administrators.

## My profile

**My profile** (`/me`) shows who you are and where you appear across the directories:

- **Your account**: username, display name, distinguished name, e-mail, groups, whether you are an
  **Administrator**, and how you signed in (with the certificate subject for certificate
  sign-ins).
- **Your entries**: for every directory that is set up to find you (for example where the entry's DN
  is yours, or where you are a `member` of a group, or a device's assigned user), the matching entries.
- **Self-service**: fields marked as self-service can be edited here on your own entries, even if you
  have no edit access to the directory itself. Other fields stay read-only.
- **Not listed in**: directories that could not find an entry for you.

If no directory can locate you, the page shows *No directories can locate you*.

## Metrics

**Metrics** (`/metrics`) offers statistics and charts computed live from the directories you can
read. Every chart reads one directory, so it covers only entries you're allowed to see.

### Metrics home

- **Entries per directory**: an entry count for each directory you can read.
- **Directory overviews**: a ready-made dashboard for each directory.
- **Dashboards**: dashboards that administrators have shared with all signed-in users. Dashboards
  that are not shared are marked **Admins only** and are visible to administrators only.

### Directory overview

Generated automatically from the directory's form. It shows:

- the entry count
- new entries per month
- a breakdown for every drop-down field and e-mail domain
- containers (for subtree directories)
- values per entry for multi-valued links (for example group members)
- how completely each field is filled in

### Working with a chart

| Control | Does |
|---|---|
| **Show table** / **Show chart** | Toggle between the chart and its data table |
| **Download CSV** | Save the chart's data as a CSV file |
| **Open in the explorer** / **Customize in the explorer** | Open the chart in the explorer to change it |
| **Refresh** (dashboards) | Recompute instead of using cached results |

Results are cached briefly (60 seconds by default). A chart marked as partial read more entries
than the configured limit allows, so its numbers cover only part of the directory.

### Explorer

**Explore** (`/metrics/explore`) builds a chart from any directory you can read. The chart updates
as you change the settings:

| Measure | Shows | Chart types |
|---|---|---|
| Count | Number of entries | number |
| Breakdown | Entries per value of an attribute (top N, the rest as "Other", missing as "(none)"), optionally split by a second attribute | bars, columns, donut, stacked bars, table |
| Completeness | Share of entries with a value, per attribute | bars, table |
| Over time | Entries per day, week, month, quarter or year of a date attribute, optionally as a running total | columns, line, area, table |
| Numbers | Count, average, median, lowest and highest, plus a histogram, of numeric values or of how many values each entry has | columns, number, table |

Values can be grouped before counting, for example by lower/upper case, e-mail domain, the name in
a DN, its parent container, the first N characters, a regular expression's first group, or a
date's year, quarter, month, week or day. An extra LDAP filter narrows any chart.

You can chart the attributes the directory shows you (form fields, list columns, title) and
entry timestamps. The explorer settings live in the address, so you can bookmark a chart or send it
to a colleague. Administrators also see **Add to dashboard**.

## Background tasks

Metrics that take longer than a couple of seconds, and all data analyses, continue on the server in
the background. The **bell** in the top bar lists your recent tasks:

- While a task runs, its page shows what it is doing (for example "Reading Devices"), how many
  entries it has read and, when the source has been read before, how far along it is. You can
  cancel it.
- You can leave or close the page. When the task finishes, the portal shows a notification with a
  link to the result. With **Notify me on this device**, you also get a desktop notification while
  the tab is in the background. Tasks whose page is open are not announced; the page simply shows
  the result.
- Each entry in the bell list shows the task's status (running, ready, failed or cancelled). You
  can remove finished tasks from the list.
- Asking for the same result while it is computing joins the running task. Opening a result
  shortly after it finished shows it without computing it again.
- Each user can have a limited number of tasks running or queued at once (5 by default). Tasks are
  lost if the server restarts.

## Exporting data

| What | How |
|---|---|
| Chart data | **Download CSV** on any chart (overviews, dashboards, explorer) |
| Analysis findings (administrators) | **CSV** on an analysis result: every finding matching the current filters |
| Custom schema (administrators) | **Export custom** on the Schema page (`.schema` file) |
| A single entry | **Show raw attributes** on the entry page, then copy the text |
| A directory's entries | **Export CSV** or **Export LDIF** on the directory page: the entries matching your search (all of them without one), up to 10,000 by default, with the form's fields and never passwords |
| A group's members | **Export** on the group's page (CSV or LDIF) |

## Importing entries and changing members in bulk

Where you may write to a directory:

- **Import** takes a CSV in the export's layout or an LDIF file. **Check file** first shows, row by
  row, what would be created, updated, left unchanged or refused, without writing anything; then
  **Import** writes. Every entry is checked like a form you saved yourself.
- On a group, **Change members in bulk** adds and removes many members at once (one per line).

See [Import and export](import-export.md) for the file formats and limits.

## Errors you may see

| Message | Meaning |
|---|---|
| *You do not have access to this directory* | No view permission for that directory |
| *This directory is read only* | Writes through a white pages directory are not allowed |
| *You can only edit your own entry* | A self-service edit was attempted on another entry |
| *Authentication required* | You are not signed in, or your session has ended; sign in again |
| **Signed out**, on the sign-in page | Your session timed out (idle, or the maximum session length), or ended because you signed in elsewhere; the notice says which. Sign in again |
| *... is not included in this portal's license* | That feature isn't licensed for your organisation; ask your administrator |
| *Page not found* | The address doesn't match any screen |
