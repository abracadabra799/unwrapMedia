package com.multiviewer.ui

import java.io.File

// byteOffset is the frame's own packet position in the file (ffprobe's pkt_pos) -- lets the UI
// jump the hex viewer straight to a selected frame's actual bytes, the same way tile/item
// selection elsewhere in this app already drives a hex highlight from a real byte range. null
// when ffprobe doesn't report a position for a given frame (rare, but not guaranteed present for
// every container/codec).
data class FrameInfo(val index: Int, val type: Char, val sizeBytes: Int, val ptsSeconds: Double, val byteOffset: Long? = null)

// ffprobe's CSV output (-of csv=p=0) does NOT preserve the field order given in -show_entries,
// and its field count is inconsistent between frames (verified directly against a real video:
// some rows had an unexpected trailing empty field). -of default=noprint_wrappers=1 instead
// prints reliable "key=value" lines, always in the fixed order pts_time, pkt_size, pkt_pos,
// pict_type per frame -- accumulate them into a map and finalize one FrameInfo each time
// pict_type (always last) is seen.
fun probeFrameTypes(file: File): List<FrameInfo>? {
    return try {
        val process = ProcessBuilder(
            FfmpegLocator.ffprobePath(), "-v", "error", "-select_streams", "v:0",
            "-show_entries", "frame=pict_type,pkt_size,pts_time,pkt_pos",
            "-of", "default=noprint_wrappers=1", file.absolutePath,
        ).redirectErrorStream(false).redirectError(ProcessBuilder.Redirect.DISCARD)
            .also { FfmpegLocator.configureEnvironment(it) }.start()
        val lines = readProcessOutputWithTimeout(process, 120) { process.inputStream.bufferedReader().readLines() }
            ?: return null

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
                val byteOffset = values["pkt_pos"]?.toLongOrNull()
                if (pts != null && size != null && type != null) {
                    frames.add(FrameInfo(frames.size, type, size, pts, byteOffset))
                }
                values.clear()
            }
        }
        if (frames.isEmpty()) null else frames
    } catch (e: Exception) {
        null
    }
}

// Distance (in frames) to the nearest preceding keyframe, and that keyframe's own index --
// together, "how far into this GOP is this frame." I-frames are their own keyframe (distance 0).
// Returns null only if index is out of bounds or no preceding I-frame exists (a malformed/partial
// GOP at the very start of a file, which shouldn't happen in practice since frame 0 is always I
// in every real file this has been tested against, but isn't assumed here).
data class GopPosition(val keyframeIndex: Int, val distanceFromKeyframe: Int)

fun gopPositionOf(frames: List<FrameInfo>, index: Int): GopPosition? {
    if (index !in frames.indices) return null
    val keyframeIndex = (index downTo 0).firstOrNull { frames[it].type == 'I' } ?: return null
    return GopPosition(keyframeIndex, index - keyframeIndex)
}

// The frame at the current playback position -- the last frame whose own pts has already passed,
// or -1 before playback has started (playbackElapsedSeconds <= 0.0, the default before the first
// FfmpegVideoPlayer position callback). Shared by GopAnalysisView (its own bar-chart highlight/
// auto-scroll) and FrameThumbnailFilmstrip (same behavior for its thumbnail cells) so both views
// track the same frame during playback without duplicating this lookup.
fun currentFrameIndex(frames: List<FrameInfo>, playbackElapsedSeconds: Double): Int =
    if (playbackElapsedSeconds <= 0.0) -1
    else frames.indexOfLast { it.ptsSeconds <= playbackElapsedSeconds }
