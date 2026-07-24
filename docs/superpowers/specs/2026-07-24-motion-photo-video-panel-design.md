# Motion Photo Video Preview Panel — Design

## Background

`ImageInspectorUI`'s top row currently shows two panels side by side: the embedded EXIF thumbnail (left) and the decoded primary image (right, now via the ffmpeg fallback for HEIC — see `2026-07-24-heic-vlc-fallback-decoding-design.md`). For Motion Photo files (HEIC or JPEG with an embedded video clip, detected today via `findEmbeddedVideo` in `MotionPhotoExtractor.kt` and already surfaced as `TabState.embeddedVideo: EmbeddedVideo?`), that embedded video is currently only reachable through the "Save extracted video" menu action (`Main.kt`'s `extractMotionPhotoVideo`, which writes it to a user-chosen file via `extractEmbeddedVideo`). There's no way to preview it inline.

The app already has a live video player, `VlcVideoPlayer(file: File, modifier)`, used today by `VideoInspectorUI` for standalone `.mp4`/`.mov` tabs. It requires a real `File` — `EmbeddedVideo` is only a byte range (`start`/`end`/`extension`) within the *original* image file, so playing it means extracting those bytes to a temporary file first (the same `extractEmbeddedVideo` function the menu action already uses) before handing that temp file to `VlcVideoPlayer`.

`TabState` also carries a `motionPhotoPreview: EmbeddedVideo?` (a short Samsung-specific auto-play preview clip, `MotionPhoto_AutoPlay`, currently computed but never rendered anywhere). Confirmed with the user: the new panel uses `embeddedVideo` (the full motion clip, same source as "Save extracted video"), not `motionPhotoPreview`.

## Goal

Opening a Motion Photo file shows a third panel at the top of `ImageInspectorUI`, to the right of the primary image, that plays the embedded motion video live (using the existing `VlcVideoPlayer`). The top row becomes a 3-way split (thumbnail / primary image / motion video) when `tab.embeddedVideo != null`, and stays exactly as it is today (2-way split) for files without an embedded video — no layout or behavior change for ordinary images.

## Non-Goals

- No change to `motionPhotoPreview` — it remains computed but unused, exactly as today. Only `embeddedVideo` drives the new panel.
- No change to the existing "Save extracted video" menu action (`Main.kt`) — it continues to extract independently to a user-chosen permanent location. The new panel's extraction is a separate, throwaway temp file with no shared state.
- No change to `VideoInspectorUI` or standalone video-tab playback — this panel only appears inside `ImageInspectorUI` (image tabs with an embedded motion video).
- No caching/reuse of the extracted temp file across recompositions beyond what `LaunchedEffect(tab.file, video)` keying already provides — re-opening the same file in a fresh tab re-extracts.

## Design

### 1. Lazy extraction + playback: new composable in `ImageInspectorUI.kt`

```kotlin
@Composable
private fun MotionPhotoVideoPreview(tab: TabState, video: EmbeddedVideo) {
    var extractedFile by remember(tab.file, video) { mutableStateOf<File?>(null) }

    LaunchedEffect(tab.file, video) {
        val temp = withContext(Dispatchers.IO) {
            val dest = File.createTempFile("motion-photo-preview-", ".${video.extension}")
            dest.deleteOnExit()
            extractEmbeddedVideo(tab.file, video, dest)
            dest
        }
        extractedFile = temp
    }

    DisposableEffect(tab.file, video) {
        onDispose { extractedFile?.delete() }
    }

    val file = extractedFile
    if (file != null) {
        VlcVideoPlayer(file, modifier = Modifier.fillMaxSize())
    } else {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Text("Extracting motion video...", color = Color.Gray, fontSize = 12.sp)
        }
    }
}
```

`extractEmbeddedVideo` and `EmbeddedVideo` are already public in `com.multiviewer.parser` (`MotionPhotoExtractor.kt`) and importable directly (`ImageInspectorUI.kt` is in `com.multiviewer.ui`, and other files in this package already do `import com.multiviewer.parser.*`-style access to sibling-package parser types — matches existing convention, e.g. `ImageAnalyzer`, `VideoAnalyzer` usage in `AppState.kt`).

`LaunchedEffect`/`withContext(Dispatchers.IO)` is used here (rather than the raw `Thread`/`EventQueue.invokeLater` pattern `VlcVideoPlayer.kt` and `FfmpegImageSnapshotDecoder.kt` use) because those two exist specifically to hand off from a *non-Compose* native callback thread back into Compose state — this extraction is a plain one-shot side effect entirely within our own control, for which `LaunchedEffect` is Compose's standard idiom and needs no manual thread-hopping.

New imports needed in `ImageInspectorUI.kt`: `androidx.compose.runtime.LaunchedEffect`, `androidx.compose.runtime.DisposableEffect`, `androidx.compose.runtime.mutableStateOf` (may already be present via `androidx.compose.runtime.*`), `kotlinx.coroutines.Dispatchers`, `kotlinx.coroutines.withContext`, `com.multiviewer.parser.EmbeddedVideo`, `com.multiviewer.parser.extractEmbeddedVideo`, `java.io.File`.

### 2. Three-way top row in `ImageInspectorUI`

The existing top `Row` (currently exactly two `weight(1f)` boxes) gains a third, conditional on `tab.embeddedVideo`:

```kotlin
Row(
    modifier = Modifier
        .weight(verticalSplit)
        .fillMaxWidth()
) {
    // Left Panel: Embedded EXIF Thumbnail (unchanged)
    Box(modifier = Modifier.weight(1f).fillMaxHeight()...) { /* unchanged */ }

    // Middle Panel: Primary Image View (unchanged content, now "middle" instead of "right")
    Box(modifier = Modifier.weight(1f).fillMaxHeight()...) { /* unchanged */ }

    // Right Panel: Motion Photo Video (new — only when present)
    val embeddedVideo = tab.embeddedVideo
    if (embeddedVideo != null) {
        Box(
            modifier = Modifier.weight(1f).fillMaxHeight().border(0.5.dp, AppColors.Border).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            MotionPhotoVideoPreview(tab, embeddedVideo)
            Text("MOTION PHOTO VIDEO",
                modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
                style = AppTypography.labelLarge.copy(fontSize = 9.sp, color = AppColors.NeonPurple)
            )
        }
    }
}
```

Because `Row`'s children all share `weight(1f)`, going from 2 to 3 children automatically reflows to an even 3-way split — no manual width math needed. Label color `AppColors.NeonPurple` (defined, currently unused) keeps the three panels visually distinct (blue / green / purple).

## Testing

- No automated test: `ImageInspectorUI.kt` has no existing Compose UI test coverage (confirmed in the prior HEIC-fallback spec), and this is a pure Compose-layer addition — matches the existing project convention for this file.
- Manual: open a real Motion Photo HEIC/JPEG with a detected `embeddedVideo` (e.g. one of the Samsung motion photos used during the HEIC/ffmpeg investigation) — expect three panels (thumbnail / primary image / "Extracting motion video..." then live playback). Open an ordinary image with no embedded video — expect the existing 2-panel layout, byte-identical to today.
