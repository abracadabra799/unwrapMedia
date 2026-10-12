# C2PA test fixtures

Files in `app/src/test/resources/c2pa/`:

| File | Size (bytes) | Source |
|---|---|---|
| C.jpg | 132518 | `contentauth/c2pa-rs` `sdk/tests/fixtures` (MIT OR Apache-2.0), unmodified |
| CA.jpg | 166864 | same — manifest store split across two APP11 segments, ingredient chain |
| video1.mp4 | 828571 | same — BMFF `uuid` store with two manifests |
| no_manifest.jpg | 97591 | same — no C2PA |
| signed.heif | 307167 | c2pa-rs `sample1.heif`, signed with c2patool 0.27.22's built-in test certificate (C2PA 2.x labels) |
| signed_small.webp | 63854 | c2pa-rs `sample1.webp`, signed the same way (no thumbnail, to keep it small) |

Regenerate the two signed files (c2patool warns that it uses its built-in test key — expected; the new files get new manifest UUIDs):

    brew install c2patool
    c2patool sample1.heif -m docs/validation/c2pa-fixtures/manifest.json -o signed.heif -f
    c2patool sample1.webp -m docs/validation/c2pa-fixtures/manifest.json -o signed_small.webp -f

`c2patool <file>` prints a *normalised* view (for example it reports the stored v1 label `c2pa.actions`
as `c2pa.actions.v2`). Tests assert raw labels and fields as stored in the file, read with the
independent Python tools in `tools/` (no dependencies, run with `python3 -I`):

| Script | Prints |
|---|---|
| `tools/c2pa_probe.py <files>` | container layout (APP11 segments with CI/En/Z/LBox, BMFF top boxes + C2PA `uuid`, WebP chunks) and the JUMBF tree with labels/toggles |
| `tools/c2pa_dump.py <files>` | decoded claim, assertions (CBOR/JSON/thumbnail) and COSE_Sign1 headers per manifest |
| `tools/c2pa_certs.py <files>` | COSE `alg`, x5chain length and the leaf certificate subject/issuer (needs `openssl`) |
