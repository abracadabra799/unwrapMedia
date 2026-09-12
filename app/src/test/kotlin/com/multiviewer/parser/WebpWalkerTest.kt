package com.multiviewer.parser

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

private fun byteReaderOf(bytes: ByteArray, namePrefix: String): ByteReader {
    val tmp = File.createTempFile(namePrefix, ".bin")
    tmp.deleteOnExit()
    tmp.writeBytes(bytes)
    return ByteReader.open(tmp)
}

class WebpWalkerTest {
    @Test
    fun `RIFF file_size and a VP8 chunk's size and dimensions parse as little-endian`() {
        // Bytes captured verbatim from a real cwebp-generated file's header:
        // "RIFF" + file_size(LE)=22 + "WEBP" + "VP8 " + chunk_size(LE)=10 +
        // [frame_tag(3) + sync_code(3) + width(LE 16-bit)=100 + height(LE 16-bit)=50]
        val bytes = byteArrayOf(
            0x52, 0x49, 0x46, 0x46, // "RIFF"
            0x16, 0x00, 0x00, 0x00, // file_size = 22 (LE)
            0x57, 0x45, 0x42, 0x50, // "WEBP"
            0x56, 0x50, 0x38, 0x20, // "VP8 "
            0x0a, 0x00, 0x00, 0x00, // chunk_size = 10 (LE)
            0xd0.toByte(), 0x4f, 0x00, // frame tag (from a real VP8 keyframe)
            0x9d.toByte(), 0x01, 0x2a, // VP8 sync code (from a real VP8 keyframe)
            0x64, 0x00, // width = 100 (LE 16-bit)
            0x32, 0x00, // height = 50 (LE 16-bit)
        )
        byteReaderOf(bytes, "webp-walker-riff-vp8").use { reader ->
            val nodes = parseWebpChunks(reader, 0, bytes.size.toLong())
            assertEquals(2, nodes.size)
            val riff = nodes[0]
            assertEquals("30", riff.fields.first { it.name == "file_size" }.value)
            val vp8 = nodes[1]
            assertEquals("VP8 ", vp8.type)
            assertEquals("100", vp8.fields.first { it.name == "width" }.value)
            assertEquals("50", vp8.fields.first { it.name == "height" }.value)
            assertEquals("Lossy, 100x50", vp8.summary)
        }
    }

    @Test
    fun `VP8X width and height (already little-endian via readUInt24) are unaffected by this fix`() {
        val bytes = byteArrayOf(
            0x52, 0x49, 0x46, 0x46, // "RIFF"
            0x00, 0x00, 0x00, 0x00, // file_size (not asserted in this test)
            0x57, 0x45, 0x42, 0x50, // "WEBP"
            0x56, 0x50, 0x38, 0x58, // "VP8X"
            0x0a, 0x00, 0x00, 0x00, // chunk_size = 10 (LE)
            0x00, // flags
            0x00, 0x00, 0x00, // reserved
            0x1f, 0x03, 0x00, // width_minus_one = 799 (LE 24-bit) -> width = 800
            0x57, 0x02, 0x00, // height_minus_one = 599 (LE 24-bit) -> height = 600
        )
        byteReaderOf(bytes, "webp-walker-vp8x").use { reader ->
            val nodes = parseWebpChunks(reader, 0, bytes.size.toLong())
            val vp8x = nodes[1]
            assertEquals("VP8X", vp8x.type)
            assertEquals("800", vp8x.fields.first { it.name == "width" }.value)
            assertEquals("600", vp8x.fields.first { it.name == "height" }.value)
        }
    }

    @Test
    fun `VP8L width and height (already hand-composed little-endian) are unaffected by this fix`() {
        val bytes = byteArrayOf(
            0x52, 0x49, 0x46, 0x46, // "RIFF"
            0x00, 0x00, 0x00, 0x00, // file_size (not asserted in this test)
            0x57, 0x45, 0x42, 0x50, // "WEBP"
            0x56, 0x50, 0x38, 0x4c, // "VP8L"
            0x05, 0x00, 0x00, 0x00, // chunk_size = 5 (LE)
            0x2f, // VP8L signature
            0x63, 0x40, 0x0c, 0x00, // packed width=100, height=50 (see plan comments for the bit math)
        )
        byteReaderOf(bytes, "webp-walker-vp8l").use { reader ->
            val nodes = parseWebpChunks(reader, 0, bytes.size.toLong())
            val vp8l = nodes[1]
            assertEquals("VP8L", vp8l.type)
            assertEquals("100", vp8l.fields.first { it.name == "width" }.value)
            assertEquals("50", vp8l.fields.first { it.name == "height" }.value)
        }
    }

    @Test
    fun `the chunk-walking loop advances correctly past a VP8X chunk using the fixed little-endian size`() {
        // This is the test that actually proves the real-world impact of the bug fix:
        // before this fix, VP8X's chunk_size (10, LE) was misread as a huge big-endian
        // number, so the loop's "pos + totalSize > end" check would reject the very
        // next chunk (or worse) -- every chunk after the first was unreachable.
        val bytes = byteArrayOf(
            0x52, 0x49, 0x46, 0x46, // "RIFF"
            0x24, 0x00, 0x00, 0x00, // file_size = 36 (LE)
            0x57, 0x45, 0x42, 0x50, // "WEBP"
            0x56, 0x50, 0x38, 0x58, // "VP8X"
            0x0a, 0x00, 0x00, 0x00, // chunk_size = 10 (LE)
            0x00, 0x00, 0x00, 0x00, // flags + reserved
            0x1f, 0x03, 0x00, // width_minus_one = 799
            0x57, 0x02, 0x00, // height_minus_one = 599
            0x41, 0x4e, 0x49, 0x4d, // "ANIM"
            0x06, 0x00, 0x00, 0x00, // chunk_size = 6 (LE)
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // arbitrary ANIM payload (not decoded by this task)
        )
        byteReaderOf(bytes, "webp-walker-multi-chunk").use { reader ->
            val nodes = parseWebpChunks(reader, 0, bytes.size.toLong())
            assertEquals(3, nodes.size)
            assertEquals("RIFF", nodes[0].type)
            assertEquals("VP8X", nodes[1].type)
            assertEquals(12L, nodes[1].offset)
            assertEquals("ANIM", nodes[2].type)
            assertEquals(30L, nodes[2].offset)
            assertEquals(14L, nodes[2].size)
        }
    }
}
