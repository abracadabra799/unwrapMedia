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
