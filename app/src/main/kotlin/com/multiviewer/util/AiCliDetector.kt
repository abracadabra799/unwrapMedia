package com.multiviewer.util

import java.awt.Desktop
import java.io.File
import java.net.URI

// Declaration order is the chip order in the AI prompt window. agy before gemini:
// enterprise accounts can't use agy yet, so gemini stays the fallback and both
// need a chip. `command` is what a chip click types into the terminal.
enum class AiCliType(val displayName: String, val command: String) {
    CLAUDE("Claude Code", "claude"),
    CODEX("Codex", "codex"),
    AGY("Antigravity", "agy"),
    GEMINI("Gemini CLI", "gemini"),
}

object AiCliDetector {

    /**
     * Opens the OS terminal at [dir] with no CLI (macOS/Linux). Windows uses the
     * embedded shell instead and never calls this. Returns whether a terminal was
     * spawned.
     */
    fun openShellAt(dir: File?): Boolean {
        val path = dir?.takeIf { it.isDirectory }?.absolutePath ?: System.getProperty("user.home")
        val os = System.getProperty("os.name").lowercase()
        return try {
            when {
                os.contains("mac") ->
                    ProcessBuilder("open", "-a", "Terminal", path).start().waitFor() == 0
                os.contains("win") ->
                    ProcessBuilder("cmd.exe", "/c", "start", "powershell", "-NoLogo")
                        .directory(File(path)).start().let { true }
                else -> {
                    val terminals = listOf("x-terminal-emulator", "gnome-terminal", "konsole", "xterm")
                    terminals.any { t ->
                        runCatching { ProcessBuilder(t, "--working-directory=$path").start() }.isSuccess
                    }
                }
            }
        } catch (_: Throwable) {
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
