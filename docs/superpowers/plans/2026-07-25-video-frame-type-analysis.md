# Video Frame-Type (GOP) Analysis Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** From the video inspector, a user can trigger a per-frame I/P/B breakdown of the whole video and see it as a horizontally scrollable, color-coded bar graph; clicking a frame shows its details in the existing right-hand details panel.

**Architecture:** A new `probeFrameTypes` function shells out to the already-bundled `ffprobe` to get per-frame type/size/timestamp (no hand-rolled bitstream parser). A new `AppState.analyzeFrames(tab)` method runs it on a background thread, button-triggered, following the exact same background-thread + `EventQueue.invokeLater` pattern `openFile()` already uses. A new `GopAnalysisView` composable renders the result as a virtualized `LazyRow` of colored bars. This fully replaces three already-dead-code files (`BitrateVisualizer.kt`, `BoxBlockView.kt`, `VideoAnalyzer.kt`), which are deleted.

**Tech Stack:** Kotlin 2.2.20, Compose Multiplatform Desktop, bundled `ffprobe` (via `FfmpegLocator`, already used by `FfmpegVideoPlayer.kt`).

## Global Constraints

- No hand-rolled H.264/HEVC bitstream parser — `ffprobe -show_frames` supplies frame types.
- No automatic analysis on tab open — manually triggered by a button.
- `ffprobe`'s CSV output (`-of csv=p=0`) does **not** preserve the field order given in `-show_entries`, and its field count is inconsistent between frames (verified directly: a real synthetic video produced 19 rows of 3 CSV fields and 1 row of 4). Use `-of default=noprint_wrappers=1` (reliable `key=value` lines, verified in the exact fixed order `pts_time`, `pkt_size`, `pict_type` per frame) instead — this is a correctness requirement, not a style preference.
- `BitrateVisualizer.kt`, `BoxBlockView.kt`, `VideoAnalyzer.kt`, `VideoAnalysisData`, `BitratePoint`, and `tab.videoAnalysis` are deleted in full — confirmed via full-codebase search to have no other call sites.
- Spec: `docs/superpowers/specs/2026-07-25-video-frame-type-analysis-design.md`.

---

### Task 1: `probeFrameTypes` — ffprobe-backed frame data extraction

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/ui/FrameTypeAnalyzer.kt`
- Test: `app/src/test/kotlin/com/multiviewer/ui/FrameTypeAnalyzerTest.kt`

**Interfaces:**
- Consumes: `FfmpegLocator.ffprobePath()` (existing, `com.multiviewer.ui` package).
- Produces: `data class FrameInfo(val index: Int, val type: Char, val sizeBytes: Int, val ptsSeconds: Double)` and `fun probeFrameTypes(file: File): List<FrameInfo>?` — both consumed by Task 2.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/kotlin/com/multiviewer/ui/FrameTypeAnalyzerTest.kt`:

```kotlin
package com.multiviewer.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FrameTypeAnalyzerTest {
    @Test
    fun `probeFrameTypes reads type, size, and timestamp for every frame of a real synthetic video`() {
        val video = File.createTempFile("frame-type-analyzer-test-", ".mp4")
        video.deleteOnExit()
        val generate = ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "testsrc=duration=2:size=64x48:rate=10",
            video.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        generate.waitFor()

        val frames = probeFrameTypes(video)

        assertTrue(frames != null && frames.size == 20, "Expected 20 frames (2s at 10fps), got ${frames?.size}")
        assertEquals(0, frames!![0].index)
        assertEquals('I', frames[0].type)
        assertTrue(frames[0].sizeBytes > 0)
        assertEquals(0.0, frames[0].ptsSeconds)
        assertEquals(19, frames[19].index)
        assertTrue(frames.any { it.type == 'P' }, "Expected at least one P frame")
        assertTrue(frames.any { it.type == 'B' }, "Expected at least one B frame")
        video.delete()
    }

    @Test
    fun `probeFrameTypes returns null for a nonexistent file`() {
        assertNull(probeFrameTypes(File("/nonexistent/path/does-not-exist.mp4")))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew :app:test --tests "com.multiviewer.ui.FrameTypeAnalyzerTest" --console=plain`
Expected: FAIL to compile — `probeFrameTypes` and `FrameInfo` don't exist yet.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/kotlin/com/multiviewer/ui/FrameTypeAnalyzer.kt`:

```kotlin
package com.multiviewer.ui

import java.io.File
import java.util.concurrent.TimeUnit

data class FrameInfo(val index: Int, val type: Char, val sizeBytes: Int, val ptsSeconds: Double)

// ffprobe's CSV output (-of csv=p=0) does NOT preserve the field order given in -show_entries,
// and its field count is inconsistent between frames (verified directly against a real video:
// some rows had an unexpected trailing empty field). -of default=noprint_wrappers=1 instead
// prints reliable "key=value" lines, always in the fixed order pts_time, pkt_size, pict_type per
// frame -- accumulate them into a map and finalize one FrameInfo each time pict_type (always last)
// is seen.
fun probeFrameTypes(file: File): List<FrameInfo>? {
    return try {
        val process = ProcessBuilder(
            FfmpegLocator.ffprobePath(), "-v", "error", "-select_streams", "v:0",
            "-show_entries", "frame=pict_type,pkt_size,pts_time",
            "-of", "default=noprint_wrappers=1", file.absolutePath,
        ).redirectErrorStream(false).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val lines = process.inputStream.bufferedReader().readLines()
        process.waitFor(120, TimeUnit.SECONDS)

        val values = mutableMapOf<String, String>()
        val frames = mutableListOf<FrameInfo>()
        for (line in lines) {
            val eq = line.indexOf('=')
            if (eq < 0) continue
            val key = line.substring(0, eq)
            val value = line.substring(eq + 1)
            values[key] = value
            if (key == "pict_type") {
                val pts = values["pts_time"]?.toDoubleOrNull()
                val size = values["pkt_size"]?.toIntOrNull()
                val type = value.firstOrNull()
                if (pts != null && size != null && type != null) {
                    frames.add(FrameInfo(frames.size, type, size, pts))
                }
                values.clear()
            }
        }
        if (frames.isEmpty()) null else frames
    } catch (e: Exception) {
        null
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew :app:test --tests "com.multiviewer.ui.FrameTypeAnalyzerTest" --console=plain`
Expected: PASS, both tests green.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/FrameTypeAnalyzer.kt app/src/test/kotlin/com/multiviewer/ui/FrameTypeAnalyzerTest.kt
git commit -m "Add ffprobe-backed per-frame I/P/B type extraction"
```

---

### Task 2: State wiring + retire dead bitrate/treemap code

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/AppState.kt`
- Delete: `app/src/main/kotlin/com/multiviewer/parser/VideoAnalyzer.kt`
- Delete: `app/src/main/kotlin/com/multiviewer/ui/BitrateVisualizer.kt`
- Delete: `app/src/main/kotlin/com/multiviewer/ui/BoxBlockView.kt`
- Test: `app/src/test/kotlin/com/multiviewer/ui/AppStateTest.kt`

**Interfaces:**
- Consumes: `probeFrameTypes(file: File): List<FrameInfo>?` and `FrameInfo` from Task 1 (same package, no import needed).
- Produces: `TabState.gopFrames: List<FrameInfo>?`, `TabState.isAnalyzingFrames: Boolean`, `TabState.selectedFrame: FrameInfo?`, and `AppState.analyzeFrames(tab: TabState): Unit` — all consumed by Task 3.

- [ ] **Step 1: Write the failing test**

Add to `app/src/test/kotlin/com/multiviewer/ui/AppStateTest.kt` (inside the `AppStateTest` class, alongside the existing `waitForLoad` helper — reuse the same polling pattern for this second kind of async operation):

```kotlin
    private fun waitForFrameAnalysis(tab: TabState, timeoutMs: Long = 15000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (tab.isAnalyzingFrames) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for frame analysis of ${tab.file.name}" }
            Thread.sleep(10)
        }
    }

    @Test
    fun `analyzeFrames populates gopFrames from a real synthetic video without blocking`() {
        val video = File.createTempFile("appstate-frame-analysis-test-", ".mp4")
        video.deleteOnExit()
        val generate = ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "testsrc=duration=2:size=64x48:rate=10",
            video.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        generate.waitFor()

        val appState = AppState()
        val tab = TabState(video)

        appState.analyzeFrames(tab)
        assertEquals(true, tab.isAnalyzingFrames)

        waitForFrameAnalysis(tab)

        assertEquals(false, tab.isAnalyzingFrames)
        assertEquals(20, tab.gopFrames?.size)
        assertEquals('I', tab.gopFrames?.first()?.type)
        video.delete()
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew :app:test --tests "com.multiviewer.ui.AppStateTest" --console=plain`
Expected: FAIL to compile — `analyzeFrames`, `isAnalyzingFrames`, `gopFrames` don't exist on `TabState`/`AppState` yet.

- [ ] **Step 3: Add the new TabState fields**

In `app/src/main/kotlin/com/multiviewer/ui/AppState.kt`, replace:

```kotlin
class TabState(val file: File) {
    var isLoading: Boolean by mutableStateOf(true)
    var type by mutableStateOf(MediaType.UNKNOWN)
    var root: BoxNode? by mutableStateOf(null)
    var mediaSummary: MediaSummary? by mutableStateOf(null)
    var imageForensic: ImageForensicData? by mutableStateOf(null)
    var videoAnalysis: VideoAnalysisData? by mutableStateOf(null)
    
    var embeddedVideo: EmbeddedVideo? by mutableStateOf(null)
    var motionPhotoPreview: EmbeddedVideo? by mutableStateOf(null)
    var error: String? by mutableStateOf(null)
    var selected: BoxNode? by mutableStateOf(null)
    var verticalSplit: Float by mutableStateOf(0.5f)
    var horizontalSplit: Float by mutableStateOf(1f / 1.3f)
    var summaryTabIndex: Int by mutableStateOf(0)
}
```

with:

```kotlin
class TabState(val file: File) {
    var isLoading: Boolean by mutableStateOf(true)
    var type by mutableStateOf(MediaType.UNKNOWN)
    var root: BoxNode? by mutableStateOf(null)
    var mediaSummary: MediaSummary? by mutableStateOf(null)
    var imageForensic: ImageForensicData? by mutableStateOf(null)

    var embeddedVideo: EmbeddedVideo? by mutableStateOf(null)
    var motionPhotoPreview: EmbeddedVideo? by mutableStateOf(null)
    var error: String? by mutableStateOf(null)
    var selected: BoxNode? by mutableStateOf(null)
    var verticalSplit: Float by mutableStateOf(0.5f)
    var horizontalSplit: Float by mutableStateOf(1f / 1.3f)
    var summaryTabIndex: Int by mutableStateOf(0)

    // GOP / frame-type analysis (see FrameTypeAnalyzer.kt) -- null gopFrames means "never asked";
    // an empty (non-null) list means "asked, ffprobe found nothing".
    var gopFrames: List<FrameInfo>? by mutableStateOf(null)
    var isAnalyzingFrames: Boolean by mutableStateOf(false)
    var selectedFrame: FrameInfo? by mutableStateOf(null)
}
```

- [ ] **Step 4: Remove `VideoAnalysisData`/`BitratePoint` and the `videoAnalysis` assignment in `openFile()`**

In the same file, replace:

```kotlin
data class BitratePoint(val timestampSeconds: Double, val kbps: Double)

data class VideoAnalysisData(
    val bitratePoints: List<BitratePoint> = emptyList(),
    val boxWeights: Map<String, Long> = emptyMap()
)

class TabState(val file: File) {
```

with:

```kotlin
class TabState(val file: File) {
```

Then replace:

```kotlin
                var imageForensic: ImageForensicData? = null
                var videoAnalysis: VideoAnalysisData? = null
                when (type) {
                    MediaType.IMAGE -> imageForensic = ImageAnalyzer.analyze(file, root)
                    MediaType.VIDEO -> {
                        videoAnalysis = VideoAnalyzer.analyze(file, root)
                        // Attempt to extract thumbnail for video files too
                        imageForensic = ImageAnalyzer.analyze(file, root)
                    }
                    else -> {}
                }
                val finalImageForensic = imageForensic

                EventQueue.invokeLater {
                    tab.root = root
                    tab.type = type
                    tab.mediaSummary = mediaSummary
                    tab.embeddedVideo = embeddedVideo
                    tab.motionPhotoPreview = motionPhotoPreview
                    tab.videoAnalysis = videoAnalysis
                    tab.isLoading = false
```

with:

```kotlin
                var imageForensic: ImageForensicData? = null
                when (type) {
                    MediaType.IMAGE -> imageForensic = ImageAnalyzer.analyze(file, root)
                    MediaType.VIDEO -> {
                        // Attempt to extract thumbnail for video files too
                        imageForensic = ImageAnalyzer.analyze(file, root)
                    }
                    else -> {}
                }
                val finalImageForensic = imageForensic

                EventQueue.invokeLater {
                    tab.root = root
                    tab.type = type
                    tab.mediaSummary = mediaSummary
                    tab.embeddedVideo = embeddedVideo
                    tab.motionPhotoPreview = motionPhotoPreview
                    tab.isLoading = false
```

- [ ] **Step 5: Add `AppState.analyzeFrames`**

In the same file, add this method to the `AppState` class, right after `closeTab`:

```kotlin
    fun analyzeFrames(tab: TabState) {
        if (tab.isAnalyzingFrames || tab.gopFrames != null) return
        tab.isAnalyzingFrames = true
        Thread {
            val frames = probeFrameTypes(tab.file)
            EventQueue.invokeLater {
                tab.gopFrames = frames ?: emptyList()
                tab.isAnalyzingFrames = false
            }
        }.apply { isDaemon = true }.start()
    }
```

- [ ] **Step 6: Delete the three fully-superseded dead-code files**

```bash
git rm app/src/main/kotlin/com/multiviewer/parser/VideoAnalyzer.kt
git rm app/src/main/kotlin/com/multiviewer/ui/BitrateVisualizer.kt
git rm app/src/main/kotlin/com/multiviewer/ui/BoxBlockView.kt
```

- [ ] **Step 7: Run test to verify it passes**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew :app:test --tests "com.multiviewer.ui.AppStateTest" --console=plain`
Expected: PASS, all `AppStateTest` tests green (including the new one).

- [ ] **Step 8: Run the full suite to confirm the deletions didn't break anything else**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew test --console=plain`
Expected: `BUILD SUCCESSFUL`, all tests passing — confirms nothing else referenced `VideoAnalyzer`/`VideoAnalysisData`/`BitrateVisualizer`/`BoxBlockView`/`tab.videoAnalysis`.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/AppState.kt app/src/test/kotlin/com/multiviewer/ui/AppStateTest.kt
git commit -m "Wire GOP frame analysis into AppState; retire dead bitrate/treemap code"
```

---

### Task 3: GOP graph UI + details-panel wiring

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/ui/GopAnalysisView.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/VideoInspectorUI.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt` (the `DetailedPropertiesPanel` composable defined at its bottom)
- Modify: `app/src/main/kotlin/com/multiviewer/Main.kt`

**Interfaces:**
- Consumes: `TabState.gopFrames`, `TabState.isAnalyzingFrames`, `TabState.selectedFrame`, `AppState.analyzeFrames(tab)`, `FrameInfo` (all from Task 2, same package where relevant).
- Produces: nothing consumed by later tasks — this is the last task in the plan.

No automated test for these Composables (established convention for this project's UI layer, same as every other Compose file). Verification is the full test suite compiling cleanly plus manual checks below.

- [ ] **Step 1: Create the GOP graph view**

Create `app/src/main/kotlin/com/multiviewer/ui/GopAnalysisView.kt`:

```kotlin
package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val GOP_GRAPH_HEIGHT_DP = 120
private const val FRAME_BAR_WIDTH_DP = 3

private fun colorForFrameType(type: Char) = when (type) {
    'I' -> AppColors.NeonRed
    'P' -> AppColors.NeonGreen
    'B' -> AppColors.NeonBlue
    else -> AppColors.TextSecondary
}

@Composable
fun GopAnalysisView(tab: TabState, onAnalyze: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(GOP_GRAPH_HEIGHT_DP.dp)
            .background(AppColors.Panel),
    ) {
        val frames = tab.gopFrames
        when {
            tab.isAnalyzingFrames -> {
                Text(
                    "분석 중...",
                    modifier = Modifier.align(Alignment.Center),
                    style = AppTypography.bodyLarge.copy(color = AppColors.TextSecondary),
                )
            }
            frames == null -> {
                Button(onClick = onAnalyze, modifier = Modifier.align(Alignment.Center)) {
                    Text("프레임 분석 시작")
                }
            }
            frames.isEmpty() -> {
                Text(
                    "Could not analyze frames",
                    modifier = Modifier.align(Alignment.Center),
                    style = AppTypography.bodyLarge.copy(color = AppColors.NeonRed),
                )
            }
            else -> {
                val maxSize = frames.maxOf { it.sizeBytes }.coerceAtLeast(1)
                LazyRow(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                    items(frames) { frame ->
                        val barHeightDp = ((frame.sizeBytes.toFloat() / maxSize) * (GOP_GRAPH_HEIGHT_DP - 16)).coerceAtLeast(1f)
                        Column(
                            modifier = Modifier.width(FRAME_BAR_WIDTH_DP.dp).fillMaxSize(),
                            verticalArrangement = Arrangement.Bottom,
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(FRAME_BAR_WIDTH_DP.dp)
                                    .height(barHeightDp.dp)
                                    .background(colorForFrameType(frame.type))
                                    .clickable {
                                        tab.selectedFrame = frame
                                        tab.selected = null
                                    },
                            )
                        }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 2: Wire it into `VideoInspectorUI.kt`**

In `app/src/main/kotlin/com/multiviewer/ui/VideoInspectorUI.kt`, replace:

```kotlin
                // Resizable Divider
                DraggableDivider(
                    orientation = Orientation.Horizontal,
                    containerSizePx = containerHeightPx,
                    getSplit = { verticalSplit },
                    setSplit = { verticalSplit = it }
                )
```

with:

```kotlin
                GopAnalysisView(tab, onAnalyze = { appState.analyzeFrames(tab) })

                // Resizable Divider
                DraggableDivider(
                    orientation = Orientation.Horizontal,
                    containerSizePx = containerHeightPx,
                    getSplit = { verticalSplit },
                    setSplit = { verticalSplit = it }
                )
```

`GopAnalysisView` needs `appState` to call `analyzeFrames`, so `VideoInspectorUI` needs it too. Replace the function signature:

```kotlin
@Composable
fun VideoInspectorUI(
    tab: TabState,
    leftPanel: @Composable ColumnScope.() -> Unit,
    bottomPanel: @Composable ColumnScope.() -> Unit
) {
```

with:

```kotlin
@Composable
fun VideoInspectorUI(
    appState: AppState,
    tab: TabState,
    leftPanel: @Composable ColumnScope.() -> Unit,
    bottomPanel: @Composable ColumnScope.() -> Unit
) {
```

- [ ] **Step 3: Update `VideoInspectorUI`'s call site in `Main.kt`**

In `app/src/main/kotlin/com/multiviewer/Main.kt`, replace:

```kotlin
                            MediaType.VIDEO -> VideoInspectorUI(currentTab, leftPanel, bottomPanel)
```

with:

```kotlin
                            MediaType.VIDEO -> VideoInspectorUI(appState, currentTab, leftPanel, bottomPanel)
```

- [ ] **Step 4: Make the box-tree selection clear `selectedFrame`**

In the same file (`Main.kt`), replace:

```kotlin
                                BoxTreeView(
                                    root = rootNode,
                                    selected = currentTab.selected,
                                    onSelect = { currentTab.selected = it },
                                )
```

with:

```kotlin
                                BoxTreeView(
                                    root = rootNode,
                                    selected = currentTab.selected,
                                    onSelect = {
                                        currentTab.selected = it
                                        currentTab.selectedFrame = null
                                    },
                                )
```

- [ ] **Step 5: Show frame details in `DetailedPropertiesPanel`**

In `app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt`, replace:

```kotlin
@Composable
fun DetailedPropertiesPanel(tab: TabState) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        PanelHeader("Detailed Properties")
        Spacer(Modifier.height(16.dp))
        
        val selectedNode = tab.selected
        if (selectedNode != null) {
```

with:

```kotlin
@Composable
fun DetailedPropertiesPanel(tab: TabState) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        PanelHeader("Detailed Properties")
        Spacer(Modifier.height(16.dp))

        val selectedFrame = tab.selectedFrame
        if (selectedFrame != null) {
            PropertyRow("Frame #", selectedFrame.index.toString())
            PropertyRow("Type", selectedFrame.type.toString())
            PropertyRow("Size", "${selectedFrame.sizeBytes} bytes")
            PropertyRow("PTS", "${selectedFrame.ptsSeconds}s")
            return@Column
        }

        val selectedNode = tab.selected
        if (selectedNode != null) {
```

- [ ] **Step 6: Run the full test suite**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew test --console=plain`
Expected: `BUILD SUCCESSFUL`, all tests passing — confirms the `VideoInspectorUI` signature change and `DetailedPropertiesPanel` edit compile cleanly against every call site.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/GopAnalysisView.kt app/src/main/kotlin/com/multiviewer/ui/VideoInspectorUI.kt app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt app/src/main/kotlin/com/multiviewer/Main.kt
git commit -m "Add GOP frame-type graph to the video inspector"
```

- [ ] **Step 8: Build and run the app**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew :app:run`
Expected: app window opens with no build errors.

- [ ] **Step 9: Manually verify**

Open a video file. Confirm a "프레임 분석 시작" button appears between the player and the divider. Click it, confirm "분석 중..." appears briefly, then a horizontally scrollable color-coded bar graph appears. Scroll it. Click a bar and confirm the right-hand "Detailed Properties" panel shows Frame #/Type/Size/PTS for that frame. Click a node in the left box tree afterward and confirm the right panel switches back to showing that node's properties (not stale frame details). Open an image file and confirm its "Detailed Properties" panel still works normally (unaffected by this change, since `selectedFrame` stays null for image tabs).
