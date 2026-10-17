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
 * Scans the first supported Opus audio track.
 * Every finding remains a structural observation until normative clauses are verified.
 */
suspend fun analyzeOpusBitstream(
    file: File,
    root: BoxNode,
    onProgress: (Int, Int?) -> Unit,
): OpusBitstreamReport = withContext(Dispatchers.IO) {
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

    fun report(complete: Boolean, checked: Int = 0, indexed: Int = 0): OpusBitstreamReport =
        OpusBitstreamReport(
            findings = findings,
            checkedSamples = checked,
            indexedSamples = indexed,
            totalOccurrences = totals,
            limitations = limitations,
            checkedTrack = "audio track #$trackIndex (${tag ?: "unknown"})",
            complete = complete,
        )

    if (entry == null || tag != "Opus") {
        limitations += "Opus audio sample entry not found / Opus 오디오 샘플 엔트리 없음"
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

    val dOps = boxDescendants(entry).firstOrNull { it.type == "dOps" }
    if (dOps == null || dOps.size - dOps.headerSize < 11 || dOps.size > 1_000_000) {
        limitations += "dOps missing, truncated, or too large / dOps 없음·잘림·과대"
        return@withContext report(false)
    }
    if (dOps.warnings.isNotEmpty()) {
        limitations += "dOps box layout is malformed: ${dOps.warnings.take(3).joinToString()} / dOps 상자 손상"
        return@withContext report(false)
    }

    ByteReader.open(file).use { reader ->
        val config = parseOpusConfig(reader, dOps)

        val table = buildAudioSampleTable(reader, root)
        if (table == null || table.format != "Opus") {
            limitations += "Audio sample table unavailable / 샘플 표 사용 불가"
            return@use report(false)
        }
        limitations += table.warnings
        if (table.samples.isEmpty()) limitations += "No indexed samples / 인덱싱된 샘플 없음"

        fun add(id: String, status: ConformanceStatus, detail: String, range: LongRange = LongRange.EMPTY) {
            totals[id] = (totals[id] ?: 0L) + 1
            if (totals.getValue(id) <= MAX_REPORTED_OCCURRENCES_PER_RULE) {
                val rule = OpusRuleCatalog.rule(id) ?: return
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
                "opus.config.dops",
                ConformanceStatus.PASS,
                "dOps box parsed: version=0, channels=${config.channelCount}, preSkip=${config.preSkip}, rate=${config.sampleRate}Hz, gain=${config.gain}, family=${config.mappingFamily} / dOps 파라미터 정상",
                dOps.offset until (dOps.offset + dOps.size),
            )
        } else {
            malformed = true
            add(
                "opus.config.dops",
                ConformanceStatus.FAIL,
                "dOps invalid: ${config.issue} / dOps 구문 오류",
                dOps.offset until (dOps.offset + dOps.size),
            )
        }

        val entryChannelCount = entry.fields.firstOrNull { it.name == "channelcount" }?.value?.toIntOrNull()
        val entrySampleRate = entry.fields.firstOrNull { it.name == "samplerate" }?.value?.toDoubleOrNull()?.toInt()

        if (entryChannelCount == config.channelCount && (entrySampleRate == 48000 || (config.sampleRate != null && entrySampleRate == config.sampleRate.toInt()))) {
            add(
                "opus.config.match",
                ConformanceStatus.PASS,
                "entry channels=$entryChannelCount sampleRate=${entrySampleRate}Hz matches dOps / 샘플 엔트리와 dOps 일치",
                entry.offset until (entry.offset + 28),
            )
        } else {
            malformed = true
            add(
                "opus.config.match",
                ConformanceStatus.FAIL,
                "entry channels=$entryChannelCount sampleRate=${entrySampleRate}Hz vs dOps channels=${config.channelCount} sampleRate=${config.sampleRate}Hz / 불일치",
                entry.offset until (entry.offset + 28),
            )
        }

        limitations += "Full LPC analysis, pitch prediction, and MDCT synthesis are not checked / LPC 및 MDCT 미검증"

        var checked = 0
        var tocFailures = 0
        var framingFailures = 0
        var sampleFramingFailures = 0

        for (sample in table.samples) {
            currentCoroutineContext().ensureActive()
            val obs = scanOpusSample(reader, sample, config.channelCount ?: 1)
            if (obs.issue != null) {
                malformed = true
                when {
                    obs.issue.contains("stereo", ignoreCase = true) -> {
                        tocFailures++
                        add(
                            "opus.packet.toc",
                            ConformanceStatus.FAIL,
                            "sample=${sample.index} ${obs.issue} / TOC 구문 오류",
                            sample.offset..sample.offset,
                        )
                    }
                    obs.issue.contains("Code 3", ignoreCase = true) || obs.issue.contains("duration", ignoreCase = true) -> {
                        framingFailures++
                        add(
                            "opus.packet.framing",
                            ConformanceStatus.FAIL,
                            "sample=${sample.index} ${obs.issue} / 패킷 프레이밍 오류",
                            sample.offset..(sample.offset + 1),
                        )
                    }
                    else -> {
                        sampleFramingFailures++
                        add(
                            "opus.sample.framing",
                            ConformanceStatus.FAIL,
                            "sample=${sample.index} ${obs.issue} / 샘플 범위 오류",
                            sample.offset..sample.offset,
                        )
                    }
                }
            }
            checked++
            onProgress(checked, if (table.warnings.isEmpty() && otherAudioTracks == 0) table.samples.size else null)
        }

        val complete = !malformed && table.warnings.isEmpty() && table.samples.isNotEmpty() && otherAudioTracks == 0 && config.issue == null
        if (complete && tocFailures == 0) {
            add("opus.packet.toc", ConformanceStatus.PASS, "checked=$checked samples; packet TOC headers valid / TOC 구문 정상")
        }
        if (complete && framingFailures == 0) {
            add("opus.packet.framing", ConformanceStatus.PASS, "checked=$checked samples; packet framing valid / 패킷 프레이밍 정상")
        }
        if (complete && sampleFramingFailures == 0) {
            add("opus.sample.framing", ConformanceStatus.PASS, "checked=$checked samples; all sample extents within file bounds / 모든 샘플 바이트 범위 정상")
        }
        if (!complete) {
            limitations += "Partial Opus sample coverage / Opus 샘플 일부 미검증"
        }

        report(complete, checked, table.samples.size)
    }
}
