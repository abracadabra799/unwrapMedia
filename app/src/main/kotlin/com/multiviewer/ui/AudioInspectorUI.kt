package com.multiviewer.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp

// Structural parsing for audio files -- currently just M4A, an MP4-family container (same
// ftyp/moov/trak layout as mp4/mov/m4v) parsed by the same generic box walker, with
// MediaSummaryBuilder's existing detectCategory/buildVideoSummary/buildAudioDetail already
// handling a video-less "soun"-only moov correctly. Playback is FfmpegAudioPlayer -- ffmpeg PCM
// piped to a javax.sound.sampled SourceDataLine, plus a waveform (real PCM min/max peaks drawn via
// Compose Canvas, see AudioWaveformPeaks.kt) and a spectrogram (ffmpeg's showspectrumpic filter,
// regenerated at the panel's actual size after a resize settles).
@Composable
fun AudioInspectorUI(
    appState: AppState,
    tab: TabState,
    leftPanel: @Composable ColumnScope.() -> Unit,
    bottomPanel: @Composable ColumnScope.() -> Unit,
) {
    val summary = tab.mediaSummary
    var containerHeightPx by remember(tab) { mutableStateOf(0) }
    var verticalSplit by remember(tab) { mutableStateOf(0.5f) }

    DashboardLayout(
        leftPanel = leftPanel,
        centerPanel = {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { containerHeightPx = it.size.height },
            ) {
                FfmpegAudioPlayer(tab.file, rawAudioParams = tab.rawAudioParams, modifier = Modifier.weight(verticalSplit).fillMaxWidth())

                DraggableDivider(
                    orientation = Orientation.Horizontal,
                    containerSizePx = containerHeightPx,
                    getSplit = { verticalSplit },
                    setSplit = { verticalSplit = it },
                )

                val scrollState = remember(tab) { androidx.compose.foundation.lazy.LazyListState() }
                Box(modifier = Modifier.weight(1f - verticalSplit).fillMaxWidth()) {
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
            }
        },
        rightPanel = {
            DetailedPropertiesPanel(tab)
        },
        bottomPanel = bottomPanel,
    )
}
