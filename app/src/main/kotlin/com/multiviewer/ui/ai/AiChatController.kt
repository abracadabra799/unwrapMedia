package com.multiviewer.ui.ai

import com.multiviewer.ui.AppLanguage
import com.multiviewer.ui.TabState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.toAwtImage
import java.io.File

/** What one finished run proved about a provider, for the capability display. */
data class AiCapabilityUpdate(
    val provider: AiProviderId,
    val answered: Boolean = false,
    val failure: AiFailure? = null,
    /** Result of an explicit image-input check. */
    val image: CapabilityState? = null,
    val detail: String? = null,
    /** VERIFIED once a web-search-enabled answer actually cited a URL. */
    val webSearch: CapabilityState? = null,
)

typealias AiRunner = suspend (
    adapter: AiCliAdapter, executable: File, prompt: String, fileStillCurrent: () -> Boolean, images: List<java.awt.image.BufferedImage>,
) -> AiCliResult

/**
 * Sends questions for a tab. The selection is read when [send] is called (on the UI thread), the context
 * is captured off it, and every state change targets the [TabState] the question came from, so switching
 * tabs never moves an answer to another file.
 */
class AiChatController(
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val language: () -> AppLanguage = { AppLanguage.KO },
    private val runner: AiRunner = { adapter, exe, prompt, current, images ->
        runAiCli(adapter, exe, prompt, fileStillCurrent = current, images = images)
    },
    private val onCapabilityUpdate: (AiCapabilityUpdate) -> Unit = {},
    private val capabilities: (AiProviderId) -> AiProviderCapabilities? = { null },
    /** Test hook run inside context capture (e.g. to simulate a failure there). */
    private val contextCapture: () -> Any? = {},
) {
    var lastCapabilityUpdate: AiCapabilityUpdate? = null
        private set

    private fun label(ko: String, en: String) = if (language() == AppLanguage.KO) ko else en

    private fun report(update: AiCapabilityUpdate) {
        lastCapabilityUpdate = update
        onCapabilityUpdate(update)
    }

    fun isRunning(tab: TabState) = tab.aiJob?.isActive == true || tab.aiConversation.status == AiRunStatus.RUNNING

    fun send(tab: TabState, question: String, provider: AiProviderId, executable: File?, webSearch: Boolean = false) {
        val text = question.trim()
        if (text.isEmpty() || isRunning(tab)) return
        // Send-time selection: read now, before the user can move on.
        val file = tab.file
        val root = tab.root
        val type = tab.type
        val selected = tab.selected
        val field = tab.selectedField
        val frame = tab.selectedFrame
        val contentCheck = tab.contentCheck
        val parsed = tab.parsedIdentity
        val video = tab.embeddedVideo
        val visual = tab.aiVisualContext()
        val frames = tab.gopFrames
        val reports = buildMap {
            tab.avSyncReport?.let { r ->
                put("avsync", "video ${r.videoDurationSec} s, audio ${r.audioDurationSec} s, initial skew ${r.initialSkewMs} ms, " +
                    "max ${r.maxSkewMs} ms, avg ${r.avgSkewMs} ms, drift ${r.driftRateMsPerMin} ms/min; " +
                    r.diagnoses.take(10).joinToString("; ") { "[${it.severity}] ${it.category}: ${it.summary}" })
            }
            tab.bitstreamCorruptionReport?.let { r ->
                put("bitstream", com.multiviewer.cli.scrubPaths(
                    "${r.overallStatus}: ${r.corruptFrameCount}/${r.totalFramesScanned} corrupt frames, ${r.criticalErrorCount} critical, " +
                        "reference loss ${r.hasReferenceLoss}; ${r.summaryRecommendation}", file))
            }
        }
        val imageSource = tab.aiImageSource
        val adapter = adapterFor(provider, webSearch = webSearch)
        val canPassImages = adapter.argumentsWithImages(listOf(File("probe.png"))) != null
        val imageVerified = capabilities(provider)?.canAttachImage == true
        tab.aiJob = scope.launch {
            // A capture failure must become a visible notice, never an exception that kills the scope.
            val snapshot = try {
                withContext(ioDispatcher) {
                    contextCapture()
                    captureAiContext(file, root, type, selected, field, frame, contentCheck, parsed, video, visual)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val (started, run) = tab.aiConversation.withUserMessage(text, "", provider, now())
                tab.aiConversation = started.withRunEnded(run, AiRunStatus.FAILED,
                    label("컨텍스트를 만들지 못해 보내지 않았습니다: ", "Not sent: the context could not be built: ") + (e.message ?: e.toString()), now())
                return@launch
            }
            // A crop goes only to a provider whose image input was verified here, and only of the marked
            // region (else the visible viewport); otherwise the coordinates travel alone, labeled as such.
            val shown = snapshot.visual
            val region = shown?.let { it.marked ?: it.visible }
            val crop = if (shown != null && region != null && imageSource != null && canPassImages && imageVerified) {
                withContext(Dispatchers.Default) {
                    @Suppress("DEPRECATION")
                    cropForAi(imageSource.toAwtImage(), region)
                }
            } else null
            val omitted = when {
                shown == null || crop != null -> null
                !canPassImages -> label("이 앱의 ${provider.displayName} 연동은 이미지를 보낼 수 없음", "this app's ${provider.displayName} integration cannot send images")
                else -> label("${provider.displayName} 이미지 입력이 아직 확인되지 않음", "${provider.displayName} image input is not verified yet")
            }
            val context = renderAiContext(snapshot, attachedImage = region.takeIf { crop != null }, imageOmittedReason = omitted)
            val history = tab.aiConversation.messages
            val (started, run) = tab.aiConversation.withUserMessage(text, context, provider, now())
            tab.aiConversation = started
            fun end(status: AiRunStatus, notice: String) {
                tab.aiConversation = tab.aiConversation.withRunEnded(run, status, notice, now())
            }
            if (snapshot.fileChangedSinceOpen) {
                end(AiRunStatus.FAILED, label(
                    "보내지 않음: 파일이 열린 뒤 변경되었거나 없어졌습니다. 파일을 다시 열어 주세요.",
                    "Not sent: the file changed or disappeared after it was opened. Reopen the file.",
                ))
                return@launch
            }
            if (executable == null) {
                end(AiRunStatus.FAILED, label(
                    "${provider.displayName} CLI를 찾지 못했습니다. 설치 후 다시 시도하거나 브라우저/클립보드 방식을 쓰세요.",
                    "${provider.displayName} CLI was not found. Install it, or use the browser/clipboard route.",
                ))
                return@launch
            }
            val toolContext = AiToolContext(
                file, root?.takeIf { !snapshot.fileChangedSinceOpen }, parsed ?: snapshot.identity,
                snapshot.contentCheckSection, frames, reports,
            )
            try {
                var toolResults = ""
                var round = 0
                var result: AiCliResult
                while (true) {
                    val prompt = buildAiCliPrompt(history, context + toolResults, text)
                    result = runner(adapter, executable, prompt, { snapshot.isCurrent(file) }, listOfNotNull(crop))
                    val requests = (result as? AiCliResult.Answer)?.let { parseToolRequests(it.text) }.orEmpty()
                    if (requests.isEmpty()) break
                    if (round == MAX_TOOL_ROUNDS) {
                        end(AiRunStatus.FAILED, label(
                            "AI가 자료 요청을 ${MAX_TOOL_ROUNDS}회 넘게 반복해 중단했습니다.",
                            "Stopped: the AI kept requesting data beyond $MAX_TOOL_ROUNDS rounds.",
                        ))
                        return@launch
                    }
                    round++
                    val answers = withContext(ioDispatcher) {
                        requests.take(MAX_TOOL_REQUESTS_PER_ROUND).map { parsedRequest ->
                            parsedRequest.request?.let { executeToolRequest(it, toolContext).copy(line = parsedRequest.line) }
                                ?: AiToolResult(parsedRequest.line, false, "Refused: ${parsedRequest.reason}")
                        }
                    }
                    val roundText = buildString {
                        answers.forEach { r ->
                            appendLine("${r.line} → ${if (r.ok) "OK" else "REFUSED/FAILED"}")
                            appendLine(r.text.trimEnd())
                        }
                        if (requests.size > MAX_TOOL_REQUESTS_PER_ROUND) appendLine("(only the first $MAX_TOOL_REQUESTS_PER_ROUND requests were answered)")
                    }
                    tab.aiConversation = tab.aiConversation.withToolRound(run, roundText.trimEnd(), answers.size, now())
                    toolResults += "\n### [앱 도구 결과 ${round}회차]\n$roundText"
                }
                when (result) {
                    is AiCliResult.Answer -> {
                        tab.aiConversation = tab.aiConversation.withAssistantReply(run, result.text, now()).let { s ->
                            if (result.truncated) s.withActivity(label("응답이 너무 길어 일부만 표시합니다", "The answer was too long and was cut"), now()) else s
                        }
                        report(AiCapabilityUpdate(provider, answered = true,
                            webSearch = if (webSearch && adapter.supportsWebSearch && Regex("https?://").containsMatchIn(result.text)) CapabilityState.VERIFIED else null))
                    }
                    is AiCliResult.Failed -> {
                        end(AiRunStatus.FAILED, when (result.reason) {
                            AiFailure.AUTH_REQUIRED -> label(
                                "${provider.displayName} 로그인이 필요합니다. 터미널에서 로그인한 뒤 다시 시도하세요.",
                                "${provider.displayName} needs a login. Sign in from a terminal, then retry.",
                            )
                            AiFailure.EMPTY_OUTPUT -> label("응답이 비어 있습니다.", "The answer was empty.")
                            AiFailure.LAUNCH_FAILED -> label("CLI를 실행하지 못했습니다: ", "Could not start the CLI: ") + result.detail
                            AiFailure.EXIT_ERROR -> label("CLI가 오류로 끝났습니다 (종료 코드 ${result.exitCode}): ", "The CLI failed (exit ${result.exitCode}): ") + result.detail
                        })
                        report(AiCapabilityUpdate(provider, failure = result.reason))
                    }
                    AiCliResult.TimedOut -> end(AiRunStatus.TIMED_OUT, label(
                        "시간 초과로 중단했습니다. 응답을 받지 못했습니다.", "Stopped after the time limit; no answer was received.",
                    ))
                    AiCliResult.StaleFile -> end(AiRunStatus.FAILED, label(
                        "보내지 않음: 컨텍스트를 만든 뒤 파일이 바뀌었습니다.", "Not sent: the file changed after the context was captured.",
                    ))
                }
            } catch (e: CancellationException) {
                end(AiRunStatus.CANCELLED, label("중단됨 — 응답을 받지 않았습니다.", "Stopped — no answer was received."))
                throw e
            } catch (e: Exception) {
                end(AiRunStatus.FAILED, label("요청 실패: ", "Request failed: ") + (e.message ?: e.toString()))
            }
        }
    }

    fun stop(tab: TabState) {
        tab.aiJob?.cancel()
    }

    /**
     * Explicit, user-started check that [provider] really reads an attached image: one solid red square,
     * asked for its color. Only a correct answer marks image input VERIFIED.
     */
    fun verifyImageInput(provider: AiProviderId, executable: File, onDone: () -> Unit = {}) = scope.launch {
        val adapter = adapterFor(provider)
        if (adapter.argumentsWithImages(listOf(File("probe.png"))) == null) {
            report(AiCapabilityUpdate(provider, image = CapabilityState.UNSUPPORTED, detail = "no image argument"))
            onDone(); return@launch
        }
        val probe = java.awt.image.BufferedImage(96, 96, java.awt.image.BufferedImage.TYPE_INT_RGB).apply {
            val g = createGraphics(); g.color = java.awt.Color(220, 20, 20); g.fillRect(0, 0, 96, 96); g.dispose()
        }
        val prompt = "The attached image is one solid color. Reply with only that color as a single lowercase English word."
        try {
            val result = runner(adapter, executable, prompt, { true }, listOf(probe))
            val ok = result is AiCliResult.Answer && Regex("\\bred\\b", RegexOption.IGNORE_CASE).containsMatchIn(result.text)
            report(AiCapabilityUpdate(
                provider,
                image = if (ok) CapabilityState.VERIFIED else CapabilityState.UNSUPPORTED,
                detail = when (result) {
                    is AiCliResult.Answer -> result.text.take(80)
                    is AiCliResult.Failed -> result.reason.name
                    else -> result.toString()
                },
            ))
        } finally {
            onDone()
        }
    }
}
