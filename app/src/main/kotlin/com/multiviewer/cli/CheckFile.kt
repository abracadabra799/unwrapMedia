package com.multiviewer.cli

import com.multiviewer.ui.MediaType
import com.multiviewer.ui.containerConformanceFor
import com.multiviewer.parser.conformance.ConformanceReport
import com.multiviewer.parser.conformance.ConformanceStatus
import com.multiviewer.parser.conformance.SpecCitation
import com.multiviewer.parser.contentMediaType
import com.multiviewer.parser.WarningEntry
import com.multiviewer.parser.collectWarnings
import com.multiviewer.parser.MediaSummary
import com.multiviewer.parser.buildMediaSummary
import com.multiviewer.parser.integrity.ImageIntegrityChecker
import com.multiviewer.ui.I18n
import com.multiviewer.ui.AudioIntegrityReport
import com.multiviewer.ui.inspectAudioIntegrity
import com.multiviewer.ui.MotionPhotoIntegrityAnalyzer
import com.multiviewer.ui.VideoIntegrityReport
import com.multiviewer.ui.hasMotionPhotoData
import com.multiviewer.ui.inspectImageDecode
import com.multiviewer.ui.inspectVideoIntegrity
import com.multiviewer.ui.summaryMayHaveVideoTrack
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.MessageDigest
import java.util.Locale

sealed class CheckResult {
    data class Success(
        val json: String,
        val prompt: String,
        val warningCount: Int,
        val analysisCaseJson: String? = null,
    ) : CheckResult()
    data class Failure(val message: String) : CheckResult()
}

fun checkFile(file: File, includeCase: Boolean = false, decode: Boolean = false,
              integrityReport: VideoIntegrityReport? = null): CheckResult = when (val result = parseForCli(file)) {
    is CliParseResult.Success -> try {
        val extension = result.file.extension.lowercase(Locale.US)
        val mediaType = contentMediaType(result.file)
        val isVideo = mediaType == MediaType.VIDEO
        val isImage = mediaType == MediaType.IMAGE
        val isAudio = mediaType == MediaType.AUDIO
        require(!decode || isVideo || isImage || isAudio) { "--decode requires a video, image or audio file" }
        val summary by lazy { buildMediaSummary(result.root, result.file) }
        // An audio-only .mp4 is VIDEO by extension; there is no video stream to decode.
        val integrity = integrityReport ?: if (decode && isVideo && summaryMayHaveVideoTrack(summary)) {
            runBlocking { inspectVideoIntegrity(result.file) }
        } else null
        val audio = if (decode && (isAudio || isVideo)) runBlocking { inspectAudioIntegrity(result.file) } else null
        val warnings = collectWarnings(result.root)
        val imageIntegrity = if (isImage) {
            val structure = ImageIntegrityChecker.check(result.file, result.root)
            val decodeReport = if (decode) runBlocking { inspectImageDecode(result.file, structure) } else null
            val detected = hasMotionPhotoData(result.root)
            var motionReport: com.multiviewer.ui.MotionPhotoIntegrityReport? = null
            var motionError: String? = null
            if (decode && detected) {
                try {
                    motionReport = MotionPhotoIntegrityAnalyzer.analyze(result.file, result.root)
                } catch (e: Exception) {
                    motionError = e.message ?: e.toString()
                }
            }
            imageIntegrityJson(structure, decodeReport, motionPhotoJson(detected, motionReport, result.file, motionError))
        } else {
            null
        }
        val containerConformance = containerConformanceFor(result.file)
        val json = buildCheckJson(result.file, warnings, integrity, imageIntegrity, audio,
            containerConformance = containerConformance)
        val prompt = AiDiagnosticPromptBuilder.buildPrompt(result.file, result.root, warnings)
        val caseJson = if (includeCase) {
            buildAnalysisCaseJson(
                result.file, warnings, summary,
                integrityReport = integrity, imageIntegrity = imageIntegrity, audioIntegrity = audio,
                containerConformance = containerConformance,
            )
        } else {
            null
        }
        CheckResult.Success(json = json, prompt = prompt, warningCount = warnings.size, analysisCaseJson = caseJson)
    } catch (e: Exception) {
        CheckResult.Failure("Failed to parse ${file.path}: ${e.message ?: e.toString()}")
    }
    is CliParseResult.Failure -> CheckResult.Failure(result.message)
}

fun buildAnalysisCaseJson(
    file: File,
    warnings: List<WarningEntry>,
    summary: MediaSummary,
    appVersion: String = I18n.APP_VERSION,
    integrityReport: VideoIntegrityReport? = null,
    imageIntegrity: JsonValue? = null,
    audioIntegrity: AudioIntegrityReport? = null,
    workflow: JsonValue? = null,
    containerConformance: ConformanceReport? = null,
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    val sha256 = digest.digest().joinToString("") { "%02x".format(it) }

    val case = JsonValue.JObject(
        listOf(
            "schemaVersion" to JsonValue.JNumber(1),
            "tool" to JsonValue.JObject(
                listOf(
                    "name" to JsonValue.JString("unwrapMedia"),
                    "version" to JsonValue.JString(appVersion),
                ),
            ),
            "file" to JsonValue.JObject(
                listOf(
                    "name" to JsonValue.JString(file.name),
                    "sizeBytes" to JsonValue.JNumber(file.length()),
                    "sha256" to JsonValue.JString(sha256),
                ),
            ),
            "analysis" to JsonValue.JObject(
                listOf(
                    "category" to JsonValue.JString(summary.category.name),
                    "audioIntegrity" to (audioIntegrity?.toJsonValue() ?: JsonValue.JObject(listOf(
                        "status" to JsonValue.JString("NOT_RUN"),
                    ))),
                    "videoIntegrity" to (integrityReport?.toJsonValue() ?: JsonValue.JObject(listOf(
                        "decodeStatus" to JsonValue.JString("NOT_RUN"),
                        "packetStatus" to JsonValue.JString("NOT_RUN"),
                    ))),
                ) + (if (imageIntegrity != null) listOf("imageIntegrity" to imageIntegrity) else emptyList()) +
                    (if (containerConformance != null) listOf("containerConformance" to containerConformanceJson(containerConformance, file))
                    else emptyList()) +
                    (if (workflow != null) listOf("workflow" to workflow) else emptyList()) + listOf(
                    "summary" to JsonValue.JArray(summary.sections.filterNot { section ->
                        section.title.equals("GPS Location", ignoreCase = true)
                    }.map { section ->
                        JsonValue.JObject(
                            listOf(
                                "title" to JsonValue.JString(section.title),
                                "fields" to JsonValue.JArray(section.fields.map { field ->
                                    JsonValue.JObject(
                                        listOf(
                                            "label" to JsonValue.JString(field.label),
                                            "value" to JsonValue.JString(field.value),
                                        ),
                                    )
                                }),
                            ),
                        )
                    }),
                    "warningCount" to JsonValue.JNumber(warnings.size.toLong()),
                    "warnings" to JsonValue.JArray(warnings.mapIndexed { index, warning ->
                        JsonValue.JObject(
                            listOf(
                                "id" to JsonValue.JString("W-%03d".format(index + 1)),
                                "severity" to JsonValue.JString(AiDiagnosticPromptBuilder.determineSeverity(warning.node.type, warning.warning)),
                                "type" to JsonValue.JString(warning.node.type),
                                "offset" to JsonValue.JNumber(warning.node.offset),
                                "message" to JsonValue.JString(warning.warning),
                            ),
                        )
                    }),
                ),
            ),
        ),
    )
    return case.render()
}

fun buildCheckJson(
    file: File,
    warnings: List<WarningEntry>,
    integrityReport: VideoIntegrityReport? = null,
    imageIntegrity: JsonValue? = null,
    audioIntegrity: AudioIntegrityReport? = null,
    workflow: JsonValue? = null,
    containerConformance: ConformanceReport? = null,
): String {
    val wrapper = JsonValue.JObject(
        listOf(
            "file" to JsonValue.JString(file.name),
            "warningCount" to JsonValue.JNumber(warnings.size.toLong()),
            "warnings" to JsonValue.JArray(warnings.map { it.toJsonValue() }),
        ) + (if (integrityReport != null) listOf("videoIntegrity" to integrityReport.toJsonValue()) else emptyList()) +
            (if (imageIntegrity != null) listOf("imageIntegrity" to imageIntegrity) else emptyList()) +
            (if (audioIntegrity != null) listOf("audioIntegrity" to audioIntegrity.toJsonValue()) else emptyList()) +
            (if (containerConformance != null) listOf("containerConformance" to containerConformanceJson(containerConformance, file))
            else emptyList()) +
            (if (workflow != null) listOf("workflow" to workflow) else emptyList()),
    )
    return wrapper.render()
}

/** Container conformance (ISO-BMFF/QuickTime for MP4/MOV/M4A, HEIF/AVIF for images); status never reads PASS on an inspection error. */
fun containerConformanceJson(report: ConformanceReport, file: File): JsonValue = JsonValue.JObject(
    listOf(
        "status" to JsonValue.JString(report.overall.name),
        "format" to JsonValue.JString(report.format),
        "profile" to JsonValue.JString(report.profile),
    ) + (report.inspectionError?.let { listOf("inspectionError" to JsonValue.JString(scrubPaths(it, file).take(1_000))) }
        ?: emptyList()) + listOf(
        "limitations" to JsonValue.JArray(report.limitations.map { JsonValue.JString(scrubPaths(it, file).take(1_000)) }),
        "findings" to JsonValue.JArray(report.findings.map { finding ->
            JsonValue.JObject(
                listOf(
                    "ruleId" to JsonValue.JString(finding.rule.id),
                    "status" to JsonValue.JString(finding.status.name),
                    "title" to JsonValue.JString(finding.rule.title),
                    "scope" to JsonValue.JString(finding.rule.scope),
                    "source" to JsonValue.JString(finding.rule.source),
                    "reference" to (finding.rule.reference?.let { JsonValue.JString(it) } ?: JsonValue.JNull),
                    "details" to JsonValue.JArray(finding.details.take(30).map { JsonValue.JString(scrubPaths(it, file).take(1_000)) }),
                    "ranges" to JsonValue.JArray(finding.ranges.take(30).map { range ->
                        JsonValue.JObject(listOf(
                            "start" to JsonValue.JNumber(range.first),
                            "endInclusive" to JsonValue.JNumber(range.last),
                        ))
                    }),
                ) + (finding.rule.citation?.let { listOf("citation" to citationJson(it)) }
                    ?: com.multiviewer.ui.conformanceSourceNote(finding.rule)
                        ?.takeIf { finding.status == ConformanceStatus.WARN || finding.status == ConformanceStatus.FAIL }
                        ?.let { listOf("sourceNote" to JsonValue.JString(it)) }
                    ?: emptyList()),
            )
        }),
    ),
)

/** `{document, section, page?, url, verified}`; `page` is omitted when the clause page was not verified. */
internal fun citationJson(citation: SpecCitation): JsonValue = JsonValue.JObject(
    listOf(
        "document" to JsonValue.JString(citation.document),
        "section" to JsonValue.JString(citation.section),
    ) + (citation.page?.let { listOf("page" to JsonValue.JNumber(it.toLong())) } ?: emptyList()) + listOf(
        "url" to JsonValue.JString(citation.url),
        "verified" to JsonValue.JBoolean(citation.verified),
    ),
)

private fun WarningEntry.toJsonValue(): JsonValue = JsonValue.JObject(
    listOf(
        "type" to JsonValue.JString(node.type),
        "offset" to JsonValue.JNumber(node.offset),
        "message" to JsonValue.JString(warning),
    ),
)
