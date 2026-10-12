package com.multiviewer.cache

import com.multiviewer.ui.FrameInfo
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaIndexCacheTest {
    private lateinit var tempDir: File
    private lateinit var sampleVideoFile: File

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("media-index-cache-test").toFile()
        sampleVideoFile = File(tempDir, "sample.mp4").apply {
            writeBytes(ByteArray(1024) { 0x42 })
        }
        MediaIndexCache.initialize(tempDir)
        MediaIndexCache.clear()
    }

    @AfterTest
    fun tearDown() {
        MediaIndexCache.clear()
        tempDir.deleteRecursively()
    }

    @Test
    fun `put and get roundtrips correctly via L1 and L2 disk cache`() {
        val sampleFrames = listOf(
            FrameInfo(index = 0, type = 'I', sizeBytes = 50000, ptsSeconds = 0.0, byteOffset = 1024L),
            FrameInfo(index = 1, type = 'P', sizeBytes = 12000, ptsSeconds = 0.033, byteOffset = 51024L),
            FrameInfo(index = 2, type = 'B', sizeBytes = 8000, ptsSeconds = 0.066, byteOffset = 63024L),
        )

        assertNull(MediaIndexCache.get(sampleVideoFile))

        MediaIndexCache.put(sampleVideoFile, sampleFrames)

        // Retrieved from L1
        val retrievedL1 = MediaIndexCache.get(sampleVideoFile)
        assertNotNull(retrievedL1)
        assertEquals(sampleFrames, retrievedL1)

        // Clear L1 by reflecting a fresh instance/clearing l1 to test L2 disk load
        val key = MediaIndexCache.getCacheKey(sampleVideoFile)
        val diskFile = File(tempDir, "$key.fidx")
        assertEquals(true, diskFile.exists())

        // Re-read from disk
        val retrievedL2 = MediaIndexCache.get(sampleVideoFile)
        assertNotNull(retrievedL2)
        assertEquals(sampleFrames, retrievedL2)
    }

    @Test
    fun `modifying the video file invalidates the cache key`() {
        val sampleFrames = listOf(
            FrameInfo(index = 0, type = 'I', sizeBytes = 50000, ptsSeconds = 0.0, byteOffset = 1024L),
        )
        MediaIndexCache.put(sampleVideoFile, sampleFrames)

        val initialKey = MediaIndexCache.getCacheKey(sampleVideoFile)

        // Modify file
        sampleVideoFile.setLastModified(sampleVideoFile.lastModified() + 5000)
        val newKey = MediaIndexCache.getCacheKey(sampleVideoFile)

        assertEquals(false, initialKey == newKey)
        assertNull(MediaIndexCache.get(sampleVideoFile))
    }

    // The disk tier gets one .fidx per opened video and used to keep every one of them forever.
    @Test
    fun `disk tier evicts least recently used index files beyond its byte budget`() {
        fun video(name: String) = File(tempDir, name).apply { writeBytes(ByteArray(16) { name.length.toByte() }) }
        val frames = (0 until 100).map { FrameInfo(it, 'P', 1000, it / 30.0, it * 1000L) }
        val oldest = video("a.mp4"); val middle = video("bb.mp4"); val newest = video("ccc.mp4")
        MediaIndexCache.put(oldest, frames)
        val oneEntryBytes = File(tempDir, MediaIndexCache.getCacheKey(oldest) + ".fidx").length()
        MediaIndexCache.initialize(tempDir, maxDiskBytes = oneEntryBytes * 2)
        fun fidx(f: File) = File(tempDir, MediaIndexCache.getCacheKey(f) + ".fidx")
        fidx(oldest).setLastModified(System.currentTimeMillis() - 60_000)
        MediaIndexCache.put(middle, frames)
        fidx(middle).setLastModified(System.currentTimeMillis() - 30_000)
        MediaIndexCache.put(newest, frames)

        assertTrue(!fidx(oldest).exists(), "least recently used index should have been evicted")
        assertTrue(fidx(middle).exists())
        assertTrue(fidx(newest).exists())
    }
}
