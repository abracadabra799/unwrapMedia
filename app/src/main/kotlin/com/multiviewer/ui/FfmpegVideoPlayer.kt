package com.multiviewer.ui

import java.io.File
import java.util.concurrent.TimeUnit

data class VideoInfo(val width: Int, val height: Int, val fps: Double)

fun parseFrameRate(fraction: String): Double? {
    val parts = fraction.split("/")
    val num = parts.getOrNull(0)?.toDoubleOrNull() ?: return null
    val den = parts.getOrNull(1)?.toDoubleOrNull() ?: return null
    if (den == 0.0 || num == 0.0) return null
    return num / den
}

fun probeVideo(file: File): VideoInfo? {
    return try {
        val process = ProcessBuilder(
            "ffprobe", "-v", "error", "-select_streams", "v:0",
            "-show_entries", "stream=width,height,avg_frame_rate,r_frame_rate",
            "-of", "csv=p=0", file.absolutePath,
        ).redirectErrorStream(false).start()
        val line = process.inputStream.bufferedReader().readLine()
        process.waitFor(5, TimeUnit.SECONDS)
        if (line == null) return null
        val parts = line.split(",")
        if (parts.size < 4) return null
        val width = parts[0].toIntOrNull() ?: return null
        val height = parts[1].toIntOrNull() ?: return null
        val fps = parseFrameRate(parts[2]) ?: parseFrameRate(parts[3]) ?: 30.0
        VideoInfo(width, height, fps)
    } catch (e: Exception) {
        null
    }
}
