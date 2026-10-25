# Exports, saved views and scheduled reports

The **Certificates** page (`/certificates`, in the navigation bar for everybody signed in) lists
every certificate the directory publishes, with who holds it. Its filters drive the table, the
[API](api-reference.md#public-api)'s `/api/v1/certificates`, the CSV and Excel exports, saved views and
scheduled reports alike, so each of them is exactly what is on screen.

## Filters

Text (subject, issuer, serial, fingerprint, names, owner), state, holder (people or servers), expiry
window, issuer, key algorithm, key size, hash algorithm, revocation, and, where licensed, risky
names. In the API the same filters are query parameters, for example
`?status=EXPIRING_SOON&expiresWithinDays=30&issuer=DigiCert&keyAlgorithm=RSA&keySizeMax=2047&hashAlgorithm=SHA-1&ownerType=SERVER&q=web`.

## Export CSV and Export Excel

The two buttons download the filtered inventory without the paging: one row per certificate with its
holder, DN, points of contact, subject, issuer, serial, fingerprint, validity, days left, state, key,
signature and hash algorithms, names, use, revocation and risk flags. In the API:
`POST /api/v1/certificates/export` with `format=csv` or `format=xlsx` and the filters, in the query
string or a form body.

- **A `POST`, because it is audited.** Each export is recorded as `inventory.exported`, with the
  format, the filters and the row count, before a byte is sent. The buttons submit a form carrying
  the CSRF token, so another site cannot make a signed-in browser export. Exporting works in a
  read-only license and in the demo: it changes nothing but the audit trail. From the next
  release an auditor may export too, like the rows the export contains; up to now an auditor is
  refused (`403`).
- **Always a file**: `text/csv` or the Excel type, `Content-Disposition: attachment` and
  `X-Content-Type-Options: nosniff`.
- **Streamed** from a database cursor, so a large export does not sit in memory. Capped at
  `cert-alert.exports.max-rows` (200,000); a capped download carries `X-CertAlert-Truncated: true`.
- **Formula-injection guard.** A text cell that starts with a tab or a carriage return, or whose
  first character after any white space is `=`, `+`, `-` or `@`, is written with a leading
  apostrophe, in CSV and in Excel. Numbers and dates are typed cells.
- **CSV** is UTF-8 with a byte-order mark (so Excel reads it as UTF-8), RFC 4180 quoting, times in
  ISO-8601 UTC.
- **The workbook** has a frozen header row, real date cells (UTC), and an **About** sheet saying
  when, by whom and with which filters it was exported. With the
  [classification banner](configuration.md#classification-banner-cert-alertbanner) on, the marking is
  on the About sheet and in every printed page's header and footer.

## Saved views

**Save view** keeps the current filters under a name, for the person who saved them only
(`/api/v1/saved-views`). Saving under a name already used replaces it. Only a filter is ever kept:
every key and value is checked when it is saved. Auditors, who otherwise change nothing, keep views of
their own.

## Bulk point-of-contact assignment

Tick server certificates in the table and **Add to selected** adds one point of contact to all their
servers at once (`POST /api/v1/servers/contacts/bulk`). Each server is checked under the usual rule
(see [Admin guide → Server contacts](admin-guide.md#server-contacts)): a server the person may not
manage is reported as refused and left alone. Each addition is audited as a single one would be.

## Scheduled reports

The **Scheduled reports** card at the foot of the Certificates page e-mails the current filters'
export as CSV or Excel, daily, weekly (Monday) or monthly (the 1st), at an hour in UTC, to up to 20
addresses (`/api/v1/admin/reports`). Administrators create, pause, send now and delete them;
auditors can see them.

- Every five minutes each replica looks for reports that are due, under the same database lock
  (ShedLock) every scheduled job takes, so one replica sends each report once.
- **Every run checks again** that the report's owner is still an administrator in the live
  directory (entry present, not disabled or locked, administrator by `admin-identifiers` or a
  directory group; an identity provider's group does not count) and that every recipient is inside
  the allowed domains. A run that fails a check sends nothing, and says why on the page and in the
  audit trail.
- The e-mail goes through the [mail outbox](configuration.md#mail-outbox-cert-alertmailoutbox): sent
  after the commit, retried while the mail server is down.
- A report holds at most `cert-alert.exports.report-max-rows` (50,000) rows and 15 MB; the e-mail
  says when it was cut.
- Audited: `report.created`, `report.deleted`, and `report.sent` with the recipients and row count.

!!! warning "Recipient domains are required"
    A report sends the inventory to the addresses typed, so **nothing can be scheduled or sent**
    until `cert-alert.exports.report-recipient-domains` names your own domains (`example.gov`, or
    `*.mil`). Scheduled reports also need a mail server (`spring.mail.host`).

## Settings

| Property (`cert-alert.exports.`) | Env var | Helm | Default | Description |
|---|---|---|---|---|
| `max-rows` | `CERT_ALERT_EXPORTS_MAX_ROWS` | `exports.maxRows` | `200000` | The most rows one download holds |
| `report-max-rows` | `CERT_ALERT_EXPORTS_REPORT_MAX_ROWS` | `exports.reportMaxRows` | `50000` | The most rows a scheduled report's file holds |
| `report-recipient-domains` | `CERT_ALERT_EXPORTS_REPORT_RECIPIENT_DOMAINS` | `exports.reportRecipientDomains` | empty | Domains a scheduled report may be sent to. Required for scheduled reports |
| `max-reports-per-user` | | | `20` | Scheduled reports one administrator may own |
| `report-poll-interval` | | | `5m` | How often replicas look for due reports |
