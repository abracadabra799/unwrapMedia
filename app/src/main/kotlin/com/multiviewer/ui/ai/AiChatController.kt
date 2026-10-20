package com.multiviewer.ui.ai

import com.multiviewer.ui.AppLanguage
import com.multiviewer.ui.TabState
import com.multiviewer.ui.decodeSelectedFrame
import com.multiviewer.ui.postIfOpen
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
    /** Result of the image-input check run on a provider's first image send. */
    val image: CapabilityState? = null,
    val detail: String? = null,
    /** Unused for now: a cited URL cannot prove that a search happened, so web search is never marked verified. */
    val webSearch: CapabilityState? = null,
)

private val AUTH_REASON_HINT = Regex("(?i)error|auth|log ?in|eligib|tier|unauthori|api key|expired")

internal const val IMAGE_PROBE_PROMPT =
    "The attached image is one solid color. Reply with only that color as a single lowercase English word."

// More colors lower the chance that a model that never saw the image guesses right (1 in 7); ambiguous hues
// (purple/violet, cyan) are avoided because a correct-but-differently-named answer is final for the session.
internal val IMAGE_PROBE_COLORS = listOf(
    "red" to java.awt.Color(220, 20, 20), "green" to java.awt.Color(20, 180, 40), "blue" to java.awt.Color(20, 40, 220),
    "yellow" to java.awt.Color(240, 220, 20), "orange" to java.awt.Color(245, 130, 20),
    "black" to java.awt.Color(0, 0, 0), "white" to java.awt.Color(255, 255, 255),
)

/** Just the pixels of [region] from [source], without converting the whole bitmap. */
internal fun regionPixels(source: androidx.compose.ui.graphics.ImageBitmap, region: ImageRegion): java.awt.image.BufferedImage {
    val x = region.x.coerceIn(0, source.width - 1)
    val y = region.y.coerceIn(0, source.height - 1)
    val w = region.width.coerceIn(1, source.width - x)
    val h = region.height.coerceIn(1, source.height - y)
    // Sample at most ~4096 px on the long edge before the final downscale, to bound memory.
    val step = maxOf(1, maxOf(w, h) / 4096)
    val buffer = IntArray(w * minOf(h, 256))
    val out = java.awt.image.BufferedImage((w + step - 1) / step, (h + step - 1) / step, java.awt.image.BufferedImage.TYPE_INT_RGB)
    var row = 0
    while (row < h) {
        val rows = minOf(256, h - row)
        source.readPixels(buffer, x, y + row, w, rows)
        for (r in 0 until rows) {
            val sy = row + r
            if (sy % step != 0) continue
            for (sx in 0 until w step step) out.setRGB(sx / step, sy / step, buffer[r * w + sx])
        }
        row += rows
    }
    return out
}

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
    /** The app-wide last Quality Compare result (read on the UI thread at send time). */
    private val quality: () -> com.multiviewer.ui.StoredComparison? = { null },
    /** Test hook run inside context capture (e.g. to simulate a failure there). */
    private val contextCapture: () -> Any? = {},
    /** Decodes a video tab's selected frame for the AI (blocking; run off the UI thread). */
    private val frameDecoder: (File, com.multiviewer.ui.FrameInfo, List<com.multiviewer.ui.FrameInfo>?) -> com.multiviewer.ui.ExactFrameResult =
        ::decodeSelectedFrame,
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
        // Nothing is sent until the tree is loaded: without it no context or byte request can be checked.
        if (text.isEmpty() || isRunning(tab) || tab.isLoading || tab.root == null) return
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
        // A list still being streamed by the user's own analysis is partial: not treated as complete.
        val frames = tab.gopFrames?.takeIf { !tab.isAnalyzingFrames }
        val avSyncReport = tab.avSyncReport
        // Send-time snapshot of every analysis result the AI may ask for; validated (stat) off the UI thread.
        val reportInputs = AiReportInputs(
            file = file, root = root, type = type, avSync = avSyncReport,
            hdr = tab.hdrScan, hrd = tab.hrdReport, timed = tab.timedMetadata, frames = frames,
            gainmap = tab.gainmapInfo, audio = tab.audioWaveform, quality = quality(),
            gainmapBase = tab.imageForensic?.let { f ->
                f.bitmap?.let { com.multiviewer.ui.codedSize(it.width, it.height, f.orientationCode) }
            },
        )
        val imageSource = tab.aiImageSource
        // A video tab sends its selected frame (filmstrip/GOP/popup), decoded exactly at native size at send time.
        val isVideo = type == com.multiviewer.ui.MediaType.VIDEO && imageSource == null
        val av2 = isVideo && com.multiviewer.ui.isAv2VideoTrack(root)
        val videoFrame = frame?.takeIf { isVideo && !av2 }
        val adapter = adapterFor(provider, webSearch = webSearch)
        val canPassImages = adapter.canAttachImages
        val imageState = capabilities(provider)?.image ?: CapabilityState.UNKNOWN
        // Auto-run analyses (frame probe, HDR scan) happen at most once per send and reach the tab when done,
        // on the UI thread; a frame list the user's own analysis is producing is never overwritten.
        val autoRun = AiAutoRunState(frames)
        // The indicator is cleared only by the send that set it: identity of a per-send label instance.
        val myLabel = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val autoRunHooks = AiAutoRunHooks(
            publishFrames = { f -> tab.postIfOpen { if (tab.gopFrames == null && !tab.isAnalyzingFrames) tab.gopFrames = f } },
            publishHdr = { s -> tab.postIfOpen { tab.hdrScan = s } },
            running = { l ->
                if (l != null) {
                    val mine = String(l.toCharArray())
                    myLabel.set(mine)
                    tab.postIfOpen { tab.aiAutoRunning = mine }
                } else {
                    val mine = myLabel.get()
                    tab.postIfOpen { if (tab.aiAutoRunning === mine) tab.aiAutoRunning = null }
                }
            },
        )
        tab.aiJob = scope.launch {
            // A capture failure must become a visible notice, never an exception that kills the scope.
            val (snapshot, reportSources) = try {
                withContext(ioDispatcher) {
                    contextCapture()
                    captureAiContext(file, root, type, selected, field, frame, contentCheck, parsed, video, visual) to
                        aiReportSources(reportInputs, ::label, autoRun = autoRun, hooks = autoRunHooks)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                System.err.println("[AI] ${provider.name} context capture failed: $e")
                e.printStackTrace()
                val (started, run) = tab.aiConversation.withUserMessage(text, "", provider, now())
                tab.aiConversation = started.withRunEnded(run, AiRunStatus.FAILED,
                    label("컨텍스트를 만들지 못해 보내지 않았습니다: ", "Not sent: the context could not be built: ") + (e.message ?: e.toString()), now())
                return@launch
            }
            // Video: decode the selected frame (once per send). A frame that cannot be shown exactly is not
            // shown at all -- the reason goes into the context instead.
            var frameImage: java.awt.image.BufferedImage? = null
            var frameHdr = false
            val frameNotes = mutableListOf<String>()
            if (isVideo) {
                when {
                    av2 -> frameNotes += label("AV2 프레임 이미지는 아직 첨부하지 않습니다.", "AV2 frame images are not attached yet.")
                    videoFrame == null -> frameNotes += label(
                        "프레임 이미지 없음 — 필름스트립/GOP에서 프레임을 선택하면 첨부됩니다.",
                        "No frame image: select a frame in the filmstrip/GOP view to attach it.",
                    )
                    !canPassImages -> frameNotes += label(
                        "이 앱의 ${provider.displayName} 연동은 이미지를 보낼 수 없음",
                        "this app's ${provider.displayName} integration cannot send images",
                    )
                    executable == null -> Unit // the send ends with the "CLI not found" notice
                    imageState == CapabilityState.UNSUPPORTED -> frameNotes += label(
                        "확인용 이미지를 읽지 못함 (${provider.displayName}) — 프레임 이미지를 보내지 않음",
                        "${provider.displayName} failed the image input check: the frame image is not sent",
                    )
                    !snapshot.fileChangedSinceOpen -> {
                        tab.aiFrameDecoding = true
                        val r = try {
                            withContext(ioDispatcher) { kotlinx.coroutines.runInterruptible { frameDecoder(file, videoFrame, frames) } }
                        } finally {
                            tab.aiFrameDecoding = false
                        }
                        when (r) {
                            is com.multiviewer.ui.ExactFrameResult.Frame -> {
                                frameImage = r.image
                                frameHdr = r.hdr
                            }
                            is com.multiviewer.ui.ExactFrameResult.Failed -> frameNotes += label(
                                "선택한 프레임 이미지를 정확히 얻지 못해 첨부하지 않음 (${r.reason})",
                                "Selected frame image not attached: it could not be decoded exactly (${r.reason})",
                            )
                        }
                    }
                }
            }
            val videoImage = frameImage
            val withFrame = if (videoImage != null && videoFrame != null) {
                snapshot.copy(
                    visual = AiVisualContext(
                        "video frame #${videoFrame.index} ${videoFrame.type} PTS ${String.format(java.util.Locale.US, "%.6f", videoFrame.ptsSeconds)} s",
                        videoImage.width, videoImage.height, ImageRegion(0, 0, videoImage.width, videoImage.height), null, null,
                    ),
                    warnings = snapshot.warnings + frameNotes,
                )
            } else {
                snapshot.copy(warnings = snapshot.warnings + frameNotes)
            }
            // A crop goes only to a provider whose image input was verified here, and only of the marked
            // region (else the visible viewport); otherwise the coordinates travel alone, labeled as such.
            val shown = withFrame.visual
            val hasPixels = imageSource != null || videoImage != null
            val region = shown?.let { it.marked ?: it.visible }
            // A provider's first image send checks its image input once, before the question goes out. A run
            // that fails (timeout, login) stays UNKNOWN, so a later send tries again; a wrong answer does not.
            val checkedImage = if (shown != null && region != null && hasPixels && canPassImages &&
                imageState == CapabilityState.UNKNOWN && executable != null && !snapshot.fileChangedSinceOpen
            ) {
                tab.aiImageChecking = true
                try {
                    probeImageInput(provider, executable)
                } finally {
                    tab.aiImageChecking = false
                }
            } else {
                imageState
            }
            var cropFailed: String? = null
            val crop = if (shown != null && region != null && hasPixels && canPassImages && checkedImage == CapabilityState.VERIFIED) {
                try {
                    // Read only the region's pixels: converting a 200 MP bitmap whole would need ~1.6 GB.
                    withContext(Dispatchers.Default) {
                        if (videoImage != null) cropForAi(videoImage, region)
                        else cropForAi(regionPixels(imageSource!!, region), ImageRegion(0, 0, region.width, region.height))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    cropFailed = e.toString(); null
                }
            } else null
            val omitted = when {
                shown == null || crop != null -> null
                cropFailed != null -> label("화면 영역을 잘라내지 못함 ($cropFailed)", "could not crop the view ($cropFailed)")
                !canPassImages -> label("이 앱의 ${provider.displayName} 연동은 이미지를 보낼 수 없음", "this app's ${provider.displayName} integration cannot send images")
                checkedImage == CapabilityState.UNSUPPORTED ->
                    label("확인용 이미지를 읽지 못함 (${provider.displayName})", "${provider.displayName} failed the image input check")
                else -> label("${provider.displayName} 이미지 입력이 아직 확인되지 않음", "${provider.displayName} image input is not verified yet")
            }
            val hdrNote = label(
                "HDR 프레임을 톤매핑 없이 8비트로 변환한 이미지 — 밝기·색이 실제와 다를 수 있음",
                "HDR frame converted to 8-bit without tone mapping: brightness and colour may differ",
            )
            val contextSnapshot = if (crop != null && frameHdr) withFrame.copy(warnings = withFrame.warnings + hdrNote) else withFrame
            val reportSection = renderReportSection(
                if (snapshot.fileChangedSinceOpen) markReportsStale(reportSources, ::label) else reportSources, ::label)
            val fileContext = renderAiContext(contextSnapshot, attachedImage = region.takeIf { crop != null }, imageOmittedReason = omitted)
            // The stored/previewed context still shows the section; the prompt sends it outside the data fence.
            val context = fileContext + (if (reportSection.isEmpty()) "" else "\n\n$reportSection")
            val history = tab.aiConversation.messages
            val (started, run) = tab.aiConversation.withUserMessage(text, context, provider, now())
            tab.aiConversation = started
            fun end(status: AiRunStatus, notice: String, failure: AiFailure? = null) {
                // Local diagnostics only: the run status and the app notice, never the question or context.
                System.err.println("[AI] ${provider.name} run $run ended $status: $notice")
                tab.aiConversation = tab.aiConversation.withRunEnded(run, status, notice, now(), failure)
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
                snapshot.contentCheckSection, { autoRun.frames.get() }, reportSources,
            )
            try {
                var toolResults = ""
                var round = 0
                var result: AiCliResult
                while (true) {
                    val prompt = buildAiCliPrompt(history, fileContext + toolResults, text, webSearch = webSearch, reportSection = reportSection)
                    result = runner(adapter, executable, prompt, { snapshot.isCurrent(file) }, listOfNotNull(crop))
                    val requests = (result as? AiCliResult.Answer)?.let { parseToolRequests(it.text) }.orEmpty()
                    if (requests.isEmpty()) break
                    if (round == MAX_TOOL_ROUNDS) {
                        // Out of rounds: keep any prose the model wrote alongside its requests.
                        val prose = (result as AiCliResult.Answer).text.lineSequence()
                            .filterNot { it.trim().startsWith(TOOL_PREFIX) }.joinToString("\n").trim()
                        if (prose.length >= 40) {
                            result = AiCliResult.Answer(prose, result.truncated)
                            break
                        }
                        end(AiRunStatus.FAILED, label(
                            "AI가 자료 요청을 ${MAX_TOOL_ROUNDS}회 넘게 반복해 중단했습니다.",
                            "Stopped: the AI kept requesting data beyond $MAX_TOOL_ROUNDS rounds.",
                        ))
                        return@launch
                    }
                    round++
                    // Interruptible: stopping the run interrupts an on-request report that is still analysing.
                    val answers = withContext(ioDispatcher) {
                        kotlinx.coroutines.runInterruptible {
                            requests.take(MAX_TOOL_REQUESTS_PER_ROUND).map { parsedRequest ->
                                parsedRequest.request?.let { executeToolRequest(it, toolContext).copy(line = parsedRequest.line) }
                                    ?: AiToolResult(parsedRequest.line, false, "Refused: ${parsedRequest.reason}")
                            }
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
                        // Web search is never marked verified from an answer: a cited URL may come from memory.
                        report(AiCapabilityUpdate(provider, answered = true))
                    }
                    is AiCliResult.Failed -> {
                        end(AiRunStatus.FAILED, when (result.reason) {
                            AiFailure.AUTH_REQUIRED -> label(
                                "${provider.displayName} 로그인(또는 계정 권한)에 문제가 있습니다. 터미널에서 로그인을 확인한 뒤 다시 시도하세요.",
                                "${provider.displayName} login (or account eligibility) failed. Check the login from a terminal, then retry.",
                            ) + authReason(result.detail)
                            AiFailure.EMPTY_OUTPUT -> label("응답이 비어 있습니다.", "The answer was empty.")
                            AiFailure.LAUNCH_FAILED -> label("CLI를 실행하지 못했습니다: ", "Could not start the CLI: ") + result.detail
                            AiFailure.EXIT_ERROR -> label("CLI가 오류로 끝났습니다 (종료 코드 ${result.exitCode}): ", "The CLI failed (exit ${result.exitCode}): ") + result.detail
                        }, result.reason)
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
                e.printStackTrace()
                end(AiRunStatus.FAILED, label("요청 실패: ", "Request failed: ") + (e.message ?: e.toString()))
            }
        }
    }

    // The CLI's own first meaningful error line (e.g. an ineligible account tier), without stack frames.
    private fun authReason(detail: String): String {
        val lines = detail.lineSequence().map { it.trim() }
            .filter { it.length > 12 && !it.startsWith("at ") && !it.contains(".js:") }.toList()
        val best = lines.firstOrNull { AUTH_REASON_HINT.containsMatchIn(it) } ?: lines.firstOrNull()
        return best?.let { " — ${it.take(200)}" } ?: ""
    }

    fun stop(tab: TabState) {
        tab.aiJob?.cancel()
    }

    /**
     * One round trip proving [provider] reads an attached image: a random solid color, asked for as one exact
     * word ("I can't see the image, maybe red?" must not pass). VERIFIED / UNSUPPORTED (it answered, wrongly) /
     * UNKNOWN (the run itself failed or timed out, so a later send tries again).
     */
    private suspend fun probeImageInput(provider: AiProviderId, executable: File): CapabilityState {
        val (word, color) = IMAGE_PROBE_COLORS.random()
        val probe = java.awt.image.BufferedImage(96, 96, java.awt.image.BufferedImage.TYPE_INT_RGB).apply {
            val g = createGraphics(); g.color = color; g.fillRect(0, 0, 96, 96); g.dispose()
        }
        val result = try {
            runner(adapterFor(provider), executable, IMAGE_PROBE_PROMPT, { true }, listOf(probe))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            System.err.println("[AI] ${provider.name} image check failed: $e")
            report(AiCapabilityUpdate(provider, image = CapabilityState.UNKNOWN, detail = e.toString().take(80)))
            return CapabilityState.UNKNOWN
        }
        val state = when {
            result is AiCliResult.Answer && result.text.trim().trimEnd('.').lowercase() == word -> CapabilityState.VERIFIED
            result is AiCliResult.Answer -> CapabilityState.UNSUPPORTED
            else -> CapabilityState.UNKNOWN
        }
        report(AiCapabilityUpdate(
            provider,
            image = state,
            detail = when (result) {
                is AiCliResult.Answer -> result.text.take(80)
                is AiCliResult.Failed -> result.reason.name
                else -> result.toString()
            },
        ))
        return state
    }
}
