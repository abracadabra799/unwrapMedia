package com.multiviewer.parser.integrity

import kotlin.test.Test
import kotlin.test.assertEquals

class PngIntegrityTest {
    @Test
    fun `valid png passes and declares 1x1`() {
        val r = checkBytes(pngBytes(), "png")
        assertEquals("PNG", r.format)
        listOf("png.ihdr", "png.crc", "png.idat", "png.iend", "png.trailing").forEach {
            assertEquals(CheckStatus.PASS, r.item(it).status, it)
        }
        assertEquals(CheckStatus.PASS, r.overall, r.items.toString())
        assertEquals(1, r.declaredWidth)
        assertEquals(1, r.declaredHeight)
    }

    @Test
    fun `critical chunk CRC mismatch fails at the IDAT offset`() {
        val bytes = pngBytes(corruptIdatCrc = true)
        val idatOffset = (PNG_SIG.size + 25 + 15).toLong() // IHDR chunk 25 bytes, tEXt chunk 12+3
        val crc = checkBytes(bytes, "png").item("png.crc")
        assertEquals(CheckStatus.FAIL, crc.status)
        assertEquals(idatOffset, crc.offset)
    }

    @Test
    fun `ancillary chunk CRC mismatch only warns`() {
        val r = checkBytes(pngBytes(corruptTextCrc = true), "png")
        assertEquals(CheckStatus.WARN, r.item("png.crc").status)
        assertEquals(CheckStatus.WARN, r.overall)
    }

    @Test
    fun `truncated png fails layout and IEND`() {
        val full = pngBytes()
        val r = checkBytes(full.copyOf(full.size - 8), "png")
        assertEquals(CheckStatus.FAIL, r.item("png.truncated").status)
        assertEquals(CheckStatus.FAIL, r.item("png.iend").status)
    }

    @Test
    fun `bytes after IEND warn`() {
        val r = checkBytes(pngBytes(trailing = byteArrayOf(1, 2, 3)), "png")
        assertEquals(CheckStatus.WARN, r.item("png.trailing").status)
        assertEquals(3L, r.item("png.trailing").length)
    }

    @Test
    fun `non-consecutive IDAT chunks fail`() {
        val ihdr = be32(1) + be32(1) + byteArrayOf(8, 0, 0, 0, 0)
        val idat = pngIdatPayload()
        val bytes = PNG_SIG + pngChunk("IHDR", ihdr) + pngChunk("IDAT", idat.copyOfRange(0, 4)) +
            pngChunk("tEXt", "k\u0000v".toByteArray(Charsets.ISO_8859_1)) +
            pngChunk("IDAT", idat.copyOfRange(4, idat.size)) + pngChunk("IEND", ByteArray(0))
        assertEquals(CheckStatus.FAIL, checkBytes(bytes, "png").item("png.idat").status)
    }
}
