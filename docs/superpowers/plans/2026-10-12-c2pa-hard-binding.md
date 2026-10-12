# C2PA Hard-Binding Hash Recomputation & Motion Photo Range Mapping (Phase 2b) — Implementation Plan

**Goal:** Implement C2PA hard-binding hash recomputation (`c2pa.hash.data` for JPEG/WebP and `c2pa.hash.bmff` for BMFF/HEIC/MP4), structure-to-binding coverage mapping, and edit-impact diagnostics (motion photo video, XMP, Samsung SEF), adding a "Hard Binding" tab to the C2PA credentials window.

**Architecture:** New pure-Kotlin components in `com.multiviewer.parser.c2pa`:
- `C2paHardBinding.kt`: Streaming data hash recomputation, BMFF box-filtered hash recomputation, and hash comparison logic.
- `C2paCoverageMap.kt`: Container segment/box mapping into Hashed vs Excluded regions, and edit-impact safety evaluator.
- `C2paWindow.kt`: New "하드 바인딩" / "Hard Binding" tab rendering the report.

**Tech Stack:** Kotlin 2.0.21, Compose Desktop (Material3), JUnit 5 / kotlin.test, JDK `MessageDigest`. No new dependencies.

**Spec:** `docs/superpowers/specs/2026-10-12-c2pa-hard-binding-design.md`

---

## File Structure

| File | Responsibility |
|---|---|
| `app/src/main/kotlin/com/multiviewer/parser/c2pa/C2paHardBinding.kt` (new) | Core hash engine (`c2pa.hash.data` and `c2pa.hash.bmff`), model types |
| `app/src/main/kotlin/com/multiviewer/parser/c2pa/C2paCoverageMap.kt` (new) | Structure-to-binding coverage mapper & edit impact diagnostics |
| `app/src/main/kotlin/com/multiviewer/ui/C2paWindow.kt` (modify) | Add "하드 바인딩" tab with status badge, cards, and coverage table |
| `app/src/test/kotlin/com/multiviewer/parser/c2pa/C2paHardBindingTest.kt` (new) | Tests for data/BMFF hash recomputation on real fixtures |
| `app/src/test/kotlin/com/multiviewer/parser/c2pa/C2paCoverageMapTest.kt` (new) | Tests for structure coverage and edit safety assessment |

---

### Task 1: `c2pa.hash.data` Recomputation Engine

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/c2pa/C2paHardBinding.kt`
- Create: `app/src/test/kotlin/com/multiviewer/parser/c2pa/C2paHardBindingTest.kt`

**Details:**
- Implement `computeDataHash(reader: ByteReader, exclusions: List<C2paSpan>, algorithm: String): ByteArray`.
- Stream file blocks (64 KB buffer) using standard JDK `MessageDigest.getInstance(alg)`.
- Skip any byte in `exclusions`.
- Support SHA-256, SHA-384, SHA-512.
- Write tests asserting exact hash match for `C.jpg`, `CA.jpg`, `s26-camera.jpg`, and `signed_small.webp`.

---

### Task 2: `c2pa.hash.bmff` (v2 & v3) Recomputation Engine

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/c2pa/C2paHardBinding.kt`
- Modify: `app/src/test/kotlin/com/multiviewer/parser/c2pa/C2paHardBindingTest.kt`

**Details:**
- Parse BMFF exclusions:
  - XPath matching (`/uuid`, `/ftyp`, `/meta/iloc`, `/free`, `/skip`, `/mfra`, `/sefd`).
  - Match C2PA `uuid` box by payload UUID (`D8 FEC3 D6 1B 0E 48 3C 92 97 58 28 87 7E C4 81`).
  - Subset exclusions (masked offsets/lengths, e.g. for chunk offset boxes).
- Implement BMFF v3 algorithm:
  - For included root boxes: hash `bigEndianU64(box.offset) || boxBytes(with subset masks)`.
- Implement BMFF v2 algorithm:
  - Hash included box bytes sequentially.
- Verify against `signed.heif` (`MATCH`) and `s26-motion-photo.heic` (`MISMATCH` with accurate diagnostics).

---

### Task 3: Structure-to-Binding Coverage Mapper & Edit Impact Evaluator

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/c2pa/C2paCoverageMap.kt`
- Create: `app/src/test/kotlin/com/multiviewer/parser/c2pa/C2paCoverageMapTest.kt`

**Details:**
- Map top-level `BoxNode` children (JPEG segments, BMFF boxes, WebP chunks) against exclusion spans.
- Mark each as `CoverageStatus.HASHED` or `CoverageStatus.EXCLUDED`.
- Evaluate edit impact:
  - `motionPhotoVideoSafe`: Check if appended video boxes are excluded.
  - `sefTrailerSafe`: Check if `/sefd` or SEF trailer range is excluded.
  - `xmpMetadataSafe`: Check if XMP APP1 / box is excluded (typically false).
- Provide unit tests on `s26-camera.jpg`, `s26-motion-photo.heic`, and `C.jpg`.

---

### Task 4: Unified Hard-Binding Report

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/c2pa/C2paHardBinding.kt`
- Modify: `app/src/test/kotlin/com/multiviewer/parser/c2pa/C2paHardBindingTest.kt`

**Details:**
- Implement `fun verifyC2paHardBinding(file: File, root: BoxNode, store: C2paManifestStore): C2paHardBindingReport`.
- Extract active manifest's `hash.data` or `hash.bmff` assertion.
- Run hash engine and coverage mapper.
- Format stored vs computed hex strings and byte statistics.

---

### Task 5: UI Integration ("Hard Binding" Tab in `C2paWindow`)

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/C2paWindow.kt`
- Modify: `app/src/test/kotlin/com/multiviewer/ui/C2paPresentationTest.kt`

**Details:**
- Add tab 4: `label("하드 바인딩", "Hard Binding")` in `C2paWindow`.
- Render:
  - Status banner: `PASS` (NeonBlue/Green) / `MISMATCH` (NeonRed) badge.
  - Stored vs Computed hash hex with monospace font and copyable text.
  - 3-card Edit Impact Assessment (Motion Photo Video, SEF Trailer, XMP Metadata).
  - Coverage Table listing all boxes/segments with their C2PA protection status.
- Ensure only `androidx.compose.material3.*` is imported.

---

### Task 6: Final Verification & Integration

**Details:**
- Run `./gradlew :app:test` (all tests passing).
- Confirm zero usages of `ProcessBuilder` or `ffmpeg`.
- Fast-forward merge to `main`.
- Update `HANDOFF.md`.
