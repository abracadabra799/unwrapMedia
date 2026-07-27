package com.multiviewer.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// Minimal inspector for audio files whose container this app already understands structurally --
// currently just M4A, an MP4-family container (same ftyp/moov/trak layout as mp4/mov/m4v) parsed
// by the same generic box walker, with MediaSummaryBuilder's existing detectCategory/
// buildVideoSummary/buildAudioDetail already handling a video-less "soun"-only moov correctly.
// No player: this app has never had real audio output (FfmpegVideoPlayer always drops audio with
// -an) -- that's a separate subsystem, not something reusing the existing parser gets for free.
@Composable
fun AudioInspectorUI(
    appState: AppState,
    tab: TabState,
    leftPanel: @Composable ColumnScope.() -> Unit,
    bottomPanel: @Composable ColumnScope.() -> Unit,
) {
    val summary = tab.mediaSummary
    DashboardLayout(
        leftPanel = leftPanel,
        centerPanel = {
            val scrollState = remember(tab) { androidx.compose.foundation.lazy.LazyListState() }
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(state = scrollState, modifier = Modifier.fillMaxSize()) {
                    item {
                        if (summary != null) {
                            SummaryBox("🎵 오디오 분석 요약", summary.sections)
                        }
                    }
                    item { Spacer(Modifier.height(32.dp)) }
                }
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(scrollState),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                )
            }
        },
        rightPanel = {
            DetailedPropertiesPanel(tab)
        },
        bottomPanel = bottomPanel,
    )
}
