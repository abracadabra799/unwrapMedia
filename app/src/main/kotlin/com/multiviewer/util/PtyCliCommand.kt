package com.multiviewer.util

/**
 * Pure string builders for driving an AI CLI inside a PowerShell PTY session.
 * No I/O, no process launching — kept separate so it is unit-testable.
 */
internal object PtyCliCommand {

    /** PowerShell host for the PTY. Interactive by default under ConPTY (no -File/-Command). */
    fun powershellArgv(): Array<String> = arrayOf(
        "powershell.exe", "-NoLogo", "-ExecutionPolicy", "Bypass",
    )

    /**
     * The PowerShell line written into the PTY to start the CLI. Sets UTF-8
     * output first so multibyte prompt text renders correctly, then exits the
     * host with the CLI's exit code (or 1 if it never ran) so the shell dies when
     * the CLI does — this is what lets the session report `Exited` instead of
     * dropping to `PS C:\>`.
     */
    fun launchLine(cli: AiCliType, binPath: String): String {
        // Single-quote the path so a '$' in a user profile name isn't interpolated
        // by PowerShell; '' escapes a literal quote.
        val quoted = "'" + binPath.replace("'", "''") + "'"
        val invoke = when (cli) {
            AiCliType.AGY -> "& $quoted -i"
            else -> "& $quoted"
        }
        return "[Console]::OutputEncoding=[System.Text.Encoding]::UTF8; $invoke; " +
            "exit \$(if (\$null -eq \$LASTEXITCODE) {1} else {\$LASTEXITCODE})"
    }

    /**
     * The bytes to feed the terminal to paste [text] into the CLI's input. When
     * [bracketed], wraps in xterm bracketed-paste markers so a readline/Ink CLI
     * inserts the whole block at once instead of submitting each line; otherwise
     * raw. Embedded newlines are normalized to CR (terminal paste convention),
     * trailing newlines trimmed. Does NOT include the final submit CR.
     */
    fun pastePayload(text: String, bracketed: Boolean): String {
        val body = text.replace("\r\n", "\n").replace('\n', '\r').trimEnd('\r')
        return if (bracketed) "\u001B[200~" + body + "\u001B[201~" else body
    }
}
