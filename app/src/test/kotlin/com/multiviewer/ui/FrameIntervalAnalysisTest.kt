package com.multiviewer.ui

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrameIntervalAnalysisTest {
    private fun assertClose(actual: Double, expected: Double) =
        assertTrue(abs(actual - expected) < 0.001, "expected $expected, got $actual")

    @Test
    fun `computes zero diff for perfectly regular frame spacing`() {
        val frames = listOf(
            FrameInfo(0, 'I', 100, 0.0),
            FrameInfo(1, 'P', 80, 0.1),
            FrameInfo(2, 'P', 80, 0.2),
            FrameInfo(3, 'P', 80, 0.3),
        )
        val result = computeFrameIntervals(frames)
        assertEquals(3, result.size)
        for (interval in result) {
            assertClose(interval.intervalMs, 100.0)
            assertClose(interval.intervalDiffMs, 0.0)
        }
    }

    @Test
    fun `flags an irregular gap with a nonzero interval diff`() {
        val frames = listOf(
            FrameInfo(0, 'I', 100, 0.0),
            FrameInfo(1, 'P', 80, 0.1),
            FrameInfo(2, 'P', 80, 0.2),
            FrameInfo(3, 'P', 80, 0.5),
            FrameInfo(4, 'P', 80, 0.6),
        )
        val result = computeFrameIntervals(frames)
        assertEquals(4, result.size)
        assertClose(result[0].intervalMs, 100.0)
        assertClose(result[1].intervalMs, 100.0)
        assertClose(result[2].intervalMs, 300.0)
        assertClose(result[2].intervalDiffMs, 200.0)
        assertClose(result[3].intervalMs, 100.0)
        assertClose(result[3].intervalDiffMs, -200.0)
    }

    @Test
    fun `excludes the first frame since it has no preceding interval`() {
        val frames = listOf(
            FrameInfo(0, 'I', 100, 0.0),
            FrameInfo(1, 'P', 80, 0.05),
        )
        val result = computeFrameIntervals(frames)
        assertEquals(1, result.size)
        assertEquals(1, result[0].frameIndex)
    }

    @Test
    fun `carries the frame type through for graph coloring`() {
        val frames = listOf(
            FrameInfo(0, 'I', 100, 0.0),
            FrameInfo(1, 'B', 80, 0.1),
        )
        val result = computeFrameIntervals(frames)
        assertEquals('B', result[0].type)
    }

    @Test
    fun `returns an empty list for fewer than two frames`() {
        assertEquals(emptyList(), computeFrameIntervals(emptyList()))
        assertEquals(emptyList(), computeFrameIntervals(listOf(FrameInfo(0, 'I', 100, 0.0))))
    }
}
