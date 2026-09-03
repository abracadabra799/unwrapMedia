package com.multiviewer.ui

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FormatFramePtsTest {

    @Test
    fun testFormatFramePtsSeconds() {
        assertEquals("0.000s", formatFramePts(0.0))
        assertEquals("0.033s", formatFramePts(0.033333))
        assertEquals("1.250s", formatFramePts(1.25))
        assertEquals("59.999s", formatFramePts(59.999))
    }

    @Test
    fun testFormatFramePtsMinutes() {
        assertEquals("1:00.000", formatFramePts(60.0))
        assertEquals("1:05.123", formatFramePts(65.123))
        assertEquals("10:30.500", formatFramePts(630.5))
    }
}
