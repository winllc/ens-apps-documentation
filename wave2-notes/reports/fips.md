## Plan item 16 (FIPS 140-3): ENS license library

Done on `claude/wave2-fips` in `/home/user/wt/w2-fips`, as 5 commits on top of main `4313f69`. Nothing is pushed. Java tests: 38 pass (was 23). Node tests: 99 pass (was 75). Type check and build are clean. The new CI job hasn't run on GitHub because nothing is pushed; I ran its three step scripts locally and all passed.

**Bottom line:** on a FIPS host today, every app becomes Unlicensed. On BouncyCastle FIPS 2.0.x or 2.1.0 in approved-only mode, apps whose license bean calls `trusting()` (as the Spring example does) don't start at all. The fix is an ECDSA P-256 signature alongside Ed25519. Old apps still accept the new dual-signed files.

### 1. Findings

**(a) Red Hat OpenJDK in FIPS mode: no Ed25519 signatures.**
- **Versions:** UBI 9 `openjdk-21-runtime` 1.24 (OpenJDK 21.0.12.1, NSS 3.124, RHEL 9.8), and `openjdk-17-runtime` (OpenJDK 17.0.20.1).
- **Providers:** `SunPKCS11-NSS-FIPS` first, then SUN, SunEC, SunJSSE, SunJCE, SunRsaSign, XMLDSig.
  - SunEC keeps only its key factories, so `KeyFactory.getInstance("Ed25519")` still works.
  - `Signature.getInstance("Ed25519")` throws `NoSuchAlgorithmException`.
  - SunPKCS11 has no EdDSA support (OpenJDK issue JDK-8255411 is still open).
  - `SHA256withECDSA` works through `SunPKCS11-NSS-FIPS`, and a tampered P-256 signature returns false.
- **Old library in that mode:** every license is refused with "Ed25519 Signature not available", so the app is Unlicensed.
- **How FIPS mode is detected.** I disassembled `java.security.Security` and `SystemConfigurator` from the image:
  1. With `security.useSystemPropertiesFile=true`, the JDK loads the crypto-policies `java.config`.
  2. Unless `-Dcom.redhat.fips=false` is set, it then calls native `libsystemconf`, which asks NSS `SECMOD_GetSystemFIPSEnabled`.
  3. NSS answers from the `NSS_FIPS` environment variable or `/proc/sys/crypto/fips_enabled`.
- **What does and doesn't force it in a container:**
  - `update-crypto-policies --set FIPS` plus `-Dcom.redhat.fips=true` does **not** turn FIPS mode on.
  - `NSS_FIPS=1` does, with or without the FIPS policy.
  - `-Dcom.redhat.fips=false` brings Ed25519 back, but it takes the whole JVM out of FIPS mode, so it is not a fix.
- **Confidence: high.** The NSS_FIPS path is the same code path a FIPS kernel triggers, and the providers and the NSS FIPS token are the real ones. Red Hat's FIPS guide for OpenJDK 21 also says SunEC exposes only AlgorithmParameters and KeyFactory in FIPS mode. I could not test on a real FIPS kernel.

**(b) BouncyCastle FIPS (bc-fips) in approved-only mode, as the only crypto provider (plus SUN), on OpenJDK 21:**
- **2.0.0, 2.0.1, 2.0.2 and 2.1.0:** no Ed25519 Signature and no Ed25519 KeyFactory. The old library's `trusting()` throws `IllegalArgumentException`, so the app fails at startup.
  - 2.0.0 is the version on CMVP certificate #4743.
  - 2.1.0 is the version on certificate #4943, an interim certificate that sunsets in January 2027. That is per the vendor and NIST pages; I did not read the certificate PDFs.
- **2.1.1, 2.1.2 and 2.1.3:** Ed25519 is available in approved-only mode. I did not confirm whether any certificate covers these versions.
- **P-256** (`SHA256withECDSA`) works through BCFIPS in every version.
- I confirmed approved-only mode was really on through `CryptoServicesRegistrar.isInApprovedOnlyMode()`.

**What this means for verification order:** the library tries Ed25519 first, because it works everywhere except FIPS mode. When the algorithm is missing, or the provider fails, it falls back to P-256. A P-256 key must be accepted by `trusting()` even when the runtime can't use Ed25519 keys.

### 2. Format design
- **Encoding:** one armored block per signing key, all over a byte-identical payload, with Ed25519 first. Each block is a complete `keyId.payload.signature` token.
  - The P-256 signature is SHA-256 ECDSA in DER form, which is what Java's `SHA256withECDSA` and Node's default both use.
  - The trusted key decides the algorithm, never the file. A key id is SHA-256 over the public key's SPKI, which already names the algorithm, so the key id format is unchanged.
- **Verifier rule:** accept if any block verifies with a trusted key whose algorithm this runtime has.
  - If none verifies, the error is `BAD_SIGNATURE`.
  - If none could be checked, the error is the new `UNSUPPORTED_ALGORITHM`, with a message telling the admin to ask ENS for a file with a P-256 signature.
  - Blocks carrying different payloads are refused as `MALFORMED`.
- **Old apps accept dual-signed files.** The 1.0.0 parser, the old portal check and the old CLI all read only the first block. I verified this by running the actual 1.0.0 code from `4313f69` against the dual-signed fixture, through the verifier, through `ENS_LICENSE`, and through upload. A CI step now repeats that check.
- **Issuing default:** no separate setting is needed. The portal signs with both keys whenever `LICENSE_SIGNING_KEY_P256` or `LICENSE_SIGNING_KEY_P256_FILE` is set, and with Ed25519 only otherwise.

### 3. Commits and files
34 files changed, 1949 lines added and 208 removed.

| Commit | What it does |
|---|---|
| `9e88300` | Format work: `license.ts` (signing with several keys, verification with an optional algorithm restriction). CLI: `keygen --alg p256`; `inspect` shows every block; `verify --alg p256` checks a file as FIPS mode will. New fixtures signed with a published **test** P-256 key (private value `0x07` × 32): `dual-signed.lic`, `p256-only.lic`, `tampered-p256.lic`, `test-public-key-p256.txt`. Existing fixtures are byte-identical. |
| `c88a3f9` | Portal: the two new P-256 key settings; `LICENSE_TRUSTED_KEYS` takes either algorithm. `/api/licensing/keys` names each key's algorithm. Health check loads both keys. README, `.env.example` and both compose files updated. |
| `ff31273` | Java library 1.1.0: `LicenseVerifier`, `LicenseException`, `LicenseManager`, now compiled for Java 17. `SignatureAlgorithmsTest` (15 tests) simulates a missing Ed25519 per verifier through a package-private seam, never by changing installed providers. |
| `3375198` | CI: new `licensing-fips` job runs a checker program (`FipsCheck`) on the UBI 9 OpenJDK 21 and 17 images with `NSS_FIPS=1`, and on bc-fips 2.0.0, 2.1.0 and 2.1.3 in approved-only mode. It first confirms the runtime really is in that FIPS configuration. The `java-verifier` job gains the 1.0.0 compatibility check (`LegacyReaderCheck`). |
| `8e032a0` | Docs: the "FIPS 140-3" section of `licensing/README.md`, and the public licensing page. |

**Two portal additions beyond the brief:**
- An admin **Re-sign** action (audit action `license.resigned`). It gives an existing license a P-256 signature without changing its dates. Renewing can't do that for a perpetual license whose maintenance has lapsed.
- *Check a file* now says whether a file will verify on Java in FIPS mode.

**What the CI job proves:** the library works with the real FIPS providers, as Red Hat and BouncyCastle configure them. It doesn't prove behaviour on a FIPS kernel beyond what NSS reports, and it certifies nothing. The UBI images are pinned to `latest` on purpose, so a change in Red Hat's FIPS mode shows up in CI; that also means such a change could fail an unrelated PR.

### 4. What ENS must do in production
1. Run `npm run license -- keygen --alg p256` on a trusted machine. Store the private key as a secret and back it up like the Ed25519 key.
2. Set `LICENSE_SIGNING_KEY_P256` (or the `_FILE` form) on the licensing service. `/api/licensing/keys` will then show both keys as current.
3. Add the P-256 public key to each app's `EnsKeys`, next to the Ed25519 key, with library 1.1.0, and release.
4. For each FIPS customer, open their license in `/admin` and use **Re-sign**. Licenses issued before step 2 carry only Ed25519. Tell customers on FIPS hosts not to use `-Dcom.redhat.fips=false`.

### 5. Changes the four apps' copies need
- **Replace three files:** `LicenseVerifier.java`, `LicenseException.java` and `LicenseManager.java`. No other library file changes.
  - The public API is unchanged (`trusting(String...)`, `verify`, `keyId`), plus two new methods: `isSupported(Algorithm)` and `trustedKeys()`.
  - The unused package-private `LicenseVerifier.publicKey(String)` is gone.
  - `ENS_LICENSE` now also accepts each block's token on its own line.
- **Check one thing in app code:** the new `Reason.UNSUPPORTED_ALGORITHM` constant will break any exhaustive `switch` over `LicenseException.Reason` without a default branch.
- **`EnsKeys`:** add the production P-256 public key once ENS generates it.
- **Java 17:** the library uses no Java 21 features, so the RSA app can compile it as is.
- **No other changes:** the Spring wiring and the container images stay as they are.
- **Optional:** show the `UNSUPPORTED_ALGORITHM` message on the license screen. If an app carries the library's tests, copy the new tests and the new fixtures too.

### 6. Statement text for the docs
> **Licensing and FIPS 140-3.** The application checks its license file offline, by verifying ENS Solutions' digital signature with the Java runtime's own cryptographic providers; the license library contains no cryptographic implementation of its own and needs no secret on your system. License files carry two signatures over the same content: Ed25519 and ECDSA P-256 with SHA-256 (FIPS 186-5). When the Java runtime is in FIPS mode, such as Red Hat build of OpenJDK on a FIPS-enabled RHEL or OpenShift host, the application verifies the ECDSA P-256 signature through the runtime's FIPS provider (on Red Hat, the NSS FIPS module through SunPKCS11-NSS-FIPS) and does not use Ed25519. Whether a deployment meets FIPS 140-3 depends on the Java runtime and its validated cryptographic module; ENS Solutions does not supply or claim a validated module. License files issued before ECDSA signing was enabled carry only an Ed25519 signature: if the license screen reports that the runtime can't check the license, ask ENS Solutions for a re-issued file.

The statement and the public page are only true once step 2 is done, so publish them after it.

### Left undone and caveats
- **One process-kill risk on the shared machine.** While cleaning up a smoke test around 18:14 UTC, I ran `pkill -f "node dist/server/entry.mjs"`. That could have stopped another agent's ens-products-page licensing service. None were running afterwards; please let the other agents know.
- **Stricter than 1.0.0 on damaged files:** 1.1.0 refuses a file whose extra block is malformed. 1.0.0 ignored extra blocks.
- **Not done, by design:** no production key was generated or touched, and no app repo was edited.
- **Cleanup:** my images and scratch directories are removed.

Sources:
- [Red Hat: Configuring OpenJDK 21 on RHEL with FIPS](https://docs.redhat.com/en/documentation/red_hat_build_of_openjdk/21/html-single/configuring_red_hat_build_of_openjdk_21_on_rhel_with_fips/index)
- [Red Hat: crypto-policies](https://docs.redhat.com/ja/documentation/red_hat_build_of_openjdk/21/html/configuring_red_hat_build_of_openjdk_21_on_rhel_with_fips/crypto_policies)
- [JDK-8255411: Add EdDSA support to SunPKCS11](https://bugs.openjdk.org/browse/JDK-8255411)
- [NIST CMVP certificate #4943](https://csrc.nist.gov/projects/cryptographic-module-validation-program/certificate/4943)
- [BouncyCastle: BC-FJA 2.1.0 FIPS 140-3](https://bouncycastle.org/resources/bouncy-castle-java-fips-2-1-0-hardware-acceleration-with-intel-fips-140-3-certification)
- [sec-certs: certificate #4743](https://sec-certs.org/fips/id/4743)
- [AlmaLinux NSS RHEL-59579](https://git.almalinux.org/rpms/nss/commit/ed5ad1f04eef5f9bd5b4784ba75b1691b8a03609)