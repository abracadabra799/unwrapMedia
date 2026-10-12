package com.multiviewer.ui

import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image
import java.io.File

private const val APP_ICON_PATH = "icons/app_source.png"

// Decoded once and shared by the main, About and Update windows (each used to re-read and
// re-decode the PNG on every open, leaving the classpath resource stream unclosed). A dev-run
// checkout's icons/ folder wins over the packaged resource, as before.
val appIconPainter: BitmapPainter? by lazy {
    try {
        val bytes = File(APP_ICON_PATH).takeIf { it.exists() }?.readBytes()
            ?: Thread.currentThread().contextClassLoader?.getResourceAsStream(APP_ICON_PATH)?.use { it.readBytes() }
        bytes?.let { BitmapPainter(Image.makeFromEncoded(it).toComposeImageBitmap()) }
    } catch (_: Exception) {
        null
    }
}
