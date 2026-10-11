package com.multiviewer.ui

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AvmProcessRunnerTest {
    @Test
    fun `cancellation also interrupts stdout backpressure after process exit`() {
        val windows = System.getProperty("os.name").contains("Windows", ignoreCase = true)
        val script = helperScript(windows, "echo frame-data")
        val consumerStarted = CountDownLatch(1)
        val cancel = AtomicBoolean(false)
        Thread {
            if (consumerStarted.await(5, TimeUnit.SECONDS)) {
                Thread.sleep(150)
                cancel.set(true)
            }
        }.apply { isDaemon = true; start() }
        val started = System.currentTimeMillis()

        val result = runAvmProcess(
            command(windows, script),
            onStdout = { consumerStarted.countDown(); Thread.sleep(5_000) },
            cancelRequested = { cancel.get() },
        )

        assertEquals(-1, result.exitCode)
        assertTrue(System.currentTimeMillis() - started < 2_000)
    }

    @Test
    fun `waits for stdout consumer to finish after process exits`() {
        val windows = System.getProperty("os.name").contains("Windows", ignoreCase = true)
        val script = helperScript(windows, "echo frame-data")
        val consumerStarted = CountDownLatch(1)
        val allowConsumerToFinish = CountDownLatch(1)
        val consumerFinished = AtomicBoolean(false)
        Thread {
            if (consumerStarted.await(5, TimeUnit.SECONDS)) {
                Thread.sleep(150)
                allowConsumerToFinish.countDown()
            }
        }.apply { isDaemon = true; start() }

        val result = runAvmProcess(command(windows, script), onStdout = { stdout ->
            consumerStarted.countDown()
            allowConsumerToFinish.await(5, TimeUnit.SECONDS)
            stdout.copyTo(ByteArrayOutputStream())
            consumerFinished.set(true)
        })

        assertEquals(0, result.exitCode)
        assertTrue(consumerFinished.get(), "stdout callback should finish before the runner returns")
    }

    @Test
    fun `waits for all process diagnostics to be drained before returning`() {
        val windows = System.getProperty("os.name").contains("Windows", ignoreCase = true)
        val flood = if (windows) {
            "for /L %%i in (1,1,50000) do echo x 1>&2"
        } else {
            "i=0; while [ ${'$'}i -lt 50000 ]; do echo x 1>&2; i=${'$'}((i+1)); done"
        }
        val script = helperScript(windows, flood, "echo stderr-final 1>&2")

        val result = runAvmProcess(command(windows, script))

        assertEquals(0, result.exitCode)
        assertTrue(result.stderr.trimEnd().endsWith("stderr-final"))
        assertTrue(result.stderrTruncated, "Large stderr should report that earlier diagnostics were dropped")
        assertTrue(result.stderr.length <= 64 * 1024)
    }

    @Test
    fun `bounds stderr when decoder writes a huge line without newlines`() {
        if (System.getProperty("os.name").contains("Windows", ignoreCase = true)) return
        val script = helperScript(false, "dd if=/dev/zero bs=100000 count=100 2>/dev/null | tr '\\000' x >&2")

        val result = runAvmProcess(command(false, script))

        assertEquals(0, result.exitCode)
        assertTrue(result.stderr.length <= 64 * 1024)
        assertTrue(result.stderrTruncated)
    }

    @Test
    fun `consumer failure terminates a decoder blocked writing stdout`() {
        val windows = System.getProperty("os.name").contains("Windows", ignoreCase = true)
        val script = if (windows) helperScript(windows, "for /L %%i in (1,1,1000000) do echo frame-data")
            else helperScript(windows, "while :; do echo frame-data; done")
        val started = System.currentTimeMillis()
        val result = runAvmProcess(command(windows, script), onStdout = { throw IllegalStateException("consumer failed") })
        assertEquals(-1, result.exitCode)
        assertTrue(result.callbackError?.contains("consumer failed") == true)
        assertTrue(System.currentTimeMillis() - started < 3_000)
    }

    @Test
    fun `captures stdout and stderr while process exits successfully`() {
        val windows = System.getProperty("os.name").contains("Windows", ignoreCase = true)
        val holdOpen = if (windows) "ping -n 2 127.0.0.1 >nul" else "sleep 0.2"
        val script = helperScript(windows, "echo frame-data", "echo decoder-note 1>&2", holdOpen)
        val stdout = ByteArrayOutputStream()

        val result = runAvmProcess(command(windows, script), onStdout = { it.copyTo(stdout) })

        assertEquals(0, result.exitCode)
        assertTrue(stdout.toString().contains("frame-data"))
        assertTrue(result.stderr.contains("decoder-note"))
    }

    @Test
    fun `cancellation terminates a running decoder process`() {
        val windows = System.getProperty("os.name").contains("Windows", ignoreCase = true)
        val script = if (windows) {
            helperScript(windows, ":loop", "echo working", "ping -n 2 127.0.0.1 >nul", "goto loop")
        } else {
            helperScript(windows, "while :; do echo working; sleep 1; done")
        }
        val started = System.currentTimeMillis()

        val result = runAvmProcess(command(windows, script), cancelRequested = { System.currentTimeMillis() - started > 150 })

        assertEquals(-1, result.exitCode)
        assertTrue(System.currentTimeMillis() - started < 5_000)
    }

    @Test
    fun `cancellation terminates decoder descendants`() {
        if (System.getProperty("os.name").contains("Windows", ignoreCase = true)) return
        val pidFile = File.createTempFile("avm-child-pid-", ".txt")
        pidFile.delete()
        val script = helperScript(false, "sleep 4 &", "echo ${'$'}! > '${pidFile.absolutePath}'", "wait")
        val started = System.currentTimeMillis()

        val result = runAvmProcess(command(false, script), cancelRequested = {
            pidFile.exists() && pidFile.readText().trim().toLongOrNull() != null
        })
        val childPid = pidFile.readText().trim().toLong()
        val child = ProcessHandle.of(childPid).orElse(null)
        pidFile.delete()

        assertEquals(-1, result.exitCode)
        assertTrue(System.currentTimeMillis() - started < 3_000, "cancellation should not wait for child timeout")
        assertTrue(child == null || !child.isAlive, "decoder child process $childPid should be terminated")
    }

    private fun command(windows: Boolean, script: File): List<String> =
        if (windows) listOf("cmd.exe", "/c", script.absolutePath) else listOf(script.absolutePath)

    private fun helperScript(windows: Boolean, vararg lines: String): File {
        val script = File.createTempFile("avm-process-test-", if (windows) ".cmd" else ".sh")
        script.writeText((if (windows) listOf("@echo off") else listOf("#!/bin/sh"))
            .plus(lines).joinToString("\n", postfix = "\n"))
        if (!windows) script.setExecutable(true)
        script.deleteOnExit()
        return script
    }
}
