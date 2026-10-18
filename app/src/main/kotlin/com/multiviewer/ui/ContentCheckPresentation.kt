package com.multiviewer.ui

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.MediaSummary
import com.multiviewer.parser.firstVideoSampleEntry
import com.multiviewer.parser.firstAudioSampleEntry
import com.multiviewer.parser.integrity.CheckStatus
import com.multiviewer.parser.conformance.ConformanceStatus

fun checkStageLabel(stage: CheckStage, ko: Boolean): String = when (stage) {
    CheckStage.CONTAINER_CONFORMANCE -> if (ko) "컨테이너 규격" else "Container conformance"
    CheckStage.STRUCTURE -> if (ko) "파일 구조 및 무결성" else "File structure & integrity"
    CheckStage.CODEC -> if (ko) "코덱 비트스트림" else "Codec bitstream"
    CheckStage.VISUAL_DECODE -> if (ko) "영상·이미지 디코딩" else "Video or image decode"
    CheckStage.AUDIO_DECODE -> if (ko) "오디오 디코딩" else "Audio decode"
    CheckStage.MOTION_PHOTO -> if (ko) "모션포토" else "Motion photo"
    CheckStage.GAINMAP -> if (ko) "게인맵" else "Gain map"
}

enum class CheckCategory { FORMAT_AND_BITSTREAM, DECODE_VERIFICATION, ADDITIONAL_CHECKS }

fun checkCategory(stage: CheckStage): CheckCategory = when (stage) {
    CheckStage.CONTAINER_CONFORMANCE,
    CheckStage.STRUCTURE,
    CheckStage.CODEC -> CheckCategory.FORMAT_AND_BITSTREAM
    CheckStage.VISUAL_DECODE,
    CheckStage.AUDIO_DECODE -> CheckCategory.DECODE_VERIFICATION
    CheckStage.MOTION_PHOTO,
    CheckStage.GAINMAP -> CheckCategory.ADDITIONAL_CHECKS
}

fun checkCategoryLabel(category: CheckCategory, ko: Boolean): String = when (category) {
    CheckCategory.FORMAT_AND_BITSTREAM -> if (ko) "포맷 및 비트스트림 검증" else "Format & Bitstream Validation"
    CheckCategory.DECODE_VERIFICATION -> if (ko) "디코딩 검증" else "Decode Verification"
    CheckCategory.ADDITIONAL_CHECKS -> if (ko) "확장 검사" else "Additional Checks"
}

fun checkRunStatusLabel(status: CheckRunStatus, ko: Boolean): String = when (status) {
    CheckRunStatus.NOT_RUN -> if (ko) "미검사" else "Not run"
    CheckRunStatus.QUEUED -> if (ko) "대기" else "Queued"
    CheckRunStatus.RUNNING -> if (ko) "검사 중" else "Running"
    CheckRunStatus.COMPLETED -> if (ko) "완료" else "Completed"
    CheckRunStatus.PARTIAL -> if (ko) "부분 검사" else "Partial"
    CheckRunStatus.FAILED -> if (ko) "실패" else "Failed"
    CheckRunStatus.CANCELLED -> if (ko) "취소" else "Cancelled"
}

fun checkProgressLabel(progress: CheckProgress): String =
    "${progress.completed}" + (progress.total?.let { "/$it" } ?: "") + " ${progress.unit}"

fun checkStageActionLabel(status: CheckRunStatus, ko: Boolean): String =
    if (status == CheckRunStatus.NOT_RUN || status == CheckRunStatus.QUEUED) {
        if (ko) "실행" else "Run"
    } else if (ko) "다시 실행" else "Rerun"

fun nextSelectedStage(selected: CheckStage, manuallySelected: Boolean, started: CheckStage): CheckStage =
    if (manuallySelected) selected else started

fun checkStageDetailTab(stage: CheckStage, type: MediaType): ContentTab = when (stage) {
    CheckStage.CONTAINER_CONFORMANCE -> ContentTab.CONFORMANCE
    CheckStage.STRUCTURE -> ContentTab.STRUCTURE
    CheckStage.CODEC -> ContentTab.CODEC
    CheckStage.VISUAL_DECODE -> if (type == MediaType.IMAGE) ContentTab.IMAGE_DECODE else ContentTab.VIDEO_DECODE
    CheckStage.AUDIO_DECODE -> ContentTab.AUDIO
    CheckStage.MOTION_PHOTO -> ContentTab.MOTION_PHOTO
    CheckStage.GAINMAP -> ContentTab.GAINMAP
}

fun checkStageTargetLabel(
    stage: CheckStage,
    type: MediaType,
    root: BoxNode? = null,
    summary: MediaSummary? = null,
): String = when (stage) {
    CheckStage.CONTAINER_CONFORMANCE,
    CheckStage.STRUCTURE -> {
        val ftypBrand = root?.children?.firstOrNull { it.type == "ftyp" }
            ?.fields?.firstOrNull { it.name == "major_brand" }?.value
        when {
            ftypBrand != null -> "ISO-BMFF ($ftypBrand)"
            summary != null -> summary.sections.flatMap { it.fields }
                .firstOrNull { it.label == "Format" || it.label == "Container" }?.value
                ?: type.name
            type == MediaType.VIDEO -> "Video (${type.name})"
            type == MediaType.IMAGE -> "Image (${type.name})"
            type == MediaType.AUDIO -> "Audio (${type.name})"
            else -> type.name
        }
    }
    CheckStage.CODEC -> {
        val videoEntry = root?.let { firstVideoSampleEntry(it)?.second }
        val audioEntry = root?.let { firstAudioSampleEntry(it)?.second }
        when {
            videoEntry != null -> when (videoEntry.type) {
                "hvc1" -> "HEVC (hvc1)"
                "hev1" -> "HEVC (hev1)"
                "avc1" -> "H.264 (avc1)"
                "av01" -> "AV1 (av01)"
                "vp09" -> "VP9 (vp09)"
                "apv1" -> "APV (apv1)"
                "vvc1" -> "VVC (vvc1)"
                "vvi1" -> "VVC (vvi1)"
                else -> videoEntry.type
            }
            audioEntry != null -> when (audioEntry.type) {
                "mp4a" -> "AAC (mp4a)"
                "Opus" -> "Opus"
                "fLaC" -> "FLAC"
                else -> audioEntry.type
            }
            type == MediaType.AUDIO -> type.name
            else -> "-"
        }
    }
    CheckStage.VISUAL_DECODE -> {
        val videoEntry = root?.let { firstVideoSampleEntry(it)?.second }
        val width = videoEntry?.fields?.firstOrNull { it.name == "width" }?.value
        val height = videoEntry?.fields?.firstOrNull { it.name == "height" }?.value
        if (width != null && height != null) {
            "${width}×${height}"
        } else {
            summary?.sections?.flatMap { it.fields }
                ?.firstOrNull { it.label == "Resolution" || it.label == "Dimensions" }?.value
                ?: if (type == MediaType.VIDEO) "Video Stream" else if (type == MediaType.IMAGE) "Image Frame" else "-"
        }
    }
    CheckStage.AUDIO_DECODE -> {
        val audioEntry = root?.let { firstAudioSampleEntry(it)?.second }
        val sampleRate = audioEntry?.fields?.firstOrNull { it.name == "samplerate" || it.name == "sample_rate" }?.value
        val channels = audioEntry?.fields?.firstOrNull { it.name == "channelcount" || it.name == "channels" }?.value
        when {
            sampleRate != null && channels != null -> "$channels ch · ${sampleRate}Hz"
            sampleRate != null -> "${sampleRate}Hz"
            audioEntry != null -> audioEntry.type
            type == MediaType.AUDIO -> "Audio Track"
            else -> "Audio Track"
        }
    }
    CheckStage.MOTION_PHOTO -> "XMP / GCamera"
    CheckStage.GAINMAP -> "ISO 21496-1 / Ultra HDR"
}

enum class ContentCheckStageGroup { ON_OPEN, ON_REQUEST }

fun contentCheckStageGroups(tabs: List<ContentTab>): Map<ContentCheckStageGroup, List<ContentTab>> = buildMap {
    val onOpen = tabs.filter { it == ContentTab.STRUCTURE || it == ContentTab.CONFORMANCE || it == ContentTab.GAINMAP }
    val onRequest = tabs.filterNot { it in onOpen }
    if (onOpen.isNotEmpty()) put(ContentCheckStageGroup.ON_OPEN, onOpen)
    if (onRequest.isNotEmpty()) put(ContentCheckStageGroup.ON_REQUEST, onRequest)
}

fun contentCheckStructureStatusLabel(status: CheckStatus?, error: String?, ko: Boolean, treePending: Boolean = false): String = when {
    treePending -> if (ko) "검사 중" else "Checking"
    error != null -> if (ko) "실패" else "Failed"
    status == null -> if (ko) "검사 중" else "Checking"
    else -> checkStatusLabel(status, ko)
}

fun contentCheckConformanceStatusLabel(status: ConformanceStatus, ko: Boolean, treePending: Boolean): String =
    if (treePending) { if (ko) "검사 중" else "Checking" } else conformanceStatusLabel(status, ko)

fun contentCheckStartHint(tab: ContentTab, ko: Boolean): String = when (tab) {
    ContentTab.MOTION_PHOTO -> if (ko)
        "상단의 실행 버튼으로 모션포토 영상 검사를 시작하세요. 영상 전체를 디코딩하므로 시간이 걸릴 수 있습니다."
    else "Use the run button above to check the motion photo video. Decoding the whole video may take a while."
    ContentTab.VIDEO_DECODE, ContentTab.VIDEO_PACKETS -> if (ko)
        "상단의 실행 버튼으로 영상 디코딩·패킷 검사를 시작하세요."
    else "Use the run button above for video decode and packet checks."
    else -> error("No start hint for $tab")
}

fun contentCheckRunLabel(type: MediaType, motionDetected: Boolean, ko: Boolean): String? = when (type) {
    MediaType.IMAGE -> if (motionDetected) {
        if (ko) "이미지 디코딩·모션포토 검사 실행" else "Run image decode and motion-photo checks"
    } else {
        if (ko) "이미지 디코딩 검사 실행" else "Run image decode check"
    }
    MediaType.VIDEO -> if (ko) "영상·패킷·오디오 검사 실행" else "Run video, packet, and audio checks"
    MediaType.AUDIO -> if (ko) "오디오 디코딩 검사 실행" else "Run audio decode check"
    MediaType.RAW_PIXEL, MediaType.UNKNOWN -> null
}

data class ContentCheckActionState(
    val canRun: Boolean,
    val canExport: Boolean,
    val canDiagnose: Boolean,
    val canCancel: Boolean,
)

fun contentCheckActionState(
    type: MediaType,
    hasSteps: Boolean,
    structureReady: Boolean,
    rootLoaded: Boolean,
    fileChanged: Boolean,
    running: Boolean,
    saving: Boolean,
): ContentCheckActionState = ContentCheckActionState(
    canRun = !fileChanged && hasSteps && !running && !saving && (type != MediaType.IMAGE || structureReady),
    canExport = !fileChanged && rootLoaded && !running && !saving && (type != MediaType.IMAGE || structureReady),
    canDiagnose = rootLoaded,
    canCancel = running,
)
