# Troubleshooting

Symptoms, likely causes and fixes. Start with `sudo syncd status`, `sudo syncdctl status` and the
journal (`journalctl -u syncd -u syncd-console -e`). Commands, metrics and alert rules are in the
[Operator guide](operator-guide.md); settings are in [Configuration](configuration.md).

## Installation and upgrade

??? failure "`choose the console database: --local-database, or --database-url postgres://...`"
    `syncdctl install` needs to know where the console's database is. Pass `--local-database` to
    create it on this host's PostgreSQL, or `--database-url` with a database you created. See
    [Installation](install.md#the-consoles-database).

??? failure "`cannot reach PostgreSQL as postgres on /var/run/postgresql (port 5432); is the server running?`"
    `--local-database` runs `psql` as the `postgres` account through the local socket. Start the
    PostgreSQL server (and on a fresh RHEL host, initialise it first), or point `SYNCD_PG_SOCKET`
    and `SYNCD_PG_PORT` at the right socket directory and port. `psql is required` means the
    PostgreSQL server or client is not installed at all. On RHEL 8, enable a PostgreSQL newer than
    the default 10 first (`dnf module enable postgresql:16`).

??? failure "`--fips needs a FIPS build of syncd`"
    The release you ran is not a FIPS build (it has no `FIPS` file, and no `-fips` in its name).
    The published releases are not FIPS builds; see [FIPS 140-3 and DISA STIG](fips-and-stig.md).

??? failure "`syncd <version> is installed already; to upgrade: syncdctl upgrade <release>`"
    `/etc/syncd/console.env` exists, so this host already has syncd. Upgrade it, or, to start
    again from nothing, `syncdctl uninstall --purge` first (it removes the configuration and the
    engine store, not the console's database).

??? failure "`<dir> is damaged: files do not match MANIFEST.sha256`"
    The unpacked release differs from what was built: a damaged download or a changed file.
    Download it again and check it against `SHA256SUMS` before unpacking.

??? failure "`syncd did not start; see the log above`, or `the engine did not become healthy within 120s`"
    `syncdctl` prints the last 25 lines of the failing service's journal. Common causes: the
    console cannot reach its database (wrong `CONSOLE_DATABASE_URL`, or PostgreSQL's
    `pg_hba.conf` refuses the account), a port already in use (7443, 8080, the metrics port), or a
    secret the configuration names missing from `/etc/syncd/engine.env`. On a slow host or a large
    store, raise `SYNCD_HEALTH_TIMEOUT` (seconds). During an upgrade, the failure makes
    `syncdctl` roll back to the previous release and restore the backup by itself.

??? failure "`pg_dump is not installed: install the PostgreSQL client tools to back up the console database, or pass --skip-db-backup`"
    Upgrades and backups dump the console's database with `pg_dump`. Install the PostgreSQL client
    tools (`postgresql-client` on Debian and Ubuntu, `postgresql` on RHEL). Use `--skip-db-backup`
    only if you back up the database another way: without the dump, a rollback cannot restore it.
    On a package install the backup runs before the new files are unpacked, so the package
    upgrade fails here and nothing changes.

??? failure "`not enough space in /var/lib/syncd/backups for a backup (... KiB needed)`"
    An upgrade copies the engine store and the console's files before it changes anything, and
    refuses to start without room for them plus 10 MiB. Free space, or remove old backups you
    have copied elsewhere.

??? failure "`the engine configuration is not valid for syncd <version>`"
    The new release's engine does not accept the running configuration. Nothing was stopped. Run
    the command it prints (`<release>/bin/syncd -c /etc/syncd/engine/engine.yaml validate`) to see
    why, fix the configuration (publish a fixed version from the console), and upgrade again.

??? failure "`<version> is older than the installed <version>`"
    `syncdctl upgrade` only moves forward. To go back, use `syncdctl rollback` or restore a backup;
    to install the same version again, `--reinstall`.

??? failure "`this host uses the syncd package: upgrade with the package manager`"
    The host was installed from a `.deb` or `.rpm`. Upgrade and remove it with `apt` or `dnf`.
    To roll back, install the previous package, then `syncdctl restore` the backup taken before
    the upgrade.

??? failure "`another syncdctl is running`"
    Another upgrade, rollback or install holds `/run/syncdctl.lock`. Wait for it to finish.

??? failure "`no backup of <old> was taken before upgrading to <new>`"
    `syncdctl rollback` restores the backup named `*-upgrade-<old>-to-<new>`, and it is gone (only
    the newest three backups are kept). Restore an older backup with `syncdctl restore`, or roll
    back the programs alone with `--keep-data` if the previous release reads the store's format.

??? failure "`Start request repeated too quickly` (systemd's `start-limit-hit`)"
    systemd refuses to start a unit started more than 5 times in 10 seconds. Before 0.3.0,
    `syncdctl`'s own restarts could add up to that, for example `renew-certs` run straight after an
    upgrade; 0.3.0 clears the limit before each of its restarts. If you see it now, the service
    is in a crash loop: read why in `journalctl -u syncd -e` (or `-u syncd-console`), fix it, then
    `sudo systemctl reset-failed syncd` and start it.

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
    [Certificates](operator-guide.md#certificates).

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
    other control commands against the running service instead.

??? failure "`starting the metrics listener on <addr>`, or the control API cannot listen"
    The address in `api.metrics_listen` or `api.listen` is in use. A taken port stops startup.
    Find the process (`ss -ltnp`), or change the address in `/etc/syncd/engine/engine.yaml` and
    restart syncd.

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
      commas;
    - `CONSOLE_OIDC_DEFAULT_ROLE: unknown role ...`.

    Fix `/etc/syncd/console.env` and `systemctl restart syncd-console`. See
    [Single sign-on](single-sign-on.md).

??? failure "The console will not start: `CONSOLE_REVEAL_ROLES: ... is not a role (or none)`"
    List roles by name (`admin`, `designer`, ...), comma-separated, or `none`.

??? failure "The console will not start: `.../master.key is missing, and the database holds secrets sealed with the master key it held`"
    New in 0.3.0. The key file is gone (a restore that missed it, a new data directory), and the
    console's database holds the client keys of engines registered with mTLS, which only that
    key opens. Before 0.3.0 the console created a new key and started; the engines then failed at
    their next call. Restore `/var/lib/syncd/console/master.key` from a backup (owner
    `syncd-console`, mode 0600), or set `CONSOLE_MASTER_KEY` to the old key, and restart. If the
    key is lost for good, see [The master key](operator-guide.md#the-master-key):
    `CONSOLE_NEW_MASTER_KEY=true` starts the console with a new key, and the engines must be
    registered again.

??? failure "The console will not start: `the master key does not open the secrets the database holds (engine <name>'s client key)`"
    New in 0.3.0. The key, from the file or `CONSOLE_MASTER_KEY`, is not the one the secrets were
    sealed with: often the key of another installation, or a database restored without its own
    key. Put back the key that belongs with this database. Only if it is lost for good, set
    `CONSOLE_NEW_MASTER_KEY=true` (or `--new-master-key`); the console logs that engines
    registered with mTLS must be registered again. On Kubernetes, this is what a deleted
    `syncd-console` Secret gives: see [Kubernetes](#kubernetes).

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

??? failure "`sign-in failed; after repeated failures an account locks for 15 minutes`"
    Every failed local sign-in gets this same answer, so it reveals nothing about the account.
    The audit log (**Audit log**, event `auth.login`) records the real reason:

    | Reason | Fix |
    |---|---|
    | `wrong password`, `unknown account` | Check the username and password |
    | `locked` | Five wrong passwords lock the account for 15 minutes. Wait, or have an administrator reset its password (**Users**, the reset button on the account), which also unlocks it |
    | `disabled` | An administrator disabled the account in **Users** |
    | `no local password` | The account signs in through single sign-on; use the single sign-on button |
    | `local sign-in is limited to break-glass accounts` | An administrator limited local sign-in. Use single sign-on, or a break-glass account under "Break-glass sign-in" |

    If every administrator is locked out, issue a new one-time password on the host:
    `syncd-console admin reset-password --user admin`, run as `syncd-console` with the console's
    settings (the full command is in [First sign-in](install.md#first-sign-in)). It unlocks the
    account and signs out its sessions.

??? failure "`admin reset-password`: `<user> signs in through single sign-on and has no console password`"
    New in 0.3.0. The account signs in through the identity provider, and a console password would
    let it sign in without the provider and its MFA, so the command refuses, as the **Users** page
    does. Sign in through single sign-on. If the provider is unavailable and you need the account
    now, add `--allow-single-sign-on`; the audit log's `user.password_reset` then records
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
    password was mistyped.

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
    The ID token's role claim (`CONSOLE_OIDC_ROLE_CLAIM`, default `groups`) has no value listed in
    `CONSOLE_OIDC_ROLE_MAP`. Check that the provider puts the claim in the ID token (some need an
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

??? failure "`the <role> role cannot do this`"
    The account's role does not allow the action: registering engines and managing accounts need
    `admin`; editing and simulating configuration need `designer`. See [Admin guide](admin-guide.md).

??? failure "A published configuration is refused: `engine.data_dir cannot be changed through the control API` or `the api section can only be changed on the engine host`"
    The version changes settings that stay with the engine host. Start from the running
    configuration (import it in the console), keep its `engine.data_dir` and `api` sections as
    they are, and change those only in `/etc/syncd/engine/engine.yaml` on the host.

??? failure "A published configuration is refused: `N of M test cases fail`"
    A computed attribute's test cases fail, and the engine refuses to apply it. The console lets
    such a version be saved, but not published. Fix the rule or the test case. See
    [Rules](rules.md).

## The engine at work

??? warning "`the engine is busy (<activity>); try again when it is done`"
    A long operation runs: a full sync (after `syncd full-sync`, or after a configuration is
    published), a reconciliation, or the deletes a full read found. `syncd status` shows
    `busy: <activity> (for ...)`; alert `SyncdEngineBusy` fires after an hour. Commands wait 10 s,
    then are refused rather than queued. Source changes wait until it ends; they aren't lost.

    At a million entities on 4 vCPU a full sync takes about 9 s, a reconciliation about 6 s, and
    a reload that changed every entity about 2 minutes; it grows linearly with the store. Wait and
    retry. If it runs far longer, look at the log for errors, at CPU (`top -p $(pgrep -x syncd)`)
    and at disk I/O: rules that read many other entities (`referrers()`, large groups) slow a full
    sync down. Stopping the engine is safe: a full sync resumes where it stopped at the next start.

??? warning "`target <name> UNREACHABLE`, alert `SyncdTargetDown`"
    The engine lost its connection to the directory and can't make a new one. The log says
    `export target is unreachable` and `the directory is still unreachable`. It keeps reading and
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

??? warning "A published configuration rolled back: `the new configuration did not start; restoring the previous one`"
    The engine saved and reloaded the new configuration but could not start with it: a source or
    target it could not connect to, a listen address in use, a certificate it could not read, or a
    secret reference (`env:`, `file:`) that doesn't resolve on this host. It wrote the previous
    configuration back and runs that one; nothing was exported with the new rules. The console
    shows the publish as failed and the version stays a draft.

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
    console) shows what it would send, without sending anything.

??? warning "Deletes are held back: `exceeds the deletion threshold`"
    A source read, or a change of rules or scope, would delete more entries than the threshold
    allows. Nothing was deleted; other changes still flow. `syncd status` lists the scope, the
    count and since when; alert `SyncdDeletesHeld`.

    Find out why first: a source that came back empty or partial (a wrong filter, a failed view,
    an expired account) is the usual cause, and approving would delete real entries. If the
    source is wrong, fix it; the next full read clears the hold. If the deletes are intended,
    approve them once: `sudo syncd approve "<scope>"`, with the scope exactly as `syncd status`
    shows it.

??? warning "A source is `RETRYING` or `FAILED`, or keeps restarting (`SyncdSourceDown`, `SyncdSourceFlapping`)"
    The error in `syncd status` and the log (`source failed; restarting it from its checkpoint`)
    says why: credentials, a dropped replication slot, a server that's gone. Restarts back off
    from 1 s to `engine.source_retry_max_secs` (60); a source that ran for a minute starts over at
    1 s. Fix the cause; the next restart picks up from its checkpoint. `sudo syncd test-connection
    <connector>` checks the connection with the same settings and secrets as the service.

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
    a very large `engine.cache_mb`. Raise `MemoryMax` (`systemctl edit syncd`) or the host's
    memory. Don't remove the limit. See [Sizing](availability-and-sizing.md#the-memory-limit).

??? warning "Entities are quarantined (`SyncdQuarantined`)"
    `syncd status` shows `view people: 3 quarantined (exports held back by a failing rule)`, and
    the console's engine page *Held back by a failing rule*. A computed attribute with
    `on_error: quarantine` fails for these entities, so the engine holds their exports back rather
    than write a wrong value. From 0.3.0 the hold is kept in the store, so a restart keeps it; it
    ends by itself once the rule evaluates again for the entity.

    1. List them with the rule and its error: `sudo syncd quarantine` (the engine page shows the
       same).
    2. Fix the source data the rule reads, and the next change to the entity releases it. Or fix
       the rule, publish it, and the full sync that follows releases the entities.

??? warning "`an entry moved or renamed in the target was put back where the publication places it`"
    Or `... was found by its id`, a warning in the log with the DN it was found at. Someone else
    moved or renamed an entry the engine manages. From 0.3.0 the engine finds it by the target's
    own id (`objectGUID` on Active Directory, `nsUniqueId` on 389, `entryUUID` elsewhere), puts it
    back at the DN the publication computes, and applies the change there. No second entry is
    added.

    1. Nothing to do, if the move was a mistake.
    2. If the entry belongs somewhere else, change the publication's DN template, or the source
       data it is built from: otherwise the engine puts it back every time.

??? failure "An export fails: `find <dn> by its id: two entries have it (...)`"
    Two entries in the target share the id the engine looks entries up by: usually an entry
    restored or copied with its id. Remove the one that should not be there, then
    `sudo syncd retry`.

??? warning "A PostgreSQL source fails: `publication <name> lacks <tables>, which <source> follows: changes to them would not stream`"
    New in 0.3.0. The publication exists, from an earlier configuration, and lacks tables added to
    the object's `cdc.watch` since. The engine adds them itself when its role may (the log says
    `the publication lacked tables the object follows; added them`); here it may not. Run the
    statement the error gives (`ALTER PUBLICATION <name> ADD TABLE ...`) as a role that owns the
    publication. The source's next restart picks it up. Before 0.3.0, changes to those tables were
    silently not streamed. See [Connectors](connectors.md).

??? warning "A database source fails with `<host>:<port>: no answer within 10s`"
    New in 0.3.0. A PostgreSQL, MySQL or SQL Server server accepted the connection and then did
    not finish its handshake (TLS, login) within 10 seconds: a hung server, or a proxy or load
    balancer whose backend is gone. Before, the source waited for ever in `starting`; now it fails,
    and is retried with its usual back-off. MySQL's `no binary log stream within 10s` is the same
    for the binary log request. Check the server, and the path to it, from the engine's host. A
    connection refused at once means nothing listens on the port. MongoDB already had a 10-second
    limit.

??? warning "Dead letters keep growing (`SyncdDeadLetters`)"
    `sudo syncd dlq` lists each failed export with its DN and error. Errors from the target
    (schema, access rights, a missing parent entry) need fixing there or in the publication;
    then `syncd retry`. `syncd discard` removes one you don't want sent.

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

??? failure "`this listener is read-only: commands need mTLS (api.tls), or api.local_control: true`"
    The engine's control API has no `tls` section, and plain-text loopback listeners accept only
    reads. The installed configuration uses mutual TLS; keep it. Similarly,
    `revealing sensitive values needs mTLS (api.tls), or api.local_reveal: true` for `--reveal`.

??? failure "`the engine has too many commands waiting; try again shortly`, or `the engine is shutting down`"
    The engine's command queue is full, or it is stopping. Retry in a moment; check `systemctl
    status syncd` if it persists.

??? warning "Prometheus gets nothing from the engine"
    Metrics are off unless `api.metrics_listen` is set in `/etc/syncd/engine/engine.yaml`, and it
    cannot be set from the console. Set it, restart syncd, and check the log for `metrics
    listening`. The shipped alert rules expect the scrape job to be called `syncd`. See
    [Monitoring](operator-guide.md#monitoring).

## Kubernetes

??? failure "`helm install` fails: `console.database.url or console.database.existingSecret is required`"
    The console keeps its data in a PostgreSQL you provide, and the chart has no bundled one. Set
    `console.database.url`, or `console.database.existingSecret` (with `existingSecretKey`, by
    default `url`). See [Install with Helm](install.md#install-with-helm).

??? failure "The pods do not start: `ImagePullBackOff`, or `exec format error`"
    No release has been published to `ghcr.io/winllc` yet: build the images and point
    `image.engine.repository` and `image.console.repository` at your registry (see
    [Getting the release](install.md#getting-the-release)), with `imagePullSecrets` if it needs
    them. `exec format error` means an arm64 node: the images are amd64 only, so schedule the pods
    on amd64 nodes (`nodeSelector: {kubernetes.io/arch: amd64}`).

??? failure "The console pod restarts: the master key does not open the stored secrets"
    The `syncd-console` Secret was deleted, or replaced, and the chart generated a new master key.
    The console refuses to start over the client key it sealed with the old one. Put the backed-up
    Secret back and restart the console
    (`kubectl -n syncd rollout restart deploy/syncd-console`). If the key is lost for good, see
    [The master key Secret](operator-guide.md#the-master-key-secret).

??? failure "The registration Job fails"
    `syncd-register` runs after each install and upgrade, and retries (12 times, for up to 15
    minutes) while the engine or the console's database is not up yet. A failed Job stays, for its
    logs, until the next install or upgrade: `kubectl -n syncd logs job/syncd-register`. Usual
    causes: the console's database URL is wrong or unreachable, the engine pod is not ready
    (`kubectl -n syncd describe pod syncd-engine-0`), or the master key does not open the stored
    secrets (above).

??? failure "The engine pod restarts before it is ready"
    The startup probe gives the engine 10 minutes to open its store and listen on 7443. Look at
    `kubectl -n syncd logs syncd-engine-0 --previous`. A store migration or a very large store can
    take longer; so can a memory limit the engine keeps hitting (`OOMKilled` in
    `kubectl describe pod`): raise `engine.resources.limits.memory` (see
    [Sizing on Kubernetes](availability-and-sizing.md#sizing-on-kubernetes)).

??? failure "`kubectl exec ... -- syncd status` fails"
    The image has no shell, so run `syncd` itself, as `kubectl -n syncd exec syncd-engine-0 --
    syncd status`, not through `sh -c`. The command reaches the engine through its control socket,
    `/run/syncd/control.sock`, which exists only while the engine runs.

??? warning "The alert rules never fire with the ServiceMonitor"
    The shipped rules match `job="syncd"`; a ServiceMonitor's job is the engine Service's name
    (`syncd-engine`). Change the rules, or relabel the job. See
    [Monitoring on Kubernetes](operator-guide.md#monitoring-on-kubernetes).
