package com.multiviewer.parser.conformance

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.buildAudioSampleTable
import com.multiviewer.parser.parseFile
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AacRuleCatalogTest {
    private fun fixture(name: String) = File(javaClass.getResource("/aac-conformance/$name")!!.toURI())

    @Test
    fun stableRuleIdsAreDefinedWithMetadata() {
        val expected = listOf(
            "aac.config.esds",
            "aac.config.asc",
            "aac.config.match",
            "aac.frame.adts_guard",
            "aac.frame.element",
            "aac.frame.ics_reserved",
            "aac.sample.framing",
        )
        for (id in expected) {
            val rule = assertNotNull(AacRuleCatalog.rule(id), "Rule $id must exist")
            assertEquals(id, rule.id)
            assertTrue(rule.title.isNotBlank())
            assertTrue(rule.source.contains("structural observation"))
            assertTrue(rule.reference?.isNotBlank() == true)
            assertTrue(rule.scope.contains("mp4a"))
        }
    }

    @Test
    fun buildAudioSampleTableReadsDefaultMp4a() {
        val file = fixture("default-mp4a.m4a")
        val root = parseFile(file)
        ByteReader.open(file).use { reader ->
            val table = assertNotNull(buildAudioSampleTable(reader, root))
            assertEquals("mp4a", table.format)
            assertEquals(8, table.samples.size)
            assertEquals(44100L, table.timescale)
            assertEquals(0L, table.samples[0].decodeTime)
            assertEquals(1024L, table.samples[1].decodeTime)
            assertTrue(table.samples[0].offset > 0)
            assertEquals(32L, table.samples[0].size)
            assertTrue(table.warnings.isEmpty())
        }
    }
}
