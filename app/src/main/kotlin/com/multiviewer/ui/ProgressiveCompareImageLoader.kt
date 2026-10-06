package com.multiviewer.ui

import java.io.File

/** Reads an image once, publishes a bounded preview, then returns its full-resolution bitmap. */
internal class ProgressiveCompareImageLoader<T>(
    private val decodePreview: (ByteArray, longestEdge: Int) -> T?,
    private val decodeFinal: (ByteArray) -> T?,
    private val previewLongestEdge: Int = DEFAULT_PREVIEW_LONGEST_EDGE,
) {
    init {
        require(previewLongestEdge > 0) { "previewLongestEdge must be positive" }
    }

    fun load(file: File, embeddedPreview: T?, onPreview: (T) -> Unit): T? {
        if (embeddedPreview != null) runCatching { onPreview(embeddedPreview) }

        val encoded = try {
            file.readBytes()
        } catch (_: Exception) {
            return null
        }

        if (embeddedPreview == null) {
            runCatching { decodePreview(encoded, previewLongestEdge) }.getOrNull()?.let { preview ->
                runCatching { onPreview(preview) }
            }
        }
        return runCatching { decodeFinal(encoded) }.getOrNull()
    }

    companion object {
        const val DEFAULT_PREVIEW_LONGEST_EDGE = 1600
    }
}
