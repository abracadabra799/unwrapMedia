package com.multiviewer.parser.conformance.png

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.conformance.ConformanceReport
import com.multiviewer.parser.conformance.ConformanceStatus.FAIL
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_CHECKED
import com.multiviewer.parser.conformance.ConformanceStatus.PASS
import com.multiviewer.parser.conformance.ConformanceStatus.WARN
import com.multiviewer.parser.conformance.conformanceFixture
import com.multiviewer.parser.conformance.idsWith
import com.multiviewer.parser.conformance.notPassing
import com.multiviewer.parser.conformance.statusOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real tool output (ffmpeg, macOS sips) and byte-level variants — docs/validation/png-webp-gif-conformance-fixtures/. */
class PngFixtureTest {
    private fun run(name: String): ConformanceReport = ByteReader.open(conformanceFixture(name)).use { PngConformanceChecker.check(it) }

    @Test fun `ffmpeg default, palette, 16-bit grey, RGBA, APNG and sips output have no FAIL, WARN or NOT_CHECKED`() {
        listOf("ffmpeg-default.png", "ffmpeg-pal8.png", "ffmpeg-gray16.png", "ffmpeg-rgba.png", "ffmpeg.apng", "sips.png", "png-text-ok.png").forEach { name ->
            val r = run(name)
            assertNull(r.inspectionError, name)
            assertEquals(emptySet(), r.idsWith(FAIL, WARN, NOT_CHECKED), "$name: ${r.notPassing()}")
            assertEquals(PASS, r.overall, name)
        }
        val apng = run("ffmpeg.apng")
        assertTrue(apng.findings.single { it.rule.id == "png3.actl" }.details.single().contains("num_frames 3"))
    }

    @Test fun `each variant fails exactly its targeted rule`() {
        val expected = mapOf(
            "png-crc.png" to setOf("png3.crc"),
            "png-truncated.png" to setOf("png3.chunk.layout", "png3.critical"),
            "png-trailing.png" to setOf("png3.trailing"),
            "png-ihdr-depth.png" to setOf("png3.ihdr"),
            "png-plte-gray.png" to setOf("png3.plte"),
            "png-pal-noplte.png" to setOf("png3.plte"),
            "png-order.png" to setOf("png3.order"),
            "png-gama-dup.png" to setOf("png3.multiple"),
            "png-trns-rgba.png" to setOf("png3.trns"),
            "png-srgb-iccp.png" to setOf("png3.srgb_iccp"),
            "png-keyword.png" to setOf("png3.keyword"),
            "png-itxt.png" to setOf("png3.text"),
            "png-zlib-fcheck.png" to setOf("zlib.header"),
            "png-fdict.png" to setOf("png3.compression"),
            "png-bkgd-bits.png" to setOf("png3.unused_bits"),
            "apng-seq.apng" to setOf("png3.sequence"),
            "apng-actl.apng" to setOf("png3.actl"),
            "apng-region.apng" to setOf("png3.fctl"),
            "apng-nofdat.apng" to setOf("png3.fdat"),
        )
        expected.forEach { (name, ids) ->
            val r = run(name)
            assertNull(r.inspectionError, name)
            assertEquals(ids, r.idsWith(FAIL, WARN), "$name: ${r.notPassing()}")
        }
        listOf("png-srgb-iccp.png", "png-bkgd-bits.png").forEach { assertEquals(WARN, run(it).overall, it) }
        // PNG-3 §15.3.1 (conformance condition): data after IEND is a FAIL, not a WARN.
        assertEquals(FAIL, run("png-trailing.png").overall)
        assertEquals(FAIL, run("png-trailing.png").statusOf("png3.trailing"))
    }

    @Test fun `findings carry the verified W3C anchor`() {
        val c = run("png-crc.png").findings.single { it.rule.id == "png3.crc" }.rule.citation!!
        assertTrue(c.verified)
        assertNull(c.page)
        assertEquals("https://www.w3.org/TR/2025/REC-png-3-20250624/#5CRC-algorithm", c.url)
    }
}
