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
import com.multiviewer.parser.findPresentationTimestampUs
import com.multiviewer.parser.parseXmpDocument
import java.io.File

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
internal fun analyzeGoogleXmpSection(root: BoxNode, reader: ByteReader, videoDurationUs: Long?): List<SefCheckResult> {
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
    var resolvedOffset: Long? = null
    if (declaredHasFtyp) {
        checks.add(SefCheckResult(SefIntegritySeverity.PASS, "구글 모션포토 오프셋 일치", "선언된 오프셋($approxStart)에서 실제 ftyp를 확인했습니다"))
        resolvedOffset = approxStart
    } else {
        val corrected = correctMp4StartOffset(reader, approxStart)
        if (corrected != approxStart) {
            checks.add(SefCheckResult(SefIntegritySeverity.WARNING, "구글 모션포토 오프셋 불일치", "선언된 오프셋($approxStart)이 아니라 ${corrected - approxStart}바이트 떨어진 위치($corrected)에서 실제 ftyp를 찾았습니다 -- XMP 선언값이 정확하지 않습니다"))
            resolvedOffset = corrected
        } else {
            checks.add(SefCheckResult(SefIntegritySeverity.CRITICAL, "구글 모션포토 비디오 위치", "선언된 오프셋 근방에서 ftyp를 찾지 못했습니다 -- 비디오 데이터가 없거나 심각하게 손상되었습니다"))
        }
    }

    // Item:Padding -- structural validity only (a non-negative integer); this codebase's own
    // extraction logic doesn't use Padding for any byte-offset computation, so there's no
    // independent cross-check to validate its exact value against.
    fromDirectory?.padding?.let { padding ->
        val paddingValue = padding.toLongOrNull()
        checks.add(
            if (paddingValue != null && paddingValue >= 0)
                SefCheckResult(SefIntegritySeverity.PASS, "구글 모션포토 Padding", "Item:Padding=$padding")
            else
                SefCheckResult(SefIntegritySeverity.WARNING, "구글 모션포토 Padding", "Item:Padding=\"$padding\"은 0 이상의 정수가 아닙니다"),
        )
    }

    // Item:Mime -- compare the declared MIME against the real resolved video's own ftyp major_brand
    // (ftyp layout: 4-byte size, 4-byte "ftyp" tag, then major_brand -- resolvedOffset+8).
    if (resolvedOffset != null) {
        fromDirectory?.mimeType?.let { declaredMime ->
            val majorBrand = try {
                reader.readFourCC(resolvedOffset + 8)
            } catch (e: Exception) {
                null
            }
            if (majorBrand != null) {
                val expectedMime = if (majorBrand.trim() == "qt") "video/quicktime" else "video/mp4"
                checks.add(
                    if (declaredMime == expectedMime)
                        SefCheckResult(SefIntegritySeverity.PASS, "구글 모션포토 Mime", "Item:Mime=\"$declaredMime\"이 실제 컨테이너(major_brand=\"${majorBrand.trim()}\")와 일치합니다")
                    else
                        SefCheckResult(SefIntegritySeverity.WARNING, "구글 모션포토 Mime", "Item:Mime=\"$declaredMime\"이 실제 컨테이너(major_brand=\"${majorBrand.trim()}\", 예상 \"$expectedMime\")와 다릅니다"),
                )
            }
        }
    }

    // PresentationTimestampUs -- the shutter-click moment should fall within the video's real duration.
    val presentationTimestampUs = findPresentationTimestampUs(document)
    if (presentationTimestampUs != null) {
        checks.add(
            when {
                videoDurationUs == null -> SefCheckResult(SefIntegritySeverity.SKIPPED, "구글 모션포토 셔터 타임스탬프", "비디오 길이를 확인할 수 없어 검증을 건너뜁니다")
                presentationTimestampUs in 0..videoDurationUs -> SefCheckResult(SefIntegritySeverity.PASS, "구글 모션포토 셔터 타임스탬프", "PresentationTimestampUs=${presentationTimestampUs}us (비디오 길이 ${videoDurationUs}us 이내)")
                else -> SefCheckResult(SefIntegritySeverity.WARNING, "구글 모션포토 셔터 타임스탬프", "PresentationTimestampUs=${presentationTimestampUs}us 가 비디오 길이(${videoDurationUs}us) 범위를 벗어납니다")
            },
        )
    }

    return checks
}

// Probes the resolved video's real duration (needed to validate PresentationTimestampUs falls
// within it). Extracts to a temp file the same way analyzeDecodability does -- a second
// extraction+ffprobe pass of the same bytes, accepted as a simplicity tradeoff on this
// non-hot-path analyzer rather than threading a shared temp file between the two functions.
internal fun probeVideoDurationUs(file: File, video: com.multiviewer.parser.EmbeddedVideo): Long? {
    val temp = File.createTempFile("motion-photo-duration-probe", ".${video.extension}")
    return try {
        com.multiviewer.parser.extractEmbeddedVideo(file, video, temp)
        val processBuilder = ProcessBuilder(
            FfmpegLocator.ffprobePath(), "-v", "error",
            "-show_entries", "format=duration",
            "-of", "csv=p=0",
            temp.absolutePath,
        )
        FfmpegLocator.configureEnvironment(processBuilder)
        val process = processBuilder.start()
        val output = readProcessOutputWithTimeout(process, 30) { process.inputStream.bufferedReader().readText().trim() }
        process.waitFor()
        output?.toDoubleOrNull()?.let { (it * 1_000_000).toLong() }
    } catch (e: Exception) {
        null
    } finally {
        temp.delete()
    }
}

// Verifies a HEIC-style embedded video (an "mpvd" or "EmbeddedVideoData" box, used by
// this app's HEIC motion-photo path) is positioned within the file and has a real ftyp child --
// the same box shape findEmbeddedVideo already reads to extract the video, but this reports on
// its structural validity instead of just extracting it.
internal fun analyzeAppleMpvdSection(root: BoxNode, fileLength: Long): List<SefCheckResult> {
    val mpvdNode = com.multiviewer.parser.findFirst(root) { it.type == "mpvd" || it.type == "EmbeddedVideoData" }
        ?: return emptyList()

    if (mpvdNode.offset < 0 || mpvdNode.offset + mpvdNode.size > fileLength) {
        return listOf(SefCheckResult(SefIntegritySeverity.CRITICAL, "mpvd 박스 범위", "mpvd 박스가 파일 범위를 벗어납니다 (offset=${mpvdNode.offset}, size=${mpvdNode.size}, file=$fileLength)"))
    }

    val ftypChild = mpvdNode.children.find { it.type == "ftyp" }
    return if (ftypChild == null) {
        listOf(SefCheckResult(SefIntegritySeverity.WARNING, "mpvd 내부 ftyp", "mpvd 박스 내부에서 ftyp 자식 박스를 찾지 못했습니다"))
    } else {
        val majorBrand = ftypChild.fields.find { it.name == "major_brand" }?.value?.trim() ?: "알 수 없음"
        listOf(SefCheckResult(SefIntegritySeverity.PASS, "mpvd 내부 ftyp", "major_brand=\"$majorBrand\""))
    }
}

// Format-independent: extracts whichever video findEmbeddedVideo resolved (any of the 3 formats)
// to a temp file and runs a real ffmpeg decode pass on it, to catch corruption/truncation that pure
// offset/length arithmetic can't -- every other check in this file validates declared *positions*,
// this is the only one that validates the actual bytes decode. `-f null -` forces genuine frame
// decoding; ffprobe alone only reads container metadata and would miss frame-level corruption.
internal fun analyzeDecodability(file: File, video: com.multiviewer.parser.EmbeddedVideo?): List<SefCheckResult> {
    if (video == null) return emptyList()
    val temp = File.createTempFile("motion-photo-decode-check", ".${video.extension}")
    return try {
        // extractEmbeddedVideo opens its own separate ByteReader on `file` internally (brief, "copy
        // bytes out" one-shot open+close via .use{}) -- a documented, accepted exception to this
        // analyzer's "one shared ByteReader for the whole analysis" constraint, not the redundant-
        // reopen-per-tab pattern that constraint exists to prevent. Not worth changing
        // extractEmbeddedVideo's shared File-based signature (used elsewhere in the app) for this one
        // caller.
        com.multiviewer.parser.extractEmbeddedVideo(file, video, temp)
        val processBuilder = ProcessBuilder(
            FfmpegLocator.ffmpegPath(), "-v", "error",
            "-i", temp.absolutePath,
            "-f", "null", "-",
        ).redirectErrorStream(true)
        FfmpegLocator.configureEnvironment(processBuilder)
        val process = processBuilder.start()
        val output = readProcessOutputWithTimeout(process, 30) { process.inputStream.bufferedReader().readText().trim() }
        if (output == null) {
            listOf(SefCheckResult(SefIntegritySeverity.CRITICAL, "임베디드 비디오 디코딩 확인", "ffmpeg 실행이 시간 초과되었습니다"))
        } else {
            val exitCode = process.waitFor()
            if (exitCode == 0 && output.isEmpty()) {
                listOf(SefCheckResult(SefIntegritySeverity.PASS, "임베디드 비디오 디코딩 확인", "ffmpeg으로 전체 비디오 디코딩에 성공했습니다"))
            } else {
                // Truncate: a badly corrupted video can produce 100+ lines of decoder errors, which would
                // otherwise blow out this single report row.
                val truncated = if (output.length > 2000) output.take(2000) + "... (truncated)" else output
                listOf(SefCheckResult(SefIntegritySeverity.CRITICAL, "임베디드 비디오 디코딩 확인", "ffmpeg가 디코딩 오류를 보고했습니다 (exit=$exitCode): $truncated"))
            }
        }
    } catch (e: Exception) {
        listOf(SefCheckResult(SefIntegritySeverity.CRITICAL, "임베디드 비디오 디코딩 확인", "ffmpeg 실행 실패: ${e.message}"))
    } finally {
        temp.delete()
    }
}

// Top-level orchestrator: opens exactly ONE ByteReader for the whole analysis (SEF delegation,
// Google XMP checks, and findEmbeddedVideo all share it) -- this project has already had to fix
// redundant-ByteReader.open bugs twice, so this never opens a second reader per section. Blocking
// (file I/O + one ffmpeg subprocess call): callers must invoke via withContext(Dispatchers.IO).
object MotionPhotoIntegrityAnalyzer {
    fun analyze(file: File, root: BoxNode): MotionPhotoIntegrityReport {
        return ByteReader.open(file).use { reader ->
            val sefdNode = com.multiviewer.parser.findFirst(root) { it.type == "sefd" }
            val sefSection = sefdNode?.let { sefd ->
                com.multiviewer.parser.SefIntegrityAnalyzer.analyze(reader, sefd.offset, sefd.headerSize, sefd.size, file.length())
            }
            val video = try {
                com.multiviewer.parser.findEmbeddedVideo(root, reader)
            } catch (e: Exception) {
                null
            }
            val videoDurationUs = video?.let { probeVideoDurationUs(file, it) }
            val googleChecks = analyzeGoogleXmpSection(root, reader, videoDurationUs)
            val appleChecks = analyzeAppleMpvdSection(root, file.length())
            val decodeChecks = analyzeDecodability(file, video)

            val detectedFormats = buildList {
                if (sefdNode != null) add(MotionPhotoFormat.SAMSUNG_SEF)
                if (googleChecks.isNotEmpty()) add(MotionPhotoFormat.GOOGLE_XMP)
                if (appleChecks.isNotEmpty()) add(MotionPhotoFormat.APPLE_MPVD)
            }

            val allSeverities = (sefSection?.let { it.structuralChecks + it.semanticChecks }?.map { it.severity } ?: emptyList()) +
                (sefSection?.directoryEntries?.map { it.status } ?: emptyList()) +
                (sefSection?.let { listOf(it.declaredEntryCountSeverity) } ?: emptyList()) +
                (googleChecks + appleChecks + decodeChecks).map { it.severity }
            val overall = when {
                allSeverities.any { it == SefIntegritySeverity.CRITICAL } -> SefIntegritySeverity.CRITICAL
                allSeverities.any { it == SefIntegritySeverity.WARNING } -> SefIntegritySeverity.WARNING
                else -> SefIntegritySeverity.PASS
            }

            MotionPhotoIntegrityReport(detectedFormats, sefSection, googleChecks, appleChecks, decodeChecks, overall)
        }
    }
}
