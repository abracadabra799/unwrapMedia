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
}
