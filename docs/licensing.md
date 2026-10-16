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
| CertAlert | Administration → License | Administrators |
| Directory Services Portal | Administration → License | Administrators |
| ReadyRoom | Settings → License | Administrators |
| RSA Operations Portal | Admin → License | PMO |

The applications keep the license, the installation id and their license history in one directory:

| Setting | In the container images | Outside a container (default) |
|---|---|---|
| `ENS_LICENSE_FILE` | `/var/lib/ens/<product>/license.lic` | `/etc/ens/<product>.lic` |
| `ENS_LICENSE_STATE_DIR` | `/var/lib/ens/<product>` | `/var/lib/ens/<product>` |

`<product>` is `certalert`, `directory-portal`, `readyroom` or `mfa-portal`.

The images declare `/var/lib/ens` as a volume, and the release bundles' compose files keep it on a
named volume, `ens-license`. **Keep that volume.** The installation id lives there: if it is lost,
the installation gets a new id and any license bound to the old one must be re-bound on the portal.

You can also skip the upload screen. Put the file at `license/license.lic` next to the bundle's
`docker-compose.yml` before running `install.sh` or `upgrade.sh`, and they copy it in. Or copy it in
yourself:

```bash
docker compose cp license.lic <service>:/var/lib/ens/<product>/license.lic
```

## What the license controls

| License state | What the application does |
|---|---|
| Valid | Everything the license's feature list unlocks. Evaluations show a banner. |
| Expired, within the grace period (subscriptions: 30 days) | The same, plus a renewal warning for administrators. |
| Expired past grace | **Read-only**: data stays visible and exportable; nothing can be changed; scheduled jobs, syncs and notifications pause. |
| Perpetual, but this release was built after its maintenance ended | Read-only. Renew maintenance or run an older release. |
| Missing, invalid, for another product or installation, superseded by a newer revision, or not yet valid | **Unlicensed**: only sign-in and the license screen work. |

Administrators see a renewal reminder from 30 days before expiry.

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

## For ENS: keys and issuing

- **Signing key.** The portal signs licenses with an Ed25519 key passed in `LICENSE_SIGNING_KEY` or
  `LICENSE_SIGNING_KEY_FILE`. Generate one with `npm run license -- keygen` in the products-page
  repository. Keep the private key in a secret store and never commit it.
- **Public keys.** Each application has the public key built in, as `EnsKeys`, by key id. Licenses
  name the key that signed them, so keys can be rotated:
  1. add the new public key to every application and release;
  2. switch the portal to the new private key;
  3. remove the old public key once no license signed with it is still in use.

  Until the old key is removed from the applications, keep it trusted on the portal with
  `LICENSE_TRUSTED_KEYS`.
- **Inspecting and checking files.** `npm run license -- inspect file.lic` decodes a license, and
  `npm run license -- verify file.lic <public-key>` checks its signature.
- **The file format** is described in `licensing/README.md` in the products-page repository. That
  directory also holds the Java library every application embeds.
