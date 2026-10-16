package com.multiviewer.ui.ai

import java.util.Locale

/** One (x, y) sample of a time series sent to the AI as a compact summary. */
data class SeriesPoint(val x: Double, val y: Double)

private fun num(v: Double): String = String.format(Locale.US, if (Math.abs(v) >= 100.0) "%.1f" else "%.3f", v)

private fun xNum(v: Double): String = String.format(Locale.US, "%.3f", v)

/**
 * Summarises a series without sending every point: header, overall statistics and the [spikes] largest values first
 * (so truncation from the end drops detail before summary), then at most [buckets] min/mean/max lines (one line per
 * point when the series is that short). NaN/infinite points are dropped.
 */
fun renderSeries(
    title: String,
    unit: String,
    xLabel: String,
    points: List<SeriesPoint>,
    buckets: Int = 60,
    spikes: Int = 10,
): String = renderSeriesOrNull(title, unit, xLabel, points, buckets, spikes) ?: "$title: no data"

/** Like [renderSeries] but null when no finite point remains, so callers can omit empty series. */
fun renderSeriesOrNull(
    title: String,
    unit: String,
    xLabel: String,
    points: List<SeriesPoint>,
    buckets: Int = 60,
    spikes: Int = 10,
): String? {
    val pts = points.filter { it.x.isFinite() && it.y.isFinite() }.sortedBy { it.x }
    if (pts.isEmpty()) return null
    val n = pts.size
    val unitPart = if (unit.isEmpty()) "" else " ($unit)"
    val out = mutableListOf("$title$unitPart over $xLabel, $n points:")
    val all = pts.map { it.y }
    out += "  overall: min ${num(all.min())} · mean ${num(all.average())} · max ${num(all.max())}"
    val top = pts.sortedByDescending { it.y }.take(spikes.coerceAtLeast(0))
    if (top.isNotEmpty()) out += "  largest: " + top.joinToString(", ") { "${xNum(it.x)}=${num(it.y)}" }
    val b = buckets.coerceAtLeast(1)
    if (n <= b) {
        pts.forEach { out += "  ${xNum(it.x)}: ${num(it.y)}" }
    } else {
        for (i in 0 until b) {
            val from = (i.toLong() * n / b).toInt()
            val to = ((i + 1).toLong() * n / b).toInt()
            if (to <= from) continue
            val chunk = pts.subList(from, to)
            val ys = chunk.map { it.y }
            out += "  ${xNum(chunk.first().x)}–${xNum(chunk.last().x)}: min ${num(ys.min())} · mean ${num(ys.average())} · max ${num(ys.max())}"
        }
    }
    return out.joinToString("\n")
}
