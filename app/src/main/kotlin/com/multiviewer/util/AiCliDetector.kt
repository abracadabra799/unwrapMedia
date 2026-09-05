package com.multiviewer.util

import java.awt.Desktop
import java.io.File
import java.net.URI

enum class AiCliType(val displayName: String, val binaryName: String) {
    CLAUDE("Claude Code", "claude"),
    CODEX("Codex", "codex"),
    GEMINI("Gemini CLI", "gemini"),
    AGY("Antigravity (agy)", "agy");

    val isAvailable: Boolean
        get() = AiCliDetector.findBinary(binaryName) != null
}

object AiCliDetector {
    private val candidatePaths = listOf(
        "/usr/local/bin",
        "/opt/homebrew/bin",
        "${System.getProperty("user.home")}/.local/bin",
        "${System.getProperty("user.home")}/bin",
        "${System.getProperty("user.home")}/.nvm/current/bin",
        "${System.getProperty("user.home")}/.cargo/bin",
        "/usr/bin",
        "/bin",
    )

    fun findBinary(name: String): String? {
        for (dir in candidatePaths) {
            val file = File(dir, name)
            if (file.exists() && file.canExecute()) {
                return file.absolutePath
            }
        }

        val envPath = System.getenv("PATH") ?: ""
        for (dir in envPath.split(File.pathSeparator)) {
            val file = File(dir, name)
            if (file.exists() && file.canExecute()) {
                return file.absolutePath
            }
        }

        return try {
            val isWindows = System.getProperty("os.name").lowercase().contains("win")
            val cmd = if (isWindows) listOf("where", name) else listOf("which", name)
            val process = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            if (process.waitFor() == 0 && output.isNotEmpty() && File(output).exists()) {
                output
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Launches an interactive AI CLI in a new Terminal window passing the prompt.
     */
    fun launchInteractiveCli(type: AiCliType, promptText: String, workingDir: File? = null): Boolean {
        val binPath = findBinary(type.binaryName) ?: return false
        return try {
            val dirPath = workingDir?.takeIf { it.exists() }?.absolutePath ?: System.getProperty("user.home")
            val osName = System.getProperty("os.name").lowercase()

            val tempPromptFile = File.createTempFile("ai_prompt_", ".txt")
            tempPromptFile.writeText(promptText)

            if (osName.contains("mac")) {
                val runnerScript = File.createTempFile("ai_runner_", ".sh")
                val cmdArg = when (type) {
                    AiCliType.CLAUDE -> "\"$binPath\" \"\$PROMPT\""
                    AiCliType.CODEX -> "\"$binPath\" \"\$PROMPT\""
                    AiCliType.GEMINI -> "\"$binPath\" \"\$PROMPT\""
                    AiCliType.AGY -> "\"$binPath\" -i \"\$PROMPT\""
                }

                // Load prompt safely into variable, then pass as single quoted argument
                runnerScript.writeText(
                    """
                    #!/bin/bash
                    export PATH="/opt/homebrew/bin:/opt/homebrew/sbin:/usr/local/bin:${'$'}HOME/.local/bin:${'$'}PATH"
                    cd "$dirPath"
                    PROMPT=${'$'}(cat "${tempPromptFile.absolutePath}")
                    $cmdArg
                    """.trimIndent() + "\n"
                )
                runnerScript.setExecutable(true)

                val appleScript = """
                    tell application "Terminal"
                        do script "${runnerScript.absolutePath}"
                        activate
                    end tell
                """.trimIndent()

                val process = ProcessBuilder("osascript", "-e", appleScript).start()
                process.waitFor() == 0
            } else if (osName.contains("win")) {
                // To prevent PowerShell "TerminatorExpectedAtEndOfString" syntax error caused by
                // multiline newlines, backticks, quotes, and special characters in promptText,
                // generate a standalone UTF-8 runner .ps1 script file and execute it.
                val runnerScript = File.createTempFile("ai_runner_", ".ps1")
                val cmdInvocation = when (type) {
                    AiCliType.AGY -> "& \"$binPath\" -i \$promptText"
                    else -> "& \"$binPath\" \$promptText"
                }

                // Read the prompt from the temp file safely inside PowerShell
                val scriptContent = """
                    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
                    Set-Location -LiteralPath "$dirPath"
                    ${'$'}promptText = Get-Content -LiteralPath "${tempPromptFile.absolutePath}" -Raw -Encoding UTF8
                    $cmdInvocation
                """.trimIndent() + "\r\n"

                runnerScript.writeText(scriptContent, Charsets.UTF_8)

                // Launch PowerShell in a new window running the script with -ExecutionPolicy Bypass -NoExit
                ProcessBuilder(
                    "cmd.exe", "/c", "start", "powershell",
                    "-NoExit", "-ExecutionPolicy", "Bypass",
                    "-File", runnerScript.absolutePath
                ).start()
                true
            } else {
                val runnerScript = File.createTempFile("ai_runner_", ".sh")
                val cmdArg = when (type) {
                    AiCliType.CLAUDE -> "\"$binPath\" \"\$PROMPT\""
                    AiCliType.CODEX -> "\"$binPath\" \"\$PROMPT\""
                    AiCliType.GEMINI -> "\"$binPath\" \"\$PROMPT\""
                    AiCliType.AGY -> "\"$binPath\" -i \"\$PROMPT\""
                }
                runnerScript.writeText(
                    """
                    #!/bin/bash
                    cd "$dirPath"
                    PROMPT=${'$'}(cat "${tempPromptFile.absolutePath}")
                    $cmdArg
                    exec ${'$'}SHELL
                    """.trimIndent() + "\n"
                )
                runnerScript.setExecutable(true)

                val terminals = listOf("x-terminal-emulator", "gnome-terminal", "konsole", "xterm")
                var launched = false
                for (term in terminals) {
                    try {
                        ProcessBuilder(term, "-e", runnerScript.absolutePath).start()
                        launched = true
                        break
                    } catch (_: Throwable) {}
                }
                launched
            }
        } catch (e: Throwable) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Opens a web AI chat service in Google Chrome (where company accounts are logged in)
     * or falls back to the system default browser.
     */
    fun openWebAi(url: String): Boolean {
        val osName = System.getProperty("os.name").lowercase()
        try {
            if (osName.contains("win")) {
                // Windows: Check common Chrome install paths or chrome.exe
                val chromePaths = listOf(
                    "${System.getenv("ProgramFiles")}\\Google\\Chrome\\Application\\chrome.exe",
                    "${System.getenv("ProgramFiles(x86)")}\\Google\\Chrome\\Application\\chrome.exe",
                    "${System.getenv("LOCALAPPDATA")}\\Google\\Chrome\\Application\\chrome.exe",
                )
                val chromeBin = chromePaths.firstOrNull { File(it).exists() }
                if (chromeBin != null) {
                    ProcessBuilder(chromeBin, url).start()
                    return true
                }
                // Try launching via cmd start chrome
                try {
                    val p = ProcessBuilder("cmd.exe", "/c", "start", "chrome", url).start()
                    if (p.waitFor() == 0) return true
                } catch (_: Throwable) {}
            } else if (osName.contains("mac")) {
                // macOS: Open via Google Chrome application bundle
                val chromeApp = File("/Applications/Google Chrome.app")
                if (chromeApp.exists()) {
                    val p = ProcessBuilder("open", "-a", "Google Chrome", url).start()
                    if (p.waitFor() == 0) return true
                }
            } else {
                // Linux: Try google-chrome or google-chrome-stable
                val linuxChromium = listOf("google-chrome", "google-chrome-stable", "chromium-browser", "chromium")
                for (b in linuxChromium) {
                    try {
                        ProcessBuilder(b, url).start()
                        return true
                    } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {}

        // Fallback to default browser
        return try {
            Desktop.getDesktop().browse(URI.create(url))
            true
        } catch (_: Throwable) {
            false
        }
    }
}
