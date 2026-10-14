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

??? failure "The image will not start with the `dev` profile"
    Expected. The embedded sample directory is a `developmentOnly` dependency and is not in the
    jar. Use `./gradlew bootRun --args='--spring.profiles.active=dev'` from source, or the Docker
    Compose demo.

??? failure "Compose: `UnknownHostException: directory`"
    The application and the stand-in directory are on different networks, typically because a
    directory container from an earlier run is still attached to an old network. Run
    `docker compose down` and bring the stack up again.

## Health

??? warning "`/actuator/health` is DOWN but the UI works"
    The aggregate includes the LDAP indicator and goes `DOWN` when the directory is unreachable,
    while cached data is still served. Use `/actuator/health/liveness` and `/readiness` for
    orchestrator probes. The image's built-in Docker `HEALTHCHECK` uses the aggregate, so under
    plain Docker the container shows *unhealthy* in this state. See
    [Operator guide → Health checks](operator-guide.md#health-checks).

??? warning "Container is unhealthy after enabling TLS"
    The image's `HEALTHCHECK` calls `http://localhost:${SERVER_PORT}/actuator/health` over plain
    HTTP. With `server.ssl.enabled: true` that request fails. Use your orchestrator's own probe
    with HTTPS.

## Signing in

??? failure "Nobody can trigger the first sync / no Admin menu"
    `cert-alert.security.admin-identifiers` is empty (the default) or does not match any identifier
    the directory holds for you. Add your address, uid or cn and restart. See
    [Security → Roles](security.md#roles).

??? failure "Client certificate is ignored; I always get the password form"
    - TLS must be enabled with a trust store containing the issuing CA and `client-auth: want`.
    - The certificate must be one the directory publishes **for a person**, and it must already be
      cached: run a sync first.
    - The log shows `Rejecting client certificate '...': the directory publishes no such certificate`
      when the fingerprint is unknown.
    - Server certificates never authenticate.

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
    `cert-alert.ldap.sync.enabled` is `false` (as in the `dev` profile). That switch disables the
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

??? info "A renewed certificate still appears"
    It drops out once the entry publishes a newer, unexpired certificate with the **same subject
    and the same use**. Check that the renewal was published to the directory and synced.

??? failure "Custom template edits have no effect"
    Templates are read once and cached: restart. Files must be under an `email/` subdirectory of
    the configured directory.

## Revocation

??? warning "Many certificates are *Unknown*"
    No reachable CRL/OCSP for them. Configure `cert-alert.revocation.default-crl-url` (preferred)
    or `default-ocsp-url` for certificates that name none, and populate `issuer-directory` (OCSP
    needs issuer certificates). Check outbound access from CertAlert to the distribution points.

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
