# Video Player Audio Playback — Design

**Date:** 2026-09-07
**Status:** Approved (pending user spec review)

## Problem

`FfmpegVideoPlayer` plays video only. The ffmpeg pipe explicitly disables audio
(`-an` in `ffmpegPipeArgs`), and there is no `SourceDataLine` anywhere in the
player. A user opened a video expecting sound and reported hearing nothing
("소리가 안 들리는 것 같아서요"). We want the video player to play the file's
audio track, in sync with the picture.

## Goals

- Play `0:a:0` of the file while the video plays, staying lip-synced.
- Audio is the master clock: the video reader paces itself to the audio's
  actually-rendered position, not to a wall clock or per-frame sleep budget.
- Pause / resume / seek / replay all keep audio and video together.
- A mute toggle in the player controls.
- Files with no audio track behave exactly as they do today (video-frame clock,
  no regression).
- If the audio ffmpeg process fails to start, or audio is shorter than video,
  playback continues on the current video-frame clock for the affected span.

## Non-goals

- Volume slider (mute only for now; slider is a possible follow-up).
- Audio waveform / spectrogram in the video player (that lives in the dedicated
  audio player).
- Changing `FfmpegAudioPlayer` behavior. Shared logic is duplicated, not
  extracted, for this change (~40 lines) — see "Code reuse".

## Architecture

### New file: `VideoAudioTrack.kt`

A sidecar audio engine owned by `FfmpegVideoPlayer`. One `ffmpeg` process
piping PCM + one `SourceDataLine` writer thread. Same pattern as
`FfmpegAudioPlayer`'s `DisposableEffect` reader loop, lifted into a plain class
so the video composable can hold a reference and query its clock.

```kotlin
internal class VideoAudioTrack(
    private val file: File,
    private val startFromSeconds: Double,
    private val sampleRate: Int,
    private val channels: Int,
    private val playing: AtomicBoolean,   // shared with the video player; polled each loop
    private val muted: AtomicBoolean,     // shared with the video player; applied on change
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

    /** Audio actually rendered by the mixer, in seconds, relative to this pipe's -ss seek point.
     *  Frozen while the line is stopped (pause). 0.0 before the line opens. */
    val clockSeconds: Double
        get() = (line?.microsecondPosition ?: 0L) / 1_000_000.0

    fun start()     // spawn ffmpeg + writer thread (idempotent-safe: no-op if already started/stopped)
    fun destroy()   // stopped=true; thread.interrupt(); ProcessManager.terminate(process)
}
```

**ffmpeg args** (identical shape to `FfmpegAudioPlayer`, no raw-PCM branch —
the video player never has `rawAudioParams`):

```
ffmpeg [-ss <startFromSeconds>] -i <file> -map 0:a:0
       -f s16le -ar <sampleRate> -ac <channels> -acodec pcm_s16le -
```

**Writer thread** (daemon, name `"video-audio"`):

```
line = AudioSystem.getSourceDataLine(AudioFormat(sampleRate, 16, channels, true, false))
line.open(format); line.start()
var wasPlaying = true
var appliedMute = false
loop while !stopped:
    if muted.get() != appliedMute { applyMute(line, muted.get()); appliedMute = muted.get() }
    if !playing.get():
        if wasPlaying { line.stop(); wasPlaying = false }
        Thread.sleep(30); continue
    if !wasPlaying { line.start(); wasPlaying = true }
    n = input.read(buffer)          // buffer = ByteArray(8192)
    if n < 0 { ended = true; break }
    line.write(buffer, 0, n)
catch InterruptedException -> {}          // expected on destroy()
catch Exception -> { failed = true; System.err.println(...) }
finally { line?.stop(); line?.flush(); line?.close() }
```

`start()` sets `failed = true` and returns if `processFactory` throws.

**`applyMute(line, muted)`** — try `BooleanControl.Type.MUTE`; if unsupported,
fall back to `FloatControl.Type.MASTER_GAIN` set to its `minimum` (muted) or
`0f` (unmuted). Whole thing wrapped in `runCatching` — a line that supports
neither just stays audible, no crash.

### Changes to `FfmpegVideoPlayer.kt`

**New state (all `remember(file)`):**

```kotlin
val audioPlayingAtomic = remember(file) { AtomicBoolean(false) }
val audioMutedAtomic   = remember(file) { AtomicBoolean(false) }
var isMuted            by remember(file) { mutableStateOf(false) }
var audioInfo         by remember(file) { mutableStateOf<AudioFileInfo?>(null) }
```

**Probe** — in the existing `LaunchedEffect(file)`, after `probedInfo`/
`frameTimestamps` resolve, add:

```kotlin
audioInfo = withContext(Dispatchers.IO) { probeAudioFormat(file) }
```

`probeAudioFormat` already returns `null` when there is no audio stream or
ffprobe fails — that null is the "no audio, use video clock" signal.

**`isPlaying` fan-out** — every site that assigns `isPlaying = X` (video-area
click, spacebar, bottom play/pause button, seek-to-pause, EOF handler) also
calls `audioPlayingAtomic.set(X)`. There are 6 such sites; a local helper keeps
it one line each:

```kotlin
fun setPlaying(play: Boolean) { isPlaying = play; audioPlayingAtomic.set(play) }
```

(EOF handler runs on the EventQueue thread — it does `isPlaying = false` inside
`EventQueue.invokeLater`; `setPlaying(false)` there is fine.)

**`isMuted` toggle** sets `audioMutedAtomic.set(isMuted)`.

**`DisposableEffect(file, restartTrigger)`** — after the video process is set
up, before/alongside the reader thread:

```kotlin
val audioTrack = audioInfo?.let { ai ->
    VideoAudioTrack(file, seekSeconds, ai.sampleRate, ai.channels,
                    audioPlayingAtomic, audioMutedAtomic).also { it.start() }
}
```

`audioPlayingAtomic` is set to the current `isPlaying` at the top of the
`DisposableEffect` (mirrors `FfmpegAudioPlayer`'s
`isPlayingAtomic.set(isPlaying)`), so a restart/seek that happened while playing
resumes audio.

`onDispose` also calls `audioTrack?.destroy()`.

**Video reader loop rewrite** (the `while (!stopped.get())` block):

```kotlin
var frameElapsed = 0.0   // sum of frame durations processed so far = this pipe's target position
// first frame: readFrame() -> nextFrameDurationSeconds() -> deliver(null)   (unchanged)
while (!stopped.get()) {
    if (!isPlaying) { Thread.sleep(50); continue }
    val start = System.currentTimeMillis()
    if (!readFrame()) { EventQueue.invokeLater { setPlaying(false); hasEnded = true }; break }
    val durationSeconds = nextFrameDurationSeconds()
    val budgetMillis = (durationSeconds * 1000).toLong()
    val frameStart = frameElapsed
    frameElapsed += durationSeconds

    val audioClock = audioTrack?.takeIf { !it.failed && !it.ended }?.clockSeconds
    if (audioClock == null) {
        // no audio / audio failed / audio ended before video -> current video-frame clock
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
        // audio-mode pacing == frameSyncAction(frameStart, audioClock) applied:
        when (val action = frameSyncAction(frameStart, audioClock)) {
            is FrameAction.WaitThenDeliver -> { Thread.sleep(action.millis); deliver(null) }
            FrameAction.Drop -> { /* >100ms behind audio -> skip render, bytes already read */ }
            FrameAction.Deliver -> deliver(null)
        }
        // deliver(null): progress in audio mode comes from the audio clock, not from here
    }
}
```

`frameSyncAction` (pure, unit-tested):

```kotlin
sealed interface FrameAction {
    data object Deliver : FrameAction
    data object Drop : FrameAction
    data class WaitThenDeliver(val millis: Long) : FrameAction
}

fun frameSyncAction(frameStartSeconds: Double, audioClockSeconds: Double): FrameAction {
    val leadSeconds = frameStartSeconds - audioClockSeconds
    return when {
        leadSeconds > 0.0    -> FrameAction.WaitThenDeliver((leadSeconds * 1000).toLong().coerceAtMost(500))
        leadSeconds < -0.10  -> FrameAction.Drop
        else                 -> FrameAction.Deliver
    }
}
```

Notes:
- In audio mode the reader thread never touches `playedSeconds`. Progress comes
  from a separate coroutine (below).
- `cumulativeLagMillis` / `shouldSkipFrame` / `laggedAfter*` are used only in
  the no-audio branch — unchanged behavior for audio-less files.
- The first-frame-while-paused `deliver(null)` path is unchanged.
- `deliver(null)` already means "show the frame, don't advance `playedSeconds`".

**Progress / elapsed in audio mode** — a coroutine that mirrors the audio clock
into `playedSeconds`:

```kotlin
// hoisted holder so both DisposableEffect and this coroutine see the same track
var audioTrackRef by remember(file) { mutableStateOf<VideoAudioTrack?>(null) }
// ... DisposableEffect sets audioTrackRef = audioTrack on create, = null in onDispose ...

LaunchedEffect(restartTrigger, audioInfo) {
    if (audioInfo == null) return@LaunchedEffect
    while (true) {
        audioTrackRef?.let { playedSeconds = it.clockSeconds }
        delay(50)
    }
}
```
`elapsedSeconds = (startFromSeconds + playedSeconds).coerceIn(...)` is unchanged
— both `frameElapsed` and `clockSeconds` are pipe-relative from the same `-ss`,
so `startFromSeconds + <audio clock>` is absolute video time, exactly as today.

**Seek** — `restartTrigger++` already re-keys `DisposableEffect`, which now also
recreates `VideoAudioTrack` with the new `seekSeconds`. Both ffmpeg processes
get the same `-ss`. No extra seek wiring.

### Controls: mute toggle

In the bottom controls `Row`, after the play/pause button (only when
`audioInfo != null`):

```kotlin
if (audioInfo != null) {
    Spacer(Modifier.width(6.dp))
    Box(
        modifier = Modifier.size(24.dp).clip(CircleShape)
            .background(Color.White.copy(alpha = 0.25f))
            .clickable { isMuted = !isMuted; audioMutedAtomic.set(isMuted) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (isMuted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
            contentDescription = if (isMuted) "Unmute" else "Mute",
            tint = Color.White, modifier = Modifier.size(14.dp),
        )
    }
}
```

(If `Icons.Filled.VolumeUp/VolumeOff` are not on the classpath — the project
uses `material-icons-core`, not `-extended` — fall back to a tiny Canvas
speaker glyph or the text "🔊"/"🔇". Implementer picks whichever is already
available; the audio player uses a hand-drawn `AudioPauseIcon`, so a
hand-drawn speaker is consistent.)

## Code reuse

`VideoAudioTrack`'s writer loop overlaps `FfmpegAudioPlayer`'s by ~40 lines.
We deliberately do NOT extract a shared helper now:

- `FfmpegAudioPlayer` has the raw-PCM (`rawAudioParams`) branch, temp-file
  cleanup, and its own `setPlaying`/`hasEnded` state machine — the loop is
  entangled with composable state there.
- `VideoAudioTrack` is a plain class driven by two `AtomicBoolean`s and exposes
  a clock the audio player has no need for.

Forcing a shared abstraction across those two shapes costs more than the
duplication. If a third consumer appears, revisit.

## Error handling

| Situation | Behavior |
|---|---|
| No `0:a:0` stream | `probeAudioFormat` → null → no `VideoAudioTrack`, video-frame clock (today's behavior) |
| Audio ffmpeg fails to start | `VideoAudioTrack.failed = true` → reader loop uses video-frame clock for the whole playthrough |
| `getSourceDataLine` / `line.open` throws | caught → `failed = true` → same as above |
| Audio track shorter than video | `ended = true` at audio EOF → reader loop switches to video-frame clock for the remaining frames |
| Line supports neither MUTE nor MASTER_GAIN | `applyMute` `runCatching` swallows it; audio stays audible; toggle is visually a no-op |
| Video EOF before audio EOF | reader loop `break`s on `!readFrame()` as today; `onDispose` → `audioTrack.destroy()` stops audio |
| Tab closed mid-play | `onDispose`: `stopped=true`, `readerThread.interrupt()`, `ProcessManager.terminate(videoProcess)`, `audioTrack.destroy()` (interrupt + `ProcessManager.terminate(audioProcess)`) |

## Testing

**Pure / unit:**

- `frameSyncAction(frameStartSeconds, audioClockSeconds): FrameAction` extracted
  from the audio-mode branch, where
  `FrameAction = Deliver | Drop | WaitThenDeliver(millis)`:
  - `frameStart` ahead of audio by 40ms → `WaitThenDeliver(40)`
  - lead capped: ahead by 900ms → `WaitThenDeliver(500)`
  - within tolerance (lead −50ms) → `Deliver`
  - behind by 150ms → `Drop`
  - boundary: lead exactly −100ms → `Deliver` (not `Drop`)
- `VideoAudioTrack` lifecycle with an injected `processFactory`:
  - factory throws → `failed == true`, `start()` does not propagate
  - `destroy()` before `start()` → no crash
  - `destroy()` twice → no crash
  - after `start()` with a fake process whose stdin yields EOF immediately →
    `ended` becomes true, thread terminates, `ProcessManager` count returns to
    baseline (same assertion style as `ProcessManagerTest`)
- `applyMute` selects MASTER_GAIN when a fake line reports MUTE unsupported.

**Manual (macOS, owed on Windows):**

- Play an MP4 with stereo audio: hear sound, lip-sync looks right, pause/resume
  keeps sync, drag-seek lands both tracks together, replay after EOF works.
- Toggle mute mid-play: audio silences, video keeps playing, clock keeps moving,
  unmute restores.
- Play a silent / audio-less video: no errors, plays at correct speed.
- Play a file where audio is ~1s shorter than video: last second plays on video
  clock, no stall, no crash at audio EOF.

## Files

- **Create:** `app/src/main/kotlin/com/multiviewer/ui/VideoAudioTrack.kt`
- **Create:** `app/src/test/kotlin/com/multiviewer/ui/VideoAudioTrackTest.kt`
- **Modify:** `app/src/main/kotlin/com/multiviewer/ui/FfmpegVideoPlayer.kt`
  (state, probe, reader-loop rewrite, `setPlaying` helper, mute button,
  `frameSyncAction` extraction)
- **Modify:** `app/src/test/kotlin/com/multiviewer/ui/FfmpegVideoPlayerTest.kt`
  if it exists; otherwise the `frameSyncAction` tests go in
  `VideoAudioTrackTest.kt` or a new `VideoPlayerSyncTest.kt`.
- `ffmpegPipeArgs` keeps `-an` — video pipe stays video-only.

## Global constraints

- Kotlin 2.0.21. Do not touch `app/build.gradle.kts` toolchain / compiler-arg
  forces.
- Commit messages end with:
  `Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>`
  `Claude-Session: https://claude.ai/code/session_013FCbWJi4EtnSbNjb27Rvyo`
- `configureEnvironment` on every `ProcessBuilder` that runs ffmpeg/ffprobe.
- Every spawned process registers with `ProcessManager` and is terminated on
  dispose.
