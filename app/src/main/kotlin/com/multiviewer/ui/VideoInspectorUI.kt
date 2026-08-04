package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun VideoInspectorUI(
    appState: AppState,
    tab: TabState,
    leftPanel: @Composable ColumnScope.() -> Unit,
    bottomPanel: @Composable ColumnScope.() -> Unit
) {
    var topContainerWidthPx by remember { mutableStateOf(0) }
    // Player and GOP sit side-by-side (not stacked) so the player keeps the full center-panel
    // height instead of sharing it vertically with GOP -- videoGopSplit divides that region
    // horizontally, into player width vs GOP width. The analysis summary that used to share this
    // column (split via a second, vertical DraggableDivider) moved to DetailedPropertiesPanel's
    // Overview tab, so this row now fills the whole center panel.
    var videoGopSplit by remember { mutableStateOf(0.35f) }

    DashboardLayout(
        leftPanel = leftPanel,
        rightPanelDefaultWidthDp = 298f,
        centerPanel = {
            Column(modifier = Modifier.fillMaxSize()) {
                tab.largeResolutionWarning?.let { warning ->
                    ResolutionWarningBanner(warning, onDismiss = { tab.largeResolutionWarning = null })
                }
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .onGloballyPositioned { topContainerWidthPx = it.size.width }
                ) {
                    // Left: Live Player (full height of the top region)
                    Box(
                        modifier = Modifier
                            .weight(videoGopSplit)
                            .fillMaxHeight()
                            .background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        FfmpegVideoPlayer(
                            tab.file,
                            onElapsedChanged = { tab.playbackElapsedSeconds = it },
                            seekRequestSeconds = tab.seekTargetSeconds,
                            seekRequestTick = tab.seekRequestTick,
                            onProbeComplete = { tab.videoReadyForAnalysis = true },
                        )

                        Text("LIVE PLAYER",
                            modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
                            style = AppTypography.labelLarge.copy(fontSize = 10.sp, color = AppColors.NeonGreen)
                        )
                    }

                    DraggableDivider(
                        orientation = Orientation.Vertical,
                        containerSizePx = topContainerWidthPx,
                        getSplit = { videoGopSplit },
                        setSplit = { videoGopSplit = it }
                    )

                    // Right: GOP Analysis (full height of the top region)
                    GopAnalysisView(
                        tab,
                        onAnalyze = { appState.analyzeFrames(tab) },
                        modifier = Modifier.weight(1f - videoGopSplit).fillMaxHeight(),
                    )
                }
            }
        },
        rightPanel = {
            DetailedPropertiesPanel(appState, tab)
        },
        bottomPanel = bottomPanel
    )
}
