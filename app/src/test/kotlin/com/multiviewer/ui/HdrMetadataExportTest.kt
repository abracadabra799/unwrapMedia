package com.multiviewer.ui

import com.multiviewer.parser.ContentLightLevel
import com.multiviewer.parser.DecodedMetadata
import com.multiviewer.parser.FrameHdrRecord
import com.multiviewer.parser.Hdr10PlusMetadata
import com.multiviewer.parser.Hdr10PlusWindow
import com.multiviewer.parser.HdrAnomaly
import com.multiviewer.parser.HdrRule
import com.multiviewer.parser.HdrSeverity
import com.multiviewer.parser.MetadataSource
import com.multiviewer.parser.MetadataUnit
import kotlin.test.Test
import kotlin.test.assertEquals

class HdrMetadataExportTest {
    @Test
    fun `frames csv`() {
        val plus = MetadataUnit(
            MetadataSource.HEVC_PREFIX_SEI, 4, "user_data_registered_itu_t_t35", 0, 1, 1,
            DecodedMetadata.Hdr10Plus(Hdr10PlusMetadata(1, 1, 1000, listOf(Hdr10PlusWindow(listOf(4000, 4500, 3700), 1000, emptyList(), 0, false, null, null, emptyList())), null)),
        )
        val cll = MetadataUnit(MetadataSource.HEVC_PREFIX_SEI, 144, "content_light_level_info", 0, 1, 4, DecodedMetadata.LightLevel(ContentLightLevel(1000, 400)))
        val csv = buildHdrFramesCsv(listOf(FrameHdrRecord(0, 0, 0.0, true, listOf(cll, plus)), FrameHdrRecord(1, 1, 0.033333, false, emptyList())))
        val lines = csv.trimEnd().lines()
        assertEquals(
            "frame,sample,pts_seconds,keyframe,metadata,maxscl_r_nits,maxscl_g_nits,maxscl_b_nits,average_maxrgb_nits,targeted_max_nits,mdcv_max_nits,mdcv_min_nits,max_cll,max_fall",
            lines[0],
        )
        assertEquals("0,0,0.000000,1,content_light_level_info|user_data_registered_itu_t_t35,400.0,450.0,370.0,100.0,1000,,,1000,400", lines[1])
        assertEquals("1,1,0.033333,0,,,,,,,,,,", lines[2])
    }

    @Test
    fun `anomalies csv quotes commas and quotes`() {
        val csv = buildHdrAnomaliesCsv(listOf(HdrAnomaly(HdrRule.ABRUPT_CHANGE, HdrSeverity.WARN, 17, 17, "MaxSCL 1 → 2 nits; \"x\", y")))
        assertEquals("severity,rule,first_frame,last_frame,message\nWARN,ABRUPT_CHANGE,17,17,\"MaxSCL 1 → 2 nits; \"\"x\"\", y\"\n", csv)
    }
}
