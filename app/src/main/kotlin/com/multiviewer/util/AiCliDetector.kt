package com.multiviewer.util

import java.awt.Desktop
import java.io.File
import java.net.URI

// Declaration order is also the button order in the AI prompt window. agy before
// gemini: enterprise accounts can't use agy yet, so gemini stays the fallback and
// both need a launch button regardless of what's detected on PATH.
enum class AiCliType(val displayName: String, val binaryName: String) {
    CLAUDE("Claude Code", "claude"),
    CODEX("Codex", "codex"),
    AGY("Antigravity (agy)", "agy"),
    GEMINI("Gemini CLI", "gemini");

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

    /** Extensions a bare `powershell &` / ProcessBuilder can actually run on Windows. */
    private val WINDOWS_RUNNABLE_EXTS = listOf(".cmd", ".bat", ".exe", ".ps1")

    private val isWindows: Boolean by lazy {
        System.getProperty("os.name").lowercase().contains("win")
    }

    /**
     * Common Windows install dirs for npm/volta/fnm-managed CLIs. The JVM's `PATH`
     * can miss user-level additions (e.g. `%APPDATA%\npm`) when the app is launched
     * from a shortcut or installer, so scan these explicitly before falling back to
     * `where`.
     */
    private val windowsExtraPaths: List<String> by lazy {
        if (!isWindows) return@lazy emptyList()
        val appData = System.getenv("APPDATA")
        val localAppData = System.getenv("LOCALAPPDATA")
        val userProfile = System.getenv("USERPROFILE") ?: System.getProperty("user.home")
        val programFiles = System.getenv("ProgramFiles")
        listOfNotNull(
            appData?.let { "$it\\npm" },
            localAppData?.let { "$it\\npm" },
            localAppData?.let { "$it\\Volta\\bin" },
            localAppData?.let { "$it\\fnm_multishells" },
            "$userProfile\\AppData\\Roaming\\npm",
            "$userProfile\\.volta\\bin",
            "$userProfile\\.bun\\bin",
            "$userProfile\\scoop\\shims",
            programFiles?.let { "$it\\nodejs" },
        )
    }

    /**
     * Candidate file names for [name] in a PATH dir, most-preferred first. On
     * Windows the extensionless name is a POSIX shell script (npm ships one next
     * to `<name>.cmd`) that neither PowerShell nor ProcessBuilder can launch, so
     * it is excluded.
     */
    private fun candidateFileNames(name: String): List<String> =
        if (isWindows) WINDOWS_RUNNABLE_EXTS.map { "$name$it" } else listOf(name)

    /** Picks the best runnable path from `where`/`which` output (may be multi-line). */
    internal fun pickFromLookupOutput(output: String, windows: Boolean): String? {
        val hits = output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && File(it).isFile }
            .toList()
        if (!windows) return hits.firstOrNull()
        return hits.firstOrNull { hit -> WINDOWS_RUNNABLE_EXTS.any { hit.lowercase().endsWith(it) } }
    }

    fun findBinary(name: String): String? {
        val dirs = candidatePaths + windowsExtraPaths + (System.getenv("PATH") ?: "").split(File.pathSeparator)
        val fileNames = candidateFileNames(name)
        for (dir in dirs) {
            for (fileName in fileNames) {
                val file = File(dir, fileName)
                if (file.isFile && (isWindows || file.canExecute())) {
                    return file.absolutePath
                }
            }
        }

        return try {
            val cmd = if (isWindows) listOf("where", name) else listOf("which", name)
            val process = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            if (process.waitFor() != 0) null else pickFromLookupOutput(output, isWindows)
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
                // On Windows, AI CLIs like claude or agy are often installed as npm batch files (.cmd),
                // or the Win32 CreateProcess command-line parsing cuts multiline text at the first newline (\r or \n).
                // To solve this:
                // 1. Ensure prompt is in Windows clipboard (already done by caller, also written to file).
                // 2. In PowerShell, create an interactive runner script that sets UTF-8 encoding.
                // 3. Instead of passing massive multiline strings via .cmd argv (which batch files clip after %1 or \r\n),
                //    we can either pass the temp file / summary or launch the CLI with a concise entry prompt and
                //    guide the user with a clean helper message, or use Windows SendKeys / direct node invocation.
                // Even better: Check if the binary is a .cmd wrapper. If passing to CLI directly,
                // we can pass an initial prompt referencing the file or prompt, or launch the interactive CLI.
                val runnerScript = File.createTempFile("ai_runner_", ".ps1")
                val promptPath = tempPromptFile.absolutePath.replace("\\", "/")

                // Build invocation command depending on CLI type
                // For agy: agy --add-dir or -i
                // If we pass multiline to cmd/node, npm's batch file truncates at newline.
                // To pass the entire prompt cleanly into CLI:
                // We provide the command to run, and write instructions.
                val scriptContent = """
                    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
                    Set-Location -LiteralPath "$dirPath"
                    Write-Host "=================================================================" -ForegroundColor Cyan
                    Write-Host " [unwrapMedia] AI Diagnostic Session (${type.displayName})" -ForegroundColor Green
                    Write-Host " 전체 정밀 진단 프롬프트(수백 줄)가 클립보드에 이미 복사되어 있습니다!" -ForegroundColor Yellow
                    Write-Host " 임시 프롬프트 파일: $promptPath" -ForegroundColor DarkGray
                    Write-Host "=================================================================" -ForegroundColor Cyan
                    Write-Host ""
                    
                    # Run CLI
                    & "$binPath"
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
