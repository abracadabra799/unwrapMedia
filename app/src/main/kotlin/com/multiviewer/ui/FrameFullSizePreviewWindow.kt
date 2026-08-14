package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

// Opened by clicking a thumbnail in FrameThumbnailFilmstrip -- the filmstrip's own thumbnails are
// small (FrameThumbnailDecoder's THUMBNAIL_DECODE_WIDTH_PX), so this decodes and shows that same
// frame at full native resolution instead, via FrameFullSizeDecoder (plain accurate-seek, no
// scale/filter, works for any codec unlike the H.264-only CodecViewFrameDecoder). Re-decodes on
// every frame change rather than caching -- unlike the thumbnail strip, this popup only ever shows
// one frame at a time, so there's nothing to gain from a persistent cache here.
@Composable
fun FrameFullSizePreviewWindow(tab: TabState, frame: FrameInfo, onCloseRequest: () -> Unit) {
    LaunchedEffect(tab.file, frame) {
        tab.fullSizeFrameBitmap = null
        val bitmap = suspendCancellableCoroutine { cont ->
            FrameFullSizeDecoder.decodeFrameAsync(tab.file, frame.ptsSeconds) { result ->
                if (cont.isActive) cont.resume(result)
            }
        }
        tab.fullSizeFrameBitmap = bitmap
    }

    Window(onCloseRequest = onCloseRequest, title = "Frame #${frame.index} (${frame.type}) - ${tab.file.name}") {
        Box(modifier = Modifier.fillMaxSize().background(AppColors.Background)) {
            val bitmap = tab.fullSizeFrameBitmap
            when {
                bitmap != null -> PixelInspectorPreview(bitmap, modifier = Modifier.fillMaxSize(), resetKey = frame)
                else -> DecodingIndicator("프레임 디코딩 중...", modifier = Modifier.align(Alignment.Center))
            }
            PreviewCaption(
                "Frame #${frame.index} (${frame.type}) · ${"%.3f".format(frame.ptsSeconds)}s" +
                    (bitmap?.let { " · ${it.width}x${it.height}" } ?: ""),
                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
            )
        }
    }
}
