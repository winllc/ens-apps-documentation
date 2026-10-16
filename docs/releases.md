# Releases and downloads

Each application ships to customers as one **deployment bundle** per release: a tarball that holds
everything an installation needs, including the container images, so a site with no internet access
can install from it alone.

## For customers: downloading and verifying a release

ENS Solutions sends you time-limited links to four kinds of file:

| File | What it is |
|---|---|
| `<product>-<version>.tar.gz` | The deployment bundle |
| `sbom-*.spdx.json` | Software bill of materials (SPDX) for each container image |
| `SHA256SUMS` | Checksums of the files above |
| `SHA256SUMS.asc` | ENS Solutions' signature over the checksums |

Check the download before installing it:

```bash
# 1. The signature: proves SHA256SUMS came from ENS Solutions (needs our public key, once)
gpg --import ens-solutions-release.asc
gpg --verify SHA256SUMS.asc SHA256SUMS

# 2. The checksums: proves each file matches what was signed
sha256sum -c SHA256SUMS
```

Both must succeed. `gpg` should report a good signature from the ENS Solutions release key. Check
its fingerprint against the one ENS Solutions gave you separately, not against anything in the
download itself.

Then unpack and install:

```bash
tar xzf <product>-<version>.tar.gz
cd <product>-<version>
./install.sh            # first install: loads the images, creates .env from the template
```

`install.sh` tells you which settings in `.env` to fill in and where the license file goes
(`license/license.lic`; see [Licensing](licensing.md)). To upgrade, unpack the new bundle next to the
old one, copy `.env` and `license/` across, and run `./upgrade.sh`. Each bundle's `README.md` has
the details for that product.

## For ENS Solutions: cutting and delivering a release

### Cutting a release

Push a version tag to the product's repository:

```bash
git tag v1.4.0 && git push origin v1.4.0
```

CI then runs the tests, builds and smoke-tests the images, and publishes them to
`ghcr.io/winllc/...:1.4.0`. From those same tested images it builds the bundle, the SBOMs and
`SHA256SUMS`, signs the checksums, and uploads everything to the release bucket under
`<product>/<version>/`. A release without a pre-release suffix also updates `<product>/latest.txt`.
Every pull request builds a dry-run bundle (`0.0.0-pr<n>`) as a workflow artifact, without signing
or uploading, so packaging problems show up before a release.

### One-time setup

Set these on each product repository (Settings → Secrets and variables → Actions), or once for the
whole organization:

| Name | Kind | Value |
|---|---|---|
| `RELEASE_S3_BUCKET` | variable | Bucket name |
| `RELEASE_S3_ENDPOINT` | variable | Only for Cloudflare R2 or MinIO, such as `https://<account>.r2.cloudflarestorage.com` |
| `RELEASE_S3_REGION` | variable | Defaults to `us-east-1`; `auto` for R2 |
| `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` | secrets | Credentials that can write to the bucket, and to nothing else |
| `RELEASE_GPG_PRIVATE_KEY` | secret | ASCII-armored private signing key |
| `RELEASE_GPG_PASSPHRASE` | secret | Its passphrase, if it has one |

Until the bucket is configured, CI keeps each release bundle as a workflow artifact for 30 days
instead. Until the signing key is configured, bundles are unsigned. The job summary says which.

Keep the bucket **private**: no public access and no website hosting. Customers only ever get
pre-signed links.

Create the signing key once, offline, and publish only its public half:

```bash
gpg --quick-generate-key "ENS Solutions Release Signing <releases@ens-solutions.com>" ed25519 sign 3y
gpg --armor --export-secret-keys releases@ens-solutions.com   # paste into RELEASE_GPG_PRIVATE_KEY
gpg --armor --export releases@ens-solutions.com > ens-solutions-release.asc   # give to customers
gpg --fingerprint releases@ens-solutions.com                   # send separately, for them to check
```

This key signs downloads. It is separate from the license signing key the licensing portal uses
(see [Licensing](licensing.md)), and the two should never be the same key.

### Sending a customer their download

`tools/release/presign.sh` prints a link for every file in a release, valid for up to seven days:

```bash
export RELEASE_S3_BUCKET=ens-releases          # plus RELEASE_S3_ENDPOINT for R2/MinIO
tools/release/presign.sh readyroom             # the latest release
tools/release/presign.sh readyroom 1.4.0 86400 # a specific version, links valid for one day
```

Send the links together with the customer's license file. Customers who pull images directly from
a registry instead of using the bundle still need the license file, and can use the bundle's
`docker-compose.yml` and `env.template` without its `images/` directory.
