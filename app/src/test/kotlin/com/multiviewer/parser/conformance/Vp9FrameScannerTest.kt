package com.multiviewer.parser.conformance

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.VideoSample
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Vp9FrameScannerTest {
    private fun fixture(name: String) = File(javaClass.getResource("/vp9-conformance/$name")!!.toURI())
    private fun sample(offset: Long = 44, size: Long = 1692) = VideoSample(0, offset, size, 0, 0, true)

    @Test
    fun realVp09HasVerifiedFirstAndSecondFrameHeaders() {
        val seen = mutableListOf<Vp9FrameObservation>()
        val result = ByteReader.open(fixture("default-vp09.mp4")).use {
            scanVp9Sample(it, sample(), seen::add)
        }
        assertEquals(1, seen.size)
        assertEquals(1, result.checkedFrames)
        assertEquals(1692L, result.consumedBytes)
        assertNull(result.coverageReason)

        // Sample 0, Frame 0: KEY_FRAME
        val f0 = seen[0]
        assertEquals(0, f0.sampleIndex)
        assertEquals(0, f0.frameIndex)
        assertEquals(44L, f0.offset)
        assertEquals(1692L, f0.size)
        assertEquals(2, f0.frameMarker)
        assertEquals(0, f0.profile)
        assertFalse(f0.showExistingFrame)
        assertEquals(0, f0.frameType) // 0 = KEY_FRAME
        assertEquals(true, f0.showFrame)
        assertEquals(false, f0.errorResilientMode)
        assertEquals(0x498342, f0.syncCode)
        assertEquals(8, f0.bitDepth)
        assertEquals(0, f0.colorSpace)
        assertEquals(64, f0.width)
        assertEquals(64, f0.height)
        assertNull(f0.profileReservedBit)
        assertNull(f0.issue)

        // Sample 1: NON_KEY_FRAME
        val seen1 = mutableListOf<Vp9FrameObservation>()
        val result1 = ByteReader.open(fixture("default-vp09.mp4")).use {
            scanVp9Sample(it, VideoSample(1, 1736, 355, 1, 0, false), seen1::add)
        }
        assertEquals(1, seen1.size)
        val f1 = seen1[0]
        assertEquals(1, f1.sampleIndex)
        assertEquals(2, f1.frameMarker)
        assertEquals(0, f1.profile)
        assertEquals(1, f1.frameType) // 1 = NON_KEY_FRAME
        assertEquals(true, f1.showFrame)
        assertNull(f1.syncCode)
    }

    @Test
    fun badMarkerIsObserved() {
        val seen = mutableListOf<Vp9FrameObservation>()
        ByteReader.open(fixture("bad-marker.mp4")).use {
            scanVp9Sample(it, sample(), seen::add)
        }
        assertEquals(0, seen.first().frameMarker)
    }

    @Test
    fun badSyncCodeIsObserved() {
        val seen = mutableListOf<Vp9FrameObservation>()
        ByteReader.open(fixture("bad-sync-code.mp4")).use {
            scanVp9Sample(it, sample(), seen::add)
        }
        assertEquals(0x008342, seen.first().syncCode)
    }

    @Test
    fun profile3ReservedBitIsObserved() {
        val seen = mutableListOf<Vp9FrameObservation>()
        ByteReader.open(fixture("profile3-reserved-bit.mp4")).use {
            scanVp9Sample(it, sample(), seen::add)
        }
        val f = seen.first()
        assertEquals(3, f.profile)
        assertEquals(1, f.profileReservedBit)
    }

    @Test
    fun truncatedFrameHasExactOffset() {
        val seen = mutableListOf<Vp9FrameObservation>()
        val result = ByteReader.open(fixture("truncated-frame.mp4")).use {
            scanVp9Sample(it, sample(44, 2), seen::add)
        }
        assertEquals("truncated_frame_header", result.coverageReason)
        assertEquals(1, seen.size)
        assertEquals("truncated_frame_header", seen.first().issue)
        assertEquals(44L, seen.first().offset)
    }

    @Test
    fun sampleExtentOverflowCannotWrapIntoAValidRead() {
        val seen = mutableListOf<Vp9FrameObservation>()
        val result = ByteReader.open(fixture("default-vp09.mp4")).use {
            scanVp9Sample(it, VideoSample(0, Long.MAX_VALUE - 1, 5, 0, 0, true), seen::add)
        }
        assertEquals("sample_extent_overflow", result.coverageReason)
        assertTrue(seen.isEmpty())
    }

    @Test
    fun sampleOutsideFileAndEmptySampleAreHandled() {
        val seen = mutableListOf<Vp9FrameObservation>()
        val result = ByteReader.open(fixture("default-vp09.mp4")).use {
            scanVp9Sample(it, VideoSample(0, it.length - 2, 10, 0, 0, true), seen::add)
        }
        assertEquals("sample_outside_file", result.coverageReason)
        assertTrue(seen.isEmpty())

        val zeroResult = ByteReader.open(fixture("default-vp09.mp4")).use {
            scanVp9Sample(it, VideoSample(0, 44, 0, 0, 0, true), seen::add)
        }
        assertEquals(0, zeroResult.checkedFrames)
        assertNull(zeroResult.coverageReason)
    }
}
