# Performance and Resource Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bound thumbnail and comparison decode work, cancel stale child processes, and release completed references.

**Architecture:** Keep existing caches and ffmpeg helpers. Add explicit ownership to decode requests, remove cancelled jobs from worker queues, and sample comparison video at a controlled cadence. Verify each lifecycle transition with deterministic tests and real process stress checks.

**Tech Stack:** Kotlin, Compose Desktop, JDK 21, ffmpeg, Gradle tests.

**Spec:** `docs/superpowers/specs/2026-10-15-performance-lifecycle-design.md`

## Global Constraints

- No new heavy dependency.
- Never block the UI thread on file I/O or process termination.
- Version 1.25.0 in Gradle, I18n, and version.json.
- User-visible copy bilingual where added.

## Review Focus

- Rapid filmstrip scrolling: stale batches must not start late or publish after cancellation.
- Filmstrip disposal: pending indices must reset and ffmpeg must terminate.
- Compare playback at 25 clock ticks/second: decode request count must remain bounded.
- Compare file replacement or window close: old callbacks must not overwrite new frames.
- Timeout or interruption during ffmpeg: process and temporary output must be removed.

---

### Task 1: Bounded filmstrip requests

**Files:** `FrameThumbnailDecoder.kt`, `FrameThumbnailFilmstrip.kt`, `FrameThumbnailDecoderTest.kt`

**Interfaces:** `decodeRangeAsync(...)` keeps its existing signature; add a cancellable queue mechanism inside the decoder and a viewport request owner in the filmstrip.

- [ ] Write tests for cancellation before start, rapid viewport replacement, and completion callback behavior.
- [ ] Run focused tests and observe the expected failure.
- [ ] Implement bounded active/queued requests and remove canceled tasks promptly; ensure process termination on interruption.
- [ ] Run focused tests and the full suite; commit `fix: bound filmstrip thumbnail work`.

### Task 2: Comparison playback lifecycle

**Files:** `CompareVisualDiffView.kt`, a focused test under `ui/`

**Interfaces:** Add a pure preview timestamp sampler and/or a request owner; leave the 40 ms playback clock intact.

- [ ] Write failing tests for no more than four preview requests per second and for obsolete callback rejection.
- [ ] Run focused test and observe failure.
- [ ] Use sampled timestamps as effect keys, cancel both futures on effect disposal, and accept only current results.
- [ ] Run focused tests and the full suite; commit `fix: cancel stale comparison frame decodes`.

### Task 3: One-shot fallback and compare window cleanup

**Files:** `FfmpegImageSnapshotDecoder.kt`, `ImageCompareWindow.kt`, `CompareExplorerView.kt` if needed, focused tests.

**Interfaces:** Return cancellable `Future` from async snapshot functions while preserving call-site defaults; add a comparison-window task owner that cancels on close/file replacement.

- [ ] Write failing tests that interrupt a silent ffmpeg process and verify its death; test stale compare load rejection.
- [ ] Run focused tests and observe failure.
- [ ] Replace per-call threads with a bounded pool, wire cancellation to window lifecycle, and release listener references for discarded thumbnail requests.
- [ ] Run focused tests and the full suite; commit `fix: release comparison decode work on close`.

### Task 4: Process audit, release, and integration

**Files:** only proven process lifecycle violations, `app/build.gradle.kts`, `I18n.kt`, `version.json`, audit notes.

- [ ] Audit direct process starts against registration/timeout/interruption/cleanup; write regression tests before any fix.
- [ ] Run focused tests, then `./gradlew :app:test :app:compileKotlin` and lifecycle stress tests.
- [ ] Update all three version locations to 1.25.0, release notes and audit evidence; commit `chore: release 1.25.0`.
- [ ] Review whole branch diff, fast-forward `main`, and push `origin main` as explicitly requested.
