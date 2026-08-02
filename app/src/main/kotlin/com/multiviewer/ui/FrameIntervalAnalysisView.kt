package com.multiviewer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val GRAPH_POINT_RADIUS_DP = 2.5f
private const val GRAPH_HIGHLIGHT_RADIUS_DP = 5f
private const val GRAPH_HIT_RADIUS_DP = 10f
private const val Y_AXIS_TICK_COUNT = 5
private const val X_AXIS_TICK_STEP_FRAMES = 5

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
    // 20% headroom below the min and above the max interval currently on screen -- scales to the
    // ACTUAL data range (unlike a fixed ceiling) while keeping points off the very top/bottom
    // edge, so a file with only 2-3 distinct interval values (e.g. a variable-frame-rate video
    // alternating between two frame durations) doesn't look like it snapped onto flat lines at
    // the boundary. This also naturally handles a perfectly regular video (min == max): the 20%
    // padding still produces a valid nonzero range around that single value.
    val axisMinMs = minIntervalMs * 0.8
    val rawAxisMaxMs = maxIntervalMs * 1.2
    val axisMaxMs = if (rawAxisMaxMs > axisMinMs) rawAxisMaxMs else axisMinMs + 1.0
    val tickValuesMs = (0 until Y_AXIS_TICK_COUNT).map { axisMinMs + (axisMaxMs - axisMinMs) * it / (Y_AXIS_TICK_COUNT - 1) }
    fun yFraction(value: Double): Float = ((value - axisMinMs) / (axisMaxMs - axisMinMs)).toFloat().coerceIn(0f, 1f)
    // Every X_AXIS_TICK_STEP_FRAMES-th frame number, starting from the first multiple at or after
    // minFrameIndex -- ((minFrameIndex + step - 1) / step) * step is the standard ceiling-to-a-
    // multiple trick for non-negative ints (frame indices are always >= 1 here).
    val frameTicks = remember(minFrameIndex, maxFrameIndex) {
        val firstTick = ((minFrameIndex + X_AXIS_TICK_STEP_FRAMES - 1) / X_AXIS_TICK_STEP_FRAMES) * X_AXIS_TICK_STEP_FRAMES
        generateSequence(firstTick) { it + X_AXIS_TICK_STEP_FRAMES }.takeWhile { it <= maxFrameIndex }.toList()
    }
    fun xFraction(frameIndex: Int): Float = (frameIndex - minFrameIndex).toFloat() / frameSpan

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // Moves the highlighted point one position left/right through intervals (list order, not raw
    // frameIndex arithmetic, since frameIndex can have gaps) -- same selectedFrameIndex state the
    // click handlers already use, so arrow-key and click selection stay in sync automatically.
    // indexOfFirst returns -1 when nothing is selected yet, so -1 + either delta clamps to
    // position 0 -- the first arrow press (either direction) selects the first point.
    fun stepSelection(delta: Int) {
        val currentPosition = intervals.indexOfFirst { it.frameIndex == selectedFrameIndex }
        val targetPosition = (currentPosition + delta).coerceIn(0, intervals.size - 1)
        selectedFrameIndex = intervals[targetPosition].frameIndex
    }

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(8.dp)
                .border(1.dp, AppColors.Border)
                .background(AppColors.Panel)
                .focusRequester(focusRequester)
                .focusable()
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft -> { stepSelection(-1); true }
                        Key.DirectionRight -> { stepSelection(1); true }
                        else -> false
                    }
                },
        ) {
            // BoxWithConstraints (not plain Box) so the tick labels below can be positioned by
            // maxHeight * fraction -- same technique AudioMinimap.kt already uses for its
            // zoom-window rectangle (Modifier.offset(x = maxWidth * fraction)), just vertical here.
            // The vertical padding reserves room for the topmost/bottommost Y-axis labels' own
            // height so they stay inside the bordered box instead of spilling past its edge.
            BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(vertical = 10.dp)) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
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
                    for (tickMs in tickValuesMs) {
                        val tickY = size.height - size.height * yFraction(tickMs)
                        drawLine(
                            color = Color.White.copy(alpha = 0.12f),
                            start = Offset(0f, tickY),
                            end = Offset(size.width, tickY),
                            strokeWidth = 1f,
                        )
                    }
                    for (frameTick in frameTicks) {
                        val tickX = size.width * xFraction(frameTick)
                        drawLine(
                            color = Color.White.copy(alpha = 0.12f),
                            start = Offset(tickX, 0f),
                            end = Offset(tickX, size.height),
                            strokeWidth = 1f,
                        )
                    }

                    if (expectedIntervalMs != null && expectedIntervalMs in axisMinMs..axisMaxMs) {
                        val referenceY = size.height - size.height * yFraction(expectedIntervalMs)
                        drawLine(
                            color = Color.White.copy(alpha = 0.5f),
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

                for (tickMs in tickValuesMs) {
                    val fractionFromTop = 1f - yFraction(tickMs)
                    Text(
                        "%.1f".format(tickMs),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .offset(y = maxHeight * fractionFromTop - 7.dp)
                            .padding(start = 4.dp),
                        style = AppTypography.labelLarge.copy(fontSize = 9.sp, color = textSecondary),
                    )
                }
                if (expectedIntervalMs != null) {
                    Text(
                        "기준 ${"%.1f".format(expectedIntervalMs)}ms",
                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                        style = AppTypography.labelLarge.copy(fontSize = 10.sp, color = textSecondary),
                    )
                }
            }
        }

        BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(16.dp)) {
            for (frameTick in frameTicks) {
                Text(
                    "$frameTick",
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(x = maxWidth * xFraction(frameTick) - 8.dp),
                    style = AppTypography.labelLarge.copy(fontSize = 9.sp, color = textSecondary),
                )
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
