package com.multiviewer.parser.conformance

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.VideoSample
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Av1ObuScannerTest {
    private fun fixture(name: String) = File(javaClass.getResource("/av1-conformance/$name")!!.toURI())
    private fun sample(size: Long = 958) = VideoSample(0, 48, size, 0, 0, true)

    @Test
    fun realAv01HasVerifiedFirstAndSecondObuHeaders() {
        val seen = mutableListOf<Av1ObuObservation>()
        val result = ByteReader.open(fixture("default-av01.mp4")).use {
            scanAv1Sample(it, sample(), seen::add)
        }
        assertEquals(2, seen.size)
        assertEquals(2, result.checkedObus)
        assertEquals(958L, result.consumedBytes)
        assertNull(result.coverageReason)

        // OBU 0: Sequence Header (type 1)
        val obu0 = seen[0]
        assertEquals(0, obu0.sampleIndex)
        assertEquals(0, obu0.obuIndex)
        assertEquals(48L, obu0.headerOffset)
        assertEquals(50L, obu0.payloadOffset)
        assertEquals(1, obu0.obuType)
        assertEquals(0, obu0.forbiddenBit)
        assertEquals(0, obu0.reservedBit)
        assertFalse(obu0.extensionFlag)
        assertNull(obu0.temporalId)
        assertNull(obu0.spatialId)
        assertEquals(11L, obu0.payloadSize)
        assertNull(obu0.issue)

        // OBU 1: Frame (type 6)
        val obu1 = seen[1]
        assertEquals(0, obu1.sampleIndex)
        assertEquals(1, obu1.obuIndex)
        assertEquals(61L, obu1.headerOffset)
        assertEquals(64L, obu1.payloadOffset)
        assertEquals(6, obu1.obuType)
        assertEquals(0, obu1.forbiddenBit)
        assertEquals(0, obu1.reservedBit)
        assertFalse(obu1.extensionFlag)
        assertNull(obu1.temporalId)
        assertNull(obu1.spatialId)
        assertEquals(942L, obu1.payloadSize)
        assertNull(obu1.issue)
    }

    @Test
    fun truncatedObuHasExactOffset() {
        val seen = mutableListOf<Av1ObuObservation>()
        val result = ByteReader.open(fixture("truncated-obu.mp4")).use {
            scanAv1Sample(it, sample(), seen::add)
        }
        assertEquals("obu_exceeds_sample", result.coverageReason)
        assertEquals(1, result.checkedObus)
        assertEquals(2, seen.size) // obu 0 valid, obu 1 with issue
        assertEquals("obu_exceeds_sample", seen[1].issue)
        assertEquals(61L, seen[1].headerOffset)
    }

    @Test
    fun sampleExtentOverflowCannotWrapIntoAValidRead() {
        val seen = mutableListOf<Av1ObuObservation>()
        val result = ByteReader.open(fixture("default-av01.mp4")).use {
            scanAv1Sample(it, VideoSample(0, Long.MAX_VALUE - 1, 5, 0, 0, true), seen::add)
        }
        assertEquals("sample_extent_overflow", result.coverageReason)
        assertTrue(seen.isEmpty())
    }

    @Test
    fun forbiddenBitAndReservedBitMutationsAreObservable() {
        val forbidden = mutableListOf<Av1ObuObservation>()
        ByteReader.open(fixture("forbidden-bit.mp4")).use {
            scanAv1Sample(it, sample(), forbidden::add)
        }
        assertEquals(1, forbidden.first().forbiddenBit)

        val reserved = mutableListOf<Av1ObuObservation>()
        ByteReader.open(fixture("reserved-bit.mp4")).use {
            scanAv1Sample(it, sample(), reserved::add)
        }
        assertEquals(1, reserved.first().reservedBit)
    }

    @Test
    fun extensionReservedBitsAreObservable() {
        val seen = mutableListOf<Av1ObuObservation>()
        ByteReader.open(fixture("extension-reserved-bit.mp4")).use {
            scanAv1Sample(it, sample(), seen::add)
        }
        val obu0 = seen.first()
        assertTrue(obu0.extensionFlag)
        assertEquals(7, obu0.extensionReservedBits)
        assertEquals(0, obu0.forbiddenBit)
        assertEquals(0, obu0.reservedBit)
    }

    @Test
    fun sampleOutsideFileAndTruncatedHeadersAreObserved() {
        val seen = mutableListOf<Av1ObuObservation>()
        val result = ByteReader.open(fixture("default-av01.mp4")).use {
            scanAv1Sample(it, VideoSample(0, it.length - 2, 10, 0, 0, true), seen::add)
        }
        assertEquals("sample_outside_file", result.coverageReason)
        assertTrue(seen.isEmpty())

        // 1-byte truncated header test: sample size = 0
        val zeroResult = ByteReader.open(fixture("default-av01.mp4")).use {
            scanAv1Sample(it, VideoSample(0, 48, 0, 0, 0, true), seen::add)
        }
        assertEquals(0, zeroResult.checkedObus)
        assertNull(zeroResult.coverageReason)
    }
}
