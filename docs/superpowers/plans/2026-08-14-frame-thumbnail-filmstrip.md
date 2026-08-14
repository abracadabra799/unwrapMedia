# Frame Thumbnail Filmstrip Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Below the existing GOP bar chart, show a scrollable row of real decoded frame thumbnails — lazily loaded per visible range, synced to playback position, each labeled with frame number and type.

**Architecture:** A pure "what to fetch next" function decides, from the currently-visible scroll range and what's already cached, the minimal contiguous range to batch-decode. A new decoder runs one `ffmpeg` call per batch (accurate-seek once, decode a contiguous run of frames sequentially — verified far cheaper than one seek per frame), caching results by frame index on `TabState`. A new `LazyRow`-based composable drives this from its own visible-range changes and renders cached thumbnails as they arrive.

**Tech Stack:** Kotlin, Compose Desktop, ffmpeg CLI subprocess (unchanged from prior codec-view work), kotlinx-coroutines' `snapshotFlow` (new usage in this codebase, but a standard Compose API for observing state changes like scroll position outside a direct recomposition).

Full technical background and the verified ffmpeg commands are in `docs/superpowers/specs/2026-08-14-frame-thumbnail-filmstrip-design.md`.

## Global Constraints

- Lazy, visible-range-triggered extraction only — never eager whole-file decoding.
- One `ffmpeg` call per batch: `-i <file> -ss <startPtsSeconds> -frames:v <count> -vf scale=120:-1 -vsync 0 <tempDir>/thumb_%05d.png` — `-ss` placed AFTER `-i` for accurate seeking (same rule already established and verified for `CodecViewFrameDecoder.kt`).
- Thumbnails cached per frame index on `TabState` for the tab's lifetime; no eviction/cap in this iteration.
- The filmstrip is its own independently-scrolling `LazyRow`, not synchronized with `GopAnalysisView`'s own scroll/zoom state.
- Fixed cell width, no zoom, on the filmstrip itself.
- No new external dependencies.

---

### Task 1: Extract `currentFrameIndex` into a shared, tested pure function

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/FrameTypeAnalyzer.kt` (add the function)
- Modify: `app/src/main/kotlin/com/multiviewer/ui/GopAnalysisView.kt:138-141` (call the shared function instead of the inline `remember` block)
- Test: `app/src/test/kotlin/com/multiviewer/ui/FrameTypeAnalyzerTest.kt` (new file — no existing test file for this source file)

**Interfaces:**
- Produces: `fun currentFrameIndex(frames: List<FrameInfo>, playbackElapsedSeconds: Double): Int` — Task 3's `FrameThumbnailFilmstrip` calls this directly; `GopAnalysisView` is refactored to call it too.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/multiviewer/ui/FrameTypeAnalyzerTest.kt`:

```kotlin
package com.multiviewer.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class FrameTypeAnalyzerTest {
    private val frames = listOf(
        FrameInfo(index = 0, type = 'I', sizeBytes = 1000, ptsSeconds = 0.0),
        FrameInfo(index = 1, type = 'P', sizeBytes = 500, ptsSeconds = 0.04),
        FrameInfo(index = 2, type = 'P', sizeBytes = 400, ptsSeconds = 0.08),
        FrameInfo(index = 3, type = 'P', sizeBytes = 450, ptsSeconds = 0.12),
    )

    @Test
    fun `currentFrameIndex returns -1 before playback has started`() {
        assertEquals(-1, currentFrameIndex(frames, 0.0))
    }

    @Test
    fun `currentFrameIndex returns the last frame whose pts has passed`() {
        assertEquals(1, currentFrameIndex(frames, 0.06))
    }

    @Test
    fun `currentFrameIndex returns the last frame when playback is past the final pts`() {
        assertEquals(3, currentFrameIndex(frames, 999.0))
    }

    @Test
    fun `currentFrameIndex returns -1 for an empty frame list`() {
        assertEquals(-1, currentFrameIndex(emptyList(), 5.0))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test --tests "com.multiviewer.ui.FrameTypeAnalyzerTest"`
Expected: FAIL — `currentFrameIndex` is unresolved (compile error), since the function doesn't exist yet.

- [ ] **Step 3: Add the function to `FrameTypeAnalyzer.kt`**

In `app/src/main/kotlin/com/multiviewer/ui/FrameTypeAnalyzer.kt`, add after `probeFrameTypes`:

```kotlin

// The frame at the current playback position -- the last frame whose own pts has already passed,
// or -1 before playback has started (playbackElapsedSeconds <= 0.0, the default before the first
// FfmpegVideoPlayer position callback). Shared by GopAnalysisView (its own bar-chart highlight/
// auto-scroll) and FrameThumbnailFilmstrip (same behavior for its thumbnail cells) so both views
// track the same frame during playback without duplicating this lookup.
fun currentFrameIndex(frames: List<FrameInfo>, playbackElapsedSeconds: Double): Int =
    if (playbackElapsedSeconds <= 0.0) -1
    else frames.indexOfLast { it.ptsSeconds <= playbackElapsedSeconds }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test --tests "com.multiviewer.ui.FrameTypeAnalyzerTest"`
Expected: PASS (4/4 tests)

- [ ] **Step 5: Refactor `GopAnalysisView.kt` to use the shared function**

Replace lines 134-141 (currently):

```kotlin
                // Highlights and auto-follows the frame at the current playback position (only
                // meaningful once the video has actually started playing, hence the >= 0 guard
                // against the 0.0 default before playback begins). Computed up here too since
                // stepFrame below falls back to it when no frame has been explicitly selected yet.
                val currentFrameIndex = remember(frames, tab.playbackElapsedSeconds) {
                    if (tab.playbackElapsedSeconds <= 0.0) -1
                    else frames.indexOfLast { it.ptsSeconds <= tab.playbackElapsedSeconds }
                }
```

with:

```kotlin
                // Highlights and auto-follows the frame at the current playback position (only
                // meaningful once the video has actually started playing, hence the >= 0 guard
                // against the 0.0 default before playback begins). Computed up here too since
                // stepFrame below falls back to it when no frame has been explicitly selected yet.
                // Shared with FrameThumbnailFilmstrip (see FrameTypeAnalyzer.kt) so both views
                // track the same frame during playback.
                val currentFrameIndex = remember(frames, tab.playbackElapsedSeconds) {
                    currentFrameIndex(frames, tab.playbackElapsedSeconds)
                }
```

(The local `val currentFrameIndex` name shadowing the now-imported top-level `fun currentFrameIndex` inside the `remember` lambda is intentional and resolves correctly in Kotlin — the function call inside the lambda refers to the top-level function since no local `currentFrameIndex` is in scope at that point yet.)

- [ ] **Step 6: Run the full test suite**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test`
Expected: BUILD SUCCESSFUL, no regressions

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/FrameTypeAnalyzer.kt \
        app/src/main/kotlin/com/multiviewer/ui/GopAnalysisView.kt \
        app/src/test/kotlin/com/multiviewer/ui/FrameTypeAnalyzerTest.kt
git commit -m "Extract currentFrameIndex into a shared, tested function"
```

---

### Task 2: Batch thumbnail decoder

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/ui/FrameThumbnailDecoder.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/AppState.kt:177` (add two `TabState` fields after the existing `videoCodecName` field)
- Test: `app/src/test/kotlin/com/multiviewer/ui/FrameThumbnailDecoderTest.kt`

**Interfaces:**
- Produces: `fun missingThumbnailRange(visibleRange: IntRange, prefetchMargin: Int, frameCount: Int, alreadyCachedOrPending: Set<Int>): IntRange?` — pure, Task 3's UI calls this on every visible-range change.
- Produces: `object FrameThumbnailDecoder { fun decodeRangeAsync(file: java.io.File, startIndex: Int, startPtsSeconds: Double, count: Int, onResult: (Map<Int, androidx.compose.ui.graphics.ImageBitmap>) -> Unit) }` — Task 3's UI calls this directly.
- Consumes: `FfmpegLocator.ffmpegPath()` and `FfmpegLocator.configureEnvironment(ProcessBuilder)` (existing, unchanged).

- [ ] **Step 1: Write the failing tests for `missingThumbnailRange`**

Create `app/src/test/kotlin/com/multiviewer/ui/FrameThumbnailDecoderTest.kt`:

```kotlin
package com.multiviewer.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FrameThumbnailDecoderTest {
    @Test
    fun `missingThumbnailRange returns null for an empty visible range`() {
        assertNull(missingThumbnailRange(IntRange.EMPTY, prefetchMargin = 5, frameCount = 100, alreadyCachedOrPending = emptySet()))
    }

    @Test
    fun `missingThumbnailRange returns null when frameCount is zero`() {
        assertNull(missingThumbnailRange(0..10, prefetchMargin = 5, frameCount = 0, alreadyCachedOrPending = emptySet()))
    }

    @Test
    fun `missingThumbnailRange returns the visible range expanded by the prefetch margin when nothing is cached`() {
        assertEquals(15..35, missingThumbnailRange(20..30, prefetchMargin = 5, frameCount = 1000, alreadyCachedOrPending = emptySet()))
    }

    @Test
    fun `missingThumbnailRange returns null when the whole expanded range is already cached`() {
        val cached = (15..35).toSet()
        assertNull(missingThumbnailRange(20..30, prefetchMargin = 5, frameCount = 1000, alreadyCachedOrPending = cached))
    }

    @Test
    fun `missingThumbnailRange clamps against zero at the start of the video`() {
        // visible 5..10 expanded by margin 10 would be -5..20; the negative end clamps to 0.
        assertEquals(0..20, missingThumbnailRange(5..10, prefetchMargin = 10, frameCount = 1000, alreadyCachedOrPending = emptySet()))
    }

    @Test
    fun `missingThumbnailRange clamps against frameCount minus one at the end of the video`() {
        // visible 95..99 expanded by margin 10 would be 85..109; frameCount=100 means valid
        // indices are 0..99, so the end clamps to 99.
        assertEquals(85..99, missingThumbnailRange(95..99, prefetchMargin = 10, frameCount = 100, alreadyCachedOrPending = emptySet()))
    }

    @Test
    fun `missingThumbnailRange spans from the lowest to the highest missing index when partially cached`() {
        // Expanded range is 10..20; only 14 is missing (10-13 and 15-20 already cached) --
        // returns the single-index range 14..14, not the full 10..20 span.
        val cached = (10..20).toSet() - 14
        assertEquals(14..14, missingThumbnailRange(15..15, prefetchMargin = 5, frameCount = 1000, alreadyCachedOrPending = cached))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test --tests "com.multiviewer.ui.FrameThumbnailDecoderTest"`
Expected: FAIL — `missingThumbnailRange` is unresolved (compile error), since `FrameThumbnailDecoder.kt` doesn't exist yet.

- [ ] **Step 3: Create `FrameThumbnailDecoder.kt`**

```kotlin
package com.multiviewer.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image
import java.awt.EventQueue
import java.io.File
import java.util.concurrent.TimeUnit

private const val THUMBNAIL_DECODE_WIDTH_PX = 120
private const val BATCH_TIMEOUT_MS = 15000L

// Pure, unit-testable: decides what (if anything) needs fetching, given the currently-visible
// frame index range and what's already cached or has an in-flight request. Expands the visible
// range by prefetchMargin on both sides for smoother scrolling (thumbnails just off-screen are
// ready before they're scrolled into view), then clamps to the valid [0, frameCount - 1] index
// range. Returns the span from the lowest to the highest MISSING index in that expanded range --
// not necessarily minimal if the missing indices aren't contiguous (may re-request a few already-
// cached frames in between), which keeps this simple: one batch ffmpeg call per trigger rather
// than splitting into multiple sub-ranges around gaps.
fun missingThumbnailRange(
    visibleRange: IntRange,
    prefetchMargin: Int,
    frameCount: Int,
    alreadyCachedOrPending: Set<Int>,
): IntRange? {
    if (visibleRange.isEmpty() || frameCount <= 0) return null
    val expandedFirst = (visibleRange.first - prefetchMargin).coerceIn(0, frameCount - 1)
    val expandedLast = (visibleRange.last + prefetchMargin).coerceIn(0, frameCount - 1)
    val missing = (expandedFirst..expandedLast).filter { it !in alreadyCachedOrPending }
    if (missing.isEmpty()) return null
    return missing.min()..missing.max()
}

// Reuses the same "run ffmpeg -> read output -> Skia decode -> cleanup" shape
// FfmpegImageSnapshotDecoder.decodeSingleFrameToBitmap already establishes, extended to a batch of
// N sequential frames from one accurate-seek point instead of one frame -- verified directly that
// -ss placed AFTER -i (accurate seek) combined with -frames:v <count> produces exactly that many
// sequential frames starting at the seek point, in presentation order, in one ffmpeg call.
object FrameThumbnailDecoder {
    fun decodeRangeAsync(file: File, startIndex: Int, startPtsSeconds: Double, count: Int, onResult: (Map<Int, ImageBitmap>) -> Unit) {
        Thread {
            val bitmaps = decodeRangeToBitmaps(file, startPtsSeconds, count)
            val result = bitmaps.mapIndexed { offset, bitmap -> (startIndex + offset) to bitmap }.toMap()
            EventQueue.invokeLater { onResult(result) }
        }.apply { isDaemon = true }.start()
    }

    // Frames that fail to decode (or fewer output files than requested, e.g. near end of file)
    // are simply absent from the returned list rather than represented as null entries -- the
    // caller's index mapping (decodeRangeAsync above) then naturally omits those indices from the
    // result map instead of caching a null placeholder for them.
    private fun decodeRangeToBitmaps(file: File, startPtsSeconds: Double, count: Int): List<ImageBitmap> {
        val tempDir = try {
            File.createTempFile("frame-thumbnails-", "").apply {
                delete()
                mkdir()
            }
        } catch (e: Exception) {
            return emptyList()
        }
        return try {
            val process = ProcessBuilder(
                FfmpegLocator.ffmpegPath(), "-y", "-i", file.absolutePath,
                "-ss", startPtsSeconds.toString(),
                "-frames:v", count.toString(),
                "-vf", "scale=$THUMBNAIL_DECODE_WIDTH_PX:-1",
                "-vsync", "0",
                File(tempDir, "thumb_%05d.png").absolutePath,
            ).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .also { FfmpegLocator.configureEnvironment(it) }
                .start()
            val finished = process.waitFor(BATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return emptyList()
            }
            tempDir.listFiles { f -> f.name.startsWith("thumb_") }
                ?.sortedBy { it.name }
                ?.mapNotNull { pngFile ->
                    try {
                        Image.makeFromEncoded(pngFile.readBytes()).toComposeImageBitmap()
                    } catch (e: Exception) {
                        null
                    }
                }
                ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test --tests "com.multiviewer.ui.FrameThumbnailDecoderTest"`
Expected: PASS (7/7 tests)

- [ ] **Step 5: Add `TabState` fields**

In `app/src/main/kotlin/com/multiviewer/ui/AppState.kt`, immediately after line 177 (`var videoCodecName: String? by mutableStateOf(null)`), insert:

```kotlin

    // Frame thumbnail filmstrip (see FrameThumbnailDecoder.kt) -- keyed by frame index, populated
    // lazily as the filmstrip scrolls. pendingThumbnailIndices tracks in-flight requests so a
    // rapid double-trigger (e.g. two scroll events before the first batch returns) doesn't launch
    // two overlapping ffmpeg calls covering the same range.
    var thumbnailCache: Map<Int, androidx.compose.ui.graphics.ImageBitmap> by mutableStateOf(emptyMap())
    var pendingThumbnailIndices: Set<Int> by mutableStateOf(emptySet())
```

- [ ] **Step 6: Run the full test suite**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test`
Expected: BUILD SUCCESSFUL, no regressions

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/FrameThumbnailDecoder.kt \
        app/src/main/kotlin/com/multiviewer/ui/AppState.kt \
        app/src/test/kotlin/com/multiviewer/ui/FrameThumbnailDecoderTest.kt
git commit -m "Add batch frame thumbnail decoder"
```

---

### Task 3: Filmstrip UI, wired below the GOP panel

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/ui/FrameThumbnailFilmstrip.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/VideoInspectorUI.kt:81-90` (wrap `GopAnalysisView` with the new filmstrip below it)

**Interfaces:**
- Consumes: `missingThumbnailRange(...)`, `FrameThumbnailDecoder.decodeRangeAsync(...)` (Task 2); `currentFrameIndex(...)` (Task 1).
- Consumes existing: `FrameInfo(index, type, sizeBytes, ptsSeconds)`, `PreviewCaption(text, modifier)`, `AppColors`.
- Produces: `@Composable fun FrameThumbnailFilmstrip(tab: TabState, frames: List<FrameInfo>, modifier: Modifier = Modifier)` — called once from `VideoInspectorUI.kt`.

- [ ] **Step 1: Create `FrameThumbnailFilmstrip.kt`**

```kotlin
package com.multiviewer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

private const val FILMSTRIP_CELL_WIDTH_DP = 120
private const val FILMSTRIP_HEIGHT_DP = 90
private const val FILMSTRIP_PREFETCH_MARGIN = 15

// Real decoded frame thumbnails below GopAnalysisView's bar chart -- see
// docs/superpowers/specs/2026-08-14-frame-thumbnail-filmstrip-design.md. Unlike GifFilmstripPlayer
// (which preloads every frame up front, viable only because GIF frame counts are small), this
// lazily batch-decodes only the currently-visible range (plus a prefetch margin) as the LazyRow
// scrolls, via FrameThumbnailDecoder -- safe for videos with thousands of frames. Not
// horizontally synchronized with GopAnalysisView's own scroll/zoom -- an independent LazyRow, by
// design (see spec's Out of scope section).
@Composable
fun FrameThumbnailFilmstrip(tab: TabState, frames: List<FrameInfo>, modifier: Modifier = Modifier) {
    if (frames.isEmpty()) return

    val listState = rememberLazyListState()

    LaunchedEffect(listState, frames, tab.file) {
        snapshotFlow {
            val visible = listState.layoutInfo.visibleItemsInfo
            if (visible.isEmpty()) IntRange.EMPTY else visible.first().index..visible.last().index
        }.collect { visibleRange ->
            val alreadyCachedOrPending = tab.thumbnailCache.keys + tab.pendingThumbnailIndices
            val range = missingThumbnailRange(visibleRange, FILMSTRIP_PREFETCH_MARGIN, frames.size, alreadyCachedOrPending)
                ?: return@collect
            tab.pendingThumbnailIndices = tab.pendingThumbnailIndices + range
            FrameThumbnailDecoder.decodeRangeAsync(
                tab.file, range.first, frames[range.first].ptsSeconds, range.last - range.first + 1,
            ) { decoded ->
                tab.thumbnailCache = tab.thumbnailCache + decoded
                tab.pendingThumbnailIndices = tab.pendingThumbnailIndices - range
            }
        }
    }

    val currentIndex = currentFrameIndex(frames, tab.playbackElapsedSeconds)
    LaunchedEffect(currentIndex) {
        if (currentIndex < 0) return@LaunchedEffect
        val isVisible = listState.layoutInfo.visibleItemsInfo.any { it.index == currentIndex }
        if (!isVisible) listState.animateScrollToItem(currentIndex)
    }

    LazyRow(
        state = listState,
        modifier = modifier.fillMaxWidth().height(FILMSTRIP_HEIGHT_DP.dp).background(Color.Black),
    ) {
        itemsIndexed(frames) { index, frame ->
            val bitmap = tab.thumbnailCache[index]
            val isCurrent = index == currentIndex
            Box(
                modifier = Modifier
                    .width(FILMSTRIP_CELL_WIDTH_DP.dp)
                    .fillMaxHeight()
                    .padding(1.dp)
                    .let { if (isCurrent) it.border(2.dp, Color.White) else it }
                    .clickable {
                        tab.selectedFrame = frame
                        tab.selected = null
                        tab.seekTargetSeconds = frame.ptsSeconds
                        tab.seekRequestTick++
                    },
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        modifier = Modifier.fillMaxHeight().fillMaxWidth(),
                        contentScale = ContentScale.Fit,
                    )
                }
                PreviewCaption(
                    "#${frame.index} ${frame.type}",
                    modifier = Modifier.align(Alignment.TopStart).padding(2.dp),
                )
            }
        }
    }
}
```

- [ ] **Step 2: Wire into `VideoInspectorUI.kt`**

Replace lines 81-90 (currently):

```kotlin
                    // Right: GOP Analysis (full height of the top region). The codec-view preview
                    // (CodecViewPreview.kt -- motion vectors / QP heatmap) is NOT shown here -- it
                    // renders beside the Hex & Raw Data Viewer instead (see Main.kt's bottomPanel),
                    // reusing the empty space to the right of the hex byte grid rather than
                    // shrinking this already vertically-limited GOP column further.
                    GopAnalysisView(
                        tab,
                        onAnalyze = { appState.analyzeFrames(tab) },
                        modifier = Modifier.weight(1f - videoGopSplit).fillMaxHeight(),
                    )
```

with:

```kotlin
                    // Right: GOP Analysis (top) + frame thumbnail filmstrip (bottom, fixed height
                    // -- not a resizable split, per the filmstrip's own design). The codec-view
                    // preview (CodecViewPreview.kt -- motion vectors / QP heatmap) is NOT shown
                    // here -- it renders beside the Hex & Raw Data Viewer instead (see Main.kt's
                    // bottomPanel), reusing the empty space to the right of the hex byte grid
                    // rather than shrinking this already vertically-limited column further.
                    Column(modifier = Modifier.weight(1f - videoGopSplit).fillMaxHeight()) {
                        GopAnalysisView(
                            tab,
                            onAnalyze = { appState.analyzeFrames(tab) },
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                        tab.gopFrames?.takeIf { it.isNotEmpty() }?.let { frames ->
                            FrameThumbnailFilmstrip(tab, frames, modifier = Modifier.fillMaxWidth())
                        }
                    }
```

`Column` is already available via this file's `androidx.compose.foundation.layout.*` wildcard import — no new import needed.

- [ ] **Step 3: Compile**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Run the full test suite**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test`
Expected: BUILD SUCCESSFUL, no regressions

- [ ] **Step 5: Manual verification**

Launch the app (`./gradlew :app:run`), open a video, click "프레임 분석 시작" in the GOP panel, confirm:
- A thumbnail filmstrip appears below the GOP bar chart once frame analysis completes.
- Thumbnails populate progressively as you scroll (not all at once) — scrolling far ahead shows placeholders briefly, then real thumbnails.
- Scrolling back to an already-visited range shows thumbnails immediately (cached, no re-decode flicker).
- Playing the video moves a highlighted cell along the filmstrip, matching the GOP bar chart's own current-frame highlight directly above it.
- Each cell's label (`#<index> <type>`) matches the frame directly above it in the bar chart.
- Clicking a thumbnail selects that frame (same as clicking its bar) — hex highlight/codec-view panel (if active) update accordingly.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/FrameThumbnailFilmstrip.kt \
        app/src/main/kotlin/com/multiviewer/ui/VideoInspectorUI.kt
git commit -m "Add frame thumbnail filmstrip below the GOP panel"
```
