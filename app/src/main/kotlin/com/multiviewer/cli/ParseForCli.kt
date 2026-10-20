package com.multiviewer.cli

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.confidentFormat
import com.multiviewer.parser.parseFile
import com.multiviewer.ui.AUDIO_EXTENSIONS
import com.multiviewer.ui.IMAGE_EXTENSIONS
import com.multiviewer.ui.VIDEO_EXTENSIONS
import java.io.File

sealed class CliParseResult {
    data class Success(val file: File, val root: BoxNode) : CliParseResult()
    data class Failure(val message: String) : CliParseResult()
}

// Shared by dumpFile and checkFile -- both need "does the file exist, is it a supported
// format (by extension or content), does parseFile succeed" before doing anything format-specific with the result.
// Reuses the same extension allow-lists the GUI validates against (AppState.kt) rather than
// duplicating them -- raw pixel formats are deliberately excluded (RAW_PIXEL_EXTENSIONS, not
// imported here): they need width/height/format parameters this simple CLI has no way to supply.
fun parseForCli(file: File): CliParseResult {
    if (!file.exists()) {
        return CliParseResult.Failure("File not found: ${file.path}")
    }
    val extension = file.extension.lowercase()
    val supported = IMAGE_EXTENSIONS + VIDEO_EXTENSIONS + AUDIO_EXTENSIONS
    // Same rule as the GUI (AppState.openFile): a wrong or missing extension is fine when the
    // bytes are a format we recognize with confidence.
    if (extension !in supported && confidentFormat(file) == null) {
        return CliParseResult.Failure("Unsupported file: .$extension is not a supported extension and the content is not a recognized format (supported: ${supported.joinToString(", ")})")
    }
    return try {
        CliParseResult.Success(file, parseFile(file))
    } catch (e: Exception) {
        CliParseResult.Failure("Failed to parse ${file.path}: ${e.message ?: e.toString()}")
    }
}
