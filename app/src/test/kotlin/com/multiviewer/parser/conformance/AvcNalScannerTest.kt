package com.multiviewer.parser.conformance

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.VideoSample
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AvcNalScannerTest {
    private fun fixture(name: String) = File(javaClass.getResource("/avc-conformance/$name")!!.toURI())
    private fun sample(size: Long = 2174) = VideoSample(0, 48, size, 0, 0, true)

    @Test fun realAvc1HasVerifiedFirstAndSecondNalHeaders() {
        val seen = mutableListOf<AvcNalObservation>()
        ByteReader.open(fixture("default-avc1.mp4")).use { scanAvcSample(it, sample(), 4, seen::add) }
        assertEquals(2, seen.size)

        // NAL 1: SEI (type 6, ref 0)
        assertEquals(48L, seen[0].prefixOffset)
        assertEquals(52L, seen[0].headerOffset)
        assertEquals(6, seen[0].nalType)
        assertEquals(0, seen[0].forbiddenZeroBit)
        assertEquals(0, seen[0].refIdc)

        // NAL 2: IDR slice (type 5, ref 3)
        assertEquals(737L, seen[1].prefixOffset)
        assertEquals(741L, seen[1].headerOffset)
        assertEquals(5, seen[1].nalType)
        assertEquals(0, seen[1].forbiddenZeroBit)
        assertEquals(3, seen[1].refIdc)
    }

    @Test fun truncatedLengthPrefixHasExactOffset() {
        val seen = mutableListOf<AvcNalObservation>()
        val result = ByteReader.open(fixture("truncated-prefix.mp4")).use {
            scanAvcSample(it, sample(2), 4, seen::add)
        }
        assertEquals("truncated_length_prefix", result.coverageReason)
        assertEquals(48L, seen.single().prefixOffset)
        assertNull(seen.single().headerOffset)
    }

    @Test fun oversizeNalAndHeaderBitMutationsAreObservable() {
        val oversize = mutableListOf<AvcNalObservation>()
        val result = ByteReader.open(fixture("oversize-nal.mp4")).use {
            scanAvcSample(it, sample(), 4, oversize::add)
        }
        assertEquals("nal_exceeds_sample", result.coverageReason)
        assertEquals(48L, oversize.single().prefixOffset)

        val forbidden = mutableListOf<AvcNalObservation>()
        ByteReader.open(fixture("forbidden-bit.mp4")).use { scanAvcSample(it, sample(), 4, forbidden::add) }
        assertEquals(1, forbidden.first().forbiddenZeroBit)

        val idrZeroRef = mutableListOf<AvcNalObservation>()
        ByteReader.open(fixture("idr-zero-ref.mp4")).use { scanAvcSample(it, sample(), 4, idrZeroRef::add) }
        assertEquals(5, idrZeroRef[1].nalType)
        assertEquals(0, idrZeroRef[1].refIdc)
    }

    @Test fun sampleExtentOverflowCannotWrapIntoAValidRead() {
        val seen = mutableListOf<AvcNalObservation>()
        val result = ByteReader.open(fixture("default-avc1.mp4")).use {
            scanAvcSample(it, VideoSample(0, Long.MAX_VALUE - 1, 5, 0, 0, true), 4, seen::add)
        }
        assertEquals("sample_extent_overflow", result.coverageReason)
        assertTrue(seen.isEmpty())
    }

    @Test fun zeroLengthNalIsUnverifiedNotAValidHeader() {
        val seen = mutableListOf<AvcNalObservation>()
        ByteReader.open(fixture("zero-length-nal.mp4")).use { scanAvcSample(it, sample(), 4, seen::add) }
        assertEquals("zero_length_nal", seen.first().issue)
        assertNull(seen.first().headerOffset)

        val onlyZero = mutableListOf<AvcNalObservation>()
        val result = ByteReader.open(fixture("zero-length-nal.mp4")).use {
            scanAvcSample(it, sample(4), 4, onlyZero::add)
        }
        assertEquals("zero_length_nal", result.coverageReason)
    }
}
