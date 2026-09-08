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

// The view window the scrolling waveform uses while playing: keeps displayElapsedSeconds at the
// centre, then clamps so it never runs past either end of the track (so near the ends the
// playhead drifts toward the edge rather than the window showing blank margin).
fun followWindow(
    displayElapsedSeconds: Double,
    windowDurationSeconds: Double,
    totalDurationSeconds: Double,
): AudioViewWindow =
    clampWindow(displayElapsedSeconds - windowDurationSeconds / 2.0, windowDurationSeconds, totalDurationSeconds)

// The view after the playhead has left the visible span during playback (page-scroll, like
// GoldWave): when the cursor passes 90% of the width, jump forward so it reappears at ~10% from
// the left; a backward seek that lands left of the view pages the same way. Returns null when no
// paging is needed (cursor still comfortably inside, or fully zoomed out).
fun pageScrollView(
    cursorSeconds: Double,
    view: AudioViewWindow,
    totalDurationSeconds: Double,
): AudioViewWindow? {
    if (view.durationSeconds >= totalDurationSeconds) return null
    val pastRight = cursorSeconds >= view.startSeconds + view.durationSeconds * 0.9
    val pastLeft = cursorSeconds < view.startSeconds
    if (!pastRight && !pastLeft) return null
    return clampWindow(cursorSeconds - view.durationSeconds * 0.1, view.durationSeconds, totalDurationSeconds)
}

// Zoom to newSpanSeconds while keeping anchorSeconds under the same fraction of the viewport it
// occupied before (so wheel-zoom stays put under the cursor). Clamped to the track.
fun zoomAround(
    view: AudioViewWindow,
    anchorSeconds: Double,
    newSpanSeconds: Double,
    totalDurationSeconds: Double,
): AudioViewWindow {
    val frac = if (view.durationSeconds <= 0.0) 0.5
        else ((anchorSeconds - view.startSeconds) / view.durationSeconds).coerceIn(0.0, 1.0)
    return clampWindow(anchorSeconds - frac * newSpanSeconds, newSpanSeconds, totalDurationSeconds)
}

// 100% == whole file visible; scales inversely with the visible span.
fun zoomPercent(view: AudioViewWindow, totalDurationSeconds: Double): Int =
    if (view.durationSeconds <= 0.0) 100
    else Math.round(100.0 * totalDurationSeconds / view.durationSeconds).toInt()
