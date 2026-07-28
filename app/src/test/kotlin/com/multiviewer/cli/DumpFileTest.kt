package com.multiviewer.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DumpFileTest {
    @Test
    fun `dumpFile returns Failure when the file does not exist`() {
        val result = dumpFile(File("/nonexistent/path/does-not-exist.jpg"))

        assertTrue(result is DumpResult.Failure)
        assertTrue((result as DumpResult.Failure).message.contains("not found"), "Expected a 'not found' message, got: ${result.message}")
    }

    @Test
    fun `dumpFile returns Failure for an unsupported extension`() {
        val file = File.createTempFile("dump-test-", ".xyz")
        file.deleteOnExit()
        file.writeBytes(ByteArray(4))

        val result = dumpFile(file)

        assertTrue(result is DumpResult.Failure)
        assertTrue((result as DumpResult.Failure).message.contains("xyz"), "Expected the message to mention the extension, got: ${result.message}")
        file.delete()
    }

    @Test
    fun `dumpFile returns Success with a JSON tree for a real PNG`() {
        val file = File.createTempFile("dump-test-", ".png")
        file.deleteOnExit()
        ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "color=red:size=32x24",
            "-frames:v", "1", file.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()

        val result = dumpFile(file)

        assertTrue(result is DumpResult.Success)
        val json = (result as DumpResult.Success).json
        assertTrue(json.contains("\"file\": \"${file.name}\""), "Expected the file name in the output, got: $json")
        assertTrue(json.contains("\"IHDR\""), "Expected a PNG IHDR node in the output, got: $json")
        file.delete()
    }

    @Test
    fun `dumpFile returns Success with a JSON tree for a real MP4`() {
        val file = File.createTempFile("dump-test-", ".mp4")
        file.deleteOnExit()
        ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "testsrc=duration=1:size=64x48:rate=10",
            file.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()

        val result = dumpFile(file)

        assertTrue(result is DumpResult.Success)
        val json = (result as DumpResult.Success).json
        assertTrue(json.contains("\"ftyp\""), "Expected an ftyp box in the output, got: $json")
        file.delete()
    }
}
