package com.multiviewer.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class ParseForCliTest {
    @Test
    fun `parseForCli returns Failure when the file does not exist`() {
        val result = parseForCli(File("/nonexistent/path/does-not-exist.jpg"))

        assertTrue(result is CliParseResult.Failure)
        assertTrue((result as CliParseResult.Failure).message.contains("not found"), "Expected a 'not found' message, got: ${result.message}")
    }

    @Test
    fun `parseForCli returns Failure for an unsupported extension`() {
        val file = File.createTempFile("parse-for-cli-test-", ".xyz")
        file.deleteOnExit()
        file.writeBytes(ByteArray(4))

        val result = parseForCli(file)

        assertTrue(result is CliParseResult.Failure)
        assertTrue((result as CliParseResult.Failure).message.contains("xyz"), "Expected the message to mention the extension, got: ${result.message}")
        file.delete()
    }

    @Test
    fun `parseForCli returns Success with the parsed root for a real PNG`() {
        val file = File.createTempFile("parse-for-cli-test-", ".png")
        file.deleteOnExit()
        ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "color=red:size=32x24",
            "-frames:v", "1", file.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()

        val result = parseForCli(file)

        assertTrue(result is CliParseResult.Success)
        val success = result as CliParseResult.Success
        assertTrue(success.root.children.any { it.type == "IHDR" }, "Expected a PNG IHDR node, got: ${success.root.children.map { it.type }}")
        file.delete()
    }
}
