package com.multiviewer.ui

import java.io.File
import java.util.concurrent.TimeUnit

data class FrameInfo(val index: Int, val type: Char, val sizeBytes: Int, val ptsSeconds: Double)

// ffprobe's CSV output (-of csv=p=0) does NOT preserve the field order given in -show_entries,
// and its field count is inconsistent between frames (verified directly against a real video:
// some rows had an unexpected trailing empty field). -of default=noprint_wrappers=1 instead
// prints reliable "key=value" lines, always in the fixed order pts_time, pkt_size, pict_type per
// frame -- accumulate them into a map and finalize one FrameInfo each time pict_type (always last)
// is seen.
fun probeFrameTypes(file: File): List<FrameInfo>? {
    return try {
        val process = ProcessBuilder(
            FfmpegLocator.ffprobePath(), "-v", "error", "-select_streams", "v:0",
            "-show_entries", "frame=pict_type,pkt_size,pts_time",
            "-of", "default=noprint_wrappers=1", file.absolutePath,
        ).redirectErrorStream(false).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val lines = process.inputStream.bufferedReader().readLines()
        process.waitFor(120, TimeUnit.SECONDS)

        val values = mutableMapOf<String, String>()
        val frames = mutableListOf<FrameInfo>()
        for (line in lines) {
            val eq = line.indexOf('=')
            if (eq < 0) continue
            val key = line.substring(0, eq)
            val value = line.substring(eq + 1)
            values[key] = value
            if (key == "pict_type") {
                val pts = values["pts_time"]?.toDoubleOrNull()
                val size = values["pkt_size"]?.toIntOrNull()
                val type = value.firstOrNull()
                if (pts != null && size != null && type != null) {
                    frames.add(FrameInfo(frames.size, type, size, pts))
                }
                values.clear()
            }
        }
        if (frames.isEmpty()) null else frames
    } catch (e: Exception) {
        null
    }
}
