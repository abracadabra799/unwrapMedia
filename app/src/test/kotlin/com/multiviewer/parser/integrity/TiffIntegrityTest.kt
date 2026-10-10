package com.multiviewer.parser.integrity

import com.multiviewer.parser.ByteReader
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    /** Little-endian TIFF from IFDs laid out back to back from offset 8: each IFD is (entries, next-IFD offset);
     *  entries are (tag, LONG value). [ifd0Offset] overrides the header pointer. */
    private fun rawTiff(ifds: List<Pair<List<Pair<Int, Long>>, Long>>, ifd0Offset: Long = 8): ByteArray {
        var out = byteArrayOf(0x49, 0x49, 0x2A, 0) + le32(ifd0Offset)
        for ((entries, next) in ifds) {
            out += le16(entries.size)
            for ((tag, v) in entries) out += le16(tag) + le16(4) + le32(1) + le32(v)
            out += le32(next)
        }
        return out
    }

    private fun ifdSize(entries: Int) = 2L + 12 * entries + 4

    @Test
    fun `an exif IFD shared by IFD0 and IFD1 is not a loop`() {
        val ifd1 = 8 + ifdSize(1)
        val exif = ifd1 + ifdSize(1)
        val r = checkBytes(rawTiff(listOf(
            listOf(0x8769 to exif) to ifd1,
            listOf(0x8769 to exif) to 0L,
            listOf(0x9000 to 0L) to 0L,
        )), "tif")
        assertTrue(r.items.none { it.id == "tiff.loop" }, r.items.toString())
        assertEquals(CheckStatus.PASS, r.item("tiff.ifd").status, r.items.toString())
    }

    @Test
    fun `junk next pointer of an exif IFD is ignored`() {
        val exif = 8 + ifdSize(1)
        val r = checkBytes(rawTiff(listOf(
            listOf(0x8769 to exif) to 0L,
            listOf(0x9000 to 0L) to 0xFFFFFFF0L,
        )), "tif")
        assertTrue(r.items.none { it.status == CheckStatus.FAIL }, r.items.toString())
        assertEquals(CheckStatus.PASS, r.item("tiff.ifd").status, r.items.toString())
    }

    @Test
    fun `out-of-file next pointer inside a SubIFD chain warns, not fails`() {
        val sub = 8 + ifdSize(1)
        val r = checkBytes(rawTiff(listOf(
            listOf(0x14A to sub) to 0L,
            listOf(0x9000 to 0L) to 0xFFFFFFF0L,
        )), "tif")
        assertTrue(r.items.none { it.status == CheckStatus.FAIL }, r.items.toString())
        assertEquals(CheckStatus.WARN, r.item("tiff.ifd").status, r.items.toString())
    }

    @Test
    fun `SubIFD next pointer onto a huge entry count warns, not fails`() {
        val sub = 8 + ifdSize(1)
        val junk = sub + ifdSize(1)
        val r = checkBytes(rawTiff(listOf(
            listOf(0x14A to sub) to 0L,
            listOf(0x9000 to 0L) to junk,
        )) + le16(0xFFFF) + ByteArray(20), "tif")
        assertTrue(r.items.none { it.status == CheckStatus.FAIL }, r.items.toString())
        assertEquals(CheckStatus.WARN, r.item("tiff.ifd").status, r.items.toString())
    }

    @Test
    fun `SubIFD chain looping back warns, not fails`() {
        val sub = 8 + ifdSize(1)
        val r = checkBytes(rawTiff(listOf(
            listOf(0x14A to sub) to 0L,
            listOf(0x9000 to 0L) to sub,
        )), "tif")
        assertTrue(r.items.none { it.status == CheckStatus.FAIL }, r.items.toString())
        assertEquals(CheckStatus.WARN, r.item("tiff.loop").status, r.items.toString())
    }

    @Test
    fun `IFD0 offset 0 fails`() {
        val item = checkBytes(rawTiff(listOf(listOf(0x100 to 1L) to 0L), ifd0Offset = 0), "tif").item("tiff.ifd")
        assertEquals(CheckStatus.FAIL, item.status)
        assertEquals("IFD0 offset is 0: the file has no image directory", item.detail)
    }
}
