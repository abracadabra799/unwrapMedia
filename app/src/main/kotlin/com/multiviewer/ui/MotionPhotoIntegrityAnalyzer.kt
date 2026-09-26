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

// Verifies an Apple/QuickTime-style embedded video (an "mpvd" or "EmbeddedVideoData" box, used by
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
// to a temp file and runs a real ffprobe on it, to catch corruption/truncation that pure
// offset/length arithmetic can't -- every other check in this file validates declared *positions*,
// this is the only one that validates the actual bytes decode.
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
            FfmpegLocator.ffprobePath(), "-v", "error",
            "-show_entries", "format=duration",
            "-of", "default=noprint_wrappers=1",
            temp.absolutePath,
        ).redirectErrorStream(true)
        FfmpegLocator.configureEnvironment(processBuilder)
        val process = processBuilder.start()
        val output = readProcessOutputWithTimeout(process, 30) { process.inputStream.bufferedReader().readText().trim() }
        if (output == null) {
            listOf(SefCheckResult(SefIntegritySeverity.CRITICAL, "임베디드 비디오 디코딩 확인", "ffprobe 실행이 시간 초과되었습니다"))
        } else {
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                listOf(SefCheckResult(SefIntegritySeverity.PASS, "임베디드 비디오 디코딩 확인", "ffprobe로 정상적으로 스트림 정보를 읽었습니다: $output"))
            } else {
                listOf(SefCheckResult(SefIntegritySeverity.CRITICAL, "임베디드 비디오 디코딩 확인", "ffprobe가 실패했습니다 (exit=$exitCode): $output"))
            }
        }
    } catch (e: Exception) {
        listOf(SefCheckResult(SefIntegritySeverity.CRITICAL, "임베디드 비디오 디코딩 확인", "ffprobe 실행 실패: ${e.message}"))
    } finally {
        temp.delete()
    }
}

// Top-level orchestrator: opens exactly ONE ByteReader for the whole analysis (SEF delegation,
// Google XMP checks, and findEmbeddedVideo all share it) -- this project has already had to fix
// redundant-ByteReader.open bugs twice, so this never opens a second reader per section. Blocking
// (file I/O + one ffprobe subprocess call): callers must invoke via withContext(Dispatchers.IO).
object MotionPhotoIntegrityAnalyzer {
    fun analyze(file: File, root: BoxNode): MotionPhotoIntegrityReport {
        return ByteReader.open(file).use { reader ->
            val sefdNode = com.multiviewer.parser.findFirst(root) { it.type == "sefd" }
            val sefSection = sefdNode?.let { sefd ->
                com.multiviewer.parser.SefIntegrityAnalyzer.analyze(reader, sefd.offset, sefd.headerSize, sefd.size, file.length())
            }
            val googleChecks = analyzeGoogleXmpSection(root, reader)
            val appleChecks = analyzeAppleMpvdSection(root, file.length())
            val video = try {
                com.multiviewer.parser.findEmbeddedVideo(root, reader)
            } catch (e: Exception) {
                null
            }
            val decodeChecks = analyzeDecodability(file, video)

            val detectedFormats = buildList {
                if (sefdNode != null) add(MotionPhotoFormat.SAMSUNG_SEF)
                if (googleChecks.isNotEmpty()) add(MotionPhotoFormat.GOOGLE_XMP)
                if (appleChecks.isNotEmpty()) add(MotionPhotoFormat.APPLE_MPVD)
            }

            val allSeverities = (sefSection?.let { it.structuralChecks + it.semanticChecks } ?: emptyList()) +
                googleChecks + appleChecks + decodeChecks
            val overall = when {
                allSeverities.any { it.severity == SefIntegritySeverity.CRITICAL } -> SefIntegritySeverity.CRITICAL
                allSeverities.any { it.severity == SefIntegritySeverity.WARNING } -> SefIntegritySeverity.WARNING
                else -> SefIntegritySeverity.PASS
            }

            MotionPhotoIntegrityReport(detectedFormats, sefSection, googleChecks, appleChecks, decodeChecks, overall)
        }
    }
}
