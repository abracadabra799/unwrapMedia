package com.multiviewer.ui

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.conformance.ConformanceReport
import com.multiviewer.parser.conformance.analyzeConformance
import com.multiviewer.parser.integrity.AudioStructureChecker
import com.multiviewer.parser.integrity.AudioStructureReport
import com.multiviewer.parser.integrity.ImageIntegrityChecker
import com.multiviewer.parser.integrity.ImageStructureReport
import java.io.File

enum class ContentTab { STRUCTURE, CONFORMANCE, IMAGE_DECODE, MOTION_PHOTO, GAINMAP, VIDEO_DECODE, VIDEO_PACKETS, AUDIO }

enum class HeavyStep { IMAGE_DECODE, MOTION_PHOTO, VIDEO_INTEGRITY, AUDIO_INTEGRITY }

fun contentTabs(type: MediaType, motionDetected: Boolean, gainmapDetected: Boolean = false): List<ContentTab> = when (type) {
    MediaType.IMAGE -> listOf(ContentTab.STRUCTURE, ContentTab.CONFORMANCE, ContentTab.IMAGE_DECODE) +
        (if (motionDetected) listOf(ContentTab.MOTION_PHOTO) else emptyList()) +
        (if (gainmapDetected) listOf(ContentTab.GAINMAP) else emptyList())
    MediaType.VIDEO -> listOf(ContentTab.STRUCTURE, ContentTab.CONFORMANCE, ContentTab.VIDEO_DECODE, ContentTab.VIDEO_PACKETS, ContentTab.AUDIO)
    MediaType.AUDIO -> listOf(ContentTab.STRUCTURE, ContentTab.CONFORMANCE, ContentTab.AUDIO)
    MediaType.RAW_PIXEL, MediaType.UNKNOWN -> listOf(ContentTab.STRUCTURE, ContentTab.CONFORMANCE)
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

fun contentAudioStructureReport(file: File, root: BoxNode): AudioStructureReport =
    AudioStructureChecker.check(file, root)

/** Uses existing observations only; no file reads and no normative video profile is inferred. */
fun contentConformanceReport(
    type: MediaType,
    structure: ImageStructureReport? = null,
    audioStructure: AudioStructureReport? = null,
    inspectionError: String? = null,
): ConformanceReport {
    val report = when (type) {
        MediaType.IMAGE -> analyzeConformance(structure?.format ?: type.name, structure?.items.orEmpty(), inspectionError)
        MediaType.AUDIO -> analyzeConformance(
            audioStructure?.format ?: type.name,
            audioStructure?.let { it.items + it.parserWarnings }.orEmpty(),
            inspectionError,
        )
        else -> analyzeConformance(type.name, structure?.items.orEmpty().filter { it.id.startsWith("parser.") }, inspectionError)
    }
    return report.copy(limitations = report.limitations +
        "Samsung 내부 Motion Photo·SEF·Gallery 규칙은 미검증입니다 / Samsung internal Motion Photo, SEF and Gallery rules are not checked")
}
