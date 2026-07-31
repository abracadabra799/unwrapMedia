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
