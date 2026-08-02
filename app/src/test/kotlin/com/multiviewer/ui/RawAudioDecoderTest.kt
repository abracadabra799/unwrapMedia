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
    fun `zero offset returns the original file unchanged`() {
        val file = File.createTempFile("raw-audio-decoder-test-", ".pcm")
        file.deleteOnExit()
        file.writeBytes(byteArrayOf(1, 2, 3, 4))
        val result = rawAudioSourceFile(file, 0L)
        assertEquals(file.absolutePath, result.absolutePath)
        file.delete()
    }

    @Test
    fun `positive offset produces a new file containing only the bytes after the offset`() {
        val file = File.createTempFile("raw-audio-decoder-test-", ".pcm")
        file.deleteOnExit()
        file.writeBytes(byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 1, 2, 3, 4))
        val result = rawAudioSourceFile(file, 2L)
        assertTrue(result.absolutePath != file.absolutePath)
        assertEquals(listOf<Byte>(1, 2, 3, 4), result.readBytes().toList())
        file.delete()
        result.delete()
    }
}
