package com.multiviewer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
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
import androidx.compose.ui.input.pointer.pointerInput
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

// Per-frame presentation timestamps for the whole file, used to pace playback by each frame's
// REAL duration instead of a flat average (see the DisposableEffect in FfmpegVideoPlayer). An
// earlier version got this live, from ffmpeg's "-vf showinfo" filter writing to stderr while raw
// frames streamed from stdout on the same process -- correct and fast in local testing, but that
// makes per-frame pacing depend on how two separate OS pipes interleave under the platform's
// scheduler, which isn't something this codebase can verify cross-platform (this file already
// notes Windows' higher per-syscall read overhead as a real, measured difference from macOS/
// Linux). Probing timestamps upfront removes that dependency: durations become a plain list
// indexed by frame number, no live cross-pipe synchronization involved during playback at all.
fun probeFrameTimestamps(file: File): List<Double>? {
    return try {
        val process = ProcessBuilder(
            FfmpegLocator.ffprobePath(), "-v", "error", "-select_streams", "v:0",
            "-show_entries", "frame=pts_time", "-of", "default=noprint_wrappers=1", file.absolutePath,
        ).redirectErrorStream(false).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val lines = process.inputStream.bufferedReader().readLines()
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        val timestamps = lines.mapNotNull { line ->
            if (line.startsWith("pts_time=")) line.removePrefix("pts_time=").toDoubleOrNull() else null
        }
        timestamps.ifEmpty { null }
    } catch (e: Exception) {
        null
    }
}

// durationSeconds(i) = timestamps[i+1] - timestamps[i]; the last frame has no "next" timestamp to
// derive a real duration from, so it falls back to the file's average frame duration.
fun frameDurationsSeconds(timestamps: List<Double>, fallbackSeconds: Double): List<Double> =
    timestamps.mapIndexed { i, pts ->
        val next = timestamps.getOrNull(i + 1)
        if (next != null) (next - pts).coerceAtLeast(0.001) else fallbackSeconds
    }

// ffmpeg prints its own resolved output stream dimensions to stderr at startup, e.g.:
// "Stream #0:0(und): Video: rawvideo (BGRA / 0x41524742), bgra(...), 480x640 [SAR 1:1 DAR 3:4], ..."
// -- matched as "<space>WIDTHxHEIGHT<space>[" specifically to avoid the FourCC hex literal
// earlier on the same line (e.g. "0x41524742", which also matches a naive \d+x\d+ pattern).
private val FFMPEG_OUTPUT_DIMENSIONS_REGEX = Regex("""\s(\d+)x(\d+)\s\[""")

// Reads ffmpeg's stderr banner looking for the actual output rawvideo dimensions, then keeps
// draining stderr for the rest of the process's life so the pipe never fills and blocks ffmpeg.
// This exists because probeVideo()'s width/height swap for rotated video (see its own comment)
// is a prediction of what ffmpeg will do, not a guarantee -- if that prediction is ever wrong
// (an unparsed rotation value, a future ffmpeg version handling auto-rotation differently, some
// other dimension-affecting behavior this app doesn't know to predict), every frame boundary in
// the raw pipe misaligns and the picture comes out completely scrambled, not just mis-rotated.
// Reading ffmpeg's own self-reported dimensions removes the need to predict its behavior at all.
fun watchForActualDimensions(process: Process, fallback: Pair<Int, Int>): java.util.concurrent.CompletableFuture<Pair<Int, Int>> {
    val result = java.util.concurrent.CompletableFuture<Pair<Int, Int>>()
    Thread {
        try {
            process.errorStream.bufferedReader().forEachLine { line ->
                if (!result.isDone) {
                    FFMPEG_OUTPUT_DIMENSIONS_REGEX.find(line)?.let { match ->
                        result.complete(match.groupValues[1].toInt() to match.groupValues[2].toInt())
                    }
                }
            }
        } catch (e: Exception) {
            // Pipe closed from destroyForcibly() on dispose -- expected, not an error.
        } finally {
            result.complete(fallback) // no-op if already completed with a real match
        }
    }.apply { isDaemon = true }.start()
    return result
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
    // Whole-file probe, done once and reused across replays/seeks of the same file (see below for
    // how a seek slices into this list rather than re-probing).
    val frameTimestamps = remember(file) { probeFrameTimestamps(file) }

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
        // See watchForActualDimensions' comment -- confirms against ffmpeg's own stderr banner
        // rather than trusting probeVideo()'s width/height prediction for the piped frame size.
        val (actualWidth, actualHeight) = if (process != null) {
            try {
                watchForActualDimensions(process, info.width to info.height).get(3, TimeUnit.SECONDS)
            } catch (e: Exception) {
                info.width to info.height
            }
        } else {
            info.width to info.height
        }

        val stopped = AtomicBoolean(false)
        val fallbackDurationSeconds = 1.0 / info.fps
        // -ss before -i resets the piped stream's own frame index to 0, so the duration list for
        // THIS pipe run needs to start from wherever the seek landed, not from the whole file's
        // frame 0 -- find the first probed timestamp at or after the seek target and slice from
        // there. Empty (not null) durations list if the probe failed/came up empty is deliberate:
        // getOrElse below then falls back to fallbackDurationSeconds for every frame, same
        // behavior as before per-frame timestamps existed at all.
        val durations: List<Double> = frameTimestamps?.let { timestamps ->
            val startIndex = timestamps.indexOfFirst { it >= seekSeconds }.let { if (it < 0) 0 else it }
            frameDurationsSeconds(timestamps, fallbackDurationSeconds).drop(startIndex)
        } ?: emptyList()

        val readerThread = if (process != null) {
            Thread {
                // onDispose (below) calls readerThread.interrupt() on teardown, and this loop
                // spends most of its time in Thread.sleep() -- an interrupt landing there throws
                // InterruptedException with nothing to catch it otherwise, which used to escape
                // this thread uncaught every time a video tab with an active player was closed.
                // A force-killed process's pipe (destroyForcibly(), also called from onDispose)
                // can likewise surface as an IOException from input.read() rather than a clean
                // EOF (-1), depending on platform/timing -- caught here for the same reason.
                try {
                    val frameSize = actualWidth * actualHeight * 4
                    val buffer = ByteArray(frameSize)
                    val input = process.inputStream
                    var frameIndex = 0

                    fun readFrame(): Boolean {
                        var offset = 0
                        while (offset < frameSize) {
                            val read = input.read(buffer, offset, frameSize - offset)
                            if (read < 0) return false
                            offset += read
                        }
                        return true
                    }

                    fun nextFrameDurationSeconds(): Double =
                        durations.getOrElse(frameIndex) { fallbackDurationSeconds }.also { frameIndex++ }

                    fun deliver(durationSeconds: Double?) {
                        val bitmap = Bitmap().apply {
                            allocPixels(ImageInfo(ColorInfo(ColorType.BGRA_8888, ColorAlphaType.PREMUL, ColorSpace.sRGB), actualWidth, actualHeight))
                            installPixels(imageInfo, buffer, actualWidth * 4)
                        }
                        val snapshot = Image.makeFromBitmap(bitmap).toComposeImageBitmap()
                        EventQueue.invokeLater {
                            videoBitmap = snapshot
                            if (durationSeconds != null) {
                                playedSeconds += durationSeconds
                            }
                        }
                    }

                    // First frame, shown immediately while paused -- still advances frameIndex so
                    // the duration list stays aligned with frames read from here on, but it
                    // doesn't count toward playedSeconds since nothing played yet.
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
                    // Expected on dispose -- not an error.
                } catch (e: Exception) {
                    System.err.println("FfmpegVideoPlayer reader thread failed: $e")
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
                DecodingIndicator("동영상 디코딩 중...")
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
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(6.dp)
                    .background(Color.White.copy(alpha = 0.15f))
                    .pointerInput(info.duration) {
                        fun seekToFraction(fraction: Float) {
                            hasEnded = false
                            isPlaying = false // seek-and-pause, same as a GOP-frame-click seek
                            startFromSeconds = fraction.coerceIn(0f, 1f) * info.duration
                            restartTrigger++
                        }
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            seekToFraction(down.position.x / size.width.toFloat())
                            drag(down.id) { change ->
                                change.consume()
                                seekToFraction(change.position.x / size.width.toFloat())
                            }
                        }
                    },
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
