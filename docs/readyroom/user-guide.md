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
| **Sidebar** | Home, Groups, Users, My Reports, Approvals, Org Chart and, for admins and managers, Metrics. Admins also see Settings, License, Delegates, Privacy and Audit Log. |
| **Bell** (top left) | Your absence alerts that still await an answer, unread ones in bold. A red dot means at least one is unread. Click one to open it. **Mark All Read** clears the dot; the alerts stay listed until someone answers them. |
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

**Accounted for.** A day counts as accounted for (on Home, My Reports and Metrics), as covered by a
status (in the 30-day attendance) and as needing no absence alert only when you checked in, or the
day carries a status that excuses an absence (leave, Work From Home, TDY and so on) or one only a
manager records (*Absent*, *Absent Unexcused*, *Unscheduled Leave*). *Late Arrival* and *Early
Leave* are your own word: they are shown on calendars, in the user tables and the API, but without
a check-in the day is still not accounted for.

## Home

**Home** (`/app/home`) is your own day. The top line shows the date being viewed.

| Card | Shows |
|---|---|
| **My status** | Your current status and when it started, the time you checked in and out, the time you are **expected in by** with the rule it came from, and your **average arrival**. |
| **Last 30 days** | Working days you checked in, days covered only by a status (one that accounts for the day, see [Statuses](#statuses); not *Late Arrival* or *Early Leave*), and days with nothing recorded, as counts and a bar. |
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
| **Accounted for** | The percentage of your reports who are checked in or have a status that accounts for the day (see [Statuses](#statuses)), and the mix of statuses. On weekends and holidays it says why nobody is expected. |
| **Not in yet** | Reports who are not accounted for, with their expected time: the time of a *Late Arrival* they set for the day, else their login time or average arrival. A **Late** badge (**Missed** when you view a past day) appears once they are past it. |
| **Awaiting your response** | Absence alerts nobody has answered yet, with a **New** badge on those you have not read. Marking them read in the bell does not remove them. Click one to respond. |
| **Team agents** | How many reports' agents reported in the last 7 days, and who stopped reporting. |
| **Team coming up** | Your reports' statuses for the next 14 days. |

If the directory cannot be read, Home says *The directory could not be read, so your team is not
shown* instead of leaving the team section out. Try again in a few minutes.

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
- **Group Managers** (admins only): see [Administration](admin-guide.md#group-managers).
- **Calendar**: a month calendar combining the statuses each member you may see has set (leave,
  telework and so on), each named and linked to the person.
- **CSV** and **Excel** download the member list with each person's status on the day the page
  shows (the **System Date** you picked, else today). The same buttons are on the results of
  *Find a user*.

!!! note
    Saving a favorite is limited to the `MANAGER` and `ADMIN` roles. For a user with only the `USER`
    role, the server refuses the change.

### Group report

The report page shows the group name, the date range and the number of users, then:

- **Day Reports**: for each day, the number of check-ins and check-outs, and counts by employee
  type, location and organization.
- **User Reports**: for each member, each day's status, check-in time, check-out time and how long
  they were checked in.

The dates you pick are whole days in ReadyRoom's time zone, at most 366 of them by default. The
check-out time is when the person left: their last check-out of the day, or, for an automatic
check-out, the screen lock before it. While the day is still in progress a screen lock is taken as
a break, and a person who has not checked out shows *No logoff recorded*. Once the day is over, a
day that ended with a screen lock ended at that lock. The downloads and scheduled reports count
departures the same way.

**Download PDF** saves the report as `group_report.pdf`. **CSV** and **Excel** download a row per
person and day (status, first check-in, last check-out, minutes in and time zone), with each person's
times in their own time zone, or choose **My time zone** to show every row in yours. See
[Reports, exports and time zones](exports.md).

If the directory does not answer while the report is made, the page says *The directory could not
be read* instead of showing a report: the group's members come from the directory. Try again in a
few minutes, and if it keeps happening ask your administrator to check ReadyRoom's directory
connection. The report's CSV and Excel downloads are refused the same way, rather than giving a
file with no members.

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
*Absent Unexcused* and *Unscheduled Leave*. A *Late Arrival* you record for someone else has no
latest time and no limit on the number of days.

Admins also see a **Role** card, with **Export or erase data**. The role list offers `USER` and
`MANAGER`; `ADMIN` is never given there. See [Administration](admin-guide.md#roles).

## My Reports

**My Reports** (`/app/user/reports`) lists everyone who reports directly to you in the directory.
If nobody does, the page says *No direct reports*. If the directory cannot be read, it says *The
directory could not be read* instead: who reports to you is then not known, which is not the same
as nobody. Try again in a few minutes.

| Section | Shows |
|---|---|
| **Accounted for today** | Percentage and count of reports accounted for (checked in, or a status that accounts for the day; see [Statuses](#statuses)). |
| **Attendance, last 30 days** | Share of working days checked in, days covered by a status (not *Late Arrival* or *Early Leave*), and days with nothing recorded. |
| **Average arrival, last 30 days** | The team's average first check-in on working days. |
| **Absence alerts, last 30 days** | Alerts raised, answered and unanswered, with how they were answered. |
| **Daily attendance, last 30 days** | A chart of each day's counts, with **Show as a table**. |
| **Status today** | Every report's current status, organization and employee type. **Advanced** adds the DN and check-in and check-out times. |
| **Attendance by report, last 30 days** | For each report: days checked in, status-only days, days with nothing recorded, average arrival, and when their agent last reported. A **Quiet** badge means nothing was received for a week or more. |
| **Calendar** | A month calendar combining the statuses your reports have set, each named and linked to the person. |

## Org Chart

**Org Chart** (`/app/orgchart`) shows the organization hierarchy built from an attribute in the
directory. Above the tree, the whole organization's **accounted for** figure for the day being viewed
(today, or the **System Date** an admin or manager picked), a bar of who is in which status, and the legend for
it. Each row shows the organization's own name with its full directory value beside it, the same bar
for that organization and everything under it, **accounted of total**, and a percentage.

Each person is counted the way you may see them elsewhere. People whose details you may see
(yourself, your reports, the people you are a delegate of and the members of groups you manage;
everyone, for admins) are counted the way **Users** and **Home** count them: a status on the
person's calendar for the day that accounts for it (work from home, TDY, leave, or one a manager
recorded) comes first; otherwise their latest record decides between **Checked in**, **Away**
(screen locked) and **Checked out**; anyone with neither is **No record**. *Late Arrival*, *Early
Leave* and *normal* do not stand in for being there: such a person is counted by their presence,
and without a check-in is **No record**. Everyone else is counted as a coworker sees them, which depends on
your organization's coworker visibility setting:

- *full* (the default): the same, except that a status a manager recorded counts as *Out of Office*;
- *status*: **Checked in** (a locked screen included) or **Not in**, with no calendar status;
- *none*: only admins and managers see the counts, with these people as **Checked in** or **Not
  in**; everyone else sees headcounts without presence.

*Accounted for* is everyone but *No record* and *Not in*.

Who belongs to which organization: someone with a check-in record that day carrying a duty
sub-organization is counted under the value on their latest such record, wherever the directory lists
them, so a person working in another organization that day counts there and someone who moved during the
day counts under the later one. Everyone else is counted where the directory lists them (every entry
with the attribute, narrowed only by the duty sub-org filter). Nobody is counted twice, and someone whose
record names an organization that is not on the chart is not counted.

Filter by name, expand or collapse the tree, and click an organization to list the people counted in it, with the
usual **Advanced** switch. As in **Users**, coworkers whose details you may not see show their status
only. Click a member to open their details.

## My Profile

Open **My Profile** from the avatar menu (`/app/profile`).

### Update Profile

- **Notes**: a free-text line shown only to you, admins and the people responsible for you: your
  manager, your groups' managers and your delegates. Do not enter classified data or personal
  data (PII). The audit trail records that your notes changed, never what they say.
- **Login Time**: your normal start time. It decides when you are expected in. If you leave it
  empty, your average arrival time is used. It can be at the latest 12:00, the same limit as a
  late arrival you set yourself (your organization may have changed it).
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
| normal | `STANDARD` | Set at once. It clears a status you set earlier. Not for a day already past: ask your manager to correct it | No |
| Early Leave | `EARLY_LEAVE` | Set at once for today and later days; for days that include an earlier one, sent as a request | No |
| Late Arrival | `LATE_ARRIVAL` | Set at once for today and later days; the time you enter becomes your expected time that day. For days that include an earlier one, sent as a request. At the latest 12:00, and at most 31 days at a time: for a later time ask your manager to record it; for a lasting change set your **Login Time** | No |
| Out of Office (needs approval) | `OUT_OF_OFFICE` | Sent as a request | Yes, once approved |
| Work From Home (needs approval) | `WORK_FROM_HOME` | Sent as a request | Yes, once approved |
| TDY (needs approval) | `TDY` | Sent as a request | Yes, once approved |
| Scheduled Leave (needs approval) | `SCHEDULED_LEAVE` | Sent as a request | Yes, once approved |
| Absent Excused (needs approval) | `ABSENT_EXCUSED` | Sent as a request | Yes, once approved |

Which statuses need approval, and the late arrival limits, are set by your administrators, so
yours may differ. For one
that needs approval, you can add a **note for your manager** (up to 500 characters); it is kept
with the request and shown to whoever decides it. Follow it under **Approvals**.

**Days already past.** "Today" is the date in ReadyRoom's time zone; the **System Date** does not
move it. A *Late Arrival* or *Early Leave* for a selection that includes an earlier day becomes one
request for the whole selection, today and later days included: your manager is e-mailed and it
reaches your calendar, with its time, only once approved. Choosing *normal* for such a selection is
refused: a past day is your manager's to correct.

**Days an approved request covers.** Such a day is the request's. Setting *normal*, *Early Leave*
or *Late Arrival* on it is refused, with a message naming the request: cancel or change the request
instead (under **Approvals**), which tells your approvers. Nor can you ask for days that a pending or
approved request of yours already covers: cancel that one first, or ask only for the days it does
not cover.

Excusable statuses appear in blue on calendars and others in purple (the defaults); the days of a
pending request appear in grey, as *Requested: &lt;status&gt;*. Organization holidays and events appear in orange. Setting a status
again for the same day replaces the one you set before. One request or status can cover at most
366 days by default.

## Statuses and approvals

Each status is in one of three groups, and ReadyRoom enforces them on the server, whatever page or
API is used:

| Group | Statuses (default) | Who sets them |
|---|---|---|
| Set by the person | normal, Early Leave, Late Arrival | You, on your own calendar, for today and later days. They take effect at once and excuse nothing. A Late Arrival or Early Leave reaching an earlier day is requested instead (see [Days already past](#calendar)); normal there is refused, and so is any of them on a day an approved request of yours covers |
| Requested | Out of Office, Work From Home, TDY, Scheduled Leave, Absent Excused | You ask; your directory manager, one of your delegates or an admin approves or rejects. Until approved, the status is not on your calendar, and absence alerts treat the day as not excused |
| Recorded by managers | Absent, Absent Unexcused, Unscheduled Leave | Only your directory manager, a delegate or an admin, for you (also by answering an absence alert) |

**Approvals** (`/app/approvals`, in the sidebar) has three parts:

- **Waiting for your approval**: requests from people you decide for, with the person, status
  (a late arrival with its time, for example *Late Arrival by 09:30*), dates, number of days, their
  note and when they asked. **Approve** or **Reject**, each with an
  optional comment or reason. Nobody decides their own request, admins included.
- **Ask for a status**: the status, the first and last day, and an optional note; **Send
  request**. You can also ask from your profile calendar. A request over a day that a pending or
  approved request of yours already covers is refused: cancel that one first, or ask only for the
  other days.
- **My requests**: your requests, their state (requested, approved, rejected or cancelled), who
  decided and their comment. **Cancel** withdraws a pending request, or an approved one until its
  last day has passed: its days from today on come off your calendar. Should another approved
  request of yours still cover one of those days (possible only for requests made before
  overlapping requests were refused), that request's status goes back on the day.

An approval writes the status on each day of the request, replacing what you had set there; a
rejection writes nothing. A request that overlaps another approved request of the same person
cannot be approved until that one is cancelled. Approving a request also answers the absence
alerts raised about the person on its days: each alert takes the approved status as its answer,
with the approver as who answered (see [Notifications](#notifications-absence-alerts)).

**When a day holds two statuses.** A day can hold your own status beside one your manager, a
delegate or an administrator recorded (say *Late Arrival* you set after your manager recorded
*Absent Unexcused*). One rule decides which counts, on every page, report, export and metric: a
status a manager, delegate or administrator recorded wins over one an approved request put there,
which wins over your own; between two of the same kind, the latest wins.

**Email.** When a mail server is configured, a new request goes to the person's manager and
delegates (*Approval needed: &lt;status&gt; for &lt;name&gt;*), a decision to the person who asked
(*Your &lt;status&gt; request was approved* or *rejected*), and a cancellation to the approvers
(*Cancelled: ...*). The emails carry the status and dates, never the notes.

**No manager or delegate.** Someone with no manager in the directory and no delegate can still ask.
They are told *No manager or delegate is set for you in ReadyRoom: an administrator has been
asked*, and the request goes to the administrators (other than the person asking), whose email says
it comes to them as administrators. Anyone else is told *your manager or delegate has been asked*.

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

If the mail server did not take the email, the next run of the alert job sends it again, the same
day, while nobody has answered the alert.

**Mark All Read** in the bell marks your alerts read and clears the red dot. It answers nothing: the
alerts stay in the bell and under **Awaiting your response** on Home until someone responds.

The notification page shows the summary and a **Status** list with these choices:
`UNSCHEDULED_LEAVE`, `ABSENT_EXCUSED`, `ABSENT_UNEXCUSED` and `ABSENT`. Your answer is recorded:

- on your notification and on every other manager's copy of the same alert, so each copy shows
  who responded and when, and
- as the person's status for that day.

Only managers the alert was sent to, while they still decide for the person, and administrators
can respond. You can change the answer later, and the day's status is updated to match, for 14 days
by default (`application.events.alert-answer-days`); after that only an administrator can answer
it. An alert whose day an approved request covers has already been answered by the request: it shows
**Answered By Request**, and nobody can answer it on its own, administrators included, until the
request is cancelled. When you cannot answer an alert, its page says why. Alerts are not raised on weekends, on holidays in the organization
calendar, for anyone already checked in, for anyone with an excusable status that day, for anyone
whose day already carries a status a manager, delegate or administrator recorded (*Absent*,
*Absent Unexcused* or *Unscheduled Leave*), for anyone who has left the directory or whose account
is disabled or expired, or for anyone with no expected time at all. Each person gets at most one
alert per day, and you are never alerted about yourself. A requested status that is still waiting
for approval does not prevent an alert, and neither does *Late Arrival* or *Early Leave*: a late
arrival only moves the expected time to the one entered. If someone's expected time plus the grace period falls
after midnight, the alert still comes that day, shortly before midnight.

## Metrics (admins and managers)

**Metrics** (`/app/metrics`) shows organization-wide figures as **counts only, never names**:

| Section | Shows |
|---|---|
| **Accounted for** | The percentage of expected people who checked in or have a status that accounts for the day (see [Statuses](#statuses)). "Expected" means anyone active in the last 30 days or given such a status for the day. |
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

## Settings (admins)

The **Settings** page manages the organization calendar, the group cache, the check of stored group
DNs and the org-chart parse rules. It is organization-wide configuration, for administrators only.
It is described in [Administration](admin-guide.md#settings-page).
