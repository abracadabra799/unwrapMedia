package com.multiviewer.ui.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jediterm.core.util.TermSize
import com.jediterm.terminal.TtyConnector
import com.multiviewer.util.AiCliType
import com.multiviewer.util.PtyCliCommand
import com.pty4j.PtyProcessBuilder
import java.io.File

sealed interface SessionState {
    data object Starting : SessionState
    data object Running : SessionState
    data class Exited(val code: Int) : SessionState
    data class Failed(val reason: String) : SessionState
}

/**
 * Owns exactly one AI CLI process running inside a PowerShell ConPTY session.
 * Windows only. One session at a time is enforced by the caller.
 */
class WindowsPtyCliSession(
    private val cli: AiCliType,
    private val binPath: String,
    private val workingDir: File?,
    private val promptText: String,
    private val startProcess: () -> Process = {
        val dir = workingDir?.takeIf { it.isDirectory } ?: File(System.getProperty("user.home"))
        PtyProcessBuilder()
            .setCommand(PtyCliCommand.powershellArgv())
            .setDirectory(dir.absolutePath)
            .setEnvironment(HashMap(System.getenv()).apply { put("TERM", "xterm-256color") })
            .setInitialColumns(120)
            .setInitialRows(30)
            .setConsole(false)
            .setUseWinConPty(true)
            .setWindowsAnsiColorEnabled(true)
            .start()
    },
) {
    var state: SessionState by mutableStateOf(SessionState.Starting)
        private set

    private var process: Process? = null
    private var _ttyConnector: TtyConnector? = null

    val ttyConnector: TtyConnector
        get() = checkNotNull(_ttyConnector) { "start() has not created a connector" }

    val displayName: String get() = cli.displayName

    val isAlive: Boolean get() = process?.isAlive == true

    fun start() {
        try {
            val p = startProcess()
            process = p
            _ttyConnector = PtyCliTtyConnector(p)
            // Running is set before the watcher Thread object exists, so Exited can
            // only ever follow Running — no start()-vs-watcher ordering race.
            state = SessionState.Running
            runCatching { _ttyConnector!!.write(PtyCliCommand.launchLine(cli, binPath) + "\r") }
            Thread {
                try {
                    val code = p.waitFor()
                    // Deliberate off-thread write: Compose snapshot state is safe to
                    // write from any thread and schedules recomposition on its own.
                    // After destroy() this still fires with the forced exit code.
                    state = SessionState.Exited(code)
                } catch (_: InterruptedException) {
                    // not currently reachable (destroy() uses destroyForcibly(), not
                    // interrupt) — kept so a future interrupting teardown stays quiet
                }
            }.apply { isDaemon = true; name = "ai-cli-watch" }.start()
        } catch (t: Throwable) {
            state = SessionState.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    fun injectPrompt() {
        if (isAlive) {
            runCatching { ttyConnector.write(PtyCliCommand.bracketedPaste(promptText)) }
        }
    }

    /**
     * Explicitly push a terminal size to the PTY. Currently unused: JediTerm's
     * `JediTermWidget` drives sizing itself via a component listener that calls
     * [PtyCliTtyConnector.resize] on the connector directly. Kept for a future
     * manual "fit" control or a resize path that does not go through the widget.
     */
    fun resize(columns: Int, rows: Int) {
        runCatching { _ttyConnector?.resize(TermSize(columns, rows)) }
    }

    fun destroy() {
        runCatching { process?.destroyForcibly() }
        runCatching { _ttyConnector?.close() }
    }
}
