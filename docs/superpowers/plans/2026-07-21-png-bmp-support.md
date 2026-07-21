# PNG and BMP Format Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add support for standalone PNG and BMP files — a new chunk-stream walker for PNG (mirroring the existing JPEG marker-segment walker) and a new flat-header walker for BMP, plus Media Summary integration for both.

**Architecture:** PNG is a flat sequence of self-describing `length`+`type`+`data`+`crc` chunks, decoded by a new `PngWalker.kt` that mirrors `JpegWalker.kt`'s "flat walker, decode known segments, generic node for the rest" pattern. PNG's official `eXIf` metadata chunk holds a raw TIFF structure and is decoded by reusing the existing `decodeTiff` function unchanged — the same function already used for standalone TIFF files and JPEG/HEIC's embedded EXIF. BMP is two fixed-layout, non-chunked structs (`BITMAPFILEHEADER` + `BITMAPINFOHEADER`), decoded by a new `BmpWalker.kt` using local little-endian helpers (BMP is the one format in this codebase that isn't big-endian).

**Tech Stack:** Kotlin 2.0.21, `kotlin.test`.

## Global Constraints

- No `zTXt`/`iTXt` decoding — shown as generic, field-less nodes.
- No PNG interlace (Adam7) scan-detail decoding — `interlace_method` is a raw field on `IHDR`, nothing further.
- No animated PNG (APNG) support — out of scope.
- No BMP Color Space support — deferred, same reasoning as TIFF's deferred Color Space.
- No BMP header variants beyond `BITMAPINFOHEADER` (40 bytes) — `BITMAPCOREHEADER`/`BITMAPV4HEADER`/`BITMAPV5HEADER` show as a generic `DIBHEADER` node with just a `header_size` field.
- No pixel-data node for either format — raw pixel bytes aren't modeled as a box.
- No PNG chunk CRC verification — CRCs are skipped over (used only to compute chunk boundaries), not validated or displayed.
- No GIF, animated GIF — separate, later efforts (previously deferred by the user).

---

### Task 1: PNG chunk walker core — signature detection, chunk loop, `IHDR` decode

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/PngWalker.kt`
- Create: `app/src/test/kotlin/com/multiviewer/parser/PngWalkerTest.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/parser/ParseFile.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/ParseFileIntegrationTest.kt`

**Interfaces:**
- Consumes: `ByteReader.readUInt32(offset: Long): Long`, `.readUInt8(offset: Long): Int`, `.readFourCC(offset: Long): String`, `.readBytes(offset: Long, len: Int): ByteArray` (all existing, big-endian, already used by the ISOBMFF/JPEG walkers). `BoxNode`/`BoxField` (existing, `BoxNode.kt`).
- Produces: `fun parsePngChunks(reader: ByteReader, start: Long, end: Long): List<BoxNode>` (new, `PngWalker.kt`) — later tasks (Task 2, Task 4) call this indirectly via `parseFile` and read its `IHDR`/`eXIf` output nodes. `val PNG_COLOR_TYPE_NAMES: Map<Int, String>` (new, top-level, non-private so `MediaSummaryBuilder.kt` can reuse it in Task 4) — maps PNG's `color_type` byte (0/2/3/4/6) to a human label ("Grayscale"/"Truecolor"/"Indexed"/"Grayscale+Alpha"/"Truecolor+Alpha").

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/multiviewer/parser/PngWalkerTest.kt`:

```kotlin
package com.multiviewer.parser

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun pngChunk(type: String, data: ByteArray): ByteArray {
    val length = data.size
    val lengthBytes = byteArrayOf(
        ((length shr 24) and 0xFF).toByte(),
        ((length shr 16) and 0xFF).toByte(),
        ((length shr 8) and 0xFF).toByte(),
        (length and 0xFF).toByte(),
    )
    val typeBytes = type.toByteArray(Charsets.US_ASCII)
    val crc = ByteArray(4)
    return lengthBytes + typeBytes + data + crc
}

private fun readerOver(bytes: ByteArray, namePrefix: String): ByteReader {
    val tmp = File.createTempFile(namePrefix, ".bin")
    tmp.deleteOnExit()
    tmp.writeBytes(bytes)
    return ByteReader.open(tmp)
}

class PngWalkerTest {
    @Test
    fun `decodes IHDR fields and a WxH summary`() {
        val ihdrData = byteArrayOf(
            0x00, 0x00, 0x03, 0x20, // width = 800
            0x00, 0x00, 0x02, 0x58, // height = 600
            0x08, // bit_depth = 8
            0x06, // color_type = 6 (Truecolor+Alpha)
            0x00, 0x00, 0x00, // compression_method, filter_method, interlace_method = 0
        )
        val bytes = pngChunk("IHDR", ihdrData)
        readerOver(bytes, "png-walker-ihdr").use { reader ->
            val nodes = parsePngChunks(reader, 0, bytes.size.toLong())
            assertEquals(1, nodes.size)
            val ihdr = nodes[0]
            assertEquals("IHDR", ihdr.type)
            assertEquals("800", ihdr.fields.first { it.name == "width" }.value)
            assertEquals("600", ihdr.fields.first { it.name == "height" }.value)
            assertEquals("8", ihdr.fields.first { it.name == "bit_depth" }.value)
            assertEquals("6", ihdr.fields.first { it.name == "color_type" }.value)
            assertEquals("800x600, Truecolor+Alpha, 8-bit", ihdr.summary)
        }
    }

    @Test
    fun `an unrecognized chunk type shows as a generic, field-less node`() {
        val bytes = pngChunk("tIME", byteArrayOf(0x07, 0xEA.toByte(), 0x01, 0x0F, 0x0C, 0x1E, 0x00))
        readerOver(bytes, "png-walker-generic").use { reader ->
            val nodes = parsePngChunks(reader, 0, bytes.size.toLong())
            assertEquals(1, nodes.size)
            assertEquals("tIME", nodes[0].type)
            assertTrue(nodes[0].fields.isEmpty())
            assertEquals(bytes.size.toLong(), nodes[0].size)
        }
    }

    @Test
    fun `a chunk declaring more bytes than remain produces a warning node`() {
        val bytes = byteArrayOf(
            0x00, 0x00, 0x00, 0x10, // declared length = 16, but no data follows
            'I'.code.toByte(), 'H'.code.toByte(), 'D'.code.toByte(), 'R'.code.toByte(),
        )
        readerOver(bytes, "png-walker-truncated").use { reader ->
            val nodes = parsePngChunks(reader, 0, bytes.size.toLong())
            assertEquals(1, nodes.size)
            assertTrue(nodes[0].warnings.isNotEmpty())
        }
    }
}
```

Add this test to the end of the `ParseFileIntegrationTest` class in `app/src/test/kotlin/com/multiviewer/parser/ParseFileIntegrationTest.kt` (immediately before the class's closing `}`):

```kotlin
    @Test
    fun `parses a PNG file via the chunk walker, not the ISOBMFF path`() {
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val ihdrData = byteArrayOf(
            0x00, 0x00, 0x00, 0x0A, // width = 10
            0x00, 0x00, 0x00, 0x0A, // height = 10
            0x08, 0x02, 0x00, 0x00, 0x00, // bit_depth=8, color_type=2 (Truecolor), rest=0
        )
        val ihdrChunk = pngChunkBytes("IHDR", ihdrData)
        val iendChunk = pngChunkBytes("IEND", ByteArray(0))
        val bytes = signature + ihdrChunk + iendChunk
        val tmp = File.createTempFile("multiviewer-png", ".png")
        tmp.deleteOnExit()
        tmp.writeBytes(bytes)

        val root = parseFile(tmp)

        assertEquals(listOf("IHDR", "IEND"), root.children.map { it.type })
    }
```

Add this helper to the bottom of the same file, alongside the existing `box`/`fullBox`/`uint32` helpers:

```kotlin
private fun pngChunkBytes(type: String, data: ByteArray): ByteArray {
    val length = data.size
    val lengthBytes = byteArrayOf(
        ((length shr 24) and 0xFF).toByte(),
        ((length shr 16) and 0xFF).toByte(),
        ((length shr 8) and 0xFF).toByte(),
        (length and 0xFF).toByte(),
    )
    return lengthBytes + type.toByteArray(Charsets.US_ASCII) + data + ByteArray(4)
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "com.multiviewer.parser.PngWalkerTest" --tests "com.multiviewer.parser.ParseFileIntegrationTest"`
Expected: `PngWalkerTest` FAILs to compile (`parsePngChunks` doesn't exist yet). `ParseFileIntegrationTest`'s new test also fails to compile for the same reason.

- [ ] **Step 3: Implement the PNG chunk walker**

Create `app/src/main/kotlin/com/multiviewer/parser/PngWalker.kt`:

```kotlin
package com.multiviewer.parser

val PNG_COLOR_TYPE_NAMES = mapOf(
    0 to "Grayscale",
    2 to "Truecolor",
    3 to "Indexed",
    4 to "Grayscale+Alpha",
    6 to "Truecolor+Alpha",
)

fun parsePngChunks(reader: ByteReader, start: Long, end: Long): List<BoxNode> {
    val result = mutableListOf<BoxNode>()
    var pos = start
    while (pos < end) {
        if (pos + 8 > end) {
            result.add(BoxNode("?", pos, 0, end - pos, warnings = listOf("Trailing ${end - pos} byte(s): too short for a chunk header")))
            break
        }
        val length = reader.readUInt32(pos)
        val type = reader.readFourCC(pos + 4)
        val dataStart = pos + 8
        val chunkTotalSize = 8L + length + 4L
        if (pos + chunkTotalSize > end) {
            result.add(BoxNode(type, pos, 8, end - pos, warnings = listOf("Chunk declares length $length but only ${end - pos - 8} byte(s) remain")))
            break
        }
        result.add(decodePngChunk(reader, type, pos, dataStart, length, chunkTotalSize))
        pos += chunkTotalSize
    }
    return result
}

private fun decodePngChunk(reader: ByteReader, type: String, offset: Long, dataStart: Long, length: Long, totalSize: Long): BoxNode =
    when (type) {
        "IHDR" -> decodeIhdr(reader, offset, dataStart, totalSize)
        else -> BoxNode(type = type, offset = offset, headerSize = 8, size = totalSize)
    }

private fun decodeIhdr(reader: ByteReader, offset: Long, dataStart: Long, totalSize: Long): BoxNode {
    if (totalSize < 25) { // 8 (length+type) + 13 (IHDR body) + 4 (crc)
        return BoxNode(type = "IHDR", offset = offset, headerSize = 8, size = totalSize, warnings = listOf("IHDR chunk too short to contain all fields"))
    }
    val width = reader.readUInt32(dataStart)
    val height = reader.readUInt32(dataStart + 4)
    val bitDepth = reader.readUInt8(dataStart + 8)
    val colorType = reader.readUInt8(dataStart + 9)
    val compressionMethod = reader.readUInt8(dataStart + 10)
    val filterMethod = reader.readUInt8(dataStart + 11)
    val interlaceMethod = reader.readUInt8(dataStart + 12)
    val colorTypeName = PNG_COLOR_TYPE_NAMES[colorType] ?: "Unknown"
    return BoxNode(
        type = "IHDR", offset = offset, headerSize = 8, size = totalSize,
        fields = listOf(
            BoxField("width", width.toString(), dataStart, 4),
            BoxField("height", height.toString(), dataStart + 4, 4),
            BoxField("bit_depth", bitDepth.toString(), dataStart + 8, 1),
            BoxField("color_type", colorType.toString(), dataStart + 9, 1),
            BoxField("compression_method", compressionMethod.toString(), dataStart + 10, 1),
            BoxField("filter_method", filterMethod.toString(), dataStart + 11, 1),
            BoxField("interlace_method", interlaceMethod.toString(), dataStart + 12, 1),
        ),
        summary = "${width}x${height}, $colorTypeName, ${bitDepth}-bit",
    )
}
```

- [ ] **Step 4: Wire PNG detection into `parseFile`**

Replace the full contents of `app/src/main/kotlin/com/multiviewer/parser/ParseFile.kt` with:

```kotlin
package com.multiviewer.parser

import java.io.File

fun parseFile(path: File): BoxNode {
    registerAllDecoders()
    ByteReader.open(path).use { reader ->
        val isJpeg = reader.length >= 2 && reader.readUInt8(0) == 0xFF && reader.readUInt8(1) == 0xD8
        val isPng = !isJpeg && isPngMagic(reader)
        val isTiff = !isJpeg && !isPng && isTiffMagic(reader)
        val children = when {
            isJpeg -> parseJpegSegments(reader, 0, reader.length)
            isPng -> parsePngChunks(reader, 8, reader.length)
            isTiff -> decodeTiff(reader, 0, reader.length)
            else -> parseBoxes(reader, 0, reader.length)
        }
        return BoxNode(type = "root", offset = 0, headerSize = 0, size = reader.length, children = children)
    }
}

private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

private fun isPngMagic(reader: ByteReader): Boolean {
    if (reader.length < 8) return false
    return reader.readBytes(0, 8).contentEquals(PNG_SIGNATURE)
}

private fun isTiffMagic(reader: ByteReader): Boolean {
    if (reader.length < 4) return false
    val bytes = reader.readBytes(0, 4)
    val isLittleEndian = bytes[0] == 'I'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
        bytes[2] == 0x2A.toByte() && bytes[3] == 0x00.toByte()
    val isBigEndian = bytes[0] == 'M'.code.toByte() && bytes[1] == 'M'.code.toByte() &&
        bytes[2] == 0x00.toByte() && bytes[3] == 0x2A.toByte()
    return isLittleEndian || isBigEndian
}
```

(`parsePngChunks` is called with `start = 8` to skip past the 8-byte signature, mirroring how the JPEG path starts at offset 0 and the TIFF path starts at offset 0 — PNG is the one format whose first chunk starts after a fixed preamble rather than at byte 0.)

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew test --tests "com.multiviewer.parser.PngWalkerTest" --tests "com.multiviewer.parser.ParseFileIntegrationTest"`
Expected: PASS

- [ ] **Step 6: Run the full suite**

Run: `./gradlew test --console=plain`
Expected: BUILD SUCCESSFUL, all tests pass (160 existing + 4 new = 164)

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/PngWalker.kt \
        app/src/main/kotlin/com/multiviewer/parser/ParseFile.kt \
        app/src/test/kotlin/com/multiviewer/parser/PngWalkerTest.kt \
        app/src/test/kotlin/com/multiviewer/parser/ParseFileIntegrationTest.kt
git commit -m "feat: add PNG chunk walker with IHDR decoding"
```

---

### Task 2: PNG metadata chunks — `pHYs`, `tEXt`, `eXIf`

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/PngWalker.kt`
- Modify: `app/src/test/kotlin/com/multiviewer/parser/PngWalkerTest.kt`

**Interfaces:**
- Consumes: `decodeTiff(reader: ByteReader, tiffStart: Long, itemEnd: Long): List<BoxNode>` (existing, `ExifDecoder.kt`, unchanged) — `eXIf`'s data holds a raw TIFF structure with no `"Exif\0\0"` prefix, so `tiffStart` is simply the chunk's `dataStart`.
- Produces: nothing new beyond Task 1's `parsePngChunks` — this task only adds more chunk types `decodePngChunk` can decode.

- [ ] **Step 1: Write the failing tests**

Add these three tests to the end of the `PngWalkerTest` class in `app/src/test/kotlin/com/multiviewer/parser/PngWalkerTest.kt` (immediately before the class's closing `}`):

```kotlin
    @Test
    fun `decodes pHYs pixel density fields`() {
        val physData = byteArrayOf(
            0x00, 0x00, 0x0B, 0x13, // pixels_per_unit_x = 2835
            0x00, 0x00, 0x0B, 0x13, // pixels_per_unit_y = 2835
            0x01, // unit_specifier = 1 (meter)
        )
        val bytes = pngChunk("pHYs", physData)
        readerOver(bytes, "png-walker-phys").use { reader ->
            val nodes = parsePngChunks(reader, 0, bytes.size.toLong())
            val phys = nodes[0]
            assertEquals("pHYs", phys.type)
            assertEquals("2835", phys.fields.first { it.name == "pixels_per_unit_x" }.value)
            assertEquals("2835", phys.fields.first { it.name == "pixels_per_unit_y" }.value)
            assertEquals("meter", phys.fields.first { it.name == "unit_specifier" }.value)
            assertEquals("2835x2835 px/meter", phys.summary)
        }
    }

    @Test
    fun `decodes tEXt keyword and text`() {
        val textData = "Comment Made with multiViewer".toByteArray(Charsets.ISO_8859_1)
        val bytes = pngChunk("tEXt", textData)
        readerOver(bytes, "png-walker-text").use { reader ->
            val nodes = parsePngChunks(reader, 0, bytes.size.toLong())
            val text = nodes[0]
            assertEquals("tEXt", text.type)
            assertEquals("Comment", text.fields.first { it.name == "keyword" }.value)
            assertEquals("Made with multiViewer", text.fields.first { it.name == "text" }.value)
            assertEquals("Comment: Made with multiViewer", text.summary)
        }
    }

    @Test
    fun `decodes an eXIf chunk by reusing decodeTiff, producing an IFD0 child`() {
        val tiffBytes = byteArrayOf(
            0x49, 0x49, 0x2A, 0x00, // "II", 42 (little-endian byte order)
            0x08, 0x00, 0x00, 0x00, // IFD0 offset = 8
            0x02, 0x00, // entry_count = 2
            0x00, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, 0x80.toByte(), 0x02, 0x00, 0x00, // ImageWidth = 640
            0x01, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, 0xE0.toByte(), 0x01, 0x00, 0x00, // ImageLength = 480
            0x00, 0x00, 0x00, 0x00, // next IFD offset = 0
        )
        val bytes = pngChunk("eXIf", tiffBytes)
        readerOver(bytes, "png-walker-exif").use { reader ->
            val nodes = parsePngChunks(reader, 0, bytes.size.toLong())
            val exifChunk = nodes[0]
            assertEquals("eXIf", exifChunk.type)
            assertEquals(1, exifChunk.children.size)
            val ifd0 = exifChunk.children[0]
            assertEquals("IFD0", ifd0.type)
            assertEquals("640", ifd0.fields.first { it.name == "ImageWidth" }.value)
            assertEquals("480", ifd0.fields.first { it.name == "ImageLength" }.value)
        }
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "com.multiviewer.parser.PngWalkerTest"`
Expected: All three new tests FAIL — `pHYs`/`tEXt`/`eXIf` currently fall into `decodePngChunk`'s generic `else` branch (no fields, no children).

- [ ] **Step 3: Add the three chunk decoders**

In `app/src/main/kotlin/com/multiviewer/parser/PngWalker.kt`, replace:

```kotlin
private fun decodePngChunk(reader: ByteReader, type: String, offset: Long, dataStart: Long, length: Long, totalSize: Long): BoxNode =
    when (type) {
        "IHDR" -> decodeIhdr(reader, offset, dataStart, totalSize)
        else -> BoxNode(type = type, offset = offset, headerSize = 8, size = totalSize)
    }
```

with:

```kotlin
private fun decodePngChunk(reader: ByteReader, type: String, offset: Long, dataStart: Long, length: Long, totalSize: Long): BoxNode =
    when (type) {
        "IHDR" -> decodeIhdr(reader, offset, dataStart, totalSize)
        "pHYs" -> decodePhys(reader, offset, dataStart, totalSize)
        "tEXt" -> decodeText(reader, offset, dataStart, length, totalSize)
        "eXIf" -> decodeExifChunk(reader, offset, dataStart, dataStart + length, totalSize)
        else -> BoxNode(type = type, offset = offset, headerSize = 8, size = totalSize)
    }

private fun decodePhys(reader: ByteReader, offset: Long, dataStart: Long, totalSize: Long): BoxNode {
    if (totalSize < 21) { // 8 (length+type) + 9 (pHYs body) + 4 (crc)
        return BoxNode(type = "pHYs", offset = offset, headerSize = 8, size = totalSize, warnings = listOf("pHYs chunk too short to contain all fields"))
    }
    val ppuX = reader.readUInt32(dataStart)
    val ppuY = reader.readUInt32(dataStart + 4)
    val unitSpecifier = reader.readUInt8(dataStart + 8)
    val unitLabel = if (unitSpecifier == 1) "meter" else "unknown"
    return BoxNode(
        type = "pHYs", offset = offset, headerSize = 8, size = totalSize,
        fields = listOf(
            BoxField("pixels_per_unit_x", ppuX.toString(), dataStart, 4),
            BoxField("pixels_per_unit_y", ppuY.toString(), dataStart + 4, 4),
            BoxField("unit_specifier", unitLabel, dataStart + 8, 1),
        ),
        summary = "${ppuX}x${ppuY} px/$unitLabel",
    )
}

private fun decodeText(reader: ByteReader, offset: Long, dataStart: Long, length: Long, totalSize: Long): BoxNode {
    val bytes = reader.readBytes(dataStart, length.toInt())
    val nullIndex = bytes.indexOf(0)
    if (nullIndex < 0) {
        return BoxNode(type = "tEXt", offset = offset, headerSize = 8, size = totalSize, warnings = listOf("Missing keyword/text separator"))
    }
    val keyword = String(bytes, 0, nullIndex, Charsets.ISO_8859_1)
    val text = String(bytes, nullIndex + 1, bytes.size - nullIndex - 1, Charsets.ISO_8859_1)
    return BoxNode(
        type = "tEXt", offset = offset, headerSize = 8, size = totalSize,
        fields = listOf(
            BoxField("keyword", keyword, dataStart, nullIndex.toLong()),
            BoxField("text", text, dataStart + nullIndex + 1, (bytes.size - nullIndex - 1).toLong()),
        ),
        summary = "$keyword: $text",
    )
}

private fun decodeExifChunk(reader: ByteReader, offset: Long, dataStart: Long, dataEnd: Long, totalSize: Long): BoxNode {
    val children = decodeTiff(reader, dataStart, dataEnd)
    return BoxNode(type = "eXIf", offset = offset, headerSize = 8, size = totalSize, children = children, summary = "Exif metadata")
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests "com.multiviewer.parser.PngWalkerTest"`
Expected: PASS

- [ ] **Step 5: Run the full suite**

Run: `./gradlew test --console=plain`
Expected: BUILD SUCCESSFUL, all tests pass (164 existing + 3 new = 167)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/PngWalker.kt \
        app/src/test/kotlin/com/multiviewer/parser/PngWalkerTest.kt
git commit -m "feat: decode PNG pHYs, tEXt, and eXIf chunks"
```

---

### Task 3: BMP header walker

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/BmpWalker.kt`
- Create: `app/src/test/kotlin/com/multiviewer/parser/BmpWalkerTest.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/parser/ParseFile.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/ParseFileIntegrationTest.kt`

**Interfaces:**
- Consumes: `ByteReader.readBytes(offset: Long, len: Int): ByteArray` (existing) — BMP fields are little-endian, so this task reads raw bytes and assembles them itself rather than using the big-endian `readUInt16`/`readUInt32`.
- Produces: `fun parseBmpHeaders(reader: ByteReader, start: Long, end: Long): List<BoxNode>` (new, `BmpWalker.kt`) — Task 4 reads its `BITMAPFILEHEADER`/`BITMAPINFOHEADER` output nodes.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/multiviewer/parser/BmpWalkerTest.kt`:

```kotlin
package com.multiviewer.parser

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun uint16LE(value: Int): ByteArray = byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())

private fun uint32LE(value: Long): ByteArray = byteArrayOf(
    (value and 0xFF).toByte(),
    ((value shr 8) and 0xFF).toByte(),
    ((value shr 16) and 0xFF).toByte(),
    ((value shr 24) and 0xFF).toByte(),
)

private fun int32LE(value: Int): ByteArray = uint32LE(value.toLong() and 0xFFFFFFFFL)

private fun bmpFileHeader(fileSize: Long, pixelDataOffset: Long): ByteArray =
    byteArrayOf('B'.code.toByte(), 'M'.code.toByte()) + uint32LE(fileSize) + ByteArray(4) + uint32LE(pixelDataOffset)

private fun bitmapInfoHeader(width: Int, height: Int, bitCount: Int): ByteArray =
    uint32LE(40) + int32LE(width) + int32LE(height) + uint16LE(1) + uint16LE(bitCount) + ByteArray(24)

private fun readerOver(bytes: ByteArray, namePrefix: String): ByteReader {
    val tmp = File.createTempFile(namePrefix, ".bin")
    tmp.deleteOnExit()
    tmp.writeBytes(bytes)
    return ByteReader.open(tmp)
}

class BmpWalkerTest {
    @Test
    fun `decodes BITMAPFILEHEADER and BITMAPINFOHEADER for a bottom-up bitmap`() {
        val bytes = bmpFileHeader(fileSize = 122, pixelDataOffset = 54) + bitmapInfoHeader(width = 100, height = 50, bitCount = 24)
        readerOver(bytes, "bmp-walker-basic").use { reader ->
            val nodes = parseBmpHeaders(reader, 0, bytes.size.toLong())
            assertEquals(listOf("BITMAPFILEHEADER", "BITMAPINFOHEADER"), nodes.map { it.type })
            assertEquals("122", nodes[0].fields.first { it.name == "file_size" }.value)
            assertEquals("54", nodes[0].fields.first { it.name == "pixel_data_offset" }.value)
            assertEquals("100", nodes[1].fields.first { it.name == "width" }.value)
            assertEquals("50", nodes[1].fields.first { it.name == "height" }.value)
            assertEquals("0", nodes[1].fields.first { it.name == "compression" }.value)
            assertEquals("100x50, 24-bit", nodes[1].summary)
        }
    }

    @Test
    fun `a negative height decodes as a signed value (top-down bitmap)`() {
        val bytes = bmpFileHeader(fileSize = 122, pixelDataOffset = 54) + bitmapInfoHeader(width = 100, height = -50, bitCount = 24)
        readerOver(bytes, "bmp-walker-topdown").use { reader ->
            val nodes = parseBmpHeaders(reader, 0, bytes.size.toLong())
            assertEquals("-50", nodes[1].fields.first { it.name == "height" }.value)
        }
    }

    @Test
    fun `a non-40-byte DIB header falls back to a generic DIBHEADER node`() {
        val coreHeader = uint32LE(12) + ByteArray(8) // BITMAPCOREHEADER, 12 bytes total
        val bytes = bmpFileHeader(fileSize = 68, pixelDataOffset = 26) + coreHeader
        readerOver(bytes, "bmp-walker-core-header").use { reader ->
            val nodes = parseBmpHeaders(reader, 0, bytes.size.toLong())
            assertEquals("DIBHEADER", nodes[1].type)
            assertEquals("12", nodes[1].fields.first { it.name == "header_size" }.value)
        }
    }

    @Test
    fun `a file too short for a BITMAPFILEHEADER produces a warning node`() {
        val bytes = byteArrayOf('B'.code.toByte(), 'M'.code.toByte(), 0x00, 0x00)
        readerOver(bytes, "bmp-walker-truncated").use { reader ->
            val nodes = parseBmpHeaders(reader, 0, bytes.size.toLong())
            assertEquals(1, nodes.size)
            assertTrue(nodes[0].warnings.isNotEmpty())
        }
    }
}
```

Add this test to the end of the `ParseFileIntegrationTest` class in `app/src/test/kotlin/com/multiviewer/parser/ParseFileIntegrationTest.kt` (immediately before the class's closing `}`):

```kotlin
    @Test
    fun `parses a BMP file via the header walker, not the ISOBMFF or TIFF path`() {
        val fileHeader = byteArrayOf('B'.code.toByte(), 'M'.code.toByte()) + uint32LE(70) + ByteArray(4) + uint32LE(54)
        val infoHeader = uint32LE(40) + int32LE(10) + int32LE(10) + uint16LE(1) + uint16LE(24) + ByteArray(24)
        val bytes = fileHeader + infoHeader
        val tmp = File.createTempFile("multiviewer-bmp", ".bmp")
        tmp.deleteOnExit()
        tmp.writeBytes(bytes)

        val root = parseFile(tmp)

        assertEquals(listOf("BITMAPFILEHEADER", "BITMAPINFOHEADER"), root.children.map { it.type })
    }
```

Add these little-endian helpers to the bottom of the same file, alongside the existing (big-endian) `uint32`/`box`/`fullBox` helpers:

```kotlin
private fun uint16LE(value: Int): ByteArray = byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())

private fun uint32LE(value: Long): ByteArray = byteArrayOf(
    (value and 0xFF).toByte(),
    ((value shr 8) and 0xFF).toByte(),
    ((value shr 16) and 0xFF).toByte(),
    ((value shr 24) and 0xFF).toByte(),
)

private fun int32LE(value: Int): ByteArray = uint32LE(value.toLong() and 0xFFFFFFFFL)
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "com.multiviewer.parser.BmpWalkerTest" --tests "com.multiviewer.parser.ParseFileIntegrationTest"`
Expected: `BmpWalkerTest` FAILs to compile (`parseBmpHeaders` doesn't exist yet). `ParseFileIntegrationTest`'s new test also fails to compile for the same reason.

- [ ] **Step 3: Implement the BMP header walker**

Create `app/src/main/kotlin/com/multiviewer/parser/BmpWalker.kt`:

```kotlin
package com.multiviewer.parser

private fun readUInt16LE(reader: ByteReader, offset: Long): Int {
    val b = reader.readBytes(offset, 2)
    return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
}

private fun readUInt32LE(reader: ByteReader, offset: Long): Long {
    val b = reader.readBytes(offset, 4)
    return (b[0].toLong() and 0xFF) or
        ((b[1].toLong() and 0xFF) shl 8) or
        ((b[2].toLong() and 0xFF) shl 16) or
        ((b[3].toLong() and 0xFF) shl 24)
}

private fun readInt32LE(reader: ByteReader, offset: Long): Int = readUInt32LE(reader, offset).toInt()

fun parseBmpHeaders(reader: ByteReader, start: Long, end: Long): List<BoxNode> {
    if (end - start < 14) {
        return listOf(BoxNode("?", start, 0, end - start, warnings = listOf("File too short for a BITMAPFILEHEADER")))
    }
    val result = mutableListOf<BoxNode>()
    result.add(decodeBitmapFileHeader(reader, start))

    val dibStart = start + 14
    if (end - dibStart < 4) return result
    val headerSize = readUInt32LE(reader, dibStart)
    result.add(
        if (headerSize == 40L) {
            decodeBitmapInfoHeader(reader, dibStart, end)
        } else {
            BoxNode(
                type = "DIBHEADER", offset = dibStart, headerSize = 0, size = minOf(headerSize, end - dibStart),
                fields = listOf(BoxField("header_size", headerSize.toString(), dibStart, 4)),
            )
        },
    )
    return result
}

private fun decodeBitmapFileHeader(reader: ByteReader, offset: Long): BoxNode {
    val fileSize = readUInt32LE(reader, offset + 2)
    val pixelDataOffset = readUInt32LE(reader, offset + 10)
    return BoxNode(
        type = "BITMAPFILEHEADER", offset = offset, headerSize = 0, size = 14,
        fields = listOf(
            BoxField("signature", "BM", offset, 2),
            BoxField("file_size", fileSize.toString(), offset + 2, 4),
            BoxField("pixel_data_offset", pixelDataOffset.toString(), offset + 10, 4),
        ),
    )
}

private fun decodeBitmapInfoHeader(reader: ByteReader, offset: Long, end: Long): BoxNode {
    if (end - offset < 40) {
        return BoxNode(type = "BITMAPINFOHEADER", offset = offset, headerSize = 0, size = end - offset, warnings = listOf("Truncated BITMAPINFOHEADER"))
    }
    val width = readInt32LE(reader, offset + 4)
    val height = readInt32LE(reader, offset + 8)
    val bitCount = readUInt16LE(reader, offset + 14)
    val compression = readUInt32LE(reader, offset + 16)
    return BoxNode(
        type = "BITMAPINFOHEADER", offset = offset, headerSize = 0, size = 40,
        fields = listOf(
            BoxField("width", width.toString(), offset + 4, 4),
            BoxField("height", height.toString(), offset + 8, 4),
            BoxField("bit_count", bitCount.toString(), offset + 14, 2),
            BoxField("compression", compression.toString(), offset + 16, 4),
        ),
        summary = "${width}x${height}, ${bitCount}-bit",
    )
}
```

- [ ] **Step 4: Wire BMP detection into `parseFile`**

Replace the full contents of `app/src/main/kotlin/com/multiviewer/parser/ParseFile.kt` with:

```kotlin
package com.multiviewer.parser

import java.io.File

fun parseFile(path: File): BoxNode {
    registerAllDecoders()
    ByteReader.open(path).use { reader ->
        val isJpeg = reader.length >= 2 && reader.readUInt8(0) == 0xFF && reader.readUInt8(1) == 0xD8
        val isPng = !isJpeg && isPngMagic(reader)
        val isBmp = !isJpeg && !isPng && isBmpMagic(reader)
        val isTiff = !isJpeg && !isPng && !isBmp && isTiffMagic(reader)
        val children = when {
            isJpeg -> parseJpegSegments(reader, 0, reader.length)
            isPng -> parsePngChunks(reader, 8, reader.length)
            isBmp -> parseBmpHeaders(reader, 0, reader.length)
            isTiff -> decodeTiff(reader, 0, reader.length)
            else -> parseBoxes(reader, 0, reader.length)
        }
        return BoxNode(type = "root", offset = 0, headerSize = 0, size = reader.length, children = children)
    }
}

private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

private fun isPngMagic(reader: ByteReader): Boolean {
    if (reader.length < 8) return false
    return reader.readBytes(0, 8).contentEquals(PNG_SIGNATURE)
}

private fun isBmpMagic(reader: ByteReader): Boolean {
    if (reader.length < 2) return false
    val bytes = reader.readBytes(0, 2)
    return bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte()
}

private fun isTiffMagic(reader: ByteReader): Boolean {
    if (reader.length < 4) return false
    val bytes = reader.readBytes(0, 4)
    val isLittleEndian = bytes[0] == 'I'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
        bytes[2] == 0x2A.toByte() && bytes[3] == 0x00.toByte()
    val isBigEndian = bytes[0] == 'M'.code.toByte() && bytes[1] == 'M'.code.toByte() &&
        bytes[2] == 0x00.toByte() && bytes[3] == 0x2A.toByte()
    return isLittleEndian || isBigEndian
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew test --tests "com.multiviewer.parser.BmpWalkerTest" --tests "com.multiviewer.parser.ParseFileIntegrationTest"`
Expected: PASS

- [ ] **Step 6: Run the full suite**

Run: `./gradlew test --console=plain`
Expected: BUILD SUCCESSFUL, all tests pass (167 existing + 5 new = 172)

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/BmpWalker.kt \
        app/src/main/kotlin/com/multiviewer/parser/ParseFile.kt \
        app/src/test/kotlin/com/multiviewer/parser/BmpWalkerTest.kt \
        app/src/test/kotlin/com/multiviewer/parser/ParseFileIntegrationTest.kt
git commit -m "feat: add BMP header walker"
```

---

### Task 4: Media Summary integration for PNG and BMP

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt`

**Interfaces:**
- Consumes: `PNG_COLOR_TYPE_NAMES: Map<Int, String>` (from Task 1, `PngWalker.kt`, same package — no import needed). `IHDR`/`BITMAPFILEHEADER`/`BITMAPINFOHEADER` node shapes (from Tasks 1-3) — `IHDR` has `width`/`height`/`color_type` fields; `BITMAPINFOHEADER` has `width`/`height` fields.
- Produces: nothing new — `buildMediaSummary`'s signature is unchanged; this task only extends `buildImageBasicInfo`'s internal logic.

- [ ] **Step 1: Write the failing tests**

Add these three tests to the end of the `MediaSummaryBuilderTest` class in `app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt` (immediately before the class's closing `}`):

```kotlin
    @Test
    fun `a PNG-shaped tree (IHDR as a direct root child) produces Resolution, Format PNG, and Color Space`() {
        val ihdr = BoxNode(
            type = "IHDR", offset = 0, headerSize = 0, size = 0,
            fields = listOf(
                BoxField("width", "1920", 0, 4),
                BoxField("height", "1080", 0, 4),
                BoxField("color_type", "6", 0, 1),
            ),
        )
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(ihdr))
        val file = File.createTempFile("png-summary-test", ".png")
        file.deleteOnExit()
        file.writeBytes(ByteArray(2000))

        val basicInfo = buildMediaSummary(root, file).sections.first { it.title == "Basic Info" }
        assertEquals("1920x1080", basicInfo.fields.first { it.label == "Resolution" }.value)
        assertEquals("PNG", basicInfo.fields.first { it.label == "Format" }.value)
        assertEquals("Truecolor+Alpha", basicInfo.fields.first { it.label == "Color Space" }.value)
    }

    @Test
    fun `a PNG's eXIf chunk populates Camera Info and GPS Location exactly like a TIFF's IFD0`() {
        val gps = BoxNode(
            type = "GPS", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("GPSLatitudeRef", "N", 0, 1)),
        )
        val ifd0 = BoxNode(
            type = "IFD0", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("Make", "PngCam", 0, 6), BoxField("Model", "P900", 0, 4)),
            children = listOf(gps),
        )
        val exifChunk = BoxNode(type = "eXIf", offset = 0, headerSize = 0, size = 0, children = listOf(ifd0))
        val ihdr = BoxNode(
            type = "IHDR", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("width", "640", 0, 4), BoxField("height", "480", 0, 4), BoxField("color_type", "2", 0, 1)),
        )
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(ihdr, exifChunk))

        val summary = buildMediaSummary(root, tempFile())

        val cameraInfo = summary.sections.first { it.title == "Camera Info" }
        assertEquals("PngCam", cameraInfo.fields.first { it.label == "Make" }.value)
        assertEquals("P900", cameraInfo.fields.first { it.label == "Model" }.value)

        val gpsSection = summary.sections.first { it.title == "GPS Location" }
        assertEquals("N", gpsSection.fields.first { it.label == "Latitude Ref" }.value)
    }

    @Test
    fun `a BMP-shaped tree produces Resolution and Format BMP, with no Color Space or Camera Info sections`() {
        val fileHeader = BoxNode(type = "BITMAPFILEHEADER", offset = 0, headerSize = 0, size = 0)
        val infoHeader = BoxNode(
            type = "BITMAPINFOHEADER", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("width", "100", 0, 4), BoxField("height", "-50", 0, 4)),
        )
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(fileHeader, infoHeader))
        val file = File.createTempFile("bmp-summary-test", ".bmp")
        file.deleteOnExit()
        file.writeBytes(ByteArray(500))

        val summary = buildMediaSummary(root, file)

        assertEquals(1, summary.sections.size)
        val basicInfo = summary.sections[0]
        assertEquals("Basic Info", basicInfo.title)
        assertEquals("100x50", basicInfo.fields.first { it.label == "Resolution" }.value)
        assertEquals("BMP", basicInfo.fields.first { it.label == "Format" }.value)
        assertEquals(null, basicInfo.fields.find { it.label == "Color Space" })
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "com.multiviewer.parser.MediaSummaryBuilderTest"`
Expected: The first two new tests FAIL — `buildImageBasicInfo` has no PNG branch yet, so Resolution/Format ("PNG")/Color Space are missing (Camera Info/GPS Location for the second test already pass today, since `findFirst(root) { it.type == "IFD0" }` already finds any `IFD0` regardless of nesting — this is expected, it's the regression-proof that the eXIf reuse needs no Camera/GPS code change). The third test FAILs — no BMP branch yet, so Resolution and Format ("BMP") are missing.

- [ ] **Step 3: Add PNG and BMP branches to `buildImageBasicInfo`**

In `app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt`, add this import alongside the existing `import java.io.File`:

```kotlin
import kotlin.math.abs
```

Replace:

```kotlin
private fun buildImageBasicInfo(root: BoxNode, file: File): SummarySection {
    val fields = mutableListOf<SummaryField>()
    val isJpeg = root.children.any { it.type == "SOI" }
    val isTiff = root.children.any { it.type == "IFD0" }
    val sof = findFirst(root) { it.type.startsWith("SOF") }
    val ispe = findPrimaryItemProperty(root, "ispe") ?: findFirst(root) { it.type == "ispe" }
    val sofOrIspe = sof ?: ispe

    if (sofOrIspe != null) {
        val width = sofOrIspe.fields.find { it.name == "width" || it.name == "image_width" }?.value
        val height = sofOrIspe.fields.find { it.name == "height" || it.name == "image_height" }?.value
        if (width != null && height != null) {
            fields.add(SummaryField("Resolution", "${width}x${height}"))
        }
    } else if (isTiff) {
        val tiffIfd0 = root.children.find { it.type == "IFD0" }
        val width = tiffIfd0?.fields?.find { it.name == "ImageWidth" }?.value
        val height = tiffIfd0?.fields?.find { it.name == "ImageLength" }?.value
        if (width != null && height != null) {
            fields.add(SummaryField("Resolution", "${width}x${height}"))
        }
    }

    fields.add(SummaryField("File Size", formatFileSize(file.length())))

    val format = if (isJpeg) {
        "JPEG"
    } else if (isTiff) {
        "TIFF"
    } else {
        root.children.find { it.type == "ftyp" }?.fields?.find { it.name == "major_brand" }?.value ?: "Unknown"
    }
    fields.add(SummaryField("Format", format))

    val colr = findPrimaryItemProperty(root, "colr") ?: findFirst(root) { it.type == "colr" }
    val colorSpace = if (colr != null) {
        colr.summary ?: "Unknown"
    } else if (sofOrIspe != null && isJpeg) {
        when (sofOrIspe.fields.find { it.name == "num_components" }?.value?.toIntOrNull()) {
            3 -> "Color (YCbCr)"
            1 -> "Grayscale"
            else -> "Unknown"
        }
    } else {
        null
    }
    colorSpace?.let { fields.add(SummaryField("Color Space", it)) }
```

with:

```kotlin
private fun buildImageBasicInfo(root: BoxNode, file: File): SummarySection {
    val fields = mutableListOf<SummaryField>()
    val isJpeg = root.children.any { it.type == "SOI" }
    val isTiff = root.children.any { it.type == "IFD0" }
    val isPng = root.children.any { it.type == "IHDR" }
    val isBmp = root.children.any { it.type == "BITMAPFILEHEADER" }
    val sof = findFirst(root) { it.type.startsWith("SOF") }
    val ispe = findPrimaryItemProperty(root, "ispe") ?: findFirst(root) { it.type == "ispe" }
    val sofOrIspe = sof ?: ispe

    if (sofOrIspe != null) {
        val width = sofOrIspe.fields.find { it.name == "width" || it.name == "image_width" }?.value
        val height = sofOrIspe.fields.find { it.name == "height" || it.name == "image_height" }?.value
        if (width != null && height != null) {
            fields.add(SummaryField("Resolution", "${width}x${height}"))
        }
    } else if (isTiff) {
        val tiffIfd0 = root.children.find { it.type == "IFD0" }
        val width = tiffIfd0?.fields?.find { it.name == "ImageWidth" }?.value
        val height = tiffIfd0?.fields?.find { it.name == "ImageLength" }?.value
        if (width != null && height != null) {
            fields.add(SummaryField("Resolution", "${width}x${height}"))
        }
    } else if (isPng) {
        val ihdr = root.children.find { it.type == "IHDR" }
        val width = ihdr?.fields?.find { it.name == "width" }?.value
        val height = ihdr?.fields?.find { it.name == "height" }?.value
        if (width != null && height != null) {
            fields.add(SummaryField("Resolution", "${width}x${height}"))
        }
    } else if (isBmp) {
        val infoHeader = root.children.find { it.type == "BITMAPINFOHEADER" }
        val width = infoHeader?.fields?.find { it.name == "width" }?.value
        val height = infoHeader?.fields?.find { it.name == "height" }?.value?.toIntOrNull()
        if (width != null && height != null) {
            fields.add(SummaryField("Resolution", "${width}x${abs(height)}"))
        }
    }

    fields.add(SummaryField("File Size", formatFileSize(file.length())))

    val format = if (isJpeg) {
        "JPEG"
    } else if (isTiff) {
        "TIFF"
    } else if (isPng) {
        "PNG"
    } else if (isBmp) {
        "BMP"
    } else {
        root.children.find { it.type == "ftyp" }?.fields?.find { it.name == "major_brand" }?.value ?: "Unknown"
    }
    fields.add(SummaryField("Format", format))

    val colr = findPrimaryItemProperty(root, "colr") ?: findFirst(root) { it.type == "colr" }
    val colorSpace = if (colr != null) {
        colr.summary ?: "Unknown"
    } else if (sofOrIspe != null && isJpeg) {
        when (sofOrIspe.fields.find { it.name == "num_components" }?.value?.toIntOrNull()) {
            3 -> "Color (YCbCr)"
            1 -> "Grayscale"
            else -> "Unknown"
        }
    } else if (isPng) {
        val ihdr = root.children.find { it.type == "IHDR" }
        val colorType = ihdr?.fields?.find { it.name == "color_type" }?.value?.toIntOrNull()
        colorType?.let { PNG_COLOR_TYPE_NAMES[it] }
    } else {
        null
    }
    colorSpace?.let { fields.add(SummaryField("Color Space", it)) }
```

No other line in `buildImageBasicInfo` changes — Capture Date logic below this block is untouched. No other function in `MediaSummaryBuilder.kt` changes — `buildImageSummary`'s Camera Info/GPS Location construction already reads via `findFirst(root) { it.type == "IFD0" }`, which finds a PNG's `eXIf`-nested `IFD0` exactly as it already finds a JPEG/HEIC/TIFF one.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests "com.multiviewer.parser.MediaSummaryBuilderTest"`
Expected: PASS

- [ ] **Step 5: Run the full suite**

Run: `./gradlew test --console=plain`
Expected: BUILD SUCCESSFUL, all tests pass (172 existing + 3 new = 175)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt \
        app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt
git commit -m "feat: populate Media Summary Resolution/Format/Color Space for PNG and BMP"
```

- [ ] **Step 7: Manual verification note**

Launch the app (`./gradlew :app:run`) and open a real `.png` file — confirm Media Summary shows correct Resolution/Format ("PNG")/Color Space/File Size, and Structure Analyser shows `IHDR` (with decoded fields), `pHYs`/`tEXt` if present (decoded), and any other chunks as generic browsable nodes. If the PNG has an embedded `eXIf` chunk (e.g. one exported from a camera or edited with EXIF-preserving tools), confirm Camera Info/GPS Location/Capture Date also populate. Open a real `.bmp` file — confirm Media Summary shows correct Resolution/Format ("BMP")/File Size (no Color Space, no Camera Info/GPS), and Structure Analyser shows `BITMAPFILEHEADER` and `BITMAPINFOHEADER` with decoded fields.
