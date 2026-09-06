package com.multiviewer.ui.terminal

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.TextStyle
import com.jediterm.terminal.model.StyleState
import com.jediterm.terminal.model.TerminalTextBuffer
import com.jediterm.terminal.ui.JediTermWidget
import com.jediterm.terminal.ui.TerminalPanel
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider
import com.jediterm.terminal.ui.settings.SettingsProvider
import com.multiviewer.ui.AppColors
import com.multiviewer.util.PtyCliCommand
import kotlinx.coroutines.delay
import java.awt.Component
import java.awt.Font
import javax.swing.JPanel

/**
 * Tracks whether the running CLI currently has bracketed-paste mode on — the
 * signal that it is a readline/Ink prompt ready to receive a pasted block rather
 * than, say, its login menu or a still-booting screen. Compose snapshot state so
 * the "프롬프트 붙여넣기" button can enable itself (written from JediTerm's reader
 * thread — safe, same as [SessionState]).
 *
 * The prompt is NEVER auto-injected: Claude Code / Codex turn bracketed-paste on
 * at startup while still on their browser-login screen, so a blind paste there
 * corrupts the login flow. The user pastes it themselves (this button, or the
 * terminal's own Ctrl/Cmd+V) once they are actually at the CLI prompt.
 */
internal class BracketedPasteSignal {
    // "has it ever been on" — once a CLI reaches an interactive prompt it stays a
    // sensible paste target even if it briefly toggles the mode (e.g. redraw).
    var isReady by mutableStateOf(false)
        private set

    fun onBracketedPasteMode(enabled: Boolean) { if (enabled) isReady = true }
}

/**
 * JediTerm 3.74's [DefaultSettingsProvider] defaults to black-on-white
 * (`UserSettingsProvider.getDefaultStyle()` → `TextStyle(BLACK, WHITE)`), which
 * looks nothing like a dev-tool console and clashes with the app's dark panel.
 * Override the default style so the terminal is light-on-dark, matching the
 * [SwingPanel] background used below. `getDefaultForeground`/`getDefaultBackground`
 * both delegate to `getDefaultStyle()`, so this one override is enough.
 */
internal val CLI_TERMINAL_FOREGROUND: TerminalColor = TerminalColor.rgb(0xC9, 0xD1, 0xD9)
internal val CLI_TERMINAL_BACKGROUND: TerminalColor = TerminalColor.rgb(0x13, 0x16, 0x1A)

internal class CliTerminalSettings(val readySignal: BracketedPasteSignal) : DefaultSettingsProvider() {
    // Consolas ships on Windows and renders TUI box-drawing far better than the
    // generic MONOSPACED logical font (Courier New).
    override fun getTerminalFont(): Font = Font("Consolas", Font.PLAIN, 13)
    override fun getTerminalFontSize(): Float = 13f
    override fun audibleBell(): Boolean = false

    // getDefaultStyle() is @Deprecated in jediterm 3.74's UserSettingsProvider, but
    // TerminalPanel / StyleState still resolve the console's base colours through it
    // (getDefaultForeground/Background delegate here). No non-deprecated replacement
    // exists in this pinned version.
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getDefaultStyle(): TextStyle = TextStyle(CLI_TERMINAL_FOREGROUND, CLI_TERMINAL_BACKGROUND)
}

private class ReadySignalTerminalPanel(
    settings: SettingsProvider,
    textBuffer: TerminalTextBuffer,
    styleState: StyleState,
    private val signal: BracketedPasteSignal,
) : TerminalPanel(settings, textBuffer, styleState) {
    override fun setBracketedPasteMode(enabled: Boolean) {
        super.setBracketedPasteMode(enabled)
        signal.onBracketedPasteMode(enabled)
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
 * The AI prompt popup's live VT100 terminal running the CLI (docked to the right
 * of the prompt view). The diagnostic prompt is NOT auto-injected — it is copied
 * to the clipboard when the session starts, and the user pastes it (the
 * "프롬프트 붙여넣기" button, or the terminal's own Ctrl/Cmd+V) once they have
 * finished any browser login and are at the CLI's own prompt. Auto-injecting hit
 * Claude Code / Codex mid-login (they enable bracketed-paste on their menu
 * screens) and corrupted the flow.
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
    // EDT): sendString bottoms out in an executor.execute(), so a multi-KB write
    // can't block the UI and can't interleave with the user's keystrokes.
    // bracketed=true so an Ink/readline CLI inserts the whole block at once; and
    // crucially NO trailing CR — the user reviews the pasted prompt and presses
    // Enter themselves. sendString's `false` = "not user typing" (skips typeahead).
    fun pastePrompt() {
        if (!readySignal.isReady) return
        val starter = widget?.terminalStarter ?: return
        starter.sendString(PtyCliCommand.pastePayload(session.promptText, bracketed = true), false)
    }

    LaunchedEffect(widget) {
        // requestFocusInWindow() is a no-op until the peer is realized.
        if (widget != null) {
            delay(120)
            widget?.requestFocusInWindow()
        }
    }
    DisposableEffect(session) {
        onDispose { widget?.close() }
    }

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("● ${session.displayName}", fontSize = 11.sp, color = AppColors.NeonPurple, maxLines = 1)
            Spacer(Modifier.width(8.dp))
            Text(
                when (val s = state) {
                    SessionState.Starting -> "기동 중…"
                    SessionState.Running -> "실행 중"
                    is SessionState.Exited -> "종료됨 (exit ${s.code})"
                    is SessionState.Failed -> "실패: ${s.reason}"
                },
                fontSize = 11.sp,
                color = AppColors.TextSecondary,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(4.dp))
            TextButton(
                onClick = { pastePrompt() },
                enabled = readySignal.isReady,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Text(
                    if (readySignal.isReady) "프롬프트 붙여넣기" else "붙여넣기(준비 중…)",
                    fontSize = 11.sp,
                    color = AppColors.NeonPurple,
                    maxLines = 1,
                )
            }
            TextButton(
                onClick = onEndSession,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Text("세션 종료", fontSize = 11.sp, color = AppColors.TextSecondary, maxLines = 1)
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
                        // publish before start() so a fast bracketed-paste signal can't beat it
                        widget = w
                        w.start()
                    } as Component
                }.getOrElse {
                    // Spec: JediTermWidget init failure → tear the session down
                    // rather than crash the popup composition. Return an inert
                    // component, not another widget that would throw identically.
                    onEndSession()
                    JPanel()
                }
            },
            update = { _ ->
                // size is driven by JediTermWidget's own component listener
            },
        )
    }
}
