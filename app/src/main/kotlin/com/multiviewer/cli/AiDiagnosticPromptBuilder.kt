package com.multiviewer.cli

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.WarningEntry
import com.multiviewer.parser.findFirst
import java.io.File
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
        sb.appendLine("당신은 10년 차 $roleTitle 멀티미디어 프레임워크 전문 엔지니어입니다.")
        sb.appendLine()
        sb.appendLine("다음은 unwrapMedia 바이너리 분석기가 감지한 미디어 파일의 구조적 결함 및 메타데이터 진단 데이터입니다.")
        sb.appendLine()
        sb.appendLine("### [1. 분석 대상 미디어 정보]")
        sb.appendLine("- 파일명: ${file.name}")
        sb.appendLine("- 파일 크기: $fileSizeStr (${file.length()} bytes)")
        if (majorBrand != "unknown") {
            sb.appendLine("- Major Brand: $majorBrand")
        }
        if (compatibleBrands.isNotEmpty()) {
            sb.appendLine("- Compatible Brands: $compatibleBrands")
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
            sb.appendLine("2. 모바일 기기(Android MediaCodec/Apple ImageIO) 및 웹 브라우저에서의 재생/디코딩 효율을 높이기 위한 인코딩/먹싱 권장 설정을 설명해 주세요.")
            return sb.toString()
        }

        sb.appendLine("### [2. 감지된 구조적 결함 및 경고 (Facts Detected by UnwrapMedia)]")
        sb.appendLine("```json")
        sb.appendLine("[")
        warnings.forEachIndexed { index, w ->
            val severity = determineSeverity(w.node.type, w.warning)
            val comma = if (index < warnings.size - 1) "," else ""
            sb.appendLine("  {")
            sb.appendLine("    \"box\": \"${w.node.type}\",")
            sb.appendLine("    \"offset\": ${w.node.offset},")
            sb.appendLine("    \"size\": ${w.node.size},")
            sb.appendLine("    \"severity\": \"$severity\",")
            sb.appendLine("    \"warning\": \"${escapeJson(w.warning)}\"")
            sb.appendLine("  }$comma")
        }
        sb.appendLine("]")
        sb.appendLine("```")
        sb.appendLine()

        sb.appendLine("### [3. 표준 규격 및 프레임워크 컨텍스트]")
        sb.appendLine("- 관련 표준 스펙: $specs")
        sb.appendLine("- 주요 타깃 환경: $targetEnvironments")
        sb.appendLine()

        sb.appendLine("### [4. 요청 사항 (Analysis Requested from AI)]")
        sb.appendLine("1. **근본 원인 분석 (Root Cause)**:")
        sb.appendLine("   - 위 JSON에 나열된 구조적 결함들이 인코더/먹서(Muxer) 생성 과정에서 왜 발생했는지 추론해 주세요.")
        sb.appendLine("   - 이 결함이 $targetEnvironments 환경에서 파싱 실패, A/V 싱크 어긋남, 프레임 드랍, 또는 크래시(Crash)를 유발할 수 있는 기술적 메커니즘을 설명해 주세요.")
        sb.appendLine()
        sb.appendLine("2. **무손실 복구 방안 (Quick Fix)**:")
        sb.appendLine("   - 비디오/오디오 스트림을 재인코딩하지 않고 원본 화질을 보존하면서 컨테이너 구조를 복구할 수 있는 **FFmpeg 리먹싱 커맨드**(`-c copy`, `-movflags` 등)를 제시해 주세요.")
        sb.appendLine()
        sb.appendLine("3. **먹서/파이프라인 수정 가이드라인 (Prevention)**:")
        sb.appendLine("   - 파일 생성/먹싱 소프트웨어 레벨에서 이 결함이 재발하지 않도록 하기 위해 수정해야 할 스펙 준수 가이드를 제안해 주세요.")
        sb.appendLine()
        sb.appendLine("4. **분석 신뢰도 및 검증 방법 (Confidence & Verification)**:")
        sb.appendLine("   - 제공된 관측 사실(Observed Facts)과 추론(Inference)을 명확히 구분하고, 결론에 대한 신뢰도 수준을 명시해 주세요.")

        return sb.toString()
    }

    private data class DomainContext(val roleTitle: String, val specs: String, val targets: String)

    private fun getFormatDomainContext(extension: String, majorBrand: String): DomainContext {
        val brandLower = majorBrand.lowercase(Locale.US)
        return when {
            extension in listOf("heic", "heif", "avif") || brandLower.contains("heic") || brandLower.contains("mif1") -> {
                DomainContext(
                    roleTitle = "HEIF/HEIC 컨테이너, ISOBMFF 이미지 시퀀스 및 EXIF/ICC 메타데이터",
                    specs = "ISO/IEC 23008-12 (HEIF), ISO/IEC 14496-12 (ISOBMFF), ITU-T H.265 (HEVC Main Still Picture), EXIF 2.32",
                    targets = "Android BitmapFactory / ImageDecoder / HeifDecoder, Apple ImageIO / CoreGraphics, libheif, Chromium, FFmpeg",
                )
            }
            extension in listOf("jpg", "jpeg") -> {
                DomainContext(
                    roleTitle = "JPEG (JFIF/EXIF) 바이너리 마커 및 이미지 코덱",
                    specs = "ISO/IEC 10918-1 (ITU-T T.81 JPEG), JEITA CP-3451 (EXIF 2.32), Adobe XMP, ICC.1:2010",
                    targets = "Android Skia / BitmapFactory, libjpeg-turbo, Apple CoreGraphics / ImageIO, Web Browsers",
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
                    specs = "ISO/IEC 14496-12 (ISOBMFF Box Hierarchy & Sample Tables), ISO/IEC 23008-12 (HEIF), ITU-T H.264/H.265 NALU",
                    targets = "Android MediaExtractor / MediaCodec 디코딩 파이프라인, Apple AVFoundation, Chromium Demuxer, FFmpeg",
                )
            }
        }
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

    private fun escapeJson(text: String): String {
        return text.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}
