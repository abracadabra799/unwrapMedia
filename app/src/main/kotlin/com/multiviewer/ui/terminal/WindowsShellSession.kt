package com.multiviewer.ui.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jediterm.terminal.TtyConnector
import com.multiviewer.util.PtyCliCommand
import com.multiviewer.util.ProcessManager
import com.pty4j.PtyProcessBuilder
import java.io.File

internal sealed interface SessionState {
    data object Starting : SessionState
    data object Running : SessionState
    data class Exited(val code: Int) : SessionState
    data class Failed(val reason: String) : SessionState
}

/**
 * Owns one interactive PowerShell running inside a ConPTY. Windows only. One
 * session at a time is enforced by the caller. No CLI is launched — the user runs
 * whichever AI CLI they want in the shell; [promptText] is on the clipboard for
 * them to paste.
 */
internal class WindowsShellSession(
    private val workingDir: File?,
    val promptText: String,
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

    val isAlive: Boolean get() = process?.isAlive == true

    fun start() {
        try {
            val p = startProcess()
            process = ProcessManager.register(p)
            _ttyConnector = PtyCliTtyConnector(p)
            state = SessionState.Running
            // Off the caller thread (the EDT): the PTY pipe may not be drained yet.
            Thread {
                runCatching { _ttyConnector?.write(PtyCliCommand.utf8Prelude() + "\r") }
            }.apply { isDaemon = true; name = "shell-prelude" }.start()
            Thread {
                try {
                    val code = p.waitFor()
                    ProcessManager.unregister(p)
                    state = SessionState.Exited(code)
                } catch (_: InterruptedException) {
                }
            }.apply { isDaemon = true; name = "shell-watch" }.start()
        } catch (t: Throwable) {
            runCatching { process?.destroyForcibly() }
            process?.let { ProcessManager.unregister(it) }
            state = SessionState.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    fun destroy() {
        process?.let { p ->
            // Kill the CLI the user launched (node.exe etc.) too. pty4j's
            // WinConPtyProcess.destroy() only terminates the PowerShell handle and
            // doesn't override toHandle(), so p.descendants() throws — go via
            // ProcessHandle.of(pid). Guard on isAlive so a recycled PID can't point
            // us at a stranger.
            if (p.isAlive) {
                runCatching {
                    java.lang.ProcessHandle.of(p.pid()).ifPresent { h ->
                        h.descendants().forEach { it.destroyForcibly() }
                    }
                }
            }
            runCatching { p.destroyForcibly() }
            ProcessManager.unregister(p)
        }
        runCatching { _ttyConnector?.close() }
    }
}
