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
        val bytes = pngChunk("tIME", byteArrayOf(0x07, 0xEA.toByte(), 0x01, 0x0F, 0x0C, 0x1E, 0x00))
        readerOver(bytes, "png-walker-generic").use { reader ->
            val nodes = parsePngChunks(reader, 0, bytes.size.toLong())
            assertEquals(1, nodes.size)
            assertEquals("tIME", nodes[0].type)
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
}
