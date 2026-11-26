# Installation

## At a glance

| Method | Use it for | You run |
|---|---|---|
| [Offline bundle](#install-from-the-bundle) | Any Linux distribution with systemd (glibc 2.34 or newer), x86_64 or aarch64, including hosts with no internet access | `install.sh`, then `syncdctl` |
| [Package](#install-from-a-package) | Debian and Ubuntu (`.deb`); RHEL, Rocky, Alma 9 and newer and Fedora (`.rpm`); RHEL, Rocky, Alma 8 (`.el8.rpm`). Downloaded from the ENS licensing portal; the signed apt and dnf repositories are not published yet | `apt` or `dnf`, then `syncdctl install` |
| [Helm chart](#install-with-helm) | Kubernetes, from the container images (amd64 and arm64; FIPS images for amd64) | `helm install` |

syncd has two parts:

- **The engine** (`syncd`). It reads the sources, merges identities into views and exports them
  to the target directories. It keeps its state in a store of its own on local disk.
- **The web console** (`syncd-console`), with its PostgreSQL database.

On a host, they run as two systemd services. Every form of a release installs the same files,
and `syncdctl` does the rest: service accounts, configuration, the control API's certificates,
the services, and registering the engine in the console.

On Kubernetes, the engine is a StatefulSet with one replica, or two as an
[active/passive pair](#an-active-passive-pair), and the console a Deployment. The chart issues
the certificates and registers the engine.

From 0.4.0 two engines can also run as an active/passive pair on two hosts, which from 0.5.0
`syncdctl ha` sets up (see [An active/passive pair on hosts](#an-activepassive-pair-on-hosts)).
How the pair works is in [High availability](availability-and-sizing.md#high-availability).

The password capture agent, for the hosts of the directory servers people change passwords in,
is a package of its own from 0.5.0, `syncd-capture` (see
[The capture agent's package](#the-capture-agents-package)). It is not needed on the engine's
host.

!!! note "A license, within 60 days"
    From 0.5.0 syncd checks a license from the ENS licensing portal. A new or upgraded
    installation runs a 30-day evaluation with everything enabled, then a 30-day grace period;
    after that, publishing configuration changes is refused until a license is installed. Sync
    itself never stops. Install it as in [The license](#the-license); the states, packs and
    managed identities are in [syncd licensing](licensing.md).

For the settings referred to here, see [Configuration](configuration.md). Day-to-day running is in
the [Operator guide](operator-guide.md).

## Prerequisites

The requirements for the [Helm chart](#install-with-helm) are listed with it.

| Requirement | Notes |
|---|---|
| **Linux with systemd** | x86_64 or aarch64. Releases are built for both, and from 0.5.0 the FIPS bundle too; the aarch64 files are missing only when the release was built with the arm64 jobs switched off (see [Getting the release](#getting-the-release)) |
| **glibc 2.34 or newer** | Releases are built on Rocky Linux 9, so they run on RHEL 9, Ubuntu 22.04, Debian 12 and anything newer. A release built elsewhere needs at least the build host's glibc. |
| **RHEL, Rocky or Alma 8** | Use the `.el8.rpm` (x86_64 only). It is built on Rocky Linux 8, and its build checks that the binaries need nothing newer than RHEL 8 has. RHEL 8's default PostgreSQL is 10, too old for the console: `sudo dnf module enable postgresql:16` first. |
| **PostgreSQL 13 or newer** | For the console. On the same host, or elsewhere. |
| **`openssl`** | Issues the control API's certificates at install time. The packages depend on it. |
| **PostgreSQL client tools** | `pg_dump`, `pg_restore` and `psql`, for backups. Upgrades refuse to run without them unless you pass `--skip-db-backup`; from 0.5.1 they check, before stopping anything, that `pg_dump` can dump the server (a `pg_dump` older than the server's major version cannot). The `.deb` recommends `postgresql-client`; the `.rpm` recommends `postgresql`. |
| **`curl`** *(recommended)* | For the health checks. Without it, `syncdctl` falls back to a bash TCP check. |
| **`git`** *(recommended)* | For the console's Git sync (see [Configuration as code](config-as-code.md)). From 0.4.0 the packages recommend it. Not needed otherwise |
| **Disk** | Room for the engine store, its history, and backups: see [Sizing](availability-and-sizing.md#sizing). An upgrade checks that `/var/lib/syncd/backups` has room for a backup before it stops anything. |
| **Java 17 or newer** *(only for the JDBC bridge)* | To read databases syncd has no driver of its own for (Db2, Informix, Oracle, ...). See [The JDBC bridge](#the-jdbc-bridge). |
| **The source and target systems** | Network access from the engine's host to the databases and directories it reads and writes, and the accounts it uses. See [Connectors](connectors.md). |

## Getting the release

Download the bundles and packages from the **Downloads** page of the ENS licensing portal, like
the other ENS products (see [Releases and downloads](../releases.md#downloading)): any account
that holds a syncd license, an evaluation included, can download them. The images and the Helm
chart come from the GitHub container registry
([below](#container-images-and-the-helm-chart)).

The release build (the `Package` workflow of the syncd source repository,
`winllc/ldap-sync-merge-view`) runs on every `v*` tag: it builds the bundles and the packages on
Rocky Linux 9 and Rocky Linux 8, runs the install, upgrade, rollback, backup and uninstall
lifecycle on each, checks the tag against the version in the source, and only then uploads the
files, with `SHA256SUMS`, to the portal's release bucket (`syncd/<version>/`), from which the
**Downloads** page serves them. With the release signing key set, it also signs `SHA256SUMS` and
the `.rpm` files and publishes the apt and dnf repositories.

!!! warning "0.5.0 to 0.6.1: unsigned, and no package repositories yet"
    0.5.0, 0.5.1, 0.5.4, 0.5.5, 0.6.0 and 0.6.1 are tagged (14, 15, 18, 19, 20 and 20
    November 2026), and their files are on the portal's **Downloads** page and on the source
    repository's GitHub releases. They were built without the release signing key: there is no
    `SHA256SUMS.asc`, the `.rpm` files are not signed, and the apt and dnf repositories were not
    published (`https://winllc.github.io/ldap-sync-merge-view` has none). Install and upgrade
    from the downloaded files, and check them against `SHA256SUMS` (see
    [Verifying a release](#verifying-a-release)), which shows that a file is complete, not who
    built it. From 0.5.5 the release job refuses to publish without the signing key unless the
    maintainers set the repository variable `ALLOW_UNSIGNED_RELEASE`; they did, for 0.5.5, 0.6.0
    and 0.6.1.

    - **0.6.1** (20 November) fixes the installer only. Its bundles, packages, SBOMs,
      `SHA256SUMS` and `TESTED-0.6.1.md` are published, with its notes on the GitHub release.
      **Its container images and Helm chart were not published**: the tag's `Container` run
      failed building the amd64 binaries, so it pushed and signed nothing. On Kubernetes, stay
      on chart and images 0.6.0, which lose nothing (0.6.1 changes only the host installer).
    - **0.6.0** (20 November) and **0.5.5** (19 November) are complete: bundles, packages,
      SBOMs, notes on the GitHub release, and images and charts on ghcr.io.
    - **0.5.4** is the first release with packages since 0.5.1. Its GitHub release was marked
      pre-release and carries no notes text: the notes are in the source repository.
    - **0.5.2** (17 November) was published as container images and a Helm chart only: its
      bundles and packages failed to build, so the portal has no files for it.
    - **There is no 0.5.3.** Its tag was made on a commit still at version 0.5.2, and nothing was
      published under it.
    - **0.5.0 and 0.5.1.** Their GitHub releases, marked pre-release, were made by hand; 0.5.1's
      holds only the images' SPDX SBOMs, not the bundles or the packages.

    The 0.6.0, 0.5.5, 0.5.4, 0.5.2 and 0.5.1 images and charts are on ghcr.io, signed with
    cosign.

| File | For |
|---|---|
| `syncd-<version>-linux-x86_64.tar.gz`, `syncd-<version>-linux-aarch64.tar.gz` | The offline bundle, for x86_64 and aarch64 (arm64): any distribution with systemd and glibc 2.34+, including hosts without internet access. Each with its `.sha256` |
| `syncd-<version>-linux-x86_64-fips.tar.gz`, `syncd-<version>-linux-aarch64-fips.tar.gz` | The FIPS bundle: the same release built with the FIPS 140-3 validated module, for `syncdctl install --fips`. Each with its `.sha256`; the aarch64 one is new in 0.5.0 |
| `syncd_<version>_amd64.deb`, `syncd_<version>_arm64.deb` | Debian and Ubuntu, through apt |
| `syncd-<version>-1.x86_64.rpm`, `syncd-<version>-1.aarch64.rpm` | RHEL, Rocky and Alma 9 and newer, and Fedora, through dnf |
| `syncd-<version>-1.el8.x86_64.rpm` | RHEL, Rocky and Alma 8, through dnf (x86_64 only) |
| `syncd-capture_<version>_amd64.deb`, `syncd-capture_<version>_arm64.deb`, `syncd-capture-<version>-1.x86_64.rpm`, `syncd-capture-<version>-1.aarch64.rpm`, `syncd-capture-<version>-1.el8.x86_64.rpm` | The password capture agent alone, for the directory servers' hosts (see [The capture agent's package](#the-capture-agents-package)). New in 0.5.0 |
| `syncd-<version>.cdx.json`, `syncd-console-<version>.cdx.json`, `syncd-capture-<version>.cdx.json` | The software bill of materials of the engine, of the console and of the capture agent (CycloneDX JSON) |
| `SHA256SUMS` | The checksums of every file above |
| `SHA256SUMS.asc` | The release signing key's GPG signature over `SHA256SUMS`. Not in 0.5.0 to 0.6.1, which are unsigned |
| `sbom-syncd-<version>[-fips]-<arch>.spdx.json`, `sbom-syncd-console-<version>[-fips]-<arch>.spdx.json` | The SPDX bill of materials of each container image and platform, attached to the source repository's GitHub release (not on the **Downloads** page). Not in `SHA256SUMS`: each is also a signed attestation on its image. New in 0.5.0; none for 0.6.1, whose images were not published |
| `TESTED-<version>.md` | What CI ran the release against, row by row with the results. On the source repository's GitHub release only, not in `SHA256SUMS` |

- **aarch64.** From 0.5.0 the aarch64 bundles and packages, and the arm64 images, are built by
  default. A release built with the source repository's variable `ARM64_RUNNERS` set to `false`
  is x86_64 only, and the release workflow's summary says so.
- **FIPS.** From 0.5.0 the FIPS bundle and the FIPS images are built for both architectures.
  There are no FIPS `.deb` or `.rpm` packages. See [FIPS 140-3 and DISA STIG](fips-and-stig.md).
- **Package repositories.** From 0.5.0 a release built with the signing key also publishes the
  `.deb` and `.rpm` files in signed apt and dnf repositories (see
  [From the package repositories](#from-the-package-repositories)). None has been published so
  far.
- The release notes are in the source repository, `docs/releases/<version>.md`, and from 0.5.5
  on the GitHub release too (those of 0.5.1, 0.5.2 and 0.5.4 carry none; 0.5.4's notes also
  cover 0.5.2). Which versions
  get fixes (the latest minor version only) and what a version number promises are in the source
  repository's `docs/SUPPORT.md`.

### Verifying a release

New in 0.4.0. Check what you downloaded before you install it. For 0.5.0 to 0.6.1, which
are unsigned, only step 2 applies: it shows that each file is complete and matches the `SHA256SUMS`
you downloaded with it, and the **Downloads** page shows each file's SHA-256 as well.

```bash
# 1. The signature: SHA256SUMS came from the release signing key (import the key once)
gpg --import syncd-release.asc
gpg --verify SHA256SUMS.asc SHA256SUMS

# 2. The checksums: each file you downloaded matches what was signed
sha256sum -c SHA256SUMS --ignore-missing
```

- **Both must succeed.** `gpg` reports a good signature from the syncd release key. Check the
  key's fingerprint against the one published in the source repository's
  `docs/RELEASE-KEY.md` (or one you were given separately), never against anything in the
  download itself. From 0.5.0 the public key (`syncd-release.asc`) is to be served with the
  package repositories, at `https://winllc.github.io/ldap-sync-merge-view/syncd-release.asc`; it
  is not one of the release's files. As no release has been signed yet, `RELEASE-KEY.md`
  publishes no fingerprint so far, and there is no key to import.
- **`.rpm` signatures.** From 0.5.0 the `.rpm` files of a signed release carry the same key's
  signature, which `dnf` checks when it installs from the repository. To check a downloaded
  file: `sudo rpm --import syncd-release.asc && rpm -K syncd-*.rpm` (`digests signatures OK`).
  The `.rpm` files of 0.5.0 to 0.6.1 carry no signature.
- **A release without `SHA256SUMS.asc`** was published without the signing key (the release
  workflow's summary says so), as 0.5.0 to 0.6.1 were. Its checksums still show that a file is
  complete, not who built it.
- **The bundle alone:** `sha256sum -c syncd-0.6.1-linux-x86_64.tar.gz.sha256`. Inside the bundle,
  every file is listed in `MANIFEST.sha256`, and the installer checks the release against it
  before it copies anything, so a damaged archive is refused even without these steps.
- **SBOMs.** The two CycloneDX files list the crates and the web console's packages each
  binary was built from, for your vulnerability scanner. They are covered by `SHA256SUMS`. The
  images carry SPDX SBOMs of their own as signed attestations (below); from 0.5.0 the same
  SBOMs are also release files, for tools that read a file (the attestation is the signed copy).

### Container images and the Helm chart

A second workflow (`Container`) builds the images and the Helm chart on every push, scans the
images, installs them in a kind cluster (one release, and an HA pair failed over on each lease
kind), and, on a `v*` tag only and once those have passed, pushes them to the GitHub container
registry and signs them. Its runs for the `v0.6.0`, `v0.5.5`, `v0.5.4`, `v0.5.2` and `v0.5.1`
tags pushed and signed those releases' images, their FIPS variants and the chart. Its run for
`v0.6.1` failed building the amd64 binaries and published nothing, so the newest images and
chart are 0.6.0's (0.6.1 changes only the host installer):

| What | Where |
|---|---|
| The engine's image | `ghcr.io/winllc/syncd:<version>`, and `ghcr.io/winllc/syncd:<version>-fips` |
| The console's image | `ghcr.io/winllc/syncd-console:<version>` (with the web UI, `git` and `ssh`), and `<version>-fips` |
| The Helm chart | `oci://ghcr.io/winllc/charts/syncd`, version `<version>` |

- **Tags.** Each release is tagged with its version, for example `0.6.0`, and its FIPS variant
  `0.6.0-fips`. There is no `latest`. The chart's version and `appVersion` are the release's.
- **Platforms.** A tag holds `linux/amd64` and `linux/arm64`, unless the release was built with
  `ARM64_RUNNERS` set to `false`. `docker buildx imagetools inspect
  ghcr.io/winllc/syncd:<version>` lists a tag's platforms. From 0.5.0 the `-fips` tags are built
  for the same platforms (0.4.1's were amd64 only).
- **FIPS images** are the same release built with the FIPS 140-3 module. Set
  `SYNCD_FIPS_MODE=true` in both containers to turn the mode on (see
  [FIPS images](#fips-images)). The standard images refuse `SYNCD_FIPS_MODE` at startup (`not a
  FIPS build`), so the mode is never a silent downgrade.
- **Base.** Distroless (`gcr.io/distroless/cc-debian12:nonroot`), pinned by digest in the
  Dockerfile: no shell and no package manager. Both images run as uid and gid 65532, and work on
  a read-only root file system.
- **Scanned.** Every build scans the images with Trivy, and a fixable HIGH or CRITICAL finding
  fails it. Accepted findings are listed, each with its reason and an end date, in the source
  repository's `.trivyignore`.
- **Signed.** On a release tag, each image and the chart are signed with cosign: keyless
  signatures (Sigstore), made with the release workflow's GitHub identity, always by digest. Each
  image also carries its SPDX SBOM (one per platform) as a signed attestation. The chart is not
  attached to the GitHub release.

### Verifying the images and the chart

```bash
# A tag's digest (the image index: every platform)
docker buildx imagetools inspect ghcr.io/winllc/syncd:0.6.0

# The image's signature, and its SBOM attestations
cosign verify ghcr.io/winllc/syncd@<digest> \
  --certificate-identity-regexp 'https://github.com/winllc/ldap-sync-merge-view/.github/workflows/.*' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
cosign verify-attestation --type spdxjson ghcr.io/winllc/syncd@<digest> \
  --certificate-identity-regexp 'https://github.com/winllc/ldap-sync-merge-view/.github/workflows/.*' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com

# The chart (its digest is the one `helm pull` prints)
cosign verify ghcr.io/winllc/charts/syncd@<digest> \
  --certificate-identity-regexp 'https://github.com/winllc/ldap-sync-merge-view/.github/workflows/.*' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
```

Do the same for `ghcr.io/winllc/syncd-console` and the `-fips` tags. The release workflow's
summary lists every digest it signed. cosign contacts Sigstore's public services to verify a
keyless signature, so on an air-gapped network, verify on a connected machine before you mirror
the images (see [Air-gapped clusters](#air-gapped-clusters)).

**Images you build yourself**, from the source repository:

```bash
docker build -f deploy/container/Dockerfile --target engine -t registry.example.com/syncd:0.6.1 .
docker build -f deploy/container/Dockerfile --target console -t registry.example.com/syncd-console:0.6.1 .
# The FIPS variant
docker build -f deploy/container/Dockerfile --target engine -t registry.example.com/syncd:0.6.1-fips \
  --build-arg FEATURES=syncd/fips,syncd-console/fips .
```

The build is for the host's platform. Push the images to a registry your cluster can pull from,
then set `image.engine.repository`, `image.console.repository` and their `tag` in the chart's
values, and install the chart from the checkout: `helm install syncd ./deploy/helm/syncd ...` in
place of the `oci://` address below.

!!! note "The ENS licensing portal"
    From 0.5.0 syncd's files are on the portal's **Downloads** page, as the other ENS
    applications' are, but its bundles and packages are its own (no `<product>-<version>.tgz`
    chart file: the chart is on ghcr.io), and a signed release's `SHA256SUMS` is signed with
    syncd's own release key, not the ENS release key. [Releases and downloads](../releases.md)
    describes the portal and the signatures for the other products. syncd's **license** comes
    from the portal too (see [The license](#the-license)).

**Builds you make yourself.** `scripts/package.sh` in the source repository builds the bundle, the
`.deb` and the `.rpm` into `dist/`, for the host's architecture (on an aarch64 host,
`syncd-<version>-linux-aarch64.tar.gz`, `syncd_<version>_arm64.deb` and
`syncd-<version>-1.aarch64.rpm`), and `scripts/package-el8.sh` the RHEL 8 `.rpm`. From 0.5.0
each also builds the `syncd-capture` package from the same build. A FIPS build
(`deploy/packaging/build.sh --fips --packages`) has `-fips` in its name and a `FIPS` marker file.
See [FIPS 140-3 and DISA STIG](fips-and-stig.md). A build from source without the release's
license key accepts only development licenses and says `DEVELOPMENT LICENSE KEY` (see
[syncd licensing](licensing.md#release-builds-and-the-development-key)): don't run one in
production.

## Install from the bundle

```bash
sha256sum -c syncd-0.6.1-linux-x86_64.tar.gz.sha256
tar -xzf syncd-0.6.1-linux-x86_64.tar.gz
sudo ./syncd-0.6.1-linux-x86_64/install.sh --local-database
```

On aarch64, use `syncd-0.6.1-linux-aarch64.tar.gz`. For FIPS mode, use
`syncd-0.6.1-linux-x86_64-fips.tar.gz` (or, from 0.5.0, `syncd-0.6.1-linux-aarch64-fips.tar.gz`)
and add `--fips`.

`install.sh` runs `bin/syncdctl install` with the options you give it. On a host where syncd is
already installed (`/etc/syncd/console.env` exists), it runs `syncdctl upgrade` instead (see
[Upgrade](#upgrade)).

### The console's database

Choose one:

- **`--local-database`** sets the database up on this host's PostgreSQL server:
    - it creates a role named `syncd-console` and a database named `syncd_console`;
    - the console signs in through the local socket with peer authentication, so no password is
      stored;
    - it runs `psql` as the `postgres` system account against `/var/run/postgresql`, port 5432.
      Set `SYNCD_PG_SOCKET` and `SYNCD_PG_PORT` for another socket directory or port.
- **`--database-url-file FILE`** (since 0.5.5) uses a database server elsewhere. Create the
  database and its account first, then pass its URL in a file, on its first line. A URL with
  a password does not belong on the command line: every account on the host can read a
  running command's arguments (`ps`), and the shell's history keeps them.

    ```bash
    sudo sh -c 'umask 077 && cat >/root/syncd-database.url'    # paste the URL, then Ctrl-D:
    # postgres://syncd:secret@db.example.com/syncd_console?sslmode=require
    sudo ./install.sh --database-url-file /root/syncd-database.url
    sudo rm /root/syncd-database.url
    ```

- **`--database-url URL`** takes the URL itself, for one without a password. syncdctl gives
  PostgreSQL's tools (`pg_dump` for backups, `psql`) the URL without its password, and the
  password in their environment.

The URL is written to `CONSOLE_DATABASE_URL` in `/etc/syncd/console.env`, which only root and the
`syncd-console` account can read.

### syncdctl install options

| Option | Effect |
|---|---|
| `--local-database` | Creates the console's role and database on this host's PostgreSQL (above) |
| `--database-url-file FILE` | Uses the PostgreSQL database whose URL is on the file's first line (since 0.5.5; keeps a password off the command line) |
| `--database-url URL` | Uses this PostgreSQL database for the console; for a URL without a password |
| `--listen ADDR` | The console's address. Default `127.0.0.1:8080`. For other machines, give the console a certificate or put nginx in front (see [HTTPS in front of the console](#https-in-front-of-the-console)) |
| `--fips` | Runs both services with the FIPS 140-3 module (`SYNCD_FIPS_MODE=true` in both environment files). Refused unless the release is a FIPS build (the FIPS bundle) |
| `--no-start` | Installs everything but starts nothing. `syncdctl register` then starts the services and registers the engine in the console (running the installer again does the same) |

One of `--local-database`, `--database-url-file` and `--database-url` is required. `install` is refused on a host that
already has syncd; use `syncdctl upgrade` there.

### What the installer does

1. Checks the release against its `MANIFEST.sha256`.
2. Creates the `syncd` and `syncd-console` system accounts (no login shell, home
   `/var/lib/syncd`).
3. Copies the release to `/opt/syncd/releases/<version>` and points `/opt/syncd/current` at it.
   Links `syncd`, `syncd-console` and `syncdctl` into `/usr/local/bin`.
4. With `--local-database`, creates the role and database.
5. Writes the configuration to `/etc/syncd`: `console.env`, `engine.env` and
   `engine/engine.yaml`. Files that already exist are kept. From 0.5.0 the new `engine.yaml`
   gives the control API strict access: the console's certificate applies configurations, the
   command line's reads and operates (see [Control API access](#control-api-access)).
6. Issues the control API's certificates from a CA of the host's own, in `/etc/syncd/pki`. The
   engine and the console talk to each other over mutual TLS, even on loopback.
7. Installs the systemd units, enables and starts both services, and waits for both to be
   healthy (120 seconds; `SYNCD_HEALTH_TIMEOUT` changes it).
8. Registers the engine in the console, as `local` at `127.0.0.1:7443`.

At the end it prints the console's address and the `admin` account's one-time password (see
[First sign-in](#first-sign-in)).

**An installation that did not finish.** When the services do not start, the installer stops
there and says so: once the cause is fixed, `sudo syncdctl register` (or the installer run
again) finishes steps 7 and 8. Until then `syncdctl status` says the installation is not
finished. From 0.6.1 an installation that stopped earlier, at step 6 (`openssl` failing while
it issued the certificates), is finished the same way: that run issues the certificates that
are missing and installs the systemd units the first run did not, with the configuration the
first run wrote. `openssl`'s error is now shown, naming which certificate it was making;
0.6.0 and 0.5.5 sent it to `/dev/null` and ended with nothing but `==> installing syncd
<version>`, and running the installer again only tried to start services that had no keys or
units. The installer finishes an installation with its own release, a newer one's included
(syncd never ran there, so there is nothing to upgrade), and a FIPS installation with a FIPS
build only. Under the CA `syncdctl` made (`/etc/syncd/pki/ca.key`) only missing certificates
are issued; certificates you put there yourself (a `ca.crt` without `syncdctl`'s `ca.key`) are
left as they are.

## Install from a package

```bash
sudo apt install ./syncd_0.6.1_amd64.deb            # Debian, Ubuntu (arm64: syncd_0.6.1_arm64.deb)
sudo dnf install ./syncd-0.6.1-1.x86_64.rpm         # RHEL, Rocky, Alma 9+, Fedora (aarch64: syncd-0.6.1-1.aarch64.rpm)
sudo dnf install ./syncd-0.6.1-1.el8.x86_64.rpm     # RHEL, Rocky, Alma 8
sudo syncdctl install --local-database              # or --database-url ...
```

The package unpacks the release to `/opt/syncd/releases/<version>`, installs the units in
`/usr/lib/systemd/system`, links the commands into `/usr/bin`, creates the service accounts, and
says how to finish. `syncdctl install`, with the [same options](#syncdctl-install-options), then
does the rest as with the bundle. From 0.4.0 the packages recommend `git`, which the console's Git
sync needs; apt installs recommended packages by default, dnf when its `install_weak_deps` is on.

A host installed from a package is upgraded and removed with the package manager. `syncdctl
upgrade`, `rollback` and `uninstall` refuse to run there and say so; `backup`, `restore` and
`status` work the same as on a bundle install. There are no FIPS packages: use the FIPS bundle for
FIPS mode.

### From the package repositories

New in 0.5.0. The same packages are in apt and dnf repositories, so that an upgrade is `apt
upgrade` or `dnf upgrade` rather than a download. They are served by GitHub Pages at
`https://winllc.github.io/ldap-sync-merge-view`, hold the newest three releases (never
pre-releases), and are signed with the release key: check its fingerprint against
`docs/RELEASE-KEY.md` in the source repository before you trust it. They exist from the first
release published with the signing key and with GitHub Pages set up; until then the address
answers 404 (see [Getting the release](#getting-the-release)).

!!! warning "Not published yet"
    0.5.0 to 0.6.1 were released without the signing key, so no repository has been published
    and the commands below do not work yet. Install and upgrade from the files on the portal's
    **Downloads** page, as in [Install from a package](#install-from-a-package).

=== "Debian, Ubuntu (amd64, arm64)"

    ```bash
    curl -fsSL https://winllc.github.io/ldap-sync-merge-view/syncd-release.gpg |
      sudo tee /usr/share/keyrings/syncd-release.gpg >/dev/null
    gpg --show-keys --fingerprint /usr/share/keyrings/syncd-release.gpg   # compare with RELEASE-KEY.md
    sudo curl -fsSL -o /etc/apt/sources.list.d/syncd.sources \
      https://winllc.github.io/ldap-sync-merge-view/syncd.sources
    sudo apt update && sudo apt install syncd
    sudo syncdctl install --local-database        # or --database-url ...
    ```

=== "RHEL, Rocky, Alma 9+, Fedora (x86_64, aarch64)"

    ```bash
    sudo curl -fsSL -o /etc/yum.repos.d/syncd.repo \
      https://winllc.github.io/ldap-sync-merge-view/syncd.repo
    sudo dnf install syncd        # dnf shows the key's fingerprint and asks: compare with RELEASE-KEY.md
    sudo syncdctl install --local-database
    ```

    On RHEL, Rocky and Alma 8 (x86_64), use `syncd-el8.repo`. RHEL 8's `rpm` checks RSA
    signatures only: if the release key is of another kind, the published `syncd-el8.repo` says
    `gpgcheck=0`, and the packages are still covered by the signed repository metadata, which
    holds their checksums.

`syncd.repo` sets `gpgcheck=1` (each package's signature) and `repo_gpgcheck=1` (the repository's
metadata). On a host without internet access, mirror the repository's directory tree (static
files), or keep installing from the downloaded package or the bundle. The repositories were
tested in CI with stand-in packages and a throwaway key only; the published ones have not been
installed from yet.

### The capture agent's package

New in 0.5.0. `syncd-capture` is the password capture agent alone, for the hosts of the directory
servers people change their passwords in (OpenLDAP, 389 Directory Server). It is not needed on the
engine's host, and the bundle's `install.sh` is not for a directory server:

```bash
sudo apt install ./syncd-capture_0.6.1_amd64.deb       # from the repositories, once published: apt install syncd-capture
sudo dnf install ./syncd-capture-0.6.1-1.x86_64.rpm    # from the repositories, once published: dnf install syncd-capture
                                                       # RHEL 8: ./syncd-capture-0.6.1-1.el8.x86_64.rpm
```

It installs the agent (`/usr/bin/syncd-capture`), a sample `/etc/syncd-capture/capture.conf`
(kept across upgrades), its systemd unit, a `syncd-capture` account with its state directory
`/var/lib/syncd-capture` (mode 0700: the enrollment and the spool of sealed changes), and, under
`/usr/share/syncd-capture`, the README and the directory hooks' source. It starts nothing: enroll
the agent with the engine first, then enable it. An upgrade of the package restarts a running
agent; removing it keeps the enrollment and the settings (`apt purge` removes them). The hooks are not shipped compiled: build the
OpenLDAP overlay against the installed slapd's source, or the 389 plugin against the server's
headers, on that host, and again when the directory server is upgraded. Nothing for Active
Directory is packaged: the Windows password filter is source only, and compile-checked only. The
steps are in [Password synchronisation](password-sync.md#capture-agents). The packaged agent has
not been started under systemd in the project's tests.

## HTTPS in front of the console

The console serves plain HTTP on `127.0.0.1:8080`. Its session cookie is `Secure`, so browsers
keep it only over HTTPS, or on `localhost`. Opened at `http://<server>:8080` from another machine,
sign-in cannot stick, and the sign-in page says so. To use the console from other machines, give
it HTTPS, in one of two ways:

| | [The console's own HTTPS](#the-consoles-own-https) | [nginx in front](#nginx-in-front-of-the-console) |
|---|---|---|
| Port | 1024 or above (for example 8443): the console doesn't run as root | 443 |
| Certificate | PEM files the console reads, picked up again within a minute when renewed | nginx's |
| HTTP/2, a certificate shared with other sites | No (HTTP/1.1 only) | Yes |
| Extra software | None | nginx |

### The console's own HTTPS

The console serves HTTPS itself when it is given a certificate. In `/etc/syncd/console.env`:

```bash
CONSOLE_LISTEN=0.0.0.0:8443
# The certificate, followed by its chain
CONSOLE_TLS_CERT=/etc/syncd/console-tls/fullchain.pem
CONSOLE_TLS_KEY=/etc/syncd/console-tls/privkey.pem
# For single sign-on
CONSOLE_PUBLIC_URL=https://syncd.example.com:8443
```

!!! warning "Comments in `console.env` go on lines of their own"
    systemd reads `console.env` as an `EnvironmentFile`, which takes everything after the `=` as
    the value, a `# ...` at the end of the line included. From 0.4.1 the release's templates
    (`/opt/syncd/current/share/config/console.env` and `engine.env`) keep every note on a line
    of its own. Your `/etc/syncd/console.env` is written at install only, so a host installed
    with 0.4.0 or earlier may still have commented lines that end with a note, for example
    `#CONSOLE_PUBLISH_HOOK_ON_ERROR=refuse   # or allow`. Uncommenting one as it is sets a wrong
    value, and the console refuses it or fails to start; from 0.4.1 its error then adds
    `` <setting> holds ` #` (a comment at the end of the line becomes part of the value) ``. Move
    the note to a line of its own when you uncomment a line. The same holds for `engine.env`,
    though the engine gives no such hint.

```bash
sudo chown root:syncd-console /etc/syncd/console-tls/*
sudo chmod 0640 /etc/syncd/console-tls/privkey.pem
sudo firewall-cmd --permanent --add-port=8443/tcp && sudo firewall-cmd --reload   # RHEL
sudo systemctl restart syncd-console
```

- **Both or neither.** `CONSOLE_TLS_CERT` and `CONSOLE_TLS_KEY` go together; the console refuses
  to start with one alone. It also refuses a file it cannot read, a certificate file with no
  certificate in it, and a key that does not go with the certificate, and names the file.
- **Renewals** (certbot's deploy hook copying the new files there, say) are picked up within a
  minute, without a restart, and logged as `TLS certificate loaded again`. A renewal that does
  not load is logged (`the changed TLS certificate does not load; keeping the previous one`), and
  the previous certificate stays in use.
- **Connections.** Each TLS handshake has 10 seconds, and a client that stalls holds up no other.
  The console offers HTTP/1.1 only.
- **`syncdctl`'s health check.** From 0.5.1, with `CONSOLE_TLS_CERT` set, `syncdctl` checks the
  console's health over HTTPS on this host (`https://127.0.0.1:8443/healthz` here) when an
  upgrade, a rollback, a restore or another command restarts it. It does not check the
  certificate, which names the public address: the check sends nothing but the request, to this
  host. 0.5.0's `syncdctl` asked over plain HTTP, which a console serving HTTPS does not answer.
- **Health.** Since 0.5.5 `/healthz` answers `ok`, or 503 naming each background task (the mail
  sender, the webhook sender, the audit outputs, the engine watcher, ...) that keeps stopping,
  waits to start again or says it cannot work: point monitoring at it. `/livez` answers `ok`
  while the console serves requests, whatever its tasks do; the Helm chart's probes use it, so
  a setting the console cannot use does not take its pages out of service.
- **`CONSOLE_TRUSTED_PROXIES`** is not needed: clients connect to the console directly, and the
  audit trail records their addresses.
- **HSTS.** From 0.5.0 the console sends `Strict-Transport-Security` when it serves HTTPS
  itself: `ENS_HSTS_MAX_AGE` seconds, a year by default (`0` sends none), and
  `ENS_HSTS_INCLUDE_SUBDOMAINS`. Behind nginx, nginx sends it.
- **Client certificates (CAC/PIV).** From 0.5.0 the console can sign people in with a client
  certificate, directly on its own HTTPS, or behind nginx through a header (the shipped nginx
  file has the lines to uncomment, with `ENS_X509_MODE=proxy`). See
  [Single sign-on](single-sign-on.md).
- **Older hosts.** A host installed before 0.3.0 keeps its `console.env`, so the commented
  `CONSOLE_TLS_CERT` and `CONSOLE_TLS_KEY` lines of a new install's file are not in it. Add the
  settings yourself.

### nginx in front of the console

For port 443, a certificate shared with other sites, or HTTP/2, put nginx with TLS in front of the
console. The release ships an nginx configuration in `/opt/syncd/current/share/nginx/syncd.conf`.

Below, replace `syncd.example.com` and `192.0.2.10` with the server's name and address.

**1. Install nginx** and open HTTPS in the firewall.

=== "RHEL, Rocky, Alma 8 and 9"

    ```bash
    sudo dnf install -y nginx
    sudo setsebool -P httpd_can_network_connect 1     # SELinux: let nginx reach the console
    sudo firewall-cmd --permanent --add-service=https --add-service=http
    sudo firewall-cmd --reload
    ```

=== "Ubuntu, Debian"

    ```bash
    sudo apt-get install -y nginx
    sudo rm -f /etc/nginx/sites-enabled/default
    sudo ufw allow 'Nginx Full'                        # if ufw is enabled
    ```

**2. Create a certificate.** With a certificate from your CA, copy it and its key to
`/etc/nginx/tls/syncd.crt` and `/etc/nginx/tls/syncd.key`. For a self-signed one, list every name
and address people will type (browsers check `subjectAltName`):

```bash
sudo install -d -m 0750 /etc/nginx/tls
sudo openssl req -x509 -newkey rsa:2048 -nodes -days 825 \
  -subj "/CN=syncd.example.com" \
  -addext "subjectAltName=DNS:syncd.example.com,IP:192.0.2.10" \
  -keyout /etc/nginx/tls/syncd.key -out /etc/nginx/tls/syncd.crt
sudo chmod 600 /etc/nginx/tls/syncd.key
```

**3. Install the nginx configuration**, with your names in `server_name`:

```bash
sudo cp /opt/syncd/current/share/nginx/syncd.conf /etc/nginx/conf.d/syncd.conf
sudo sed -i 's/server_name syncd.example.com;/server_name syncd.example.com 192.0.2.10;/' \
  /etc/nginx/conf.d/syncd.conf
sudo nginx -t && sudo systemctl enable --now nginx && sudo systemctl reload nginx
```

The shipped configuration redirects port 80 to HTTPS, allows TLS 1.2 and 1.3, accepts request
bodies up to 2 MB (configuration imports), sets `X-Forwarded-For` to the client's address
(replacing whatever the client sent), and turns buffering off with a one-hour read timeout, for the
live status stream (server-sent events). From 0.5.0 it also sends HSTS (`max-age` a year), and
has commented lines for certificate sign-in through the proxy.

**4. Tell the console about the proxy** in `/etc/syncd/console.env`, then restart it:

```bash
# Reachable through nginx only
CONSOLE_LISTEN=127.0.0.1:8080
# Also the base of the single sign-on redirect URI
CONSOLE_PUBLIC_URL=https://syncd.example.com
# Believe nginx's X-Forwarded-For
CONSOLE_TRUSTED_PROXIES=127.0.0.1
```

```bash
sudo systemctl restart syncd-console
```

`CONSOLE_TRUSTED_PROXIES` lists the proxies (addresses or CIDR ranges, comma-separated) whose
`X-Forwarded-For` header names the client. With it, the audit trail records users' own addresses,
and the sign-in limit (20 attempts per address in 5 minutes) applies to each user. Without it,
every request seems to come from nginx: the audit trail shows `127.0.0.1`, and all users share one
sign-in budget. List only proxies you run; a request from anywhere else is taken at its own
address, whatever header it sends.

**5. Check it:**

```bash
curl -k -o /dev/null -w '%{http_code}\n' https://syncd.example.com/   # 200
sudo syncdctl status                                                   # users: https://...
```

Then open `https://syncd.example.com`. With a self-signed certificate the browser warns once;
accept it, or import `/etc/nginx/tls/syncd.crt` into the clients' trusted certificates (Windows:
`certmgr.msc`, Trusted Root Certification Authorities; macOS: Keychain Access, Always Trust; Linux:
copy it to `/usr/local/share/ca-certificates/` and run `update-ca-certificates`).

!!! warning "Listening beyond loopback without HTTPS"
    If `CONSOLE_LISTEN` is not a loopback address, the console has no certificate of its own, and
    `CONSOLE_PUBLIC_URL` is not an `https://` address, the console logs a warning at startup:
    browsers on other machines will not keep its session cookie. It still starts.

## First sign-in

1. Open the console (through nginx, or `http://127.0.0.1:8080` on the host itself).
2. Sign in as `admin` with the one-time password the installer printed. It is also in
   `/var/lib/syncd/console/initial-admin-password` (mode 0600, readable by `syncd-console` only)
   until it has been used.
3. Set a new password. Nothing else works until you do. A password needs at least 12 characters,
   and may not be the username or `admin` (from 0.5.0 the sign-in policy's `ENS_PASSWORD_*`
   settings can ask for more). Once it is changed, the file is deleted.

An installer can supply the first password instead, with `CONSOLE_ADMIN_PASSWORD` or
`CONSOLE_ADMIN_PASSWORD_FILE` in `console.env` before the first start. `admin` and empty passwords
are refused, and so is one that breaks the password rule: the console does not start.

If the account locked, or the password is lost, issue a new one-time password on the host:

```bash
sudo runuser -u syncd-console -- env -C / \
  $(sudo grep -E '^[A-Za-z_][A-Za-z0-9_]*=' /etc/syncd/console.env) \
  CONSOLE_DATA_DIR=/var/lib/syncd/console \
  /opt/syncd/current/bin/syncd-console admin reset-password --user admin
```

It prints the password, unlocks the account, signs out its sessions and records
`user.password_reset` in the audit trail. It refuses an account that signs in through single
sign-on, as the **Users** page does: a password would let it sign in without the identity
provider and its MFA. To give one anyway, while the provider is unavailable say, add
`--allow-single-sign-on`; the audit record then notes `single_sign_on: true`. On Kubernetes, run
the same command in the console's pod (see [Install with Helm](#install-with-helm)). Next steps:
add accounts and roles, and set up [single sign-on](single-sign-on.md) if you use it
([Admin guide](admin-guide.md)).

## The license

New in 0.5.0. syncd needs a license file from the ENS licensing portal; a new installation
evaluates with everything enabled for 30 days from its first start, then has a 30-day grace
period, after which publishing configuration changes is refused. Sync never stops. Request the
license with the **installation id**, which the console's **License** page shows (or
`sudo syncdctl license status` on the console's host), then install it one of these ways:

| Where | How |
|---|---|
| The console, by hand | Upload it on the **License** page (administrators with a role on everything) |
| The console's host, as a file | `sudo syncdctl license install L-0001.lic`: checks the file with this release's keys, installs it as `/etc/syncd/license.lic` (root, readable by `syncd-console`), sets `CONSOLE_LICENSE_FILE` in `/etc/syncd/console.env` and restarts the console. A renewal is the same command again. Suits air-gapped hosts |
| Kubernetes | A Secret named in `console.license.existingSecret` (see [The license on Kubernetes](#the-license-on-kubernetes)) |
| An engine without a console | `engine.license_file` in its `engine.yaml` (relative to that file, readable by `syncd`, read every minute). `syncd license show` gives the state and the installation id; `syncd license verify FILE` checks a file first |

The console gives its engines the license. What the states, packs and managed identities mean,
and what a block does and does not stop, are in [syncd licensing](licensing.md). `syncdctl
license` was not run on a packaged host before 0.5.0 was released.

## Registering engines

The installer registers the engine on the same host as `local`. A console can manage more engines:
an administrator registers each one under **Engines** → **Register engine**, with:

| Field | |
|---|---|
| Name | 1 to 64 letters, digits, `.`, `_` or `-` |
| Control API address | `host:port`, as in the engine's `api.listen`; or `unix:` and the absolute path of its control socket (`unix:/run/syncd/control.sock`, as in `api.control_socket`) |
| Engine CA certificate, console client certificate and key (PEM) | For mutual TLS. Required unless the address is on loopback or a `unix:` socket, and refused with a `unix:` address. The key is stored encrypted with the console's master key and never shown again |

The console checks that the engine answers before it saves it, and records `engine.register` in
the audit trail. On the host, `syncd-console admin register-engine --name N --address HOST:PORT
--server-ca FILE --client-cert FILE --client-key FILE` does the same. With an engine of that name
registered already:

- **Without `--update`** it does nothing (`engine N is registered already`). From 0.4.0 it fails
  instead when the stored client key no longer opens with the console's master key (the key was
  replaced), and says why: `engine N is registered, but this console's master key cannot open its
  stored client key`. `POST /api/v1/engines` answers the same, with `400`.
- **With `--update`** (`replace: true` through the API) it replaces that engine's address and
  credentials, keeping its id, configuration versions and environment, and records
  `engine.update`. When the stored key could not be opened, it seals the new one over it and
  records `engine.re-registered`. `syncdctl renew-certs` and the Helm chart's registration Job
  use it.
- **`--environment ENV`** sets the environment the engine runs in (see
  [Configuration as code](config-as-code.md)): `default` for a new engine unless given. With
  `--update`, the engine keeps the environment it has unless `--environment` is given. In 0.4.0,
  `--update` without it moved the engine back to `default`, so a `renew-certs` or a
  `helm upgrade` did.

`syncd-console admin rename-engine --from A --to B` renames a registered engine, keeping its id,
configuration versions, reports and history; it does nothing when no engine is called `A`, or
one is called `B` already, and records `engine.renamed`. New in 0.4.1; the Helm chart runs it when
HA is turned on (see [An active/passive pair](#an-active-passive-pair)). It does not change engine
names in the console's settings (`CONSOLE_SELF_SERVICE_ENGINE`, `CONSOLE_CONFIG_GIT_ENGINES`):
change those by hand. From 0.5.0 the console warns about such a setting that names no registered
engine, at start, at `rename-engine` and on the **Engines** page, and a running console picks up
a `register-engine --update` or `rename-engine` made by another process without a restart (0.4.1
kept the old address and certificates until it was restarted). From 0.5.0 an engine's name is
also what scoped roles name (`operator@engine:hr-1`), and a rename renames those assignments
too.

A `unix:` address only works for a console on the engine's host, running as a user that may open
the socket. The socket is mode 0600, owned by the engine's user, so the packaged
`syncd-console` account cannot open it: the installer keeps registering the engine at
`127.0.0.1:7443` with mutual TLS.

The engine's page in the console warns when the console's client certificate for the engine has
less than 60 days left, and when it has expired. See
[Certificates](operator-guide.md#certificates) for renewing them.

!!! note "Engines on other hosts"
    The installed engine's control API listens on `127.0.0.1:7443`, and its certificate (from the
    host's own CA) names only `localhost`, `127.0.0.1` and `::1`. For a console on another host to
    manage it, the engine needs an `api.listen` that host can reach, and a certificate for the name
    the console uses. `api` can only be changed in the engine's file on its host (see
    [Configuration](configuration.md)). syncdctl issues such certificates only for the engines
    of a pair (`syncdctl ha`, below), and otherwise `syncdctl renew-certs` renews them for
    `localhost` only.

## Control API access

New in 0.5.0. Every call to the engine's control API belongs to a tier, each including the one
before: `read` (status, lists, simulations, reports), `operate` (runs, approvals, dead-letter
actions, joins, backups and HA snapshots, revealing values), and `configure` (applying a
configuration). Setting passwords through the API (`syncd password set`, adding or binding a
capture agent) needs `passwords` on top of `operate`, which no certificate has by default; on the
engine's host, `sudo syncd password ...` goes through the control socket, which may.

From 0.5.1, on an engine that holds password keys (a password key or the agents' CA on disk),
what hands them out needs `passwords` too: backups, HA snapshots and following the password
queue (`Backup`, `PullSnapshot`, `PullSnapshotFiles`, `FollowPasswordQueue`). In strict mode
`syncd backup` and `syncdctl backup --to` on such an engine are then served only through the
control socket: uncomment `api.control_socket` in `/etc/syncd/engine/engine.yaml` and restart the
engine (`syncdctl backup --to` runs `syncd backup` as the engine's user, which opens it). The
plain-text loopback listener, without `api.tls`, serves backups, snapshots and the password
queue only with both `api.local_control` and `api.local_reveal`; `local_reveal` alone no longer
does.
`api.access` in `engine.yaml` names which
client certificate (by common name, or a `uri:` subject alternative name) has which tier; the
settings are in [Configuration](configuration.md#control-api-api), and the reasons in
[Security](security.md#the-engine-control-api).

| Install | Access | What it means |
|---|---|---|
| New in 0.5.0 (the installer's `engine.yaml`, the container image's) | `mode: strict` | The console's certificate (`syncd console`) applies configurations; the command line's (`cli.crt`, `syncd command line`, readable by the `syncd` group) and the control socket read and operate. `sudo syncd apply-config` is refused (`ApplyConfig needs the configure tier`): configurations go through the console, its approvals and its gates |
| Upgraded from 0.4.1 or earlier | No `access` section: compatible | Every certificate the client CA signed may do everything, as before, including `cli.crt`. The engine warns at every start, `syncd status` says `control API access: COMPATIBLE`, and the console warns on the engine's page |

**Opting an upgraded host in:**

```bash
sudo syncdctl api-access strict --dry-run      # what it would change
sudo syncdctl api-access strict
```

It adds the new-install `access` section to the `api` section of
`/etc/syncd/engine/engine.yaml`, checks it with `syncd validate`, restarts the engine, and checks
that `syncd status` says `control API access: strict`; otherwise it puts the previous file back
(a copy stays in `/etc/syncd/engine/before-api-access-<time>/`). A second run changes nothing.
Then, in the console, **import the running configuration** on the engine's **Configuration**
page before the next publish: the engine refuses a published configuration that changes its
`api` section. With an HA pair, do it on both hosts. The command does not edit an `api` section
written on one line (it says so); there is no command back to compatible mode other than the
saved copy. `sudo syncdctl api-access status` shows the engine's lines on access.

**Signed configurations.** With `engine.require_signed_config`, the engine applies only a
configuration the console signed for it (ECDSA P-256, bound to a value the engine issues anew at
every start and reload), whoever sends it. On a host whose console is local:

```bash
sudo syncdctl api-access signing-key --from-console             # trust the console's key
sudo syncdctl api-access signing-key --from-console --require   # and apply nothing unsigned
```

The first installs the key as `/etc/syncd/pki/console-signing.pub` and names it in
`engine.config_signing_keys`; signed publishes then show as `signed by key <id>` in `syncd
status`. Publish once from the console in between, then add `--require`, and import the running
configuration in the console again. For a console on another host, write its key with
`syncd-console admin config-signing-key --out FILE` there and give the file to `syncdctl
api-access signing-key FILE`. On Kubernetes, see
[Control API access on Kubernetes](#control-api-access-on-kubernetes).

`engine.data_dir`, `engine.ha`, `engine.require_signed_config` with `engine.config_signing_keys`,
`engine.license_file` and the `api` section can only be changed on the engine's host. Neither
tiers nor signatures protect against root on the engine's host, who can edit the configuration
file. The console reports a running configuration it did not publish (`config.unpublished-running`
in the audit trail and as a webhook event).

## An active/passive pair on hosts

New in 0.4.0. Two engines on two hosts, with the same configuration, share a lease in PostgreSQL
(the console's database works) or in a file on a shared file system (or, on Kubernetes, from
0.5.0, a Lease object). The one holding it runs the sources and the exports; the other keeps a
warm copy of the store from snapshots, follows the queued password changes, and takes over when
the lease expires. How it works, its failover window and its limits are in
[High availability](availability-and-sizing.md#high-availability); running it is in the
[Operator guide](operator-guide.md#high-availability).

### Setting up a pair with syncdctl

New in 0.5.0. On two hosts installed as usual (`syncdctl install` on each, from the bundle or the
packages):

```bash
# On the first host: the lease is a PostgreSQL database both hosts reach (the console's works
# when both reach it), or --lease-path FILE on a file system both share.
sudo syncdctl ha init --name idm \
  --lease-url 'postgres://syncd:secret@db.example.gov/syncd_console?sslmode=require' \
  --advertise idm1.example.gov

# Copy /root/syncd-ha-idm.join to the second host over a channel you trust, then there:
sudo syncdctl ha join syncd-ha-idm.join --advertise idm2.example.gov

# Delete the join file on both hosts. On the host whose console manages the pair, register
# the other host's engine (the console shows the two as one pair):
sudo syncdctl ha register idm2 idm2.example.gov:7443
```

- **What they do.** `ha init` keeps the first host's CA and writes it, with its key, into the join
  file; `ha join` puts it in place of the second host's, issues the console's and the command
  line's certificates from it, and gives the second host's console its new credentials. Each
  issues an engine certificate for `localhost` and the `--advertise` name (which `renew-certs`
  keeps from then on, reading `SYNCD_HA_ADVERTISE`), sets `api.listen: 0.0.0.0:7443`, writes the
  pair's `SYNCD_HA_*` settings to `/etc/syncd/engine.env` (`engine.yaml` says nothing of the pair,
  so one published configuration suits both), restarts the engine and waits for `syncd status`
  to show the lease held. If the engine does not start, the previous certificates, `engine.yaml`
  and `engine.env` are put back (kept in `/etc/syncd/pki/before-ha-<time>`); from 0.5.1, on the
  first host, also when the engine does not see the lease, so that it goes on running alone as
  before.
- **Options.** `--advertise` is the name the other engine reaches this host's API by (the host's
  fully qualified name by default); `--member` this engine's name in the pair (the host name):
  give each engine its own. `--ttl` (15) and `--snapshot-every` (60) go to `ha init` and are
  carried to the second host. `--dry-run` shows what would change; both ask before changing
  anything (`--yes` skips it) and can be run again. `sudo syncdctl ha status` prints the engine's
  line on the pair.
- **A lease file** (`--lease-path`). The file's directory must exist on both hosts (the shared
  file system mounted there), and must be an absolute path without blanks, `%` or `\`. From
  0.5.1 each command writes a systemd drop-in that lets the engine write there,
  `/etc/systemd/system/syncd.service.d/ha-lease.conf` (`ReadWritePaths=` and
  `RequiresMountsFor=` for the directory): the engine's unit otherwise lets it write only its own
  directories. With 0.5.0, add such a drop-in by hand. `syncdctl uninstall --purge` removes it.
- **Sealed password keys.** When the first host seals its password keys, the join file carries
  the passphrase and `ha join` installs it at the same path. A second host that seals its keys
  with another passphrase, or seals them when the first does not, is refused.
- **The join file** holds the CA's private key, the lease database's URL with its password and,
  when the keys are sealed, their passphrase: copy it as a secret and delete it afterwards.
- **Left to you:** open the control API's port (7443) between the two hosts (`syncdctl ha` opens
  no firewall port), and import the running configuration in the console before the next
  publish (the `api` section changed on the host). In strict control API access, when the pair
  delivers passwords, list the certificate the passive engine pulls snapshots with (`cli.crt`,
  `syncd command line`, unless `engine.ha.peer` names another) under `api.access.passwords` on
  both hosts (see [Passwords and the pair](#passwords-and-the-pair)); from 0.5.1 the commands say
  so. `ha init` and `ha join` refuse a host whose `engine.yaml` has an `engine.ha` section of its
  own, and `syncdctl renew-certs --new-ca` on one host breaks the pair until both commands are
  run again.

### Setting up a pair by hand

The same, step by step, for hosts whose certificates syncdctl did not issue:

1. **One CA for both hosts.** Copy `/etc/syncd/pki/ca.crt` and `ca.key` from the first host over
   the second's, and run `sudo syncdctl renew-certs` there. On each host, replace
   `/etc/syncd/pki/engine.crt` and `engine.key` with a certificate from that CA that names the
   host (your PKI, or `openssl` with `subjectAltName=DNS:idm1.example.gov` and
   `extendedKeyUsage=serverAuth`). The command line's `cli.crt` is what a passive engine pulls
   snapshots with.
2. **Configure both engines**: `api.listen: 0.0.0.0:7443` with `api.tls` in
   `/etc/syncd/engine/engine.yaml`, and the pair either in `engine.ha` or in `/etc/syncd/engine.env`
   (`SYNCD_HA_NAME`, `SYNCD_HA_LEASE_URL` or `SYNCD_HA_LEASE_PATH`, `SYNCD_HA_LEASE_TTL_SECS`,
   `SYNCD_HA_SNAPSHOT_EVERY_SECS`, and this host's `SYNCD_HA_MEMBER` and `SYNCD_HA_ADVERTISE`):

    ```yaml
    engine:
      data_dir: /var/lib/syncd/engine
      ha:
        name: idm                          # the pair: both engines use the same name
        lease:
          backend: postgres                # or file, with path: on a file system both hosts share
          url: { env: SYNCD_HA_LEASE_URL } # postgres://user:password@db.example.gov/syncd_console
          # tls_verify: true               # check the database's certificate (ca_file: ...)
          ttl_secs: 15                     # at least 3
        snapshot_every_secs: 60            # the default from 0.5.0 (300 before); at least 10
        # password_replication_wait_ms: 2000  # how long a submitted password change waits for
        #                                  # the passive engine to have it; 0: not at all
        # role: passive                    # a standby that never takes the lease
        # member: idm1                     # this engine's name in the pair; the host name by default
        # advertise: idm1.example.gov:7443 # where the other engine reaches this one's API
    ```

3. **Open 7443 between the two hosts**, so each engine reaches the other's control API.
4. **Start the first engine, then the second.** The first takes the lease; the second starts
   passive and pulls a snapshot.
5. **Register both engines in the console**, each at its host's address with mutual TLS (see
   [Registering engines](#registering-engines)). The console shows them as one pair.

`SYNCD_HA_MEMBER` and `SYNCD_HA_ADVERTISE`, when set and not empty, override `member` and
`advertise` (from 0.4.1). A file with an `engine.ha` of its own keeps it. `engine.ha` can only
be changed on the host: the engine refuses a configuration published from the console that
changes it. On Kubernetes, `engine.ha.enabled` does all of this (see
[An active/passive pair](#an-active-passive-pair)).

### Passwords and the pair

- **Keys.** The snapshots carry the engine's password keys and the agents' CA, so the engine that
  takes over opens the password changes queued in the store. When the keys are sealed (see
  [The password keys](operator-guide.md#the-password-keys)), both engines need the same
  `SYNCD_PASSWORD_KEY_PASSPHRASE_FILE` passphrase: an engine with another one, or none, refuses
  to start on a snapshot's keys.
- **Queued changes** (from 0.5.0) are followed by the passive engine write by write between
  snapshots, so they survive a failover. A submitted change waits up to
  `password_replication_wait_ms` (2000) for the passive engine to have it, and is accepted
  either way.
- **Capture agents reach either engine** (from 0.5.0): with `passwords.ingest`, the passive
  engine listens on the ingest port too and hands every call to the active one. Give agents one
  name that reaches both hosts (DNS with both addresses, a load balancer, a virtual address) and
  list it in `passwords.ingest.names` on both. In strict mode, the active engine takes these
  calls only from a certificate listed under `api.access.passwords`.
- **Strict access** (from 0.5.1). Whenever the pair delivers passwords, with agents or not, the
  active engine holds password keys, and gives its snapshots and its password queue, which carry
  the keys or are sealed to them, only to a certificate listed under `api.access.passwords` (or
  the control socket). List the one the passive engine uses, on both hosts: by default `cli.crt`
  (`syncd command line`), which the `syncd` group can read; a certificate of its own for the pair
  (`engine.ha.peer.cert_file` and `key_file`, its common name under `operate` and `passwords`)
  keeps that power from the group. Without it the passive engine's pulls are refused
  (`pulling a store snapshot failed`, with `... hands out the password keys: in strict mode it
  needs a client certificate listed under api.access.passwords ...`).

## Install with Helm

The chart (`deploy/helm/syncd` in the source repository, published as
`oci://ghcr.io/winllc/charts/syncd`; 0.6.0 is the newest there, as 0.6.1's was not
published) runs the two
[container images](#container-images-and-the-helm-chart):

- **The engine** is a StatefulSet with **one replica**, or two with `engine.ha.enabled` (an
  [active/passive pair](#an-active-passive-pair)). Its store and its configuration file are on a
  volume, one per engine.
- **The console** is a Deployment. It keeps its data in a PostgreSQL that you provide.
- **The chart issues the control API's certificates** (mutual TLS between the console and the
  engine) from a CA of its own, and generates the console's master key and the initial `admin`
  password. All of them are kept across upgrades and uninstalls, or, from 0.5.1, come from
  Secrets of your own ([GitOps](#gitops)).
- **A Job registers the engine in the console** after each install and upgrade.

| Requirement | Notes |
|---|---|
| **Kubernetes on amd64 or arm64 nodes** | Both platforms, and from 0.5.0 the FIPS images too, unless the release was built without arm64 (see [Container images](#container-images-and-the-helm-chart)). The CI tests run on kind; EKS, AKS, GKE and OpenShift are not tested |
| **Helm 3.8 or newer** | For OCI charts. CI uses Helm 3.16 |
| **PostgreSQL 13 or newer** | For the console, outside the chart: the chart has no bundled database. An HA pair keeps its lease there too |
| **A StorageClass** | The cluster's default, or one you name, for the engine's volume (`ReadWriteOnce`, 10 GiB by default) |
| **An Ingress controller** *(recommended)* | To reach the console from outside the cluster, with TLS |
| **Prometheus Operator** *(optional)* | For the ServiceMonitor and the PrometheusRule |
| **A CNI that enforces NetworkPolicies** *(optional)* | For `networkPolicy.enabled` |
| **Network access from the engine's pod** | To the databases and directories it reads and writes |

### 1. Install the chart

Create the console's database, as for a host install:

```sql
CREATE ROLE syncd_console LOGIN PASSWORD '...';
CREATE DATABASE syncd_console OWNER syncd_console;
```

Create the namespace, and a Secret with the secrets the engine's configuration will refer to
(`password: { env: HR_DB_PASSWORD }`). Its keys become the engine's environment variables. It can
be added later too.

```bash
kubectl create namespace syncd
kubectl -n syncd create secret generic syncd-engine-env \
  --from-literal=HR_DB_PASSWORD='...' --from-literal=TARGET_LDAP_PASSWORD='...'
kubectl -n syncd create secret generic syncd-console-db \
  --from-literal=url='postgres://syncd_console:...@db.example.com:5432/syncd_console?sslmode=require'
```

Write your values to a file, for example `syncd-values.yaml`:

```yaml
engine:
  envFromSecret: syncd-engine-env
console:
  database:
    existingSecret: syncd-console-db       # key url
  trustedProxies: 10.0.0.0/8               # the ingress controller's pods
  ingress:
    enabled: true
    className: nginx
    host: syncd.example.com
    tls:
      - secretName: syncd-tls
        hosts: [syncd.example.com]
```

Then install:

```bash
helm install syncd oci://ghcr.io/winllc/charts/syncd --version 0.6.0 \
  --namespace syncd -f syncd-values.yaml --wait
```

Or install from a checkout of the source repository, with images you built and pushed:

```bash
helm install syncd ./deploy/helm/syncd --namespace syncd -f syncd-values.yaml --wait \
  --set image.engine.repository=registry.example.com/syncd \
  --set image.console.repository=registry.example.com/syncd-console
```

`console.database.url` or `console.database.existingSecret` is required. The chart stops with
`console.database.url or console.database.existingSecret is required` if both are missing.

The resources are named after the release: `<release>-syncd-...`, or `<release>-...` when the
release's name contains `syncd`. For a release named `syncd`, as here, they are the StatefulSet
`syncd-engine` (pod `syncd-engine-0`), the Deployment and Service `syncd-console`, the Service
`syncd-engine`, the Secrets `syncd-console` and `syncd-pki`, and the Job `syncd-register`. The
examples on these pages use those names. [Production values](#production-values) covers single
sign-on, the console's own HTTPS, extra volumes, NetworkPolicies, the alert rules, password
synchronisation and an HA pair.

### 2. First sign-in

`helm status syncd -n syncd` prints how to read the initial `admin` password:

```bash
kubectl -n syncd get secret syncd-console -o jsonpath='{.data.admin-password}' | base64 -d; echo
```

Sign in with it; it must be changed then, as on a host (see [First sign-in](#first-sign-in)).
Without an Ingress, use a port-forward:

```bash
kubectl -n syncd port-forward svc/syncd-console 8080:8080     # then http://127.0.0.1:8080
```

(With `console.tls.existingSecret`, open `https://127.0.0.1:8080` instead.)

The engine starts with no sources and waits for the console to publish a configuration. It is
registered in the console under the release's name (`syncd` here), not `local`. Configure it in
the console as on any other install.

To issue a new one-time password (a locked or lost `admin` account), run the console's own command
in its pod. The image has no shell, but the pod has the console's settings:

```bash
kubectl -n syncd exec deploy/syncd-console -- syncd-console admin reset-password --user admin
```

### 3. Key values

| Value | Default | What it sets |
|---|---|---|
| `image.engine.repository`, `.tag`, `.pullPolicy` | `ghcr.io/winllc/syncd`, the chart's `appVersion`, `IfNotPresent` | The engine's image. `<version>-fips` for the FIPS image |
| `image.console.repository`, `.tag`, `.pullPolicy` | `ghcr.io/winllc/syncd-console`, the chart's `appVersion`, `IfNotPresent` | The console's image, also used by the registration Job |
| `imagePullSecrets` | `[]` | For a private registry or mirror |
| `podSecurityContext`, `securityContext` | uid and gid 65532, `fsGroup` 65532, `runAsNonRoot`, seccomp `RuntimeDefault`; no privilege escalation, read-only root file system, every capability dropped | Both components and the Job |
| `engine.name` | The release's name | The name the console knows the engine by |
| `engine.environment` | | The console environment the engine runs in, passed to each registration (`--environment`). Empty: a new engine runs in `default`, and a registered one keeps the one it has. New in 0.4.1 |
| `engine.envFromSecret` | | A Secret whose keys become the engine's environment variables: the secrets its configuration refers to, and S3 keys for backups |
| `engine.env` | `[]` | More environment variables, for example `RUST_LOG` or `SYNCD_FIPS_MODE` |
| `engine.extraVolumes`, `.extraVolumeMounts` | `[]` | More volumes on the engine's pod: a Kerberos keytab, a CSV inbox, secrets from a vault (see [Extra volumes](#extra-volumes)) |
| `engine.extraCaCertificates.configMap`, `.secret` | | A ConfigMap or a Secret (one of the two) of CA certificates, every key a file under `/etc/syncd/ca`, for connectors' `ca_file` (see [CA certificates](#ca-certificates)). New in 0.5.0 |
| `engine.serviceAccount.create`, `.name`, `.annotations` | `false`, , `{}` | The engine pods' service account (see [Service accounts](#service-accounts)). New in 0.5.0 |
| `engine.configSigning.publicKey`, `.existingConfigMap`, `.existingSecret`, `.key` | , , , `console-signing.pub` | The console's configuration signing key, mounted at `/etc/syncd/config-signing/<key>` (see [Control API access on Kubernetes](#control-api-access-on-kubernetes)). New in 0.5.0 |
| `engine.passwordKeyPassphrase.existingSecret`, `.key`, `.previousKey` | , `passphrase`, | A Secret of yours holding the passphrase that seals the password keys; `previousKey` (new in 0.5.0) names the key holding the passphrase before a change (see [Password synchronisation](#password-synchronisation)). New in 0.4.1 |
| `engine.persistence.size`, `.storageClass`, `.accessModes` | `10Gi`, the default class, `[ReadWriteOnce]` | The engine's volume, one per engine (see [Sizing](availability-and-sizing.md#sizing)) |
| `engine.resources` | Requests 250m CPU and 512Mi; memory limit 4Gi | The engine's pod. The memory limit is the engine's `syncd_memory_limit_bytes` |
| `engine.terminationGracePeriodSeconds` | `90` | Time the engine has to finish its batch and stop, as `TimeoutStopSec` on a host |
| `engine.nodeSelector`, `.tolerations`, `.affinity`, `.podAnnotations` | | Scheduling, annotations. `affinity` replaces the HA pair's anti-affinity |
| `engine.service.type` | `ClusterIP` | The Service with the control API (`api`, 7443) and metrics (`metrics`, 9464) |
| `engine.passwordIngest.enabled`, `.port`, `.service.type`, `.service.annotations` | `false`, `7445`, `ClusterIP`, `{}` | The password agents' ingest listener on a Service of its own, `<release>-syncd-engine-ingest` (see [Password synchronisation](#password-synchronisation)). New in 0.4.1 |
| `engine.ha.enabled` | `false` | Two engine pods as an [active/passive pair](#an-active-passive-pair) |
| `engine.ha.lease.kind` | `postgres` | Where the pair's lease is: a row in the console's database, or (`kubernetes`, new in 0.5.0) a Lease object. Changing it on a running pair needs both engine pods stopped first (see [The lease on Kubernetes](#the-lease-on-kubernetes)) |
| `engine.ha.leaseTtlSeconds`, `.snapshotEverySeconds` | `15`, `60` | The lease's TTL and the snapshot interval. The interval's default was 300 before 0.5.0; a release that sets it, or is upgraded with `--reuse-values`, keeps its own |
| `engine.ha.antiAffinity` | `soft` | `soft` prefers two nodes for the pair; `hard` requires them |
| `engine.ha.podDisruptionBudget.enabled` | `true` | A drain evicts one engine of the pair at a time |
| `engine.metrics.job` | `syncd` | The `job` label the ServiceMonitor gives the engine's metrics, and the job the PrometheusRule selects |
| `engine.metrics.serviceMonitor.enabled`, `.interval`, `.labels` | `false`, `30s`, `{}` | A Prometheus Operator ServiceMonitor scraping `/metrics` (see [Monitoring on Kubernetes](operator-guide.md#monitoring-on-kubernetes)) |
| `engine.metrics.prometheusRule.enabled`, `.labels` | `false`, `{}` | The shipped alert rules as a PrometheusRule (see [Alert rules](#alert-rules-as-a-prometheusrule)) |
| `console.replicas` | `1` | The console's pods |
| `console.serviceAccount.create`, `.name`, `.annotations` | `false`, , `{}` | The service account of the console's pods and of the registration Job. New in 0.5.0 |
| `console.license.existingSecret`, `.key` | , `license.lic` | A Secret holding the license file (see [The license on Kubernetes](#the-license-on-kubernetes)). New in 0.5.0 |
| `console.database.url` | | The console's PostgreSQL URL, kept in the chart's `syncd-console` Secret |
| `console.database.existingSecret`, `.existingSecretKey` | , `url` | Or a Secret of your own holding the URL, which keeps it out of Helm's values |
| `console.adminPassword` | Generated (24 characters) | The initial `admin` password, which must be changed at the first sign-in |
| `console.adminPasswordExistingSecret`, `console.adminPasswordExistingSecretKey` | , `admin-password` | Or the initial password from a Secret of your own, which the console reads at its first start only (see [GitOps](#gitops)). New in 0.5.1 |
| `console.masterKey.existingSecret`, `.existingSecretKey` | , `master-key` | The console's master key from a Secret of your own (32 random bytes, base64: `openssl rand -base64 32`) instead of the one the chart generates (see [GitOps](#gitops)). New in 0.5.1 |
| `console.trustedProxies` | | `CONSOLE_TRUSTED_PROXIES`: the ingress controller's addresses or CIDR ranges, comma-separated, whose `X-Forwarded-For` names the client |
| `console.publicUrl` | `https://<console.ingress.host>` when the Ingress is enabled | `CONSOLE_PUBLIC_URL`: the console's address as users reach it, the base of the single sign-on redirect URI |
| `console.oidc.*` | Off | [Single sign-on](#single-sign-on) |
| `console.tls.existingSecret` | | A `kubernetes.io/tls` Secret: the console serves HTTPS itself (see [The console's own HTTPS on Kubernetes](#the-consoles-own-https-on-kubernetes)) |
| `console.extraVolumes`, `.extraVolumeMounts` | `[]` | More volumes on the console's pod: a Git deploy key, a HEC token, a CA |
| `console.env` | `[]` | Any other console setting, as its environment variable (below). The registration Job gets them too |
| `console.newMasterKey` | `false` | Starts the console and the registration Job with a new master key when the old one is lost (see [The master key Secret](operator-guide.md#the-master-key-secret)). For one upgrade only |
| `console.resources` | Requests 100m CPU and 128Mi; memory limit 512Mi | The console's pods |
| `console.nodeSelector`, `.tolerations`, `.affinity`, `.podAnnotations` | | Scheduling, annotations |
| `console.service.type`, `.port` | `ClusterIP`, `8080` | The console's Service: HTTP, or HTTPS with `console.tls` |
| `console.ingress.enabled`, `.className`, `.annotations`, `.host`, `.tls` | `false`, , `{}`, `syncd.example.com`, `[]` | An Ingress for one host, path `/` |
| `networkPolicy.*` | Off | [NetworkPolicies](#networkpolicies). `networkPolicy.passwordIngestFrom` (new in 0.4.1): who may reach the ingest port |
| `pki.days` | `3650` | How long the CA and the certificates the chart issues are valid |
| `pki.existingSecret` | | The control API's certificates from a Secret of your own instead of the chart's (`ca.crt`, `engine.crt`, `engine.key`, `console.crt`, `console.key`; see [GitOps](#gitops)). New in 0.5.1 |

`values.yaml` in the chart lists them all, and the source repository's
`tests/helm/all-options.yaml` turns every production option on at once (CI renders it). There is
no value for the engine's configuration: it comes from the console, as on a host. Nor is there one
for its host settings (`api.access`, `engine.require_signed_config`, `engine.config_signing_keys`),
which live in the engine's own file on its volume (see
[Control API access on Kubernetes](#control-api-access-on-kubernetes)), or for the KMS settings
(`SYNCD_PASSWORD_KEY_PASSPHRASE_KMS` can go in `engine.env`; `CONSOLE_MASTER_KEY_KMS` cannot be
set up through the chart).

CI renders the values new in 0.5.0 and 0.5.1 and checks which pods use what
(`tests/helm/check-values.py`: the service accounts, the signing key, CA certificates and the
Secrets of your own), and installs, upgrades and restarts a release in a kind cluster; the
Kubernetes Lease and its RBAC are installed by the `kind-ha` job. All of these passed in the
`Container` workflow's run for the `v0.6.0` tag, which publishes nothing otherwise. The license
Secret and `previousKey` are not in those checks. No cloud cluster was used: try the values you
need on a test cluster first.

**Other console settings**, such as session lengths, the audit outputs, webhooks or Git sync, go in
`console.env` as the variables in [Configuration](configuration.md). Take secrets from a Secret of
your own, mounted as a file with `console.extraVolumes`, never from Helm's values:

```yaml
console:
  env:
    - { name: CONSOLE_SESSION_IDLE_MINUTES, value: "30" }
    - { name: ENS_AUDIT_SPLUNK_URL, value: https://splunk.example.com:8088 }
    - { name: ENS_AUDIT_SPLUNK_TOKEN_FILE, value: /etc/syncd/secrets/splunk-hec-token }
```

The sign-in settings new in 0.5.0 (SAML, `CONSOLE_SAML_*`; certificate sign-in, `ENS_X509_*`; the
notice-and-consent banner and the sign-in policy, `ENS_CONSENT_BANNER_*`, `ENS_SESSION_*`,
`ENS_LOCKOUT_*`, `ENS_PASSWORD_*`, `ENS_HSTS_*`) have no values of their own either: set them in
`console.env`, with their files (the provider's metadata, the allowed issuers, the notice's text)
mounted with `console.extraVolumes`. See [Single sign-on](single-sign-on.md).

Before 0.4.0 single sign-on went in `console.env` too. It now has values of its own
(`console.oidc.*`, `console.publicUrl`); don't set the same variables both ways.

### 4. What the chart keeps

| Secret or volume | Holds | Kept |
|---|---|---|
| `syncd-console` | `master-key` (the console's master key, which seals the engine's client key, webhook addresses and secrets, and secret environment values in the console's database), `admin-password`, and `database-url` when given inline | Generated at install. Kept by every upgrade, and by `helm uninstall` (`helm.sh/resource-policy: keep`). A reinstall reuses it. From 0.5.1, with `console.masterKey.existingSecret` the master key comes from your Secret instead, and with `console.adminPasswordExistingSecret` the password; the chart then makes this Secret only for what it still generates or holds |
| `syncd-pki` | `ca.crt`, `ca.key`, `engine.crt`, `engine.key`, `console.crt`, `console.key` | Issued at install, kept by upgrades while it holds `ca.crt` and `ca.key`. From 0.4.0 also kept by `helm uninstall`, so a reinstall reuses the certificates the console has. New certificates are ECDSA P-256; an install made with chart 0.3.0 keeps its RSA ones until the Secret is deleted. From 0.5.1, `pki.existingSecret` names a Secret of your own instead, and the chart makes none |
| `data-syncd-engine-0` (and `data-syncd-engine-1` for a pair) (PersistentVolumeClaim) | The engine's store (`/var/lib/syncd/engine`) and its configuration file (`/var/lib/syncd/engine.yaml`) | Kept by `helm uninstall`, as a StatefulSet's volumes are |

The console's pods keep nothing of their own: their data directory is an `emptyDir` (the Git sync
clone is made again after a restart), and the master key comes from the Secret as
`CONSOLE_MASTER_KEY`.

!!! warning "Back up the `syncd-console` Secret"
    Its master key opens the engine's client key and the other sealed secrets in the console's
    database. If the Secret is lost and the chart generates a new one, the console refuses to
    start over the sealed secrets (see [The master key](operator-guide.md#the-master-key)).

!!! note "Render the chart with Helm, against the cluster, or bring your own Secrets"
    The chart reads the existing Secrets with `lookup` to keep the master key, the initial
    password and the certificates. `helm template`, and tools that render the chart that way (Argo
    CD), cannot see the cluster and generate new ones each time. Install and upgrade with
    `helm install` and `helm upgrade` (Flux's helm-controller runs Helm itself), or, from 0.5.1,
    keep all three in Secrets of your own (see [GitOps](#gitops)).

### 5. Reaching the console

The console's Service is plain HTTP on 8080, unless the console has its own certificate
([below](#the-consoles-own-https-on-kubernetes)). Users reach it through an Ingress with TLS
(`console.ingress.*`, as in step 1).

- **`console.trustedProxies`.** Set it to the ingress controller's pod range, so that the audit
  trail and the sign-in limit see each user's address rather than the controller's.
- **`console.publicUrl`.** `https://<console.ingress.host>` when the Ingress is enabled; set it
  otherwise, for single sign-on.
- **The controller's settings.** The nginx configuration shipped for hosts turns buffering off
  with a one-hour read timeout, for the live status stream (server-sent events), and accepts
  bodies up to 2 MB, for configuration imports. The chart sets no Ingress annotations; with
  ingress-nginx, the equivalents are:

    ```yaml
    console:
      ingress:
        annotations:
          nginx.ingress.kubernetes.io/proxy-buffering: "off"
          nginx.ingress.kubernetes.io/proxy-read-timeout: "3600"
          nginx.ingress.kubernetes.io/proxy-body-size: 2m
    ```

    These are a suggestion that mirrors the shipped nginx configuration; the chart's CI test does
    not use an Ingress.

The engine's Service is `ClusterIP`. Its metrics port (9464) is plain HTTP, and its control API
(7443) needs a client certificate from the chart's CA. To limit who may connect to either, turn on
the chart's [NetworkPolicies](#networkpolicies).

### Production values

New in 0.4.0, all off by default.

#### Single sign-on

The console's OpenID Connect settings are under `console.oidc`. The client secret comes from a
Secret of your own, mounted as a file (`CONSOLE_OIDC_CLIENT_SECRET_FILE`), never from Helm's
values:

```bash
kubectl -n syncd create secret generic syncd-oidc --from-literal=client-secret='...'
```

```yaml
console:
  oidc:
    issuer: https://login.example.com/realms/corp
    clientId: syncd-console
    existingSecret: syncd-oidc               # key client-secret (existingSecretKey)
    roleClaim: groups
    roleMap: syncd-admins=admin,syncd-ops=operator
    defaultRole: viewer
    # ca: { configMap: corp-ca }             # or secret: ...; key ca.crt by default
```

- Register the console with the provider with the redirect URI
  `<console.publicUrl>/api/v1/auth/oidc/callback`.
- For a provider whose certificate is not public, put its CA (PEM) in a ConfigMap or a Secret and
  name it in `console.oidc.ca.configMap` or `console.oidc.ca.secret` (key `console.oidc.ca.key`,
  `ca.crt` by default). The console gets it as `CONSOLE_OIDC_CA_FILE`.
- Other `CONSOLE_OIDC_*` settings (scopes, username claim, the button's label) go in
  `console.env`.
- The chart refuses an `issuer` without `clientId`, without `existingSecret`, or without a public
  URL, and a CA from both a ConfigMap and a Secret.

See [Single sign-on](single-sign-on.md) for the settings themselves.

#### The console's own HTTPS on Kubernetes

By default the console speaks plain HTTP on 8080 and the ingress controller terminates TLS. To
encrypt that hop too, or without an Ingress, give the console a `kubernetes.io/tls` Secret
(`tls.crt`, `tls.key`; a cert-manager `Certificate` makes one):

```yaml
console:
  tls:
    existingSecret: syncd-console-tls
  ingress:
    annotations:
      nginx.ingress.kubernetes.io/backend-protocol: HTTPS
```

- The console then serves HTTPS on 8080 (`CONSOLE_TLS_CERT`, `CONSOLE_TLS_KEY`), and reloads
  the files when cert-manager renews them.
- The probes use `scheme: HTTPS`, and the container's, the Service's and the Ingress backend's
  port is called `https` instead of `http`.
- The ingress controller must be told to speak HTTPS to the backend, which is
  controller-specific: with ingress-nginx the annotation above; with Traefik a `ServersTransport`
  or the `traefik.ingress.kubernetes.io/service.serversscheme: https` annotation; with HAProxy
  `haproxy.org/server-ssl: "true"`.
- `kubectl port-forward` then needs `https://` too.

#### CA certificates

New in 0.5.0. For directories and other systems whose certificates come from a CA of your own,
`engine.extraCaCertificates` mounts a ConfigMap or a Secret (one of the two) read-only at
`/etc/syncd/ca`, every key a file, and a connector names its file:

```bash
kubectl -n syncd create configmap corp-ca --from-file=corp-ca.pem
```

```yaml
engine:
  extraCaCertificates:
    configMap: corp-ca        # or secret: corp-ca
```

In the configuration published from the console, `connection: { url: ldaps://dc1.corp.example.com,
ca_file: /etc/syncd/ca/corp-ca.pem, ... }` on an LDAP or Active Directory connector (new in 0.5.0;
see [Connectors](connectors.md)). Those connectors read the file at each connection, and the
kubelet updates a mounted ConfigMap within about a minute, so a renewed CA needs no restart. The
same directory serves a `scim` connector's `connection.tls.ca_file` and `engine.backup`'s
`ca_file`.

#### Service accounts

New in 0.5.0. By default the chart names no service account, and the engine, the console and the
registration Job run as the namespace's `default` account, as before. Give them their own when a
workload identity is bound to one (an IAM role through IRSA, a vault role, a Google service
account):

```yaml
engine:
  serviceAccount:
    create: true             # <release>-syncd-engine (or `name`)
    annotations:
      eks.amazonaws.com/role-arn: arn:aws:iam::111122223333:role/syncd-engine
console:
  serviceAccount:
    create: false            # an account that exists, by name
    name: syncd-console
```

The registration Job runs as the console's account. The pods mount no API server token
(`automountServiceAccountToken: false`), except the engine's with a Kubernetes lease. Azure
workload identity's pod label (`azure.workload.identity/use`) has no chart value. No workload
identity was exercised on a cloud.

#### Extra volumes

`engine.extraVolumes` and `engine.extraVolumeMounts`, and the `console.*` pair, add volumes and
mounts to the pods for what the chart has no value of its own for: a Kerberos keytab, an ODBC
configuration, secrets from a vault, a Git deploy key, a Splunk HEC token. The root file systems
are read-only, so anything the processes must find on disk comes in this way:

```yaml
engine:
  extraVolumes:
    - name: krb5
      secret: { secretName: syncd-krb5, defaultMode: 0440 }
  extraVolumeMounts:
    - name: krb5
      mountPath: /etc/syncd/krb5
      readOnly: true
```

From 0.5.0 the chart's own volume names on the engine are taken: `data`, `pki`, `run`, `tmp`,
`password-key-passphrase`, `config-signing` and `extra-ca-certificates`. From 0.5.0 the engine can
also read secrets from HashiCorp Vault, AWS Secrets Manager or Azure Key Vault itself, signing in
as its pod (the settings in `engine.env`; see
[Configuration](configuration.md#secret-references)); the External Secrets Operator or the
Secrets Store CSI driver, which bring a secret to the pod as a file, work through these volumes.

The inbox of a `csv` connector comes in the same way: a PersistentVolumeClaim (or an NFS share)
the exporting system writes to, mounted where the connector's `path` points. The engine only reads
it, and deletes a `done_file` once a file is read, so the mount is read-write only when done files
are used:

```yaml
engine:
  extraVolumes:
    - name: inbox
      persistentVolumeClaim: { claimName: syncd-inbox }
  extraVolumeMounts:
    - name: inbox
      mountPath: /var/lib/syncd/in
      readOnly: false
```

The connector itself is in [Connectors](connectors.md). The console's Git sync takes its deploy
key and `known_hosts` the same way, through `console.extraVolumes`, with the
`CONSOLE_CONFIG_GIT_*` settings in `console.env` (see [Configuration as code](config-as-code.md)).
`file://` repositories do not work in the image, which has no shell.

#### NetworkPolicies

`networkPolicy.enabled=true` adds one NetworkPolicy per component. Your CNI must enforce them;
kind's does not, so the chart's CI test checks the objects, not the isolation.

- **The engine** accepts its control API (7443) from the console's and the registration Job's
  pods only (and, in a pair, from the other engine), and its metrics (9464) from
  `networkPolicy.monitoringNamespaceSelector` and `monitoringPodSelector` (both in one `from`
  entry; from anywhere while both are empty). With `engine.passwordIngest.enabled`, it also
  accepts the ingest port from `networkPolicy.passwordIngestFrom` (`from` entries, such as an
  `ipBlock` for the domain controllers; from anywhere while it is empty). New in 0.4.1.
- **The console** accepts its port (8080) from `networkPolicy.ingressNamespaceSelector` and
  `ingressControllerSelector`, plus `networkPolicy.console.extraIngressFrom` (more `from`
  entries, an `ipBlock` for instance); from anywhere while all are empty.
- **The registration Job** accepts nothing.

```yaml
networkPolicy:
  enabled: true
  monitoringNamespaceSelector: { matchLabels: { kubernetes.io/metadata.name: monitoring } }
  ingressNamespaceSelector: { matchLabels: { kubernetes.io/metadata.name: ingress-nginx } }
```

Kubelet probes are not subject to NetworkPolicies on the common CNIs (Calico, Cilium), so the
engine's liveness probe on 9464 keeps working with a narrow monitoring selector.

**Egress stays open** by default, on purpose: the engine's sources and targets, and the console's
PostgreSQL and identity provider, are wherever you run them. `networkPolicy.egress.enabled=true`
closes it: every pod may then reach DNS (53), the console and the Job may reach the engine's
control API, the engines of a pair each other's, and nothing else until you add the rest in
`networkPolicy.egress.extra`, a list of NetworkPolicy egress rules applied to all three
components:

```yaml
networkPolicy:
  enabled: true
  egress:
    enabled: true
    extra:
      - to: [{ ipBlock: { cidr: 10.1.0.0/16 } }]        # the database, the directories
        ports: [{ port: 5432, protocol: TCP }, { port: 636, protocol: TCP }]
      - to: [{ ipBlock: { cidr: 0.0.0.0/0, except: [10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16] } }]
        ports: [{ port: 443, protocol: TCP }]           # the identity provider, webhooks, SCIM, Entra, S3
```

With egress closed, also allow:

- the console's database, for the console, the Job, and the engines of a pair (their lease);
- the receivers of the console's webhooks and pre-publish hook, which connect directly, without
  a proxy, and its Git server and mail relay;
- for the engine: a `scim` target and its OAuth token endpoint; for an `entra` connector, the
  cloud's Graph and login hosts on 443 (`graph.microsoft.com` and `login.microsoftonline.com`
  for the global cloud; `graph.microsoft.us`, `dod-graph.microsoft.us` and
  `login.microsoftonline.us` for the US Government clouds; `microsoftgraph.chinacloudapi.cn` and
  `login.chinacloudapi.cn` for China); an S3 or MinIO backup destination.

A source the engine cannot reach shows as `SyncdSourceDown`; a database the console cannot reach
keeps it from starting; a webhook receiver it cannot reach shows as a retried delivery
(`connection failed`) on the Integrations page; a pre-publish hook it cannot reach refuses the
publish. Check `kubectl describe networkpolicy` first when one of these appears after turning
egress off.

#### Alert rules as a PrometheusRule

```yaml
engine:
  metrics:
    serviceMonitor: { enabled: true, labels: { release: kube-prometheus-stack } }
    prometheusRule: { enabled: true, labels: { release: kube-prometheus-stack } }
```

`engine.metrics.prometheusRule.enabled` renders the shipped alert rules (the same file the
packages ship) as a PrometheusRule. The labels are whatever your Prometheus's
`serviceMonitorSelector` and `ruleSelector` look for. The ServiceMonitor sets its targets' `job`
label to `engine.metrics.job` (`syncd`), and the rules are rendered with that job, so
`SyncdEngineDown` fires for the engine the ServiceMonitor scrapes. Change `engine.metrics.job` if
`syncd` is taken; the rules follow. With several releases scraped by one Prometheus, enable the
PrometheusRule on one of them: its rules match every engine. See
[Monitoring on Kubernetes](operator-guide.md#monitoring-on-kubernetes).

#### Control API access on Kubernetes { #control-api-access-on-kubernetes }

New in 0.5.0 (see [Control API access](#control-api-access) for the tiers). The chart issues two
client certificates: `syncd console` (the console's) and `syncd engine` (the engine's own, which
the passive engine of a pair pulls snapshots with).

- **A new release is strict.** The image's initial configuration has `api.access` with `mode:
  strict`: `syncd console` applies configurations, `syncd engine` and the control socket
  (`kubectl exec ... -- syncd status`, `reconcile`, `approve`) read and operate, and `syncd engine`
  is listed under `passwords` too (`passwords: [syncd engine]`): for a pair's ingest forwarding
  and, from 0.5.1, for the passive engine's snapshot pulls and its following of the password
  queue once the engine holds password keys. `kubectl exec ... -- syncd apply-config` is refused;
  `kubectl exec ... -- syncd backup` goes through the control socket, which may.
- **A release first installed before 0.5.0 stays compatible.** The engine's configuration file
  is on its data volume and an upgrade never rewrites it. To opt in, add to the `api` section of
  `/var/lib/syncd/engine.yaml` on the volume:

    ```yaml
    api:
      access:
        mode: strict
        configure: [syncd console]
        operate: [syncd engine]
        passwords: [syncd engine]      # a pair that delivers passwords: snapshot pulls, agents
    ```

    The image has no shell or editor: use an ephemeral debug container, or a pod that mounts the
    volume while the engine is scaled down. Restart the engine's pod (both pods of a pair), then
    import the running configuration in the console. This procedure has not been rehearsed on a
    cluster, and the chart has no value for the access mode.
- **Signed configurations.** `engine.configSigning` mounts the console's public key in the engine's
  pods, at `/etc/syncd/config-signing/console-signing.pub`:

    ```bash
    kubectl -n syncd exec deploy/syncd-console -- syncd-console admin config-signing-key |
      sed -n '/BEGIN PUBLIC KEY/,/END PUBLIC KEY/p' > console-signing.pub
    helm upgrade syncd oci://ghcr.io/winllc/charts/syncd --version 0.6.0 -n syncd --reuse-values \
      --set-file engine.configSigning.publicKey=console-signing.pub
    ```

    The chart keeps it in a ConfigMap (`syncd-config-signing`); `existingConfigMap` or
    `existingSecret` (with `key`) name one of your own instead, one of the three. Mounting it
    changes nothing by itself: add `config_signing_keys:
    [/etc/syncd/config-signing/console-signing.pub]`, then `require_signed_config: true`, to the
    `engine` section of the file on the volume, as for `api.access`. For a rotation, put both keys
    in the value; the engine reads the file at each apply.

#### The license on Kubernetes { #the-license-on-kubernetes }

New in 0.5.0. Install the license on the console's **License** page, or keep it in a Secret, which
suits GitOps:

```bash
kubectl -n syncd create secret generic syncd-license --from-file=license.lic=L-0001.lic
helm upgrade syncd oci://ghcr.io/winllc/charts/syncd --version 0.6.0 -n syncd --reuse-values \
  --set console.license.existingSecret=syncd-license
```

The chart mounts the Secret at `/etc/syncd/license` and sets `CONSOLE_LICENSE_FILE`. A renewal is
a changed Secret, which the console reads within minutes without a restart; with the Secret, the
License page shows the license and takes no uploads. The installation id the portal may ask for is
on the License page, or `kubectl -n syncd exec deploy/syncd-console -- syncd-console admin
license`. See [syncd licensing](licensing.md).

#### GitOps: Argo CD and other tools that render the chart { #gitops }

New in 0.5.1. The chart generates the console's master key, its initial `admin` password and the
control API's certificates at install, and keeps them by reading the Secrets it made (Helm's
`lookup`). A tool that renders the chart with `helm template` and applies the result itself, as
Argo CD does, cannot read them, so every render would make new ones: the console then refuses to
start on a master key that does not open its secrets, and new certificates restart the engine at
every sync. Flux's helm-controller runs Helm itself, and keeps them. With such a tool, make the
three Secrets once (by hand, or with the External Secrets Operator from a vault) and name them:

```bash
kubectl -n syncd create secret generic syncd-master-key --from-literal=master-key="$(openssl rand -base64 32)"
kubectl -n syncd create secret generic syncd-admin-password --from-literal=admin-password="$(openssl rand -base64 18)"
kubectl -n syncd create secret generic syncd-api-certs --from-file=ca.crt --from-file=engine.crt \
  --from-file=engine.key --from-file=console.crt --from-file=console.key
```

```yaml
console:
  database:
    existingSecret: syncd-database
  masterKey:
    existingSecret: syncd-master-key                  # existingSecretKey: master-key
  adminPasswordExistingSecret: syncd-admin-password   # adminPasswordExistingSecretKey: admin-password
pki:
  existingSecret: syncd-api-certs
```

- **The certificates**, all from the CA in `ca.crt` (its key stays with you): `engine.crt` and
  `engine.key` with the common name `syncd engine`, for server and client use, naming
  `<fullname>-engine`, `<fullname>-engine.<namespace>`, `<fullname>-engine.<namespace>.svc`,
  `<fullname>-engine.<namespace>.svc.cluster.local` and `localhost`, and with `engine.ha` also
  `*.<fullname>-engine-peers.<namespace>.svc` and its `.svc.cluster.local` form; `console.crt`
  and `console.key` with the common name `syncd console`, for client use. `<fullname>` is
  `syncd` for a release called `syncd`. The common names are what a strict engine tells its
  callers apart by. The chart's own are ECDSA P-256.
- **A release that runs already.** Copy what the chart made, so nothing changes: `master-key`
  from the `syncd-console` Secret and the five files of `syncd-pki`, for example
  `kubectl -n syncd get secret syncd-pki -o jsonpath='{.data.engine\.crt}' | base64 -d >engine.crt`.
  The chart's own Secrets are left in place; delete them once the release runs with yours (the
  chart keeps `syncd-console` while it still holds a generated password or the database URL).
- **Rotation.** The pods do not restart when a Secret of your own changes: `kubectl rollout
  restart` them after rotating one.

With these, and the database URL in a Secret, a render is the same every time. CI renders these
values (`tests/helm/check-values.py`); no GitOps tool was run against the chart.

#### Password synchronisation

New in 0.4.1. Two values serve [password synchronisation](password-sync.md): a passphrase that
seals the password keys, and a Service for the capture agents' ingest listener.

**Seal the password keys.** Without a passphrase, the engine's password key and the agents' CA
key are plain files on the data volume, and backups and HA snapshots carry them as they are (see
[The password keys](operator-guide.md#the-password-keys)). Create a Secret with a passphrase and
name it:

```bash
kubectl -n syncd create secret generic syncd-password-key \
  --from-literal=passphrase="$(head -c 32 /dev/urandom | base64)"
```

```yaml
engine:
  passwordKeyPassphrase:
    existingSecret: syncd-password-key
    key: passphrase          # the default
```

The chart mounts the key read-only at `/run/secrets/syncd-password-key/passphrase` and sets
`SYNCD_PASSWORD_KEY_PASSPHRASE_FILE` to it. At its next start the engine seals both files in place,
with the same keys, and `syncd status` says `password keys: sealed with the passphrase`. Both
engines of a pair use the same Secret. The chart neither creates nor keeps this Secret: keep a copy
of the passphrase with your other recovery secrets, since a restore needs it.

**Changing the passphrase** (new in 0.5.0). The engine cannot write a mounted Secret, so the
change is made in the Secret: keep the current passphrase under another key, put the new one
under `passphrase`, and name the old key in `engine.passwordKeyPassphrase.previousKey` (mounted
as `passphrase.previous`). At `helm upgrade` each engine pod opens the key files with the
previous passphrase and seals them again with the new one. Once every pod has started since (and,
for a pair, the passive one has received a snapshot taken after the change), remove `previousKey`
and the old key. Rotating the keys themselves (`syncd password rotate-key`, `rotate-agent-ca`) is
done with `kubectl exec`, as on a host. The procedure has not been rehearsed on a cluster.

**The ingest listener.** Capture agents enroll and send changes on the listener the configuration
sets in `passwords.ingest`. `engine.passwordIngest.enabled` gives the engine's pods an `ingest` port
and a Service of its own, `syncd-engine-ingest`, so a load balancer for the agents exposes neither
the control API nor the metrics:

```yaml
engine:
  passwordIngest:
    enabled: true
    port: 7445               # the port of passwords.ingest.listen
    service:
      type: LoadBalancer     # for agents outside the cluster; ClusterIP by default
networkPolicy:
  passwordIngestFrom:        # with networkPolicy.enabled; from anywhere when empty
    - ipBlock: { cidr: 10.20.0.0/24 }
```

The configuration the console publishes must listen on that port, with `names` the address the
agents use (`passwords: { ingest: { listen: 0.0.0.0:7445, names: [...] } }`). With
`engine.ha.enabled` the Service routes to both pods, and from 0.5.0 both answer: the passive
engine hands every call (enrollment, renewal, submissions) to the active one, once it has received
its first snapshot (in 0.4.1 the passive pod refused the agent, which retried). While neither pod
holds the lease, agents are told `unavailable` and keep their changes. This forwarding is tested
with two engine processes on one host, not on a cluster.

#### An active/passive pair { #an-active-passive-pair }

```bash
helm upgrade syncd oci://ghcr.io/winllc/charts/syncd -n syncd --reuse-values \
  --set engine.ha.enabled=true
```

`engine.ha.enabled` runs two engine pods, `syncd-engine-0` and `syncd-engine-1`, each with its own
data volume, as an active/passive pair:

- **The lease** is, by default, a row in the console's PostgreSQL database (table
  `syncd_lease`, created by the console's migrations), reached with the console's database URL.
  From 0.5.0 it can be a Kubernetes Lease object instead (see
  [The lease on Kubernetes](#the-lease-on-kubernetes)). Either way the pair is set from the
  environment (`SYNCD_HA_*`), so the configuration the console publishes is the same for both
  pods.
- **Each pod by name.** A headless Service, `syncd-engine-peers`, gives each pod a name
  (`syncd-engine-0.syncd-engine-peers.<namespace>.svc`). The lease records the active one's; the
  passive one pulls snapshots from it with the chart's engine certificate, which names both pods.
- **Registration.** The Job registers both pods in the console, as `<engine name>-0` and `-1`. The
  console shows them as one pair, with the active one's status. From 0.4.1, an engine registered
  before under `<engine name>` alone is renamed `<engine name>-0` first, keeping its configuration
  versions, reports and history: an init container of the Job runs
  `syncd-console admin rename-engine` (it does nothing when there is no such engine, or `-0`
  exists already). With chart 0.4.0 it stayed registered beside the pair until removed under
  **Engines**. Engine names in `CONSOLE_SELF_SERVICE_ENGINE` or `CONSOLE_CONFIG_GIT_ENGINES` are
  not renamed: change them in `console.env`; from 0.5.0 the Job's log, the console's log and a
  notice on its Engines page name such a setting.
- **Password synchronisation.** From 0.5.0 both engines answer on the ingest port, and the passive
  one follows the queued password changes (see
  [Password synchronisation](#password-synchronisation)). With sealed keys, both pods use the same
  passphrase Secret.
- **Scheduling.** A PodAntiAffinity on the host name keeps the two pods apart:
  `engine.ha.antiAffinity: soft` (the default) prefers two nodes, `hard` requires them (a
  one-node cluster then runs one). `engine.affinity`, when set, replaces it. A
  PodDisruptionBudget lets a drain evict one engine at a time.
- **Timing.** `engine.ha.leaseTtlSeconds` (15) and `engine.ha.snapshotEverySeconds` (60 from 0.5.0;
  300 before, and kept by a release that sets it or is upgraded with `--reuse-values`). After the
  first, a snapshot sends only the files the passive pod does not hold. A pod stopped by a rollout
  or a drain gives the lease up at once, for the other pod to take. An engine that starts to a
  free lease waits a third of the TTL first, so that the recreated pod does not take the lease
  back before the other engine's next try; an engine on its own becomes active that much after it
  starts (5 s with the default TTL).

**On an existing release**, the StatefulSet's `serviceName` and `podManagementPolicy` change with
`engine.ha.enabled`, and Kubernetes does not update them in place. Before the upgrade:

```bash
kubectl -n syncd delete statefulset syncd-engine --cascade=orphan   # the pods and the volume stay
kubectl -n syncd delete secret syncd-pki    # certificates from an older chart don't name the pods
```

Then upgrade. The chart issues new certificates, and the registration Job gives the console the
new ones. The same applies when turning HA off again; nothing is renamed back then: the Job
registers `<engine name>` anew, and `-0` and `-1` stay registered until you remove them.

##### The lease on Kubernetes { #the-lease-on-kubernetes }

New in 0.5.0. `engine.ha.lease.kind: kubernetes` keeps the pair's lease in a
`coordination.k8s.io/v1` Lease object named after the release, in its namespace, so the engines'
availability no longer depends on the console's database:

```bash
helm upgrade syncd oci://ghcr.io/winllc/charts/syncd -n syncd --reuse-values \
  --set engine.ha.enabled=true --set engine.ha.lease.kind=kubernetes
kubectl -n syncd get lease syncd -o yaml     # holderIdentity, renewTime, leaseTransitions
```

- **What the chart makes:** the Lease object; a Role that may `get` and `update` that one Lease;
  a RoleBinding to the engine's service account; and that account, when `engine.serviceAccount`
  names none. The engine's pods then mount their account's token.
- **How it decides.** An engine takes the lease once the Lease object has stood unchanged for its
  whole duration as that engine saw it, so the nodes' clocks need not agree; every write names the
  `resourceVersion` it read. A pod that has just started waits one lease duration before it takes
  over a lease whose holder is gone. From 0.5.1 the engine measures that duration, and its fence,
  on a clock NTP cannot step.
- **With `networkPolicy.egress.enabled`,** let the engine pods reach the cluster's API server.
- **Changing the kind of a running pair** (either way): the two kinds don't know of each other,
  so stop both engine pods first (`kubectl -n syncd scale statefulset syncd-engine
  --replicas=0`), upgrade with the new kind, and let the chart scale the pair back up. A release
  installed before 0.5.0 keeps `postgres`.

!!! warning "Tested in kind only"
    New in 0.5.0, a `kind-ha` CI job installs a pair in kind, turns HA on for an existing
    release, and fails it over by lease expiry and by deleting the active pod, once with the
    PostgreSQL lease and once with the Kubernetes lease. It passed in the `Container` workflow's
    run for the `v0.6.0` tag, whose publishing waits for it, so the Kubernetes Lease backend has
    met a real API server (kind's). No cloud cluster (EKS, AKS, GKE, OpenShift) was used. The
    pair itself is tested end to end on hosts. Try a failover on a test cluster before you rely on
    it.

#### FIPS images

```yaml
image:
  engine: { tag: 0.6.0-fips }
  console: { tag: 0.6.0-fips }
engine:
  env:
    - { name: SYNCD_FIPS_MODE, value: "true" }
console:
  env:
    - { name: SYNCD_FIPS_MODE, value: "true" }
```

`kubectl -n syncd exec syncd-engine-0 -- syncd status` then shows `fips on`. From 0.5.0 the FIPS
images are built for amd64 and arm64. The registration Job gets `console.env` too, so it runs in
FIPS mode with the console. Custom functions in WebAssembly are refused in FIPS mode unless
`SYNCD_FIPS_ALLOW_WASM_FUNCTIONS=true` is in both containers' `env`. See
[FIPS 140-3 and DISA STIG](fips-and-stig.md).

### 6. Upgrade { #helm-upgrade }

```bash
helm upgrade syncd oci://ghcr.io/winllc/charts/syncd --version X.Y.Z \
  --namespace syncd -f syncd-values.yaml --wait
```

Unlike `syncdctl upgrade`, the chart takes no backup and does not roll back by itself. Back up
first (see [Backups on Kubernetes](availability-and-sizing.md#backups-on-kubernetes)):

- the engine: `kubectl -n syncd exec syncd-engine-0 -- syncd backup --to s3://...`, or a volume
  snapshot of its volume;
- the console's database, with `pg_dump`;
- the `syncd-console` Secret (and the `syncd-pki` Secret, the password-key passphrase Secret and
  the license Secret if you use them), or the Secrets of your own that replace them
  ([GitOps](#gitops)).

On an engine that holds password keys in strict mode, `kubectl exec ... -- syncd backup` goes
through the control socket, as every `kubectl exec` command does, so it keeps working from 0.5.1.

On upgrade, the engine migrates its store if the new release changed its format (see
[Store format](#store-format)), the console migrates its database, and the registration Job runs
again. The engine pod has 10 minutes to open its store and listen (its startup probe) before
Kubernetes restarts it (from 0.5.5 `engine.startupProbe` sets this: raise `failureThreshold` for
a large store and a release whose notes say it migrates the store; `engine.minReadySeconds` makes
a new engine pod stay ready that long before it counts as available). `helm rollback` and its limits are in [Rollback](#rollback). What changes
from chart 0.5.4 to 0.6.0 (0.6.1's chart was not published) is in
[Upgrading from 0.5.4 to 0.6.1](#upgrading-from-054-to-061), from chart 0.5.1 or 0.5.2 to 0.5.4 in
[Upgrading from 0.5.1 or 0.5.2 to 0.5.4](#upgrading-from-051-or-052-to-054), from 0.5.0 to 0.5.1 in
[Upgrading from 0.5.0 to 0.5.1](#upgrading-from-050-to-051), from
0.4.1 to 0.5.0 in [Upgrading from 0.4.1 to 0.5.0](#upgrading-from-041-to-050), from
0.4.0 to 0.4.1 in [Upgrading from 0.4.0 to 0.4.1](#upgrading-from-040-to-041), and from 0.3.0 to
0.4.0 in [Upgrading from 0.3.0 to 0.4.0](#upgrading-from-030-to-040).

A host install does not move to Kubernetes by itself: see
[Moving from a host to Kubernetes](availability-and-sizing.md#moving-from-a-host-to-kubernetes).

### 7. Uninstall { #helm-uninstall }

```bash
helm uninstall syncd -n syncd
```

This removes the pods, Services, Ingress, NetworkPolicies and the Job. It keeps the
`syncd-console` Secret (the master key), the `syncd-pki` Secret (the certificates, from 0.4.0) and
the engine's volume, so a later `helm install` with the same release name carries on where it
was: the console's database still holds the engine registered with the client certificate from
that Secret. To start over, delete them too, and drop the console's database:

```bash
kubectl -n syncd delete secret syncd-console syncd-pki
kubectl -n syncd delete pvc data-syncd-engine-0          # and data-syncd-engine-1 for a pair
```

Deleting the PKI Secret alone makes the next install or upgrade issue new certificates; deleting
the console Secret alone loses the master key (see
[The master key Secret](operator-guide.md#the-master-key-secret)).

### Air-gapped clusters

1. On a connected host, get the chart: `helm pull oci://ghcr.io/winllc/charts/syncd --version
   0.6.0` gives `syncd-0.6.0.tgz`. Verify it and the images with cosign there (see
   [Verifying the images and the chart](#verifying-the-images-and-the-chart)).
2. Copy `ghcr.io/winllc/syncd:0.6.0` and `ghcr.io/winllc/syncd-console:0.6.0` (or the `-fips`
   tags) into your registry, for example with `skopeo copy --all` (every platform) or `docker
   pull`, `docker tag` and `docker push`. The chart uses no other image.
3. Install from the file, with your registry:

```bash
helm install syncd ./syncd-0.6.0.tgz --namespace syncd -f syncd-values.yaml \
  --set image.engine.repository=registry.example.gov/winllc/syncd \
  --set image.console.repository=registry.example.gov/winllc/syncd-console
```

Add `imagePullSecrets` if your registry needs them.

This is installing syncd on a disconnected cluster. Synchronising identities *between* two
networks that are not connected (DSML files carried across a data diode or on media) is
[Air-gapped synchronisation](air-gap.md).

### What the chart does not do yet

- **A bundled PostgreSQL.**
- **The engine's host settings.** `api.access` and the signing settings live in the engine's own
  file on its volume: the chart mounts the signing key and cannot set them (see
  [Control API access on Kubernetes](#control-api-access-on-kubernetes)).
- **KMS-wrapped keys.** The chart has no values for `SYNCD_PASSWORD_KEY_PASSPHRASE_KMS` or
  `CONSOLE_MASTER_KEY_KMS`; the console's needs the chart's `CONSOLE_MASTER_KEY` gone, which it
  does not offer.
- **Pod labels**, such as Azure workload identity's `azure.workload.identity/use`.
- **An etcd lease backend**, and a `values.schema.json`.
- **A pair tested on a cloud cluster** (above): the kind jobs are the only tests.

## The JDBC bridge

syncd reads PostgreSQL, MySQL, SQL Server and MongoDB with drivers of its own. For any other
database with a JDBC driver (Db2, Informix, Oracle, ...), a `jdbc` connector reads through the
JDBC bridge, a separate Java program that runs the SQL syncd builds and streams the rows back over
gRPC. It is not part of the release bundle or the packages: it is built from `bridge/jdbc` in the
source repository and run separately.

1. **Build** it with Java 17+ and Gradle 8: `gradle installDist` (or `gradle distTar` for a
   `.tar`).
2. **Add the drivers**: put the databases' JDBC jars (`db2jcc4.jar`, `ojdbc11.jar`, ...) in its
   `drivers/` directory, or point `SYNCD_JDBC_DRIVERS` at the directory holding them.
3. **Run** it as a service next to syncd, with a restart policy. syncd retries its reads while the
   bridge is away.

    ```bash
    bin/syncd-jdbc-bridge --listen 127.0.0.1:50071 --token-file /etc/syncd/bridge-token
    ```

| Option | |
|---|---|
| `--listen HOST:PORT` | Where to listen. `127.0.0.1:50071` by default |
| `--token-file FILE` | A shared secret every call must carry (`SYNCD_BRIDGE_TOKEN` also works). Required to listen beyond loopback |
| `--tls-cert FILE`, `--tls-key FILE` | Serve TLS. The key is PKCS#8 PEM |

`JAVA` picks the Java runtime and `JAVA_OPTS` adds JVM options. The bridge logs to standard error
and holds no configuration of its own: each request names its database. Every request carries the
database's password, so a bridge on another machine must serve TLS and require a token; syncd
refuses a `jdbc` connector whose bridge is beyond loopback otherwise. The connector's settings are
in [Connectors](connectors.md).

## The Debezium sidecar

To stream changes from Oracle or Db2 as they happen, a `debezium` connector receives change events
from [Debezium Server](https://debezium.io/documentation/reference/stable/operations/debezium-server.html),
run beside syncd. Debezium Server is not shipped with syncd; you install and run it. The source
repository has a starting `application.properties` in `deploy/debezium/`:

- the sink is HTTP, to the address the `debezium` connector listens on (for example
  `http://127.0.0.1:8085/<token>`; the token goes in the path, as the HTTP sink sends no other
  credentials);
- it retries for ever (`debezium.sink.http.retries=2147483647`, every second), because syncd
  answers an event only once it has committed it;
- events are plain JSON, decimals as text, with no tombstones;
- Debezium keeps its own position in `offsets.dat` and `schema-history.dat`.

Run it with a restart policy (systemd `Restart=always`, or a container with
`--restart unless-stopped`). See [Connectors](connectors.md) for the connector.

## Upgrade

On Kubernetes, upgrade with `helm upgrade` (see [Upgrade with Helm](#helm-upgrade)). On a host,
upgrade as in [Bundle installs](#bundle-installs) or [Package installs](#package-installs). Read
what changes first.

### Upgrading from 0.5.4 to 0.6.1

Upgrade as usual, with `syncdctl upgrade`, `apt` or `dnf` (with the downloaded files: the
repositories are not published). Three releases since 0.5.4, and one step: upgrade straight to
0.6.1.

- **0.5.5** (19 November) is a release of fixes from an in-depth review of the engine, the
  console and the packaging: 3 critical, 34 high, 73 medium and 45 low findings, most fixed.
  The critical ones: a gone entity's exported record could delete the live entry another
  entity had taken over; an answer from a SCIM provider or Microsoft Graph that could not be
  read whole was taken for an empty list (members removed, resources created twice); and a
  password agent's submission could undo a revocation made while it ran.
- **0.6.0** (20 November) adds synchronisation across an air gap: the `dsml` connector type
  (see [Air-gapped synchronisation](air-gap.md)).
- **0.6.1** (20 November) fixes the installer: a first install whose `openssl` failed stopped
  without saying why, and could not be finished (see [What the installer does](#what-the-installer-does)).
  Upgrades were not affected.

**On Kubernetes**, upgrade with chart **0.6.0**: 0.6.1's images and chart were not published
(see [Getting the release](#getting-the-release)), and 0.6.1 changes nothing that runs in them.

- **The console's database: schema 30 to 33** (0.5.5: publish leases, the published flag of
  engine configurations, the expiry of one-time passwords; 0.6.0 and 0.6.1 add nothing). A
  rollback to 0.5.4 restores the database backup taken before the upgrade: what was done in
  the console since is lost.
- **The store's format stays 2.** A DSML target keeps its files' sequence number, and the
  exports waiting for the end of their interval, as settings beside the data, which 0.5.5 and
  older ignore.
- **The configuration** gains the `dsml` connector type and its settings, and a CSV
  connection's `allow_empty`; nothing is renamed or removed. 0.5.5 adds checks: group filters
  are checked for attributes no flow sets, an attribute listed twice in precedence is refused,
  and `attributes.multi_valued` no longer counts as setting an attribute. Run the new
  release's `syncd validate` on your configuration before upgrading.
- **Behaviour changes (0.5.5):**
    - **Safety gates.** Disables and moves aside of leavers count under a publication's
      deletion threshold, as deletes do. Drift reverts pass through the thresholds, the change
      window and the canary. A publication over its threshold holds only its own deprovisions,
      not the batch's other publications'. A change window's overdue release lets through only
      the overdue changes. See [Change safety](change-safety.md).
    - **Errors keep state.** A publication filter that fails on an entity leaves its entry as
      it is (it used to deprovision it); a mapping that fails keeps the exported value (it
      cleared it); a group filter that fails keeps the membership.
    - **Groups follow their members' writes.** A member leaves or joins a group when its own
      export is written: a held delete, a change waiting for a window or a canary, a failed
      export or a quarantined entity no longer moves it meanwhile.
    - **CSV.** A file with a header and no rows deletes nothing unless the connection sets
      `allow_empty: true`. Without a done file, a file is read once its modification time is
      3 s old. The done file is deleted once the read is committed.
    - **REST sources** without a total count ask for one more page after a short one, and
      stop at an empty page.
    - **DirSync** reads each changed object again in the base with the object's filter, so an
      object that stops matching is deleted. **Active Directory delta reads** keep their
      position per domain controller: each reads in full once after the upgrade.
    - **SCIM and Entra ID targets** wait out a `Retry-After` of up to 5 s; a longer one stops
      requests to the provider until it ends (the exports fail and are retried).
    - **Precedence** naming a connector ranks every object of it the view reads; a
      `connector/object` names one.
    - **Read-only API callers** (below the configure tier) get the running configuration
      without the values its environment marks secret, and their simulations run without them.
    - **Control commands** are refused while the engine exports after the initial load,
      releases held changes, writes back a target's changes after an outage, or delivers queued
      passwords, saying what it is busy with; they used to queue behind it. A command whose
      caller stopped waiting is not run.
    - **The status** is published at most every 250 ms while source events keep coming.
    - **Listings** of dead letters, quarantined entities and quality failures return at most
      10,000 entries.
    - **The console.** A password reset revokes the API tokens the account created, and a
      one-time password an administrator issues is refused after 7 days. `/healthz` answers 503
      naming a background task that keeps stopping or cannot work; the new `/livez` answers
      `ok` while the console serves, and the Helm chart's probes use it. A UI request is given
      up after ten minutes, with a message. A PDF report has at most 20,000 rows (CSV and
      Excel have no limit).
    - **systemd.** A failing service is restarted after 10 s, growing to 5 minutes, instead
      of every 5 s for ever.
    - **Backups.** `syncdctl backup --to ... --keep N` removes the oldest backups only once
      every part of the new one is stored (`syncd backup-prune`, new, does it).
- **`syncd status`** (0.6.0): the line of an unreachable target goes on to say that a DSML
  target keeps its exports for the next file it writes. A script matching
  `target <name> UNREACHABLE` is unaffected.
- **Helm** (0.5.5): `engine.startupProbe` (`periodSeconds` 5, `failureThreshold` 120: ten
  minutes to open the store) and `engine.minReadySeconds` (0) are values. A StatefulSet's
  volume size cannot be changed by `helm upgrade`; see
  [Growing the engine's volume](availability-and-sizing.md).
- **Files.** Nothing new on the host; `/etc/syncd` stays as you left it. The custom-functions
  example module's bytes follow the version: 0.6.1 pins `66671f41…`, the same as 0.5.4, so a
  package of your own that pins the example module needs a change only if you updated it to
  0.5.5's or 0.6.0's `732b8651…`.
- **Known limitations** (0.5.5 and 0.6.0): a throttling SCIM or Entra target still holds the
  engine for up to 5 s per request; after an HA takeover, Debezium sources resume from
  Debezium's own position, so have Debezium send the tables again with an incremental
  snapshot; MySQL and SQL Server sources look up changed keys one at a time; DSML's are in
  [Air-gapped synchronisation](air-gap.md#limitations-as-of-061).

**From 0.5.1 or 0.5.2**, read [Upgrading from 0.5.1 or 0.5.2 to 0.5.4](#upgrading-from-051-or-052-to-054)
too; **from 0.5.0**, also [Upgrading from 0.5.0 to 0.5.1](#upgrading-from-050-to-051).

### Upgrading from 0.5.1 or 0.5.2 to 0.5.4

Upgrade as usual, with `syncdctl upgrade`, `apt` or `dnf` (with the downloaded files: the
repositories are not published), or `helm upgrade` with chart 0.5.4. 0.5.2 was published as images
and a chart only, so a host install comes from 0.5.1; a Helm release from 0.5.1 or 0.5.2. 0.5.4
brings 0.5.2's faster joins (see
[Joins and first reads](availability-and-sizing.md#joins-and-first-reads)), and makes rules that
compute each person's groups from the groups naming them cheaper on large groups (see
[Computing memberOf from groups](rules.md#computing-memberof-from-groups)). The release build of
0.5.4 upgraded a bundle built from the `v0.5.3` tag (version 0.5.2) to it, rolled it back and
upgraded it again; it did not test the upgrade from 0.5.1.

- **The console's database: schema 30, unchanged.** A rollback to 0.5.2 or 0.5.1 keeps it, and
  `syncdctl rollback --keep-data` is allowed (see
  [Rolling back 0.5.4 to 0.5.2 or 0.5.1](#rolling-back-054-to-052-or-051)).
- **The store's format stays 2.** Nothing new is written to it.
- **Behaviour changes:**
    - **Related entities in a fixed order.** A rule that makes one value of several related
      entities (`referrers(e, "member").map(g, g.cn).join(", ")`) sees them in the order of their
      ids. Before, the order depended on what had happened since the engine started, so it
      could change after a restart. Such a value may change once after the upgrade, and the
      targets get the change. A list stored as it is (`memberOf`) was sorted already and does
      not change.
    - **From 0.5.1 only:** when two records of one source wait for the same entity and it
      appears, the first in order of source and record joins it, and the other waits for review.
      In 0.5.1 either could, differently from run to run.
    - **From 0.5.1 only:** the `ambiguous` count (`syncd status`'s totals, the summary of
      `syncd run --once`, the metric `syncd_ambiguous_joins_total`) counts evaluations of
      ambiguous joins. A full read now evaluates a record waiting for review again only when the
      entities its keys find, or its source's joins, changed, so the count grows more slowly:
      dashboards on its rate show less.
- **Two new warnings.** `syncd validate` and the console's check warn about a reference
  attribute no flow or computed attribute sets, and about an attribute a rule reads of another
  view's entities that no flow or computed attribute of that view sets (see
  [Validation rules](configuration.md#validation-rules)). They are warnings: the upgrade's
  configuration check does not stop on them, but each points at a rule that is empty today.
- **Console.** A computed attribute that reads other entities (`ref()`, `refs()`, `reports()`,
  `referrers()`) no longer shows "Does not compile" in the visual editor and the Studio (see
  [Rules that read other entities](user-guide.md#rules-that-read-other-entities)).
- **Helm.** No new values. Chart and images move to 0.5.4 together.
- **Known limitations:**
    - in one batch of changes, a record that moves because its own join keys changed, and
      records that move because their entity's join attribute changed, are checked in another
      order than one change at a time would check them: when both find the same entity, they can
      end differently placed. This needs keys that change in the same batch, and was so in
      0.5.1;
    - a projection with almost nothing to recompute pays a few percent for the batching's
      bookkeeping.
- **Files.** Nothing new on the host; `/etc/syncd` stays as you left it.

**From 0.5.0 straight to 0.5.4**, read [Upgrading from 0.5.0 to 0.5.1](#upgrading-from-050-to-051)
too: the console's schema goes from 29 to 30, so a rollback to 0.5.0 restores the database.

### Upgrading from 0.5.0 to 0.5.1

Upgrade as usual, with `syncdctl upgrade`, `apt` or `dnf` (with the downloaded files: the
repositories are not published), or `helm upgrade` with chart 0.5.1. The upgrade takes its local
backup first, as before. 0.5.1 fixes what a full review of 0.5.0 found, three of them critical: a
stream's checkpoint could be committed before the changes it covered, the Entra ID source staged
a group as empty when its members could not be read (and the publications then removed every
member), and the console's view of the running configuration showed the engine's environment
secrets to every reader. The release build of 0.5.1 upgraded a 0.5.0 bundle to it, rolled it back
and upgraded it again. Several fixes refuse something 0.5.0 allowed, so read this first:

- **The console's database: schema 29 to 30** (migration `0030_review_fixes`). **A rollback to
  0.5.0 therefore restores the database** backed up before the upgrade, and `syncdctl rollback
  --keep-data` is refused (see [Rolling back 0.5.1 to 0.5.0](#rolling-back-051-to-050)).
- **The store's format stays 2.** A new column family, `unique_values/<view>`, holds the values
  `unique()` claimed where they differ from the attribute's own; 0.5.0 opens a store that has it
  and ignores it.
- **The first full sync after the upgrade does more than usual** (the full sync a published
  configuration runs, or `syncd full-sync`):
    - it claims the current value of every computed attribute that has a `unique()` scope. Where
      two entities already share a value, the one with the lower entity id keeps it and the
      other gets a new candidate. Values duplicated before the upgrade in an attribute that uses
      `let` with `assign_once` are not split up;
    - it releases the claims of scopes no attribute uses any more;
    - orphans that the current join rules match are joined or projected;
    - a view added on a source another view projects gets entities for every record, not only
      those that changed since;
    - entities lose the records of sources taken out of their view, and an entity with no
      projecting source left is deleted, under the deletion thresholds.

    Try the first publish after the upgrade with **Simulate**, or in report mode, where renamed
    `unique()` values or deletes would matter.
- **Configurations that no longer validate.** The upgrade checks the running configuration with
  the new engine first (`syncd validate`) and stops, changing nothing, when it is refused:
    - `unique()` anywhere but a computed attribute (publication attributes, DN attributes and
      filters, group filters and `owner_from`, `passwords.initial.from`, correlation hints);
    - a `deletion_threshold` that is empty or out of range, on a connector or a publication;
    - two computed attributes, two flows of one source, or two publication attributes whose names
      differ only in case;
    - a rule nested deeper than 200 levels, an operator chain longer than 400 terms, or more than
      100,000 nodes once package functions are inlined;
    - a WASM module written into the YAML (`packages[].wasm.module`) instead of uploaded and
      approved in the console's module store;
    - a published configuration that changes `passwords.key_file`, which is now a host setting
      like `api`.
- **Control API access that 0.5.0 allowed and 0.5.1 refuses** (see
  [Control API access](#control-api-access)):
    - a plain-text loopback listener with `api.local_reveal` alone no longer serves backups, HA
      snapshots or the password queue: they need `api.local_control` too;
    - in strict mode, on an engine that holds password keys, `Backup`, `PullSnapshot`,
      `PullSnapshotFiles` and `FollowPasswordQueue` need a certificate listed under
      `api.access.passwords`, or the control socket. Before the upgrade, list the passive engine's
      certificate under `passwords` on both hosts of a pair that delivers passwords (see
      [Passwords and the pair](#passwords-and-the-pair)), and uncomment `api.control_socket` for
      `syncdctl backup --to` and `syncd backup`;
    - an engine managed by a console that holds no license is judged by its own evaluation period
      again, and blocks publishing once it ends (0.5.0 let such a console switch enforcement off).
      See [syncd licensing](licensing.md).
- **The console refuses more:** cookie-authenticated changes from another site (`Origin` or
  `Sec-Fetch-Site`); API tokens of single sign-on accounts that have not signed in for
  `CONSOLE_API_SSO_CREATOR_MAX_IDLE` (35 days by default, `0` turns it off); the author of a
  version approving it through a token or a Git commit; and, for an account with a temporary
  password, anything but the password change.
- **Behaviour changes** (the release notes list them all):
    - **A target that is down when the engine starts or reloads** (LDAP and Active Directory) is
      reported down and connected later, instead of failing the start or rolling a published
      configuration back. `syncd run --once` still fails at once. If a restored previous
      configuration does not start either, the engine tries it again every 5 seconds, doubling up
      to a minute, instead of exiting.
    - **HA:** a takeover's reconciliation, and the full read of PostgreSQL streams whose positions
      it forgets, happen only after a promotion or a restart, not again at every reload. A crash-restarted active engine
      waits for its own lease to expire before taking it again, and two engines with the same
      member name no longer both hold the lease. When a promotion cannot release the store within
      30 seconds and a received snapshot exists, the engine exits, so that its restart swaps the
      snapshot in. The fence and the Kubernetes lease measure time on a clock NTP cannot step.
    - **Restored and received files are mode 0600**, the store's included: run `syncd restore` as
      the engine's user (`sudo -u syncd`), as `syncdctl` does.
    - **Backups:** a failed backup leaves nothing behind, and backups are ordered by when they
      were taken, not by host name. An archive without its manifest, left by a failed backup of an
      earlier release, is no longer listed, restored or pruned: delete it by hand.
    - **Environment rendering** keeps the configuration as written (only the scalars holding
      `{{ }}` change), and environment files are read strictly: only `true`/`false`, `null` and
      plain decimal numbers are typed, so `NO`, `on`, `0100` and `+33` are text.
    - **`syncdctl` and the packages:** the console's health check uses HTTPS when the console
      serves it ([The console's own HTTPS](#the-consoles-own-https)); upgrades and backups check
      that `pg_dump` can dump the console's database before stopping anything; `ha init
      --lease-path` writes the engine's drop-in for the lease's directory; the packages create
      `/var/log/syncd` for the console (owner `syncd-console`, mode 0750), for an
      `ENS_AUDIT_FILE` there.
- **Package upgrades from 0.5.0.** The installed release's `syncdctl` takes the backup before the
  new files are unpacked, so this once it is 0.5.0's: it does not check `pg_dump` or the disk space
  before it stops syncd, and leaves the database out (`SYNCD_SKIP_DB_BACKUP=1`) only when
  `pg_dump` is not installed. When its backup fails, 0.5.1's package script starts syncd again and
  the upgrade stops there. See [Package installs](#package-installs).
- **Helm.** New values: `console.masterKey.existingSecret`, `console.adminPasswordExistingSecret`
  and `pki.existingSecret`, for Argo CD and other tools that render the chart (see
  [GitOps](#gitops)). The image's initial configuration is unchanged in what it grants: a release
  first installed with 0.5.0 already lists `syncd engine` under `passwords`. A release first
  installed before 0.5.0 that opted in to strict mode by hand needs `passwords: [syncd engine]`
  beside `operate` for a pair that delivers passwords (see
  [Control API access on Kubernetes](#control-api-access-on-kubernetes)). Chart and images move
  to 0.5.1 together.
- **Known limitations** (the release notes have the full list):
    - a Debezium truncate held by the deletion threshold cannot be released by approving it (a
      Debezium object never reads in full): the deletes wait for the next truncate;
    - page-number paging in the REST source (UKG's default) can still miss a record that moves
      between pages during a read;
    - engine state held in memory can still drift from the store when a batch fails to commit:
      restarting the engine reloads it;
    - `unique()` claims are released when an entity is deleted, even if its entry stays in the
      target (`deprovision: disable`): use `permanent` for values that must outlive the entity;
    - the Windows password filter's new check and the OpenLDAP overlay's change compile, but
      have not run on Windows or against a live slapd.
- **Files.** `/etc/syncd/engine/engine.yaml`, `console.env` and `engine.env` stay as you left
  them. New: `/var/log/syncd` (above) and, for a pair on a lease file set up with `syncdctl ha`,
  `/etc/systemd/system/syncd.service.d/ha-lease.conf`. The services can write only their own
  directories: a directory for scheduled backups, an audit file outside `/var/log/syncd` or
  `CONSOLE_REPORTS_SHARE_DIR` needs a drop-in (see
  [What runs where](operator-guide.md#what-runs-where)).

**From 0.4.1 straight to 0.5.1**, read [Upgrading from 0.4.1 to 0.5.0](#upgrading-from-041-to-050)
too: the store is migrated to format 2 and the console's schema goes from 17 to 30. The release
build of 0.5.1 tested the upgrade from 0.5.0 only.

### Upgrading from 0.4.1 to 0.5.0

Upgrade as usual, with `syncdctl upgrade`, `apt` or `dnf`, or `helm upgrade` with chart 0.5.0. The
upgrade takes its local backup first, as before. A good part of 0.5.0 was tested against stand-ins
only (the release notes' "Known limitations"); the release build of 0.5.0 upgraded a 0.4.1 bundle
to it, rolled it back and upgraded it again. Try the upgrade on a test installation first, and
upgrade to 0.5.1, which fixes what a review of 0.5.0 found ([above](#upgrading-from-050-to-051)).
What to know:

- **Request a license as part of the upgrade.** Nothing changes at the upgrade itself. The 30-day
  evaluation starts at the first start of 0.5.0, a warning shows from day 23, a 30-day grace period
  follows, and then publishing a changed configuration is refused: **about 60 days after the
  upgrade**. Runs, approvals, dead-letter actions, password changes and publishing again a version
  published before (a rollback) go on. Find the installation id on the License page and install the
  license as in [The license](#the-license); the clock is in
  [syncd licensing](licensing.md#upgrading-from-041-the-clock-starts).
- **The control API stays compatible until you opt in.** An upgrade never rewrites
  `/etc/syncd/engine/engine.yaml`, so an upgraded engine has no `access` section and lets every
  certificate of its CA do everything, with a warning at every start, in `syncd status` and on
  the console's engine page. Opt in with `sudo syncdctl api-access strict` (on Kubernetes, an edit
  on the volume), then import the running configuration in the console (see
  [Control API access](#control-api-access)).
- **Configurations that no longer validate.** Delivering passwords, initial passwords or
  self-service changes to an LDAP directory on another host over `ldap://` without `starttls` is
  refused: add TLS, or `passwords: { allow_cleartext: true }` (loopback and `ldapi://` are
  exempt). The upgrade's configuration check (`syncd validate` with the new binary) tells you
  first.
- **Capture agents.** `syncd password agent add` needs `--source <connector>` for a new agent. An
  agent enrolled before 0.5.0 is bound to no source: it keeps working, with a warning at every
  start, in `syncd status` and in `syncd password agent list`. Bind each with `sudo syncd password
  agent bind <name> --source <connector>`.
- **Role maps add up.** In `CONSOLE_OIDC_ROLE_MAP` (and the new SAML and certificate role maps)
  every entry that matches adds its role; in 0.4.1 the first match won. Review maps that relied on
  their order. Sign-in audit records carry `roles` (a list) instead of `role`: update SIEM
  parsers. With the consent banner on, scripts that sign in with a password must first
  `POST /api/v1/auth/consent`.
- **Other behaviour changes** (the release notes list them all):
    - A `TRUNCATE` of a followed PostgreSQL table now deletes the table's entries in the targets,
      subject to the deletion thresholds (0.4.1 ignored it).
    - An initial password the directory refuses is retried three times, then put in the
      dead-letter queue.
    - Source changes that arrive during a reconciliation, a full sync or a reload are applied
      during it (see [Sizing](availability-and-sizing.md#sizing)).
    - A publication with `drift` also lists its target's unmanaged entries after each scheduled
      drift check (a read-only paged search; `unmanaged: { scheduled: false }` turns it off).
    - The HA snapshot interval's default is 60 s (was 300 s). A value that is set is kept, such
      as the `snapshot_every_secs: 300` of a pair set up from the 0.4.1 instructions: remove it
      to take the new default.
    - The console's master key is checked at start against one sealed secret of each kind. A
      database with secrets sealed under two keys, which 0.4.1 started, may now refuse to start,
      naming what the key does not open.
    - A passphrase file inside `/etc/syncd` is left out of `syncdctl backup --to` archives, with a
      warning: move it to `/etc/syncd-keys/` and back it up apart.
- **The console's database gains migrations 0018 to 0029** (schema 17 to 29): the configuration
  signing key, the sign-in policy, SAML, role assignments (`users.role` is dropped: every account
  keeps its former role, on everything), audit scope columns, API token limits, WebAssembly
  modules, the license, audit filter indexes, the xlsx report format, each version's Git commit,
  and the message of the day with each account's last sign-in. **A rollback to 0.4.1 therefore
  needs the console database backup taken before the upgrade** (see
  [Rolling back 0.5.0 to 0.4.1](#rolling-back-050-to-041)).
- **The store's format changes from 1 to 2.** Each view keeps its own links from a source record
  to its entity, and its own joins made by hand, so that one connector object can feed several
  views (in 0.4.1 such a configuration validated, then stopped the engine with `entity
  EntityId(...) is referenced but missing from the store`). The engine migrates the store at its
  first start, before anything else (`migrating the store`, then `store migrated`): one pass over
  every entity. The release notes measured 300,000 entities with one record each in 1.4 s
  (release build, 4 vCPU); entities with many attributes take longer. Only a store of many
  millions comes near the upgrade's health wait: then raise `SYNCD_HEALTH_TIMEOUT` (see
  [Store format](#store-format)). **A 0.4.1 engine refuses the migrated store**: a rollback
  restores the store backed up before the upgrade, and `syncdctl rollback --keep-data` is refused
  (see [Rolling back 0.5.0 to 0.4.1](#rolling-back-050-to-041)).
- **An HA pair** works mixed during the upgrade: snapshots fall back to whole archives, and the
  password queue is not followed until both engines run 0.5.0. A mixed pair was not run in the
  project's tests. A console and an engine of different minor versions are still not tested.
- **Helm.** New values: `engine.serviceAccount`, `console.serviceAccount`, `engine.configSigning`,
  `engine.extraCaCertificates`, `engine.passwordKeyPassphrase.previousKey`, `engine.ha.lease.kind`
  (`postgres` by default; changing it needs both engine pods stopped first) and
  `console.license` (see [Key values](#3-key-values)). `engine.ha.snapshotEverySeconds` defaults to
  60; `--reuse-values` keeps the old 300. Chart and images move to 0.5.0 together.
- **New alert rules** (21 to 30): `SyncdGroupReviewsDue`, the four `SyncdLicense*` rules,
  `SyncdPasswordAgentCertExpiring`, `SyncdPasswordKeyOld`, `SyncdUnmanagedEntriesGrew` and
  `SyncdUnmanagedPrivilegedMembersGrew` (see [Alert rules](operator-guide.md#alert-rules)). Load
  the new rule file into Prometheus, or upgrade the chart with `prometheusRule.enabled`. New
  webhook events (`config.unpublished-running`, `group.review-due`, `license.state-changed`,
  `quality.errors`) reach an endpoint once they are added to its list.
- **Files kept.** `/etc/syncd/engine/engine.yaml`, `console.env` and `engine.env` stay as you left
  them. The release's templates (`/opt/syncd/current/share/config/`) list the new settings.
- **New files.** On the active engine of a pair, `<data_dir>/ha/snapshots/` (the newest two
  snapshots, hard links to the store's files); on the passive one, `ha/password-queue.replica`.
  Previous password keys (`password.key.<id>`) and a previous agent CA during a rotation. With a
  license installed by `syncdctl`, `/etc/syncd/license.lic`; with signed configurations,
  `/etc/syncd/pki/console-signing.pub`.
- **New package:** `syncd-capture`, for the directory servers' hosts (see
  [The capture agent's package](#the-capture-agents-package)).

### Upgrading from 0.4.0 to 0.4.1

Upgrade as usual, with `syncdctl upgrade`, `apt` or `dnf`, or `helm upgrade` with chart 0.4.1. The
upgrade takes its local backup first, as before. 0.4.1 fixes what documenting 0.4.0 found. What to
know:

- **Nothing changes format.** The store stays format 1, and the console's schema stays 17, so a
  rollback to 0.4.0 keeps the console's database.
- **Seal the password keys (recommended).** Without a passphrase they stay plain files, as in
  0.4.0, and the engine now says so: a warning at start and `password keys: stored in the clear`
  in `syncd status`. Set `SYNCD_PASSWORD_KEY_PASSPHRASE_FILE` in `/etc/syncd/engine.env` (or
  `engine.passwordKeyPassphrase.existingSecret` in the chart) and restart: both key files are
  sealed in place, with the same keys. See [The password keys](operator-guide.md#the-password-keys).
  Backups taken before still hold them in the clear. **A rollback to 0.4.0 cannot open sealed
  keys** (see [Rolling back 0.4.1 to 0.4.0](#rolling-back-041-to-040)).
- **Off-host backups hold the console's master key.** `syncdctl backup --to` writes it as
  `<name>.console-master.key`, and `syncdctl restore --from` puts it back. `--sse`, `--endpoint`,
  `--region` and `--s3-ca` are now accepted by both. In 0.4.0 the upload of `/etc/syncd` to S3 and
  every fetch of `restore --from` failed on file permissions (silently for the fetches), and the
  database dump failed with `--local-database`: take a new off-host backup after the upgrade. See
  [Off-host backups](operator-guide.md#off-host-backups).
- **Behaviour changes:**
    - `SYNCD_HA_MEMBER` and `SYNCD_HA_ADVERTISE` now override `engine.ha.member` and `advertise`
      when set (0.4.0 used the configuration first).
    - `syncd test` finds golden files one directory level down too (`tests/golden/<name>/*.yaml`),
      as the console does.
    - An entity with an open hold or a dead letter keeps all its history until that clears,
      beyond `keep_per_entity` as well as `keep_days` (see
      [Entity history](availability-and-sizing.md#entity-history)).
    - **Environment values** (`GET /api/v1/environments[/{name}]`) need the admin or auditor
      role, or an API token with the new `environments:read` scope (or `admin`); every role still
      reads the names (`GET /api/v1/environment-names`). Scripts that read values with a
      `configs:read` token need the new scope.
    - **Golden tests gate every publish:** a publish through the API, Git auto-publish and
      promotion run the golden tests from Git, like the editor's check, and refuse when they fail.
    - **`register-engine --update` keeps the engine's environment** unless `--environment` is
      given, so `syncdctl renew-certs` and `helm upgrade` no longer move an engine back to
      `default`. The chart's new `engine.environment` passes one.
    - **The console's master key check at start** now tests the key against any kind of sealed
      secret: an engine's client key, else a webhook's URL, else a secret environment value (0.4.0
      tried engine client keys only). A console with a wrong key and only webhooks or secret
      environment values now refuses to start.
- **Helm.** New values: `engine.environment`, `engine.passwordKeyPassphrase`,
  `engine.passwordIngest` and `networkPolicy.passwordIngestFrom` (see [Key values](#3-key-values)).
  Turning on `engine.ha.enabled` now renames the existing `<name>` registration to `<name>-0`
  (see [An active/passive pair](#an-active-passive-pair)). Chart and images move to 0.4.1
  together.
- **New alert rules.** `SyncdPasswordDeliveryFailures` and `SyncdPasswordChangesDropped`, on the
  new metrics `syncd_password_deliveries_total` and `syncd_password_waiting` (see
  [Alert rules](operator-guide.md#alert-rules)). Load the new rule file into Prometheus; the
  chart's PrometheusRule has them.
- **Files kept.** `/etc/syncd/console.env` and `engine.env` are written at install only. The new
  templates (`/opt/syncd/current/share/config/*.env`) have every explanation on its own line; in
  your own files, a comment after a value is still part of the value (see
  [Comments in `console.env`](#the-consoles-own-https)).
- **No new ports by default.** With `engine.passwordIngest.enabled`, the chart adds the ingest
  Service.

### Upgrading from 0.3.0 to 0.4.0

Upgrade as usual, with `syncdctl upgrade`, `apt` or `dnf`, or `helm upgrade` with chart 0.4.0. The
upgrade takes its local backup first, as before. What to know:

- **The store's format does not change** (format 1). 0.4.0 adds column families to the store on
  its first start (persisted timers, held changes, data-quality results and others). That is not
  a format change: 0.3.0 opens whatever column families exist. See [Rollback](#rollback) for what
  0.3.0 then ignores.
- **The console's database gains tables** (schema 8 to 17, migrations 0009 to 0017): the audit
  trail's new format, API tokens, webhooks, the pre-publish hook, environments, Git sync,
  scheduled reports and their mail outbox, the HA lease, and the data-quality daily rollup. A
  rollback to 0.3.0 therefore restores the console's database.
- **The audit trail continues in the ENS format** (`ens-audit/1`). Entries written before the
  upgrade keep their chain. At the first start the console records `audit.format-upgraded`, and
  the first new event continues the old chain's head, so **Verify chain** checks both. See
  [Security](security.md).
- **Audit events on standard output.** The console writes every audit event as one JSON line on
  standard output, so to the journal (`journalctl -u syncd-console`) or the pod log.
  `ENS_AUDIT_STDOUT=false` in `console.env` turns that off.
- **`syncd-console admin register-engine` refuses to register a name again** when the stored
  client key no longer opens with the master key, and says why, instead of answering `registered
  already`. `--update` seals a new key over it, audited as `engine.re-registered` (see
  [Registering engines](#registering-engines)). `POST /api/v1/engines` behaves the same, with
  `replace: true`.
- **Active Directory passwords over plain LDAP are refused at validation.** A publication with a
  `passwords` block whose Active Directory target is reached over `ldap://` without `starttls`
  now fails `syncd validate` and the upgrade's configuration check. Before, it failed when a
  password was written. Switch the target to `ldaps://` or `starttls: true` first (see
  [Password synchronisation](password-sync.md)).
- **Entity history keeps more.** Past `keep_days`, the newest `min_per_entity` entries of each
  entity (default 10) stay, and so does every entry of an entity with an open held change or a
  dead letter. Allow for it on disk: see [Entity history](availability-and-sizing.md#entity-history).
- **The shipped alert rules.** `SyncdMemoryNearLimit` no longer selects `job="syncd"`; only
  `SyncdEngineDown` needs the scrape job's name. Eight rules are new (see
  [Alert rules](operator-guide.md#alert-rules)). Load the new file into Prometheus.
- **Helm: the ServiceMonitor's job label.** The ServiceMonitor now sets its targets' `job` label
  to `engine.metrics.job` (`syncd`). Before, the operator named the job after the Service
  (`<release>-syncd-engine`). Change queries and dashboards that use the old label.
- **Helm: the PKI Secret is kept on `helm uninstall`**, as the console's Secret already was. To
  start over, delete both Secrets and the engine's volume (see [Uninstall](#helm-uninstall)).
  New certificates are ECDSA P-256; an existing install keeps its RSA ones until its PKI Secret
  is deleted.
- **Helm: the registration Job gets `console.env`** too. A `SYNCD_FIPS_MODE` or a database
  option set there now applies to it.
- **Helm: turning on `engine.ha.enabled`** in an existing release needs the StatefulSet deleted
  without its pods, and the PKI Secret deleted, first (see
  [An active/passive pair](#an-active-passive-pair)). The same applies when turning HA off.
- **Files kept.** `/etc/syncd/console.env` stays as you left it. The release's template,
  `/opt/syncd/current/share/config/console.env`, has commented lines for the new settings:
  `ENS_AUDIT_*`, `CONSOLE_API_*`, `CONSOLE_WEBHOOKS_*`, `CONSOLE_REPORTS_*`, `CONSOLE_SMTP_*`,
  `CONSOLE_PUBLISH_*` and `CONSOLE_CONFIG_GIT_*`. None is needed. When you copy one into your
  file, leave out any `# ...` note at the end of the line (see
  [Comments in `console.env`](#the-consoles-own-https)).
- **New files.** The engine's data directory may hold `backups/` (a store checkpoint while a
  backup is taken), `restore/` and `replaced-<time>/` (left by `syncd restore`), and
  `ha/swap.json` and `ha/old-store` (only while an HA engine swaps a snapshot in). `syncd restore`
  puts the backed-up configuration beside the live one as `engine.restored.yaml`. With Git sync
  on, the console keeps its clone under its data directory (`git/`).
- **Packages** now recommend `git`, which Git sync needs. The console's image includes `git` and
  `ssh`.
- **No new ports by default.** The engines of an HA pair reach each other's control API (7443).
- **New in 0.4.0, nothing to do:** change windows, thresholds, canaries and report mode;
  persisted timers and effective dates; API tokens, webhooks, reports and the pre-publish hook;
  configuration as code; off-host and scheduled backups; the support bundle; new connectors. All
  are off until configured.

### Upgrading from 0.2.1 to 0.3.0

Upgrade as below, with `syncdctl upgrade`, `apt` or `dnf`. What to know first:

- **The store's format does not change** (format 1). `syncd store-format` prints it, and from
  0.3.0 `syncdctl` refuses a rollback or restore that the release it would run could not read.
- **The console's database gains a table** (schema 7 to 8, for banners). A rollback to 0.2.1
  therefore restores the console database backed up before the upgrade, and changes made in the
  console since (configuration versions, accounts, audit entries) are lost.
- **Back up the master key, and check it is in place.** The 0.3.0 console refuses to start when
  `/var/lib/syncd/console/master.key` is missing, or does not open the secrets in its database,
  instead of creating a new key (see [The master key](operator-guide.md#the-master-key)).
- **`syncd-console admin reset-password` refuses single sign-on accounts** unless given
  `--allow-single-sign-on` (see [First sign-in](#first-sign-in)).
- **Database connects time out.** Connecting to PostgreSQL, MySQL or SQL Server now fails after
  10 seconds when the server accepts the connection and never answers, and the source is
  retried. Before, it waited forever.
- **Entries moved or renamed in a target by someone else are put back** where the publication
  places them, found by the target's own id (`objectGUID`, `nsUniqueId` or `entryUUID`). Before,
  a second copy was added, or the export was dead-lettered. If people move managed entries by
  hand on purpose, change the publication's DN template instead, or the engine moves them back.
- **LDAP sources read with `stream` or `delta` check their containers** for renames every 60
  seconds (`container_check_secs`; 0 turns it off). See [Connectors](connectors.md).
- **`/run/syncd`.** The engine's unit now creates it (`RuntimeDirectory`), for the optional
  control socket. `syncdctl` and the packages reload the units.
- **Files kept.** `console.env` and `engine.yaml` are kept as they are, so the new commented
  settings of a fresh install (`CONSOLE_TLS_CERT`, `CONSOLE_TLS_KEY`, `api.control_socket`) are
  not added to them. Add the ones you want by hand.
- **New in 0.3.0, nothing to do:** `syncdctl renew-certs` and certificate expiry in
  `syncdctl status`; the console's own HTTPS; quarantined entities kept across restarts
  (`syncd quarantine`); live engine CPU and memory in the console; the audit log's head recorded
  hourly; password synchronisation (off until configured). `syncdctl` also clears systemd's
  start limit before its own restarts, so an upgrade followed at once by `renew-certs` no longer
  fails with `Start request repeated too quickly`.

### Bundle installs

Either command upgrades:

```bash
sudo syncdctl upgrade syncd-0.6.1-linux-x86_64.tar.gz
sudo ./syncd-0.6.1-linux-x86_64/install.sh        # on a host that runs syncd
```

The new release's own `syncdctl` runs the upgrade:

1. It checks the archive against its `.sha256` (when it is next to it), the release against its
   manifest, and that it is newer than the installed one.
2. It checks that the new programs run on this host and that the new engine accepts the current
   configuration (`syncd validate`).
3. It checks there is enough disk space for a backup and, from 0.5.1, that `pg_dump` can dump the
   console's database (a `pg_dump` older than the server's major version refuses).
4. It stops both services and backs up the engine store, the console's files, the console's
   database (with `pg_dump`) and `/etc/syncd`.
5. It switches `/opt/syncd/current` to the new release and starts the services. The console
   updates its database schema when it starts, and the engine migrates its store if the format
   changed (see [Store format](#store-format)).
6. It waits up to 120 seconds for both services to be healthy (`SYNCD_HEALTH_TIMEOUT` changes
   this). If they are not, it rolls back to the previous release and restores the backup by
   itself. From 0.5.1 it removes the failed release's store first, so this needs no more room
   than the backup took; if the restore still fails, the failed release stays current and
   `syncdctl rollback` finishes the job once the cause is fixed.

The services are down from step 4 until the health checks pass. Since 0.5.5 the engine store's
table files (`*.sst`, almost all of it) are hard-linked into the backup rather than copied when
the backups are on the store's file system (the default, `/var/lib/syncd/backups`), so a large
store adds little; with the backups on another file system the whole store is copied, and the
stop grows with the store. Then the new release starts, and migrates the store first when its
format changed. Afterwards, the engine catches up with its sources from its checkpoints. Since
0.5.5 only the services that were running before are started again and checked: a console
stopped on purpose stays stopped (the same holds for `backup`, `restore`, `rollback` and the
package upgrades).

A backup's linked table files are the store's own until the engine compacts them away, after
which the backup keeps them: in time a backup takes as much room as the store. The upgrade's
check counts on that much room in `/var/lib/syncd/backups`, and a tenth more.

| Option | Effect |
|---|---|
| `--no-rollback` | Leaves a failed upgrade in place for investigation |
| `--skip-db-backup` | Leaves the console's database out of the backup: when `pg_dump` is not installed, or cannot dump the server. From 0.5.1 it always leaves it out (0.5.0 only when `pg_dump` was missing). Without the dump, a schema change by the new release cannot be undone: since 0.5.5 the upgrade says so before it starts, its own rollback then leaves the new release in place, and `syncdctl rollback` refuses until the database is back at its old schema (restore it from a backup of your own first). Upgrading from 0.5.4 changes the schema (30 to 33) |
| `--reinstall` | Installs the same version again |
| `--yes` | Asks no questions |

Only one `syncdctl` runs at a time; since 0.5.5 an interrupted run starts syncd again and leaves
no half backup. The last three backups are kept (`SYNCD_KEEP_BACKUPS` changes this; the newest,
taken by the upgrade itself, is always kept, so 0 counts as 1), and so are the current and the
previous release; older releases are removed.

**A pair on hosts.** Upgrade the passive engine's host first, then the active one's: stopping the
active engine hands the lease to the upgraded one at once. Both engines should run the same
release. Between 0.4.1 and 0.5.0 a mixed pair works while you upgrade: snapshots are sent whole,
and the passive engine follows the password queue only once both run 0.5.0. Before upgrading a
strict pair that delivers passwords to 0.5.1, list the passive engine's certificate under
`api.access.passwords` on both hosts (see [Passwords and the pair](#passwords-and-the-pair)).

### Package installs

Upgrade with the package manager:

```bash
sudo apt install ./syncd_0.6.1_amd64.deb
sudo dnf upgrade ./syncd-0.6.1-1.x86_64.rpm          # RHEL 8: ./syncd-0.6.1-1.el8.x86_64.rpm
# From the package repositories, once they are published:
sudo apt update && sudo apt install syncd
sudo dnf upgrade syncd
```

Before the new files are unpacked, the installed release stops syncd and takes the same backup.
Afterwards the new release starts the services and checks they are healthy. Unlike a bundle
upgrade, a package upgrade does not check the configuration first, and does not roll back by
itself: a failed health check fails the package's configuration step and names the backup.

- **Checks before anything stops** (from 0.5.1, when the installed release is 0.5.1 or newer):
  room for the backup, and a `pg_dump` that can dump the console's database. When the backup
  fails, the upgrade stops there and syncd is started again as it was (from 0.5.1 the new
  package's script does this, whatever the installed release).
- **Without a working `pg_dump`**, leave the database out of the backup with
  `SYNCD_SKIP_DB_BACKUP=1` in the package manager's environment:
  `sudo SYNCD_SKIP_DB_BACKUP=1 apt install ./syncd_0.6.1_amd64.deb`, or the same with `dnf`.
  0.5.0's `syncdctl`, which takes the backup when you upgrade from 0.5.0, honours it only when
  `pg_dump` is not installed at all.
- **To go back,** install the previous package version (`sudo apt install syncd=<version>`,
  `sudo dnf downgrade syncd-<version>`, or the downloaded file), then run
  `sudo syncdctl restore <backup>` with the backup taken when that version was upgraded,
  `<time>-upgrade-<old>-to-<new>`. Back on the older package, its health check fails while the
  data is still the newer release's (0.5.0's console does not start on 0.5.1's database); 0.5.1
  and newer then name the right backup, but 0.5.0 may name another: restore the
  `*-upgrade-0.5.0-to-0.5.1` one.

With the repositories configured, an upgrade comes whenever the package manager runs, and it stops
both services for its backup. Choose when: hold the package between maintenance windows (`sudo
apt-mark hold syncd`; `exclude=syncd` in `/etc/yum.repos.d/syncd.repo`, lifted with
`--disableexcludes=syncd`), in particular on hosts with unattended upgrades.

## Rollback

```bash
sudo syncdctl rollback
```

This goes back to the previous release (bundle installs only):

- The engine store and `/etc/syncd` are restored from the backup taken before the upgrade. The
  engine picks up changes made since then when it next reads its sources.
- The console's database is restored only if the newer release changed its schema, or (since
  0.5.5) the schema is not known because the database did not answer when the backup was taken,
  or does not answer now. Otherwise its contents are kept: configuration versions, accounts and the audit trail written since the
  upgrade, and so are the control API's certificates in `/etc/syncd/pki`, which the console's
  stored credentials for the engine match. From 0.4.0, a restore first drops the tables the newer
  release added, so the database comes back as it was when it was dumped.
- `--keep-data` switches the programs back and restores nothing. It is refused when the previous
  release cannot read the engine store, because the newer release migrated it to a newer format,
  and from 0.5.1 also when the console's database schema is not the one the previous release's
  console had (recorded in the backup taken before the upgrade): that console would not start on
  it. Since 0.5.5, when that cannot be checked (no backup records the schema, or the database does
  not answer), it is refused too, unless `--skip-schema-check` is added.
- **A backup without the database** (taken with `--skip-db-backup` or `SYNCD_SKIP_DB_BACKUP=1`):
  since 0.5.5 the rollback keeps the database when its schema is the one the previous release
  had, and is refused, before anything is stopped, when it is not or is not known. Restore the
  database from a backup of your own first.
- **Room.** A restore copies the backup's engine store and console files in beside the live ones
  before replacing them. From 0.5.1 `rollback` and `restore` check first that `/var/lib/syncd`
  has room for that copy, and change nothing when it has not. The data is restored before the
  programs are switched back, so a rollback whose restore fails can be run again.
- `--yes` asks no questions.

After a rollback there is no previous release to go back to again. On a package install, rollback
is refused: install the older package, then `syncdctl restore` the backup named
`<time>-upgrade-<old>-to-<new>`. More on recovery is in
[Sizing, availability and recovery](availability-and-sizing.md#rolling-back-an-upgrade).

### Rolling back once governance is in use

In development (governance, not in a release yet). Governance keeps its records in the
console's database, and each release that adds governance tables changes the schema, so
a rollback restores the database dumped before the upgrade, and with it the governance
records as they were then: requests, approvals, review decisions and assignments made
since the upgrade are gone, as are the grants the console sent since. An older console
refuses a database a newer one migrated and changes nothing in it (it stops at start with
`migration <n> was previously applied but is missing`): there is no partial rollback.
The engine store comes back from the same backup, so the two agree; after restoring only
one of them, run `syncd-console governance redeliver --engine <name>` (see
[Security, backups](security.md#backups)).

### Rolling back 0.6.1, 0.6.0 or 0.5.5

- **0.6.1 to 0.6.0**: nothing to restore. The schema (33), the store's format (2) and the
  configuration are the same; `syncdctl rollback --keep-data` keeps everything.
- **0.6.0 to 0.5.5**: the schema and the store's format are the same, but 0.5.5 has no `dsml`
  connector. Before rolling back with the store kept, run `syncd reconcile` on an engine with a
  DSML target, which writes out the exports waiting for the end of their interval (0.5.5 ignores
  them), then publish a configuration without the `dsml` connector: 0.5.5 refuses one with it.
- **To 0.5.4**: the console's schema went from 30 to 33, so `syncdctl rollback` restores the
  database backup taken before the upgrade, and what was done in the console since is lost;
  `--keep-data` is refused. The store's format is the same, so a package downgrade followed by
  `syncdctl restore` of that backup, or `helm rollback` after restoring the database, works the
  same way. 0.5.5's fixes go with it.

### Rolling back 0.5.4 to 0.5.2 or 0.5.1

- **The console's database is kept**: 0.5.4 did not change its schema (30).
- **The engine store** is restored from the backup taken before the upgrade by a full `syncdctl
  rollback`. Its format is the same (2) and 0.5.4 adds nothing to it, so `syncdctl rollback
  --keep-data`, a package downgrade and `helm rollback` can keep it.
- **The configuration stays valid**: 0.5.4 adds no settings, only the two warnings.
- **What 0.5.4 changed comes back**: values made of several related entities take their order
  from what happened since the engine started again, and a group's member changes evaluate the
  rules of all its members. Back on 0.5.1, 0.5.2's faster joins and its behaviour changes go too
  ([Upgrading from 0.5.1 or 0.5.2 to 0.5.4](#upgrading-from-051-or-052-to-054)).

### Rolling back 0.5.1 to 0.5.0

- **The console's database is restored**, since 0.5.1 changed its schema (29 to 30). `syncdctl
  rollback` does this from the backup taken before the upgrade; everything done in the console
  since is lost. `syncdctl rollback --keep-data` is refused, and 0.5.0's console does not start
  on the newer database, so a package downgrade or `helm rollback` needs the database restored
  too.
- **The engine store** comes back from the same backup. Its format is the same (2): 0.5.0 would
  open 0.5.1's store and ignore `unique_values/<view>`, but the database cannot stay, so keep
  both from the backup.
- **What 0.5.1 fixed comes back**, the critical fixes included, and so do the configurations and
  the access 0.5.0 allowed ([Upgrading from 0.5.0 to 0.5.1](#upgrading-from-050-to-051)).
  Prefer fixing forward.

### Rolling back 0.5.0 to 0.4.1

- **The console's database is restored**, since 0.5.0 changed its schema (17 to 29). `syncdctl
  rollback` and the upgrade's own backup do this; everything done in the console since the upgrade
  is lost (configuration versions, role assignments, licenses, audit entries). From 0.5.0 the
  restore also drops the database functions a newer release added, so upgrading again after the
  rollback works (before, it failed at migration 0024).
- **The engine store is restored too**: 0.5.0 migrated it to format 2, which **a 0.4.1 engine
  refuses** (`the store has format 2, written by a newer syncd`). `syncdctl rollback` restores
  the backup taken before the upgrade; `syncdctl rollback --keep-data` is refused. With a package
  downgrade or `helm rollback`, restore the store from the backup taken before the upgrade
  first. What happened since is read again from the sources; what 0.5.0 kept in the store
  (password agents and queued changes, previous password keys, group-review timers, the license
  ledger) goes with it: agents enrolled since enroll again.
- **0.4.1 refuses settings it doesn't know.** The configuration file is restored with the store
  on a host. Where it is kept (on Kubernetes, the file on the engine's volume, unless you restore
  the volume), publish a configuration without the settings new in 0.5.0 (`api.access`, `engine.require_signed_config`,
  `engine.license_file`, `engine.wasm_functions`, the `kubernetes` lease, `connection.ca_file`,
  `passwords.allow_cleartext`, `vault`, `aws_secret` and `azure_keyvault` references, the new Entra
  `auth` methods, the `rest` and HR connectors, `unmanaged`, `reviewers`, `placeholder_member`,
  ...). The host settings among them must be removed from the engine's file on its host.
- **A pair.** A pair on the Kubernetes lease goes back to the PostgreSQL lease with chart 0.4.1:
  stop both engine pods first, as for any change of lease kind.

### Rolling back 0.4.1 to 0.4.0

- **The console's database is kept**: 0.4.1 did not change its schema.
- **0.4.0 cannot open sealed password keys.** A full `syncdctl rollback` restores the engine
  store, with the plain key files, from the backup taken before the upgrade. With the store kept
  (`syncdctl rollback --keep-data`, `helm rollback`, or a package downgrade), put the plain files
  back first: copy `password.key` and `password-agent-ca.key` from the upgrade's backup
  (`/var/lib/syncd/backups/<time>-upgrade-0.4.0-to-0.4.1/engine/`) into `/var/lib/syncd/engine/`,
  owner `syncd`, mode 0600; on Kubernetes, from a backup taken before
  the keys were sealed. The keys are the same, so nothing queued is lost. Otherwise the 0.4.0
  engine does not start while password synchronisation is configured.
- **What 0.4.1 fixed comes back**, as described in [Upgrading from 0.4.0 to
  0.4.1](#upgrading-from-040-to-041): among others, `--update` moves an engine back to `default`,
  and `syncdctl backup --to` leaves out the master key and refuses the S3 options.

### Rolling back 0.4.0 to 0.3.0

- **The console's database is restored**, since 0.4.0 changed its schema. Everything recorded in
  the console since the upgrade is lost: configuration versions, accounts, API tokens, webhooks,
  environments, scheduled reports and audit entries. Export what you need first.
- **A full rollback restores the engine store** taken before the upgrade, so what 0.4.0 kept in
  it is gone, and the engine catches up from its checkpoints.
- **With the store kept** (`syncdctl rollback --keep-data`, or `helm rollback`), 0.3.0 opens it,
  but does not act on what 0.4.0 keeps there: staged leaver steps, effective dates, change
  windows, canaries and group expiry wait on timers it ignores, and it knows nothing of held
  changes. A change held by 0.4.0 is applied, or not, by 0.3.0's own rules.
- **0.3.0 refuses settings it doesn't know.** Before rolling back with the store kept, publish a
  configuration without the settings new in 0.4.0 (change windows, thresholds, canaries,
  `mode`, `deprovision`, `passwords.initial`, `group_policy`, `quality`, `packages`,
  `correlation`, `engine.backup`, `engine.ha`, `min_per_entity`, the new connectors, ...).
  Otherwise 0.3.0 refuses to start with it.
- **Take the pair apart first.** 0.3.0 has no HA: stop the passive engine (or turn
  `engine.ha.enabled` off on Kubernetes, as in
  [An active/passive pair](#an-active-passive-pair)) and remove `engine.ha` before rolling back.
- **Audit outputs.** Events sent to Splunk, syslog or the audit file since the upgrade stay
  there, but are gone from the restored database.

### On Kubernetes

```bash
helm rollback syncd -n syncd          # the previous revision
```

`helm rollback` goes back to the previous images and values. It restores no data: the engine's
volume, the console's database and the Secrets stay as the newer release left them, and the
registration Job does not run (it runs after installs and upgrades only). That is enough when the
newer release changed neither the store's format nor the console's schema. Otherwise, restore the
backups taken before the upgrade first: the older engine refuses a store format it cannot read.
See [Backups on Kubernetes](availability-and-sizing.md#backups-on-kubernetes).

From chart 0.5.4 to 0.5.2 or 0.5.1, neither changed: `helm rollback` is enough (see
[Rolling back 0.5.4 to 0.5.2 or 0.5.1](#rolling-back-054-to-052-or-051)).

From chart 0.5.1 to 0.5.0, the console's schema changed (29 to 30): restore the console's
database dump taken before the upgrade into an empty database (drop and create it first). The
store's format is the same (2); restore the engine's volume from the same time too, so that the
store and the database agree (see [Rolling back 0.5.1 to 0.5.0](#rolling-back-051-to-050)).

From chart 0.5.0 to 0.4.1, both changed: restore the console's database dump taken before the
upgrade into an empty database (drop and create it first), and the engine's store from a backup
taken before the upgrade (`syncd restore --from ... --replace` with the engine scaled to 0, or a
volume snapshot), since 0.4.1 refuses the format-2 store. A store restored with `syncd restore`
leaves the configuration file on the volume as it is: publish a configuration without 0.5.0's
settings first (see [Rolling back 0.5.0 to 0.4.1](#rolling-back-050-to-041)).

From chart 0.4.1 to 0.4.0, neither changed, but put the plain password key files back on the
engine's volume first if the keys were sealed (see
[Rolling back 0.4.1 to 0.4.0](#rolling-back-041-to-040)).

From chart 0.4.0 to 0.3.0, the console's schema changed: restore the console's database dump
into an empty database (drop and create it first). `pg_restore --clean` alone leaves the tables
0.4.0 added. Publish a configuration without 0.4.0's settings first, as above, since the
configuration file on the engine's volume is kept.

## Store format

The engine store has a format: a number for the layout of its data. `syncdctl status` shows it,
and `syncd store-format` prints it (`--supported`: the newest format this `syncd` reads).

- A release that changes the format says so in its release notes. When its engine first starts on
  an older store, it migrates the store before anything else, and logs each step
  (`migrating the store`, then `store migrated`).
- A migration that stops part way (a crash, a power cut) starts again at the next start; the store
  keeps its old format until the migration completes.
- A store migrated by a newer release cannot be read by an older one. The older engine refuses to
  start: `the store has format N, written by a newer syncd`. `syncdctl rollback` restores the
  backup taken before the upgrade; `syncdctl restore` and `syncd restore` refuse a backup whose
  store the installed release cannot read.
- A large store can take longer to migrate than the upgrade waits for the engine to be healthy.
  For such an upgrade, set `SYNCD_HEALTH_TIMEOUT` as the release notes suggest.

Stores written by 0.2.1 and older record no format: they are format 1. 0.3.0 started recording
it. **0.5.0 moved it to format 2**, which keeps a link from each source record to its entity for
every view that reads it, where format 1 kept one for all views; 0.5.0 and newer migrate a
format-1 store at their first start, and 0.4.1 and older refuse a format-2 one. 0.5.1, 0.5.2 and
0.5.4 keep format 2. New column families, such as those 0.4.0 adds and 0.5.1's
`unique_values/<view>`, are not a format change.

## Uninstall

```bash
sudo syncdctl uninstall               # keeps /etc/syncd and /var/lib/syncd
sudo syncdctl uninstall --purge       # removes them, the backups and the service accounts too
```

Both stop and disable the services, remove the units, the command links and `/opt/syncd`. `--yes`
asks no questions. Neither drops the console's database (nor the `syncd_lease` table of a pair):
drop it yourself (`DROP DATABASE syncd_console`) if you no longer need it. Off-host backups stay
where they were written.

On Kubernetes, see [Uninstall with Helm](#helm-uninstall).

Package installs are removed with the package manager (`apt remove syncd`, `dnf remove syncd`).
That stops and disables the services; `/etc/syncd` and `/var/lib/syncd` stay.

## First start checklist

- [ ] You verified the download: `sha256sum -c SHA256SUMS --ignore-missing`, and for a signed
      release `gpg --verify SHA256SUMS.asc SHA256SUMS` against the release key's fingerprint
      (0.5.0 to 0.6.1 are unsigned).
- [ ] `sudo syncdctl status` shows the engine `active, control API answers` and the console
      `active, http://127.0.0.1:8080 answers`.
- [ ] The console is served over HTTPS (its own certificate, or nginx with
      `CONSOLE_TRUSTED_PROXIES`), and `console.env` sets `CONSOLE_PUBLIC_URL`.
- [ ] `sudo syncdctl status` shows when the control API's certificates expire.
- [ ] You signed in as `admin` and changed the one-time password.
- [ ] A license is installed, or requested with the installation id from the **License** page
      before the evaluation and grace period run out (see [The license](#the-license)).
- [ ] `sudo syncd status` says `control API access: strict` (an upgraded host: `sudo syncdctl
      api-access strict`, then import the running configuration in the console).
- [ ] `/var/lib/syncd/console/master.key` is backed up somewhere safe (see
      [Operator guide](operator-guide.md#the-master-key)).
- [ ] The secrets your configuration refers to are in `/etc/syncd/engine.env`, and
      `sudo syncd test-connection <connector>` connects for each connector.
- [ ] Monitoring is set up: `api.metrics_listen` and the shipped alert rules (see
      [Operator guide](operator-guide.md#monitoring)).
- [ ] The audit trail reaches your SIEM, through the journal or the `ENS_AUDIT_*` outputs.
- [ ] With password synchronisation, the keys are sealed: `sudo syncd status` says `password
      keys: sealed with the passphrase`, and the passphrase is kept apart (see
      [The password keys](operator-guide.md#the-password-keys)).
- [ ] Backups leave the host: `engine.backup` on a schedule, or `sudo syncdctl backup --to
      <dir or s3://...>`, and you have taken a first one.

On Kubernetes: the images and the chart are verified with cosign; the engine pod `syncd-engine-0`
is ready and `kubectl -n syncd exec syncd-engine-0 -- syncd status` answers; the registration Job
finished (`syncd-register` is deleted once it succeeds; a failed one stays, for `kubectl logs`);
you signed in through the Ingress and changed the password; a license is installed (the License
page, or `console.license.existingSecret`); the `syncd-console` Secret is backed up; the engine Secret holds the configuration's secrets; with password synchronisation, the
passphrase Secret is set (`engine.passwordKeyPassphrase`) and kept apart; the ServiceMonitor (or a scrape job of
your own) reads port 9464 and the PrometheusRule is loaded; `engine.backup` writes to S3.
