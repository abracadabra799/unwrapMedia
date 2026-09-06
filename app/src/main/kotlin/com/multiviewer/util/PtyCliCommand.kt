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
     * host with the CLI's exit code so the shell dies when the CLI does (this is
     * what lets the session report `Exited` instead of dropping to `PS C:\>`).
     */
    fun launchLine(cli: AiCliType, binPath: String): String {
        // Single-quote the path so a '$' in a user profile name isn't interpolated
        // by PowerShell; '' escapes a literal quote.
        val quoted = "'" + binPath.replace("'", "''") + "'"
        val invoke = when (cli) {
            AiCliType.AGY -> "& $quoted -i"
            else -> "& $quoted"
        }
        return "[Console]::OutputEncoding=[System.Text.Encoding]::UTF8; $invoke; exit \$LASTEXITCODE"
    }
}
