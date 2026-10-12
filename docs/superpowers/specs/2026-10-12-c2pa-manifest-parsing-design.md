# C2PA Manifest Parsing (Phase 2a) — Design Spec

**Goal:** Locate, parse and present C2PA Content Credentials (manifest stores) embedded in JPEG, HEIF/MP4/MOV and WebP files: JUMBF boxes as children in the structure tree (hex-linked), and an Analyze-menu "C2PA 콘텐츠 자격 증명" window showing the manifest chain, claims, actions, ingredients, assertions and thumbnails.

**Context:** Roadmap item 2 (2026-10-12 benchmark) — C2PA/JUMBF parsing plus motion-photo interaction. Galaxy S25 and later add Content Credentials to photos edited with Galaxy AI; the user (Galaxy multimedia framework / codec / motion-photo owner) needs to see them and, later, to know whether motion-photo trailers or XMP merges break the C2PA hard binding. The work is split into three phases, each with its own spec → plan → implementation:

- **2a (this spec):** structure parsing and display.
- **2b:** recompute hard bindings (`c2pa.hash.data`, `c2pa.hash.bmff`) and map motion-photo trailer / XMP / SEF ranges against the hashed ranges ("does this edit break the binding?").
- **2c:** COSE_Sign1 signature, X.509 chain, timestamp and trust-list verification using the JDK's built-in crypto APIs only (decided: no external crypto library; c2patool only for cross-checking during development).

Today the app has no C2PA handling: JPEG APP11, the top-level BMFF `uuid` box and the WebP `C2PA` chunk render as unparsed bytes. No CBOR or JSON library is used in this codebase.

## Verified facts (planning-time inspection of real files, 2026-10-12)

Public samples from `contentauth/c2pa-rs` `sdk/tests/fixtures` (MIT/Apache-2.0) plus files signed locally with c2patool 0.27.22's built-in test certificate:

- **JPEG:** manifest store in APP11 segments with `CI = "JP"`, `En` (box instance, 529 in all samples), `Z` (sequence, 1-based). A store larger than one segment is split (`CA.jpg`: 64000 + 53249 payload bytes). Every segment repeats the 8-byte JUMBF superbox header (LBox, TBox); reassembly = first segment's bytes from LBox onward + each following segment's bytes after CI/En/Z/LBox/TBox (16 bytes). The reassembled length equals LBox exactly in every sample.
- **BMFF (HEIF, MP4, MOV):** top-level `uuid` box with UUID `d8fec3d6-1b0e-483c-9297-5828877ec481`, then version/flags (4 bytes), a NUL-terminated purpose string (`manifest`), an 8-byte merkle offset, then the JUMBF manifest store. In HEIF it sits right after `ftyp`; MP4 samples carry a second C2PA `uuid` (merkle data) after `moov`.
- **WebP:** RIFF chunk `C2PA` whose payload is the JUMBF manifest store.
- **JUMBF tree:** `jumb`(`c2pa` store) → `jumb`(`c2ma` manifest, one per manifest; last one is active) → `c2as` assertion store, `c2cl` claim (one `cbor` box), `c2cs` signature (one `cbor` box holding COSE_Sign1, tag 18). Assertion superboxes carry `cbor`, `json`, or thumbnail `bfdb` (MIME type) + `bidb` (bytes) content boxes.
- **`jumd` toggles:** both `0x03` (requestable + label) and `0x13` (+ private box after the label, used for salts) occur.
- **Label versions coexist:** older files use `c2pa.claim`, `c2pa.actions`, `c2pa.hash.bmff`; c2patool 0.27 writes `c2pa.claim.v2`, `c2pa.actions.v2`, `c2pa.hash.bmff.v3`, `c2pa.thumbnail.claim` (no extension). Assertion box types seen: `cbor`, `json`, and the thumbnail UUID type `40cb0c32-…`.
- A real Galaxy photo (`20261012_223627.jpg`, unedited Ultra HDR capture) contains **no** C2PA — Samsung only adds it to AI-edited images. No real Galaxy C2PA sample is available yet.

## Scope

- Containers: JPEG (APP11), HEIF/HEIC/AVIF/MP4/MOV (top-level C2PA `uuid`, purpose `manifest`), WebP (`C2PA` chunk).
- Embedded manifest stores only.
- Parse every JUMBF box; decode claim and assertions semantically where listed below, otherwise show raw CBOR/JSON.
- Keep the raw COSE_Sign1 bytes and the raw claim bytes for phase 2c.
- Show the signing certificate's subject/issuer by parsing (not validating) the first certificate of the COSE `x5chain` header with `java.security.cert.CertificateFactory`, labelled "not verified".

## Out of scope (2a)

Hash recomputation and motion-photo range mapping (2b); signature, chain, timestamp and trust verification (2c); PNG `caBX`; remote (URL-referenced) manifests; writing or editing manifests; Content Check integration.

## Architecture

### Parser (`app/src/main/kotlin/com/multiviewer/parser/c2pa/`, pure, no UI dependencies)

| File | Responsibility |
|---|---|
| `Cbor.kt` | CBOR (RFC 8949) decoder → `CborValue` (unsigned/negative int as `Long` with big-int fallback to `BigInteger`, bytes, text, array, map with insertion order, tag, bool, null, undefined, float16/32/64). Definite and indefinite lengths. Limits: nesting depth 64, total item count 1,000,000; violations and truncation throw `CborException` with the byte offset. Also `toDiagnostic(value, maxBytesShown)` producing RFC 8949 diagnostic notation for display. |
| `Jumbf.kt` | JUMBF box parser over a byte array → `JumbfBox` (type, logical offset/size, header size, children for `jumb`; for `jumd`: content-type UUID, toggles, label, optional ID, optional signature, optional private box; raw content for leaf boxes). Handles LBox 0 (to end) and 1 (64-bit). Malformed boxes become a box with a warning and stop that level's walk. |
| `C2paLocator.kt` | Finds the embedded store per container and returns `C2paStoreLocation(container, bytes, offsetMap, warnings)`. JPEG: groups APP11 `JP` segments by `En`, orders by `Z`, reassembles as above, warns on missing/duplicate `Z` or length ≠ LBox; picks the group whose first `jumd` label is `c2pa`. BMFF: first top-level C2PA `uuid` with purpose `manifest`. WebP: `C2PA` chunk. `offsetMap` maps a logical offset in the reassembled bytes to its file offset (identity plus base for BMFF/WebP; piecewise per segment for JPEG). Reads at most 64 MB. |
| `C2paManifest.kt` | Builds `C2paManifestStore(manifests, activeManifestLabel, warnings)` from the JUMBF tree. Each `C2paManifest`: label, `claim` (decoded: version 1 or 2, title, format, instanceID, claim_generator / claim_generator_info, signature reference, assertion references (`assertions` or `created_assertions` + `gathered_assertions`) with their hash algorithm), `assertions` (label, JUMBF content type, decoded kind), raw claim bytes, raw COSE_Sign1 bytes, and the parsed COSE protected header's `alg` and first `x5chain` certificate subject/issuer. Assertion kinds: actions (v1/v2: action, softwareAgent, when, digitalSourceType, parameters), ingredient (v1–v3: title, format, relationship, instanceID, active manifest reference, thumbnail reference), thumbnail (MIME + bytes), hash.data / hash.bmff (shown raw; recomputation is 2b), everything else raw CBOR / JSON text. |

### Structure tree

- JPEG: APP11 segments decode CI/En/Z/LBox/TBox as fields. For the store's group, the first APP11 node gets the JUMBF tree as children with file offsets mapped through `offsetMap`. A box that spans a segment boundary gets a warning that its hex highlight includes continuation-segment header bytes.
- BMFF: register a decoder for top-level `uuid` that recognises the C2PA UUID (fields: uuid, version/flags, purpose, merkle offset) and attaches the JUMBF tree; other `uuid` boxes stay generic.
- WebP: the `C2PA` chunk gets the JUMBF tree as children.
- Node fields: label, content-type (four-char code or UUID), toggles; leaf `cbor` nodes show a one-line summary (e.g. top-level map keys).

### UI: Analyze → "C2PA 콘텐츠 자격 증명" / "C2PA Content Credentials"

- Enabled only when a manifest store was found in the current tab.
- Left: manifest list in store order, active manifest marked; selecting one fills the right side.
- Right tabs: **Overview** (title, format, instanceID, claim generator, claim version, signature `alg`, certificate subject/issuer "not verified"), **Actions**, **Ingredients** (with thumbnails and a link to the referenced manifest when present in the store), **Assertions** (label list; selecting shows the CBOR diagnostic notation / JSON text; malformed ones show the error and hex), **Thumbnail** (claim thumbnail, decoded on demand).
- Strings bilingual (KO/EN); all text `androidx.compose.material3.Text`.
- Phases 2b and 2c add tabs to this window.

## Error handling

- Malformed JUMBF: warning on the box, siblings at the same level after it are skipped, other manifests still shown.
- CBOR errors: the affected claim/assertion shows "decode failed: <reason> @offset" with its hex; nothing else is affected.
- JPEG APP11 anomalies (missing/duplicate Z, length mismatch, multiple En groups): store-level warnings shown in the window header and on the APP11 node.
- Size caps: 64 MB per store, CBOR depth 64 / 1M items. Thumbnails decoded only when viewed.
- Parsing runs as part of normal structure parsing but only when an APP11 `JP` segment, a C2PA `uuid` or a `C2PA` chunk exists, so files without C2PA pay nothing.

## Testing

- Fixtures (committed under `app/src/test/resources/c2pa/`, each ≤ ~850 KB): `C.jpg` (single APP11), `CA.jpg` (two APP11 segments, ingredient chain), `video1.mp4` (BMFF, two manifests), a HEIF and a WebP signed locally with c2patool's test certificate (2.x labels; WebP re-signed without a thumbnail to keep it small), plus `no_manifest.jpg`. A `docs/validation/c2pa-fixtures/README.md` records sources, licence and the c2patool commands.
- Expected values (labels, claim generators, actions, ingredient titles, manifest counts) are taken from `c2patool <file> --detailed` during planning and hard-coded in tests; tests do not run c2patool.
- Unit tests: CBOR against RFC 8949 Appendix A vectors (incl. indefinite lengths, tags, float16, big ints), depth/item limits and truncation; JUMBF toggles combinations, private box, LBox 0/1, truncated boxes; JPEG APP11 reassembly incl. missing Z and length mismatch; claim v1 vs v2 decoding; locator per container; offset mapping across APP11 segments.
- Manual: open each fixture, check tree nodes, hex highlighting and the window; optional real Galaxy AI-edited file when available.
