package com.multiviewer.ui.ai

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiConversationRetentionTest {
    private fun bigContext(i: Int) = "ctx$i @ 0x${(0x1000 + i).toString(16)} " + "x".repeat(20_000)

    private fun conversationWith(runs: Int): AiConversationState {
        var c = AiConversationState()
        repeat(runs) { i ->
            val (started, run) = c.withUserMessage("question $i", bigContext(i), AiProviderId.CLAUDE, i.toLong())
            c = started.withToolRound(run, "hex 0x${(0x2000 + i).toString(16)} " + "y".repeat(8_000), 1, i.toLong())
                .withAssistantReply(run, "answer $i", i.toLong())
        }
        return c
    }

    @Test
    fun `only the latest runs keep full contexts and tool output`() {
        val c = conversationWith(25)
        val users = c.messages.filter { it.role == AiRole.USER }
        val tools = c.messages.filter { it.role == AiRole.TOOL }
        assertEquals(25, users.size) // the transcript itself is kept
        users.takeLast(AI_FULL_DETAIL_RUNS).forEach { assertTrue(it.outgoingContext!!.length > 20_000) }
        users.dropLast(AI_FULL_DETAIL_RUNS).forEach { assertTrue(it.outgoingContext!!.length < 400, it.outgoingContext) }
        tools.takeLast(AI_FULL_DETAIL_RUNS).forEach { assertTrue(it.text.length > 8_000) }
        tools.dropLast(AI_FULL_DETAIL_RUNS).forEach { assertTrue(it.text.length < 400, it.text) }
        // Questions and answers stay intact, so history and the transcript read the same.
        assertEquals((0 until 25).map { "question $it" }, users.map { it.text })
        assertEquals((0 until 25).map { "answer $it" }, c.messages.filter { it.role == AiRole.ASSISTANT }.map { it.text })
    }

    @Test
    fun `compacted messages keep the offsets the app sent, so evidence chips still resolve`() {
        val c = conversationWith(25)
        val first = c.messages.first { it.role == AiRole.USER }
        assertTrue(first.outgoingContext!!.contains("0x1000"), first.outgoingContext)
        val firstTool = c.messages.first { it.role == AiRole.TOOL }
        assertTrue(firstTool.text.contains("0x2000"), firstTool.text)
    }

    @Test
    fun `memory held by a long conversation is bounded`() {
        val c = conversationWith(200)
        val chars = c.messages.sumOf { it.text.length + (it.outgoingContext?.length ?: 0) }
        // ~ AI_FULL_DETAIL_RUNS × (20 k context + 8 k tool) plus small stubs for the rest.
        assertTrue(chars < AI_FULL_DETAIL_RUNS * 30_000 + 200 * 1_000, "held $chars chars")
        assertTrue(c.activity.size <= AI_MAX_ACTIVITY, "activity ${c.activity.size}")
    }

    @Test
    fun `stale ai work dirs are removed at startup, fresh ones and unrelated dirs are kept`() {
        val tmp = Files.createTempDirectory("ai-sweep").toFile()
        try {
            val stale = File(tmp, "unwrapmedia-ai-111").apply { mkdirs(); File(this, "view-1.png").writeBytes(ByteArray(10)) }
            val fresh = File(tmp, "unwrapmedia-ai-222").apply { mkdirs() }
            val other = File(tmp, "something-else").apply { mkdirs() }
            val staleFile = File(tmp, "unwrapmedia-ai-not-a-dir").apply { writeText("x") }
            val old = System.currentTimeMillis() - 2 * 60 * 60 * 1000L
            stale.setLastModified(old); File(stale, "view-1.png").setLastModified(old); staleFile.setLastModified(old)
            val removed = cleanStaleAiWorkDirs(tmp, olderThanMillis = 60 * 60 * 1000L)
            assertEquals(1, removed)
            assertFalse(stale.exists())
            assertTrue(fresh.exists())
            assertTrue(other.exists())
            assertTrue(staleFile.exists()) // only directories the runner creates are touched
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `deleting a work dir never follows a symlink out of it`() {
        val tmp = Files.createTempDirectory("ai-link").toFile()
        try {
            val outside = File(tmp, "outside").apply { mkdirs() }
            val precious = File(outside, "keep.txt").apply { writeText("keep") }
            val work = File(tmp, "unwrapmedia-ai-333").apply { mkdirs() }
            Files.createSymbolicLink(File(work, "link").toPath(), outside.toPath())
            assertTrue(deleteTreeNoFollow(work))
            assertFalse(work.exists())
            assertTrue(precious.exists(), "symlink target was deleted")
            // The sweep uses the same delete.
            val stale = File(tmp, "unwrapmedia-ai-444").apply { mkdirs() }
            Files.createSymbolicLink(File(stale, "link").toPath(), outside.toPath())
            // The link's own mtime is fresh and can't portably be set; judge staleness from "two hours later".
            cleanStaleAiWorkDirs(tmp, olderThanMillis = 60 * 60 * 1000L, now = System.currentTimeMillis() + 2 * 60 * 60 * 1000L)
            assertFalse(stale.exists())
            assertTrue(precious.exists(), "sweep followed the symlink")
        } finally {
            tmp.deleteRecursively()
        }
    }
}
