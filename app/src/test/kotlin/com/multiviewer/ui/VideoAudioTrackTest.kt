package com.multiviewer.ui

import com.multiviewer.util.ProcessManager
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Control
import javax.sound.sampled.DataLine
import javax.sound.sampled.LineListener
import javax.sound.sampled.SourceDataLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VideoAudioTrackTest {
    @Test
    fun `software volume scales signed little endian PCM and mute silences it`() {
        val pcm = byteArrayOf(0x20, 0x4e, 0xe0.toByte(), 0xb1.toByte()) // +20000, -20000
        scalePcm16Le(pcm, pcm.size, 50)
        assertEquals(listOf(0x10, 0x27, 0xf0, 0xd8), pcm.map { it.toInt() and 0xff }) // +10000, -10000
        scalePcm16Le(pcm, pcm.size, 0)
        assertEquals(listOf(0, 0, 0, 0), pcm.map { it.toInt() and 0xff })
    }

    @Test
    fun `audio timeline keeps advancing after PCM input ends so late frames stay stale`() {
        val timeline = AudioMasterTimeline()
        assertEquals(2.0, timeline.position(2.0, audioEnded = false, playing = true, nowNanos = 1_000_000_000L))
        val afterEof = timeline.position(2.0, audioEnded = true, playing = true, nowNanos = 2_000_000_000L)
        assertEquals(3.0, afterEof)
        assertEquals(FrameAction.Drop, frameSyncAction(frameStartSeconds = 1.0, audioClockSeconds = afterEof!!))
    }

    @Test
    fun `audio timeline does not spend paused time after PCM input ends`() {
        val timeline = AudioMasterTimeline()
        timeline.position(2.0, audioEnded = false, playing = true, nowNanos = 1_000_000_000L)
        assertEquals(3.0, timeline.position(2.0, audioEnded = true, playing = false, nowNanos = 2_000_000_000L))
        assertEquals(3.0, timeline.position(2.0, audioEnded = true, playing = false, nowNanos = 3_000_000_000L))
        assertEquals(3.0, timeline.position(2.0, audioEnded = true, playing = true, nowNanos = 3_000_000_000L))
        assertEquals(3.5, timeline.position(2.0, audioEnded = true, playing = true, nowNanos = 3_500_000_000L))
    }

    @Test
    fun `audio timeline has no master clock without an audio track`() {
        assertEquals(null, AudioMasterTimeline().position(null, audioEnded = false, playing = true, nowNanos = 1L))
    }


    @Test
    fun `frameSyncAction waits when the frame is ahead of the audio clock`() {
        assertEquals(FrameAction.WaitThenDeliver(40), frameSyncAction(frameStartSeconds = 1.04, audioClockSeconds = 1.0))
    }

    @Test
    fun `frameSyncAction caps the wait at 500ms`() {
        assertEquals(FrameAction.WaitThenDeliver(500), frameSyncAction(frameStartSeconds = 1.9, audioClockSeconds = 1.0))
    }

    @Test
    fun `frameSyncAction delivers immediately when within tolerance behind`() {
        assertEquals(FrameAction.Deliver, frameSyncAction(frameStartSeconds = 0.95, audioClockSeconds = 1.0))
    }

    @Test
    fun `frameSyncAction drops when more than 100ms behind the audio clock`() {
        assertEquals(FrameAction.Drop, frameSyncAction(frameStartSeconds = 0.84, audioClockSeconds = 1.0))
    }

    @Test
    fun `frameSyncAction at exactly minus 100ms still delivers`() {
        // Pass the lead directly so leadSeconds is exactly -0.1 (0.9 - 1.0 is -0.09999999999999998
        // in IEEE754 and would not exercise the boundary).
        assertEquals(FrameAction.Deliver, frameSyncAction(frameStartSeconds = 0.0, audioClockSeconds = 0.10))
    }

    /** True when this host can open a plain stereo PCM line -- gates the tests that need a mixer. */
    /** A SourceDataLine that accepts every write immediately and has nothing left to drain. */
    private class InstantLine(private val fmt: AudioFormat) : SourceDataLine {
        @Volatile private var open = false
        @Volatile private var running = false
        private var written = 0L
        val output = ByteArrayOutputStream()
        override fun open(format: AudioFormat, bufferSize: Int) { open = true }
        override fun open(format: AudioFormat) { open = true }
        override fun open() { open = true }
        override fun write(b: ByteArray, off: Int, len: Int): Int {
            output.write(b, off, len)
            written += len
            return len
        }
        override fun drain() {}
        override fun flush() {}
        override fun start() { running = true }
        override fun stop() { running = false }
        override fun isRunning() = running
        override fun isActive() = running
        override fun getFormat() = fmt
        override fun getBufferSize() = 4096
        override fun available() = bufferSize
        override fun getFramePosition() = getLongFramePosition().toInt()
        override fun getLongFramePosition() = written / fmt.frameSize
        override fun getMicrosecondPosition() = if (open) getLongFramePosition() * 1_000_000L / fmt.sampleRate.toLong() else 0L
        override fun getLevel() = AudioSystem.NOT_SPECIFIED.toFloat()
        override fun getLineInfo() = DataLine.Info(SourceDataLine::class.java, fmt)
        override fun close() { open = false }
        override fun isOpen() = open
        override fun getControls() = emptyArray<Control>()
        override fun isControlSupported(control: Control.Type) = false
        override fun getControl(control: Control.Type): Control = throw IllegalArgumentException("unsupported: $control")
        override fun addLineListener(listener: LineListener) {}
        override fun removeLineListener(listener: LineListener) {}
    }

    /** Fake process whose stdout is a fixed PCM byte blob then EOF. */
    private class FakePcmProcess(bytes: ByteArray, chunkSize: Int = 8192) : Process() {
        private val stdout: InputStream = object : ByteArrayInputStream(bytes) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(len, chunkSize))
        }
        @Volatile private var alive = true
        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = InputStream.nullInputStream()
        override fun waitFor(): Int { alive = false; return 0 }
        override fun exitValue(): Int = if (alive) throw IllegalThreadStateException() else 0
        override fun destroy() { alive = false }
        override fun isAlive(): Boolean = alive
    }

    @Test
    fun `start marks failed when the process factory throws`() {
        val track = VideoAudioTrack(
            file = java.io.File("x.mp4"), startFromSeconds = 0.0, sampleRate = 48000, channels = 2,
            playing = AtomicBoolean(true), muted = AtomicBoolean(false),
            processFactory = { throw java.io.IOException("boom") },
        )
        track.start()
        assertTrue(track.failed)
    }

    @Test
    fun `destroy before start does not throw`() {
        VideoAudioTrack(
            java.io.File("x.mp4"), 0.0, 48000, 2, AtomicBoolean(false), AtomicBoolean(false),
            processFactory = { FakePcmProcess(ByteArray(0)) },
        ).destroy()
    }

    @Test
    fun `destroy twice does not throw`() {
        val track = VideoAudioTrack(
            java.io.File("x.mp4"), 0.0, 48000, 2, AtomicBoolean(false), AtomicBoolean(false),
            processFactory = { FakePcmProcess(ByteArray(0)) },
        )
        track.start()
        track.destroy()
        track.destroy()
    }

    @Test
    fun `pipe EOF sets ended and destroy unregisters the process`() {
        val process = FakePcmProcess(ByteArray(16000)) // ~1s of 8kHz mono s16le
        val track = VideoAudioTrack(
            java.io.File("x.mp4"), 0.0, 8000, 1, AtomicBoolean(true), AtomicBoolean(false),
            processFactory = { process },
            lineFactory = { InstantLine(it) },
        )
        track.start()
        assertTrue(ProcessManager.isTracked(process))
        val deadline = System.currentTimeMillis() + 5000
        while (!track.ended && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(track.ended, "expected the track to reach EOF")
        track.destroy()
        val gone = System.currentTimeMillis() + 2000
        while (ProcessManager.isTracked(process) && System.currentTimeMillis() < gone) Thread.sleep(20)
        assertFalse(ProcessManager.isTracked(process), "process must be unregistered after EOF + destroy")
    }

    @Test
    fun `audio track applies volume even when PCM arrives one byte at a time`() {
        val pcm = byteArrayOf(0x20, 0x4e, 0xe0.toByte(), 0xb1.toByte())
        val output = InstantLine(AudioFormat(8000f, 16, 1, true, false))
        val track = VideoAudioTrack(
            java.io.File("x.mp4"), 0.0, 8000, 1, AtomicBoolean(true), AtomicBoolean(false),
            volumePercent = AtomicInteger(50),
            processFactory = { FakePcmProcess(pcm, chunkSize = 1) },
            lineFactory = { output },
        )
        try {
            track.start()
            val deadline = System.currentTimeMillis() + 2000
            while (!track.ended && System.currentTimeMillis() < deadline) Thread.sleep(10)
            assertTrue(track.ended, "expected the track to reach EOF")
            assertEquals(listOf(0x10, 0x27, 0xf0, 0xd8), output.output.toByteArray().map { it.toInt() and 0xff })
        } finally {
            track.destroy()
        }
    }

    @Test
    fun `audio clock retains its rendered position after the output line closes`() {
        val process = FakePcmProcess(ByteArray(16000)) // 1s, 8kHz mono s16le
        val track = VideoAudioTrack(
            java.io.File("x.mp4"), 0.0, 8000, 1, AtomicBoolean(true), AtomicBoolean(false),
            processFactory = { process }, lineFactory = { InstantLine(it) },
        )
        track.start()
        val deadline = System.currentTimeMillis() + 2000
        while (ProcessManager.isTracked(process) && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertFalse(ProcessManager.isTracked(process), "expected the audio output line to close")
        assertEquals(1.0, track.clockSeconds)
        track.destroy()
    }

    @Test
    fun `multichannel source is downmixed to stereo in the ffmpeg args`() {
        var capturedArgs: List<String>? = null
        val track = VideoAudioTrack(
            java.io.File("x.mp4"), 0.0, sampleRate = 48000, channels = 6, // 5.1 source
            playing = AtomicBoolean(false), muted = AtomicBoolean(false),
            processFactory = { args -> capturedArgs = args; FakePcmProcess(ByteArray(0)) },
        )
        track.start()
        val args = capturedArgs ?: error("processFactory was not invoked")
        val acIndex = args.indexOf("-ac")
        assertTrue(acIndex >= 0 && acIndex + 1 < args.size, "expected -ac in the ffmpeg args")
        assertEquals("2", args[acIndex + 1])
        track.destroy()
    }

    @Test
    fun `muteControlPlan prefers the MUTE control when available`() {
        assertEquals(MuteControlAction.SetMuteControl(true), muteControlPlan(muteSupported = true, gainSupported = true, muted = true))
        assertEquals(MuteControlAction.SetMuteControl(false), muteControlPlan(muteSupported = true, gainSupported = false, muted = false))
    }

    @Test
    fun `muteControlPlan falls back to MASTER_GAIN when MUTE is unsupported`() {
        assertEquals(MuteControlAction.SetGainDb(toMinimum = true), muteControlPlan(muteSupported = false, gainSupported = true, muted = true))
        assertEquals(MuteControlAction.SetGainDb(toMinimum = false), muteControlPlan(muteSupported = false, gainSupported = true, muted = false))
    }

    @Test
    fun `muteControlPlan is a no-op when the line exposes neither control`() {
        assertEquals(MuteControlAction.NoOp, muteControlPlan(muteSupported = false, gainSupported = false, muted = true))
    }

    @Test
    fun `line-open failure sets failed rather than crashing the thread`() {
        // An absurd sample rate no mixer supports (and if there is no mixer at all, getSourceDataLine
        // throws regardless) -- either way the writer thread must catch it and set failed, not crash.
        // channels is downmixed to <=2 now, so the sample rate is the lever.
        val track = VideoAudioTrack(
            java.io.File("x.mp4"), 0.0, sampleRate = 100_000_000, channels = 2,
            playing = AtomicBoolean(true), muted = AtomicBoolean(false),
            processFactory = { FakePcmProcess(ByteArray(64)) },
        )
        track.start()
        val deadline = System.currentTimeMillis() + 3000
        while (!track.failed && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(track.failed)
        track.destroy()
    }
}
