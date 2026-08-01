# Audio Waveform Canvas + Spectrogram Resize Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the audio player's static, once-baked waveform image with a resolution-independent Canvas-drawn waveform computed from real PCM peak data, and make the spectrogram regenerate at the panel's actual current pixel size instead of a fixed 1600×300 baked resolution.

**Architecture:** A new `AudioWaveformPeaks.kt` computes min/max amplitude peaks per channel into a fixed 4096-bucket array by streaming the same `ffmpeg -f s16le` PCM pipe already used for playback, then draws them via a `Canvas` composable that redraws crisply at any panel width with no recomputation needed. `FfmpegAudioPlayer.kt` swaps its waveform `Image` for this new composable, and separately adds panel-size tracking + a debounced `LaunchedEffect` so the spectrogram regenerates via the existing ffmpeg `showspectrumpic` path at the panel's real size after a resize settles.

**Tech Stack:** Kotlin, Compose Desktop Canvas, no new dependencies.

## Global Constraints

- The playback pipeline (`DisposableEffect` + `SourceDataLine` reader thread), the playhead/progress `Box` overlay, and the waveform area's click/drag seek gesture (`pointerInput` + `awaitEachGesture`) must not change at all — confirmed unrelated to the reported issue in the design's investigation.
- The spectrogram keeps using ffmpeg's `showspectrumpic` filter (via the existing `generateSpectrogramImage`/`renderAudioVisualization` functions, unchanged) — only the target size and regeneration trigger change, not the generation mechanism itself.
- Surround audio (more than 2 channels) only displays its first 2 channels — no per-channel UI beyond L/R.
- No interactive zoom/scroll — this stays a whole-file overview, matching the existing scope decision from the original audio-playback feature.
- `ffmpeg` must be on `PATH` for any test that shells out to generate real audio fixtures (matches this project's existing test conventions).

---

### Task 1: `AudioWaveformPeaks.kt` — peak computation and Canvas rendering

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/ui/AudioWaveformPeaks.kt`
- Test: `app/src/test/kotlin/com/multiviewer/ui/AudioWaveformPeaksTest.kt`

**Interfaces:**
- Consumes: `AudioFileInfo` and `FfmpegLocator` (both already in `app/src/main/kotlin/com/multiviewer/ui/FfmpegAudioPlayer.kt`'s package, `com.multiviewer.ui` — no import needed, same package), `probeAudioFormat(file: File): AudioFileInfo?` (also already in that file, used by this task's tests to obtain a real `AudioFileInfo`).
- Produces: `data class ChannelPeaks(val min: FloatArray, val max: FloatArray)`, `data class WaveformPeaks(val channelCount: Int, val bucketCount: Int, val channels: List<ChannelPeaks>)`, `fun computeWaveformPeaks(file: File, info: AudioFileInfo, bucketCount: Int = WAVEFORM_PEAK_BUCKET_COUNT): WaveformPeaks?`, `@Composable fun WaveformDisplay(peaks: WaveformPeaks, color: Color, modifier: Modifier = Modifier)`. Task 2 calls `computeWaveformPeaks` and `WaveformDisplay` directly from `FfmpegAudioPlayer.kt` (same package, no import needed there either).

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/multiviewer/ui/AudioWaveformPeaksTest.kt`:

```kotlin
package com.multiviewer.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AudioWaveformPeaksTest {
    @Test
    fun `computes the requested bucket count and channel count for a mono file`() {
        val audio = File.createTempFile("waveform-peaks-mono-test-", ".wav")
        audio.deleteOnExit()
        ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "sine=duration=1:frequency=440",
            "-ac", "1", "-c:a", "pcm_s16le", audio.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()
        val info = probeAudioFormat(audio)
        checkNotNull(info)

        val peaks = computeWaveformPeaks(audio, info, bucketCount = 256)

        checkNotNull(peaks)
        assertEquals(1, peaks.channelCount)
        assertEquals(256, peaks.bucketCount)
        assertEquals(1, peaks.channels.size)
        audio.delete()
    }

    @Test
    fun `computes two channels of peaks for a stereo file`() {
        val audio = File.createTempFile("waveform-peaks-stereo-test-", ".wav")
        audio.deleteOnExit()
        ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "sine=duration=1:frequency=440",
            "-ac", "2", "-c:a", "pcm_s16le", audio.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()
        val info = probeAudioFormat(audio)
        checkNotNull(info)

        val peaks = computeWaveformPeaks(audio, info, bucketCount = 256)

        checkNotNull(peaks)
        assertEquals(2, peaks.channelCount)
        assertEquals(2, peaks.channels.size)
        audio.delete()
    }

    @Test
    fun `captures non-zero peak amplitudes for a real sine tone`() {
        val audio = File.createTempFile("waveform-peaks-amplitude-test-", ".wav")
        audio.deleteOnExit()
        ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "sine=duration=1:frequency=440",
            "-ac", "1", "-c:a", "pcm_s16le", audio.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()
        val info = probeAudioFormat(audio)
        checkNotNull(info)

        val peaks = computeWaveformPeaks(audio, info, bucketCount = 256)

        checkNotNull(peaks)
        val channel = peaks.channels.single()
        assertTrue(channel.max.any { it > 0.1f }, "Expected at least one bucket with a max amplitude above 0.1, got max values: ${channel.max.toList()}")
        assertTrue(channel.min.any { it < -0.1f }, "Expected at least one bucket with a min amplitude below -0.1, got min values: ${channel.min.toList()}")
        audio.delete()
    }

    @Test
    fun `returns null for a file with no decodable audio`() {
        val garbage = File.createTempFile("waveform-peaks-garbage-test-", ".wav")
        garbage.deleteOnExit()
        garbage.writeBytes(ByteArray(100))
        val fakeInfo = AudioFileInfo(sampleRate = 44100, channels = 1, duration = 1.0)

        val peaks = computeWaveformPeaks(garbage, fakeInfo, bucketCount = 256)

        assertNull(peaks)
        garbage.delete()
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests "com.multiviewer.ui.AudioWaveformPeaksTest"`
Expected: FAIL — `computeWaveformPeaks`/`WaveformPeaks`/`AudioFileInfo` constructor mismatch, file doesn't exist yet.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/kotlin/com/multiviewer/ui/AudioWaveformPeaks.kt`:

```kotlin
package com.multiviewer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import java.io.File
import java.util.concurrent.TimeUnit

data class ChannelPeaks(val min: FloatArray, val max: FloatArray)
data class WaveformPeaks(val channelCount: Int, val bucketCount: Int, val channels: List<ChannelPeaks>)

// Far more buckets than any realistic panel width in pixels -- the Canvas renderer just redraws
// the same bucket values at new pixel positions on resize, no recomputation ever needed.
const val WAVEFORM_PEAK_BUCKET_COUNT = 4096

// Streams the same ffmpeg PCM pipe already used for playback (see FfmpegAudioPlayer's
// DisposableEffect) to compute per-channel min/max amplitude peaks into a fixed-size bucket
// array, without holding the whole decoded file in memory (a multi-hour recording could
// otherwise use hundreds of MB). Frame boundaries don't align with arbitrary read-buffer
// boundaries, so leftover bytes from an incomplete trailing frame are carried into the next read.
fun computeWaveformPeaks(file: File, info: AudioFileInfo, bucketCount: Int = WAVEFORM_PEAK_BUCKET_COUNT): WaveformPeaks? {
    val channels = info.channels
    if (channels <= 0 || bucketCount <= 0) return null

    val frameSizeBytes = channels * 2
    val estimatedTotalFrames = (info.duration * info.sampleRate).toLong().coerceAtLeast(1L)
    val framesPerBucket = (estimatedTotalFrames / bucketCount).coerceAtLeast(1L)

    val minPerChannel = Array(channels) { FloatArray(bucketCount) { Float.MAX_VALUE } }
    val maxPerChannel = Array(channels) { FloatArray(bucketCount) { -Float.MAX_VALUE } }

    val process = try {
        ProcessBuilder(
            FfmpegLocator.ffmpegPath(), "-i", file.absolutePath, "-map", "0:a:0",
            "-f", "s16le", "-ar", info.sampleRate.toString(), "-ac", channels.toString(),
            "-acodec", "pcm_s16le", "-",
        ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    } catch (e: Exception) {
        return null
    }

    return try {
        val input = process.inputStream
        val readBuffer = ByteArray(65536)
        var carry = ByteArray(0)
        var frameIndex = 0L

        while (true) {
            val bytesRead = input.read(readBuffer)
            if (bytesRead < 0) break
            val chunk = if (carry.isEmpty()) readBuffer.copyOf(bytesRead) else carry + readBuffer.copyOf(bytesRead)
            val usableFrames = chunk.size / frameSizeBytes
            val usableBytes = usableFrames * frameSizeBytes
            var offset = 0
            repeat(usableFrames) {
                val bucket = (frameIndex / framesPerBucket).coerceAtMost((bucketCount - 1).toLong()).toInt()
                for (c in 0 until channels) {
                    val sample = (((chunk[offset + 1].toInt() shl 8) or (chunk[offset].toInt() and 0xFF))).toShort().toFloat() / 32768f
                    if (sample < minPerChannel[c][bucket]) minPerChannel[c][bucket] = sample
                    if (sample > maxPerChannel[c][bucket]) maxPerChannel[c][bucket] = sample
                    offset += 2
                }
                frameIndex++
            }
            carry = chunk.copyOfRange(usableBytes, chunk.size)
        }
        val finished = process.waitFor(30, TimeUnit.SECONDS)

        if (!finished || frameIndex == 0L) {
            null
        } else {
            for (c in 0 until channels) {
                for (b in 0 until bucketCount) {
                    if (minPerChannel[c][b] == Float.MAX_VALUE) {
                        minPerChannel[c][b] = 0f
                        maxPerChannel[c][b] = 0f
                    }
                }
            }
            WaveformPeaks(
                channelCount = channels,
                bucketCount = bucketCount,
                channels = (0 until channels).map { ChannelPeaks(minPerChannel[it], maxPerChannel[it]) },
            )
        }
    } catch (e: Exception) {
        null
    } finally {
        process.destroyForcibly()
    }
}

// channelCount >= 2 stacks channel 0 (L) above channel 1 (R); any channels beyond the first two
// are ignored (surround audio is out of scope). channelCount == 1 draws a single full-size Canvas.
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
    for (i in 0 until bucketCount) {
        val x = width * i / bucketCount
        val yTop = centerY - peaks.max[i] * centerY
        val yBottom = centerY - peaks.min[i] * centerY
        drawLine(color = color, start = Offset(x, yTop), end = Offset(x, yBottom))
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests "com.multiviewer.ui.AudioWaveformPeaksTest"`
Expected: PASS, 4/4 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/AudioWaveformPeaks.kt app/src/test/kotlin/com/multiviewer/ui/AudioWaveformPeaksTest.kt
git commit -m "feat: add PCM-based waveform peak computation and Canvas rendering"
```

---

### Task 2: Wire `WaveformDisplay` into `FfmpegAudioPlayer.kt`

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/FfmpegAudioPlayer.kt`

**Interfaces:**
- Consumes: `computeWaveformPeaks(file: File, info: AudioFileInfo, bucketCount: Int = WAVEFORM_PEAK_BUCKET_COUNT): WaveformPeaks?` and `WaveformDisplay(peaks: WaveformPeaks, color: Color, modifier: Modifier = Modifier)` from Task 1 (same package, `com.multiviewer.ui` — no new import needed).
- Produces: no new public symbols; the waveform section of `FfmpegAudioPlayer` now renders via Canvas instead of a baked image. Task 3 builds on the same `LaunchedEffect(file)` block this task edits.

This task touches only the waveform half of the file. The spectrogram half is untouched here (still generated once at the old fixed size) — Task 3 handles that.

- [ ] **Step 1: Replace the waveform state and generation call**

In `app/src/main/kotlin/com/multiviewer/ui/FfmpegAudioPlayer.kt`, find:

```kotlin
    var probedInfo by remember(file) { mutableStateOf<AudioFileInfo?>(null) }
    var probing by remember(file) { mutableStateOf(true) }
    var waveformBitmap by remember(file) { mutableStateOf<ImageBitmap?>(null) }
    var spectrogramBitmap by remember(file) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(file) {
        probing = true
        val info = withContext(Dispatchers.IO) { probeAudioFormat(file) }
        probedInfo = info
        probing = false
        waveformBitmap = withContext(Dispatchers.IO) { generateWaveformImage(file, WAVEFORM_IMAGE_WIDTH, WAVEFORM_IMAGE_HEIGHT) }
        spectrogramBitmap = withContext(Dispatchers.IO) { generateSpectrogramImage(file, WAVEFORM_IMAGE_WIDTH, WAVEFORM_IMAGE_HEIGHT) }
    }
```

Replace with:

```kotlin
    var probedInfo by remember(file) { mutableStateOf<AudioFileInfo?>(null) }
    var probing by remember(file) { mutableStateOf(true) }
    var waveformPeaks by remember(file) { mutableStateOf<WaveformPeaks?>(null) }
    var spectrogramBitmap by remember(file) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(file) {
        probing = true
        val info = withContext(Dispatchers.IO) { probeAudioFormat(file) }
        probedInfo = info
        probing = false
        if (info != null) {
            waveformPeaks = withContext(Dispatchers.IO) { computeWaveformPeaks(file, info) }
        }
        spectrogramBitmap = withContext(Dispatchers.IO) { generateSpectrogramImage(file, WAVEFORM_IMAGE_WIDTH, WAVEFORM_IMAGE_HEIGHT) }
    }
```

- [ ] **Step 2: Replace the waveform rendering**

Find:

```kotlin
            val waveform = waveformBitmap
            if (loadError) {
                Text("Could not start ffmpeg playback", color = Color.White, modifier = Modifier.align(Alignment.Center))
            } else if (waveform != null) {
                Image(bitmap = waveform, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            } else {
                DecodingIndicator("파형 생성 중...", modifier = Modifier.align(Alignment.Center))
            }
```

Replace with:

```kotlin
            val peaks = waveformPeaks
            if (loadError) {
                Text("Could not start ffmpeg playback", color = Color.White, modifier = Modifier.align(Alignment.Center))
            } else if (peaks != null) {
                WaveformDisplay(peaks = peaks, color = Color(0xFF39FF14), modifier = Modifier.fillMaxSize())
            } else {
                DecodingIndicator("파형 생성 중...", modifier = Modifier.align(Alignment.Center))
            }
```

- [ ] **Step 3: Remove the now-unused `generateWaveformImage` function**

Find and delete this function (its two callers were both replaced in Steps 1-2 above; `WAVEFORM_IMAGE_WIDTH`/`WAVEFORM_IMAGE_HEIGHT` stay for now — the spectrogram call in Step 1 above still uses them, until Task 3 replaces that call too):

```kotlin
fun generateWaveformImage(file: File, width: Int, height: Int): ImageBitmap? =
    renderAudioVisualization(file, "showwavespic=s=${width}x${height}:colors=0x39FF14")
```

- [ ] **Step 4: Build and manually sanity-check**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL (no automated test covers this Compose UI code — this project has no Compose UI test infrastructure for `FfmpegAudioPlayer.kt`, consistent with its existing lack of coverage).

Then run the full test suite once to confirm the unrelated `AudioWaveformPeaksTest` (Task 1) and everything else still pass:

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test`
Expected: PASS, no new failures.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/FfmpegAudioPlayer.kt
git commit -m "feat: render the audio waveform via Canvas instead of a baked ffmpeg image"
```

---

### Task 3: Debounced spectrogram regeneration at panel size

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/FfmpegAudioPlayer.kt`

**Interfaces:**
- Consumes: `generateSpectrogramImage(file: File, width: Int, height: Int): ImageBitmap?` (already in this file, unchanged) and `IntSize`/`delay` (new imports, see Step 1).
- Produces: no new public symbols; the spectrogram now regenerates at its panel's actual pixel size after a resize settles, instead of once at a fixed 1600×300.

- [ ] **Step 1: Add the new imports**

In `app/src/main/kotlin/com/multiviewer/ui/FfmpegAudioPlayer.kt`, find the import block (near the top of the file) containing:

```kotlin
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
```

Replace with:

```kotlin
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
```

Then find:

```kotlin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
```

Replace with:

```kotlin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
```

- [ ] **Step 2: Remove the fixed-size spectrogram generation from the main `LaunchedEffect`**

Find (this is the same block Task 2 already edited once -- it should currently look like this):

```kotlin
    LaunchedEffect(file) {
        probing = true
        val info = withContext(Dispatchers.IO) { probeAudioFormat(file) }
        probedInfo = info
        probing = false
        if (info != null) {
            waveformPeaks = withContext(Dispatchers.IO) { computeWaveformPeaks(file, info) }
        }
        spectrogramBitmap = withContext(Dispatchers.IO) { generateSpectrogramImage(file, WAVEFORM_IMAGE_WIDTH, WAVEFORM_IMAGE_HEIGHT) }
    }
```

Replace with:

```kotlin
    LaunchedEffect(file) {
        probing = true
        val info = withContext(Dispatchers.IO) { probeAudioFormat(file) }
        probedInfo = info
        probing = false
        if (info != null) {
            waveformPeaks = withContext(Dispatchers.IO) { computeWaveformPeaks(file, info) }
        }
    }
```

- [ ] **Step 3: Add panel-size tracking and the debounced regeneration effect**

Find:

```kotlin
    var waveformSplit by remember(file) { mutableStateOf(0.6f) }
    var containerHeightPx by remember(file) { mutableStateOf(0) }
    val elapsedSeconds = (startFromSeconds + playedSeconds).coerceIn(0.0, if (info.duration > 0) info.duration else Double.MAX_VALUE)
```

Replace with:

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
        val newBitmap = withContext(Dispatchers.IO) { generateSpectrogramImage(file, boxSize.width, boxSize.height) }
        if (newBitmap != null) spectrogramBitmap = newBitmap
    }
```

- [ ] **Step 4: Track the spectrogram Box's size**

Find:

```kotlin
        Box(modifier = Modifier.weight(1f - waveformSplit).fillMaxWidth().background(Color.Black)) {
            val spectrogram = spectrogramBitmap
```

Replace with:

```kotlin
        Box(
            modifier = Modifier
                .weight(1f - waveformSplit)
                .fillMaxWidth()
                .background(Color.Black)
                .onGloballyPositioned { spectrogramBoxSize = it.size },
        ) {
            val spectrogram = spectrogramBitmap
```

- [ ] **Step 5: Add the debounce constant and remove the now-unused fixed-size constants**

Find:

```kotlin
private const val WAVEFORM_IMAGE_WIDTH = 1600
private const val WAVEFORM_IMAGE_HEIGHT = 300
```

Replace with:

```kotlin
private const val SPECTROGRAM_RESIZE_DEBOUNCE_MS = 400L
```

- [ ] **Step 6: Build and run the full suite**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test`
Expected: PASS, no new failures.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/FfmpegAudioPlayer.kt
git commit -m "feat: regenerate the spectrogram at the panel's actual size after resize settles"
```

---

### Task 4: Manual end-to-end verification (controller-performed)

No automated coverage is possible for this task (Compose UI + timing-dependent debounce + audio hardware playback). This step is performed by the controller directly, not dispatched to a subagent.

- [ ] Generate a real stereo fixture: `ffmpeg -y -f lavfi -i "sine=duration=10:frequency=440" -ac 2 -c:a pcm_s16le /tmp/test-verify-stereo.wav` and a mono one: `ffmpeg -y -f lavfi -i "sine=duration=10:frequency=440" -ac 1 -c:a pcm_s16le /tmp/test-verify-mono.wav`
- [ ] Launch the app (`export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew run`) and open the stereo file
- [ ] Confirm two waveform rows render (L on top, R on bottom), not one
- [ ] Resize the window to be much wider than before (well past the old fixed 1600px baked resolution) and confirm the waveform stays sharp with no blurring or pixelation
- [ ] Drag the divider to resize the spectrogram panel; confirm the spectrogram regenerates at the new size after a brief pause, with no blank flash during the transition
- [ ] Play the file and confirm the playhead line visually tracks the waveform's actual shape/transients accurately as it moves
- [ ] Confirm click-to-seek and drag-to-seek on the waveform still work exactly as before
- [ ] Open the mono file and confirm it shows a single waveform row, not two
- [ ] If any issue is found, treat it as a real bug — return to systematic-debugging, not a quick patch

---

## Self-Review Notes

- **Spec coverage:** peak computation (streaming, leftover-byte carry, bucket count) ✅ (Task 1), Canvas rendering resolution-independent redraw ✅ (Task 1), mono vs. L/R-stacked-stereo display ✅ (Task 1), waveform wiring into `FfmpegAudioPlayer.kt` with the playback/seek/playhead code left untouched ✅ (Task 2), spectrogram panel-size tracking + debounced regeneration ✅ (Task 3), old-bitmap-stays-visible-during-regen (no explicit code needed -- falls out of `mutableState` only being reassigned once the new bitmap is ready, confirmed in Task 3's code comment) ✅, manual verification of both channel layouts, resize sharpness, and unaffected seek/playback ✅ (Task 4).
- **Placeholder scan:** none found.
- **Type consistency:** `computeWaveformPeaks(file: File, info: AudioFileInfo, bucketCount: Int = WAVEFORM_PEAK_BUCKET_COUNT): WaveformPeaks?` and `WaveformDisplay(peaks: WaveformPeaks, color: Color, modifier: Modifier = Modifier)` are used identically in Task 1 (definition) and Task 2 (call sites). `waveformPeaks`/`spectrogramBitmap`/`spectrogramBoxSize` state variable names are consistent across Tasks 2-3's sequential edits to the same file (each task's "before" snippet matches exactly what the prior task's "after" snippet produced).
