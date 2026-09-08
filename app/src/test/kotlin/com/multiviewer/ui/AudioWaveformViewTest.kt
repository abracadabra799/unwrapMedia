package com.multiviewer.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AudioWaveformViewTest {

    @Test
    fun `formatMinSecMillis zero-pads minutes and seconds and shows three ms digits`() {
        assertEquals("00:00.000", formatMinSecMillis(0.0))
        assertEquals("00:32.450", formatMinSecMillis(32.45))
        assertEquals("03:15.820", formatMinSecMillis(195.82))
        assertEquals("125:03.900", formatMinSecMillis(7503.9))
    }

    @Test
    fun `formatMinSecMillis clamps negatives to zero`() {
        assertEquals("00:00.000", formatMinSecMillis(-5.0))
    }

    @Test
    fun `niceTimeStep gives between 3 and 12 ticks across the span and is non-decreasing`() {
        var prev = 0.0
        for (span in listOf(0.2, 0.5, 1.0, 3.0, 8.0, 20.0, 60.0, 200.0, 900.0, 3600.0)) {
            val step = niceTimeStep(span)
            assertTrue(step > 0.0)
            val ticks = span / step
            assertTrue(ticks in 3.0..12.0, "span=$span step=$step ticks=$ticks")
            assertTrue(step >= prev, "step decreased at span=$span")
            prev = step
        }
    }
}
