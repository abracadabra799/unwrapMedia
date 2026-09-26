# Motion Photo Integrity Check — Design

**Date:** 2026-09-26
**Status:** Approved (pending user spec review)

## Goal

A new menu, "모션포토 정합성 검사" ("Motion Photo Integrity Check"), that
validates a motion photo's embedded-video reference across all three formats
this app already recognizes — Samsung SEF, Google's XMP-based Motion
Photo/MicroVideo, and Apple/QuickTime-style `mpvd` — in one unified report,
rather than requiring the user to already know which format a given file
uses and open a format-specific tool.

## Background: what's already there (verified by reading the code, not assumed)

- **Three formats, one shared detector.** `MotionPhotoExtractor.kt`'s
  `findEmbeddedVideo(root, reader)` already locates the embedded video for
  all three: an `mpvd`/`EmbeddedVideoData` box (Apple/QuickTime-style), a
  Samsung SEF `MotionPhoto_Data` field block (via `sefd`), or a Google XMP
  reference (`Container:Directory`'s `Item:Semantic="MotionPhoto"`+`Length`,
  or the legacy `GCamera:MicroVideoOffset` attribute) — falling back through
  them in that order. `extractEmbeddedVideo(source, video, destination)`
  already copies the resolved byte range out to a file. Both are reused
  as-is by this feature, not reimplemented.
- **Samsung SEF already has a dedicated, shipped integrity checker.**
  `SefIntegrityAnalyzer.analyze(reader, offset, headerSize, size,
  fileLength): SefIntegrityReport` (structural + semantic checks, `PASS`/
  `INFO`/`WARNING`/`CRITICAL`/`SKIPPED` severities) already validates SEFT/
  SEFH structure, per-entry bounds (does each field block's declared
  position actually hold real data), and `MotionPhoto_Data`'s
  `video_offset+video_length` against the real file length. This feature
  calls it unchanged as one report section — no duplicate SEF logic, and
  the existing standalone "SEF 무결성 검사" menu is untouched.
- **Google's offset math already self-heals silently — that's the gap.**
  `findGoogleMotionPhotoVideo` computes an XMP-declared start offset, then
  calls `correctMp4StartOffset(reader, approxStart)`, which scans ±1024
  bytes for a real `ftyp` box and quietly returns the corrected position if
  the declared one was wrong (a real, observed case: this project's own
  code comment notes "Oppo, observed off by ~190 bytes"). That silent
  correction is exactly right for *extracting* the video, but it means
  today there is no way to see, after the fact, whether a given file's XMP
  metadata was actually accurate — the correction and the inaccuracy it's
  covering for are indistinguishable from outside. This feature adds a
  diagnostic path that reports the correction as a finding instead of
  quietly applying it.
- **No existing check for the Apple/QuickTime `mpvd` box's own position or
  child structure**, or for the "MotionPhoto vs MicroVideo" XMP schema
  version actually present in a given file — `findGoogleMotionPhotoVideo`
  handles both schemas for extraction purposes but doesn't surface which
  one it found, or whether it found a length/offset at all.
- **No format-independent "does the referenced video actually decode"
  check anywhere.** Every existing check (SEF's included) validates
  declared *positions*, not whether the bytes at that position form a
  playable video — a truncated or corrupted embedded video with
  perfectly-correct offsets would currently pass every existing check.
- **Confirmed via grep**: none of `parseXmpDocument`, `findMotionPhotoInDirectory`,
  `findMicroVideoOffset`, `findPropertyValue`, `correctMp4StartOffset`,
  `DirectoryVideoInfo`, `MP4_START_SEARCH_WINDOW` (all currently `private` in
  `MotionPhotoExtractor.kt`) collide with any identically-named symbol
  elsewhere in the codebase — promoting them to `internal` for reuse by this
  feature's new analyzer is safe (this exact class of collision bit the SEF
  Integrity Check project's Task 1 once already, hence checking explicitly
  rather than assuming).
- **Package precedent confirmed both ways**: `ui`-package files
  (`QualityMetrics.kt`, `AvSyncAnalyzer.kt`) already invoke `ffprobe` via
  `FfmpegLocator.ffprobePath()`, while several `parser`-package files
  already import from `ui` (`Av1FrameHeaderAnalyzer.kt`, `GainmapParser.kt`,
  `ImageAnalyzer.kt`) — so placing the new analyzer in `ui` (needed for the
  ffprobe decode check) while it imports `internal` helpers from the
  `parser`-package `MotionPhotoExtractor.kt` matches how this codebase
  already crosses that boundary.

## Approach

### Architecture

Two new files, following the `SefIntegrityAnalyzer.kt`/`SefIntegrityWindow.kt`
split already established:

- **`app/src/main/kotlin/com/multiviewer/ui/MotionPhotoIntegrityAnalyzer.kt`**
  (new, `ui` package — needs `FfmpegLocator`) — `object
  MotionPhotoIntegrityAnalyzer { fun analyze(file: File, root: BoxNode):
  MotionPhotoIntegrityReport }`. Blocking (file I/O + one `ffprobe`
  subprocess call) — callers invoke it via `withContext(Dispatchers.IO)`,
  matching `QualityMetrics.kt`'s established convention.
- **`app/src/main/kotlin/com/multiviewer/ui/MotionPhotoIntegrityWindow.kt`**
  (new) — the report window, mirroring `SefIntegrityWindow.kt`'s
  `LaunchedEffect(file) { isLoading = true; report =
  withContext(Dispatchers.IO) { ... }; isLoading = false }` pattern, with
  one collapsible section per format actually detected in the file.

**Reused types, not duplicated:** `SefCheckResult(severity, label, detail)`
and `SefIntegritySeverity` (`PASS`/`INFO`/`WARNING`/`CRITICAL`/`SKIPPED`,
already a generic check-result shape despite the `Sef`-prefixed name) are
imported from the `parser` package and reused directly for the Google and
Apple sections too, rather than defining a second, near-identical result
type — one severity vocabulary across the whole report.

```kotlin
enum class MotionPhotoFormat { SAMSUNG_SEF, GOOGLE_XMP, APPLE_MPVD }

data class MotionPhotoIntegrityReport(
    val detectedFormats: List<MotionPhotoFormat>,
    val sefSection: SefIntegrityReport?,      // null if no sefd box present
    val googleXmpChecks: List<SefCheckResult>, // empty if not detected
    val appleMpvdChecks: List<SefCheckResult>, // empty if not detected
    val decodeChecks: List<SefCheckResult>,    // format-independent; empty if no video resolved at all
    val overallSeverity: SefIntegritySeverity,
)
```

**One shared `ByteReader.open(file)` for the whole analysis** — this
project has already had to fix redundant-`ByteReader.open` bugs twice
(the drag-and-drop Compare window slow-open fix, and this very session's
Task 2 final-review finding on the Image Quality Comparison branch), so
`analyze()` opens the file exactly once and passes that one `reader` to
every section (SEF delegation, Google XMP checks, `findEmbeddedVideo` for
the decode check) — never one `ByteReader.open` per section.

### Per-format checks

**Samsung SEF** — delegate unchanged:
```kotlin
val sefdNode = findFirst(root) { it.type == "sefd" }
val sefSection = sefdNode?.let { sefd ->
    SefIntegrityAnalyzer.analyze(reader, sefd.offset, sefd.headerSize, sefd.size, file.length())
}
```

**Google XMP** — new checks, reusing the (now-`internal`) XMP-parsing
helpers from `MotionPhotoExtractor.kt`:
1. Locate the XMP text (same `findFirst(root) { it.fields.any { f -> f.name
   == "xmp" } }` pattern `findGoogleMotionPhotoVideo` already uses). If none
   found, or found but containing neither `"MotionPhoto"` nor
   `"MicroVideo"` (case-insensitive), this format isn't present — empty
   list, not a finding.
2. Parse via `parseXmpDocument`. A parse failure is reported as one
   `WARNING` (not silently skipped, and not a crash — `parseXmpDocument`'s
   existing hardening against XXE/entity attacks is unchanged).
3. Try `findMotionPhotoInDirectory` (current, v2.0, `Item:Semantic=
   "MotionPhoto"` + `Item:Length`) first; if absent, try
   `findMicroVideoOffset` (legacy, v1.0, `GCamera:MicroVideoOffset`
   attribute) — matching `findGoogleMotionPhotoVideo`'s existing fallback
   order. Report one `INFO` line naming which schema was actually found (or
   a `WARNING` if motion-photo markers exist in the XMP text but neither
   schema yields a length/offset value).
4. Validate the declared length is in `1..root.size`; `CRITICAL` if not.
5. Compute `approxStart = root.size - declaredLength`. Check whether a real
   `ftyp` box exists exactly there (`reader.readFourCC(approxStart + 4) ==
   "ftyp"`, bounds-checked). If yes: `PASS`, offset matches. If no: call
   `correctMp4StartOffset(reader, approxStart)` — if it found a different
   position, `WARNING` naming the byte delta (the XMP declaration doesn't
   match the real video location, but it's still recoverable); if it found
   nothing at all in the ±1024-byte window, `CRITICAL` (video effectively
   missing or the reference is badly wrong).

**Apple/QuickTime `mpvd`**:
1. `findFirst(root) { it.type == "mpvd" || it.type == "EmbeddedVideoData"
   }`. Absent → empty list, not a finding.
2. Bounds-check the node's `offset`/`size` against the real file length —
   `CRITICAL` if it overruns the file.
3. Check for a `ftyp` child (`mpvdNode.children.find { it.type == "ftyp"
   }`); `WARNING` if missing, else `PASS` naming the recovered
   `major_brand` field.

**Format-independent decode check** (runs once, using whichever format's
video actually resolved via the existing shared `findEmbeddedVideo`):
1. If `findEmbeddedVideo(root, reader)` returned `null`, empty list (no
   video to check — the per-format sections above already explain why, if
   relevant).
2. Otherwise `extractEmbeddedVideo` the resolved range to a temp file
   (deleted in a `finally`, mirroring this app's other extract-to-temp
   call sites in `Main.kt`/`AppState.kt`), then run `ffprobe -v error
   -show_entries format=duration -of default=noprint_wrappers=1` on it.
   Exit code `0` → `PASS` (video is genuinely decodable); non-zero or a
   process-launch failure → `CRITICAL` with the captured stderr/exception
   message.

### Report window

One `LazyColumn` with a section per format actually present in
`detectedFormats` (a file with none shows a single explanatory line, though
in practice the menu item is disabled in that case — see below), plus one
final "임베디드 비디오 디코딩 확인" section for the format-independent
check. Each section reuses `SefIntegrityWindow.kt`'s existing
`SeverityBadge`/`CheckRow`/`CheckSection` composables' *pattern*
(re-declared as file-private copies in the new window file, matching this
codebase's established per-file-duplicate convention for small private UI
helpers — same reasoning `SefIntegrityAnalyzer.kt` already documents for
its own duplicated `readUInt16LE`/`readUInt32LE`).

### Menu item

New "모션포토 정합성 검사" item, enabled whenever the current tab's tree
contains ANY of the three formats' markers (broader than the existing
`hasSefData`, which only gates the standalone SEF menu):
```kotlin
val hasMotionPhoto = currentTab?.root?.let { root ->
    findFirst(root) { it.type == "sefd" } != null ||
    findFirst(root) { it.type == "mpvd" || it.type == "EmbeddedVideoData" } != null ||
    findFirst(root) { it.fields.any { f -> f.name == "xmp" &&
        (f.value.contains("MotionPhoto", ignoreCase = true) || f.value.contains("MicroVideo", ignoreCase = true)) } } != null
} ?: false
```
The existing "SEF 무결성 검사" menu item and its `hasSefData` gate are
unchanged — this is a new, additional, broader entry point.

## Non-goals (explicitly out of scope, with why)

- **Still-photo-vs-embedded-video capture-timestamp cross-check**: raised
  during brainstorming but not selected — deferred to a possible future
  phase, not part of this design.
- **Any change to `SefIntegrityAnalyzer.kt` itself** (e.g. giving
  `MotionPhoto_AutoPlay` its own named bounds check to mirror
  `MotionPhoto_Data`'s): explicitly declined — the existing generic
  per-entry bounds check already validates `MotionPhoto_AutoPlay`
  functionally, just without a special-cased label; not worth touching
  already-shipped, already-reviewed code for a cosmetic report-label
  symmetry.
- **Repairing or re-encoding a broken motion photo**: this is a read-only
  diagnostic tool, matching every other integrity/analysis window in this
  app (`SefIntegrityWindow`, `AvSyncAnalysisWindow`, etc.).
- **A unified severity/menu that replaces "SEF 무결성 검사"**: the existing
  menu stays; this is additive.

## Error handling

- No motion-photo markers of any kind → menu item disabled (mirrors
  `hasSefData`'s existing pattern) — not an in-window error state.
- `ffprobe` missing/unable to launch → the decode check reports one
  `CRITICAL` line with the exception message; every other section's checks
  still run and report independently (one failed subprocess never blanks
  the rest of the report).
- Malformed/unparseable XMP → one `WARNING` line naming the parse failure,
  not a silent skip and not a window-level crash (`parseXmpDocument`'s
  existing XXE/entity-attack hardening, and its `Throwable` catch for
  `StackOverflowError`/`OutOfMemoryError` on crafted input, are unchanged
  and still apply).
- A window-level exception during analysis (any other unexpected failure)
  is caught and shown as `"오류: <message>"`, matching
  `SefIntegrityWindow`'s existing top-level `catch (e: Exception)`.

## Testing

- Pure logic (schema detection, offset/length arithmetic, bounds checks)
  is unit-testable against synthetic `BoxNode`/XMP-string fixtures, in the
  same style as `SefIntegrityAnalyzer`'s existing test suite — no ffmpeg
  needed for these.
- The `ffprobe`-decode check and the SEF-delegation path are verified via
  real synthesized files (`MotionPhotoBuilder` + real `ffmpeg`/`sips`, the
  same approach this project's SEF Integrity Check and Image Quality
  Comparison Enhancements branches both used for their own manual
  verification) rather than mocking ffmpeg's own behavior.
- Manual verification: open a real Samsung SEF motion photo, a real Google
  Motion Photo (both schema versions if obtainable), and a real HEIC/QT
  `mpvd`-style file; confirm each shows only its own relevant section(s),
  confirm the SEF section's content matches what the standalone "SEF 무결성
  검사" menu already shows for the same file, and confirm a deliberately
  truncated/corrupted embedded video is caught by the decode check even
  when its declared offset/length are otherwise correct.
