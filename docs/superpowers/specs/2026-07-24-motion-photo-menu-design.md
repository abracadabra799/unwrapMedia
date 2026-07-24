# Motion Photo Extraction Menu — Design

## Background

`Main.kt` already has an `extractMotionPhotoVideo(appState, tab)` function (saves `tab.embeddedVideo` to a user-chosen file via a `FileDialog` and `extractEmbeddedVideo`) — but it is **dead code**, never called from any menu item, button, or other UI (confirmed by search: zero call sites). The `MenuBar` currently has only a `Menu("File")` with `Open`/`Close`. `TabState.motionPhotoPreview: EmbeddedVideo?` (the short Samsung `MotionPhoto_AutoPlay` clip) is likewise computed on every file open but never consumed anywhere in the UI.

## Goal

A new `Menu("모션포토")` in the menu bar, next to `File`, with two items:
- **"모션포토 동영상 추출"** — saves the current tab's `embeddedVideo` (the full motion clip) to a user-chosen path. Wires up the existing dead-code function.
- **"모션포토 미리보기 재생용 비디오 추출"** — saves the current tab's `motionPhotoPreview` (the short Samsung autoplay preview clip) to a user-chosen path, via a new, near-identical function.

Both items are disabled (grayed out) when the currently selected tab has no such video, matching the existing `Close` item's `enabled = appState.tabs.isNotEmpty()` pattern.

## Non-Goals

- No change to `extractEmbeddedVideo`/`EmbeddedVideo` (`MotionPhotoExtractor.kt`) — reused exactly as-is for both menu items.
- No change to the Motion Photo video preview panel (`ImageInspectorUI.kt`, already implemented) — this is a separate, save-to-disk feature, not a preview.
- No forcing of a `.mp4` extension — the save dialog's suggested filename keeps using the detected `video.extension` (`mp4` or `mov`, from `EmbeddedVideo`), exactly like the existing dead-code function already does. Most motion photos are `mp4` in practice, but a `mov`-branded one should keep saving as `.mov`.

## Design

### `Main.kt` — extract a shared save helper, wire two menu items

Replace the existing dead-code function with a shared helper plus two thin wrappers:

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

`EmbeddedVideo` needs a new import (`com.multiviewer.parser.EmbeddedVideo`) — `extractEmbeddedVideo` is already imported.

In the `MenuBar` block, add the new menu after `File`:

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

`currentTab` is read fresh on each recomposition of the `Menu` block (triggered by `appState.selectedTabIndex`/`appState.tabs` being Compose state), so switching tabs or closing the last tab correctly updates both items' enabled state.

## Testing

- No automated test: `Main.kt` has no existing test coverage (it's the application entry point / menu wiring), matching this project's convention for `main()`/menu-bar code.
- Manual: open a Motion Photo file with both `embeddedVideo` and `motionPhotoPreview` present — both menu items enabled; click each, save to a temp path, confirm the saved file plays and the status bar shows "Saved to ...". Open a file with neither (an ordinary image) — both items grayed out. Switch between two open tabs with different motion-photo status — confirm the menu's enabled state updates to match the newly selected tab.
