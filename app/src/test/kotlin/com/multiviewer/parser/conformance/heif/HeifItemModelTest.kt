package com.multiviewer.parser.conformance.heif

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.conformance.isobmff.IsoBmffBoxTree
import com.multiviewer.parser.conformance.isobmff.be16
import com.multiviewer.parser.conformance.isobmff.be32
import com.multiviewer.parser.conformance.isobmff.box
import com.multiviewer.parser.conformance.isobmff.fullBox
import com.multiviewer.parser.fileOf
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Timeout
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        assertTrue(m.problems.any { it.startsWith("infe@") }, m.problems.toString())
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

    private fun be64(v: Long) = be32(v ushr 32) + be32(v and 0xFFFFFFFFL)
    private fun iloc(version: Int, sizes: Int, baseIndex: Int, count: ByteArray, vararg entries: ByteArray) =
        fullBox("iloc", version, 0, byteArrayOf(sizes.toByte(), baseIndex.toByte()), count, *entries)
    private fun withIloc(iloc: ByteArray) = model(buildHeif(HeifSpec(dropMetaChild = "iloc", extraMetaChildren = listOf(iloc))))

    @Test
    @Timeout(10)
    fun `zero-width extents are capped across items`() {
        val entry = be32(1) + be16(0) + be16(0) + be16(65535)   // v2, all field sizes 0: each extent is 0 bytes
        val m = withIloc(iloc(2, 0x00, 0x00, be32(100), *Array(100) { entry }))
        assertTrue(m.problems.any { HEIF_COUNT_CAP_PROBLEM in it && it.startsWith("iloc@") }, m.problems.toString())
        assertTrue(m.locations.sumOf { it.extents.size }.toLong() <= HEIF_MAX_ENTRIES)
    }

    @Test
    fun `8-byte extent offset with the top bit set is rejected and only that location skipped`() {
        val bad = be16(1) + be16(0) + be16(0) + be16(1) + be64(Long.MIN_VALUE) + be64(16)
        val good = be16(2) + be16(0) + be16(0) + be16(1) + be64(100) + be64(16)
        val m = withIloc(iloc(1, 0x88, 0x00, be16(2), bad, good))
        assertTrue(m.problems.any { it.startsWith("iloc@") && "항목 1" in it }, m.problems.toString())
        assertEquals(listOf(2L), m.locations.map { it.itemId })
        assertEquals(listOf(HeifExtent(100, 16)), m.locations.single().extents)
    }

    @Test
    fun `base_offset plus extent_offset overflow is rejected`() {
        val bad = be16(1) + be16(0) + be16(0) + be64(Long.MAX_VALUE - 15) + be16(1) + be64(0x20) + be64(16)
        val m = withIloc(iloc(1, 0x88, 0x80, be16(1), bad))
        assertTrue(m.problems.any { it.startsWith("iloc@") }, m.problems.toString())
        assertTrue(m.locations.isEmpty())
    }

    @Test
    fun `8-byte extent length with the top bit set is rejected`() {
        val bad = be16(1) + be16(0) + be16(0) + be16(1) + be64(100) + be64(-1L)
        val m = withIloc(iloc(1, 0x88, 0x00, be16(1), bad))
        assertTrue(m.problems.any { it.startsWith("iloc@") }, m.problems.toString())
        assertTrue(m.locations.isEmpty())
    }

    @Test
    fun `8-byte fields parse`() {
        val e = be16(1) + be16(0) + be16(0) + be64(0x1_0000_0000L) + be16(1) + be64(0x10) + be64(0x2_0000_0000L)
        val m = withIloc(iloc(1, 0x88, 0x80, be16(1), e))
        assertEquals(emptyList(), m.problems)
        val loc = m.locations.single()
        assertEquals(0x1_0000_0000L, loc.baseOffset)
        assertEquals(listOf(HeifExtent(0x1_0000_0010L, 0x2_0000_0000L)), loc.extents)
    }

    @Test
    fun `iloc version 2 uses 32-bit item ids and count`() {
        val e = be32(70000) + be16(1) + be16(0) + be16(1) + be32(4) + be32(8)
        val m = withIloc(iloc(2, 0x44, 0x00, be32(1), e))
        assertEquals(emptyList(), m.problems)
        assertEquals(2, m.ilocVersion)
        val loc = m.locations.single()
        assertEquals(70000L, loc.itemId); assertEquals(1, loc.method)
        assertEquals(listOf(HeifExtent(4, 8)), loc.extents)
    }

    @Test
    fun `index_size above zero is skipped per extent`() {
        val e = be16(1) + be16(0) + be16(0) + be16(2) + be32(9) + be32(100) + be32(10) + be32(9) + be32(200) + be32(20)
        val m = withIloc(iloc(1, 0x44, 0x04, be16(1), e))
        assertEquals(emptyList(), m.problems)
        assertEquals(listOf(HeifExtent(100, 10), HeifExtent(200, 20)), m.locations.single().extents)
    }

    @Test
    fun `base_offset is added to extent offsets`() {
        val e = be16(1) + be16(0) + be16(0) + be32(1000) + be16(1) + be32(24) + be32(16)
        val m = withIloc(iloc(1, 0x44, 0x40, be16(1), e))
        assertEquals(emptyList(), m.problems)
        val loc = m.locations.single()
        assertEquals(1000L, loc.baseOffset)
        assertEquals(listOf(HeifExtent(1024, 16)), loc.extents)
    }

    @Test
    fun `iloc version is known even when the rest is truncated`() {
        val m = withIloc(fullBox("iloc", 1, 0))
        assertEquals(1, m.ilocVersion)
        assertTrue(m.problems.any { it.startsWith("iloc@") }, m.problems.toString())
    }

    @Test
    fun `iloc item count above the cap is reported`() {
        val m = withIloc(iloc(2, 0x44, 0x00, be32(2_000_000)))
        assertTrue(m.problems.any { HEIF_COUNT_CAP_PROBLEM in it && it.startsWith("iloc@") }, m.problems.toString())
    }

    @Test
    fun `iinf and ipma counts above the cap are reported`() {
        val iinf = fullBox("iinf", 1, 0, be32(2_000_000))
        val iprp = box("iprp", box("ipco", hvcC()), fullBox("ipma", 0, 0, be32(2_000_000)))
        val m = model(buildHeif(HeifSpec(dropMetaChild = "iinf", extraMetaChildren = listOf(iinf))))
        assertTrue(m.problems.any { HEIF_COUNT_CAP_PROBLEM in it && it.startsWith("iinf@") }, m.problems.toString())
        val m2 = model(buildHeif(HeifSpec(dropMetaChild = "iprp", extraMetaChildren = listOf(iprp))))
        assertTrue(m2.problems.any { HEIF_COUNT_CAP_PROBLEM in it && it.startsWith("ipma@") }, m2.problems.toString())
    }

    @Test
    fun `pitm version 1 uses a 32-bit item id`() {
        val m = model(buildHeif(HeifSpec(dropMetaChild = "pitm", extraMetaChildren = listOf(fullBox("pitm", 1, 0, be32(70000))))))
        assertEquals(70000L, m.primaryId)
    }

    @Test
    fun `duplicate unique meta children are reported and the first is used`() {
        val hdlr2 = fullBox("hdlr", 0, 0, be32(0), "vide".toByteArray(), ByteArray(12), byteArrayOf(0))
        val m = model(buildHeif(HeifSpec(extraMetaChildren = listOf(hdlr2, fullBox("pitm", 0, 0, be16(7))))))
        assertEquals("pict", m.handler)
        assertEquals(1L, m.primaryId)
        assertTrue(m.problems.any { "hdlr 박스가 2개" in it }, m.problems.toString())
        assertTrue(m.problems.any { "pitm 박스가 2개" in it }, m.problems.toString())
    }

    @Test
    fun `non-infe child in iinf is reported`() {
        val iinf = fullBox("iinf", 0, 0, be16(1), fullBox("infe", 2, 0, be16(1), be16(0), "hvc1".toByteArray(), byteArrayOf(0)), box("free"))
        val m = model(buildHeif(HeifSpec(dropMetaChild = "iinf", extraMetaChildren = listOf(iinf))))
        assertEquals(1, m.items.size)
        assertTrue(m.problems.any { it.startsWith("iinf@") && "free" in it }, m.problems.toString())
    }

    @Test
    fun `trailing bytes after infe fields are reported`() {
        val iinf = fullBox("iinf", 0, 0, be16(1), fullBox("infe", 2, 0, be16(1), be16(0), "hvc1".toByteArray(), byteArrayOf(0), byteArrayOf(9, 9)))
        val m = model(buildHeif(HeifSpec(dropMetaChild = "iinf", extraMetaChildren = listOf(iinf))))
        assertEquals(1, m.items.size)
        assertTrue(m.problems.any { it.startsWith("infe@") }, m.problems.toString())
    }

    @Test
    fun `mime infe with content type has no problems`() {
        val iinf = fullBox("iinf", 0, 0, be16(1), fullBox("infe", 2, 0, be16(1), be16(0), "mime".toByteArray(),
            "XMP\u0000application/rdf+xml\u0000".toByteArray()))
        val m = model(buildHeif(HeifSpec(dropMetaChild = "iinf", extraMetaChildren = listOf(iinf))))
        assertEquals(emptyList(), m.problems)
        assertEquals("mime", m.items.single().type)
    }

    @Test
    fun `trailing bytes after iref to-ids are reported`() {
        val iref = fullBox("iref", 0, 0, box("dimg", be16(10), be16(1), be16(1), byteArrayOf(9)))
        val m = model(buildHeif(HeifSpec(extraMetaChildren = listOf(iref))))
        assertEquals(1, m.references.size)
        assertTrue(m.problems.any { "dimg" in it }, m.problems.toString())
    }

    @Test
    fun `builder writes 32-bit item id for infe version 3`() {
        val m = model(buildHeif(HeifSpec(items = listOf(HeifItem(5, "hvc1", infeVersion = 3)), primary = 5,
            assoc = listOf(5L to listOf(1 to true)))))
        assertEquals(emptyList(), m.problems)
        assertEquals(5L, m.items.single().id); assertEquals(3, m.items.single().infeVersion)
    }

    @Test
    fun `builder rejects unsupported infe versions and wide ipma indices`() {
        assertFailsWith<IllegalArgumentException> { buildHeif(HeifSpec(items = listOf(HeifItem(1, "hvc1", infeVersion = 4)))) }
        assertFailsWith<IllegalArgumentException> { buildHeif(HeifSpec(assoc = listOf(1L to listOf(128 to false)))) }
    }
}
