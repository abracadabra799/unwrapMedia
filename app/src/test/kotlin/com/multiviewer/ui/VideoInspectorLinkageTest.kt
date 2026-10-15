package com.multiviewer.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VideoInspectorLinkageTest {
    private val frames = listOf(
        FrameInfo(0, 'I', 1000, 0.0),
        FrameInfo(1, 'P', 500, 0.033),
        FrameInfo(2, 'B', 200, 0.066),
        FrameInfo(3, 'P', 600, 0.099),
    )

    @Test
    fun `frame position maps 1-based frame number to 0-based frame info`() {
        val pos = FramePosition(number = 2, total = 4, ptsSeconds = 0.033)
        val selected = frames.getOrNull(pos.number - 1)
        assertEquals(frames[1], selected)
    }

    @Test
    fun `frame position out of bounds returns null`() {
        val outOfBounds = FramePosition(number = 5, total = 4, ptsSeconds = 0.200)
        val selected = frames.getOrNull(outOfBounds.number - 1)
        assertNull(selected)

        val invalidZero = FramePosition(number = 0, total = 4, ptsSeconds = 0.0)
        assertNull(frames.getOrNull(invalidZero.number - 1))
    }

    @Test
    fun `empty frames list handles frame position safely`() {
        val emptyFrames = emptyList<FrameInfo>()
        val pos = FramePosition(number = 1, total = 1, ptsSeconds = 0.0)
        assertNull(emptyFrames.getOrNull(pos.number - 1))
    }
}
