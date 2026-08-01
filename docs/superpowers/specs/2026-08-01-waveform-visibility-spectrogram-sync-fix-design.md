# Waveform Visibility + Spectrogram Progress Sync Fix Design

## Goal

Fix two issues reported immediately after shipping the audio waveform Canvas rendering feature: the waveform is too faint to see clearly, and the spectrogram's progress overlay doesn't visually line up with where playback actually is.

## Background

Both issues were root-caused empirically before writing this spec, not guessed at:

**Waveform visibility**: `AudioWaveformPeaks.kt`'s `drawChannelPeaks` calls `drawLine(color = color, start = ..., end = ...)` with no `strokeWidth` argument, so it uses `DrawScope.drawLine`'s default (`Stroke.HairlineWidth`, effectively a single physical pixel regardless of density). With up to 4096 buckets compressed into a typically much narrower panel, the result reads as faint and indistinct rather than a solid waveform shape.

**Spectrogram progress mismatch**: `generateSpectrogramImage`'s filter chain is `showspectrumpic=s=${width}x${height},scale=${width}:${height}:force_original_aspect_ratio=decrease,pad=${width}:${height}:(ow-iw)/2:(oh-ih)/2`. This exists because ffmpeg's `showspectrumpic` doesn't honor its own `s=WxH` request precisely (confirmed again empirically: requesting `1200x300` actually renders `1482x428`), so the filter chain scales-to-fit while preserving the natural aspect ratio and pads the rest with black bars to hit the exact requested size. Measured directly: for a `1200x300` target, the real spectrogram content ends up centered in roughly the middle 87% of the width, with ~6.5% black padding on each side. The progress overlay (`elapsedSeconds / info.duration` mapped linearly across the full box width) has no awareness of this padding, so the playhead visually races ahead of / lags behind the actual spectrogram content near both edges. This existed since the original audio-playback feature, but was harder to notice when the spectrogram was always baked at one fixed 1600×300 resolution (constant padding ratio); it became clearly visible once the spectrogram started regenerating at the panel's actual (constantly changing) size.

## Design

### A. Waveform stroke width

In `AudioWaveformPeaks.kt`'s `drawChannelPeaks`, add an explicit stroke width to the `drawLine` call: `strokeWidth = 1.5.dp.toPx()`. `1.5.dp` was chosen (over a bolder `2.5.dp`) to stay closer to how GoldWave/Audition's own default waveform line weight looks -- clearly visible without looking chunky or losing fine detail in dense/loud sections.

### B. Spectrogram: stretch instead of pad

In `FfmpegAudioPlayer.kt`'s `generateSpectrogramImage`, change the filter chain from:

```
showspectrumpic=s=${width}x${height},scale=${width}:${height}:force_original_aspect_ratio=decrease,pad=${width}:${height}:(ow-iw)/2:(oh-ih)/2
```

to:

```
showspectrumpic=s=${width}x${height},scale=${width}:${height}
```

Dropping `force_original_aspect_ratio=decrease` and the `pad` stage means the image is stretched (not letterboxed) to exactly `width x height`, so the spectrogram content fills the entire image edge-to-edge with zero padding -- matching what the linear `elapsedSeconds / info.duration` progress overlay already assumes. This introduces a small amount of aspect-ratio distortion at the ffmpeg level, but that's not a new kind of distortion for this feature: the resulting bitmap is already stretched again one layer up via Compose's `contentScale = FillBounds`. Verified empirically: `showspectrumpic=s=1200x300,scale=1200:300` (no aspect-ratio preservation) produces an exact `1200x300` PNG with content filling the full frame.

## Non-Goals

- Any change to the waveform's peak computation, bucket count, or color -- only the line stroke width changes.
- Any change to the spectrogram's actual frequency/color rendering -- only how it's resized to fit the target dimensions.
- Any change to the progress overlay math itself (`elapsedSeconds / info.duration`) -- it was already correct; the bug was that the spectrogram image's content didn't actually span the box it was drawn into.

## Testing

- No automated coverage possible for either change (Compose Canvas visual stroke width and ffmpeg-rendered image content placement are both outside this project's existing test infrastructure for `FfmpegAudioPlayer.kt`/`AudioWaveformPeaks.kt`).
- Manual verification: open a stereo audio file, confirm the waveform is now clearly visible (not faint/hairline); confirm the spectrogram fills its panel edge-to-edge with no black bars on the sides; play the file and confirm the spectrogram's playhead line visually tracks real time correctly from the very start of playback to the very end, including near both edges (where the old padding made the mismatch most visible).
