package com.multiviewer.ui

import com.multiviewer.parser.SefCheckResult
import com.multiviewer.parser.integrity.AudioStructureReport
import com.multiviewer.parser.integrity.CheckStatus
import com.multiviewer.parser.integrity.ImageStructureReport
import com.multiviewer.parser.integrity.IntegrityCheckItem

data class VideoFindingPresentation(
    val decoderFindings: List<ContentFinding>,
    val packetFindings: List<ContentFinding>,
    val packetRows: List<IntegrityPacket>,
)

data class AudioTrackMeasurementRow(
    val streamIndex: Int,
    val codec: String,
    val status: CheckStatus,
    val declaredSampleRate: Int?,
    val observedSampleRates: Set<Int>,
    val declaredChannels: Int?,
    val observedChannels: Set<Int>,
    val declaredDurationSeconds: Double?,
    val decodedDurationSeconds: Double,
    val decodedSamples: Long,
    val decodedFrames: Long,
)

data class AudioFindingPresentation(
    val probeFindings: List<ContentFinding>,
    val trackFindings: List<ContentFinding>,
    val trackRows: List<AudioTrackMeasurementRow>,
)

fun structureFindings(report: ImageStructureReport, stage: FindingStage): List<ContentFinding> =
    report.items.map { it.toFinding(stage).sanitizeFindingPaths() }

fun audioStructureFindings(report: AudioStructureReport): List<ContentFinding> = buildList {
    addAll(report.items.map { it.toFinding(FindingStage.AUDIO_STRUCTURE) })
    addAll(report.parserWarnings.map { it.toFinding(FindingStage.PARSER) })
}.map(ContentFinding::sanitizeFindingPaths)

fun imageDecodeFindings(report: ImageDecodeReport): List<ContentFinding> = buildList {
    add(ContentFinding(
        id = "image.decode.ffmpeg", stage = FindingStage.IMAGE_DECODE,
        status = mapImageDecodeStatus(report.ffmpegStatus), title = "FFmpeg image decode", location = null,
        offset = null, length = null,
        summary = "${report.decodedFrames} frame(s) decoded",
        verifiedFacts = buildList {
            add("FFmpeg status: ${report.ffmpegStatus}")
            add("Decoded frames: ${report.decodedFrames}")
            report.ffmpegVersion?.let { add("FFmpeg version: $it") }
            addAll(report.logs.take(20))
        },
    ))
    if (report.skia.attempted) add(ContentFinding(
        id = "image.decode.skia", stage = FindingStage.IMAGE_DECODE,
        status = if (report.skia.ok) CheckStatus.PASS else CheckStatus.WARN,
        title = "Skia image decode", location = null, offset = null, length = null,
        summary = report.skia.detail, verifiedFacts = listOf(report.skia.detail),
    ))
    add(ContentFinding(
        id = "image.decode.resolution", stage = FindingStage.IMAGE_DECODE,
        status = report.resolutionStatus,
        title = "Declared vs decoded dimensions", location = null, offset = null, length = null,
        summary = dimensionSummary(report.declaredWidth, report.declaredHeight, report.decodedWidth, report.decodedHeight),
        verifiedFacts = buildList {
            report.declaredWidth?.let { add("Declared width: $it px") }
            report.declaredHeight?.let { add("Declared height: $it px") }
            report.decodedWidth?.let { add("Decoded width: $it px") }
            report.decodedHeight?.let { add("Decoded height: $it px") }
        },
    ))
}.map(ContentFinding::sanitizeFindingPaths)

fun motionPhotoFindings(report: MotionPhotoIntegrityReport): List<ContentFinding> = buildList {
    report.sefSection?.let { sef ->
        addAll(sef.structuralChecks.mapIndexed { i, check -> check.toFinding("motion.sef.structural.$i", FindingStage.MOTION_PHOTO) })
        addAll(sef.semanticChecks.mapIndexed { i, check -> check.toFinding("motion.sef.semantic.$i", FindingStage.MOTION_PHOTO) })
        addAll(sef.directoryEntries.map { row -> ContentFinding(
            id = "motion.sef.directory.${row.entryIndex}", stage = FindingStage.MOTION_PHOTO,
            status = row.status.toCheckStatus(), title = row.name ?: "SEF directory entry #${row.entryIndex}",
            location = null, offset = row.computedDataStart, length = row.declaredLength,
            summary = "SEF directory entry bounds and marker validation",
            verifiedFacts = listOf(
                "Marker: ${row.markerHex}", "Declared offset: ${row.declaredOffset}",
                "Declared length: ${row.declaredLength}", "Computed data start: ${row.computedDataStart}",
                "Computed data end: ${row.computedDataEnd}", "In bounds: ${row.inBounds}",
                "Marker matches: ${row.markerMatches ?: "unavailable"}",
            ),
        ) })
    }
    addAll(report.googleXmpChecks.map { it.toFinding("motion.google", FindingStage.MOTION_PHOTO) })
    addAll(report.appleMpvdChecks.map { it.toFinding("motion.apple", FindingStage.MOTION_PHOTO) })
    addAll(report.decodeChecks.map { it.toFinding("motion.decode", FindingStage.MOTION_PHOTO) })
}.map(ContentFinding::sanitizeFindingPaths)

fun videoFindings(report: VideoIntegrityReport): VideoFindingPresentation {
    val decoderFindings = buildList {
        if (report.decodeStatus != IntegrityStatus.NOT_RUN && report.logs.isEmpty()) {
            add(ContentFinding(
                id = "video.decode.status", stage = FindingStage.VIDEO_DECODE,
                status = mapIntegrityStatus(report.decodeStatus), title = "Video decode", location = null,
                offset = null, length = null, summary = "${report.decodedFrames} frame(s) decoded",
                verifiedFacts = buildList {
                    add("Decode status: ${report.decodeStatus}")
                    add("Decoded frames: ${report.decodedFrames}")
                    if (report.logsTruncated) add("Decoder logs were truncated")
                },
            ))
        }
        report.logs.forEachIndexed { index, message ->
            val explanation = explainIntegrityLog(message).explanation
            add(ContentFinding(
                id = "video.decode.log.$index", stage = FindingStage.VIDEO_DECODE,
                status = mapIntegrityStatus(report.decodeStatus), title = explanation.title, location = null,
                offset = null, length = null, summary = message,
                verifiedFacts = listOf(message),
                hypothesis = listOf(explanation.summary, explanation.probableCause, explanation.visualImpact)
                    .filter(String::isNotBlank).joinToString(" ").ifBlank { null },
                nextChecks = listOf(explanation.actionableFix).filter(String::isNotBlank),
            ))
        }
    }
    val packetFindings = buildList {
        if (report.packetStatus != IntegrityStatus.NOT_RUN && report.packetLogs.isEmpty()) add(ContentFinding(
            id = "video.packet.status", stage = FindingStage.VIDEO_PACKETS,
            status = mapIntegrityStatus(report.packetStatus), title = "Packet inspection",
            location = null, offset = null, length = null,
            summary = "Packet status: ${report.packetStatus}",
            verifiedFacts = buildList {
                add("Packet status: ${report.packetStatus}")
                add("Packets captured: ${report.packets.size}")
                if (report.packetsTruncated) add("Packet list was truncated")
                if (report.logsTruncated) add("Decoder logs were truncated")
            },
        ))
        report.packetLogs.forEachIndexed { index, message -> add(ContentFinding(
            id = "video.packet.log.$index", stage = FindingStage.VIDEO_PACKETS,
            status = mapIntegrityStatus(report.packetStatus), title = "Packet inspection message", location = null,
            offset = null, length = null, summary = message, verifiedFacts = listOf(message),
        )) }
    }
    return VideoFindingPresentation(
        decoderFindings = decoderFindings.map(ContentFinding::sanitizeFindingPaths),
        packetFindings = packetFindings.map(ContentFinding::sanitizeFindingPaths),
        packetRows = report.packets,
    )
}

fun audioFindings(report: AudioIntegrityReport): AudioFindingPresentation {
    val probeFindings = if (report.probeStatus == IntegrityStatus.NOT_RUN && report.logs.isEmpty()) emptyList() else listOf(
        ContentFinding(
            id = "audio.decode.probe", stage = FindingStage.AUDIO_DECODE,
            status = mapIntegrityStatus(report.probeStatus), title = "Audio probe", location = null,
            offset = null, length = null, summary = "Audio stream discovery: ${report.probeStatus}",
            verifiedFacts = listOf("Probe status: ${report.probeStatus}") + report.logs.take(20) +
                if (report.logsTruncated) listOf("Probe logs were truncated") else emptyList(),
        ),
    )
    val trackFindings = report.streams.map { stream ->
        ContentFinding(
            id = "audio.track.${stream.stream.index}", stage = FindingStage.AUDIO_DECODE,
            status = mapIntegrityStatus(stream.status), title = "Audio track ${stream.stream.index}: measurements",
            location = "Track ${stream.stream.index}", offset = null, length = null,
            summary = stream.mismatches.firstOrNull() ?: "Decoded measurements recorded",
            verifiedFacts = listOf(
                "Declared codec: ${stream.stream.codec}",
                "Declared sample rate: ${stream.stream.sampleRate ?: "unknown"}; observed: ${stream.observedSampleRates.sorted().joinToString().ifEmpty { "unknown" }}",
                "Declared channels: ${stream.stream.channels ?: "unknown"}; observed: ${stream.observedChannels.sorted().joinToString().ifEmpty { "unknown" }}",
                "Decoded samples per channel: ${stream.decodedSamples}",
                "Decoded duration: ${stream.decodedDurationSeconds}s",
            ) + stream.mismatches + stream.logs.take(20) +
                if (stream.logsTruncated) listOf("Track decoder logs were truncated") else emptyList(),
        )
    }
    return AudioFindingPresentation(
        probeFindings = probeFindings.map(ContentFinding::sanitizeFindingPaths),
        trackFindings = trackFindings.map(ContentFinding::sanitizeFindingPaths),
        trackRows = report.streams.map { stream -> AudioTrackMeasurementRow(
            streamIndex = stream.stream.index, codec = stream.stream.codec, status = mapIntegrityStatus(stream.status),
            declaredSampleRate = stream.stream.sampleRate, observedSampleRates = stream.observedSampleRates.toSet(),
            declaredChannels = stream.stream.channels, observedChannels = stream.observedChannels.toSet(),
            declaredDurationSeconds = stream.stream.durationSeconds, decodedDurationSeconds = stream.decodedDurationSeconds,
            decodedSamples = stream.decodedSamples, decodedFrames = stream.decodedFrames,
        ) },
    )
}

private val ABSOLUTE_PATH = Regex("""(?<![\w])(?:[A-Za-z]:[\\/]|/)(?:[^\s"'<>|]+[\\/])*[^\s"'<>|]*""")

internal fun safeFindingText(value: String): String = ABSOLUTE_PATH.replace(value) { match ->
    val path = match.value.trimEnd(',', '.', ':', ';', ')', ']', '}')
    path.substringAfterLast('/').substringAfterLast('\\').ifBlank { "[path]" }
}

internal fun ContentFinding.sanitizeFindingPaths(): ContentFinding = copy(
    id = safeFindingText(id), title = safeFindingText(title), location = location?.let(::safeFindingText),
    summary = safeFindingText(summary), verifiedFacts = verifiedFacts.map(::safeFindingText),
    hypothesis = hypothesis?.let(::safeFindingText), nextChecks = nextChecks.map(::safeFindingText),
)

private fun IntegrityCheckItem.toFinding(stage: FindingStage, prefix: String = "structure"): ContentFinding = ContentFinding(
    id = "$prefix.$id", stage = stage, status = status, title = title,
    location = null, offset = offset, length = length, summary = detail,
    verifiedFacts = listOf(detail),
)

private fun SefCheckResult.toFinding(id: String, stage: FindingStage): ContentFinding = ContentFinding(
    id = id, stage = stage, status = severity.toCheckStatus(), title = label,
    location = null, offset = null, length = null, summary = detail, verifiedFacts = listOf(detail),
)

private fun dimensionSummary(declaredWidth: Int?, declaredHeight: Int?, decodedWidth: Int?, decodedHeight: Int?): String =
    "Declared ${declaredWidth ?: "?"}×${declaredHeight ?: "?"}; decoded ${decodedWidth ?: "?"}×${decodedHeight ?: "?"}"
