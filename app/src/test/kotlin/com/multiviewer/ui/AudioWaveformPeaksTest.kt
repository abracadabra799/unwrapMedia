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
}
