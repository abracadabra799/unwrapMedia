package com.multiviewer.ui.terminal

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.jediterm.terminal.model.hyperlinks.HyperlinkFilter
import com.jediterm.terminal.model.hyperlinks.LinkInfo
import com.jediterm.terminal.model.hyperlinks.LinkResult
import com.jediterm.terminal.model.hyperlinks.LinkResultItem
import com.jediterm.terminal.ui.JediTermWidget
import com.jediterm.terminal.ui.TerminalPanel
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider
import com.jediterm.terminal.ui.settings.SettingsProvider
import com.multiviewer.ui.AppColors
import com.multiviewer.util.AiCliDetector
import com.multiviewer.util.PtyCliCommand
import kotlinx.coroutines.delay
import java.awt.Component
import java.awt.Font
import java.awt.GraphicsEnvironment
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
    // Follows the running program's current bracketed-paste mode. A readline/Ink
    // CLI turns it on at its prompt and off on exit, so this is true while the
    // running program has bracketed-paste mode on. A CLI that exits via a full
    // terminal reset can briefly leave this stale-true — Ctrl+V paste is
    // unaffected either way. Ink apps don't toggle it on redraw, so no flicker.
    var isReady by mutableStateOf(false)
        private set

    fun onBracketedPasteMode(enabled: Boolean) { isReady = enabled }
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

internal const val CLI_TERMINAL_FONT_SIZE: Int = 14

/**
 * JediTerm's [TerminalPanel.getFontToDisplay] does no glyph-coverage fallback, so
 * a font that can't draw Hangul (Consolas) renders every Korean character — which
 * the AI CLIs and the diagnostic prompt are full of — as tofu boxes.
 *
 * The old list also preferred `GulimChe` / `DotumChe` / `MS Gothic` / `NSimSun`,
 * which DO cover Hangul but render as crunchy hinted bitmaps at terminal sizes —
 * users found them hard to read. Now: only genuinely crisp Korean *coding* fonts
 * as a preference, then straight to the logical `Monospaced` composite — the same
 * face Compose's `FontFamily.Monospace` resolves to (so the terminal matches the
 * prompt view beside it), and its JRE CJK fallback renders Hangul cleanly.
 */
internal fun pickCliTerminalFont(size: Int = CLI_TERMINAL_FONT_SIZE): Font {
    // 가 = Hangul syllable, ─ = box drawing, A = Latin.
    val sample = "가─A"
    val installed = runCatching {
        GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toHashSet()
    }.getOrDefault(hashSetOf())
    val preferred = listOf("D2Coding", "NanumGothicCoding", "Nanum Gothic Coding")
    for (name in preferred) {
        if (name in installed) {
            val f = Font(name, Font.PLAIN, size)
            if (f.canDisplayUpTo(sample) == -1) return f
        }
    }
    return Font(Font.MONOSPACED, Font.PLAIN, size)
}

/** Any `http(s)://…` printed by the CLI (e.g. Claude's login URL) becomes clickable. */
internal class UrlHyperlinkFilter : HyperlinkFilter {
    private val urlRegex = Regex("""https?://[^\s"'<>()\[\]{}]+""")

    override fun apply(line: String?): LinkResult? {
        if (line.isNullOrEmpty()) return null
        val items = urlRegex.findAll(line).mapNotNull { m ->
            val url = m.value.trimEnd('.', ',', ';', ':', '!', '?')
            if (url.length < 8) return@mapNotNull null
            LinkResultItem(m.range.first, m.range.first + url.length, LinkInfo { openInBrowser(url) })
        }.toList()
        return if (items.isEmpty()) null else LinkResult(items)
    }

    private fun openInBrowser(url: String) {
        // openWebAi tries Chrome first (where the user's company account is signed
        // in), then falls back to Desktop.browse (the system default browser).
        runCatching { AiCliDetector.openWebAi(url) }
    }
}

internal class CliTerminalSettings(val readySignal: BracketedPasteSignal) : DefaultSettingsProvider() {
    private val terminalFont = pickCliTerminalFont()
    override fun getTerminalFont(): Font = terminalFont
    override fun getTerminalFontSize(): Float = CLI_TERMINAL_FONT_SIZE.toFloat()
    override fun audibleBell(): Boolean = false
    // Claude / Codex enable mouse reporting; without this a click on the login
    // URL would be swallowed by the CLI instead of following the hyperlink.
    override fun forceActionOnMouseReporting(): Boolean = true

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
 * The AI prompt popup's live VT100 terminal — a plain PowerShell docked to the
 * right of the prompt view. The diagnostic prompt is NOT auto-injected — it is copied
 * to the clipboard when the session starts, and the user pastes it (the
 * "프롬프트 붙여넣기" button, or the terminal's own Ctrl/Cmd+V) once they have
 * finished any browser login and are at the CLI's own prompt. Auto-injecting hit
 * Claude Code / Codex mid-login (they enable bracketed-paste on their menu
 * screens) and corrupted the flow.
 */
@Composable
internal fun EmbeddedTerminalPanel(
    session: WindowsShellSession,
    onEndSession: () -> Unit,
    onRestart: () -> Unit,
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

    // Chip click: type "<cmd>" and submit it at the shell. `true` = user typing.
    fun sendCommand(cmd: String) {
        if (state != SessionState.Running) return
        val starter = widget?.terminalStarter ?: return
        starter.sendString("$cmd\r", true)
    }

    LaunchedEffect(widget) {
        // requestFocusInWindow() is a no-op until the peer is realized.
        if (widget != null) {
            delay(120)
            widget?.requestFocusInWindow()
        }
    }
    DisposableEffect(session) {
        // close() on a widget that failed mid-start() can throw; disposal must not
        // propagate an exception into Compose.
        onDispose { runCatching { widget?.close() } }
    }

    val ended = state is SessionState.Exited || state is SessionState.Failed

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("● PowerShell", fontSize = 11.sp, color = AppColors.NeonPurple, maxLines = 1)
            Spacer(Modifier.width(8.dp))
            Text(
                when (val s = state) {
                    SessionState.Starting -> "기동 중…"
                    SessionState.Running -> "실행 중"
                    is SessionState.Exited -> "종료됨 (exit ${s.code})"
                    is SessionState.Failed -> "실패: ${s.reason}"
                },
                fontSize = 11.sp,
                color = if (ended) AppColors.NeonOrange else AppColors.TextSecondary,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(4.dp))
            if (ended) {
                TextButton(
                    onClick = onRestart,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text("↻ PowerShell 다시 시작", fontSize = 11.sp, color = AppColors.NeonGreen, maxLines = 1)
                }
            } else {
                TextButton(
                    onClick = { pastePrompt() },
                    enabled = readySignal.isReady,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text(
                        if (readySignal.isReady) "프롬프트 붙여넣기" else "붙여넣기(CLI 진입 후)",
                        fontSize = 11.sp,
                        color = AppColors.NeonPurple,
                        maxLines = 1,
                    )
                }
            }
            TextButton(
                onClick = onEndSession,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Text(if (ended) "닫기" else "세션 종료", fontSize = 11.sp, color = AppColors.TextSecondary, maxLines = 1)
            }
        }

        if (state == SessionState.Running && !readySignal.isReady) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 6.dp).padding(bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                com.multiviewer.util.AiCliType.entries.forEach { cli ->
                    TextButton(
                        onClick = { sendCommand(cli.command) },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        modifier = Modifier.height(22.dp),
                    ) {
                        Text(cli.displayName, fontSize = 10.sp, color = AppColors.NeonBlue, maxLines = 1)
                    }
                }
                Text(
                    "셸에서 실행 — 또는 직접 입력",
                    fontSize = 10.sp,
                    color = AppColors.TextSecondary,
                    maxLines = 1,
                )
            }
        }

        val guidance: String? = when (val s = state) {
            SessionState.Starting -> "PowerShell을 여는 중입니다…"
            SessionState.Running ->
                "① 위 칩을 누르거나 직접 명령을 입력해 AI CLI를 실행하세요. " +
                    "② 로그인이 필요하면 진행하세요 (브라우저가 안 열리면 출력된 URL 클릭). " +
                    "③ CLI 프롬프트에서 '프롬프트 붙여넣기'(또는 Ctrl+V) 후 Enter."
            is SessionState.Exited ->
                "PowerShell이 종료되었습니다 (exit ${s.code}). " +
                    "'↻ PowerShell 다시 시작'을 누르면 새 셸이 열리고 프롬프트가 다시 클립보드에 복사됩니다."
            is SessionState.Failed ->
                "PowerShell을 시작하지 못했습니다: ${s.reason}. '↻ PowerShell 다시 시작'으로 재시도하세요."
        }
        if (guidance != null) {
            Text(
                guidance,
                fontSize = 10.sp,
                color = if (ended) AppColors.NeonOrange else AppColors.TextSecondary,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp).padding(bottom = 4.dp),
            )
        }
        SwingPanel(
            background = Color(0xFF13161A),
            modifier = Modifier.fillMaxWidth().weight(1f),
            // factory runs once per mount. A restart changes the session
            // instance; the call site wraps this panel in key(session) so the
            // whole composable remounts and the widget is recreated with the new
            // connector. Do not rely on recomposition to rebind the connector.
            factory = {
                runCatching {
                    ReadyAwareJediTermWidget(120, 30, CliTerminalSettings(readySignal)).also { w ->
                        w.ttyConnector = session.ttyConnector
                        // Claude's browser-open frequently fails on Windows ("browser
                        // didn't open? use the url below") — make the printed login URL
                        // clickable so the user can open it from here.
                        w.addHyperlinkFilter(UrlHyperlinkFilter())
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
