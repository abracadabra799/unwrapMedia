# Motion Photo Extraction Menu Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire up a "모션포토" menu with two items — extract the full motion video, and extract the short autoplay preview video — each saving to a user-chosen path.

**Architecture:** `Main.kt`'s existing but never-called `extractMotionPhotoVideo` function is replaced by a shared `extractVideoToFile` helper plus two thin wrappers (one per `EmbeddedVideo` source: `tab.embeddedVideo` and `tab.motionPhotoPreview`), both wired to new `Item`s under a new `Menu("모션포토")`.

**Tech Stack:** Kotlin 2.2.20, Compose Multiplatform Desktop (`MenuBar`/`Menu`/`Item`, existing `java.awt.FileDialog`).

## Global Constraints

- Both menu items must be `enabled` only when the *currently selected* tab has the corresponding video (`embeddedVideo` / `motionPhotoPreview` non-null) — not just "any tab has one."
- The save dialog's suggested extension must keep using `video.extension` (`mp4` or `mov`, whichever `EmbeddedVideo` detected) — never hard-code `.mp4`.
- No change to `MotionPhotoExtractor.kt`, `AppState.kt`, or `ImageInspectorUI.kt` — this plan touches `Main.kt` only.
- Spec: `docs/superpowers/specs/2026-07-24-motion-photo-menu-design.md`.

---

### Task 1: Wire the Motion Photo extraction menu

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/Main.kt`

**Interfaces:**
- Consumes: `TabState.embeddedVideo: EmbeddedVideo?`, `TabState.motionPhotoPreview: EmbeddedVideo?` (existing, `AppState.kt`), `EmbeddedVideo` and `extractEmbeddedVideo(source: File, video: EmbeddedVideo, destination: File)` (existing, `com.multiviewer.parser.MotionPhotoExtractor.kt`, `extractEmbeddedVideo` already imported in `Main.kt`).
- Produces: nothing consumed by other tasks — this is the only task in this plan.

No automated test: `Main.kt` (the application entry point / menu wiring) has no existing test coverage, matching this project's convention.

- [ ] **Step 1: Add the `EmbeddedVideo` import**

In `app/src/main/kotlin/com/multiviewer/Main.kt`, replace:

```kotlin
import com.multiviewer.parser.extractEmbeddedVideo
```

with:

```kotlin
import com.multiviewer.parser.EmbeddedVideo
import com.multiviewer.parser.extractEmbeddedVideo
```

- [ ] **Step 2: Replace the dead-code function with a shared helper plus two wrappers**

Replace:

```kotlin
private fun extractMotionPhotoVideo(appState: AppState, tab: TabState) {
    val video = tab.embeddedVideo ?: return
    val dialog = FileDialog(null as Frame?, "Save extracted video", FileDialog.SAVE)
    dialog.file = "${tab.file.nameWithoutExtension}_motion.${video.extension}"
    dialog.isVisible = true
    val fileName = dialog.file
    val directory = dialog.directory
    if (fileName == null || directory == null) return
    val destination = File(directory, fileName)
    appState.statusMessage = try {
        extractEmbeddedVideo(tab.file, video, destination)
        "Saved to ${destination.name}"
    } catch (e: Exception) {
        "Failed to save: ${e.message ?: e.toString()}"
    }
}
```

with:

```kotlin
private fun extractVideoToFile(appState: AppState, tab: TabState, video: EmbeddedVideo, fileNameSuffix: String) {
    val dialog = FileDialog(null as Frame?, "Save extracted video", FileDialog.SAVE)
    dialog.file = "${tab.file.nameWithoutExtension}_$fileNameSuffix.${video.extension}"
    dialog.isVisible = true
    val fileName = dialog.file
    val directory = dialog.directory
    if (fileName == null || directory == null) return
    val destination = File(directory, fileName)
    appState.statusMessage = try {
        extractEmbeddedVideo(tab.file, video, destination)
        "Saved to ${destination.name}"
    } catch (e: Exception) {
        "Failed to save: ${e.message ?: e.toString()}"
    }
}

private fun extractMotionPhotoVideo(appState: AppState, tab: TabState) {
    val video = tab.embeddedVideo ?: return
    extractVideoToFile(appState, tab, video, "motion")
}

private fun extractMotionPhotoPreviewVideo(appState: AppState, tab: TabState) {
    val video = tab.motionPhotoPreview ?: return
    extractVideoToFile(appState, tab, video, "preview")
}
```

- [ ] **Step 3: Add the "모션포토" menu**

Replace:

```kotlin
        MenuBar {
            Menu("File") {
                Item("Open", shortcut = KeyShortcut(Key.O, meta = true), onClick = { showOpenFileDialog(appState) })
                Item("Close", enabled = appState.tabs.isNotEmpty(), shortcut = KeyShortcut(Key.W, meta = true), onClick = { appState.closeTab(appState.selectedTabIndex) })
            }
        }
```

with:

```kotlin
        MenuBar {
            Menu("File") {
                Item("Open", shortcut = KeyShortcut(Key.O, meta = true), onClick = { showOpenFileDialog(appState) })
                Item("Close", enabled = appState.tabs.isNotEmpty(), shortcut = KeyShortcut(Key.W, meta = true), onClick = { appState.closeTab(appState.selectedTabIndex) })
            }
            Menu("모션포토") {
                val currentTab = appState.tabs.getOrNull(appState.selectedTabIndex)
                Item(
                    "모션포토 동영상 추출",
                    enabled = currentTab?.embeddedVideo != null,
                    onClick = { currentTab?.let { extractMotionPhotoVideo(appState, it) } },
                )
                Item(
                    "모션포토 미리보기 재생용 비디오 추출",
                    enabled = currentTab?.motionPhotoPreview != null,
                    onClick = { currentTab?.let { extractMotionPhotoPreviewVideo(appState, it) } },
                )
            }
        }
```

- [ ] **Step 4: Run the full test suite to check for regressions**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew test --console=plain`
Expected: BUILD SUCCESSFUL, all tests passed (confirms the file compiles cleanly).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/Main.kt
git commit -m "Wire Motion Photo video/preview extraction menu items"
```

- [ ] **Step 6: Build and run the app**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew :app:run`
Expected: The app window opens with no build errors, with a new "모션포토" menu visible in the menu bar next to "File".

- [ ] **Step 7: Manually verify against a real Motion Photo file**

Open a Motion Photo file with both an `embeddedVideo` and a `motionPhotoPreview` (e.g. one of the Samsung motion photos in `/Users/dong.kim/Downloads/`, such as `20260715_223835.heic`).

Expected: Both "모션포토 동영상 추출" and "모션포토 미리보기 재생용 비디오 추출" are enabled (not grayed out). Click each in turn, save to a temp location via the file dialog, and confirm: the status bar shows "Saved to ...", and the resulting saved file plays correctly (e.g. open it in the app itself, or via `open <path>`).

- [ ] **Step 8: Manually verify the disabled state**

Open an ordinary image with no embedded motion video.

Expected: Both menu items appear grayed out (disabled) and cannot be clicked.
