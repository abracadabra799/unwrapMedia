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

/** Scans the first supported APV video track. Every finding remains a structural observation. */
suspend fun analyzeApvBitstream(
    file: File,
    root: BoxNode,
    onProgress: (Int, Int?) -> Unit,
): ApvBitstreamReport = withContext(Dispatchers.IO) {
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

    fun report(complete: Boolean, checked: Int = 0, indexed: Int = 0): ApvBitstreamReport =
        ApvBitstreamReport(
            findings = findings,
            checkedSamples = checked,
            indexedSamples = indexed,
            totalOccurrences = totals,
            limitations = limitations,
            checkedTrack = "video track #$trackIndex (${tag ?: "unknown"})",
            complete = complete,
        )

    if (tag != "apv1") {
        limitations += "APV apv1 video sample entry not found / APV 비디오 샘플 엔트리 없음"
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
    val apvC = entry?.children?.firstOrNull { it.type == "apvC" }
    if (apvC == null || apvC.size - apvC.headerSize < 21 || apvC.size > 1_000_000) {
        limitations += "apvC missing, truncated, or too large / apvC 없음·잘림·과대"
        return@withContext report(false)
    }
    if (apvC.warnings.isNotEmpty()) {
        limitations += "apvC box layout is malformed: ${apvC.warnings.take(3).joinToString()} / apvC 상자 손상"
        return@withContext report(false)
    }

    ByteReader.open(file).use { reader ->
        val payload = apvC.offset + apvC.headerSize
        if (payload < 0 || payload + 21 > reader.length) {
            limitations += "apvC payload outside file / apvC 파일 범위 초과"
            return@use report(false)
        }

        val profileIdc = reader.readUInt8(payload + 9)
        val levelIdc = reader.readUInt8(payload + 10)
        val bandIdc = reader.readUInt8(payload + 11)
        val frameWidth = reader.readUInt32(payload + 12)
        val frameHeight = reader.readUInt32(payload + 16)
        val chromaByte = reader.readUInt8(payload + 20)
        val chromaFormatIdc = (chromaByte shr 4) and 0x0F
        val bitDepth = (chromaByte and 0x0F) + 8

        val table = buildVideoSampleTable(reader, root)
        if (table == null || table.codec != VideoCodec.APV) {
            limitations += "apvC or sample table unavailable / apvC 또는 샘플 표 사용 불가"
            return@use report(false)
        }
        limitations += table.warnings
        if (table.samples.isEmpty()) limitations += "No indexed samples / 인덱싱된 샘플 없음"

        fun add(id: String, status: ConformanceStatus, detail: String, range: LongRange = LongRange.EMPTY) {
            totals[id] = (totals[id] ?: 0L) + 1
            if (totals.getValue(id) <= MAX_REPORTED_OCCURRENCES_PER_RULE) {
                val rule = ApvRuleCatalog.rule(id) ?: return
                findings += ConformanceFinding(
                    rule = rule,
                    status = status,
                    details = listOf(detail),
                    ranges = if (range.isEmpty()) emptyList() else listOf(range),
                )
            }
        }

        add(
            "apv.config.apvc",
            ConformanceStatus.PASS,
            "profile=$profileIdc level=$levelIdc band=$bandIdc ${frameWidth}x${frameHeight} chroma=$chromaFormatIdc bitDepth=$bitDepth / 프로필=$profileIdc 레벨=$levelIdc",
            apvC.offset until (apvC.offset + apvC.size),
        )

        limitations += "Tile layout, quantization matrix, and non-primary PBUs are not checked / 타일과 부가 PBU 미검증"

        var checked = 0
        var malformed = false
        var signatureFailures = 0
        var framingFailures = 0
        var pbuReservedFailures = 0
        var bandReservedFailures = 0
        var frameReservedFailures = 0

        for (sample in table.samples) {
            currentCoroutineContext().ensureActive()
            val obs = scanApvSample(reader, sample)
            if (obs.issue != null) {
                malformed = true
                if (obs.signature != null && obs.signature != 0x61507631) {
                    signatureFailures++
                    add(
                        "apv.au.signature",
                        ConformanceStatus.FAIL,
                        "sample=${sample.index} observed=0x${obs.signature.toUInt().toString(16)} expected=0x61507631 ('aPv1')",
                        (sample.offset + 4)..(sample.offset + 7),
                    )
                } else {
                    framingFailures++
                    add(
                        "apv.pbu.framing",
                        ConformanceStatus.FAIL,
                        "sample=${sample.index} issue=${obs.issue}",
                        sample.offset..sample.offset,
                    )
                }
            } else {
                if (obs.pbuReservedZero != null && obs.pbuReservedZero != 0) {
                    pbuReservedFailures++
                    val pbuResOffset = sample.offset + 8 + 4 + 3
                    add(
                        "apv.pbu.reserved_zero",
                        ConformanceStatus.FAIL,
                        "sample=${sample.index} observed=${obs.pbuReservedZero} expected=0",
                        pbuResOffset..pbuResOffset,
                    )
                }
                if ((obs.bandIdc != null && obs.bandIdc > 3) || (obs.reservedZero5bits != null && obs.reservedZero5bits != 0)) {
                    bandReservedFailures++
                    val bandOffset = obs.primaryFrameOffset?.plus(2) ?: sample.offset
                    add(
                        "apv.frame.profile",
                        ConformanceStatus.FAIL,
                        "sample=${sample.index} band_idc=${obs.bandIdc} (expected <= 3), reserved_zero_5bits=${obs.reservedZero5bits} (expected 0)",
                        bandOffset..bandOffset,
                    )
                }
                if ((obs.frameInfoReservedZero != null && obs.frameInfoReservedZero != 0) ||
                    (obs.frameHeaderReservedZero != null && obs.frameHeaderReservedZero != 0)) {
                    frameReservedFailures++
                    val resOffset = obs.primaryFrameOffset?.plus(10) ?: sample.offset
                    add(
                        "apv.frame.reserved_zero",
                        ConformanceStatus.FAIL,
                        "sample=${sample.index} non-zero frame reserved byte",
                        resOffset..resOffset,
                    )
                }
            }
            checked++
            onProgress(checked, if (table.warnings.isEmpty() && otherVideoTracks == 0) table.samples.size else null)
        }

        val complete = !malformed && table.warnings.isEmpty() && table.samples.isNotEmpty() && otherVideoTracks == 0
        if (complete && signatureFailures == 0) {
            add("apv.au.signature", ConformanceStatus.PASS, "checked=$checked samples; signature='aPv1' / 접근 단위 서명 일치")
        }
        if (complete && framingFailures == 0) {
            add("apv.pbu.framing", ConformanceStatus.PASS, "checked=$checked samples; PBU framing valid / PBU 프레이밍 일치")
        }
        if (complete && pbuReservedFailures == 0) {
            add("apv.pbu.reserved_zero", ConformanceStatus.PASS, "checked=$checked samples; pbu_reserved_zero=0 / PBU 예약 바이트 정상")
        }
        if (complete && bandReservedFailures == 0) {
            add("apv.frame.profile", ConformanceStatus.PASS, "checked=$checked samples; band_idc in 0..3, reserved_zero_5bits=0 / 프로필·밴드 정상")
        }
        if (complete && frameReservedFailures == 0) {
            add("apv.frame.reserved_zero", ConformanceStatus.PASS, "checked=$checked samples; frame_reserved_zero=0 / 프레임 헤더 예약 바이트 정상")
        }
        if (!complete) {
            if (totals["apv.au.signature"] == null) {
                add("apv.au.signature", ConformanceStatus.NOT_CHECKED, "incomplete sample coverage / 일부 샘플 미검증")
            }
            if (totals["apv.pbu.framing"] == null) {
                add("apv.pbu.framing", ConformanceStatus.NOT_CHECKED, "incomplete sample coverage / 일부 샘플 미검증")
            }
            limitations += "Partial APV sample coverage / APV 샘플 일부 미검증"
        }
        report(complete, checked, table.samples.size)
    }
}
