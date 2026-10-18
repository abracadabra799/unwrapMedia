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

/** Scans the first supported VP9 video track. Every finding remains a structural observation. */
suspend fun analyzeVp9Bitstream(
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
        CodecBitstreamReport(BitstreamCodec.VP9, findings = findings,
            checkedSamples = checked,
            indexedSamples = indexed,
            totalOccurrences = totals,
            limitations = limitations,
            checkedTrack = "video track #$trackIndex (${tag ?: "unknown"})",
            complete = complete,
        )

    if (tag != "vp09") {
        limitations += "VP9 vp09 video sample entry not found / VP9 비디오 샘플 엔트리 없음"
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
    val vpcC = entry?.children?.firstOrNull { it.type == "vpcC" }
    if (vpcC == null || vpcC.size - vpcC.headerSize < 12 || vpcC.size > 1_000_000) {
        limitations += "vpcC missing, truncated, or too large / vpcC 없음·잘림·과대"
        return@withContext report(false)
    }
    if (vpcC.warnings.isNotEmpty()) {
        limitations += "vpcC box layout is malformed: ${vpcC.warnings.take(3).joinToString()} / vpcC 상자 손상"
        return@withContext report(false)
    }

    ByteReader.open(file).use { reader ->
        val payload = vpcC.offset + vpcC.headerSize
        if (payload < 0 || payload >= reader.length) {
            limitations += "vpcC payload outside file / vpcC 파일 범위 초과"
            return@use report(false)
        }
        val version = reader.readUInt8(payload)
        if (version != 1 && version != 0) {
            limitations += "vpcC FullBox version is invalid (version=$version) / vpcC 버전 오류"
            return@use report(false)
        }

        val table = buildVideoSampleTable(reader, root)
        if (table == null || table.codec != VideoCodec.VP9) {
            limitations += "vpcC or sample table unavailable / vpcC 또는 샘플 표 사용 불가"
            return@use report(false)
        }
        limitations += table.warnings
        if (table.samples.isEmpty()) limitations += "No indexed samples / 인덱싱된 샘플 없음"

        fun add(id: String, status: ConformanceStatus, detail: String, range: LongRange = LongRange.EMPTY) {
            totals[id] = (totals[id] ?: 0L) + 1
            if (totals.getValue(id) <= MAX_REPORTED_OCCURRENCES_PER_RULE) {
                val rule = Vp9RuleCatalog.rule(id) ?: return
                findings += ConformanceFinding(
                    rule = rule,
                    status = status,
                    details = listOf(detail),
                    ranges = if (range.isEmpty()) emptyList() else listOf(range),
                )
            }
        }

        val recordStart = payload + 4
        val profile = reader.readUInt8(recordStart)
        val level = reader.readUInt8(recordStart + 1)
        val b2 = reader.readUInt8(recordStart + 2)
        val bitDepth = (b2 shr 4) and 0x0F
        val subsampling = (b2 shr 1) and 0x07
        add(
            "vp9.config.vpcc",
            ConformanceStatus.PASS,
            "profile=$profile level=$level bitDepth=$bitDepth chroma=$subsampling / 프로필=$profile 레벨=$level",
            vpcC.offset until (vpcC.offset + vpcC.size),
        )

        limitations += "Tile layout, transform decoding, and boolean entropy updates are not checked / 타일과 전체 VP9 구문 미검증"

        var checked = 0
        var malformed = false
        var frameCount = 0
        var markerFailures = 0
        var syncCodeFailures = 0
        var profileReservedFailures = 0

        for (sample in table.samples) {
            currentCoroutineContext().ensureActive()
            val scan = scanVp9Sample(reader, sample) { frame ->
                if (frame.issue != null) {
                    malformed = true
                    add(
                        "vp9.frame.framing",
                        ConformanceStatus.FAIL,
                        "sample=${frame.sampleIndex} frame=${frame.frameIndex} observed=${frame.issue} expected=frame within sample / 범위 안의 프레임",
                        frame.offset..frame.offset,
                    )
                } else {
                    frameCount++
                    val frameRange = frame.offset..frame.offset
                    if (frame.frameMarker != 2) {
                        markerFailures++
                        add(
                            "vp9.frame.marker",
                            ConformanceStatus.FAIL,
                            "sample=${frame.sampleIndex} frame=${frame.frameIndex} observed=${frame.frameMarker} expected=2",
                            frameRange,
                        )
                    }
                    if (frame.frameType == 0 && frame.syncCode != null && frame.syncCode != 0x498342) {
                        syncCodeFailures++
                        add(
                            "vp9.frame.sync_code",
                            ConformanceStatus.FAIL,
                            "sample=${frame.sampleIndex} frame=${frame.frameIndex} observed=0x${frame.syncCode.toString(16).padStart(6, '0')} expected=0x498342",
                            (frame.offset + 1)..(frame.offset + 3),
                        )
                    }
                    if (frame.profile == 3 && frame.profileReservedBit != null && frame.profileReservedBit != 0) {
                        profileReservedFailures++
                        add(
                            "vp9.frame.profile_reserved_bit",
                            ConformanceStatus.FAIL,
                            "sample=${frame.sampleIndex} frame=${frame.frameIndex} observed=${frame.profileReservedBit} expected=0",
                            frameRange,
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
            add("vp9.frame.framing", ConformanceStatus.PASS, "checked=$checked indexed=${table.samples.size}; every frame extent fits / 프레임 범위 일치")
        } else if (totals["vp9.frame.framing"] == null) {
            add("vp9.frame.framing", ConformanceStatus.NOT_CHECKED, "checked=$checked indexed=${table.samples.size}; incomplete sample coverage / 일부 샘플 미검증")
        }
        if (complete && frameCount > 0 && markerFailures == 0) {
            add("vp9.frame.marker", ConformanceStatus.PASS, "checkedFrames=$frameCount observed=2 expected=2")
        }
        if (complete && frameCount > 0 && syncCodeFailures == 0) {
            add("vp9.frame.sync_code", ConformanceStatus.PASS, "checkedKeyframes observed=0x498342 expected=0x498342")
        }
        if (complete && frameCount > 0 && profileReservedFailures == 0) {
            add("vp9.frame.profile_reserved_bit", ConformanceStatus.PASS, "checkedFrames=$frameCount observed=0 expected=0")
        }
        if (!complete) {
            limitations += "Partial VP9 sample coverage / VP9 샘플 일부 미검증"
        }
        report(complete, checked, table.samples.size)
    }
}
