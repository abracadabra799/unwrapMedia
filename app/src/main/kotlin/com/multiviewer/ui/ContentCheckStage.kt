package com.multiviewer.ui

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.conformance.BitstreamCodec
import com.multiviewer.parser.conformance.CodecBitstreamReport
import com.multiviewer.parser.firstVideoSampleEntry
import com.multiviewer.parser.firstAudioSampleEntry
import com.multiviewer.parser.boxDescendants

fun hevcCodecAvailability(type: MediaType, root: BoxNode?): CheckAvailability {
    if (type != MediaType.VIDEO) return CheckAvailability.NOT_APPLICABLE
    if (root == null) return CheckAvailability.LOADING
    val entry = firstVideoSampleEntry(root)?.second ?: return CheckAvailability.UNSUPPORTED
    if (entry.type !in setOf("hvc1", "hev1")) return CheckAvailability.UNSUPPORTED
    if (boxDescendants(root).any { it.type == "moof" || it.type == "mvex" } ||
        boxDescendants(entry).any { it.type == "sinf" || it.type == "encv" }) return CheckAvailability.UNSUPPORTED
    val config = entry.children.firstOrNull { it.type == "hvcC" } ?: return CheckAvailability.UNSUPPORTED
    if (config.warnings.isNotEmpty() || config.size - config.headerSize < 23 ||
        config.fields.firstOrNull { it.name == "configuration_version" }?.value != "1") {
        return CheckAvailability.UNSUPPORTED
    }
    return CheckAvailability.AVAILABLE
}

fun avcCodecAvailability(type: MediaType, root: BoxNode?): CheckAvailability {
    if (type != MediaType.VIDEO) return CheckAvailability.NOT_APPLICABLE
    if (root == null) return CheckAvailability.LOADING
    val entry = firstVideoSampleEntry(root)?.second ?: return CheckAvailability.UNSUPPORTED
    if (entry.type != "avc1") return CheckAvailability.UNSUPPORTED
    if (boxDescendants(root).any { it.type == "moof" || it.type == "mvex" } ||
        boxDescendants(entry).any { it.type == "sinf" || it.type == "encv" }) return CheckAvailability.UNSUPPORTED
    val config = entry.children.firstOrNull { it.type == "avcC" } ?: return CheckAvailability.UNSUPPORTED
    if (config.warnings.isNotEmpty() || config.size - config.headerSize < 7 ||
        config.fields.firstOrNull { it.name == "configuration_version" }?.value != "1") {
        return CheckAvailability.UNSUPPORTED
    }
    return CheckAvailability.AVAILABLE
}

fun av1CodecAvailability(type: MediaType, root: BoxNode?): CheckAvailability {
    if (type != MediaType.VIDEO) return CheckAvailability.NOT_APPLICABLE
    if (root == null) return CheckAvailability.LOADING
    val entry = firstVideoSampleEntry(root)?.second ?: return CheckAvailability.UNSUPPORTED
    if (entry.type != "av01") return CheckAvailability.UNSUPPORTED
    if (boxDescendants(root).any { it.type == "moof" || it.type == "mvex" } ||
        boxDescendants(entry).any { it.type == "sinf" || it.type == "encv" }) return CheckAvailability.UNSUPPORTED
    val config = entry.children.firstOrNull { it.type == "av1C" } ?: return CheckAvailability.UNSUPPORTED
    if (config.warnings.isNotEmpty() || config.size - config.headerSize < 4 ||
        config.fields.firstOrNull { it.name == "version" }?.value != "1" ||
        config.fields.firstOrNull { it.name == "marker" }?.value != "1") {
        return CheckAvailability.UNSUPPORTED
    }
    return CheckAvailability.AVAILABLE
}

fun vp9CodecAvailability(type: MediaType, root: BoxNode?): CheckAvailability {
    if (type != MediaType.VIDEO) return CheckAvailability.NOT_APPLICABLE
    if (root == null) return CheckAvailability.LOADING
    val entry = firstVideoSampleEntry(root)?.second ?: return CheckAvailability.UNSUPPORTED
    if (entry.type != "vp09") return CheckAvailability.UNSUPPORTED
    if (boxDescendants(root).any { it.type == "moof" || it.type == "mvex" } ||
        boxDescendants(entry).any { it.type == "sinf" || it.type == "encv" }) return CheckAvailability.UNSUPPORTED
    val config = entry.children.firstOrNull { it.type == "vpcC" } ?: return CheckAvailability.UNSUPPORTED
    if (config.warnings.isNotEmpty() || config.size - config.headerSize < 12 ||
        (config.fields.firstOrNull { it.name == "version" }?.value != "1" &&
            config.fields.firstOrNull { it.name == "version" }?.value != "0")) {
        return CheckAvailability.UNSUPPORTED
    }
    return CheckAvailability.AVAILABLE
}

fun apvCodecAvailability(type: MediaType, root: BoxNode?): CheckAvailability {
    if (type != MediaType.VIDEO) return CheckAvailability.NOT_APPLICABLE
    if (root == null) return CheckAvailability.LOADING
    val entry = firstVideoSampleEntry(root)?.second ?: return CheckAvailability.UNSUPPORTED
    if (entry.type != "apv1") return CheckAvailability.UNSUPPORTED
    if (boxDescendants(root).any { it.type == "moof" || it.type == "mvex" } ||
        boxDescendants(entry).any { it.type == "sinf" || it.type == "encv" }) return CheckAvailability.UNSUPPORTED
    val config = entry.children.firstOrNull { it.type == "apvC" } ?: return CheckAvailability.UNSUPPORTED
    if (config.warnings.isNotEmpty() || config.size - config.headerSize < 21) {
        return CheckAvailability.UNSUPPORTED
    }
    return CheckAvailability.AVAILABLE
}

fun vvcCodecAvailability(type: MediaType, root: BoxNode?): CheckAvailability {
    if (type != MediaType.VIDEO) return CheckAvailability.NOT_APPLICABLE
    if (root == null) return CheckAvailability.LOADING
    val entry = firstVideoSampleEntry(root)?.second ?: return CheckAvailability.UNSUPPORTED
    if (entry.type !in setOf("vvc1", "vvi1")) return CheckAvailability.UNSUPPORTED
    if (boxDescendants(root).any { it.type == "moof" || it.type == "mvex" } ||
        boxDescendants(entry).any { it.type == "sinf" || it.type == "encv" }) return CheckAvailability.UNSUPPORTED
    val config = entry.children.firstOrNull { it.type == "vvcC" } ?: return CheckAvailability.UNSUPPORTED
    if (config.warnings.isNotEmpty() || config.size - config.headerSize < 5 ||
        config.fields.firstOrNull { it.name == "version" }?.value != "0") {
        return CheckAvailability.UNSUPPORTED
    }
    return CheckAvailability.AVAILABLE
}

fun aacCodecAvailability(type: MediaType, root: BoxNode?): CheckAvailability {
    if (type != MediaType.AUDIO && type != MediaType.VIDEO) return CheckAvailability.NOT_APPLICABLE
    if (root == null) return CheckAvailability.LOADING
    val entry = firstAudioSampleEntry(root)?.second ?: return CheckAvailability.UNSUPPORTED
    if (entry.type != "mp4a") return CheckAvailability.UNSUPPORTED
    if (boxDescendants(root).any { it.type == "moof" || it.type == "mvex" } ||
        boxDescendants(entry).any { it.type == "sinf" || it.type == "enca" }) return CheckAvailability.UNSUPPORTED
    // ISO MP4 puts esds directly in the entry; a QuickTime V1/V2 sound description puts it inside 'wave'.
    val config = (entry.children.firstOrNull { it.type == "esds" }
        ?: entry.children.firstOrNull { it.type == "wave" }?.children?.firstOrNull { it.type == "esds" })
        ?: return CheckAvailability.UNSUPPORTED
    if (config.warnings.isNotEmpty() || config.size - config.headerSize < 4) {
        return CheckAvailability.UNSUPPORTED
    }
    return CheckAvailability.AVAILABLE
}

fun opusCodecAvailability(type: MediaType, root: BoxNode?): CheckAvailability {
    if (type != MediaType.AUDIO && type != MediaType.VIDEO) return CheckAvailability.NOT_APPLICABLE
    if (root == null) return CheckAvailability.LOADING
    val entry = firstAudioSampleEntry(root)?.second ?: return CheckAvailability.UNSUPPORTED
    if (entry.type != "Opus") return CheckAvailability.UNSUPPORTED
    if (boxDescendants(root).any { it.type == "moof" || it.type == "mvex" } ||
        boxDescendants(entry).any { it.type == "sinf" || it.type == "enca" }) return CheckAvailability.UNSUPPORTED
    val config = entry.children.firstOrNull { it.type == "dOps" } ?: return CheckAvailability.UNSUPPORTED
    if (config.warnings.isNotEmpty() || config.size - config.headerSize < 11) {
        return CheckAvailability.UNSUPPORTED
    }
    return CheckAvailability.AVAILABLE
}

fun flacCodecAvailability(type: MediaType, root: BoxNode?): CheckAvailability {
    if (type != MediaType.AUDIO && type != MediaType.VIDEO) return CheckAvailability.NOT_APPLICABLE
    if (root == null) return CheckAvailability.LOADING
    val entry = firstAudioSampleEntry(root)?.second ?: return CheckAvailability.UNSUPPORTED
    if (entry.type != "fLaC") return CheckAvailability.UNSUPPORTED
    if (boxDescendants(root).any { it.type == "moof" || it.type == "mvex" } ||
        boxDescendants(entry).any { it.type == "sinf" || it.type == "enca" }) return CheckAvailability.UNSUPPORTED
    val config = entry.children.firstOrNull { it.type == "dfLa" } ?: return CheckAvailability.UNSUPPORTED
    if (config.warnings.isNotEmpty() || config.size - config.headerSize < 4) {
        return CheckAvailability.UNSUPPORTED
    }
    return CheckAvailability.AVAILABLE
}

private fun codecAvailability(codec: BitstreamCodec, type: MediaType, root: BoxNode?): CheckAvailability = when (codec) {
    BitstreamCodec.HEVC -> hevcCodecAvailability(type, root)
    BitstreamCodec.AVC -> avcCodecAvailability(type, root)
    BitstreamCodec.AV1 -> av1CodecAvailability(type, root)
    BitstreamCodec.VP9 -> vp9CodecAvailability(type, root)
    BitstreamCodec.APV -> apvCodecAvailability(type, root)
    BitstreamCodec.VVC -> vvcCodecAvailability(type, root)
    BitstreamCodec.AAC -> aacCodecAvailability(type, root)
    BitstreamCodec.OPUS -> opusCodecAvailability(type, root)
    BitstreamCodec.FLAC -> flacCodecAvailability(type, root)
}

/**
 * The byte checks the CODEC stage runs, in order: the first supported video codec, then the first supported
 * audio codec. A phone/camera MP4 carries both tracks and both get checked.
 */
fun codecBitstreamPlan(type: MediaType, root: BoxNode?): List<BitstreamCodec> {
    val (audio, video) = BitstreamCodec.entries.partition { it.isAudio }
    return listOfNotNull(
        video.firstOrNull { codecAvailability(it, type, root) == CheckAvailability.AVAILABLE },
        audio.firstOrNull { codecAvailability(it, type, root) == CheckAvailability.AVAILABLE },
    )
}

/**
 * What a CODEC run did not cover, one bilingual reason per gap; empty means every track was byte-checked in full.
 * A video whose video or audio track has no checker, a planned check that never produced a report (cancelled or
 * failed midway) and partial sample coverage are all gaps, so an incomplete run never reads as complete (rule 7).
 */
// Any `soun` track, whatever its codec (firstAudioSampleEntry only knows the codecs the app can play).
private fun hasSoundTrack(root: BoxNode): Boolean = boxDescendants(root).any { trak ->
    trak.type == "trak" && boxDescendants(trak).any { node ->
        node.type == "hdlr" && node.fields.any { it.name == "handler_type" && it.value == "soun" }
    }
}

fun codecCoverageGaps(type: MediaType, root: BoxNode?, plan: List<BitstreamCodec>, reports: List<CodecBitstreamReport>): List<String> = buildList {
    if (type == MediaType.VIDEO && plan.none { !it.isAudio }) {
        add("Video codec has no byte checker; video track not checked / 영상 코덱 미지원 · 영상 트랙 미검사")
    }
    if (type == MediaType.VIDEO && plan.none { it.isAudio } && root != null && hasSoundTrack(root)) {
        add("Audio codec has no byte checker; audio track not checked / 오디오 코덱 미지원 · 오디오 트랙 미검사")
    }
    val ran = reports.map { it.codec }.toSet()
    plan.filter { it !in ran }.forEach { add("${it.label} check did not run / ${it.label} 검사 미실행") }
    if (reports.any { !it.complete }) add("Only indexed, supported samples were checked / 일부 샘플만 검사")
}

fun videoCodecAvailability(type: MediaType, root: BoxNode?): CheckAvailability {
    val hevc = hevcCodecAvailability(type, root)
    if (hevc == CheckAvailability.AVAILABLE) return CheckAvailability.AVAILABLE
    val avc = avcCodecAvailability(type, root)
    if (avc == CheckAvailability.AVAILABLE) return CheckAvailability.AVAILABLE
    val av1 = av1CodecAvailability(type, root)
    if (av1 == CheckAvailability.AVAILABLE) return CheckAvailability.AVAILABLE
    val vp9 = vp9CodecAvailability(type, root)
    if (vp9 == CheckAvailability.AVAILABLE) return CheckAvailability.AVAILABLE
    val apv = apvCodecAvailability(type, root)
    if (apv == CheckAvailability.AVAILABLE) return CheckAvailability.AVAILABLE
    val vvc = vvcCodecAvailability(type, root)
    if (vvc == CheckAvailability.AVAILABLE) return CheckAvailability.AVAILABLE
    if (hevc == CheckAvailability.LOADING || avc == CheckAvailability.LOADING || av1 == CheckAvailability.LOADING || vp9 == CheckAvailability.LOADING || apv == CheckAvailability.LOADING || vvc == CheckAvailability.LOADING) return CheckAvailability.LOADING
    if (hevc == CheckAvailability.NOT_APPLICABLE && avc == CheckAvailability.NOT_APPLICABLE && av1 == CheckAvailability.NOT_APPLICABLE && vp9 == CheckAvailability.NOT_APPLICABLE && apv == CheckAvailability.NOT_APPLICABLE && vvc == CheckAvailability.NOT_APPLICABLE) return CheckAvailability.NOT_APPLICABLE
    return CheckAvailability.UNSUPPORTED
}

enum class CheckStage { CONTAINER_CONFORMANCE, STRUCTURE, CODEC, VISUAL_DECODE, AUDIO_DECODE, MOTION_PHOTO, GAINMAP }

enum class CheckAvailability { AVAILABLE, UNSUPPORTED, NOT_APPLICABLE, LOADING }

data class CheckStageSpec(
    val stage: CheckStage,
    val availability: CheckAvailability,
    val reasonKo: String? = null,
    val reasonEn: String? = null,
)

fun checkStageDescription(stage: CheckStage, ko: Boolean): String = when (stage) {
    CheckStage.CONTAINER_CONFORMANCE -> if (ko) "컨테이너 포맷 규격(ISO-BMFF, EXIF 등) 필수 박스 및 구조 제약 조건을 검증합니다." else "Validates container format specification requirements (ISO-BMFF, EXIF, etc.)."
    CheckStage.STRUCTURE -> if (ko) "박스·청크 배치, 오프셋 무결성 및 파서 경고를 검사합니다." else "Checks box and chunk layout, offset integrity, and parser warnings."
    CheckStage.CODEC -> if (ko) "코덱 구문, 값 범위, NAL 헤더 및 파라미터 간 참조를 검사합니다." else "Checks codec syntax, value ranges, NAL headers, and parameter references."
    CheckStage.VISUAL_DECODE -> if (ko) "압축 데이터를 실제로 디코딩하고 오류를 확인합니다." else "Decodes compressed data and checks for errors."
    CheckStage.AUDIO_DECODE -> if (ko) "오디오 트랙을 디코딩하고 오류를 확인합니다." else "Decodes audio tracks and checks for errors."
    CheckStage.MOTION_PHOTO -> if (ko) "정지 이미지와 포함된 영상의 연결·시점을 확인합니다." else "Checks the link and timing between the still image and embedded video."
    CheckStage.GAINMAP -> if (ko) "게인맵 데이터와 메타데이터의 구조·값을 확인합니다." else "Checks gain-map data and metadata structure and values."
}

fun contentCheckStages(
    type: MediaType,
    root: BoxNode?,
    motionDetected: Boolean,
    gainmapDetected: Boolean,
    hasAudioTrack: Boolean,
    codecRunnerAvailable: Boolean,
    aacCodecAvailable: Boolean = false,
    opusCodecAvailable: Boolean = false,
    flacCodecAvailable: Boolean = false,
): List<CheckStageSpec> {
    val loading = root == null
    fun available(stage: CheckStage) = CheckStageSpec(
        stage, if (loading) CheckAvailability.LOADING else CheckAvailability.AVAILABLE,
        if (loading) "파일 구조 불러오는 중" else null,
        if (loading) "Loading file structure" else null,
    )
    fun notApplicable(stage: CheckStage) = CheckStageSpec(stage, CheckAvailability.NOT_APPLICABLE,
        "해당 파일에 적용되지 않음", "Not applicable to this file")
    val result = mutableListOf(available(CheckStage.CONTAINER_CONFORMANCE), available(CheckStage.STRUCTURE))
    result += when {
        type == MediaType.AUDIO -> when {
            loading -> available(CheckStage.CODEC)
            aacCodecAvailable || opusCodecAvailable || flacCodecAvailable -> available(CheckStage.CODEC)
            else -> CheckStageSpec(CheckStage.CODEC, CheckAvailability.UNSUPPORTED,
                "지원되는 AAC mp4a, Opus 또는 FLAC 구성 없음", "No supported AAC mp4a, Opus, or FLAC configuration")
        }
        type != MediaType.VIDEO -> notApplicable(CheckStage.CODEC)
        loading -> available(CheckStage.CODEC)
        !codecRunnerAvailable -> CheckStageSpec(CheckStage.CODEC, CheckAvailability.UNSUPPORTED,
            "지원되는 H.264 avc1, HEVC hvc1/hev1, AV1 av01, VP9 vp09, APV apv1 또는 VVC vvc1/vvi1 구성 없음", "No supported H.264 avc1, HEVC hvc1/hev1, AV1 av01, VP9 vp09, APV apv1, or VVC vvc1/vvi1 configuration")
        else -> available(CheckStage.CODEC)
    }
    result += if (type == MediaType.IMAGE || type == MediaType.VIDEO) available(CheckStage.VISUAL_DECODE)
        else notApplicable(CheckStage.VISUAL_DECODE)
    result += if (type == MediaType.AUDIO || hasAudioTrack) available(CheckStage.AUDIO_DECODE)
        else notApplicable(CheckStage.AUDIO_DECODE)
    if (type == MediaType.IMAGE && motionDetected) result += available(CheckStage.MOTION_PHOTO)
    if (type == MediaType.IMAGE && gainmapDetected) result += available(CheckStage.GAINMAP)
    return result
}
