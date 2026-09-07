# Scrolling Oscilloscope Waveform + Channel Solo Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the audio player's static full-file waveform with a DJ-tool-style scrolling view (5 s window, centre playhead, 60 fps follow) and add Stereo/L/R channel solo.

**Architecture:** Waveform peaks become duration-proportional (fine enough for a 5 s zoom) with a `downsamplePeaks` step so renderers stay fast when zoomed out. A `withFrameNanos` coroutine drives `visibleWindow` to keep the interpolated playhead centred while playing. Channel solo re-pipes ffmpeg with an `-af pan` filter and a position-preserving restart.

**Tech Stack:** Kotlin 2.0.21, Compose for Desktop 1.7.3, `javax.sound.sampled`, ffmpeg subprocess, JUnit5 + `kotlin.test`.

## Global Constraints

- Kotlin 2.0.21. Do NOT touch `app/build.gradle.kts`.
- Every ffmpeg/ffprobe `ProcessBuilder` calls `FfmpegLocator.configureEnvironment(it)`; spawned processes register with `com.multiviewer.util.ProcessManager` and are terminated on dispose. (This plan does not add new process spawns — it only extends the existing ffmpeg arg list.)
- Commit messages end with exactly (blank line before the trailers):
  ```
  Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_013FCbWJi4EtnSbNjb27Rvyo
  ```
- The static full-file waveform is being replaced by the scrolling view; the minimap remains the whole-file overview.
- Solo affects audio output only — the waveform keeps its existing L/R split rendering.

## File Structure

| File | Responsibility |
|---|---|
| `app/src/main/kotlin/com/multiviewer/ui/AudioWaveformPeaks.kt` | **Modify.** Add `waveformBucketCountFor`, `PeakColumn`, `downsamplePeaks`; `drawChannelPeaks` renders downsampled columns. |
| `app/src/main/kotlin/com/multiviewer/ui/AudioMinimap.kt` | **Modify.** `drawMinimapWaveform` uses `downsamplePeaks` (drop `MINIMAP_BUCKET_STRIDE`). |
| `app/src/main/kotlin/com/multiviewer/ui/AudioZoomPan.kt` | **Modify.** Add `followWindow` pure helper. |
| `app/src/main/kotlin/com/multiviewer/ui/FfmpegAudioPlayer.kt` | **Modify.** 5 s default `visibleWindow`; pass `waveformBucketCountFor` to `computeWaveformPeaks`; follow coroutine + `smoothElapsed`; `ChannelMode` + `channelModeFilterArgs` + pipe wiring + segmented toggle UI. |
| `app/src/test/kotlin/com/multiviewer/ui/AudioWaveformPeaksTest.kt` | **Modify.** Tests for `waveformBucketCountFor`, `downsamplePeaks`. |
| `app/src/test/kotlin/com/multiviewer/ui/AudioZoomPanTest.kt` | **Modify.** Tests for `followWindow`. |
| `app/src/test/kotlin/com/multiviewer/ui/FfmpegAudioPlayerTest.kt` | **Modify.** Tests for `channelModeFilterArgs`. |

---

## Task 1: Peak resolution — `waveformBucketCountFor`, `PeakColumn`, `downsamplePeaks` (pure)

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/AudioWaveformPeaks.kt`
- Test: `app/src/test/kotlin/com/multiviewer/ui/AudioWaveformPeaksTest.kt`

**Interfaces:**
- Consumes: existing `ChannelPeaks(val min: FloatArray, val max: FloatArray)`.
- Produces:
  ```kotlin
  fun waveformBucketCountFor(durationSeconds: Double): Int
  data class PeakColumn(val min: Float, val max: Float)
  fun downsamplePeaks(peaks: ChannelPeaks, visibleRange: IntRange, targetColumns: Int): List<PeakColumn>
  ```

- [ ] **Step 1: Write the failing tests**

Append to `AudioWaveformPeaksTest.kt` (imports already include `kotlin.test.Test`, `assertEquals`, `assertTrue`):

```kotlin
    @Test
    fun `waveformBucketCountFor floors at 4096 for short files`() {
        assertEquals(4096, waveformBucketCountFor(0.0))
        assertEquals(4096, waveformBucketCountFor(10.0)) // 10*300 = 3000, below the floor
    }

    @Test
    fun `waveformBucketCountFor scales at 300 buckets per second in the mid range`() {
        assertEquals(30_000, waveformBucketCountFor(100.0))
        assertEquals(90_000, waveformBucketCountFor(300.0))
    }

    @Test
    fun `waveformBucketCountFor caps at 1_800_000 for very long files`() {
        assertEquals(1_800_000, waveformBucketCountFor(20_000.0)) // would be 6,000,000
        assertEquals(1_800_000, waveformBucketCountFor(6_000.0))  // exactly at the cap
    }

    @Test
    fun `downsamplePeaks returns empty for an empty range or zero columns`() {
        val p = ChannelPeaks(FloatArray(10), FloatArray(10))
        assertTrue(downsamplePeaks(p, IntRange.EMPTY, 100).isEmpty())
        assertTrue(downsamplePeaks(p, 0..9, 0).isEmpty())
    }

    @Test
    fun `downsamplePeaks passes buckets through unchanged when the range fits in the target`() {
        val min = floatArrayOf(-0.1f, -0.2f, -0.3f, -0.4f)
        val max = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f)
        val cols = downsamplePeaks(ChannelPeaks(min, max), 1..2, 100)
        assertEquals(listOf(PeakColumn(-0.2f, 0.2f), PeakColumn(-0.3f, 0.3f)), cols)
    }

    @Test
    fun `downsamplePeaks aggregates min and max over each span when buckets exceed the target`() {
        // 8 buckets -> 2 columns: column 0 covers buckets 0..3, column 1 covers 4..7
        val min = floatArrayOf(-0.1f, -0.9f, -0.2f, -0.3f, -0.4f, -0.5f, -0.05f, -0.6f)
        val max = floatArrayOf(0.1f, 0.2f, 0.8f, 0.3f, 0.4f, 0.5f, 0.7f, 0.6f)
        val cols = downsamplePeaks(ChannelPeaks(min, max), 0..7, 2)
        assertEquals(2, cols.size)
        assertEquals(PeakColumn(-0.9f, 0.8f), cols[0]) // deepest min + tallest max in buckets 0..3
        assertEquals(PeakColumn(-0.6f, 0.7f), cols[1]) // buckets 4..7
    }

    @Test
    fun `downsamplePeaks preserves a lone spike through heavy downsampling`() {
        val min = FloatArray(1000)
        val max = FloatArray(1000)
        max[473] = 0.95f
        val cols = downsamplePeaks(ChannelPeaks(min, max), 0..999, 10)
        assertEquals(10, cols.size)
        assertEquals(0.95f, cols.maxOf { it.max })
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:test --tests 'com.multiviewer.ui.AudioWaveformPeaksTest'`
Expected: FAIL — `waveformBucketCountFor` / `downsamplePeaks` / `PeakColumn` unresolved.

- [ ] **Step 3: Write the implementation**

In `AudioWaveformPeaks.kt`, after the `WAVEFORM_PEAK_BUCKET_COUNT` constant (line ~20):

```kotlin
// Peak buckets scale with file length so a zoomed-in view (the player's default is a 5-second
// window) still has ~one bucket per screen pixel of real detail. 300 buckets/sec ~= 3.3 ms.
// Floored at the old fixed count and capped so a multi-hour file stays bounded (1.8M buckets ~=
// 14 MB per stereo channel pair of min+max floats). The decode pass in computeWaveformPeaks reads
// every sample regardless of bucket count, so a finer array costs memory, not I/O.
fun waveformBucketCountFor(durationSeconds: Double): Int =
    (durationSeconds * 300.0).toInt().coerceIn(WAVEFORM_PEAK_BUCKET_COUNT, 1_800_000)

// One vertical span to draw at one screen x-pixel.
data class PeakColumn(val min: Float, val max: Float)

// Collapses the buckets in visibleRange down to at most targetColumns (min,max) spans -- one per
// screen pixel -- so the Canvas draws O(width) lines regardless of how many buckets the range
// spans. When the range already fits in targetColumns, each bucket is returned as its own column
// unchanged. An all-silent span yields PeakColumn(0f, 0f).
fun downsamplePeaks(peaks: ChannelPeaks, visibleRange: IntRange, targetColumns: Int): List<PeakColumn> {
    val first = visibleRange.first
    val last = visibleRange.last
    val count = last - first + 1
    if (count <= 0 || targetColumns <= 0) return emptyList()
    val size = minOf(peaks.min.size, peaks.max.size)
    if (count <= targetColumns) {
        return (first..last).map { i ->
            if (i in 0 until size) PeakColumn(peaks.min[i], peaks.max[i]) else PeakColumn(0f, 0f)
        }
    }
    val result = ArrayList<PeakColumn>(targetColumns)
    for (col in 0 until targetColumns) {
        val lo = first + (col.toLong() * count / targetColumns).toInt()
        val hi = (first + ((col + 1).toLong() * count / targetColumns).toInt()).coerceAtMost(size)
        var mn = Float.MAX_VALUE
        var mx = -Float.MAX_VALUE
        var b = lo.coerceAtLeast(0)
        while (b < hi) {
            if (peaks.min[b] < mn) mn = peaks.min[b]
            if (peaks.max[b] > mx) mx = peaks.max[b]
            b++
        }
        result.add(if (mn == Float.MAX_VALUE) PeakColumn(0f, 0f) else PeakColumn(mn, mx))
    }
    return result
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:test --tests 'com.multiviewer.ui.AudioWaveformPeaksTest'`
Expected: PASS (existing + 7 new).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/AudioWaveformPeaks.kt app/src/test/kotlin/com/multiviewer/ui/AudioWaveformPeaksTest.kt
git commit -m "feat: duration-proportional waveform buckets + downsamplePeaks

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_013FCbWJi4EtnSbNjb27Rvyo"
```

---

## Task 2: Renderers draw downsampled columns; player computes fine peaks

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/AudioWaveformPeaks.kt` (`drawChannelPeaks`)
- Modify: `app/src/main/kotlin/com/multiviewer/ui/AudioMinimap.kt` (`drawMinimapWaveform`)
- Modify: `app/src/main/kotlin/com/multiviewer/ui/FfmpegAudioPlayer.kt` (the `computeWaveformPeaks` call)

**Interfaces:**
- Consumes: `downsamplePeaks`, `PeakColumn`, `waveformBucketCountFor` (Task 1).
- Produces: no new public API. Rendering behaviour: waveform + minimap draw ≤ canvas-width lines.

- [ ] **Step 1: Update `drawChannelPeaks`**

In `AudioWaveformPeaks.kt`, replace the body of `private fun DrawScope.drawChannelPeaks(...)`:

```kotlin
private fun DrawScope.drawChannelPeaks(peaks: ChannelPeaks, color: Color, visibleRange: IntRange) {
    val width = size.width
    val height = size.height
    val centerY = height / 2f
    if (width <= 0f) return
    val columns = downsamplePeaks(peaks, visibleRange, width.toInt())
    if (columns.isEmpty()) return
    val strokeWidthPx = 1.5.dp.toPx()
    for ((idx, col) in columns.withIndex()) {
        val x = width * idx / columns.size
        val yTop = centerY - col.max * centerY
        val yBottom = centerY - col.min * centerY
        drawLine(color = color, start = Offset(x, yTop), end = Offset(x, yBottom), strokeWidth = strokeWidthPx)
    }
}
```

- [ ] **Step 2: Update `drawMinimapWaveform`**

In `AudioMinimap.kt`: delete the `MINIMAP_BUCKET_STRIDE` constant (lines ~25-27) and replace `drawMinimapWaveform`:

```kotlin
private fun DrawScope.drawMinimapWaveform(peaks: WaveformPeaks) {
    val channel = peaks.channels.firstOrNull() ?: return
    val width = size.width
    val centerY = size.height / 2f
    val bucketCount = channel.min.size
    if (bucketCount == 0 || width <= 0f) return
    val columns = downsamplePeaks(channel, 0 until bucketCount, width.toInt())
    for ((idx, col) in columns.withIndex()) {
        val x = width * idx / columns.size
        val yTop = centerY - col.max * centerY
        val yBottom = centerY - col.min * centerY
        drawLine(
            color = Color(0xFF39FF14).copy(alpha = 0.6f),
            start = Offset(x, yTop), end = Offset(x, yBottom), strokeWidth = 1f,
        )
    }
}
```

(The comment above the deleted constant referenced "the same 4096-bucket array"; remove/adjust it — the array is no longer fixed-size.)

- [ ] **Step 3: Pass the fine bucket count in the player**

In `FfmpegAudioPlayer.kt`, `LaunchedEffect(file)`, change the peak computation call (line ~244):

```kotlin
waveformPeaks = withContext(Dispatchers.IO) {
    computeWaveformPeaks(file, info, bucketCount = waveformBucketCountFor(info.duration), rawAudioParams = rawAudioParams)
}
```

- [ ] **Step 4: Build and run the audio suites**

Run: `./gradlew :app:compileKotlin`
Expected: BUILD SUCCESSFUL.

Run: `./gradlew :app:test --tests 'com.multiviewer.ui.AudioWaveformPeaksTest' --tests 'com.multiviewer.ui.AudioZoomPanTest' --tests 'com.multiviewer.ui.FfmpegAudioPlayerTest'`
Expected: PASS — no behavioural test asserts on the Canvas draw, and `visibleBucketRange` is unchanged, so the existing tests must still hold.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/AudioWaveformPeaks.kt app/src/main/kotlin/com/multiviewer/ui/AudioMinimap.kt app/src/main/kotlin/com/multiviewer/ui/FfmpegAudioPlayer.kt
git commit -m "feat: draw downsampled peak columns; compute fine per-file waveform buckets

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_013FCbWJi4EtnSbNjb27Rvyo"
```

---

## Task 3: `followWindow` pure helper

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/AudioZoomPan.kt`
- Test: `app/src/test/kotlin/com/multiviewer/ui/AudioZoomPanTest.kt`

**Interfaces:**
- Consumes: existing `AudioViewWindow`, `clampWindow`.
- Produces: `fun followWindow(displayElapsedSeconds: Double, windowDurationSeconds: Double, totalDurationSeconds: Double): AudioViewWindow`

- [ ] **Step 1: Write the failing tests**

Append to `AudioZoomPanTest.kt`:

```kotlin
    @Test
    fun `followWindow centres the playhead in the middle of a long track`() {
        val w = followWindow(displayElapsedSeconds = 30.0, windowDurationSeconds = 5.0, totalDurationSeconds = 120.0)
        assertEquals(27.5, w.startSeconds, 1e-9)
        assertEquals(5.0, w.durationSeconds, 1e-9)
    }

    @Test
    fun `followWindow pins to the start within the first half-window`() {
        val w = followWindow(displayElapsedSeconds = 1.0, windowDurationSeconds = 5.0, totalDurationSeconds = 120.0)
        assertEquals(0.0, w.startSeconds, 1e-9)
    }

    @Test
    fun `followWindow pins to the end within the last half-window`() {
        val w = followWindow(displayElapsedSeconds = 119.0, windowDurationSeconds = 5.0, totalDurationSeconds = 120.0)
        assertEquals(115.0, w.startSeconds, 1e-9) // total - duration
    }

    @Test
    fun `followWindow with a window at least as long as the track shows the whole track from zero`() {
        val w = followWindow(displayElapsedSeconds = 2.0, windowDurationSeconds = 10.0, totalDurationSeconds = 4.0)
        assertEquals(0.0, w.startSeconds, 1e-9)
        assertEquals(4.0, w.durationSeconds, 1e-9)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:test --tests 'com.multiviewer.ui.AudioZoomPanTest'`
Expected: FAIL — `followWindow` unresolved.

- [ ] **Step 3: Write the implementation**

In `AudioZoomPan.kt`, after `clampWindow`:

```kotlin
// The view window the scrolling waveform uses while playing: keeps displayElapsedSeconds at the
// centre, then clamps so it never runs past either end of the track (so near the ends the
// playhead drifts toward the edge rather than the window showing blank margin).
fun followWindow(
    displayElapsedSeconds: Double,
    windowDurationSeconds: Double,
    totalDurationSeconds: Double,
): AudioViewWindow =
    clampWindow(displayElapsedSeconds - windowDurationSeconds / 2.0, windowDurationSeconds, totalDurationSeconds)
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:test --tests 'com.multiviewer.ui.AudioZoomPanTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/AudioZoomPan.kt app/src/test/kotlin/com/multiviewer/ui/AudioZoomPanTest.kt
git commit -m "feat: followWindow helper for the scrolling waveform view

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_013FCbWJi4EtnSbNjb27Rvyo"
```

---

## Task 4: Scroll-follow integration in `FfmpegAudioPlayer`

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/FfmpegAudioPlayer.kt`

**Interfaces:**
- Consumes: `followWindow` (Task 3), `waveformBucketCountFor` (already wired in Task 2).
- Produces: no new public API. Behaviour: while playing, `visibleWindow` follows the interpolated playhead at frame rate; default window is 5 s.

- [ ] **Step 1: Add `withFrameNanos` import and change the default window**

Add import: `import androidx.compose.runtime.withFrameNanos`.

Change the `visibleWindow` initializer (line ~360):

```kotlin
// Default to a 5-second detail window -- the waveform/spectrogram scroll to follow the playhead
// while playing (see the follow LaunchedEffect below). The minimap remains the whole-track view.
var visibleWindow by remember(file) { mutableStateOf(AudioViewWindow(0.0, minOf(5.0, info.duration.coerceAtLeast(0.5)))) }
```

- [ ] **Step 2: Add the smoothed-playhead state and the follow coroutine**

Immediately after the `visibleWindow` declaration:

```kotlin
// Playhead position for display, interpolated to frame rate. playedSeconds only updates ~20x/sec
// (once per PCM read chunk), which would step-scroll the waveform; between samples this advances
// at wall-clock rate. Only meaningful while isPlaying -- otherwise use elapsedSeconds directly.
var smoothElapsed by remember(file) { mutableStateOf(0.0) }
val displayElapsed = if (isPlaying) smoothElapsed else elapsedSeconds

// While playing: keep the playhead centred in visibleWindow every frame. Wheel-zoom still works
// (it changes durationSeconds; this re-centres with the new width next frame). Panning / scrollbar
// drags are overwritten here until playback is paused. Cancelled (loop ends) when isPlaying flips
// false, leaving the window where it stopped; re-keyed on restartTrigger so a seek restarts the
// interpolation cleanly.
LaunchedEffect(isPlaying, restartTrigger) {
    if (!isPlaying) return@LaunchedEffect
    var lastPlayed = playedSeconds
    var lastChangeNanos = System.nanoTime()
    while (true) {
        withFrameNanos {
            if (playedSeconds != lastPlayed) {
                lastPlayed = playedSeconds
                lastChangeNanos = System.nanoTime()
            }
            // Cap the extrapolation so a genuinely stuck sample (e.g. paused at the OS layer)
            // can't run the playhead away.
            val sinceChange = ((System.nanoTime() - lastChangeNanos) / 1e9).coerceIn(0.0, 0.12)
            val interp = (startFromSeconds + lastPlayed + sinceChange)
                .coerceIn(0.0, if (info.duration > 0) info.duration else Double.MAX_VALUE)
            smoothElapsed = interp
            visibleWindow = followWindow(interp, visibleWindow.durationSeconds, info.duration)
        }
    }
}
```

- [ ] **Step 3: Use `displayElapsed` for the playhead marker, caption, and minimap**

In the waveform `Box` progress overlay (line ~451), change `elapsedSeconds` → `displayElapsed`:

```kotlin
val windowProgress = ((displayElapsed - visibleWindow.startSeconds) / visibleWindow.durationSeconds)
    .toFloat().coerceIn(0f, 1f)
```

and the caption just below it:

```kotlin
PreviewCaption(
    "${formatMmSs(displayElapsed)} / ${formatMmSs(info.duration)}",
    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
)
```

In the `AudioMinimap(...)` call (search for `elapsedSeconds = elapsedSeconds` or the positional `elapsedSeconds` argument), pass `displayElapsed`:

```kotlin
AudioMinimap(
    peaks = waveformPeaks,
    window = visibleWindow,
    totalDuration = info.duration,
    elapsedSeconds = displayElapsed,
    onWindowChange = { visibleWindow = it },
    onSeek = { fraction -> seekToFraction(fraction) },
    modifier = ...,
)
```

Leave the spectrogram window-progress overlay (if present, second copy of the `windowProgress` block) on `displayElapsed` too, for consistency.

- [ ] **Step 4: Build and verify existing tests**

Run: `./gradlew :app:compileKotlin`
Expected: BUILD SUCCESSFUL.

Run: `./gradlew :app:test --tests 'com.multiviewer.ui.*Audio*' --tests 'com.multiviewer.ui.FfmpegAudioPlayerTest'`
Expected: PASS (no new tests — this is Compose timing wiring, verified manually).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/FfmpegAudioPlayer.kt
git commit -m "feat: scrolling waveform that follows the playhead while playing

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_013FCbWJi4EtnSbNjb27Rvyo"
```

---

## Task 5: Channel solo — `channelModeFilterArgs`, pipe wiring, toggle UI

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/FfmpegAudioPlayer.kt`
- Test: `app/src/test/kotlin/com/multiviewer/ui/FfmpegAudioPlayerTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  enum class ChannelMode { STEREO, LEFT, RIGHT }
  fun channelModeFilterArgs(mode: ChannelMode): List<String>
  ```

- [ ] **Step 1: Write the failing test**

Append to `FfmpegAudioPlayerTest.kt` (imports include `kotlin.test.Test`, `assertEquals`):

```kotlin
    @Test
    fun `channelModeFilterArgs maps each mode to the right ffmpeg pan filter`() {
        assertEquals(emptyList(), channelModeFilterArgs(ChannelMode.STEREO))
        assertEquals(listOf("-af", "pan=stereo|c0=c0|c1=c0"), channelModeFilterArgs(ChannelMode.LEFT))
        assertEquals(listOf("-af", "pan=stereo|c0=c1|c1=c1"), channelModeFilterArgs(ChannelMode.RIGHT))
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:test --tests 'com.multiviewer.ui.FfmpegAudioPlayerTest'`
Expected: FAIL — `ChannelMode` / `channelModeFilterArgs` unresolved.

- [ ] **Step 3: Add the enum + pure function**

In `FfmpegAudioPlayer.kt`, at top level (near the other top-level helpers, e.g. above `FfmpegAudioPlayer`):

```kotlin
// Solo one channel of a stereo file for listening. The soloed channel is sent to BOTH output
// channels (so it plays in both ears); output stays 2ch so the SourceDataLine format is unchanged.
enum class ChannelMode { STEREO, LEFT, RIGHT }

fun channelModeFilterArgs(mode: ChannelMode): List<String> = when (mode) {
    ChannelMode.STEREO -> emptyList()
    ChannelMode.LEFT -> listOf("-af", "pan=stereo|c0=c0|c1=c0")
    ChannelMode.RIGHT -> listOf("-af", "pan=stereo|c0=c1|c1=c1")
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:test --tests 'com.multiviewer.ui.FfmpegAudioPlayerTest'`
Expected: PASS.

- [ ] **Step 5: Add `channelMode` state and wire it into the ffmpeg pipe**

In `FfmpegAudioPlayer`, with the other `remember(file)` state (near line ~200):

```kotlin
var channelMode by remember(file) { mutableStateOf(ChannelMode.STEREO) }
```

In `DisposableEffect(file, restartTrigger)`, insert the filter args into the ffmpeg command — after `"-map", "0:a:0"` and before the `"-f", "s16le"` output block (line ~279-283):

```kotlin
val process = try {
    ProcessBuilder(
        listOf(FfmpegLocator.ffmpegPath()) + seekArgs + rawInputArgs + listOf(
            "-i", inputFile.absolutePath, "-map", "0:a:0",
        ) + channelModeFilterArgs(channelMode) + listOf(
            "-f", "s16le", "-ar", sampleRate.toString(), "-ac", channels.toString(),
            "-acodec", "pcm_s16le", "-",
        ),
    ).redirectError(ProcessBuilder.Redirect.DISCARD)
    .also { FfmpegLocator.configureEnvironment(it) }.start().also {
        com.multiviewer.util.ProcessManager.register(it)
    }
} catch (e: Exception) {
    null
}
```

`channelMode` is read inside the `DisposableEffect`; the toggle (next step) bumps `restartTrigger`, which re-runs the effect with the new mode. `-ac channels` stays 2 for a stereo file, matching `pan=stereo`'s 2-channel output.

- [ ] **Step 6: Add the segmented toggle UI**

The toggle is shown only for stereo files (`info.channels == 2`). Place it in the controls area of the waveform `Box` — e.g. top-left, mirroring the bottom-right `PreviewCaption`. Add this inside the waveform `Box` (the `.weight(waveformSplit)` one), as a sibling of the existing overlays:

```kotlin
if (info.channels == 2) {
    Row(
        modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ChannelMode.entries.forEach { mode ->
            val selected = mode == channelMode
            val label = when (mode) {
                ChannelMode.STEREO -> "Stereo"
                ChannelMode.LEFT -> "L"
                ChannelMode.RIGHT -> "R"
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (selected) Color(0xFF39FF14).copy(alpha = 0.25f) else Color.White.copy(alpha = 0.10f))
                    .clickable {
                        if (mode != channelMode) {
                            channelMode = mode
                            hasEnded = false
                            startFromSeconds = elapsedSeconds
                            playedSeconds = 0.0
                            restartTrigger++
                        }
                    }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    label,
                    color = if (selected) Color(0xFF39FF14) else Color.White.copy(alpha = 0.7f),
                    fontSize = 10.sp,
                )
            }
        }
    }
}
```

Add any missing imports: `androidx.compose.foundation.layout.Arrangement`, `androidx.compose.foundation.layout.Row`, `androidx.compose.foundation.shape.RoundedCornerShape`, `androidx.compose.ui.unit.sp`, `androidx.compose.material3.Text` (likely already imported — check).

The position-preserving restart mirrors `seekToFraction`: `isPlayingAtomic` is untouched so playback state carries across the restart (the reader thread reads it at the top of its loop after `DisposableEffect` re-runs).

- [ ] **Step 7: Build and run the full suite**

Run: `./gradlew :app:compileKotlin`
Expected: BUILD SUCCESSFUL.

Run: `./gradlew :app:test`
Expected: PASS — full suite green.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/FfmpegAudioPlayer.kt app/src/test/kotlin/com/multiviewer/ui/FfmpegAudioPlayerTest.kt
git commit -m "feat: Stereo/L/R channel solo in the audio player

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_013FCbWJi4EtnSbNjb27Rvyo"
```

---

## Task 6: Manual verification

**Files:** none.

- [ ] **Step 1: Build and run**

Run: `./gradlew :app:run`

- [ ] **Step 2: Walk the checklist** (macOS)

- [ ] Open a stereo music file → waveform shows a ~5 s window, not the whole file.
- [ ] Press play → waveform scrolls smoothly (no visible stepping) with the playhead pinned at centre; spectrogram scrolls with it; minimap playhead advances.
- [ ] Scroll-wheel over the waveform while playing → window widens/narrows, still centred, still following.
- [ ] Pause → waveform freezes; drag on it to scrub, wheel-zoom, drag the minimap window — all work manually.
- [ ] Press play again → follow resumes, playhead snaps back to centre.
- [ ] Near the very start / very end → playhead sits off-centre (toward the edge), window does not show blank margin.
- [ ] Open a long file (30+ min) → 5 s zoom still shows real waveform detail, not blocky steps.
- [ ] `[ Stereo | L | R ]` toggle: click L → only the left channel is audible in both ears; click R → only right; click Stereo → both. Playback position is preserved across each switch; if it was playing it keeps playing.
- [ ] Open a mono file → no channel toggle shown, waveform + scroll still work.
- [ ] Open a raw-PCM audio file (Raw Audio dialog) → scroll + solo still work.

- [ ] **Step 3: Record outcome**

If all pass, note "macOS manual pass complete" in the PR/commit body. If anything fails, STOP and use `superpowers:systematic-debugging`.

- [ ] **Step 4: Finish the branch**

Use `superpowers:finishing-a-development-branch`.

---

## Self-Review

**Spec coverage:**
- Peak resolution (duration-proportional + downsampling) → Task 1 + Task 2 ✓
- Scrolling view, 5 s default, centre playhead, `isPlaying`-tied follow, `withFrameNanos` interpolation → Task 4 ✓
- `followWindow` pure helper (spec called it `followWindowStart`; renamed to `followWindow` and made it return the full clamped window so the test is meaningful — noted here) → Task 3 ✓
- Channel solo Stereo/L/R, `-af pan`, position-preserving restart, hidden for mono → Task 5 ✓
- Spectrogram scrolls via shared `visibleWindow` (crop moves, no re-render) → falls out of Task 4 Step 3 (same `visibleWindow`), resolution limitation is a documented non-goal ✓
- Renderer downsampling keeps minimap + zoomed-out waveform fast → Task 2 ✓
- Tests: `waveformBucketCountFor`, `downsamplePeaks`, `followWindow`, `channelModeFilterArgs` → Tasks 1/3/5 ✓

**Placeholder scan:** none. All steps carry complete code.

**Type consistency:** `PeakColumn(min, max)` created in Task 1, consumed by name in Task 2. `downsamplePeaks(peaks, visibleRange, targetColumns)` signature identical across Tasks 1-2. `followWindow(displayElapsedSeconds, windowDurationSeconds, totalDurationSeconds)` identical in Task 3 def and Task 4 call. `ChannelMode` / `channelModeFilterArgs` identical in Task 5 def and test. `smoothElapsed` / `displayElapsed` names consistent within Task 4.

**Deviation from spec:** helper renamed `followWindowStart` → `followWindow` and returns `AudioViewWindow` (clamp folded in) instead of a bare start double — a reviewer should confirm this is fine (it makes the unit test exercise real centering + end-clamp behaviour rather than a trivial subtraction). Flagged for the task reviewer.
