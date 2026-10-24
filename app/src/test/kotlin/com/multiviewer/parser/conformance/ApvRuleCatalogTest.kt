package com.multiviewer.parser.conformance

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.VideoCodec
import com.multiviewer.parser.buildVideoSampleTable
import com.multiviewer.parser.parseFile
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApvRuleCatalogTest {
    private fun fixture(name: String) = File(javaClass.getResource("/apv-conformance/$name")!!.toURI())

    @Test
    fun verifiedByteObservationsHaveStableIdsWithoutUnverifiedNormativeClaims() {
        val expectedIds = listOf(
            "apv.au.signature",
            "apv.pbu.framing",
            "apv.pbu.reserved_zero",
            "apv.frame.profile",
            "apv.frame.reserved_zero",
            "apv.config.apvc",
        )
        for (id in expectedIds) {
            val rule = assertNotNull(ApvRuleCatalog.rule(id))
            assertEquals(id, rule.id)
            assertNotNull(rule.citation)
            assertTrue(rule.scope.contains("apv1"))
        }
        assertNull(ApvRuleCatalog.rule("apv.unknown.rule"))
    }

    @Test
    fun apv1SampleEntryAndApvCBoxAreResolved() {
        val file = fixture("default-apv1.mp4")
        val root = parseFile(file)
        val apvc = com.multiviewer.parser.boxDescendants(root).firstOrNull { it.type == "apvC" }
        assertNotNull(apvc)
        assertTrue(apvc.warnings.isEmpty())

        fun field(name: String) = apvc.fields.first { it.name == name }.value
        assertEquals("33", field("profile_idc"))
        assertEquals("123", field("level_idc"))
        assertEquals("2", field("band_idc"))
        assertEquals("3840", field("frame_width"))
        assertEquals("2160", field("frame_height"))
        assertEquals("2", field("chroma_format_idc"))
        assertEquals("10", field("bit_depth"))

        ByteReader.open(file).use { reader ->
            val table = assertNotNull(buildVideoSampleTable(reader, root))
            assertEquals(VideoCodec.APV, table.codec)
            assertNull(table.nalLengthSize)
            assertEquals(8, table.samples.size)
        }
    }
}
