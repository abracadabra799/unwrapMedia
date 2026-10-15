package com.multiviewer.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FilmstripSelectionTrackerTest {
    private val first = FrameInfo(3, 'P', 100, 0.120)
    private val second = FrameInfo(4, 'B', 90, 0.160)

    @Test
    fun `only a new filmstrip request moves the open popup`() {
        val tracker = FilmstripSelectionTracker(initialRequestTick = 7)

        assertNull(tracker.consume(7, first))
        assertNull(tracker.consume(7, second)) // Popup's own selection update has no filmstrip request.
        assertEquals(second, tracker.consume(8, second))
        assertNull(tracker.consume(8, second))
    }

    @Test
    fun `reselecting the same frame still requests a paused seek`() {
        val tracker = FilmstripSelectionTracker(initialRequestTick = 0)

        assertEquals(first, tracker.consume(1, first))
        assertEquals(first, tracker.consume(2, first))
    }

    @Test
    fun `missing selection does not replay an old request`() {
        val tracker = FilmstripSelectionTracker(initialRequestTick = 0)

        assertNull(tracker.consume(1, null))
        assertNull(tracker.consume(1, first))
    }
}
