package com.multiviewer.ui

import androidx.compose.ui.graphics.ImageBitmap
import java.awt.EventQueue
import java.io.File

// Plain accurate-seek single-frame extraction at native resolution -- no scale filter (unlike
// FrameThumbnailDecoder's small thumbnails) and no codecview side-data flags (unlike
// CodecViewFrameDecoder, which is gated to H.264 only; this works for any codec ffmpeg can
// decode). Backs the filmstrip's "click a thumbnail to see the real frame" popup.
object FrameFullSizeDecoder {
    fun decodeFrameAsync(file: File, ptsSeconds: Double, onResult: (ImageBitmap?) -> Unit) {
        Thread {
            val result = FfmpegImageSnapshotDecoder.decodeSingleFrameToBitmap(
                listOf(
                    FfmpegLocator.ffmpegPath(), "-y", "-i", file.absolutePath,
                    "-ss", ptsSeconds.toString(),
                    "-frames:v", "1", "-update", "1",
                ),
            )
            EventQueue.invokeLater { onResult(result) }
        }.apply { isDaemon = true }.start()
    }
}
