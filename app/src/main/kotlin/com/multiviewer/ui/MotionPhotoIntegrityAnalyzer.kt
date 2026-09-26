package com.multiviewer.ui

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.SefCheckResult
import com.multiviewer.parser.SefIntegrityReport
import com.multiviewer.parser.SefIntegritySeverity
import com.multiviewer.parser.correctMp4StartOffset
import com.multiviewer.parser.findFirst
import com.multiviewer.parser.findMicroVideoOffset
import com.multiviewer.parser.findMotionPhotoInDirectory
import com.multiviewer.parser.parseXmpDocument

enum class MotionPhotoFormat { SAMSUNG_SEF, GOOGLE_XMP, APPLE_MPVD }

data class MotionPhotoIntegrityReport(
    val detectedFormats: List<MotionPhotoFormat>,
    val sefSection: SefIntegrityReport?,
    val googleXmpChecks: List<SefCheckResult>,
    val appleMpvdChecks: List<SefCheckResult>,
    val decodeChecks: List<SefCheckResult>,
    val overallSeverity: SefIntegritySeverity,
)

// Verifies a Google-format (Container:Directory "MotionPhoto" semantic, current schema, or the
// legacy GCamera:MicroVideoOffset attribute) motion photo's declared video length/offset against
// the file's real bytes. Unlike MotionPhotoExtractor.kt's findGoogleMotionPhotoVideo (which
// silently self-heals a wrong offset via correctMp4StartOffset so extraction still works), this
// reports the correction as a finding instead of hiding it -- the whole point of an integrity
// check is surfacing exactly this kind of silently-tolerated inaccuracy.
internal fun analyzeGoogleXmpSection(root: BoxNode, reader: ByteReader): List<SefCheckResult> {
    val xmpText = findFirst(root) { it.fields.any { field -> field.name == "xmp" } }
        ?.fields?.find { it.name == "xmp" }?.value
        ?: return emptyList()
    if (!xmpText.contains("MotionPhoto", ignoreCase = true) && !xmpText.contains("MicroVideo", ignoreCase = true)) {
        return emptyList()
    }

    val checks = mutableListOf<SefCheckResult>()
    val document = try {
        parseXmpDocument(xmpText)
    } catch (e: Throwable) {
        // Untrusted input: a crafted/deeply-nested XMP document can throw StackOverflowError or
        // OutOfMemoryError (both Error, not Exception) -- matches findGoogleMotionPhotoVideo's own
        // Throwable catch for this exact risk.
        return listOf(SefCheckResult(SefIntegritySeverity.WARNING, "구글 모션포토 XMP 파싱", "XMP를 파싱할 수 없습니다: ${e.message ?: e.toString()}"))
    }

    val fromDirectory = findMotionPhotoInDirectory(document)
    val microVideoOffset = findMicroVideoOffset(document)
    if (fromDirectory == null && microVideoOffset == null) {
        checks.add(SefCheckResult(SefIntegritySeverity.WARNING, "구글 모션포토 스키마 감지", "MotionPhoto/MicroVideo 마커는 있지만 Length/Offset 값을 찾을 수 없습니다"))
        return checks
    }

    val declaredLength = fromDirectory?.length ?: microVideoOffset!!
    val schemaLabel = if (fromDirectory != null) {
        "Container:Directory (현재 스키마, Item:Semantic=\"MotionPhoto\")"
    } else {
        "GCamera:MicroVideoOffset (레거시 스키마)"
    }
    checks.add(SefCheckResult(SefIntegritySeverity.INFO, "구글 모션포토 스키마", "감지된 스키마: $schemaLabel, 선언된 길이=$declaredLength"))

    if (declaredLength <= 0 || declaredLength > root.size) {
        checks.add(SefCheckResult(SefIntegritySeverity.CRITICAL, "구글 모션포토 길이 값", "선언된 길이($declaredLength)가 파일 크기(${root.size})를 벗어납니다"))
        return checks
    }

    val approxStart = root.size - declaredLength
    val declaredHasFtyp = try {
        reader.readFourCC(approxStart + 4) == "ftyp"
    } catch (e: Exception) {
        false
    }
    if (declaredHasFtyp) {
        checks.add(SefCheckResult(SefIntegritySeverity.PASS, "구글 모션포토 오프셋 일치", "선언된 오프셋($approxStart)에서 실제 ftyp를 확인했습니다"))
    } else {
        val corrected = correctMp4StartOffset(reader, approxStart)
        if (corrected != approxStart) {
            checks.add(SefCheckResult(SefIntegritySeverity.WARNING, "구글 모션포토 오프셋 불일치", "선언된 오프셋($approxStart)이 아니라 ${corrected - approxStart}바이트 떨어진 위치($corrected)에서 실제 ftyp를 찾았습니다 -- XMP 선언값이 정확하지 않습니다"))
        } else {
            checks.add(SefCheckResult(SefIntegritySeverity.CRITICAL, "구글 모션포토 비디오 위치", "선언된 오프셋 근방에서 ftyp를 찾지 못했습니다 -- 비디오 데이터가 없거나 심각하게 손상되었습니다"))
        }
    }

    return checks
}
