package com.multiviewer.parser.spatial

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.VideoCodec
import com.multiviewer.parser.buildVideoSampleTable
import java.io.File

enum class SpatialSeverity {
    ERROR,
    WARN,
    INFO,
}

data class SpatialAnomaly(
    val code: String,
    val severity: SpatialSeverity,
    val message: String,
    val frameIndex: Int? = null,
)

data class SpatialVideoMetadata(
    val hasSpatialBoxes: Boolean,
    val heroEye: String?,
    val baselineMm: Double?,
    val horizontalFovDeg: Double?,
    val disparityAdjustment: Double?,
    val stereoMode: String?,
    val lhvcSummary: String?,
)

data class SpatialLayerFrame(
    val frameIndex: Int,
    val dtsSeconds: Double,
    val ptsSeconds: Double,
    val layer0Bytes: Long,
    val layer1Bytes: Long,
    val layer0NalCount: Int,
    val layer1NalCount: Int,
    val isLayer0Keyframe: Boolean,
    val isLayer1Keyframe: Boolean,
)

data class SpatialVideoReport(
    val isSpatialVideo: Boolean,
    val metadata: SpatialVideoMetadata,
    val totalFrames: Int,
    val layer0TotalBytes: Long,
    val layer1TotalBytes: Long,
    val layer0KeyframeCount: Int,
    val layer1KeyframeCount: Int,
    val layerParityMatch: Boolean,
    val anomalies: List<SpatialAnomaly>,
    val frames: List<SpatialLayerFrame>,
)

private fun findBoxes(node: BoxNode, type: String): List<BoxNode> {
    val result = mutableListOf<BoxNode>()
    fun walk(curr: BoxNode) {
        if (curr.type == type) result.add(curr)
        curr.children.forEach { walk(it) }
    }
    walk(node)
    return result
}

fun extractSpatialMetadata(root: BoxNode): SpatialVideoMetadata {
    val lhvcNodes = findBoxes(root, "lhvC")
    val eyesNodes = findBoxes(root, "eyes")
    val heroNodes = findBoxes(root, "hero")
    val st3dNodes = findBoxes(root, "st3d")

    val hasSpatialBoxes = lhvcNodes.isNotEmpty() || eyesNodes.isNotEmpty() || heroNodes.isNotEmpty() || st3dNodes.isNotEmpty()

    val heroEye = heroNodes.firstOrNull()?.fields?.find { it.name == "hero_eye" }?.value
        ?: eyesNodes.firstOrNull()?.fields?.find { it.name == "hero_eye" }?.value

    val stereoMode = st3dNodes.firstOrNull()?.fields?.find { it.name == "stereo_mode" }?.value
        ?: eyesNodes.firstOrNull()?.fields?.find { it.name == "eye_views" }?.value

    val lhvcSummary = lhvcNodes.firstOrNull()?.summary

    // Extract Apple QuickTime spatial keys from metadata
    var baselineMm: Double? = null
    var horizontalFovDeg: Double? = null
    var disparityAdjustment: Double? = null

    fun searchMetadataFields(curr: BoxNode) {
        for (f in curr.fields) {
            val nameLower = f.name.lowercase()
            if (nameLower.contains("baseline")) {
                val rawVal = f.value.toDoubleOrNull()
                if (rawVal != null) {
                    // Usually in micrometers (e.g. 64000 um -> 64.0 mm)
                    baselineMm = if (rawVal > 1000.0) rawVal / 1000.0 else rawVal
                }
            } else if (nameLower.contains("field-of-view") || nameLower.contains("fov")) {
                val rawVal = f.value.toDoubleOrNull()
                if (rawVal != null) {
                    // Usually in millidegrees (e.g. 63400 -> 63.4 deg)
                    horizontalFovDeg = if (rawVal > 360.0) rawVal / 1000.0 else rawVal
                }
            } else if (nameLower.contains("disparity")) {
                disparityAdjustment = f.value.toDoubleOrNull()
            }
        }
        curr.children.forEach { searchMetadataFields(it) }
    }
    searchMetadataFields(root)

    return SpatialVideoMetadata(
        hasSpatialBoxes = hasSpatialBoxes,
        heroEye = heroEye,
        baselineMm = baselineMm,
        horizontalFovDeg = horizontalFovDeg,
        disparityAdjustment = disparityAdjustment,
        stereoMode = stereoMode,
        lhvcSummary = lhvcSummary,
    )
}

/**
 * Parses HEVC NAL layers per frame sample.
 */
fun analyzeSpatialVideo(
    file: File,
    root: BoxNode,
    maxFrames: Int = 100_000,
): SpatialVideoReport? {
    val metadata = extractSpatialMetadata(root)

    return ByteReader.open(file).use { reader ->
        val table = buildVideoSampleTable(reader, root, maxFrames) ?: return@use null
        if (table.codec != VideoCodec.HEVC) {
            return@use null
        }
        val nalLenSize = table.nalLengthSize ?: 4
        val timescale = table.timescale.coerceAtLeast(1L).toDouble()

        val frames = mutableListOf<SpatialLayerFrame>()
        val anomalies = mutableListOf<SpatialAnomaly>()

        var totalL0Bytes = 0L
        var totalL1Bytes = 0L
        var totalL0Keyframes = 0
        var totalL1Keyframes = 0

        for (sample in table.samples) {
            var pos = sample.offset
            val end = sample.offset + sample.size

            var l0Bytes = 0L
            var l1Bytes = 0L
            var l0Nals = 0
            var l1Nals = 0
            var l0Key = false
            var l1Key = false

            while (pos + nalLenSize + 2 <= end) {
                val nalLen = when (nalLenSize) {
                    4 -> reader.readUInt32(pos)
                    2 -> reader.readUInt16(pos).toLong()
                    1 -> reader.readUInt8(pos).toLong()
                    else -> 0L
                }
                pos += nalLenSize
                if (nalLen <= 0L || pos + nalLen > end) {
                    break
                }

                val byte0 = reader.readUInt8(pos)
                val byte1 = reader.readUInt8(pos + 1)
                val nalType = (byte0 shr 1) and 0x3F
                val layerId = ((byte0 and 0x01) shl 5) or ((byte1 shr 3) and 0x1F)

                val isKey = nalType in 16..21 // BLA, IDR, CRA

                if (layerId == 0) {
                    l0Bytes += nalLen
                    l0Nals++
                    if (isKey) l0Key = true
                } else {
                    l1Bytes += nalLen
                    l1Nals++
                    if (isKey) l1Key = true
                }

                pos += nalLen
            }

            if (l0Key) totalL0Keyframes++
            if (l1Key) totalL1Keyframes++
            totalL0Bytes += l0Bytes
            totalL1Bytes += l1Bytes

            // Asymmetric keyframe warning
            if (l1Nals > 0 && (l0Key != l1Key)) {
                anomalies.add(
                    SpatialAnomaly(
                        code = "ASYMMETRIC_KEYFRAME",
                        severity = SpatialSeverity.WARN,
                        message = "Frame #${sample.index}: Layer 0 (Key=$l0Key) and Layer 1 (Key=$l1Key) keyframe status mismatch",
                        frameIndex = sample.index,
                    ),
                )
            }

            val dts = sample.decodeTime / timescale
            val pts = (sample.decodeTime + sample.compositionOffset) / timescale

            frames.add(
                SpatialLayerFrame(
                    frameIndex = sample.index,
                    dtsSeconds = dts,
                    ptsSeconds = pts,
                    layer0Bytes = l0Bytes,
                    layer1Bytes = l1Bytes,
                    layer0NalCount = l0Nals,
                    layer1NalCount = l1Nals,
                    isLayer0Keyframe = l0Key,
                    isLayer1Keyframe = l1Key,
                ),
            )
        }

        val hasLayer1 = frames.any { it.layer1NalCount > 0 }
        val isSpatial = metadata.hasSpatialBoxes || hasLayer1

        if (metadata.hasSpatialBoxes && !hasLayer1) {
            anomalies.add(
                SpatialAnomaly(
                    code = "MISSING_LAYER_1",
                    severity = SpatialSeverity.ERROR,
                    message = "Spatial video metadata declared in container, but no Secondary View (Layer 1) NAL units found in bitstream",
                ),
            )
        }

        val parityMatch = hasLayer1 && frames.all { it.layer0NalCount > 0 && it.layer1NalCount > 0 }
        if (hasLayer1 && !parityMatch) {
            val missingL1Count = frames.count { it.layer1NalCount == 0 }
            anomalies.add(
                SpatialAnomaly(
                    code = "LAYER_PARITY_MISMATCH",
                    severity = SpatialSeverity.ERROR,
                    message = "Layer parity mismatch: $missingL1Count frame(s) missing Layer 1 secondary view slices",
                ),
            )
        }

        if (isSpatial && metadata.heroEye == null) {
            anomalies.add(
                SpatialAnomaly(
                    code = "MISSING_HERO_EYE",
                    severity = SpatialSeverity.INFO,
                    message = "Hero eye indicator not specified (defaults to Left Eye for 2D presentation)",
                ),
            )
        }

        SpatialVideoReport(
            isSpatialVideo = isSpatial,
            metadata = metadata,
            totalFrames = frames.size,
            layer0TotalBytes = totalL0Bytes,
            layer1TotalBytes = totalL1Bytes,
            layer0KeyframeCount = totalL0Keyframes,
            layer1KeyframeCount = totalL1Keyframes,
            layerParityMatch = parityMatch,
            anomalies = anomalies,
            frames = frames,
        )
    }
}
