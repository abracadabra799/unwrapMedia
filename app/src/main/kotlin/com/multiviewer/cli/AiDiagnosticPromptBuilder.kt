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
                lower.contains("overflow") || lower.contains("out of bounds") || lower.contains("crash") -> "CRITICAL"
            lower.contains("mismatch") || lower.contains("unsupported") || lower.contains("non-standard") ||
                lower.contains("duplicate") || lower.contains("trailing") || lower.contains("unaligned") -> "WARNING"
            else -> "INFO"
        }
    }

    fun buildPrompt(file: File, root: BoxNode?, warnings: List<WarningEntry>): String {
        val fileSizeStr = formatFileSize(file.length())
        val ftyp = root?.let { findFirst(it) { node -> node.type == "ftyp" } }
        val majorBrand = ftyp?.fields?.find { it.name == "major_brand" }?.value?.trim() ?: "unknown"
        val compatibleBrands = ftyp?.fields?.find { it.name == "compatible_brands" }?.value?.trim() ?: ""

        val sb = StringBuilder()
        sb.appendLine("당신은 10년 차 비디오 코덱, ISOBMFF/HEIF 컨테이너 스펙 및 Android Stagefright/MediaCodec / Apple AVFoundation 멀티미디어 프레임워크 전문 엔지니어입니다.")
        sb.appendLine()
        sb.appendLine("다음은 unwrapMedia 바이너리 분석기가 감지한 미디어 파일의 구조적 결함 및 메타데이터 진단 데이터입니다.")
        sb.appendLine()
        sb.appendLine("### [1. 분석 대상 미디어 정보]")
        sb.appendLine("- 파일명: ${file.name}")
        sb.appendLine("- 파일 크기: $fileSizeStr (${file.length()} bytes)")
        sb.appendLine("- Major Brand: $majorBrand")
        if (compatibleBrands.isNotEmpty()) {
            sb.appendLine("- Compatible Brands: $compatibleBrands")
        }
        sb.appendLine()

        if (warnings.isEmpty()) {
            sb.appendLine("### [2. unwrapMedia 바이너리 검증 결과]")
            sb.appendLine("✓ 구조적 결함이나 스펙 위반 경고가 감지되지 않은 정상 파일입니다.")
            sb.appendLine()
            sb.appendLine("### [3. 요청 사항]")
            sb.appendLine("이 미디어 컨테이너의 표준 호환성(Web Streaming, Android/iOS 호환성 등)을 더욱 높이기 위한 최적화 방안(예: faststart, moov 위치, 청크 정렬 등)을 제안해 주세요.")
            return sb.toString()
        }

        sb.appendLine("### [2. 감지된 구조적 결함 및 경고 (JSON)]")
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
        sb.appendLine("- 관련 표준 스펙: ISO/IEC 14496-12 (ISOBMFF Box Hierarchy & Sample Tables), ISO/IEC 23008-12 (HEIF), ITU-T H.264/H.265 NALU")
        sb.appendLine("- 주요 타깃 환경: Android MediaExtractor / MediaCodec 디코딩 파이프라인, Apple AVFoundation, Chromium Demuxer, FFmpeg")
        sb.appendLine()

        sb.appendLine("### [4. 요청 사항]")
        sb.appendLine("1. **근본 원인 분석 (Root Cause)**:")
        sb.appendLine("   - 위 JSON에 나열된 구조적 결함들이 인코더/먹서(Muxer) 생성 과정에서 왜 발생했는지 추론해 주세요.")
        sb.appendLine("   - 이 결함이 Android MediaExtractor, ExoPlayer, 또는 특정 기기 디코더에서 파싱 실패, A/V 싱크 어긋남, 프레임 드랍, 또는 크래시(Crash)를 유발할 수 있는 기술적 메커니즘을 설명해 주세요.")
        sb.appendLine()
        sb.appendLine("2. **무손실 복구 방안 (Quick Fix)**:")
        sb.appendLine("   - 비디오/오디오 스트림을 재인코딩하지 않고 원본 화질을 보존하면서 컨테이너 구조를 복구할 수 있는 **FFmpeg 리먹싱 커맨드**(`-c copy`, `-movflags` 등)를 제시해 주세요.")
        sb.appendLine()
        sb.appendLine("3. **먹서/파이프라인 수정 가이드라인 (Prevention)**:")
        sb.appendLine("   - 파일 생성/먹싱 소프트웨어 레벨에서 이 결함이 재발하지 않도록 하기 위해 수정해야 할 스펙 준수 가이드를 제안해 주세요.")

        return sb.toString()
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
