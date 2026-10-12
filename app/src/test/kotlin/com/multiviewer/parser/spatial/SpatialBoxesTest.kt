package com.multiviewer.parser.spatial

import com.multiviewer.parser.byteReaderOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpatialBoxesTest {

    @Test
    fun `decodes lhvC box with profile and NAL array counts`() {
        val payload = ByteArray(23 + 3 + 4).apply {
            this[0] = 1.toByte() // configVersion
            this[1] = (0x20 or 2).toByte() // profile_idc = 2 (Main 10)
            this[12] = 120.toByte() // level_idc
            this[21] = 0x03.toByte() // length_size = 4 (3 + 1)
            this[22] = 1.toByte() // num_arrays = 1

            // Array 0: SPS (type 33), 1 NAL
            this[23] = 33.toByte() // nal_type
            this[24] = 0.toByte() // count MSB
            this[25] = 1.toByte() // count LSB
            this[26] = 0.toByte() // len MSB
            this[27] = 2.toByte() // len LSB = 2
            this[28] = 0x12.toByte() // dummy data
            this[29] = 0x34.toByte()
        }

        val reader = byteReaderOf(payload)
        val node = LhvcBoxDecoder.decode(reader, "lhvC", 0L, 0, payload.size.toLong(), emptyList())

        assertTrue(node.warnings.isEmpty())
        assertEquals("MV-HEVC profile=2, level=120, 1 SPS, 0 PPS", node.summary)
        assertEquals("4", node.fields.find { it.name == "length_size" }?.value)
        assertEquals("1", node.fields.find { it.name == "num_sps" }?.value)
    }

    @Test
    fun `decodes eyes box with hero eye and eye views`() {
        // version 0, flags 0, hero_eye = 1 (Left), eye_views = 3 (Stereo)
        val payload = byteArrayOf(0, 0, 0, 0, 1, 3)
        val reader = byteReaderOf(payload)
        val node = EyesBoxDecoder.decode(reader, "eyes", 0L, 0, payload.size.toLong(), emptyList())

        assertTrue(node.warnings.isEmpty())
        assertEquals("Left Eye", node.fields.find { it.name == "hero_eye" }?.value)
        assertEquals("Stereo (Left + Right)", node.fields.find { it.name == "eye_views" }?.value)
        assertTrue(node.summary?.contains("hero=Left Eye") == true)
    }

    @Test
    fun `decodes hero box indicating right eye`() {
        // version 0, flags 0, hero_eye = 2 (Right Eye)
        val payload = byteArrayOf(0, 0, 0, 0, 2)
        val reader = byteReaderOf(payload)
        val node = HeroBoxDecoder.decode(reader, "hero", 0L, 0, payload.size.toLong(), emptyList())

        assertTrue(node.warnings.isEmpty())
        assertEquals("Right Eye", node.fields.find { it.name == "hero_eye" }?.value)
        assertEquals("Hero Eye: Right Eye", node.summary)
    }

    @Test
    fun `decodes st3d box with multi-view mode`() {
        // version 0, flags 0, mode = 4 (Multi-View)
        val payload = byteArrayOf(0, 0, 0, 0, 4)
        val reader = byteReaderOf(payload)
        val node = St3dBoxDecoder.decode(reader, "st3d", 0L, 0, payload.size.toLong(), emptyList())

        assertTrue(node.warnings.isEmpty())
        assertEquals("4 (Multi-View (MV-HEVC))", node.fields.find { it.name == "stereo_mode" }?.value)
        assertEquals("Stereo Mode: Multi-View (MV-HEVC)", node.summary)
    }

    @Test
    fun `warns on truncated spatial boxes`() {
        val short = byteArrayOf(0, 0) // shorter than FullBox 4 bytes
        val reader = byteReaderOf(short)
        val node = EyesBoxDecoder.decode(reader, "eyes", 0L, 0, short.size.toLong(), emptyList())
        assertTrue(node.warnings.isNotEmpty())
    }
}
