package com.multiviewer.ui.ai

import com.multiviewer.parser.BoxNode
import com.multiviewer.ui.ContentFileIdentity
import com.multiviewer.ui.MediaType
import com.multiviewer.ui.TabState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiChatControllerTest {
    private val dir = Files.createTempDirectory("ai-chat").toFile()

    @AfterTest
    fun cleanup() { dir.deleteRecursively() }

    private fun tab(name: String): TabState {
        val file = File(dir, name).apply { writeBytes(ByteArray(100)) }
        return TabState(file).apply {
            type = MediaType.IMAGE
            root = BoxNode("root", 0, 0, 100, listOf(BoxNode("first", 0, 8, 50), BoxNode("second", 50, 8, 50)))
            parsedIdentity = ContentFileIdentity.capture(file)
        }
    }

    private suspend fun waitFor(condition: () -> Boolean) = withTimeout(5_000) { while (!condition()) yield() }

    @Test
    fun `the context uses the selection at send time and the reply lands on the sending tab only`() = runBlocking {
        val a = tab("a.jpg")
        val b = tab("b.jpg")
        a.selected = a.root!!.children[0]
        val gate = CompletableDeferred<Unit>()
        var sentPrompt = ""
        val controller = AiChatController(this, now = { 1L }, ioDispatcher = Dispatchers.Default, runner = { _, _, prompt, _ ->
            sentPrompt = prompt
            gate.await()
            AiCliResult.Answer("answer for a", truncated = false)
        })
        controller.send(a, "what is this?", AiProviderId.CLAUDE, File("/bin/true"))
        a.selected = a.root!!.children[1] // user moves on before the answer arrives
        waitFor { sentPrompt.isNotEmpty() }
        gate.complete(Unit)
        waitFor { a.aiConversation.status == AiRunStatus.FINISHED }
        assertTrue(sentPrompt.contains("경로: root > first"), sentPrompt)
        assertFalse(sentPrompt.contains("경로: root > second"), sentPrompt)
        assertEquals("answer for a", a.aiConversation.messages.last().text)
        assertTrue(b.aiConversation.messages.isEmpty())
    }

    @Test
    fun `stop cancels the request and records a visible notice`() = runBlocking {
        val a = tab("a.jpg")
        val controller = AiChatController(this, now = { 1L }, ioDispatcher = Dispatchers.Default, runner = { _, _, _, _ -> awaitCancellation() })
        controller.send(a, "q", AiProviderId.CODEX, File("/bin/true"))
        waitFor { a.aiJob != null && a.aiConversation.status == AiRunStatus.RUNNING }
        controller.stop(a)
        waitFor { a.aiConversation.status == AiRunStatus.CANCELLED }
        assertEquals(AiRole.NOTICE, a.aiConversation.messages.last().role)
    }

    @Test
    fun `failures, missing CLIs and stale files are notices, never answers`() = runBlocking {
        val a = tab("a.jpg")
        val auth = AiChatController(this, now = { 1L }, ioDispatcher = Dispatchers.Default, runner = { _, _, _, _ ->
            AiCliResult.Failed(AiFailure.AUTH_REQUIRED, 1, "Please run /login")
        })
        auth.send(a, "q", AiProviderId.GEMINI, File("/bin/true"))
        waitFor { a.aiConversation.status == AiRunStatus.FAILED }
        assertEquals(AiCapabilityUpdate(AiProviderId.GEMINI, failure = AiFailure.AUTH_REQUIRED), auth.lastCapabilityUpdate)

        val missing = AiChatController(this, now = { 1L }, ioDispatcher = Dispatchers.Default, runner = { _, _, _, _ -> error("must not run") })
        missing.send(a, "q", AiProviderId.CLAUDE, null)
        waitFor { a.aiConversation.messages.size == 4 }
        assertEquals(AiRole.NOTICE, a.aiConversation.messages.last().role)

        val stale = AiChatController(this, now = { 1L }, ioDispatcher = Dispatchers.Default, runner = { _, _, _, current ->
            if (current()) AiCliResult.Answer("x", false) else AiCliResult.StaleFile
        })
        a.file.appendBytes(byteArrayOf(1))
        stale.send(a, "q", AiProviderId.CLAUDE, File("/bin/true"))
        waitFor { a.aiConversation.messages.size == 6 }
        assertEquals(AiRole.NOTICE, a.aiConversation.messages.last().role)
        assertEquals(0, a.aiConversation.messages.count { it.role == AiRole.ASSISTANT })
    }

    @Test
    fun `a second send while running is ignored`() = runBlocking {
        val a = tab("a.jpg")
        var runs = 0
        val controller = AiChatController(this, now = { 1L }, ioDispatcher = Dispatchers.Default, runner = { _, _, _, _ -> runs++; awaitCancellation() })
        controller.send(a, "q1", AiProviderId.CLAUDE, File("/bin/true"))
        waitFor { a.aiConversation.status == AiRunStatus.RUNNING }
        controller.send(a, "q2", AiProviderId.CLAUDE, File("/bin/true"))
        yield()
        assertEquals(1, a.aiConversation.messages.count { it.role == AiRole.USER })
        controller.stop(a)
        waitFor { a.aiConversation.status == AiRunStatus.CANCELLED }
        assertEquals(1, runs)
    }
}
