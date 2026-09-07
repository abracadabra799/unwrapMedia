# Scrolling Oscilloscope Waveform + Channel Solo — Design

**Date:** 2026-09-07
**Status:** Approved (pending user spec review)

## Problem

The audio player's waveform panel renders the whole file as a static image (a
4096-bucket peak array drawn once). The user wants it to behave like a DJ tool's
scrolling waveform: a zoomed-in view (~5 s wide) that scrolls beneath a fixed
centre playhead as playback advances. They also want to solo the left or right
channel for listening.

The static full-file view is being **replaced** by the scrolling detail view.
The existing minimap already provides the whole-file overview + click-to-seek.

## Goals

- Waveform panel = a scrolling window, default 5 s wide, playhead pinned at
  centre, scrolling smoothly (60 fps) while playing.
- Wheel-zoom still sets the window width; drag-scrub and minimap-click still seek.
- Waveform peaks resolved finely enough that a 5 s zoom shows real detail on any
  file length (today 4096 buckets/file makes a 5 s zoom blocky, unusable on long
  files).
- Solo Stereo / L / R for audio output. Hidden for mono files.
- Spectrogram scrolls in sync (it already shares the view window) — resolution
  limitation noted, not addressed here.

## Non-goals

- Per-window spectrogram re-rendering (it stays a cropped full-file image, blurry
  when zoomed — pre-existing; separate follow-up).
- Mid/Side solo (L/R only).
- Changing the waveform's L/R split rendering when a channel is soloed (solo
  affects audio output only).
- A follow/lock toggle button — follow is simply tied to `isPlaying`.
- "Playhead always dead-centre with blank margins" at the file's first/last half
  window — `clampWindow` pins the window to the ends there and the playhead
  drifts to the edge (standard DJ-tool behaviour).

## Approach

### 1. Peak resolution (the real cost item)

`computeWaveformPeaks` already decodes the whole PCM stream in one pass; bucketing
it more finely is O(1) per sample — **no extra I/O**. Only memory grows.

- New pure fn in `AudioWaveformPeaks.kt`:
  ```kotlin
  fun waveformBucketCountFor(durationSeconds: Double): Int =
      (durationSeconds * 300.0).toInt().coerceIn(4096, 1_800_000)
  ```
  300 buckets/s ≈ 3.3 ms. 1 h stereo → 1.08M buckets → min+max Float ×2ch ≈ 17 MB
  (one per open file, freed on tab close; same order as the spectrogram bitmap).
- `FfmpegAudioPlayer`'s `LaunchedEffect(file)`:
  `computeWaveformPeaks(file, info, bucketCount = waveformBucketCountFor(info.duration))`.
- `computeWaveformPeaks` internals unchanged (default param stays 4096 for other
  callers / tests).

**Renderer downsampling** — with up to 1.8M buckets, drawing one `drawLine` per
visible bucket is wasteful when zoomed out and janky at 1M+. New pure fn:

```kotlin
data class PeakColumn(val min: Float, val max: Float)

// Aggregate visibleRange into at most targetColumns (min,max) spans — one per
// screen x-pixel. When the range already has <= targetColumns buckets, returns
// one PeakColumn per bucket unchanged.
fun downsamplePeaks(peaks: ChannelPeaks, visibleRange: IntRange, targetColumns: Int): List<PeakColumn>
```

- `drawChannelPeaks` (`AudioWaveformPeaks.kt`) and `drawMinimapWaveform`
  (`AudioMinimap.kt`) call `downsamplePeaks` with `targetColumns =
  size.width.toInt()` (the DrawScope canvas width in px) and draw one line per
  returned column, evenly spaced across the canvas.
- 5 s zoom (buckets dense) → one bucket-or-fewer per pixel, full detail.
  Full zoom-out (1M buckets) → only ~width columns drawn.
- `visibleBucketRange` unchanged (bucket-index mapping).

### 2. Scrolling / follow

- `visibleWindow` init changes from `AudioViewWindow(0.0, info.duration)` to
  `AudioViewWindow(0.0, minOf(5.0, info.duration))`.
- **Follow rule: while `isPlaying`, the window keeps the playhead centred.**
  - Playing: window auto-follows. Scrollbar-drag / pan is overwritten next tick
    (pause to look elsewhere). Wheel-zoom still works (width changes, stays
    centred).
  - Paused: `visibleWindow` fully manual (zoom / pan / drag-scrub).
  - Pressing play re-attaches (playhead snaps to centre).
- **Smoothing** — `playedSeconds` updates ~20 Hz (per 8192-byte chunk); raw use
  would step-scroll. A `LaunchedEffect(isPlaying)` coroutine:
  ```kotlin
  while (isPlaying) {
      withFrameNanos { now ->
          val displayElapsed = lastSampleSeconds + (now - lastSampleWallNanos) / 1e9
          visibleWindow = clampWindow(
              followWindowStart(displayElapsed, visibleWindow.durationSeconds, info.duration),
              visibleWindow.durationSeconds, info.duration,
          )
      }
  }
  ```
  - `lastSampleSeconds` / `lastSampleWallNanos` recorded whenever a new
    `playedSeconds` sample lands (and reset on seek).
  - `followWindowStart(displayElapsed, windowDur, total)` — pure helper (=
    `displayElapsed - windowDur / 2`, extracted for testing).
  - Coroutine ends when `isPlaying` goes false → window frozen where it is.
- **Playhead marker** — the existing progress overlay already draws a white line
  at `(elapsedSeconds - visibleWindow.startSeconds) / durationSeconds`. It follows
  automatically once the window follows. Draw it from `displayElapsed` (the
  interpolated value) so it's smooth too. Near the file ends `clampWindow` pins
  the window and the marker slides toward the edge — accepted.

### 3. Channel solo

- `enum class ChannelMode { STEREO, LEFT, RIGHT }`;
  `var channelMode by remember(file) { mutableStateOf(ChannelMode.STEREO) }`.
- Hidden when `info.channels < 2`.
- Pure fn `channelModeFilterArgs(mode: ChannelMode): List<String>`:
  - `STEREO` → `emptyList()`
  - `LEFT` → `listOf("-af", "pan=stereo|c0=c0|c1=c0")`
  - `RIGHT` → `listOf("-af", "pan=stereo|c0=c1|c1=c1")`
  - Output stays 2ch → `SourceDataLine` format / `bytesPerSecond` unchanged.
- Added to the ffmpeg arg list in `DisposableEffect(file, restartTrigger)`.
- On `channelMode` change: same position-preserving restart as a seek —
  `startFromSeconds = elapsedSeconds; playedSeconds = 0.0; restartTrigger++`
  (`isPlayingAtomic` untouched, so playback state carries over).
- Works with `rawAudioParams` (pan applies to the decoded stream).
- UI: a small segmented toggle `[ Stereo | L | R ]` near the play button; active
  segment highlighted, others dimmed.

### 4. Spectrogram

- Scrolls in sync via the shared `visibleWindow` (crop moves, no re-render).
- Stays a cropped full-file `SPECTROGRAM_WIDTH_PX` image → blurry at 5 s zoom.
  Pre-existing (zooming in is already blurry today). Per-window re-render is a
  separate follow-up, out of scope here.

## Edge cases

| Case | Behaviour |
|---|---|
| File ≤ 5 s | Window = whole file (`clampWindow`), follow is a no-op, static display |
| Mono file | Channel toggle hidden, single waveform canvas (as today) |
| Waveform / minimap click seek | Existing seek; if playing, follow continues from the new position |
| Playback reaches EOF | Follow coroutine ends, window frozen at the end |
| Tab switch / file reload | All state `remember(file)` — window resets to 5 s, `ChannelMode.STEREO` |
| Solo active + seek | `channelMode` preserved (args rebuilt each restart read it) |

## Testing

- Pure: `waveformBucketCountFor` (boundaries, clamp), `downsamplePeaks` (empty
  range / buckets < columns / buckets ≫ columns / min & max preserved),
  `followWindowStart` (centre maths, near-zero, near-end),
  `channelModeFilterArgs` (three mappings).
- Manual: scroll smoothness while playing, wheel-zoom while playing, pause →
  manual pan, L/R solo actually changes what you hear, mono file hides the
  toggle, long-file 5 s zoom shows detail.

## Files

| File | Change |
|---|---|
| `app/src/main/kotlin/com/multiviewer/ui/AudioWaveformPeaks.kt` | `waveformBucketCountFor`, `PeakColumn` + `downsamplePeaks`, `drawChannelPeaks` uses it |
| `app/src/main/kotlin/com/multiviewer/ui/AudioMinimap.kt` | `drawMinimapWaveform` uses `downsamplePeaks` |
| `app/src/main/kotlin/com/multiviewer/ui/AudioZoomPan.kt` | `followWindowStart` helper |
| `app/src/main/kotlin/com/multiviewer/ui/FfmpegAudioPlayer.kt` | 5 s default window; follow coroutine + interpolation; `ChannelMode` + toggle UI + pan filter + `channelModeFilterArgs`; peak call passes `waveformBucketCountFor` |
| `app/src/test/kotlin/com/multiviewer/ui/AudioWaveformPeaksTest.kt` | `waveformBucketCountFor`, `downsamplePeaks` tests |
| `app/src/test/kotlin/com/multiviewer/ui/AudioZoomPanTest.kt` | `followWindowStart` tests |
| `app/src/test/kotlin/com/multiviewer/ui/FfmpegAudioPlayerTest.kt` | `channelModeFilterArgs` test |

## Global constraints

- Kotlin 2.0.21; do not touch `app/build.gradle.kts`.
- Every ffmpeg/ffprobe `ProcessBuilder` keeps `FfmpegLocator.configureEnvironment`;
  spawned processes register with `ProcessManager` and are terminated on dispose.
- Commit messages end with:
  `Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>`
  `Claude-Session: https://claude.ai/code/session_013FCbWJi4EtnSbNjb27Rvyo`

## Branch

Land on a fresh `feat/audio-scrolling-waveform` branched from `main` **after**
`feat/video-player-audio` is merged — not stacked on it (that branch still has
unverified video-audio work).
