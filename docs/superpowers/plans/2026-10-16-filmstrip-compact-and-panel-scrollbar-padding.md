# Filmstrip Compact Size & Panel Scrollbar Margin Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce filmstrip panel share by half (to ~32% share) and add right margin to DetailedPropertiesPanel tabs so scrollbars never overlay text.

**Architecture:**
- `VideoInspectorUI.kt`: Change default `gopFilmstripSplit` to `0.68f`.
- `ImageInspectorUI.kt`: Add end padding `14.dp` to `LazyColumn` in `OverviewTabContent`, `DetailPropertiesTabContent`, and `WarningsTabContent`.

**Tech Stack:** Kotlin, Compose Desktop (Material3), Gradle.

**Spec:** `docs/superpowers/specs/2026-10-16-filmstrip-compact-and-panel-scrollbar-padding-design.md`

## Global Constraints

- JDK 21; Material3 Text only (`androidx.compose.material3.Text`).
- No process wait or file I/O on UI thread.

---

### Task 1: Adjust filmstrip split and add scrollbar end clearance

**Files:**
- `app/src/main/kotlin/com/multiviewer/ui/VideoInspectorUI.kt`
- `app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt`

- [x] Set `gopFilmstripSplit` default to `0.68f` in `VideoInspectorUI.kt`.
- [x] Add `14.dp` end padding to `LazyColumn` in `OverviewTabContent`, `DetailPropertiesTabContent`, and `WarningsTabContent` in `ImageInspectorUI.kt`.
- [x] Run `./gradlew :app:compileKotlin` to verify compilation.

### Task 2: Tests and verification

- [x] Run `./gradlew :app:test --tests 'com.multiviewer.ui.*'`.
- [x] Run full test suite: `./gradlew :app:test`.

### Task 3: Commit, merge and rerun app

- [x] Commit changes.
- [x] Fast-forward merge to `main`.
- [x] Rerun app with `./gradlew :app:run`.
