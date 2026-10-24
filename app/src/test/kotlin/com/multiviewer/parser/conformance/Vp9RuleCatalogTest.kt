package com.multiviewer.parser.conformance

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.VpcCBoxDecoder
import com.multiviewer.parser.parseFile
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Vp9RuleCatalogTest {
    private fun fixture(name: String) = File(javaClass.getResource("/vp9-conformance/$name")!!.toURI())

    @Test
    fun verifiedByteObservationsHaveStableIdsWithoutUnverifiedNormativeClaims() {
        val expectedIds = listOf(
            "vp9.frame.framing",
            "vp9.frame.marker",
            "vp9.frame.sync_code",
            "vp9.frame.profile_reserved_bit",
            "vp9.config.vpcc",
        )
        for (id in expectedIds) {
            val rule = assertNotNull(Vp9RuleCatalog.rule(id))
            assertEquals(id, rule.id)
            assertNotNull(rule.citation)
            assertTrue(rule.scope.contains("vp09"))
        }
        assertNull(Vp9RuleCatalog.rule("vp9.unknown.rule"))
    }

    @Test
    fun vpccBoxDecoderParsesConfigurationRecord() {
        val file = fixture("default-vp09.mp4")
        val root = parseFile(file)
        val vpcc = com.multiviewer.parser.boxDescendants(root).firstOrNull { it.type == "vpcC" }
        assertNotNull(vpcc)
        assertTrue(vpcc.warnings.isEmpty())

        fun field(name: String) = vpcc.fields.first { it.name == name }.value
        assertEquals("0", field("profile"))
        assertEquals("10", field("level"))
        assertEquals("8", field("bit_depth"))
        assertEquals("1", field("chroma_subsampling"))
        assertEquals("0", field("video_full_range_flag"))
    }
}
