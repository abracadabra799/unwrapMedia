# Video Player Audio Playback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add in-sync audio playback to `FfmpegVideoPlayer`, with audio as the master clock and a mute toggle.

**Architecture:** A new plain class `VideoAudioTrack` owns a second ffmpeg process (PCM pipe) and a `SourceDataLine` writer thread, exposing `clockSeconds` (audio actually rendered). `FfmpegVideoPlayer`'s reader loop paces video frames against that clock; with no audio track (or after audio failure/EOF) it falls back to today's video-frame clock unchanged. Two shared `AtomicBoolean`s (`playing`, `muted`) drive the track. A pure `frameSyncAction` function holds the pacing decision.

**Tech Stack:** Kotlin 2.0.21, Compose for Desktop 1.7.3, `javax.sound.sampled`, ffmpeg/ffprobe subprocess, JUnit + `kotlin.test`.

## Global Constraints

- Kotlin stays 2.0.21. Do NOT touch `app/build.gradle.kts` toolchain or compiler-arg forces.
- No `material-icons-extended` on the classpath — only core icons (`PlayArrow`, `Info`, `Warning`, `CheckCircle`). The mute icon is hand-drawn Canvas, consistent with `VideoPauseIcon` / `AudioPauseIcon`.
- Every `ProcessBuilder` running ffmpeg/ffprobe calls `FfmpegLocator.configureEnvironment(it)`.
- Every spawned process: `com.multiviewer.util.ProcessManager.register(it)` on start, `ProcessManager.terminate(process)` on dispose.
- `ffmpegPipeArgs` keeps `-an` — the video pipe stays video-only.
- Commit messages end with exactly:
  ```
  Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_013FCbWJi4EtnSbNjb27Rvyo
  ```
- No behavior change for audio-less videos: `cumulativeLagMillis` / `shouldSkipFrame` / `laggedAfterFrame` / `laggedAfterSkip` remain the pacing path when there is no audio clock.

## File Structure

| File | Responsibility |
|---|---|
| `app/src/main/kotlin/com/multiviewer/ui/VideoAudioTrack.kt` | **Create.** Sidecar audio engine: ffmpeg PCM pipe + `SourceDataLine` writer thread; exposes `clockSeconds`, `failed`, `ended`; `start()` / `destroy()`. Also holds the pure `frameSyncAction` + `FrameAction` sealed interface and `applyMuteControl` helper. |
| `app/src/test/kotlin/com/multiviewer/ui/VideoAudioTrackTest.kt` | **Create.** Pure tests for `frameSyncAction`; lifecycle tests for `VideoAudioTrack` with an injected `processFactory`; `applyMuteControl` fallback test. |
| `app/src/main/kotlin/com/multiviewer/ui/FfmpegVideoPlayer.kt` | **Modify.** New state (atomics, `isMuted`, `audioInfo`, `audioTrackRef`); probe `audioInfo`; `setPlaying` helper replacing bare `isPlaying =` assignments; reader-loop rewrite (audio-clock branch via `frameSyncAction`); progress coroutine; mute button + `SpeakerIcon`. |

`FfmpegVideoPlayerTest.kt` already exists (26 tests) — new pure-function tests for anything added there may also live in `VideoAudioTrackTest.kt`; this plan puts `frameSyncAction` in `VideoAudioTrack.kt` so its tests go in `VideoAudioTrackTest.kt`.

---

## Task 1: `FrameAction` + `frameSyncAction` (pure pacing decision)

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/ui/VideoAudioTrack.kt`
- Test: `app/src/test/kotlin/com/multiviewer/ui/VideoAudioTrackTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  ```kotlin
  sealed interface FrameAction {
      data object Deliver : FrameAction
      data object Drop : FrameAction
      data class WaitThenDeliver(val millis: Long) : FrameAction
  }
  fun frameSyncAction(frameStartSeconds: Double, audioClockSeconds: Double): FrameAction
  ```

- [ ] **Step 1: Write the failing test**

Create `app/src/test/kotlin/com/multiviewer/ui/VideoAudioTrackTest.kt`:

```kotlin
package com.multiviewer.ui

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:test --tests 'com.multiviewer.ui.VideoAudioTrackTest'`
Expected: FAIL — `VideoAudioTrack.kt` / `frameSyncAction` unresolved.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/kotlin/com/multiviewer/ui/VideoAudioTrack.kt`:

```kotlin
package com.multiviewer.ui

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
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:test --tests 'com.multiviewer.ui.VideoAudioTrackTest'`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/VideoAudioTrack.kt app/src/test/kotlin/com/multiviewer/ui/VideoAudioTrackTest.kt
git commit -m "feat: add frameSyncAction pacing decision for video-audio sync

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_013FCbWJi4EtnSbNjb27Rvyo"
```

---

## Task 2: `VideoAudioTrack` class (ffmpeg PCM pipe + SourceDataLine writer)

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/VideoAudioTrack.kt`
- Test: `app/src/test/kotlin/com/multiviewer/ui/VideoAudioTrackTest.kt`

**Interfaces:**
- Consumes: `FfmpegLocator.ffmpegPath()` / `configureEnvironment` (existing), `com.multiviewer.util.ProcessManager.register` / `.terminate` / `.activeCount` (existing).
- Produces:
  ```kotlin
  internal class VideoAudioTrack(
      file: java.io.File,
      startFromSeconds: Double,
      sampleRate: Int,
      channels: Int,
      playing: java.util.concurrent.atomic.AtomicBoolean,
      muted: java.util.concurrent.atomic.AtomicBoolean,
      processFactory: (List<String>) -> Process = { /* real ProcessBuilder */ },
  ) {
      val failed: Boolean          // volatile; true if ffmpeg or the audio line could not start
      val ended: Boolean           // volatile; true once the PCM pipe hit EOF
      val clockSeconds: Double     // (line?.microsecondPosition ?: 0) / 1e6, pipe-relative
      fun start()
      fun destroy()
  }
  internal sealed interface MuteControlAction {
      data class SetMuteControl(val muted: Boolean) : MuteControlAction
      data class SetGainDb(val toMinimum: Boolean) : MuteControlAction
      data object NoOp : MuteControlAction
  }
  internal fun muteControlPlan(muteSupported: Boolean, gainSupported: Boolean, muted: Boolean): MuteControlAction
  internal fun applyMuteControl(line: javax.sound.sampled.SourceDataLine, muted: Boolean)
  ```

- [ ] **Step 1: Write the failing tests**

Append to `VideoAudioTrackTest.kt` (add imports: `java.io.ByteArrayInputStream`, `java.io.InputStream`, `java.io.OutputStream`, `java.util.concurrent.atomic.AtomicBoolean`, `kotlin.test.assertFalse`, `kotlin.test.assertTrue`, `com.multiviewer.util.ProcessManager`):

```kotlin
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
```

> Note: the EOF test opens a real `SourceDataLine`. If the CI/build host has no audio device, `AudioSystem.getSourceDataLine` throws — `VideoAudioTrack` must catch that and set `failed = true` (not crash the thread). Add this test too:

```kotlin
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
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:test --tests 'com.multiviewer.ui.VideoAudioTrackTest'`
Expected: FAIL — `VideoAudioTrack` type unresolved.

- [ ] **Step 3: Write minimal implementation**

Append to `VideoAudioTrack.kt` (add imports):

```kotlin
import com.multiviewer.util.ProcessManager
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.BooleanControl
import javax.sound.sampled.FloatControl
import javax.sound.sampled.SourceDataLine

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
    private var thread: Thread? = null

    // Audio actually rendered by the mixer, in seconds, relative to this pipe's -ss seek point.
    // Frozen while the line is stopped (pause). 0.0 before the line opens.
    val clockSeconds: Double
        get() = (line?.microsecondPosition ?: 0L) / 1_000_000.0

    fun start() {
        if (stopped.get() || thread != null) return
        val seekArgs = if (startFromSeconds > 0.0) listOf("-ss", startFromSeconds.toString()) else emptyList()
        val args = listOf(FfmpegLocator.ffmpegPath()) + seekArgs + listOf(
            "-i", file.absolutePath, "-map", "0:a:0",
            "-f", "s16le", "-ar", sampleRate.toString(), "-ac", channels.toString(),
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
                val format = AudioFormat(sampleRate.toFloat(), 16, channels, true, false)
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
                ln?.stop()
                ln?.flush()
                ln?.close()
            }
        }.apply { isDaemon = true; name = "video-audio" }.also { it.start() }
    }

    fun destroy() {
        stopped.set(true)
        thread?.interrupt()
        ProcessManager.terminate(process)
    }
}
```

> `ProcessManager.terminate(null)` is already a safe no-op (see `ProcessManager.kt:41`), so `destroy()` before `start()` is fine.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:test --tests 'com.multiviewer.ui.VideoAudioTrackTest'`
Expected: PASS (all — 5 from Task 1 + 3 `muteControlPlan` + 5 lifecycle). If the build host genuinely has no mixer at all, the `pipe EOF` test may report `failed` instead — in that case mark it `@org.junit.jupiter.api.Disabled` with a comment, keep the rest.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/VideoAudioTrack.kt app/src/test/kotlin/com/multiviewer/ui/VideoAudioTrackTest.kt
git commit -m "feat: VideoAudioTrack -- ffmpeg PCM pipe + SourceDataLine sidecar for the video player

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_013FCbWJi4EtnSbNjb27Rvyo"
```

---

## Task 3: Wire `VideoAudioTrack` into `FfmpegVideoPlayer` — probe, state, lifecycle, master clock

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/FfmpegVideoPlayer.kt`

**Interfaces:**
- Consumes: `VideoAudioTrack`, `frameSyncAction`, `FrameAction` (Tasks 1-2); `probeAudioFormat` / `AudioFileInfo` (existing, `FfmpegAudioPlayer.kt`).
- Produces: no new public API — `FfmpegVideoPlayer`'s signature is unchanged.

- [ ] **Step 1: Add imports and audio state**

In `FfmpegVideoPlayer.kt` add imports:
```kotlin
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
```
(the first two are already present — verify; `delay` is new.)

Inside `FfmpegVideoPlayer`, alongside the other `remember(file)` state (after `lastHandledSeekTick`):

```kotlin
val audioPlayingAtomic = remember(file) { AtomicBoolean(false) }
val audioMutedAtomic = remember(file) { AtomicBoolean(false) }
var isMuted by remember(file) { mutableStateOf(false) }
var audioInfo by remember(file) { mutableStateOf<AudioFileInfo?>(null) }
// Hoisted so both the DisposableEffect (which creates it) and the progress coroutine (which reads
// its clock) see the same instance.
var audioTrackRef by remember(file) { mutableStateOf<VideoAudioTrack?>(null) }
```

- [ ] **Step 2: Probe the audio format**

In `LaunchedEffect(file)`, after the `if (info != null) { ... }` block that resolves `frameTimestamps` and before `onProbeComplete()`:

```kotlin
audioInfo = if (info != null) withContext(Dispatchers.IO) { probeAudioFormat(file) } else null
```

- [ ] **Step 3: Add the `setPlaying` helper and route all `isPlaying =` assignments through it**

Immediately after the state declarations (before `LaunchedEffect(seekRequestTick)`), add:

```kotlin
fun setPlaying(play: Boolean) {
    isPlaying = play
    audioPlayingAtomic.set(play)
}
```

Then replace every bare assignment `isPlaying = true` / `isPlaying = false` in this composable with `setPlaying(true)` / `setPlaying(false)`. Sites (all within `FfmpegVideoPlayer`):
1. `LaunchedEffect(seekRequestTick)` → `isPlaying = false` becomes `setPlaying(false)`
2. reader thread EOF handler inside `EventQueue.invokeLater { isPlaying = false; hasEnded = true }` → `setPlaying(false)`
3. `stepSingleFrame` → `isPlaying = false` (both branches) → `setPlaying(false)`
4. `onKeyEvent` Spacebar branch → both `isPlaying = true` and `isPlaying = false` → `setPlaying(...)`
5. `.clickable { ... }` on the video area → both → `setPlaying(...)`
6. bottom play/pause `Box` `.clickable` → both → `setPlaying(...)`

Leave `isPlaying` *reads* untouched.

- [ ] **Step 4: Create/destroy the track in `DisposableEffect`, sync initial play state**

At the top of `DisposableEffect(file, restartTrigger)`, right after `playedSeconds = 0.0`:

```kotlin
audioPlayingAtomic.set(isPlaying)
audioMutedAtomic.set(isMuted)
```

After `val (actualWidth, actualHeight) = ...` and before `val stopped = AtomicBoolean(false)`:

```kotlin
val audioTrack = audioInfo?.let { ai ->
    VideoAudioTrack(
        file = file,
        startFromSeconds = seekSeconds,
        sampleRate = ai.sampleRate,
        channels = ai.channels,
        playing = audioPlayingAtomic,
        muted = audioMutedAtomic,
    ).also { it.start() }
}
audioTrackRef = audioTrack
```

In `onDispose`, after `com.multiviewer.util.ProcessManager.terminate(process)`:

```kotlin
audioTrack?.destroy()
audioTrackRef = null
```

- [ ] **Step 5: Rewrite the reader loop's per-frame body to follow the audio clock**

Replace the `while (!stopped.get()) { ... }` body (the block starting `if (!isPlaying) { Thread.sleep(50); continue }` through the end of the loop) with:

```kotlin
var frameElapsed = 0.0
while (!stopped.get()) {
    if (!isPlaying) {
        Thread.sleep(50)
        continue
    }
    val start = System.currentTimeMillis()
    if (!readFrame()) {
        EventQueue.invokeLater {
            setPlaying(false)
            hasEnded = true
        }
        break // EOF
    }
    val durationSeconds = nextFrameDurationSeconds()
    val budgetMillis = (durationSeconds * 1000).toLong()
    val frameStart = frameElapsed
    frameElapsed += durationSeconds

    val audioClock = audioTrack?.takeIf { !it.failed && !it.ended }?.clockSeconds
    if (audioClock == null) {
        // No audio track, audio failed to start, or audio ended before the video -- fall back to
        // the self-pacing video-frame clock (unchanged behavior for audio-less files).
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
    } else {
        // Audio is the master clock. deliver(null): in audio mode playedSeconds is mirrored from
        // the audio clock by a separate coroutine, not advanced here.
        when (val action = frameSyncAction(frameStart, audioClock)) {
            is FrameAction.WaitThenDeliver -> {
                if (action.millis > 0) Thread.sleep(action.millis)
                deliver(null)
            }
            FrameAction.Drop -> { /* >100ms behind audio: bytes already read, skip the render */ }
            FrameAction.Deliver -> deliver(null)
        }
    }
}
```

Keep the `var cumulativeLagMillis = 0L` declaration where it is (just before the loop). Keep the first-frame `if (readFrame()) { nextFrameDurationSeconds(); deliver(null) }` block unchanged.

- [ ] **Step 6: Add the progress coroutine (audio mode) and guard the video-mode accumulation**

The video-mode branch still advances `playedSeconds` (via `deliver(durationSeconds)` and the skip path). For audio mode, add after the `DisposableEffect` block (top-level in the composable, near the other `LaunchedEffect`s):

```kotlin
// In audio mode the reader thread does not touch playedSeconds -- mirror the audio clock here so
// the progress bar and elapsed caption track what the user actually hears.
LaunchedEffect(restartTrigger, audioInfo) {
    if (audioInfo == null) return@LaunchedEffect
    while (true) {
        audioTrackRef?.let { track ->
            if (!track.failed) playedSeconds = track.clockSeconds
        }
        delay(50)
    }
}
```

`elapsedSeconds = (startFromSeconds + playedSeconds).coerceIn(...)` is unchanged.

- [ ] **Step 7: Build and run the existing test suite**

Run: `./gradlew :app:test --tests 'com.multiviewer.ui.FfmpegVideoPlayerTest' --tests 'com.multiviewer.ui.VideoAudioTrackTest'`
Expected: PASS — all 26 existing video-player tests + the new track tests. No test asserts on the reader loop internals, so the rewrite must not break them.

Run: `./gradlew :app:compileKotlin` (or `:app:assemble`)
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/FfmpegVideoPlayer.kt
git commit -m "feat: play the video's audio track with audio as the master clock

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_013FCbWJi4EtnSbNjb27Rvyo"
```

---

## Task 4: Mute toggle button + `SpeakerIcon`

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/FfmpegVideoPlayer.kt`

**Interfaces:**
- Consumes: `isMuted` / `audioMutedAtomic` / `audioInfo` state (Task 3).
- Produces: `@Composable private fun SpeakerIcon(muted: Boolean, modifier: Modifier, color: Color)`.

- [ ] **Step 1: Add the hand-drawn speaker icon**

At the bottom of `FfmpegVideoPlayer.kt`, next to `VideoPauseIcon`:

```kotlin
@Composable
private fun SpeakerIcon(muted: Boolean, modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        // Speaker body: a small rectangle + triangular cone on the left third.
        val bodyLeft = w * 0.05f
        val bodyTop = h * 0.35f
        val bodyW = w * 0.22f
        val bodyH = h * 0.30f
        drawRect(color, topLeft = Offset(bodyLeft, bodyTop), size = Size(bodyW, bodyH))
        val cone = androidx.compose.ui.graphics.Path().apply {
            moveTo(bodyLeft + bodyW, bodyTop)
            lineTo(w * 0.5f, h * 0.15f)
            lineTo(w * 0.5f, h * 0.85f)
            lineTo(bodyLeft + bodyW, bodyTop + bodyH)
            close()
        }
        drawPath(cone, color)
        if (muted) {
            // Slash through the whole glyph.
            drawLine(color, Offset(w * 0.15f, h * 0.15f), Offset(w * 0.9f, h * 0.9f), strokeWidth = h * 0.09f)
        } else {
            // Two sound-wave arcs on the right.
            drawArc(color, startAngle = -50f, sweepAngle = 100f, useCenter = false,
                topLeft = Offset(w * 0.35f, h * 0.2f), size = Size(w * 0.4f, h * 0.6f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = h * 0.07f))
            drawArc(color, startAngle = -50f, sweepAngle = 100f, useCenter = false,
                topLeft = Offset(w * 0.2f, h * 0.05f), size = Size(w * 0.7f, h * 0.9f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = h * 0.07f))
        }
    }
}
```

Add imports if missing: `androidx.compose.ui.geometry.Offset` (present), `androidx.compose.ui.geometry.Size` (present).

- [ ] **Step 2: Add the mute button to the bottom controls Row**

In the bottom controls `Row`, immediately after the play/pause `Box` (the one with `VideoPauseIcon` / `PlayArrow`) and before `Spacer(Modifier.width(6.dp))` + `PreviewCaption(...)`:

```kotlin
if (audioInfo != null) {
    Spacer(Modifier.width(6.dp))
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.25f))
            .clickable {
                isMuted = !isMuted
                audioMutedAtomic.set(isMuted)
            },
        contentAlignment = Alignment.Center,
    ) {
        SpeakerIcon(muted = isMuted, modifier = Modifier.size(14.dp), color = Color.White)
    }
}
```

- [ ] **Step 3: Build and smoke-check compilation**

Run: `./gradlew :app:compileKotlin`
Expected: BUILD SUCCESSFUL.

Run: `./gradlew :app:test --tests 'com.multiviewer.ui.*'`
Expected: PASS (no new tests here — the icon is visual; verified manually).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/FfmpegVideoPlayer.kt
git commit -m "feat: mute toggle in the video player controls

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_013FCbWJi4EtnSbNjb27Rvyo"
```

---

## Task 5: Manual verification pass + notes

**Files:** none (verification only; may add a short note to `docs/`).

- [ ] **Step 1: Build the app**

Run: `./gradlew :app:run` (or the project's usual run task)
Expected: app launches.

- [ ] **Step 2: Walk the checklist** (macOS now; Windows still owed and must be recorded as owed)

- [ ] Open an MP4 with a stereo audio track in the video player → sound is audible, lip-sync looks correct.
- [ ] Pause mid-play → audio and video both stop; resume → both continue together, still synced.
- [ ] Drag the seek bar → after the jump, audio and video are both at the new position and synced.
- [ ] Let it play to the end, then press play again → restarts from 0 with audio.
- [ ] Toggle the mute button mid-play → audio silences, video keeps playing, elapsed keeps advancing; unmute → audio returns.
- [ ] Open a video with NO audio track → no mute button shown, plays at correct speed, no errors in the console.
- [ ] Open a file whose audio is ~1s shorter than its video (e.g. `ffmpeg -i in.mp4 -t <videoLen-1> -c copy` on the audio) → last second plays without stalling, no crash at audio EOF.
- [ ] Close the tab mid-play → no lingering `ffmpeg` processes (check Activity Monitor / `pgrep ffmpeg`).

- [ ] **Step 3: Record the outcome**

If all pass, note in the PR / commit body: "macOS manual pass complete; Windows manual pass owed (audio device + orphan-process check)."
If anything fails, STOP and use `superpowers:systematic-debugging`.

- [ ] **Step 4: Finish the branch**

Use `superpowers:finishing-a-development-branch`.

---

## Self-Review

**Spec coverage:**
- Audio playback in sync, audio as master clock → Tasks 2-3 ✓
- Pause/resume/seek/replay keep sync → Task 3 Step 3-4 (`setPlaying` fan-out, `DisposableEffect` re-key) ✓
- Mute toggle → Task 4 ✓
- No-audio files unchanged → Task 3 Step 5 (`audioClock == null` branch keeps `shouldSkipFrame` path) ✓
- Audio ffmpeg start failure / audio shorter than video → `takeIf { !it.failed && !it.ended }` → Task 3 Step 5 ✓
- `frameSyncAction` + `VideoAudioTrack` lifecycle tests → Tasks 1-2 ✓
- `-an` kept on video pipe → Global Constraints + no task touches `ffmpegPipeArgs` ✓
- `configureEnvironment` / `ProcessManager` on the audio process → Task 2 Step 3 (`processFactory` default + `ProcessManager.register`, `destroy()` → `terminate`) ✓
- Mute control MUTE→MASTER_GAIN fallback → Task 2 `muteControlPlan` (pure) + 3 tests ✓

**Gap found & fixed:** the design doc asks for a test that "`applyMute` selects MASTER_GAIN when a fake line reports MUTE unsupported." A fake `SourceDataLine` needs ~20 method stubs. Fixed by splitting the control-selection decision into a pure `muteControlPlan(muteSupported, gainSupported, muted)` returning a `MuteControlAction`, with 3 direct tests; `applyMuteControl` is the thin `isControlSupported` → `muteControlPlan` → `getControl` adapter, guarded by `runCatching`. Satisfies the spec's intent without the fake.

**Placeholder scan:** no TBD/TODO; all code blocks complete.

**Type consistency:** `frameSyncAction(frameStartSeconds, audioClockSeconds)` — same names Task 1 defines and Task 3 calls with `frameSyncAction(frameStart, audioClock)` (positional). `VideoAudioTrack` constructor param order identical in Tasks 2 and 3. `audioTrackRef` / `audioInfo` / `audioPlayingAtomic` / `audioMutedAtomic` / `isMuted` names consistent across Tasks 3-4.
