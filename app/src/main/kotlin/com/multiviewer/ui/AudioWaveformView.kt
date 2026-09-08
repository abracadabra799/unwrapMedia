package com.multiviewer.ui

// MM:SS.mmm, minutes zero-padded to 2 (more digits past 100 min). Negative -> 00:00.000.
fun formatMinSecMillis(seconds: Double): String {
    val totalMs = (seconds * 1000).toLong().coerceAtLeast(0L)
    return "%02d:%02d.%03d".format(totalMs / 60_000, (totalMs % 60_000) / 1000, totalMs % 1000)
}

// Time between waveform axis ticks: the smallest "nice" value (1-2-5 x 10^n, plus 0.1/0.2/0.5)
// that yields <= ~10 ticks across the visible span.
private val NICE_STEPS = doubleArrayOf(
    0.05, 0.1, 0.2, 0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 30.0, 60.0, 120.0, 300.0, 600.0, 1800.0, 3600.0,
)

fun niceTimeStep(spanSeconds: Double): Double {
    val target = spanSeconds / 8.0 // aim for ~8 ticks
    return NICE_STEPS.firstOrNull { it >= target } ?: NICE_STEPS.last()
}
