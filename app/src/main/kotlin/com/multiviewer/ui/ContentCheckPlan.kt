package com.multiviewer.ui

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.integrity.ImageIntegrityChecker
import com.multiviewer.parser.integrity.ImageStructureReport
import java.io.File

enum class ContentTab { STRUCTURE, IMAGE_DECODE, MOTION_PHOTO, VIDEO_DECODE, VIDEO_PACKETS, AUDIO }

enum class HeavyStep { IMAGE_DECODE, MOTION_PHOTO, VIDEO_INTEGRITY, AUDIO_INTEGRITY }

fun contentTabs(type: MediaType, motionDetected: Boolean): List<ContentTab> = when (type) {
    MediaType.IMAGE -> listOf(ContentTab.STRUCTURE, ContentTab.IMAGE_DECODE) +
        if (motionDetected) listOf(ContentTab.MOTION_PHOTO) else emptyList()
    MediaType.VIDEO -> listOf(ContentTab.STRUCTURE, ContentTab.VIDEO_DECODE, ContentTab.VIDEO_PACKETS, ContentTab.AUDIO)
    MediaType.AUDIO -> listOf(ContentTab.STRUCTURE, ContentTab.AUDIO)
    MediaType.RAW_PIXEL, MediaType.UNKNOWN -> listOf(ContentTab.STRUCTURE)
}

fun heavySteps(type: MediaType, motionDetected: Boolean): List<HeavyStep> = when (type) {
    MediaType.IMAGE -> listOf(HeavyStep.IMAGE_DECODE) + if (motionDetected) listOf(HeavyStep.MOTION_PHOTO) else emptyList()
    MediaType.VIDEO -> listOf(HeavyStep.VIDEO_INTEGRITY, HeavyStep.AUDIO_INTEGRITY)
    MediaType.AUDIO -> listOf(HeavyStep.AUDIO_INTEGRITY)
    MediaType.RAW_PIXEL, MediaType.UNKNOWN -> emptyList()
}

/** Structure report for any media type: images → ImageIntegrityChecker.check; others → parser warnings only. */
fun contentStructureReport(file: File, root: BoxNode, type: MediaType): ImageStructureReport =
    if (type == MediaType.IMAGE) ImageIntegrityChecker.check(file, root)
    else ImageStructureReport(format = type.name, items = ImageIntegrityChecker.parserWarningItems(root))
