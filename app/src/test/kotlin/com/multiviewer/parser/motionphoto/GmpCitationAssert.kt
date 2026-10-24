package com.multiviewer.parser.motionphoto

import com.multiviewer.parser.SefIntegritySeverity
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal const val GMP_DOC_URL = "https://developer.android.com/media/platform/motion-photo-format"

/** Anchors verified in the published page (id="…" attributes), per spec section. §3.4 has two subsections. */
internal fun expectedGmpAnchor(ruleId: String, section: String): String = when (section) {
    "2.1" -> "file-name"
    "2.2" -> "media-data"
    "3" -> "xmp-attributes"
    "3.1" -> "camera-metadata"
    "3.2" -> "container-element"
    "3.3" -> "item_element"
    "3.4" -> if (ruleId in setOf("gmp.item.mime_value", "gmp.item.video_mime")) "itemmime_type_values" else "itemsemantic_values"
    "4" -> "isobmff-image-specific-behavior"
    "5" -> "video-container"
    "6" -> "video-metadata"
    else -> error("unexpected section $section ($ruleId)")
}

/** Every FAIL/WARN Google Motion Photo finding cites the verified section anchor it names. */
internal fun List<GoogleSpecFinding>.assertGmpCited(): List<GoogleSpecFinding> {
    filter { it.severity == SefIntegritySeverity.CRITICAL || it.severity == SefIntegritySeverity.WARNING }.forEach { f ->
        val c = assertNotNull(f.citation, "${f.ruleId} §${f.section} has no citation")
        assertEquals("Google Motion Photo Format 1.0 (Android Developers)", c.document, f.ruleId)
        assertEquals("$GMP_DOC_URL#${expectedGmpAnchor(f.ruleId, f.section)}", c.url, f.ruleId)
        assertTrue(c.section.startsWith("§${f.section} "), "${f.ruleId}: ${c.section}")
        assertNull(c.page, f.ruleId)
        assertEquals(true, c.verified, f.ruleId)
    }
    return this
}
