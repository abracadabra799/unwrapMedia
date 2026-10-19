# Windows player pacing repair

## Problem

Video, motion photo preview, and frame popup share `FfmpegVideoPlayer`. Its video reader uses the audio clock while the PCM pipe is active, then switches to a fresh self-paced clock as soon as `VideoAudioTrack.ended` becomes true. `ended` currently means PCM input EOF, before the output line has drained. If the video reader is behind, this switch plays the remaining frames after the audio timeline has ended. The progress bar follows the audio clock and can therefore reach the end before the picture.

## Design

Keep one audio-derived playback timeline through PCM EOF. While audio renders, use `SourceDataLine.microsecondPosition`, retaining its last value if a Windows mixer resets it when the line closes. At EOF, extrapolate the last clock sample with monotonic time, excluding paused time. Use this timeline both to decide whether a decoded video frame is due or stale and to display progress. A failed or absent audio track still uses the existing video-only pacing path. The final video pipe EOF remains the completion event.

Collect bounded diagnostics for playback stalls: frame read time, bitmap creation time, output dimensions, video timestamp, audio/timeline position, and dropped frame count. Emit a summary only when video falls materially behind; this distinguishes Windows raw-pipe throughput from a clock error without generating per-frame log spam.

## Verification

Add deterministic clock tests for PCM EOF, pause/resume, and no audio. Add a regression test showing a stale frame remains a drop after PCM EOF. Run focused player tests and the full app suite. Windows runtime validation remains necessary for raw-pipe throughput and the three affected UI surfaces; macOS tests can verify timing logic but cannot measure the Windows pipe.
