# Licensing

From 0.5.0, syncd checks a license. It is licensed by **managed identities** (how many people
it manages) and **packs** (connectors and features beyond Core), in one signed file that it
verifies offline, so licensing works the same on an air-gapped network. Licenses come from the
**ENS licensing portal**, as for the rest of the suite (see [Licensing](../licensing.md)), but
syncd's file, its states and what it enforces are its own, and are described here.

!!! tip "Running sync never stops because of a license"
    No source, export, scheduled run or restart consults the license. An expired license, too
    many identities or no license at all only ever block **publishing configuration changes**,
    and only after warnings and a 30-day grace period. A configuration that was published keeps
    running, across restarts and failovers, whatever the license says.

## Upgrading from 0.4.1: the clock starts

An installation upgraded from 0.4.1 has no license. Nothing changes at the upgrade; what starts
is a clock, from the first start of 0.5.0:

| From the first start of 0.5.0 | What happens |
|---|---|
| Days 0 to 30 | **Evaluation**: everything enabled. The License page and `syncd status` say "no license". A warning from day 23. |
| Days 30 to 60 | **Grace period**: a banner in the console, the `SyncdLicenseGracePeriod` alert and the `license.state-changed` webhook event. Publishing still works. |
| From day 60 | **Publishing configuration changes is blocked** (see [below](#what-publishing-is-blocked-blocks)). Sync keeps running. |

Install a license before day 60. Removing a license does not start a new evaluation.

## Getting a license

1. **Find the installation id.** On the console's **License** page, with
   `syncd-console admin license` (or `sudo syncdctl license status`) on the console's host, or,
   for an engine without a console, in `syncd status` and `syncd license show`. It looks like
   `3f9c2a41-7be05d12-a8c4e6f0-19d2b377`.
2. **Request it from the ENS licensing portal.** An evaluation is one click on your portal
   account; a license you buy is issued into the same account. Enter the installation id to bind
   the license to this installation, or leave it out for a license that moves freely (an HA pair
   of engines without a console has an id per engine: use an unbound license there).
3. **Download the `.lic` file** from **Account → Licenses**. **Check a file** on the portal
   confirms a syncd file is genuine before you install it.

A syncd license is not the ENS license file the other applications read: it is a JSON file in
syncd's own format, signed with ECDSA P-256. The portal writes that format for every syncd
license, and only syncd 0.5.0 or later reads it.

| Portal edition | In the syncd file |
|---|---|
| Evaluation | 30 days, 50,000 managed identities, every pack, shown as "Evaluation license" |
| Pilot | 90 days, your size, every pack |
| Subscription | Your order's term and size; the packs on the order |
| Perpetual | Never expires (the file carries a far expiry date); the packs on the order |

Every change on the portal (a renewal, a binding, a bigger tier) is a new file with a new license
id: install the newest one.

## Installing a license

### With a console (the usual case)

- **Upload** it on the console's **License** page. Administrators with a role on everything can
  open it. The file must verify with the release's keys, be valid now, and be bound to this
  installation or to none. Uploading, replacing and removing are audited (`license.upload`,
  `license.replace`, `license.remove`).
- **Or a file:** set `CONSOLE_LICENSE_FILE` (for example `/etc/syncd/license.lic`). On a host,
  `sudo syncdctl license install FILE` checks the file with this release's keys, installs it as
  `/etc/syncd/license.lic`, sets `CONSOLE_LICENSE_FILE` in `console.env` and restarts the
  console; on Kubernetes, put it in a Secret and name it in
  `console.license.existingSecret`. The console reads the file every status round; the License
  page then shows it and takes no uploads.

The console gives every engine it manages the license and its assessment, after an upload,
before each publish, and whenever an engine shows something else. Each engine verifies the
signature itself and keeps the license in its store, so it starts and runs without the console.
An engine's own `engine.license_file` is ignored once a console manages it.

### Without a console

Set `engine.license_file` in the engine's configuration (a path relative to the configuration's
directory, read every minute; it can't be changed through `ApplyConfig`). `syncd license show`
gives the state, and `syncd license verify FILE [--installation ID]` checks a file offline with
the binary's keys.

## The states

| State | When | Effect |
|---|---|---|
| `licensed` | A license, within its validity period, at most its tier | None |
| `evaluation` | No license, within 30 days of the installation's first start | Everything enabled |
| `warning` | The license expires within 30 days, usage is at 90 % of the tier or more, or the evaluation ends within 7 days | Banner, metric, alert, webhook event |
| `grace` | For 30 days from the earliest of: the expiry, the moment usage went over the tier, the end of the evaluation | As `warning` |
| `blocked` | The grace period is over and the problem remains | Publishing configuration changes is refused |

A new license (a renewal, a bigger tier) ends the problem at once. Usage that drops back under
the tier ends that problem; going over again starts a new grace period. The grace period is
syncd's own 30 days for every edition, whatever the portal shows for the edition.

The console, and an engine on its own, remember the latest time they have seen, so turning the
clock back does not undo an expiry.

### What publishing is blocked blocks

Blocked:

- publishing a version that was never published to that engine (the console's publish,
  promotions, Git auto-publish);
- for an engine without a console, `syncd apply-config` with a text that differs from the
  running one;
- saving or publishing a configuration that uses a connector or feature outside the license's
  packs (at any time, see [Packs](#packs)).

Never blocked:

- sync itself: sources, exports, scheduled runs, restarts, HA failover;
- pausing and resuming, approving or rejecting held changes, dead-letter retries and discards,
  joins and unjoins, password operations, backups;
- **a rollback**: publishing again a version that was published to that engine before. It is
  recorded in the audit trail as "published again as a rollback";
- the configuration file an engine starts with.

## Managed identities

A managed identity is a person syncd manages: an entity **exported to at least one target**,
counted **once** however many targets it reaches. Not counted: groups (group publications and
computed groups), devices and computers, source records that never joined or projected an entity,
and entities no publication exports.

Each engine counts every ten minutes. The console adds up its engines, counting an HA pair once,
and keeps the **high-water mark** and each day's highest count. Export them for true-up from the
License page (CSV or JSON) or `GET /api/v1/license/usage?format=csv|json`; each export is audited
(`license.usage-export`).

## Packs

Core is in every license; the others are named in it. Which connector or feature belongs to which
pack is decided by the syncd release, and may move between releases.

| Pack | Connectors | Features |
|---|---|---|
| Core | Active Directory, LDAP (OpenLDAP, 389 DS, Ping), PostgreSQL, MySQL and MariaDB, CSV files | The rule engine and the console, rule packages, data-quality checks, unmanaged-entry reports, secrets from Vault, AWS or Azure, SAML and X.509 sign-in, scoped roles, drift checks |
| Enterprise Database | SQL Server, MongoDB, Debezium (Oracle, Db2 and others), the JDBC bridge | |
| Cloud and SaaS | Microsoft Entra ID, SCIM 2.0, REST and OData, Workday, SAP SuccessFactors, UKG Pro | Entra ID certificate, federated and managed-identity sign-in |
| Password Sync | | Capture agents, self-service, password delivery, initial passwords, password key and agent CA rotation |
| High Availability | | Active/passive pairs, with the PostgreSQL, file and Kubernetes Lease backends |
| Extensibility | | Custom functions in WebAssembly |

What the license does not include is **locked**: the console's editor marks the connector type
and won't add one, the Studio marks WebAssembly functions, validation names the setting and its
pack, and saving and publishing such a configuration are refused. Without a usable license (the
evaluation and its grace period) nothing is locked. A configuration that already runs keeps
running when a new license leaves a pack out, and may be published again as a rollback; a change
to it needs the pack, or the locked parts removed.

## Watching it

| Where | What |
|---|---|
| Console | The License page; a banner on every page in `warning`, `grace` and `blocked`; usage against the tier on the Engines page |
| `syncd status`, `syncd license show` | State, license, usage and high-water mark, installation id, source, messages |
| Metrics | `syncd_license_state` (0 licensed or evaluating, 1 warning, 2 grace, 3 blocked), `syncd_license_managed_identities`, `syncd_license_managed_identities_limit`, `syncd_license_expiry_seconds`, `syncd_license_development_key` |
| Alerts | `SyncdLicenseWarning`, `SyncdLicenseGracePeriod`, `SyncdLicensePublishingBlocked` (critical), `SyncdLicenseDevelopmentKey` |
| Webhook | `license.state-changed` (see [Webhooks](webhooks.md)) |
| Audit | `license.upload`, `license.replace`, `license.remove`, `license.state`, `license.usage-export` |

## Release builds and the development key

The keys a binary accepts license signatures from are compiled in. Release builds carry the ENS
licensing portal's ECDSA P-256 key. A build from source without it accepts only a
**development key**, whose private half is public in the repository for tests: it says
"DEVELOPMENT LICENSE KEY" in `syncd version`, `syncd status`, the License page and the Engines
page, and raises `SyncdLicenseDevelopmentKey`. Never run such a build in production. `syncd
version` lists the key ids a binary accepts.

In FIPS mode the signature is checked through AWS-LC like any other P-256 signature, so licensing
works the same (see [FIPS 140-3 and DISA STIG](fips-and-stig.md)).

## Known limitations

- syncd's own tests use development licenses. Files issued by the ENS licensing portal were
  checked with syncd's verifier, not installed on a running release.
- An engine without a console that is edited on its host and restarted runs the edited file.
- There is no command to hand licensing back from the console to an engine.
- `syncdctl license` was not run on a packaged host before 0.5.0 was released.
