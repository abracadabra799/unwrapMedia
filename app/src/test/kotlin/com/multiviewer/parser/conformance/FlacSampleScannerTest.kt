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

class FlacSampleScannerTest {
    private fun fixture(name: String) = File(javaClass.getResource("/flac-conformance/$name")!!.toURI())

    @Test
    fun defaultFlacParsesDflaAndFirstSample() {
        val file = fixture("default-flac.mp4")
        val root = parseFile(file)
        val dfla = assertNotNull(boxDescendants(root).firstOrNull { it.type == "dfLa" })
        ByteReader.open(file).use { reader ->
            val config = parseFlacConfig(reader, dfla)
            assertNull(config.issue)
            assertEquals(0, config.version)
            assertEquals(4608, config.minBlockSize)
            assertEquals(4608, config.maxBlockSize)
            assertEquals(44100L, config.sampleRate)
            assertEquals(1, config.channels)
            assertEquals(16, config.bitsPerSample)

            val table = assertNotNull(buildAudioSampleTable(reader, root))
            val obs = scanFlacSample(reader, table.samples[0], config)
            assertNull(obs.issue)
            assertTrue(obs.syncValid)
            assertTrue(obs.headerCrcValid)
            assertTrue(obs.frameCrcValid)
        }
    }

    @Test
    fun badDflaVersionDetectsNonZeroVersion() {
        val file = fixture("bad-dfla-version.mp4")
        val root = parseFile(file)
        val dfla = assertNotNull(boxDescendants(root).firstOrNull { it.type == "dfLa" })
        ByteReader.open(file).use { reader ->
            val config = parseFlacConfig(reader, dfla)
            assertEquals(1, config.version)
            assertNotNull(config.issue)
            assertTrue(config.issue!!.contains("version 1"))
        }
    }

    @Test
    fun badStreaminfoSizeDetectsWrongSize() {
        val file = fixture("bad-streaminfo-size.mp4")
        val root = parseFile(file)
        val dfla = assertNotNull(boxDescendants(root).firstOrNull { it.type == "dfLa" })
        ByteReader.open(file).use { reader ->
            val config = parseFlacConfig(reader, dfla)
            assertNotNull(config.issue)
            assertTrue(config.issue!!.contains("32") || config.issue!!.contains("STREAMINFO"))
        }
    }

    @Test
    fun badFrameSyncDetectsCorruptedSyncword() {
        val file = fixture("bad-frame-sync.mp4")
        val root = parseFile(file)
        ByteReader.open(file).use { reader ->
            val table = assertNotNull(buildAudioSampleTable(reader, root))
            val config = parseFlacConfig(reader, boxDescendants(root).first { it.type == "dfLa" })
            val obs = scanFlacSample(reader, table.samples[0], config)
            assertFalse(obs.syncValid)
            assertNotNull(obs.issue)
            assertTrue(obs.issue!!.contains("sync", ignoreCase = true))
        }
    }

    @Test
    fun badFrameCrc8DetectsHeaderCrcMismatch() {
        val file = fixture("bad-frame-crc8.mp4")
        val root = parseFile(file)
        ByteReader.open(file).use { reader ->
            val table = assertNotNull(buildAudioSampleTable(reader, root))
            val config = parseFlacConfig(reader, boxDescendants(root).first { it.type == "dfLa" })
            val obs = scanFlacSample(reader, table.samples[0], config)
            assertFalse(obs.headerCrcValid)
            assertNotNull(obs.issue)
            assertTrue(obs.issue!!.contains("CRC-8") || obs.issue!!.contains("CRC"))
        }
    }

    @Test
    fun badFrameCrc16DetectsPayloadCrcMismatch() {
        val file = fixture("bad-frame-crc16.mp4")
        val root = parseFile(file)
        ByteReader.open(file).use { reader ->
            val table = assertNotNull(buildAudioSampleTable(reader, root))
            val config = parseFlacConfig(reader, boxDescendants(root).first { it.type == "dfLa" })
            val obs = scanFlacSample(reader, table.samples[0], config)
            assertFalse(obs.frameCrcValid)
            assertNotNull(obs.issue)
            assertTrue(obs.issue!!.contains("CRC-16") || obs.issue!!.contains("CRC"))
        }
    }

    @Test
    fun truncatedSampleReportsExtentIssue() {
        val file = fixture("truncated-sample.mp4")
        val root = parseFile(file)
        ByteReader.open(file).use { reader ->
            val table = assertNotNull(buildAudioSampleTable(reader, root))
            val config = parseFlacConfig(reader, boxDescendants(root).first { it.type == "dfLa" })
            val lastSample = table.samples.last()
            val obs = scanFlacSample(reader, lastSample, config)
            assertNotNull(obs.issue)
            assertTrue(obs.issue!!.contains("bounds"))
        }
    }

    @Test
    fun sampleExtentOverflowDoesNotCrash() {
        val file = fixture("default-flac.mp4")
        ByteReader.open(file).use { reader ->
            val overflowSample = AudioSample(
                index = 999,
                offset = Long.MAX_VALUE - 100,
                size = 200,
                decodeTime = 0,
            )
            val config = FlacConfigObservation(0, 0)
            val obs = scanFlacSample(reader, overflowSample, config)
            assertNotNull(obs.issue)
            assertTrue(obs.issue!!.contains("bounds"))
        }
    }
}
