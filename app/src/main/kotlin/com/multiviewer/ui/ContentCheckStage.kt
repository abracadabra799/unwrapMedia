package com.multiviewer.ui

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.firstVideoSampleEntry
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

fun videoCodecAvailability(type: MediaType, root: BoxNode?): CheckAvailability {
    val hevc = hevcCodecAvailability(type, root)
    if (hevc == CheckAvailability.AVAILABLE) return CheckAvailability.AVAILABLE
    val avc = avcCodecAvailability(type, root)
    if (avc == CheckAvailability.AVAILABLE) return CheckAvailability.AVAILABLE
    val av1 = av1CodecAvailability(type, root)
    if (av1 == CheckAvailability.AVAILABLE) return CheckAvailability.AVAILABLE
    val vp9 = vp9CodecAvailability(type, root)
    if (vp9 == CheckAvailability.AVAILABLE) return CheckAvailability.AVAILABLE
    if (hevc == CheckAvailability.LOADING || avc == CheckAvailability.LOADING || av1 == CheckAvailability.LOADING || vp9 == CheckAvailability.LOADING) return CheckAvailability.LOADING
    if (hevc == CheckAvailability.NOT_APPLICABLE && avc == CheckAvailability.NOT_APPLICABLE && av1 == CheckAvailability.NOT_APPLICABLE && vp9 == CheckAvailability.NOT_APPLICABLE) return CheckAvailability.NOT_APPLICABLE
    return CheckAvailability.UNSUPPORTED
}

enum class CheckStage { STRUCTURE, CODEC, VISUAL_DECODE, AUDIO_DECODE, MOTION_PHOTO, GAINMAP }

enum class CheckAvailability { AVAILABLE, UNSUPPORTED, NOT_APPLICABLE, LOADING }

data class CheckStageSpec(
    val stage: CheckStage,
    val availability: CheckAvailability,
    val reasonKo: String? = null,
    val reasonEn: String? = null,
)

fun checkStageDescription(stage: CheckStage, ko: Boolean): String = when (stage) {
    CheckStage.STRUCTURE -> if (ko) "박스·청크 배치와 필수 데이터의 위치·범위를 검사합니다." else "Checks box and chunk layout and the location and extent of required data."
    CheckStage.CODEC -> if (ko) "코덱 구문, 값 범위, 파라미터 간 참조를 검사합니다." else "Checks codec syntax, value ranges, and parameter references."
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
): List<CheckStageSpec> {
    val loading = root == null
    fun available(stage: CheckStage) = CheckStageSpec(
        stage, if (loading) CheckAvailability.LOADING else CheckAvailability.AVAILABLE,
        if (loading) "파일 구조 불러오는 중" else null,
        if (loading) "Loading file structure" else null,
    )
    fun notApplicable(stage: CheckStage) = CheckStageSpec(stage, CheckAvailability.NOT_APPLICABLE,
        "해당 파일에 적용되지 않음", "Not applicable to this file")
    val result = mutableListOf(available(CheckStage.STRUCTURE))
    result += when {
        type != MediaType.VIDEO -> notApplicable(CheckStage.CODEC)
        loading -> available(CheckStage.CODEC)
        !codecRunnerAvailable -> CheckStageSpec(CheckStage.CODEC, CheckAvailability.UNSUPPORTED,
            "지원되는 H.264 avc1, HEVC hvc1/hev1, AV1 av01 또는 VP9 vp09 구성 없음", "No supported H.264 avc1, HEVC hvc1/hev1, AV1 av01, or VP9 vp09 configuration")
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
