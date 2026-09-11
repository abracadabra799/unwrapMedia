# Shared ByteReader on File Open — Design

**Date:** 2026-09-11
**Status:** Approved (pending user spec review)

## Goal

Opening a media file (in the Media Comparison Analyzer and the main app's normal
open flow) currently opens the same file **independently up to 4 times** on the way
to becoming interactive:

1. `parseFile(file)` — opens its own `ByteReader` for the full structural walk.
2. `buildMediaSummary(root, file)` — opens its own `ByteReader` twice internally
   (`buildMotionPhotoVideoSummary`, `buildThumbnail`).
3. `ImageAnalyzer.analyze(file, root)` — opens its own `ByteReader` once internally
   (`tryExtractEmbeddedJpeg`).

Each open is a fresh `RandomAccessFile(file, "r")` construction. `ByteReader.kt`'s
own doc comment already documents that per-syscall file I/O is measurably slower on
Windows, especially under real-time antivirus/EDR scanning that hooks file I/O — that
comment describes a *within-one-open* problem (many small reads) that a 64KB
read-ahead cache already fixed. This is a *different, complementary* problem: the
file is reopened from scratch multiple times across the parse pipeline, and each open
(not just each read) can carry that same per-syscall tax. Reported symptom: opening
two 12-megapixel JPEGs in the Media Comparison Analyzer feels noticeably slow on
Windows, not on macOS — consistent with an environment where each file open is more
expensive (corporate AV/EDR), and consistent with the fact that isolated CPU-side
timing of parse+decode on a realistic EXIF-bearing 12MP JPEG measured fast (30–150ms
total) on this investigation's macOS test environment.

Sharing one already-open `ByteReader` across all three steps reduces the file-open
count for one file from 4 to 1.

## Non-goals

- `AppState.openFile`'s separate `findEmbeddedVideo` call and
  `GainmapParser.findGainmapInfo` (which itself opens a `ByteReader` up to 8 times
  internally for various HDR gain-map probes) are **not** touched by this change.
  They're a materially larger refactor and a separate concern from the reported
  symptom; noted as a follow-up opportunity, not silently dropped.
- `ImageAnalyzer.decodePrimaryBitmapAndHistogram`'s `file.readBytes()` (the
  full-resolution pixel decode) already opens the file exactly once via a different,
  necessarily-different access pattern (sequential full read vs. `ByteReader`'s
  random-access small reads) — left as-is.
- The HEIC/HEIF `ffmpeg` fallback path (`FfmpegImageSnapshotDecoder`, which round-trips
  through a temporary PNG file on disk) is a separate, already-identified improvement
  opportunity the user explicitly deferred — not part of this change.
- No change to what gets parsed or displayed — this is purely an I/O-count reduction.
  Every reader-accepting overload must produce byte-identical output to its existing
  file-only counterpart for the same input.

## Approach

`buildMediaSummary(root: BoxNode, file: File): MediaSummary` alone has over 130
existing call sites in tests (`MediaSummaryBuilderTest.kt` and siblings), all calling
the plain two-argument form. Changing that signature outright would force a
mechanical rewrite of all of them for zero behavioral benefit in tests (which don't
care about Windows AV-scan-per-open cost). Instead:

- **The existing two-argument public functions are kept, unchanged in signature,
  for every existing caller** (tests, CLI, anywhere that doesn't have a reader to
  share). They become thin wrappers: open one `ByteReader`, delegate to a new
  sibling overload that takes that reader explicitly.
- **A new reader-accepting overload is added next to each:**
  `parseFile(path: File, reader: ByteReader): BoxNode`,
  `buildMediaSummary(root: BoxNode, file: File, reader: ByteReader): MediaSummary`,
  `ImageAnalyzer.analyze(file: File, root: BoxNode, reader: ByteReader): ImageForensicData`.
  These carry the actual logic (moved from the bodies of the existing functions).
- **The two private helpers that each independently opened a reader**
  (`MediaSummaryBuilder.kt`'s `buildMotionPhotoVideoSummary` / `buildThumbnail`, and
  `ImageAnalyzer.kt`'s `tryExtractEmbeddedJpeg`) have their signatures changed in
  place to accept the passed-down reader instead of opening their own — safe because
  each is `private` with exactly one call site (inside the function being split), so
  there's no external caller to preserve compatibility for.
- **Only the two real per-file-open call sites are updated** to open one reader and
  call the three-argument overloads: `ImageCompareWindow.kt`'s `loadInfo` (the
  reported case) and `AppState.kt`'s `openFile` (the main app's normal open, which
  goes through the identical three calls and benefits identically).

This keeps the change mechanical and low-risk: every existing test keeps calling the
signature it already calls, and the new overloads are additive.

## File Structure

| File | Change |
|---|---|
| `app/src/main/kotlin/com/multiviewer/parser/ParseFile.kt` | Split `parseFile(path: File)` into a thin delegating wrapper + new `parseFile(path: File, reader: ByteReader)` carrying the existing body. |
| `app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt` | Same split for `buildMediaSummary`. `buildMotionPhotoVideoSummary`/`buildThumbnail` signatures change to accept `reader: ByteReader` directly (drop their internal `ByteReader.open`). |
| `app/src/main/kotlin/com/multiviewer/parser/ImageAnalyzer.kt` | Same split for `analyze`. `tryExtractEmbeddedJpeg` signature changes to accept `reader: ByteReader` directly. |
| `app/src/main/kotlin/com/multiviewer/ui/ImageCompareWindow.kt` | `loadInfo`'s background-thread branch opens one `ByteReader` and calls the three 3-argument overloads instead of the 2-argument ones. |
| `app/src/main/kotlin/com/multiviewer/ui/AppState.kt` | `openFile`'s equivalent three calls updated the same way. `findEmbeddedVideo`/`GainmapParser` calls untouched (non-goal). |
| Tests (new, alongside each split file's existing test file) | Regression-equivalence tests: the 3-argument overload must produce output identical to the 2-argument overload for the same fixture. One `ByteReader`-open-count test confirming the orchestration in `loadInfo`/`openFile` now opens the file once, not four times. |

## Error Handling

- No new error paths. Every reader-accepting overload does exactly what its
  file-only counterpart did with the bytes it reads — only *which reader instance*
  supplies those bytes changes. Exceptions/`null` fallbacks already present in
  `tryExtractEmbeddedJpeg`, `buildMotionPhotoVideoSummary`, and `buildThumbnail`
  (each already wraps its work in `try/catch` returning `null`/empty) are preserved
  verbatim — the reader passed in is expected to already be open and valid for the
  duration of the caller's `.use {}` block, matching how each already used its own
  reader within its own `.use {}` scope before this change.
- If a shared reader is closed early or throws, this surfaces as it already would
  have before this change (an `IOException`/`EOFException` from `ByteReader`'s
  underlying `RandomAccessFile`) — no new failure mode introduced.

## Testing

- **Regression-equivalence tests** (one per split function, using existing test
  fixtures already in each file's own test suite): for a given file/root, the
  2-argument and 3-argument overloads must return equal results (`BoxNode`,
  `MediaSummary`, `ImageForensicData` — using their existing `equals`/structural
  comparison, or comparing the specific fields each test already asserts on).
- **Open-count regression test**: a small `ByteReader.open` call-counting mechanism
  (e.g. an `AtomicInteger` incremented via a lambda hook exposed only for tests, or a
  before/after count using a wrapped/spy file) confirms `ImageCompareWindow.loadInfo`'s
  fresh-file-load path opens the file exactly once via `ByteReader`, not four times.
  Exact mechanism decided at plan time based on what's cleanly testable without
  production code carrying test-only hooks.
- **Manual/basic-behavior verification after implementation** (explicitly requested):
  since this touches the shared parse pipeline used by every file the app opens, run
  the full existing automated suite (regression safety net for every existing format
  this pipeline already supports — video, audio, other image formats, motion photos,
  gain maps) **and** manually re-verify the basic open flows still work end-to-end:
  opening a plain image (main window), opening two images in the Media Comparison
  Analyzer (the reported case), opening a video, opening an audio file, and opening a
  motion-photo JPEG (which exercises `buildMotionPhotoVideoSummary`'s non-null path,
  not just the common early-return).
