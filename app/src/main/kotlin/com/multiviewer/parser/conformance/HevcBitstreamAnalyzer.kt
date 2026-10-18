package com.multiviewer.parser.conformance

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.VideoCodec
import com.multiviewer.parser.boxDescendants
import com.multiviewer.parser.buildVideoSampleTable
import com.multiviewer.parser.extractHvcCRawParameterSets
import com.multiviewer.parser.firstVideoSampleEntry
import com.multiviewer.parser.parseHevcPps
import com.multiviewer.parser.parseHevcSps
import com.multiviewer.parser.parseHevcVps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

private const val MAX_REPORTED_OCCURRENCES_PER_RULE = 100

/** Scans the first supported HEVC video track. Every finding remains a structural observation. */
suspend fun analyzeHevcBitstream(
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
        CodecBitstreamReport(BitstreamCodec.HEVC, findings, checked, indexed, totals, limitations,
            "video track #$trackIndex (${tag ?: "unknown"})", complete)
    if (tag !in setOf("hvc1", "hev1")) {
        limitations += "HEVC hvc1/hev1 video sample entry not found / HEVC 비디오 샘플 엔트리 없음"
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
    val hvcC = entry?.children?.firstOrNull { it.type == "hvcC" }
    if (hvcC == null || hvcC.size - hvcC.headerSize < 23 || hvcC.size > 1_000_000) {
        limitations += "hvcC missing, truncated, or too large / hvcC 없음·잘림·과대"
        return@withContext report(false)
    }
    if (hvcC.warnings.isNotEmpty()) {
        limitations += "hvcC array layout is malformed: ${hvcC.warnings.take(3).joinToString()} / hvcC 배열 손상"
        return@withContext report(false)
    }
    ByteReader.open(file).use { reader ->
        val payload = hvcC.offset + hvcC.headerSize
        if (payload < 0 || payload >= reader.length || reader.readUInt8(payload) != 1) {
            limitations += "hvcC configurationVersion is not 1 / hvcC 버전 오류"
            return@use report(false)
        }
        val table = buildVideoSampleTable(reader, root)
        if (table == null || table.codec != VideoCodec.HEVC || table.nalLengthSize !in 1..4) {
            limitations += "hvcC or sample table unavailable / hvcC 또는 샘플 표 사용 불가"
            return@use report(false)
        }
        limitations += table.warnings
        if (table.samples.isEmpty()) limitations += "No indexed samples / 인덱싱된 샘플 없음"
        val parameterSets = extractHvcCRawParameterSets(file, hvcC)
        if (parameterSets == null) limitations += "hvcC parameter arrays unreadable / 파라미터 배열 읽기 실패"
        else {
            if (parameterSets.vpsList.any { parseHevcVps(it.bytes) == null } ||
                parameterSets.spsList.any { parseHevcSps(it.bytes) == null } ||
                parameterSets.ppsList.any { parseHevcPps(it.bytes) == null }) {
                limitations += "Some VPS/SPS/PPS syntax is unsupported or unreadable; references not checked / 일부 파라미터 구문 미지원"
            }
        }
        limitations += "Slice references and complete HEVC syntax are not checked / 슬라이스 참조와 전체 HEVC 구문 미검증"
        fun add(id: String, status: ConformanceStatus, detail: String, range: LongRange = LongRange.EMPTY) {
            totals[id] = (totals[id] ?: 0L) + 1
            if (totals.getValue(id) <= MAX_REPORTED_OCCURRENCES_PER_RULE) {
                val rule = HevcRuleCatalog.rule(id) ?: return
                findings += ConformanceFinding(rule, status, listOf(detail),
                    if (range.isEmpty()) emptyList() else listOf(range))
            }
        }
        var checked = 0
        var malformed = false
        var headerCount = 0
        var forbiddenFailures = 0
        var temporalFailures = 0
        for (sample in table.samples) {
            currentCoroutineContext().ensureActive()
            val scan = scanHevcSample(reader, sample, table.nalLengthSize!!) { nal ->
                if (nal.issue != null) {
                    if (nal.issue == "zero_length_nal") {
                        limitations += "sample=${nal.sampleIndex} nal=${nal.nalIndex}: zero-length NAL header not checked / 0길이 NAL 미검증"
                    } else {
                        malformed = true
                        add("hevc.nal.framing", ConformanceStatus.FAIL,
                            "sample=${nal.sampleIndex} nal=${nal.nalIndex} observed=${nal.issue} expected=NAL within sample / 범위 안의 NAL",
                            nal.prefixOffset..nal.prefixOffset)
                    }
                } else if (nal.headerOffset != null) {
                    headerCount++
                    val headerRange = nal.headerOffset..(nal.headerOffset + 1)
                    if (nal.forbiddenZeroBit != 0) {
                        forbiddenFailures++
                        add("hevc.nal.forbidden_zero_bit", ConformanceStatus.FAIL,
                            "sample=${nal.sampleIndex} nal=${nal.nalIndex} type=${nal.nalType} observed=${nal.forbiddenZeroBit} expected=0",
                            headerRange)
                    }
                    if (nal.temporalIdPlus1 == 0) {
                        temporalFailures++
                        add("hevc.nal.temporal_id_plus1", ConformanceStatus.FAIL,
                            "sample=${nal.sampleIndex} nal=${nal.nalIndex} type=${nal.nalType} observed=0 expected=1..7",
                            headerRange)
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
        if (complete) add("hevc.nal.framing", ConformanceStatus.PASS,
            "checked=$checked indexed=${table.samples.size}; every NAL extent fits / NAL 범위 일치")
        else if (totals["hevc.nal.framing"] == null) add("hevc.nal.framing", ConformanceStatus.NOT_CHECKED,
            "checked=$checked indexed=${table.samples.size}; incomplete sample coverage / 일부 샘플 미검증")
        if (complete && headerCount > 0 && forbiddenFailures == 0) add("hevc.nal.forbidden_zero_bit", ConformanceStatus.PASS,
            "checkedHeaders=$headerCount observed=0 expected=0")
        if (complete && headerCount > 0 && temporalFailures == 0) add("hevc.nal.temporal_id_plus1", ConformanceStatus.PASS,
            "checkedHeaders=$headerCount observed=1..7 expected=1..7")
        if (!complete) limitations += "Partial HEVC sample coverage / HEVC 샘플 일부 미검증"
        report(complete, checked, table.samples.size)
    }
}
