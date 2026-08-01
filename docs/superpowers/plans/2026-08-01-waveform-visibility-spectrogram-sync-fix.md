# Waveform Visibility + Spectrogram Progress Sync Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the waveform line visibly thick enough to see clearly, and make the spectrogram image fill its panel edge-to-edge (no black padding bars) so its progress overlay visually lines up with actual playback position.

**Architecture:** Two small, independent one-line-ish fixes in already-shipped code: an explicit `strokeWidth` on the waveform's `Canvas` `drawLine` call, and dropping the aspect-ratio-preserving pad stage from the spectrogram's ffmpeg filter chain in favor of a plain stretch-to-fit scale.

**Tech Stack:** Kotlin, Compose Desktop Canvas, ffmpeg filter chain (no new dependencies).

## Global Constraints

- Waveform stroke width: exactly `1.5.dp` (confirmed with the user over a bolder `2.5.dp` option) -- do not use a different value.
- The spectrogram filter chain change must produce an image whose content fills the requested `width x height` exactly, with zero padding on any side -- verified empirically in the design spec (`showspectrumpic=s=1200x300,scale=1200:300` produces an exact `1200x300` PNG with no black bars, vs. the current pad-based chain which measured ~13% total black-bar width for the same test file).
- Do not touch the waveform's peak computation, bucket count, or color; do not touch the spectrogram's actual frequency/color rendering; do not touch the progress overlay math (`elapsedSeconds / info.duration`) -- all three were confirmed correct/unrelated in the design's investigation.
- No automated test coverage is possible for either change (Compose Canvas visual stroke width and ffmpeg-rendered image content placement are both outside this project's existing test infrastructure for these two files).

---

### Task 1: Waveform stroke width

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/AudioWaveformPeaks.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces: no signature changes -- `drawChannelPeaks` still draws the same peaks, just with a visible line weight instead of a hairline.

- [ ] **Step 1: Add the `dp` import**

In `app/src/main/kotlin/com/multiviewer/ui/AudioWaveformPeaks.kt`, find:

```kotlin
import androidx.compose.ui.graphics.drawscope.DrawScope
import java.io.File
```

Replace with:

```kotlin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import java.io.File
```

- [ ] **Step 2: Add the explicit stroke width**

Find:

```kotlin
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

Replace with:

```kotlin
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

(`strokeWidthPx` is computed once outside the loop, not per-iteration, since `DrawScope.dp.toPx()` is a fixed conversion for the whole draw call -- no need to recompute it 4096 times.)

- [ ] **Step 3: Build and confirm no regressions**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test`
Expected: PASS, no new failures (this file's existing `AudioWaveformPeaksTest.kt` only tests `computeWaveformPeaks`, which this task doesn't touch).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/AudioWaveformPeaks.kt
git commit -m "fix: give the waveform line a visible stroke width instead of a hairline"
```

---

### Task 2: Spectrogram fills its panel edge-to-edge (no padding)

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/FfmpegAudioPlayer.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces: `generateSpectrogramImage`'s signature is unchanged -- only its internal ffmpeg filter string changes.

- [ ] **Step 1: Replace the filter chain**

In `app/src/main/kotlin/com/multiviewer/ui/FfmpegAudioPlayer.kt`, find:

```kotlin
fun generateSpectrogramImage(file: File, width: Int, height: Int): ImageBitmap? =
    renderAudioVisualization(file, "showspectrumpic=s=${width}x${height},scale=${width}:${height}:force_original_aspect_ratio=decrease,pad=${width}:${height}:(ow-iw)/2:(oh-ih)/2")
```

Replace with:

```kotlin
// showspectrumpic doesn't honor its own s=WxH request precisely (measured: requesting 1200x300
// actually renders 1482x428) -- scale=W:H (no aspect-ratio preservation) forces the exact
// requested dimensions by stretching rather than letterboxing/pillarboxing, so the spectrogram
// content fills the image edge-to-edge with no black padding bars. This matters because the
// progress overlay assumes "image width == full duration" linearly; padding here would make the
// playhead visually misalign with the actual spectrogram content near both edges.
fun generateSpectrogramImage(file: File, width: Int, height: Int): ImageBitmap? =
    renderAudioVisualization(file, "showspectrumpic=s=${width}x${height},scale=${width}:${height}")
```

- [ ] **Step 2: Build and confirm no regressions**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test`
Expected: PASS, no new failures. This includes `FfmpegAudioPlayerTest.kt`'s existing `` `generateSpectrogramImage produces a decoded bitmap at the requested dimensions` `` test -- it asserts on `bitmap.width`/`bitmap.height` only (not on padding/content placement), so it should still pass unchanged since the new filter chain still produces the exact requested dimensions (verified empirically in the design spec), just without padding.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/FfmpegAudioPlayer.kt
git commit -m "fix: stretch the spectrogram to fill its panel exactly, removing padding that desynced the progress overlay"
```

---

### Task 3: Manual end-to-end verification (controller-performed)

No automated coverage is possible for this task (Compose Canvas visual appearance + ffmpeg-rendered image content placement). This step is performed by the controller directly, not dispatched to a subagent.

- [ ] Launch the app (`export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew run`) and open a real audio file
- [ ] Confirm the waveform is now clearly visible as a solid shape, not a faint/hairline smear
- [ ] Confirm the spectrogram panel has no black bars on any edge -- the rendered spectrogram fills the entire panel
- [ ] Play the file and confirm the spectrogram's playhead line visually tracks real elapsed time correctly across the whole duration, especially near the very start and very end (where the old padding made the mismatch most visible)
- [ ] Resize the spectrogram panel and confirm it still fills edge-to-edge after regenerating at the new size
- [ ] If any issue is found, treat it as a real bug -- return to systematic-debugging, not a quick patch

---

## Self-Review Notes

- **Spec coverage:** waveform stroke width (exactly `1.5.dp`, per the user's confirmed choice) ✅ (Task 1), spectrogram stretch-not-pad fix (verified empirically to produce exact dimensions with no padding) ✅ (Task 2), manual verification of both fixes together ✅ (Task 3).
- **Placeholder scan:** none found.
- **Type consistency:** `generateSpectrogramImage(file: File, width: Int, height: Int): ImageBitmap?`'s signature is unchanged from its current shipped form (Task 2 only edits the function body's filter string), so its one existing caller (`FfmpegAudioPlayer`'s debounced `LaunchedEffect`, from the prior audio-waveform-canvas plan) needs no changes and isn't touched by this plan.
