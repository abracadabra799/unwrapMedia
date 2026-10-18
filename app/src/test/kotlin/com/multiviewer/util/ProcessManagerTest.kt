package com.multiviewer.util

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProcessManagerTest {
    @Test
    fun `watch timeout terminates a silent process before any output arrives`() {
        val process = ProcessManager.register(ProcessBuilder("sleep", "30").start())
        val watch = ProcessManager.watchCancellation(process, timeoutMillis = 100) { false }
        try {
            assertTrue(process.waitFor(3, TimeUnit.SECONDS))
            assertFalse(process.isAlive)
        } finally {
            watch.close()
            ProcessManager.terminate(process)
        }
    }


    // A deadline kill must be distinguishable from a normal end or a cancel, so long scans can say
    // "timed out" instead of presenting a partial result as if it were complete.
    @Test
    fun `watch reports whether its deadline killed the process`() {
        val slow = ProcessManager.register(ProcessBuilder("sleep", "30").start())
        val deadlineWatch = ProcessManager.watchCancellation(slow, timeoutMillis = 100) { false }
        val cancelled = ProcessManager.register(ProcessBuilder("sleep", "30").start())
        val cancelWatch = ProcessManager.watchCancellation(cancelled, timeoutMillis = 60_000) { true }
        try {
            assertTrue(slow.waitFor(3, TimeUnit.SECONDS))
            assertTrue(cancelled.waitFor(3, TimeUnit.SECONDS))
            assertTrue(deadlineWatch.timedOut, "deadline kill must be reported as a timeout")
            assertFalse(cancelWatch.timedOut, "a cancel is not a timeout")
        } finally {
            deadlineWatch.close(); cancelWatch.close()
            ProcessManager.terminate(slow); ProcessManager.terminate(cancelled)
        }
    }

    @Test
    fun `terminate stops a child even when its parent exits immediately`() {
        val parent = ProcessBuilder("sh", "-c", "sleep 30 & echo ${'$'}!; wait").start()
        val child = ProcessHandle.of(parent.inputReader().readLine().toLong()).orElseThrow()
        try {
            ProcessManager.register(parent)
            ProcessManager.terminate(parent)
            assertTrue(parent.waitFor(2, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (child.isAlive && System.nanoTime() < deadline) Thread.sleep(10)
            assertFalse(child.isAlive, "Parent exited but its child survived termination")
        } finally {
            child.destroyForcibly()
            parent.destroyForcibly()
            parent.waitFor(2, TimeUnit.SECONDS)
            ProcessManager.unregister(parent)
        }
    }

    /** No-op fake so tests don't spawn real OS processes. */
    private class NoopProcess : Process() {
        @Volatile private var alive = true
        override fun getOutputStream() = java.io.OutputStream.nullOutputStream()
        override fun getInputStream() = java.io.InputStream.nullInputStream()
        override fun getErrorStream() = java.io.InputStream.nullInputStream()
        override fun waitFor(): Int = 0
        override fun exitValue(): Int = 0
        override fun destroy() { alive = false }
        override fun isAlive(): Boolean = alive
    }

    @Test
    fun `register and terminate terminates active process without leaking`() {
        val process = ProcessBuilder("sleep", "10").start()
        assertTrue(process.isAlive)

        ProcessManager.register(process)
        ProcessManager.terminate(process)

        // Process should be terminated
        Thread.sleep(300)
        assertFalse(process.isAlive)
    }

    @Test
    fun `destroyAll terminates all registered active processes`() {
        val p1 = ProcessBuilder("sleep", "10").start()
        val p2 = ProcessBuilder("sleep", "10").start()

        ProcessManager.register(p1)
        ProcessManager.register(p2)

        assertTrue(p1.isAlive)
        assertTrue(p2.isAlive)

        ProcessManager.destroyAll()

        Thread.sleep(300)
        assertFalse(p1.isAlive)
        assertFalse(p2.isAlive)
    }

    @Test
    fun `activeCount tracks register and unregister`() {
        val base = ProcessManager.activeCount
        val a = NoopProcess()
        val b = NoopProcess()
        ProcessManager.register(a)
        ProcessManager.register(b)
        assertEquals(base + 2, ProcessManager.activeCount)
        ProcessManager.register(a) // idempotent set add
        assertEquals(base + 2, ProcessManager.activeCount)
        ProcessManager.unregister(a)
        ProcessManager.unregister(b)
        ProcessManager.unregister(b) // idempotent remove
        assertEquals(base, ProcessManager.activeCount)
    }

    @Test
    fun `concurrent register unregister leaves a consistent count`() {
        val base = ProcessManager.activeCount
        val threads = 16
        val perThread = 500
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        repeat(threads) {
            pool.submit {
                start.await()
                repeat(perThread) {
                    val p = NoopProcess()
                    ProcessManager.register(p)
                    ProcessManager.unregister(p)
                }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS), "workers did not finish")
        pool.shutdown()
        assertEquals(base, ProcessManager.activeCount, "register/unregister race leaked entries")
    }

    @Test
    fun `destroyAll on an empty set is safe and repeatable`() {
        ProcessManager.destroyAll()
        assertEquals(0, ProcessManager.activeCount)
        ProcessManager.destroyAll()
        assertEquals(0, ProcessManager.activeCount)
    }

    @Test
    fun `terminate unregisters even a process that is already dead`() {
        val base = ProcessManager.activeCount
        val p = NoopProcess()
        p.destroy() // already not alive
        ProcessManager.register(p)
        ProcessManager.terminate(p)
        assertEquals(base, ProcessManager.activeCount)
    }
    // onDispose runs on the UI thread: a seek or tab close must not wait out the SIGTERM grace
    // period there. A child that ignores SIGTERM is the worst case (grace + force kill).
    @Test
    fun `terminateAsync returns at once and runs the callback only after the process is dead`() {
        val process = ProcessBuilder("sh", "-c", "trap '' TERM; echo ready; sleep 30").start()
        process.inputReader().readLine()
        ProcessManager.register(process)
        val done = CountDownLatch(1)
        var aliveInCallback = true
        val started = System.nanoTime()
        ProcessManager.terminateAsync(process) {
            aliveInCallback = process.isAlive
            done.countDown()
        }
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        try {
            assertTrue(elapsedMs < 100, "terminateAsync blocked the caller for ${elapsedMs}ms")
            assertTrue(done.await(5, TimeUnit.SECONDS), "callback never ran")
            assertFalse(aliveInCallback, "callback ran before the process was gone")
        } finally {
            process.destroyForcibly()
            ProcessManager.unregister(process)
        }
    }

    @Test
    fun `terminateAsync of null still runs the callback`() {
        val done = CountDownLatch(1)
        ProcessManager.terminateAsync(null) { done.countDown() }
        assertTrue(done.await(2, TimeUnit.SECONDS))
    }
}
