package com.multiviewer.parser.hrd

data class VideoFrameItem(
    val index: Int,
    val sizeBytes: Long,
    val dtsSeconds: Double,
    val ptsSeconds: Double,
    val isKeyframe: Boolean,
)

data class HrdBufferPoint(
    val frameIndex: Int,
    val dtsSeconds: Double,
    val frameSizeBytes: Long,
    val bufferLevelBytes: Long,
    val occupancyPercent: Double,
    val isUnderflow: Boolean,
    val isWarning: Boolean,
    val isKeyframe: Boolean,
)

data class HrdSimulationReport(
    val cpbSizeBytes: Long,
    val bitrateBps: Long,
    val initialDelaySeconds: Double,
    val totalFrames: Int,
    val minOccupancyPercent: Double,
    val maxOccupancyPercent: Double,
    val avgOccupancyPercent: Double,
    val underflowCount: Int,
    val underflowFrames: List<Int>,
    val warningFrames: List<Int>,
    val points: List<HrdBufferPoint>,
)

/**
 * Simulates a Hypothetical Reference Decoder (HRD) / Video Buffer Verifier (VBV)
 * Coded Picture Buffer (CPB) occupancy over time.
 */
fun simulateHrdBuffer(
    frames: List<VideoFrameItem>,
    bitrateBps: Long,
    cpbSizeBytes: Long,
    initialDelaySeconds: Double = 0.5,
): HrdSimulationReport {
    if (frames.isEmpty() || cpbSizeBytes <= 0L || bitrateBps <= 0L) {
        return HrdSimulationReport(
            cpbSizeBytes = cpbSizeBytes,
            bitrateBps = bitrateBps,
            initialDelaySeconds = initialDelaySeconds,
            totalFrames = 0,
            minOccupancyPercent = 0.0,
            maxOccupancyPercent = 0.0,
            avgOccupancyPercent = 0.0,
            underflowCount = 0,
            underflowFrames = emptyList(),
            warningFrames = emptyList(),
            points = emptyList(),
        )
    }

    val bytesPerSec = bitrateBps / 8.0
    val initialBytes = (bytesPerSec * initialDelaySeconds).toLong().coerceIn(0L, cpbSizeBytes)
    var currentBuffer = initialBytes

    val points = mutableListOf<HrdBufferPoint>()
    val underflowFrames = mutableListOf<Int>()
    val warningFrames = mutableListOf<Int>()

    var prevDts = frames.first().dtsSeconds

    for ((i, frame) in frames.withIndex()) {
        val deltaT = if (i == 0) 0.0 else (frame.dtsSeconds - prevDts).coerceAtLeast(0.0)
        prevDts = frame.dtsSeconds

        val incomingBytes = (bytesPerSec * deltaT).toLong()
        val beforeRemoval = (currentBuffer + incomingBytes).coerceAtMost(cpbSizeBytes)
        currentBuffer = beforeRemoval - frame.sizeBytes

        val isUnderflow = currentBuffer < 0L
        val occupancy = (currentBuffer.toDouble() / cpbSizeBytes.toDouble() * 100.0)
        val isWarning = occupancy in 0.0..15.0

        if (isUnderflow) underflowFrames.add(frame.index)
        if (isWarning) warningFrames.add(frame.index)

        points.add(
            HrdBufferPoint(
                frameIndex = frame.index,
                dtsSeconds = frame.dtsSeconds,
                frameSizeBytes = frame.sizeBytes,
                bufferLevelBytes = currentBuffer,
                occupancyPercent = occupancy,
                isUnderflow = isUnderflow,
                isWarning = isWarning,
                isKeyframe = frame.isKeyframe,
            ),
        )
    }

    val occupancies = points.map { it.occupancyPercent }
    val minOccupancy = occupancies.minOrNull() ?: 0.0
    val maxOccupancy = occupancies.maxOrNull() ?: 0.0
    val avgOccupancy = if (occupancies.isNotEmpty()) occupancies.average() else 0.0

    return HrdSimulationReport(
        cpbSizeBytes = cpbSizeBytes,
        bitrateBps = bitrateBps,
        initialDelaySeconds = initialDelaySeconds,
        totalFrames = frames.size,
        minOccupancyPercent = minOccupancy,
        maxOccupancyPercent = maxOccupancy,
        avgOccupancyPercent = avgOccupancy,
        underflowCount = underflowFrames.size,
        underflowFrames = underflowFrames,
        warningFrames = warningFrames,
        points = points,
    )
}
