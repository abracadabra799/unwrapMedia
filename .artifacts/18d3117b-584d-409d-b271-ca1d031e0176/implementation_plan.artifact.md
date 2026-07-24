# Implementation Plan - Optimized Media Rendering & HEIC Debug

Optimize the VLC callback player for performance and add deep container tracing to resolve the black screen issues in MP4 and HEIC files.

## User Review Required

> [!IMPORTANT]
> - **Threading & Performance**: Callback rendering currently creates new objects for every frame. I will implement a **Buffer Reuse** strategy and move state updates to the AWT thread to ensure the UI remains responsive and frames are actually rendered.
> - **HEIC Trace**: I will add logging for every box type encountered to pinpoint why the thumbnail extraction is missing the JPEG items in your specific files.

## Proposed Changes

### [Component: UI - Video Player]

#### [MODIFY] [VlcVideoPlayer.kt](file:///Users/dong.kim/AndroidStudioProjects/multiViewer/app/src/main/kotlin/com/multiviewer/ui/VlcVideoPlayer.kt)
- **Zero-Allocation Rendering**:
    - Pre-allocate a `ByteArray` buffer and a Skia `Bitmap` object based on the source dimensions.
    - Reuse these instances for every frame to eliminate GC pressure.
- **UI Thread Synchronization**:
    - Use `java.awt.EventQueue.invokeLater` for all `videoBitmap` updates.
- **Throttled Diagnostic Logging**:
    - Log every 300th frame to verify the playback loop is active without flooding the console.

### [Component: Parser - Image Analysis]

#### [MODIFY] [ImageAnalyzer.kt](file:///Users/dong.kim/AndroidStudioProjects/multiViewer/app/src/main/kotlin/com/multiviewer/parser/ImageAnalyzer.kt)
- **Box Hierarchy Trace**:
    - Recursively print the box structure of the file during analysis.
    - Log every `infe` entry's ID, type, and name.
- **Aggressive JPEG Search**:
    - If metadata search fails, scan the first 1MB of the file for `FF D8 FF E0` or `FF D8 FF E1` magic headers.

## Verification Plan

### Manual Verification
- **MP4 Playback**: Verify that the `[swscaler]` logs are accompanied by "VLC Frame Processed" logs, and that the video is visible.
- **HEIC Previews**: Review the box structure in the console to identify if the thumbnail is stored in a non-standard box (like `thmb` item but without `iref`).
