package com.multiviewer.ui.ai

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.jediterm.terminal.ui.JediTermWidget
import com.multiviewer.ui.AppColors
import com.multiviewer.ui.AppLanguage
import com.multiviewer.ui.AppTheme
import com.multiviewer.ui.ThemeMode
import com.multiviewer.ui.terminal.BracketedPasteSignal
import com.multiviewer.ui.terminal.CliTerminalSettings
import com.multiviewer.ui.terminal.UrlHyperlinkFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JPanel

/** Interactive CLI window. Authentication stays entirely inside the provider's own CLI/browser. */
@Composable
fun AiLoginWindow(
    provider: AiProviderId,
    executable: File,
    language: AppLanguage,
    themeMode: ThemeMode,
    onCloseRequest: () -> Unit,
) {
    val session = remember(executable) { AiLoginSession(executable) }
    var widget by remember(session) { mutableStateOf<JediTermWidget?>(null) }
    val ko = language == AppLanguage.KO
    LaunchedEffect(session) { withContext(Dispatchers.IO) { session.start() } }
    DisposableEffect(session) {
        onDispose {
            runCatching { widget?.close() }
            session.destroy()
        }
    }

    Window(
        onCloseRequest = onCloseRequest,
        title = if (ko) "${provider.displayName} 계정 연결" else "Connect ${provider.displayName}",
        state = rememberWindowState(size = DpSize(760.dp, 550.dp)),
    ) {
        AppTheme(mode = themeMode, showPixelGrid = false) {
            Column(Modifier.fillMaxSize()) {
                Text(
                    if (ko) "${provider.displayName}에서 로그인을 완료한 뒤 이 창을 닫고 질문을 다시 시도하세요. 브라우저가 열리지 않으면 아래 URL을 클릭하세요."
                    else "Sign in to ${provider.displayName}, then close this window and retry your question. If the browser does not open, click the URL below.",
                    color = AppColors.TextSecondary, fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                )
                when (val state = session.state) {
                    AiLoginState.Starting -> Text(if (ko) "CLI 시작 중…" else "Starting CLI…", modifier = Modifier.padding(10.dp))
                    is AiLoginState.Failed -> Text(
                        (if (ko) "CLI 시작 실패: " else "Could not start CLI: ") + state.reason,
                        color = AppColors.NeonOrange, modifier = Modifier.padding(10.dp),
                    )
                    else -> {
                        if (state is AiLoginState.Exited) Text(
                            (if (ko) "CLI 종료 코드: " else "CLI exit code: ") + state.code,
                            color = AppColors.NeonOrange, modifier = Modifier.padding(10.dp),
                        )
                        SwingPanel(
                            background = Color(0xFF13161A),
                            modifier = Modifier.fillMaxSize(),
                            factory = {
                                runCatching {
                                    JediTermWidget(100, 30, CliTerminalSettings(BracketedPasteSignal())).also { w ->
                                        w.ttyConnector = session.ttyConnector
                                        w.addHyperlinkFilter(UrlHyperlinkFilter())
                                        widget = w
                                        w.start()
                                    }
                                }.getOrElse { e ->
                                    session.fail(e.message ?: e.javaClass.simpleName)
                                    JPanel()
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
