package com.multiviewer.cache

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class ThumbnailDiskCacheTest {
    @Test
    fun `reuses encoded thumbnail across cache instances`() {
        val root = Files.createTempDirectory("thumbnail-disk-cache").toFile()
        try {
            val source = File(root, "photo.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val first = ThumbnailDiskCache(File(root, "cache"), maxBytes = 1024L)
            val second = ThumbnailDiskCache(File(root, "cache"), maxBytes = 1024L)
            val bytes = byteArrayOf(9, 8, 7, 6)

            first.put(source, longestEdge = 240, encodedThumbnail = bytes)

            assertContentEquals(bytes, second.get(source, longestEdge = 240))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `source change and thumbnail size produce different cache entries`() {
        val root = Files.createTempDirectory("thumbnail-disk-cache-key").toFile()
        try {
            val source = File(root, "photo.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val cache = ThumbnailDiskCache(File(root, "cache"), maxBytes = 1024L)
            val originalKey = cache.cacheKeyFor(source, longestEdge = 240)
            cache.put(source, longestEdge = 240, encodedThumbnail = byteArrayOf(1))

            assertNull(cache.get(source, longestEdge = 320))
            source.appendBytes(byteArrayOf(4))
            assertNotEquals(originalKey, cache.cacheKeyFor(source, longestEdge = 240))
            assertNull(cache.get(source, longestEdge = 240))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `evicts least recently used entries to stay under size limit`() {
        val root = Files.createTempDirectory("thumbnail-disk-cache-eviction").toFile()
        try {
            val cacheDir = File(root, "cache")
            val cache = ThumbnailDiskCache(cacheDir, maxBytes = 6L)
            val firstSource = File(root, "first.jpg").apply { writeBytes(byteArrayOf(1)) }
            val secondSource = File(root, "second.jpg").apply { writeBytes(byteArrayOf(2)) }
            val thirdSource = File(root, "third.jpg").apply { writeBytes(byteArrayOf(3)) }
            cache.put(firstSource, 240, byteArrayOf(1, 1, 1))
            cache.put(secondSource, 240, byteArrayOf(2, 2, 2))

            assertContentEquals(byteArrayOf(1, 1, 1), cache.get(firstSource, 240))
            cache.put(thirdSource, 240, byteArrayOf(3, 3, 3))

            assertNull(cache.get(secondSource, 240))
            assertContentEquals(byteArrayOf(1, 1, 1), cache.get(firstSource, 240))
            assertEquals(6L, cacheDir.listFiles()?.filter { it.extension == "thumb" }?.sumOf { it.length() })
            assertFalse(cacheDir.listFiles()?.any { it.extension == "tmp" } == true)
        } finally {
            root.deleteRecursively()
        }
    }
}
