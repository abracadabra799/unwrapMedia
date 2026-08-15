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

    // Parses the video track's avcC box once per tab -- independent of tab.videoCodecName's own
    // probe above (this just checks whether an avcC box exists in the tree at all, the same gate
    // parseH264Sps/parseH264Pps's own callers rely on implicitly via an empty list otherwise).
    LaunchedEffect(tab.root) {
        val root = tab.root ?: return@LaunchedEffect
        val avcCNode = com.multiviewer.parser.findFirst(root) { it.type == "avcC" } ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val raw = com.multiviewer.parser.extractAvcCRawParameterSets(tab.file, avcCNode) ?: return@withContext
            tab.avcLengthSize = raw.lengthSize
            tab.avcSpsList = raw.spsList.mapNotNull { com.multiviewer.parser.parseH264Sps(it.bytes) }
            tab.avcPpsList = raw.ppsList.mapNotNull { com.multiviewer.parser.parseH264Pps(it.bytes) }
        }
    }

    // Parses the video track's hvcC box once per tab -- mirrors the avcC LaunchedEffect above.
    LaunchedEffect(tab.root) {
        val root = tab.root ?: return@LaunchedEffect
        val hvcCNode = com.multiviewer.parser.findFirst(root) { it.type == "hvcC" } ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val raw = com.multiviewer.parser.extractHvcCRawParameterSets(tab.file, hvcCNode) ?: return@withContext
            tab.hevcLengthSize = raw.lengthSize
            tab.hevcVpsList = raw.vpsList.mapNotNull { com.multiviewer.parser.parseHevcVps(it.bytes) }
            tab.hevcSpsList = raw.spsList.mapNotNull { com.multiviewer.parser.parseHevcSps(it.bytes) }
            tab.hevcPpsList = raw.ppsList.mapNotNull { com.multiviewer.parser.parseHevcPps(it.bytes) }
        }
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
