package com.multiviewer.ui

import java.awt.image.BufferedImage
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max

// Exact single-frame decoding by ABSOLUTE stream PTS (ffprobe frame=pts_time, as FrameInfo carries it).
// Measured (docs/validation/video-frame-fixtures): plain `-ss PTS -i` lands on another frame once the
// stream's start_time is not 0 (MPEG-TS), so this keeps timestamps absolute (-copyts), seeks a little
// early (fast, keyframe-aligned) and lets `select` pick the first frame at/after PTS; showinfo reports
// which frame actually came out, and a different one is refused rather than shown as the selected frame.

private const val SEEK_PREROLL_SECONDS = 3.0
private const val SELECT_EPSILON_SECONDS = 0.0005
private val SHOWINFO_PTS = Regex("""pts_time:\s*(-?[0-9]+(?:\.[0-9]+)?)""")
private val HDR_TRANSFERS = setOf("smpte2084", "arib-std-b67")

internal fun fmt6(x: Double): String = String.format(Locale.US, "%.6f", x)

data class FrameStreamInfo(val startTime: Double, val colorTransfer: String?, val formatName: String? = null) {
    // Measured (docs/validation/video-frame-fixtures): a plain `-ss` lands on the requested frame in MP4/MOV,
    // Matroska/WebM and MPEG-PS even with a non-zero start_time, but not in MPEG-TS (no seek index) -- and
    // ffmpeg's own default TS output starts at 1.47 s, so practically every TS file is affected.
    val needsExactSeek: Boolean get() = formatName?.split(',')?.any { it.trim() == "mpegts" } == true
}

fun parseFrameStreamInfo(lines: List<String>): FrameStreamInfo {
    var start = 0.0
    var transfer: String? = null
    var format: String? = null
    for (line in lines) {
        val eq = line.indexOf('=')
        if (eq < 0) continue
        val value = line.substring(eq + 1).trim()
        when (line.substring(0, eq).trim()) {
            "start_time" -> start = value.toDoubleOrNull() ?: 0.0
            "color_transfer" -> transfer = value.takeIf { it.isNotEmpty() && it != "unknown" }
            "format_name" -> format = value.takeIf { it.isNotEmpty() }
        }
    }
    return FrameStreamInfo(start, transfer, format)
}

/** One cheap ffprobe; null when it failed (timeout, missing ffprobe, unreadable file) -- callers must not cache that. */
fun probeFrameStreamInfoOrNull(file: File): FrameStreamInfo? {
    return try {
        val process = ProcessBuilder(
            FfmpegLocator.ffprobePath(), "-v", "error", "-select_streams", "v:0",
            "-show_entries", "format=start_time,format_name:stream=color_transfer", "-of", "default=noprint_wrappers=1", file.absolutePath,
        ).redirectError(ProcessBuilder.Redirect.DISCARD).also { FfmpegLocator.configureEnvironment(it) }.start()
        val lines = readProcessOutputWithTimeout(process, 5) { process.inputStream.bufferedReader().readLines() }
            ?: return null
        if (lines.none { it.startsWith("format_name=") }) return null
        parseFrameStreamInfo(lines)
    } catch (e: InterruptedException) {
        throw e
    } catch (e: Exception) {
        null
    }
}

/** As [probeFrameStreamInfoOrNull], with start_time 0 on failure (the PTS check in [decodeExactFrame] still guards). */
fun probeFrameStreamInfo(file: File): FrameStreamInfo = probeFrameStreamInfoOrNull(file) ?: FrameStreamInfo(0.0, null)

/** ffmpeg arguments up to (not including) the output path. */
fun exactFrameArgs(ffmpeg: String, file: String, ptsSeconds: Double, startTime: Double): List<String> = listOf(
    ffmpeg, "-v", "info", "-copyts",
    "-ss", fmt6(max(0.0, ptsSeconds - startTime - SEEK_PREROLL_SECONDS)),
    "-i", file, "-map", "0:v:0",
    "-vf", "select=gte(t\\,${fmt6(ptsSeconds - SELECT_EPSILON_SECONDS)}),showinfo",
    "-frames:v", "1", "-pix_fmt", "rgb24", "-y",
)

fun parseShowinfoPts(stderr: String): Double? = stderr.lineSequence()
    .filter { "Parsed_showinfo" in it }
    .firstNotNullOfOrNull { SHOWINFO_PTS.find(it)?.groupValues?.get(1)?.toDoubleOrNull() }

sealed interface ExactFrameResult {
    data class Frame(val image: BufferedImage, val ptsSeconds: Double, val hdr: Boolean) : ExactFrameResult
    data class Failed(val reason: String) : ExactFrameResult
}

/** Blocking (ffmpeg); interruption kills the process and propagates. */
fun decodeExactFrame(
    file: File,
    ptsSeconds: Double,
    info: FrameStreamInfo,
    frameDurationSeconds: Double,
    timeoutSeconds: Long = 15,
): ExactFrameResult {
    val out = try {
        File.createTempFile("exact-frame-", ".png")
    } catch (e: Exception) {
        return ExactFrameResult.Failed("temp file could not be created")
    }
    try {
        val process = try {
            ProcessBuilder(exactFrameArgs(FfmpegLocator.ffmpegPath(), file.absolutePath, ptsSeconds, info.startTime) + out.absolutePath)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .also { FfmpegLocator.configureEnvironment(it) }
                .start()
        } catch (e: Exception) {
            return ExactFrameResult.Failed("ffmpeg did not start")
        }
        val stderr = readProcessOutputWithTimeout(process, timeoutSeconds) { process.errorStream.bufferedReader().readText() }
            ?: return ExactFrameResult.Failed("ffmpeg timed out after $timeoutSeconds s")
        if (!process.waitFor(2, TimeUnit.SECONDS) || process.exitValue() != 0) {
            val last = stderr.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }
            return ExactFrameResult.Failed(if (last == null) "ffmpeg failed" else "ffmpeg failed: ${com.multiviewer.cli.scrubPaths(last, file)}")
        }
        val decoded = parseShowinfoPts(stderr) ?: return ExactFrameResult.Failed("no frame at or after PTS ${fmt6(ptsSeconds)}")
        val tolerance = max(0.001, frameDurationSeconds / 2)
        if (abs(decoded - ptsSeconds) > tolerance) {
            return ExactFrameResult.Failed("decoded PTS ${fmt6(decoded)} ≠ selected ${fmt6(ptsSeconds)}")
        }
        val image = ImageIO.read(out) ?: return ExactFrameResult.Failed("unreadable frame image")
        return ExactFrameResult.Frame(image, decoded, info.colorTransfer in HDR_TRANSFERS)
    } finally {
        out.delete()
    }
}

/** Duration of [frame]: gap to the next frame, else to the previous one, else 1/30 s. */
fun frameDurationAround(frame: FrameInfo, frames: List<FrameInfo>?): Double {
    val sorted = frames?.sortedBy { it.ptsSeconds }.orEmpty()
    val at = sorted.indexOfFirst { it.index == frame.index }
    val next = sorted.getOrNull(at + 1)?.takeIf { at >= 0 }
    val prev = sorted.getOrNull(at - 1)?.takeIf { at >= 1 }
    return when {
        next != null -> next.ptsSeconds - frame.ptsSeconds
        prev != null -> frame.ptsSeconds - prev.ptsSeconds
        else -> 1.0 / 30
    }
}

/** The AI's view of a selected frame: stream info + exact decode. Blocking. */
fun decodeSelectedFrame(file: File, frame: FrameInfo, frames: List<FrameInfo>?): ExactFrameResult =
    decodeExactFrame(file, frame.ptsSeconds, probeFrameStreamInfo(file), frameDurationAround(frame, frames))
