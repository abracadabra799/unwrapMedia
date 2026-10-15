# Video Filmstrip Playback Inspection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make a filmstrip popup suitable for locating playback hitches by source frame.

**Architecture:** Extend the existing ffmpeg player with frame and drop events; keep its timeline sourced from the analyzed filmstrip. Turn the existing still popup into a two-view player/inspect window and show independent source and UI timing observations.

**Tech Stack:** Kotlin, Compose Desktop, ffmpeg/ffprobe, JUnit/Gradle.

**Spec:** `docs/superpowers/specs/2026-10-15-video-filmstrip-playback-inspection-design.md`

## Global Constraints

- JDK 21; no new heavy dependency; bilingual new UI strings.
- No process wait or file I/O on the UI thread.
- The popup owns all decoding and disposes it on close or tab close.
- Version 1.27.0 in Gradle, I18n, and version.json.

## Review Focus

- Clicking frame N must initially show frame N, not start at video time zero.
- Arrow stepping on variable-rate video must follow source indices, including first/last bounds.
- Playback must not trigger a full-resolution ffmpeg decode on every presented frame.
- File PTS gaps, UI callback delay, and intentionally dropped playback frames must be labeled separately.
- AV2 must not silently enter an unsupported ffmpeg seek path.

---

### Task 1: Timing model and player events

**Files:** `PlaybackTimingTracker.kt`, `FfmpegVideoPlayer.kt`, focused tests.

- [x] Write failing tests for source intervals, UI intervals, drop counts, reset and bounded anomalies.
- [x] Implement the timing tracker and player callbacks with explicit process ownership.
- [x] Run focused tests and review the event semantics.

### Task 2: Two-view filmstrip popup

**Files:** `FrameFullSizePreviewWindow.kt`, `VideoInspectorUI.kt`, `Main.kt`, tests.

- [x] Write failing tests for initial selected-frame seek and navigation mapping.
- [x] Add paused playback, native still mode, live metrics, recent events and interval-window link.
- [x] Avoid duplicate inline playback and AV2 false support.
- [x] Run focused tests and review UI lifecycle.

### Task 3: Release and integration

**Files:** `app/build.gradle.kts`, `I18n.kt`, `version.json`, docs.

- [x] Update all three version locations and release notes.
- [x] Run full tests and Kotlin compilation, inspect diff and process cleanup.
- [ ] Commit, fast-forward main and push origin/main under the user's existing go-ahead.
