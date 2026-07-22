# GIF (Static + Animated) Format Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add support for GIF files (static and animated — the same code path handles both) — a new block-stream walker mirroring the existing PNG chunk walker's pattern, plus Media Summary integration.

**Architecture:** GIF is a flat sequence of self-describing blocks (Logical Screen Descriptor, optional Color Tables, Extension blocks, Image Descriptor blocks, a Trailer), decoded by a new `GifWalker.kt` that mirrors `PngWalker.kt`'s "flat walker, decode known blocks, generic node for the rest" pattern. An animated GIF is simply a static GIF's block stream with more than one Image Descriptor — the walker requires no special-casing for animation, it just decodes whatever sequence of blocks is present. GIF's multi-byte fields are little-endian (like BMP), so `GifWalker.kt` uses local little-endian helpers rather than the shared big-endian `ByteReader` methods.

**Tech Stack:** Kotlin 2.0.21, `kotlin.test`.

## Global Constraints

- No LZW decompression or pixel rendering — image data sub-blocks are skipped over (byte-counted to advance past them), never decoded into pixels.
- No Color Table entry decoding — Global/Local Color Tables show only their byte size, not each RGB triplet.
- No Comment Extension or Plain Text Extension text decoding — both show as generic, field-less nodes (with recognizable type names).
- No Color Space field for GIF in Media Summary — GIF is always palette-based, no color-space value to show (same precedent as BMP).
- No frame-rate or total-duration calculation — only Frame Count (a block count) and Loop Count (a plain field from the Netscape Application Extension) are computed.
- No interlaced GIF pixel reconstruction — the `interlace_flag` on an Image Descriptor is a raw field only.
- No non-Netscape Application Extension sub-block decoding — any Application Extension whose identifier isn't exactly `NETSCAPE2.0` shows only its `application_identifier` field.

---

### Task 1: GIF block walker core — detection, Logical Screen Descriptor, Color Tables, Image Descriptor, generic extension fallback

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/GifWalker.kt`
- Create: `app/src/test/kotlin/com/multiviewer/parser/GifWalkerTest.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/parser/ParseFile.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/ParseFileIntegrationTest.kt`

**Interfaces:**
- Consumes: `ByteReader.readUInt8(offset: Long): Int`, `.readBytes(offset: Long, len: Int): ByteArray` (both existing). `BoxNode`/`BoxField` (existing, `BoxNode.kt`).
- Produces: `fun parseGifBlocks(reader: ByteReader, start: Long, end: Long): List<BoxNode>` (new, `GifWalker.kt`) — Task 2 extends this same file's extension dispatch; Task 3 reads its `LogicalScreenDescriptor`/`ImageDescriptor`/`ApplicationExtension` output node shapes. Node types this task establishes: `"LogicalScreenDescriptor"` (fields: `width`, `height`, `global_color_table_flag`, `color_resolution`, `sort_flag`, `global_color_table_size`, `background_color_index`, `pixel_aspect_ratio`), `"GlobalColorTable"`/`"LocalColorTable"` (field: `size`), `"ImageDescriptor"` (fields: `left`, `top`, `width`, `height`, `local_color_table_flag`, `interlace_flag`, `local_color_table_size`; may have a `LocalColorTable` child), `"Trailer"`, `"CommentExtension"`/`"PlainTextExtension"`/`"Extension_0x<HEX>"` (all field-less in this task).

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/multiviewer/parser/GifWalkerTest.kt`:

```kotlin
package com.multiviewer.parser

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun uint16LE(value: Int): ByteArray = byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())

private fun logicalScreenDescriptor(width: Int, height: Int, globalColorTableFlag: Boolean, globalColorTableSize: Int): ByteArray {
    val packed = (if (globalColorTableFlag) 0x80 else 0x00) or (globalColorTableSize and 0x07)
    return uint16LE(width) + uint16LE(height) + byteArrayOf(packed.toByte(), 0x00, 0x00)
}

private fun imageDescriptor(left: Int, top: Int, width: Int, height: Int, imageData: ByteArray): ByteArray =
    byteArrayOf(0x2C) + uint16LE(left) + uint16LE(top) + uint16LE(width) + uint16LE(height) +
        byteArrayOf(0x00) + byteArrayOf(0x02) + imageData

private fun subBlock(data: ByteArray): ByteArray = byteArrayOf(data.size.toByte()) + data

private val SUB_BLOCK_TERMINATOR = byteArrayOf(0x00)

private fun readerOver(bytes: ByteArray, namePrefix: String): ByteReader {
    val tmp = File.createTempFile(namePrefix, ".bin")
    tmp.deleteOnExit()
    tmp.writeBytes(bytes)
    return ByteReader.open(tmp)
}

class GifWalkerTest {
    @Test
    fun `decodes Logical Screen Descriptor fields and a WxH summary`() {
        val bytes = logicalScreenDescriptor(width = 320, height = 240, globalColorTableFlag = false, globalColorTableSize = 0) +
            byteArrayOf(0x3B)
        readerOver(bytes, "gif-walker-lsd").use { reader ->
            val nodes = parseGifBlocks(reader, 0, bytes.size.toLong())
            val lsd = nodes[0]
            assertEquals("LogicalScreenDescriptor", lsd.type)
            assertEquals("320", lsd.fields.first { it.name == "width" }.value)
            assertEquals("240", lsd.fields.first { it.name == "height" }.value)
            assertEquals("0", lsd.fields.first { it.name == "global_color_table_flag" }.value)
            assertEquals("320x240", lsd.summary)
        }
    }

    @Test
    fun `decodes a Global Color Table when the flag is set`() {
        val bytes = logicalScreenDescriptor(width = 4, height = 4, globalColorTableFlag = true, globalColorTableSize = 0) +
            ByteArray(6) + // 3 * 2^(0+1) = 6 bytes
            byteArrayOf(0x3B)
        readerOver(bytes, "gif-walker-gct").use { reader ->
            val nodes = parseGifBlocks(reader, 0, bytes.size.toLong())
            assertEquals("GlobalColorTable", nodes[1].type)
            assertEquals("6", nodes[1].fields.first { it.name == "size" }.value)
        }
    }

    @Test
    fun `omits the Global Color Table node when the flag is not set`() {
        val bytes = logicalScreenDescriptor(width = 4, height = 4, globalColorTableFlag = false, globalColorTableSize = 0) +
            byteArrayOf(0x3B)
        readerOver(bytes, "gif-walker-no-gct").use { reader ->
            val nodes = parseGifBlocks(reader, 0, bytes.size.toLong())
            assertEquals(listOf("LogicalScreenDescriptor", "Trailer"), nodes.map { it.type })
        }
    }

    @Test
    fun `decodes an Image Descriptor with no Local Color Table`() {
        val imageData = subBlock(byteArrayOf(0x00)) + SUB_BLOCK_TERMINATOR
        val bytes = logicalScreenDescriptor(width = 10, height = 10, globalColorTableFlag = false, globalColorTableSize = 0) +
            imageDescriptor(left = 0, top = 0, width = 10, height = 10, imageData = imageData) +
            byteArrayOf(0x3B)
        readerOver(bytes, "gif-walker-image-descriptor").use { reader ->
            val nodes = parseGifBlocks(reader, 0, bytes.size.toLong())
            val imageDescriptorNode = nodes.first { it.type == "ImageDescriptor" }
            assertEquals("10", imageDescriptorNode.fields.first { it.name == "width" }.value)
            assertEquals("10", imageDescriptorNode.fields.first { it.name == "height" }.value)
            assertEquals("0", imageDescriptorNode.fields.first { it.name == "local_color_table_flag" }.value)
            assertEquals("10x10 at (0,0)", imageDescriptorNode.summary)
            assertTrue(imageDescriptorNode.children.isEmpty())
        }
    }

    @Test
    fun `decodes an Image Descriptor with a Local Color Table child`() {
        val imageData = subBlock(byteArrayOf(0x00)) + SUB_BLOCK_TERMINATOR
        val localColorTableBytes = ByteArray(6) // 3 * 2^(0+1)
        val packed = 0x80 // local color table flag set, size=0
        val imageDescriptorBytes = byteArrayOf(0x2C) + uint16LE(0) + uint16LE(0) + uint16LE(8) + uint16LE(8) +
            byteArrayOf(packed.toByte()) + localColorTableBytes + byteArrayOf(0x02) + imageData
        val bytes = logicalScreenDescriptor(width = 8, height = 8, globalColorTableFlag = false, globalColorTableSize = 0) +
            imageDescriptorBytes +
            byteArrayOf(0x3B)
        readerOver(bytes, "gif-walker-local-color-table").use { reader ->
            val nodes = parseGifBlocks(reader, 0, bytes.size.toLong())
            val imageDescriptorNode = nodes.first { it.type == "ImageDescriptor" }
            assertEquals(1, imageDescriptorNode.children.size)
            val localColorTable = imageDescriptorNode.children[0]
            assertEquals("LocalColorTable", localColorTable.type)
            assertEquals("6", localColorTable.fields.first { it.name == "size" }.value)
        }
    }

    @Test
    fun `multiple Image Descriptors (an animated GIF) are all decoded, proving animation needs no special-casing`() {
        val imageData = subBlock(byteArrayOf(0x00)) + SUB_BLOCK_TERMINATOR
        val frame = imageDescriptor(left = 0, top = 0, width = 4, height = 4, imageData = imageData)
        val bytes = logicalScreenDescriptor(width = 4, height = 4, globalColorTableFlag = false, globalColorTableSize = 0) +
            frame + frame + frame +
            byteArrayOf(0x3B)
        readerOver(bytes, "gif-walker-animated").use { reader ->
            val nodes = parseGifBlocks(reader, 0, bytes.size.toLong())
            assertEquals(3, nodes.count { it.type == "ImageDescriptor" })
        }
    }

    @Test
    fun `a Comment Extension shows as a generic, field-less node`() {
        val commentData = subBlock("hello".toByteArray(Charsets.US_ASCII)) + SUB_BLOCK_TERMINATOR
        val bytes = logicalScreenDescriptor(width = 4, height = 4, globalColorTableFlag = false, globalColorTableSize = 0) +
            byteArrayOf(0x21, 0xFE.toByte()) + commentData +
            byteArrayOf(0x3B)
        readerOver(bytes, "gif-walker-comment").use { reader ->
            val nodes = parseGifBlocks(reader, 0, bytes.size.toLong())
            val commentNode = nodes.first { it.type == "CommentExtension" }
            assertTrue(commentNode.fields.isEmpty())
        }
    }

    @Test
    fun `an unrecognized extension label shows as a generic, field-less node`() {
        val extensionData = subBlock(byteArrayOf(0x01, 0x02)) + SUB_BLOCK_TERMINATOR
        val bytes = logicalScreenDescriptor(width = 4, height = 4, globalColorTableFlag = false, globalColorTableSize = 0) +
            byteArrayOf(0x21, 0x99.toByte()) + extensionData +
            byteArrayOf(0x3B)
        readerOver(bytes, "gif-walker-unknown-extension").use { reader ->
            val nodes = parseGifBlocks(reader, 0, bytes.size.toLong())
            val extensionNode = nodes.first { it.type.startsWith("Extension_") }
            assertEquals("Extension_0x99", extensionNode.type)
            assertTrue(extensionNode.fields.isEmpty())
        }
    }

    @Test
    fun `the Trailer stops the block loop`() {
        val bytes = logicalScreenDescriptor(width = 4, height = 4, globalColorTableFlag = false, globalColorTableSize = 0) +
            byteArrayOf(0x3B, 0x2C) // garbage after the trailer must be ignored
        readerOver(bytes, "gif-walker-trailer").use { reader ->
            val nodes = parseGifBlocks(reader, 0, bytes.size.toLong())
            assertEquals(listOf("LogicalScreenDescriptor", "Trailer"), nodes.map { it.type })
        }
    }

    @Test
    fun `a file too short for a Logical Screen Descriptor produces a warning node`() {
        val bytes = byteArrayOf(0x04, 0x00, 0x00) // only 3 of the required 7 bytes
        readerOver(bytes, "gif-walker-truncated").use { reader ->
            val nodes = parseGifBlocks(reader, 0, bytes.size.toLong())
            assertEquals(1, nodes.size)
            assertTrue(nodes[0].warnings.isNotEmpty())
        }
    }
}
```

Add this test to the end of the `ParseFileIntegrationTest` class in `app/src/test/kotlin/com/multiviewer/parser/ParseFileIntegrationTest.kt` (immediately before the class's closing `}`; note `uint16LE` already exists at the bottom of this file from the BMP task — reuse it, don't redeclare it):

```kotlin
    @Test
    fun `parses a GIF file via the block walker, not the ISOBMFF or TIFF path`() {
        val header = "GIF89a".toByteArray(Charsets.US_ASCII)
        val logicalScreenDescriptor = uint16LE(4) + uint16LE(4) + byteArrayOf(0x00, 0x00, 0x00)
        val trailer = byteArrayOf(0x3B)
        val bytes = header + logicalScreenDescriptor + trailer
        val tmp = File.createTempFile("multiviewer-gif", ".gif")
        tmp.deleteOnExit()
        tmp.writeBytes(bytes)

        val root = parseFile(tmp)

        assertEquals(listOf("LogicalScreenDescriptor", "Trailer"), root.children.map { it.type })
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew test --tests "com.multiviewer.parser.GifWalkerTest" --tests "com.multiviewer.parser.ParseFileIntegrationTest" --console=plain`
Expected: `GifWalkerTest` FAILs to compile (`parseGifBlocks` doesn't exist yet). `ParseFileIntegrationTest`'s new test also fails to compile for the same reason.

- [ ] **Step 3: Implement the GIF block walker**

Create `app/src/main/kotlin/com/multiviewer/parser/GifWalker.kt`:

```kotlin
package com.multiviewer.parser

private const val EXTENSION_INTRODUCER = 0x21
private const val IMAGE_DESCRIPTOR_INTRODUCER = 0x2C
private const val TRAILER = 0x3B
private const val COMMENT_LABEL = 0xFE
private const val PLAIN_TEXT_LABEL = 0x01

fun parseGifBlocks(reader: ByteReader, start: Long, end: Long): List<BoxNode> {
    val result = mutableListOf<BoxNode>()
    if (start + 7 > end) {
        result.add(BoxNode("?", start, 0, end - start, warnings = listOf("Trailing ${end - start} byte(s): too short for a Logical Screen Descriptor")))
        return result
    }

    val packed = reader.readUInt8(start + 4)
    val globalColorTableFlag = (packed shr 7) and 0x01
    result.add(decodeLogicalScreenDescriptor(reader, start))
    var pos = start + 7

    if (globalColorTableFlag == 1) {
        val globalColorTableSize = packed and 0x07
        val tableBytes = 3L * (1L shl (globalColorTableSize + 1))
        if (pos + tableBytes > end) {
            result.add(BoxNode("GlobalColorTable", pos, 0, end - pos, warnings = listOf("Global Color Table declares $tableBytes byte(s) but only ${end - pos} remain")))
            return result
        }
        result.add(BoxNode("GlobalColorTable", pos, 0, tableBytes, fields = listOf(BoxField("size", tableBytes.toString(), pos, tableBytes))))
        pos += tableBytes
    }

    while (pos < end) {
        val introducer = reader.readUInt8(pos)
        if (introducer == TRAILER) {
            result.add(BoxNode("Trailer", pos, 1, 1))
            return result
        }
        if (introducer == EXTENSION_INTRODUCER) {
            if (pos + 2 > end) {
                result.add(BoxNode("?", pos, 0, end - pos, warnings = listOf("Trailing ${end - pos} byte(s): too short for an extension label")))
                return result
            }
            val label = reader.readUInt8(pos + 1)
            val decoded = decodeExtension(reader, label, pos, end)
            if (decoded == null) {
                result.add(BoxNode("?", pos, 0, end - pos, warnings = listOf("Malformed extension at offset $pos")))
                return result
            }
            result.add(decoded.first)
            pos = decoded.second
        } else if (introducer == IMAGE_DESCRIPTOR_INTRODUCER) {
            val decoded = decodeImageDescriptor(reader, pos, end)
            if (decoded == null) {
                result.add(BoxNode("?", pos, 0, end - pos, warnings = listOf("Malformed image descriptor at offset $pos")))
                return result
            }
            result.add(decoded.first)
            pos = decoded.second
        } else {
            result.add(BoxNode("?", pos, 0, end - pos, warnings = listOf("Unexpected byte 0x${introducer.toString(16).padStart(2, '0')} where a block introducer was expected")))
            return result
        }
    }
    return result
}

private fun decodeExtension(reader: ByteReader, label: Int, offset: Long, end: Long): Pair<BoxNode, Long>? =
    when (label) {
        COMMENT_LABEL -> decodeGenericSubBlockExtension(reader, "CommentExtension", offset, end)
        PLAIN_TEXT_LABEL -> decodeGenericSubBlockExtension(reader, "PlainTextExtension", offset, end)
        else -> decodeGenericSubBlockExtension(reader, "Extension_0x${label.toString(16).padStart(2, '0').uppercase()}", offset, end)
    }

private fun decodeGenericSubBlockExtension(reader: ByteReader, type: String, offset: Long, end: Long): Pair<BoxNode, Long>? {
    val (_, nextPos) = readSubBlocks(reader, offset + 2, end) ?: return null
    return BoxNode(type = type, offset = offset, headerSize = 2, size = nextPos - offset) to nextPos
}

private fun decodeLogicalScreenDescriptor(reader: ByteReader, offset: Long): BoxNode {
    val width = readUInt16LE(reader, offset)
    val height = readUInt16LE(reader, offset + 2)
    val packed = reader.readUInt8(offset + 4)
    val globalColorTableFlag = (packed shr 7) and 0x01
    val colorResolution = (packed shr 4) and 0x07
    val sortFlag = (packed shr 3) and 0x01
    val globalColorTableSize = packed and 0x07
    val backgroundColorIndex = reader.readUInt8(offset + 5)
    val pixelAspectRatio = reader.readUInt8(offset + 6)
    return BoxNode(
        type = "LogicalScreenDescriptor", offset = offset, headerSize = 0, size = 7,
        fields = listOf(
            BoxField("width", width.toString(), offset, 2),
            BoxField("height", height.toString(), offset + 2, 2),
            BoxField("global_color_table_flag", globalColorTableFlag.toString(), offset + 4, 1),
            BoxField("color_resolution", colorResolution.toString(), offset + 4, 1),
            BoxField("sort_flag", sortFlag.toString(), offset + 4, 1),
            BoxField("global_color_table_size", globalColorTableSize.toString(), offset + 4, 1),
            BoxField("background_color_index", backgroundColorIndex.toString(), offset + 5, 1),
            BoxField("pixel_aspect_ratio", pixelAspectRatio.toString(), offset + 6, 1),
        ),
        summary = "${width}x${height}",
    )
}

private fun decodeImageDescriptor(reader: ByteReader, offset: Long, end: Long): Pair<BoxNode, Long>? {
    val fixedEnd = offset + 10
    if (fixedEnd > end) return null
    val left = readUInt16LE(reader, offset + 1)
    val top = readUInt16LE(reader, offset + 3)
    val width = readUInt16LE(reader, offset + 5)
    val height = readUInt16LE(reader, offset + 7)
    val packed = reader.readUInt8(offset + 9)
    val localColorTableFlag = (packed shr 7) and 0x01
    val interlaceFlag = (packed shr 6) and 0x01
    val localColorTableSize = packed and 0x07

    var pos = fixedEnd
    val children = mutableListOf<BoxNode>()
    if (localColorTableFlag == 1) {
        val tableBytes = 3L * (1L shl (localColorTableSize + 1))
        if (pos + tableBytes > end) return null
        children.add(BoxNode("LocalColorTable", pos, 0, tableBytes, fields = listOf(BoxField("size", tableBytes.toString(), pos, tableBytes))))
        pos += tableBytes
    }

    if (pos + 1 > end) return null
    pos += 1 // LZW minimum code size

    val (_, nextPos) = readSubBlocks(reader, pos, end) ?: return null

    return BoxNode(
        type = "ImageDescriptor", offset = offset, headerSize = 10, size = nextPos - offset,
        fields = listOf(
            BoxField("left", left.toString(), offset + 1, 2),
            BoxField("top", top.toString(), offset + 3, 2),
            BoxField("width", width.toString(), offset + 5, 2),
            BoxField("height", height.toString(), offset + 7, 2),
            BoxField("local_color_table_flag", localColorTableFlag.toString(), offset + 9, 1),
            BoxField("interlace_flag", interlaceFlag.toString(), offset + 9, 1),
            BoxField("local_color_table_size", localColorTableSize.toString(), offset + 9, 1),
        ),
        children = children,
        summary = "${width}x${height} at ($left,$top)",
    ) to nextPos
}

private fun readSubBlocks(reader: ByteReader, pos: Long, end: Long): Pair<List<ByteArray>, Long>? {
    val blocks = mutableListOf<ByteArray>()
    var p = pos
    while (true) {
        if (p >= end) return null
        val size = reader.readUInt8(p)
        p += 1
        if (size == 0) break
        if (p + size > end) return null
        blocks.add(reader.readBytes(p, size))
        p += size
    }
    return blocks to p
}

private fun readUInt16LE(reader: ByteReader, offset: Long): Int {
    val b = reader.readBytes(offset, 2)
    return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
}
```

Every GIF block's variable-length data (Application/Comment/Plain-Text Extension data, and an Image Descriptor's compressed pixel data) follows the exact same shape in the GIF spec: a sequence of `size-byte + that-many-data-bytes` sub-blocks, terminated by a zero-size sub-block. `readSubBlocks` is the one shared implementation of that shape, reused by every decoder in this file (and, in Task 2, by the Graphic Control and Application Extension decoders too).

- [ ] **Step 4: Wire GIF detection into `parseFile`**

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
        val isGif = !isJpeg && !isPng && !isBmp && isGifMagic(reader)
        val isTiff = !isJpeg && !isPng && !isBmp && !isGif && isTiffMagic(reader)
        val children = when {
            isJpeg -> parseJpegSegments(reader, 0, reader.length)
            isPng -> parsePngChunks(reader, 8, reader.length)
            isBmp -> parseBmpHeaders(reader, 0, reader.length)
            isGif -> parseGifBlocks(reader, 6, reader.length)
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

private fun isGifMagic(reader: ByteReader): Boolean {
    if (reader.length < 6) return false
    val text = String(reader.readBytes(0, 6), Charsets.US_ASCII)
    return text == "GIF87a" || text == "GIF89a"
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

(`parseGifBlocks` is called with `start = 6` to skip past the 6-byte `GIF87a`/`GIF89a` header, mirroring how PNG's chunk stream starts at offset 8, past its own 8-byte signature.)

- [ ] **Step 5: Run the tests to verify they pass**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew test --tests "com.multiviewer.parser.GifWalkerTest" --tests "com.multiviewer.parser.ParseFileIntegrationTest" --console=plain`
Expected: PASS

- [ ] **Step 6: Run the full suite**

Run: `./gradlew test --console=plain`
Expected: BUILD SUCCESSFUL, all tests pass (176 existing + 11 new = 187)

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/GifWalker.kt \
        app/src/main/kotlin/com/multiviewer/parser/ParseFile.kt \
        app/src/test/kotlin/com/multiviewer/parser/GifWalkerTest.kt \
        app/src/test/kotlin/com/multiviewer/parser/ParseFileIntegrationTest.kt
git commit -m "feat: add GIF block walker with Logical Screen Descriptor and Image Descriptor decoding"
```

---

### Task 2: Graphic Control Extension and Application Extension (Netscape loop count) decoding

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/GifWalker.kt`
- Modify: `app/src/test/kotlin/com/multiviewer/parser/GifWalkerTest.kt`

**Interfaces:**
- Consumes: `readSubBlocks(reader: ByteReader, pos: Long, end: Long): Pair<List<ByteArray>, Long>?` (from Task 1, same file) — the same shared sub-block reader used for the generic fallback is reused here to read each extension's fixed-shape data.
- Produces: nothing new beyond Task 1's `parseGifBlocks` — this task only adds two more cases (`GraphicControlExtension`, `ApplicationExtension`) that `decodeExtension`'s `when` can decode instead of falling through to the generic fallback. Node types added: `"GraphicControlExtension"` (fields: `disposal_method`, `transparent_color_flag`, `delay_time`, `transparent_color_index`), `"ApplicationExtension"` (field: `application_identifier`; plus `loop_count` when the identifier is exactly `"NETSCAPE2.0"`).

- [ ] **Step 1: Write the failing tests**

Add these three tests to the end of the `GifWalkerTest` class in `app/src/test/kotlin/com/multiviewer/parser/GifWalkerTest.kt` (immediately before the class's closing `}`):

```kotlin
    @Test
    fun `decodes Graphic Control Extension fields`() {
        // packed = 0x09 = 0b00001001 -> disposal_method (bits 4-2) = 2, transparent_color_flag (bit 0) = 1
        val gceData = subBlock(byteArrayOf(0x09, 0x0A, 0x00, 0x05)) + SUB_BLOCK_TERMINATOR
        val bytes = logicalScreenDescriptor(width = 4, height = 4, globalColorTableFlag = false, globalColorTableSize = 0) +
            byteArrayOf(0x21, 0xF9.toByte()) + gceData +
            byteArrayOf(0x3B)
        readerOver(bytes, "gif-walker-gce").use { reader ->
            val nodes = parseGifBlocks(reader, 0, bytes.size.toLong())
            val gce = nodes.first { it.type == "GraphicControlExtension" }
            assertEquals("2", gce.fields.first { it.name == "disposal_method" }.value)
            assertEquals("1", gce.fields.first { it.name == "transparent_color_flag" }.value)
            assertEquals("10", gce.fields.first { it.name == "delay_time" }.value)
            assertEquals("5", gce.fields.first { it.name == "transparent_color_index" }.value)
        }
    }

    @Test
    fun `decodes an Application Extension's Netscape loop count`() {
        val appHeader = subBlock("NETSCAPE2.0".toByteArray(Charsets.US_ASCII)) // 11 bytes
        val loopSubBlock = subBlock(byteArrayOf(0x01, 0x00, 0x00)) // sub-block ID 1, loop count = 0 (infinite)
        val appData = appHeader + loopSubBlock + SUB_BLOCK_TERMINATOR
        val bytes = logicalScreenDescriptor(width = 4, height = 4, globalColorTableFlag = false, globalColorTableSize = 0) +
            byteArrayOf(0x21, 0xFF.toByte()) + appData +
            byteArrayOf(0x3B)
        readerOver(bytes, "gif-walker-netscape").use { reader ->
            val nodes = parseGifBlocks(reader, 0, bytes.size.toLong())
            val app = nodes.first { it.type == "ApplicationExtension" }
            assertEquals("NETSCAPE2.0", app.fields.first { it.name == "application_identifier" }.value)
            assertEquals("0", app.fields.first { it.name == "loop_count" }.value)
        }
    }

    @Test
    fun `an Application Extension with a non-Netscape identifier has no loop_count field`() {
        val appHeader = subBlock("XYZAPP1.0X0".toByteArray(Charsets.US_ASCII)) // 11 bytes, arbitrary
        val appData = appHeader + SUB_BLOCK_TERMINATOR
        val bytes = logicalScreenDescriptor(width = 4, height = 4, globalColorTableFlag = false, globalColorTableSize = 0) +
            byteArrayOf(0x21, 0xFF.toByte()) + appData +
            byteArrayOf(0x3B)
        readerOver(bytes, "gif-walker-non-netscape").use { reader ->
            val nodes = parseGifBlocks(reader, 0, bytes.size.toLong())
            val app = nodes.first { it.type == "ApplicationExtension" }
            assertEquals("XYZAPP1.0X0", app.fields.first { it.name == "application_identifier" }.value)
            assertEquals(null, app.fields.find { it.name == "loop_count" })
        }
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew test --tests "com.multiviewer.parser.GifWalkerTest" --console=plain`
Expected: All three new tests FAIL — `0xF9` and `0xFF` labels currently fall into `decodeExtension`'s generic `else` branch (`"Extension_0xF9"`/`"Extension_0xFF"`, no fields), not `"GraphicControlExtension"`/`"ApplicationExtension"` with decoded fields.

- [ ] **Step 3: Add the two extension decoders**

In `app/src/main/kotlin/com/multiviewer/parser/GifWalker.kt`, add these two constants alongside the existing `COMMENT_LABEL`/`PLAIN_TEXT_LABEL`:

```kotlin
private const val GRAPHIC_CONTROL_LABEL = 0xF9
private const val APPLICATION_LABEL = 0xFF
```

Replace:

```kotlin
private fun decodeExtension(reader: ByteReader, label: Int, offset: Long, end: Long): Pair<BoxNode, Long>? =
    when (label) {
        COMMENT_LABEL -> decodeGenericSubBlockExtension(reader, "CommentExtension", offset, end)
        PLAIN_TEXT_LABEL -> decodeGenericSubBlockExtension(reader, "PlainTextExtension", offset, end)
        else -> decodeGenericSubBlockExtension(reader, "Extension_0x${label.toString(16).padStart(2, '0').uppercase()}", offset, end)
    }
```

with:

```kotlin
private fun decodeExtension(reader: ByteReader, label: Int, offset: Long, end: Long): Pair<BoxNode, Long>? =
    when (label) {
        GRAPHIC_CONTROL_LABEL -> decodeGraphicControlExtension(reader, offset, end)
        APPLICATION_LABEL -> decodeApplicationExtension(reader, offset, end)
        COMMENT_LABEL -> decodeGenericSubBlockExtension(reader, "CommentExtension", offset, end)
        PLAIN_TEXT_LABEL -> decodeGenericSubBlockExtension(reader, "PlainTextExtension", offset, end)
        else -> decodeGenericSubBlockExtension(reader, "Extension_0x${label.toString(16).padStart(2, '0').uppercase()}", offset, end)
    }

private fun decodeGraphicControlExtension(reader: ByteReader, offset: Long, end: Long): Pair<BoxNode, Long>? {
    val (blocks, nextPos) = readSubBlocks(reader, offset + 2, end) ?: return null
    val data = blocks.firstOrNull()
    if (data == null || data.size < 4) {
        return BoxNode("GraphicControlExtension", offset, 2, nextPos - offset, warnings = listOf("Graphic Control Extension missing its data sub-block")) to nextPos
    }
    val packed = data[0].toInt() and 0xFF
    val disposalMethod = (packed shr 2) and 0x07
    val transparentColorFlag = packed and 0x01
    val delayTime = (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8)
    val transparentColorIndex = data[3].toInt() and 0xFF
    return BoxNode(
        type = "GraphicControlExtension", offset = offset, headerSize = 2, size = nextPos - offset,
        fields = listOf(
            BoxField("disposal_method", disposalMethod.toString(), offset + 3, 1),
            BoxField("transparent_color_flag", transparentColorFlag.toString(), offset + 3, 1),
            BoxField("delay_time", delayTime.toString(), offset + 4, 2),
            BoxField("transparent_color_index", transparentColorIndex.toString(), offset + 6, 1),
        ),
    ) to nextPos
}

private fun decodeApplicationExtension(reader: ByteReader, offset: Long, end: Long): Pair<BoxNode, Long>? {
    val (blocks, nextPos) = readSubBlocks(reader, offset + 2, end) ?: return null
    val header = blocks.firstOrNull()
    if (header == null || header.size < 11) {
        return BoxNode("ApplicationExtension", offset, 2, nextPos - offset, warnings = listOf("Application Extension missing its identifier sub-block")) to nextPos
    }
    val identifier = String(header, 0, 11, Charsets.US_ASCII)
    val fields = mutableListOf(BoxField("application_identifier", identifier, offset + 3, 11))
    if (identifier == "NETSCAPE2.0") {
        val loopBlock = blocks.getOrNull(1)
        if (loopBlock != null && loopBlock.size >= 3) {
            val loopCount = (loopBlock[1].toInt() and 0xFF) or ((loopBlock[2].toInt() and 0xFF) shl 8)
            // offset+3..+13: 11-byte identifier; offset+14: loop sub-block's size byte; offset+15: sub-block ID byte; offset+16: loop count value
            fields.add(BoxField("loop_count", loopCount.toString(), offset + 16, 2))
        }
    }
    return BoxNode(type = "ApplicationExtension", offset = offset, headerSize = 2, size = nextPos - offset, fields = fields) to nextPos
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew test --tests "com.multiviewer.parser.GifWalkerTest" --console=plain`
Expected: PASS

- [ ] **Step 5: Run the full suite**

Run: `./gradlew test --console=plain`
Expected: BUILD SUCCESSFUL, all tests pass (187 existing + 3 new = 190)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/GifWalker.kt \
        app/src/test/kotlin/com/multiviewer/parser/GifWalkerTest.kt
git commit -m "feat: decode Graphic Control Extension and Application Extension (Netscape loop count)"
```

---

### Task 3: Media Summary integration for GIF

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt`

**Interfaces:**
- Consumes: `LogicalScreenDescriptor`/`ImageDescriptor`/`ApplicationExtension` node shapes (from Tasks 1-2) — `LogicalScreenDescriptor` has `width`/`height` fields; `ApplicationExtension` has an optional `loop_count` field.
- Produces: nothing new — `buildMediaSummary`'s signature is unchanged; this task only extends `buildImageGeneral`/`buildImageDetail`'s internal logic.

- [ ] **Step 1: Write the failing tests**

Add these three tests to the end of the `MediaSummaryBuilderTest` class in `app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt` (immediately before the class's closing `}`):

```kotlin
    @Test
    fun `a GIF-shaped tree (LogicalScreenDescriptor as a direct root child) produces Width, Height, Format GIF, and Frame Count`() {
        val lsd = BoxNode(
            type = "LogicalScreenDescriptor", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("width", "320", 0, 2), BoxField("height", "240", 0, 2)),
        )
        val imageDescriptor = BoxNode(type = "ImageDescriptor", offset = 0, headerSize = 0, size = 0)
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(lsd, imageDescriptor))
        val file = File.createTempFile("gif-summary-test", ".gif")
        file.deleteOnExit()
        file.writeBytes(ByteArray(1000))

        val summary = buildMediaSummary(root, file)

        val general = summary.sections.first { it.title == "General" }
        assertEquals("GIF", general.fields.first { it.label == "Format" }.value)

        val image = summary.sections.first { it.title == "Image" }
        assertEquals("320", image.fields.first { it.label == "Width" }.value)
        assertEquals("240", image.fields.first { it.label == "Height" }.value)
        assertEquals("1", image.fields.first { it.label == "Frame Count" }.value)
        assertEquals(null, image.fields.find { it.label == "Loop Count" })
    }

    @Test
    fun `an animated GIF-shaped tree with a Netscape loop_count of 0 shows Frame Count and an Infinite Loop Count`() {
        val lsd = BoxNode(
            type = "LogicalScreenDescriptor", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("width", "100", 0, 2), BoxField("height", "80", 0, 2)),
        )
        val appExtension = BoxNode(
            type = "ApplicationExtension", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("application_identifier", "NETSCAPE2.0", 0, 11), BoxField("loop_count", "0", 0, 2)),
        )
        val frames = List(3) { BoxNode(type = "ImageDescriptor", offset = 0, headerSize = 0, size = 0) }
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(lsd, appExtension) + frames)

        val summary = buildMediaSummary(root, tempFile())

        val image = summary.sections.first { it.title == "Image" }
        assertEquals("3", image.fields.first { it.label == "Frame Count" }.value)
        assertEquals("Infinite", image.fields.first { it.label == "Loop Count" }.value)
    }

    @Test
    fun `a finite Netscape loop_count shows as its raw number, not Infinite`() {
        val lsd = BoxNode(
            type = "LogicalScreenDescriptor", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("width", "100", 0, 2), BoxField("height", "80", 0, 2)),
        )
        val appExtension = BoxNode(
            type = "ApplicationExtension", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("application_identifier", "NETSCAPE2.0", 0, 11), BoxField("loop_count", "5", 0, 2)),
        )
        val frames = List(2) { BoxNode(type = "ImageDescriptor", offset = 0, headerSize = 0, size = 0) }
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(lsd, appExtension) + frames)

        val summary = buildMediaSummary(root, tempFile())

        val image = summary.sections.first { it.title == "Image" }
        assertEquals("5", image.fields.first { it.label == "Loop Count" }.value)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew test --tests "com.multiviewer.parser.MediaSummaryBuilderTest" --console=plain`
Expected: All three new tests FAIL — `buildImageGeneral`/`buildImageDetail` have no GIF branch yet, so Format ("GIF"), Width/Height, Frame Count, and Loop Count are all missing.

- [ ] **Step 3: Add the GIF branch to `buildImageGeneral`**

In `app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt`, replace:

```kotlin
private fun buildImageGeneral(root: BoxNode, file: File): SummarySection {
    val fields = mutableListOf<SummaryField>()
    val isJpeg = root.children.any { it.type == "SOI" }
    val isTiff = root.children.any { it.type == "IFD0" }
    val isPng = root.children.any { it.type == "IHDR" }
    val isBmp = root.children.any { it.type == "BITMAPFILEHEADER" }

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
    fields.add(SummaryField("File Size", formatFileSize(file.length())))

    return SummarySection("General", fields)
}
```

with:

```kotlin
private fun buildImageGeneral(root: BoxNode, file: File): SummarySection {
    val fields = mutableListOf<SummaryField>()
    val isJpeg = root.children.any { it.type == "SOI" }
    val isTiff = root.children.any { it.type == "IFD0" }
    val isPng = root.children.any { it.type == "IHDR" }
    val isBmp = root.children.any { it.type == "BITMAPFILEHEADER" }
    val isGif = root.children.any { it.type == "LogicalScreenDescriptor" }

    val format = if (isJpeg) {
        "JPEG"
    } else if (isTiff) {
        "TIFF"
    } else if (isPng) {
        "PNG"
    } else if (isBmp) {
        "BMP"
    } else if (isGif) {
        "GIF"
    } else {
        root.children.find { it.type == "ftyp" }?.fields?.find { it.name == "major_brand" }?.value ?: "Unknown"
    }
    fields.add(SummaryField("Format", format))
    fields.add(SummaryField("File Size", formatFileSize(file.length())))

    return SummarySection("General", fields)
}
```

- [ ] **Step 4: Add the GIF branch, Frame Count, and Loop Count to `buildImageDetail`**

In `app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt`, replace:

```kotlin
private fun buildImageDetail(root: BoxNode): SummarySection? {
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
            fields.add(SummaryField("Width", width))
            fields.add(SummaryField("Height", height))
        }
    } else if (isTiff) {
        val tiffIfd0 = root.children.find { it.type == "IFD0" }
        val width = tiffIfd0?.fields?.find { it.name == "ImageWidth" }?.value
        val height = tiffIfd0?.fields?.find { it.name == "ImageLength" }?.value
        if (width != null && height != null) {
            fields.add(SummaryField("Width", width))
            fields.add(SummaryField("Height", height))
        }
    } else if (isPng) {
        val ihdr = root.children.find { it.type == "IHDR" }
        val width = ihdr?.fields?.find { it.name == "width" }?.value
        val height = ihdr?.fields?.find { it.name == "height" }?.value
        if (width != null && height != null) {
            fields.add(SummaryField("Width", width))
            fields.add(SummaryField("Height", height))
        }
    } else if (isBmp) {
        val infoHeader = root.children.find { it.type == "BITMAPINFOHEADER" }
        val width = infoHeader?.fields?.find { it.name == "width" }?.value
        val height = infoHeader?.fields?.find { it.name == "height" }?.value?.toIntOrNull()
        if (width != null && height != null) {
            fields.add(SummaryField("Width", width))
            fields.add(SummaryField("Height", abs(height).toString()))
        }
    }

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

    val ifd0 = findFirst(root) { it.type == "IFD0" }
    val exif = ifd0?.children?.find { it.type == "Exif" }
    val captureDate = exif?.fields?.find { it.name == "DateTimeOriginal" }?.value
        ?: ifd0?.fields?.find { it.name == "DateTime" }?.value
    captureDate?.let { fields.add(SummaryField("Capture Date", it)) }

    return if (fields.isNotEmpty()) SummarySection("Image", fields) else null
}
```

with:

```kotlin
private fun buildImageDetail(root: BoxNode): SummarySection? {
    val fields = mutableListOf<SummaryField>()
    val isJpeg = root.children.any { it.type == "SOI" }
    val isTiff = root.children.any { it.type == "IFD0" }
    val isPng = root.children.any { it.type == "IHDR" }
    val isBmp = root.children.any { it.type == "BITMAPFILEHEADER" }
    val isGif = root.children.any { it.type == "LogicalScreenDescriptor" }
    val sof = findFirst(root) { it.type.startsWith("SOF") }
    val ispe = findPrimaryItemProperty(root, "ispe") ?: findFirst(root) { it.type == "ispe" }
    val sofOrIspe = sof ?: ispe

    if (sofOrIspe != null) {
        val width = sofOrIspe.fields.find { it.name == "width" || it.name == "image_width" }?.value
        val height = sofOrIspe.fields.find { it.name == "height" || it.name == "image_height" }?.value
        if (width != null && height != null) {
            fields.add(SummaryField("Width", width))
            fields.add(SummaryField("Height", height))
        }
    } else if (isTiff) {
        val tiffIfd0 = root.children.find { it.type == "IFD0" }
        val width = tiffIfd0?.fields?.find { it.name == "ImageWidth" }?.value
        val height = tiffIfd0?.fields?.find { it.name == "ImageLength" }?.value
        if (width != null && height != null) {
            fields.add(SummaryField("Width", width))
            fields.add(SummaryField("Height", height))
        }
    } else if (isPng) {
        val ihdr = root.children.find { it.type == "IHDR" }
        val width = ihdr?.fields?.find { it.name == "width" }?.value
        val height = ihdr?.fields?.find { it.name == "height" }?.value
        if (width != null && height != null) {
            fields.add(SummaryField("Width", width))
            fields.add(SummaryField("Height", height))
        }
    } else if (isBmp) {
        val infoHeader = root.children.find { it.type == "BITMAPINFOHEADER" }
        val width = infoHeader?.fields?.find { it.name == "width" }?.value
        val height = infoHeader?.fields?.find { it.name == "height" }?.value?.toIntOrNull()
        if (width != null && height != null) {
            fields.add(SummaryField("Width", width))
            fields.add(SummaryField("Height", abs(height).toString()))
        }
    } else if (isGif) {
        val lsd = root.children.find { it.type == "LogicalScreenDescriptor" }
        val width = lsd?.fields?.find { it.name == "width" }?.value
        val height = lsd?.fields?.find { it.name == "height" }?.value
        if (width != null && height != null) {
            fields.add(SummaryField("Width", width))
            fields.add(SummaryField("Height", height))
        }
    }

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

    val ifd0 = findFirst(root) { it.type == "IFD0" }
    val exif = ifd0?.children?.find { it.type == "Exif" }
    val captureDate = exif?.fields?.find { it.name == "DateTimeOriginal" }?.value
        ?: ifd0?.fields?.find { it.name == "DateTime" }?.value
    captureDate?.let { fields.add(SummaryField("Capture Date", it)) }

    val frameCount = root.children.count { it.type == "ImageDescriptor" }
    if (frameCount > 0) {
        fields.add(SummaryField("Frame Count", frameCount.toString()))
    }

    val loopCount = root.children.find { it.type == "ApplicationExtension" }
        ?.fields?.find { it.name == "loop_count" }?.value?.toIntOrNull()
    if (loopCount != null) {
        fields.add(SummaryField("Loop Count", if (loopCount == 0) "Infinite" else loopCount.toString()))
    }

    return if (fields.isNotEmpty()) SummarySection("Image", fields) else null
}
```

`Frame Count` and `Loop Count` need no `isGif` guard of their own — `root.children.count { it.type == "ImageDescriptor" }` is naturally `0` for every non-GIF format (no other walker ever produces an `"ImageDescriptor"` node), and the same is true for `"ApplicationExtension"`'s `loop_count` field, so these two lines are format-agnostic exactly like the pre-existing `colorSpace`/`captureDate` fields above them.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew test --tests "com.multiviewer.parser.MediaSummaryBuilderTest" --console=plain`
Expected: PASS

- [ ] **Step 6: Run the full suite**

Run: `./gradlew test --console=plain`
Expected: BUILD SUCCESSFUL, all tests pass (190 existing + 3 new = 193)

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt \
        app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt
git commit -m "feat: populate Media Summary Format/Width/Height/Frame Count/Loop Count for GIF"
```

- [ ] **Step 8: Manual verification note**

Launch the app (`./gradlew :app:run`) and open a real static `.gif` file — confirm Media Summary shows General (Format "GIF", File Size) and Image (Width/Height, Frame Count "1", no Loop Count), and Structure Analyser shows the Logical Screen Descriptor, Color Table (if the sample has one), and a single Image Descriptor, all decoded. Open a real animated `.gif` file — confirm Frame Count matches its actual frame count, Loop Count shows correctly ("Infinite" for the common case of looping forever, or a number for a finite loop count), and Structure Analyser shows a Graphic Control Extension before each Image Descriptor plus an Application Extension with the Netscape loop count decoded.
