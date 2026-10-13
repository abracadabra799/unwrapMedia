package com.multiviewer.parser

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

// S26 files carry both Ultra HDR XMP and ISO 21496-1 binary metadata; they used to be reported as
// Apple gain maps (APPLE_MPF_JPEG / APPLE_HEIC) because classification only looked for "21496" in XMP.
class GainmapClassificationTest {
    private fun info(name: String): GainmapInfo {
        val f = File("src/test/resources/c2pa/$name")
        return assertNotNull(GainmapParser.findGainmapInfo(f, parseFile(f)))
    }

    @Test
    fun `S26 JPEG is ISO 21496-1 with Ultra HDR XMP alongside`() {
        val g = info("s26-camera.jpg")
        assertEquals(GainmapFormatType.ISO_21496_1_JPEG, g.formatType)
        assertEquals(
            setOf(GainmapMetadataSource.ISO_21496_1, GainmapMetadataSource.HDRGM_XMP, GainmapMetadataSource.GCONTAINER, GainmapMetadataSource.MPF),
            g.metadataSources,
        )
        assertEquals(2.3, assertNotNull(g.isoMetadata).alternateHdrHeadroom, 1e-6)
        assertEquals(2.3, assertNotNull(g.xmpParameters).gainMapMax!!, 1e-6)
        assertEquals(true, g.parameters?.version?.startsWith("ISO 21496-1")) // effective = ISO
        assertEquals(true, g.hasIsoVersionBlockInPrimary)
    }

    @Test
    fun `S26 HEIC is ISO 21496-1 via its tmap item with a Samsung auxC`() {
        val g = info("s26-motion-photo.heic")
        assertEquals(GainmapFormatType.ISO_21496_1_HEIC, g.formatType)
        assertEquals(
            setOf(GainmapMetadataSource.ISO_21496_1, GainmapMetadataSource.HEIF_TMAP, GainmapMetadataSource.HEIF_AUXC, GainmapMetadataSource.HDRGM_XMP),
            g.metadataSources,
        )
        assertEquals("urn:com:samsung:photo:2024:aux:hdrgainmap", g.auxType)
        assertEquals(56L, g.tmapItem?.itemId)
        assertEquals(2.3, assertNotNull(g.parameters).hdrCapacityMax!!, 1e-6)
    }
}
