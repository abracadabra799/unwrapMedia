package com.multiviewer.cli

import java.io.File

fun runCheckCommand(args: List<String>): Int {
    val path = args.firstOrNull()
    if (path == null) {
        System.err.println("Usage: unwrapMedia check <file>")
        return 1
    }
    return when (val result = checkFile(File(path))) {
        is CheckResult.Success -> {
            println(result.json)
            0
        }
        is CheckResult.Failure -> {
            System.err.println(result.message)
            1
        }
    }
}
