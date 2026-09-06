package com.multiviewer.util

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Global process tracker ensuring no zombie ffmpeg/ffprobe processes remain
 * when coroutines are cancelled, timeouts occur, or the application shuts down.
 */
object ProcessManager {
    private val activeProcesses = Collections.newSetFromMap(ConcurrentHashMap<Process, Boolean>())

    /** Number of processes currently tracked. For leak assertions in tests. */
    internal val activeCount: Int get() = activeProcesses.size

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
     * Safely terminates a process with a short grace period, then force kills it and all its descendants.
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
                            killDescendantsAndForcibly(process)
                        }
                    } catch (_: Throwable) {
                        killDescendantsAndForcibly(process)
                    }
                }.apply { isDaemon = true }.start()
            }
        } catch (_: Throwable) {
            killDescendantsAndForcibly(process)
        }
    }

    private fun killDescendantsAndForcibly(process: Process) {
        try {
            ProcessHandle.of(process.pid()).ifPresent { h ->
                h.descendants().forEach { it.destroyForcibly() }
            }
        } catch (_: Throwable) {}
        try {
            process.destroyForcibly()
        } catch (_: Throwable) {}
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
                    // Kill children first (a PTY shell's CLI child would otherwise
                    // survive). Go via ProcessHandle.of(pid) because pty4j's
                    // WinConPtyProcess doesn't implement toHandle()/descendants().
                    try {
                        ProcessHandle.of(p.pid()).ifPresent { h ->
                            h.descendants().forEach { it.destroyForcibly() }
                        }
                    } catch (_: Throwable) {}
                    p.destroyForcibly()
                }
            } catch (_: Throwable) {}
        }
    }
}
