package com.multiviewer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Dedicated, intentionally minimal UI for headerless raw pixel dumps (.raw/.rgb/.rgba/.yuv) --
// unlike ImageInspectorUI (thumbnail/primary/motion-photo triple preview + media summary), a raw
// dump has none of that: no EXIF, no embedded thumbnail, no motion photo pairing, and possibly
// many frames instead of one image. The only things that apply are a large preview and, when the
// file holds more than one frame, a way to move through them.
@Composable
fun RawPixelInspectorUI(
    appState: AppState,
    tab: TabState,
    leftPanel: @Composable ColumnScope.() -> Unit,
    bottomPanel: @Composable ColumnScope.() -> Unit,
) {
    val params = tab.rawPixelParams

    DashboardLayout(
        leftPanel = leftPanel,
        centerPanel = {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth().background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    val bitmap = tab.imageForensic?.bitmap
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                    } else {
                        Text("디코딩 중...", color = AppColors.TextSecondary)
                    }

                    if (params != null) {
                        PreviewCaption(
                            "${params.width}x${params.height} · ${params.format.label}",
                            modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
                        )
                        if (params.frameCount > 1) {
                            PreviewCaption(
                                "${tab.rawPixelFrameIndex + 1} / ${params.frameCount}",
                                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
                            )
                        }
                    }
                }

                if (params != null && params.frameCount > 1) {
                    val progress = if (params.frameCount > 1) {
                        tab.rawPixelFrameIndex.toFloat() / (params.frameCount - 1).toFloat()
                    } else {
                        0f
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .background(AppColors.Panel)
                            .pointerInput(params.frameCount) {
                                detectTapGestures { offset ->
                                    val fraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                    val target = (fraction * (params.frameCount - 1)).toInt()
                                    appState.seekRawPixelFrame(tab, target)
                                }
                            },
                    ) {
                        Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(progress).background(AppColors.NeonGreen))
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            onClick = { appState.seekRawPixelFrame(tab, tab.rawPixelFrameIndex - 1) },
                            enabled = tab.rawPixelFrameIndex > 0,
                        ) { Text("◀ 이전 프레임", fontSize = 11.sp) }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "프레임 ${tab.rawPixelFrameIndex + 1} / ${params.frameCount}",
                            style = AppTypography.labelLarge.copy(fontSize = 11.sp, color = AppColors.TextSecondary),
                        )
                        Spacer(Modifier.width(12.dp))
                        Button(
                            onClick = { appState.seekRawPixelFrame(tab, tab.rawPixelFrameIndex + 1) },
                            enabled = tab.rawPixelFrameIndex < params.frameCount - 1,
                        ) { Text("다음 프레임 ▶", fontSize = 11.sp) }
                    }
                }
            }
        },
        rightPanel = {
            DetailedPropertiesPanel(tab)
        },
        bottomPanel = bottomPanel,
    )
}
