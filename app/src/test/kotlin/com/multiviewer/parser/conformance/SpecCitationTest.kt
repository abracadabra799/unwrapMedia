package com.multiviewer.parser.conformance

import kotlin.test.Test
import kotlin.test.assertEquals

class SpecCitationTest {
    @Test fun labelWithoutPageVerified() {
        val c = SpecCitation("AVIF v1.2.0 (AOMedia, 2025-10-16)", "§7 General constraints", null,
            "https://aomediacodec.github.io/av1-avif/v1.2.0.html#file-constraints", true)
        assertEquals("AVIF v1.2.0 (AOMedia, 2025-10-16) §7 General constraints", c.label())
    }

    @Test fun labelWithPageVerified() {
        val c = SpecCitation("ITU-T H.265 (V10, 2024-07)", "§7.3.2.2", 7, "https://example.org/h265.pdf#page=7", true)
        assertEquals("ITU-T H.265 (V10, 2024-07) §7.3.2.2, p. 7", c.label())
    }

    @Test fun labelUnverifiedAddsDisclaimer() {
        val c = SpecCitation("ISO/IEC 23008-12:2025", "§9.3", null, "https://www.iso.org/standard/1.html", false)
        assertEquals("ISO/IEC 23008-12:2025 §9.3 (원문 미대조 / not checked against the original)", c.label())
    }

    @Test fun ruleCitationDefaultsToNull() {
        assertEquals(null, ConformanceRule("r", "t", "s", null, "ftyp").citation)
    }
}
