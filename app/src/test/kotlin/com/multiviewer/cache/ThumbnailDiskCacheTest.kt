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
    fun `put after source change uses the fingerprint captured before decode`() {
        val root = Files.createTempDirectory("thumbnail-disk-cache-captured-key").toFile()
        try {
            val source = File(root, "photo.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val cache = ThumbnailDiskCache(File(root, "cache"), maxBytes = 1024L)
            val captured = ThumbnailSourceFingerprint.capture(source)
            val oldKey = cache.cacheKeyFor(source, longestEdge = 240, fingerprint = captured)
            val oldPixels = byteArrayOf(6, 5, 4)

            source.writeBytes(byteArrayOf(9, 8, 7, 6))
            cache.put(source, longestEdge = 240, encodedThumbnail = oldPixels, fingerprint = captured)

            assertNull(cache.get(source, longestEdge = 240))
            assertContentEquals(oldPixels, cache.get(source, longestEdge = 240, fingerprint = captured))
            assertEquals(oldKey, cache.cacheKeyFor(source, longestEdge = 240, fingerprint = captured))
            assertNotEquals(oldKey, cache.cacheKeyFor(source, longestEdge = 240))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `removing invalid old cache entry preserves replacement fingerprint entry`() {
        val root = Files.createTempDirectory("thumbnail-disk-cache-versioned-remove").toFile()
        try {
            val source = File(root, "photo.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val cache = ThumbnailDiskCache(File(root, "cache"), maxBytes = 1024L)
            val oldFingerprint = ThumbnailSourceFingerprint.capture(source)
            val oldBytes = byteArrayOf(0)
            cache.put(source, longestEdge = 240, encodedThumbnail = oldBytes, fingerprint = oldFingerprint)

            source.writeBytes(byteArrayOf(9, 8, 7, 6))
            val replacementBytes = byteArrayOf(5, 4, 3, 2)
            cache.put(source, longestEdge = 240, encodedThumbnail = replacementBytes)

            cache.remove(source, longestEdge = 240, fingerprint = oldFingerprint)

            assertContentEquals(replacementBytes, cache.get(source, longestEdge = 240))
            assertNull(cache.get(source, longestEdge = 240, fingerprint = oldFingerprint))
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
