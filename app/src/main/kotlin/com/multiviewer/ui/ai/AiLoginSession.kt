package com.multiviewer.ui.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jediterm.terminal.TtyConnector
import com.multiviewer.ui.terminal.PtyCliTtyConnector
import com.multiviewer.util.ProcessManager
import com.pty4j.PtyProcessBuilder
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Runs the selected CLI interactively so its own browser and account flow can finish. */
internal fun aiLoginCommand(executable: File, osName: String): List<String> =
    if (osName.lowercase().contains("win")) {
        listOf("powershell.exe", "-NoLogo", "-NoProfile", "-NoExit", "-Command",
            "& '${executable.path.replace("'", "''")}'")
    } else {
        listOf(executable.absolutePath)
    }

internal sealed interface AiLoginState {
    data object Starting : AiLoginState
    data object Running : AiLoginState
    data class Exited(val code: Int) : AiLoginState
    data class Failed(val reason: String) : AiLoginState
}

internal class AiLoginSession(private val executable: File) {
    private val closed = AtomicBoolean(false)
    var state: AiLoginState by mutableStateOf(AiLoginState.Starting)
        private set
    private var process: Process? = null
    private var connector: TtyConnector? = null
    val ttyConnector: TtyConnector get() = checkNotNull(connector)

    fun start() {
        if (closed.get()) return
        try {
            val windows = System.getProperty("os.name").lowercase().contains("win")
            val p = PtyProcessBuilder()
                .setCommand(aiLoginCommand(executable, System.getProperty("os.name")).toTypedArray())
                .setDirectory(File(System.getProperty("user.home")).absolutePath)
                .setEnvironment(HashMap(System.getenv()).apply { put("TERM", "xterm-256color") })
                .setInitialColumns(100)
                .setInitialRows(30)
                .setConsole(false)
                .setUseWinConPty(windows)
                .setWindowsAnsiColorEnabled(windows)
                .start()
            if (closed.get()) {
                p.destroyForcibly()
                return
            }
            process = ProcessManager.register(p)
            connector = PtyCliTtyConnector(p, executable.name)
            if (closed.get()) {
                destroy()
                return
            }
            state = AiLoginState.Running
            Thread {
                val code = runCatching { p.waitFor() }.getOrNull()
                ProcessManager.unregister(p)
                if (code != null) state = AiLoginState.Exited(code)
            }.apply { isDaemon = true; name = "ai-login-watch" }.start()
        } catch (e: Exception) {
            destroy()
            state = AiLoginState.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    fun destroy() {
        closed.set(true)
        process?.let { p ->
            if (p.isAlive) {
                runCatching { ProcessHandle.of(p.pid()).ifPresent { h -> h.descendants().forEach { it.destroyForcibly() } } }
                runCatching { p.destroyForcibly() }
            }
            ProcessManager.unregister(p)
        }
        runCatching { connector?.close() }
    }

    fun fail(reason: String) {
        destroy()
        state = AiLoginState.Failed(reason)
    }
}
