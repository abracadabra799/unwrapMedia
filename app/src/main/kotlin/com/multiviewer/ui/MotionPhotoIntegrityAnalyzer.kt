package com.multiviewer.ui

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.SefCheckResult
import com.multiviewer.parser.SefIntegrityReport
import com.multiviewer.parser.SefIntegritySeverity
import com.multiviewer.parser.correctMp4StartOffset
import com.multiviewer.parser.integrity.CheckStatus
import com.multiviewer.parser.findFirst
import com.multiviewer.parser.findMicroVideoOffset
import com.multiviewer.parser.findMotionPhotoInDirectory
import com.multiviewer.parser.findPresentationTimestampUs
import com.multiviewer.parser.parseXmpDocument
import com.multiviewer.parser.motionphoto.GoogleMotionPhotoSpecChecker
import com.multiviewer.parser.motionphoto.GoogleSpecFinding
import java.io.File

fun SefIntegritySeverity.toCheckStatus(): CheckStatus = when (this) {
    SefIntegritySeverity.PASS -> CheckStatus.PASS
    SefIntegritySeverity.INFO -> CheckStatus.INFO
    SefIntegritySeverity.WARNING -> CheckStatus.WARN
    SefIntegritySeverity.CRITICAL -> CheckStatus.FAIL
    SefIntegritySeverity.SKIPPED -> CheckStatus.SKIP
}

/** Single shared "is this a motion photo" predicate: drives the Content Check window's `contentTabs` gate for the Motion photo tab. */
fun hasMotionPhotoData(root: BoxNode): Boolean {
    val r = root
    return (findFirst(r) { it.type == "sefd" }?.let { sefd ->
        // Mirrors MotionPhotoIntegrityAnalyzer.kt's detectedFormats gate: MotionPhoto_Data
        // is mandatory for an intact trailer, but a structurally broken one (warnings
        // non-empty -- SefdBoxDecoder bails with no children in that case) must still
        // enable the menu so its CRITICAL diagnosis stays reachable.
        sefd.children.any { it.type == "MotionPhoto_Data" } || sefd.warnings.isNotEmpty()
    } == true) ||
        findFirst(r) { it.type == "mpvd" || it.type == "EmbeddedVideoData" } != null ||
        findFirst(r) {
            it.fields.any { f ->
                f.name == "xmp" && (f.value.contains("MotionPhoto", ignoreCase = true) || f.value.contains("MicroVideo", ignoreCase = true))
            }
        } != null
}

/** The primary XMP declares a Google motion photo (1.0 Camera/Container fields or the legacy MicroVideo ones). */
internal fun hasGoogleMotionPhotoXmp(root: BoxNode): Boolean =
    findFirst(root) { node -> node.fields.any { it.name == "xmp" } }?.fields?.find { it.name == "xmp" }?.value
        ?.let { it.contains("MotionPhoto", ignoreCase = true) || it.contains("MicroVideo", ignoreCase = true) } == true

enum class MotionPhotoFormat { SAMSUNG_SEF, GOOGLE_XMP, APPLE_MPVD }

data class MotionPhotoDeepAnalysis(
    val frameMatch: com.multiviewer.parser.motionphoto.FrameMatchResult?,
    val livePhotoPair: com.multiviewer.parser.motionphoto.LivePhotoPairReport?,
    val compatibilityMatrix: com.multiviewer.parser.motionphoto.CompatibilityMatrixReport,
)

data class MotionPhotoIntegrityReport(
    val detectedFormats: List<MotionPhotoFormat>,
    val sefSection: SefIntegrityReport?,
    /** Google Motion Photo 1.0 compliance: the first-priority standard, checked for every detected motion photo. */
    val googleSpec: List<GoogleSpecFinding>,
    val decodeChecks: List<SefCheckResult>,
    val overallSeverity: SefIntegritySeverity,
    val deepAnalysis: MotionPhotoDeepAnalysis? = null,
)

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
        ).redirectError(ProcessBuilder.Redirect.DISCARD) // never read: a full pipe would stall ffprobe
        FfmpegLocator.configureEnvironment(processBuilder)
        val process = processBuilder.start()
        // readProcessOutputWithTimeout has already reaped (or killed) the process on every path.
        val output = readProcessOutputWithTimeout(process, 30) { process.inputStream.bufferedReader().readText().trim() }
        output?.toDoubleOrNull()?.let { (it * 1_000_000).toLong() }
    } catch (e: InterruptedException) {
        throw e
    } catch (e: Exception) {
        null
    } finally {
        temp.delete()
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
    } catch (e: InterruptedException) {
        throw e
    } catch (e: Exception) {
        listOf(SefCheckResult(SefIntegritySeverity.CRITICAL, "임베디드 비디오 디코딩 확인", "ffmpeg 실행 실패: ${e.message}"))
    } finally {
        temp.delete()
    }
}

// Top-level orchestrator: opens exactly ONE ByteReader for the whole analysis (SEF delegation,
// Google XMP checks, and findEmbeddedVideo all share it) -- this project has already had to fix
// redundant-ByteReader.open bugs twice, so this never opens a second reader per section. Blocking
// (file I/O plus up to two ffmpeg/ffprobe subprocess calls -- probeVideoDurationUs and
// analyzeDecodability each extract and invoke their own subprocess): callers must invoke via
// withContext(Dispatchers.IO).
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
            val hasGoogleXmp = hasGoogleMotionPhotoXmp(root)
            val hasMpvd = com.multiviewer.parser.findFirst(root) { it.type == "mpvd" || it.type == "EmbeddedVideoData" } != null
            val detectedFormats = buildList {
                // MotionPhoto_Data is mandatory for a file to actually BE a SEF motion photo --
                // MotionPhoto_AutoPlay/MotionPhoto_Version are optional. A bare sefd box (ordinary
                // SEF-tagged EXIF metadata, no motion video at all) must not count as "detected".
                // Exception: a SEF trailer whose own report is CRITICAL for reasons that would prevent
                // MotionPhoto_Data from ever being found -- whole-trailer corruption (SEFT/SEFH magic
                // missing, position out of bounds: directoryEntries comes back empty), a specific
                // corrupted entry (name unreadable: directoryEntries has a CRITICAL row), or a
                // truncated directory (declaredEntryCountSeverity CRITICAL: entries beyond what was
                // found, possibly including MotionPhoto_Data itself, were never read). Deliberately
                // NOT sefSection's overall severity, which also folds in semanticChecks (e.g. a
                // malformed-JSON field elsewhere in an ordinary, non-motion-photo SEF trailer) and
                // would reintroduce the false-positive detection Task 2 fixed. Treating a file that
                // matches any of these as "detected, and here's why it's broken" (rather than "not
                // detected") keeps the integrity report reachable for the files it exists to diagnose.
                val sefEntriesUntrustworthy = sefSection != null && (
                    sefSection.structuralChecks.any { it.severity == SefIntegritySeverity.CRITICAL } ||
                        sefSection.directoryEntries.any { it.status == SefIntegritySeverity.CRITICAL } ||
                        sefSection.declaredEntryCountSeverity == SefIntegritySeverity.CRITICAL
                )
                if (sefSection?.directoryEntries?.any { it.name == "MotionPhoto_Data" } == true || sefEntriesUntrustworthy) {
                    add(MotionPhotoFormat.SAMSUNG_SEF)
                }
                if (hasGoogleXmp) add(MotionPhotoFormat.GOOGLE_XMP)
                if (hasMpvd) add(MotionPhotoFormat.APPLE_MPVD)
            }
            // Google Motion Photo 1.0 is the first-priority standard: every detected motion photo is held to it,
            // Samsung SEF-only files included (they fail §3.1 Camera:MotionPhoto).
            val googleSpec = if (detectedFormats.isNotEmpty()) {
                GoogleMotionPhotoSpecChecker.check(file, root, reader, videoDurationUs)
            } else {
                emptyList()
            }

            val decodeChecks = if (video != null) {
                analyzeDecodability(file, video)
            } else if (detectedFormats.isNotEmpty()) {
                listOf(
                    SefCheckResult(
                        SefIntegritySeverity.CRITICAL,
                        "임베디드 비디오 디코딩 확인",
                        "모션포토 메타데이터가 감지되었으나 유효한 임베디드 비디오 스트림을 추출할 수 없어 디코딩할 수 없습니다",
                    ),
                )
            } else {
                emptyList()
            }

            val allSeverities = (sefSection?.let { it.structuralChecks + it.semanticChecks }?.map { it.severity } ?: emptyList()) +
                (sefSection?.directoryEntries?.map { it.status } ?: emptyList()) +
                (sefSection?.let { listOf(it.declaredEntryCountSeverity) } ?: emptyList()) +
                googleSpec.map { it.severity } + decodeChecks.map { it.severity }
            val overall = when {
                allSeverities.any { it == SefIntegritySeverity.CRITICAL } -> SefIntegritySeverity.CRITICAL
                allSeverities.any { it == SefIntegritySeverity.WARNING } -> SefIntegritySeverity.WARNING
                else -> SefIntegritySeverity.PASS
            }

            val deepAnalysis = try {
                analyzeDeepMotionPhoto(file, root, reader, video)
            } catch (_: Exception) {
                null
            }

            MotionPhotoIntegrityReport(detectedFormats, sefSection, googleSpec, decodeChecks, overall, deepAnalysis)
        }
    }

    private fun analyzeDeepMotionPhoto(
        file: File,
        root: BoxNode,
        reader: ByteReader,
        video: com.multiviewer.parser.EmbeddedVideo?,
    ): MotionPhotoDeepAnalysis {
        var videoRoot: BoxNode? = null
        var sampleTable: com.multiviewer.parser.VideoSampleTable? = null

        if (video != null) {
            val tempVideo = File.createTempFile("motion-photo-deep-probe", ".${video.extension}")
            try {
                com.multiviewer.parser.extractEmbeddedVideo(file, video, tempVideo)
                videoRoot = try { com.multiviewer.parser.parseFile(tempVideo) } catch (_: Exception) { null }
                if (videoRoot != null) {
                    ByteReader.open(tempVideo).use { vReader ->
                        sampleTable = com.multiviewer.parser.buildVideoSampleTable(vReader, videoRoot)
                    }
                }
            } catch (_: Exception) {
                // Extraction failure fallback
            } finally {
                tempVideo.delete()
            }
        } else {
            // Standalone video file case
            sampleTable = com.multiviewer.parser.buildVideoSampleTable(reader, root)
        }

        // 1. Frame match
        val targetTimestampUs = com.multiviewer.parser.motionphoto.extractPresentationTimestampUs(root)
        val frameMatch = if (sampleTable != null && targetTimestampUs != null) {
            com.multiviewer.parser.motionphoto.matchFrameToTimestamp(sampleTable, targetTimestampUs)
        } else {
            null
        }

        // 2. Apple Live Photo Pair
        val stillMeta = com.multiviewer.parser.motionphoto.extractAppleStillMetadata(root)
        val videoMeta = if (videoRoot != null) {
            com.multiviewer.parser.motionphoto.extractAppleVideoMetadata(videoRoot)
        } else {
            com.multiviewer.parser.motionphoto.extractAppleVideoMetadata(root)
        }

        val pairCandidate = com.multiviewer.parser.motionphoto.findLivePhotoPairCandidate(file)
        val livePhotoPair = if (pairCandidate != null) {
            val pairRoot = try { com.multiviewer.parser.parseFile(pairCandidate) } catch (_: Exception) { null }
            val pairStill = pairRoot?.let { com.multiviewer.parser.motionphoto.extractAppleStillMetadata(it) }
            val pairVideo = pairRoot?.let { com.multiviewer.parser.motionphoto.extractAppleVideoMetadata(it) }
            when {
                stillMeta != null && pairVideo != null ->
                    com.multiviewer.parser.motionphoto.inspectLivePhotoPair(stillMeta, pairVideo, file, pairCandidate)
                videoMeta != null && pairStill != null ->
                    com.multiviewer.parser.motionphoto.inspectLivePhotoPair(pairStill, videoMeta, pairCandidate, file)
                else ->
                    com.multiviewer.parser.motionphoto.inspectLivePhotoPair(stillMeta, videoMeta, file, pairCandidate)
            }
        } else if (stillMeta != null || videoMeta != null) {
            com.multiviewer.parser.motionphoto.inspectLivePhotoPair(stillMeta, videoMeta, file, null)
        } else {
            null
        }

        // 3. Compatibility matrix
        val hasPair = livePhotoPair?.status == com.multiviewer.parser.motionphoto.LivePhotoPairStatus.MATCHED
        val specs = com.multiviewer.parser.motionphoto.extractMotionPhotoProfileSpecs(
            root = root,
            hasPair = hasPair,
            videoCodec = sampleTable?.codec?.name,
        )
        val compatMatrix = com.multiviewer.parser.motionphoto.evaluateMotionPhotoCompatibility(specs)

        return MotionPhotoDeepAnalysis(
            frameMatch = frameMatch,
            livePhotoPair = livePhotoPair,
            compatibilityMatrix = compatMatrix,
        )
    }
}
