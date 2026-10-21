package com.multiviewer.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

// Starting/default frame-bar width -- also the initial value of the per-composable frameBarWidthDp
// zoom state below. FRAME_BAR_MIN/MAX bound how far mouse-wheel zoom can shrink/grow it; STEP is
// the change applied per wheel-scroll unit.
private const val FRAME_BAR_WIDTH_DP = 16
private const val FRAME_BAR_MIN_WIDTH_DP = 4f
private const val FRAME_BAR_MAX_WIDTH_DP = 48f
private const val FRAME_BAR_ZOOM_STEP_DP = 2f
private const val FRAME_BAR_SPACING_DP = 2
// The tallest bar (the single biggest frame in the video) would otherwise reach 100% of the
// panel's height, which reads as too dominant -- this caps it at 60%, scaling every other bar down
private const val FRAME_BAR_MAX_HEIGHT_FRACTION = 0.6f

data class GopSpan(
    val gopNumber: Int,
    val startFrameIndex: Int,
    val endFrameIndex: Int,
    val startPtsSeconds: Double,
    val endPtsSeconds: Double,
    val durationSeconds: Double,
    val totalBytes: Long,
    val iCount: Int,
    val pCount: Int,
    val bCount: Int,
) {
    val frameCount: Int get() = endFrameIndex - startFrameIndex + 1
}

internal fun computeGopSpans(frames: List<FrameInfo>): List<GopSpan> {
    if (frames.isEmpty()) return emptyList()
    val avgFrameDuration = if (frames.size >= 2) {
        ((frames.last().ptsSeconds - frames.first().ptsSeconds) / (frames.size - 1)).coerceIn(0.001, 1.0)
    } else {
        1.0 / 30.0
    }
    val gopStarts = ArrayList<Int>()
    for (i in frames.indices) {
        if (i == 0 || frames[i].type == 'I') {
            gopStarts.add(i)
        }
    }
    return gopStarts.mapIndexed { gopIdx, startIdx ->
        val endIdx = if (gopIdx + 1 < gopStarts.size) gopStarts[gopIdx + 1] - 1 else frames.lastIndex
        var totalBytes = 0L
        var iCount = 0
        var pCount = 0
        var bCount = 0
        var minPts = frames[startIdx].ptsSeconds
        var maxPts = frames[startIdx].ptsSeconds
        for (idx in startIdx..endIdx) {
            val f = frames[idx]
            totalBytes += f.sizeBytes.toLong()
            when (f.type) {
                'I' -> iCount++
                'P' -> pCount++
                'B' -> bCount++
            }
            if (f.ptsSeconds < minPts) minPts = f.ptsSeconds
            if (f.ptsSeconds > maxPts) maxPts = f.ptsSeconds
        }
        val nextStartPts = gopStarts.getOrNull(gopIdx + 1)?.let { frames[it].ptsSeconds }
        val durationSec = if (nextStartPts != null && nextStartPts > minPts) {
            (nextStartPts - minPts).coerceAtLeast(0.0)
        } else {
            (maxPts - minPts + avgFrameDuration).coerceAtLeast(avgFrameDuration)
        }
        GopSpan(
            gopNumber = gopIdx + 1,
            startFrameIndex = startIdx,
            endFrameIndex = endIdx,
            startPtsSeconds = minPts,
            endPtsSeconds = maxPts,
            durationSeconds = durationSec,
            totalBytes = totalBytes,
            iCount = iCount,
            pCount = pCount,
            bCount = bCount,
        )
    }
}

internal fun findGopSpanForFrame(spans: List<GopSpan>, frameIndex: Int): GopSpan? {
    if (spans.isEmpty() || frameIndex < 0) return null
    var low = 0
    var high = spans.lastIndex
    while (low <= high) {
        val mid = (low + high) ushr 1
        val span = spans[mid]
        when {
            frameIndex < span.startFrameIndex -> high = mid - 1
            frameIndex > span.endFrameIndex -> low = mid + 1
            else -> return span
        }
    }
    return null
}

internal fun findNearestFrameIndexByPts(frames: List<FrameInfo>, ptsSeconds: Double): Int? {
    if (frames.isEmpty() || !ptsSeconds.isFinite()) return null
    var low = 0
    var high = frames.lastIndex
    while (low <= high) {
        val mid = (low + high) ushr 1
        val cmp = frames[mid].ptsSeconds.compareTo(ptsSeconds)
        when {
            cmp < 0 -> low = mid + 1
            cmp > 0 -> high = mid - 1
            else -> return mid
        }
    }
    val before = (low - 1).takeIf { it in frames.indices }
    val after = low.takeIf { it in frames.indices }
    return listOfNotNull(before, after).minByOrNull { abs(frames[it].ptsSeconds - ptsSeconds) }
}

internal fun resolveActiveGopFrameIndex(
    frames: List<FrameInfo>,
    selectedFrame: FrameInfo?,
    currentVideoPtsSeconds: Double?,
    isPlaying: Boolean,
): Int? {
    if (frames.isEmpty()) return null
    if (isPlaying && currentVideoPtsSeconds != null) {
        findNearestFrameIndexByPts(frames, currentVideoPtsSeconds)?.let { return it }
    }
    if (selectedFrame != null && selectedFrame.index in frames.indices) {
        return selectedFrame.index
    }
    if (currentVideoPtsSeconds != null) {
        return findNearestFrameIndexByPts(frames, currentVideoPtsSeconds)
    }
    return null
}

private fun formatFrameByteSize(bytes: Long): String {
    return if (bytes >= 1024 * 1024) String.format(java.util.Locale.US, "%.1f MB", bytes.toDouble() / (1024 * 1024))
    else if (bytes >= 1024) String.format(java.util.Locale.US, "%.1f KB", bytes.toDouble() / 1024)
    else "$bytes B"
}

private fun formatFrameByteSize(bytes: Int): String = formatFrameByteSize(bytes.toLong())

@Composable
private fun colorForFrameType(type: Char) = when (type) {
    'I' -> AppColors.FrameTypeI
    'P' -> AppColors.FrameTypeP
    'B' -> AppColors.FrameTypeB
    else -> AppColors.TextSecondary
}

@Composable
private fun FrameTypeLegendEntry(type: Char, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(colorForFrameType(type))
                .border(0.5.dp, AppColors.Border),
        )
        Text(
            "  $label",
            style = AppTypography.bodyLarge.copy(color = AppColors.TextSecondary, fontSize = 11.sp),
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun GopAnalysisView(tab: TabState, onAnalyze: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(AppColors.Panel),
    ) {
        val frames = tab.gopFrames
        when {
            tab.isAnalyzingFrames && frames.isNullOrEmpty() -> {
                val text = if (tab.frameAnalysisLoadedCount > 0) {
                    "프레임 분석 중... (${tab.frameAnalysisLoadedCount}개 로드됨)"
                } else {
                    "프레임 분석 중..."
                }
                DecodingIndicator(text, modifier = Modifier.align(Alignment.Center))
            }
            frames == null -> {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Button(onClick = onAnalyze, enabled = tab.videoReadyForAnalysis, shape = AppControlShape, colors = appButtonColors(), border = appButtonBorder(enabled = tab.videoReadyForAnalysis), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                        Text("프레임 분석 시작")
                    }
                    if (!tab.videoReadyForAnalysis) {
                        Text(
                            "동영상 분석이 끝나면 활성화됩니다",
                            style = AppTypography.bodyLarge.copy(color = AppColors.TextSecondary, fontSize = 11.sp),
                        )
                    }
                }
            }
            frames.isEmpty() -> {
                Text(
                    "Could not analyze frames",
                    modifier = Modifier.align(Alignment.Center),
                    style = AppTypography.bodyLarge.copy(color = AppColors.NeonRed),
                )
            }
            else -> {
                val listState = rememberLazyListState()
                val focusRequester = remember { FocusRequester() }
                val coroutineScope = rememberCoroutineScope()
                var dragStartPos by remember { mutableStateOf(Offset.Zero) }
                var dragLastPos by remember { mutableStateOf(Offset.Zero) }
                var isDragPanning by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { focusRequester.requestFocus() }

                val gopSpans = remember(frames) { computeGopSpans(frames) }
                val activeFrameIndex = resolveActiveGopFrameIndex(
                    frames = frames,
                    selectedFrame = tab.selectedFrame,
                    currentVideoPtsSeconds = tab.currentVideoPtsSeconds,
                    isPlaying = tab.videoPlaybackPlaying,
                )
                val activeFrame = activeFrameIndex?.let { frames.getOrNull(it) }
                val activeGopSpan = remember(gopSpans, activeFrameIndex) {
                    activeFrameIndex?.let { findGopSpanForFrame(gopSpans, it) }
                }

                fun selectFrame(frame: FrameInfo) {
                    tab.selectedFrame = frame
                    tab.selected = null
                    tab.seekTargetSeconds = frame.ptsSeconds
                    tab.seekRequestTick++
                    focusRequester.requestFocus()
                }

                fun stepFrame(delta: Int) {
                    val current = activeFrameIndex ?: tab.selectedFrame?.index ?: 0
                    val target = (current + delta).coerceIn(0, frames.size - 1)
                    selectFrame(frames[target])
                }

                fun stepGop(delta: Int) {
                    if (gopSpans.isEmpty()) return
                    val currentGopIdx = activeGopSpan?.let { it.gopNumber - 1 } ?: 0
                    val targetGopIdx = (currentGopIdx + delta).coerceIn(0, gopSpans.lastIndex)
                    val targetFrameIdx = gopSpans[targetGopIdx].startFrameIndex
                    frames.getOrNull(targetFrameIdx)?.let { selectFrame(it) }
                }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .focusRequester(focusRequester)
                        .focusable()
                        .onKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                            when (event.key) {
                                Key.DirectionLeft -> { stepFrame(-1); true }
                                Key.DirectionRight -> { stepFrame(1); true }
                                Key.PageUp -> { stepGop(-1); true }
                                Key.PageDown -> { stepGop(1); true }
                                else -> false
                            }
                        },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            FrameTypeLegendEntry('I', "I-frame")
                            FrameTypeLegendEntry('P', "P-frame")
                            FrameTypeLegendEntry('B', "B-frame")
                            Text(
                                "총 ${gopSpans.size} GOPs",
                                style = AppTypography.labelMedium.copy(fontSize = 11.sp, color = AppColors.TextSecondary),
                            )
                            if (tab.isAnalyzingFrames) {
                                Text(
                                    "분석 진행 중 (${frames.size}개...)",
                                    style = AppTypography.labelMedium.copy(fontSize = 11.sp, color = AppColors.NeonBlue),
                                )
                            }
                        }

                        if (activeFrame != null) {
                            val sizeStr = formatFrameByteSize(activeFrame.sizeBytes)
                            Text(
                                "#${activeFrame.index} (${activeFrame.type}) · ${formatFramePts(activeFrame.ptsSeconds)} · $sizeStr | ${activeFrame.index + 1} / ${frames.size}",
                                style = AppTypography.labelLarge.copy(fontSize = 11.sp, color = AppColors.TextPrimary, fontWeight = FontWeight.SemiBold),
                            )
                        }
                    }

                    // Active GOP highlight summary bar & GOP jump controls
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(AppColors.NeonBlue.copy(alpha = 0.08f))
                            .border(0.5.dp, AppColors.NeonBlue.copy(alpha = 0.35f))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (activeGopSpan != null && activeFrame != null) {
                            val offsetInGop = activeFrame.index - activeGopSpan.startFrameIndex
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .background(AppColors.NeonBlue.copy(alpha = 0.22f))
                                        .border(1.dp, AppColors.NeonBlue)
                                        .padding(horizontal = 6.dp, vertical = 1.dp),
                                ) {
                                    Text(
                                        "GOP #${activeGopSpan.gopNumber} / ${gopSpans.size}",
                                        style = AppTypography.labelLarge.copy(fontSize = 11.sp, color = AppColors.NeonBlue, fontWeight = FontWeight.Bold),
                                    )
                                }
                                Text(
                                    "구간: #${activeGopSpan.startFrameIndex} ~ #${activeGopSpan.endFrameIndex} (${activeGopSpan.frameCount}f · ${String.format(Locale.US, "%.2fs", activeGopSpan.durationSeconds)})",
                                    fontSize = 11.sp,
                                    color = AppColors.TextPrimary,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    "구성: I:${activeGopSpan.iCount} P:${activeGopSpan.pCount} B:${activeGopSpan.bCount} (${formatFrameByteSize(activeGopSpan.totalBytes)})",
                                    fontSize = 11.sp,
                                    color = AppColors.TextSecondary,
                                )
                                Text(
                                    "현재: #${activeFrame.index} [${activeFrame.type}] (키프레임 +#${offsetInGop})",
                                    fontSize = 11.sp,
                                    color = AppColors.NeonGreen,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        } else {
                            Text(
                                "프레임을 클릭하거나 재생하면 해당 프레임과 소속 GOP 구간이 강조 표시됩니다.",
                                fontSize = 11.sp,
                                color = AppColors.TextSecondary,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (gopSpans.size > 1) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .background(AppColors.Surface)
                                        .border(0.5.dp, AppColors.Border)
                                        .clickable { stepGop(-1) }
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                ) {
                                    Text("◀ 이전 GOP", fontSize = 10.sp, color = AppColors.NeonBlue)
                                }
                                Box(
                                    modifier = Modifier
                                        .background(AppColors.Surface)
                                        .border(0.5.dp, AppColors.Border)
                                        .clickable { stepGop(1) }
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                ) {
                                    Text("다음 GOP ▶", fontSize = 10.sp, color = AppColors.NeonBlue)
                                }
                            }
                        }
                    }

                    val maxSize = remember(frames) { frames.maxOf { it.sizeBytes }.coerceAtLeast(1) }
                    val avgSize = remember(frames) { if (frames.isNotEmpty()) frames.map { it.sizeBytes }.average().toLong() else 0L }
                    val totalBytes = remember(frames) { frames.sumOf { it.sizeBytes } }
                    val durationSec = remember(frames) {
                        if (frames.size >= 2) (frames.last().ptsSeconds - frames.first().ptsSeconds).coerceAtLeast(0.01) else 1.0
                    }
                    val avgBitrateKbps = remember(totalBytes, durationSec) {
                        ((totalBytes * 8.0) / durationSec / 1000.0).toLong()
                    }
                    var frameBarWidthDp by remember { mutableStateOf(FRAME_BAR_WIDTH_DP.toFloat()) }

                    // Frame Bitrate & Size Distribution Summary Line
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(AppColors.Surface)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "📊 프레임 크기 분포:",
                                style = AppTypography.labelLarge.copy(fontSize = 11.sp, color = AppColors.NeonBlue, fontWeight = FontWeight.Bold),
                            )
                            Text("최대: ${formatFrameByteSize(maxSize)}", fontSize = 11.sp, color = AppColors.TextPrimary)
                            Text("평균: ${formatFrameByteSize(avgSize)}", fontSize = 11.sp, color = AppColors.TextPrimary)
                            Text("추정 비트레이트: ${avgBitrateKbps} kbps", fontSize = 11.sp, color = AppColors.NeonGreen)
                        }
                        Text(
                            "휠: 가로 줌 (${frameBarWidthDp.toInt()}px) · 드래그/스크롤바: 이동",
                            fontSize = 10.sp,
                            color = AppColors.TextSecondary,
                        )
                    }

                    // Keeps a keyboard/click-selected or actively-playing frame in view too -- without this,
                    // stepping or playing past the visible window would move the active frame out of sight.
                    LaunchedEffect(activeFrameIndex) {
                        val index = activeFrameIndex ?: return@LaunchedEffect
                        val visible = listState.layoutInfo.visibleItemsInfo
                        val isVisible = visible.any { it.index == index }
                        if (!isVisible) {
                            if (tab.videoPlaybackPlaying) {
                                listState.scrollToItem(index)
                            } else {
                                listState.animateScrollToItem(index)
                            }
                        }
                    }
                    LazyRow(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = 8.dp)
                            // Intercepts the wheel scroll on the way down (Initial pass), before
                            // LazyRow's own internal scrollable modifier can consume it to pan the
                            // list -- plain wheel now zooms only; panning is still available via
                            // click-and-drag (already works, see Global Constraints) or the
                            // scrollbar below. Scrolling up (away from the user) reports a negative
                            // scrollDelta.y in Compose, so subtracting it increases the width --
                            // i.e. scroll up zooms in.
                            .onPointerEvent(PointerEventType.Scroll, pass = PointerEventPass.Initial) { event ->
                                val scrollDeltaY = event.changes.firstOrNull()?.scrollDelta?.y ?: return@onPointerEvent
                                frameBarWidthDp = (frameBarWidthDp - scrollDeltaY * FRAME_BAR_ZOOM_STEP_DP)
                                    .coerceIn(FRAME_BAR_MIN_WIDTH_DP, FRAME_BAR_MAX_WIDTH_DP)
                                event.changes.forEach { it.consume() }
                            }
                            .onPointerEvent(PointerEventType.Press, pass = PointerEventPass.Initial) { event ->
                                val pos = event.changes.firstOrNull()?.position ?: return@onPointerEvent
                                dragStartPos = pos
                                dragLastPos = pos
                                isDragPanning = false
                            }
                            .onPointerEvent(PointerEventType.Move, pass = PointerEventPass.Initial) { event ->
                                val change = event.changes.firstOrNull() ?: return@onPointerEvent
                                if (change.pressed) {
                                    val currentPos = change.position
                                    val dx = currentPos.x - dragLastPos.x
                                    val totalDist = (currentPos - dragStartPos).getDistance()
                                    if (totalDist > 4f) {
                                        isDragPanning = true
                                        dragLastPos = currentPos
                                        if (dx != 0f) {
                                            coroutineScope.launch {
                                                listState.scrollBy(-dx)
                                            }
                                        }
                                        change.consume()
                                    }
                                }
                            }
                            .onPointerEvent(PointerEventType.Release, pass = PointerEventPass.Initial) { event ->
                                if (isDragPanning) {
                                    event.changes.forEach { it.consume() }
                                    isDragPanning = false
                                }
                            },
                        horizontalArrangement = Arrangement.spacedBy(FRAME_BAR_SPACING_DP.dp),
                    ) {
                        itemsIndexed(frames) { index, frame ->
                            // A fraction of the row's own height, not a computed dp value against a
                            // fixed constant -- GopAnalysisView's container height is now
                            // drag-resizable (see VideoInspectorUI), so bar heights need to scale
                            // with whatever height it actually ends up with. Width comes from the
                            // zoom state above, not the FRAME_BAR_WIDTH_DP constant (which is now
                            // only the starting/default value).
                            val heightFraction = (frame.sizeBytes.toFloat() / maxSize * FRAME_BAR_MAX_HEIGHT_FRACTION).coerceAtLeast(0.02f)
                            val isActive = index == activeFrameIndex
                            val inActiveGop = activeGopSpan != null && index in activeGopSpan.startFrameIndex..activeGopSpan.endFrameIndex
                            val isGopStart = index == 0 || frame.type == 'I'
                            val isActiveGopBoundary = activeGopSpan != null &&
                                (index == activeGopSpan.startFrameIndex || index == activeGopSpan.endFrameIndex)
                            val columnBgColor = when {
                                isActive -> AppColors.NeonBlue.copy(alpha = 0.28f)
                                inActiveGop -> AppColors.NeonBlue.copy(alpha = 0.11f)
                                else -> Color.Transparent
                            }
                            Box(
                                modifier = Modifier
                                    .width(frameBarWidthDp.dp)
                                    .fillMaxHeight()
                                    .background(columnBgColor)
                                    .clickable { selectFrame(frame) },
                            ) {
                                // GOP start vertical boundary line so every GOP division is visible
                                if (isGopStart) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopStart)
                                            .width(if (inActiveGop) 2.dp else 1.dp)
                                            .fillMaxHeight()
                                            .background(
                                                if (inActiveGop) AppColors.NeonBlue.copy(alpha = 0.85f)
                                                else AppColors.FrameTypeI.copy(alpha = 0.35f)
                                            ),
                                    )
                                }
                                if (activeGopSpan != null && index == activeGopSpan.endFrameIndex) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .width(2.dp)
                                            .fillMaxHeight()
                                            .background(AppColors.NeonBlue.copy(alpha = 0.85f)),
                                    )
                                }
                                // Continuous top bracket bar spanning all frames of the active GOP
                                if (inActiveGop) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopCenter)
                                            .fillMaxWidth()
                                            .height(if (isActiveGopBoundary) 6.dp else 4.dp)
                                            .background(AppColors.NeonBlue),
                                    )
                                }
                                // Full-height vertical playhead & top cap for the active frame
                                // (ensures tiny B/P bars are still unmistakably visible)
                                if (isActive) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.Center)
                                            .width(2.dp)
                                            .fillMaxHeight()
                                            .background(AppColors.NeonGreen),
                                    )
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopCenter)
                                            .fillMaxWidth()
                                            .height(7.dp)
                                            .background(AppColors.NeonGreen),
                                    )
                                }
                                // Actual frame size bar anchored at the bottom
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .width(frameBarWidthDp.dp)
                                        .fillMaxHeight(heightFraction)
                                        .background(colorForFrameType(frame.type))
                                        .let {
                                            when {
                                                isActive -> it.border(2.dp, Color.White)
                                                inActiveGop && isGopStart -> it.border(1.5.dp, AppColors.NeonBlue)
                                                else -> it
                                            }
                                        },
                                    contentAlignment = Alignment.TopCenter,
                                ) {
                                    if (frameBarWidthDp >= 14f) {
                                        Text(
                                            frame.type.toString(),
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.Black.copy(alpha = 0.8f),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // LazyRow scrolls via drag/trackpad regardless, but with potentially thousands
                    // of frames a visible scrollbar is the only way to see how far through the
                    // video the current view is, and to jump around quickly.
                    HorizontalScrollbar(
                        adapter = rememberScrollbarAdapter(listState),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    )
                }
            }
        }
    }
}
