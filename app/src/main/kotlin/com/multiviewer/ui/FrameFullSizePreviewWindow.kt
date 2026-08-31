package com.multiviewer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

// Opened by clicking a thumbnail in FrameThumbnailFilmstrip -- the filmstrip's own thumbnails are
// small (FrameThumbnailDecoder's THUMBNAIL_DECODE_WIDTH_PX), so this decodes and shows that same
// frame at full native resolution instead, via FrameFullSizeDecoder (plain accurate-seek, no
// scale/filter, works for any codec unlike the H.264-only CodecViewFrameDecoder). Always tracks
// tab.selectedFrame LIVE rather than freezing whichever frame was clicked to open it -- stepping
// frames with arrow keys or mouse wheel while this window is open re-decodes and updates it too,
// keeping the popup in sync with wherever the filmstrip's focus currently is.
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun FrameFullSizePreviewWindow(tab: TabState, onCloseRequest: () -> Unit) {
    val frame = tab.selectedFrame
    val frames = tab.gopFrames ?: emptyList()
    var scrollAccumulator by remember { mutableStateOf(0f) }

    fun selectFrame(targetFrame: FrameInfo) {
        tab.selectedFrame = targetFrame
        tab.selected = null
        tab.seekTargetSeconds = targetFrame.ptsSeconds
        tab.seekRequestTick++
    }

    fun stepFrame(delta: Int) {
        if (frames.isEmpty()) return
        val currentIdx = tab.selectedFrame?.let { sf -> frames.indexOfFirst { it.index == sf.index } } ?: 0
        if (currentIdx < 0) return
        val nextIdx = (currentIdx + delta).coerceIn(0, frames.size - 1)
        if (nextIdx != currentIdx) {
            selectFrame(frames[nextIdx])
        }
    }

    LaunchedEffect(tab.file, frame) {
        tab.fullSizeFrameBitmap = null
        if (frame == null) return@LaunchedEffect
        val bitmap = suspendCancellableCoroutine { cont ->
            FrameFullSizeDecoder.decodeFrameAsync(tab.file, frame.ptsSeconds) { result ->
                if (cont.isActive) cont.resume(result)
            }
        }
        tab.fullSizeFrameBitmap = bitmap
    }

    Window(
        onCloseRequest = onCloseRequest,
        title = if (frame != null) "Frame #${frame.index} (${frame.type}) - ${tab.file.name}" else tab.file.name,
        onKeyEvent = { keyEvent ->
            if (keyEvent.type == KeyEventType.KeyDown) {
                when (keyEvent.key) {
                    Key.DirectionLeft, Key.A, Key.Comma -> {
                        stepFrame(-1)
                        true
                    }
                    Key.DirectionRight, Key.D, Key.Period -> {
                        stepFrame(1)
                        true
                    }
                    else -> false
                }
            } else {
                false
            }
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(AppColors.Background)
                .onPointerEvent(PointerEventType.Scroll, pass = PointerEventPass.Initial) { event ->
                    val delta = event.changes.firstOrNull()?.scrollDelta ?: return@onPointerEvent
                    val scrollDelta = if (delta.x != 0f) delta.x else delta.y
                    if (scrollDelta != 0f) {
                        scrollAccumulator += scrollDelta
                        if (scrollAccumulator >= 1f) {
                            val steps = scrollAccumulator.toInt()
                            stepFrame(steps)
                            scrollAccumulator -= steps
                        } else if (scrollAccumulator <= -1f) {
                            val steps = (-scrollAccumulator).toInt()
                            stepFrame(-steps)
                            scrollAccumulator += steps
                        }
                        event.changes.forEach { it.consume() }
                    }
                },
        ) {
            val bitmap = tab.fullSizeFrameBitmap
            when {
                frame == null -> Text(
                    "프레임을 선택하세요",
                    modifier = Modifier.align(Alignment.Center),
                    style = AppTypography.bodyLarge.copy(color = AppColors.TextSecondary, fontSize = 13.sp),
                )
                bitmap != null -> Image(
                    bitmap = bitmap,
                    contentDescription = "Frame #${frame.index}",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
                else -> DecodingIndicator("프레임 디코딩 중...", modifier = Modifier.align(Alignment.Center))
            }

            // Left / Right Navigation buttons
            if (frames.isNotEmpty()) {
                IconButton(
                    onClick = { stepFrame(-1) },
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(12.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(20.dp)),
                ) {
                    Text("◀", color = Color.White, fontSize = 16.sp)
                }

                IconButton(
                    onClick = { stepFrame(1) },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(12.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(20.dp)),
                ) {
                    Text("▶", color = Color.White, fontSize = 16.sp)
                }
            }

            if (frame != null) {
                val frameIndex = frames.indexOfFirst { it.index == frame.index }
                val totalStr = if (frames.isNotEmpty()) " / ${frames.size}" else ""
                val currentNum = if (frameIndex >= 0) "${frameIndex + 1}" else "${frame.index}"
                PreviewCaption(
                    "Frame #$currentNum$totalStr (${frame.type}) · ${"%.3f".format(frame.ptsSeconds)}s" +
                        (bitmap?.let { " · ${it.width}x${it.height}" } ?: "") +
                        "  (◀/▶ 방향키 또는 마우스 휠로 프레임 이동)",
                    modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
                )
            }
        }
    }
}
