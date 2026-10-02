package com.multiviewer.ui

import java.io.File

sealed interface AvmDecoderLocation {
    data class Packaged(val file: File) : AvmDecoderLocation
    data class Missing(val checked: List<File>) : AvmDecoderLocation
    data object UnsupportedPlatform : AvmDecoderLocation
}

/** Resolves only the packaged Windows AVM helper; it never silently falls back to PATH. */
object AvmLocator {
    fun decoderLocation(): AvmDecoderLocation {
        val osName = System.getProperty("os.name") ?: ""
        if (!osName.contains("Windows", ignoreCase = true)) return AvmDecoderLocation.UnsupportedPlatform
        val resources = System.getProperty("compose.application.resources.dir")?.let(::File)
            ?: return AvmDecoderLocation.Missing(emptyList())
        val checked = listOf(File(resources, "bin/avmdec.exe"), File(resources, "avmdec.exe"))
        val found = checked.firstOrNull { it.isFile }
        if (found != null) {
            found.setExecutable(true)
            return AvmDecoderLocation.Packaged(found)
        }
        return AvmDecoderLocation.Missing(checked)
    }

    fun decoderPathOrThrow(): String = when (val location = decoderLocation()) {
        is AvmDecoderLocation.Packaged -> location.file.absolutePath
        is AvmDecoderLocation.Missing -> error("Windows AV2 decoder avmdec.exe is missing; checked ${location.checked}")
        AvmDecoderLocation.UnsupportedPlatform -> error("AV2 playback is currently supported on Windows only")
    }
}
