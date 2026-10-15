# Licensing

Every application in the suite runs without a license, but only with its **core** features. A
license file signed by ENS Solutions turns on the **licensed** features it lists. The core of
each application is complete enough to do its main job; the licensed features add automation,
reporting and analysis on top.

## How it works

- A license is a small text file (`*.lic`) naming the product, the licensee, an expiry date and
  the licensed features (`*` means all of them). It is signed with ENS Solutions' Ed25519 key,
  and editing any line invalidates it.
- Each application checks the signature with the public key built into it, and checks that the
  license is for that product.
- If the file is missing, invalid, for another product or expired, the application logs why and
  runs with core features only. It does not refuse to start.
- The file is re-read when it changes, within about 30 seconds, so a renewal takes effect without
  a restart. Expiry is checked continuously: the licensed features switch off after the end of the
  expiry date (UTC).
- A request for a feature that is not licensed gets **403**. API callers get a JSON body naming
  the feature (`"feature": "<id>"`) and the reason `license_required` (in the `error` field, or in
  `code` in Directory Services Portal, whose `error` carries the message it shows), and pages say
  the feature needs a license. Menu items for unlicensed features are hidden, and scheduled jobs that belong to them
  are skipped.

## Installing a license

The production images look for the license at **`/etc/ens/license/license.lic`**. Mount it
read-only:

```bash
docker run -v /srv/ens/license.lic:/etc/ens/license/license.lic:ro ...
# or mount the directory that holds license.lic
docker run -v /srv/ens/license:/etc/ens/license:ro ...
```

With Compose, put the file at `./license/license.lic` next to the compose file; the application
service mounts `./license` there. With Kubernetes or Docker secrets, mount the secret anywhere and
set `ENS_LICENSE_FILE` to its path.

Signed-in users can check the license state at `GET /api/v1/license` (CertAlert),
`GET /api/license` (Directory Services Portal, ReadyRoom) or `GET /license/status` (RSA Operations
Portal); administrators also see a status line in the application.

Outside a container, set `ENS_LICENSE_FILE` (Spring property `ens.license.file`). The default is
`license.lic` in the working directory.

The application logs the license state at startup, for example:

```text
License: licensed to Acme Corp (5f0c...), features [*], expires 2027-12-31
License: no license file at /etc/ens/license/license.lic; running with core features only
```

## Licensed features

### CertAlert (`cert-alert`)

| Feature id | What it unlocks |
|---|---|
| `revocation` | OCSP and CRL revocation checks |
| `tls-probe` | Probing a server's TLS endpoint for the certificate it actually serves |
| `changelog` | Near-real-time sync from the directory changelog |
| `directory-cleanup` | Certificate cleanup and prune, which write back to the directory |
| `email-notifications` | Email digests, email alerts and email delivery of notifications |
| `projects` | Projects and project administrators |
| `metrics` | The metrics dashboard |

Core: directory sweeps and refresh, the People and Servers tables, certificate expiry status,
sign-in, in-app notifications, logging alerts, server attributes, contacts, the audit trail
and the banner.

### Directory Services Portal (`directory-services-portal`)

| Feature id | What it unlocks |
|---|---|
| `analysis` | Data-quality analysis, its findings and CSV export |
| `metrics` | Metrics, the metrics explorer and dashboards |
| `schema-transfer` | Schema import and export |

Core: sign-in (password and X.509), directory browse, search and editing, self-service
profiles, administration of definitions, forms, schema and permissions, the audit log and the
banner.

### ReadyRoom (`readyroom`)

| Feature id | What it unlocks |
|---|---|
| `metrics` | The metrics dashboard |
| `reports` | My Reports and group reports, including PDF export |
| `org-chart` | The org chart |
| `absence-alerts` | Absence email notifications to managers |

Core: the Windows client check-in and check-out API, the home dashboard, profile and calendar,
groups, user search, in-app notifications, settings, and every sign-in method.

### RSA Operations Portal (`rsa-operations-portal`)

| Feature id | What it unlocks |
|---|---|
| `reports` | The overview, site and trusted-agent reports |
| `bulk-upload` | Bulk trusted-agent upload from CSV |
| `group-email` | Group email messages |
| `automation` | The scheduled jobs (expiry and appointment emails, TA re-sync, lost-token monitoring, delayed registration emails, PIN-reset checks) and manual job runs |

Core: sign-in and every token operation (enrollment, testing, PIN resets, lost, deprovisioned and
distributed tokens), user search, appointments, shipments, sites, trusted agents, exemptions,
system notifications, role mapping and the admin page.

## Issuing licenses

Licenses are issued with the single-file
[license tool](https://github.com/winllc/ens-apps-documentation/tree/master/tools/license-tool),
which needs only a JDK:

```bash
java LicenseTool.java sign --key keys/ens-license.private.pem \
    --product readyroom --licensee "Acme Corp" --expires 2027-12-31 --out acme-readyroom.lic
```

A license looks like this:

```properties
# ENS Solutions license. Any edit invalidates the signature.
expires=2027-12-31
features=*
issued=2026-10-15
licenseId=0bb2325b-745c-433b-9db3-8f2abb981e40
licensee=Acme Corp
product=readyroom
signature=BBd3w4TKJe8m...
```
