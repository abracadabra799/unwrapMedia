package com.multiviewer.parser

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IsoSampleTableTest {
    private fun table(name: String): VideoSampleTable? {
        val file = File(javaClass.getResource("/hdr/$name")!!.toURI())
        val root = parseFile(file)
        return ByteReader.open(file).use { buildVideoSampleTable(it, root) }
    }

    @Test
    fun `HEVC fixture indexes 30 samples with stss sync flags`() {
        val t = assertNotNull(table("hevc_hdr10plus.mp4"))
        assertEquals(VideoCodec.HEVC, t.codec)
        assertEquals(4, t.nalLengthSize)
        assertEquals(15360L, t.timescale)
        assertEquals(30, t.samples.size)
        assertEquals(listOf(0, 10, 20), t.samples.filter { it.isSync }.map { it.index })
        assertEquals(44L, t.samples[0].offset)
        assertEquals(5823L, t.samples[0].size)
        assertEquals(5867L, t.samples[1].offset)
        assertTrue(t.warnings.isEmpty())
    }

    @Test
    fun `ctts offsets are read`() {
        val t = assertNotNull(table("hevc_box.mp4"))
        assertEquals(listOf(1024L, 2048L, 1024L, 0L), t.samples.take(4).map { it.compositionOffset })
        assertEquals(listOf(0L, 512L, 1024L, 1536L), t.samples.take(4).map { it.decodeTime })
    }

    @Test
    fun `H264 and AV1 codecs`() {
        val h264 = assertNotNull(table("h264_hdr.mp4"))
        assertEquals(VideoCodec.H264, h264.codec)
        assertEquals(4, h264.nalLengthSize)
        assertEquals(listOf(0, 5), h264.samples.filter { it.isSync }.map { it.index })
        val av1 = assertNotNull(table("av1_hdr.mp4"))
        assertEquals(VideoCodec.AV1, av1.codec)
        assertNull(av1.nalLengthSize)
        assertEquals(10, av1.samples.size)
    }

    @Test
    fun `non-video tree yields null`() {
        assertNull(buildVideoSampleTable(byteReaderOf(ByteArray(8)), BoxNode("root", 0, 0, 8)))
    }
}

class IsoSampleTableWarningTest {
    private val file = File(javaClass.getResource("/hdr/hevc_hdr10plus.mp4")!!.toURI())

    private fun BoxNode.mapTree(f: (BoxNode) -> BoxNode): BoxNode = f(copy(children = children.map { it.mapTree(f) }))

    @Test
    fun `stsz shorter than stts produces a disagreement warning`() {
        val root = parseFile(file).mapTree { n ->
            if (n.type == "stsz") n.copy(table = n.table!!.copy(entryCount = 20)) else n
        }
        val t = assertNotNull(ByteReader.open(file).use { buildVideoSampleTable(it, root) })
        assertEquals(20, t.samples.size)
        assertTrue(
            "HEVC sample tables disagree: stts=30, stsz=20, chunk offsets=20; indexed 20" in t.warnings,
            t.warnings.toString(),
        )
    }

    @Test
    fun `mvex box produces the fragmented MP4 warning`() {
        val root = parseFile(file).let { it.copy(children = it.children + BoxNode("mvex", 0, 8, 8)) }
        val t = assertNotNull(ByteReader.open(file).use { buildVideoSampleTable(it, root) })
        assertEquals(30, t.samples.size)
        assertEquals(
            listOf("Fragmented MP4 (moof) is not supported; only samples in the moov sample tables were scanned"),
            t.warnings,
        )
    }

    @Test
    fun `detectVideoCodec uses the first video trak sample entry`() {
        // an hvcC outside any trak (e.g. a HEIC item property) is not a video track
        val stray = BoxNode("root", 0, 0, 0, children = listOf(BoxNode("hvcC", 0, 8, 8)))
        assertNull(detectVideoCodec(stray))
        assertEquals(VideoCodec.HEVC, detectVideoCodec(parseFile(file)))
    }
}
