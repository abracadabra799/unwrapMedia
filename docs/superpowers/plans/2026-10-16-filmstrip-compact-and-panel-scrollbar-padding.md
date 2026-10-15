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

- [ ] Set `gopFilmstripSplit` default to `0.68f` in `VideoInspectorUI.kt`.
- [ ] Add `14.dp` end padding to `LazyColumn` in `OverviewTabContent`, `DetailPropertiesTabContent`, and `WarningsTabContent` in `ImageInspectorUI.kt`.
- [ ] Run `./gradlew :app:compileKotlin` to verify compilation.

### Task 2: Tests and verification

- [ ] Run `./gradlew :app:test --tests 'com.multiviewer.ui.*'`.
- [ ] Run full test suite: `./gradlew :app:test`.

### Task 3: Commit, merge and rerun app

- [ ] Commit changes.
- [ ] Fast-forward merge to `main`.
- [ ] Rerun app with `./gradlew :app:run`.
