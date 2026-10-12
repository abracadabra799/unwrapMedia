# C2PA Hard-Binding Hash Recomputation & Motion Photo Range Mapping (Phase 2b) — Design Spec

**Goal:** Recompute cryptographic hard-binding hashes (`c2pa.hash.data` for byte-stream formats and `c2pa.hash.bmff` for ISO Base Media File Format containers), verify asset integrity against claims, and provide visual range mapping and edit-impact diagnostics for motion photos, XMP, and Samsung SEF metadata trailers.

**Context:** Roadmap item 2b (2026-10-12 benchmark). Phase 2a parsed and displayed C2PA stores. In Phase 2b, the app validates whether the asset's binary payload matches the signed hash assertion, and answers the key multimedia-engineering question: *"Does appending a motion-photo video, merging XMP, or updating Samsung SEF metadata break the C2PA hard binding?"*

---

## 1. Verified Facts from Real Files

1. **JPEG / WebP (`c2pa.hash.data`)**:
   - Exclusions specify absolute file byte ranges (`start`, `length`).
   - The hash covers the entire file excluding these ranges.
   - Verified on:
     - `C.jpg` (132,518 B): APP11 exclusion `[20, 45904)`. Computed SHA-256 matches stored `81664d10e30740d8942df03fb4ee470f66b03b060c037b677d43c0a48afd72c9` (100% match).
     - `s26-camera.jpg` (Galaxy S26 Ultra JPEG, 9,638,500 B): APP11 exclusion `[58149, 72643)`. Computed SHA-256 matches stored `881a9cd33819b11806c965f3c21e8220b2e56a4873b529b2ec2542e3c39e7bc4` (100% match).
     - `CA.jpg` (166,864 B): two APP11 segments `[20, 64032)` and `[64032, 117293)`.
     - `signed_small.webp` (63,854 B): `C2PA` RIFF chunk `[50408, 63854)`.

2. **BMFF (`c2pa.hash.bmff.v2` / `v3`)**:
   - Exclusions use XPath expressions targeting BMFF boxes and optional byte subsets.
   - `c2pa.hash.bmff.v3`: Roots not excluded contribute `(big_endian_u64(file_offset) || box_bytes)`.
     - `signed.heif`: Root boxes `ftyp` (excl), `uuid` (excl C2PA), `meta` (included @ 13583), `mdat` (included @ 14093).
     - Computed SHA-256 matches stored `OolpAGX8zY0vUFMVXKdDt9qwzhcAJNA0LLVRH3rlRhU=` (100% match, verified against `c2patool 0.27.22`).
   - `s26-motion-photo.heic` (Galaxy S26 Ultra motion photo):
     - Exclusions explicitly list `/sefd` (`{'xpath': '/sefd'}`), alongside `/uuid`, `/ftyp`, `/mfra`.
     - Samsung's camera framework deliberately excludes `/sefd` so SEF metadata updates do not alter the BMFF hash.
     - However, the appended motion-photo video stream (`ftyp`, `mdat`, `moov`) sits outside the still image boxes and is not covered by exclusions, which `c2patool` flags as `assertion.bmffHash.mismatch`. This exact discrepancy is a primary forensic finding that unwrapMedia surfaces.

---

## 2. Scope & Capabilities

### In Scope (2b):
1. **Hash Engine**:
   - `c2pa.hash.data` recomputation for JPEG, WebP, and other byte-stream formats using streaming `MessageDigest` (SHA-256, SHA-384, SHA-512).
   - `c2pa.hash.bmff.v2` and `v3` recomputation for BMFF files (HEIC, AVIF, MP4, MOV).
   - Comparison with stored assertion `hash` bytes -> `MATCH (PASS)` or `MISMATCH (FAIL)`.
   - Verification of the claim's URI assertion hash link (`claim.assertions[i].hash == hash(assertion_jumbf)`).
2. **Coverage Mapping**:
   - Classify all parsed top-level segments/boxes of the active file into **Protected (Hashed)** vs **Excluded**.
   - Overlay logical file structures: Exif (APP1), XMP (APP1), C2PA (APP11), Image Scans, Gain Maps, MP4 Video Tracks, SEF Trailers.
3. **Edit-Impact Diagnostics**:
   - Analyze whether specific modifications would preserve or break the binding:
     - Appending a motion photo video track.
     - Modifying / appending Samsung SEF trailers (`/sefd` excluded vs monolithic hash).
     - Merging XMP / Exif metadata.
4. **UI Presentation**:
   - New tab in `C2paWindow`: **"하드 바인딩" / "Hard Binding"**.
   - Visual status badge (PASS / MISMATCH), algorithm, hash comparison (hex view).
   - Exclusion list table.
   - Interactive Structure & Binding Coverage Table with impact assessment.

### Out of Scope (deferred to 2c or later):
- COSE_Sign1 signature verification, X.509 certificate trust-path validation, timestamp RFC 3161 verification (Phase 2c).
- Writing / modifying C2PA manifest stores or re-signing assets.

---

## 3. Architecture & Interfaces

### New Pure-Kotlin Package: `com.multiviewer.parser.c2pa`

```
com.multiviewer.parser.c2pa/
├── C2paHardBinding.kt       // Models, hash recomputation engine, exclusion evaluators
└── C2paCoverageMap.kt        // Container structure-to-binding coverage and edit-impact analysis
```

### Core Data Models

```kotlin
enum class BindingStatus {
    MATCH,       // Computed hash matches stored assertion hash
    MISMATCH,    // Hash differs (file was edited, corrupted, or truncated)
    UNSUPPORTED  // Unknown algorithm or unhandled exclusion type
}

data class HashComparison(
    val algorithm: String,
    val status: BindingStatus,
    val storedHashHex: String,
    val computedHashHex: String,
    val excludedByteCount: Long,
    val hashedByteCount: Long,
    val error: String? = null
)

enum class CoverageStatus {
    HASHED,      // Included in cryptographic hash (protected against tampering)
    EXCLUDED     // Excluded from hash (changes here do NOT alter C2PA hash)
}

data class StructureCoverageItem(
    val name: String,
    val type: String,
    val offset: Long,
    val size: Long,
    val status: CoverageStatus,
    val impactNote: String
)

data class EditImpactAssessment(
    val motionPhotoVideoSafe: Boolean,
    val motionPhotoVideoReason: String,
    val sefTrailerSafe: Boolean,
    val sefTrailerReason: String,
    val xmpMetadataSafe: Boolean,
    val xmpMetadataReason: String
)

data class C2paHardBindingReport(
    val comparison: HashComparison,
    val coverageItems: List<StructureCoverageItem>,
    val editImpact: EditImpactAssessment
)
```

### Engine Functions

```kotlin
/** Recomputes hard-binding hash for the given file and manifest store */
fun verifyC2paHardBinding(file: File, root: BoxNode, store: C2paManifestStore): C2paHardBindingReport

/** Low-level byte-range hash for c2pa.hash.data */
fun computeDataHash(reader: ByteReader, exclusions: List<C2paSpan>, alg: String): ByteArray

/** Low-level BMFF hash for c2pa.hash.bmff */
fun computeBmffHash(reader: ByteReader, root: BoxNode, assertion: C2paAssertion, alg: String): ByteArray
```

---

## 4. UI Design: "Hard Binding" Tab in `C2paWindow`

1. **Status Banner**:
   - Green `PASS` badge if computed hash matches stored hash.
   - Red `MISMATCH` badge if hash differs (e.g. `s26-motion-photo.heic`).
   - Alg, Stored Hash, Computed Hash, Hashed Byte Count, Excluded Byte Count.
2. **Edit-Impact Summary Box**:
   - 3-card indicator:
     - **Motion Photo Video**: 🟢 Safe / 🔴 Breaks Binding (explanation)
     - **Samsung SEF Trailer**: 🟢 Safe / 🔴 Breaks Binding (explanation)
     - **XMP / Exif Edit**: 🟢 Safe / 🔴 Breaks Binding (explanation)
3. **Structure & Binding Coverage Table**:
   - Columns: Box/Segment Name, Type, Offset, Size, C2PA Status, Edit Impact.
   - Colors: Hashed in `AppColors.NeonBlue` / `TextPrimary`, Excluded in `AppColors.NeonYellow` / `TextSecondary`.

---

## 5. Test Plan

1. **Unit Tests (`C2paHardBindingTest.kt`)**:
   - `C.jpg`: verify `c2pa.hash.data` produces `81664d10...` -> `MATCH`.
   - `s26-camera.jpg`: verify `c2pa.hash.data` produces `881a9cd3...` -> `MATCH`.
   - `CA.jpg`: multi-segment exclusion recomputation -> `MATCH`.
   - `signed_small.webp`: WebP RIFF C2PA exclusion -> `MATCH`.
   - `signed.heif`: `c2pa.hash.bmff.v3` root offset prefixing -> `MATCH`.
   - `s26-motion-photo.heic`: detect `/sefd` exclusion, assess motion-photo video mismatch -> `MISMATCH` with accurate diagnostics.
   - `no_manifest.jpg`: graceful non-C2PA handling.
2. **Coverage Mapping Tests (`C2paCoverageMapTest.kt`)**:
   - Verify all segments/boxes match physical file offsets and byte sums.
   - Verify SEF trailer coverage on Galaxy JPEG vs HEIC.
3. **UI Integration (`C2paPresentationTest.kt`)**:
   - Verify tab indexing, bilingual labels, and state binding.
