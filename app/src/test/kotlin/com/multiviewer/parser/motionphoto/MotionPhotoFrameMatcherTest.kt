package com.multiviewer.parser.motionphoto

import com.multiviewer.parser.BoxField
import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.VideoCodec
import com.multiviewer.parser.VideoSample
import com.multiviewer.parser.VideoSampleTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MotionPhotoFrameMatcherTest {

    private fun createSampleTable(
        timescale: Long = 1000L, // 1 tick = 1 ms (1000 us)
        sampleDurations: List<Long> = listOf(33, 33, 34), // ~100 ms total, 3 samples
        compositionOffsets: List<Long> = listOf(0, 0, 0),
        syncSamples: Set<Int> = setOf(0), // First sample is keyframe
    ): VideoSampleTable {
        var currentDts = 0L
        val samples = sampleDurations.indices.map { i ->
            val sample = VideoSample(
                index = i,
                offset = (i * 1000).toLong(),
                size = 1000L,
                decodeTime = currentDts,
                compositionOffset = compositionOffsets.getOrElse(i) { 0L },
                isSync = i in syncSamples,
            )
            currentDts += sampleDurations[i]
            sample
        }
        return VideoSampleTable(
            codec = VideoCodec.HEVC,
            nalLengthSize = 4,
            timescale = timescale,
            samples = samples,
            warnings = emptyList(),
        )
    }

    @Test
    fun `matches exact keyframe when timestamp is at sample 0`() {
        val table = createSampleTable(timescale = 1000L, sampleDurations = listOf(33, 33, 34))
        // Sample 0: DTS 0, PTS 0 us
        val result = matchFrameToTimestamp(table, targetTimestampUs = 0L)

        assertNotNull(result)
        assertEquals(0, result.matchedSampleIndex)
        assertEquals(1, result.matchedFrameNumber)
        assertEquals(0L, result.matchedPtsUs)
        assertEquals(0L, result.deltaUs)
        assertTrue(result.isKeyframe)
        assertEquals(FrameAlignmentVerdict.EXACT_KEYFRAME, result.alignmentVerdict)
        assertEquals(0.0, result.relativePositionPercent, 0.001)
    }

    @Test
    fun `matches closest sample within frame duration when timestamp is slightly offset`() {
        val table = createSampleTable(timescale = 1000L, sampleDurations = listOf(33, 33, 34))
        // Sample 1: DTS 33 (33,000 us), CTS 0. Target: 35,000 us
        val result = matchFrameToTimestamp(table, targetTimestampUs = 35_000L)

        assertNotNull(result)
        assertEquals(1, result.matchedSampleIndex)
        assertEquals(2, result.matchedFrameNumber)
        assertEquals(33_000L, result.matchedPtsUs)
        assertEquals(-2_000L, result.deltaUs)
        assertEquals(false, result.isKeyframe)
        assertEquals(FrameAlignmentVerdict.ALIGNED_NON_KEYFRAME, result.alignmentVerdict)
    }

    @Test
    fun `accounts for composition offsets in PTS calculation`() {
        // Sample 0: DTS 0, CTS 66 -> PTS 66,000 us
        // Sample 1: DTS 33, CTS 0 -> PTS 33,000 us
        val table = createSampleTable(
            timescale = 1000L,
            sampleDurations = listOf(33, 33),
            compositionOffsets = listOf(66, 0),
            syncSamples = setOf(0),
        )

        val result = matchFrameToTimestamp(table, targetTimestampUs = 66_000L)
        assertNotNull(result)
        assertEquals(0, result.matchedSampleIndex)
        assertEquals(66_000L, result.matchedPtsUs)
        assertEquals(0L, result.deltaUs)
        assertTrue(result.isKeyframe)
        assertEquals(FrameAlignmentVerdict.EXACT_KEYFRAME, result.alignmentVerdict)
    }

    @Test
    fun `returns OUT_OF_BOUNDS when timestamp exceeds video duration`() {
        val table = createSampleTable(timescale = 1000L, sampleDurations = listOf(33, 33, 34)) // 100,000 us duration
        val result = matchFrameToTimestamp(table, targetTimestampUs = 200_000L)

        assertNotNull(result)
        assertEquals(FrameAlignmentVerdict.OUT_OF_BOUNDS, result.alignmentVerdict)
        assertEquals(2, result.matchedSampleIndex) // Matched to last frame
    }

    @Test
    fun `returns OUT_OF_BOUNDS for negative timestamp`() {
        val table = createSampleTable(timescale = 1000L, sampleDurations = listOf(33, 33, 34))
        val result = matchFrameToTimestamp(table, targetTimestampUs = -50_000L)

        assertNotNull(result)
        assertEquals(FrameAlignmentVerdict.OUT_OF_BOUNDS, result.alignmentVerdict)
        assertEquals(0, result.matchedSampleIndex) // Matched to first frame
    }

    @Test
    fun `returns null for empty sample table`() {
        val table = VideoSampleTable(
            codec = VideoCodec.H264,
            nalLengthSize = 4,
            timescale = 1000L,
            samples = emptyList(),
            warnings = emptyList(),
        )
        val result = matchFrameToTimestamp(table, targetTimestampUs = 10_000L)
        assertNull(result)
    }

    @Test
    fun `extracts presentation timestamp from Google XMP node`() {
        val xmp = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description
                    xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
                    GCamera:MotionPhoto="1"
                    GCamera:MotionPhotoPresentationTimestampUs="1234567"/>
              </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()
        val root = BoxNode(
            type = "root", offset = 0, headerSize = 0, size = 1000,
            children = listOf(
                BoxNode(
                    type = "APP1", offset = 2, headerSize = 4, size = xmp.length.toLong(),
                    fields = listOf(BoxField("xmp", xmp, 2, xmp.length.toLong())),
                ),
            ),
        )

        val timestamp = extractPresentationTimestampUs(root)
        assertEquals(1_234_567L, timestamp)
    }

    @Test
    fun `extracts presentation timestamp from QuickTime still-image-time metadata`() {
        val root = BoxNode(
            type = "root", offset = 0, headerSize = 0, size = 1000,
            children = listOf(
                BoxNode(
                    type = "moov", offset = 0, headerSize = 8, size = 500,
                    children = listOf(
                        BoxNode(
                            type = "meta", offset = 8, headerSize = 8, size = 400,
                            fields = listOf(
                                BoxField("com.apple.quicktime.live-photo.still-image-time", "1500000", 16, 8),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val timestamp = extractPresentationTimestampUs(root)
        assertEquals(1_500_000L, timestamp)
    }
}
