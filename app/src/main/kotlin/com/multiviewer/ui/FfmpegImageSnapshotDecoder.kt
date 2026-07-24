package com.multiviewer.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image
import java.awt.EventQueue
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Headless, one-shot fallback for images Skia's Image.makeFromEncoded can't decode (HEIC/HEVC and
 * other HEIF-family stills). Shells out to a system `ffmpeg` (must be on PATH) to extract the
 * primary frame as a temporary PNG, then decodes that PNG via Skia like any other supported image.
 */
object FfmpegImageSnapshotDecoder {
    private const val TIMEOUT_MS = 8000L

    fun decodeFirstFrameAsync(file: File, onResult: (ImageBitmap?) -> Unit) {
        Thread {
            val tempPng = try {
                File.createTempFile("ffmpeg-snapshot-", ".png")
            } catch (e: Exception) {
                EventQueue.invokeLater { onResult(null) }
                return@Thread
            }
            tempPng.deleteOnExit()

            val result = try {
                val process = ProcessBuilder(
                    "ffmpeg", "-y", "-i", file.absolutePath,
                    "-frames:v", "1", "-update", "1",
                    tempPng.absolutePath,
                )
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()

                val finished = process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    null
                } else if (process.exitValue() != 0 || tempPng.length() == 0L) {
                    null
                } else {
                    Image.makeFromEncoded(tempPng.readBytes()).toComposeImageBitmap()
                }
            } catch (e: Exception) {
                // ProcessBuilder.start() throws IOException when `ffmpeg` isn't on PATH.
                null
            } finally {
                tempPng.delete()
            }

            EventQueue.invokeLater { onResult(result) }
        }.apply { isDaemon = true }.start()
    }
}
