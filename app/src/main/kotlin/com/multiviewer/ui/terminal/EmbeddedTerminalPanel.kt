package com.multiviewer.ui.terminal

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jediterm.terminal.model.StyleState
import com.jediterm.terminal.model.TerminalTextBuffer
import com.jediterm.terminal.ui.JediTermWidget
import com.jediterm.terminal.ui.TerminalPanel
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider
import com.jediterm.terminal.ui.settings.SettingsProvider
import com.multiviewer.ui.AppColors
import kotlinx.coroutines.delay
import java.awt.Font

/**
 * Fallback deadline for prompt injection. Normally injection fires as soon as the
 * CLI enables bracketed-paste mode (see [BracketedPasteSignal]); this is the cap
 * for a CLI that is slow or never enables it.
 */
internal const val PROMPT_INJECT_MAX_WAIT_MS: Long = 8000

/** Flipped by [ReadySignalTerminalPanel] when the CLI turns on bracketed-paste mode. */
private class BracketedPasteSignal {
    @Volatile
    var isReady = false
        private set

    fun markReady() { isReady = true }
}

private class CliTerminalSettings(val readySignal: BracketedPasteSignal) : DefaultSettingsProvider() {
    // Consolas ships on Windows and renders TUI box-drawing far better than the
    // generic MONOSPACED logical font (Courier New).
    override fun getTerminalFont(): Font = Font("Consolas", Font.PLAIN, 13)
    override fun getTerminalFontSize(): Float = 13f
    override fun audibleBell(): Boolean = false
}

private class ReadySignalTerminalPanel(
    settings: SettingsProvider,
    textBuffer: TerminalTextBuffer,
    styleState: StyleState,
    private val signal: BracketedPasteSignal,
) : TerminalPanel(settings, textBuffer, styleState) {
    override fun setBracketedPasteMode(enabled: Boolean) {
        super.setBracketedPasteMode(enabled)
        if (enabled) signal.markReady()
    }
}

/**
 * `JediTermWidget` whose terminal panel reports when the running program enables
 * bracketed-paste mode — the moment a readline-based CLI (Claude Code, Gemini,
 * Codex, agy) is actually ready to receive a pasted prompt.
 *
 * The signal is threaded through the [SettingsProvider] because
 * `createTerminalPanel` is invoked from the `JediTermWidget` constructor, before
 * this subclass's own fields are initialized.
 */
private class ReadyAwareJediTermWidget(
    columns: Int,
    rows: Int,
    settings: CliTerminalSettings,
) : JediTermWidget(columns, rows, settings) {
    override fun createTerminalPanel(
        settingsProvider: SettingsProvider,
        styleState: StyleState,
        textBuffer: TerminalTextBuffer,
    ): TerminalPanel {
        val signal = (settingsProvider as CliTerminalSettings).readySignal
        return ReadySignalTerminalPanel(settingsProvider, textBuffer, styleState, signal)
    }
}

/**
 * Bottom panel of the AI prompt popup: a live VT100 terminal running the CLI.
 * Auto-injects the diagnostic prompt once the CLI is ready (or after
 * [PROMPT_INJECT_MAX_WAIT_MS] as a fallback).
 */
@Composable
internal fun EmbeddedTerminalPanel(
    session: WindowsPtyCliSession,
    onEndSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = session.state
    val readySignal = remember(session) { BracketedPasteSignal() }
    var widget by remember(session) { mutableStateOf<JediTermWidget?>(null) }

    // Send the prompt through JediTerm's own writer thread (never the caller /
    // EDT): sendString queues on TerminalStarter's single-thread executor, so a
    // multi-KB write can't block the UI, and it can't interleave with the user's
    // keystrokes. The `true` flag lets JediTerm wrap it in bracketed-paste
    // markers only if the CLI actually enabled that mode.
    fun injectPrompt() {
        widget?.terminalStarter?.let { starter ->
            starter.sendString(session.promptText, true)
            starter.sendString("\r", false)
        }
    }

    LaunchedEffect(session) {
        val deadline = System.currentTimeMillis() + PROMPT_INJECT_MAX_WAIT_MS
        while (!readySignal.isReady && System.currentTimeMillis() < deadline) {
            delay(50)
        }
        injectPrompt()
    }
    DisposableEffect(session) {
        onDispose { widget?.close() }
    }

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("● ${session.displayName}", fontSize = 11.sp, color = AppColors.NeonPurple)
            Spacer(Modifier.width(10.dp))
            Text(
                when (val s = state) {
                    SessionState.Starting -> "기동 중…"
                    SessionState.Running -> "실행 중"
                    is SessionState.Exited -> "종료됨 (exit ${s.code})"
                    is SessionState.Failed -> "실패: ${s.reason}"
                },
                fontSize = 11.sp,
                color = AppColors.TextSecondary,
            )
            Spacer(Modifier.width(10.dp))
            TextButton(onClick = { injectPrompt() }) {
                Text("프롬프트 재주입", fontSize = 11.sp, color = AppColors.NeonPurple)
            }
            TextButton(onClick = onEndSession) {
                Text("세션 종료", fontSize = 11.sp, color = AppColors.TextSecondary)
            }
        }
        SwingPanel(
            background = Color(0xFF13161A),
            modifier = Modifier.fillMaxWidth().weight(1f),
            // factory runs once per mount. A CLI switch changes the session
            // instance; the call site wraps this panel in key(session) so the
            // whole composable remounts and the widget is recreated with the new
            // connector. Do not rely on recomposition to rebind the connector.
            factory = {
                runCatching {
                    ReadyAwareJediTermWidget(120, 30, CliTerminalSettings(readySignal)).also { w ->
                        w.ttyConnector = session.ttyConnector
                        w.start()
                        w.requestFocusInWindow()
                        widget = w
                    }
                }.getOrElse {
                    // Spec: JediTermWidget init failure → tear the session down
                    // rather than crash the popup composition.
                    onEndSession()
                    JediTermWidget(1, 1, CliTerminalSettings(readySignal)).also { widget = it }
                }
            },
            update = { _ ->
                // size is driven by JediTermWidget's own component listener
            },
        )
    }
}
