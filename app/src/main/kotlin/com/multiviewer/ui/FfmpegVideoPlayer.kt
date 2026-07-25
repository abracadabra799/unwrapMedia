package com.multiviewer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorInfo
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.awt.EventQueue
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class VideoInfo(val width: Int, val height: Int, val fps: Double)

fun parseFrameRate(fraction: String): Double? {
    val parts = fraction.split("/")
    val num = parts.getOrNull(0)?.toDoubleOrNull() ?: return null
    val den = parts.getOrNull(1)?.toDoubleOrNull() ?: return null
    if (den == 0.0 || num == 0.0) return null
    return num / den
}

fun probeVideo(file: File): VideoInfo? {
    return try {
        val process = ProcessBuilder(
            FfmpegLocator.ffprobePath(), "-v", "error", "-select_streams", "v:0",
            "-show_entries", "stream=width,height,avg_frame_rate,r_frame_rate:stream_side_data=rotation",
            "-of", "csv=p=0", file.absolutePath,
        ).redirectErrorStream(false).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val line = process.inputStream.bufferedReader().readLine()
        process.waitFor(5, TimeUnit.SECONDS)
        if (line == null) return null
        val parts = line.split(",")
        if (parts.size < 4) return null
        var width = parts[0].toIntOrNull() ?: return null
        var height = parts[1].toIntOrNull() ?: return null
        val fps = parseFrameRate(parts[2]) ?: parseFrameRate(parts[3]) ?: 30.0
        // ffmpeg auto-applies rotation side-data when transcoding to rawvideo, so the
        // actual piped frame dimensions are swapped from ffprobe's raw stream dimensions
        // whenever the stream is rotated a quarter turn.
        val rotation = parts.getOrNull(4)?.toIntOrNull() ?: 0
        if (Math.abs(rotation) == 90 || Math.abs(rotation) == 270) {
            val tmp = width
            width = height
            height = tmp
        }
        VideoInfo(width, height, fps)
    } catch (e: Exception) {
        null
    }
}

@Composable
fun FfmpegVideoPlayer(file: File, modifier: Modifier = Modifier) {
    var videoBitmap by remember(file) { mutableStateOf<ImageBitmap?>(null, neverEqualPolicy()) }
    var isPlaying by remember(file) { mutableStateOf(false) }
    var loadError by remember(file) { mutableStateOf(false) }

    val info = remember(file) { probeVideo(file) }

    if (info == null) {
        Box(modifier.fillMaxSize().background(Color.DarkGray), contentAlignment = Alignment.Center) {
            Text("Could not read video (is ffmpeg installed?)", color = Color.White)
        }
        return
    }

    DisposableEffect(file) {
        val process = try {
            ProcessBuilder(
                FfmpegLocator.ffmpegPath(), "-i", file.absolutePath,
                "-f", "rawvideo", "-pix_fmt", "bgra", "-an",
                "-r", info.fps.toString(), "-",
            ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        } catch (e: Exception) {
            null
        }
        // probeVideo() already succeeded, so ffmpeg/ffprobe are known to work in general -- if this
        // second, separate process still fails to start, that's a genuine failure to surface, not
        // silent: without this flag, the UI would otherwise sit on "Decoding stream..." forever.
        if (process == null) loadError = true

        val stopped = AtomicBoolean(false)

        val readerThread = if (process != null) {
            Thread {
                val frameSize = info.width * info.height * 4
                val buffer = ByteArray(frameSize)
                val frameDurationMs = (1000.0 / info.fps).toLong()
                val input = process.inputStream

                fun readFrame(): Boolean {
                    var offset = 0
                    while (offset < frameSize) {
                        val read = input.read(buffer, offset, frameSize - offset)
                        if (read < 0) return false
                        offset += read
                    }
                    return true
                }

                fun deliver() {
                    val bitmap = Bitmap().apply {
                        allocPixels(ImageInfo(ColorInfo(ColorType.BGRA_8888, ColorAlphaType.PREMUL, ColorSpace.sRGB), info.width, info.height))
                        installPixels(imageInfo, buffer, info.width * 4)
                    }
                    val snapshot = Image.makeFromBitmap(bitmap).toComposeImageBitmap()
                    EventQueue.invokeLater { videoBitmap = snapshot }
                }

                if (readFrame()) deliver() // first frame, shown immediately while paused
                while (!stopped.get()) {
                    if (!isPlaying) {
                        Thread.sleep(50)
                        continue
                    }
                    val start = System.currentTimeMillis()
                    if (!readFrame()) break // EOF
                    deliver()
                    val remaining = frameDurationMs - (System.currentTimeMillis() - start)
                    if (remaining > 0) Thread.sleep(remaining)
                }
            }.apply { isDaemon = true }.also { it.start() }
        } else {
            null
        }

        onDispose {
            stopped.set(true)
            readerThread?.interrupt()
            process?.destroyForcibly()
        }
    }

    Box(
        modifier = modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        val currentFrame = videoBitmap
        if (loadError) {
            Text("Could not start ffmpeg playback", color = Color.White)
        } else if (currentFrame != null) {
            Image(bitmap = currentFrame, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Decoding stream...", color = Color.Gray)
                Text("File: ${file.name}", color = Color.DarkGray, fontSize = 10.sp)
            }
        }

        if (!isPlaying) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable { isPlaying = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = Color.White, modifier = Modifier.size(48.dp))
            }
        } else {
            Box(modifier = Modifier.fillMaxSize().clickable { isPlaying = false })
        }
    }
}
