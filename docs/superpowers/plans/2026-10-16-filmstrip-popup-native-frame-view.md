# Filmstrip Popup Native Frame View with Zoom/Pan Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a "View Native Frame" button in the filmstrip popup that displays the full-resolution decoded frame using `PixelInspectorPreview` with zoom in/out, pan, and arrow key stepping.

**Architecture:**
In `FrameFullSizePreviewWindow.kt`:
- Add `var nativeView by remember(tab) { mutableStateOf(av2) }`.
- Add an `AssistChip` in the header row to toggle between Player and Native Frame modes.
- Trigger `FrameFullSizeDecoder.decodeFrameAtPtsAsync` when `nativeView` is true.
- Render `PixelInspectorPreview` when `nativeView` is true.
- Gate Spacebar playback toggle on `!nativeView`.

**Tech Stack:** Kotlin, Compose Desktop (Material3), Gradle.

**Spec:** `docs/superpowers/specs/2026-10-16-filmstrip-popup-native-frame-view-design.md`

## Global Constraints

- JDK 21; Material3 Text only (`androidx.compose.material3.Text`).
- No process wait or file I/O on UI thread.
- Bilingual labels (KO/EN).

---

### Task 1: Add nativeView state and toggle button in FrameFullSizePreviewWindow

**Files:** `app/src/main/kotlin/com/multiviewer/ui/FrameFullSizePreviewWindow.kt`

- [x] Add `var nativeView by remember(tab) { mutableStateOf(av2) }`.
- [x] In header `Row`, add toggle `AssistChip` (when `!av2`).
- [x] Update `LaunchedEffect` to decode frame bitmap when `nativeView` is true (not just `av2`).
- [x] Display `PixelInspectorPreview` when `nativeView` is true; display `FfmpegVideoPlayer` when `nativeView` is false.
- [x] Run `./gradlew :app:compileKotlin` to verify compilation.

### Task 2: Tests and verification

- [x] Run `./gradlew :app:test --tests 'com.multiviewer.ui.*'`.
- [x] Run full test suite: `./gradlew :app:test`.

### Task 3: Commit, merge and rerun app

- [x] Commit changes.
- [x] Fast-forward merge to `main`.
- [x] Rerun app with `./gradlew :app:run`.
