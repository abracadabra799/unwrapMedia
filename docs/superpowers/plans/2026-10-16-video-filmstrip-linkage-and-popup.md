# Video Filmstrip Linkage and Popup Inspection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Link the video live player to GOP and filmstrip frame selections, indicate filmstrip interactions clearly, and separate single-click seek from double-click/button popup inspection.

**Architecture:**
1. Filmstrip UI (`FrameThumbnailFilmstrip.kt`): Add a compact sub-header with bilingual interaction guide and an explicit `[ ↗ 정밀 검사 팝업 ]` AssistChip. Convert thumbnail cell click to `combinedClickable` where single click selects/seeks and double click opens popup.
2. Video Inspector Wiring (`VideoInspectorUI.kt`): Pass `seekRequestSeconds = tab.seekTargetSeconds` and `seekRequestTick = tab.seekRequestTick` to `FfmpegVideoPlayer`. When live playback is paused, update `tab.selectedFrame` to match the current playback position without incurring continuous overhead during playback.
3. Keep AV2 custom decoder fallback untouched.

**Tech Stack:** Kotlin, Compose Desktop (Material3), ffmpeg/ffprobe, JUnit/Gradle.

**Spec:** `docs/superpowers/specs/2026-10-16-video-filmstrip-linkage-and-popup-design.md`

## Global Constraints

- JDK 21; Material3 Text only (`androidx.compose.material3.Text`); bilingual (KO/EN) user strings.
- No new heavy dependencies.
- No blocking process or file I/O on the UI thread.
- Zero performance overhead during continuous playback (no continuous recomposition/scrolling).

---

### Task 1: Filmstrip sub-header & combinedClickable interaction

**Files:** `app/src/main/kotlin/com/multiviewer/ui/FrameThumbnailFilmstrip.kt`

- [x] Add compact sub-header above the filmstrip:
  - Title: `🎞️ 프레임 필름스트립` / `🎞️ Frame Filmstrip`
  - Hint: `• 클릭: 플레이어 이동 · 더블클릭: 팝업 검사기` / `• Click: seek player · Double-click: popup inspector`
  - Action chip: `정밀 검사 팝업 ↗` / `Popup Inspector ↗` setting `tab.fullSizeFramePreviewOpen = true`
- [x] Update thumbnail click handler to `combinedClickable`:
  - `onClick`: select frame, update seekTargetSeconds & seekRequestTick
  - `onDoubleClick`: select frame and set `tab.fullSizeFramePreviewOpen = true`
- [x] Run `./gradlew :app:compileKotlin` to verify compilation.

### Task 2: Live player linkage in VideoInspectorUI

**Files:** `app/src/main/kotlin/com/multiviewer/ui/VideoInspectorUI.kt`

- [x] Wire `seekRequestSeconds` and `seekRequestTick` into `FfmpegVideoPlayer` from `tab.seekTargetSeconds` and `tab.seekRequestTick`.
- [x] Wire `onElapsedChanged` to keep track of the current playback position.
- [x] Wire `onPlaybackStateChanged` so when playback pauses (`isPlaying == false`), `tab.selectedFrame` is synchronized to the frame closest to the paused PTS from `tab.gopFrames`.
- [x] Run `./gradlew :app:compileKotlin` to verify compilation.

### Task 3: Unit tests and verification

**Files:** `app/src/test/kotlin/com/multiviewer/ui/VideoInspectorLinkageTest.kt`

- [x] Add unit test verifying frame resolution by playback elapsed seconds on pause.
- [x] Run focused tests: `./gradlew :app:test --tests 'com.multiviewer.ui.*'`.
- [x] Run full test suite: `./gradlew :app:test`.

### Task 4: Version bump & documentation

**Files:** `app/build.gradle.kts`, `app/src/main/kotlin/com/multiviewer/ui/I18n.kt`, `version.json`

- [x] Bump version from `1.28.0` to `1.29.0` across all 3 files.
- [x] Add bilingual release note for the filmstrip UI and live player linkage.
- [x] Verify clean build.
