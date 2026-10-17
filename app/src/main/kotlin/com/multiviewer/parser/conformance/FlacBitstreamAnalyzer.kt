package com.multiviewer.parser.conformance

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.boxDescendants
import com.multiviewer.parser.buildAudioSampleTable
import com.multiviewer.parser.firstAudioSampleEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

private const val MAX_REPORTED_OCCURRENCES_PER_RULE = 100

/**
 * Scans the first supported FLAC audio track.
 * Every finding remains a structural observation until normative clauses are verified.
 */
suspend fun analyzeFlacBitstream(
    file: File,
    root: BoxNode,
    onProgress: (Int, Int?) -> Unit,
): FlacBitstreamReport = withContext(Dispatchers.IO) {
    val limitations = mutableListOf<String>()
    val findings = mutableListOf<ConformanceFinding>()
    val totals = mutableMapOf<String, Long>()
    val pair = firstAudioSampleEntry(root)
    val entry = pair?.second
    val tag = entry?.type
    val audioTracks = boxDescendants(root).filter { it.type == "trak" }.filter { trak ->
        boxDescendants(trak).any { node -> node.type == "hdlr" && node.fields.any { it.name == "handler_type" && it.value == "soun" } }
    }.toList()
    val trackIndex = audioTracks.indexOf(pair?.first).takeIf { it >= 0 }?.plus(1) ?: 1
    val otherAudioTracks = (audioTracks.size - 1).coerceAtLeast(0)

    fun report(complete: Boolean, checked: Int = 0, indexed: Int = 0): FlacBitstreamReport =
        FlacBitstreamReport(
            findings = findings,
            checkedSamples = checked,
            indexedSamples = indexed,
            totalOccurrences = totals,
            limitations = limitations,
            checkedTrack = "audio track #$trackIndex (${tag ?: "unknown"})",
            complete = complete,
        )

    if (entry == null || tag != "fLaC") {
        limitations += "FLAC audio sample entry not found / FLAC 오디오 샘플 엔트리 없음"
        return@withContext report(false)
    }
    if (otherAudioTracks > 0) limitations += "$otherAudioTracks other audio track(s) not checked / 다른 오디오 트랙 미검증"
    if (boxDescendants(root).any { it.type == "moof" || it.type == "mvex" }) {
        limitations += "Fragmented MP4 samples are not supported / 조각난 MP4 샘플 미지원"
        return@withContext report(false)
    }
    if (entry.children.any { it.type == "sinf" } ||
        boxDescendants(pair.first).any { node -> node.type == "enca" || node.type == "sinf" }) {
        limitations += "Encrypted audio samples are not supported / 암호화 샘플 미지원"
        return@withContext report(false)
    }

    val dfLa = boxDescendants(entry).firstOrNull { it.type == "dfLa" }
    if (dfLa == null || dfLa.size - dfLa.headerSize < 4 || dfLa.size > 1_000_000) {
        limitations += "dfLa missing, truncated, or too large / dfLa 없음·잘림·과대"
        return@withContext report(false)
    }
    if (dfLa.warnings.isNotEmpty()) {
        limitations += dfLa.warnings
    }

    ByteReader.open(file).use { reader ->
        val config = parseFlacConfig(reader, dfLa)

        val table = buildAudioSampleTable(reader, root)
        if (table == null || table.format != "fLaC") {
            limitations += "Audio sample table unavailable / 샘플 표 사용 불가"
            return@use report(false)
        }
        limitations += table.warnings
        if (table.samples.isEmpty()) limitations += "No indexed samples / 인덱싱된 샘플 없음"

        fun add(id: String, status: ConformanceStatus, detail: String, range: LongRange = LongRange.EMPTY) {
            totals[id] = (totals[id] ?: 0L) + 1
            if (totals.getValue(id) <= MAX_REPORTED_OCCURRENCES_PER_RULE) {
                val rule = FlacRuleCatalog.rule(id) ?: return
                findings += ConformanceFinding(
                    rule = rule,
                    status = status,
                    details = listOf(detail),
                    ranges = if (range.isEmpty()) emptyList() else listOf(range),
                )
            }
        }

        var malformed = false

        if (config.issue == null) {
            add(
                "flac.config.dfla",
                ConformanceStatus.PASS,
                "dfLa box parsed: version=0, minBlockSize=${config.minBlockSize}, maxBlockSize=${config.maxBlockSize}, rate=${config.sampleRate}Hz, channels=${config.channels}, bits=${config.bitsPerSample} / dfLa 파라미터 정상",
                dfLa.offset until (dfLa.offset + dfLa.size),
            )
        } else {
            malformed = true
            add(
                "flac.config.dfla",
                ConformanceStatus.FAIL,
                "dfLa invalid: ${config.issue} / dfLa 구문 오류",
                dfLa.offset until (dfLa.offset + dfLa.size),
            )
        }

        val entryChannelCount = entry.fields.firstOrNull { it.name == "channelcount" }?.value?.toIntOrNull()
        val entrySampleSize = entry.fields.firstOrNull { it.name == "samplesize" }?.value?.toIntOrNull()
        val entrySampleRate = entry.fields.firstOrNull { it.name == "samplerate" }?.value?.toDoubleOrNull()?.toInt()

        val matchChannels = entryChannelCount == config.channels
        val matchBits = entrySampleSize == config.bitsPerSample
        val matchRate = (config.sampleRate != null && entrySampleRate == config.sampleRate.toInt())

        if (config.issue == null && matchChannels && matchBits && matchRate) {
            add(
                "flac.config.match",
                ConformanceStatus.PASS,
                "entry channels=$entryChannelCount sampleRate=${entrySampleRate}Hz sampleSize=$entrySampleSize matches dfLa STREAMINFO / 샘플 엔트리와 dfLa 일치",
                entry.offset until (entry.offset + 28),
            )
        } else {
            malformed = true
            add(
                "flac.config.match",
                ConformanceStatus.FAIL,
                "entry channels=$entryChannelCount sampleRate=${entrySampleRate}Hz sampleSize=$entrySampleSize vs dfLa channels=${config.channels} sampleRate=${config.sampleRate}Hz bits=${config.bitsPerSample} / 불일치",
                entry.offset until (entry.offset + 28),
            )
        }

        limitations += "Full subframe prediction, residual Rice decoding, and MD5 audio check are not checked / 서브프레임 및 MD5 미검증"

        var checked = 0
        var headerFailures = 0
        var frameCrcFailures = 0
        var sampleFramingFailures = 0

        for (sample in table.samples) {
            currentCoroutineContext().ensureActive()
            val obs = scanFlacSample(reader, sample, config)
            if (obs.issue != null && (obs.issue.contains("bounds", ignoreCase = true) || obs.issue.contains("short", ignoreCase = true))) {
                malformed = true
                sampleFramingFailures++
                add(
                    "flac.sample.framing",
                    ConformanceStatus.FAIL,
                    "sample=${sample.index} ${obs.issue} / 샘플 범위 오류",
                    sample.offset..sample.offset,
                )
            } else {
                if (!obs.syncValid) {
                    malformed = true
                    headerFailures++
                    add(
                        "flac.frame.header",
                        ConformanceStatus.FAIL,
                        "sample=${sample.index} syncword or header invalid: ${obs.issue} / 프레임 동기 오류",
                        sample.offset until (sample.offset + minOf(obs.size, 16L)),
                    )
                } else if (!obs.headerCrcValid) {
                    malformed = true
                    headerFailures++
                    add(
                        "flac.frame.header",
                        ConformanceStatus.FAIL,
                        "sample=${sample.index} header CRC-8 mismatch: ${obs.issue} / 프레임 헤더 CRC-8 불일치",
                        sample.offset until (sample.offset + minOf(obs.size, 16L)),
                    )
                }

                if (!obs.frameCrcValid) {
                    malformed = true
                    frameCrcFailures++
                    add(
                        "flac.frame.crc",
                        ConformanceStatus.FAIL,
                        "sample=${sample.index} frame CRC-16 mismatch: ${obs.issue} / 프레임 CRC-16 불일치",
                        (sample.offset + obs.size - 2).coerceAtLeast(sample.offset) until (sample.offset + obs.size),
                    )
                }
            }

            checked++
            onProgress(checked, if (table.warnings.isEmpty() && otherAudioTracks == 0) table.samples.size else null)
        }

        val complete = !malformed && table.warnings.isEmpty() && table.samples.isNotEmpty() && otherAudioTracks == 0 && config.issue == null
        if (complete && headerFailures == 0) {
            add("flac.frame.header", ConformanceStatus.PASS, "checked=$checked samples; frame headers and CRC-8 valid / 프레임 헤더 및 CRC-8 정상")
        }
        if (complete && frameCrcFailures == 0) {
            add("flac.frame.crc", ConformanceStatus.PASS, "checked=$checked samples; frame CRC-16 valid / 프레임 CRC-16 정상")
        }
        if (complete && sampleFramingFailures == 0) {
            add("flac.sample.framing", ConformanceStatus.PASS, "checked=$checked samples; all sample extents within file bounds / 모든 샘플 바이트 범위 정상")
        }
        if (!complete) {
            limitations += "Partial FLAC sample coverage / FLAC 샘플 일부 미검증"
        }

        report(complete, checked, table.samples.size)
    }
}
