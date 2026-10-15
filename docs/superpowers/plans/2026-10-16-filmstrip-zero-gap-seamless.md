# Filmstrip Zero Gap Seamless Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove horizontal gaps and letterboxing between adjacent filmstrip frames so thumbnails form a seamless, continuous film reel.

**Architecture:**
In `FrameThumbnailFilmstrip.kt`:
- Remove `padding(horizontal = 1.dp)` on the `Column`.
- Set `contentScale = ContentScale.Crop` on the thumbnail `Image`.
- Keep clean 18dp bottom label row centered per cell.

**Tech Stack:** Kotlin, Compose Desktop (Material3), Gradle.

**Spec:** `docs/superpowers/specs/2026-10-16-filmstrip-zero-gap-seamless-design.md`

## Global Constraints

- JDK 21; Material3 Text only (`androidx.compose.material3.Text`).
- No process wait or file I/O on UI thread.

---

### Task 1: Remove gaps between frames and use ContentScale.Crop

**Files:** `app/src/main/kotlin/com/multiviewer/ui/FrameThumbnailFilmstrip.kt`

- [x] Remove horizontal padding on cell `Column`.
- [x] Change `contentScale` on `Image` to `ContentScale.Crop`.
- [x] Run `./gradlew :app:compileKotlin` to verify compilation.

### Task 2: Tests and verification

- [x] Run `./gradlew :app:test --tests 'com.multiviewer.ui.*'`.
- [x] Run full test suite: `./gradlew :app:test`.

### Task 3: Commit, merge and rerun app

- [x] Commit changes.
- [x] Fast-forward merge to `main`.
- [x] Rerun app with `./gradlew :app:run`.
