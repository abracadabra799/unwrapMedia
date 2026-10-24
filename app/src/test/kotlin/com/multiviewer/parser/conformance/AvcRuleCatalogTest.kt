package com.multiviewer.parser.conformance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AvcRuleCatalogTest {
    @Test
    fun verifiedByteObservationsHaveStableIdsWithoutUnverifiedNormativeClaims() {
        for (id in listOf("avc.nal.framing", "avc.nal.forbidden_zero_bit", "avc.nal.idr_ref_idc")) {
            val rule = assertNotNull(AvcRuleCatalog.rule(id))
            assertEquals(id, rule.id)
            assertNotNull(rule.citation)
            assertTrue(rule.scope.contains("avc1"))
        }
        assertNull(AvcRuleCatalog.rule("avc.unknown.rule"))
    }
}
