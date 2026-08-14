package com.multiviewer.ui

import androidx.compose.ui.graphics.ImageBitmap
import java.awt.EventQueue
import java.io.File
import java.util.concurrent.TimeUnit

enum class CodecViewMode { MOTION_VECTORS, QP_HEATMAP }

// Only H.264 is known to work, verified independently per mode -- ffmpeg's native HEVC decoder
// silently ignores both -flags2 +export_mvs and -export_side_data venc_params (no warning either
// way), confirmed by a byte-identical before/after comparison against a real HEVC file for each.
// Kept as separate per-mode checks (not one shared boolean) because they test two different
// ffmpeg mechanisms that could diverge in support in a future ffmpeg version.
fun codecViewSupportedFor(mode: CodecViewMode, codecName: String?): Boolean = codecName == "h264"

// One-shot codec name lookup, cheap regardless of file length (unlike probeFrameTypes' -show_frames
// scan) -- safe to run once per opened video tab to decide which codec-view modes to offer.
fun probeVideoCodecName(file: File): String? {
    return try {
        val process = ProcessBuilder(
            FfmpegLocator.ffprobePath(), "-v", "error", "-select_streams", "v:0",
            "-show_entries", "stream=codec_name",
            "-of", "default=noprint_wrappers=1:nokey=1", file.absolutePath,
        ).redirectErrorStream(false).redirectError(ProcessBuilder.Redirect.DISCARD)
            .also { FfmpegLocator.configureEnvironment(it) }.start()
        val name = process.inputStream.bufferedReader().readLine()?.trim()
        process.waitFor(10, TimeUnit.SECONDS)
        name?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }
}

// Pure, unit-testable: builds the ffmpeg CLI args for frame-accurate codec-view extraction.
// Requests BOTH side-data exports unconditionally regardless of mode (-flags2 +export_mvs and
// -export_side_data venc_params) -- verified this is harmless: requesting both together produces
// byte-identical QP output to requesting venc_params alone, and visually-correct motion vector
// output combined with just -flags2 alone. This keeps one shared builder instead of two
// near-duplicate ones; only the -vf value changes per mode. Both decoder options MUST precede -i
// (AVOptions on the decoder context, silently ignored if placed after -i -- verified against a
// real ffmpeg 8.1.2 build for -flags2 in the motion-vector-only predecessor of this function).
// -ss placed AFTER -i is ffmpeg's accurate seek, independent of the above.
fun buildCodecViewFfmpegArgs(ffmpegPath: String, filePath: String, ptsSeconds: Double, mode: CodecViewMode): List<String> {
    val vf = when (mode) {
        CodecViewMode.MOTION_VECTORS -> "codecview=mv=pf+bf+bb"
        CodecViewMode.QP_HEATMAP -> "codecview=qp=1"
    }
    return listOf(
        ffmpegPath, "-y",
        "-flags2", "+export_mvs",
        "-export_side_data", "venc_params",
        "-i", filePath,
        "-ss", ptsSeconds.toString(),
        "-vf", vf,
        "-frames:v", "1", "-update", "1",
    )
}

// Reuses FfmpegImageSnapshotDecoder's "ffmpeg -> temp PNG -> Skia decode" pipeline instead of
// re-implementing its temp-file/process/timeout/decode boilerplate.
object CodecViewFrameDecoder {
    fun decodeFrameAsync(file: File, ptsSeconds: Double, mode: CodecViewMode, onResult: (ImageBitmap?) -> Unit) {
        Thread {
            val result = FfmpegImageSnapshotDecoder.decodeSingleFrameToBitmap(
                buildCodecViewFfmpegArgs(FfmpegLocator.ffmpegPath(), file.absolutePath, ptsSeconds, mode),
            )
            EventQueue.invokeLater { onResult(result) }
        }.apply { isDaemon = true }.start()
    }
}
