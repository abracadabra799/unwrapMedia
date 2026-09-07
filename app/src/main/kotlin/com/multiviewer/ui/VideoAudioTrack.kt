package com.multiviewer.ui

import com.multiviewer.util.ProcessManager
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.BooleanControl
import javax.sound.sampled.FloatControl
import javax.sound.sampled.SourceDataLine

// Pure pacing decision for the video reader loop when audio is the master clock. Kept free of
// I/O / Thread / Compose so it is directly unit-testable, same convention as shouldSkipFrame in
// FfmpegVideoPlayer.kt. frameStartSeconds and audioClockSeconds are both measured relative to the
// same ffmpeg -ss seek point, so their difference is the video frame's lead (+) or lag (-) versus
// the audio actually rendered by the mixer.
sealed interface FrameAction {
    data object Deliver : FrameAction
    data object Drop : FrameAction
    data class WaitThenDeliver(val millis: Long) : FrameAction
}

fun frameSyncAction(frameStartSeconds: Double, audioClockSeconds: Double): FrameAction {
    val leadSeconds = frameStartSeconds - audioClockSeconds
    return when {
        // Frame is due later than the audio has reached -- hold it. Cap the hold so a bogus clock
        // reading (e.g. line not yet started, position still 0) can't freeze the reader for
        // seconds; 500ms is well past any real single-frame interval.
        leadSeconds > 0.0 -> FrameAction.WaitThenDeliver((leadSeconds * 1000).toLong().coerceAtMost(500))
        // More than 100ms behind audio -- rendering this frame would only widen the gap; skip its
        // (expensive) bitmap construction. Its bytes were already read off the pipe by the caller.
        leadSeconds < -0.10 -> FrameAction.Drop
        else -> FrameAction.Deliver
    }
}

// Pure control-selection decision, split out so it is unit-testable without a fake SourceDataLine
// (which would need ~20 method stubs). Prefer the dedicated MUTE control; fall back to pinning
// MASTER_GAIN to its minimum (and back to 0 dB on unmute); a line exposing neither is left alone.
internal sealed interface MuteControlAction {
    data class SetMuteControl(val muted: Boolean) : MuteControlAction
    data class SetGainDb(val toMinimum: Boolean) : MuteControlAction
    data object NoOp : MuteControlAction
}

internal fun muteControlPlan(muteSupported: Boolean, gainSupported: Boolean, muted: Boolean): MuteControlAction =
    when {
        muteSupported -> MuteControlAction.SetMuteControl(muted)
        gainSupported -> MuteControlAction.SetGainDb(toMinimum = muted)
        else -> MuteControlAction.NoOp
    }

// Applies a mute state to an already-open line. runCatching guards a driver that reports a control
// as supported but throws on access.
internal fun applyMuteControl(line: SourceDataLine, muted: Boolean) {
    runCatching {
        val plan = muteControlPlan(
            muteSupported = line.isControlSupported(BooleanControl.Type.MUTE),
            gainSupported = line.isControlSupported(FloatControl.Type.MASTER_GAIN),
            muted = muted,
        )
        when (plan) {
            is MuteControlAction.SetMuteControl ->
                (line.getControl(BooleanControl.Type.MUTE) as BooleanControl).value = plan.muted
            is MuteControlAction.SetGainDb -> {
                val gain = line.getControl(FloatControl.Type.MASTER_GAIN) as FloatControl
                gain.value = if (plan.toMinimum) gain.minimum else 0f
            }
            MuteControlAction.NoOp -> {}
        }
    }
}

// A sidecar audio engine for FfmpegVideoPlayer: one ffmpeg process piping s16le PCM, one
// SourceDataLine writer thread. The video reader loop paces itself against clockSeconds. Same
// reader-loop shape as FfmpegAudioPlayer's DisposableEffect (deliberately duplicated -- see the
// design doc's "Code reuse" section), lifted into a plain class so the composable can hold a
// reference and read the clock.
internal class VideoAudioTrack(
    private val file: File,
    private val startFromSeconds: Double,
    private val sampleRate: Int,
    private val channels: Int,
    private val playing: AtomicBoolean,
    private val muted: AtomicBoolean,
    private val processFactory: (List<String>) -> Process = { args ->
        ProcessBuilder(args)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .also { FfmpegLocator.configureEnvironment(it) }
            .start()
    },
) {
    @Volatile var failed: Boolean = false
        private set
    @Volatile var ended: Boolean = false
        private set

    @Volatile private var process: Process? = null
    @Volatile private var line: SourceDataLine? = null
    private val stopped = AtomicBoolean(false)
    @Volatile private var thread: Thread? = null

    // Java Sound's default mixers on macOS/Windows routinely refuse a >2-channel SourceDataLine, so
    // a 5.1/7.1 source would throw at getSourceDataLine and fall back to a silent movie. Downmix to
    // stereo in ffmpeg and open the line with the same count.
    private val outChannels: Int = channels.coerceIn(1, 2)

    // Audio actually rendered by the mixer, in seconds, relative to this pipe's -ss seek point.
    // Frozen while the line is stopped (pause). 0.0 before the line opens. Only meaningful while
    // !failed && !ended -- after the line closes this keeps returning its last stale position.
    val clockSeconds: Double
        get() = (line?.microsecondPosition ?: 0L) / 1_000_000.0

    fun start() {
        if (stopped.get() || thread != null) return
        val seekArgs = if (startFromSeconds > 0.0) listOf("-ss", startFromSeconds.toString()) else emptyList()
        val args = listOf(FfmpegLocator.ffmpegPath()) + seekArgs + listOf(
            "-i", file.absolutePath, "-map", "0:a:0",
            "-f", "s16le", "-ar", sampleRate.toString(), "-ac", outChannels.toString(),
            "-acodec", "pcm_s16le", "-",
        )
        val p = try {
            processFactory(args).also { ProcessManager.register(it) }
        } catch (e: Exception) {
            failed = true
            return
        }
        process = p
        thread = Thread {
            var ln: SourceDataLine? = null
            try {
                val format = AudioFormat(sampleRate.toFloat(), 16, outChannels, true, false)
                ln = AudioSystem.getSourceDataLine(format)
                ln.open(format)
                ln.start()
                line = ln
                var wasPlaying = true
                var appliedMute = false
                val buffer = ByteArray(8192)
                val input = p.inputStream
                while (!stopped.get()) {
                    if (muted.get() != appliedMute) {
                        applyMuteControl(ln, muted.get())
                        appliedMute = muted.get()
                    }
                    if (!playing.get()) {
                        if (wasPlaying) { ln.stop(); wasPlaying = false }
                        Thread.sleep(30)
                        continue
                    }
                    if (!wasPlaying) { ln.start(); wasPlaying = true }
                    val n = input.read(buffer)
                    if (n < 0) { ended = true; break }
                    ln.write(buffer, 0, n)
                }
            } catch (e: InterruptedException) {
                // Expected on destroy() -- not an error.
            } catch (e: Exception) {
                failed = true
                System.err.println("VideoAudioTrack thread failed: $e")
            } finally {
                // On a clean pipe EOF (ended, and not a destroy()), let the line's internal buffer
                // finish rendering before stopping -- stop()+flush() alone discard the last
                // ~200-400ms still queued in the mixer buffer, audible as the audio cutting out
                // slightly before the end. Poll (rather than drain()) so a destroy() landing here
                // still tears down promptly via the stopped check; 3s hard cap as a safety net.
                val drainLine = ln
                if (ended && drainLine != null && !stopped.get()) {
                    runCatching {
                        val deadline = System.currentTimeMillis() + 3000
                        while (!stopped.get() && System.currentTimeMillis() < deadline &&
                            drainLine.bufferSize - drainLine.available() > 0
                        ) {
                            Thread.sleep(20)
                        }
                    }
                }
                ln?.stop()
                ln?.flush()
                ln?.close()
                // Proactively kill the ffmpeg pipe on failure/EOF rather than leaving it blocked on
                // a full stdout pipe until the owning composable disposes. Idempotent; the destroy()
                // path also calls terminate().
                ProcessManager.terminate(process)
            }
        }.apply { isDaemon = true; name = "video-audio" }.also { it.start() }
    }

    fun destroy() {
        stopped.set(true)
        thread?.interrupt()
        ProcessManager.terminate(process)
    }
}
