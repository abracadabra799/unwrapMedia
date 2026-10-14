package com.multiviewer.ui.ai

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.findEmbeddedVideo
import com.multiviewer.parser.parseFile
import com.multiviewer.ui.ContentFileIdentity
import com.multiviewer.ui.MediaType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test

/**
 * Manual acceptance against the real, logged-in CLIs (sends real requests). Skipped unless
 * UNWRAPMEDIA_LIVE_AI=1. Uses only the non-sensitive showcase fixture.
 */
class AiCliLiveAcceptanceTest {
    @Test
    fun `live cli round trip`() {
        assumeTrue(System.getenv("UNWRAPMEDIA_LIVE_AI") == "1", "live AI acceptance is opt-in")
        val file = File("../docs/showcase-media/images/campfire-motion-photo.jpg")
        val root = parseFile(file)
        val video = ByteReader.open(file).use { findEmbeddedVideo(root, it) }
        val sef = root.children.first { it.type == "sefd" }
        val snapshot = captureAiContext(file, root, MediaType.IMAGE, sef, null, null, null, ContentFileIdentity.capture(file), video)
        val context = renderAiContext(snapshot)
        val question = "이 sefd 영역에 무엇이 들어 있고 영상 데이터는 어느 바이트 범위인지 두 문장으로 답하세요. 근거 오프셋을 0x 표기로 인용하세요."
        val dirs = cliSearchDirectories()
        val only = System.getenv("UNWRAPMEDIA_LIVE_AI_PROVIDERS")?.split(',')?.map { it.trim().uppercase() }
        if (System.getenv("UNWRAPMEDIA_LIVE_AI_WEB") == "1") {
            val exe = resolveExecutable("claude", dirs) ?: return
            val web = runBlocking {
                runAiCli(adapterFor(AiProviderId.CLAUDE, webSearch = true), exe,
                    "Use web search to find the current W3C Recommendation page for PNG (Third Edition) and reply with its exact URL and publication date, one line.",
                    timeoutMillis = 240_000)
            }
            println("LIVE WEB CLAUDE: $web")
            return
        }
        for (provider in AiProviderId.entries.filter { only == null || it.name in only }) {
            val exe = resolveExecutable(provider.command, dirs)
            println("LIVE ===== ${provider.displayName}: ${exe?.path} version=${exe?.let { runBlocking { probeCliVersion(it) } }}")
            if (exe == null) continue
            val started = System.nanoTime()
            val result = runBlocking { runAiCli(adapterFor(provider), exe, buildAiCliPrompt(emptyList(), context, question), timeoutMillis = 240_000) }
            println("LIVE ${provider.name} ${(System.nanoTime() - started) / 1_000_000} ms -> ${result::class.simpleName}")
            when (result) {
                is AiCliResult.Answer -> println("LIVE ANSWER ${provider.name}: " + result.text.take(1500).replace("\n", "\nLIVE | "))
                is AiCliResult.Failed -> println("LIVE FAIL ${provider.name}: ${result.reason} exit=${result.exitCode} ${result.detail.take(2000).replace("\n", "\nLIVE | ")}")
                else -> println("LIVE OTHER ${provider.name}: $result")
            }
            if (provider == AiProviderId.CODEX) {
                val probe = java.awt.image.BufferedImage(96, 96, java.awt.image.BufferedImage.TYPE_INT_RGB).apply {
                    val g = createGraphics(); g.color = java.awt.Color(220, 20, 20); g.fillRect(0, 0, 96, 96); g.dispose()
                }
                val img = runBlocking {
                    runAiCli(adapterFor(provider), exe, "The attached image is one solid color. Reply with only that color as a single lowercase English word.",
                        timeoutMillis = 240_000, images = listOf(probe))
                }
                println("LIVE IMAGE ${provider.name}: $img")
            }
        }
    }
}
