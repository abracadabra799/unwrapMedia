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
            isLoading = false
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
        val controller = AiChatController(this, now = { 1L }, ioDispatcher = Dispatchers.Default, runner = { _, _, prompt, _, _ ->
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
    fun `Codex web search choice reaches both CLI settings and the outgoing instructions`() = runBlocking {
        val a = tab("search.jpg")
        val calls = mutableListOf<Pair<List<String>, String>>()
        val controller = AiChatController(this, now = { 1L }, ioDispatcher = Dispatchers.Default,
            runner = { adapter, _, prompt, _, _ ->
                calls += adapter.arguments() to prompt
                AiCliResult.Answer("ok", false)
            })

        controller.send(a, "latest PNG spec?", AiProviderId.CODEX, File("/bin/true"), webSearch = true)
        waitFor { a.aiConversation.status == AiRunStatus.FINISHED }
        assertTrue(calls.single().first.contains("web_search=live"))
        assertTrue(calls.single().second.contains("웹 검색"))

        controller.send(a, "only local data", AiProviderId.CODEX, File("/bin/true"), webSearch = false)
        waitFor { calls.size == 2 && a.aiConversation.status == AiRunStatus.FINISHED }
        assertTrue(calls.last().first.contains("web_search=disabled"))
        assertTrue(calls.last().second.contains("외부 검색을 하지 마세요"))
    }

    @Test
    fun `stop cancels the request and records a visible notice`() = runBlocking {
        val a = tab("a.jpg")
        val controller = AiChatController(this, now = { 1L }, ioDispatcher = Dispatchers.Default, runner = { _, _, _, _, _ -> awaitCancellation() })
        controller.send(a, "q", AiProviderId.CODEX, File("/bin/true"))
        waitFor { a.aiJob != null && a.aiConversation.status == AiRunStatus.RUNNING }
        controller.stop(a)
        waitFor { a.aiConversation.status == AiRunStatus.CANCELLED }
        assertEquals(AiRole.NOTICE, a.aiConversation.messages.last().role)
    }

    @Test
    fun `failures, missing CLIs and stale files are notices, never answers`() = runBlocking {
        val a = tab("a.jpg")
        val auth = AiChatController(this, now = { 1L }, ioDispatcher = Dispatchers.Default, runner = { _, _, _, _, _ ->
            AiCliResult.Failed(AiFailure.AUTH_REQUIRED, 1, "Warning: True color (24-bit) support not detected.\nError authenticating: IneligibleTierError: tier gone\n    at x (a.js:1:2)")
        })
        auth.send(a, "q", AiProviderId.GEMINI, File("/bin/true"))
        waitFor { a.aiConversation.status == AiRunStatus.FAILED }
        assertEquals(AiCapabilityUpdate(AiProviderId.GEMINI, failure = AiFailure.AUTH_REQUIRED), auth.lastCapabilityUpdate)
        assertTrue(a.aiConversation.messages.last().text.contains("IneligibleTierError"), a.aiConversation.messages.last().text)

        val missing = AiChatController(this, now = { 1L }, ioDispatcher = Dispatchers.Default, runner = { _, _, _, _, _ -> error("must not run") })
        missing.send(a, "q", AiProviderId.CLAUDE, null)
        waitFor { a.aiConversation.messages.size == 4 }
        assertEquals(AiRole.NOTICE, a.aiConversation.messages.last().role)

        val stale = AiChatController(this, now = { 1L }, ioDispatcher = Dispatchers.Default, runner = { _, _, _, current, _ ->
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
        val controller = AiChatController(this, now = { 1L }, ioDispatcher = Dispatchers.Default, runner = { _, _, _, _, _ -> runs++; awaitCancellation() })
        controller.send(a, "q1", AiProviderId.CLAUDE, File("/bin/true"))
        waitFor { a.aiConversation.status == AiRunStatus.RUNNING }
        controller.send(a, "q2", AiProviderId.CLAUDE, File("/bin/true"))
        yield()
        assertEquals(1, a.aiConversation.messages.count { it.role == AiRole.USER })
        controller.stop(a)
        waitFor { a.aiConversation.status == AiRunStatus.CANCELLED }
        assertEquals(1, runs)
    }

    @Test
    fun `visual marks are taken at send time and a crop goes only to a verified image provider`() = runBlocking {
        val a = tab("a.jpg")
        a.aiImageSource = androidx.compose.ui.graphics.ImageBitmap(400, 200)
        a.aiViewport = ImageRegion(0, 0, 400, 200)
        a.aiPointer = ImagePoint(10, 20)
        a.aiMarked = ImageRegion(50, 60, 100, 40)
        var images = -1
        var prompt = ""
        fun controller(image: CapabilityState) = AiChatController(this, now = { 1L }, ioDispatcher = Dispatchers.Default,
            capabilities = { AiProviderCapabilities(it, image = image) },
            runner = { _, _, p, _, imgs -> prompt = p; images = imgs.size; AiCliResult.Answer("ok", false) })

        controller(CapabilityState.UNKNOWN).send(a, "q", AiProviderId.CODEX, File("/bin/true"))
        a.aiPointer = ImagePoint(300, 100) // moved after sending
        waitFor { a.aiConversation.status == AiRunStatus.FINISHED }
        assertEquals(0, images)
        assertTrue(prompt.contains("(10, 20)"), prompt)
        assertTrue(prompt.contains("not verified") || prompt.contains("확인되지 않음"), prompt)

        controller(CapabilityState.VERIFIED).send(a, "q", AiProviderId.CLAUDE, File("/bin/true"))
        waitFor { a.aiConversation.messages.count { it.role == AiRole.ASSISTANT } == 2 }
        assertEquals(1, images) // Claude passes images inline (stream-json)
        controller(CapabilityState.VERIFIED).send(a, "q", AiProviderId.CODEX, File("/bin/true"))
        waitFor { a.aiConversation.messages.count { it.role == AiRole.ASSISTANT } == 3 }
        assertEquals(1, images)
        assertTrue(prompt.contains("x=50, y=60, w=100, h=40"), prompt)
    }

    @Test
    fun `a failure while building the context is a notice and sending keeps working`() = runBlocking {
        val a = tab("a.jpg")
        var fail = true
        val controller = AiChatController(this, now = { 1L }, ioDispatcher = Dispatchers.Default,
            contextCapture = { if (fail) error("boom") else null },
            runner = { _, _, _, _, _ -> AiCliResult.Answer("ok", false) })
        controller.send(a, "q", AiProviderId.CLAUDE, File("/bin/true"))
        waitFor { a.aiConversation.status == AiRunStatus.FAILED }
        assertEquals(AiRole.NOTICE, a.aiConversation.messages.last().role)
        fail = false
        controller.send(a, "q2", AiProviderId.CLAUDE, File("/bin/true"))
        waitFor { a.aiConversation.status == AiRunStatus.FINISHED }
    }

    @Test
    fun `tool requests are answered by the app and the model is asked again, within a round limit`() = runBlocking {
        val a = tab("a.jpg")
        val prompts = mutableListOf<String>()
        val controller = AiChatController(this, now = { 1L }, ioDispatcher = Dispatchers.Default, runner = { _, _, p, _, _ ->
            prompts += p
            if (prompts.size == 1) AiCliResult.Answer("@@unwrapmedia hex 0x0 4", false) else AiCliResult.Answer("final answer", false)
        })
        controller.send(a, "q", AiProviderId.CLAUDE, File("/bin/true"))
        waitFor { a.aiConversation.status == AiRunStatus.FINISHED }
        assertEquals(2, prompts.size)
        assertTrue(prompts[1].contains("00 00 00 00"), prompts[1])
        assertEquals(listOf(AiRole.USER, AiRole.TOOL, AiRole.ASSISTANT), a.aiConversation.messages.map { it.role })
        assertEquals("final answer", a.aiConversation.messages.last().text)

        val greedy = AiChatController(this, now = { 1L }, ioDispatcher = Dispatchers.Default, runner = { _, _, _, _, _ ->
            AiCliResult.Answer("@@unwrapmedia structure", false)
        })
        greedy.send(a, "again", AiProviderId.CLAUDE, File("/bin/true"))
        waitFor { a.aiConversation.status != AiRunStatus.RUNNING && a.aiConversation.messages.size > 3 }
        waitFor { a.aiConversation.status == AiRunStatus.FAILED }
        assertEquals(MAX_TOOL_ROUNDS, a.aiConversation.messages.drop(3).count { it.role == AiRole.TOOL })
        assertEquals(AiRole.NOTICE, a.aiConversation.messages.last().role)
    }

    @Test
    fun `image input is verified only by an exact one-word color`() = runBlocking {
        for ((reply, expected) in listOf("I cannot see any image, so I can't say if it is red, green or blue." to CapabilityState.UNSUPPORTED)) {
            val controller = AiChatController(this, now = { 1L }, ioDispatcher = Dispatchers.Default, runner = { _, _, _, _, _ -> AiCliResult.Answer(reply, false) })
            controller.verifyImageInput(AiProviderId.CODEX, File("/bin/true")).join()
            assertEquals(expected, controller.lastCapabilityUpdate?.image)
        }
        val gemini = AiChatController(this, now = { 1L }, ioDispatcher = Dispatchers.Default, runner = { _, _, _, _, _ -> error("must not run") })
        gemini.verifyImageInput(AiProviderId.GEMINI, File("/bin/true")).join()
        assertEquals(CapabilityState.UNSUPPORTED, gemini.lastCapabilityUpdate?.image)
    }

    @Test
    fun `nothing is sent while the tab is still loading`() = runBlocking {
        val a = tab("a.jpg").apply { isLoading = true }
        val controller = AiChatController(this, now = { 1L }, ioDispatcher = Dispatchers.Default, runner = { _, _, _, _, _ -> error("must not run") })
        controller.send(a, "q", AiProviderId.CLAUDE, File("/bin/true"))
        yield()
        assertTrue(a.aiConversation.messages.isEmpty())
    }
}
