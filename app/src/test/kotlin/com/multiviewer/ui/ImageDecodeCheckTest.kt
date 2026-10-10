package com.multiviewer.ui

import com.multiviewer.parser.integrity.CheckStatus
import com.multiviewer.parser.integrity.ImageIntegrityChecker
import com.multiviewer.parser.parseFile
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ImageDecodeCheckTest {
    private fun realJpeg(): ByteArray {
        val surface = Surface.makeRasterN32Premul(64, 64)
        surface.canvas.clear(0xFF3366CC.toInt())
        return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.JPEG, 90)!!.bytes
    }

    private fun ffmpegAvailable() = runCatching { ProcessBuilder("ffmpeg", "-version").start().waitFor() == 0 }.getOrDefault(false)

    @Test
    fun `decode status needs exit 0, no log and at least one frame`() {
        assertEquals(ImageDecodeStatus.CLEAN, imageDecodeStatus(0, 1, emptyList()))
        assertEquals(ImageDecodeStatus.ISSUES, imageDecodeStatus(0, 1, listOf("overread 8")))
        assertEquals(ImageDecodeStatus.FAILED, imageDecodeStatus(1, 1, emptyList()))
        assertEquals(ImageDecodeStatus.FAILED, imageDecodeStatus(0, 0, emptyList()))
    }

    @Test
    fun `skia failure downgrades a clean ffmpeg result`() {
        val bad = SkiaDecodeResult(attempted = true, ok = false, detail = "Incomplete input")
        val skipped = SkiaDecodeResult(attempted = false, ok = false, detail = "n/a")
        assertEquals(ImageDecodeStatus.ISSUES, combineDecodeStatus(ImageDecodeStatus.CLEAN, bad))
        assertEquals(ImageDecodeStatus.CLEAN, combineDecodeStatus(ImageDecodeStatus.CLEAN, skipped))
        assertEquals(ImageDecodeStatus.FAILED, combineDecodeStatus(ImageDecodeStatus.FAILED, bad))
    }

    @Test
    fun `resolution check accepts a rotation swap`() {
        assertEquals(CheckStatus.PASS, resolutionCheck(4000, 2252, 2252, 4000))
        assertEquals(CheckStatus.WARN, resolutionCheck(64, 64, 32, 64))
        assertEquals(CheckStatus.SKIP, resolutionCheck(null, 64, 64, 64))
    }

    @Test
    fun `framecrc lines are classified`() {
        assertEquals(FramecrcLine.Dimensions(2252, 4000), classifyFramecrcLine("#dimensions 0: 2252x4000"))
        assertEquals(FramecrcLine.Header, classifyFramecrcLine("#tb 0: 1/1"))
        assertEquals(FramecrcLine.Frame, classifyFramecrcLine("0,          0,          0,        1, 13512000, 0xbffcdbb8"))
        assertEquals(FramecrcLine.Log("[mjpeg @ 0x1] overread 8"), classifyFramecrcLine("[mjpeg @ 0x1] overread 8"))
    }

    @Test
    fun `skia rejects a truncated jpeg`() {
        val jpeg = realJpeg()
        assertTrue(skiaDecode(jpeg).ok)
        assertFalse(skiaDecode(jpeg.copyOf(jpeg.size / 2)).ok)
    }

    @Test
    fun `skia decode of a truncated jpeg stays false across repeated calls`() {
        val jpeg = realJpeg()
        val truncated = jpeg.copyOf(jpeg.size / 2)
        repeat(50) { assertFalse(skiaDecode(truncated).ok) }
        assertTrue(skiaDecode(jpeg).ok)
    }

    @Test
    fun `ffmpeg integration - full jpeg is clean, truncated is not`() {
        assumeTrue(ffmpegAvailable(), "ffmpeg not on PATH")
        val jpeg = realJpeg()
        fun run(bytes: ByteArray): ImageDecodeReport {
            val f = File.createTempFile("decode-", ".jpg"); f.deleteOnExit(); f.writeBytes(bytes)
            val structure = ImageIntegrityChecker.check(f, parseFile(f))
            return runBlocking { inspectImageDecode(f, structure, ffmpeg = "ffmpeg") }
        }
        val full = run(jpeg)
        assertEquals(ImageDecodeStatus.CLEAN, full.status, full.logs.toString())
        assertEquals(64, full.decodedWidth)
        assertEquals(CheckStatus.PASS, full.resolutionStatus)
        assertNotEquals(ImageDecodeStatus.CLEAN, run(jpeg.copyOf(jpeg.size / 2)).status)
    }
}
