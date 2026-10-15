package com.multiviewer.ui

enum class PlaybackDropReason { VIDEO_LAG, AUDIO_SYNC }
enum class PlaybackTimingEventKind { UI_DELAY, FRAME_DROP }

data class PlaybackTimingSample(
    val position: FramePosition,
    /** Difference from the previous source frame, even if the player did not display it. */
    val sourceIntervalMs: Double?,
    /** Difference between successive EDT frame-delivery callbacks. Not physical screen scanout. */
    val uiDeliveryIntervalMs: Double?,
    val sourceElapsedSinceDisplayMs: Double?,
    val deliveryDelayMs: Double?,
    val skippedSinceDisplay: Int,
)

data class PlaybackTimingEvent(
    val position: FramePosition,
    val kind: PlaybackTimingEventKind,
    val delayMs: Double? = null,
    val dropReason: PlaybackDropReason? = null,
)

/** A bounded UI-side observation log. The caller resets the clock on pause/seek. */
class PlaybackTimingTracker(private val timeline: FrameTimestampIndex, private val maxEvents: Int = 100) {
    init { require(maxEvents > 0) }

    private var previousPosition: FramePosition? = null
    private var previousDeliveryNs: Long? = null
    private val events = ArrayDeque<PlaybackTimingEvent>()
    val recentEvents: List<PlaybackTimingEvent> get() = events.toList()
    var dropCount: Int = 0
        private set

    fun resetClock() {
        previousPosition = null
        previousDeliveryNs = null
    }

    fun record(position: FramePosition, deliveryNs: Long): PlaybackTimingSample {
        val sourcePrevious = timeline.position(position.number - 2)
        val sourceInterval = sourcePrevious?.let { (position.ptsSeconds - it.ptsSeconds) * 1000.0 }
        val prior = previousPosition?.takeIf { it.number < position.number }
        val priorNs = if (prior != null) previousDeliveryNs else null
        val uiInterval = priorNs?.let { (deliveryNs - it).coerceAtLeast(0L) / 1_000_000.0 }
        val sourceElapsed = prior?.let { (position.ptsSeconds - it.ptsSeconds) * 1000.0 }
        val delay = if (uiInterval != null && sourceElapsed != null) uiInterval - sourceElapsed else null
        val sample = PlaybackTimingSample(
            position, sourceInterval, uiInterval, sourceElapsed, delay,
            prior?.let { (position.number - it.number - 1).coerceAtLeast(0) } ?: 0,
        )
        previousPosition = position
        previousDeliveryNs = deliveryNs
        if (delay != null && sourceElapsed != null && delay > maxOf(25.0, sourceElapsed * 0.5)) {
            addEvent(PlaybackTimingEvent(position, PlaybackTimingEventKind.UI_DELAY, delayMs = delay))
        }
        return sample
    }

    fun recordDrop(position: FramePosition, reason: PlaybackDropReason) {
        dropCount++
        addEvent(PlaybackTimingEvent(position, PlaybackTimingEventKind.FRAME_DROP, dropReason = reason))
    }

    private fun addEvent(event: PlaybackTimingEvent) {
        events.addLast(event)
        while (events.size > maxEvents) events.removeFirst()
    }
}
