# Audio Playback Design

## Goal

Add playback (play/pause, drag-to-seek progress bar, elapsed/total time) for `m4a`/`mp3`/`wav` files. Currently `AudioInspectorUI.kt` only shows structural summary information -- there is no playback of any kind.

## Background

`FfmpegVideoPlayer.kt` is the closest existing precedent: spawns the bundled ffmpeg as a subprocess, pipes raw decoded output on stdout, manages play/pause/seek state via `remember(file) { mutableStateOf(...) }` plus a `DisposableEffect(file, restartTrigger)` that owns the subprocess's lifecycle (seeking = kill and respawn with `-ss`, since there's no other way to rewind a one-shot pipe). This project has no audio-output code today (`javax.sound.sampled` is unused) but it's part of the standard JDK the app already bundles, so no new dependency is needed -- ffmpeg is likewise already bundled.

Audio playback is actually simpler than video in one important way: there's no frame-pacing problem to solve. `javax.sound.sampled.SourceDataLine.write()` blocks until the OS audio buffer has room, which naturally paces the reader thread to real playback speed -- no custom sleep/catch-up logic like `FfmpegVideoPlayer`'s frame pacer needs.

## Design

### A. PCM extraction and duration probe

A new `probeAudioDuration(file: File): Double?` (parallel to `probeVideo`, but far simpler -- just `-select_streams a:0 -show_entries stream=duration`) gets the file's duration via ffprobe. Playback itself pipes raw PCM from ffmpeg, forcing a single fixed output format regardless of the source's actual sample rate/channel layout: `ffmpeg -i file -map 0:a:0 -f s16le -ar 44100 -ac 2 -acodec pcm_s16le -` (16-bit signed little-endian, 44100Hz, stereo -- ffmpeg resamples/remixes transparently). Forcing a fixed format means the code that opens the `SourceDataLine` never needs to inspect or adapt to the source file's own format.

### B. Playback via SourceDataLine

A new file `FfmpegAudioPlayer.kt`, a `@Composable` structurally parallel to `FfmpegVideoPlayer`: `remember(file)`-scoped `isPlaying`/`hasEnded`/`restartTrigger`/`playedSeconds`/`startFromSeconds`/`lastHandledSeekTick` state, and a `DisposableEffect(file, restartTrigger)` that spawns the ffmpeg PCM process and a reader thread. The reader thread opens and starts a `SourceDataLine` (format: 44100Hz/16-bit/stereo/signed/little-endian, matching the forced ffmpeg output exactly) once, then loops: while paused, `Thread.sleep(50)` (same wait-loop convention `FfmpegVideoPlayer` already uses); while playing, read a chunk from the ffmpeg pipe and `line.write(...)` it (this call's natural blocking IS the pacing -- no sleep/timing math needed), incrementing `playedSeconds` by `bytesRead / bytesPerSecond` (computed from the fixed format: `44100 * 2 channels * 2 bytes/sample`). Pausing calls `line.stop()` (silences output immediately, matching how a stopped `SourceDataLine` behaves) and resuming calls `line.start()`. `onDispose` stops the reader thread, force-kills the ffmpeg process, and stops+flushes+closes the line.

### C. Seek and UI integration

Seeking follows the exact same pattern as `FfmpegVideoPlayer`: bump `restartTrigger`, which tears down and respawns the `DisposableEffect` with a fresh `-ss <seconds>` ffmpeg process (and a fresh `SourceDataLine`, since the old one is being closed anyway). `AudioInspectorUI.kt`'s `centerPanel` gets a small fixed-height playback control row above the existing summary content: a play/pause icon button, a drag-to-seek progress bar, and "elapsed / total" time text.

The drag-to-seek progress bar itself (currently ~15 lines of gesture-handling code inline in `FfmpegVideoPlayer.kt`) is extracted into a new shared composable, `PlaybackProgressBar(elapsedSeconds: Double, totalSeconds: Double, onSeek: (fraction: Float) -> Unit, modifier: Modifier = Modifier)`, in `Components.kt` alongside this app's other shared UI pieces (`DraggableDivider`, `PreviewCaption`, `DecodingIndicator`). `FfmpegVideoPlayer.kt` is updated to call it instead of its inline version (behavior-identical, just deduplicated), and `FfmpegAudioPlayer.kt` uses the same composable -- avoiding two near-identical copies of drag-gesture code now that there are two players needing it.

## Non-Goals

- Waveform visualization (explicitly deferred, per discussion) -- only play/pause, seek bar, and time display.
- Any change to `FfmpegVideoPlayer.kt`'s own playback behavior beyond the progress-bar extraction (behavior-identical refactor).
- Volume control, playback speed control, or any control beyond play/pause/seek.
- Gapless looping or auto-advance to another file when playback ends (matches `FfmpegVideoPlayer`'s existing "ends, shows replay" behavior, reused as-is).

## Testing

- `probeAudioDuration`: unit tests using real ffmpeg-generated audio fixtures (matching this session's established convention), covering a normal file and a nonexistent-file failure case.
- `PlaybackProgressBar`'s extraction: no automated coverage possible (Compose UI/gesture code, consistent with this project's existing lack of Compose UI test infrastructure) -- verified by confirming `FfmpegVideoPlayer.kt`'s seek behavior is unchanged after the refactor (source-level review: same seek-fraction math, same state updates) plus manual confirmation that video seeking still works.
- `FfmpegAudioPlayer`: no automated coverage for the playback loop itself (real audio hardware I/O, same category as `FfmpegVideoPlayer`'s reader thread, which also has no direct unit test). Manual verification: open an `m4a`/`mp3`/`wav` file, confirm play/pause, drag-seek, and elapsed/total time all work and audio is audible.
