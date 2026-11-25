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
    or more), `x509.trustedCa` and, from 0.0.4, `x509.trustedProxies`. Add the named value to your
    values file and run the command again.

??? failure "Helm: `x509.source=header needs x509.trustedProxies` after upgrading"
    From 0.0.4 the chart's `x509.trustedProxies` is empty by default; before, it was every private
    range. A header-mode release that relied on the default no longer renders. Set
    `x509.trustedProxies` to the ingress controller's pod network (CIDRs, for example
    `10.42.0.0/16`) and run `helm upgrade` again. See
    [Install → Upgrade](install.md#6-upgrade).

??? failure "Startup fails: `ENS_X509_SOURCE=header needs ...` or `cannot be combined with SERVER_FORWARD_HEADERS_STRATEGY=native`"
    Header mode needs a proxy secret of at least 32 characters (`ENS_X509_PROXY_SECRET_FILE` or
    `ENS_X509_PROXY_SECRET`) and the CA file (`ENS_X509_TRUSTED_CA_FILE`), and cannot be used with
    the `native` forwarded-headers strategy. Supply them, and use
    `SERVER_FORWARD_HEADERS_STRATEGY=framework`. See
    [Configuration → Client certificates](configuration.md#client-certificates-ensx509).

??? failure "Startup fails reading the consent banner file"
    `ENS_CONSENT_BANNER_FILE` names a file CertAlert can't read. In the release package, put it in
    `tls/` and name it as `/etc/cert-alert/tls/<file>`; with Helm, mount it with `extraVolumes`.

??? failure "`install.sh` refuses to start: plain HTTP to other hosts (from 0.0.3)"
    TLS is off and `CERT_ALERT_BIND_ADDRESS` is not `127.0.0.1`, and `.env` says
    `CERT_ALERT_ALLOW_PLAIN_HTTP=false` (as every new `.env` from 0.0.3 does). Turn TLS
    on, bind to `127.0.0.1` behind your own TLS-terminating proxy, or, for a lab, set
    `CERT_ALERT_ALLOW_PLAIN_HTTP=true`. See [Install → Choose where it listens](install.md#4-choose-where-it-listens).

??? failure "Startup stops: `cert-alert.security.enabled=false` ... allowed only with the 'dev' profile (from 0.0.3)"
    `cert-alert.security.enabled=false` leaves every page and API open to anybody, and from 0.0.3
    it is refused outside the `dev` profile. Remove the setting, or set it to `true`.

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

??? failure "Single sign-on is refused: the provider left your groups out (`sso_error=groups`, from 0.0.3)"
    The person is in more groups than Entra ID puts in a token (group overage), so the roles their
    groups grant cannot be known and the sign-in is refused, recorded as a failed `auth.login`. In
    the app registration's **Token configuration**, send only the groups assigned to the
    application (`groupMembershipClaims: ApplicationGroup`). See
    [Single sign-on → Microsoft Entra ID](single-sign-on.md#microsoft-entra-id-oidc).

??? warning "Signing out with SAML stays local although `single-logout` is on (from 0.0.3)"
    The startup log says why. SAML single logout needs the service provider's signing key and
    certificate (`CERT_ALERT_SSO_SAML_SIGNING_KEY_LOCATION` and `_CERTIFICATE_LOCATION`, Helm
    `sso.saml.signingSecret`) and identity provider metadata that names a `SingleLogoutService`.
    Once both are there, give the provider the service provider metadata again so it knows
    `<public-url>/logout/saml2/slo`. See [SAML single logout](single-sign-on.md#saml-single-logout).

??? failure "SAML sign-ins fail after the identity provider changed its signing certificate"
    Before 0.0.4 the provider's metadata is read once, at the first sign-in after a start: restart
    CertAlert. From 0.0.4 it is read again every `cert-alert.sso.saml.metadata-refresh` (an hour),
    and at once, at most every five minutes, when a response's signature does not verify, so the
    next sign-in after that works. If the log shows `Could not read the SAML identity provider's
    metadata from ... again`, CertAlert cannot reach the metadata address and is still using the
    old copy: restore the route, or with a `file:` location replace the file. A
    `metadata-refresh` of `0` turns the re-reading off.

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

??? failure "Client certificate refused: `certificate published by more than one person`"
    From 0.0.4. Two or more people's entries publish the certificate presented (a regular and a
    privileged account carrying one card, a shared mailbox certificate), so it cannot say which of
    them is signing in. The log shows `Refusing client certificate '...': N different directory
    entries publish it`, and the audit trail a failed `auth.login` with that reason and the
    certificate's fingerprint. Earlier releases signed in whichever entry the database returned
    first, which could change between requests. Publish the certificate on one person's entry
    only (find the entries by the fingerprint on the **Certificates** page), or have those people
    sign in with the password form or single sign-on.

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
    - `revocation unknown`: no OCSP or CRL answer. From 0.0.4 such an answer is reused for 30
      seconds, then a minute, then two minutes while the certificate stays unanswered, so allow up
      to two minutes after fixing the cause. Check that CertAlert can reach the responder or
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

??? failure "An API token answers 401: *The person who created this API token no longer holds a role its scopes need*"
    The creator no longer has the role in the directory (`admin-identifiers`,
    `auditor-identifiers` or a group in `security.groups`; an identity provider's groups do not
    count), or the token was narrowed at one of their sign-ins. Before 0.0.4 a sign-in during
    which the directory could not return the creator's groups narrowed every token they had
    created, for good. From 0.0.4 a sign-in narrows tokens only to the roles the live directory
    gives the creator, and never when it cannot be read. A token is never widened again: create a
    new one and revoke the old.

??? warning "An API token answers 401: *The directory cannot be read to check this API token's creator; try again*"
    Every use of a token reads its creator's entry from the directory. From 0.0.4 a directory that
    cannot be read is asked again after 10 seconds, so the token works again within seconds of the
    directory answering.

??? info "*Your session has ended. Sign in again to carry on.*"
    The session reached its idle or absolute limit, or the person signed in elsewhere beyond
    `ENS_SESSION_MAX_PER_USER`. The audit trail has `auth.session-expired` with the reason. See
    [Configuration → Security headers, banner and sessions](configuration.md#security-headers-banner-and-sessions).

??? info "The consent page appears again after signing out"
    By design: the agreement is kept in the session, so a new session asks again.

??? failure "**I agree** on the consent page answers 429"
    From 0.0.4 one client address may agree at most 10 times a minute. If ordinary users see this,
    CertAlert is seeing one address for all of them: a proxy in front that is not in
    `ENS_X509_TRUSTED_PROXIES`, or `SERVER_FORWARD_HEADERS_STRATEGY=framework` not set behind it.
    Set both, and try again a minute later. See
    [Configuration → The client's address behind a proxy](configuration.md#the-clients-address-behind-a-proxy).

??? warning "The audit trail shows the proxy's address for every request"
    From 0.0.4, with `SERVER_FORWARD_HEADERS_STRATEGY=framework`, `X-Forwarded-For` is followed only
    through the proxies listed in `ENS_X509_TRUSTED_PROXIES`, which is loopback by default (and,
    from 0.0.4, in the Helm chart too). Set it to the proxy's own addresses: the compose
    network's subnet for the release package, the ingress controller's or router's pod network
    (`x509.trustedProxies`) with Helm. Without `SERVER_FORWARD_HEADERS_STRATEGY=framework` the
    header is not read at all. See
    [Configuration → The client's address behind a proxy](configuration.md#the-clients-address-behind-a-proxy).

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

??? warning "`Skipping the scheduled ... job: it is running, or has just run, on this replica or another`"
    A run outlasted its interval, or another replica took this firing. Normal occasionally on very
    large directories; if persistent, space the crons further apart or tune `page-size` /
    `batch-size` / `search-timeout`.

??? failure "A sweep ends `FAILED` with `SizeLimitExceededException`"
    From 0.0.4. The directory cut the search short at its size limit, and CertAlert no longer
    treats the part it got as the whole directory (earlier releases ended such a sweep as a
    success, never alerted on anybody past the limit, and with prune on deleted them later).
    Paging does not help where the server counts the limit across all the pages of a search (389
    Directory Server has `nsslapd-pagedsizelimit` and OpenLDAP `size.prtotal` for this; check your
    server's documentation). Raise or lift the size limit for CertAlert's service account in the directory,
    then run **Sync directory**. `cert-alert.ldap.count-limit` above `0` is the one case where
    stopping at a limit is not an error.

??? warning "A sweep ends `COMPLETED_WITH_ERRORS`; the log shows `Failed to write ... entry '<dn>'`"
    That entry could not be stored and was skipped; from 0.0.4 its neighbours in the batch are
    written (the run's `errors` counts entries). The log line carries the database's reason.
    While a sweep of a kind keeps ending with errors, the prune leaves that kind in place.

??? warning "Prune deleted nothing: the run is `COMPLETED_WITH_ERRORS`, the log shows `Prune: left ... in place`"
    From 0.0.4 a prune deletes the unseen people, or the unseen servers, only when a sweep of that
    kind has finished `COMPLETED` (no errors) since they were last seen, and only when they are at
    most `cert-alert.ldap.prune.max-share` (10%) of that kind. The log line says which applies.
    Get the sweep to complete cleanly first. If the directory really has lost more than a tenth of
    its people or servers, raise `max-share` (`CERT_ALERT_LDAP_PRUNE_MAX_SHARE`, for example
    `0.5`) for one run and set it back. See
    [Operator guide → What a prune will not delete](operator-guide.md#what-a-prune-will-not-delete).

??? failure "`GET /api/v1/sync/runs` or a preview answers 403"
    From 0.0.4 the run log and the prune and certificate clean-up previews need an administrator
    or auditor (an API token: `jobs:read` for the runs, `admin` for the previews). A run's
    message can carry the directory's own error text.

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
    and the same use**. Check that the renewal was published to the directory and synced. From
    0.0.4 a person's expired signing or encryption certificate that was **not** renewed stays in
    the round-up although the other half of the pair is current.

??? warning "The round-up says *Stopped at the 500 message cap for one run*"
    More messages were due than `cert-alert.notifications.email.max-per-run` allows. From 0.0.4
    the soonest expiries are mailed first and the longest-expired wait; the note gives the number
    held back, and everybody is still told on the Notifications page. Raise `max-per-run`, or
    remove long-expired certificates nobody needs from the directory.

??? info "Two alerts arrived about the same certificate"
    From 0.0.4 a certificate is alerted on when it comes into the warning window (`WARNING`) and
    once more when it comes inside `cert-alert.critical-threshold-days` (`CRITICAL`, 7 days by
    default), on every alert channel, as a notification and as a `certificate.expiring` webhook.

??? info "Somebody gets one round-up email for several entries"
    From 0.0.4 the round-up sends one message per address. Where several entries publish the same
    address, their certificates are in the one message. Each entry still has its own round-up on
    the Notifications page.

??? warning "Email is late or doesn't arrive; the log shows `Could not send the ... email`"
    The mail server refused it or did not answer. The message stays in the outbox and is tried
    again (a minute later, doubling up to an hour apart) for three days, then given up on with
    `Gave up on the ... email`. Check `SPRING_MAIL_*`, the timeouts and the server's reason in the
    log. `ens_certalert_mail_outbox{state="pending"}` shows how many are waiting.

??? failure "Custom template edits have no effect"
    Templates are read once and cached: restart. Files must be under an `email/` subdirectory of
    the configured directory.

??? failure "Webhook deliveries fail through the egress proxy (from 0.0.3)"
    - The proxy answers `CONNECT` with something other than 2xx: a 407 means it wants credentials
      (`CERT_ALERT_WEBHOOKS_PROXY_USERNAME` with `_PASSWORD_FILE` or `_PASSWORD`); a 403 usually
      means its rules allow names only. CertAlert asks for a tunnel to the receiver's **address**,
      never its name, so allow the receivers' addresses (Squid `dst`, not `dstdomain`).
    - CertAlert still resolves the receiver's name itself: the host or pod needs DNS for it, even
      behind the proxy.
    - With the Helm chart's NetworkPolicy on, the pod must reach the proxy
      (`networkPolicy.egress.extra`).
    See [Webhooks → Through an egress proxy](webhooks.md#through-an-egress-proxy).

??? info "A webhook's deliveries end with *The webhook is disabled*"
    From 0.0.4, disabling a webhook gives up on what was still queued for it. Enable it again for
    new events; what was given up on is not sent later.

## Revocation

??? warning "Many certificates are *Unknown*"
    No reachable CRL/OCSP for them. Configure `cert-alert.revocation.default-crl-url` (preferred)
    or `default-ocsp-url` for certificates that name none, and populate `issuer-directory` (OCSP
    needs issuer certificates). Check outbound access from CertAlert to the distribution points.

    From 0.0.4 the certificate's revocation detail also says when a list was fetched but does not
    count:

    - `... is out of date: its next update was due ...`: the authority is serving a CRL past its
      `nextUpdate`. Certificates on it are still *Revoked*; the rest are *Unknown* until the
      authority publishes a current list;
    - `The CRL from ... is issued by ..., not by the certificate's issuer`: with no issuer
      certificates configured, the list (often `default-crl-url`) belongs to another authority;
    - `The CRL from ... is not signed by the certificate's issuer`: an issuer certificate is in
      `issuer-directory` and the list's signature does not verify against it.

    A distribution point that fails, or serves an out-of-date list, is asked again after two
    minutes.

??? info "A revoked certificate's detail says *Still revoked; the last check got no answer*"
    From 0.0.4 a check that gets no answer does not undo a revocation already found. The
    certificate stays *Revoked* with its date and reason until an authority says otherwise.

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

## License

??? failure "*That license was not installed. This license starts on …*"
    From 0.0.4 a license that is not yet valid, or has already expired, is not installed while the
    installed license is in full effect, so an early upload of next term's license no longer
    leaves CertAlert unlicensed until its start date. The installed license stays in force, and
    the refusal is recorded as `license.rejected`. Upload the new license on or after its start
    date.

??? warning "The license message says this server's clock is behind"
    From 0.0.4 CertAlert records the latest time it has seen, about once an hour. When the host's
    clock is more than a day behind that, licenses are checked as of the recorded time less a
    day, so setting the clock back does not bring an expired license back. Correct the host's
    clock (NTP); ordinary corrections of less than a day change nothing.

## Audit trail

??? failure "**Verify integrity** reports events missing after upgrading"
    From 0.0.4 verification checks both ends of the trail: the oldest retained event must be seq 1
    or the one right after the last event retention pruned, and the newest must be the chain
    head. Messages such as `seq N to M are missing; retention has pruned nothing` or
    `seq N to M are missing (the chain head is at seq M)` mean events were removed from
    `audit_event` by something other than CertAlert's retention (rows deleted by hand, a table
    restored without `audit_chain_head`). Earlier releases reported such a trail as intact.
    Compare with the copies in your SIEM. A restore must bring back `audit_event` and
    `audit_chain_head` together, from the same backup. See [Audit logging](../audit-logging.md).

??? info "Two `access.denied` events for one burst of refusals"
    From 0.0.4 the first refusal is recorded at once (`details.count` 1). Identical refusals in
    the next 60 seconds are recorded as one more event, with their number in `details.count` and
    `details.collapsed` set.
