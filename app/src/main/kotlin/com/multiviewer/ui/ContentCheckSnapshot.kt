package com.multiviewer.ui

import com.multiviewer.parser.motionphoto.toSefCheckResult
import com.multiviewer.cli.scrubPaths
import com.multiviewer.cli.JsonValue
import com.multiviewer.parser.SefCheckResult
import com.multiviewer.parser.conformance.CodecBitstreamReport
import com.multiviewer.parser.conformance.ConformanceFinding
import com.multiviewer.parser.conformance.ConformanceReport
import com.multiviewer.parser.conformance.ConformanceStatus
import com.multiviewer.parser.conformance.SpecCitation
import com.multiviewer.parser.conformance.label
import com.multiviewer.parser.integrity.CheckStatus
import com.multiviewer.parser.integrity.AudioStructureReport
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
    val structureError: String? = null,
    val imageDecodeError: String? = null,
    val videoError: String? = null,
    /** True while a run is in progress: steps with no result and no error yet are still pending. */
    val running: Boolean = false,
    val audio: AudioIntegrityReport? = null,
    val audioError: String? = null,
    val audioStructure: AudioStructureReport? = null,
    val conformance: ConformanceReport? = null,
    /** The file state the results describe; evidence is only valid while [ContentFileIdentity.matches] holds. */
    val fileIdentity: ContentFileIdentity? = null,
    val stages: Map<CheckStage, CheckStageState> = emptyMap(),
    /** Codec byte checks in run order: the video track's first, then the audio track's. */
    val codecBitstreams: List<CodecBitstreamReport> = emptyList(),
    /** What the codec run did not cover ([codecCoverageGaps]); non-empty means the codec check is not complete. */
    val codecGaps: List<String> = emptyList(),
)

/** Size + modification time of the checked file, captured before reading it (same idea as the thumbnail cache). */
data class ContentFileIdentity(val length: Long, val lastModified: Long) {
    /** Blocking stat calls: never call on the UI thread. A missing file never matches. */
    fun matches(file: File): Boolean = file.isFile && file.length() == length && file.lastModified() == lastModified

    companion object {
        fun capture(file: File) = ContentFileIdentity(file.length(), file.lastModified())
    }
}

/** The file's size/mtime changed while it was being checked; the partial results must be discarded. */
class ContentFileChangedException : Exception("File changed during inspection")

/** Only evaluated, identity-bound rules may contribute a green summary. */
fun effectiveConformanceStatus(snapshot: ContentCheckSnapshot): ConformanceStatus {
    val report = snapshot.conformance ?: return ConformanceStatus.NOT_CHECKED
    val normative = report.findings.filterNot { it.rule.source.startsWith("unwrapMedia structural check") ||
        it.rule.source.contains("structural observation") }
    if (normative.any { it.status == ConformanceStatus.FAIL }) return ConformanceStatus.FAIL
    if (normative.any { it.status == ConformanceStatus.WARN }) return ConformanceStatus.WARN
    if (snapshot.stages[CheckStage.STRUCTURE]?.status != CheckRunStatus.COMPLETED) return ConformanceStatus.NOT_CHECKED
    if (snapshot.mediaType == MediaType.VIDEO && snapshot.stages[CheckStage.CODEC]?.status != CheckRunStatus.COMPLETED) {
        return ConformanceStatus.NOT_CHECKED
    }
    if (snapshot.mediaType == MediaType.AUDIO && snapshot.stages.containsKey(CheckStage.CODEC) &&
        snapshot.stages[CheckStage.CODEC]?.status != CheckRunStatus.COMPLETED) {
        return ConformanceStatus.NOT_CHECKED
    }
    if (report.inspectionError != null || normative.any { it.status == ConformanceStatus.NOT_CHECKED }) return ConformanceStatus.NOT_CHECKED
    return if (normative.any { it.status == ConformanceStatus.PASS }) ConformanceStatus.PASS else ConformanceStatus.NOT_CHECKED
}

/** Additive, versioned workflow metadata; evidence fields in the older JSON schema are untouched. */
fun contentCheckWorkflowJson(snapshot: ContentCheckSnapshot, file: File): JsonValue = JsonValue.JObject(listOf(
    "version" to JsonValue.JNumber(1),
    "conformanceStatus" to JsonValue.JString(effectiveConformanceStatus(snapshot).name),
    "stages" to JsonValue.JArray(snapshot.stages.entries.map { (stage, state) ->
        JsonValue.JObject(listOf(
            "stage" to JsonValue.JString(stage.name),
            "status" to JsonValue.JString(state.status.name),
        ) + (state.reason?.let { listOf("reason" to JsonValue.JString(scrubPaths(it, file).take(1_000))) } ?: emptyList()) +
            (state.progress?.let { progress -> listOf(
                "processed" to JsonValue.JNumber(progress.completed),
                "unit" to JsonValue.JString(progress.unit),
            ) + (progress.total?.let { listOf("total" to JsonValue.JNumber(it)) } ?: emptyList()) } ?: emptyList()))
    }),
) + codecJson(snapshot.codecBitstreams, file))

/** "codec" keeps the first check (the video track's, when there is one) for older readers; "codecs" lists every checked track. */
private fun codecJson(reports: List<CodecBitstreamReport>, file: File): List<Pair<String, JsonValue>> {
    if (reports.isEmpty()) return emptyList()
    val objects = reports.map { codecReportJson(it, file) }
    return listOf("codec" to objects.first(), "codecs" to JsonValue.JArray(objects))
}

private fun codecReportJson(report: CodecBitstreamReport, file: File): JsonValue = JsonValue.JObject(listOf(
    "codecName" to JsonValue.JString(report.codec.label),
    "checkedSamples" to JsonValue.JNumber(report.checkedSamples.toLong()),
    "indexedSamples" to JsonValue.JNumber(report.indexedSamples.toLong()),
    "complete" to JsonValue.JBoolean(report.complete),
    "checkedTrack" to JsonValue.JString(report.checkedTrack),
    "totalOccurrences" to JsonValue.JObject(report.totalOccurrences.entries.take(30).map { (id, count) ->
        id to JsonValue.JNumber(count)
    }),
    "limitations" to JsonValue.JArray(report.limitations.take(30).map { JsonValue.JString(scrubPaths(it, file).take(1_000)) }),
    "findings" to JsonValue.JArray(report.findings.take(100).map { finding -> JsonValue.JObject(listOf(
        "ruleId" to JsonValue.JString(finding.rule.id),
        "status" to JsonValue.JString(finding.status.name),
        "source" to JsonValue.JString(finding.rule.source),
        "details" to JsonValue.JArray(finding.details.take(4).map { JsonValue.JString(scrubPaths(it, file).take(1_000)) }),
        "ranges" to JsonValue.JArray(finding.ranges.take(4).map { range -> JsonValue.JObject(listOf(
            "start" to JsonValue.JNumber(range.first), "endInclusive" to JsonValue.JNumber(range.last))) }),
    )) }),
))

const val STALE_CONTENT_CHECK_NOTICE =
    "컨텐츠 검사 결과 제외: 검사 이후 파일이 변경되어 이전 판정과 바이트 근거를 사용하지 않습니다. 파일을 다시 열어 검사해야 합니다."
const val UNBOUND_CONTENT_CHECK_NOTICE =
    "컨텐츠 검사 결과 제외: 구조 검사가 아직 끝나지 않았거나 검사한 파일 상태를 확인할 수 없어, 판정과 바이트 근거를 사용하지 않습니다."

private const val MAX_ITEMS = 30
private const val MAX_LOG_LINES = 20
private const val MAX_PROMPT_FACTS_PER_FINDING = 30
private const val MAX_PROMPT_TEXT_CHARS = 1_000
private const val NOT_RUN = "미실행"
private const val NOT_APPLICABLE = "해당 없음"
private const val IN_PROGRESS = "진행 중"

private fun hex(v: Long): String = "0x" + v.toString(16).uppercase()

/** Appends at most [max] lines of [items], then a "… N개 생략" line for the rest. */
private fun <T> StringBuilder.appendCapped(items: List<T>, max: Int, indent: String, line: (T) -> String) {
    items.take(max).forEach { appendLine(indent + line(it)) }
    if (items.size > max) appendLine("$indent… ${items.size - max}개 생략")
}

private fun contentCheckFindings(snapshot: ContentCheckSnapshot): List<ContentFinding> = buildList {
    snapshot.codecBitstreams.forEach { addAll(codecBitstreamFindings(it)) }
    snapshot.structure?.let { addAll(structureFindings(it, FindingStage.STRUCTURE)) }
    snapshot.audioStructure?.let { addAll(audioStructureFindings(it)) }
    snapshot.imageDecode?.let { addAll(imageDecodeFindings(it)) }
    snapshot.motion?.let { addAll(motionPhotoFindings(it)) }
    snapshot.video?.let {
        val presentation = videoFindings(it)
        addAll(presentation.decoderFindings)
        addAll(presentation.packetFindings)
    }
    snapshot.audio?.let {
        val presentation = audioFindings(it)
        addAll(presentation.probeFindings)
        addAll(presentation.trackFindings)
    }
    snapshot.structureError?.let { add(errorFinding("structure.error", FindingStage.STRUCTURE, it)) }
    snapshot.audioError?.let { add(errorFinding("audio.error", FindingStage.AUDIO_DECODE, it)) }
    snapshot.imageDecodeError?.let { add(errorFinding("image.decode.error", FindingStage.IMAGE_DECODE, it)) }
    snapshot.motionError?.let { add(errorFinding("motion.error", FindingStage.MOTION_PHOTO, it)) }
    snapshot.videoError?.let { add(errorFinding("video.error", FindingStage.VIDEO_DECODE, it)) }
}

private fun errorFinding(id: String, stage: FindingStage, error: String) = ContentFinding(
    id = id, stage = stage, status = CheckStatus.FAIL, title = "Inspection failed", location = null,
    offset = null, length = null, summary = safeFindingText(error),
    verifiedFacts = listOf("Inspection failed: ${safeFindingText(error)}"),
    sourceNote = STRUCTURAL_OBSERVATION_LABEL,
).sanitizeFindingPaths()

/** Text section for the AI diagnosis prompt; every free-text value passes through [scrubPaths]. */
fun contentCheckPromptSection(snapshot: ContentCheckSnapshot, file: File): String {
    fun scrub(text: String) = scrubPaths(text, file).replace('\n', ' ').trim()
    fun promptText(text: String): String {
        val safe = scrub(text)
        return if (safe.length <= MAX_PROMPT_TEXT_CHARS) safe
        else safe.take(MAX_PROMPT_TEXT_CHARS) + "… (문자 일부 생략)"
    }
    val type = snapshot.mediaType
    val pending = if (snapshot.running) IN_PROGRESS else NOT_RUN
    val sb = StringBuilder()
    // One codec byte check: coverage line, limitations, then the findings. Shared by all nine codecs.
    fun StringBuilder.appendCodecByteCheck(
        codec: String,
        checkedSamples: Int,
        indexedSamples: Int,
        complete: Boolean,
        checkedTrack: String,
        limitations: List<String>,
        findings: List<ConformanceFinding>,
    ) {
        appendLine("- $codec 바이트 검사: $checkedSamples/$indexedSamples samples; complete=$complete; ${promptText(checkedTrack)}")
        appendCapped(limitations, MAX_ITEMS, "  • ") { promptText(it) }
        appendCapped(findings, MAX_ITEMS, "  • ") { finding ->
            "[${finding.status.name}] ${finding.rule.id}; 구조 관측값·규격 조항 미확인; " +
                finding.details.take(4).joinToString("; ") { promptText(it) } +
                (if (finding.status == ConformanceStatus.WARN || finding.status == ConformanceStatus.FAIL)
                    "; 출처: " + (finding.rule.citation?.let { "${promptText(it.label())} ${promptText(it.url)}" }
                        ?: promptText(linkPendingSourceLabel(finding.rule.source))) else "")
        }
    }
    sb.appendLine("### [컨텐츠 검사 결과 (앱이 직접 검증한 사실)]")
    sb.appendLine(
        "아래 검사 결과는 관측 사실과 가능한 해석을 분리해 제공합니다. 가능한 원인과 후속 확인은 확정 진단이 아닌 가설입니다.",
    )

    sb.appendLine("### [규격 적합성 — 구현된 검사와 범위]")
    sb.appendLine("전체 규격 준수나 디코딩 성공을 의미하지 않습니다. AI는 아래의 결정적 판정을 생성하거나 변경할 수 없습니다.")
    snapshot.stages.forEach { (stage, state) ->
        sb.appendLine("- ${stage.name}: ${state.status.name}" + (state.reason?.let { " — ${promptText(it)}" } ?: ""))
    }
    val conformance = snapshot.conformance
    if (conformance == null) {
        if (snapshot.structureError != null) sb.appendLine("- 규격 검사: 검사 실패 · 미검증 — ${promptText(snapshot.structureError)}")
        else sb.appendLine("- 규격 검사: $pending")
    } else {
        val normative = conformance.findings.filterNot { it.rule.source.startsWith("unwrapMedia structural check") ||
            it.rule.source.contains("structural observation") }
        sb.appendLine("- 규격 검사 (${promptText(conformance.format)}): ${effectiveConformanceStatus(snapshot).name}; 프로필: ${promptText(conformance.profile)}")
        sb.appendLine("- 범위: " + ConformanceStatus.entries.joinToString(", ") { status ->
            "${status.name} ${normative.count { it.status == status }}"
        })
        sb.appendLine("- 별도 구조 관측: ${conformance.findings.size - normative.size}개")
        sb.appendCapped(normative, MAX_ITEMS, "  • ") { finding ->
            // Use a representable inclusive length, including ranges ending at Long.MAX_VALUE.
            val locations = finding.ranges.take(MAX_PROMPT_FACTS_PER_FINDING).joinToString(", ") { range ->
                val extent = range.last - range.first
                "${hex(range.first)}–${hex(range.last)} (${if (extent < Long.MAX_VALUE) "${extent + 1}B" else "${extent} + 1 B"})"
            }
            val details = finding.details.take(MAX_PROMPT_FACTS_PER_FINDING).joinToString("; ") { promptText(it) }
            "[${finding.status.name}] ${promptText(finding.rule.id)} — ${promptText(finding.rule.title)}; " +
                "규칙 근거: ${promptText(finding.rule.source)}; 참조: ${finding.rule.reference?.let(::promptText) ?: "없음"}; " +
                "검사 범위: ${promptText(finding.rule.scope)}; 관측: $details" +
                (if (locations.isEmpty()) "" else "; 바이트: $locations") +
                // Non-PASS rows name the exact spec text so the AI can point the user at it.
                (if (finding.status == ConformanceStatus.PASS) "" else finding.rule.citation
                    ?.let { "; 출처: ${promptText(it.label())} ${promptText(it.url)}" }
                    ?: conformanceSourceNote(finding.rule)?.let { "; 출처: ${promptText(it)}" } ?: "") +
                (if (finding.details.size > MAX_PROMPT_FACTS_PER_FINDING || finding.ranges.size > MAX_PROMPT_FACTS_PER_FINDING) "; 근거 일부 생략" else "")
        }
        sb.appendLine("- 제한 및 파서 근거 (규격 판정과 별도):")
        sb.appendCapped(conformance.limitations, MAX_ITEMS, "  • ", ::promptText)
    }

    // Every codec byte check gives the AI its coverage and limitations, so a partial check (complete=false)
    // never reads as clean (rule 7).
    snapshot.codecBitstreams.forEach {
        sb.appendCodecByteCheck(it.codec.label, it.checkedSamples, it.indexedSamples, it.complete, it.checkedTrack, it.limitations, it.findings)
    }
    snapshot.codecGaps.forEach { sb.appendLine("- 코덱 바이트 검사 범위 밖: ${promptText(it)}") }

    val normalizedFindings = contentCheckFindings(snapshot)
    sb.appendLine("### [검증된 사실]")
    sb.appendCapped(normalizedFindings, MAX_ITEMS, "- ") { finding ->
        val allFacts = (listOf(finding.summary) + finding.verifiedFacts).distinct()
        val facts = allFacts.take(MAX_PROMPT_FACTS_PER_FINDING).joinToString("; ") { promptText(it) }
        val omitted = allFacts.size - MAX_PROMPT_FACTS_PER_FINDING
        val source = when (val line = contentFindingSourceLine(finding).takeIf { finding.status == CheckStatus.WARN || finding.status == CheckStatus.FAIL }) {
            is CitationSourceLine.Link -> "; 출처: ${promptText(line.text)} ${promptText(line.url)}"
            is CitationSourceLine.Plain -> "; 출처: ${promptText(line.text)}"
            null -> ""
        }
        "[${finding.status.name}] ${finding.id}: $facts" + source +
            if (omitted > 0) "; 근거 일부 생략 (${omitted}개)" else ""
    }
    val hypotheses = normalizedFindings.filter { !it.hypothesis.isNullOrBlank() }
    sb.appendLine("### [가능성 / 가설]")
    if (hypotheses.isEmpty()) sb.appendLine("- 원인을 특정할 수 있는 해석이 없습니다.")
    else sb.appendCapped(hypotheses, MAX_ITEMS, "- ") { finding -> "${finding.id}: ${promptText(finding.hypothesis!!)}" }
    val nextChecks = normalizedFindings.flatMap { finding -> finding.nextChecks.map { finding.id to it } }
    sb.appendLine("### [다음 확인]")
    if (nextChecks.isEmpty()) sb.appendLine("- 추가 확인 항목이 없습니다.")
    else sb.appendCapped(nextChecks, MAX_ITEMS, "- ") { (id, check) -> "$id: ${promptText(check)}" }

    // 1) Structure
    if (type == MediaType.AUDIO) {
        val audioStructure = snapshot.audioStructure
        if (audioStructure == null) {
            if (snapshot.structureError != null) sb.appendLine("- 오디오 구조 검사: 검사 실패 — ${scrub(snapshot.structureError)}")
            else sb.appendLine("- 오디오 구조 검사: $pending")
        } else {
            val problems = audioStructure.items.filter { it.status in setOf(CheckStatus.FAIL, CheckStatus.WARN, CheckStatus.SKIP) }
            sb.appendLine("- 오디오 구조 검사 (${audioStructure.format}): ${audioStructure.overall.name} — 검사 항목 ${audioStructure.items.size}개 중 FAIL/WARN/SKIP ${problems.size}개")
            sb.appendCapped(problems, MAX_ITEMS, "  • ") { item ->
                val location = item.offset?.let { off -> " @ ${hex(off)}" + (item.length?.let { " (${it}B)" } ?: "") } ?: ""
                "[${item.status.name}] ${item.id} — ${scrub(item.title)}$location: ${scrub(item.detail)}"
            }
            val parserProblems = audioStructure.parserWarnings.filter { it.status in setOf(CheckStatus.FAIL, CheckStatus.WARN, CheckStatus.SKIP) }
            val parserStatus = when {
                parserProblems.any { it.status == CheckStatus.FAIL } -> CheckStatus.FAIL
                parserProblems.any { it.status == CheckStatus.WARN } -> CheckStatus.WARN
                parserProblems.any { it.status == CheckStatus.SKIP } -> CheckStatus.SKIP
                else -> CheckStatus.PASS
            }
            sb.appendLine("- 파서 경고: ${parserStatus.name} — 항목 ${audioStructure.parserWarnings.size}개 중 문제 ${parserProblems.size}개")
            sb.appendCapped(parserProblems, MAX_ITEMS, "  • ") { item ->
                val location = item.offset?.let { off -> " @ ${hex(off)}" + (item.length?.let { " (${it}B)" } ?: "") } ?: ""
                "[${item.status.name}] ${item.id} — ${scrub(item.title)}$location: ${scrub(item.detail)}"
            }
        }
    } else {
        val s = snapshot.structure
        if (s == null) {
            if (snapshot.structureError != null) sb.appendLine("- 구조 검사: 검사 실패 — ${scrub(snapshot.structureError)}")
            else sb.appendLine("- 구조 검사: $NOT_RUN")
        } else {
            val problems = s.items.filter { it.status == CheckStatus.FAIL || it.status == CheckStatus.WARN }
            sb.appendLine("- 구조 검사 (${s.format}): ${s.overall.name} — 검사 항목 ${s.items.size}개 중 FAIL/WARN ${problems.size}개")
            sb.appendCapped(problems, MAX_ITEMS, "  • ") { item ->
                val location = item.offset?.let { off ->
                    " @ ${hex(off)}" + (item.length?.let { " (${it}B)" } ?: "")
                } ?: ""
                "[${item.status.name}] ${item.id} — ${scrub(item.title)}$location: ${scrub(item.detail)}; 출처: $STRUCTURAL_OBSERVATION_LABEL"
            }
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
        snapshot.imageDecodeError != null -> sb.appendLine("- 이미지 디코딩: 검사 실패 — ${scrub(snapshot.imageDecodeError)}")
        type != null && type != MediaType.IMAGE -> sb.appendLine("- 이미지 디코딩: $NOT_APPLICABLE")
        else -> sb.appendLine("- 이미지 디코딩: $pending")
    }

    // 3) Motion photo
    val m = snapshot.motion
    when {
        snapshot.motionError != null -> sb.appendLine("- 모션포토: 분석 실패 — ${scrub(snapshot.motionError)}")
        m != null -> {
            val formats = m.detectedFormats.joinToString(", ") { it.name }.ifEmpty { "없음" }
            sb.appendLine("- 모션포토: ${m.verdictStatus().name} (감지 형식: $formats)")
            val citations = java.util.IdentityHashMap<SefCheckResult, SpecCitation>()
            val notes = java.util.IdentityHashMap<SefCheckResult, String>()
            val checks = buildList<Pair<String, SefCheckResult>> {
                m.googleSpec.forEach { spec ->
                    val row = spec.toSefCheckResult()
                    spec.citation?.let { citations[row] = it } ?: run { notes[row] = linkPendingSourceLabel(GOOGLE_MOTION_PHOTO_SOURCE) }
                    add("Google Motion Photo 1.0" to row)
                }
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
                m.decodeChecks.forEach { add("영상 디코딩" to it) }
            }.filter { (_, c) -> c.severity.toCheckStatus().let { it != CheckStatus.PASS && it != CheckStatus.INFO } }
            sb.appendCapped(checks, MAX_ITEMS, "  • ") { (section, c) ->
                "[${c.severity.toCheckStatus().name}] $section — ${scrub(c.label)}: ${scrub(c.detail)}" +
                    (citations[c]?.let { "; 출처: ${scrub(it.label())} ${scrub(it.url)}" }
                        ?: "; 출처: ${notes[c] ?: when (section) {
                            "영상 디코딩" -> DECODER_RESULT_SOURCE_LABEL
                            else -> SAMSUNG_SEF_SOURCE_LABEL
                        }}")
            }
        }
        !snapshot.motionDetected -> sb.appendLine("- 모션포토: $NOT_APPLICABLE (모션포토 데이터 미감지)")
        else -> sb.appendLine("- 모션포토: $pending")
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
        snapshot.videoError != null -> sb.appendLine("- 영상 디코딩: 검사 실패 — ${scrub(snapshot.videoError)}")
        type != null && type != MediaType.VIDEO -> sb.appendLine("- 영상 디코딩: $NOT_APPLICABLE")
        else -> sb.appendLine("- 영상 디코딩: $pending")
    }
    val audio = snapshot.audio
    when {
        audio != null -> {
            sb.appendLine("- 오디오 디코딩 (모든 오디오 트랙): " + if (audio.noAudio) "해당 없음 — 오디오 트랙 없음" else audio.status.name)
            sb.appendCapped(audio.logs, MAX_LOG_LINES, "  • ") { scrub(it) }
            if (audio.logsTruncated) sb.appendLine("  • 프로브 로그 일부 생략")
            sb.appendCapped(audio.streams, MAX_ITEMS, "") { r ->
                buildString {
                    appendLine("  • 트랙 #${r.stream.index} ${r.stream.codec}: ${r.status.name}; 프레임 ${r.decodedFrames}, 샘플 ${r.decodedSamples} (채널당)")
                    appendLine("    샘플레이트: 선언 ${r.stream.sampleRate ?: "unknown"} / 디코딩 ${r.observedSampleRates}; 채널: 선언 ${r.stream.channels ?: "unknown"} / 디코딩 ${r.observedChannels}")
                    appendLine("    길이: 선언 ${r.stream.durationSeconds ?: "unknown"} / 디코딩 ${r.decodedDurationSeconds} s; 허용 오차 ${r.durationToleranceSeconds} s")
                    r.mismatches.forEach { appendLine("    ${scrub(it)}") }
                    appendCapped(r.logs, MAX_LOG_LINES, "    ") { scrub(it) }
                    if (r.logsTruncated) appendLine("    디코더 로그 일부 생략")
                }.trimEnd()
            }
        }
        snapshot.audioError != null -> sb.appendLine("- 오디오 디코딩: 검사 실패 — ${scrub(snapshot.audioError)}")
        type != null && type != MediaType.AUDIO && type != MediaType.VIDEO -> sb.appendLine("- 오디오 디코딩: $NOT_APPLICABLE")
        else -> sb.appendLine("- 오디오 디코딩: $pending")
    }
    return sb.toString()
}
