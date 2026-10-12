package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MetadataFormattingTest {
    private fun unit(decoded: DecodedMetadata, payloadSize: Int = 4) =
        MetadataUnit(MetadataSource.HEVC_PREFIX_SEI, 0, "x", 0, 10, payloadSize, decoded)

    private val plus = Hdr10PlusMetadata(
        1, 1, 1000,
        listOf(Hdr10PlusWindow(listOf(4000, 4500, 3700), 1000, listOf(1 to 0, 99 to 4500), 0, true, 1000, 1200, listOf(300, 600, 900))),
        null,
    )

    @Test
    fun `summaries`() {
        assertEquals("MaxCLL 1000 / MaxFALL 400", summarizeMetadata(unit(DecodedMetadata.LightLevel(ContentLightLevel(1000, 400)))))
        assertEquals("HDR10+ MaxSCL 450.0 nits, avg 100.0 nits, target 1000 nits", summarizeMetadata(unit(DecodedMetadata.Hdr10Plus(plus))))
        assertEquals("12 bytes", summarizeMetadata(unit(DecodedMetadata.PresenceOnly, payloadSize = 12)))
        assertEquals("malformed: cut", summarizeMetadata(unit(DecodedMetadata.Malformed("cut"))))
    }

    @Test
    fun `HDR10plus detail rows`() {
        val rows = describeMetadata(unit(DecodedMetadata.Hdr10Plus(plus))).toMap()
        assertEquals("400.0 / 450.0 / 370.0 nits", rows["maxscl R/G/B"])
        assertEquals("1%: 0.0, 99%: 450.0 nits", rows["distribution_maxrgb"])
        assertEquals("(1000, 1200), anchors 300, 600, 900", rows["tone_mapping"])
    }

    @Test
    fun `mastering detail rows`() {
        val md = decodeMasteringDisplayPayload(hex("33c286c41d4c0bb884d03e803d134042 00989680 00000032"))!!
        val rows = describeMetadata(unit(DecodedMetadata.Mastering(md))).toMap()
        assertEquals("0.68000, 0.32000", rows["R (x, y)"])
        assertEquals("1000.0000 / 0.0050 cd/m²", rows["max / min luminance"])
        assertTrue(describeMetadata(unit(DecodedMetadata.PresenceOnly)).isEmpty())
    }
}
