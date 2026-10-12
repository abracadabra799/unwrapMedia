package com.multiviewer.parser.motionphoto

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.SefCheckResult
import com.multiviewer.parser.SefIntegritySeverity
import com.multiviewer.parser.findFirst
import java.io.File
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs

enum class LivePhotoPairStatus {
    MATCHED,            // 스틸과 비디오의 Content ID UUID 일치
    MISMATCHED_UUID,    // 서로 다른 Content ID
    MISSING_STILL_ID,   // 스틸 이미지에 Content ID 부재
    MISSING_VIDEO_ID,   // 동영상에 Content ID 부재
    NOT_LIVE_PHOTO,     // 둘 다 Content ID 부재
}

data class AppleStillMetadata(
    val contentIdentifier: String?,
    val dateTimeOriginal: String? = null,
    val makerNoteStillTime: Long? = null,
)

data class AppleVideoMetadata(
    val contentIdentifier: String?,
    val creationDate: String? = null,
    val stillImageTime: Long? = null,
    val vitalityScore: Float? = null,
)

data class LivePhotoPairReport(
    val status: LivePhotoPairStatus,
    val stillFile: File?,
    val videoFile: File?,
    val stillMetadata: AppleStillMetadata?,
    val videoMetadata: AppleVideoMetadata?,
    val uuidMatch: Boolean,
    val timeDeltaSeconds: Long?,
    val checks: List<SefCheckResult>,
)

/**
 * Extracts Apple Live Photo metadata from a still image BoxNode (HEIC or JPEG).
 */
fun extractAppleStillMetadata(root: BoxNode): AppleStillMetadata? {
    var contentId: String? = null
    var dateTimeOriginal: String? = null
    var makerNoteStillTime: Long? = null

    // 1. QuickTime meta keys (HEIC)
    findFirst(root) { node ->
        node.fields.any { it.name.contains("content.identifier", ignoreCase = true) }
    }?.let { node ->
        contentId = node.fields.find { it.name.contains("content.identifier", ignoreCase = true) }?.value
    }

    // 2. MakerNote (Apple Tag 0x0011 ContentIdentifier or similar)
    if (contentId == null) {
        val makerNote = findFirst(root) { it.type == "MakerNote" }
        contentId = makerNote?.fields?.find { it.name.equals("ContentIdentifier", ignoreCase = true) }?.value
        makerNoteStillTime = makerNote?.fields?.find { it.name.contains("CoverageTime", ignoreCase = true) }?.value?.toLongOrNull()
    }

    // 3. XMP (apple-fi:ContentIdentifier)
    if (contentId == null) {
        val xmpText = findFirst(root) { it.fields.any { f -> f.name == "xmp" } }
            ?.fields?.find { it.name == "xmp" }?.value
        if (xmpText != null) {
            val uuidRegex = Regex("""(?:apple-fi:ContentIdentifier|ContentIdentifier)[=>"]+([0-9a-fA-F\-]{36})""")
            uuidRegex.find(xmpText)?.let {
                contentId = it.groupValues[1]
            }
        }
    }

    // EXIF DateTimeOriginal
    val exifNode = findFirst(root) { node ->
        node.fields.any { it.name.equals("DateTimeOriginal", ignoreCase = true) }
    }
    dateTimeOriginal = exifNode?.fields?.find { it.name.equals("DateTimeOriginal", ignoreCase = true) }?.value

    if (contentId == null && dateTimeOriginal == null) return null
    return AppleStillMetadata(
        contentIdentifier = contentId?.trim(),
        dateTimeOriginal = dateTimeOriginal?.trim(),
        makerNoteStillTime = makerNoteStillTime,
    )
}

/**
 * Extracts Apple Live Photo metadata from a video BoxNode (MOV).
 */
fun extractAppleVideoMetadata(root: BoxNode): AppleVideoMetadata? {
    var contentId: String? = null
    var creationDate: String? = null
    var stillImageTime: Long? = null
    var vitalityScore: Float? = null

    val metaNode = findFirst(root) { node ->
        node.fields.any { it.name.contains("content.identifier", ignoreCase = true) || it.name.contains("creationdate", ignoreCase = true) }
    }

    metaNode?.fields?.forEach { field ->
        when {
            field.name.contains("content.identifier", ignoreCase = true) -> contentId = field.value
            field.name.contains("creationdate", ignoreCase = true) -> creationDate = field.value
            field.name.contains("still-image-time", ignoreCase = true) -> stillImageTime = field.value.toLongOrNull()
            field.name.contains("vitality-score", ignoreCase = true) -> vitalityScore = field.value.toFloatOrNull()
        }
    }

    if (contentId == null && creationDate == null && stillImageTime == null) return null
    return AppleVideoMetadata(
        contentIdentifier = contentId?.trim(),
        creationDate = creationDate?.trim(),
        stillImageTime = stillImageTime,
        vitalityScore = vitalityScore,
    )
}

/**
 * Evaluates pairing between still image and video metadata.
 */
fun inspectLivePhotoPair(
    stillMetadata: AppleStillMetadata?,
    videoMetadata: AppleVideoMetadata?,
    stillFile: File? = null,
    videoFile: File? = null,
): LivePhotoPairReport {
    val stillId = stillMetadata?.contentIdentifier
    val videoId = videoMetadata?.contentIdentifier

    val uuidMatch = if (!stillId.isNullOrBlank() && !videoId.isNullOrBlank()) {
        stillId.equals(videoId, ignoreCase = true)
    } else {
        false
    }

    val status = when {
        stillId.isNullOrBlank() && videoId.isNullOrBlank() -> LivePhotoPairStatus.NOT_LIVE_PHOTO
        stillId.isNullOrBlank() -> LivePhotoPairStatus.MISSING_STILL_ID
        videoId.isNullOrBlank() -> LivePhotoPairStatus.MISSING_VIDEO_ID
        uuidMatch -> LivePhotoPairStatus.MATCHED
        else -> LivePhotoPairStatus.MISMATCHED_UUID
    }

    val checks = mutableListOf<SefCheckResult>()

    // 1. UUID Check
    when (status) {
        LivePhotoPairStatus.MATCHED -> {
            checks.add(SefCheckResult(SefIntegritySeverity.PASS, "Content Identifier UUID 일치", "스틸과 비디오의 Content ID가 일치합니다 ($stillId)"))
        }
        LivePhotoPairStatus.MISMATCHED_UUID -> {
            checks.add(SefCheckResult(SefIntegritySeverity.CRITICAL, "Content Identifier UUID 일치", "스틸 ID($stillId)와 비디오 ID($videoId)가 일치하지 않습니다"))
        }
        LivePhotoPairStatus.MISSING_STILL_ID -> {
            checks.add(SefCheckResult(SefIntegritySeverity.WARNING, "스틸 Content Identifier", "스틸 이미지에서 Live Photo Content ID를 찾을 수 없습니다"))
        }
        LivePhotoPairStatus.MISSING_VIDEO_ID -> {
            checks.add(SefCheckResult(SefIntegritySeverity.WARNING, "비디오 Content Identifier", "동영상 파일에서 Live Photo Content ID를 찾을 수 없습니다"))
        }
        LivePhotoPairStatus.NOT_LIVE_PHOTO -> {
            checks.add(SefCheckResult(SefIntegritySeverity.INFO, "Live Photo 식별자", "스틸과 동영상 모두에서 Live Photo 식별자가 발견되지 않았습니다"))
        }
    }

    // 2. Still image time check
    videoMetadata?.stillImageTime?.let { time ->
        checks.add(
            if (time >= 0L) {
                SefCheckResult(SefIntegritySeverity.PASS, "Live Photo Still Image Time", "비디오 내 스틸 프레임 위치=$time")
            } else {
                SefCheckResult(SefIntegritySeverity.INFO, "Live Photo Still Image Time", "스틸 프레임 위치 미지정(기본값=$time)")
            },
        )
    }

    // 3. Date / Time synchronization check
    val timeDelta = computeTimeDeltaSeconds(stillMetadata?.dateTimeOriginal, videoMetadata?.creationDate)
    if (timeDelta != null) {
        if (abs(timeDelta) <= 5L) {
            checks.add(SefCheckResult(SefIntegritySeverity.PASS, "촬영 시각 동기화", "스틸과 비디오의 촬영 시각이 일치합니다 (차이 ${timeDelta}초)"))
        } else {
            checks.add(SefCheckResult(SefIntegritySeverity.WARNING, "촬영 시각 동기화", "스틸과 비디오의 촬영 시각 차이가 큽니다 (${timeDelta}초 차이)"))
        }
    }

    return LivePhotoPairReport(
        status = status,
        stillFile = stillFile,
        videoFile = videoFile,
        stillMetadata = stillMetadata,
        videoMetadata = videoMetadata,
        uuidMatch = uuidMatch,
        timeDeltaSeconds = timeDelta,
        checks = checks,
    )
}

/**
 * Computes difference in seconds between still DateTimeOriginal ("yyyy:MM:dd HH:mm:ss")
 * and QuickTime CreationDate (ISO-8601 string).
 */
private fun computeTimeDeltaSeconds(stillDateStr: String?, videoDateStr: String?): Long? {
    if (stillDateStr.isNullOrBlank() || videoDateStr.isNullOrBlank()) return null
    return try {
        val stillLdt = LocalDateTime.parse(stillDateStr.trim(), DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss"))
        val videoOdt = try {
            OffsetDateTime.parse(videoDateStr.trim())
        } catch (_: Exception) {
            LocalDateTime.parse(videoDateStr.trim().take(19), DateTimeFormatter.ISO_LOCAL_DATE_TIME).atOffset(ZoneOffset.UTC)
        }
        ChronoUnit.SECONDS.between(stillLdt.toInstant(videoOdt.offset), videoOdt.toInstant())
    } catch (_: Exception) {
        null
    }
}

/**
 * Looks for candidate pair file in the same directory:
 * - If given an image (HEIC/JPG/JPEG), looks for .MOV / .mp4 with matching base name.
 * - If given a video (MOV/MP4), looks for .HEIC / .JPG / .JPEG with matching base name.
 */
fun findLivePhotoPairCandidate(sourceFile: File): File? {
    val parent = sourceFile.parentFile ?: return null
    val baseName = sourceFile.nameWithoutExtension
    val ext = sourceFile.extension.lowercase()

    val targetExtensions = when (ext) {
        "heic", "jpg", "jpeg" -> setOf("mov", "mp4")
        "mov", "mp4" -> setOf("heic", "jpg", "jpeg")
        else -> emptySet()
    }

    if (targetExtensions.isEmpty()) return null

    val files = parent.listFiles() ?: return null
    for (file in files) {
        if (!file.isFile) continue
        if (file.nameWithoutExtension.equals(baseName, ignoreCase = true) &&
            file.extension.lowercase() in targetExtensions &&
            file.canonicalPath != sourceFile.canonicalPath
        ) {
            return file
        }
    }
    return null
}
