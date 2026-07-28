package com.multiviewer.cli

import java.io.File

fun runDumpCommand(args: List<String>): Int {
    val path = args.firstOrNull()
    if (path == null) {
        System.err.println("Usage: unwrapMedia dump <file>")
        return 1
    }
    return when (val result = dumpFile(File(path))) {
        is DumpResult.Success -> {
            println(result.json)
            0
        }
        is DumpResult.Failure -> {
            System.err.println(result.message)
            1
        }
    }
}
