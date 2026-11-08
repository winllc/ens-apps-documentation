# Configuration as code

One base configuration, kept in Git, can run in several environments: development, test and
production. syncd 0.4.0 adds the pieces a team needs to work that way:

| Piece | What it does | Where |
|---|---|---|
| [Semantic diff](#what-changed-syncd-config-diff) | Tells what changed between two configurations by meaning: per connector, view, rule and publication field, with secrets redacted | `syncd config diff`, the console's **Compare** tab |
| [Environments](#environments) | `{{ env.<name> }}` placeholders in the base, filled from an environment's values | `syncd --env`, the console's **Environments** |
| [Git sync](#git-sync) | Commits on a branch become configuration versions in the console, and can be published | `CONSOLE_CONFIG_GIT_*` |
| [Promotion](#promotion) | Publishes a version proven in one environment to every engine of another | `syncd config promote`, `POST /api/v1/configs/promote` |
| [Golden-file tests](#golden-file-tests) | Check what whole publications contain after a run over fixture data | `syncd test --golden` |

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
  functions that changed.
- **Secrets are redacted.** A path segment that is or contains `password` or `secret`, is `token`
  or `key`, or ends in `_file` or `_key`, and a URL carrying `password=`, shows as
  `<redacted> (changed)`. Not even where a secret lives is printed. Two Active Directory flags,
  `password_never_expires` and `must_change_password`, are rules, not secrets, and are shown.

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
`semantic` and `summary` beside the unified text.

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
  (`connectors[0].connection.host: the environment has no value for hr_host`). Anything else in
  `{{ ... }}` is an error too.
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

### Environments in the console

- **Each engine runs in an environment**, `default` unless one is given when it is registered
  (`syncd-console admin register-engine --environment prod`, or the registration form) or set
  later by an admin (`PATCH /api/v1/engines/{id}` with `{"environment": "prod"}`).
- **Environments are kept under Integrations > Environments** (admins), or with
  `PUT /api/v1/environments/{name}`:

    ```json
    {"values": {"hr_host": {"value": "hr.prod.example.gov"},
                "hr_password_file": {"value": "/run/secrets/hr", "secret": true}}}
    ```

    A value marked secret is sealed with the master key and never returned: `GET` shows
    `{"secret": true}`, and a `PUT` that leaves its value out keeps the stored one. Changes are
    audited as `environment.changed` (the keys added, removed and changed, never a value). An
    environment that an engine runs in can't be removed. Reading environments needs a role that may
    view configuration (`configs:read` for a token); changing them is for admins, signed in.
- **A version is the base.** Publishing a version to an engine renders it for the engine's
  environment, with secret values in clear, and sends that. A value the environment lacks refuses
  the publish and names it.
- **What is shown is rendered with secrets masked:** the editor's check, saving a version, and
  the **Compare** tab, which renders both sides for the engine's environment. Its **Rendered for**
  selector picks another environment, or none to compare the texts as saved.
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
| `CONSOLE_CONFIG_GIT_URL` | none (off) | The repository: `ssh://` or `https://`. Plain `http://` is refused |
| `CONSOLE_CONFIG_GIT_BRANCH` | `main` | The branch to follow |
| `CONSOLE_CONFIG_GIT_PATH` | `syncd.yaml` | The configuration file in the repository. A relative path, without `..` |
| `CONSOLE_CONFIG_GIT_ENV_PATH` | none | A directory of `<name>.env.yaml` [environment files](#environment-files-from-git) |
| `CONSOLE_CONFIG_GIT_SSH_KEY_FILE` | none | A deploy key, for `ssh://` |
| `CONSOLE_CONFIG_GIT_KNOWN_HOSTS_FILE` | none | The Git server's host key, for `ssh://` |
| `CONSOLE_CONFIG_GIT_TOKEN_FILE` | none | A token, for `https://` (basic authentication; the user name from the URL, else `x-access-token`). Not with an SSH key |
| `CONSOLE_CONFIG_GIT_POLL` | `60s` | How often the branch is fetched |
| `CONSOLE_CONFIG_GIT_AUTO_PUBLISH` | `off` | `off`, `validate` or `publish` (below) |
| `CONSOLE_CONFIG_GIT_PUSH_BACK` | `false` | Commit versions saved in the console to the branch |
| `CONSOLE_CONFIG_GIT_ENGINES` | every engine | The engines the repository is for, comma-separated |

The console checks these at start-up and refuses both credentials at once, absolute or `..`
paths, and files that are not there.

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
   comment), unless the engine's latest version already has the same text.
3. What happens next depends on `CONSOLE_CONFIG_GIT_AUTO_PUBLISH`:

| Mode | What happens to the new version |
|---|---|
| `off` | It stays a draft. Someone publishes it from the console |
| `validate` | It is checked: compiled, with the rules' test cases, the packages' tests and the [golden tests](#golden-tests-in-the-console) from the repository. Failures are recorded as `config.git-failed`. It stays a draft either way, and one that fails can't be published |
| `publish` | It is checked as for `validate`, then published the way the **Publish** button does it: four-eyes approval (the version waits for approval), the pre-publish hook, and, when `CONSOLE_PUBLISH_TICKET_PATTERN` is set, a change-ticket reference found in the commit message (`HR port moved (CHG-12345)`). A commit without one is recorded as `config.git-failed` and its version stays a draft |

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
console user, with the subject `<engine> v<n>: saved (<comment>)`, and pushed. Nothing is
committed when the branch already has that text. A push the server rejects (the branch moved, a
protected branch) is recorded as `config.git-failed` with the reason. It is never forced. The next
pass sees the pushed commit and makes no second version of it.

With push-back on, the repository needs a deploy key or token that may write to the branch.

### Watching it

- **The Git card** on the configuration page: the last commit seen (its author and message), the
  last sync and the last error (a fetch that failed, a commit that did not compile, a refused
  secret, a rejected push), with **Sync now**. The same is `GET /api/v1/config-git` (anyone who
  may view configuration; `configs:read`), and **Sync now** is `POST /api/v1/config-git/sync`
  (designers and admins; `configs:write`).
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
| `--token-file` | A file holding an [API token](api-reference.md) with `configs:publish`, created by a designer or admin. `SYNCD_CONSOLE_TOKEN_FILE` does the same |
| `--ticket` | A change-ticket reference. Required when `CONSOLE_PUBLISH_TICKET_PATTERN` is set |
| `--note` | A note for the change record |
| `--ca` | PEM CA certificates the console's certificate chains to, when it isn't publicly trusted |

The command calls `POST /api/v1/configs/promote` with
`{"engine", "version", "to", "ticket", "note"}`. Every engine of the target environment except
the source gets the text as a version of its own (source `promotion`, `dev-1/v7`), rendered for
its environment, and the usual gates apply **on each engine**: four-eyes approval, the rules' test
cases, the ticket pattern and the pre-publish hook. Golden tests are not among them (see
[Golden tests in the console](#golden-tests-in-the-console)): run them in CI before you promote.

| Outcome per engine | Meaning |
|---|---|
| `published` | Published and running |
| `pending_approval` | Saved; waits for a second person's approval on that engine |
| `running` | The engine already runs this text; nothing changed |
| `failed` | Refused by a gate, or the engine couldn't take it; the detail says why |

The command prints one line per engine (`prod-eu v3: published`), with the detail. The API's
answer also carries, per engine, a one-line summary of the semantic diff against what the engine
ran before (`diff_summary`). The command exits `0` when no engine failed, `1` when any did, and
`2` when the console refused the request: a version never published, an unknown
engine, an environment with no engines, a token without the scope, a missing ticket.

Each target engine's outcome is recorded as `config.promoted` in the audit trail (from, to, the
status and the diff summary), and `config.promoted` is raised once as a webhook event.

Promotion is command line and API only: **the web console has no Promote button**.

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

`syncd test` reads the `*.yaml` files directly in the directory, not in its subdirectories. The
console also reads one level of subdirectories. Keep golden files directly in `tests/golden`, with
their fixtures and expected files in subdirectories, and both find the same tests:

```text
syncd.yaml
tests/golden/full-run.yaml
tests/golden/fixtures/hr.csv
tests/golden/fixtures/corp.ldif
tests/golden/fixtures/openldap-existing.ldif
tests/golden/expected/people_to_openldap.ldif
```

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

The server's publish gate itself (`POST /api/v1/engines/{id}/configs/{version}/publish`, which
API tokens and [promotion](#promotion) use) runs the rules' test cases but not the golden tests.
A publish through the API, or a promotion, goes ahead with a failing golden test, so run
`syncd test` in CI before either. Without Git sync, the console has no golden tests.

The console reads the golden files at the **branch head**, not at the commit of the version being
checked. A version from an older commit, or a draft that has moved ahead of the branch, is checked
against the newest tests and expected files.

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
`configs:publish`, and its creator must be a designer or admin. A version made from the commit
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
- With Git sync, the console reads package files from the repository (at the branch head), and
  the configuration editor's Packages panel says when the branch has a newer file. The console
  keeps no package files of its own: a configuration it publishes uses inline packages, packages
  from its Git repository, or files next to the engine's own configuration file.

[Computed attributes and rules](rules.md) documents packages, how rules call them, and test cases.

## Known limitations

- **Promotion** is command line and API only. There is no Promote button in the web console.
- **The console's golden tests** (and the package files it reads from Git) come from the branch
  head, not from the commit of the version being checked.
- **The publish endpoint and promotion** don't run golden tests; only the editor and Git sync's
  `validate` and `publish` modes do.
- **The console's version compare** shows a file package's name, version, file and pin, not the
  functions that changed. `syncd config diff` does show them.
- **`syncd test`** reads golden files only directly in its directory; the console reads one level
  of subdirectories too.
- **Git sync** runs the `git` program: `file://` repositories don't work in the container image,
  which has no shell.
