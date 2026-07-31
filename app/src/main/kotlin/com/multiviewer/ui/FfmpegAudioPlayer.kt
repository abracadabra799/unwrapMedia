package com.multiviewer.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image
import java.io.File
import java.util.concurrent.TimeUnit

data class AudioFileInfo(val sampleRate: Int, val channels: Int, val duration: Double)

fun probeAudioFormat(file: File): AudioFileInfo? {
    return try {
        val process = ProcessBuilder(
            FfmpegLocator.ffprobePath(), "-v", "error", "-select_streams", "a:0",
            "-show_entries", "stream=sample_rate,channels,duration",
            "-of", "default=noprint_wrappers=1", file.absolutePath,
        ).redirectErrorStream(false).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val lines = process.inputStream.bufferedReader().readLines()
        process.waitFor(5, TimeUnit.SECONDS)

        val values = mutableMapOf<String, String>()
        for (line in lines) {
            val eq = line.indexOf('=')
            if (eq < 0) continue
            values[line.substring(0, eq)] = line.substring(eq + 1)
        }
        val sampleRate = values["sample_rate"]?.toIntOrNull() ?: return null
        val channels = values["channels"]?.toIntOrNull() ?: return null
        val duration = values["duration"]?.toDoubleOrNull() ?: 0.0
        AudioFileInfo(sampleRate, channels, duration)
    } catch (e: Exception) {
        null
    }
}

private const val AUDIO_VISUAL_TIMEOUT_MS = 10000L

// Renders a whole-file waveform/spectrogram overview via ffmpeg's own showwavespic/showspectrumpic
// filters -- both already implement the standard min/max-per-column peak-decimation technique
// audio editors use for this, so there's no need to hand-roll PCM bucketing or FFT in Kotlin.
// Follows the same temp-file ffmpeg-image-extraction convention as
// FfmpegImageSnapshotDecoder.decodeSingleFrameToBitmap: write to a temp PNG, wait with a timeout,
// check exit code and file size, decode via Skia, always clean up the temp file.
private fun renderAudioVisualization(file: File, filter: String): ImageBitmap? {
    val tempPng = try {
        File.createTempFile("audio-visual-", ".png")
    } catch (e: Exception) {
        return null
    }
    tempPng.deleteOnExit()
    return try {
        val process = ProcessBuilder(
            FfmpegLocator.ffmpegPath(), "-y", "-i", file.absolutePath,
            "-lavfi", filter, "-frames:v", "1", tempPng.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val finished = process.waitFor(AUDIO_VISUAL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            null
        } else if (process.exitValue() != 0 || tempPng.length() == 0L) {
            null
        } else {
            Image.makeFromEncoded(tempPng.readBytes()).toComposeImageBitmap()
        }
    } catch (e: Exception) {
        null
    } finally {
        tempPng.delete()
    }
}

fun generateWaveformImage(file: File, width: Int, height: Int): ImageBitmap? =
    renderAudioVisualization(file, "showwavespic=s=${width}x${height}:colors=0x39FF14")

fun generateSpectrogramImage(file: File, width: Int, height: Int): ImageBitmap? =
    renderAudioVisualization(file, "showspectrumpic=s=${width}x${height},scale=${width}:${height}:force_original_aspect_ratio=decrease,pad=${width}:${height}:(ow-iw)/2:(oh-ih)/2")
