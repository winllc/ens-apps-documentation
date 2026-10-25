# Troubleshooting

Symptoms, likely causes and fixes. Start with the application log (stdout) and
`GET /api/v1/sync/runs`. Settings referenced are in [Configuration](configuration.md).

## Startup

??? failure "Application fails at boot with a Hibernate schema validation error"
    Hibernate runs with `ddl-auto: validate` and Flyway owns the schema. The database schema does
    not match the code: usually a migration failed or the database was modified by hand. Restore
    from backup or fix the failed Flyway migration; do not switch `ddl-auto` to `update`.

??? failure "Startup error about the email template directory"
    `cert-alert.notifications.email.template-directory` is set but **empty**. The setting must be
    absent to be off. Remove it, or point it at a directory.

??? failure "Release package: `set CERT_ALERT_... in .env`"
    `docker compose` stops before starting anything when a required setting is empty. Fill in the
    named setting in `.env` and run `./install.sh` (or `docker compose up -d`) again.

??? failure "Helm: the install stops with a message about a required value"
    `ldap.url`, `ldap.base` and either `database.host` or `database.embedded.enabled` are
    required. With `tls.enabled`, so are `tls.keystore.existingSecret` and
    `tls.truststore.existingSecret`; with `x509.source: header`, `x509.proxySecret` (32 characters
    or more) and `x509.trustedCa`. Add the named value to your values file and run the command
    again.

??? failure "Startup fails: `ENS_X509_SOURCE=header needs ...` or `cannot be combined with SERVER_FORWARD_HEADERS_STRATEGY=native`"
    Header mode needs a proxy secret of at least 32 characters (`ENS_X509_PROXY_SECRET_FILE` or
    `ENS_X509_PROXY_SECRET`) and the CA file (`ENS_X509_TRUSTED_CA_FILE`), and cannot be used with
    the `native` forwarded-headers strategy. Supply them, and use
    `SERVER_FORWARD_HEADERS_STRATEGY=framework`. See
    [Configuration → Client certificates](configuration.md#client-certificates-ensx509).

??? failure "Startup fails reading the consent banner file"
    `ENS_CONSENT_BANNER_FILE` names a file CertAlert can't read. In the release package, put it in
    `tls/` and name it as `/etc/cert-alert/tls/<file>`; with Helm, mount it with `extraVolumes`.

??? failure "`install.sh` refuses to start: plain HTTP to other hosts (next release)"
    TLS is off and `CERT_ALERT_BIND_ADDRESS` is not `127.0.0.1`, and `.env` says
    `CERT_ALERT_ALLOW_PLAIN_HTTP=false` (as every new `.env` from the next release does). Turn TLS
    on, bind to `127.0.0.1` behind your own TLS-terminating proxy, or, for a lab, set
    `CERT_ALERT_ALLOW_PLAIN_HTTP=true`. See [Install → Choose where it listens](install.md#4-choose-where-it-listens).

??? failure "Startup stops: `cert-alert.security.enabled=false` ... allowed only with the 'dev' profile (next release)"
    `cert-alert.security.enabled=false` leaves every page and API open to anybody, and from the next
    release it is refused outside the `dev` profile. Remove the setting, or set it to `true`.

## Health

??? warning "`/actuator/health` is DOWN but the UI works"
    The aggregate includes the LDAP indicator and goes `DOWN` when the directory is unreachable,
    while cached data is still served. Use `/actuator/health/liveness` and `/readiness` for
    probes. The release package and the Helm chart already do. See
    [Operator guide → Health checks](operator-guide.md#health-checks).

??? warning "Container or pod is unhealthy after enabling TLS"
    With TLS on, CertAlert serves HTTPS on 8080. The release package's health check tries `/livez`
    over HTTP and then HTTPS, so it keeps working. With Helm, the probes use the metrics port
    (plain HTTP) while `metrics.enabled` is on; with it off, set `tls.enabled: true` in your values
    rather than passing the TLS settings in `extraEnv`, so the probes switch to HTTPS too.

??? warning "Prometheus gets nothing from `/actuator/prometheus`"
    Prometheus is served only on the management port. Set `MANAGEMENT_SERVER_PORT` (Helm:
    `metrics.enabled`, port 9090) and scrape that port. The release package does not publish it:
    add it to `docker-compose.yml`, bound to an interface only your Prometheus reaches.

## Signing in

??? failure "Single sign-on is refused: the provider left your groups out (`sso_error=groups`, next release)"
    The person is in more groups than Entra ID puts in a token (group overage), so the roles their
    groups grant cannot be known and the sign-in is refused, recorded as a failed `auth.login`. In
    the app registration's **Token configuration**, send only the groups assigned to the
    application (`groupMembershipClaims: ApplicationGroup`). See
    [Single sign-on → Microsoft Entra ID](single-sign-on.md#microsoft-entra-id-oidc).

??? failure "Nobody can trigger the first sync / no Admin menu"
    `cert-alert.security.admin-identifiers` is empty (the default) or does not name your `uid` or
    your entry's DN. Add your uid (or your entry's DN, as a YAML list item) and restart. An e-mail
    address and a `cn` do not count. See [Security → Roles](security.md#roles).

??? failure "I lost the Admin menu after upgrading"
    `admin-identifiers` named you by something that no longer grants the role: an e-mail address
    (the startup log warns about each one and names the uid to use), a `cn`, display name or
    preferred name. Replace it with your `uid` or your entry's DN, and restart.

??? failure "Client certificate is ignored; I always get the password form"
    - TLS must be enabled with a trust store containing the issuing CA and `client-auth: want`,
      or, behind a proxy, header mode must be set up (`ENS_X509_SOURCE=header`).
    - The certificate must be one the directory publishes **for a person**, and it must already be
      cached: run a sync first.
    - The log shows `Rejecting client certificate '...': the directory publishes no such certificate`
      when the fingerprint is unknown.
    - Server certificates never authenticate.

??? failure "Client certificate refused although the directory publishes it"
    The certificate is revoked or outside its validity dates. The log shows
    `Refusing client certificate '...' published by '...': its authority revoked it`,
    `...: it expired on ...` or `...: it is not valid until ...`, and the audit trail has a failed
    `auth.login` with the reason `certificate revoked`, `certificate expired` or
    `certificate not yet valid`. Sign in with a current certificate or the password form, and have a
    revoked or expired certificate removed from the directory. See [Security → Client certificate](security.md#client-certificate-x509).

??? failure "Client certificate refused: `certificate revoked`, `revocation unknown` or `issuer not allowed`"
    The check at sign-in refused it (see the failed `auth.login` in the audit trail and the
    `Refusing client certificate` line in the log).

    - `certificate revoked`: the CA has revoked it. Use a current certificate.
    - `revocation unknown`: no OCSP or CRL answer. Check that CertAlert can reach the responder or
      distribution point (and that `cert-alert.revocation.allowed-hosts` allows it), that the
      issuer's certificate is available (the presented chain, `ENS_X509_TRUSTED_CA_FILE`,
      `issuer-directory` or the trust store), or use `ENS_X509_OCSP_RESPONDER_URL` or
      `ENS_X509_CRL_DIR`. `ENS_X509_REVOCATION_FAIL=open` lets such certificates in, which DISA
      STIG does not allow.
    - `issuer not allowed`: the issuer's DN is not in `ENS_X509_ALLOWED_ISSUERS`. The log names
      it.

??? failure "Header mode: `certificate header not from a trusted proxy` or `certificate not trusted`"
    - The proxy's address is not in `ENS_X509_TRUSTED_PROXIES`, or it did not send the secret in
      `X-ENS-Proxy-Secret`. IPv4 ranges don't match IPv6 addresses: add the proxy's IPv6 range if
      it connects over IPv6.
    - The forwarded certificate does not chain to a CA in `ENS_X509_TRUSTED_CA_FILE`, is out of
      date, or is restricted to another use.

??? info "*Your session has ended. Sign in again to carry on.*"
    The session reached its idle or absolute limit, or the person signed in elsewhere beyond
    `ENS_SESSION_MAX_PER_USER`. The audit trail has `auth.session-expired` with the reason. See
    [Configuration → Security headers, banner and sessions](configuration.md#security-headers-banner-and-sessions).

??? info "The consent page appears again after signing out"
    By design: the agreement is kept in the session, so a new session asks again.

??? failure "A page doesn't work and the browser console reports a Content-Security-Policy violation"
    The page tried to run something the policy blocks. As a temporary escape hatch, set
    `ENS_CSP_REPORT_ONLY=true` and restart, and report the page to ENS Solutions. Don't leave it on.

??? failure "Browsers without a certificate cannot reach the site at all"
    `server.ssl.client-auth` is `need`. Set it to `want`.

??? failure "*That username and password were not accepted by the directory.*"
    The bind failed. Check `cert-alert.security.ldap.user-search-base` / `user-search-filter`, or use
    `user-dn-patterns` if the directory does not permit the service account to search. Confirm
    `cert-alert.security.ldap.enabled` is not `false`.

## Syncing

??? failure "Entries sync but no certificates do"
    Every attribute is populated but every certificate column is empty, with no error. The
    directory does not answer requests for `userCertificate;binary`, which is typical of virtual
    directories (for example Radiant Logic FID 7.4). Set
    `CERT_ALERT_LDAP_BINARY_CERTIFICATE_OPTION=false`. Check with:

    ```bash
    ldapsearch -H "$CERT_ALERT_LDAP_URL" -b "$CERT_ALERT_LDAP_BASE" "(uid=someone)" "userCertificate;binary"
    ldapsearch -H "$CERT_ALERT_LDAP_URL" -b "$CERT_ALERT_LDAP_BASE" "(uid=someone)" userCertificate
    ```

    If you mapped the certificate to a custom attribute name, also declare it under
    `spring.ldap.base-environment.java.naming.ldap.attributes.binary`.

??? failure "Columns are empty for some attributes"
    The attribute names under `cert-alert.ldap.user.*` / `server.*` do not match your directory. A
    wrong name produces an empty column, not an error.

??? failure "Sweep finds zero entries"
    Check `spring.ldap.base`, the `search-base` values (relative to it) and the `search-filter`
    object classes (`icOrgPerson` / `icOrgServer` by default).

??? warning "Servers have no points of contact; log shows `serverPOC value(s) that are not email addresses`"
    `cert-alert.ldap.server.require-email-poc` is `true` and the directory holds names in
    `serverPOC`. Set it to `false` (names then resolve through the identifier index), or fix the
    data at source.

??? warning "A `serverPOC` matches nobody"
    The value may be a team list or an old address. Add it to the right person under
    **Addresses this person answers to** (tick **Group address** for a list), or add contacts on the
    server directly. If the directory stores it in a non-schema attribute, list that attribute in
    `cert-alert.ldap.user.additional-email-attributes`.

??? warning "`Skipping the scheduled ... job: the previous run has not finished`"
    A run outlasted its interval. Normal occasionally on very large directories; if persistent,
    space the crons further apart or tune `page-size` / `batch-size` / `search-timeout`.

??? failure "No scheduled jobs run at all"
    `cert-alert.ldap.sync.enabled` is `false`. That switch disables the
    whole scheduler, including revocation and the round-up.

??? warning "*Use not known* on credentials, or missing revocation/risk data after an upgrade"
    These are read when a certificate is parsed. Rows cached by an earlier version lack them until
    a sweep re-reads them. Run **Sync directory**.

## Changelog connector

??? failure "Connector errors in a loop / always empty"
    - The directory may not publish `cn=changelog`, or it uses a different suffix (`base-dn`).
    - Reading `cn=changelog` is usually a **separate ACL grant**: an account that reads every entry
      can still see an empty changelog.
    - Check `GET /api/v1/changelog` for `lastError`.

??? warning "`changeNumber` comparisons look wrong"
    Directories without an integer ordering index on `changeNumber` may compare it as a string.
    CertAlert re-checks each change number against its cursor, so changes are not applied out of
    order, but index the attribute for efficiency.

??? info "A full sweep started on its own"
    Either the first start with no stored position (`full-sync-on-first-run`) or the changelog was
    trimmed past the stored position (`full-sync-on-gap`; counted in `gapsDetected`).

## Notifications and email

??? failure "Round-up email is not sent"
    - `cert-alert.notifications.email.enabled` must be `true` and `spring.mail.*` configured.
    - `cert-alert.notifications.email.dry-run` (`CERT_ALERT_EMAIL_DRY_RUN`) may be `true`.
    - `cert-alert.notifications.digest.enabled` may be `false`, or the scheduler is off.
    - Use **Dry run** on the Notifications page: it shows who would hear, the messages, any build
      failures in red, and a note explaining an empty result.

??? failure "Operator alert emails are not sent"
    Check `cert-alert.alerts.email.enabled: true`, `cert-alert.alerts.email.to` (an empty list logs
    *Email alerts are enabled but no recipients are configured*) and `spring.mail.*`. The audit trail
    records **Alert failed** per channel when a send throws.

??? warning "The round-up reports certificates beyond 30 days / not enough"
    The window is set on the Notifications page (stored in the database) and overrides
    `cert-alert.notifications.digest.window`. It is independent of `warning-threshold-days`, which
    controls the *Expiring soon* status.

??? warning "Log says `Expiry digest: more than N ...`"
    One of the round-up's reads stopped at `cert-alert.notifications.digest.scan-limit` (10,000 by
    default). If the message is about certificates that `expire in the next D day(s)`, the round-up
    reports the soonest N and the rest wait for a later run. If it is about `expired certificate(s)`,
    it reports the most recently expired and leaves out older ones. Raise
    `cert-alert.notifications.digest.scan-limit`, or remove expired certificates that are no longer
    needed from the directory. Until a run completes within the limit, round-ups that no longer apply
    are left in place.

??? info "A renewed certificate still appears"
    It drops out once the entry publishes a newer, unexpired certificate with the **same subject
    and the same use**. Check that the renewal was published to the directory and synced.

??? warning "Email is late or doesn't arrive; the log shows `Could not send the ... email`"
    The mail server refused it or did not answer. The message stays in the outbox and is tried
    again (a minute later, doubling up to an hour apart) for three days, then given up on with
    `Gave up on the ... email`. Check `SPRING_MAIL_*`, the timeouts and the server's reason in the
    log. `ens_certalert_mail_outbox{state="pending"}` shows how many are waiting.

??? failure "Custom template edits have no effect"
    Templates are read once and cached: restart. Files must be under an `email/` subdirectory of
    the configured directory.

## Revocation

??? warning "Many certificates are *Unknown*"
    No reachable CRL/OCSP for them. Configure `cert-alert.revocation.default-crl-url` (preferred)
    or `default-ocsp-url` for certificates that name none, and populate `issuer-directory` (OCSP
    needs issuer certificates). Check outbound access from CertAlert to the distribution points.

??? warning "A CRL or responder is refused: the log warns about a refused address"
    The fetch policy does not allow it:

    - link-local and cloud metadata addresses are never fetched from;
    - loopback (`localhost`, `127.0.0.1`, `::1`) only when named in
      `cert-alert.revocation.allowed-hosts`;
    - with `allowed-hosts` set, any host not in it;
    - an IPv4 range in `allowed-hosts` does not match a host that resolves to an IPv6 address.

    Add the host (or its range, in the right address family) to
    `CERT_ALERT_REVOCATION_ALLOWED_HOSTS`. The hosts of `default-ocsp-url` and `default-crl-url`
    are always allowed. The result is *Unknown* until the fetch succeeds.

??? info "Results say `(signature not verified)`"
    No issuer certificate was available to verify the CRL. Populate `issuer-directory`; set
    `allow-unverified-crl: false` to treat such results as *Unknown*.

## Endpoint probe

??? info "No Endpoint card on a server page"
    Shown only when `cert-alert.probe.enabled` is `true`, the entry has a `serverURL` or
    `icServerAddress`, and you may manage that server (point of contact, project administrator or
    admin).

## Directory writes

??? failure "Certificate clean-up or attribute edits are refused"
    The directory's access control has the last word. Grant the service account write access to
    the certificate attribute (clean-up) or to exactly the editable attributes. If CertAlert binds
    anonymously, edits are not possible.

??? info "Manual certificate clean-up did nothing"
    It is a no-op unless `cert-alert.ldap.certificate-cleanup.enabled` is `true`.
