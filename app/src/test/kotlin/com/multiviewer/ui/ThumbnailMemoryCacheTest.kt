package com.multiviewer.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ThumbnailMemoryCacheTest {
    @Test
    fun `same path with changed file fingerprint misses stale bitmap`() {
        val file = File.createTempFile("thumbnail-memory-cache", ".jpg")
        try {
            file.writeText("old")
            val cache = ThumbnailMemoryCache<String>()
            cache.put(file, "old bitmap")

            assertEquals("old bitmap", cache.get(file))
            file.writeText("replacement image content")

            assertNull(cache.get(file))
            cache.put(file, "replacement bitmap")
            assertEquals("replacement bitmap", cache.get(file))
        } finally {
            file.delete()
        }
    }
}
