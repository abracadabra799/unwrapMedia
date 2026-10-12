package com.multiviewer.parser

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HdrMetadataScannerTest {
    private fun fixture(name: String) = File(javaClass.getResource("/hdr/$name")!!.toURI())
    private fun scan(name: String) = fixture(name).let { assertNotNull(scanHdrMetadata(it, parseFile(it))) }

    @Test
    fun `HEVC HDR10plus fixture end to end`() {
        val r = scan("hevc_hdr10plus.mp4")
        assertEquals(VideoCodec.HEVC, r.codec)
        assertEquals(30, r.records.size)
        // ffprobe reports frame 11's values on 12-14 (sticky side data); the parser must not.
        assertEquals(listOf(12, 13, 14), r.records.filter { it.hdr10Plus == null }.map { it.frameNumber })
        assertEquals(listOf(4000, 4500, 3700), r.records[0].hdr10Plus!!.windows[0].maxscl)
        assertEquals(1000.0, r.records[0].masteringDisplay!!.maxLuminance, 1e-9)
        assertEquals(listOf(0, 10, 20), r.records.filter { it.isSync }.map { it.frameNumber })

        val anomalies = detectHdrAnomalies(r.records, r.container)
        val missing = anomalies.single { it.rule == HdrRule.HDR10PLUS_MISSING }
        assertEquals(12 to 14, missing.firstFrame to missing.lastFrame)
        assertEquals(HdrSeverity.WARN, missing.severity)
        assertEquals(listOf(17, 18), anomalies.filter { it.rule == HdrRule.ABRUPT_CHANGE }.map { it.firstFrame })
        assertTrue(anomalies.none { it.rule == HdrRule.STATIC_DYNAMIC_MISMATCH })
        assertEquals(
            listOf(HdrSeverity.INFO, HdrSeverity.INFO),
            anomalies.filter { it.rule == HdrRule.CONTAINER_INBAND_MISMATCH }.map { it.severity },
        )
        assertEquals(HdrVerdict.WARN, hdrVerdict(anomalies))
    }

    @Test
    fun `B-frame file is in presentation order`() {
        val r = scan("hevc_box.mp4")
        assertTrue(r.records.zipWithNext().all { (a, b) -> a.ptsSeconds < b.ptsSeconds })
        assertTrue(r.records.map { it.sampleIndex } != r.records.indices.toList())
        assertEquals(1000.0, r.container.masteringDisplay!!.maxLuminance, 1e-9)
    }

    @Test
    fun `H264 and AV1 static metadata`() {
        val h264 = scan("h264_hdr.mp4")
        assertEquals(listOf(0, 5), h264.records.filter { it.contentLightLevel != null }.map { it.frameNumber })
        val av1 = scan("av1_hdr.mp4")
        assertEquals(VideoCodec.AV1, av1.codec)
        assertTrue(av1.records.first().masteringDisplay != null)
    }

    @Test
    fun `progress and cancellation`() {
        val file = fixture("hevc_hdr10plus.mp4")
        val root = parseFile(file)
        var lastDone = 0
        scanHdrMetadata(file, root, onProgress = { done, total -> lastDone = done; assertEquals(30, total) })
        assertEquals(30, lastDone)
        assertNull(scanHdrMetadata(file, root, isCancelled = { true }))
    }
}
