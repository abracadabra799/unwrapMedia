package com.multiviewer.cli

import com.multiviewer.parser.parseFile
import com.multiviewer.ui.AUDIO_EXTENSIONS
import com.multiviewer.ui.IMAGE_EXTENSIONS
import com.multiviewer.ui.VIDEO_EXTENSIONS
import java.io.File

sealed class DumpResult {
    data class Success(val json: String) : DumpResult()
    data class Failure(val message: String) : DumpResult()
}

// Reuses the same extension allow-lists the GUI validates against (AppState.kt) rather than
// duplicating them -- raw pixel formats are deliberately excluded (RAW_PIXEL_EXTENSIONS, not
// imported here): they need width/height/format parameters this simple CLI has no way to supply.
fun dumpFile(file: File): DumpResult {
    if (!file.exists()) {
        return DumpResult.Failure("File not found: ${file.path}")
    }
    val extension = file.extension.lowercase()
    val supported = IMAGE_EXTENSIONS + VIDEO_EXTENSIONS + AUDIO_EXTENSIONS
    if (extension !in supported) {
        return DumpResult.Failure("Unsupported extension: .$extension (supported: ${supported.joinToString(", ")})")
    }
    return try {
        val root = parseFile(file)
        DumpResult.Success(buildDumpJson(file, root))
    } catch (e: Exception) {
        DumpResult.Failure("Failed to parse ${file.path}: ${e.message ?: e.toString()}")
    }
}
