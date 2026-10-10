package com.multiviewer.ui

import com.multiviewer.cli.scrubPaths
import com.multiviewer.parser.SefCheckResult
import com.multiviewer.parser.integrity.CheckStatus
import com.multiviewer.parser.integrity.ImageStructureReport
import java.io.File

/**
 * The Content Check window's latest verified results for one tab, published to [TabState.contentCheck]
 * so the AI diagnosis prompt can include them. A null field means that step has not run (yet).
 * [mediaType] lets the prompt tell "not applicable" apart from "not run"; null = unknown.
 */
data class ContentCheckSnapshot(
    val structure: ImageStructureReport?,
    val imageDecode: ImageDecodeReport?,
    val motionDetected: Boolean,
    val motion: MotionPhotoIntegrityReport?,
    val motionError: String?,
    val video: VideoIntegrityReport?,
    val mediaType: MediaType? = null,
)

private const val MAX_ITEMS = 30
private const val MAX_LOG_LINES = 20
private const val NOT_RUN = "미실행"
private const val NOT_APPLICABLE = "해당 없음"

private fun hex(v: Long): String = "0x" + v.toString(16).uppercase()

/** Appends at most [max] lines of [items], then a "… N개 생략" line for the rest. */
private fun <T> StringBuilder.appendCapped(items: List<T>, max: Int, indent: String, line: (T) -> String) {
    items.take(max).forEach { appendLine(indent + line(it)) }
    if (items.size > max) appendLine("$indent… ${items.size - max}개 생략")
}

/** Text section for the AI diagnosis prompt; every free-text value passes through [scrubPaths]. */
fun contentCheckPromptSection(snapshot: ContentCheckSnapshot, file: File): String {
    fun scrub(text: String) = scrubPaths(text, file).replace('\n', ' ').trim()
    val type = snapshot.mediaType
    val sb = StringBuilder()
    sb.appendLine("### [컨텐츠 검사 결과 (앱이 직접 검증한 사실)]")
    sb.appendLine(
        "아래 항목은 unwrapMedia가 바이트 단위 구조 검증과 실제 디코딩으로 직접 확인한 사실입니다. " +
            "추측이 아닌 검증된 사실로 취급하고, 결과를 다시 의심하기보다 원인과 복구 방법에 집중해 주세요.",
    )

    // 1) Structure
    val s = snapshot.structure
    if (s == null) {
        sb.appendLine("- 구조 검사: $NOT_RUN")
    } else {
        val problems = s.items.filter { it.status == CheckStatus.FAIL || it.status == CheckStatus.WARN }
        sb.appendLine("- 구조 검사 (${s.format}): ${s.overall.name} — 검사 항목 ${s.items.size}개 중 FAIL/WARN ${problems.size}개")
        sb.appendCapped(problems, MAX_ITEMS, "  • ") { item ->
            val location = item.offset?.let { off ->
                " @ ${hex(off)}" + (item.length?.let { " (${it}B)" } ?: "")
            } ?: ""
            "[${item.status.name}] ${item.id} — ${scrub(item.title)}$location: ${scrub(item.detail)}"
        }
    }

    // 2) Image decode
    val d = snapshot.imageDecode
    when {
        d != null -> {
            sb.appendLine("- 이미지 디코딩: ${d.status.name}")
            val decodedSize = if (d.decodedWidth != null && d.decodedHeight != null) "${d.decodedWidth}x${d.decodedHeight}" else "unknown"
            sb.appendLine(
                "  • FFmpeg: ${d.ffmpegStatus.name} | 버전: ${d.ffmpegVersion?.let(::scrub) ?: "unknown"} | " +
                    "디코딩 프레임: ${d.decodedFrames} | 디코딩 크기: $decodedSize",
            )
            val skia = when {
                !d.skia.attempted -> "시도 안 함"
                d.skia.ok -> "성공"
                else -> "실패"
            }
            sb.appendLine("  • Skia: $skia — ${scrub(d.skia.detail)}")
            val declared = if (d.declaredWidth != null && d.declaredHeight != null) "${d.declaredWidth}x${d.declaredHeight}" else "unknown"
            sb.appendLine("  • 해상도 검사: ${d.resolutionStatus.name} (선언 $declared / 디코딩 $decodedSize)")
            if (d.logs.isNotEmpty()) {
                sb.appendLine("  • FFmpeg 로그" + if (d.logsTruncated) " (원본 로그 일부 잘림):" else ":")
                sb.appendCapped(d.logs, MAX_LOG_LINES, "    ") { scrub(it) }
            }
        }
        type != null && type != MediaType.IMAGE -> sb.appendLine("- 이미지 디코딩: $NOT_APPLICABLE")
        else -> sb.appendLine("- 이미지 디코딩: $NOT_RUN")
    }

    // 3) Motion photo
    val m = snapshot.motion
    when {
        snapshot.motionError != null -> sb.appendLine("- 모션포토: 분석 실패 — ${scrub(snapshot.motionError)}")
        m != null -> {
            val formats = m.detectedFormats.joinToString(", ") { it.name }.ifEmpty { "없음" }
            sb.appendLine("- 모션포토: ${m.verdictStatus().name} (감지 형식: $formats)")
            val checks = buildList<Pair<String, SefCheckResult>> {
                m.googleXmpChecks.forEach { add("Google XMP" to it) }
                m.sefSection?.let { sef ->
                    sef.structuralChecks.forEach { add("SEF 구조" to it) }
                    sef.semanticChecks.forEach { add("SEF 의미" to it) }
                    sef.directoryEntries.forEach { row ->
                        add(
                            "SEF 디렉터리" to SefCheckResult(
                                row.status,
                                row.name ?: "#${row.entryIndex}",
                                "marker ${row.markerHex} offset ${row.declaredOffset} length ${row.declaredLength}",
                            ),
                        )
                    }
                }
                m.appleMpvdChecks.forEach { add("HEIC mpvd" to it) }
                m.decodeChecks.forEach { add("영상 디코딩" to it) }
            }.filter { (_, c) -> c.severity.toCheckStatus().let { it != CheckStatus.PASS && it != CheckStatus.INFO } }
            sb.appendCapped(checks, MAX_ITEMS, "  • ") { (section, c) ->
                "[${c.severity.toCheckStatus().name}] $section — ${scrub(c.label)}: ${scrub(c.detail)}"
            }
        }
        !snapshot.motionDetected -> sb.appendLine("- 모션포토: $NOT_APPLICABLE (모션포토 데이터 미감지)")
        else -> sb.appendLine("- 모션포토: $NOT_RUN")
    }

    // 4) Video
    val v = snapshot.video
    when {
        v != null -> {
            sb.appendLine("- 영상 디코딩 (첫 번째 영상 트랙, 소프트웨어 디코딩): ${v.decodeStatus.name} | 패킷 검사: ${v.packetStatus.name} | 디코딩 프레임: ${v.decodedFrames}")
            if (v.logs.isNotEmpty()) {
                sb.appendLine("  • 디코더 로그" + if (v.logsTruncated) " (원본 로그 일부 잘림):" else ":")
                sb.appendCapped(v.logs, MAX_LOG_LINES, "    ") { scrub(it) }
            }
            if (v.packetLogs.isNotEmpty()) {
                sb.appendLine("  • 패킷 로그:")
                sb.appendCapped(v.packetLogs, MAX_LOG_LINES, "    ") { scrub(it) }
            }
        }
        type != null && type != MediaType.VIDEO -> sb.appendLine("- 영상 디코딩: $NOT_APPLICABLE")
        else -> sb.appendLine("- 영상 디코딩: $NOT_RUN")
    }
    return sb.toString()
}
