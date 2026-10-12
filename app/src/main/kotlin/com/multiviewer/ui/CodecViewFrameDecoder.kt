package com.multiviewer.ui

import androidx.compose.ui.graphics.ImageBitmap
import java.awt.EventQueue
import java.io.File

enum class CodecViewMode { MOTION_VECTORS, QP_HEATMAP }

// Only H.264 is known to work, verified independently per mode -- ffmpeg's native HEVC decoder
// silently ignores both -flags2 +export_mvs and -export_side_data venc_params (no warning either
// way), confirmed by a byte-identical before/after comparison against a real HEVC file for each.
// Kept as separate per-mode checks (not one shared boolean) because they test two different
// ffmpeg mechanisms that could diverge in support in a future ffmpeg version.
fun codecViewSupportedFor(mode: CodecViewMode, codecName: String?): Boolean = when (mode) {
    CodecViewMode.MOTION_VECTORS -> codecName == "h264" || codecName == "hevc"
    CodecViewMode.QP_HEATMAP -> codecName == "h264" || codecName == "hevc" || codecName == "av1"
}

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
        val name = readProcessOutputWithTimeout(process, 10) { process.inputStream.bufferedReader().readLine()?.trim() }
        name?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }
}

// Pure, unit-testable: builds the ffmpeg CLI args for frame-accurate codec-view extraction.
// Requests BOTH side-data exports unconditionally regardless of mode (-flags2 +export_mvs and
// -export_side_data venc_params) -- verified this is harmless: requesting both together produces
// byte-identical QP output to requesting venc_params alone, and visually-correct motion vector
// output combined with just -flags2 alone. For HEVC QP, block=1 provides partition boundaries.
fun buildCodecViewFfmpegArgs(
    ffmpegPath: String,
    filePath: String,
    ptsSeconds: Double,
    mode: CodecViewMode,
    codecName: String? = null,
): List<String> {
    val vf = when (mode) {
        CodecViewMode.MOTION_VECTORS -> "codecview=mv=pf+bf+bb"
        CodecViewMode.QP_HEATMAP -> if (codecName == "hevc") "codecview=block=1" else "codecview=qp=1"
    }
    val args = mutableListOf(
        ffmpegPath, "-y",
        "-flags2", "+export_mvs",
        "-export_side_data", "venc_params",
        "-i", filePath,
        "-ss", ptsSeconds.toString(),
    )
    if (codecName != "av1") {
        args += listOf("-vf", vf)
    }
    args += listOf("-frames:v", "1", "-update", "1")
    return args
}

// Reuses FfmpegImageSnapshotDecoder's "ffmpeg -> temp PNG -> Skia decode" pipeline instead of
// re-implementing its temp-file/process/timeout/decode boilerplate.
object CodecViewFrameDecoder {
    fun decodeFrameAsync(
        file: File,
        ptsSeconds: Double,
        mode: CodecViewMode,
        codecName: String? = null,
        onResult: (ImageBitmap?) -> Unit,
    ) {
        Thread {
            val result = FfmpegImageSnapshotDecoder.decodeSingleFrameToBitmap(
                buildCodecViewFfmpegArgs(FfmpegLocator.ffmpegPath(), file.absolutePath, ptsSeconds, mode, codecName),
            )
            EventQueue.invokeLater { onResult(result) }
        }.apply { isDaemon = true }.start()
    }
}
