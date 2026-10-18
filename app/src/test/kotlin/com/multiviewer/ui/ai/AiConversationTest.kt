package com.multiviewer.ui.ai

import com.multiviewer.ui.TabState
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class AiConversationTest {
    @Test
    fun `each tab owns its own conversation`() {
        val a = TabState(File("a.jpg"))
        val b = TabState(File("a.jpg")) // same file in two tabs: still two conversations
        a.aiConversation = a.aiConversation.withUserMessage("hello", "ctx", AiProviderId.CLAUDE, now = 1).first
        assertEquals(1, a.aiConversation.messages.size)
        assertEquals(0, b.aiConversation.messages.size)
        assertNotSame(a.aiConversation, b.aiConversation)
    }

    @Test
    fun `a reply from a superseded or cancelled run is dropped`() {
        val (first, run1) = AiConversationState().withUserMessage("q1", "ctx1", AiProviderId.CODEX, now = 1)
        val cancelled = first.withRunEnded(run1, AiRunStatus.CANCELLED, "stopped", now = 2)
        assertSame(cancelled, cancelled.withAssistantReply(run1, "late answer", now = 3))

        val (second, run2) = cancelled.withUserMessage("q2", "ctx2", AiProviderId.CODEX, now = 4)
        assertSame(second, second.withAssistantReply(run1, "stale", now = 5))
        val answered = second.withAssistantReply(run2, "fresh", now = 6)
        assertEquals(listOf(AiRole.USER, AiRole.NOTICE, AiRole.USER, AiRole.ASSISTANT), answered.messages.map { it.role })
        assertEquals("fresh", answered.messages.last().text)
        assertEquals(AiRunStatus.FINISHED, answered.status)
        assertEquals("ctx2", answered.messages[2].outgoingContext)
    }

    @Test
    fun `a failed run is a visible notice, never an assistant answer`() {
        val (state, run) = AiConversationState().withUserMessage("q", "ctx", AiProviderId.GEMINI, now = 1)
        val failed = state.withRunEnded(run, AiRunStatus.TIMED_OUT, "timed out after 120 s", now = 2)
        assertEquals(AiRole.NOTICE, failed.messages.last().role)
        assertEquals(AiRunStatus.TIMED_OUT, failed.status)
        assertEquals(0, failed.messages.count { it.role == AiRole.ASSISTANT })
    }
}

class AiTabCloseTest {
    @Test
    fun `closing a tab cancels its running ai request`() {
        val appState = com.multiviewer.ui.AppState()
        val tab = TabState(File("x.jpg"))
        appState.tabs.add(tab)
        val job = kotlinx.coroutines.Job()
        tab.aiJob = job
        appState.closeTab(0)
        kotlin.test.assertTrue(job.isCancelled)
    }

    @Test
    fun `sentOffsets collects hex offsets from outgoing context and tool text only`() {
        fun msg(id: Long, role: AiRole, text: String, ctx: String? = null) =
            AiMessage(id, role, text, null, 0, ctx)
        val offsets = sentOffsets(listOf(
            msg(1, AiRole.USER, "question 0xAAAA", ctx = "box at 0x10 and 0xFF"),
            msg(2, AiRole.ASSISTANT, "answer 0xBBBB"),
            msg(3, AiRole.TOOL, "tool read 0x2000"),
            msg(4, AiRole.NOTICE, "notice 0xCCCC"),
        ))
        assertEquals(setOf(0x10L, 0xFFL, 0x2000L), offsets)
        assertEquals(emptySet(), sentOffsets(emptyList()))
    }
}
