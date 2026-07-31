# FLAC Playback and Structural Parsing Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add FLAC to the app's supported audio formats: playable via the existing `FfmpegAudioPlayer` (zero new code needed there — ffmpeg already decodes FLAC), and structurally parseable in the tree/summary/warnings views via a new dedicated walker, following the same pattern established for WebM's `EbmlWalker.kt`.

**Architecture:** A new `FlacWalker.kt` parses the `"fLaC"` magic plus a sequence of METADATA_BLOCKs into the existing `BoxNode`/`BoxField` model, decoding STREAMINFO (bit-packed sample rate/channels/bits/total samples), VORBIS_COMMENT (little-endian tags), and PICTURE (metadata only) in detail, and summarizing SEEKTABLE/PADDING/APPLICATION/CUESHEET/unknown blocks by byte count. `ParseFile.kt` routes `.flac` files to it by magic bytes; `AppState.kt` adds `"flac"` to `AUDIO_EXTENSIONS`; `MediaSummaryBuilder.kt` recognizes the `"fLaC"` root node for category detection and builds a FLAC-specific General/Audio summary reusing the same field-label vocabulary as `buildWavSummary`.

**Tech Stack:** Kotlin, no new dependencies (reuses `ByteReader`, `BoxNode`/`BoxField`, existing test conventions).

## Global Constraints

- Every new format walker reuses `BoxNode`/`BoxField` unchanged — no changes to the tree view, `collectWarnings`, or CLI `dump`/`check` are needed or permitted.
- FLAC audio frames (the compressed sample data after the last METADATA_BLOCK) are never decoded — represented as one opaque `"FrameData"` node with a byte-count summary only, matching JPEG's SOS scan data and WebM's `SimpleBlock` convention.
- `PICTURE` block image bytes are never extracted as a displayable thumbnail — only metadata fields (type/mime/dimensions).
- `SEEKTABLE` gets a computed entry count only (`payloadLength / 18`), never per-entry detail.
- VORBIS_COMMENT's `vendor_length`/`vendor_string`/`comment_count`/per-comment `length` fields are little-endian — every other multi-byte FLAC field is big-endian. This is a real FLAC-spec quirk, not a bug — do not "fix" it to big-endian.
- OGG and AIFF support are explicitly out of scope for this plan (separate specs/plans later).
- `ffmpeg`/`ffprobe` must be on `PATH` for any test that shells out to generate a real `.flac` fixture (matches this project's existing test conventions for WAV/MP3/WebM/M4A fixtures).

---

### Task 1: `FlacWalker.kt` core parser

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/FlacWalker.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/FlacWalkerTest.kt`

**Interfaces:**
- Consumes: `ByteReader` (`readUInt8`, `readUInt16`, `readUInt32`, `readUInt64`, `readBytes` — all in `app/src/main/kotlin/com/multiviewer/parser/ByteReader.kt`, all big-endian), `BoxNode`/`BoxField` (`app/src/main/kotlin/com/multiviewer/parser/BoxNode.kt`).
- Produces: `fun parseFlacBlocks(reader: ByteReader, start: Long, end: Long): List<BoxNode>` — the only public symbol. Task 2 calls this directly from `ParseFile.kt`.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/multiviewer/parser/FlacWalkerTest.kt`:

```kotlin
package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlacWalkerTest {
    @Test
    fun `parses the fLaC marker and decodes a STREAMINFO block's bit-packed fields`() {
        // STREAMINFO payload (34 bytes): min/max_blocksize=4096, min_framesize=1000,
        // max_framesize=2000, packed 64-bit field encoding sample_rate=44100, channels=2,
        // bits_per_sample=16, total_samples=88200 (independently verified: packed =
        // (44100 shl 44) or (1 shl 41) or (15 shl 36) or 88200 = 0x0AC442F000015888), then a
        // 16-byte MD5 (arbitrary bytes 0x00..0x0F here, not a real MD5).
        val streamInfoPayload = byteArrayOf(
            0x10, 0x00, // min_blocksize = 4096
            0x10, 0x00, // max_blocksize = 4096
            0x00, 0x03, 0xE8.toByte(), // min_framesize = 1000
            0x00, 0x07, 0xD0.toByte(), // max_framesize = 2000
            0x0A, 0xC4.toByte(), 0x42, 0xF0.toByte(), 0x00, 0x01, 0x58, 0x88.toByte(), // packed field
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, // md5
        )
        assertEquals(34, streamInfoPayload.size)
        val bytes = "fLaC".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0x80.toByte(), 0x00, 0x00, 0x22) + // last=1, type=0 (STREAMINFO), length=34
            streamInfoPayload
        val reader = byteReaderOf(bytes)

        val elements = parseFlacBlocks(reader, 0, reader.length)

        assertEquals(2, elements.size)
        assertEquals("fLaC", elements[0].type)
        assertEquals(0L, elements[0].offset)
        assertEquals(4, elements[0].headerSize)
        assertEquals(4L, elements[0].size)

        val streamInfo = elements[1]
        assertEquals("STREAMINFO", streamInfo.type)
        assertEquals(4L, streamInfo.offset)
        assertEquals(4, streamInfo.headerSize)
        assertEquals(38L, streamInfo.size)
        assertEquals("4096", streamInfo.fields.single { it.name == "min_blocksize" }.value)
        assertEquals("4096", streamInfo.fields.single { it.name == "max_blocksize" }.value)
        assertEquals("1000", streamInfo.fields.single { it.name == "min_framesize" }.value)
        assertEquals("2000", streamInfo.fields.single { it.name == "max_framesize" }.value)
        assertEquals("44100", streamInfo.fields.single { it.name == "sample_rate" }.value)
        assertEquals("2", streamInfo.fields.single { it.name == "channels" }.value)
        assertEquals("16", streamInfo.fields.single { it.name == "bits_per_sample" }.value)
        assertEquals("88200", streamInfo.fields.single { it.name == "total_samples" }.value)
        assertEquals("000102030405060708090a0b0c0d0e0f", streamInfo.fields.single { it.name == "md5_signature" }.value)
        reader.close()
    }

    @Test
    fun `decodes a VORBIS_COMMENT block's little-endian vendor and comment fields`() {
        val payload = byteArrayOf(
            0x04, 0x00, 0x00, 0x00, // vendor_length = 4 (LE)
            'L'.code.toByte(), 'a'.code.toByte(), 'v'.code.toByte(), 'f'.code.toByte(), // vendor_string
            0x01, 0x00, 0x00, 0x00, // comment_count = 1 (LE)
            0x0A, 0x00, 0x00, 0x00, // comment[0] length = 10 (LE)
        ) + "TITLE=Test".toByteArray(Charsets.UTF_8)
        assertEquals(26, payload.size)
        val bytes = "fLaC".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0x84.toByte(), 0x00, 0x00, 0x1A) + // last=1, type=4 (VORBIS_COMMENT), length=26
            payload
        val reader = byteReaderOf(bytes)

        val elements = parseFlacBlocks(reader, 0, reader.length)

        assertEquals(2, elements.size)
        val comment = elements[1]
        assertEquals("VORBIS_COMMENT", comment.type)
        assertEquals("Lavf", comment.fields.single { it.name == "vendor" }.value)
        assertEquals("Test", comment.fields.single { it.name == "TITLE" }.value)
        assertEquals("1 comment(s)", comment.summary)
        reader.close()
    }

    @Test
    fun `an unrecognized block type falls back to an unlabeled byte-count summary`() {
        val bytes = "fLaC".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0x8A.toByte(), 0x00, 0x00, 0x05) + // last=1, type=10 (reserved), length=5
            byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55)
        val reader = byteReaderOf(bytes)

        val elements = parseFlacBlocks(reader, 0, reader.length)

        assertEquals(2, elements.size)
        assertEquals("Unknown (10)", elements[1].type)
        assertTrue(elements[1].fields.isEmpty())
        assertEquals("5 byte(s)", elements[1].summary)
        reader.close()
    }

    @Test
    fun `the last-block flag stops the metadata loop and everything after becomes FrameData`() {
        val bytes = "fLaC".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0x01, 0x00, 0x00, 0x02) + byteArrayOf(0x00, 0x00) + // PADDING, not last, length=2
            byteArrayOf(0x81.toByte(), 0x00, 0x00, 0x03) + byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte()) + // PADDING, last, length=3
            byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()) // trailing frame bytes
        val reader = byteReaderOf(bytes)

        val elements = parseFlacBlocks(reader, 0, reader.length)

        assertEquals(3, elements.size)
        assertEquals("PADDING", elements[0].type)
        assertEquals("2 byte(s)", elements[0].summary)
        assertEquals("PADDING", elements[1].type)
        assertEquals("3 byte(s)", elements[1].summary)
        assertEquals("FrameData", elements[2].type)
        assertEquals(4L, elements[2].size)
        assertEquals("4 byte(s)", elements[2].summary)
        reader.close()
    }

    @Test
    fun `too few bytes for a metadata block header produces a trailing-bytes warning and stops`() {
        val bytes = "fLaC".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x80.toByte(), 0x00)
        val reader = byteReaderOf(bytes)

        val elements = parseFlacBlocks(reader, 0, reader.length)

        assertEquals(2, elements.size)
        assertEquals("?", elements[1].type)
        assertTrue(elements[1].warnings.single().contains("too short"))
        reader.close()
    }

    @Test
    fun `declared block size extending past the parent range produces a warning and clamps`() {
        val bytes = "fLaC".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0x81.toByte(), 0x00, 0x00, 0x64) + // PADDING, last, declared length=100
            byteArrayOf(0x01, 0x02, 0x03) // only 3 bytes actually available
        val reader = byteReaderOf(bytes)

        val elements = parseFlacBlocks(reader, 0, reader.length)

        assertEquals(2, elements.size)
        assertEquals("PADDING", elements[1].type)
        assertTrue(elements[1].warnings.single().contains("extends"))
        assertEquals(7L, elements[1].size)
        reader.close()
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests "com.multiviewer.parser.FlacWalkerTest"`
Expected: FAIL — `parseFlacBlocks` is unresolved (file doesn't exist yet).

- [ ] **Step 3: Write the implementation**

Create `app/src/main/kotlin/com/multiviewer/parser/FlacWalker.kt`:

```kotlin
package com.multiviewer.parser

private val FLAC_BLOCK_TYPE_NAMES = mapOf(
    0 to "STREAMINFO",
    1 to "PADDING",
    2 to "APPLICATION",
    3 to "SEEKTABLE",
    4 to "VORBIS_COMMENT",
    5 to "CUESHEET",
    6 to "PICTURE",
)

fun parseFlacBlocks(reader: ByteReader, start: Long, end: Long): List<BoxNode> {
    val result = mutableListOf<BoxNode>()
    if (end - start < 4) return result
    result.add(BoxNode("fLaC", start, 4, 4))

    var pos = start + 4
    var isLast = false
    while (!isLast && pos < end) {
        val remaining = end - pos
        if (remaining < 4) {
            result.add(BoxNode("?", pos, 0, remaining, warnings = listOf("Trailing $remaining byte(s): too short for a metadata block header")))
            break
        }
        val headerByte = reader.readUInt8(pos)
        isLast = (headerByte and 0x80) != 0
        val blockType = headerByte and 0x7F
        val blockLength = readUInt24BE(reader, pos + 1)
        val headerSize = 4
        var size = headerSize + blockLength
        val warnings = mutableListOf<String>()
        if (pos + size > end) {
            warnings.add("Declared size $size extends ${pos + size - end} byte(s) past the end of its parent")
            size = end - pos
        }

        val name = FLAC_BLOCK_TYPE_NAMES[blockType] ?: "Unknown ($blockType)"
        val payloadStart = pos + headerSize
        val payloadEnd = pos + size
        result.add(decodeFlacBlock(reader, name, pos, headerSize, size, payloadStart, payloadEnd, warnings))
        pos += size
    }

    if (pos < end) {
        result.add(BoxNode("FrameData", pos, 0, end - pos, summary = "${end - pos} byte(s)"))
    }

    return result
}

private fun decodeFlacBlock(
    reader: ByteReader,
    name: String,
    offset: Long,
    headerSize: Int,
    size: Long,
    payloadStart: Long,
    payloadEnd: Long,
    warnings: List<String>,
): BoxNode {
    val payloadLength = (payloadEnd - payloadStart).toInt()
    if (payloadLength <= 0) return BoxNode(name, offset, headerSize, size, warnings = warnings)
    return when (name) {
        "STREAMINFO" -> decodeStreamInfo(reader, name, offset, headerSize, size, payloadStart, payloadLength, warnings)
        "VORBIS_COMMENT" -> decodeVorbisComment(reader, name, offset, headerSize, size, payloadStart, payloadLength, warnings)
        "PICTURE" -> decodePicture(reader, name, offset, headerSize, size, payloadStart, payloadLength, warnings)
        "SEEKTABLE" -> BoxNode(name, offset, headerSize, size, summary = "${payloadLength / 18} seek point(s)", warnings = warnings)
        else -> BoxNode(name, offset, headerSize, size, summary = "$payloadLength byte(s)", warnings = warnings)
    }
}

private fun decodeStreamInfo(
    reader: ByteReader, name: String, offset: Long, headerSize: Int, size: Long,
    payloadStart: Long, payloadLength: Int, warnings: List<String>,
): BoxNode {
    if (payloadLength < 34) {
        return BoxNode(name, offset, headerSize, size, warnings = warnings + "STREAMINFO block is $payloadLength byte(s), expected 34")
    }
    val minBlocksize = reader.readUInt16(payloadStart)
    val maxBlocksize = reader.readUInt16(payloadStart + 2)
    val minFramesize = readUInt24BE(reader, payloadStart + 4)
    val maxFramesize = readUInt24BE(reader, payloadStart + 7)
    val packed = reader.readUInt64(payloadStart + 10)
    val sampleRate = (packed shr 44) and 0xFFFFF
    val channels = ((packed shr 41) and 0x7) + 1
    val bitsPerSample = ((packed shr 36) and 0x1F) + 1
    val totalSamples = packed and 0xFFFFFFFFFL
    val md5 = reader.readBytes(payloadStart + 18, 16).joinToString("") { "%02x".format(it) }

    val fields = listOf(
        BoxField("min_blocksize", minBlocksize.toString(), payloadStart, 2),
        BoxField("max_blocksize", maxBlocksize.toString(), payloadStart + 2, 2),
        BoxField("min_framesize", minFramesize.toString(), payloadStart + 4, 3),
        BoxField("max_framesize", maxFramesize.toString(), payloadStart + 7, 3),
        BoxField("sample_rate", sampleRate.toString(), payloadStart + 10, 8),
        BoxField("channels", channels.toString(), payloadStart + 10, 8),
        BoxField("bits_per_sample", bitsPerSample.toString(), payloadStart + 10, 8),
        BoxField("total_samples", totalSamples.toString(), payloadStart + 10, 8),
        BoxField("md5_signature", md5, payloadStart + 18, 16),
    )
    return BoxNode(
        name, offset, headerSize, size, fields = fields,
        summary = "${sampleRate}Hz, ${channels}ch, ${bitsPerSample}-bit, $totalSamples samples",
        warnings = warnings,
    )
}

private fun decodeVorbisComment(
    reader: ByteReader, name: String, offset: Long, headerSize: Int, size: Long,
    payloadStart: Long, payloadLength: Int, warnings: List<String>,
): BoxNode {
    val allWarnings = warnings.toMutableList()
    val payloadEnd = payloadStart + payloadLength
    val fields = mutableListOf<BoxField>()
    var pos = payloadStart

    if (pos + 4 > payloadEnd) {
        allWarnings.add("VORBIS_COMMENT block is $payloadLength byte(s), too short for its vendor length field")
        return BoxNode(name, offset, headerSize, size, warnings = allWarnings)
    }
    val vendorLength = readUInt32LE(reader, pos).toInt()
    pos += 4
    if (vendorLength < 0 || pos + vendorLength > payloadEnd) {
        allWarnings.add("Vendor string length $vendorLength extends past the end of this block")
        return BoxNode(name, offset, headerSize, size, warnings = allWarnings)
    }
    val vendor = String(reader.readBytes(pos, vendorLength), Charsets.UTF_8)
    fields.add(BoxField("vendor", vendor, pos, vendorLength.toLong()))
    pos += vendorLength

    if (pos + 4 > payloadEnd) {
        allWarnings.add("Truncated before comment count")
        return BoxNode(name, offset, headerSize, size, fields = fields, warnings = allWarnings)
    }
    val commentCount = readUInt32LE(reader, pos).toInt()
    pos += 4

    for (i in 0 until commentCount) {
        if (pos + 4 > payloadEnd) {
            allWarnings.add("Comment list truncated at entry $i of $commentCount")
            break
        }
        val commentLength = readUInt32LE(reader, pos).toInt()
        pos += 4
        if (commentLength < 0 || pos + commentLength > payloadEnd) {
            allWarnings.add("Comment $i length $commentLength extends past the end of this block")
            break
        }
        val comment = String(reader.readBytes(pos, commentLength), Charsets.UTF_8)
        val eq = comment.indexOf('=')
        if (eq >= 0) {
            fields.add(BoxField(comment.substring(0, eq), comment.substring(eq + 1), pos, commentLength.toLong()))
        } else {
            fields.add(BoxField("comment", comment, pos, commentLength.toLong()))
        }
        pos += commentLength
    }

    return BoxNode(name, offset, headerSize, size, fields = fields, summary = "$commentCount comment(s)", warnings = allWarnings)
}

private fun decodePicture(
    reader: ByteReader, name: String, offset: Long, headerSize: Int, size: Long,
    payloadStart: Long, payloadLength: Int, warnings: List<String>,
): BoxNode {
    val allWarnings = warnings.toMutableList()
    val payloadEnd = payloadStart + payloadLength
    var pos = payloadStart

    if (pos + 8 > payloadEnd) {
        allWarnings.add("PICTURE block is $payloadLength byte(s), too short for its fixed fields")
        return BoxNode(name, offset, headerSize, size, warnings = allWarnings)
    }
    val pictureType = reader.readUInt32(pos)
    val mimeLength = reader.readUInt32(pos + 4).toInt()
    pos += 8
    if (mimeLength < 0 || pos + mimeLength > payloadEnd) {
        allWarnings.add("MIME type length $mimeLength extends past the end of this block")
        return BoxNode(name, offset, headerSize, size, warnings = allWarnings)
    }
    val mime = String(reader.readBytes(pos, mimeLength), Charsets.US_ASCII)
    val mimeOffset = pos
    pos += mimeLength

    if (pos + 4 > payloadEnd) {
        allWarnings.add("Truncated before description length")
        return BoxNode(name, offset, headerSize, size, warnings = allWarnings)
    }
    val descriptionLength = reader.readUInt32(pos).toInt()
    pos += 4
    if (descriptionLength < 0 || pos + descriptionLength > payloadEnd) {
        allWarnings.add("Description length $descriptionLength extends past the end of this block")
        return BoxNode(name, offset, headerSize, size, warnings = allWarnings)
    }
    val description = String(reader.readBytes(pos, descriptionLength), Charsets.UTF_8)
    val descriptionOffset = pos
    pos += descriptionLength

    val baseFields = mutableListOf(
        BoxField("picture_type", pictureType.toString(), payloadStart, 4),
        BoxField("mime", mime, mimeOffset, mimeLength.toLong()),
        BoxField("description", description, descriptionOffset, descriptionLength.toLong()),
    )

    if (pos + 20 > payloadEnd) {
        allWarnings.add("Truncated before width/height/color fields")
        return BoxNode(name, offset, headerSize, size, fields = baseFields, warnings = allWarnings)
    }
    val width = reader.readUInt32(pos)
    val height = reader.readUInt32(pos + 4)
    val colorDepth = reader.readUInt32(pos + 8)
    baseFields.add(BoxField("width", width.toString(), pos, 4))
    baseFields.add(BoxField("height", height.toString(), pos + 4, 4))
    baseFields.add(BoxField("color_depth", colorDepth.toString(), pos + 8, 4))

    return BoxNode(name, offset, headerSize, size, fields = baseFields, summary = "$mime, ${width}x$height", warnings = allWarnings)
}

private fun readUInt24BE(reader: ByteReader, offset: Long): Long {
    val bytes = reader.readBytes(offset, 3)
    return ((bytes[0].toLong() and 0xFF) shl 16) or ((bytes[1].toLong() and 0xFF) shl 8) or (bytes[2].toLong() and 0xFF)
}

private fun readUInt32LE(reader: ByteReader, offset: Long): Long {
    val b = reader.readBytes(offset, 4)
    return (b[0].toLong() and 0xFF) or ((b[1].toLong() and 0xFF) shl 8) or
        ((b[2].toLong() and 0xFF) shl 16) or ((b[3].toLong() and 0xFF) shl 24)
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests "com.multiviewer.parser.FlacWalkerTest"`
Expected: PASS, 6/6 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/FlacWalker.kt app/src/test/kotlin/com/multiviewer/parser/FlacWalkerTest.kt
git commit -m "feat: add FlacWalker for FLAC container structural parsing"
```

---

### Task 2: Wire FLAC into `ParseFile.kt` and `AppState.kt`

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/ParseFile.kt` (full current content is 84 lines; the magic-byte dispatch chain ends with `isEbml`/`isEbmlMagic`)
- Modify: `app/src/main/kotlin/com/multiviewer/ui/AppState.kt:35` (`AUDIO_EXTENSIONS`) and its preceding comment block (lines 28-34)
- Test: `app/src/test/kotlin/com/multiviewer/parser/ParseFileIntegrationTest.kt`

**Interfaces:**
- Consumes: `parseFlacBlocks(reader: ByteReader, start: Long, end: Long): List<BoxNode>` from Task 1.
- Produces: `.flac` files are recognized as `MediaType.AUDIO` by `AppState.openFile` and routed to `parseFlacBlocks` by `parseFile`. Task 3 relies on this routing being in place.

- [ ] **Step 1: Write the failing test**

In `app/src/test/kotlin/com/multiviewer/parser/ParseFileIntegrationTest.kt`, add this test (matches the existing `` `parses a synthetic minimal webm file...` `` test right above it in style):

```kotlin
    @Test
    fun `parses a synthetic minimal flac file via the FLAC path, not the ISOBMFF path`() {
        val streamInfoPayload = byteArrayOf(
            0x10, 0x00, 0x10, 0x00,
            0x00, 0x03, 0xE8.toByte(), 0x00, 0x07, 0xD0.toByte(),
            0x0A, 0xC4.toByte(), 0x42, 0xF0.toByte(), 0x00, 0x01, 0x58, 0x88.toByte(),
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F,
        )
        val bytes = "fLaC".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0x80.toByte(), 0x00, 0x00, 0x22) +
            streamInfoPayload
        val tmp = File.createTempFile("multiviewer-flac", ".flac")
        tmp.deleteOnExit()
        tmp.writeBytes(bytes)

        val root = parseFile(tmp)

        assertEquals(listOf("fLaC", "STREAMINFO"), root.children.map { it.type })
        assertEquals("44100", root.children[1].fields.single { it.name == "sample_rate" }.value)
    }
```

Add this test as a new method inside the existing `ParseFileIntegrationTest` class (place it right after the `` `parses a synthetic minimal webm file...` `` test). No new imports are needed — `File`, `assertEquals` are already imported.

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests "com.multiviewer.parser.ParseFileIntegrationTest"`
Expected: FAIL — the new test fails because `parseFile` doesn't recognize `.flac` yet (falls through to `parseBoxes`, producing garbage/empty children, not `["fLaC", "STREAMINFO"]`).

- [ ] **Step 3: Wire the routing**

In `app/src/main/kotlin/com/multiviewer/parser/ParseFile.kt`, change:

```kotlin
            val isEbml = !isJpeg && !isPng && !isBmp && !isGif && !isTiff && !isWebp && !isWav && !isMp3 && isEbmlMagic(reader)
            val children = when {
                isJpeg -> parseJpegSegments(reader, 0, reader.length)
                isPng -> parsePngChunks(reader, 8, reader.length)
                isBmp -> parseBmpHeaders(reader, 0, reader.length)
                isGif -> parseGifBlocks(reader, 6, reader.length)
                isTiff -> decodeTiff(reader, 0, reader.length)
                isWebp -> parseWebpChunks(reader, 0, reader.length)
                isWav -> parseWavChunks(reader, 0, reader.length)
                isMp3 -> parseMp3(reader, 0, reader.length)
                isEbml -> parseEbmlElements(reader, 0, reader.length)
                else -> parseBoxes(reader, 0, reader.length)
            }
```

to:

```kotlin
            val isEbml = !isJpeg && !isPng && !isBmp && !isGif && !isTiff && !isWebp && !isWav && !isMp3 && isEbmlMagic(reader)
            val isFlac = !isJpeg && !isPng && !isBmp && !isGif && !isTiff && !isWebp && !isWav && !isMp3 && !isEbml && isFlacMagic(reader)
            val children = when {
                isJpeg -> parseJpegSegments(reader, 0, reader.length)
                isPng -> parsePngChunks(reader, 8, reader.length)
                isBmp -> parseBmpHeaders(reader, 0, reader.length)
                isGif -> parseGifBlocks(reader, 6, reader.length)
                isTiff -> decodeTiff(reader, 0, reader.length)
                isWebp -> parseWebpChunks(reader, 0, reader.length)
                isWav -> parseWavChunks(reader, 0, reader.length)
                isMp3 -> parseMp3(reader, 0, reader.length)
                isEbml -> parseEbmlElements(reader, 0, reader.length)
                isFlac -> parseFlacBlocks(reader, 0, reader.length)
                else -> parseBoxes(reader, 0, reader.length)
            }
```

Then add a new private function right after `isEbmlMagic` (which currently ends the file's private-helper section):

```kotlin
private fun isFlacMagic(reader: ByteReader): Boolean {
    if (reader.length < 4) return false
    return reader.readFourCC(0) == "fLaC"
}
```

- [ ] **Step 4: Add the extension and update the comment**

In `app/src/main/kotlin/com/multiviewer/ui/AppState.kt`, change:

```kotlin
// M4A is an MP4-family container (same ftyp/moov/trak structure as mp4/mov/m4v above) holding an
// audio-only track (AAC, ALAC, or AC-3) -- parseFile's magic-byte dispatch already reaches the
// same generic ISOBMFF box walker for it with no new parser needed, and MediaSummaryBuilder's
// detectCategory/buildVideoSummary already handle a video-less "soun"-only moov correctly (that
// code predates this extension even being routed here). MP3 and WAV have their own dedicated
// parsers (Mp3Walker/WavWalker). FLAC and OGG use genuinely different container structures and
// still need their own parsers -- not included yet.
val AUDIO_EXTENSIONS = listOf("m4a", "mp3", "wav")
```

to:

```kotlin
// M4A is an MP4-family container (same ftyp/moov/trak structure as mp4/mov/m4v above) holding an
// audio-only track (AAC, ALAC, or AC-3) -- parseFile's magic-byte dispatch already reaches the
// same generic ISOBMFF box walker for it with no new parser needed, and MediaSummaryBuilder's
// detectCategory/buildVideoSummary already handle a video-less "soun"-only moov correctly (that
// code predates this extension even being routed here). MP3, WAV, and FLAC each have their own
// dedicated parsers (Mp3Walker/WavWalker/FlacWalker). OGG uses a genuinely different container
// structure and still needs its own parser -- not included yet.
val AUDIO_EXTENSIONS = listOf("m4a", "mp3", "wav", "flac")
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests "com.multiviewer.parser.ParseFileIntegrationTest"`
Expected: PASS, including the new FLAC test.

Also run the full suite once to confirm nothing else regressed:

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test`
Expected: PASS, no new failures.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/ParseFile.kt app/src/main/kotlin/com/multiviewer/ui/AppState.kt app/src/test/kotlin/com/multiviewer/parser/ParseFileIntegrationTest.kt
git commit -m "feat: route .flac files to FlacWalker and recognize the flac extension"
```

---

### Task 3: `MediaSummaryBuilder.kt` category detection and FLAC summary

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt` (`detectCategory` at lines 56-75, `buildStandaloneAudioSummary` at lines 460-463)
- Test: Create `app/src/test/kotlin/com/multiviewer/parser/FlacMediaSummaryBuilderTest.kt`
- Test: Modify `app/src/test/kotlin/com/multiviewer/ui/AppStateTest.kt` (add one real-fixture test, after the existing `` `openFile opens a real MP3...` `` test)

**Interfaces:**
- Consumes: `BoxNode`/`BoxField`, `SummarySection`/`SummaryField`, `formatDuration`/`formatBitrate`/`formatFileSize` (all already in `MediaSummaryBuilder.kt`), the `"fLaC"`/`"STREAMINFO"` node types and `sample_rate`/`channels`/`bits_per_sample`/`total_samples` field names produced by Task 1's `FlacWalker.kt`.
- Produces: `buildMediaSummary(root, file)` now returns `MediaCategory.AUDIO` with populated `General`/`Audio` sections for any FLAC file.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/multiviewer/parser/FlacMediaSummaryBuilderTest.kt`:

```kotlin
package com.multiviewer.parser

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class FlacMediaSummaryBuilderTest {
    private fun buildFlacFixture(): BoxNode {
        val streamInfo = BoxNode(
            "STREAMINFO", 0, 0, 0,
            fields = listOf(
                BoxField("sample_rate", "44100", 0, 0),
                BoxField("channels", "2", 0, 0),
                BoxField("bits_per_sample", "16", 0, 0),
                BoxField("total_samples", "88200", 0, 0),
            ),
        )
        val flacMarker = BoxNode("fLaC", 0, 0, 0)
        return BoxNode("root", 0, 0, 0, children = listOf(flacMarker, streamInfo))
    }

    @Test
    fun `a fLaC root is classified as AUDIO`() {
        val root = buildFlacFixture()
        val tmp = File.createTempFile("flac-summary-category-test", ".flac")
        tmp.deleteOnExit()
        tmp.writeBytes(ByteArray(10))

        val summary = buildMediaSummary(root, tmp)

        assertEquals(MediaCategory.AUDIO, summary.category)
    }

    @Test
    fun `a FLAC tree produces General and Audio sections with correct values`() {
        val root = buildFlacFixture()
        val tmp = File.createTempFile("flac-summary-test", ".flac")
        tmp.deleteOnExit()
        tmp.writeBytes(ByteArray(200_000))

        val summary = buildMediaSummary(root, tmp)

        assertEquals(2, summary.sections.size)

        val general = summary.sections.first { it.title == "General" }
        assertEquals("FLAC", general.fields.first { it.label == "Format" }.value)
        assertEquals("0:00:02.000", general.fields.first { it.label == "Duration" }.value)

        val audio = summary.sections.first { it.title == "Audio" }
        assertEquals("44100 Hz", audio.fields.first { it.label == "Sampling Rate" }.value)
        assertEquals("2", audio.fields.first { it.label == "Channel(s)" }.value)
        assertEquals("16-bit", audio.fields.first { it.label == "Bit Depth" }.value)
    }
}
```

In `app/src/test/kotlin/com/multiviewer/ui/AppStateTest.kt`, add this test right after `` `openFile opens a real MP3 as MediaType_AUDIO with ID3v2 tags and audio frame details` `` (same file, same class — no new imports needed, mirrors the WAV/MP3 tests immediately above it):

```kotlin
    @Test
    fun `openFile opens a real FLAC as MediaType_AUDIO with a populated Audio section`() {
        val audio = File.createTempFile("appstate-flac-test-", ".flac")
        audio.deleteOnExit()
        ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "sine=duration=1:frequency=440",
            "-c:a", "flac", audio.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()

        val appState = AppState()
        appState.openFile(audio)
        val tab = appState.tabs.single()
        waitForLoad(tab)

        assertEquals(null, tab.error)
        assertEquals(MediaType.AUDIO, tab.type)
        val generalSection = tab.mediaSummary?.sections?.find { it.title == "General" }
        assertTrue(generalSection?.fields?.any { it.label == "Format" && it.value == "FLAC" } == true, "Expected General/Format=FLAC, got: $generalSection")
        val audioSection = tab.mediaSummary?.sections?.find { it.title == "Audio" }
        assertTrue(audioSection?.fields?.any { it.label == "Sampling Rate" } == true, "Expected an Audio/Sampling Rate field, got: $audioSection")
        audio.delete()
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests "com.multiviewer.parser.FlacMediaSummaryBuilderTest" --tests "com.multiviewer.ui.AppStateTest"`
Expected: FAIL — `FlacMediaSummaryBuilderTest`'s category test gets `MediaCategory.IMAGE` (wrong fallback), and the new `AppStateTest` FLAC test finds no populated `Audio` section (both because `detectCategory` doesn't yet recognize `"fLaC"`).

- [ ] **Step 3: Implement the category detection and summary**

In `app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt`, change `detectCategory` from:

```kotlin
private fun detectCategory(root: BoxNode): MediaCategory {
    if (root.children.any { it.type == "SOI" }) return MediaCategory.IMAGE
    val ftyp = root.children.find { it.type == "ftyp" }
    val majorBrand = ftyp?.fields?.find { it.name == "major_brand" }?.value
    if (majorBrand == "avif" || majorBrand == "avis" || majorBrand == "heic") return MediaCategory.IMAGE
    if (isWebm(root)) return MediaCategory.VIDEO

    // WAV and WebP both put a "RIFF" node at the root (WebP stores its form-type in a field,
    // not the node's own type string), so a WAV check can't key off "RIFF" alone -- "fmt " is
    // WAV-specific and never appears in a WebP tree.
    if (root.children.any { it.type == "fmt " }) return MediaCategory.AUDIO
    if (root.children.any { it.type == "ID3v2" || it.type == "AudioFrames" || it.type == "ID3v1" }) return MediaCategory.AUDIO

    val moov = root.children.find { it.type == "moov" } ?: return MediaCategory.IMAGE
    val hasVideoOrAudioTrack = moov.children.filter { it.type == "trak" }.any { trak ->
        val handlerType = findFirst(trak) { it.type == "hdlr" }?.fields?.find { it.name == "handler_type" }?.value
        handlerType == "vide" || handlerType == "soun"
    }
    return if (hasVideoOrAudioTrack) MediaCategory.VIDEO else MediaCategory.IMAGE
}
```

to:

```kotlin
private fun detectCategory(root: BoxNode): MediaCategory {
    if (root.children.any { it.type == "SOI" }) return MediaCategory.IMAGE
    val ftyp = root.children.find { it.type == "ftyp" }
    val majorBrand = ftyp?.fields?.find { it.name == "major_brand" }?.value
    if (majorBrand == "avif" || majorBrand == "avis" || majorBrand == "heic") return MediaCategory.IMAGE
    if (isWebm(root)) return MediaCategory.VIDEO

    // WAV and WebP both put a "RIFF" node at the root (WebP stores its form-type in a field,
    // not the node's own type string), so a WAV check can't key off "RIFF" alone -- "fmt " is
    // WAV-specific and never appears in a WebP tree.
    if (root.children.any { it.type == "fmt " }) return MediaCategory.AUDIO
    if (root.children.any { it.type == "ID3v2" || it.type == "AudioFrames" || it.type == "ID3v1" }) return MediaCategory.AUDIO
    if (root.children.any { it.type == "fLaC" }) return MediaCategory.AUDIO

    val moov = root.children.find { it.type == "moov" } ?: return MediaCategory.IMAGE
    val hasVideoOrAudioTrack = moov.children.filter { it.type == "trak" }.any { trak ->
        val handlerType = findFirst(trak) { it.type == "hdlr" }?.fields?.find { it.name == "handler_type" }?.value
        handlerType == "vide" || handlerType == "soun"
    }
    return if (hasVideoOrAudioTrack) MediaCategory.VIDEO else MediaCategory.IMAGE
}
```

Then change `buildStandaloneAudioSummary` from:

```kotlin
private fun buildStandaloneAudioSummary(root: BoxNode, fileSizeBytes: Long): List<SummarySection> {
    val fmt = root.children.find { it.type == "fmt " }
    return if (fmt != null) buildWavSummary(root, fmt, fileSizeBytes) else buildMp3Summary(root, fileSizeBytes)
}
```

to:

```kotlin
private fun buildStandaloneAudioSummary(root: BoxNode, fileSizeBytes: Long): List<SummarySection> {
    val fmt = root.children.find { it.type == "fmt " }
    return when {
        fmt != null -> buildWavSummary(root, fmt, fileSizeBytes)
        root.children.any { it.type == "fLaC" } -> buildFlacSummary(root, fileSizeBytes)
        else -> buildMp3Summary(root, fileSizeBytes)
    }
}

private fun buildFlacSummary(root: BoxNode, fileSizeBytes: Long): List<SummarySection> {
    val generalFields = mutableListOf(
        SummaryField("Format", "FLAC"),
        SummaryField("File Size", formatFileSize(fileSizeBytes)),
    )

    val streamInfo = root.children.find { it.type == "STREAMINFO" }
    val sampleRate = streamInfo?.fields?.find { it.name == "sample_rate" }?.value?.toDoubleOrNull()
    val totalSamples = streamInfo?.fields?.find { it.name == "total_samples" }?.value?.toDoubleOrNull()
    if (sampleRate != null && sampleRate > 0 && totalSamples != null) {
        val durationSeconds = totalSamples / sampleRate
        generalFields.add(SummaryField("Duration", formatDuration(durationSeconds)))
        if (durationSeconds > 0) {
            val bitrate = (fileSizeBytes * 8) / durationSeconds
            generalFields.add(SummaryField("Overall Bit Rate", formatBitrate(bitrate)))
        }
    }

    val audioFields = mutableListOf<SummaryField>()
    streamInfo?.fields?.find { it.name == "sample_rate" }?.let { audioFields.add(SummaryField("Sampling Rate", "${it.value} Hz")) }
    streamInfo?.fields?.find { it.name == "channels" }?.let { audioFields.add(SummaryField("Channel(s)", it.value)) }
    streamInfo?.fields?.find { it.name == "bits_per_sample" }?.let { audioFields.add(SummaryField("Bit Depth", "${it.value}-bit")) }

    return listOf(SummarySection("General", generalFields), SummarySection("Audio", audioFields))
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests "com.multiviewer.parser.FlacMediaSummaryBuilderTest" --tests "com.multiviewer.ui.AppStateTest"`
Expected: PASS.

Then run the full suite:

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test`
Expected: PASS, no new failures.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt app/src/test/kotlin/com/multiviewer/parser/FlacMediaSummaryBuilderTest.kt app/src/test/kotlin/com/multiviewer/ui/AppStateTest.kt
git commit -m "feat: classify FLAC as AUDIO and build a FLAC-specific media summary"
```

---

### Task 4: Manual end-to-end verification (controller-performed)

No automated coverage is possible for this task (Compose UI + audio hardware playback, same category as `FfmpegAudioPlayer`'s own manual-verification-only coverage). This step is performed by the controller directly, not dispatched to a subagent.

- [ ] Generate a real `.flac` fixture: `ffmpeg -y -f lavfi -i "sine=duration=5:frequency=440" -c:a flac /tmp/test-verify.flac`
- [ ] Launch the app (`export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew run`) and open `/tmp/test-verify.flac`
- [ ] Confirm the tree view shows `fLaC` and `STREAMINFO` (and any other blocks ffmpeg wrote, e.g. `VORBIS_COMMENT`/`PADDING`) with sensible decoded fields, not raw garbage
- [ ] Confirm the Detail Properties panel shows General (Format=FLAC, Duration, File Size, Overall Bit Rate) and Audio (Sampling Rate, Channel(s), Bit Depth) sections with correct values
- [ ] Confirm playback works: play/pause, the waveform and spectrogram render, the playhead moves, and clicking/dragging on the waveform seeks correctly (same checks as the audio-playback feature's own manual verification)
- [ ] If any issue is found, treat it as a real bug — return to systematic-debugging, not a quick patch

---

## Self-Review Notes

- **Spec coverage:** STREAMINFO ✅ (Task 1), VORBIS_COMMENT ✅ (Task 1), PICTURE ✅ (Task 1, no automated test since the design's testing section doesn't list one, but code exists per spec §A), SEEKTABLE/PADDING/APPLICATION/CUESHEET summary-only ✅ (Task 1, PADDING covered by test, others use the identical code path), unknown block fallback ✅ (Task 1), FrameData tail ✅ (Task 1), truncated header warning ✅ (Task 1), wiring into ParseFile/AppState ✅ (Task 2), MediaSummaryBuilder category+summary ✅ (Task 3), manual verification ✅ (Task 4).
- **Placeholder scan:** none found.
- **Type consistency:** `parseFlacBlocks(reader: ByteReader, start: Long, end: Long): List<BoxNode>` is used identically in Task 1 (definition), Task 2 (`ParseFile.kt` call site), matching `parseEbmlElements`'s exact signature shape. Field names (`sample_rate`, `channels`, `bits_per_sample`, `total_samples`) are consistent between Task 1's `FlacWalker.kt` and Task 3's `buildFlacSummary`/`FlacMediaSummaryBuilderTest.kt`.
