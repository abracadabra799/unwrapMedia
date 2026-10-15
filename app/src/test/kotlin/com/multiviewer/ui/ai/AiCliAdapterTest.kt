package com.multiviewer.ui.ai

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiCliAdapterTest {
    @Test
    fun `text runs are unchanged for every provider`() {
        for (p in AiProviderId.entries) {
            val adapter = adapterFor(p)
            val inv = adapter.invocation("hello")!!
            assertEquals(adapter.arguments(), inv.arguments, p.name)
            assertEquals("hello", inv.stdin, p.name)
            assertFalse(inv.imagesInline, p.name)
            assertEquals(AiParsedOutput.Answer("hi"), inv.parse("  hi \n"), p.name)
        }
    }

    @Test
    fun `claude image runs use stream-json with the image blocks before the question`() {
        val inv = ClaudeCliAdapter().invocation(
            "what \"is\" this?\nline2",
            listOf(AiImageAttachment(File("/unused.png"), byteArrayOf(1, 2, 3))),
        )!!
        assertEquals(
            listOf("-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose",
                "--tools", "", "--strict-mcp-config", "--no-session-persistence"),
            inv.arguments,
        )
        assertTrue(inv.imagesInline)
        assertTrue(inv.stdin.endsWith("\n"))
        assertEquals(1, inv.stdin.trimEnd('\n').lines().size)
        assertFalse(inv.stdin.contains("/unused.png"))
        val msg = parseJsonLoose(inv.stdin.trim()) as Map<*, *>
        assertEquals("user", msg["type"])
        val message = msg["message"] as Map<*, *>
        assertEquals("user", message["role"])
        val content = message["content"] as List<*>
        assertEquals(2, content.size)
        val image = content[0] as Map<*, *>
        assertEquals("image", image["type"])
        assertEquals(mapOf("type" to "base64", "media_type" to "image/png", "data" to "AQID"), image["source"])
        assertEquals(mapOf("type" to "text", "text" to "what \"is\" this?\nline2"), content[1])
        assertEquals(AiParsedOutput.Answer("red"), inv.parse("""{"type":"result","subtype":"success","is_error":false,"result":"red"}"""))
    }

    @Test
    fun `claude web search keeps its only tool in image runs`() {
        val args = ClaudeCliAdapter(webSearch = true)
            .invocation("q", listOf(AiImageAttachment(File("x.png"), ByteArray(1))))!!.arguments
        val tools = args.indexOf("--tools")
        assertEquals("WebSearch", args[tools + 1])
        assertEquals(listOf("--allowedTools", "WebSearch"), args.takeLast(2))
    }

    @Test
    fun `codex image flags end before the stdin marker and the prompt stays plain`() {
        val inv = CodexCliAdapter().invocation("q", listOf(AiImageAttachment(File("/t/view-1.png"), ByteArray(0))))!!
        assertEquals(listOf("exec", "-i", "/t/view-1.png", "--sandbox"), inv.arguments.take(4))
        assertEquals("-", inv.arguments.last())
        assertEquals("q", inv.stdin)
        assertFalse(inv.imagesInline)
    }

    @Test
    fun `claude, codex and antigravity can attach images, gemini cannot`() {
        assertEquals(
            setOf(AiProviderId.CLAUDE, AiProviderId.CODEX, AiProviderId.AGY),
            AiProviderId.entries.filter { adapterFor(it).canAttachImages }.toSet(),
        )
        assertEquals(null, adapterFor(AiProviderId.GEMINI).invocation("q", listOf(AiImageAttachment(File("x.png"), ByteArray(1)))))
    }

    @Test
    fun `antigravity image runs name the work-dir files for view_file and keep the prompt`() {
        val adapter = AgyCliAdapter()
        val inv = adapter.invocation(
            "PROMPT",
            listOf(
                AiImageAttachment(File("/tmp/unwrapmedia-ai-1/view-1.png"), ByteArray(1)),
                AiImageAttachment(File("/tmp/unwrapmedia-ai-1/view-2.png"), ByteArray(1)),
            ),
        )!!
        assertEquals(adapter.arguments(), inv.arguments)
        assertFalse(inv.imagesInline)
        assertTrue(inv.stdin.contains("view_file"), inv.stdin)
        assertTrue(inv.stdin.contains("view-1.png, view-2.png"), inv.stdin)
        assertFalse(inv.stdin.contains("/tmp/"), inv.stdin) // file names only: the CLI runs in that directory
        assertTrue(inv.stdin.endsWith("\n\nPROMPT"), inv.stdin)
        assertEquals(AiParsedOutput.Answer("blue"), inv.parse("blue\n"))
    }
}
