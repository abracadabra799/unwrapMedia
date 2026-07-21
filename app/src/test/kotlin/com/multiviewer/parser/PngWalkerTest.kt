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
}
