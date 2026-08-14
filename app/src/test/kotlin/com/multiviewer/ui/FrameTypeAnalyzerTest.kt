package com.multiviewer.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class FrameTypeAnalyzerTest {
    private val frames = listOf(
        FrameInfo(index = 0, type = 'I', sizeBytes = 1000, ptsSeconds = 0.0),
        FrameInfo(index = 1, type = 'P', sizeBytes = 500, ptsSeconds = 0.04),
        FrameInfo(index = 2, type = 'P', sizeBytes = 400, ptsSeconds = 0.08),
        FrameInfo(index = 3, type = 'P', sizeBytes = 450, ptsSeconds = 0.12),
    )

    @Test
    fun `currentFrameIndex returns -1 before playback has started`() {
        assertEquals(-1, currentFrameIndex(frames, 0.0))
    }

    @Test
    fun `currentFrameIndex returns the last frame whose pts has passed`() {
        assertEquals(1, currentFrameIndex(frames, 0.06))
    }

    @Test
    fun `currentFrameIndex returns the last frame when playback is past the final pts`() {
        assertEquals(3, currentFrameIndex(frames, 999.0))
    }

    @Test
    fun `currentFrameIndex returns -1 for an empty frame list`() {
        assertEquals(-1, currentFrameIndex(emptyList(), 5.0))
    }
}
