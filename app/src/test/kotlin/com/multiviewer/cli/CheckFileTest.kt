package com.multiviewer.cli

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.WarningEntry
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CheckFileTest {
    @Test
    fun `buildCheckJson lists each warning with its node type, offset, and message`() {
        val node1 = BoxNode(type = "QuantizationTable", offset = 20L, headerSize = 1, size = 65L)
        val node2 = BoxNode(type = "HuffmanTable", offset = 6699L, headerSize = 1, size = 30L)
        val warnings = listOf(
            WarningEntry(node1, "Declared size 999 extends 934 byte(s) past the end of its parent"),
            WarningEntry(node2, "declares 12 code(s) but not enough symbol data remains"),
        )
        val file = File.createTempFile("check-file-test-", ".jpg")
        file.deleteOnExit()

        val json = buildCheckJson(file, warnings)

        val expected = "{\n" +
            "  \"file\": \"${file.name}\",\n" +
            "  \"warningCount\": 2,\n" +
            "  \"warnings\": [\n" +
            "    {\n" +
            "      \"type\": \"QuantizationTable\",\n" +
            "      \"offset\": 20,\n" +
            "      \"message\": \"Declared size 999 extends 934 byte(s) past the end of its parent\"\n" +
            "    },\n" +
            "    {\n" +
            "      \"type\": \"HuffmanTable\",\n" +
            "      \"offset\": 6699,\n" +
            "      \"message\": \"declares 12 code(s) but not enough symbol data remains\"\n" +
            "    }\n" +
            "  ]\n" +
            "}"
        assertEquals(expected, json)
        file.delete()
    }

    @Test
    fun `buildCheckJson reports an empty warnings array and zero count for a clean tree`() {
        val file = File.createTempFile("check-file-test-", ".jpg")
        file.deleteOnExit()

        val json = buildCheckJson(file, emptyList())

        assertEquals(
            "{\n" +
                "  \"file\": \"${file.name}\",\n" +
                "  \"warningCount\": 0,\n" +
                "  \"warnings\": []\n" +
                "}",
            json,
        )
        file.delete()
    }

    @Test
    fun `checkFile returns Failure when the file does not exist`() {
        val result = checkFile(File("/nonexistent/path/does-not-exist.jpg"))

        assertTrue(result is CheckResult.Failure)
        assertTrue((result as CheckResult.Failure).message.contains("not found"), "Expected a 'not found' message, got: ${result.message}")
    }

    @Test
    fun `checkFile returns Success with warningCount 0 for a clean real PNG`() {
        val file = File.createTempFile("check-file-test-", ".png")
        file.deleteOnExit()
        ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "color=blue:size=16x16",
            "-frames:v", "1", file.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()

        val result = checkFile(file)

        assertTrue(result is CheckResult.Success)
        val success = result as CheckResult.Success
        assertTrue(success.json.contains("\"warningCount\": 0"), "Expected a clean PNG to report 0 warnings, got: ${success.json}")
        assertTrue(success.prompt.contains("정상 파일입니다"), "Expected prompt to report clean file, got: ${success.prompt}")
        file.delete()
    }

    @Test
    fun `buildPrompt formats warnings with severity and domain context`() {
        val node = BoxNode(type = "trun", offset = 104230L, headerSize = 8, size = 500L)
        val warnings = listOf(
            WarningEntry(node, "sample_duration mismatch with stts: expected 1000, got 1001"),
        )
        val file = File.createTempFile("prompt-test-", ".mp4")
        file.deleteOnExit()

        val prompt = AiDiagnosticPromptBuilder.buildPrompt(file, null, warnings)

        assertTrue(prompt.contains("비디오 코덱, ISOBMFF/HEIF 컨테이너 스펙"))
        assertTrue(prompt.contains("\"box\": \"trun\""))
        assertTrue(prompt.contains("\"severity\": \"WARNING\""))
        assertTrue(prompt.contains("sample_duration mismatch with stts"))
        assertTrue(prompt.contains("FFmpeg 리먹싱 커맨드"))
        file.delete()
    }

    @Test
    fun `runCheckCommand supports prompt and clipboard flags`() {
        val file = File.createTempFile("cli-test-", ".png")
        file.deleteOnExit()
        ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "color=red:size=8x8",
            "-frames:v", "1", file.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()

        val exitCode = runCheckCommand(listOf(file.absolutePath, "--prompt", "--clipboard"))
        assertEquals(0, exitCode)

        val helpCode = runCheckCommand(listOf("--help"))
        assertEquals(0, helpCode)

        file.delete()
    }
}
