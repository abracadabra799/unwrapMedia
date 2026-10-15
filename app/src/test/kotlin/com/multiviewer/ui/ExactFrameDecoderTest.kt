package com.multiviewer.ui

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ExactFrameDecoderTest {
    private fun ffmpegAvailable() = runCatching { ProcessBuilder("ffmpeg", "-version").start().waitFor() == 0 }.getOrDefault(false)
    private fun fixture(name: String) = File(javaClass.getResource("/video-frame/$name")!!.toURI())

    private fun ffprobeFramePts(file: File): List<Double> {
        val p = ProcessBuilder("ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "frame=pts_time",
            "-of", "csv=p=0", file.absolutePath).redirectErrorStream(true).start()
        return p.inputStream.bufferedReader().readLines().mapNotNull { it.trim().trimEnd(',').toDoubleOrNull() }
    }

    private fun referenceFrame(file: File, n: Int): IntArray {
        val out = Files.createTempFile("ref", ".png").toFile()
        try {
            val p = ProcessBuilder("ffmpeg", "-v", "error", "-y", "-i", file.absolutePath, "-vf", "select=eq(n\\,$n)",
                "-frames:v", "1", out.absolutePath).redirectErrorStream(true).start()
            p.inputStream.readAllBytes(); assertEquals(0, p.waitFor())
            val img = ImageIO.read(out)
            return img.getRGB(0, 0, img.width, img.height, null, 0, img.width)
        } finally { out.delete() }
    }

    @Test
    fun `stream info parses start time and transfer, N_A is zero`() {
        assertEquals(FrameStreamInfo(11.4, "bt709"), parseFrameStreamInfo(listOf("color_transfer=bt709", "start_time=11.400000")))
        assertEquals(FrameStreamInfo(0.0, null), parseFrameStreamInfo(listOf("color_transfer=unknown", "start_time=N/A")))
    }

    @Test
    fun `args seek three seconds early with copyts and select the exact pts, no exponent`() {
        val args = exactFrameArgs("ffmpeg", "/v/a b.ts", 12.9, 11.4)
        assertEquals(listOf("ffmpeg", "-v", "info", "-copyts", "-ss", "0.000000", "-i", "/v/a b.ts", "-map", "0:v:0",
            "-vf", "select=gte(t\\,12.899500),showinfo", "-frames:v", "1", "-y"), args)
        assertEquals("0.000100", exactFrameArgs("ffmpeg", "f", 3.0001, 0.0)[5])
        assertEquals("select=gte(t\\,-0.000400),showinfo", exactFrameArgs("ffmpeg", "f", 0.0001, 0.0)[11])
    }

    @Test
    fun `showinfo pts is the first pts_time`() {
        val err = "[Parsed_showinfo_1 @ 0x1] n:   0 pts: 1161000 pts_time:12.9 duration: 3003\n[Parsed_showinfo_1 @ 0x1] n:   1 pts_time:12.933333"
        assertEquals(12.9, parseShowinfoPts(err))
        assertEquals(null, parseShowinfoPts("no frames"))
    }

    @Test
    fun `selected frame decodes to exactly that frame on every fixture`() {
        assumeTrue(ffmpegAvailable(), "ffmpeg not on PATH")
        for (name in listOf("default.mp4", "offset.ts", "hevc.mp4", "vfr.mkv")) {
            val file = fixture(name)
            val pts = ffprobeFramePts(file)
            val info = probeFrameStreamInfo(file)
            if (name == "offset.ts") assertTrue(info.startTime > 1.0, "offset.ts start ${info.startTime}")
            for (n in listOf(0, 1, 2, 29, 30, 31, pts.size - 1)) {
                val result = decodeExactFrame(file, pts[n], info, frameDurationSeconds = 1.0 / 30)
                assertIs<ExactFrameResult.Frame>(result, "$name #$n")
                assertEquals(pts[n], result.ptsSeconds, 1e-6, "$name #$n")
                val img = result.image
                assertTrue(referenceFrame(file, n).contentEquals(img.getRGB(0, 0, img.width, img.height, null, 0, img.width)), "$name #$n pixels")
            }
        }
    }

    @Test
    fun `a pts between frames is refused, not rounded to a neighbour`() {
        assumeTrue(ffmpegAvailable(), "ffmpeg not on PATH")
        val file = fixture("offset.ts")
        val pts = ffprobeFramePts(file)
        val between = (pts[10] + pts[11]) / 2
        val result = decodeExactFrame(file, between, probeFrameStreamInfo(file), frameDurationSeconds = 0.001)
        assertIs<ExactFrameResult.Failed>(result)
    }

    @Test
    fun `frame duration comes from the neighbouring frame`() {
        val frames = listOf(FrameInfo(0, 'I', 1, 1.0), FrameInfo(1, 'P', 1, 1.04), FrameInfo(2, 'B', 1, 1.1))
        assertEquals(0.04, frameDurationAround(frames[0], frames), 1e-9)
        assertEquals(0.06, frameDurationAround(frames[2], frames), 1e-9)
        assertEquals(1.0 / 30, frameDurationAround(FrameInfo(5, 'I', 1, 2.0), null), 1e-9)
    }
}
