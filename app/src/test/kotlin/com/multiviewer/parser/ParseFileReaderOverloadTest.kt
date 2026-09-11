package com.multiviewer.parser

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class ParseFileReaderOverloadTest {
    // Same minimal synthetic MP4 fixture ParseFileIntegrationTest.kt's first test uses --
    // reusing it (rather than a new fixture) keeps this test's only variable the reader
    // overload itself, not the fixture.
    private fun syntheticMp4Bytes(): ByteArray {
        val ftyp = box("ftyp", byteArrayOf(0x69, 0x73, 0x6F, 0x6D, 0x00, 0x00, 0x00, 0x00))
        val mvhd = fullBox("mvhd", version = 0, body = uint32(0) + uint32(0) + uint32(600) + uint32(1200))
        val mdat = box("mdat", byteArrayOf(0x01, 0x02, 0x03))
        return ftyp + mvhd + mdat
    }

    @Test
    fun `reader-accepting overload produces the same tree as the file-only overload`() {
        val tmp = File.createTempFile("multiviewer-reader-overload", ".mp4")
        tmp.deleteOnExit()
        tmp.writeBytes(syntheticMp4Bytes())

        val viaFileOnly = parseFile(tmp)
        val viaSharedReader = ByteReader.open(tmp).use { reader -> parseFile(tmp, reader) }

        assertEquals(viaFileOnly, viaSharedReader)
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
