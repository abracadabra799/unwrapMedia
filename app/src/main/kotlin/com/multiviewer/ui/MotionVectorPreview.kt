package com.multiviewer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

// Toggle-driven preview of a single GOP frame with motion vectors baked onto it (see
// MotionVectorFrameDecoder.kt). Re-keying on (tab.selectedFrame, tab.motionVectorOverlayEnabled)
// cancels the AWAITING coroutine whenever the user steps to a new frame or flips the toggle
// before the previous request finished -- the cont.isActive guard then stops a late result from
// ever being assigned, so no manual staleness guard is needed for what the UI shows. This does
// NOT kill the underlying ffmpeg subprocess itself: decodeFrameAsync's background Thread runs to
// completion (bounded by decodeSingleFrameToBitmap's own 8s timeout) regardless of cancellation,
// since that shared helper doesn't expose its Process handle. Acceptable for this single-frame,
// click-triggered v1 -- would need revisiting if a future live-playback toggle triggers this
// continuously instead.
@Composable
fun MotionVectorPreview(tab: TabState, modifier: Modifier = Modifier) {
    LaunchedEffect(tab.selectedFrame, tab.motionVectorOverlayEnabled) {
        val frame = tab.selectedFrame
        if (!tab.motionVectorOverlayEnabled || frame == null) {
            tab.motionVectorFrameBitmap = null
            tab.isDecodingMotionVectorFrame = false
            return@LaunchedEffect
        }
        tab.isDecodingMotionVectorFrame = true
        val bitmap = suspendCancellableCoroutine { cont ->
            MotionVectorFrameDecoder.decodeFrameAsync(tab.file, frame.ptsSeconds) { result ->
                if (cont.isActive) cont.resume(result)
            }
        }
        tab.motionVectorFrameBitmap = bitmap
        tab.isDecodingMotionVectorFrame = false
    }

    Column(modifier = modifier.background(AppColors.Panel)) {
        Button(
            onClick = { tab.motionVectorOverlayEnabled = !tab.motionVectorOverlayEnabled },
            modifier = Modifier.padding(8.dp),
        ) {
            Text(if (tab.motionVectorOverlayEnabled) "모션 벡터 끄기" else "모션 벡터 켜기")
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val frame = tab.selectedFrame
            val bitmap = tab.motionVectorFrameBitmap
            when {
                !tab.motionVectorOverlayEnabled -> {}
                frame == null -> Text("프레임을 선택하세요", color = Color.Gray, fontSize = 13.sp)
                tab.isDecodingMotionVectorFrame -> DecodingIndicator("모션 벡터 추출 중...")
                bitmap != null -> {
                    Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                    PreviewCaption(
                        "Frame #${frame.index} (${frame.type})",
                        modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
                    )
                }
                else -> Text("모션 벡터 추출 실패", color = AppColors.NeonRed, fontSize = 13.sp)
            }
        }
    }
}
