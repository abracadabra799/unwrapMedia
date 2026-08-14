package com.multiviewer.ui

import androidx.compose.ui.graphics.ImageBitmap
import java.awt.EventQueue
import java.io.File

// Pure, unit-testable: builds the ffmpeg CLI args for frame-accurate motion-vector-overlay
// extraction. -ss placed AFTER -i is ffmpeg's accurate seek (decodes from the nearest keyframe
// forward to the exact requested timestamp) rather than the fast-but-inexact seek before -i --
// verified this combination produces the correct single frame against a real ffmpeg 8.1.2 build.
// -flags2 +export_mvs makes the decoder attach motion vector side data to each frame; codecview's
// mv=pf+bf+bb draws it as arrows directly onto the frame's own pixels (forward vectors on P/B
// frames, backward vectors on B frames -- I-frames have none, so the filter draws nothing extra).
fun buildMotionVectorFfmpegArgs(ffmpegPath: String, filePath: String, ptsSeconds: Double): List<String> = listOf(
    ffmpegPath, "-y", "-i", filePath,
    "-ss", ptsSeconds.toString(),
    "-flags2", "+export_mvs",
    "-vf", "codecview=mv=pf+bf+bb",
    "-frames:v", "1", "-update", "1",
)

// Reuses FfmpegImageSnapshotDecoder's "ffmpeg -> temp PNG -> Skia decode" pipeline (widened to
// internal above) instead of re-implementing its temp-file/process/timeout/decode boilerplate.
object MotionVectorFrameDecoder {
    fun decodeFrameAsync(file: File, ptsSeconds: Double, onResult: (ImageBitmap?) -> Unit) {
        Thread {
            val result = FfmpegImageSnapshotDecoder.decodeSingleFrameToBitmap(
                buildMotionVectorFfmpegArgs(FfmpegLocator.ffmpegPath(), file.absolutePath, ptsSeconds),
            )
            EventQueue.invokeLater { onResult(result) }
        }.apply { isDaemon = true }.start()
    }
}
