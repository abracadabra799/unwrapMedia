# Audio Spectrogram Canvas Rendering — Design

## Background

`FfmpegAudioPlayer` (`app/src/main/kotlin/com/multiviewer/ui/FfmpegAudioPlayer.kt`) shows two panels while an audio file is open: a waveform and a spectrogram, both zoomable/pannable via a shared `visibleWindow` (`AudioViewWindow`), both scrubbed by a real-time playhead line that already moves correctly during playback (updated every audio buffer chunk).

The two panels are built very differently today:

- **Waveform**: `computeWaveformPeaks` streams the file's real PCM data once, when the file is opened, into a fixed `WAVEFORM_PEAK_BUCKET_COUNT = 4096`-bucket min/max array (`AudioWaveformPeaks.kt`). `WaveformDisplay` draws whatever sub-range `visibleBucketRange` maps the current `visibleWindow` onto, via a plain `Canvas`. Zooming/panning is pure arithmetic on data already in memory — instant, no I/O.
- **Spectrogram**: `generateSpectrogramImage` shells out to ffmpeg's `showspectrumpic` filter to render a PNG **every time `visibleWindow` or the panel's pixel size changes** (debounced 400ms), sized to the exact zoom window and panel dimensions. The result is displayed as a plain `Image` composable.

The user finds the spectrogram "어색함" (feels off) specifically because it's rendered as a static image re-fetched from an external process on every interaction, unlike the waveform's native, instantly-responsive Canvas drawing. The request: make the spectrogram behave like the waveform — computed once from real data when the file opens, then drawn (not re-fetched) for whatever window is currently visible, matching how professional audio editors (GoldWave, Adobe Audition) already work (a single loaded view, scrubbed by a moving playhead — which this app already has for both panels).

## Goal

Replace the spectrogram's "regenerate via ffmpeg subprocess on every zoom/pan/resize" flow with a "compute once via ffmpeg when the file opens, then draw a cropped/stretched region of that single image via Canvas" flow — mirroring `computeWaveformPeaks`/`WaveformDisplay`'s existing structure exactly, including reusing `visibleBucketRange` unchanged for column selection.

## Non-Goals

- No hand-rolled FFT in Kotlin — ffmpeg's `showspectrumpic` (already relied on, already produces the standard color-mapped STFT audio editors use) remains the sole source of spectral data, just called once instead of repeatedly.
- No change to the playhead-line overlay, zoom/pan gesture handling, `AudioZoomScrollbar`, or `AudioMinimap` — all already work correctly against `visibleWindow` and are unaffected by how the spectrogram panel is drawn underneath them.
- No change to the waveform panel — it's already the reference implementation this design copies.
- No live/progressive re-analysis during playback (an "oscilloscope reveal" mode) — out of scope; matches how real audio editors work (whole file loaded once, cursor scrubs across it), which is what was actually requested.

## Design

### Fixed-resolution one-time render

A new file, `AudioSpectrogramDisplay.kt`, defines:

```kotlin
const val SPECTROGRAM_WIDTH_PX = 4096   // matches WAVEFORM_PEAK_BUCKET_COUNT for symmetry
const val SPECTROGRAM_HEIGHT_PX = 512

fun generateFullSpectrogramImage(file: File, rawAudioParams: RawAudioParams? = null): ImageBitmap? =
    generateSpectrogramImage(file, SPECTROGRAM_WIDTH_PX, SPECTROGRAM_HEIGHT_PX, rawAudioParams, window = null)
```

This is a thin wrapper around the existing `generateSpectrogramImage` (`FfmpegAudioPlayer.kt`, unchanged) called with `window = null` (whole file) instead of the current per-zoom `AudioViewWindow`. `FfmpegAudioPlayer`'s file-open `LaunchedEffect` calls this once, right alongside `computeWaveformPeaks`, and stores the result in `spectrogramBitmap` (same state variable name that exists today, now populated once instead of repeatedly).

**Resolution trade-off (confirmed with user):** a fixed 4096px-wide render means very long files (tens of minutes), zoomed in deeply, will show a blockier/blurrier crop instead of ffmpeg re-rendering fresh spectral detail for that exact window (today's behavior). This is accepted — it matches the same trade-off `WaveformPeaks` already makes with its own fixed 4096-bucket count, and this app's typical files (motion photo clips, short recordings) are short enough that 4096px gives ample time resolution even fully zoomed in.

### Drawing a cropped window

```kotlin
@Composable
fun SpectrogramDisplay(bitmap: ImageBitmap, visibleRange: IntRange, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val srcWidth = (visibleRange.last - visibleRange.first + 1).coerceAtLeast(1)
        drawImage(
            image = bitmap,
            srcOffset = IntOffset(visibleRange.first, 0),
            srcSize = IntSize(srcWidth, bitmap.height),
            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
        )
    }
}
```

`visibleRange` comes from the existing, unmodified `visibleBucketRange(visibleWindow, info.duration, SPECTROGRAM_WIDTH_PX)` — the same function the waveform already uses, just called with the spectrogram's own pixel-width constant instead of `peaks.bucketCount`. Both panels' visible ranges are computed the same way from the same `visibleWindow`, so they always stay in sync by construction.

### What gets removed from `FfmpegAudioPlayer.kt`

- `spectrogramBoxSize` state and its `onGloballyPositioned` callback — no longer needed; `Canvas`'s own `DrawScope.size` is read directly at draw time, same as `WaveformChannelCanvas` does today.
- The debounced `LaunchedEffect(file, spectrogramBoxSize, visibleWindow) { ... generateSpectrogramImage(...) ... }` block and `SPECTROGRAM_RESIZE_DEBOUNCE_MS` — regeneration-on-interaction no longer exists.
- The spectrogram panel's `Image(bitmap = spectrogram, ..., contentScale = ContentScale.FillBounds)` — replaced with `SpectrogramDisplay(bitmap = spectrogram, visibleRange = visibleBucketRange(visibleWindow, info.duration, SPECTROGRAM_WIDTH_PX), modifier = Modifier.fillMaxSize())`.

Everything else in `FfmpegAudioPlayer.kt` (playhead overlay, zoom/pan gesture handlers, scrollbar, minimap, the `"스펙트로그램 생성 중..."` loading placeholder shown while `spectrogramBitmap` is still null) is unchanged.

## Testing

`generateFullSpectrogramImage` is a pure I/O function (file in, `ImageBitmap?` out) like `computeWaveformPeaks`, tested the same way: real ffmpeg-generated synthetic audio fixtures (`sine=duration=...`), no mocking.

- Returns a bitmap sized exactly `SPECTROGRAM_WIDTH_PX x SPECTROGRAM_HEIGHT_PX` for a valid audio file.
- Returns `null` for an undecodable/garbage file (mirrors `computeWaveformPeaks`'s existing null-fixture test).

`SpectrogramDisplay`'s Canvas drawing itself is not unit-tested, matching this project's existing convention — `WaveformDisplay`/`WaveformChannelCanvas` aren't unit-tested either (only the pure data functions feeding them are). Visual correctness (crop alignment, stretch, playhead sync) is verified manually against real files, same as every other UI change in this project.
