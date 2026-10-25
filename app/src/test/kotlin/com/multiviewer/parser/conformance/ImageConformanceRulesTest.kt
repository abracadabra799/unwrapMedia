package com.multiviewer.parser.conformance

import com.multiviewer.parser.conformance.gif.GifRules
import com.multiviewer.parser.conformance.png.PngRules
import com.multiviewer.parser.conformance.webp.WebpRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImageConformanceRulesTest {
    private fun common(rules: List<ConformanceRule>) {
        assertEquals(rules.size, rules.map { it.id }.toSet().size)
        rules.forEach { r ->
            val c = r.citation!!
            assertTrue(c.verified, r.id)
            assertEquals(c.url, r.reference, r.id)
            assertTrue(r.title.contains(" / "), r.id)
            // Never collide with the Structure row's observation ids (png.*, webp.*, gif.*).
            assertTrue(!r.id.startsWith("png.") && !r.id.startsWith("webp.") && !r.id.startsWith("gif."), r.id)
        }
    }

    private fun definitional(rules: List<ConformanceRule>) = rules.filter { it.citation!!.section.endsWith(DEFINITIONAL_SUFFIX) }.map { it.id }.toSet()

    @Test fun `PNG catalog cites PNG-3 anchors and RFC 1950 page 5`() {
        val rules = PngRules.all
        common(rules)
        assertEquals(23, rules.size)
        assertEquals(setOf("png3.srgb_iccp", "png3.unused_bits"), PngRules.WARN_RULES)
        rules.filter { it.id != "zlib.header" }.forEach { r ->
            val c = r.citation!!
            assertNull(c.page, r.id)
            assertTrue(c.url.startsWith(PngRules.PNG_URL + "#") && c.url.length > PngRules.PNG_URL.length + 1, r.id)
        }
        val z = PngRules.rule("zlib.header").citation!!
        assertEquals(5, z.page)
        assertEquals("https://www.rfc-editor.org/rfc/rfc1950.html#page-5", z.url)
        assertEquals(setOf("png3.signature", "png3.crc", "png3.multiple"), definitional(rules))
    }

    @Test fun `WebP catalog cites RFC 9649 and RFC 6386 section anchors`() {
        val rules = WebpRules.all
        common(rules)
        assertEquals(17, rules.size)
        assertEquals(setOf("rfc9649.anmf.flag", "rfc9649.alph.vp8l", "rfc9649.metadata.count", "rfc9649.trailing", "rfc6386.version"), WebpRules.WARN_RULES)
        rules.forEach { r ->
            val c = r.citation!!
            assertNull(c.page, r.id)
            assertTrue(c.url.startsWith(WebpRules.WEBP_URL + "#section-") || c.url == WebpRules.VP8_URL + "#section-9.1", r.id)
        }
        assertEquals(setOf("rfc9649.riff.size", "rfc9649.layout", "rfc9649.vp8x.flags", "rfc6386.frame", "rfc9649.canvas"), definitional(rules))
    }

    @Test fun `GIF catalog cites section numbers of the plain-text original`() {
        val rules = GifRules.all
        common(rules)
        assertEquals(15, rules.size)
        assertEquals(6, GifRules.WARN_RULES.size)
        rules.forEach { r ->
            val c = r.citation!!
            assertNull(c.page, r.id)
            assertEquals(GifRules.GIF_URL, c.url, r.id)
            assertTrue(c.document.contains("no anchors"), r.id)
        }
        assertEquals(setOf("gif89a.version", "gif89a.blocks", "gif89a.lzw", "gif89a.trailer", "gif89a.trailing"), definitional(rules))
    }
}
