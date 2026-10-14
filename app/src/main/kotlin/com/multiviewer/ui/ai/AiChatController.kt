package com.multiviewer.ui.ai

import com.multiviewer.ui.AppLanguage
import com.multiviewer.ui.TabState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What one finished run proved about a provider, for the capability display. */
data class AiCapabilityUpdate(val provider: AiProviderId, val answered: Boolean = false, val failure: AiFailure? = null)

typealias AiRunner = suspend (adapter: AiCliAdapter, executable: File, prompt: String, fileStillCurrent: () -> Boolean) -> AiCliResult

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
    private val runner: AiRunner = { adapter, exe, prompt, current -> runAiCli(adapter, exe, prompt, fileStillCurrent = current) },
    private val onCapabilityUpdate: (AiCapabilityUpdate) -> Unit = {},
) {
    var lastCapabilityUpdate: AiCapabilityUpdate? = null
        private set

    private fun label(ko: String, en: String) = if (language() == AppLanguage.KO) ko else en

    private fun report(update: AiCapabilityUpdate) {
        lastCapabilityUpdate = update
        onCapabilityUpdate(update)
    }

    fun isRunning(tab: TabState) = tab.aiJob?.isActive == true || tab.aiConversation.status == AiRunStatus.RUNNING

    fun send(tab: TabState, question: String, provider: AiProviderId, executable: File?) {
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
        tab.aiJob = scope.launch {
            val snapshot = withContext(ioDispatcher) {
                captureAiContext(file, root, type, selected, field, frame, contentCheck, parsed, video)
            }
            val context = renderAiContext(snapshot)
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
            try {
                when (val result = runner(adapterFor(provider), executable, buildAiCliPrompt(history, context, text)) { snapshot.isCurrent(file) }) {
                    is AiCliResult.Answer -> {
                        tab.aiConversation = tab.aiConversation.withAssistantReply(run, result.text, now()).let { s ->
                            if (result.truncated) s.withActivity(label("응답이 너무 길어 일부만 표시합니다", "The answer was too long and was cut"), now()) else s
                        }
                        report(AiCapabilityUpdate(provider, answered = true))
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
}
