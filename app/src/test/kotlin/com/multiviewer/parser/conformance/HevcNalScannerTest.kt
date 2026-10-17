package com.multiviewer.parser.conformance

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.VideoSample
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HevcNalScannerTest {
    private fun fixture(name: String) = File(javaClass.getResource("/hevc-conformance/$name")!!.toURI())
    private fun sample(size: Long = 1275) = VideoSample(0, 44, size, 0, 0, true)

    @Test fun realHvc1AndHev1HaveTheSameVerifiedFirstNalHeader() {
        for (name in listOf("default-hev1.mp4", "explicit-hvc1.mp4")) {
            val seen = mutableListOf<HevcNalObservation>()
            ByteReader.open(fixture(name)).use { scanHevcSample(it, sample(), 4, seen::add) }
            assertEquals(44L, seen.first().prefixOffset)
            assertEquals(48L, seen.first().headerOffset)
            assertEquals(20, seen.first().nalType)
            assertEquals(0, seen.first().forbiddenZeroBit)
            assertEquals(1, seen.first().temporalIdPlus1)
        }
    }

    @Test fun truncatedLengthPrefixHasExactOffset() {
        val seen = mutableListOf<HevcNalObservation>()
        val result = ByteReader.open(fixture("truncated-prefix.mp4")).use {
            scanHevcSample(it, sample(2), 4, seen::add)
        }
        assertEquals("truncated_length_prefix", result.coverageReason)
        assertEquals(44L, seen.single().prefixOffset)
        assertNull(seen.single().headerOffset)
    }

    @Test fun oversizeNalAndHeaderBitMutationsAreObservable() {
        val oversize = mutableListOf<HevcNalObservation>()
        val result = ByteReader.open(fixture("oversize-nal.mp4")).use {
            scanHevcSample(it, sample(), 4, oversize::add)
        }
        assertEquals("nal_exceeds_sample", result.coverageReason)
        assertEquals(44L, oversize.single().prefixOffset)
        val forbidden = mutableListOf<HevcNalObservation>()
        ByteReader.open(fixture("forbidden-bit.mp4")).use { scanHevcSample(it, sample(), 4, forbidden::add) }
        assertEquals(1, forbidden.first().forbiddenZeroBit)
        val temporal = mutableListOf<HevcNalObservation>()
        ByteReader.open(fixture("zero-temporal-id.mp4")).use { scanHevcSample(it, sample(), 4, temporal::add) }
        assertEquals(0, temporal.first().temporalIdPlus1)
    }

    @Test fun sampleExtentOverflowCannotWrapIntoAValidRead() {
        val seen = mutableListOf<HevcNalObservation>()
        val result = ByteReader.open(fixture("default-hev1.mp4")).use {
            scanHevcSample(it, VideoSample(0, Long.MAX_VALUE - 1, 5, 0, 0, true), 4, seen::add)
        }
        assertEquals("sample_extent_overflow", result.coverageReason)
        assertTrue(seen.isEmpty())
    }

    @Test fun zeroLengthNalIsUnverifiedNotAValidHeader() {
        val seen = mutableListOf<HevcNalObservation>()
        ByteReader.open(fixture("zero-length-nal.mp4")).use { scanHevcSample(it, sample(), 4, seen::add) }
        assertEquals("zero_length_nal", seen.first().issue)
        assertNull(seen.first().headerOffset)
    }
}
