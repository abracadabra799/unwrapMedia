package com.multiviewer.cli

import java.io.File

sealed class DumpResult {
    data class Success(val json: String) : DumpResult()
    data class Failure(val message: String) : DumpResult()
}

fun dumpFile(file: File): DumpResult = when (val result = parseForCli(file)) {
    is CliParseResult.Success -> try {
        DumpResult.Success(buildDumpJson(result.file, result.root))
    } catch (e: Exception) {
        DumpResult.Failure("Failed to parse ${file.path}: ${e.message ?: e.toString()}")
    }
    is CliParseResult.Failure -> DumpResult.Failure(result.message)
}
