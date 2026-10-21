package com.multiviewer.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GopAnalysisViewTest {

    @Test
    fun `computeGopSpans partitions frames by I-frame boundaries and counts frame types`() {
        val frames = listOf(
            FrameInfo(0, 'I', 10_000, 0.00),
            FrameInfo(1, 'B', 1_000, 0.04),
            FrameInfo(2, 'P', 3_000, 0.08),
            FrameInfo(3, 'I', 12_000, 0.12),
            FrameInfo(4, 'P', 4_000, 0.16),
        )
        val spans = computeGopSpans(frames)
        assertEquals(2, spans.size)

        val gop1 = spans[0]
        assertEquals(1, gop1.gopNumber)
        assertEquals(0, gop1.startFrameIndex)
        assertEquals(2, gop1.endFrameIndex)
        assertEquals(3, gop1.frameCount)
        assertEquals(1, gop1.iCount)
        assertEquals(1, gop1.pCount)
        assertEquals(1, gop1.bCount)
        assertEquals(14_000L, gop1.totalBytes)
        assertEquals(0.12, gop1.durationSeconds, 1e-6)

        val gop2 = spans[1]
        assertEquals(2, gop2.gopNumber)
        assertEquals(3, gop2.startFrameIndex)
        assertEquals(4, gop2.endFrameIndex)
        assertEquals(2, gop2.frameCount)
        assertEquals(1, gop2.iCount)
        assertEquals(1, gop2.pCount)
        assertEquals(0, gop2.bCount)
        assertEquals(16_000L, gop2.totalBytes)

        assertEquals(gop1, findGopSpanForFrame(spans, 1))
        assertEquals(gop2, findGopSpanForFrame(spans, 4))
        assertNull(findGopSpanForFrame(spans, 99))
    }

    @Test
    fun `resolveActiveGopFrameIndex follows live playback PTS when playing and selectedFrame when paused`() {
        val frames = listOf(
            FrameInfo(0, 'I', 10_000, 0.00),
            FrameInfo(1, 'P', 2_000, 0.05),
            FrameInfo(2, 'P', 2_000, 0.10),
            FrameInfo(3, 'I', 10_000, 0.15),
        )

        // Paused with selectedFrame = #1 -> returns 1
        assertEquals(
            1,
            resolveActiveGopFrameIndex(frames, selectedFrame = frames[1], currentVideoPtsSeconds = 0.14, isPlaying = false),
        )

        // Playing with currentVideoPtsSeconds = 0.14 -> follows live PTS (#3 at 0.15s)
        val activePlaying = resolveActiveGopFrameIndex(frames, selectedFrame = frames[1], currentVideoPtsSeconds = 0.14, isPlaying = true)
        assertNotNull(activePlaying)
        assertEquals(3, activePlaying)
    }
}
