# Audio Waveform/Spectrogram Zoom & Pan Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the waveform and spectrogram panels in `FfmpegAudioPlayer` zoom in/out and pan left/right over the track's timeline, with a minimap that always shows the full track, the current zoom window, and the playhead.

**Architecture:** A shared `AudioViewWindow(startSeconds, durationSeconds)` state (new `AudioZoomPan.kt`) drives both panels. The waveform re-slices its already-computed 4096-bucket array to the visible window (no recomputation). The spectrogram is re-rendered by ffmpeg for just the visible window (`-ss`/`-t` input-side trim, same pattern as the raw-PCM input flags). Mouse wheel zooms, trackpad horizontal scroll and a new draggable scrollbar pan, and a new minimap composable shows the whole track with a draggable window indicator and click-to-seek.

**Tech Stack:** Kotlin, Compose Multiplatform Desktop, ffmpeg (subprocess), `kotlin.test`.

## Global Constraints

- Waveform and spectrogram always share one `AudioViewWindow` -- never independently zoomed/panned.
- Minimum visible window: `MIN_VISIBLE_DURATION_SECONDS = 0.5` seconds. Maximum: the full track duration (today's behavior, unchanged when never zoomed).
- Zoom: mouse wheel / trackpad vertical scroll over either panel. Pan: trackpad horizontal scroll over either panel, plus a new draggable horizontal scrollbar beneath each panel.
- The existing click-drag-to-seek gesture on the waveform/spectrogram panels is untouched and keeps operating on the whole track's real time, independent of the current zoom window.
- The minimap always spans the full track (`0..info.duration`), shows a draggable rectangle for the current zoom window and a playhead marker, and click-anywhere seeks (whole-track, unaffected by zoom).
- Zoom/pan state resets to fully-zoomed-out (`AudioViewWindow(0.0, info.duration)`) whenever `file` changes.
- Spec reference: `docs/superpowers/specs/2026-08-02-audio-waveform-spectrogram-zoom-pan-design.md`.

---

## Task 1: Shared zoom/pan window state + waveform windowed drawing

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/ui/AudioZoomPan.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/AudioWaveformPeaks.kt`
- Test: `app/src/test/kotlin/com/multiviewer/ui/AudioZoomPanTest.kt` (new)
- Test: `app/src/test/kotlin/com/multiviewer/ui/AudioWaveformPeaksTest.kt` (append)

**Interfaces:**
- Produces (package `com.multiviewer.ui`, consumed by Tasks 2-4):
  ```kotlin
  const val MIN_VISIBLE_DURATION_SECONDS = 0.5
  data class AudioViewWindow(val startSeconds: Double, val durationSeconds: Double)
  fun clampWindow(requestedStart: Double, requestedDuration: Double, totalDuration: Double): AudioViewWindow
  fun visibleBucketRange(window: AudioViewWindow, totalDuration: Double, bucketCount: Int): IntRange
  ```
  `WaveformDisplay`'s signature changes (see Step 4) -- its one existing call site (`FfmpegAudioPlayer.kt`) is updated in Task 3, not here; this task's own compile-check step accepts that `FfmpegAudioPlayer.kt` will fail to compile until Task 3 -- see Step 6.

- [ ] **Step 1: Write the failing test for `clampWindow`**

Create `app/src/test/kotlin/com/multiviewer/ui/AudioZoomPanTest.kt`:

```kotlin
package com.multiviewer.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class AudioZoomPanTest {
    @Test
    fun `clampWindow leaves an already-valid window unchanged`() {
        val window = clampWindow(requestedStart = 10.0, requestedDuration = 5.0, totalDuration = 60.0)
        assertEquals(AudioViewWindow(10.0, 5.0), window)
    }

    @Test
    fun `clampWindow enforces the minimum duration`() {
        val window = clampWindow(requestedStart = 10.0, requestedDuration = 0.1, totalDuration = 60.0)
        assertEquals(MIN_VISIBLE_DURATION_SECONDS, window.durationSeconds)
    }

    @Test
    fun `clampWindow caps duration at the total track length`() {
        val window = clampWindow(requestedStart = 0.0, requestedDuration = 999.0, totalDuration = 60.0)
        assertEquals(60.0, window.durationSeconds)
    }

    @Test
    fun `clampWindow prevents the window from extending past the end of the track`() {
        val window = clampWindow(requestedStart = 58.0, requestedDuration = 10.0, totalDuration = 60.0)
        assertEquals(50.0, window.startSeconds)
        assertEquals(10.0, window.durationSeconds)
    }

    @Test
    fun `clampWindow prevents a negative start`() {
        val window = clampWindow(requestedStart = -5.0, requestedDuration = 10.0, totalDuration = 60.0)
        assertEquals(0.0, window.startSeconds)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test --tests "com.multiviewer.ui.AudioZoomPanTest"`
Expected: compile failure -- `clampWindow`/`AudioViewWindow`/`MIN_VISIBLE_DURATION_SECONDS` are unresolved references.

- [ ] **Step 3: Create `AudioZoomPan.kt`**

Create `app/src/main/kotlin/com/multiviewer/ui/AudioZoomPan.kt`:

```kotlin
package com.multiviewer.ui

// The narrowest time range zoom can show -- prevents a degenerate zero-width (or inverted)
// window, which would make both the waveform's bucket range and the spectrogram's ffmpeg -t
// argument meaningless.
const val MIN_VISIBLE_DURATION_SECONDS = 0.5

// The waveform, spectrogram, minimap, and scrollbar all share one of these: what time range is
// currently shown in the (non-minimap) detail panels. durationSeconds == totalDuration means
// fully zoomed out (today's pre-zoom-feature behavior).
data class AudioViewWindow(val startSeconds: Double, val durationSeconds: Double)

// Single source of truth for keeping a requested window valid: duration is clamped to
// [MIN_VISIBLE_DURATION_SECONDS, totalDuration], then start is clamped so the window never
// extends past either end of the track. Re-clamping start after duration keeps the window valid
// even when duration grows back toward totalDuration (e.g. zooming back out from a window whose
// start would otherwise no longer fit).
fun clampWindow(requestedStart: Double, requestedDuration: Double, totalDuration: Double): AudioViewWindow {
    val safeTotal = totalDuration.coerceAtLeast(MIN_VISIBLE_DURATION_SECONDS)
    val duration = requestedDuration.coerceIn(MIN_VISIBLE_DURATION_SECONDS, safeTotal)
    val start = requestedStart.coerceIn(0.0, (totalDuration - duration).coerceAtLeast(0.0))
    return AudioViewWindow(start, duration)
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test --tests "com.multiviewer.ui.AudioZoomPanTest"`
Expected: all 5 tests PASS.

- [ ] **Step 5: Add `visibleBucketRange` and windowed drawing to `AudioWaveformPeaks.kt`**

In `app/src/main/kotlin/com/multiviewer/ui/AudioWaveformPeaks.kt`, find:

```kotlin
// Far more buckets than any realistic panel width in pixels -- the Canvas renderer just redraws
// the same bucket values at new pixel positions on resize, no recomputation ever needed.
const val WAVEFORM_PEAK_BUCKET_COUNT = 4096
```

Replace with:

```kotlin
// Far more buckets than any realistic panel width in pixels -- the Canvas renderer just redraws
// the same bucket values at new pixel positions on resize, no recomputation ever needed.
const val WAVEFORM_PEAK_BUCKET_COUNT = 4096

// Maps a time window onto an index range within a bucket array of the given size -- since
// computeWaveformPeaks already spaces its buckets evenly across the whole file duration, this is
// pure arithmetic, no new peak computation needed to redraw a zoomed-in sub-range.
fun visibleBucketRange(window: AudioViewWindow, totalDuration: Double, bucketCount: Int): IntRange {
    if (totalDuration <= 0.0 || bucketCount <= 0) return 0..0
    val startFraction = (window.startSeconds / totalDuration).coerceIn(0.0, 1.0)
    val endFraction = ((window.startSeconds + window.durationSeconds) / totalDuration).coerceIn(0.0, 1.0)
    val startBucket = (startFraction * bucketCount).toInt().coerceIn(0, bucketCount - 1)
    val endBucket = (endFraction * bucketCount).toInt().coerceIn(startBucket + 1, bucketCount)
    return startBucket until endBucket
}
```

Then find:

```kotlin
@Composable
fun WaveformDisplay(peaks: WaveformPeaks, color: Color, modifier: Modifier = Modifier) {
    val displayChannels = peaks.channels.take(2)
    if (displayChannels.size >= 2) {
        Column(modifier = modifier) {
            WaveformChannelCanvas(displayChannels[0], color, Modifier.weight(1f).fillMaxWidth())
            WaveformChannelCanvas(displayChannels[1], color, Modifier.weight(1f).fillMaxWidth())
        }
    } else if (displayChannels.size == 1) {
        WaveformChannelCanvas(displayChannels[0], color, modifier.fillMaxSize())
    }
}

@Composable
private fun WaveformChannelCanvas(peaks: ChannelPeaks, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        drawChannelPeaks(peaks, color)
    }
}

private fun DrawScope.drawChannelPeaks(peaks: ChannelPeaks, color: Color) {
    val width = size.width
    val height = size.height
    val centerY = height / 2f
    val bucketCount = peaks.min.size
    if (bucketCount == 0 || width <= 0f) return
    val strokeWidthPx = 1.5.dp.toPx()
    for (i in 0 until bucketCount) {
        val x = width * i / bucketCount
        val yTop = centerY - peaks.max[i] * centerY
        val yBottom = centerY - peaks.min[i] * centerY
        drawLine(color = color, start = Offset(x, yTop), end = Offset(x, yBottom), strokeWidth = strokeWidthPx)
    }
}
```

Replace with:

```kotlin
@Composable
fun WaveformDisplay(peaks: WaveformPeaks, color: Color, visibleRange: IntRange, modifier: Modifier = Modifier) {
    val displayChannels = peaks.channels.take(2)
    if (displayChannels.size >= 2) {
        Column(modifier = modifier) {
            WaveformChannelCanvas(displayChannels[0], color, visibleRange, Modifier.weight(1f).fillMaxWidth())
            WaveformChannelCanvas(displayChannels[1], color, visibleRange, Modifier.weight(1f).fillMaxWidth())
        }
    } else if (displayChannels.size == 1) {
        WaveformChannelCanvas(displayChannels[0], color, visibleRange, modifier.fillMaxSize())
    }
}

@Composable
private fun WaveformChannelCanvas(peaks: ChannelPeaks, color: Color, visibleRange: IntRange, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        drawChannelPeaks(peaks, color, visibleRange)
    }
}

private fun DrawScope.drawChannelPeaks(peaks: ChannelPeaks, color: Color, visibleRange: IntRange) {
    val width = size.width
    val height = size.height
    val centerY = height / 2f
    val startBucket = visibleRange.first
    val visibleCount = visibleRange.last - visibleRange.first + 1
    if (visibleCount <= 0 || width <= 0f) return
    val strokeWidthPx = 1.5.dp.toPx()
    for (i in visibleRange) {
        val x = width * (i - startBucket) / visibleCount
        val yTop = centerY - peaks.max[i] * centerY
        val yBottom = centerY - peaks.min[i] * centerY
        drawLine(color = color, start = Offset(x, yTop), end = Offset(x, yBottom), strokeWidth = strokeWidthPx)
    }
}
```

- [ ] **Step 6: Add `visibleBucketRange` tests and compile-check**

Append to `app/src/test/kotlin/com/multiviewer/ui/AudioWaveformPeaksTest.kt`, inside the existing `class AudioWaveformPeaksTest { ... }` body (add these two methods alongside the existing ones, before the closing `}`):

```kotlin
    @Test
    fun `visibleBucketRange covers the whole array when the window spans the full duration`() {
        val range = visibleBucketRange(AudioViewWindow(0.0, 60.0), totalDuration = 60.0, bucketCount = 4096)
        assertEquals(0, range.first)
        assertEquals(4095, range.last)
    }

    @Test
    fun `visibleBucketRange narrows to the middle of the array for a zoomed-in window`() {
        val range = visibleBucketRange(AudioViewWindow(20.0, 20.0), totalDuration = 60.0, bucketCount = 4096)
        assertEquals((4096 / 3), range.first)
        assertEquals((4096 * 2 / 3) - 1, range.last)
    }

    @Test
    fun `visibleBucketRange never returns an empty or inverted range`() {
        val range = visibleBucketRange(AudioViewWindow(59.9, MIN_VISIBLE_DURATION_SECONDS), totalDuration = 60.0, bucketCount = 4096)
        assertTrue(range.last >= range.first)
    }
}
```

This project's `compileKotlin` will fail after this step, because `WaveformDisplay`'s new required `visibleRange` parameter breaks its one existing call site in `FfmpegAudioPlayer.kt` -- that call site is fixed in Task 3, not here. Run only the test suite for now:

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test --tests "com.multiviewer.ui.AudioZoomPanTest" --tests "com.multiviewer.ui.AudioWaveformPeaksTest"`
Expected: BUILD SUCCESSFUL if these two test classes alone compile and pass -- but note Gradle compiles the *whole* module before running any test, so this command will actually fail to compile at this point in the plan (same `WaveformDisplay` call-site mismatch). This is expected and acceptable: Task 1 is intentionally left in a temporarily-non-compiling state, completed by Task 3. Do not attempt to fix `FfmpegAudioPlayer.kt` here -- that is out of this task's scope. If the test run fails with a compile error naming `FfmpegAudioPlayer.kt`'s `WaveformDisplay(peaks = peaks, color = ...)` call, that confirms the expected state; report DONE_WITH_CONCERNS noting this, not BLOCKED.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/AudioZoomPan.kt app/src/main/kotlin/com/multiviewer/ui/AudioWaveformPeaks.kt app/src/test/kotlin/com/multiviewer/ui/AudioZoomPanTest.kt app/src/test/kotlin/com/multiviewer/ui/AudioWaveformPeaksTest.kt
git commit -m "feat: add shared zoom/pan window state and windowed waveform drawing"
```

---

## Task 2: Spectrogram windowed regeneration

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/FfmpegAudioPlayer.kt`
- Test: `app/src/test/kotlin/com/multiviewer/ui/FfmpegAudioPlayerTest.kt` (append)

**Interfaces:**
- Consumes (from Task 1): `AudioViewWindow`.
- Produces (consumed by Task 3): `generateSpectrogramImage(file, width, height, rawAudioParams = null, window: AudioViewWindow? = null)`, `renderAudioVisualization(file, filter, rawAudioParams = null, window: AudioViewWindow? = null)` (both new `window` params optional, default `null` preserves all existing behavior/call sites exactly).

- [ ] **Step 1: Write the failing test**

Append to `app/src/test/kotlin/com/multiviewer/ui/FfmpegAudioPlayerTest.kt`, inside `class FfmpegAudioPlayerTest { ... }`, before the closing `}`:

```kotlin
    @Test
    fun `generateSpectrogramImage honors a windowed time range and still returns the requested dimensions`() {
        val audio = File.createTempFile("ffmpeg-spectrogram-window-test-", ".wav")
        audio.deleteOnExit()
        ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=4",
            audio.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()

        val window = AudioViewWindow(startSeconds = 1.0, durationSeconds = 2.0)
        val bitmap = generateSpectrogramImage(audio, 300, 80, window = window)

        assertNotNull(bitmap)
        assertEquals(300, bitmap.width)
        assertEquals(80, bitmap.height)
        audio.delete()
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test --tests "com.multiviewer.ui.FfmpegAudioPlayerTest"`
Expected: compile failure -- `generateSpectrogramImage(audio, 300, 80, window = window)` has no `window` parameter yet. (This run will also still hit Task 1's known `WaveformDisplay` call-site failure in the same file -- expected, not a new problem.)

- [ ] **Step 3: Add the `window` parameter to `renderAudioVisualization`/`generateSpectrogramImage`**

In `app/src/main/kotlin/com/multiviewer/ui/FfmpegAudioPlayer.kt`, find:

```kotlin
private fun renderAudioVisualization(file: File, filter: String, rawAudioParams: RawAudioParams? = null): ImageBitmap? {
    val tempPng = try {
        File.createTempFile("audio-visual-", ".png")
    } catch (e: Exception) {
        return null
    }
    tempPng.deleteOnExit()
    var inputFile: File? = null
    return try {
        val resolvedInputFile = if (rawAudioParams != null) rawAudioSourceFile(file, rawAudioParams.offsetBytes) else file
        inputFile = resolvedInputFile
        val rawInputArgs = if (rawAudioParams != null) {
            listOf("-f", rawAudioParams.ffmpegFormatCode(), "-ar", rawAudioParams.sampleRate.toString(), "-ac", rawAudioParams.channels.toString())
        } else {
            emptyList()
        }
        val process = ProcessBuilder(
            listOf(FfmpegLocator.ffmpegPath(), "-y") + rawInputArgs + listOf(
                "-i", resolvedInputFile.absolutePath,
                "-lavfi", filter, "-frames:v", "1", tempPng.absolutePath,
            ),
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD)
            .also { FfmpegLocator.configureEnvironment(it) }.start()
        val finished = process.waitFor(AUDIO_VISUAL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            null
        } else if (process.exitValue() != 0 || tempPng.length() == 0L) {
            null
        } else {
            Image.makeFromEncoded(tempPng.readBytes()).toComposeImageBitmap()
        }
    } catch (e: Exception) {
        null
    } finally {
        tempPng.delete()
        val fileToClean = inputFile
        if (fileToClean != null && fileToClean != file) fileToClean.delete()
    }
}

// showspectrumpic doesn't honor its own s=WxH request precisely (measured: requesting 1200x300
// actually renders 1482x428) -- scale=W:H (no aspect-ratio preservation) forces the exact
// requested dimensions by stretching rather than letterboxing/pillarboxing, so the spectrogram
// content fills the image edge-to-edge with no black padding bars. This matters because the
// progress overlay assumes "image width == full duration" linearly; padding here would make the
// playhead visually misalign with the actual spectrogram content near both edges.
fun generateSpectrogramImage(file: File, width: Int, height: Int, rawAudioParams: RawAudioParams? = null): ImageBitmap? =
    renderAudioVisualization(file, "showspectrumpic=s=${width}x${height},scale=${width}:${height}", rawAudioParams)
```

Replace with:

```kotlin
private fun renderAudioVisualization(
    file: File,
    filter: String,
    rawAudioParams: RawAudioParams? = null,
    window: AudioViewWindow? = null,
): ImageBitmap? {
    val tempPng = try {
        File.createTempFile("audio-visual-", ".png")
    } catch (e: Exception) {
        return null
    }
    tempPng.deleteOnExit()
    var inputFile: File? = null
    return try {
        val resolvedInputFile = if (rawAudioParams != null) rawAudioSourceFile(file, rawAudioParams.offsetBytes) else file
        inputFile = resolvedInputFile
        val rawInputArgs = if (rawAudioParams != null) {
            listOf("-f", rawAudioParams.ffmpegFormatCode(), "-ar", rawAudioParams.sampleRate.toString(), "-ac", rawAudioParams.channels.toString())
        } else {
            emptyList()
        }
        // Trims the SOURCE to just the visible zoom window before ffmpeg ever sees the rest of the
        // file, rather than rendering the whole spectrum and cropping the image -- this is what
        // makes zooming in reveal genuinely more spectral detail instead of a blurrier crop of the
        // same fixed-resolution picture. Both -ss and -t are input-side flags (must precede -i to
        // trim the input rather than the output), same convention as the raw-PCM input flags above.
        val windowArgs = if (window != null) {
            listOf("-ss", window.startSeconds.toString(), "-t", window.durationSeconds.toString())
        } else {
            emptyList()
        }
        val process = ProcessBuilder(
            listOf(FfmpegLocator.ffmpegPath(), "-y") + rawInputArgs + windowArgs + listOf(
                "-i", resolvedInputFile.absolutePath,
                "-lavfi", filter, "-frames:v", "1", tempPng.absolutePath,
            ),
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD)
            .also { FfmpegLocator.configureEnvironment(it) }.start()
        val finished = process.waitFor(AUDIO_VISUAL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            null
        } else if (process.exitValue() != 0 || tempPng.length() == 0L) {
            null
        } else {
            Image.makeFromEncoded(tempPng.readBytes()).toComposeImageBitmap()
        }
    } catch (e: Exception) {
        null
    } finally {
        tempPng.delete()
        val fileToClean = inputFile
        if (fileToClean != null && fileToClean != file) fileToClean.delete()
    }
}

// showspectrumpic doesn't honor its own s=WxH request precisely (measured: requesting 1200x300
// actually renders 1482x428) -- scale=W:H (no aspect-ratio preservation) forces the exact
// requested dimensions by stretching rather than letterboxing/pillarboxing, so the spectrogram
// content fills the image edge-to-edge with no black padding bars. This matters because the
// progress overlay assumes "image width == full duration" linearly; padding here would make the
// playhead visually misalign with the actual spectrogram content near both edges.
fun generateSpectrogramImage(
    file: File,
    width: Int,
    height: Int,
    rawAudioParams: RawAudioParams? = null,
    window: AudioViewWindow? = null,
): ImageBitmap? =
    renderAudioVisualization(file, "showspectrumpic=s=${width}x${height},scale=${width}:${height}", rawAudioParams, window)
```

- [ ] **Step 4: Run the new test to verify it passes**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test --tests "com.multiviewer.ui.FfmpegAudioPlayerTest"`
Expected: still a compile failure from Task 1's known-pending `WaveformDisplay` call-site mismatch (same file) -- this is expected, not a new problem introduced by this task. Confirm by reading the compiler error: it should name only the `WaveformDisplay(peaks = peaks, color = ...)` call (missing `visibleRange` argument), not anything related to `generateSpectrogramImage`/`renderAudioVisualization`/`window`. Report DONE_WITH_CONCERNS noting this expected state, not BLOCKED -- Task 3 resolves it.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/FfmpegAudioPlayer.kt app/src/test/kotlin/com/multiviewer/ui/FfmpegAudioPlayerTest.kt
git commit -m "feat: regenerate the spectrogram for the visible zoom window instead of the whole file"
```

---

## Task 3: Zoom/pan interaction wiring in `FfmpegAudioPlayer`

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/FfmpegAudioPlayer.kt`

**Interfaces:**
- Consumes (from Tasks 1-2): `AudioViewWindow`, `clampWindow`, `visibleBucketRange`, `WaveformDisplay(peaks, color, visibleRange, modifier)`, `generateSpectrogramImage(file, width, height, rawAudioParams, window)`.
- Produces (consumed by Task 4): a `visibleWindow: AudioViewWindow` state and its setter, both readable by the minimap this task adds the call site for (Task 4 supplies the composable itself).

This task finally makes the whole project compile again -- it's the one that fixes `WaveformDisplay`'s call site.

- [ ] **Step 1: Add imports**

In `app/src/main/kotlin/com/multiviewer/ui/FfmpegAudioPlayer.kt`, find:

```kotlin
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
```

Replace with:

```kotlin
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
```

- [ ] **Step 2: Add zoom/pan constants and `@OptIn`**

Find:

```kotlin
private const val SPECTROGRAM_RESIZE_DEBOUNCE_MS = 400L

@Composable
fun FfmpegAudioPlayer(file: File, rawAudioParams: RawAudioParams? = null, modifier: Modifier = Modifier) {
```

Replace with:

```kotlin
private const val SPECTROGRAM_RESIZE_DEBOUNCE_MS = 400L

// Both scale with the CURRENT visible duration rather than being a fixed number of seconds per
// scroll unit, so zoom/pan feel consistent whether the view is showing the whole track or one
// second of it -- a fixed-seconds step would feel glacial zoomed out and twitchy zoomed in.
private const val ZOOM_STEP_FACTOR = 0.08
private const val PAN_STEP_FACTOR = 0.05

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun FfmpegAudioPlayer(file: File, rawAudioParams: RawAudioParams? = null, modifier: Modifier = Modifier) {
```

- [ ] **Step 3: Add `visibleWindow` state and the zoom/pan scroll handler**

Find:

```kotlin
    var waveformSplit by remember(file) { mutableStateOf(0.6f) }
    var containerHeightPx by remember(file) { mutableStateOf(0) }
    var spectrogramBoxSize by remember(file) { mutableStateOf(IntSize.Zero) }
    val elapsedSeconds = (startFromSeconds + playedSeconds).coerceIn(0.0, if (info.duration > 0) info.duration else Double.MAX_VALUE)

    // Regenerates the spectrogram at the panel's actual current pixel size, debounced so a drag
    // resize doesn't spawn ffmpeg on every intermediate frame -- LaunchedEffect's key-change
    // semantics cancel the previous coroutine and start a fresh one on every size change, so only
    // the last size that survives the delay without being superseded actually triggers a
    // regeneration. The old bitmap (if any) stays visible via contentScale = FillBounds until the
    // new one is ready, so there's no flicker or blank flash mid-resize.
    LaunchedEffect(file, spectrogramBoxSize) {
        val boxSize = spectrogramBoxSize
        if (boxSize.width <= 0 || boxSize.height <= 0) return@LaunchedEffect
        delay(SPECTROGRAM_RESIZE_DEBOUNCE_MS)
        val newBitmap = withContext(Dispatchers.IO) {
            generateSpectrogramImage(file, boxSize.width, boxSize.height, rawAudioParams = rawAudioParams)
        }
        if (newBitmap != null) spectrogramBitmap = newBitmap
    }

    fun seekToFraction(fraction: Float) {
        hasEnded = false
        isPlaying = false
        startFromSeconds = fraction.coerceIn(0f, 1f) * info.duration
        restartTrigger++
    }
```

Replace with:

```kotlin
    var waveformSplit by remember(file) { mutableStateOf(0.6f) }
    var containerHeightPx by remember(file) { mutableStateOf(0) }
    var spectrogramBoxSize by remember(file) { mutableStateOf(IntSize.Zero) }
    val elapsedSeconds = (startFromSeconds + playedSeconds).coerceIn(0.0, if (info.duration > 0) info.duration else Double.MAX_VALUE)

    // Shared by the waveform, spectrogram, scrollbar, and minimap -- fully zoomed out (the whole
    // track) whenever a new file loads, same as today's pre-zoom behavior.
    var visibleWindow by remember(file) { mutableStateOf(AudioViewWindow(0.0, info.duration)) }

    fun applyZoomOrPan(scrollDeltaX: Float, scrollDeltaY: Float) {
        visibleWindow = if (scrollDeltaX != 0f) {
            // Two-finger trackpad horizontal scroll -- pan.
            clampWindow(
                visibleWindow.startSeconds + scrollDeltaX.toDouble() * PAN_STEP_FACTOR * visibleWindow.durationSeconds,
                visibleWindow.durationSeconds,
                info.duration,
            )
        } else {
            // Mouse wheel / trackpad vertical scroll -- zoom. Compose reports a NEGATIVE
            // scrollDelta.y when scrolling up/away (confirmed by GopAnalysisView's own existing
            // zoom code and comment), and scroll-up conventionally means "zoom in" -- zooming in
            // means a SMALLER visible duration (narrower time window), the opposite relationship
            // GopAnalysisView has (there, scroll-up grows a pixel width). So the sign here is
            // deliberately `+`, not `-`: factor = 1.0 + scrollDeltaY * ZOOM_STEP_FACTOR gives
            // factor < 1 (duration shrinks) when scrollDeltaY is negative (scroll up), and
            // factor > 1 (duration grows) when scrolling down.
            clampWindow(
                visibleWindow.startSeconds,
                visibleWindow.durationSeconds * (1.0 + scrollDeltaY.toDouble() * ZOOM_STEP_FACTOR),
                info.duration,
            )
        }
    }

    // Regenerates the spectrogram for the current visible window at the panel's actual pixel
    // size, debounced so a drag resize or a burst of zoom/pan scroll events doesn't spawn ffmpeg
    // on every intermediate frame -- LaunchedEffect's key-change semantics cancel the previous
    // coroutine and start a fresh one on every change, so only the last window/size that survives
    // the delay without being superseded actually triggers a regeneration. The old bitmap (if
    // any) stays visible via contentScale = FillBounds until the new one is ready, so there's no
    // flicker or blank flash mid-resize or mid-zoom.
    LaunchedEffect(file, spectrogramBoxSize, visibleWindow) {
        val boxSize = spectrogramBoxSize
        if (boxSize.width <= 0 || boxSize.height <= 0) return@LaunchedEffect
        delay(SPECTROGRAM_RESIZE_DEBOUNCE_MS)
        val newBitmap = withContext(Dispatchers.IO) {
            generateSpectrogramImage(file, boxSize.width, boxSize.height, rawAudioParams = rawAudioParams, window = visibleWindow)
        }
        if (newBitmap != null) spectrogramBitmap = newBitmap
    }

    fun seekToFraction(fraction: Float) {
        hasEnded = false
        isPlaying = false
        startFromSeconds = fraction.coerceIn(0f, 1f) * info.duration
        restartTrigger++
    }
```

- [ ] **Step 4: Wire the scroll handler and windowed waveform into the waveform `Box`**

Find:

```kotlin
        Box(
            modifier = Modifier
                .weight(waveformSplit)
                .fillMaxWidth()
                .background(Color.Black)
                .pointerInput(info.duration) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        seekToFraction(down.position.x / size.width.toFloat())
                        drag(down.id) { change ->
                            change.consume()
                            seekToFraction(change.position.x / size.width.toFloat())
                        }
                    }
                },
        ) {
            val peaks = waveformPeaks
            if (loadError) {
                Text("Could not start ffmpeg playback", color = Color.White, modifier = Modifier.align(Alignment.Center))
            } else if (peaks != null) {
                WaveformDisplay(peaks = peaks, color = Color(0xFF39FF14), modifier = Modifier.fillMaxSize())
            } else {
                DecodingIndicator("파형 생성 중...", modifier = Modifier.align(Alignment.Center))
            }
```

Replace with:

```kotlin
        Box(
            modifier = Modifier
                .weight(waveformSplit)
                .fillMaxWidth()
                .background(Color.Black)
                .onPointerEvent(PointerEventType.Scroll, pass = PointerEventPass.Initial) { event ->
                    val delta = event.changes.firstOrNull()?.scrollDelta ?: return@onPointerEvent
                    applyZoomOrPan(delta.x, delta.y)
                    event.changes.forEach { it.consume() }
                }
                .pointerInput(info.duration) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        seekToFraction(down.position.x / size.width.toFloat())
                        drag(down.id) { change ->
                            change.consume()
                            seekToFraction(change.position.x / size.width.toFloat())
                        }
                    }
                },
        ) {
            val peaks = waveformPeaks
            if (loadError) {
                Text("Could not start ffmpeg playback", color = Color.White, modifier = Modifier.align(Alignment.Center))
            } else if (peaks != null) {
                val visibleRange = visibleBucketRange(visibleWindow, info.duration, peaks.bucketCount)
                WaveformDisplay(peaks = peaks, color = Color(0xFF39FF14), visibleRange = visibleRange, modifier = Modifier.fillMaxSize())
            } else {
                DecodingIndicator("파형 생성 중...", modifier = Modifier.align(Alignment.Center))
            }
```

- [ ] **Step 5: Make the waveform progress fill relative to the visible window, add a scrollbar**

Find (this is the waveform box's progress overlay -- the FIRST of the two identical-looking occurrences in the file, immediately followed by the play/pause button code, not the spectrogram box's later copy):

```kotlin
            if (info.duration > 0) {
                val progress = (elapsedSeconds / info.duration).toFloat().coerceIn(0f, 1f)
                Box(modifier = Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(progress)) {
                    Box(modifier = Modifier.align(Alignment.CenterEnd).width(2.dp).fillMaxHeight().background(Color.White))
                }
                PreviewCaption(
                    "${formatMmSs(elapsedSeconds)} / ${formatMmSs(info.duration)}",
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
                )
            }

            if (!isPlaying) {
```

Replace with:

```kotlin
            if (info.duration > 0) {
                // Progress fill is relative to the VISIBLE window now, not the whole track -- when
                // the playhead is outside the current zoom window, this fraction clamps to 0 or 1
                // (fill empty or full) rather than pointing somewhere meaningless off-screen.
                val windowProgress = ((elapsedSeconds - visibleWindow.startSeconds) / visibleWindow.durationSeconds)
                    .toFloat().coerceIn(0f, 1f)
                Box(modifier = Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(windowProgress)) {
                    Box(modifier = Modifier.align(Alignment.CenterEnd).width(2.dp).fillMaxHeight().background(Color.White))
                }
                PreviewCaption(
                    "${formatMmSs(elapsedSeconds)} / ${formatMmSs(info.duration)}",
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
                )
            }

            if (!isPlaying) {
```

- [ ] **Step 6: Same scroll handler and windowed progress fill on the spectrogram `Box`, plus its scrollbar**

Find:

```kotlin
        Box(
            modifier = Modifier
                .weight(1f - waveformSplit)
                .fillMaxWidth()
                .background(Color.Black)
                .onGloballyPositioned { spectrogramBoxSize = it.size },
        ) {
            val spectrogram = spectrogramBitmap
            if (spectrogram != null) {
                Image(bitmap = spectrogram, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            } else {
                DecodingIndicator("스펙트로그램 생성 중...", modifier = Modifier.align(Alignment.Center))
            }
            if (info.duration > 0) {
                val progress = (elapsedSeconds / info.duration).toFloat().coerceIn(0f, 1f)
                Box(modifier = Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(progress)) {
                    Box(modifier = Modifier.align(Alignment.CenterEnd).width(2.dp).fillMaxHeight().background(Color.White))
                }
            }
        }
    }
}
```

Replace with:

```kotlin
        Box(
            modifier = Modifier
                .weight(1f - waveformSplit)
                .fillMaxWidth()
                .background(Color.Black)
                .onPointerEvent(PointerEventType.Scroll, pass = PointerEventPass.Initial) { event ->
                    val delta = event.changes.firstOrNull()?.scrollDelta ?: return@onPointerEvent
                    applyZoomOrPan(delta.x, delta.y)
                    event.changes.forEach { it.consume() }
                }
                .onGloballyPositioned { spectrogramBoxSize = it.size },
        ) {
            val spectrogram = spectrogramBitmap
            if (spectrogram != null) {
                Image(bitmap = spectrogram, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            } else {
                DecodingIndicator("스펙트로그램 생성 중...", modifier = Modifier.align(Alignment.Center))
            }
            if (info.duration > 0) {
                val windowProgress = ((elapsedSeconds - visibleWindow.startSeconds) / visibleWindow.durationSeconds)
                    .toFloat().coerceIn(0f, 1f)
                Box(modifier = Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(windowProgress)) {
                    Box(modifier = Modifier.align(Alignment.CenterEnd).width(2.dp).fillMaxHeight().background(Color.White))
                }
            }
        }

        AudioZoomScrollbar(
            window = visibleWindow,
            totalDuration = info.duration,
            onWindowChange = { visibleWindow = it },
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

// A thin draggable strip beneath the panels showing (and letting the user drag) the current zoom
// window as a highlighted segment against the full track -- same detectDragGestures convention
// already used by this app's DraggableDivider (Components.kt), just horizontal-position instead
// of a resize split.
@Composable
private fun AudioZoomScrollbar(
    window: AudioViewWindow,
    totalDuration: Double,
    onWindowChange: (AudioViewWindow) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .background(Color.White.copy(alpha = 0.1f))
            .pointerInput(totalDuration) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    if (totalDuration > 0.0 && size.width > 0) {
                        val deltaSeconds = (dragAmount.x / size.width) * totalDuration
                        onWindowChange(clampWindow(window.startSeconds + deltaSeconds, window.durationSeconds, totalDuration))
                    }
                }
            },
    ) {
        if (totalDuration > 0.0) {
            val startFraction = (window.startSeconds / totalDuration).toFloat().coerceIn(0f, 1f)
            val durationFraction = (window.durationSeconds / totalDuration).toFloat().coerceIn(0.001f, 1f)
            val afterFraction = (1f - startFraction - durationFraction).coerceAtLeast(0f)
            Row(modifier = Modifier.fillMaxSize()) {
                Spacer(modifier = Modifier.weight(startFraction.coerceAtLeast(0.0001f)))
                Box(modifier = Modifier.weight(durationFraction).fillMaxHeight().background(Color(0xFF39FF14)))
                Spacer(modifier = Modifier.weight(afterFraction.coerceAtLeast(0.0001f)))
            }
        }
    }
}
```

Note: `Row`'s `weight` requires a positive value, hence the `.coerceAtLeast(0.0001f)` floors on the two spacer weights (a weight of exactly `0f` is invalid) -- the visual effect at either extreme (window starts at 0, or window ends at the track's end) is indistinguishable from a true zero-width spacer.

- [ ] **Step 7: Compile the whole project**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:compileKotlin`
Expected: BUILD SUCCESSFUL. This is the task that resolves Task 1's and Task 2's known-pending compile state -- if this fails, read the exact error; it should NOT be the `WaveformDisplay` or `window` parameter mismatches from before (those are fixed by this task's Steps 4 and 3/6 respectively), so any remaining error is new and needs investigating, not assumed-expected.

- [ ] **Step 8: Run the full test suite**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test`
Expected: BUILD SUCCESSFUL, all tests pass including Task 1's and Task 2's new tests, no regressions elsewhere.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/FfmpegAudioPlayer.kt
git commit -m "feat: wire mouse-wheel zoom, trackpad pan, and a draggable scrollbar into the audio panels"
```

---

## Task 4: Minimap

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/ui/AudioMinimap.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/FfmpegAudioPlayer.kt`

**Interfaces:**
- Consumes (from Tasks 1-3): `AudioViewWindow`, `clampWindow`, `WaveformPeaks`/`ChannelPeaks` (from `AudioWaveformPeaks.kt`), the `visibleWindow` state and `seekToFraction` function already present in `FfmpegAudioPlayer`.
- Produces: `@Composable fun AudioMinimap(peaks: WaveformPeaks?, window: AudioViewWindow, totalDuration: Double, elapsedSeconds: Double, onWindowChange: (AudioViewWindow) -> Unit, onSeek: (fraction: Float) -> Unit, modifier: Modifier = Modifier)`.

- [ ] **Step 1: Create `AudioMinimap.kt`**

Create `app/src/main/kotlin/com/multiviewer/ui/AudioMinimap.kt`:

```kotlin
package com.multiviewer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

// Every Nth bucket of the same 4096-bucket array WaveformDisplay already draws, sampled coarser
// since this is a whole-track overview, not a detail view -- no new computation, no ffmpeg call.
private const val MINIMAP_BUCKET_STRIDE = 8

// Always shows the WHOLE track (never zoomed itself), with a draggable rectangle for the current
// zoom window and a playhead marker. Clicking anywhere seeks the whole player, independent of
// zoom -- the one place seeking always reaches the entire file regardless of the detail panels'
// current window.
@Composable
fun AudioMinimap(
    peaks: WaveformPeaks?,
    window: AudioViewWindow,
    totalDuration: Double,
    elapsedSeconds: Double,
    onWindowChange: (AudioViewWindow) -> Unit,
    onSeek: (fraction: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .background(Color.Black)
            .pointerInput(totalDuration) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    onSeek(down.position.x / size.width.toFloat())
                    drag(down.id) { change ->
                        change.consume()
                        onSeek(change.position.x / size.width.toFloat())
                    }
                }
            },
    ) {
        val totalWidthPx = constraints.maxWidth

        if (peaks != null) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawMinimapWaveform(peaks)
            }
        }

        if (totalDuration > 0.0) {
            val startFraction = (window.startSeconds / totalDuration).toFloat().coerceIn(0f, 1f)
            val durationFraction = (window.durationSeconds / totalDuration).toFloat().coerceIn(0.001f, 1f)
            Box(
                modifier = Modifier
                    .offset(x = maxWidth * startFraction)
                    .width(maxWidth * durationFraction)
                    .fillMaxHeight()
                    .background(Color.White.copy(alpha = 0.25f))
                    .pointerInput(totalDuration, totalWidthPx) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            if (totalWidthPx > 0) {
                                val deltaSeconds = (dragAmount.x / totalWidthPx.toFloat()) * totalDuration
                                onWindowChange(clampWindow(window.startSeconds + deltaSeconds, window.durationSeconds, totalDuration))
                            }
                        }
                    },
            )

            val playheadFraction = (elapsedSeconds / totalDuration).toFloat().coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .offset(x = maxWidth * playheadFraction)
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(Color.White),
            )
        }
    }
}

private fun DrawScope.drawMinimapWaveform(peaks: WaveformPeaks) {
    val channel = peaks.channels.firstOrNull() ?: return
    val width = size.width
    val height = size.height
    val centerY = height / 2f
    val bucketCount = channel.min.size
    if (bucketCount == 0 || width <= 0f) return
    var i = 0
    while (i < bucketCount) {
        val x = width * i / bucketCount
        val yTop = centerY - channel.max[i] * centerY
        val yBottom = centerY - channel.min[i] * centerY
        drawLine(color = Color(0xFF39FF14).copy(alpha = 0.6f), start = Offset(x, yTop), end = Offset(x, yBottom), strokeWidth = 1f)
        i += MINIMAP_BUCKET_STRIDE
    }
}
```

- [ ] **Step 2: Wire `AudioMinimap` into `FfmpegAudioPlayer`**

In `app/src/main/kotlin/com/multiviewer/ui/FfmpegAudioPlayer.kt`, find:

```kotlin
        AudioZoomScrollbar(
            window = visibleWindow,
            totalDuration = info.duration,
            onWindowChange = { visibleWindow = it },
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
```

Replace with:

```kotlin
        AudioZoomScrollbar(
            window = visibleWindow,
            totalDuration = info.duration,
            onWindowChange = { visibleWindow = it },
            modifier = Modifier.padding(top = 2.dp),
        )

        AudioMinimap(
            peaks = waveformPeaks,
            window = visibleWindow,
            totalDuration = info.duration,
            elapsedSeconds = elapsedSeconds,
            onWindowChange = { visibleWindow = it },
            onSeek = { fraction -> seekToFraction(fraction) },
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
```

- [ ] **Step 3: Compile the whole project**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run the full test suite**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test`
Expected: BUILD SUCCESSFUL, no regressions.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/AudioMinimap.kt app/src/main/kotlin/com/multiviewer/ui/FfmpegAudioPlayer.kt
git commit -m "feat: add an always-full-track minimap with draggable zoom window and playhead"
```

---

## Task 5: Controller-performed manual verification

This task has no subagent dispatch -- run it directly in the controlling session, matching this project's established precedent for real runtime/manual verification.

- [ ] **Step 1: Generate a test audio file with distinguishable content over time**

```bash
ffmpeg -f lavfi -i "sine=frequency=220:duration=2,sine=frequency=880:duration=2" -filter_complex "[0:a][1:a]concat=n=2:v=0:a=1" -y /tmp/test-zoom-audio.wav
```

(If the filter graph syntax above doesn't work directly on this ffmpeg build, a simpler alternative that still gives a 4-second file with an audible frequency change partway through is acceptable -- the goal is just "not a single unchanging tone," so zoomed-in sections look visibly different from each other.)

- [ ] **Step 2: Run the app and open the test file**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:run
```

Open `/tmp/test-zoom-audio.wav`.

- [ ] **Step 3: Verify against the plan's Global Constraints**

Confirm each of the following, and note any that fail:
- Scrolling the mouse wheel over the waveform zooms it in/out; the spectrogram zooms together with it (same visible range).
- After zooming in, the spectrogram shows visibly more detail than a naive crop would (compare a zoomed-in view against the same region visible when fully zoomed out -- it should look sharper/more resolved, not blurrier).
- Two-finger trackpad horizontal scroll pans both panels together, in the direction that feels natural (content follows your fingers). The plan's sign for this (`PAN_STEP_FACTOR` applied directly to `scrollDeltaX`, unlike the deliberately-flipped zoom sign) was not empirically verified against real trackpad hardware the way the zoom direction was -- if panning feels backwards, flip the sign on `scrollDeltaX.toDouble() * PAN_STEP_FACTOR` in `applyZoomOrPan` (a one-line fix) and re-verify.
- The draggable scrollbar beneath the panels pans when dragged, and its highlighted segment's width/position always matches the current zoom level/position.
- The existing click-drag-to-seek gesture on the waveform/spectrogram still works exactly as before and is unaffected by zoom level.
- The minimap always shows the whole track, its window-indicator rectangle tracks the current zoom, dragging that rectangle pans, and clicking anywhere on the minimap seeks (even when zoomed in elsewhere).
- Zooming all the way in never produces a degenerate/empty view; panning to either end of the track stops cleanly rather than showing blank space.
- Opening a different audio file resets zoom/pan back to fully-zoomed-out.

- [ ] **Step 4: Update the progress ledger**

Append a summary line to `.git/sdd/progress.md` recording Task 1-4 commit ranges and the outcome of this manual verification (pass, or any issues found and how they were resolved).
