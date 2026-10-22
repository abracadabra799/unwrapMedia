package com.multiviewer.parser.motionphoto

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.EmbeddedVideo
import com.multiviewer.parser.GainmapParser
import com.multiviewer.parser.SefIntegrityAnalyzer
import com.multiviewer.parser.SefIntegritySeverity
import com.multiviewer.parser.VideoSampleTable
import com.multiviewer.parser.buildVideoSampleTable
import com.multiviewer.parser.correctMp4StartOffset
import com.multiviewer.parser.extractEmbeddedVideo
import com.multiviewer.parser.findEmbeddedVideo
import com.multiviewer.parser.findFirst
import com.multiviewer.parser.findMicroVideoOffset
import com.multiviewer.parser.findMotionPhotoInDirectory
import com.multiviewer.parser.findPresentationTimestampUs
import com.multiviewer.parser.integrity.CheckStatus
import com.multiviewer.parser.integrity.ImageIntegrityChecker
import com.multiviewer.parser.parseFile
import com.multiviewer.parser.parseXmpDocument
import com.multiviewer.parser.validateMotionPhotoPointer
import com.multiviewer.parser.walkMp4TopLevelBoxes
import com.multiviewer.ui.FfmpegLocator
import com.multiviewer.ui.readProcessOutputWithTimeout
import com.multiviewer.util.deleteWhenReleased
import java.io.File
import java.util.Locale
import kotlin.math.abs

data class MotionPhotoInspectionItem(
    val id: String,
    val status: CheckStatus,
    val label: String,
    val expected: String? = null,
    val actual: String? = null,
    val detail: String,
    val offset: Long? = null,
    val length: Long? = null,
    val remediation: String? = null,
)

data class MotionPhotoInspectionSection(
    val id: String,
    val titleKo: String,
    val titleEn: String,
    val status: CheckStatus,
    val items: List<MotionPhotoInspectionItem>,
) {
    val passCount: Int get() = items.count { it.status == CheckStatus.PASS }
    val totalCount: Int get() = items.size
}

data class MotionPhotoInspectionReport(
    val formatLabel: String,
    val overallStatus: CheckStatus,
    val stillImageSection: MotionPhotoInspectionSection,
    val formatXmpSection: MotionPhotoInspectionSection,
    val sefSection: MotionPhotoInspectionSection,
    val videoCodecSection: MotionPhotoInspectionSection,
    val avSyncSection: MotionPhotoInspectionSection,
) {
    val allSections: List<MotionPhotoInspectionSection>
        get() = listOf(stillImageSection, formatXmpSection, sefSection, videoCodecSection, avSyncSection)
}

/**
 * Playback length of [table] in seconds: the last sample's decode time plus its duration. The table keeps
 * no per-sample duration, so the last sample is given the previous sample's decode-time delta (exact for
 * constant frame rate; a single sample counts as 0 s). This replaces two disagreeing formulas: one added
 * 1000 *ticks* whatever the timescale (11 ms at 90 kHz, 1.7 s at 600 Hz), the other left out the last frame.
 */
internal fun videoSampleTableDurationSec(table: VideoSampleTable): Double {
    val samples = table.samples
    if (samples.isEmpty() || table.timescale <= 0) return 0.0
    val last = samples.last()
    val lastDelta = if (samples.size >= 2) (last.decodeTime - samples[samples.size - 2].decodeTime).coerceAtLeast(0) else 0L
    return (last.decodeTime + lastDelta).toDouble() / table.timescale
}

object MotionPhotoInspectorEngine {

    // The inspector runs under runInterruptible: closing its window interrupts this thread. The sections
    // swallow most exceptions to degrade gracefully, so check between them that nobody asked us to stop.
    private fun throwIfInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Motion photo inspection cancelled")
    }

    fun inspect(file: File, root: BoxNode): MotionPhotoInspectionReport {
        return ByteReader.open(file).use { reader ->
            val stillSection = inspectStillImage(file, root, reader)
            throwIfInterrupted()
            val embeddedVideo = try { findEmbeddedVideo(root, reader) } catch (_: Exception) { null }

            // Extract embedded video once if available for video and av-sync analysis
            var tempVideoFile: File? = null
            var videoRoot: BoxNode? = null
            // Assigned the moment the temp file exists, so the finally below removes it on every path --
            // including a cancel (interrupt) or failure midway through extraction.
            var createdTemp: File? = null
            try {
                if (embeddedVideo != null) {
                    try {
                        val temp = File.createTempFile("motion-photo-inspect-video", ".${embeddedVideo.extension}")
                        createdTemp = temp
                        extractEmbeddedVideo(file, embeddedVideo, temp)
                        tempVideoFile = temp
                        videoRoot = try { parseFile(temp) } catch (_: Exception) { null }
                    } catch (e: InterruptedException) {
                        throw e
                    } catch (_: Exception) {
                        // Extraction failed; the sections report the missing video.
                    }
                }
                throwIfInterrupted()

                val formatXmpSection = inspectFormatAndXmp(file, root, reader, tempVideoFile)
                throwIfInterrupted()
                val sefSection = inspectSefIntegrity(file, root, reader)
                throwIfInterrupted()
                // Built once (one full stsz/stco/stts read) and shared by the codec and A/V sync sections.
                val videoSampleTable = if (embeddedVideo != null && tempVideoFile != null && videoRoot != null) {
                    ByteReader.open(tempVideoFile).use { vReader -> buildVideoSampleTable(vReader, videoRoot) }
                } else {
                    null
                }
                throwIfInterrupted()
                val videoCodecSection = inspectVideoAndCodec(file, root, reader, embeddedVideo, tempVideoFile, videoRoot, videoSampleTable)
                throwIfInterrupted()
                val avSyncSection = inspectAvSyncAndTiming(file, root, reader, embeddedVideo, tempVideoFile, videoRoot, videoSampleTable)
                throwIfInterrupted()

                val detectedFormats = mutableListOf<String>()
                if (sefSection.status != CheckStatus.SKIP) detectedFormats.add("Samsung SEF")
                if (formatXmpSection.items.any { it.id.startsWith("xmp") && it.status == CheckStatus.PASS }) detectedFormats.add("Google XMP")
                // Only a real top-level mpvd box is HEIC mpvd; a JPEG's trailing video (EmbeddedVideoData) is not.
                if (root.children.any { it.type == "mpvd" }) detectedFormats.add("HEIC mpvd")
                val formatLabel = if (detectedFormats.isEmpty()) "Unknown Motion Photo" else detectedFormats.joinToString(" + ")

                val allSections = listOf(stillSection, formatXmpSection, sefSection, videoCodecSection, avSyncSection)
                val allStatuses = allSections.flatMap { it.items }.map { it.status }
                val overallStatus = when {
                    allStatuses.any { it == CheckStatus.FAIL } -> CheckStatus.FAIL
                    allStatuses.any { it == CheckStatus.WARN } -> CheckStatus.WARN
                    else -> CheckStatus.PASS
                }

                MotionPhotoInspectionReport(
                    formatLabel = formatLabel,
                    overallStatus = overallStatus,
                    stillImageSection = stillSection,
                    formatXmpSection = formatXmpSection,
                    sefSection = sefSection,
                    videoCodecSection = videoCodecSection,
                    avSyncSection = avSyncSection,
                )
            } finally {
                // deleteWhenReleased: on Windows an ffmpeg/ffprobe just killed by the cancel may still hold it.
                deleteWhenReleased(createdTemp)
            }
        }
    }

    // Full software decode of the extracted video: (exit code, ffmpeg's error output). (-1, "") when ffmpeg
    // could not be run or timed out. An interrupt (the inspector's window/tab closed) propagates as
    // InterruptedException -- readProcessOutputWithTimeout has already killed ffmpeg by then.
    internal fun runDecodeCheck(videoFile: File): Pair<Int, String> {
        val processBuilder = ProcessBuilder(
            FfmpegLocator.ffmpegPath(), "-v", "error",
            "-i", videoFile.absolutePath,
            "-f", "null", "-",
        ).redirectErrorStream(true)
        FfmpegLocator.configureEnvironment(processBuilder)

        var decodeExit = -1
        var decodeOutput = ""
        try {
            val process = processBuilder.start()
            val output = readProcessOutputWithTimeout(process, 30) { process.inputStream.bufferedReader().readText().trim() }
            if (output != null) {
                decodeExit = process.waitFor()
                decodeOutput = output
            }
        } catch (e: InterruptedException) {
            throw e
        } catch (_: Exception) {}
        return decodeExit to decodeOutput
    }

    // ffprobe stream durations of the extracted video: (video ms, audio ms), null where not reported.
    internal fun probeStreamDurations(videoFile: File): Pair<Long?, Long?> {
        var videoDurationMs: Long? = null
        var audioDurationMs: Long? = null
        try {
            val processBuilder = ProcessBuilder(
                FfmpegLocator.ffprobePath(), "-v", "error",
                "-show_entries", "stream=codec_type,duration",
                "-of", "csv=p=0",
                videoFile.absolutePath,
            ).redirectError(ProcessBuilder.Redirect.DISCARD)
            FfmpegLocator.configureEnvironment(processBuilder)
            val process = processBuilder.start()
            val output = readProcessOutputWithTimeout(process, 15) { process.inputStream.bufferedReader().readText().trim() }
            if (output != null) {
                // Format: audio,2.500000 \n video,2.520000
                output.lines().forEach { line ->
                    val parts = line.split(",")
                    if (parts.size >= 2) {
                        val type = parts[0].trim()
                        val durSec = parts[1].trim().toDoubleOrNull()
                        if (durSec != null) {
                            val durMs = (durSec * 1000).toLong()
                            if (type == "video" && videoDurationMs == null) videoDurationMs = durMs
                            if (type == "audio" && audioDurationMs == null) audioDurationMs = durMs
                        }
                    }
                }
            }
        } catch (e: InterruptedException) {
            throw e
        } catch (_: Exception) {}
        return videoDurationMs to audioDurationMs
    }

    // -----------------------------------------------------------------------------------------------------------------
    // 1. Still Image Specification Check
    // -----------------------------------------------------------------------------------------------------------------
    private fun inspectStillImage(file: File, root: BoxNode, reader: ByteReader): MotionPhotoInspectionSection {
        val items = mutableListOf<MotionPhotoInspectionItem>()
        val structure = try { ImageIntegrityChecker.check(root, reader) } catch (e: Exception) { null }

        if (structure == null) {
            items.add(
                MotionPhotoInspectionItem(
                    id = "still.structure",
                    status = CheckStatus.FAIL,
                    label = "정지 이미지 구조 해석",
                    expected = "유효한 JPEG 또는 HEIC 구조",
                    actual = "구조 파싱 실패",
                    detail = "정지 이미지의 메타데이터 및 마커를 파싱할 수 없습니다.",
                    remediation = "정지 이미지 컨테이너 헤더가 손상되었습니다. 파일 시작점(SOI/ftyp)과 필수 메타데이터 박스가 올바르게 인코딩되었는지 점검하십시오.",
                )
            )
            return MotionPhotoInspectionSection("still_image", "메인 정지 이미지 규격", "Main Still Image Spec", CheckStatus.FAIL, items)
        }

        // Format check
        val formatValid = structure.format in setOf("JPEG", "HEIF")
        items.add(
            MotionPhotoInspectionItem(
                id = "still.format",
                status = if (formatValid) CheckStatus.PASS else CheckStatus.FAIL,
                label = "이미지 포맷 규격",
                expected = "JPEG 또는 HEIF(HEIC)",
                actual = structure.format,
                detail = if (formatValid) "${structure.format} 컨테이너 규격을 준수합니다." else "지원되지 않는 이미지 포맷입니다: ${structure.format}",
                offset = 0L,
                length = 4L,
                remediation = if (formatValid) null else "구글/삼성 모션포토는 JPEG 또는 HEIF(HEIC) 컨테이너만 지원합니다. 표준 JPEG 또는 HEIC 포맷으로 인코딩하십시오.",
            )
        )

        // Structure check items from ImageIntegrityChecker
        for (item in structure.items) {
            if (item.id == "format") continue
            val hasIssue = item.status == CheckStatus.FAIL || item.status == CheckStatus.WARN
            items.add(
                MotionPhotoInspectionItem(
                    id = "still.${item.id}",
                    status = item.status,
                    label = item.title,
                    expected = "정상 마커/박스 배치",
                    actual = item.detail,
                    detail = item.detail,
                    offset = item.offset,
                    length = item.length,
                    remediation = if (hasIssue) "정지 이미지 마커 배치 및 헤더 규격을 확인하십시오 (${item.detail})." else null,
                )
            )
        }

        // Exif Dimension vs Bitstream Dimension
        val bitstreamW = structure.declaredWidth
        val bitstreamH = structure.declaredHeight
        var exifW: Int? = null
        var exifH: Int? = null

        fun findExifDimensions(node: BoxNode) {
            for (field in node.fields) {
                if (field.name == "ExifImageWidth" || (field.name == "ImageWidth" && node.type == "IFD0")) {
                    field.value.toIntOrNull()?.let { if (exifW == null) exifW = it }
                }
                if (field.name == "ExifImageLength" || (field.name == "ImageLength" && node.type == "IFD0")) {
                    field.value.toIntOrNull()?.let { if (exifH == null) exifH = it }
                }
            }
            node.children.forEach { findExifDimensions(it) }
        }
        findExifDimensions(root)

        if (bitstreamW != null && bitstreamH != null && exifW != null && exifH != null) {
            val matches = (bitstreamW == exifW && bitstreamH == exifH) || (bitstreamW == exifH && bitstreamH == exifW)
            items.add(
                MotionPhotoInspectionItem(
                    id = "still.dimensions",
                    status = if (matches) CheckStatus.PASS else CheckStatus.WARN,
                    label = "해상도 메타데이터 일치",
                    expected = "Exif (${exifW}x${exifH}) == 비트스트림 (${bitstreamW}x${bitstreamH})",
                    actual = "${bitstreamW}x${bitstreamH}",
                    detail = if (matches) "Exif 메타데이터와 실제 이미지 비트스트림 해상도가 일치합니다."
                    else "Exif 해상도(${exifW}x${exifH})와 실제 디코딩 해상도(${bitstreamW}x${bitstreamH})가 다릅니다.",
                    remediation = if (matches) null else "Exif 해상도 태그(ExifImageWidth/Length)와 실제 비트스트림 해상도가 불일치합니다. 이미지 크롭/회전 또는 리사이징 후 Exif 태그를 실제 비트스트림 해상도로 갱신하십시오.",
                )
            )
        } else if (bitstreamW != null && bitstreamH != null) {
            items.add(
                MotionPhotoInspectionItem(
                    id = "still.dimensions",
                    status = CheckStatus.PASS,
                    label = "비트스트림 해상도",
                    expected = "유효한 가로/세로 해상도",
                    actual = "${bitstreamW}x${bitstreamH}",
                    detail = "실제 이미지 해상도: ${bitstreamW}x${bitstreamH} px",
                )
            )
        }

        val sectionStatus = when {
            items.any { it.status == CheckStatus.FAIL } -> CheckStatus.FAIL
            items.any { it.status == CheckStatus.WARN } -> CheckStatus.WARN
            else -> CheckStatus.PASS
        }
        return MotionPhotoInspectionSection("still_image", "메인 정지 이미지 규격", "Main Still Image Spec", sectionStatus, items)
    }

    // -----------------------------------------------------------------------------------------------------------------
    // 2. Format & Primary XMP Check
    // -----------------------------------------------------------------------------------------------------------------
    private fun inspectFormatAndXmp(file: File, root: BoxNode, reader: ByteReader, videoFile: File?): MotionPhotoInspectionSection {
        val items = mutableListOf<MotionPhotoInspectionItem>()
        val xmpNode = findFirst(root) { it.fields.any { f -> f.name == "xmp" } }
        val xmpText = xmpNode?.fields?.find { f -> f.name == "xmp" }?.value
        val isHeicContainer = root.children.firstOrNull()?.type == "ftyp"
        val mpvdNode = findFirst(root) { it.type == "mpvd" || it.type == "EmbeddedVideoData" }
        val sefdNode = findFirst(root) { it.type == "sefd" }

        if (isHeicContainer) {
            if (mpvdNode != null) {
                val mpvdEnd = mpvdNode.offset + mpvdNode.size
                val boundsValid = mpvdNode.offset >= 0 && mpvdEnd <= file.length() &&
                    (sefdNode == null || mpvdNode.offset >= sefdNode.offset || mpvdEnd == sefdNode.offset)
                val ftypChild = mpvdNode.children.find { it.type == "ftyp" }
                val mpvdValid = boundsValid && ftypChild != null
                items.add(
                    MotionPhotoInspectionItem(
                        id = "heic.mpvd_box",
                        status = if (mpvdValid) CheckStatus.PASS else CheckStatus.FAIL,
                        label = "HEIC 임베디드 비디오 박스 (mpvd) [필수]",
                        expected = "최상위 mpvd 박스 존재 (내부 MP4 ftyp 포함, 경계 일치)",
                        actual = if (mpvdValid) "mpvd 존재 (offset=0x${mpvdNode.offset.toString(16)}, size=${mpvdNode.size} bytes)"
                        else "mpvd 경계 또는 내부 ftyp 오류 (offset=0x${mpvdNode.offset.toString(16)}, size=${mpvdNode.size} bytes)",
                        detail = if (mpvdValid) "HEIC 모션포토 필수 최상위 mpvd 박스(0x${mpvdNode.offset.toString(16)}..0x${mpvdEnd.toString(16)}, ${mpvdNode.size} bytes)가 임베디드 MP4 스트림을 올바르게 감싸고 있습니다."
                        else "mpvd 박스의 크기/경계가 sefd 시작점 또는 파일 크기와 일치하지 않거나 내부에 MP4 ftyp 헤더가 없습니다.",
                        offset = mpvdNode.offset,
                        length = mpvdNode.size,
                        remediation = if (mpvdValid) null else "HEIC 모션포토의 mpvd 박스 크기(8바이트 헤더 + MP4 바이트 길이)가 실제 MP4 스트림 및 뒤따르는 sefd 박스 시작 오프셋과 정확히 일치하도록 작성하십시오.",
                    )
                )
            } else {
                val exposedMoov = root.children.any { it.type == "moov" }
                items.add(
                    MotionPhotoInspectionItem(
                        id = "heic.mpvd_box",
                        status = CheckStatus.FAIL,
                        label = "HEIC 임베디드 비디오 박스 (mpvd) [필수]",
                        expected = "최상위 mpvd 박스 존재 ([4B size][\"mpvd\"][MP4 스트림])",
                        actual = if (exposedMoov) "mpvd 박스 누락 (MP4 ftyp/mdat/moov가 최상위에 노출됨)" else "mpvd 박스 누락",
                        detail = if (exposedMoov)
                            "HEIC 모션포토 컨테이너에 필수인 최상위 mpvd 박스(8바이트 헤더)가 누락되어 임베디드 동영상의 ftyp/mdat/moov 박스가 HEIC 최상위 레벨에 노출되었으며, 이로 인해 SEF MotionPhoto_Data의 video_offset이 +8바이트 밀리는 오류가 발생합니다."
                        else
                            "HEIC 모션포토 컨테이너에 필수인 최상위 mpvd 박스가 존재하지 않습니다.",
                        remediation = "HEIC 모션포토 규격에서는 임베디드 MP4 스트림을 반드시 최상위 'mpvd' 박스(8바이트 헤더: [4B BE size = mp4_length + 8][\"mpvd\"])로 감싸서 기록해야 합니다.",
                    )
                )
            }
        }

        if (xmpText == null) {
            // Check if it's HEIC mpvd or pure SEF
            val hasMpvd = mpvdNode != null
            val hasSef = sefdNode != null
            if (hasMpvd) {
                items.add(
                    MotionPhotoInspectionItem(
                        id = "xmp.heic_mpvd",
                        status = CheckStatus.PASS,
                        label = "HEIC 임베디드 비디오 박스",
                        expected = "mpvd 박스 존재",
                        actual = "mpvd 감지됨",
                        detail = "XMP 대신 ISO-BMFF mpvd 박스로 임베디드 동영상이 선언되었습니다.",
                    )
                )
            } else if (hasSef) {
                items.add(
                    MotionPhotoInspectionItem(
                        id = "xmp.sef_only",
                        status = CheckStatus.INFO,
                        label = "모션포토 XMP 메타데이터",
                        expected = "XMP (선택 사항)",
                        actual = "미포함 (삼성 SEF 전용)",
                        detail = "파일에 모션포토 XMP 태그가 없으며 삼성 SEF 방식으로만 구성되었습니다.",
                    )
                )
            } else {
                items.add(
                    MotionPhotoInspectionItem(
                        id = "xmp.missing",
                        status = CheckStatus.WARN,
                        label = "모션포토 XMP 메타데이터",
                        expected = "모션포토 XMP 태그",
                        actual = "XMP 없음",
                        detail = "모션포토 XMP 태그가 발견되지 않았습니다.",
                        remediation = "모션포토 표준 재생 및 갤러리 연동을 위해 Primary XMP에 구글 모션포토 네임스페이스(Container/Item)와 Container:Directory(Item:Semantic=\"MotionPhoto\")를 작성하십시오.",
                    )
                )
            }
            val status = if (items.any { it.status == CheckStatus.FAIL }) CheckStatus.FAIL
            else if (items.any { it.status == CheckStatus.WARN }) CheckStatus.WARN else CheckStatus.PASS
            return MotionPhotoInspectionSection("format_xmp", "모션포토 포맷 & Primary XMP", "Format & Primary XMP", status, items)
        }

        // XMP Syntax check
        val doc = try {
            parseXmpDocument(xmpText)
        } catch (e: Throwable) {
            items.add(
                MotionPhotoInspectionItem(
                    id = "xmp.syntax",
                    status = CheckStatus.FAIL,
                    label = "XMP 파싱 무결성",
                    expected = "유효한 XML/RDF 구조",
                    actual = "파싱 오류: ${e.message}",
                    detail = "Primary XMP 문서를 파싱할 수 없습니다.",
                    offset = xmpNode.offset,
                    length = xmpNode.size,
                    remediation = "Primary XMP XML 구문 오류입니다. XML 특수문자 이스케이프, 태그 닫힘, UTF-8 인코딩을 확인하십시오.",
                )
            )
            return MotionPhotoInspectionSection("format_xmp", "모션포토 포맷 & Primary XMP", "Format & Primary XMP", CheckStatus.FAIL, items)
        }

        items.add(
            MotionPhotoInspectionItem(
                id = "xmp.syntax",
                status = CheckStatus.PASS,
                label = "XMP XML/RDF 파싱",
                expected = "유효한 XML 구조",
                actual = "정상 파싱 완료",
                detail = "Primary XMP XML 구조가 올바르게 파싱되었습니다.",
                offset = xmpNode.offset,
                length = xmpNode.size,
            )
        )

        // 1. Google Motion Photo XML Namespaces Conformance
        val hasContainerNs = xmpText.contains("http://ns.google.com/photos/1.0/container/", ignoreCase = true)
        val hasItemNs = xmpText.contains("http://ns.google.com/photos/1.0/container/item/", ignoreCase = true)
        val hasGCameraNs = xmpText.contains("http://ns.google.com/photos/1.0/camera/", ignoreCase = true)
        val nsValid = hasContainerNs && hasItemNs
        items.add(
            MotionPhotoInspectionItem(
                id = "xmp.namespaces",
                status = if (nsValid || hasGCameraNs) CheckStatus.PASS else CheckStatus.WARN,
                label = "구글 모션포토 네임스페이스 규격",
                expected = "Container, Item, GCamera 네임스페이스",
                actual = "Container:$hasContainerNs, Item:$hasItemNs, GCamera:$hasGCameraNs",
                detail = if (nsValid) "구글 모션포토 표준 XML 네임스페이스가 올바르게 선언되어 있습니다."
                else "구글 모션포토 필수 네임스페이스(Container/Item)가 누락되었습니다.",
                remediation = if (nsValid) null else "구글 모션포토 표준 사양을 만족하려면 xmlns:Container=\"http://ns.google.com/photos/1.0/container/\" 및 xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\" 네임스페이스가 필수 선언되어야 합니다.",
            )
        )

        // 2. GCamera Top-level Metadata
        val gCameraMotionPhoto = findDescriptionAttribute(doc, "MotionPhoto")
        if (gCameraMotionPhoto != null) {
            val isMp1 = gCameraMotionPhoto == "1" || gCameraMotionPhoto.equals("true", ignoreCase = true)
            items.add(
                MotionPhotoInspectionItem(
                    id = "xmp.gcamera_motion_photo",
                    status = if (isMp1) CheckStatus.PASS else CheckStatus.WARN,
                    label = "GCamera:MotionPhoto 플래그",
                    expected = "1",
                    actual = gCameraMotionPhoto,
                    detail = if (isMp1) "GCamera:MotionPhoto 플래그가 '1'로 활성화되어 있습니다."
                    else "GCamera:MotionPhoto 값이 '1'이 아닙니다: $gCameraMotionPhoto",
                    remediation = if (isMp1) null else "구글 카메라 규격 및 갤러리 호환성을 위해 GCamera:MotionPhoto=\"1\"로 설정해야 합니다 (현재: $gCameraMotionPhoto).",
                )
            )
        }
        val gCameraVersion = findDescriptionAttribute(doc, "MotionPhotoVersion") ?: findDescriptionAttribute(doc, "MicroVideoVersion")
        if (gCameraVersion != null) {
            items.add(
                MotionPhotoInspectionItem(
                    id = "xmp.gcamera_version",
                    status = CheckStatus.PASS,
                    label = "GCamera 모션포토 버전",
                    expected = "버전 선언",
                    actual = "Version: $gCameraVersion",
                    detail = "모션포토 버전 정보가 올바르게 선언되어 있습니다.",
                )
            )
        }

        // 3. Container:Directory inspection (All items)
        val dirItems = parseAllDirectoryItems(doc)
        if (dirItems.isNotEmpty()) {
            items.add(
                MotionPhotoInspectionItem(
                    id = "xmp.directory_overview",
                    status = if (dirItems.size >= 2) CheckStatus.PASS else CheckStatus.WARN,
                    label = "Container:Directory 엔트리 구성 (${dirItems.size}개)",
                    expected = "최소 2개 이상 (Primary + MotionPhoto)",
                    actual = "${dirItems.size}개 아이템 (${dirItems.mapNotNull { it.semantic }.joinToString(", ")})",
                    detail = "디렉토리에 ${dirItems.size}개의 파일 청크가 선언되어 있습니다.",
                    remediation = if (dirItems.size >= 2) null else "Container:Directory에 최소 2개(첫 번째: Primary, 두 번째: MotionPhoto) 이상의 Item이 등록되어야 합니다.",
                )
            )

            // Inspect each item in Container:Directory
            for (dItem in dirItems) {
                val itemPrefix = "xmp.item.${dItem.index}"
                val isFirst = dItem.index == 1

                if (isFirst || dItem.semantic == "Primary") {
                    val isPrimarySemantic = dItem.semantic == "Primary"
                    val isImageMime = dItem.mime?.startsWith("image/") == true
                    val lengthValid = dItem.length == null || dItem.length == 0L
                    val primaryStatus = if (isPrimarySemantic && isImageMime && isFirst && lengthValid) CheckStatus.PASS else CheckStatus.WARN

                    // 1) Primary Item 기본 규격 (Semantic & MIME & 순서)
                    items.add(
                        MotionPhotoInspectionItem(
                            id = "$itemPrefix.primary",
                            status = primaryStatus,
                            label = "Item #${dItem.index} [Primary] 기본 규격 (Semantic & MIME)",
                            expected = "첫 번째 엔트리, Semantic=\"Primary\", Mime=\"image/*\"",
                            actual = "순서=#${dItem.index}, Semantic=\"${dItem.semantic}\", Mime=\"${dItem.mime}\"",
                            detail = if (primaryStatus == CheckStatus.PASS) "첫 번째 아이템이 메인 정지 이미지 규격을 완벽하게 만족합니다."
                            else "첫 번째 아이템의 Semantic이 'Primary'가 아니거나, 이미지 MIME이 아니거나, 순서가 1이 아닙니다.",
                            remediation = if (primaryStatus == CheckStatus.PASS) null else "구글 모션포토 스펙상 Directory 첫 번째 Item은 반드시 Item:Semantic=\"Primary\", Item:Mime=\"image/*\" 이어야 합니다.",
                        )
                    )

                    // 2) Primary Item Length 규격 (있으면 안 됨 / 생략 또는 0)
                    val rawLength = dItem.rawLength
                    val lengthStatus = if (lengthValid) CheckStatus.PASS else CheckStatus.WARN
                    items.add(
                        MotionPhotoInspectionItem(
                            id = "$itemPrefix.primary.length",
                            status = lengthStatus,
                            label = "Item #${dItem.index} [Primary] Length 규격 [기입 불가 / 생략 또는 0]",
                            expected = "있으면 안 됨 (생략 또는 0)",
                            actual = if (rawLength == null) "생략됨 (정상 규격 준수)" else if (dItem.length == 0L) "0 (정상 규격 준수)" else "$rawLength (규격 위반 - 있으면 안 됨)",
                            detail = if (lengthStatus == CheckStatus.PASS) "Primary 이미지는 파일 시작(오프셋 0)부터 포맷 자체 경계(EOI/Box)까지이므로 Length 속성이 없거나 0이어야 합니다 (있으면 안 됨)."
                            else "Primary 이미지에 양수 Length($rawLength)가 기입되어 있습니다. 구글 컨테이너 규격상 Primary에는 Length가 있으면 안 되거나 0이어야 합니다.",
                            remediation = if (lengthStatus == CheckStatus.PASS) null else "구글 스펙 준수를 위해 Primary Item의 Item:Length 속성을 제거하거나 0으로 설정하십시오.",
                        )
                    )

                    // 3) Primary Item Padding 규격 (선택)
                    val rawPadding = dItem.rawPadding
                    val pad = dItem.padding
                    val padValid = rawPadding == null || (pad != null && pad >= 0)
                    val padStatus = if (padValid) CheckStatus.PASS else CheckStatus.WARN
                    items.add(
                        MotionPhotoInspectionItem(
                            id = "$itemPrefix.primary.padding",
                            status = padStatus,
                            label = "Item #${dItem.index} [Primary] Padding 규격 [선택]",
                            expected = "선택 (생략 시 기본값 0, 기입 시 >= 0 정수)",
                            actual = if (rawPadding == null) "미기입 (기본값 0 간주 - 정상)" else "$rawPadding bytes",
                            detail = if (padStatus == CheckStatus.PASS) {
                                if (rawPadding == null) "선택 속성으로 생략되었습니다. 메인 이미지와 다음 아이템 사이 패딩은 0바이트로 처리됩니다."
                                else "선택 속성이 올바르게 기입되었습니다 (${pad}바이트 정렬 패딩)."
                            } else "선택 속성이지만 유효하지 않은 정수/음수 값($rawPadding)이 기입되었습니다.",
                            remediation = if (padStatus == CheckStatus.PASS) null else "Item:Padding은 0 이상의 정수로 기입하거나 생략하십시오.",
                        )
                    )

                } else if (dItem.semantic == "MotionPhoto") {
                    val isVideoMime = dItem.mime?.startsWith("video/") == true
                    items.add(
                        MotionPhotoInspectionItem(
                            id = "$itemPrefix.motion_photo",
                            status = if (isVideoMime) CheckStatus.PASS else CheckStatus.WARN,
                            label = "Item #${dItem.index} [MotionPhoto] 기본 규격 (Semantic & MIME)",
                            expected = "Semantic=\"MotionPhoto\", Mime=\"video/*\"",
                            actual = "Semantic=\"MotionPhoto\", Mime=\"${dItem.mime}\"",
                            detail = if (isVideoMime) "모션포토 임베디드 동영상 항목이 올바르게 선언되었습니다."
                            else "MotionPhoto 아이템의 MIME 타입이 비디오 형식(video/mp4 등)이 아닙니다: ${dItem.mime}",
                            remediation = if (isVideoMime) null else "MotionPhoto Item의 Item:Mime을 'video/mp4'로 지정하십시오.",
                        )
                    )

                    // Length check [필수]
                    val length = dItem.length ?: 0L
                    val lengthValid = length > 0 && length < root.size
                    items.add(
                        MotionPhotoInspectionItem(
                            id = "$itemPrefix.motion_photo.length",
                            status = if (lengthValid) CheckStatus.PASS else CheckStatus.FAIL,
                            label = "Item #${dItem.index} [MotionPhoto] Length 규격 [필수]",
                            expected = "필수 (> 0 양의 정수, 비디오 바이트 크기)",
                            actual = if (lengthValid) "$length bytes (정상)" else (dItem.rawLength ?: "미기입 (누락 - FAIL)"),
                            detail = if (lengthValid) "구글 모션포토 스펙상 필수 속성입니다. 비디오 시작 오프셋 역산(파일크기 - Length)을 위한 바이트 크기($length bytes)가 올바르게 기입되었습니다."
                            else "구글 모션포토 스펙상 MotionPhoto 아이템은 비디오 시작 오프셋 역산을 위해 Item:Length가 필수입니다.",
                            remediation = if (lengthValid) null else "모션포토 동영상 아이템의 Item:Length는 0보다 크고 파일 전체 크기보다 작아야 합니다. (실제 비디오 스트림 바이트 크기를 기입하십시오.)",
                        )
                    )

                    // Padding check [선택]
                    val rawPadding = dItem.rawPadding
                    val pad = dItem.padding
                    val padValid = rawPadding == null || (pad != null && pad >= 0)
                    val padStatus = if (padValid) CheckStatus.PASS else CheckStatus.WARN
                    items.add(
                        MotionPhotoInspectionItem(
                            id = "$itemPrefix.motion_photo.padding",
                            status = padStatus,
                            label = "Item #${dItem.index} [MotionPhoto] Padding 규격 [선택]",
                            expected = "선택 (생략 시 기본값 0, 기입 시 >= 0 정수)",
                            actual = if (rawPadding == null) "미기입 (기본값 0 간주 - 정상)" else "$rawPadding bytes",
                            detail = if (padStatus == CheckStatus.PASS) {
                                if (rawPadding == null) "선택 속성으로 생략되었습니다. 비디오 뒤 기본값 0바이트 패딩이 적용됩니다."
                                else "선택 속성이 올바르게 기입되었습니다 (${pad}바이트 패딩)."
                            } else "선택 속성이지만 유효하지 않은 정수/음수 값($rawPadding)이 기입되었습니다.",
                            remediation = if (padStatus == CheckStatus.PASS) null else "Item:Padding은 0 이상의 정수로 기입하거나 생략하십시오.",
                        )
                    )

                } else if (dItem.semantic == "GainMap") {
                    val isImageMime = dItem.mime?.startsWith("image/") == true
                    items.add(
                        MotionPhotoInspectionItem(
                            id = "$itemPrefix.GainMap",
                            status = if (isImageMime) CheckStatus.PASS else CheckStatus.WARN,
                            label = "Item #${dItem.index} [GainMap] 기본 규격 (Semantic & MIME)",
                            expected = "Semantic=\"GainMap\", Mime=\"image/*\"",
                            actual = "Semantic=\"GainMap\", Mime=\"${dItem.mime}\"",
                            detail = if (isImageMime) "Ultra HDR 게인맵 보조 아이템이 올바르게 선언되었습니다."
                            else "GainMap 아이템의 MIME 타입이 이미지 형식이 아닙니다: ${dItem.mime}",
                            remediation = if (isImageMime) null else "GainMap Item의 Item:Mime을 'image/jpeg'로 지정하십시오.",
                        )
                    )

                    // Length check [필수]
                    val length = dItem.length ?: 0L
                    val lengthValid = length > 0 && length < root.size
                    items.add(
                        MotionPhotoInspectionItem(
                            id = "$itemPrefix.GainMap.length",
                            status = if (lengthValid) CheckStatus.PASS else CheckStatus.FAIL,
                            label = "Item #${dItem.index} [GainMap] Length 규격 [필수]",
                            expected = "필수 (> 0 양의 정수, 게인맵 바이트 크기)",
                            actual = if (lengthValid) "$length bytes (정상)" else (dItem.rawLength ?: "미기입 (누락 - FAIL)"),
                            detail = if (lengthValid) "Ultra HDR 컨테이너 규격상 필수 속성입니다. 게인맵 이미지 데이터 분리를 위한 크기($length bytes)가 올바르게 기입되었습니다."
                            else "Ultra HDR GContainer 규격상 GainMap 아이템의 데이터 추출을 위해 Item:Length가 필수입니다.",
                            remediation = if (lengthValid) null else "GainMap Item의 Item:Length에 실제 게인맵 이미지 바이트 크기를 기입하십시오.",
                        )
                    )

                    // Padding check [선택]
                    val rawPadding = dItem.rawPadding
                    val pad = dItem.padding
                    val padValid = rawPadding == null || (pad != null && pad >= 0)
                    val padStatus = if (padValid) CheckStatus.PASS else CheckStatus.WARN
                    items.add(
                        MotionPhotoInspectionItem(
                            id = "$itemPrefix.GainMap.padding",
                            status = padStatus,
                            label = "Item #${dItem.index} [GainMap] Padding 규격 [선택]",
                            expected = "선택 (생략 시 기본값 0, 기입 시 >= 0 정수)",
                            actual = if (rawPadding == null) "미기입 (기본값 0 간주 - 정상)" else "$rawPadding bytes",
                            detail = if (padStatus == CheckStatus.PASS) {
                                if (rawPadding == null) "선택 속성으로 생략되었습니다. 게인맵 데이터 뒤 기본값 0바이트 패딩이 적용됩니다."
                                else "선택 속성이 올바르게 기입되었습니다 (${pad}바이트 패딩)."
                            } else "선택 속성이지만 유효하지 않은 정수/음수 값($rawPadding)이 기입되었습니다.",
                            remediation = if (padStatus == CheckStatus.PASS) null else "Item:Padding은 0 이상의 정수로 기입하거나 생략하십시오.",
                        )
                    )

                } else {
                    val semanticName = dItem.semantic ?: "other"
                    val length = dItem.length ?: 0L
                    val itemValid = !dItem.semantic.isNullOrBlank() && !dItem.mime.isNullOrBlank()
                    items.add(
                        MotionPhotoInspectionItem(
                            id = "$itemPrefix.$semanticName",
                            status = if (itemValid) CheckStatus.PASS else CheckStatus.WARN,
                            label = "Item #${dItem.index} [${dItem.semantic ?: "Unknown"}] 기본 규격 (Semantic & MIME)",
                            expected = "Semantic 선언, Mime 선언",
                            actual = "Semantic=\"${dItem.semantic}\", Mime=\"${dItem.mime}\"",
                            detail = if (itemValid) "보조 컨테이너 아이템(${dItem.semantic})의 정보가 선언되었습니다."
                            else "보조 컨테이너 아이템의 선언값(MIME 또는 Semantic)이 유효하지 않습니다.",
                            remediation = if (itemValid) null else "보조 컨테이너 아이템의 Item:Semantic과 Item:Mime 속성을 확인하고 유효한 값을 입력하십시오.",
                        )
                    )

                    // Length check [필수]
                    val lengthValid = length > 0 && length < root.size
                    items.add(
                        MotionPhotoInspectionItem(
                            id = "$itemPrefix.$semanticName.length",
                            status = if (lengthValid) CheckStatus.PASS else CheckStatus.FAIL,
                            label = "Item #${dItem.index} [${dItem.semantic ?: "Unknown"}] Length 규격 [필수]",
                            expected = "필수 (> 0 양의 정수)",
                            actual = if (lengthValid) "$length bytes (정상)" else (dItem.rawLength ?: "미기입 (누락 - FAIL)"),
                            detail = if (lengthValid) "보조 아이템 데이터 크기($length bytes)가 올바르게 기입되었습니다."
                            else "보조 컨테이너 아이템의 데이터 추출을 위해 Item:Length가 필수입니다.",
                            remediation = if (lengthValid) null else "Item:Length에 실제 데이터 바이트 크기를 기입하십시오.",
                        )
                    )

                    // Padding check [선택]
                    val rawPadding = dItem.rawPadding
                    val pad = dItem.padding
                    val padValid = rawPadding == null || (pad != null && pad >= 0)
                    items.add(
                        MotionPhotoInspectionItem(
                            id = "$itemPrefix.$semanticName.padding",
                            status = if (padValid) CheckStatus.PASS else CheckStatus.WARN,
                            label = "Item #${dItem.index} [${dItem.semantic ?: "Unknown"}] Padding 규격 [선택]",
                            expected = "선택 (생략 시 기본값 0, 기입 시 >= 0 정수)",
                            actual = if (rawPadding == null) "미기입 (기본값 0 간주 - 정상)" else "$rawPadding bytes",
                            detail = if (padValid) "선택 속성이 올바르게 처리되었습니다." else "유효하지 않은 패딩 값입니다: $rawPadding",
                        )
                    )
                }
            }

            // GainMap Item 미선언 시 스펙 안내 (GainMap이 Directory에 없는 경우)
            val hasGainMapItem = dirItems.any { it.semantic == "GainMap" }
            if (!hasGainMapItem) {
                val gainmapInfo = try { GainmapParser.findGainmapInfo(file, root) } catch (_: Exception) { null }
                val fileHasGainmap = gainmapInfo?.hasGainmap == true
                if (fileHasGainmap) {
                    items.add(
                        MotionPhotoInspectionItem(
                            id = "xmp.gainmap_spec_status",
                            status = CheckStatus.INFO,
                            label = "GainMap Item 규격 상태 [Ultra HDR]",
                            expected = "Ultra HDR 시 선언 권장 (Length 필수, Padding 선택)",
                            actual = "Directory 내 미선언 (${gainmapInfo?.formatType?.displayName ?: "MPF/ISO 메타데이터"} 사용 중)",
                            detail = "파일 내에 Ultra HDR/GainMap 데이터가 존재하지만 Container:Directory에는 GainMap 항목이 선언되지 않았습니다 (단독 MPF/ISO 방식). 구글 Ultra HDR 모션포토 규격을 완벽 준수하려면 GainMap Item 선언(Length 필수, Padding 선택)을 권장합니다.",
                            remediation = "구글 Ultra HDR GContainer 스펙 준수를 위해 Container:Directory에 GainMap Item을 추가하고 Item:Length=[게인맵크기]를 기입하십시오.",
                        )
                    )
                } else {
                    items.add(
                        MotionPhotoInspectionItem(
                            id = "xmp.gainmap_spec_status",
                            status = CheckStatus.INFO,
                            label = "GainMap Item 규격 상태 [SDR 모션포토]",
                            expected = "선택 [SDR 모션포토는 미선언이 정상 규격]",
                            actual = "미선언 (일반 SDR 파일 - 정상)",
                            detail = "일반 SDR 모션포토이므로 GainMap Item이 포함되지 않는 것이 정상 규격입니다. (구글 스펙상 GainMap Item은 Ultra HDR 모션포토일 때만 선언되며, 선언 시 Length는 필수, Padding은 선택입니다.)",
                        )
                    )
                }
            }

            // Secondary items cumulative length check
            val secondaryLengths = dirItems.filter { it.semantic != "Primary" }.mapNotNull { it.length }
            val totalSecondary = secondaryLengths.sum()
            val totalSecondaryValid = totalSecondary > 0 && totalSecondary < root.size
            items.add(
                MotionPhotoInspectionItem(
                    id = "xmp.secondary_total_length",
                    status = if (totalSecondaryValid) CheckStatus.PASS else CheckStatus.FAIL,
                    label = "보조 아이템 데이터 총합 검증",
                    expected = "0 < total < ${root.size} (파일 크기 이내)",
                    actual = "$totalSecondary bytes (${secondaryLengths.size}개 보조 스트림)",
                    detail = if (totalSecondaryValid) "모든 보조 스트림(동영상 및 게인맵 등)의 데이터 총합이 파일 크기 범위 내에 위치합니다."
                    else "보조 스트림의 데이터 총합이 파일 크기를 초과하거나 0입니다.",
                    remediation = if (totalSecondaryValid) null else "보조 스트림들의 Item:Length 총합($totalSecondary bytes)이 파일 크기(${root.size} bytes)를 초과하거나 0입니다. 각 보조 스트림의 길이를 재계산하십시오.",
                )
            )
        } else if (findMicroVideoOffset(doc) != null) {
            items.add(
                MotionPhotoInspectionItem(
                    id = "xmp.directory_overview",
                    status = CheckStatus.INFO,
                    label = "Container:Directory 스펙 (V2 권장)",
                    expected = "V2 Directory (Primary: Length 기입불가/Padding 선택, MotionPhoto: Length 필수/Padding 선택, GainMap: 상황별 필수)",
                    actual = "V1 레거시 (단일 태그 GCamera:MicroVideoOffset)",
                    detail = "구형 V1 스키마는 단일 태그로 비디오 길이만 지정하며, 다중 아이템(Primary/GainMap/MotionPhoto) 분리 및 개별 Length/Padding 속성을 지원하지 않습니다.",
                    remediation = "최신 모션포토 및 Ultra HDR 호환성을 위해 Container:Directory V2 구조로 XMP 메타데이터를 전환하십시오.",
                )
            )
        }

        val fromDir = findMotionPhotoInDirectory(doc)
        val microVideoOffset = findMicroVideoOffset(doc)

        if (fromDir == null && microVideoOffset == null) {
            items.add(
                MotionPhotoInspectionItem(
                    id = "xmp.schema",
                    status = CheckStatus.INFO,
                    label = "구글 모션포토 비디오 선언",
                    expected = "Container:Directory 또는 GCamera:MicroVideo",
                    actual = "미선언",
                    detail = "구글 모션포토 비디오 항목이 선언되지 않았습니다.",
                )
            )
        } else {
            val declaredLength = fromDir?.length ?: microVideoOffset!!
            val schemaName = if (fromDir != null) "Google V2 (Container:Directory)" else "Google V1 (MicroVideoOffset)"
            items.add(
                MotionPhotoInspectionItem(
                    id = "xmp.schema",
                    status = CheckStatus.PASS,
                    label = "감지된 모션포토 스키마",
                    expected = "Google Motion Photo 스펙",
                    actual = "$schemaName (선언 길이: $declaredLength bytes)",
                    detail = "스키마 규격을 만족하며, 비디오 데이터 길이가 선언되어 있습니다.",
                )
            )

            // Byte offset validation
            if (declaredLength <= 0 || declaredLength > root.size) {
                items.add(
                    MotionPhotoInspectionItem(
                        id = "xmp.length_bounds",
                        status = CheckStatus.FAIL,
                        label = "선언된 비디오 길이 유효성",
                        expected = "0 < length <= ${root.size}",
                        actual = "$declaredLength",
                        detail = "선언된 비디오 길이가 전체 파일 크기를 벗어납니다.",
                        remediation = "선언된 비디오 길이($declaredLength bytes)가 파일 크기(${root.size} bytes)를 벗어납니다. Muxer에서 'Item:Length = total_file_size - ftyp_offset' 공식을 재점검하십시오.",
                    )
                )
            } else {
                val approxStart = root.size - declaredLength
                val declaredHasFtyp = try { reader.readFourCC(approxStart + 4) == "ftyp" } catch (_: Exception) { false }
                if (declaredHasFtyp) {
                    items.add(
                        MotionPhotoInspectionItem(
                            id = "xmp.offset_match",
                            status = CheckStatus.PASS,
                            label = "비디오 시작 오프셋 정확도",
                            expected = "0x${approxStart.toString(16)} (파일 크기: ${root.size} - Length: $declaredLength)",
                            actual = "일치 (0x${approxStart.toString(16)})",
                            detail = "공식(파일 크기 ${root.size} - 선언 길이 $declaredLength = 0x${approxStart.toString(16)})으로 계산된 오프셋에서 정확히 MP4 ftyp 컨테이너를 확인했습니다.",
                            offset = approxStart,
                            length = 8L,
                        )
                    )
                } else {
                    val corrected = correctMp4StartOffset(reader, approxStart)
                    if (corrected != approxStart) {
                        val delta = corrected - approxStart
                        items.add(
                            MotionPhotoInspectionItem(
                                id = "xmp.offset_match",
                                status = CheckStatus.WARN,
                                label = "비디오 시작 오프셋 불일치",
                                expected = "0x${approxStart.toString(16)} (공식: ${root.size} - $declaredLength)",
                                actual = "0x${corrected.toString(16)} (${delta}바이트 편차)",
                                detail = "공식(파일 크기 ${root.size} - 선언 길이 $declaredLength = 0x${approxStart.toString(16)})으로 계산된 위치가 실제 ftyp 시작점(0x${corrected.toString(16)})과 ${delta}바이트 차이가 납니다.",
                                offset = corrected,
                                length = 8L,
                                remediation = "선언된 길이로 계산된 오프셋(0x${approxStart.toString(16)} = 파일 크기 ${root.size} - 선언 길이 $declaredLength)과 실제 ftyp 시작 오프셋(0x${corrected.toString(16)}) 간에 ${delta}바이트 오차가 있습니다. Muxer 완료 후 비디오 실제 시작 오프셋을 측정하여 'Item:Length = file_size - ftyp_offset'으로 XMP를 갱신하십시오.",
                            )
                        )
                    } else {
                        items.add(
                            MotionPhotoInspectionItem(
                                id = "xmp.offset_match",
                                status = CheckStatus.FAIL,
                                label = "비디오 시작 오프셋 확인",
                                expected = "0x${approxStart.toString(16)} (공식: ${root.size} - $declaredLength 근방 ftyp)",
                                actual = "ftyp 미발견",
                                detail = "공식(파일 크기 ${root.size} - 선언 길이 $declaredLength = 0x${approxStart.toString(16)}) 위치 근방에서 유효한 MP4 ftyp 헤더를 찾지 못했습니다.",
                                remediation = "선언된 위치에서 MP4 ftyp 헤더를 찾을 수 없습니다. 정지 이미지 파일 끝(EOF)에 MP4 비디오가 누락 없이 올바르게 결합되었는지 확인하십시오.",
                            )
                        )
                    }
                }
            }

            // MIME match
            fromDir?.mimeType?.let { declaredMime ->
                val majorBrand = try {
                    val approxStart = root.size - declaredLength
                    reader.readFourCC(approxStart + 8).trim()
                } catch (_: Exception) { null }
                val expectedMime = if (majorBrand == "qt") "video/quicktime" else "video/mp4"
                val match = declaredMime == expectedMime
                items.add(
                    MotionPhotoInspectionItem(
                        id = "xmp.mime",
                        status = if (match) CheckStatus.PASS else CheckStatus.WARN,
                        label = "Item:Mime 규격 일치",
                        expected = expectedMime,
                        actual = declaredMime,
                        detail = if (match) "선언된 MIME($declaredMime)이 실제 컨테이너 major_brand($majorBrand)와 일치합니다."
                        else "선언된 MIME($declaredMime)과 실제 컨테이너($majorBrand, 예상: $expectedMime)가 다릅니다.",
                        remediation = if (match) null else "선언된 MIME($declaredMime)과 실제 컨테이너 포맷($expectedMime)이 다릅니다. MP4 비디오는 'video/mp4'로 기입하십시오.",
                    )
                )
            }

            // Padding
            fromDir?.padding?.let { pStr ->
                val pad = pStr.toLongOrNull()
                val valid = pad != null && pad >= 0
                items.add(
                    MotionPhotoInspectionItem(
                        id = "xmp.padding",
                        status = if (valid) CheckStatus.PASS else CheckStatus.WARN,
                        label = "Item:Padding 값 유효성",
                        expected = ">= 0 정수",
                        actual = pStr,
                        detail = if (valid) "패딩 값이 올바르게 기입되었습니다 ($pad bytes)." else "유효하지 않은 패딩 값입니다: $pStr",
                        remediation = if (valid) null else "Item:Padding 값은 0 이상의 10진수 정수여야 합니다. 불필요한 공백이나 문자가 들어가지 않도록 점검하십시오.",
                    )
                )
            }
        }

        // PresentationTimestampUs
        val ptsUs = findPresentationTimestampUs(doc)
        if (ptsUs != null) {
            val valid = ptsUs >= 0
            items.add(
                MotionPhotoInspectionItem(
                    id = "xmp.presentation_timestamp",
                    status = if (valid) CheckStatus.PASS else CheckStatus.WARN,
                    label = "셔터 타임스탬프 (PresentationTimestampUs)",
                    expected = ">= 0 us",
                    actual = "${ptsUs} us (${String.format(Locale.US, "%.3f", ptsUs / 1_000_000.0)} s)",
                    detail = if (valid) "셔터 캡처 시점이 명시되어 있습니다." else "타임스탬프 값이 음수입니다: $ptsUs",
                    remediation = if (valid) null else "PresentationTimestampUs 값은 0 이상의 정수(마이크로초)여야 합니다.",
                )
            )
        }

        val status = when {
            items.any { it.status == CheckStatus.FAIL } -> CheckStatus.FAIL
            items.any { it.status == CheckStatus.WARN } -> CheckStatus.WARN
            else -> CheckStatus.PASS
        }
        return MotionPhotoInspectionSection("format_xmp", "모션포토 포맷 & Primary XMP", "Format & Primary XMP", status, items)
    }

    // -----------------------------------------------------------------------------------------------------------------
    // 3. Full SEF Integrity & Non-Motion SEF Validation
    // -----------------------------------------------------------------------------------------------------------------
    private fun inspectSefIntegrity(file: File, root: BoxNode, reader: ByteReader): MotionPhotoInspectionSection {
        val items = mutableListOf<MotionPhotoInspectionItem>()
        val sefdNode = findFirst(root) { it.type == "sefd" }

        if (sefdNode == null) {
            items.add(
                MotionPhotoInspectionItem(
                    id = "sef.not_present",
                    status = CheckStatus.SKIP,
                    label = "삼성 SEF 트레일러",
                    expected = "SEF 트레일러 (삼성 포맷인 경우)",
                    actual = "미포함",
                    detail = "파일에 삼성 SEF(Samsung Extension Format) 트레일러가 존재하지 않습니다.",
                )
            )
            return MotionPhotoInspectionSection("sef_integrity", "SEF 무결성 및 전체 엔트리", "Full SEF Integrity", CheckStatus.SKIP, items)
        }

        val sefReport = SefIntegrityAnalyzer.analyze(reader, sefdNode.offset, sefdNode.headerSize, sefdNode.size, file.length())

        // 1. Structural checks (SEFT, SEFH)
        for (check in sefReport.structuralChecks) {
            val isIssue = check.severity == SefIntegritySeverity.CRITICAL || check.severity == SefIntegritySeverity.WARNING
            items.add(
                MotionPhotoInspectionItem(
                    id = "sef.struct.${check.label}",
                    status = when (check.severity) {
                        SefIntegritySeverity.CRITICAL -> CheckStatus.FAIL
                        SefIntegritySeverity.WARNING -> CheckStatus.WARN
                        SefIntegritySeverity.INFO -> CheckStatus.INFO
                        else -> CheckStatus.PASS
                    },
                    label = "SEF 구조: ${check.label}",
                    expected = "정상 SEF 매직 및 헤더",
                    actual = check.detail,
                    detail = check.detail,
                    remediation = if (isIssue) "삼성 SEF 트레일러 구조가 손상되었습니다. 파일 끝의 SEFT 마커, 디렉토리 길이, SEFH 매직 넘버가 온전히 작성되었는지 확인하십시오." else null,
                )
            )
        }

        // 2. Directory Entry count
        val countStatus = when (sefReport.declaredEntryCountSeverity) {
            SefIntegritySeverity.CRITICAL -> CheckStatus.FAIL
            SefIntegritySeverity.WARNING -> CheckStatus.WARN
            else -> CheckStatus.PASS
        }
        items.add(
            MotionPhotoInspectionItem(
                id = "sef.entry_count",
                status = countStatus,
                label = "SEF 디렉토리 엔트리 수 일치",
                expected = "${sefReport.declaredEntryCount ?: "N/A"} 개",
                actual = "${sefReport.directoryEntries.size} 개",
                detail = if (countStatus == CheckStatus.PASS) "선언된 엔트리 수와 실제 파싱된 엔트리 수가 일치합니다."
                else "선언된 엔트리 수(${sefReport.declaredEntryCount})와 실제 수(${sefReport.directoryEntries.size})가 다릅니다.",
                remediation = if (countStatus == CheckStatus.PASS) null else "SEF 디렉토리 헤더의 엔트리 개수(${sefReport.declaredEntryCount})와 실제 엔트리 수(${sefReport.directoryEntries.size})가 다릅니다. 엔트리 추가/삭제 후 SEFH 헤더의 카운트를 동기화하십시오.",
            )
        )

        // 3. MotionPhoto_Data specific check
        val motionEntry = sefReport.directoryEntries.find { it.name == "MotionPhoto_Data" }
        if (motionEntry != null) {
            val nameSize = try {
                val b = reader.readBytes(motionEntry.computedDataStart + 4, 4)
                ((b[3].toLong() and 0xFF) shl 24) or
                    ((b[2].toLong() and 0xFF) shl 16) or
                    ((b[1].toLong() and 0xFF) shl 8) or
                    (b[0].toLong() and 0xFF)
            } catch (_: Exception) { 0L }
            val dataStart = motionEntry.computedDataStart + 8 + nameSize
            val dataLength = motionEntry.computedDataEnd - dataStart

            if (!motionEntry.inBounds || motionEntry.markerMatches != true || dataLength < 8L) {
                items.add(
                    MotionPhotoInspectionItem(
                        id = "sef.motion_photo_data",
                        status = CheckStatus.FAIL,
                        label = "MotionPhoto_Data 엔트리 및 MP4 페이로드",
                        expected = "유효한 SEF 블록 경계 및 MP4 페이로드/포인터",
                        actual = "블록 경계/마커 오류 또는 페이로드 길이 부족($dataLength bytes)",
                        detail = "오프셋: 0x${motionEntry.computedDataStart.toString(16)} .. 0x${motionEntry.computedDataEnd.toString(16)}, 선언 길이: ${motionEntry.declaredLength} bytes",
                        offset = motionEntry.computedDataStart,
                        length = motionEntry.declaredLength,
                        remediation = "SEF MotionPhoto_Data 엔트리의 SEFH 오프셋/길이가 파일 경계를 벗어나거나 블록 헤더 마커가 일치하지 않습니다.",
                    )
                )
            } else if (dataLength == 12L) {
                val ptr = validateMotionPhotoPointer(reader, dataStart, sefdNode.offset, file.length(), "MotionPhoto_Data")
                val ptrValid = ptr.severity == SefIntegritySeverity.PASS
                items.add(
                    MotionPhotoInspectionItem(
                        id = "sef.motion_photo_data",
                        status = if (ptrValid) CheckStatus.PASS else CheckStatus.FAIL,
                        label = "MotionPhoto_Data 포인터 및 실제 동영상 위치/길이 검증 (HEIC)",
                        expected = if (ptr.actualVideoOffset != null && ptr.actualVideoLength != null)
                            "offset=${ptr.actualVideoOffset} (0x${ptr.actualVideoOffset.toString(16)}), length=${ptr.actualVideoLength} bytes (태그=mpv2)"
                        else
                            "파일 앞(0) 기준 실제 MP4 시작 오프셋(ftyp) 및 정확한 동영상 길이 (태그=mpv2)",
                        actual = "offset=${ptr.declaredVideoOffset} (0x${ptr.declaredVideoOffset.toString(16)}), length=${ptr.declaredVideoLength} bytes (태그=${ptr.formatTag})",
                        detail = ptr.koDetail,
                        offset = ptr.declaredVideoOffset,
                        length = ptr.declaredVideoLength,
                        remediation = ptr.koRemediation,
                    )
                )
            } else {
                val walk = walkMp4TopLevelBoxes(reader, dataStart, motionEntry.computedDataEnd, stopAtZeroPadding = false)
                val inlineValid = walk.hasFtyp && walk.error == null && walk.totalLength == dataLength
                items.add(
                    MotionPhotoInspectionItem(
                        id = "sef.motion_photo_data",
                        status = if (inlineValid) CheckStatus.PASS else CheckStatus.FAIL,
                        label = "MotionPhoto_Data 엔트리 및 인라인 MP4 페이로드",
                        expected = "0x${dataStart.toString(16)} 위치의 유효한 인라인 MP4 (페이로드 길이: $dataLength bytes)",
                        actual = when {
                            inlineValid -> "유효한 MP4 (박스: ${walk.boxes.joinToString("+")}, 길이: $dataLength bytes)"
                            !walk.hasFtyp -> "MP4 매직(ftyp) 불일치"
                            else -> "MP4 박스 길이(${walk.totalLength} bytes) ≠ SEFH 선언 페이로드 길이($dataLength bytes)"
                        },
                        detail = if (inlineValid)
                            "SEF 블록: 0x${motionEntry.computedDataStart.toString(16)}..0x${motionEntry.computedDataEnd.toString(16)} (총 ${motionEntry.declaredLength} bytes), 인라인 MP4 페이로드: 0x${dataStart.toString(16)}..0x${motionEntry.computedDataEnd.toString(16)} ($dataLength bytes)"
                        else
                            "SEF 블록: 0x${motionEntry.computedDataStart.toString(16)}..0x${motionEntry.computedDataEnd.toString(16)}, 페이로드 시작: 0x${dataStart.toString(16)} ($dataLength bytes) — ${walk.error ?: "박스 총합(${walk.totalLength} bytes)과 선언 길이($dataLength bytes) 불일치"}",
                        offset = dataStart,
                        length = dataLength,
                        remediation = if (inlineValid) null else "SEF MotionPhoto_Data 페이로드(0x${dataStart.toString(16)}, 선언 길이: $dataLength bytes)에 유효한 MP4 ftyp가 없거나 내부 MP4 박스 크기 합계가 SEFH 디렉토리의 길이와 일치하지 않습니다. SEFH 엔트리 오프셋/길이와 데이터 복사 로직을 점검하십시오.",
                    )
                )
            }
        } else {
            items.add(
                MotionPhotoInspectionItem(
                    id = "sef.motion_photo_data",
                    status = CheckStatus.WARN,
                    label = "MotionPhoto_Data 엔트리 확인",
                    expected = "MotionPhoto_Data 키 존재",
                    actual = "미발견",
                    detail = "SEF 트레일러 내에 MotionPhoto_Data 엔트리가 없습니다.",
                    remediation = "SEF 트레일러 내에 MotionPhoto_Data 엔트리가 누락되었습니다. 삼성 모션포토 규격은 MotionPhoto_Data 서브파일 키를 필수로 요구합니다.",
                )
            )
        }

        // 4. All other SEF entries validation (bounds, markers)
        val nonMotionEntries = sefReport.directoryEntries.filter { it.name != "MotionPhoto_Data" }
        var allBoundsValid = true
        var allMarkersValid = true
        for (entry in nonMotionEntries) {
            if (!entry.inBounds) allBoundsValid = false
            if (entry.markerMatches != true) allMarkersValid = false
        }
        val nonMotionValid = allBoundsValid && allMarkersValid

        items.add(
            MotionPhotoInspectionItem(
                id = "sef.non_motion_entries",
                status = if (nonMotionValid) CheckStatus.PASS else CheckStatus.FAIL,
                label = "기타 전체 SEF 엔트리 무결성 (${nonMotionEntries.size}개)",
                expected = "모든 엔트리 인바운드 및 매직 일치",
                actual = if (nonMotionValid) "전체 정상 (${nonMotionEntries.size}개)"
                else "경계 또는 매직 오류 감지",
                detail = "파일 내 포함된 모든 SEF 키: " + nonMotionEntries.mapNotNull { it.name }.joinToString(", "),
                remediation = if (nonMotionValid) null else "일부 SEF 엔트리의 데이터 블록이 파일 범위를 벗어났거나 헤더 마커가 불일치합니다. 각 SEF 모듈의 길이 및 오프셋 직렬화 로직을 검증하십시오.",
            )
        )

        // 5. Overlap detection between SEF entries
        val sortedEntries = sefReport.directoryEntries.filter { it.inBounds }.sortedBy { it.computedDataStart }
        var hasOverlap = false
        val overlapDetails = mutableListOf<String>()
        for (i in 0 until sortedEntries.size - 1) {
            val curr = sortedEntries[i]
            val next = sortedEntries[i + 1]
            if (curr.computedDataEnd > next.computedDataStart) {
                hasOverlap = true
                overlapDetails.add("${curr.name ?: "Entry $i"}과(와) ${next.name ?: "Entry ${i+1}"} 간 ${curr.computedDataEnd - next.computedDataStart}바이트 중복")
            }
        }

        items.add(
            MotionPhotoInspectionItem(
                id = "sef.overlap_check",
                status = if (!hasOverlap) CheckStatus.PASS else CheckStatus.FAIL,
                label = "SEF 엔트리 데이터 간 중복(Overlap) 검사",
                expected = "엔트리 간 데이터 중복 없음",
                actual = if (!hasOverlap) "중복 없음" else "${overlapDetails.size}건 중복 발생",
                detail = if (!hasOverlap) "모든 SEF 데이터 블록이 고유한 영역에 독립 배치되어 있습니다."
                else overlapDetails.joinToString("; "),
                remediation = if (!hasOverlap) null else "SEF 엔트리 간 데이터 영역 겹침이 감지되었습니다 (${overlapDetails.joinToString("; ")}). 이전 엔트리의 끝점 뒤에 다음 엔트리가 위치하도록 오프셋을 재배치하십시오.",
            )
        )

        val status = when {
            items.any { it.status == CheckStatus.FAIL } -> CheckStatus.FAIL
            items.any { it.status == CheckStatus.WARN } -> CheckStatus.WARN
            else -> CheckStatus.PASS
        }
        return MotionPhotoInspectionSection("sef_integrity", "SEF 무결성 및 전체 엔트리", "Full SEF Integrity", status, items)
    }

    // -----------------------------------------------------------------------------------------------------------------
    // 4. Embedded Video & Codec Check
    // -----------------------------------------------------------------------------------------------------------------
    private fun inspectVideoAndCodec(
        file: File,
        root: BoxNode,
        reader: ByteReader,
        video: EmbeddedVideo?,
        tempVideoFile: File?,
        videoRoot: BoxNode?,
        sampleTable: VideoSampleTable?,
    ): MotionPhotoInspectionSection {
        val items = mutableListOf<MotionPhotoInspectionItem>()

        if (video == null || tempVideoFile == null || videoRoot == null) {
            items.add(
                MotionPhotoInspectionItem(
                    id = "video.missing",
                    status = CheckStatus.FAIL,
                    label = "임베디드 동영상 데이터 추출",
                    expected = "유효한 MP4 비디오 스트림",
                    actual = "추출 실패 또는 데이터 없음",
                    detail = "모션포토 임베디드 동영상을 추출할 수 없습니다.",
                    remediation = "임베디드 비디오 스트림을 추출할 수 없습니다. XMP Container:Directory, MicroVideoOffset, 또는 SEF MotionPhoto_Data가 파일에 올바르게 삽입되었는지 확인하십시오.",
                )
            )
            return MotionPhotoInspectionSection("video_codec", "임베디드 동영상 & 코덱", "Embedded MP4 & Codec", CheckStatus.FAIL, items)
        }

        // 1. Box container hierarchy
        val boxes = videoRoot.children.map { it.type }
        val hasFtyp = "ftyp" in boxes
        val hasMoov = "moov" in boxes
        val hasMdat = "mdat" in boxes
        val containerValid = hasFtyp && hasMoov && hasMdat

        items.add(
            MotionPhotoInspectionItem(
                id = "video.container_boxes",
                status = if (containerValid) CheckStatus.PASS else CheckStatus.FAIL,
                label = "MP4 컨테이너 박스 구조",
                expected = "ftyp, moov, mdat 포함",
                actual = boxes.joinToString(", "),
                detail = if (containerValid) "표준 ISO-BMFF 컨테이너 구조를 만족합니다." else "필수 박스가 누락되었습니다 (ftyp:$hasFtyp, moov:$hasMoov, mdat:$hasMdat)",
                remediation = if (containerValid) null else "MP4 비디오 컨테이너에 필수 박스(ftyp, moov, mdat)가 누락되었습니다. 비디오 인코더/먹서가 중간에 비정상 종료되지 않고 정상 플러시되었는지 확인하십시오.",
            )
        )

        // 2. Fast-start moov check
        val moovNode = videoRoot.children.find { it.type == "moov" }
        val mdatNode = videoRoot.children.find { it.type == "mdat" }
        if (moovNode != null && mdatNode != null) {
            val fastStart = moovNode.offset < mdatNode.offset
            items.add(
                MotionPhotoInspectionItem(
                    id = "video.fast_start",
                    status = CheckStatus.PASS,
                    label = "moov 헤더 배치 (Fast-start)",
                    expected = "moov 위치",
                    actual = if (fastStart) "시작부 (Fast-start / moov < mdat)" else "후미 (Trailer / mdat < moov)",
                    detail = if (fastStart) "moov 박스가 mdat 앞에 위치하여 빠른 스트리밍 재생에 유리합니다."
                    else "moov 박스가 비디오 끝부분에 위치합니다.",
                    remediation = if (fastStart) null else "moov 박스가 mdat 뒤에 위치하여 스트리밍 재생 시 지연이 발생할 수 있습니다. 'movflags +faststart' 옵션을 적용하여 moov 박스를 mdat 앞으로 재배치하는 것을 권장합니다.",
                )
            )
        }

        // 3. Video Sample Table & Track Details
        if (sampleTable != null) {
            val sampleCount = sampleTable.samples.size
            val durationSec = videoSampleTableDurationSec(sampleTable)

            items.add(
                MotionPhotoInspectionItem(
                    id = "video.codec_details",
                    status = CheckStatus.PASS,
                    label = "비디오 코덱 및 스트림 규격",
                    expected = "H.264 / HEVC / AV1",
                    actual = "${sampleTable.codec.name} (${sampleCount} 프레임, timescale: ${sampleTable.timescale})",
                    detail = "코덱: ${sampleTable.codec.name}, 총 샘플: $sampleCount 개, 길이: ${String.format(Locale.US, "%.3f", durationSec)} s",
                )
            )
        }

        // 4. Software decoding test via ffmpeg
        val (decodeExit, decodeOutput) = runDecodeCheck(tempVideoFile)

        val decodeSuccess = decodeExit == 0 && decodeOutput.isEmpty()
        items.add(
            MotionPhotoInspectionItem(
                id = "video.decode_integrity",
                status = if (decodeSuccess) CheckStatus.PASS else CheckStatus.FAIL,
                label = "동영상 소프트웨어 디코딩 검증",
                expected = "오류 없는 전체 디코딩 (exit: 0)",
                actual = if (decodeSuccess) "정상 디코딩 완료" else "디코딩 오류 (exit: $decodeExit)",
                detail = if (decodeSuccess) "ffmpeg 전체 프레임 디코딩을 무결하게 통과했습니다."
                else "디코더 에러 로그: ${decodeOutput.take(300)}",
                remediation = if (decodeSuccess) null else "ffmpeg 소프트웨어 디코딩 실패입니다. 비디오 인코딩 비트스트림 오류(NAL 헤더 손상, 키프레임 누락, 비호환 프로파일 등)가 원인입니다. 인코더 파라미터 설정을 확인하십시오.",
            )
        )

        val status = when {
            items.any { it.status == CheckStatus.FAIL } -> CheckStatus.FAIL
            items.any { it.status == CheckStatus.WARN } -> CheckStatus.WARN
            else -> CheckStatus.PASS
        }
        return MotionPhotoInspectionSection("video_codec", "임베디드 동영상 & 코덱", "Embedded MP4 & Codec", status, items)
    }

    // -----------------------------------------------------------------------------------------------------------------
    // 5. A/V Sync & Timing Check
    // -----------------------------------------------------------------------------------------------------------------
    private fun inspectAvSyncAndTiming(
        file: File,
        root: BoxNode,
        reader: ByteReader,
        video: EmbeddedVideo?,
        tempVideoFile: File?,
        videoRoot: BoxNode?,
        sampleTable: VideoSampleTable?,
    ): MotionPhotoInspectionSection {
        val items = mutableListOf<MotionPhotoInspectionItem>()

        if (video == null || tempVideoFile == null || videoRoot == null) {
            items.add(
                MotionPhotoInspectionItem(
                    id = "sync.no_video",
                    status = CheckStatus.SKIP,
                    label = "A/V 싱크 분석",
                    expected = "유효한 비디오",
                    actual = "비디오 없음",
                    detail = "임베디드 비디오가 없어 A/V 싱크 분석을 건너뜁니다.",
                )
            )
            return MotionPhotoInspectionSection("av_sync", "A/V 길이 편차 & 셔터 타이밍", "A/V Sync & Timing", CheckStatus.SKIP, items)
        }

        // Probing streams via ffprobe
        val probed = probeStreamDurations(tempVideoFile)
        var videoDurationMs: Long? = probed.first
        val audioDurationMs: Long? = probed.second

        // Fallback to sample table duration if ffprobe didn't report stream duration
        if (videoDurationMs == null) {
            if (sampleTable != null && sampleTable.samples.isNotEmpty()) {
                videoDurationMs = (videoSampleTableDurationSec(sampleTable) * 1000).toLong()
            }
        }

        // 1. A/V Duration discrepancy
        if (videoDurationMs != null && audioDurationMs != null) {
            val deltaMs = abs(videoDurationMs!! - audioDurationMs!!)
            val status = when {
                deltaMs <= 100 -> CheckStatus.PASS
                deltaMs <= 300 -> CheckStatus.WARN
                else -> CheckStatus.FAIL
            }
            items.add(
                MotionPhotoInspectionItem(
                    id = "sync.duration_delta",
                    status = status,
                    label = "비디오 / 오디오 길이 편차 (A/V Delta)",
                    expected = "편차 <= 100 ms",
                    actual = "편차: ${deltaMs} ms (비디오: ${videoDurationMs}ms, 오디오: ${audioDurationMs}ms)",
                    detail = when (status) {
                        CheckStatus.PASS -> "비디오와 오디오 스트림의 재생 길이가 완벽하게 일치합니다."
                        CheckStatus.WARN -> "비디오와 오디오의 재생 길이에 ${deltaMs}ms의 미세 편차가 있습니다."
                        else -> "비디오와 오디오 길이 편차가 ${deltaMs}ms로 큽니다. 스트림 일부가 트렁케이션되었을 수 있습니다."
                    },
                    remediation = if (status == CheckStatus.PASS) null else "비디오와 오디오 재생 길이 편차(${deltaMs}ms)가 큽니다. 오디오 인코더 버퍼 플러시 누락 또는 비디오 녹화 중단 시점의 타임스탬프 불일치가 원인일 수 있습니다. 녹화 종료 시 두 트랙의 길이를 일치시키거나 트림(trim)하십시오.",
                )
            )
        } else if (videoDurationMs != null) {
            items.add(
                MotionPhotoInspectionItem(
                    id = "sync.no_audio_stream",
                    status = CheckStatus.INFO,
                    label = "오디오 스트림 상태",
                    expected = "오디오 스트림 (선택)",
                    actual = "오디오 없음 (비디오만 존재, 길이: ${videoDurationMs}ms)",
                    detail = "임베디드 동영상에 오디오 트랙이 포함되어 있지 않습니다.",
                )
            )
        }

        // 2. Shutter PresentationTimestampUs match
        val xmpDoc = try {
            findFirst(root) { it.fields.any { f -> f.name == "xmp" } }?.fields?.find { it.name == "xmp" }?.value?.let { parseXmpDocument(it) }
        } catch (_: Exception) { null }
        val presentationTimestampUs = xmpDoc?.let { findPresentationTimestampUs(it) }

        if (presentationTimestampUs != null && videoDurationMs != null) {
            val videoDurUs = videoDurationMs!! * 1000L
            val inRange = presentationTimestampUs in 0..videoDurUs
            items.add(
                MotionPhotoInspectionItem(
                    id = "sync.shutter_bounds",
                    status = if (inRange) CheckStatus.PASS else CheckStatus.WARN,
                    label = "셔터 시점 비디오 구간 내 포함 여부",
                    expected = "0 .. ${videoDurUs} us (동영상 전체 구간)",
                    actual = "${presentationTimestampUs} us (${String.format(Locale.US, "%.3f", presentationTimestampUs / 1_000_000.0)} s)",
                    detail = if (inRange) "셔터 캡처 시점이 동영상 재생 시간 내에 위치합니다."
                    else "셔터 시점(${presentationTimestampUs}us)이 동영상 총 길이(${videoDurUs}us)를 초과합니다.",
                    remediation = if (inRange) null else "셔터 캡처 시점(PresentationTimestampUs: ${presentationTimestampUs}us)이 비디오 재생 시간(0 ~ ${videoDurUs}us)을 벗어납니다. 비디오 시작 시점 기준(0us)의 상대 시간으로 PTS를 재계산하십시오.",
                )
            )

            // Frame Match
            val match = if (sampleTable != null) matchFrameToTimestamp(sampleTable, presentationTimestampUs) else null
            if (match != null) {
                items.add(
                    MotionPhotoInspectionItem(
                        id = "sync.frame_match",
                        status = CheckStatus.PASS,
                        label = "정지 이미지 ↔ 비디오 프레임 매칭",
                        expected = "가장 근접한 비디오 프레임",
                        actual = "프레임 #${match.matchedFrameNumber} / ${match.totalSamples} (PTS: ${String.format(Locale.US, "%.4f", match.matchedPtsUs / 1_000_000.0)} s)",
                        detail = "시간 오차: ${match.deltaUs} us, 정렬 상태: ${match.alignmentVerdict.name}",
                    )
                )
            }
        }

        val status = when {
            items.any { it.status == CheckStatus.FAIL } -> CheckStatus.FAIL
            items.any { it.status == CheckStatus.WARN } -> CheckStatus.WARN
            else -> CheckStatus.PASS
        }
        return MotionPhotoInspectionSection("av_sync", "A/V 길이 편차 & 셔터 타이밍", "A/V Sync & Timing", status, items)
    }

    private data class XmpContainerItem(
        val index: Int,
        val semantic: String?,
        val mime: String?,
        val length: Long?,
        val padding: Long?,
        val rawLength: String?,
        val rawPadding: String?,
    )

    private fun parseAllDirectoryItems(document: org.w3c.dom.Document): List<XmpContainerItem> {
        val items = mutableListOf<XmpContainerItem>()
        val liList = document.getElementsByTagNameNS("*", "li")
        for (i in 0 until liList.length) {
            val li = liList.item(i) as? org.w3c.dom.Element ?: continue
            val semantic = com.multiviewer.parser.findPropertyValue(li, "Semantic")
            val mime = com.multiviewer.parser.findPropertyValue(li, "Mime")
            val rawLength = com.multiviewer.parser.findPropertyValue(li, "Length")
            val rawPadding = com.multiviewer.parser.findPropertyValue(li, "Padding")
            if (semantic != null || mime != null || rawLength != null) {
                items.add(
                    XmpContainerItem(
                        index = items.size + 1,
                        semantic = semantic,
                        mime = mime,
                        length = rawLength?.toLongOrNull(),
                        padding = rawPadding?.toLongOrNull(),
                        rawLength = rawLength,
                        rawPadding = rawPadding,
                    )
                )
            }
        }
        return items
    }

    private fun findDescriptionAttribute(document: org.w3c.dom.Document, localName: String): String? {
        val descriptions = document.getElementsByTagNameNS("*", "Description")
        for (i in 0 until descriptions.length) {
            val desc = descriptions.item(i) as? org.w3c.dom.Element ?: continue
            com.multiviewer.parser.findPropertyValue(desc, localName)?.let { return it }
        }
        return null
    }
}
