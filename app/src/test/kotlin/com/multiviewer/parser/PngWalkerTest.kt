package com.multiviewer.parser

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun pngChunk(type: String, data: ByteArray): ByteArray {
    val length = data.size
    val lengthBytes = byteArrayOf(
        ((length shr 24) and 0xFF).toByte(),
        ((length shr 16) and 0xFF).toByte(),
        ((length shr 8) and 0xFF).toByte(),
        (length and 0xFF).toByte(),
    )
    val typeBytes = type.toByteArray(Charsets.US_ASCII)
    val crc = ByteArray(4)
    return lengthBytes + typeBytes + data + crc
}

private fun readerOver(bytes: ByteArray, namePrefix: String): ByteReader {
    val tmp = File.createTempFile(namePrefix, ".bin")
    tmp.deleteOnExit()
    tmp.writeBytes(bytes)
    return ByteReader.open(tmp)
}

class PngWalkerTest {
    @Test
    fun `decodes IHDR fields and a WxH summary`() {
        val ihdrData = byteArrayOf(
            0x00, 0x00, 0x03, 0x20, // width = 800
            0x00, 0x00, 0x02, 0x58, // height = 600
            0x08, // bit_depth = 8
            0x06, // color_type = 6 (Truecolor+Alpha)
            0x00, 0x00, 0x00, // compression_method, filter_method, interlace_method = 0
        )
        val bytes = pngChunk("IHDR", ihdrData)
        readerOver(bytes, "png-walker-ihdr").use { reader ->
            val nodes = parsePngChunks(reader, 0, bytes.size.toLong())
            assertEquals(1, nodes.size)
            val ihdr = nodes[0]
            assertEquals("IHDR", ihdr.type)
            assertEquals("800", ihdr.fields.first { it.name == "width" }.value)
            assertEquals("600", ihdr.fields.first { it.name == "height" }.value)
            assertEquals("8", ihdr.fields.first { it.name == "bit_depth" }.value)
            assertEquals("6", ihdr.fields.first { it.name == "color_type" }.value)
            assertEquals("800x600, Truecolor+Alpha, 8-bit", ihdr.summary)
        }
    }

    @Test
    fun `an unrecognized chunk type shows as a generic, field-less node`() {
        val bytes = pngChunk("xyzZ", byteArrayOf(0x07, 0xEA.toByte(), 0x01, 0x0F, 0x0C, 0x1E, 0x00))
        readerOver(bytes, "png-walker-generic").use { reader ->
            val nodes = parsePngChunks(reader, 0, bytes.size.toLong())
            assertEquals(1, nodes.size)
            assertEquals("xyzZ", nodes[0].type)
            assertTrue(nodes[0].fields.isEmpty())
            assertEquals(bytes.size.toLong(), nodes[0].size)
        }
    }

    @Test
    fun `a chunk declaring more bytes than remain produces a warning node`() {
        val bytes = byteArrayOf(
            0x00, 0x00, 0x00, 0x10, // declared length = 16, but no data follows
            'I'.code.toByte(), 'H'.code.toByte(), 'D'.code.toByte(), 'R'.code.toByte(),
        )
        readerOver(bytes, "png-walker-truncated").use { reader ->
            val nodes = parsePngChunks(reader, 0, bytes.size.toLong())
            assertEquals(1, nodes.size)
            assertTrue(nodes[0].warnings.isNotEmpty())
        }
    }

    @Test
    fun `decodes pHYs pixel density fields`() {
        val physData = byteArrayOf(
            0x00, 0x00, 0x0B, 0x13, // pixels_per_unit_x = 2835
            0x00, 0x00, 0x0B, 0x13, // pixels_per_unit_y = 2835
            0x01, // unit_specifier = 1 (meter)
        )
        val bytes = pngChunk("pHYs", physData)
        readerOver(bytes, "png-walker-phys").use { reader ->
            val nodes = parsePngChunks(reader, 0, bytes.size.toLong())
            val phys = nodes[0]
            assertEquals("pHYs", phys.type)
            assertEquals("2835", phys.fields.first { it.name == "pixels_per_unit_x" }.value)
            assertEquals("2835", phys.fields.first { it.name == "pixels_per_unit_y" }.value)
            assertEquals("meter", phys.fields.first { it.name == "unit_specifier" }.value)
            assertEquals("2835x2835 px/meter", phys.summary)
        }
    }

    @Test
    fun `decodes tEXt keyword and text`() {
        val textData = "Comment\u0000Made with unwrapMedia".toByteArray(Charsets.ISO_8859_1)
        val bytes = pngChunk("tEXt", textData)
        readerOver(bytes, "png-walker-text").use { reader ->
            val nodes = parsePngChunks(reader, 0, bytes.size.toLong())
            val text = nodes[0]
            assertEquals("tEXt", text.type)
            assertEquals("Comment", text.fields.first { it.name == "keyword" }.value)
            assertEquals("Made with unwrapMedia", text.fields.first { it.name == "text" }.value)
            assertEquals("Comment: Made with unwrapMedia", text.summary)
        }
    }

    @Test
    fun `decodes an eXIf chunk by reusing decodeTiff, producing an IFD0 child`() {
        val tiffBytes = byteArrayOf(
            0x49, 0x49, 0x2A, 0x00, // "II", 42 (little-endian byte order)
            0x08, 0x00, 0x00, 0x00, // IFD0 offset = 8
            0x02, 0x00, // entry_count = 2
            0x00, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, 0x80.toByte(), 0x02, 0x00, 0x00, // ImageWidth = 640
            0x01, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, 0xE0.toByte(), 0x01, 0x00, 0x00, // ImageLength = 480
            0x00, 0x00, 0x00, 0x00, // next IFD offset = 0
        )
        val bytes = pngChunk("eXIf", tiffBytes)
        readerOver(bytes, "png-walker-exif").use { reader ->
            val nodes = parsePngChunks(reader, 0, bytes.size.toLong())
            val exifChunk = nodes[0]
            assertEquals("eXIf", exifChunk.type)
            assertEquals(1, exifChunk.children.size)
            val ifd0 = exifChunk.children[0]
            assertEquals("IFD0", ifd0.type)
            assertEquals("640", ifd0.fields.first { it.name == "ImageWidth" }.value)
            assertEquals("480", ifd0.fields.first { it.name == "ImageLength" }.value)
        }
    }

    @Test
    fun `decodes gAMA as a gamma value`() {
        val bytes = pngChunk("gAMA", byteArrayOf(0x00, 0x00, 0xB1.toByte(), 0x8F.toByte())) // 45455 -> 0.45455
        readerOver(bytes, "png-walker-gama").use { reader ->
            val nodes = parsePngChunks(reader, 0, bytes.size.toLong())
            val gama = nodes[0]
            assertEquals("gAMA", gama.type)
            assertEquals("0.45455", gama.fields.first { it.name == "gamma" }.value)
            assertEquals("gamma=0.45455", gama.summary)
        }
    }

    @Test
    fun `decodes cHRM chromaticity points`() {
        val data = byteArrayOf(
            0x00, 0x00, 0x7A, 0x26, // white_x = 31270 -> 0.3127
            0x00, 0x00, 0x80.toByte(), 0x84.toByte(), // white_y = 32900 -> 0.3290
            0x00, 0x00, 0xFA.toByte(), 0x00, // red_x = 64000 -> 0.6400
            0x00, 0x00, 0x80.toByte(), 0xE8.toByte(), // red_y = 33000 -> 0.3300
            0x00, 0x00, 0x75, 0x30, // green_x = 30000 -> 0.3000
            0x00, 0x00, 0xEA.toByte(), 0x60, // green_y = 60000 -> 0.6000
            0x00, 0x00, 0x3A, 0x98.toByte(), // blue_x = 15000 -> 0.1500
            0x00, 0x00, 0x17, 0x70, // blue_y = 6000 -> 0.0600
        )
        val bytes = pngChunk("cHRM", data)
        readerOver(bytes, "png-walker-chrm").use { reader ->
            val nodes = parsePngChunks(reader, 0, bytes.size.toLong())
            val chrm = nodes[0]
            assertEquals("cHRM", chrm.type)
            assertEquals("x=0.3127, y=0.3290", chrm.fields.first { it.name == "white_point" }.value)
            assertEquals("x=0.6400, y=0.3300", chrm.fields.first { it.name == "red" }.value)
            assertEquals("x=0.3000, y=0.6000", chrm.fields.first { it.name == "green" }.value)
            assertEquals("x=0.1500, y=0.0600", chrm.fields.first { it.name == "blue" }.value)
        }
    }

    @Test
    fun `decodes sRGB rendering intent`() {
        val bytes = pngChunk("sRGB", byteArrayOf(0x00)) // 0 = Perceptual
        readerOver(bytes, "png-walker-srgb").use { reader ->
            val nodes = parsePngChunks(reader, 0, bytes.size.toLong())
            val srgb = nodes[0]
            assertEquals("sRGB", srgb.type)
            assertEquals("Perceptual", srgb.fields.first { it.name == "rendering_intent" }.value)
            assertEquals("Perceptual", srgb.summary)
        }
    }

    @Test
    fun `decodes tIME last-modified timestamp`() {
        val bytes = pngChunk("tIME", byteArrayOf(0x07, 0xE8.toByte(), 0x01, 0x0F, 0x0C, 0x1E, 0x00)) // 2024-01-15 12:30:00
        readerOver(bytes, "png-walker-time").use { reader ->
            val nodes = parsePngChunks(reader, 0, bytes.size.toLong())
            val time = nodes[0]
            assertEquals("tIME", time.type)
            assertEquals("2024-01-15 12:30:00 UTC", time.fields.first { it.name == "last_modified" }.value)
            assertEquals("2024-01-15 12:30:00 UTC", time.summary)
        }
    }
}
