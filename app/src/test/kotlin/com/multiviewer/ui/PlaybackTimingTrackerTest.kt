package com.multiviewer.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackTimingTrackerTest {
    private val timeline = FrameTimestampIndex(listOf(0.0, 0.040, 0.120, 0.160))

    @Test
    fun `a VFR source gap is separate from UI delivery delay and skipped playback frames`() {
        val tracker = PlaybackTimingTracker(timeline)
        tracker.record(timeline.position(0)!!, 1_000_000_000L)

        val sample = tracker.record(timeline.position(2)!!, 1_200_000_000L)

        assertEquals(80.0, sample.sourceIntervalMs!!, 0.001)
        assertEquals(200.0, sample.uiDeliveryIntervalMs!!, 0.001)
        assertEquals(120.0, sample.sourceElapsedSinceDisplayMs!!, 0.001)
        assertEquals(80.0, sample.deliveryDelayMs!!, 0.001)
        assertEquals(1, sample.skippedSinceDisplay)
        assertEquals(PlaybackTimingEventKind.UI_DELAY, tracker.recentEvents.single().kind)
    }

    @Test
    fun `a long source interval delivered on time is not a playback delay`() {
        val tracker = PlaybackTimingTracker(timeline)
        tracker.record(timeline.position(1)!!, 1_000_000_000L)

        val sample = tracker.record(timeline.position(2)!!, 1_080_000_000L)

        assertEquals(80.0, sample.sourceIntervalMs!!, 0.001)
        assertEquals(0.0, sample.deliveryDelayMs!!, 0.001)
        assertEquals(emptyList(), tracker.recentEvents)
    }

    @Test
    fun `pause or seek resets the UI clock without erasing recent events`() {
        val tracker = PlaybackTimingTracker(timeline)
        tracker.record(timeline.position(0)!!, 1_000_000_000L)
        tracker.record(timeline.position(1)!!, 1_200_000_000L)
        tracker.resetClock()

        val sample = tracker.record(timeline.position(2)!!, 9_000_000_000L)

        assertNull(sample.uiDeliveryIntervalMs)
        assertEquals(80.0, sample.sourceIntervalMs!!, 0.001)
        assertEquals(1, tracker.recentEvents.size)
    }

    @Test
    fun `drop events retain the exact frame and the event log stays bounded`() {
        val tracker = PlaybackTimingTracker(timeline, maxEvents = 2)
        tracker.recordDrop(timeline.position(1)!!, PlaybackDropReason.VIDEO_LAG)
        tracker.recordDrop(timeline.position(2)!!, PlaybackDropReason.AUDIO_SYNC)
        tracker.recordDrop(timeline.position(3)!!, PlaybackDropReason.VIDEO_LAG)

        assertEquals(3, tracker.dropCount)
        assertEquals(listOf(3, 4), tracker.recentEvents.map { it.position.number })
        assertEquals(PlaybackDropReason.AUDIO_SYNC, tracker.recentEvents.first().dropReason)
    }
}
