# Releases and downloads

Each application ships to customers in two forms per release, and these are the only supported
ways to install it:

- a signed **deployment bundle** (release package): a tarball that holds everything a Docker Compose
  installation needs, including the container images, so a site with no internet access can install
  from it alone. The Directory Services Portal also has a **native package** for a Linux host with
  systemd and no containers;
- a **Helm chart** for Kubernetes and OpenShift, at the same version as the application, published
  at `oci://ghcr.io/winllc/charts/<chart>` and included in the download.

## For customers: downloading and verifying a release

ENS Solutions sends you time-limited links to these files:

| File | What it is |
|---|---|
| `<product>-<version>.tar.gz` | The deployment bundle |
| `<product>-<version>.tgz` | The Helm chart (ReadyRoom: `readyroom-helm-<version>.tgz`) |
| `directory-services-portal-<version>-native.tar.gz` | Directory Services Portal only: the native (systemd) package |
| `sbom-*.spdx.json` | Software bill of materials (SPDX) for each container image |
| `SHA256SUMS` | Checksums of the files above |
| `SHA256SUMS.asc` | ENS Solutions' signature over the checksums |

| Product | `<product>` | Chart |
|---|---|---|
| CertAlert | `cert-alert` | `oci://ghcr.io/winllc/charts/cert-alert` |
| Directory Services Portal | `directory-services-portal` | `oci://ghcr.io/winllc/charts/directory-services-portal` |
| ReadyRoom | `readyroom` | `oci://ghcr.io/winllc/charts/readyroom` |
| RSA Operations Portal | `rsa-operations-portal` | `oci://ghcr.io/winllc/charts/rsa-operations-portal` |

Check the download before installing it:

```bash
# 1. The signature: proves SHA256SUMS came from ENS Solutions (needs our public key, once)
gpg --import ens-solutions-release.asc
gpg --verify SHA256SUMS.asc SHA256SUMS

# 2. The checksums: proves each file matches what was signed
sha256sum -c SHA256SUMS --ignore-missing   # checks the files you downloaded
```

Both must succeed. `gpg` should report a good signature from the ENS Solutions release key. Check
its fingerprint against the one ENS Solutions gave you separately, not against anything in the
download itself.

Then install it in one of two ways.

=== "Release package"

    ```bash
    tar xzf <product>-<version>.tar.gz
    cd <product>-<version>
    ./install.sh            # first install: loads the images, creates .env from the template
    ```

    `install.sh` tells you which settings in `.env` to fill in and where the license file goes
    (`license/license.lic`; see [Licensing](licensing.md)). To upgrade, unpack the new bundle and
    run its `./upgrade.sh`, pointing it at the current installation. It carries your `.env` and
    license across. Each bundle's `README.md` has the details for that product.

=== "Helm"

    Install the chart from the registry, or from the `.tgz` you downloaded and verified:

    ```bash
    helm install <release> oci://ghcr.io/winllc/charts/<chart> --version <version> \
      -n <namespace> -f my-values.yaml
    # or, from the download (an air-gapped cluster, with the images mirrored):
    helm install <release> ./<product>-<version>.tgz -n <namespace> -f my-values.yaml
    ```

    Upgrade with `helm upgrade` and the new version. Each chart's `README.md` lists its values.

Each product's install guide covers both methods in full.

## For ENS Solutions: cutting and delivering a release

### Cutting a release

Push a version tag to the product's repository:

```bash
git tag v1.4.0 && git push origin v1.4.0
```

CI then runs the tests, builds and smoke-tests the images, and publishes them to
`ghcr.io/winllc/...:1.4.0`. From those same tested images it builds the bundle (and, for the Directory
Services Portal, the native package), packages the Helm chart and publishes it to
`oci://ghcr.io/winllc/charts/<chart>`, makes the SBOMs and `SHA256SUMS`, signs the checksums, and uploads everything to the release bucket under
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

Send the links together with the customer's license file. Customers who install with Helm, or
whose Docker host can pull from `ghcr.io/winllc`, still need the license file. A Helm install pulls
the images from the registry or a mirror; the bundle's `images/` archive can seed that mirror.
