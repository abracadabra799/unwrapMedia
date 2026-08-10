# Image Viewer Zoom/Pan — Design

## Background

The user asked to make "all panels" zoomable. A code survey found this is already true for the audio waveform/spectrogram (mouse wheel zoom, drag pan, scrollbar, minimap) and the GOP analysis graph (mouse wheel zoom on the time axis), but not for the image viewer, the video player, the frame interval analysis graph, the Hex/Raw Data viewer, or the Raw Pixel viewer. These are different enough in kind (2D spatial zoom for images/video vs. 1D time-axis zoom for graphs vs. font-size scaling for text views) that they're being treated as separate sub-projects rather than one change. This spec covers the first: the image viewer.

Both of the image viewer's two boxes — the embedded EXIF thumbnail and the primary image view (`ImageInspectorUI.kt`) — already render through the same shared composable, `PixelInspectorPreview` (`PixelInspectorPreview.kt`), which today just does `Image(bitmap, contentScale = ContentScale.Fit)` with no interaction beyond that. Fixing this one composable covers both boxes.

The video player (`FfmpegVideoPlayer`) is explicitly **out of scope** — it already has click-to-pause and a seek-bar-drag gesture, and the user decided not to add zoom/pan there in this pass to avoid the added gesture-conflict complexity.

## Goal

`PixelInspectorPreview` supports mouse-wheel/trackpad zoom (cursor-anchored — the point under the cursor stays fixed on screen as scale changes, the standard "zoom toward pointer" behavior in image viewers) and drag-to-pan once zoomed in, with pan clamped so the image can never be dragged fully out of view. Double-click resets to the original fit view. Zoom/pan state resets automatically whenever the displayed bitmap changes (new file, or switching between thumbnail/primary — each box gets its own independent state since each is a separate `PixelInspectorPreview` call site).

## Non-Goals

- No pinch-to-zoom (trackpad pinch isn't a distinct gesture Compose Desktop exposes the same way mobile does — trackpad "zoom" gestures arrive as scroll events on desktop, same input this design already uses).
- No zoom for `FfmpegVideoPlayer` (video player, standalone or Motion Photo preview) — explicitly deferred.
- No zoom for any other panel (GOP already has it; frame interval analysis, Hex viewer, Raw Pixel viewer, Media Structure tree are separate future sub-projects, not part of this change).
- No persisted zoom level across file switches — always resets to fit, matching this app's existing per-file-reset convention (`waveformSplit`, `selectedFrame`, etc. all reset the same way via `remember(key)`).

## Design

### State

`PixelInspectorPreview` gains two `remember(bitmap)`-keyed state values: `scale: Float` (range `1f..MAX_ZOOM_SCALE`, where `1f` is the original fit view) and `offset: Offset` (pixel translation in the Box's own coordinate space). Keying on `bitmap` (not just the enclosing file) is deliberate: the thumbnail and primary image are two separate `PixelInspectorPreview` call sites with two separate `ImageBitmap` instances, so each already gets independent state for free — no shared/cross-talking zoom between the two boxes.

### Zoom (mouse wheel / trackpad scroll)

On a scroll event at cursor position `p` (in Box-local coordinates):

```
newScale = (scale * (1f - scrollDelta.y * ZOOM_STEP_FACTOR)).coerceIn(1f, MAX_ZOOM_SCALE)
offset = p - (p - offset) * (newScale / scale)
scale = newScale
```

This is the standard cursor-anchored zoom formula: it re-derives `offset` so that the content point currently under the cursor stays under the cursor after the scale change, rather than zooming around the box's center (which would make the point you're actually looking at drift away as you zoom in). `ZOOM_STEP_FACTOR` matches the feel already established by `FfmpegAudioPlayer.kt`'s own zoom step (`0.08`), reused verbatim so scroll-to-zoom feels consistent across the app. After computing `newScale`, `offset` is immediately re-clamped (see below) — zooming back out toward `1f` naturally pulls a previously out-of-bounds pan back into range.

### Pan (drag, only once zoomed in)

A `pointerInput` drag handler updates `offset += dragAmount`, active at any `scale` — at `scale == 1f` the clamp (below) collapses the valid range to a single point (no visible movement), so no separate scale-gating branch is needed; the clamp does that work implicitly and correctly for every scale in one place.

### Clamping

After both the box's own size and the bitmap's rendered (post-`ContentScale.Fit`) size are known, the maximum pan distance on each axis is `((renderedSize * scale) - renderedSize) / 2` — i.e., half of however much the scaled content now overhangs the original fit bounds on that axis. `offset.x`/`offset.y` are coerced into `-maxPanX..maxPanX` / `-maxPanY..maxPanY` after every zoom and every drag update. At `scale == 1f` this range is `0f..0f`, so panning is a no-op until the user has actually zoomed in, without needing an explicit `if (scale > 1f)` branch anywhere.

### Reset

A double-click, via `Modifier.pointerInput` + `detectTapGestures(onDoubleTap = { scale = 1f; offset = Offset.Zero })` (`androidx.compose.foundation.gestures.detectTapGestures`), resets to the original fit view.

### Rendering

The existing `Image(bitmap, contentScale = ContentScale.Fit, ...)` gains `Modifier.graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)`. `graphicsLayer` is a pure visual transform applied after layout — it doesn't require re-measuring the image or touching `ContentScale.Fit`'s own sizing logic, so the "fit" baseline this design zooms away from is exactly what already renders today.

## Testing

This is Compose UI gesture/rendering code with no pure-function logic worth isolating into a unit-testable helper beyond the zoom/clamp math itself. Following this project's established pattern (e.g. `shouldSkipFrame`/`laggedAfterFrame` in `FfmpegVideoPlayer.kt`, `visibleBucketRange` in `AudioWaveformPeaks.kt` — pure functions pulled out of Composables specifically so they're unit-testable without Compose test infrastructure):

- Extract the zoom-toward-cursor formula and the pan-clamping formula as standalone top-level functions (not methods on a Composable), each independently unit-tested with plain numeric assertions (no ffmpeg, no real files, no Compose test rule needed).
- The `graphicsLayer`/gesture-wiring itself is verified manually, matching how `WaveformDisplay`/`SpectrogramDisplay`'s actual Canvas drawing is manually verified rather than unit-tested.
