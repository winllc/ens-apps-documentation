# Troubleshooting

Symptoms, likely causes and fixes, for syncd 0.7.0 (tag `v0.7.0`, commit `6de2fa1`). Start with
`sudo syncd status`, `sudo syncdctl status` and the journal (`journalctl -u syncd -u syncd-console
-e`). Commands, metrics and alert rules are in the [Operator guide](operator-guide.md); settings
are in [Configuration](configuration.md); the license is in [Licensing](licensing.md). The source
repository's `docs/RUNBOOK.md` has the same entries for each alert, and the support bundle
(`sudo syncdctl support-bundle`) collects what support needs (see
[Support bundle](operator-guide.md#support-bundle)).

!!! tip "Just upgraded from 0.4.1?"
    Most surprises after the upgrade are on purpose: role maps that now add up
    ([Signing in](#signing-in)), `syncd apply-config` refused on a newly installed host
    ([The command line](#the-command-line)), clear-text password delivery refused at validation
    ([Installation and upgrade](#installation-and-upgrade)), capture agents that need `--source`
    or a bind, initial passwords that end in the dead-letter queue ([Passwords](#passwords)),
    a `TRUNCATE` that now deletes ([The engine at work](#the-engine-at-work)), and the 30-day
    evaluation that ends in a publishing block about 60 days after the upgrade
    ([The license](#the-license)).

!!! tip "Just upgraded from 0.5.0?"
    Several 0.5.1 fixes close a gap by refusing something 0.5.0 allowed: configurations that no
    longer validate ([Installation and upgrade](#installation-and-upgrade)), backups and HA
    snapshots of an engine that holds password keys refused to the command line's certificate
    or to a passive engine not listed under `api.access.passwords`
    ([Backups and restore](#backups-and-restore), [High availability](#high-availability)), a
    loopback listener with `api.local_reveal` alone ([The command line](#the-command-line)),
    cross-site changes and idle single sign-on accounts' API tokens in the console
    ([Signing in](#signing-in)), and an engine managed by a console without a license judged by
    its own evaluation period again ([The license](#the-license)). `syncd restore` must run as the
    engine's user. The full list is in
    [Upgrading from 0.5.0 to 0.5.1](install.md#upgrading-from-050-to-051).

!!! tip "Just upgraded from 0.5.1 or 0.5.2?"
    0.5.4 refuses nothing new. A value a rule makes of several related entities may change once,
    as they now come in the order of their ids; from 0.5.1, of two records waiting for the same
    entity the first in order of source and record joins it, and the `ambiguous` count grows more
    slowly. `syncd validate` may print two new warnings, each about a rule that reads nothing
    today ([The engine at work](#the-engine-at-work)). The full list is in
    [Upgrading from 0.5.1 or 0.5.2 to 0.5.4](install.md#upgrading-from-051-or-052-to-054).

!!! tip "Just upgraded from 0.5.4?"
    0.5.5 changes some things on purpose: control commands are refused while the engine exports
    after its initial load, releases held changes, writes back after an outage or delivers queued
    passwords, and a CSV file with a header and no rows deletes nothing
    ([The engine at work](#the-engine-at-work)); the console's `/healthz` answers 503 while a
    background task cannot work, which `syncdctl`'s health check sees too
    ([Installation and upgrade](#installation-and-upgrade)); a one-time password an administrator
    issued is refused after 7 days, and a password reset revokes the account's API tokens
    ([Signing in](#signing-in)); reserved addresses are refused for webhooks
    ([Webhooks, reports and Git sync](#webhooks-reports-and-git-sync)); and Debezium sources need
    an incremental snapshot after an HA takeover ([High availability](#high-availability)). 0.6.0
    adds the `dsml` connector ([DSML files across an air gap](#dsml-files-across-an-air-gap)), and
    0.6.1 fixes an install that stopped after `==> installing syncd`. The full list is in
    [Upgrading from 0.5.4 to 0.6.1](install.md#upgrading-from-054-to-061).

!!! tip "Just upgraded from 0.6.1?"
    0.7.0 adds governance, and changes a few things on purpose: the console's database is at
    schema 52, which 0.6.1's console refuses after a rollback without the database restored
    ([Installation and upgrade](#installation-and-upgrade)); the evaluation's `license:` line
    ends with `governance` ([The license](#the-license)); a password reset after single sign-on
    needs the provider's proof of a fresh sign-in, sessions from before the upgrade included, and
    the console's certificate needs `api.access.passwords` for it
    ([Passwords](#passwords)); a version that changes `passwords` is published by an admin only,
    scoped governance administrators get 404 and 403 outside their scope, list searches match
    from the start of a name, and campaigns start as **preparing**
    ([The console and its engines](#the-console-and-its-engines)); grants are sent again by
    themselves after a restore or a failover ([Backups and restore](#backups-and-restore),
    [High availability](#high-availability)). On Kubernetes, chart 0.7.0 is not published
    ([Kubernetes](#kubernetes)). The full list is in
    [Upgrading from 0.6.1 to 0.7.0](install.md#upgrading-from-061-to-070), and going back in
    [Rolling back once governance is in use](install.md#rolling-back-once-governance-is-in-use).

## Installation and upgrade

??? failure "`gpg: BAD signature`, `gpg: Can't check signature: No public key`, or `sha256sum: WARNING: ... computed checksum did NOT match`"
    New in 0.4.0, a signed release's `SHA256SUMS` is signed (`SHA256SUMS.asc`). `No public key`:
    import the syncd release key first (`gpg --import syncd-release.asc`), and check its
    fingerprint against the one you were given separately. `BAD signature`: `SHA256SUMS` is not
    the one that was signed; download both files again. A file whose checksum does not match is
    damaged or not the published one: download it again from the portal's **Downloads** page,
    and do not install it. A release without `SHA256SUMS.asc` was published without the signing
    key, as 0.5.0, 0.5.1, 0.5.4, 0.5.5, 0.6.0, 0.6.1 and 0.7.0 were: there is nothing to `gpg --verify`,
    and `sha256sum -c SHA256SUMS --ignore-missing` is the check. From 0.5.5 the release job
    refuses to publish an unsigned release unless the maintainers allow it
    (`ALLOW_UNSIGNED_RELEASE`), which they did for 0.5.5, 0.6.0, 0.6.1 and 0.7.0. The `.rpm`
    files are not signed either. The container images are verified another way: the 0.7.0 images,
    like 0.6.0's, are signed with cosign (see [Kubernetes](#kubernetes)). See
    [Verifying a release](install.md#verifying-a-release).

??? failure "`apt update` or `dnf` cannot reach `https://winllc.github.io/ldap-sync-merge-view` (404)"
    The signed apt and dnf repositories are not published yet: 0.5.0, 0.5.1, 0.5.4, 0.5.5, 0.6.0,
    0.6.1 and 0.7.0 were released without the signing key. Remove `/etc/apt/sources.list.d/syncd.sources` or
    `/etc/yum.repos.d/syncd.repo`, and install or upgrade from the `.deb` or `.rpm` downloaded
    from the portal's **Downloads** page. See
    [From the package repositories](install.md#from-the-package-repositories).

??? failure "`choose the console database: --local-database, or --database-url-file FILE (a file holding its postgres://... URL)`"
    `syncdctl install` needs to know where the console's database is. Pass `--local-database` to
    create it on this host's PostgreSQL, or `--database-url-file` with a file whose first line is
    the URL of a database you created (`--database-url URL` takes the URL itself, for one without
    a password: the command line is readable by every account on the host). Before 0.5.5 the
    message ended `or --database-url postgres://...`. `<file> is empty: the URL goes on its first
    line` and `cannot read <file>` are about that file. See
    [Installation](install.md#the-consoles-database).

??? failure "`cannot reach PostgreSQL as postgres on /var/run/postgresql (port 5432); is the server running?`"
    `--local-database` runs `psql` as the `postgres` account through the local socket. Start the
    PostgreSQL server (and on a fresh RHEL host, initialise it first), or point `SYNCD_PG_SOCKET`
    and `SYNCD_PG_PORT` at the right socket directory and port. `psql is required` means the
    PostgreSQL server or client is not installed at all. On RHEL 8, enable a PostgreSQL newer than
    the default 10 first (`dnf module enable postgresql:16`).

??? failure "`--fips needs a FIPS build of syncd`"
    The release you ran is not a FIPS build (it has no `FIPS` file, and no `-fips` in its name).
    Use the FIPS bundle, `syncd-<version>-linux-x86_64-fips.tar.gz` (or, from 0.5.0,
    `syncd-<version>-linux-aarch64-fips.tar.gz`; there are no FIPS packages). See [FIPS 140-3 and DISA STIG](fips-and-stig.md).

??? failure "`syncd <version> is installed already; to upgrade: syncdctl upgrade <release>`"
    `/etc/syncd/console.env` exists, so this host already has syncd. Upgrade it, or, to start
    again from nothing, `syncdctl uninstall --purge` first (it removes the configuration and the
    engine store, not the console's database). From 0.5.5 an installation that did not finish is
    finished instead (next entries).

??? failure "The install stops after `==> installing syncd 0.6.0`, with nothing else; running it again says `syncd did not start`"
    0.6.0's installer, on a fresh host: `openssl` failed while it made the control API's
    certificates, and what it said went nowhere. The run stopped after writing `console.env`, but
    before the certificates and the systemd units (no `/etc/syncd/pki/ca.key` or `engine.key`, no
    `syncd.service`). 0.6.0's installer run again, or its `syncdctl register`, only starts the
    services, which cannot start without them. Upgrades were not affected, and 0.5.5's installer
    does the same.

    Fixed in 0.6.1. Run 0.6.1's installer (`sudo ./syncd-0.6.1-linux-x86_64/install.sh` from the
    bundle), or install the 0.6.1 package and run `sudo syncdctl register`. Either finishes the
    installation as 0.6.1, with the configuration the first run wrote: it issues the missing
    certificates, installs the units, starts syncd and registers the engine. If `openssl` fails
    again, what it said is shown, followed by `openssl could not make <what> (above)`: the
    control API's CA, the engine's certificate, the console's or the command line's. See
    [Finishing an installation](operator-guide.md#finishing-an-installation).

??? failure "`syncdctl status`: `install:   NOT FINISHED (the engine is not registered in the console): syncdctl register`"
    New in 0.5.5. The installation did not finish: it was made with `--no-start`, its first start
    failed (`syncd did not start; ... Once that is fixed, finish the installation with: syncdctl
    register`), the engine could not be registered in the console, or the run stopped earlier. The
    same is said by a package installed again (`the installation did not finish: syncdctl register
    finishes it`) and by `syncdctl upgrade` (`syncd <version> is installed already (--reinstall to
    install it again); its installation did not finish: syncdctl register finishes it`). Fix the
    cause the earlier run showed, then run `sudo syncdctl register`, or the installer again: from
    0.6.1 either issues the certificates that are missing and installs the units before it starts
    the services. The installer finishes with its own release, and the options given to it again
    are not used. `this installation is for FIPS mode: finish it with a FIPS build of syncd`: use
    the FIPS bundle. See [Finishing an installation](operator-guide.md#finishing-an-installation).

??? failure "`<dir> is damaged: files do not match MANIFEST.sha256`"
    The unpacked release differs from what was built: a damaged download or a changed file.
    Download it again and check it against `SHA256SUMS` before unpacking.

??? failure "`syncd did not start; see the log above`, or `the engine did not become healthy within 120s`"
    `syncdctl` prints the last 25 lines of the failing service's journal. Common causes: the
    console cannot reach its database (wrong `CONSOLE_DATABASE_URL`, or PostgreSQL's
    `pg_hba.conf` refuses the account), a port already in use (7443, 8080, the metrics port), a
    secret the configuration names missing from `/etc/syncd/engine.env`, or a setting in
    `console.env` with a trailing comment (below). On a slow host or a large store, raise
    `SYNCD_HEALTH_TIMEOUT` (seconds). During an upgrade, the failure makes `syncdctl` roll back to
    the previous release and restore the backup by itself.

    `the console did not become healthy`, with the console running: from 0.5.5 `syncdctl` asks
    the console's `/healthz`, which answers 503 while a background task keeps stopping or cannot
    work. `curl -s http://127.0.0.1:8080/healthz` (or the console's own HTTPS address) names it,
    for example `webhook sender: its TLS configuration does not load: ...` for a
    `CONSOLE_WEBHOOKS_CA_FILE` that does not load: fix that setting in `console.env`.

    From 0.5.5 only the services that were running before are started again and checked: a console
    stopped on purpose stays stopped. An install's first start that fails ends `Once that is
    fixed, finish the installation with: syncdctl register`.

??? failure "`pg_dump is not installed: install the PostgreSQL client tools to back up the console database, or pass --skip-db-backup`"
    Upgrades and backups dump the console's database with `pg_dump`. Install the PostgreSQL client
    tools (`postgresql-client` on Debian and Ubuntu, `postgresql` on RHEL). Use `--skip-db-backup`
    only if you back up the database another way: without the dump, a rollback cannot restore it.
    On a package install the backup runs before the new files are unpacked, so the package
    upgrade fails here and nothing changes; from 0.5.1 the message ends `or set
    SYNCD_SKIP_DB_BACKUP=1 for the package manager (sudo SYNCD_SKIP_DB_BACKUP=1 apt-get install
    syncd, or the same with dnf)`. Upgrading a package from 0.5.0, 0.5.0's `syncdctl` takes that
    backup and honours `SYNCD_SKIP_DB_BACKUP=1` only when `pg_dump` is not installed at all.

??? failure "`pg_dump cannot back up the console database: ... (install the PostgreSQL client tools of the server's version, or pass --skip-db-backup)`"
    New in 0.5.1: an upgrade or a backup checks, before it stops anything, that `pg_dump` can dump
    the console's database. The usual cause is a `pg_dump` older than the server's major version
    (`aborting because of server version mismatch`): install the client tools of the server's
    version (on RHEL, the PostgreSQL module stream the server uses), or a server elsewhere that
    the account cannot reach. `--skip-db-backup` (for a package, `SYNCD_SKIP_DB_BACKUP=1`) leaves
    the database out. Nothing was stopped.

??? failure "A package upgrade: `the backup before the upgrade to <new> failed (above); syncd <old> was started again, and the package was not upgraded`"
    New in 0.5.1. The installed release's backup failed (the reason is above it: room, `pg_dump`,
    a copy that failed), so the package manager stopped before unpacking anything, and the
    services that were running were started again. Fix the cause, or leave the database out with
    `SYNCD_SKIP_DB_BACKUP=1`, and run the upgrade again.

??? failure "`not enough space in /var/lib/syncd/backups for a backup (... KiB needed: the engine store and the console's files, and room for the engine to go on writing; ... KiB free)`"
    An upgrade (and `syncdctl backup`) backs up the engine store and the console's files before it
    changes anything. From 0.5.5 the store's table files are hard-linked into the backup when it is
    on the store's file system, which is quick, but the backup keeps each one the engine later
    compacts away, so in time it takes as much room as the store: the check counts the store's
    whole size and the console's files, a tenth more, and 100 MiB (before 0.5.5, the copy and
    10 MiB). Free space, or remove old backups you have copied elsewhere.

??? failure "`the engine configuration is not valid for syncd <version>`"
    The new release's engine does not accept the running configuration. Nothing was stopped. Run
    the command it prints (`<release>/bin/syncd -c /etc/syncd/engine/engine.yaml validate`) to see
    why, fix the configuration (publish a fixed version from the console), and upgrade again.

    Upgrading to 0.5.1, which refuses what 0.5.0 let through:

    - `... unique() is only allowed in computed attributes`: `unique()` in a publication
      attribute, a DN attribute or filter, a group filter or `owner_from`,
      `passwords.initial.from` or a correlation hint, where it claimed nothing. Move it into a
      computed attribute and use that;
    - `deletion_threshold needs a percent, a count or both`, or `deletion_threshold: percent must
      be between 0 and 100`, on a connector or a publication;
    - `duplicate ... name ...: attribute names ignore case, so it is the same as ...`: two computed
      attributes, two flows of one source, or two publication attributes whose names differ only
      in case;
    - `the expression nests too deeply: more than 200 levels ...` or `the expression is too large:
      more than 100000 parts once its package functions are expanded` (also an operator chain
      longer than 400 terms): split it into steps (`let`) or computed attributes;
    - a WASM module written into the YAML (`packages[].wasm.module`): upload it in the console's
      module store and have it approved instead.

    Upgrading to 0.4.0, the usual reason is `publication <p>: Active Directory takes passwords only
    over LDAPS or StartTLS; target <t> connects to ldap://... without starttls`: a `passwords`
    block on an Active Directory target over plain LDAP, which 0.3.0 refused only when it wrote a
    password. Give the target an `ldaps://` URL or `starttls: true`, publish, and upgrade again.

    Upgrading to 0.5.0, the usual reason is the same rule for every LDAP directory:
    `publication <p>: passwords would go to target <t> in the clear (ldap://... without
    starttls): use ldaps:// or starttls, or accept it with passwords: { allow_cleartext: true }`
    (or `passwords.self_service: people's passwords would go to <source> in the clear ...`). A
    configuration that delivers passwords, initial passwords or self-service changes to a
    directory on another host over `ldap://` without `starttls` no longer validates. Loopback
    addresses and `ldapi://` are exempt. Add TLS (from 0.5.0 an LDAP connector takes
    `connection.ca_file` for a private CA), or accept it knowingly with `allow_cleartext`, which
    `syncd status` then reports as `passwords: WARNING: sent in the CLEAR to ...`.

??? failure "`<version> is older than the installed <version>`"
    `syncdctl upgrade` only moves forward. To go back, use `syncdctl rollback` or restore a backup;
    to install the same version again, `--reinstall`.

??? failure "`this host uses the syncd package: upgrade with the package manager`"
    The host was installed from a `.deb` or `.rpm`. Upgrade and remove it with `apt` or `dnf`.
    To roll back, install the previous package, then `syncdctl restore` the backup taken before
    the upgrade.

??? failure "`another syncdctl is running`"
    Another upgrade, rollback, install, backup or restore holds `/run/syncdctl.lock`. Wait for it
    to finish.

??? failure "`no backup of <old> was taken before upgrading to <new>`"
    `syncdctl rollback` restores the backup named `*-upgrade-<old>-to-<new>`, and it is gone (only
    the newest three backups are kept). Restore an older backup with `syncdctl restore`, or roll
    back the programs alone with `--keep-data` if the previous release reads the store's format
    and the console's schema did not change.

??? failure "`syncdctl rollback --keep-data`: `the console database is at schema N, and syncd <old>'s console knows schema M: it would not start on it`"
    New in 0.5.1. The newer release migrated the console's database (0.5.1 took it from 29 to 30,
    0.5.5 from 30 to 33, 0.7.0 from 33 to 52), and the older console refuses a database with
    migrations it does not know. Roll back without `--keep-data`, which restores the database from the backup taken
    before the upgrade (what was done in the console since is lost). From 0.5.5, when no backup
    records the database's schema or the database does not answer, `--keep-data` is refused too
    (`... whether syncd <old>'s console can start on the current database cannot be checked`),
    unless `--skip-schema-check` is added; before, those were warnings and the check was skipped.

??? failure "`the backup taken before the upgrade (...) has no console database dump (it was taken with --skip-db-backup), and the database's schema is N now, M under syncd <old>`"
    New in 0.5.5. The upgrade left the console's database out of its backup (`--skip-db-backup`,
    or `SYNCD_SKIP_DB_BACKUP=1` for a package), and the newer release changed the database's
    schema: the backup cannot put it back, and the older console would not start on it.
    `syncdctl rollback` changes nothing. Restore the console's database from a backup of your own
    taken before the upgrade, then run `syncdctl rollback` again, or stay on the newer release.
    The same holds when such an upgrade fails: its own rollback leaves the new release in place
    (`syncd <new> is not healthy, and the upgrade cannot be rolled back by itself: ...`). The
    upgrade warns before it starts (`the console database is not backed up (--skip-db-backup):
    ...`). `syncdctl restore` of a host-local backup without a dump keeps the database as it is,
    and refuses (`... and the database would have to be restored ... Nothing was changed`) when
    it would have to be restored.

??? failure "`not enough space in /var/lib/syncd to restore <backup> (... KiB needed, ... KiB free): nothing was changed`"
    New in 0.5.1. `syncdctl restore` and `rollback` copy the backup's engine store and console
    files in beside the live ones, and replace them only once the copy is complete, so they need
    room for that copy. Free space under `/var/lib/syncd` (old backups you have copied elsewhere,
    `replaced-<time>` directories left by earlier restores), and run the command again.

??? failure "`restoring <backup> failed: copying its <part> files to /var/lib/syncd did not complete (is the disk full?). The services are stopped.`"
    New in 0.5.1. The copy failed part way; the live files were not replaced. The message ends
    with how to try again: `syncdctl restore <backup>`, or, for a rollback, `syncd <new> is still
    the current release: once that is fixed, run syncdctl rollback again`. After an upgrade's own
    automatic rollback, the failed release's store has already been removed: fix the cause (often
    a full disk) and run `syncdctl rollback`, which restores the same backup.

??? failure "After a package downgrade, `helm rollback` or a restore of the programs alone to 0.6.1 (or older), the console does not start: `migration 34 was previously applied but is missing`"
    New in 0.7.0. 0.7.0 migrated the console's database from schema 33 to 52 (the governance
    tables and the rest of migrations 34 to 52), and 0.6.1's console stops at start on a
    database with migrations it does not know. It changes nothing in it, so the database is as
    0.7.0 left it, and there is no partial rollback. Either go back to 0.7.0, or restore the
    database taken before the upgrade: `sudo syncdctl restore
    /var/lib/syncd/backups/<time>-upgrade-0.6.1-to-0.7.0` after a package downgrade, `syncdctl
    rollback` on a bundle install (which does this itself), or on Kubernetes the database into an
    empty database before `helm rollback`. The governance records go back to how they were before
    the upgrade: what was requested, approved, reviewed or assigned since is gone. With the store
    kept, 0.6.1 leaves the grants in it alone (access granted by the console is then neither
    enforced nor reported, and holds again on 0.7.0), and refuses a configuration with 0.7.0's
    settings (`grants`, publications to SQL, JDBC and REST targets, `external` connectors): publish
    one without them first. See
    [Rolling back once governance is in use](install.md#rolling-back-once-governance-is-in-use).

??? failure "After a rollback or a package downgrade to 0.4.1, the engine does not start: `the store has format 2, written by a newer syncd`"
    0.5.0 migrated the store to format 2, which 0.4.1 cannot read. `syncdctl rollback` restores
    the store backed up before the upgrade, and `--keep-data` is refused for this reason; after a
    package downgrade or `helm rollback`, restore that backup yourself (`syncdctl restore
    /var/lib/syncd/backups/<time>-upgrade-0.4.1-to-0.5.0`, or on Kubernetes the volume). 0.4.1
    also refuses settings it doesn't know: where the configuration file is kept, publish one
    without the settings new in 0.5.0 first. The console's database gained migrations 0018 to
    0029, so it is restored from the same backup; what was done in the console since is lost. See
    [Rolling back 0.5.0 to 0.4.1](install.md#rolling-back-050-to-041).

??? failure "After a package downgrade from 0.5.1 to 0.5.0, the console does not start, and the message names the wrong backup"
    0.5.1's console database (schema 30) is newer than 0.5.0's console knows. Restore the backup
    taken before the upgrade, `sudo syncdctl restore
    /var/lib/syncd/backups/<time>-upgrade-0.5.0-to-0.5.1`: 0.5.0's `syncdctl` names the newest
    `*-upgrade-*-to-0.5.0` backup instead, which holds older data. From 0.5.1 a failed health check
    on an older package says `syncd <old> is not healthy on the data syncd <new> left` and names
    the right one. See [Rolling back 0.5.1 to 0.5.0](install.md#rolling-back-051-to-050).

??? failure "After a rollback to 0.3.0 with `--keep-data`, the engine does not start, or leavers, windows and canaries stopped"
    0.3.0 refuses settings it doesn't know: publish a configuration without the settings new in
    0.4.0 before rolling back with the store kept, or put the previous configuration back in
    `/etc/syncd/engine/engine.yaml` and restart. 0.3.0 also ignores the timers and held changes
    0.4.0 kept in the store: staged leaver steps, effective dates, change windows, canaries and
    group expiry wait on timers it does not act on. See
    [Rolling back 0.4.0 to 0.3.0](install.md#rolling-back-040-to-030).

??? failure "`Start request repeated too quickly` (systemd's `start-limit-hit`)"
    Before 0.5.5: systemd refuses to start a unit started more than 5 times in 10 seconds.
    `syncdctl` clears the limit before each of its restarts, so if you see it, the service is in a
    crash loop: read why in `journalctl -u syncd -e` (or `-u syncd-console`), fix it, then
    `sudo systemctl reset-failed syncd` and start it. From 0.5.5 the units have no start limit and
    wait longer between restarts, from 10 seconds to 5 minutes (10 seconds each time before
    systemd 254): a service that keeps failing shows `activating (auto-restart)` in `systemctl
    status`; read why in the journal. Upgrades install the new units.

??? failure "`syncdctl renew-certs`: `no CA key in /etc/syncd/pki: the certificates were not issued by syncdctl`"
    `renew-certs` issues new certificates from the CA's key, which is missing. Certificates you
    placed there yourself are yours to renew; then register the engine again in the console with
    `syncd-console admin register-engine --update`.

??? failure "`syncdctl renew-certs`: `the engine did not start with the new certificates; putting the previous ones back`"
    The engine did not become healthy with the new certificates within `SYNCD_HEALTH_TIMEOUT`.
    `renew-certs` put the previous ones back (they are also in
    `/etc/syncd/pki/before-renewal-<time>`), restarted the engine and stopped with
    `certificates not renewed (journalctl -u syncd)`. Read the engine's log for the reason.

??? failure "`syncdctl renew-certs`: `the console did not take the new credentials`"
    The engine runs with the new certificates, but registering them in the console failed (the
    console's database unreachable, say). Until it is fixed the console cannot reach the engine.
    Register them yourself, as `syncd-console` with the console's settings (the command in
    [First sign-in](install.md#first-sign-in), with `admin register-engine --name local --address
    127.0.0.1:7443 --server-ca /etc/syncd/pki/ca.crt --client-cert
    /etc/syncd/pki/console-client.crt --client-key /etc/syncd/pki/console-client.key --update` in
    place of `admin reset-password ...`), then restart the console.

??? failure "`syncdctl status`: `certificates: ... EXPIRES WITHIN 60 DAYS, renew with syncdctl renew-certs`"
    The first of the control API's certificates expires within 60 days. Run
    `sudo syncdctl renew-certs`, or `renew-certs --new-ca` when it is `ca.crt` that expires. See
    [Certificates](operator-guide.md#certificates). On the hosts of an HA pair, replace the
    `localhost` engine certificate it issues with one naming the host afterwards.

??? failure "`the engine store is in format N, which syncd <old> cannot read`"
    The newer release migrated the store. Roll back without `--keep-data`, which restores the
    backup taken before the upgrade. See [Store format](install.md#store-format).

## Startup

??? failure "The engine stops at startup: `the store has format N, written by a newer syncd`"
    The store was migrated by a newer release, and this older engine refuses to read it. Run the
    newer release again, or restore the backup taken before upgrading to it (`syncdctl rollback`).

??? failure "`the store has format N and this syncd uses format M: start the engine (syncd run) once to migrate it`"
    A read-only command (`syncd dlq`, `syncd store-format`) found a store from an older release.
    Start the service once; it migrates the store, logging `migrating the store` and
    `store migrated`.

??? failure "`opening the store` fails with a RocksDB lock error"
    Another `syncd` process holds the store: you ran `syncd run` by hand while the service runs,
    or an engine is still stopping. Only one engine can open its store. Use `syncd status` and the
    other control commands against the running service instead. `syncd restore` refuses while
    the engine runs for the same reason.

??? failure "After `syncd restore`, the engine cannot open its store or its keys (permission denied)"
    From 0.5.1 every file `syncd restore` puts in place, the store's included, is mode 0600, owned
    by whoever ran it. Run as root, it leaves files the `syncd` account cannot read. Run it as the
    engine's user, `sudo -u syncd syncd restore ...`, as `syncdctl restore --from` does; to repair
    a restore made as root, `sudo chown -R syncd:syncd /var/lib/syncd/engine` and start the
    engine. On Kubernetes, run the restore as the image's user (65532).

??? failure "`starting the metrics listener on <addr>`, or the control API cannot listen"
    The address in `api.metrics_listen` or `api.listen` is in use. A taken port stops startup.
    Find the process (`ss -ltnp`), or change the address in `/etc/syncd/engine/engine.yaml` and
    restart syncd.

??? failure "The console fails to start, or refuses a setting, after uncommenting a line of `console.env`"
    systemd reads `/etc/syncd/console.env` (and `engine.env`) as an `EnvironmentFile`: everything
    after the `=` is the value, including a `# ...` note at the end of the line. The templates of
    0.4.0 and earlier had such notes, for example
    `#CONSOLE_PUBLISH_HOOK_ON_ERROR=refuse   # or allow` or
    `#CONSOLE_CONFIG_GIT_AUTO_PUBLISH=off   # off | validate | publish`, and your file, written at
    install, keeps them; 0.4.1's templates keep notes on lines of their own. Uncommented as it is,
    the value is `refuse   # or allow`, which the console refuses, or takes as a wrong value. From
    0.4.1 the console's error adds `` <setting> holds ` #` (a comment at the end of the line becomes
    part of the value) ``; the engine gives no such hint. Move the note to a line of its own, and
    restart: `systemctl show syncd-console -p Environment` shows the values systemd passes.

??? failure "The console will not start: `refusing to start: the supplied admin password is empty or admin`"
    `CONSOLE_ADMIN_PASSWORD` or `CONSOLE_ADMIN_PASSWORD_FILE` supplies a first password that is
    empty, `admin`, or breaks the password rule (12 characters or more, not the username). Set a
    better one, or remove the setting to have the console generate one.

??? failure "The console will not start: a single sign-on setting"
    The console checks the settings at startup and names the one that is wrong:

    - `CONSOLE_OIDC_CLIENT_ID is required with an issuer`, `CONSOLE_OIDC_CLIENT_SECRET (or _FILE) is required with an issuer`;
    - `CONSOLE_PUBLIC_URL is required for single sign-on`;
    - `... must use https (plain http only for loopback)`: the issuer is `http://` on another host;
    - `role map entry "..." is not value=role`, or `unknown role`: the roles are `admin`,
      `designer`, `operator`, `viewer` and `auditor`; use `;` between entries when values contain
      commas. From 0.5.0 a role may carry a scope (`group=operator@prod`,
      `group=designer@engine:hr-1,hr-2`), and `role map entry "...": ...` names what is wrong with
      it. The same applies to `CONSOLE_SAML_ROLE_MAP` and `ENS_X509_ROLE_MAP`;
    - `CONSOLE_OIDC_DEFAULT_ROLE: unknown role ...`.

    Fix `/etc/syncd/console.env` and `systemctl restart syncd-console`. See
    [Single sign-on](single-sign-on.md).

??? failure "The console will not start: `CONSOLE_REVEAL_ROLES: ... is not a role (or none)`"
    List roles by name (`admin`, `designer`, ...), comma-separated, or `none`.

??? failure "The console will not start: `.../master.key is missing, and the database holds secrets sealed with the master key it held`"
    The key file is gone (a restore that missed it, a new data directory), and the console's
    database holds secrets only that key opens: the client keys of engines registered with mTLS,
    webhook addresses and secrets, secret environment values. Restore
    `/var/lib/syncd/console/master.key` from a backup (owner `syncd-console`, mode 0600), or set
    `CONSOLE_MASTER_KEY` to the old key, and restart. Off-host backups taken with 0.4.1's
    `syncdctl backup --to` hold it (`<name>.console-master.key`), and `syncdctl restore --from`
    puts it back; those taken with 0.4.0 do not. If the key is lost for good, see
    [The master key](operator-guide.md#the-master-key): `CONSOLE_NEW_MASTER_KEY=true` starts the
    console with a new key, and the engines must be registered again.

??? failure "The console will not start: `the master key does not open the secrets the database holds (engine <name>'s client key)`"
    Or `(webhook <name>'s URL)`, `(webhook <name>'s signing secret)`, `(environment <env>'s secret
    value <key>)` or `(the ... configuration signing key <id>)`, several separated by commas. From
    0.5.0 the check at start opens one sealed secret of each kind, not only the first one found as
    in 0.4.1: **a database with secrets sealed under two keys that 0.4.1 started may now refuse to
    start**, naming what the key does not open. Find the key those secrets were sealed with (an
    older backup of `master.key`), or, if it is gone, start once with
    `CONSOLE_NEW_MASTER_KEY=true` and set those secrets again.
    The key, from the file or `CONSOLE_MASTER_KEY`, is not the one the secrets were sealed with:
    often the key of another installation, or a database restored without its own key. Put back
    the key that belongs with this database. Only if it is lost for good, set
    `CONSOLE_NEW_MASTER_KEY=true` (or `--new-master-key`); the console logs that engines
    registered with mTLS must be registered again, webhooks given their URL and secret again,
    secret environment values entered again and the configuration signing key rotated. On
    Kubernetes, this is what a deleted `syncd-console` Secret gives: see [Kubernetes](#kubernetes).
    With `CONSOLE_MASTER_KEY_KMS`, the console must also reach the KMS: there is no fallback.

??? failure "The console will not start: `reading the TLS certificate ...`, `... holds no certificate`, `reading the TLS key ...`, or `the TLS certificate and key do not go together`"
    `CONSOLE_TLS_CERT` or `CONSOLE_TLS_KEY` (the console's own HTTPS) names a file the console
    cannot read, a certificate file without a PEM certificate, or a key for another certificate.
    The files must be readable by `syncd-console`; the certificate file holds the certificate,
    followed by its chain. Setting only one of the two is refused too. See
    [The console's own HTTPS](install.md#the-consoles-own-https).

??? warning "`the changed TLS certificate does not load; keeping the previous one`"
    A renewed certificate or key written to `CONSOLE_TLS_CERT` or `CONSOLE_TLS_KEY` does not load,
    often because the certificate was replaced before its key, or the reverse. The console keeps
    serving the previous certificate and checks again every minute, so writing the matching pair
    is enough (`TLS certificate loaded again`).

??? failure "The console will not start: `master key is not base64`, or `the master key must be 32 bytes`"
    `CONSOLE_MASTER_KEY` is set but is not 32 bytes in base64. Correct it, or remove it to use
    `/var/lib/syncd/console/master.key`. See [The master key](operator-guide.md#the-master-key).

??? failure "The engine refuses its configuration: `engine.ha.lease: ttl_secs must be at least 3`, `engine.ha: snapshot_every_secs must be at least 10`"
    The pair's settings are checked with the rest of the configuration. Raise the values (the
    defaults are 15 and, from 0.5.0, 60; 300 before). See
    [High availability](availability-and-sizing.md#high-availability).

??? warning "`password agents enrolled before 0.5.0 are bound to no source and may submit changes for any`"
    New in 0.5.0. An agent enrolled before the upgrade keeps working, but may submit changes for any
    source; the engine warns at every start, `syncd status` says `password agents: WARNING: ...
    bound to no source`, and `syncd password agent list` marks it. Bind each:
    `sudo syncd password agent bind <name> --source <connector>` (repeat `--source` for an agent
    that serves several). Such agents are also asked to renew their certificate at their first
    exchange; `syncd-capture` does it by itself.

??? failure "The engine will not start: `the key is sealed with a passphrase, and none is set`, or `the passphrase (SYNCD_PASSWORD_KEY_PASSPHRASE_FILE) does not open the sealed key`"
    New in 0.4.1. The password key or the agents' CA key is sealed (see
    [The password keys](operator-guide.md#the-password-keys)), and the engine has no passphrase,
    or another one: the variable is missing from `/etc/syncd/engine.env` (or the pod's
    environment), names another file, or the keys came from another engine (a restored backup,
    the other engine of an HA pair) sealed with its passphrase. The key files are left as they
    are. Set `SYNCD_PASSWORD_KEY_PASSPHRASE_FILE` to a file holding the passphrase the keys were
    sealed with, and start again. `cannot read the passphrase file ...` or `the passphrase file
    ... is empty`: the file is missing, not readable by `syncd`, or empty. A lost passphrase
    cannot be recovered: move both key files aside and start with new keys (queued changes are
    reported failed; agents enroll again).

??? failure "After a rollback to 0.4.0, the engine will not start: `loading the password key ...`"
    0.4.0 cannot open password keys sealed by 0.4.1. Put the plain files back from a backup taken
    before they were sealed, such as the upgrade's (see
    [Rolling back 0.4.1 to 0.4.0](install.md#rolling-back-041-to-040)).

## Signing in

??? failure "Sign-in does not stick: `You signed in, but your browser did not keep the session.`"
    The console's session cookie is `Secure`, so browsers keep it only over HTTPS or on
    `localhost`. The console was opened over plain HTTP from another machine
    (`http://<server>:8080`). The sign-in page also shows: *The console's session cookie is only
    kept over HTTPS (or on localhost).* Give the console a certificate (`CONSOLE_TLS_CERT` and
    `CONSOLE_TLS_KEY`), or put nginx with TLS in front of it, and open its `https://` address; see
    [HTTPS in front of the console](install.md#https-in-front-of-the-console). The console logs a
    warning at startup when it listens beyond loopback over plain HTTP without an `https://`
    `CONSOLE_PUBLIC_URL`. On the host itself, `http://127.0.0.1:8080` works.

??? warning "After the upgrade to 0.5.0, someone signed in through single sign-on has more rights than before"
    From 0.5.0 **every role map entry that matches adds its role** (`CONSOLE_OIDC_ROLE_MAP`, and the
    new `CONSOLE_SAML_ROLE_MAP` and `ENS_X509_ROLE_MAP`); in 0.4.1 the first match won. Someone in
    two mapped groups gets both roles at their next sign-in. Review maps that relied on their
    order: remove the entries that should no longer apply, or give a role a scope
    (`operator@test`). The audit trail's sign-in records now carry `roles` (a list) instead of
    `role`, and events carry `details.assignment`: update SIEM parsers.

??? failure "`acknowledge the notice on the sign-in page before signing in` (403)"
    New in 0.5.0. The notice-and-consent banner is on (`ENS_CONSENT_BANNER_ENABLED`), and every
    sign-in method refuses until the banner was acknowledged from the same browser within 15
    minutes. People press the banner's button. A script that signs in with a password must first
    `POST /api/v1/auth/consent` and send the cookie it gets with the sign-in. API tokens are not
    affected.

??? failure "`sign-in failed; after repeated failures an account locks for 15 minutes`"
    Every failed local sign-in gets this same answer, so it reveals nothing about the account.
    The text says 15 minutes whatever the lockout settings are: from 0.5.0 `ENS_LOCKOUT_ATTEMPTS`
    (5), `ENS_LOCKOUT_WINDOW` and `ENS_LOCKOUT_DURATION` (15 minutes; 0 locks until an
    administrator unlocks) change them. The audit trail (**Audit log**, event `auth.login`)
    records the real reason:

    | Reason | Fix |
    |---|---|
    | `wrong password`, `unknown account` | Check the username and password |
    | `locked` | Five wrong passwords (by default) lock the account for 15 minutes. Wait, or have an administrator reset its password (**Users**, the reset button on the account), which also unlocks it |
    | `disabled` | An administrator disabled the account in **Users** |
    | `no local password` | The account signs in through single sign-on; use the single sign-on button |
    | `local sign-in is limited to break-glass accounts` | An administrator limited local sign-in. Use single sign-on, or a break-glass account under "Break-glass sign-in" |

    If every administrator is locked out, issue a new one-time password on the host:
    `syncd-console admin reset-password --user admin`, run as `syncd-console` with the console's
    settings (the full command is in [First sign-in](install.md#first-sign-in)). It unlocks the
    account and signs out its sessions.

??? failure "`admin reset-password`: `<user> signs in through single sign-on and has no console password`"
    The account signs in through the identity provider, and a console password would let it sign
    in without the provider and its MFA, so the command refuses, as the **Users** page does. Sign
    in through single sign-on. If the provider is unavailable and you need the account now, add
    `--allow-single-sign-on`; the audit trail's `user.password_reset` then records
    `single_sign_on: true`. `no account named <user>` means there is no such account.

??? failure "An account locked while sign-in could not stick"
    Repeated attempts over plain HTTP can lock the account before anyone notices the cookie
    problem. Fix the HTTPS access first, then reset the password as above.

??? failure "HTTP 429 at sign-in: too many requests"
    Sign-in is limited to 20 attempts per client address in 5 minutes. Behind nginx without
    `CONSOLE_TRUSTED_PROXIES=127.0.0.1`, every user appears as `127.0.0.1` and shares one budget.
    Set it (see [Installation](install.md#https-in-front-of-the-console)) and restart the console.

??? failure "`change your password first`"
    The account has a one-time password. Set a new one on the page the console shows; nothing else
    works until then. The new password is refused with `a password needs at least 12 characters`,
    `that password is too easy to guess` (it is the username or `admin`), or `choose a password you
    have not used here` (it is the current one). `the current password is wrong` means the one-time
    password was mistyped. From 0.5.1 wrong current passwords count toward the lockout, and an
    account with a temporary password gets an answer on the password change only.

??? failure "`this one-time password has expired: ask an administrator for a new one` (403)"
    New in 0.5.5. A one-time password an administrator issues (a new account, a reset on the
    **Users** page or with `syncd-console admin reset-password`) is good for 7 days. Past that it
    is refused, and recorded as a denied sign-in. An administrator resets the password again. The
    `admin` account's initial password, from the first start, does not expire this way. See
    [Password resets and lockouts](admin-guide.md#password-resets-and-lockouts).

??? failure "After a password reset, the account's API tokens are refused (401)"
    New in 0.5.5. A reset, on the **Users** page or with `syncd-console admin reset-password`,
    revokes every API token the account created: a reset is how an account whose password was lost
    or taken is given back, and what was made with the old password goes with it. The audit
    record (`user.password_reset`) lists the tokens revoked. Create new ones once the account has
    signed in again.

??? failure "A change is refused: `refused: a change made with the console's session must come from the console's own pages`"
    New in 0.5.1. A change made with the session cookie (not with an API token) must come from the
    console's own pages: the browser's `Sec-Fetch-Site`, or else its `Origin`, must name the
    console. Behind a proxy that rewrites the `Host` header, the console may not recognise its own
    address: have the proxy pass the original `Host` (or `X-Forwarded-Host`), or set
    `CONSOLE_PUBLIC_URL` to the address people use, which the check accepts too. Scripts should
    use API tokens, which this check does not apply to.

??? failure "An API token stops working: `the account that created the token signs in through single sign-on and has not signed in for 35 days; the token works again once it has`"
    New in 0.5.1. The console cannot ask the identity provider whether such an account is still
    allowed in, so its tokens expire while it does not sign in for `CONSOLE_API_SSO_CREATOR_MAX_IDLE`
    (35 days by default; `0` turns this off). Have the account sign in once, or create the
    token from an account that signs in regularly.

??? failure "Local accounts cannot sign in after turning on FIPS mode"
    In FIPS mode the console hashes passwords with PBKDF2-HMAC-SHA512 and refuses the Argon2id
    hashes written without it. Reset those accounts' passwords (**Users**, or `admin
    reset-password` on the host). See [FIPS 140-3 and DISA STIG](fips-and-stig.md).

??? failure "Single sign-on: `the identity provider refused the sign-in: <error>`"
    The provider sent the browser back with an error, shown with its description. Often the
    redirect URI: register exactly `<CONSOLE_PUBLIC_URL>/api/v1/auth/oidc/callback` with the
    provider. `the identity provider refused the sign-in (<status>)` means the token request was
    refused: check `CONSOLE_OIDC_CLIENT_ID` and the client secret.

??? failure "Single sign-on: `this sign-in was not started from this browser; start again`"
    A cookie ties a sign-in to the browser that started it, and it was missing on the way back.
    Usually the console was opened at one address and `CONSOLE_PUBLIC_URL` names another, or over
    plain HTTP. Open the console at its `CONSOLE_PUBLIC_URL`, over HTTPS.

??? failure "Single sign-on: `this sign-in expired or was already used; start again`"
    A sign-in must finish within 10 minutes, and each one is used once. A page reloaded on the
    callback, or a slow sign-in at the provider, gets this. Start again.

??? failure "Single sign-on: `<user> has no console role: none of their groups matches the role map`"
    (With SAML, the same for its role attribute; with a client certificate, `nothing in the
    certificate matches the role map`.) The ID token's role claim (`CONSOLE_OIDC_ROLE_CLAIM`,
    default `groups`) has no value listed in `CONSOLE_OIDC_ROLE_MAP`. Check that the provider puts the claim in the ID token (some need an
    extra scope in `CONSOLE_OIDC_SCOPES`, or a mapper), that the values match exactly (group names
    or DNs as the provider sends them), or set `CONSOLE_OIDC_DEFAULT_ROLE`.

??? failure "Single sign-on: `a console account named <name> already exists and is not linked to this identity`"
    A local account has the same username. The console never signs an identity in to a local
    account. An administrator renames or removes the local account, or the provider sends another
    username (`CONSOLE_OIDC_USERNAME_CLAIM`).

??? failure "Single sign-on: `the console account <name> is disabled`"
    An administrator disabled the account in the console. That keeps it out whatever the provider
    says. Enable it in **Users**.

??? failure "Single sign-on: `the identity provider did not answer`"
    The console cannot reach the issuer's discovery document, keys or token endpoint. Check DNS,
    the firewall and any proxy from the console's host. For a provider with a certificate from a
    private CA, set `CONSOLE_OIDC_CA_FILE`.

??? failure "Single sign-on: `the provider says its issuer is <A>, not <B>`"
    `CONSOLE_OIDC_ISSUER` must be the issuer exactly as the provider's discovery document gives
    it (a trailing `/` is ignored). Copy it from `<issuer>/.well-known/openid-configuration`.

??? failure "Single sign-on: `the ID token is not valid: ...`"
    - `HS256 signatures are not accepted`: the console accepts only asymmetric signatures (RSA,
      RSA-PSS, ECDSA, EdDSA). Configure the client to sign ID tokens with one.
    - `signed with a key the provider does not publish`: the provider rotated its keys, or signs
      with a key outside its JWKS. The console fetches the keys again periodically; try again, and
      check the provider's key configuration.
    - `the nonce does not match this sign-in`, or an expiry, audience or issuer error: start
      again, and check the host's clock.

??? failure "The single sign-on button gives `single sign-on is not configured`"
    `CONSOLE_OIDC_ISSUER` is not set in `console.env`, or the console was not restarted after
    setting it.

## The console and its engines

??? failure "The engine page shows `cannot reach <address>: ...` or `<address> did not answer in time`"
    The console cannot connect to the engine's control API (5 s to connect, 30 s per call). Check
    that syncd is running (`systemctl status syncd`) and listening on the registered address, and
    for mutual TLS that the registered CA and client certificate still match `/etc/syncd/pki`.
    A client certificate that has expired fails the handshake; the engine page warns 60 days
    before (`Certificate expires soon`, then `Certificate expired`). On the engine's host,
    `sudo syncdctl renew-certs` renews the certificates and gives the console the new ones. On
    Kubernetes, see [Certificates on Kubernetes](operator-guide.md#certificates-on-kubernetes).

??? failure "Registering an engine: `engine <name> is registered, but this console's master key cannot open its stored client key`"
    New in 0.4.0. The console was started with a new master key (`CONSOLE_NEW_MASTER_KEY`, or a
    restore with another key), and the engine's stored client key no longer opens. A plain
    registration refuses, rather than answer `registered already` and leave a console that cannot
    reach the engine. Register it again replacing the old registration:
    `syncd-console admin register-engine ... --update` (or `replace: true` through
    `POST /api/v1/engines`). The engine keeps its id and configuration versions, and the audit
    trail records `engine.re-registered`. See [Registering engines](install.md#registering-engines).

??? failure "Registering an engine: `an engine off loopback needs mTLS`"
    An engine on another address must be registered with its CA and the console's client
    certificate and key. Plain text is allowed only to a loopback address or a `unix:` control
    socket. Other refusals:

    - `the address must be host:port, or unix: and the path of the engine's control socket`;
    - `a control socket address is unix: and an absolute path (unix:/run/syncd/control.sock)`;
    - `a unix: address is the engine's control socket, which takes no mTLS`: leave the
      certificates out;
    - `an engine named <name> exists`: choose another name, or, from the command line, replace its
      registration with `register-engine --update`;
    - `give all of server_ca_pem, client_cert_pem and client_key_pem, or none`.

    A `unix:` address that does not answer is usually one the console's account may not open: the
    socket is mode 0600, for the engine's own user. See [Registering engines](install.md#registering-engines).

??? failure "`the <role> role cannot do this`, `none of your roles (...) allows this on ...`, or an engine that answers 404"
    The account's role does not allow the action: registering engines and managing accounts need
    `admin`; editing and simulating configuration need `designer`. From 0.5.0 a role may be held on
    one environment or on named engines only: `none of your roles (operator@test) allows this on
    ...` means it does not reach that engine, `this needs a role that covers everything` that the
    action is global (accounts, settings, webhooks, Git sync, registering engines, the whole audit
    trail), and an engine outside the account's roles answers 404 and is left out of lists. An
    API token limited to an environment or engines behaves the same way. Upgraded accounts hold
    their former role on everything. From 0.4.1 an environment's
    values (`GET /api/v1/environments[/{name}]`) need `admin` or `auditor`, or an API token with
    the `environments:read` scope (or `admin`); a script that read them with a `configs:read`
    token needs the new scope. Every role still reads the names (`GET /api/v1/environment-names`).
    See [Admin guide](admin-guide.md).

??? failure "A scoped governance administrator gets 404 for a person, account or system, or 403: `<what> needs a governance role on everything, not on a scope`"
    New in 0.7.0, on purpose. An administrator holding `governance_admin` on a governance scope
    (`governance_admin@governance:<scope>`) reaches only the people and systems of that scope: on
    every route, a person, account, entitlement, system or request outside it answers 404, as one
    that does not exist. What has no scope of its own answers 403: campaigns and their definitions
    (reads included), changes to roles, policies, workflows and lifecycle rules, the governance
    reports, the risk score's settings, workgroups, scopes, registering systems, imports and
    connected products. Give the work to an administrator on everything, or the role on
    everything. See [Scopes](admin-guide.md#scopes).

??? failure "Publishing is refused: `this version changes password synchronization's settings (a passwords block): publishing it needs manage_passwords on the engine (an admin), with a session`"
    New in 0.7.0. A version whose `passwords` settings differ from the version published before
    (or that cannot be read) is published only by an `admin` whose role reaches the engine,
    signed in to the console with a session: a designer is refused. The capture agents' page
    (**Password agents**, new in 0.7.0) is for the same administrators. Ask an administrator to publish it; versions that do
    not touch `passwords` publish as before.

??? failure "A role version cannot be approved: `a version is approved by someone other than its proposer`, or claiming an approval: `you asked for this access or it is for you: another member of the team takes it` (403)"
    New in 0.7.0 (four eyes on roles, and approvals by a workgroup). The person who proposed a
    role's version, or who created the API token that proposed it, cannot approve it: another
    governance administrator does. A member of a workgroup cannot claim an approval of a request
    they made or that is for them; another member takes it.

??? warning "A campaign stays **preparing** (state `draft`)"
    New in 0.7.0. Starting a campaign answers at once (202) with the campaign as a `draft`, while
    its items are made in the background; it turns `active`, and its reviewers are told, once every
    reviewer has their items. On a large catalog that takes a while (about a quarter of an hour
    for a manager campaign over a million people's access, before the release's fixes; see
    [Sizing the console for governance](availability-and-sizing.md#sizing-the-console-for-governance)).
    A preparation that stopped (the console restarted, or another replica took over) is resumed
    by the clock. If an attempt failed, the campaign's page says why and that it is tried again; a
    campaign that could not start is `cancelled`, with the reason. An API client that waited for
    `active` in the answer to `POST /api/v1/governance/campaigns` now polls the campaign.

??? failure "A campaign report is refused: `the XLSX report holds at most 200000 items and this campaign has N: download the CSV, which has every item`"
    New in 0.7.0: a campaign's report downloads as CSV at any size, but as Excel only up to
    200,000 items and as PDF up to 20,000 (`the PDF report holds at most 20000 items ...`).
    Download the CSV.

??? failure "An API client breaks on `GET /api/v1/governance/entitlements`, or a list's `after` cursor is refused"
    New in 0.7.0. The entitlements list answers a page, `{"entitlements": [...], "next_after":
    "..."}`, with `q`, `ids`, `requestable`, `after` and `limit`, instead of a list capped at
    5,000: read `entitlements`, and pass `next_after` as `after` until it is empty. The cursors
    of the identity and account lists are opaque strings whose format changed: do not build them,
    and start again from the first page after the upgrade.

??? warning "A search in the identity or account list no longer finds a name by its middle"
    New in 0.7.0. These searches match from the start of the login, name, mail or account name
    (and of the first RDN's value), using indexes, so they stay fast with a million people:
    `smi` finds `smith`, `ith` does not. Substring search remains among one person's accounts.

??? warning "The governance dashboard's counts are a few minutes old"
    New in 0.7.0: they are served from the last measurement, at most about five minutes old, and
    the page shows its time. They are counted across every scope.

??? warning "Time-limited access ended, and its assignment shows ended with a removal queued"
    New in 0.7.0, on purpose: an assignment or role assignment ends in the console at its end
    date, and the removal is queued to the engine. Before, it stayed pending once the engine had
    ended the grant. The engine ends the grant at its end date by itself too, while the console is
    down.

??? failure "A published configuration is refused: `engine.data_dir cannot be changed through the control API`, `the api section can only be changed on the engine host` or `engine.ha can only be changed on the engine host`"
    The version changes settings that stay with the engine host. Start from the running
    configuration (import it in the console), keep its `engine.data_dir`, `api` and `engine.ha`
    as they are, and change those only in `/etc/syncd/engine/engine.yaml` on the host. From 0.5.0
    so are `engine.require_signed_config` with `engine.config_signing_keys`, and
    `engine.license_file`, and from 0.5.1 `passwords.key_file` (`passwords.key_file can only be
    changed on the engine host`). The usual case after the upgrade: `syncdctl api-access strict` added
    `api.access` on the host, and the console's next version still has the old `api` section;
    import the running configuration once.

??? failure "A publish is refused: `this engine applies only configurations its console signed`, or `the configuration's signature is not valid here: ...`"
    New in 0.5.0. The engine has `engine.require_signed_config`. The first comes from
    `syncd apply-config` or any client other than the console: publish from the console. The
    second means the console signed with a key the engine's `engine.config_signing_keys` file does
    not hold (a key rotated or replaced in the console, `--replace` after a lost master key):
    install the console's current key (`sudo syncdctl api-access signing-key --from-console`, or
    the file `syncd-console admin config-signing-key --out` writes), or finish the rotation with
    `--activate` only after every engine has the new key.

??? warning "The engine page warns that the engine runs a configuration the console did not publish (`config.unpublished-running`)"
    New in 0.5.0. The engine's status says who applied its configuration, and it is not the version
    the console last published: someone ran `syncd apply-config` (in compatible control API
    access) or edited the file on the host. The console records it in its audit trail, raises the
    webhook event of that name, and warns until a version is published from the console again.
    Find out who (`syncd status` names the caller, or `the file on this host`), then import the
    running configuration in the console or publish the version you want. Opt the host in to
    strict access (`sudo syncdctl api-access strict`) so that it cannot happen around the console
    again.

??? failure "A published configuration is refused: `not published: N of M test cases fail`"
    A computed attribute's test cases, a rule package's tests, or (with Git sync) a golden-file
    test fail, and the version cannot be published. The console lets such a version be saved,
    but not published. From 0.4.1 the golden tests in Git gate every publish: the editor's,
    one through the API, Git auto-publish, and `syncd config promote`. From 0.5.0 they are read at
    the version's own Git commit, not the branch head: a version saved while its golden files
    failed stays refused after the files are fixed at the head, so save it again. Versions saved
    before 0.5.0 are checked at the branch head, with a note. Fix the rule or the test case. `golden tests: not run, the configuration rendered for display does not compile: ...`
    means the golden tests could not run on the version as the console renders it for display. See
    [Rules](rules.md) and [Configuration as code](config-as-code.md).

??? failure "Publishing is refused: `a publish needs a change ticket reference matching ...` or `the ticket reference ... does not match ...` (400)"
    New in 0.4.0. `CONSOLE_PUBLISH_TICKET_PATTERN` is set, so every publish must carry a matching
    change-ticket reference: type it in the publish dialog, or pass `--ticket` to
    `syncd config promote`. With Git sync publishing, put it in the commit message.

??? failure "Publishing is refused: `not published: the change hook refused version N: <reason>` (409)"
    New in 0.4.0. The pre-publish hook (`CONSOLE_PUBLISH_HOOK_URL`) answered `deny`, or a 4xx.
    The reason is the change-management system's: an unapproved ticket, a change freeze. Nothing
    was sent to the engine; the webhook event `config.rejected` was raised. Sort it out in the
    change-management system and publish again.

??? failure "Publishing is refused: `hook_unavailable` (503): `the change hook did not answer: ...` or `the change hook answered 500`"
    The hook timed out (`CONSOLE_PUBLISH_HOOK_TIMEOUT`, 10 s), answered 5xx, or could not be
    reached, and `CONSOLE_PUBLISH_HOOK_ON_ERROR` is `refuse` (the default). Check the hook's
    service, and the path from the console to it (its address must pass the webhook address
    rules, below). Other reasons: `the hook's address is refused by the address rules: ...`, `the
    hook's secret cannot be read: ...` (`CONSOLE_PUBLISH_HOOK_SECRET_FILE`, readable by
    `syncd-console`). `CONSOLE_PUBLISH_HOOK_ON_ERROR=allow` lets publishes go while the hook is
    down, recorded as `error-allowed`; choose it knowingly.

## The license

New in 0.5.0. How licensing works, its states and how to install a license are in
[Licensing](licensing.md); these are the symptoms.

??? failure "Publishing is refused: `not published: publishing configuration changes is blocked by the license (...)` (`SyncdLicensePublishingBlocked`, `license: BLOCKED`)"
    The license expired, the managed identities went over its tier, or there is no license and the
    30-day evaluation ended, and the 30-day grace period after that is over. On an upgraded
    installation with no license, that is about 60 days after the first start of 0.5.0. **Sync keeps
    running** with the published configuration, across restarts and failovers; only changing it is
    refused (an engine without a console answers `not applied: ...` to `syncd apply-config`).

    Still allowed meanwhile: approving or rejecting held changes, dead-letter retries and
    discards, joins, password operations, backups, and **publishing again a version that was
    published to that engine before** (a rollback, audited with a `license` note). See
    [What publishing is blocked blocks](licensing.md#what-publishing-is-blocked-blocks).

    1. Find the cause: the console's **License** page, `syncd license show`, or
       `sudo syncdctl license status`. The first lines say which problem started the grace period.
    2. Get a license that covers it from the licensing portal, with the installation id from the
       same page if the portal asks for it.
    3. Install it: **License** → **Install license**, or `sudo syncdctl license install FILE`
       (see [Installing a license](licensing.md#installing-a-license)). The block ends at once;
       publish again.
    4. Over the tier and cannot wait: taking out what the count includes (a publication of people
       no longer needed, leavers kept exported) lowers it at the engines' next count, every ten
       minutes.

??? warning "`SyncdLicenseWarning` or `SyncdLicenseGracePeriod`, a banner across the console, the webhook `license.state-changed`"
    The license expires within 30 days, the managed identities are near (warning) or over (grace)
    its tier, or the evaluation is ending or over. Nothing is refused yet. `syncd license show` (or
    the License page) gives the reason and the date the block starts. Renew, or extend the tier,
    before then.

??? failure "A version is refused: `not published: connector <name> (<type>) needs the <pack> pack, which the license does not include`"
    Not the block: this happens in any state. The version uses a connector or setting of a pack
    (Cloud and SaaS, Enterprise Database, Password Sync, High Availability, Extensibility, and
    from 0.7.0 Governance) the license does not include. Get the pack, or take the connector or
    setting out of the version. A version with it that ran on the engine before may still be
    published again as a rollback. From 0.7.0 a feature names itself the same way: `access granted
    by governance (grants on a publication) needs the Governance pack, ...`, or `SQL database
    tables as targets (a publication to postgres, mysql or sql_server) needs the Enterprise
    Database pack, ...`. See [Packs](licensing.md#packs).

??? failure "A governance change is refused: `not done: this needs the Governance pack, which the license does not include (License page)` (409)"
    New in 0.7.0. The governance features are in the pack **Governance** (`governance`), which the
    license does not include. Reads are never refused: what was recorded stays visible. During the
    evaluation every pack is enabled, so this starts once a license without the pack is
    installed. Get a license with it from the licensing portal (see [Packs](licensing.md#packs)).

??? warning "A script that reads `syncd status` no longer matches the evaluation's `license:` line"
    New in 0.7.0: during the evaluation the line names every pack it enables, and the list now
    ends with `governance` (`license: EVALUATION (no license; packs core, enterprise-database,
    password-sync, high-availability, extensibility, cloud-saas, governance)`). Match the start of
    the line, or the new list.

??? warning "`SyncdLicenseDevelopmentKey`, or a build that says `DEVELOPMENT LICENSE KEY`"
    The build accepts the development license key from the source repository (a build made
    without the release's key), or the license was signed with it. Production runs release
    builds; `syncd version` names the license keys a build accepts. See
    [Release builds and the development key](licensing.md#release-builds-and-the-development-key).

??? warning "After the upgrade to 0.5.1, an engine managed by a console without a license blocks publishing"
    0.5.0 let a console that holds no license switch the engines' enforcement off. From 0.5.1 such
    an engine is judged by its own evaluation period again, and blocks publishing once that
    ends. Install a license in the console (see [Licensing](licensing.md)).

??? warning "The license stays expired after the clock was corrected"
    The console and the engines keep the latest time they have seen, so a clock that ran ahead
    past the license's expiry keeps it expired. Ask the portal for a license valid past that time.

## Held changes and change safety

??? warning "A publication's changes are held (`held: N changes` in `syncd status`, `SyncdChangesNeedApproval`)"
    New in 0.4.0. The publication planned changes it did not apply. `syncd held <publication>`
    lists each with its reason:

    - `window`: its change window is closed. Nothing to do: they are planned again and applied
      when it opens (`syncd status` says when). `disable` and `password` changes go through by
      default (`immediate`).
    - `overdue`: they waited longer than `max_delay` and the window has `on_overdue: hold`.
      Approve them, or widen the window.
    - `modifications`, `additions`, `deletions`: one pass would change, add or delete more than
      the threshold allows. Find out why first: a source read that changed a column for
      everyone, a rule with wider effect than meant, a bad lookup table, an empty source. Fix it,
      and the next pass under the threshold clears the hold by itself; or, if the change is
      intended, approve it.
    - `canary`: a canary is soaking; the rest follows by itself.
    - `canary_failed`: see below.
    - `report`: the publication is in report mode; see below.
    - `expired`: a computed group expired; see below.

    To approve: `sudo syncd approve "publication <name>"`, or **Approve and apply** on the
    console's Held changes card. Every held change of the publication is planned again from the
    entity's current state and applied once, deletes over the deletion threshold included. The
    thresholds and the window stay in force for later changes. See
    [Change safety and lifecycle](change-safety.md#approving).

??? failure "A canary failed (`SyncdCanaryFailed`, `canary_failed`, `the canary failed: ...`)"
    A pass changed more entities than `canary.on`; the first `canary.count` were applied, and
    during the soak one of them went to the dead-letter queue (the target refused it) or, for a
    publication that checks drift, read back different from what was written. The rest of the
    change is held.

    1. `sudo syncd dlq` shows what the target refused and why; the console's drift findings
       show what differed. A schema the target lacks, a value it refuses, an access right: fix
       the rules or the target.
    2. Once the first entries are right (a retry of the dead letters succeeds, a drift check is
       clean), approve the rest: `sudo syncd approve "publication <name>"`.

    A canary's drift check follows the publication's drift policy, reverts included.

??? warning "A publication in report mode writes nothing"
    `mode: report` plans the publication on every pass and writes nothing: `syncd status` shows
    `report mode: N adds, N modifies, ... would be sent`, and `syncd held <publication> --reason
    report` (or `syncd report`) lists them. That is what it is for. When the list looks right,
    publish the configuration with `mode: apply`; the next reconciliation applies them. A very
    large view in report mode holds a would-do per entity in memory: don't leave it there for
    good.

??? warning "Computed groups expired (`SyncdGroupsExpired`, `EXPIRED` in `syncd status`)"
    The group's `expires` date passed, or `expires_after` ran out since it was created or last
    reviewed. By `on_expiry`: `report` (the default) changed nothing and lists it as held with
    reason `expired` (no approval needed; `syncd approve` leaves it alone); `empty` removed its
    members; `delete` deleted the entry.

    - Still wanted: `sudo syncd group review <publication> <group>`, or **Review** in the
      console. The clocks start again, an emptied group gets its members back, a deleted one is
      added again.
    - Not wanted: remove it from the configuration.
    - An `on_expiry: empty` group whose object class needs a member (`groupOfNames`,
      `groupOfUniqueNames`) dead-letters unless it has a `placeholder_member` (new in 0.5.0),
      which the group holds while emptied. From 0.5.0 `syncd validate` and the engine's start
      warn about the combination. Set `placeholder_member`, or use `report` or `delete`, then
      review the group.

??? warning "A group review is due (`SyncdGroupReviewsDue`, `REVIEW DUE` in `syncd status`, `computed group review is due` in the log)"
    The softer version of expiry: `review_every` passed since the creation or the last review, and
    nothing changed in the target. From 0.5.0 the console also raises `group.review-due` and mails
    the group's `reviewers`; the alert fires after a day. Look at the members and review the group
    (`sudo syncd group review <publication> <group>`, or **Review** in the console). Reviewers who
    got no mail: the console's mail settings, and their domains in
    `CONSOLE_REPORTS_RECIPIENT_DOMAINS` (an address outside them is left out and named in the
    console's log). See [Group lifecycle](change-safety.md#group-lifecycle).

??? warning "Records wait to join an entity (`SyncdUnmatchedRecordsAgeing`)"
    Records of a source found no entity by the view's join rules (or several), and some have
    waited longer than `correlation.unmatched_alert_after`. Nothing of theirs reaches the targets.
    `sudo syncd correlation suggest --view <view>` (or the console's Correlation page) scores
    each against the entities; accept the right ones (`syncd correlation accept --view <view>
    --above 0.95`, a dry run until `--yes`). Fix the data for the rest. From 0.5.0 correlation
    finds more (a typo at the start of a value, values one edit apart), so a bulk accept may take
    records 0.4.1 left: read the dry run. An `in view <name>` line points to a look-alike in
    another view (`correlation.other_views`), which cannot be accepted. See
    [Correlation assistance](reports-and-data-quality.md#correlation-assistance).

??? warning "A data-quality check fails (`SyncdQualityErrors`, `quality ...: N entities fail (error)`)"
    A view's `quality` check does not hold for these entities. `sudo syncd quality <view>
    --check <check> --list` lists them with the message (never values); the console's Data
    quality page links each to the explorer. Fix the data in the source, or the check. With
    `on_error: quarantine` their exports wait (they show in `syncd quarantine`); with
    `skip_publication` they are out of those publications' scope, so the publication's leaver
    policy applies to their entries. See
    [Data-quality checks](reports-and-data-quality.md#data-quality-checks).

??? warning "A leaver kept memberships, or a leaver's entry came back enabled"
    The staged leaver policy strips the groups syncd manages. Groups made by hand or by another
    tool keep the leaver unless the policy has `strip_unmanaged_groups: true` (new in 0.5.0, LDAP
    and Active Directory, with `keep_groups` for the ones to leave alone); a `groupOfNames` that
    would lose its last member is left as it is, with a warning.

    From 0.5.0 an entity added at the DN of a leaver's disabled entry takes the entry over and
    enables it (`a leaver's disabled entry is taken over by the entity added at its DN, and
    enabled`); 0.4.1 left it disabled. A leaver moved aside (`move_to`) is not taken over. See [Staged leaver policy](change-safety.md#staged-leaver-policy).

??? warning "Unmanaged entries or privileged members grew (`SyncdUnmanagedEntriesGrew`, `SyncdUnmanagedPrivilegedMembersGrew`)"
    New in 0.5.0. The target holds more entries of the kind a publication manages that syncd never
    exported than a day earlier, or a privileged group has more members no rule put there.
    `sudo syncd unmanaged --last` lists them (`syncd unmanaged` reads the targets again); so does
    the console's **Unmanaged entries** page. For each: adopt it if it belongs to a person syncd
    knows (`syncd adopt --publication <p> --dn '<dn>' --entity <id>`, or **Adopt**); fix the
    source or join the record if the person is known under another key; add a service or
    break-glass account to `unmanaged.ignore`; or disable or remove it in the directory (syncd
    never removes what it does not manage). For a privileged member, remove it in the directory or
    make a rule account for it. The entries alert clears by itself a day later. A result marked
    `stopped` could not finish (the target did not answer). See
    [Unmanaged entries](operator-guide.md#unmanaged-entries).

## Passwords

??? failure "`passwords: ... N failed; last failure: ...` in `syncd status`, `SyncdPasswordDeliveryFailures`, or the webhook `password.delivery-failed`"
    A password change was not delivered. The log names the account and DN (`password change not
    delivered`), never the password. `syncd_password_deliveries_total{outcome="failed"}` counts
    failures per publication (new in 0.4.1), and the console's engine page lists them. Usual
    reasons:

    - **The target refused it** (`... refused the password of ...`): its password policy (length,
      history, complexity), or the sync account's access rights. A refused change is reported
      and not retried; the person's next change is delivered.
    - **It waited too long** (`... expired before it could be delivered`): the account was not
      provisioned or exported yet, or the target was unreachable, for longer than
      `passwords.ttl_secs` (a day by default). It is dropped and counted as failed.
      `syncd_password_waiting` climbing before shows which publication was stuck.
    - **It could not be opened** (an empty `publication` label): the change was sealed to another
      password key, such as one from before a restore with new keys, or one retired after a
      rotation (`syncd password rotate-key --retire`). The capture point fetches the current key
      again by itself.
    - **Plain LDAP**: AD takes `unicodePwd` only over LDAPS or StartTLS; from 0.4.0 such a
      configuration is refused at validation, and from 0.5.0 so is one that sends passwords to any
      LDAP directory on another host over `ldap://` without `starttls`, unless
      `passwords.allow_cleartext` (see [Installation and upgrade](#installation-and-upgrade)).

    `sudo syncd password queue` lists what is waiting, with who submitted each change (an agent,
    the self-service page, a control API caller) and the publications it reached; never a
    password (new in 0.5.0).

    See [Password synchronisation](password-sync.md).

??? warning "Password changes are dropped (`SyncdPasswordChangesDropped`, `N dropped (report mode)`)"
    New in 0.4.1. The publication that delivers them is in report mode, which writes nothing,
    passwords included, and they are not kept for later. Put the publication in apply mode, or
    stop delivering passwords through it (`passwords` on the publication).

??? warning "`password keys: stored in the clear` in `syncd status`, or `the password keys are stored in the clear` in the log"
    New in 0.4.1. No passphrase seals the password key and the agents' CA key, so they are plain
    files protected by their mode only, and backups and HA snapshots carry them as they are. Seal
    them: set `SYNCD_PASSWORD_KEY_PASSPHRASE_FILE` and restart (see
    [The password keys](operator-guide.md#the-password-keys)). Backups taken before still hold
    them in the clear.

??? warning "`the target refused the initial password; the entry is there without it`, or a dead letter `the initial password was refused 3 times, the last: ...`"
    A `passwords.initial` password (generated or read from the entity) was refused by an LDAP or
    Active Directory target, usually by its password policy. The entry exists without a password,
    and on Active Directory stays disabled. `syncd status` counts it in `initial passwords: N set,
    N refused`.

    From 0.5.0 the engine tries again, a new password each time for `generate` and the entity's
    current value for `from`: at the entity's next change, the next reconciliation, or a minute
    later (two after the second refusal), three attempts in all. After the third the entry is held
    in the dead-letter queue (`syncd dlq`, the console, `syncd_publication_dead_letters`,
    `SyncdDeadLetters`) and tried no more, whatever else is exported for it. 0.4.1 only logged the
    refusal; entries refused before the upgrade are not retried.

    1. Fix the cause: a `generate` shorter, or with fewer character classes, than the target's
       policy asks (`length`, `classes`), or a `from` value the policy refuses.
    2. `sudo syncd retry --publication <p>` makes one more attempt. `syncd discard` gives up: the
       entry stays without a password.

    On SCIM and Entra ID targets the password goes with the create, so a refusal fails the create,
    which is a dead letter retried like any other. From 0.5.0 an entry a drift check adds back gets
    an initial password. `the initial password rule failed; the entry is created without one` means
    the `from` expression failed.

??? warning "Password agents stop delivering after an HA failover"
    From 0.5.0 both engines of a pair answer on the ingest port: the passive one forwards what
    agents send to the active one. In strict control API access that forward needs the certificate
    the passive engine uses listed under `api.access.passwords` on both hosts (the container
    image's file lists `syncd engine`); without it, the active engine logs `refused a password
    request`. An agent enrolled or a key rotated after the last snapshot is lost with the active
    engine: enroll it again. Self-service changes follow the lease from 0.4.1: the console sends
    them to the member that holds it. With sealed keys, an engine with another passphrase does not
    start on the snapshot's keys (above). See
    [Password synchronisation](password-sync.md#high-availability).

??? failure "`syncd password agent add`: `a new agent needs the source it captures passwords for: syncd password agent add <name> --source <connector>`"
    New in 0.5.0. Give the source connector the agent captures for (`--source`, repeated for
    several). The engine takes that agent's changes for those sources only; a change for another
    is refused, never queued, and counted (`password agents: N changes refused for a source their
    agent is not bound to`, `syncd_password_changes_refused_total`). On the agent's host,
    `syncd-capture` says `the engine has this agent bound to no source` for an agent enrolled
    before 0.5.0: bind it with `syncd password agent bind <name> --source <connector>`.

??? failure "`<call> sets passwords: in strict mode it needs a client certificate listed under api.access.passwords on the engine's host, or the control socket`"
    New in 0.5.0. In strict control API access, setting passwords (`syncd password set`, `agent
    add` and `bind`, `rotate-key`, `rotate-agent-ca`, and from 0.7.0 `password reset` and
    `unlock`) is a permission of its own. Run the command with `sudo` on the engine's host, which
    uses the control socket, or list the caller's certificate under `api.access.passwords`.

    From 0.7.0 the console makes such calls too: `ResetPassword` and `UnlockAccount` for **My
    password** and reset links, `AddPasswordAgent` and `BindPasswordAgent` for its **Password
    agents** page. The console shows the refusal; list the console's certificate (`syncd console`)
    under `api.access.passwords` on that engine's host and restart the engine. See
    [Control API access](operator-guide.md#control-api-access).

??? failure "**My password**: `you signed in with a password, which proves nothing here: sign in again with single sign-on or a certificate`"
    New in 0.7.0. A reset or an unlock on **My password** needs proof of who the person is other
    than the password they forgot: a sign-in through single sign-on or with a certificate. Sign
    out, and sign in that way. Without single sign-on or certificates, a manager's reset link is
    the way (**My team**), once `reset_mail_attribute` is set in the governance settings.

??? failure "**My password**: `sign in again first: a reset needs a sign-in less than 5 minutes old in which the identity provider had you sign in again (the page's sign-in link)`"
    The session's proof is more than five minutes old, or the sign-in was not a fresh one. Use the
    page's **Sign in again with single sign-on** (or **with SAML**): it asks the provider to
    authenticate the person again (`prompt=login` and `max_age=0`, or SAML `ForceAuthn`). With a
    certificate, the sign-in itself counts, for five minutes. **Sessions started before the
    upgrade to 0.7.0 never count**: sign in again.

??? failure "Single sign-on, signing in again for **My password**: `the sign-in is not fresh: the identity provider did not say when it authenticated you (auth_time), so this sign-in does not prove who you are: ask its administrator to send auth_time`"
    New in 0.7.0, after the release's review: the console checks the provider's proof that it
    authenticated the person again, the ID token's `auth_time` (OpenID Connect) or the assertion's
    `AuthnInstant` (SAML), and refuses a provider that sends neither. Have the provider's
    administrator send `auth_time` in the ID token (or `AuthnInstant` in the assertion). `the
    identity provider did not have you sign in again (it last authenticated you at <time>): sign in
    again there` means the provider reused its session instead of asking for credentials, and
    the time it gave is older than the console's request (with a minute's leeway for the
    clocks): the provider ignores `prompt=login` or `ForceAuthn`, which it must honour for
    resets. `<auth_time or AuthnInstant> is in the future (check the clocks)`: the provider's
    clock and the console's disagree by more than a minute. See
    [Single sign-on](single-sign-on.md).

??? failure "**My password** says `Password reset is not available here.`, or `password reset is off: no engine takes self-service changes (CONSOLE_SELF_SERVICE_ENGINE)`"
    New in 0.7.0. Reset and unlock go to the engine `CONSOLE_SELF_SERVICE_ENGINE` names, which
    needs `passwords.self_service` (see [Password synchronisation](password-sync.md)). `the engine
    is older than password reset (0.7): upgrade it`: that engine runs a release before 0.7.0.

??? failure "A reset link: `assisted resets are off: set reset_mail_attribute in the governance settings`, `<login> has no <attribute> to mail the link to`, `this link expired: ask for a new one`, or `this link was used already: ask for a new one`"
    New in 0.7.0. A manager (for a direct report, on **My team**) or a holder of `reset_passwords`
    sends a single-use link valid 15 minutes, at most three a person a day, to the address in the
    projected attribute `reset_mail_attribute` names. Set it in the governance settings, make sure
    the person has that attribute, and that mail is set up and allows the address's domain (`the
    link cannot be mailed: ...`); `a reset link needs the console's public address
    (CONSOLE_PUBLIC_URL)`. A link expired or used: send a new one. Nobody sends themselves a
    link (`use My password`).

??? warning "`SyncdPasswordKeyOld`, or `SyncdPasswordAgentCertExpiring`"
    New in 0.5.0. The password key is more than a year old: rotate it with
    `sudo syncd password rotate-key` (capture points pick the new key up by themselves; the old one
    is retired after `passwords.rotation_overlap_days`). Or an agent's certificate expires within
    14 days: `syncd-capture` renews in the last third of the certificate's life, so the agent is
    down, cannot reach the engine, or was enrolled with `syncd password agent enroll`, which does
    not renew by itself. Renew it on its host (`syncd password agent renew --dir <dir>`) while the
    certificate is valid; after it expired, enroll the agent again. `syncd password agent list`
    shows each agent's certificate. See
    [Changing the passphrase and rotating the keys](operator-guide.md#changing-the-passphrase-and-rotating-the-keys).

??? failure "`syncd password rekey`: `<file> opens with neither the engine's passphrase nor the new one; nothing was changed`"
    New in 0.5.0. A key file opens with neither the passphrase the engine has (or the previous one
    an interrupted rekey left) nor the new one: the files came from another engine, or the
    engine's passphrase variable names another file. Nothing was changed. An interrupted rekey is finished by running the same command again; the engine still
    opens files sealed with the previous passphrase (`<passphrase file>.previous`) until
    `syncd password rekey --forget-previous`. With the passphrase in
    `SYNCD_PASSWORD_KEY_PASSPHRASE` (a value, not a file) the command cannot change the variable:
    change it and restart the engine. On Kubernetes the Secret is read-only to the engine.

## Backups and restore

??? warning "No recent backup (`SyncdBackupStale`, `backups: ... FAILED <ago>: <why>`)"
    New in 0.4.0. No scheduled backup (`engine.backup`) has succeeded for two intervals, or none
    since it was configured. The reason is in the status line, the log (`backup failed`) and the
    `backup.failed` webhook: a destination that does not answer, credentials that expired
    (`AWS_*` in `/etc/syncd/engine.env`; a web identity token the pod no longer has), a bucket
    policy that refuses the key (`AccessDenied`, `SignatureDoesNotMatch`: a wrong secret or
    session token), a full disk for a directory destination. Fix it, then take one by hand:
    `sudo syncdctl backup --to <the same destination>`. The schedule carries on by itself.

??? warning "An S3 backup waits a few seconds before `no AWS credentials: ...`"
    New in 0.5.0. With none of the access key variables, a web identity token or a shared
    credentials file, the engine asks the EC2 instance metadata service for the instance's role,
    last. Off EC2 that answer never comes, and the error follows a short wait. Set
    `AWS_EC2_METADATA_DISABLED=true` in `/etc/syncd/engine.env` to skip it, or give credentials.
    On EC2, give the instance a role that may write the bucket.

??? warning "`syncdctl backup --to`: `the password-key passphrase file ... is inside /etc/syncd: it is LEFT OUT of this backup ...`"
    New in 0.5.0. The passphrase that seals the password keys would otherwise travel in the same
    backup as the keys. Keep a copy apart from the backups (a restore needs it), and move the file
    out of `/etc/syncd`, to `/etc/syncd-keys/` for example, updating
    `SYNCD_PASSWORD_KEY_PASSPHRASE_FILE`. `engine.env sets SYNCD_PASSWORD_KEY_PASSPHRASE, the
    passphrase itself, which is in this backup`: use a file outside `/etc/syncd` instead. On
    restore, `the backup does not hold the password-key passphrase file ...`: put it back from
    where you keep it, or the engine cannot open its keys. See
    [The password keys](operator-guide.md#the-password-keys).

??? failure "`syncd backup`: `a backup carries the whole store: it needs mTLS (api.tls), the control socket, or both api.local_control: true and api.local_reveal: true for the loopback listener`"
    The backup was asked over a plain-text loopback listener that may not take commands or reveal
    values. From 0.5.1 it needs both settings: `api.local_reveal` alone, which 0.5.0 accepted, no
    longer serves backups, HA snapshots or the password queue. Use the installed mutual TLS (the
    default), the control socket, or `sudo syncdctl backup --to`. (0.5.0 said `... or
    api.local_reveal: true for the loopback listener`.)

??? failure "`Backup hands out the password keys: in strict mode it needs a client certificate listed under api.access.passwords on the engine's host, or the control socket`"
    New in 0.5.1. The engine holds password keys (a password key or the agents' CA on disk), so in
    strict access its backups go only to the control socket and to certificates listed under
    `api.access.passwords`. `syncdctl backup --to` then fails with `the engine's backup failed (an
    engine that holds password keys gives its backups, under strict access, only through its
    control socket: uncomment api.control_socket in /etc/syncd/engine/engine.yaml and restart
    syncd)`. Do that: `syncdctl backup --to` runs `syncd backup` as the engine's user, which opens
    the socket. Listing `syncd command line` under `passwords` would work too, but gives the
    `syncd` group the password keys. Scheduled backups (`engine.backup`) are taken by the engine
    itself and are not affected. See [Control API access](operator-guide.md#control-api-access).

??? warning "A backup listing, restore or `--keep` passes over an archive"
    New in 0.5.1: an archive without its manifest (`syncd-<host>-<time>.tar.zst` with no
    `.manifest.json` beside it) is not a backup. A failed backup of an earlier release could leave
    one; 0.5.1's leave none. Delete such archives by hand. Backups are listed, and `keep`
    prunes them, by when they were taken (0.5.0 ordered them by name, so by host first).

??? failure "`syncdctl backup`: `unknown option --sse`, or `--sse, --endpoint, --region and --s3-ca are for an off-host backup (--to)`"
    From 0.4.1, `syncdctl backup --to` and `syncdctl restore --from` take `--sse`, `--endpoint`,
    `--region` and `--s3-ca` and pass them on; 0.4.0's refused them (`unknown option`), and still
    does after a rollback. A host-local `syncdctl backup` (without `--to`) takes none of them.
    Other options are refused. `--to takes a directory or s3://bucket/prefix` means the
    destination is neither.

??? failure "`syncdctl backup --to <dir>`: `the engine's backup failed` (permission denied)"
    The engine's part is written by the `syncd` user, so a directory destination must be writable
    by it: `sudo install -d -o syncd -g syncd -m 0700 /mnt/backups/syncd`. `syncdctl` writes the
    other files there as root. With 0.4.0, the upload of `/etc/syncd` to S3 and the console
    database's dump with `--local-database` failed too; 0.4.1 fixes both.

??? failure "`syncdctl backup --to`: `... failed: backup <name> at <to> is INCOMPLETE (it has the engine's archive, not all of the console's parts): delete its files (<name>.*) there, and take another backup once the cause is fixed`"
    New in 0.5.5. The engine's archive was stored, but adding the console's dump, `/etc/syncd`'s
    archive or the master key beside it failed (a full destination, credentials that lost the
    right to write). No older backup was removed: `--keep` prunes only once every part is stored.
    Fix the cause, delete `<name>.*` at the destination, and back up again. A failure before
    anything was written (`backing up the console database failed (above); nothing was written to
    ...`, `archiving /etc/syncd failed; ...`) leaves the destination as it was.
    `removing the oldest backups at <to> failed (above); backup <name> itself is complete`: the new
    backup is good, and the older ones stay until the next run prunes them.

??? warning "`syncdctl restore --from`: `<name>.<part> was not fetched: ...`, or `the backup has no console master key`"
    Before 0.5.5: a part of the backup (the console's dump, `/etc/syncd`'s archive, the master key)
    could not be fetched; the restore went on without it, and kept what the host had. From 0.5.5 a
    part that cannot be fetched stops the restore there, after the engine store is restored:
    `fetching <name>.<part> from ... failed: ... The engine store is restored; the console
    database, /etc/syncd and the console's master key are not, and the services are stopped. Once
    that is fixed: <the command to run again>`. A part the backup does not have (`<name>.<part> is
    not in the backup: ...`, the dump of a backup taken with `--skip-db-backup`) is a warning, and
    the restore goes on, keeping what the host has. When the engine's own restore fails before it
    touched the store, nothing is changed and syncd is started again; after that, the services
    stay stopped and the output says where the previous store went. Check the S3 options
    (`--endpoint`, `--region`, `--s3-ca`: the ones the backup was taken with) and the destination.
    A backup taken with 0.4.0 has no master key file: the console keeps its own key, which opens
    the restored database's secrets only if it is the same one; copy back the key that belongs
    with the backup if it is not.

??? failure "`syncd restore`: `the engine is running (its control API answers): stop it before restoring`"
    A restore replaces the store, so the engine must be stopped: `sudo systemctl stop syncd`
    (on Kubernetes, scale the engine to 0). `syncdctl restore --from` stops the services itself.

??? failure "`syncd restore`: `<dir> already holds a store: pass --replace ...`"
    Pass `--replace`, which moves the store there to `<data_dir>/replaced-<time>/`, or restore
    into another `--data-dir`. `syncdctl restore --from` passes it.

??? failure "`syncd restore`: `the backup's store is in format N, which this syncd cannot read`, `no backup found at ...`, or a file that does not match the manifest"
    Install the syncd version the message names (or newer) first. `no backup found`: the
    destination or prefix is wrong, or the credentials see another bucket; `--name` picks one by
    name (`syncd-<host>-<time>`). A file that does not match the manifest is a damaged or
    incomplete backup: nothing was put in place; use another one.

??? warning "`syncd restore`: `the restored password key is sealed: ...`"
    New in 0.4.1. The backup's password key is sealed, and the passphrase this command has is
    missing or does not open it. The engine will not start until `SYNCD_PASSWORD_KEY_PASSPHRASE_FILE`
    names the passphrase of the engine the backup came from. See
    [The password keys](operator-guide.md#the-password-keys).

??? warning "After a restore, the engine runs its old configuration"
    `syncd restore` never writes over the live configuration: the backed-up one is beside it, as
    `/var/lib/syncd/engine/engine.restored.yaml`. `syncd config diff engine.restored.yaml
    /etc/syncd/engine/engine.yaml` says what differs; publish the one you want.

??? warning "After a restore, the console's log says `the engine's store was replaced; its grants are sent again`"
    New in 0.7.0, and expected: the restored engine's store has a new generation, and the console
    sends it every grant again (audited as `governance.delivery.redelivered`); the engine takes
    those it holds as no change. Nothing needs doing. When the engine's files were put back
    another way (a volume snapshot, a copied directory), or only the console's database was
    restored, the console cannot tell: run `syncd-console governance redeliver --engine <name>`,
    and when the database is older than the engine, end the grants it does not know with `syncd
    grants --apply`. See
    [The console and the engine at different points](operator-guide.md#the-console-and-the-engine-at-different-points).

## High availability

??? failure "Neither engine is active (`SyncdHaNoActive`, `NO ENGINE HOLDS THE LEASE`)"
    New in 0.4.0. Both engines of the pair are up and passive, so nothing is read or exported.
    `syncd status` on either shows the lease's last error (`LEASE ERROR: ...`, and `the lease
    could not be read or renewed` in the log): the lease's database is unreachable or refuses the
    role (the URL, the network, the password, the rights on the `syncd_lease` table), or the
    lease file's file system is not mounted, or the engine may not write the lease file's
    directory (a read-only file system error: the engine's unit lets it write only its own
    directories, and needs the drop-in `syncdctl ha init --lease-path` writes from 0.5.1, or one
    of your own with `ReadWritePaths=`; see
    [What runs where](operator-guide.md#what-runs-where)); or both engines are configured with
    `role: passive`. As soon as one engine can take the lease, it does.

??? warning "`LEASE ERROR: the lease is held under this engine's name (<name>) by another process: ...; waiting for it to expire`"
    New in 0.5.1. Either both engines of the pair have the same member name (`engine.ha.member`,
    or `SYNCD_HA_MEMBER`, the host name by default: two hosts with the same short name, or two
    containers), or this engine restarted after a crash while it held the lease. In the second
    case it takes part again once its old lease expires (`ttl_secs`), and nothing is to be done.
    In the first, give each engine its own name and restart it. 0.5.0 let both such engines hold
    the lease.

??? warning "The snapshots are late (`SyncdHaSnapshotStale`)"
    On the active engine, taking them fails (`taking a store snapshot failed`: a full disk under
    the data directory, say). On the passive one, pulling them does (`pulling a store snapshot
    failed`): the active engine's API unreachable on 7443, a client certificate it does not
    accept, or a name its certificate does not carry. In strict control API access the passive
    engine's certificate must be listed under `operate` on the active host (`cli.crt` on hosts,
    `syncd engine` on Kubernetes, which the shipped files list), and from 0.5.1, when the active
    engine holds password keys, under `passwords` too: the pull then fails with `PullSnapshot
    hands out the password keys: in strict mode it needs a client certificate listed under
    api.access.passwords ...` (or `PullSnapshotFiles`), and the password queue is not followed
    either. List it on both hosts (see
    [Passwords and the pair](install.md#passwords-and-the-pair)). From 0.5.0 the default interval
    is 60 s, so the alert's three intervals pass sooner than with 0.4.1's 300 s. `this engine takes no snapshots: it is not
    the active engine of a pair` means the passive engine reached the wrong one. Until it is
    fixed, a takeover starts from an older store and has more to read and reconcile; nothing is
    lost.

??? warning "`THE PASSIVE ENGINE IS NOT FOLLOWING THE PASSWORD QUEUE`, or `syncd_ha_password_changes_unreplicated_total` grows"
    New in 0.5.0. On the active engine: the passive engine is down, cannot reach the active one's
    API (the same path as its snapshot pulls), or did not answer within
    `engine.ha.password_replication_wait_ms` (2000). Password changes are still accepted and
    delivered, but one queued and not yet delivered when the active engine is lost, before the
    passive one catches up or the next snapshot, is not delivered by the engine that takes over.
    Bring the passive engine back; it catches up by itself. During an upgrade of the pair, the
    queue is not followed until both engines run 0.5.0. From 0.5.1, in strict access, following
    the queue needs the passive engine's certificate under `api.access.passwords` (above).

??? failure "A Kubernetes lease: `LEASE ERROR` with 403, 404 or a timeout"
    New in 0.5.0, with `engine.ha.lease.kind: kubernetes`. 403: the engine's service account may
    not get or update the Lease (the chart's Role and RoleBinding; check with
    `kubectl auth can-i update lease/<name> --as system:serviceaccount:<namespace>:<account>`). 404
    on a create: the Lease is gone and the account may not create it (`helm upgrade` creates it
    again). A timeout: the pods cannot reach the API server (a NetworkPolicy's egress).
    `kubectl get lease -n <namespace> <name> -o yaml` shows the holder, the last renewal and the
    takeovers. A pod that has just started waits one lease duration before it takes over from a
    holder that is gone. Changing a pair's lease kind needs both engine pods stopped first. This
    backend is tested in kind by the release's CI, not on a cloud cluster.

??? warning "`the pair's lease is lost: stopping the sources and the exports; this engine is now the passive one`"
    This engine could not renew the lease for longer than its TTL (cut off from the lease's
    database, or frozen), and stepped down. Whatever it had left to write was fenced off, not
    written: the other engine plans it again. Check the path to the lease's database. The engine
    carries on as the passive one.

??? warning "Both engines claim to be active (`syncd_ha_active` 1 on both)"
    Only for the moments before the old one notices: an engine checks the lease before every
    export batch (from 0.5.1, every 64 exports) and writes nothing within a second of its expiry.
    From 0.5.5 a chunk still being written at that point is abandoned (`stopped writing: this
    engine's lease runs out`), and writing back after an outage checks the lease before each
    write, so the two do not write together; a write the target had already taken stays, and the
    engine taking over reconciles it.
    If it lasts, the hosts' clocks disagree (the `file` lease is judged by them; keep them in NTP
    sync), or the lease file is not on a file system both hosts share. With 0.5.0, two engines
    with the same member name could both hold the lease; 0.5.1 refuses the second (above).

??? failure "The engine exits while taking a pair over: `taking the pair over: the store could not be released for the received snapshot; restart the engine`"
    New in 0.5.1. The passive engine won the lease, but calls it was still answering held its
    store for more than 30 seconds, so it could not swap the received snapshot in. Rather than
    take over with an older store, it exits (logging `the store is still in use by calls the
    passive engine was answering: stopping ...`). systemd (`Restart=on-failure`) or Kubernetes
    restarts it, and the restart swaps the snapshot in before anything opens the store. Nothing
    is to be done unless it keeps happening: then send the engine's log with a support bundle.

??? failure "A command is refused: `this engine is the passive one of the pair <pair>; <engine> is active: send the command there`"
    Commands go to the active engine. `syncd status` names it. Run the command on its host, or in
    its pod.

??? warning "`the active engine's configuration differs from this engine's and cannot replace it; running this engine's own`"
    After a takeover, the engine kept its own configuration file because its host settings
    (`engine.data_dir`, `api`, `engine.ha`) differ from the active engine's. Publish the
    configuration to both engines of the pair.

??? warning "After a failover, a PostgreSQL source reads its tables in full"
    Expected. A logical replication slot cannot go back past what the old engine confirmed, so the
    new active engine reads the tables in full first (unchanged rows cost nothing), then streams.
    From 0.5.0 password changes queued after the last snapshot survive when the passive engine was
    following the queue (above); the log says `password queue: what the old active engine wrote
    after the snapshot was applied`, or warns that its copy had a gap.

??? warning "After a failover: `taking the pair over: changes the other engine took from Debezium after its last snapshot are not sent again; have Debezium send its tables again (an incremental snapshot through its signal table) to catch up`"
    New in 0.5.5 (the warning; the behaviour is older). Debezium keeps its own position and moves
    on once an engine answered, so the changes the old active engine took in after its last
    snapshot are not sent to the engine that took over, unlike every other source, which reads
    again from the snapshot's checkpoints. The warning names the `debezium` sources. Have Debezium
    send their tables again with an incremental snapshot: insert an `execute-snapshot` signal
    into its signal table (see Debezium's documentation for your connector). The engine skips
    what it already has. Until then, entities changed in that window may be out of date.

??? warning "After a failover, grants are sent again: `the engine's store was replaced; its grants are sent again`"
    New in 0.7.0, and expected. The engine that took over has a new store generation, and the
    console sends it every grant again, so that a grant the old engine took after its last
    snapshot is not lost; the engine takes those it already holds as no change. The earlier advice
    to run `syncd-console governance redeliver` after a failover no longer applies. For access
    granted in the console that still has not arrived, `sudo syncd grants` on the active engine
    shows each grant's state and why.

## Webhooks, reports and Git sync

??? warning "Webhook deliveries fail or are retried"
    New in 0.4.0. **Integrations** shows each webhook's delivery log with the error. A delivery
    that gets anything but a 2xx (a timeout, a 5xx, a redirect, which is never followed) is retried
    from a minute, doubling to an hour, for a day, then marked `failed`.

    - `refused by the address rules: <host> resolves to <ip>, a private network address; list
      the host in CONSOLE_WEBHOOKS_ALLOWED_HOSTS to allow it`: a receiver inside your network.
      List it, or set `CONSOLE_WEBHOOKS_BLOCK_PRIVATE_NETWORKS=false`.
    - `<host> is not among the hosts webhooks may go to (CONSOLE_WEBHOOKS_ALLOWED_HOSTS)`: add it
      to the list.
    - `... which webhooks may never go to`: loopback, link-local and metadata addresses are
      refused whatever the settings. From 0.5.5 so are reserved ones (`<host> resolves to <ip>, a
      reserved address, ...`): 192.0.0.0/24, 198.18.0.0/15 (benchmarking, often routed inside a
      network) and 240.0.0.0/4. A webhook saved before 0.5.5 to such an address fails at its next
      send.
    - `the URL must start with https://`, `the URL must not carry credentials`.
    - `connection failed`, a TLS error: the console reaches receivers directly, never through
      `HTTPS_PROXY`. Open the firewall (on Kubernetes, `networkPolicy.egress.extra`), or set
      `CONSOLE_WEBHOOKS_CA_FILE` for a receiver with a private CA.
    - A webhook listed with its URL undecryptable: the console runs with another master key.
      Save the URL again and press **New secret**.

    A receiver that says the signature is wrong must check the HMAC over the raw body, with the
    secret shown when the webhook was created. See [Webhooks](webhooks.md).

    From 0.5.5 a webhook's events go out in order across a retry: while one waits to be tried
    again, the ones raised after it for the same webhook wait too, so a receiver that is down holds
    its own queue back until it answers or the delivery is given up (after a day). Nothing is
    lost; other webhooks are not held up. A webhook disabled for longer than 14 days loses the
    deliveries waiting for it that are older than that.

??? warning "A scheduled report did not arrive (`report.failed`, **not sent** on the Reports page)"
    The reason is on the Reports page and in the audit trail (`report.sent`, failed):

    - *the owner ... is no longer an administrator or operator* (or disabled, or gone): another
      administrator or operator creates the report again.
    - *outside the allowed recipient domains*, or *needs CONSOLE_REPORTS_RECIPIENT_DOMAINS*: fix
      the setting or the recipients.
    - *e-mail is off (CONSOLE_SMTP_HOST is not set)*.
    - *the mail could not be delivered*: the mail server refused or did not answer for a day. See
      the next entry.
    - A share that is missing: the directory must be inside `CONSOLE_REPORTS_SHARE_DIR` and
      writable by `syncd-console`.

    **Send now** runs the report at once with the same checks. See
    [Reports, lineage and data quality](reports-and-data-quality.md).

??? failure "Mail: `<host> does not offer STARTTLS`, `AUTH refused: 535 ...`, `the mail server did not greet`, `the mail server did not answer within 30 s`"
    The console speaks SMTP itself: STARTTLS, or from 0.5.0 TLS from the first byte; AUTH PLAIN,
    or from 0.5.0 AUTH LOGIN.

    - **No STARTTLS offered**: the relay does not support it. Use one that does, SMTPS
      (`CONSOLE_SMTP_IMPLICIT_TLS=true` with `CONSOLE_SMTP_PORT=465`), or, for a relay on this host
      or a private network, `CONSOLE_SMTP_STARTTLS=false`.
    - **Port 465 (SMTPS, implicit TLS)** without `CONSOLE_SMTP_IMPLICIT_TLS=true`: the console
      waits for a greeting that never comes. Set it (0.4.1 did not support SMTPS at all).
    - **`AUTH refused`**: the account or password (`CONSOLE_SMTP_USERNAME`,
      `CONSOLE_SMTP_PASSWORD_FILE`) is wrong, or the mechanism is: `CONSOLE_SMTP_AUTH` is `auto`
      by default; try `login` or `plain`. `the mail server offers neither AUTH PLAIN nor AUTH LOGIN`:
      it needs a mechanism the console does not speak. `CONSOLE_SMTP_PASSWORD_FILE is needed with
      a user name`: set it.
    - **A certificate error**: set `CONSOLE_SMTP_CA_FILE` for a relay with a private CA.

    Queued mail is retried for a day. `SELECT last_error FROM mail_outbox WHERE failed_at IS NOT
    NULL` in the console's database shows what the server said.

??? failure "Git sync: the **Git** card shows an error"
    New in 0.4.0. The card on the configs page shows the last error; **Sync now** tries again.

    - `running git: ...` (no such file): `git` is not installed. The packages only recommend it:
      `apt install git` or `dnf install git`.
    - `git fetch failed (...)`, `git clone failed (...)`: the URL, the deploy key
      (`CONSOLE_CONFIG_GIT_SSH_KEY_FILE`, readable by `syncd-console`), the server's host key
      (`CONSOLE_CONFIG_GIT_KNOWN_HOSTS_FILE`), or for `https://` the token
      (`CONSOLE_CONFIG_GIT_TOKEN_FILE`; `the Git token file ... is empty`). `git ... did not
      finish within 120 s`: the server or the network is slow or blocked.
    - `config.git-refused`: an environment file in Git holds a secret value, or a plain value for
      a key the console keeps secret. Secrets never belong in Git: remove it there, and set it
      under **Integrations** > **Environments**.
    - `<engine> vN not published: ...` (`config.git-failed`): the version did not compile, its
      tests or golden tests failed, or `CONSOLE_PUBLISH_TICKET_PATTERN` is set and the commit
      message carries no ticket. The version stays a draft.
    - `pushing <engine> vN to Git: ... (the branch moved meanwhile? ...)`: with
      `CONSOLE_CONFIG_GIT_PUSH_BACK`, the server rejected the push. Nothing is forced; the next
      pass starts from the branch again.
    - Nothing taken in: an engine named in `CONSOLE_CONFIG_GIT_ENGINES` is not registered yet.
    - `file://` repositories don't work in the container image, which has no shell.

    See [Configuration as code](config-as-code.md).

??? warning "The console's audit outputs are behind (`audit.delivery-failed`, `audit delivery has been failing for N s`)"
    Splunk, the syslog server or the audit file cannot take events. They queue in the database
    and go out in order once the receiver is back; the **Audit log** page's **Delivery** card shows
    each output's backlog and last error. Check the receiver, the token
    (`ENS_AUDIT_SPLUNK_TOKEN_FILE`) and the path to it. `ENS_AUDIT_RETENTION_DAYS` prunes only
    what every output has delivered. See [Security](security.md).

## The engine at work

??? warning "`the engine is busy (<activity>); try again when it is done`"
    A long operation runs: a full sync (after `syncd full-sync`, or after a configuration is
    published), a reconciliation, or the deletes a full read found. `syncd status` shows
    `busy: <activity> (for ...)`; alert `SyncdEngineBusy` fires after an hour. Commands wait 10 s,
    then are refused rather than queued.

    From 0.5.5 the same goes for operations that used to hold commands in a queue behind them,
    with their callers waiting: `exporting after the initial load`, `firing timers and releasing
    held changes`, `writing back a target's changes after its outage` and `delivering password
    changes`. The activity in the message says which. Scripts that send control commands (and the
    console's buttons) meet the refusal more often than with 0.5.4: retry once the operation ends.
    A command whose caller stopped waiting is not run later.

    From 0.5.0 source changes do not wait for a reconciliation, a full sync or a reload: the
    engine takes them in between the operation's pages (queued password changes too). During a
    reconciliation they are exported at once; while a full sync recomputes they are committed and
    exported by the reconciliation that follows. After `syncd apply-config` the log shows `starting
    sources` before the full sync's summary. They still wait for a full read's deletes, the first
    export after an initial load, a drift check, the release of held changes, and the
    reconciliation `syncd approve` runs; they aren't lost in any of these.

    At a million entities on 4 vCPU a full sync takes about 9 s, a reconciliation about 6 s, and
    a reload that changed every entity about 2 minutes; it grows linearly with the store. Wait and
    retry. If it runs far longer, look at the log for errors, at CPU (`top -p $(pgrep -x syncd)`)
    and at disk I/O: rules that read many other entities (`referrers()`, large groups) slow a full
    sync down. Stopping the engine is safe: a full sync resumes where it stopped at the next start.

??? warning "A source's first read joins slowly, with the engine on one core for most of it"
    A source's first full read, or the first after a view is added, takes much longer than the
    figures in [Joins and first reads](availability-and-sizing.md#joins-and-first-reads): on
    4 vCPU a joining source loads at about 50,000-75,000 records a second, and one whose records
    each find a few dozen people at about 12,000. Each record is matched against the view's join
    index one at a time, and from 0.5.2 the entities it joins or projects are recomputed in
    batches on every core (`engine.shards`). A record waits for the batch's recomputes, and runs
    alone, when they could change what it finds: a key it joins by arrives with them, it joins by
    a computed attribute, or one of its candidates is among them. A record that finds several
    entities reads each of them and then waits for review.

    1. Join on unique, single-valued keys: an employee id, a GUID. Use an attribute many people
       share (a department, a surname) only next to a unique one in an `all:` rule, never alone:
       alone, each record finds a crowd and waits for review.
    2. Leave out records that can never match at the source: an LDAP object's `filter`, or a
       `query` for a table, without the service and test accounts. A join rule's `when:` keeps
       such a record from being looked up, but it still waits as unmatched.
    3. Load the projecting source before the sources that join it. A record read before its
       entity exists waits, and is joined alone when the entity appears.
    4. Keep `engine.shards` at the number of cores (the default, `0`).
    5. A later full read is quick when nothing changed: records whose staged copy is the same are
       skipped, and from 0.5.2 a record waiting for review is matched again only when the entities
       its keys find, or its source's joins, changed.

    Join rules are in [Sources](configuration.md#sources).

??? warning "`syncd validate` warns: `reference R: e.R is not set by any flow or computed attribute`, or `reads X of view W's entities, which no flow or computed attribute of that view sets`"
    New in 0.5.4. Both are warnings: the configuration is accepted, and the engine logs them at
    start and reload (`configuration: ...`). Each names a rule that reads other entities and,
    as configured, finds nothing:

    - `view V: reference R: e.R is not set by any flow or computed attribute of view V; it points
      at no entity`: view V declares R as a reference (a group's `member`), but none of its
      sources flows a value into R, so `ref()`, `refs()` and `referrers(e, "R")` find nothing.
      Add the flow (`member: source.member`).
    - `view V: computed A: reads X of view W's entities, which no flow or computed attribute of
      that view sets; it reads as absent` (or `quality Q:`): the rule reads X of the entities it
      reaches (the groups' `dn` in `referrers(e, "member").map(g, g.dn)`), and view W never sets
      X. Flow it in view W (`dn: source.dn`).

    See [Computing memberOf from groups](rules.md#computing-memberof-from-groups) and
    [Validation rules](configuration.md#validation-rules).

??? warning "`target <name> UNREACHABLE`, alert `SyncdTargetDown`"
    The engine lost its connection to the directory and can't make a new one, or (from 0.5.1) the
    directory did not answer when the engine started or reloaded, and the engine started without
    it (`the directory cannot be reached; starting with it down`). The log says `export target is
    unreachable` and `the directory is still unreachable`. It keeps reading and
    committing its sources; every export to that target fails at once into the dead-letter queue.
    It tries a new connection after waits from 1 s, doubling to a minute.

    1. Fix the directory or the network path. From the engine's host:
       `ldapsearch -H <url> -x -b "" -s base`. A firewall that drops packets looks the same as a
       server that's down.
    2. Once it answers, the engine reconnects within a minute (`connected to the directory
       again`).
    3. Send what failed meanwhile: `sudo syncd retry` (or `--publication <p>`). The next
       reconciliation (`engine.reconcile_interval_secs`, hourly by default) sends the rest anyway.
    4. `syncd dlq` should be empty afterwards. A dead letter that keeps failing has its own error
       (a schema violation, an access right): fix it, or remove it with
       `syncd discard --publication <p> --entity <id>`.

    With `writeback` on the publication, exports are held instead, failing with `held until the
    changes made in the target during its outage are written back`. When the target is back, the
    engine writes the changes made in it back to the source, then retries the held exports by
    itself. If the write-back keeps failing, the log says why; it is tried again every minute.
    From 0.5.5 commands are refused while it writes back (`the engine is busy (writing back a
    target's changes after its outage)`).

    From 0.6.0 the line ends `(a DSML target keeps its exports for the next file it writes)`: for
    a `dsml` target, see [DSML files across an air gap](#dsml-files-across-an-air-gap) below.

??? warning "A CSV source deletes nothing: `the file has no rows: nothing was deleted (allow_empty: true lets an empty file empty the source)`"
    New in 0.5.5. A file with a header and no rows is far more often a failed export than an
    empty population, so it deletes nothing, with this warning under the source in `syncd status`
    (and `the file has no rows; this read deletes nothing` in the log). If the source really is
    empty, set `allow_empty: true` on the connection. Also from 0.5.5: without a done file, a file
    is read once its modification time is 3 seconds old, and the done file is deleted once the
    read is committed. See [Connectors](connectors.md).

??? warning "A published configuration rolled back: `the new configuration did not start; restoring the previous one`"
    The engine saved and reloaded the new configuration but could not start with it: an LDAP
    target that refused its bind, or whose certificate it does not trust, a listen address in use,
    a certificate it could not read, or a secret reference (`env:`, `file:`) that doesn't resolve
    on this host. It wrote the previous configuration back and runs that one; nothing was exported
    with the new rules. The console shows the publish as failed and the version stays a draft.
    From 0.5.1 an LDAP or Active Directory directory that is only down does not roll a
    configuration back: the engine starts with it, shows `target <name> UNREACHABLE`, and
    connects once it answers (`syncd run --once` still fails at once).

    If the previous configuration does not start either, 0.5.1 logs `the previous configuration
    did not start either; trying it again` and tries it every 5 seconds, doubling up to a minute,
    until it starts or is stopped (0.5.0 exited). Fix what stops it (the same causes), and it
    starts by itself.

    Read the reason: `journalctl -u syncd -g "did not start" -A 3`. Fix it (often a variable
    missing from `/etc/syncd/engine.env`, then `systemctl restart syncd`), and publish again.

    If the log also says `could not write the previous configuration back`, the engine runs the
    previous configuration from memory while its file still holds the one that didn't start: the
    next restart would fail. Put the previous configuration back in the file first; the console's
    versions list and the backups have it.

    A configuration that starts but then fails mid-run (a rule that errors on every entity) does
    not roll back. Rule errors keep entities' previous values and count in
    `syncd_rule_errors_total` (alert `SyncdRuleErrors`); the log names the rule. Publish a fix or
    the previous version. Before a large change, `syncd simulate new.yaml` (or **Simulate** in the
    console) shows what it would send, without sending anything; `syncd config diff` shows what
    changed by meaning.

??? warning "Deletes are held back: `exceeds the deletion threshold`"
    A source read, or a change of rules or scope, would delete more entries than the threshold
    allows. Nothing was deleted; other changes still flow. `syncd status` lists the scope, the
    count and since when; alert `SyncdDeletesHeld`.

    **A truncated PostgreSQL table** (new in 0.5.0). A `TRUNCATE` of a table a stream follows is
    read as a full read that finds every row gone, so it lands here when the source connector has
    a `deletion_threshold`; the source's line in `syncd status` also says `warning: truncated at
    the source ...; read in full, N rows remain`. 0.4.1 ignored a `TRUNCATE`. A stream has no next
    full read coming, so the approval brings one: `syncd approve "source <connector>/<object>"`
    reads the table again at once and applies what that read finds gone. Run the same command
    after repairing the table: the read finds nothing gone and the hold clears. Without a
    `deletion_threshold` on the source, the deletes go through (subject to the publications'
    thresholds): set one before you need it. The publication must publish truncates; the engine
    warns at start (`the publication does not publish truncates`) when one made by hand does
    not.

    Find out why first: a source that came back empty or partial (a wrong filter, a failed view,
    an expired account) is the usual cause, and approving would delete real entries. If the
    source is wrong, fix it; the next full read clears the hold. If the deletes are intended,
    approve them once: `sudo syncd approve "<scope>"`, with the scope exactly as `syncd status`
    shows it. For a publication, that also releases its other held changes (see
    [Held changes and change safety](#held-changes-and-change-safety)).

??? warning "A source is `RETRYING` or `FAILED`, or keeps restarting (`SyncdSourceDown`, `SyncdSourceFlapping`)"
    The error in `syncd status` and the log (`source failed; restarting it from its checkpoint`)
    says why: credentials, a dropped replication slot, a server that's gone. Restarts back off
    from 1 s to `engine.source_retry_max_secs` (60); a source that ran for a minute starts over at
    1 s. Fix the cause; the next restart picks up from its checkpoint. `sudo syncd test-connection
    <connector>` checks the connection with the same settings and secrets as the service. On the
    passive engine of a pair, sources do not run at all: that is expected.

    A PostgreSQL stream: from 0.5.0 an idle stream is no longer restarted every five minutes. One
    that has heard nothing for a minute asks the server whether it still serves the stream; `no
    message from the server in 60 s, and it has not heard from this stream meanwhile` means the
    connection was dropped on the way (a proxy or firewall idle timeout, a failover). A source
    whose secrets come from a vault (`vault`, `aws_secret`, `azure_keyvault`) reads them again at
    its restart. A REST source the service throttles shows `throttled=` in `syncd status`.

??? warning "A source is stalled (`SyncdSourceStalled`)"
    It sent changes, but its checkpoint hasn't moved for 15 minutes. Check whether the engine is
    busy or a target is unreachable (above); neither should hold sources for long. Otherwise the
    engine log around the source's last checkpoint shows what it was doing. A PostgreSQL source's
    replication slot keeps WAL while it is stalled: watch `pg_replication_slots` so the database's
    disk doesn't fill.

??? warning "Memory near the limit, or the kernel killed syncd (`SyncdMemoryNearLimit`, `journalctl -k -g oom`)"
    systemd starts syncd again from its last commit, and it finishes any full sync it was in. The
    usual causes, in order: the store grew past what `MemoryMax` allows (about 1.2 GiB per
    million entities, twice that during the first load); a simulation running beside the engine;
    a large number of held changes, or a very large view in report mode (from 0.5.0 held changes
    are kept in the store and only counted in memory, so this weighs less); a very large
    `engine.cache_mb`. Raise `MemoryMax` (`systemctl edit syncd`) or the host's memory. Don't
    remove the limit. See [Sizing](availability-and-sizing.md#the-memory-limit).

??? warning "The store grew after the upgrade to 0.4.0 or 0.4.1"
    Entity history now keeps the newest `min_per_entity` entries (10) of every entity past
    `keep_days`, and every entry of an entity with a held change or a dead letter (from 0.4.1,
    beyond `keep_per_entity` too). `syncd status`
    shows `history: N entries`. Lower `engine.history.min_per_entity` if the disk is tight, then
    `sudo syncd history prune --now`. See [Entity history](availability-and-sizing.md#entity-history).

??? warning "Entities are quarantined (`SyncdQuarantined`)"
    `syncd status` shows `view people: 3 quarantined (exports held back by a failing rule)`, and
    the console's engine page *Held back by a failing rule*. A computed attribute with
    `on_error: quarantine` (or a data-quality check with `on_error: quarantine`) fails for these
    entities, so the engine holds their exports back rather than write a wrong value. The hold is
    kept in the store, so a restart keeps it; it ends by itself once the rule evaluates again for
    the entity.

    1. List them with the rule and its error: `sudo syncd quarantine` (the engine page shows the
       same).
    2. Fix the source data the rule reads, and the next change to the entity releases it. Or fix
       the rule, publish it, and the full sync that follows releases the entities.

??? warning "`an entry moved or renamed in the target was put back where the publication places it`"
    Or `... was found by its id`, a warning in the log with the DN it was found at. Someone else
    moved or renamed an entry the engine manages. The engine finds it by the target's own id
    (`objectGUID` on Active Directory, `nsUniqueId` on 389, `entryUUID` elsewhere), puts it back at
    the DN the publication computes, and applies the change there. No second entry is added.

    1. Nothing to do, if the move was a mistake.
    2. If the entry belongs somewhere else, change the publication's DN template, or the source
       data it is built from: otherwise the engine puts it back every time.

??? failure "An export fails: `find <dn> by its id: two entries have it (...)`"
    Two entries in the target share the id the engine looks entries up by: usually an entry
    restored or copied with its id. Remove the one that should not be there, then
    `sudo syncd retry`.

??? warning "A PostgreSQL source fails: `publication <name> lacks <tables>, which <source> follows: changes to them would not stream`"
    The publication exists, from an earlier configuration, and lacks tables added to the object's
    `cdc.watch` since. The engine adds them itself when its role may (the log says `the
    publication lacked tables the object follows; added them`); here it may not. Run the
    statement the error gives (`ALTER PUBLICATION <name> ADD TABLE ...`) as a role that owns the
    publication. The source's next restart picks it up. See [Connectors](connectors.md).

??? warning "A database source fails with `<host>:<port>: no answer within 10s`"
    A PostgreSQL, MySQL or SQL Server server accepted the connection and then did not finish its
    handshake (TLS, login) within 10 seconds: a hung server, or a proxy or load balancer whose
    backend is gone. The source fails and is retried with its usual back-off. MySQL's `no binary
    log stream within 10s` is the same for the binary log request. Check the server, and the path
    to it, from the engine's host. A connection refused at once means nothing listens on the port.

??? warning "A grant from the console is `refused` or `failed` (`syncd grants`, the assignment in the console)"
    New in 0.7.0. `sudo syncd grants` lists each grant with its state (`pending`, `applied`,
    `held`, `failed`, `refused`, `ended`) and why. `refused` grants were not taken, and the detail
    says why: `publication <name> takes no grants` or `takes no membership grants
    (grants.memberships)` (the publication's `grants` settings), `group <dn> is not under
    grants.memberships.bases`, `group <dn> is one of the publication's computed groups: its filter
    decides its members`, or `the entity holds N membership grants already, the most
    grants.memberships.max_per_entity allows` (500 by default; from 0.7.0 ended grants no longer
    count). `failed` is a write the target refused; the entity's next plan tries again. `held` is an
    export held by a change window, a threshold, a canary or report mode
    ([Held changes](#held-changes-and-change-safety)). See
    [Governance](governance.md#setting-a-publication-up-for-grants).

??? failure "Removing a granted member fails: `...: the group cannot lose its last member (set group_policy.placeholder_member on the publication to hold its place)`"
    New in 0.7.0. The group is a `groupOfNames` (or another class that needs a member), and the
    member the console's grant ended was its last. Set `group_policy.placeholder_member` on the
    publication: the placeholder then takes the last member's place in the same change, and makes
    way again for the next granted member. The grant stays `failed` until then.

??? warning "Dead letters keep growing (`SyncdDeadLetters`)"
    `sudo syncd dlq` lists each failed export with its DN and error. Errors from the target
    (schema, access rights, a missing parent entry) need fixing there or in the publication;
    then `syncd retry`. `syncd discard` removes one you don't want sent. On Active Directory, a
    duplicate `sam_account_name` dead-letters; from 0.5.0
    `sam_account_name: { on_conflict: unique }` gives the second holder a counter instead (see
    [Unique sAMAccountName](change-safety.md#unique-samaccountname)). An initial password refused
    three times is a dead letter too (see [Passwords](#passwords)).

## DSML files across an air gap

New in 0.6.0. How the files move is in [Air-gapped synchronisation](air-gap.md), with its
[Monitoring](air-gap.md#monitoring) and [Recovery](air-gap.md#recovery); the settings are in
[Connectors](connectors.md#dsml-files-air-gap).

??? warning "Connected side: `target <name> UNREACHABLE (...) (a DSML target keeps its exports for the next file it writes)`, `SyncdTargetDown`"
    The `dsml` target could not write its file. Its exports stay in the engine's store, in order:
    none goes to the dead-letter queue, no sequence number is used, and the write is tried again
    within a minute (at the next interval, if that is sooner). Once a write succeeds the target is
    up again and nothing is lost; `sudo syncd reconcile` writes what is waiting at once. The log
    says `... could not be written`, with the error:

    - `<dir>: no such directory`: the outbox is gone, or the transfer volume is not mounted. syncd
      never creates the directory, so that an unmounted volume is not filled underneath. Mount it,
      or create it for the engine's user.
    - `<file>: Read-only file system (os error 30)`: the engine's systemd sandbox lets it write only
      its own directories. Add a drop-in with `ReadWritePaths=<the directory>` (and
      `RequiresMountsFor=` for a network file system) with `sudo systemctl edit syncd`, then
      restart (see [What runs where](operator-guide.md#what-runs-where)). `Permission denied`: the
      directory must be writable by the `syncd` user.
    - `<host>:<port> presented ssh-ed25519 SHA256:..., which is not among sftp.host_keys: the server
      is not trusted`: the SFTP server's key is not pinned. There is no trusting a key on first use.
      Compare the key named with the server's own (`ssh-keygen -lf
      /etc/ssh/ssh_host_ed25519_key.pub` there); if it is the server's, add it (or its `SHA256:`
      fingerprint) to `host_keys` and publish. A key that changed without a reason is not to be
      trusted.
    - `sftp.private_key: ... (an OpenSSH Ed25519 or ECDSA key; RSA keys are not taken)`: the
      client is built without RSA. Make an Ed25519 key (`ssh-keygen -t ed25519`) and authorise it
      on the server.
    - `<host>:<port> refused the sign-in of <user> (...)`, `sign-in timed out`, `no answer within
      ...`: the account, the password or key, or the path to the server.
    - `an sftp:// location cannot be used in FIPS mode: the SSH transport's ciphers are not
      FIPS-validated`: use a mounted directory (NFS, SMB, the transfer appliance's share) instead.

    `syncd test-connection <connector>` signs in and lists the directory, to check the fix.

??? warning "Far side: `warning: file N of <prefix> not arrived: <file> and the files after it wait for it (a snapshot of <prefix> after it, or on_gap: continue, goes on without)`"
    A file in the writer's sequence is missing (`files N to M of <prefix>` for several), and
    `on_gap: wait` (the default) holds the ones after it: changes applied without the ones before
    them would leave entries wrong. It goes on by itself when the file arrives. Look for it on
    the way (the outbox on the connected side keeps it unless the transfer removes it, and the
    transfer's own logs). If it is lost for good, run `sudo syncd reconcile` on the connected side,
    whose target has `full_snapshots: true`, and carry the snapshot (`.full.dsml.xml`) across: a
    snapshot of the same writer after the gap holds everything the missing file changed, so the
    source goes on from it, with a warning in the log (`the snapshot after it supersedes them`).
    `on_gap: continue` goes on without waiting, with a warning, and leaves the entries as the
    files say until a snapshot puts them right.

??? failure "Far side: `<file> does not match <file>.sha256 (SHA-256 ..., expected ...): the file is damaged or still arriving; replace it to go on`"
    The file is damaged, or the transfer wrote it in place without a temporary name or done
    marker. The source stops at that file, with the error in `syncd status`, until it is replaced
    or removed. Copy it across again from the outbox. The same holds for a file whose XML is cut
    short, a document type declaration, an `anyURI` value or a request a file cannot carry (a
    search): each stops the source with an error naming the file. If the transfer software writes
    files in place, set `done_files: true` on both sides, or `require_checksums: true` here
    (`<file> has no <file>.sha256 (require_checksums): waiting for it` while its checksum is not
    there yet).

??? failure "Far side: `<file> is N bytes, larger than max_bytes (M): raise max_bytes or remove the file`"
    The source reads no file larger than its `max_bytes`. Raise it on the far side's connection
    (at least the connected side's `max_bytes`, which its files and snapshots never exceed), or
    remove the file. On the connected side, a snapshot larger than `max_bytes` is not written (`the
    snapshot is N bytes, more than the connector's max_bytes (M): a snapshot is one file`): raise
    `max_bytes` on both sides.

??? warning "Far side: `warning: <file>: N changes to entries the source does not hold were skipped (a snapshot carries them whole)`"
    A file of changes can only change what the source holds. A modification that brings an entry
    into an object's `filter`, whose other attributes the source never held, is skipped until a
    snapshot carries the entry whole. Select the entries on the connected side and read everything
    on the far side (`filter: (objectClass=*)`, or the object classes alone), and run `sudo syncd
    reconcile` on the connected side to send a snapshot now.

??? warning "Far side: a snapshot deletes nothing: `<file>: the snapshot holds none of the object's entries: nothing was deleted (allow_empty: true lets an empty snapshot empty the source)`"
    A snapshot is read as a full read, but one with no entries for the object, or whose search
    failed (`<file>: the search failed (result code N) ... nothing was deleted`), deletes nothing:
    far more often it is a wrong `base` or `filter`, or a failed export, than an empty population.
    Check the object's `base`, `scope` and `filter` against the DNs the files carry. If the
    population really is empty, `allow_empty: true` lets the snapshot empty the source. Deletes a
    snapshot does make are subject to the deletion thresholds.

## The command line

??? failure "`no configuration file found (looked for syncd.yaml or /etc/syncd/engine/engine.yaml)`"
    `syncd` was run where there is no `./syncd.yaml`, on a host without the packaged
    configuration. Pass `-c FILE` or set `SYNCD_CONFIG`.

??? failure "`cannot read ... (permission denied): run as root (sudo), or as a member of the syncd group`"
    The configuration, `engine.env` and the command line's key are readable by root and the
    `syncd` group only. Use `sudo`, or add the operator to the `syncd` group.

??? failure "`the control API at ... requires a client certificate (mTLS), and there is no cli.crt and cli.key next to ...`"
    The command line's client certificate is missing (for example on a host installed before it
    was issued). `sudo syncdctl cli-cert` issues it; or pass `--api-cert` and `--api-key`, which
    go together (`--api-cert and --api-key go together`).

??? failure "`ApplyConfig needs the configure tier, and this client certificate has operate (api.access on the engine's host)`"
    New in 0.5.0. The host runs strict [control API access](operator-guide.md#control-api-access),
    as every host installed with 0.5.0 does: the command line's certificate reads and operates but
    does not apply configurations, so `syncd apply-config` is refused (on the control socket the
    message ends `which in strict mode only a client certificate listed under api.access.configure
    has`). Publish the configuration from the console. Without a console, and a configuration must
    change now: edit `/etc/syncd/engine/engine.yaml` as root and restart the engine; the status then
    says the engine runs `the file on this host`, and the console reports it. There is no
    break-glass flag. Other commands refused with `... needs the operate tier` come from a
    certificate listed under `read` only.

??? failure "`syncd grants --apply`: `ApplyGrants needs the configure tier, and this client certificate has operate (api.access on the engine's host)`"
    New in 0.7.0. Applying grants is in the `configure` tier, as applying a configuration is: the
    console's certificate has it, the command line's and the control socket do not in strict
    mode. The console sends grants itself; `--apply` is for tests and recovery. Listing them
    (`syncd grants`) needs only `read`. To end a grant the console does not know (after the
    console's database was restored to an earlier point), list the console's own grants first,
    then apply the `removes` with a certificate listed under `configure`. `grant <id>: ...` names
    a grant of the file the engine could not take (an entity `view:attribute=value` that matches
    no entity, or several), and `<file> is not a grants file` one that does not parse.

??? failure "`syncd inventory`: `--kind members needs --group`"
    New in 0.7.0. A group's members are listed one group at a time: pass its DN (LDAP, Active
    Directory) or its id (SCIM, Entra ID) as `--group`. `publication <name> has no
    grants.inventory: nothing is listed`: set `grants.inventory` on the publication; `the dn of
    <object> has no fixed end: set grants.inventory.account_bases`: say where its accounts are.
    A certificate below the `operate` tier is refused.

??? failure "`this client certificate is not listed in api.access on the engine's host`"
    New in 0.5.0. In strict mode a certificate listed under no tier gets nothing, not even the
    status. Use the command line's own certificate (the default with `sudo`), or list the
    certificate's common name (or `uri:` and a URI SAN) under the tier it needs, then restart the
    engine. The engine logs `refused a client certificate that api.access does not list`. A
    passive engine of a pair that pulls snapshots with its own `engine.ha.peer.cert_file` needs
    that certificate listed under `operate`.

??? failure "`this listener is read-only: commands need mTLS (api.tls), or api.local_control: true`"
    The engine's control API has no `tls` section, and plain-text loopback listeners accept only
    reads. The installed configuration uses mutual TLS; keep it. Similarly,
    `revealing sensitive values needs mTLS (api.tls), or api.local_reveal: true` for `--reveal`.
    In strict mode the loopback listener, like the control socket, stops at `operate`, on top of
    these two settings. From 0.5.1 backups, HA snapshots and the password queue need both
    settings on such a listener (see [Backups and restore](#backups-and-restore)).

??? failure "`the engine has too many commands waiting; try again shortly`, or `the engine is shutting down`"
    The engine's command queue is full, or it is stopping. Retry in a moment; check `systemctl
    status syncd` if it persists.

??? failure "`syncd quality` exits 1"
    By design: it exits 1 while a check of severity `error` fails, so scripts and CI can stop on
    it. `syncd config diff` exits 1 when the two configurations differ, and 2 on an error.

??? warning "Prometheus gets nothing from the engine"
    Metrics are off unless `api.metrics_listen` is set in `/etc/syncd/engine/engine.yaml`, and it
    cannot be set from the console. Set it, restart syncd, and check the log for `metrics
    listening`. In the shipped alert rules, only `SyncdEngineDown` expects the scrape job to be
    called `syncd`. See [Monitoring](operator-guide.md#monitoring).

## Kubernetes

??? failure "`helm install` fails: `console.database.url or console.database.existingSecret is required`"
    The console keeps its data in a PostgreSQL you provide, and the chart has no bundled one. Set
    `console.database.url`, or `console.database.existingSecret` (with `existingSecretKey`, by
    default `url`). See [Install with Helm](install.md#install-with-helm).

??? failure "`helm install` fails: `console.oidc.clientId is required with console.oidc.issuer` (or `existingSecret`, `publicUrl`, `ca`)"
    New in 0.4.0. Single sign-on through `console.oidc.*` needs a client id, a Secret of your own
    with the client secret, and a public URL (`console.publicUrl`, or an enabled Ingress); its CA
    comes from a ConfigMap or a Secret, not both. See [Single sign-on](install.md#single-sign-on).

??? failure "The pods do not start: `ImagePullBackOff`, or `exec format error`"
    `ImagePullBackOff`: the tag is not in the registry the values name. The newest published
    images are 0.7.0's, on `ghcr.io/winllc` (`syncd:0.7.0`, `syncd-console:0.7.0` and their
    `-fips` tags, amd64 and arm64), signed with cosign; there is no `latest`. **There are no
    0.6.1 images or chart**: that tag's Container workflow failed building the amd64 binaries, so
    the job that pushes and signs was skipped. A chart taken from the source repository at
    `v0.6.1`, or `image.*.tag: 0.6.1`, asks for tags that do not exist: go to 0.7.0 (next entry),
    or stay on 0.6.0. Check `image.engine.tag` and `image.console.tag` (the chart's `appVersion` by
    default), and on a cluster that cannot reach ghcr.io, mirror the images (see
    [Air-gapped clusters](install.md#air-gapped-clusters)) or build them (see
    [Getting the release](install.md#getting-the-release)), with `imagePullSecrets` if your
    registry needs them. `exec format error` means an image for another platform: a release has
    arm64 images only when it was built with the arm64 runners (`docker buildx imagetools
    inspect` lists a tag's platforms), and an image you built is for your build host's platform.
    From 0.5.0 the `-fips` images are built for both platforms too (0.4.1's were amd64 only).
    Schedule the pods on matching nodes (`nodeSelector: {kubernetes.io/arch: amd64}`), or build
    for the nodes' platform.

??? failure "`helm install` or `helm upgrade` with `--version 0.7.0` fails: the chart is not found in `oci://ghcr.io/winllc/charts/syncd`"
    Chart 0.7.0 is not published: the release's job that pushes and signs ran into GitHub's
    Actions storage quota just before the chart step, after it had pushed and signed the images.
    The newest published chart is 0.6.0. Chart 0.7.0's templates and values are the same as
    0.6.0's (only `version` and `appVersion` differ), so either use chart 0.6.0 with the 0.7.0
    images, `--version 0.6.0 --set image.engine.tag=0.7.0 --set image.console.tag=0.7.0`
    (`0.7.0-fips` for FIPS), or install the chart from the source repository at tag `v0.7.0`
    (`deploy/helm/syncd`). With chart 0.6.0 the `app.kubernetes.io/version` label and the
    install's notes say 0.6.0; `kubectl -n syncd exec syncd-engine-0 -- syncd status` says
    `engine 0.7.0`. CI has not run that combination (its kind tests use the chart from the
    checkout). See [Upgrades](operator-guide.md#upgrades) and
    [Install with Helm](install.md#install-with-helm).

??? failure "The engine fails at startup with the FIPS image: `not a FIPS build`, or `fips off` in its status"
    `SYNCD_FIPS_MODE=true` is refused by the standard images: use the `<version>-fips` tags for
    both components. With the FIPS image and no `SYNCD_FIPS_MODE`, the mode is off: set it in
    `engine.env` and `console.env`. See [FIPS images](install.md#fips-images).

??? failure "`helm upgrade` fails: `StatefulSet.apps "syncd-engine" is invalid: spec: Forbidden: updates to statefulset spec for fields other than ...`"
    New in 0.4.0. Turning `engine.ha.enabled` on or off changes the StatefulSet's `serviceName`
    and `podManagementPolicy`, which Kubernetes does not update in place. Delete it without its
    pods, `kubectl -n syncd delete statefulset syncd-engine --cascade=orphan` (the volume stays),
    and the `syncd-pki` Secret (certificates from an older chart don't name the pods), then
    upgrade again. See [An active/passive pair](install.md#an-active-passive-pair).

    A changed `engine.persistence.size` is refused the same way: a StatefulSet's volume claims
    cannot change. Grow the claims themselves first; see
    [Growing the engine's volume](availability-and-sizing.md#growing-the-engines-volume).

??? failure "The console pod restarts: the master key does not open the stored secrets"
    The `syncd-console` Secret was deleted, or replaced, and the chart generated a new master key.
    The console refuses to start over the secrets it sealed with the old one, and the registration
    Job fails too. Put the backed-up Secret back and run `helm upgrade` again. If the key is lost
    for good, upgrade once with `console.newMasterKey=true` and once more with it `false`: the
    Job registers the engine again by itself (`engine.re-registered`). Before 0.4.0 the Job did not
    get the setting and kept failing. See
    [The master key Secret](operator-guide.md#the-master-key-secret).

??? failure "With Argo CD (or another tool that renders the chart), the console refuses its master key, or the engine restarts at every sync"
    The chart keeps its generated master key, initial password and certificates by reading its
    own Secrets with Helm's `lookup`, which `helm template` cannot: every render makes new ones.
    From 0.5.1 keep them in Secrets of your own and name them (`console.masterKey.existingSecret`,
    `console.adminPasswordExistingSecret`, `pki.existingSecret`); for a release that runs already,
    copy what the chart made first, so nothing changes. See [GitOps](install.md#gitops). A key
    already replaced: see the master key entry above.

??? failure "The registration Job fails"
    `syncd-register` runs after each install and upgrade, and retries (12 times, for up to 15
    minutes) while the engine or the console's database is not up yet. A failed Job stays, for its
    logs, until the next install or upgrade: `kubectl -n syncd logs job/syncd-register
    --all-containers` (with a pair, the containers are `register-0` and `register-1`, after the
    init container `rename`, new in 0.4.1). Usual causes: the console's database URL is
    wrong or unreachable, the engine pod is not ready (`kubectl -n syncd describe pod
    syncd-engine-0`), a NetworkPolicy with egress closed that does not let the Job reach the
    database, or the master key does not open the stored secrets (above). The Job gets
    `console.env` from 0.4.0, so a database option set there applies to it.

??? failure "The engine pod restarts before it is ready"
    The startup probe gives the engine 10 minutes to open its store and listen on 7443. Look at
    `kubectl -n syncd logs syncd-engine-0 --previous`. A store migration or a very large store can
    take longer: from chart 0.5.5 raise `engine.startupProbe.failureThreshold` (120 tries every
    `periodSeconds`, 5) for that upgrade. So can a memory limit the engine keeps hitting
    (`OOMKilled` in `kubectl describe pod`): raise `engine.resources.limits.memory` (see
    [Sizing on Kubernetes](availability-and-sizing.md#sizing-on-kubernetes)).

??? failure "`kubectl cp` fails to copy the support bundle out of the pod"
    The images have no `tar`. From 0.4.1 the engine streams its bundle to standard output:
    `kubectl -n syncd exec syncd-engine-0 -- syncd support-bundle --out - > syncd-support.tar.zst`.
    A pod's bundle has no journal: add `kubectl logs` output. See
    [The command line](operator-guide.md#the-command-line).

??? warning "After turning on `engine.ha.enabled`, the console shows `<name>-0` with the old engine's history"
    New in 0.4.1, and expected: the registration Job renames the existing `<name>` registration to
    `<name>-0` (`syncd-console admin rename-engine`), keeping its configuration versions, reports
    and history, and registers `<name>-1` beside it. Engine names in `CONSOLE_SELF_SERVICE_ENGINE`
    and `CONSOLE_CONFIG_GIT_ENGINES` are not renamed: change them in `console.env`. See
    [An active/passive pair](install.md#an-active-passive-pair).

??? failure "`kubectl exec ... -- syncd status` fails"
    The image has no shell, so run `syncd` itself, as `kubectl -n syncd exec syncd-engine-0 --
    syncd status`, not through `sh -c`. The command reaches the engine through its control socket,
    `/run/syncd/control.sock`, which exists only while the engine runs.

??? failure "`kubectl exec ... -- syncd apply-config` is refused: `ApplyConfig needs the configure tier, which in strict mode only a client certificate listed under api.access.configure has`"
    New in 0.5.0, on a release first installed with 0.5.0: the image's initial configuration is
    strict, and the control socket reads and operates but applies no configuration. Publish from
    the console. A release first installed before 0.5.0 keeps its file on the data volume, stays
    compatible, and warns; the chart cannot set `api.access`, so opting in means editing
    `/var/lib/syncd/engine.yaml` on the volume (see the source repository's KUBERNETES.md,
    "Control API access").

??? warning "Sources, the database or webhook receivers become unreachable after turning on NetworkPolicies"
    With `networkPolicy.egress.enabled`, the pods reach DNS and the engine's control API only,
    until you add the rest in `networkPolicy.egress.extra`: the sources and targets, the console's
    database (for the console, the Job, and a pair's lease), the identity provider, webhook and
    hook receivers, the mail relay, the Git server, S3. Symptoms: `SyncdSourceDown`, a console
    that does not start, deliveries retried with `connection failed`, a publish refused by an
    unreachable hook. Check `kubectl describe networkpolicy`. Your CNI must enforce NetworkPolicies
    at all; kind's does not. See [NetworkPolicies](install.md#networkpolicies).

??? warning "Dashboards or queries lost the engine after upgrading the chart to 0.4.0"
    The ServiceMonitor now sets its targets' `job` label to `engine.metrics.job` (`syncd`);
    before, the operator named the job after the Service (`syncd-engine`). Change queries that use
    `job="syncd-engine"`. This is what lets the shipped rules fire: before 0.4.0,
    `SyncdEngineDown` and `SyncdMemoryNearLimit` could never fire with the ServiceMonitor. Load
    the rules with `engine.metrics.prometheusRule.enabled` (see
    [Monitoring on Kubernetes](operator-guide.md#monitoring-on-kubernetes)).

??? warning "The console cannot reach the engine after a reinstall"
    Fixed in 0.4.0: `helm uninstall` now keeps the `syncd-pki` Secret, so a reinstall reuses the
    certificates the console already has. With chart 0.3.0, a reinstall issued new certificates,
    and the console could not reach the engine until the registration Job ran again: run
    `helm upgrade` once to run it.
