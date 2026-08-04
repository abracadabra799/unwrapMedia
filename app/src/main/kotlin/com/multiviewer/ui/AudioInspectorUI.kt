package com.multiviewer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

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
    DashboardLayout(
        leftPanel = leftPanel,
        centerPanel = {
            // The analysis summary that used to share this column (split via a DraggableDivider)
            // moved to DetailedPropertiesPanel's Overview tab, so the player now fills the whole
            // center panel.
            Column(modifier = Modifier.fillMaxSize()) {
                FfmpegAudioPlayer(tab.file, rawAudioParams = tab.rawAudioParams, modifier = Modifier.fillMaxSize())
            }
        },
        rightPanel = {
            DetailedPropertiesPanel(appState, tab)
        },
        bottomPanel = bottomPanel,
    )
}
