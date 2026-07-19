# Tab Limit and Close Button Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cap open files at 2 with an inline rejection message, and let the user close an open file tab via a per-tab close control that keeps `selectedTabIndex` correctly pointing at a valid remaining tab.

**Architecture:** `AppState.openFile` gains a size check before adding a new tab, and a new `AppState.closeTab(index)` removes a tab and re-derives `selectedTabIndex` with three cases (closing the selected tab, a tab before it, a tab after it). `Main.kt` adds a close control inside each file tab's content and displays the rejection message next to the "Open File" button.

**Tech Stack:** Kotlin 2.0.21, Compose Multiplatform Desktop, kotlin.test.

## Global Constraints

- No "unsaved changes" confirmation on close (the app is read-only).
- No keyboard shortcut for closing tabs — click/tap only.
- The 2-file limit is a fixed constant, not user-configurable.
- No auto-dismiss timer on the rejection message — it's cleared/replaced the next time `openFile` runs, not a timed toast.

---

### Task 1: `AppState` — 2-file limit and `closeTab`

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/AppState.kt`
- Test: `app/src/test/kotlin/com/multiviewer/ui/AppStateTest.kt` (new directory: `app/src/test/kotlin/com/multiviewer/ui/`)

**Interfaces:**
- Consumes: nothing new.
- Produces: `AppState.openError: String?` (new public property, read by Task 2's UI), `AppState.closeTab(index: Int): Unit` (new public function, called by Task 2's close control). `TabState`'s constructor (`TabState(file: File)`) is unchanged and already public — this task's tests construct `TabState` instances directly to set up multi-tab scenarios without going through the 2-file-capped `openFile`.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/multiviewer/ui/AppStateTest.kt`:

```kotlin
package com.multiviewer.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppStateTest {
    private fun tempFile(name: String): File {
        val tmp = File.createTempFile(name, ".bin")
        tmp.deleteOnExit()
        tmp.writeBytes(ByteArray(4))
        return tmp
    }

    @Test
    fun `openFile rejects a third distinct file and sets openError`() {
        val appState = AppState()
        val file1 = tempFile("appstate-test-1")
        val file2 = tempFile("appstate-test-2")
        val file3 = tempFile("appstate-test-3")

        appState.openFile(file1)
        appState.openFile(file2)
        assertEquals(2, appState.tabs.size)
        assertEquals(null, appState.openError)

        appState.openFile(file3)
        assertEquals(2, appState.tabs.size)
        assertEquals("You can only have 2 files open at a time.", appState.openError)
    }

    @Test
    fun `openFile re-opening an already-open file switches to it without being rejected`() {
        val appState = AppState()
        val file1 = tempFile("appstate-test-a")
        val file2 = tempFile("appstate-test-b")

        appState.openFile(file1)
        appState.openFile(file2)
        appState.openFile(file1)

        assertEquals(2, appState.tabs.size)
        assertEquals(0, appState.selectedTabIndex)
        assertEquals(null, appState.openError)
    }

    @Test
    fun `closeTab on the last remaining tab returns to an empty tab list`() {
        val appState = AppState()
        appState.tabs.add(TabState(File("dummy.bin")))
        appState.selectedTabIndex = 0

        appState.closeTab(0)

        assertTrue(appState.tabs.isEmpty())
        assertEquals(0, appState.selectedTabIndex)
    }

    @Test
    fun `closeTab on the selected tab selects the tab that slides into its position`() {
        val appState = AppState()
        appState.tabs.add(TabState(File("a.bin")))
        appState.tabs.add(TabState(File("b.bin")))
        appState.tabs.add(TabState(File("c.bin")))
        appState.selectedTabIndex = 1

        appState.closeTab(1)

        assertEquals(listOf("a.bin", "c.bin"), appState.tabs.map { it.file.name })
        assertEquals(1, appState.selectedTabIndex)
    }

    @Test
    fun `closeTab on a tab before the selected tab shifts the selection index down`() {
        val appState = AppState()
        appState.tabs.add(TabState(File("a.bin")))
        appState.tabs.add(TabState(File("b.bin")))
        appState.tabs.add(TabState(File("c.bin")))
        appState.selectedTabIndex = 2

        appState.closeTab(0)

        assertEquals(listOf("b.bin", "c.bin"), appState.tabs.map { it.file.name })
        assertEquals(1, appState.selectedTabIndex)
    }

    @Test
    fun `closeTab on a tab after the selected tab leaves the selection index unchanged`() {
        val appState = AppState()
        appState.tabs.add(TabState(File("a.bin")))
        appState.tabs.add(TabState(File("b.bin")))
        appState.tabs.add(TabState(File("c.bin")))
        appState.selectedTabIndex = 0

        appState.closeTab(2)

        assertEquals(listOf("a.bin", "b.bin"), appState.tabs.map { it.file.name })
        assertEquals(0, appState.selectedTabIndex)
    }

    @Test
    fun `closeTab on the last tab when it is selected selects the new last tab`() {
        val appState = AppState()
        appState.tabs.add(TabState(File("a.bin")))
        appState.tabs.add(TabState(File("b.bin")))
        appState.selectedTabIndex = 1

        appState.closeTab(1)

        assertEquals(listOf("a.bin"), appState.tabs.map { it.file.name })
        assertEquals(0, appState.selectedTabIndex)
    }
}
```

Note: this test file lives in a new package/directory, `app/src/test/kotlin/com/multiviewer/ui/` (every existing test in this project is under `com.multiviewer.parser`) — create the directory as part of writing this file.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.multiviewer.ui.AppStateTest" -i`
Expected: FAIL — compilation error, `AppState.openError` and `AppState.closeTab` don't exist yet, and the 2-file limit doesn't exist so the first test's rejection assertion would fail even if it compiled.

- [ ] **Step 3: Implement the 2-file limit and `closeTab` in `AppState.kt`**

Change `app/src/main/kotlin/com/multiviewer/ui/AppState.kt` from:

```kotlin
class AppState {
    val tabs = mutableStateListOf<TabState>()
    var selectedTabIndex by mutableStateOf(0)

    fun openFile(file: File) {
        val existingIndex = tabs.indexOfFirst { it.file.absolutePath == file.absolutePath }
        if (existingIndex >= 0) {
            selectedTabIndex = existingIndex
            return
        }
        val tab = TabState(file)
        tabs.add(tab)
        selectedTabIndex = tabs.size - 1
        try {
            val root = parseFile(file)
            tab.root = root
            tab.mediaSummary = try {
                buildMediaSummary(root, file)
            } catch (e: Exception) {
                null
            }
        } catch (e: Exception) {
            tab.error = e.message ?: "Failed to open file"
        }
    }
}
```

to:

```kotlin
private const val MAX_OPEN_FILES = 2

class AppState {
    val tabs = mutableStateListOf<TabState>()
    var selectedTabIndex by mutableStateOf(0)
    var openError: String? by mutableStateOf(null)

    fun openFile(file: File) {
        val existingIndex = tabs.indexOfFirst { it.file.absolutePath == file.absolutePath }
        if (existingIndex >= 0) {
            selectedTabIndex = existingIndex
            openError = null
            return
        }
        if (tabs.size >= MAX_OPEN_FILES) {
            openError = "You can only have $MAX_OPEN_FILES files open at a time."
            return
        }
        openError = null
        val tab = TabState(file)
        tabs.add(tab)
        selectedTabIndex = tabs.size - 1
        try {
            val root = parseFile(file)
            tab.root = root
            tab.mediaSummary = try {
                buildMediaSummary(root, file)
            } catch (e: Exception) {
                null
            }
        } catch (e: Exception) {
            tab.error = e.message ?: "Failed to open file"
        }
    }

    fun closeTab(index: Int) {
        tabs.removeAt(index)
        selectedTabIndex = when {
            tabs.isEmpty() -> 0
            index < selectedTabIndex -> selectedTabIndex - 1
            index == selectedTabIndex -> index.coerceAtMost(tabs.size - 1)
            else -> selectedTabIndex
        }
    }
}
```

`private const val MAX_OPEN_FILES = 2` is placed at file scope, above the `class AppState` declaration (matching this file's existing style of file-scope constants above the class they belong to — see how other files in this project, e.g. `Main.kt`'s `BYTES_PER_ROW`, place constants outside the class).

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.multiviewer.ui.AppStateTest" -i`
Expected: PASS — all 7 tests pass.

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew test -i`
Expected: PASS — no regressions in any existing test (117 pre-existing tests, all under `com.multiviewer.parser`, plus these 7 new ones = 124 total).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/AppState.kt app/src/test/kotlin/com/multiviewer/ui/AppStateTest.kt
git commit -m "feat: cap open files at 2 and add AppState.closeTab"
```

---

### Task 2: Close button and rejection message in `Main.kt`

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/Main.kt`

**Interfaces:**
- Consumes: `AppState.openError: String?`, `AppState.closeTab(index: Int): Unit` (both from Task 1).
- Produces: nothing consumed elsewhere — this is the final task.

This task is pure UI wiring with no new pure-logic functions to unit test — this project has no Compose UI test infrastructure (an established, deliberate choice already applied to every prior UI-touching feature in this project). Verification here is: the project compiles, the full test suite still passes (proving Task 1's logic is unaffected), and a manual run of the app.

- [ ] **Step 1: Add imports**

In `app/src/main/kotlin/com/multiviewer/Main.kt`, add these two imports (both are new; `Row`, `Text`, `Modifier`, and `dp` are already imported):

```kotlin
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
```

- [ ] **Step 2: Show `openError` next to the "Open File" button**

Change:

```kotlin
                Button(onClick = {
                    val dialog = FileDialog(null as Frame?, "Open file", FileDialog.LOAD)
                    dialog.isVisible = true
                    val fileName = dialog.file
                    val directory = dialog.directory
                    if (fileName != null && directory != null) {
                        appState.openFile(File(directory, fileName))
                    }
                }) {
                    Text("Open File")
                }

                if (appState.tabs.isNotEmpty()) {
```

to:

```kotlin
                Row {
                    Button(onClick = {
                        val dialog = FileDialog(null as Frame?, "Open file", FileDialog.LOAD)
                        dialog.isVisible = true
                        val fileName = dialog.file
                        val directory = dialog.directory
                        if (fileName != null && directory != null) {
                            appState.openFile(File(directory, fileName))
                        }
                    }) {
                        Text("Open File")
                    }
                    appState.openError?.let { message ->
                        Text(message, modifier = Modifier.padding(start = 8.dp))
                    }
                }

                if (appState.tabs.isNotEmpty()) {
```

- [ ] **Step 3: Add a close control to each file tab**

Change:

```kotlin
                    TabRow(selectedTabIndex = appState.selectedTabIndex) {
                        appState.tabs.forEachIndexed { index, tab ->
                            Tab(
                                selected = index == appState.selectedTabIndex,
                                onClick = { appState.selectedTabIndex = index },
                                text = { Text(tab.file.name) },
                            )
                        }
                    }
```

to:

```kotlin
                    TabRow(selectedTabIndex = appState.selectedTabIndex) {
                        appState.tabs.forEachIndexed { index, tab ->
                            Tab(
                                selected = index == appState.selectedTabIndex,
                                onClick = { appState.selectedTabIndex = index },
                                text = {
                                    Row {
                                        Text(tab.file.name)
                                        Text(
                                            "✕",
                                            modifier = Modifier
                                                .padding(start = 8.dp)
                                                .clickable { appState.closeTab(index) },
                                        )
                                    }
                                },
                            )
                        }
                    }
```

The close glyph (`"✕"`, "✕") is a separate clickable `Text` nested inside the `Tab`'s own content, not the `Tab`'s `onClick` itself — clicking it is consumed by its own `clickable` modifier before it would reach the `Tab`'s tab-selection click handler, so clicking "✕" closes the tab without also selecting it first.

- [ ] **Step 4: Verify it compiles and the full suite passes**

Run:
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew compileKotlin -i
./gradlew test -i
```
Expected: both `BUILD SUCCESSFUL`, full test suite passes with no regressions (124 tests: 117 pre-existing plus Task 1's 7 new `AppStateTest` tests — this task adds no new test file).

- [ ] **Step 5: Manual verification**

Run the app (`./gradlew :app:run`). Confirm:
- Opening a first and second file works as before.
- Attempting to open a third distinct file (via "Open File" or drag-and-drop) does NOT open a new tab, and a message reading "You can only have 2 files open at a time." appears next to the "Open File" button.
- Each open file's tab shows a small "✕" after its filename; clicking it closes that tab without first selecting it.
- Closing the currently-selected tab leaves a sensible remaining tab selected (not a blank screen, unless it was the last tab).
- Closing the last remaining tab returns to the empty state (just the "Open File" button, no tab bar).
- After closing a tab, opening a new (third, previously-rejected) file now succeeds and the rejection message disappears.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/Main.kt
git commit -m "feat: add per-tab close button and open-file-limit message to the UI"
```
