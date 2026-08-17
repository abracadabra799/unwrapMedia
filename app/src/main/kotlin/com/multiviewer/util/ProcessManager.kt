package com.multiviewer.util

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Global process tracker ensuring no zombie ffmpeg/ffprobe processes remain
 * when coroutines are cancelled, timeouts occur, or the application shuts down.
 */
object ProcessManager {
    private val activeProcesses = Collections.newSetFromMap(ConcurrentHashMap<Process, Boolean>())

    init {
        // Register JVM shutdown hook to kill all remaining child processes
        Runtime.getRuntime().addShutdownHook(Thread {
            destroyAll()
        }.apply { isDaemon = true })
    }

    /**
     * Registers an active process to be tracked.
     */
    fun register(process: Process): Process {
        activeProcesses.add(process)
        return process
    }

    /**
     * Unregisters a finished process.
     */
    fun unregister(process: Process) {
        activeProcesses.remove(process)
    }

    /**
     * Safely terminates a process with a short grace period, then force kills it.
     */
    fun terminate(process: Process?) {
        if (process == null) return
        activeProcesses.remove(process)
        try {
            if (process.isAlive) {
                process.destroy()
                // If it doesn't exit promptly, force kill
                Thread {
                    try {
                        Thread.sleep(200)
                        if (process.isAlive) {
                            process.destroyForcibly()
                        }
                    } catch (_: Throwable) {
                        process.destroyForcibly()
                    }
                }.apply { isDaemon = true }.start()
            }
        } catch (_: Throwable) {
            try {
                process.destroyForcibly()
            } catch (_: Throwable) {}
        }
    }

    /**
     * Force kills all currently active processes.
     */
    fun destroyAll() {
        val iterator = activeProcesses.iterator()
        while (iterator.hasNext()) {
            val p = iterator.next()
            iterator.remove()
            try {
                if (p.isAlive) {
                    p.destroyForcibly()
                }
            } catch (_: Throwable) {}
        }
    }
}
