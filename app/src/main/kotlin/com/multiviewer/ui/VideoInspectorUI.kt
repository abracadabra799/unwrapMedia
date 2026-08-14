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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun VideoInspectorUI(
    appState: AppState,
    tab: TabState,
    leftPanel: @Composable ColumnScope.() -> Unit,
    bottomPanel: @Composable ColumnScope.() -> Unit
) {
    // Gates whether the codec-view panel (motion vectors / QP heatmap; Main.kt's bottomPanel) is
    // offered at all -- only H.264 is known to export the side data codecview needs (see
    // codecViewSupportedFor).
    LaunchedEffect(tab.file) {
        tab.videoCodecName = withContext(Dispatchers.IO) { probeVideoCodecName(tab.file) }
    }

    var topContainerWidthPx by remember { mutableStateOf(0) }
    // Player and GOP sit side-by-side (not stacked) so the player keeps the full center-panel
    // height instead of sharing it vertically with GOP -- videoGopSplit divides that region
    // horizontally, into player width vs GOP width. The analysis summary that used to share this
    // column (split via a second, vertical DraggableDivider) moved to DetailedPropertiesPanel's
    // Overview tab, so this row now fills the whole center panel.
    var videoGopSplit by remember { mutableStateOf(0.35f) }
    // GOP timeline gets more of the column by default (0.7) -- the filmstrip below it is still
    // useful at a smaller share, and this is user-draggable wider anytime via the divider between
    // them.
    var gopFilmstripSplit by remember { mutableStateOf(0.7f) }

    DashboardLayout(
        leftPanel = leftPanel,
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

                    // Right: GOP Analysis (top) + frame thumbnail filmstrip (bottom), independently
                    // resizable via gopFilmstripSplit -- same DraggableDivider pattern videoGopSplit
                    // already establishes for the player/GOP split above. The codec-view preview
                    // (CodecViewPreview.kt -- motion vectors / QP heatmap) is NOT shown here -- it
                    // renders beside the Hex & Raw Data Viewer instead (see Main.kt's bottomPanel),
                    // reusing the empty space to the right of the hex byte grid rather than
                    // shrinking this already vertically-limited column further.
                    var gopColumnHeightPx by remember { mutableStateOf(0) }
                    val hasFilmstripFrames = tab.gopFrames?.isNotEmpty() == true
                    Column(
                        modifier = Modifier
                            .weight(1f - videoGopSplit)
                            .fillMaxHeight()
                            .onGloballyPositioned { gopColumnHeightPx = it.size.height },
                    ) {
                        GopAnalysisView(
                            tab,
                            onAnalyze = { appState.analyzeFrames(tab) },
                            modifier = Modifier.weight(if (hasFilmstripFrames) gopFilmstripSplit else 1f).fillMaxWidth(),
                        )
                        if (hasFilmstripFrames) {
                            DraggableDivider(
                                orientation = Orientation.Horizontal,
                                containerSizePx = gopColumnHeightPx,
                                getSplit = { gopFilmstripSplit },
                                setSplit = { gopFilmstripSplit = it },
                            )
                            tab.gopFrames?.let { frames ->
                                FrameThumbnailFilmstrip(
                                    tab, frames,
                                    modifier = Modifier.weight(1f - gopFilmstripSplit).fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
        },
        rightPanel = {
            DetailedPropertiesPanel(appState, tab)
        },
        bottomPanel = bottomPanel
    )
}
