package com.multiviewer.ui

import com.multiviewer.util.ProcessManager
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.abs

data class AudioTimelinePacket(
    val index: Int,
    val ptsSeconds: Double,
    val durationSeconds: Double?,
    val sizeBytes: Int?,
    val byteOffset: Long?,
)

data class AudioTimelinePackets(val packets: List<AudioTimelinePacket>, val truncated: Boolean)

data class TimelineTimeRange(val startSeconds: Double, val endSeconds: Double) {
    val durationSeconds: Double get() = endSeconds - startSeconds
}

fun timelineTimeAtFraction(fraction: Float, range: TimelineTimeRange, view: AudioViewWindow): Double =
    range.startSeconds + view.startSeconds + fraction.coerceIn(0f, 1f) * view.durationSeconds

fun timelineFraction(seconds: Double, range: TimelineTimeRange, view: AudioViewWindow): Float =
    ((seconds - range.startSeconds - view.startSeconds) / view.durationSeconds).toFloat()

/** `ffprobe -of compact=p=0:nk=0` emits optional trailing `|` and may omit N/A values. */
internal fun parseAudioTimelinePacket(line: String, index: Int): AudioTimelinePacket? {
    val values = line.split('|').mapNotNull { part ->
        val eq = part.indexOf('=')
        if (eq <= 0) null else part.substring(0, eq) to part.substring(eq + 1)
    }.toMap()
    val pts = values["pts_time"]?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
    return AudioTimelinePacket(
        index, pts,
        values["duration_time"]?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 },
        values["size"]?.toIntOrNull(), values["pos"]?.toLongOrNull(),
    )
}

/** Bounded first-audio-stream packet index; a cap or deadline must never appear complete. */
fun probeAudioTimelinePackets(
    file: File,
    maxPackets: Int = 200_000,
    timeoutMillis: Long = TimeUnit.SECONDS.toMillis(30),
    isCancelled: () -> Boolean = { false },
): AudioTimelinePackets? {
    require(maxPackets > 0)
    var process: Process? = null
    var watch: ProcessManager.Watch? = null
    val worker = Thread.currentThread()
    return try {
        process = ProcessManager.register(
            ProcessBuilder(
                FfmpegLocator.ffprobePath(), "-v", "error", "-select_streams", "a:0",
                "-show_entries", "packet=pts_time,duration_time,size,pos",
                "-of", "compact=p=0:nk=0", file.absolutePath,
            ).redirectError(ProcessBuilder.Redirect.DISCARD)
                .also { FfmpegLocator.configureEnvironment(it) }.start()
        )
        watch = ProcessManager.watchCancellation(process, timeoutMillis) { isCancelled() || worker.isInterrupted }
        val packets = ArrayList<AudioTimelinePacket>(minOf(maxPackets, 8192))
        var truncated = false
        var sourceIndex = 0
        process.inputStream.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (isCancelled() || worker.isInterrupted) return null
                val packet = parseAudioTimelinePacket(line, sourceIndex++) ?: continue
                if (packets.size == maxPackets) {
                    truncated = true
                    break
                }
                packets.add(packet)
            }
        }
        if (isCancelled() || worker.isInterrupted || watch.timedOut) return null
        if (!truncated && process.waitFor() != 0) return null
        AudioTimelinePackets(packets.sortedBy { it.ptsSeconds }, truncated)
    } catch (_: Exception) {
        null
    } finally {
        watch?.close()
        ProcessManager.terminate(process)
    }
}

fun audioVideoTimelineRange(
    frames: List<FrameInfo>,
    audio: AudioFileInfo?,
    packets: List<AudioTimelinePacket>,
): TimelineTimeRange {
    val firstFrame = frames.minOfOrNull { it.ptsSeconds }
    val lastFrame = frames.maxOfOrNull { it.ptsSeconds }
    val frameTail = if (frames.size > 1 && firstFrame != null && lastFrame != null)
        ((lastFrame - firstFrame) / (frames.size - 1)).coerceIn(0.001, 1.0) else 1.0 / 30.0
    val start = listOfNotNull(0.0, firstFrame, audio?.startTimeSeconds, packets.minOfOrNull { it.ptsSeconds }).minOrNull() ?: 0.0
    val end = listOfNotNull(
        lastFrame?.plus(frameTail), audio?.let { it.startTimeSeconds + it.duration },
        packets.maxOfOrNull { it.ptsSeconds + (it.durationSeconds ?: 0.0) },
    ).maxOrNull()?.coerceAtLeast(start + MIN_VISIBLE_DURATION_SECONDS) ?: start + MIN_VISIBLE_DURATION_SECONDS
    return TimelineTimeRange(start, end)
}

/** Finds a packet covering [ptsSeconds], or one within [maxDistanceSeconds]; distant partial indexes return null. */
fun nearestAudioTimelinePacket(
    packets: List<AudioTimelinePacket>,
    ptsSeconds: Double,
    maxDistanceSeconds: Double = 0.1,
): AudioTimelinePacket? {
    if (packets.isEmpty() || !ptsSeconds.isFinite()) return null
    var low = 0
    var high = packets.size
    while (low < high) {
        val mid = (low + high) ushr 1
        if (packets[mid].ptsSeconds < ptsSeconds) low = mid + 1 else high = mid
    }
    val before = packets.getOrNull(low - 1)
    val after = packets.getOrNull(low)
    if (after != null && abs(after.ptsSeconds - ptsSeconds) < 1e-9) return after
    if (before != null && ptsSeconds < before.ptsSeconds + (before.durationSeconds ?: 0.0)) return before
    val nearest = listOfNotNull(before, after).minByOrNull { abs(it.ptsSeconds - ptsSeconds) } ?: return null
    return nearest.takeIf { abs(it.ptsSeconds - ptsSeconds) <= maxDistanceSeconds }
}

/** Steps through the frames in timeline (presentation) order, using the shared selected frame. */
fun stepTimelineFrame(frames: List<FrameInfo>, selected: FrameInfo?, delta: Int): FrameInfo? {
    if (frames.isEmpty()) return null
    val current = frames.indexOfFirst { it.index == selected?.index }
    return frames[if (current < 0) 0 else (current + delta).coerceIn(frames.indices)]
}

data class TimelineStepResult(
    val frame: FrameInfo?,
    val packet: AudioTimelinePacket?,
    val cursorPtsSeconds: Double,
)

/**
 * Steps through the timeline across both video frames and audio packets.
 * When audio extends past the last video frame (or starts before the first video frame),
 * stepping past the video boundary continues through the remaining audio packets so the
 * timeline bar can reach the true end/start of the media.
 */
fun stepTimelineCursor(
    orderedFrames: List<FrameInfo>,
    packets: List<AudioTimelinePacket>,
    selectedFrame: FrameInfo?,
    chosenPacket: AudioTimelinePacket?,
    delta: Int,
): TimelineStepResult? {
    if (orderedFrames.isEmpty() && packets.isEmpty()) return null
    if (orderedFrames.isEmpty()) {
        val currentIdx = packets.indexOfFirst { it.index == chosenPacket?.index }
        val nextIdx = if (currentIdx < 0) 0 else (currentIdx + delta).coerceIn(packets.indices)
        val pkt = packets[nextIdx]
        return TimelineStepResult(frame = null, packet = pkt, cursorPtsSeconds = pkt.ptsSeconds)
    }

    val firstVideoPts = orderedFrames.first().ptsSeconds
    val lastVideoPts = orderedFrames.last().ptsSeconds

    if (chosenPacket != null && packets.isNotEmpty()) {
        val currentPktIdx = packets.indexOfFirst { it.index == chosenPacket.index }
            .takeIf { it >= 0 }
            ?: packets.indices.minByOrNull { abs(packets[it].ptsSeconds - chosenPacket.ptsSeconds) }
            ?: 0
        val nextPktIdx = (currentPktIdx + delta).coerceIn(packets.indices)
        val nextPacket = packets[nextPktIdx]
        // If we were in the audio tail past the last video frame and stepped Left back onto/before the last video frame,
        // return to video-frame stepping at the last video frame.
        if (delta < 0 && chosenPacket.ptsSeconds > lastVideoPts + 1e-6 && nextPacket.ptsSeconds <= lastVideoPts + 1e-6) {
            val lastFrame = orderedFrames.last()
            return TimelineStepResult(frame = lastFrame, packet = null, cursorPtsSeconds = lastFrame.ptsSeconds)
        }
        // Symmetrically, if we were in the audio head before the first video frame and stepped Right into the video range:
        if (delta > 0 && chosenPacket.ptsSeconds < firstVideoPts - 1e-6 && nextPacket.ptsSeconds >= firstVideoPts - 1e-6) {
            val firstFrame = orderedFrames.first()
            return TimelineStepResult(frame = firstFrame, packet = null, cursorPtsSeconds = firstFrame.ptsSeconds)
        }
        val nearestFrame = orderedFrames.minByOrNull { abs(it.ptsSeconds - nextPacket.ptsSeconds) }
        return TimelineStepResult(frame = nearestFrame, packet = nextPacket, cursorPtsSeconds = nextPacket.ptsSeconds)
    }

    val currentFrameIdx = orderedFrames.indexOfFirst { it.index == selectedFrame?.index }
    if (currentFrameIdx < 0) {
        val first = orderedFrames.first()
        return TimelineStepResult(frame = first, packet = null, cursorPtsSeconds = first.ptsSeconds)
    }

    val targetFrameIdx = currentFrameIdx + delta
    if (targetFrameIdx in orderedFrames.indices) {
        val nextFrame = orderedFrames[targetFrameIdx]
        return TimelineStepResult(frame = nextFrame, packet = null, cursorPtsSeconds = nextFrame.ptsSeconds)
    }

    // Stepping past the last video frame: continue into trailing audio packets if any exist.
    if (targetFrameIdx > orderedFrames.lastIndex && packets.isNotEmpty()) {
        val firstTailPacket = packets.firstOrNull { it.ptsSeconds > lastVideoPts + 1e-6 }
        if (firstTailPacket != null) {
            return TimelineStepResult(
                frame = orderedFrames.last(),
                packet = firstTailPacket,
                cursorPtsSeconds = firstTailPacket.ptsSeconds,
            )
        }
    }

    // Stepping before the first video frame: continue into leading audio packets if any exist.
    if (targetFrameIdx < 0 && packets.isNotEmpty()) {
        val lastHeadPacket = packets.lastOrNull { it.ptsSeconds < firstVideoPts - 1e-6 }
        if (lastHeadPacket != null) {
            return TimelineStepResult(
                frame = orderedFrames.first(),
                packet = lastHeadPacket,
                cursorPtsSeconds = lastHeadPacket.ptsSeconds,
            )
        }
    }

    val clampedFrame = orderedFrames[targetFrameIdx.coerceIn(orderedFrames.indices)]
    return TimelineStepResult(frame = clampedFrame, packet = null, cursorPtsSeconds = clampedFrame.ptsSeconds)
}
