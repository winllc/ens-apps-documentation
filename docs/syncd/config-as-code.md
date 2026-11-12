# Configuration as code

One base configuration, kept in Git, can run in several environments: development, test and
production. syncd 0.4.0 adds the pieces a team needs to work that way:

| Piece | What it does | Where |
|---|---|---|
| [Semantic diff](#what-changed-syncd-config-diff) | Tells what changed between two configurations by meaning: per connector, view, rule and publication field, with secrets redacted | `syncd config diff`, the console's **Compare** tab |
| [Environments](#environments) | `{{ env.<name> }}` placeholders in the base, filled from an environment's values | `syncd --env`, the console's **Environments** |
| [Git sync](#git-sync) | Commits on a branch become configuration versions in the console, and can be published | `CONSOLE_CONFIG_GIT_*` |
| [Promotion](#promotion) | Publishes a version proven in one environment to every engine of another | `syncd config promote`, `POST /api/v1/configs/promote`, the console's **Promote** button |
| [Golden-file tests](#golden-file-tests) | Check what whole publications contain after a run over fixture data | `syncd test --golden` |

In syncd 0.4.1 the golden tests from Git gate every publish (the API, Git auto-publish and
promotion, not only the editor), `syncd config promote` prints each engine's diff summary, and
`syncd test` finds golden files one directory level down, as the console does.

This page describes syncd 0.5.0 (`main` at commit `a9d17c6`), which adds:

- **Each version's own Git commit.** The console keeps the commit a version came from, and reads
  its golden tests and package files there, not at the branch head (see
  [Golden tests in the console](#golden-tests-in-the-console)). **Compare** reads each side's
  packages at its own commit and shows the functions, and WebAssembly signatures, that changed.
- **A Promote button** in the console, with a dry-run preview, through the same gate as the command
  (see [Promotion](#promotion)).
- **Signed configurations.** The console signs every configuration it publishes, and an engine set
  to strict access or to require signed configurations takes configuration from the console only
  (see [Publishing to a strict engine](#publishing-to-a-strict-engine)).
- **The license gate.** Validating, saving and publishing check the license; a rollback is always
  allowed (see [The license](#the-license)).
- **Scoped roles.** Git sync is for roles on everything; promotion needs the right to publish on
  every engine of the target environment.

Rule packages, functions and lookup tables shared between configurations, fit the same way of
working; see [Rule packages](#rule-packages) below and [Computed attributes and rules](rules.md).

## What changed: `syncd config diff`

```sh
syncd config diff old.yaml new.yaml [--format text|json|markdown]
syncd config diff --running new.yaml          # the running engine's configuration against a file
```

Both files are parsed and compared by meaning, not by line. A comment, a reordered key or a default
written out is no difference. A rule change shows as the attribute with the old and the new
expression.

- **Sections.** `engine`, `api`, `attributes` and `passwords` are compared as one block each.
  `packages`, `connectors`, `views` and `publications` are lists of named items, matched by name,
  so a renamed publication is one removed and one added.
- **Paths.** Within an item every field is a path: `connection.host`, `objects[person].mode`,
  `sources[hr_db/person].flows.title`, `computed[displayName].output`, `attributes.mail`,
  `deletion_threshold.percent`, `change_window.allow`, `canary.soak`, `mode`. A named part present
  on one side only (a computed attribute added, an object removed) is one change, not one per
  field it holds.
- **Packages.** A package's version change shows as `gov-naming 1.1.0 -> 1.2.0`, with the
  functions that changed. A package's WebAssembly module shows as its pin (`wasm.sha256`), its
  size and every signature that changed, never its bytes.
- **Secrets are redacted.** A path segment that is or contains `password` or `secret`, is `token`
  or `key`, ends in `_file`, `_key` or (since 0.5.0) `_token`, or is `assertion`, the HA lease's
  `url`, and a URL carrying `password=` or `pwd=`, shows as `<redacted> (changed)`. Not even where a secret lives is printed. Two Active
  Directory flags, `password_never_expires` and `must_change_password`, are rules, not secrets,
  and are shown.

| Exit code | Meaning |
|---|---|
| `0` | The two configurations are the same |
| `1` | They differ |
| `2` | An error: a file that does not read or parse (a base whose placeholders need `--env`, for example), or, with `--running`, an engine that can't be reached |

`--running` asks the engine for its running configuration through the control API (the usual
`--api` options; none on the engine's host) and compares it, as the before, with the file.
With `--env`, both files are [rendered](#environments) for that environment first.

The text format, for the same base before and after a change:

```text
connectors
  ~ directory
      connection.password.env: <redacted> (changed)
  ~ hr_db
      connection.user: syncd -> syncd_ro
      objects[person].mode: scrape -> delta
      objects[person].watermark: (unset) -> updated_at
views
  ~ people
      computed[displayName]: added
      sources[hr_db/person].flows.title: source.title -> source.job_title
publications
  ~ people_out
      deletion_threshold.percent: 5.0 -> 2.0
      mode: (unset) -> report
  + people_report
connectors: 2 changed; views: 1 changed; publications: 1 added, 1 changed
```

`--format markdown` gives the same as nested lists, for a pull-request comment. `--format json`
gives `from`, `to`, `same`, `summary` and the structured `diff`.

In the console, the configuration page's **Compare** tab shows the semantic diff above the text
diff, and the diff endpoint (`GET /api/v1/engines/{id}/configs/{version}/diff`) returns it as
`semantic` and `summary` beside the unified text. Since 0.5.0 each side's packages are read at that
side's own Git commit (and modules from the engine's module store), so the console shows the
functions that changed as `syncd config diff` does. A package that cannot be read on a side (a
version older than 0.5.0 whose file has moved on, a module not approved) is compared by name,
version, file and pin, and a note above the changes (`notes` in the API) says which and why.

## Environments

### Placeholders

Where a value differs between installations, write a placeholder in the base configuration:

```yaml
connectors:
  - name: hr_db
    type: postgres
    connection:
      host: "{{ env.hr_host }}"
      port: "{{ env.hr_port | default 5432 }}"
      user: syncd
      dbname: hr
      password: { file: "{{ env.hr_password_file }}" }
  - name: directory
    type: ldap
    connection:
      url: "{{ env.ldap_url }}"
      bind_dn: "cn=syncd,{{ env.ldap_suffix }}"
      password: { env: DIRECTORY_PASSWORD }

publications:
  - name: people_out
    view: people
    target: directory
    dn: "uid={uid},ou=People,{{ env.ldap_suffix }}"
```

- A placeholder can stand anywhere in a string value. Rendering never touches keys, numbers or
  booleans.
- `| default "x"` (or `'x'`, or a bare number or boolean) is used when the environment has no
  value.
- A value that is one placeholder and nothing else takes the type of the environment's value, so
  `port: "{{ env.hr_port }}"` renders as `port: 5433`. A placeholder among other text renders as
  text.
- A placeholder with no value and no default is an error that names the key and where it is
  (``connectors[0].connection.host: the environment has no value for `hr_host` ``, with a hint to
  set it or give a default). Anything else in `{{ ... }}` is an error too.
- Comments are not kept in a rendered configuration.

Keep secrets as secret references (`{ env: NAME }`, `{ file: PATH }`), as above. An environment's
values then hold only where the secret is, not the secret itself.

### Environment files

```yaml
# environments/prod.env.yaml
environment: prod
values:
  hr_host: hr.prod.example.gov
  hr_port: 5433
  hr_password_file: { value: /run/secrets/hr, secret: true }
  ldap_url: ldaps://directory.prod.example.gov
  ldap_suffix: dc=example,dc=gov
```

The name is lower-case letters, digits, `-` and `_`, at most 64 characters. A value is a scalar,
or `{ value: x, secret: true }` for one that is shown as `<secret name>` wherever a rendering is
displayed rather than run.

### Environments on the engine's host

| Command | What it does |
|---|---|
| `syncd --env prod.env.yaml run` | The daemon renders its configuration with the file at every start. `syncd status` shows the environment |
| `syncd --env FILE validate`, `simulate`, `apply-config`, `test` | Render what they read first |
| `syncd --env FILE config diff a.yaml b.yaml` | Renders both sides |
| `syncd config render base.yaml --env prod.env.yaml` | Prints the configuration rendered for that environment, checked |

`SYNCD_ENVIRONMENT` does the same as `--env`. On a packaged install, set it in
`/etc/syncd/engine.env` for the service. A configuration the console publishes arrives rendered
already, so an engine registered with the console doesn't need `--env`.

On an engine in strict access mode (every new 0.5.0 install), `syncd apply-config` on the host is
refused: the command line's certificate may read and operate, not configure. The other commands in
the table work as before; see [Publishing to a strict engine](#publishing-to-a-strict-engine).

### Environments in the console

- **Each engine runs in an environment**, `default` unless one is given when it is registered
  (`syncd-console admin register-engine --environment prod`, or the registration form) or set
  later by an admin (`PATCH /api/v1/engines/{id}` with `{"environment": "prod"}`). Since 0.4.1,
  re-registering an engine (`register-engine --update`, which `syncdctl renew-certs` and the Helm
  chart's registration Job run) keeps its environment unless `--environment` is given; in 0.4.0
  it moved the engine back to `default`. On Kubernetes, the chart's `engine.environment` passes
  one at each registration.
- **Environments are kept under Integrations > Environments** (admins change them: an admin on
  everything adds them, an admin of an environment changes its values; auditors see them), or with
  `PUT /api/v1/environments/{name}`:

    ```json
    {"values": {"hr_host": {"value": "hr.prod.example.gov"},
                "hr_password_file": {"value": "/run/secrets/hr", "secret": true}}}
    ```

    A value marked secret is sealed with the master key and never returned: `GET` shows
    `{"secret": true}`, and a `PUT` that leaves its value out keeps the stored one. Changes are
    audited as `environment.changed` (the keys added, removed and changed, never a value). An
    environment that an engine runs in can't be removed, and the console asks before it removes
    one.
- **Who reads what.** Since 0.4.1 the values (`GET /api/v1/environments` and
  `GET /api/v1/environments/{name}`) are for admins and auditors, or a token with the
  `environments:read` scope (or `admin`); since 0.5.0, those on everything or on that environment.
  In 0.4.0 any role, or a `configs:read` token, could read them: scripts that read values with a
  `configs:read` token need the new scope. Every role reads the names of the environments it
  reaches, with `GET /api/v1/environment-names` (`configs:read` for a token); the **Compare** tab
  uses it. Changing environments is for admins, signed in.
- **A version is the base.** Publishing a version to an engine renders it for the engine's
  environment, with secret values in clear, and sends that. A value the environment lacks refuses
  the publish and names it.
- **What is shown is rendered with secrets masked:** the editor's check, saving a version, and
  the **Compare** tab, which renders both sides for the engine's environment. Its **Rendered for**
  selector picks another environment, or **as written** to compare the texts as saved. A
  rendering shown this way still shows the plain values it uses, to any role that may view
  configuration.
- **The versions list** shows each version's **Source** (`console`, `git` or `promotion`, with the
  commit or the version it came from) and where it **Runs in**: the environments whose engines run
  that text.

## Git sync

The console can take its configuration from a Git repository, and put what is saved in the
console back into it. It is off unless `CONSOLE_CONFIG_GIT_URL` is set.

```sh
CONSOLE_CONFIG_GIT_URL=ssh://git@git.example.gov/idm/syncd.git   # or https://...
CONSOLE_CONFIG_GIT_BRANCH=main
CONSOLE_CONFIG_GIT_PATH=syncd.yaml
CONSOLE_CONFIG_GIT_ENV_PATH=environments
CONSOLE_CONFIG_GIT_SSH_KEY_FILE=/etc/syncd/git-deploy-key
CONSOLE_CONFIG_GIT_KNOWN_HOSTS_FILE=/etc/syncd/git-known-hosts
CONSOLE_CONFIG_GIT_POLL=60s
CONSOLE_CONFIG_GIT_AUTO_PUBLISH=validate
CONSOLE_CONFIG_GIT_PUSH_BACK=false
CONSOLE_CONFIG_GIT_ENGINES=dev-1,dev-2
```

| Setting | Default | Meaning |
|---|---|---|
| `CONSOLE_CONFIG_GIT_URL` | none (off) | The repository: `ssh://` (or `git@host:path`) or `https://`. Plain `http://` is refused |
| `CONSOLE_CONFIG_GIT_BRANCH` | `main` | The branch to follow |
| `CONSOLE_CONFIG_GIT_PATH` | `syncd.yaml` | The configuration file in the repository. A relative path, without `..` |
| `CONSOLE_CONFIG_GIT_ENV_PATH` | none | A directory of `<name>.env.yaml` [environment files](#environment-files-from-git) |
| `CONSOLE_CONFIG_GIT_SSH_KEY_FILE` | none | A deploy key, for `ssh://` |
| `CONSOLE_CONFIG_GIT_KNOWN_HOSTS_FILE` | none | The Git server's host key, for `ssh://` |
| `CONSOLE_CONFIG_GIT_TOKEN_FILE` | none | A token, for `https://` (basic authentication; the user name from the URL, else `x-access-token`). Not with an SSH key |
| `CONSOLE_CONFIG_GIT_POLL` | `60s` | How often the branch is fetched |
| `CONSOLE_CONFIG_GIT_AUTO_PUBLISH` | `off` | `off`, `validate` or `publish` (below) |
| `CONSOLE_CONFIG_GIT_PUSH_BACK` | `false` | Commit versions saved in the console to the branch |
| `CONSOLE_CONFIG_GIT_ENGINES` | every engine | The engines the repository is for, comma-separated. Renaming an engine (`syncd-console admin rename-engine`) doesn't change this list: change it by hand. The command prints a warning saying so, the console logs one at start, and administrators see a notice on the **Engines** page while a name here matches no registered engine |

The console checks these at start-up and refuses both credentials at once, absolute or `..`
paths, and files that are not there.

The repository holds every engine's configuration, so Git sync belongs to the console as a whole:
its status and the **Git** card are for roles on everything, **Sync now** for designers and admins
on everything, and an API token limited to an environment or to engines can do neither. A role
limited to an environment or an engine still sees and publishes the versions Git sync made on its
engines.

### What a pass does

Every `CONSOLE_CONFIG_GIT_POLL`, or on **Sync now**, the console fetches the branch and takes in
the commits since the last one it took that touch the configuration file or the environment
files, oldest first, at most 50 a pass. The first time, it takes only the branch head. Nothing is
taken in while an engine named in `CONSOLE_CONFIG_GIT_ENGINES` is not registered yet; the Git card
says what it waits for.

For each commit:

1. The environment files it changed are [taken in](#environment-files-from-git).
2. If it changed the configuration file, that text becomes a new version on each engine the
   repository is for (source `git`, with the commit's sha and author, and its subject as the
   comment), unless the engine's latest version already has the same text. Since 0.5.0 the version
   keeps the commit, and its golden tests and package files are read there.
3. What happens next depends on `CONSOLE_CONFIG_GIT_AUTO_PUBLISH`:

| Mode | What happens to the new version |
|---|---|
| `off` | It stays a draft. Someone publishes it from the console |
| `validate` | It is checked: compiled, with the rules' test cases, the packages' tests and the [golden tests](#golden-tests-in-the-console) from the repository, at that commit. Failures are recorded as `config.git-failed`. It stays a draft either way, and one that fails can't be published, by any route |
| `publish` | It is checked as for `validate`, then published through the same gate as the **Publish** button: four-eyes approval (the version waits for approval), the [license](#the-license), the pre-publish hook, and, when `CONSOLE_PUBLISH_TICKET_PATTERN` is set, a change-ticket reference found in the commit message (`HR port moved (CHG-12345)`). A commit without one is recorded as `config.git-failed` and its version stays a draft |

The commit counts as seen whatever happened to it, so a bad commit is reported once, not every
minute.

### Environment files from Git

With `CONSOLE_CONFIG_GIT_ENV_PATH` set, every `*.env.yaml` file in that directory sets the plain
values of the console environment it names (its `environment:` key). The file is the whole set of
plain values: a plain value missing from it is removed. Secret values the console holds are kept.

A file is **refused** when it holds a value marked `secret: true`, or a plain value for a key the
console keeps as a secret. Secrets never come from Git. A refusal is recorded as
`config.git-refused` in the audit trail (with the keys, never the values) and raised as the
`config.git-refused` webhook event, and the environment is left as it was. Set secret values in
the console instead.

### Push-back

With `CONSOLE_CONFIG_GIT_PUSH_BACK=true`, a version saved or published in the console for an
engine the repository is for is committed to the branch at `CONSOLE_CONFIG_GIT_PATH`, as the
console user, with the subject `<engine> v<n>: saved (<comment>)` (or `published (<comment>)`), and
pushed. Nothing is committed when the branch already has that text. A push the server rejects
(the branch moved, a protected branch) is recorded as `config.git-failed` with the reason. It is
never forced. The next pass sees the pushed commit and makes no second version of it.

With push-back on, the repository needs a deploy key or token that may write to the branch.

### Watching it

- **The Git card** on the configuration page: the last commit seen (its author and message), the
  last sync and the last error (a fetch that failed, a commit that did not compile, a refused
  secret, a rejected push), with **Sync now**. The same is `GET /api/v1/config-git` (any role on
  everything; `configs:read` on a token with no limit), and **Sync now** is
  `POST /api/v1/config-git/sync` (designers and admins on everything; `configs:write`).
- **The audit trail:** `config.git-synced` for each commit taken in (the versions and environments
  it made, and its errors), `config.git-failed` and `config.git-refused`. See
  [Admin guide](admin-guide.md).

### Credentials and the `git` program

The console runs the `git` program for the Git work. The console's container image includes git
and ssh. A packaged console needs `git` installed: the packages recommend it. The clone lives in
the console's data directory, under `git/`.

The token and the SSH key never reach a command line or a file of the repository: the token goes
in as an HTTP header through git's own settings, and the key and known hosts through an ssh
configuration in a home directory of git's own under the clone. Nothing from the repository is
run; files are read with `git show`.

`file://` repositories work on a host but not in the container image, which has no shell.

On Kubernetes, mount the deploy key and known hosts from a Secret with `console.extraVolumes`, and
set the variables with `console.env`. See [Install](install.md).

## Promotion

A version that runs in one environment is published to every engine of another environment with
one command:

```sh
syncd config promote dev-1/v7 --to prod \
  --console https://syncd.example.gov --token-file /run/secrets/console-token \
  --ticket CHG-12345 --note "Q3 directory changes"
```

| Option | Meaning |
|---|---|
| `<engine>/v<n>` | The version to promote, as the console names it. It must have been published |
| `--to` | The environment whose engines get it |
| `--console` | The console's address. `https://`, or plain `http://` only on this host. `SYNCD_CONSOLE` does the same |
| `--token-file` | A file holding an [API token](api-reference.md) with `configs:publish`. Only administrators create tokens; the token's creator must reach the source engine and hold a designer or admin role on every engine of the target environment, and a limited token must reach them all too. `SYNCD_CONSOLE_TOKEN_FILE` does the same |
| `--ticket` | A change-ticket reference. Required when `CONSOLE_PUBLISH_TICKET_PATTERN` is set |
| `--note` | A note for the change record |
| `--ca` | PEM CA certificates the console's certificate chains to, when it isn't publicly trusted |

The command calls `POST /api/v1/configs/promote` with
`{"engine", "version", "to", "ticket", "note"}`. Every engine of the target environment except
the source gets the text as a version of its own (source `promotion`, `dev-1/v7`), rendered for
its environment, keeping the source version's Git commit, and the usual gates apply **on each
engine**: four-eyes approval, the rules' test cases, the
[golden tests](#golden-tests-in-the-console) from Git (since 0.4.1), the [license](#the-license),
the ticket pattern and the pre-publish hook.

Since 0.5.0 the caller's roles are checked on every engine first: when they do not allow publishing
to one of them, nothing is promoted ("promoting to environment prod publishes to every engine in
it, and your roles (…) do not allow publishing to …").

| Outcome per engine | Meaning |
|---|---|
| `published` | Published and running |
| `pending_approval` | Saved; waits for a second person's approval on that engine |
| `running` | The engine already runs this text; nothing changed |
| `failed` | Refused by a gate, or the engine couldn't take it; the detail says why |

The command prints one line per engine, with the detail, and under it, since 0.4.1, the one-line
summary of the semantic diff against what the engine ran before (`diff_summary` in the API's
answer):

```text
prod-eu v3: published (<the publish's summary>)
  changes: views: 1 changed; publications: 1 changed
prod-us v5: running (the engine runs this configuration as v5 already)
```

The command exits `0` when no engine failed, `1` when any did, and `2` when the console refused
the request: a version never published, an unknown engine, an environment with no engines, a token
without the scope, a missing ticket.

Each target engine's outcome is recorded as `config.promoted` in the audit trail (from, to, the
status and the diff summary), and `config.promoted` is raised once as a webhook event.

### Promoting from the console

Since 0.5.0, **Promote** on an engine's **Configuration** page does the same through the same
endpoint, for designers and admins (the button shows once a version of that engine was published;
the server decides whether the caller may publish to every engine of the target). In its dialog,
pick the source engine, one of its published versions and the target environment, give the ticket
and note, then:

1. **Preview** sends the request with `"dry_run": true`: the same role checks, and per target
   engine what would happen (`would_publish`, or `running` when the engine runs that text already)
   and its diff summary, shown in the command's words. Nothing is saved, published or recorded, and
   the test cases, golden tests, license and hook are not run.
2. **Promote**, enabled once a preview succeeded (a change of any field asks for a new one),
   promotes, and shows each engine's outcome and how many engines published.

`syncd config promote` has no dry run; the API's `dry_run` is what the preview uses. See the
[User guide](user-guide.md#promoting-a-version).

## Publishing to a strict engine

The console's gates (four-eyes approval, test cases, golden tests, the license, the change ticket
and the pre-publish hook) hold only if configuration reaches the engine through the console alone.
Since 0.5.0 the engine can enforce that:

- **Strict access** (`api.access` with `mode: strict`, the default of a new install's
  `engine.yaml`): only the console's certificate may apply a configuration. The command line's
  certificate and the control socket read and operate, so `syncd apply-config` on the host is
  refused ("ApplyConfig needs the configure tier"). An engine upgraded from 0.4.x stays compatible
  until `sudo syncdctl api-access strict` is run on its host; then import the running configuration
  in the console, because the `api` section of a published configuration must match the host's.
- **Signed configurations** (`engine.require_signed_config`, with the console's public key in
  `engine.config_signing_keys`): the console signs every configuration it publishes, after its gates
  passed, with an ECDSA P-256 key of its own; the signature covers the configuration's SHA-256, the
  version label and a value the engine issues anew at each start and reload, so it is for that text,
  on that engine, once. The engine applies nothing else, whoever sends it.

Nothing changes in how designers work: Git sync, the **Publish** button, the API and promotion all
publish through the console, which signs. A configuration put in place around the console (a file
edited as root on the engine's host, or `syncd apply-config` on an engine still compatible) is
reported on the engine's page and as `config.unpublished-running`. Setting up an engine, rotating the
signing key and the console's `syncd-console admin config-signing-key` are in the
[Install guide](install.md) and the [Admin guide](admin-guide.md#syncd-console-admin-config-signing-key).

## The license

Since 0.5.0 the console checks the license when a configuration is validated (the editor's check,
Git sync's `validate`), saved and published (the **Publish** button, the API, Git auto-publish,
promotion):

- a configuration that uses a connector or feature outside the license's packs does not validate and
  cannot be saved or published, at any time; the check names the connector or setting and the pack;
- once the license's grace period is over, publishing a version that was never published to that
  engine is refused;
- **a rollback is always allowed**: a version that was published to that engine before can be
  published again whatever the license says, recorded with a `license` note in
  `config.publish.start` ("published again as a rollback, which the license never blocks");
- running sync never stops, whatever the license says.

With no console, an engine applies the same rules to `ApplyConfig`. An installation upgraded from
0.4.1 evaluates for 30 days and then has a 30-day grace period, so a pipeline that publishes keeps
working for about 60 days without a license. See [Licensing](licensing.md).

## Golden-file tests

A rule's test cases check one attribute. A golden test checks a whole publication: given these
source records and these entries already in the target, the publication holds exactly this LDIF
after a full run. Any change to a rule, join, precedence, package or filter that changes what a
target receives shows as a diff.

### The golden file

```yaml
# tests/golden/full-run.yaml
fixtures:
  now: 2026-01-01T00:00:00Z               # the run's instant (this is the default)
  hr_db: fixtures/hr.csv                  # a source: CSV, LDIF, or rows inline
  corp_ldap: fixtures/corp.ldif
  openldap: fixtures/openldap-existing.ldif   # a target: the entries it holds already
expect:
  people_to_openldap: expected/people_to_openldap.ldif
```

Paths are relative to the golden file.

- **`fixtures`** is keyed by connector, or `connector/object` when the connector has several
  objects.
    - For a connector a view reads, the fixture is the records of a full read: a CSV file, an LDIF
      file, or rows written inline (`- { id: 1, given: Jane, sn: Doe }`). Each record needs the
      object's anchor, and two records can't share one.
    - A CSV fixture has a header row. An empty cell is no value. A column whose header ends in
      `[]` (`groups[]`) holds several values separated by `;`.
    - For a publication's target, the fixture is an LDIF file of the entries the target holds
      already.
- **`expect`** names, for each publication to check, the LDIF file of what it should contain.
- **`now`** is the instant the run happens at. `now()`, effective dates and timers read it.

### The run

`syncd test` builds an engine on an in-memory store, applies every fixture record at `now`, plans
every publication in full twice (so a reference to an entity exported in the first pass resolves
in the second), plans computed groups, and applies the plans to an in-memory directory for each
target, seeded with its LDIF.

- An add over an existing entry replaces the attributes it names, so attributes syncd doesn't
  manage stay, as the writer converges them.
- A disable shows as `x-syncd-disabled: TRUE`, because how a target disables an account depends on
  its profile.
- What a publication contains is the entries it wrote, as **canonical LDIF**: entries sorted by
  normalised DN, attributes by name, values sorted, no line folding, base64 for values LDIF can't
  write plainly, and sensitive attributes (`attributes.sensitive`, passwords) as `<masked>`.
- A fresh store gives the same `unique()` values every time, so the output is the same on every
  run.

### Running them

```sh
syncd -c syncd.yaml test [--golden DIR] [--update] [--junit FILE]
```

| Option | Meaning |
|---|---|
| (none) | Runs the computed attributes' test cases, the packages' tests, and every golden file in `tests/golden` next to the configuration, when that directory exists |
| `--golden DIR` | Runs the golden files in `DIR` instead |
| `--update` | Writes what each differing publication contains to its expected file. Review the diff it prints first |
| `--junit FILE` | Writes a JUnit XML report: one test case per publication compared (and per golden file that couldn't run), plus the rule and package tests |

A publication that differs prints as a unified diff of the expected file against what it
contains. The command exits `1` when any test case, package test or golden test fails, else `0`.
Add `--env FILE` when the configuration has placeholders.

A golden file is a `*.yaml` or `*.yml` file directly in the directory or one level of
subdirectories down. `syncd test` and the console find the same files. (Before 0.4.1, `syncd test`
read only the files directly in the directory, while the console read one level down as well.)
Either layout works: golden files directly in `tests/golden`, with fixtures and expected files in
subdirectories, or one subdirectory per test:

```text
syncd.yaml
tests/golden/full-run.yaml
tests/golden/fixtures/hr.csv
tests/golden/fixtures/corp.ldif
tests/golden/fixtures/openldap-existing.ldif
tests/golden/expected/people_to_openldap.ldif
tests/golden/leavers/leavers.yaml
tests/golden/leavers/fixtures/hr.csv
tests/golden/leavers/expected/people_to_openldap.ldif
```

Any other YAML file one level down is taken for a golden file too, so keep fixtures as CSV or
LDIF there. Files two levels down are only read as fixtures and expected files.

### What golden tests leave out

Golden tests show the **desired state**. They leave out:

- report mode, change windows, modification, add and deletion thresholds, and canaries: each
  publication is planned and applied in full;
- the targets themselves: schema checks, password policies and access rights are not exercised;
- connector settings that shape values on the way in, such as a CSV connector's column types:
  fixture values reach the rules as text, except attributes the object lists under `timestamps`.

Use [report mode](change-safety.md) against the real target to see what a change would do there.

### Golden tests in the console

With Git sync on, the console reads the golden files from `tests/golden` beside the configuration
file in the repository, with their fixtures and expected files, and runs them on the server with
the rules' test cases:

- **The configuration editor's check** (and saving) shows each golden failure's diff with the test
  cases' failures. While any fails, the editor's **Publish** button is disabled.
- **Git sync's `validate` and `publish` modes** check every version they make the same way. A
  failing golden test is a `config.git-failed`, and the version is not published.
- **Every publish** runs them, since 0.4.1: the server's publish gate
  (`POST /api/v1/engines/{id}/configs/{version}/publish`, which the **Publish** button, API tokens,
  Git auto-publish and [promotion](#promotion) all use) runs the golden tests with the rules' test
  cases, on the version rendered for the engine's environment with secrets masked, and refuses
  the publish when any fails (`not published: N of M test cases fail`, with each golden failure).
  In 0.4.0 the gate ran only the rules' test cases, so a publish through the API or a promotion
  went ahead with a failing golden test.

Without Git sync, the console has no golden tests: run `syncd test` in CI.

**Which commit.** Since 0.5.0 the console keeps each version's Git commit: the commit Git sync took
it from; for a version saved or imported in the console, the branch head when it was saved; for
a promoted version, the source version's commit. A saved version is checked and published
against the golden files (and package files) of **its own commit**, whatever the branch head holds
now; an edited text, like any draft, against the branch head. The check names the commit
(**Golden tests: N of M publications as expected (at commit …)**).

- A version saved while its golden files failed stays refused after the files are fixed at the
  head: save it again, which makes a version at the new commit.
- A version saved before 0.5.0 has no commit: it is checked at the branch head, and the check and a
  refused publish say so in a note.

In 0.4.1 every check read the golden files at the branch head, so a version from an older commit
was checked against the newest tests.

## A worked CI pipeline

This example keeps one base configuration in Git and runs three stages:

1. **On a pull request**, CI validates the configuration, runs the tests and posts the semantic
   diff for review.
2. **On merge to `main`**, the console's Git sync publishes the new version to the development
   engines (`CONSOLE_CONFIG_GIT_AUTO_PUBLISH=publish`, `CONSOLE_CONFIG_GIT_ENGINES=dev-1`), through
   the usual gates.
3. **When development has run it**, a manual job promotes that version to production.

The repository:

```text
syncd.yaml                       # the base configuration, with {{ env.* }} placeholders
packages/gov-naming.yaml         # rule packages it reads (from: packages/...)
environments/dev.env.yaml        # plain values only; secrets are set in the console
environments/prod.env.yaml
tests/ci.env.yaml                # values for CI, outside the environments directory
tests/golden/full-run.yaml       # golden tests, with fixtures/ and expected/ beside them
```

`tests/ci.env.yaml` sits outside `environments/`, so the console doesn't take it in as an
environment. It fills every placeholder, secrets included, with values that need not work: CI
compiles and tests the configuration but connects to nothing.

A GitHub Actions workflow (other CI systems run the same commands). It assumes `syncd` is on the
runner: syncd publishes release bundles only once a `v*` tag is pushed, so until then build it, or
install it from a bundle you have.

```yaml
name: syncd configuration
on:
  pull_request:
  workflow_dispatch:
    inputs:
      commit: { description: "The commit to promote", required: true }

jobs:
  check:
    if: github.event_name == 'pull_request'
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with: { fetch-depth: 0 }
      - name: Validate
        run: syncd -c syncd.yaml --env tests/ci.env.yaml validate
      - name: Test cases, package tests and golden tests
        run: syncd -c syncd.yaml --env tests/ci.env.yaml test --golden tests/golden --junit golden.xml
      - name: What changes, by meaning
        run: |
          git show "origin/${{ github.base_ref }}:syncd.yaml" > base.yaml
          set +e
          syncd --env tests/ci.env.yaml config diff base.yaml syncd.yaml --format markdown > diff.md
          code=$?
          set -e
          [ "$code" -le 1 ] || exit "$code"     # 0 same, 1 different, 2 error
          cat diff.md >> "$GITHUB_STEP_SUMMARY"

  promote:
    if: github.event_name == 'workflow_dispatch'
    runs-on: ubuntu-latest
    environment: production            # a GitHub environment with required reviewers
    steps:
      - name: Promote the version dev-1 runs from this commit
        env:
          CONSOLE: https://syncd.example.gov
          SHA: ${{ github.event.inputs.commit }}
          TICKET: ${{ vars.CHANGE_TICKET }}
          TOKEN: ${{ secrets.SYNCD_CONSOLE_TOKEN }}
        run: |
          printf '%s' "$TOKEN" > token
          auth="Authorization: Bearer $(cat token)"
          id=$(curl -fsS -H "$auth" "$CONSOLE/api/v1/engines" | jq -r '.[] | select(.name == "dev-1") | .id')
          v=$(curl -fsS -H "$auth" "$CONSOLE/api/v1/engines/$id/configs" |
              jq -r --arg sha "$SHA" '[.[] | select(.source_ref == $sha and .published_at != null)][0].version')
          syncd config promote "dev-1/v$v" --to prod --console "$CONSOLE" --token-file token --ticket "$TICKET"
```

The promotion token needs `engines:read` and `configs:read` (to find the version) and
`configs:publish`; its creator, an administrator, must reach `dev-1` and hold a designer or admin
role on every production engine, and a token limited to an environment would not reach both (use engines
by name, or no limit). A version made from the commit
exists only if the commit changed `syncd.yaml` and the text was new; otherwise promote the version
the console shows as running on `dev-1`. With four-eyes approval on, production engines answer
`pending_approval` and wait for a second person in the console.

## Rule packages

A rule package holds functions and lookup tables written once, versioned and tested, and shared
between configurations. A configuration's `packages` list names each package with its version,
read `from` a file next to the configuration (with an optional `pin: sha256:...`) or written
inline. In a configuration-as-code repository, packages are files in the same repository:

- `syncd validate`, `syncd test` and the publish gate run each package's own tests.
- `syncd config diff` shows a package's version change and the functions that changed.
- With Git sync, the console reads package files from the repository at the version's commit
  (at the branch head for a version saved before 0.5.0), and the configuration editor's Packages
  panel says when the branch has a newer file. The console keeps no package files of its own: a
  configuration it publishes uses inline packages, packages from its Git repository, or files next
  to the engine's own configuration file.
- A package's WebAssembly module of custom functions (`wasm: { from, sha256 }`) is read from the
  repository beside its package file, like the file, and reviewed with the change that adds it; or,
  named by its hash alone, filled in from the console's module store once uploaded and approved
  there. Either way it is pinned by its SHA-256, and the console publishes it inside the
  configuration it signs. See
  [Computed attributes and rules](rules.md#custom-functions-in-webassembly).

[Computed attributes and rules](rules.md) documents packages, how rules call them, and test cases.

## Known limitations

- **Versions saved before 0.5.0** have no Git commit: their golden tests and package files are
  read at the branch head, and the version compare may fall back to a package's name, version,
  file and pin, with a note.
- **A version saved while its golden files failed** stays refused after the files are fixed at the
  branch head, since it is checked at its own commit: save it again.
- **The Promote preview** checks roles and shows each engine's diff summary only: the test cases,
  golden tests, license and hook run when you promote. `syncd config promote` has no dry run.
- **Roles:** Git sync is for roles on everything, and an API token limited to an environment or to
  engines cannot use it; there are no per-view scopes.
- **Strict engines:** `syncd apply-config` on the engine's host is refused once the engine is
  strict or requires signed configurations; configuration goes through the console. Changing the
  configuration without the console means editing the file as root on the host, which the console
  then reports.
- **Renaming an engine** (`syncd-console admin rename-engine`, run by the Helm chart when it turns
  HA on) doesn't update `CONSOLE_CONFIG_GIT_ENGINES`: change it by hand (the command and the
  console warn about it).
- **Git sync** runs the `git` program: `file://` repositories don't work in the container image,
  which has no shell.
