package com.multiviewer.parser.conformance

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.boxDescendants
import com.multiviewer.parser.buildVideoSampleTable
import com.multiviewer.parser.parseFile
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VvcSampleScannerTest {
    private fun fixture(name: String) = File(javaClass.getResource("/vvc-conformance/$name")!!.toURI())

    @Test
    fun defaultVvcParsesConfigAndNalHeaders() {
        val file = fixture("default-vvc.mp4")
        val root = parseFile(file)
        val vvcc = assertNotNull(boxDescendants(root).firstOrNull { it.type == "vvcC" })
        ByteReader.open(file).use { reader ->
            val config = parseVvcConfig(reader, vvcc)
            assertNull(config.issue)
            assertEquals(0, config.version)
            assertEquals(3, config.lengthSizeMinusOne)
            assertTrue(config.ptlPresent)
            assertEquals(1920, config.maxPictureWidth)
            assertEquals(1080, config.maxPictureHeight)

            val table = assertNotNull(buildVideoSampleTable(reader, root))
            assertEquals(8, table.samples.size)

            val obs0 = scanVvcSample(reader, table.samples[0], table.nalLengthSize!!)
            assertNull(obs0.issue)
            assertEquals(1, obs0.nals.size)
            val nal0 = obs0.nals[0]
            assertFalse(nal0.forbiddenZeroBit)
            assertFalse(nal0.reservedZeroBit)
            assertEquals(7, nal0.nalUnitType) // IDR_W_RADL
            assertEquals(1, nal0.temporalIdPlus1)

            val obs1 = scanVvcSample(reader, table.samples[1], table.nalLengthSize!!)
            assertNull(obs1.issue)
            assertEquals(1, obs1.nals.size)
            val nal1 = obs1.nals[0]
            assertFalse(nal1.forbiddenZeroBit)
            assertFalse(nal1.reservedZeroBit)
            assertEquals(0, nal1.nalUnitType) // TRAIL_NUT
            assertEquals(1, nal1.temporalIdPlus1)
        }
    }

    @Test
    fun badVvccVersionDetected() {
        val file = fixture("bad-vvcc-box.mp4")
        val root = parseFile(file)
        val vvcc = assertNotNull(boxDescendants(root).firstOrNull { it.type == "vvcC" })
        ByteReader.open(file).use { reader ->
            val config = parseVvcConfig(reader, vvcc)
            assertNotNull(config.issue)
            assertTrue(config.issue!!.contains("version 1"))
        }
    }

    @Test
    fun badForbiddenBitDetected() {
        val file = fixture("bad-forbidden-bit.mp4")
        val root = parseFile(file)
        ByteReader.open(file).use { reader ->
            val table = assertNotNull(buildVideoSampleTable(reader, root))
            val obs = scanVvcSample(reader, table.samples[0], table.nalLengthSize!!)
            assertNull(obs.issue)
            assertTrue(obs.nals[0].forbiddenZeroBit)
        }
    }

    @Test
    fun badReservedBitDetected() {
        val file = fixture("bad-reserved-bit.mp4")
        val root = parseFile(file)
        ByteReader.open(file).use { reader ->
            val table = assertNotNull(buildVideoSampleTable(reader, root))
            val obs = scanVvcSample(reader, table.samples[0], table.nalLengthSize!!)
            assertNull(obs.issue)
            assertTrue(obs.nals[0].reservedZeroBit)
        }
    }

    @Test
    fun badTemporalIdDetected() {
        val file = fixture("bad-temporal-id.mp4")
        val root = parseFile(file)
        ByteReader.open(file).use { reader ->
            val table = assertNotNull(buildVideoSampleTable(reader, root))
            val obs = scanVvcSample(reader, table.samples[0], table.nalLengthSize!!)
            assertNull(obs.issue)
            assertEquals(0, obs.nals[0].temporalIdPlus1)
        }
    }

    @Test
    fun truncatedSampleDetected() {
        val file = fixture("truncated-sample.mp4")
        val root = parseFile(file)
        ByteReader.open(file).use { reader ->
            val table = assertNotNull(buildVideoSampleTable(reader, root))
            val lastSample = table.samples.last()
            val obs = scanVvcSample(reader, lastSample, table.nalLengthSize!!)
            assertNotNull(obs.issue)
            assertTrue(obs.issue!!.contains("bounds") || obs.issue!!.contains("length"))
        }
    }
}
