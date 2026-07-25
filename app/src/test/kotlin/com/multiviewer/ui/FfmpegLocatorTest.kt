package com.multiviewer.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FfmpegLocatorTest {
    @Test
    fun `ffmpegPath returns the literal command when compose_application_resources_dir is unset`() {
        System.clearProperty("compose.application.resources.dir")
        assertEquals("ffmpeg", FfmpegLocator.ffmpegPath())
        assertEquals("ffprobe", FfmpegLocator.ffprobePath())
    }

    @Test
    fun `ffmpegPath returns the bundled binary's absolute path when it exists under resources_dir slash bin`() {
        val resourcesDir = File.createTempFile("ffmpeg-locator-test-", "").apply { delete(); mkdirs() }
        val binDir = File(resourcesDir, "bin").apply { mkdirs() }
        val isWindows = System.getProperty("os.name")?.contains("Windows", ignoreCase = true) == true
        val bundledName = if (isWindows) "ffmpeg.exe" else "ffmpeg"
        val bundled = File(binDir, bundledName).apply { writeText("fake binary") }

        System.setProperty("compose.application.resources.dir", resourcesDir.absolutePath)
        try {
            assertEquals(bundled.absolutePath, FfmpegLocator.ffmpegPath())
        } finally {
            System.clearProperty("compose.application.resources.dir")
            resourcesDir.deleteRecursively()
        }
    }

    @Test
    fun `ffmpegPath falls back to the literal command when resources_dir is set but the file is not there`() {
        val resourcesDir = File.createTempFile("ffmpeg-locator-test-empty-", "").apply { delete(); mkdirs() }

        System.setProperty("compose.application.resources.dir", resourcesDir.absolutePath)
        try {
            assertEquals("ffmpeg", FfmpegLocator.ffmpegPath())
        } finally {
            System.clearProperty("compose.application.resources.dir")
            resourcesDir.deleteRecursively()
        }
    }
}
