# GoldWave-style Waveform Audio Player Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rework the audio player into a GoldWave/Audacity-style waveform-only player: full-file waveform with L/R lanes, a playhead driven by the actually-rendered playback position, page-scroll follow, click-to-seek, zoom, an explicit transport bar (rewind / play-pause / stop), an in-player Open button, and an `MM:SS.mmm` time readout.

**Architecture:** `FfmpegAudioPlayer` keeps its ffmpeg-PCM-to-`SourceDataLine` reader thread but the displayed cursor comes from `SourceDataLine.microsecondPosition` (mixer clock, drift-free) instead of a byte accumulator. A `withFrameNanos` coroutine reads it each frame and page-scrolls the `AudioViewWindow`. The waveform and transport bar are extracted into `AudioWaveformView.kt` / `AudioTransportBar.kt`. The spectrogram, minimap, zoom-scrollbar and channel-solo listening toggle are removed.

**Tech Stack:** Kotlin 2.0.21, Compose for Desktop 1.7.3, `javax.sound.sampled`, ffmpeg subprocess, JUnit5 + `kotlin.test`.

## Global Constraints

- Kotlin 2.0.21. Do NOT touch `app/build.gradle.kts`.
- Every ffmpeg/ffprobe `ProcessBuilder` calls `FfmpegLocator.configureEnvironment(it)`. The playback pipe registers with `com.multiviewer.util.ProcessManager` and is terminated on dispose.
- `formatMmSs` / `formatMmSsMs` (in `FfmpegVideoPlayer.kt`) are used by the video player — do NOT change them.
- Commit messages end with exactly (blank line before the trailers):
  ```
  Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_013FCbWJi4EtnSbNjb27Rvyo
  ```
- `AudioViewWindow` / `clampWindow` / `MIN_VISIBLE_DURATION_SECONDS` (in `AudioZoomPan.kt`) and `visibleBucketRange` / `computeWaveformPeaks` / `downsamplePeaks` / `forEachPeakColumn` / `PeakColumn` / `waveformBucketCountFor` (in `AudioWaveformPeaks.kt`) are kept — their existing tests must stay green.
- Zoom-in floor is `MIN_VISIBLE_DURATION_SECONDS` (0.5 s) — the minimum `clampWindow` already enforces. Do not add a second cap.

## File Structure

| File | Responsibility |
|---|---|
| `AudioZoomPan.kt` | View-window type + `clampWindow` (kept). **Add** `pageScrollView`, `zoomAround`, `zoomPercent`. **Remove** `followWindow` (Task 5). |
| `AudioWaveformView.kt` | **New.** The waveform Composable — L/R (or mono) lanes from peaks, playhead, click-to-seek, wheel-to-zoom, time-axis ticks. Pure helpers `formatMinSecMillis`, `niceTimeStep`. |
| `AudioTransportBar.kt` | **New.** Stateless transport/zoom/open bar. |
| `FfmpegAudioPlayer.kt` | Rewritten `@Composable` body + reader thread (microsecond clock). Orphan funcs (`generateSpectrogramImage`, `renderAudioVisualization`, `ChannelMode`, `channelModeFilterArgs`, `AudioZoomScrollbar`) removed in Task 5. |
| `AudioWaveformPeaks.kt` | Peak data + helpers (kept). `WaveformDisplay` / `WaveformChannelCanvas` / `drawChannelPeaks` removed in Task 5 (logic re-homed in `AudioWaveformView.kt`). |
| `AudioSpectrogramDisplay.kt`, `AudioMinimap.kt` | **Deleted** (Task 5). |
| `AudioInspectorUI.kt` | Build + pass `onOpenAudio`. |

Existing helpers available: `DecodingIndicator(label, modifier)` (Components.kt); `RawAudioParams`, `computeRawAudioDuration`, `rawAudioSourceFile` (RawAudioDecoder.kt); `AppState.openFile(File)` (AppState.kt); `FfmpegLocator.ffmpegPath()` / `.configureEnvironment`.

---

## Task 1: Pure helpers — `pageScrollView`, `zoomAround`, `zoomPercent`, `formatMinSecMillis`, `niceTimeStep`

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/AudioZoomPan.kt`
- Create: `app/src/main/kotlin/com/multiviewer/ui/AudioWaveformView.kt` (helpers only for now)
- Modify: `app/src/test/kotlin/com/multiviewer/ui/AudioZoomPanTest.kt`
- Create: `app/src/test/kotlin/com/multiviewer/ui/AudioWaveformViewTest.kt`

**Interfaces produced:**
```kotlin
fun pageScrollView(cursorSeconds: Double, view: AudioViewWindow, totalDurationSeconds: Double): AudioViewWindow?
fun zoomAround(view: AudioViewWindow, anchorSeconds: Double, newSpanSeconds: Double, totalDurationSeconds: Double): AudioViewWindow
fun zoomPercent(view: AudioViewWindow, totalDurationSeconds: Double): Int
fun formatMinSecMillis(seconds: Double): String
fun niceTimeStep(spanSeconds: Double): Double
```
This task does NOT remove `followWindow` yet (the current `FfmpegAudioPlayer` still uses it) — that's Task 5.

- [ ] **Step 1: Write the failing tests**

Append to `AudioZoomPanTest.kt` (imports: `kotlin.test.Test`, `assertEquals`, `assertNull`, `assertTrue`):

```kotlin
    @Test
    fun `pageScrollView returns null while the cursor is inside the visible span`() {
        assertNull(pageScrollView(cursorSeconds = 12.0, view = AudioViewWindow(10.0, 5.0), totalDurationSeconds = 120.0))
    }

    @Test
    fun `pageScrollView pages forward when the cursor passes 90 percent of the span`() {
        // view 10..15, 90% edge at 14.5; cursor 14.6 -> page so cursor sits at 10% from the left
        val w = pageScrollView(cursorSeconds = 14.6, view = AudioViewWindow(10.0, 5.0), totalDurationSeconds = 120.0)!!
        assertEquals(14.6 - 0.5, w.startSeconds, 1e-9) // cursor - span*0.1
        assertEquals(5.0, w.durationSeconds, 1e-9)
    }

    @Test
    fun `pageScrollView pages back when the cursor is left of the span`() {
        val w = pageScrollView(cursorSeconds = 3.0, view = AudioViewWindow(10.0, 5.0), totalDurationSeconds = 120.0)!!
        assertEquals(3.0 - 0.5, w.startSeconds, 1e-9)
    }

    @Test
    fun `pageScrollView returns null at 100 percent zoom`() {
        assertNull(pageScrollView(cursorSeconds = 50.0, view = AudioViewWindow(0.0, 120.0), totalDurationSeconds = 120.0))
    }

    @Test
    fun `zoomAround keeps the anchor time under the same fraction of the view`() {
        // anchor 12.0 is at fraction 0.4 of view 10..15; after zooming to span 2.0 it must still be at 0.4 -> start 11.2
        val w = zoomAround(view = AudioViewWindow(10.0, 5.0), anchorSeconds = 12.0, newSpanSeconds = 2.0, totalDurationSeconds = 120.0)
        assertEquals(11.2, w.startSeconds, 1e-9)
        assertEquals(2.0, w.durationSeconds, 1e-9)
    }

    @Test
    fun `zoomAround clamps at the track end`() {
        val w = zoomAround(view = AudioViewWindow(110.0, 10.0), anchorSeconds = 119.0, newSpanSeconds = 5.0, totalDurationSeconds = 120.0)
        assertEquals(115.0, w.startSeconds, 1e-9) // total - span
    }

    @Test
    fun `zoomPercent is 100 at full span and scales inversely`() {
        assertEquals(100, zoomPercent(AudioViewWindow(0.0, 120.0), 120.0))
        assertEquals(250, zoomPercent(AudioViewWindow(0.0, 48.0), 120.0))
    }
```

Create `AudioWaveformViewTest.kt`:

```kotlin
package com.multiviewer.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AudioWaveformViewTest {

    @Test
    fun `formatMinSecMillis zero-pads minutes and seconds and shows three ms digits`() {
        assertEquals("00:00.000", formatMinSecMillis(0.0))
        assertEquals("00:32.450", formatMinSecMillis(32.45))
        assertEquals("03:15.820", formatMinSecMillis(195.82))
        assertEquals("125:03.900", formatMinSecMillis(7503.9))
    }

    @Test
    fun `formatMinSecMillis clamps negatives to zero`() {
        assertEquals("00:00.000", formatMinSecMillis(-5.0))
    }

    @Test
    fun `niceTimeStep gives between 3 and 12 ticks across the span and is non-decreasing`() {
        var prev = 0.0
        for (span in listOf(0.2, 0.5, 1.0, 3.0, 8.0, 20.0, 60.0, 200.0, 900.0, 3600.0)) {
            val step = niceTimeStep(span)
            assertTrue(step > 0.0)
            val ticks = span / step
            assertTrue(ticks in 3.0..12.0, "span=$span step=$step ticks=$ticks")
            assertTrue(step >= prev, "step decreased at span=$span")
            prev = step
        }
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:test --tests 'com.multiviewer.ui.AudioZoomPanTest' --tests 'com.multiviewer.ui.AudioWaveformViewTest'`
Expected: FAIL — the new functions are unresolved.

- [ ] **Step 3: Implement**

In `AudioZoomPan.kt`, after `followWindow` (leave `followWindow` in place):

```kotlin
// The view after the playhead has left the visible span during playback (page-scroll, like
// GoldWave): when the cursor passes 90% of the width, jump forward so it reappears at ~10% from
// the left; a backward seek that lands left of the view pages the same way. Returns null when no
// paging is needed (cursor still comfortably inside, or fully zoomed out).
fun pageScrollView(
    cursorSeconds: Double,
    view: AudioViewWindow,
    totalDurationSeconds: Double,
): AudioViewWindow? {
    if (view.durationSeconds >= totalDurationSeconds) return null
    val pastRight = cursorSeconds >= view.startSeconds + view.durationSeconds * 0.9
    val pastLeft = cursorSeconds < view.startSeconds
    if (!pastRight && !pastLeft) return null
    return clampWindow(cursorSeconds - view.durationSeconds * 0.1, view.durationSeconds, totalDurationSeconds)
}

// Zoom to newSpanSeconds while keeping anchorSeconds under the same fraction of the viewport it
// occupied before (so wheel-zoom stays put under the cursor). Clamped to the track.
fun zoomAround(
    view: AudioViewWindow,
    anchorSeconds: Double,
    newSpanSeconds: Double,
    totalDurationSeconds: Double,
): AudioViewWindow {
    val frac = if (view.durationSeconds <= 0.0) 0.5
        else ((anchorSeconds - view.startSeconds) / view.durationSeconds).coerceIn(0.0, 1.0)
    return clampWindow(anchorSeconds - frac * newSpanSeconds, newSpanSeconds, totalDurationSeconds)
}

// 100% == whole file visible; scales inversely with the visible span.
fun zoomPercent(view: AudioViewWindow, totalDurationSeconds: Double): Int =
    if (view.durationSeconds <= 0.0) 100
    else Math.round(100.0 * totalDurationSeconds / view.durationSeconds).toInt()
```

Create `AudioWaveformView.kt`:

```kotlin
package com.multiviewer.ui

// MM:SS.mmm, minutes zero-padded to 2 (more digits past 100 min). Negative -> 00:00.000.
fun formatMinSecMillis(seconds: Double): String {
    val totalMs = (seconds * 1000).toLong().coerceAtLeast(0L)
    return "%02d:%02d.%03d".format(totalMs / 60_000, (totalMs % 60_000) / 1000, totalMs % 1000)
}

// Time between waveform axis ticks: the smallest "nice" value (1-2-5 x 10^n, plus 0.1/0.2/0.5)
// that yields <= ~10 ticks across the visible span.
private val NICE_STEPS = doubleArrayOf(
    0.05, 0.1, 0.2, 0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 30.0, 60.0, 120.0, 300.0, 600.0, 1800.0, 3600.0,
)

fun niceTimeStep(spanSeconds: Double): Double {
    val target = spanSeconds / 8.0 // aim for ~8 ticks
    return NICE_STEPS.firstOrNull { it >= target } ?: NICE_STEPS.last()
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:test --tests 'com.multiviewer.ui.AudioZoomPanTest' --tests 'com.multiviewer.ui.AudioWaveformViewTest'`
Expected: PASS. (`niceTimeStep` for span 0.2 → target 0.025 → first `>= 0.025` is 0.05 → ticks = 4.0, in [3,12] ✓. span 3600 → target 450 → 600 → 6.0 ✓.)

Also run: `./gradlew :app:compileKotlin` — must still BUILD SUCCESSFUL (pure additions).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/AudioZoomPan.kt app/src/main/kotlin/com/multiviewer/ui/AudioWaveformView.kt app/src/test/kotlin/com/multiviewer/ui/AudioZoomPanTest.kt app/src/test/kotlin/com/multiviewer/ui/AudioWaveformViewTest.kt
git commit -m "feat: pure helpers for the GoldWave audio player (page-scroll, zoom, time fmt)

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_013FCbWJi4EtnSbNjb27Rvyo"
```

---

## Task 2: `AudioWaveformView` Composable

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/AudioWaveformView.kt`

**Interfaces produced:**
```kotlin
@Composable
fun AudioWaveformView(
    peaks: WaveformPeaks?,
    view: AudioViewWindow,
    totalDurationSeconds: Double,
    cursorSeconds: Double,
    onSeekTo: (seconds: Double) -> Unit,
    onZoom: (scrollDeltaY: Float, anchorSeconds: Double) -> Unit,
    modifier: Modifier = Modifier,
)
```

**Interfaces consumed:** `WaveformPeaks` / `ChannelPeaks` / `forEachPeakColumn` / `visibleBucketRange` (AudioWaveformPeaks.kt), `AudioViewWindow` (AudioZoomPan.kt), `niceTimeStep` / `formatMinSecMillis` (Task 1), `DecodingIndicator` (Components.kt).

- [ ] **Step 1: Implement the Composable**

Add to `AudioWaveformView.kt` (imports: `androidx.compose.foundation.Canvas`, `androidx.compose.foundation.background`, `androidx.compose.foundation.gestures.awaitEachGesture`, `androidx.compose.foundation.gestures.awaitFirstDown`, `androidx.compose.foundation.layout.*`, `androidx.compose.runtime.*`, `androidx.compose.ui.Alignment`, `androidx.compose.ui.Modifier`, `androidx.compose.ui.geometry.Offset`, `androidx.compose.ui.graphics.Color`, `androidx.compose.ui.graphics.drawscope.DrawScope`, `androidx.compose.ui.input.pointer.*`, `androidx.compose.ui.text.*` for `rememberTextMeasurer`/`drawText`, `androidx.compose.ui.unit.dp`, `androidx.compose.ui.unit.sp`):

```kotlin
private val WAVE_COLOR = Color(0xFF39FF14)
private val PLAYHEAD_COLOR = Color.White
private val AXIS_COLOR = Color.White.copy(alpha = 0.35f)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AudioWaveformView(
    peaks: WaveformPeaks?,
    view: AudioViewWindow,
    totalDurationSeconds: Double,
    cursorSeconds: Double,
    onSeekTo: (seconds: Double) -> Unit,
    onZoom: (scrollDeltaY: Float, anchorSeconds: Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    // rememberUpdatedState so the gesture lambdas (pointerInput restarts only on totalDurationSeconds)
    // read the live view, not the one captured at first composition.
    val liveView by rememberUpdatedState(view)
    val liveTotal by rememberUpdatedState(totalDurationSeconds)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPointerEvent(PointerEventType.Scroll, pass = PointerEventPass.Initial) { event ->
                val change = event.changes.firstOrNull() ?: return@onPointerEvent
                val w = size.width.toFloat()
                if (w > 0f) {
                    val anchor = liveView.startSeconds + (change.position.x / w) * liveView.durationSeconds
                    onZoom(change.scrollDelta.y, anchor)
                }
                event.changes.forEach { it.consume() }
            }
            .pointerInput(totalDurationSeconds) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val w = size.width.toFloat()
                    if (w > 0f) {
                        onSeekTo(liveView.startSeconds + (down.position.x / w) * liveView.durationSeconds)
                    }
                    // no drag: consume the down so it doesn't fall through, but ignore movement
                    down.consume()
                }
            },
    ) {
        if (peaks == null) {
            DecodingIndicator("파형 생성 중...", modifier = Modifier.align(Alignment.Center))
            return@Box
        }
        val channels = peaks.channels.take(2)
        val visibleRange = visibleBucketRange(view, totalDurationSeconds, peaks.bucketCount)
        val cursorFrac = ((cursorSeconds - view.startSeconds) / view.durationSeconds).toFloat()
        val cursorVisible = cursorFrac in 0f..1f

        Column(Modifier.fillMaxSize()) {
            channels.forEachIndexed { idx, ch ->
                val label = if (channels.size == 2) (if (idx == 0) "L" else "R") else null
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawLaneWaveform(ch, visibleRange)
                        if (cursorVisible) drawPlayhead(cursorFrac)
                        drawTimeAxis(view, textMeasurerOrNull = null)   // ticks drawn without labels here; see below
                    }
                    if (label != null) {
                        Text(label, color = WAVE_COLOR.copy(alpha = 0.7f), fontSize = 9.sp,
                            modifier = Modifier.align(Alignment.TopStart).padding(2.dp))
                    }
                }
            }
        }
        // Time labels overlaid across the whole area (top edge), one text layer not per-lane.
        WaveformTimeLabels(view = view, modifier = Modifier.align(Alignment.TopStart).fillMaxWidth())
    }
}

private fun DrawScope.drawLaneWaveform(peaks: ChannelPeaks, visibleRange: IntRange) {
    val w = size.width
    val centerY = size.height / 2f
    if (w <= 0f) return
    forEachPeakColumn(peaks, visibleRange, w.toInt()) { idx, n, mn, mx ->
        val x = w * idx / n
        drawLine(WAVE_COLOR, Offset(x, centerY - mx * centerY), Offset(x, centerY - mn * centerY), strokeWidth = 1f)
    }
    drawLine(AXIS_COLOR, Offset(0f, centerY), Offset(w, centerY), strokeWidth = 1f) // zero line
}

private fun DrawScope.drawPlayhead(fraction: Float) {
    val x = size.width * fraction
    drawLine(PLAYHEAD_COLOR, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2f)
}

private fun DrawScope.drawTimeAxis(view: AudioViewWindow, textMeasurerOrNull: Any?) {
    val step = niceTimeStep(view.durationSeconds)
    var t = kotlin.math.ceil(view.startSeconds / step) * step
    val end = view.startSeconds + view.durationSeconds
    while (t <= end) {
        val x = ((t - view.startSeconds) / view.durationSeconds * size.width).toFloat()
        drawLine(AXIS_COLOR, Offset(x, 0f), Offset(x, size.height * 0.06f), strokeWidth = 1f)
        drawLine(AXIS_COLOR, Offset(x, size.height * 0.94f), Offset(x, size.height), strokeWidth = 1f)
        t += step
    }
}

@Composable
private fun WaveformTimeLabels(view: AudioViewWindow, modifier: Modifier = Modifier) {
    val step = niceTimeStep(view.durationSeconds)
    Box(modifier.height(12.dp)) {
        var t = kotlin.math.ceil(view.startSeconds / step) * step
        val end = view.startSeconds + view.durationSeconds
        val labels = buildList {
            while (t <= end) { add(t); t += step }
        }
        labels.forEach { time ->
            val frac = ((time - view.startSeconds) / view.durationSeconds).toFloat()
            Text(
                text = if (step < 1.0) "%.1f".format(time) else formatMinSecMillis(time).substringBeforeLast('.'),
                color = AXIS_COLOR, fontSize = 8.sp,
                modifier = Modifier.align(Alignment.TopStart).offset { IntOffset((frac * this.size.width).toInt(), 0) },
            )
        }
    }
}
```

> The `WaveformTimeLabels` `offset {}` block needs the parent width; if that's awkward, a `Layout` or `BoxWithConstraints` measuring `constraints.maxWidth` and placing `Text` at `frac * maxWidth` is fine. The implementer may adjust the tick/label rendering as long as: ticks/labels reflect `niceTimeStep(view.durationSeconds)`, labels are `M:SS` (or `%.1f` when step < 1 s), and they don't block gestures on the waveform (`Modifier` with no pointer input).

- [ ] **Step 2: Build and run existing tests**

Run: `./gradlew :app:compileKotlin`
Expected: BUILD SUCCESSFUL. (`AudioWaveformView` is new and unused; nothing else changes.)

Run: `./gradlew :app:test --tests 'com.multiviewer.ui.AudioWaveformViewTest' --tests 'com.multiviewer.ui.AudioWaveformPeaksTest'`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/AudioWaveformView.kt
git commit -m "feat: AudioWaveformView -- L/R lanes, playhead, click-seek, wheel-zoom, time axis

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_013FCbWJi4EtnSbNjb27Rvyo"
```

---

## Task 3: `AudioTransportBar` Composable

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/ui/AudioTransportBar.kt`

**Interfaces produced:**
```kotlin
@Composable
fun AudioTransportBar(
    isPlaying: Boolean,
    cursorSeconds: Double,
    totalSeconds: Double,
    zoomPercentValue: Int,
    canZoomOut: Boolean,
    canZoomIn: Boolean,
    onOpenAudio: (() -> Unit)?,
    onRewind: () -> Unit,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onZoomOut: () -> Unit,
    onZoomIn: () -> Unit,
    modifier: Modifier = Modifier,
)
```

**Interfaces consumed:** `formatMinSecMillis` (Task 1), `Icons.Filled.PlayArrow` (material-icons-core), the existing hand-drawn-icon pattern.

- [ ] **Step 1: Implement**

`AudioTransportBar.kt` — a `Column` of: (a) header `Row` with `[ Open Audio ]` (TextButton, only if `onOpenAudio != null`) on the left, a `Spacer(Modifier.weight(1f))`, and `Text("${formatMinSecMillis(cursorSeconds)} / ${formatMinSecMillis(totalSeconds)}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)` on the right; (b) transport `Row` centred (`Arrangement.Center`) with three 28.dp clickable `Box`es: rewind (hand-drawn `◀◀` — two left-pointing triangles), play/pause (`Icons.Filled.PlayArrow` when paused, hand-drawn two-bar pause when playing — reuse the `AudioPauseIcon` pattern; copy it here as a `private @Composable` since the old one is removed in Task 5), stop (a filled square `Box`); (c) zoom `Row`: `Text("Zoom:")`, a `[-]` `Box` (`enabled = canZoomOut`, dimmed + no `clickable` when disabled), `Text("$zoomPercentValue%", Modifier.width(48.dp), textAlign = TextAlign.Center)`, a `[+]` `Box` (`enabled = canZoomIn`).

Icons are all `Canvas`/`Box` primitives + the one core `PlayArrow` — no `material-icons-extended`. Keep each icon ~14.dp inside a ~28.dp hit target. Colours: white / `Color.White.copy(alpha = 0.35f)` for disabled.

- [ ] **Step 2: Build**

Run: `./gradlew :app:compileKotlin`
Expected: BUILD SUCCESSFUL (new, unused).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/AudioTransportBar.kt
git commit -m "feat: AudioTransportBar -- open / time / rewind-play-stop / zoom

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_013FCbWJi4EtnSbNjb27Rvyo"
```

---

## Task 4: Rewrite `FfmpegAudioPlayer` + wire `onOpenAudio`

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/FfmpegAudioPlayer.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/AudioInspectorUI.kt`

**Interfaces produced:** `@Composable fun FfmpegAudioPlayer(file: File, rawAudioParams: RawAudioParams? = null, onOpenAudio: (() -> Unit)? = null, modifier: Modifier = Modifier)`

**Interfaces consumed:** `AudioWaveformView` (Task 2), `AudioTransportBar` (Task 3), `pageScrollView` / `zoomAround` / `zoomPercent` / `clampWindow` / `AudioViewWindow` / `MIN_VISIBLE_DURATION_SECONDS` (Tasks 1 + existing), `computeWaveformPeaks` / `waveformBucketCountFor` / `WaveformPeaks` (existing), `probeAudioFormat` / `AudioFileInfo` (existing, in this file), `rawAudioSourceFile` / `computeRawAudioDuration` / `RawAudioParams` (existing).

This task does NOT delete the orphaned top-level funcs (`generateSpectrogramImage`, `renderAudioVisualization`, `ChannelMode`, `channelModeFilterArgs`, `AudioZoomScrollbar`, `AudioPauseIcon`, the `AUDIO_VISUAL_TIMEOUT_MS` / `ZOOM_STEP_FACTOR` / `PAN_STEP_FACTOR` consts) — leaving them keeps `AudioSpectrogramDisplay.kt` compiling. Task 5 removes them.

- [ ] **Step 1: Rewrite the `@Composable fun FfmpegAudioPlayer` body**

Keep the top-of-file funcs (`probeAudioFormat` etc.) and the orphans. Replace the entire `@Composable fun FfmpegAudioPlayer(...)` (currently ~line 209 to the end of its body ~line 703) with:

```kotlin
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun FfmpegAudioPlayer(
    file: File,
    rawAudioParams: RawAudioParams? = null,
    onOpenAudio: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var isPlaying by remember(file) { mutableStateOf(false) }
    val isPlayingAtomic = remember(file) { AtomicBoolean(false) }
    var hasEnded by remember(file) { mutableStateOf(false) }
    var restartTrigger by remember(file) { mutableStateOf(0) }
    var pipeStartSeconds by remember(file) { mutableStateOf(0.0) }   // -ss of the current pipe
    var cursorSeconds by remember(file) { mutableStateOf(0.0) }      // displayed playhead (absolute)
    var loadError by remember(file) { mutableStateOf(false) }

    var probedInfo by remember(file) { mutableStateOf<AudioFileInfo?>(null) }
    var probing by remember(file) { mutableStateOf(true) }
    var waveformPeaks by remember(file) { mutableStateOf<WaveformPeaks?>(null) }

    // The audio line, published by the reader thread so the follow coroutine can read its clock.
    val lineHolder = remember(file) { java.util.concurrent.atomic.AtomicReference<SourceDataLine?>(null) }

    LaunchedEffect(file) {
        probing = true
        val info = withContext(Dispatchers.IO) {
            if (rawAudioParams != null) {
                AudioFileInfo(
                    rawAudioParams.sampleRate, rawAudioParams.channels,
                    computeRawAudioDuration(file.length(), rawAudioParams.offsetBytes, rawAudioParams.sampleRate,
                        rawAudioParams.channels, rawAudioParams.format.bytesPerSample),
                )
            } else probeAudioFormat(file)
        }
        probedInfo = info
        probing = false
        if (info != null) {
            waveformPeaks = withContext(Dispatchers.IO) {
                computeWaveformPeaks(file, info, bucketCount = waveformBucketCountFor(info.duration), rawAudioParams = rawAudioParams)
            }
        }
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

    var view by remember(file) { mutableStateOf(AudioViewWindow(0.0, info.duration.coerceAtLeast(MIN_VISIBLE_DURATION_SECONDS))) }

    // --- reader thread + audio pipe (respawned on restartTrigger, e.g. every seek) ---
    DisposableEffect(file, restartTrigger) {
        isPlayingAtomic.set(isPlaying)
        val startSec = pipeStartSeconds
        val seekArgs = if (startSec > 0.0) listOf("-ss", startSec.toString()) else emptyList()
        val sr = info.sampleRate
        val ch = info.channels
        val inputFile = if (rawAudioParams != null) rawAudioSourceFile(file, rawAudioParams.offsetBytes) else file
        val rawInputArgs = if (rawAudioParams != null)
            listOf("-f", rawAudioParams.ffmpegFormatCode(), "-ar", rawAudioParams.sampleRate.toString(), "-ac", rawAudioParams.channels.toString())
        else emptyList()
        val process = try {
            ProcessBuilder(
                listOf(FfmpegLocator.ffmpegPath()) + seekArgs + rawInputArgs + listOf(
                    "-i", inputFile.absolutePath, "-map", "0:a:0",
                    "-f", "s16le", "-ar", sr.toString(), "-ac", ch.toString(), "-acodec", "pcm_s16le", "-",
                ),
            ).redirectError(ProcessBuilder.Redirect.DISCARD)
                .also { FfmpegLocator.configureEnvironment(it) }.start()
                .also { com.multiviewer.util.ProcessManager.register(it) }
        } catch (e: Exception) { null }
        if (process == null) loadError = true

        val stopped = AtomicBoolean(false)
        val format = AudioFormat(sr.toFloat(), 16, ch, true, false)

        val readerThread = if (process != null) Thread {
            var line: SourceDataLine? = null
            try {
                line = AudioSystem.getSourceDataLine(format).apply { open(format); start() }
                lineHolder.set(line)
                var wasPlaying = true
                val buf = ByteArray(8192)
                val input = process.inputStream
                var ended = false
                while (!stopped.get()) {
                    if (!isPlayingAtomic.get()) {
                        if (wasPlaying) { line.stop(); wasPlaying = false }
                        Thread.sleep(50); continue
                    }
                    if (!wasPlaying) { line.start(); wasPlaying = true }
                    val n = input.read(buf)
                    if (n < 0) { ended = true; break }
                    line.write(buf, 0, n)
                }
                if (ended && !stopped.get()) {
                    // let the line buffer drain so the cursor reaches the true end
                    val deadline = System.currentTimeMillis() + 2000
                    while (!stopped.get() && System.currentTimeMillis() < deadline &&
                        line.bufferSize - line.available() > 0) Thread.sleep(20)
                    EventQueue.invokeLater { setPlayingRef.get()?.invoke(false); hasEndedRef.get()?.invoke() }
                }
            } catch (e: InterruptedException) {
            } catch (e: Exception) {
                System.err.println("FfmpegAudioPlayer reader thread failed: $e")
            } finally {
                line?.stop(); line?.flush(); line?.close()
                lineHolder.set(null)
                com.multiviewer.util.ProcessManager.terminate(process)
            }
        }.apply { isDaemon = true }.also { it.start() } else null

        onDispose {
            stopped.set(true)
            readerThread?.interrupt()
            com.multiviewer.util.ProcessManager.terminate(process)
            if (inputFile != file) inputFile.delete()
        }
    }
```

> **Implementer note on the EOF callback:** the reader thread must call back onto the composition to set `isPlaying = false` / `hasEnded = true`. The snippet above sketches `setPlayingRef` / `hasEndedRef` holders — implement whatever is cleanest: e.g. `val eofSignal = remember(file) { AtomicBoolean(false) }` set by the thread + a `LaunchedEffect` polling it, OR capture stable lambdas via `rememberUpdatedState`. Keep it on the EDT (`EventQueue.invokeLater`).

- [ ] **Step 2: Add the follow coroutine, seek/zoom/transport handlers, and the layout**

After the `DisposableEffect`:

```kotlin
    fun setPlaying(play: Boolean) {
        if (play && hasEnded) { hasEnded = false; pipeStartSeconds = 0.0; cursorSeconds = 0.0; restartTrigger++ }
        isPlaying = play
        isPlayingAtomic.set(play)
    }

    fun seekToSeconds(t: Double) {
        val clamped = t.coerceIn(0.0, info.duration)
        hasEnded = false
        pipeStartSeconds = clamped
        cursorSeconds = clamped
        if (clamped < view.startSeconds || clamped > view.startSeconds + view.durationSeconds) {
            view = clampWindow(clamped - view.durationSeconds / 2.0, view.durationSeconds, info.duration)
        }
        restartTrigger++
    }

    fun zoomTo(newSpan: Double, anchor: Double) {
        view = zoomAround(view, anchor, newSpan.coerceIn(MIN_VISIBLE_DURATION_SECONDS, info.duration.coerceAtLeast(MIN_VISIBLE_DURATION_SECONDS)), info.duration)
    }

    // Follow: while playing, drive cursorSeconds from the mixer clock and page-scroll the view.
    LaunchedEffect(isPlaying, restartTrigger) {
        if (!isPlaying) return@LaunchedEffect
        while (true) {
            withFrameNanos {
                val line = lineHolder.get()
                val micros = line?.microsecondPosition ?: 0L
                cursorSeconds = (pipeStartSeconds + micros / 1_000_000.0).coerceIn(0.0, info.duration)
                pageScrollView(cursorSeconds, view, info.duration)?.let { view = it }
            }
        }
    }

    Column(modifier.fillMaxSize().background(Color.Black)) {
        AudioTransportHeader(...)   // [ Open Audio ] + time  -- can be part of AudioTransportBar or split; see note
        AudioWaveformView(
            peaks = waveformPeaks, view = view, totalDurationSeconds = info.duration,
            cursorSeconds = cursorSeconds,
            onSeekTo = { seekToSeconds(it) },
            onZoom = { deltaY, anchor ->
                val factor = if (deltaY < 0f) 1.0 / 1.5 else 1.5   // scroll up = zoom in
                zoomTo(view.durationSeconds * factor, anchor)
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        if (view.durationSeconds < info.duration) {
            AudioWaveformScrollbar(view = view, totalDuration = info.duration, onScroll = { view = it })
        }
        AudioTransportBar(
            isPlaying = isPlaying, cursorSeconds = cursorSeconds, totalSeconds = info.duration,
            zoomPercentValue = zoomPercent(view, info.duration),
            canZoomOut = view.durationSeconds < info.duration,
            canZoomIn = view.durationSeconds > MIN_VISIBLE_DURATION_SECONDS,
            onOpenAudio = onOpenAudio,
            onRewind = { seekToSeconds(0.0) },
            onPlayPause = { setPlaying(!isPlaying) },
            onStop = { setPlaying(false); seekToSeconds(0.0); view = clampWindow(0.0, view.durationSeconds, info.duration) },
            onZoomOut = { zoomTo(view.durationSeconds * 1.5, cursorSeconds) },
            onZoomIn = { zoomTo(view.durationSeconds / 1.5, cursorSeconds) },
        )
    }
```

> **Note on the header:** the design's mockup puts `[ Open Audio ]` + time at the *top* and the transport + zoom at the *bottom*. `AudioTransportBar` (Task 3) already contains all four rows. Simplest: render `AudioTransportBar` once at the bottom with everything, OR split its header row out. The implementer picks — the requirement is that all of `[ Open Audio ]`, `MM:SS.mmm / MM:SS.mmm`, `◀◀ ▶/❚❚ ■`, `Zoom [-] % [+]` are present and wired. If splitting, add a small `@Composable fun AudioWaveformScrollbar(view, totalDuration, onScroll)` (a full-width 6.dp strip; thumb at `view.start/total` width `view.duration/total`; `detectDragGestures` + `rememberUpdatedState(view)` + `clampWindow`).

- [ ] **Step 3: Wire `onOpenAudio` in `AudioInspectorUI.kt`**

```kotlin
Column(modifier = Modifier.fillMaxSize()) {
    FfmpegAudioPlayer(
        tab.file,
        rawAudioParams = tab.rawAudioParams,
        onOpenAudio = {
            val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Open audio", java.awt.FileDialog.LOAD)
            appState.lastOpenedDirectory?.let { if (it.isDirectory) dialog.directory = it.absolutePath }
            dialog.isVisible = true
            val dir = dialog.directory
            val name = dialog.file
            if (dir != null && name != null) {
                val picked = java.io.File(dir, name)
                appState.lastOpenedDirectory = java.io.File(dir)
                appState.openFile(picked)
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}
```

Update the file's top comment to drop the "spectrogram" mention.

- [ ] **Step 4: Build and run**

Run: `./gradlew :app:compileKotlin`
Expected: BUILD SUCCESSFUL. The orphaned funcs stay (used by `AudioSpectrogramDisplay.kt` until Task 5) — `compileKotlin` may warn about unused private members; that's fine.

Run: `./gradlew :app:test`
Expected: PASS — the old `FfmpegAudioPlayerTest` cases for `generateSpectrogramImage` / `channelModeFilterArgs` still compile (those funcs still exist) and pass; everything else green.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/FfmpegAudioPlayer.kt app/src/main/kotlin/com/multiviewer/ui/AudioInspectorUI.kt
git commit -m "feat: rewrite FfmpegAudioPlayer as a GoldWave-style waveform player

Mixer-clock playhead (SourceDataLine.microsecondPosition, drift-free), page-scroll
follow, click-seek, wheel/button zoom, rewind/play/stop transport, [Open Audio].

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_013FCbWJi4EtnSbNjb27Rvyo"
```

---

## Task 5: Delete the dead code + prune tests

**Files:**
- Delete: `app/src/main/kotlin/com/multiviewer/ui/AudioSpectrogramDisplay.kt`, `app/src/test/kotlin/com/multiviewer/ui/AudioSpectrogramDisplayTest.kt`, `app/src/main/kotlin/com/multiviewer/ui/AudioMinimap.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/FfmpegAudioPlayer.kt`, `app/src/main/kotlin/com/multiviewer/ui/AudioWaveformPeaks.kt`, `app/src/main/kotlin/com/multiviewer/ui/AudioZoomPan.kt`, `app/src/test/kotlin/com/multiviewer/ui/AudioZoomPanTest.kt`, `app/src/test/kotlin/com/multiviewer/ui/FfmpegAudioPlayerTest.kt`, `app/src/test/kotlin/com/multiviewer/ui/AudioWaveformPeaksTest.kt` (only if it references removed symbols)

- [ ] **Step 1: Delete files**

```bash
git rm app/src/main/kotlin/com/multiviewer/ui/AudioSpectrogramDisplay.kt \
       app/src/test/kotlin/com/multiviewer/ui/AudioSpectrogramDisplayTest.kt \
       app/src/main/kotlin/com/multiviewer/ui/AudioMinimap.kt
```

- [ ] **Step 2: Remove dead code from the surviving files**

- `FfmpegAudioPlayer.kt`: delete `renderAudioVisualization`, `generateSpectrogramImage`, `AUDIO_VISUAL_TIMEOUT_MS`, `ZOOM_STEP_FACTOR`, `PAN_STEP_FACTOR`, `ChannelMode`, `channelModeFilterArgs`, the private `AudioZoomScrollbar`, the private `AudioPauseIcon` (now copied into `AudioTransportBar.kt`). Remove now-unused imports (`ImageBitmap`, `toComposeImageBitmap`, `org.jetbrains.skia.Image`, `TimeUnit`, `Orientation`, `detectDragGestures`, `drag`, `Surface`, `CircleShape`, `RoundedCornerShape`, etc. — let `compileKotlin` warnings guide you).
- `AudioWaveformPeaks.kt`: delete `WaveformDisplay`, `WaveformChannelCanvas`, `drawChannelPeaks` (the Composables — their logic now lives in `AudioWaveformView.kt`). Keep `computeWaveformPeaks`, `waveformBucketCountFor`, `PeakColumn`, `downsamplePeaks`, `forEachPeakColumn`, `visibleBucketRange`, `ChannelPeaks`, `WaveformPeaks`. Remove now-unused imports (`Canvas`, `Column`, `DrawScope`, `Color`, `Offset`, etc.).
- `AudioZoomPan.kt`: delete `followWindow`.

- [ ] **Step 3: Prune tests**

- `AudioZoomPanTest.kt`: delete the four `followWindow …` test methods. Keep everything else (incl. the Task 1 additions).
- `FfmpegAudioPlayerTest.kt`: delete the `generateSpectrogramImage …` test methods and the `channelModeFilterArgs …` test. If the file is left with no tests, delete the file. If it retains tests (e.g. a probe test), keep it.
- `AudioWaveformPeaksTest.kt`: only touch it if it references `WaveformDisplay` (it does not — it tests `computeWaveformPeaks` / `downsamplePeaks` / `visibleBucketRange`). Leave it.

- [ ] **Step 4: Build and run the full suite**

Run: `./gradlew :app:compileKotlin`
Expected: BUILD SUCCESSFUL, no unresolved references.

Run: `./gradlew :app:test`
Expected: PASS — full suite green. Confirm the count dropped only by the deleted spectrogram/channelMode/followWindow cases and rose by Task 1's additions.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "chore: remove the spectrogram, minimap, zoom-scrollbar and channel-solo code

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_013FCbWJi4EtnSbNjb27Rvyo"
```

---

## Task 6: Manual verification + finish

**Files:** none.

- [ ] **Step 1: Build and run**: `./gradlew :app:run`

- [ ] **Step 2: Walk the user's verification list** (macOS)

- [ ] Open a stereo audio file → full-file waveform, L lane above R lane, `[ Open Audio ]` + `00:00.000 / MM:SS.mmm` header, transport + `Zoom: 100%` at the bottom.
- [ ] Press `▶` → playhead sweeps left→right; the displayed time matches what's heard (no drift over a few minutes); at ~90% width the view pages forward and the playhead reappears near the left.
- [ ] `❚❚` then `▶` → playhead resumes exactly where it paused.
- [ ] Click the waveform while paused → the playhead jumps there, playback does NOT start; press `▶` → plays from there. Click while playing → continues from the clicked point.
- [ ] `◀◀` → playhead to 0 (keeps playing if it was); `■` → stops and returns to 0.
- [ ] `[ Open Audio ]` → file picker → picking an audio file loads it.
- [ ] `[+]` / `[-]` and mouse wheel over the waveform → zoom in/out around the cursor / mouse; `%` updates; `[-]` disables at 100%; bottom scrollbar appears when zoomed in and drags the view.
- [ ] Long file (30+ min) → open, scroll, zoom, play — no UI stutter.
- [ ] Mono file → single lane, everything works.
- [ ] Raw-PCM file (Raw Audio dialog) → waveform + playback work.
- [ ] Quit the app mid-playback → `pgrep ffmpeg` shows nothing.

- [ ] **Step 3: Record the outcome** in the finishing report.

- [ ] **Step 4: Finish the branch** — use `superpowers:finishing-a-development-branch`. Report the final merge commit hash.

---

## Self-Review

**Spec coverage:**
- req 1, 2 (waveform, X=time Y=amplitude) → Task 2 lanes ✓
- req 3, 4, 15 (playhead, sync, decoder timestamp) → Task 4 `microsecondPosition` clock + follow coroutine ✓
- req 5 (auto-scroll / page) → Task 1 `pageScrollView` + Task 4 follow ✓
- req 6 (click to seek) → Task 2 gesture + Task 4 `seekToSeconds` ✓
- req 7 (play/pause/stop/seek) → Task 3 bar + Task 4 handlers ✓
- req 8 (`MM:SS.mmm / MM:SS.mmm`) → Task 1 `formatMinSecMillis` + Task 3 header ✓
- req 9 (zoom) → Task 1 `zoomAround`/`zoomPercent` + Task 3 buttons + Task 2 wheel ✓
- req 10 (stereo L/R) → Task 2 lane stacking ✓
- req 11 (downsample peaks) → kept `computeWaveformPeaks` + `forEachPeakColumn` ✓
- req 12 (background analysis) → Task 4 `withContext(Dispatchers.IO) { computeWaveformPeaks(...) }` ✓
- req 13 (compute once, update playhead only) → peaks computed in `LaunchedEffect(file)`; follow coroutine only writes `cursorSeconds`/`view` ✓
- req 14 (30–60 fps) → `withFrameNanos` ✓

**Placeholder scan:** Task 2's `drawTimeAxis` / `WaveformTimeLabels` and Task 4's EOF-callback holder are sketched with an explicit "implementer picks the cleanest form" note and hard requirements listed — acceptable latitude, not a placeholder. Task 3 describes the bar in prose (it's straightforward layout). Task 4 Step 2's header/scrollbar split is left to the implementer with the hard requirement stated. No "TODO"/"TBD".

**Type consistency:** `AudioViewWindow(startSeconds, durationSeconds)` used identically across Tasks 1/2/4. `pageScrollView(cursorSeconds, view, totalDurationSeconds)` / `zoomAround(view, anchorSeconds, newSpanSeconds, totalDurationSeconds)` / `zoomPercent(view, totalDurationSeconds)` signatures identical in Task 1 def and Task 4 calls. `AudioWaveformView(peaks, view, totalDurationSeconds, cursorSeconds, onSeekTo, onZoom, modifier)` and `AudioTransportBar(...)` signatures identical in Tasks 2/3 defs and Task 4 calls. `formatMinSecMillis` used in Tasks 1/2/3.

**Sequencing:** Tree compiles after every task — Task 4 leaves the spectrogram orphans in place so `AudioSpectrogramDisplay.kt` still resolves; Task 5 removes both together.
