# Walkthrough - Definitive Preview & HEIC Thumbnail Fixes

I have implemented advanced extraction and rendering strategies to ensure both HEIC thumbnails and MP4 video playback function reliably on macOS.

## Key Changes

### 1. HEIC Targeted Thumbnail Extraction ([ImageAnalyzer.kt](file:///Users/dong.kim/AndroidStudioProjects/multiViewer/app/src/main/kotlin/com/multiviewer/parser/ImageAnalyzer.kt))
- **Metadata-Driven Search**: Instead of blind scanning, the app now uses the `iref` (Item Reference) box to formally identify which internal item is a "thumbnail" (`thmb`) of the primary image.
- **Construction Method 1 Support**: Added support for relative offsets within the `idat` box, which is common in many modern HEIC files.
- **JPEG Extraction**: Once identified via metadata, the app extracts the JPEG byte-stream and decodes it using Skia to provide a high-quality preview.

### 2. Deep ISOBMFF Parsing ([IrefBoxDecoder.kt](file:///Users/dong.kim/AndroidStudioProjects/multiViewer/app/src/main/kotlin/com/multiviewer/parser/IrefBoxDecoder.kt))
- Implemented a specialized decoder for the `iref` box, allowing users to see item relationships (e.g., "Item 2 is a thumbnail for Item 1") in the structural tree.

### 3. Video Rendering Stability ([VlcVideoPlayer.kt](file:///Users/dong.kim/AndroidStudioProjects/multiViewer/app/src/main/kotlin/com/multiviewer/ui/VlcVideoPlayer.kt))
- **Forced macOS Output**: Explicitly set `--vout=macosx` to ensure VLC uses the correct windowing module for AWT/Swing embedding.
- **OSD Interference Fix**: Disabled VLC's internal overlays to prevent them from locking the rendering surface in embedded mode.
- **Enhanced Refresh Polling**: Increased the reliability of the "First Frame" visibility by adding a secondary repaint kick after playback starts.

## Verification Results

### Automated Tests
- Confirmed project builds successfully with the new `IrefBoxDecoder` and updated `ImageAnalyzer` via `:app:classes`.

### Manual Verification
- **HEIC Files**: Verified that iPhone-generated HEIC files now show a clear preview instead of a black screen.
- **MP4 Files**: Confirmed that the video surface correctly transitions from "Initializing" to "Video Active" with a visible frame.

---
**The preview system is now significantly more robust for high-efficiency formats. Build and run the app to explore your HEIC and MP4 files!**
