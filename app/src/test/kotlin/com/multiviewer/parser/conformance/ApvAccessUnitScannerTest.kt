package com.multiviewer.parser.conformance

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.VideoSample
import com.multiviewer.parser.buildVideoSampleTable
import com.multiviewer.parser.parseFile
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApvAccessUnitScannerTest {
    private fun fixture(name: String) = File(javaClass.getResource("/apv-conformance/$name")!!.toURI())

    @Test
    fun defaultApv1SampleIsParsed() {
        val file = fixture("default-apv1.mp4")
        val root = parseFile(file)
        ByteReader.open(file).use { reader ->
            val table = assertNotNull(buildVideoSampleTable(reader, root))
            val sample = table.samples.first()
            val obs = scanApvSample(reader, sample)
            assertNull(obs.issue)
            assertEquals(0x61507631, obs.signature)
            assertEquals(1, obs.pbuType)
            assertEquals(0, obs.pbuReservedZero)
            assertEquals(33, obs.profileIdc)
            assertEquals(123, obs.levelIdc)
            assertEquals(2, obs.bandIdc)
            assertEquals(0, obs.reservedZero5bits)
            assertEquals(3840, obs.frameWidth)
            assertEquals(2160, obs.frameHeight)
            assertEquals(2, obs.chromaFormatIdc)
            assertEquals(10, obs.bitDepth)
            assertEquals(0, obs.frameInfoReservedZero)
            assertEquals(0, obs.frameHeaderReservedZero)
        }
    }

    @Test
    fun badSignatureIsDetected() {
        val file = fixture("bad-signature.mp4")
        val root = parseFile(file)
        ByteReader.open(file).use { reader ->
            val table = assertNotNull(buildVideoSampleTable(reader, root))
            val obs = scanApvSample(reader, table.samples.first())
            assertNotNull(obs.issue)
            assertEquals(0x58585858, obs.signature) // 'XXXX'
        }
    }

    @Test
    fun badPbuReservedIsDetected() {
        val file = fixture("bad-pbu-reserved.mp4")
        val root = parseFile(file)
        ByteReader.open(file).use { reader ->
            val table = assertNotNull(buildVideoSampleTable(reader, root))
            val obs = scanApvSample(reader, table.samples.first())
            assertEquals(0xFF, obs.pbuReservedZero)
        }
    }

    @Test
    fun badBandReservedIsDetected() {
        val file = fixture("bad-band-reserved.mp4")
        val root = parseFile(file)
        ByteReader.open(file).use { reader ->
            val table = assertNotNull(buildVideoSampleTable(reader, root))
            val obs = scanApvSample(reader, table.samples.first())
            assertEquals(7, obs.bandIdc)
            assertEquals(31, obs.reservedZero5bits)
        }
    }

    @Test
    fun truncatedSampleReportsIssue() {
        val file = fixture("truncated-sample.mp4")
        val root = parseFile(file)
        ByteReader.open(file).use { reader ->
            val table = assertNotNull(buildVideoSampleTable(reader, root))
            val obs = scanApvSample(reader, table.samples.first())
            assertNotNull(obs.issue)
        }
    }

    @Test
    fun sampleExtentOverflowDoesNotWrap() {
        val file = fixture("default-apv1.mp4")
        ByteReader.open(file).use { reader ->
            val overflowSample = VideoSample(
                index = 0,
                offset = Long.MAX_VALUE - 10,
                size = 100,
                decodeTime = 0,
                compositionOffset = 0,
                isSync = true,
            )
            val obs = scanApvSample(reader, overflowSample)
            assertNotNull(obs.issue)
            assertTrue(obs.issue!!.contains("bounds"))
        }
    }
}
