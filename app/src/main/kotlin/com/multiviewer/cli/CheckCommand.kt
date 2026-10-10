package com.multiviewer.cli

import com.multiviewer.util.ClipboardUtil
import java.io.File

fun runCheckCommand(args: List<String>): Int {
    var showPrompt = false
    var copyToClipboard = false
    var filePath: String? = null
    var casePath: String? = null
    var decode = false

    var index = 0
    while (index < args.size) {
        val arg = args[index]
        when (arg) {
            "-p", "--prompt", "--ai" -> showPrompt = true
            "-c", "--clipboard", "--copy" -> copyToClipboard = true
            "--json" -> showPrompt = false
            "--decode" -> decode = true
            "--case" -> {
                val path = args.getOrNull(index + 1)
                if (path == null || path.startsWith("-")) {
                    System.err.println("Missing output path after --case")
                    return 1
                }
                casePath = path
                index++
            }
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
        index++
    }

    if (filePath == null) {
        System.err.println("Usage: unwrapMedia check <file> [--prompt] [--clipboard] [--decode] [--case <output.json>]")
        return 1
    }

    if (decode && showPrompt) {
        System.err.println("--decode cannot be combined with --prompt; use JSON or --case for decoding results")
        return 1
    }
    return when (val result = checkFile(File(filePath), includeCase = casePath != null, decode = decode)) {
        is CheckResult.Success -> {
            if (casePath != null) {
                val outputFile = File(casePath)
                try {
                    if (!outputFile.createNewFile()) {
                        System.err.println("Case output already exists: ${outputFile.path}")
                        return 1
                    }
                    outputFile.writeText(result.analysisCaseJson ?: error("Analysis case was not generated"), Charsets.UTF_8)
                } catch (e: Exception) {
                    outputFile.delete()
                    System.err.println("Failed to write analysis case ${outputFile.path}: ${e.message ?: e.toString()}")
                    return 1
                }
            }
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
          --case <output.json>      Save a reproducible JSON analysis case (does not overwrite existing files)
          --decode                 Videos: decode the first video stream and map packets (up to 30 minutes per stage)
                                   Images: also decode with FFmpeg and Skia, and analyze an embedded motion photo
                                   Audio/video: decode all audio streams and cross-check duration, sample rate and channels
                                   (image structure checks always run)
          -h, --help               Show this help message
        """.trimIndent(),
    )
}
