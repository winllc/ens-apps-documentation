# Admin guide

This guide is for **console administrators**: people holding the `admin` role in the syncd web
console (`syncd-console`). It covers what an administrator does in the console and with the
`syncd-console admin` commands: accounts and roles, sign-in policy, configuration approval,
banners, engine registration and the audit log. Installing, upgrading and backing up are in the
[Install guide](install.md) and the [Operator guide](operator-guide.md); the screens designers and
operators use every day are in the [User guide](user-guide.md).

## Becoming an administrator

The console keeps its own accounts in its PostgreSQL database. There are two ways to hold the
`admin` role:

| Account | How it gets `admin` |
|---|---|
| The built-in `admin` account | Created the first time the console starts on an empty database. It is the only account until an administrator makes others |
| A local account | An administrator creates it, or changes its role, on the **Users** page |
| A single sign-on account | The identity provider's group claim maps to `admin` in `CONSOLE_OIDC_ROLE_MAP` (or `CONSOLE_OIDC_DEFAULT_ROLE` is `admin`). The role is set again at every sign-in. See [Single sign-on](single-sign-on.md) |

### The first administrator

When the console first starts on an empty database it creates `admin` (display name
**Administrator**) with a one-time password:

| Mode | Initial password |
|---|---|
| Production, nothing supplied | A random 24-character password, printed once to the console's log and written to `$CONSOLE_DATA_DIR/initial-admin-password` (mode 0600). `syncdctl install` prints it at the end of the install |
| Production, supplied by the installer | `CONSOLE_ADMIN_PASSWORD` or `CONSOLE_ADMIN_PASSWORD_FILE`. An empty password, `admin`, or one that fails the password rules stops the console from starting |
| Development mode (`SYNC_DEV_MODE=true`) | `admin` / `admin`, with a permanent warning banner on every page |

The first sign-in must set a new password; the initial password file is then deleted (recorded
in the audit log as `bootstrap.password_file_removed`). Creating the account is recorded as
`user.bootstrap`, with whether the password was generated, supplied or the development default.

You can tell your role from the header, which shows your name and role (for example
`Administrator · admin`). Administrators see **Users** and **Audit log** in the navigation;
auditors see **Audit log**.

## Roles

The console has five roles. Each account has exactly one.

| Role | In short |
|---|---|
| **admin** | Everything, including accounts, engines and console settings |
| **designer** | Reads everything a viewer reads; edits, checks, previews, simulates, approves and publishes configuration; runs connector checks |
| **operator** | Reads everything a viewer reads; runs and controls engines: reconciliations, full syncs, drift checks, threshold approvals, dead letters, manual joins and unjoins |
| **viewer** | Reads engines, their status, configuration versions and the object explorer |
| **auditor** | Reads everything a viewer reads, plus the audit log |

The roles do not stack: a designer cannot run a full sync and an operator cannot publish a
configuration. Give someone who needs both the `admin` role, or two accounts. The console checks
every request on the server; the UI only hides what would be refused.

### Permissions matrix

What each role may call, from the role checks in the console's API handlers:

| Action | admin | designer | operator | viewer | auditor |
|---|:-:|:-:|:-:|:-:|:-:|
| See engines, live status, metrics, dead letters | ✓ | ✓ | ✓ | ✓ | ✓ |
| Read configuration versions, compare them, see what the engine runs, export a version to a file | ✓ | ✓ | ✓ | ✓ | ✓ |
| Open the Computed Attribute Studio and preview rules (read-only unless designer) | ✓ | ✓ | ✓ | ✓ | ✓ |
| Search the object explorer, open entities and their history, see correlation review | ✓ | ✓ | ✓ | ✓ | ✓ |
| Check a configuration as it is typed | ✓ | ✓ | | | |
| Save a version, import the running configuration, import a file | ✓ | ✓ | | | |
| Test a connection, browse a connector, read sample records, live preview | ✓ | ✓ | | | |
| Simulate a version | ✓ | ✓ | | | |
| Approve a version (not your own) | ✓ | ✓ | | | |
| Publish a version | ✓ | ✓ | | | |
| Reconcile, full sync, drift check | ✓ | | ✓ | | |
| Let deletes held by a deletion threshold through | ✓ | | ✓ | | |
| Retry or discard dead letters | ✓ | | ✓ | | |
| Join a record to an entity, unjoin a record | ✓ | | ✓ | | |
| Read the audit log and verify its chain | ✓ | | | | ✓ |
| Create, change, disable and reset accounts | ✓ | | | | |
| Local sign-in policy, configuration approval, banners | ✓ | | | | |
| Register and remove engines | ✓ | | | | |
| Reveal sensitive attributes | Roles listed in `CONSOLE_REVEAL_ROLES` (default: `admin` only); see below | | | | |

Anyone signed in may read their own account, sign out and change their own password. The sign-in
page, the version and the banners (`GET /api/v1/system`) need no sign-in.

A refused request answers `403` with a message such as "the operator role cannot do this". Refusals
of this kind are not written to the audit log; refused sign-ins and refused reveals are.

### Revealing sensitive attributes

Attributes listed in an engine configuration's `attributes.sensitive` come without their values in
the object explorer, correlation review, drift findings and simulations, and are not searched.
What derives from them is hidden too: computed attributes that read them, source fields that flow
into them, and target attributes built from them. DNs are not masked, so do not build DNs from
sensitive attributes.

Roles listed in the console setting `CONSOLE_REVEAL_ROLES` may ask to see the values. It is a
comma-separated list of role names, or `none`; the default is `admin`. A value that is not a role
stops the console from starting. Accounts whose role may reveal get a **Reveal sensitive
attributes** button on an entity and an **Include sensitive attributes (audited)** checkbox on
searches, correlation review and simulations.

- Every reveal is recorded (`explore.reveal`, `config.simulate`) with the names of the attributes
  it showed, and the record is written before the values are returned: if the audit log cannot be
  written, nothing is revealed. A search that reveals stores a SHA-256 of the search text instead
  of the text.
- An attempt by a role that may not reveal is refused and recorded with the outcome `denied`.
- Revealing in a simulation also needs the designer role. Attributes the engine's running
  configuration treats as sensitive stay hidden even when the simulated version no longer lists
  them.

`CONSOLE_REVEAL_ROLES` is set in the console's environment (`/etc/syncd/console.env`); see
[Configuration](configuration.md).

## The Users page (`/users`)

Administrators only. At the top are three cards, **Local sign-in**, **Configuration approval** and
**Banners**, described in the sections below. Under them is the table of accounts:

| Column | Shows |
|---|---|
| **User** | Username and display name |
| **Role** | A drop-down: changing it changes the role at once |
| **State** | **active** or **disabled**, plus **locked** (too many failed sign-ins), **one-time password** (must change it at the next sign-in) and **SSO** (signs in through single sign-on) |
| **Break-glass** | A checkbox, for accounts with a local password (see [Local sign-in and break-glass accounts](#local-sign-in-and-break-glass-accounts)) |
| **Created** | When the account was made |
| Actions | A key button, **Reset password** (local accounts only), and **Disable** / **Enable** (not on your own row) |

### Creating an account

Choose **New user** and fill in:

| Field | Rules |
|---|---|
| **Username** | 1–64 characters: letters, digits, `.`, `_`, `@`, `-`. Must be unused |
| **Display name** | Optional |
| **Role** | One of the five roles; **viewer** is preselected. The hint under it says what the role does |

**Create** shows a 20-character **one-time password** once. Give it to the user over a trusted
channel; they must choose their own at the first sign-in. The account and its record in the audit
log (`user.create`, with the role) are written together, so an account never exists without its
record.

### Changing a role, disabling and enabling

- **Role**: pick another in the row's drop-down. It applies from the account's next request; the
  account stays signed in. Recorded as `user.update` with the role before and after.
- **Disable**: the account can no longer sign in, by password or single sign-on, and its sessions
  end at once (a live status stream it has open stops too). **Enable** lets it back in.

The console refuses changes that would lock everyone out:

- the last enabled admin cannot be demoted or disabled ("make another admin first");
- while local sign-in is limited to break-glass accounts, the last enabled break-glass admin with
  a password cannot be unmarked, demoted or disabled.

Accounts are never deleted; disable those that should no longer sign in.

### Password resets and lockouts

The key button issues a new one-time password for a local account. It also clears the failed
sign-in count, unlocks the account, and ends its sessions. Recorded as `user.password_reset`. An
account that signs in through single sign-on has no console password and cannot be reset here (nor
from the command line, unless told to; see
[`syncd-console admin reset-password`](#syncd-console-admin-reset-password)).

Sign-in limits, which are fixed in the code:

| Limit | Value |
|---|---|
| Failed sign-ins before an account locks | 5 |
| Lock duration | 15 minutes (a password reset unlocks it at once) |
| Sign-in attempts per client address | 20 per 5 minutes |

Every failed sign-in gets the same answer, so it reveals nothing about the account. The audit log
records the reason (`unknown account`, `wrong password`, `locked`, `disabled`, `no local password`,
`local sign-in is limited to break-glass accounts`, `rate limited`).

The password rules, for every new password:

- at least 12 characters;
- not the username, and not `admin` (ignoring case);
- not the same as the current password.

Passwords are hashed with Argon2id, or with PBKDF2-HMAC-SHA512 when the console runs in FIPS mode.
FIPS mode does not accept Argon2id hashes, so accounts created before FIPS mode was turned on need
a password reset. See [FIPS 140-3 and DISA STIG](fips-and-stig.md).

If every administrator is locked out, use [`syncd-console admin reset-password`](#syncd-console-admin-reset-password)
on the console host.

### Changing your own password

Anyone with a local password can change it at `/change-password`: the current password, the new
one twice. A forced change after a one-time password uses the same page. Changing it signs out the
account's other sessions and is recorded as `user.password_change` (a wrong current password is
recorded as a failure).

!!! note
    The console has no link to this page other than the forced change after a one-time password:
    type `/change-password` after the console's address.

### Accounts that sign in through single sign-on

With OpenID Connect set up, the first sign-in through the identity provider creates the account,
marked **SSO**. Such an account:

- has no console password: it cannot be reset, cannot change a password here, and cannot be
  break-glass. Only `syncd-console admin reset-password --allow-single-sign-on` gives it one;
- gets its role from the provider's group claim at every sign-in. Changing it on the **Users** page
  lasts only until the account next signs in;
- stays out if it is disabled here, whatever the provider says.

An identity whose username matches an existing local account is refused rather than signed in to
it. Setting up the provider and the role map is described in [Single sign-on](single-sign-on.md).

## Local sign-in and break-glass accounts

Once single sign-on is set up, an administrator can stop local accounts from signing in with their
passwords, except a few **break-glass** accounts kept for when the identity provider is down. The
**Local sign-in** card on the **Users** page shows the current setting (**everyone** or
**break-glass accounts only**) and the break-glass accounts that can sign in now.

To limit local sign-in:

1. Tick **Break-glass** for at least one enabled `admin` account with a local password (`admin`
   itself is a good choice). Accounts marked **SSO** cannot be break-glass.
2. Choose **Limit to break-glass accounts**. The button is disabled until single sign-on is
   configured.

The console refuses the change without single sign-on, or without an enabled break-glass admin
that has a password. When it is made:

- local accounts that are not break-glass are signed out, and their later attempts get the usual
  refusal (the audit log records why);
- the sign-in page shows only **Sign in with &lt;provider&gt;**, with a small **Break-glass sign-in**
  link that opens the password form and warns that every attempt is audited.

**Let every local account sign in** turns the limit off. Both changes are recorded as
`settings.local_sign_in`, with the setting before and after.

## Configuration approval (four eyes)

By default a designer publishes their own configuration versions. An administrator can require
that someone else approves each version first: **Configuration approval** card, **Require
approval**. **Stop requiring approval** turns it off. Both are recorded as
`settings.config_approval`.

While approval is required:

- a version that has never been published is published only after a designer or admin **other
  than its author** has chosen **Approve** on it. The editor shows **awaiting approval** and the
  **Publish** button stays disabled until then;
- an approval is recorded (`config.approve`) and final: the database refuses to change it, and
  refuses an approval by the version's author, whatever the API is asked;
- a version that was published before can be published again without approval, so rolling back is
  never held up;
- approving needs the designer or admin role. Operators, viewers and auditors cannot approve.

!!! warning
    The rule lives in the console. The engine's own control API (and `syncd` commands run on the
    engine host) can still apply a configuration without approval. Keep the engine's mTLS client
    certificates for the console only.

## Banners

The **Banners** card sets a banner across the **top** and one across the **bottom** of every
console page, the sign-in page included: a classification marking such as `UNCLASSIFIED//FOUO`, or
the environment's name. Both stay in view while a page scrolls.

For each banner, tick it on and set:

| Field | Rules |
|---|---|
| **Text** | One line, up to 200 characters |
| **Background** | A colour as `#rrggbb` (or `#rgb`), typed or picked. Default `#007a33` |
| **Text color** | The same. Default `#ffffff` |

A preview shows the banner as it will look. **Save banners** applies both; untick a banner and save
to remove it. The console refuses colours whose contrast ratio is below **4.5:1** (WCAG AA for
text), naming the ratio it found: white on yellow is refused, black on yellow is accepted. Changes
are recorded as `settings.banners`, with the banners before and after.

Banners are console settings, kept in the database; they are not environment variables.

## Engines

The console manages one or more engines. Each is registered by name and the address of its gRPC
control API, or of its control socket.

### Registering an engine

The local engine is registered for you:

| Install | Registered as |
|---|---|
| Package or bundle (`syncdctl install`) | `local` at `127.0.0.1:7443`, with the certificates `syncdctl` issued |
| Helm chart | The release's name (or `engine.name`) at the engine's service, port 7443, with the chart's certificates. A Job does it after each install and upgrade, with `--update` |

To add another, choose **Register engine** on the **Engines** page:

| Field | Meaning |
|---|---|
| **Name** | 1–64 letters, digits, `.`, `_`, `-`; unique in this console |
| **Control API address** | `host:port`, as in the engine's `api.listen`; or `unix:` and the absolute path of the engine's control socket (`api.control_socket`), such as `unix:/run/syncd/control.sock` |
| **Mutual TLS** | Ticked by default. Required unless the engine is on a loopback address. Then paste the **engine CA certificate**, the **console client certificate** and the **console client key** (PEM). Untick it for a `unix:` address: the control socket takes no TLS, and the console refuses certificates with one |

The console connects and asks the engine for its status before saving; an engine that does not
answer is not registered. The client key is stored encrypted with the console's master key and is
never shown again. Recorded as `engine.register`, with the address and whether mTLS is used.

A `unix:` address works only when the console runs on the engine's host as a user that may open
the socket. On a packaged install the socket is mode 0600 (root and the engine's own user), so the
`syncd-console` user cannot open it; the packaged console keeps using `127.0.0.1:7443`.

### Certificate expiry and renewal

For an engine registered with mutual TLS, the console reads when its client certificate expires.
The engine's page warns from 60 days before (in red under 14 days), and says when it has expired:
the console then cannot reach the engine.

- **Package or bundle installs:** run `sudo syncdctl renew-certs` on the engine's host
  (`--new-ca` to replace the CA too). It issues new certificates, restarts the engine, gives the
  console its new ones with `syncd-console admin register-engine --update`, and restarts the
  console. `syncdctl status` shows when the certificates expire. See the
  [Install guide](install.md).
- **Helm installs:** the registration Job gives the console new credentials after each upgrade
  that issued new certificates.
- **Other engines:** issue new certificates for the engine and the console, then register the
  engine again with [`register-engine --update`](#syncd-console-admin-register-engine). The page
  cannot replace a registration.

Replacing a registration keeps the engine's configuration versions, and is recorded as
`engine.update`.

### Removing an engine

The **Remove engine** button (a bin icon) at the end of the buttons on an engine's page, then
**Remove**. The console forgets the engine **and all its
configuration versions** in the console database; the engine itself keeps running with the
configuration it has. Recorded as `engine.remove`.

!!! warning
    Removing an engine deletes its version history from the console. Export the versions you want
    to keep first (**Export** on each version), or back up the console database.

## The audit log (`/audit`)

Administrators and auditors. The console records every sign-in, account change, setting change,
configuration action, engine command and object-explorer look in its own audit log, in the console
database. It is not the `ens-audit/1` trail of the other applications: it has no export, no SIEM
forwarder and no pull API yet (see [Audit logging](../audit-logging.md)).

The page lists entries newest first, 100 at a time (**Older entries** loads more), with the columns
**Time**, **Actor** (and the client address), **Action**, **Target**, **Outcome** (`success`,
`failure` or `denied`) and **Detail** (the record's JSON). The page has no search or filter; the
API (`GET /api/v1/audit?before=<id>&limit=<n>`, up to 500 a page) pages through it the same way.

The client address is the connection's, or, behind a reverse proxy listed in
`CONSOLE_TRUSTED_PROXIES`, the client named in its `X-Forwarded-For` header.

### What is recorded

| Actions | When |
|---|---|
| `auth.login`, `auth.logout`, `auth.oidc.login` | Sign-ins (with the reason for a failure or refusal) and sign-outs |
| `user.bootstrap`, `bootstrap.password_file_removed` | The first `admin` account, and the initial password file being deleted |
| `user.create`, `user.update`, `user.password_reset`, `user.password_change`, `user.password_change_skipped` | Account changes (role, disabled and break-glass before and after). A reset from the command line has the actor `system`, and says whether the account signs in through single sign-on |
| `settings.local_sign_in`, `settings.config_approval`, `settings.banners` | Console settings, before and after |
| `engine.register`, `engine.update`, `engine.remove` | Engines added, registered again with new credentials, and removed (`system` when the installer or `syncdctl renew-certs` does it) |
| `engine.run`, `engine.approve_deletions`, `engine.check_drift`, `engine.dead_letters.retry`, `engine.dead_letters.discard` | Operator commands, with the request and the engine's answer or error |
| `correlation.join`, `correlation.unjoin` | Records joined or unjoined by hand |
| `config.save`, `config.import`, `config.import_file`, `config.export` | Versions saved, imported or exported |
| `config.approve` | Approvals, and refused approvals |
| `config.publish.start`, `config.publish` | A publish is recorded before it is sent and again with its outcome; a publish the engine confirms late is settled with `config.publish` |
| `config.simulate`, `config.preview`, `connector.test`, `connector.discover` | The designer checks that make the engine read data |
| `explore.search`, `explore.entity`, `explore.history`, `explore.unjoined`, `explore.reveal` | Every object-explorer search (with its text), entity viewed, history read and correlation-review listing; reveals |

Some changes and their records are written in one transaction, so if the audit log cannot be
written the change is refused ("the audit log cannot be written, so this was not done"): account
creation, changes and resets, settings, approvals, the start of a publish, and reveals. Other
records are written after the action; if that write fails, the console logs an error instead.

The log has no retention setting: entries are kept for good.

### Verifying the chain

The table is append-only: database triggers refuse updates, deletes and truncation. Each entry also
stores `SHA-256(previous entry's hash ‖ the entry)`, so an entry changed or removed behind the
triggers (by someone with direct database access) breaks the chain.

**Verify chain** recomputes the whole chain (`GET /api/v1/audit/verify`). Verification starts
from the first entry and can take a while on a large log.

The chain shows edits and removals of entries that have later entries after them. Removing the
newest entries leaves a shorter chain that still verifies on its own. To catch that, the console
records the chain's **head** (its newest entry's id, time and hash) outside the database:

- every hour, while the log has changed, the console writes a line `audit log head` with `id`,
  `at` and `hash` to its own log (on a host, the journal: `journalctl -u syncd-console | grep
  'audit log head'`). Keep those lines outside the console database, in a SIEM or log archive;
- each successful verification shows the head too (**Head: entry N, &lt;hash&gt;**).

To verify against a head recorded earlier, fill in **Recorded head: entry** and **Its hash** above
the log before choosing **Verify chain** (the API takes `anchor_id` and `anchor_hash`, both or
neither). The result is one of:

| Result | Meaning |
|---|---|
| **All N entries are intact** (with the head; with a recorded head, "up to and past the recorded head") | The chain matches, and so does the recorded head |
| **The log was altered**: entry N does not match the chain | An entry, or an earlier one, was changed outside the console |
| **Entries were removed** | The recorded head's entry is no longer in the log: entries were removed from its end |
| **The log was altered**: entry N no longer has the recorded hash | It, or an earlier entry, was changed |

## Command-line administration (`syncd-console admin`)

`syncd-console` has two administration commands besides `serve` (the default). They connect to the
console database directly, so they need the console's settings: run them on the console host as
the `syncd-console` user, with the console's environment file.

```sh
sudo runuser -u syncd-console -- env -C / \
  $(sudo grep -E '^[A-Za-z_][A-Za-z0-9_]*=' /etc/syncd/console.env) \
  CONSOLE_DATA_DIR=/var/lib/syncd/console \
  /opt/syncd/current/bin/syncd-console admin <command> ...
```

The environment file matters: it carries the database URL and, on a FIPS installation,
`SYNCD_FIPS_MODE`, which decides how the new password is hashed.

### `syncd-console admin reset-password`

```sh
syncd-console admin reset-password [--user NAME] [--allow-single-sign-on]
```

Issues a new 24-character one-time password for the account (`admin` by default), prints it, and
requires it to be changed at the next sign-in. It also unlocks the account and ends its sessions.
Recorded as `user.password_reset` by `system`, with `"via": "command line"` and whether the
account signs in through single sign-on. Use it when every administrator is locked out, or when the
identity provider and every break-glass password are unavailable.

Like the **Users** page, it refuses an account that signs in through single sign-on: a password
would let it sign in without the identity provider (and its multi-factor authentication).
`--allow-single-sign-on` gives such an account a password anyway, for recovery while the provider
is down. The account can then sign in with the password as well as through the provider, until
it is disabled.

### `syncd-console admin register-engine`

```sh
syncd-console admin register-engine --name NAME --address HOST:PORT|unix:/PATH \
  [--server-ca CA.pem --client-cert CERT.pem --client-key KEY.pem] [--update]
```

Registers an engine as the **Register engine** page does, checking that it answers first. The three
PEM files are needed together, and are required unless the address is a loopback one or a `unix:`
control socket (which takes none). When an engine with the name is registered already, the command
says so and changes nothing, unless given `--update`: then it replaces the engine's address and
credentials, keeping its configuration versions, and the console's next call uses them.
`syncdctl install` uses it to register the local engine, and `syncdctl renew-certs` and the Helm
chart's registration Job use it with `--update`. Recorded as `engine.register` (or `engine.update`)
by `system`.

There is no command to create accounts, change roles or change the console settings; those are done
in the console or through the [API](api-reference.md).

## Settings that are not in the console

These are set in the console's environment (`/etc/syncd/console.env`) by whoever runs the host, and
take effect when the console restarts. See [Configuration](configuration.md).

| Setting | What it controls |
|---|---|
| `CONSOLE_REVEAL_ROLES` | Which roles may reveal sensitive attributes (default `admin`) |
| `CONSOLE_OIDC_*`, `CONSOLE_PUBLIC_URL` | Single sign-on, the role claim and the role map |
| `CONSOLE_SESSION_IDLE_MINUTES`, `CONSOLE_SESSION_MAX_HOURS` | Session idle timeout (default 30 minutes) and maximum length (default 12 hours) |
| `CONSOLE_TRUSTED_PROXIES` | Reverse proxies whose `X-Forwarded-For` names the client |
| `CONSOLE_ADMIN_PASSWORD`, `CONSOLE_ADMIN_PASSWORD_FILE` | The first `admin` password, on an empty database only |
| `CONSOLE_TLS_CERT`, `CONSOLE_TLS_KEY` | A certificate (PEM, with its chain) and key for the console to serve HTTPS itself, without a TLS reverse proxy. Both or neither. The files are read again within a minute when they change, so a renewal needs no restart |
| `CONSOLE_MASTER_KEY`, `CONSOLE_NEW_MASTER_KEY` | The master key that seals the engines' client keys (otherwise `$CONSOLE_DATA_DIR/master.key`), and whether to start without the right one; see [The master key](#the-master-key) |
| `SYNCD_FIPS_MODE` | FIPS mode, which changes password hashing |

### The master key

The console seals the client keys of engines registered with mutual TLS with its master key:
`CONSOLE_MASTER_KEY`, or the file `$CONSOLE_DATA_DIR/master.key` (`/var/lib/syncd/console/master.key`
on a host; the `syncd-console` Secret under Helm). The console creates the file on its first
start. Back it up with the database: the sealed keys cannot be recovered without it.

At each start the console checks the key against a sealed key in its database. It refuses to start
when:

- `master.key` is missing while the database holds sealed keys (a restore that missed it, a new
  data directory). It does not create a new key over them;
- the key does not open them: it is not the key they were sealed with.

Either way, restore `master.key` from a backup, or set `CONSOLE_MASTER_KEY` to the old key. To
start anyway, set `CONSOLE_NEW_MASTER_KEY=true` (or pass `--new-master-key`): the console starts
with a new key if it has none, the sealed keys are lost, and every engine registered with mutual
TLS must be registered again (`register-engine --update`, or `sudo syncdctl renew-certs` for the
local engine). Keep the setting until they are: until then the old sealed keys are still in the
database, and the console, and the `syncd-console admin` commands run with its environment, refuse
to start without it. Remove it afterwards, so that a lost key is caught again.
