package com.multiviewer.parser.conformance

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.VideoCodec
import com.multiviewer.parser.boxDescendants
import com.multiviewer.parser.buildVideoSampleTable
import com.multiviewer.parser.extractAv1CRawSequenceHeader
import com.multiviewer.parser.firstVideoSampleEntry
import com.multiviewer.parser.parseAv1SequenceHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

private const val MAX_REPORTED_OCCURRENCES_PER_RULE = 100

/** Scans the first supported AV1 video track. Every finding remains a structural observation. */
suspend fun analyzeAv1Bitstream(
    file: File,
    root: BoxNode,
    onProgress: (Int, Int?) -> Unit,
): CodecBitstreamReport = withContext(Dispatchers.IO) {
    val limitations = mutableListOf<String>()
    val findings = mutableListOf<ConformanceFinding>()
    val totals = mutableMapOf<String, Long>()
    val pair = firstVideoSampleEntry(root)
    val entry = pair?.second
    val tag = entry?.type
    val videoTracks = boxDescendants(root).filter { it.type == "trak" }.filter { trak ->
        boxDescendants(trak).any { node -> node.type == "hdlr" && node.fields.any { it.name == "handler_type" && it.value == "vide" } }
    }.toList()
    val trackIndex = videoTracks.indexOf(pair?.first).takeIf { it >= 0 }?.plus(1) ?: 1
    val otherVideoTracks = (videoTracks.size - 1).coerceAtLeast(0)

    fun report(complete: Boolean, checked: Int = 0, indexed: Int = 0): CodecBitstreamReport =
        CodecBitstreamReport(BitstreamCodec.AV1, findings = findings,
            checkedSamples = checked,
            indexedSamples = indexed,
            totalOccurrences = totals,
            limitations = limitations,
            checkedTrack = "video track #$trackIndex (${tag ?: "unknown"})",
            complete = complete,
        )

    if (tag != "av01") {
        limitations += "AV1 av01 video sample entry not found / AV1 비디오 샘플 엔트리 없음"
        return@withContext report(false)
    }
    if (otherVideoTracks > 0) limitations += "$otherVideoTracks other video track(s) not checked / 다른 영상 트랙 미검증"
    if (boxDescendants(root).any { it.type == "moof" || it.type == "mvex" }) {
        limitations += "Fragmented MP4 samples are not supported / 조각난 MP4 샘플 미지원"
        return@withContext report(false)
    }
    if (entry?.children?.any { it.type == "sinf" } == true ||
        pair?.first?.let { boxDescendants(it).any { node -> node.type == "encv" || node.type == "sinf" } } == true) {
        limitations += "Encrypted video samples are not supported / 암호화 샘플 미지원"
        return@withContext report(false)
    }
    val av1C = entry?.children?.firstOrNull { it.type == "av1C" }
    if (av1C == null || av1C.size - av1C.headerSize < 4 || av1C.size > 1_000_000) {
        limitations += "av1C missing, truncated, or too large / av1C 없음·잘림·과대"
        return@withContext report(false)
    }
    if (av1C.warnings.isNotEmpty()) {
        limitations += "av1C box layout is malformed: ${av1C.warnings.take(3).joinToString()} / av1C 상자 손상"
        return@withContext report(false)
    }

    ByteReader.open(file).use { reader ->
        val payload = av1C.offset + av1C.headerSize
        if (payload < 0 || payload >= reader.length) {
            limitations += "av1C payload outside file / av1C 파일 범위 초과"
            return@use report(false)
        }
        val b0 = reader.readUInt8(payload)
        val marker = (b0 shr 7) and 1
        val version = b0 and 0x7F
        if (marker != 1 || version != 1) {
            limitations += "av1C marker bit or version is invalid (marker=$marker, version=$version) / av1C 마커·버전 오류"
            return@use report(false)
        }

        val table = buildVideoSampleTable(reader, root)
        if (table == null || table.codec != VideoCodec.AV1) {
            limitations += "av1C or sample table unavailable / av1C 또는 샘플 표 사용 불가"
            return@use report(false)
        }
        limitations += table.warnings
        if (table.samples.isEmpty()) limitations += "No indexed samples / 인덱싱된 샘플 없음"

        fun add(id: String, status: ConformanceStatus, detail: String, range: LongRange = LongRange.EMPTY) {
            totals[id] = (totals[id] ?: 0L) + 1
            if (totals.getValue(id) <= MAX_REPORTED_OCCURRENCES_PER_RULE) {
                val rule = Av1RuleCatalog.rule(id) ?: return
                findings += ConformanceFinding(
                    rule = rule,
                    status = status,
                    details = listOf(detail),
                    ranges = if (range.isEmpty()) emptyList() else listOf(range),
                )
            }
        }

        val rawSeqHeader = extractAv1CRawSequenceHeader(file, av1C)
        if (rawSeqHeader == null) {
            limitations += "av1C Sequence Header OBU unreadable or missing / av1C 시퀀스 헤더 OBU 읽기 실패"
            add("av1.config.sequence_header", ConformanceStatus.NOT_CHECKED, "av1C Sequence Header OBU unreadable / av1C 시퀀스 헤더 OBU 읽기 불가")
        } else {
            val seq = parseAv1SequenceHeader(rawSeqHeader.bytes)
            val seqRange = rawSeqHeader.offset until (rawSeqHeader.offset + rawSeqHeader.bytes.size)
            if (seq == null) {
                limitations += "Sequence Header syntax is unsupported or unreadable / 시퀀스 헤더 구문 미지원"
                add("av1.config.sequence_header", ConformanceStatus.NOT_CHECKED, "Sequence Header syntax unsupported or unreadable / 시퀀스 헤더 구문 미지원", seqRange)
            } else {
                add("av1.config.sequence_header", ConformanceStatus.PASS, "profile=${seq.seqProfile} level=${seq.seqLevelIdx0} tier=${seq.seqTierIdx0} bitDepth=${seq.bitDepth} / 프로필=${seq.seqProfile} 레벨=${seq.seqLevelIdx0}", seqRange)
            }
        }

        limitations += "Tile/transform decoding, CDF symbol decoding, and full AV1 syntax are not checked / 타일 디코딩과 전체 AV1 구문 미검증"

        var checked = 0
        var malformed = false
        var headerCount = 0
        var forbiddenFailures = 0
        var reservedFailures = 0
        var extReservedFailures = 0

        for (sample in table.samples) {
            currentCoroutineContext().ensureActive()
            val scan = scanAv1Sample(reader, sample) { obu ->
                if (obu.issue != null) {
                    malformed = true
                    add(
                        "av1.obu.framing",
                        ConformanceStatus.FAIL,
                        "sample=${obu.sampleIndex} obu=${obu.obuIndex} observed=${obu.issue} expected=OBU within sample / 범위 안의 OBU",
                        obu.headerOffset..obu.headerOffset,
                    )
                } else {
                    headerCount++
                    val headerLen = if (obu.extensionFlag) 2 else 1
                    val headerRange = obu.headerOffset until (obu.headerOffset + headerLen)
                    if (obu.forbiddenBit != 0) {
                        forbiddenFailures++
                        add(
                            "av1.obu.forbidden_bit",
                            ConformanceStatus.FAIL,
                            "sample=${obu.sampleIndex} obu=${obu.obuIndex} type=${obu.obuType} observed=${obu.forbiddenBit} expected=0",
                            headerRange,
                        )
                    }
                    if (obu.reservedBit != 0) {
                        reservedFailures++
                        add(
                            "av1.obu.reserved_bit",
                            ConformanceStatus.FAIL,
                            "sample=${obu.sampleIndex} obu=${obu.obuIndex} type=${obu.obuType} observed=${obu.reservedBit} expected=0",
                            headerRange,
                        )
                    }
                    if (obu.extensionFlag && obu.extensionReservedBits != null && obu.extensionReservedBits != 0) {
                        extReservedFailures++
                        add(
                            "av1.obu.extension_reserved_bits",
                            ConformanceStatus.FAIL,
                            "sample=${obu.sampleIndex} obu=${obu.obuIndex} observed=${obu.extensionReservedBits} expected=0",
                            headerRange,
                        )
                    }
                }
            }
            checked++
            if (scan.coverageReason != null) {
                malformed = true
                limitations += "sample=${sample.index}: ${scan.coverageReason} / 샘플 범위 미검증"
            }
            onProgress(checked, if (table.warnings.isEmpty() && otherVideoTracks == 0) table.samples.size else null)
        }

        val complete = !malformed && table.warnings.isEmpty() && table.samples.isNotEmpty() && otherVideoTracks == 0
        if (complete) {
            add("av1.obu.framing", ConformanceStatus.PASS, "checked=$checked indexed=${table.samples.size}; every OBU extent fits / OBU 범위 일치")
        } else if (totals["av1.obu.framing"] == null) {
            add("av1.obu.framing", ConformanceStatus.NOT_CHECKED, "checked=$checked indexed=${table.samples.size}; incomplete sample coverage / 일부 샘플 미검증")
        }
        if (complete && headerCount > 0 && forbiddenFailures == 0) {
            add("av1.obu.forbidden_bit", ConformanceStatus.PASS, "checkedHeaders=$headerCount observed=0 expected=0")
        }
        if (complete && headerCount > 0 && reservedFailures == 0) {
            add("av1.obu.reserved_bit", ConformanceStatus.PASS, "checkedHeaders=$headerCount observed=0 expected=0")
        }
        if (complete && headerCount > 0 && extReservedFailures == 0) {
            add("av1.obu.extension_reserved_bits", ConformanceStatus.PASS, "checkedHeaders=$headerCount observed=0 expected=0")
        }
        if (!complete) {
            limitations += "Partial AV1 sample coverage / AV1 샘플 일부 미검증"
        }
        report(complete, checked, table.samples.size)
    }
}
