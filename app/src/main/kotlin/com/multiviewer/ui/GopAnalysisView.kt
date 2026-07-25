package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val GOP_GRAPH_HEIGHT_DP = 120
private const val FRAME_BAR_WIDTH_DP = 3

private fun colorForFrameType(type: Char) = when (type) {
    'I' -> AppColors.NeonRed
    'P' -> AppColors.NeonGreen
    'B' -> AppColors.NeonBlue
    else -> AppColors.TextSecondary
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
                val maxSize = frames.maxOf { it.sizeBytes }.coerceAtLeast(1)
                LazyRow(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                    items(frames) { frame ->
                        val barHeightDp = ((frame.sizeBytes.toFloat() / maxSize) * (GOP_GRAPH_HEIGHT_DP - 16)).coerceAtLeast(1f)
                        Column(
                            modifier = Modifier.width(FRAME_BAR_WIDTH_DP.dp).fillMaxSize(),
                            verticalArrangement = Arrangement.Bottom,
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(FRAME_BAR_WIDTH_DP.dp)
                                    .height(barHeightDp.dp)
                                    .background(colorForFrameType(frame.type))
                                    .clickable {
                                        tab.selectedFrame = frame
                                        tab.selected = null
                                    },
                            )
                        }
                    }
                }
            }
        }
    }
}
