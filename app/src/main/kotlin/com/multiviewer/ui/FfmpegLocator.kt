package com.multiviewer.ui

import java.io.File

/**
 * Resolves the ffmpeg/ffprobe executable to invoke: the bundled binary if this is a packaged
 * Windows/Linux build that shipped one (see the ffmpeg-bundling design), otherwise the literal
 * command name, resolved via PATH by ProcessBuilder exactly as before bundling existed. This is
 * always the PATH fallback in development (`./gradlew :app:run`, where the
 * `compose.application.resources.dir` system property jpackage sets is never present) and on
 * macOS (which this bundling design doesn't cover).
 */
object FfmpegLocator {
    fun ffmpegPath(): String = resolve(unixName = "ffmpeg", windowsName = "ffmpeg.exe")
    fun ffprobePath(): String = resolve(unixName = "ffprobe", windowsName = "ffprobe.exe")

    private fun resolve(unixName: String, windowsName: String): String {
        val resourcesDirPath = System.getProperty("compose.application.resources.dir") ?: return unixName
        val isWindows = System.getProperty("os.name")?.contains("Windows", ignoreCase = true) == true
        val binaryName = if (isWindows) windowsName else unixName
        val resourcesDir = File(resourcesDirPath)
        val candidates = listOf(File(resourcesDir, "bin/$binaryName"), File(resourcesDir, binaryName))
        val found = candidates.firstOrNull { it.exists() }
        if (found == null) {
            println("FfmpegLocator: bundled $binaryName not found under $resourcesDirPath (checked: ${candidates.map { it.path }}); falling back to PATH")
        } else {
            // jpackage's resource copy isn't guaranteed to preserve the executable bit CI's
            // chmod set on the staged binary -- set it again defensively so a permission-denied
            // ProcessBuilder failure (which looks identical to "ffmpeg isn't installed" from the
            // caller's perspective) can't happen even if that guarantee doesn't hold.
            found.setExecutable(true)
        }
        return found?.absolutePath ?: unixName
    }
}
