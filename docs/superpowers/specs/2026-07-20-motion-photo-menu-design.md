# Motion Photo Menu + Video Extraction — Design

## Background

The app currently opens files via a top-left "Open File" button, and each open file shows two tabs (Media Summary / Structure Analyser). The user wants to add the ability to extract a Motion Photo's embedded video and save it as a standalone `.mp4`/`.mov` file. Rather than adding this as a third per-file tab, the user wants it exposed through a native application menu bar (in the style of Android Studio's File/Edit menu), alongside a File menu that replaces the current Open File button.

## Goal

1. Add a native menu bar with a `File` menu (`Open`, `Close`) and a `MotionPhoto` menu (`동영상 추출`).
2. `MotionPhoto > 동영상 추출` is enabled only when the currently selected tab's file has an extractable embedded video, and clicking it goes directly to a native save dialog — no intermediate UI.
3. The embedded video's container format (MP4 vs QuickTime/MOV) is detected from the embedded video's own `ftyp` header, and used to pick the default file extension.

## Non-Goals

- No dedicated tab for motion photo extraction — this is a direct menu action, not a view.
- No video preview or playback.
- No keyboard shortcut on `MotionPhoto > 동영상 추출` (click-only), though `File > Open`/`Close` do get standard shortcuts since a native menu bar is expected to have them.
- No change to Media Summary / Structure Analyser content, or the existing 2-file-open cap logic.
- No change to the per-tab "✕" close button — it stays, coexisting with the new `File > Close` menu item.

## Design

### 1. Native menu bar (`Main.kt`)

Compose Desktop's `MenuBar` (called inside the `Window` block) renders as the system menu bar on macOS and as an in-window menu bar on Windows/Linux — same code, platform-appropriate placement automatically.

```kotlin
MenuBar {
    Menu("File") {
        Item("Open", shortcut = KeyShortcut(Key.O, meta = true), onClick = { showOpenFileDialog(appState) })
        Item(
            "Close",
            enabled = appState.tabs.isNotEmpty(),
            shortcut = KeyShortcut(Key.W, meta = true),
            onClick = { appState.closeTab(appState.selectedTabIndex) },
        )
    }
    Menu("MotionPhoto") {
        val currentTab = appState.tabs.getOrNull(appState.selectedTabIndex)
        Item(
            "동영상 추출",
            enabled = currentTab?.embeddedVideo != null,
            onClick = { currentTab?.let { extractMotionPhotoVideo(appState, it) } },
        )
    }
}
```

`showOpenFileDialog` is the existing "Open File" button's `FileDialog(..., FileDialog.LOAD)` logic, extracted into a shared function so both the menu item and the empty-state click (below) call the same code.

### 2. Empty state replaces the "Open File" button

The button and its `Row` are removed. When `appState.tabs.isEmpty()`, a large centered clickable text (`"📂 Open File"`, sized up, matching the existing plain-Text-as-affordance style already used for the tab's "✕" close control) fills the window and calls `showOpenFileDialog(appState)` on click. When tabs are non-empty, the existing file-tab row and per-file Media Summary/Structure Analyser UI render exactly as before.

### 3. Motion photo detection & extraction (`MotionPhotoExtractor.kt`, new)

```kotlin
data class EmbeddedVideo(val start: Long, val end: Long, val extension: String)

fun findEmbeddedVideo(root: BoxNode): EmbeddedVideo? {
    val videoNode = root.children.find { it.type == "mpvd" }
        ?: findFirst(root) { it.type == "sefd" }
            ?.children
            ?.find { it.children.firstOrNull()?.type == "ftyp" }
        ?: return null
    val majorBrand = videoNode.children.find { it.type == "ftyp" }
        ?.fields?.find { it.name == "major_brand" }?.value
    val extension = if (majorBrand?.trim() == "qt") "mov" else "mp4"
    return EmbeddedVideo(videoNode.offset + videoNode.headerSize, videoNode.offset + videoNode.size, extension)
}

fun extractEmbeddedVideo(source: File, video: EmbeddedVideo, destination: File) {
    ByteReader.open(source).use { reader ->
        destination.outputStream().use { out ->
            var offset = video.start
            while (offset < video.end) {
                val chunkSize = minOf(1 shl 20, (video.end - offset).toInt())
                out.write(reader.readBytes(offset, chunkSize))
                offset += chunkSize
            }
        }
    }
}
```

- For HEIC Motion Photos, the embedded video lives directly in the payload of a top-level `mpvd` box (already parsed as that video's own `ftyp`/`moov`/`mdat` tree, since `mpvd` is registered as a plain container decoder).
- For JPEG files with a Samsung SEFD trailer, `SefdBoxDecoder` already detects an embedded MP4 by sniffing for a nested `ftyp` inside a field's raw bytes (existing behavior, unchanged) — that field node's `children` are the embedded video's own box tree, exactly mirroring the `mpvd` case. `findEmbeddedVideo` treats both uniformly once it has that node.
- Returns `null` when neither is found — this is the exact gate for the menu item's enabled state.
- `findFirst` (currently `private` in `MediaSummaryBuilder.kt`) is made non-private so `MotionPhotoExtractor.kt` can reuse it instead of duplicating tree-walk logic (same relaxation pattern already used for `FieldPanel`'s `MetadataRow`).
- `extractEmbeddedVideo` streams in 1 MB chunks via the existing `ByteReader` abstraction, rather than buffering the whole video in memory.

### 4. State (`AppState.kt`)

- `TabState` gains `embeddedVideo: EmbeddedVideo? by mutableStateOf(null)`, computed in `openFile` right after `mediaSummary`, with its own try/catch-to-null (a detection failure must not collapse the tab, matching the existing `mediaSummary` pattern).
- `AppState.openError` is renamed to `AppState.statusMessage: String?` — it now carries two distinct transient messages that share one display location: the existing "2 files max" rejection, and the motion-photo extraction result (success or failure). All existing call sites that set/clear `openError` are updated to `statusMessage`.

### 5. Extraction flow (`Main.kt`)

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
        "Failed to save: ${e.message}"
    }
}
```

`appState.statusMessage` renders as a single inline `Text`, shown whenever `appState.tabs.isNotEmpty()`, near the top of the file-tab row (visible regardless of which per-file sub-tab is active).

## Testing

- `MotionPhotoExtractorTest.kt` (new): detection via a directly-constructed `mpvd`-shaped root (asserts correct byte range + `.mp4` extension from an `isom`-branded embedded `ftyp`); detection via a `sefd`-field-shaped root (asserts `.mov` extension from a `qt  `-branded embedded `ftyp`); no-video root returns `null`; `extractEmbeddedVideo` copies the exact byte range from a synthetic source file to a destination file (byte-for-byte comparison).
- `AppStateTest.kt` additions: opening a small synthetic file (real bytes on disk, following the existing `JpegWalkerTest`-style synthetic-fixture convention) that contains a detectable embedded video populates `tab.embeddedVideo`; opening one that doesn't leaves it `null`.
- Manual verification (this app's established pattern for `AppState`/UI-level changes): open a real Samsung Motion Photo file, confirm `MotionPhoto > 동영상 추출` is enabled and produces a playable, correctly-named `.mp4`; confirm it's disabled for a non-Motion-Photo file; confirm `File > Open`/`Close` and the empty-state click all behave correctly; confirm the 2-file-limit rejection message still displays correctly under its new `statusMessage` name.
