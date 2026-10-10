package com.multiviewer.parser.integrity

import com.multiviewer.parser.ByteReader
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TiffIntegrityTest {
    private fun reader(bytes: ByteArray): ByteReader {
        val f = File.createTempFile("tiff-", ".tif"); f.deleteOnExit(); f.writeBytes(bytes)
        return ByteReader.open(f)
    }

    @Test
    fun `valid tiff passes and declares IFD0 size`() {
        val r = checkBytes(tiffBytes(), "tif")
        assertEquals("TIFF", r.format)
        listOf("tiff.ifd", "tiff.data").forEach { assertEquals(CheckStatus.PASS, r.item(it).status, it) }
        assertEquals(4, r.declaredWidth)
        assertEquals(2, r.declaredHeight)
    }

    @Test
    fun `strip past end of file fails`() {
        assertEquals(CheckStatus.FAIL, checkBytes(tiffBytes(stripCountOverride = 1000), "tif").item("tiff.data").status)
    }

    @Test
    fun `IFD chain pointing back to itself is a loop`() {
        assertEquals(CheckStatus.FAIL, checkBytes(tiffBytes(nextIfd = 8), "tif").item("tiff.loop").status)
    }

    @Test
    fun `next IFD outside the file fails`() {
        assertEquals(CheckStatus.FAIL, checkBytes(tiffBytes(nextIfd = 99_999), "tif").item("tiff.ifd").status)
    }

    @Test
    fun `largest baseline jpeg preview is found, lossless is ignored`() {
        val preview = jpegBytes()
        val bytes = tiffBytes(jpegPreview = preview)
        reader(bytes).use { assertEquals((bytes.size - preview.size).toLong() to preview.size.toLong(), TiffIntegrity.largestJpegPreview(it)) }
        reader(tiffBytes(jpegPreview = jpegBytes(sofMarker = 0xC3))).use { assertNull(TiffIntegrity.largestJpegPreview(it)) }
    }
}
