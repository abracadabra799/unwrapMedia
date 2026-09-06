package com.multiviewer.util

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AiCliTypeTest {

    @Test
    fun fourClisWithDisplayNamesAndCommands() {
        assertEquals(
            listOf("Claude Code", "Codex", "Antigravity", "Gemini CLI"),
            AiCliType.entries.map { it.displayName },
        )
        assertEquals(
            listOf("claude", "codex", "agy", "gemini"),
            AiCliType.entries.map { it.command },
        )
    }

    @Test
    fun agyIsListedBeforeGemini() {
        val names = AiCliType.entries.map { it.name }
        assertTrue(names.indexOf("AGY") < names.indexOf("GEMINI"), "order was $names")
    }
}
