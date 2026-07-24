# Implementation Plan - HEIC Embedded Thumbnail Extraction

Resolve the black preview issue for HEIC files by implementing a targeted search for embedded JPEG thumbnails using ISOBMFF metadata structures.

## User Review Required

> [!IMPORTANT]
> - **Decoding Limitation**: Since Skia does not natively support HEVC, we are relying on the existence of a compatible embedded JPEG thumbnail. Most HEIC files from modern smartphones include this.
> - **Metadata Depth**: This implementation adds support for the `iref` box to formally identify thumbnail relationships rather than blindly scanning for JPEG magic bytes.

## Proposed Changes

### [Component: Parser - ISOBMFF Decoders]

#### [NEW] [IrefBoxDecoder.kt](file:///Users/dong.kim/AndroidStudioProjects/multiViewer/app/src/main/kotlin/com/multiviewer/parser/IrefBoxDecoder.kt)
- Decode the Item Reference box.
- Parse `SingleItemTypeReferenceBox` entries (like `thmb`, `cdsc`).
- Surface `from_item_ID` and `to_item_ID` relationships.

#### [MODIFY] [Decoders.kt](file:///Users/dong.kim/AndroidStudioProjects/multiViewer/app/src/main/kotlin/com/multiviewer/parser/Decoders.kt)
- Register `iref` using `IrefBoxDecoder`.

### [Component: Parser - Image Analysis]

#### [MODIFY] [ImageAnalyzer.kt](file:///Users/dong.kim/AndroidStudioProjects/multiViewer/app/src/main/kotlin/com/multiviewer/parser/ImageAnalyzer.kt)
- **Primary Item Detection**: Read the `pitm` value to know which item is the master image.
- **Reference Tracking**: Search the `iref` results for a `thmb` reference pointing to the primary item.
- **Absolute Offset Resolution**:
    - Find the file offset of the `idat` box.
    - If an item in `iloc` uses `construction_method=1`, add the `idat` payload start to its relative offset.
- **Optimized JPEG Extraction**: Prioritize the item identified via `iref` as the thumbnail.

## Verification Plan

### Automated Tests
- Test `IrefBoxDecoder` with a sample reference box.
- Test the new offset resolution logic in `ImageAnalyzer`.

### Manual Verification
- Open a HEIC file:
    - Verify the `iref` box appears in the structure tree.
    - Verify that the preview area shows the thumbnail instead of a black screen.
- Check console logs for "Thumbnail found via iref for item X".
