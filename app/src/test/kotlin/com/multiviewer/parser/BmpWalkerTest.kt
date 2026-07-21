package com.multiviewer.parser

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun uint16LE(value: Int): ByteArray = byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())

private fun uint32LE(value: Long): ByteArray = byteArrayOf(
    (value and 0xFF).toByte(),
    ((value shr 8) and 0xFF).toByte(),
    ((value shr 16) and 0xFF).toByte(),
    ((value shr 24) and 0xFF).toByte(),
)

private fun int32LE(value: Int): ByteArray = uint32LE(value.toLong() and 0xFFFFFFFFL)

private fun bmpFileHeader(fileSize: Long, pixelDataOffset: Long): ByteArray =
    byteArrayOf('B'.code.toByte(), 'M'.code.toByte()) + uint32LE(fileSize) + ByteArray(4) + uint32LE(pixelDataOffset)

private fun bitmapInfoHeader(width: Int, height: Int, bitCount: Int): ByteArray =
    uint32LE(40) + int32LE(width) + int32LE(height) + uint16LE(1) + uint16LE(bitCount) + ByteArray(24)

private fun readerOver(bytes: ByteArray, namePrefix: String): ByteReader {
    val tmp = File.createTempFile(namePrefix, ".bin")
    tmp.deleteOnExit()
    tmp.writeBytes(bytes)
    return ByteReader.open(tmp)
}

class BmpWalkerTest {
    @Test
    fun `decodes BITMAPFILEHEADER and BITMAPINFOHEADER for a bottom-up bitmap`() {
        val bytes = bmpFileHeader(fileSize = 122, pixelDataOffset = 54) + bitmapInfoHeader(width = 100, height = 50, bitCount = 24)
        readerOver(bytes, "bmp-walker-basic").use { reader ->
            val nodes = parseBmpHeaders(reader, 0, bytes.size.toLong())
            assertEquals(listOf("BITMAPFILEHEADER", "BITMAPINFOHEADER"), nodes.map { it.type })
            assertEquals("122", nodes[0].fields.first { it.name == "file_size" }.value)
            assertEquals("54", nodes[0].fields.first { it.name == "pixel_data_offset" }.value)
            assertEquals("100", nodes[1].fields.first { it.name == "width" }.value)
            assertEquals("50", nodes[1].fields.first { it.name == "height" }.value)
            assertEquals("0", nodes[1].fields.first { it.name == "compression" }.value)
            assertEquals("100x50, 24-bit", nodes[1].summary)
        }
    }

    @Test
    fun `a negative height decodes as a signed value (top-down bitmap)`() {
        val bytes = bmpFileHeader(fileSize = 122, pixelDataOffset = 54) + bitmapInfoHeader(width = 100, height = -50, bitCount = 24)
        readerOver(bytes, "bmp-walker-topdown").use { reader ->
            val nodes = parseBmpHeaders(reader, 0, bytes.size.toLong())
            assertEquals("-50", nodes[1].fields.first { it.name == "height" }.value)
        }
    }

    @Test
    fun `a non-40-byte DIB header falls back to a generic DIBHEADER node`() {
        val coreHeader = uint32LE(12) + ByteArray(8) // BITMAPCOREHEADER, 12 bytes total
        val bytes = bmpFileHeader(fileSize = 68, pixelDataOffset = 26) + coreHeader
        readerOver(bytes, "bmp-walker-core-header").use { reader ->
            val nodes = parseBmpHeaders(reader, 0, bytes.size.toLong())
            assertEquals("DIBHEADER", nodes[1].type)
            assertEquals("12", nodes[1].fields.first { it.name == "header_size" }.value)
        }
    }

    @Test
    fun `a file too short for a BITMAPFILEHEADER produces a warning node`() {
        val bytes = byteArrayOf('B'.code.toByte(), 'M'.code.toByte(), 0x00, 0x00)
        readerOver(bytes, "bmp-walker-truncated").use { reader ->
            val nodes = parseBmpHeaders(reader, 0, bytes.size.toLong())
            assertEquals(1, nodes.size)
            assertTrue(nodes[0].warnings.isNotEmpty())
        }
    }
}
