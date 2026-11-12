# Admin guide

This guide is for **console administrators**: people holding the `admin` role on everything in the
syncd web console (`syncd-console`). It covers what an administrator does in the console and with the
`syncd-console admin` commands: accounts, roles and their scopes, sign-in policy, configuration
approval and the pre-publish hook, banners, engines and HA pairs, the **Integrations** page (API
tokens, environments, webhooks), Git sync, scheduled reports and mail, the self-service password
page, and the audit trail. The license and the **License** page are in [Licensing](licensing.md).
Installing, upgrading and backing up are in the [Install guide](install.md) and the
[Operator guide](operator-guide.md); the screens designers and operators use every day are in the
[User guide](user-guide.md).

This page describes syncd 0.5.0 (`main` at commit `a9d17c6`). Since 0.4.1, roles are held as
**assignments** that can be limited to an environment or an engine, and role maps add up; see
[Roles](#roles).

## Becoming an administrator

The console keeps its own accounts in its PostgreSQL database. A **console administrator** is an
account with the `admin` role **on everything**; an `admin` role limited to an environment or an
engine makes an administrator of that only (see [Scopes](#scopes)). There are three ways to hold
`admin` on everything:

| Account | How it gets `admin` |
|---|---|
| The built-in `admin` account | Created the first time the console starts on an empty database. It is the only account until an administrator makes others |
| A local account | An administrator creates it, or changes its roles, on the **Users** page |
| An account that signs in through an identity provider or a certificate | A role map entry that matches gives it `admin` (`CONSOLE_OIDC_ROLE_MAP`, `CONSOLE_SAML_ROLE_MAP` or `ENS_X509_ROLE_MAP`), or the matching `*_DEFAULT_ROLE` is `admin`. The roles are set again at every sign-in. See [Single sign-on](single-sign-on.md) |

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

You can tell your roles from the header, which shows your name and your role (for example
`Administrator · admin`, or `operator on prod` for a role limited to an environment), or how many
roles you hold (`2 roles`, with the list on hover). The navigation depends on the roles:

| Role | Navigation |
|---|---|
| admin on everything | **Engines**, **Users**, **Audit log**, **Reports**, **Integrations**, **License** |
| admin of an environment or an engine | **Engines**, **Audit log**, **Reports**, **Integrations** |
| auditor (in any scope) | **Engines**, **Audit log**, **Reports**, **Integrations** (read-only) |
| operator (in any scope) | **Engines**, **Reports** |
| designer, viewer | **Engines** |

An account holding several roles gets every entry any of them gives.

## Roles

The console has five roles. An account holds one or more **role assignments**: each a role, and
what it applies to (everything, one environment, or one engine). The assignments add up.

| Role | In short |
|---|---|
| **admin** | Everything, including accounts, engines, API tokens, webhooks, environments and console settings |
| **designer** | Reads everything a viewer reads; edits, checks, previews, simulates, approves, publishes and promotes configuration; runs connector checks; uploads and approves modules of custom functions; syncs from Git |
| **operator** | Reads everything a viewer reads; runs and controls engines: reconciliations, full syncs, drift checks, unmanaged-entry checks and adoptions, threshold approvals, held changes, group reviews, dead letters, manual joins, unjoins and accepted correlation suggestions; schedules reports |
| **viewer** | Reads engines, their status, configuration versions, the object explorer and data quality, and exports reports and lineage |
| **auditor** | Reads everything a viewer reads, plus the audit trail, the API token and webhook lists, the environments' values and the scheduled reports |

One role does not include another: a designer cannot run a full sync and an operator cannot publish
a configuration. Give someone who needs both the two roles (`designer` and `operator`), or `admin`.
The console checks every request on the server; the UI only hides what would be refused. Until
0.5.0 an account held exactly one role, on everything; at the upgrade every account kept its role,
on everything.

### Scopes

Each assignment has a scope, written after the role in role maps and settings:

| Scope | Written | Covers |
|---|---|---|
| Everything | `operator` or `operator@*` | Every engine and environment, and the console itself |
| An environment | `operator@prod` | The engines running in that environment, and its values |
| An engine | `designer@engine:hr-1` | That engine, wherever it runs; not its environment's values. `designer@engine:hr-1,hr-2` in a role map names several |

- **An engine outside every assignment does not exist** for the account: it is in no list, and its
  pages and API answer `404`, as for an id never used. An action none of the account's roles allows
  is `403`.
- **Lists show what the account reaches**: engines, environment names, environments with values (for
  an admin or auditor of everything or of that environment), scheduled reports (of engines the
  account operates or audits), API tokens and the audit page.
- **Global only, on purpose:** accounts and console settings, webhooks, Git sync, registering,
  moving and removing engines, the **License** page, and the audit trail as a whole (verifying,
  exporting and pulling it, and its outputs). These need the role on everything.
- **Views are not a scope.** The smallest scope is an engine: to give a team one population only,
  give that population its own engine. An HA pair is two registrations (`<name>-0`, `<name>-1`):
  scope by environment, or name both engines.
- **Moving an engine** to another environment takes it out of reach of the roles on its old
  environment and into reach of those on the new one. Roles on the engine by name stay with it,
  and follow [`rename-engine`](#syncd-console-admin-rename-engine).
- **Who counts as an administrator.** Only accounts with `admin` on everything manage accounts and
  settings, count for "the last admin cannot be demoted, limited to a scope or disabled" and for
  break-glass sign-in, and get the administrators' idle limit. An admin of an environment or an
  engine is an administrator of that: its environment values, API tokens limited to it, and other
  people's scheduled reports on it.
- **The second pair of eyes** for [configuration approval](#configuration-approval-four-eyes) is a
  designer or admin whose role reaches the version's engine.

Some examples:

| Need | Assignments (and the same as a role map entry) |
|---|---|
| A designer for the HR engine who cannot edit the contractors' engine | `designer` on engine `hr-1` (`hr-designers=designer@engine:hr-1`) |
| Operators of the test environment who may watch production but not act there | `operator` on `test` and `viewer` on `prod` (`syncd-test-ops=operator@test,syncd-test-ops=viewer@prod`): an approval in production is refused |
| Auditors of production only | `auditor` on `prod` (`prod-auditors=auditor@prod`): production's engines, its values, the audit entries about them, tokens limited to it; not the trail's export or verification |
| A team that runs its own engine without seeing the others | `admin` on engine `team-a`: everything on that engine, and API tokens limited to it. Registering the engine and the team's first account stay with a console administrator |

### Permissions matrix

What each role may do, from the console's table of API operations (`console/server/src/api/mod.rs`),
which gives every operation one rule before its handler runs. A ✓ holds on the engines (or the
environment) the role's assignment reaches; rows marked *everything* need the role on everything.

| Action | admin | designer | operator | viewer | auditor |
|---|:-:|:-:|:-:|:-:|:-:|
| See engines, live status, metrics, dead letters, held changes, groups, data quality, unmanaged entries, custom function modules | ✓ | ✓ | ✓ | ✓ | ✓ |
| Download the reconciliation report (**Report CSV**, **Excel**, **PDF**) and an entity's lineage (**Lineage JSON**, **PDF**) | ✓ | ✓ | ✓ | ✓ | ✓ |
| Read configuration versions, compare them (also by meaning), see what the engine runs, see a version's packages, export a version to a file | ✓ | ✓ | ✓ | ✓ | ✓ |
| Open the Computed Attribute Studio, preview rules and try a quality check on a sample (read-only unless designer) | ✓ | ✓ | ✓ | ✓ | ✓ |
| Search the object explorer, open entities and their history, see correlation suggestions | ✓ | ✓ | ✓ | ✓ | ✓ |
| See the Git card (*everything*) | ✓ | ✓ | ✓ | ✓ | ✓ |
| Check a configuration as it is typed | ✓ | ✓ | | | |
| Save a version, import the running configuration, import a file | ✓ | ✓ | | | |
| **Sync now** from Git (*everything*) | ✓ | ✓ | | | |
| Test a connection, browse a connector, read sample records, live preview | ✓ | ✓ | | | |
| Simulate a version | ✓ | ✓ | | | |
| Upload a module of custom functions; approve one (not one you uploaded) | ✓ | ✓ | | | |
| Approve a version (not your own) | ✓ | ✓ | | | |
| Publish a version; promote one to another environment (**Promote**, the API, `syncd config promote`; the role must reach every engine of the target environment) | ✓ | ✓ | | | |
| Reconcile, full sync, drift check; **Check now** for unmanaged entries; **Adopt** an entry | ✓ | | ✓ | | |
| Let deletes held by a deletion threshold through; **Approve and apply** held changes (windows, thresholds, canaries; see [Change safety](change-safety.md#approving)) | ✓ | | ✓ | | |
| **Review** a group ([group lifecycle](change-safety.md#review)) | ✓ | | ✓ | | |
| Retry or discard dead letters | ✓ | | ✓ | | |
| Join a record to an entity, unjoin a record, accept correlation suggestions | ✓ | | ✓ | | |
| See the scheduled reports | ✓ | | ✓ | | ✓ |
| Create, change, send now and delete scheduled reports | Anyone's | | Their own | | |
| Read the audit page, filter it, **Export CSV** | ✓ | | | | ✓ |
| Verify the audit chain, export it as NDJSON, pull it, see its outputs' delivery (*everything*) | ✓ | | | | ✓ |
| See the API tokens | ✓ | | | | ✓ |
| See the webhooks, with each webhook's delivery log (*everything*) | ✓ | | | | ✓ |
| Read the environments' names (**Rendered for** on the **Compare** tab) | ✓ | ✓ | ✓ | ✓ | ✓ |
| Read the environments' values (the **Integrations** page and the API) | ✓ | | | | ✓ |
| Set an environment's values | ✓ | | | | |
| Create and revoke API tokens (limited to what you administer) | ✓ | | | | |
| Add environments; add, change, test and delete webhooks (*everything*) | ✓ | | | | |
| Create, change, disable, unlock and reset accounts (*everything*) | ✓ | | | | |
| Local sign-in, configuration approval, banners (*everything*) | ✓ | | | | |
| The **License** page: install, replace, remove the license, export usage (*everything*) | ✓ | | | | |
| Register and remove engines; move an engine to another environment (API) (*everything*) | ✓ | | | | |
| Download the console's support bundle (**Users** page, API) (*everything*) | ✓ | | | | |
| Reveal sensitive attributes | Roles listed in `CONSOLE_REVEAL_ROLES` (default: `admin` only), on the engines their assignment reaches; see below | | | | |

Anyone signed in may read their own account, sign out and change their own password, and read the
license's state and what it locks (`GET /api/v1/license/entitlements`, which the banner and the
editors use). The sign-in page, the version, the banners, the consent notice and the publish settings
(`GET /api/v1/system`) need no sign-in, nor does the
[self-service password page](#the-self-service-password-page).

The environments' values, and the names of their secret ones (`GET /api/v1/environments` and
`/api/v1/environments/{name}`), need the admin or auditor role on everything or on that environment,
or a token with the `environments:read` scope (or `admin`). Every role reads the names of the
environments it reaches (`GET /api/v1/environment-names`, the `configs:read` scope). A configuration
rendered for display (the editor's check, **Compare**) still shows the plain values it uses, never
the secret ones.

A refused request answers `403` with a message such as "the operator role cannot do this", "none of
your roles (operator@test, viewer@prod) allows this on …", or "this needs a role that covers
everything; yours (…) are limited to an environment or to engines". Refusals of this kind are not
written to the audit trail when they come from a signed-in session; refused sign-ins, refused
reveals and refused API tokens (`api.token-refused`) are. Every action that is recorded carries the
assignment that allowed it (`details.assignment`, such as `operator@prod`).

### Revealing sensitive attributes

Attributes listed in an engine configuration's `attributes.sensitive` come without their values in
the object explorer, correlation review, drift findings, simulations, reports and lineage exports,
and are not searched. What derives from them is hidden too: computed attributes that read them,
source fields that flow into them, and target attributes built from them. DNs are not masked, so do
not build DNs from sensitive attributes.

Roles listed in the console setting `CONSOLE_REVEAL_ROLES` may ask to see the values. It is a
comma-separated list of role names, or `none`; the default is `admin`. An account reveals on an
engine when one of its assignments that reach the engine has a reveal role. An entry can also limit
where its role reveals, with a scope written as in the role maps: `admin,auditor@prod` lets auditors
reveal on production's engines only, and `operator@engine:lab` lets operators reveal on that one
engine (repeat the entry for more). A value that is not a role, or a scope that cannot be read,
stops the console from starting. Accounts that may reveal get a **Reveal sensitive
attributes** button on an entity, an **Include sensitive attributes (audited)** checkbox on
searches and correlation review, and **Show sensitive values (audited)** on simulations. A lineage
export made while an entity's values are revealed includes them.

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

For administrators of everything. At the top are four cards: **Local sign-in** (with the sign-in
policy in effect), **Configuration approval** and **Banners**, described in the sections below, and
**Support bundle** (see [Settings that are not in the console](#settings-that-are-not-in-the-console)).
Under them is the table of accounts:

| Column | Shows |
|---|---|
| **User** | Username and display name |
| **Roles** | Each of the account's assignments as a badge (`admin`, `operator on prod`, `designer on engine hr-1`), and **change**, which opens the account's roles dialog |
| **State** | **active** or **disabled**, plus **locked** (too many failed sign-ins, with an **unlock** link), **one-time password** (must change it at the next sign-in) and **SSO** (signs in through an identity provider or a certificate) |
| **Break-glass** | A checkbox, for accounts with a local password (see [Local sign-in and break-glass accounts](#local-sign-in-and-break-glass-accounts)) |
| **Created** | When the account was made |
| Actions | A key button that resets the password (local accounts only), and **Disable** / **Enable** (not on your own row) |

### Creating an account

Choose **New user** and fill in:

| Field | Rules |
|---|---|
| **Username** | 1–64 characters: letters, digits, `.`, `_`, `@`, `-`. Must be unused |
| **Display name** | Optional |
| **Roles** | One row per assignment: a role, and **on everything**, **on an environment** or **on an engine** with its name (the field suggests the names). **Add a role** adds a row; an account needs at least one, and holds at most 100. One row, **viewer** on everything, is preselected |

**Create** shows a 20-character **one-time password** once. Give it to the user over a trusted
channel; they must choose their own at the first sign-in. The account and its record in the audit
trail (`user.create`, with the roles) are written together, so an account never exists without its
record.

### Changing roles, disabling and enabling

- **Roles**: **change** in the row opens **Roles of &lt;user&gt;**, the same rows as at creation;
  **Save roles** applies them from the account's next request; the account stays signed in.
  Recorded as `user.update` with the roles before and after. For an account that signs in through
  an identity provider or a certificate, the dialog says that the roles are set from the role map at
  each sign-in, so a change lasts until the next one.
- **Disable**: the account can no longer sign in, by password, single sign-on or certificate, and its
  sessions end at once (a live status stream it has open stops too). **Enable** lets it back in.

Both also apply to the account's [API tokens](#api-tokens) at once: a disabled creator's tokens are
refused, and a demoted creator's tokens keep only what the new roles allow.

The API takes the same as `"assignments": [{"role": "operator", "scope": "environment", "name":
"prod"}, ...]` on `POST /api/v1/users` and `PATCH /api/v1/users/{id}`; `"role": "viewer"` is still
accepted as one role on everything.

The console refuses changes that would lock everyone out:

- the last enabled administrator of everything cannot be demoted, limited to a scope or disabled
  ("make another admin first");
- while local sign-in is limited to break-glass accounts, the last enabled break-glass admin with
  a password cannot be unmarked, demoted or disabled.

Accounts are never deleted; disable those that should no longer sign in.

### Password resets and lockouts

The key button issues a new one-time password for a local account. It also clears the failed
sign-in count, unlocks the account, and ends its sessions. Recorded as `user.password_reset`. An
account that signs in through single sign-on or a certificate has no console password and cannot be
reset here (nor from the command line, unless told to; see
[`syncd-console admin reset-password`](#syncd-console-admin-reset-password)).

**unlock** beside a **locked** badge lifts the lock and clears the failed sign-in count, keeping the
account's password and sessions (recorded as `user.update` with `unlocked`). It is the way out of a
lock that lasts until an administrator ends it (`ENS_LOCKOUT_DURATION=0`, below).

Every failed sign-in gets the same answer, so it reveals nothing about the account; a locked
account gets the same answer as a wrong password. The audit trail records the reason (`unknown
account`, `wrong password`, `locked`, `disabled`, `no local password`, `local sign-in is limited to
break-glass accounts`, `rate limited`). Besides the lockout below, a client address may make at most
20 sign-in attempts in 5 minutes; that limit is fixed in the code.

Passwords are hashed with Argon2id, or with PBKDF2-HMAC-SHA512 when the console runs in FIPS mode.
FIPS mode does not accept Argon2id hashes, so accounts created before FIPS mode was turned on need
a password reset. See [FIPS 140-3 and DISA STIG](fips-and-stig.md).

### Sign-in policy

The sign-in baseline the console shares with the other ENS products, under the family's `ENS_*`
names, is set in the console's environment (`/etc/syncd/console.env`) and read at start. Unset, every
setting keeps what 0.4.1 did, so an upgrade changes nothing until they are set. **Users** →
**Local sign-in** → **Sign-in policy in effect** shows what applies, read-only.

| Setting | Default | Meaning |
|---|---|---|
| `ENS_CONSENT_BANNER_ENABLED` | `false` | Show a notice that must be acknowledged (**I acknowledge and consent**) before any sign-in, by password, single sign-on or certificate |
| `ENS_CONSENT_BANNER_TEXT`, `ENS_CONSENT_BANNER_TEXT_FILE` | the DoD notice | The notice's text, or a file holding it (for several paragraphs) |
| `CONSOLE_SESSION_IDLE_MINUTES`, `CONSOLE_SESSION_MAX_HOURS` | `30`, `12` | Idle and absolute session limits |
| `ENS_SESSION_ADMIN_IDLE_TIMEOUT` | unset | A shorter idle limit for administrators of everything (`10`, `10m`, `600s`) |
| `ENS_SESSION_MAX_PER_USER` | `0` (no limit) | Sessions one account may have; one more ends its oldest (`auth.session.ended`) |
| `ENS_LOCKOUT_ATTEMPTS` | `5` | Failed sign-ins after which a local account locks; `0` never locks |
| `ENS_LOCKOUT_WINDOW` | `0` | The time those failures must fall within (`15`, `15m`); `0` counts them until a sign-in succeeds |
| `ENS_LOCKOUT_DURATION` | `15` | How long the lock lasts (`15`, `15m`, `1h`); `0` until an administrator unlocks the account or resets its password |
| `ENS_PASSWORD_MIN_LENGTH` | `12` | The fewest characters of a local password (at least 8) |
| `ENS_PASSWORD_MIN_UPPERCASE`, `_MIN_LOWERCASE`, `_MIN_DIGITS`, `_MIN_SPECIAL` | `0` | The fewest characters of each kind ("special" is anything neither a letter nor a digit) |
| `ENS_PASSWORD_HISTORY` | `0` | How many of the account's latest passwords, the current one included, a new one must differ from |
| `ENS_PASSWORD_MAX_AGE_DAYS` | `0` (never) | Days after which the password must be changed at the next sign-in (`user.password_expired`) |
| `ENS_PASSWORD_REJECT_USERNAME` | `false` | Refuse a password that contains the username (of three characters or more) |
| `ENS_HSTS_MAX_AGE`, `ENS_HSTS_INCLUDE_SUBDOMAINS` | a year, `false` | The `Strict-Transport-Security` header, sent only when the console serves HTTPS itself (`CONSOLE_TLS_CERT`); `0` sends none |

Whatever the policy, a new password is never the username or `admin` (ignoring case) and never the
current password. The rules apply when a person chooses a password and to an installer's
`CONSOLE_ADMIN_PASSWORD`; accounts that sign in through an identity provider or a certificate have no
console password, and none of this applies to them.

- **The consent banner** is enforced by the server, not the page: a password sign-in, the start of a
  single sign-on and a certificate sign-in are refused (`403`, "acknowledge the notice on the
  sign-in page before signing in") unless the browser acknowledged the notice in the last 15
  minutes, and one acknowledgement serves one sign-in. It is recorded as `auth.consent`, and the
  sign-in it leads to carries the same consent id. A script that signs in with a password must
  first `POST /api/v1/auth/consent` and send the cookie it sets. API tokens are not sign-ins and are
  not affected.
- The console's README gives values for a review against the DISA Application Security and
  Development STIG (`console/README.md`, "Values for a STIG review"); see also
  [FIPS 140-3 and DISA STIG](fips-and-stig.md). A minimum password lifetime and a minimum number of
  changed characters are not enforced.

SAML and certificate (CAC/PIV) sign-in, and their role maps, are in
[Single sign-on](single-sign-on.md).

If every administrator is locked out, use [`syncd-console admin reset-password`](#syncd-console-admin-reset-password)
on the console host.

### Changing your own password

Anyone with a local password can change their console password at `/change-password`: the current
password, the new one twice; the hint under the new password states the [sign-in policy's](#sign-in-policy)
rules. A forced change after a one-time password, or after the password reached
`ENS_PASSWORD_MAX_AGE_DAYS`, uses the same page. Changing
it signs out the account's other sessions and is recorded as `user.password_change` (a wrong current
password is recorded as a failure).

!!! note
    The console has no link to this page other than the forced change after a one-time password:
    type `/change-password` after the console's address. It changes the **console** password. The
    public `/password` page is a different thing: it changes people's **directory** passwords (see
    [The self-service password page](#the-self-service-password-page)).

### Accounts that sign in through single sign-on

With OpenID Connect, SAML or certificate sign-in set up, the first sign-in that way creates the
account, marked **SSO**. Such an account:

- has no console password: it cannot be reset, cannot change a password here, and cannot be
  break-glass. Only `syncd-console admin reset-password --allow-single-sign-on` gives it one;
- gets its roles from the role map at every sign-in: what the map gives for that sign-in's groups
  (or certificate), and nothing kept from before. **Every entry that matches adds its role**; until
  0.5.0 the first match won, so someone in two mapped groups may hold more than before. Review maps
  that relied on their order. Changing the roles on the **Users** page lasts only until the account
  next signs in;
- stays out if it is disabled here, whatever the provider says.

An identity whose username matches an existing local account is refused rather than signed in to
it. Setting up the providers and the role maps is described in [Single sign-on](single-sign-on.md).

## Local sign-in and break-glass accounts

Once single sign-on (OpenID Connect or SAML) or certificate sign-in is set up, an administrator can
stop local accounts from signing in with their passwords, except a few **break-glass** accounts kept
for when the identity provider is down. The
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
- the sign-in page shows only the **Sign in with &lt;provider&gt;** buttons (one per provider set up,
  and one for a certificate), with a small **Break-glass sign-in** link that opens the password form
  and warns that every attempt is audited.

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
- approving needs the designer or admin role on the version's engine (on everything, its
  environment or the engine itself). Operators, viewers and auditors cannot approve, and no API
  token can approve;
- versions that [Git sync](#git-sync) publishes, and versions [promoted](config-as-code.md#promotion) to
  another environment's engines, wait for an approval on each engine like any other.

!!! warning
    The rule lives in the console. It holds only if configurations reach the engine through the
    console alone. A new 0.5.0 install's engine is **strict** (`api.access`): only the console's
    certificate may apply a configuration, and `syncd apply-config` on the host is refused. An
    engine upgraded from 0.4.x stays **compatible** until its administrator runs
    `sudo syncdctl api-access strict`; until then every certificate of its CA, the command line's
    included, can apply a configuration without approval, and the engine's page in the console says
    so. With `engine.require_signed_config`, the engine also applies only configurations the console
    signed. Whatever an engine runs that the console did not publish is reported on its page and as
    `config.unpublished-running`. See [Security](security.md) and the [Install guide](install.md).

## The pre-publish hook and change tickets

Publishing can be made to carry a change-ticket reference, and to ask a change-management system
(an ITSM, a CAB workflow) first. Both are off unless set in the console's environment, and both
apply to every publish: the **Publish** button, an API token with `configs:publish`, Git sync's
auto-publish and promotion (the **Promote** button and `syncd config promote`).

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
  webhook) after the four-eyes, test and [license](licensing.md) checks, and before anything is
  recorded or sent to the engine. Its body names the ticket, the note, the version, the engine, who asked, and how many
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

## The license

The **License** page (`/license`, in the navigation for administrators of everything) shows the
license and its state, the managed identities against the tier with the high-water mark, the packs,
and the installation id a license request needs; it installs, replaces and removes the license and
exports usage for true-up. `syncd-console admin license` prints the same state and installation id
on the console host.

An installation upgraded from 0.4.1 has no license: it evaluates for 30 days from the first start of
0.5.0, then has a 30-day grace period, after which **publishing configuration changes is refused**
(about 60 days after the upgrade). Running sync never stops, and publishing again a version that was
published to that engine before (a rollback) is always allowed. While the license needs attention,
every page shows a banner (red once publishing is blocked), and the **Engines** page a line with the
usage against the tier. What the license does not include is locked in the editor and the Studio,
and refused when a configuration is validated, saved or published. States, packs, managed
identities and installing a license are in [Licensing](licensing.md).

## Engines

The console manages one or more engines. Each is registered by name and the address of its gRPC
control API, or of its control socket.

### Registering an engine

The local engine is registered for you:

| Install | Registered as |
|---|---|
| Package or bundle (`syncdctl install`) | `local` at `127.0.0.1:7443`, with the certificates `syncdctl` issued |
| Helm chart | The release's name (or `engine.name`) at the engine's service, port 7443, with the chart's certificates, in the environment `engine.environment` names (when set). A Job does it after each install and upgrade, with `--update`. With `engine.ha.enabled`, it registers both pods, as `<name>-0` and `<name>-1`; an engine registered as `<name>` before HA was turned on is first renamed `<name>-0` ([`rename-engine`](#syncd-console-admin-rename-engine)), keeping its versions |

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
`engine.update`, with the environment before and after. The engine also moves out of reach of the
roles on its old environment and into reach of those on the new one (see [Scopes](#scopes)).

What an engine's page tells an administrator, besides its status:

- **The engine's control API is in compatible mode** (amber): an engine upgraded from 0.4.x whose
  `api.access` is not strict. Every client certificate its CA signed may apply a configuration and
  approve held changes without the console. `sudo syncdctl api-access strict` on its host opts in;
  then import the running configuration on its **Configuration** page.
- A line saying who applied the running configuration and when, whether it was **signed** (and by
  which key), the access mode, and **signed configurations only** when the engine has
  `engine.require_signed_config`.
- **The engine runs a configuration this console did not publish**: what runs differs from the
  version the console last published to it (applied through the control API around the console, or
  a file edited on the host). It is also recorded as `config.unpublished-running` and raised as a
  webhook event, and stays until a version is published again.
- **License**: what the running configuration uses that the license leaves out, or the license's
  problems.

On the **Engines** page, administrators of everything also see a notice for each setting of the
console's environment that names no registered engine (`CONSOLE_SELF_SERVICE_ENGINE`, and
`CONSOLE_CONFIG_GIT_ENGINES` when Git sync is on), with what to change. The console logs the same
warning at start. Such a setting cannot be changed from the console: change it in `console.env` and
restart, or register the engine.

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

Replacing a registration keeps the engine's configuration versions and its environment (unless
`--environment` names another), and is recorded as `engine.update`. In 0.4.0 a re-registration
without `--environment` moved the engine back to `default`, so a certificate renewal or a Helm
upgrade did; since 0.4.1 it no longer does.

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
**Webhooks**. An administrator or auditor of an environment or of engines sees the tokens limited to
what they reach and the environments they reach; the **Webhooks** card is for roles on everything.

### API tokens

Other systems call the console's API with a token instead of a session: a SIEM pulling the audit
trail, a pipeline promoting configuration between environments. The **API tokens** card lists each
token's name, the start of the token (`sdt_…`), its scopes, what it is **Limited to** (an
environment, engines, or **no limit**), who created it, when it expires, when it was last used and
its state (**active**, **expired**, **revoked**).

To create one, choose **New token**:

| Field | Rules |
|---|---|
| **Name** | What uses it, such as the SIEM or the pipeline. Up to 100 characters |
| **Scopes** | One or more, below. Each needs a role of its creator |
| **Limited to** | **no limit: whatever its creator reaches** (offered to administrators of everything only), **one environment** (its engines and its values), or **engines by name** (ticked in a list). You must be an administrator of all the token reaches |
| **Expires** | Optional: a date (the token stops at the end of that day, UTC). Left empty, it gets the longest lifetime allowed (`CONSOLE_API_MAX_TOKEN_LIFETIME`, 365 days by default), which is also the most that may be asked for |

**Create** shows the token **once**, with a **Copy** button. The console keeps only its SHA-256; it
is never written to a log or the audit trail. **Revoke** on a row asks for confirmation, then
stops the token at once.

| Scope | Needs the creator to be | Allows |
|---|---|---|
| `engines:read` | any role | The engines, their status, dead letters, quarantine, held changes, data quality, the reconciliation report |
| `configs:read` | any role | Configuration versions, the running configuration, diffs, exports, packages, the environments' names, Git sync status |
| `configs:write` | designer or admin | Validate, save and import a version; upload a module of custom functions; Git **Sync now** (not publish) |
| `configs:publish` | designer or admin | Publish a version; promote one (approval, of versions and of modules, stays with people) |
| `runs:run` | operator or admin | Start runs and drift checks; approve held deletions and changes; review groups; accept correlation suggestions; retry and discard dead letters |
| `audit:read` | auditor or admin | The audit trail, its verification, the pull API, the export and the outputs' status |
| `environments:read` | auditor or admin | The environments: their plain values, the names of their secret ones, and their engines (new in 0.4.1) |
| `admin` | admin | Everything above |

A token never does more than its creator **now**, within its limit:

- on every use the creator's account is read: a creator who is disabled or gone makes the token
  fail (`401`); otherwise the token does no more than the creator's role assignments allow today,
  so a demoted creator's token keeps its reads and loses its writes (`403`), and one whose creator
  was moved to another environment reaches nothing. A good answer is reused for up to a minute,
  and dropped when the account changes;
- a limited token does nothing on the console as a whole (no audit pull or export, no Git sync),
  and an engine outside its limit does not exist for it (`404`). The limit follows an engine's
  rename;
- no token, whatever its scopes, can manage tokens, webhooks, accounts, settings or scheduled
  reports, sign in through single sign-on, approve a version or a module, simulate or preview a
  version, test connectors, use the object explorer or export lineage: those stay with people.

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

The **Environments** card, shown to administrators and auditors, lists each environment they reach
with its values (secret ones shown only as **(secret)**), the engines that run in it and when it was
last saved. Administrators of everything choose **New environment**; an administrator of everything
or of that environment chooses **Edit**:

| Field | Rules |
|---|---|
| **Name** | 1–64 lower-case letters, digits, `-` and `_`, starting with a letter or digit. New environments only |
| **Key** / **Value** | One row per value; **Add a value** adds a row. A whole number or `true` / `false` keeps its type, so a whole-value placeholder such as `port: "{{ env.hr_port }}"` renders as a number |
| **Secret** | Sealed with the console's master key when saved and never shown again, not even to administrators. On **Edit**, a secret left blank keeps its stored value; typing replaces it |

**Save** records `environment.changed`, with the keys added, removed and changed, never a value.
An environment no engine runs in can be removed with the bin button, after a confirmation
(`environment.removed`).
Engines that are given no environment run in `default`, which needs no entry here.

- A publish to an engine whose environment lacks a value the version needs is refused, naming the
  value.
- Secret values are rendered into the configuration sent to the engine; the console shows them as
  `<secret name>` everywhere else (the **Compare** tab, the check panel). Plain values show as they
  are there, to every role that reads configuration.
- With [Git sync](#git-sync), environment files in the repository can set plain values, but a
  file that carries a secret is refused.

### Webhooks

The **Webhooks** card sends signed HTTPS posts to your own integration, a Slack channel, a Microsoft
Teams workflow or PagerDuty when something happens: an engine, source or target going down or
coming back, runs, rising dead letters or quarantine, held changes, failed password deliveries,
expired groups and group reviews due, ageing unmatched records, rising data-quality errors, failed
reports or backups, an HA failover, a change of the license's state, and the configuration
lifecycle (a configuration running that the console did not publish included). Nothing is sent until
an administrator adds a webhook, and an existing webhook gets the events new in 0.5.0 only once
they are added to its list. Webhooks get every engine's events, so they are for administrators (and,
to look, auditors) of everything.

Administrators choose **New webhook**, give a **Name**, a **Format** (`json`, `slack`, `teams` or
`pagerduty`), an `https://` **URL** and the **Events**, and are shown the **signing secret**
(`whsec_…`) once. A `pagerduty` webhook asks for the integration's **Routing key** instead, which
is its secret. Each row has **Deliveries** (the delivery log; auditors too), **Send a test**,
**Edit**, **New secret** (**Routing key** for PagerDuty) and **Delete** (with a confirmation; the
delivery log goes too). **New secret** offers **Generate** (a random one, shown once) or a secret
the receiver already has (16 to 200 characters without spaces, **Use this secret**).

- The URL is checked when saved and at every send: never loopback, link-local or a cloud metadata
  address; private addresses only as `CONSOLE_WEBHOOKS_BLOCK_PRIVATE_NETWORKS` and
  `CONSOLE_WEBHOOKS_ALLOWED_HOSTS` allow.
- A Slack or Teams URL is itself a credential: only administrators see it whole. Auditors, and the
  audit trail, see its scheme and host. URLs and secrets are sealed with the console's master key.
- Changes are audited: `webhook.created`, `webhook.changed`, `webhook.deleted`,
  `webhook.secret-rotated`, `webhook.test-sent`.
- `CONSOLE_WEBHOOKS_ENABLED=false` queues and posts nothing; the card then says so.
- `run.completed` and `run.failed` are sent for every run the engine reports ended, also one that
  started and ended between two of the console's looks at its status (since 0.4.1; an engine
  before 0.4.1 does not count its runs, and only runs a look saw are told).

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
- While Git sync is on, every publish runs the repository's golden tests (`tests/golden`) as the
  editor's check does, and is refused when one fails: the **Publish** button, the API,
  auto-publish and promotion. Since 0.5.0 they are read at **the version's own Git commit** (the
  commit Git sync took it from, or the branch head when it was saved), not at the branch head; a
  version saved before 0.5.0 has no commit and is checked at the branch head, with a note.
- The **Configuration** page of each engine then has a **Git** card: the repository, what a new
  commit becomes, the last commit seen, the last sync, the last error, and **Sync now**. The
  repository holds every engine's configuration, so the card is shown to roles on everything, and
  **Sync now** is for designers and admins on everything (and API tokens with no limit). Nothing is
  taken in while an engine named in `CONSOLE_CONFIG_GIT_ENGINES` is not registered; the card says
  so, and administrators see a notice on the **Engines** page.
- Audited: `config.git-synced` per commit, `config.git-failed`, and `config.git-refused` when an
  environment file carries a secret (also a webhook event).
- The `git` program does the work. The console's image carries git and ssh; a packaged console
  needs `git` installed (the packages recommend it). The clone is kept under the console's data
  directory (`git/`).

## Scheduled reports and mail

Anyone who may view an engine may download its reconciliation report from its page (**Report CSV**,
**Excel**, **PDF**). The **Reports** page (`/reports`) sends it on a schedule, as CSV, Excel
(`.xlsx`) or PDF, by e-mail or to a share. What a report holds
is in [Reports, lineage and data quality](reports-and-data-quality.md#scheduled-reports); this section is what an
administrator sets up and controls.

Who may do what:

- **Operators** create scheduled reports, and change, **Send now** and delete their own.
- **Administrators** do the same for everyone's (an administrator of an environment or an engine,
  for the reports on its engines).
- **Auditors** see the list.
- Each sees the reports of the engines their roles reach.
- No API token manages reports.

Each run checks again that the report's owner is still an enabled administrator or operator and
that every recipient is in an allowed domain. A run that fails a check sends nothing and says why
in the **Last run** column and in the audit trail. Audited: `report.created`, `report.changed`,
`report.deleted` and `report.sent` (recipients and row count; a failed run is a failed
`report.sent`).

Nothing can be e-mailed until both of these are set. The **Reports** page says which is missing.
The same mail settings carry the console's mail to a group's reviewers when a
[group review](change-safety.md#review) falls due, to addresses in the same allowed domains.

| Setting | Default | Meaning |
|---|---|---|
| `CONSOLE_REPORTS_RECIPIENT_DOMAINS` | empty | The domains reports may be e-mailed to, comma-separated (`example.gov`, or `*.mil` for a domain and its subdomains). Required for e-mail |
| `CONSOLE_SMTP_HOST` | unset | The mail relay. Turns e-mail on |
| `CONSOLE_SMTP_PORT` | `587` | |
| `CONSOLE_SMTP_STARTTLS` | `true` | Upgrade the connection with STARTTLS, checking the server's certificate against the public roots or `CONSOLE_SMTP_CA_FILE`. `false` sends in plain text: a relay on this host or a private network only |
| `CONSOLE_SMTP_IMPLICIT_TLS` | `false` | Speak TLS from the first byte (SMTPS) instead of STARTTLS, with the same certificate check; set `CONSOLE_SMTP_PORT=465` (or the relay's SMTPS port) with it |
| `CONSOLE_SMTP_CA_FILE` | unset | CA certificates (PEM) the relay's certificate may chain to |
| `CONSOLE_SMTP_USERNAME`, `CONSOLE_SMTP_PASSWORD_FILE` | unset | The account to authenticate as (only over TLS, of either kind), and a file holding its password (read at each connection) |
| `CONSOLE_SMTP_AUTH` | `auto` | `auto` uses AUTH PLAIN when the server offers it (or lists no mechanisms) and AUTH LOGIN when it offers that and not PLAIN; `plain` or `login` forces one |
| `CONSOLE_SMTP_FROM` | `syncd@<host>` | The sender address |
| `CONSOLE_REPORTS_SHARE_DIR` | unset | A directory (a mounted share) reports may be written to. Without it, reports go by e-mail only |
| `CONSOLE_REPORTS_MAX_ROWS` | `50000` | The most rows a scheduled report's file holds (the mail says when it was cut) |
| `CONSOLE_EXPORTS_MAX_ROWS` | `200000` | The most rows one download from an engine's page holds |

Mail goes through an outbox in the console database: a message waits while the relay is down and
is retried (from a minute, doubling to an hour) for a day, then given up, which raises the
`report.failed` [webhook](webhooks.md) event. Only one console replica sends a due report, however
many run.

!!! note "Limits"
    The console's SMTP client speaks STARTTLS or SMTPS, with AUTH PLAIN or AUTH LOGIN; a server
    that offers only other mechanisms (CRAM-MD5, XOAUTH2, GSSAPI) is refused, and no credential is
    sent. The release notes say the Excel files were read back with Python's `zipfile`, not opened
    in Excel or LibreOffice, and the PDFs were checked with qpdf and poppler, not with veraPDF, PAC,
    Acrobat or a screen reader.

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

- The page states the engine's policy for a new password (`min_length` and `min_classes` of
  `passwords.self_service`, read from the engine's status and reused for a minute), and falls back
  to the defaults (12 characters, three kinds) when the engine cannot be asked. In 0.4.0 it always
  said 12.
- When the setting names one engine of an HA pair, a change goes to the member that holds the
  pair's lease (only the active engine takes self-service changes); to the named engine when that
  cannot be told.
- [`rename-engine`](#syncd-console-admin-rename-engine) does not change this setting: after an
  engine is renamed, change it by hand. The command prints a `warning:` line saying so, the console
  logs a warning at start, and the **Engines** page shows a notice while the setting names no
  registered engine; the page is not available until then.
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
**alert** badge for those severities) and **Detail** (the reason and the record's JSON).

The **Filters** card narrows the list (new in 0.5.0): **From** and **To** (in your time zone),
**Category**, **Action** (exactly; the field suggests the console's actions, `GET
/api/v1/audit/actions`), **Actor** (exactly: an account, `api-token:<name>` or `system`) and
**Outcome**. **Apply filters** applies them, **Clear** removes them, and the page says how many
entries match. The server filters, each condition from an index, and **Older entries** pages with
the same filters. The API is `GET /api/v1/audit?from=&to=&category=&action=&actor=&outcome=` with
`before=<id>&limit=<n>` (up to 500 a page).

An auditor or administrator of an environment or of engines sees the entries recorded about those
engines and environments (each request the console let through for an engine or an environment is
recorded with its name and environment). Sign-ins, account changes, the events of background tasks
(Git sync, the status watcher) and everything recorded before 0.5.0 carry neither, and show only to
auditors and administrators of everything.

From the page:

- **Export CSV** downloads what the page lists, with its filters and your scope, for a spreadsheet:
  UTF-8 with a byte-order mark, newest first, at most 100,000 rows, with a leading apostrophe on a
  cell a spreadsheet would read as a formula (`GET /api/v1/audit/csv`, the same parameters).
- **Export** downloads the trail as NDJSON (`ens-audit/1` events, newest first, within the **From**
  and **To** filters when they are set), for `tools/audit/verify_chain.py` or another tool. The CSV
  is for reading; the NDJSON export is what the chain checker verifies.
- **Verify chain** checks the hash chain (below).
- The **Delivery** card shows each enabled output (**stdout**, **file**, **splunk**, **syslog**):
  its state (**up to date**, **catching up**, **failing**), the `seq` delivered up to, the
  **Backlog**, the last delivery and the last error. With no output enabled it says the trail is
  kept in the database only.

**Export**, **Verify chain** and the **Delivery** card are for auditors and administrators of
everything: a chain cannot be verified in part. Every export, CSV or NDJSON, is recorded first, as
`data.exported`, and refused when it cannot be.

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
| `auth.login`, `auth.logout`, `auth.oidc.login`, `auth.saml.login`, `auth.x509.login` | Sign-ins (with the reason for a failure or refusal) and sign-outs. Since 0.5.0 a sign-in carries the account's `roles` (a list of assignments) instead of `role`: update SIEM parsers |
| `auth.consent`, `auth.session.ended`, `user.password_expired` | The consent notice acknowledged (the sign-in it leads to carries the same consent id), a session ended because the account had too many, a sign-in with an expired password sent to the change-password page |
| `user.bootstrap`, `bootstrap.password_file_removed` | The first `admin` account, and the initial password file being deleted |
| `user.create`, `user.update`, `user.password_reset`, `user.password_change`, `user.password_change_skipped` | Account changes (roles, disabled and break-glass before and after, and an unlock). A reset from the command line has the actor `system`, and says whether the account signs in through single sign-on |
| `settings.local_sign_in`, `settings.config_approval`, `settings.banners` | Console settings, before and after |
| `license.upload`, `license.replace`, `license.remove`, `license.state`, `license.usage-export` | The license installed, replaced or removed, its state changing (actor `system`), and usage exports. See [Licensing](licensing.md) |
| `engine.register`, `engine.update`, `engine.re-registered`, `engine.renamed`, `engine.remove` | Engines added, registered again with new credentials or moved to another environment, registered again over a client key the master key no longer opens, renamed, and removed (`system` when the installer, `syncdctl renew-certs`, `syncd-console admin` or the Helm Job does it) |
| `engine.run`, `engine.approve_deletions`, `engine.check_drift`, `engine.check_unmanaged`, `engine.adopt_entry`, `engine.dead_letters.retry`, `engine.dead_letters.discard`, `group.reviewed` | Operator commands, with the request and the engine's answer or error. **Approve and apply** on held changes is an `engine.approve_deletions` with the publication's scope |
| `correlation.join`, `correlation.unjoin`, `correlation.accepted` | Records joined or unjoined by hand, and suggestions accepted |
| `config.save`, `config.import`, `config.import_file`, `config.export` | Versions saved, imported or exported |
| `config.approve` | Approvals, and refused approvals |
| `config.publish.start`, `config.publish` | A publish is recorded before it is sent and again with its outcome, with the ticket, the hook's decision and, for a rollback the license would otherwise block, a `license` note; a publish the engine confirms late is settled with `config.publish` |
| `config.unpublished-running` | An engine runs a configuration the console did not publish |
| `wasm_module.upload`, `wasm_module.approve`, `wasm_module.download` | Modules of custom functions, with the module's SHA-256 |
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

`syncd-console` has six administration commands besides `serve` (the default): `reset-password`,
`register-engine`, `rename-engine`, and, new in 0.5.0, `license`, `config-signing-key` and
`wrap-master-key`. They connect to the console database (or, for `wrap-master-key`, its key file)
directly, so they need the console's settings: run them on the console host as the `syncd-console`
user, with the console's environment file.

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
[environment](#environments): `default` for a new engine unless given; with `--update`, the engine
keeps the one it has unless given.

When an engine with the name is registered already:

| Case | Without `--update` | With `--update` |
|---|---|---|
| Its stored client key opens with the master key | Says so ("engine … is registered already"; the API answers `409`, "an engine named … exists") and changes nothing | Replaces the address and credentials, keeping the engine's id, configuration versions and environment (unless `--environment` names another). Recorded as `engine.update` |
| Its stored client key no longer opens (the master key was replaced) | **Refused**, saying why, instead of "registered already" | Seals the new key over the old one, keeping the id, versions and environment (unless `--environment` names another). Recorded as `engine.re-registered`, with the reason |

`POST /api/v1/engines` does the same, with `replace: true` for `--update`. `syncdctl install` uses
the command to register the local engine; `syncdctl renew-certs` and the Helm chart's registration
Job use it with `--update`, which keeps the engine's environment (the chart passes
`--environment` when `engine.environment` is set). In 0.4.0, `--update` without `--environment`
moved the engine back to `default`. New registrations are recorded as `engine.register` by
`system`.

### `syncd-console admin rename-engine`

```sh
syncd-console admin rename-engine --from NAME --to NEW-NAME
```

Renames a registered engine, keeping its id and with it its configuration versions, reports and
history. Its stored client key is sealed again for the new name (one the master key cannot open is
left as it is: `register-engine --update` replaces it), and role assignments and API token limits
that name the engine follow it. It does nothing, and says so, when no engine is named `--from` or
one is named `--to` already, so it can run on every deployment. Recorded as `engine.renamed` by
`system`.

The Helm chart's registration Job runs it when `engine.ha.enabled` is on, before registering the
pair: an engine registered as `<name>` before HA was turned on becomes `<name>-0`. The command does
not change engine names in the console's settings, which come from its environment: it prints a
`warning:` line for each of `CONSOLE_SELF_SERVICE_ENGINE` and `CONSOLE_CONFIG_GIT_ENGINES` that names
the old name (which variable to set to what, and what stops until then). Change them by hand.

A running console now notices a registration that `register-engine --update` or `rename-engine`
changed from another process; until 0.5.0 it kept using the engine's old address and certificates
until it was restarted.

### `syncd-console admin license`

Prints the installation id (which a license request names), the license's state, the license held
and the managed identities. See [Licensing](licensing.md).

### `syncd-console admin config-signing-key`

```sh
syncd-console admin config-signing-key [--out FILE] [--rotate | --activate | --replace]
```

Prints the public key (ECDSA P-256) the console signs every published configuration with, made on
first use and kept in the database sealed with the master key; `--out` writes it to a file to copy to
an engine's host, for `engine.config_signing_keys` (`sudo syncdctl api-access signing-key` installs
it there). `--rotate` stages a new key and prints it, to add to every engine's key file; `--activate`
then signs with it. `--replace` makes a new key at once, for a key that is lost or exposed: engines
that require signed configurations refuse this console's publishes until they have the new public
key. Administrators can also read the keys from `GET /api/v1/settings/config-signing-keys`. How an
engine is set to require signed configurations is in the [Install guide](install.md).

### `syncd-console admin wrap-master-key`

```sh
syncd-console admin wrap-master-key --kms URI
```

Wraps the master key file in place with a KMS key (`awskms:<key>[?region=...]`,
`azurekv:https://<vault>.vault.azure.net/keys/<name>` or `vaulttransit:<mount>/<key>`). Stop the
console first; afterwards it must start with `CONSOLE_MASTER_KEY_KMS` set to the same key. See
[The master key](#the-master-key).

There is no command to create accounts, change roles or change the console settings; those are done
in the console or through the [API](api-reference.md).

## Settings that are not in the console

These are set in the console's environment (`/etc/syncd/console.env`) by whoever runs the host, and
take effect when the console restarts. An upgrade leaves `/etc/syncd/console.env` as it is; the
release's template, `/opt/syncd/current/share/config/console.env`, has commented lines for the
settings, those new in 0.5.0 included, and none of the new ones is needed: unset, every sign-in
setting keeps 0.4.1's behaviour. See [Configuration](configuration.md).

In this file everything after `=` is the value, a `#` included: a comment after a value becomes part
of it. The templates since 0.4.1 put every explanation on a line of its own; an older file may still have
comments after values, so move them to their own lines before uncommenting a setting. When a
setting that holds ` #` stops the console (or a `syncd-console admin` command), the error is followed
by a line naming the setting: "a comment at the end of the line becomes part of the value".

| Setting | What it controls |
|---|---|
| `CONSOLE_REVEAL_ROLES` | Which roles may reveal sensitive attributes, optionally with a scope (default `admin`); see [Revealing sensitive attributes](#revealing-sensitive-attributes) |
| `CONSOLE_OIDC_*`, `CONSOLE_SAML_*`, `ENS_X509_*`, `CONSOLE_PUBLIC_URL` | Single sign-on (OpenID Connect, SAML) and certificate sign-in, their role maps (with scopes) and default roles; see [Single sign-on](single-sign-on.md) |
| `CONSOLE_SESSION_IDLE_MINUTES`, `CONSOLE_SESSION_MAX_HOURS` | Session idle timeout (default 30 minutes) and maximum length (default 12 hours) |
| `ENS_CONSENT_BANNER_*`, `ENS_SESSION_*`, `ENS_LOCKOUT_*`, `ENS_PASSWORD_*`, `ENS_HSTS_*` | The [sign-in policy](#sign-in-policy) |
| `CONSOLE_LICENSE_FILE` | A license file to use instead of one installed on the **License** page (air-gapped hosts, Kubernetes Secrets); see [Licensing](licensing.md) |
| `CONSOLE_TRUSTED_PROXIES` | Reverse proxies whose `X-Forwarded-For` names the client |
| `CONSOLE_ADMIN_PASSWORD`, `CONSOLE_ADMIN_PASSWORD_FILE` | The first `admin` password, on an empty database only |
| `CONSOLE_TLS_CERT`, `CONSOLE_TLS_KEY` | A certificate (PEM, with its chain) and key for the console to serve HTTPS itself, without a TLS reverse proxy. Both or neither. The files are read again within a minute when they change, so a renewal needs no restart |
| `CONSOLE_MASTER_KEY`, `CONSOLE_MASTER_KEY_KMS`, `CONSOLE_NEW_MASTER_KEY` | The master key that seals the engines' client keys, webhook URLs and secrets, secret environment values and the configuration signing key (otherwise `$CONSOLE_DATA_DIR/master.key`), a KMS key that wraps it, and whether to start without the right one; see [The master key](#the-master-key) |
| `ENS_AUDIT_*` | The audit trail's outputs and retention; see [Outputs, retention and the pull API](#outputs-retention-and-the-pull-api) |
| `CONSOLE_API_TOKENS_ENABLED`, `CONSOLE_API_MAX_TOKEN_LIFETIME`, `CONSOLE_API_REQUESTS_PER_MINUTE` | [API tokens](#api-tokens): on or off (default on), the longest lifetime (default `365d`; `0` removes the limit) and the rate limit per token (default 300 a minute; `0` turns it off) |
| `CONSOLE_WEBHOOKS_*` | [Webhooks](webhooks.md): on or off, allowed hosts, private networks, extra CAs, timings |
| `CONSOLE_PUBLISH_*` | [The pre-publish hook and change tickets](#the-pre-publish-hook-and-change-tickets) |
| `CONSOLE_CONFIG_GIT_*` | [Git sync](#git-sync) |
| `CONSOLE_REPORTS_*`, `CONSOLE_EXPORTS_MAX_ROWS`, `CONSOLE_SMTP_*` | [Scheduled reports and mail](#scheduled-reports-and-mail) |
| `CONSOLE_SELF_SERVICE_ENGINE` | [The self-service password page](#the-self-service-password-page) |
| `SYNCD_FIPS_MODE` | FIPS mode, which changes password hashing |
| `SYNCD_FIPS_ALLOW_WASM_FUNCTIONS` | In FIPS mode, accept modules of custom functions (refused by default; set it on the engine too). See [Computed attributes and rules](rules.md#custom-functions-in-webassembly) |

The console checks the new settings when it starts and refuses to start with one it cannot use (a
hook URL that is not `https://`, a Git URL over plain `http://`, a share directory that does not
exist), saying which.

The console's part of a support bundle (its version, settings with secret values redacted, the
database migrations applied, and the registered engines with whether each answers; no credentials
and no entity data) is **Download support bundle** on the **Users** page's **Support bundle** card
(new in 0.4.1), or `GET /api/v1/admin/support-bundle` (administrators of everything, signed in). It is a `.tar.zst`
archive, recorded as `support.bundle-exported`. On a host, `syncdctl support-bundle` collects the
same from the host beside the engine's own bundle; see the [Operator guide](operator-guide.md).

### The master key

The console seals the client keys of engines registered with mutual TLS, the URLs and signing
secrets of webhooks, secret environment values and its configuration signing key with its master
key: `CONSOLE_MASTER_KEY`, or the file `$CONSOLE_DATA_DIR/master.key`
(`/var/lib/syncd/console/master.key` on a host; the `syncd-console` Secret under Helm). The console
creates the file on its first start. Back it up with the database: the sealed values cannot be
recovered without it.

Since 0.5.0 the file can hold the key wrapped by a KMS key (AWS KMS, an Azure Key Vault key or a
Vault transit key): set `CONSOLE_MASTER_KEY_KMS` to that key, and the console asks the KMS to unwrap
the file at every start. [`wrap-master-key`](#syncd-console-admin-wrap-master-key) wraps an existing
file; a first start without a file writes a new key wrapped. When the KMS cannot be reached or does
not unwrap the file, the console does not start: it never falls back to another key. Back up the
wrapped file and keep access to the KMS key (or a copy of the plain key kept offline). The release
notes say this was tested against stand-ins of the KMS services only.

At each start the console checks the key against **one sealed secret of each kind** it holds (an
engine's client key, a webhook's URL, a webhook's signing secret, a secret environment value, the
configuration signing key), not only the first one found as in 0.4.1. It refuses to start when:

- `master.key` is missing while the database holds sealed secrets (a restore that missed it, a new
  data directory). It does not create a new key over them;
- the key does not open one of them: it is not the key they were sealed with. The message names
  each kind it did not open. A database with secrets sealed under two keys, which 0.4.1 started, may
  now refuse to start.

Either way, restore `master.key` from a backup, or set `CONSOLE_MASTER_KEY` to the old key. To
start anyway, set `CONSOLE_NEW_MASTER_KEY=true` (or pass `--new-master-key`): the console starts
with a new key if it has none, and everything sealed with the old key is lost:

- every engine registered with mutual TLS must be registered again: `register-engine --update`
  (recorded as `engine.re-registered`), or `sudo syncdctl renew-certs` for the local engine. A
  plain registration is refused, saying the stored key no longer opens. Under Helm,
  `console.newMasterKey=true` does this with the registration Job (see the
  [Install guide](install.md));
- webhooks are listed with their URL marked as unreadable and their deliveries fail: save each
  URL again and choose **New secret** (**Routing key** for PagerDuty);
- secret environment values must be typed again on the **Integrations** page;
- the configuration signing key must be replaced
  ([`config-signing-key --replace`](#syncd-console-admin-config-signing-key)), and engines that
  require signed configurations given the new public key.

Keep the setting until those secrets are set again: until then the old sealed ones are still in
the database, and the console, and the `syncd-console admin` commands run with its environment,
can refuse to start without it. Remove it afterwards, so that a lost key is caught again.
