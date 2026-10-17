package com.multiviewer.parser.conformance

import com.multiviewer.parser.AudioSample
import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.boxDescendants
import com.multiviewer.parser.buildAudioSampleTable
import com.multiviewer.parser.parseFile
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpusSampleScannerTest {
    private fun fixture(name: String) = File(javaClass.getResource("/opus-conformance/$name")!!.toURI())

    @Test
    fun defaultOpusParsesDopsAndFirstSample() {
        val file = fixture("default-opus.mp4")
        val root = parseFile(file)
        val dops = assertNotNull(boxDescendants(root).firstOrNull { it.type == "dOps" })
        ByteReader.open(file).use { reader ->
            val config = parseOpusConfig(reader, dops)
            assertNull(config.issue)
            assertEquals(0, config.version)
            assertEquals(1, config.channelCount)
            assertEquals(312, config.preSkip)
            assertEquals(48000L, config.sampleRate)
            assertEquals(0, config.gain)
            assertEquals(0, config.mappingFamily)

            val table = assertNotNull(buildAudioSampleTable(reader, root))
            val obs = scanOpusSample(reader, table.samples[0], config.channelCount ?: 1)
            assertNull(obs.issue)
            assertEquals(31, obs.tocConfig)
            assertEquals(false, obs.isStereo)
            assertEquals(0, obs.frameCountCode)
            assertEquals(1, obs.frameCount)
        }
    }

    @Test
    fun badDopsVersionDetectsNonZeroVersion() {
        val file = fixture("bad-dops-version.mp4")
        val root = parseFile(file)
        val dops = assertNotNull(boxDescendants(root).firstOrNull { it.type == "dOps" })
        ByteReader.open(file).use { reader ->
            val config = parseOpusConfig(reader, dops)
            assertEquals(1, config.version)
            assertNotNull(config.issue)
            assertTrue(config.issue!!.contains("version 1"))
        }
    }

    @Test
    fun badChannelCountDetectsZero() {
        val file = fixture("bad-channel-count.mp4")
        val root = parseFile(file)
        val dops = assertNotNull(boxDescendants(root).firstOrNull { it.type == "dOps" })
        ByteReader.open(file).use { reader ->
            val config = parseOpusConfig(reader, dops)
            assertEquals(0, config.channelCount)
            assertNotNull(config.issue)
            assertTrue(config.issue!!.contains("channel", ignoreCase = true))
        }
    }

    @Test
    fun badMonoStereoFlagDetectsStereoInMonoTrack() {
        val file = fixture("bad-mono-stereo-flag.mp4")
        val root = parseFile(file)
        ByteReader.open(file).use { reader ->
            val table = assertNotNull(buildAudioSampleTable(reader, root))
            val obs = scanOpusSample(reader, table.samples[0], 1)
            assertEquals(true, obs.isStereo)
            assertNotNull(obs.issue)
            assertTrue(obs.issue!!.contains("stereo"))
        }
    }

    @Test
    fun badCode3ZeroFramesDetectsForbiddenMZero() {
        val file = fixture("bad-code3-zero-frames.mp4")
        val root = parseFile(file)
        ByteReader.open(file).use { reader ->
            val table = assertNotNull(buildAudioSampleTable(reader, root))
            val obs = scanOpusSample(reader, table.samples[0], 1)
            assertEquals(3, obs.frameCountCode)
            assertEquals(0, obs.frameCount)
            assertNotNull(obs.issue)
            assertTrue(obs.issue!!.contains("M=0") || obs.issue!!.contains("zero"))
        }
    }

    @Test
    fun truncatedSampleReportsExtentIssue() {
        val file = fixture("truncated-sample.mp4")
        val root = parseFile(file)
        ByteReader.open(file).use { reader ->
            val table = assertNotNull(buildAudioSampleTable(reader, root))
            val lastSample = table.samples.last()
            val obs = scanOpusSample(reader, lastSample, 1)
            assertNotNull(obs.issue)
            assertTrue(obs.issue!!.contains("bounds"))
        }
    }

    @Test
    fun sampleExtentOverflowDoesNotCrash() {
        val file = fixture("default-opus.mp4")
        ByteReader.open(file).use { reader ->
            val overflowSample = AudioSample(
                index = 999,
                offset = Long.MAX_VALUE - 100,
                size = 200,
                decodeTime = 0,
            )
            val obs = scanOpusSample(reader, overflowSample, 1)
            assertNotNull(obs.issue)
            assertTrue(obs.issue!!.contains("bounds"))
        }
    }
}
