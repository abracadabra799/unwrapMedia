# Motion Vector Overlay Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show motion vectors baked onto a single selected GOP-timeline frame, via ffmpeg's `codecview` filter and an accurate `-ss`-after-`-i` seek — the first sub-project benchmarking multiViewer's video analyzer against Elecard StreamEye 4.

**Architecture:** A new small decoder file (`MotionVectorFrameDecoder.kt`) builds the ffmpeg command and reuses `FfmpegImageSnapshotDecoder`'s existing "run ffmpeg → temp PNG → Skia decode" pipeline. A new `TabState` toggle + bitmap + loading flag drive a new `MotionVectorPreview.kt` composable, wired into `VideoInspectorUI.kt`'s existing GOP column as a second, vertically-stacked, resizable panel below the GOP timeline.

**Tech Stack:** Kotlin, Compose Desktop, ffmpeg CLI subprocess (already a project dependency via `FfmpegLocator`), kotlinx-coroutines (`suspendCancellableCoroutine`, already used elsewhere in the project).

Full technical background and the verified ffmpeg commands are in `docs/superpowers/specs/2026-08-14-motion-vector-overlay-design.md`.

## Global Constraints

- Scope is v1 only: a single selected/stepped GOP-timeline frame (`tab.selectedFrame`), not live playback.
- No new external dependencies, no native/JNI decoder binding.
- ffmpeg command for extraction is exactly:
  ```
  <ffmpegPath> -y -i <filePath> -ss <ptsSeconds> -flags2 +export_mvs -vf codecview=mv=pf+bf+bb -frames:v 1 -update 1 <outputPath>
  ```
  `-ss` MUST come after `-i` (accurate seek — verified against a real ffmpeg 8.1.2 build; placing it before `-i` gives a fast but inaccurate seek and is NOT what this feature needs).
- Reuse `FfmpegImageSnapshotDecoder`'s existing `decodeSingleFrameToBitmap` helper rather than duplicating its temp-file/process/timeout/Skia-decode logic — widen its visibility, don't copy it.
- Stale-request handling relies on `LaunchedEffect` re-keying/cancellation (Compose's own structured concurrency) — no manual request-token/tick counter.
- UI toggle uses a `Button` (label text flips between "모션 벡터 켜기"/"모션 벡터 끄기"), not a `Checkbox`/`Switch` — this codebase has no existing Checkbox/Switch component anywhere, and introducing one for a single toggle would be a new UI pattern with no other adopter.

---

### Task 1: Motion vector frame decoder

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/ui/MotionVectorFrameDecoder.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/FfmpegImageSnapshotDecoder.kt:65` (widen `decodeSingleFrameToBitmap` from `private` to `internal`)
- Test: `app/src/test/kotlin/com/multiviewer/ui/MotionVectorFrameDecoderTest.kt`

**Interfaces:**
- Produces: `fun buildMotionVectorFfmpegArgs(ffmpegPath: String, filePath: String, ptsSeconds: Double): List<String>` — pure, used directly by Task 2's UI code is NOT required (Task 2 only calls `MotionVectorFrameDecoder.decodeFrameAsync`), but must stay public/top-level in this file for its own unit test.
- Produces: `object MotionVectorFrameDecoder { fun decodeFrameAsync(file: java.io.File, ptsSeconds: Double, onResult: (androidx.compose.ui.graphics.ImageBitmap?) -> Unit) }` — Task 2's `LaunchedEffect` calls this directly.
- Consumes: `FfmpegImageSnapshotDecoder.decodeSingleFrameToBitmap(inputArgs: List<String>): ImageBitmap?` (existing, widened to `internal` by this task) and `FfmpegLocator.ffmpegPath(): String` (existing, already `internal`/public — unchanged).

- [ ] **Step 1: Write the failing tests for `buildMotionVectorFfmpegArgs`**

Create `app/src/test/kotlin/com/multiviewer/ui/MotionVectorFrameDecoderTest.kt`:

```kotlin
package com.multiviewer.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class MotionVectorFrameDecoderTest {
    @Test
    fun `buildMotionVectorFfmpegArgs produces the accurate-seek plus codecview command in order`() {
        val args = buildMotionVectorFfmpegArgs("/usr/bin/ffmpeg", "/tmp/video.mp4", 1.5)
        assertEquals(
            listOf(
                "/usr/bin/ffmpeg", "-y", "-i", "/tmp/video.mp4",
                "-ss", "1.5",
                "-flags2", "+export_mvs",
                "-vf", "codecview=mv=pf+bf+bb",
                "-frames:v", "1", "-update", "1",
            ),
            args,
        )
    }

    @Test
    fun `buildMotionVectorFfmpegArgs formats an integer-valued pts seconds correctly`() {
        val args = buildMotionVectorFfmpegArgs("/usr/bin/ffmpeg", "/tmp/video.mp4", 2.0)
        assertEquals("2.0", args[args.indexOf("-ss") + 1])
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test --tests "com.multiviewer.ui.MotionVectorFrameDecoderTest"`
Expected: FAIL — `buildMotionVectorFfmpegArgs` is unresolved (compile error), since neither the function nor the file exists yet.

- [ ] **Step 3: Widen `decodeSingleFrameToBitmap`'s visibility**

In `app/src/main/kotlin/com/multiviewer/ui/FfmpegImageSnapshotDecoder.kt`, change:

```kotlin
    private fun decodeSingleFrameToBitmap(inputArgs: List<String>): ImageBitmap? {
```

to:

```kotlin
    internal fun decodeSingleFrameToBitmap(inputArgs: List<String>): ImageBitmap? {
```

- [ ] **Step 4: Create `MotionVectorFrameDecoder.kt`**

```kotlin
package com.multiviewer.ui

import androidx.compose.ui.graphics.ImageBitmap
import java.awt.EventQueue
import java.io.File

// Pure, unit-testable: builds the ffmpeg CLI args for frame-accurate motion-vector-overlay
// extraction. -ss placed AFTER -i is ffmpeg's accurate seek (decodes from the nearest keyframe
// forward to the exact requested timestamp) rather than the fast-but-inexact seek before -i --
// verified this combination produces the correct single frame against a real ffmpeg 8.1.2 build.
// -flags2 +export_mvs makes the decoder attach motion vector side data to each frame; codecview's
// mv=pf+bf+bb draws it as arrows directly onto the frame's own pixels (forward vectors on P/B
// frames, backward vectors on B frames -- I-frames have none, so the filter draws nothing extra).
fun buildMotionVectorFfmpegArgs(ffmpegPath: String, filePath: String, ptsSeconds: Double): List<String> = listOf(
    ffmpegPath, "-y", "-i", filePath,
    "-ss", ptsSeconds.toString(),
    "-flags2", "+export_mvs",
    "-vf", "codecview=mv=pf+bf+bb",
    "-frames:v", "1", "-update", "1",
)

// Reuses FfmpegImageSnapshotDecoder's "ffmpeg -> temp PNG -> Skia decode" pipeline (widened to
// internal above) instead of re-implementing its temp-file/process/timeout/decode boilerplate.
object MotionVectorFrameDecoder {
    fun decodeFrameAsync(file: File, ptsSeconds: Double, onResult: (ImageBitmap?) -> Unit) {
        Thread {
            val result = FfmpegImageSnapshotDecoder.decodeSingleFrameToBitmap(
                buildMotionVectorFfmpegArgs(FfmpegLocator.ffmpegPath(), file.absolutePath, ptsSeconds),
            )
            EventQueue.invokeLater { onResult(result) }
        }.apply { isDaemon = true }.start()
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test --tests "com.multiviewer.ui.MotionVectorFrameDecoderTest"`
Expected: PASS (2/2 tests)

- [ ] **Step 6: Run the full test suite**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test`
Expected: BUILD SUCCESSFUL, no regressions (the `FfmpegImageSnapshotDecoder` visibility widening is a strict relaxation, not a behavior change).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/MotionVectorFrameDecoder.kt \
        app/src/main/kotlin/com/multiviewer/ui/FfmpegImageSnapshotDecoder.kt \
        app/src/test/kotlin/com/multiviewer/ui/MotionVectorFrameDecoderTest.kt
git commit -m "Add motion vector frame decoder (ffmpeg codecview filter)"
```

---

### Task 2: Wire motion vector preview into the video inspector UI

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/AppState.kt:167` (add three `TabState` fields after the existing `seekRequestTick` field)
- Create: `app/src/main/kotlin/com/multiviewer/ui/MotionVectorPreview.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/VideoInspectorUI.kt` (split the GOP column vertically)

**Interfaces:**
- Consumes: `MotionVectorFrameDecoder.decodeFrameAsync(file: File, ptsSeconds: Double, onResult: (ImageBitmap?) -> Unit)` (Task 1).
- Consumes: `FrameInfo(index: Int, type: Char, sizeBytes: Int, ptsSeconds: Double)` (existing, `FrameTypeAnalyzer.kt`) via `tab.selectedFrame`.
- Consumes existing composables: `DecodingIndicator(text: String)`, `PreviewCaption(text: String, modifier: Modifier)`, `AppColors.Panel`, `AppColors.NeonRed` (all already used the same way in `ImageInspectorUI.kt`/`GopAnalysisView.kt` — no signature changes).
- Consumes: `DraggableDivider(orientation: Orientation, containerSizePx: Int, getSplit: () -> Float, setSplit: (Float) -> Unit)` (existing, `Components.kt`).
- Produces: `@Composable fun MotionVectorPreview(tab: TabState, modifier: Modifier = Modifier)` — called once from `VideoInspectorUI.kt`.

- [ ] **Step 1: Add `TabState` fields**

In `app/src/main/kotlin/com/multiviewer/ui/AppState.kt`, immediately after line 167 (`var seekRequestTick: Int by mutableStateOf(0)`), insert:

```kotlin

    // Motion vector overlay preview (see MotionVectorFrameDecoder.kt) -- a single selected GOP
    // frame re-extracted via ffmpeg's codecview filter with motion vectors baked onto its pixels.
    // Off by default; toggling on (or stepping to a new frame while on) triggers extraction.
    var motionVectorOverlayEnabled: Boolean by mutableStateOf(false)
    var motionVectorFrameBitmap: androidx.compose.ui.graphics.ImageBitmap? by mutableStateOf(null)
    var isDecodingMotionVectorFrame: Boolean by mutableStateOf(false)
```

- [ ] **Step 2: Create `MotionVectorPreview.kt`**

```kotlin
package com.multiviewer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.suspendCancellableCoroutine

// Toggle-driven preview of a single GOP frame with motion vectors baked onto it (see
// MotionVectorFrameDecoder.kt). Re-keying on (tab.selectedFrame, tab.motionVectorOverlayEnabled)
// means Compose itself cancels an in-flight extraction whenever the user steps to a new frame or
// flips the toggle before the previous one finished -- no manual staleness guard needed.
@Composable
fun MotionVectorPreview(tab: TabState, modifier: Modifier = Modifier) {
    LaunchedEffect(tab.selectedFrame, tab.motionVectorOverlayEnabled) {
        val frame = tab.selectedFrame
        if (!tab.motionVectorOverlayEnabled || frame == null) {
            tab.motionVectorFrameBitmap = null
            tab.isDecodingMotionVectorFrame = false
            return@LaunchedEffect
        }
        tab.isDecodingMotionVectorFrame = true
        val bitmap = suspendCancellableCoroutine { cont ->
            MotionVectorFrameDecoder.decodeFrameAsync(tab.file, frame.ptsSeconds) { result ->
                if (cont.isActive) cont.resume(result)
            }
        }
        tab.motionVectorFrameBitmap = bitmap
        tab.isDecodingMotionVectorFrame = false
    }

    Column(modifier = modifier.background(AppColors.Panel)) {
        Button(
            onClick = { tab.motionVectorOverlayEnabled = !tab.motionVectorOverlayEnabled },
            modifier = Modifier.padding(8.dp),
        ) {
            Text(if (tab.motionVectorOverlayEnabled) "모션 벡터 끄기" else "모션 벡터 켜기")
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val frame = tab.selectedFrame
            val bitmap = tab.motionVectorFrameBitmap
            when {
                !tab.motionVectorOverlayEnabled -> {}
                frame == null -> Text("프레임을 선택하세요", color = Color.Gray, fontSize = 13.sp)
                tab.isDecodingMotionVectorFrame -> DecodingIndicator("모션 벡터 추출 중...")
                bitmap != null -> {
                    Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                    PreviewCaption(
                        "Frame #${frame.index} (${frame.type})",
                        modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
                    )
                }
                else -> Text("모션 벡터 추출 실패", color = AppColors.NeonRed, fontSize = 13.sp)
            }
        }
    }
}
```

- [ ] **Step 3: Split the GOP column in `VideoInspectorUI.kt`**

Add `gopMvSplit` next to the existing `videoGopSplit` declaration:

```kotlin
    var videoGopSplit by remember { mutableStateOf(0.35f) }
    // GOP timeline gets more of the column by default (0.7) since it's the primary view here --
    // same asymmetric-default reasoning as videoGopSplit itself giving the live player more width
    // than the GOP panel.
    var gopMvSplit by remember { mutableStateOf(0.7f) }
```

Replace the existing `// Right: GOP Analysis (full height of the top region)` block:

```kotlin
                    // Right: GOP Analysis (full height of the top region)
                    GopAnalysisView(
                        tab,
                        onAnalyze = { appState.analyzeFrames(tab) },
                        modifier = Modifier.weight(1f - videoGopSplit).fillMaxHeight(),
                    )
```

with:

```kotlin
                    // Right: GOP Analysis (top) + Motion Vector Preview (bottom), stacked and
                    // independently resizable via gopMvSplit -- same DraggableDivider pattern
                    // videoGopSplit already establishes for the player/GOP split above.
                    var gopColumnHeightPx by remember { mutableStateOf(0) }
                    Column(
                        modifier = Modifier
                            .weight(1f - videoGopSplit)
                            .fillMaxHeight()
                            .onGloballyPositioned { gopColumnHeightPx = it.size.height },
                    ) {
                        GopAnalysisView(
                            tab,
                            onAnalyze = { appState.analyzeFrames(tab) },
                            modifier = Modifier.weight(gopMvSplit),
                        )

                        DraggableDivider(
                            orientation = Orientation.Horizontal,
                            containerSizePx = gopColumnHeightPx,
                            getSplit = { gopMvSplit },
                            setSplit = { gopMvSplit = it },
                        )

                        MotionVectorPreview(
                            tab,
                            modifier = Modifier.weight(1f - gopMvSplit).fillMaxWidth(),
                        )
                    }
```

`Orientation` and `onGloballyPositioned` are already imported in this file (used by `videoGopSplit`'s own `DraggableDivider` and `topContainerWidthPx`'s `onGloballyPositioned` respectively) — no new imports needed.

- [ ] **Step 4: Compile**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Run the full test suite**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test`
Expected: BUILD SUCCESSFUL, no regressions

- [ ] **Step 6: Manual verification**

Launch the app (`./gradlew :app:run`), open a video file with I/P/B frames, click "프레임 분석 시작" in the GOP panel, select a frame, click "모션 벡터 켜기" in the new bottom panel, confirm:
- A decoding indicator appears briefly, then an image with visible motion vector arrows on P/B frames.
- Stepping to a new frame (arrow keys) while the toggle is on re-extracts automatically.
- Rapidly stepping through several frames does not leave a stale/mismatched frame's vectors showing once extraction settles.
- Toggling off clears the preview; toggling back on re-extracts for the currently selected frame.
- The new divider between the GOP timeline and the motion vector panel drags to resize both.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/AppState.kt \
        app/src/main/kotlin/com/multiviewer/ui/MotionVectorPreview.kt \
        app/src/main/kotlin/com/multiviewer/ui/VideoInspectorUI.kt
git commit -m "Wire motion vector overlay preview into the GOP panel column"
```
