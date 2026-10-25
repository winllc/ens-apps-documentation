# Import and export

Directory pages can export their entries as CSV or LDIF, import CSV or LDIF with a dry run first,
and change a group's members in bulk. Every one of these goes through the same permission checks,
namespace confinement, form validation and audit trail as the entry form.

## Export

On a directory page, **Export CSV** or **Export LDIF** downloads the entries matching the search
(all of them without one).

- Entries are read page by page from the directory, up to `EXPORT_MAX_ENTRIES` (10,000); the
  message says when there were more.
- Only the attributes of the directory's form are exported, never passwords.
- **CSV**: a `dn` column, then one column per field. Several values in one cell are separated by `|`
  (`\|` and `\\` inside a value).
- **Formula guard.** Cells a spreadsheet would run as a formula (`=`, `+`, `-` or `@` first, also
  after leading spaces, tabs or line breaks, or a tab or carriage return first) get a leading `'`,
  which an import removes again. An apostrophe is removed only before such text, so a value that
  really starts with `'=` comes back without its apostrophe.
- A group's page exports its **members** (CSV or LDIF).
- Audited as `data.exported`.
- Both downloads are `POST` requests with the `X-DSP-Request` header
  (`/api/directories/{id}/export` and `/api/directories/{id}/members/export`, parameters in the query
  string), so a link, image or form on another site can't start an audited export.

## Import

**Import** (users with write access to the directory) takes:

- a **CSV** in the export's layout. The `dn` column is optional: without it, entries are named by the
  form's naming attribute in the first create container;
- or **LDIF** entries (`changetype: add` too). Modify, delete and moddn records are refused, and so
  is any file with `:<` on a line other than a comment (a value given as a URL, such as
  `attr:< file:///…` or `http://…`, which would make the server read a file or fetch a URL), and any
  line starting with a tab. Give a value that contains `:<` base64-encoded (`attr:: …`).

**Check file** runs every row without writing and shows what would be created, updated, left
unchanged or refused, with the field messages. **Import** then writes.

- Existing entries get the values given; an empty cell leaves an attribute alone. Other entries are
  created. The import can be limited to only creating or only updating.
- Each entry is audited as `entry.create` or `entry.update`, and the import itself as `data.imported`.
- Limits: `IMPORT_MAX_BYTES` (5 MiB) and `IMPORT_MAX_ROWS` (5,000). Raise the web tier's
  `MAX_BODY_SIZE` together with `IMPORT_MAX_BYTES` (the native package's nginx site keeps
  `client_max_body_size 5m`; edit it there).
- A long import runs as a [background task](user-guide.md#background-tasks).

In the API: `POST /api/directories/{id}/import?format=csv|ldif&mode=create|update|upsert&dryRun=true`
with the file as the body (`text/csv`, `application/ldif` or `text/plain`). `mode` defaults to
`upsert`.

## Bulk membership

On an entry with a member field, **Change members in bulk** adds and removes many members (one per
line) in one audited change, up to `BULK_MAX_MEMBERS` (5,000).

- Sent as value-level adds and deletes with the permissive modify control, never a replace of the
  list, so another change made at the same time is kept, and so is an Active Directory group of more
  than 1,500 members, whose members come back in ranges.
- Saving a group in the entry form, an API update and an import row also change `member`,
  `uniqueMember` and `memberUid` by their difference. They refuse (409 `ranged_values`) to edit a
  member list the directory returned in ranges, which only the bulk change can add to or remove
  from.
- The same is available to integrations: `POST /api/v1/directories/{id}/members?dn=` with
  `{"add": [...], "remove": [...]}` (see the [API reference](api-reference.md#integration-api-apiv1)).

## Settings

| Variable | Property | Default | Description |
|---|---|---|---|
| `EXPORT_MAX_ENTRIES` | `portal.integration.transfer.export-max-entries` | `10000` | The most entries one export holds |
| `IMPORT_MAX_BYTES` | `portal.integration.transfer.import-max-bytes` | `5242880` (5 MiB) | The largest import file |
| `IMPORT_MAX_ROWS` | `portal.integration.transfer.import-max-rows` | `5000` | The most rows one import holds |
| `BULK_MAX_MEMBERS` | `portal.integration.transfer.bulk-max-members` | `5000` | The most members one bulk change adds and removes |
| `MAX_BODY_SIZE` | (web tier) | `5m` | The web tier's upload limit; raise it with `IMPORT_MAX_BYTES` |

Helm: `integrations.transfer.exportMaxEntries`, `.importMaxBytes` (the web tier's limit follows it),
`.importMaxRows` and `.bulkMaxMembers`.

## Not yet

Import as an unattended scheduled job, LDIF modify and delete records, multi-select bulk edit and
delete in the list, clearing attributes by import, and lifting the 1,000-entry cap of the list view.
