# Reports, exports and time zones

## Downloads

- **Group report**: *Generate Group Report* on a group's page (managers of the group with the
  `MANAGER` role, and administrators) downloads as **CSV** or **Excel (XLSX)** as well as the PDF: a
  row per person and day, with their status, first check-in, last check-out, minutes in and time
  zone.
- **Check-out** is when the person left, the same on the page, in the downloads and in scheduled
  reports: the day's last check-out (unless they came back after it), or, for an automatic
  check-out, the screen lock before it. While the day is in progress a screen lock is a break, not
  a departure: the page shows *No logoff recorded* and a download leaves the check-out empty. Once
  the day is over, a day that ends with a screen lock ended at that lock (the automatic check-out
  of an evening lock is written after midnight, on the next day).
- **People lists**: a group's members (on its page) and a name search (*Find a user*) download as a
  list of people with each person's status today.

A download holds exactly what its viewer may see on screen: the report's people are those whose
details they may see, and a list shows everyone else as
[`application.privacy.coworker-visibility`](configuration.md#statuses-requests-and-privacy) says
(no times).

- Files are made on the server and streamed as they are read, so a large group does not need the
  memory of the whole report.
- Each download is a form post with the CSRF token, audited as `data.exported` (format, group or
  search, period) before it starts.
- The days picked are taken exactly, as days in ReadyRoom's time zone (`APPLICATION_TIMEZONE`).
  Exports, and the group report page, cover at most `APPLICATION_REPORTS_MAXRANGEDAYS` (366)
  days; a longer range is refused with `400`. Days the retention purge has
  summarised are marked so; the monthly summaries stay on the HTML report.
- **Formula guard**: in CSV, text starting with `=`, `+`, `-`, `@`, a tab or a carriage return (or
  their full-width forms) gets a leading apostrophe; XLSX cells are typed text (inline strings) or
  numbers and never hold a formula. CSV is UTF-8 with a byte-order mark.

## Scheduled reports

*Scheduled reports* (`/app/reports/schedules`, managers and administrators) e-mails a group's
attendance as CSV or Excel, at an hour in its owner's time zone:

| Frequency | Covers | Sent |
|---|---|---|
| Daily | The day before | Every day |
| Weekly | The week before | Monday |
| Monthly | The month before | The 1st |

- Only someone who may run the group's report schedules it, and its recipients (e-mail addresses or
  DNs) must each be able to run it themselves (an administrator, or a manager of the group with the
  manager role). A report never reaches anyone who could not see it in ReadyRoom.
- **Both are checked again at every run**, and recipients who may no longer receive it are
  skipped. The page shows each schedule's last result. Owners and recipients go through the same
  live directory account check as API token owners (see
  [API reference → Tokens](api-reference.md#tokens)).
- **A schedule is turned off** only when its owner may no longer run it: their account is gone,
  disabled, locked or expired, or they lost the right to the group's report. The owner is e-mailed
  that it was turned off and why. **Enable**, on the schedules page, turns it back on once they may
  run the report again (checked then); it next goes out at its next sending time.
- **A directory that cannot be read** puts the run off: the schedule stays on and due, and is tried
  again.
- **A run that reaches nobody** because the directory or the mail server did not answer, or an
  error, is tried again every 30 minutes until its next sending time or
  `APPLICATION_REPORTS_RETRYHOURS` (24) hours after it was due, whichever comes first. Then it is
  given up, and its owner is e-mailed. A report too large to send, or with nobody left who may
  receive it, is not retried and waits for its next sending time.
- **A report covers the period it was due for**, however late it goes out. Runs missed while
  ReadyRoom was down go out as one report covering all of them (at most
  `APPLICATION_REPORTS_MAXRANGEDAYS` days, the latest ones).
- The job runs every five minutes on one replica at a time (ShedLock), while the license includes
  reports and a mail server is configured.
- Attachments over `APPLICATION_REPORTS_MAXATTACHMENTBYTES` (10 MB) are not sent; at most
  `APPLICATION_REPORTS_MAXRECIPIENTS` (20) recipients.
- Audited: `report.schedule-created`, `report.schedule-deleted`, `report.schedule-disabled`,
  `report.schedule-enabled`, and each sending as `data.exported` (`details.via` `scheduled`, the
  recipients).
- Erasing a person deletes their schedules and takes them off others'.

## Time zones

ReadyRoom runs in one zone (`APPLICATION_TIMEZONE`), which the pages, the absence check and the day
boundaries of the metrics still use. **Reports, downloads, scheduled reports and the public API show
each person's times in their own time zone**: the one they chose on their profile (*Time zone*), else
their site's, else the application's. Sites are mapped by the directory's location attribute.

A group report's download can instead show every row in the viewer's own zone (*My time zone*).
Per-site absence checks are not done yet.

## Settings

| Variable | Default | Helm | |
|---|---|---|---|
| `APPLICATION_REPORTS_SITETIMEZONES` | (none) | `reports.siteTimeZones` | `<location>=<IANA zone>`, comma separated, for example `New York=America/New_York,Los Angeles=America/Los_Angeles`; an unknown zone stops startup |
| `APPLICATION_REPORTS_MAXRANGEDAYS` | `366` | `reports.maxRangeDays` | The most days one export covers |
| `APPLICATION_REPORTS_MAXATTACHMENTBYTES` | `10485760` | `reports.maxAttachmentBytes` | A scheduled report's largest attachment |
| `APPLICATION_REPORTS_MAXRECIPIENTS` | `20` | `reports.maxRecipients` | A scheduled report's most recipients |
| `APPLICATION_REPORTS_RETRYHOURS` | `24` | – | How long a scheduled report that reached nobody is tried again, never past its next sending time |

## Upgrading

Until `APPLICATION_REPORTS_SITETIMEZONES` is set, reports look as before (everyone in
`APPLICATION_TIMEZONE`). Flyway `V10` adds `user_records.time_zone` and `report_schedules`.
