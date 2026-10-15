# Expand Filmstrip Panel and Fix Bottom Label Clipping Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expand filmstrip panel height by adjusting `gopFilmstripSplit` default and fix bottom label clipping with adequate height and 10sp text.

**Architecture:**
- `VideoInspectorUI.kt`: Change default `gopFilmstripSplit` from `0.7f` to `0.4f`.
- `FrameThumbnailFilmstrip.kt`: Change bottom label `Row` height to `18.dp`, font size to `10.sp`, ensuring text is vertically centered and not clipped.

**Tech Stack:** Kotlin, Compose Desktop (Material3), Gradle.

**Spec:** `docs/superpowers/specs/2026-10-16-expand-filmstrip-panel-and-label-fix-design.md`

## Global Constraints

- JDK 21; Material3 Text only (`androidx.compose.material3.Text`).
- No process wait or file I/O on UI thread.

---

### Task 1: Expand default filmstrip panel split and fix label row sizing

**Files:**
- `app/src/main/kotlin/com/multiviewer/ui/VideoInspectorUI.kt`
- `app/src/main/kotlin/com/multiviewer/ui/FrameThumbnailFilmstrip.kt`

- [ ] Change `gopFilmstripSplit` default to `0.4f` in `VideoInspectorUI.kt`.
- [ ] In `FrameThumbnailFilmstrip.kt`, adjust bottom label `Row` to `height(18.dp)` and `fontSize = 10.sp`.
- [ ] Run `./gradlew :app:compileKotlin` to verify compilation.

### Task 2: Tests and verification

- [ ] Run `./gradlew :app:test --tests 'com.multiviewer.ui.*'`.
- [ ] Run full test suite: `./gradlew :app:test`.

### Task 3: Commit and merge to main

- [ ] Commit changes.
- [ ] Fast-forward merge to `main`.
- [ ] Rerun app with `./gradlew :app:run`.
