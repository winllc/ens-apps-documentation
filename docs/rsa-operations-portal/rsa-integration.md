# RSA Authentication Manager Integration

The **Connector** (`rsaapp-connector`) is the only component that talks to RSA Authentication Manager (AM). It uses the RSA AM SDK command API (`am-client.jar` 8.8 and its dependencies, in `rsaapp-connector/rsa-lib/`). The Portal calls the Connector through the Java interface `com.winllc.rsaapp.api.RSAAuthManagerServiceInterface`, sent over **Hessian** (binary HTTP remoting) on HTTPS.

```mermaid
flowchart LR
    P["Portal<br/>HessianProxyFactory<br/>(ConnectorTls)"] -- "HTTPS :8443, TLS 1.2/1.3<br/>Basic auth, mutual TLS<br/>/app/connector" --> H["Connector (Tomcat 9)<br/>AllowListHessianServiceExporter"]
    H --> S[RSAAuthManagerService]
    S --> C[RSAAuthManagerConnection]
    C -- "admin session: service account<br/>ims.httpinvoker.client.provider.url<br/>host name checked, replies filtered" --> AM1[(AM primary :7002)]
    C -- "read-only session: read-only account<br/>ims.httpinvoker.client.provider.readonly.url" --> AM2[(AM primary or replica :7002)]
    S -- "operator's own AM session<br/>(run as)" --> AM1
```

## Transport

| Hop | Protocol | Address | Configurable? |
|---|---|---|---|
| Portal → Connector | Hessian over HTTPS (TLS 1.2 or 1.3), with the Connector's certificate and host name checked, HTTP Basic credentials, and mutual TLS (on in new installations) | `https://connector:8443/app/connector` | Yes: `connector.url` (`CONNECTOR_URL`), or `connector.host` for the host name only |
| Connector → AM (write) | RSA `HttpInvokerCommandTarget`, basic auth with the Command Client credentials, TLS 1.3 or 1.2, with AM's certificate and host name checked and its replies read through a [filter](#what-the-connector-accepts) | `ims.httpinvoker.client.provider.url` | Yes |
| Connector → AM (read) | Same, on a separate target | `ims.httpinvoker.client.provider.readonly.url` | Yes (can point at a replica) |

The Connector offers AM TLS 1.3 and 1.2 (`-Dhttps.protocols=TLSv1.3,TLSv1.2` in its `JAVA_OPTS`), and the handshake picks the newest version both support. A `connector.env` from an older release holds `-Dhttps.protocols=TLSv1.2 -Djdk.tls.client.protocols=TLSv1.2`, which keeps the Connector at TLS 1.2; replace them to allow TLS 1.3.

**AM's host name.** AM's certificate must chain to the CA in `trust.jks` and name the host in the `ims.httpinvoker.*` URLs: a DNS name, or an IP address the certificate lists. The RSA SDK on its own checks only the CA, so any certificate that CA issued would pass; the Connector adds the name check, and completes the handshake before the socket is used, so a wrong certificate fails the connection at once with `TLS to Authentication Manager at <host>:<port> failed: ...`. Use the name in AM's certificate (usually its fully qualified name) in the URLs. Where that is impossible, `RSA_TLS_VERIFY_HOSTNAME=false` in `connector.env` (Helm: `connector.extraEnv`; or `-Drsa.tls.verify.hostname=false`) turns the name check off, and the Connector warns at every start; any other value than `true` or `false` stops it. `util/check-env.sh`, which `install.sh` and `upgrade.sh` run, warns about an `https` AM URL whose host is an IP address or a name without a domain, and so do the Helm chart's notes for `authManager.host`, `.readOnlyHost` or the URLs that override them.

The Connector's `springapp.xml` aliases `CommandAPIConnection` to the SDK's `CacheableCommandTargetBasicAuth`. The old RMI exporter on port 1199 is a commented-out block; neither the release package nor the Helm chart publishes that port.

## Portal to Connector

| Side | Setting | Default | Meaning |
|---|---|---|---|
| Portal | `connector.url` (`CONNECTOR_URL`) | `https://connector:8443/app/connector` | The Connector's address. A plain `http` URL logs a warning |
| Portal | `connector.tls.truststore` (`CONNECTOR_TLS_TRUSTSTORE`) | Java's default trust store | The CA certificates the Connector's certificate must chain to: PEM, or a PKCS12/JKS trust store. The release package's template sets `/ssl/connector-ca.crt` |
| Portal | `connector.tls.truststore-password` | — | Password of a PKCS12/JKS trust store |
| Portal | `connector.tls.client-certificate`, `connector.tls.client-key` (`CONNECTOR_TLS_CLIENTCERTIFICATE`, `CONNECTOR_TLS_CLIENTKEY`), `connector.tls.client-key-password` | unset; the release package's template sets `/portal-tls/portal-client.crt` and `.key` | The Portal's certificate and key (PEM; `CONNECTOR_TLS_CLIENTKEYPASSWORD` for an encrypted key), which a Connector with mutual TLS requires. Set both or neither. Keep a key of your own in `portal-tls/` too, not in `default_build`, which the Connector mounts |
| Portal | `connector.auth.user`, `CONNECTOR_AUTH_PASSWORD` (`connector.auth.password`) | `portal`, — | The HTTP Basic credentials the Portal sends. The same password goes in `connector.env` |
| Portal | `connector.connect-timeout-millis`, `connector.read-timeout-millis` | `5000`, `120000` | How long the Portal waits for the Connector |
| Portal | `connector.health-url`, `connector.health-timeout-millis` | the Connector's `/app/health`, `5000` | What the Portal's `connector` health indicator calls, with the same TLS settings |
| Connector | `CONNECTOR_TLS_CERTIFICATE`, `CONNECTOR_TLS_PRIVATE_KEY` | `/custom/connector-tls/tls.crt`, `/custom/connector-tls/tls.key` | The Connector's certificate (PEM, intermediate CAs after it) and key. Without them HTTPS starts with a throwaway self-signed certificate, which the Portal refuses |
| Connector | `CONNECTOR_TLS_PRIVATE_KEY_PASSWORD` | set by `util/connector-tls.sh` | Password of an encrypted key |
| Connector | `CONNECTOR_TLS_CLIENT_AUTH` | `none` when unset; the release package's template, and the Helm chart for a new installation, set `required` | `required`: mutual TLS. Anything else lets any client through to the shared secret, with a warning at every start |
| Connector | `CONNECTOR_TLS_CLIENT_CA` | `/custom/connector-tls/ca.crt` | The CA of the clients' certificates |
| Connector | `CONNECTOR_HTTPS_ADDRESS` | every interface | The address HTTPS on 8443 listens on. Unset: `::` (IPv6 and IPv4) where the container has an IPv6 address and `java.net.preferIPv4Stack` is not `true`, else `0.0.0.0`, as before it was a setting. `0.0.0.0` for IPv4 only, or one of the host's addresses |
| Connector | `CONNECTOR_HTTP_ADDRESS` | `127.0.0.1` | Plain HTTP on 8080, for the container's own health check. `0.0.0.0` opens it to the network unencrypted: the demo only |

**Release package.** `util/connector-tls.sh`, which `install.sh` and `upgrade.sh` run, makes what is missing and renews its own certificates when 60 days are left (`--renew` renews them now). It never touches certificates it did not make.

| File | What |
|---|---|
| `connector-tls-ca/ca.crt`, `ca.key` | A CA for this installation only (10 years). No container mounts it; keep the key private |
| `default_build/connector-tls/tls.crt`, `tls.key` | The Connector's certificate (5 years; names `connector`, `localhost` and `CONNECTOR_TLS_NAMES`) and its key, encrypted with `CONNECTOR_TLS_PRIVATE_KEY_PASSWORD` in `connector.env`. Only the Connector mounts this folder |
| `default_build/ssl/connector-ca.crt` | The CA certificate the Portal checks the Connector's against (`CONNECTOR_TLS_TRUSTSTORE=/ssl/connector-ca.crt` in `.env`) |
| `portal-tls/portal-client.crt`, `.key` | The Portal's certificate (5 years) and key, for mutual TLS. Only the Portal mounts this folder (`/portal-tls`): the Connector mounts all of `default_build`, so the key is not kept there. An earlier release kept them in `default_build/ssl`; the script moves them, and the settings in `.env` that name `/ssl/portal-client.*`. The key is mode 640 with group 0, which the Portal (uid 1001, group 0) reads through: the script sets that when run as root, otherwise `util/file-owners.sh` does |

To use certificates from your own PKI, set `CONNECTOR_TLS_CERTIFICATE` and `CONNECTOR_TLS_PRIVATE_KEY` in `connector.env` and `CONNECTOR_TLS_TRUSTSTORE` in `.env`; the script leaves files it did not make alone.

### Mutual TLS

With mutual TLS the Connector takes only clients with a certificate from its CA, so a client that reaches port 8443 without the Portal's certificate never gets as far as the shared secret.

**New installations** have it on. In the release package, `connector.env.template` sets `CONNECTOR_TLS_CLIENT_AUTH=required` and `env.template` sets `CONNECTOR_TLS_CLIENTCERTIFICATE=/portal-tls/portal-client.crt` and `CONNECTOR_TLS_CLIENTKEY=/portal-tls/portal-client.key`, which `util/connector-tls.sh` makes. `CONNECTOR_TLS_CLIENT_CA` names another CA for the clients' certificates.

**Upgrades** keep an installation as it was. An installation from before this default keeps mutual TLS off: `upgrade.sh` says so, `util/check-env.sh` warns, the Connector logs `Mutual TLS is off (CONNECTOR_TLS_CLIENT_AUTH=none) ...` at every start, and `upgrade.sh` does not list the three settings as new ones to add, because they go together on both sides. To turn it on, in the install folder:

```bash
./util/connector-tls.sh --mutual   # CONNECTOR_TLS_CLIENT_AUTH=required in connector.env, and the Portal's certificate in .env
./util/file-owners.sh              # lets the Portal (group 0) read its key
docker compose up -d               # recreates both containers with the new settings
```

`--mutual` adds the Portal's certificate and key to `.env` unless `.env` names them already (it stops if only one of the two is set), and sets `CONNECTOR_TLS_CLIENT_AUTH=required` in `connector.env`. Keep the two sides together: `util/check-env.sh` refuses a `connector.env` that requires a client certificate while `.env` has no `CONNECTOR_TLS_CLIENTCERTIFICATE` and `CONNECTOR_TLS_CLIENTKEY`, since the Connector would refuse the Portal. It also warns when a file those settings name under `/portal-tls/` or `/ssl/` does not exist, and when the key is under `/ssl/`. Without `--mutual`, the script never turns mutual TLS on or off; it only says when it is off.

With Helm, see `connector.tls.mutual` below.

**Helm.** The chart makes a CA and the certificates with Helm's `genCA` and `genSignedCert` in the Secret `<release>-rsa-operations-portal-connector-tls`, and keeps them across upgrades by reading the Secret back from the cluster. Or give your own in `connector.tls.existingSecret` (`tls.crt`, `tls.key`, `ca.crt`; a cert-manager Certificate's Secret, for example). Each pod mounts only its own key.

`connector.tls.mutual` is unset by default:

- a new installation (`helm install`, or `helm template` without `--is-upgrade`) gets mutual TLS;
- `helm upgrade` keeps what the release has. The chart records it in the annotation `rsa-operations-portal/connector-mutual-tls` (`"true"` or `"false"`) on its TLS Secret and its ConfigMap, and reads it back on an upgrade; for a release from before that record, it reads whether the release's Connector Deployment runs with `CONNECTOR_TLS_CLIENT_AUTH=required`. So a release from before this default stays as it was, with a warning in the notes and at every Connector start;
- an upgrade that finds neither (a name override changed, `helm template --is-upgrade`, no access to the cluster) stops and asks for `connector.tls.mutual`, rather than turn mutual TLS off;
- with `existingSecret` and no `clientSecret` there is no client certificate for the Portal, so mutual TLS stays off unless set to `true`, which then needs `clientSecret`.

`connector.tls.mutual=true` turns it on and `false` turns it off; the Portal and the Connector roll together.

| Value | Default | Meaning |
|---|---|---|
| `connector.tls.existingSecret` | `""` (the chart makes the CA) | Your own `tls.crt`, `tls.key` and `ca.crt` |
| `connector.tls.mutual` | unset: on for a new installation, kept as it was on an upgrade | Mutual TLS (above) |
| `connector.tls.clientSecret` | `""` | The Portal's certificate (`tls.crt`, `tls.key`) when you use `existingSecret` with mutual TLS |
| `connector.tls.validityDays` | `3650` | Validity of the CA and certificates the chart makes |
| `connector.service.port` | `8443` | The Connector Service's port (8080 before this release) |
| `networkPolicy.connector.enabled` | `true` | Only the Portal's pods may open a connection to the Connector, even when `networkPolicy.enabled` is `false`. Set `false` only on a cluster without NetworkPolicy support |
| `connector.extraEnv` | `[]` | The Connector's other settings: `RSA_TLS_VERIFY_HOSTNAME`, `CONNECTOR_SERIAL_FILTER_ALLOW`, `CONNECTOR_SERIAL_FILTER_LIMITS` and, in an emergency, `CONNECTOR_SERIAL_FILTER` (below) |

!!! note "Not verified on a real cluster"
    The chart's reuse of its generated certificates, and of its record of mutual TLS, across upgrades relies on Helm's `lookup`, which works with `helm install` and `helm upgrade` against the cluster. `helm template`, or a GitOps tool that renders without cluster access, makes new certificates on every render: use `connector.tls.existingSecret` there.

## What the Connector accepts

### From the Portal (Hessian)

`AllowListHessianServiceExporter` rebuilds only these classes, the parameter types of `RSAAuthManagerServiceInterface` and what their fields hold, then denies everything else (`*`). In Hessian's patterns `*` is any text, so `com.rsa.authn.data.*` also takes what is below that package. Strings, numbers, dates, lists and maps Hessian reads itself.

| Group | Classes |
|---|---|
| The API's request classes | `com.winllc.rsaapp.api.security.AppAuthenticationToken`, `com.winllc.rsaapp.api.security.RSALoginWrapper`, `com.winllc.rsaapp.api.security.UpdatePrincipalCommandWrapper` |
| RSA data classes | `com.rsa.admin.data.AttributeDTO`, `com.rsa.admin.data.AuthenticatorDTO`, `com.rsa.admin.data.ModificationDTO`, `com.rsa.admin.data.PrincipalDTO`, `com.rsa.admin.data.UpdatePrincipalDTO`, `com.rsa.authmgr.admin.attributemgt.data.AMAttributeValueDTO`, `com.rsa.authmgr.admin.attributemgt.data.AMTokenAttributeValueDTO`, `com.rsa.authmgr.admin.ctkip.data.CTKIPActivationCodeDTO`, `com.rsa.authmgr.admin.ctkip.data.CTKIPConnectionInfo`, `com.rsa.authmgr.admin.tokenmgt.data.ListTokenDTO`, `com.rsa.authmgr.admin.tokenmgt.data.TokenAttributeDTO`, `com.rsa.authmgr.admin.tokenmgt.data.TokenDTO`, `com.rsa.authn.data.*`, `com.rsa.rba.data.DeviceIdentityCookieDTO`, `com.rsa.rba.data.DeviceIdentityDTO`, `com.rsa.common.NameValuePair`, `com.rsa.common.search.*` |
| Java types | `java.lang.Boolean`, `java.lang.Integer`, `java.lang.Long`, `java.lang.String`, `java.sql.Timestamp`, `java.util.ArrayList`, `java.util.Date`, `java.util.HashSet` |

Any other class in a request arrives as a map of its fields, never as an instance of itself. Earlier releases allowed whole packages (`com.rsa.*`, `com.winllc.rsaapp.api.*`, `java.lang.*`, `java.util.*`, `java.math.*`) and let Hessian fall back to its own list, which allows nearly all of `java.*`.

### From Authentication Manager (Java serialization)

The RSA SDK reads AM's replies with Java serialization, which without a filter would rebuild any class on the Connector's class path. The Connector reads them through a JEP 290 deserialization filter, on those replies only. Built in, it allows:

| Group | Patterns |
|---|---|
| Spring's HTTP invoker reply | `org.springframework.remoting.support.RemoteInvocationResult` |
| The commands the Connector runs, which come back with their results, and their data classes | `com.rsa.admin.*`, `com.rsa.admin.data.*`, `com.rsa.admin.data.search.*`, `com.rsa.authmgr.admin.principalmgt.*`, `com.rsa.authmgr.admin.principalmgt.data.*`, `com.rsa.authmgr.admin.tokenmgt.*`, `com.rsa.authmgr.admin.tokenmgt.data.*`, `com.rsa.authmgr.admin.attributemgt.data.*`, `com.rsa.authmgr.admin.ctkip.data.*`, `com.rsa.authn.*`, `com.rsa.authn.data.*`, `com.rsa.rba.data.*`, `com.rsa.session.*`, `com.rsa.security.RSAPrincipal`, `com.rsa.ldapslotmgt.*`, `com.rsa.common.search.*`, `com.rsa.common.rba.*`, `com.rsa.batchjob.common.*`, `com.rsa.ims.common.DNGUID`, `com.rsa.ims.common.IMSGUID`, `com.rsa.ims.common.SQLGUID` |
| The command base class and the exceptions AM answers with | `com.rsa.command.TargetableCommand`, `com.rsa.command.AuditedLocalizableSystemException`, `com.rsa.command.CommandException`, `com.rsa.command.CommandJobException`, `com.rsa.command.CommandJobSystemException`, `com.rsa.command.CommandRejectedException`, `com.rsa.command.InvalidSessionException`, `com.rsa.command.MissingConnectionInfoException`, `com.rsa.command.SessionNotFoundException`, `com.rsa.command.exception.*`, `com.rsa.common.*` |
| Java types | `java.lang.*`, `java.io.Serializable` (as an array's element type), `java.lang.reflect.InvocationTargetException`, `java.lang.reflect.UndeclaredThrowableException`, `java.math.BigDecimal`, `java.math.BigInteger`, `java.net.InetAddress`, `java.net.Inet4Address`, `java.net.Inet6Address`, `java.security.cert.Certificate$CertificateRep`, `java.sql.Date`, `java.sql.Time`, `java.sql.Timestamp`, `java.util.Date`, `java.util.Locale`, `java.util.UUID` |
| Collections | `java.util.ArrayList`, `java.util.Arrays$ArrayList`, `java.util.Collections$Empty*`, `java.util.Collections$Singleton*`, `java.util.Collections$Synchronized*`, `java.util.Collections$Unmodifiable*`, `java.util.EnumMap`, `java.util.EnumSet$SerializationProxy`, `java.util.JumboEnumSet`, `java.util.RegularEnumSet`, `java.util.HashMap`, `java.util.HashSet`, `java.util.Hashtable`, `java.util.LinkedHashMap`, `java.util.LinkedHashSet`, `java.util.LinkedList`, `java.util.TreeMap`, `java.util.TreeSet`, `java.util.Vector`, `java.util.concurrent.ConcurrentHashMap`, `java.util.concurrent.ConcurrentHashMap$Segment`, `java.util.concurrent.locks.AbstractOwnableSynchronizer`, `java.util.concurrent.locks.AbstractQueuedSynchronizer`, `java.util.concurrent.locks.ReentrantLock`, `java.util.concurrent.locks.ReentrantLock$NonfairSync`, `java.util.concurrent.locks.ReentrantLock$Sync`, `java.util.Map$Entry` |

Primitive types and arrays of allowed classes pass too. A reply must also stay within these limits:

| Limit | Default | What |
|---|---|---|
| `maxdepth` | `32` | Nesting depth |
| `maxrefs` | `10000000` | Object references |
| `maxarray` | `1000000` | Array length |
| `maxbytes` | `268435456` (256 MB) | Size of the reply |

The SDK's replies against the mock AM went 11 deep at most; the limits allow a search of about 250,000 tokens at twice that.

Anything else is refused: the reply is not read, the command fails, and the Connector logs a warning starting `Refused a reply from Authentication Manager:` that names the class or the limit.

- For an RSA class (`com.rsa`) or a limit, the warning gives the setting that would let it through. Should a real AM send an RSA class the list does not have, add it to `CONNECTOR_SERIAL_FILTER_ALLOW`; a very large search can need a higher `CONNECTOR_SERIAL_FILTER_LIMITS`. Restart the Connector after either.
- Any other class is not one AM is known to send: the warning says the reply may be an attack and offers no pattern. Check that the AM URLs reach the real server, and ask RSA support before allowing it.

| Setting (`connector.env`; Helm `connector.extraEnv`) | Meaning |
|---|---|
| `CONNECTOR_SERIAL_FILTER_ALLOW` (or `-Dconnector.serial.filter.allow`) | More patterns, separated by commas: a class (`com.rsa.example.SomeDTO`), a package (`com.rsa.example.*`), a package and its subpackages (`com.rsa.example.**`) or a prefix (`com.rsa.example.Some*`) |
| `CONNECTOR_SERIAL_FILTER_LIMITS` (or `-Dconnector.serial.filter.limits`) | Other limits, `maxdepth=`, `maxrefs=`, `maxarray=` and `maxbytes=` separated by `;`, for the ones to change. Example: `maxrefs=20000000;maxbytes=536870912` |
| `CONNECTOR_SERIAL_FILTER` (or `-Dconnector.serial.filter`) | Emergency only: `off`, exactly, reads AM's replies without the filter, with a warning at every start. Any other value stops the Connector |

!!! warning "Test against a staging AM first"
    The list was built against the SDK and the mock AM, and a real AM may send more. Before
    upgrading production, run the new release against a staging AM: sign in, change a user, and
    let the scheduled jobs run. Watch the Connector's log (`docker compose logs connector`, or
    `kubectl logs` on the Connector Deployment) for `Refused a reply from Authentication Manager`.
    If production refuses a reply all the same, `CONNECTOR_SERIAL_FILTER=off` in `connector.env`
    (Helm: `connector.extraEnv`) reads the replies without the filter. Use it only until the class
    from the refusal warning is in `CONNECTOR_SERIAL_FILTER_ALLOW`, then remove it.

## Sessions

`RSAAuthManagerConnection` keeps two AM `ClientSession`s:

| Session | Account | Used for |
|---|---|---|
| **Admin** | The service account, `rsa.admin.user.id` / `RSA_ADMIN_USER_ID` | Changes no signed-in operator makes |
| **Read-only** | `rsa.readonly.user.id` / `RSA_READONLY_USER_ID`. Unset: the service account, and the Connector logs a warning | Searches and lookups |

1. At startup the Connector connects both sessions. If AM can't be reached, it still starts, logs the error and connects on the next request.
2. On (re)connect it runs `SearchRealmsCommand` for `rsa.authmanager.realm.name` and `SearchSecurityDomainCommand` for `rsa.authmanager.securityDomain.name`, and caches the security domain and the realm's identity sources. A missing realm or domain fails the session check (`RsaSessionCheckFailedException`).
3. A session AM has confirmed is used for 60 seconds without asking again. If AM drops it sooner, the failing command is run once more on a new session.
4. On shutdown the Connector logs out both sessions.

## Who runs each command

| Runs as | Commands |
|---|---|
| **The signed-in operator** (Trusted Agent, Help Desk, PMO), in the operator's own AM session (`executeCommandAs`) | Assigning and unassigning tokens (assign, lost token, deprovision), taking back a user's expired tokens before assigning a new one, PIN resets by an agent, emergency access codes, enabling and unlocking accounts, granting and revoking administrative roles, clearing cached Windows passwords. AM applies the operator's own permissions and scope, and its audit log names the operator. PIN resets, emergency codes, lost and deprovisioned assigned tokens and cached-password clearing run only through a [help-desk request](help-desk.md) |
| **The service account** (admin session) | Users acting on their own token: setting the PIN of a new token, a PIN reset with a certificate, a Windows password or a reset code, swapping to a replacement token, checking a tokencode (including the help desk's tokencode verification). The scheduled jobs, such as re-syncing Trusted Agents' roles |
| **The read-only account** (read-only session) | Searches and lookups: principals, tokens, identity sources, administrative-role membership |
| No session | Sign-in (`LoginCommand`) and the tokencode checks of a sign-in |

Every command that changes AM, whoever runs it, is recorded as `am.change-attempt` before it is sent and `am.change-outcome` after, with `details.amAccount` saying which session made it (`operator` or `service-account`). If the attempt can't be recorded, the command is not sent. See [Security → Other controls](security.md#other-controls).

The Portal passes the operator's AM session ID and principal GUID (`AppAuthenticationToken`) with each operator command. If that AM session has ended, the Connector throws `RsaUserSessionEndedException`, and the Portal signs the user out and redirects to `/login`.

!!! warning "Upgrade the Portal and the Connector together"
    The role grant and revoke calls and `clearCachedWindowsPassword` take the operator's token since the release that moved them to the operator's session. A Portal and a Connector from different releases can't talk to each other. The release package and the Helm chart always run matching versions.

## Operations used by the Portal

| Portal feature | Interface method | AM SDK command(s) |
|---|---|---|
| Sign-in (SecurID) and multi-step prompts | `login`, `loginAttempt` | `LoginCommand` (in-progress logins are cached for 4 minutes) |
| Sign-out | `logout` | `LogoutCommand` |
| Test My Token | `testTokenLogin` | `LoginCommand` + `LogoutCommand` |
| Verify tokencode (PIN-check reset link) | `verifyTokencodeOfToken` | `ValidateFirstTokencodeForResyncTokenCommand` (service account; no PIN, and not a logon) |
| Windows-password PIN reset | `loginWithPassword` | `LoginCommand` (password) |
| Enrollment step 1 | `verifyTokenCodeDuringRegistration` | `LoginCommand` |
| Enrollment step 2 (set PIN) | `updateTokenPin` | `LookupTokenCommand`, `UpdateTokenCommand` |
| User search | `searchUnregisteredUsers`, `lookupUser`, `lookupUserByLastname`, `retrieveUsersByRsaId` | `SearchPrincipalsCommand` (limit 25 per identity source, sub-domains included) |
| Role mapping | `getAdminRoleGuidsForUser`, `assignAdminRole`, `removeAdminRole`, `getAllUsersWithRole` | `GetPrincipalAdminRolesCommand`, `LinkAdminRolesPrincipalsCommand`, `UnlinkAdminRolesPrincipalsCommand`, `GetAdminRolePrincipalsCommand` |
| Assign token | `distributeTokenToUser` | `LookupTokenCommand`, `LinkTokensWithPrincipalCommand` (refused if the token is already assigned) |
| Lost token / unassign | `unAssignUserToken` | `LookupTokenCommand`, `UnlinkTokensFromPrincipalsCommand` |
| Deprovision | `resetTokenToAssignableState` | `UnlinkTokensFromPrincipalsCommand` |
| Reset PIN (agent or user) | `resetTokenPin`, `resetTokenPinByUser` | `UpdateTokenCommand` (puts the token in new-PIN mode) |
| Emergency access code | `generateEmergencyTokenCode` | `GenerateEmergencyAccessCodeCommand`, then `UpdateTokenEmergencyAccessCommand` (temporary fixed tokencode, token flagged lost, expires after `token.emergencyCode.validityInDays`) |
| Unlock account | `setUserAccountLockoutStatus` | `UpdatePrincipalCommand` (lockout flag) |
| Clear cached Windows password | `clearCachedWindowsPassword` | `LookupAMPrincipalCommand`, `UpdateAMPrincipalCommand` |
| Shipments: validate serial range | `checkValidRange`, `findTokensInSerialRange` | `SearchTokensCommand` |
| Jobs: expiring tokens, inactive tokens | `findTokensExpiringWithinDays`, `findTokensThatHaveNotLoggedInWithinMinutes`, `findUsersWithTokens` | `SearchTokensCommand`, `SearchPrincipalsCommand` |
| Health | `testConnection` | Admin session check |

## Health

* **Connector:** `GET` or `HEAD /app/health` needs no credentials. It answers 200 `UP` when the Connector has an AM admin session or can open one, and 503 `DOWN` otherwise. The result is kept for 30 seconds, so probes don't reach AM on every call; the reason for `DOWN` goes to the Connector's log only. The compose files and the podman unit check `curl http://localhost:8080/app/health` inside the container; the Helm chart's readiness probe uses it too, and its liveness and startup probes check the HTTPS port.
* **Portal:** the health indicator named `connector` calls the Connector's health endpoint over the Portal's TLS settings, and is part of the Portal's readiness group with the database (`/actuator/health/readiness`).

## Token serial prefix

AM stores token serials as 12 digits, while labels are often read without the leading zeros. Both the Connector and the Portal prepend **`token.serial.prefix`** (default `000`) to any serial that does not already start with it (`normalizeTokenSerial`, `lookupTokenBySerial`). Set the same value on both sides.

When a Trusted Agent assigns several tokens in a row, the **Assign Token** page notices that recent serials share a prefix (all but the last 3 digits) and pre-fills it, so the agent types only the last 3 digits.

## Administrative role GUIDs

The Portal has no role store of its own. A signed-in user's roles are computed on each login:

```mermaid
flowchart LR
    A[User signs in] --> B[GetPrincipalAdminRolesCommand]
    B --> C{GUID matches<br/>role.mapped.N.rsaSecurityGuid?}
    C -- yes --> D[Grant role.mapped.N.name<br/>TRUSTED_AGENT / PMO / HELP_DESK]
    C -- none --> E[Grant USER]
```

Granting a role in the Portal (**Admin → Manage User Roles**, or Trusted Agent registration) **links the user to that AM administrative role** in the PMO user's own AM session. Revoking a role unlinks it. The re-sync job runs as the service account.

## Least-privilege AM roles

The Connector's accounts no longer need Super Admin. Create these custom administrative roles in the AM Security Console and assign them to internal AM users.

!!! warning "Check the permission names in your Security Console"
    These definitions come from the commands the code runs. The checkbox labels below have **not** been checked against an AM Security Console. Verify each one against the AM 8.8 Security Console (or your AM release) when you create the roles, and test every Portal function with the new accounts before you remove Super Admin from the old service account.

**Service account role** (for `RSA_ADMIN_USER_ID`). Scope: the security domain the Portal manages (`rsa.authmanager.securityDomain.name`) and its identity sources.

| Area | Permissions |
|---|---|
| Users | View |
| Realms, security domains, sessions | View |
| Administrative roles | View; assign and unassign **the Trusted Agent administrative role only** (for the re-sync job) |
| SecurID tokens | View; edit (set PIN, require new PIN); replace; resynchronize (for the tokencode check); unassign |
| Not needed | Adding or deleting users, assigning tokens, emergency access, enabling or unlocking accounts, Windows credentials, system settings |

**Read-only role** (for `RSA_READONLY_USER_ID`, recommended). Same scope. View only: users and AM principal attributes, SecurID tokens, administrative-role membership, realms, security domains and sessions.

**Operators' roles.** Trusted Agent, Help Desk and PMO users' changes run in their own AM sessions, so the AM administrative roles you map in `role.mapped.N.rsaSecurityGuid` need the AM permissions for what each does in the Portal ([feature matrix](security.md#feature-access-matrix)), within the managed security domain. Since operators' role changes, cached-Windows-password clearing and emergency codes moved to their own sessions, these roles also need:

| Portal role | Additional AM permission |
|---|---|
| PMO | Assign and unassign administrative roles (the Trusted Agent, PMO and Help Desk roles) |
| Trusted Agent, Help Desk | Clear cached Windows credentials |
| Trusted Agent, Help Desk | Emergency access (generate emergency access codes) |

## Compatibility

What is known about which Authentication Manager the Connector works with:

| | Status |
|---|---|
| RSA AM SDK in the Connector | `am-client` 8.8, the jars in `rsaapp-connector/rsa-lib`, on Java 8 bytecode and Spring 3.2 |
| AM API used | The AM 8.x command API: HttpInvoker `CommandServer` on port 7002 (`/ims-ws/httpinvoker/CommandServer`) |
| Automated tests | Run against the **mock AM** (`rsaapp-mock-authmanager`): an in-memory stand-in built on the RSA client API that serves the same HttpInvoker endpoint. The Connector's own tests, the image test and the Portal-to-Connector Hessian test use it. Commands the mock does not implement fail with `MOCK_UNSUPPORTED_COMMAND` |
| A real AM | **Not part of the automated tests.** ENS has no recorded test of this release against a particular AM version |
| TLS 1.3 to AM | Offered, **untested against a real AM**. TLS 1.2 is offered too, so an AM without 1.3 still connects |
| The deserialization filter on AM's replies | Built and tested against the SDK and the mock AM, **not against a real AM**: test against a staging AM first ([above](#from-authentication-manager-java-serialization)) |
| The least-privilege roles above | **Untested against a real AM**; see the warning |
| AM releases other than 8.x, and RSA's cloud services | **Not supported**: the Connector speaks only the on-premises AM command API |

## AM-side prerequisites checklist

- [ ] Root CA exported and imported into `trust.jks` with alias `rsa_am_ca` ([Installation](install.md#export-the-am-root-certificate-and-build-trustjks))
- [ ] The AM URLs use a host name AM's certificate names, usually its fully qualified name, not an IP address or a short name ([AM's host name](#transport))
- [ ] Command Client user and password from `rsautil manage-secrets --action list`
- [ ] An internal AM user with the [service account role](#least-privilege-am-roles) for `RSA_ADMIN_USER_ID`
- [ ] Recommended: an internal AM user with the [read-only role](#least-privilege-am-roles) for `RSA_READONLY_USER_ID`
- [ ] Realm and security domain names (`rsa.authmanager.realm.name`, `rsa.authmanager.securityDomain.name`), both `SystemDomain` by default
- [ ] Three administrative roles (Trusted Agent, PMO, Help Desk) with the permissions their holders need, including the [additional ones](#least-privilege-am-roles), and their `ims.…` GUIDs
- [ ] TCP 7002 open from the Connector to the primary (and to the replica if it is used for reads)

## Trust store renewal

When the AM root CA changes, rebuild `default_build/ssl/trust.jks` and restart the Connector. See [Operator Guide → Certificate renewal](operator-guide.md#certificate-renewal).
