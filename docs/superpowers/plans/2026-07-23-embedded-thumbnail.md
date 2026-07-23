# Embedded EXIF Thumbnail Display Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract a JPEG/TIFF file's embedded EXIF thumbnail (via `IFD0`'s `NextIFDOffset` chain to `IFD1`) and display it at the top of the Media Summary tab when present.

**Architecture:** `ExifDecoder.kt`'s shared `decodeTiff`/`decodeIfd` functions follow `IFD0`'s existing `NextIFDOffset` field to `IFD1` and recognize the `JPEGInterchangeFormat`/`JPEGInterchangeFormatLength` tag pair there as a `ThumbnailImage` byte-range node. `MediaSummaryBuilder.kt` extracts those bytes into a new `MediaSummary.thumbnail: ByteArray?` field, the same way it already extracts Motion Photo's embedded video. `MediaSummaryView.kt` decodes and renders it via Compose Desktop's built-in `loadImageBitmap`, at the top of the panel, only when present.

**Tech Stack:** Kotlin 2.0.21, Compose Multiplatform Desktop 1.7.3, `kotlin.test`.

## Global Constraints

- JPEG and TIFF only — HEIC's native `iref`/`thmb` thumbnail mechanism is out of scope (a HEIC file's embedded EXIF metadata incidentally getting a thumbnail via the shared `decodeExif`/`decodeTiff` path is an accepted side effect, not a dedicated feature).
- No decoding of the embedded thumbnail's own internal JPEG structure — it's a raw byte range, never walked by `JpegWalker.kt`.
- No IFD chain beyond `IFD1` — only `IFD0`'s immediate `NextIFDOffset` is followed once.
- No handling of non-JPEG (e.g. uncompressed `StripOffsets`-based) thumbnails — only the `JPEGInterchangeFormat`/`JPEGInterchangeFormatLength` tag pair is recognized.
- Displayed thumbnail must be sized to be clearly visible (targeting ~200dp height, aspect-ratio preserved via `ContentScale.Fit`) — not a tiny icon.
- Files without an embedded thumbnail show Media Summary exactly as today, with no placeholder gap.

---

### Task 1: Follow IFD0 → IFD1 and recognize the thumbnail tag pair

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/ExifDecoder.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/ExifDecoderTest.kt`

**Interfaces:**
- Consumes: `readUInt32Endian(reader: ByteReader, offset: Long, littleEndian: Boolean): Long` (existing, unchanged), `TAG_NAMES_IFD0: Map<Int, String>` (existing, reused as `IFD1`'s tag table).
- Produces: `decodeTiff(reader: ByteReader, tiffStart: Long, itemEnd: Long): List<BoxNode>` (existing signature unchanged, but now may return 2 elements `[IFD0, IFD1]` instead of always 1) — Task 2 relies on a `"ThumbnailImage"` node appearing as a child of whichever `BoxNode` `decodeIfd` produces when both thumbnail tags are present (in practice, `IFD1`).

- [ ] **Step 1: Write the failing tests**

Add these two tests to the end of the `ExifDecoderTest` class in `app/src/test/kotlin/com/multiviewer/parser/ExifDecoderTest.kt` (immediately before the class's closing `}`):

```kotlin
    @Test
    fun `decodeTiff follows NextIFDOffset to IFD1 and extracts a ThumbnailImage node from JPEGInterchangeFormat tags`() {
        val tiff = byteArrayOf(
            0x49, 0x49, 0x2a, 0x00, // "II", 42 (little-endian byte order)
            0x08, 0x00, 0x00, 0x00, // IFD0 offset = 8
            0x00, 0x00, // IFD0 entry_count = 0
            0x0e, 0x00, 0x00, 0x00, // IFD0 NextIFDOffset = 14
            0x02, 0x00, // IFD1 entry_count = 2
            0x01, 0x02, 0x04, 0x00, 0x01, 0x00, 0x00, 0x00, 0x2c, 0x00, 0x00, 0x00, // JPEGInterchangeFormat (0x0201) = 44
            0x02, 0x02, 0x04, 0x00, 0x01, 0x00, 0x00, 0x00, 0x04, 0x00, 0x00, 0x00, // JPEGInterchangeFormatLength (0x0202) = 4
            0x00, 0x00, 0x00, 0x00, // IFD1 NextIFDOffset = 0
            0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte(), // thumbnail bytes at offset 44 (4 bytes)
        )
        val reader = byteReaderOf(tiff)
        val ifds = decodeTiff(reader, 0, tiff.size.toLong())

        assertEquals(2, ifds.size)
        assertEquals("IFD0", ifds[0].type)
        assertEquals("IFD1", ifds[1].type)
        val thumbnail = ifds[1].children.first { it.type == "ThumbnailImage" }
        assertEquals(44L, thumbnail.offset)
        assertEquals(4L, thumbnail.size)
        reader.close()
    }

    @Test
    fun `an IFD1 with only one of the two JPEGInterchangeFormat tags produces no ThumbnailImage node`() {
        val tiff = byteArrayOf(
            0x49, 0x49, 0x2a, 0x00, // "II", 42 (little-endian byte order)
            0x08, 0x00, 0x00, 0x00, // IFD0 offset = 8
            0x00, 0x00, // IFD0 entry_count = 0
            0x0e, 0x00, 0x00, 0x00, // IFD0 NextIFDOffset = 14
            0x01, 0x00, // IFD1 entry_count = 1
            0x01, 0x02, 0x04, 0x00, 0x01, 0x00, 0x00, 0x00, 0x64, 0x00, 0x00, 0x00, // JPEGInterchangeFormat only (no Length tag)
            0x00, 0x00, 0x00, 0x00, // IFD1 NextIFDOffset = 0
        )
        val reader = byteReaderOf(tiff)
        val ifds = decodeTiff(reader, 0, tiff.size.toLong())

        assertEquals(2, ifds.size)
        assertEquals(true, ifds[1].children.none { it.type == "ThumbnailImage" })
        reader.close()
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew test --tests "com.multiviewer.parser.ExifDecoderTest" --console=plain`
Expected: The first new test FAILs — `decodeTiff` currently always returns a 1-element list (`assertEquals(2, ifds.size)` fails). The second new test FAILs to compile or fails for the same reason (only 1 element returned, `ifds[1]` throws `IndexOutOfBoundsException`).

- [ ] **Step 3: Follow NextIFDOffset to IFD1 in `decodeTiff`**

In `app/src/main/kotlin/com/multiviewer/parser/ExifDecoder.kt`, replace:

```kotlin
fun decodeTiff(reader: ByteReader, tiffStart: Long, itemEnd: Long): List<BoxNode> {
    if (tiffStart + 8 > itemEnd) return emptyList()
    val byteOrderBytes = reader.readBytes(tiffStart, 2)
    val littleEndian = byteOrderBytes[0] == 'I'.code.toByte() && byteOrderBytes[1] == 'I'.code.toByte()
    val ifd0Offset = readUInt32Endian(reader, tiffStart + 4, littleEndian)
    val ifd0AbsoluteOffset = tiffStart + ifd0Offset
    val visitedOffsets = mutableSetOf<Long>()
    return listOf(
        decodeIfd(reader, tiffStart, ifd0AbsoluteOffset, itemEnd, littleEndian, "IFD0", TAG_NAMES_IFD0, visitedOffsets),
    )
}
```

with:

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

- [ ] **Step 4: Recognize the JPEGInterchangeFormat tag pair in `decodeIfd`**

In `app/src/main/kotlin/com/multiviewer/parser/ExifDecoder.kt`, add these two constants directly after the existing `private const val TAG_MAKER_NOTE = 0x927C` line:

```kotlin
private const val TAG_JPEG_INTERCHANGE_FORMAT = 0x0201
private const val TAG_JPEG_INTERCHANGE_FORMAT_LENGTH = 0x0202
```

Replace:

```kotlin
private fun decodeIfd(
    reader: ByteReader,
    tiffStart: Long,
    ifdOffset: Long,
    itemEnd: Long,
    littleEndian: Boolean,
    label: String,
    tagNames: Map<Int, String>,
    visitedOffsets: MutableSet<Long>,
): BoxNode {
    if (!visitedOffsets.add(ifdOffset)) {
        return BoxNode(label, ifdOffset, 0, 0, warnings = listOf("Circular or duplicate IFD reference detected, skipping"))
    }
    if (ifdOffset + 2 > itemEnd) {
        return BoxNode(label, ifdOffset, 0, 0, warnings = listOf("IFD too short to contain entry_count"))
    }
    val entryCount = readUInt16Endian(reader, ifdOffset, littleEndian)
    val fields = mutableListOf<BoxField>()
    val children = mutableListOf<BoxNode>()
    var pos = ifdOffset + 2
    for (i in 0 until entryCount) {
        if (pos + 12 > itemEnd) break
        val tag = readUInt16Endian(reader, pos, littleEndian)
        val fieldType = readUInt16Endian(reader, pos + 2, littleEndian)
        val count = readUInt32Endian(reader, pos + 4, littleEndian)
        val valueOffsetPos = pos + 8
        val typeSize = TIFF_TYPE_SIZES[fieldType] ?: 1
        val totalSize = typeSize * count
        val valueAbsolutePos = if (totalSize <= 4) {
            valueOffsetPos
        } else {
            tiffStart + readUInt32Endian(reader, valueOffsetPos, littleEndian)
        }

        when (tag) {
            TAG_EXIF_IFD_POINTER -> {
                val subOffset = tiffStart + readUInt32Endian(reader, valueOffsetPos, littleEndian)
                children.add(
                    decodeIfd(reader, tiffStart, subOffset, itemEnd, littleEndian, "Exif", TAG_NAMES_EXIF, visitedOffsets),
                )
            }
            TAG_GPS_IFD_POINTER -> {
                val subOffset = tiffStart + readUInt32Endian(reader, valueOffsetPos, littleEndian)
                children.add(
                    decodeIfd(reader, tiffStart, subOffset, itemEnd, littleEndian, "GPS", TAG_NAMES_GPS, visitedOffsets),
                )
            }
            TAG_INTEROP_IFD_POINTER -> {
                val subOffset = tiffStart + readUInt32Endian(reader, valueOffsetPos, littleEndian)
                children.add(
                    decodeIfd(reader, tiffStart, subOffset, itemEnd, littleEndian, "Interop", TAG_NAMES_EXIF, visitedOffsets),
                )
            }
            TAG_MAKER_NOTE -> {
                if (valueAbsolutePos >= 0 && valueAbsolutePos + count <= itemEnd) {
                    children.add(decodeMakerNote(reader, tiffStart, valueAbsolutePos, count.toInt(), littleEndian, itemEnd))
                }
            }
            else -> {
                val name = tagNames[tag] ?: "Tag 0x${tag.toString(16).padStart(4, '0')}"
                if (valueAbsolutePos < 0 || valueAbsolutePos + totalSize > itemEnd) {
                    fields.add(BoxField(name, "(out of bounds)", valueAbsolutePos, totalSize))
                } else {
                    val display = formatTiffValue(reader, fieldType, count.toInt(), valueAbsolutePos, littleEndian)
                    fields.add(BoxField(name, display, valueAbsolutePos, totalSize))
                }
            }
        }
        pos += 12
    }
    return BoxNode(
        type = label, offset = ifdOffset, headerSize = 2, size = pos - ifdOffset,
        fields = fields, children = children,
    )
}
```

with:

```kotlin
private fun decodeIfd(
    reader: ByteReader,
    tiffStart: Long,
    ifdOffset: Long,
    itemEnd: Long,
    littleEndian: Boolean,
    label: String,
    tagNames: Map<Int, String>,
    visitedOffsets: MutableSet<Long>,
): BoxNode {
    if (!visitedOffsets.add(ifdOffset)) {
        return BoxNode(label, ifdOffset, 0, 0, warnings = listOf("Circular or duplicate IFD reference detected, skipping"))
    }
    if (ifdOffset + 2 > itemEnd) {
        return BoxNode(label, ifdOffset, 0, 0, warnings = listOf("IFD too short to contain entry_count"))
    }
    val entryCount = readUInt16Endian(reader, ifdOffset, littleEndian)
    val fields = mutableListOf<BoxField>()
    val children = mutableListOf<BoxNode>()
    var jpegThumbnailOffset: Long? = null
    var jpegThumbnailLength: Long? = null
    var pos = ifdOffset + 2
    for (i in 0 until entryCount) {
        if (pos + 12 > itemEnd) break
        val tag = readUInt16Endian(reader, pos, littleEndian)
        val fieldType = readUInt16Endian(reader, pos + 2, littleEndian)
        val count = readUInt32Endian(reader, pos + 4, littleEndian)
        val valueOffsetPos = pos + 8
        val typeSize = TIFF_TYPE_SIZES[fieldType] ?: 1
        val totalSize = typeSize * count
        val valueAbsolutePos = if (totalSize <= 4) {
            valueOffsetPos
        } else {
            tiffStart + readUInt32Endian(reader, valueOffsetPos, littleEndian)
        }

        when (tag) {
            TAG_EXIF_IFD_POINTER -> {
                val subOffset = tiffStart + readUInt32Endian(reader, valueOffsetPos, littleEndian)
                children.add(
                    decodeIfd(reader, tiffStart, subOffset, itemEnd, littleEndian, "Exif", TAG_NAMES_EXIF, visitedOffsets),
                )
            }
            TAG_GPS_IFD_POINTER -> {
                val subOffset = tiffStart + readUInt32Endian(reader, valueOffsetPos, littleEndian)
                children.add(
                    decodeIfd(reader, tiffStart, subOffset, itemEnd, littleEndian, "GPS", TAG_NAMES_GPS, visitedOffsets),
                )
            }
            TAG_INTEROP_IFD_POINTER -> {
                val subOffset = tiffStart + readUInt32Endian(reader, valueOffsetPos, littleEndian)
                children.add(
                    decodeIfd(reader, tiffStart, subOffset, itemEnd, littleEndian, "Interop", TAG_NAMES_EXIF, visitedOffsets),
                )
            }
            TAG_MAKER_NOTE -> {
                if (valueAbsolutePos >= 0 && valueAbsolutePos + count <= itemEnd) {
                    children.add(decodeMakerNote(reader, tiffStart, valueAbsolutePos, count.toInt(), littleEndian, itemEnd))
                }
            }
            TAG_JPEG_INTERCHANGE_FORMAT -> {
                jpegThumbnailOffset = readUInt32Endian(reader, valueOffsetPos, littleEndian)
            }
            TAG_JPEG_INTERCHANGE_FORMAT_LENGTH -> {
                jpegThumbnailLength = readUInt32Endian(reader, valueOffsetPos, littleEndian)
            }
            else -> {
                val name = tagNames[tag] ?: "Tag 0x${tag.toString(16).padStart(4, '0')}"
                if (valueAbsolutePos < 0 || valueAbsolutePos + totalSize > itemEnd) {
                    fields.add(BoxField(name, "(out of bounds)", valueAbsolutePos, totalSize))
                } else {
                    val display = formatTiffValue(reader, fieldType, count.toInt(), valueAbsolutePos, littleEndian)
                    fields.add(BoxField(name, display, valueAbsolutePos, totalSize))
                }
            }
        }
        pos += 12
    }

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

    return BoxNode(
        type = label, offset = ifdOffset, headerSize = 2, size = pos - ifdOffset,
        fields = fields, children = children,
    )
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew test --tests "com.multiviewer.parser.ExifDecoderTest" --console=plain`
Expected: PASS

- [ ] **Step 6: Run the full suite**

Run: `./gradlew test --console=plain`
Expected: BUILD SUCCESSFUL, all tests pass (193 existing + 2 new = 195). In particular, confirm the existing test `decodeTiff decodes a standalone TIFF blob with no HEIF offset wrapper` (which has `NextIFDOffset = 0`) still asserts `assertEquals(1, ifds.size)` and passes unchanged — this is the regression guard proving `NextIFDOffset = 0` correctly produces no `IFD1`.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/ExifDecoder.kt \
        app/src/test/kotlin/com/multiviewer/parser/ExifDecoderTest.kt
git commit -m "feat: follow EXIF IFD0->IFD1 and extract embedded JPEG thumbnail byte range"
```

---

### Task 2: Extract thumbnail bytes into MediaSummary

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/MediaSummary.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt`

**Interfaces:**
- Consumes: `findFirst(node: BoxNode, predicate: (BoxNode) -> Boolean): BoxNode?` (existing, unchanged), a `"ThumbnailImage"` node with `offset`/`size` (from Task 1).
- Produces: `MediaSummary.thumbnail: ByteArray?` (new field) — Task 3 reads this in `MediaSummaryView.kt`.

- [ ] **Step 1: Write the failing tests**

Add these two tests to the end of the `MediaSummaryBuilderTest` class in `app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt` (immediately before the class's closing `}`):

```kotlin
    @Test
    fun `a JPEG-shaped tree with a ThumbnailImage node populates MediaSummary#thumbnail with the exact bytes`() {
        val thumbnailBytes = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte())
        val file = File.createTempFile("thumbnail-summary-test", ".jpg")
        file.deleteOnExit()
        file.writeBytes(ByteArray(20) + thumbnailBytes)
        val thumbnailOffset = 20L

        val thumbnailNode = BoxNode(type = "ThumbnailImage", offset = thumbnailOffset, headerSize = 0, size = thumbnailBytes.size.toLong())
        val ifd1 = BoxNode(type = "IFD1", offset = 0, headerSize = 0, size = 0, children = listOf(thumbnailNode))
        val ifd0 = BoxNode(type = "IFD0", offset = 0, headerSize = 0, size = 0)
        val app1 = BoxNode(type = "APP1", offset = 0, headerSize = 4, size = 0, children = listOf(ifd0, ifd1))
        val root = BoxNode(
            type = "root", offset = 0, headerSize = 0, size = 0,
            children = listOf(BoxNode(type = "SOI", offset = 0, headerSize = 2, size = 2), app1),
        )

        val summary = buildMediaSummary(root, file)

        assertTrue(summary.thumbnail != null)
        assertTrue(thumbnailBytes.contentEquals(summary.thumbnail!!))
    }

    @Test
    fun `an image tree with no ThumbnailImage node leaves MediaSummary#thumbnail null`() {
        val root = BoxNode(
            type = "root", offset = 0, headerSize = 0, size = 0,
            children = listOf(BoxNode(type = "SOI", offset = 0, headerSize = 2, size = 2)),
        )
        val summary = buildMediaSummary(root, tempFile())
        assertEquals(null, summary.thumbnail)
    }
```

Add `assertTrue` to the existing `kotlin.test` imports at the top of the file — replace:

```kotlin
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
```

with:

```kotlin
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew test --tests "com.multiviewer.parser.MediaSummaryBuilderTest" --console=plain`
Expected: Both new tests FAIL to compile — `MediaSummary` has no `thumbnail` property yet.

- [ ] **Step 3: Add the `thumbnail` field to `MediaSummary`**

In `app/src/main/kotlin/com/multiviewer/parser/MediaSummary.kt`, replace:

```kotlin
data class MediaSummary(
    val category: MediaCategory,
    val sections: List<SummarySection>,
    val motionPhotoVideoSections: List<SummarySection>? = null,
)
```

with:

```kotlin
data class MediaSummary(
    val category: MediaCategory,
    val sections: List<SummarySection>,
    val motionPhotoVideoSections: List<SummarySection>? = null,
    val thumbnail: ByteArray? = null,
)
```

- [ ] **Step 4: Extract the thumbnail bytes in `buildMediaSummary`**

In `app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt`, replace:

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
    return MediaSummary(category, sections, motionPhotoVideoSections)
}
```

with:

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

- [ ] **Step 5: Run the tests to verify they pass**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew test --tests "com.multiviewer.parser.MediaSummaryBuilderTest" --console=plain`
Expected: PASS

- [ ] **Step 6: Run the full suite**

Run: `./gradlew test --console=plain`
Expected: BUILD SUCCESSFUL, all tests pass (195 existing + 2 new = 197)

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/MediaSummary.kt \
        app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt \
        app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt
git commit -m "feat: extract embedded thumbnail bytes into MediaSummary#thumbnail"
```

---

### Task 3: Render the thumbnail at the top of Media Summary

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/MediaSummaryView.kt`

**Interfaces:**
- Consumes: `MediaSummary.thumbnail: ByteArray?` (from Task 2).
- Produces: nothing new for later tasks — this is the final task in the plan.

This task has no automated test — this codebase has no Compose UI test setup (no snapshot/screenshot testing framework configured), and no other UI-only change in this project has one either. Verification is manual (Step 3 below).

- [ ] **Step 1: Add the thumbnail rendering to `MediaSummaryView.kt`**

Read the current file first — it's short (74 lines). Replace the full contents of `app/src/main/kotlin/com/multiviewer/ui/MediaSummaryView.kt` with:

```kotlin
package com.multiviewer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multiviewer.parser.MediaSummary
import com.multiviewer.parser.SummarySection
import java.io.ByteArrayInputStream

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

@Composable
private fun SummaryBox(title: String, sections: List<SummarySection>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        Text(
            title,
            modifier = Modifier.padding(bottom = 8.dp),
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
        )
        sections.forEach { section -> SectionContent(section) }
    }
}

@Composable
private fun SectionContent(section: SummarySection) {
    Column(modifier = Modifier.padding(bottom = 12.dp)) {
        Text(
            section.title,
            modifier = Modifier.padding(bottom = 6.dp),
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.primary,
        )
        section.fields.forEach { field ->
            MetadataRow(field.label, field.value)
        }
    }
}
```

This adds the `ThumbnailPreview` composable and its `summary.thumbnail?.let { ... }` call site at the top of the `LazyColumn`, plus the four new imports (`androidx.compose.foundation.Image`, `androidx.compose.foundation.layout.heightIn`, `androidx.compose.ui.layout.ContentScale`, `androidx.compose.ui.res.loadImageBitmap`, `androidx.compose.runtime.remember`, `java.io.ByteArrayInputStream`) — every other line (`MediaSummaryView`'s existing body below the new `item` block, `SummaryBox`, `SectionContent`) is unchanged from the current file.

- [ ] **Step 2: Compile and run the full test suite**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew test --console=plain`
Expected: BUILD SUCCESSFUL, all tests pass (197 — unchanged from Task 2, since this task adds no new tests, only Compose UI code that the existing parser/data-layer test suite doesn't exercise).

- [ ] **Step 3: Manual verification**

Launch the app (`./gradlew :app:run`) and open a real JPEG file with a known embedded EXIF thumbnail (most camera- and phone-originated JPEGs have one — check with `exiftool -b -ThumbnailImage yourfile.jpg > /tmp/thumb.jpg` beforehand if you want to confirm one exists before testing in-app). Confirm:
- The thumbnail renders in a bordered box at the top of the Media Summary tab, above "General", at a clearly visible size (not a tiny icon) — legible even though embedded EXIF thumbnails are typically low-resolution (~160×120), so some softness from upscaling is expected.
- Opening a JPEG/TIFF file with no embedded thumbnail shows Media Summary exactly as before this change, with no empty box or gap where the thumbnail would be.
- Opening a PNG/BMP/GIF/AVIF/HEIC file (none of which produce a `ThumbnailImage` node from this change) is unaffected.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/MediaSummaryView.kt
git commit -m "feat: render embedded EXIF thumbnail at the top of Media Summary"
```
