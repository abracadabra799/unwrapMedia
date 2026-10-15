package com.multiviewer.ui

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class FrameFullSizeDecoderTest {
    private fun ffmpegAvailable() = runCatching { ProcessBuilder("ffmpeg", "-version").start().waitFor() == 0 }.getOrDefault(false)
    private fun fixture(name: String) = File(javaClass.getResource("/video-frame/$name")!!.toURI())

    // The first frame at/after start_time + seconds, decoded independently from the start of the file.
    private fun referenceAt(file: File, absoluteSeconds: Double): IntArray {
        val out = Files.createTempFile("ref", ".png").toFile()
        try {
            val p = ProcessBuilder("ffmpeg", "-v", "error", "-y", "-copyts", "-i", file.absolutePath, "-map", "0:v:0",
                "-vf", "select=gte(t\\,${fmt6(absoluteSeconds - 0.0005)})", "-frames:v", "1", "-pix_fmt", "rgb24", out.absolutePath)
                .redirectErrorStream(true).start()
            p.inputStream.readAllBytes()
            assertEquals(0, p.waitFor())
            val img = ImageIO.read(out)
            return img.getRGB(0, 0, img.width, img.height, null, 0, img.width)
        } finally {
            out.delete()
        }
    }

    @Test
    fun `a stream starting at zero keeps today's two ffmpeg attempts`() {
        val attempts = frameDecodeAttempts("ffmpeg", "/v/a.mp4", 1.5, 0.0)
        assertEquals(
            listOf(
                listOf("ffmpeg", "-y", "-hwaccel", "auto", "-ss", "1.5", "-i", "/v/a.mp4", "-frames:v", "1", "-q:v", "2", "-update", "1"),
                listOf("ffmpeg", "-y", "-i", "/v/a.mp4", "-ss", "1.5", "-frames:v", "1", "-q:v", "2", "-update", "1"),
            ),
            attempts.map { it.args },
        )
        assertEquals(listOf(".jpg", ".jpg"), attempts.map { it.extension })
    }

    @Test
    fun `an offset stream uses one exact absolute-time attempt`() {
        val attempts = frameDecodeAttempts("ffmpeg", "/v/a.ts", 1.5, 11.4)
        assertEquals(listOf(exactFrameArgs("ffmpeg", "/v/a.ts", 12.9, 11.4)), attempts.map { it.args })
        assertEquals(listOf(".png"), attempts.map { it.extension })
    }

    @Test
    fun `seconds on an offset TS decode the frame at that point of the timeline`() {
        assumeTrue(ffmpegAvailable(), "ffmpeg not on PATH")
        val file = fixture("offset.ts")
        val start = probeFrameStreamInfo(file).startTime
        for (seconds in listOf(0.0, 0.5, 1.0, 1.5, 1.9)) {
            val bitmap = assertNotNull(FrameFullSizeDecoder.decodeFrameBlocking(file, seconds), "t=$seconds")
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.readPixels(pixels)
            assertContentEquals(referenceAt(file, start + seconds), pixels, "t=$seconds")
        }
    }

    @Test
    fun `an absolute frame PTS on an offset TS decodes that frame`() {
        assumeTrue(ffmpegAvailable(), "ffmpeg not on PATH")
        val file = fixture("offset.ts")
        val start = probeFrameStreamInfo(file).startTime
        val bitmap = assertNotNull(FrameFullSizeDecoder.decodeFrameAtPtsBlocking(file, start + 1.5))
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.readPixels(pixels)
        assertContentEquals(referenceAt(file, start + 1.5), pixels)
    }

    @Test
    fun `a stream starting at zero still decodes`() {
        assumeTrue(ffmpegAvailable(), "ffmpeg not on PATH")
        val bitmap = assertNotNull(FrameFullSizeDecoder.decodeFrameBlocking(fixture("default.mp4"), 1.5))
        assertEquals(160, bitmap.width)
    }
}
