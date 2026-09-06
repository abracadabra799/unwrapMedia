package com.multiviewer.ui

import com.multiviewer.util.ProcessManager
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VideoAudioTrackTest {

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
        assertEquals(FrameAction.Deliver, frameSyncAction(frameStartSeconds = 0.9, audioClockSeconds = 1.0))
    }

    /** Fake process whose stdout is a fixed PCM byte blob then EOF. */
    private class FakePcmProcess(bytes: ByteArray) : Process() {
        private val stdout: InputStream = ByteArrayInputStream(bytes)
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
    fun `pipe EOF sets ended and unregisters the process`() {
        val before = ProcessManager.activeCount
        val track = VideoAudioTrack(
            java.io.File("x.mp4"), 0.0, 8000, 1, AtomicBoolean(true), AtomicBoolean(false),
            processFactory = { FakePcmProcess(ByteArray(16000)) }, // ~1s of 8kHz mono s16le
        )
        track.start()
        val deadline = System.currentTimeMillis() + 5000
        while (!track.ended && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(track.ended, "expected the track to reach EOF")
        track.destroy()
        Thread.sleep(200)
        assertEquals(before, ProcessManager.activeCount)
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
    fun `no audio device sets failed rather than crashing`() {
        // Can't force "no device" portably; assert instead that a line-open failure path exists by
        // constructing with an absurd format the mixer will reject.
        val track = VideoAudioTrack(
            java.io.File("x.mp4"), 0.0, sampleRate = 1, channels = 99, // invalid -> line open throws
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
