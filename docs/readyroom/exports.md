# Reports, exports and time zones

## Downloads

- **Group report**: *Generate Group Report* on a group's page (managers of the group with the
  `MANAGER` role, and administrators) downloads as **CSV** or **Excel (XLSX)** as well as the PDF: a
  row per person and day, with their status, first check-in, last check-out, minutes in and time
  zone.
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
- Exports cover at most `APPLICATION_REPORTS_MAXRANGEDAYS` (366) days. Days the retention purge has
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
- **Both are checked again at every run**: a schedule whose owner may no longer run it is turned off,
  and recipients who may no longer receive it are skipped. The page shows each schedule's last
  result. Owners and recipients go through the same live directory account check as API token
  owners (see [API reference → Tokens](api-reference.md#tokens)).
- The job runs every five minutes on one replica at a time (ShedLock), while the license includes
  reports and a mail server is configured.
- Attachments over `APPLICATION_REPORTS_MAXATTACHMENTBYTES` (10 MB) are not sent; at most
  `APPLICATION_REPORTS_MAXRECIPIENTS` (20) recipients.
- Audited: `report.schedule-created`, `report.schedule-deleted`, `report.schedule-disabled`, and each
  sending as `data.exported` (`details.via` `scheduled`, the recipients).
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

## Upgrading

Until `APPLICATION_REPORTS_SITETIMEZONES` is set, reports look as before (everyone in
`APPLICATION_TIMEZONE`). Flyway `V10` adds `user_records.time_zone` and `report_schedules`.
