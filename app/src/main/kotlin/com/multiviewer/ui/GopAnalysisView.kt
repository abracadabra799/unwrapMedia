package com.multiviewer.ui

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
// proportionally along with it so their relative size differences are preserved.
private const val FRAME_BAR_MAX_HEIGHT_FRACTION = 0.6f

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
                    Button(onClick = onAnalyze, enabled = tab.videoReadyForAnalysis) {
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

                fun selectFrame(frame: FrameInfo) {
                    tab.selectedFrame = frame
                    tab.selected = null
                    tab.seekTargetSeconds = frame.ptsSeconds
                    tab.seekRequestTick++
                    focusRequester.requestFocus()
                }

                fun stepFrame(delta: Int) {
                    val current = tab.selectedFrame?.index ?: 0
                    val target = (current + delta).coerceIn(0, frames.size - 1)
                    selectFrame(frames[target])
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
                            if (tab.isAnalyzingFrames) {
                                Text(
                                    "분석 진행 중 (${frames.size}개...)",
                                    style = AppTypography.labelMedium.copy(fontSize = 11.sp, color = AppColors.NeonBlue),
                                )
                            }
                        }

                        val selectedFrame = tab.selectedFrame
                        if (selectedFrame != null) {
                            val sizeStr = if (selectedFrame.sizeBytes >= 1024 * 1024) {
                                String.format(java.util.Locale.US, "%.1f MB", selectedFrame.sizeBytes.toDouble() / (1024 * 1024))
                            } else if (selectedFrame.sizeBytes >= 1024) {
                                String.format(java.util.Locale.US, "%.1f KB", selectedFrame.sizeBytes.toDouble() / 1024)
                            } else {
                                "${selectedFrame.sizeBytes} B"
                            }
                            Text(
                                "#${selectedFrame.index} (${selectedFrame.type}) · ${formatFramePts(selectedFrame.ptsSeconds)} · $sizeStr | ${selectedFrame.index + 1} / ${frames.size}",
                                style = AppTypography.labelLarge.copy(fontSize = 11.sp, color = AppColors.TextSecondary),
                            )
                        }
                    }

                    val maxSize = remember(frames) { frames.maxOf { it.sizeBytes }.coerceAtLeast(1) }
                    var frameBarWidthDp by remember { mutableStateOf(FRAME_BAR_WIDTH_DP.toFloat()) }

                    // Keeps a keyboard/click-selected frame in view too -- without this, stepping
                    // past the visible window with the arrow keys would move the selection out of
                    // sight with no way to tell where it landed.
                    LaunchedEffect(tab.selectedFrame) {
                        val index = tab.selectedFrame?.index ?: return@LaunchedEffect
                        val isVisible = listState.layoutInfo.visibleItemsInfo.any { it.index == index }
                        if (!isVisible) {
                            listState.animateScrollToItem(index)
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
                            val isSelected = index == tab.selectedFrame?.index
                            Column(
                                modifier = Modifier.width(frameBarWidthDp.dp).fillMaxSize(),
                                verticalArrangement = Arrangement.Bottom,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(frameBarWidthDp.dp)
                                        .fillMaxHeight(heightFraction)
                                        .background(colorForFrameType(frame.type))
                                        .let { if (isSelected) it.border(2.dp, Color.White) else it }
                                        .clickable { selectFrame(frame) },
                                )
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
