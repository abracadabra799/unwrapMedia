package com.multiviewer.parser.conformance.heif

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.conformance.isobmff.IsoBmffBoxTree
import com.multiviewer.parser.conformance.isobmff.be16
import com.multiviewer.parser.conformance.isobmff.be32
import com.multiviewer.parser.conformance.isobmff.box
import com.multiviewer.parser.conformance.isobmff.fullBox
import com.multiviewer.parser.fileOf
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeifItemModelTest {
    private fun model(bytes: ByteArray): HeifModel = model(fileOf(bytes))
    private fun model(file: File): HeifModel =
        ByteReader.open(file).use { HeifItemModel.read(IsoBmffBoxTree.walk(it), it) }

    @Test
    fun `default spec reads one item with location, properties and associations`() {
        val bytes = buildHeif()
        val m = model(bytes)
        assertEquals(emptyList(), m.problems)
        assertNotNull(m.meta)
        assertEquals(1, m.metaCount)
        assertEquals("pict", m.handler)
        assertEquals(1L, m.primaryId)
        assertEquals(1, m.items.size)
        val item = m.items.single()
        assertEquals(1L, item.id); assertEquals("hvc1", item.type); assertEquals(2, item.infeVersion)
        assertEquals(false, item.hidden)
        assertEquals("infe", item.infe.type)
        assertEquals(1L, m.iinfEntryCount)
        assertEquals(1, m.ilocVersion)
        val loc = m.locations.single()
        assertEquals(1L, loc.itemId); assertEquals(0, loc.method); assertEquals(0, loc.dataReferenceIndex)
        assertEquals(0L, loc.baseOffset)
        val ext = loc.extents.single()
        assertEquals(16L, ext.length)
        assertContentEquals(ByteArray(16) { 5 }, bytes.copyOfRange(ext.offset.toInt(), (ext.offset + ext.length).toInt()))
        assertEquals(listOf(1 to "hvcC", 2 to "ispe"), m.properties.map { it.index to it.type })
        assertEquals(listOf(HeifAssociation(1, 1, true), HeifAssociation(1, 2, false)), m.associations)
        assertEquals(listOf(1L), m.ipmaOrder)
        assertEquals(emptyList(), m.references)
        assertNull(m.idat)
    }

    @Test
    fun `grid spec reads idat, idat-relative location and dimg reference`() {
        val m = model(buildHeif(HeifSpec(
            items = listOf(HeifItem(1, "hvc1", hidden = true), HeifItem(2, "hvc1", hidden = true),
                HeifItem(10, "grid", data = gridData(1, 2, 128, 48), method = 1)),
            primary = 10,
            refs = listOf(Triple("dimg", 10L, listOf(1L, 2L))),
        )))
        assertEquals(emptyList(), m.problems)
        assertEquals(10L, m.primaryId)
        assertEquals(listOf(true, true, false), m.items.map { it.hidden })
        assertNotNull(m.idat)
        val grid = m.locations.single { it.itemId == 10L }
        assertEquals(1, grid.method)
        assertEquals(listOf(HeifExtent(0, 8)), grid.extents)
        val ref = m.references.single()
        assertEquals("dimg", ref.type); assertEquals(10L, ref.from); assertEquals(listOf(1L, 2L), ref.to)
    }

    @Test
    fun `ipma with 16-bit associations is parsed`() {
        val ipma = fullBox("ipma", 0, 1, be32(1), be16(1), byteArrayOf(2), be16(0x8001), be16(0x0002))
        val iprp = box("iprp", box("ipco", hvcC(), ispe(64, 48)), ipma)
        val m = model(buildHeif(HeifSpec(dropMetaChild = "iprp", extraMetaChildren = listOf(iprp))))
        assertEquals(emptyList(), m.problems)
        assertEquals(listOf(HeifAssociation(1, 1, true), HeifAssociation(1, 2, false)), m.associations)
    }

    @Test
    fun `ipma version 1 uses 32-bit item ids`() {
        val ipma = fullBox("ipma", 1, 0, be32(1), be32(70000), byteArrayOf(1), byteArrayOf(0x81.toByte()))
        val iprp = box("iprp", box("ipco", hvcC()), ipma)
        val m = model(buildHeif(HeifSpec(dropMetaChild = "iprp", extraMetaChildren = listOf(iprp))))
        assertEquals(listOf(HeifAssociation(70000, 1, true)), m.associations)
    }

    @Test
    fun `iloc version 0 entries are parsed`() {
        val bytes = buildHeif(HeifSpec(ilocVersion = 0))
        val m = model(bytes)
        assertEquals(emptyList(), m.problems)
        assertEquals(0, m.ilocVersion)
        val loc = m.locations.single()
        assertEquals(0, loc.method)
        val ext = loc.extents.single()
        assertContentEquals(ByteArray(16) { 5 }, bytes.copyOfRange(ext.offset.toInt(), (ext.offset + ext.length).toInt()))
    }

    @Test
    fun `truncated ipma records a problem without throwing`() {
        val ipma = fullBox("ipma", 0, 0, be32(5), be16(1), byteArrayOf(1), byteArrayOf(0x81.toByte()))
        val iprp = box("iprp", box("ipco", hvcC()), ipma)
        val m = model(buildHeif(HeifSpec(dropMetaChild = "iprp", extraMetaChildren = listOf(iprp))))
        assertTrue(m.problems.any { "ipma" in it }, m.problems.toString())
        assertEquals(listOf(HeifAssociation(1, 1, true)), m.associations)
    }

    @Test
    fun `truncated iloc records a problem without throwing`() {
        val badIloc = fullBox("iloc", 1, 0, byteArrayOf(0x44, 0x00), be16(3), be16(1))
        val m = model(buildHeif(HeifSpec(dropMetaChild = "iloc", extraMetaChildren = listOf(badIloc))))
        assertTrue(m.problems.any { "iloc" in it }, m.problems.toString())
        assertEquals(emptyList(), m.locations)
    }

    @Test
    fun `truncated infe and miscounted iinf record problems without throwing`() {
        val badIinf = fullBox("iinf", 0, 0, be16(4), fullBox("infe", 2, 0, be16(1)))
        val m = model(buildHeif(HeifSpec(dropMetaChild = "iinf", extraMetaChildren = listOf(badIinf))))
        assertTrue(m.problems.any { "infe" in it }, m.problems.toString())
        assertTrue(m.problems.any { "iinf" in it && "entry_count" in it }, m.problems.toString())
        assertEquals(4L, m.iinfEntryCount)
        assertTrue(m.items.isEmpty())
    }

    @Test
    fun `truncated iref child records a problem without throwing`() {
        val badIref = fullBox("iref", 0, 0, box("dimg", be16(10), be16(5), be16(1)))
        val m = model(buildHeif(HeifSpec(extraMetaChildren = listOf(badIref))))
        assertTrue(m.problems.any { "dimg" in it }, m.problems.toString())
        assertTrue(m.references.isEmpty())
    }

    @Test
    fun `iref version 1 uses 32-bit item ids`() {
        val iref = fullBox("iref", 1, 0, box("thmb", be32(70000), be16(1), be32(1)))
        val m = model(buildHeif(HeifSpec(extraMetaChildren = listOf(iref))))
        assertEquals(emptyList(), m.problems)
        assertEquals(listOf(Triple("thmb", 70000L, listOf(1L))), m.references.map { Triple(it.type, it.from, it.to) })
    }

    @Test
    fun `infe version 3 uses a 32-bit item id`() {
        val iinf = fullBox("iinf", 1, 0, be32(1), fullBox("infe", 3, 1, be32(70000), be16(0), "av01".toByteArray(), byteArrayOf(0)))
        val m = model(buildHeif(HeifSpec(dropMetaChild = "iinf", extraMetaChildren = listOf(iinf))))
        assertEquals(emptyList(), m.problems)
        val item = m.items.single()
        assertEquals(70000L, item.id); assertEquals("av01", item.type); assertEquals(3, item.infeVersion); assertTrue(item.hidden)
    }

    @Test
    fun `unsupported iloc field sizes record a problem`() {
        val badIloc = fullBox("iloc", 1, 0, byteArrayOf(0x33, 0x00), be16(0))
        val m = model(buildHeif(HeifSpec(dropMetaChild = "iloc", extraMetaChildren = listOf(badIloc))))
        assertTrue(m.problems.any { "iloc" in it }, m.problems.toString())
        assertEquals(emptyList(), m.locations)
    }

    @Test
    fun `file without meta yields empty model`() {
        val m = model(box("ftyp", "heic".toByteArray(), be32(0)))
        assertNull(m.meta); assertEquals(0, m.metaCount); assertNull(m.handler); assertNull(m.primaryId)
        assertTrue(m.items.isEmpty())
        assertNull(m.ilocVersion); assertNull(m.iinfEntryCount)
    }

    @Test
    fun `macOS sips HEIC reads one hvc1 item with hvcC and ispe`() {
        val sips = File("/usr/bin/sips")
        assumeTrue(sips.exists())
        val png = File("src/test/resources/format-sniff/a.png").takeIf { it.exists() }
            ?: File("app/src/test/resources/format-sniff/a.png")
        assumeTrue(png.exists())
        val dir = Files.createTempDirectory("heif-sips").toFile()
        try {
            val out = File(dir, "out.heic")
            val p = ProcessBuilder(sips.path, "-s", "format", "heic", png.absolutePath, "--out", out.absolutePath)
                .redirectErrorStream(true).redirectOutput(File(dir, "log.txt")).start()
            assumeTrue(p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0 && out.length() > 0)
            val m = model(out)
            assertEquals(emptyList(), m.problems)
            val hvc = m.items.filter { it.type == "hvc1" }
            assertEquals(1, hvc.size)
            val props = m.associations.filter { it.itemId == hvc.single().id }
                .mapNotNull { a -> m.properties.firstOrNull { it.index == a.propertyIndex }?.type }
            assertTrue("hvcC" in props && "ispe" in props, props.toString())
        } finally {
            dir.deleteRecursively()
        }
    }
}
