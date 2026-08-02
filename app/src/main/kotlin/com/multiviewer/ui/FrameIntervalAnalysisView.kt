package com.multiviewer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val GRAPH_POINT_RADIUS_DP = 2.5f
private const val GRAPH_HIGHLIGHT_RADIUS_DP = 5f
private const val GRAPH_HIT_RADIUS_DP = 10f

// Pure UI: given already-computed intervals and the (optional) container fps, draws the scatter
// plot + data table with bidirectional click highlighting. Fetching/caching intervals and fps,
// and handling the loading/empty/error states, is the caller's job (see
// FrameIntervalAnalysisWindow) -- this composable assumes intervals is non-empty.
@Composable
fun FrameIntervalAnalysisView(intervals: List<FrameInterval>, fps: Double?, modifier: Modifier = Modifier) {
    var selectedFrameIndex by remember(intervals) { mutableStateOf<Int?>(null) }

    // AppColors.* getters are @Composable (theme-reactive) and can only be read here, in the
    // composable body -- NOT from inside Canvas's onDraw lambda below, which runs during the draw
    // phase rather than composition. Resolving them to plain Color vals here lets the draw lambda
    // close over the values instead (same reason GopAnalysisView keeps colorForFrameType outside
    // its Canvas-less bar Boxes, and AudioWaveformPeaks/AudioMinimap take color as a parameter).
    val colorI = AppColors.FrameTypeI
    val colorP = AppColors.FrameTypeP
    val colorB = AppColors.FrameTypeB
    val colorDefault = AppColors.TextSecondary
    val selectionRowColor = AppColors.Selection
    val textPrimary = AppColors.TextPrimary
    val textSecondary = AppColors.TextSecondary

    val minFrameIndex = intervals.first().frameIndex
    val maxFrameIndex = intervals.last().frameIndex
    val minIntervalMs = intervals.minOf { it.intervalMs }
    val maxIntervalMs = intervals.maxOf { it.intervalMs }
    val expectedIntervalMs = fps?.takeIf { it > 0.0 }?.let { 1000.0 / it }
    val frameSpan = (maxFrameIndex - minFrameIndex).coerceAtLeast(1)
    // A perfectly regular video (the common, healthy case) has minIntervalMs == maxIntervalMs --
    // naively dividing by a near-zero span would pin every point to the very bottom of the graph
    // instead of the vertical center, which would look alarming/wrong for exactly the case that
    // should look the most reassuring. yFraction returns 0.5 (dead center) when there's no
    // variance to show, and the true proportional fraction otherwise.
    val hasIntervalVariance = maxIntervalMs > minIntervalMs
    fun yFraction(value: Double): Float =
        if (hasIntervalVariance) ((value - minIntervalMs) / (maxIntervalMs - minIntervalMs)).toFloat() else 0.5f

    Column(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(8.dp)
                .pointerInput(intervals) {
                    detectTapGestures { offset ->
                        val widthPx = size.width.toFloat()
                        val heightPx = size.height.toFloat()
                        val hitRadiusPx = GRAPH_HIT_RADIUS_DP.dp.toPx()
                        var nearest: FrameInterval? = null
                        var nearestDistanceSq = Float.MAX_VALUE
                        for (interval in intervals) {
                            val x = widthPx * (interval.frameIndex - minFrameIndex).toFloat() / frameSpan
                            val y = heightPx - heightPx * yFraction(interval.intervalMs)
                            val dx = offset.x - x
                            val dy = offset.y - y
                            val distanceSq = dx * dx + dy * dy
                            if (distanceSq < nearestDistanceSq) {
                                nearestDistanceSq = distanceSq
                                nearest = interval
                            }
                        }
                        if (nearest != null && nearestDistanceSq <= hitRadiusPx * hitRadiusPx) {
                            selectedFrameIndex = nearest.frameIndex
                        }
                    }
                },
        ) {
            if (expectedIntervalMs != null && expectedIntervalMs in minIntervalMs..maxIntervalMs) {
                val referenceY = size.height - size.height * yFraction(expectedIntervalMs)
                drawLine(
                    color = Color.White.copy(alpha = 0.3f),
                    start = Offset(0f, referenceY),
                    end = Offset(size.width, referenceY),
                    strokeWidth = 1f,
                )
            }

            val pointRadiusPx = GRAPH_POINT_RADIUS_DP.dp.toPx()
            val highlightRadiusPx = GRAPH_HIGHLIGHT_RADIUS_DP.dp.toPx()
            for (interval in intervals) {
                val x = size.width * (interval.frameIndex - minFrameIndex).toFloat() / frameSpan
                val y = size.height - size.height * yFraction(interval.intervalMs)
                val color = when (interval.type) {
                    'I' -> colorI
                    'P' -> colorP
                    'B' -> colorB
                    else -> colorDefault
                }
                if (interval.frameIndex == selectedFrameIndex) {
                    drawCircle(color = Color.White, radius = highlightRadiusPx, center = Offset(x, y), style = Stroke(width = 2f))
                }
                drawCircle(color = color, radius = pointRadiusPx, center = Offset(x, y))
            }
        }

        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text("프레임 번호", modifier = Modifier.width(100.dp), style = AppTypography.labelLarge.copy(fontSize = 11.sp, color = textSecondary))
            Text("타임스탬프(s)", modifier = Modifier.width(120.dp), style = AppTypography.labelLarge.copy(fontSize = 11.sp, color = textSecondary))
            Text("간격(ms)", modifier = Modifier.width(100.dp), style = AppTypography.labelLarge.copy(fontSize = 11.sp, color = textSecondary))
            Text("간격 diff(ms)", modifier = Modifier.width(120.dp), style = AppTypography.labelLarge.copy(fontSize = 11.sp, color = textSecondary))
        }

        val listState = rememberLazyListState()
        LaunchedEffect(selectedFrameIndex) {
            val index = selectedFrameIndex ?: return@LaunchedEffect
            val position = intervals.indexOfFirst { it.frameIndex == index }
            if (position < 0) return@LaunchedEffect
            val isVisible = listState.layoutInfo.visibleItemsInfo.any { it.index == position }
            if (!isVisible) listState.animateScrollToItem(position)
        }

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(intervals) { _, interval ->
                    val isSelected = interval.frameIndex == selectedFrameIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (isSelected) selectionRowColor else Color.Transparent)
                            .clickable { selectedFrameIndex = interval.frameIndex }
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text("${interval.frameIndex}", modifier = Modifier.width(100.dp), style = AppTypography.bodyLarge.copy(fontSize = 11.sp, color = textPrimary))
                        Text("%.3f".format(interval.ptsSeconds), modifier = Modifier.width(120.dp), style = AppTypography.bodyLarge.copy(fontSize = 11.sp, color = textPrimary))
                        Text("%.1f".format(interval.intervalMs), modifier = Modifier.width(100.dp), style = AppTypography.bodyLarge.copy(fontSize = 11.sp, color = textPrimary))
                        Text("%.1f".format(interval.intervalDiffMs), modifier = Modifier.width(120.dp), style = AppTypography.bodyLarge.copy(fontSize = 11.sp, color = textPrimary))
                    }
                }
            }
            VerticalScrollbar(adapter = rememberScrollbarAdapter(listState), modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

// Owns data-fetching: reuses the same tab.gopFrames/AppState.analyzeFrames the GOP panel already
// populates (no duplicate ffprobe call if the user already opened GOP analysis for this tab), plus
// a fresh probeVideo call for fps (not cached anywhere else on TabState). Opens an independent,
// resizable Window rather than a modal Dialog since the data table can be long.
@Composable
fun FrameIntervalAnalysisWindow(appState: AppState, tab: TabState, onCloseRequest: () -> Unit) {
    LaunchedEffect(tab) {
        appState.analyzeFrames(tab)
    }
    var videoInfo by remember(tab) { mutableStateOf<VideoInfo?>(null) }
    LaunchedEffect(tab) {
        videoInfo = withContext(Dispatchers.IO) { probeVideo(tab.file) }
    }

    Window(onCloseRequest = onCloseRequest, title = "프레임 간격 분석 - ${tab.file.name}") {
        val frames = tab.gopFrames
        val intervals = remember(frames) { frames?.let { computeFrameIntervals(it) } ?: emptyList() }

        Box(modifier = Modifier.fillMaxSize().background(AppColors.Background)) {
            when {
                tab.isAnalyzingFrames || frames == null -> {
                    DecodingIndicator("프레임 분석 중...", modifier = Modifier.align(Alignment.Center))
                }
                intervals.isEmpty() -> {
                    Text(
                        "간격 정보 없음",
                        modifier = Modifier.align(Alignment.Center),
                        style = AppTypography.bodyLarge.copy(color = AppColors.TextSecondary),
                    )
                }
                else -> {
                    FrameIntervalAnalysisView(intervals = intervals, fps = videoInfo?.fps, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}
