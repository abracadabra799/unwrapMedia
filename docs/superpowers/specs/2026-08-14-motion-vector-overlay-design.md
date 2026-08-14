# Motion Vector Overlay — Design Spec

**Goal:** Show, for a single selected frame in the existing GOP timeline, an image of that frame with its motion vectors drawn on top — the first sub-project in benchmarking multiViewer's video analyzer against Elecard StreamEye 4.

**Context:** multiViewer already has a GOP/frame-type timeline (`GopAnalysisView.kt`) driven by `ffprobe`-derived `FrameInfo` (index, I/P/B type, size, PTS), with click and arrow-key frame stepping that sets `tab.selectedFrame` and seeks the live player. It has no bitstream-level access to motion vectors, macroblocks, or QP — the app's only connection to FFmpeg is CLI subprocess invocation (`FfmpegLocator` resolving a bundled or PATH `ffmpeg`/`ffprobe` binary), with no JNI/FFI binding to `libavcodec`.

## Technical foundation (verified against real ffmpeg 8.1.2)

`ffprobe` cannot export raw per-block motion vector coordinates in any structured (JSON/CSV) form — it can only report that "Motion vectors" side data exists on a frame. The only CLI-accessible way to visualize motion vectors is FFmpeg's `codecview` video filter, which reads that internal side data and draws arrows directly onto the decoded frame's pixels. This was confirmed by direct testing:

```
ffmpeg -y -flags2 +export_mvs -i input.mp4 -vf "codecview=mv=pf+bf+bb" -frames:v 1 -update 1 out.png
```

This constrains the design: a fully custom, toggleable, per-vector-colored Compose Canvas overlay (in the style of `TileGridOverlay.kt`) is **not achievable** without a new native decoder binding, which is out of scope for this sub-project. The overlay is instead baked into the extracted frame's pixels by FFmpeg itself.

Frame-accurate extraction was also verified: FFmpeg's `-ss` placed *after* `-i` performs an accurate seek (decodes from the nearest keyframe forward, discarding frames, until landing exactly on the requested timestamp) — so `frame.ptsSeconds` from the existing `FrameInfo` can be used directly as the seek target with no frame-index-to-filter-`n` mapping needed:

```
ffmpeg -y -i input.mp4 -ss <ptsSeconds> -flags2 +export_mvs -vf "codecview=mv=pf+bf+bb" -frames:v 1 -update 1 out.png
```

Both commands were run against a locally generated test clip and produced correct single-frame PNG output.

## Scope (v1)

- Applies to a single selected/stepped frame in the GOP timeline only — not live playback. (Live-playback toggle is a natural follow-up: since `FfmpegVideoPlayer` already pipes continuously decoded frames, adding the same filter args to that pipe when a toggle is on is architecturally straightforward, but requires reconfiguring/restarting the live decode pipe, which is more involved and deferred.)
- Applies to whichever frame is currently `tab.selectedFrame` — I-frames have no motion vectors (the filter will simply draw nothing extra), P-frames get `pf` (forward), B-frames get `bf`+`bb` (forward/backward).
- No new external dependencies. No native decoder binding.

## Components

### 1. `MotionVectorFrameDecoder.kt` (new file)

Mirrors `FfmpegImageSnapshotDecoder.kt`'s existing "ffmpeg → temp PNG → Skia decode" pipeline rather than duplicating it:

```kotlin
package com.multiviewer.ui

import androidx.compose.ui.graphics.ImageBitmap
import java.io.File

// Pure, unit-testable: builds the ffmpeg CLI args for frame-accurate motion-vector-overlay
// extraction. -ss placed AFTER -i (accurate seek, decodes from the nearest keyframe forward to
// the exact requested timestamp) rather than before -i (fast but inexact seek) -- verified this
// combination produces the correct single frame against a real ffmpeg build.
fun buildMotionVectorFfmpegArgs(ffmpegPath: String, filePath: String, ptsSeconds: Double): List<String> = listOf(
    ffmpegPath, "-y", "-i", filePath,
    "-ss", ptsSeconds.toString(),
    "-flags2", "+export_mvs",
    "-vf", "codecview=mv=pf+bf+bb",
    "-frames:v", "1", "-update", "1",
)

object MotionVectorFrameDecoder {
    fun decodeFrameAsync(file: File, ptsSeconds: Double, onResult: (ImageBitmap?) -> Unit) {
        Thread {
            val result = FfmpegImageSnapshotDecoder.decodeSingleFrameToBitmap(
                buildMotionVectorFfmpegArgs(FfmpegLocator.ffmpegPath(), file.absolutePath, ptsSeconds),
            )
            java.awt.EventQueue.invokeLater { onResult(result) }
        }.apply { isDaemon = true }.start()
    }
}
```

`FfmpegImageSnapshotDecoder.decodeSingleFrameToBitmap` widens from `private` to `internal` (same-module visibility, same pattern already used for `extractItemBytes`/`findItemProperty` in `HeifHevcThumbnail.kt` during the HEIC tile work) so this new file can call it directly instead of re-implementing the temp-file/process/timeout/Skia-decode boilerplate.

### 2. `TabState` additions (`AppState.kt`)

```kotlin
var motionVectorOverlayEnabled by mutableStateOf(false)
var motionVectorFrameBitmap: ImageBitmap? by mutableStateOf(null)
var isDecodingMotionVectorFrame by mutableStateOf(false)
```

All three reset naturally per-tab (already-established `mutableStateOf` pattern used throughout `TabState`).

### 3. Trigger effect (`VideoInspectorUI.kt` or `MotionVectorPreview.kt`)

```kotlin
LaunchedEffect(tab.selectedFrame, tab.motionVectorOverlayEnabled) {
    val frame = tab.selectedFrame
    if (!tab.motionVectorOverlayEnabled || frame == null) {
        tab.motionVectorFrameBitmap = null
        tab.isDecodingMotionVectorFrame = false
        return@LaunchedEffect
    }
    tab.isDecodingMotionVectorFrame = true
    val bitmap = suspendCancellableCoroutine<ImageBitmap?> { cont ->
        MotionVectorFrameDecoder.decodeFrameAsync(tab.file, frame.ptsSeconds) { result ->
            if (cont.isActive) cont.resume(result)
        }
    }
    tab.motionVectorFrameBitmap = bitmap
    tab.isDecodingMotionVectorFrame = false
}
```

Stale-request handling relies on Compose's own `LaunchedEffect` cancellation: stepping frames quickly (arrow keys) re-keys the effect on every `tab.selectedFrame` change, which cancels the in-flight coroutine (including the suspended continuation) before starting a new one — no manual request-token/tick counter needed, consistent with how the rest of the app already relies on `LaunchedEffect` re-keying for this exact kind of staleness (e.g. `MotionPhotoVideoPreview`'s `remember(tab.file, video)` extraction).

### 4. UI placement (`VideoInspectorUI.kt`)

The existing "Right: GOP Analysis" column (currently just `GopAnalysisView`) splits vertically into two stacked panels via a new `gopMvSplit` state and a `DraggableDivider(orientation = Orientation.Horizontal, ...)`, following the exact same resizable-split pattern `videoGopSplit` already establishes for the player/GOP split:

- Top: existing `GopAnalysisView` (unchanged).
- Bottom: new `MotionVectorPreview.kt` composable — a toggle `Button` ("모션 벡터 켜기" / "모션 벡터 끄기", since the app has no existing Checkbox/Switch component — this follows the `Button`-toggle idiom `GopAnalysisView`'s own "프레임 분석 시작" button already establishes), then:
  - No frame selected: placeholder text ("프레임을 선택하세요").
  - `isDecodingMotionVectorFrame`: `DecodingIndicator` (existing shared composable).
  - `motionVectorFrameBitmap` present: the image, plus a caption (frame index/type — same `PreviewCaption` composable `ImageInspectorUI.kt` already uses for its preview panels).
  - Extraction failed (`decodeFrameAsync` resolved `null`): an error text, matching the app's existing "Primary Image Decoding Failed"-style fallback text.

## Error handling

`decodeSingleFrameToBitmap` already returns `null` on any failure (ffmpeg missing, non-zero exit, timeout, empty output, undecodable PNG) — `MotionVectorFrameDecoder` inherits this without new error-handling code; the UI just needs a `null`-but-not-loading state to render as a failure message rather than silently showing nothing.

## Testing

- `buildMotionVectorFfmpegArgs` is a pure function — unit tested directly (correct flag order/values, `-ss` positioned after `-i`, PTS formatting).
- The actual ffmpeg subprocess execution path is **not** unit tested, consistent with the existing untested state of `FfmpegImageSnapshotDecoder.decodeSingleFrameToBitmap` and `probeFrameTypes` (both real subprocess I/O boundaries already left to manual verification elsewhere in this codebase).
- Manual verification: open a video with a mix of I/P/B frames, step through frames with arrow keys, toggle motion vectors on, confirm arrows appear on P/B frames and not (or trivially) on I-frames, confirm rapid stepping doesn't show a stale frame's vectors.

## Out of scope (deferred)

- Live-playback motion vector toggle (requires reconfiguring `FfmpegVideoPlayer`'s continuous decode pipe).
- QP/macroblock heatmap, GOP dependency graph, PSNR/SSIM quality metrics, decode-exact frame stepping as a general capability (only borrowed here incidentally, via `-ss` after `-i`, for this one extraction path) — later StreamEye-benchmarking sub-projects per the priority list already discussed with the user.
