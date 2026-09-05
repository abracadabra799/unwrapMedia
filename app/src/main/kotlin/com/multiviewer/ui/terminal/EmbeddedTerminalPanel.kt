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
import com.jediterm.terminal.ui.JediTermWidget
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider
import com.multiviewer.ui.AppColors
import kotlinx.coroutines.delay
import java.awt.Font

const val PROMPT_INJECT_DELAY_MS: Long = 1400

private class CliTerminalSettings : DefaultSettingsProvider() {
    override fun getTerminalFont(): Font = Font(Font.MONOSPACED, Font.PLAIN, 13)
    override fun getTerminalFontSize(): Float = 13f
    override fun audibleBell(): Boolean = false
}

/**
 * Bottom panel of the AI prompt popup: a live VT100 terminal running the CLI.
 * Auto-injects the diagnostic prompt once, [PROMPT_INJECT_DELAY_MS] after mount.
 */
@Composable
fun EmbeddedTerminalPanel(
    session: WindowsPtyCliSession,
    onEndSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = session.state
    var widget by remember(session) { mutableStateOf<JediTermWidget?>(null) }

    LaunchedEffect(session) {
        delay(PROMPT_INJECT_DELAY_MS)
        session.injectPrompt()
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
            TextButton(onClick = { session.injectPrompt() }) {
                Text("프롬프트 재주입", fontSize = 11.sp, color = AppColors.NeonPurple)
            }
            TextButton(onClick = onEndSession) {
                Text("세션 종료", fontSize = 11.sp, color = AppColors.TextSecondary)
            }
        }
        SwingPanel(
            background = Color(0xFF13161A),
            modifier = Modifier.fillMaxWidth().weight(1f),
            // factory runs once; a CLI switch fully unmounts this composable
            // (Task 5 sets activeCliSession = null before starting the next), so
            // the widget is always recreated with the correct session connector.
            factory = {
                JediTermWidget(120, 30, CliTerminalSettings()).also { w ->
                    w.ttyConnector = session.ttyConnector
                    w.start()
                    widget = w
                }
            },
            update = { _ ->
                // size is driven by JediTermWidget's own component listener
            },
        )
    }
}
