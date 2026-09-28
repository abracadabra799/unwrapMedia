# Motion Photo Integrity Check — Per-Category Tables — Design

**Date:** 2026-09-28
**Status:** Approved (pending user spec review)

## Goal

Redesign the "모션포토 정합성 검사" window around what it's actually for:
confirming that the opened file satisfies every condition of being a valid
motion photo. For a JPEG container that means three independent
correctness categories; for a HEIC container, those three plus a fourth.
Each category becomes its own table instead of scattered flat check lines,
and three of the categories gain real validation logic they don't have
today (not just a display change).

- **JPEG 컨테이너:**
  1. 구글 XMP 모션포토 구성 — 구조가 정상인지 + 선언된 값(length, timestamp,
     mime, padding)이 정확한지
  2. SEF 모션포토 관련 필드 — `MotionPhoto_Data`/`MotionPhoto_AutoPlay`/
     `MotionPhoto_Version` 존재 여부 + 값 정확성
  3. SEF 전체 구조 무결성 — SEFH/SEFT의 offset·length가 실제 데이터 위치와
     일치하는지 (기존 SEF 디렉토리 엔트리 표 재사용)
- **HEIC 컨테이너:** 위 1·2·3 전부 + 4) mpvd 박스 위치/존재/정상 여부

## Background (confirmed against real Samsung device files, not assumed)

Dogfooding the just-shipped SEF directory-entry table against two real
files (`20260715_223835_motion.heic`, `20260718_200439_motion.jpg`)
surfaced:

- **A real, already-fixed bug**: `Image_UTC_Data` was being read as
  seconds-since-epoch instead of milliseconds — every real file produced a
  bogus "year 58506" WARNING. Fixed in both `SefIntegrityAnalyzer.kt` and
  `SefdBoxDecoder.kt` (commit `5acb393`), independently re-verified against
  both real files afterward (correct dates now shown).
- **A real, confirmed-working case this feature exists for**: both real
  files' Google XMP interop metadata had a genuine offset inaccuracy (HEIC:
  8 bytes off; JPEG: 161 bytes off) — correctly caught as WARNING by the
  existing `analyzeGoogleXmpSection`.
- **A real format difference this design must account for**: SEF's
  `MotionPhoto_Data` field is NOT always a 12-byte offset+length pointer.
  On the real HEIC file it is (12 bytes, pointing at the separate `mpvd`
  box). On the real JPEG file it is the *entire embedded video appended
  directly inline* (3,960,928 bytes) — JPEG has no `mpvd` box at all, so
  the video lives directly inside the SEF trailer. The existing
  `checkMotionPhotoData` already handles this correctly (WARNING,
  "cannot verify bounds", when the field isn't exactly 12 bytes) — this
  design's new `MotionPhoto_AutoPlay` check must use the same defensive
  shape rather than assuming AutoPlay is always a 12-byte pointer, since
  no real JPEG sample with an AutoPlay preview was available to confirm
  either way.
- **Confirmed gaps in the existing Google XMP check**: `analyzeGoogleXmpSection`
  today only validates the declared length/offset. It does not check
  `PresentationTimestampUs` (the shutter-click timestamp — should fall
  within the video's real duration), `Item:Padding` (should be a
  non-negative integer), or `Item:Mime` (should match the resolved video's
  real container brand). All three are real fields `MotionPhotoBuilder.kt`
  already writes (`GCamera:MotionPhotoPresentationTimestampUs`/
  `GCamera:MicroVideoPresentationTimestampUs`, `Item:Padding`, `Item:Mime`)
  but nothing currently reads back and validates.
- **Confirmed gap in SEF's MotionPhoto_AutoPlay**: only generic per-entry
  bounds checking applies to it today (was `MotionPhoto_Data`'s dedicated
  payload check). In the standalone "SEF 무결성 검사" window this was
  deliberately left as-is (generic-purpose window, not motion-photo
  specific). In *this* window, whose entire purpose is motion-photo
  validity, the same gap is now in scope to close.
- **A real, now-confirmed bug in this window's own detection/gating
  logic**: `MotionPhoto_Data` is required for a file to actually BE a SEF
  motion photo — `MotionPhoto_AutoPlay`/`MotionPhoto_Version` are optional.
  But both `detectedFormats`'s `SAMSUNG_SEF` inclusion
  (`MotionPhotoIntegrityAnalyzer.kt:176`, currently `if (sefdNode != null)
  add(SAMSUNG_SEF)`) and the menu-enablement gate
  (`Main.kt:548`'s `hasMotionPhoto`, currently `findFirst(r) { it.type ==
  "sefd" }`) only check for a bare `sefd` box — confirmed against real
  data: all 15 real JPEGs found in this investigation have a `sefd` box
  with ordinary EXIF-style fields (HDR info, color profile, capture mode)
  but NO `MotionPhoto_Data` at all — they are not motion photos, just
  ordinary SEF-tagged stills, yet both the menu and the report currently
  treat them as if they were.

## Approach

### 0. Fix SEF motion-photo detection to require `MotionPhoto_Data`, not bare `sefd`

`MotionPhotoIntegrityAnalyzer.kt`: change `detectedFormats`'s condition
from `sefdNode != null` to `sefdNode != null &&
directoryEntryRows.any { it.name == "MotionPhoto_Data" }` (using the
already-computed `sefSection.directoryEntries`, no new lookup needed) —
`SAMSUNG_SEF` is only "detected" when the file is actually a SEF motion
photo, not merely SEF-tagged.

`Main.kt`'s `hasMotionPhoto` gate: change the SEF branch from `findFirst(r)
{ it.type == "sefd" }` to also require a `MotionPhoto_Data` field block
present under that `sefd` — reusing the same field-block-name lookup
`findFirst` already supports elsewhere in this file, so a plain SEF-tagged
photo (no motion video) no longer enables this menu at all.

### A. Extend `analyzeGoogleXmpSection` with 3 new checks

In `MotionPhotoExtractor.kt`, promote `findPresentationTimestampUs` (new,
mirrors `findMicroVideoOffset`'s existing traversal pattern, checking both
`MotionPhotoPresentationTimestampUs` and legacy
`MicroVideoPresentationTimestampUs`) to `internal`, and extend the existing
`internal data class DirectoryVideoInfo(length, mimeType)` with a third
field, `padding: String?` (read via the same `findPropertyValue(li,
"Padding")` call already used for `Mime`).

In `MotionPhotoIntegrityAnalyzer.kt`, `analyzeGoogleXmpSection` gains a
`videoDurationUs: Long?` parameter (computed once by the orchestrator —
see below — and threaded in, so the duration probe isn't a third
extraction+ffprobe pass on top of the existing offset check and decode
check). Three new checks, each independent (a missing field is skipped,
not flagged, matching this analyzer's existing "absent ≠ invalid"
convention):

1. **Padding**: `PASS` if it parses as a non-negative integer; `WARNING`
   if present but non-numeric or negative; skipped (no check emitted) if
   absent.
2. **Mime**: read the real resolved video's `ftyp` `major_brand` at the
   already-computed offset (reusing the same read this function already
   does for the offset check), derive the expected MIME the same way
   `MotionPhotoExtractor.kt`'s own extraction logic already does
   (`"qt"` → `video/quicktime`, else → `video/mp4`), compare against the
   declared `Item:Mime`. `PASS` on match, `WARNING` on mismatch, skipped
   if `Item:Mime` absent.
3. **PresentationTimestampUs**: `PASS` if `0 <= timestamp <= videoDurationUs`,
   `WARNING` if outside that range, `SKIPPED` if `videoDurationUs` is
   `null` (duration probe failed — a decode failure elsewhere already
   surfaces that), and simply omitted if the XMP has no timestamp
   attribute at all.

New `probeVideoDurationUs(file: File, video: EmbeddedVideo): Long?` in
`MotionPhotoIntegrityAnalyzer.kt`: extracts the resolved video (reusing
`extractEmbeddedVideo`, same as the decode check) to a temp file, runs
`ffprobe -v error -show_entries format=duration -of csv=p=0` (a single
float, seconds), multiplies by 1,000,000 for microseconds, cleans up the
temp file. Called once in the orchestrator, result passed to both
`analyzeGoogleXmpSection` and (unchanged) `analyzeDecodability` continues
to do its own separate extraction for the actual decode pass — two
extractions of the same bytes, accepted as a simplicity/performance
tradeoff consistent with this analyzer's existing non-hot-path nature.

### B. New `MotionPhoto_AutoPlay` payload check in `SefIntegrityAnalyzer.kt`

Mirrors `checkMotionPhotoData` exactly, same defensive 12-byte-shape
guard:
```kotlin
fb.name == "MotionPhoto_AutoPlay" ->
    if (fb.dataLength == 12) checkMotionPhotoAutoPlay(reader, fb, fileLength)
    else SefCheckResult(WARNING, "Entry #${fb.entryIndex} MotionPhoto_AutoPlay bounds", "Expected exactly 12 bytes, found ${fb.dataLength} -- cannot verify bounds")
```
`checkMotionPhotoAutoPlay` is a straight copy of `checkMotionPhotoData`'s
body (video_offset + video_length vs. real file length), consistent with
this codebase's established per-function-duplicate convention for
near-identical small checks rather than a forced shared abstraction.

### C. UI: per-category tables in `MotionPhotoIntegrityWindow.kt`

Four small, focused tables/sections instead of the current flat lists
(and instead of my earlier stopgap synthesis of `SefCheckResult`s from the
SEF directory-entry data):

1. **구글 XMP 모션포토 구성** table — one row per checked attribute
   (스키마 버전, 선언된 길이/오프셋, PresentationTimestampUs, Padding,
   Mime), columns: 항목 / 선언된 값 / 상태 / 상세. Built from
   `googleXmpChecks` (already a flat `List<SefCheckResult>` — this table
   is a direct 1:1 render of that list in tabular form, no new data
   shape needed since each check already carries a label+detail+severity).
2. **SEF 모션포토 관련 필드** table — filters `sefSection.directoryEntries`
   to rows whose `name` starts with `"MotionPhoto"`, reusing the exact
   same `SefDirectoryEntryRow` shape and the exact same table-rendering
   composables already built for the standalone SEF window (extracted to
   a shared file — see below), plus the corresponding semantic checks
   (`MotionPhoto_Data`/`MotionPhoto_AutoPlay`/`MotionPhoto_Version`
   entries from `sefSection.semanticChecks`) shown as a small flat list
   beneath it (semantic checks stay flat everywhere in this app, per the
   original SEF table design's established split). **Required vs.
   optional**: `MotionPhoto_Data` is mandatory for this table to have any
   content at all — if this table is even being shown, `MotionPhoto_Data`
   is present by construction (see §0: `SAMSUNG_SEF` isn't "detected"
   without it), so no separate "missing required field" row is needed.
   `MotionPhoto_AutoPlay`/`MotionPhoto_Version` simply don't appear as
   rows when absent — their absence is normal and not flagged.
3. **SEF 전체 구조 무결성** table — the complete, unfiltered SEF
   directory-entry table (all entries, not just `MotionPhoto*`), reusing
   the same shared table composables. Identical in content to what the
   standalone "SEF 무결성 검사" window already shows for this file.
4. **HEIC mpvd 박스** (HEIC only, hidden entirely for JPEG) — kept as the
   existing small flat list (`appleMpvdChecks`, already just 1-2 rows:
   bounds + ftyp child) — not worth a table for 1-2 rows.

**Shared table composables**: extract `SeverityBadge`, `severityColor`,
`severityBadgeText`, `DirectoryEntryTableHeader`, `DirectoryEntryTableRow`,
`DirectoryEntryTable`, `DirectoryEntryCountSummary` from
`SefIntegrityWindow.kt` into a new shared file,
`app/src/main/kotlin/com/multiviewer/ui/SefIntegrityTableComponents.kt`,
and have both windows import from there — replacing this codebase's
established "duplicate small private UI helpers per file" convention for
*this specific case*, since duplicating a real table (not a 5-line color
map) across two windows would mean fixing every future table bug twice.
Both windows switch these composables from `private` to `internal`.

## Non-goals

- No cross-format position reconciliation (comparing whether SEF's,
  Google XMP's, and mpvd's resolved video byte ranges agree with each
  other when multiple formats are simultaneously present) — this was an
  earlier misreading of the original request during brainstorming,
  explicitly corrected by the user: the ask is per-format-category
  correctness, not cross-format comparison.
- No change to `MotionPhoto_Data`'s existing 12-byte-shape handling — it
  already correctly produces WARNING (not CRITICAL, not a crash) for
  JPEG's full-embedded-video shape, confirmed against a real file.
- No new checks for `Camera_Scene_Info`/`Color_Display_P3`/`Photo_HDR_Info`/
  `Camera_Capture_Mode_Info` or other non-motion-photo-specific SEF
  fields — those are already covered generically (encoding/JSON-shape
  validation) and are out of this window's stated scope (motion-photo
  validity specifically, not general SEF field correctness).
- `Item:Padding`'s "correctness" is checked only as "parses to a
  non-negative integer" — no deeper semantic validation against computed
  byte layout, since this codebase's own extraction logic doesn't use
  Padding for any byte-offset computation either (nothing to cross-check
  it against).

## Error handling

- `probeVideoDurationUs` returning `null` (ffprobe failure, missing
  binary, unparseable output) → the `PresentationTimestampUs` check
  reports `SKIPPED`, not a crash and not silently omitted — matches this
  analyzer's existing SKIPPED-cascade convention.
- A JPEG's `MotionPhoto_AutoPlay` in the same
  full-embedded-video shape `MotionPhoto_Data` can take →  `WARNING`,
  "cannot verify bounds", not a false CRITICAL and not a crash trying to
  read a 12-byte pointer from a multi-megabyte payload.
- HEIC's 4th table section (mpvd) is simply not rendered for a JPEG file
  (`detectedFormats` won't include `APPLE_MPVD`) rather than showing an
  empty/placeholder section.

## Testing

- New `analyzeGoogleXmpSection` checks: unit tests using the existing
  `buildSefTrailer`-adjacent XMP-fixture pattern already established in
  `MotionPhotoIntegrityAnalyzerTest.kt` — one fixture per new check
  (valid/invalid Padding, matching/mismatching Mime vs a real `ftyp`
  fixture, timestamp inside/outside a given duration).
- New `checkMotionPhotoAutoPlay`: mirrors the existing
  `MotionPhoto_Data bounds` test pair (within/exceeds file length) in
  `SefIntegrityAnalyzerTest.kt`.
- `probeVideoDurationUs`: tested with a real ffmpeg-generated clip
  (this codebase's established `generateTestClip`-style real-file
  pattern), not mocked.
- No new tests for the UI table extraction/reuse (Compose rendering,
  matching this codebase's established no-dedicated-window-tests
  precedent) — verified via manual inspection against the same two real
  files already used for this design's own background investigation.
