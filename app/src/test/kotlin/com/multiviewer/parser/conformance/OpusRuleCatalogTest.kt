package com.multiviewer.parser.conformance

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.buildAudioSampleTable
import com.multiviewer.parser.parseFile
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpusRuleCatalogTest {
    private fun fixture(name: String) = File(javaClass.getResource("/opus-conformance/$name")!!.toURI())

    @Test
    fun stableRuleIdsAreDefinedWithMetadata() {
        val expected = listOf(
            "opus.config.dops",
            "opus.config.match",
            "opus.packet.toc",
            "opus.packet.framing",
            "opus.sample.framing",
        )
        for (id in expected) {
            val rule = assertNotNull(OpusRuleCatalog.rule(id), "Rule $id must exist")
            assertEquals(id, rule.id)
            assertTrue(rule.title.isNotBlank())
            assertTrue(rule.source.contains("structural observation"))
            assertTrue(rule.reference?.isNotBlank() == true)
            assertTrue(rule.scope.contains("Opus"))
        }
    }

    @Test
    fun buildAudioSampleTableReadsDefaultOpus() {
        val file = fixture("default-opus.mp4")
        val root = parseFile(file)
        ByteReader.open(file).use { reader ->
            val table = assertNotNull(buildAudioSampleTable(reader, root))
            assertEquals("Opus", table.format)
            assertEquals(8, table.samples.size)
            assertEquals(48000L, table.timescale)
            assertEquals(0L, table.samples[0].decodeTime)
            assertEquals(960L, table.samples[1].decodeTime)
            assertTrue(table.samples[0].offset > 0)
            assertEquals(32L, table.samples[0].size)
            assertTrue(table.warnings.isEmpty())
        }
    }
}
