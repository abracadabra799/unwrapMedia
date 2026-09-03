package com.multiviewer.cli

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.WarningEntry
import com.multiviewer.parser.buildMediaSummary
import com.multiviewer.parser.findFirst
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

object AiDiagnosticPromptBuilder {

    fun determineSeverity(type: String, message: String): String {
        val lower = message.lowercase(Locale.US)
        return when {
            lower.contains("missing") || lower.contains("corrupt") || lower.contains("invalid length") ||
                lower.contains("overflow") || lower.contains("out of bounds") || lower.contains("crash") ||
                lower.contains("error") -> "CRITICAL"
            lower.contains("mismatch") || lower.contains("unsupported") || lower.contains("non-standard") ||
                lower.contains("duplicate") || lower.contains("trailing") || lower.contains("unaligned") ||
                lower.contains("warning") -> "WARNING"
            else -> "INFO"
        }
    }

    fun buildPrompt(file: File, root: BoxNode?, warnings: List<WarningEntry>): String {
        val fileSizeStr = formatFileSize(file.length())
        val extension = file.extension.lowercase(Locale.US)
        val ftyp = root?.let { findFirst(it) { node -> node.type == "ftyp" } }
        val majorBrand = ftyp?.fields?.find { it.name == "major_brand" }?.value?.trim() ?: "unknown"
        val compatibleBrands = ftyp?.fields?.find { it.name == "compatible_brands" }?.value?.trim() ?: ""

        val (roleTitle, specs, targetEnvironments) = getFormatDomainContext(extension, majorBrand)

        val sb = StringBuilder()
        sb.appendLine("당신은 10년 차 $roleTitle 멀티미디어 바이너리 분석 및 컨테이너 전문 엔지니어입니다.")
        sb.appendLine()
        sb.appendLine("다음은 unwrapMedia 멀티미디어 분석기가 파싱한 미디어 파일의 종합 메타데이터 및 구조적 결함 진단 리포트입니다.")
        sb.appendLine("제공된 모든 메타데이터와 결함 위치의 상세 필드, 연관 테이블, 헥사 바이트를 바탕으로 문제의 원인과 해결 방안을 정밀 진단해 주세요.")
        sb.appendLine()

        // 1. 종합 미디어 프로필 (Media Profile & Stream Summary)
        sb.appendLine("### [1. 분석 대상 미디어 종합 프로필]")
        sb.appendLine("- 파일명: ${file.name}")
        sb.appendLine("- 확장자: .$extension | 파일 크기: $fileSizeStr (${file.length()} bytes)")
        if (majorBrand != "unknown") {
            sb.appendLine("- Major Brand: $majorBrand")
        }
        if (compatibleBrands.isNotEmpty()) {
            sb.appendLine("- Compatible Brands: $compatibleBrands")
        }

        // MediaSummaryBuilder 연동으로 추출한 트랙 및 포맷 메타데이터 요약
        if (root != null) {
            val mediaSummary = try { buildMediaSummary(root, file) } catch (_: Exception) { null }
            if (mediaSummary != null && mediaSummary.sections.isNotEmpty()) {
                sb.appendLine("- 카테고리: ${mediaSummary.category}")
                mediaSummary.sections.forEach { section ->
                    val fieldsStr = section.fields.joinToString(", ") { "${it.label}: ${it.value}" }
                    if (fieldsStr.isNotEmpty()) {
                        sb.appendLine("  • [${section.title}] $fieldsStr")
                    }
                }
            }

            // Top-level 컨테이너 구조 요약 (Top-Level Box Map)
            val topLevelBoxes = root.children
            if (topLevelBoxes.isNotEmpty()) {
                val boxMapStr = topLevelBoxes.joinToString(", ") { box ->
                    "${box.type} (0x${box.offset.toString(16).uppercase()}, ${formatFileSize(box.size)})"
                }
                sb.appendLine("- 최상위 컨테이너 구조: [$boxMapStr]")

                val moovIndex = topLevelBoxes.indexOfFirst { it.type == "moov" }
                val mdatIndex = topLevelBoxes.indexOfFirst { it.type == "mdat" }
                if (moovIndex >= 0 && mdatIndex >= 0) {
                    if (moovIndex < mdatIndex) {
                        sb.appendLine("  (참고: moov가 mdat보다 앞에 위치하여 Fast-Start/웹 스트리밍에 최적화된 상태)")
                    } else {
                        sb.appendLine("  (참고: moov가 mdat 뒤에 위치하여 전체 다운로드 전까지 재생 시작이 지연될 수 있음)")
                    }
                }
            }
        }
        sb.appendLine()

        if (warnings.isEmpty()) {
            sb.appendLine("### [2. unwrapMedia 바이너리 검증 결과]")
            sb.appendLine("✓ 구조적 결함이나 스펙 위반 경고가 감지되지 않은 정상 파일입니다.")
            sb.appendLine()
            sb.appendLine("### [3. 표준 규격 및 프레임워크 컨텍스트]")
            sb.appendLine("- 관련 표준 스펙: $specs")
            sb.appendLine("- 주요 타깃 환경: $targetEnvironments")
            sb.appendLine()
            sb.appendLine("### [4. 요청 사항]")
            sb.appendLine("1. 이 미디어 컨테이너의 표준 호환성(Web Streaming, Android/iOS 호환성 등)을 더욱 높이기 위한 최적화 방안(예: faststart, moov 위치, 청크 정렬 등)을 제안해 주세요.")
            sb.appendLine("2. 모바일 기기(Android MediaCodec/Apple AVFoundation) 및 웹 브라우저에서의 재생/디코딩 효율을 높이기 위한 인코딩/먹싱 권장 설정을 설명해 주세요.")
            return sb.toString()
        }

        // 2. 구조적 결함 및 경고 상세 데이터 (Facts & Detailed Context)
        sb.appendLine("### [2. 감지된 구조적 결함 및 경고 (Facts Detected by UnwrapMedia)]")
        sb.appendLine("총 ${warnings.size}건의 결함 또는 스펙 위반이 감지되었습니다.")
        sb.appendLine()
        sb.appendLine("```json")
        sb.appendLine("[")
        warnings.forEachIndexed { index, w ->
            val severity = determineSeverity(w.node.type, w.warning)
            val comma = if (index < warnings.size - 1) "," else ""
            val pathStr = root?.let { findNodePath(it, w.node) }?.joinToString(" > ") ?: w.node.type
            sb.appendLine("  {")
            sb.appendLine("    \"box\": \"${w.node.type}\",")
            sb.appendLine("    \"path\": \"$pathStr\",")
            sb.appendLine("    \"offset\": ${w.node.offset},")
            sb.appendLine("    \"size\": ${w.node.size},")
            sb.appendLine("    \"severity\": \"$severity\",")
            sb.appendLine("    \"warning\": \"${escapeJson(w.warning)}\"")
            sb.appendLine("  }$comma")
        }
        sb.appendLine("]")
        sb.appendLine("```")
        sb.appendLine()

        sb.appendLine("#### [결함별 상세 내부 필드 및 바이너리 컨텍스트]")
        warnings.forEachIndexed { index, w ->
            val severity = determineSeverity(w.node.type, w.warning)
            val pathStr = root?.let { findNodePath(it, w.node) }?.joinToString(" > ") ?: w.node.type
            val hexOffset = "0x" + w.node.offset.toString(16).uppercase()

            sb.appendLine("• **결함 #${index + 1}: [${w.node.type}]** (Severity: $severity)")
            sb.appendLine("  - **경로(Path)**: `$pathStr`")
            sb.appendLine("  - **오프셋 / 크기**: $hexOffset (${w.node.offset} bytes) | 박스 크기: ${w.node.size} bytes (헤더: ${w.node.headerSize}B)")
            sb.appendLine("  - **경고 내용**: `${w.warning}`")

            // 해당 노드의 파싱된 필드 데이터 (Box Fields)
            if (w.node.fields.isNotEmpty()) {
                sb.appendLine("  - **파싱된 내부 필드 데이터**:")
                sb.appendLine("    ```")
                w.node.fields.forEach { f ->
                    sb.appendLine("    ${f.name} = ${f.value}  (offset: 0x${f.offset.toString(16).uppercase()}, len: ${f.length}B)")
                }
                sb.appendLine("    ```")
            }

            // 테이블 데이터가 존재하는 경우
            w.node.table?.let { tbl ->
                sb.appendLine("  - **테이블 정보**: 컬럼 [${tbl.columns.joinToString(", ")}], 등록된 엔트리 수: ${tbl.entryCount}")
            }

            // 부모 트랙이 있는 경우 샘플 테이블 간 교차 검증 정보 (Correlated Sample Table Context)
            if (root != null) {
                val parentTrak = findParentTrack(root, w.node)
                if (parentTrak != null) {
                    val trackContext = buildTrackSampleTableContext(parentTrak)
                    if (trackContext.isNotEmpty()) {
                        sb.appendLine("  - **동일 트랙 내 연관 샘플 테이블 교차 검증 데이터**:")
                        trackContext.forEach { (key, value) ->
                            sb.appendLine("    • $key: $value")
                        }
                    }
                }
            }

            // 실제 바이너리 헥사 덤프 스니펫 (Hex Dump Snippet)
            val hexDump = readHexDumpSnippet(file, w.node.offset, minOf(32, w.node.size.coerceAtLeast(16).toInt()))
            if (hexDump != null) {
                sb.appendLine("  - **원본 바이트 헥사 덤프**:")
                sb.appendLine("    ```")
                sb.appendLine("    $hexDump")
                sb.appendLine("    ```")
            }
            sb.appendLine()
        }

        // 3. 표준 규격 및 프레임워크 컨텍스트
        sb.appendLine("### [3. 표준 규격 및 프레임워크 컨텍스트]")
        sb.appendLine("- 관련 표준 스펙: $specs")
        sb.appendLine("- 주요 타깃 환경: $targetEnvironments")
        sb.appendLine()

        // 4. 구체적 AI 분석 요청 사항
        sb.appendLine("### [4. 요청 사항 (Analysis Requested from AI)]")
        sb.appendLine("위의 상세 메타데이터, 파싱된 필드 값, 연관 테이블 교차 검증 수치, 원본 바이트 덤프를 바탕으로 다음 사항들을 전문적으로 분석해 주세요:")
        sb.appendLine()
        sb.appendLine("1. **근본 원인 및 규격 위반 메커니즘 분석 (Root Cause)**:")
        sb.appendLine("   - 제공된 필드 값과 수치 불일치(예: sample_count, chunk_count, time_to_sample 등)를 근거로, 파일 생성/인코딩/먹싱(Muxing) 과정 중 어느 단계에서 왜 이 결함이 발생했는지 추론해 주세요.")
        sb.appendLine("   - 녹화 중 비정상 종료(Crash/Power off), 불완전한 헤더 Finalize, 인코더 소프트웨어 버그, 비표준 확장 쓰기 등 가능성이 높은 원인을 짚어주세요.")
        sb.appendLine()
        sb.appendLine("2. **플랫폼별 파서/디코더 영향도 (Platform Impact)**:")
        sb.appendLine("   - 이 결함이 $targetEnvironments 환경(Android Stagefright/MediaCodec, Apple AVFoundation/ImageIO, Chromium, FFmpeg 등)에서 재생될 때 발생할 구체적인 문제(재생 불가 크래시, Seeking 불가, A/V 싱크 불일치, 프레임 드랍, 또는 무시하고 재생 등)를 기술해 주세요.")
        sb.appendLine()
        sb.appendLine("3. **무손실 복구 가이드 (Lossless Quick Fix)**:")
        sb.appendLine("   - 원본 비디오/오디오 스트림을 재인코딩하지 않고 화질 손실 없이 컨테이너만 복구할 수 있는 **구체적인 FFmpeg 리먹싱 커맨드**(`-c copy`, `-movflags`, `-fflags` 등) 또는 바이너리 패치 방안을 제시해 주세요.")
        sb.appendLine()
        sb.appendLine("4. **소프트웨어 파이프라인 수정 가이드 (Prevention)**:")
        sb.appendLine("   - 이 미디어 파일을 생성하는 인코더/먹서 소프트웨어 개발자가 동일한 결함을 방지하기 위해 준수해야 할 표준 스펙 조항과 파이프라인 방어 로직을 제안해 주세요.")

        return sb.toString()
    }

    /**
     * 노드의 최상위 루트로부터의 계층 경로를 탐색하여 친절한 라벨 리스트를 반환합니다.
     * 예: ["root", "moov", "trak(#1, vide)", "mdia", "minf", "stbl", "stsz"]
     */
    fun findNodePath(current: BoxNode, target: BoxNode, currentPath: List<String> = emptyList()): List<String>? {
        val nodeLabel = describeNodeForPath(current)
        val nextPath = currentPath + nodeLabel
        if (current === target || (current.type == target.type && current.offset == target.offset && current.size == target.size)) {
            return nextPath
        }
        for (child in current.children) {
            val found = findNodePath(child, target, nextPath)
            if (found != null) return found
        }
        return null
    }

    private fun describeNodeForPath(node: BoxNode): String {
        return when (node.type) {
            "trak" -> {
                val hdlr = findFirst(node) { it.type == "hdlr" }
                val hType = hdlr?.fields?.find { it.name == "handler_type" }?.value?.trim()
                val tkhd = findFirst(node) { it.type == "tkhd" }
                val trackId = tkhd?.fields?.find { it.name == "track_id" }?.value?.trim()
                if (hType != null && trackId != null) "trak(#$trackId, $hType)"
                else if (hType != null) "trak($hType)"
                else "trak"
            }
            "stsd" -> {
                val codec = node.children.firstOrNull()?.type
                if (codec != null) "stsd($codec)" else "stsd"
            }
            else -> node.type
        }
    }

    /**
     * 타깃 노드를 포함하고 있는 부모 trak 노드를 탐색합니다.
     */
    fun findParentTrack(root: BoxNode, target: BoxNode): BoxNode? {
        val tracks = mutableListOf<BoxNode>()
        fun collectTracks(node: BoxNode) {
            if (node.type == "trak") tracks.add(node)
            node.children.forEach { collectTracks(it) }
        }
        collectTracks(root)

        for (track in tracks) {
            if (containsNode(track, target)) return track
        }
        return null
    }

    private fun containsNode(parent: BoxNode, target: BoxNode): Boolean {
        if (parent === target || (parent.type == target.type && parent.offset == target.offset && parent.size == target.size)) {
            return true
        }
        return parent.children.any { containsNode(it, target) }
    }

    /**
     * trak 내부의 stts, stsz, stco, stsc, mdhd 등 주요 샘플 테이블의 수치를 추출하여 상호 검증용 컨텍스트를 구성합니다.
     */
    fun buildTrackSampleTableContext(trak: BoxNode): Map<String, String> {
        val result = mutableMapOf<String, String>()

        // 1. Handler & Track ID
        val tkhd = findFirst(trak) { it.type == "tkhd" }
        tkhd?.fields?.find { it.name == "track_id" }?.value?.let { result["Track ID"] = it }
        val hdlr = findFirst(trak) { it.type == "hdlr" }
        hdlr?.fields?.find { it.name == "handler_type" }?.value?.let { result["Handler Type"] = it }

        // 2. mdhd (Timescale, Duration)
        val mdhd = findFirst(trak) { it.type == "mdhd" }
        val timescale = mdhd?.fields?.find { it.name == "timescale" }?.value?.toDoubleOrNull()
        val duration = mdhd?.fields?.find { it.name == "duration" }?.value?.toDoubleOrNull()
        if (timescale != null && timescale > 0 && duration != null) {
            val sec = duration / timescale
            result["mdhd Duration"] = String.format(Locale.US, "%.2f sec (duration: %.0f, timescale: %.0f)", sec, duration, timescale)
        }

        // 3. stts (Time-to-Sample)
        val stts = findFirst(trak) { it.type == "stts" }
        stts?.fields?.find { it.name == "entry_count" }?.value?.let { entries ->
            result["stts (Time-to-Sample)"] = "$entries entries"
        }

        // 4. stsz / stz2 (Sample Size Table)
        val stsz = findFirst(trak) { it.type == "stsz" || it.type == "stz2" }
        if (stsz != null) {
            val sampleCount = stsz.fields.find { it.name == "sample_count" }?.value
            val sampleSize = stsz.fields.find { it.name == "sample_size" }?.value
            result["stsz (Sample Size)"] = "sample_count: ${sampleCount ?: "unknown"}, fixed_sample_size: ${sampleSize ?: "variable"}"
        }

        // 5. stco / co64 (Chunk Offset Table)
        val stco = findFirst(trak) { it.type == "stco" || it.type == "co64" }
        if (stco != null) {
            val entryCount = stco.fields.find { it.name == "entry_count" }?.value
            result["${stco.type} (Chunk Offset)"] = "${entryCount ?: "unknown"} chunks"
        }

        // 6. stss (Sync Sample / Keyframes)
        val stss = findFirst(trak) { it.type == "stss" }
        if (stss != null) {
            val keyframeCount = stss.fields.find { it.name == "entry_count" }?.value
            result["stss (Sync Samples/Keyframes)"] = "${keyframeCount ?: "unknown"} keyframes"
        }

        return result
    }

    /**
     * 파일의 지정 오프셋 위치에서 최대 [length] 바이트를 읽어 헥사 덤프 문자열을 생성합니다.
     */
    fun readHexDumpSnippet(file: File, offset: Long, length: Int = 32): String? {
        if (!file.exists() || !file.isFile || offset < 0 || offset >= file.length()) return null
        return try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                val toRead = minOf(length.toLong(), file.length() - offset).toInt()
                if (toRead <= 0) return null
                val buf = ByteArray(toRead)
                raf.readFully(buf)
                val hex = buf.joinToString(" ") { String.format(Locale.US, "%02X", it) }
                val ascii = buf.map { b ->
                    val c = (b.toInt() and 0xFF).toChar()
                    if (c in ' '..'~') c else '.'
                }.joinToString("")
                String.format(Locale.US, "0x%08X: %-48s | %s", offset, hex, ascii)
            }
        } catch (_: Exception) {
            null
        }
    }

    private data class DomainContext(val roleTitle: String, val specs: String, val targets: String)

    private fun getFormatDomainContext(extension: String, majorBrand: String): DomainContext {
        val brandLower = majorBrand.lowercase(Locale.US)
        return when {
            extension in listOf("heic", "heif", "avif") || brandLower.contains("heic") || brandLower.contains("mif1") -> {
                DomainContext(
                    roleTitle = "HEIF/HEIC/AVIF 컨테이너, ISOBMFF 이미지 시퀀스 및 EXIF/ICC 메타데이터",
                    specs = "ISO/IEC 23008-12 (HEIF), ISO/IEC 14496-12 (ISOBMFF), ITU-T H.265 (HEVC Still), AV1 Image File Format (AVIF), EXIF 2.32",
                    targets = "Android BitmapFactory / ImageDecoder / HeifDecoder, Apple ImageIO / CoreGraphics, libheif, libavif, Chromium, FFmpeg",
                )
            }
            extension in listOf("webm", "mkv") -> {
                DomainContext(
                    roleTitle = "WebM / Matroska (EBML) 멀티미디어 컨테이너 및 VP8/VP9/AV1/Opus 코덱",
                    specs = "RFC 8794 (EBML), WebM Container Guidelines, Matroska Specification v4, VP8/VP9 Bitstream, Opus Interactive Audio (RFC 6716)",
                    targets = "Chromium / WebRTC 데먹서, Android Stagefright / MediaCodec, libvpx, FFmpeg Matroska demuxer, VLC",
                )
            }
            extension in listOf("avi") -> {
                DomainContext(
                    roleTitle = "RIFF AVI (Audio Video Interleave) 및 OpenDML 확장 비디오 컨테이너",
                    specs = "Microsoft RIFF AVI Format, OpenDML AVI File Format Extensions v1.02, DirectShow AVI Splitter, ACM/VCM Architecture",
                    targets = "Windows Media Player / DirectShow, FFmpeg AVI demuxer, VLC, GStreamer",
                )
            }
            extension in listOf("flv") -> {
                DomainContext(
                    roleTitle = "Adobe Flash Video (FLV) 컨테이너 및 RTMP 스트리밍 패킷",
                    specs = "Adobe Flash Video File Format Specification v10.1, Enhanced RTMP/FLV (HEVC/AV1/Opus), AMF0 Metadata Specification",
                    targets = "FFmpeg FLV demuxer, OBS Studio Streaming pipeline, SRS / NGINX-RTMP, VLC",
                )
            }
            extension in listOf("wmv", "asf", "wma") -> {
                DomainContext(
                    roleTitle = "Microsoft Advanced Systems Format (ASF) 및 Windows Media Video/Audio",
                    specs = "Advanced Systems Format (ASF) Specification v01.20.03, VC-1 (SMPTE 421M), Windows Media Audio 9",
                    targets = "Windows Media Foundation, FFmpeg ASF demuxer, VLC",
                )
            }
            extension in listOf("aac") -> {
                DomainContext(
                    roleTitle = "MPEG-4 AAC (Advanced Audio Coding) 및 ADTS 프레임 스트림",
                    specs = "ISO/IEC 13818-7 (MPEG-2 AAC), ISO/IEC 14496-3 (MPEG-4 Audio ADTS Header & CRC), ID3v2 Tags",
                    targets = "Android Stagefright AAC Extractor, Apple CoreAudio / AudioToolbox, FFmpeg aac demuxer",
                )
            }
            extension in listOf("mp3") -> {
                DomainContext(
                    roleTitle = "MPEG-1/2 Audio Layer III (MP3) 프레임 스트림 및 ID3v2 메타데이터",
                    specs = "ISO/IEC 11172-3 (MPEG-1 Audio), ISO/IEC 13818-3 (MPEG-2 Audio), ID3v2.3/ID3v2.4 Informative Standards, Xing/VBRI VBR Header",
                    targets = "Android MediaPlayer / AudioTrack, Apple CoreAudio, FFmpeg mp3 demuxer, Web Audio API",
                )
            }
            extension in listOf("wav") -> {
                DomainContext(
                    roleTitle = "Microsoft RIFF WAVE 오디오 컨테이너 및 PCM/압축 오디오 포맷",
                    specs = "Microsoft Multimedia Standards Update (RIFF WAVE), WAVEFORMATEX & WAVEFORMATEXTENSIBLE, Broadcast Wave Format (BWF)",
                    targets = "ALSA / PulseAudio, Windows WASAPI, Apple CoreAudio, FFmpeg wav demuxer",
                )
            }
            extension in listOf("jpg", "jpeg") -> {
                DomainContext(
                    roleTitle = "JPEG (JFIF/EXIF) 바이너리 마커 및 이미지 코덱",
                    specs = "ISO/IEC 10918-1 (ITU-T T.81 JPEG), JEITA CP-3451 (EXIF 2.32), Adobe XMP, ICC.1:2010",
                    targets = "Android Skia / BitmapFactory, libjpeg-turbo, Apple CoreGraphics / ImageIO, Web Browsers",
                )
            }
            extension in listOf("png") -> {
                DomainContext(
                    roleTitle = "Portable Network Graphics (PNG) 청크 구조 및 zlib 압축 스트림",
                    specs = "ISO/IEC 15948 (PNG 2nd Edition), RFC 2083, W3C PNG Specification, eXIf / iCCP / cHRM chunks",
                    targets = "libpng, Skia, Apple CoreGraphics, Web Browsers",
                )
            }
            extension in listOf("raw", "yuv") -> {
                DomainContext(
                    roleTitle = "Raw YUV/비압축 픽셀 포맷 및 비디오 파이프라인",
                    specs = "Planar / Semi-planar YUV (NV12, NV21, I420, YUY2), ITU-R BT.601 / BT.709 / BT.2020 Color Matrix, Bit Depth & Stride",
                    targets = "Android Camera2 HAL / SurfaceTexture, OpenGL ES / Vulkan Compute Shaders, FFmpeg rawvideo",
                )
            }
            else -> {
                DomainContext(
                    roleTitle = "비디오 코덱, ISOBMFF/HEIF 컨테이너 스펙 및 Android Stagefright/MediaCodec / Apple AVFoundation",
                    specs = "ISO/IEC 14496-12 (ISOBMFF Box Hierarchy & Sample Tables), ISO/IEC 14496-14 (MP4 File Format), ITU-T H.264/H.265 NALU",
                    targets = "Android MediaExtractor / MediaCodec 디코딩 파이프라인, Apple AVFoundation, Chromium Demuxer, FFmpeg",
                )
            }
        }
    }

    private fun escapeJson(text: String): String {
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.2f GB", gb)
    }
}
