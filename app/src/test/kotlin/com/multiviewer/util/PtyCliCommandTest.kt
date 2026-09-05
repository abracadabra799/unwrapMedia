package com.multiviewer.util

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PtyCliCommandTest {

    @Test
    fun powershellArgvStartsInteractiveNonExitingShell() {
        val argv = PtyCliCommand.powershellArgv()
        assertEquals("powershell.exe", argv.first())
        assertTrue(argv.contains("-NoExit"))
        assertTrue(argv.contains("-NoLogo"))
        assertTrue(argv.contains("-ExecutionPolicy"))
        assertTrue(argv.contains("Bypass"))
    }

    @Test
    fun launchLineForClaudeInvokesBinaryWithUtf8() {
        val line = PtyCliCommand.launchLine(AiCliType.CLAUDE, "C:\\tools\\claude.cmd")
        assertTrue(line.contains("OutputEncoding"))
        assertTrue(line.contains("& \"C:\\tools\\claude.cmd\""))
        assertFalse(line.contains(" -i"))
    }

    @Test
    fun launchLineForAgyAddsInteractiveFlag() {
        val line = PtyCliCommand.launchLine(AiCliType.AGY, "C:\\tools\\agy.cmd")
        assertTrue(line.contains("& \"C:\\tools\\agy.cmd\" -i"))
    }

    @Test
    fun bracketedPasteWrapsWithMarkersAndSubmits() {
        val out = PtyCliCommand.bracketedPaste("hello")
        assertEquals("\u001B[200~hello\u001B[201~\r", out)
    }

    @Test
    fun bracketedPasteNormalizesCrlfAndTrimsTrailingNewlines() {
        val out = PtyCliCommand.bracketedPaste("a\r\nb\r\n\n")
        assertEquals("\u001B[200~a\nb\u001B[201~\r", out)
    }

    @Test
    fun bracketedPasteHandlesEmptyString() {
        assertEquals("\u001B[200~\u001B[201~\r", PtyCliCommand.bracketedPaste(""))
    }
}
