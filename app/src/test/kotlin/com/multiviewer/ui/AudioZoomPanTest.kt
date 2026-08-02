package com.multiviewer.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class AudioZoomPanTest {
    @Test
    fun `clampWindow leaves an already-valid window unchanged`() {
        val window = clampWindow(requestedStart = 10.0, requestedDuration = 5.0, totalDuration = 60.0)
        assertEquals(AudioViewWindow(10.0, 5.0), window)
    }

    @Test
    fun `clampWindow enforces the minimum duration`() {
        val window = clampWindow(requestedStart = 10.0, requestedDuration = 0.1, totalDuration = 60.0)
        assertEquals(MIN_VISIBLE_DURATION_SECONDS, window.durationSeconds)
    }

    @Test
    fun `clampWindow caps duration at the total track length`() {
        val window = clampWindow(requestedStart = 0.0, requestedDuration = 999.0, totalDuration = 60.0)
        assertEquals(60.0, window.durationSeconds)
    }

    @Test
    fun `clampWindow prevents the window from extending past the end of the track`() {
        val window = clampWindow(requestedStart = 58.0, requestedDuration = 10.0, totalDuration = 60.0)
        assertEquals(50.0, window.startSeconds)
        assertEquals(10.0, window.durationSeconds)
    }

    @Test
    fun `clampWindow prevents a negative start`() {
        val window = clampWindow(requestedStart = -5.0, requestedDuration = 10.0, totalDuration = 60.0)
        assertEquals(0.0, window.startSeconds)
    }
}
