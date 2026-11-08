# Troubleshooting

Symptoms, likely causes and fixes. Start with `sudo syncd status`, `sudo syncdctl status` and the
journal (`journalctl -u syncd -u syncd-console -e`). Commands, metrics and alert rules are in the
[Operator guide](operator-guide.md); settings are in [Configuration](configuration.md). The source
repository's `docs/RUNBOOK.md` has the same entries for each alert, and the support bundle
(`sudo syncdctl support-bundle`) collects what support needs (see
[Support bundle](operator-guide.md#support-bundle)).

## Installation and upgrade

??? failure "`gpg: BAD signature`, `gpg: Can't check signature: No public key`, or `sha256sum: WARNING: ... computed checksum did NOT match`"
    New in 0.4.0, a release's `SHA256SUMS` is signed (`SHA256SUMS.asc`). `No public key`: import
    the syncd release key first (`gpg --import syncd-release.asc`), and check its fingerprint
    against the one you were given separately. `BAD signature`: `SHA256SUMS` is not the one that
    was signed; download both files again. A file whose checksum does not match is damaged or
    not the published one: download it again, and do not install it. A release without
    `SHA256SUMS.asc` was published without the signing key. See
    [Verifying a release](install.md#verifying-a-release).

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
    Use the FIPS bundle, `syncd-<version>-linux-x86_64-fips.tar.gz` (x86_64 only; there are no
    FIPS packages). See [FIPS 140-3 and DISA STIG](fips-and-stig.md).

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
    `pg_hba.conf` refuses the account), a port already in use (7443, 8080, the metrics port), a
    secret the configuration names missing from `/etc/syncd/engine.env`, or a setting in
    `console.env` with a trailing comment (below). On a slow host or a large store, raise
    `SYNCD_HEALTH_TIMEOUT` (seconds). During an upgrade, the failure makes `syncdctl` roll back to
    the previous release and restore the backup by itself.

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

    Upgrading to 0.4.0, the usual reason is `publication <p>: Active Directory takes passwords only
    over LDAPS or StartTLS; target <t> connects to ldap://... without starttls`: a `passwords`
    block on an Active Directory target over plain LDAP, which 0.3.0 refused only when it wrote a
    password. Give the target an `ldaps://` URL or `starttls: true`, publish, and upgrade again.

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
    back the programs alone with `--keep-data` if the previous release reads the store's format.

??? failure "After a rollback to 0.3.0 with `--keep-data`, the engine does not start, or leavers, windows and canaries stopped"
    0.3.0 refuses settings it doesn't know: publish a configuration without the settings new in
    0.4.0 before rolling back with the store kept, or put the previous configuration back in
    `/etc/syncd/engine/engine.yaml` and restart. 0.3.0 also ignores the timers and held changes
    0.4.0 kept in the store: staged leaver steps, effective dates, change windows, canaries and
    group expiry wait on timers it does not act on. See
    [Rolling back 0.4.0 to 0.3.0](install.md#rolling-back-040-to-030).

??? failure "`Start request repeated too quickly` (systemd's `start-limit-hit`)"
    systemd refuses to start a unit started more than 5 times in 10 seconds. `syncdctl` clears the
    limit before each of its restarts, so if you see it, the service is in a crash loop: read why
    in `journalctl -u syncd -e` (or `-u syncd-console`), fix it, then
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

??? failure "`starting the metrics listener on <addr>`, or the control API cannot listen"
    The address in `api.metrics_listen` or `api.listen` is in use. A taken port stops startup.
    Find the process (`ss -ltnp`), or change the address in `/etc/syncd/engine/engine.yaml` and
    restart syncd.

??? failure "The console fails to start, or refuses a setting, after uncommenting a line of `console.env`"
    systemd reads `/etc/syncd/console.env` (and `engine.env`) as an `EnvironmentFile`: everything
    after the `=` is the value, including a `# ...` note at the end of the line. Several commented
    lines of the release's template end with one, for example
    `#CONSOLE_PUBLISH_HOOK_ON_ERROR=refuse   # or allow` or
    `#CONSOLE_CONFIG_GIT_AUTO_PUBLISH=off   # off | validate | publish`. Uncommented as they are,
    the value is `refuse   # or allow`, which the console refuses, or takes as a wrong value. Remove
    the trailing note, keep comments on lines of their own, and restart:
    `systemctl show syncd-console -p Environment` shows the values systemd passes.

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
    The key file is gone (a restore that missed it, a new data directory), and the console's
    database holds secrets only that key opens: the client keys of engines registered with mTLS,
    webhook addresses and secrets, secret environment values. Restore
    `/var/lib/syncd/console/master.key` from a backup (owner `syncd-console`, mode 0600), or set
    `CONSOLE_MASTER_KEY` to the old key, and restart. Off-host backups (`syncdctl backup --to`) do
    not hold the key: keep it apart. If the key is lost for good, see
    [The master key](operator-guide.md#the-master-key): `CONSOLE_NEW_MASTER_KEY=true` starts the
    console with a new key, and the engines must be registered again.

??? failure "The console will not start: `the master key does not open the secrets the database holds (engine <name>'s client key)`"
    The key, from the file or `CONSOLE_MASTER_KEY`, is not the one the secrets were sealed with:
    often the key of another installation, or a database restored without its own key. Put back
    the key that belongs with this database. Only if it is lost for good, set
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

??? failure "The engine refuses its configuration: `engine.ha.lease: ttl_secs must be at least 3`, `engine.ha: snapshot_every_secs must be at least 10`"
    The pair's settings are checked with the rest of the configuration. Raise the values (the
    defaults are 15 and 300). See [High availability](availability-and-sizing.md#high-availability).

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
    The audit trail (**Audit log**, event `auth.login`) records the real reason:

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

??? failure "`the <role> role cannot do this`"
    The account's role does not allow the action: registering engines and managing accounts need
    `admin`; editing and simulating configuration need `designer`. See [Admin guide](admin-guide.md).

??? failure "A published configuration is refused: `engine.data_dir cannot be changed through the control API`, `the api section can only be changed on the engine host` or `engine.ha can only be changed on the engine host`"
    The version changes settings that stay with the engine host. Start from the running
    configuration (import it in the console), keep its `engine.data_dir`, `api` and `engine.ha`
    as they are, and change those only in `/etc/syncd/engine/engine.yaml` on the host.

??? failure "A published configuration is refused: `N of M test cases fail`"
    A computed attribute's test cases, a rule package's tests, or (with Git sync) a golden-file
    test fail, and the version cannot be published. The console lets such a version be saved,
    but not published. Fix the rule or the test case. See [Rules](rules.md) and
    [Configuration as code](config-as-code.md).

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
    - An `on_expiry: empty` group whose object class needs a member (`groupOfNames`)
      dead-letters: use `report` or `delete` for those.

    `REVIEW DUE` is the softer version: `review_every` passed and nothing changed. It sends no
    mail; the same review clears it. See [Group lifecycle](change-safety.md#group-lifecycle).

??? warning "Records wait to join an entity (`SyncdUnmatchedRecordsAgeing`)"
    Records of a source found no entity by the view's join rules (or several), and some have
    waited longer than `correlation.unmatched_alert_after`. Nothing of theirs reaches the targets.
    `sudo syncd correlation suggest --view <view>` (or the console's Correlation page) scores
    each against the entities; accept the right ones (`syncd correlation accept --view <view>
    --above 0.95`, a dry run until `--yes`). Fix the data for the rest. See
    [Correlation assistance](reports-and-data-quality.md#correlation-assistance).

??? warning "A data-quality check fails (`SyncdQualityErrors`, `quality ...: N entities fail (error)`)"
    A view's `quality` check does not hold for these entities. `sudo syncd quality <view>
    --check <check> --list` lists them with the message (never values); the console's Data
    quality page links each to the explorer. Fix the data in the source, or the check. With
    `on_error: quarantine` their exports wait (they show in `syncd quarantine`); with
    `skip_publication` they are out of those publications' scope, so the publication's leaver
    policy applies to their entries. See
    [Data-quality checks](reports-and-data-quality.md#data-quality-checks).

??? warning "A leaver was not re-enabled, or kept memberships"
    The staged leaver policy re-enables a rehire whose entity comes back. An entry recreated under
    a new entity is not re-enabled, and memberships in groups syncd doesn't manage are not
    stripped. See [Staged leaver policy](change-safety.md#staged-leaver-policy).

## Passwords

??? failure "`passwords: ... N failed; last failure: ...` in `syncd status`, or the webhook `password.delivery-failed`"
    A password change was not delivered. The log names the account and DN (`password change not
    delivered`), never the password. Usual reasons:

    - **The target refused it**: its password policy (length, history, complexity), or the sync
      account's access rights. A refused change is reported and not retried; the person's next
      change is delivered.
    - **It waited too long**: the account was not provisioned or exported yet, or the target
      was unreachable, for longer than `passwords.ttl_secs` (a day by default). It is dropped and
      counted as failed.
    - **Active Directory over plain LDAP**: AD takes `unicodePwd` only over LDAPS or StartTLS;
      from 0.4.0 such a configuration is refused at validation.

    There are no password metrics or alert rules: watch the status line, the console, or the
    webhook. See [Password synchronisation](password-sync.md).

??? warning "`the target refused the initial password; the entry was created without it`"
    A `passwords.initial` password (generated or read from the entity) was refused by the target,
    usually its password policy. The entry exists without a password, and on Active Directory
    stays disabled. A refused initial password is not retried: set one by hand, or make
    `generate` meet the policy (`length`, `classes`). An entry a drift check puts back is
    re-added without one. `the initial password rule failed; the entry is created without one`
    means the `from` expression failed.

??? warning "Password agents or self-service stop working after an HA failover"
    Only the active engine of a pair runs the agents' ingest listener and answers self-service
    changes, and the console's `CONSOLE_SELF_SERVICE_ENGINE` names one engine. Point agents and
    the console at the engine that is now active, or at an address that follows it. See
    [Password synchronisation](password-sync.md#high-availability).

## Backups and restore

??? warning "No recent backup (`SyncdBackupStale`, `backups: ... FAILED <ago>: <why>`)"
    New in 0.4.0. No scheduled backup (`engine.backup`) has succeeded for two intervals, or none
    since it was configured. The reason is in the status line, the log (`backup failed`) and the
    `backup.failed` webhook: a destination that does not answer, credentials that expired
    (`AWS_*` in `/etc/syncd/engine.env`; a web identity token the pod no longer has), a bucket
    policy that refuses the key (`AccessDenied`, `SignatureDoesNotMatch`: a wrong secret or
    session token), a full disk for a directory destination. Fix it, then take one by hand:
    `sudo syncdctl backup --to <the same destination>`. The schedule carries on by itself.

??? failure "`syncd backup`: `a backup carries the whole store: it needs mTLS (api.tls), the control socket, or api.local_reveal: true for the loopback listener`"
    The backup was asked over a plain-text loopback listener that may not reveal values. Use the
    installed mutual TLS (the default), the control socket, or `sudo syncdctl backup --to`.

??? failure "`syncdctl backup --to ...`: `unknown option --sse` (or `--endpoint`, `--region`, `--s3-ca`)"
    `syncdctl backup --to` takes only `--keep` and `--skip-db-backup`. Set `AWS_ENDPOINT_URL_S3`
    and `AWS_REGION` in `/etc/syncd/engine.env`, give the bucket a default encryption, or run
    `syncd backup` with those options. `--to takes a directory or s3://bucket/prefix` means the
    destination is neither.

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

??? warning "After a restore, the engine runs its old configuration"
    `syncd restore` never writes over the live configuration: the backed-up one is beside it, as
    `/var/lib/syncd/engine/engine.restored.yaml`. `syncd config diff engine.restored.yaml
    /etc/syncd/engine/engine.yaml` says what differs; publish the one you want.

## High availability

??? failure "Neither engine is active (`SyncdHaNoActive`, `NO ENGINE HOLDS THE LEASE`)"
    New in 0.4.0. Both engines of the pair are up and passive, so nothing is read or exported.
    `syncd status` on either shows the lease's last error (`LEASE ERROR: ...`, and `the lease
    could not be read or renewed` in the log): the lease's database is unreachable or refuses the
    role (the URL, the network, the password, the rights on the `syncd_lease` table), or the
    lease file's file system is not mounted; or both engines are configured with `role:
    passive`. As soon as one engine can take the lease, it does.

??? warning "The snapshots are late (`SyncdHaSnapshotStale`)"
    On the active engine, taking them fails (`taking a store snapshot failed`: a full disk under
    the data directory, say). On the passive one, pulling them does (`pulling a store snapshot
    failed`): the active engine's API unreachable on 7443, a client certificate it does not
    accept, or a name its certificate does not carry. `this engine takes no snapshots: it is not
    the active engine of a pair` means the passive engine reached the wrong one. Until it is
    fixed, a takeover starts from an older store and has more to read and reconcile; nothing is
    lost.

??? warning "`the pair's lease is lost: stopping the sources and the exports; this engine is now the passive one`"
    This engine could not renew the lease for longer than its TTL (cut off from the lease's
    database, or frozen), and stepped down. Whatever it had left to write was fenced off, not
    written: the other engine plans it again. Check the path to the lease's database. The engine
    carries on as the passive one.

??? warning "Both engines claim to be active (`syncd_ha_active` 1 on both)"
    Only for the moments before the old one notices: an engine checks the lease before every
    export batch and writes nothing within a second of its expiry. If it lasts, the hosts' clocks
    disagree (the `file` lease is judged by them; keep them in NTP sync), or the lease file is not
    on a file system both hosts share.

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
    Password changes the old engine had queued after its last snapshot are not delivered again.

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
      refused whatever the settings.
    - `the URL must start with https://`, `the URL must not carry credentials`.
    - `connection failed`, a TLS error: the console reaches receivers directly, never through
      `HTTPS_PROXY`. Open the firewall (on Kubernetes, `networkPolicy.egress.extra`), or set
      `CONSOLE_WEBHOOKS_CA_FILE` for a receiver with a private CA.
    - A webhook listed with its URL undecryptable: the console runs with another master key.
      Save the URL again and press **New secret**.

    A receiver that says the signature is wrong must check the HMAC over the raw body, with the
    secret shown when the webhook was created. See [Webhooks](webhooks.md).

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
    The console speaks SMTP itself, with STARTTLS and AUTH PLAIN only.

    - **No STARTTLS offered**: the relay does not support it. Use one that does, or, for a relay
      on this host or a private network, `CONSOLE_SMTP_STARTTLS=false`.
    - **Port 465 (SMTPS, implicit TLS)** is not supported: the console waits for a greeting that
      never comes. Use the submission port, 587, with STARTTLS.
    - **`AUTH refused`**: the account or password (`CONSOLE_SMTP_USERNAME`,
      `CONSOLE_SMTP_PASSWORD_FILE`) is wrong, or the server only offers AUTH LOGIN, which the
      console does not speak. `CONSOLE_SMTP_PASSWORD_FILE is needed with a user name`: set it.
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
    console) shows what it would send, without sending anything; `syncd config diff` shows what
    changed by meaning.

??? warning "Deletes are held back: `exceeds the deletion threshold`"
    A source read, or a change of rules or scope, would delete more entries than the threshold
    allows. Nothing was deleted; other changes still flow. `syncd status` lists the scope, the
    count and since when; alert `SyncdDeletesHeld`.

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
    a large number of held changes, or a very large view in report mode; a very large
    `engine.cache_mb`. Raise `MemoryMax` (`systemctl edit syncd`) or the host's memory. Don't
    remove the limit. See [Sizing](availability-and-sizing.md#the-memory-limit).

??? warning "The store grew after the upgrade to 0.4.0"
    Entity history now keeps the newest `min_per_entity` entries (10) of every entity past
    `keep_days`, and every entry of an entity with a held change or a dead letter. `syncd status`
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

??? warning "Dead letters keep growing (`SyncdDeadLetters`)"
    `sudo syncd dlq` lists each failed export with its DN and error. Errors from the target
    (schema, access rights, a missing parent entry) need fixing there or in the publication;
    then `syncd retry`. `syncd discard` removes one you don't want sent. On Active Directory, a
    duplicate `sam_account_name` dead-letters: it has no `on_conflict: unique`, so mint the login
    once as a computed attribute with `unique()` instead.

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
    No release has been published to `ghcr.io/winllc` yet: build the images and point
    `image.engine.repository` and `image.console.repository` at your registry (see
    [Getting the release](install.md#getting-the-release)), with `imagePullSecrets` if it needs
    them. `exec format error` means an image for another platform: a release has arm64 images
    only when it was built with the arm64 runners (`docker buildx imagetools inspect` lists a
    tag's platforms), an image you built is for your build host's platform, and the `-fips`
    images are amd64 only. Schedule the pods on matching nodes
    (`nodeSelector: {kubernetes.io/arch: amd64}`), or build for the nodes' platform.

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

??? failure "The console pod restarts: the master key does not open the stored secrets"
    The `syncd-console` Secret was deleted, or replaced, and the chart generated a new master key.
    The console refuses to start over the secrets it sealed with the old one, and the registration
    Job fails too. Put the backed-up Secret back and run `helm upgrade` again. If the key is lost
    for good, upgrade once with `console.newMasterKey=true` and once more with it `false`: the
    Job registers the engine again by itself (`engine.re-registered`). Before 0.4.0 the Job did not
    get the setting and kept failing. See
    [The master key Secret](operator-guide.md#the-master-key-secret).

??? failure "The registration Job fails"
    `syncd-register` runs after each install and upgrade, and retries (12 times, for up to 15
    minutes) while the engine or the console's database is not up yet. A failed Job stays, for its
    logs, until the next install or upgrade: `kubectl -n syncd logs job/syncd-register` (with a
    pair, `-c register-0` and `-c register-1`). Usual causes: the console's database URL is
    wrong or unreachable, the engine pod is not ready (`kubectl -n syncd describe pod
    syncd-engine-0`), a NetworkPolicy with egress closed that does not let the Job reach the
    database, or the master key does not open the stored secrets (above). The Job gets
    `console.env` from 0.4.0, so a database option set there applies to it.

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
