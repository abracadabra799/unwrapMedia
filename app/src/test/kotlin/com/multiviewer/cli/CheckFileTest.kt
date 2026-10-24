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

    @Test
    fun `buildPrompt adapts domain context for JPEG and HEIC files`() {
        val jpegFile = File("sample.jpg")
        val jpegPrompt = AiDiagnosticPromptBuilder.buildPrompt(jpegFile, null, emptyList())
        assertTrue(jpegPrompt.contains("JPEG"))
        assertTrue(jpegPrompt.contains("정상 파일입니다"))

        val heicFile = File("sample.heic")
        val heicPrompt = AiDiagnosticPromptBuilder.buildPrompt(heicFile, null, emptyList())
        assertTrue(heicPrompt.contains("HEIF/HEIC"))
        assertTrue(heicPrompt.contains("정상 파일입니다"))
    }

    @Test
    fun `box boundary and incomplete header defects are critical regardless of box type`() {
        val messages = listOf(
            "Declared size 130 extends 32 byte(s) past the end of its parent",
            "Declared size 4 is smaller than header size 8",
            "Declared a 64-bit size but only 12 byte(s) remain",
            "Trailing 4 byte(s): too short for a box header",
        )
        for (type in listOf("udta", "mdat", "?")) {
            for (message in messages) {
                assertEquals("CRITICAL", AiDiagnosticPromptBuilder.determineSeverity(type, message), "$type: $message")
            }
        }
    }

    @Test
    fun `determineSeverity categorizes critical, warning, and info correctly`() {
        assertEquals("CRITICAL", AiDiagnosticPromptBuilder.determineSeverity("mdat", "corrupt box length exceeds file size"))
        assertEquals("CRITICAL", AiDiagnosticPromptBuilder.determineSeverity("stbl", "missing required stsd box"))
        assertEquals("WARNING", AiDiagnosticPromptBuilder.determineSeverity("trun", "sample_duration mismatch with stts"))
        assertEquals("INFO", AiDiagnosticPromptBuilder.determineSeverity("udta", "custom vendor metadata encountered"))
    }

    @Test
    fun `ClipboardUtil copies text safely without crashing`() {
        val success = com.multiviewer.util.ClipboardUtil.copyToClipboard("unwrapMedia test clipboard string")
        assertTrue(success)
    }

    private fun movie(name: String, spec: com.multiviewer.parser.conformance.isobmff.MovieSpec =
        com.multiviewer.parser.conformance.isobmff.MovieSpec()): File {
        val dir = kotlin.io.path.createTempDirectory("check-iso-").toFile().also { it.deleteOnExit() }
        return File(dir, name).also { it.deleteOnExit(); it.writeBytes(com.multiviewer.parser.conformance.isobmff.buildMovie(spec)) }
    }

    private fun checkJson(file: File): String =
        (checkFile(file) as? CheckResult.Success ?: error("check failed: ${checkFile(file)}")).json

    @Test
    fun `checkFile adds containerConformance for an MP4`() {
        val json = checkJson(movie("x.mp4"))
        assertTrue(json.contains("\"containerConformance\": {"), json)
        assertTrue(Regex("\"ruleId\": \"bmff.moov.count\",\\s*\"status\": \"PASS\"").containsMatchIn(json), json)
        assertTrue(json.contains("\"format\": \"ISO-BMFF\""), json)
        assertTrue(json.contains("\"scope\": \"필수 박스 / Required boxes\""), json)
    }

    @Test
    fun `checkFile reports a moov-less MP4 as FAIL`() {
        val json = checkJson(movie("nomoov.mp4", com.multiviewer.parser.conformance.isobmff.MovieSpec(dropBox = "moov")))
        val section = json.substringAfter("\"containerConformance\": {")
        assertTrue(section.trimStart().startsWith("\"status\": \"FAIL\""), section)
        assertTrue(Regex("\"ruleId\": \"bmff.moov.count\",\\s*\"status\": \"FAIL\"").containsMatchIn(json), json)
    }

    @Test
    fun `checkFile omits containerConformance for formats without a container checker`() {
        val file = File.createTempFile("check-iso-", ".png").also {
            it.deleteOnExit(); it.writeBytes(com.multiviewer.parser.integrity.pngBytes())
        }
        val json = checkJson(file)
        assertTrue(!json.contains("containerConformance"), json)
    }

    @Test
    fun `checkFile includes the JPEG containerConformance report`() {
        val file = File.createTempFile("check-jpeg-", ".jpg").also {
            it.deleteOnExit(); it.writeBytes(com.multiviewer.parser.integrity.jpegBytes())
        }
        val json = checkJson(file)
        assertTrue(json.contains("\"containerConformance\""), json)
        assertTrue(json.contains("t81.soi"), json)
    }
    @Test
    fun `analysis case includes containerConformance with the check JSON shape`() {
        val file = movie("nomoov.mp4", com.multiviewer.parser.conformance.isobmff.MovieSpec(dropBox = "moov"))
        val result = checkFile(file, includeCase = true) as CheckResult.Success
        val case = result.analysisCaseJson!!
        val caseSection = case.substringAfter("\"containerConformance\": {")
        assertTrue(case.contains("\"containerConformance\": {"), case)
        assertTrue(caseSection.trimStart().startsWith("\"status\": \"FAIL\""), caseSection)
        assertTrue(Regex("\"ruleId\": \"bmff.moov.count\",\\s*\"status\": \"FAIL\"").containsMatchIn(case), case)
        // Same shape as `check`: identical key sequence inside the section.
        val keys = Regex("\"([A-Za-z]+)\":")
        fun keySeq(s: String) = keys.findAll(s.substringAfter("\"containerConformance\": {").substringBefore("\"ruleId\": \"bmff.fragmented\"")).map { it.groupValues[1] }.toList()
        assertEquals(keySeq(result.json), keySeq(case))
        assertTrue(keySeq(case).size > 100)
        assertTrue(!case.contains(file.parentFile.absolutePath), case)
    }

    private fun sniffFixture(name: String): File {
        val dir = kotlin.io.path.createTempDirectory("check-heif-").toFile().also { it.deleteOnExit() }
        return File(javaClass.getResource("/format-sniff/$name")!!.toURI()).copyTo(File(dir, name)).also { it.deleteOnExit() }
    }

    @Test
    fun `checkFile adds HEIF and AVIF containerConformance for image files`() {
        val heic = checkFile(sniffFixture("a.heic"), includeCase = true) as CheckResult.Success
        assertTrue(heic.json.contains("\"containerConformance\": {"), heic.json)
        val heicSection = heic.json.substringAfter("\"containerConformance\": {")
        assertTrue(heicSection.contains("\"format\": \"HEIF\""), heicSection)
        assertTrue(Regex("\"ruleId\": \"heif.meta.present\",\\s*\"status\": \"PASS\"").containsMatchIn(heicSection), heicSection)
        assertTrue(heic.json.contains("\"imageIntegrity\""), heic.json)
        assertTrue(heic.analysisCaseJson!!.contains("\"containerConformance\": {"), heic.analysisCaseJson!!)
        val avif = checkJson(sniffFixture("a.avif"))
        assertTrue(avif.substringAfter("\"containerConformance\": {").contains("\"format\": \"AVIF\""), avif)
        assertTrue(avif.contains("\"url\": \"https://aomediacodec.github.io/av1-avif/"), avif)
    }

    @Test
    fun `analysis case omits containerConformance for formats without a container checker`() {
        val file = File.createTempFile("check-iso-", ".png").also {
            it.deleteOnExit(); it.writeBytes(com.multiviewer.parser.integrity.pngBytes())
        }
        val result = checkFile(file, includeCase = true) as CheckResult.Success
        assertTrue(!result.analysisCaseJson!!.contains("containerConformance"))
    }


    @Test
    fun `containerConformanceJson renders the citation object and omits null page and null citation`() {
        val file = File.createTempFile("citation-json-", ".avif").apply { deleteOnExit() }
        val withPage = com.multiviewer.parser.conformance.SpecCitation("ITU-T H.265", "§7.3", 7, "https://example.org/h265.pdf#page=7", true)
        val noPage = com.multiviewer.parser.conformance.SpecCitation("ISO/IEC 23008-12:2025", "§9", null, "https://www.iso.org/standard/1.html", false)
        fun f(id: String, c: com.multiviewer.parser.conformance.SpecCitation?) = com.multiviewer.parser.conformance.ConformanceFinding(
            com.multiviewer.parser.conformance.ConformanceRule(id, "t", "s", null, "ftyp", c),
            com.multiviewer.parser.conformance.ConformanceStatus.FAIL, emptyList(), emptyList())
        val json = containerConformanceJson(
            com.multiviewer.parser.conformance.ConformanceReport("HEIF", "p", listOf(f("a", withPage), f("b", noPage), f("c", null))), file,
        ).renderCompact()
        assertTrue(json.contains("\"ruleId\":\"a\""), json)
        assertTrue(json.contains("\"citation\":{\"document\":\"ITU-T H.265\",\"section\":\"§7.3\",\"page\":7,\"url\":\"https://example.org/h265.pdf#page=7\",\"verified\":true}"), json)
        assertTrue(json.contains("\"citation\":{\"document\":\"ISO/IEC 23008-12:2025\",\"section\":\"§9\",\"url\":\"https://www.iso.org/standard/1.html\",\"verified\":false}"), json)
        val third = json.substringAfter("\"ruleId\":\"c\"")
        assertTrue(!third.contains("citation"), json)
    }
}
