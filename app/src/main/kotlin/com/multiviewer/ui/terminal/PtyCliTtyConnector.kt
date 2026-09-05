package com.multiviewer.ui.terminal

import com.jediterm.core.util.TermSize
import com.jediterm.terminal.ProcessTtyConnector
import com.pty4j.PtyProcess
import com.pty4j.WinSize
import java.nio.charset.StandardCharsets

/**
 * Bridges a [Process] (real: a pty4j [PtyProcess]) to JediTerm. JediTerm's own
 * `PtyProcessTtyConnector` is not shipped in the published jediterm-core /
 * jediterm-ui artifacts, so this small subclass reimplements it.
 */
class PtyCliTtyConnector(
    private val process: Process,
) : ProcessTtyConnector(process, StandardCharsets.UTF_8, null) {

    override fun getName(): String = "AI CLI"

    override fun resize(termSize: TermSize) {
        val p = process
        if (p is PtyProcess && p.isAlive) {
            p.setWinSize(WinSize(termSize.columns, termSize.rows))
        }
    }
}
