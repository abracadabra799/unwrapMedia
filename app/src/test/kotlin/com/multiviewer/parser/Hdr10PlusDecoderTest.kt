package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Hdr10PlusDecoderTest {
    // Real payload from the hevc_hdr10plus.mp4 fixture (frame 0); ffprobe 8.1.2 decodes the same values.
    private val frame0 = hex("b5003c0001040140001f401f401194073a00fa240800002800285000c8c80321900fa2580c82d01772f82713184650004fa12c0d2c963840")

    @Test
    fun `decodes every field of a real single-window payload`() {
        val m = assertNotNull(decodeHdr10Plus(frame0))
        assertEquals(1, m.applicationVersion)
        assertEquals(1, m.numWindows)
        assertEquals(1000L, m.targetedSystemDisplayMaximumLuminance)
        val w = m.windows.single()
        assertEquals(listOf(4000, 4500, 3700), w.maxscl)
        assertEquals(1000, w.averageMaxrgb)
        assertEquals(
            listOf(1 to 0, 5 to 10, 10 to 50, 25 to 200, 50 to 1000, 75 to 800, 90 to 1500, 95 to 2500, 99 to 4500),
            w.distribution,
        )
        assertEquals(0, w.fractionBrightPixels)
        assertTrue(w.toneMappingFlag)
        assertEquals(1000, w.kneePointX)
        assertEquals(1200, w.kneePointY)
        assertEquals(listOf(300, 600, 900), w.bezierCurveAnchors)
        assertNull(m.colorSaturationWeight)
        assertEquals(450.0, w.maxSclNits)
        assertEquals(100.0, w.averageMaxrgbNits)
    }

    @Test
    fun `trailing bytes after the last field are ignored`() {
        assertNotNull(decodeHdr10Plus(frame0 + byteArrayOf(0x80.toByte())))
    }

    @Test
    fun `header check rejects other T35 payloads`() {
        assertTrue(isHdr10PlusT35(frame0))
        val dolby = frame0.copyOf().also { it[2] = 0x3B } // provider 0x003B instead of 0x003C
        assertFalse(isHdr10PlusT35(dolby))
        assertNull(decodeHdr10Plus(dolby))
    }

    @Test
    fun `truncated payload returns null`() {
        assertNull(decodeHdr10Plus(frame0.copyOf(20)))
    }

    @Test
    fun `two windows, peak luminance matrices and saturation weight`() {
        val w = TestBitWriter()
            .u(8, 0xB5).u(16, 0x3C).u(16, 1).u(8, 4)
            .u(8, 1).u(2, 2)
        // window 1 geometry: 6 x u16, u8, 3 x u16, u1
        repeat(6) { w.u(16, 7) }
        w.u(8, 0); repeat(3) { w.u(16, 9) }; w.u(1, 0)
        w.u(27, 4000).u(1, 1).u(5, 2).u(5, 2); repeat(4) { w.u(4, 5) } // targeted + 2x2 matrix
        listOf(listOf(100L, 200L, 300L, 150L), listOf(10L, 20L, 30L, 15L)).forEach { (r, g, b, avg) ->
            w.u(17, r).u(17, g).u(17, b).u(17, avg).u(4, 1).u(7, 99).u(17, b).u(10, 3)
        }
        w.u(1, 1).u(5, 1).u(5, 1).u(4, 2) // mastering_display_actual_peak 1x1
        w.u(1, 0)                          // window 0: no tone mapping
        w.u(1, 1).u(12, 5).u(12, 6).u(4, 1).u(10, 7) // window 1 tone mapping
        w.u(1, 1).u(6, 33)                 // color saturation
        val m = assertNotNull(decodeHdr10Plus(w.toBytes()))
        assertEquals(2, m.numWindows)
        assertEquals(4000L, m.targetedSystemDisplayMaximumLuminance)
        assertEquals(listOf(100, 200, 300), m.windows[0].maxscl)
        assertEquals(15, m.windows[1].averageMaxrgb)
        assertEquals(listOf(99 to 30), m.windows[1].distribution)
        assertFalse(m.windows[0].toneMappingFlag)
        assertNull(m.windows[0].kneePointX)
        assertEquals(listOf(7), m.windows[1].bezierCurveAnchors)
        assertEquals(33, m.colorSaturationWeight)
    }
}
