package com.multiviewer.ui

import java.io.File
import java.util.concurrent.TimeUnit

data class MetricFrameSample(val frameIndex: Int, val value: Double)

data class MetricStatistics(val min: Double, val max: Double, val mean: Double, val median: Double)

data class MetricRunResult(val perFrame: List<MetricFrameSample>, val statistics: MetricStatistics)

private const val METRIC_RUN_TIMEOUT_SECONDS = 600L
private const val PSNR_INFINITE_CAP_DB = 100.0
private val PSNR_AVG_REGEX = Regex("""psnr_avg:(\S+)""")
private val SSIM_ALL_REGEX = Regex("""All:(\S+)""")

// Computes min/max/mean/median over a metric's per-frame values -- shared by every metric pass so
// PSNR/SSIM (and, in a later phase, VMAF) report statistics the same way. Returns null for an empty
// list: a metric pass that produced zero frames of data is a failure, not a zero-stats result.
fun computeStatistics(perFrame: List<MetricFrameSample>): MetricStatistics? {
    if (perFrame.isEmpty()) return null
    val values = perFrame.map { it.value }.sorted()
    val n = values.size
    val median = if (n % 2 == 1) values[n / 2] else (values[n / 2 - 1] + values[n / 2]) / 2.0
    return MetricStatistics(min = values.first(), max = values.last(), mean = values.sum() / n, median = median)
}

// ffprobe's video-stream width/height, or null if the file has no video stream or ffprobe fails.
private fun probeResolution(file: File): Pair<Int, Int>? {
    return try {
        val process = ProcessBuilder(
            FfmpegLocator.ffprobePath(), "-v", "error", "-select_streams", "v:0",
            "-show_entries", "stream=width,height", "-of", "csv=p=0", file.absolutePath,
        ).also { FfmpegLocator.configureEnvironment(it) }
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val line = process.inputStream.bufferedReader().readLine()
        process.waitFor(30, TimeUnit.SECONDS)
        val parts = line?.trim()?.split(",") ?: return null
        if (parts.size != 2) return null
        Pair(parts[0].toInt(), parts[1].toInt())
    } catch (e: Exception) {
        null
    }
}

// True only when both files have a resolvable, matching video-stream resolution. Callers must check
// this before running any metric pass -- verified: ffmpeg's psnr/ssim filters exit with a non-zero
// code (234, observed) on mismatched resolutions rather than comparing what they can.
fun resolutionsMatch(comparison: File, reference: File): Boolean {
    val a = probeResolution(comparison) ?: return false
    val b = probeResolution(reference) ?: return false
    return a == b
}

data class ComparisonPair(val id: String, val label: String, val comparison: File, val reference: File)

// Determines which of the 3 possible comparison pairs (Raw-A, Raw-B, A-B) the currently-filled file
// slots imply, in this fixed order -- pure structural logic, no I/O, no resolution checking (that's
// resolutionsMatch, checked separately per pair once this list is known). Encoded A alone never
// produces a pair since there's nothing to compare it against yet.
fun determineComparisonPairs(raw: File?, encodedA: File?, encodedB: File?): List<ComparisonPair> {
    val pairs = mutableListOf<ComparisonPair>()
    if (raw != null && encodedA != null) {
        pairs.add(ComparisonPair(id = "raw_a", label = "Raw ↔ Encoded A", comparison = encodedA, reference = raw))
    }
    if (raw != null && encodedA != null && encodedB != null) {
        pairs.add(ComparisonPair(id = "raw_b", label = "Raw ↔ Encoded B", comparison = encodedB, reference = raw))
    }
    if (encodedA != null && encodedB != null) {
        pairs.add(ComparisonPair(id = "a_b", label = "Encoded A ↔ Encoded B", comparison = encodedB, reference = encodedA))
    }
    return pairs
}

// Total video-stream frame count, used as the progress bar's denominator. Prefers the container's
// stored frame count (nb_frames, fast); falls back to duration * frame rate when nb_frames is
// unavailable ("N/A" on some containers/codecs that don't store it). Returns null if neither source
// is usable -- callers show an indeterminate/unknown-total progress bar in that case.
private fun probeFrameCount(file: File): Int? {
    try {
        val nbFramesProcess = ProcessBuilder(
            FfmpegLocator.ffprobePath(), "-v", "error", "-select_streams", "v:0",
            "-show_entries", "stream=nb_frames", "-of", "csv=p=0", file.absolutePath,
        ).also { FfmpegLocator.configureEnvironment(it) }
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val nbFramesLine = nbFramesProcess.inputStream.bufferedReader().readLine()
        nbFramesProcess.waitFor(30, TimeUnit.SECONDS)
        val nbFrames = nbFramesLine?.trim()?.toIntOrNull()
        if (nbFrames != null) return nbFrames

        val durationProcess = ProcessBuilder(
            FfmpegLocator.ffprobePath(), "-v", "error", "-select_streams", "v:0",
            "-show_entries", "stream=duration,r_frame_rate", "-of", "csv=p=0", file.absolutePath,
        ).also { FfmpegLocator.configureEnvironment(it) }
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val durationLine = durationProcess.inputStream.bufferedReader().readLine()
        durationProcess.waitFor(30, TimeUnit.SECONDS)
        val parts = durationLine?.trim()?.split(",") ?: return null
        if (parts.size != 2) return null
        val duration = parts[0].toDoubleOrNull() ?: return null
        val rateParts = parts[1].split("/")
        if (rateParts.size != 2) return null
        val num = rateParts[0].toDoubleOrNull() ?: return null
        val den = rateParts[1].toDoubleOrNull() ?: return null
        if (den == 0.0) return null
        return (duration * (num / den)).toInt().takeIf { it > 0 }
    } catch (e: Exception) {
        return null
    }
}

// Escapes a path for embedding as a filtergraph option value -- ':' is the lavfi
// option separator and must be escaped; backslashes are normalized to forward
// slashes (which ffmpeg accepts as path separators on all platforms) rather than
// escaped, since escaping '\' itself inside an already-'\'-heavy Windows path is
// more failure-prone than avoiding backslashes entirely.
private fun escapeForFilterGraph(path: String): String =
    path.replace("\\", "/").replace(":", "\\:")

// Runs one ffmpeg metric pass (`-lavfi "<filterSpec>"`), reporting progress via onProgress(currentFrame,
// totalFrames) as ffmpeg's own `-progress pipe:1` output reports frames processed (verified real
// output shape: key=value lines including "frame=N", one block per update, "progress=end" on the
// final block), and honoring cancellation via isCancelled -- checked between progress lines, killing
// the process (destroyForcibly) if set. Blocks the calling thread until the process exits, is
// cancelled, or times out; callers must invoke this off the UI thread. Returns true only on a clean
// exit with the stats file actually written.
private fun runMetricPass(
    comparison: File,
    reference: File,
    filterSpec: String,
    statsFile: File,
    onProgress: (currentFrame: Int, totalFrames: Int?) -> Unit,
    isCancelled: () -> Boolean,
): Boolean {
    val totalFrames = probeFrameCount(comparison)
    val process = try {
        ProcessBuilder(
            FfmpegLocator.ffmpegPath(), "-y",
            "-i", comparison.absolutePath, "-i", reference.absolutePath,
            "-lavfi", filterSpec,
            "-progress", "pipe:1",
            "-f", "null", "-",
        ).also { FfmpegLocator.configureEnvironment(it) }
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
    } catch (e: Exception) {
        return false
    }
    return try {
        process.inputStream.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (isCancelled()) {
                    process.destroyForcibly()
                    return false
                }
                if (line.startsWith("frame=")) {
                    val frame = line.substringAfter("frame=").trim().toIntOrNull()
                    if (frame != null) onProgress(frame, totalFrames)
                }
            }
        }
        val finished = process.waitFor(METRIC_RUN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return false
        }
        process.exitValue() == 0 && statsFile.exists()
    } catch (e: Exception) {
        process.destroyForcibly()
        false
    }
}

// Parses ffmpeg's psnr filter stats-file format (one line per frame, e.g. "n:1 mse_avg:0.88 ...
// psnr_avg:48.68 ..."), verified against real ffmpeg 8.1 output. Curates only psnr_avg (the
// combined Y/U/V average) per frame, matching this codebase's "curated fields, not every value the
// tool exposes" convention (see e.g. Av1SequenceHeader.kt). A pixel-identical frame reports
// "psnr_avg:inf" (verified: comparing a file against itself) -- Kotlin's toDoubleOrNull doesn't
// parse "inf" (only "Infinity"), and even if it did, a literal infinite value would poison any mean
// computed over a run that also contains normal frames. Capped at PSNR_INFINITE_CAP_DB instead.
private fun parsePsnrLog(statsFile: File): List<MetricFrameSample> {
    return statsFile.readLines().mapIndexedNotNull { index, line ->
        val match = PSNR_AVG_REGEX.find(line) ?: return@mapIndexedNotNull null
        val rawValue = match.groupValues[1]
        val value = if (rawValue == "inf") PSNR_INFINITE_CAP_DB else rawValue.toDoubleOrNull() ?: return@mapIndexedNotNull null
        MetricFrameSample(frameIndex = index, value = value)
    }
}

// Parses ffmpeg's ssim filter stats-file format (one line per frame, e.g. "n:1 Y:0.998812 ...
// All:0.998723 (28.939485)"), verified against real ffmpeg 8.1 output. Curates only All (the
// combined Y/U/V SSIM, always in 0.0..1.0, including for identical frames -- verified: identical
// frames report "All:1.000000", not "inf"; the "(inf)" that DOES appear for identical frames is a
// separate dB-scale figure in parentheses that this regex never captures).
private fun parseSsimLog(statsFile: File): List<MetricFrameSample> {
    return statsFile.readLines().mapIndexedNotNull { index, line ->
        val match = SSIM_ALL_REGEX.find(line) ?: return@mapIndexedNotNull null
        val value = match.groupValues[1].toDoubleOrNull() ?: return@mapIndexedNotNull null
        MetricFrameSample(frameIndex = index, value = value)
    }
}

fun runPsnrPass(
    comparison: File,
    reference: File,
    onProgress: (currentFrame: Int, totalFrames: Int?) -> Unit,
    isCancelled: () -> Boolean,
): MetricRunResult? {
    val statsFile = try {
        File.createTempFile("multiviewer_psnr_", ".log")
    } catch (e: Exception) {
        return null
    }
    return try {
        val success = runMetricPass(
            comparison, reference,
            filterSpec = "psnr=stats_file=${escapeForFilterGraph(statsFile.absolutePath)}",
            statsFile = statsFile, onProgress = onProgress, isCancelled = isCancelled,
        )
        if (!success) return null
        val perFrame = parsePsnrLog(statsFile)
        val statistics = computeStatistics(perFrame) ?: return null
        MetricRunResult(perFrame, statistics)
    } finally {
        statsFile.delete()
    }
}

fun runSsimPass(
    comparison: File,
    reference: File,
    onProgress: (currentFrame: Int, totalFrames: Int?) -> Unit,
    isCancelled: () -> Boolean,
): MetricRunResult? {
    val statsFile = try {
        File.createTempFile("multiviewer_ssim_", ".log")
    } catch (e: Exception) {
        return null
    }
    return try {
        val success = runMetricPass(
            comparison, reference,
            filterSpec = "ssim=stats_file=${escapeForFilterGraph(statsFile.absolutePath)}",
            statsFile = statsFile, onProgress = onProgress, isCancelled = isCancelled,
        )
        if (!success) return null
        val perFrame = parseSsimLog(statsFile)
        val statistics = computeStatistics(perFrame) ?: return null
        MetricRunResult(perFrame, statistics)
    } finally {
        statsFile.delete()
    }
}
