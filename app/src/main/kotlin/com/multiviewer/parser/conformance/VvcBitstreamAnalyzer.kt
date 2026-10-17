package com.multiviewer.parser.conformance

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.VideoCodec
import com.multiviewer.parser.boxDescendants
import com.multiviewer.parser.buildVideoSampleTable
import com.multiviewer.parser.firstVideoSampleEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

private const val MAX_REPORTED_OCCURRENCES_PER_RULE = 100

/** Scans the first supported VVC video track. Every finding remains a structural observation. */
suspend fun analyzeVvcBitstream(
    file: File,
    root: BoxNode,
    onProgress: (Int, Int?) -> Unit,
): VvcBitstreamReport = withContext(Dispatchers.IO) {
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

    fun report(complete: Boolean, checked: Int = 0, indexed: Int = 0): VvcBitstreamReport =
        VvcBitstreamReport(
            findings = findings,
            checkedSamples = checked,
            indexedSamples = indexed,
            totalOccurrences = totals,
            limitations = limitations,
            checkedTrack = "video track #$trackIndex (${tag ?: "unknown"})",
            complete = complete,
        )

    if (tag !in setOf("vvc1", "vvi1")) {
        limitations += "VVC vvc1/vvi1 video sample entry not found / VVC 비디오 샘플 엔트리 없음"
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

    val vvcC = entry?.children?.firstOrNull { it.type == "vvcC" }
    if (vvcC == null || vvcC.size - vvcC.headerSize < 5 || vvcC.size > 1_000_000) {
        limitations += "vvcC missing, truncated, or too large / vvcC 없음·잘림·과대"
        return@withContext report(false)
    }
    if (vvcC.warnings.isNotEmpty()) {
        limitations += vvcC.warnings
    }

    ByteReader.open(file).use { reader ->
        val config = parseVvcConfig(reader, vvcC)

        val table = buildVideoSampleTable(reader, root)
        if (table == null || table.codec != VideoCodec.VVC || table.nalLengthSize !in 1..4) {
            limitations += "vvcC or sample table unavailable / vvcC 또는 샘플 표 사용 불가"
            return@use report(false)
        }
        limitations += table.warnings
        if (table.samples.isEmpty()) limitations += "No indexed samples / 인덱싱된 샘플 없음"

        fun add(id: String, status: ConformanceStatus, detail: String, range: LongRange = LongRange.EMPTY) {
            totals[id] = (totals[id] ?: 0L) + 1
            if (totals.getValue(id) <= MAX_REPORTED_OCCURRENCES_PER_RULE) {
                val rule = VvcRuleCatalog.rule(id) ?: return
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
                "vvc.config.vvcc",
                ConformanceStatus.PASS,
                "vvcC parsed: version=0, lengthSize=${config.lengthSizeMinusOne?.plus(1)}B, ptlPresent=${config.ptlPresent}, size=${config.maxPictureWidth}x${config.maxPictureHeight} / vvcC 파라미터 정상",
                vvcC.offset until (vvcC.offset + vvcC.size),
            )
        } else {
            malformed = true
            add(
                "vvc.config.vvcc",
                ConformanceStatus.FAIL,
                "vvcC invalid: ${config.issue} / vvcC 구문 오류",
                vvcC.offset until (vvcC.offset + vvcC.size),
            )
        }

        limitations += "Slice headers, ALF/LMCS/LFNST/MTS transform verification, and reference picture lists are not checked / 슬라이스 및 변환 미검증"

        var checked = 0
        var framingFailures = 0
        var sampleFramingFailures = 0
        var forbiddenFailures = 0
        var reservedFailures = 0
        var temporalFailures = 0

        for (sample in table.samples) {
            currentCoroutineContext().ensureActive()
            val obs = scanVvcSample(reader, sample, table.nalLengthSize!!)
            if (obs.issue != null) {
                malformed = true
                if (obs.issue.contains("bounds", ignoreCase = true) || obs.issue.contains("length", ignoreCase = true)) {
                    sampleFramingFailures++
                    add(
                        "vvc.sample.framing",
                        ConformanceStatus.FAIL,
                        "sample=${sample.index} ${obs.issue} / 샘플 범위 오류",
                        sample.offset..sample.offset,
                    )
                } else {
                    framingFailures++
                    add(
                        "vvc.nal.framing",
                        ConformanceStatus.FAIL,
                        "sample=${sample.index} ${obs.issue} / NAL 프레이밍 오류",
                        sample.offset..sample.offset,
                    )
                }
            }

            for (nal in obs.nals) {
                if (nal.forbiddenZeroBit) {
                    malformed = true
                    forbiddenFailures++
                    add(
                        "vvc.nal.forbidden_zero_bit",
                        ConformanceStatus.FAIL,
                        "sample=${sample.index} NAL @ ${nal.offset}: forbidden_zero_bit = 1 (type=${nal.nalUnitType}) / forbidden_zero_bit 위반",
                        nal.offset until (nal.offset + 2),
                    )
                }
                if (nal.reservedZeroBit) {
                    malformed = true
                    reservedFailures++
                    add(
                        "vvc.nal.reserved_zero_bit",
                        ConformanceStatus.FAIL,
                        "sample=${sample.index} NAL @ ${nal.offset}: nuh_reserved_zero_bit = 1 (type=${nal.nalUnitType}) / nuh_reserved_zero_bit 위반",
                        nal.offset until (nal.offset + 2),
                    )
                }
                if (nal.temporalIdPlus1 == 0) {
                    malformed = true
                    temporalFailures++
                    add(
                        "vvc.nal.temporal_id_plus1",
                        ConformanceStatus.FAIL,
                        "sample=${sample.index} NAL @ ${nal.offset}: nuh_temporal_id_plus1 = 0 (forbidden value) / nuh_temporal_id_plus1 위반",
                        nal.offset until (nal.offset + 2),
                    )
                }
            }

            checked++
            onProgress(checked, if (table.warnings.isEmpty() && otherVideoTracks == 0) table.samples.size else null)
        }

        val complete = !malformed && table.warnings.isEmpty() && table.samples.isNotEmpty() && otherVideoTracks == 0 && config.issue == null
        if (complete && framingFailures == 0) {
            add("vvc.nal.framing", ConformanceStatus.PASS, "checked=$checked samples; length-prefixed NAL unit framing valid / NAL 프레이밍 정상")
        }
        if (complete && forbiddenFailures == 0) {
            add("vvc.nal.forbidden_zero_bit", ConformanceStatus.PASS, "checked=$checked samples; forbidden_zero_bit = 0 on all NALs / forbidden_zero_bit 정상")
        }
        if (complete && reservedFailures == 0) {
            add("vvc.nal.reserved_zero_bit", ConformanceStatus.PASS, "checked=$checked samples; nuh_reserved_zero_bit = 0 on all NALs / nuh_reserved_zero_bit 정상")
        }
        if (complete && temporalFailures == 0) {
            add("vvc.nal.temporal_id_plus1", ConformanceStatus.PASS, "checked=$checked samples; nuh_temporal_id_plus1 != 0 on all NALs / nuh_temporal_id_plus1 정상")
        }
        if (complete && sampleFramingFailures == 0) {
            add("vvc.sample.framing", ConformanceStatus.PASS, "checked=$checked samples; all sample extents within file bounds / 모든 샘플 바이트 범위 정상")
        }
        if (!complete) {
            limitations += "Partial VVC sample coverage / VVC 샘플 일부 미검증"
        }

        report(complete, checked, table.samples.size)
    }
}
