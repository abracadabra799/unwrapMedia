package com.multiviewer.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RawAudioDecoderTest {
    @Test
    fun `computes ffmpeg format codes for every format and byte order combination`() {
        assertEquals("u8", RawAudioParams(44100, 1, RawAudioFormat.U8, RawAudioByteOrder.LITTLE_ENDIAN, 0).ffmpegFormatCode())
        assertEquals("u8", RawAudioParams(44100, 1, RawAudioFormat.U8, RawAudioByteOrder.BIG_ENDIAN, 0).ffmpegFormatCode())
        assertEquals("s16le", RawAudioParams(44100, 1, RawAudioFormat.S16, RawAudioByteOrder.LITTLE_ENDIAN, 0).ffmpegFormatCode())
        assertEquals("s16be", RawAudioParams(44100, 1, RawAudioFormat.S16, RawAudioByteOrder.BIG_ENDIAN, 0).ffmpegFormatCode())
        assertEquals("s24le", RawAudioParams(44100, 1, RawAudioFormat.S24, RawAudioByteOrder.LITTLE_ENDIAN, 0).ffmpegFormatCode())
        assertEquals("s24be", RawAudioParams(44100, 1, RawAudioFormat.S24, RawAudioByteOrder.BIG_ENDIAN, 0).ffmpegFormatCode())
        assertEquals("s32le", RawAudioParams(44100, 1, RawAudioFormat.S32, RawAudioByteOrder.LITTLE_ENDIAN, 0).ffmpegFormatCode())
        assertEquals("s32be", RawAudioParams(44100, 1, RawAudioFormat.S32, RawAudioByteOrder.BIG_ENDIAN, 0).ffmpegFormatCode())
        assertEquals("f32le", RawAudioParams(44100, 1, RawAudioFormat.F32, RawAudioByteOrder.LITTLE_ENDIAN, 0).ffmpegFormatCode())
        assertEquals("f32be", RawAudioParams(44100, 1, RawAudioFormat.F32, RawAudioByteOrder.BIG_ENDIAN, 0).ffmpegFormatCode())
    }

    @Test
    fun `computes duration from file size, sample rate, and channels`() {
        // 44100 Hz, 1 channel, 2 bytes/sample -> 88200 bytes/second. A 3-second stream is 264600 bytes.
        val duration = computeRawAudioDuration(fileSize = 264600, offsetBytes = 0, sampleRate = 44100, channels = 1, bytesPerSample = 2)
        assertEquals(3.0, duration)
    }

    @Test
    fun `subtracts the offset before computing duration`() {
        // A 1-second (88200-byte) header in front of the same 3-second (264600-byte) payload as
        // above -- skipping the header via offsetBytes should still measure exactly 3 seconds.
        val duration = computeRawAudioDuration(fileSize = 264600 + 88200, offsetBytes = 88200, sampleRate = 44100, channels = 1, bytesPerSample = 2)
        assertEquals(3.0, duration)
    }

    @Test
    fun `duration is zero when the offset consumes the whole file`() {
        val duration = computeRawAudioDuration(fileSize = 1000, offsetBytes = 5000, sampleRate = 44100, channels = 1, bytesPerSample = 2)
        assertEquals(0.0, duration)
    }

    @Test
    fun `raw input args skip the leading offset inside ffmpeg`() {
        val params = RawAudioParams(48000, 2, RawAudioFormat.S16, RawAudioByteOrder.LITTLE_ENDIAN, 1000)
        assertEquals(listOf("-skip_initial_bytes", "1000", "-f", "s16le", "-ar", "48000", "-ac", "2"), params.ffmpegInputArgs())
        assertEquals(listOf("-f", "s16le", "-ar", "48000", "-ac", "2"), params.copy(offsetBytes = 0).ffmpegInputArgs())
    }

    // Verified against ffmpeg 8.1: -skip_initial_bytes decodes byte-identically to the old
    // copy-everything-after-the-offset temp file, with and without -ss.
    @Test
    fun `waveform of an offset raw file matches the same PCM without the junk header`() {
        val dir = java.nio.file.Files.createTempDirectory("raw-offset-test").toFile()
        try {
            val pcm = File(dir, "pcm.raw")
            ProcessBuilder("ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=1",
                "-ac", "2", "-ar", "48000", "-f", "s16le", pcm.absolutePath).start().waitFor()
            val withHeader = File(dir, "with-header.raw").apply { writeBytes(ByteArray(1000) { 0x7F } + pcm.readBytes()) }
            val params = RawAudioParams(48000, 2, RawAudioFormat.S16, RawAudioByteOrder.LITTLE_ENDIAN, 0)
            val info = AudioFileInfo(48000, 2, 1.0)
            val expected = computeWaveformPeaks(pcm, info, bucketCount = 64, rawAudioParams = params)!!
            val actual = computeWaveformPeaks(withHeader, info, bucketCount = 64, rawAudioParams = params.copy(offsetBytes = 1000))!!
            for (c in expected.channels.indices) {
                assertTrue(expected.channels[c].min.contentEquals(actual.channels[c].min))
                assertTrue(expected.channels[c].max.contentEquals(actual.channels[c].max))
            }
            assertTrue(dir.listFiles()!!.size == 2, "no temp copy may be written next to the inputs")
        } finally {
            dir.deleteRecursively()
        }
    }
}
