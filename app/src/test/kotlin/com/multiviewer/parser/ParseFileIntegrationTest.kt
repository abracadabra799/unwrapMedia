package com.multiviewer.parser

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ParseFileIntegrationTest {
    @Test
    fun `parses a synthetic minimal mp4 into a full box tree with decoded fields`() {
        val ftyp = box("ftyp", byteArrayOf(0x69, 0x73, 0x6F, 0x6D, 0x00, 0x00, 0x00, 0x00))
        val mvhd = fullBox("mvhd", version = 0, body = uint32(0) + uint32(0) + uint32(600) + uint32(1200))
        val tkhd = fullBox(
            "tkhd", version = 0,
            body = uint32(0) + uint32(0) + uint32(7) + uint32(0) + uint32(10) + ByteArray(8 + 2 + 2 + 2 + 2 + 36) + uint32(1920L * 65536L) + uint32(1080L * 65536L),
        )
        val mdhd = fullBox("mdhd", version = 0, body = uint32(0) + uint32(0) + uint32(1000) + uint32(2000) + byteArrayOf(0x55, 0xC4.toByte()) + byteArrayOf(0, 0))
        val hdlr = fullBox("hdlr", version = 0, body = uint32(0) + "vide".toByteArray() + ByteArray(12) + "Video\u0000".toByteArray())
        val mdia = box("mdia", mdhd + hdlr)
        val trak = box("trak", tkhd + mdia)
        val moov = box("moov", mvhd + trak)
        val mdat = box("mdat", byteArrayOf(0x01, 0x02, 0x03))

        val bytes = ftyp + moov + mdat
        val tmp = File.createTempFile("multiviewer-integration", ".mp4")
        tmp.deleteOnExit()
        tmp.writeBytes(bytes)

        val root = parseFile(tmp)

        assertEquals(3, root.children.size)
        assertEquals(listOf("ftyp", "moov", "mdat"), root.children.map { it.type })

        val moovNode = root.children[1]
        assertEquals(listOf("mvhd", "trak"), moovNode.children.map { it.type })
        assertNotNull(moovNode.children[0].summary)

        val trakNode = moovNode.children[1]
        val mdiaNode = trakNode.children.single { it.type == "mdia" }
        assertEquals(listOf("mdhd", "hdlr"), mdiaNode.children.map { it.type })
        assertEquals("vide: Video", mdiaNode.children[1].summary)
    }

    @Test
    fun `parses a JPEG file via the JPEG marker-segment path, not the ISOBMFF path`() {
        val bytes = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte())
        val tmp = File.createTempFile("multiviewer-jpeg", ".jpg")
        tmp.deleteOnExit()
        tmp.writeBytes(bytes)

        val root = parseFile(tmp)

        assertEquals(listOf("SOI", "EOI"), root.children.map { it.type })
    }

    @Test
    fun `parses a TIFF file via decodeTiff, not the ISOBMFF path`() {
        val bytes = byteArrayOf(
            0x49, 0x49, 0x2A, 0x00, // "II", 42 (little-endian byte order)
            0x08, 0x00, 0x00, 0x00, // IFD0 offset = 8
            0x02, 0x00, // entry_count = 2
            0x00, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, 0x80.toByte(), 0x02, 0x00, 0x00, // ImageWidth = 640
            0x01, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, 0xE0.toByte(), 0x01, 0x00, 0x00, // ImageLength = 480
            0x00, 0x00, 0x00, 0x00, // next IFD offset = 0
        )
        val tmp = File.createTempFile("multiviewer-tiff", ".tiff")
        tmp.deleteOnExit()
        tmp.writeBytes(bytes)

        val root = parseFile(tmp)

        assertEquals(listOf("IFD0"), root.children.map { it.type })
        val ifd0 = root.children.single()
        assertEquals("640", ifd0.fields.first { it.name == "ImageWidth" }.value)
        assertEquals("480", ifd0.fields.first { it.name == "ImageLength" }.value)
    }

    @Test
    fun `parses a big-endian TIFF file (MM byte order) via decodeTiff`() {
        val bytes = byteArrayOf(
            0x4D, 0x4D, 0x00, 0x2A, // "MM", 42 (big-endian byte order)
            0x00, 0x00, 0x00, 0x08, // IFD0 offset = 8
            0x00, 0x02, // entry_count = 2
            0x01, 0x00, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01, 0x02, 0x80.toByte(), 0x00, 0x00, // ImageWidth = 640
            0x01, 0x01, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01, 0x01, 0xE0.toByte(), 0x00, 0x00, // ImageLength = 480
            0x00, 0x00, 0x00, 0x00, // next IFD offset = 0
        )
        val tmp = File.createTempFile("multiviewer-tiff-be", ".tiff")
        tmp.deleteOnExit()
        tmp.writeBytes(bytes)

        val root = parseFile(tmp)

        assertEquals(listOf("IFD0"), root.children.map { it.type })
        val ifd0 = root.children.single()
        assertEquals("640", ifd0.fields.first { it.name == "ImageWidth" }.value)
        assertEquals("480", ifd0.fields.first { it.name == "ImageLength" }.value)
    }

    @Test
    fun `parses a PNG file via the chunk walker, not the ISOBMFF path`() {
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val ihdrData = byteArrayOf(
            0x00, 0x00, 0x00, 0x0A, // width = 10
            0x00, 0x00, 0x00, 0x0A, // height = 10
            0x08, 0x02, 0x00, 0x00, 0x00, // bit_depth=8, color_type=2 (Truecolor), rest=0
        )
        val ihdrChunk = pngChunkBytes("IHDR", ihdrData)
        val iendChunk = pngChunkBytes("IEND", ByteArray(0))
        val bytes = signature + ihdrChunk + iendChunk
        val tmp = File.createTempFile("multiviewer-png", ".png")
        tmp.deleteOnExit()
        tmp.writeBytes(bytes)

        val root = parseFile(tmp)

        assertEquals(listOf("IHDR", "IEND"), root.children.map { it.type })
    }

    @Test
    fun `parses a BMP file via the header walker, not the ISOBMFF or TIFF path`() {
        val fileHeader = byteArrayOf('B'.code.toByte(), 'M'.code.toByte()) + uint32LE(70) + ByteArray(4) + uint32LE(54)
        val infoHeader = uint32LE(40) + int32LE(10) + int32LE(10) + uint16LE(1) + uint16LE(24) + ByteArray(24)
        val bytes = fileHeader + infoHeader
        val tmp = File.createTempFile("multiviewer-bmp", ".bmp")
        tmp.deleteOnExit()
        tmp.writeBytes(bytes)

        val root = parseFile(tmp)

        assertEquals(listOf("BITMAPFILEHEADER", "BITMAPINFOHEADER"), root.children.map { it.type })
    }

    @Test
    fun `parses a GIF file via the block walker, not the ISOBMFF or TIFF path`() {
        val header = "GIF89a".toByteArray(Charsets.US_ASCII)
        val logicalScreenDescriptor = uint16LE(4) + uint16LE(4) + byteArrayOf(0x00, 0x00, 0x00)
        val trailer = byteArrayOf(0x3B)
        val bytes = header + logicalScreenDescriptor + trailer
        val tmp = File.createTempFile("multiviewer-gif", ".gif")
        tmp.deleteOnExit()
        tmp.writeBytes(bytes)

        val root = parseFile(tmp)

        assertEquals(listOf("LogicalScreenDescriptor", "Trailer"), root.children.map { it.type })
    }
}

private fun uint32(value: Long): ByteArray = byteArrayOf(
    ((value shr 24) and 0xFF).toByte(),
    ((value shr 16) and 0xFF).toByte(),
    ((value shr 8) and 0xFF).toByte(),
    (value and 0xFF).toByte(),
)

private fun box(type: String, body: ByteArray): ByteArray {
    val size = 8 + body.size
    return uint32(size.toLong()) + type.toByteArray(Charsets.US_ASCII) + body
}

private fun fullBox(type: String, version: Int, body: ByteArray): ByteArray {
    val fullBoxHeader = byteArrayOf(version.toByte(), 0, 0, 0)
    return box(type, fullBoxHeader + body)
}

private fun pngChunkBytes(type: String, data: ByteArray): ByteArray {
    val length = data.size
    val lengthBytes = byteArrayOf(
        ((length shr 24) and 0xFF).toByte(),
        ((length shr 16) and 0xFF).toByte(),
        ((length shr 8) and 0xFF).toByte(),
        (length and 0xFF).toByte(),
    )
    return lengthBytes + type.toByteArray(Charsets.US_ASCII) + data + ByteArray(4)
}

private fun uint16LE(value: Int): ByteArray = byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())

private fun uint32LE(value: Long): ByteArray = byteArrayOf(
    (value and 0xFF).toByte(),
    ((value shr 8) and 0xFF).toByte(),
    ((value shr 16) and 0xFF).toByte(),
    ((value shr 24) and 0xFF).toByte(),
)

private fun int32LE(value: Int): ByteArray = uint32LE(value.toLong() and 0xFFFFFFFFL)
