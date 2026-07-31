# Audio Playback Design

## Goal

Add playback for `m4a`/`mp3`/`wav` files: play/pause, seek, elapsed/total time, plus a static waveform and spectrogram overview (Audition/GoldWave-style: precomputed once per file, with a moving playhead cursor and click/drag-to-seek on the waveform). Currently `AudioInspectorUI.kt` only shows structural summary information -- there is no playback of any kind.

## Background

`FfmpegVideoPlayer.kt` is the closest existing precedent: spawns the bundled ffmpeg as a subprocess, pipes raw decoded output on stdout, manages play/pause/seek state via `remember(file) { mutableStateOf(...) }` plus a `DisposableEffect(file, restartTrigger)` that owns the subprocess's lifecycle (seeking = kill and respawn with `-ss`, since there's no other way to rewind a one-shot pipe). This project has no audio-output code today (`javax.sound.sampled` is unused) but it's part of the standard JDK the app already bundles, so no new dependency is needed -- ffmpeg is likewise already bundled.

Audio playback is simpler than video in one important way: there's no frame-pacing problem to solve. `javax.sound.sampled.SourceDataLine.write()` blocks until the OS audio buffer has room, which naturally paces the reader thread to real playback speed -- no custom sleep/catch-up logic like `FfmpegVideoPlayer`'s frame pacer needs.

The waveform/spectrogram overview doesn't need any hand-rolled signal processing either: ffmpeg has built-in filters, `showwavespic` and `showspectrumpic`, that render a whole audio file's waveform or spectrogram directly to a PNG image in one invocation. This avoids implementing PCM bucketing/downsampling/FFT in Kotlin entirely -- just run ffmpeg once per image and decode the resulting PNG bytes via Skia, the same way this app already decodes embedded thumbnails elsewhere (`Image.makeFromEncoded(...).toComposeImageBitmap()`).

## Design

### A. PCM extraction and format probe

A new `probeAudioFormat(file: File): AudioFileInfo?` (parallel to `probeVideo`, but simpler) reads `-select_streams a:0 -show_entries stream=sample_rate,channels,duration` via ffprobe in one call, returning `AudioFileInfo(sampleRate: Int, channels: Int, duration: Double)`. Playback pipes raw PCM from ffmpeg at the **source's own probed sample rate and channel count** -- no forced resampling: `ffmpeg -i file -map 0:a:0 -f s16le -ar <probed sampleRate> -ac <probed channels> -acodec pcm_s16le -` (16-bit signed little-endian is still fixed, since that's just the wire format between ffmpeg and this app, not something perceptible -- only the rate/channel count, which affect actual fidelity, are preserved from the source). If probing fails, fall back to a fixed 44100Hz/stereo default rather than refusing to play.

### B. Playback via SourceDataLine

A new file `FfmpegAudioPlayer.kt`, a `@Composable` structurally parallel to `FfmpegVideoPlayer`: `remember(file)`-scoped `isPlaying`/`hasEnded`/`restartTrigger`/`playedSeconds`/`startFromSeconds`/`lastHandledSeekTick` state, and a `DisposableEffect(file, restartTrigger)` that spawns the ffmpeg PCM process (at the probed sample rate/channels) and a reader thread. The reader thread opens and starts a `SourceDataLine` with an `AudioFormat` built from that same probed sample rate/channel count once, then loops: while paused, `Thread.sleep(50)` (same wait-loop convention `FfmpegVideoPlayer` already uses); while playing, read a chunk from the ffmpeg pipe and `line.write(...)` it (this call's natural blocking IS the pacing), incrementing `playedSeconds` by `bytesRead / bytesPerSecond` (`sampleRate * channels * 2 bytes/sample`). Pausing calls `line.stop()`; resuming calls `line.start()`. `onDispose` stops the reader thread, force-kills the ffmpeg process, and stops+flushes+closes the line. Seeking bumps `restartTrigger`, tearing down and respawning the whole effect with a fresh `-ss <seconds>` process, identical to `FfmpegVideoPlayer`'s approach.

### C. Waveform and spectrogram images

Two new functions, run once per file in a background-thread `LaunchedEffect(file)` (not blocking the UI, same convention as `FfmpegVideoPlayer`'s own probing):

- `generateWaveformImage(file: File, width: Int, height: Int): ByteArray?` -- `ffmpeg -i file -lavfi showwavespic=s=${width}x${height}:colors=<app's neon-green hex> -frames:v 1 -f image2pipe -vcodec png -`, capturing PNG bytes from stdout.
- `generateSpectrogramImage(file: File, width: Int, height: Int): ByteArray?` -- same pattern with `showspectrumpic=s=${width}x${height}`.

Both are generated once at a fixed backing resolution (e.g. 1600x300) and decoded to `ImageBitmap` via Skia; Compose's `Image` composable scales them to whatever display size the panel ends up at (same `ContentScale.Fit` approach `FfmpegVideoPlayer` already uses), so no regeneration is needed on window/panel resize.

### D. Layout

`AudioInspectorUI.kt` changes from its current single-region (summary fills the whole center panel) to a `VideoInspectorUI`-style two-region split: a `verticalSplit`-controlled top region containing the new `FfmpegAudioPlayer`, and the existing summary dashboard below it (same `DraggableDivider` mechanism, same pattern as video/image). Within `FfmpegAudioPlayer`'s own area: the waveform image on top, a `DraggableDivider`, the spectrogram image below (mirroring how `VideoInspectorUI` already splits its player/GOP region) -- both get a playhead cursor (a thin vertical line positioned at `elapsed/total` fraction of the image's width, redrawn as `playedSeconds` changes). The waveform image is click/drag-to-seek (same `awaitEachGesture`/`awaitFirstDown`/`drag` gesture pattern `FfmpegVideoPlayer`'s existing progress bar already uses, applied to the whole waveform surface instead of a thin bar); the spectrogram is display-only. A play/pause icon button and "elapsed / total" time captions overlay the waveform area, in the same corner positions `FfmpegVideoPlayer` already uses (`PreviewCaption`, bottom-start/bottom-end, and a center play/pause circle).

## Non-Goals

- Real-time/live waveform or spectrum analysis (e.g. a VU-meter-style visualizer reacting to audio as it plays) -- both images are precomputed once per file; only the playhead cursor position updates during playback.
- Volume control, playback speed control, or any control beyond play/pause/seek.
- Gapless looping or auto-advance to another file when playback ends (matches `FfmpegVideoPlayer`'s existing "ends, shows replay" behavior).
- Any change to `FfmpegVideoPlayer.kt`'s own behavior -- audio's waveform-click-to-seek replaces the need for a separate shared progress-bar component, so `FfmpegVideoPlayer.kt` is untouched by this feature.

## Testing

- `probeAudioFormat`: unit tests using real ffmpeg-generated audio fixtures, covering a normal file (sampleRate/channels/duration all read correctly) and a nonexistent-file failure case.
- `generateWaveformImage`/`generateSpectrogramImage`: unit tests using a real ffmpeg-generated audio fixture, asserting the returned bytes decode as a valid PNG of the requested dimensions.
- `FfmpegAudioPlayer`'s playback loop, gesture handling, and layout: no automated coverage possible (Compose UI/audio-hardware I/O, consistent with this project's existing lack of Compose UI test infrastructure, same category as `FfmpegVideoPlayer`). Manual verification: open an `m4a`/`mp3`/`wav` file, confirm the waveform and spectrogram render, play/pause works and audio is audible, the playhead moves during playback, and clicking/dragging on the waveform seeks correctly.
