# Motion Photo Frame Inspection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make motion-photo hitches inspectable at an exact embedded-video frame.

**Architecture:** Add a popup around the existing extracted-video preview and reuse its file. Extend the player with source-frame reporting and index-based navigation; link the existing frame-interval window to the popup.

**Tech Stack:** Kotlin, Compose Desktop, ffmpeg/ffprobe, JUnit/Gradle.

**Spec:** `docs/superpowers/specs/2026-10-15-motion-photo-frame-inspection-design.md`

## Global Constraints

- JDK 21; no new heavy dependency; bilingual user-facing copy.
- No blocking file I/O or process wait on the UI thread.
- Embedded video remains tied to the image tab and its temporary file lifetime.

## Review Focus

- A variable-rate clip must step to the adjacent PTS entry, not average-FPS time.
- First/last frame arrows must remain in range.
- A delayed ffprobe or decode must not report a guessed exact frame number.
- Popup close and tab disposal must stop playback and release the temporary file.
- Switching tabs must close the originating tab's popup and analysis windows, preventing cross-tab seeks.

---

### Task 1: Exact frame position and player callback

**Files:** `FfmpegVideoPlayer.kt`, `FfmpegVideoPlayerTest.kt`

- [x] Write tests for variable-rate stepping, boundaries, and PTS position.
- [x] Run focused tests and observe expected failure.
- [x] Add an index-based position helper and optional frame-presented callback to the player.
- [x] Run focused tests and commit.

### Task 2: Popup and shared embedded-video lifetime

**Files:** `ImageInspectorUI.kt`, `MotionPhotoVideoWindow.kt`, focused helper tests.

- [x] Reuse the tested timestamp index for popup navigation; keep the existing real-video timestamp probe tests.
- [x] Add popup action, one active player, exact frame caption and seek; share the extracted file.
- [x] Run focused tests and commit.

### Task 3: Interval selection and verification

**Files:** `FrameIntervalAnalysisView.kt`, `Main.kt` if needed, related tests.

- [x] Test interval selection to frame navigation, including duplicate PTS.
- [x] Run focused tests and observe expected failure.
- [x] Link interval rows to popup seek, preserve tab ownership and existing menu behavior.
- [x] Run all tests and Kotlin compilation, review diff, commit and fast-forward main.
