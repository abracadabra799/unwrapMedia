# Content Check Audio Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Verify all audio streams and expose actual decoding evidence in Content Check, AI diagnosis and JSON.

**Architecture:** Add an independent audio inspector using the existing cancellable process runner. Keep stream parsing and decoded-frame accumulation testable without a GUI. Integrate its immutable report into the existing planner, window and exports.

**Tech Stack:** Kotlin/JVM, Compose Desktop, coroutines, FFmpeg/ffprobe, JUnit 5.

**Spec:** `docs/superpowers/specs/2026-10-10-content-check-audio-design.md`

## Global Constraints

- Continue in the existing content-check worktree; preserve other branches and user changes.
- No sample-rate/channel coercion before measuring decoded frames.
- Duration tolerance: max(100 ms, twice the largest decoded frame duration).
- Existing 30-minute timeout/cancellation and 2,000-line log bound.
- Additive JSON schema 1 fields; retain SEF timestamp and MCC.

## Review Focus

- Video without audio is not applicable; failed probing is a failure.
- Multiple tracks with distinct rates/channels must retain separate results.
- Successful exit with no samples cannot pass.
- Missing duration and codec padding must not cause false damage reports.
- Cancel or tool failure must not leave a process or stale clean result.

### Task 1: Audio inspector

**Files:** Create `ui/AudioIntegrity.kt` and `ui/AudioIntegrityTest.kt` under the existing main/test Kotlin package roots.

**Interfaces:** `suspend fun inspectAudioIntegrity(file: File, ffmpeg: String = FfmpegLocator.ffmpegPath(), ffprobe: String = FfmpegLocator.ffprobePath()): AudioIntegrityReport`; immutable report with overall status, per-stream metadata/observations, logs, mismatches, and `toJsonValue()`.

- [ ] Add tests for compact ffprobe stream parsing, ashowinfo frame parsing, missing duration, rate/channel mismatches, duration tolerance and zero samples.
- [ ] Run targeted tests, then implement stream-by-stream decoding using `integrityProcess`.
- [ ] Add real FFmpeg tests: clean WAV, two-track container, silent video without audio, truncated WAV and cancellation. Missing executables return FAILED.
- [ ] Run `timeout 900 ./gradlew :app:test --tests '*AudioIntegrityTest'` and commit the inspector.

### Task 2: UI, AI and export integration

**Files:** Modify `ui/ContentCheckPlan.kt`, `ui/ContentCheckWindow.kt`, `ui/ContentCheckSnapshot.kt`, `cli/CheckFile.kt`; create `ui/AudioCheckPanel.kt`; extend planner, snapshot and CLI tests; update README.

**Interfaces:** Consume `AudioIntegrityReport` and `inspectAudioIntegrity`; append optional audio report/error fields to snapshots and JSON builders to preserve current callers.

- [ ] Extend planner tests: AUDIO → structure/audio, VIDEO → existing tabs plus audio; heavy steps include audio inspection.
- [ ] Render per-stream status, observed vs declared duration/rate/channels, sample counts, capped logs and mismatches; distinguish not run, failed and no audio.
- [ ] Run audio after video inspection, preserving independent failure handling and current-run guards. Publish results to AI and both JSON paths; add CLI `--decode` integration.
- [ ] Test AI inclusion/path scrubbing and JSON additive fields; run targeted tests then the full suite.
- [ ] Inspect real sample output, review diff, record verification and commit.
