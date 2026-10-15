package com.multiviewer.ui

/** A displayed frame's one-based number and actual presentation timestamp. */
data class FramePosition(val number: Int, val total: Int, val ptsSeconds: Double)

/** Navigation in presentation order, including clips with unequal frame intervals. */
class FrameTimestampIndex(private val timestamps: List<Double>) {
    init {
        require(timestamps.isNotEmpty())
    }

    val size: Int get() = timestamps.size

    fun position(zeroBasedIndex: Int): FramePosition? =
        timestamps.getOrNull(zeroBasedIndex)?.let { FramePosition(zeroBasedIndex + 1, size, it) }

    fun step(zeroBasedIndex: Int, delta: Int): FramePosition =
        position((zeroBasedIndex + delta).coerceIn(timestamps.indices))!!

    fun atPts(ptsSeconds: Double): FramePosition? {
        val match = timestamps.binarySearch(ptsSeconds).let { if (it >= 0) it else -it - 1 }
        val nearest = listOf(match - 1, match).filter { it in timestamps.indices }
            .minByOrNull { kotlin.math.abs(timestamps[it] - ptsSeconds) } ?: return null
        return if (kotlin.math.abs(timestamps[nearest] - ptsSeconds) <= 0.001) position(nearest) else null
    }

    fun forInterval(frameIndex: Int, ptsSeconds: Double): FramePosition? {
        val indexed = position(frameIndex)
        return if (indexed != null && kotlin.math.abs(indexed.ptsSeconds - ptsSeconds) <= 0.001) {
            indexed
        } else {
            atPts(ptsSeconds)
        }
    }
}
