# Filmstrip Bottom Label Track Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move frame index and type labels from the inside of filmstrip thumbnails to a dedicated bottom label track below each thumbnail, leaving video frames 100% visible.

**Architecture:**
In `FrameThumbnailFilmstrip.kt`, restructure each item in `LazyRow`:
- Wrap each item in a `Column` with aspect-ratio-constrained width and full height.
- Place the clean video frame image in a `Box` with `weight(1f)`.
- Place a compact label row underneath (height ~14dp) with `#<index>` and colored frame type `I` / `P` / `B`.
- Keep `combinedClickable` on the cell.

**Tech Stack:** Kotlin, Compose Desktop (Material3), JUnit/Gradle.

**Spec:** `docs/superpowers/specs/2026-10-16-filmstrip-bottom-label-track-design.md`

## Global Constraints

- JDK 21; Material3 Text only (`androidx.compose.material3.Text`).
- No process wait or file I/O on UI thread.
- Maintain existing click and double-click behavior.

---

### Task 1: Restructure filmstrip thumbnail cells with bottom label track

**Files:** `app/src/main/kotlin/com/multiviewer/ui/FrameThumbnailFilmstrip.kt`

- [ ] Remove `PreviewCaption` from inside the thumbnail `Box`.
- [ ] Restructure cell to `Column(modifier = ...)`:
  - Top `Box(modifier = Modifier.weight(1f)...)`: renders clean thumbnail image with selection border.
  - Bottom `Row(modifier = Modifier.fillMaxWidth().height(14.dp)...)`: renders `#<index>` in 9sp and frame type with `colorForFrameType(frame.type)`.
- [ ] Run `./gradlew :app:compileKotlin` to verify compilation.

### Task 2: Tests and verification

**Files:** `FrameThumbnailDecoderTest.kt`, UI test suite.

- [ ] Run `./gradlew :app:test --tests 'com.multiviewer.ui.*'`.
- [ ] Run `./gradlew :app:test`.

### Task 3: Commit and merge to main

- [ ] Commit changes with conventional commit message (`refactor:` or `feat:`).
- [ ] Merge to `main` via fast-forward.
- [ ] Verify clean build.
