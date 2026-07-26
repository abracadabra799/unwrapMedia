package com.multiviewer.ui

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val GOP_GRAPH_HEIGHT_DP = 180
private const val FRAME_BAR_WIDTH_DP = 10
private const val FRAME_BAR_SPACING_DP = 3

private fun colorForFrameType(type: Char) = when (type) {
    'I' -> AppColors.NeonRed
    'P' -> AppColors.NeonGreen
    'B' -> AppColors.NeonBlue
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

@Composable
fun GopAnalysisView(tab: TabState, onAnalyze: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(GOP_GRAPH_HEIGHT_DP.dp)
            .background(AppColors.Panel),
    ) {
        val frames = tab.gopFrames
        when {
            tab.isAnalyzingFrames -> {
                Text(
                    "분석 중...",
                    modifier = Modifier.align(Alignment.Center),
                    style = AppTypography.bodyLarge.copy(color = AppColors.TextSecondary),
                )
            }
            frames == null -> {
                Button(onClick = onAnalyze, modifier = Modifier.align(Alignment.Center)) {
                    Text("프레임 분석 시작")
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
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        FrameTypeLegendEntry('I', "I-frame")
                        FrameTypeLegendEntry('P', "P-frame")
                        FrameTypeLegendEntry('B', "B-frame")
                    }

                    val maxSize = frames.maxOf { it.sizeBytes }.coerceAtLeast(1)
                    val graphAreaHeight = GOP_GRAPH_HEIGHT_DP - 32 - 16
                    // Highlights and auto-follows the frame at the current playback position (only
                    // meaningful once the video has actually started playing, hence the >= 0 guard
                    // against the 0.0 default before playback begins).
                    val currentFrameIndex = remember(frames, tab.playbackElapsedSeconds) {
                        if (tab.playbackElapsedSeconds <= 0.0) -1
                        else frames.indexOfLast { it.ptsSeconds <= tab.playbackElapsedSeconds }
                    }
                    LaunchedEffect(currentFrameIndex) {
                        if (currentFrameIndex < 0) return@LaunchedEffect
                        val isVisible = listState.layoutInfo.visibleItemsInfo.any { it.index == currentFrameIndex }
                        if (!isVisible) {
                            listState.animateScrollToItem(currentFrameIndex)
                        }
                    }
                    LazyRow(
                        state = listState,
                        modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(FRAME_BAR_SPACING_DP.dp),
                    ) {
                        itemsIndexed(frames) { index, frame ->
                            val barHeightDp = ((frame.sizeBytes.toFloat() / maxSize) * graphAreaHeight).coerceAtLeast(2f)
                            val isCurrent = index == currentFrameIndex
                            Column(
                                modifier = Modifier.width(FRAME_BAR_WIDTH_DP.dp).fillMaxSize(),
                                verticalArrangement = Arrangement.Bottom,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(FRAME_BAR_WIDTH_DP.dp)
                                        .height(barHeightDp.dp)
                                        .background(colorForFrameType(frame.type))
                                        .border(if (isCurrent) 2.dp else 0.5.dp, if (isCurrent) Color.White else AppColors.Border)
                                        .clickable {
                                            tab.selectedFrame = frame
                                            tab.selected = null
                                        },
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
