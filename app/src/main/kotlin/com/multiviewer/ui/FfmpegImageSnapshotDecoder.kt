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
    private const val DEFAULT_TIMEOUT_MS = 60_000L

    fun decodeFirstFrameAsync(file: File, root: BoxNode? = null, onResult: (ImageBitmap?) -> Unit) {
        Thread {
            // Attempt 1: Direct full-resolution decode (PNG format to avoid JPEG 65k/buffer limits on 200MP stills)
            var result = decodeSingleFrameToBitmap(
                listOf(FfmpegLocator.ffmpegPath(), "-y", "-i", file.absolutePath, "-frames:v", "1", "-update", "1"),
                tempExtension = ".png",
                timeoutMs = DEFAULT_TIMEOUT_MS,
            )

            // Attempt 2: If full 200MP decode failed or exceeded memory/texture limits, try safe downscaled decode (max 8192)
            if (result == null) {
                result = decodeSingleFrameToBitmap(
                    listOf(
                        FfmpegLocator.ffmpegPath(), "-y", "-i", file.absolutePath,
                        "-vf", "scale='min(8192,iw)':'min(8192,ih)':force_original_aspect_ratio=decrease",
                        "-frames:v", "1", "-update", "1",
                    ),
                    tempExtension = ".png",
                    timeoutMs = 30_000L,
                )
            }

            // Attempt 3: If JPEG fallback is preferred
            if (result == null) {
                result = decodeSingleFrameToBitmap(
                    listOf(FfmpegLocator.ffmpegPath(), "-y", "-i", file.absolutePath, "-frames:v", "1", "-update", "1"),
                    tempExtension = ".jpg",
                    timeoutMs = 15_000L,
                )
            }

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
            val result = try {
                tempH265.writeBytes(annexB)
                decodeSingleFrameToBitmap(
                    listOf(FfmpegLocator.ffmpegPath(), "-y", "-f", "hevc", "-i", tempH265.absolutePath, "-frames:v", "1", "-update", "1"),
                    timeoutMs = 15_000L,
                )
            } finally {
                tempH265.delete()
            }
            EventQueue.invokeLater { onResult(result) }
        }.apply { isDaemon = true }.start()
    }

    // Shared "ffmpeg <inputArgs> -> one JPEG/PNG frame -> Skia decode" pipeline. Runs synchronously on
    // the caller's own thread.
    internal fun decodeSingleFrameToBitmap(
        inputArgs: List<String>,
        tempExtension: String = ".png",
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): ImageBitmap? {
        val tempFile = try {
            File.createTempFile("ffmpeg-snapshot-", tempExtension)
        } catch (e: Exception) {
            return null
        }
        // No deleteOnExit(): the finally below deletes it on every path, and this runs once per
        // decoded snapshot -- see RawPixelDecoder.decodeYuvFamily for why that pairing leaks.
        var process: Process? = null
        return try {
            process = ProcessBuilder(inputArgs + listOf(tempFile.absolutePath))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .also { FfmpegLocator.configureEnvironment(it) }
                .start()
                .also { com.multiviewer.util.ProcessManager.register(it) }

            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                com.multiviewer.util.ProcessManager.terminate(process)
                null
            } else if (process.exitValue() != 0 || tempFile.length() == 0L) {
                null
            } else {
                try {
                    val bytes = tempFile.readBytes()
                    val skiaImg = Image.makeFromEncoded(bytes)
                    skiaImg?.toComposeImageBitmap()
                } catch (oom: OutOfMemoryError) {
                    System.gc()
                    null
                } catch (e: Exception) {
                    null
                }
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
