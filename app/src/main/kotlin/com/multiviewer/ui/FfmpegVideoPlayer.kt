package com.multiviewer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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

data class VideoInfo(val width: Int, val height: Int, val fps: Double, val rotation: Int = 0, val duration: Double = 0.0)

fun formatMmSs(seconds: Double): String {
    val total = seconds.toLong()
    return "%d:%02d".format(total / 60, total % 60)
}

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
            "-show_entries", "stream=width,height,avg_frame_rate,r_frame_rate,duration:stream_side_data=rotation",
            // -of csv=p=0 does NOT preserve the field order given in -show_entries -- ffprobe
            // emits fields in the stream struct's internal order regardless of request order
            // (same quirk already worked around in FrameTypeAnalyzer.kt). On a real device video
            // where r_frame_rate (a container timebase artifact, e.g. 120) differs from
            // avg_frame_rate (the true playback rate, e.g. ~30.3), csv put r_frame_rate first,
            // so this code was reading it as avg_frame_rate and pacing playback at 120fps against
            // ~30fps content -- ffmpeg then had to quadruple-duplicate frames to match, and
            // reading+converting each 6.9MB frame took longer than the resulting 8ms-per-frame
            // budget, so playback fell permanently behind (a 3s clip took 10+s). Verified via a
            // direct ffprobe CSV dump against a real motion-photo video: r_frame_rate=120/1 and
            // avg_frame_rate=705000/23249 came back in swapped CSV column order. default=
            // noprint_wrappers=1 gives unambiguous key=value pairs instead.
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

        var width = values["width"]?.toIntOrNull() ?: return null
        var height = values["height"]?.toIntOrNull() ?: return null
        val fps = values["avg_frame_rate"]?.let(::parseFrameRate)
            ?: values["r_frame_rate"]?.let(::parseFrameRate)
            ?: 30.0
        val duration = values["duration"]?.toDoubleOrNull() ?: 0.0
        // ffmpeg auto-applies rotation side-data when transcoding to rawvideo, so the
        // actual piped frame dimensions are swapped from ffprobe's raw stream dimensions
        // whenever the stream is rotated a quarter turn.
        val rotation = values["rotation"]?.toIntOrNull() ?: 0
        if (Math.abs(rotation) == 90 || Math.abs(rotation) == 270) {
            val tmp = width
            width = height
            height = tmp
        }
        VideoInfo(width, height, fps, rotation, duration)
    } catch (e: Exception) {
        null
    }
}

@Composable
fun FfmpegVideoPlayer(
    file: File,
    modifier: Modifier = Modifier,
    onElapsedChanged: (Double) -> Unit = {},
    seekRequestSeconds: Double = 0.0,
    seekRequestTick: Int = 0,
) {
    var videoBitmap by remember(file) { mutableStateOf<ImageBitmap?>(null, neverEqualPolicy()) }
    var isPlaying by remember(file) { mutableStateOf(false) }
    var loadError by remember(file) { mutableStateOf(false) }
    // ffmpeg's rawvideo pipe is one-shot -- once it hits EOF, that process is done and can't be
    // rewound. Reaching the end sets hasEnded and stops isPlaying (below); clicking play again
    // while hasEnded bumps restartTrigger, which re-keys the DisposableEffect to tear down and
    // spawn a fresh ffmpeg process from the start of the file.
    var hasEnded by remember(file) { mutableStateOf(false) }
    var restartTrigger by remember(file) { mutableStateOf(0) }
    // Sum of each delivered frame's REAL duration (from ffmpeg's showinfo filter, see the
    // DisposableEffect below), not a frame count / average fps -- a flat average badly
    // misrepresents elapsed time for variable-frame-rate content, where individual frame durations
    // can differ substantially from the file's average.
    var playedSeconds by remember(file) { mutableStateOf(0.0) }
    // Where the currently-running ffmpeg process started decoding from -- 0.0 for a normal replay
    // from the beginning, or a GOP-frame-click seek target otherwise. Piping raw frames from a
    // fresh ffmpeg process is the only way to "seek" (see restartTrigger above), and -ss before -i
    // resets the piped stream's own pts to ~0, so this offset is added back to playedSeconds
    // everywhere elapsed position is reported, keeping it in absolute video time.
    var startFromSeconds by remember(file) { mutableStateOf(0.0) }
    var lastHandledSeekTick by remember(file) { mutableStateOf(0) }

    LaunchedEffect(seekRequestTick) {
        if (seekRequestTick != lastHandledSeekTick) {
            lastHandledSeekTick = seekRequestTick
            startFromSeconds = seekRequestSeconds
            hasEnded = false
            isPlaying = false // seek-and-pause: show the requested frame rather than resuming playback
            restartTrigger++
        }
    }

    val info = remember(file) { probeVideo(file) }

    if (info == null) {
        Box(modifier.fillMaxSize().background(Color.DarkGray), contentAlignment = Alignment.Center) {
            Text("Could not read video (is ffmpeg installed?)", color = Color.White)
        }
        return
    }

    DisposableEffect(file, restartTrigger) {
        playedSeconds = 0.0
        val seekSeconds = startFromSeconds
        val seekArgs = if (seekSeconds > 0.0) listOf("-ss", seekSeconds.toString()) else emptyList()
        val process = try {
            ProcessBuilder(
                listOf(FfmpegLocator.ffmpegPath()) + seekArgs + listOf(
                    "-i", file.absolutePath,
                    // showinfo logs each decoded frame's real duration_time to stderr, in the same
                    // order frames are written to stdout -- read alongside the raw pixels below and
                    // used to pace playback per-frame instead of a flat average. No -r output option
                    // here: forcing a constant output rate makes ffmpeg duplicate/drop frames to hit
                    // it, which is exactly the "ignores real frame timing" behavior this replaces.
                    "-vf", "showinfo",
                    "-f", "rawvideo", "-pix_fmt", "bgra", "-an", "-",
                ),
            ).start()
        } catch (e: Exception) {
            null
        }
        // probeVideo() already succeeded, so ffmpeg/ffprobe are known to work in general -- if this
        // second, separate process still fails to start, that's a genuine failure to surface, not
        // silent: without this flag, the UI would otherwise sit on "Decoding stream..." forever.
        if (process == null) loadError = true

        val stopped = AtomicBoolean(false)
        // showinfo lines land on stderr as frames are decoded, in the same order they're written to
        // stdout -- drained on its own thread into this queue so a slow/bursty OS pipe on either
        // side never blocks the other. Unbounded: duration lines are tiny (~150 bytes each).
        val durationQueue = java.util.concurrent.LinkedBlockingQueue<Double>()
        val durationRegex = Regex("duration_time:([0-9.]+)")

        val stderrThread = if (process != null) {
            Thread {
                try {
                    process.errorStream.bufferedReader().forEachLine { line ->
                        durationRegex.find(line)?.groupValues?.get(1)?.toDoubleOrNull()?.let { durationQueue.put(it) }
                    }
                } catch (e: Exception) {
                    // Pipe closed from destroyForcibly() on dispose -- expected, not an error.
                }
            }.apply { isDaemon = true }.also { it.start() }
        } else {
            null
        }

        val readerThread = if (process != null) {
            Thread {
                val frameSize = info.width * info.height * 4
                val buffer = ByteArray(frameSize)
                val fallbackDurationSeconds = 1.0 / info.fps
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

                // Falls back to the average fps if showinfo's line for this frame hasn't arrived
                // within 500ms (should never happen in practice -- stderr and stdout are flushed
                // for the same frame together -- but a stuck pipe must not hang playback forever).
                fun nextFrameDurationSeconds(): Double =
                    durationQueue.poll(500, TimeUnit.MILLISECONDS) ?: fallbackDurationSeconds

                fun deliver(durationSeconds: Double?) {
                    val bitmap = Bitmap().apply {
                        allocPixels(ImageInfo(ColorInfo(ColorType.BGRA_8888, ColorAlphaType.PREMUL, ColorSpace.sRGB), info.width, info.height))
                        installPixels(imageInfo, buffer, info.width * 4)
                    }
                    val snapshot = Image.makeFromBitmap(bitmap).toComposeImageBitmap()
                    EventQueue.invokeLater {
                        videoBitmap = snapshot
                        if (durationSeconds != null) {
                            playedSeconds += durationSeconds
                        }
                    }
                }

                try {
                    // First frame, shown immediately while paused -- still drain its duration line
                    // so the queue stays aligned with frames read from here on, but it doesn't count
                    // toward playedSeconds since nothing played yet.
                    if (readFrame()) {
                        nextFrameDurationSeconds()
                        deliver(null)
                    }
                    while (!stopped.get()) {
                        if (!isPlaying) {
                            Thread.sleep(50)
                            continue
                        }
                        val start = System.currentTimeMillis()
                        if (!readFrame()) {
                            EventQueue.invokeLater {
                                isPlaying = false
                                hasEnded = true
                            }
                            break // EOF
                        }
                        val durationSeconds = nextFrameDurationSeconds()
                        deliver(durationSeconds)
                        val remaining = (durationSeconds * 1000).toLong() - (System.currentTimeMillis() - start)
                        if (remaining > 0) Thread.sleep(remaining)
                    }
                } catch (e: InterruptedException) {
                    // Expected on dispose (readerThread.interrupt() below).
                }
            }.apply { isDaemon = true }.also { it.start() }
        } else {
            null
        }

        onDispose {
            stopped.set(true)
            readerThread?.interrupt()
            stderrThread?.interrupt()
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
                Text("File: ${file.name}", color = Color.DarkGray, fontSize = 11.sp)
            }
        }

        val rotationSuffix = if (info.rotation != 0) " · 회전 (${info.rotation}°)" else ""
        PreviewCaption(
            "${info.width}x${info.height}$rotationSuffix",
            modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
        )

        if (info.duration > 0) {
            val elapsedSeconds = (startFromSeconds + playedSeconds).coerceIn(0.0, info.duration)
            LaunchedEffect(elapsedSeconds) { onElapsedChanged(elapsedSeconds) }
            PreviewCaption(
                "${formatMmSs(elapsedSeconds)} / ${formatMmSs(info.duration)}",
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
            )
            val progress = (elapsedSeconds / info.duration).toFloat().coerceIn(0f, 1f)
            Box(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.15f)),
            ) {
                Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(progress).background(AppColors.NeonGreen))
            }
        }

        if (!isPlaying) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable {
                        if (hasEnded) {
                            hasEnded = false
                            startFromSeconds = 0.0
                            restartTrigger++
                        }
                        isPlaying = true
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = Color.White, modifier = Modifier.size(48.dp))
            }
        } else {
            Box(modifier = Modifier.fillMaxSize().clickable { isPlaying = false })
        }
    }
}
