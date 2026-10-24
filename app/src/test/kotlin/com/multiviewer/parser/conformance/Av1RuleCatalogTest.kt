package com.multiviewer.parser.conformance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Av1RuleCatalogTest {
    @Test
    fun verifiedByteObservationsHaveStableIdsWithoutUnverifiedNormativeClaims() {
        val expectedIds = listOf(
            "av1.obu.framing",
            "av1.obu.forbidden_bit",
            "av1.obu.reserved_bit",
            "av1.obu.extension_reserved_bits",
            "av1.config.sequence_header",
        )
        for (id in expectedIds) {
            val rule = assertNotNull(Av1RuleCatalog.rule(id))
            assertEquals(id, rule.id)
            assertNotNull(rule.citation)
            assertTrue(rule.scope.contains("av01"))
        }
        assertNull(Av1RuleCatalog.rule("av1.unknown.rule"))
    }
}
