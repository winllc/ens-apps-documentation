# Administration

This page is for people who manage ReadyRoom from inside the application: assigning roles, setting
up who manages whom, keeping the organization calendar, and shaping the org chart. For running the
server itself, see [Operations](operator-guide.md).

## Roles

ReadyRoom has three roles (`UserRoleEnum`):

| Role | How someone gets it |
|---|---|
| `USER` | Automatically, for anyone whose sign-in resolves to a directory entry. Every signed-in person has it, including managers and admins. |
| `MANAGER` | An admin sets it on the person's details page. It is stored in `user_records.user_role`. |
| `ADMIN` | The person's DN is listed in `application.super-user-dns` (matched without regard to case). The role list in the UI does not offer `ADMIN`. The application also honors `ADMIN` if it is stored in `user_records.user_role`, which can only be set directly in the database. |

A user record is created automatically the first time someone signs in, and also by the hourly
directory refresh, with the role `USER`. A certificate or account whose DN cannot be found in the
directory does not sign in at all.

### Assigning a role

1. Open **Users**, search for the person, and open their details page.
2. In the **Role** card (shown to admins only), choose `USER` or `MANAGER`.
3. Choose **Submit**.

Roles are read when a person signs in, so a change takes effect at their next session. Changing
the stored role of someone listed in `super-user-dns` does not remove their `ADMIN` role.

!!! tip "Role versus reporting line"
    "Manager" means two different things in ReadyRoom:

    - The **`MANAGER` role** opens the manager pages: Metrics, Settings, group reports and the
      **System Date** picker. It does not by itself show every group or every person's data.
    - A **responsibility for people** gives you their details (times, notes, **Audit Log** and
      **Event Calendar**), whatever your role: a **reporting line** in the directory (someone's
      `user-ldap-manager-id-attribute` pointing at you), managing a **group** they are in, or being
      named as an additional manager on their profile. A reporting line also gives you
      **My Reports**, the team cards on **Home**, and absence alerts for those people.

    Give the `MANAGER` role only to people who need the manager pages. See
    [Security → Authorization](security.md#authorization) for the full rules.

## What each role can do

| Capability | USER | MANAGER | ADMIN |
|---|:-:|:-:|:-:|
| Home, own status, last 30 days, own calendar and profile | ✓ | ✓ | ✓ |
| Team cards on Home and **My Reports** (needs direct reports in the directory) | ✓ | ✓ | ✓ |
| **Users** search and anyone's basic details page | ✓ | ✓ | ✓ |
| **Org Chart** | ✓ | ✓ | ✓ |
| **Groups**: groups the person is a member of in the directory, and groups they manage | ✓ | ✓ | – |
| **Groups**: every configured group tree | – | – | ✓ |
| Star (favorite) groups | – | ✓ | ✓ |
| Other people's times and notes, **Audit Log** and **Event Calendar** | only people they are responsible for | only people they are responsible for | everyone |
| Generate a **group report** | – | groups they manage | ✓ |
| Edit a group's **additional managers** | – | groups they manage | ✓ |
| **Metrics** | – | ✓ | ✓ |
| **Settings**: calendar, group cache, parse rules | – | ✓ | ✓ |
| **System Date** picker (view another day) | – | ✓ | ✓ |
| Change someone's **role** | – | – | ✓ |
| Respond to an absence alert | only alerts sent to them | only alerts sent to them | only alerts sent to them |

Pages are protected on the server with `@PreAuthorize` rules. The sidebar hides what a role cannot
open, and a page opened directly without the required role shows *Unauthorized*.

## Groups and access

Group trees come from the directory: each entry under every `application.groups[].groups-base-dn`
whose object class is `groupOfUniqueNames`. Members are its `uniqueMember` values, and its
directory manager is its `owner` attribute. ReadyRoom never writes to the directory.

**Who can open a group** (`/app/users/<group DN>`) is decided by `AccessPolicy.canViewGroup()`:
`ADMIN` can open any group, and anyone else can open a group they are a member of in the directory
or manage. Group memberships are cached per user for
`application.ldap.group-membership-cache-seconds` (300 s by default), so a directory change can take
that long to apply. On a group's page, members whose details you may not see show only their
directory fields and whether they are in.

!!! note "Per-group permission records"
    The database has a `permission_records` table, and `POST /api/permissions/update` (admins, and
    managers of that group) grants or revokes a group for a user. No screen in the current UI calls this
    endpoint, and in the current version the access checks for **Groups** and group pages read only
    directory membership, not these records. To give someone access to a group, add them to it in
    the directory.

### Group managers

On a group's page, admins, and people with the `MANAGER` role who manage that group, see a
**Group Managers** card:

1. Type at least part of a name in **Add a manager** and pick people from the results.
2. Remove anyone with the remove control next to their name.
3. Choose **Update**. The list is saved in `group_records.alternate_managers`.

A person counts as a **manager of a group** if they are its directory `owner` or are on this list.
A group manager can open the group and sees the details of every member of every group they manage
(times, notes, **Audit Log** and **Event Calendar**), even with only the `USER` role. Changing the
list and running the group's report also need the `MANAGER` role. A sub-group is a separate group
with its own managers.

!!! warning "Group managers and absence alerts"
    The card says that these managers receive absence notifications. In the current code, absence
    alerts go only to the person's **directory manager** and the **additional managers** the person
    chose on their own profile, not to group managers. If a group's managers must be alerted, ask
    the members to add them under *Additional Managers* on **My Profile**.

### Favorites

Admins and managers can star groups on the **Groups** page or with the **Favorite** switch on a
group's page. Favorites are stored per person in `user_records.favorite_groups` and drive the
*Favorites only* filter.

## Absence notifications

Absence alerts are produced by a scheduled job. By default it runs every 30 minutes; the packaged
configuration runs it every 5 minutes.

On each working day (not Saturday or Sunday, and not a date marked **Holiday** in the organization
calendar), for every user record, a notification is raised when **all** of these are true:

- the person has no check-in today;
- they have not already been alerted about today;
- an expected time is known: today's *Late Arrival* time, else their preferred **Login Time**, else
  their average arrival;
- the current time is past that expected time plus
  `application.extra-time-before-absent-notification-minutes` (default 60);
- they have no excusable status today (*Out of Office*, *Work From Home*, *TDY*, *Scheduled Leave*
  or *Absent Excused*).

**Recipients** are the person's directory manager (found through the reporting-line attributes)
and the additional managers they chose on their profile. Each recipient gets a notification record
and, if their directory entry has a valid email address, an email from
`application.notification-sender-email` linking to `application.application-base-url` +
`/app/notifications/id/<id>`. A recipient who is not found in the directory, or has no email
address, is logged as an error.

**Responses.** A recipient opens the alert and picks `UNSCHEDULED_LEAVE`, `ABSENT_EXCUSED`,
`ABSENT_UNEXCUSED` or `ABSENT`. The response is copied to every recipient's copy of the alert and
stored as the person's status for that day.

To set up notifications:

1. Configure SMTP and the two `application.*` mail settings (see
   [Configuration](configuration.md#email-and-notifications)).
2. Make sure the reporting-line attributes are mapped correctly
   (`user-ldap-manager-id-attribute` and `manager-ldap-id-attribute`). A person's **Manager** field
   on their details page shows the result.
3. Make sure managers have an email address in the directory attribute set by
   `user-ldap-email-attribute` (default `mail`).
4. Add your organization's holidays to the calendar (below).

## Settings page

**Settings** (`/app/settings`) is available to admins and managers.

### Organization calendar

- **Add an event:** drag across one or more days. In the dialog, enter a **Title**, tick
  **Holiday** if nobody is expected that day, and choose **Submit**. One event is stored per day.
- **Delete an event:** click it and confirm.

Calendar events appear in orange on everyone's calendars. Events marked **Holiday** also stop
absence alerts and make metrics report the day as *Not a working day* with the event's title.

### Group cache

Group trees are read from the directory and cached, so a directory change does not show until the
cached entry is refreshed (see `cache-duration-refresh-minutes` and
`cache-duration-expiration-minutes` in [Configuration](configuration.md#directory-caches)). The
card shows how many group entries are cached.

- **Evict** a single DN: removes that group, the groups beneath it, and the cached trees that
  contain it. The next request reads them from the directory again.
- **Evict All** (with confirmation): clears every cached group tree. Pages are slower until the
  caches fill again.

### Organization parse rules

The org chart is built from the distinct values of one directory attribute
(`user-ldap-duty-sub-organization-attribute`, for example `departmentNumber`). By default each
value is split wherever letters change to digits or digits to letters, and trees that share a
prefix are merged:

```
RYS34B  ->  RYS
             └── 34
                  └── B
```

When that does not fit your naming, add a rule:

| Field | Meaning |
|---|---|
| **Organization Name** | A prefix. The first rule whose name the value starts with (ignoring case) is used. |
| **Parse Regex** | A Java regular expression that must match the **whole** value. Each capturing group becomes one level of the tree. |

For example, with the name `RYS` and the regex `([a-zA-Z]+\d)(\d+)([a-zA-Z]+)$`, the value
`RYS34B` becomes `RYS3` → `4` → `B`.

- An invalid regex is rejected when you save it.
- Use the edit control to load a rule back into the form. Delete asks for confirmation.
- A value that matches a rule's prefix but **not** its regex is left out of the org chart, and a
  warning is logged.
- The org chart is cached, and saving a rule does not clear the cache. A new rule applies when
  the chart is rebuilt: in the background once the cached chart is older than
  `cache-duration-refresh-minutes` (60 by default), at the latest after
  `cache-duration-expiration-minutes` (120), or after a restart.

## Banner and branding

The banner at the top and bottom of every page shows `application.banner-text` on
`application.banner-color`. The sidebar logo is `application.logofile`. These are server settings;
see [Configuration](configuration.md#user-interface).
