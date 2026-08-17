package com.multiviewer.ui

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FrameIntervalOptimizationTest {

    @Test
    fun `downsampleIntervalsForCanvas preserves min and max intervals per bucket and keeps selected frame`() {
        val largeList = (0 until 10_000).map { i ->
            val interval = if (i == 500) 100.0 else if (i == 501) 1.0 else 16.6
            FrameInterval(frameIndex = i, ptsSeconds = i * 0.0166, intervalMs = interval, intervalDiffMs = 0.0, type = 'P')
        }

        val downsampled = downsampleIntervalsForCanvas(largeList, targetWidthPx = 1000, selectedFrameIndex = 500)

        assertTrue(downsampled.size <= 2005)
        assertTrue(downsampled.any { it.frameIndex == 500 && it.intervalMs == 100.0 })
        assertTrue(downsampled.any { it.frameIndex == 501 && it.intervalMs == 1.0 })
    }

    @Test
    fun `findNearestIntervalBinary accurately finds clicked point in large dataset`() {
        val intervals = (0 until 50_000).map { i ->
            FrameInterval(frameIndex = i, ptsSeconds = i * 0.0166, intervalMs = 16.6, intervalDiffMs = 0.0, type = 'P')
        }

        val widthPx = 1000f
        val heightPx = 500f
        val targetIndex = 25_000
        val targetX = widthPx * targetIndex / 50_000f

        val targetY = heightPx - heightPx * ((16.6 - 10.0) / 15.0).toFloat()

        val nearest = findNearestIntervalBinary(
            intervals = intervals,
            tapOffset = Offset(targetX, targetY),
            widthPx = widthPx,
            heightPx = heightPx,
            minFrameIndex = 0,
            frameSpan = 50_000,
            axisMinMs = 10.0,
            axisMaxMs = 25.0,
            hitRadiusPx = 10f,
        )

        assertNotNull(nearest)
        assertEquals(targetIndex, nearest.frameIndex)
    }
}
