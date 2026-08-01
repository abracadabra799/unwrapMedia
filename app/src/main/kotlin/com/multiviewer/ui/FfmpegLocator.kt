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

    // The bundled Linux ffmpeg/ffprobe are shared-linked against a sibling lib/ directory, but
    // don't reliably resolve it via RPATH at runtime -- confirmed empirically against a real
    // packaged build: `ldd` reported every libav*/libsw* dependency as "not found" even though
    // $ORIGIN/../lib (i.e. this resources dir's lib/) contained them, despite the binary's own
    // embedded build-configure string claiming `-Wl,-rpath=$ORIGIN/../lib` was requested at link
    // time -- that string only proves what flags were passed to the linker, not that they took
    // effect in the final binary. So callers must point the dynamic linker at lib/ explicitly via
    // LD_LIBRARY_PATH before spawning ffmpeg/ffprobe as a subprocess on Linux. Call this on every
    // ProcessBuilder that runs an FfmpegLocator-resolved path, right before start().
    fun configureEnvironment(processBuilder: ProcessBuilder) {
        val resourcesDirPath = System.getProperty("compose.application.resources.dir") ?: return
        val osName = System.getProperty("os.name") ?: return
        val isWindows = osName.contains("Windows", ignoreCase = true)
        val isMac = osName.contains("Mac", ignoreCase = true)
        if (isWindows || isMac) return
        val libDir = File(resourcesDirPath, "lib")
        if (!libDir.isDirectory) return
        processBuilder.environment()["LD_LIBRARY_PATH"] = libDir.absolutePath
    }

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
