# ENS license tool

Issues and checks the license files that unlock the licensed features of CertAlert, Directory
Services Portal, ReadyRoom and RSA Operations Portal. It is a single Java source file, so a
JDK 17 or later is all it needs. There is nothing to build.

```bash
# 1. Once: make the signing key pair. Keep the private key offline and out of every repository.
java LicenseTool.java keygen --out keys/ens-license

# 2. Per customer and product: issue a license
java LicenseTool.java sign --key keys/ens-license.private.pem \
    --product readyroom --licensee "Acme Corp" --expires 2027-12-31 \
    --out acme-readyroom.lic

#    only some features:
java LicenseTool.java sign --key keys/ens-license.private.pem \
    --product cert-alert --licensee "Acme Corp" --expires 2027-12-31 \
    --features revocation,metrics --out acme-cert-alert.lic

# 3. Check a license against a public key
java LicenseTool.java verify --key keys/ens-license.public.pem --license acme-readyroom.lic
```

Products: `cert-alert`, `directory-services-portal`, `readyroom`, `rsa-operations-portal`.
`--expires never` issues a perpetual license; `--features` defaults to `*` (every licensed
feature). The feature ids for each product are listed in
[docs/licensing.md](../../docs/licensing.md).

## Rotating the key

Each application verifies licenses with the public key built into it, at
`src/main/resources/license/ens-license-public.pem` (in Directory Services Portal, under
`backend/`; in RSA Operations Portal, under `rsaapp-portal/`). The key is deliberately not
configurable at run time: an installation that could name its own key could sign its own
license. To change keys, run `keygen`, copy the new `.public.pem` over that file in all four
repositories, and release new images. Licenses signed with the old key stop working in those
images.
