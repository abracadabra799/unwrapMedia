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

        controller(CapabilityState.UNSUPPORTED).send(a, "q", AiProviderId.CODEX, File("/bin/true"))
        a.aiPointer = ImagePoint(300, 100) // moved after sending
        waitFor { a.aiConversation.status == AiRunStatus.FINISHED }
        assertEquals(0, images)
        assertTrue(prompt.contains("(10, 20)"), prompt)
        assertTrue(prompt.contains("확인용 이미지를 읽지 못함"), prompt)

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
    fun `nothing is sent while the tab is still loading`() = runBlocking {
        val a = tab("a.jpg").apply { isLoading = true }
        val controller = AiChatController(this, now = { 1L }, ioDispatcher = Dispatchers.Default, runner = { _, _, _, _, _ -> error("must not run") })
        controller.send(a, "q", AiProviderId.CLAUDE, File("/bin/true"))
        yield()
        assertTrue(a.aiConversation.messages.isEmpty())
    }

    private fun probeAnswer(imgs: List<java.awt.image.BufferedImage>) =
        IMAGE_PROBE_COLORS.first { it.second.rgb == imgs.single().getRGB(0, 0) }.first

    private fun imageTab(name: String = "a.jpg"): TabState = tab(name).apply {
        aiImageSource = androidx.compose.ui.graphics.ImageBitmap(400, 200)
        aiViewport = ImageRegion(0, 0, 400, 200)
    }

    @Test
    fun `stopping during the image check cancels it and clears the checking flag`() = runBlocking {
        val a = imageTab()
        val probeStarted = CompletableDeferred<Unit>()
        var mainRuns = 0
        val controller = AiChatController(this, now = { 1L }, ioDispatcher = Dispatchers.Default,
            capabilities = { AiProviderCapabilities(it, image = CapabilityState.UNKNOWN) },
            runner = { _, _, p, _, _ ->
                if (p == IMAGE_PROBE_PROMPT) {
                    probeStarted.complete(Unit)
                    awaitCancellation()
                } else {
                    mainRuns++
                    AiCliResult.Answer("ok", false)
                }
            })
        controller.send(a, "q", AiProviderId.CLAUDE, File("/bin/true"))
        probeStarted.await()
        assertTrue(a.aiImageChecking)
        assertTrue(controller.isRunning(a))
        controller.stop(a)
        waitFor { !controller.isRunning(a) }
        // isActive turns false at cancellation, before the finally that clears the flag has run: join first.
        a.aiJob?.join()
        assertFalse(a.aiImageChecking)
        assertEquals(0, mainRuns)
    }

    @Test
    fun `the first image send checks image input once, then attaches the crop`() = runBlocking {
        val a = imageTab()
        var state = CapabilityState.UNKNOWN
        val prompts = mutableListOf<String>()
        var mainImages = -1
        var checkingSeen = false
        val controller = AiChatController(this, now = { 1L }, ioDispatcher = Dispatchers.Default,
            capabilities = { AiProviderCapabilities(it, image = state) },
            onCapabilityUpdate = { u -> u.image?.let { state = it } },
            runner = { _, _, p, _, imgs ->
                prompts += p
                if (p == IMAGE_PROBE_PROMPT) {
                    checkingSeen = a.aiImageChecking
                    AiCliResult.Answer(probeAnswer(imgs), false)
                } else {
                    mainImages = imgs.size
                    AiCliResult.Answer("ok", false)
                }
            })
        controller.send(a, "q", AiProviderId.CLAUDE, File("/bin/true"))
        waitFor { a.aiConversation.status == AiRunStatus.FINISHED }
        assertEquals(listOf(IMAGE_PROBE_PROMPT), prompts.take(1))
        assertEquals(2, prompts.size)
        assertEquals(1, mainImages)
        assertEquals(CapabilityState.VERIFIED, state)
        assertTrue(checkingSeen)
        assertFalse(a.aiImageChecking)

        controller.send(a, "q2", AiProviderId.CLAUDE, File("/bin/true"))
        waitFor { a.aiConversation.messages.count { it.role == AiRole.ASSISTANT } == 2 }
        assertEquals(3, prompts.size) // verified: no second check
        assertEquals(1, mainImages)
    }

    @Test
    fun `a wrong check answer sends coordinates only and is not retried`() = runBlocking {
        val a = imageTab()
        var state = CapabilityState.UNKNOWN
        var probes = 0
        var prompt = ""
        var mainImages = -1
        val controller = AiChatController(this, now = { 1L }, ioDispatcher = Dispatchers.Default,
            capabilities = { AiProviderCapabilities(it, image = state) },
            onCapabilityUpdate = { u -> u.image?.let { state = it } },
            runner = { _, _, p, _, imgs ->
                if (p == IMAGE_PROBE_PROMPT) {
                    probes++
                    AiCliResult.Answer("I cannot see any image, so I can't say if it is red, green or blue.", false)
                } else {
                    prompt = p
                    mainImages = imgs.size
                    AiCliResult.Answer("ok", false)
                }
            })
        controller.send(a, "q", AiProviderId.CODEX, File("/bin/true"))
        waitFor { a.aiConversation.status == AiRunStatus.FINISHED }
        assertEquals(CapabilityState.UNSUPPORTED, state)
        assertEquals(0, mainImages)
        assertTrue(prompt.contains("확인용 이미지를 읽지 못함"), prompt)

        controller.send(a, "q2", AiProviderId.CODEX, File("/bin/true"))
        waitFor { a.aiConversation.messages.count { it.role == AiRole.ASSISTANT } == 2 }
        assertEquals(1, probes)
    }

    @Test
    fun `a check run that fails leaves image input unknown and the next send checks again`() = runBlocking {
        val a = imageTab()
        var state = CapabilityState.UNKNOWN
        var probes = 0
        var prompt = ""
        val controller = AiChatController(this, now = { 1L }, ioDispatcher = Dispatchers.Default,
            capabilities = { AiProviderCapabilities(it, image = state) },
            onCapabilityUpdate = { u -> u.image?.let { state = it } },
            runner = { _, _, p, _, _ ->
                if (p == IMAGE_PROBE_PROMPT) {
                    probes++
                    AiCliResult.Failed(AiFailure.EXIT_ERROR, 1, "boom")
                } else {
                    prompt = p
                    AiCliResult.Answer("ok", false)
                }
            })
        controller.send(a, "q", AiProviderId.CLAUDE, File("/bin/true"))
        waitFor { a.aiConversation.status == AiRunStatus.FINISHED }
        assertEquals(CapabilityState.UNKNOWN, state)
        assertTrue(prompt.contains("확인되지 않음"), prompt)

        controller.send(a, "q2", AiProviderId.CLAUDE, File("/bin/true"))
        waitFor { a.aiConversation.messages.count { it.role == AiRole.ASSISTANT } == 2 }
        assertEquals(2, probes)
    }

    @Test
    fun `an exception thrown by the check run still sends the question with coordinates only`() = runBlocking {
        val a = imageTab()
        var state: CapabilityState? = null
        var prompt = ""
        val controller = AiChatController(this, now = { 1L }, ioDispatcher = Dispatchers.Default,
            capabilities = { AiProviderCapabilities(it, image = CapabilityState.UNKNOWN) },
            onCapabilityUpdate = { u -> u.image?.let { state = it } },
            runner = { _, _, p, _, _ ->
                if (p == IMAGE_PROBE_PROMPT) throw java.io.IOException("disk full")
                prompt = p
                AiCliResult.Answer("ok", false)
            })
        controller.send(a, "q", AiProviderId.CLAUDE, File("/bin/true"))
        waitFor { a.aiConversation.status == AiRunStatus.FINISHED }
        assertEquals(CapabilityState.UNKNOWN, state)
        assertTrue(prompt.contains("확인되지 않음"), prompt)
        assertFalse(a.aiImageChecking)
    }

    @Test
    fun `no check runs without a preview image or for a provider that cannot take images`() = runBlocking {
        var probes = 0
        val controller = AiChatController(this, now = { 1L }, ioDispatcher = Dispatchers.Default,
            capabilities = { AiProviderCapabilities(it, image = CapabilityState.UNKNOWN) },
            runner = { _, _, p, _, _ ->
                if (p == IMAGE_PROBE_PROMPT) probes++
                AiCliResult.Answer("ok", false)
            })
        val plain = tab("b.jpg")
        controller.send(plain, "q", AiProviderId.CLAUDE, File("/bin/true"))
        waitFor { plain.aiConversation.status == AiRunStatus.FINISHED }
        val a = imageTab()
        controller.send(a, "q", AiProviderId.GEMINI, File("/bin/true"))
        waitFor { a.aiConversation.status == AiRunStatus.FINISHED }
        assertEquals(0, probes)
    }

    private fun videoTab(name: String = "v.mp4"): TabState = tab(name).apply {
        type = MediaType.VIDEO
        selectedFrame = com.multiviewer.ui.FrameInfo(7, 'P', 100, 0.25)
        gopFrames = listOf(com.multiviewer.ui.FrameInfo(6, 'I', 100, 0.2), com.multiviewer.ui.FrameInfo(7, 'P', 100, 0.25))
    }

    private fun frameImage(w: Int = 320, h: Int = 180) = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)

    @Test
    fun `a video tab attaches the selected frame decoded at send time`() = runBlocking {
        val a = videoTab()
        var decoded: com.multiviewer.ui.FrameInfo? = null
        var mainImages = -1
        var prompt = ""
        val controller = AiChatController(this, now = { 1L }, ioDispatcher = Dispatchers.Default,
            capabilities = { AiProviderCapabilities(it, image = CapabilityState.VERIFIED) },
            frameDecoder = { _, f, _ -> decoded = f; com.multiviewer.ui.ExactFrameResult.Frame(frameImage(), f.ptsSeconds, hdr = false) },
            runner = { _, _, p, _, imgs -> prompt = p; mainImages = imgs.size; AiCliResult.Answer("ok", false) })
        controller.send(a, "q", AiProviderId.CLAUDE, File("/bin/true"))
        a.selectedFrame = com.multiviewer.ui.FrameInfo(6, 'I', 100, 0.2) // moved after sending
        waitFor { a.aiConversation.status == AiRunStatus.FINISHED }
        assertEquals(7, decoded?.index)
        assertEquals(1, mainImages)
        assertTrue(prompt.contains("video frame #7"), prompt)
        assertTrue(prompt.contains("320x180"), prompt)
    }

    @Test
    fun `no frame, AV2, a failed decode or a text-only provider attach nothing and say why`() = runBlocking {
        var decodes = 0
        var prompt = ""
        var mainImages = -1
        fun controller(result: com.multiviewer.ui.ExactFrameResult) = AiChatController(this, now = { 1L }, ioDispatcher = Dispatchers.Default,
            capabilities = { AiProviderCapabilities(it, image = CapabilityState.VERIFIED) },
            frameDecoder = { _, _, _ -> decodes++; result },
            runner = { _, _, p, _, imgs -> prompt = p; mainImages = imgs.size; AiCliResult.Answer("ok", false) })
        val ok = com.multiviewer.ui.ExactFrameResult.Frame(frameImage(), 0.25, hdr = false)

        val none = videoTab("n.mp4").apply { selectedFrame = null }
        controller(ok).send(none, "q", AiProviderId.CLAUDE, File("/bin/true"))
        waitFor { none.aiConversation.status == AiRunStatus.FINISHED }
        assertEquals(0, decodes); assertEquals(0, mainImages)
        assertTrue(prompt.contains("필름스트립/GOP에서 프레임을 선택"), prompt)

        val av2 = videoTab("a.mp4").apply {
            root = BoxNode("root", 0, 0, 100, listOf(BoxNode("av02", 0, 8, 50, listOf(BoxNode("av2C", 8, 8, 20)))))
        }
        controller(ok).send(av2, "q", AiProviderId.CLAUDE, File("/bin/true"))
        waitFor { av2.aiConversation.status == AiRunStatus.FINISHED }
        assertEquals(0, decodes)
        assertTrue(prompt.contains("AV2"), prompt)

        val failed = videoTab("f.ts")
        controller(com.multiviewer.ui.ExactFrameResult.Failed("decoded PTS 0.300000 ≠ selected 0.250000")).send(failed, "q", AiProviderId.CLAUDE, File("/bin/true"))
        waitFor { failed.aiConversation.status == AiRunStatus.FINISHED }
        assertEquals(1, decodes); assertEquals(0, mainImages)
        assertTrue(prompt.contains("정확히 얻지 못해") && prompt.contains("0.300000"), prompt)

        val gemini = videoTab("g.mp4")
        controller(ok).send(gemini, "q", AiProviderId.GEMINI, File("/bin/true"))
        waitFor { gemini.aiConversation.status == AiRunStatus.FINISHED }
        assertEquals(1, decodes) // no decode for a provider that cannot take images
        assertTrue(prompt.contains("이미지를 보낼 수 없음"), prompt)
    }

    @Test
    fun `an HDR frame is attached with a tone-mapping note`() = runBlocking {
        val a = videoTab()
        var prompt = ""
        val controller = AiChatController(this, now = { 1L }, ioDispatcher = Dispatchers.Default,
            capabilities = { AiProviderCapabilities(it, image = CapabilityState.VERIFIED) },
            frameDecoder = { _, f, _ -> com.multiviewer.ui.ExactFrameResult.Frame(frameImage(), f.ptsSeconds, hdr = true) },
            runner = { _, _, p, _, _ -> prompt = p; AiCliResult.Answer("ok", false) })
        controller.send(a, "q", AiProviderId.CLAUDE, File("/bin/true"))
        waitFor { a.aiConversation.status == AiRunStatus.FINISHED }
        assertTrue(prompt.contains("톤매핑 없이"), prompt)
    }

    @Test
    fun `stopping during the frame decode clears the flag and adds no message`() = runBlocking {
        val a = videoTab()
        val started = CompletableDeferred<Unit>()
        var runs = 0
        val controller = AiChatController(this, now = { 1L }, ioDispatcher = Dispatchers.Default,
            capabilities = { AiProviderCapabilities(it, image = CapabilityState.VERIFIED) },
            frameDecoder = { _, _, _ ->
                started.complete(Unit)
                java.util.concurrent.CountDownLatch(1).await() // interrupted by runInterruptible on cancel
                com.multiviewer.ui.ExactFrameResult.Failed("unreachable")
            },
            runner = { _, _, _, _, _ -> runs++; AiCliResult.Answer("ok", false) })
        controller.send(a, "q", AiProviderId.CLAUDE, File("/bin/true"))
        started.await()
        assertTrue(a.aiFrameDecoding)
        assertTrue(controller.isRunning(a))
        controller.stop(a)
        a.aiJob?.join()
        assertFalse(a.aiFrameDecoding)
        assertEquals(0, runs)
        assertTrue(a.aiConversation.messages.none { it.role == AiRole.USER })
    }

    @Test
    fun `an unsupported image provider skips the decode and says so`() = runBlocking {
        val a = videoTab()
        var decodes = 0
        var prompt = ""
        val controller = AiChatController(this, now = { 1L }, ioDispatcher = Dispatchers.Default,
            capabilities = { AiProviderCapabilities(it, image = CapabilityState.UNSUPPORTED) },
            frameDecoder = { _, f, _ -> decodes++; com.multiviewer.ui.ExactFrameResult.Frame(frameImage(), f.ptsSeconds, hdr = true) },
            runner = { _, _, p, _, _ -> prompt = p; AiCliResult.Answer("ok", false) })
        controller.send(a, "q", AiProviderId.CLAUDE, File("/bin/true"))
        waitFor { a.aiConversation.status == AiRunStatus.FINISHED }
        assertEquals(0, decodes)
        assertTrue(prompt.contains("확인용 이미지를 읽지 못함"), prompt)
        assertFalse(prompt.contains("톤매핑 없이"), prompt)
    }

    @Test
    fun `no executable means no frame decode`() = runBlocking {
        val a = videoTab()
        var decodes = 0
        val controller = AiChatController(this, now = { 1L }, ioDispatcher = Dispatchers.Default,
            capabilities = { AiProviderCapabilities(it, image = CapabilityState.VERIFIED) },
            frameDecoder = { _, f, _ -> decodes++; com.multiviewer.ui.ExactFrameResult.Frame(frameImage(), f.ptsSeconds, hdr = false) },
            runner = { _, _, _, _, _ -> AiCliResult.Answer("ok", false) })
        controller.send(a, "q", AiProviderId.CLAUDE, null)
        waitFor { a.aiConversation.status != AiRunStatus.RUNNING && a.aiConversation.messages.isNotEmpty() }
        a.aiJob?.join()
        assertEquals(0, decodes)
    }

    @Test
    fun `the HDR note is absent when the frame was not actually sent`() = runBlocking {
        val a = videoTab()
        var prompt = ""
        val controller = AiChatController(this, now = { 1L }, ioDispatcher = Dispatchers.Default,
            capabilities = { AiProviderCapabilities(it, image = CapabilityState.UNKNOWN) },
            frameDecoder = { _, f, _ -> com.multiviewer.ui.ExactFrameResult.Frame(frameImage(), f.ptsSeconds, hdr = true) },
            runner = { _, _, p, _, _ -> if (p == IMAGE_PROBE_PROMPT) AiCliResult.Answer("nonsense", false) else { prompt = p; AiCliResult.Answer("ok", false) } })
        controller.send(a, "q", AiProviderId.CLAUDE, File("/bin/true"))
        waitFor { a.aiConversation.status == AiRunStatus.FINISHED }
        assertFalse(prompt.contains("톤매핑 없이"), prompt)
    }
}
