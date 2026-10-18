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
 * Scans the first supported AAC (mp4a) audio track.
 * Every finding remains a structural observation until normative clauses are verified.
 */
suspend fun analyzeAacBitstream(
    file: File,
    root: BoxNode,
    onProgress: (Int, Int?) -> Unit,
): CodecBitstreamReport = withContext(Dispatchers.IO) {
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

    fun report(complete: Boolean, checked: Int = 0, indexed: Int = 0): CodecBitstreamReport =
        CodecBitstreamReport(BitstreamCodec.AAC, findings = findings,
            checkedSamples = checked,
            indexedSamples = indexed,
            totalOccurrences = totals,
            limitations = limitations,
            checkedTrack = "audio track #$trackIndex (${tag ?: "unknown"})",
            complete = complete,
        )

    if (entry == null || tag != "mp4a") {
        limitations += "AAC mp4a audio sample entry not found / AAC 오디오 샘플 엔트리 없음"
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

    val esds = boxDescendants(entry).firstOrNull { it.type == "esds" }
    if (esds == null || esds.size - esds.headerSize < 4 || esds.size > 1_000_000) {
        limitations += "esds missing, truncated, or too large / esds 없음·잘림·과대"
        return@withContext report(false)
    }
    if (esds.warnings.isNotEmpty()) {
        limitations += "esds box layout is malformed: ${esds.warnings.take(3).joinToString()} / esds 상자 손상"
        return@withContext report(false)
    }

    ByteReader.open(file).use { reader ->
        val config = parseAacConfig(reader, esds)
        if (config.issue != null && config.aot == null) {
            limitations += "esds box is malformed: ${config.issue} / esds 상자 손상"
            return@use report(false)
        }

        val table = buildAudioSampleTable(reader, root)
        if (table == null || table.format != "mp4a") {
            limitations += "Audio sample table unavailable / 샘플 표 사용 불가"
            return@use report(false)
        }
        limitations += table.warnings
        if (table.samples.isEmpty()) limitations += "No indexed samples / 인덱싱된 샘플 없음"

        fun add(id: String, status: ConformanceStatus, detail: String, range: LongRange = LongRange.EMPTY) {
            totals[id] = (totals[id] ?: 0L) + 1
            if (totals.getValue(id) <= MAX_REPORTED_OCCURRENCES_PER_RULE) {
                val rule = AacRuleCatalog.rule(id) ?: return
                findings += ConformanceFinding(
                    rule = rule,
                    status = status,
                    details = listOf(detail),
                    ranges = if (range.isEmpty()) emptyList() else listOf(range),
                )
            }
        }

        var malformed = false

        add(
            "aac.config.esds",
            ConformanceStatus.PASS,
            "esds box parsed: ES_Descriptor and DecoderConfigDescriptor (streamType=AudioStream) / esds 디스크립터 정상",
            esds.offset until (esds.offset + esds.size),
        )

        val entryChannelCount = entry.fields.firstOrNull { it.name == "channelcount" }?.value?.toIntOrNull()
        val entrySampleRate = entry.fields.firstOrNull { it.name == "samplerate" }?.value?.toDoubleOrNull()?.toInt()

        if (config.issue == null && config.aot != null) {
            add(
                "aac.config.asc",
                ConformanceStatus.PASS,
                "audioObjectType=${config.aot} samplingFrequencyIndex=${config.samplingFrequencyIndex} (${config.sampleRate}Hz) channelConfiguration=${config.channelConfig} / ASC 구문 정상",
                esds.offset until (esds.offset + esds.size),
            )
            // With SBR the entry may carry the output rate, and with PS a mono core decodes to stereo.
            val channelsMatch = entryChannelCount == config.channelConfig ||
                (config.psPresent && config.channelConfig == 1 && entryChannelCount == 2)
            val rateMatches = entrySampleRate == config.sampleRate ||
                (config.extensionSampleRate != null && entrySampleRate == config.extensionSampleRate)
            if (channelsMatch && rateMatches) {
                add(
                    "aac.config.match",
                    ConformanceStatus.PASS,
                    "entry channels=$entryChannelCount sampleRate=${entrySampleRate}Hz matches ASC / 샘플 엔트리와 ASC 일치",
                    entry.offset until (entry.offset + 28),
                )
            } else {
                malformed = true
                add(
                    "aac.config.match",
                    ConformanceStatus.FAIL,
                    "entry channels=$entryChannelCount sampleRate=${entrySampleRate}Hz vs ASC channels=${config.channelConfig} sampleRate=${config.sampleRate}Hz" +
                        (config.extensionSampleRate?.let { " (SBR ${it}Hz${if (config.psPresent) ", PS" else ""})" } ?: "") + " / 불일치",
                    entry.offset until (entry.offset + 28),
                )
            }
        } else if (config.issue != null) {
            malformed = true
            add(
                "aac.config.asc",
                ConformanceStatus.FAIL,
                "AudioSpecificConfig invalid: ${config.issue} / AudioSpecificConfig 구문 오류",
                esds.offset until (esds.offset + esds.size),
            )
        }

        limitations += "Huffman spectral decoding, scale factors, and PNS/TNS are not checked / 허프만 및 스케일 팩터 미검증"

        var checked = 0
        var adtsFailures = 0
        var elementFailures = 0
        var icsReservedFailures = 0
        var framingFailures = 0

        for (sample in table.samples) {
            currentCoroutineContext().ensureActive()
            val obs = scanAacSample(reader, sample, config.channelConfig ?: 2)
            if (obs.issue != null) {
                malformed = true
                when {
                    obs.hasAdtsSync -> {
                        adtsFailures++
                        add(
                            "aac.frame.adts_guard",
                            ConformanceStatus.FAIL,
                            "sample=${sample.index} raw sample begins with ADTS syncword 0xFFF / 원시 샘플에 ADTS 동기단어 포함",
                            sample.offset..(sample.offset + 1),
                        )
                    }
                    obs.icsReservedBit != null && obs.icsReservedBit != 0 -> {
                        icsReservedFailures++
                        add(
                            "aac.frame.ics_reserved",
                            ConformanceStatus.FAIL,
                            "sample=${sample.index} non-zero ics_reserved_bit=${obs.icsReservedBit} / ics_reserved_bit 예약 비트 0 아님",
                            sample.offset..(sample.offset + 1),
                        )
                    }
                    obs.issue.contains("elementId", ignoreCase = true) -> {
                        elementFailures++
                        add(
                            "aac.frame.element",
                            ConformanceStatus.FAIL,
                            "sample=${sample.index} ${obs.issue} / 구문 요소 식별자 오류",
                            sample.offset..sample.offset,
                        )
                    }
                    else -> {
                        framingFailures++
                        add(
                            "aac.sample.framing",
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
        if (complete && adtsFailures == 0) {
            add("aac.frame.adts_guard", ConformanceStatus.PASS, "checked=$checked samples; no ADTS syncword / ADTS 동기단어 미포함 확인")
        }
        if (complete && elementFailures == 0) {
            add("aac.frame.element", ConformanceStatus.PASS, "checked=$checked samples; syntactic element headers valid / 구문 요소 헤더 정상")
        }
        if (complete && icsReservedFailures == 0) {
            add("aac.frame.ics_reserved", ConformanceStatus.PASS, "checked=$checked samples; ics_reserved_bit=0 / ics_reserved_bit 예약 비트 정상")
        }
        if (complete && framingFailures == 0) {
            add("aac.sample.framing", ConformanceStatus.PASS, "checked=$checked samples; all sample extents within file bounds / 모든 샘플 바이트 범위 정상")
        }
        if (!complete) {
            limitations += "Partial AAC sample coverage / AAC 샘플 일부 미검증"
        }

        report(complete, checked, table.samples.size)
    }
}
