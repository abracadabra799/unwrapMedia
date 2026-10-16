// Creates AI-facing text from analysis-window results; privacy rules: see spec
// docs/superpowers/specs/2026-10-16-ai-analysis-reports-design.md ("개인정보").
// Every renderer is pure, caps its lists at MAX_REPORT_ITEMS, and passes all free text
// (warnings, messages, details, labels read from the file) through scrubPaths. Values that may
// identify a person or place are never emitted: GPS coordinates, C2PA claim title / instanceId /
// certificate subject+issuer / assertion contents / action times, SEF semantic-check details and
// entry values, raw XMP.
package com.multiviewer.ui.ai

import com.multiviewer.cli.scrubPaths
import com.multiviewer.parser.GainmapInfo
import com.multiviewer.parser.GainmapParsedParameters
import com.multiviewer.parser.HdrScanResult
import com.multiviewer.parser.MasteringDisplay
import com.multiviewer.parser.SefIntegrityReport
import com.multiviewer.parser.c2pa.C2paAssertionContent
import com.multiviewer.parser.c2pa.C2paHardBindingReport
import com.multiviewer.parser.c2pa.C2paManifest
import com.multiviewer.parser.c2pa.C2paManifestStore
import com.multiviewer.parser.detectHdrAnomalies
import com.multiviewer.parser.hrd.HrdSimulationReport
import com.multiviewer.parser.integrity.IntegrityCheckItem
import com.multiviewer.parser.metadata.SensorType
import com.multiviewer.parser.metadata.TimedMetadataReport
import com.multiviewer.parser.spatial.SpatialVideoReport
import com.multiviewer.ui.AudioWaveformData
import com.multiviewer.ui.FrameInfo
import com.multiviewer.ui.StoredComparison
import com.multiviewer.ui.computeFrameIntervals
import java.io.File
import java.util.Locale
import kotlin.math.sqrt

internal const val MAX_REPORT_ITEMS = 30
private const val MAX_C2PA_MANIFESTS = 5
private const val MAX_LARGEST_GAPS = 10

private fun f(format: String, vararg args: Any?): String = String.format(Locale.US, format, *args)

private const val MAX_TEXT = 200

private fun clip(s: String, max: Int = MAX_TEXT) = if (s.length <= max) s else s.take(max) + "…"

/** Path-scrubbed and length-capped free text. */
private fun clean(s: String, file: File): String = clip(scrubPaths(s, file))

private val UUID_RE = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
private val LONG_HEX_RE = Regex("[0-9a-fA-F]{16,}")
private const val ID_WITHHELD = "<id withheld>"

/** [clean] plus removal of per-asset identifiers (UUIDs, long hex runs) that C2PA labels may carry. */
private fun c2(s: String, file: File): String =
    clip(scrubPaths(s, file).replace(UUID_RE, ID_WITHHELD).replace(LONG_HEX_RE, ID_WITHHELD))

private const val NAME_WITHHELD = "<name withheld>"
// SefIntegrityAnalyzer reads an entry name as `name_size` bytes. A corrupted, oversized name_size makes the
// name swallow the entry's value bytes with NO NUL separator (e.g. "Image_UTC_Data1697412345678"), so a
// "plausible" name can carry a value. Real SEF names are identifiers without digit runs; anything else is
// withheld (the marker hex still identifies the entry type).
private val SAFE_SEF_NAME = Regex("^[A-Za-z_][A-Za-z0-9_.\\-]{0,39}$")
private val DIGIT_RUN_3 = Regex("[0-9]{3,}")
private val SAFE_LABEL_CHARS = Regex("^[A-Za-z0-9_.\\-#()/:, ]*$")
private val ENTRY_NAME_LABEL = Regex("^Entry #(\\d+) \\((.*)\\) (encoding|JSON syntax)$", RegexOption.DOT_MATCHES_ALL)

internal fun sanitizeSefName(name: String): String =
    if (SAFE_SEF_NAME.matches(name) && !DIGIT_RUN_3.containsMatchIn(name)) name else NAME_WITHHELD

/**
 * Semantic-check labels may embed the (untrusted) entry name. Rebuild them by structure instead of substituting
 * names: the parenthesised-name templates drop the name entirely; any other label is kept only if it is short
 * and made of allowed characters.
 */
private fun sanitizeSefLabel(label: String): String? {
    ENTRY_NAME_LABEL.matchEntire(label)?.let { m ->
        val n = m.groupValues[1]
        if (n.length > 9) return null
        return "Entry #$n ${m.groupValues[3]}"
    }
    return if (label.length <= 120 && SAFE_LABEL_CHARS.matches(label)) label else null
}

private fun yn(b: Boolean): String = if (b) "y" else "n"

/** At most [MAX_REPORT_ITEMS] lines, then a "… N more" line. */
private fun capLines(lines: List<String>): List<String> =
    if (lines.size <= MAX_REPORT_ITEMS) lines
    else lines.take(MAX_REPORT_ITEMS) + "… ${lines.size - MAX_REPORT_ITEMS} more"

/** Comma-joined, at most [MAX_REPORT_ITEMS] items, then " … N more". */
private fun capJoin(items: List<Any?>): String {
    val shown = items.take(MAX_REPORT_ITEMS).joinToString(", ")
    return if (items.size > MAX_REPORT_ITEMS) "$shown … ${items.size - MAX_REPORT_ITEMS} more" else shown
}

private fun section(out: MutableList<String>, title: String, lines: List<String>) {
    if (lines.isEmpty()) out += "$title none" else { out += title; out += capLines(lines) }
}

// ---- HDR / SEI ----

private fun mdcvText(md: MasteringDisplay): String = f(
    "present · primaries R(%.4f, %.4f) G(%.4f, %.4f) B(%.4f, %.4f) · white point (%.4f, %.4f) · luminance max %.4f / min %.4f cd/m²",
    md.redX, md.redY, md.greenX, md.greenY, md.blueX, md.blueY, md.whiteX, md.whiteY, md.maxLuminance, md.minLuminance,
)

fun renderHdrReport(r: HdrScanResult, file: File): String {
    val out = mutableListOf<String>()
    out += "codec: ${r.codec} · frames scanned: ${r.records.size}"
    out += "container mdcv: " + (r.container.masteringDisplay?.let(::mdcvText) ?: "absent")
    out += "container clli: " + (r.container.contentLightLevel?.let { "MaxCLL ${it.maxCll} · MaxFALL ${it.maxFall}" } ?: "absent")
    out += "frames with HDR10+: ${r.records.count { it.hdr10Plus != null }} · " +
        "mastering SEI: ${r.records.count { it.masteringDisplay != null }} · " +
        "content light level SEI: ${r.records.count { it.contentLightLevel != null }}"
    r.records.firstNotNullOfOrNull { rec -> rec.hdr10Plus?.let { rec to it } }?.let { (rec, m) ->
        val w = m.windows.firstOrNull()
        out += "first HDR10+ (frame ${rec.frameNumber}): targeted max luminance ${m.targetedSystemDisplayMaximumLuminance} cd/m² · " +
            "application version ${m.applicationVersion} · windows ${m.numWindows}" +
            (w?.let { f(" · MaxSCL %.1f nits · average maxRGB %.1f nits", it.maxSclNits, it.averageMaxrgbNits) } ?: "")
    }
    val anomalies = detectHdrAnomalies(r.records, r.container).map {
        "- [${it.severity}] ${it.rule} frames ${it.firstFrame ?: "-"}–${it.lastFrame ?: "-"}: ${clean(it.message, file)}"
    }
    section(out, "anomalies (default thresholds):", anomalies)
    section(out, "warnings:", r.warnings.map { "- " + clean(it, file) })
    val lumPts = r.records.mapNotNull { rec -> rec.hdr10Plus?.let { SeriesPoint(rec.ptsSeconds, it.targetedSystemDisplayMaximumLuminance.toDouble()) } }
    val cllPts = r.records.mapNotNull { rec -> rec.contentLightLevel?.let { SeriesPoint(rec.ptsSeconds, it.maxCll.toDouble()) } }
    val buckets = if (lumPts.isNotEmpty() && cllPts.isNotEmpty()) 30 else 60
    val trends = listOfNotNull(
        renderSeriesOrNull("HDR10+ targeted max luminance", "nits", "s", lumPts, buckets),
        renderSeriesOrNull("SEI MaxCLL", "nits", "s", cllPts, buckets),
    )
    if (trends.isNotEmpty()) { out += "trend:"; out += trends }
    return out.joinToString("\n")
}

// ---- HRD ----

fun renderHrdReport(r: HrdSimulationReport): String {
    val out = mutableListOf<String>()
    out += f("cpb %d B · bitrate %d bps · initial delay %.3f s · frames %d", r.cpbSizeBytes, r.bitrateBps, r.initialDelaySeconds, r.totalFrames)
    out += f("occupancy min/avg/max %.2f/%.2f/%.2f %%", r.minOccupancyPercent, r.avgOccupancyPercent, r.maxOccupancyPercent)
    out += "underflows ${r.underflowCount}" + if (r.underflowFrames.isEmpty()) "" else ": frames ${capJoin(r.underflowFrames)}"
    out += if (r.warningFrames.isEmpty()) "warnings at frames: none" else "warnings at frames ${capJoin(r.warningFrames)}"
    renderSeriesOrNull("buffer occupancy", "%", "s", r.points.map { SeriesPoint(it.dtsSeconds, it.occupancyPercent) })?.let {
        out += "trend:"; out += it
    }
    return out.joinToString("\n")
}

// ---- Frame interval ----

fun renderIntervalReport(frames: List<FrameInfo>): String {
    if (frames.size < 2) return "frames ${frames.size} · fewer than 2 frames — no intervals"
    val intervals = computeFrameIntervals(frames)
    val values = intervals.map { it.intervalMs }
    val mean = values.average()
    val stddev = sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
    val out = mutableListOf<String>()
    out += "frames ${frames.size} · intervals ${intervals.size}"
    out += f("interval ms: min/mean/max/stddev %.3f/%.3f/%.3f/%.3f", values.min(), mean, values.max(), stddev)
    out += "largest gaps:"
    intervals.sortedByDescending { it.intervalMs }.take(MAX_LARGEST_GAPS).forEach {
        out += f("  #%d %s PTS %.6f s: %.3f ms (Δ %+.3f ms)", it.frameIndex, it.type, it.ptsSeconds, it.intervalMs, it.intervalDiffMs)
    }
    renderSeriesOrNull("frame interval", "ms", "s", intervals.map { SeriesPoint(it.ptsSeconds, it.intervalMs) })?.let {
        out += "trend:"; out += it
    }
    return out.joinToString("\n")
}

// ---- Timed metadata ----

fun renderTimedReport(r: TimedMetadataReport): String {
    val out = mutableListOf<String>()
    out += f("track %s id %d · samples %d · duration %.3f s · rate %.3f Hz", r.trackFormat, r.trackId, r.totalSamples, r.durationSeconds, r.sampleRateHz)
    out += "decoded sensor points: ${r.samples.size}"
    val byType = r.samples.groupBy { it.type }
    for (type in SensorType.entries) {
        val pts = byType[type] ?: continue
        val time = f("t %.3f–%.3f s", pts.minOf { it.timestampSeconds }, pts.maxOf { it.timestampSeconds })
        out += when (type) {
            // Never any coordinate (x/y/z = lat/lon/alt) in any form -- count and time range only.
            SensorType.GPS -> "GPS: ${pts.size} points (coordinates withheld) · $time"
            // GENERIC carries placeholder zeros; values are meaningless, so count only.
            SensorType.GENERIC -> "GENERIC: ${pts.size} samples · $time"
            else -> {
                fun axis(name: String, v: List<Double>) = f("%s min/mean/max %.4f/%.4f/%.4f", name, v.min(), v.average(), v.max())
                "$type: ${pts.size} samples · $time · " +
                    listOf(axis("x", pts.map { it.x }), axis("y", pts.map { it.y }), axis("z", pts.map { it.z })).joinToString(" · ")
            }
        }
    }
    // Sample labels are free text and are not sent. GPS/GENERIC values never enter a trend either.
    val trendTypes = SensorType.entries.filter { it != SensorType.GPS && it != SensorType.GENERIC && byType[it] != null }
    val buckets = if (trendTypes.size > 1) 30 else 60
    val trends = trendTypes.mapNotNull { type ->
        byType[type]?.let { pts ->
            renderSeriesOrNull("$type magnitude", "", "s", pts.map { SeriesPoint(it.timestampSeconds, sqrt(it.x * it.x + it.y * it.y + it.z * it.z)) }, buckets)
        }
    }
    if (trends.isNotEmpty()) { out += "trend:"; out += trends }
    return out.joinToString("\n")
}

// ---- A/V sync ----

fun renderAvSyncReport(r: com.multiviewer.ui.AvSyncReport): String {
    val head = "video ${r.videoDurationSec} s, audio ${r.audioDurationSec} s, initial skew ${r.initialSkewMs} ms, " +
        "max ${r.maxSkewMs} ms, avg ${r.avgSkewMs} ms, drift ${r.driftRateMsPerMin} ms/min; " +
        r.diagnoses.take(10).joinToString("; ") { "[${it.severity}] ${it.category}: ${it.summary}" }
    val trend = renderSeriesOrNull("A/V skew", "ms", "s", r.syncPoints.map { SeriesPoint(it.timeSeconds, it.deltaMs) })
    return if (trend == null) head else "$head\ntrend:\n$trend"
}

// ---- Spatial video ----

fun renderSpatialReport(r: SpatialVideoReport, file: File): String {
    val m = r.metadata
    val out = mutableListOf<String>()
    out += "spatial video: ${if (r.isSpatialVideo) "yes" else "no"}"
    out += listOfNotNull(
        "metadata: spatial boxes ${if (m.hasSpatialBoxes) "yes" else "no"}",
        m.heroEye?.let { "hero eye ${clean(it, file)}" },
        m.baselineMm?.let { f("baseline %.3f mm", it) },
        m.horizontalFovDeg?.let { f("hFOV %.3f°", it) },
        m.disparityAdjustment?.let { f("disparity adj %.4f", it) },
        m.stereoMode?.let { "stereo mode ${clean(it, file)}" },
        m.lhvcSummary?.let { "lhvC ${clean(it, file)}" },
    ).joinToString(" · ")
    out += "frames ${r.totalFrames} · layer0 ${r.layer0TotalBytes} B · layer1 ${r.layer1TotalBytes} B · " +
        "keyframes ${r.layer0KeyframeCount}/${r.layer1KeyframeCount} · parity match ${if (r.layerParityMatch) "yes" else "no"}"
    section(out, "anomalies:", r.anomalies.map {
        "- [${it.severity}] ${it.code}${it.frameIndex?.let { i -> " @$i" } ?: ""}: ${clean(it.message, file)}"
    })
    return out.joinToString("\n")
}

// ---- C2PA ----

private fun c2paManifestLines(index: Int, total: Int, m: C2paManifest, file: File): List<String> {
    val out = mutableListOf<String>()
    out += "manifest ${index + 1}/$total: ${c2(m.label, file)}"
    val c = m.claim
    if (c == null) {
        out += "  claim error: ${c2(m.claimError ?: "no claim", file)}"
    } else {
        // claim title and instanceId are deliberately not emitted.
        out += "  claim generator: ${c.claimGenerator?.let { c2(it, file) } ?: "-"}"
        out += "  generator info: ${if (c.generatorInfo.isEmpty()) "-" else capJoin(c.generatorInfo.map { c2(it, file) })}"
        out += "  format: ${c.format?.let { c2(it, file) } ?: "-"}"
        out += "  claim version: ${c.version}"
    }
    // Labels only -- never assertion contents.
    out += "  assertions: ${capJoin(m.assertions.map { c2(it.label, file) })}"
    val actions = m.assertions.mapNotNull { it.content as? C2paAssertionContent.Actions }.flatMap { it.actions }
    // Action name, softwareAgent and digitalSourceType only -- no `when` time, no parameters.
    val actionTexts = actions.map { a ->
        c2(a.action, file) +
            (a.softwareAgent?.let { " by ${c2(it, file)}" } ?: "") +
            (a.digitalSourceType?.let { " (${c2(it, file)})" } ?: "")
    }
    out += "  actions: ${if (actionTexts.isEmpty()) "none" else capJoin(actionTexts)}"
    out += "  ingredients: ${m.assertions.count { it.content is C2paAssertionContent.Ingredient }}"
    val s = m.signature
    out += when {
        s == null -> "  signature: absent"
        s.error != null -> "  signature: could not be parsed"
        else -> "  signature: alg ${s.algorithm ?: "-"} · chain ${s.chainLength} certs · " +
            if (s.certificateSubject != null || s.certificateIssuer != null) "certificate subject/issuer present (withheld)" else "no certificate"
    }
    if (m.warnings.isNotEmpty()) {
        out += "  warnings:"
        out += capLines(m.warnings.map { "  - " + c2(it, file) })
    }
    return out
}

fun renderC2paReport(store: C2paManifestStore, binding: C2paHardBindingReport?, bindingError: String?, file: File): String {
    val out = mutableListOf<String>()
    out += "location: ${store.location.container}"
    val total = store.manifests.size
    out += "manifests: $total (active = last)"
    // The active manifest is the last one, so when there are more than MAX_C2PA_MANIFESTS keep the last ones.
    val skipped = (total - MAX_C2PA_MANIFESTS).coerceAtLeast(0)
    if (skipped > 0) out += "… $skipped earlier manifests omitted"
    store.manifests.withIndex().drop(skipped).forEach { (i, m) -> out += c2paManifestLines(i, total, m, file) }
    out += when {
        binding != null -> {
            val c = binding.comparison
            "hard binding: ${c.status} · alg ${c2(c.algorithm, file)} · hashed ${c.hashedByteCount} B · excluded ${c.excludedByteCount} B" +
                (c.error?.let { " · error: ${c2(it, file)}" } ?: "")
        }
        else -> "hard binding: not verified (${c2(bindingError ?: "no result", file)})"
    }
    section(out, "store warnings:", store.warnings.map { "- " + c2(it, file) })
    return out.joinToString("\n")
}

// ---- SEF ----

fun renderSefReport(r: SefIntegrityReport, file: File): String {
    val out = mutableListOf<String>()
    out += "overall: ${r.overallSeverity} · declared entries: ${r.declaredEntryCount ?: "unknown"} (${r.declaredEntryCountSeverity})"
    section(out, "structural checks:", r.structuralChecks.map { "- [${it.severity}] ${clean(it.label, file)}: ${clean(it.detail, file)}" })
    // Semantic details carry entry values (MCC, timestamps) -- label and severity only.
    section(out, "semantic checks:", r.semanticChecks.map {
        "- [${it.severity}] ${sanitizeSefLabel(it.label)?.let { l -> clean(l, file) } ?: "Entry check"}"
    })
    section(out, "directory:", r.directoryEntries.map { e ->
        "#${e.entryIndex} marker ${e.markerHex} ${e.name?.let(::sanitizeSefName) ?: "?"} @${e.declaredOffset} +${e.declaredLength} " +
            "in-bounds ${yn(e.inBounds)} marker-match ${e.markerMatches?.let(::yn) ?: "-"} [${e.status}]"
    })
    return out.joinToString("\n")
}

// ---- Gain map ----

private fun parametersText(p: GainmapParsedParameters, file: File): String = listOfNotNull(
    p.version?.let { "version ${clean(it, file)}" },
    p.gainMapMin?.let { f("gain map min %.4f", it) },
    p.gainMapMax?.let { f("gain map max %.4f", it) },
    p.gamma?.let { f("gamma %.4f", it) },
    p.offsetSdr?.let { f("offset SDR %.6f", it) },
    p.offsetHdr?.let { f("offset HDR %.6f", it) },
    p.hdrCapacityMin?.let { f("HDR capacity min %.4f", it) },
    p.hdrCapacityMax?.let { f("HDR capacity max %.4f", it) },
    p.baseRenditionIsHdr?.let { "base is HDR ${if (it) "yes" else "no"}" },
).joinToString(" · ").ifEmpty { "none" }

fun renderGainmapReport(info: GainmapInfo, checks: List<IntegrityCheckItem>, file: File): String {
    val out = mutableListOf<String>()
    out += "format: ${info.formatType.displayName} · gain map: ${if (info.hasGainmap) "yes" else "no"} · " +
        "summary: ${clean(info.summaryDescription, file).ifEmpty { "-" }}"
    val image = listOfNotNull(
        if (info.imageWidth != null && info.imageHeight != null) "${info.imageWidth}x${info.imageHeight}" else null,
        info.imageFormat?.let { clean(it, file) },
        info.byteOffset?.let { "@$it" },
        info.byteLength?.let { "+$it" },
    )
    out += "gain map image: " + if (image.isEmpty()) (if (info.hasGainmapImage) "present" else "absent") else image.joinToString(" ")
    info.auxType?.let { out += "aux type: ${clean(it, file)}" }
    info.isoMetadata?.let { m ->
        out += f(
            "ISO 21496-1: min version %d · writer version %d · multichannel %s · base colour space %s · direction %s · base headroom %.4f · alternate headroom %.4f",
            m.minimumVersion, m.writerVersion, yn(m.isMultichannel), yn(m.useBaseColourSpace),
            if (m.backwardDirection) "backward" else "forward", m.baseHdrHeadroom, m.alternateHdrHeadroom,
        )
        m.channels.withIndex().forEach { (i, c) ->
            out += f(
                "  channel %d: gain map min/max %.4f/%.4f · gamma %.4f · base offset %.6f · alternate offset %.6f",
                i, c.gainMapMin, c.gainMapMax, c.gamma, c.baseOffset, c.alternateOffset,
            )
        }
    }
    info.parameters?.let { out += "parameters: ${parametersText(it, file)}" }
    // Raw XMP may hold free text (owner, device, edit history) -- length only.
    out += "XMP: " + (info.rawXmp?.let { "${it.length} chars (not sent)" } ?: "absent")
    section(out, "checks:", checks.map { "- [${it.status}] ${clean(it.title, file)}: ${clean(it.detail, file)}" })
    return out.joinToString("\n")
}

// ---- GOP ----

private fun sizeStats(label: String, sizes: List<Int>): String? =
    if (sizes.isEmpty()) null else f("%s %d/%.1f/%d", label, sizes.min(), sizes.average(), sizes.max())

fun renderGopReport(frames: List<FrameInfo>): String {
    val sorted = frames.sortedBy { it.ptsSeconds }
    val n = sorted.size
    if (n == 0) return "frames 0"
    fun count(t: Char) = sorted.count { it.type == t }
    val other = sorted.count { it.type !in "IPB" }
    val out = mutableListOf(f("frames %d · I %d · P %d · B %d · other %d", n, count('I'), count('P'), count('B'), other))
    val iIdx = sorted.indices.filter { sorted[it].type == 'I' }
    if (iIdx.isEmpty()) {
        out += "GOP length (frames): no I-frames"
    } else {
        val lengths = iIdx.zipWithNext { a, b -> b - a }
        val open = n - iIdx.last()
        out += if (lengths.isEmpty()) {
            "GOP length (frames): no complete GOP (last GOP open: $open frames)"
        } else {
            f("GOP length (frames): min/mean/max %.1f/%.1f/%.1f over %d GOPs (last GOP open: %d frames)",
                lengths.min().toDouble(), lengths.average(), lengths.max().toDouble(), lengths.size, open)
        }
    }
    val bySize = listOf('I', 'P', 'B').mapNotNull { t -> sizeStats(t.toString(), sorted.filter { it.type == t }.map { it.sizeBytes }) }
    if (bySize.isNotEmpty()) out += "frame size by type (bytes min/mean/max): " + bySize.joinToString(" · ")
    if (n >= 2) {
        val first = sorted.first().ptsSeconds
        val span = sorted.last().ptsSeconds - first
        val duration = span * n / (n - 1)
        if (duration > 0.0) {
            val bytes = sorted.sumOf { it.sizeBytes.toLong() }
            out += f("average bitrate %.1f kbps over %.3f s", bytes * 8.0 / duration / 1000.0, duration)
            val b = minOf(60, n)
            val sums = DoubleArray(b)
            sorted.forEach { fr ->
                val i = (((fr.ptsSeconds - first) / duration) * b).toInt().coerceIn(0, b - 1)
                sums[i] += fr.sizeBytes.toDouble()
            }
            val bucketLen = duration / b
            val points = List(b) { SeriesPoint(first + (it + 0.5) * bucketLen, sums[it] * 8.0 / bucketLen / 1000.0) }
            out += "trend:"
            out += renderSeries("bitrate", "kbps", "s", points, buckets = 60)
        }
    }
    return out.joinToString("\n")
}

// ---- Audio waveform ----

private const val CLIP_THRESHOLD = 0.999f
private const val SILENCE_LINEAR = 0.001f
private const val MIN_SILENCE_SECONDS = 0.5
private const val MAX_AUDIO_SERIES_POINTS = 600
private const val MAX_LISTED_SPANS = 10

private fun dbfs(amp: Float): Double = if (amp <= 0f) -120.0 else maxOf(-120.0, 20.0 * Math.log10(amp.toDouble()))

fun renderAudioReport(data: AudioWaveformData): String {
    val peaks = data.peaks
    val buckets = peaks.bucketCount
    val dur = data.durationSeconds
    val bucketSec = if (buckets > 0) dur / buckets else 0.0
    val out = mutableListOf(
        f("channels %d · duration %.3f s · buckets %d (%.1f ms each) · peak-only (min/max per bucket); RMS/loudness not available",
            peaks.channelCount, dur, buckets, bucketSec * 1000.0))
    val trends = mutableListOf<String>()
    val minSilentBuckets = if (bucketSec > 0.0) Math.ceil(MIN_SILENCE_SECONDS / bucketSec - 1e-9).toInt().coerceAtLeast(1) else Int.MAX_VALUE
    peaks.channels.forEachIndexed { c, ch ->
        val n = minOf(buckets, ch.min.size, ch.max.size)
        val amp = FloatArray(n) { maxOf(Math.abs(ch.min[it]), Math.abs(ch.max[it])) }
        var peak = 0f
        var clippedCount = 0
        val clippedFirst = ArrayList<Int>(MAX_LISTED_SPANS)
        for (k in 0 until n) {
            val a = amp[k]
            if (a > peak) peak = a
            if (a >= CLIP_THRESHOLD) {
                clippedCount++
                if (clippedFirst.size < MAX_LISTED_SPANS) clippedFirst += k
            }
        }
        var silentCount = 0
        val silent = ArrayList<Pair<Double, Double>>(MAX_LISTED_SPANS)
        var i = 0
        while (i < n) {
            if (amp[i] < SILENCE_LINEAR) {
                var j = i
                while (j + 1 < n && amp[j + 1] < SILENCE_LINEAR) j++
                if (j - i + 1 >= minSilentBuckets) {
                    silentCount++
                    if (silent.size < MAX_LISTED_SPANS) silent += (i * bucketSec) to ((j + 1) * bucketSec)
                }
                i = j + 1
            } else i++
        }
        val clipText = if (clippedCount == 0) "clipped buckets 0"
        else f("clipped buckets %d (first at %s s)", clippedCount,
            clippedFirst.joinToString(", ") { f("%.3f", it * bucketSec) })
        val silentText = f("silent spans ≥0.5 s below −60 dBFS: %d", silentCount) +
            if (silent.isEmpty()) "" else " (" + silent.joinToString(", ") { f("%.3f–%.3f", it.first, it.second) } + " s)"
        out += f("channel %d: peak %.1f dBFS · ", c, dbfs(peak)) + clipText + " · " + silentText
        // Max-pool to at most MAX_AUDIO_SERIES_POINTS so a multi-million-bucket file stays cheap.
        val pooled = if (n == 0) emptyList() else {
            val m = minOf(n, MAX_AUDIO_SERIES_POINTS)
            List(m) { p ->
                val from = (p.toLong() * n / m).toInt()
                val to = (((p + 1).toLong() * n / m).toInt()).coerceAtLeast(from + 1)
                var mx = 0f
                for (k in from until minOf(to, n)) if (amp[k] > mx) mx = amp[k]
                SeriesPoint(if (m == n) (from + 0.5) * bucketSec else from * bucketSec, dbfs(mx))
            }
        }
        renderSeriesOrNull("channel $c peak", "dBFS", "s", pooled)?.let { trends += it }
    }
    if (trends.isNotEmpty()) {
        out += "trend:"
        out += trends
    }
    return out.joinToString("\n")
}

private const val QUALITY_WORST_FRAMES = 10

/**
 * Summary first, then trends (the tool bridge truncates from the end): every pair with, per metric, statistics and the
 * worst frames (lowest first); then one trend series per pair x metric. Trend buckets scale with the series count:
 * `buckets = max(8, 60 / seriesCount)` (integer division), e.g. 1 series 60, 3 series 20, 9 series 8. File names only.
 * No "largest" values: for quality metrics those are the best frames.
 */
fun renderQualityReport(c: StoredComparison): String {
    fun role(f: File) = c.files.entries.firstOrNull { it.value.absolutePath == f.absolutePath }?.key ?: "?"
    fun name(f: File) = scrubPaths(f.name, f)
    val out = mutableListOf<String>()
    if (c.missing.isNotEmpty()) out += "incomplete: " + c.missing.joinToString(", ")
    val seriesCount = c.results.values.sumOf { it.size }
    val buckets = maxOf(8, 60 / maxOf(1, seriesCount))
    c.results.forEach { (pairLabel, metrics) ->
        val pair = c.pairs.firstOrNull { it.label == pairLabel }
        out += if (pair != null) {
            "pair $pairLabel: reference ${role(pair.reference)} \"${name(pair.reference)}\" · comparison ${role(pair.comparison)} \"${name(pair.comparison)}\""
        } else "pair $pairLabel"
        metrics.forEach { (metric, r) ->
            val st = r.statistics
            val worst = r.perFrame.filter { it.value.isFinite() }.sortedBy { it.value }.take(QUALITY_WORST_FRAMES)
                .joinToString(", ") { f("#%d=%.3f", it.frameIndex, it.value) }
            out += f("  %s: min %.3f / mean %.3f / median %.3f / max %.3f · worst frames: %s", metric, st.min, st.mean, st.median, st.max,
                worst.ifEmpty { "none" })
        }
    }
    val trends = mutableListOf<String>()
    c.results.forEach { (pairLabel, metrics) ->
        metrics.forEach { (metric, r) ->
            renderSeriesOrNull("$pairLabel $metric", "", "frame", r.perFrame.map { SeriesPoint(it.frameIndex.toDouble(), it.value) },
                buckets = buckets, spikes = 0)?.let { trends += it }
        }
    }
    if (trends.isNotEmpty()) {
        out += "trend:"
        out += trends
    }
    return out.joinToString("\n")
}
