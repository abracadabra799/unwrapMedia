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
    fun utf8PreludeForcesConsoleToUtf8WithNoCliInvocationAndNoExit() {
        val p = PtyCliCommand.utf8Prelude()
        assertTrue(p.contains("chcp 65001"), "sets the console code page")
        assertTrue(p.contains("[Console]::InputEncoding"), "sets console input encoding")
        assertTrue(p.contains("[Console]::OutputEncoding"), "sets console output encoding")
        // A plain interactive shell -- it must not launch a CLI or kill itself.
        assertFalse(p.contains("&"), "no CLI invocation")
        assertFalse(p.contains("exit"), "no exit -- the shell stays alive")
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

    @Test
    fun pastePayloadKeepsInternalBlankLinesAsCarriageReturns() {
        assertEquals("a\r\rb", PtyCliCommand.pastePayload("a\n\nb", bracketed = false))
    }

    @Test
    fun pastePayloadStripsStrayPasteEndMarker() {
        val out = PtyCliCommand.pastePayload("before\u001B[201~after", bracketed = true)
        assertEquals("\u001B[200~beforeafter\u001B[201~", out)
    }
}
