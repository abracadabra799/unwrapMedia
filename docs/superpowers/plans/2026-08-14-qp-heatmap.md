# QP / Macroblock Heatmap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the shipped motion vector overlay into a two-mode "codec view" panel (motion vectors / QP heatmap) — the second sub-project benchmarking multiViewer's video analyzer against Elecard StreamEye 4.

**Architecture:** Generalizes the existing single-mode decoder/UI pair into a mode-parameterized pair. One shared ffmpeg-args builder now requests both side-data exports (`-flags2 +export_mvs` and `-export_side_data venc_params`) unconditionally and only varies the `-vf codecview=...` argument by mode — verified harmless to request both together. `TabState`'s single enabled-boolean becomes a nullable mode enum.

**Tech Stack:** Kotlin, Compose Desktop, ffmpeg CLI subprocess (unchanged from v1), kotlinx-coroutines (unchanged from v1).

Full technical background and the verified ffmpeg commands are in `docs/superpowers/specs/2026-08-14-qp-heatmap-design.md`.

## Global Constraints

- Scope is the same single-selected-frame model as v1 (`tab.selectedFrame`), not live playback.
- No new external dependencies, no native/JNI decoder binding.
- One shared ffmpeg-args builder for both modes, requesting both `-flags2 +export_mvs` and `-export_side_data venc_params` unconditionally, both placed BEFORE `-i` (decoder options — placed after `-i` they are silently ignored, per v1's fix). `-ss` stays AFTER `-i` for accurate seeking. Only the `-vf` value differs per mode:
  - `CodecViewMode.MOTION_VECTORS` → `codecview=mv=pf+bf+bb`
  - `CodecViewMode.QP_HEATMAP` → `codecview=qp=1`
- Support is checked **per mode** via `codecViewSupportedFor(mode, codecName)`, not one shared boolean — both currently return true only for `"h264"`, but the two modes must remain independently checkable since they test different ffmpeg mechanisms.
- This is a v1→v2 generalization of one feature, not a new parallel feature: the old single-mode names (`buildMotionVectorFfmpegArgs`, `motionVectorsSupportedFor`, `MotionVectorFrameDecoder` object, `MotionVectorPreview` composable, and the three old `TabState` fields `motionVectorOverlayEnabled`/`motionVectorFrameBitmap`/`isDecodingMotionVectorFrame`) are removed and replaced, not kept alongside the new ones.
- Refinement over the spec text (spec said "no rename" for the decoder file): for naming consistency with the renamed `CodecViewFrameDecoder` object inside it, this plan renames the file too: `MotionVectorFrameDecoder.kt` → `CodecViewFrameDecoder.kt`, and its test file `MotionVectorFrameDecoderTest.kt` → `CodecViewFrameDecoderTest.kt` (class renamed to match). This is a same-session refinement, not a deviation requiring approval — noted here for the record.

---

### Task 1: Generalize the codec-view decoder into a two-mode API

**Files:**
- Delete: `app/src/main/kotlin/com/multiviewer/ui/MotionVectorFrameDecoder.kt` (replaced by the file below)
- Create: `app/src/main/kotlin/com/multiviewer/ui/CodecViewFrameDecoder.kt`
- Delete: `app/src/test/kotlin/com/multiviewer/ui/MotionVectorFrameDecoderTest.kt` (replaced by the file below)
- Create: `app/src/test/kotlin/com/multiviewer/ui/CodecViewFrameDecoderTest.kt`

**Interfaces:**
- Produces: `enum class CodecViewMode { MOTION_VECTORS, QP_HEATMAP }`
- Produces: `fun codecViewSupportedFor(mode: CodecViewMode, codecName: String?): Boolean`
- Produces: `fun buildCodecViewFfmpegArgs(ffmpegPath: String, filePath: String, ptsSeconds: Double, mode: CodecViewMode): List<String>`
- Produces: `object CodecViewFrameDecoder { fun decodeFrameAsync(file: java.io.File, ptsSeconds: Double, mode: CodecViewMode, onResult: (androidx.compose.ui.graphics.ImageBitmap?) -> Unit) }` — Task 2's `LaunchedEffect` calls this directly.
- Produces: `fun probeVideoCodecName(file: java.io.File): String?` — unchanged signature from v1, carried over into the new file, still called from `VideoInspectorUI.kt` (no change needed there).
- Consumes: `FfmpegImageSnapshotDecoder.decodeSingleFrameToBitmap(inputArgs: List<String>): ImageBitmap?` and `FfmpegLocator.ffmpegPath()`/`FfmpegLocator.ffprobePath()`/`FfmpegLocator.configureEnvironment(ProcessBuilder)` (all existing, unchanged).

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/multiviewer/ui/CodecViewFrameDecoderTest.kt`:

```kotlin
package com.multiviewer.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodecViewFrameDecoderTest {
    @Test
    fun `buildCodecViewFfmpegArgs requests both side-data exports before -i for motion vectors mode`() {
        val args = buildCodecViewFfmpegArgs("/usr/bin/ffmpeg", "/tmp/video.mp4", 1.5, CodecViewMode.MOTION_VECTORS)
        assertEquals(
            listOf(
                "/usr/bin/ffmpeg", "-y",
                "-flags2", "+export_mvs",
                "-export_side_data", "venc_params",
                "-i", "/tmp/video.mp4",
                "-ss", "1.5",
                "-vf", "codecview=mv=pf+bf+bb",
                "-frames:v", "1", "-update", "1",
            ),
            args,
        )
    }

    @Test
    fun `buildCodecViewFfmpegArgs uses the qp filter for QP heatmap mode, same side-data exports`() {
        val args = buildCodecViewFfmpegArgs("/usr/bin/ffmpeg", "/tmp/video.mp4", 1.5, CodecViewMode.QP_HEATMAP)
        assertEquals(
            listOf(
                "/usr/bin/ffmpeg", "-y",
                "-flags2", "+export_mvs",
                "-export_side_data", "venc_params",
                "-i", "/tmp/video.mp4",
                "-ss", "1.5",
                "-vf", "codecview=qp=1",
                "-frames:v", "1", "-update", "1",
            ),
            args,
        )
    }

    @Test
    fun `buildCodecViewFfmpegArgs formats an integer-valued pts seconds correctly`() {
        val args = buildCodecViewFfmpegArgs("/usr/bin/ffmpeg", "/tmp/video.mp4", 2.0, CodecViewMode.MOTION_VECTORS)
        assertEquals("2.0", args[args.indexOf("-ss") + 1])
    }

    @Test
    fun `codecViewSupportedFor is true only for h264, independently per mode`() {
        assertTrue(codecViewSupportedFor(CodecViewMode.MOTION_VECTORS, "h264"))
        assertTrue(codecViewSupportedFor(CodecViewMode.QP_HEATMAP, "h264"))
        assertFalse(codecViewSupportedFor(CodecViewMode.MOTION_VECTORS, "hevc"))
        assertFalse(codecViewSupportedFor(CodecViewMode.QP_HEATMAP, "hevc"))
        assertFalse(codecViewSupportedFor(CodecViewMode.MOTION_VECTORS, null))
        assertFalse(codecViewSupportedFor(CodecViewMode.QP_HEATMAP, null))
    }
}
```

- [ ] **Step 2: Delete the old test file and run to verify the new one fails**

```bash
rm app/src/test/kotlin/com/multiviewer/ui/MotionVectorFrameDecoderTest.kt
```

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test --tests "com.multiviewer.ui.CodecViewFrameDecoderTest"`
Expected: FAIL — `CodecViewMode`/`buildCodecViewFfmpegArgs`/`codecViewSupportedFor` are unresolved (compile error), since `CodecViewFrameDecoder.kt` doesn't exist yet and the old `MotionVectorFrameDecoder.kt` doesn't define these names.

- [ ] **Step 3: Delete the old decoder file and create the new one**

```bash
rm app/src/main/kotlin/com/multiviewer/ui/MotionVectorFrameDecoder.kt
```

Create `app/src/main/kotlin/com/multiviewer/ui/CodecViewFrameDecoder.kt`:

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

// One-shot codec name lookup, cheap regardless of file length (unlike probeFrameTypes' -show_frames
// scan) -- safe to run once per opened video tab to decide which codec-view modes to offer.
fun probeVideoCodecName(file: File): String? {
    return try {
        val process = ProcessBuilder(
            FfmpegLocator.ffprobePath(), "-v", "error", "-select_streams", "v:0",
            "-show_entries", "stream=codec_name",
            "-of", "default=noprint_wrappers=1:nokey=1", file.absolutePath,
        ).redirectErrorStream(false).redirectError(ProcessBuilder.Redirect.DISCARD)
            .also { FfmpegLocator.configureEnvironment(it) }.start()
        val name = process.inputStream.bufferedReader().readLine()?.trim()
        process.waitFor(10, TimeUnit.SECONDS)
        name?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }
}

// Pure, unit-testable: builds the ffmpeg CLI args for frame-accurate codec-view extraction.
// Requests BOTH side-data exports unconditionally regardless of mode (-flags2 +export_mvs and
// -export_side_data venc_params) -- verified this is harmless: requesting both together produces
// byte-identical QP output to requesting venc_params alone, and visually-correct motion vector
// output combined with just -flags2 alone. This keeps one shared builder instead of two
// near-duplicate ones; only the -vf value changes per mode. Both decoder options MUST precede -i
// (AVOptions on the decoder context, silently ignored if placed after -i -- verified against a
// real ffmpeg 8.1.2 build for -flags2 in the motion-vector-only predecessor of this function).
// -ss placed AFTER -i is ffmpeg's accurate seek, independent of the above.
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

// Reuses FfmpegImageSnapshotDecoder's "ffmpeg -> temp PNG -> Skia decode" pipeline instead of
// re-implementing its temp-file/process/timeout/decode boilerplate.
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

- [ ] **Step 4: Run the new test to verify it passes**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test --tests "com.multiviewer.ui.CodecViewFrameDecoderTest"`
Expected: PASS (4/4 tests)

- [ ] **Step 5: Compile — expect Task 2's not-yet-updated call sites to fail**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:compileKotlin`
Expected: FAIL — `AppState.kt`, `MotionVectorPreview.kt`, and `Main.kt` still reference the removed `motionVectorsSupportedFor`/`buildMotionVectorFfmpegArgs`/`MotionVectorFrameDecoder`/`motionVectorOverlayEnabled`/`motionVectorFrameBitmap`/`isDecodingMotionVectorFrame` names. **This failure is expected and is not this task's problem to fix** — Task 2 updates every one of those call sites. Do not modify `AppState.kt`, `MotionVectorPreview.kt`, or `Main.kt` in this task.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/CodecViewFrameDecoder.kt \
        app/src/test/kotlin/com/multiviewer/ui/CodecViewFrameDecoderTest.kt
git rm app/src/main/kotlin/com/multiviewer/ui/MotionVectorFrameDecoder.kt \
       app/src/test/kotlin/com/multiviewer/ui/MotionVectorFrameDecoderTest.kt
git commit -m "Generalize motion vector decoder into two-mode codec-view decoder"
```

(`git rm` here is a no-op re-stage for files already deleted via `rm` in Steps 2/3 — included for clarity/completeness of what the commit contains.)

---

### Task 2: Wire the two-mode panel into the app

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/AppState.kt:169-177` (replace the three v1 `TabState` fields with mode-based equivalents)
- Delete: `app/src/main/kotlin/com/multiviewer/ui/MotionVectorPreview.kt`
- Create: `app/src/main/kotlin/com/multiviewer/ui/CodecViewPreview.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/Main.kt:416-456` (gating condition and call site)

**Interfaces:**
- Consumes: `CodecViewFrameDecoder.decodeFrameAsync(file: File, ptsSeconds: Double, mode: CodecViewMode, onResult: (ImageBitmap?) -> Unit)`, `codecViewSupportedFor(mode: CodecViewMode, codecName: String?): Boolean`, `CodecViewMode` (Task 1).
- Consumes existing composables unchanged from v1: `DecodingIndicator(text: String)`, `PreviewCaption(text: String, modifier: Modifier)`, `PixelInspectorPreview(bitmap, modifier, resetKey)`, `AppColors.Panel`, `AppColors.NeonRed`, `DraggableDivider(...)` (all already used the same way in the current `MotionVectorPreview.kt`/`Main.kt` — no signature changes).
- Produces: `@Composable fun CodecViewPreview(tab: TabState, modifier: Modifier = Modifier)` — called once from `Main.kt`, replacing the `MotionVectorPreview(...)` call.

- [ ] **Step 1: Replace the `TabState` fields**

In `app/src/main/kotlin/com/multiviewer/ui/AppState.kt`, replace lines 169-177 (currently):

```kotlin
    // Motion vector overlay preview (see MotionVectorFrameDecoder.kt) -- a single selected GOP
    // frame re-extracted via ffmpeg's codecview filter with motion vectors baked onto its pixels.
    // Off by default; toggling on (or stepping to a new frame while on) triggers extraction. Only
    // offered for H.264 video (see motionVectorsSupportedFor) -- ffmpeg's HEVC decoder silently
    // ignores the export_mvs request, so the panel is hidden entirely rather than shown empty.
    // null videoCodecName means "not probed yet"; probing happens once per opened video tab.
    var videoCodecName: String? by mutableStateOf(null)
    var motionVectorOverlayEnabled: Boolean by mutableStateOf(false)
    var motionVectorFrameBitmap: androidx.compose.ui.graphics.ImageBitmap? by mutableStateOf(null)
    var isDecodingMotionVectorFrame: Boolean by mutableStateOf(false)
```

with:

```kotlin
    // Codec-view preview (see CodecViewFrameDecoder.kt) -- a single selected GOP frame
    // re-extracted via ffmpeg's codecview filter, in one of two modes (motion vectors or QP
    // heatmap) baked onto its pixels. null codecViewMode means the panel is off; selecting a mode
    // (or stepping to a new frame while one is active) triggers extraction. Each mode is offered
    // independently per codecViewSupportedFor -- only H.264 today for both, verified separately
    // per mode since they use different ffmpeg mechanisms. null videoCodecName means "not probed
    // yet"; probing happens once per opened video tab.
    var videoCodecName: String? by mutableStateOf(null)
    var codecViewMode: com.multiviewer.ui.CodecViewMode? by mutableStateOf(null)
    var codecViewFrameBitmap: androidx.compose.ui.graphics.ImageBitmap? by mutableStateOf(null)
    var isDecodingCodecViewFrame: Boolean by mutableStateOf(false)
```

(`com.multiviewer.ui.CodecViewMode` is fully qualified only because `AppState.kt` is itself in `com.multiviewer.ui` and `CodecViewMode` is a top-level enum in the same package — Kotlin resolves this without an import either way; write it as plain `CodecViewMode?` since no import is needed for a same-package type.)

- [ ] **Step 2: Delete the old preview file and create the new one**

```bash
rm app/src/main/kotlin/com/multiviewer/ui/MotionVectorPreview.kt
```

Create `app/src/main/kotlin/com/multiviewer/ui/CodecViewPreview.kt`:

```kotlin
package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

// Toggle-driven preview of a single GOP frame in one of two codec-view modes (see
// CodecViewFrameDecoder.kt): motion vectors or a QP heatmap, baked onto the frame's own pixels by
// ffmpeg. Re-keying on (tab.selectedFrame, tab.codecViewMode) cancels the AWAITING coroutine
// whenever the user steps to a new frame or switches/turns off the mode before the previous
// request finished -- the cont.isActive guard then stops a late result from ever being assigned,
// so no manual staleness guard is needed for what the UI shows. This does NOT kill the underlying
// ffmpeg subprocess itself: decodeFrameAsync's background Thread runs to completion (bounded by
// decodeSingleFrameToBitmap's own 8s timeout) regardless of cancellation, since that shared helper
// doesn't expose its Process handle. Acceptable for this single-frame, click-triggered scope --
// would need revisiting if a future live-playback toggle triggers this continuously instead.
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
                }) {
                    Text(if (tab.codecViewMode == CodecViewMode.MOTION_VECTORS) "모션 벡터 끄기" else "모션 벡터 켜기")
                }
            }
            if (codecViewSupportedFor(CodecViewMode.QP_HEATMAP, tab.videoCodecName)) {
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    tab.codecViewMode = if (tab.codecViewMode == CodecViewMode.QP_HEATMAP) null else CodecViewMode.QP_HEATMAP
                }) {
                    Text(if (tab.codecViewMode == CodecViewMode.QP_HEATMAP) "QP 히트맵 끄기" else "QP 히트맵 켜기")
                }
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
                    // codecview draws both motion vectors and QP shading at native pixel scale, and
                    // a typical video frame is far larger than this panel -- a plain fit-to-panel
                    // Image would shrink either past legibility. Reuses the same scroll-to-zoom/
                    // drag-to-pan viewer PIXEL INSPECTOR already uses instead of building a second one.
                    PixelInspectorPreview(
                        bitmap,
                        modifier = Modifier.fillMaxSize(),
                        resetKey = frame,
                    )
                    PreviewCaption(
                        "Frame #${frame.index} (${frame.type})",
                        modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
                    )
                }
                else -> Text("추출 실패", color = AppColors.NeonRed, fontSize = 13.sp)
            }
        }
    }
}
```

- [ ] **Step 3: Update `Main.kt`'s gating and call site**

Replace lines 416-456 (currently):

```kotlin
                        // Motion vector preview sits beside the hex grid rather than in the GOP
                        // column (see VideoInspectorUI.kt) -- the hex grid's own row width is
                        // fixed by its byte-per-row count, so on any window wider than that it
                        // already leaves empty space in this panel to reuse, and this panel is
                        // also taller than the GOP column, where the same content was too small
                        // to make the motion vector arrows legible even zoomed in. Only offered
                        // for H.264 (motionVectorsSupportedFor) -- ffmpeg's HEVC decoder silently
                        // ignores the export_mvs request codecview needs, so for any other codec
                        // this panel is omitted entirely rather than shown with nothing to show.
                        var hexMotionVectorSplit by remember(currentTab) { mutableStateOf(0.6f) }
                        var hexRowWidthPx by remember(currentTab) { mutableStateOf(0) }
                        val bottomPanel: @Composable ColumnScope.() -> Unit = {
                            PanelHeader("Hex & Raw Data Viewer", color = AppColors.NeonGreen)
                            if (currentTab.type == MediaType.VIDEO && motionVectorsSupportedFor(currentTab.videoCodecName)) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .onGloballyPositioned { hexRowWidthPx = it.size.width },
                                ) {
                                    Box(modifier = Modifier.weight(hexMotionVectorSplit).fillMaxHeight()) {
                                        HexView(
                                            file = currentTab.file,
                                            highlightRange = currentTab.tileHighlightRange
                                                ?: activeField?.let { it.offset until (it.offset + it.length) }
                                                ?: currentTab.selected?.let { it.offset until (it.offset + it.size) },
                                            listState = hexListState,
                                        )
                                    }

                                    DraggableDivider(
                                        orientation = Orientation.Vertical,
                                        containerSizePx = hexRowWidthPx,
                                        getSplit = { hexMotionVectorSplit },
                                        setSplit = { hexMotionVectorSplit = it },
                                    )

                                    MotionVectorPreview(
                                        currentTab,
                                        modifier = Modifier.weight(1f - hexMotionVectorSplit).fillMaxHeight(),
                                    )
                                }
                            } else {
                                HexView(
```

with:

```kotlin
                        // Codec-view preview (motion vectors / QP heatmap) sits beside the hex
                        // grid rather than in the GOP column (see VideoInspectorUI.kt) -- the hex
                        // grid's own row width is fixed by its byte-per-row count, so on any
                        // window wider than that it already leaves empty space in this panel to
                        // reuse, and this panel is also taller than the GOP column, where the same
                        // content was too small to make the overlays legible even zoomed in. The
                        // panel column is offered if EITHER mode is supported; CodecViewPreview
                        // itself independently hides whichever mode isn't (see codecViewSupportedFor).
                        var hexCodecViewSplit by remember(currentTab) { mutableStateOf(0.6f) }
                        var hexRowWidthPx by remember(currentTab) { mutableStateOf(0) }
                        val bottomPanel: @Composable ColumnScope.() -> Unit = {
                            PanelHeader("Hex & Raw Data Viewer", color = AppColors.NeonGreen)
                            val codecViewAvailable = currentTab.type == MediaType.VIDEO &&
                                (codecViewSupportedFor(CodecViewMode.MOTION_VECTORS, currentTab.videoCodecName) ||
                                    codecViewSupportedFor(CodecViewMode.QP_HEATMAP, currentTab.videoCodecName))
                            if (codecViewAvailable) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .onGloballyPositioned { hexRowWidthPx = it.size.width },
                                ) {
                                    Box(modifier = Modifier.weight(hexCodecViewSplit).fillMaxHeight()) {
                                        HexView(
                                            file = currentTab.file,
                                            highlightRange = currentTab.tileHighlightRange
                                                ?: activeField?.let { it.offset until (it.offset + it.length) }
                                                ?: currentTab.selected?.let { it.offset until (it.offset + it.size) },
                                            listState = hexListState,
                                        )
                                    }

                                    DraggableDivider(
                                        orientation = Orientation.Vertical,
                                        containerSizePx = hexRowWidthPx,
                                        getSplit = { hexCodecViewSplit },
                                        setSplit = { hexCodecViewSplit = it },
                                    )

                                    CodecViewPreview(
                                        currentTab,
                                        modifier = Modifier.weight(1f - hexCodecViewSplit).fillMaxHeight(),
                                    )
                                }
                            } else {
                                HexView(
```

(The rest of the `else` branch and the closing braces after it are unchanged — only the block shown above is replaced.)

- [ ] **Step 4: Compile**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:compileKotlin`
Expected: BUILD SUCCESSFUL (this also confirms Task 1's expected failure from its own Step 5 is now resolved)

- [ ] **Step 5: Run the full test suite**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test`
Expected: BUILD SUCCESSFUL, no regressions

- [ ] **Step 6: Manual verification**

Launch the app (`./gradlew :app:run`), open the H.264 test file (or any H.264 video), select a frame in the GOP panel, confirm:
- Both "모션 벡터 켜기" and "QP 히트맵 켜기" buttons appear beside the hex viewer.
- Motion vectors mode still behaves exactly as before (arrows/dots visible after zooming in).
- QP heatmap mode shows a green-tinted frame; the tint visibly varies across different content regions.
- Switching between the two modes (or stepping to a new frame while one is active) doesn't briefly show a stale bitmap from the other mode/frame.
- Turning a mode off (clicking its button again) clears the preview.

Open an HEVC file, confirm the whole panel (both buttons) is absent, same as before this change — the Hex & Raw Data Viewer fills the full panel width as it did prior to the motion vector feature.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/AppState.kt \
        app/src/main/kotlin/com/multiviewer/ui/CodecViewPreview.kt \
        app/src/main/kotlin/com/multiviewer/Main.kt
git rm app/src/main/kotlin/com/multiviewer/ui/MotionVectorPreview.kt
git commit -m "Add QP heatmap mode alongside motion vectors in the codec-view panel"
```
