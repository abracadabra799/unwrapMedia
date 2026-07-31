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

        assertEquals(4, elements.size)
        assertEquals("PADDING", elements[1].type)
        assertEquals("2 byte(s)", elements[1].summary)
        assertEquals("PADDING", elements[2].type)
        assertEquals("3 byte(s)", elements[2].summary)
        assertEquals("FrameData", elements[3].type)
        assertEquals(4L, elements[3].size)
        assertEquals("4 byte(s)", elements[3].summary)
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
