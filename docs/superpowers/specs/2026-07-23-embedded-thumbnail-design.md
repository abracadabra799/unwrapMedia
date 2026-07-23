# Embedded EXIF Thumbnail Display — Design

## Background

Many JPEG and TIFF files carry a small embedded preview image inside their EXIF metadata — a second TIFF IFD (`IFD1`), chained after the main `IFD0` via `IFD0`'s `NextIFDOffset` field, holding `JPEGInterchangeFormat`(tag `0x0201`, a byte offset) and `JPEGInterchangeFormatLength`(tag `0x0202`, a byte length) that together point at a small standalone JPEG thumbnail embedded in the file. The user asked to surface this thumbnail in the Media Summary tab when present.

The app's shared `decodeIfd`/`decodeTiff` functions (`ExifDecoder.kt`) currently only walk `IFD0` — `decodeTiff` explicitly stops after one IFD, a decision recorded as a non-goal in the earlier TIFF-support design ("no multi-page TIFF: `decodeTiff` only ever decodes IFD0"). Following `IFD0`'s `NextIFDOffset` to `IFD1` is exactly the missing piece: `decodeIfd`'s per-entry loop already leaves `pos` sitting immediately after the last directory entry once it returns (`BoxNode.size = pos - ifdOffset`), which is precisely where the 4-byte `NextIFDOffset` field lives — no restructuring needed, just one more read after the existing `decodeIfd` call and a second call using the same function for `IFD1`.

This is the app's first time rendering actual pixel content — every other feature so far (Structure Analyser, Media Summary) is pure structure/metadata display, with pixel/video decoding explicitly declared out of scope in prior designs (PNG/BMP/GIF's "no rendering" non-goals). Displaying the embedded thumbnail is a deliberate, narrow exception: the thumbnail is a small, already-compressed JPEG the app can hand to a decoder as opaque bytes, not something the app decodes itself.

Scope, confirmed with the user: **JPEG and TIFF only** (both share the same EXIF/TIFF IFD1 mechanism via `decodeTiff`/`decodeExif`). HEIC's *native* thumbnail mechanism (`iref`/`thmb` item references, entirely different from TIFF IFDs) is explicitly deferred to a future increment.

One caveat worth stating up front: `decodeExif`/`decodeTiff` is shared code, also called from `MetaBoxDecoder.kt` whenever a HEIC/HEIF file's `meta` box contains an `'Exif'` item (common for HEIC photos from phones). Since this design's `IFD0`→`IFD1` change lives in that shared function, a HEIC file whose *embedded EXIF metadata* happens to contain an `IFD1` JPEG thumbnail will incidentally also have it extracted and displayed — this is a harmless side effect of code reuse, not the dedicated `iref`/`thmb` support that's deferred, and requires no extra code to make happen.

## Goal

Opening a JPEG or TIFF file that has an embedded EXIF thumbnail shows that thumbnail at the top of the Media Summary tab, above the "General" section, sized to be clearly visible (targeting ~200dp height, aspect-ratio preserved) — not a tiny icon. Files without an embedded thumbnail show Media Summary exactly as they do today, with no empty placeholder space.

## Non-Goals

- No HEIC/HEIF *native* thumbnail support (`iref`/`thmb` item references) — a structurally unrelated mechanism, deferred to a future increment. (A HEIC file's embedded EXIF metadata incidentally getting a thumbnail extracted via the shared `decodeExif`/`decodeTiff` path is a harmless side effect, not this feature — see Background.)
- No decoding of the embedded thumbnail's own internal JPEG structure (SOF/DQT/etc.) — it's extracted as a raw byte range and handed directly to Compose's image loader, not walked by `JpegWalker.kt`. It doesn't appear as a browsable node in Structure Analyser beyond a single `ThumbnailImage` marker node with its byte range.
- No multi-page TIFF support beyond `IFD1` — if a file's IFD chain continues past `IFD1` (`IFD2`, `IFD3`, ...), those aren't followed; only `IFD0`'s immediate `NextIFDOffset` is read. This matches real-world EXIF files, where the chain is always `IFD0 → IFD1` (thumbnail) and stops.
- No handling of non-JPEG embedded thumbnails (e.g. an uncompressed `StripOffsets`-based thumbnail in `IFD1`, an older and now-rare TIFF thumbnail convention) — only the `JPEGInterchangeFormat`/`JPEGInterchangeFormatLength` tag pair is recognized.
- No thumbnail support for AVIF/PNG/BMP/GIF — none of these formats have a standard embedded-thumbnail mechanism in this app's scope.

## Design

### 1. Follow `IFD0` → `IFD1` (`ExifDecoder.kt`)

`decodeTiff` changes from always returning a single-element list to conditionally including `IFD1`:

```kotlin
fun decodeTiff(reader: ByteReader, tiffStart: Long, itemEnd: Long): List<BoxNode> {
    if (tiffStart + 8 > itemEnd) return emptyList()
    val byteOrderBytes = reader.readBytes(tiffStart, 2)
    val littleEndian = byteOrderBytes[0] == 'I'.code.toByte() && byteOrderBytes[1] == 'I'.code.toByte()
    val ifd0Offset = readUInt32Endian(reader, tiffStart + 4, littleEndian)
    val ifd0AbsoluteOffset = tiffStart + ifd0Offset
    val visitedOffsets = mutableSetOf<Long>()
    val ifd0Node = decodeIfd(reader, tiffStart, ifd0AbsoluteOffset, itemEnd, littleEndian, "IFD0", TAG_NAMES_IFD0, visitedOffsets)

    val ifds = mutableListOf(ifd0Node)
    val nextIfdOffsetPos = ifd0Node.offset + ifd0Node.size
    if (nextIfdOffsetPos + 4 <= itemEnd) {
        val nextIfdOffset = readUInt32Endian(reader, nextIfdOffsetPos, littleEndian)
        if (nextIfdOffset != 0L) {
            val ifd1AbsoluteOffset = tiffStart + nextIfdOffset
            ifds.add(decodeIfd(reader, tiffStart, ifd1AbsoluteOffset, itemEnd, littleEndian, "IFD1", TAG_NAMES_IFD0, visitedOffsets))
        }
    }
    return ifds
}
```

`IFD1` reuses `TAG_NAMES_IFD0` as its tag table (both IFDs draw from the same TIFF tag namespace; no new table needed). The existing `visitedOffsets` set (already threaded through every `decodeIfd` call for circular-reference protection) naturally protects against a malformed `NextIFDOffset` pointing back at `IFD0`.

### 2. Recognize the thumbnail tag pair (`ExifDecoder.kt`, inside `decodeIfd`)

Two new tag constants alongside the existing `TAG_EXIF_IFD_POINTER`/`TAG_GPS_IFD_POINTER`/etc.:

```kotlin
private const val TAG_JPEG_INTERCHANGE_FORMAT = 0x0201
private const val TAG_JPEG_INTERCHANGE_FORMAT_LENGTH = 0x0202
```

`decodeIfd`'s entry loop tracks both values (they're two separate directory entries that must be correlated after the loop, unlike the single-tag pointers handled inline today):

```kotlin
var jpegThumbnailOffset: Long? = null
var jpegThumbnailLength: Long? = null
```

Added as new cases in the existing `when (tag)` block:

```kotlin
TAG_JPEG_INTERCHANGE_FORMAT -> {
    jpegThumbnailOffset = readUInt32Endian(reader, valueOffsetPos, littleEndian)
}
TAG_JPEG_INTERCHANGE_FORMAT_LENGTH -> {
    jpegThumbnailLength = readUInt32Endian(reader, valueOffsetPos, littleEndian)
}
```

After the entry loop, before constructing the returned `BoxNode`, if both were found and the resulting byte range is in-bounds, a `ThumbnailImage` child node is added:

```kotlin
val thumbnailOffset = jpegThumbnailOffset
val thumbnailLength = jpegThumbnailLength
if (thumbnailOffset != null && thumbnailLength != null && thumbnailLength > 0) {
    val thumbnailAbsoluteOffset = tiffStart + thumbnailOffset
    if (thumbnailAbsoluteOffset >= 0 && thumbnailAbsoluteOffset + thumbnailLength <= itemEnd) {
        children.add(
            BoxNode(type = "ThumbnailImage", offset = thumbnailAbsoluteOffset, headerSize = 0, size = thumbnailLength, summary = "$thumbnailLength bytes"),
        )
    }
}
```

This check runs for every IFD `decodeIfd` processes (`IFD0`, `IFD1`, `Exif`, `GPS`, `Interop`), not just `IFD1` specifically — harmless, since tags `0x0201`/`0x0202` only appear in a thumbnail IFD in practice. Keeping the check generic in `decodeIfd` avoids adding an `IFD1`-specific code path.

### 3. Extract the thumbnail bytes into `MediaSummary` (`MediaSummaryBuilder.kt`)

`MediaSummary` gains a new field:

```kotlin
data class MediaSummary(
    val category: MediaCategory,
    val sections: List<SummarySection>,
    val motionPhotoVideoSections: List<SummarySection>? = null,
    val thumbnail: ByteArray? = null,
)
```

(Adding a `ByteArray` to a data class means `==`/`hashCode()` won't do a deep byte comparison — an accepted, low-risk trade-off here since no existing code compares whole `MediaSummary` instances with `==`; tests only assert on individual fields like `.sections`/`.category`.)

`buildMediaSummary` extracts the bytes the same way `buildMotionPhotoVideoSummary` already extracts the embedded Motion Photo video — open a fresh `ByteReader` on the file and read the exact byte range:

```kotlin
fun buildMediaSummary(root: BoxNode, file: File): MediaSummary {
    val category = detectCategory(root)
    val sections = if (category == MediaCategory.IMAGE) {
        buildImageSummary(root, file)
    } else {
        buildVideoSummary(root, file.length())
    }
    val motionPhotoVideoSections = if (category == MediaCategory.IMAGE) {
        buildMotionPhotoVideoSummary(root, file)
    } else {
        null
    }
    val thumbnail = if (category == MediaCategory.IMAGE) buildThumbnail(root, file) else null
    return MediaSummary(category, sections, motionPhotoVideoSections, thumbnail)
}

private fun buildThumbnail(root: BoxNode, file: File): ByteArray? {
    val thumbnailNode = findFirst(root) { it.type == "ThumbnailImage" } ?: return null
    return try {
        ByteReader.open(file).use { reader ->
            reader.readBytes(thumbnailNode.offset, thumbnailNode.size.toInt())
        }
    } catch (e: Exception) {
        null
    }
}
```

`findFirst` already searches the whole tree recursively, so it finds the `ThumbnailImage` node regardless of whether it's nested under a JPEG's `APP1` → `IFD0` → `IFD1` or a standalone TIFF's root-level `IFD1`, with no format-specific branching needed.

### 4. Render it at the top of Media Summary (`MediaSummaryView.kt`)

```kotlin
@Composable
fun MediaSummaryView(summary: MediaSummary?) {
    if (summary == null) return
    val videoSections = summary.motionPhotoVideoSections
    LazyColumn(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        summary.thumbnail?.let { bytes ->
            item { ThumbnailPreview(bytes) }
        }
        if (videoSections != null) {
            item {
                SummaryBox("📷 이미지", summary.sections)
                Spacer(modifier = Modifier.height(12.dp))
                SummaryBox("🎬 동영상 (모션포토)", videoSections)
            }
        } else {
            items(summary.sections) { section ->
                SectionContent(section)
            }
        }
    }
}

@Composable
private fun ThumbnailPreview(bytes: ByteArray) {
    val bitmap = remember(bytes) { loadImageBitmap(ByteArrayInputStream(bytes)) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = "Embedded thumbnail",
            modifier = Modifier.heightIn(max = 200.dp),
            contentScale = ContentScale.Fit,
        )
    }
    Spacer(modifier = Modifier.height(12.dp))
}
```

New imports: `androidx.compose.foundation.Image`, `androidx.compose.foundation.layout.heightIn`, `androidx.compose.ui.layout.ContentScale`, `androidx.compose.ui.res.loadImageBitmap` (confirmed present in the project's existing Compose Multiplatform 1.7.3 dependency — `androidx/compose/ui/res/ImageResources_desktopKt.class` in `ui-desktop-1.7.3.jar`, no new dependency needed), `java.io.ByteArrayInputStream`.

`loadImageBitmap` decodes standard raster formats (JPEG among them) via Skia under the hood — since the embedded thumbnail is always a plain JPEG by the `JPEGInterchangeFormat` tag's own definition, no format detection is needed on the extracted bytes. `contentScale = ContentScale.Fit` combined with `heightIn(max = 200.dp)` scales the thumbnail up to that height while preserving its aspect ratio (never stretching or cropping) — matches the user's "not too small" requirement, while accepting that embedded EXIF thumbnails are inherently low-resolution (commonly ~160×120), so the enlarged image will look soft, the same trade-off every OS file-properties preview pane makes.

If bitmap decoding throws (a corrupt or non-JPEG thumbnail — should not happen given the tag's definition, but untrusted file input warrants a guard), `MediaSummaryBuilder.buildThumbnail`'s existing try/catch only guards the byte-range read, not the decode step, which happens later in the UI layer. `ThumbnailPreview` wraps `loadImageBitmap` so a decode failure doesn't crash the whole Media Summary tab — falling back to rendering nothing for that file, consistent with how a missing thumbnail already renders nothing.

## Testing

- `ExifDecoderTest`/wherever TIFF/EXIF tests live today: a synthetic `IFD0` whose `NextIFDOffset` points at a synthetic `IFD1` containing `JPEGInterchangeFormat`/`JPEGInterchangeFormatLength` asserts a `ThumbnailImage` child node appears on `IFD1` with the correct offset/size; a synthetic `IFD0` with `NextIFDOffset = 0` asserts `decodeTiff` returns only the one `IFD0` node (no regression to today's TIFF behavior); a synthetic `IFD1` with only one of the two tags present (missing pair) asserts no `ThumbnailImage` node is added.
- `MediaSummaryBuilderTest`: a tree with a `ThumbnailImage` node backed by a real small JPEG byte range (written to a temp file) asserts `MediaSummary.thumbnail` contains exactly those bytes; a tree without any `ThumbnailImage` node asserts `thumbnail` is `null`.
- Manual verification: open a real JPEG with a known embedded EXIF thumbnail (most camera-originated JPEGs have one) and confirm it renders above "General" at a legible size; open a JPEG/TIFF without one and confirm Media Summary looks exactly as it does today, with no gap or placeholder.
