package com.multiviewer.parser.hrd

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HrdBufferSimulatorTest {

    @Test
    fun `stable CBR frames maintain non-negative buffer occupancy without underflow`() {
        // Bitrate: 800,000 bps = 100,000 bytes/sec
        // 10 fps (100ms per frame), each frame is exactly 10,000 bytes
        // CPB size: 200,000 bytes (2.0 sec)
        // Initial delay: 0.5s -> initial buffer = 50,000 bytes
        val frames = (0 until 10).map { i ->
            VideoFrameItem(
                index = i,
                sizeBytes = 10_000L,
                dtsSeconds = i * 0.1,
                ptsSeconds = i * 0.1,
                isKeyframe = i == 0,
            )
        }

        val report = simulateHrdBuffer(
            frames = frames,
            bitrateBps = 800_000L,
            cpbSizeBytes = 200_000L,
            initialDelaySeconds = 0.5,
        )

        assertEquals(10, report.totalFrames)
        assertEquals(0, report.underflowCount)
        assertTrue(report.underflowFrames.isEmpty())
        assertFalse(report.points.any { it.isUnderflow })
        assertTrue(report.minOccupancyPercent > 0.0)
    }

    @Test
    fun `detects underflow when massive frame exceeds available buffer`() {
        // Initial buffer: 20,000 bytes
        // Frame 0 arrives and requires 50,000 bytes
        // -> underflow by 30,000 bytes!
        val frames = listOf(
            VideoFrameItem(index = 0, sizeBytes = 50_000L, dtsSeconds = 0.0, ptsSeconds = 0.0, isKeyframe = true),
            VideoFrameItem(index = 1, sizeBytes = 1_000L, dtsSeconds = 0.1, ptsSeconds = 0.1, isKeyframe = false),
        )

        val report = simulateHrdBuffer(
            frames = frames,
            bitrateBps = 800_000L, // 100,000 B/s
            cpbSizeBytes = 100_000L,
            initialDelaySeconds = 0.2, // 20,000 B initial
        )

        assertEquals(1, report.underflowCount)
        assertEquals(listOf(0), report.underflowFrames)
        assertTrue(report.points[0].isUnderflow)
        assertTrue(report.points[0].occupancyPercent < 0.0)
    }

    @Test
    fun `handles empty frame list gracefully`() {
        val report = simulateHrdBuffer(
            frames = emptyList(),
            bitrateBps = 1_000_000L,
            cpbSizeBytes = 500_000L,
        )
        assertEquals(0, report.totalFrames)
        assertEquals(0, report.underflowCount)
        assertEquals(0.0, report.avgOccupancyPercent)
    }
}
