# Implementation Plan - Video Dashboard & Summary Integration

Refactor the **Video Inspector**'s central area to match the scrollable, MediaInfo-style dashboard layout used in the Image Inspector.

## User Review Required

> [!IMPORTANT]
> - **Scrollable Video Dashboard**: The center panel will switch to a `LazyColumn` containing:
>     1. Visual Preview (Fixed height or scrollable)
>     2. Bitrate Analysis & Box Treemap
>     3. MediaInfo-style Summary Cards (General, Video, Audio)
> - **Consistency**: This mirrors the "Image/Motion Photo" structure, ensuring a unified user experience across different media types.

## Proposed Changes

### [Component: UI]

#### [MODIFY] [VideoInspectorUI.kt](file:///Users/dong.kim/AndroidStudioProjects/multiViewer/app/src/main/kotlin/com/multiviewer/ui/VideoInspectorUI.kt)
- Update `centerPanel` to use a `LazyColumn`.
- **Item 1: Visual Preview**: Keep the black preview placeholder at the top.
- **Item 2: Analysis Row**: Place `BitrateVisualizer` and `BoxBlockView` in a row within the `LazyColumn`.
- **Item 3: Media Summary**: Use the `SummaryBox` component (to be made shared or duplicated) to display General, Track List, Video, and Audio sections.

#### [MODIFY] [ImageInspectorUI.kt](file:///Users/dong.kim/AndroidStudioProjects/multiViewer/app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt)
- Extract the `SummaryBox` and `PropertyRow` (if shared) to a common UI file if appropriate, or ensure they are available for `VideoInspectorUI`.

## Verification Plan

### Automated Tests
- Confirm successful build with `:app:classes`.

### Manual Verification
- Open a 4K MOV file: Verify that the bitrate chart and summary cards (General, Video, Audio) appear in a scrollable list.
- Verify that the right-side "Detailed Properties" panel correctly reflects the selected Box from the left tree.
