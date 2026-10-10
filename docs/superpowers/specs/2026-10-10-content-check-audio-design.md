# Content Check Phase 2: audio decoding

Inspect all audio streams in standalone audio files and video containers. Add an
`오디오 / Audio` tab to Content Check for AUDIO and VIDEO. Images and embedded
motion-photo video remain under their existing checks in this phase.

Use ffprobe to enumerate audio stream indices, codec, declared sample rate,
channel count and stream duration. Decode each stream independently with FFmpeg
and `ashowinfo`, without forcing a sample rate or channel count. Count decoded
frames and samples, accumulate duration using each frame's actual rate, and
collect observed rates and channel counts. A successful process with zero decoded
samples is a failure. Decoder warnings/errors and metadata mismatches are issues.
An absent duration is unknown, not a mismatch. Duration tolerance is the larger
of 100 ms and two of the largest decoded audio frames, to accommodate priming,
padding and container rounding. Report this tolerance explicitly.

No audio streams in a successfully probed video means not applicable. Probe
failure, missing tools and timeouts must never look like a clean/no-audio result.
Each child process uses the existing cancellable 30-minute process runner.
Cancellation stops the inspection; completed results from an older run cannot
replace a newer one. Continue inspecting subsequent streams after one fails.
Capture at most 2,000 log lines per phase and preserve truncation information.

Expose per-stream facts and mismatches in the Audio tab, AI prompt, copied JSON
and saved analysis case. `check --decode` includes audio for AUDIO/VIDEO, with an
additive `audioIntegrity` object. Keep case schema 1, no overwrite on save, and
existing path scrubbing. Samsung SEF capture timestamps and MCC remain included
by explicit user decision. Byte-level audio format validators are phase 3.
