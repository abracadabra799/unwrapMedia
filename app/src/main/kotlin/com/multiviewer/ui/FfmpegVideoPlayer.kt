package com.multiviewer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

fun formatMmSsMs(seconds: Double): String {
    val totalMillis = (seconds * 1000).toLong()
    val totalSeconds = totalMillis / 1000
    return "%d:%02d.%03d".format(totalSeconds / 60, totalSeconds % 60, totalMillis % 1000)
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
        ).redirectErrorStream(false).redirectError(ProcessBuilder.Redirect.DISCARD)
            .also { FfmpegLocator.configureEnvironment(it) }.start()
        val lines = readProcessOutputWithTimeout(process, 10) { process.inputStream.bufferedReader().readLines() }
            ?: return null
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
// Anchored on the "rawvideo (BGRA" marker (unique to our requested OUTPUT pixel format -- no
// INPUT stream banner, whatever the source codec, is ever described that way), then the first
// 2+-digit WIDTHxHEIGHT pair after it. The 2+ digit requirement is what skips the FourCC hex
// literal earlier on the same line ("0x41524742" itself matches a naive \d+x\d+ as "0"x"41524742",
// since every digit of this particular literal happens to be 0-9) without depending on a trailing
// "[SAR...]" bracket -- real device footage (verified against an iPhone-shot MOV with a non-90/180/
// 270-degree rotation transform) can print this line with a bare ", q=2-31, ..." tail instead of
// a bracket, which silently failed to match at all and fell back to probeVideo()'s predicted
// dimensions -- wrong for this file, misaligning every raw frame boundary into a scrambled image.
private val FFMPEG_OUTPUT_DIMENSIONS_REGEX = Regex("""rawvideo \(BGRA.*?\b(\d{2,5})x(\d{2,5})\b""")

// Reads ffmpeg's stderr banner looking for the actual output rawvideo dimensions, then keeps
// draining stderr for the rest of the process's life so the pipe never fills and blocks ffmpeg.
// This exists because probeVideo()'s width/height swap for rotated video (see its own comment)
// is a prediction of what ffmpeg will do, not a guarantee -- if that prediction is ever wrong
// (an unparsed rotation value, a future ffmpeg version handling auto-rotation differently, some
// other dimension-affecting behavior this app doesn't know to predict), every frame boundary in
// the raw pipe misaligns and the picture comes out completely scrambled, not just mis-rotated.
// Reading ffmpeg's own self-reported dimensions removes the need to predict its behavior at all.
fun parseFfmpegOutputDimensionsLine(line: String): Pair<Int, Int>? =
    FFMPEG_OUTPUT_DIMENSIONS_REGEX.find(line)?.let { match ->
        match.groupValues[1].toInt() to match.groupValues[2].toInt()
    }

fun watchForActualDimensions(process: Process, fallback: Pair<Int, Int>): java.util.concurrent.CompletableFuture<Pair<Int, Int>> {
    val result = java.util.concurrent.CompletableFuture<Pair<Int, Int>>()
    Thread {
        try {
            process.errorStream.bufferedReader().forEachLine { line ->
                if (!result.isDone) {
                    parseFfmpegOutputDimensionsLine(line)?.let { result.complete(it) }
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

// Pure pacing decision, kept free of I/O/Thread/Compose so it's directly unit-testable.
// cumulativeLagMillis tracks how far real per-frame processing (read + Skia bitmap construction)
// has fallen behind the video's own real-time clock. It only grows via laggedAfterFrame (never
// goes negative) so a fast frame can't "bank" spare time against a later slow one -- the reader
// thread already does that implicitly by sleeping out any positive remainder, which this model
// doesn't need to duplicate. Root cause this fixes: at high enough resolution, per-frame
// processing alone can exceed its own frame budget (measured: ~1.2x budget at 4K30) -- the reader
// loop used to unconditionally process every frame regardless of how far behind it already was,
// so that overrun compounded across the whole video with nothing to pay it back down, making
// total playback wall time grow well past the real video duration.
fun shouldSkipFrame(cumulativeLagMillis: Long, budgetMillis: Long): Boolean =
    cumulativeLagMillis >= budgetMillis

// Skipping a frame's expensive bitmap construction/delivery pays down exactly one frame's budget
// worth of debt -- the frame's bytes are still read off the pipe to stay aligned, but that read
// alone is cheap enough (measured well under budget at every resolution tested) to not need its
// own accounting here.
fun laggedAfterSkip(cumulativeLagMillis: Long, budgetMillis: Long): Long =
    cumulativeLagMillis - budgetMillis

fun laggedAfterFrame(cumulativeLagMillis: Long, budgetMillis: Long, elapsedMillis: Long): Long =
    cumulativeLagMillis + (elapsedMillis - budgetMillis).coerceAtLeast(0L)

fun probeVideo(file: File): VideoInfo? {
    return try {
        val process = ProcessBuilder(
            FfmpegLocator.ffprobePath(), "-v", "error", "-select_streams", "v:0",
            "-show_entries", "stream=width,height,avg_frame_rate,r_frame_rate,duration:stream_side_data=rotation:format=duration",
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
        ).redirectErrorStream(false).redirectError(ProcessBuilder.Redirect.DISCARD)
            .also { FfmpegLocator.configureEnvironment(it) }.start()
        val lines = readProcessOutputWithTimeout(process, 5) { process.inputStream.bufferedReader().readLines() }
            ?: return null

        val values = mutableMapOf<String, String>()
        val validDurations = mutableListOf<Double>()
        for (line in lines) {
            val eq = line.indexOf('=')
            if (eq < 0) continue
            val key = line.substring(0, eq)
            val value = line.substring(eq + 1)
            if (key == "duration") {
                val d = value.toDoubleOrNull()
                if (d != null && d > 0.0) {
                    validDurations.add(d)
                }
            } else {
                values[key] = value
            }
        }

        var width = values["width"]?.toIntOrNull() ?: return null
        var height = values["height"]?.toIntOrNull() ?: return null
        val fps = values["avg_frame_rate"]?.let(::parseFrameRate)
            ?: values["r_frame_rate"]?.let(::parseFrameRate)
            ?: 30.0
        val duration = validDurations.firstOrNull() ?: 0.0
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

// Extracted so the exact ffmpeg args (in particular -fps_mode, below) are unit-testable without
// shelling out to a real process.
fun ffmpegPipeArgs(ffmpegPath: String, filePath: String, seekArgs: List<String>): List<String> =
    listOf(ffmpegPath) + seekArgs + listOf(
        "-i", filePath,
        // Explicit stream selection, matching probeVideo()'s own "-select_streams
        // v:0" -- some device footage (Samsung Motion Photo, iPhone) muxes several
        // video tracks into one file (a main track plus small preview/depth tracks),
        // and without -map ffmpeg's own default "best stream" heuristic decides which
        // one to pipe. It happened to agree with v:0 in local testing, but nothing
        // guarantees that in general -- an implicit mismatch here would silently pipe
        // a different (e.g. tiny grayscale preview) track than the one probeVideo()
        // measured.
        "-map", "0:v:0",
        // Some device footage declares a container frame-rate far above its real content rate
        // (verified against a real Samsung Motion Photo clip: r_frame_rate 120/1 vs
        // avg_frame_rate ~30.3 -- the same mismatch already noted below for probeVideo()'s fps
        // parsing). Without this flag, ffmpeg's default frame-rate-conversion behavior silently
        // duplicates each real decoded frame (~4x here) to fill that declared rate, so the piped
        // frame count no longer matches probeFrameTimestamps()'s real per-frame count (measured:
        // 372 piped vs 94 real). The reader loop then keeps reading/pacing through the extra
        // duplicate frames (using the fallback duration once the real per-frame durations list is
        // exhausted) well after playedSeconds has already reached info.duration -- the video
        // visibly keeps playing for several extra seconds after its own progress bar reads 100%.
        // "passthrough" (ffmpeg's current name for legacy -vsync 0) outputs decoded frames as-is,
        // matching probeFrameTimestamps()'s real frame count exactly (verified: 94 == 94).
        "-fps_mode", "passthrough",
        // Caps the piped raw frame at 1280px on its longer side (never upscales --
        // force_original_aspect_ratio=decrease only shrinks). Source device footage
        // routinely exceeds 1920x1440, meaning >10MB of uncompressed BGRA per frame
        // over a plain OS pipe -- reported (and, per this file's other comments,
        // previously measured) to be dramatically slower on Windows than macOS/Linux
        // for the same raw-pipe volume. The player only ever displays this bitmap
        // scaled to fit its own panel, which is never near source resolution, so
        // there is no visible quality cost. actualWidth/actualHeight below is read
        // from ffmpeg's own stderr banner (watchForActualDimensions), so it already
        // reflects this filter's real output size with no separate prediction needed.
        "-vf", "scale='min(1280,iw)':'min(1280,ih)':force_original_aspect_ratio=decrease:flags=fast_bilinear",
        "-f", "rawvideo", "-pix_fmt", "bgra", "-an", "-",
    )

@Composable
fun FfmpegVideoPlayer(
    file: File,
    modifier: Modifier = Modifier,
    onElapsedChanged: (Double) -> Unit = {},
    seekRequestSeconds: Double = 0.0,
    seekRequestTick: Int = 0,
    onProbeComplete: () -> Unit = {},
    onStepFrame: ((delta: Int) -> Unit)? = null,
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

    // probeVideo/probeFrameTimestamps both shell out to ffprobe and block on process.waitFor() --
    // this used to run them via remember{}, which executes its initializer INLINE during
    // composition, freezing the entire UI thread (including the loading spinner's own animation,
    // since Compose's frame clock is blocked along with everything else) for however long ffprobe
    // took. probeFrameTimestamps in particular walks every frame in the file and can take real
    // time on a longer video -- reported as "video opens with a delay and no visible progress
    // indicator" (the indicator was there, just frozen mid-render along with the rest of the UI).
    var probedInfo by remember(file) { mutableStateOf<VideoInfo?>(null) }
    // Whole-file probe, done once and reused across replays/seeks of the same file (see below for
    // how a seek slices into this list rather than re-probing).
    var frameTimestamps by remember(file) { mutableStateOf<List<Double>?>(null) }
    var probing by remember(file) { mutableStateOf(true) }

    LaunchedEffect(file) {
        probing = true
        val info = withContext(Dispatchers.IO) { probeVideo(file) }
        // Flip probing off (and let the player UI render) as soon as this cheap probe resolves --
        // do not wait on the expensive full-file frame-timestamp scan below. The player already
        // has a correct average-fps pacing fallback (nextFrameDurationSeconds's
        // fallbackDurationSeconds) for whenever frameTimestamps is still null, the same fallback
        // already used today if probeFrameTimestamps fails outright. Continuing to await it here,
        // in the same coroutine, still updates frameTimestamps once it completes -- the next
        // replay or seek (both already restart DisposableEffect) picks up the more precise
        // per-frame durations automatically; an in-flight playthrough does not hot-swap mid-play.
        probedInfo = info
        probing = false
        if (info != null) {
            val timestamps = withContext(Dispatchers.IO) { probeFrameTimestamps(file) }
            frameTimestamps = timestamps
            if ((probedInfo?.duration ?: 0.0) <= 0.0 && !timestamps.isNullOrEmpty()) {
                val lastPts = timestamps.last()
                val inferredDuration = if (timestamps.size > 1) {
                    lastPts + (lastPts - timestamps[timestamps.size - 2]).coerceAtLeast(0.001)
                } else {
                    lastPts + (1.0 / info.fps)
                }
                probedInfo = info.copy(duration = inferredDuration)
            }
        }
        onProbeComplete()
    }

    if (probing) {
        Box(modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            DecodingIndicator("동영상 정보 분석 중...")
        }
        return
    }

    val info = probedInfo
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
                ffmpegPipeArgs(FfmpegLocator.ffmpegPath(), file.absolutePath, seekArgs),
            ).also { FfmpegLocator.configureEnvironment(it) }.start().also {
                com.multiviewer.util.ProcessManager.register(it)
            }
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
                    // See shouldSkipFrame/laggedAfterFrame/laggedAfterSkip's docs -- tracks how far
                    // real processing time has fallen behind the video's own clock so a frame can
                    // be skipped (bytes still read to stay pipe-aligned, but no expensive bitmap
                    // construction/delivery) once that debt reaches a full frame's budget, instead
                    // of letting every frame's overrun compound for the rest of the video.
                    var cumulativeLagMillis = 0L
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
                        val budgetMillis = (durationSeconds * 1000).toLong()
                        if (shouldSkipFrame(cumulativeLagMillis, budgetMillis)) {
                            cumulativeLagMillis = laggedAfterSkip(cumulativeLagMillis, budgetMillis)
                            EventQueue.invokeLater { playedSeconds += durationSeconds }
                        } else {
                            deliver(durationSeconds)
                            val elapsedMillis = System.currentTimeMillis() - start
                            cumulativeLagMillis = laggedAfterFrame(cumulativeLagMillis, budgetMillis, elapsedMillis)
                            val remaining = budgetMillis - elapsedMillis
                            if (remaining > 0) Thread.sleep(remaining)
                        }
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
            com.multiviewer.util.ProcessManager.terminate(process)
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
            if (LocalShowPixelGrid.current) {
                PixelGridOverlay(nativeSize = Size(currentFrame.width.toFloat(), currentFrame.height.toFloat()), scale = 1f)
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                DecodingIndicator("동영상 디코딩 중...")
                Text("File: ${file.name}", color = Color.DarkGray, fontSize = 11.sp)
            }
        }

        val rotationSuffix = if (info.rotation != 0) " · 회전 (${info.rotation}°)" else ""
        val elapsedSeconds = (startFromSeconds + playedSeconds).coerceIn(0.0, maxOf(info.duration, 0.001))
        if (info.duration > 0) {
            LaunchedEffect(elapsedSeconds) { onElapsedChanged(elapsedSeconds) }
        }

        val playerFocusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            try {
                playerFocusRequester.requestFocus()
            } catch (_: Exception) {}
        }

        fun stepSingleFrame(delta: Int) {
            if (onStepFrame != null) {
                isPlaying = false
                onStepFrame(delta)
            } else {
                isPlaying = false
                hasEnded = false
                val fps = if (info.fps > 0) info.fps else 30.0
                val timestamps = frameTimestamps
                val targetPts = if (!timestamps.isNullOrEmpty()) {
                    val currentPts = (startFromSeconds + playedSeconds).coerceIn(0.0, maxOf(info.duration, 0.001))
                    // Proximity search or binary/last-le search
                    val currentIdx = if (delta > 0) {
                        timestamps.indexOfLast { it <= currentPts + 0.0001 }.coerceAtLeast(0)
                    } else {
                        val idx = timestamps.indexOfFirst { it >= currentPts - 0.0001 }
                        if (idx < 0) timestamps.size - 1 else idx
                    }
                    val nextIdx = (currentIdx + delta).coerceIn(0, timestamps.size - 1)
                    timestamps[nextIdx]
                } else {
                    val frameDelta = 1.0 / fps
                    ((startFromSeconds + playedSeconds) + delta * frameDelta).coerceIn(0.0, maxOf(info.duration, 0.0))
                }
                startFromSeconds = targetPts
                playedSeconds = 0.0
                onElapsedChanged(targetPts)
                restartTrigger++
            }
        }

        // Clickable video area for play/pause toggle without obscuring the frame, with keyboard focus for frame stepping
        Box(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(playerFocusRequester)
                .focusable()
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft -> {
                            stepSingleFrame(-1)
                            true
                        }
                        Key.DirectionRight -> {
                            stepSingleFrame(1)
                            true
                        }
                        Key.Spacebar -> {
                            if (!isPlaying) {
                                if (hasEnded) {
                                    hasEnded = false
                                    startFromSeconds = 0.0
                                    restartTrigger++
                                }
                                isPlaying = true
                            } else {
                                isPlaying = false
                            }
                            true
                        }
                        else -> false
                    }
                }
                .clickable {
                    playerFocusRequester.requestFocus()
                    if (!isPlaying) {
                        if (hasEnded) {
                            hasEnded = false
                            startFromSeconds = 0.0
                            restartTrigger++
                        }
                        isPlaying = true
                    } else {
                        isPlaying = false
                    }
                },
        )

        // Bottom Controls Bar (Play/Pause button on left, Info captions, and Progress bar)
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.5f)),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.25f))
                        .clickable {
                            if (isPlaying) {
                                isPlaying = false
                            } else {
                                if (hasEnded) {
                                    hasEnded = false
                                    startFromSeconds = 0.0
                                    restartTrigger++
                                }
                                isPlaying = true
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (isPlaying) {
                        VideoPauseIcon(modifier = Modifier.size(12.dp), color = Color.White)
                    } else {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = "Play",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }

                Spacer(Modifier.width(6.dp))

                PreviewCaption("${info.width}x${info.height}$rotationSuffix")

                Spacer(Modifier.weight(1f))

                if (info.duration > 0) {
                    PreviewCaption("${formatMmSsMs(elapsedSeconds)} / ${formatMmSsMs(info.duration)}")
                } else {
                    PreviewCaption(formatMmSsMs(elapsedSeconds))
                }
            }

            if (info.duration > 0) {
                val progress = (elapsedSeconds / info.duration).toFloat().coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .background(Color.White.copy(alpha = 0.2f))
                        .pointerInput(info.duration) {
                            fun seekToFraction(fraction: Float) {
                                hasEnded = false
                                isPlaying = false
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
        }
    }
}

@Composable
private fun VideoPauseIcon(modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier = modifier) {
        val barWidth = size.width * 0.3f
        val barHeight = size.height * 0.85f
        val top = (size.height - barHeight) / 2f
        val gap = size.width * 0.3f
        val left1 = (size.width - (2 * barWidth + gap)) / 2f
        val left2 = left1 + barWidth + gap
        drawRect(color, Offset(left1, top), Size(barWidth, barHeight))
        drawRect(color, Offset(left2, top), Size(barWidth, barHeight))
    }
}
