package com.multiviewer.parser.integrity

import kotlin.test.Test
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
}
