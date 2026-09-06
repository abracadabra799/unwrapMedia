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
     * One PowerShell statement chain written into the PTY right after the shell
     * starts. Forces the console to UTF-8 in both directions — `chcp 65001` plus
     * both `[Console]` encodings (wrapped in `try/catch` in case stdin/stdout
     * isn't a real console) plus `$OutputEncoding` — so Korean text survives
     * rendering AND bracketed-paste, which the console otherwise decodes with the
     * legacy OEM code page (cp949 → mojibake). No CLI is launched and the shell is
     * NOT exited: the user runs whichever AI CLI they want and the `PS>` prompt
     * stays available afterwards.
     */
    fun utf8Prelude(): String =
        "chcp 65001 > \$null; " +
            "try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}; " +
            "try { [Console]::InputEncoding = [System.Text.Encoding]::UTF8 } catch {}; " +
            "\$OutputEncoding = [System.Text.Encoding]::UTF8"

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
