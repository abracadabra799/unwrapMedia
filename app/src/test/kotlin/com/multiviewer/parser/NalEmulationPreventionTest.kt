package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class NalEmulationPreventionTest {
    @Test
    fun `removeEmulationPreventionBytes strips a 0x03 that follows 00 00`() {
        val input = byteArrayOf(0x01, 0x00, 0x00, 0x03, 0x02)
        assertEquals(listOf<Byte>(0x01, 0x00, 0x00, 0x02), removeEmulationPreventionBytes(input).toList())
    }

    @Test
    fun `removeEmulationPreventionBytes leaves bytes with no 00 00 prefix untouched`() {
        val input = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        assertEquals(input.toList(), removeEmulationPreventionBytes(input).toList())
    }

    @Test
    fun `removeEmulationPreventionBytes does not strip 0x03 after only one leading zero`() {
        val input = byteArrayOf(0x01, 0x00, 0x03, 0x02)
        assertEquals(input.toList(), removeEmulationPreventionBytes(input).toList())
    }

    @Test
    fun `removeEmulationPreventionBytes handles consecutive emulation sequences`() {
        // 00 00 03 00 00 03 01 -- each 00 00 immediately followed by 03 gets stripped, and the
        // zero-run count resets after each strip so the second 00 00 03 is detected independently.
        val input = byteArrayOf(0x00, 0x00, 0x03, 0x00, 0x00, 0x03, 0x01)
        assertEquals(listOf<Byte>(0x00, 0x00, 0x00, 0x00, 0x01), removeEmulationPreventionBytes(input).toList())
    }

    // Real SPS from an x264-encoded file (same source as H264ParameterSetsTest's fixture) --
    // contains two genuine emulation_prevention_three_byte occurrences (at raw byte indices 13
    // and 18, both inside the VUI timing_info fields, which parseH264Sps parses but this specific
    // test file's earlier assertions never checked). Without stripping them, num_units_in_tick/
    // time_scale decode to nonsensical values; with stripping, they decode to a sane 25fps-implying
    // pair -- confirmed against this exact real file both ways before writing this test.
    private val realSpsWithEmulationBytes = byteArrayOf(
        0x67, 0xf4.toByte(), 0x00, 0x0d, 0x91.toByte(), 0x9b.toByte(), 0x28, 0x28,
        0x3f, 0x60, 0x22, 0x00, 0x00, 0x03, 0x00, 0x02,
        0x00, 0x00, 0x03, 0x00, 0x64, 0x1e, 0x28, 0x53.toByte(), 0x2c,
    )

    @Test
    fun `parseH264Sps decodes correct VUI timing_info only after emulation-prevention bytes are stripped`() {
        // Verified directly (a standalone Python simulation of this exact real file, both with and
        // without stripping) that the RAW bytes decode timing_info to num_units_in_tick=384,
        // time_scale=16777217 -- a nonsensical multi-million time_scale -- while the DE-EMULATED
        // bytes correctly decode to num_units_in_tick=1, time_scale=50 (2 * 25fps, the standard
        // H.264 field-rate timing convention for 25fps content).
        val sps = parseH264Sps(realSpsWithEmulationBytes)
        val vui = sps?.vui
        kotlin.test.assertNotNull(vui)
        assertEquals(1L, vui.numUnitsInTick)
        assertEquals(50L, vui.timeScale)
    }
}
