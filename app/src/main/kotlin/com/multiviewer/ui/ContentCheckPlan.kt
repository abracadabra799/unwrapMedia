package com.multiviewer.ui

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.ContainerFormat
import com.multiviewer.parser.conformance.isobmff.IsoBmffConformanceChecker
import java.util.concurrent.CancellationException
import com.multiviewer.parser.conformance.ConformanceReport
import com.multiviewer.parser.conformance.analyzeConformance
import com.multiviewer.parser.integrity.AudioStructureChecker
import com.multiviewer.parser.integrity.AudioStructureReport
import com.multiviewer.parser.integrity.ImageIntegrityChecker
import com.multiviewer.parser.integrity.ImageStructureReport
import com.multiviewer.parser.integrity.IntegrityCheckItem
import com.multiviewer.parser.integrity.CheckStatus
import com.multiviewer.parser.confidentFormat
import com.multiviewer.parser.formatMismatch
import java.io.File

enum class ContentTab { STRUCTURE, CONFORMANCE, CODEC, IMAGE_DECODE, MOTION_PHOTO, GAINMAP, VIDEO_DECODE, VIDEO_PACKETS, AUDIO }

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
fun contentStructureReport(file: File, root: BoxNode, type: MediaType): ImageStructureReport {
    val report = if (type == MediaType.IMAGE) ImageIntegrityChecker.check(file, root)
    else ImageStructureReport(format = type.name, items = ImageIntegrityChecker.parserWarningItems(root))
    val mismatch = extensionMismatchItem(file) ?: return report
    return report.copy(items = listOf(mismatch) + report.items)
}

fun contentAudioStructureReport(file: File, root: BoxNode): AudioStructureReport {
    val report = AudioStructureChecker.check(file, root)
    val mismatch = extensionMismatchItem(file) ?: return report
    return report.copy(items = listOf(mismatch) + report.items)
}

/**
 * WARN when the file's extension isn't one its detected content format is saved under (a HEIC named .jpg):
 * tools that trust the name will mis-handle it. Null when it matches or the content isn't confidently
 * detected. Reads the file's first bytes: background only.
 */
fun extensionMismatchItem(file: File): IntegrityCheckItem? {
    val format = confidentFormat(file) ?: return null
    val mismatch = formatMismatch(format, file.extension) ?: return null
    val shown = if (mismatch.extension.isEmpty()) "no extension" else ".${mismatch.extension}"
    return IntegrityCheckItem(
        id = "file.extension_mismatch",
        title = "File extension",
        status = CheckStatus.WARN,
        detail = "Extension $shown does not match the content (${format.displayName}; expected " +
            format.extensions.joinToString("/") { ".$it" } + "). Tools that trust the extension may mis-handle this file.",
    )
}

/**
 * Uses existing observations only; no file reads. [isoBmff] (computed off the UI thread by [isoBmffConformanceFor])
 * replaces the parser-warning-only report for MP4/MOV/M4A containers.
 */
fun contentConformanceReport(
    type: MediaType,
    structure: ImageStructureReport? = null,
    audioStructure: AudioStructureReport? = null,
    inspectionError: String? = null,
    isoBmff: ConformanceReport? = null,
): ConformanceReport {
    val report = if (isoBmff != null) isoBmff else when (type) {
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

private val ISO_BMFF_CONTAINERS = setOf(ContainerFormat.MP4, ContainerFormat.MOV, ContainerFormat.M4A)

/**
 * ISO-BMFF / QuickTime container conformance for files whose content is MP4, MOV or M4A; null otherwise.
 * Blocking file I/O: never call on the UI thread.
 */
fun isoBmffConformanceFor(file: File): ConformanceReport? {
    if (confidentFormat(file) !in ISO_BMFF_CONTAINERS) return null
    return try {
        ByteReader.open(file).use { IsoBmffConformanceChecker.check(it) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val message = "검사 중 오류: ${e.javaClass.simpleName}: ${e.message ?: ""}".trim()
        ConformanceReport("ISO-BMFF", "ISO/IEC 14496-12 phase 1", emptyList(), inspectionError = message,
            limitations = listOf(
                "코덱 비트스트림 구문 전체는 검사하지 않습니다 / Codec bitstream syntax is not fully checked",
                "검사 오류 / Inspection error: $message",
            ))
    }
}
