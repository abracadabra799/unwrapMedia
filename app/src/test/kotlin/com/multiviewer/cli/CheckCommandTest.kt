package com.multiviewer.cli

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CheckCommandTest {
    private fun captureOutput(block: () -> Int): Triple<Int, String, String> {
        val originalOut = System.out
        val originalErr = System.err
        val outBuffer = ByteArrayOutputStream()
        val errBuffer = ByteArrayOutputStream()
        System.setOut(PrintStream(outBuffer))
        System.setErr(PrintStream(errBuffer))
        val exitCode = try {
            block()
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
        return Triple(exitCode, outBuffer.toString(), errBuffer.toString())
    }

    @Test
    fun `returns exit code 1 and prints usage to stderr when no file argument is given`() {
        val (exitCode, stdout, stderr) = captureOutput { runCheckCommand(emptyList()) }

        assertEquals(1, exitCode)
        assertEquals("", stdout)
        assertTrue(stderr.contains("Usage"), "Expected a usage message, got: $stderr")
    }

    @Test
    fun `returns exit code 1 and prints the failure message to stderr for a missing file`() {
        val (exitCode, stdout, stderr) = captureOutput { runCheckCommand(listOf("/nonexistent/does-not-exist.jpg")) }

        assertEquals(1, exitCode)
        assertEquals("", stdout)
        assertTrue(stderr.contains("not found"), "Expected a 'not found' message, got: $stderr")
    }

    @Test
    fun `returns exit code 0 and prints JSON to stdout for a real clean PNG, regardless of warningCount`() {
        val file = File.createTempFile("check-command-test-", ".png")
        file.deleteOnExit()
        ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "color=green:size=16x16",
            "-frames:v", "1", file.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()

        val (exitCode, stdout, stderr) = captureOutput { runCheckCommand(listOf(file.absolutePath)) }

        assertEquals(0, exitCode)
        assertEquals("", stderr)
        assertTrue(stdout.contains("\"warningCount\""), "Expected a warningCount field in stdout, got: $stdout")
        file.delete()
    }
}
