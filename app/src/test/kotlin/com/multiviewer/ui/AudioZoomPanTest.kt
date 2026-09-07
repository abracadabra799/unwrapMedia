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

    @Test
    fun `followWindow centres the playhead in the middle of a long track`() {
        val w = followWindow(displayElapsedSeconds = 30.0, windowDurationSeconds = 5.0, totalDurationSeconds = 120.0)
        assertEquals(27.5, w.startSeconds, 1e-9)
        assertEquals(5.0, w.durationSeconds, 1e-9)
    }

    @Test
    fun `followWindow pins to the start within the first half-window`() {
        val w = followWindow(displayElapsedSeconds = 1.0, windowDurationSeconds = 5.0, totalDurationSeconds = 120.0)
        assertEquals(0.0, w.startSeconds, 1e-9)
    }

    @Test
    fun `followWindow pins to the end within the last half-window`() {
        val w = followWindow(displayElapsedSeconds = 119.0, windowDurationSeconds = 5.0, totalDurationSeconds = 120.0)
        assertEquals(115.0, w.startSeconds, 1e-9) // total - duration
    }

    @Test
    fun `followWindow with a window at least as long as the track shows the whole track from zero`() {
        val w = followWindow(displayElapsedSeconds = 2.0, windowDurationSeconds = 10.0, totalDurationSeconds = 4.0)
        assertEquals(0.0, w.startSeconds, 1e-9)
        assertEquals(4.0, w.durationSeconds, 1e-9)
    }
}
