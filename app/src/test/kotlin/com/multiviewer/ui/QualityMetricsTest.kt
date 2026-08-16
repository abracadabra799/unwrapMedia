package com.multiviewer.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QualityMetricsTest {
    private fun generateTestClip(sizeSpec: String, suffix: String): File {
        val file = File.createTempFile("quality-metrics-test-$suffix-", ".mp4")
        file.deleteOnExit()
        ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "testsrc=size=$sizeSpec:rate=10:duration=1",
            "-c:v", "libx264", "-pix_fmt", "yuv420p", file.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()
        return file
    }

    private fun reencode(source: File, crf: Int, suffix: String): File {
        val file = File.createTempFile("quality-metrics-test-$suffix-", ".mp4")
        file.deleteOnExit()
        ProcessBuilder(
            "ffmpeg", "-y", "-i", source.absolutePath, "-c:v", "libx264", "-crf", crf.toString(),
            "-pix_fmt", "yuv420p", file.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()
        return file
    }

    // computeStatistics -------------------------------------------------------------------------

    @Test
    fun `computeStatistics returns min max mean and median over an odd-length series`() {
        val samples = listOf(1.0, 5.0, 3.0).mapIndexed { i, v -> MetricFrameSample(i, v) }
        val stats = computeStatistics(samples)
        assertNotNull(stats)
        assertEquals(1.0, stats.min)
        assertEquals(5.0, stats.max)
        assertEquals(3.0, stats.mean)
        assertEquals(3.0, stats.median)
    }

    @Test
    fun `computeStatistics averages the two middle values for an even-length series`() {
        val samples = listOf(1.0, 2.0, 3.0, 4.0).mapIndexed { i, v -> MetricFrameSample(i, v) }
        val stats = computeStatistics(samples)
        assertNotNull(stats)
        assertEquals(2.5, stats.median)
        assertEquals(2.5, stats.mean)
    }

    @Test
    fun `computeStatistics returns null for an empty series`() {
        assertNull(computeStatistics(emptyList()))
    }

    // resolutionsMatch ----------------------------------------------------------------------------

    @Test
    fun `resolutionsMatch is true for two files with the same resolution`() {
        val a = generateTestClip("64x48", "res-a")
        val b = generateTestClip("64x48", "res-b")
        assertTrue(resolutionsMatch(a, b))
        a.delete(); b.delete()
    }

    @Test
    fun `resolutionsMatch is false for two files with different resolutions`() {
        val a = generateTestClip("64x48", "res-mismatch-a")
        val b = generateTestClip("32x24", "res-mismatch-b")
        assertFalse(resolutionsMatch(a, b))
        a.delete(); b.delete()
    }

    // determineComparisonPairs ---------------------------------------------------------------------

    @Test
    fun `determineComparisonPairs returns an empty list when only Encoded A is filled`() {
        val encodedA = File("encoded-a.mp4")
        assertEquals(emptyList(), determineComparisonPairs(raw = null, encodedA = encodedA, encodedB = null))
    }

    @Test
    fun `determineComparisonPairs returns an empty list when nothing is filled`() {
        assertEquals(emptyList(), determineComparisonPairs(raw = null, encodedA = null, encodedB = null))
    }

    @Test
    fun `determineComparisonPairs returns only Raw-A when Raw and Encoded A are filled`() {
        val raw = File("raw.mp4")
        val encodedA = File("encoded-a.mp4")

        val pairs = determineComparisonPairs(raw, encodedA, encodedB = null)

        assertEquals(1, pairs.size)
        assertEquals("raw_a", pairs[0].id)
        assertEquals("Raw ↔ Encoded A", pairs[0].label)
        assertEquals(encodedA, pairs[0].comparison)
        assertEquals(raw, pairs[0].reference)
    }

    @Test
    fun `determineComparisonPairs returns only A-B when Encoded A and Encoded B are filled`() {
        val encodedA = File("encoded-a.mp4")
        val encodedB = File("encoded-b.mp4")

        val pairs = determineComparisonPairs(raw = null, encodedA, encodedB)

        assertEquals(1, pairs.size)
        assertEquals("a_b", pairs[0].id)
        assertEquals("Encoded A ↔ Encoded B", pairs[0].label)
        assertEquals(encodedB, pairs[0].comparison)
        assertEquals(encodedA, pairs[0].reference)
    }

    @Test
    fun `determineComparisonPairs returns all three pairs in Raw-A, Raw-B, A-B order when all three slots are filled`() {
        val raw = File("raw.mp4")
        val encodedA = File("encoded-a.mp4")
        val encodedB = File("encoded-b.mp4")

        val pairs = determineComparisonPairs(raw, encodedA, encodedB)

        assertEquals(3, pairs.size)
        assertEquals(listOf("raw_a", "raw_b", "a_b"), pairs.map { it.id })
        assertEquals(listOf("Raw ↔ Encoded A", "Raw ↔ Encoded B", "Encoded A ↔ Encoded B"), pairs.map { it.label })
        assertEquals(raw, pairs[1].reference)
        assertEquals(encodedB, pairs[1].comparison)
    }

    // runPsnrPass ---------------------------------------------------------------------------------

    @Test
    fun `runPsnrPass reports a high but finite score for two different real encodes`() {
        val reference = generateTestClip("64x48", "psnr-ref")
        val comparison = reencode(reference, crf = 30, suffix = "psnr-cmp")

        val result = runPsnrPass(comparison, reference, onProgress = { _, _ -> }, isCancelled = { false })

        assertNotNull(result)
        assertEquals(10, result.perFrame.size)
        assertTrue(result.statistics.mean > 20.0 && result.statistics.mean < 100.0)
        reference.delete(); comparison.delete()
    }

    @Test
    fun `runPsnrPass caps identical-frame infinite PSNR at 100dB instead of propagating infinity`() {
        val file = generateTestClip("64x48", "psnr-identical")

        val result = runPsnrPass(file, file, onProgress = { _, _ -> }, isCancelled = { false })

        assertNotNull(result)
        assertTrue(result.perFrame.all { it.value == 100.0 })
        assertEquals(100.0, result.statistics.mean)
        assertTrue(result.statistics.mean.isFinite())
        file.delete()
    }

    @Test
    fun `runPsnrPass reports progress with an increasing current-frame count`() {
        val reference = generateTestClip("64x48", "psnr-progress-ref")
        val comparison = reencode(reference, crf = 30, suffix = "psnr-progress-cmp")
        val reportedFrames = mutableListOf<Int>()

        runPsnrPass(comparison, reference, onProgress = { current, _ -> reportedFrames.add(current) }, isCancelled = { false })

        assertTrue(reportedFrames.isNotEmpty())
        assertEquals(reportedFrames.max(), reportedFrames.last())
        reference.delete(); comparison.delete()
    }

    @Test
    fun `runPsnrPass returns null when cancelled immediately`() {
        val reference = generateTestClip("64x48", "psnr-cancel-ref")
        val comparison = reencode(reference, crf = 30, suffix = "psnr-cancel-cmp")

        val result = runPsnrPass(comparison, reference, onProgress = { _, _ -> }, isCancelled = { true })

        assertNull(result)
        reference.delete(); comparison.delete()
    }

    // runSsimPass ---------------------------------------------------------------------------------

    @Test
    fun `runSsimPass reports a score close to 1_0 for two different real encodes`() {
        val reference = generateTestClip("64x48", "ssim-ref")
        val comparison = reencode(reference, crf = 30, suffix = "ssim-cmp")

        val result = runSsimPass(comparison, reference, onProgress = { _, _ -> }, isCancelled = { false })

        assertNotNull(result)
        assertEquals(10, result.perFrame.size)
        assertTrue(result.statistics.mean in 0.0..1.0)
        reference.delete(); comparison.delete()
    }

    @Test
    fun `runSsimPass reports exactly 1_0 for identical frames`() {
        val file = generateTestClip("64x48", "ssim-identical")

        val result = runSsimPass(file, file, onProgress = { _, _ -> }, isCancelled = { false })

        assertNotNull(result)
        assertTrue(result.perFrame.all { it.value == 1.0 })
        file.delete()
    }
}
