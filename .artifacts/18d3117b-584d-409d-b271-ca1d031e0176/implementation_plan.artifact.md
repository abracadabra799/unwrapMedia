# Implementation Plan - Reliable Media Rendering (Callback Player & HEIC Fix)

Transition to a high-reliability "Callback Rendering" approach for video and further refine the HEIC thumbnail extraction to solve the persistent black screen issues on macOS.

## User Review Required

> [!IMPORTANT]
> - **Video Performance**: Callback rendering involves copying pixel data from VLC to the JVM and then to the GPU. For 4K files, this may increase CPU usage. We will use a fast buffer-copy strategy to mitigate this.
> - **Threading**: Rendering must happen on the main UI thread, while VLC decodes on its own threads. Proper synchronization is critical to avoid crashes.

## Proposed Changes

### [Component: UI - Video Player]

#### [MODIFY] [VlcVideoPlayer.kt](file:///Users/dong.kim/AndroidStudioProjects/multiViewer/app/src/main/kotlin/com/multiviewer/ui/VlcVideoPlayer.kt)
- **Architectural Shift**: Replace `EmbeddedMediaPlayerComponent` (native window) with `CallbackMediaPlayerComponent` (memory buffer).
- **Buffer Management**:
    - Implement `BufferFormatCallback` to negotiate the frame size and pixel format (RV32/ARGB).
    - Implement `RenderCallback` to receive a `ByteBuffer` of the decoded frame.
- **Compose Bridge**:
    - Convert the raw `IntArray` or `ByteBuffer` into a `Bitmap` (Skia).
    - Update a `mutableStateOf<ImageBitmap?>` to trigger Compose recomposition for every frame.
- **Cleanup**: Remove `SwingPanel` and replace it with a standard Compose `Image`.

### [Component: Parser - HEIC Extraction]

#### [MODIFY] [ImageAnalyzer.kt](file:///Users/dong.kim/AndroidStudioProjects/multiViewer/app/src/main/kotlin/com/multiviewer/parser/ImageAnalyzer.kt)
- **Deep Debugging**: Add exhaustive logging for every item ID and type found in the `iinf` box.
- **Item Extraction Fix**: Ensure that for HEIC, we are looking for the *correct* `jpeg` item (some files have multiple tiny icons and one large preview).
- **Fallback to Main Item**: If no JPEG is found, log the specific error from Skia when trying to decode the main `hvc1` item.

## Verification Plan

### Manual Verification
- **MP4 Playback**: Open a video and confirm that frames are visible and fluid. Check CPU usage in Activity Monitor.
- **HEIC Previews**: Open multiple HEIC files and verify if the "Found JPEG item..." log appears and the image is displayed.
- **UI Overlay**: Confirm that the "Video Active" status text is clearly visible *over* the video (proving it's a standard Compose component now).
