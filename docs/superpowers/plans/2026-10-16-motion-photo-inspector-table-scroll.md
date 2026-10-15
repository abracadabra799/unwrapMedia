# Motion Photo Inspector Table Scroll & Dynamic Row Height Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove text truncation (`maxLines`/`Ellipsis`), support dynamic row heights for multiline text, provide generous column widths, and add horizontal scroll with `HorizontalScrollbar` and text selection.

**Architecture:**
In `MotionPhotoInspectorWindow.kt`:
- Replace fixed small column widths with generous widths (`Status`: 64dp, `Check Item`: 220dp, `Expected`: 260dp, `Actual`: 260dp, `Detail`: 520dp).
- Remove `maxLines` and `overflow = TextOverflow.Ellipsis` from all text cells so text wraps naturally and rows expand dynamically.
- Place `LazyColumn` inside a container supporting `Modifier.horizontalScroll(hScroll)` with `Modifier.width(1350.dp)`.
- Attach `HorizontalScrollbar(adapter = rememberScrollbarAdapter(hScroll))` at the bottom and `VerticalScrollbar(adapter = rememberScrollbarAdapter(lazyListState))` at the right.
- Enclose table cells in `SelectionContainer` so text can be copied with mouse drag.

**Spec:** `docs/superpowers/specs/2026-10-16-motion-photo-inspector-table-scroll-design.md`

---

## Global Constraints

- JDK 21; Compose Desktop (Material3); `androidx.compose.material3.Text` only.
- Bilingual labels (KO/EN).
- Conventional commit messages.

---

### Task 1: Update MotionPhotoInspectorWindow table layout and scrolling

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/MotionPhotoInspectorWindow.kt`

- [x] Add `hScroll = rememberScrollState()` and `lazyListState = rememberLazyListState()`.
- [x] Remove `maxLines` and `TextOverflow.Ellipsis` on Expected, Actual, and Detail cells.
- [x] Wrap rows in `SelectionContainer`.
- [x] Set minimum column widths (Status: 64dp, Check: 220dp, Expected: 260dp, Actual: 260dp, Detail: 520dp).
- [x] Wrap `LazyColumn` with width `1350.dp` inside horizontal scroll box.
- [x] Add `HorizontalScrollbar` at bottom and `VerticalScrollbar` at right edge.
- [x] Connect "🎬 동영상 보기" to `MotionPhotoVideoWindow` with async video extraction.
- [x] Add remediation guide banner rendering when WARN/FAIL.
- [x] Run `./gradlew :app:compileKotlin` to verify compilation.

---

### Task 2: Primary XMP comprehensive validation & Remediation in Engine

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/motionphoto/MotionPhotoInspectorEngine.kt`
- Modify: `app/src/test/kotlin/com/multiviewer/parser/motionphoto/MotionPhotoInspectorEngineTest.kt`

- [x] Verify Google namespaces, top-level flags, and all `Container:Directory` items (Primary, MotionPhoto, GainMap).
- [x] Calculate and validate cumulative length of all secondary streams.
- [x] Populate actionable `remediation` guides for all WARN/FAIL items across 5 categories.
- [x] Run `./gradlew :app:test --tests 'com.multiviewer.parser.motionphoto.*'`.
- [x] Run full test suite: `./gradlew :app:test`.

---

### Task 3: Commit, merge and rerun app

- [x] Commit changes on feature branch.
- [x] Fast-forward merge to `main`.
- [x] Rerun app with `./gradlew :app:run`.
