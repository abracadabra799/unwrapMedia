# Motion Photo Inspector Revamp Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a dedicated, clean, developer-focused Motion Photo Inspector window with compact controls, zero boilerplate text, and comprehensive 5-category forensic inspection (Main Image, Format & Primary XMP, Full SEF Integrity, Embedded MP4 & Codec, A/V Sync & Duration).

**Architecture:**
- `MotionPhotoInspectorEngine.kt`: Orchestrates the 5-domain forensic analysis pipeline on a single shared `ByteReader`.
  - Category 1: `StillImageSpecCheck` (JPEG/HEIC marker/box integrity, Exif dimensions vs bitstream dimensions, truncation).
  - Category 2: `FormatXmpCheck` (Google V1/V2, Apple MPVD, Item:Length vs real offset, MIME, PresentationTimestampUs).
  - Category 3: `SefComprehensiveCheck` (SEFT/SEFH, `MotionPhoto_Data`, sub-keys, all other SEF entries in file for bounds, overlap, gaps).
  - Category 4: `EmbeddedVideoCodecCheck` (MP4 box structure, fast-start moov, video/audio codec info, full frame decode check).
  - Category 5: `AvSyncTimingCheck` (Video vs Audio duration delta, shutter timestamp match, PTS monotonicity).
- `MotionPhotoInspectorWindow.kt`: Modern Compose Desktop (Material3) window.
  - Top header: compact title, format tag, overall verdict badge (`PASS`/`WARN`/`FAIL`), compact AssistChip buttons (`[ ⟳ 재검사 ]`, `[ 📋 JSON 복사 ]`, `[ 🎬 동영상 재생 ]`).
  - 5 Accordion Cards: status badge, pass count, auto-expanded on WARN/FAIL, detail table with byte offset links to Hex viewer.
- `Main.kt`: `모션포토` → `모션포토 검사` opens `MotionPhotoInspectorWindow`.

**Spec:** `docs/superpowers/specs/2026-10-16-motion-photo-inspector-revamp-design.md`

---

## Global Constraints

- JDK 21; Compose Desktop (Material3); `androidx.compose.material3.Text` only.
- Bilingual labels (KO/EN) via `label(korean, english)`.
- No blocking file I/O on UI thread (`Dispatchers.IO`).
- Single shared `ByteReader` per analysis pass.
- Conventional commit messages.

---

### Task 1: Comprehensive 5-Category Motion Photo Inspection Models & Engine

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/motionphoto/MotionPhotoInspectorEngine.kt`

- [x] Define data models:
  - `MotionPhotoInspectionReport` (overallStatus, stillImageSection, formatXmpSection, sefSection, videoCodecSection, avSyncSection).
  - `InspectionSection` (title, status, checks: List<InspectionItem>).
  - `InspectionItem` (id, status, label, expected, actual, detail, offset, length).
- [x] Implement Category 1 (Still Image Spec):
  - Check format (JPEG / HEIC).
  - Check primary markers/boxes (SOI, APP1, SOF, SOS, EOI / ftyp, meta, iloc, idat).
  - Compare Exif ImageWidth/Height with SOF/ispe dimensions.
- [x] Implement Category 2 (Format & Primary XMP):
  - Check schema presence (Google V2 / Google V1 / Apple MPVD).
  - Parse declared length/offset and verify exact byte match against `root.size - declaredLength` for `ftyp`.
  - Validate MIME type vs actual container major brand.
  - Validate PresentationTimestampUs and Item:Padding.
- [x] Implement Category 3 (Full SEF Integrity):
  - Verify SEFT/SEFH trailer and header.
  - Verify `MotionPhoto_Data` entry, boundary, and payload MP4 validity.
  - Check all other SEF entries (non-motion SEF): markers, boundaries, overlaps between adjacent entries, orphan gaps.
- [x] Implement Category 4 (Embedded MP4 & Codec):
  - Verify container hierarchy (`ftyp`, `moov`, `mdat`).
  - Verify `moov` fast-start position.
  - Parse video codec name, resolution, FPS, duration.
  - Parse audio codec name, sample rate, channels, duration.
  - Run ffmpeg decoding test and collect error logs.
- [x] Implement Category 5 (A/V Sync & Timing):
  - Calculate Audio vs Video duration delta: report `PASS` if `<= 100ms`, `WARN` if `> 100ms`, `INFO` if no audio.
  - Map PresentationTimestampUs to exact video frame index & PTS.
  - Verify PTS monotonicity and detect drops.
- [x] Run `./gradlew :app:compileKotlin` to verify compilation.

---

### Task 2: Unit Tests for Motion Photo Inspector Engine

**Files:**
- Create: `app/src/test/kotlin/com/multiviewer/parser/motionphoto/MotionPhotoInspectorEngineTest.kt`

- [x] Write test for Category 1: Still image dimension cross-check and truncation detection.
- [x] Write test for Category 2: Primary XMP offset match and MIME validation.
- [x] Write test for Category 3: Full SEF entries validation (boundary, overlaps, gaps).
- [x] Write test for Category 4 & 5: Audio/Video duration delta calculation and timing checks.
- [x] Test on existing fixture files (`s26-motion-photo.heic`, synthetic files).
- [x] Run `./gradlew :app:test --tests 'com.multiviewer.parser.motionphoto.MotionPhotoInspectorEngineTest'`.

---

### Task 3: Build MotionPhotoInspectorWindow UI

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/ui/MotionPhotoInspectorWindow.kt`

- [x] Build window container with title `모션포토 검사 (Motion Photo Inspector) — <file.name>`.
- [x] Build compact toolbar:
  - Format tag & overall status badge (`PASS` / `WARN` / `FAIL`).
  - AssistChip buttons: `[ ⟳ 재검사 / Re-check ]`, `[ 📋 JSON 복사 / Copy JSON ]`, `[ 🎬 동영상 재생 / Play Video ]`.
- [x] Build 5 Accordion Cards:
  - Header: Section title, status badge, pass ratio, chevron.
  - Auto-expand cards that have `WARN` or `FAIL`; collapse passed cards by default.
  - Card body: Compact detail table showing Status, Check, Expected, Actual, and Detail.
  - Clickable byte offsets linking to `tab.parameterSetHighlightRange`.
- [x] Add JSON serialization function for the inspection report (`buildMotionPhotoInspectionJson`).
- [x] Run `./gradlew :app:compileKotlin`.

---

### Task 4: Integration with Menu & Verification

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/Main.kt`

- [x] In `Main.kt`, declare `var motionPhotoInspectorTab by remember { mutableStateOf<TabState?>(null) }`.
- [x] In `모션포토` menu, update `I18n.menuMotionPhotoCheck` `onClick` to set `motionPhotoInspectorTab = currentTab`.
- [x] Render `MotionPhotoInspectorWindow` pinned to tab with disposal check.
- [x] Run `./gradlew :app:compileKotlin` and full test suite `./gradlew :app:test`.
- [x] Commit, fast-forward merge to `main`, and run the app with `./gradlew :app:run`.
