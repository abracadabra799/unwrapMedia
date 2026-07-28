package com.multiviewer.cli

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DumpCommandTest {
    // Captures stdout/stderr around a block, restoring the real streams afterward -- runDumpCommand
    // writes directly to System.out/System.err (it's the layer whose whole job is doing that), so
    // this is the only way to observe its output without spawning a real process.
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
        val (exitCode, stdout, stderr) = captureOutput { runDumpCommand(emptyList()) }

        assertEquals(1, exitCode)
        assertEquals("", stdout)
        assertTrue(stderr.contains("Usage"), "Expected a usage message, got: $stderr")
    }

    @Test
    fun `returns exit code 1 and prints the failure message to stderr for a missing file`() {
        val (exitCode, stdout, stderr) = captureOutput { runDumpCommand(listOf("/nonexistent/does-not-exist.jpg")) }

        assertEquals(1, exitCode)
        assertEquals("", stdout)
        assertTrue(stderr.contains("not found"), "Expected a 'not found' message, got: $stderr")
    }

    @Test
    fun `returns exit code 0 and prints JSON to stdout for a real PNG`() {
        val file = File.createTempFile("dump-command-test-", ".png")
        file.deleteOnExit()
        ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "color=blue:size=16x16",
            "-frames:v", "1", file.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()

        val (exitCode, stdout, stderr) = captureOutput { runDumpCommand(listOf(file.absolutePath)) }

        assertEquals(0, exitCode)
        assertEquals("", stderr)
        assertTrue(stdout.contains("\"IHDR\""), "Expected a PNG IHDR node in stdout, got: $stdout")
        file.delete()
    }
}
