# Raw Pixel Viewer and Hex Viewer Zoom Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add scroll-to-zoom/drag-to-pan to the Raw Pixel viewer (reusing the image viewer's `PixelInspectorPreview`, with zoom persisting across multi-frame playback) and Cmd/Ctrl+scroll font-size zoom to the Hex viewer.

**Architecture:** `PixelInspectorPreview` gains an optional `resetKey` parameter so its `remember`-keyed zoom/pan state can be decoupled from the specific `ImageBitmap` instance being displayed — needed because the Raw Pixel viewer swaps to a new `ImageBitmap` on every played frame but should not reset zoom on every frame the way the image viewer's existing call sites correctly do today. `HexView` gains a pure `hexZoomFontSize` function (mirroring `zoomTowardPoint`'s shape) plus a Cmd/Ctrl-gated scroll handler that scales its monospace grid's font size without disturbing the list's own plain-scroll navigation.

**Tech Stack:** Kotlin, Compose Desktop (`remember`, `onPointerEvent`, `PointerEventType.Scroll`, `PointerKeyboardModifiers`).

## Global Constraints

- Raw Pixel viewer: zoom/pan must persist across frame changes during multi-frame playback, resetting only on file/tab switch.
- Raw Pixel viewer: existing call sites of `PixelInspectorPreview` (image viewer thumbnail/primary, GIF filmstrip) must be unaffected — `resetKey` defaults to `bitmap`, preserving current behavior exactly.
- Hex viewer: zoom triggers only on Cmd (macOS) or Ctrl (Windows/Linux) + scroll; plain scroll must continue to scroll the byte list exactly as it does today.
- Hex viewer: font size range `8f`..`28f` sp (default `12f`, matching today's hardcoded size), `lineHeight` scales with it at the same 4:3 ratio the current `12.sp`/`16.sp` pair uses.
- Hex viewer: zoom level resets per-file (matches this app's existing per-file-reset convention).
- No horizontal scrolling added to the Hex viewer (rows may clip at high zoom in narrow panels — accepted trade-off, not a defect to fix in this plan).
- No changes to the video player, frame interval analysis graph, or Media Structure tree (out of scope).

---

### Task 1: `PixelInspectorPreview` gains a `resetKey`, wired into the Raw Pixel viewer

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/PixelInspectorPreview.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/RawPixelInspectorUI.kt`
- Test (unchanged, verifies no regression): `app/src/test/kotlin/com/multiviewer/ui/PixelInspectorPreviewTest.kt`

**Interfaces:**
- Produces: `PixelInspectorPreview(bitmap: ImageBitmap, modifier: Modifier = Modifier, resetKey: Any = bitmap)` — the new third parameter. No other task depends on this signature beyond this task's own second step.

- [ ] **Step 1: Change `PixelInspectorPreview`'s state keys from `bitmap` to a new `resetKey` parameter**

In `app/src/main/kotlin/com/multiviewer/ui/PixelInspectorPreview.kt`, find:

```kotlin
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PixelInspectorPreview(bitmap: ImageBitmap, modifier: Modifier = Modifier) {
    var scale by remember(bitmap) { mutableStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    var boxSize by remember(bitmap) { mutableStateOf(Size.Zero) }
```

Replace with:

```kotlin
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PixelInspectorPreview(bitmap: ImageBitmap, modifier: Modifier = Modifier, resetKey: Any = bitmap) {
    var scale by remember(resetKey) { mutableStateOf(1f) }
    var offset by remember(resetKey) { mutableStateOf(Offset.Zero) }
    var boxSize by remember(resetKey) { mutableStateOf(Size.Zero) }
```

Then find (there are two occurrences of this exact line in the same file):

```kotlin
            .pointerInput(bitmap) {
```

Replace **both** occurrences with:

```kotlin
            .pointerInput(resetKey) {
```

Defaulting `resetKey` to `bitmap` means every existing call site (`ImageInspectorUI.kt`'s thumbnail/primary boxes, `GifFilmstripPlayer.kt`'s two call sites) keeps its exact current behavior with no changes needed there.

- [ ] **Step 2: Run the existing test suite to confirm no regression**

Run: `./gradlew :app:test --tests "com.multiviewer.ui.PixelInspectorPreviewTest"`
Expected: PASS, same test count as before this change — `zoomTowardPoint`/`clampPanOffset`/`panToPoint` are untouched by this step, so nothing here should change their behavior.

- [ ] **Step 3: Wire the Raw Pixel viewer to use `PixelInspectorPreview` with `resetKey = tab.file`**

In `app/src/main/kotlin/com/multiviewer/ui/RawPixelInspectorUI.kt`, find:

```kotlin
                    val bitmap = tab.imageForensic?.bitmap
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                    } else {
```

Replace with:

```kotlin
                    val bitmap = tab.imageForensic?.bitmap
                    if (bitmap != null) {
                        PixelInspectorPreview(
                            bitmap = bitmap,
                            modifier = Modifier.fillMaxSize(),
                            resetKey = tab.file,
                        )
                    } else {
```

`tab.file` is stable across frame changes (playback only mutates `tab.rawPixelFrameIndex`/`tab.imageForensic.bitmap`, per the `LaunchedEffect` above this block) but changes when the user switches to a different file/tab — exactly the reset boundary this plan wants. `PixelInspectorPreview` is in the same `com.multiviewer.ui` package as this file, so no new import is needed.

The `Image` import (`androidx.compose.foundation.Image`) and `ContentScale` import may now be unused in this file if nothing else in it uses them — leave them; do not remove imports as part of this step (a later unused-import cleanup, if any, is out of scope here since removing an import that's actually still used elsewhere in a large file is a common self-inflicted compile break — let the compiler in Step 4 tell you definitively).

- [ ] **Step 4: Compile**

Run: `./gradlew :app:compileKotlin`
Expected: `BUILD SUCCESSFUL`. If it fails with an unused-import *warning* (not an error), that's fine and expected — Kotlin does not error on unused imports. If it fails with an *error* naming `Image` or `ContentScale` as unresolved somewhere else in the file, that means one of those imports was still needed elsewhere; do not have removed it (Step 3 doesn't ask you to touch imports at all, so this should not happen — investigate if it does).

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew :app:test`
Expected: `BUILD SUCCESSFUL`, all tests pass, no regressions.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/PixelInspectorPreview.kt app/src/main/kotlin/com/multiviewer/ui/RawPixelInspectorUI.kt
git commit -m "Add zoom/pan to the Raw Pixel viewer, persisting across playback frames"
```

---

### Task 2: `hexZoomFontSize` pure function

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/HexView.kt`
- Test: `app/src/test/kotlin/com/multiviewer/ui/HexViewTest.kt` (new)

**Interfaces:**
- Produces: `const val MIN_HEX_FONT_SP = 8f`, `const val MAX_HEX_FONT_SP = 28f`, `fun hexZoomFontSize(currentSp: Float, scrollDeltaY: Float): Float` — Task 3 wires this into `HexView`'s scroll handler in the same file.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/multiviewer/ui/HexViewTest.kt`:

```kotlin
package com.multiviewer.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HexViewTest {
    @Test
    fun `hexZoomFontSize increases font size on scroll-up (negative delta)`() {
        val result = hexZoomFontSize(currentSp = 12f, scrollDeltaY = -1f)
        assertTrue(result > 12f, "Expected font size to increase on scroll-up, got $result")
    }

    @Test
    fun `hexZoomFontSize decreases font size on scroll-down (positive delta)`() {
        val result = hexZoomFontSize(currentSp = 12f, scrollDeltaY = 1f)
        assertTrue(result < 12f, "Expected font size to decrease on scroll-down, got $result")
    }

    @Test
    fun `hexZoomFontSize clamps at MAX_HEX_FONT_SP`() {
        val result = hexZoomFontSize(currentSp = MAX_HEX_FONT_SP, scrollDeltaY = -100f)
        assertEquals(MAX_HEX_FONT_SP, result)
    }

    @Test
    fun `hexZoomFontSize clamps at MIN_HEX_FONT_SP`() {
        val result = hexZoomFontSize(currentSp = MIN_HEX_FONT_SP, scrollDeltaY = 100f)
        assertEquals(MIN_HEX_FONT_SP, result)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:test --tests "com.multiviewer.ui.HexViewTest"`
Expected: FAIL to compile — `hexZoomFontSize`/`MIN_HEX_FONT_SP`/`MAX_HEX_FONT_SP` are unresolved references (they don't exist yet).

- [ ] **Step 3: Write the implementation**

In `app/src/main/kotlin/com/multiviewer/ui/HexView.kt`, find:

```kotlin
private const val BYTES_PER_ROW = 16
```

Replace with:

```kotlin
private const val BYTES_PER_ROW = 16

const val MIN_HEX_FONT_SP = 8f
const val MAX_HEX_FONT_SP = 28f
private const val HEX_ZOOM_STEP_FACTOR = 0.08f // matches PixelInspectorPreview's ZOOM_STEP_FACTOR

// Cmd/Ctrl+scroll font-size zoom for the hex/ASCII grid below. Scroll-up (negative delta) zooms
// in, matching PixelInspectorPreview's own scroll-up-zooms-in convention.
fun hexZoomFontSize(currentSp: Float, scrollDeltaY: Float): Float =
    (currentSp * (1f - scrollDeltaY * HEX_ZOOM_STEP_FACTOR)).coerceIn(MIN_HEX_FONT_SP, MAX_HEX_FONT_SP)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:test --tests "com.multiviewer.ui.HexViewTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew :app:test`
Expected: `BUILD SUCCESSFUL`, all tests pass, no regressions.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/HexView.kt app/src/test/kotlin/com/multiviewer/ui/HexViewTest.kt
git commit -m "Add hexZoomFontSize for Hex viewer font-size zoom"
```

---

### Task 3: Wire Cmd/Ctrl+scroll font-size zoom into `HexView`

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/HexView.kt`

**Interfaces:**
- Consumes: `MIN_HEX_FONT_SP`, `MAX_HEX_FONT_SP`, `hexZoomFontSize` (Task 2, same file, no import needed).

- [ ] **Step 1: Add the new pointer-input imports**

In `app/src/main/kotlin/com/multiviewer/ui/HexView.kt`, find:

```kotlin
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
```

Replace with:

```kotlin
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
```

- [ ] **Step 2: Add the per-file font-size state**

In `app/src/main/kotlin/com/multiviewer/ui/HexView.kt`, find:

```kotlin
    var selectionAnchor by remember(file) { mutableStateOf<Long?>(null) }
    var selectionEnd by remember(file) { mutableStateOf<Long?>(null) }
```

Replace with:

```kotlin
    var selectionAnchor by remember(file) { mutableStateOf<Long?>(null) }
    var selectionEnd by remember(file) { mutableStateOf<Long?>(null) }
    var fontSizeSp by remember(file) { mutableStateOf(12f) }
```

- [ ] **Step 3: Add the Cmd/Ctrl+scroll handler and use `fontSizeSp` in the row style**

In `app/src/main/kotlin/com/multiviewer/ui/HexView.kt`, find:

```kotlin
                    Box(
                        modifier = Modifier.fillMaxSize().pointerInput(file) {
                            awaitEachGesture {
```

Replace with:

```kotlin
                    Box(
                        modifier = Modifier.fillMaxSize()
                            .onPointerEvent(PointerEventType.Scroll, pass = PointerEventPass.Initial) { event ->
                                val modifiers = event.keyboardModifiers
                                if (!modifiers.isCtrlPressed && !modifiers.isMetaPressed) return@onPointerEvent
                                val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: return@onPointerEvent
                                fontSizeSp = hexZoomFontSize(fontSizeSp, delta)
                                event.changes.forEach { it.consume() }
                            }
                            .pointerInput(file) {
                            awaitEachGesture {
```

Then find:

```kotlin
                                Text(
                                    // Smaller than the app's default 14sp body text -- at 14sp a
                                    // row ("%08X  " + 16 "XX " hex groups + 16 ASCII chars, ~75
                                    // monospace chars) could exceed the panel's available width
                                    // and wrap, breaking the hex/ASCII column alignment this grid
                                    // depends on. softWrap = false is a second guard against the
                                    // same failure mode if the panel is ever narrower than a row.
                                    style = AppTypography.bodyLarge.copy(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp),
```

Replace with:

```kotlin
                                Text(
                                    // Smaller than the app's default 14sp body text -- at the
                                    // default 12sp a row ("%08X  " + 16 "XX " hex groups + 16
                                    // ASCII chars, ~75 monospace chars) could exceed the panel's
                                    // available width and wrap, breaking the hex/ASCII column
                                    // alignment this grid depends on. softWrap = false is a second
                                    // guard against the same failure mode if the panel is ever
                                    // narrower than a row -- still true at any zoom level, since a
                                    // larger fontSizeSp only makes a row wider, never narrower.
                                    // lineHeight keeps the same 4:3 ratio to fontSizeSp that the
                                    // fixed 12sp/16sp pair had, at every zoom level.
                                    style = AppTypography.bodyLarge.copy(
                                        fontSize = fontSizeSp.sp,
                                        lineHeight = (fontSizeSp * (16f / 12f)).sp,
                                        letterSpacing = 0.2.sp,
                                    ),
```

The grid's column math (`OFFSET_PREFIX_CHARS`, `HEX_SECTION_CHARS`, `charIndexToByteIndex`) is purely character-count-based and independent of the actual rendered font size, so no other layout code needs to change — this is a monospace font, so scaling `fontSize` uniformly keeps every row's hex/ASCII columns aligned at any zoom level.

- [ ] **Step 4: Compile**

Run: `./gradlew :app:compileKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew :app:test`
Expected: `BUILD SUCCESSFUL`, all tests pass, no regressions.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/HexView.kt
git commit -m "Wire Cmd/Ctrl+scroll font-size zoom into HexView"
```

---

### Task 4: Manual verification

**Files:** None (no code changes).

- [ ] **Step 1: Run the app**

```bash
./gradlew :app:run
```

- [ ] **Step 2: Verify Raw Pixel viewer zoom**

- Open a multi-frame raw pixel dump (`.raw`/`.rgb`/`.rgba`/`.yuv` with more than one frame, per `RawPixelOpenDialog`'s frame-count field).
- Scroll to zoom in on the preview; drag to pan; double-click to reset — same gestures as the image viewer.
- Zoom in, then press play (▶): confirm the zoom/pan level stays put as frames advance, instead of snapping back to fit view on every frame.
- Switch to a different file/tab and back: confirm zoom/pan reset to the default fit view for the new file.

- [ ] **Step 3: Verify Hex viewer zoom**

- Open any file's Hex/Raw Data viewer tab.
- Plain mouse-wheel scroll: confirm the byte list still scrolls up/down exactly as before.
- Hold Cmd (macOS) or Ctrl (Windows/Linux) and scroll: confirm the hex/ASCII grid's font size grows/shrinks, with hex and ASCII columns staying aligned at every size, and the row you were looking at stays roughly in view rather than the list jumping to the top.
- Switch to a different file: confirm font size resets to the default.

- [ ] **Step 4: Report**

Note in the progress ledger (`.git/sdd/progress.md`) what was actually confirmed. If GUI interaction isn't reliable in the current environment (this sandbox has a documented history of the app window closing on its own shortly after launch, and no way to simulate real mouse scroll/drag/click input), say so explicitly rather than claiming a full interactive pass; Tasks 1-3's automated tests, compiles, and full-suite passes stand as code-level confirmation regardless.
