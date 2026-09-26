# Image Quality Comparison Enhancements — Design

**Date:** 2026-09-26
**Status:** Approved (pending user spec review)

## Goal

Three additions to the Media Comparison Analyzer (`ImageCompareWindow.kt`)
aimed at what image/video quality engineers actually reach for day to day,
distinct from the tool's existing forensic/structural comparison strengths
(structure diff, metadata diff, hex diff, raw pixel-difference heatmap):

- **(A) Numeric PSNR/SSIM for still images**, shown in the existing Diff
  Heatmap mode.
- **(B) A capture-condition mismatch warning**, surfaced wherever it matters
  (metadata view and next to the new PSNR/SSIM numbers), since comparing
  two images shot under different ISO/exposure/aperture/focal length is the
  #1 real-world mistake in image quality comparison — the numbers are
  technically correct but the comparison itself is invalid.
- **(C) Cursor-position pixel RGB readout** in the already-existing
  synchronized zoom/pan Side-by-Side view, for real pixel-peeping work.

## Background: what's already there (verified by reading the code, not assumed)

- **Idea "add a synchronized zoom/pan loupe" is already fully implemented.**
  `SideBySideCompareView` shares one `scale`/`offset` state applied
  identically to both panes' `graphicsLayer` transforms, with scroll-to-zoom
  (`zoomTowardPoint`), drag-to-pan (`clampPanOffset`), tap-to-center
  (`panToPoint`), and double-tap-to-reset already wired — confirmed while
  investigating this design, corrected the plan accordingly (no restated
  action item here).
- **PSNR/SSIM computation already exists and is directly reusable.**
  `QualityMetrics.kt`'s `runPsnrPass(comparison: File, reference: File, ...)`
  and `runSsimPass(...)` shell out to ffmpeg's `psnr`/`ssim` filters —
  nothing about their signatures is video-specific, and ffmpeg treats a
  still image file as a 1-frame input natively. Reusing these means **no
  new SSIM/PSNR math is written** — this codebase has repeatedly found
  hand-rolled numeric algorithms (fixed-point conversions, bit-packed
  fields) to be a real bug source elsewhere; reusing a battle-tested ffmpeg
  filter avoids that risk category entirely. `autoScale` (via ffmpeg's
  `scale2ref`) already handles a resolution mismatch between the two
  images.
- **`MetadataDiffView` already generically diffs every EXIF field**,
  including the capture-condition ones — `extractMetadataDiffRows` walks
  every `MediaSummary` section's fields and flags `isDifferent` per row,
  already highlighted with an orange background and "≠ DIFF" badge. What's
  missing isn't the diff detection itself, but a **specific, elevated
  warning** for the handful of fields whose mismatch specifically
  invalidates a quality comparison (as opposed to, say, a differing file
  name or GPS coordinate, which doesn't). Confirmed the exact field labels
  these appear under in `MediaSummaryBuilder.kt`: `"ISO"`,
  `"Exposure Time"`, `"F-Number"`, `"Aperture"`, `"Focal Length"`,
  `"White Balance"` (two slightly different label sets are used by two
  different summary-building code paths in that file, so both label
  spellings for aperture/F-number need to be checked).
- **No pixel-value-under-cursor readout exists anywhere in the app** —
  checked both `ImageCompareWindow.kt`'s `SideBySideCompareView` and the
  single-image `PixelInspectorPreview.kt` (the app's other zoom/pan
  viewer, used outside the compare window) — neither shows RGB values at
  the cursor. This is a genuine gap, not a duplicate of existing work.

## Approach

### (A) PSNR/SSIM in Diff Heatmap mode

When `VisualCompareMode.DIFF_HEATMAP` is selected, compute PSNR and SSIM
between `infoA.file`/`infoB.file` by calling the existing `runPsnrPass`/
`runSsimPass` (both files, not the decoded bitmaps — matches how these
functions already work, and sidesteps needing to write the two bitmaps back
out to temp files). Since these functions block (they call
`Process.waitFor` synchronously), run them inside a `LaunchedEffect(infoA.file,
infoB.file)` on `Dispatchers.IO`, with a loading state — matches this
codebase's established async-window pattern (e.g. `AvSyncAnalysisWindow`'s
`LaunchedEffect`+`isLoading`).

A still-image comparison always produces exactly one `MetricFrameSample`
(ffmpeg sees a "1-frame video"), so `MetricRunResult.statistics.mean` (or
`.perFrame.first().value`) is the single PSNR/SSIM value to display — no
new statistics handling needed, `computeStatistics` already handles a
1-element list correctly (min=max=mean=median).

Replace the Diff Heatmap mode's current caption ("🔍 차이점 마스크...")
with the PSNR/SSIM values once computed:
`"PSNR: 38.24 dB | SSIM: 0.9812"` (plain text, no color-coded quality
bands — `QualityCompareWindow.kt`'s existing PSNR/SSIM display is also
plain text with no such convention, so this doesn't invent a new visual
language the rest of the app doesn't already use). Both metrics compute in
one combined `LaunchedEffect` (two sequential ffmpeg calls) rather than two
independent effects, since they're always shown together.

If `resolutionsMatch(infoA.file, infoB.file)` is false (existing helper),
pass `autoScale = true` to both calls, matching `QualityCompareWindow.kt`'s
own existing decision logic for when to enable it.

### (B) Capture-condition mismatch warning

A new pure function, `fun captureConditionMismatches(rows: List<MetadataDiffRow>): List<String>`,
filters `extractMetadataDiffRows`'s already-computed output for rows whose
`key` is one of the known capture-condition labels
(`ISO`, `Exposure Time`, `F-Number`, `Aperture`, `Focal Length`,
`White Balance`) AND `isDifferent == true`, returning the matched labels
(empty list = no mismatch, the common case).

Two display sites, both derived from the same function call (computed once
per `infoA`/`infoB` pair, passed down rather than recomputed per site):

1. **`MetadataDiffView`**: a warning banner above the search bar, shown only
   when the list is non-empty — `"⚠️ 촬영조건이 다릅니다: ISO, F-Number"`
   (joining the mismatched labels), styled distinctly from the existing
   per-row orange diff highlighting (this is a comparison-validity warning,
   not just "a value differs").
2. **Next to the PSNR/SSIM readout** (part A, same `VisualDiffView`): a
   small inline warning icon + tooltip-style text when the list is
   non-empty, so a viewer looking at the numbers sees the caveat in the
   same glance, not only if they separately switch to the Metadata tab.

### (C) Cursor-position pixel RGB readout

In `SideBySideCompareView`, track the last hovered pointer position per pane
(a new `var hoverPosition by remember { mutableStateOf<Offset?>(null) }` per
pane, updated via a pointer-move handler — Compose Desktop's
`onPointerEvent(PointerEventType.Move, ...)`, the same event-handling style
already used for `PointerEventType.Scroll` in this file). Map the hover
position through the existing fitted-content math
(`fittedContentSize`/the `(pointerPos - offset) / scale` pattern
`panToPoint` already uses) down to a native-pixel coordinate in that pane's
bitmap, then read the RGB value via Skia's `Bitmap.getColor(x, y)` (already
used by `computeDiffBitmap`) for both `bitmapA` and `bitmapB` at that same
underlying position (only meaningful when both images are aligned/same
content, which is this tool's primary use case) — actual precise coordinate
math (accounting for letterboxing when the two images have different
aspect ratios) is worked out during planning, not this design.

Display: a small fixed overlay (e.g. bottom-left of the view area, mirroring
the existing zoom-level indicator's bottom-right placement) showing
`"A: #FF8040 (255,128,64)  B: #FE7E3F (254,126,63)"` while the cursor is
over either pane, hidden otherwise (`hoverPosition == null`).

## Non-goals (explicitly out of scope, with why)

- **Full SSIM/PSNR math reimplementation in Kotlin**: explicitly rejected in
  favor of reusing ffmpeg's existing, already-shipped, already-tested
  filters — see Background.
- **Color-coded quality-band thresholds** (e.g. green/yellow/red PSNR
  ranges): no established convention exists anywhere in this app for this;
  inventing one now for a single feature would be a new, unreviewed
  UI language, not a reuse of an existing pattern.
- **ΔE2000 color-difference metric, multi-ROI pinned-patch comparison,
  sharpness/MTF estimation, noise/SNR measurement**: these were flagged as
  medium/lower priority in the original brainstorm and are deferred to a
  possible future phase, not part of this design.
- **Capture-condition checks beyond the 6 listed fields** (e.g. lens
  model, sensor size): the 6 chosen fields are the standard "exposure
  triangle + focal length + white balance" set that directly affects pixel
  values; broader camera-identity fields (make/model/lens) matter for
  provenance, not for whether a pixel-level quality comparison is fair, and
  are already visible as ordinary (non-elevated) diff rows.
- **Extending the capture-condition warning or PSNR/SSIM to the video
  compare path** (`isVideoCompare` in `VisualDiffView`): video already has
  its own dedicated, more thorough PSNR/SSIM/VMAF tool
  (`QualityCompareWindow.kt`) — this phase is scoped to the still-image
  case in `ImageCompareWindow.kt` specifically.

## Error handling

- If ffmpeg fails or times out for the PSNR/SSIM pass (matches
  `runPsnrPass`/`runSsimPass`'s existing `null`-on-failure contract), show
  `"PSNR/SSIM 계산 실패"` in place of the numbers rather than blocking the
  rest of the Diff Heatmap view — the heatmap image itself still renders
  independently.
- `captureConditionMismatches` operates on already-extracted string values
  with no parsing that can throw; an empty/missing field is simply absent
  from both sides and never flagged as "different" (existing
  `extractMetadataDiffRows` behavior, unchanged).
- The pixel RGB readout degrades to hidden (not a crash or wrong value) if
  a hover position maps outside a bitmap's bounds (e.g. in the letterboxed
  margin) — bounds-check before calling `getColor`.

## Testing

- `captureConditionMismatches`: pure function, straightforward unit tests
  (no mismatch → empty list; one/several of the 6 fields differing →
  returned; a non-capture-condition field differing, e.g. file name → not
  returned; both label-spelling variants for aperture/F-number recognized).
- PSNR/SSIM integration: since `runPsnrPass`/`runSsimPass` are already
  tested/used elsewhere, this phase's new code is thin UI wiring — tested
  via manual verification (see below) rather than new unit tests of
  ffmpeg's own filters.
- Pixel-coordinate mapping math (the trickiest new logic): unit-testable in
  isolation as a pure function taking pointer position + pane size + native
  bitmap size + scale/offset, returning a native pixel coordinate (or null
  if out of bounds) — covering at least: 1x zoom no-letterbox, a
  letterboxed (non-matching aspect ratio) image, and a zoomed+panned case.
- Manual verification: two real photos (same scene, different ISO/exposure
  if achievable, or synthetically adjusted) opened in the Compare window,
  confirming the PSNR/SSIM numbers appear, the capture-condition warning
  appears when expected and stays absent for two identically-shot images,
  and the pixel readout tracks the cursor correctly at various zoom levels
  including a letterboxed image.
