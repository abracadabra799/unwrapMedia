package com.multiviewer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// Dedicated, intentionally minimal UI for headerless raw pixel dumps (.raw/.rgb/.rgba/.yuv) --
// unlike ImageInspectorUI (thumbnail/primary/motion-photo triple preview + media summary), a raw
// dump has none of that: no EXIF, no embedded thumbnail, no motion photo pairing, and possibly
// many frames instead of one image. When the file holds more than one frame it's a raw video
// stream (see RawPixelOpenDialog's frame-rate field) -- besides the click-to-seek progress bar
// and prev/next stepping, it can also be played back continuously at the user-supplied fps.
@Composable
fun RawPixelInspectorUI(
    appState: AppState,
    tab: TabState,
    leftPanel: @Composable ColumnScope.() -> Unit,
    bottomPanel: @Composable ColumnScope.() -> Unit,
) {
    val params = tab.rawPixelParams

    if (params != null && params.frameCount > 1) {
        LaunchedEffect(tab.rawPixelIsPlaying, tab.file) {
            if (!tab.rawPixelIsPlaying) return@LaunchedEffect
            val frameDurationMs = (1000.0 / params.fps).toLong().coerceAtLeast(1)
            while (tab.rawPixelIsPlaying) {
                delay(frameDurationMs)
                if (tab.rawPixelFrameIndex >= params.frameCount - 1) {
                    tab.rawPixelIsPlaying = false
                    break
                }
                appState.seekRawPixelFrame(tab, tab.rawPixelFrameIndex + 1)
            }
        }
    }

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

                    if (params != null && params.frameCount > 1) {
                        if (!tab.rawPixelIsPlaying) {
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.5f))
                                    .clickable {
                                        if (tab.rawPixelFrameIndex >= params.frameCount - 1) {
                                            appState.seekRawPixelFrame(tab, 0)
                                        }
                                        tab.rawPixelIsPlaying = true
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = Color.White, modifier = Modifier.size(48.dp))
                            }
                        } else {
                            Box(modifier = Modifier.fillMaxSize().clickable { tab.rawPixelIsPlaying = false })
                        }
                    }
                }

                if (params != null && params.frameCount > 1) {
                    val progress = tab.rawPixelFrameIndex.toFloat() / (params.frameCount - 1).toFloat()
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .background(AppColors.Panel)
                            .pointerInput(params.frameCount) {
                                detectTapGestures { offset ->
                                    tab.rawPixelIsPlaying = false
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
                            onClick = {
                                tab.rawPixelIsPlaying = false
                                appState.seekRawPixelFrame(tab, tab.rawPixelFrameIndex - 1)
                            },
                            enabled = tab.rawPixelFrameIndex > 0,
                        ) { Text("◀ 이전 프레임", fontSize = 11.sp) }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "프레임 ${tab.rawPixelFrameIndex + 1} / ${params.frameCount} · ${params.fps} fps",
                            style = AppTypography.labelLarge.copy(fontSize = 11.sp, color = AppColors.TextSecondary),
                        )
                        Spacer(Modifier.width(12.dp))
                        Button(
                            onClick = {
                                tab.rawPixelIsPlaying = false
                                appState.seekRawPixelFrame(tab, tab.rawPixelFrameIndex + 1)
                            },
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
