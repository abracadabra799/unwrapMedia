# QP / Macroblock Heatmap — Design Spec

**Goal:** Extend the existing motion vector preview into a two-mode "codec view" panel — motion vectors (already shipped) and a new QP (quantization parameter) heatmap — the second sub-project benchmarking multiViewer's video analyzer against Elecard StreamEye 4.

**Context:** The motion vector overlay (`docs/superpowers/specs/2026-08-14-motion-vector-overlay-design.md`, shipped in commit `ce5195f`) bakes per-block motion vectors onto a single selected GOP-timeline frame via ffmpeg's `codecview` filter, shown in a panel beside the Hex & Raw Data Viewer, gated to H.264 only (ffmpeg's native HEVC decoder doesn't export the side data `codecview` needs). QP heatmap reuses the exact same mechanism and UI shape for a different per-block value.

## Technical foundation (verified against real ffmpeg 8.1.2)

`codecview`'s `qp=1` option draws a **green color-intensity overlay** per macroblock (not numeric text) — darker/lighter shades of green encode the block's QP, directly onto the frame's pixels, the same "baked into pixels" approach the filter already uses for motion vectors. Confirmed visually against a synthetic test clip: distinct green shades appear per macroblock, varying with the underlying content.

Getting `codecview=qp=1` to draw anything requires exporting a *different* side data than motion vectors: `-export_side_data venc_params` (not `-flags2 +export_mvs`), and like `-flags2`, this is a decoder option that must be placed **before** `-i` to take effect (same placement rule already learned from the motion vector work).

**Both side-data exports are safe to request together.** Verified directly: passing both `-flags2 +export_mvs -export_side_data venc_params` before `-i` and switching only the `-vf codecview=...` argument produces byte-identical QP output to requesting `venc_params` alone, and still-correct motion vector output (arrows/dots present, confirmed visually) when combined with `-flags2` alone. This means one shared ffmpeg-args builder can serve both modes, differing only in the `-vf` value — no need for two near-duplicate command builders.

**QP export has the same H.264-only limitation as motion vectors, independently verified.** Ran the exact same real HEVC file used to verify the motion-vector limitation: a `codecview=qp=1` frame and a plain (no side-data, no filter) frame extracted at the identical timestamp are **byte-for-byte identical (matching SHA-256)** — ffmpeg's HEVC decoder silently ignores `-export_side_data venc_params` just as it ignores `-flags2 +export_mvs`, with no warning either way.

## Scope (v2 — extends v1)

- Same frame-selection model as motion vectors: applies to `tab.selectedFrame` only, no live playback.
- No new external dependencies, no native/JNI decoder binding (unchanged from v1).
- The existing motion vector panel becomes a **two-mode panel** (motion vectors / QP heatmap) rather than a second separate panel — they're alternate views of the same selected frame, and panel space beside the hex viewer is limited.
- Support is checked **per mode**, not as one combined flag: both currently gate to H.264 only, verified independently, but a future ffmpeg version could support one without the other (QP export is a newer, more general mechanism than motion vector export), so call sites must not assume they're equivalent.

## Components

### 1. `MotionVectorFrameDecoder.kt` → generalized in place (no rename — file already lives at the right layer; only its contents grow)

```kotlin
package com.multiviewer.ui

import androidx.compose.ui.graphics.ImageBitmap
import java.awt.EventQueue
import java.io.File
import java.util.concurrent.TimeUnit

enum class CodecViewMode { MOTION_VECTORS, QP_HEATMAP }

// Only H.264 is known to work, verified independently per mode -- ffmpeg's native HEVC decoder
// silently ignores both -flags2 +export_mvs and -export_side_data venc_params (no warning either
// way), confirmed by a byte-identical before/after comparison against a real HEVC file for each.
// Kept as separate per-mode checks (not one shared boolean) because they test two different
// ffmpeg mechanisms that could diverge in support in a future ffmpeg version.
fun codecViewSupportedFor(mode: CodecViewMode, codecName: String?): Boolean = codecName == "h264"

// Builds the ffmpeg CLI args for frame-accurate codec-view extraction. Requests BOTH side-data
// exports unconditionally regardless of mode (-flags2 +export_mvs and -export_side_data
// venc_params) -- verified this is harmless: requesting both together produces byte-identical QP
// output to requesting venc_params alone, and visually-correct motion vector output combined with
// just -flags2 alone. This keeps one shared builder instead of two near-duplicate ones; only the
// -vf value changes per mode. Both decoder options MUST precede -i (AVOptions on the decoder
// context, silently ignored if placed after -i -- see buildMotionVectorFfmpegArgs's original
// bug). -ss placed AFTER -i is ffmpeg's accurate seek, independent of the above.
fun buildCodecViewFfmpegArgs(ffmpegPath: String, filePath: String, ptsSeconds: Double, mode: CodecViewMode): List<String> {
    val vf = when (mode) {
        CodecViewMode.MOTION_VECTORS -> "codecview=mv=pf+bf+bb"
        CodecViewMode.QP_HEATMAP -> "codecview=qp=1"
    }
    return listOf(
        ffmpegPath, "-y",
        "-flags2", "+export_mvs",
        "-export_side_data", "venc_params",
        "-i", filePath,
        "-ss", ptsSeconds.toString(),
        "-vf", vf,
        "-frames:v", "1", "-update", "1",
    )
}

fun probeVideoCodecName(file: File): String? { /* unchanged from v1 */ }

object CodecViewFrameDecoder {
    fun decodeFrameAsync(file: File, ptsSeconds: Double, mode: CodecViewMode, onResult: (ImageBitmap?) -> Unit) {
        Thread {
            val result = FfmpegImageSnapshotDecoder.decodeSingleFrameToBitmap(
                buildCodecViewFfmpegArgs(FfmpegLocator.ffmpegPath(), file.absolutePath, ptsSeconds, mode),
            )
            EventQueue.invokeLater { onResult(result) }
        }.apply { isDaemon = true }.start()
    }
}
```

`buildMotionVectorFfmpegArgs`, `motionVectorsSupportedFor`, and the old single-mode `MotionVectorFrameDecoder` object are removed (not deprecated/kept alongside) — this is a v1→v2 generalization of the same feature, not a new parallel feature, so there is exactly one code path afterward. The implementation plan's task breakdown covers updating `MotionVectorFrameDecoderTest.kt` accordingly (same file, updated tests — not a new test file).

### 2. `TabState` changes (`AppState.kt`)

Replace the three v1 fields with mode-based equivalents:

```kotlin
// null = panel off; non-null = which codec-view mode is currently showing for tab.selectedFrame.
var codecViewMode: CodecViewMode? by mutableStateOf(null)
var codecViewFrameBitmap: androidx.compose.ui.graphics.ImageBitmap? by mutableStateOf(null)
var isDecodingCodecViewFrame: Boolean by mutableStateOf(false)
```

`videoCodecName` (added in v1) is unchanged and reused as-is for both modes' support checks.

### 3. `MotionVectorPreview.kt` → `CodecViewPreview.kt` (renamed; two-mode toggle)

The single "모션 벡터 켜기/끄기" `Button` becomes two buttons, one per mode, each showing only when `codecViewSupportedFor(mode, tab.videoCodecName)` is true. Clicking a mode's button when it's already active turns the panel off (sets `codecViewMode = null`); clicking the other mode's button switches directly to it. The `LaunchedEffect` re-keys on `(tab.selectedFrame, tab.codecViewMode)` instead of `(tab.selectedFrame, tab.motionVectorOverlayEnabled)` — switching modes cancels any in-flight extraction exactly the way stepping frames already does, no new staleness-handling logic needed.

```kotlin
@Composable
fun CodecViewPreview(tab: TabState, modifier: Modifier = Modifier) {
    LaunchedEffect(tab.selectedFrame, tab.codecViewMode) {
        val frame = tab.selectedFrame
        val mode = tab.codecViewMode
        if (mode == null || frame == null) {
            tab.codecViewFrameBitmap = null
            tab.isDecodingCodecViewFrame = false
            return@LaunchedEffect
        }
        tab.isDecodingCodecViewFrame = true
        val bitmap = suspendCancellableCoroutine { cont ->
            CodecViewFrameDecoder.decodeFrameAsync(tab.file, frame.ptsSeconds, mode) { result ->
                if (cont.isActive) cont.resume(result)
            }
        }
        tab.codecViewFrameBitmap = bitmap
        tab.isDecodingCodecViewFrame = false
    }

    Column(modifier = modifier.background(AppColors.Panel)) {
        Row(modifier = Modifier.padding(8.dp)) {
            if (codecViewSupportedFor(CodecViewMode.MOTION_VECTORS, tab.videoCodecName)) {
                Button(onClick = {
                    tab.codecViewMode = if (tab.codecViewMode == CodecViewMode.MOTION_VECTORS) null else CodecViewMode.MOTION_VECTORS
                }) { Text(if (tab.codecViewMode == CodecViewMode.MOTION_VECTORS) "모션 벡터 끄기" else "모션 벡터 켜기") }
            }
            if (codecViewSupportedFor(CodecViewMode.QP_HEATMAP, tab.videoCodecName)) {
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    tab.codecViewMode = if (tab.codecViewMode == CodecViewMode.QP_HEATMAP) null else CodecViewMode.QP_HEATMAP
                }) { Text(if (tab.codecViewMode == CodecViewMode.QP_HEATMAP) "QP 히트맵 끄기" else "QP 히트맵 켜기") }
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val frame = tab.selectedFrame
            val bitmap = tab.codecViewFrameBitmap
            when {
                tab.codecViewMode == null -> {}
                frame == null -> Text("프레임을 선택하세요", color = Color.Gray, fontSize = 13.sp)
                tab.isDecodingCodecViewFrame -> DecodingIndicator("추출 중...")
                bitmap != null -> {
                    PixelInspectorPreview(bitmap, modifier = Modifier.fillMaxSize(), resetKey = frame)
                    PreviewCaption("Frame #${frame.index} (${frame.type})", modifier = Modifier.align(Alignment.BottomStart).padding(4.dp))
                }
                else -> Text("추출 실패", color = AppColors.NeonRed, fontSize = 13.sp)
            }
        }
    }
}
```

(`PixelInspectorPreview`'s zoom/pan reuse is unchanged from v1 — QP shading needs the same native-pixel-scale legibility motion vectors did.)

### 4. Call site updates

- `Main.kt`: the gating condition `currentTab.type == MediaType.VIDEO && motionVectorsSupportedFor(currentTab.videoCodecName)` becomes `currentTab.type == MediaType.VIDEO && (codecViewSupportedFor(CodecViewMode.MOTION_VECTORS, currentTab.videoCodecName) || codecViewSupportedFor(CodecViewMode.QP_HEATMAP, currentTab.videoCodecName))` — the panel column is offered if *either* mode is available; the panel's own two-button row (above) independently hides whichever mode isn't. The `MotionVectorPreview(...)` call becomes `CodecViewPreview(...)`.
- `VideoInspectorUI.kt`: no changes needed beyond the existing `probeVideoCodecName` call already populating `tab.videoCodecName` (unchanged from v1; both modes share it).

## Error handling

Unchanged from v1 — `decodeSingleFrameToBitmap`'s existing null-on-failure/timeout behavior is inherited as-is; the UI's "추출 실패" branch is now mode-agnostic text rather than repeating "모션 벡터" in the failure message.

## Testing

- `codecViewSupportedFor` and `buildCodecViewFfmpegArgs` are pure functions — unit tested directly (per-mode support boolean; exact arg list per mode, including both decoder options preceding `-i` and the mode-specific `-vf` value).
- Same untested-subprocess-boundary convention as v1 for `CodecViewFrameDecoder.decodeFrameAsync` and `probeVideoCodecName`.
- Manual verification: open an H.264 file, confirm both mode buttons appear, motion vectors mode still behaves as before, QP heatmap mode shows a green-tinted frame that changes when stepping frames, switching modes doesn't show a stale bitmap from the other mode. Open an HEVC file, confirm the panel is absent (as today).

## Out of scope (deferred)

- GOP dependency graph, PSNR/SSIM quality metrics, decode-exact frame stepping as a general capability — later sub-projects.
- A numeric/legend readout of actual QP values (e.g. hovering a block to see its number) — `codecview`'s `qp=1` only bakes a color tint into pixels, not queryable numeric data; a numeric readout would need the same kind of native-decoder-binding investment the original motion-vector design explicitly ruled out.
