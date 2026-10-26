# Licensing

Every application in the suite needs a license file from ENS Solutions. Licenses are issued through
the **ENS licensing portal**:
- customers download free evaluations and the licenses they have bought;
- ENS staff issue, renew and revoke licenses against orders.

Each application checks its license offline, against public keys built into it, so licensing works the
same on an air-gapped network.

## Getting a license

1. **Create an account** on the ENS licensing portal (`/account`) and confirm your email address.
2. **Evaluate**: download a free 30-day evaluation license for any product, one per product. An
   evaluation covers the smallest size band, unlocks every feature, and shows an evaluation banner.
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

### Hiding the license screen's menu entry

From the next release, `ENS_LICENSE_MENU_VISIBLE=false` (Helm: `license.menuVisible: false`) takes
the license screen out of the navigation, for installations where operators should not be pointed at
it. The default is `true`, so nothing changes unless you set it. A value that is not a boolean stops
the application at startup.

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
is refused, and the installed license is untouched. The application logs one line saying what it did with
`ENS_LICENSE`. That line is the place to look if a license from the variable didn't take effect.

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

## Features

A license lists every standard feature of its product. Enterprise features come with the top size
band, evaluations and pilots, or when ENS adds them to an order.

| Product | Feature ids |
|---|---|
| CertAlert (`certalert`) | `directory-sync`, `changelog-sync`, `notifications`, `metrics`, `projects`, `revocation-checks`, `endpoint-checks`, `risky-names`, `directory-cleanup`, `classification-banners`, `multi-directory` (Enterprise, not yet in the product) |
| Directory Services Portal (`directory-portal`) | `directory-editor`, `form-builder`, `self-service-profile`, `white-pages`, `smart-card-sign-in`, `metrics`, `data-analysis`, `schema-transfer` |
| ReadyRoom (`readyroom`) | `attendance`, `calendar`, `absence-alerts`, `metrics` (metrics and reports), `org-chart`, `classification-banners` |
| RSA Operations Portal (`mfa-portal`) | `self-service`, `trusted-agents`, `help-desk`, `shipments`, `scheduled-jobs`, `branding`, `reports`, `bulk-upload`, `group-email` |

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
- **Inspecting and checking files.** `npm run license -- inspect file.lic` decodes a license and
  shows each signature's key id, and `npm run license -- verify file.lic <public-key>...` checks
  every signature made by a key given. Add `--alg p256` to check it the way a Java runtime in FIPS
  mode will.
- **The file format** is described in `licensing/README.md` in the products-page repository. That
  directory also holds the Java library every application embeds.
