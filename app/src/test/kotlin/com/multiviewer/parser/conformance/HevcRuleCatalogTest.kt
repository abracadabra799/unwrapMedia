package com.multiviewer.parser.conformance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HevcRuleCatalogTest {
    @Test
    fun verifiedByteObservationsHaveStableIdsWithoutUnverifiedNormativeClaims() {
        for (id in listOf("hevc.nal.framing", "hevc.nal.forbidden_zero_bit", "hevc.nal.temporal_id_plus1")) {
            val rule = assertNotNull(HevcRuleCatalog.rule(id))
            assertEquals(id, rule.id)
            assertTrue(rule.source.contains("structural observation"))
            assertTrue(rule.scope.contains("hvc1") && rule.scope.contains("hev1"))
        }
        assertNull(HevcRuleCatalog.rule("hevc.hvc1.parameter_sets_location"))
    }
}
