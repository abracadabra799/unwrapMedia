# WebM Playback and Structural Parsing Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Support WebM files end-to-end: structural tree parsing (new EBML/Matroska walker), playback (via the already-webm-capable bundled ffmpeg), and summary/warnings/CLI integration (via the existing generic `BoxNode` model).

**Architecture:** A new file `EbmlWalker.kt` parses EBML's variable-length-integer element format into the existing `BoxNode`/`BoxField` tree model, exactly parallel to how `BoxWalker.kt` parses ISOBMFF. `ParseFile.kt`'s magic-byte dispatch and `AppState.kt`'s `VIDEO_EXTENSIONS` route `.webm` files to it. `MediaSummaryBuilder.kt` gets a WebM-specific summary builder alongside the existing ISOBMFF one, selected by tree shape. Playback, GOP frame analysis, and codec-detail probing need no changes -- they already operate on the raw file via ffprobe/ffmpeg, independent of this app's own parser.

**Tech Stack:** Kotlin, EBML/Matroska binary format (variable-length integers), no new dependencies.

## Global Constraints

- Reuse the existing `BoxNode`/`BoxField` model unchanged -- no new tree types. This is what makes the structure tree view, `collectWarnings`, and the CLI `dump`/`check` commands work on WebM files automatically, with no changes anywhere else in the app.
- Element IDs not in the new name table fall back to `"0x${id.toString(16).uppercase()}"` (unlabeled, shown as a bare numeric ID) -- the same convention this app already uses for unrecognized IDs elsewhere (e.g. unlabeled JPEG destination IDs). Unknown elements are treated as opaque leaves (not recursed into), since their type (master vs. leaf) can't be safely assumed.
- An EBML "unknown size" (all value bits set to 1 after stripping the marker) is treated the same way `BoxWalker.kt` already treats a declared ISOBMFF box size of `0`: the element's content extends to the end of its parent's range.
- No changes to `FfmpegVideoPlayer.kt`, `probeFrameTypes` (`FrameTypeAnalyzer.kt`), or `probeStreamDetails` (`StreamCodecDetails.kt`) -- all three already work generically against the source file via ffprobe/ffmpeg and need no WebM-specific code once `.webm` is a recognized video extension.
- Full Matroska/EBML specification coverage (chapters, tags, attachments, detailed subtitle-track parsing) is out of scope -- only elements meaningful to a video inspector are named in the table; everything else still parses structurally (as an unnamed numeric-ID node), just isn't specially summarized.

---

### Task 1: EBML element walker (`EbmlWalker.kt`)

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/EbmlWalker.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/EbmlWalkerTest.kt`

**Interfaces:**
- Produces: `fun parseEbmlElements(reader: ByteReader, start: Long, end: Long): List<BoxNode>` -- the entry point Task 2 wires into `ParseFile.kt`, structurally parallel to the existing `parseBoxes(reader, rangeStart, rangeEnd): List<BoxNode>` (`BoxWalker.kt`).

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/multiviewer/parser/EbmlWalkerTest.kt`:

```kotlin
package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EbmlWalkerTest {
    @Test
    fun `parses a 4-byte-ID master element containing a 2-byte-ID string leaf`() {
        // EBML header (ID 0x1A45DFA3, 4 bytes) containing a DocType (ID 0x4282, 2 bytes) of "webm".
        val reader = byteReaderOf(
            byteArrayOf(
                0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), // EBML element ID (4 bytes)
                0x87.toByte(), // size = 7 (1-byte VINT)
                0x42, 0x82.toByte(), // DocType element ID (2 bytes)
                0x84.toByte(), // size = 4 (1-byte VINT)
                0x77, 0x65, 0x62, 0x6D, // "webm"
            )
        )
        val elements = parseEbmlElements(reader, 0, reader.length)

        assertEquals(1, elements.size)
        assertEquals("EBML", elements[0].type)
        assertEquals(0L, elements[0].offset)
        assertEquals(5, elements[0].headerSize)
        assertEquals(12L, elements[0].size)

        assertEquals(1, elements[0].children.size)
        val docType = elements[0].children[0]
        assertEquals("DocType", docType.type)
        assertEquals(5L, docType.offset)
        assertEquals(3, docType.headerSize)
        assertEquals(7L, docType.size)
        assertEquals("webm", docType.fields.single { it.name == "value" }.value)
        reader.close()
    }

    @Test
    fun `decodes a known 1-byte-ID uint element to its numeric value`() {
        // TrackType (ID 0x83, 1 byte) with a 1-byte value of 1 (video).
        val reader = byteReaderOf(byteArrayOf(0x83.toByte(), 0x81.toByte(), 0x01))
        val elements = parseEbmlElements(reader, 0, reader.length)

        assertEquals(1, elements.size)
        assertEquals("TrackType", elements[0].type)
        assertEquals(2, elements[0].headerSize)
        assertEquals(3L, elements[0].size)
        assertEquals("1", elements[0].fields.single { it.name == "value" }.value)
        reader.close()
    }

    @Test
    fun `a known master element recurses into its children`() {
        // Video (ID 0xE0, 1 byte) containing PixelWidth (ID 0xB0, 1 byte) = 640.
        val reader = byteReaderOf(
            byteArrayOf(
                0xE0.toByte(), 0x84.toByte(), // Video, size = 4
                0xB0.toByte(), 0x82.toByte(), 0x02, 0x80.toByte(), // PixelWidth, size = 2, value = 640
            )
        )
        val elements = parseEbmlElements(reader, 0, reader.length)

        assertEquals(1, elements.size)
        assertEquals("Video", elements[0].type)
        assertEquals(1, elements[0].children.size)
        val pixelWidth = elements[0].children[0]
        assertEquals("PixelWidth", pixelWidth.type)
        assertEquals("640", pixelWidth.fields.single { it.name == "value" }.value)
        reader.close()
    }

    @Test
    fun `an element ID not in the known table falls back to an unlabeled numeric name with no children or fields`() {
        val reader = byteReaderOf(byteArrayOf(0x9B.toByte(), 0x82.toByte(), 0xAA.toByte(), 0xBB.toByte()))
        val elements = parseEbmlElements(reader, 0, reader.length)

        assertEquals(1, elements.size)
        assertEquals("0x9B", elements[0].type)
        assertTrue(elements[0].children.isEmpty())
        assertTrue(elements[0].fields.isEmpty())
        reader.close()
    }

    @Test
    fun `an unknown-size element extends to the end of its parent range`() {
        // TrackType (ID 0x83, 1 byte) with the "unknown size" marker (0xFF = all value bits 1),
        // followed by 1 remaining byte in the given range.
        val reader = byteReaderOf(byteArrayOf(0x83.toByte(), 0xFF.toByte(), 0x05))
        val elements = parseEbmlElements(reader, 0, reader.length)

        assertEquals(1, elements.size)
        assertEquals(3L, elements[0].size)
        assertEquals("5", elements[0].fields.single { it.name == "value" }.value)
        reader.close()
    }

    @Test
    fun `declared size extending past the parent range produces a warning and clamps`() {
        val reader = byteReaderOf(byteArrayOf(0x83.toByte(), 0x8A.toByte(), 0x05))
        val elements = parseEbmlElements(reader, 0, reader.length)

        assertEquals(1, elements.size)
        assertTrue(elements[0].warnings.single().contains("extends"))
        assertEquals(3L, elements[0].size)
        reader.close()
    }

    @Test
    fun `too few bytes for an element header produces a trailing-bytes warning and stops`() {
        val reader = byteReaderOf(byteArrayOf(0x83.toByte()))
        val elements = parseEbmlElements(reader, 0, reader.length)

        assertEquals(1, elements.size)
        assertEquals("?", elements[0].type)
        assertTrue(elements[0].warnings.single().contains("too short"))
        reader.close()
    }

    @Test
    fun `two sibling elements parse back to back`() {
        val reader = byteReaderOf(
            byteArrayOf(
                0x83.toByte(), 0x81.toByte(), 0x01, // TrackType = 1
                0x83.toByte(), 0x81.toByte(), 0x02, // TrackType = 2
            )
        )
        val elements = parseEbmlElements(reader, 0, reader.length)

        assertEquals(2, elements.size)
        assertEquals(0L, elements[0].offset)
        assertEquals(3L, elements[1].offset)
        assertEquals("1", elements[0].fields.single { it.name == "value" }.value)
        assertEquals("2", elements[1].fields.single { it.name == "value" }.value)
        reader.close()
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests "com.multiviewer.parser.EbmlWalkerTest"`
Expected: FAIL to compile -- `parseEbmlElements` doesn't exist yet.

- [ ] **Step 3: Create EbmlWalker.kt**

Create `app/src/main/kotlin/com/multiviewer/parser/EbmlWalker.kt`:

```kotlin
package com.multiviewer.parser

private enum class EbmlElementType { MASTER, UINT, STRING, UTF8, FLOAT, DATE }

private data class EbmlElementInfo(val name: String, val type: EbmlElementType)

// Element IDs from the Matroska/EBML specification, as broad as practical for a video inspector --
// the EBML header, Segment's top-level children (SeekHead, Info, Tracks/TrackEntry with Video/
// Audio sub-elements, Cues, Cluster), and Tags/Chapters/Attachments as named-but-unparsed master
// placeholders. An ID not in this table falls through to an unlabeled numeric name (see
// parseEbmlElements) -- not a parse error, just unnamed, the same convention this app already uses
// for unrecognized IDs elsewhere.
private val EBML_ELEMENTS: Map<Long, EbmlElementInfo> = mapOf(
    0x1A45DFA3L to EbmlElementInfo("EBML", EbmlElementType.MASTER),
    0x4286L to EbmlElementInfo("EBMLVersion", EbmlElementType.UINT),
    0x42F7L to EbmlElementInfo("EBMLReadVersion", EbmlElementType.UINT),
    0x42F2L to EbmlElementInfo("EBMLMaxIDLength", EbmlElementType.UINT),
    0x42F3L to EbmlElementInfo("EBMLMaxSizeLength", EbmlElementType.UINT),
    0x4282L to EbmlElementInfo("DocType", EbmlElementType.STRING),
    0x4287L to EbmlElementInfo("DocTypeVersion", EbmlElementType.UINT),
    0x4285L to EbmlElementInfo("DocTypeReadVersion", EbmlElementType.UINT),
    0x18538067L to EbmlElementInfo("Segment", EbmlElementType.MASTER),
    0x114D9B74L to EbmlElementInfo("SeekHead", EbmlElementType.MASTER),
    0x4DBBL to EbmlElementInfo("Seek", EbmlElementType.MASTER),
    0x53ABL to EbmlElementInfo("SeekID", EbmlElementType.UTF8),
    0x53ACL to EbmlElementInfo("SeekPosition", EbmlElementType.UINT),
    0x1549A966L to EbmlElementInfo("Info", EbmlElementType.MASTER),
    0x2AD7B1L to EbmlElementInfo("TimecodeScale", EbmlElementType.UINT),
    0x4489L to EbmlElementInfo("Duration", EbmlElementType.FLOAT),
    0x4461L to EbmlElementInfo("DateUTC", EbmlElementType.DATE),
    0x4D80L to EbmlElementInfo("MuxingApp", EbmlElementType.UTF8),
    0x5741L to EbmlElementInfo("WritingApp", EbmlElementType.UTF8),
    0x1654AE6BL to EbmlElementInfo("Tracks", EbmlElementType.MASTER),
    0xAEL to EbmlElementInfo("TrackEntry", EbmlElementType.MASTER),
    0xD7L to EbmlElementInfo("TrackNumber", EbmlElementType.UINT),
    0x73C5L to EbmlElementInfo("TrackUID", EbmlElementType.UINT),
    0x83L to EbmlElementInfo("TrackType", EbmlElementType.UINT),
    0xB9L to EbmlElementInfo("FlagEnabled", EbmlElementType.UINT),
    0x88L to EbmlElementInfo("FlagDefault", EbmlElementType.UINT),
    0x9CL to EbmlElementInfo("FlagLacing", EbmlElementType.UINT),
    0x22B59CL to EbmlElementInfo("Language", EbmlElementType.STRING),
    0x86L to EbmlElementInfo("CodecID", EbmlElementType.STRING),
    0x258688L to EbmlElementInfo("CodecName", EbmlElementType.UTF8),
    0x23E383L to EbmlElementInfo("DefaultDuration", EbmlElementType.UINT),
    0xE0L to EbmlElementInfo("Video", EbmlElementType.MASTER),
    0xB0L to EbmlElementInfo("PixelWidth", EbmlElementType.UINT),
    0xBAL to EbmlElementInfo("PixelHeight", EbmlElementType.UINT),
    0x53B8L to EbmlElementInfo("StereoMode", EbmlElementType.UINT),
    0x54B0L to EbmlElementInfo("DisplayWidth", EbmlElementType.UINT),
    0x54BAL to EbmlElementInfo("DisplayHeight", EbmlElementType.UINT),
    0xE1L to EbmlElementInfo("Audio", EbmlElementType.MASTER),
    0xB5L to EbmlElementInfo("SamplingFrequency", EbmlElementType.FLOAT),
    0x9FL to EbmlElementInfo("Channels", EbmlElementType.UINT),
    0x6264L to EbmlElementInfo("BitDepth", EbmlElementType.UINT),
    0x1C53BB6BL to EbmlElementInfo("Cues", EbmlElementType.MASTER),
    0xBBL to EbmlElementInfo("CuePoint", EbmlElementType.MASTER),
    0xB3L to EbmlElementInfo("CueTime", EbmlElementType.UINT),
    0xB7L to EbmlElementInfo("CueTrackPositions", EbmlElementType.MASTER),
    0xF7L to EbmlElementInfo("CueTrack", EbmlElementType.UINT),
    0xF1L to EbmlElementInfo("CueClusterPosition", EbmlElementType.UINT),
    0x1F43B675L to EbmlElementInfo("Cluster", EbmlElementType.MASTER),
    0xE7L to EbmlElementInfo("Timecode", EbmlElementType.UINT),
    0xA3L to EbmlElementInfo("SimpleBlock", EbmlElementType.UINT),
    0xA0L to EbmlElementInfo("BlockGroup", EbmlElementType.MASTER),
    0xA1L to EbmlElementInfo("Block", EbmlElementType.UINT),
    0x1254C367L to EbmlElementInfo("Tags", EbmlElementType.MASTER),
    0x1043A770L to EbmlElementInfo("Chapters", EbmlElementType.MASTER),
    0x1941A469L to EbmlElementInfo("Attachments", EbmlElementType.MASTER),
)

// Determines how many bytes the VINT starting at this first byte spans (1-8), per EBML's encoding:
// the position of the leading 1-bit in the first byte determines the length. Returns null for a
// first byte of 0x00, which would require a 9+ byte VINT -- not supported (surfaces as a
// too-short-header warning at the call site, same as any other unparseable header).
private fun vintLength(firstByte: Int): Int? {
    if (firstByte == 0) return null
    var mask = 0x80
    var length = 1
    while ((firstByte and mask) == 0) {
        mask = mask shr 1
        length++
    }
    return length
}

// Reads the numeric value of a `length`-byte VINT starting at offset. Element IDs keep their
// leading marker bit as part of the value (EBML convention -- IDs are canonically written/matched
// including their length marker, e.g. the EBML header's own ID is 0x1A45DFA3, not 0x0A45DFA3);
// element sizes strip the marker to get the actual numeric size.
private fun readVintValue(reader: ByteReader, offset: Long, length: Int, stripMarker: Boolean): Long {
    val first = reader.readUInt8(offset)
    val marker = 0x80 shr (length - 1)
    var value = (if (stripMarker) first and (marker - 1) else first).toLong()
    for (i in 1 until length) {
        value = (value shl 8) or reader.readUInt8(offset + i).toLong()
    }
    return value
}

private fun readUnsignedBigEndian(reader: ByteReader, offset: Long, length: Int): Long {
    var value = 0L
    for (i in 0 until length) {
        value = (value shl 8) or reader.readUInt8(offset + i).toLong()
    }
    return value
}

private fun decodeLeafElement(
    reader: ByteReader,
    name: String,
    offset: Long,
    headerSize: Int,
    size: Long,
    type: EbmlElementType?,
    dataStart: Long,
    dataEnd: Long,
    warnings: List<String>,
): BoxNode {
    val dataLength = (dataEnd - dataStart).toInt()
    if (dataLength <= 0) return BoxNode(name, offset, headerSize, size, warnings = warnings)
    return when (type) {
        EbmlElementType.UINT, EbmlElementType.DATE -> {
            val value = readUnsignedBigEndian(reader, dataStart, dataLength)
            BoxNode(
                name, offset, headerSize, size,
                fields = listOf(BoxField("value", value.toString(), dataStart, dataLength.toLong())),
                summary = value.toString(), warnings = warnings,
            )
        }
        EbmlElementType.STRING -> {
            val value = String(reader.readBytes(dataStart, dataLength), Charsets.US_ASCII).trimEnd(' ')
            BoxNode(
                name, offset, headerSize, size,
                fields = listOf(BoxField("value", value, dataStart, dataLength.toLong())),
                summary = value, warnings = warnings,
            )
        }
        EbmlElementType.UTF8 -> {
            val value = String(reader.readBytes(dataStart, dataLength), Charsets.UTF_8).trimEnd(' ')
            BoxNode(
                name, offset, headerSize, size,
                fields = listOf(BoxField("value", value, dataStart, dataLength.toLong())),
                summary = value, warnings = warnings,
            )
        }
        EbmlElementType.FLOAT -> {
            val bits = readUnsignedBigEndian(reader, dataStart, dataLength)
            val value = when (dataLength) {
                4 -> Float.fromBits(bits.toInt()).toDouble()
                8 -> Double.fromBits(bits)
                else -> 0.0
            }
            BoxNode(
                name, offset, headerSize, size,
                fields = listOf(BoxField("value", value.toString(), dataStart, dataLength.toLong())),
                summary = value.toString(), warnings = warnings,
            )
        }
        else -> BoxNode(name, offset, headerSize, size, summary = "$dataLength byte(s)", warnings = warnings)
    }
}

fun parseEbmlElements(reader: ByteReader, start: Long, end: Long): List<BoxNode> {
    val result = mutableListOf<BoxNode>()
    var pos = start
    while (pos < end) {
        val remaining = end - pos
        val idLength = vintLength(reader.readUInt8(pos))
        if (idLength == null || idLength > 4 || remaining < idLength + 1) {
            result.add(BoxNode("?", pos, 0, remaining, warnings = listOf("Trailing $remaining byte(s): too short for an element header")))
            break
        }
        val id = readVintValue(reader, pos, idLength, stripMarker = false)
        val sizePos = pos + idLength
        val sizeLength = vintLength(reader.readUInt8(sizePos))
        if (sizeLength == null || remaining < idLength + sizeLength) {
            result.add(BoxNode("?", pos, 0, remaining, warnings = listOf("Trailing $remaining byte(s): too short for an element header")))
            break
        }
        val rawSize = readVintValue(reader, sizePos, sizeLength, stripMarker = true)
        val allOnes = (1L shl (7 * sizeLength)) - 1
        val declaredSize: Long? = if (rawSize == allOnes) null else rawSize

        val headerSize = idLength + sizeLength
        val info = EBML_ELEMENTS[id]
        val name = info?.name ?: "0x${id.toString(16).uppercase()}"

        val warnings = mutableListOf<String>()
        var size = declaredSize ?: remaining // unknown size -- extends to the end of this range
        if (pos + size > end) {
            warnings.add("Declared size $size extends ${pos + size - end} byte(s) past the end of its parent")
            size = end - pos
        }

        val dataStart = pos + headerSize
        val dataEnd = pos + size
        val node = if (info?.type == EbmlElementType.MASTER) {
            BoxNode(name, pos, headerSize, size, children = parseEbmlElements(reader, dataStart, dataEnd), warnings = warnings)
        } else {
            decodeLeafElement(reader, name, pos, headerSize, size, info?.type, dataStart, dataEnd, warnings)
        }
        result.add(node)
        pos += size
    }
    return result
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests "com.multiviewer.parser.EbmlWalkerTest"`
Expected: PASS (8/8).

- [ ] **Step 5: Run the full suite**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test`
Expected: `BUILD SUCCESSFUL`, all tests pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/EbmlWalker.kt app/src/test/kotlin/com/multiviewer/parser/EbmlWalkerTest.kt
git commit -m "Add EbmlWalker: parses EBML/Matroska's variable-length element format into BoxNode"
```

---

### Task 2: Wire WebM into file detection and extension support

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/ParseFile.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/AppState.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/ParseFileIntegrationTest.kt`
- Test: `app/src/test/kotlin/com/multiviewer/ui/AppStateTest.kt`

**Interfaces:**
- Consumes: `parseEbmlElements(reader: ByteReader, start: Long, end: Long): List<BoxNode>` from Task 1.
- Produces: nothing new -- `.webm` files now route through `parseFile` end-to-end and are recognized as `MediaType.VIDEO`.

- [ ] **Step 1: Write the failing tests**

In `app/src/test/kotlin/com/multiviewer/parser/ParseFileIntegrationTest.kt`, add this test to the `ParseFileIntegrationTest` class (anywhere among the existing `@Test` functions):

```kotlin
    @Test
    fun `parses a synthetic minimal webm file via the EBML path, not the ISOBMFF path`() {
        val bytes = byteArrayOf(
            0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), // EBML element ID (4 bytes)
            0x87.toByte(), // size = 7 (1-byte VINT)
            0x42, 0x82.toByte(), // DocType element ID (2 bytes)
            0x84.toByte(), // size = 4 (1-byte VINT)
            0x77, 0x65, 0x62, 0x6D, // "webm"
        )
        val tmp = File.createTempFile("multiviewer-webm", ".webm")
        tmp.deleteOnExit()
        tmp.writeBytes(bytes)

        val root = parseFile(tmp)

        assertEquals(1, root.children.size)
        assertEquals("EBML", root.children[0].type)
        assertEquals(1, root.children[0].children.size)
        val docType = root.children[0].children[0]
        assertEquals("DocType", docType.type)
        assertEquals("webm", docType.fields.single { it.name == "value" }.value)
    }
```

In `app/src/test/kotlin/com/multiviewer/ui/AppStateTest.kt`, add this test to the `AppStateTest` class:

```kotlin
    @Test
    fun `openFile classifies a real webm file as MediaType VIDEO`() {
        val video = File.createTempFile("appstate-webm-test-", ".webm")
        video.deleteOnExit()
        val generate = ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "testsrc=duration=1:size=64x48:rate=10",
            "-c:v", "libvpx", "-pix_fmt", "yuv420p", video.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        generate.waitFor()

        val appState = AppState()
        appState.openFile(video)

        val tab = appState.tabs.single()
        waitForLoad(tab)
        assertEquals(null, tab.error)
        assertEquals(MediaType.VIDEO, tab.type)
        video.delete()
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests "com.multiviewer.parser.ParseFileIntegrationTest" --tests "com.multiviewer.ui.AppStateTest"`
Expected: the new `ParseFileIntegrationTest` case fails (webm parses via the ISOBMFF fallback path instead, so `root.children[0].type` won't be `"EBML"`); the new `AppStateTest` case fails (`.webm` isn't in `VIDEO_EXTENSIONS` yet, so `openFile` rejects it as an unsupported extension and `tab.error` is non-null, or no tab is even created -- either way the assertions fail).

- [ ] **Step 3: Add the EBML magic check to ParseFile.kt**

Find:

```kotlin
private fun isMp3Magic(reader: ByteReader): Boolean {
    if (reader.length >= 3 && String(reader.readBytes(0, 3), Charsets.US_ASCII) == "ID3") return true
    if (reader.length < 2) return false
    val b0 = reader.readUInt8(0)
    val b1 = reader.readUInt8(1)
    return b0 == 0xFF && (b1 and 0xE0) == 0xE0
}
```

Replace with:

```kotlin
private fun isMp3Magic(reader: ByteReader): Boolean {
    if (reader.length >= 3 && String(reader.readBytes(0, 3), Charsets.US_ASCII) == "ID3") return true
    if (reader.length < 2) return false
    val b0 = reader.readUInt8(0)
    val b1 = reader.readUInt8(1)
    return b0 == 0xFF && (b1 and 0xE0) == 0xE0
}

private fun isEbmlMagic(reader: ByteReader): Boolean {
    if (reader.length < 4) return false
    return reader.readUInt32(0) == 0x1A45DFA3L
}
```

- [ ] **Step 4: Wire the EBML branch into parseFile's dispatch**

Find:

```kotlin
        val isMp3 = !isJpeg && !isPng && !isBmp && !isGif && !isTiff && !isWebp && !isWav && isMp3Magic(reader)
        val children = when {
            isJpeg -> parseJpegSegments(reader, 0, reader.length)
            isPng -> parsePngChunks(reader, 8, reader.length)
            isBmp -> parseBmpHeaders(reader, 0, reader.length)
            isGif -> parseGifBlocks(reader, 6, reader.length)
            isTiff -> decodeTiff(reader, 0, reader.length)
            isWebp -> parseWebpChunks(reader, 0, reader.length)
            isWav -> parseWavChunks(reader, 0, reader.length)
            isMp3 -> parseMp3(reader, 0, reader.length)
            else -> parseBoxes(reader, 0, reader.length)
        }
```

Replace with:

```kotlin
        val isMp3 = !isJpeg && !isPng && !isBmp && !isGif && !isTiff && !isWebp && !isWav && isMp3Magic(reader)
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

- [ ] **Step 5: Add webm to VIDEO_EXTENSIONS**

In `app/src/main/kotlin/com/multiviewer/ui/AppState.kt`, find:

```kotlin
val VIDEO_EXTENSIONS = listOf("mp4", "mov", "m4v")
```

Replace with:

```kotlin
val VIDEO_EXTENSIONS = listOf("mp4", "mov", "m4v", "webm")
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests "com.multiviewer.parser.ParseFileIntegrationTest" --tests "com.multiviewer.ui.AppStateTest"`
Expected: PASS.

- [ ] **Step 7: Run the full suite**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test`
Expected: `BUILD SUCCESSFUL`, all tests pass.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/ParseFile.kt app/src/main/kotlin/com/multiviewer/ui/AppState.kt app/src/test/kotlin/com/multiviewer/parser/ParseFileIntegrationTest.kt app/src/test/kotlin/com/multiviewer/ui/AppStateTest.kt
git commit -m "Route .webm files to the EBML walker and recognize them as video"
```

---

### Task 3: WebM media summary

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/WebmMediaSummaryBuilderTest.kt`

**Interfaces:**
- Consumes: the `BoxNode` tree shape Task 1's `EbmlWalker` produces for a real WebM file (an `EBML` node and a `Segment` node as top-level siblings; `Segment` containing `Info` and `Tracks`; `Tracks` containing `TrackEntry` children; each `TrackEntry` containing leaf fields like `TrackType`/`CodecID` and a nested `Video` or `Audio` master with its own leaf fields) -- all leaf values are read from a `BoxField` named `"value"`, per Task 1's `decodeLeafElement`.
- Produces: nothing new for other files -- `buildMediaSummary` (unchanged signature) now returns a populated summary for WebM roots instead of misclassifying/failing.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/multiviewer/parser/WebmMediaSummaryBuilderTest.kt`:

```kotlin
package com.multiviewer.parser

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class WebmMediaSummaryBuilderTest {
    private fun webmLeaf(type: String, value: String): BoxNode =
        BoxNode(type, 0, 0, 0, fields = listOf(BoxField("value", value, 0, 0)))

    private fun buildWebmFixture(includeAudioTrack: Boolean): BoxNode {
        val info = BoxNode(
            "Info", 0, 0, 0,
            children = listOf(
                webmLeaf("TimecodeScale", "1000000"),
                webmLeaf("Duration", "20000.0"),
            ),
        )
        val videoTrackEntry = BoxNode(
            "TrackEntry", 0, 0, 0,
            children = listOf(
                webmLeaf("TrackType", "1"),
                webmLeaf("CodecID", "V_VP9"),
                BoxNode("Video", 0, 0, 0, children = listOf(webmLeaf("PixelWidth", "1920"), webmLeaf("PixelHeight", "1080"))),
            ),
        )
        val trackEntries = mutableListOf(videoTrackEntry)
        if (includeAudioTrack) {
            trackEntries.add(
                BoxNode(
                    "TrackEntry", 0, 0, 0,
                    children = listOf(
                        webmLeaf("TrackType", "2"),
                        webmLeaf("CodecID", "A_OPUS"),
                        BoxNode("Audio", 0, 0, 0, children = listOf(webmLeaf("SamplingFrequency", "48000.0"), webmLeaf("Channels", "2"))),
                    ),
                ),
            )
        }
        val tracks = BoxNode("Tracks", 0, 0, 0, children = trackEntries)
        val segment = BoxNode("Segment", 0, 0, 0, children = listOf(info, tracks))
        val ebml = BoxNode("EBML", 0, 0, 0)
        return BoxNode("root", 0, 0, 0, children = listOf(ebml, segment))
    }

    @Test
    fun `an EBML root is classified as VIDEO`() {
        val root = buildWebmFixture(includeAudioTrack = false)
        val tmp = File.createTempFile("webm-summary-category-test", ".webm")
        tmp.deleteOnExit()
        tmp.writeBytes(ByteArray(10))

        val summary = buildMediaSummary(root, tmp)

        assertEquals(MediaCategory.VIDEO, summary.category)
    }

    @Test
    fun `a full WebM tree produces General, Track List, Video, and Audio sections with correct values`() {
        val root = buildWebmFixture(includeAudioTrack = true)
        val tmp = File.createTempFile("webm-summary-test", ".webm")
        tmp.deleteOnExit()
        tmp.writeBytes(ByteArray(1_250_000))

        val summary = buildMediaSummary(root, tmp)

        assertEquals(4, summary.sections.size)

        val general = summary.sections.first { it.title == "General" }
        assertEquals("0:00:20.000", general.fields.first { it.label == "Duration" }.value)
        assertEquals("WebM", general.fields.first { it.label == "Format" }.value)
        assertEquals("500.0 Kbps", general.fields.first { it.label == "Overall Bit Rate" }.value)

        val trackList = summary.sections.first { it.title == "Track List" }
        assertEquals("1", trackList.fields.first { it.label == "Video Tracks" }.value)
        assertEquals("1", trackList.fields.first { it.label == "Audio Tracks" }.value)

        val videoDetail = summary.sections.first { it.title == "Video" }
        assertEquals("VP9", videoDetail.fields.first { it.label == "Format" }.value)
        assertEquals("1920", videoDetail.fields.first { it.label == "Width" }.value)
        assertEquals("1080", videoDetail.fields.first { it.label == "Height" }.value)

        val audioDetail = summary.sections.first { it.title == "Audio" }
        assertEquals("Opus", audioDetail.fields.first { it.label == "Format" }.value)
        assertEquals("48000.0 Hz", audioDetail.fields.first { it.label == "Sampling Rate" }.value)
        assertEquals("2", audioDetail.fields.first { it.label == "Channel(s)" }.value)
    }

    @Test
    fun `a WebM tree with no audio track omits the Audio section`() {
        val root = buildWebmFixture(includeAudioTrack = false)
        val tmp = File.createTempFile("webm-summary-video-only-test", ".webm")
        tmp.deleteOnExit()
        tmp.writeBytes(ByteArray(1_250_000))

        val summary = buildMediaSummary(root, tmp)

        assertEquals(3, summary.sections.size)
        assertEquals(null, summary.sections.find { it.title == "Audio" })
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests "com.multiviewer.parser.WebmMediaSummaryBuilderTest"`
Expected: the first test fails (an EBML root currently falls through to `MediaCategory.IMAGE`, since `detectCategory` has no WebM check yet); the other two fail to compile/run correctly since `buildWebmVideoSummary` doesn't exist.

- [ ] **Step 3: Add isWebm and route detectCategory/buildMediaSummary through it**

Find:

```kotlin
fun buildMediaSummary(root: BoxNode, file: File): MediaSummary {
    val category = detectCategory(root)
    val sections = when (category) {
        MediaCategory.IMAGE -> buildImageSummary(root, file)
        MediaCategory.VIDEO -> buildVideoSummary(root, file.length())
        MediaCategory.AUDIO -> buildStandaloneAudioSummary(root, file.length())
    }
```

Replace with:

```kotlin
fun buildMediaSummary(root: BoxNode, file: File): MediaSummary {
    val category = detectCategory(root)
    val sections = when (category) {
        MediaCategory.IMAGE -> buildImageSummary(root, file)
        MediaCategory.VIDEO -> if (isWebm(root)) buildWebmVideoSummary(root, file.length()) else buildVideoSummary(root, file.length())
        MediaCategory.AUDIO -> buildStandaloneAudioSummary(root, file.length())
    }
```

Find:

```kotlin
private fun detectCategory(root: BoxNode): MediaCategory {
    if (root.children.any { it.type == "SOI" }) return MediaCategory.IMAGE
    val ftyp = root.children.find { it.type == "ftyp" }
    val majorBrand = ftyp?.fields?.find { it.name == "major_brand" }?.value
    if (majorBrand == "avif" || majorBrand == "avis" || majorBrand == "heic") return MediaCategory.IMAGE
    
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

Replace with:

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

private fun isWebm(root: BoxNode): Boolean = root.children.any { it.type == "EBML" }
```

- [ ] **Step 4: Add the WebM summary builder functions**

Find the start of `private fun buildVideoSummary(root: BoxNode, fileSizeBytes: Long): List<SummarySection> {` and insert the following new functions directly above it (do not modify `buildVideoSummary` itself or anything below it):

```kotlin
private val WEBM_CODEC_DISPLAY_NAMES = mapOf(
    "V_VP8" to "VP8",
    "V_VP9" to "VP9",
    "V_AV1" to "AV1",
    "A_OPUS" to "Opus",
    "A_VORBIS" to "Vorbis",
)

private fun buildWebmVideoSummary(root: BoxNode, fileSizeBytes: Long): List<SummarySection> {
    val segment = root.children.find { it.type == "Segment" }
    val info = segment?.children?.find { it.type == "Info" }
    val tracks = segment?.children?.find { it.type == "Tracks" }
    val trackEntries = tracks?.children?.filter { it.type == "TrackEntry" } ?: emptyList()
    val videoTrack = trackEntries.find { entryTrackType(it) == 1L }
    val audioTrack = trackEntries.find { entryTrackType(it) == 2L }

    val sections = mutableListOf<SummarySection>()
    sections.add(buildWebmGeneral(fileSizeBytes, info))
    sections.add(buildWebmTrackList(trackEntries))
    buildWebmVideoDetail(videoTrack)?.let { sections.add(it) }
    buildWebmAudioDetail(audioTrack)?.let { sections.add(it) }
    return sections
}

private fun entryTrackType(trackEntry: BoxNode): Long? =
    webmFieldValue(trackEntry, "TrackType")?.toLongOrNull()

private fun webmFieldValue(node: BoxNode?, childType: String): String? =
    node?.children?.find { it.type == childType }?.fields?.find { it.name == "value" }?.value

private fun webmCodecDisplayName(codecId: String?): String? =
    codecId?.let { WEBM_CODEC_DISPLAY_NAMES[it] ?: it }

private fun buildWebmGeneral(fileSizeBytes: Long, info: BoxNode?): SummarySection {
    val fields = mutableListOf<SummaryField>()
    val timecodeScale = webmFieldValue(info, "TimecodeScale")?.toDoubleOrNull() ?: 1_000_000.0
    val durationTicks = webmFieldValue(info, "Duration")?.toDoubleOrNull()
    val durationSeconds = durationTicks?.let { it * timecodeScale / 1_000_000_000.0 }
    durationSeconds?.let { fields.add(SummaryField("Duration", formatDuration(it))) }
    fields.add(SummaryField("File Size", formatFileSize(fileSizeBytes)))
    fields.add(SummaryField("Format", "WebM"))
    if (durationSeconds != null && durationSeconds > 0) {
        val bitrate = (fileSizeBytes * 8) / durationSeconds
        fields.add(SummaryField("Overall Bit Rate", formatBitrate(bitrate)))
    }
    return SummarySection("General", fields)
}

private fun buildWebmTrackList(trackEntries: List<BoxNode>): SummarySection {
    val videoCount = trackEntries.count { entryTrackType(it) == 1L }
    val audioCount = trackEntries.count { entryTrackType(it) == 2L }
    val otherCount = trackEntries.size - videoCount - audioCount
    val fields = mutableListOf(
        SummaryField("Video Tracks", videoCount.toString()),
        SummaryField("Audio Tracks", audioCount.toString()),
    )
    if (otherCount > 0) fields.add(SummaryField("Other Tracks", otherCount.toString()))
    return SummarySection("Track List", fields)
}

private fun buildWebmVideoDetail(videoTrack: BoxNode?): SummarySection? {
    if (videoTrack == null) return null
    val fields = mutableListOf<SummaryField>()
    webmCodecDisplayName(webmFieldValue(videoTrack, "CodecID"))?.let { fields.add(SummaryField("Format", it)) }
    val video = videoTrack.children.find { it.type == "Video" }
    webmFieldValue(video, "PixelWidth")?.let { fields.add(SummaryField("Width", it)) }
    webmFieldValue(video, "PixelHeight")?.let { fields.add(SummaryField("Height", it)) }
    return if (fields.isEmpty()) null else SummarySection("Video", fields)
}

private fun buildWebmAudioDetail(audioTrack: BoxNode?): SummarySection? {
    if (audioTrack == null) return null
    val fields = mutableListOf<SummaryField>()
    webmCodecDisplayName(webmFieldValue(audioTrack, "CodecID"))?.let { fields.add(SummaryField("Format", it)) }
    val audio = audioTrack.children.find { it.type == "Audio" }
    webmFieldValue(audio, "SamplingFrequency")?.toDoubleOrNull()?.let {
        fields.add(SummaryField("Sampling Rate", "$it Hz"))
    }
    webmFieldValue(audio, "Channels")?.let { fields.add(SummaryField("Channel(s)", it)) }
    return if (fields.isEmpty()) null else SummarySection("Audio", fields)
}

```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests "com.multiviewer.parser.WebmMediaSummaryBuilderTest"`
Expected: PASS (3/3).

- [ ] **Step 6: Run the full suite**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test`
Expected: `BUILD SUCCESSFUL`, all tests pass.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt app/src/test/kotlin/com/multiviewer/parser/WebmMediaSummaryBuilderTest.kt
git commit -m "Add WebM media summary: General/Track List/Video/Audio sections from the EBML tree"
```

---

### Task 4: Manual end-to-end verification

**Files:** none (verification only, no code changes).

**Interfaces:** none.

- [ ] **Step 1: Generate a real WebM fixture with both video and audio**

Run:
```bash
ffmpeg -y -f lavfi -i "testsrc=duration=5:size=640x480:rate=30" -f lavfi -i "sine=frequency=440:duration=5" \
  -c:v libvpx-vp9 -c:a libopus /tmp/webm-verify.webm
```

- [ ] **Step 2: Verify via the CLI dump/check commands**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew jar` then run the packaged jar's `dump` and `check` subcommands against `/tmp/webm-verify.webm` (same invocation style already used to verify `dump`/`check` for other formats earlier this session). Confirm the JSON tree shows a real `EBML`/`Segment`/`Tracks`/`TrackEntry` structure (not a `"?"` fallback node), and `check` reports `warningCount: 0` for this well-formed file.

- [ ] **Step 3: Manual GUI verification**

Build and run the app (`export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew run`), open `/tmp/webm-verify.webm`. Confirm: the structure tree shows the EBML element tree; the Detailed Properties/summary panel shows Duration/Format/Track List/Video/Audio sections with sensible values; the video plays back via the existing player; clicking "프레임 분석 시작" (after it enables, per the earlier gating work) successfully runs GOP frame analysis on the file.

---
