# Audio Spectrogram Canvas Rendering Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the audio spectrogram's "re-render via ffmpeg on every zoom/pan/resize" flow with a "render once via ffmpeg when the file opens, then crop/stretch that single bitmap via Canvas" flow, matching how the waveform panel already works.

**Architecture:** A new `AudioSpectrogramDisplay.kt` renders one fixed-size (4096x512) spectrogram bitmap per file via ffmpeg's `showspectrumpic`, then draws a `visibleRange` column-slice of it via `Canvas.drawImage`. `FfmpegAudioPlayer.kt` computes this bitmap once (alongside the existing `waveformPeaks` computation) and removes all per-interaction regeneration code.

**Tech Stack:** Kotlin, Compose Desktop (`Canvas`/`DrawScope.drawImage`), ffmpeg (`showspectrumpic` filter, unchanged, called once instead of repeatedly).

## Global Constraints

- Spectrogram bitmap is rendered exactly once per file open, at a fixed `SPECTROGRAM_WIDTH_PX = 4096` x `SPECTROGRAM_HEIGHT_PX = 512` (matches `WAVEFORM_PEAK_BUCKET_COUNT = 4096` in `AudioWaveformPeaks.kt` for symmetry).
- Zoom/pan/resize never re-invoke ffmpeg for the spectrogram — only `Canvas` redraws.
- Column selection reuses the existing `visibleBucketRange(window, totalDuration, bucketCount)` (`AudioWaveformPeaks.kt`) unchanged, called with `SPECTROGRAM_WIDTH_PX` in place of `peaks.bucketCount`.
- No hand-rolled FFT — ffmpeg's `showspectrumpic` remains the sole spectral data source.
- Playhead overlay, zoom/pan gestures, `AudioZoomScrollbar`, `AudioMinimap`, and the waveform panel are unchanged.

---

### Task 1: `generateFullSpectrogramImage` + `SpectrogramDisplay`

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/ui/AudioSpectrogramDisplay.kt`
- Test: `app/src/test/kotlin/com/multiviewer/ui/AudioSpectrogramDisplayTest.kt`

**Interfaces:**
- Consumes: `generateSpectrogramImage(file: File, width: Int, height: Int, rawAudioParams: RawAudioParams?, window: AudioViewWindow?): ImageBitmap?` (already exists, `FfmpegAudioPlayer.kt`, unchanged — this task calls it with `window = null`). `RawAudioParams` (already exists, same package).
- Produces: `SPECTROGRAM_WIDTH_PX: Int`, `SPECTROGRAM_HEIGHT_PX: Int`, `generateFullSpectrogramImage(file: File, rawAudioParams: RawAudioParams? = null): ImageBitmap?`, `SpectrogramDisplay(bitmap: ImageBitmap, visibleRange: IntRange, modifier: Modifier = Modifier)` composable — Task 2 wires all three into `FfmpegAudioPlayer.kt`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/kotlin/com/multiviewer/ui/AudioSpectrogramDisplayTest.kt`:

```kotlin
package com.multiviewer.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AudioSpectrogramDisplayTest {
    @Test
    fun `renders a fixed-size bitmap for a real audio file`() {
        val audio = File.createTempFile("spectrogram-display-test-", ".wav")
        audio.deleteOnExit()
        ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "sine=duration=1:frequency=440",
            "-ac", "1", "-c:a", "pcm_s16le", audio.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()

        val bitmap = generateFullSpectrogramImage(audio)

        checkNotNull(bitmap)
        assertEquals(SPECTROGRAM_WIDTH_PX, bitmap.width)
        assertEquals(SPECTROGRAM_HEIGHT_PX, bitmap.height)
        audio.delete()
    }

    @Test
    fun `returns null for a file with no decodable audio`() {
        val garbage = File.createTempFile("spectrogram-display-garbage-test-", ".wav")
        garbage.deleteOnExit()
        garbage.writeBytes(ByteArray(100))

        val bitmap = generateFullSpectrogramImage(garbage)

        assertNull(bitmap)
        garbage.delete()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests "com.multiviewer.ui.AudioSpectrogramDisplayTest"`
Expected: FAIL to compile — `generateFullSpectrogramImage`, `SPECTROGRAM_WIDTH_PX`, `SPECTROGRAM_HEIGHT_PX` are unresolved references (they don't exist yet).

- [ ] **Step 3: Write the implementation**

Create `app/src/main/kotlin/com/multiviewer/ui/AudioSpectrogramDisplay.kt`:

```kotlin
package com.multiviewer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import java.io.File

// Matches WAVEFORM_PEAK_BUCKET_COUNT (AudioWaveformPeaks.kt) so both panels share the same
// column-to-time mapping via visibleBucketRange -- see that function's doc comment.
const val SPECTROGRAM_WIDTH_PX = 4096
const val SPECTROGRAM_HEIGHT_PX = 512

// One-time full-file render, reusing generateSpectrogramImage (FfmpegAudioPlayer.kt) with
// window = null instead of the per-zoom AudioViewWindow it's normally called with. Fixed
// dimensions mean this runs exactly once per file open, not on every zoom/pan/resize --
// SpectrogramDisplay below crops/stretches the result instead of asking ffmpeg to re-render.
fun generateFullSpectrogramImage(file: File, rawAudioParams: RawAudioParams? = null): ImageBitmap? =
    generateSpectrogramImage(file, SPECTROGRAM_WIDTH_PX, SPECTROGRAM_HEIGHT_PX, rawAudioParams, window = null)

// Draws the visibleRange column slice of a full-file spectrogram bitmap, stretched to fill this
// Canvas -- the spectrogram equivalent of AudioWaveformPeaks.kt's WaveformChannelCanvas, which
// draws a slice of the peaks array instead of a slice of an image. Coerced so a visibleRange right
// at the bitmap's own edge (e.g. the full-duration case, which ends exactly at bitmap.width) can
// never push srcOffset + srcSize past the bitmap's actual bounds.
@Composable
fun SpectrogramDisplay(bitmap: ImageBitmap, visibleRange: IntRange, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val srcWidth = (visibleRange.last - visibleRange.first + 1).coerceIn(1, bitmap.width)
        val srcX = visibleRange.first.coerceIn(0, bitmap.width - srcWidth)
        drawImage(
            image = bitmap,
            srcOffset = IntOffset(srcX, 0),
            srcSize = IntSize(srcWidth, bitmap.height),
            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:test --tests "com.multiviewer.ui.AudioSpectrogramDisplayTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/AudioSpectrogramDisplay.kt app/src/test/kotlin/com/multiviewer/ui/AudioSpectrogramDisplayTest.kt
git commit -m "Add generateFullSpectrogramImage and SpectrogramDisplay for one-time spectrogram rendering"
```

---

### Task 2: Wire into `FfmpegAudioPlayer.kt`

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/FfmpegAudioPlayer.kt`

**Interfaces:**
- Consumes: `SPECTROGRAM_WIDTH_PX`, `generateFullSpectrogramImage`, `SpectrogramDisplay` (Task 1, same package, no import needed). `visibleBucketRange(window: AudioViewWindow, totalDuration: Double, bucketCount: Int): IntRange` (already exists, `AudioWaveformPeaks.kt`, already used by the waveform panel in this same file).
- Produces: nothing new — this task only changes how `FfmpegAudioPlayer`'s existing `spectrogramBitmap` state is populated and drawn.

- [ ] **Step 1: Compute the spectrogram once, alongside `waveformPeaks`**

In `FfmpegAudioPlayer.kt`, find the `LaunchedEffect(file)` block (the one that sets `probedInfo`/`waveformPeaks`):

```kotlin
        probedInfo = info
        probing = false
        if (info != null) {
            waveformPeaks = withContext(Dispatchers.IO) { computeWaveformPeaks(file, info, rawAudioParams = rawAudioParams) }
        }
    }
```

Replace with:

```kotlin
        probedInfo = info
        probing = false
        if (info != null) {
            waveformPeaks = withContext(Dispatchers.IO) { computeWaveformPeaks(file, info, rawAudioParams = rawAudioParams) }
            spectrogramBitmap = withContext(Dispatchers.IO) { generateFullSpectrogramImage(file, rawAudioParams = rawAudioParams) }
        }
    }
```

- [ ] **Step 2: Remove the per-interaction regeneration code**

Find and delete this constant near the top of the file (below `AUDIO_VISUAL_TIMEOUT_MS`):

```kotlin
private const val SPECTROGRAM_RESIZE_DEBOUNCE_MS = 400L

```

Find this state declaration:

```kotlin
    var waveformSplit by remember(file) { mutableStateOf(0.6f) }
    var containerHeightPx by remember(file) { mutableStateOf(0) }
    var spectrogramBoxSize by remember(file) { mutableStateOf(IntSize.Zero) }
    val elapsedSeconds = (startFromSeconds + playedSeconds).coerceIn(0.0, if (info.duration > 0) info.duration else Double.MAX_VALUE)
```

Replace with:

```kotlin
    var waveformSplit by remember(file) { mutableStateOf(0.6f) }
    var containerHeightPx by remember(file) { mutableStateOf(0) }
    val elapsedSeconds = (startFromSeconds + playedSeconds).coerceIn(0.0, if (info.duration > 0) info.duration else Double.MAX_VALUE)
```

Find and delete this entire block (sits between the `applyZoomOrPan` function and `fun seekToFraction`):

```kotlin
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

```

- [ ] **Step 3: Draw a cropped slice instead of the whole re-fetched image**

Find the spectrogram panel's `Box`:

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
                },
        ) {
            val spectrogram = spectrogramBitmap
            if (spectrogram != null) {
                val visibleRange = visibleBucketRange(visibleWindow, info.duration, SPECTROGRAM_WIDTH_PX)
                SpectrogramDisplay(bitmap = spectrogram, visibleRange = visibleRange, modifier = Modifier.fillMaxSize())
            } else {
                DecodingIndicator("스펙트로그램 생성 중...", modifier = Modifier.align(Alignment.Center))
            }
```

(The rest of that `Box` — the playhead overlay `if (info.duration > 0) { ... }` — is unchanged, leave it exactly as-is.)

- [ ] **Step 4: Remove now-unused imports**

Delete these two lines from the top of the file:

```kotlin
import androidx.compose.foundation.Image
```
```kotlin
import androidx.compose.ui.layout.ContentScale
```
```kotlin
import kotlinx.coroutines.delay
```

Change:
```kotlin
import androidx.compose.ui.unit.IntSize
```
to nothing (delete this line too — `IntSize` was only used by the now-removed `spectrogramBoxSize`).

Do **not** remove `import androidx.compose.ui.layout.onGloballyPositioned` — it's still used by the outer `Column`'s `.onGloballyPositioned { containerHeightPx = it.size.height }`.

- [ ] **Step 5: Compile**

Run: `./gradlew :app:compileKotlin`
Expected: `BUILD SUCCESSFUL`. If it fails with "unused import" it's a warning, not an error — but if any of the four imports removed in Step 4 are still referenced elsewhere in the file, compilation will fail with an unresolved-reference error instead; re-check Step 4 didn't remove an import that's still needed (only remove the four listed there — nothing else).

- [ ] **Step 6: Run the full test suite**

Run: `./gradlew :app:test`
Expected: `BUILD SUCCESSFUL`, all tests pass, no regressions (baseline count plus Task 1's 2 new tests).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/FfmpegAudioPlayer.kt
git commit -m "Render spectrogram once per file instead of re-invoking ffmpeg on every zoom/pan/resize"
```

---

### Task 3: Manual verification

**Files:** None (no code changes).

- [ ] **Step 1: Generate a synthetic test file**

```bash
ffmpeg -y -f lavfi -i "sine=duration=6:frequency=220,volume=0.5" -f lavfi -i "sine=duration=6:frequency=1800" \
  -filter_complex "[0][1]amix=inputs=2" -c:a pcm_s16le /tmp/test-spectrogram-verify.wav
```

This gives a 6-second stereo-audible file with two distinct, stable tones (220Hz + 1800Hz) — the spectrogram should show two clear horizontal bands, useful for visually confirming the crop/stretch math lines up correctly at different zoom levels (a misaligned crop would show the bands at the wrong height or drifting horizontally from where the waveform's own peaks say a transient is).

- [ ] **Step 2: Run the app and open the test file**

```bash
./gradlew :app:run
```

Open `/tmp/test-spectrogram-verify.wav`. Confirm:
- The spectrogram renders (no longer shows "스펙트로그램 생성 중..." indefinitely) shortly after the waveform appears — both come from the same `LaunchedEffect(file)` now.
- Scrolling to zoom/pan the waveform updates the spectrogram's visible slice **instantly**, with no "규ecalculating" delay or flicker (previously debounced 400ms + ffmpeg subprocess time).
- The two frequency bands stay visually level (no vertical jitter) as you zoom in/out — confirms `srcOffset`/`srcSize` cropping is stable.
- Playing the file moves the white playhead line across both the waveform and spectrogram in sync, same as before this change (this part was never modified).

- [ ] **Step 3: Report and clean up**

Delete `/tmp/test-spectrogram-verify.wav`. Note in the progress ledger (`.git/sdd/progress.md`) what was actually confirmed — if GUI interaction isn't reliable in the current environment (this sandbox has a documented history of the app window closing on its own shortly after launch), say so explicitly rather than claiming a full interactive pass; code-level confirmation (Task 1/2's tests, compile, full suite) stands on its own regardless.
