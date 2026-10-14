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

!!! note "You do not check in on the website"
    Check-in happens on your Windows workstation. The ReadyRoom agent reports when you sign in,
    lock or unlock the screen, and sign out. Your first sign-in or unlock of the day counts as
    arriving.

## The page layout

| Area | What it contains |
|---|---|
| **Banner** (top and bottom) | The text your organization configured, for example a classification marking. |
| **Sidebar** | Home, Groups, Users, My Reports, Org Chart and, for admins and managers, Metrics and Settings. |
| **Bell** (top left) | Your unread notifications. A red dot means there is at least one. Click one to open it, or **Mark All Read** to clear the list. |
| **System Date** (admins and managers) | Shows the site as it stood on an earlier day. See [Looking at another day](#looking-at-another-day). |
| **?** | The "How ReadyRoom works" help dialog. |
| **Avatar menu** | **My Profile**, and **Log out** for password sessions. |

## Statuses

| Status | Meaning |
|---|---|
| `IN` (green) | Checked in, and the latest event was a sign-in or an unlock. |
| `AWAY` (purple) | The latest event was a screen lock. |
| `OUT` (blue) | Checked out: you signed out, or ReadyRoom checked you out after the screen had been locked for too long. |
| `NONE` (grey) | Nothing recorded for the day. |

If you set a status on your calendar for the day (anything other than *Standard*), it is shown
instead, for example `WORK_FROM_HOME`, `TDY` or `LATE_ARRIVAL`.

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

- **Admins and managers** see every group.
- **Users** see only the groups they are a member of in the directory. Parent groups are shown
  so you can reach them, but opening a parent you are not a member of shows *Unauthorized*. If you
  are in no groups, the page says *No groups available*.

Use the search box to filter by name, **Expand All** or **Collapse All**, and the **Favorites only**
switch to show only favorite groups. Each group shows its member count. Click a group to open it.
Admins and managers can star or unstar a group directly in the tree. Other users see the star
without being able to change it.

### A group's page

`/app/users/<group DN>` shows:

- **Members**: a table of each member's name, status, last status change and notes. Each column can
  be filtered. Turn on **Advanced** to also show the DN, check-in time and check-out time. Click a
  row to open that person's details.
- **Favorite**: pins the group so it shows under *Favorites only* on the Groups page.
- **Generate Group Report** (admins and managers): pick a date range, then **Generate Report**. See
  [Group report](#group-report).
- **Group Managers** (admins and managers): see [Administration](admin-guide.md#group-managers).

!!! note
    Saving a favorite is limited to the `MANAGER` and `ADMIN` roles. For a user with only the `USER`
    role, the server refuses the change.

### Group report

The report page shows the group name, the date range and the number of users, then:

- **Day Reports**: for each day, the number of check-ins and check-outs, and counts by employee
  type, location and organization.
- **User Reports**: for each member, each day's status, check-in time, check-out time and how long
  they were checked in.

**Download PDF** saves the report as `group_report.pdf`.

## Users

**Users** (`/app/user/search`) finds anyone in the directory. Type at least three characters of a
name and choose **Search**. The search matches any part of the common name. The results table has
the same columns as a group's member list, including the **Advanced** switch. Click a row to open
that person's details.

## A person's details

`/app/user/details/<DN>` shows a person's **User Information**: status, organization, employee
type, location, branch, email, phone, manager, average login time and notes. **Member Of** lists
their directory groups, each linking to the group page.

You also see the following if it is your own page, you are an admin or manager, or you manage one
of the person's groups:

- **Audit Log**: every check-in, lock, unlock and check-out recorded for them, newest first.
- **Event Calendar**: their statuses, their online periods ("Online: ..."), and organization events
  and holidays.

Admins also see a **Role** card. See [Administration](admin-guide.md#roles).

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

## Org Chart

**Org Chart** (`/app/orgchart`) shows the organization hierarchy built from an attribute in the
directory. Each organization shows **present / total**, counting sub-organizations, and a
percentage. Filter by name, expand or collapse the tree, and click an organization to list its
members, with the usual **Advanced** switch. Click a member to open their details.

## My Profile

Open **My Profile** from the avatar menu (`/app/profile`).

### Update Profile

- **Notes**: a free-text line shown to anyone who looks you up. Do not enter classified data or
  personal data (PII).
- **Login Time**: your normal start time. It decides when you are expected in. If you leave it
  empty, your average arrival time is used.

Choose **Submit**. *Successfully updated profile* appears at the top of the page.

### My Information

Your directory details: organization, employee type, location, branch, email, phone, manager and
average login time. **View My Details Page** opens your details page. If something is wrong, use
the **Help Desk** link to report it. ReadyRoom cannot change directory data.

### Additional Managers

Search for people by name and add them to the list, then choose **Update**. Additional managers
are notified when you are not in on time, along with your directory manager.

### Calendar

Drag across one or more days to set your status for that period, then choose **Submit**:

| Option | Stored as | Excuses an absence alert? |
|---|---|---|
| Standard | `STANDARD` | No. It clears a status you set earlier. |
| Out of Office | `OUT_OF_OFFICE` | Yes |
| WFH | `WORK_FROM_HOME` | Yes |
| TDY | `TDY` | Yes |
| Scheduled Leave | `SCHEDULED_LEAVE` | Yes |
| Early Leave | `EARLY_LEAVE` | No |
| Late Arrival | `LATE_ARRIVAL` | No, but the time you enter becomes your expected time that day |

Excusable statuses appear in blue on calendars and others in purple (the defaults). Organization
holidays and events appear in orange. Setting a status again for the same day replaces the one you
set before.

## Notifications (absence alerts)

When one of your reports, or someone who added you as an additional manager, has not checked in by
their expected time plus the grace period on a working day, you receive:

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
anyone with no expected time at all. Each person gets at most one alert per day.

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
