package com.multiviewer.ui.ai

/** The CLIs the panel can drive; capabilities are probed per adapter, never assumed from this list. */
enum class AiProviderId(val displayName: String, val command: String) {
    CLAUDE("Claude Code", "claude"),
    CODEX("Codex", "codex"),
    AGY("Antigravity", "agy"),
    GEMINI("Gemini CLI", "gemini"),
}

/**
 * NOTICE = app-generated status (failure, timeout, cancel, stale file); TOOL = what the app's read-only
 * bridge answered to the model's requests. Neither is ever presented as the AI's words.
 */
enum class AiRole { USER, ASSISTANT, NOTICE, TOOL }

enum class AiRunStatus { IDLE, RUNNING, FINISHED, FAILED, TIMED_OUT, CANCELLED }

data class AiMessage(
    val id: Long,
    val role: AiRole,
    val text: String,
    val provider: AiProviderId?,
    val timestampMillis: Long,
    /** For a user message: exactly the context text that left with it (shown on demand). */
    val outgoingContext: String? = null,
)

/**
 * Byte offsets the app itself sent to the AI (outgoing context and tool output). An offset quoted in an answer
 * becomes an evidence chip only if it is in this set or a node starts there.
 */
internal fun sentOffsets(messages: List<AiMessage>): Set<Long> =
    messages.flatMap { m -> listOfNotNull(m.outgoingContext, m.text.takeIf { m.role == AiRole.TOOL }) }
        .flatMap { t -> HEX_OFFSET.findAll(t).mapNotNull { it.value.drop(2).toLongOrNull(16) } }.toSet()

data class AiActivity(val timestampMillis: Long, val text: String)

/** Runs (questions) whose sent context and tool output are kept in full; older ones keep a short stub. */
const val AI_FULL_DETAIL_RUNS = 10
/** Activity log entries kept per tab. */
const val AI_MAX_ACTIVITY = 200
private const val MAX_STUB_OFFSETS = 200
private val HEX_OFFSET = Regex("\\b0x[0-9A-Fa-f]{1,12}\\b")

// A long session would otherwise hold every sent context (~24 k chars) and every tool round (up to 15 × 8 k)
// for the life of the tab. Questions and answers are kept as they are (the transcript and the resent history
// only use those); older contexts and tool output shrink to a stub that still lists the offsets the app sent,
// so evidence chips on old answers keep resolving.
private fun stub(kind: String, text: String): String {
    val offsets = HEX_OFFSET.findAll(text).map { it.value }.distinct().take(MAX_STUB_OFFSETS).toList()
    return "($kind compacted: ${text.length} chars" + (if (offsets.isEmpty()) "" else "; offsets ${offsets.joinToString(" ")}") + ")"
}

/**
 * One file tab's conversation. Immutable: every change returns a new state, and replies are accepted
 * only for the current [runId] while [status] is RUNNING, so a late answer from a cancelled or superseded
 * run can never land in the transcript.
 */
data class AiConversationState(
    val messages: List<AiMessage> = emptyList(),
    val activity: List<AiActivity> = emptyList(),
    val status: AiRunStatus = AiRunStatus.IDLE,
    val runId: Long = 0,
    val provider: AiProviderId? = null,
) {
    private fun nextId() = (messages.maxOfOrNull { it.id } ?: 0) + 1

    /** Bounds what the tab holds: see [AI_FULL_DETAIL_RUNS] and [AI_MAX_ACTIVITY]. */
    private fun bounded(): AiConversationState {
        val userIds = messages.filter { it.role == AiRole.USER }.map { it.id }
        val keepFrom = userIds.getOrNull(userIds.size - AI_FULL_DETAIL_RUNS) ?: Long.MIN_VALUE
        val compacted = if (userIds.size <= AI_FULL_DETAIL_RUNS) messages else messages.map { m ->
            when {
                m.id >= keepFrom -> m
                m.role == AiRole.USER && m.outgoingContext != null && !m.outgoingContext.startsWith("(context compacted") ->
                    m.copy(outgoingContext = stub("context", m.outgoingContext))
                m.role == AiRole.TOOL && !m.text.startsWith("(tool round compacted") -> m.copy(text = stub("tool round", m.text))
                else -> m
            }
        }
        return copy(messages = compacted, activity = activity.takeLast(AI_MAX_ACTIVITY))
    }

    /** Starts a run; returns the new state and the run id the reply must carry. */
    fun withUserMessage(text: String, outgoingContext: String, provider: AiProviderId, now: Long): Pair<AiConversationState, Long> {
        val run = runId + 1
        val state = copy(
            messages = messages + AiMessage(nextId(), AiRole.USER, text, provider, now, outgoingContext),
            status = AiRunStatus.RUNNING, runId = run, provider = provider,
            activity = activity + AiActivity(now, "→ ${provider.displayName}: ${outgoingContext.length} chars of context"),
        ).bounded()
        return state to run
    }

    fun withAssistantReply(run: Long, text: String, now: Long): AiConversationState {
        if (run != runId || status != AiRunStatus.RUNNING) return this
        return copy(
            messages = messages + AiMessage(nextId(), AiRole.ASSISTANT, text, provider, now),
            status = AiRunStatus.FINISHED,
            activity = activity + AiActivity(now, "← ${provider?.displayName ?: "AI"}: ${text.length} chars"),
        ).bounded()
    }

    /** Ends the current run without an answer; [notice] becomes a visible app message. */
    fun withRunEnded(run: Long, status: AiRunStatus, notice: String, now: Long): AiConversationState {
        if (run != runId || this.status != AiRunStatus.RUNNING) return this
        return copy(
            messages = messages + AiMessage(nextId(), AiRole.NOTICE, notice, provider, now),
            status = status,
            activity = activity + AiActivity(now, "${status.name}: $notice"),
        ).bounded()
    }

    fun withActivity(text: String, now: Long) = copy(activity = activity + AiActivity(now, text)).bounded()

    /** Records one bridge round (requests and bounded results) of the current run. */
    fun withToolRound(run: Long, text: String, requestCount: Int, now: Long): AiConversationState {
        if (run != runId || status != AiRunStatus.RUNNING) return this
        return copy(
            messages = messages + AiMessage(nextId(), AiRole.TOOL, text, provider, now),
            activity = activity + AiActivity(now, "app bridge: $requestCount request(s) answered"),
        ).bounded()
    }
}
