package com.multiviewer.ui

import java.util.concurrent.Future

/** One in-flight decode per side; a slow decode is followed by the latest requested timestamp. */
internal class ComparisonFrameScheduler<T>(
    private val decode: (Double, (T?) -> Unit) -> Future<*>?,
    private val publish: (T?) -> Unit,
) : AutoCloseable {
    private var closed = false
    private var running = false
    private var pendingTime: Double? = null
    private var activeFuture: Future<*>? = null

    // Called from Compose's UI thread; decoder callbacks return on the same EDT.
    fun request(seconds: Double) {
        if (closed) return
        pendingTime = seconds
        if (!running) startNext()
    }

    private fun startNext() {
        val seconds = pendingTime ?: return
        pendingTime = null
        running = true
        try {
            activeFuture = decode(seconds) { bitmap ->
                if (closed) return@decode
                publish(bitmap)
                running = false
                activeFuture = null
                startNext()
            }
            if (activeFuture == null) running = false
        } catch (_: Exception) {
            running = false
        }
    }

    override fun close() {
        closed = true
        pendingTime = null
        activeFuture?.cancel(true)
        activeFuture = null
    }
}
