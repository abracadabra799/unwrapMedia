package com.multiviewer.ui

import java.util.PriorityQueue

internal data class ScheduledThumbnailWork(
    val key: String,
    val priority: Int,
    val sequence: Long,
    val action: () -> Unit,
)

internal class ThumbnailPriorityQueue {
    private val lock = Object()
    private val pending = mutableMapOf<String, ScheduledThumbnailWork>()
    private val ordered = PriorityQueue<ScheduledThumbnailWork>(
        compareBy<ScheduledThumbnailWork> { it.priority }.thenBy { it.sequence },
    )
    private var nextSequence = 0L

    fun enqueue(key: String, priority: Int, action: () -> Unit): Boolean = synchronized(lock) {
        if (pending.containsKey(key)) return@synchronized false

        val work = ScheduledThumbnailWork(key, priority, nextSequence++, action)
        pending[key] = work
        ordered.add(work)
        lock.notifyAll()
        true
    }

    fun reprioritize(priorityForKey: (String) -> Int) = synchronized(lock) {
        val updated = pending.values.map { work ->
            work.copy(priority = priorityForKey(work.key))
        }
        ordered.clear()
        pending.clear()
        updated.forEach { work ->
            pending[work.key] = work
            ordered.add(work)
        }
    }

    fun takeNext(): ScheduledThumbnailWork = synchronized(lock) {
        while (ordered.isEmpty()) lock.wait()
        val work = ordered.remove()
        pending.remove(work.key)
        work
    }

    fun remove(key: String): Boolean = synchronized(lock) {
        val work = pending.remove(key) ?: return@synchronized false
        ordered.remove(work)
        true
    }
}

internal fun thumbnailPriorityForPath(
    path: String,
    visiblePaths: Set<String>,
    prefetchPaths: Set<String>,
    listMode: Boolean,
): Int = when {
    listMode -> 0
    path in visiblePaths -> 0
    path in prefetchPaths -> 1
    else -> 2
}
