# JPEG Overview Detail Section Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a JPEGsnoop-depth "JPEG Detail" section to the Overview tab for JPEG files, visible the moment a JPEG is opened with no tree navigation required.

**Architecture:** Two new marker decoders (`DRI`, `APP14`) in `JpegWalker.kt` fill gaps in already-parsed structural data; a new `buildJpegDetail(root)` function in `MediaSummaryBuilder.kt` assembles a 7-field "JPEG Detail" `SummarySection` from that data (both new and pre-existing) and is wired into `buildImageSummary`.

**Tech Stack:** Kotlin, existing `BoxNode`/`BoxField`/`ByteReader` parser primitives, `kotlin.test` for unit tests.

## Global Constraints

- Reference design doc: `docs/superpowers/specs/2026-08-06-jpeg-overview-detail-design.md`.
- Section title is exactly `"JPEG Detail"`, inserted into `buildImageSummary`'s section list immediately after the existing "Image" section.
- Field labels are exactly: `"Encoding"`, `"Precision"`, `"Quality Estimate"`, `"Huffman Tables"`, `"Adobe Color Transform"`, `"Restart Interval"`, `"Comment"`.
- Every field except Encoding/Precision is independently optional — a missing prerequisite (no DQT, no DHT, no APP14, no DRI, no COM) omits only that field, never the whole section or other fields.
- `buildJpegDetail` returns `null` only when there is no SOF node at all (matches this file's existing `null`-returning `buildXxxDetail` convention).
- No changes to any non-JPEG code path, the Detailed Properties (tree) tab, or any UI/Compose file — the Overview tab already renders arbitrary `SummarySection` lists generically.
- 0=Luminance / 1=Chrominance is a convention (not a hard spec rule) already used elsewhere in `JpegWalker.kt` (`dqtDestinationLabel`) — reuse the same convention for both DQT and DHT destination-id interpretation in this plan.

---

### Task 1: New JPEG marker decoders (DRI, APP14)

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/JpegWalker.kt:144` (add two `when` branches), and append two new functions after `decodeApp0` (currently ending at line 477, right before `tryDecodeSefdTrailer` at line 479).
- Test: `app/src/test/kotlin/com/multiviewer/parser/JpegWalkerTest.kt` (append new test cases at the end of the class, before the final closing brace)

**Interfaces:**
- Produces: a `DRI`-type `BoxNode` with a `BoxField("restart_interval", <decimal string>, ...)` field when the segment is well-formed (≥2 payload bytes); a `BoxNode` with `warnings` and no fields otherwise.
- Produces: an `APP14`-type `BoxNode` with a `BoxField("color_transform", <decimal string 0/1/2>, ...)` field when the payload starts with the 5-byte `"Adobe"` prefix and has at least 12 payload bytes; a bare `BoxNode` with no fields otherwise (same fallback shape as `decodeApp0`'s non-JFIF case).
- Task 2 consumes these two field names (`restart_interval` on `DRI` nodes, `color_transform` on `APP14` nodes) by name — do not rename them.

- [ ] **Step 1: Write the failing tests**

Open `app/src/test/kotlin/com/multiviewer/parser/JpegWalkerTest.kt`. Add these four test functions inside the `JpegWalkerTest` class, right before its final closing `}`:

```kotlin
    @Test
    fun `DRI decodes the restart interval`() {
        val bytes = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xdd.toByte(), 0x00, 0x04, 0x00, 0x10,
            0xff.toByte(), 0xd9.toByte(),
        )
        val reader = byteReaderOf(bytes)
        val segments = parseJpegSegments(reader, 0, bytes.size.toLong())

        assertEquals(listOf("SOI", "DRI", "EOI"), segments.map { it.type })
        assertEquals("16", segments[1].fields.first { it.name == "restart_interval" }.value)
        reader.close()
    }

    @Test
    fun `DRI with a truncated payload produces a warning and no fields`() {
        val bytes = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xdd.toByte(), 0x00, 0x03, 0x00,
            0xff.toByte(), 0xd9.toByte(),
        )
        val reader = byteReaderOf(bytes)
        val segments = parseJpegSegments(reader, 0, bytes.size.toLong())

        val dri = segments[1]
        assertEquals(0, dri.fields.size)
        assertTrue(dri.warnings.isNotEmpty())
        reader.close()
    }

    @Test
    fun `APP14 decodes the Adobe color_transform byte`() {
        val bytes = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(),
            0xff.toByte(), 0xee.toByte(), 0x00, 0x0e,
            0x41, 0x64, 0x6f, 0x62, 0x65, 0x00, 0x64, 0x00, 0x00, 0x00, 0x00, 0x01,
            0xff.toByte(), 0xd9.toByte(),
        )
        val reader = byteReaderOf(bytes)
        val segments = parseJpegSegments(reader, 0, bytes.size.toLong())

        assertEquals(listOf("SOI", "APP14", "EOI"), segments.map { it.type })
        assertEquals("1", segments[1].fields.first { it.name == "color_transform" }.value)
        reader.close()
    }

    @Test
    fun `a non-Adobe APP14 falls back to a plain structural node`() {
        val bytes = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(),
            0xff.toByte(), 0xee.toByte(), 0x00, 0x0e,
            0x58, 0x58, 0x58, 0x58, 0x58, 0x58, 0x58, 0x58, 0x58, 0x58, 0x58, 0x58,
            0xff.toByte(), 0xd9.toByte(),
        )
        val reader = byteReaderOf(bytes)
        val segments = parseJpegSegments(reader, 0, bytes.size.toLong())

        assertEquals(listOf("SOI", "APP14", "EOI"), segments.map { it.type })
        assertEquals(0, segments[1].fields.size)
        reader.close()
    }
```

- [ ] **Step 2: Run tests to verify they fail**

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:test --tests "com.multiviewer.parser.JpegWalkerTest"
```
Expected: FAIL — `DRI` and `APP14` currently fall through `decodeSegment`'s `else` branch, producing a bare `BoxNode` with zero fields, so `segments[1].fields.first { it.name == "restart_interval" }` and `.first { it.name == "color_transform" }` throw `NoSuchElementException`.

- [ ] **Step 3: Add the two `when` branches**

In `app/src/main/kotlin/com/multiviewer/parser/JpegWalker.kt`, find `decodeSegment` (around line 134):

```kotlin
private fun decodeSegment(reader: ByteReader, marker: Int, offset: Long, declaredSize: Long, totalSize: Long): BoxNode {
    val name = markerName(marker)
    return when {
        marker in SOF_MARKERS -> decodeSof(reader, name, offset, declaredSize, totalSize)
        marker == 0xE1 -> decodeApp1(reader, name, offset, declaredSize, totalSize)
        marker == 0xDB -> decodeDqt(reader, name, offset, declaredSize, totalSize)
        marker == 0xC4 -> decodeDht(reader, name, offset, declaredSize, totalSize)
        marker == 0xDA -> decodeSos(reader, name, offset, declaredSize, totalSize)
        marker == 0xFE -> decodeCom(reader, name, offset, declaredSize, totalSize)
        marker == 0xE0 -> decodeApp0(reader, name, offset, declaredSize, totalSize)
        else -> BoxNode(type = name, offset = offset, headerSize = 4, size = totalSize)
    }
}
```

Replace it with (two new branches added):

```kotlin
private fun decodeSegment(reader: ByteReader, marker: Int, offset: Long, declaredSize: Long, totalSize: Long): BoxNode {
    val name = markerName(marker)
    return when {
        marker in SOF_MARKERS -> decodeSof(reader, name, offset, declaredSize, totalSize)
        marker == 0xE1 -> decodeApp1(reader, name, offset, declaredSize, totalSize)
        marker == 0xDB -> decodeDqt(reader, name, offset, declaredSize, totalSize)
        marker == 0xC4 -> decodeDht(reader, name, offset, declaredSize, totalSize)
        marker == 0xDA -> decodeSos(reader, name, offset, declaredSize, totalSize)
        marker == 0xFE -> decodeCom(reader, name, offset, declaredSize, totalSize)
        marker == 0xE0 -> decodeApp0(reader, name, offset, declaredSize, totalSize)
        marker == 0xDD -> decodeDri(reader, name, offset, declaredSize, totalSize)
        marker == 0xEE -> decodeApp14(reader, name, offset, declaredSize, totalSize)
        else -> BoxNode(type = name, offset = offset, headerSize = 4, size = totalSize)
    }
}
```

- [ ] **Step 4: Add the two decoder functions**

In the same file, find the end of `decodeApp0` (ends with `return BoxNode(type = name, offset = offset, headerSize = 4, size = totalSize)` followed by a closing `}`, right before `private fun tryDecodeSefdTrailer`). Insert these two new functions immediately after `decodeApp0`'s closing `}` and before `tryDecodeSefdTrailer`:

```kotlin
private fun decodeDri(reader: ByteReader, name: String, offset: Long, declaredSize: Long, totalSize: Long): BoxNode {
    val payloadStart = offset + 4
    val payloadEnd = offset + declaredSize
    if (payloadEnd - payloadStart < 2) {
        return BoxNode(name, offset, 4, totalSize, warnings = listOf("Segment too short to contain a restart interval"))
    }
    val restartInterval = reader.readUInt16(payloadStart)
    return BoxNode(
        type = name, offset = offset, headerSize = 4, size = totalSize,
        fields = listOf(BoxField("restart_interval", restartInterval.toString(), payloadStart, 2)),
        summary = "restart_interval=$restartInterval",
    )
}

private val ADOBE_PREFIX = byteArrayOf(0x41, 0x64, 0x6F, 0x62, 0x65) // "Adobe"

private fun decodeApp14(reader: ByteReader, name: String, offset: Long, declaredSize: Long, totalSize: Long): BoxNode {
    val payloadStart = offset + 4
    val payloadEnd = offset + declaredSize
    val bodySize = 7 // version(2) + flags0(2) + flags1(2) + transform(1)
    val hasAdobePrefix = payloadEnd - payloadStart >= ADOBE_PREFIX.size &&
        reader.readBytes(payloadStart, ADOBE_PREFIX.size).contentEquals(ADOBE_PREFIX)
    if (!hasAdobePrefix || payloadEnd - payloadStart < ADOBE_PREFIX.size + bodySize) {
        return BoxNode(type = name, offset = offset, headerSize = 4, size = totalSize)
    }
    val transformPos = payloadStart + ADOBE_PREFIX.size + 6 // skip version(2)+flags0(2)+flags1(2)
    val colorTransform = reader.readUInt8(transformPos)
    return BoxNode(
        type = name, offset = offset, headerSize = 4, size = totalSize,
        fields = listOf(BoxField("color_transform", colorTransform.toString(), transformPos, 1)),
        summary = "Adobe, color_transform=$colorTransform",
    )
}
```

- [ ] **Step 5: Run tests to verify they pass**

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:test --tests "com.multiviewer.parser.JpegWalkerTest"
```
Expected: PASS, all 4 new tests plus all pre-existing `JpegWalkerTest` cases.

- [ ] **Step 6: Run the full suite and commit**

```
./gradlew :app:test
git add app/src/main/kotlin/com/multiviewer/parser/JpegWalker.kt app/src/test/kotlin/com/multiviewer/parser/JpegWalkerTest.kt
git commit -m "Add DRI and APP14 (Adobe) marker decoders to JpegWalker"
```
Expected: full suite passes, 0 failures, 0 regressions.

---

### Task 2: "JPEG Detail" Overview section

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt:121` (wire the new section into `buildImageSummary`), and append new private constants/functions anywhere in the file (convention in this file is top-of-file for constant tables, e.g. `CODEC_DISPLAY_NAMES` at line 6 — add the new tables and `buildJpegDetail` function right after `buildImageDetail` ends at line 282).
- Test: `app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt` (append new test cases at the end of the class, before the final closing brace)

**Interfaces:**
- Consumes from Task 1: `BoxField("restart_interval", ...)` on `DRI` nodes, `BoxField("color_transform", ...)` on `APP14` nodes.
- Consumes pre-existing fields (already present before this plan, unchanged): `precision`/`num_components` on `SOF*` nodes, `quality_estimate`/`destination_id` on `QuantizationTable` nodes (children of `DQT` nodes), `class`/`destination_id`/`bit_counts`/`codes_length_NN` on `HuffmanTable` nodes (children of `DHT` nodes), `comment` on `COM` nodes.
- Produces: `private fun buildJpegDetail(root: BoxNode): SummarySection?`, called from `buildImageSummary`. No other file calls this function.

- [ ] **Step 1: Write the failing tests**

Open `app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt`. Add these test functions inside the `MediaSummaryBuilderTest` class, right before its final closing `}`:

```kotlin
    @Test
    fun `a JPEG with all-standard Huffman tables reports Huffman Tables as Standard`() {
        val sof0 = BoxNode(
            type = "SOF0", offset = 0, headerSize = 4, size = 19,
            fields = listOf(
                BoxField("precision", "8", 0, 1),
                BoxField("height", "480", 0, 2),
                BoxField("width", "640", 0, 2),
                BoxField("num_components", "3", 0, 1),
            ),
        )
        val dcLuminance = BoxNode(
            type = "HuffmanTable", offset = 0, headerSize = 0, size = 0,
            fields = listOf(
                BoxField("class", "DC", 0, 1),
                BoxField("destination_id", "0", 0, 1),
                BoxField("bit_counts", "0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0", 0, 16),
                BoxField("codes_length_02", "00", 0, 1),
                BoxField("codes_length_03", "01, 02, 03, 04, 05", 0, 5),
                BoxField("codes_length_04", "06", 0, 1),
                BoxField("codes_length_05", "07", 0, 1),
                BoxField("codes_length_06", "08", 0, 1),
                BoxField("codes_length_07", "09", 0, 1),
                BoxField("codes_length_08", "0A", 0, 1),
                BoxField("codes_length_09", "0B", 0, 1),
            ),
        )
        val dcChrominance = BoxNode(
            type = "HuffmanTable", offset = 0, headerSize = 0, size = 0,
            fields = listOf(
                BoxField("class", "DC", 0, 1),
                BoxField("destination_id", "1", 0, 1),
                BoxField("bit_counts", "0, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0", 0, 16),
                BoxField("codes_length_02", "00, 01, 02", 0, 3),
                BoxField("codes_length_03", "03", 0, 1),
                BoxField("codes_length_04", "04", 0, 1),
                BoxField("codes_length_05", "05", 0, 1),
                BoxField("codes_length_06", "06", 0, 1),
                BoxField("codes_length_07", "07", 0, 1),
                BoxField("codes_length_08", "08", 0, 1),
                BoxField("codes_length_09", "09", 0, 1),
                BoxField("codes_length_10", "0A", 0, 1),
                BoxField("codes_length_11", "0B", 0, 1),
            ),
        )
        val dht = BoxNode(type = "DHT", offset = 0, headerSize = 0, size = 0, children = listOf(dcLuminance, dcChrominance))
        val root = BoxNode(
            type = "root", offset = 0, headerSize = 0, size = 0,
            children = listOf(BoxNode(type = "SOI", offset = 0, headerSize = 2, size = 2), sof0, dht),
        )

        val summary = buildMediaSummary(root, tempFile())

        val jpegDetail = summary.sections.first { it.title == "JPEG Detail" }
        assertEquals("Standard", jpegDetail.fields.first { it.label == "Huffman Tables" }.value)
    }

    @Test
    fun `a JPEG with one non-standard Huffman table reports Custom Optimized with the mismatched table labeled`() {
        val sof0 = BoxNode(
            type = "SOF0", offset = 0, headerSize = 4, size = 19,
            fields = listOf(
                BoxField("precision", "8", 0, 1),
                BoxField("height", "480", 0, 2),
                BoxField("width", "640", 0, 2),
                BoxField("num_components", "3", 0, 1),
            ),
        )
        val dcLuminanceStandard = BoxNode(
            type = "HuffmanTable", offset = 0, headerSize = 0, size = 0,
            fields = listOf(
                BoxField("class", "DC", 0, 1),
                BoxField("destination_id", "0", 0, 1),
                BoxField("bit_counts", "0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0", 0, 16),
                BoxField("codes_length_02", "00", 0, 1),
                BoxField("codes_length_03", "01, 02, 03, 04, 05", 0, 5),
                BoxField("codes_length_04", "06", 0, 1),
                BoxField("codes_length_05", "07", 0, 1),
                BoxField("codes_length_06", "08", 0, 1),
                BoxField("codes_length_07", "09", 0, 1),
                BoxField("codes_length_08", "0A", 0, 1),
                BoxField("codes_length_09", "0B", 0, 1),
            ),
        )
        val dcChrominanceCustom = BoxNode(
            type = "HuffmanTable", offset = 0, headerSize = 0, size = 0,
            fields = listOf(
                BoxField("class", "DC", 0, 1),
                BoxField("destination_id", "1", 0, 1),
                BoxField("bit_counts", "0, 12, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0", 0, 16),
                BoxField("codes_length_02", "00, 01, 02, 03, 04, 05, 06, 07, 08, 09, 0A, 0B", 0, 12),
            ),
        )
        val dht = BoxNode(type = "DHT", offset = 0, headerSize = 0, size = 0, children = listOf(dcLuminanceStandard, dcChrominanceCustom))
        val root = BoxNode(
            type = "root", offset = 0, headerSize = 0, size = 0,
            children = listOf(BoxNode(type = "SOI", offset = 0, headerSize = 2, size = 2), sof0, dht),
        )

        val summary = buildMediaSummary(root, tempFile())

        val jpegDetail = summary.sections.first { it.title == "JPEG Detail" }
        assertEquals("Custom/Optimized (differs: DC1)", jpegDetail.fields.first { it.label == "Huffman Tables" }.value)
    }

    @Test
    fun `a JPEG with no DHT omits the Huffman Tables field but still reports Encoding and Precision`() {
        val sof0 = BoxNode(
            type = "SOF0", offset = 0, headerSize = 4, size = 19,
            fields = listOf(
                BoxField("precision", "8", 0, 1),
                BoxField("height", "480", 0, 2),
                BoxField("width", "640", 0, 2),
                BoxField("num_components", "3", 0, 1),
            ),
        )
        val root = BoxNode(
            type = "root", offset = 0, headerSize = 0, size = 0,
            children = listOf(BoxNode(type = "SOI", offset = 0, headerSize = 2, size = 2), sof0),
        )

        val summary = buildMediaSummary(root, tempFile())

        val jpegDetail = summary.sections.first { it.title == "JPEG Detail" }
        assertEquals(null, jpegDetail.fields.find { it.label == "Huffman Tables" })
        assertEquals("Baseline DCT (Huffman)", jpegDetail.fields.first { it.label == "Encoding" }.value)
        assertEquals("8-bit", jpegDetail.fields.first { it.label == "Precision" }.value)
    }

    @Test
    fun `a SOF2 JPEG reports Encoding as Progressive DCT (Huffman)`() {
        val sof2 = BoxNode(
            type = "SOF2", offset = 0, headerSize = 4, size = 19,
            fields = listOf(
                BoxField("precision", "8", 0, 1),
                BoxField("height", "480", 0, 2),
                BoxField("width", "640", 0, 2),
                BoxField("num_components", "3", 0, 1),
            ),
        )
        val root = BoxNode(
            type = "root", offset = 0, headerSize = 0, size = 0,
            children = listOf(BoxNode(type = "SOI", offset = 0, headerSize = 2, size = 2), sof2),
        )

        val summary = buildMediaSummary(root, tempFile())

        val jpegDetail = summary.sections.first { it.title == "JPEG Detail" }
        assertEquals("Progressive DCT (Huffman)", jpegDetail.fields.first { it.label == "Encoding" }.value)
    }

    @Test
    fun `Quality Estimate prefers the Luminance (destination_id 0) quantization table when both are present`() {
        val sof0 = BoxNode(
            type = "SOF0", offset = 0, headerSize = 4, size = 19,
            fields = listOf(
                BoxField("precision", "8", 0, 1),
                BoxField("height", "480", 0, 2),
                BoxField("width", "640", 0, 2),
                BoxField("num_components", "3", 0, 1),
            ),
        )
        val chrominanceTable = BoxNode(
            type = "QuantizationTable", offset = 0, headerSize = 0, size = 0,
            fields = listOf(
                BoxField("destination_id", "1 (Chrominance)", 0, 1),
                BoxField("quality_estimate", "~50%", 0, 65),
            ),
        )
        val luminanceTable = BoxNode(
            type = "QuantizationTable", offset = 0, headerSize = 0, size = 0,
            fields = listOf(
                BoxField("destination_id", "0 (Luminance)", 0, 1),
                BoxField("quality_estimate", "~90%", 0, 65),
            ),
        )
        // Chrominance listed first in tree order to prove selection is by destination_id, not position.
        val dqt = BoxNode(type = "DQT", offset = 0, headerSize = 0, size = 0, children = listOf(chrominanceTable, luminanceTable))
        val root = BoxNode(
            type = "root", offset = 0, headerSize = 0, size = 0,
            children = listOf(BoxNode(type = "SOI", offset = 0, headerSize = 2, size = 2), sof0, dqt),
        )

        val summary = buildMediaSummary(root, tempFile())

        val jpegDetail = summary.sections.first { it.title == "JPEG Detail" }
        assertEquals("~90%", jpegDetail.fields.first { it.label == "Quality Estimate" }.value)
    }

    @Test
    fun `APP14 color_transform 0 with 4 components reports Adobe Color Transform as CMYK`() {
        val sof0 = BoxNode(
            type = "SOF0", offset = 0, headerSize = 4, size = 19,
            fields = listOf(
                BoxField("precision", "8", 0, 1),
                BoxField("height", "480", 0, 2),
                BoxField("width", "640", 0, 2),
                BoxField("num_components", "4", 0, 1),
            ),
        )
        val app14 = BoxNode(
            type = "APP14", offset = 0, headerSize = 4, size = 16,
            fields = listOf(BoxField("color_transform", "0", 0, 1)),
        )
        val root = BoxNode(
            type = "root", offset = 0, headerSize = 0, size = 0,
            children = listOf(BoxNode(type = "SOI", offset = 0, headerSize = 2, size = 2), sof0, app14),
        )

        val summary = buildMediaSummary(root, tempFile())

        val jpegDetail = summary.sections.first { it.title == "JPEG Detail" }
        assertEquals("CMYK", jpegDetail.fields.first { it.label == "Adobe Color Transform" }.value)
    }

    @Test
    fun `APP14 color_transform 0 with 3 components reports Adobe Color Transform as RGB`() {
        val sof0 = BoxNode(
            type = "SOF0", offset = 0, headerSize = 4, size = 19,
            fields = listOf(
                BoxField("precision", "8", 0, 1),
                BoxField("height", "480", 0, 2),
                BoxField("width", "640", 0, 2),
                BoxField("num_components", "3", 0, 1),
            ),
        )
        val app14 = BoxNode(
            type = "APP14", offset = 0, headerSize = 4, size = 16,
            fields = listOf(BoxField("color_transform", "0", 0, 1)),
        )
        val root = BoxNode(
            type = "root", offset = 0, headerSize = 0, size = 0,
            children = listOf(BoxNode(type = "SOI", offset = 0, headerSize = 2, size = 2), sof0, app14),
        )

        val summary = buildMediaSummary(root, tempFile())

        val jpegDetail = summary.sections.first { it.title == "JPEG Detail" }
        assertEquals("RGB", jpegDetail.fields.first { it.label == "Adobe Color Transform" }.value)
    }

    @Test
    fun `APP14 color_transform 1 reports Adobe Color Transform as YCbCr`() {
        val sof0 = BoxNode(
            type = "SOF0", offset = 0, headerSize = 4, size = 19,
            fields = listOf(
                BoxField("precision", "8", 0, 1),
                BoxField("height", "480", 0, 2),
                BoxField("width", "640", 0, 2),
                BoxField("num_components", "3", 0, 1),
            ),
        )
        val app14 = BoxNode(
            type = "APP14", offset = 0, headerSize = 4, size = 16,
            fields = listOf(BoxField("color_transform", "1", 0, 1)),
        )
        val root = BoxNode(
            type = "root", offset = 0, headerSize = 0, size = 0,
            children = listOf(BoxNode(type = "SOI", offset = 0, headerSize = 2, size = 2), sof0, app14),
        )

        val summary = buildMediaSummary(root, tempFile())

        val jpegDetail = summary.sections.first { it.title == "JPEG Detail" }
        assertEquals("YCbCr", jpegDetail.fields.first { it.label == "Adobe Color Transform" }.value)
    }

    @Test
    fun `a JPEG with no APP14 omits the Adobe Color Transform field`() {
        val sof0 = BoxNode(
            type = "SOF0", offset = 0, headerSize = 4, size = 19,
            fields = listOf(
                BoxField("precision", "8", 0, 1),
                BoxField("height", "480", 0, 2),
                BoxField("width", "640", 0, 2),
                BoxField("num_components", "3", 0, 1),
            ),
        )
        val root = BoxNode(
            type = "root", offset = 0, headerSize = 0, size = 0,
            children = listOf(BoxNode(type = "SOI", offset = 0, headerSize = 2, size = 2), sof0),
        )

        val summary = buildMediaSummary(root, tempFile())

        val jpegDetail = summary.sections.first { it.title == "JPEG Detail" }
        assertEquals(null, jpegDetail.fields.find { it.label == "Adobe Color Transform" })
    }

    @Test
    fun `a JPEG with DRI and COM segments reports Restart Interval and Comment`() {
        val sof0 = BoxNode(
            type = "SOF0", offset = 0, headerSize = 4, size = 19,
            fields = listOf(
                BoxField("precision", "8", 0, 1),
                BoxField("height", "480", 0, 2),
                BoxField("width", "640", 0, 2),
                BoxField("num_components", "3", 0, 1),
            ),
        )
        val dri = BoxNode(
            type = "DRI", offset = 0, headerSize = 4, size = 6,
            fields = listOf(BoxField("restart_interval", "16", 0, 2)),
        )
        val com = BoxNode(
            type = "COM", offset = 0, headerSize = 4, size = 14,
            fields = listOf(BoxField("comment", "Created with GIMP", 0, 10)),
        )
        val root = BoxNode(
            type = "root", offset = 0, headerSize = 0, size = 0,
            children = listOf(BoxNode(type = "SOI", offset = 0, headerSize = 2, size = 2), sof0, dri, com),
        )

        val summary = buildMediaSummary(root, tempFile())

        val jpegDetail = summary.sections.first { it.title == "JPEG Detail" }
        assertEquals("16 MCUs", jpegDetail.fields.first { it.label == "Restart Interval" }.value)
        assertEquals("Created with GIMP", jpegDetail.fields.first { it.label == "Comment" }.value)
    }

    @Test
    fun `a JPEG with no DRI or COM segments omits Restart Interval and Comment`() {
        val sof0 = BoxNode(
            type = "SOF0", offset = 0, headerSize = 4, size = 19,
            fields = listOf(
                BoxField("precision", "8", 0, 1),
                BoxField("height", "480", 0, 2),
                BoxField("width", "640", 0, 2),
                BoxField("num_components", "3", 0, 1),
            ),
        )
        val root = BoxNode(
            type = "root", offset = 0, headerSize = 0, size = 0,
            children = listOf(BoxNode(type = "SOI", offset = 0, headerSize = 2, size = 2), sof0),
        )

        val summary = buildMediaSummary(root, tempFile())

        val jpegDetail = summary.sections.first { it.title == "JPEG Detail" }
        assertEquals(null, jpegDetail.fields.find { it.label == "Restart Interval" })
        assertEquals(null, jpegDetail.fields.find { it.label == "Comment" })
    }

    @Test
    fun `a non-JPEG image (PNG) has no JPEG Detail section`() {
        val ihdr = BoxNode(
            type = "IHDR", offset = 0, headerSize = 0, size = 0,
            fields = listOf(
                BoxField("width", "1920", 0, 4),
                BoxField("height", "1080", 0, 4),
                BoxField("color_type", "6", 0, 1),
            ),
        )
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(ihdr))

        val summary = buildMediaSummary(root, tempFile())

        assertEquals(null, summary.sections.find { it.title == "JPEG Detail" })
    }
```

- [ ] **Step 2: Run tests to verify they fail**

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:test --tests "com.multiviewer.parser.MediaSummaryBuilderTest"
```
Expected: FAIL — `summary.sections.first { it.title == "JPEG Detail" }` throws `NoSuchElementException` since no such section exists yet. The last test (`a non-JPEG image (PNG) has no JPEG Detail section`) passes even before implementation (there's nothing to remove) — that's fine, it locks in the non-regression guarantee going forward.

- [ ] **Step 3: Add the standard Huffman table constants and comparison helper**

In `app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt`, insert the following after `buildImageDetail`'s closing `}` (currently ends at line 282, right before the `WEBM_CODEC_DISPLAY_NAMES` map at line 284):

```kotlin
private val SOF_ENCODING_NAMES = mapOf(
    0 to "Baseline DCT (Huffman)",
    1 to "Extended Sequential DCT (Huffman)",
    2 to "Progressive DCT (Huffman)",
    3 to "Lossless (Sequential, Huffman)",
    5 to "Differential Sequential DCT (Huffman)",
    6 to "Differential Progressive DCT (Huffman)",
    7 to "Differential Lossless (Huffman)",
    9 to "Extended Sequential DCT (Arithmetic)",
    10 to "Progressive DCT (Arithmetic)",
    11 to "Lossless (Sequential, Arithmetic)",
    13 to "Differential Sequential DCT (Arithmetic)",
    14 to "Differential Progressive DCT (Arithmetic)",
    15 to "Differential Lossless (Arithmetic)",
)

private val STANDARD_DC_LUMINANCE_BITS = intArrayOf(0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0)
private val STANDARD_DC_LUMINANCE_VALUES = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)

private val STANDARD_DC_CHROMINANCE_BITS = intArrayOf(0, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0)
private val STANDARD_DC_CHROMINANCE_VALUES = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)

private val STANDARD_AC_LUMINANCE_BITS = intArrayOf(0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 0x7D)
private val STANDARD_AC_LUMINANCE_VALUES = intArrayOf(
    0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12, 0x21, 0x31, 0x41, 0x06, 0x13, 0x51, 0x61, 0x07,
    0x22, 0x71, 0x14, 0x32, 0x81, 0x91, 0xA1, 0x08, 0x23, 0x42, 0xB1, 0xC1, 0x15, 0x52, 0xD1, 0xF0,
    0x24, 0x33, 0x62, 0x72, 0x82, 0x09, 0x0A, 0x16, 0x17, 0x18, 0x19, 0x1A, 0x25, 0x26, 0x27, 0x28,
    0x29, 0x2A, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3A, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49,
    0x4A, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5A, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69,
    0x6A, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7A, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89,
    0x8A, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9A, 0xA2, 0xA3, 0xA4, 0xA5, 0xA6, 0xA7,
    0xA8, 0xA9, 0xAA, 0xB2, 0xB3, 0xB4, 0xB5, 0xB6, 0xB7, 0xB8, 0xB9, 0xBA, 0xC2, 0xC3, 0xC4, 0xC5,
    0xC6, 0xC7, 0xC8, 0xC9, 0xCA, 0xD2, 0xD3, 0xD4, 0xD5, 0xD6, 0xD7, 0xD8, 0xD9, 0xDA, 0xE1, 0xE2,
    0xE3, 0xE4, 0xE5, 0xE6, 0xE7, 0xE8, 0xE9, 0xEA, 0xF1, 0xF2, 0xF3, 0xF4, 0xF5, 0xF6, 0xF7, 0xF8,
    0xF9, 0xFA,
)

private val STANDARD_AC_CHROMINANCE_BITS = intArrayOf(0, 2, 1, 2, 4, 4, 3, 4, 7, 5, 4, 4, 0, 1, 2, 0x77)
private val STANDARD_AC_CHROMINANCE_VALUES = intArrayOf(
    0x00, 0x01, 0x02, 0x03, 0x11, 0x04, 0x05, 0x21, 0x31, 0x06, 0x12, 0x41, 0x51, 0x07, 0x61, 0x71,
    0x13, 0x22, 0x32, 0x81, 0x08, 0x14, 0x42, 0x91, 0xA1, 0xB1, 0xC1, 0x09, 0x23, 0x33, 0x52, 0xF0,
    0x15, 0x62, 0x72, 0xD1, 0x0A, 0x16, 0x24, 0x34, 0xE1, 0x25, 0xF1, 0x17, 0x18, 0x19, 0x1A, 0x26,
    0x27, 0x28, 0x29, 0x2A, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3A, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48,
    0x49, 0x4A, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5A, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68,
    0x69, 0x6A, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7A, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87,
    0x88, 0x89, 0x8A, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9A, 0xA2, 0xA3, 0xA4, 0xA5,
    0xA6, 0xA7, 0xA8, 0xA9, 0xAA, 0xB2, 0xB3, 0xB4, 0xB5, 0xB6, 0xB7, 0xB8, 0xB9, 0xBA, 0xC2, 0xC3,
    0xC4, 0xC5, 0xC6, 0xC7, 0xC8, 0xC9, 0xCA, 0xD2, 0xD3, 0xD4, 0xD5, 0xD6, 0xD7, 0xD8, 0xD9, 0xDA,
    0xE2, 0xE3, 0xE4, 0xE5, 0xE6, 0xE7, 0xE8, 0xE9, 0xEA, 0xF2, 0xF3, 0xF4, 0xF5, 0xF6, 0xF7, 0xF8,
    0xF9, 0xFA,
)

private fun standardHuffmanTable(className: String, destinationId: String): Pair<IntArray, IntArray>? = when {
    className == "DC" && destinationId == "0" -> STANDARD_DC_LUMINANCE_BITS to STANDARD_DC_LUMINANCE_VALUES
    className == "DC" && destinationId == "1" -> STANDARD_DC_CHROMINANCE_BITS to STANDARD_DC_CHROMINANCE_VALUES
    className == "AC" && destinationId == "0" -> STANDARD_AC_LUMINANCE_BITS to STANDARD_AC_LUMINANCE_VALUES
    className == "AC" && destinationId == "1" -> STANDARD_AC_CHROMINANCE_BITS to STANDARD_AC_CHROMINANCE_VALUES
    else -> null
}

private fun huffmanTableMatchesStandard(table: BoxNode): Boolean {
    val className = table.fields.find { it.name == "class" }?.value ?: return false
    val destinationId = table.fields.find { it.name == "destination_id" }?.value ?: return false
    val (standardBits, standardValues) = standardHuffmanTable(className, destinationId) ?: return false

    val actualBits = table.fields.find { it.name == "bit_counts" }?.value
        ?.split(", ")?.map { it.trim().toInt() }?.toIntArray() ?: return false
    if (!actualBits.contentEquals(standardBits)) return false

    val actualValues = (1..16).flatMap { length ->
        val field = table.fields.find { it.name == "codes_length_${length.toString().padStart(2, '0')}" }
        field?.value?.split(", ")?.map { it.trim().toInt(16) } ?: emptyList()
    }.toIntArray()
    return actualValues.contentEquals(standardValues)
}
```

- [ ] **Step 4: Add `buildJpegDetail`**

Immediately after the code from Step 3, add:

```kotlin
private fun buildJpegDetail(root: BoxNode): SummarySection? {
    val sof = findFirst(root) { it.type.startsWith("SOF") } ?: return null
    val fields = mutableListOf<SummaryField>()

    sof.type.removePrefix("SOF").toIntOrNull()?.let { sofNumber ->
        SOF_ENCODING_NAMES[sofNumber]?.let { fields.add(SummaryField("Encoding", it)) }
    }
    sof.fields.find { it.name == "precision" }?.let { fields.add(SummaryField("Precision", "${it.value}-bit")) }

    val quantizationTables = root.children.filter { it.type == "DQT" }.flatMap { it.children }
    val luminanceTable = quantizationTables.find {
        it.fields.find { f -> f.name == "destination_id" }?.value?.startsWith("0") == true
    }
    (luminanceTable ?: quantizationTables.firstOrNull())
        ?.fields?.find { it.name == "quality_estimate" }
        ?.let { fields.add(SummaryField("Quality Estimate", it.value)) }

    val huffmanTables = root.children.filter { it.type == "DHT" }.flatMap { it.children }
    if (huffmanTables.isNotEmpty()) {
        val mismatchLabels = huffmanTables.mapNotNull { table ->
            if (huffmanTableMatchesStandard(table)) {
                null
            } else {
                val className = table.fields.find { it.name == "class" }?.value ?: "?"
                val destinationId = table.fields.find { it.name == "destination_id" }?.value ?: "?"
                "$className$destinationId"
            }
        }
        val huffmanValue = if (mismatchLabels.isEmpty()) {
            "Standard"
        } else {
            "Custom/Optimized (differs: ${mismatchLabels.joinToString(", ")})"
        }
        fields.add(SummaryField("Huffman Tables", huffmanValue))
    }

    val app14 = root.children.find { it.type == "APP14" }
    app14?.fields?.find { it.name == "color_transform" }?.value?.toIntOrNull()?.let { transform ->
        val numComponents = sof.fields.find { it.name == "num_components" }?.value?.toIntOrNull()
        val label = when (transform) {
            1 -> "YCbCr"
            2 -> "YCCK"
            0 -> if (numComponents == 4) "CMYK" else "RGB"
            else -> "Unknown ($transform)"
        }
        fields.add(SummaryField("Adobe Color Transform", label))
    }

    val dri = root.children.find { it.type == "DRI" }
    dri?.fields?.find { it.name == "restart_interval" }
        ?.let { fields.add(SummaryField("Restart Interval", "${it.value} MCUs")) }

    val com = root.children.find { it.type == "COM" }
    com?.fields?.find { it.name == "comment" }?.let { fields.add(SummaryField("Comment", it.value)) }

    return if (fields.isNotEmpty()) SummarySection("JPEG Detail", fields) else null
}
```

- [ ] **Step 5: Wire it into `buildImageSummary`**

Find `buildImageSummary` (around line 118):

```kotlin
private fun buildImageSummary(root: BoxNode, file: File): List<SummarySection> {
    val sections = mutableListOf<SummarySection>()
    sections.add(buildImageGeneral(root, file))
    buildImageDetail(root)?.let { sections.add(it) }

    val ifd0 = findFirst(root) { it.type == "IFD0" }
```

Change it to:

```kotlin
private fun buildImageSummary(root: BoxNode, file: File): List<SummarySection> {
    val sections = mutableListOf<SummarySection>()
    sections.add(buildImageGeneral(root, file))
    buildImageDetail(root)?.let { sections.add(it) }
    buildJpegDetail(root)?.let { sections.add(it) }

    val ifd0 = findFirst(root) { it.type == "IFD0" }
```

(One new line added: `buildJpegDetail(root)?.let { sections.add(it) }`, right after the existing `buildImageDetail` call.)

- [ ] **Step 6: Run tests to verify they pass**

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:test --tests "com.multiviewer.parser.MediaSummaryBuilderTest"
```
Expected: PASS, all 11 new tests plus all pre-existing `MediaSummaryBuilderTest` cases.

- [ ] **Step 7: Run the full suite and commit**

```
./gradlew :app:test
git add app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt
git commit -m "Add JPEG Detail section to the Overview tab"
```
Expected: full suite passes, 0 failures, 0 regressions.

---

### Task 3: Manual verification

**Files:** none (no code changes — this task confirms Tasks 1–2 render correctly in the running app)

**Interfaces:** none

- [ ] **Step 1: Launch the app**

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:run
```

- [ ] **Step 2: Open a real JPEG file and confirm the Overview tab**

Open any `.jpg`/`.jpeg` file (a photo from a phone or camera is a good test — real-world files commonly have Baseline or Progressive encoding, standard or optimized Huffman tables, and sometimes an Adobe APP14 marker if edited in Photoshop). Confirm:
- The Overview tab (default on file open) shows a "JPEG Detail" section below "Image".
- It contains at least "Encoding" and "Precision".
- If the file has a Huffman table, "Huffman Tables" reads either "Standard" or "Custom/Optimized (differs: ...)" — not blank, not a crash.
- If the file has no Adobe APP14/DRI/comment, those three fields are simply absent (not shown as empty/blank rows).

- [ ] **Step 3: Confirm no regression on a non-JPEG image**

Open a PNG or HEIC file. Confirm the Overview tab shows no "JPEG Detail" section (only the pre-existing sections).

- [ ] **Step 4: Report result**

If both checks pass, this plan is complete. If anything looks wrong, note the exact file and field, and fix before considering the plan done.
