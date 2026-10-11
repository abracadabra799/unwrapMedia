package com.multiviewer.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class ProcessOutputReaderTest {
    @Test
    fun `interrupted caller stops the process and propagates interruption`() {
        val process = ProcessBuilder("sleep", "30").start()
        try {
            Thread.currentThread().interrupt()
            assertFailsWith<InterruptedException> {
                readProcessOutputWithTimeout(process, 30) { process.inputStream.read() }
            }
        } finally {
            Thread.interrupted()
            assertTrue(process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS))
            process.destroyForcibly()
        }
    }
    @Test
    fun `readProcessOutputWithTimeout returns the read result for a fast-completing process`() {
        val process = ProcessBuilder("echo", "hello").start()

        val result = readProcessOutputWithTimeout(process, timeoutSeconds = 5) {
            process.inputStream.bufferedReader().readLines()
        }

        assertEquals(listOf("hello"), result)
    }

    @Test
    fun `readProcessOutputWithTimeout returns null and force-kills the process when the read exceeds the timeout`() {
        // Produces no stdout output and doesn't exit until 30s -- read() blocks the entire time,
        // exactly reproducing the real bug's shape (an unbounded blocking read on a stuck process).
        val process = ProcessBuilder("sleep", "30").start()

        val result = readProcessOutputWithTimeout(process, timeoutSeconds = 1) {
            process.inputStream.bufferedReader().readLines()
        }

        assertNull(result)
        Thread.sleep(200) // brief grace period for destroyForcibly() to take effect
        assertTrue(!process.isAlive)
    }

    @Test
    fun `readProcessOutputWithTimeout returns null when the read block itself throws`() {
        val process = ProcessBuilder("echo", "hello").start()

        val result = readProcessOutputWithTimeout(process, timeoutSeconds = 5) {
            throw RuntimeException("boom")
        }

        assertNull(result)
    }
}
