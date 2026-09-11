# Media Comparison Analyzer Drag-and-Drop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a user drag one or two media files onto the Media Comparison Analyzer
window (`ImageCompareWindow`) to fill the A/B slots, instead of always going through
the OS file dialog.

**Architecture:** Extract the recursive-`DropTarget` drag-and-drop wiring that
`Main.kt` already uses for its main window into a small shared helper
(`attachFileDropTarget`), migrate `Main.kt` onto it (no behavior change), then reuse
it in `ImageCompareWindow` alongside a new pure function (`resolveDroppedFiles`) that
decides which slot(s) a drop fills — mirroring the existing `resolveComparePick`
used by the dialog's multi-select path, but keyed by drop position instead of which
button was clicked.

**Tech Stack:** Kotlin, Compose Multiplatform for Desktop, JUnit5 (`kotlin("test-junit5")`), java.awt.dnd.

## Global Constraints

- Design spec: `docs/superpowers/specs/2026-09-11-image-compare-drag-drop-design.md` — every requirement below traces back to it.
- Additive only: the existing "📂 파일 찾기" dialog and `FileDropdownOrLabel` tab/sibling-file dropdown are unchanged.
- Dropped folders and non-media files are filtered out before the 1-file/2-file/refuse rules apply; a drop with nothing left after filtering is silently ignored (no warning).
- Single-file drop side: `xFraction < 0.5f` → A, else → B (matches the design's `else -> B` boundary rule, so exactly `0.5f` is B).
- More than 2 valid files after filtering → reuse the existing `tooManyPickedCount` refusal path, unchanged message.
- Reuse `ALL_SUPPORTED_MEDIA_EXTENSIONS` (from `AppState.kt`) for the extension filter — the same list `FileDropdownOrLabel` already uses.
- Match existing code style in touched files: local functions capturing `remember`-backed `var`s (see `loadInfo`, `pickFile` in `QualityCompareWindow.kt`), `EventQueue.invokeLater`/Executor threading pattern is *not* needed here (AWT drag-and-drop callbacks already run on the AWT event thread, same as `Main.kt`'s existing handler).

---

## File Structure

| File | Change | Responsibility |
|---|---|---|
| `app/src/main/kotlin/com/multiviewer/ui/FileDropTarget.kt` | **Create** | Generic recursive-`DropTarget` attach helper, extracted from `Main.kt`. No app-specific logic (extension filtering, slot assignment) — just flavor accept/reject and reporting window-relative drop/drag-hover coordinates. |
| `app/src/main/kotlin/com/multiviewer/Main.kt` | Modify | Replace its inline `DropTargetAdapter` block with a call to `attachFileDropTarget`. Remove now-unused DnD imports. |
| `app/src/main/kotlin/com/multiviewer/ui/ImageCompareWindow.kt` | Modify | Add `resolveDroppedFiles` next to `resolveComparePick`; extract the existing inline `onPick` lambda into a named `applyPick` local function so both the dialog and the new drop handler call it; wire `attachFileDropTarget` into the window; add drag-hover highlight state and thread it into `MediaSelectionBar`'s two side panels. |
| `app/src/test/kotlin/com/multiviewer/ui/CompareFileSelectionTest.kt` | Modify | Add tests for `resolveDroppedFiles` next to the existing `resolveComparePick` tests. |

---

### Task 1: Extract shared drop-target helper and migrate Main.kt onto it

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/ui/FileDropTarget.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/Main.kt:53-57` (imports), `app/src/main/kotlin/com/multiviewer/Main.kt:634-679` (the `LaunchedEffect` block)

**Interfaces:**
- Produces: `fun attachFileDropTarget(window: java.awt.Window, onDragPosition: (java.awt.Point?) -> Unit = {}, onFilesDropped: (files: List<File>, location: java.awt.Point) -> Unit)` — package `com.multiviewer.ui`. `onDragPosition` fires with a window-relative point while a file drag hovers over `window`, and with `null` when the drag leaves or a drop completes. `onFilesDropped` fires once per accepted drop with the raw dropped files and the window-relative drop location; callers own all filtering/interpretation.

This task is a pure refactor with no automated test possible (dragging a real file from
the OS desktop isn't something a JUnit test can do — this matches the caveat already
called out in the design spec for `Main.kt`'s existing drag-and-drop). Correctness is
established by (a) the new helper being a line-for-line behavioral match of the code
it replaces, verified by inspection in step 2, and (b) `./gradlew compileKotlin`
succeeding. A manual drag-and-drop smoke test of the main window is called out at the
end for the user, since this touches already-working code.

- [ ] **Step 1: Create the shared helper**

Create `app/src/main/kotlin/com/multiviewer/ui/FileDropTarget.kt`:

```kotlin
package com.multiviewer.ui

import java.awt.Component
import java.awt.Container
import java.awt.Point
import java.awt.Window
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.awt.dnd.DropTargetEvent
import java.io.File
import javax.swing.SwingUtilities

/**
 * Wires OS file-drag-and-drop onto [window] for a Compose Desktop window.
 *
 * Compose Desktop renders into a deeply-nested Skiko SkiaLayer several levels below
 * `window` (window -> JRootPane -> JLayeredPane -> ... -> SkiaLayer) -- that SkiaLayer
 * is the only real heavyweight/native surface actually receiving OS drag events.
 * Attaching a DropTarget to `window` or `window.contentPane` alone never sees a drag
 * at all (confirmed: dragEnter never fired). Attaching recursively to every component
 * in the tree reaches the SkiaLayer regardless of Compose Desktop's internal structure,
 * without depending on that structure by name/type.
 *
 * [onDragPosition] is called with the drag location converted to [window]-relative
 * coordinates while a file drag hovers over the window, and with `null` once the drag
 * leaves the window or a drop completes -- callers that don't need drag-hover feedback
 * can leave it as the no-op default. [onFilesDropped] is called with the raw dropped
 * files and the window-relative drop location; this helper only accepts/rejects the
 * drag flavor and reports what was dropped -- filtering and interpreting the file list
 * is the caller's job.
 */
fun attachFileDropTarget(
    window: Window,
    onDragPosition: (Point?) -> Unit = {},
    onFilesDropped: (files: List<File>, location: Point) -> Unit,
) {
    fun relativePoint(sourceComponent: Component, location: Point): Point =
        SwingUtilities.convertPoint(sourceComponent, location, window)

    val listener = object : DropTargetAdapter() {
        override fun dragEnter(dtde: DropTargetDragEvent) {
            if (dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                dtde.acceptDrag(DnDConstants.ACTION_COPY)
                onDragPosition(relativePoint(dtde.dropTargetContext.component, dtde.location))
            } else {
                dtde.rejectDrag()
            }
        }

        override fun dragOver(dtde: DropTargetDragEvent) {
            if (dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                dtde.acceptDrag(DnDConstants.ACTION_COPY)
                onDragPosition(relativePoint(dtde.dropTargetContext.component, dtde.location))
            } else {
                dtde.rejectDrag()
            }
        }

        override fun dragExit(dte: DropTargetEvent) {
            onDragPosition(null)
        }

        override fun drop(event: DropTargetDropEvent) {
            if (!event.transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                event.rejectDrop()
                onDragPosition(null)
                return
            }
            event.acceptDrop(DnDConstants.ACTION_COPY)
            try {
                @Suppress("UNCHECKED_CAST")
                val files = event.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
                onFilesDropped(files, relativePoint(event.dropTargetContext.component, event.location))
                event.dropComplete(true)
            } catch (e: Exception) {
                event.dropComplete(false)
            } finally {
                onDragPosition(null)
            }
        }
    }

    fun attachRecursively(component: Component) {
        component.dropTarget = DropTarget(component, listener)
        if (component is Container) {
            for (child in component.components) attachRecursively(child)
        }
    }
    attachRecursively(window)
}
```

- [ ] **Step 2: Migrate Main.kt onto the helper**

In `app/src/main/kotlin/com/multiviewer/Main.kt`, replace lines 634-679 (the whole
`LaunchedEffect(Unit) { ... }` block) with:

```kotlin
        LaunchedEffect(Unit) {
            attachFileDropTarget(window) { files, _ ->
                if (files.isNotEmpty()) {
                    if (files.size == 1 && files[0].isDirectory) {
                        appState.openFolder(files[0])
                    } else {
                        appState.openFiles(files)
                    }
                }
            }
        }
```

Then remove the now-unused imports at lines 53-57:

```kotlin
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDropEvent
```

(`Main.kt` already has `import com.multiviewer.ui.*` at line 40, so
`attachFileDropTarget` resolves without a new import.)

- [ ] **Step 3: Compile**

Run: `./gradlew compileKotlin`
Expected: `BUILD SUCCESSFUL`, no "unused import" or unresolved-reference errors.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/FileDropTarget.kt app/src/main/kotlin/com/multiviewer/Main.kt
git commit -m "refactor: extract attachFileDropTarget helper from Main.kt's drag-and-drop

No behavior change -- Main.kt's main-window drag-and-drop still opens dropped
files/folders exactly as before. Pulled out so ImageCompareWindow's upcoming
drag-and-drop support (docs/superpowers/specs/2026-09-11-image-compare-drag-drop-design.md)
can reuse the same recursive-DropTarget wiring instead of duplicating it."
```

- [ ] **Step 5: Manual smoke test (tell the user)**

Automated coverage stops at compilation for this task — dragging a real file from
Finder isn't something this session can do. Ask the user to run the app and drag a
file onto the main window, confirming it still opens exactly as it did before this
change.

---

### Task 2: `resolveDroppedFiles` — pure slot-assignment logic, test-first

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/ImageCompareWindow.kt` (add after `resolveComparePick`, i.e. after line 114)
- Test: `app/src/test/kotlin/com/multiviewer/ui/CompareFileSelectionTest.kt`

**Interfaces:**
- Consumes: `ComparePick`, `MAX_COMPARE_SELECTION` (both already defined in `ImageCompareWindow.kt`).
- Produces: `fun resolveDroppedFiles(picked: List<File>, xFraction: Float): ComparePick` — same output shape as `resolveComparePick`, but the single-file case is decided by `xFraction` (`< 0.5f` → A, else → B) instead of a `targetIsA` flag.

- [ ] **Step 1: Write the failing tests**

Append to `app/src/test/kotlin/com/multiviewer/ui/CompareFileSelectionTest.kt`, inside
the `CompareFileSelectionTest` class (after the last existing test):

```kotlin
    // A single file dropped on the left half of the window lands in A -- matches where
    // the A panel is drawn, so the drop location alone should predict the outcome.
    @Test
    fun `one dropped file on the left half fills A`() {
        val result = resolveDroppedFiles(listOf(f("only.mp4")), xFraction = 0.25f)
        assertEquals(f("only.mp4"), result.fileA)
        assertNull(result.fileB)
    }

    // Same file, dropped on the right half, fills B instead -- no button-click context
    // needed, unlike resolveComparePick's targetIsA.
    @Test
    fun `one dropped file on the right half fills B`() {
        val result = resolveDroppedFiles(listOf(f("only.mp4")), xFraction = 0.75f)
        assertNull(result.fileA)
        assertEquals(f("only.mp4"), result.fileB)
    }

    // Exact center is B, not A -- the boundary is "< 0.5f is A", so 0.5f itself falls
    // into the else branch.
    @Test
    fun `one dropped file exactly at the halfway point fills B`() {
        val result = resolveDroppedFiles(listOf(f("only.mp4")), xFraction = 0.5f)
        assertNull(result.fileA)
        assertEquals(f("only.mp4"), result.fileB)
    }

    // Two dropped files fill both slots, sorted by name -- same rule resolveComparePick
    // uses for a two-file browse, and drop position is irrelevant once there are two.
    @Test
    fun `two dropped files fill both slots ordered by name regardless of drop position`() {
        val result = resolveDroppedFiles(listOf(f("z.mp4"), f("m.mp4")), xFraction = 0.9f)
        assertEquals(f("m.mp4"), result.fileA)
        assertEquals(f("z.mp4"), result.fileB)
    }

    // More than two dropped files is refused exactly like an over-sized dialog
    // selection -- silently keeping two of three would drop the rest with no
    // explanation.
    @Test
    fun `more than two dropped files are refused and change nothing`() {
        val result = resolveDroppedFiles(listOf(f("a.mp4"), f("b.mp4"), f("c.mp4")), xFraction = 0.1f)
        assertEquals(3, result.refusedCount)
        assertNull(result.fileA)
        assertNull(result.fileB)
    }

    // An empty drop (everything filtered out upstream, e.g. a folder or a non-media
    // file) changes nothing and carries no refusal.
    @Test
    fun `an empty dropped list changes nothing`() {
        val result = resolveDroppedFiles(emptyList(), xFraction = 0.5f)
        assertNull(result.fileA)
        assertNull(result.fileB)
        assertNull(result.refusedCount)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "com.multiviewer.ui.CompareFileSelectionTest"`
Expected: FAIL — `resolveDroppedFiles` is unresolved (compilation failure in the test source set).

- [ ] **Step 3: Implement `resolveDroppedFiles`**

In `app/src/main/kotlin/com/multiviewer/ui/ImageCompareWindow.kt`, immediately after
the closing brace of `resolveComparePick` (after line 114), add:

```kotlin

/**
 * Same output shape as [resolveComparePick], but for a drag-and-drop rather than a
 * dialog browse: a single dropped file's slot is decided by where it landed instead
 * of which button was clicked. [xFraction] is the drop's horizontal position as a
 * fraction of the window's width (0f = left edge, 1f = right edge) -- left half lands
 * in A (matching where the A panel is drawn), right half (including exactly the
 * midpoint) lands in B.
 *
 * Two or more dropped files behave identically to [resolveComparePick]: sorted by
 * name for exactly two, refused outright for more than [MAX_COMPARE_SELECTION].
 */
fun resolveDroppedFiles(picked: List<File>, xFraction: Float): ComparePick {
    if (picked.size > MAX_COMPARE_SELECTION) return ComparePick(null, null, refusedCount = picked.size)
    val sorted = picked.sortedBy { it.name.lowercase(Locale.US) }
    return when {
        sorted.isEmpty() -> ComparePick(null, null)
        sorted.size == 1 -> if (xFraction < 0.5f) ComparePick(sorted[0], null) else ComparePick(null, sorted[0])
        else -> ComparePick(sorted[0], sorted[1])
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests "com.multiviewer.ui.CompareFileSelectionTest"`
Expected: PASS — all tests in the class, including the 6 new ones, succeed.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/ImageCompareWindow.kt app/src/test/kotlin/com/multiviewer/ui/CompareFileSelectionTest.kt
git commit -m "feat: add resolveDroppedFiles for drag-and-drop slot assignment

Pure function mirroring resolveComparePick's rules (name-sorted pair, refuse
>2), but a single file's slot is decided by drop x-position instead of which
Browse button was clicked. Not yet wired to the window -- that's next."
```

---

### Task 3: Wire drag-and-drop and hover highlight into ImageCompareWindow

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/ImageCompareWindow.kt`

**Interfaces:**
- Consumes: `attachFileDropTarget` (Task 1), `resolveDroppedFiles` (Task 2), `ALL_SUPPORTED_MEDIA_EXTENSIONS` (already defined in `AppState.kt`, same package — no import needed).
- Produces: `MediaSelectionBar` gains a `dragHoverSide: Boolean?` parameter (`true` = highlight the A panel, `false` = highlight the B panel, `null` = no highlight).

No new automated test — this step is UI wiring over already-tested pure functions
(`resolveDroppedFiles` from Task 2) and an already-proven helper
(`attachFileDropTarget` from Task 1). Verified by `./gradlew compileKotlin` plus a
manual drag-and-drop check at the end, same caveat as Task 1.

- [ ] **Step 1: Extract the existing inline `onPick` handling into a named function**

In `app/src/main/kotlin/com/multiviewer/ui/ImageCompareWindow.kt`, find the
`MediaSelectionBar` call (around line 306-335). Its `onPick` argument is currently:

```kotlin
                    onPick = { pick ->
                        tooManyPickedCount = pick.refusedCount
                        pick.fileA?.let { fileA = it; folderA = it.parentFile }
                        pick.fileB?.let { fileB = it; folderB = it.parentFile }
                    },
```

Just above the `Window(...)` call that follows this composable's `LaunchedEffect(fileB)`
line (line 296), add a named local function with that exact body:

```kotlin
    fun applyPick(pick: ComparePick) {
        tooManyPickedCount = pick.refusedCount
        pick.fileA?.let { fileA = it; folderA = it.parentFile }
        pick.fileB?.let { fileB = it; folderB = it.parentFile }
    }

```

Then replace the inline `onPick = { pick -> ... }` block in the `MediaSelectionBar`
call with:

```kotlin
                    onPick = ::applyPick,
```

- [ ] **Step 2: Add drag-hover state and attach the drop target**

In the same file, inside the `Window(...) { ... }` content lambda, immediately after
the opening `) {` (i.e. as the first lines of the content block, right before the
existing `Surface(...)` line), add:

```kotlin
        var dragHoverSide by remember { mutableStateOf<Boolean?>(null) }

        LaunchedEffect(Unit) {
            attachFileDropTarget(
                window = window,
                onDragPosition = { point ->
                    dragHoverSide = point?.let { it.x < window.width / 2 }
                },
                onFilesDropped = { files, point ->
                    val mediaFiles = files.filter {
                        it.isFile && it.extension.lowercase(Locale.US) in ALL_SUPPORTED_MEDIA_EXTENSIONS
                    }
                    val xFraction = if (window.width > 0) point.x.toFloat() / window.width else 0f
                    applyPick(resolveDroppedFiles(mediaFiles, xFraction))
                    dragHoverSide = null
                },
            )
        }

```

- [ ] **Step 3: Pass the hover state into `MediaSelectionBar`**

In the same `MediaSelectionBar(...)` call from Step 1, add one more argument:

```kotlin
                    dragHoverSide = dragHoverSide,
```

- [ ] **Step 4: Add the parameter to `MediaSelectionBar`'s signature**

Find the `MediaSelectionBar` function declaration (around line 383-397):

```kotlin
private fun MediaSelectionBar(
    appState: AppState,
    language: AppLanguage,
    fileA: File?,
    fileB: File?,
    folderA: File?,
    folderB: File?,
    infoA: CompareMediaInfo?,
    infoB: CompareMediaInfo?,
    openTabFiles: List<File>,
    tooManyPickedCount: Int?,
    onPick: (ComparePick) -> Unit,
    onSelectA: (File) -> Unit,
    onSelectB: (File) -> Unit,
    onSwap: () -> Unit,
) {
```

Add `dragHoverSide: Boolean?,` as a new parameter (placed with the other display-only
inputs, next to `tooManyPickedCount`):

```kotlin
private fun MediaSelectionBar(
    appState: AppState,
    language: AppLanguage,
    fileA: File?,
    fileB: File?,
    folderA: File?,
    folderB: File?,
    infoA: CompareMediaInfo?,
    infoB: CompareMediaInfo?,
    openTabFiles: List<File>,
    tooManyPickedCount: Int?,
    dragHoverSide: Boolean?,
    onPick: (ComparePick) -> Unit,
    onSelectA: (File) -> Unit,
    onSelectB: (File) -> Unit,
    onSwap: () -> Unit,
) {
```

- [ ] **Step 5: Highlight the A and B panels while a drag hovers over them**

Still in `MediaSelectionBar`, find the "Media A Selector" column (around line 446):

```kotlin
            // Media A Selector
            Column(modifier = Modifier.weight(1f)) {
```

Replace with:

```kotlin
            // Media A Selector -- bordered while a file drag hovers over the left half
            // of the window (see attachFileDropTarget's onDragPosition wiring above).
            Column(
                modifier = Modifier.weight(1f).padding(4.dp).then(
                    if (dragHoverSide == true) Modifier.border(2.dp, AppColors.NeonBlue, RoundedCornerShape(6.dp)) else Modifier
                ),
            ) {
```

Then find the "Media B Selector" column (around line 495):

```kotlin
            // Media B Selector
            Column(modifier = Modifier.weight(1f)) {
```

Replace with:

```kotlin
            // Media B Selector -- bordered while a file drag hovers over the right half
            // of the window (see attachFileDropTarget's onDragPosition wiring above).
            Column(
                modifier = Modifier.weight(1f).padding(4.dp).then(
                    if (dragHoverSide == false) Modifier.border(2.dp, AppColors.NeonBlue, RoundedCornerShape(6.dp)) else Modifier
                ),
            ) {
```

This file already imports `androidx.compose.foundation.border` (line 6) and
`androidx.compose.foundation.shape.RoundedCornerShape` (line 16), so no new imports
are needed.

- [ ] **Step 6: Compile**

Run: `./gradlew compileKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Run the full test suite**

Run: `./gradlew test`
Expected: `BUILD SUCCESSFUL` — the Task 2 tests and everything else still pass; this
task added no new automated tests of its own (pure UI wiring, per the note above).

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/ImageCompareWindow.kt
git commit -m "feat: drag-and-drop file selection for Media Comparison Analyzer

Drop 1 file onto the left/right half of the window to fill A/B respectively;
drop 2 files anywhere to fill both at once (name-sorted, same rule as the
dialog's multi-select). More than 2 files reuses the existing 'max 2' warning.
Folders and non-media files are filtered out and ignored. The A/B panel under
the cursor highlights while dragging. Dialog browsing and the open-tab
dropdown are unchanged.

See docs/superpowers/specs/2026-09-11-image-compare-drag-drop-design.md"
```

- [ ] **Step 9: Manual smoke test (tell the user)**

Ask the user to run the app, open the Media Comparison Analyzer (Cmd+D), and check:
1. Drag one file onto the left half → fills A, left panel highlights while dragging.
2. Drag one file onto the right half → fills B.
3. Drag two files at once from Finder → both fill, sorted by name.
4. Drag three files at once → the existing "최대 2개까지만 선택할 수 있습니다" warning appears.
5. Drag a folder → nothing happens, no warning.
6. The "📂 파일 찾기" button and the open-tab dropdown still work exactly as before.
