package com.multiviewer.parser.integrity

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.parseFile
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class JpegIntegrityTest {
    @Test
    fun `valid jpeg passes every check and declares its size`() {
        val r = checkBytes(jpegBytes(), "jpg")
        assertEquals("JPEG", r.format)
        listOf("jpeg.soi", "jpeg.frame", "jpeg.dqt", "jpeg.dht", "jpeg.eoi", "jpeg.trailing").forEach {
            assertEquals(CheckStatus.PASS, r.item(it).status, it)
        }
        assertEquals(CheckStatus.PASS, r.overall, r.items.toString())
        assertEquals(3, r.declaredWidth)
        assertEquals(2, r.declaredHeight)
    }

    @Test
    fun `missing EOI fails`() {
        val r = checkBytes(jpegBytes(withEoi = false), "jpg")
        assertEquals(CheckStatus.FAIL, r.item("jpeg.eoi").status)
        assertEquals(CheckStatus.FAIL, r.overall)
    }

    @Test
    fun `unknown bytes after EOI warn at the first unknown offset`() {
        val base = jpegBytes()
        val r = checkBytes(jpegBytes(trailing = byteArrayOf(0x00, 0x11, 0x22)), "jpg")
        val item = r.item("jpeg.trailing")
        assertEquals(CheckStatus.WARN, item.status)
        assertEquals(base.size.toLong(), item.offset)
    }

    @Test
    fun `secondary jpeg after EOI is recognized as info`() {
        val r = checkBytes(jpegBytes(trailing = jpegBytes()), "jpg")
        assertEquals(CheckStatus.INFO, r.item("jpeg.trailing").status)
        assertEquals(CheckStatus.PASS, r.overall, r.items.toString())
    }

    @Test
    fun `huffman jpeg without DHT warns, arithmetic jpeg skips`() {
        assertEquals(CheckStatus.WARN, checkBytes(jpegBytes(withDht = false), "jpg").item("jpeg.dht").status)
        assertEquals(CheckStatus.SKIP, checkBytes(jpegBytes(withDht = false, sofMarker = 0xC9), "jpg").item("jpeg.dht").status)
    }

    @Test
    fun `unrecognized format yields a single skip item`() {
        val r = checkBytes("not an image at all".toByteArray(), "jpg")
        assertEquals("UNKNOWN", r.format)
        assertEquals(CheckStatus.SKIP, r.item("format").status)
    }

    @Test
    fun `lossless jpeg without DQT skips the DQT check`() {
        listOf(0xC3, 0xC7, 0xCB, 0xCF).forEach { sof ->
            val item = checkBytes(jpegBytes(withDqt = false, sofMarker = sof), "jpg").item("jpeg.dqt")
            assertEquals(CheckStatus.SKIP, item.status, "SOF 0x%02X".format(sof))
        }
    }

    @Test
    fun `baseline jpeg without DQT still fails`() {
        assertEquals(CheckStatus.FAIL, checkBytes(jpegBytes(withDqt = false), "jpg").item("jpeg.dqt").status)
    }

    /** Real JPEG followed by [extra] padding bytes, with the tree's post-EOI nodes replaced by [trailing]. */
    private fun trailingDetail(vararg trailing: BoxNode): String {
        val base = jpegBytes()
        val f = File.createTempFile("integrity-trailing-", ".jpg").apply { deleteOnExit(); writeBytes(base + ByteArray(64)) }
        val root = parseFile(f)
        val eoi = root.children.indexOfFirst { it.type == "EOI" }
        val tree = root.copy(children = root.children.subList(0, eoi + 1) + trailing.toList())
        val item = ImageIntegrityChecker.check(f, tree).item("jpeg.trailing")
        assertEquals(CheckStatus.INFO, item.status, item.detail)
        return item.detail
    }

    private val hint = "video integrity"

    @Test
    fun `embedded motion photo video after EOI gets the video integrity hint`() {
        val off = jpegBytes().size.toLong()
        assertTrue(trailingDetail(BoxNode("EmbeddedVideoData", off, 0, 64)).contains(hint))
    }

    @Test
    fun `SEF trailer with MotionPhoto_Data gets the video integrity hint`() {
        val off = jpegBytes().size.toLong()
        val sefd = BoxNode("sefd", off, 0, 64, children = listOf(BoxNode("MotionPhoto_Data", off, 0, 12)))
        assertTrue(trailingDetail(sefd).contains(hint))
    }

    @Test
    fun `plain SEF trailer without MotionPhoto_Data gets no video integrity hint`() {
        val off = jpegBytes().size.toLong()
        val sefd = BoxNode("sefd", off, 0, 64, children = listOf(BoxNode("Image_UTC_Data", off, 0, 13)))
        val detail = trailingDetail(sefd)
        assertTrue(detail.contains("Samsung SEF trailer"), detail)
        assertFalse(detail.contains(hint), detail)
    }
}
