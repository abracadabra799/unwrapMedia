package com.multiviewer.parser.conformance

import com.multiviewer.parser.AudioSample
import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.buildAudioSampleTable
import com.multiviewer.parser.boxDescendants
import com.multiviewer.parser.byteReaderOf
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

    // SCE: id_syn_ele(3) element_instance_tag(4) global_gain(8), then ics_info starts with ics_reserved_bit — bit 15,
    // the low bit of byte 1. Bytes 01 62 are a real ffmpeg mono frame (global_gain=177, so bit 7 is set).
    @Test
    fun sceReservedBitIsReadAfterGlobalGain() {
        fun scan(vararg bytes: Int) = byteReaderOf(bytes.map { it.toByte() }.toByteArray()).use { reader ->
            scanAacSample(reader, AudioSample(0, 0L, bytes.size.toLong(), 0L), 1)
        }
        val ok = scan(0x01, 0x62, 0x98, 0xD8)
        assertEquals(0, ok.elementId)
        assertEquals(0, ok.icsReservedBit)
        assertNull(ok.issue)
        val bad = scan(0x01, 0x63, 0x98, 0xD8)
        assertEquals(1, bad.icsReservedBit)
        assertNotNull(bad.issue)
    }

    // CPE without common_window: id(3) tag(4) common_window(1), then the first channel's global_gain(8), then its
    // ics_info — ics_reserved_bit is bit 16 (top bit of byte 2). LFE has the SCE layout (bit 15).
    @Test
    fun cpeWithoutCommonWindowAndLfeReservedBits() {
        fun scan(channels: Int, vararg bytes: Int) = byteReaderOf(bytes.map { it.toByte() }.toByteArray()).use { reader ->
            scanAacSample(reader, AudioSample(0, 0L, bytes.size.toLong(), 0L), channels)
        }
        assertEquals(0, scan(2, 0x20, 0xB1, 0x00, 0x00).icsReservedBit)
        assertEquals(1, scan(2, 0x20, 0xB1, 0x80, 0x00).icsReservedBit)
        assertEquals(0, scan(6, 0x61, 0x62, 0x00).icsReservedBit)
        assertEquals(1, scan(6, 0x61, 0x63, 0x00).icsReservedBit)
    }

    @Test
    fun realFfmpegMonoAacHasNoReservedBitFailures() {
        val file = fixture("ffmpeg-mono.m4a")
        val root = parseFile(file)
        ByteReader.open(file).use { reader ->
            val table = assertNotNull(buildAudioSampleTable(reader, root))
            assertTrue(table.samples.size > 50)
            for (sample in table.samples) {
                val obs = scanAacSample(reader, sample, 1)
                assertNull(obs.issue, "sample ${sample.index}: ${obs.issue}")
            }
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
