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
     * The PowerShell line written into the PTY to start the CLI. Forces the
     * console to UTF-8 first — `chcp 65001` plus both `[Console]` encodings — so
     * the multibyte (e.g. Korean) diagnostic prompt survives both directions:
     * output rendering AND the bracketed-paste injection, which the console
     * otherwise decodes with the legacy OEM code page (cp949 → mojibake). The
     * `[Console]` assignments are wrapped in `try/catch` because setting them can
     * throw on a host whose stdin/stdout isn't a real console; `chcp` alone still
     * covers that case. Then exits the host with the CLI's exit code (or 1 if it
     * never ran) so the shell dies when the CLI does — this is what lets the
     * session report `Exited` instead of dropping to `PS C:\>`.
     */
    fun launchLine(cli: AiCliType, binPath: String): String {
        // Single-quote the path so a '$' in a user profile name isn't interpolated
        // by PowerShell; '' escapes a literal quote.
        val quoted = "'" + binPath.replace("'", "''") + "'"
        val invoke = when (cli) {
            AiCliType.AGY -> "& $quoted -i"
            else -> "& $quoted"
        }
        val utf8Prelude = "chcp 65001 > \$null; " +
            "try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}; " +
            "try { [Console]::InputEncoding = [System.Text.Encoding]::UTF8 } catch {}; " +
            "\$OutputEncoding = [System.Text.Encoding]::UTF8; "
        return utf8Prelude + "$invoke; " +
            "\$ok=\$?; \$ec=\$LASTEXITCODE; exit \$(if (\$ok -and \$null -ne \$ec) {\$ec} else {1})"
    }

    /**
     * The bytes to feed the terminal to paste [text] into the CLI's input. When
     * [bracketed], wraps in xterm bracketed-paste markers so a readline/Ink CLI
     * inserts the whole block at once instead of submitting each line; otherwise
     * raw. Embedded newlines are normalized to CR (terminal paste convention),
     * trailing newlines trimmed, and any stray paste-end marker in the text
     * is stripped so it cannot end the burst early. No trailing submit CR.
     */
    fun pastePayload(text: String, bracketed: Boolean): String {
        val body = text
            .replace("\u001B[200~", "")
            .replace("\u001B[201~", "")
            .replace("\r\n", "\n")
            .replace('\n', '\r')
            .trimEnd('\r')
        return if (bracketed) "\u001B[200~" + body + "\u001B[201~" else body
    }
}
