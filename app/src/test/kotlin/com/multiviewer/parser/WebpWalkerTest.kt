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
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // all-zero ANIM payload (decodes to background_color #00000000, loop_count 0/infinite -- not asserted here, this test is about the chunk-walking loop, not ANIM's fields; see the dedicated ANIM tests above)
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

    @Test
    fun `ICCP payload is parsed as a raw (uncompressed) 128-byte ICC header`() {
        // The same 128-byte ICC.1 header bytes already verified correct by
        // JpegWalkerTest's "APP2 ICC profile first chunk parses the full
        // 128-byte header" test -- reused here since WebP's ICCP chunk is
        // this exact 128-byte header with no name prefix and no compression
        // (unlike PNG's iCCP, which needed zlib-inflate in Phase 2).
        val iccHeaderBytes = byteArrayOf(
            0x00, 0x00, 0x00, 0x8e.toByte(), // profile_size = 142
            0x41, 0x50, 0x50, 0x4c,          // cmm_type = "APPL"
            0x02, 0x10, 0x00, 0x00,          // version = 2.1.0
            0x6d, 0x6e, 0x74, 0x72,          // profile_class = "mntr"
            0x52, 0x47, 0x42, 0x20,          // data_colour_space = "RGB "
            0x58, 0x59, 0x5a, 0x20,          // pcs = "XYZ "
            0x07, 0xe8.toByte(), 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // date_time_created
            0x61, 0x63, 0x73, 0x70,          // "acsp"
            0x41, 0x50, 0x50, 0x4c,          // primary_platform = "APPL"
            0x00, 0x00, 0x00, 0x00,          // profile_flags = 0
            0x41, 0x50, 0x50, 0x4c,          // device_manufacturer = "APPL"
            0x00, 0x00, 0x00, 0x00,          // device_model = (unspecified)
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // device_attributes = 0
            0x00, 0x00, 0x00, 0x00,          // rendering_intent = 0 (Perceptual)
            0x00, 0x00, 0xf6.toByte(), 0xd4.toByte(), // illuminant X = 0.9642
            0x00, 0x01, 0x00, 0x00,          // illuminant Y = 1.0000
            0x00, 0x00, 0xd3.toByte(), 0x2d, // illuminant Z = 0.8249
            0x41, 0x50, 0x50, 0x4c,          // profile_creator = "APPL"
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // profile_id = (not set)
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // 28 reserved bytes
        )
        val header = byteArrayOf(
            0x52, 0x49, 0x46, 0x46, // "RIFF"
            0x00, 0x00, 0x00, 0x00, // file_size (not asserted in this test)
            0x57, 0x45, 0x42, 0x50, // "WEBP"
            0x49, 0x43, 0x43, 0x50, // "ICCP"
            0x80.toByte(), 0x00, 0x00, 0x00, // chunk_size = 128 (LE)
        )
        val bytes = header + iccHeaderBytes
        byteReaderOf(bytes, "webp-walker-iccp").use { reader ->
            val nodes = parseWebpChunks(reader, 0, bytes.size.toLong())
            val iccp = nodes[1]
            assertEquals("ICCP", iccp.type)
            assertEquals("2.1.0", iccp.fields.first { it.name == "version" }.value)
            assertEquals("mntr", iccp.fields.first { it.name == "profile_class" }.value)
            assertEquals("RGB", iccp.fields.first { it.name == "data_colour_space" }.value)
            assertEquals("ICC Profile v2.1.0 (128 bytes)", iccp.summary)
        }
    }

    @Test
    fun `ICCP shorter than 128 bytes produces a warning and no fields`() {
        val bytes = byteArrayOf(
            0x52, 0x49, 0x46, 0x46, // "RIFF"
            0x00, 0x00, 0x00, 0x00, // file_size (not asserted in this test)
            0x57, 0x45, 0x42, 0x50, // "WEBP"
            0x49, 0x43, 0x43, 0x50, // "ICCP"
            0x0a, 0x00, 0x00, 0x00, // chunk_size = 10 (LE)
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, // 10 arbitrary bytes
        )
        byteReaderOf(bytes, "webp-walker-iccp-short").use { reader ->
            val nodes = parseWebpChunks(reader, 0, bytes.size.toLong())
            val iccp = nodes[1]
            assertEquals("ICCP", iccp.type)
            assertEquals(0, iccp.fields.size)
            assertEquals(listOf("ICC profile too short to contain a valid header"), iccp.warnings)
        }
    }

    @Test
    fun `XMP payload is exposed as a single UTF-8 text field`() {
        val text = "<x:xmpmeta></x:xmpmeta> " // 24 chars, ASCII, even length
        val payload = text.toByteArray(Charsets.UTF_8)
        val sizeBytes = byteArrayOf(payload.size.toByte(), 0x00, 0x00, 0x00) // chunk_size (LE)
        val header = byteArrayOf(
            0x52, 0x49, 0x46, 0x46, // "RIFF"
            0x00, 0x00, 0x00, 0x00, // file_size (not asserted in this test)
            0x57, 0x45, 0x42, 0x50, // "WEBP"
            0x58, 0x4d, 0x50, 0x20, // "XMP " (trailing space, same FourCC padding convention as "VP8 ")
        )
        val bytes = header + sizeBytes + payload
        byteReaderOf(bytes, "webp-walker-xmp").use { reader ->
            val nodes = parseWebpChunks(reader, 0, bytes.size.toLong())
            val xmp = nodes[1]
            assertEquals("XMP ", xmp.type)
            assertEquals(text, xmp.fields.first { it.name == "xmp" }.value)
            assertEquals("XMP (24 chars)", xmp.summary)
        }
    }

    @Test
    fun `ANIM decodes background color and a non-zero loop count`() {
        val bytes = byteArrayOf(
            0x52, 0x49, 0x46, 0x46, // "RIFF"
            0x00, 0x00, 0x00, 0x00, // file_size (not asserted in this test)
            0x57, 0x45, 0x42, 0x50, // "WEBP"
            0x41, 0x4e, 0x49, 0x4d, // "ANIM"
            0x06, 0x00, 0x00, 0x00, // chunk_size = 6 (LE)
            0x11, 0x22, 0x33, 0xff.toByte(), // background color: B=0x11, G=0x22, R=0x33, A=0xFF
            0x05, 0x00, // loop_count = 5 (LE)
        )
        byteReaderOf(bytes, "webp-walker-anim").use { reader ->
            val nodes = parseWebpChunks(reader, 0, bytes.size.toLong())
            val anim = nodes[1]
            assertEquals("ANIM", anim.type)
            assertEquals("#332211FF", anim.fields.first { it.name == "background_color" }.value)
            assertEquals("5", anim.fields.first { it.name == "loop_count" }.value)
            assertEquals("Loop count: 5", anim.summary)
        }
    }

    @Test
    fun `ANIM loop count of zero is labeled infinite`() {
        val bytes = byteArrayOf(
            0x52, 0x49, 0x46, 0x46,
            0x00, 0x00, 0x00, 0x00,
            0x57, 0x45, 0x42, 0x50,
            0x41, 0x4e, 0x49, 0x4d,
            0x06, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, // background color = black, opaque=0 (not asserted here)
            0x00, 0x00, // loop_count = 0
        )
        byteReaderOf(bytes, "webp-walker-anim-infinite").use { reader ->
            val nodes = parseWebpChunks(reader, 0, bytes.size.toLong())
            val anim = nodes[1]
            assertEquals("0 (infinite)", anim.fields.first { it.name == "loop_count" }.value)
            assertEquals("Loop count: 0 (infinite)", anim.summary)
        }
    }

    @Test
    fun `ANIM shorter than 6 bytes produces a warning and no fields`() {
        val bytes = byteArrayOf(
            0x52, 0x49, 0x46, 0x46,
            0x00, 0x00, 0x00, 0x00,
            0x57, 0x45, 0x42, 0x50,
            0x41, 0x4e, 0x49, 0x4d,
            0x03, 0x00, 0x00, 0x00, // chunk_size = 3 (too short)
            0x01, 0x02, 0x03,
        )
        byteReaderOf(bytes, "webp-walker-anim-short").use { reader ->
            val nodes = parseWebpChunks(reader, 0, bytes.size.toLong())
            val anim = nodes[1]
            assertEquals(0, anim.fields.size)
            assertEquals(listOf("ANIM chunk too short to contain background color and loop count"), anim.warnings)
        }
    }

    @Test
    fun `ANMF decodes its 16-byte frame header and summarizes the unparsed frame data`() {
        val bytes = byteArrayOf(
            0x52, 0x49, 0x46, 0x46, // "RIFF"
            0x00, 0x00, 0x00, 0x00, // file_size (not asserted in this test)
            0x57, 0x45, 0x42, 0x50, // "WEBP"
            0x41, 0x4e, 0x4d, 0x46, // "ANMF"
            0x14, 0x00, 0x00, 0x00, // chunk_size = 20 (LE) -- 16-byte header + 4 bytes of unparsed frame data
            0x05, 0x00, 0x00, // frame_x raw = 5 -> frame_x = 10 (raw * 2)
            0x03, 0x00, 0x00, // frame_y raw = 3 -> frame_y = 6
            0x9f.toByte(), 0x00, 0x00, // width_minus_one = 159 -> width = 160
            0x77, 0x00, 0x00, // height_minus_one = 119 -> height = 120
            0x64, 0x00, 0x00, // duration raw = 100 -> duration_ms = 100
            0x03, // flags: bit1 (blending) = 1, bit0 (disposal) = 1
            0xde.toByte(), 0xad.toByte(), 0xbe.toByte(), 0xef.toByte(), // 4 bytes of unparsed frame sub-chunk data
        )
        byteReaderOf(bytes, "webp-walker-anmf").use { reader ->
            val nodes = parseWebpChunks(reader, 0, bytes.size.toLong())
            val anmf = nodes[1]
            assertEquals("ANMF", anmf.type)
            assertEquals("10", anmf.fields.first { it.name == "frame_x" }.value)
            assertEquals("6", anmf.fields.first { it.name == "frame_y" }.value)
            assertEquals("160", anmf.fields.first { it.name == "width" }.value)
            assertEquals("120", anmf.fields.first { it.name == "height" }.value)
            assertEquals("100", anmf.fields.first { it.name == "duration_ms" }.value)
            assertEquals("Do not blend", anmf.fields.first { it.name == "blending" }.value)
            assertEquals("Dispose to background", anmf.fields.first { it.name == "disposal" }.value)
            assertEquals("160x120, 100ms (frame data: 4 bytes, not parsed)", anmf.summary)
        }
    }

    @Test
    fun `ANMF flags of zero mean blend and do not dispose`() {
        val bytes = byteArrayOf(
            0x52, 0x49, 0x46, 0x46,
            0x00, 0x00, 0x00, 0x00,
            0x57, 0x45, 0x42, 0x50,
            0x41, 0x4e, 0x4d, 0x46,
            0x10, 0x00, 0x00, 0x00, // chunk_size = 16 (header only, no trailing frame data)
            0x00, 0x00, 0x00, // frame_x raw = 0
            0x00, 0x00, 0x00, // frame_y raw = 0
            0x00, 0x00, 0x00, // width_minus_one = 0 -> width = 1
            0x00, 0x00, 0x00, // height_minus_one = 0 -> height = 1
            0x00, 0x00, 0x00, // duration raw = 0
            0x00, // flags = 0
        )
        byteReaderOf(bytes, "webp-walker-anmf-zero-flags").use { reader ->
            val nodes = parseWebpChunks(reader, 0, bytes.size.toLong())
            val anmf = nodes[1]
            assertEquals("Blend", anmf.fields.first { it.name == "blending" }.value)
            assertEquals("Do not dispose", anmf.fields.first { it.name == "disposal" }.value)
            assertEquals("1x1, 0ms (frame data: 0 bytes, not parsed)", anmf.summary)
        }
    }

    @Test
    fun `ANMF shorter than 16 bytes produces a warning and no fields`() {
        val bytes = byteArrayOf(
            0x52, 0x49, 0x46, 0x46,
            0x00, 0x00, 0x00, 0x00,
            0x57, 0x45, 0x42, 0x50,
            0x41, 0x4e, 0x4d, 0x46,
            0x0a, 0x00, 0x00, 0x00, // chunk_size = 10 (too short for the 16-byte header)
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a,
        )
        byteReaderOf(bytes, "webp-walker-anmf-short").use { reader ->
            val nodes = parseWebpChunks(reader, 0, bytes.size.toLong())
            val anmf = nodes[1]
            assertEquals(0, anmf.fields.size)
            assertEquals(listOf("ANMF chunk too short to contain its frame header"), anmf.warnings)
        }
    }

    private fun alphBytes(headerByte: Byte): ByteArray = byteArrayOf(
        0x52, 0x49, 0x46, 0x46, // "RIFF"
        0x00, 0x00, 0x00, 0x00, // file_size (not asserted in these tests)
        0x57, 0x45, 0x42, 0x50, // "WEBP"
        0x41, 0x4c, 0x50, 0x48, // "ALPH"
        0x01, 0x00, 0x00, 0x00, // chunk_size = 1
        headerByte,
    )

    @Test
    fun `ALPH decodes all four preprocessing values`() {
        val labels = listOf("None", "Level reduction", "Reserved (2)", "Reserved (3)")
        for (value in 0..3) {
            val bytes = alphBytes((value shl 4).toByte())
            byteReaderOf(bytes, "webp-walker-alph-preprocessing-$value").use { reader ->
                val nodes = parseWebpChunks(reader, 0, bytes.size.toLong())
                val alph = nodes[1]
                assertEquals(labels[value], alph.fields.first { it.name == "preprocessing" }.value)
            }
        }
    }

    @Test
    fun `ALPH decodes all four filtering_method values`() {
        val labels = listOf("None", "Horizontal", "Vertical", "Gradient")
        for (value in 0..3) {
            val bytes = alphBytes((value shl 2).toByte())
            byteReaderOf(bytes, "webp-walker-alph-filtering-$value").use { reader ->
                val nodes = parseWebpChunks(reader, 0, bytes.size.toLong())
                val alph = nodes[1]
                assertEquals(labels[value], alph.fields.first { it.name == "filtering_method" }.value)
            }
        }
    }

    @Test
    fun `ALPH decodes all four compression_method values`() {
        val labels = listOf("None", "Lossless (WebP)", "Reserved (2)", "Reserved (3)")
        for (value in 0..3) {
            val bytes = alphBytes(value.toByte())
            byteReaderOf(bytes, "webp-walker-alph-compression-$value").use { reader ->
                val nodes = parseWebpChunks(reader, 0, bytes.size.toLong())
                val alph = nodes[1]
                assertEquals(labels[value], alph.fields.first { it.name == "compression_method" }.value)
            }
        }
    }

    @Test
    fun `ALPH exposes non-zero reserved bits and builds a combined summary`() {
        // byte 0b11_01_10_11: reserved=3, preprocessing=1, filtering=2, compression=3
        val bytes = alphBytes(0xdb.toByte())
        byteReaderOf(bytes, "webp-walker-alph-combined").use { reader ->
            val nodes = parseWebpChunks(reader, 0, bytes.size.toLong())
            val alph = nodes[1]
            assertEquals("3", alph.fields.first { it.name == "reserved" }.value)
            assertEquals("Level reduction", alph.fields.first { it.name == "preprocessing" }.value)
            assertEquals("Vertical", alph.fields.first { it.name == "filtering_method" }.value)
            assertEquals("Reserved (3)", alph.fields.first { it.name == "compression_method" }.value)
            assertEquals("Vertical filtering, Reserved (3) compression", alph.summary)
        }
    }

    @Test
    fun `ALPH with an empty payload produces a warning and no fields`() {
        val bytes = byteArrayOf(
            0x52, 0x49, 0x46, 0x46,
            0x00, 0x00, 0x00, 0x00,
            0x57, 0x45, 0x42, 0x50,
            0x41, 0x4c, 0x50, 0x48,
            0x00, 0x00, 0x00, 0x00, // chunk_size = 0
        )
        byteReaderOf(bytes, "webp-walker-alph-empty").use { reader ->
            val nodes = parseWebpChunks(reader, 0, bytes.size.toLong())
            val alph = nodes[1]
            assertEquals(0, alph.fields.size)
            assertEquals(listOf("ALPH chunk too short to contain its header byte"), alph.warnings)
        }
    }
}
