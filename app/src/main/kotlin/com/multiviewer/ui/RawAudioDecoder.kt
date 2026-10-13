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

// Input-side ffmpeg args (before -i) for a headerless PCM file. An offset > 0 means the real audio
// starts past byte 0: -skip_initial_bytes makes ffmpeg skip it itself. This replaced copying the
// rest of the file into a temp file on every playback (re)start -- i.e. every seek, on the UI
// thread -- verified byte-identical output against ffmpeg 8.1, with and without -ss.
fun RawAudioParams.ffmpegInputArgs(): List<String> =
    (if (offsetBytes > 0L) listOf("-skip_initial_bytes", offsetBytes.toString()) else emptyList()) +
        listOf("-f", ffmpegFormatCode(), "-ar", sampleRate.toString(), "-ac", channels.toString())
