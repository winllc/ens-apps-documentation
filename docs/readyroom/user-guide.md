# User guide

This guide walks through every screen of the ReadyRoom web application. What you see depends on
your role (`USER`, `MANAGER` or `ADMIN`) and on whether anyone reports to you in the directory. The
help button (**?**) in the top bar opens a short in-app overview of the same material.

## Signing in

ReadyRoom signs you in automatically if your browser presents a **client certificate** issued to
you. You go straight to **Home**, and the user menu has no *Log out* entry, because your
certificate would sign you straight back in.

Without a certificate, the **Sign in** page asks for your directory credentials:

- **Username**: your `uid`, your common name (`cn`) or your email address.
- **Password**: your directory password.

A wrong username or password shows an error on the same page. When you signed in with a password,
**Log out** in the user menu (your avatar, top right) ends the session.

Where your organization has set up **single sign-on**, the **Sign in** page also has a button for it
(for example *Sign in with single sign-on*). You sign in at your organization's identity provider and
come back signed in, provided the directory holds exactly one account for you. **Log out** ends your
ReadyRoom session only, not your session at the identity provider.

!!! note "You do not check in on the website"
    Check-in happens on your Windows workstation. The ReadyRoom agent reports when you sign in,
    lock or unlock the screen, and sign out. Your first sign-in or unlock of the day counts as
    arriving.

## The page layout

| Area | What it contains |
|---|---|
| **Banner** (top and bottom) | The text your organization configured, for example a classification marking. |
| **Sidebar** | Home, Groups, Users, My Reports, Approvals, Org Chart and, for admins and managers, Metrics and Settings. Admins also see License, Delegates, Privacy and Audit Log. |
| **Bell** (top left) | Your unread notifications. A red dot means there is at least one. Click one to open it, or **Mark All Read** to clear the list. |
| **System Date** (admins and managers) | Shows the site as it stood on an earlier day. See [Looking at another day](#looking-at-another-day). |
| **?** | The "How ReadyRoom works" help dialog. |
| **Avatar menu** | **My Profile**, **My data**, and **Log out** for password sessions. |

## Statuses

| Status | Meaning |
|---|---|
| `IN` (green) | Checked in, and the latest event was a sign-in or an unlock. |
| `AWAY` (purple) | The latest event was a screen lock. |
| `OUT` (blue) | Checked out: you signed out, or ReadyRoom checked you out after the screen had been locked for too long. |
| `NONE` (grey) | Nothing recorded for the day. |

If a status is on your calendar for the day (anything other than *Standard*), it is shown
instead, for example `WORK_FROM_HOME`, `TDY` or `LATE_ARRIVAL`. A status you asked for counts only
once it is approved; see [Statuses and approvals](#statuses-and-approvals).

## Home

**Home** (`/app/home`) is your own day. The top line shows the date being viewed.

| Card | Shows |
|---|---|
| **My status** | Your current status and when it started, the time you checked in and out, the time you are **expected in by** with the rule it came from, and your **average arrival**. |
| **Last 30 days** | Working days you checked in, days covered only by a status, and days with nothing recorded, as counts and a bar. |
| **Workstation agent** | *Reporting* with the time of the last event, or *Not reporting* if nothing has arrived from your PC for 7 days or more. If you have been working but see *Not reporting*, the agent on your PC needs attention. |
| **Coming up** | Statuses you have set for the next 14 days, with a link to **Update my calendar**. |

**Expected in by** is worked out in this order:

1. a *Late Arrival* time you entered for today,
2. otherwise the **login time** you set on your profile,
3. otherwise your **average arrival**: the mean of your first check-in on each weekday over the
   last 30 days.

If you are not checked in by that time plus the grace period (60 minutes unless your organization
changed it) on a working day, your managers are notified.

### My team (if people report to you)

If anyone reports to you in the directory, **Home** also shows your team. You do not need a
particular application role for this.

| Card | Shows |
|---|---|
| **Accounted for** | The percentage of your reports who are checked in or have a status today, and the mix of statuses. On weekends and holidays it says why nobody is expected. |
| **Not in yet** | Reports who are not accounted for, with their expected time. A **Late** badge (**Missed** when you view a past day) appears once they are past it. |
| **Awaiting your response** | Absence alerts you have not answered. Click one to respond. |
| **Team agents** | How many reports' agents reported in the last 7 days, and who stopped reporting. |
| **Team coming up** | Your reports' statuses for the next 14 days. |

## Groups

**Groups** (`/app/groups`) shows the directory group trees your organization configured.

- **Admins** see every group.
- **Everyone else** sees the groups they are a member of in the directory and the groups they
  manage. Parent groups are shown so you can reach them, but opening a parent you are neither a
  member nor a manager of shows *Unauthorized*. If there are none, the page says
  *No groups available*.

Use the search box to filter by name, **Expand All** or **Collapse All**, and the **Favorites only**
switch to show only favorite groups. Each group shows its member count. Click a group to open it.
Admins and managers can star or unstar a group directly in the tree. Other users see the star
without being able to change it.

### A group's page

`/app/users/<group DN>` shows:

- **Members**: a table of each member's name, status, last status change and notes. Each column can
  be filtered. Turn on **Advanced** to also show the DN, check-in time and check-out time. Click a
  row to open that person's details. Other members' times and notes are shown only if you manage
  the group (or can otherwise see their details, see [A person's details](#a-persons-details)).
  Everyone else's row shows their status only.
- **Favorite**: pins the group so it shows under *Favorites only* on the Groups page.
- **Generate Group Report** (admins, and managers of this group with the `MANAGER` role): pick a
  date range, then **Generate Report**. See [Group report](#group-report).
- **Group Managers** (the same people): see [Administration](admin-guide.md#group-managers).
- **Calendar**: a month calendar combining the statuses each member you may see has set (leave,
  telework and so on), each named and linked to the person.
- **CSV** and **Excel** download the member list with each person's status today. The same buttons
  are on the results of *Find a user*.

!!! note
    Saving a favorite is limited to the `MANAGER` and `ADMIN` roles. For a user with only the `USER`
    role, the server refuses the change.

### Group report

The report page shows the group name, the date range and the number of users, then:

- **Day Reports**: for each day, the number of check-ins and check-outs, and counts by employee
  type, location and organization.
- **User Reports**: for each member, each day's status, check-in time, check-out time and how long
  they were checked in.

**Download PDF** saves the report as `group_report.pdf`. **CSV** and **Excel** download a row per
person and day (status, first check-in, last check-out, minutes in and time zone), with each person's
times in their own time zone, or choose **My time zone** to show every row in yours. See
[Reports, exports and time zones](exports.md).

Managers and administrators can have a group's report e-mailed every day, week or month:
**Scheduled reports** (`/app/reports/schedules`).

## Users

**Users** (`/app/user/search`) finds anyone in the directory. Type at least three characters of a
name and choose **Search**. The search matches any part of the common name. The results table has
the same columns as a group's member list, including the **Advanced** switch. For coworkers whose
details you may not see, it shows their directory details and status only. Click a row to open that
person's details.

## A person's details

`/app/user/details/<DN>` shows a person's **User Information**: status, organization, employee
type, location, branch, email, phone, manager, average login time and notes. **Member Of** lists
their directory groups, each linking to the group page.

Coworkers see the directory details and, depending on how your organization set ReadyRoom up,
the status (in, away or out, and the day's status), only whether the person is in or out, or no
status at all. They never see the average login time, the notes, or the cards below. A status a
manager recorded after an absence alert shows to them as *Out of Office*. You see everything,
including the following, if it is your own page, you are an admin, or you are responsible for
the person: you manage one of their groups, they report to you directly in the directory, or you
are one of their delegates.

- **Audit Log**: every check-in, lock, unlock and check-out recorded for them, newest first.
- **Delegates**: the people who, besides their directory manager, receive their absence alerts,
  may answer them and approve their requests. Only admins change them.
- **Event Calendar**: their statuses, their online periods ("Online: ..."), and organization events
  and holidays.

If you are the person's directory manager, one of their delegates or an admin, you can **record a
status** for them: drag across days on their Event Calendar, choose the status and **Record**. It
takes effect at once, without approval, and you can record any status, including *Absent*,
*Absent Unexcused* and *Unscheduled Leave*.

Admins also see a **Role** card, with **Export or erase data**. See
[Administration](admin-guide.md#roles).

## My Reports

**My Reports** (`/app/user/reports`) lists everyone who reports directly to you in the directory.
If nobody does, the page says *No direct reports*.

| Section | Shows |
|---|---|
| **Accounted for today** | Percentage and count of reports accounted for. |
| **Attendance, last 30 days** | Share of working days checked in, days covered by a status, and days with nothing recorded. |
| **Average arrival, last 30 days** | The team's average first check-in on working days. |
| **Absence alerts, last 30 days** | Alerts raised, answered and unanswered, with how they were answered. |
| **Daily attendance, last 30 days** | A chart of each day's counts, with **Show as a table**. |
| **Status today** | Every report's current status, organization and employee type. **Advanced** adds the DN and check-in and check-out times. |
| **Attendance by report, last 30 days** | For each report: days checked in, status-only days, days with nothing recorded, average arrival, and when their agent last reported. A **Quiet** badge means nothing was received for a week or more. |
| **Calendar** | A month calendar combining the statuses your reports have set, each named and linked to the person. |

## Org Chart

**Org Chart** (`/app/orgchart`) shows the organization hierarchy built from an attribute in the
directory. Each organization shows **present / total**, counting sub-organizations, and a
percentage. Filter by name, expand or collapse the tree, and click an organization to list its
members, with the usual **Advanced** switch. As in **Users**, coworkers whose details you may not see
show their status only. Click a member to open their details.

## My Profile

Open **My Profile** from the avatar menu (`/app/profile`).

### Update Profile

- **Notes**: a free-text line shown only to you, admins and the people responsible for you: your
  manager, your groups' managers and your delegates. Do not enter classified data or personal
  data (PII). The audit trail records that your notes changed, never what they say.
- **Login Time**: your normal start time. It decides when you are expected in. If you leave it
  empty, your average arrival time is used.
- **Time zone**: the time zone your arrival and departure times are shown in on reports, downloads
  and the public API. Leave it on *My site's time zone* to use your site's.

Choose **Submit**. *Successfully updated profile* appears at the top of the page.

### My Information

Your directory details: organization, employee type, location, branch, email, phone, manager and
average login time. **View My Details Page** opens your details page. If something is wrong, use
the **Help Desk** link to report it. ReadyRoom cannot change directory data.

### Delegates

Lists your delegates: besides your manager, the people who receive your absence alerts and approve
your requests. They can also see your details: your times, notes, **Audit Log** and **Event
Calendar**. Only an administrator can change them; ask yours if they are wrong.

### Calendar

Drag across one or more days, choose a status, then choose **Submit**. With the default settings:

| Option | Stored as | What happens | Excuses an absence alert? |
|---|---|---|---|
| normal | `STANDARD` | Set at once. It clears a status you set earlier | No |
| Early Leave | `EARLY_LEAVE` | Set at once | No |
| Late Arrival | `LATE_ARRIVAL` | Set at once; the time you enter becomes your expected time that day | No |
| Out of Office (needs approval) | `OUT_OF_OFFICE` | Sent as a request | Yes, once approved |
| Work From Home (needs approval) | `WORK_FROM_HOME` | Sent as a request | Yes, once approved |
| TDY (needs approval) | `TDY` | Sent as a request | Yes, once approved |
| Scheduled Leave (needs approval) | `SCHEDULED_LEAVE` | Sent as a request | Yes, once approved |
| Absent Excused (needs approval) | `ABSENT_EXCUSED` | Sent as a request | Yes, once approved |

Which statuses need approval is set by your administrators, so your list may differ. For one
that needs approval, you can add a **note for your manager** (up to 500 characters); it is kept
with the request and shown to whoever decides it. Follow it under **Approvals**.

Excusable statuses appear in blue on calendars and others in purple (the defaults); the days of a
pending request appear in grey, as *Requested: &lt;status&gt;*. Organization holidays and events appear in orange. Setting a status
again for the same day replaces the one you set before. One request or status can cover at most
366 days by default.

## Statuses and approvals

Each status is in one of three groups, and ReadyRoom enforces them on the server, whatever page or
API is used:

| Group | Statuses (default) | Who sets them |
|---|---|---|
| Set by the person | normal, Early Leave, Late Arrival | You, on your own calendar. They take effect at once |
| Requested | Out of Office, Work From Home, TDY, Scheduled Leave, Absent Excused | You ask; your directory manager, one of your delegates or an admin approves or rejects. Until approved, the status is not on your calendar, and absence alerts treat the day as not excused |
| Recorded by managers | Absent, Absent Unexcused, Unscheduled Leave | Only your directory manager, a delegate or an admin, for you (also by answering an absence alert) |

**Approvals** (`/app/approvals`, in the sidebar) has three parts:

- **Waiting for your approval**: requests from people you decide for, with the person, status,
  dates, number of days, their note and when they asked. **Approve** or **Reject**, each with an
  optional comment or reason. Nobody decides their own request, admins included.
- **Ask for a status**: the status, the first and last day, and an optional note; **Send
  request**. You can also ask from your profile calendar.
- **My requests**: your requests, their state (requested, approved, rejected or cancelled), who
  decided and their comment. **Cancel** withdraws a pending request, or an approved one until its
  last day has passed: its days from today on come off your calendar.

An approval writes the status on each day of the request, replacing what you had set there; a
rejection writes nothing.

**Email.** When a mail server is configured, a new request goes to the person's manager and
delegates (*Approval needed: &lt;status&gt; for &lt;name&gt;*), a decision to the person who asked
(*Your &lt;status&gt; request was approved* or *rejected*), and a cancellation to the approvers
(*Cancelled: ...*). The emails carry the status and dates, never the notes.

## My data

**My data** (`/app/privacy`, in the avatar menu) shows what ReadyRoom keeps about you, who sees it
and for how long, and **Download my data**: a JSON file with your profile, the group access
granted to you in ReadyRoom, whose delegate you are, your check-ins, calendar statuses, status
requests (with notes and comments), absence alerts about you, and monthly summaries. Each download
is recorded in the audit trail. The audit trail itself is not in the file; ask an administrator.

## Notifications (absence alerts)

When one of your reports, or someone whose delegate you are, has not checked in by their expected
time plus the grace period on a working day, you receive:

- an **email** titled *Accountability Notification for &lt;name&gt;* with the person, the date,
  the expected check-in time and a **Confirm Status** link, and
- an entry under the **bell** in the top bar.

The notification page shows the summary and a **Status** list with these choices:
`UNSCHEDULED_LEAVE`, `ABSENT_EXCUSED`, `ABSENT_UNEXCUSED` and `ABSENT`. Your answer is recorded:

- on your notification and on every other manager's copy of the same alert, so each copy shows
  who responded and when, and
- as the person's status for that day.

Only managers the alert was sent to can respond. You can change the answer later, and the day's
status is updated to match. Alerts are not raised on weekends, on holidays in the organization
calendar, for anyone already checked in, for anyone with an excusable status that day, or for
anyone with no expected time at all. Each person gets at most one alert per day. A requested
status that is still waiting for approval does not prevent an alert.

## Metrics (admins and managers)

**Metrics** (`/app/metrics`) shows organization-wide figures as **counts only, never names**:

| Section | Shows |
|---|---|
| **Accounted for** | The percentage of expected people who checked in or have a status. "Expected" means anyone active in the last 30 days or given a status for the day. |
| **Status mix** | How the expected people split across statuses. |
| **Agent coverage** | The share of users whose agent reported in the last 7 days, with counts of stopped agents and users with no activity in 90 days. |
| **Current User Statuses** | A pie chart of today's check-ins, check-outs, locks and unlocks against the total number of directory users. |
| **Today's Activity** | Each event type over the day in 15-minute buckets. |
| Breakdown tabs | Status charts by **organization**, **employee type**, **location** and **branch**. |

## Looking at another day

Admins and managers have a **System Date** picker in the top bar. Choose an earlier date (future
dates are not allowed), and every page (Home, groups, reports, metrics) shows that day for the rest
of your session, or until you pick another date. The **Home** heading then reads *Showing
&lt;date&gt;* instead of *Today*.

## Settings (admins and managers)

The **Settings** page manages the organization calendar, the group cache and the org-chart parse
rules. It is described in [Administration](admin-guide.md#settings-page).
