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
// piped to a javax.sound.sampled SourceDataLine, plus a GoldWave-style scrolling waveform (real
// PCM min/max peaks drawn via Compose Canvas, see AudioWaveformPeaks.kt).
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
                FfmpegAudioPlayer(
                    tab.file,
                    rawAudioParams = tab.rawAudioParams,
                    onOpenAudio = {
                        val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Open audio", java.awt.FileDialog.LOAD)
                        appState.lastOpenedDirectory?.let { if (it.exists() && it.isDirectory) dialog.directory = it.absolutePath }
                        dialog.isVisible = true
                        val dir = dialog.directory
                        val name = dialog.file
                        if (dir != null && name != null) {
                            // appState.openFile already updates lastOpenedDirectory (with an
                            // exists()/isDirectory guard this assignment skipped).
                            appState.openFile(java.io.File(dir, name))
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        },
        rightPanel = {
            DetailedPropertiesPanel(appState, tab)
        },
        bottomPanel = bottomPanel,
    )
}
