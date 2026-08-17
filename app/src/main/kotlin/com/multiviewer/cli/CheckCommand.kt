package com.multiviewer.cli

import com.multiviewer.util.ClipboardUtil
import java.io.File

fun runCheckCommand(args: List<String>): Int {
    var showPrompt = false
    var copyToClipboard = false
    var filePath: String? = null

    for (arg in args) {
        when (arg) {
            "-p", "--prompt", "--ai" -> showPrompt = true
            "-c", "--clipboard", "--copy" -> copyToClipboard = true
            "--json" -> showPrompt = false
            "-h", "--help" -> {
                printCheckHelp()
                return 0
            }
            else -> {
                if (!arg.startsWith("-") && filePath == null) {
                    filePath = arg
                }
            }
        }
    }

    if (filePath == null) {
        System.err.println("Usage: unwrapMedia check <file> [--prompt] [--clipboard]")
        return 1
    }

    return when (val result = checkFile(File(filePath))) {
        is CheckResult.Success -> {
            val output = if (showPrompt) result.prompt else result.json
            println(output)

            if (copyToClipboard) {
                if (ClipboardUtil.copyToClipboard(output)) {
                    System.err.println("[unwrapMedia] Output copied to OS clipboard.")
                } else {
                    System.err.println("[unwrapMedia] Warning: Could not access OS clipboard.")
                }
            }
            0
        }
        is CheckResult.Failure -> {
            System.err.println(result.message)
            1
        }
    }
}

private fun printCheckHelp() {
    println(
        """
        unwrapMedia check - Inspect media file structure and generate AI diagnostic prompts

        Usage:
          unwrapMedia check <file> [options]

        Options:
          -p, --prompt, --ai       Generate a structured AI diagnostic prompt with domain context
          -c, --clipboard, --copy  Copy the output directly to the OS clipboard
          --json                   Output raw JSON inspection results (default)
          -h, --help               Show this help message
        """.trimIndent(),
    )
}
