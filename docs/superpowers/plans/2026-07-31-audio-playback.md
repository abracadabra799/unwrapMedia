# Audio Playback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Play `m4a`/`mp3`/`wav` files with play/pause, click/drag-to-seek, elapsed/total time, and a static waveform + spectrogram overview with a moving playhead.

**Architecture:** A new file `FfmpegAudioPlayer.kt`, structurally parallel to `FfmpegVideoPlayer.kt` -- ffmpeg subprocess piping raw PCM to a `javax.sound.sampled.SourceDataLine` for playback (no video-style frame pacing needed, `SourceDataLine.write()`'s natural blocking IS the pacing), plus two ffmpeg-filter-rendered PNG images (`showwavespic`/`showspectrumpic`) decoded via Skia, following this codebase's existing temp-file ffmpeg-image-extraction convention (`FfmpegImageSnapshotDecoder.kt`). `AudioInspectorUI.kt` gains a `verticalSplit`-controlled top region for the new player, mirroring `ImageInspectorUI.kt`'s existing single-split layout.

**Tech Stack:** Kotlin, Compose Multiplatform Desktop, `javax.sound.sampled` (JDK-standard, no new dependency), bundled ffmpeg (already present).

## Global Constraints

- No forced resampling: PCM extraction and the `SourceDataLine`'s `AudioFormat` both use the source file's own probed sample rate and channel count (`probeAudioFormat`'s result), falling back to 44100Hz/stereo only if probing fails.
- Waveform and spectrogram images are generated once per file (in the background, non-blocking) and never regenerated on resize -- a fixed backing resolution (1600x300) that Compose scales to fit, matching `ContentScale.Fit`/`FillBounds` usage elsewhere in this codebase.
- No real-time/live audio analysis -- both images are static; only the playhead cursor (a line positioned at `elapsed/total` fraction) updates during playback.
- `FfmpegVideoPlayer.kt` is not modified by this plan at all -- audio's waveform-click-to-seek is self-contained in the new file, there is no shared component to extract.
- Pausing must be near-instant (not merely "stop feeding new data and let the OS buffer drain") -- the reader thread calls `SourceDataLine.stop()`/`start()` exactly on play/pause transitions, not just skips writes.

---

### Task 1: Audio format probe and waveform/spectrogram image generation

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/ui/FfmpegAudioPlayer.kt`
- Test: `app/src/test/kotlin/com/multiviewer/ui/FfmpegAudioPlayerTest.kt`

**Interfaces:**
- Produces: `data class AudioFileInfo(val sampleRate: Int, val channels: Int, val duration: Double)`; `fun probeAudioFormat(file: File): AudioFileInfo?`; `fun generateWaveformImage(file: File, width: Int, height: Int): ImageBitmap?`; `fun generateSpectrogramImage(file: File, width: Int, height: Int): ImageBitmap?`. Task 2 adds the `@Composable FfmpegAudioPlayer` to this same file and calls all four.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/multiviewer/ui/FfmpegAudioPlayerTest.kt`:

```kotlin
package com.multiviewer.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FfmpegAudioPlayerTest {
    @Test
    fun `probeAudioFormat reads sample rate, channels, and duration from a real audio file`() {
        val audio = File.createTempFile("ffmpeg-audio-probe-test-", ".wav")
        audio.deleteOnExit()
        ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=3:sample_rate=48000",
            "-ac", "2", audio.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()

        val info = probeAudioFormat(audio)

        assertEquals(48000, info?.sampleRate)
        assertEquals(2, info?.channels)
        assertTrue(info != null && info.duration > 2.9 && info.duration < 3.1, "expected duration near 3.0s, got ${info?.duration}")
        audio.delete()
    }

    @Test
    fun `probeAudioFormat returns null for a nonexistent file`() {
        assertNull(probeAudioFormat(File("/nonexistent/path/does-not-exist.wav")))
    }

    @Test
    fun `generateWaveformImage produces a decoded bitmap at the requested dimensions`() {
        val audio = File.createTempFile("ffmpeg-waveform-test-", ".wav")
        audio.deleteOnExit()
        ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=2",
            audio.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()

        val bitmap = generateWaveformImage(audio, 400, 100)

        assertNotNull(bitmap)
        assertEquals(400, bitmap.width)
        assertEquals(100, bitmap.height)
        audio.delete()
    }

    @Test
    fun `generateSpectrogramImage produces a decoded bitmap at the requested dimensions`() {
        val audio = File.createTempFile("ffmpeg-spectrogram-test-", ".wav")
        audio.deleteOnExit()
        ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=2",
            audio.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()

        val bitmap = generateSpectrogramImage(audio, 400, 100)

        assertNotNull(bitmap)
        assertEquals(400, bitmap.width)
        assertEquals(100, bitmap.height)
        audio.delete()
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests "com.multiviewer.ui.FfmpegAudioPlayerTest"`
Expected: FAIL to compile -- `probeAudioFormat`/`generateWaveformImage`/`generateSpectrogramImage`/`AudioFileInfo` don't exist yet.

- [ ] **Step 3: Create FfmpegAudioPlayer.kt with the probe and image-generation functions**

Create `app/src/main/kotlin/com/multiviewer/ui/FfmpegAudioPlayer.kt`:

```kotlin
package com.multiviewer.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image
import java.io.File
import java.util.concurrent.TimeUnit

data class AudioFileInfo(val sampleRate: Int, val channels: Int, val duration: Double)

fun probeAudioFormat(file: File): AudioFileInfo? {
    return try {
        val process = ProcessBuilder(
            FfmpegLocator.ffprobePath(), "-v", "error", "-select_streams", "a:0",
            "-show_entries", "stream=sample_rate,channels,duration",
            "-of", "default=noprint_wrappers=1", file.absolutePath,
        ).redirectErrorStream(false).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val lines = process.inputStream.bufferedReader().readLines()
        process.waitFor(5, TimeUnit.SECONDS)

        val values = mutableMapOf<String, String>()
        for (line in lines) {
            val eq = line.indexOf('=')
            if (eq < 0) continue
            values[line.substring(0, eq)] = line.substring(eq + 1)
        }
        val sampleRate = values["sample_rate"]?.toIntOrNull() ?: return null
        val channels = values["channels"]?.toIntOrNull() ?: return null
        val duration = values["duration"]?.toDoubleOrNull() ?: 0.0
        AudioFileInfo(sampleRate, channels, duration)
    } catch (e: Exception) {
        null
    }
}

private const val AUDIO_VISUAL_TIMEOUT_MS = 10000L

// Renders a whole-file waveform/spectrogram overview via ffmpeg's own showwavespic/showspectrumpic
// filters -- both already implement the standard min/max-per-column peak-decimation technique
// audio editors use for this, so there's no need to hand-roll PCM bucketing or FFT in Kotlin.
// Follows the same temp-file ffmpeg-image-extraction convention as
// FfmpegImageSnapshotDecoder.decodeSingleFrameToBitmap: write to a temp PNG, wait with a timeout,
// check exit code and file size, decode via Skia, always clean up the temp file.
private fun renderAudioVisualization(file: File, filter: String): ImageBitmap? {
    val tempPng = try {
        File.createTempFile("audio-visual-", ".png")
    } catch (e: Exception) {
        return null
    }
    tempPng.deleteOnExit()
    return try {
        val process = ProcessBuilder(
            FfmpegLocator.ffmpegPath(), "-y", "-i", file.absolutePath,
            "-lavfi", filter, "-frames:v", "1", tempPng.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
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
    }
}

fun generateWaveformImage(file: File, width: Int, height: Int): ImageBitmap? =
    renderAudioVisualization(file, "showwavespic=s=${width}x${height}:colors=0x39FF14")

fun generateSpectrogramImage(file: File, width: Int, height: Int): ImageBitmap? =
    renderAudioVisualization(file, "showspectrumpic=s=${width}x${height}")
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests "com.multiviewer.ui.FfmpegAudioPlayerTest"`
Expected: PASS (4/4).

- [ ] **Step 5: Run the full suite**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test`
Expected: `BUILD SUCCESSFUL`, all tests pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/FfmpegAudioPlayer.kt app/src/test/kotlin/com/multiviewer/ui/FfmpegAudioPlayerTest.kt
git commit -m "Add audio format probe and waveform/spectrogram image generation"
```

---

### Task 2: FfmpegAudioPlayer composable (playback + UI)

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/FfmpegAudioPlayer.kt`

**Interfaces:**
- Consumes: `AudioFileInfo`, `probeAudioFormat`, `generateWaveformImage`, `generateSpectrogramImage` (Task 1, same file); `FfmpegLocator.ffmpegPath()`; `DecodingIndicator(label, modifier)`, `PreviewCaption(text, modifier)`, `DraggableDivider(orientation, containerSizePx, getSplit, setSplit)`, `formatMmSs(seconds: Double): String` (all existing, from `Components.kt`/`FfmpegVideoPlayer.kt`, same package).
- Produces: `@Composable fun FfmpegAudioPlayer(file: File, modifier: Modifier = Modifier)`, the entry point Task 3 wires into `AudioInspectorUI.kt`.

No automated tests for this step -- Compose composable/audio-hardware I/O, consistent with this project's existing lack of Compose UI test infrastructure (same category as `FfmpegVideoPlayer`'s own composable, which also has no direct unit test -- only its top-level pure functions like `parseFrameRate` are tested).

- [ ] **Step 1: Append the composable to FfmpegAudioPlayer.kt**

Add these imports to the top of `app/src/main/kotlin/com/multiviewer/ui/FfmpegAudioPlayer.kt` (merge with the existing ones from Task 1, keep both sets):

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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.EventQueue
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
```

Then append this to the end of the file:

```kotlin
private const val WAVEFORM_IMAGE_WIDTH = 1600
private const val WAVEFORM_IMAGE_HEIGHT = 300

@Composable
fun FfmpegAudioPlayer(file: File, modifier: Modifier = Modifier) {
    var isPlaying by remember(file) { mutableStateOf(false) }
    var hasEnded by remember(file) { mutableStateOf(false) }
    var restartTrigger by remember(file) { mutableStateOf(0) }
    var playedSeconds by remember(file) { mutableStateOf(0.0) }
    var startFromSeconds by remember(file) { mutableStateOf(0.0) }
    var loadError by remember(file) { mutableStateOf(false) }

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

    if (probing) {
        Box(modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            DecodingIndicator("오디오 정보 분석 중...")
        }
        return
    }

    val info = probedInfo
    if (info == null) {
        Box(modifier.fillMaxSize().background(Color.DarkGray), contentAlignment = Alignment.Center) {
            Text("Could not read audio (is ffmpeg installed?)", color = Color.White)
        }
        return
    }

    DisposableEffect(file, restartTrigger) {
        playedSeconds = 0.0
        val seekSeconds = startFromSeconds
        val seekArgs = if (seekSeconds > 0.0) listOf("-ss", seekSeconds.toString()) else emptyList()
        val sampleRate = info.sampleRate
        val channels = info.channels
        val process = try {
            ProcessBuilder(
                listOf(FfmpegLocator.ffmpegPath()) + seekArgs + listOf(
                    "-i", file.absolutePath, "-map", "0:a:0",
                    "-f", "s16le", "-ar", sampleRate.toString(), "-ac", channels.toString(),
                    "-acodec", "pcm_s16le", "-",
                ),
            ).start()
        } catch (e: Exception) {
            null
        }
        if (process == null) loadError = true

        val stopped = AtomicBoolean(false)
        val format = AudioFormat(sampleRate.toFloat(), 16, channels, true, false)
        val bytesPerSecond = sampleRate * channels * 2

        val readerThread = if (process != null) {
            Thread {
                try {
                    val line = AudioSystem.getSourceDataLine(format)
                    line.open(format)
                    line.start()
                    var wasPlaying = true
                    val buffer = ByteArray(8192)
                    val input = process.inputStream
                    while (!stopped.get()) {
                        if (!isPlaying) {
                            if (wasPlaying) {
                                line.stop()
                                wasPlaying = false
                            }
                            Thread.sleep(50)
                            continue
                        }
                        if (!wasPlaying) {
                            line.start()
                            wasPlaying = true
                        }
                        val bytesRead = input.read(buffer)
                        if (bytesRead < 0) {
                            EventQueue.invokeLater {
                                isPlaying = false
                                hasEnded = true
                            }
                            break
                        }
                        line.write(buffer, 0, bytesRead)
                        val secondsThisChunk = bytesRead.toDouble() / bytesPerSecond
                        EventQueue.invokeLater { playedSeconds += secondsThisChunk }
                    }
                    line.stop()
                    line.flush()
                    line.close()
                } catch (e: InterruptedException) {
                    // Expected on dispose -- not an error.
                } catch (e: Exception) {
                    System.err.println("FfmpegAudioPlayer reader thread failed: $e")
                }
            }.apply { isDaemon = true }.also { it.start() }
        } else {
            null
        }

        onDispose {
            stopped.set(true)
            readerThread?.interrupt()
            process?.destroyForcibly()
        }
    }

    var waveformSplit by remember(file) { mutableStateOf(0.6f) }
    var containerHeightPx by remember(file) { mutableStateOf(0) }
    val elapsedSeconds = (startFromSeconds + playedSeconds).coerceIn(0.0, if (info.duration > 0) info.duration else Double.MAX_VALUE)

    fun seekToFraction(fraction: Float) {
        hasEnded = false
        isPlaying = false
        startFromSeconds = fraction.coerceIn(0f, 1f) * info.duration
        restartTrigger++
    }

    Column(modifier = modifier.fillMaxSize().onGloballyPositioned { containerHeightPx = it.size.height }) {
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
            val waveform = waveformBitmap
            if (loadError) {
                Text("Could not start ffmpeg playback", color = Color.White, modifier = Modifier.align(Alignment.Center))
            } else if (waveform != null) {
                Image(bitmap = waveform, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            } else {
                DecodingIndicator("파형 생성 중...", modifier = Modifier.align(Alignment.Center))
            }

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
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.5f))
                        .clickable {
                            if (hasEnded) {
                                hasEnded = false
                                startFromSeconds = 0.0
                                restartTrigger++
                            }
                            isPlaying = true
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = Color.White, modifier = Modifier.size(48.dp))
                }
            } else {
                Box(modifier = Modifier.fillMaxSize().clickable { isPlaying = false })
            }
        }

        DraggableDivider(
            orientation = Orientation.Horizontal,
            containerSizePx = containerHeightPx,
            getSplit = { waveformSplit },
            setSplit = { waveformSplit = it },
        )

        Box(modifier = Modifier.weight(1f - waveformSplit).fillMaxWidth().background(Color.Black)) {
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

- [ ] **Step 2: Compile**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew compileKotlin`
Expected: `BUILD SUCCESSFUL`. All symbols used (`DecodingIndicator`, `PreviewCaption`, `DraggableDivider`, `formatMmSs`, `FfmpegLocator`) already exist in this package from prior work in this codebase -- if compilation fails on any of them, double-check the exact names against `Components.kt` and `FfmpegVideoPlayer.kt` rather than guessing at a fix.

- [ ] **Step 3: Run the full suite (regression check)**

Run:
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test
find app/build/test-results -name "*.xml" | xargs grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' | awk -F'"' '{tests+=$2; fail+=$6; err+=$8} END {print "total tests:", tests, "failures:", fail, "errors:", err}'
```
Expected: `BUILD SUCCESSFUL`, `failures: 0 errors: 0`, same total test count as after Task 1 (this task adds no new tests, only the composable).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/FfmpegAudioPlayer.kt
git commit -m "Add FfmpegAudioPlayer: playback, waveform/spectrogram display, click-to-seek"
```

---

### Task 3: Wire FfmpegAudioPlayer into AudioInspectorUI

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/AudioInspectorUI.kt`

**Interfaces:**
- Consumes: `FfmpegAudioPlayer(file: File, modifier: Modifier = Modifier)` (Task 2).
- Produces: nothing new for other files.

- [ ] **Step 1: Confirm current AudioInspectorUI.kt**

Run: `cat -n app/src/main/kotlin/com/multiviewer/ui/AudioInspectorUI.kt`

Confirm it matches:

```kotlin
package com.multiviewer.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// Minimal inspector for audio files whose container this app already understands structurally --
// currently just M4A, an MP4-family container (same ftyp/moov/trak layout as mp4/mov/m4v) parsed
// by the same generic box walker, with MediaSummaryBuilder's existing detectCategory/
// buildVideoSummary/buildAudioDetail already handling a video-less "soun"-only moov correctly.
// No player: this app has never had real audio output (FfmpegVideoPlayer always drops audio with
// -an) -- that's a separate subsystem, not something reusing the existing parser gets for free.
@Composable
fun AudioInspectorUI(
    appState: AppState,
    tab: TabState,
    leftPanel: @Composable ColumnScope.() -> Unit,
    bottomPanel: @Composable ColumnScope.() -> Unit,
) {
    val summary = tab.mediaSummary
    DashboardLayout(
        leftPanel = leftPanel,
        centerPanel = {
            val scrollState = remember(tab) { androidx.compose.foundation.lazy.LazyListState() }
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(state = scrollState, modifier = Modifier.fillMaxSize()) {
                    item {
                        if (summary != null) {
                            SummaryBox("🎵 오디오 분석 요약", summary.sections)
                        }
                    }
                    item { Spacer(Modifier.height(32.dp)) }
                }
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(scrollState),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                )
            }
        },
        rightPanel = {
            DetailedPropertiesPanel(tab)
        },
        bottomPanel = bottomPanel,
    )
}
```

If different, stop and re-read the file before editing.

- [ ] **Step 2: Replace with the two-region layout**

Replace the entire file content with:

```kotlin
package com.multiviewer.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp

// Structural parsing for audio files -- currently just M4A, an MP4-family container (same
// ftyp/moov/trak layout as mp4/mov/m4v) parsed by the same generic box walker, with
// MediaSummaryBuilder's existing detectCategory/buildVideoSummary/buildAudioDetail already
// handling a video-less "soun"-only moov correctly. Playback is FfmpegAudioPlayer -- ffmpeg PCM
// piped to a javax.sound.sampled SourceDataLine, plus a static waveform/spectrogram overview
// rendered once per file via ffmpeg's own showwavespic/showspectrumpic filters.
@Composable
fun AudioInspectorUI(
    appState: AppState,
    tab: TabState,
    leftPanel: @Composable ColumnScope.() -> Unit,
    bottomPanel: @Composable ColumnScope.() -> Unit,
) {
    val summary = tab.mediaSummary
    var containerHeightPx by remember(tab) { mutableStateOf(0) }
    var verticalSplit by remember(tab) { mutableStateOf(0.5f) }

    DashboardLayout(
        leftPanel = leftPanel,
        centerPanel = {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { containerHeightPx = it.size.height },
            ) {
                FfmpegAudioPlayer(tab.file, modifier = Modifier.weight(verticalSplit).fillMaxWidth())

                DraggableDivider(
                    orientation = Orientation.Horizontal,
                    containerSizePx = containerHeightPx,
                    getSplit = { verticalSplit },
                    setSplit = { verticalSplit = it },
                )

                val scrollState = remember(tab) { androidx.compose.foundation.lazy.LazyListState() }
                Box(modifier = Modifier.weight(1f - verticalSplit).fillMaxWidth()) {
                    LazyColumn(state = scrollState, modifier = Modifier.fillMaxSize()) {
                        item {
                            if (summary != null) {
                                SummaryBox("🎵 오디오 분석 요약", summary.sections)
                            }
                        }
                        item { Spacer(Modifier.height(32.dp)) }
                    }
                    VerticalScrollbar(
                        adapter = rememberScrollbarAdapter(scrollState),
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                    )
                }
            }
        },
        rightPanel = {
            DetailedPropertiesPanel(tab)
        },
        bottomPanel = bottomPanel,
    )
}
```

- [ ] **Step 3: Compile**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew compileKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Run the full suite (regression check)**

Run:
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test
find app/build/test-results -name "*.xml" | xargs grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' | awk -F'"' '{tests+=$2; fail+=$6; err+=$8} END {print "total tests:", tests, "failures:", fail, "errors:", err}'
```
Expected: `BUILD SUCCESSFUL`, `failures: 0 errors: 0`, same total test count as after Task 1.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/AudioInspectorUI.kt
git commit -m "Wire FfmpegAudioPlayer into AudioInspectorUI's center panel"
```

---

### Task 4: Manual end-to-end verification

**Files:** none (verification only, no code changes).

**Interfaces:** none.

- [ ] **Step 1: Generate real audio fixtures for each supported extension**

Run:
```bash
ffmpeg -y -f lavfi -i "sine=frequency=440:duration=8" /tmp/audio-verify.wav
ffmpeg -y -i /tmp/audio-verify.wav -c:a aac /tmp/audio-verify.m4a
ffmpeg -y -i /tmp/audio-verify.wav -c:a libmp3lame /tmp/audio-verify.mp3
```

- [ ] **Step 2: Manual GUI verification**

Build and run the app (`export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew run`). For each of `/tmp/audio-verify.wav`, `.m4a`, `.mp3`:
- Open the file, confirm the waveform and spectrogram render above the summary panel.
- Click the play button, confirm audio is audible and the playhead cursor moves across both the waveform and spectrogram in sync.
- Click pause, confirm audio stops immediately (not after a noticeable delay).
- Click/drag on the waveform to seek to a few different positions, confirm playback resumes from roughly the right spot and the elapsed/total time labels update correctly.
- Let a short clip play to the end, confirm it stops and shows the play icon again (replay), and clicking play again restarts from the beginning.
- Drag the divider between the waveform/spectrogram area and confirm it resizes; drag the divider between that area and the summary panel below and confirm that resizes too.

---
