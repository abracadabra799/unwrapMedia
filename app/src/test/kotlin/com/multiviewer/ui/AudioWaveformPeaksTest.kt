package com.multiviewer.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AudioWaveformPeaksTest {
    @Test
    fun `computes the requested bucket count and channel count for a mono file`() {
        val audio = File.createTempFile("waveform-peaks-mono-test-", ".wav")
        audio.deleteOnExit()
        ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "sine=duration=1:frequency=440",
            "-ac", "1", "-c:a", "pcm_s16le", audio.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()
        val info = probeAudioFormat(audio)
        checkNotNull(info)

        val peaks = computeWaveformPeaks(audio, info, bucketCount = 256)

        checkNotNull(peaks)
        assertEquals(1, peaks.channelCount)
        assertEquals(256, peaks.bucketCount)
        assertEquals(1, peaks.channels.size)
        audio.delete()
    }

    @Test
    fun `computes two channels of peaks for a stereo file`() {
        val audio = File.createTempFile("waveform-peaks-stereo-test-", ".wav")
        audio.deleteOnExit()
        ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "sine=duration=1:frequency=440",
            "-ac", "2", "-c:a", "pcm_s16le", audio.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()
        val info = probeAudioFormat(audio)
        checkNotNull(info)

        val peaks = computeWaveformPeaks(audio, info, bucketCount = 256)

        checkNotNull(peaks)
        assertEquals(2, peaks.channelCount)
        assertEquals(2, peaks.channels.size)
        audio.delete()
    }

    @Test
    fun `captures non-zero peak amplitudes for a real sine tone`() {
        val audio = File.createTempFile("waveform-peaks-amplitude-test-", ".wav")
        audio.deleteOnExit()
        ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "sine=duration=1:frequency=440",
            "-ac", "1", "-c:a", "pcm_s16le", audio.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()
        val info = probeAudioFormat(audio)
        checkNotNull(info)

        val peaks = computeWaveformPeaks(audio, info, bucketCount = 256)

        checkNotNull(peaks)
        val channel = peaks.channels.single()
        assertTrue(channel.max.any { it > 0.1f }, "Expected at least one bucket with a max amplitude above 0.1, got max values: ${channel.max.toList()}")
        assertTrue(channel.min.any { it < -0.1f }, "Expected at least one bucket with a min amplitude below -0.1, got min values: ${channel.min.toList()}")
        audio.delete()
    }

    @Test
    fun `returns null for a file with no decodable audio`() {
        val garbage = File.createTempFile("waveform-peaks-garbage-test-", ".wav")
        garbage.deleteOnExit()
        garbage.writeBytes(ByteArray(100))
        val fakeInfo = AudioFileInfo(sampleRate = 44100, channels = 1, duration = 1.0)

        val peaks = computeWaveformPeaks(garbage, fakeInfo, bucketCount = 256)

        assertNull(peaks)
        garbage.delete()
    }

    @Test
    fun `visibleBucketRange covers the whole array when the window spans the full duration`() {
        val range = visibleBucketRange(AudioViewWindow(0.0, 60.0), totalDuration = 60.0, bucketCount = 4096)
        assertEquals(0, range.first)
        assertEquals(4095, range.last)
    }

    @Test
    fun `visibleBucketRange narrows to the middle of the array for a zoomed-in window`() {
        val range = visibleBucketRange(AudioViewWindow(20.0, 20.0), totalDuration = 60.0, bucketCount = 4096)
        assertEquals((4096 / 3), range.first)
        assertEquals((4096 * 2 / 3) - 1, range.last)
    }

    @Test
    fun `visibleBucketRange never returns an empty or inverted range`() {
        val range = visibleBucketRange(AudioViewWindow(59.9, MIN_VISIBLE_DURATION_SECONDS), totalDuration = 60.0, bucketCount = 4096)
        assertTrue(range.last >= range.first)
    }

    @Test
    fun `waveformBucketCountFor floors at 4096 for short files`() {
        assertEquals(4096, waveformBucketCountFor(0.0))
        assertEquals(4096, waveformBucketCountFor(10.0)) // 10*300 = 3000, below the floor
    }

    @Test
    fun `waveformBucketCountFor scales at 300 buckets per second in the mid range`() {
        assertEquals(30_000, waveformBucketCountFor(100.0))
        assertEquals(90_000, waveformBucketCountFor(300.0))
    }

    @Test
    fun `waveformBucketCountFor caps at 1_800_000 for very long files`() {
        assertEquals(1_800_000, waveformBucketCountFor(20_000.0)) // would be 6,000,000
        assertEquals(1_800_000, waveformBucketCountFor(6_000.0))  // exactly at the cap
    }

    @Test
    fun `downsamplePeaks returns empty for an empty range or zero columns`() {
        val p = ChannelPeaks(FloatArray(10), FloatArray(10))
        assertTrue(downsamplePeaks(p, IntRange.EMPTY, 100).isEmpty())
        assertTrue(downsamplePeaks(p, 0..9, 0).isEmpty())
    }

    @Test
    fun `downsamplePeaks passes buckets through unchanged when the range fits in the target`() {
        val min = floatArrayOf(-0.1f, -0.2f, -0.3f, -0.4f)
        val max = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f)
        val cols = downsamplePeaks(ChannelPeaks(min, max), 1..2, 100)
        assertEquals(listOf(PeakColumn(-0.2f, 0.2f), PeakColumn(-0.3f, 0.3f)), cols)
    }

    @Test
    fun `downsamplePeaks aggregates min and max over each span when buckets exceed the target`() {
        // 8 buckets -> 2 columns: column 0 covers buckets 0..3, column 1 covers 4..7
        val min = floatArrayOf(-0.1f, -0.9f, -0.2f, -0.3f, -0.4f, -0.5f, -0.05f, -0.6f)
        val max = floatArrayOf(0.1f, 0.2f, 0.8f, 0.3f, 0.4f, 0.5f, 0.7f, 0.6f)
        val cols = downsamplePeaks(ChannelPeaks(min, max), 0..7, 2)
        assertEquals(2, cols.size)
        assertEquals(PeakColumn(-0.9f, 0.8f), cols[0]) // deepest min + tallest max in buckets 0..3
        assertEquals(PeakColumn(-0.6f, 0.7f), cols[1]) // buckets 4..7
    }

    @Test
    fun `downsamplePeaks preserves a lone spike through heavy downsampling`() {
        val min = FloatArray(1000)
        val max = FloatArray(1000)
        max[473] = 0.95f
        val cols = downsamplePeaks(ChannelPeaks(min, max), 0..999, 10)
        assertEquals(10, cols.size)
        assertEquals(0.95f, cols.maxOf { it.max })
    }

    private fun forEachPeakColumnList(peaks: ChannelPeaks, visibleRange: IntRange, targetColumns: Int): List<PeakColumn> {
        val out = ArrayList<PeakColumn>()
        var expectedCount = -1
        forEachPeakColumn(peaks, visibleRange, targetColumns) { idx, columnCount, mn, mx ->
            if (expectedCount < 0) expectedCount = columnCount
            assertEquals(expectedCount, columnCount, "columnCount must be constant within one invocation")
            assertEquals(out.size, idx, "columnIndex must run 0 until columnCount in order")
            out.add(PeakColumn(mn, mx))
        }
        if (expectedCount >= 0) assertEquals(expectedCount, out.size, "emitted column count must equal reported columnCount")
        return out
    }

    @Test
    fun `forEachPeakColumn emits the same sequence as downsamplePeaks`() {
        // Aggregation case: buckets far exceed the target.
        val min = FloatArray(1000) { -0.001f * it }
        val max = FloatArray(1000) { 0.002f * it }
        max[473] = 0.95f
        min[512] = -0.9f
        val big = ChannelPeaks(min, max)
        assertEquals(downsamplePeaks(big, 0..999, 10), forEachPeakColumnList(big, 0..999, 10))
        assertEquals(downsamplePeaks(big, 100..800, 64), forEachPeakColumnList(big, 100..800, 64))

        // Passthrough case: range already fits in the target (including out-of-bounds tail).
        val small = ChannelPeaks(floatArrayOf(-0.1f, -0.2f, -0.3f, -0.4f), floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f))
        assertEquals(downsamplePeaks(small, 1..2, 100), forEachPeakColumnList(small, 1..2, 100))
        assertEquals(downsamplePeaks(small, 0..6, 100), forEachPeakColumnList(small, 0..6, 100))

        // Degenerate inputs emit nothing.
        assertTrue(forEachPeakColumnList(small, IntRange.EMPTY, 100).isEmpty())
        assertTrue(forEachPeakColumnList(small, 0..3, 0).isEmpty())

        // Explicit expected values (not just self-consistency with downsamplePeaks).
        val agg = ChannelPeaks(
            floatArrayOf(-0.1f, -0.9f, -0.2f, -0.3f, -0.4f, -0.5f, -0.05f, -0.6f),
            floatArrayOf(0.1f, 0.2f, 0.8f, 0.3f, 0.4f, 0.5f, 0.7f, 0.6f),
        )
        assertEquals(
            listOf(PeakColumn(-0.9f, 0.8f), PeakColumn(-0.6f, 0.7f)),
            forEachPeakColumnList(agg, 0..7, 2),
        )
        assertEquals(
            listOf(PeakColumn(-0.2f, 0.2f), PeakColumn(-0.3f, 0.3f)),
            forEachPeakColumnList(small, 1..2, 100),
        )
    }
}
