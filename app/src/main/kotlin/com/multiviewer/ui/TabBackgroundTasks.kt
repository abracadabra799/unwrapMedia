package com.multiviewer.ui

import java.awt.EventQueue
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.ThreadPoolExecutor

/** Owns work independently of the global worker pool, so closing a tab releases its queue. */
internal class TabBackgroundTasks(private val executor: ExecutorService = backgroundExecutor) : AutoCloseable {
    private val lock = Any()
    private var closed = false
    private val tasks = mutableSetOf<FutureTask<Unit>>()

    fun submit(block: () -> Unit): Future<*>? = synchronized(lock) {
        if (closed) return null
        val task = object : FutureTask<Unit>(Callable {
            if (synchronized(lock) { !closed }) {
                try { block() }
                catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                // ByteReader stops an interrupted (cancelled) read this way; not a failure.
                catch (_: java.util.concurrent.CancellationException) { }
                catch (e: Exception) { System.err.println("Background task failed: $e") }
            }
        }) {
            override fun done() {
                synchronized(lock) { tasks.remove(this) }
                (executor as? ThreadPoolExecutor)?.remove(this)
            }
        }
        tasks.add(task)
        try { executor.execute(task) } catch (e: RuntimeException) {
            tasks.remove(task)
            throw e
        }
        task
    }

    override fun close() {
        val pending = synchronized(lock) {
            closed = true
            tasks.toList().also { tasks.clear() }
        }
        pending.forEach { it.cancel(true) }
    }
}

internal fun TabState.postIfOpen(block: () -> Unit) {
    EventQueue.invokeLater { if (!isClosed) block() }
}
