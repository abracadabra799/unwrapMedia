package com.multiviewer.util

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProcessManagerTest {

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
}
