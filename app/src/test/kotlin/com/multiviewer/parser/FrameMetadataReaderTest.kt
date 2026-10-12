package com.multiviewer.parser

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FrameMetadataReaderTest {
    private fun fixture(name: String) = File(javaClass.getResource("/hdr/$name")!!.toURI())

    private fun unitsOf(name: String, sampleIndex: Int): List<MetadataUnit> {
        val file = fixture(name)
        val root = parseFile(file)
        return ByteReader.open(file).use { reader ->
            val t = assertNotNull(buildVideoSampleTable(reader, root))
            val s = t.samples[sampleIndex]
            readFrameMetadataUnits(reader, t.codec, t.nalLengthSize, s.offset, s.size)
        }
    }

    @Test
    fun `detectVideoCodec from config boxes`() {
        assertEquals(VideoCodec.HEVC, detectVideoCodec(parseFile(fixture("hevc_hdr10plus.mp4"))))
        assertEquals(VideoCodec.H264, detectVideoCodec(parseFile(fixture("h264_hdr.mp4"))))
        assertEquals(VideoCodec.AV1, detectVideoCodec(parseFile(fixture("av1_hdr.mp4"))))
        assertNull(detectVideoCodec(BoxNode("root", 0, 0, 0)))
    }

    @Test
    fun `HEVC sample 0 lists CLL, MDCV, unregistered, HDR10plus in order`() {
        val units = unitsOf("hevc_hdr10plus.mp4", 0)
        assertEquals(listOf(144, 137, 5, 4), units.map { it.typeCode })
        assertTrue(units.all { it.source == MetadataSource.HEVC_PREFIX_SEI })
        assertEquals(ContentLightLevel(1000, 400), assertIs<DecodedMetadata.LightLevel>(units[0].decoded).value)
        assertEquals("2ca2de09-b517-47db-bb55-a4fe7fc2fc4e", assertIs<DecodedMetadata.UserDataUnregistered>(units[2].decoded).uuid)
        val plus = assertIs<DecodedMetadata.Hdr10Plus>(units[3].decoded).value
        assertEquals(listOf(4000, 4500, 3700), plus.windows[0].maxscl)
        // NAL range starts at the NAL header right after the 4-byte length prefix of the sample's first NAL
        assertEquals(44L + 4, units[0].unitOffset)
    }

    @Test
    fun `HEVC sample 12 has no metadata and sample 17 has the jump`() {
        assertEquals(emptyList(), unitsOf("hevc_hdr10plus.mp4", 12))
        val plus = assertIs<DecodedMetadata.Hdr10Plus>(unitsOf("hevc_hdr10plus.mp4", 17).single().decoded).value
        assertEquals(9000, plus.windows[0].maxscl[0])
    }

    @Test
    fun `H264 IDR sample SEIs`() {
        assertEquals(listOf(5, 137, 144), unitsOf("h264_hdr.mp4", 0).map { it.typeCode })
        assertEquals(emptyList(), unitsOf("h264_hdr.mp4", 1))
        assertTrue(unitsOf("h264_hdr.mp4", 5).any { it.decoded is DecodedMetadata.Mastering })
    }

    @Test
    fun `AV1 keyframe metadata OBUs`() {
        val units = unitsOf("av1_hdr.mp4", 0)
        assertEquals(listOf("HDR_CLL", "HDR_MDCV"), units.map { it.name })
        assertTrue(units.all { it.source == MetadataSource.AV1_METADATA_OBU })
        val md = assertIs<DecodedMetadata.Mastering>(units[1].decoded).value
        assertEquals(1000.0, md.maxLuminance, 1e-9)
        assertEquals(emptyList(), unitsOf("av1_hdr.mp4", 1))
    }

    @Test
    fun `bad NAL length is reported, not thrown`() {
        // one length prefix claiming 0x100 bytes inside a 10-byte sample
        val bytes = hex("00000100 4e01 90 04 03e8")
        val units = byteReaderOf(bytes).use { readFrameMetadataUnits(it, VideoCodec.HEVC, 4, 0, bytes.size.toLong()) }
        assertIs<DecodedMetadata.Malformed>(units.single().decoded)
        assertEquals(MetadataSource.SAMPLE_STRUCTURE, units.single().source)
    }

    @Test
    fun `HEVC suffix SEI after a VCL NAL and DV NAL 62 are found`() {
        val vcl = hex("0201 aa bb")                   // TRAIL_R slice (type 1)
        val suffix = hex("5001 90 04 03e8 0190 80")   // type 40 suffix SEI
        val rpu = hex("7c01 19 08")                   // type 62
        fun prefixed(n: ByteArray) = byteArrayOf(0, 0, 0, n.size.toByte()) + n
        val sample = prefixed(vcl) + prefixed(suffix) + prefixed(rpu)
        val units = byteReaderOf(sample).use { readFrameMetadataUnits(it, VideoCodec.HEVC, 4, 0, sample.size.toLong()) }
        assertEquals(listOf(MetadataSource.HEVC_SUFFIX_SEI, MetadataSource.HEVC_UNSPECIFIED_NAL), units.map { it.source })
        assertEquals(62, units[1].typeCode)
    }
}
