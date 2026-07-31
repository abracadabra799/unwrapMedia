# OGG/Opus Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add OGG (Vorbis) and Opus to the app's supported audio formats: playable via the existing `FfmpegAudioPlayer` (zero new code needed there — ffmpeg already decodes both), and structurally parseable in the tree/summary/warnings views via a new dedicated page-based walker.

**Architecture:** A new `OggWalker.kt` scans the file as a sequence of Ogg pages (27-byte fixed header + segment table + payload). Pages whose payload starts with a recognized Vorbis/Opus signature are decoded into detailed nodes; every other page (the bulk of a real file — its actual audio data) is folded into a running count/byte-total, flushed as a single `OggPages` summary node. `ParseFile.kt` routes `.ogg`/`.opus` files to it by magic bytes; `AppState.kt` adds both extensions to `AUDIO_EXTENSIONS`; `MediaSummaryBuilder.kt` recognizes any `"Ogg"`-prefixed root child for category detection and builds a Vorbis/Opus-specific summary, computing duration from the granule position captured off the last page (with Opus's granule-is-always-48kHz quirk handled explicitly).

**Tech Stack:** Kotlin, no new dependencies.

## Global Constraints

- Every new format walker reuses `BoxNode`/`BoxField` unchanged — no changes to the tree view, `collectWarnings`, or CLI `dump`/`check`.
- Every node type `OggWalker.kt` emits is prefixed with `"Ogg"` (`OggPages`, `OggVorbisIdentificationHeader`, `OggVorbisComment`, `OggVorbisSetupHeader`, `OggOpusIdentificationHeader`, `OggOpusTags`) so category detection can use a single `startsWith("Ogg")` check instead of enumerating every type name.
- Packets spanning multiple pages are never reassembled — only a single page's fragment of such a packet is decoded, which may produce a truncation warning instead of full data. This is an accepted limitation, not a bug to fix.
- The Vorbis Setup Header (codebook data) is never decoded field-by-field — represented as one opaque byte-count node.
- Opus's channel mapping table (`channel_mapping_family != 0`) is never decoded — only the `channel_mapping_family` field itself is shown.
- Multiplexed/chained Ogg files (multiple logical streams by `serial_number`) are not demuxed or grouped — pages are parsed in raw physical file order.
- The bulk of a file's audio-data pages are never turned into individual tree nodes — grouped into one `OggPages` summary node instead.
- Opus's granule position is always counted in 48kHz units regardless of the stream's actual/original sample rate (a real Opus-in-Ogg spec quirk, RFC 7845) — `durationSeconds = (final_granule_position - pre_skip) / 48000`, never divided by any other sample rate.
- AIFF support is out of scope for this plan (separate spec/plan later).
- `ffmpeg`/`ffprobe` must be on `PATH` for any test that shells out to generate real `.ogg`/`.opus` fixtures (matches this project's existing test conventions).

---

### Task 1: `OggWalker.kt` core parser

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/OggWalker.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/OggWalkerTest.kt`

**Interfaces:**
- Consumes: `ByteReader` (`readUInt8`, `readFourCC`, `readBytes` — all in `app/src/main/kotlin/com/multiviewer/parser/ByteReader.kt`), `BoxNode`/`BoxField` (`app/src/main/kotlin/com/multiviewer/parser/BoxNode.kt`).
- Produces: `fun parseOggPages(reader: ByteReader, start: Long, end: Long): List<BoxNode>` — the only public symbol. Task 2 calls this directly from `ParseFile.kt`.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/multiviewer/parser/OggWalkerTest.kt`:

```kotlin
package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OggWalkerTest {
    private fun uint32LE(value: Long): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte(),
    )

    private fun int32LE(value: Long): ByteArray = uint32LE(value and 0xFFFFFFFFL)

    private fun uint16LE(value: Int): ByteArray = byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())

    private fun int16LE(value: Int): ByteArray = uint16LE(value and 0xFFFF)

    private fun int64LE(value: Long): ByteArray = ByteArray(8) { i -> ((value shr (8 * i)) and 0xFF).toByte() }

    // Builds one complete Ogg page (header + segment table + payload) from a payload byte array,
    // computing the segment table automatically (matches the real encoding: segments of 255,
    // then a final segment of the remainder -- payloads under 255 bytes get one segment).
    private fun oggPage(headerType: Int, granulePosition: Long, payload: ByteArray): ByteArray {
        val segments = mutableListOf<Int>()
        var remaining = payload.size
        while (remaining >= 255) {
            segments.add(255)
            remaining -= 255
        }
        segments.add(remaining)
        val header = "OggS".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0) + // version
            byteArrayOf(headerType.toByte()) +
            int64LE(granulePosition) +
            uint32LE(1) + // serial_number (arbitrary constant, not asserted on)
            uint32LE(0) + // page_sequence_number (arbitrary constant, not asserted on)
            uint32LE(0) + // checksum (not validated by the parser)
            byteArrayOf(segments.size.toByte()) +
            segments.map { it.toByte() }.toByteArray()
        return header + payload
    }

    private fun vendorCommentPayload(vendor: String, comments: List<String>): ByteArray {
        var result = uint32LE(vendor.toByteArray(Charsets.UTF_8).size.toLong()) + vendor.toByteArray(Charsets.UTF_8)
        result += uint32LE(comments.size.toLong())
        for (c in comments) {
            val bytes = c.toByteArray(Charsets.UTF_8)
            result += uint32LE(bytes.size.toLong()) + bytes
        }
        return result
    }

    private fun vorbisIdHeaderPayload(
        channels: Int, sampleRate: Long, bitrateMax: Long, bitrateNominal: Long, bitrateMin: Long,
        blocksize0Exp: Int, blocksize1Exp: Int,
    ): ByteArray {
        return byteArrayOf(0x01) + "vorbis".toByteArray(Charsets.US_ASCII) +
            uint32LE(0) + // vorbis_version
            byteArrayOf(channels.toByte()) +
            uint32LE(sampleRate) +
            int32LE(bitrateMax) + int32LE(bitrateNominal) + int32LE(bitrateMin) +
            byteArrayOf(((blocksize1Exp shl 4) or blocksize0Exp).toByte()) +
            byteArrayOf(0x01) // framing flag
    }

    private fun opusIdHeaderPayload(channelCount: Int, preSkip: Int, inputSampleRate: Long, channelMappingFamily: Int): ByteArray {
        return "OpusHead".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(1) + // version
            byteArrayOf(channelCount.toByte()) +
            uint16LE(preSkip) +
            uint32LE(inputSampleRate) +
            int16LE(0) + // output_gain
            byteArrayOf(channelMappingFamily.toByte())
    }

    @Test
    fun `decodes a Vorbis identification header page`() {
        val payload = vorbisIdHeaderPayload(
            channels = 2, sampleRate = 44100, bitrateMax = 0, bitrateNominal = 112000, bitrateMin = 0,
            blocksize0Exp = 8, blocksize1Exp = 11,
        )
        val bytes = oggPage(headerType = 0x02, granulePosition = 0, payload = payload) // bos flag set
        val reader = byteReaderOf(bytes)

        val elements = parseOggPages(reader, 0, reader.length)

        assertEquals(1, elements.size)
        val header = elements[0]
        assertEquals("OggVorbisIdentificationHeader", header.type)
        assertEquals(0L, header.offset)
        assertEquals("2", header.fields.single { it.name == "channels" }.value)
        assertEquals("44100", header.fields.single { it.name == "sample_rate" }.value)
        assertEquals("112000", header.fields.single { it.name == "bitrate_nominal" }.value)
        assertEquals("256", header.fields.single { it.name == "blocksize_0" }.value)
        assertEquals("2048", header.fields.single { it.name == "blocksize_1" }.value)
        reader.close()
    }

    @Test
    fun `decodes a Vorbis comment page's little-endian vendor and comment fields`() {
        val payload = byteArrayOf(0x03) + "vorbis".toByteArray(Charsets.US_ASCII) +
            vendorCommentPayload("Xiph.Org libVorbis", listOf("TITLE=Test Song"))
        val bytes = oggPage(headerType = 0x00, granulePosition = 0, payload = payload)
        val reader = byteReaderOf(bytes)

        val elements = parseOggPages(reader, 0, reader.length)

        assertEquals(1, elements.size)
        val comment = elements[0]
        assertEquals("OggVorbisComment", comment.type)
        assertEquals("Xiph.Org libVorbis", comment.fields.single { it.name == "vendor" }.value)
        assertEquals("Test Song", comment.fields.single { it.name == "TITLE" }.value)
        assertEquals("1 comment(s)", comment.summary)
        reader.close()
    }

    @Test
    fun `a Vorbis setup header page falls back to a byte-count-only summary, not decoded`() {
        val payload = byteArrayOf(0x05) + "vorbis".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x11, 0x22, 0x33, 0x44)
        val bytes = oggPage(headerType = 0x00, granulePosition = 0, payload = payload)
        val reader = byteReaderOf(bytes)

        val elements = parseOggPages(reader, 0, reader.length)

        assertEquals(1, elements.size)
        assertEquals("OggVorbisSetupHeader", elements[0].type)
        assertTrue(elements[0].fields.isEmpty())
        assertEquals("11 byte(s)", elements[0].summary)
        reader.close()
    }

    @Test
    fun `decodes an Opus identification header page`() {
        val payload = opusIdHeaderPayload(channelCount = 2, preSkip = 312, inputSampleRate = 44100, channelMappingFamily = 0)
        val bytes = oggPage(headerType = 0x02, granulePosition = 0, payload = payload)
        val reader = byteReaderOf(bytes)

        val elements = parseOggPages(reader, 0, reader.length)

        assertEquals(1, elements.size)
        val header = elements[0]
        assertEquals("OggOpusIdentificationHeader", header.type)
        assertEquals("2", header.fields.single { it.name == "channel_count" }.value)
        assertEquals("312", header.fields.single { it.name == "pre_skip" }.value)
        assertEquals("44100", header.fields.single { it.name == "input_sample_rate" }.value)
        assertEquals("0", header.fields.single { it.name == "channel_mapping_family" }.value)
        reader.close()
    }

    @Test
    fun `decodes an Opus tags page`() {
        val payload = "OpusTags".toByteArray(Charsets.US_ASCII) +
            vendorCommentPayload("Lavf62.12.102", listOf("ENCODER=Lavf62.12.102"))
        val bytes = oggPage(headerType = 0x00, granulePosition = 0, payload = payload)
        val reader = byteReaderOf(bytes)

        val elements = parseOggPages(reader, 0, reader.length)

        assertEquals(1, elements.size)
        val tags = elements[0]
        assertEquals("OggOpusTags", tags.type)
        assertEquals("Lavf62.12.102", tags.fields.single { it.name == "vendor" }.value)
        assertEquals("Lavf62.12.102", tags.fields.single { it.name == "ENCODER" }.value)
        reader.close()
    }

    @Test
    fun `a run of generic pages accumulates into one OggPages summary and captures the eos page's granule position`() {
        val page1 = oggPage(headerType = 0x00, granulePosition = 999, payload = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte()))
        val page2 = oggPage(headerType = 0x00, granulePosition = 1999, payload = byteArrayOf(0x11, 0x22, 0x33, 0x44))
        val page3 = oggPage(headerType = 0x04, granulePosition = 12345, payload = byteArrayOf(0x55, 0x66, 0x77, 0x88.toByte())) // eos flag set
        val bytes = page1 + page2 + page3
        val reader = byteReaderOf(bytes)

        val elements = parseOggPages(reader, 0, reader.length)

        assertEquals(1, elements.size)
        val pages = elements[0]
        assertEquals("OggPages", pages.type)
        assertEquals("3 page(s), 96 byte(s)", pages.summary)
        assertEquals("12345", pages.fields.single { it.name == "final_granule_position" }.value)
        reader.close()
    }

    @Test
    fun `too few bytes for a page header produces a warning and stops`() {
        val bytes = "OggS".toByteArray(Charsets.US_ASCII) + ByteArray(10) // 14 bytes total, need 27 for a full header
        val reader = byteReaderOf(bytes)

        val elements = parseOggPages(reader, 0, reader.length)

        assertEquals(1, elements.size)
        assertEquals("?", elements[0].type)
        assertTrue(elements[0].warnings.single().contains("too short"))
        reader.close()
    }

    @Test
    fun `a capture pattern mismatch stops the scan and flushes any pending accumulation first`() {
        val page1 = oggPage(headerType = 0x00, granulePosition = 0, payload = byteArrayOf(0x01, 0x02))
        val garbage = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        val bytes = page1 + garbage
        val reader = byteReaderOf(bytes)

        val elements = parseOggPages(reader, 0, reader.length)

        assertEquals(2, elements.size)
        assertEquals("OggPages", elements[0].type)
        assertEquals("1 page(s), 30 byte(s)", elements[0].summary)
        assertEquals("?", elements[1].type)
        assertTrue(elements[1].warnings.single().contains("OggS"))
        reader.close()
    }

    @Test
    fun `a declared payload size extending past the parent range warns and clamps, and the warning is not lost`() {
        // A valid 28-byte header (segment_count=1, segment_table=[10], declaring a 10-byte
        // payload) followed by only 3 actual payload bytes -- this page doesn't match any known
        // signature, so it becomes part of the OggPages accumulator; its truncation warning must
        // still surface on that summary node, not be silently dropped.
        val header = "OggS".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0) + byteArrayOf(0x00) + int64LE(0) + uint32LE(1) + uint32LE(0) + uint32LE(0) +
            byteArrayOf(1) + byteArrayOf(10)
        val bytes = header + byteArrayOf(0x01, 0x02, 0x03)
        val reader = byteReaderOf(bytes)

        val elements = parseOggPages(reader, 0, reader.length)

        assertEquals(1, elements.size)
        assertEquals("OggPages", elements[0].type)
        assertTrue(elements[0].warnings.single().contains("extends"))
        reader.close()
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests "com.multiviewer.parser.OggWalkerTest"`
Expected: FAIL — `parseOggPages` is unresolved (file doesn't exist yet).

- [ ] **Step 3: Write the implementation**

Create `app/src/main/kotlin/com/multiviewer/parser/OggWalker.kt`:

```kotlin
package com.multiviewer.parser

private const val OGG_HEADER_FIXED_SIZE = 27

fun parseOggPages(reader: ByteReader, start: Long, end: Long): List<BoxNode> {
    val result = mutableListOf<BoxNode>()
    var pos = start
    var pendingPageCount = 0
    var pendingByteCount = 0L
    var pendingStart = start
    val pendingWarnings = mutableListOf<String>()
    var finalGranulePosition: Long? = null
    var finalGranulePositionOffset = start

    fun flushPending() {
        if (pendingPageCount > 0) {
            val fields = mutableListOf<BoxField>()
            finalGranulePosition?.let { fields.add(BoxField("final_granule_position", it.toString(), finalGranulePositionOffset, 8)) }
            result.add(
                BoxNode(
                    "OggPages", pendingStart, 0, pendingByteCount, fields = fields,
                    summary = "$pendingPageCount page(s), $pendingByteCount byte(s)",
                    warnings = pendingWarnings.toList(),
                ),
            )
            pendingPageCount = 0
            pendingByteCount = 0L
            pendingWarnings.clear()
        }
    }

    while (pos < end) {
        val remaining = end - pos
        if (remaining < OGG_HEADER_FIXED_SIZE || reader.readFourCC(pos) != "OggS") {
            flushPending()
            result.add(BoxNode("?", pos, 0, remaining, warnings = listOf("Expected 'OggS' capture pattern at this offset -- stopping page scan")))
            break
        }

        val headerType = reader.readUInt8(pos + 5)
        val granulePosition = readInt64LE(reader, pos + 6)
        val segmentCount = reader.readUInt8(pos + 26)

        if (remaining < OGG_HEADER_FIXED_SIZE + segmentCount) {
            flushPending()
            result.add(BoxNode("?", pos, 0, remaining, warnings = listOf("Trailing $remaining byte(s): too short for a page's segment table")))
            break
        }

        var declaredPayloadLength = 0
        for (i in 0 until segmentCount) {
            declaredPayloadLength += reader.readUInt8(pos + OGG_HEADER_FIXED_SIZE + i)
        }
        val headerSize = OGG_HEADER_FIXED_SIZE + segmentCount
        var pageSize = (headerSize + declaredPayloadLength).toLong()
        val warnings = mutableListOf<String>()
        if (pos + pageSize > end) {
            warnings.add("Declared size $pageSize extends ${pos + pageSize - end} byte(s) past the end of its parent")
            pageSize = end - pos
        }
        val payloadStart = pos + headerSize
        val payloadLength = (pos + pageSize - payloadStart).toInt()

        if ((headerType and 0x04) != 0) {
            finalGranulePosition = granulePosition
            finalGranulePositionOffset = pos + 6
        }

        val node = decodeOggPage(reader, pos, headerSize, pageSize, payloadStart, payloadLength, warnings)
        if (node != null) {
            flushPending()
            result.add(node)
        } else {
            if (pendingPageCount == 0) pendingStart = pos
            pendingPageCount++
            pendingByteCount += pageSize
            pendingWarnings.addAll(warnings)
        }

        pos += pageSize
    }

    flushPending()
    return result
}

private fun decodeOggPage(
    reader: ByteReader,
    offset: Long,
    headerSize: Int,
    size: Long,
    payloadStart: Long,
    payloadLength: Int,
    warnings: List<String>,
): BoxNode? {
    if (payloadLength <= 0) return null
    val first = reader.readUInt8(payloadStart)
    return when {
        payloadLength >= 7 && first == 0x01 && readAscii(reader, payloadStart + 1, 6) == "vorbis" ->
            decodeVorbisIdentificationHeader(reader, offset, headerSize, size, payloadStart, payloadLength, warnings)
        payloadLength >= 7 && first == 0x03 && readAscii(reader, payloadStart + 1, 6) == "vorbis" ->
            decodeOggVendorComment(reader, "OggVorbisComment", offset, headerSize, size, payloadStart + 7, payloadLength - 7, warnings)
        payloadLength >= 7 && first == 0x05 && readAscii(reader, payloadStart + 1, 6) == "vorbis" ->
            BoxNode("OggVorbisSetupHeader", offset, headerSize, size, summary = "$payloadLength byte(s)", warnings = warnings)
        payloadLength >= 8 && readAscii(reader, payloadStart, 8) == "OpusHead" ->
            decodeOpusIdentificationHeader(reader, offset, headerSize, size, payloadStart, payloadLength, warnings)
        payloadLength >= 8 && readAscii(reader, payloadStart, 8) == "OpusTags" ->
            decodeOggVendorComment(reader, "OggOpusTags", offset, headerSize, size, payloadStart + 8, payloadLength - 8, warnings)
        else -> null
    }
}

private fun decodeVorbisIdentificationHeader(
    reader: ByteReader, offset: Long, headerSize: Int, size: Long,
    payloadStart: Long, payloadLength: Int, warnings: List<String>,
): BoxNode {
    if (payloadLength < 30) {
        return BoxNode("OggVorbisIdentificationHeader", offset, headerSize, size, warnings = warnings + "Vorbis identification header is $payloadLength byte(s), expected 30")
    }
    val version = readUInt32LE(reader, payloadStart + 7)
    val channels = reader.readUInt8(payloadStart + 11)
    val sampleRate = readUInt32LE(reader, payloadStart + 12)
    val bitrateMaximum = readInt32LE(reader, payloadStart + 16)
    val bitrateNominal = readInt32LE(reader, payloadStart + 20)
    val bitrateMinimum = readInt32LE(reader, payloadStart + 24)
    val blocksizeByte = reader.readUInt8(payloadStart + 28)
    val blocksize0 = 1L shl (blocksizeByte and 0x0F)
    val blocksize1 = 1L shl ((blocksizeByte shr 4) and 0x0F)

    val fields = listOf(
        BoxField("version", version.toString(), payloadStart + 7, 4),
        BoxField("channels", channels.toString(), payloadStart + 11, 1),
        BoxField("sample_rate", sampleRate.toString(), payloadStart + 12, 4),
        BoxField("bitrate_maximum", bitrateMaximum.toString(), payloadStart + 16, 4),
        BoxField("bitrate_nominal", bitrateNominal.toString(), payloadStart + 20, 4),
        BoxField("bitrate_minimum", bitrateMinimum.toString(), payloadStart + 24, 4),
        BoxField("blocksize_0", blocksize0.toString(), payloadStart + 28, 1),
        BoxField("blocksize_1", blocksize1.toString(), payloadStart + 28, 1),
    )
    return BoxNode(
        "OggVorbisIdentificationHeader", offset, headerSize, size, fields = fields,
        summary = "${sampleRate}Hz, ${channels}ch",
        warnings = warnings,
    )
}

private fun decodeOpusIdentificationHeader(
    reader: ByteReader, offset: Long, headerSize: Int, size: Long,
    payloadStart: Long, payloadLength: Int, warnings: List<String>,
): BoxNode {
    if (payloadLength < 19) {
        return BoxNode("OggOpusIdentificationHeader", offset, headerSize, size, warnings = warnings + "Opus identification header is $payloadLength byte(s), expected 19")
    }
    val version = reader.readUInt8(payloadStart + 8)
    val channelCount = reader.readUInt8(payloadStart + 9)
    val preSkip = readUInt16LE(reader, payloadStart + 10)
    val inputSampleRate = readUInt32LE(reader, payloadStart + 12)
    val outputGain = readInt16LE(reader, payloadStart + 16)
    val channelMappingFamily = reader.readUInt8(payloadStart + 18)

    val fields = listOf(
        BoxField("version", version.toString(), payloadStart + 8, 1),
        BoxField("channel_count", channelCount.toString(), payloadStart + 9, 1),
        BoxField("pre_skip", preSkip.toString(), payloadStart + 10, 2),
        BoxField("input_sample_rate", inputSampleRate.toString(), payloadStart + 12, 4),
        BoxField("output_gain", outputGain.toString(), payloadStart + 16, 2),
        BoxField("channel_mapping_family", channelMappingFamily.toString(), payloadStart + 18, 1),
    )
    return BoxNode(
        "OggOpusIdentificationHeader", offset, headerSize, size, fields = fields,
        summary = "48000Hz (fixed), ${channelCount}ch",
        warnings = warnings,
    )
}

private fun decodeOggVendorComment(
    reader: ByteReader, name: String, offset: Long, headerSize: Int, size: Long,
    payloadStart: Long, payloadLength: Int, warnings: List<String>,
): BoxNode {
    val allWarnings = warnings.toMutableList()
    val payloadEnd = payloadStart + payloadLength
    val fields = mutableListOf<BoxField>()
    var pos = payloadStart

    if (pos + 4 > payloadEnd) {
        allWarnings.add("$name block is $payloadLength byte(s), too short for its vendor length field")
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

private fun readAscii(reader: ByteReader, offset: Long, length: Int): String =
    String(reader.readBytes(offset, length), Charsets.US_ASCII)

private fun readUInt16LE(reader: ByteReader, offset: Long): Int {
    val b = reader.readBytes(offset, 2)
    return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
}

private fun readInt16LE(reader: ByteReader, offset: Long): Int = readUInt16LE(reader, offset).toShort().toInt()

private fun readUInt32LE(reader: ByteReader, offset: Long): Long {
    val b = reader.readBytes(offset, 4)
    return (b[0].toLong() and 0xFF) or ((b[1].toLong() and 0xFF) shl 8) or
        ((b[2].toLong() and 0xFF) shl 16) or ((b[3].toLong() and 0xFF) shl 24)
}

private fun readInt32LE(reader: ByteReader, offset: Long): Long = readUInt32LE(reader, offset).toInt().toLong()

private fun readInt64LE(reader: ByteReader, offset: Long): Long {
    val b = reader.readBytes(offset, 8)
    var result = 0L
    for (i in 0 until 8) {
        result = result or ((b[i].toLong() and 0xFF) shl (8 * i))
    }
    return result
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests "com.multiviewer.parser.OggWalkerTest"`
Expected: PASS, 9/9 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/OggWalker.kt app/src/test/kotlin/com/multiviewer/parser/OggWalkerTest.kt
git commit -m "feat: add OggWalker for OGG/Opus container structural parsing"
```

---

### Task 2: Wire OGG/Opus into `ParseFile.kt` and `AppState.kt`

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/ParseFile.kt` (the magic-byte dispatch chain, currently ending with `isFlac`/`isFlacMagic` after the FLAC-support plan)
- Modify: `app/src/main/kotlin/com/multiviewer/ui/AppState.kt` (`AUDIO_EXTENSIONS`, currently `listOf("m4a", "mp3", "wav", "flac")`, and its preceding comment block)
- Test: `app/src/test/kotlin/com/multiviewer/parser/ParseFileIntegrationTest.kt`

**Interfaces:**
- Consumes: `parseOggPages(reader: ByteReader, start: Long, end: Long): List<BoxNode>` from Task 1.
- Produces: `.ogg` and `.opus` files are recognized as `MediaType.AUDIO` by `AppState.openFile` and routed to `parseOggPages` by `parseFile`. Task 3 relies on this routing being in place.

- [ ] **Step 1: Write the failing test**

In `app/src/test/kotlin/com/multiviewer/parser/ParseFileIntegrationTest.kt`, add this test (place it after the most recent format-routing test in the file, matching that test's style):

```kotlin
    @Test
    fun `parses a synthetic minimal ogg file via the OGG path, not the ISOBMFF path`() {
        val payload = byteArrayOf(0x01) + "vorbis".toByteArray(Charsets.US_ASCII) +
            uint32LE(0) + byteArrayOf(2) + uint32LE(44100) +
            uint32LE(0) + uint32LE(112000) + uint32LE(0) +
            byteArrayOf(0xB8.toByte()) + byteArrayOf(0x01)
        val segmentTable = byteArrayOf(payload.size.toByte())
        val bytes = "OggS".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0) + byteArrayOf(0x02) + // version, header_type (bos)
            ByteArray(8) + // granule_position = 0
            uint32LE(1) + uint32LE(0) + uint32LE(0) + // serial_number, page_sequence_number, checksum
            byteArrayOf(1) + segmentTable +
            payload
        val tmp = File.createTempFile("multiviewer-ogg", ".ogg")
        tmp.deleteOnExit()
        tmp.writeBytes(bytes)

        val root = parseFile(tmp)

        assertEquals(listOf("OggVorbisIdentificationHeader"), root.children.map { it.type })
        assertEquals("44100", root.children[0].fields.single { it.name == "sample_rate" }.value)
    }
```

This test needs a `uint32LE` helper. Check whether `ParseFileIntegrationTest.kt` already has one (it has `uint32LE` for big-endian named `uint32` and little-endian helpers `uint32LE`/`uint16LE`/`int32LE` for the BMP/GIF tests near the bottom of the file) — reuse the existing private `uint32LE` function already in that file rather than adding a duplicate. If the existing one only exists as `uint32LE(value: Long): ByteArray` (little-endian), use it as-is; do not add a second definition.

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests "com.multiviewer.parser.ParseFileIntegrationTest"`
Expected: FAIL — the new test fails because `parseFile` doesn't recognize `.ogg` yet (falls through to `parseBoxes`).

- [ ] **Step 3: Wire the routing**

In `app/src/main/kotlin/com/multiviewer/parser/ParseFile.kt`, find the line (added by the prior FLAC-support plan):

```kotlin
            val isFlac = !isJpeg && !isPng && !isBmp && !isGif && !isTiff && !isWebp && !isWav && !isMp3 && !isEbml && isFlacMagic(reader)
            val children = when {
```

Change it to:

```kotlin
            val isFlac = !isJpeg && !isPng && !isBmp && !isGif && !isTiff && !isWebp && !isWav && !isMp3 && !isEbml && isFlacMagic(reader)
            val isOgg = !isJpeg && !isPng && !isBmp && !isGif && !isTiff && !isWebp && !isWav && !isMp3 && !isEbml && !isFlac && isOggMagic(reader)
            val children = when {
```

Then find the `when` block's `isFlac -> parseFlacBlocks(reader, 0, reader.length)` line and add a new branch immediately after it (before `else`):

```kotlin
                isFlac -> parseFlacBlocks(reader, 0, reader.length)
                isOgg -> parseOggPages(reader, 0, reader.length)
                else -> parseBoxes(reader, 0, reader.length)
```

Then add a new private function right after `isFlacMagic` (which currently ends the file's private-helper section):

```kotlin
private fun isOggMagic(reader: ByteReader): Boolean {
    if (reader.length < 4) return false
    return reader.readFourCC(0) == "OggS"
}
```

- [ ] **Step 4: Add the extensions and update the comment**

In `app/src/main/kotlin/com/multiviewer/ui/AppState.kt`, find:

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

Change it to:

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

- [ ] **Step 5: Run tests to verify they pass**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests "com.multiviewer.parser.ParseFileIntegrationTest"`
Expected: PASS, including the new OGG test.

Also run the full suite once to confirm nothing else regressed:

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test`
Expected: PASS, no new failures.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/ParseFile.kt app/src/main/kotlin/com/multiviewer/ui/AppState.kt app/src/test/kotlin/com/multiviewer/parser/ParseFileIntegrationTest.kt
git commit -m "feat: route .ogg/.opus files to OggWalker and recognize both extensions"
```

---

### Task 3: `MediaSummaryBuilder.kt` category detection and OGG/Opus summary

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt` (`detectCategory`, `buildStandaloneAudioSummary`)
- Test: Create `app/src/test/kotlin/com/multiviewer/parser/OggMediaSummaryBuilderTest.kt`
- Test: Modify `app/src/test/kotlin/com/multiviewer/ui/AppStateTest.kt` (add two real-fixture tests — one `.ogg`/Vorbis, one `.opus`/Opus — after the existing FLAC test added by the prior plan)

**Interfaces:**
- Consumes: `BoxNode`/`BoxField`, `SummarySection`/`SummaryField`, `formatDuration`/`formatBitrate`/`formatFileSize` (all already in `MediaSummaryBuilder.kt`), the `"OggVorbisIdentificationHeader"`/`"OggOpusIdentificationHeader"`/`"OggPages"` node types and their field names (`sample_rate`, `channels`, `bitrate_nominal`, `channel_count`, `pre_skip`, `final_granule_position`) produced by Task 1's `OggWalker.kt`.
- Produces: `buildMediaSummary(root, file)` now returns `MediaCategory.AUDIO` with populated `General`/`Audio` sections for any OGG or Opus file.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/multiviewer/parser/OggMediaSummaryBuilderTest.kt`:

```kotlin
package com.multiviewer.parser

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class OggMediaSummaryBuilderTest {
    private fun buildVorbisFixture(): BoxNode {
        val header = BoxNode(
            "OggVorbisIdentificationHeader", 0, 0, 0,
            fields = listOf(
                BoxField("channels", "2", 0, 0),
                BoxField("sample_rate", "44100", 0, 0),
                BoxField("bitrate_nominal", "112000", 0, 0),
            ),
        )
        val pages = BoxNode(
            "OggPages", 0, 0, 0,
            fields = listOf(BoxField("final_granule_position", "88200", 0, 0)),
        )
        return BoxNode("root", 0, 0, 0, children = listOf(header, pages))
    }

    private fun buildOpusFixture(): BoxNode {
        val header = BoxNode(
            "OggOpusIdentificationHeader", 0, 0, 0,
            fields = listOf(
                BoxField("channel_count", "2", 0, 0),
                BoxField("pre_skip", "312", 0, 0),
                BoxField("input_sample_rate", "44100", 0, 0),
            ),
        )
        val pages = BoxNode(
            "OggPages", 0, 0, 0,
            fields = listOf(BoxField("final_granule_position", "96312", 0, 0)),
        )
        return BoxNode("root", 0, 0, 0, children = listOf(header, pages))
    }

    @Test
    fun `an Ogg root is classified as AUDIO`() {
        val root = buildVorbisFixture()
        val tmp = File.createTempFile("ogg-summary-category-test", ".ogg")
        tmp.deleteOnExit()
        tmp.writeBytes(ByteArray(10))

        val summary = buildMediaSummary(root, tmp)

        assertEquals(MediaCategory.AUDIO, summary.category)
    }

    @Test
    fun `a Vorbis tree produces General and Audio sections with correct values`() {
        val root = buildVorbisFixture()
        val tmp = File.createTempFile("ogg-vorbis-summary-test", ".ogg")
        tmp.deleteOnExit()
        tmp.writeBytes(ByteArray(200_000))

        val summary = buildMediaSummary(root, tmp)

        val general = summary.sections.first { it.title == "General" }
        assertEquals("Vorbis", general.fields.first { it.label == "Format" }.value)
        assertEquals("0:00:02.000", general.fields.first { it.label == "Duration" }.value)

        val audio = summary.sections.first { it.title == "Audio" }
        assertEquals("44100 Hz", audio.fields.first { it.label == "Sampling Rate" }.value)
        assertEquals("2", audio.fields.first { it.label == "Channel(s)" }.value)
        assertEquals("112.0 Kbps", audio.fields.first { it.label == "Bit Rate" }.value)
    }

    @Test
    fun `an Opus tree produces General and Audio sections using the fixed 48kHz granule rate`() {
        val root = buildOpusFixture()
        val tmp = File.createTempFile("ogg-opus-summary-test", ".opus")
        tmp.deleteOnExit()
        tmp.writeBytes(ByteArray(200_000))

        val summary = buildMediaSummary(root, tmp)

        val general = summary.sections.first { it.title == "General" }
        assertEquals("Opus", general.fields.first { it.label == "Format" }.value)
        // (96312 - 312) / 48000 = 2.0 seconds -- NOT divided by input_sample_rate (44100), which
        // would give a wrong answer. This is the case most likely to regress silently.
        assertEquals("0:00:02.000", general.fields.first { it.label == "Duration" }.value)

        val audio = summary.sections.first { it.title == "Audio" }
        assertEquals("48000 Hz", audio.fields.first { it.label == "Sampling Rate" }.value)
        assertEquals("2", audio.fields.first { it.label == "Channel(s)" }.value)
    }
}
```

In `app/src/test/kotlin/com/multiviewer/ui/AppStateTest.kt`, add these two tests right after the FLAC test added by the prior plan (`` `openFile opens a real FLAC as MediaType_AUDIO with a populated Audio section` ``):

```kotlin
    @Test
    fun `openFile opens a real OGG (Vorbis) as MediaType_AUDIO with a populated Audio section`() {
        val audio = File.createTempFile("appstate-ogg-test-", ".ogg")
        audio.deleteOnExit()
        // This machine's ffmpeg build has no libvorbis -- only the native "vorbis" encoder, which
        // is marked experimental (needs -strict -2) and only supports 2-channel output (needs
        // -ac 2 even though the source is mono).
        ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "sine=duration=1:frequency=440",
            "-ac", "2", "-c:a", "vorbis", "-strict", "-2", audio.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()

        val appState = AppState()
        appState.openFile(audio)
        val tab = appState.tabs.single()
        waitForLoad(tab)

        assertEquals(null, tab.error)
        assertEquals(MediaType.AUDIO, tab.type)
        val generalSection = tab.mediaSummary?.sections?.find { it.title == "General" }
        assertTrue(generalSection?.fields?.any { it.label == "Format" && it.value == "Vorbis" } == true, "Expected General/Format=Vorbis, got: $generalSection")
        val audioSection = tab.mediaSummary?.sections?.find { it.title == "Audio" }
        assertTrue(audioSection?.fields?.any { it.label == "Sampling Rate" } == true, "Expected an Audio/Sampling Rate field, got: $audioSection")
        audio.delete()
    }

    @Test
    fun `openFile opens a real Opus as MediaType_AUDIO with a populated Audio section`() {
        val audio = File.createTempFile("appstate-opus-test-", ".opus")
        audio.deleteOnExit()
        ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "sine=duration=1:frequency=440",
            "-c:a", "libopus", audio.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()

        val appState = AppState()
        appState.openFile(audio)
        val tab = appState.tabs.single()
        waitForLoad(tab)

        assertEquals(null, tab.error)
        assertEquals(MediaType.AUDIO, tab.type)
        val generalSection = tab.mediaSummary?.sections?.find { it.title == "General" }
        assertTrue(generalSection?.fields?.any { it.label == "Format" && it.value == "Opus" } == true, "Expected General/Format=Opus, got: $generalSection")
        val audioSection = tab.mediaSummary?.sections?.find { it.title == "Audio" }
        assertTrue(audioSection?.fields?.any { it.label == "Sampling Rate" && it.value == "48000 Hz" } == true, "Expected Audio/Sampling Rate=48000 Hz, got: $audioSection")
        audio.delete()
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests "com.multiviewer.parser.OggMediaSummaryBuilderTest" --tests "com.multiviewer.ui.AppStateTest"`
Expected: FAIL — `OggMediaSummaryBuilderTest`'s category test gets `MediaCategory.IMAGE` (wrong fallback), and both new `AppStateTest` tests find no populated `Audio` section (`detectCategory` doesn't yet recognize `"Ogg"`-prefixed types).

- [ ] **Step 3: Implement the category detection and summary**

In `app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt`, find:

```kotlin
    if (root.children.any { it.type == "fLaC" }) return MediaCategory.AUDIO

    val moov = root.children.find { it.type == "moov" } ?: return MediaCategory.IMAGE
```

Change it to:

```kotlin
    if (root.children.any { it.type == "fLaC" }) return MediaCategory.AUDIO
    if (root.children.any { it.type.startsWith("Ogg") }) return MediaCategory.AUDIO

    val moov = root.children.find { it.type == "moov" } ?: return MediaCategory.IMAGE
```

Then find:

```kotlin
private fun buildStandaloneAudioSummary(root: BoxNode, fileSizeBytes: Long): List<SummarySection> {
    val fmt = root.children.find { it.type == "fmt " }
    return when {
        fmt != null -> buildWavSummary(root, fmt, fileSizeBytes)
        root.children.any { it.type == "fLaC" } -> buildFlacSummary(root, fileSizeBytes)
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
        else -> buildMp3Summary(root, fileSizeBytes)
    }
}

private fun buildOggSummary(root: BoxNode, fileSizeBytes: Long): List<SummarySection> {
    val vorbisHeader = root.children.find { it.type == "OggVorbisIdentificationHeader" }
    val opusHeader = root.children.find { it.type == "OggOpusIdentificationHeader" }
    val oggPages = root.children.find { it.type == "OggPages" }
    val finalGranulePosition = oggPages?.fields?.find { it.name == "final_granule_position" }?.value?.toDoubleOrNull()

    val generalFields = mutableListOf(
        SummaryField("Format", if (opusHeader != null) "Opus" else "Vorbis"),
        SummaryField("File Size", formatFileSize(fileSizeBytes)),
    )
    val audioFields = mutableListOf<SummaryField>()

    if (opusHeader != null) {
        val preSkip = opusHeader.fields.find { it.name == "pre_skip" }?.value?.toDoubleOrNull() ?: 0.0
        val channelCount = opusHeader.fields.find { it.name == "channel_count" }?.value
        audioFields.add(SummaryField("Sampling Rate", "48000 Hz"))
        channelCount?.let { audioFields.add(SummaryField("Channel(s)", it)) }
        // Opus's granule position is always counted in 48kHz units, regardless of the stream's
        // actual/original sample rate (RFC 7845) -- never divide by input_sample_rate here.
        if (finalGranulePosition != null && finalGranulePosition > preSkip) {
            val durationSeconds = (finalGranulePosition - preSkip) / 48000.0
            generalFields.add(SummaryField("Duration", formatDuration(durationSeconds)))
            if (durationSeconds > 0) {
                val bitrate = (fileSizeBytes * 8) / durationSeconds
                generalFields.add(SummaryField("Overall Bit Rate", formatBitrate(bitrate)))
            }
        }
    } else if (vorbisHeader != null) {
        val sampleRateField = vorbisHeader.fields.find { it.name == "sample_rate" }?.value
        val sampleRate = sampleRateField?.toDoubleOrNull()
        val channels = vorbisHeader.fields.find { it.name == "channels" }?.value
        val bitrateNominal = vorbisHeader.fields.find { it.name == "bitrate_nominal" }?.value?.toDoubleOrNull()
        sampleRateField?.let { audioFields.add(SummaryField("Sampling Rate", "$it Hz")) }
        channels?.let { audioFields.add(SummaryField("Channel(s)", it)) }
        if (bitrateNominal != null && bitrateNominal > 0) {
            audioFields.add(SummaryField("Bit Rate", formatBitrate(bitrateNominal)))
        }
        if (sampleRate != null && sampleRate > 0 && finalGranulePosition != null) {
            val durationSeconds = finalGranulePosition / sampleRate
            generalFields.add(SummaryField("Duration", formatDuration(durationSeconds)))
            if (durationSeconds > 0) {
                val bitrate = (fileSizeBytes * 8) / durationSeconds
                generalFields.add(SummaryField("Overall Bit Rate", formatBitrate(bitrate)))
            }
        }
    }

    return listOf(SummarySection("General", generalFields), SummarySection("Audio", audioFields))
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests "com.multiviewer.parser.OggMediaSummaryBuilderTest" --tests "com.multiviewer.ui.AppStateTest"`
Expected: PASS.

Then run the full suite:

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test`
Expected: PASS, no new failures.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt app/src/test/kotlin/com/multiviewer/parser/OggMediaSummaryBuilderTest.kt app/src/test/kotlin/com/multiviewer/ui/AppStateTest.kt
git commit -m "feat: classify OGG/Opus as AUDIO and build a Vorbis/Opus-specific media summary"
```

---

### Task 4: Manual end-to-end verification (controller-performed)

No automated coverage is possible for this task (Compose UI + audio hardware playback). This step is performed by the controller directly, not dispatched to a subagent.

- [ ] Generate real fixtures: `ffmpeg -y -f lavfi -i "sine=duration=5:frequency=440" -ac 2 -c:a vorbis -strict -2 /tmp/test-verify.ogg` (this machine has no `libvorbis`, only the native experimental `vorbis` encoder, which needs `-strict -2` and 2-channel output) and `ffmpeg -y -f lavfi -i "sine=duration=5:frequency=440" -c:a libopus /tmp/test-verify.opus`
- [ ] Launch the app (`export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew run`) and open both files
- [ ] Confirm the tree view shows the identification header (Vorbis or Opus), the comment/tags page, and a single `OggPages` summary node (not hundreds/thousands of individual page nodes) with sensible decoded fields
- [ ] Confirm the Detail Properties panel shows General (Format, Duration, File Size, Overall Bit Rate) and Audio (Sampling Rate, Channel(s), Bit Rate for Vorbis) sections with correct values for both files
- [ ] Confirm playback works for both: play/pause, waveform/spectrogram render, playhead moves, click/drag-to-seek on the waveform works
- [ ] If any issue is found, treat it as a real bug — return to systematic-debugging, not a quick patch

---

## Self-Review Notes

- **Spec coverage:** Vorbis identification header ✅ (Task 1), Vorbis comment ✅ (Task 1), Vorbis setup header labeled-not-decoded ✅ (Task 1), Opus identification header ✅ (Task 1), Opus tags ✅ (Task 1), generic pages grouped into one `OggPages` summary ✅ (Task 1), `final_granule_position` capture ✅ (Task 1), `Ogg`-prefix category detection ✅ (Task 3), wiring into `ParseFile.kt`/`AppState.kt` (both extensions) ✅ (Task 2), `buildOggSummary` with the Vorbis-vs-Opus duration formula distinction ✅ (Task 3), manual verification ✅ (Task 4).
- **Placeholder scan:** none found.
- **Type consistency:** `parseOggPages(reader: ByteReader, start: Long, end: Long): List<BoxNode>` used identically in Task 1 (definition) and Task 2 (`ParseFile.kt` call site). Field names (`sample_rate`, `channels`, `bitrate_nominal`, `channel_count`, `pre_skip`, `final_granule_position`) are consistent between Task 1's `OggWalker.kt` and Task 3's `buildOggSummary`/`OggMediaSummaryBuilderTest.kt`. Node type names (`OggVorbisIdentificationHeader`, `OggOpusIdentificationHeader`, `OggPages`) match exactly between Task 1's emission and Task 3's lookups.
- **Correctness note carried into Task 3's dispatch:** `buildOggSummary` checks `opusHeader != null` before `vorbisHeader != null` -- a file can only have one or the other in practice, but checking Opus first means a malformed/hybrid fixture would report "Opus" rather than silently mixing fields from both branches.
