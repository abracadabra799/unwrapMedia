package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun CodecViewPreviewWindow(
    tab: TabState,
    mode: CodecViewMode,
    onCloseRequest: () -> Unit,
) {
    val frame = tab.selectedFrame
    val frames = tab.gopFrames ?: emptyList()
    var scrollAccumulator by remember { mutableStateOf(0f) }

    LaunchedEffect(Unit) {
        if (tab.selectedFrame == null && !frames.isEmpty()) {
            tab.selectedFrame = frames.firstOrNull()
        }
    }

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

    LaunchedEffect(tab.selectedFrame, mode) {
        val f = tab.selectedFrame
        if (f == null) {
            tab.codecViewFrameBitmap = null
            tab.isDecodingCodecViewFrame = false
            return@LaunchedEffect
        }
        tab.isDecodingCodecViewFrame = true
        kotlinx.coroutines.delay(40)
        val bitmap = suspendCancellableCoroutine { cont ->
            CodecViewFrameDecoder.decodeFrameAsync(tab.file, f.ptsSeconds, mode) { result ->
                if (cont.isActive) cont.resume(result)
            }
        }
        tab.codecViewFrameBitmap = bitmap
        tab.isDecodingCodecViewFrame = false
    }

    val modeTitle = when (mode) {
        CodecViewMode.MOTION_VECTORS -> "모션 벡터 (Motion Vectors)"
        CodecViewMode.QP_HEATMAP -> "QP 히트맵 (QP Heatmap)"
    }
    val windowTitle = if (frame != null) {
        "$modeTitle - Frame #${frame.index} (${frame.type}) - ${tab.file.name}"
    } else {
        "$modeTitle - ${tab.file.name}"
    }

    val windowState = rememberWindowState(
        position = WindowPosition(Alignment.Center),
        size = DpSize(1000.dp, 750.dp),
    )

    Window(
        onCloseRequest = onCloseRequest,
        title = windowTitle,
        state = windowState,
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
            val bitmap = tab.codecViewFrameBitmap
            when {
                frame == null -> Text(
                    "프레임을 선택하세요",
                    modifier = Modifier.align(Alignment.Center),
                    style = AppTypography.bodyLarge.copy(color = AppColors.TextSecondary, fontSize = 13.sp),
                )
                tab.isDecodingCodecViewFrame -> DecodingIndicator("추출 중...", modifier = Modifier.align(Alignment.Center))
                bitmap != null -> {
                    PixelInspectorPreview(
                        bitmap = bitmap,
                        modifier = Modifier.fillMaxSize(),
                        resetKey = frame,
                    )
                }
                else -> Text(
                    "추출 실패",
                    modifier = Modifier.align(Alignment.Center),
                    color = AppColors.NeonRed,
                    fontSize = 14.sp,
                )
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
                        "  (◀/▶ 방향키 또는 마우스 휠로 프레임 이동, 스크롤/드래그로 줌/팬)",
                    modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
                )
            }
        }
    }
}
