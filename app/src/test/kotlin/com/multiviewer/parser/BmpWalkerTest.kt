package com.multiviewer.parser

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun byteReaderOf(bytes: ByteArray, namePrefix: String): ByteReader {
    val tmp = File.createTempFile(namePrefix, ".bin")
    tmp.deleteOnExit()
    tmp.writeBytes(bytes)
    return ByteReader.open(tmp)
}

// BMP headers have many multi-byte little-endian fields per struct (27 in
// BITMAPV5HEADER alone) -- fields are written into a zero-filled buffer
// programmatically rather than hand-encoded as literal hex byte arrays, to
// avoid arithmetic transcription errors. See the plan's byte-offset
// reference table for every literal offset used below.
private fun ByteArray.putUInt16LE(offset: Int, value: Int) {
    this[offset] = (value and 0xFF).toByte()
    this[offset + 1] = ((value shr 8) and 0xFF).toByte()
}

private fun ByteArray.putUInt32LE(offset: Int, value: Long) {
    this[offset] = (value and 0xFF).toByte()
    this[offset + 1] = ((value shr 8) and 0xFF).toByte()
    this[offset + 2] = ((value shr 16) and 0xFF).toByte()
    this[offset + 3] = ((value shr 24) and 0xFF).toByte()
}

private fun ByteArray.putInt32LE(offset: Int, value: Int) = putUInt32LE(offset, value.toLong() and 0xFFFFFFFFL)

class BmpWalkerTest {
    @Test
    fun `parses a classic BITMAPFILEHEADER and 40-byte BITMAPINFOHEADER end to end`() {
        val bytes = ByteArray(54) // 14 (BITMAPFILEHEADER) + 40 (BITMAPINFOHEADER)
        bytes[0] = 'B'.code.toByte()
        bytes[1] = 'M'.code.toByte()
        bytes.putUInt32LE(2, 15054L) // file_size
        bytes.putUInt32LE(10, 54L) // pixel_data_offset
        bytes.putUInt32LE(14, 40L) // header_size
        bytes.putInt32LE(18, 100) // width
        bytes.putInt32LE(22, 50) // height
        bytes.putUInt16LE(26, 1) // planes
        bytes.putUInt16LE(28, 24) // bit_count
        bytes.putUInt32LE(30, 0L) // compression = BI_RGB
        bytes.putUInt32LE(34, 15000L) // image_size
        bytes.putInt32LE(38, 2835) // x_pixels_per_meter (~72 DPI)
        bytes.putInt32LE(42, 2835) // y_pixels_per_meter
        bytes.putUInt32LE(46, 0L) // colors_used
        bytes.putUInt32LE(50, 0L) // colors_important

        byteReaderOf(bytes, "bmp-walker-classic").use { reader ->
            val nodes = parseBmpHeaders(reader, 0, bytes.size.toLong())
            assertEquals(2, nodes.size)
            val fileHeader = nodes[0]
            assertEquals("BITMAPFILEHEADER", fileHeader.type)
            assertEquals("15054", fileHeader.fields.first { it.name == "file_size" }.value)
            assertEquals("54", fileHeader.fields.first { it.name == "pixel_data_offset" }.value)

            val infoHeader = nodes[1]
            assertEquals("BITMAPINFOHEADER", infoHeader.type)
            fun field(name: String) = infoHeader.fields.first { it.name == name }.value
            assertEquals("100", field("width"))
            assertEquals("50", field("height"))
            assertEquals("1", field("planes"))
            assertEquals("24", field("bit_count"))
            assertEquals("None (BI_RGB)", field("compression"))
            assertEquals("15000", field("image_size"))
            assertEquals("2835", field("x_pixels_per_meter"))
            assertEquals("2835", field("y_pixels_per_meter"))
            assertEquals("0", field("colors_used"))
            assertEquals("0", field("colors_important"))
            assertEquals("100x50, 24-bit", infoHeader.summary)
        }
    }

    @Test
    fun `each documented compression value maps to its name`() {
        val expected = mapOf(
            0L to "None (BI_RGB)",
            1L to "RLE 8-bit (BI_RLE8)",
            2L to "RLE 4-bit (BI_RLE4)",
            3L to "Bit Fields (BI_BITFIELDS)",
            4L to "JPEG (BI_JPEG)",
            5L to "PNG (BI_PNG)",
            6L to "Alpha Bit Fields (BI_ALPHABITFIELDS)",
            11L to "CMYK (BI_CMYK)",
            12L to "CMYK RLE 8-bit (BI_CMYKRLE8)",
            13L to "CMYK RLE 4-bit (BI_CMYKRLE4)",
        )
        for ((value, label) in expected) {
            val bytes = ByteArray(54)
            bytes[0] = 'B'.code.toByte()
            bytes[1] = 'M'.code.toByte()
            bytes.putUInt32LE(14, 40L)
            bytes.putInt32LE(18, 1)
            bytes.putInt32LE(22, 1)
            bytes.putUInt16LE(28, 1)
            bytes.putUInt32LE(30, value)

            byteReaderOf(bytes, "bmp-walker-compression-$value").use { reader ->
                val nodes = parseBmpHeaders(reader, 0, bytes.size.toLong())
                assertEquals(label, nodes[1].fields.first { it.name == "compression" }.value)
            }
        }
    }

    @Test
    fun `an unrecognized compression value falls back to an Unknown label`() {
        val bytes = ByteArray(54)
        bytes[0] = 'B'.code.toByte()
        bytes[1] = 'M'.code.toByte()
        bytes.putUInt32LE(14, 40L)
        bytes.putInt32LE(18, 1)
        bytes.putInt32LE(22, 1)
        bytes.putUInt32LE(30, 99L)

        byteReaderOf(bytes, "bmp-walker-unknown-compression").use { reader ->
            val nodes = parseBmpHeaders(reader, 0, bytes.size.toLong())
            assertEquals("Unknown (99)", nodes[1].fields.first { it.name == "compression" }.value)
        }
    }

    @Test
    fun `a truncated BITMAPINFOHEADER produces a warning and no fields`() {
        val bytes = ByteArray(14 + 20) // header_size claims 40, but only 20 bytes follow
        bytes[0] = 'B'.code.toByte()
        bytes[1] = 'M'.code.toByte()
        bytes.putUInt32LE(14, 40L)

        byteReaderOf(bytes, "bmp-walker-truncated-infoheader").use { reader ->
            val nodes = parseBmpHeaders(reader, 0, bytes.size.toLong())
            val infoHeader = nodes[1]
            assertEquals(0, infoHeader.fields.size)
            assertEquals(listOf("Truncated BITMAPINFOHEADER"), infoHeader.warnings)
        }
    }

    // The following tests predate this task and cover parseBmpHeaders/
    // decodeBitmapFileHeader behavior that this task does not touch (top-down
    // height, non-40-byte DIB header fallback, and short-file handling).
    // Preserved here (adapted to the put*LE helpers above) rather than
    // dropped, since Task 1's brief only supplied replacement tests for the
    // BITMAPINFOHEADER field/compression work.
    @Test
    fun `a negative height decodes as a signed value (top-down bitmap)`() {
        val bytes = ByteArray(54)
        bytes[0] = 'B'.code.toByte()
        bytes[1] = 'M'.code.toByte()
        bytes.putUInt32LE(2, 122L)
        bytes.putUInt32LE(10, 54L)
        bytes.putUInt32LE(14, 40L)
        bytes.putInt32LE(18, 100)
        bytes.putInt32LE(22, -50)
        bytes.putUInt16LE(28, 24)

        byteReaderOf(bytes, "bmp-walker-topdown").use { reader ->
            val nodes = parseBmpHeaders(reader, 0, bytes.size.toLong())
            assertEquals("-50", nodes[1].fields.first { it.name == "height" }.value)
        }
    }

    @Test
    fun `a non-40-byte DIB header falls back to a generic DIBHEADER node`() {
        val bytes = ByteArray(14 + 12) // BITMAPCOREHEADER, 12 bytes total
        bytes[0] = 'B'.code.toByte()
        bytes[1] = 'M'.code.toByte()
        bytes.putUInt32LE(2, 68L)
        bytes.putUInt32LE(10, 26L)
        bytes.putUInt32LE(14, 12L)

        byteReaderOf(bytes, "bmp-walker-core-header").use { reader ->
            val nodes = parseBmpHeaders(reader, 0, bytes.size.toLong())
            assertEquals("DIBHEADER", nodes[1].type)
            assertEquals("12", nodes[1].fields.first { it.name == "header_size" }.value)
        }
    }

    @Test
    fun `a file too short for a BITMAPFILEHEADER produces a warning node`() {
        val bytes = byteArrayOf('B'.code.toByte(), 'M'.code.toByte(), 0x00, 0x00)
        byteReaderOf(bytes, "bmp-walker-truncated").use { reader ->
            val nodes = parseBmpHeaders(reader, 0, bytes.size.toLong())
            assertEquals(1, nodes.size)
            assertTrue(nodes[0].warnings.isNotEmpty())
        }
    }
}
