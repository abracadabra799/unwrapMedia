package com.multiviewer.cli

import com.multiviewer.parser.WarningEntry
import com.multiviewer.parser.collectWarnings
import com.multiviewer.parser.integrity.ImageIntegrityChecker
import com.multiviewer.ui.IMAGE_EXTENSIONS
import com.multiviewer.ui.MotionPhotoIntegrityAnalyzer
import com.multiviewer.ui.hasMotionPhotoData
import com.multiviewer.ui.inspectImageDecode
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Locale

sealed class CheckResult {
    data class Success(
        val json: String,
        val prompt: String,
        val warningCount: Int,
    ) : CheckResult()
    data class Failure(val message: String) : CheckResult()
}

fun checkFile(file: File, decode: Boolean = false): CheckResult = when (val result = parseForCli(file)) {
    is CliParseResult.Success -> try {
        val warnings = collectWarnings(result.root)
        val imageIntegrity = if (result.file.extension.lowercase(Locale.US) in IMAGE_EXTENSIONS) {
            val structure = ImageIntegrityChecker.check(result.file, result.root)
            val decodeReport = if (decode) runBlocking { inspectImageDecode(result.file, structure) } else null
            val detected = hasMotionPhotoData(result.root)
            val motionReport = if (decode && detected) MotionPhotoIntegrityAnalyzer.analyze(result.file, result.root) else null
            imageIntegrityJson(structure, decodeReport, motionPhotoJson(detected, motionReport))
        } else {
            require(!decode) { "--decode requires an image file" }
            null
        }
        val json = buildCheckJson(result.file, warnings, imageIntegrity)
        val prompt = AiDiagnosticPromptBuilder.buildPrompt(result.file, result.root, warnings)
        CheckResult.Success(json = json, prompt = prompt, warningCount = warnings.size)
    } catch (e: Exception) {
        CheckResult.Failure("Failed to parse ${file.path}: ${e.message ?: e.toString()}")
    }
    is CliParseResult.Failure -> CheckResult.Failure(result.message)
}

fun buildCheckJson(file: File, warnings: List<WarningEntry>, imageIntegrity: JsonValue? = null): String {
    val wrapper = JsonValue.JObject(
        listOf(
            "file" to JsonValue.JString(file.name),
            "warningCount" to JsonValue.JNumber(warnings.size.toLong()),
            "warnings" to JsonValue.JArray(warnings.map { it.toJsonValue() }),
        ) + if (imageIntegrity != null) listOf("imageIntegrity" to imageIntegrity) else emptyList(),
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
