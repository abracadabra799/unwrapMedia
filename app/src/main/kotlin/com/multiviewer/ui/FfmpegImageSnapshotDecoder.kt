package com.multiviewer.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.extractHevcThumbnailAnnexB
import org.jetbrains.skia.Image
import java.awt.EventQueue
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Headless, one-shot fallback for images Skia's Image.makeFromEncoded can't decode (HEIC/HEVC and
 * other HEIF-family stills). Shells out to ffmpeg (via FfmpegLocator -- the packaged app's bundled
 * binary if present, otherwise PATH) to extract the primary frame as a temporary PNG, then decodes
 * that PNG via Skia like any other supported image.
 */
object FfmpegImageSnapshotDecoder {
    private const val TIMEOUT_MS = 8000L

    fun decodeFirstFrameAsync(file: File, onResult: (ImageBitmap?) -> Unit) {
        Thread {
            val result = decodeSingleFrameToBitmap(
                listOf(FfmpegLocator.ffmpegPath(), "-y", "-i", file.absolutePath, "-frames:v", "1", "-update", "1"),
            )
            EventQueue.invokeLater { onResult(result) }
        }.apply { isDaemon = true }.start()
    }

    // Decodes a HEIC's embedded "thmb" item (see extractHevcThumbnailAnnexB) via ffmpeg's raw HEVC
    // demuxer -- this parser's own fast-path thumbnail extraction only handles JPEG-coded items;
    // modern HEIC thumbnail items are commonly HEVC-coded instead, which is what this covers.
    fun decodeEmbeddedHevcThumbnailAsync(file: File, root: BoxNode, onResult: (ImageBitmap?) -> Unit) {
        Thread {
            val annexB = try {
                extractHevcThumbnailAnnexB(file, root)
            } catch (e: Exception) {
                null
            }
            if (annexB == null) {
                EventQueue.invokeLater { onResult(null) }
                return@Thread
            }
            val tempH265 = try {
                File.createTempFile("hevc-thumb-item-", ".h265")
            } catch (e: Exception) {
                EventQueue.invokeLater { onResult(null) }
                return@Thread
            }
            tempH265.deleteOnExit()
            val result = try {
                tempH265.writeBytes(annexB)
                decodeSingleFrameToBitmap(
                    listOf(FfmpegLocator.ffmpegPath(), "-y", "-f", "hevc", "-i", tempH265.absolutePath, "-frames:v", "1", "-update", "1"),
                )
            } finally {
                tempH265.delete()
            }
            EventQueue.invokeLater { onResult(result) }
        }.apply { isDaemon = true }.start()
    }

    // Shared "ffmpeg <inputArgs> -> one JPEG/PNG frame -> Skia decode" pipeline. Runs synchronously on
    // the caller's own thread.
    internal fun decodeSingleFrameToBitmap(inputArgs: List<String>, tempExtension: String = ".jpg"): ImageBitmap? {
        val tempFile = try {
            File.createTempFile("ffmpeg-snapshot-", tempExtension)
        } catch (e: Exception) {
            return null
        }
        tempFile.deleteOnExit()

        var process: Process? = null
        return try {
            process = ProcessBuilder(inputArgs + listOf(tempFile.absolutePath))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .also { FfmpegLocator.configureEnvironment(it) }
                .start()
                .also { com.multiviewer.util.ProcessManager.register(it) }

            val finished = process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!finished) {
                com.multiviewer.util.ProcessManager.terminate(process)
                null
            } else if (process.exitValue() != 0 || tempFile.length() == 0L) {
                null
            } else {
                Image.makeFromEncoded(tempFile.readBytes()).toComposeImageBitmap()
            }
        } catch (e: Exception) {
            // ProcessBuilder.start() throws IOException when `ffmpeg` isn't on PATH.
            null
        } finally {
            process?.let { com.multiviewer.util.ProcessManager.unregister(it) }
            tempFile.delete()
        }
    }
}
