package com.multiviewer.ui

import java.io.File
import com.multiviewer.cache.ThumbnailSourceFingerprint

internal class ThumbnailLoadCoordinator<T>(
    private val preview: (File) -> T?,
    private val decodeFinal: (File) -> T?,
    private val persistFinal: (File, T, ThumbnailSourceFingerprint) -> Unit,
    private val publish: (File, T, Boolean) -> Unit,
    private val finishWithoutBitmap: (File) -> Unit,
) {
    fun load(file: File, fingerprint: ThumbnailSourceFingerprint = ThumbnailSourceFingerprint.capture(file)) {
        val embeddedPreview = runCatching { preview(file) }.getOrNull()
        if (embeddedPreview != null) {
            if (!fingerprint.matches(file)) {
                finishWithoutBitmap(file)
                return
            }
            publish(file, embeddedPreview, false)
        }

        val finalBitmap = runCatching { decodeFinal(file) }.getOrNull()
        if (finalBitmap != null) {
            if (!fingerprint.matches(file)) {
                finishWithoutBitmap(file)
                return
            }
            runCatching { persistFinal(file, finalBitmap, fingerprint) }
            if (!fingerprint.matches(file)) {
                finishWithoutBitmap(file)
                return
            }
            publish(file, finalBitmap, true)
        } else if (embeddedPreview != null) {
            if (fingerprint.matches(file)) publish(file, embeddedPreview, true)
            else finishWithoutBitmap(file)
        } else {
            finishWithoutBitmap(file)
        }
    }
}
