package com.multiviewer.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FfmpegAudioPlayerTest {
    @Test
    fun `probeAudioFormat reads sample rate, channels, and duration from a real audio file`() {
        val audio = File.createTempFile("ffmpeg-audio-probe-test-", ".wav")
        audio.deleteOnExit()
        ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=3:sample_rate=48000",
            "-ac", "2", audio.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()

        val info = probeAudioFormat(audio)

        assertEquals(48000, info?.sampleRate)
        assertEquals(2, info?.channels)
        assertTrue(info != null && info.duration > 2.9 && info.duration < 3.1, "expected duration near 3.0s, got ${info?.duration}")
        audio.delete()
    }

    @Test
    fun `probeAudioFormat returns null for a nonexistent file`() {
        assertNull(probeAudioFormat(File("/nonexistent/path/does-not-exist.wav")))
    }

    @Test
    fun `channelModeFilterArgs maps each mode to the right ffmpeg pan filter`() {
        assertEquals(emptyList(), channelModeFilterArgs(ChannelMode.STEREO))
        assertEquals(listOf("-af", "pan=stereo|c0=c0|c1=c0"), channelModeFilterArgs(ChannelMode.LEFT))
        assertEquals(listOf("-af", "pan=stereo|c0=c1|c1=c1"), channelModeFilterArgs(ChannelMode.RIGHT))
    }
}
