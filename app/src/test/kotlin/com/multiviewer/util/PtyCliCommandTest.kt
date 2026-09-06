package com.multiviewer.util

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PtyCliCommandTest {

    @Test
    fun powershellArgvIsANoLogoBypassHost() {
        val argv = PtyCliCommand.powershellArgv()
        assertEquals("powershell.exe", argv.first())
        assertTrue(argv.contains("-NoLogo"))
        assertTrue(argv.contains("-ExecutionPolicy"))
        assertTrue(argv.contains("Bypass"))
        // -NoExit is a no-op for an interactive ConPTY host and would keep the
        // shell alive after the CLI exits, hiding the Exited state.
        assertFalse(argv.contains("-NoExit"))
    }

    @Test
    fun launchLineForClaudeInvokesBinaryWithUtf8AndExitsWithCliCode() {
        val line = PtyCliCommand.launchLine(AiCliType.CLAUDE, "C:\\tools\\claude.cmd")
        assertTrue(line.contains("OutputEncoding"))
        assertTrue(line.contains("& 'C:\\tools\\claude.cmd'"))
        assertFalse(line.contains(" -i"))
        assertTrue(line.contains("\$LASTEXITCODE"))
        assertTrue(line.trimEnd().endsWith("})"))
    }

    @Test
    fun launchLineForAgyAddsInteractiveFlag() {
        val line = PtyCliCommand.launchLine(AiCliType.AGY, "C:\\tools\\agy.cmd")
        assertTrue(line.contains("& 'C:\\tools\\agy.cmd' -i"))
    }

    @Test
    fun launchLineEscapesSingleQuoteInPath() {
        val line = PtyCliCommand.launchLine(AiCliType.CLAUDE, "C:\\us'er\\claude.cmd")
        assertTrue(line.contains("& 'C:\\us''er\\claude.cmd'"))
    }

    @Test
    fun pastePayloadBracketedWrapsAndNormalizes() {
        val out = PtyCliCommand.pastePayload("line1\r\nline2\n\n", bracketed = true)
        assertEquals("\u001B[200~line1\rline2\u001B[201~", out)
    }

    @Test
    fun pastePayloadRawNormalizesWithoutMarkers() {
        val out = PtyCliCommand.pastePayload("a\r\nb\n", bracketed = false)
        assertEquals("a\rb", out)
    }

    @Test
    fun pastePayloadHandlesEmptyString() {
        assertEquals("\u001B[200~\u001B[201~", PtyCliCommand.pastePayload("", bracketed = true))
        assertEquals("", PtyCliCommand.pastePayload("", bracketed = false))
    }
}
