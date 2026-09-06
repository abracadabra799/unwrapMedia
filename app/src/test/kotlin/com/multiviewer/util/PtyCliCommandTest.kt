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
        assertTrue(line.contains("& \"C:\\tools\\claude.cmd\""))
        assertFalse(line.contains(" -i"))
        assertTrue(line.trimEnd().endsWith("exit \$LASTEXITCODE"))
    }

    @Test
    fun launchLineForAgyAddsInteractiveFlag() {
        val line = PtyCliCommand.launchLine(AiCliType.AGY, "C:\\tools\\agy.cmd")
        assertTrue(line.contains("& \"C:\\tools\\agy.cmd\" -i"))
        assertTrue(line.trimEnd().endsWith("exit \$LASTEXITCODE"))
    }
}
