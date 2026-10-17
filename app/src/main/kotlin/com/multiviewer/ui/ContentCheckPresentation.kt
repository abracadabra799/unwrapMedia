package com.multiviewer.ui

import com.multiviewer.parser.integrity.CheckStatus

enum class ContentCheckStageGroup { ON_OPEN, ON_REQUEST }

fun contentCheckStageGroups(tabs: List<ContentTab>): Map<ContentCheckStageGroup, List<ContentTab>> = buildMap {
    val onOpen = tabs.filter { it == ContentTab.STRUCTURE || it == ContentTab.CONFORMANCE || it == ContentTab.GAINMAP }
    val onRequest = tabs.filterNot { it in onOpen }
    if (onOpen.isNotEmpty()) put(ContentCheckStageGroup.ON_OPEN, onOpen)
    if (onRequest.isNotEmpty()) put(ContentCheckStageGroup.ON_REQUEST, onRequest)
}

fun contentCheckStructureStatusLabel(status: CheckStatus?, error: String?, ko: Boolean): String = when {
    error != null -> if (ko) "실패" else "Failed"
    status == null -> if (ko) "검사 중" else "Checking"
    else -> checkStatusLabel(status, ko)
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
