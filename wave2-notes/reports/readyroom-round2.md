## Summary

RR-07 and RR-12 are both implemented in `/home/user/wt/w2-rr2`, on branch `claude/wave2-approvals-retention`, as 11 commits on top of origin/main f4f3b7f. Nothing was pushed.

The full CI build (`./gradlew build`) passes: 918 tests, 0 failures, 1 skipped. Helm lint and kubeconform pass, and the built jar starts against PostgreSQL 16.

There was no mail outbox on main. I pulled the existing direct-send code out of `NotificationService` into a shared `service/Mailer` and reused that for request mails. It is not durable: a mail lost to an SMTP failure or a restart is only logged.

## Commits

1. `36cb3ff` RR-07: cap the date range of every API that creates events
2. `2c11d55` RR-07: statuses in three classes; excusing ones are requested and approved (V2)
3. `2faab39` RR-07: delegates (alternate managers) are set by administrators only (V3)
4. `0746fcf` RR-12: a coworker visibility policy
5. `9b47fce` RR-12: retention, with monthly summaries for purged periods (V4)
6. `48bb50b` RR-12: self-service export and administrator erasure of a person's data
7. `2419038` Expose the request, privacy and retention settings in the bundle and chart
8. `d1ac715` Help, feature mapping and a DN check for the new statuses and privacy pages
9. `3fa1bfb` RR-07: managers record statuses for their people from the details page
10. `143c85a` Test the profile calendar's status choices
11. `795c6be` Purge a day as a half-open interval; decide who approves before locking

## How it works

**Status classes** (`service/StatusPolicy`), enforced on the server whatever a page offers:
- **Set by the person, takes effect at once:** normal, early leave, late arrival.
- **Requested, needs approval:** every status the existing model marks as excusing an absence — out of office, work from home, TDY, scheduled leave, absent excused. This list is configurable.
- **Admin-only:** absent, absent unexcused, unscheduled leave. Only an admin, or the person's manager or delegate, can record these, and only for someone else.

**Requests** live in a new `status_requests` table. A request goes requested → approved, rejected or cancelled. It only reaches the calendar when approved, so until then the absence alerts treat the day as not excused.
- **Who decides:** the person's directory manager, their delegates and admins. Nobody decides their own, admins included.
- **Cancelling:** the requester (or an admin) can cancel a pending request, or an approved one until its last day has passed. Cancelling an approved one removes its days from today on; past days stay.
- **Where:** the Approvals page `/app/approvals` and the `/api/requests` API. The profile calendar turns an excusing status into a request.
- **Mail:** the approvers are emailed when a request is made or cancelled, and the requester when it is decided. Mails never carry the notes.
- **Audit:** `calendar.request-created`, `-approved`, `-rejected` and `-cancelled`, with ids, dates and states but no notes.
- Managers can also record any status for their people, by dragging on that person's calendar or with `/api/event/update` plus `dn`.

**Delegates:** the self-service endpoint is gone. Admins set delegates on the person's details page or with `POST /api/users/managers/update/{dn}`. Self-chosen delegates are kept but flagged for review. *Administration → Delegates* lists them to keep, change or remove, and startup logs an INFO line with the count while any are waiting. A "request a delegate" flow was not built.

**Retention:** a daily job deletes old check-ins, statuses, requests and absence alerts. Before deleting, it rolls check-ins and statuses into one row per person per month: days checked in, time in, and days per status. It works one day at a time, 500 people per short transaction, and a lock on a single-row state table stops two instances double-counting. Each run is audited as `data.purged` with its counts. Audit events are never touched.

**What reports show for purged periods:** the group report's days show no times or statuses before the cutoff, and a "Before <date>: monthly summaries" table appears instead. Calendars show nothing for purged days. The home page, My Reports and metrics never look back more than 30 days, which is why the minimum retention is 31.

**Visibility** applies to search, a group's or an organization's members, a person's page and the org chart. Each of these views is tested in all three modes.

**Export and erasure:**
- **My data** (`/app/privacy`) lets any user download everything about themselves as JSON. Each download is audited as `data.exported`.
- *Administration → Privacy* lets an admin export anyone's data or erase it in two steps. The first step shows what is held, plus a one-time token valid for 10 minutes. The second step needs that token, the person's name typed again and a browser confirmation. Nobody can erase themselves.
- Erasure is audited as `person.erased` and says whether the person is still in the directory.
- Both work in every license state, like the audit log.
- Profile note changes are now audited as `notesChanged` without the text.

## Test results

- **Full build:** `./gradlew build --offline` succeeded — 918 tests, 0 failed, 1 skipped (`QueryPerformanceMeasurement`, opt-in and skipped on main too). The dependencies were already cached, so `--offline` should match CI's `./gradlew build`.
- **Postgres tests** (Docker was up):
  - `FlywayMigrationTest` (8 tests) now covers V2–V4, including a new test that V3 keeps and flags self-chosen delegates.
  - New: `StatusRequestPostgresTest` (6), `RetentionPostgresTest` (6: summaries, nothing counted twice, a busy day split into batches, midnight boundary, audit counts), `PersonalDataPostgresTest` (4).
- **Unit and web tests:** cover status classes, approvals, delegates, the date cap, startup validation of every new setting, visibility in each mode, CSP and icons.
- **Helm:** `helm lint --strict` and kubeconform (Kubernetes 1.29) pass for all 6 `ci/*.yaml` files. Bad `eventsDays` and `coworkerVisibility` values are refused by the schema.
- **Smoke run:** I started the built jar against postgres:16-alpine.
  - V1–V4 applied, Hibernate validated, liveness UP and `/login` returned 200.
  - The delegate-review INFO line appeared with a count of 2.
  - `APPLICATION_RETENTION_EVENTSDAYS=7` and `APPLICATION_PRIVACY_COWORKERVISIBILITY=partial` each stop startup with a message naming the setting.
- **Not run:** the Docker image build and Trivy scan from CI's image job.

## Behaviour changes for existing installations, and upgrade notes

- **Migrations:** V2, V3 and V4 run automatically at first start. They only change the schema, plus one UPDATE on `user_records`, so they are fast.
- **Status requests:** people can no longer set out of office, work from home, TDY or scheduled leave themselves; the calendar sends them as requests. Absent excused can no longer be set through the API by the person themselves.
  - Statuses already on calendars keep their effect, including future self-set ones; nothing is converted to a request.
  - To let people set one of these without approval again, leave it out of `APPLICATION_EVENTS_REQUESTSTATUSES`. Startup then warns that it excuses absences unapproved.
- **`/api/event/update`:**
  - It now returns `{status, fromDate, toDate, outcome: set|requested, requestId?, message?}` instead of echoing the request.
  - Bad input is answered with 400 and `{error, message}` (`range_too_long`, `invalid_range`, `invalid_status`, `invalid_time`, `invalid_dn`); these used to be 500s.
  - A manager's status on your own calendar gets a 403.
  - Plain `yyyy-MM-dd` dates are now accepted.
- **Date cap:** both `/api/event/update` and the organization calendar refuse ranges over 366 days.
- **Delegates:**
  - `POST /api/users/managers/update` (self) is removed. The profile page now only lists your delegates.
  - Existing delegates keep receiving alerts and can now also approve requests, until an admin reviews them.
  - The delegates column is widened to 2000 characters.
- **Audit:**
  - Profile note changes are recorded as `notesChanged` only. Events from earlier releases still contain the old and new note text; the audit table is append-only, so those stay until audit retention prunes them.
  - New actions: `calendar.request-*`, `data.purged`, `person.erased`, `data.exported` with report `personal-data`, and `person.updated` with via `alternate-managers-reviewed`.
- **Defaults keep today's behaviour:** coworker visibility `full`, retention 0 (keep forever).
- **New menu entries:** Approvals, *My data* in the user menu, and Delegates and Privacy for admins.
- **New metrics:** `ens_readyroom_requests_total{outcome}` and `ens_readyroom_retention_purged_total{kind}`.
- **Licensing:** the privacy pages work even when unlicensed, and erasure works while read-only. The Approvals page and request API are part of the calendar feature.
- **Toasts:** `showToast` now renders its message as text, not HTML.

## New settings

These are in `application.yml`, `env.template` (commented out) and the Helm values with schema checks.

| Setting (env) | Helm value | Default | Meaning |
|---|---|---|---|
| `application.events.max-range-days` (`APPLICATION_EVENTS_MAXRANGEDAYS`) | `events.maxRangeDays` | 366 | Most days one call may set a status, request or org calendar event on. Allowed range 1–3660. |
| `application.events.request-statuses` (`APPLICATION_EVENTS_REQUESTSTATUSES`) | `events.requestStatuses` | `OUT_OF_OFFICE,WORK_FROM_HOME,TDY,SCHEDULED_LEAVE,ABSENT_EXCUSED` | Statuses people request and a manager approves. `STANDARD` is refused at startup. |
| `application.privacy.coworker-visibility` (`APPLICATION_PRIVACY_COWORKERVISIBILITY`) | `privacy.coworkerVisibility` | `full` | `full`: presence and the day's status, as today. `status`: IN or OUT only. `none`: no status, and org-chart present counts hidden except from the ADMIN and MANAGER roles. Managers and admins always see their people in full. |
| `application.retention.events-days` (`APPLICATION_RETENTION_EVENTSDAYS`) | `retention.eventsDays` | 0 (keep forever) | Days to keep check-ins, statuses, requests and alerts. Otherwise at least 31. Older ones are summarised by month, then deleted. |
| `application.retention.cron` (`APPLICATION_RETENTION_CRON`) | `retention.schedule` | `0 17 2 * * *` | When the purge runs (six-field Spring cron, validated at startup). |
| `application.calendar-requested-event-color` | not exposed | `gray` | Calendar colour for days of a pending request. |

## DPIA facts

**What is stored, and where.** Everything below is in ReadyRoom's PostgreSQL database unless stated otherwise.

- **`user_records`** (one per person; the hourly directory sync creates one for every directory user):
  - DN;
  - notes: free text up to 255 characters, entered by the person;
  - preferred login time (user-set) and average login time (computed from the last 30 days);
  - role, favourite groups;
  - delegate DNs and the review flag;
  - organization, employee type, location, branch, duty sub-organization, phone and e-mail, copied from the directory.
- **`permission_records`:** group access granted inside ReadyRoom.
- **`check_in_out_records`:**
  - DN, timestamp and action (in, out, lock, unlock), and whether it was an automatic check-out;
  - Windows user id;
  - the directory attributes at the time of the event;
  - the logoff session id, which is a credential and is never exported.
- **`user_event_records`:** calendar statuses — DN, date, status, late-arrival time, and the request it came from.
- **`status_requests`:** status, dates and late-arrival time; requester note and approver comment (up to 500 characters each, free text that could hold sensitive reasons such as health); state; who decided or cancelled, and when.
- **`notification_records`** (absence alerts): who it is about, the manager it went to, date, expected time, and the response with who gave it and when.
- **`attendance_summaries` and `attendance_summary_statuses`:** per person per month — days checked in, minutes in, days per status.
- **`group_records`:** DNs of people made alternate managers of a group inside ReadyRoom.
- **`audit_events`:**
  - actor DN, name and roles, source IP, target DN and name, action, details;
  - no notes from this release on;
  - copies may also go to stdout, a file, Splunk or syslog if those outputs are configured.
- **`audit_api_tokens`:** created-by and revoked-by DNs.
- **Outside the database:**
  - in-memory browser sessions;
  - an LDAP cache (60 to 120 minutes);
  - application logs, which contain DNs;
  - e-mails: absence alerts carry name, date and expected time; request mails carry name, status and dates but no notes;
  - on workstations: the client's session file and logs;
  - database backups (`backup.sh` or the Helm CronJob, kept 14 days by default).
- **Directory data** (name, mail, phone, groups, manager) is the source of truth and stays in LDAP.

**Who can see what.**
- **The person:** all of their own data, plus the export.
- **Their directory manager, their delegates, and the managers of groups they belong to:** times, notes, history and calendar. Only the directory manager and delegates decide requests.
- **Admins:** everything, including the audit log, export and erasure.
- **The MANAGER role:** org-wide counts on the metrics page (no names), and group reports for groups they manage.
- **Coworkers:** what the visibility setting allows. Never times or notes.
- **Absence alerts:** the recipients and admins.
- **Requests and their notes:** the requester, their approvers and admins.
- **Outside ReadyRoom:** SIEM operators (audit copies) and mail recipients.

**How long.**
- **Check-ins, statuses, requests and alerts:** `events-days`, which by default is forever.
- **Monthly summaries:** forever, until erasure (they have no retention of their own).
- **User records:** forever, until erasure. A person still in the directory gets an empty record back at their next sign-in or the next directory sync.
- **Audit events:** `ENS_AUDIT_RETENTION_DAYS`, forever by default.
- **Backups:** 14 days by default.
- **Logs:** whatever the platform keeps.

**Export.**
- The person: *My data* (`/app/privacy/export`), JSON format `readyroom-personal-data/1`.
- Admins: `/app/admin/privacy/export?dn=`.
- Audit events are not in the export; admins export them from the audit page, filtered by actor or target.

**Erasure** (admin only, `/app/admin/privacy`) is one transaction. It deletes the person's user record, group access, check-ins, statuses, requests, alerts about them or sent to them, and summaries. It also removes them from other people's delegates and from groups' alternate managers.

**Not erased:**
- audit events;
- their DN where it appears in other people's records as the one who decided a request or answered an alert;
- backups until they expire;
- logs, SIEM copies and sent e-mails;
- LDAP.

## Docs edits needed (`docs/readyroom/`)

- **Configuration reference, `env.template` and Helm values pages:** the five new settings.
- **User guide:**
  - requests and approvals: what needs approval and what a pending request means;
  - the Approvals page;
  - *My data* and the export;
  - delegates are now read-only for users;
  - the updated help text.
- **Manager guide:** the approval queue, approving and rejecting, recording a status on a report's calendar, and request e-mails.
- **Admin guide:**
  - the Delegates review page and the startup INFO line;
  - *Administration → Privacy*: retention status, export on someone's behalf, two-step erasure, and the warning about people still in the directory;
  - setting delegates on the details page.
- **Security and roles page:** the coworker visibility modes, who decides requests, nobody approving their own, and admin-only statuses.
- **Retention page:** what is purged and when, the summaries, what reports show for purged periods, and that audit events are excluded.
- **Audit-logging docs:** the new action ids, and `person.updated` now recording `notesChanged` instead of the text.
- **Metrics page:** the two new counters.
- **Licensing page:** the Approvals page and `/api/requests` are part of the calendar feature; export and erasure work in every license state.
- **API reference:** `/api/requests/*`, `GET /api/event/statuses`, the new `/api/event/update` response and errors, `POST /api/users/managers/update/{dn}`, and the removed self-service endpoint.
- **Release and upgrade notes:** the behaviour changes above.
- **DPIA and works-council guide:** the facts above.

## Left undone and known limits

- **Mail is not durable:** there is still no outbox. Request mails go out after commit on a background thread, absence alerts synchronously in their job.
- **No "request a delegate" flow:** delegates are admin-managed only.
- **Summaries never expire:** they stay until a person is erased.
- **Late arrival is still self-set:** a user can enter a very late arrival time and so avoid that day's alert. An operator can close this by adding `LATE_ARRIVAL` (and `EARLY_LEAVE` if wanted) to `request-statuses`.
- **Old audit events with note text** can't be removed: the table is append-only and hash-chained.
- **No per-day view of purged periods:** calendars show nothing for purged days; only the group report shows the monthly summaries.
- **Future self-set excusing statuses** from before the upgrade were not converted to requests.
- **Not run locally:** the Docker image build and Trivy scan.
- **Not edited:** the docs in `docs/readyroom` (they live in the other repo).
