package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

// Toggle-driven preview of a single GOP frame in one of two codec-view modes (see
// CodecViewFrameDecoder.kt): motion vectors or a QP heatmap, baked onto the frame's own pixels by
// ffmpeg. `mode` is driven from the app-level "보기" menu (Main.kt), not owned by this composable
// or by TabState -- switching video tabs while a mode is checked keeps it checked, matching the
// existing "픽셀 그리드" menu toggle's own always-on-once-checked precedent, rather than resetting
// per tab. Re-keying on (tab.selectedFrame, mode) cancels the AWAITING coroutine whenever the user
// steps to a new frame or the menu switches/turns off the mode before the previous request
// finished -- the cont.isActive guard then stops a late result from ever being assigned, so no
// manual staleness guard is needed for what the UI shows. This does NOT kill the underlying ffmpeg
// subprocess itself: decodeFrameAsync's background Thread runs to completion (bounded by
// decodeSingleFrameToBitmap's own 8s timeout) regardless of cancellation, since that shared helper
// doesn't expose its Process handle. Acceptable for this single-frame, click-triggered scope --
// would need revisiting if a future live-playback toggle triggers this continuously instead.
@Composable
fun CodecViewPreview(tab: TabState, mode: CodecViewMode?, modifier: Modifier = Modifier) {
    LaunchedEffect(tab.selectedFrame, mode) {
        val frame = tab.selectedFrame
        if (mode == null || frame == null) {
            tab.codecViewFrameBitmap = null
            tab.isDecodingCodecViewFrame = false
            return@LaunchedEffect
        }
        tab.isDecodingCodecViewFrame = true
        val bitmap = suspendCancellableCoroutine { cont ->
            CodecViewFrameDecoder.decodeFrameAsync(tab.file, frame.ptsSeconds, mode) { result ->
                if (cont.isActive) cont.resume(result)
            }
        }
        tab.codecViewFrameBitmap = bitmap
        tab.isDecodingCodecViewFrame = false
    }

    Column(modifier = modifier.background(AppColors.Panel)) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val frame = tab.selectedFrame
            val bitmap = tab.codecViewFrameBitmap
            when {
                mode == null -> {}
                frame == null -> Text("프레임을 선택하세요", color = Color.Gray, fontSize = 13.sp)
                tab.isDecodingCodecViewFrame -> DecodingIndicator("추출 중...")
                bitmap != null -> {
                    // codecview draws both motion vectors and QP shading at native pixel scale, and
                    // a typical video frame is far larger than this panel -- a plain fit-to-panel
                    // Image would shrink either past legibility. Reuses the same scroll-to-zoom/
                    // drag-to-pan viewer PIXEL INSPECTOR already uses instead of building a second one.
                    PixelInspectorPreview(
                        bitmap,
                        modifier = Modifier.fillMaxSize(),
                        resetKey = frame,
                    )
                    PreviewCaption(
                        "Frame #${frame.index} (${frame.type})",
                        modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
                    )
                }
                else -> Text("추출 실패", color = AppColors.NeonRed, fontSize = 13.sp)
            }
        }
    }
}
