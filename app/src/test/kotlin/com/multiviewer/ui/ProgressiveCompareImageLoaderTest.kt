package com.multiviewer.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame

class ProgressiveCompareImageLoaderTest {
    @Test
    fun `publishes scaled preview before full decode using the same encoded bytes`() {
        val file = File.createTempFile("progressive-compare", ".jpg").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        try {
            val events = mutableListOf<String>()
            var previewInput: ByteArray? = null
            var finalInput: ByteArray? = null
            val loader = ProgressiveCompareImageLoader(
                decodePreview = { bytes, edge ->
                    previewInput = bytes
                    events += "preview-decode:$edge"
                    "preview"
                },
                decodeFinal = { bytes ->
                    finalInput = bytes
                    events += "full-decode"
                    "full"
                },
                previewLongestEdge = 1600,
            )

            val result = loader.load(file, embeddedPreview = null) { events += "publish:$it" }

            assertEquals(listOf("preview-decode:1600", "publish:preview", "full-decode"), events)
            assertSame(previewInput, finalInput)
            assertEquals("full", result)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `uses an available embedded preview without running the scaled preview decoder`() {
        val file = File.createTempFile("progressive-compare-embedded", ".jpg").apply {
            writeBytes(byteArrayOf(5, 6, 7, 8))
        }
        try {
            var previewDecoderCalled = false
            val events = mutableListOf<String>()
            val loader = ProgressiveCompareImageLoader(
                decodePreview = { _, _ ->
                    previewDecoderCalled = true
                    "scaled"
                },
                decodeFinal = { "full" },
            )

            loader.load(file, embeddedPreview = "embedded") { events += it }

            assertFalse(previewDecoderCalled)
            assertEquals(listOf("embedded"), events)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `publishes embedded preview even when primary file bytes cannot be read`() {
        val file = File.createTempFile("progressive-compare-unreadable", ".jpg")
        file.delete()
        val events = mutableListOf<String>()
        val loader = ProgressiveCompareImageLoader(
            decodePreview = { _, _ -> "scaled" },
            decodeFinal = { "full" },
        )

        val result = loader.load(file, embeddedPreview = "embedded") { events += it }

        assertEquals(listOf("embedded"), events)
        assertEquals(null, result)
    }
}
