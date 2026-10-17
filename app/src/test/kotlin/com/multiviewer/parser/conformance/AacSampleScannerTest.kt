package com.multiviewer.parser.conformance

import com.multiviewer.parser.AudioSample
import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.buildAudioSampleTable
import com.multiviewer.parser.boxDescendants
import com.multiviewer.parser.parseFile
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AacSampleScannerTest {
    private fun fixture(name: String) = File(javaClass.getResource("/aac-conformance/$name")!!.toURI())

    @Test
    fun defaultMp4aParsesAscAndFirstSample() {
        val file = fixture("default-mp4a.m4a")
        val root = parseFile(file)
        val esds = assertNotNull(boxDescendants(root).firstOrNull { it.type == "esds" })
        ByteReader.open(file).use { reader ->
            val config = parseAacConfig(reader, esds)
            assertNull(config.issue)
            assertEquals(2, config.aot)
            assertEquals(4, config.samplingFrequencyIndex)
            assertEquals(44100, config.sampleRate)
            assertEquals(2, config.channelConfig)

            val table = assertNotNull(buildAudioSampleTable(reader, root))
            val obs = scanAacSample(reader, table.samples[0], config.channelConfig ?: 2)
            assertNull(obs.issue)
            assertFalse(obs.hasAdtsSync)
            assertEquals(1, obs.elementId) // ID_CPE
            assertEquals(0, obs.elementInstanceTag)
            assertEquals(0, obs.icsReservedBit)
        }
    }

    @Test
    fun badAdtsLeakedDetectsSyncword() {
        val file = fixture("bad-adts-leaked.m4a")
        val root = parseFile(file)
        ByteReader.open(file).use { reader ->
            val table = assertNotNull(buildAudioSampleTable(reader, root))
            val obs = scanAacSample(reader, table.samples[0], 2)
            assertTrue(obs.hasAdtsSync)
            assertNotNull(obs.issue)
        }
    }

    @Test
    fun badAscSamplingDetectsReservedIndex() {
        val file = fixture("bad-asc-sampling.m4a")
        val root = parseFile(file)
        val esds = assertNotNull(boxDescendants(root).firstOrNull { it.type == "esds" })
        ByteReader.open(file).use { reader ->
            val config = parseAacConfig(reader, esds)
            assertEquals(13, config.samplingFrequencyIndex)
            assertNotNull(config.issue)
        }
    }

    @Test
    fun badIcsReservedDetectsNonZeroBit() {
        val file = fixture("bad-ics-reserved.m4a")
        val root = parseFile(file)
        ByteReader.open(file).use { reader ->
            val table = assertNotNull(buildAudioSampleTable(reader, root))
            val obs = scanAacSample(reader, table.samples[0], 2)
            assertEquals(1, obs.icsReservedBit)
        }
    }

    @Test
    fun malformedEsdsReportsIssue() {
        val file = fixture("malformed-esds.m4a")
        val root = parseFile(file)
        val esds = assertNotNull(boxDescendants(root).firstOrNull { it.type == "esds" })
        ByteReader.open(file).use { reader ->
            val config = parseAacConfig(reader, esds)
            assertNotNull(config.issue)
        }
    }

    @Test
    fun truncatedSampleReportsExtentIssue() {
        val file = fixture("truncated-sample.m4a")
        val root = parseFile(file)
        ByteReader.open(file).use { reader ->
            val table = assertNotNull(buildAudioSampleTable(reader, root))
            val lastSample = table.samples.last()
            val obs = scanAacSample(reader, lastSample, 2)
            assertNotNull(obs.issue)
            assertTrue(obs.issue!!.contains("bounds"))
        }
    }

    @Test
    fun sampleExtentOverflowReportsIssue() {
        val file = fixture("default-mp4a.m4a")
        ByteReader.open(file).use { reader ->
            val overflowSample = AudioSample(0, Long.MAX_VALUE - 10L, 32L, 0L)
            val obs = scanAacSample(reader, overflowSample, 2)
            assertNotNull(obs.issue)
            assertTrue(obs.issue!!.contains("bounds"))
        }
    }
}
