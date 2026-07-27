# Implementation Plan - Media UI Refinement & JPEG Fix

Fix the missing JPEG thumbnails and reorganize the dual-pane preview to meet specific requirements for HEIC and MP4 files.

## User Review Required

> [!IMPORTANT]
> - **JPEG Thumbnails**: I will restore thumbnail extraction for standard JPEG files by ensuring the Exif-based scanner is triggered even if the file lacks a high-level `meta` box.
> - **HEIC Layout**: For HEIC, both panels will display static images. The right panel will focus on the primary decoded image (or an error if decoding is impossible).
> - **MP4 Layout**: The left thumbnail panel will be removed for video files, giving the live player full focus in the preview area.

## Proposed Changes

### [Component: Parser - Image Analysis]

#### [MODIFY] [ImageAnalyzer.kt](file:///Users/dong.kim/AndroidStudioProjects/multiViewer/app/src/main/kotlin/com/multiviewer/parser/ImageAnalyzer.kt)
- **JPEG Discovery Fix**: Update `tryExtractEmbeddedJpeg` to scan for Exif thumbnails even if no `meta` box is found.
- **Robustness**: Ensure `embeddedThumbnail` is populated for standard JPEGs that contain an embedded preview in their Exif data.

### [Component: UI - Image Inspector]

#### [MODIFY] [ImageInspectorUI.kt](file:///Users/dong.kim/AndroidStudioProjects/multiViewer/app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt)
- **HEIC Specifics**: Remove the VLC fallback from the right panel for HEIC files. The right panel will now strictly show the `primaryImage` (Skia-decoded) or a specific error message.
- **Labeling**: Clarify labels to match "Embedded EXIF Thumbnail" vs "Primary Image".

### [Component: UI - Video Inspector]

#### [MODIFY] [VideoInspectorUI.kt](file:///Users/dong.kim/AndroidStudioProjects/multiViewer/app/src/main/kotlin/com/multiviewer/ui/VideoInspectorUI.kt)
- **Layout Simplification**: Remove the `Row` in the top preview area for video files. The `VlcVideoPlayer` will now occupy the full width of the center panel's top section.

## Verification Plan

### Manual Verification
- **Standard JPEG**: Confirm the left pane shows the embedded thumbnail.
- **HEIC**: Confirm the left pane shows the EXIF JPEG thumbnail and the right pane shows the main image (if decodable).
- **MP4**: Confirm there is no left thumbnail pane; the player should be full-width.
