package com.multiviewer.ui.ai

import java.util.Locale

/** One (x, y) sample of a time series sent to the AI as a compact summary. */
data class SeriesPoint(val x: Double, val y: Double)

private fun num(v: Double): String = String.format(Locale.US, if (Math.abs(v) >= 100.0) "%.1f" else "%.3f", v)

/**
 * Summarises a series without sending every point: at most [buckets] min/mean/max lines (one line per point when
 * the series is that short), the [spikes] largest values, and overall statistics. NaN/infinite points are dropped.
 */
fun renderSeries(
    title: String,
    unit: String,
    xLabel: String,
    points: List<SeriesPoint>,
    buckets: Int = 60,
    spikes: Int = 10,
): String {
    val pts = points.filter { it.x.isFinite() && it.y.isFinite() }.sortedBy { it.x }
    if (pts.isEmpty()) return "$title: no data"
    val n = pts.size
    val out = mutableListOf("$title ($unit) over $xLabel, $n points:")
    val b = buckets.coerceAtLeast(1)
    if (n <= b) {
        pts.forEach { out += "  ${num(it.x)}: ${num(it.y)}" }
    } else {
        for (i in 0 until b) {
            val from = (i.toLong() * n / b).toInt()
            val to = ((i + 1).toLong() * n / b).toInt()
            if (to <= from) continue
            val chunk = pts.subList(from, to)
            val ys = chunk.map { it.y }
            out += "  ${num(chunk.first().x)}–${num(chunk.last().x)}: min ${num(ys.min())} · mean ${num(ys.average())} · max ${num(ys.max())}"
        }
    }
    val top = pts.sortedByDescending { it.y }.take(spikes.coerceAtLeast(0))
    if (top.isNotEmpty()) out += "  largest: " + top.joinToString(", ") { "${num(it.x)}=${num(it.y)}" }
    val all = pts.map { it.y }
    out += "  overall: min ${num(all.min())} · mean ${num(all.average())} · max ${num(all.max())}"
    return out.joinToString("\n")
}
