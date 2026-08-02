package com.multiviewer.ui

// One entry per frame from the second onward -- the first frame has no preceding interval to
// report, so it's excluded rather than given a meaningless 0.0. frameIndex/type/ptsSeconds are
// carried straight from the source FrameInfo so the view can color-code and hit-test points
// without needing a second, index-aligned parallel list.
data class FrameInterval(val frameIndex: Int, val type: Char, val ptsSeconds: Double, val intervalMs: Double, val intervalDiffMs: Double)

// intervalDiffMs is this interval minus the PREVIOUS interval (0.0 for the very first computed
// interval, since there's no interval before it to diff against) -- a large-magnitude diff is
// what visually flags an irregular gap in the graph, without this function judging "is this a
// drop" itself (no threshold; the plan's design explicitly leaves that call to the viewer).
fun computeFrameIntervals(frames: List<FrameInfo>): List<FrameInterval> {
    if (frames.size < 2) return emptyList()
    val result = mutableListOf<FrameInterval>()
    var previousIntervalMs: Double? = null
    for (i in 1 until frames.size) {
        val intervalMs = (frames[i].ptsSeconds - frames[i - 1].ptsSeconds) * 1000.0
        val diffMs = previousIntervalMs?.let { intervalMs - it } ?: 0.0
        result.add(FrameInterval(frames[i].index, frames[i].type, frames[i].ptsSeconds, intervalMs, diffMs))
        previousIntervalMs = intervalMs
    }
    return result
}
