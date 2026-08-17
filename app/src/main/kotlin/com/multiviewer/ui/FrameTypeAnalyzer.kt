package com.multiviewer.ui

import com.multiviewer.cache.MediaIndexCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

data class FrameInfo(val index: Int, val type: Char, val sizeBytes: Int, val ptsSeconds: Double, val byteOffset: Long? = null)

data class FrameAnalysisProgress(
    val frames: List<FrameInfo>,
    val loadedCount: Int,
    val estimatedTotal: Int?,
    val isComplete: Boolean,
)

fun probeFrameTypesStreaming(
    file: File,
    estimatedDurationSec: Double? = null,
    estimatedFps: Double? = null,
    chunkSize: Int = 1000,
): Flow<FrameAnalysisProgress> = flow {
    val cached = MediaIndexCache.get(file)
    if (cached != null) {
        emit(FrameAnalysisProgress(cached, cached.size, cached.size, isComplete = true))
        return@flow
    }

    val estimatedTotal = if (estimatedDurationSec != null && estimatedFps != null && estimatedFps > 0.0) {
        (estimatedDurationSec * estimatedFps).toInt().coerceAtLeast(1)
    } else null

    try {
        val process = ProcessBuilder(
            FfmpegLocator.ffprobePath(), "-v", "error", "-select_streams", "v:0",
            "-show_entries", "frame=pict_type,pkt_size,pts_time,pkt_pos",
            "-of", "default=noprint_wrappers=1", file.absolutePath,
        ).redirectErrorStream(false).redirectError(ProcessBuilder.Redirect.DISCARD)
            .also { FfmpegLocator.configureEnvironment(it) }.start()

        val values = mutableMapOf<String, String>()
        val frames = ArrayList<FrameInfo>(estimatedTotal ?: 10000)
        var chunkCount = 0

        process.inputStream.bufferedReader().useLines { lines ->
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
                        chunkCount++
                        if (chunkCount >= chunkSize) {
                            emit(
                                FrameAnalysisProgress(
                                    frames = ArrayList(frames),
                                    loadedCount = frames.size,
                                    estimatedTotal = estimatedTotal,
                                    isComplete = false,
                                )
                            )
                            chunkCount = 0
                        }
                    }
                    values.clear()
                }
            }
        }
        process.waitFor()

        if (frames.isNotEmpty()) {
            MediaIndexCache.put(file, frames)
        }
        emit(FrameAnalysisProgress(frames, frames.size, estimatedTotal, isComplete = true))
    } catch (e: Exception) {
        emit(FrameAnalysisProgress(emptyList(), 0, estimatedTotal, isComplete = true))
    }
}.flowOn(Dispatchers.IO)

fun probeFrameTypes(file: File): List<FrameInfo>? {
    val cached = MediaIndexCache.get(file)
    if (cached != null) return cached

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
        if (frames.isEmpty()) null else {
            MediaIndexCache.put(file, frames)
            frames
        }
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
