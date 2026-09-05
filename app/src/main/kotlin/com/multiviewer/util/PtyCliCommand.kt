package com.multiviewer.util

/**
 * Pure string builders for driving an AI CLI inside a PowerShell PTY session.
 * No I/O, no process launching — kept separate so it is unit-testable.
 */
object PtyCliCommand {

    /** Interactive, non-exiting PowerShell host used as the PTY shell. */
    fun powershellArgv(): Array<String> = arrayOf(
        "powershell.exe", "-NoLogo", "-NoExit", "-ExecutionPolicy", "Bypass",
    )

    /**
     * The PowerShell line written into the PTY to start the CLI. Sets UTF-8
     * output first so multibyte prompt text renders correctly.
     */
    fun launchLine(cli: AiCliType, binPath: String): String {
        val invoke = when (cli) {
            AiCliType.AGY -> "& \"$binPath\" -i"
            else -> "& \"$binPath\""
        }
        return "[Console]::OutputEncoding=[System.Text.Encoding]::UTF8; $invoke"
    }

    /**
     * Wraps [text] as an xterm bracketed-paste burst followed by a carriage
     * return so the CLI receives it as one pasted block and then submits.
     * CRLF / lone CR are normalized to LF; trailing newlines are trimmed.
     */
    fun bracketedPaste(text: String): String {
        val body = text.replace("\r\n", "\n").replace("\r", "\n").trimEnd('\n')
        return "\u001B[200~" + body + "\u001B[201~\r"
    }
}
