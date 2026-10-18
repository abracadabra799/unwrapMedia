package com.multiviewer.parser.conformance

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.VideoCodec
import com.multiviewer.parser.boxDescendants
import com.multiviewer.parser.buildVideoSampleTable
import com.multiviewer.parser.extractAvcCRawParameterSets
import com.multiviewer.parser.firstVideoSampleEntry
import com.multiviewer.parser.parseH264Pps
import com.multiviewer.parser.parseH264Sps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

private const val MAX_REPORTED_OCCURRENCES_PER_RULE = 100

/** Scans the first supported AVC video track. Every finding remains a structural observation. */
suspend fun analyzeAvcBitstream(
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
        CodecBitstreamReport(BitstreamCodec.AVC, findings, checked, indexed, totals, limitations,
            "video track #$trackIndex (${tag ?: "unknown"})", complete)
    if (tag != "avc1") {
        limitations += "AVC avc1 video sample entry not found / AVC 비디오 샘플 엔트리 없음"
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
    val avcC = entry?.children?.firstOrNull { it.type == "avcC" }
    if (avcC == null || avcC.size - avcC.headerSize < 7 || avcC.size > 1_000_000) {
        limitations += "avcC missing, truncated, or too large / avcC 없음·잘림·과대"
        return@withContext report(false)
    }
    if (avcC.warnings.isNotEmpty()) {
        limitations += "avcC array layout is malformed: ${avcC.warnings.take(3).joinToString()} / avcC 배열 손상"
        return@withContext report(false)
    }
    ByteReader.open(file).use { reader ->
        val payload = avcC.offset + avcC.headerSize
        if (payload < 0 || payload >= reader.length || reader.readUInt8(payload) != 1) {
            limitations += "avcC configurationVersion is not 1 / avcC 버전 오류"
            return@use report(false)
        }
        val table = buildVideoSampleTable(reader, root)
        if (table == null || table.codec != VideoCodec.H264 || table.nalLengthSize !in 1..4) {
            limitations += "avcC or sample table unavailable / avcC 또는 샘플 표 사용 불가"
            return@use report(false)
        }
        limitations += table.warnings
        if (table.samples.isEmpty()) limitations += "No indexed samples / 인덱싱된 샘플 없음"
        val parameterSets = extractAvcCRawParameterSets(file, avcC)
        if (parameterSets == null) limitations += "avcC parameter arrays unreadable / 파라미터 배열 읽기 실패"
        else {
            if (parameterSets.spsList.any { parseH264Sps(it.bytes) == null } ||
                parameterSets.ppsList.any { parseH264Pps(it.bytes) == null }) {
                limitations += "Some SPS/PPS syntax is unsupported or unreadable; references not checked / 일부 파라미터 구문 미지원"
            }
        }
        limitations += "Slice references and complete AVC syntax are not checked / 슬라이스 참조와 전체 AVC 구문 미검증"
        fun add(id: String, status: ConformanceStatus, detail: String, range: LongRange = LongRange.EMPTY) {
            totals[id] = (totals[id] ?: 0L) + 1
            if (totals.getValue(id) <= MAX_REPORTED_OCCURRENCES_PER_RULE) {
                val rule = AvcRuleCatalog.rule(id) ?: return
                findings += ConformanceFinding(rule, status, listOf(detail),
                    if (range.isEmpty()) emptyList() else listOf(range))
            }
        }
        var checked = 0
        var malformed = false
        var headerCount = 0
        var idrCount = 0
        var forbiddenFailures = 0
        var idrRefFailures = 0
        for (sample in table.samples) {
            currentCoroutineContext().ensureActive()
            val scan = scanAvcSample(reader, sample, table.nalLengthSize!!) { nal ->
                if (nal.issue != null) {
                    if (nal.issue == "zero_length_nal") {
                        limitations += "sample=${nal.sampleIndex} nal=${nal.nalIndex}: zero-length NAL header not checked / 0길이 NAL 미검증"
                    } else {
                        malformed = true
                        add("avc.nal.framing", ConformanceStatus.FAIL,
                            "sample=${nal.sampleIndex} nal=${nal.nalIndex} observed=${nal.issue} expected=NAL within sample / 범위 안의 NAL",
                            nal.prefixOffset..nal.prefixOffset)
                    }
                } else if (nal.headerOffset != null) {
                    headerCount++
                    val headerRange = nal.headerOffset..nal.headerOffset
                    if (nal.forbiddenZeroBit != 0) {
                        forbiddenFailures++
                        add("avc.nal.forbidden_zero_bit", ConformanceStatus.FAIL,
                            "sample=${nal.sampleIndex} nal=${nal.nalIndex} type=${nal.nalType} observed=${nal.forbiddenZeroBit} expected=0",
                            headerRange)
                    }
                    if (nal.nalType == 5) {
                        idrCount++
                        if (nal.refIdc == 0) {
                            idrRefFailures++
                            add("avc.nal.idr_ref_idc", ConformanceStatus.FAIL,
                                "sample=${nal.sampleIndex} nal=${nal.nalIndex} type=5 observed=0 expected=1..3",
                                headerRange)
                        }
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
        if (complete) add("avc.nal.framing", ConformanceStatus.PASS,
            "checked=$checked indexed=${table.samples.size}; every NAL extent fits / NAL 범위 일치")
        else if (totals["avc.nal.framing"] == null) add("avc.nal.framing", ConformanceStatus.NOT_CHECKED,
            "checked=$checked indexed=${table.samples.size}; incomplete sample coverage / 일부 샘플 미검증")
        if (complete && headerCount > 0 && forbiddenFailures == 0) add("avc.nal.forbidden_zero_bit", ConformanceStatus.PASS,
            "checkedHeaders=$headerCount observed=0 expected=0")
        if (complete && idrCount > 0 && idrRefFailures == 0) add("avc.nal.idr_ref_idc", ConformanceStatus.PASS,
            "checkedIdrSlices=$idrCount observed=1..3 expected=1..3")
        if (!complete) limitations += "Partial AVC sample coverage / AVC 샘플 일부 미검증"
        report(complete, checked, table.samples.size)
    }
}
