package com.multiviewer.ui

import java.io.File

internal class ThumbnailLoadCoordinator<T>(
    private val preview: (File) -> T?,
    private val decodeFinal: (File) -> T?,
    private val persistFinal: (File, T) -> Unit,
    private val publish: (File, T, Boolean) -> Unit,
    private val finishWithoutBitmap: (File) -> Unit,
) {
    fun load(file: File) {
        val embeddedPreview = runCatching { preview(file) }.getOrNull()
        if (embeddedPreview != null) publish(file, embeddedPreview, false)

        val finalBitmap = runCatching { decodeFinal(file) }.getOrNull()
        if (finalBitmap != null) {
            runCatching { persistFinal(file, finalBitmap) }
            publish(file, finalBitmap, true)
        } else if (embeddedPreview != null) {
            publish(file, embeddedPreview, true)
        } else {
            finishWithoutBitmap(file)
        }
    }
}
