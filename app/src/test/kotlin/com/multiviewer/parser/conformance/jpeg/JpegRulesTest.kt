package com.multiviewer.parser.conformance.jpeg

import com.multiviewer.parser.conformance.DEFINITIONAL_SUFFIX
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JpegRulesTest {
    @Test fun `every rule is cited to a verified PDF page`() {
        assertEquals(39, JpegRules.all.size)
        assertEquals(JpegRules.all.size, JpegRules.all.map { it.id }.toSet().size)
        JpegRules.all.forEach { r ->
            val c = r.citation!!
            assertTrue(c.verified, r.id)
            assertEquals("#page=${c.page}", "#" + c.url.substringAfter("#"), r.id)
            assertEquals(c.url, r.reference, r.id)
            assertTrue(r.title.contains(" / "), r.id)
        }
        assertEquals(setOf("t81.marker.reserved", "t81.trailing", "exif.tags.recommended", "mpf.endian"), JpegRules.WARN_RULES)
        assertEquals(13, JpegRules.all.count { it.id.startsWith("t81.") })
        assertEquals(6, JpegRules.all.count { it.id.startsWith("jfif.") })
        assertEquals(11, JpegRules.all.count { it.id.startsWith("exif.") })
        assertEquals(9, JpegRules.all.count { it.id.startsWith("mpf.") })
        assertEquals(setOf("t81.marker.reserved", "t81.segment.length", "t81.dht", "t81.trailing", "exif.ifd.structure", "exif.tag.type", "mpf.count", "mpf.images"),
            JpegRules.all.filter { it.citation!!.section.endsWith(DEFINITIONAL_SUFFIX) }.map { it.id }.toSet())
    }
}
