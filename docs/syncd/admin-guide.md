# Admin guide

This guide is for **console administrators**: people holding the `admin` role in the syncd web
console (`syncd-console`). It covers what an administrator does in the console and with the
`syncd-console admin` commands: accounts and roles, sign-in policy, configuration approval and the
pre-publish hook, banners, engines and HA pairs, the **Integrations** page (API tokens, environments,
webhooks), Git sync, scheduled reports and mail, the self-service password page, and the audit trail.
Installing, upgrading and backing up are in the [Install guide](install.md) and the
[Operator guide](operator-guide.md); the screens designers and operators use every day are in the
[User guide](user-guide.md).

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
in the audit trail as `bootstrap.password_file_removed`). Creating the account is recorded as
`user.bootstrap`, with whether the password was generated, supplied or the development default.

You can tell your role from the header, which shows your name and role (for example
`Administrator · admin`). The navigation depends on the role:

| Role | Navigation |
|---|---|
| admin | **Engines**, **Users**, **Audit log**, **Reports**, **Integrations** |
| auditor | **Engines**, **Audit log**, **Reports**, **Integrations** (read-only) |
| operator | **Engines**, **Reports** |
| designer, viewer | **Engines** |

## Roles

The console has five roles. Each account has exactly one.

| Role | In short |
|---|---|
| **admin** | Everything, including accounts, engines, API tokens, webhooks, environments and console settings |
| **designer** | Reads everything a viewer reads; edits, checks, previews, simulates, approves, publishes and promotes configuration; runs connector checks; syncs from Git |
| **operator** | Reads everything a viewer reads; runs and controls engines: reconciliations, full syncs, drift checks, threshold approvals, held changes, group reviews, dead letters, manual joins, unjoins and accepted correlation suggestions; schedules reports |
| **viewer** | Reads engines, their status, configuration versions, the object explorer and data quality, and exports reports and lineage |
| **auditor** | Reads everything a viewer reads, plus the audit trail, the API token and webhook lists, the environments and the scheduled reports |

The roles do not stack: a designer cannot run a full sync and an operator cannot publish a
configuration. Give someone who needs both the `admin` role, or two accounts. The console checks
every request on the server; the UI only hides what would be refused. 0.4.0 added no role; it gave
auditors read access to tokens, webhooks and reports.

### Permissions matrix

What each role may do, from the role checks in the console's API handlers:

| Action | admin | designer | operator | viewer | auditor |
|---|:-:|:-:|:-:|:-:|:-:|
| See engines, live status, metrics, dead letters, held changes, groups, data quality | ✓ | ✓ | ✓ | ✓ | ✓ |
| Download the reconciliation report (**Report CSV**, **PDF**) and an entity's lineage (**Lineage JSON**, **PDF**) | ✓ | ✓ | ✓ | ✓ | ✓ |
| Read configuration versions, compare them (also by meaning), see what the engine runs, see a version's packages, export a version to a file | ✓ | ✓ | ✓ | ✓ | ✓ |
| Open the Computed Attribute Studio, preview rules and try a quality check on a sample (read-only unless designer) | ✓ | ✓ | ✓ | ✓ | ✓ |
| Search the object explorer, open entities and their history, see correlation suggestions | ✓ | ✓ | ✓ | ✓ | ✓ |
| Check a configuration as it is typed | ✓ | ✓ | | | |
| Save a version, import the running configuration, import a file, **Sync now** from Git | ✓ | ✓ | | | |
| Test a connection, browse a connector, read sample records, live preview | ✓ | ✓ | | | |
| Simulate a version | ✓ | ✓ | | | |
| Approve a version (not your own) | ✓ | ✓ | | | |
| Publish a version; promote one to another environment (API and `syncd config promote` only) | ✓ | ✓ | | | |
| Reconcile, full sync, drift check | ✓ | | ✓ | | |
| Let deletes held by a deletion threshold through; **Approve and apply** held changes (windows, thresholds, canaries; see [Change safety](change-safety.md#approving)) | ✓ | | ✓ | | |
| **Review** a group ([group lifecycle](change-safety.md#review)) | ✓ | | ✓ | | |
| Retry or discard dead letters | ✓ | | ✓ | | |
| Join a record to an entity, unjoin a record, accept correlation suggestions | ✓ | | ✓ | | |
| See the scheduled reports | ✓ | | ✓ | | ✓ |
| Create, change, send now and delete scheduled reports | Anyone's | | Their own | | |
| Read the audit trail, verify its chain, export it, see its outputs' delivery | ✓ | | | | ✓ |
| See the API tokens and webhooks (with each webhook's delivery log) | ✓ | | | | ✓ |
| See the environments on the **Integrations** page | ✓ | | | | ✓ |
| Create and revoke API tokens; add, change, test and delete webhooks; set environments | ✓ | | | | |
| Create, change, disable and reset accounts | ✓ | | | | |
| Local sign-in policy, configuration approval, banners | ✓ | | | | |
| Register and remove engines; move an engine to another environment (API) | ✓ | | | | |
| Download the console's support bundle (API) | ✓ | | | | |
| Reveal sensitive attributes | Roles listed in `CONSOLE_REVEAL_ROLES` (default: `admin` only); see below | | | | |

Anyone signed in may read their own account, sign out and change their own password. The sign-in
page, the version, the banners and the publish settings (`GET /api/v1/system`) need no sign-in, nor
does the [self-service password page](#the-self-service-password-page).

The environments' values (secret values excepted) are also readable through the API by any role
(`GET /api/v1/environments`, the `configs:read` scope): only the **Integrations** page limits them
to admins and auditors.

A refused request answers `403` with a message such as "the operator role cannot do this". Refusals
of this kind are not written to the audit trail when they come from a signed-in session; refused
sign-ins, refused reveals and refused API tokens (`api.token-refused`) are.

### Revealing sensitive attributes

Attributes listed in an engine configuration's `attributes.sensitive` come without their values in
the object explorer, correlation review, drift findings, simulations, reports and lineage exports,
and are not searched. What derives from them is hidden too: computed attributes that read them,
source fields that flow into them, and target attributes built from them. DNs are not masked, so do
not build DNs from sensitive attributes.

Roles listed in the console setting `CONSOLE_REVEAL_ROLES` may ask to see the values. It is a
comma-separated list of role names, or `none`; the default is `admin`. A value that is not a role
stops the console from starting. Accounts whose role may reveal get a **Reveal sensitive
attributes** button on an entity and an **Include sensitive attributes (audited)** checkbox on
searches, correlation review and simulations. A lineage export made while an entity's values are
revealed includes them.

- Every reveal is recorded (`explore.reveal`, `config.simulate`) with the names of the attributes
  it showed, and the record is written before the values are returned: if the audit trail cannot
  be written, nothing is revealed. A search that reveals stores a SHA-256 of the search text instead
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
trail (`user.create`, with the role) are written together, so an account never exists without its
record.

### Changing a role, disabling and enabling

- **Role**: pick another in the row's drop-down. It applies from the account's next request; the
  account stays signed in. Recorded as `user.update` with the role before and after.
- **Disable**: the account can no longer sign in, by password or single sign-on, and its sessions
  end at once (a live status stream it has open stops too). **Enable** lets it back in.

Both also apply to the account's [API tokens](#api-tokens) at once: a disabled creator's tokens are
refused, and a demoted creator's tokens keep only what the new role allows.

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

Every failed sign-in gets the same answer, so it reveals nothing about the account. The audit trail
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

Anyone with a local password can change their console password at `/change-password`: the current
password, the new one twice. A forced change after a one-time password uses the same page. Changing
it signs out the account's other sessions and is recorded as `user.password_change` (a wrong current
password is recorded as a failure).

!!! note
    The console has no link to this page other than the forced change after a one-time password:
    type `/change-password` after the console's address. It changes the **console** password. The
    public `/password` page is a different thing: it changes people's **directory** passwords (see
    [The self-service password page](#the-self-service-password-page)).

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
  refusal (the audit trail records why);
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
- approving needs the designer or admin role. Operators, viewers and auditors cannot approve, and
  no API token can approve;
- versions that [Git sync](#git-sync) publishes, and versions [promoted](config-as-code.md#promotion) to
  another environment's engines, wait for an approval on each engine like any other.

!!! warning
    The rule lives in the console. The engine's own control API (and `syncd` commands run on the
    engine host) can still apply a configuration without approval. Keep the engine's mTLS client
    certificates for the console only.

## The pre-publish hook and change tickets

Publishing can be made to carry a change-ticket reference, and to ask a change-management system
(an ITSM, a CAB workflow) first. Both are off unless set in the console's environment, and both
apply to every publish: the **Publish** button, an API token with `configs:publish`, Git sync's
auto-publish and promotion.

| Setting | Default | Meaning |
|---|---|---|
| `CONSOLE_PUBLISH_TICKET_PATTERN` | unset | A regular expression, such as `^(CHG\|INC)-[0-9]{4,}$`. When set, a publish without a matching ticket is refused (`400`) before anything else |
| `CONSOLE_PUBLISH_HOOK_URL` | unset | An `https://` address asked before each publish |
| `CONSOLE_PUBLISH_HOOK_SECRET_FILE` | unset | A file holding the HMAC secret the request is signed with. Needed with the URL; read at each use, so it can be rotated |
| `CONSOLE_PUBLISH_HOOK_TIMEOUT` | `10s` | How long the hook may take |
| `CONSOLE_PUBLISH_HOOK_ON_ERROR` | `refuse` | What a hook that does not answer (a timeout, a `5xx`, no connection) means: `refuse` (`503`) or `allow` |

The console refuses to start with a hook URL that is not `https://`, or without a readable secret
file.

- **In the console**, the publish dialog asks for a **Change ticket** (required when a pattern is
  set, checked as it is typed) and a **Note**, whenever a pattern or a hook is set. With Git sync
  publishing, the ticket is taken from the commit message.
- **The hook** gets a signed `POST` (`X-Syncd-Event: config.publish-requested`, signed like a
  webhook) after the four-eyes and test checks, and before anything is recorded or sent to the
  engine. Its body names the ticket, the note, the version, the engine, who asked, and how many
  lines changed against the version published before. A `2xx` with `{"decision":"allow"}` (or no
  decision) lets the publish go. `{"decision":"deny","reason":"…"}` or any `4xx` refuses it with
  `409` and the reason, and raises the `config.rejected` webhook event.
- **The hook's address** follows the same rules as a webhook's: `https://` only, never loopback,
  link-local or a metadata address, and private addresses only as `CONSOLE_WEBHOOKS_ALLOWED_HOSTS`
  and `CONSOLE_WEBHOOKS_BLOCK_PRIVATE_NETWORKS` allow (see [Webhooks](webhooks.md)).
- **What is kept**: the ticket, the note and the hook's decision are stored with the version (the
  **Ticket** column of the versions list, with a `hook: allow`, `hook: deny` or
  `hook: error-allowed` badge) and in the `config.publish.start` and `config.publish` audit events.
  A refused publish is a failed `config.publish`, with the decision.

## Banners

The **Banners** card sets a banner across the **top** and one across the **bottom** of every
console page, the sign-in page included: a classification marking such as `UNCLASSIFIED//FOUO`, or
the environment's name. Both stay in view while a page scrolls. The top banner's text is also
printed in the header and footer of every page of the PDF reports and lineage exports.

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
| Helm chart | The release's name (or `engine.name`) at the engine's service, port 7443, with the chart's certificates. A Job does it after each install and upgrade, with `--update`. With `engine.ha.enabled`, it registers both pods, as `<name>-0` and `<name>-1` |

To add another, choose **Register engine** on the **Engines** page:

| Field | Meaning |
|---|---|
| **Name** | 1–64 letters, digits, `.`, `_`, `-`; unique in this console |
| **Control API address** | `host:port`, as in the engine's `api.listen`; or `unix:` and the absolute path of the engine's control socket (`api.control_socket`), such as `unix:/run/syncd/control.sock` |
| **Environment** | The [environment](#environments) whose values a configuration published to this engine is rendered with. `default` unless you run several |
| **Mutual TLS** | Ticked by default. Required unless the engine is on a loopback address. Then paste the **engine CA certificate**, the **console client certificate** and the **console client key** (PEM). Untick it for a `unix:` address: the control socket takes no TLS, and the console refuses certificates with one |

The console connects and asks the engine for its status before saving; an engine that does not
answer is not registered. The client key is stored encrypted with the console's master key and is
never shown again. Recorded as `engine.register`, with the address, whether mTLS is used and the
environment.

A `unix:` address works only when the console runs on the engine's host as a user that may open
the socket. On a packaged install the socket is mode 0600 (root and the engine's own user), so the
`syncd-console` user cannot open it; the packaged console keeps using `127.0.0.1:7443`.

To move a registered engine to another environment, call
`PATCH /api/v1/engines/{id}` with `{"environment": "prod"}` (admins; there is no button for it).
What the engine runs is not touched; the next publish renders for the new environment. Recorded as
`engine.update`, with the environment before and after.

### HA pairs

Two engines can run as an active/passive pair (`engine.ha`; see the
[Install guide](install.md) and [Availability and sizing](availability-and-sizing.md)). Register
both, each by its own name and address. The console recognises a pair from the engines' status:

- the **Engines** page shows the pair as **one card**, headed **Pair &lt;name&gt;**, with each member
  and its role (**active**, **passive**, or **unreachable**), and the active engine's status below.
  **no active engine** shows when neither holds the lease;
- each engine's page says which role it has, who holds the lease and how old the newest store
  snapshot is. A passive engine with nobody active shows a red notice: nothing is read or exported;
- commands sent to the passive engine (a reconciliation, a retry) are refused with the active one's
  name, so act on the active engine's page;
- when the passive engine takes over, the console sends the `ha.failover` [webhook](webhooks.md)
  event, once.

Publish a configuration to the active engine. The other engine adopts it with the next snapshot
when its host settings are the same; where they differ, publish to both.

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

!!! warning "Re-registering resets the environment"
    `register-engine --update` also sets the engine's environment: to the one given with
    `--environment`, or back to `default`. `syncdctl renew-certs` and the Helm registration Job pass
    none, so an engine in another environment is back in `default` after a certificate renewal or a
    Helm upgrade. Move it back with `PATCH /api/v1/engines/{id}` before the next publish.

### Removing an engine

The **Remove engine** button (a bin icon) at the end of the buttons on an engine's page, then
**Remove**. The console forgets the engine **and all its
configuration versions** in the console database; the engine itself keeps running with the
configuration it has. Recorded as `engine.remove`.

!!! warning
    Removing an engine deletes its version history from the console. Export the versions you want
    to keep first (**Export** on each version), or back up the console database.

## Integrations (`/integrations`)

The **Integrations** page is where other systems are let in and told what happened. Administrators
manage it; auditors can look. It has three cards: **API tokens**, **Environments** and
**Webhooks**.

### API tokens

Other systems call the console's API with a token instead of a session: a SIEM pulling the audit
trail, a pipeline promoting configuration between environments. The **API tokens** card lists each
token's name, the start of the token (`sdt_…`), its scopes, who created it, when it expires, when it
was last used and its state (**active**, **expired**, **revoked**).

To create one, choose **New token**:

| Field | Rules |
|---|---|
| **Name** | What uses it, such as the SIEM or the pipeline. Up to 100 characters |
| **Scopes** | One or more, below. Each needs a role of its creator |
| **Expires** | Optional: a date (the token stops at the end of that day, UTC). Left empty, it gets the longest lifetime allowed (`CONSOLE_API_MAX_TOKEN_LIFETIME`, 365 days by default), which is also the most that may be asked for |

**Create** shows the token **once**, with a **Copy** button. The console keeps only its SHA-256; it
is never written to a log or the audit trail. **Revoke** on a row stops the token at once (there is
no confirmation).

| Scope | Needs the creator to be | Allows |
|---|---|---|
| `engines:read` | any role | The engines, their status, dead letters, quarantine, held changes, data quality, the reconciliation report |
| `configs:read` | any role | Configuration versions, the running configuration, diffs, exports, packages, environments, Git sync status |
| `configs:write` | designer or admin | Validate, save and import a version; Git **Sync now** (not publish) |
| `configs:publish` | designer or admin | Publish a version; promote one (approval, when required, stays with people) |
| `runs:run` | operator or admin | Start runs and drift checks; approve held deletions and changes; review groups; accept correlation suggestions; retry and discard dead letters |
| `audit:read` | auditor or admin | The audit trail, its verification, the pull API, the export and the outputs' status |
| `admin` | admin | Everything above |

A token never does more than its creator **now**:

- on every use the creator's account is read: a creator who is disabled or gone makes the token
  fail (`401`); a demoted creator's token keeps what the new role allows and loses the rest
  (`403`). A good answer is reused for up to a minute, and dropped when the account changes;
- no token, whatever its scopes, can manage tokens, webhooks, accounts, settings or scheduled
  reports, sign in through single sign-on, approve a version, simulate or preview one, test
  connectors, use the object explorer or export lineage: those stay with people.

Tokens are rate-limited per token (`CONSOLE_API_REQUESTS_PER_MINUTE`, 300 by default; beyond it
`429` with `Retry-After`). `CONSOLE_API_TOKENS_ENABLED=false` refuses every token without deleting
any; the page then says so and **New token** is disabled.

Audited: `api.token-created`, `api.token-revoked`, `api.token-refused` (at most once a minute per
token or client address), `api.request` for every request that changes something, and
`api.token-used` once an hour per token for its reads. The actor is `api-token:<id>`, of type
`service`. How a client sends the token is in the [API reference](api-reference.md).

### Environments

One base configuration can run in several installations. Where a value differs, the YAML says
`{{ env.hr_host }}`, and each engine's **environment** fills it in when a version is published to
that engine. How designers use this is in [Configuration as code](config-as-code.md#environments).

The **Environments** card lists each environment with its values (secret ones shown only as
**(secret)**), the engines that run in it and when it was last saved. Administrators choose
**New environment** or **Edit**:

| Field | Rules |
|---|---|
| **Name** | 1–64 lower-case letters, digits, `-` and `_`, starting with a letter or digit. New environments only |
| **Key** / **Value** | One row per value; **Add a value** adds a row. A whole number or `true` / `false` keeps its type, so a whole-value placeholder such as `port: "{{ env.hr_port }}"` renders as a number |
| **Secret** | Sealed with the console's master key when saved and never shown again, not even to administrators. On **Edit**, a secret left blank keeps its stored value; typing replaces it |

**Save** records `environment.changed`, with the keys added, removed and changed, never a value.
An environment no engine runs in can be removed with the bin button (`environment.removed`).
Engines that are given no environment run in `default`, which needs no entry here.

- A publish to an engine whose environment lacks a value the version needs is refused, naming the
  value.
- Secret values are rendered into the configuration sent to the engine; the console shows them as
  `<secret name>` everywhere else (the **Compare** tab, the check panel).
- With [Git sync](#git-sync), environment files in the repository can set plain values, but a
  file that carries a secret is refused.

### Webhooks

The **Webhooks** card sends signed HTTPS posts to your own integration, a Slack channel, a Microsoft
Teams workflow or PagerDuty when something happens: an engine, source or target going down or
coming back, runs, rising dead letters or quarantine, held changes, failed password deliveries,
expired groups, ageing unmatched records, failed reports or backups, an HA failover, and the
configuration lifecycle. Nothing is sent until an administrator adds a webhook.

Administrators choose **New webhook**, give a **Name**, a **Format** (`json`, `slack`, `teams` or
`pagerduty`), an `https://` **URL** and the **Events**, and are shown the **signing secret**
(`whsec_…`) once. Each row has **Deliveries** (the delivery log; auditors too), **Send a test**,
**Edit**, **New secret** and **Delete** (with a confirmation; the delivery log goes too).

- The URL is checked when saved and at every send: never loopback, link-local or a cloud metadata
  address; private addresses only as `CONSOLE_WEBHOOKS_BLOCK_PRIVATE_NETWORKS` and
  `CONSOLE_WEBHOOKS_ALLOWED_HOSTS` allow.
- A Slack or Teams URL is itself a credential: only administrators see it whole. Auditors, and the
  audit trail, see its scheme and host. URLs and secrets are sealed with the console's master key.
- Changes are audited: `webhook.created`, `webhook.changed`, `webhook.deleted`,
  `webhook.secret-rotated`, `webhook.test-sent`.
- `CONSOLE_WEBHOOKS_ENABLED=false` queues and posts nothing; the card then says so.

The events, the formats, how to verify the signature, retries and every setting are in
[Webhooks](webhooks.md).

## Git sync

The console can take its configuration from a Git repository, and put what is saved in the console
back into it. It is off unless `CONSOLE_CONFIG_GIT_URL` is set. How designers work with it, the
golden-file tests it runs and promotion are in [Configuration as code](config-as-code.md#git-sync); the
settings are:

| Setting | Default | Meaning |
|---|---|---|
| `CONSOLE_CONFIG_GIT_URL` | unset | The repository, `ssh://…` or `https://…`. Turns Git sync on |
| `CONSOLE_CONFIG_GIT_BRANCH` | `main` | The branch followed |
| `CONSOLE_CONFIG_GIT_PATH` | `syncd.yaml` | The configuration file in the repository |
| `CONSOLE_CONFIG_GIT_ENV_PATH` | unset | A directory of `<name>.env.yaml` environment files |
| `CONSOLE_CONFIG_GIT_SSH_KEY_FILE`, `CONSOLE_CONFIG_GIT_KNOWN_HOSTS_FILE` | unset | A deploy key and the known hosts, for `ssh://` |
| `CONSOLE_CONFIG_GIT_TOKEN_FILE` | unset | A file holding a token, for `https://` |
| `CONSOLE_CONFIG_GIT_POLL` | `60s` | How often the branch is fetched |
| `CONSOLE_CONFIG_GIT_AUTO_PUBLISH` | `off` | What a new commit becomes: `off`, a draft version; `validate`, a version whose tests are run; `publish`, a version published when it passes, through approval, the ticket pattern and the hook |
| `CONSOLE_CONFIG_GIT_PUSH_BACK` | `false` | Commit versions saved or published in the console to the branch, and push. A rejected push is reported, never forced |
| `CONSOLE_CONFIG_GIT_ENGINES` | empty (all) | The engines the repository is for, comma-separated |

- With a ticket pattern set and `publish`, the ticket must appear in the commit message; a commit
  without one stays a draft (`config.git-failed`).
- The **Configuration** page of each engine then has a **Git** card: the repository, what a new
  commit becomes, the last commit seen, the last sync, the last error, and **Sync now**
  (designers and admins). Nothing is taken in while an engine named in
  `CONSOLE_CONFIG_GIT_ENGINES` is not registered; the card says so.
- Audited: `config.git-synced` per commit, `config.git-failed`, and `config.git-refused` when an
  environment file carries a secret (also a webhook event).
- The `git` program does the work. The console's image carries git and ssh; a packaged console
  needs `git` installed (the packages recommend it). The clone is kept under the console's data
  directory (`git/`).

## Scheduled reports and mail

Anyone may download an engine's reconciliation report from its page (**Report CSV**, **PDF**). The
**Reports** page (`/reports`) sends it on a schedule, by e-mail or to a share. What a report holds
is in [Reports, lineage and data quality](reports-and-data-quality.md#scheduled-reports); this section is what an
administrator sets up and controls.

Who may do what:

- **Operators** create scheduled reports, and change, **Send now** and delete their own.
- **Administrators** do the same for everyone's.
- **Auditors** see the list.
- No API token manages reports.

Each run checks again that the report's owner is still an enabled administrator or operator and
that every recipient is in an allowed domain. A run that fails a check sends nothing and says why
in the **Last run** column and in the audit trail. Audited: `report.created`, `report.changed`,
`report.deleted` and `report.sent` (recipients and row count; a failed run is a failed
`report.sent`).

Nothing can be e-mailed until both of these are set. The **Reports** page says which is missing.

| Setting | Default | Meaning |
|---|---|---|
| `CONSOLE_REPORTS_RECIPIENT_DOMAINS` | empty | The domains reports may be e-mailed to, comma-separated (`example.gov`, or `*.mil` for a domain and its subdomains). Required for e-mail |
| `CONSOLE_SMTP_HOST` | unset | The mail relay. Turns e-mail on |
| `CONSOLE_SMTP_PORT` | `587` | |
| `CONSOLE_SMTP_STARTTLS` | `true` | Upgrade the connection with STARTTLS, checking the server's certificate against the public roots or `CONSOLE_SMTP_CA_FILE`. `false` sends in plain text: a relay on this host or a private network only |
| `CONSOLE_SMTP_CA_FILE` | unset | CA certificates (PEM) the relay's certificate may chain to |
| `CONSOLE_SMTP_USERNAME`, `CONSOLE_SMTP_PASSWORD_FILE` | unset | The account to authenticate as, and a file holding its password (read at each connection) |
| `CONSOLE_SMTP_FROM` | `syncd@<host>` | The sender address |
| `CONSOLE_REPORTS_SHARE_DIR` | unset | A directory (a mounted share) reports may be written to. Without it, reports go by e-mail only |
| `CONSOLE_REPORTS_MAX_ROWS` | `50000` | The most rows a scheduled report's file holds (the mail says when it was cut) |
| `CONSOLE_EXPORTS_MAX_ROWS` | `200000` | The most rows one download from an engine's page holds |

Mail goes through an outbox in the console database: a message waits while the relay is down and
is retried (from a minute, doubling to an hour) for a day, then given up, which raises the
`report.failed` [webhook](webhooks.md) event. Only one console replica sends a due report, however
many run.

!!! note "Limits"
    The console's SMTP client speaks STARTTLS and AUTH PLAIN only: no SMTPS (implicit TLS on 465)
    and no AUTH LOGIN. Reports have no Excel (`.xlsx`) export (spreadsheets open the CSV), and
    their summary has no "adopted" count.

## The self-service password page

With password synchronisation, people can change their own directory password on the console's
public page `/password`, without a console account. The console seals the request to the engine's
password key and hands it to that engine, which checks the current password against the directory
people sign in to, changes it there and delivers it to the other directories. The console keeps no
password. How the engine side is set up (`passwords.self_service`) is in
[Password synchronisation](password-sync.md#people-changing-their-own-password).

To turn the page on, set `CONSOLE_SELF_SERVICE_ENGINE` to the name of a registered engine whose
configuration has `passwords.self_service`. Without it, the page says "Password changes are not
available here." Give people the address `https://<console>/password`; the console has no link to
it.

- Attempts are throttled per client address, as sign-ins are, and the engine holds a user name off
  for 15 minutes after 5 failures.
- A wrong user name and a wrong password get the same answer.
- Every attempt is audited as `password.self_service`, with the actor `self-service:<user name>`
  and the outcome (changed, wrong credentials, refused by the policy, rate limited), never a
  password.

## The audit trail (`/audit`)

Administrators and auditors. The console records every sign-in, account change, setting change,
configuration action, engine command, object-explorer look, token use and export in its audit trail.
Since 0.4.0 the trail is in the ENS family's format, **`ens-audit/1`**: one JSON object per event,
hash-chained, with a `seq` number, a category, a severity and an actor, the same as the other
applications. It is the same in the database, on standard output, in the audit file, in Splunk, in
syslog and from the pull API. The format, the Splunk add-on and the suite-wide settings are
described in [Audit logging](../audit-logging.md).

### The Audit log page

The page lists entries newest first, 100 at a time (**Older entries** loads more), with the columns
**Time** (and the event's `#seq`), **Actor** (and the client address), **Action** (and its
category), **Target**, **Outcome** (`success`, `failure` or `denied`, with a **warning** or
**alert** badge for those severities) and **Detail** (the reason and the record's JSON). The page
has no search or filter; the API (`GET /api/v1/audit?before=<id>&limit=<n>`, up to 500 a page)
pages through it the same way.

From the page:

- **Export** downloads the whole trail as NDJSON (`ens-audit/1` events, newest first), for
  `tools/audit/verify_chain.py` or another tool. The export is recorded first, as `data.exported`,
  and refused when it cannot be. The API (`GET /api/v1/audit/export?from=&to=`) takes a time range;
  the button does not.
- **Verify chain** checks the hash chain (below).
- The **Delivery** card shows each enabled output (**stdout**, **file**, **splunk**, **syslog**):
  its state (**up to date**, **catching up**, **failing**), the `seq` delivered up to, the
  **Backlog**, the last delivery and the last error. With no output enabled it says the trail is
  kept in the database only.

The client address is the connection's, or, behind a reverse proxy listed in
`CONSOLE_TRUSTED_PROXIES`, the client named in its `X-Forwarded-For` header.

### Outputs, retention and the pull API

Every event is stored in the database first, then sent to each output by a background sender that
remembers how far it got. While Splunk or the syslog server is down, events queue and go out in
order once it is back; a restart resumes where it stopped; requests are never slowed or refused
because of an output. Delivery is at least once: drop duplicates by the event's `id`. After 15
minutes of failures the console records an `audit.delivery-failed` alert, and
`audit.delivery-recovered` when it catches up.

| Setting | Default | Meaning |
|---|---|---|
| `ENS_AUDIT_STDOUT` | `true` | Write each event as a JSON line on standard output (the journal, the pod log). Filter on `"schema":"ens-audit/1"` |
| `ENS_AUDIT_FILE` | unset | Also append to this file, rotated daily or at 100 MB |
| `ENS_AUDIT_FILE_KEEP` | `30` | Days of rotated files kept |
| `ENS_AUDIT_SPLUNK_URL`, `ENS_AUDIT_SPLUNK_TOKEN` (or `_TOKEN_FILE`), `ENS_AUDIT_SPLUNK_INDEX`, `ENS_AUDIT_SPLUNK_SOURCETYPE` | unset; sourcetype `ens:audit` | Splunk's HTTP Event Collector |
| `ENS_AUDIT_SYSLOG_HOST`, `ENS_AUDIT_SYSLOG_PORT`, `ENS_AUDIT_SYSLOG_PROTOCOL`, `ENS_AUDIT_SYSLOG_FACILITY` | unset; `514` (`6514` for TLS), `tcp`, `local0` | Syslog, RFC 5424, over `udp`, `tcp` or `tls` |
| `ENS_AUDIT_RETENTION_DAYS` | `0` (keep for good) | Remove events older than this, once a day: only events every enabled output has delivered, never the newest. Each run is recorded as `audit.pruned` |

The Splunk and syslog TLS connections trust the public roots. A SIEM can also pull events:
`GET /api/audit/events?after=<seq>` (or `/api/v1/audit/events`), with an
[API token](#api-tokens) that has `audit:read`, or an admin's or auditor's session. See
[Audit logging](../audit-logging.md#sending-events-to-splunk-or-another-siem).

### What is recorded

| Actions | When |
|---|---|
| `auth.login`, `auth.logout`, `auth.oidc.login` | Sign-ins (with the reason for a failure or refusal) and sign-outs |
| `user.bootstrap`, `bootstrap.password_file_removed` | The first `admin` account, and the initial password file being deleted |
| `user.create`, `user.update`, `user.password_reset`, `user.password_change`, `user.password_change_skipped` | Account changes (role, disabled and break-glass before and after). A reset from the command line has the actor `system`, and says whether the account signs in through single sign-on |
| `settings.local_sign_in`, `settings.config_approval`, `settings.banners` | Console settings, before and after |
| `engine.register`, `engine.update`, `engine.re-registered`, `engine.remove` | Engines added, registered again with new credentials or moved to another environment, registered again over a client key the master key no longer opens, and removed (`system` when the installer, `syncdctl renew-certs` or the Helm Job does it) |
| `engine.run`, `engine.approve_deletions`, `engine.check_drift`, `engine.dead_letters.retry`, `engine.dead_letters.discard`, `group.reviewed` | Operator commands, with the request and the engine's answer or error. **Approve and apply** on held changes is an `engine.approve_deletions` with the publication's scope |
| `correlation.join`, `correlation.unjoin`, `correlation.accepted` | Records joined or unjoined by hand, and suggestions accepted |
| `config.save`, `config.import`, `config.import_file`, `config.export` | Versions saved, imported or exported |
| `config.approve` | Approvals, and refused approvals |
| `config.publish.start`, `config.publish` | A publish is recorded before it is sent and again with its outcome, with the ticket and the hook's decision; a publish the engine confirms late is settled with `config.publish` |
| `config.promoted` | A version promoted to an engine of another environment |
| `config.git-synced`, `config.git-failed`, `config.git-refused` | Git sync |
| `environment.changed`, `environment.removed` | Environments (keys, never values) |
| `config.simulate`, `config.preview`, `connector.test`, `connector.discover` | The designer checks that make the engine read data |
| `explore.search`, `explore.entity`, `explore.history`, `explore.unjoined`, `explore.suggestions`, `explore.reveal` | Every object-explorer search (with its text), entity viewed, history read, correlation listing; reveals |
| `data.exported` | Report, lineage and audit-trail downloads (format, scope, row count) |
| `report.created`, `report.changed`, `report.deleted`, `report.sent` | Scheduled reports |
| `api.token-created`, `api.token-revoked`, `api.token-refused`, `api.token-used`, `api.request` | API tokens and what they do |
| `webhook.created`, `webhook.changed`, `webhook.deleted`, `webhook.secret-rotated`, `webhook.test-sent` | Webhooks (URLs as scheme and host only) |
| `password.self_service` | Self-service password changes and their refusals |
| `support.bundle-exported` | The console's support bundle downloaded |
| `audit.format-upgraded`, `audit.verified`, `audit.pruned`, `audit.delivery-failed`, `audit.delivery-recovered` | The trail itself: the switch to `ens-audit/1`, a broken chain found by **Verify chain**, retention, outputs failing and recovering |

Some changes and their records are written in one transaction, so if the audit trail cannot be
written the change is refused ("the audit log cannot be written, so this was not done"): account
creation, changes and resets, settings, approvals, environments, the start of a publish, exports
and reveals. Other records are written after the action; if that write fails, the console logs an
error instead.

### Verifying the chain

The table is append-only: database triggers refuse updates, deletes and truncation (retention
removes old events through its own path). Each event also stores the SHA-256 of the previous
event's hash and its own content, so an event changed or removed behind the triggers (by someone
with direct database access) breaks the chain.

**Verify chain** recomputes the whole chain (`GET /api/v1/audit/verify`). Verification starts
from the oldest event kept and can take a while on a large trail. A broken chain is recorded as an
`audit.verified` alert. Outside the console, `python3 tools/audit/verify_chain.py audit-export.ndjson`
checks an export or an audit file.

- **Entries from before 0.4.0** keep their earlier chain and have no `seq`. At the first start on
  0.4.0 the console recorded `audit.format-upgraded`, whose `prevHash` is the old chain's head, so
  the old chain ends where the new one begins. **Verify chain** checks both and says how many old
  entries there are. The page lists them; exports and the pull API do not (they are not
  `ens-audit/1` events).
- **After retention**, the result says from which `seq` the chain was checked.

The chain shows edits and removals of entries that have later entries after them. Removing the
newest entries leaves a shorter chain that still verifies on its own. To catch that, the console
records the chain's **head** (its newest entry's id, time and hash) outside the database:

- every hour, while the trail has changed, the console writes a line `audit log head` with `id`,
  `at` and `hash` to its own log (on a host, the journal: `journalctl -u syncd-console | grep
  'audit log head'`). Keep those lines outside the console database, in a SIEM or log archive. An
  [output](#outputs-retention-and-the-pull-api) to Splunk or syslog also keeps a copy of every event
  outside the database;
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

The environment file matters: it carries the database URL, the master key's location and, on a
FIPS installation, `SYNCD_FIPS_MODE`, which decides how the new password is hashed.

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
  [--server-ca CA.pem --client-cert CERT.pem --client-key KEY.pem] \
  [--environment NAME] [--update]
```

Registers an engine as the **Register engine** page does, checking that it answers first. The three
PEM files are needed together, and are required unless the address is a loopback one or a `unix:`
control socket (which takes none). `--environment` names the engine's
[environment](#environments) (`default` unless given).

When an engine with the name is registered already:

| Case | Without `--update` | With `--update` |
|---|---|---|
| Its stored client key opens with the master key | Says so ("an engine named … exists") and changes nothing | Replaces the address, credentials and environment, keeping the engine's id and configuration versions. Recorded as `engine.update` |
| Its stored client key no longer opens (the master key was replaced) | **Refused**, saying why, instead of "registered already" | Seals the new key over the old one, keeping the id and versions. Recorded as `engine.re-registered`, with the reason |

`POST /api/v1/engines` does the same, with `replace: true` for `--update`. `syncdctl install` uses
the command to register the local engine; `syncdctl renew-certs` and the Helm chart's registration
Job use it with `--update` (see the warning in
[Certificate expiry and renewal](#certificate-expiry-and-renewal) about the environment). New
registrations are recorded as `engine.register` by `system`.

There is no command to create accounts, change roles or change the console settings; those are done
in the console or through the [API](api-reference.md).

## Settings that are not in the console

These are set in the console's environment (`/etc/syncd/console.env`) by whoever runs the host, and
take effect when the console restarts. An upgrade leaves `/etc/syncd/console.env` as it is; the
release's template, `/opt/syncd/current/share/config/console.env`, has commented lines for the
settings new in 0.4.0, and none of them is needed. See [Configuration](configuration.md).

| Setting | What it controls |
|---|---|
| `CONSOLE_REVEAL_ROLES` | Which roles may reveal sensitive attributes (default `admin`) |
| `CONSOLE_OIDC_*`, `CONSOLE_PUBLIC_URL` | Single sign-on, the role claim and the role map |
| `CONSOLE_SESSION_IDLE_MINUTES`, `CONSOLE_SESSION_MAX_HOURS` | Session idle timeout (default 30 minutes) and maximum length (default 12 hours) |
| `CONSOLE_TRUSTED_PROXIES` | Reverse proxies whose `X-Forwarded-For` names the client |
| `CONSOLE_ADMIN_PASSWORD`, `CONSOLE_ADMIN_PASSWORD_FILE` | The first `admin` password, on an empty database only |
| `CONSOLE_TLS_CERT`, `CONSOLE_TLS_KEY` | A certificate (PEM, with its chain) and key for the console to serve HTTPS itself, without a TLS reverse proxy. Both or neither. The files are read again within a minute when they change, so a renewal needs no restart |
| `CONSOLE_MASTER_KEY`, `CONSOLE_NEW_MASTER_KEY` | The master key that seals the engines' client keys, webhook URLs and secrets, and secret environment values (otherwise `$CONSOLE_DATA_DIR/master.key`), and whether to start without the right one; see [The master key](#the-master-key) |
| `ENS_AUDIT_*` | The audit trail's outputs and retention; see [Outputs, retention and the pull API](#outputs-retention-and-the-pull-api) |
| `CONSOLE_API_TOKENS_ENABLED`, `CONSOLE_API_MAX_TOKEN_LIFETIME`, `CONSOLE_API_REQUESTS_PER_MINUTE` | [API tokens](#api-tokens): on or off (default on), the longest lifetime (default `365d`; `0` removes the limit) and the rate limit per token (default 300 a minute; `0` turns it off) |
| `CONSOLE_WEBHOOKS_*` | [Webhooks](webhooks.md): on or off, allowed hosts, private networks, extra CAs, timings |
| `CONSOLE_PUBLISH_*` | [The pre-publish hook and change tickets](#the-pre-publish-hook-and-change-tickets) |
| `CONSOLE_CONFIG_GIT_*` | [Git sync](#git-sync) |
| `CONSOLE_REPORTS_*`, `CONSOLE_EXPORTS_MAX_ROWS`, `CONSOLE_SMTP_*` | [Scheduled reports and mail](#scheduled-reports-and-mail) |
| `CONSOLE_SELF_SERVICE_ENGINE` | [The self-service password page](#the-self-service-password-page) |
| `SYNCD_FIPS_MODE` | FIPS mode, which changes password hashing |

The console checks the new settings when it starts and refuses to start with one it cannot use (a
hook URL that is not `https://`, a Git URL over plain `http://`, a share directory that does not
exist), saying which.

The console's part of a support bundle (its version, settings with secret values redacted, schema
migrations and engines) is `GET /api/v1/admin/support-bundle` (admins, signed in; there is no
button), recorded as `support.bundle-exported`. On a host, `syncdctl support-bundle` collects the
same from the host beside the engine's own bundle; see the [Operator guide](operator-guide.md).

### The master key

The console seals the client keys of engines registered with mutual TLS, the URLs and signing
secrets of webhooks, and secret environment values with its master key: `CONSOLE_MASTER_KEY`, or
the file `$CONSOLE_DATA_DIR/master.key` (`/var/lib/syncd/console/master.key` on a host; the
`syncd-console` Secret under Helm). The console creates the file on its first start. Back it up
with the database: the sealed values cannot be recovered without it.

At each start the console checks the key against an engine's sealed client key in its database. It
refuses to start when:

- `master.key` is missing while the database holds sealed keys (a restore that missed it, a new
  data directory). It does not create a new key over them;
- the key does not open them: it is not the key they were sealed with.

Either way, restore `master.key` from a backup, or set `CONSOLE_MASTER_KEY` to the old key. To
start anyway, set `CONSOLE_NEW_MASTER_KEY=true` (or pass `--new-master-key`): the console starts
with a new key if it has none, and everything sealed with the old key is lost:

- every engine registered with mutual TLS must be registered again: `register-engine --update`
  (recorded as `engine.re-registered`), or `sudo syncdctl renew-certs` for the local engine. A
  plain registration is refused, saying the stored key no longer opens. Under Helm,
  `console.newMasterKey=true` does this with the registration Job (see the
  [Install guide](install.md));
- webhooks are listed with their URL marked as unreadable and their deliveries fail: save each
  URL again and choose **New secret**;
- secret environment values must be typed again on the **Integrations** page.

Keep the setting until the engines are registered again: until then the old sealed keys are still
in the database, and the console, and the `syncd-console admin` commands run with its environment,
refuse to start without it. Remove it afterwards, so that a lost key is caught again. The start-up
check looks only at engines' client keys: a console with webhooks or secret environment values but
no mTLS engine is not stopped by a wrong key.
