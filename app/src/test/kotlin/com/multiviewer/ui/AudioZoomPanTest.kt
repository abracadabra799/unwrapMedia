package com.multiviewer.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test
    fun `pageScrollView returns null while the cursor is inside the visible span`() {
        assertNull(pageScrollView(cursorSeconds = 12.0, view = AudioViewWindow(10.0, 5.0), totalDurationSeconds = 120.0))
    }

    @Test
    fun `pageScrollView pages forward when the cursor passes 90 percent of the span`() {
        // view 10..15, 90% edge at 14.5; cursor 14.6 -> page so cursor sits at 10% from the left
        val w = pageScrollView(cursorSeconds = 14.6, view = AudioViewWindow(10.0, 5.0), totalDurationSeconds = 120.0)!!
        assertEquals(14.6 - 0.5, w.startSeconds, 1e-9) // cursor - span*0.1
        assertEquals(5.0, w.durationSeconds, 1e-9)
    }

    @Test
    fun `pageScrollView pages back when the cursor is left of the span`() {
        val w = pageScrollView(cursorSeconds = 3.0, view = AudioViewWindow(10.0, 5.0), totalDurationSeconds = 120.0)!!
        assertEquals(3.0 - 0.5, w.startSeconds, 1e-9)
    }

    @Test
    fun `pageScrollView returns null at 100 percent zoom`() {
        assertNull(pageScrollView(cursorSeconds = 50.0, view = AudioViewWindow(0.0, 120.0), totalDurationSeconds = 120.0))
    }

    @Test
    fun `zoomAround keeps the anchor time under the same fraction of the view`() {
        // anchor 12.0 is at fraction 0.4 of view 10..15; after zooming to span 2.0 it must still be at 0.4 -> start 11.2
        val w = zoomAround(view = AudioViewWindow(10.0, 5.0), anchorSeconds = 12.0, newSpanSeconds = 2.0, totalDurationSeconds = 120.0)
        assertEquals(11.2, w.startSeconds, 1e-9)
        assertEquals(2.0, w.durationSeconds, 1e-9)
    }

    @Test
    fun `zoomAround clamps at the track end`() {
        // anchor near the left of the view (frac 0.1) + a wide new span pushes newEnd past the
        // track end, so clampWindow pins startSeconds to total - span.
        val w = zoomAround(view = AudioViewWindow(114.0, 10.0), anchorSeconds = 115.0, newSpanSeconds = 10.0, totalDurationSeconds = 120.0)
        assertEquals(110.0, w.startSeconds, 1e-9) // total - span
        assertEquals(10.0, w.durationSeconds, 1e-9)
    }

    @Test
    fun `zoomPercent is 100 at full span and scales inversely`() {
        assertEquals(100, zoomPercent(AudioViewWindow(0.0, 120.0), 120.0))
        assertEquals(250, zoomPercent(AudioViewWindow(0.0, 48.0), 120.0))
    }
}
