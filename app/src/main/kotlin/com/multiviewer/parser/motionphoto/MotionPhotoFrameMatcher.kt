package com.multiviewer.parser.motionphoto

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.VideoSampleTable
import com.multiviewer.parser.findFirst
import com.multiviewer.parser.findPresentationTimestampUs
import com.multiviewer.parser.parseXmpDocument
import kotlin.math.abs

enum class FrameAlignmentVerdict {
    EXACT_KEYFRAME,       // 오차 <= 1ms (1,000 us) & 키프레임(I-Frame) 정렬
    ALIGNED_NON_KEYFRAME, // 오차 <= 평균 1 프레임 지속 시간
    MISALIGNED,           // 오차 > 평균 1 프레임 지속 시간
    OUT_OF_BOUNDS,        // 비디오 duration 범위(0 ~ duration)를 벗어남
}

data class FrameMatchResult(
    val presentationTimestampUs: Long,
    val matchedSampleIndex: Int,          // 0-based
    val matchedFrameNumber: Int,          // 1-based (matchedSampleIndex + 1)
    val matchedPtsUs: Long,
    val deltaUs: Long,                    // matchedPtsUs - presentationTimestampUs
    val isKeyframe: Boolean,
    val videoTimescale: Long,
    val totalSamples: Int,
    val videoDurationUs: Long,
    val fps: Double,
    val relativePositionPercent: Double,  // 0.0 ~ 100.0%
    val alignmentVerdict: FrameAlignmentVerdict,
    val warnings: List<String> = emptyList(),
)

/**
 * Matches a requested presentation timestamp (in microseconds) against an ISO-BMFF VideoSampleTable.
 * Calculates exact PTS for each sample including composition offsets (ctts), finds the closest sample,
 * and determines the alignment verdict and delta.
 */
fun matchFrameToTimestamp(sampleTable: VideoSampleTable, targetTimestampUs: Long): FrameMatchResult? {
    val samples = sampleTable.samples
    if (samples.isEmpty() || sampleTable.timescale <= 0L) return null

    val timescale = sampleTable.timescale
    // Convert sample ticks to microseconds: (ticks * 1_000_000L) / timescale
    val ptsList = samples.map { sample ->
        val ptsTicks = sample.decodeTime + sample.compositionOffset
        (ptsTicks * 1_000_000L) / timescale
    }

    // Determine total duration
    val lastSample = samples.last()
    val lastDurationTicks = (samples.getOrNull(samples.size - 2)?.let { lastSample.decodeTime - it.decodeTime })
        ?.coerceAtLeast(1L) ?: 1L
    val totalDurationTicks = (lastSample.decodeTime + lastDurationTicks).coerceAtLeast(1L)
    val videoDurationUs = (totalDurationTicks * 1_000_000L) / timescale

    // Find the closest sample
    var bestIndex = 0
    var minDiff = Long.MAX_VALUE
    for (i in ptsList.indices) {
        val diff = abs(ptsList[i] - targetTimestampUs)
        if (diff < minDiff) {
            minDiff = diff
            bestIndex = i
        }
    }

    val matchedSample = samples[bestIndex]
    val matchedPtsUs = ptsList[bestIndex]
    val deltaUs = matchedPtsUs - targetTimestampUs
    val totalSamples = samples.size
    val durationSeconds = videoDurationUs.toDouble() / 1_000_000.0
    val fps = if (durationSeconds > 0.0) totalSamples / durationSeconds else 0.0
    val avgFrameDurationUs = if (totalSamples > 0) videoDurationUs / totalSamples else 33_333L

    val isOutOfBounds = targetTimestampUs < 0L || targetTimestampUs > videoDurationUs
    val verdict = when {
        isOutOfBounds -> FrameAlignmentVerdict.OUT_OF_BOUNDS
        abs(deltaUs) <= 1_000L && matchedSample.isSync -> FrameAlignmentVerdict.EXACT_KEYFRAME
        abs(deltaUs) <= avgFrameDurationUs -> FrameAlignmentVerdict.ALIGNED_NON_KEYFRAME
        else -> FrameAlignmentVerdict.MISALIGNED
    }

    val relativePercent = if (videoDurationUs > 0L) {
        (targetTimestampUs.toDouble() / videoDurationUs.toDouble() * 100.0).coerceIn(0.0, 100.0)
    } else {
        0.0
    }

    return FrameMatchResult(
        presentationTimestampUs = targetTimestampUs,
        matchedSampleIndex = bestIndex,
        matchedFrameNumber = bestIndex + 1,
        matchedPtsUs = matchedPtsUs,
        deltaUs = deltaUs,
        isKeyframe = matchedSample.isSync,
        videoTimescale = timescale,
        totalSamples = totalSamples,
        videoDurationUs = videoDurationUs,
        fps = fps,
        relativePositionPercent = relativePercent,
        alignmentVerdict = verdict,
        warnings = sampleTable.warnings,
    )
}

/**
 * Searches the box tree for still image presentation timestamps from:
 * 1. Google / Samsung XMP (MotionPhotoPresentationTimestampUs, MicroVideoPresentationTimestampUs)
 * 2. Apple QuickTime metadata (com.apple.quicktime.live-photo.still-image-time or still-image-time)
 */
fun extractPresentationTimestampUs(root: BoxNode): Long? {
    // 1. Google / Samsung XMP
    val xmpField = findFirst(root) { it.fields.any { f -> f.name == "xmp" } }
        ?.fields?.find { it.name == "xmp" }?.value
    if (xmpField != null) {
        try {
            val doc = parseXmpDocument(xmpField)
            findPresentationTimestampUs(doc)?.let { return it }
        } catch (_: Throwable) {
            // Ignore XMP parse errors and continue to other metadata
        }
    }

    // 2. Apple QuickTime / Live Photo metadata
    val appleStillTimeField = findFirst(root) { node ->
        node.fields.any { f ->
            f.name.contains("still-image-time", ignoreCase = true)
        }
    }?.fields?.find { it.name.contains("still-image-time", ignoreCase = true) }?.value

    if (appleStillTimeField != null) {
        appleStillTimeField.toLongOrNull()?.let { return it }
    }

    return null
}
