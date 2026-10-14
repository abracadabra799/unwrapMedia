package com.multiviewer.ui

import java.awt.EventQueue
import java.util.concurrent.Future

/** Owns parse and nested decode jobs for one compare slot and one selected file. */
internal class CompareLoadSession : AutoCloseable {
    @Volatile private var closed = false
    private val jobs = mutableListOf<Future<*>>()

    @Synchronized fun track(job: Future<*>?) {
        if (job == null) return
        if (closed) job.cancel(true) else jobs += job
    }

    fun post(block: () -> Unit) {
        EventQueue.invokeLater { if (!closed) block() }
    }

    fun deliver(block: () -> Unit) {
        if (!closed) block()
    }

    override fun close() {
        val pending = synchronized(this) {
            closed = true
            jobs.toList().also { jobs.clear() }
        }
        pending.forEach { it.cancel(true) }
    }
}
