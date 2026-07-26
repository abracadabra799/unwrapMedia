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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private fun formatFps(fps: Double): String =
    if (fps == fps.toLong().toDouble()) fps.toLong().toString() else fps.toString()

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
        // Decodes and waits for each frame before advancing, rather than firing off a seek every
        // tick regardless of whether the previous decode finished. The fire-and-forget version
        // (AppState.seekRawPixelFrame, still used for manual prev/next/scrub) let the frame index
        // race ahead of decoding -- especially for the ffmpeg-backed YUV formats, where every frame
        // spawns a fresh subprocess -- so slow decodes were silently dropped by the stale-result
        // guard and playback visually skipped through content faster than intended.
        LaunchedEffect(tab.rawPixelIsPlaying, tab.file) {
            if (!tab.rawPixelIsPlaying) return@LaunchedEffect
            while (tab.rawPixelIsPlaying) {
                val nextIndex = tab.rawPixelFrameIndex + 1
                if (nextIndex > params.frameCount - 1) {
                    tab.rawPixelIsPlaying = false
                    break
                }
                // Read fresh each iteration so live edits to tab.rawPixelFps (via the field in the
                // controls row below) take effect on the very next frame instead of requiring a
                // pause/resume.
                val frameDurationMs = (1000.0 / tab.rawPixelFps).toLong().coerceAtLeast(1)
                val start = System.currentTimeMillis()
                val bitmap = withContext(Dispatchers.IO) {
                    decodeRawPixelFile(tab.file, params.width, params.height, params.format, params.byteOrder, nextIndex)
                }
                if (!tab.rawPixelIsPlaying) break
                tab.rawPixelFrameIndex = nextIndex
                tab.imageForensic = (tab.imageForensic ?: ImageForensicData()).copy(bitmap = bitmap)
                val elapsed = System.currentTimeMillis() - start
                val remaining = frameDurationMs - elapsed
                if (remaining > 0) delay(remaining)
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
                }

                if (params != null && params.frameCount > 1) {
                    // Play/pause lives as a small button at the start of the progress bar instead
                    // of a large overlay on top of the preview -- it stayed out of the way while
                    // still being reachable next to the scrub target it controls.
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = {
                                if (!tab.rawPixelIsPlaying && tab.rawPixelFrameIndex >= params.frameCount - 1) {
                                    appState.seekRawPixelFrame(tab, 0)
                                }
                                tab.rawPixelIsPlaying = !tab.rawPixelIsPlaying
                            },
                            modifier = Modifier.size(28.dp),
                        ) {
                            if (tab.rawPixelIsPlaying) {
                                Text("⏸", fontSize = 14.sp, color = AppColors.TextPrimary)
                            } else {
                                Icon(
                                    Icons.Filled.PlayArrow,
                                    contentDescription = "Play",
                                    tint = AppColors.TextPrimary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                        Spacer(Modifier.width(4.dp))
                        Box(
                            modifier = Modifier
                                .weight(1f)
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
                            val progress = tab.rawPixelFrameIndex.toFloat() / (params.frameCount - 1).toFloat()
                            Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(progress).background(AppColors.NeonGreen))
                        }
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
                            "프레임 ${tab.rawPixelFrameIndex + 1} / ${params.frameCount} ·",
                            style = AppTypography.labelLarge.copy(fontSize = 11.sp, color = AppColors.TextSecondary),
                        )
                        Spacer(Modifier.width(4.dp))
                        // Raw dumps carry no frame-rate metadata, so the dialog's up-front value is
                        // only ever a starting guess -- editable here too, without reopening the
                        // file, and live playback picks up the change on the next frame.
                        var fpsText by remember(tab) { mutableStateOf(formatFps(tab.rawPixelFps)) }
                        OutlinedTextField(
                            value = fpsText,
                            onValueChange = { input ->
                                val filtered = input.filter { c -> c.isDigit() || c == '.' }
                                fpsText = filtered
                                filtered.toDoubleOrNull()?.let { if (it > 0) tab.rawPixelFps = it }
                            },
                            singleLine = true,
                            textStyle = AppTypography.labelLarge.copy(fontSize = 11.sp),
                            modifier = Modifier.width(64.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("fps", style = AppTypography.labelLarge.copy(fontSize = 11.sp, color = AppColors.TextSecondary))
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
