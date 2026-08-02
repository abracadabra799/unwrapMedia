# Raw PCM Audio Support Design

## Goal

Let the app open headerless raw PCM audio files (`.pcm`, and `.raw` when the user confirms it's audio) the way GoldWave/Adobe Audition's "Open As Raw" does: collect the parameters a raw stream can't self-describe (sample rate, channels, sample format, byte order, leading offset to skip), then play it back with waveform and spectrogram, reusing this app's existing audio inspector as-is.

## Background

This app already has a nearly-identical precedent: `RawPixelOpenDialog.kt` collects width/height/pixel format/byte order/fps for headerless raw *image* dumps (`RAW_PIXEL_EXTENSIONS` = `raw`/`rgb`/`rgba`/`yuv`, gated in `AppState.openFile` before the generic parse path, confirmed via `AppState.confirmRawPixelFile`). Raw PCM audio has the exact same shape of problem -- no header, so ffmpeg needs to be told the format explicitly -- and this design follows that same pattern.

Separately, this app's existing audio pipeline (`FfmpegAudioPlayer.kt` for playback, `AudioWaveformPeaks.kt` for the waveform, `generateSpectrogramImage`/`renderAudioVisualization` in `FfmpegAudioPlayer.kt` for the spectrogram) already shells out to ffmpeg/ffprobe for every supported format (`m4a`/`mp3`/`wav`/`flac`/`ogg`/`opus`/`aiff`/`aif`/`aifc`). All three call sites currently rely on ffmpeg/ffprobe auto-detecting the container from a bare `-i <file>` -- none pass input-side format hints (`-f`/`-ar`/`-ac` before `-i`), which is exactly what raw PCM needs and exactly what's missing today. `RawPixelDecoder.kt`'s `decodeYuvFamily` already demonstrates this precise pattern for images (`-f rawvideo -pix_fmt <fmt> -s WxH -i tempIn.raw`), confirming it's a small, mechanical addition rather than new architecture.

`.raw` is already claimed by the image feature. Per the user's explicit choice, `.raw` gets a lightweight "is this an image or audio file?" chooser before routing to the appropriate parameter dialog; `.pcm` always routes straight to the new raw-audio dialog since it's unambiguous.

## Design

### A. `RawAudioParams` and format encoding (new: `RawAudioDecoder.kt`, package `com.multiviewer.parser`)

```kotlin
enum class RawAudioFormat(val bytesPerSample: Int, val needsByteOrder: Boolean) {
    U8(1, false), S16(2, true), S24(3, true), S32(4, true), F32(4, true)
}
enum class RawAudioByteOrder { LITTLE, BIG }

data class RawAudioParams(
    val sampleRate: Int,
    val channels: Int,
    val format: RawAudioFormat,
    val byteOrder: RawAudioByteOrder,
    val offsetBytes: Long,
)
```

A pure function computes ffmpeg's format-code string from `(format, byteOrder)` (`u8`, `s16le`, `s16be`, `s24le`, `s24be`, `s32le`, `s32be`, `f32le`, `f32be` -- `U8` ignores byte order, matching `needsByteOrder = false`). A second pure function computes expected playable duration from `(fileSize, offsetBytes, sampleRate, channels, format.bytesPerSample)`, used both for the dialog's live preview and for constructing `AudioFileInfo` without an `ffprobe` call (raw PCM has nothing for `ffprobe` to read).

### B. `RawAudioOpenDialog.kt` (new, package `com.multiviewer.ui`)

Mirrors `RawPixelOpenDialog.kt`'s structure and interaction pattern: sample rate (numeric, with common presets like 8000/16000/22050/44100/48000/96000 plus free entry), channel count (numeric), sample format (radio list: 8-bit unsigned / 16-bit signed / 24-bit signed / 32-bit signed / 32-bit float), byte order (radio list, shown only when `format.needsByteOrder`), offset in bytes to skip (numeric, default `0`). Shows the live-computed expected duration as the user edits fields, same spirit as the raw-pixel dialog's file-size sanity check.

### C. `.raw` disambiguation and `AppState` wiring

`AppState.openFile`: `.pcm` routes directly to `pendingRawAudioFile = file`. `.raw` sets a new `pendingRawFileChoice: File?` that shows a two-button chooser ("이미지" / "오디오"); picking a side sets the existing `pendingRawPixelFile` or the new `pendingRawAudioFile` accordingly, reusing each path's existing dialog untouched from that point on. `.rgb`/`.rgba`/`.yuv` are unaffected (unambiguous, still route straight to the existing raw-pixel path).

Confirming `RawAudioOpenDialog` calls a new `AppState.confirmRawAudioFile(params: RawAudioParams)`: creates a `TabState` with `type = MediaType.AUDIO` (reusing `AudioInspectorUI` as-is -- no new `MediaType`, unlike raw pixel's dedicated `RawPixelInspectorUI`, since raw PCM needs nothing beyond what the existing audio inspector already provides), sets a new `TabState.rawAudioParams: RawAudioParams?` field, and builds a minimal synthetic one-node `BoxNode` tree (type `"Raw PCM Audio"`, fields = sample rate/channels/format/byte order/offset/computed duration/file size) so the structure-tree panel isn't blank, matching the raw-pixel precedent.

### D. Threading params through the existing audio pipeline

`FfmpegAudioPlayer`, `computeWaveformPeaks`, and `generateSpectrogramImage`/`renderAudioVisualization` each gain an optional `rawAudioParams: RawAudioParams? = null` parameter (default preserves every existing call site's current behavior unchanged). When non-null:
- Skip the `probeAudioFormat` `ffprobe` call; construct `AudioFileInfo(sampleRate, channels, duration)` directly from `RawAudioParams` and the file size (via the pure duration function from section A).
- Insert `-f <formatCode> -ar <sampleRate> -ac <channels>` immediately before each `-i` in all three ffmpeg invocations.
- Apply `offsetBytes`: skip the leading bytes before ffmpeg ever sees the data, the same way `RawPixelDecoder.decodeYuvFamily` already handles headerless image data -- write the file's bytes from `offsetBytes` onward to a temp file, then point ffmpeg's `-i` at that temp file instead of the original.

`AudioInspectorUI.kt`'s only change is passing `tab.rawAudioParams` through to `FfmpegAudioPlayer`.

## Error Handling

- File size not evenly divisible by `channels * format.bytesPerSample`: dialog shows a warning, not a hard block -- the trailing partial sample is simply dropped during playback/decode.
- `offsetBytes >= file size`: dialog shows "재생 가능한 데이터 없음" and disables confirm.
- Extreme computed duration (e.g. many hours, suggesting wrong parameters): warning only, matching the raw-pixel dialog's existing non-blocking oversized-resolution warning convention -- never a hard block, since the user may genuinely have a long raw capture.

## Non-Goals

- Auto-detecting PCM parameters from file content -- always user-supplied, matching GoldWave/Audition's own "Open As Raw" convention.
- Multi-track/non-interleaved raw formats -- interleaved PCM only, matching every other format this app already plays.
- A dedicated raw-audio UI -- reuses `AudioInspectorUI` entirely, per the approved architecture decision.
