# Audio Waveform Canvas Rendering + Spectrogram Resize Design

## Goal

Replace the audio player's static, once-baked waveform image with a resolution-independent Canvas-drawn waveform (computed once from real PCM peak data, always crisp regardless of panel size), and make the spectrogram regenerate at the panel's actual current pixel size instead of a fixed baked resolution. This directly addresses the reported issue: "파형과 스펙트럼이 이미 만들어진 이미지로 보여지는게 어색하다. 프로그래스도 맞지 않고 이미지 크기도 작아서 재생되고 있는 타임과 스펙트럼 시점이 맞지 않아" -- the goal is a GoldWave/Audition-style feel where the visualization always looks sharp and the playhead feels precisely attached to it.

## Background

`FfmpegAudioPlayer.kt` currently generates both the waveform and spectrogram once per file via ffmpeg's `showwavespic`/`showspectrumpic` filters, baked to a fixed 1600×300 PNG, then displayed via `Image(..., contentScale = ContentScale.FillBounds)` stretched to fill whatever size the panel currently is. Investigation confirmed the playhead/progress overlay math itself (`elapsedSeconds / info.duration` mapped to a fraction-width `Box`) has no coordinate bug -- the root cause is architectural: a single fixed-resolution image stretched to an arbitrary, possibly much larger or longer-duration display, which looks blurry when the panel exceeds 1600px and feels temporally coarse for long files.

The playback pipeline (`DisposableEffect` + `SourceDataLine` reader thread), the playhead/progress `Box` overlay, and the waveform area's click/drag seek gesture (`pointerInput` + `awaitEachGesture`) are all unrelated to this problem and are explicitly out of scope -- none of them need to change, since the seek gesture reads the containing `Box`'s own layout `size`, not anything about what's drawn inside it.

## Design

### A. New file: `AudioWaveformPeaks.kt`

Pure peak-computation logic, no Compose UI, so it's independently testable (this project has no automated coverage for `FfmpegAudioPlayer.kt`'s Compose code, but pure functions like this one can get real test coverage the same way `probeAudioFormat` and the various format walkers do).

```kotlin
data class ChannelPeaks(val min: FloatArray, val max: FloatArray) // both size = bucketCount, values in [-1, 1]
data class WaveformPeaks(val channelCount: Int, val bucketCount: Int, val channels: List<ChannelPeaks>)

fun computeWaveformPeaks(file: File, info: AudioFileInfo, bucketCount: Int = 4096): WaveformPeaks?
```

Spawns the exact same `ffmpeg -f s16le -ar <rate> -ac <channels> -acodec pcm_s16le -` pipe already used for playback (just run once to completion, not fed to a `SourceDataLine`), reading raw PCM in a streaming fashion (no need to hold the whole decoded file in memory -- a multi-hour recording could otherwise use hundreds of MB). Frame boundaries don't align with arbitrary read-buffer boundaries, so leftover bytes from an incomplete trailing frame are carried over to the next read. For each complete frame, the sample's bucket index is `frameIndex / framesPerBucket`, where `framesPerBucket` is derived from `info.duration * info.sampleRate` (the probed duration) divided by `bucketCount` -- 4096 buckets is far more than any realistic panel width in pixels, so the Canvas renderer never needs to recompute anything on resize, just redraw the same buckets scaled to whatever width is currently available. Each 16-bit sample is normalized to a `Float` in `[-1, 1]` and folded into that bucket's running min/max for its channel. Buckets a very short file never reaches (rare edge case, sub-100ms clips) are left at silence (0/0) rather than crashing or dividing by zero.

`@Composable fun WaveformDisplay(peaks: WaveformPeaks, color: Color, modifier: Modifier = Modifier)` also lives in this file: for `channelCount >= 2` it stacks channel 0 (L) above channel 1 (R) in a `Column`, each drawn by its own `Canvas`; for `channelCount == 1` it draws a single `Canvas` filling the whole area. Any channels beyond the first two are ignored (surround audio is out of scope). Each `Canvas` draws one vertical line per bucket from `y(peaks.max[i])` to `y(peaks.min[i])`, where `y(amplitude) = centerY - amplitude * centerY` maps `[-1, 1]` to the canvas's actual pixel height -- this is why resizing never requires recomputation: the same 4096 bucket values just get redrawn at new pixel positions.

### B. `FfmpegAudioPlayer.kt` changes

- Remove `generateWaveformImage` and the `WAVEFORM_IMAGE_WIDTH`/`WAVEFORM_IMAGE_HEIGHT` constants (waveform-only; `generateSpectrogramImage`/`renderAudioVisualization` stay, since the spectrogram keeps using the ffmpeg-image approach).
- Replace the `waveformBitmap: ImageBitmap?` state with `waveformPeaks: WaveformPeaks?`, computed via `computeWaveformPeaks(file, info)` in the same `LaunchedEffect(file)` block that already does the background probing/generation work, on `Dispatchers.IO` as today.
- Replace the waveform `Image(...)` call with `WaveformDisplay(peaks = waveformPeaks, color = Color(0xFF39FF14), modifier = Modifier.fillMaxSize())` (same neon-green as today, for visual consistency) inside the same `Box` that already carries the seek `pointerInput` and playhead/progress overlay -- neither of those needs any change.
- Add `spectrogramBoxSize: IntSize` state, tracked via `onGloballyPositioned` on the spectrogram `Box` (mirroring the existing `containerHeightPx` tracking pattern already used one level up in the same file).
- Add a new `LaunchedEffect(file, spectrogramBoxSize)` that waits 400ms (debounce) then regenerates the spectrogram at `spectrogramBoxSize`'s actual current pixel dimensions via the existing `generateSpectrogramImage`, replacing the current one-shot fixed-1600×300 call inside the main `LaunchedEffect(file)` block. Compose's `LaunchedEffect` key-change semantics (a new key value cancels the previous coroutine and starts a fresh one) naturally debounce rapid resize events -- only the last size that survives the 400ms delay without being superseded actually triggers regeneration. The old bitmap stays visible (via `contentScale = FillBounds`, unchanged) until the new one is ready, so there's no flicker or blank flash during a resize.

## Non-Goals

- Interactive zoom/scroll/pan (a true Audition-style zoomed-in editing view) -- this stays a whole-file overview, matching the existing scope decision from the original audio-playback feature.
- Per-channel display for more than 2 channels (5.1 surround, etc.) -- only the first two channels are shown.
- Replacing the spectrogram's ffmpeg-based generation with a hand-rolled FFT/Canvas renderer -- out of scope for this change; the spectrogram keeps using `showspectrumpic`, just regenerated at the correct size.
- Any change to playback (`SourceDataLine`/reader thread), the playhead/progress overlay, or the waveform seek gesture -- all confirmed unrelated to the reported issue and left untouched.

## Testing

- `computeWaveformPeaks`: unit tests using a real ffmpeg-generated fixture (matching this project's established convention for audio-related tests), asserting: the returned `WaveformPeaks` has the requested `bucketCount` and correct `channelCount`; for a known sine-wave tone, peak amplitudes are non-zero and within `[-1, 1]`; a mono file returns exactly one `ChannelPeaks`; a stereo file returns exactly two.
- `WaveformDisplay`/`FfmpegAudioPlayer`'s resize-triggered spectrogram regeneration: no automated coverage possible (Compose UI + timing-dependent debounce), consistent with this project's existing lack of Compose UI test infrastructure for this file. Manual verification: open a stereo audio file, confirm L/R waveforms render distinctly and stay sharp when the panel or window is resized to be much wider than before; confirm the playhead visually tracks the waveform accurately during playback; resize the spectrogram panel and confirm it regenerates at the new size after a brief pause with no blank flash; confirm playback, click-to-seek, and drag-to-seek on the waveform all still work exactly as before.
