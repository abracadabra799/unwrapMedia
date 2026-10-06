package com.multiviewer.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class ThumbnailFileSelectionTest {
    @Test
    fun `selecting a third image drops the oldest selection and keeps the latest two`() {
        val first = File("/tmp/first.jpg")
        val second = File("/tmp/second.jpg")
        val third = File("/tmp/third.jpg")

        assertEquals(
            listOf(second, third),
            toggleThumbnailFileSelection(listOf(first, second), third),
        )
    }
}
