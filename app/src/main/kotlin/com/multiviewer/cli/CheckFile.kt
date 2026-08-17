package com.multiviewer.cli

import com.multiviewer.parser.WarningEntry
import com.multiviewer.parser.collectWarnings
import java.io.File

sealed class CheckResult {
    data class Success(
        val json: String,
        val prompt: String,
        val warningCount: Int,
    ) : CheckResult()
    data class Failure(val message: String) : CheckResult()
}

fun checkFile(file: File): CheckResult = when (val result = parseForCli(file)) {
    is CliParseResult.Success -> try {
        val warnings = collectWarnings(result.root)
        val json = buildCheckJson(result.file, warnings)
        val prompt = AiDiagnosticPromptBuilder.buildPrompt(result.file, result.root, warnings)
        CheckResult.Success(json = json, prompt = prompt, warningCount = warnings.size)
    } catch (e: Exception) {
        CheckResult.Failure("Failed to parse ${file.path}: ${e.message ?: e.toString()}")
    }
    is CliParseResult.Failure -> CheckResult.Failure(result.message)
}

fun buildCheckJson(file: File, warnings: List<WarningEntry>): String {
    val wrapper = JsonValue.JObject(
        listOf(
            "file" to JsonValue.JString(file.name),
            "warningCount" to JsonValue.JNumber(warnings.size.toLong()),
            "warnings" to JsonValue.JArray(warnings.map { it.toJsonValue() }),
        ),
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
