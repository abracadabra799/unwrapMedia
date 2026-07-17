# Samsung SEFD / Motion Photo Parsing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Recognize the `mpvd` top-level box (Samsung Motion Photo's embedded full video, itself a nested MP4) and the `sefd` top-level box (Samsung's field-directory metadata, including the `MotionPhoto_Data` pointer to that video and, often, a second smaller embedded MP4 preview clip).

**Architecture:** `mpvd` needs no new code — its payload is a plain sequence of top-level boxes, exactly what the existing `ContainerBoxDecoder()` already handles, so it's added to the shared container-type list in `Decoders.kt`. `sefd` gets one new decoder, `SefdBoxDecoder`, that locates the `SEFH`/`SEFT` directory trailer, walks its entries to resolve each field block's absolute position, and produces one child `BoxNode` per field — decoding the `MotionPhoto_Data` pointer specifically, recursing into any field whose data is itself a nested MP4 (content-sniffed, not hardcoded by name), and falling back to a plain string/binary-size display otherwise.

**Tech Stack:** Kotlin, JVM. Tests use `kotlin.test` + the existing `byteReaderOf(bytes: ByteArray): ByteReader` helper in `app/src/test/kotlin/com/multiviewer/parser/TestSupport.kt`. All test byte arrays in this plan were independently verified byte-for-byte using a Python simulation of the exact same encoding this decoder is written against — they are not just illustrative, they are the precise bytes to use.

## Global Constraints

- Design spec: `docs/superpowers/specs/2026-07-17-sefd-motion-photo-design.md`
- `decode(...)` signature must exactly match `BoxDecoder`'s interface: `decode(reader: ByteReader, type: String, offset: Long, headerSize: Int, size: Long, warnings: List<String>): BoxNode`.
- `payloadStart = offset + headerSize`, `payloadEnd = offset + size` — the exact convention every existing decoder uses.
- Every internal `sefd` field (reserved, marker, name_size, and every directory-entry field) is **little-endian**. `MotionPhoto_Data`'s `video_offset`/`video_length` are the one exception — **big-endian**, decoded with the existing `reader.readUInt32(offset)` (which is already big-endian) directly, no new helper needed for those two.
- `ByteReader` has no little-endian read methods; `SefdBoxDecoder.kt` adds two small private file-scoped helpers, `readUInt16LE`/`readUInt32LE`, used only within that file.
- Undersized/malformed-input bail-out convention (copy exactly): add a warning string to a mutable copy of `warnings`, then `return BoxNode(type, offset, headerSize, size, warnings = w)` — no fields, no children, no summary.
- Declared-vs-found truncation convention (copy exactly, see `AvcCBoxDecoder`/`FixedWidthTableDecoder`): loop bounds are checked before every read, and a warning naming both the declared and actual counts is added if they differ — this project has previously shipped a real bug (a decoder silently defaulting a "count" to 0 with no warning when truncated input skipped past the count field itself) and this plan's `SefdBoxDecoder` must not repeat it.
- No JPEG/PNG trailer support (this tool doesn't open raw JPEG/PNG files) — `SefdBoxDecoder` only handles the `sefd` **box** form.
- No marker→meaning lookup table — the `name` string is the display identity; `MotionPhoto_Data` is the one name-matched special case.
- JAVA_HOME for command-line Gradle runs on this machine: Android Studio's bundled JBR at `/Applications/Android Studio.app/Contents/jbr/Contents/Home` (JDK 21).

---

### Task 1: Register `mpvd` as a container box

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/Decoders.kt`
- Modify: `app/src/test/kotlin/com/multiviewer/parser/DecodersRegistrationTest.kt`

**Interfaces:**
- Consumes: `ContainerBoxDecoder` (existing, no changes).
- Produces: nothing new for other tasks — this is a self-contained registration change, independent of Task 2.

- [ ] **Step 1: Add `mpvd` to the shared container-type list in `Decoders.kt`**

Find this exact line in `registerAllDecoders()`:

```kotlin
    for (containerType in listOf("moov", "trak", "mdia", "minf", "dinf", "edts", "udta", "stbl", "iprp", "ipco")) {
```

Replace it with:

```kotlin
    for (containerType in listOf("moov", "trak", "mdia", "minf", "dinf", "edts", "udta", "stbl", "iprp", "ipco", "mpvd")) {
```

- [ ] **Step 2: Add `"mpvd"` to `DecodersRegistrationTest`'s required-type list**

Replace this exact block in `DecodersRegistrationTest.kt`:

```kotlin
        val typesThatMustHaveADecoder = listOf(
            "avc1", "hvc1", "mp4a", "avcC", "hvcC", "elst",
            "dref", "url ", "urn ", "colr", "pasp", "iinf", "infe",
        )
```

with:

```kotlin
        val typesThatMustHaveADecoder = listOf(
            "avc1", "hvc1", "mp4a", "avcC", "hvcC", "elst",
            "dref", "url ", "urn ", "colr", "pasp", "iinf", "infe", "mpvd",
        )
```

- [ ] **Step 3: Run the test to verify it passes**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew test --tests DecodersRegistrationTest
```
Expected: PASS.

- [ ] **Step 4: Run the full test suite (confirm no regression)**

```bash
./gradlew test
```
Expected: BUILD SUCCESSFUL, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/Decoders.kt app/src/test/kotlin/com/multiviewer/parser/DecodersRegistrationTest.kt
git commit -m "feat(parser): register mpvd as a container box (Motion Photo embedded video)"
```

---

### Task 2: `SefdBoxDecoder` — Samsung field-directory parsing

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/SefdBoxDecoder.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/parser/Decoders.kt`
- Modify: `app/src/test/kotlin/com/multiviewer/parser/DecodersRegistrationTest.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/SefdBoxDecoderTest.kt`

**Interfaces:**
- Consumes: `ByteReader`, `BoxNode`, `BoxField`, `parseBoxes` (existing, same package). Depends on Task 1 only in that both tasks edit `Decoders.kt` and `DecodersRegistrationTest.kt` — apply Task 2's edits on top of Task 1's, not by reverting them.
- Produces: `object SefdBoxDecoder : BoxDecoder` — registered for `"sefd"`. Final task in this plan.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SefdBoxDecoderTest {
    @Test
    fun `decodes a plain text field with its marker and value`() {
        val body = byteArrayOf(
            0x00, 0x00, 0x34, 0x12, 0x0a, 0x00, 0x00, 0x00,
            0x54, 0x65, 0x73, 0x74, 0x5f, 0x46, 0x69, 0x65, 0x6c, 0x64,
            0x68, 0x69,
            0x53, 0x45, 0x46, 0x48,
            0x01, 0x00, 0x00, 0x00,
            0x01, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x34, 0x12, 0x14, 0x00, 0x00, 0x00, 0x14, 0x00, 0x00, 0x00,
            0x18, 0x00, 0x00, 0x00,
            0x53, 0x45, 0x46, 0x54,
        )
        val reader = byteReaderOf(body)
        val node = SefdBoxDecoder.decode(reader, "sefd", 0, 0, body.size.toLong(), emptyList())

        assertEquals(1, node.children.size)
        val field = node.children[0]
        assertEquals("Test_Field", field.type)
        assertEquals(0L, field.offset)
        assertEquals(20L, field.size)
        assertEquals("0x1234", field.fields[0].value)
        assertEquals("hi", field.fields[1].value)
        assertEquals("hi", field.summary)
        assertEquals("1 field", node.summary)
        assertTrue(node.warnings.isEmpty())
        reader.close()
    }

    @Test
    fun `decodes the MotionPhoto_Data pointer as big-endian offset and length`() {
        val body = byteArrayOf(
            0x00, 0x00, 0x30, 0x0a, 0x10, 0x00, 0x00, 0x00,
            0x4d, 0x6f, 0x74, 0x69, 0x6f, 0x6e, 0x50, 0x68, 0x6f, 0x74, 0x6f, 0x5f, 0x44, 0x61, 0x74, 0x61,
            0x6d, 0x70, 0x76, 0x32, 0x00, 0x00, 0x03, 0xe8.toByte(), 0x00, 0x00, 0x00, 0x64,
            0x53, 0x45, 0x46, 0x48,
            0x01, 0x00, 0x00, 0x00,
            0x01, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x30, 0x0a, 0x24, 0x00, 0x00, 0x00, 0x24, 0x00, 0x00, 0x00,
            0x18, 0x00, 0x00, 0x00,
            0x53, 0x45, 0x46, 0x54,
        )
        val reader = byteReaderOf(body)
        val node = SefdBoxDecoder.decode(reader, "sefd", 0, 0, body.size.toLong(), emptyList())

        val field = node.children[0]
        assertEquals("MotionPhoto_Data", field.type)
        assertEquals("mpv2", field.fields[1].value)
        assertEquals("1000", field.fields[2].value)
        assertEquals("100", field.fields[3].value)
        assertEquals("offset=1000, length=100", field.summary)
        reader.close()
    }

    @Test
    fun `recurses into an embedded MP4 field instead of showing it as a flat value`() {
        val body = byteArrayOf(
            0x00, 0x00, 0x33, 0x0a, 0x0e, 0x00, 0x00, 0x00,
            0x45, 0x6d, 0x62, 0x65, 0x64, 0x64, 0x65, 0x64, 0x5f, 0x56, 0x69, 0x64, 0x65, 0x6f,
            0x00, 0x00, 0x00, 0x0c, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6f, 0x6d,
            0x53, 0x45, 0x46, 0x48,
            0x01, 0x00, 0x00, 0x00,
            0x01, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x33, 0x0a, 0x22, 0x00, 0x00, 0x00, 0x22, 0x00, 0x00, 0x00,
            0x18, 0x00, 0x00, 0x00,
            0x53, 0x45, 0x46, 0x54,
        )
        val reader = byteReaderOf(body)
        val node = SefdBoxDecoder.decode(reader, "sefd", 0, 0, body.size.toLong(), emptyList())

        val field = node.children[0]
        assertEquals("Embedded_Video", field.type)
        assertEquals(1, field.children.size)
        assertEquals("ftyp", field.children[0].type)
        assertEquals(true, field.fields.none { it.name == "value" })
        assertEquals("12 bytes, embedded MP4", field.summary)
        reader.close()
    }

    @Test
    fun `directory marker mismatching the block's own marker adds a warning`() {
        val body = byteArrayOf(
            0x00, 0x00, 0x34, 0x12, 0x07, 0x00, 0x00, 0x00,
            0x46, 0x69, 0x65, 0x6c, 0x64, 0x5f, 0x41,
            0x61, 0x61,
            0x53, 0x45, 0x46, 0x48,
            0x01, 0x00, 0x00, 0x00,
            0x01, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x99, 0x99.toByte(), 0x11, 0x00, 0x00, 0x00, 0x11, 0x00, 0x00, 0x00,
            0x18, 0x00, 0x00, 0x00,
            0x53, 0x45, 0x46, 0x54,
        )
        val reader = byteReaderOf(body)
        val node = SefdBoxDecoder.decode(reader, "sefd", 0, 0, body.size.toLong(), emptyList())

        assertEquals(1, node.children.size)
        assertEquals(1, node.children[0].warnings.size)
        reader.close()
    }

    @Test
    fun `missing SEFT trailer magic returns a warning and no children`() {
        val body = ByteArray(20)
        val reader = byteReaderOf(body)
        val node = SefdBoxDecoder.decode(reader, "sefd", 0, 0, body.size.toLong(), emptyList())
        assertEquals(1, node.warnings.size)
        assertEquals(true, node.children.isEmpty())
        reader.close()
    }

    @Test
    fun `declared directory entry count larger than available data adds a truncation warning`() {
        val body = byteArrayOf(
            0x00, 0x00, 0x34, 0x12, 0x07, 0x00, 0x00, 0x00,
            0x46, 0x69, 0x65, 0x6c, 0x64, 0x5f, 0x41,
            0x61, 0x61,
            0x53, 0x45, 0x46, 0x48,
            0x01, 0x00, 0x00, 0x00,
            0x02, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x34, 0x12, 0x11, 0x00, 0x00, 0x00, 0x11, 0x00, 0x00, 0x00,
            0x18, 0x00, 0x00, 0x00,
            0x53, 0x45, 0x46, 0x54,
        )
        val reader = byteReaderOf(body)
        val node = SefdBoxDecoder.decode(reader, "sefd", 0, 0, body.size.toLong(), emptyList())

        assertEquals(1, node.children.size)
        assertEquals(1, node.warnings.size)
        reader.close()
    }

    @Test
    fun `two fields both decode with correct offsets and directory count matches`() {
        val body = byteArrayOf(
            0x00, 0x00, 0x34, 0x12, 0x07, 0x00, 0x00, 0x00,
            0x46, 0x69, 0x65, 0x6c, 0x64, 0x5f, 0x41,
            0x61, 0x61,
            0x00, 0x00, 0x78, 0x56, 0x07, 0x00, 0x00, 0x00,
            0x46, 0x69, 0x65, 0x6c, 0x64, 0x5f, 0x42,
            0x62, 0x62,
            0x53, 0x45, 0x46, 0x48,
            0x01, 0x00, 0x00, 0x00,
            0x02, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x34, 0x12, 0x22, 0x00, 0x00, 0x00, 0x11, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x78, 0x56, 0x11, 0x00, 0x00, 0x00, 0x11, 0x00, 0x00, 0x00,
            0x24, 0x00, 0x00, 0x00,
            0x53, 0x45, 0x46, 0x54,
        )
        val reader = byteReaderOf(body)
        val node = SefdBoxDecoder.decode(reader, "sefd", 0, 0, body.size.toLong(), emptyList())

        assertEquals(2, node.children.size)
        assertEquals("Field_A", node.children[0].type)
        assertEquals(0L, node.children[0].offset)
        assertEquals("Field_B", node.children[1].type)
        assertEquals(17L, node.children[1].offset)
        assertEquals("2 fields", node.summary)
        reader.close()
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew test --tests SefdBoxDecoderTest
```
Expected: FAIL — `SefdBoxDecoder` is unresolved.

- [ ] **Step 3: Create `SefdBoxDecoder.kt`**

```kotlin
package com.multiviewer.parser

object SefdBoxDecoder : BoxDecoder {
    override fun decode(
        reader: ByteReader,
        type: String,
        offset: Long,
        headerSize: Int,
        size: Long,
        warnings: List<String>,
    ): BoxNode {
        val w = warnings.toMutableList()
        val payloadStart = offset + headerSize
        val payloadEnd = offset + size

        if (payloadEnd - payloadStart < 12) {
            w.add("Box too short to contain a SEFH/SEFT trailer")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }

        val sefMagic = reader.readFourCC(payloadEnd - 4)
        if (sefMagic != "SEFT") {
            w.add("Missing SEFT trailer magic")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val sefSize = readUInt32LE(reader, payloadEnd - 8)
        val sefhPosition = payloadEnd - 8 - sefSize
        if (sefhPosition < payloadStart || sefhPosition + 12 > payloadEnd) {
            w.add("SEFH position computed from sef_size is out of bounds")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val sefhMagic = reader.readFourCC(sefhPosition)
        if (sefhMagic != "SEFH") {
            w.add("Missing SEFH header magic at computed position")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val count = readUInt32LE(reader, sefhPosition + 8)

        val children = mutableListOf<BoxNode>()
        var entryPos = sefhPosition + 12
        var entriesFound = 0L
        while (entriesFound < count && entryPos + 12 <= payloadEnd) {
            val entryMarker = readUInt16LE(reader, entryPos + 2)
            val entryOffset = readUInt32LE(reader, entryPos + 4)
            val entrySize = readUInt32LE(reader, entryPos + 8)
            entryPos += 12
            entriesFound++

            val blockStart = sefhPosition - entryOffset
            val blockEnd = blockStart + entrySize
            if (blockStart < payloadStart || blockEnd > payloadEnd) {
                w.add("Field directory entry for marker 0x${entryMarker.toString(16)} points out of bounds")
                continue
            }
            children.add(decodeField(reader, blockStart, entrySize, entryMarker))
        }
        if (entriesFound < count) {
            w.add("Declared $count directory entries but only found $entriesFound")
        }

        return BoxNode(
            type = type, offset = offset, headerSize = headerSize, size = size,
            children = children, warnings = w,
            summary = pluralize(count, "field", "fields"),
        )
    }

    private fun decodeField(reader: ByteReader, blockStart: Long, blockSize: Long, directoryMarker: Int): BoxNode {
        val warnings = mutableListOf<String>()
        if (blockSize < 8) {
            warnings.add("Field block too short for its own header")
            return BoxNode("?", blockStart, 0, blockSize, warnings = warnings)
        }
        val blockMarker = readUInt16LE(reader, blockStart + 2)
        val nameSize = readUInt32LE(reader, blockStart + 4)
        if (blockMarker != directoryMarker) {
            warnings.add(
                "Directory marker 0x${directoryMarker.toString(16)} does not match block marker 0x${blockMarker.toString(16)}",
            )
        }
        val nameOffset = blockStart + 8
        if (nameOffset + nameSize > blockStart + blockSize) {
            warnings.add("Field name_size runs past the end of its block")
            return BoxNode("?", blockStart, 8, blockSize, warnings = warnings)
        }
        val nameBytes = reader.readBytes(nameOffset, nameSize.toInt())
        val name = String(nameBytes, Charsets.UTF_8).trimEnd(Char(0))
        val fieldHeaderSize = (8 + nameSize).toInt()
        val dataStart = blockStart + fieldHeaderSize
        val dataEnd = blockStart + blockSize
        val dataLength = (dataEnd - dataStart).toInt()

        val markerField = BoxField("marker", "0x" + blockMarker.toString(16).padStart(4, '0'), blockStart + 2, 2)

        if (dataLength >= 8 && reader.readFourCC(dataStart + 4) == "ftyp") {
            val nestedChildren = parseBoxes(reader, dataStart, dataEnd)
            return BoxNode(
                type = name, offset = blockStart, headerSize = fieldHeaderSize, size = blockSize,
                children = nestedChildren, fields = listOf(markerField), warnings = warnings,
                summary = "$dataLength bytes, embedded MP4",
            )
        }

        if (name == "MotionPhoto_Data" && dataLength == 12) {
            val formatTag = reader.readFourCC(dataStart)
            val videoOffset = reader.readUInt32(dataStart + 4)
            val videoLength = reader.readUInt32(dataStart + 8)
            val fields = listOf(
                markerField,
                BoxField("format_tag", formatTag, dataStart, 4),
                BoxField("video_offset", videoOffset.toString(), dataStart + 4, 4),
                BoxField("video_length", videoLength.toString(), dataStart + 8, 4),
            )
            return BoxNode(
                type = name, offset = blockStart, headerSize = fieldHeaderSize, size = blockSize,
                fields = fields, warnings = warnings,
                summary = "offset=$videoOffset, length=$videoLength",
            )
        }

        val dataBytes = reader.readBytes(dataStart, dataLength)
        val isPrintable = dataBytes.all { b ->
            val v = b.toInt() and 0xFF
            v in 0x20..0x7E || v == 0x09 || v == 0x0A || v == 0x0D
        }
        return if (isPrintable) {
            val value = String(dataBytes, Charsets.UTF_8)
            BoxNode(
                type = name, offset = blockStart, headerSize = fieldHeaderSize, size = blockSize,
                fields = listOf(markerField, BoxField("value", value, dataStart, dataLength.toLong())),
                warnings = warnings,
                summary = value,
            )
        } else {
            BoxNode(
                type = name, offset = blockStart, headerSize = fieldHeaderSize, size = blockSize,
                fields = listOf(markerField), warnings = warnings,
                summary = "$dataLength bytes (binary)",
            )
        }
    }
}

private fun readUInt16LE(reader: ByteReader, offset: Long): Int {
    val bytes = reader.readBytes(offset, 2)
    return ((bytes[1].toInt() and 0xFF) shl 8) or (bytes[0].toInt() and 0xFF)
}

private fun readUInt32LE(reader: ByteReader, offset: Long): Long {
    val bytes = reader.readBytes(offset, 4)
    return ((bytes[3].toLong() and 0xFF) shl 24) or
        ((bytes[2].toLong() and 0xFF) shl 16) or
        ((bytes[1].toLong() and 0xFF) shl 8) or
        (bytes[0].toLong() and 0xFF)
}
```

- [ ] **Step 4: Register `sefd` in `Decoders.kt`**

Add directly after the container-type `for` loop block edited in Task 1:

```kotlin
    BoxRegistry.register("sefd", SefdBoxDecoder)
```

- [ ] **Step 5: Add `"sefd"` to `DecodersRegistrationTest`'s required-type list**

Replace this exact block (as left by Task 1):

```kotlin
        val typesThatMustHaveADecoder = listOf(
            "avc1", "hvc1", "mp4a", "avcC", "hvcC", "elst",
            "dref", "url ", "urn ", "colr", "pasp", "iinf", "infe", "mpvd",
        )
```

with:

```kotlin
        val typesThatMustHaveADecoder = listOf(
            "avc1", "hvc1", "mp4a", "avcC", "hvcC", "elst",
            "dref", "url ", "urn ", "colr", "pasp", "iinf", "infe", "mpvd", "sefd",
        )
```

- [ ] **Step 6: Run tests to verify they pass**

```bash
./gradlew test --tests SefdBoxDecoderTest --tests DecodersRegistrationTest
```
Expected: PASS (7/7 in `SefdBoxDecoderTest`, 1/1 in `DecodersRegistrationTest`).

- [ ] **Step 7: Run the full test suite (confirm no regression)**

```bash
./gradlew test
```
Expected: BUILD SUCCESSFUL, 0 failures.

- [ ] **Step 8: Manual verification against the real sample file**

```bash
./gradlew run
```
Open `~/Downloads/20260715_223828.heic` and confirm:
- The `mpvd` top-level box now shows children (`ftyp`, `mdat`, etc.) instead of nothing.
- The `sefd` top-level box shows exactly 12 child nodes, named `PhotoEditor_Re_Edit_Data`, `Copy_Available_Edit_Info`, `Original_Path_Hash_Key`, `Image_UTC_Data`, `MCC_Data`, `Camera_Scene_Info`, `Color_Display_P3`, `Photo_HDR_Info`, `Camera_Capture_Mode_Info`, `MotionPhoto_AutoPlay`, `MotionPhoto_Version`, `MotionPhoto_Data` (directory order).
- Selecting `MotionPhoto_Data` shows `format_tag`/`video_offset`/`video_length` fields; `video_offset` should match `mpvd`'s own Offset (from the always-shown metadata block) plus 8, and `video_length` should match `mpvd`'s Payload size.
- Selecting `MotionPhoto_AutoPlay` shows it has children (a nested `ftyp`/`mdat` box tree) rather than a flat value.
- Selecting `Image_UTC_Data` shows `value = "1784122708301"`.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/SefdBoxDecoder.kt app/src/main/kotlin/com/multiviewer/parser/Decoders.kt app/src/test/kotlin/com/multiviewer/parser/DecodersRegistrationTest.kt app/src/test/kotlin/com/multiviewer/parser/SefdBoxDecoderTest.kt
git commit -m "feat(parser): decode sefd Samsung field directory (Motion Photo, editor/camera metadata)"
```
