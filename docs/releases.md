# Releases and downloads

Each application ships to customers in two forms per release, and these are the only supported
ways to install it:

- a signed **deployment bundle** (release package): a tarball that holds everything a Docker Compose
  installation needs, including the container images, so a site with no internet access can install
  from it alone. The Directory Services Portal also has a **native package** for a Linux host with
  systemd and no containers;
- a **Helm chart** for Kubernetes and OpenShift, at the same version as the application, published
  at `oci://ghcr.io/winllc/charts/<chart>` and included in the download.

!!! note "syncd"
    syncd's pipeline differs from the others'. On a version tag it uploads the release to the same
    bucket, under `syncd/<version>/`, so syncd appears on the **Downloads** page like the other products
    (from 0.5.0; the latest is 0.7.0): offline bundles (`.tar.gz`, x86_64 and aarch64, each also as
    a FIPS build), `.deb` and `.rpm` packages for a Linux host with systemd, the `syncd-capture` package
    for directory servers, CycloneDX SBOMs (`.cdx.json`), and `SHA256SUMS`. With the release key set it
    also signs `SHA256SUMS` (`SHA256SUMS.asc`) and the `.rpm` files and publishes apt and dnf
    repositories; every release so far (0.5.0 to 0.7.0) was built without it, so they are **unsigned**
    and there are no repositories yet. From 0.5.4 the GitHub release carries the same files.
    (0.5.2 was published as container images and a Helm chart only; nothing was published as 0.5.3;
    0.6.1's images and chart were not published; 0.7.0's images were pushed and signed, but its Helm
    chart was not, so the newest chart on ghcr.io is 0.6.0's.) Check a download as in
    [syncd's install guide](syncd/install.md#verifying-a-release). The container images `ghcr.io/winllc/syncd` and `ghcr.io/winllc/syncd-console` (amd64 and arm64,
    with `-fips` tags) and the Helm chart `oci://ghcr.io/winllc/charts/syncd` are signed
    with cosign as below, from the repository `winllc/ldap-sync-merge-view`. See
    [syncd's install guide](syncd/install.md#getting-the-release).

## For customers: downloading and verifying a release

### Downloading

1. Sign in to the ENS licensing portal and open **Downloads** (`/account/downloads`).
2. Pick the product. The latest version is at the top; every earlier version is listed below it,
   newest first, with pre-releases marked. Expand a version to see its files, with each file's size
   and SHA-256.
3. Download the files you need, including `SHA256SUMS` (and `SHA256SUMS.asc` when the release is
   signed).

Any account that holds a license for the product can download it, an evaluation license
included, so taking the free evaluation is enough to get the software. A product you have no
license for shows the evaluation button instead. Each link works for a few minutes only (five
by default); it is a fresh, short-lived link to ENS Solutions' private storage, made each time you
click, and each download is recorded in your account's history. If your site cannot reach the
portal, ENS Solutions can send you links instead (see
[below](#sending-a-customer-links-directly)).

Each version holds these files:

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

The newest version tagged in each product's repository, as of 25 November 2026. The **Downloads**
page is the authority on what is available:

| Product | Newest version | Notes |
|---|---|---|
| CertAlert | 0.0.5 | 0.0.3 and 0.0.4 were tagged but have no download: see [below](#versions-without-a-download) |
| Directory Services Portal | 0.0.11 | |
| ReadyRoom | 0.0.11 | |
| RSA Operations Portal | 0.0.3 | |

### Verifying

Check the download before installing it:

```bash
# 1. The signature: proves SHA256SUMS came from ENS Solutions (needs our public key, once)
gpg --import ens-solutions-release.asc
gpg --verify SHA256SUMS.asc SHA256SUMS

# 2. The checksums: proves each file matches what was signed
sha256sum -c SHA256SUMS --ignore-missing   # checks the files you downloaded
```

Both must succeed. The downloads page links to ENS Solutions' public key when the portal is
configured with it. `gpg` should report a good signature from the ENS Solutions release key. Check
its fingerprint against the one ENS Solutions gave you separately, not against anything in the
download itself.

**A release with no `SHA256SUMS.asc`** was built without ENS Solutions' signing key: the release
pipeline then uploads `SHA256SUMS` unsigned. Step 2 still shows that each file arrived complete
and matches the checksums, but nothing in the download proves who made them. Ask ENS Solutions to
confirm the checksums over another channel before installing, and verify the images and the chart
with cosign (next section), which does not depend on that key.

### Verifying the images and the chart

On a release tag, CI also signs each published image and the Helm chart with cosign: keyless
signatures (Sigstore), made with the release workflow's GitHub identity, always by digest. Each
image also gets its SPDX SBOM attached as a signed attestation.

The digest signed is the one the registry holds for the version tag: after pushing, CI reads it
back from `ghcr.io` (`docker buildx imagetools inspect`) and signs `<image>@<digest>`; the chart is
signed by the digest `helm push` printed. So the digest you look up for a tag, as in the example
below, is the digest that was signed. A tag can be moved; a digest cannot, so deploy and mirror by
digest where you can.

| Product | Images | Chart | Repository in the signing identity |
|---|---|---|---|
| CertAlert | `ghcr.io/winllc/cert-alert` | `ghcr.io/winllc/charts/cert-alert` | `winllc/cert-alert` |
| Directory Services Portal | `ghcr.io/winllc/directory-services-portal-api`, `ghcr.io/winllc/directory-services-portal-web` | `ghcr.io/winllc/charts/directory-services-portal` | `winllc/directory-services-portal` |
| ReadyRoom | `ghcr.io/winllc/in-n-out-work` | `ghcr.io/winllc/charts/readyroom` | `winllc/in-n-out-work` |
| RSA Operations Portal | `ghcr.io/winllc/rsa-operations-portal`, `ghcr.io/winllc/rsa-operations-portal-connector` | `ghcr.io/winllc/charts/rsa-operations-portal` | `winllc/rsa-operations-portal` |
| syncd | `ghcr.io/winllc/syncd`, `ghcr.io/winllc/syncd-console` | `ghcr.io/winllc/charts/syncd` | `winllc/ldap-sync-merge-view` |

For example, for ReadyRoom:

```bash
# A tag's digest
docker buildx imagetools inspect ghcr.io/winllc/in-n-out-work:<version>

# The image's signature, and its SBOM attestation
cosign verify ghcr.io/winllc/in-n-out-work@<digest> \
  --certificate-identity-regexp 'https://github.com/winllc/in-n-out-work/.github/workflows/.*' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
cosign verify-attestation --type spdxjson ghcr.io/winllc/in-n-out-work@<digest> \
  --certificate-identity-regexp 'https://github.com/winllc/in-n-out-work/.github/workflows/.*' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com

# The chart (its digest is the one `helm pull` or `helm push` prints)
cosign verify ghcr.io/winllc/charts/readyroom@<digest> \
  --certificate-identity-regexp 'https://github.com/winllc/in-n-out-work/.github/workflows/.*' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
```

For the other products, use their names and repository from the table. By default cosign
contacts Sigstore's public services to verify a keyless signature, so on an air-gapped network,
verify on a connected machine before you mirror the images.

CertAlert's image also carries a signed build-provenance attestation (which workflow, commit and
runner built it), which the GitHub CLI checks:

```bash
gh attestation verify oci://ghcr.io/winllc/cert-alert@<digest> --repo winllc/cert-alert
```

#### Versions without a download

CertAlert 0.0.3 and 0.0.4 were tagged, and their release runs pushed the image
`ghcr.io/winllc/cert-alert` under those version tags, but both runs stopped at the signing step:
they took the digest from the build machine instead of the registry and found none to sign. Those
two images, and the charts pushed in the step before, are therefore **unsigned** (`cosign verify`
fails for them), and no bundle, SBOM or checksum file was uploaded, so neither version is on the
**Downloads** page. Use 0.0.5 or later, whose release signs by the registry's digest. 0.0.5 is
0.0.4's code with that one change to the release pipeline, so everything these pages say arrived
in CertAlert 0.0.4 reaches a release-package installation with 0.0.5.

### Installing

Then install it in one of two ways.

=== "Release package"

    ```bash
    tar xzf <product>-<version>.tar.gz
    cd <product>-<version>
    ./install.sh            # first install: loads the images, creates .env from the template
    ```

    The scripts work with Docker or Podman. `install.sh` tells you which settings in `.env` to fill in and where the license file goes
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

**Before you tag: check the docs against the code.** The product's README is updated with every
change; this site is not, unless someone ports it. For the release you are about to tag:

- [ ] List the README headings and upgrade notes added or changed since the last release
  (`git diff v<previous>..HEAD -- README.md`) and find each feature on this site. Anything missing
  gets a page or a section, in the product's section of `mkdocs.yml`.
- [ ] Check every setting name and default the site gives for those features against
  `application.yml` (or `application.properties`), the Helm chart's `values.yaml` and the bundle's
  `env.template`.
- [ ] Search the product's pages for statements the release makes false: sign-in methods, roles,
  "the only token", "internal API", the last Flyway migration number, the API reference's endpoint
  list against the OpenAPI document.
- [ ] Merge the docs change to `master` before the release is announced.

Then push a version tag to the product's repository:

```bash
git tag v1.4.0 && git push origin v1.4.0
```

CI then runs the tests, builds and smoke-tests the images, and publishes them to
`ghcr.io/winllc/...:1.4.0`. From those same tested images it builds the bundle (and, for the Directory
Services Portal, the native package), packages the Helm chart and publishes it to
`oci://ghcr.io/winllc/charts/<chart>`, makes the SBOMs and `SHA256SUMS`, signs the checksums, signs the images and the chart with
cosign, and uploads everything to the private release bucket under
`<product>/<version>/`. A release without a pre-release suffix also updates `<product>/latest.txt`.
The licensing portal's **Downloads** page lists the new version within a few minutes (its listing
cache, `RELEASES_LISTING_TTL`, five minutes by default).

The bucket then holds, for each product (`<product>` as in the customers' table above):

```
<product>/latest.txt                               the newest final version, one line, such as 0.0.11
<product>/<version>/<product>-<version>.tar.gz     the deployment bundle
<product>/<version>/<product>-<version>.tgz        the Helm chart (ReadyRoom: readyroom-helm-<version>.tgz)
<product>/<version>/sbom-<image>.spdx.json         one per image in the bundle, a bundled database image included
<product>/<version>/SHA256SUMS                     over the files above
<product>/<version>/SHA256SUMS.asc                 only when the signing key is configured
```

The Directory Services Portal adds `directory-services-portal-<version>-native.tar.gz`.
`latest.txt` is left alone by a pre-release (`1.4.0-rc.1`), and the checksum file is made before
signing, so `SHA256SUMS.asc` is not listed in it.

A release reaches customers from the bucket only. On a release tag the image job first checks that
the bucket is configured (`RELEASE_S3_BUCKET` and the AWS credentials below; CertAlert's image job
checks the bucket only and its `release` job the credentials, the others check the role or, until
it exists, the access keys) and fails, before anything is built or published, when it is not.

The images are pushed first; the chart is pushed and the images and the chart are signed next;
the checksums are signed and the bundle is uploaded last. A run that fails part-way therefore
leaves the earlier steps' results behind: images or a chart in the registry without signatures,
and nothing in the bucket, as happened with
[CertAlert 0.0.3 and 0.0.4](#versions-without-a-download). Read the run's summary (it says what
was published, signed and uploaded), fix the cause, and tag the next version; say on this page
which version has no download.

Pull requests build a dry-run bundle (`0.0.0-pr<n>`) as a workflow artifact, without signing or
uploading, so packaging problems show up before a release: every pull request for CertAlert and
the Directory Services Portal, and for ReadyRoom and the RSA Operations Portal those that change
the image, the bundle or the chart. The RSA Operations Portal also builds one on every push to
`main` (`0.0.0-main.<run>`).

### One-time setup

Set these on each product repository (Settings → Secrets and variables → Actions), or once for the
whole organization:

| Name | Kind | Value |
|---|---|---|
| `RELEASE_S3_BUCKET` | variable | Bucket name |
| `RELEASE_S3_ENDPOINT` | variable | Only for Cloudflare R2 or MinIO, such as `https://<account>.r2.cloudflarestorage.com` |
| `RELEASE_S3_REGION` | variable | Defaults to `us-east-1`; `auto` for R2 |
| `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` | secrets | Credentials that can write to the bucket, and to nothing else. A fallback only, while `AWS_RELEASE_ROLE_ARN` is unset (below) |
| `AWS_RELEASE_ROLE_ARN` | variable | The IAM role the upload assumes through GitHub OIDC (below) |
| `RELEASE_GPG_PRIVATE_KEY` | secret | ASCII-armored private signing key |
| `RELEASE_GPG_PASSPHRASE` | secret | Its passphrase, if it has one |

Without the bucket settings a release tag fails (see above). The signing key is optional: without
it the release is still uploaded, with `SHA256SUMS` unsigned and no `SHA256SUMS.asc`, and the job
summary says the release is not signed and names the missing `RELEASE_GPG_PRIVATE_KEY` secret.
Customers are then told to confirm the checksums with ENS Solutions (see
[Verifying](#verifying)), so set the key before a release that customers will install.

Keep the bucket **private**: no public access and no website hosting. Customers only ever get
pre-signed links.

### Upload role and the `release` environment

Each product's pipeline (CertAlert from 0.0.3, the Directory Services Portal from 0.0.4, ReadyRoom
from 0.0.3 and the RSA Operations Portal from 0.0.2) signs and uploads in the GitHub environment
`release`, only on a `v*` tag (pull-request dry runs and branch pushes never use it), and the upload
prefers short-lived credentials for an IAM role assumed through GitHub's OIDC provider
(`aws-actions/configure-aws-credentials`, pinned by commit SHA) over the stored access keys.
`id-token: write` is granted to that one job only.

- **Nothing breaks before the setup.** While `AWS_RELEASE_ROLE_ARN` is unset, the access-key secrets
  are used, with a `::warning::` in the job and its summary. Until the `release` environment is
  created, GitHub creates it on the first tag without protection rules, and releases work as before.
- **Cloudflare R2 and MinIO** have no IAM roles: they stay on the access keys, with
  `RELEASE_S3_ENDPOINT` set.
- The job summary says which credentials the upload used.

Where the signing and upload run, and where the settings go, differs by product:

| Product | Job in the `release` environment | Settings |
|---|---|---|
| CertAlert | A separate `release` job after `image`: it fetches the bundle `image` kept, checks it against `SHA256SUMS`, signs and uploads it | `RELEASE_S3_BUCKET` must stay a repository variable (the `image` job checks it before anything is published); the GPG secrets go in the environment |
| Directory Services Portal | A separate `release` job after `image`: it pushes the chart, signs the images and chart (cosign), signs `SHA256SUMS` and uploads | All variables, `AWS_RELEASE_ROLE_ARN` included, at repository level (the `image` job checks them first); the GPG secrets in the environment; the fallback access keys at repository level |
| ReadyRoom | The `image` job itself, on a tag | GPG secrets in the environment; `AWS_RELEASE_ROLE_ARN` at repository or environment level |
| RSA Operations Portal | The `image` job itself, on a tag | Preferably all in the environment: the GPG secrets, the fallback access keys while used, `AWS_RELEASE_ROLE_ARN` and the `RELEASE_S3_*` variables |

What an administrator sets up once, per product repository (the product's `README.md`, *Releases*,
has the exact commands and policies):

1. **The `release` environment** (Settings → Environments → New environment `release`):
   *Deployment branches and tags* limited to the tag rule `v*`; *Required reviewers*, the people who
   approve a release (optionally *Prevent self-review*), so the job waits for one of them; and the
   `RELEASE_GPG_PRIVATE_KEY` and `RELEASE_GPG_PASSPHRASE` secrets moved here from the repository.
2. **GitHub's OIDC provider in AWS**, once per account: IAM → Identity providers, OpenID Connect,
   URL `https://token.actions.githubusercontent.com`, audience `sts.amazonaws.com`.
3. **The role**, with a trust policy that admits only this repository's release runs, and a
   permissions policy that allows `s3:PutObject` on `arn:aws:s3:::<bucket>/<product>/*` and nothing
   else (ReadyRoom's README adds `s3:AbortMultipartUpload`; add `kms:GenerateDataKey` on the bucket's
   key if it uses SSE-KMS). A job in an environment presents the subject
   `repo:winllc/<repo>:environment:release`. For ReadyRoom and the RSA Operations Portal, have
   GitHub put the ref in the subject first, and trust only release tags:

    ```bash
    gh api -X PUT repos/winllc/<repo>/actions/oidc/customization/sub \
      -F use_default=false -f 'include_claim_keys[]=repo' -f 'include_claim_keys[]=context' -f 'include_claim_keys[]=ref'
    ```

    ```json
    {
      "Version": "2012-10-17",
      "Statement": [{
        "Effect": "Allow",
        "Principal": {"Federated": "arn:aws:iam::<account>:oidc-provider/token.actions.githubusercontent.com"},
        "Action": "sts:AssumeRoleWithWebIdentity",
        "Condition": {
          "StringEquals": {"token.actions.githubusercontent.com:aud": "sts.amazonaws.com"},
          "StringLike": {"token.actions.githubusercontent.com:sub": "repo:winllc/<repo>:environment:release:ref:refs/tags/v*"}
        }
      }]
    }
    ```

    The CertAlert and Directory Services Portal READMEs trust the default subject with
    `StringEquals` on `repo:winllc/<repo>:environment:release` (the environment's tag rule then
    limits it to release tags), and offer the same customisation for a subject that names the tag.
4. **The variable** `AWS_RELEASE_ROLE_ARN` = the role's ARN. Push a pre-release tag (`v1.2.3-rc.1`)
   to try it; then delete the `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY` secrets and the IAM
   user's access key behind them.

### Customer downloads in the licensing portal

The licensing portal serves the **Downloads** page from the same bucket, read-only. It never
streams the files: each download is a `302` redirect to a presigned link, and entitlement is
checked again on every download. Configure it on the licensing service:

| Setting | Value |
|---|---|
| `RELEASES_S3_BUCKET` | The release bucket. Unset, the page says downloads aren't available yet |
| `RELEASES_S3_REGION` | Defaults to `us-east-1`; `auto` for R2 |
| `RELEASES_S3_ENDPOINT` | Only for R2 or MinIO |
| `RELEASES_S3_FORCE_PATH_STYLE` | `true` for MinIO |
| `RELEASES_URL_TTL` | Seconds a download link works. Default `300` |
| `RELEASES_LISTING_TTL` | Seconds a bucket listing is cached. Default `300` |
| `RELEASES_GPG_PUBLIC_KEY_URL` | Optional: where customers get the release signing key |
| `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` | Read-only credentials, or an instance or task role |

Give the portal its own credentials, separate from CI's write credentials:
`s3:ListBucket` on the bucket and `s3:GetObject` on its objects, nothing else (on R2, an API
token with *Object Read* on the bucket). The licensing portal's README has the full policy. Each
download is audited as `release.downloaded`, and a download without a license for the product is
refused and audited as `access.denied`.

Create the signing key once, offline, and publish only its public half:

```bash
gpg --quick-generate-key "ENS Solutions Release Signing <releases@ens-solutions.com>" ed25519 sign 3y
gpg --armor --export-secret-keys releases@ens-solutions.com   # paste into RELEASE_GPG_PRIVATE_KEY
gpg --armor --export releases@ens-solutions.com > ens-solutions-release.asc   # give to customers
gpg --fingerprint releases@ens-solutions.com                   # send separately, for them to check
```

This key signs downloads. It is separate from the license signing key the licensing portal uses
(see [Licensing](licensing.md)), and the two should never be the same key.

### Sending a customer links directly

The portal is the normal way. For a customer who cannot use it, `tools/release/presign.sh`
prints a link for every file in a release, valid for up to seven days:

```bash
export RELEASE_S3_BUCKET=ens-releases          # plus RELEASE_S3_ENDPOINT for R2/MinIO
tools/release/presign.sh readyroom             # the latest release
tools/release/presign.sh readyroom 1.4.0 86400 # a specific version, links valid for one day
```

Send the links together with the customer's license file. Customers who install with Helm, or
whose Docker host can pull from `ghcr.io/winllc`, still need the license file. A Helm install pulls
the images from the registry or a mirror; the bundle's `images/` archive can seed that mirror.
