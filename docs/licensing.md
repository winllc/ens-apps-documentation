# Licensing

Every application in the suite needs a license file from ENS Solutions. Licenses are issued through
the **ENS licensing portal**:
- customers download free evaluations and the licenses they have bought;
- ENS staff issue, renew and revoke licenses against orders.

Each application checks its license offline, against public keys built into it, so licensing works the
same on an air-gapped network.

!!! note "syncd"
    syncd (from 0.5.0) also takes its license from the portal, and the portal's account pages work
    the same for it, but the file is syncd's own format and syncd applies its own rules: it is
    sized by managed identities, sells connectors and features as packs, and never stops running
    sync, only publishing configuration changes. Its [Licensing](syncd/licensing.md) page covers
    installing and enforcement; from this page only [Getting a license](#getting-a-license) and
    [For ENS: keys and issuing](#for-ens-keys-and-issuing) apply to it.

## Getting a license

1. **Create an account** on the ENS licensing portal (`/account`) and confirm your email address.
2. **Evaluate**: download a free 30-day evaluation license for any product, one per product. An
   evaluation covers the second size band, so a trial pointed at a real directory sees all of it,
   unlocks every feature, and shows an evaluation banner. It can be extended once, by 30 days,
   from its license page.
3. **Buy**: licenses for your order (pilot, subscription or perpetual) are issued into the same
   account by ENS. Download them from **Account → Licenses**.
4. **Bind (optional)**: a license can be tied to one installation by entering that installation's id,
   shown on the application's license screen. A bound file is refused by every other installation.

## Installing a license

The simplest way is the application's **license screen**: an administrator signs in, opens it, and
uploads the `.lic` file. It shows whether the file was accepted and why not if it was refused.
The license takes effect immediately; no restart is needed.

| Application | License screen | Who can open it |
|---|---|---|
| CertAlert | `/admin/license` | Administrators |
| Directory Services Portal | `/admin/license` | Administrators |
| ReadyRoom | `/app/admin/license` (**License** in the admin menu) | Administrators |
| RSA Operations Portal | `/admin/license` | PMO |

While an application is unlicensed, administrators are taken to the license screen as soon as they
sign in. Everyone else sees a page asking them to contact their administrator.

### When an upload is refused

A refused file is never installed: the license that was installed stays in force, the screen gives
the reason, and the attempt is recorded in the audit trail as `license.rejected`.

| The file is | What the screen says |
|---|---|
| Not a genuine ENS license (damaged, edited, or signed by a key the application does not trust) | That it isn't a valid ENS license, and why |
| For another product | "This is a license for …, not …." |
| Bound to another installation | "This license is bound to installation …; this one is …." |
| An older revision of a license this installation has already accepted | "A newer revision (…) of this license has been installed here; install that one." |
| Not yet valid, while the installed license is in full effect | "This license starts on *date*; the license installed now is still in force. Upload this one on or after that day." |
| Expired past its grace period, while the installed license is in full effect | "This license expired on *date*; the license installed now is still in force." |

The last two rows are new in CertAlert 0.0.4, the Directory Services Portal 0.0.9, ReadyRoom 0.0.11
and the RSA Operations Portal 0.0.3. (CertAlert 0.0.4 has no download; its first release package
with these changes is 0.0.5: see
[Releases and downloads](releases.md#versions-without-a-download).) Earlier releases installed such a file over a working license:
next term's license uploaded before its start date left the application unlicensed until that date,
and an old expired file made it read-only. **Upload next term's license on or after its start
date.** The Directory Services Portal's upload API answers such a file with
`"state": "NOT_IN_EFFECT"`.

"In full effect" means valid, or expired but still within its grace period. When no license is in
full effect (none installed, read-only or unlicensed), a file that is not yet valid or already
expired is installed as before, and the application is then in that file's state.

ReadyRoom, the Directory Services Portal and, from 0.0.3, the RSA Operations Portal also refuse an
upload while the audit trail cannot be written ("The license was not installed: the audit log
cannot be written." in the RSA Operations Portal), so that no license change goes unrecorded.
Upload again once the audit trail can be written.

### Hiding the license screen's menu entry

`ENS_LICENSE_MENU_VISIBLE=false` (Helm: `license.menuVisible: false`) takes the license screen out
of the navigation, for installations where operators should not be pointed at it (CertAlert 0.0.3,
the Directory Services Portal 0.0.4, ReadyRoom 0.0.3 and the RSA Operations Portal 0.0.2, and
later). The default is `true`, so nothing changes unless you set it. A value that is not a boolean
stops the application at startup.

| Application | What `false` hides |
|---|---|
| CertAlert | The **Manage the license** link on the administration page (the navigation has no license entry) |
| Directory Services Portal | **License** in the administrators' sidebar |
| ReadyRoom | **License** in the Administration menu, while the license is in full effect |
| RSA Operations Portal | **License** in the PMO menu and **Open License** on the administration page, while the license is in full effect |

Only the menu entry goes. The license screen still opens at its address for the same people, and
every way to it that an expired, read-only or missing license needs stays: the redirect to it, the
banners and their links, and the reduced menu shown while no license is in effect. The short
license status line (on the administration page or at the foot of the menu) stays too.

The applications keep the license, the installation id and their license history in one directory:

| Setting | Value |
|---|---|
| `ENS_LICENSE_FILE` | `/var/lib/ens/<product>/license.lic` |
| `ENS_LICENSE_STATE_DIR` | `/var/lib/ens/<product>` |

`<product>` is `certalert`, `directory-portal`, `readyroom` or `mfa-portal`. The container images,
the Helm charts and the Directory Services Portal's native package all use these paths.

Where that directory is kept depends on how you installed:

| Install method | Kept in |
|---|---|
| Release package | The named Docker volume `ens-license` |
| Helm chart | A PersistentVolumeClaim the chart creates (named in each install guide), or one you give it in `persistence.license.existingClaim` |
| Native package (Directory Services Portal) | `/var/lib/ens/directory-portal` on the host |

**Keep that volume.** The installation id lives there: if it is lost, the installation gets a new id
and any license bound to the old one must be re-bound on the portal. `helm upgrade` keeps the PVC;
`helm uninstall` deletes a PVC the chart created.

Next to the license, the directory holds three small files: `installation-id`,
`revisions.properties` (the highest revision accepted of each license) and, from the releases named
under [The clock](#the-clock), `latest-time-seen`. CertAlert, ReadyRoom and the RSA Operations
Portal keep all of this in their database instead (tables `ens_license_installation`,
`ens_license_file` and `ens_license_revision`), and so does the Directory Services Portal when it
has a shared database: the directory is then read once, on the first start, to import what an
earlier release kept there. See
[Sizing, availability and recovery](availability-and-sizing.md#backups-and-disaster-recovery).

You can also skip the upload screen and give the license to the installer:

=== "Release package"

    Put the file at `license/license.lic` next to the bundle's `docker-compose.yml` before running
    `install.sh` or `upgrade.sh`, and they copy it in. Or copy it in yourself:

    ```bash
    docker compose cp license.lic <service>:/var/lib/ens/<product>/license.lic
    ```

=== "Helm"

    Pass the file with `--set-file license.file=./license.lic`, or put it in a Secret under the key
    `license.lic` and name it in `license.existingSecret`:

    ```bash
    kubectl -n <namespace> create secret generic <product>-license --from-file=license.lic=./license.lic
    helm upgrade --install <release> oci://ghcr.io/winllc/charts/<chart> --version <version> \
      -n <namespace> -f my-values.yaml --set license.existingSecret=<product>-license
    ```

Either way, the license is copied in **only if none is installed yet**. A license uploaded later on
the license screen, which may be a newer one, is never replaced by `install.sh`, `upgrade.sh` or
`helm upgrade`. To replace an installed license, upload the new one on the license screen (or, with the Directory
Services Portal's Docker package, run `./portal.sh license <file>`).

### In an environment variable

Each application also reads the license from the `ENS_LICENSE` environment variable. It takes:
- the **token**: the text between the file's `-----BEGIN ENS LICENSE-----` and `-----END ENS LICENSE-----`
  lines, line breaks allowed;
- the whole **file**;
- the file **base64-encoded**, on one line: `base64 -w0 license.lic`.

When the application starts, it installs that license into `ENS_LICENSE_FILE`, but only if:
- no usable license is installed yet; or
- the variable holds a **newer revision** of the installed license.

Anything else is left alone, so a different license uploaded on the license screen is never replaced by
the variable. A license for another product or another installation, or text that isn't a valid license,
is refused, and the installed license is untouched. So is a newer revision that is not yet valid, or
expired past its grace period, while the installed license is in full effect (see
[When an upload is refused](#when-an-upload-is-refused)). The application logs one line saying what
it did with `ENS_LICENSE`. That line is the place to look if a license from the variable didn't take
effect:

| Log line | Meaning |
|---|---|
| `ENS_LICENSE (<id> revision <n>) was installed to …` | Installed |
| `ENS_LICENSE (<id> revision <n>) is already installed, or an older revision of the installed license.` | Nothing to do |
| `ENS_LICENSE (<id>) was not installed: license <other id> is already installed. …` | A different license is installed and usable. Upload the new one on the license screen to change licenses |
| `ENS_LICENSE (<id>) was refused: …` (warning) | Another product or installation, an older revision, or not yet in effect, with the reason |
| `ENS_LICENSE was not installed: …` (warning) | The variable does not hold a valid license |
| `ENS_LICENSE could not be written to …: …. It is tried again in 30 seconds.` (warning) | The license store could not be read or written, for example because the database was still starting |

The variable is looked at once per start, with one exception. From CertAlert 0.0.4, the Directory
Services Portal 0.0.9, ReadyRoom 0.0.11 and the RSA Operations Portal 0.0.3, a start at which the
license store could not be read or written is not the end of it: the application tries the
variable again at a license check at least 30 seconds later, and keeps trying until the store
answers. Earlier releases ignored the variable until the next restart, so a pod that started before
its database could stay unlicensed with its license in the environment. Every other outcome in
the table is final until the application restarts: a license refused because it has not started yet
is installed by a restart on or after its start date.

=== "Release package"

    Set it in the bundle's `.env`. The base64 form keeps it on one line:

    ```bash
    echo "ENS_LICENSE=$(base64 -w0 license.lic)" >> .env
    docker compose up -d
    ```

=== "Helm"

    Put the license in a Secret, and pass it to the application with `extraEnv`. For the Directory
    Services Portal the setting is `api.extraEnv`:

    ```bash
    kubectl -n <namespace> create secret generic <product>-license-env --from-file=ENS_LICENSE=./license.lic
    ```

    ```yaml
    extraEnv:
      - name: ENS_LICENSE
        valueFrom:
          secretKeyRef:
            name: <product>-license-env
            key: ENS_LICENSE
    ```

    To roll out a renewed license, replace the Secret and restart the pod
    (`kubectl rollout restart deployment/<release>-<chart>`). A newer revision of the same license is
    installed when the pod starts.

The license file isn't secret: it is signed, can be bound to one installation, and holds only the
licensee and what is licensed. It is still visible to anyone who can read the container's environment
(`docker inspect`, the pod spec), the same as the file on disk.

## What the license controls

| License state | What the application does |
|---|---|
| Valid | Everything the license's feature list unlocks. Evaluations show a banner. |
| Expired, within the grace period (subscriptions: 30 days) | The same, plus a renewal warning for administrators. |
| Expired past grace | **Read-only**: data stays visible and exportable; nothing can be changed; scheduled jobs, syncs and notifications pause. |
| Perpetual, but this release was built after its maintenance ended | Read-only. Renew maintenance or run an older release. |
| Missing, invalid, for another product or installation, superseded by a newer revision, or not yet valid | **Unlicensed**: only sign-in, the license screen and the audit log work (see below). |

Administrators see a renewal reminder from 30 days before expiry.

Whatever the state, the audit log and its pull API stay available in every application, and in
ReadyRoom so do a person's data export and erasure (erasure also while read-only).

**Size limits.** Each license covers a number of directory users, tracked people or SecurID users. Up
to 10% over, the application warns. Further over, it stops taking on more, though it never removes
anything. CertAlert enforces this as it sweeps the directory, and ReadyRoom as new people are tracked.
The RSA Operations Portal and Directory Services Portal show usage only.

### The clock

Start dates, expiry and grace are checked against the server's clock. From CertAlert 0.0.4, the
Directory Services Portal 0.0.9, ReadyRoom 0.0.11 and the RSA Operations Portal 0.0.3, each
installation also records the latest time it has seen, so that setting the clock back does not
bring an expired license back:

- The application writes the clock's time to its license state at the first license check after a
  start and then at most once an hour. The recorded time only ever moves forward.
- While the clock is no more than **one day** behind the recorded time, the clock is used. NTP
  steps and corrections of minutes or hours change nothing.
- When the clock is more than a day behind it, licenses are checked as of the recorded time less
  one day. The license screen's message then ends with "(This server's clock is behind a time this
  installation has already seen, so the license is checked as of *date*.)", and the log has a
  warning: `The clock reads …, before …, the latest time this installation has seen: licenses are
  checked as of ….`

**Upgrading.** Nothing to set. The database column is added by the application's own migration at
the first start of the new release, and an installation coming from an earlier release has no
recorded time until that start records one. Check that the host's clock is right before that first
start. An earlier release ignores the file and the column, so going back to one changes nothing.

!!! warning "A clock that was once far in the future"
    If a host's clock ran ahead by more than a day while the application was running (a wrong date
    set by hand, a hypervisor or hardware clock fault before NTP corrected it), that future time
    may have been recorded. After the clock is corrected, licenses keep being checked as of that
    time less one day until the real date catches up with it. A subscription or evaluation whose
    expiry falls before that date shows as expired, with the renewal warning, or as read-only once
    its grace period is also before it, although it is valid by the calendar. A perpetual license
    is not affected, because it has no expiry date.

    The application has no screen or setting that lowers the recorded time. Correct the clock on
    every host that runs the application, then send ENS Solutions the license screen's message and
    the log warning: support will tell you how to clear the recorded time for your installation.


## Features

A license lists every standard feature of its product. Enterprise features come with the top size
band, evaluations and pilots, or when ENS adds them to an order.

| Product | Feature ids |
|---|---|
| CertAlert (`certalert`) | `directory-sync`, `changelog-sync`, `notifications`, `metrics`, `projects`, `revocation-checks`, `endpoint-checks`, `risky-names`, `directory-cleanup`, `classification-banners`, `multi-directory` (Enterprise, not yet in the product) |
| Directory Services Portal (`directory-portal`) | `directory-editor`, `form-builder`, `self-service-profile`, `white-pages`, `smart-card-sign-in`, `metrics`, `data-analysis`, `schema-transfer`, `multi-directory` (Enterprise, from v0.0.6: several LDAP directories in one portal; failover within one directory needs no feature). Group access (v0.0.7) has no id of its own: `directory-editor` or `self-service-profile` turns it on |
| ReadyRoom (`readyroom`) | `attendance`, `calendar`, `absence-alerts`, `metrics` (metrics and reports), `org-chart`, `classification-banners` |
| RSA Operations Portal (`mfa-portal`) | `self-service`, `trusted-agents`, `help-desk`, `shipments`, `scheduled-jobs`, `branding`, `reports`, `bulk-upload`, `group-email` |
| syncd (`syncd`) | Core: `directory-connectors`, `database-connectors`, `merged-views`, `console`. Enterprise, one per pack, written into the syncd file as its pack ids: `enterprise-database`, `cloud-saas`, `password-sync`, `high-availability`, `extensibility`. See [syncd's packs](syncd/licensing.md#packs) |

The catalog lives in the portal's source, in `src/data/licensing.ts` in the products-page
repository. Every feature is checked by its application, so moving one between tiers changes only
that file. Never rename an id once licenses carrying it have been issued.

## FIPS 140-3

Each application checks its license signature with the Java runtime's own providers; the license
library has no cryptography of its own and needs no secret on your system. Whether that check works
on a Java runtime in FIPS mode depends on the signature algorithm.

Every application on its current code carries license library 1.2.0 and trusts two ENS production
keys: Ed25519 and ECDSA P-256 (prime256v1). A license file can carry the same license signed by
both. An application accepts it if any signature by a trusted key verifies with an algorithm the
runtime provides, trying Ed25519 first. Red Hat's OpenJDK in FIPS mode (on a FIPS-enabled RHEL host
or OpenShift cluster) has no Ed25519, so there the P-256 signature is the one that counts. A Java
runtime that is not in FIPS mode checks Ed25519 as before.

!!! warning "Moving to FIPS mode: get a re-signed license first"
    Licenses issued before ENS enabled P-256 signing carry only the Ed25519 signature. On a Java
    runtime in FIPS mode such a file is refused, with a message that it is signed only with
    Ed25519, and the application is **unlicensed** (see
    [What the license controls](#what-the-license-controls)). Before you move an installation to a
    FIPS host, ask ENS Solutions for a re-signed file of the same license and upload it on the
    license screen. The installation id and the license's revisions are unaffected. **Check a
    file** on your licensing-portal account says whether a file has an ECDSA P-256 signature.

    Don't work around it with `-Dcom.redhat.fips=false`. It brings Ed25519 back by taking the
    whole JVM out of FIPS mode.

| Runtime | Ed25519 | ECDSA P-256 (`SHA256withECDSA`) |
|---|---|---|
| Red Hat OpenJDK 21.0.12 and 17.0.20 (UBI 9), **FIPS mode** | No | Yes, from `SunPKCS11-NSS-FIPS` |
| The same, not in FIPS mode | Yes | Yes |
| BouncyCastle FIPS 2.0.0 to 2.0.2, and 2.1.0, approved-only mode | No | Yes |
| BouncyCastle FIPS 2.1.1 to 2.1.3, approved-only mode | Yes | Yes |

These are the results of the license library's FIPS checks in the products-page repository's CI
(job `licensing-fips`, using `NSS_FIPS=1` for Red Hat's FIPS mode). They are tests, not a
certification.

## For ENS: keys and issuing

- **Signing keys.** The portal signs licenses with an Ed25519 key passed in `LICENSE_SIGNING_KEY`
  or `LICENSE_SIGNING_KEY_FILE`. Generate one with `npm run license -- keygen` in the products-page
  repository. Keep the private key in a secret store, back it up, and never commit it.
- **The ECDSA P-256 key, for FIPS mode.** The production key exists, and every application trusts
  its public key from license library 1.2.0. Keep the private key with the Ed25519 one. Set it on
  the portal in `LICENSE_SIGNING_KEY_P256` or `LICENSE_SIGNING_KEY_P256_FILE`. From then on every
  license and re-issue is signed with both keys, and `/api/licensing/keys` lists both public keys.
  For a customer running in FIPS mode, or moving to it, open their license in `/admin` and use
  **Re-sign**. It issues the same license as the next revision, signed with both keys. The action
  appears only on a revision that lacks one of the current keys' signatures. (A new key of either
  kind is made with `npm run license -- keygen`, adding `--alg p256` for P-256, on a trusted
  machine.)
- **Public keys.** Each application has the public key built in, as `EnsKeys`, by key id. Licenses
  name the key that signed them, so keys can be rotated:
  1. add the new public key to every application and release;
  2. switch the portal to the new private key;
  3. remove the old public key once no license signed with it is still in use.

  Until the old key is removed from the applications, keep it trusted on the portal with
  `LICENSE_TRUSTED_KEYS` (Ed25519 or ECDSA P-256).
- **Renewals with a later start date.** An application refuses a license that has not started
  yet while its installed license is in full effect (see
  [When an upload is refused](#when-an-upload-is-refused)), from the releases named there. When a
  renewal's start date is in the future, tell the customer to upload it on or after that date; the
  current license, with its grace period, covers the days until they do.
- **Inspecting and checking files.** `npm run license -- inspect file.lic` decodes a license and
  shows each signature's key id, and `npm run license -- verify file.lic <public-key>...` checks
  every signature made by a key given. Add `--alg p256` to check it the way a Java runtime in FIPS
  mode will.
- **The file format** is described in `licensing/README.md` in the products-page repository. That
  directory also holds the Java library every application embeds.
- **syncd.** syncd licenses are written in syncd's own format (`docs/LICENSE-FORMAT.md` in the
  syncd repository) and signed with the **P-256 key only**, since syncd verifies nothing else:
  until `LICENSE_SIGNING_KEY_P256` is set, the portal refuses to issue syncd licenses, evaluations
  included, and says why. syncd's release builds carry the portal's P-256 public key (key id
  `e0a2e4ff9c4f5bfc`, in `deploy/licensing/ens-portal-public-keys.pem` in the syncd repository),
  so the portal's P-256 private key must be the matching half. A syncd file is a new license id
  for every revision (`<id>-r<revision>`), keeps installation ids in lower case, and gives a
  perpetual license a far expiry date. Check one with syncd's own tool:
  `cargo run -p license-sign -- verify --public-key portal-p256.pem FILE`.
