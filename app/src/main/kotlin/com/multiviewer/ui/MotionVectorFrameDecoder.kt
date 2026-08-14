package com.multiviewer.ui

import androidx.compose.ui.graphics.ImageBitmap
import java.awt.EventQueue
import java.io.File

// Pure, unit-testable: builds the ffmpeg CLI args for frame-accurate motion-vector-overlay
// extraction.
// -flags2 +export_mvs is a per-INPUT DECODER option (an AVOption on the decoder context), so it
// MUST be placed BEFORE -i to take effect -- placed after -i (as an earlier version of this
// function had it) it's silently accepted by ffmpeg but never reaches the decoder, so no motion
// vector side data is ever attached and codecview has nothing to draw. Verified against a real
// ffmpeg 8.1.2 build: before-i produced visible arrows, after-i produced a byte-for-byte-different,
// arrow-less frame with no warning either way.
// -ss placed AFTER -i is ffmpeg's accurate seek (decodes from the nearest keyframe forward to the
// exact requested timestamp) rather than the fast-but-inexact seek before -i -- this placement is
// independent of -flags2's own before/after-i requirement above, and both hold simultaneously.
// codecview's mv=pf+bf+bb draws the exported vectors as arrows directly onto the frame's own
// pixels (forward vectors on P/B frames, backward vectors on B frames -- I-frames have none, so
// the filter draws nothing extra).
fun buildMotionVectorFfmpegArgs(ffmpegPath: String, filePath: String, ptsSeconds: Double): List<String> = listOf(
    ffmpegPath, "-y",
    "-flags2", "+export_mvs",
    "-i", filePath,
    "-ss", ptsSeconds.toString(),
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
