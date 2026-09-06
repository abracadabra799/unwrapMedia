package com.multiviewer.ui.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jediterm.terminal.TtyConnector
import com.multiviewer.util.AiCliType
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
 * Owns exactly one AI CLI process running inside a PowerShell ConPTY session.
 * Windows only. One session at a time is enforced by the caller.
 */
internal class WindowsPtyCliSession(
    val cli: AiCliType,
    private val binPath: String,
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

    val displayName: String get() = cli.displayName

    val isAlive: Boolean get() = process?.isAlive == true

    fun start() {
        try {
            val p = startProcess()
            // Tracked so the JVM shutdown hook / Main's destroyAll() kill it on app
            // quit even when Compose disposal is preempted by exitProcess().
            process = ProcessManager.register(p)
            _ttyConnector = PtyCliTtyConnector(p)
            // Running is set before the watcher Thread object exists, so Exited can
            // only ever follow Running — no start()-vs-watcher ordering race.
            state = SessionState.Running
            // Off the caller thread (the EDT): even this small write goes to a PTY
            // pipe that may not be drained yet.
            Thread {
                runCatching { _ttyConnector?.write(PtyCliCommand.launchLine(cli, binPath) + "\r") }
            }.apply { isDaemon = true; name = "ai-cli-launch" }.start()
            Thread {
                try {
                    val code = p.waitFor()
                    ProcessManager.unregister(p)
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
            runCatching { process?.destroyForcibly() }
            process?.let { ProcessManager.unregister(it) }
            state = SessionState.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    fun destroy() {
        process?.let { p ->
            // Kill the CLI (node.exe etc.) too. pty4j's WinConPtyProcess.destroy()
            // only terminates the PowerShell handle, and it doesn't override
            // toHandle(), so p.descendants() throws — go via ProcessHandle.of(pid).
            // Guard on isAlive so a recycled PID can't point us at a stranger.
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
