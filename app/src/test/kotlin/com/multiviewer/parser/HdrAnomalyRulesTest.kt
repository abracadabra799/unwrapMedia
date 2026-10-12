package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HdrAnomalyRulesTest {
    private val seiMd = decodeMasteringDisplayPayload(hex("33c286c41d4c0bb884d03e803d134042 00989680 00000032"))!!
    private val av1Md = decodeAv1Mdcv(hex("ae1451ec 43d7b0a4 26660f5c 500d5439 0003e800 00000052"))!!
    private val cll = ContentLightLevel(1000, 400)

    private fun plus(maxSclTenths: Int, avgTenths: Int, target: Long = 1000) = MetadataUnit(
        MetadataSource.HEVC_PREFIX_SEI, 4, "t35", 0, 1, 1,
        DecodedMetadata.Hdr10Plus(
            Hdr10PlusMetadata(1, 1, target, listOf(Hdr10PlusWindow(listOf(maxSclTenths, 0, 0), avgTenths, emptyList(), 0, false, null, null, emptyList())), null),
        ),
    )
    private fun mdUnit(md: MasteringDisplay) = MetadataUnit(MetadataSource.HEVC_PREFIX_SEI, 137, "md", 0, 1, 24, DecodedMetadata.Mastering(md))
    private fun cllUnit(c: ContentLightLevel) = MetadataUnit(MetadataSource.HEVC_PREFIX_SEI, 144, "cll", 0, 1, 4, DecodedMetadata.LightLevel(c))
    private fun rec(n: Int, sync: Boolean = false, vararg units: MetadataUnit) = FrameHdrRecord(n, n, n / 30.0, sync, units.toList())
    private val none = ContainerHdrStatic(null, null)

    @Test
    fun `missing HDR10plus ranges, ERROR when a keyframe is missing`() {
        val records = listOf(rec(0, true, plus(4000, 1000)), rec(1), rec(2), rec(3, false, plus(4000, 1000)), rec(4, true), rec(5, false, plus(4000, 1000)))
        val missing = detectHdrAnomalies(records, none).filter { it.rule == HdrRule.HDR10PLUS_MISSING }
        assertEquals(listOf(1 to 2, 4 to 4), missing.map { it.firstFrame to it.lastFrame })
        assertEquals(listOf(HdrSeverity.WARN, HdrSeverity.ERROR), missing.map { it.severity })
    }

    @Test
    fun `no HDR10plus at all or on every frame is not a missing anomaly`() {
        assertTrue(detectHdrAnomalies(listOf(rec(0), rec(1)), none).none { it.rule == HdrRule.HDR10PLUS_MISSING })
        assertTrue(detectHdrAnomalies(listOf(rec(0, true, plus(1, 1)), rec(1, false, plus(1, 1))), none).none { it.rule == HdrRule.HDR10PLUS_MISSING })
    }

    @Test
    fun `abrupt change needs both relative and absolute thresholds`() {
        // 400 -> 600 nits: +50% and +200 nits -> fires; 100 -> 190: +90% but only 90 nits -> no
        val fires = detectHdrAnomalies(listOf(rec(0, true, plus(4000, 0)), rec(1, false, plus(6000, 0))), none)
        assertEquals(HdrSeverity.WARN, fires.single { it.rule == HdrRule.ABRUPT_CHANGE }.severity)
        val quiet = detectHdrAnomalies(listOf(rec(0, true, plus(1000, 0)), rec(1, false, plus(1900, 0))), none)
        assertTrue(quiet.none { it.rule == HdrRule.ABRUPT_CHANGE })
        val belowRelative = detectHdrAnomalies(listOf(rec(0, true, plus(4000, 0)), rec(1, false, plus(5900, 0))), none)
        assertTrue(belowRelative.none { it.rule == HdrRule.ABRUPT_CHANGE })
    }

    @Test
    fun `abrupt change on a keyframe is INFO and thresholds are adjustable`() {
        val records = listOf(rec(0, false, plus(4000, 0)), rec(1, true, plus(6000, 0)))
        assertEquals(HdrSeverity.INFO, detectHdrAnomalies(records, none).single { it.rule == HdrRule.ABRUPT_CHANGE }.severity)
        assertTrue(detectHdrAnomalies(records, none, HdrThresholds(relativeChange = 0.6)).none { it.rule == HdrRule.ABRUPT_CHANGE })
    }

    @Test
    fun `static vs dynamic mismatches`() {
        val records = listOf(
            rec(0, true, mdUnit(seiMd), cllUnit(cll), plus(10500, 4100, target = 2000)),
            rec(1, false, plus(4000, 1000, target = 0)),
        )
        val messages = detectHdrAnomalies(records, none).filter { it.rule == HdrRule.STATIC_DYNAMIC_MISMATCH }
        assertEquals(5, messages.size) // MaxSCL>MDCV, target>MDCV, target==0, peak>MaxCLL, avg>MaxFALL
        assertTrue(messages.all { it.severity == HdrSeverity.WARN })
    }

    @Test
    fun `zero MaxCLL means unknown and is not compared`() {
        val records = listOf(rec(0, true, cllUnit(ContentLightLevel(0, 0)), plus(20000, 9000)))
        assertTrue(detectHdrAnomalies(records, none).none { it.rule == HdrRule.STATIC_DYNAMIC_MISMATCH })
    }

    @Test
    fun `container vs in-band`() {
        val inBandOnly = detectHdrAnomalies(listOf(rec(0, true, mdUnit(seiMd), cllUnit(cll))), none)
            .filter { it.rule == HdrRule.CONTAINER_INBAND_MISMATCH }
        assertEquals(listOf(HdrSeverity.INFO, HdrSeverity.INFO), inBandOnly.map { it.severity })

        val boxOnly = detectHdrAnomalies(listOf(rec(0, true)), ContainerHdrStatic(seiMd, cll)).filter { it.rule == HdrRule.CONTAINER_INBAND_MISMATCH }
        assertEquals(2, boxOnly.size)

        val differs = detectHdrAnomalies(
            listOf(rec(0, true, cllUnit(ContentLightLevel(1000, 401)))), ContainerHdrStatic(null, cll),
        ).filter { it.rule == HdrRule.CONTAINER_INBAND_MISMATCH }
        assertEquals(HdrSeverity.WARN, differs.single().severity)

        val agreeAcrossEncodings = detectHdrAnomalies(listOf(rec(0, true, mdUnit(av1Md))), ContainerHdrStatic(seiMd, null))
            .filter { it.rule == HdrRule.CONTAINER_INBAND_MISMATCH }
        assertTrue(agreeAcrossEncodings.none { it.severity == HdrSeverity.WARN })
    }

    @Test
    fun `in-band static change mid-stream is INFO`() {
        val records = listOf(rec(0, true, cllUnit(cll)), rec(1, true, cllUnit(ContentLightLevel(800, 300))))
        val changed = detectHdrAnomalies(records, ContainerHdrStatic(null, cll)).filter { it.rule == HdrRule.CONTAINER_INBAND_MISMATCH }
        assertEquals(listOf(HdrSeverity.INFO), changed.map { it.severity })
        assertEquals(1, changed.single().firstFrame)
    }

    @Test
    fun `mastering display tolerance across SEI and AV1 encodings`() {
        assertTrue(sameMasteringDisplay(seiMd, av1Md))
        assertFalse(sameMasteringDisplay(seiMd, seiMd.copy(maxLuminance = 1001.0)))
    }

    @Test
    fun `verdict`() {
        assertEquals(HdrVerdict.PASS, hdrVerdict(listOf(HdrAnomaly(HdrRule.ABRUPT_CHANGE, HdrSeverity.INFO, 0, 0, ""))))
        assertEquals(HdrVerdict.WARN, hdrVerdict(listOf(HdrAnomaly(HdrRule.ABRUPT_CHANGE, HdrSeverity.WARN, 0, 0, ""))))
        assertEquals(HdrVerdict.FAIL, hdrVerdict(listOf(HdrAnomaly(HdrRule.HDR10PLUS_MISSING, HdrSeverity.ERROR, 0, 0, ""))))
    }
}
