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

## Galaxy S26 originals (user supplied, 2026-10-12)

Copied byte-for-byte at the user's request, without re-signing or removing metadata.
These personal device fixtures do not inherit the public c2pa-rs fixture licence.

| Fixture | Original filename | Bytes | SHA-256 |
|---|---|---:|---|
| s26-motion-photo.heic | 20261012_131852_s26.heic | 8509243 | 6757284ff12a530e91fa190e2e79894398934663d3544f7b4aa3d305640a6664 |
| s26-camera.jpg | 20261012_131827_ㄴ26.jpg | 9638500 | d87f7806ef3f91f41c8444f722070c52e213399acff39d91e1b2c3293a70d128 |

Independent raw probe/dump and c2patool both find one manifest in each:

- HEIC motion photo: C2PA uuid offset 28, size 14764; store offset 73, length 14719.
  Top-level boxes: ftyp, uuid, mdat, meta, free, ftyp, mdat, moov, sefd.
  Manifest `urn:uuid:923069f5-221a-4f07-87c8-06faf9ff0f55`.
  Claim v1 title `20261012_131852.heic`, format `image/heic`.
  Assertions `c2pa.actions.v2`, `c2pa.hash.bmff.v2`; BMFF exclusions `/uuid`, `/ftyp`, `/mfra`, `/sefd`.
- JPEG: APP11 offset 58149, En 529, Z 1; store offset 58161, length 14482.
  Manifest `urn:uuid:dbc0fb71-330b-48a3-a0af-5ce1c74f4672`.
  Claim v1 title `20261012_131827.jpg`, format `image/jpeg`.
  Assertions `c2pa.actions.v2`, `c2pa.hash.data`; data exclusion start 58149, length 14494.
- Both: generator `Galaxy_S26_Ultra c2pa-rs/0.75.0`, action `c2pa.created`,
  softwareAgent `Camera`, digital source `digitalCapture`; COSE tag 18, alg -7 (ES256).
  Presence and decoding are established here; this phase does not validate signatures or bindings.

These user-described camera captures contradict the earlier assumption that Galaxy C2PA occurs
only after AI editing. Do not generalise that assumption across devices or firmware.
