package com.multiviewer.ui

import java.io.File

enum class RawAudioFormat(val label: String, val bytesPerSample: Int, val needsByteOrder: Boolean) {
    U8("8-bit unsigned", 1, false),
    S16("16-bit signed", 2, true),
    S24("24-bit signed", 3, true),
    S32("32-bit signed", 4, true),
    F32("32-bit float", 4, true),
}

enum class RawAudioByteOrder(val label: String) {
    LITTLE_ENDIAN("Little-endian"),
    BIG_ENDIAN("Big-endian"),
}

data class RawAudioParams(
    val sampleRate: Int,
    val channels: Int,
    val format: RawAudioFormat,
    val byteOrder: RawAudioByteOrder,
    val offsetBytes: Long,
)

// ffmpeg's raw-PCM format codes (passed as the argument to -f, before -i, when the input has no
// self-describing header) -- e.g. "s16le" = 16-bit signed little-endian. U8 has no byte-order
// axis (a single byte can't have endianness), so byteOrder is ignored for that case.
fun RawAudioParams.ffmpegFormatCode(): String {
    val suffix = if (byteOrder == RawAudioByteOrder.LITTLE_ENDIAN) "le" else "be"
    return when (format) {
        RawAudioFormat.U8 -> "u8"
        RawAudioFormat.S16 -> "s16$suffix"
        RawAudioFormat.S24 -> "s24$suffix"
        RawAudioFormat.S32 -> "s32$suffix"
        RawAudioFormat.F32 -> "f32$suffix"
    }
}

// Total playable duration once the leading offsetBytes are skipped -- used both for the open
// dialog's live preview and to build an AudioFileInfo without ffprobe (raw PCM has no header for
// ffprobe to read).
fun computeRawAudioDuration(fileSize: Long, offsetBytes: Long, sampleRate: Int, channels: Int, bytesPerSample: Int): Double {
    val playableBytes = (fileSize - offsetBytes).coerceAtLeast(0L)
    val frameSizeBytes = channels * bytesPerSample
    if (frameSizeBytes <= 0 || sampleRate <= 0) return 0.0
    val totalFrames = playableBytes / frameSizeBytes
    return totalFrames.toDouble() / sampleRate.toDouble()
}

// Raw PCM has no header, so an offset > 0 means real audio data doesn't start at byte 0 -- ffmpeg
// needs a file where it does. Mirrors RawPixelDecoder.decodeYuvFamily's temp-file pattern: when
// there's nothing to skip, hand back the original file untouched (no copy needed).
fun rawAudioSourceFile(original: File, offsetBytes: Long): File {
    if (offsetBytes <= 0L) return original
    val temp = File.createTempFile("raw-audio-offset-", ".pcm")
    temp.deleteOnExit()
    original.inputStream().use { input ->
        input.skip(offsetBytes)
        temp.outputStream().use { output -> input.copyTo(output) }
    }
    return temp
}
