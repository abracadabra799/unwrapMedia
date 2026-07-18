# JPEG File Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Recognize JPEG files at `parseFile`'s entry point and render their marker-segment structure in the existing tree UI, with detailed decoding for `SOF` (dimensions/components) and `APP1` (Exif/XMP).

**Architecture:** A new `JpegWalker.kt` walks SOI→EOI producing the same `List<BoxNode>` shape `parseBoxes` already produces for ISOBMFF, so zero UI code changes are needed. `ParseFile.kt` gains a 2-byte magic sniff to route to the new walker or the existing ISOBMFF path. `APP1` Exif decoding reuses the existing TIFF/IFD-walking logic in `ExifDecoder.kt` via a small extraction (`decodeTiff`), avoiding any duplication of IFD/GPS/MakerNote logic.

**Tech Stack:** Kotlin 2.0.21, JVM, kotlin.test for unit tests, existing `ByteReader`/`BoxNode`/`BoxField` data model.

## Global Constraints

- No decoding of `DQT`, `DHT`, `SOS` fields, or any `APPn` other than `APP1`'s Exif/XMP — shown as plain structural nodes (marker/offset/size only).
- No JPEG XL, motion-JPEG, JPEG 2000, or PNG support.
- No decoding of `APP2`/`APP13`/`APP14`.
- JPEG segment length fields are big-endian and their value **includes** the 2 length bytes themselves — total segment size (excluding SOI/EOI/RSTn/TEM, which have no length field) = `2 (marker bytes) + length`.
- Never throw on malformed/truncated input — bail out with a warning on the last-parsed node, matching `parseBoxes`'s existing convention in `BoxWalker.kt`.
- All new code follows this codebase's existing conventions exactly: `BoxNode`/`BoxField` construction style, `byteReaderOf` test helper, warnings-list convention, absolute file offsets everywhere.

---

### Task 1: Extract `decodeTiff` from `ExifDecoder.kt`

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/ExifDecoder.kt:89-102`
- Test: `app/src/test/kotlin/com/multiviewer/parser/ExifDecoderTest.kt`

**Interfaces:**
- Consumes: nothing new — pure refactor of existing private/public functions in `ExifDecoder.kt`.
- Produces: `fun decodeTiff(reader: ByteReader, tiffStart: Long, itemEnd: Long): List<BoxNode>` — a new public function that Task 3's `APP1` Exif decoder will call directly with `tiffStart` = the file offset immediately after the 6-byte `"Exif\0\0"` prefix, and `itemEnd` = the end of the `APP1` segment's payload.

This is a behavior-preserving refactor: `decodeExif` currently computes `tiffStart` from a 4-byte offset field (HEIF's `ExifDataBlock` framing) and then does everything else inline. Everything from the TIFF-header bounds check onward moves into `decodeTiff`, unchanged. `decodeExif` becomes a 6-line wrapper.

- [ ] **Step 1: Write the failing regression test for `decodeTiff`**

Add this test to `app/src/test/kotlin/com/multiviewer/parser/ExifDecoderTest.kt` (inside the existing `ExifDecoderTest` class, after the last test):

```kotlin
    @Test
    fun `decodeTiff decodes a standalone TIFF blob with no HEIF offset wrapper`() {
        val tiff = byteArrayOf(
            0x49, 0x49, 0x2a, 0x00, 0x08, 0x00, 0x00, 0x00,
            0x01, 0x00, 0x0f, 0x01, 0x02, 0x00, 0x04, 0x00,
            0x00, 0x00, 0x41, 0x42, 0x43, 0x00, 0x00, 0x00,
            0x00, 0x00,
        )
        val reader = byteReaderOf(tiff)
        val ifds = decodeTiff(reader, 0, tiff.size.toLong())

        assertEquals(1, ifds.size)
        assertEquals("IFD0", ifds[0].type)
        assertEquals("ABC", ifds[0].fields.first { it.name == "Make" }.value)
        reader.close()
    }
```

This is a 26-byte standalone TIFF blob (byte-order mark `"II"`, magic 42, IFD0 offset 8) with IFD0 containing exactly one entry: tag `0x010F` (Make), type 2 (ASCII), count 4, inline value `"ABC\0"`. Unlike every other test in this file, it calls `decodeTiff` directly (no 4-byte HEIF offset field prefix) — this is the shape `APP1` Exif segments actually have.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.multiviewer.parser.ExifDecoderTest" -i`
Expected: FAIL — `decodeTiff` is unresolved (function doesn't exist yet).

- [ ] **Step 3: Extract `decodeTiff` from `decodeExif`**

Replace the current `decodeExif` function (lines 89-102 of `ExifDecoder.kt`):

```kotlin
fun decodeExif(reader: ByteReader, itemStart: Long, itemEnd: Long): List<BoxNode> {
    if (itemEnd - itemStart < 4) return emptyList()
    val tiffHeaderOffsetField = reader.readUInt32(itemStart)
    val tiffStart = itemStart + 4 + tiffHeaderOffsetField
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
fun decodeExif(reader: ByteReader, itemStart: Long, itemEnd: Long): List<BoxNode> {
    if (itemEnd - itemStart < 4) return emptyList()
    val tiffHeaderOffsetField = reader.readUInt32(itemStart)
    val tiffStart = itemStart + 4 + tiffHeaderOffsetField
    return decodeTiff(reader, tiffStart, itemEnd)
}

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

No other function in the file changes — `decodeIfd`, `decodeMakerNote`, `formatTiffValue`, `readUInt16Endian`, `readUInt32Endian`, and all tag-name maps stay exactly as they are.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.multiviewer.parser.ExifDecoderTest" -i`
Expected: PASS — all 5 tests (4 pre-existing `decodeExif` tests + the new `decodeTiff` test) pass. The pre-existing tests passing unchanged confirms this refactor is behavior-preserving.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/ExifDecoder.kt app/src/test/kotlin/com/multiviewer/parser/ExifDecoderTest.kt
git commit -m "refactor: extract decodeTiff from decodeExif for reuse by JPEG APP1 decoding"
```

---

### Task 2: JPEG marker segment walker (`JpegWalker.kt`)

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/JpegWalker.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/JpegWalkerTest.kt`

**Interfaces:**
- Consumes: `ByteReader` (`readUInt8(offset: Long): Int`, `readUInt16(offset: Long): Int`, `readBytes(offset: Long, len: Int): ByteArray`), `BoxNode`/`BoxField` from `BoxNode.kt`.
- Produces: `fun parseJpegSegments(reader: ByteReader, start: Long, end: Long): List<BoxNode>` — Task 3 calls this from `ParseFile.kt`'s dispatch branch, and Task 3 also extends this same file's `decodeSegment` to add `APP1` handling.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/multiviewer/parser/JpegWalkerTest.kt`:

```kotlin
package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JpegWalkerTest {
    @Test
    fun `walks SOI, a generic length-bearing marker, and EOI`() {
        val bytes = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xdb.toByte(), 0x00, 0x05, 0x00, 0x01,
            0x02, 0xff.toByte(), 0xd9.toByte(),
        )
        val reader = byteReaderOf(bytes)
        val segments = parseJpegSegments(reader, 0, bytes.size.toLong())

        assertEquals(listOf("SOI", "DQT", "EOI"), segments.map { it.type })
        assertEquals(0L, segments[0].offset)
        assertEquals(2L, segments[0].size)
        assertEquals(2L, segments[1].offset)
        assertEquals(7L, segments[1].size)
        assertEquals(9L, segments[2].offset)
        assertEquals(2L, segments[2].size)
        reader.close()
    }

    @Test
    fun `decodes SOF0 dimensions and a single component`() {
        val bytes = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xc0.toByte(), 0x00, 0x0b, 0x08, 0x01,
            0xe0.toByte(), 0x02, 0x80.toByte(), 0x01, 0x01, 0x11, 0x00, 0xff.toByte(),
            0xd9.toByte(),
        )
        val reader = byteReaderOf(bytes)
        val segments = parseJpegSegments(reader, 0, bytes.size.toLong())

        assertEquals(listOf("SOI", "SOF0", "EOI"), segments.map { it.type })
        val sof0 = segments[1]
        assertEquals(13L, sof0.size)
        assertEquals("8", sof0.fields.first { it.name == "precision" }.value)
        assertEquals("480", sof0.fields.first { it.name == "height" }.value)
        assertEquals("640", sof0.fields.first { it.name == "width" }.value)
        assertEquals("1", sof0.fields.first { it.name == "num_components" }.value)
        assertEquals("1", sof0.fields.first { it.name == "component_id" }.value)
        assertEquals("0x11", sof0.fields.first { it.name == "sampling_factors" }.value)
        assertEquals("0", sof0.fields.first { it.name == "quantization_table" }.value)
        assertEquals("640x480, 1 component(s)", sof0.summary)
        reader.close()
    }

    @Test
    fun `SOS scan-data skip does not stop at a byte-stuffed FF00 or an RST marker`() {
        val bytes = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xda.toByte(), 0x00, 0x08, 0x01, 0x01,
            0x00, 0x00, 0x3f, 0x00, 0xab.toByte(), 0xff.toByte(), 0x00, 0xcd.toByte(),
            0xff.toByte(), 0xd0.toByte(), 0xef.toByte(), 0xff.toByte(), 0xd9.toByte(),
        )
        val reader = byteReaderOf(bytes)
        val segments = parseJpegSegments(reader, 0, bytes.size.toLong())

        assertEquals(listOf("SOI", "SOS", "EOI"), segments.map { it.type })
        val sos = segments[1]
        assertEquals(2L, sos.offset)
        assertEquals(17L, sos.size)
        val eoi = segments[2]
        assertEquals(19L, eoi.offset)
        reader.close()
    }

    @Test
    fun `a byte that is not 0xFF where a marker is expected produces a warning and stops`() {
        val bytes = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0x00, 0x01)
        val reader = byteReaderOf(bytes)
        val segments = parseJpegSegments(reader, 0, bytes.size.toLong())

        assertEquals(listOf("SOI", "?"), segments.map { it.type })
        assertTrue(segments[1].warnings.isNotEmpty())
        reader.close()
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.multiviewer.parser.JpegWalkerTest" -i`
Expected: FAIL — `parseJpegSegments` is unresolved (file doesn't exist yet).

- [ ] **Step 3: Implement `JpegWalker.kt`**

Create `app/src/main/kotlin/com/multiviewer/parser/JpegWalker.kt`:

```kotlin
package com.multiviewer.parser

private val MARKER_NAMES: Map<Int, String> = buildMap {
    put(0x01, "TEM")
    for (m in 0xC0..0xC3) put(m, "SOF${m - 0xC0}")
    put(0xC4, "DHT")
    for (m in 0xC5..0xC7) put(m, "SOF${m - 0xC0}")
    for (m in 0xC9..0xCB) put(m, "SOF${m - 0xC0}")
    put(0xCC, "DAC")
    for (m in 0xCD..0xCF) put(m, "SOF${m - 0xC0}")
    for (m in 0xD0..0xD7) put(m, "RST${m - 0xD0}")
    put(0xD8, "SOI")
    put(0xD9, "EOI")
    put(0xDA, "SOS")
    put(0xDB, "DQT")
    put(0xDC, "DNL")
    put(0xDD, "DRI")
    put(0xDE, "DHP")
    put(0xDF, "EXP")
    for (m in 0xE0..0xEF) put(m, "APP${m - 0xE0}")
    put(0xFE, "COM")
}

private val SOF_MARKERS = setOf(0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF)

private val NO_PAYLOAD_MARKERS = setOf(0x01, 0xD8, 0xD9) + (0xD0..0xD7).toSet()

private fun markerName(marker: Int): String =
    MARKER_NAMES[marker] ?: "0x${marker.toString(16).padStart(2, '0').uppercase()}"

fun parseJpegSegments(reader: ByteReader, start: Long, end: Long): List<BoxNode> {
    val result = mutableListOf<BoxNode>()
    var pos = start
    while (pos < end) {
        val remaining = end - pos
        if (remaining < 2) {
            result.add(BoxNode("?", pos, 0, remaining, warnings = listOf("Trailing $remaining byte(s): too short for a marker")))
            break
        }

        val markerPrefix = reader.readUInt8(pos)
        if (markerPrefix != 0xFF) {
            result.add(
                BoxNode(
                    "?", pos, 0, remaining,
                    warnings = listOf("Expected marker prefix 0xFF, found 0x${markerPrefix.toString(16).padStart(2, '0')}"),
                ),
            )
            break
        }
        val marker = reader.readUInt8(pos + 1)

        if (marker in NO_PAYLOAD_MARKERS) {
            result.add(BoxNode(markerName(marker), pos, 2, 2))
            pos += 2
            continue
        }

        if (remaining < 4) {
            result.add(
                BoxNode(markerName(marker), pos, 2, remaining, warnings = listOf("Trailing $remaining byte(s): too short for a segment length")),
            )
            break
        }
        val length = reader.readUInt16(pos + 2)
        if (length < 2) {
            result.add(
                BoxNode(markerName(marker), pos, 2, remaining, warnings = listOf("Declared length $length is smaller than the 2 length bytes themselves")),
            )
            break
        }
        val declaredSize = 2L + length
        if (pos + declaredSize > end) {
            result.add(
                BoxNode(markerName(marker), pos, 2, remaining, warnings = listOf("Declared length $length extends past the end of the file")),
            )
            break
        }

        var totalSize = declaredSize
        if (marker == 0xDA) {
            var scanPos = pos + declaredSize
            while (scanPos + 2 <= end) {
                if (reader.readUInt8(scanPos) == 0xFF) {
                    val next = reader.readUInt8(scanPos + 1)
                    if (next != 0x00 && next !in 0xD0..0xD7) break
                }
                scanPos += 1
            }
            if (scanPos + 2 > end) scanPos = end
            totalSize = scanPos - pos
        }

        result.add(decodeSegment(reader, marker, pos, declaredSize, totalSize))
        pos += totalSize
    }
    return result
}

private fun decodeSegment(reader: ByteReader, marker: Int, offset: Long, declaredSize: Long, totalSize: Long): BoxNode {
    val name = markerName(marker)
    if (marker in SOF_MARKERS) {
        return decodeSof(reader, name, offset, declaredSize, totalSize)
    }
    return BoxNode(type = name, offset = offset, headerSize = 4, size = totalSize)
}

private fun decodeSof(reader: ByteReader, name: String, offset: Long, declaredSize: Long, totalSize: Long): BoxNode {
    val payloadStart = offset + 4
    val payloadEnd = offset + declaredSize
    if (payloadEnd - payloadStart < 6) {
        return BoxNode(name, offset, 4, totalSize, warnings = listOf("Segment too short to contain SOF fields"))
    }
    val precision = reader.readUInt8(payloadStart)
    val height = reader.readUInt16(payloadStart + 1)
    val width = reader.readUInt16(payloadStart + 3)
    val numComponents = reader.readUInt8(payloadStart + 5)
    val fields = mutableListOf(
        BoxField("precision", precision.toString(), payloadStart, 1),
        BoxField("height", height.toString(), payloadStart + 1, 2),
        BoxField("width", width.toString(), payloadStart + 3, 2),
        BoxField("num_components", numComponents.toString(), payloadStart + 5, 1),
    )
    var pos = payloadStart + 6
    var componentCount = 0
    for (i in 0 until numComponents) {
        if (pos + 3 > payloadEnd) break
        val componentId = reader.readUInt8(pos)
        val samplingFactors = reader.readUInt8(pos + 1)
        val quantizationTable = reader.readUInt8(pos + 2)
        fields.add(BoxField("component_id", componentId.toString(), pos, 1))
        fields.add(BoxField("sampling_factors", "0x${samplingFactors.toString(16).padStart(2, '0')}", pos + 1, 1))
        fields.add(BoxField("quantization_table", quantizationTable.toString(), pos + 2, 1))
        componentCount += 1
        pos += 3
    }
    return BoxNode(
        type = name, offset = offset, headerSize = 4, size = totalSize,
        fields = fields,
        summary = "${width}x${height}, $componentCount component(s)",
    )
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.multiviewer.parser.JpegWalkerTest" -i`
Expected: PASS — all 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/JpegWalker.kt app/src/test/kotlin/com/multiviewer/parser/JpegWalkerTest.kt
git commit -m "feat: add JPEG marker segment walker with SOF0-15 dimension decoding"
```

---

### Task 3: `APP1` Exif/XMP decoding and `ParseFile.kt` dispatch

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/JpegWalker.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/parser/ParseFile.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/JpegWalkerTest.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/ParseFileIntegrationTest.kt`

**Interfaces:**
- Consumes: `decodeTiff(reader: ByteReader, tiffStart: Long, itemEnd: Long): List<BoxNode>` (Task 1), `parseJpegSegments(reader: ByteReader, start: Long, end: Long): List<BoxNode>` (Task 2), `parseBoxes(reader: ByteReader, rangeStart: Long, rangeEnd: Long): List<BoxNode>` (existing, `BoxWalker.kt`).
- Produces: nothing new for later tasks — this is the final integration task.

- [ ] **Step 1: Write the failing tests**

Add these tests to `app/src/test/kotlin/com/multiviewer/parser/JpegWalkerTest.kt` (inside the existing `JpegWalkerTest` class):

```kotlin
    @Test
    fun `APP1 Exif payload delegates to decodeTiff`() {
        val bytes = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe1.toByte(), 0x00, 0x22, 0x45, 0x78,
            0x69, 0x66, 0x00, 0x00, 0x49, 0x49, 0x2a, 0x00,
            0x08, 0x00, 0x00, 0x00, 0x01, 0x00, 0x0f, 0x01,
            0x02, 0x00, 0x04, 0x00, 0x00, 0x00, 0x41, 0x42,
            0x43, 0x00, 0x00, 0x00, 0x00, 0x00, 0xff.toByte(), 0xd9.toByte(),
        )
        val reader = byteReaderOf(bytes)
        val segments = parseJpegSegments(reader, 0, bytes.size.toLong())

        assertEquals(listOf("SOI", "APP1", "EOI"), segments.map { it.type })
        val app1 = segments[1]
        assertEquals(36L, app1.size)
        val ifd0 = app1.children.single()
        assertEquals("IFD0", ifd0.type)
        assertEquals("ABC", ifd0.fields.first { it.name == "Make" }.value)
        reader.close()
    }

    @Test
    fun `APP1 XMP payload is exposed as a single text field`() {
        val bytes = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe1.toByte(), 0x00, 0x2b, 0x68, 0x74,
            0x74, 0x70, 0x3a, 0x2f, 0x2f, 0x6e, 0x73, 0x2e,
            0x61, 0x64, 0x6f, 0x62, 0x65, 0x2e, 0x63, 0x6f,
            0x6d, 0x2f, 0x78, 0x61, 0x70, 0x2f, 0x31, 0x2e,
            0x30, 0x2f, 0x00, 0x3c, 0x78, 0x3a, 0x78, 0x6d,
            0x70, 0x6d, 0x65, 0x74, 0x61, 0x2f, 0x3e, 0xff.toByte(),
            0xd9.toByte(),
        )
        val reader = byteReaderOf(bytes)
        val segments = parseJpegSegments(reader, 0, bytes.size.toLong())

        assertEquals(listOf("SOI", "APP1", "EOI"), segments.map { it.type })
        val app1 = segments[1]
        assertEquals("<x:xmpmeta/>", app1.fields.first { it.name == "xmp" }.value)
        assertEquals("XMP (12 chars)", app1.summary)
        reader.close()
    }
```

Add this test to `app/src/test/kotlin/com/multiviewer/parser/ParseFileIntegrationTest.kt` (inside the existing `ParseFileIntegrationTest` class):

```kotlin
    @Test
    fun `parses a JPEG file via the JPEG marker-segment path, not the ISOBMFF path`() {
        val bytes = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte())
        val tmp = File.createTempFile("multiviewer-jpeg", ".jpg")
        tmp.deleteOnExit()
        tmp.writeBytes(bytes)

        val root = parseFile(tmp)

        assertEquals(listOf("SOI", "EOI"), root.children.map { it.type })
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.multiviewer.parser.JpegWalkerTest" --tests "com.multiviewer.parser.ParseFileIntegrationTest" -i`
Expected: FAIL — the `APP1` tests fail because `APP1` segments currently decode as plain structural nodes with no `children`/`xmp` field (`app1.children.single()` throws `NoSuchElementException`; `app1.fields.first { it.name == "xmp" }` throws). The `ParseFile` dispatch test fails because `parseFile` currently misreads the JPEG bytes as an ISOBMFF box header (`root.children` will not be `["SOI", "EOI"]`).

- [ ] **Step 3: Add `APP1` decoding to `JpegWalker.kt`**

In `app/src/main/kotlin/com/multiviewer/parser/JpegWalker.kt`, change `decodeSegment` from:

```kotlin
private fun decodeSegment(reader: ByteReader, marker: Int, offset: Long, declaredSize: Long, totalSize: Long): BoxNode {
    val name = markerName(marker)
    if (marker in SOF_MARKERS) {
        return decodeSof(reader, name, offset, declaredSize, totalSize)
    }
    return BoxNode(type = name, offset = offset, headerSize = 4, size = totalSize)
}
```

to:

```kotlin
private fun decodeSegment(reader: ByteReader, marker: Int, offset: Long, declaredSize: Long, totalSize: Long): BoxNode {
    val name = markerName(marker)
    return when {
        marker in SOF_MARKERS -> decodeSof(reader, name, offset, declaredSize, totalSize)
        marker == 0xE1 -> decodeApp1(reader, offset, declaredSize, totalSize)
        else -> BoxNode(type = name, offset = offset, headerSize = 4, size = totalSize)
    }
}
```

Then add this new function after `decodeSof` (end of the file):

```kotlin
private val EXIF_PREFIX = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0x00, 0x00) // "Exif" + 2 NUL bytes
private val XMP_IDENTIFIER = "http://ns.adobe.com/xap/1.0/".toByteArray(Charsets.US_ASCII)

private fun decodeApp1(reader: ByteReader, offset: Long, declaredSize: Long, totalSize: Long): BoxNode {
    val payloadStart = offset + 4
    val payloadEnd = offset + declaredSize

    if (payloadEnd - payloadStart >= EXIF_PREFIX.size &&
        reader.readBytes(payloadStart, EXIF_PREFIX.size).contentEquals(EXIF_PREFIX)
    ) {
        val tiffStart = payloadStart + EXIF_PREFIX.size
        val children = decodeTiff(reader, tiffStart, payloadEnd)
        return BoxNode(type = "APP1", offset = offset, headerSize = 4, size = totalSize, children = children, summary = "Exif metadata")
    }

    val xmpPrefixSize = XMP_IDENTIFIER.size + 1
    if (payloadEnd - payloadStart >= xmpPrefixSize &&
        reader.readBytes(payloadStart, XMP_IDENTIFIER.size).contentEquals(XMP_IDENTIFIER)
    ) {
        val textStart = payloadStart + xmpPrefixSize
        val textLength = payloadEnd - textStart
        val text = String(reader.readBytes(textStart, textLength.toInt()), Charsets.UTF_8)
        return BoxNode(
            type = "APP1", offset = offset, headerSize = 4, size = totalSize,
            fields = listOf(BoxField("xmp", text, textStart, textLength)),
            summary = "XMP (${text.length} chars)",
        )
    }

    return BoxNode(type = "APP1", offset = offset, headerSize = 4, size = totalSize)
}
```

- [ ] **Step 4: Add file-type dispatch to `ParseFile.kt`**

Replace the full contents of `app/src/main/kotlin/com/multiviewer/parser/ParseFile.kt`:

```kotlin
package com.multiviewer.parser

import java.io.File

fun parseFile(path: File): BoxNode {
    registerAllDecoders()
    ByteReader.open(path).use { reader ->
        val isJpeg = reader.length >= 2 && reader.readUInt8(0) == 0xFF && reader.readUInt8(1) == 0xD8
        val children = if (isJpeg) {
            parseJpegSegments(reader, 0, reader.length)
        } else {
            parseBoxes(reader, 0, reader.length)
        }
        return BoxNode(type = "root", offset = 0, headerSize = 0, size = reader.length, children = children)
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests "com.multiviewer.parser.JpegWalkerTest" --tests "com.multiviewer.parser.ParseFileIntegrationTest" -i`
Expected: PASS — all tests pass, including the pre-existing ISOBMFF `ParseFileIntegrationTest` test (confirming the dispatch change doesn't affect non-JPEG files).

- [ ] **Step 6: Run the full test suite**

Run: `./gradlew test -i`
Expected: PASS — every test in the project passes, confirming Task 1's `ExifDecoder` refactor and the new JPEG code haven't regressed anything.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/JpegWalker.kt app/src/main/kotlin/com/multiviewer/parser/ParseFile.kt app/src/test/kotlin/com/multiviewer/parser/JpegWalkerTest.kt app/src/test/kotlin/com/multiviewer/parser/ParseFileIntegrationTest.kt
git commit -m "feat: decode APP1 Exif/XMP in JPEG files and dispatch JPEG files at parseFile"
```
