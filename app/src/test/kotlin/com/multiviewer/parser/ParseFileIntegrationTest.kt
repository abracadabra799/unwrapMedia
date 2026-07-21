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
