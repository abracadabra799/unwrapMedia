package com.multiviewer.ui

// The narrowest time range zoom can show -- prevents a degenerate zero-width (or inverted)
// window, which would make both the waveform's bucket range and the spectrogram's ffmpeg -t
// argument meaningless.
const val MIN_VISIBLE_DURATION_SECONDS = 0.5

// The waveform, spectrogram, minimap, and scrollbar all share one of these: what time range is
// currently shown in the (non-minimap) detail panels. durationSeconds == totalDuration means
// fully zoomed out (today's pre-zoom-feature behavior).
data class AudioViewWindow(val startSeconds: Double, val durationSeconds: Double)

// Single source of truth for keeping a requested window valid: duration is clamped to
// [MIN_VISIBLE_DURATION_SECONDS, totalDuration], then start is clamped so the window never
// extends past either end of the track. Re-clamping start after duration keeps the window valid
// even when duration grows back toward totalDuration (e.g. zooming back out from a window whose
// start would otherwise no longer fit).
fun clampWindow(requestedStart: Double, requestedDuration: Double, totalDuration: Double): AudioViewWindow {
    val safeTotal = totalDuration.coerceAtLeast(MIN_VISIBLE_DURATION_SECONDS)
    val duration = requestedDuration.coerceIn(MIN_VISIBLE_DURATION_SECONDS, safeTotal)
    val start = requestedStart.coerceIn(0.0, (totalDuration - duration).coerceAtLeast(0.0))
    return AudioViewWindow(start, duration)
}
