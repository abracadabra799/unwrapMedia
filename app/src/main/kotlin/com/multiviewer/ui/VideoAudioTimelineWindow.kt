package com.multiviewer.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState

/** Large A/V timeline pinned to the tab that opened it. */
@Composable
fun VideoAudioTimelineWindow(
    appState: AppState,
    tab: TabState,
    language: AppLanguage,
    onCloseRequest: () -> Unit,
) {
    LaunchedEffect(tab) {
        if (tab.gopFrames == null && !tab.isAnalyzingFrames) appState.analyzeFrames(tab)
    }

    Window(
        onCloseRequest = onCloseRequest,
        title = "${if (language == AppLanguage.KO) "A/V 타임라인" else "A/V Timeline"} - ${tab.file.name}",
        state = rememberWindowState(size = DpSize(1200.dp, 340.dp), position = WindowPosition(Alignment.Center)),
    ) {
        VideoAudioTimelineView(
            tab = tab,
            frames = tab.gopFrames.orEmpty(),
            currentVideoPts = tab.currentVideoPtsSeconds,
            language = language,
            expanded = true,
            onToggleExpanded = null,
            onTogglePlayback = { tab.videoPlayPauseRequestTick++ },
            focusOnOpen = true,
            onSelectFrame = { frame ->
                tab.selectedFrame = frame
                tab.selected = null
                tab.seekTargetSeconds = frame.ptsSeconds
                tab.seekRequestTick++
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
