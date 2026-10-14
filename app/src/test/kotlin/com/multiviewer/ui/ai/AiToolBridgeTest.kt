package com.multiviewer.ui.ai

import com.multiviewer.parser.BoxField
import com.multiviewer.parser.BoxNode
import com.multiviewer.ui.ContentFileIdentity
import com.multiviewer.ui.FrameInfo
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AiToolBridgeTest {
    private val dir = Files.createTempDirectory("ai-bridge").toFile()
    private val file = File(dir, "x.bin").apply { writeBytes(ByteArray(10_000) { (it % 251).toByte() }) }
    private val child = BoxNode("moov", 100, 8, 400, fields = listOf(BoxField("GPSLatitude", "37.5", 110, 4), BoxField("version", "0", 108, 1)))
    private val root = BoxNode("root", 0, 0, 10_000, listOf(BoxNode("ftyp", 0, 8, 100), child))
    private fun ctx() = AiToolContext(file, root, ContentFileIdentity.capture(file), contentCheckText = "CHECK TEXT",
        frames = listOf(FrameInfo(0, 'I', 500, 0.0, 9_000), FrameInfo(1, 'P', 120, 0.033, 9_500)), reports = mapOf("avsync" to "skew 12 ms"))

    @AfterTest
    fun cleanup() { dir.deleteRecursively() }

    @Test
    fun `only typed read-only requests are accepted`() {
        val lines = "Let me look.\n@@unwrapmedia hex 0x64 16\n@@unwrapmedia node 0x70\n@@unwrapmedia write 0 00\n" +
            "@@unwrapmedia hex /etc/passwd 0 10\n@@unwrapmedia report avsync\n@@unwrapmedia open other.jpg"
        val parsed = parseToolRequests(lines)
        assertEquals(6, parsed.size)
        assertEquals(AiToolRequest.Hex(0x64, 16), parsed[0].request)
        assertEquals(AiToolRequest.Node(0x70), parsed[1].request)
        assertEquals(null, parsed[2].request)
        assertEquals(null, parsed[3].request)
        assertEquals(AiToolRequest.Report("avsync"), parsed[4].request)
        assertEquals(null, parsed[5].request)
        assertTrue(parseToolRequests("no requests here").isEmpty())
    }

    @Test
    fun `hex reads are bounded to the file and to a size cap`() {
        val ok = executeToolRequest(AiToolRequest.Hex(100, 16), ctx())
        assertTrue(ok.ok, ok.text)
        assertTrue(ok.text.contains("64 65 66 67"), ok.text) // bytes 100..103 = 100%251..
        val big = executeToolRequest(AiToolRequest.Hex(0, 1_000_000), ctx())
        assertTrue(big.ok)
        assertTrue(big.text.contains("$MAX_TOOL_HEX_BYTES"), big.text)
        assertTrue(big.text.length <= MAX_TOOL_RESULT_CHARS + 100, big.text.length.toString())
        assertFalse(executeToolRequest(AiToolRequest.Hex(20_000, 4), ctx()).ok)
        assertFalse(executeToolRequest(AiToolRequest.Hex(-1, 4), ctx()).ok)
    }

    @Test
    fun `node, frame, check and report answers come from this tab's data with redaction`() {
        val node = executeToolRequest(AiToolRequest.Node(0x70), ctx())
        assertTrue(node.text.contains("moov") && node.text.contains("root > moov"), node.text)
        assertFalse(node.text.contains("37.5"), node.text)
        assertTrue(executeToolRequest(AiToolRequest.Frame(1), ctx()).text.contains("0.033"))
        assertFalse(executeToolRequest(AiToolRequest.Frame(7), ctx()).ok)
        assertEquals("CHECK TEXT", executeToolRequest(AiToolRequest.Check, ctx()).text.trim())
        assertTrue(executeToolRequest(AiToolRequest.Report("avsync"), ctx()).text.contains("12 ms"))
        assertFalse(executeToolRequest(AiToolRequest.Report("hdr"), ctx()).ok)
        assertTrue(executeToolRequest(AiToolRequest.Structure, ctx()).text.contains("moov"))
    }

    @Test
    fun `a changed file refuses every byte request`() {
        val context = ctx()
        file.appendBytes(byteArrayOf(1))
        val result = executeToolRequest(AiToolRequest.Hex(0, 4), context)
        assertFalse(result.ok)
        assertTrue(result.text.contains("changed"), result.text)
    }

    @Test
    fun `evidence references are extracted only when they point into this file`() {
        val refs = extractEvidenceRefs(
            "CRC fails at 0x1C6A and 0xFFFFFF; see frame #1 and #99, check png.crc, see https://www.w3.org/TR/png-3/.",
            fileSize = 10_000, checkIds = setOf("png.crc"), frameCount = 2,
        )
        assertEquals(
            listOf(AiEvidenceRef.ByteOffset(0x1C6A), AiEvidenceRef.Frame(1), AiEvidenceRef.Check("png.crc"), AiEvidenceRef.Url("https://www.w3.org/TR/png-3/")),
            refs,
        )
    }

    @Test
    fun `an offset resolves to the deepest node containing it`() {
        assertEquals("moov", deepestNodeAt(root, 0x70)?.type)
        assertEquals("ftyp", deepestNodeAt(root, 4)?.type)
        assertEquals("root", deepestNodeAt(root, 9_000)?.type)
    }

    @Test
    fun `web search only adds the WebSearch tool, and tool rounds never enter the history`() {
        assertEquals(listOf("--tools", "WebSearch"), ClaudeCliAdapter(webSearch = true).arguments().let { it.subList(it.indexOf("--tools"), it.indexOf("--tools") + 2) })
        assertEquals("", ClaudeCliAdapter().arguments().let { it[it.indexOf("--tools") + 1] })
        assertFalse(CodexCliAdapter().supportsWebSearch)
        val history = listOf(
            AiMessage(1, AiRole.USER, "q1", AiProviderId.CLAUDE, 1),
            AiMessage(2, AiRole.TOOL, "@@unwrapmedia hex → SECRET TOOL DUMP", AiProviderId.CLAUDE, 2),
            AiMessage(3, AiRole.ASSISTANT, "a1", AiProviderId.CLAUDE, 3),
        )
        val prompt = buildAiCliPrompt(history, "ctx", "q2")
        assertFalse(prompt.contains("SECRET TOOL DUMP"))
        assertTrue(prompt.contains("a1") && prompt.contains("q1"))
        assertTrue(prompt.contains(TOOL_PREFIX))
    }
}
