package com.multiviewer.cli

import com.multiviewer.parser.WarningEntry
import com.multiviewer.parser.collectWarnings
import com.multiviewer.parser.MediaSummary
import com.multiviewer.parser.buildMediaSummary
import com.multiviewer.ui.I18n
import java.io.File
import java.security.MessageDigest
import com.multiviewer.ui.VideoIntegrityReport
import com.multiviewer.ui.inspectVideoIntegrity
import com.multiviewer.ui.VIDEO_EXTENSIONS
import kotlinx.coroutines.runBlocking

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
        require(!decode || file.extension.lowercase() in VIDEO_EXTENSIONS) { "--decode requires a video file" }
        val integrity = integrityReport ?: if (decode) runBlocking { inspectVideoIntegrity(file) } else null
        val warnings = collectWarnings(result.root)
        val json = buildCheckJson(result.file, warnings, integrity)
        val prompt = AiDiagnosticPromptBuilder.buildPrompt(result.file, result.root, warnings)
        val caseJson = if (includeCase) {
            buildAnalysisCaseJson(result.file, warnings, buildMediaSummary(result.root, result.file), integrityReport = integrity)
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
                    "videoIntegrity" to (integrityReport?.toJsonValue() ?: JsonValue.JObject(listOf(
                        "decodeStatus" to JsonValue.JString("NOT_RUN"),
                        "packetStatus" to JsonValue.JString("NOT_RUN"),
                    ))),
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

fun buildCheckJson(file: File, warnings: List<WarningEntry>, integrityReport: VideoIntegrityReport? = null): String {
    val wrapper = JsonValue.JObject(
        listOf(
            "file" to JsonValue.JString(file.name),
            "warningCount" to JsonValue.JNumber(warnings.size.toLong()),
            "warnings" to JsonValue.JArray(warnings.map { it.toJsonValue() }),
        ) + if (integrityReport != null) listOf("videoIntegrity" to integrityReport.toJsonValue()) else emptyList(),
    )
    return wrapper.render()
}

private fun WarningEntry.toJsonValue(): JsonValue = JsonValue.JObject(
    listOf(
        "type" to JsonValue.JString(node.type),
        "offset" to JsonValue.JNumber(node.offset),
        "message" to JsonValue.JString(warning),
    ),
)
