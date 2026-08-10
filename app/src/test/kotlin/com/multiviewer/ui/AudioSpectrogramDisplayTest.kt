package com.multiviewer.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AudioSpectrogramDisplayTest {
    @Test
    fun `renders a fixed-size bitmap for a real audio file`() {
        val audio = File.createTempFile("spectrogram-display-test-", ".wav")
        audio.deleteOnExit()
        ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "sine=duration=1:frequency=440",
            "-ac", "1", "-c:a", "pcm_s16le", audio.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()

        val bitmap = generateFullSpectrogramImage(audio)

        checkNotNull(bitmap)
        assertEquals(SPECTROGRAM_WIDTH_PX, bitmap.width)
        assertEquals(SPECTROGRAM_HEIGHT_PX, bitmap.height)
        audio.delete()
    }

    @Test
    fun `returns null for a file with no decodable audio`() {
        val garbage = File.createTempFile("spectrogram-display-garbage-test-", ".wav")
        garbage.deleteOnExit()
        garbage.writeBytes(ByteArray(100))

        val bitmap = generateFullSpectrogramImage(garbage)

        assertNull(bitmap)
        garbage.delete()
    }
}
