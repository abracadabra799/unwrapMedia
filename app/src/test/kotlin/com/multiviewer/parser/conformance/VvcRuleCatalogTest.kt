package com.multiviewer.parser.conformance

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.VideoCodec
import com.multiviewer.parser.buildVideoSampleTable
import com.multiviewer.parser.parseFile
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VvcRuleCatalogTest {
    private fun fixture(name: String) = File(javaClass.getResource("/vvc-conformance/$name")!!.toURI())

    @Test
    fun catalogExposesVerifiedRuleIds() {
        val expected = listOf(
            "vvc.config.vvcc",
            "vvc.nal.framing",
            "vvc.nal.forbidden_zero_bit",
            "vvc.nal.reserved_zero_bit",
            "vvc.nal.temporal_id_plus1",
            "vvc.sample.framing",
        )
        for (id in expected) {
            val rule = VvcRuleCatalog.rule(id)
            assertNotNull(rule, "Rule $id should be registered in VvcRuleCatalog")
            assertTrue(rule.source.contains("structural observation", ignoreCase = true))
            assertTrue(rule.source.contains("H.266") || rule.source.contains("14496-15"))
        }
    }

    @Test
    fun sampleTableResolvesVvcCodecAndLengthSize() {
        val file = fixture("default-vvc.mp4")
        val root = parseFile(file)
        ByteReader.open(file).use { reader ->
            val table = assertNotNull(buildVideoSampleTable(reader, root))
            assertEquals(VideoCodec.VVC, table.codec)
            assertEquals(4, table.nalLengthSize)
            assertEquals(8, table.samples.size)
            assertTrue(table.warnings.isEmpty())
            assertTrue(table.samples.all { it.offset > 0 && it.size > 4 })
        }
    }
}
