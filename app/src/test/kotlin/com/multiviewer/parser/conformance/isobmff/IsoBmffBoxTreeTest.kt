package com.multiviewer.parser.conformance.isobmff

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.fileOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IsoBmffBoxTreeTest {
    private fun walk(bytes: ByteArray): BmffTree = ByteReader.open(fileOf(bytes)).use { IsoBmffBoxTree.walk(it) }

    private fun all(boxes: List<BmffBox>): List<BmffBox> = boxes.flatMap { listOf(it) + all(it.children) }

    @Test
    fun `valid movie walks to ftyp mdat moov with nested children and no problems`() {
        val tree = walk(buildMovie())
        assertEquals(listOf("ftyp", "mdat", "moov"), tree.top.map { it.type })
        val moov = tree.top.last()
        val trak = moov.children.first { it.type == "trak" }
        assertEquals("moov", trak.parent)
        val mdia = trak.children.first { it.type == "mdia" }
        val minf = mdia.children.first { it.type == "minf" }
        val stbl = minf.children.first { it.type == "stbl" }
        assertTrue(stbl.children.any { it.type == "stsz" })
        assertTrue(all(tree.top).all { it.problem == null })
        assertEquals(0L, tree.trailingBytes)
        assertNull(tree.trailingOffset)
    }

    @Test
    fun `child overrunning its parent gets a problem and stops that level`() {
        val bad = be32(1000) + "free".toByteArray() + ByteArray(4)
        val after = box("skip")
        val moov = box("moov", bad, after)
        val tree = walk(moov)
        val kids = tree.top.single().children
        assertEquals(1, kids.size)
        assertNotNull(kids[0].problem)
        assertEquals("free", kids[0].type)
    }

    @Test
    fun `top level size zero extends to end of file`() {
        val tree = walk(box("ftyp", "isom".toByteArray(), be32(0)) + be32(0) + "mdat".toByteArray() + ByteArray(30))
        val mdat = tree.top.last()
        assertEquals(0L, mdat.rawSize)
        assertEquals(38L, mdat.size)
        assertNull(mdat.problem)
        assertEquals(0L, tree.trailingBytes)
    }

    @Test
    fun `largesize mdat is read with header size 16`() {
        val payload = ByteArray(10)
        val bytes = be32(1) + "mdat".toByteArray() + be32(0) + be32(16L + payload.size) + payload
        val mdat = walk(bytes).top.single()
        assertEquals(16, mdat.headerSize)
        assertEquals(26L, mdat.size)
        assertEquals(1L, mdat.rawSize)
        assertNull(mdat.problem)
    }

    @Test
    fun `non box bytes after moov are counted as trailing`() {
        val movie = buildMovie(MovieSpec(trailing = ByteArray(20) { 0x01 }))
        val tree = walk(movie)
        assertEquals(20L, tree.trailingBytes)
        assertEquals(tree.top.last().end, tree.trailingOffset)
        assertEquals(movie.size.toLong() - 20, tree.trailingOffset)
    }

    @Test
    fun `meta lists hdlr as child for both ISO full box and QuickTime plain forms`() {
        val hdlr = fullBox("hdlr", 0, 0, ByteArray(20))
        val iso = walk(box("meta", ByteArray(4), hdlr)).top.single()
        assertEquals(listOf("hdlr"), iso.children.map { it.type })
        val qt = walk(box("meta", hdlr)).top.single()
        assertEquals(listOf("hdlr"), qt.children.map { it.type })
    }

    @Test
    fun `largesize near Long MAX is a problem with sane trailing`() {
        val ftyp = box("ftyp", "isom".toByteArray(), be32(0))
        val bad = be32(1) + "mdat".toByteArray() + be32(0x7FFFFFFFL) + be32(0xFFFFFFFFL) + ByteArray(8)
        val tree = walk(ftyp + bad)
        val mdat = tree.top.last()
        assertNotNull(mdat.problem)
        assertEquals(0L, tree.trailingBytes)
        assertNull(tree.trailingOffset)
    }

    @Test
    fun `fewer than 8 bytes after last box are trailing`() {
        val ftyp = box("ftyp", "isom".toByteArray(), be32(0))
        val tree = walk(ftyp + ByteArray(5))
        assertEquals(1, tree.top.size)
        assertEquals(5L, tree.trailingBytes)
        assertEquals(ftyp.size.toLong(), tree.trailingOffset)
    }

    @Test
    fun `largesize header cut at EOF is a problem`() {
        val tree = walk(be32(1) + "mdat".toByteArray() + ByteArray(4))
        assertNotNull(tree.top.single().problem)
        assertEquals(0L, tree.trailingBytes)
    }

    @Test
    fun `non printable type stops the level`() {
        val ftyp = box("ftyp", "isom".toByteArray(), be32(0))
        val junk = be32(16) + byteArrayOf(0, 1, 2, 3) + ByteArray(8)
        val tree = walk(ftyp + junk + box("free"))
        assertEquals(listOf("ftyp"), tree.top.map { it.type })
        assertEquals((junk.size + 8).toLong(), tree.trailingBytes)
    }

    @Test
    fun `nested size zero box ends at parent end`() {
        val moov = box("moov", box("free"), be32(0) + "skip".toByteArray() + ByteArray(5))
        val m = walk(moov).top.single()
        val skip = m.children.last()
        assertEquals("skip", skip.type)
        assertEquals(0L, skip.rawSize)
        assertEquals(m.end, skip.end)
        assertNull(skip.problem)
    }

    @Test
    fun `nesting deeper than the cap does not throw`() {
        var b = box("free")
        repeat(20) { b = box("moov", b) }
        val tree = walk(b)
        var depth = 0
        var cur: BmffBox? = tree.top.single()
        while (cur != null) { depth++; cur = cur.children.firstOrNull { it.type == "moov" } }
        assertTrue(depth in 12..14)
    }

    private fun stco0(bytes: ByteArray): Long {
        val tree = walk(bytes)
        val stco = all(tree.top).first { it.type == "stco" }
        return ByteReader.open(fileOf(bytes)).use { it.readUInt32(stco.payload + 8) }
    }

    @Test
    fun `builder stco points at first mdat payload byte`() {
        for (spec in listOf(MovieSpec(), MovieSpec(extraTopLevelBefore = listOf(box("free"))))) {
            val bytes = buildMovie(spec)
            val off = stco0(bytes).toInt()
            assertEquals(7, bytes[off].toInt())
            assertEquals(0x6D.toByte(), bytes[off - 4]) // 'm' of "mdat"
        }
    }

    @Test
    fun `non box bytes inside a container are recorded as a leftover span`() {
        val garbage = byteArrayOf(0, 0, 0, 16, 0, 1, 2, 3) + ByteArray(8)
        val moov = box("moov", box("mvhd", ByteArray(4)), garbage)
        val tree = walk(moov)
        val left = tree.leftovers.single()
        assertEquals("moov", left.container)
        assertEquals(0L, left.containerOffset)
        assertEquals(20L, left.offset)
        assertEquals(16L, left.bytes)
        assertEquals(1, tree.top.single().children.size)
    }

    @Test
    fun `no leftovers inside meta and none when a level ends on a problem box`() {
        val meta = box("meta", ByteArray(4), box("hdlr", ByteArray(8)), ByteArray(12) { 0x0E })
        assertTrue(walk(box("moov", meta)).leftovers.isEmpty())
        val bad = be32(1000) + "free".toByteArray() + ByteArray(4)
        assertTrue(walk(box("moov", bad)).leftovers.isEmpty())
    }
}
