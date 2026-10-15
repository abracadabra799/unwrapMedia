package com.multiviewer.ui

/** Consumes filmstrip requests while ignoring popup-originated changes to the selected frame. */
class FilmstripSelectionTracker(initialRequestTick: Int) {
    private var lastHandledTick = initialRequestTick

    fun consume(requestTick: Int, selectedFrame: FrameInfo?): FrameInfo? {
        if (requestTick == lastHandledTick) return null
        lastHandledTick = requestTick
        return selectedFrame
    }
}
