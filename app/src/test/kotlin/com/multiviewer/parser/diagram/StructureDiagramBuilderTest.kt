package com.multiviewer.parser.diagram

import com.multiviewer.parser.BoxField
import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.EmbeddedVideo
import com.multiviewer.parser.findEmbeddedVideo
import com.multiviewer.parser.parseFile
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StructureDiagramBuilderTest {
    private fun node(type: String, offset: Long, size: Long, children: List<BoxNode> = emptyList(), headerSize: Int = 8, fields: List<BoxField> = emptyList()) =
        BoxNode(type, offset, headerSize, size, children, fields)

    private fun StructureDiagram.at(offset: Long, depth: Int = 0) =
        regions.single { it.offset == offset && it.depth == depth }

    @Test
    fun `real Samsung motion photo is mapped from parsed ranges with a SEF-backed video link`() {
        // Independently dumped (python, raw bytes): EOI marker at 237742, SEF "MotionPhoto_Data" record at
        // 237744 (marker 00 00 30 0a, name length 16), MP4 ftyp box at 237768, mdat ends 1041811,
        // SEFH at 1041811, SEFT at 1041839, file size 1041843.
        val file = File("../docs/showcase-media/images/campfire-motion-photo.jpg")
        assertEquals(1_041_843L, file.length())
        val root = parseFile(file)
        val video = ByteReader.open(file).use { findEmbeddedVideo(root, it) }
        val diagram = buildStructureDiagram(root, file.length(), video)

        assertEquals(1_041_843L, diagram.fileSize)
        val top = diagram.regions.filter { it.depth == 0 }
        assertEquals(0L, top.first().offset)
        assertEquals(1_041_843L, top.last().offset + top.last().length)
        assertTrue(top.none { it.status == RegionStatus.UNPARSED }, top.toString())
        assertEquals(2L, diagram.at(237_742).length) // EOI
        val sefd = diagram.at(237_744)
        assertEquals("sefd", sefd.label)
        assertEquals(804_099L, sefd.length)
        val record = diagram.at(237_744, depth = 1)
        assertEquals("MotionPhoto_Data", record.label)
        assertEquals(804_067L, record.length)
        val tail = diagram.at(1_041_811, depth = 1)
        assertEquals(RegionStatus.UNPARSED, tail.status)
        assertEquals(32L, tail.length)

        val overlay = diagram.overlays.single()
        assertEquals(237_768L, overlay.offset)
        assertEquals(1_041_811L - 237_768L, overlay.length)
        val link = diagram.links.single()
        assertEquals(sefd.id, link.fromId)
        assertEquals(overlay.id, link.toId)
        assertEquals(LinkMeaning.SEF_EMBEDDED_VIDEO, link.meaning)
        assertTrue(diagram.warnings.isEmpty(), diagram.warnings.toString())
    }

    @Test
    fun `gaps and an unparsed tail are labeled instead of drawn as contiguous`() {
        val root = node("root", 0, 100, listOf(node("a", 0, 10), node("b", 20, 10)), headerSize = 0)
        val d = buildStructureDiagram(root, 100, null)
        val top = d.regions.filter { it.depth == 0 }
        assertEquals(listOf(0L to 10L, 10L to 10L, 20L to 10L, 30L to 70L), top.map { it.offset to it.length })
        assertEquals(listOf(RegionStatus.PARSED, RegionStatus.UNPARSED, RegionStatus.PARSED, RegionStatus.UNPARSED), top.map { it.status })
    }

    @Test
    fun `overlapping, out-of-order and past-EOF children warn rather than map contiguously`() {
        val root = node("root", 0, 100, listOf(node("b", 5, 10), node("a", 0, 10), node("c", 90, 30)), headerSize = 0)
        val d = buildStructureDiagram(root, 100, null)
        val top = d.regions.filter { it.depth == 0 && it.status != RegionStatus.UNPARSED }
        assertEquals(listOf("a", "b", "c"), top.map { it.label })
        assertEquals(RegionStatus.WARNING, top[0].status)
        assertEquals(RegionStatus.WARNING, top[1].status)
        assertEquals(RegionStatus.WARNING, top[2].status)
        assertTrue(d.warnings.any { "overlap" in it.lowercase() }, d.warnings.toString())
        assertTrue(d.warnings.any { "end of file" in it.lowercase() }, d.warnings.toString())
    }

    @Test
    fun `an unverified embedded video range draws no link`() {
        val root = node("root", 0, 100, listOf(node("a", 0, 100)), headerSize = 0)
        for (video in listOf(EmbeddedVideo(50, 150, "mp4"), EmbeddedVideo(60, 60, "mp4"), EmbeddedVideo(-1, 10, "mp4"))) {
            val d = buildStructureDiagram(root, 100, video)
            assertTrue(d.links.isEmpty() && d.overlays.isEmpty(), d.toString())
            assertTrue(d.warnings.any { "embedded video" in it.lowercase() }, d.warnings.toString())
        }
    }

    @Test
    fun `an XMP motion photo links the declaring APP1 segment to the video bytes`() {
        val xmp = node("APP1", 2, 200, headerSize = 4, fields = listOf(BoxField("xmp", "<x GCamera:MotionPhoto=\"1\"/>", 6, 196)))
        val root = node("root", 0, 1_000, listOf(
            node("SOI", 0, 2, headerSize = 2), xmp, node("EOI", 202, 2, headerSize = 2), node("ftyp", 204, 24),
        ), headerSize = 0)
        val d = buildStructureDiagram(root, 1_000, EmbeddedVideo(204, 1_000, "mp4"))
        val link = d.links.single()
        assertEquals(d.at(2).id, link.fromId)
        assertEquals(LinkMeaning.XMP_EMBEDDED_VIDEO, link.meaning)
        assertTrue(link.evidence.contains("204"), link.evidence)
    }

    @Test
    fun `an XMP-declared range without a parsed ftyp at its start is not drawn as verified`() {
        val xmp = node("APP1", 2, 200, headerSize = 4, fields = listOf(BoxField("xmp", "<x GCamera:MicroVideo=\"1\"/>", 6, 196)))
        val root = node("root", 0, 1_000, listOf(node("SOI", 0, 2, headerSize = 2), xmp, node("EOI", 202, 2, headerSize = 2)), headerSize = 0)
        val d = buildStructureDiagram(root, 1_000, EmbeddedVideo(300, 1_000, "mp4"))
        assertTrue(d.links.isEmpty() && d.overlays.isEmpty(), d.toString())
        assertTrue(d.warnings.any { "not verified" in it }, d.warnings.toString())
    }

    @Test
    fun `an XMP packet that says it is not a motion photo is no declaration`() {
        val xmp = node("APP1", 2, 200, headerSize = 4, fields = listOf(BoxField("xmp", "<x GCamera:MotionPhoto=\"0\"/>", 6, 196)))
        val root = node("root", 0, 1_000, listOf(xmp, node("ftyp", 204, 24)), headerSize = 0)
        val d = buildStructureDiagram(root, 1_000, EmbeddedVideo(204, 1_000, "mp4"))
        assertTrue(d.links.isEmpty(), d.links.toString())
        assertTrue(d.warnings.any { "relationship unknown" in it }, d.warnings.toString())
    }

    @Test
    fun `a summary that folds a warned node is not clean, and logical levels say so`() {
        val samples = (0 until 40).map { node("s", 8L + it * 4, 4, headerSize = 0) }.toMutableList()
        samples[35] = BoxNode("s", 8L + 35 * 4, 0, 4, warnings = listOf("bad"))
        val ifd = node("IFD0", 8, 4, listOf(node("Thumb", 50, 10, headerSize = 0)), headerSize = 0)
        val root = node("root", 0, 400, listOf(node("mdat", 0, 168, samples), node("APP1", 168, 232, listOf(ifd))), headerSize = 0)
        val d = buildStructureDiagram(root, 400, null)
        assertEquals(RegionStatus.WARNING, d.regions.single { it.summarizedCount > 0 }.status)
        assertTrue(d.notes.any { "IFD0" in it && "logical" in it }, d.notes.toString())
    }

    @Test
    fun `large child lists are summarized with counts`() {
        val samples = (0 until 1_000).map { node("sample", 8L + it * 4, 4, headerSize = 0) }
        val root = node("root", 0, 4_008, listOf(node("mdat", 0, 4_008, samples)), headerSize = 0)
        val d = buildStructureDiagram(root, 4_008, null)
        val nested = d.regions.filter { it.depth == 1 }
        assertTrue(nested.size <= MAX_REGIONS_PER_LEVEL + 1, nested.size.toString())
        val summary = assertNotNull(nested.singleOrNull { it.summarizedCount > 0 })
        assertEquals(1_000 - (MAX_REGIONS_PER_LEVEL - 1), summary.summarizedCount)
        assertTrue(d.regions.size < 100, d.regions.size.toString())
    }

    @Test
    fun `real MP4 top level matches an independent box walk`() {
        // python box walk: ftyp 0+32, moov 32+2016, free 2048+8, mdat 2056+738228, file 740284 bytes.
        val file = File("../docs/showcase-media/video/kitten-pounce.mp4")
        val d = buildStructureDiagram(parseFile(file), file.length(), null)
        val top = d.regions.filter { it.depth == 0 }
        assertEquals(
            listOf(Triple("ftyp", 0L, 32L), Triple("moov", 32L, 2016L), Triple("free", 2048L, 8L), Triple("mdat", 2056L, 738_228L)),
            top.map { Triple(it.label, it.offset, it.length) },
        )
        assertTrue(top.all { it.status == RegionStatus.PARSED }, top.toString())
        assertTrue(d.links.isEmpty() && d.overlays.isEmpty())
        assertTrue(d.regions.any { it.depth == 1 && it.parentId == top[1].id && it.label == "mvhd" })
    }
}
