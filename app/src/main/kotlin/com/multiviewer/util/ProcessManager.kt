package com.multiviewer.util

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Global process tracker ensuring no zombie ffmpeg/ffprobe processes remain
 * when coroutines are cancelled, timeouts occur, or the application shuts down.
 */
object ProcessManager {
    private val activeProcesses = Collections.newSetFromMap(ConcurrentHashMap<Process, Boolean>())
    private val terminating = ConcurrentHashMap<Process, List<ProcessHandle>>()
    private val cleanup = ScheduledThreadPoolExecutor(4) { task ->
        Thread(task, "media-process-cleanup").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    /** Number of processes currently tracked. For leak assertions in tests. */
    internal val activeCount: Int get() = activeProcesses.size

    /** Whether [process] is still tracked. For per-process leak assertions in tests. */
    internal fun isTracked(process: Process): Boolean = process in activeProcesses

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
        // Save descendants BEFORE signalling the parent: after it exits they are reparented
        // and cannot be found through its PID. Keep this snapshot until forced cleanup ends.
        val children = descendants(process)
        if (terminating.putIfAbsent(process, children) != null) return
        try {
            children.asReversed().forEach { runCatching { if (it.isAlive) it.destroy() } }
            runCatching { if (process.isAlive) process.destroy() }
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200)
            try {
                // waitFor returns the moment the parent exits -- the usual case for ffmpeg on SIGTERM --
                // instead of always paying at least one 10ms poll tick.
                process.waitFor(200, TimeUnit.MILLISECONDS)
                while (System.nanoTime() < deadline && children.any { it.isAlive }) Thread.sleep(10)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (process.isAlive || children.any { it.isAlive }) forceStop(process, children)
            runCatching { if (process.isAlive) process.waitFor(200, TimeUnit.MILLISECONDS) }
        } finally {
            activeProcesses.remove(process)
            terminating.remove(process)
        }
    }

    /**
     * [terminate] off the calling thread, for UI-thread teardown (onDispose on seek / tab close),
     * where the SIGTERM grace period would otherwise stall the frame. The process stays registered
     * until it is gone, so destroyAll() still reaches it. [then] runs once it is dead, e.g. to
     * delete a temp input file Windows would refuse to delete while ffmpeg still holds it.
     */
    fun terminateAsync(process: Process?, then: () -> Unit = {}) {
        cleanup.execute {
            try {
                terminate(process)
            } finally {
                runCatching(then)
            }
        }
    }

    /**
     * Stops processes that are no longer reachable through a live parent -- descendants snapshotted while
     * their parent ran (see [descendants]), which it left behind when it exited normally. Same staged
     * shutdown as [terminate]: polite destroy, a short grace period, then a forced kill. Blocking.
     */
    fun terminateOrphans(handles: Collection<ProcessHandle>) {
        val alive = handles.filter { it.isAlive }.distinctBy { it.pid() }
        if (alive.isEmpty()) return
        alive.asReversed().forEach { runCatching { it.destroy() } }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200)
        try {
            while (System.nanoTime() < deadline && alive.any { it.isAlive }) Thread.sleep(10)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        alive.asReversed().forEach { runCatching { if (it.isAlive) it.destroyForcibly() } }
    }

    /** The current descendants of [process] (empty once it has exited: its children are reparented). */
    fun descendantsOf(process: Process): List<ProcessHandle> = descendants(process)

    private fun descendants(process: Process): List<ProcessHandle> = runCatching {
        ProcessHandle.of(process.pid()).orElse(null)?.descendants()?.use { it.toList() }.orEmpty()
    }.getOrDefault(emptyList())

    private fun forceStop(process: Process, children: List<ProcessHandle>) {
        (children + descendants(process)).distinctBy { it.pid() }.asReversed().forEach {
            runCatching { if (it.isAlive) it.destroyForcibly() }
        }
        runCatching { if (process.isAlive) process.destroyForcibly() }
    }

    /** The deadline [watchCancellation] applies when the caller gives none. */
    val DEFAULT_WATCH_TIMEOUT_MILLIS: Long = TimeUnit.MINUTES.toMillis(30)

    /** Handle returned by [watchCancellation]; [timedOut] tells a deadline kill apart from a cancel. */
    class Watch internal constructor() : AutoCloseable {
        /** True once the deadline (not a cancel) killed the process: whatever was read is partial. */
        @Volatile var timedOut: Boolean = false
            internal set
        @Volatile internal var task: java.util.concurrent.ScheduledFuture<*>? = null
        override fun close() { task?.cancel(false) }
    }

    /** Cancellation must not depend on a blocked stdout read producing another line. */
    fun watchCancellation(process: Process, cancelled: () -> Boolean): Watch =
        watchCancellation(process, DEFAULT_WATCH_TIMEOUT_MILLIS, cancelled)

    /** Also bounds a silent stdout read; waiting after the read cannot enforce this deadline. */
    fun watchCancellation(process: Process, timeoutMillis: Long, cancelled: () -> Boolean): Watch {
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        val watch = Watch()
        watch.task = cleanup.scheduleWithFixedDelay({
            if (runCatching(cancelled).getOrDefault(true)) {
                terminate(process)
            } else if (System.nanoTime() >= deadline) {
                // Set before the kill, so a reader that sees the process end also sees why.
                if (process.isAlive) watch.timedOut = true
                terminate(process)
            }
        }, 0, 50, TimeUnit.MILLISECONDS)
        return watch
    }

    /**
     * Force kills all currently active processes.
     */
    fun destroyAll() {
        for (p in activeProcesses.toSet() + terminating.keys) {
            forceStop(p, terminating[p].orEmpty())
            activeProcesses.remove(p)
            terminating.remove(p)
        }
    }
}
