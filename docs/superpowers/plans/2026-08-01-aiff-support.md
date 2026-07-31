# AIFF/AIFF-C Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add AIFF and AIFF-C to the app's supported audio formats: playable via the existing `FfmpegAudioPlayer` (zero new code needed there — ffmpeg already decodes AIFF), and structurally parseable in the tree/summary/warnings views via a new dedicated chunk-based walker. This is the third and last of the three planned audio-format additions (FLAC, OGG/Opus, now AIFF).

**Architecture:** A new `AiffWalker.kt` parses the `"FORM"`/`"AIFF"`/`"AIFC"` IFF chunk container — structurally almost identical to `WavWalker.kt`'s RIFF chunk loop, but big-endian throughout (so it reuses `ByteReader`'s native `readUInt16`/`readUInt32` directly, with no custom endian helpers needed). The one new piece of logic is a hand-rolled decoder for the `COMM` chunk's `sampleRate` field, which AIFF stores as a 10-byte 80-bit IEEE extended-precision float rather than an ordinary 32/64-bit float. `ParseFile.kt` routes `.aiff`/`.aif`/`.aifc` files to it by magic bytes; `AppState.kt` adds those three extensions to `AUDIO_EXTENSIONS`; `MediaSummaryBuilder.kt` recognizes a `"COMM"` root child for category detection (mirroring WAV's own `"fmt "` check) and builds an AIFF-specific summary.

**Tech Stack:** Kotlin, no new dependencies.

## Global Constraints

- Every new format walker reuses `BoxNode`/`BoxField` unchanged — no changes to the tree view, `collectWarnings`, or CLI `dump`/`check`.
- The `SSND` chunk's actual PCM sample bytes are never decoded — represented as a byte-count summary only (`offset`/`block_size` fields are decoded, since those are small fixed header fields, not sample data).
- `MARK`/`INST`/`COMT`/text chunks (`NAME`/`AUTH`/`"(c) "`/`ANNO`) and any other unrecognized chunk type get a byte-count-only summary, no field decoding.
- AIFF-C `compressionType` values are mapped to a friendly name via a small lookup table for common types only; anything else falls back to `"Unknown (fourCC)"` — this matches this app's established fallback-naming convention (FLAC's unknown block types, WebM's unknown element IDs). Do not attempt to build an exhaustive compression-type table.
- The 80-bit extended-precision `sample_rate` decode: `value = sign * (mantissa as unsigned 64-bit) * 2^(exponent - 16383 - 63)`, where `exponent` is the low 15 bits of the first 2 bytes and `mantissa` is the following 8 bytes read as an unsigned 64-bit integer (the leading integer bit is explicit in this format, unlike normal IEEE754, so no extra `+1` bit is added to the mantissa). Verified against a real ffmpeg-generated AIFF fixture: bytes `40 0E AC 44 00 00 00 00 00 00` must decode to exactly `44100.0`.
- `ffmpeg` must be on `PATH` for any test that shells out to generate a real `.aiff` fixture (matches this project's existing test conventions). Plain `ffmpeg -i <source> out.aiff` (no explicit `-c:a`) produces a valid AIFF file on this machine — no special codec flags are needed, unlike the earlier OGG/Vorbis plan's `libvorbis` workaround.

---

### Task 1: `AiffWalker.kt` core parser

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/AiffWalker.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/AiffWalkerTest.kt`

**Interfaces:**
- Consumes: `ByteReader` (`readUInt8`, `readUInt16`, `readUInt32`, `readUInt64`, `readFourCC`, `readBytes` — all in `app/src/main/kotlin/com/multiviewer/parser/ByteReader.kt`, all big-endian), `BoxNode`/`BoxField` (`app/src/main/kotlin/com/multiviewer/parser/BoxNode.kt`).
- Produces: `fun parseAiffChunks(reader: ByteReader, start: Long, end: Long): List<BoxNode>` — the only public symbol. Task 2 calls this directly from `ParseFile.kt`.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/multiviewer/parser/AiffWalkerTest.kt`:

```kotlin
package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AiffWalkerTest {
    private fun uint32BE(value: Long): ByteArray = byteArrayOf(
        ((value shr 24) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )

    private fun uint16BE(value: Int): ByteArray = byteArrayOf(
        ((value shr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )

    // The 80-bit extended-precision encoding of 44100.0 -- independently hand-verified: bytes
    // 40 0E = sign 0, biased exponent 0x400E - 0x3FFF = 15; mantissa AC44000000000000 =
    // 44100 * 2^48; value = (44100 * 2^48 / 2^63) * 2^15 = 44100 * 2^0 = 44100. Also confirmed
    // against a real ffmpeg-generated .aiff fixture, which produces this exact byte sequence.
    private val extendedSampleRate44100 = byteArrayOf(0x40, 0x0E, 0xAC.toByte(), 0x44, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)

    @Test
    fun `parses the FORM marker and decodes a COMM chunk's fields for classic AIFF`() {
        val commPayload = uint16BE(1) + uint32BE(44100) + uint16BE(16) + extendedSampleRate44100
        assertEquals(18, commPayload.size)
        val commChunk = "COMM".toByteArray(Charsets.US_ASCII) + uint32BE(commPayload.size.toLong()) + commPayload
        val bytes = "FORM".toByteArray(Charsets.US_ASCII) + uint32BE((4 + commChunk.size).toLong()) +
            "AIFF".toByteArray(Charsets.US_ASCII) + commChunk
        val reader = byteReaderOf(bytes)

        val elements = parseAiffChunks(reader, 0, reader.length)

        assertEquals(2, elements.size)
        val form = elements[0]
        assertEquals("FORM", form.type)
        assertEquals(0L, form.offset)
        assertEquals(8, form.headerSize)
        assertEquals(12L, form.size)
        assertEquals("AIFF", form.fields.single { it.name == "form_type" }.value)

        val comm = elements[1]
        assertEquals("COMM", comm.type)
        assertEquals("1", comm.fields.single { it.name == "num_channels" }.value)
        assertEquals("44100", comm.fields.single { it.name == "num_sample_frames" }.value)
        assertEquals("16", comm.fields.single { it.name == "sample_size" }.value)
        assertEquals("44100", comm.fields.single { it.name == "sample_rate" }.value)
        reader.close()
    }

    @Test
    fun `decodes a COMM chunk's extra compression_type and compression_name fields for AIFF-C`() {
        val compressionName = "not compressed"
        val commPayload = uint16BE(2) + uint32BE(44100) + uint16BE(16) + extendedSampleRate44100 +
            "NONE".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(compressionName.length.toByte()) + compressionName.toByteArray(Charsets.US_ASCII)
        val commChunk = "COMM".toByteArray(Charsets.US_ASCII) + uint32BE(commPayload.size.toLong()) + commPayload
        val bytes = "FORM".toByteArray(Charsets.US_ASCII) + uint32BE((4 + commChunk.size).toLong()) +
            "AIFC".toByteArray(Charsets.US_ASCII) + commChunk
        val reader = byteReaderOf(bytes)

        val elements = parseAiffChunks(reader, 0, reader.length)

        assertEquals("AIFF-C", elements[0].fields.single { it.name == "form_type" }.value)
        val comm = elements[1]
        assertEquals("PCM (uncompressed)", comm.fields.single { it.name == "compression_type" }.value)
        assertEquals("not compressed", comm.fields.single { it.name == "compression_name" }.value)
        reader.close()
    }

    @Test
    fun `decodes an SSND chunk's offset and block_size fields plus a byte-count summary`() {
        val ssndPayload = uint32BE(0) + uint32BE(0) + byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val ssndChunk = "SSND".toByteArray(Charsets.US_ASCII) + uint32BE(ssndPayload.size.toLong()) + ssndPayload
        val bytes = "FORM".toByteArray(Charsets.US_ASCII) + uint32BE((4 + ssndChunk.size).toLong()) +
            "AIFF".toByteArray(Charsets.US_ASCII) + ssndChunk
        val reader = byteReaderOf(bytes)

        val elements = parseAiffChunks(reader, 0, reader.length)

        assertEquals(2, elements.size)
        val ssnd = elements[1]
        assertEquals("SSND", ssnd.type)
        assertEquals("0", ssnd.fields.single { it.name == "offset" }.value)
        assertEquals("0", ssnd.fields.single { it.name == "block_size" }.value)
        assertEquals("Audio sample data (4 bytes)", ssnd.summary)
        reader.close()
    }

    @Test
    fun `an unrecognized chunk type falls back to a byte-count-only summary`() {
        val payload = byteArrayOf(0x11, 0x22, 0x33)
        val chunk = "ANNO".toByteArray(Charsets.US_ASCII) + uint32BE(payload.size.toLong()) + payload
        val bytes = "FORM".toByteArray(Charsets.US_ASCII) + uint32BE((4 + chunk.size).toLong()) +
            "AIFF".toByteArray(Charsets.US_ASCII) + chunk
        val reader = byteReaderOf(bytes)

        val elements = parseAiffChunks(reader, 0, reader.length)

        assertEquals(2, elements.size)
        assertEquals("ANNO", elements[1].type)
        assertTrue(elements[1].fields.isEmpty())
        assertEquals("3 byte(s)", elements[1].summary)
        reader.close()
    }

    @Test
    fun `a chunk declared to extend past the end of the file produces a warning and stops`() {
        val chunk = "COMM".toByteArray(Charsets.US_ASCII) + uint32BE(100) + byteArrayOf(0x01, 0x02, 0x03)
        val bytes = "FORM".toByteArray(Charsets.US_ASCII) + uint32BE((4 + chunk.size).toLong()) +
            "AIFF".toByteArray(Charsets.US_ASCII) + chunk
        val reader = byteReaderOf(bytes)

        val elements = parseAiffChunks(reader, 0, reader.length)

        assertEquals(2, elements.size)
        assertEquals("COMM", elements[1].type)
        assertTrue(elements[1].warnings.single().contains("extends"))
        reader.close()
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests "com.multiviewer.parser.AiffWalkerTest"`
Expected: FAIL — `parseAiffChunks` is unresolved (file doesn't exist yet).

- [ ] **Step 3: Write the implementation**

Create `app/src/main/kotlin/com/multiviewer/parser/AiffWalker.kt`:

```kotlin
package com.multiviewer.parser

private val AIFF_COMPRESSION_TYPE_NAMES = mapOf(
    "NONE" to "PCM (uncompressed)",
    "sowt" to "PCM (little-endian)",
    "fl32" to "32-bit float",
    "fl64" to "64-bit float",
    "ima4" to "IMA 4:1 ADPCM",
    "MAC3" to "MACE 3:1",
    "MAC6" to "MACE 6:1",
    "ulaw" to "µ-law",
    "ULAW" to "µ-law",
    "alaw" to "A-law",
    "ALAW" to "A-law",
    "Qclp" to "Qualcomm PureVoice",
    "QDMC" to "QDesign Music",
    "QDM2" to "QDesign Music 2",
)

fun parseAiffChunks(reader: ByteReader, start: Long, end: Long): List<BoxNode> {
    val result = mutableListOf<BoxNode>()
    if (end - start < 12) return result

    val formSize = reader.readUInt32(start + 4)
    val formType = reader.readFourCC(start + 8)
    result.add(
        BoxNode(
            type = "FORM", offset = start, headerSize = 8, size = 12,
            fields = listOf(
                BoxField("file_size", (formSize + 8).toString(), start + 4, 4),
                BoxField("form_type", if (formType == "AIFC") "AIFF-C" else formType, start + 8, 4),
            ),
        ),
    )

    var pos = start + 12
    while (pos + 8 <= end) {
        val type = reader.readFourCC(pos)
        val chunkSize = reader.readUInt32(pos + 4)
        val totalSize = 8 + chunkSize
        val paddedSize = if (chunkSize % 2 == 1L) totalSize + 1 else totalSize

        if (pos + totalSize > end) {
            result.add(BoxNode(type, pos, 8, end - pos, warnings = listOf("Chunk extends past end of file")))
            break
        }

        result.add(decodeAiffChunk(reader, type, pos, chunkSize, totalSize, formType))
        pos += paddedSize
    }
    return result
}

private fun decodeAiffChunk(reader: ByteReader, type: String, offset: Long, payloadSize: Long, totalSize: Long, formType: String): BoxNode {
    val payloadStart = offset + 8
    val fields = mutableListOf<BoxField>()
    var summary: String? = null

    when (type) {
        "COMM" -> {
            if (payloadSize >= 18) {
                val numChannels = reader.readUInt16(payloadStart)
                val numSampleFrames = reader.readUInt32(payloadStart + 2)
                val sampleSize = reader.readUInt16(payloadStart + 6)
                val sampleRate = readExtendedFloat80(reader, payloadStart + 8)
                val sampleRateRounded = Math.round(sampleRate)
                fields.add(BoxField("num_channels", numChannels.toString(), payloadStart, 2))
                fields.add(BoxField("num_sample_frames", numSampleFrames.toString(), payloadStart + 2, 4))
                fields.add(BoxField("sample_size", sampleSize.toString(), payloadStart + 6, 2))
                fields.add(BoxField("sample_rate", sampleRateRounded.toString(), payloadStart + 8, 10))
                var formatName = "PCM"
                if (formType == "AIFC" && payloadSize >= 18 + 4) {
                    val compressionType = reader.readFourCC(payloadStart + 18)
                    formatName = AIFF_COMPRESSION_TYPE_NAMES[compressionType] ?: "Unknown ($compressionType)"
                    fields.add(BoxField("compression_type", formatName, payloadStart + 18, 4))
                    if (payloadSize >= 18 + 4 + 1) {
                        val nameLength = reader.readUInt8(payloadStart + 22)
                        if (payloadSize >= 18 + 4 + 1 + nameLength) {
                            val compressionName = String(reader.readBytes(payloadStart + 23, nameLength), Charsets.US_ASCII)
                            fields.add(BoxField("compression_name", compressionName, payloadStart + 23, nameLength.toLong()))
                        }
                    }
                }
                summary = "$formatName, ${numChannels}ch, ${sampleRateRounded}Hz, ${sampleSize}-bit"
            }
        }
        "SSND" -> {
            if (payloadSize >= 8) {
                val ssndOffset = reader.readUInt32(payloadStart)
                val blockSize = reader.readUInt32(payloadStart + 4)
                fields.add(BoxField("offset", ssndOffset.toString(), payloadStart, 4))
                fields.add(BoxField("block_size", blockSize.toString(), payloadStart + 4, 4))
                summary = "Audio sample data (${payloadSize - 8} bytes)"
            }
        }
        else -> {
            summary = "$payloadSize byte(s)"
        }
    }

    return BoxNode(type = type, offset = offset, headerSize = 8, size = totalSize, fields = fields, summary = summary)
}

private fun readExtendedFloat80(reader: ByteReader, offset: Long): Double {
    val signExponent = reader.readUInt16(offset)
    val sign = if ((signExponent and 0x8000) != 0) -1.0 else 1.0
    val exponent = signExponent and 0x7FFF
    val mantissa = reader.readUInt64(offset + 2)
    if (exponent == 0 && mantissa == 0L) return 0.0
    return sign * mantissa.toULong().toDouble() * Math.pow(2.0, (exponent - 16383 - 63).toDouble())
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests "com.multiviewer.parser.AiffWalkerTest"`
Expected: PASS, 5/5 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/AiffWalker.kt app/src/test/kotlin/com/multiviewer/parser/AiffWalkerTest.kt
git commit -m "feat: add AiffWalker for AIFF/AIFF-C container structural parsing"
```

---

### Task 2: Wire AIFF/AIFF-C into `ParseFile.kt` and `AppState.kt`

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/ParseFile.kt` (the magic-byte dispatch chain, currently ending with `isOgg`/`isOggMagic` after the OGG-support plan)
- Modify: `app/src/main/kotlin/com/multiviewer/ui/AppState.kt` (`AUDIO_EXTENSIONS`, currently `listOf("m4a", "mp3", "wav", "flac", "ogg", "opus")`, and its preceding comment block)
- Test: `app/src/test/kotlin/com/multiviewer/parser/ParseFileIntegrationTest.kt`

**Interfaces:**
- Consumes: `parseAiffChunks(reader: ByteReader, start: Long, end: Long): List<BoxNode>` from Task 1.
- Produces: `.aiff`/`.aif`/`.aifc` files are recognized as `MediaType.AUDIO` by `AppState.openFile` and routed to `parseAiffChunks` by `parseFile`. Task 3 relies on this routing being in place.

- [ ] **Step 1: Write the failing test**

In `app/src/test/kotlin/com/multiviewer/parser/ParseFileIntegrationTest.kt`, add this test (place it after the most recent format-routing test in the file). This test reuses the file's existing private `uint32(value: Long): ByteArray` helper (already defined near the bottom of the file, big-endian, used by the MP4 box tests) — do not redefine it:

```kotlin
    @Test
    fun `parses a synthetic minimal aiff file via the AIFF path, not the ISOBMFF path`() {
        val extendedSampleRate44100 = byteArrayOf(0x40, 0x0E, 0xAC.toByte(), 0x44, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        val commPayload = byteArrayOf(0x00, 0x01) + uint32(44100) + byteArrayOf(0x00, 0x10) + extendedSampleRate44100
        val commChunk = "COMM".toByteArray(Charsets.US_ASCII) + uint32(commPayload.size.toLong()) + commPayload
        val bytes = "FORM".toByteArray(Charsets.US_ASCII) + uint32((4 + commChunk.size).toLong()) +
            "AIFF".toByteArray(Charsets.US_ASCII) + commChunk
        val tmp = File.createTempFile("multiviewer-aiff", ".aiff")
        tmp.deleteOnExit()
        tmp.writeBytes(bytes)

        val root = parseFile(tmp)

        assertEquals(listOf("FORM", "COMM"), root.children.map { it.type })
        assertEquals("44100", root.children[1].fields.single { it.name == "sample_rate" }.value)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests "com.multiviewer.parser.ParseFileIntegrationTest"`
Expected: FAIL — the new test fails because `parseFile` doesn't recognize `.aiff` yet (falls through to `parseBoxes`).

- [ ] **Step 3: Wire the routing**

In `app/src/main/kotlin/com/multiviewer/parser/ParseFile.kt`, find:

```kotlin
        val isOgg = !isJpeg && !isPng && !isBmp && !isGif && !isTiff && !isWebp && !isWav && !isMp3 && !isEbml && !isFlac && isOggMagic(reader)
        val children = when {
```

Change it to:

```kotlin
        val isOgg = !isJpeg && !isPng && !isBmp && !isGif && !isTiff && !isWebp && !isWav && !isMp3 && !isEbml && !isFlac && isOggMagic(reader)
        val isAiff = !isJpeg && !isPng && !isBmp && !isGif && !isTiff && !isWebp && !isWav && !isMp3 && !isEbml && !isFlac && !isOgg && isAiffMagic(reader)
        val children = when {
```

Then find the `when` block's `isOgg -> parseOggPages(reader, 0, reader.length)` line and add a new branch immediately after it (before `else`):

```kotlin
            isOgg -> parseOggPages(reader, 0, reader.length)
            isAiff -> parseAiffChunks(reader, 0, reader.length)
            else -> parseBoxes(reader, 0, reader.length)
```

Then add a new private function right after `isOggMagic` (which currently ends the file's private-helper section):

```kotlin
private fun isAiffMagic(reader: ByteReader): Boolean {
    if (reader.length < 12) return false
    if (reader.readFourCC(0) != "FORM") return false
    val formType = reader.readFourCC(8)
    return formType == "AIFF" || formType == "AIFC"
}
```

- [ ] **Step 4: Add the extensions and update the comment**

In `app/src/main/kotlin/com/multiviewer/ui/AppState.kt`, find:

```kotlin
// M4A is an MP4-family container (same ftyp/moov/trak structure as mp4/mov/m4v above) holding an
// audio-only track (AAC, ALAC, or AC-3) -- parseFile's magic-byte dispatch already reaches the
// same generic ISOBMFF box walker for it with no new parser needed, and MediaSummaryBuilder's
// detectCategory/buildVideoSummary already handle a video-less "soun"-only moov correctly (that
// code predates this extension even being routed here). MP3, WAV, FLAC, and OGG each have their
// own dedicated parsers (Mp3Walker/WavWalker/FlacWalker/OggWalker). "opus" files are themselves
// Ogg containers (same "OggS" magic and page format), just carrying an Opus stream instead of
// Vorbis, so they route through the same OggWalker with no separate parser needed.
val AUDIO_EXTENSIONS = listOf("m4a", "mp3", "wav", "flac", "ogg", "opus")
```

Change it to:

```kotlin
// M4A is an MP4-family container (same ftyp/moov/trak structure as mp4/mov/m4v above) holding an
// audio-only track (AAC, ALAC, or AC-3) -- parseFile's magic-byte dispatch already reaches the
// same generic ISOBMFF box walker for it with no new parser needed, and MediaSummaryBuilder's
// detectCategory/buildVideoSummary already handle a video-less "soun"-only moov correctly (that
// code predates this extension even being routed here). MP3, WAV, FLAC, OGG, and AIFF each have
// their own dedicated parsers (Mp3Walker/WavWalker/FlacWalker/OggWalker/AiffWalker). "opus" files
// are themselves Ogg containers (same "OggS" magic and page format), just carrying an Opus stream
// instead of Vorbis, so they route through the same OggWalker with no separate parser needed.
// "aif"/"aifc" are alternate extensions for the same AIFF/AIFF-C container format as "aiff".
val AUDIO_EXTENSIONS = listOf("m4a", "mp3", "wav", "flac", "ogg", "opus", "aiff", "aif", "aifc")
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests "com.multiviewer.parser.ParseFileIntegrationTest"`
Expected: PASS, including the new AIFF test.

Also run the full suite once to confirm nothing else regressed:

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test`
Expected: PASS, no new failures.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/ParseFile.kt app/src/main/kotlin/com/multiviewer/ui/AppState.kt app/src/test/kotlin/com/multiviewer/parser/ParseFileIntegrationTest.kt
git commit -m "feat: route .aiff/.aif/.aifc files to AiffWalker and recognize all three extensions"
```

---

### Task 3: `MediaSummaryBuilder.kt` category detection and AIFF summary

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt` (`detectCategory`, `buildStandaloneAudioSummary`)
- Test: Create `app/src/test/kotlin/com/multiviewer/parser/AiffMediaSummaryBuilderTest.kt`
- Test: Modify `app/src/test/kotlin/com/multiviewer/ui/AppStateTest.kt` (add one real-fixture test, after the existing OGG/Opus tests added by the prior plan)

**Interfaces:**
- Consumes: `BoxNode`/`BoxField`, `SummarySection`/`SummaryField`, `formatDuration`/`formatBitrate`/`formatFileSize` (all already in `MediaSummaryBuilder.kt`), the `"FORM"`/`"COMM"` node types and their field names (`form_type`, `num_channels`, `num_sample_frames`, `sample_size`, `sample_rate`, `compression_type`) produced by Task 1's `AiffWalker.kt`.
- Produces: `buildMediaSummary(root, file)` now returns `MediaCategory.AUDIO` with populated `General`/`Audio` sections for any AIFF or AIFF-C file.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/multiviewer/parser/AiffMediaSummaryBuilderTest.kt`:

```kotlin
package com.multiviewer.parser

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class AiffMediaSummaryBuilderTest {
    private fun buildAiffFixture(formType: String, compressionType: String?): BoxNode {
        val commFields = mutableListOf(
            BoxField("num_channels", "2", 0, 0),
            BoxField("num_sample_frames", "88200", 0, 0),
            BoxField("sample_size", "16", 0, 0),
            BoxField("sample_rate", "44100", 0, 0),
        )
        compressionType?.let { commFields.add(BoxField("compression_type", it, 0, 0)) }
        val comm = BoxNode("COMM", 0, 0, 0, fields = commFields)
        val form = BoxNode("FORM", 0, 0, 0, fields = listOf(BoxField("form_type", formType, 0, 0)))
        return BoxNode("root", 0, 0, 0, children = listOf(form, comm))
    }

    @Test
    fun `an AIFF root is classified as AUDIO`() {
        val root = buildAiffFixture("AIFF", null)
        val tmp = File.createTempFile("aiff-summary-category-test", ".aiff")
        tmp.deleteOnExit()
        tmp.writeBytes(ByteArray(10))

        val summary = buildMediaSummary(root, tmp)

        assertEquals(MediaCategory.AUDIO, summary.category)
    }

    @Test
    fun `a classic AIFF tree produces General and Audio sections with PCM format`() {
        val root = buildAiffFixture("AIFF", null)
        val tmp = File.createTempFile("aiff-summary-test", ".aiff")
        tmp.deleteOnExit()
        tmp.writeBytes(ByteArray(200_000))

        val summary = buildMediaSummary(root, tmp)

        val general = summary.sections.first { it.title == "General" }
        assertEquals("AIFF", general.fields.first { it.label == "Format" }.value)
        assertEquals("0:00:02.000", general.fields.first { it.label == "Duration" }.value)

        val audio = summary.sections.first { it.title == "Audio" }
        assertEquals("PCM", audio.fields.first { it.label == "Format" }.value)
        assertEquals("44100 Hz", audio.fields.first { it.label == "Sampling Rate" }.value)
        assertEquals("2", audio.fields.first { it.label == "Channel(s)" }.value)
        assertEquals("16-bit", audio.fields.first { it.label == "Bit Depth" }.value)
    }

    @Test
    fun `an AIFF-C tree with a compression type shows it as the Audio Format`() {
        val root = buildAiffFixture("AIFF-C", "IMA 4:1 ADPCM")
        val tmp = File.createTempFile("aiffc-summary-test", ".aifc")
        tmp.deleteOnExit()
        tmp.writeBytes(ByteArray(200_000))

        val summary = buildMediaSummary(root, tmp)

        val general = summary.sections.first { it.title == "General" }
        assertEquals("AIFF-C", general.fields.first { it.label == "Format" }.value)

        val audio = summary.sections.first { it.title == "Audio" }
        assertEquals("IMA 4:1 ADPCM", audio.fields.first { it.label == "Format" }.value)
    }
}
```

In `app/src/test/kotlin/com/multiviewer/ui/AppStateTest.kt`, add this test right after the OGG/Opus tests added by the prior plan (`` `openFile opens a real Opus as MediaType_AUDIO with a populated Audio section` ``):

```kotlin
    @Test
    fun `openFile opens a real AIFF as MediaType_AUDIO with a populated Audio section`() {
        val audio = File.createTempFile("appstate-aiff-test-", ".aiff")
        audio.deleteOnExit()
        // No explicit -c:a needed -- plain ffmpeg -i produces a valid AIFF (pcm_s16be) on this machine.
        ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "sine=duration=1:frequency=440",
            audio.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()

        val appState = AppState()
        appState.openFile(audio)
        val tab = appState.tabs.single()
        waitForLoad(tab)

        assertEquals(null, tab.error)
        assertEquals(MediaType.AUDIO, tab.type)
        val generalSection = tab.mediaSummary?.sections?.find { it.title == "General" }
        assertTrue(generalSection?.fields?.any { it.label == "Format" && it.value == "AIFF" } == true, "Expected General/Format=AIFF, got: $generalSection")
        val audioSection = tab.mediaSummary?.sections?.find { it.title == "Audio" }
        assertTrue(audioSection?.fields?.any { it.label == "Sampling Rate" } == true, "Expected an Audio/Sampling Rate field, got: $audioSection")
        audio.delete()
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests "com.multiviewer.parser.AiffMediaSummaryBuilderTest" --tests "com.multiviewer.ui.AppStateTest"`
Expected: FAIL — `AiffMediaSummaryBuilderTest`'s category test gets `MediaCategory.IMAGE` (wrong fallback), and the new `AppStateTest` AIFF test finds no populated `Audio` section (`detectCategory` doesn't yet recognize `"COMM"`).

- [ ] **Step 3: Implement the category detection and summary**

In `app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt`, find:

```kotlin
    if (root.children.any { it.type.startsWith("Ogg") }) return MediaCategory.AUDIO

    val moov = root.children.find { it.type == "moov" } ?: return MediaCategory.IMAGE
```

Change it to:

```kotlin
    if (root.children.any { it.type.startsWith("Ogg") }) return MediaCategory.AUDIO
    if (root.children.any { it.type == "COMM" }) return MediaCategory.AUDIO

    val moov = root.children.find { it.type == "moov" } ?: return MediaCategory.IMAGE
```

Then find:

```kotlin
private fun buildStandaloneAudioSummary(root: BoxNode, fileSizeBytes: Long): List<SummarySection> {
    val fmt = root.children.find { it.type == "fmt " }
    return when {
        fmt != null -> buildWavSummary(root, fmt, fileSizeBytes)
        root.children.any { it.type == "fLaC" } -> buildFlacSummary(root, fileSizeBytes)
        root.children.any { it.type.startsWith("Ogg") } -> buildOggSummary(root, fileSizeBytes)
        else -> buildMp3Summary(root, fileSizeBytes)
    }
}
```

Change it to:

```kotlin
private fun buildStandaloneAudioSummary(root: BoxNode, fileSizeBytes: Long): List<SummarySection> {
    val fmt = root.children.find { it.type == "fmt " }
    return when {
        fmt != null -> buildWavSummary(root, fmt, fileSizeBytes)
        root.children.any { it.type == "fLaC" } -> buildFlacSummary(root, fileSizeBytes)
        root.children.any { it.type.startsWith("Ogg") } -> buildOggSummary(root, fileSizeBytes)
        root.children.any { it.type == "COMM" } -> buildAiffSummary(root, fileSizeBytes)
        else -> buildMp3Summary(root, fileSizeBytes)
    }
}

private fun buildAiffSummary(root: BoxNode, fileSizeBytes: Long): List<SummarySection> {
    val form = root.children.find { it.type == "FORM" }
    val comm = root.children.find { it.type == "COMM" }
    val formType = form?.fields?.find { it.name == "form_type" }?.value ?: "AIFF"

    val generalFields = mutableListOf(
        SummaryField("Format", formType),
        SummaryField("File Size", formatFileSize(fileSizeBytes)),
    )

    val sampleRateField = comm?.fields?.find { it.name == "sample_rate" }?.value
    val sampleRate = sampleRateField?.toDoubleOrNull()
    val numSampleFrames = comm?.fields?.find { it.name == "num_sample_frames" }?.value?.toDoubleOrNull()
    if (sampleRate != null && sampleRate > 0 && numSampleFrames != null) {
        val durationSeconds = numSampleFrames / sampleRate
        generalFields.add(SummaryField("Duration", formatDuration(durationSeconds)))
        if (durationSeconds > 0) {
            val bitrate = (fileSizeBytes * 8) / durationSeconds
            generalFields.add(SummaryField("Overall Bit Rate", formatBitrate(bitrate)))
        }
    }

    val audioFields = mutableListOf<SummaryField>()
    val compressionType = comm?.fields?.find { it.name == "compression_type" }?.value
    audioFields.add(SummaryField("Format", compressionType ?: "PCM"))
    sampleRateField?.let { audioFields.add(SummaryField("Sampling Rate", "$it Hz")) }
    comm?.fields?.find { it.name == "num_channels" }?.let { audioFields.add(SummaryField("Channel(s)", it.value)) }
    comm?.fields?.find { it.name == "sample_size" }?.let { audioFields.add(SummaryField("Bit Depth", "${it.value}-bit")) }

    return listOf(SummarySection("General", generalFields), SummarySection("Audio", audioFields))
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests "com.multiviewer.parser.AiffMediaSummaryBuilderTest" --tests "com.multiviewer.ui.AppStateTest"`
Expected: PASS.

Then run the full suite:

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test`
Expected: PASS, no new failures.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt app/src/test/kotlin/com/multiviewer/parser/AiffMediaSummaryBuilderTest.kt app/src/test/kotlin/com/multiviewer/ui/AppStateTest.kt
git commit -m "feat: classify AIFF/AIFF-C as AUDIO and build an AIFF-specific media summary"
```

---

### Task 4: Manual end-to-end verification (controller-performed)

No automated coverage is possible for this task (Compose UI + audio hardware playback). This step is performed by the controller directly, not dispatched to a subagent.

- [ ] Generate a real fixture: `ffmpeg -y -f lavfi -i "sine=duration=5:frequency=440" /tmp/test-verify.aiff` (no `-c:a` needed)
- [ ] Launch the app (`export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew run`) and open the file
- [ ] Confirm the tree view shows `FORM`, `COMM` (with correct sample_rate/channels/sample_size), and `SSND` with sensible decoded fields, not raw garbage
- [ ] Confirm the Detail Properties panel shows General (Format=AIFF, Duration, File Size, Overall Bit Rate) and Audio (Format=PCM, Sampling Rate, Channel(s), Bit Depth) sections with correct values
- [ ] Confirm playback works: play/pause, the waveform and spectrogram render, the playhead moves, and clicking/dragging on the waveform seeks correctly
- [ ] If any issue is found, treat it as a real bug — return to systematic-debugging, not a quick patch

---

## Self-Review Notes

- **Spec coverage:** FORM marker (classic + AIFF-C form_type display) ✅ (Task 1), COMM field decoding including the 80-bit extended-precision sample_rate ✅ (Task 1), AIFF-C compression_type/compression_name ✅ (Task 1), SSND offset/block_size + byte-count summary ✅ (Task 1), unrecognized-chunk fallback ✅ (Task 1), chunk-extends-past-end warning ✅ (Task 1), wiring into ParseFile/AppState for all three extensions ✅ (Task 2), MediaSummaryBuilder category+summary for both classic AIFF and AIFF-C ✅ (Task 3), manual verification ✅ (Task 4).
- **Placeholder scan:** none found.
- **Type consistency:** `parseAiffChunks(reader: ByteReader, start: Long, end: Long): List<BoxNode>` used identically in Task 1 (definition) and Task 2 (`ParseFile.kt` call site), matching the exact signature shape of `parseFlacBlocks`/`parseOggPages`. Field names (`form_type`, `num_channels`, `num_sample_frames`, `sample_size`, `sample_rate`, `compression_type`) are consistent between Task 1's `AiffWalker.kt` and Task 3's `buildAiffSummary`/`AiffMediaSummaryBuilderTest.kt`.
