package com.multiviewer.util

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProcessManagerTest {

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
}
