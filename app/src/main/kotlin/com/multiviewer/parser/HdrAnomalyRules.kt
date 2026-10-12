package com.multiviewer.parser

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

enum class HdrSeverity { INFO, WARN, ERROR }
enum class HdrRule { HDR10PLUS_MISSING, ABRUPT_CHANGE, STATIC_DYNAMIC_MISMATCH, CONTAINER_INBAND_MISMATCH }
enum class HdrVerdict { PASS, WARN, FAIL }

data class HdrAnomaly(val rule: HdrRule, val severity: HdrSeverity, val firstFrame: Int?, val lastFrame: Int?, val message: String)

data class HdrThresholds(val relativeChange: Double = 0.5, val absoluteChangeNits: Double = 100.0)

private fun f(format: String, vararg args: Any): String = String.format(Locale.US, format, *args)

fun detectHdrAnomalies(records: List<FrameHdrRecord>, container: ContainerHdrStatic, thresholds: HdrThresholds = HdrThresholds()): List<HdrAnomaly> =
    missingHdr10Plus(records) + abruptChanges(records, thresholds) + staticDynamicMismatches(records, container) + containerInBandMismatches(records, container)

fun hdrVerdict(anomalies: List<HdrAnomaly>): HdrVerdict = when {
    anomalies.any { it.severity == HdrSeverity.ERROR } -> HdrVerdict.FAIL
    anomalies.any { it.severity == HdrSeverity.WARN } -> HdrVerdict.WARN
    else -> HdrVerdict.PASS
}

private fun missingHdr10Plus(records: List<FrameHdrRecord>): List<HdrAnomaly> {
    val withPlus = records.count { it.hdr10Plus != null }
    if (withPlus == 0 || withPlus == records.size) return emptyList()
    val out = mutableListOf<HdrAnomaly>()
    var i = 0
    while (i < records.size) {
        if (records[i].hdr10Plus != null) { i++; continue }
        val start = i
        while (i < records.size && records[i].hdr10Plus == null) i++
        val range = records.subList(start, i)
        val hasKeyframe = range.any { it.isSync }
        out += HdrAnomaly(
            HdrRule.HDR10PLUS_MISSING, if (hasKeyframe) HdrSeverity.ERROR else HdrSeverity.WARN,
            range.first().frameNumber, range.last().frameNumber,
            "HDR10+ missing on ${range.size} frame(s)" + if (hasKeyframe) " including a keyframe" else "",
        )
    }
    return out
}

private fun abruptChanges(records: List<FrameHdrRecord>, t: HdrThresholds): List<HdrAnomaly> {
    val out = mutableListOf<HdrAnomaly>()
    for (i in 1 until records.size) {
        val prev = records[i - 1].hdr10Plus?.windows?.firstOrNull() ?: continue
        val cur = records[i].hdr10Plus?.windows?.firstOrNull() ?: continue
        val changes = listOfNotNull(
            describeJump("MaxSCL", prev.maxSclNits, cur.maxSclNits, t),
            describeJump("average maxRGB", prev.averageMaxrgbNits, cur.averageMaxrgbNits, t),
        )
        if (changes.isEmpty()) continue
        val keyframe = records[i].isSync
        out += HdrAnomaly(
            HdrRule.ABRUPT_CHANGE, if (keyframe) HdrSeverity.INFO else HdrSeverity.WARN,
            records[i].frameNumber, records[i].frameNumber,
            changes.joinToString("; ") + if (keyframe) " (keyframe, likely scene cut)" else "",
        )
    }
    return out
}

private fun describeJump(name: String, from: Double, to: Double, t: HdrThresholds): String? {
    val delta = abs(to - from)
    val relative = when {
        from > 0.0 -> delta / from
        delta > 0.0 -> Double.POSITIVE_INFINITY
        else -> 0.0
    }
    if (delta < t.absoluteChangeNits || relative < t.relativeChange) return null
    return f("%s %.1f → %.1f nits", name, from, to)
}

private fun staticDynamicMismatches(records: List<FrameHdrRecord>, container: ContainerHdrStatic): List<HdrAnomaly> {
    val plus = records.mapNotNull { r -> r.hdr10Plus?.let { r to it } }
    if (plus.isEmpty()) return emptyList()
    val mdcv = container.masteringDisplay ?: records.firstNotNullOfOrNull { it.masteringDisplay }
    val cll = container.contentLightLevel ?: records.firstNotNullOfOrNull { it.contentLightLevel }
    val out = mutableListOf<HdrAnomaly>()
    fun aggregate(frames: List<FrameHdrRecord>, message: String) {
        if (frames.isNotEmpty()) {
            out += HdrAnomaly(HdrRule.STATIC_DYNAMIC_MISMATCH, HdrSeverity.WARN, frames.first().frameNumber, frames.last().frameNumber, message)
        }
    }
    if (mdcv != null) {
        val over = plus.filter { (_, m) -> m.windows.first().maxSclNits > mdcv.maxLuminance + 1e-9 }
        aggregate(
            over.map { it.first },
            f("MaxSCL exceeds mastering display max %.0f nits on %d frame(s)", mdcv.maxLuminance, over.size),
        )
        val aboveTarget = plus.filter { (_, m) -> m.targetedSystemDisplayMaximumLuminance > mdcv.maxLuminance + 1e-9 }
        aggregate(
            aboveTarget.map { it.first },
            f("targeted_system_display_maximum_luminance above mastering display max %.0f nits on %d frame(s)", mdcv.maxLuminance, aboveTarget.size),
        )
    }
    val zeroTarget = plus.filter { (_, m) -> m.targetedSystemDisplayMaximumLuminance == 0L }
    aggregate(zeroTarget.map { it.first }, "targeted_system_display_maximum_luminance is 0 on ${zeroTarget.size} frame(s)")
    if (cll != null && cll.maxCll > 0) {
        val (peakRecord, peak) = plus.maxBy { it.second.windows.first().maxSclNits }
        val peakNits = peak.windows.first().maxSclNits
        if (peakNits > cll.maxCll) {
            out += HdrAnomaly(
                HdrRule.STATIC_DYNAMIC_MISMATCH, HdrSeverity.WARN, peakRecord.frameNumber, peakRecord.frameNumber,
                f("Stream peak MaxSCL %.1f nits exceeds MaxCLL %d", peakNits, cll.maxCll),
            )
        }
    }
    if (cll != null && cll.maxFall > 0) {
        val (peakRecord, peak) = plus.maxBy { it.second.windows.first().averageMaxrgbNits }
        val peakNits = peak.windows.first().averageMaxrgbNits
        if (peakNits > cll.maxFall) {
            out += HdrAnomaly(
                HdrRule.STATIC_DYNAMIC_MISMATCH, HdrSeverity.WARN, peakRecord.frameNumber, peakRecord.frameNumber,
                f("Stream peak average maxRGB %.1f nits exceeds MaxFALL %d", peakNits, cll.maxFall),
            )
        }
    }
    return out
}

private fun containerInBandMismatches(records: List<FrameHdrRecord>, container: ContainerHdrStatic): List<HdrAnomaly> {
    val out = mutableListOf<HdrAnomaly>()
    compareStatic(
        "Mastering display", container.masteringDisplay,
        records.mapNotNull { r -> r.masteringDisplay?.let { r to it } }, ::sameMasteringDisplay, out,
    )
    compareStatic(
        "Content light level", container.contentLightLevel,
        records.mapNotNull { r -> r.contentLightLevel?.let { r to it } }, { a, b -> a == b }, out,
    )
    return out
}

private fun <T> compareStatic(
    label: String,
    boxValue: T?,
    inBand: List<Pair<FrameHdrRecord, T>>,
    same: (T, T) -> Boolean,
    out: MutableList<HdrAnomaly>,
) {
    val first = inBand.firstOrNull()
    if (boxValue != null && first == null) {
        out += HdrAnomaly(HdrRule.CONTAINER_INBAND_MISMATCH, HdrSeverity.INFO, null, null, "$label present in container box only")
    }
    if (first != null) {
        val frame = first.first.frameNumber
        if (boxValue == null) {
            out += HdrAnomaly(HdrRule.CONTAINER_INBAND_MISMATCH, HdrSeverity.INFO, frame, null, "$label present in-band only (no container box)")
        } else if (!same(boxValue, first.second)) {
            out += HdrAnomaly(
                HdrRule.CONTAINER_INBAND_MISMATCH, HdrSeverity.WARN, frame, frame,
                "$label differs between container box and in-band (frame $frame)",
            )
        }
    }
    if (first != null) {
        val changed = inBand.firstOrNull { !same(it.second, first.second) }
        if (changed != null) {
            out += HdrAnomaly(
                HdrRule.CONTAINER_INBAND_MISMATCH, HdrSeverity.INFO, changed.first.frameNumber, changed.first.frameNumber,
                "In-band $label changes mid-stream at frame ${changed.first.frameNumber}",
            )
        }
    }
}

// Equal within one quantization step of the coarser of the two encodings, per field.
fun sameMasteringDisplay(a: MasteringDisplay, b: MasteringDisplay): Boolean {
    val chroma = max(a.chromaStep, b.chromaStep) + 1e-9
    val maxLum = max(a.maxLuminanceStep, b.maxLuminanceStep) + 1e-9
    val minLum = max(a.minLuminanceStep, b.minLuminanceStep) + 1e-9
    val chromaPairs = listOf(
        a.redX to b.redX, a.redY to b.redY, a.greenX to b.greenX, a.greenY to b.greenY,
        a.blueX to b.blueX, a.blueY to b.blueY, a.whiteX to b.whiteX, a.whiteY to b.whiteY,
    )
    return chromaPairs.all { (x, y) -> abs(x - y) <= chroma } &&
        abs(a.maxLuminance - b.maxLuminance) <= maxLum &&
        abs(a.minLuminance - b.minLuminance) <= minLum
}
