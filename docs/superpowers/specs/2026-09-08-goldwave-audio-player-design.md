# GoldWave-style Waveform Audio Player — Design

**Date:** 2026-09-08
**Status:** Approved (pending user spec review)

## Goal

Replace the audio player's current dual-panel (waveform + spectrogram + minimap +
zoom-scrollbar + channel-solo) layout with a clean GoldWave/Audacity-style
waveform-only player: a full-file waveform, a playhead that tracks the *actually
rendered* playback position, page-scroll follow, click-to-seek, zoom, an explicit
transport bar (rewind / play-pause / stop), an in-player Open button, and a
`MM:SS.mmm` time readout.

Requirements are numbered per the user's brief (req 1–15).

## What is removed

| Item | Reason |
|---|---|
| `AudioSpectrogramDisplay.kt` (whole file) + `generateSpectrogramImage` / `renderAudioVisualization` (in `FfmpegAudioPlayer.kt`) + `AudioSpectrogramDisplayTest.kt` | spectrogram dropped |
| `AudioMinimap.kt` (whole file) | minimap dropped |
| `followWindow` (in `AudioZoomPan.kt`) + its `AudioZoomPanTest` cases | centre-pinned scroll replaced by page-scroll |
| `ChannelMode` / `channelModeFilterArgs` / the Stereo·L·R listening toggle + its `FfmpegAudioPlayerTest` case | channel-solo listening dropped (L/R **display** stays — req 10) |
| `AudioZoomScrollbar` (private in `FfmpegAudioPlayer.kt`) | replaced by the new bottom scrollbar |

## What is kept

- `computeWaveformPeaks` — background thread, duration-proportional buckets
  (`waveformBucketCountFor`), per-channel min/max, stores ≤ 2 channels. (req 11, 12)
- `downsamplePeaks` / `forEachPeakColumn` / `PeakColumn` — the alloc-free per-pixel
  min/max downsample used by the Canvas hot path. (req 11)
- `AudioViewWindow(startSeconds, durationSeconds)` + `clampWindow` +
  `MIN_VISIBLE_DURATION_SECONDS` (in `AudioZoomPan.kt`) — the view-window type.
- `visibleBucketRange` (in `AudioWaveformPeaks.kt`).
- `probeAudioFormat` / `AudioFileInfo` / raw-PCM (`rawAudioParams`) support.

## Architecture

Entry point unchanged: `AudioInspectorUI(appState, tab, …)` →
`FfmpegAudioPlayer(file, rawAudioParams, onOpenAudio, modifier)` inside
`DashboardLayout` with the existing right `DetailedPropertiesPanel`.

### New files

- **`AudioWaveformView.kt`** — the waveform Composable: L/R (or mono) lanes drawn
  from peaks via `forEachPeakColumn`, the playhead line, click-to-seek gesture,
  wheel-to-zoom gesture, thin time-axis tick labels. Also holds the pure helpers
  `formatMinSecMillis` and `niceTimeStep`. `drawChannelPeaks` /
  `WaveformChannelCanvas` move here from `AudioWaveformPeaks.kt`, rewritten for the
  view-window model.
- **`AudioTransportBar.kt`** — a stateless Composable taking only callbacks:
  `[ Open Audio ]`, `MM:SS.mmm / MM:SS.mmm` readout, `◀◀ ▶/❚❚ ■`, `Zoom [-] N% [+]`.

### Layout (`FfmpegAudioPlayer`, `Column`, top → bottom — mockup order)

1. **Header `Row`:** `[ Open Audio ]` (left, hidden if `onOpenAudio == null`) · flexible spacer · `formatMinSecMillis(cursor) + " / " + formatMinSecMillis(total)` (right, monospace).
2. **Waveform area** (`weight(1f)`, fills the rest): stereo → `Column { Lane(L, weight 1f); Lane(R, weight 1f) }`; mono → one `Lane`. X = time, Y = amplitude (lane centre = 0, up = +). Playhead = a vertical line across all lanes.
3. **Bottom scrollbar** (shown only when `view.durationSeconds < info.duration`): full-width strip, thumb width `view.duration/total`, position `view.start/total`, drag → `clampWindow(newStart, view.duration, total)` via `rememberUpdatedState`.
4. **Transport `Row`** (centre): `◀◀` · `▶`/`❚❚` · `■` — hand-drawn Canvas icons (same pattern as the existing `AudioPauseIcon`; `Icons.Filled.PlayArrow` is core-available).
5. **Zoom `Row`:** `Zoom:  [-]  <N>%  [+]` — `[-]` disabled when already at 100%, `[+]` disabled at the zoom-in cap.

## Playback clock (req 4, 15)

The current `playedSeconds += bytesRead / bytesPerSecond` accumulation is **removed**
— it counts bytes *written to* the line, drifts, and runs ahead of what is heard.

- The reader thread spawns `ffmpeg -ss <pipeStartSeconds> -i <file> -map 0:a:0 -f
  s16le -ar <sr> -ac <ch> -acodec pcm_s16le -` and opens a fresh `SourceDataLine`
  per pipe (a seek tears the whole pipe + line down via `restartTrigger`), so
  `line.microsecondPosition` always starts at 0 for the current pipe.
- **`cursorSeconds = pipeStartSeconds + line.microsecondPosition / 1_000_000.0`** —
  the mixer's own clock for audio *actually rendered*. Drift-free, freezes on
  `line.stop()` (pause), continues on `line.start()` (resume). It points at what is
  being heard, which lags the pipe's read position by the line buffer — correct for
  req 4.
- A `LaunchedEffect(isPlaying, restartTrigger)` runs `withFrameNanos` while playing,
  reading that expression each frame (30–60 fps) into the `cursorSeconds` Compose
  state. The clock advances continuously in real time, so no interpolation is
  needed. (req 14)
- **EOF:** after `input.read() < 0` the line still holds buffered audio. Keep the
  cursor advancing until the line buffer drains
  (`line.bufferSize - line.available() > 0`, hard cap ~2 s, skipped on dispose),
  then `setPlaying(false)` + `hasEnded = true`.
- While paused the follow coroutine is not running; `cursorSeconds` is whatever a
  seek or the last frame left it.
- Reader-thread loop shape (as today): while `!stopped` — if `!isPlayingAtomic` →
  `line.stop()` + park (`Thread.sleep(50)`); else `line.start()`, `read()` a
  buffer, `line.write()`. No `playedSeconds` bookkeeping any more.

## Page-scroll follow (req 5)

`view: AudioViewWindow` is the visible span. 100% ⇔ `view.durationSeconds == info.duration`.

Pure helper:
```kotlin
// Returns a new view when the cursor has left the visible span (needs paging), else null.
fun pageScrollView(cursorSeconds: Double, view: AudioViewWindow, totalDurationSeconds: Double): AudioViewWindow? {
    if (view.durationSeconds >= totalDurationSeconds) return null           // 100% -> playhead just crosses
    val rightEdge = view.startSeconds + view.durationSeconds * 0.9
    val pastRight = cursorSeconds >= rightEdge
    val pastLeft = cursorSeconds < view.startSeconds
    if (!pastRight && !pastLeft) return null
    return clampWindow(cursorSeconds - view.durationSeconds * 0.1, view.durationSeconds, totalDurationSeconds)
}
```

The follow coroutine calls it each frame after updating `cursorSeconds`; when it
returns non-null, `view = it`. Net effect: the playhead sweeps left→right; on
reaching 90% of the width the view jumps forward so the playhead reappears at ~10%
from the left (GoldWave). A backward seek that lands left of the view pages it back
the same way.

## Waveform view + zoom (req 2, 9, 10, 11)

### Lanes

Each lane is a `Canvas`:
```
val visibleRange = visibleBucketRange(view, totalDuration, peaks.bucketCount)
forEachPeakColumn(channel, visibleRange, size.width.toInt()) { idx, n, min, max ->
    val x = size.width * idx / n
    drawLine(color, Offset(x, centerY - max * centerY), Offset(x, centerY - min * centerY), strokeWidth)
}
```
Stereo stacks channel 0 (L) above channel 1 (R); mono draws one full-height lane.
A one-line lane label ("L" / "R") in the top-left corner of each stereo lane.

### Playhead

A vertical line spanning all lanes at
`x = (cursorSeconds - view.startSeconds) / view.durationSeconds * width`.
Hidden (not clamped) when `cursorSeconds` is outside `[view.start, view.end]`.

### Time axis

Thin tick labels along the top of the waveform area: step =
`niceTimeStep(view.durationSeconds)` (returns one of 0.1, 0.5, 1, 5, 10, 30, 60,
300 s — whichever gives ~4–10 ticks across the view), labels formatted
`M:SS` (or `M:SS.m` when step < 1 s).

### Gestures

- **Click** (`awaitEachGesture`, down only — no drag-scrub): `onSeekTo(view.startSeconds + (x / width) * view.durationSeconds)`.
- **Wheel** (`onPointerEvent(Scroll)`): `onZoom(scrollDelta.y, anchorSeconds = view.startSeconds + (mouseX / width) * view.durationSeconds)`.

### Zoom (req 9)

- **100% = whole file** (`view.durationSeconds == info.duration`); this is the
  zoom-**out floor** — you cannot show more than the whole file.
- **Zoom-in cap:** `view.durationSeconds >= maxOf(0.05, info.duration / 4096.0)`.
- `[+]` → `newSpan = view.durationSeconds / 1.5`; `[-]` → `newSpan = view.durationSeconds * 1.5`; wheel steps the same way by the scroll sign. Anchor: `[±]` on `cursorSeconds`, wheel on the mouse-x time.
- Pure helper:
  ```kotlin
  fun zoomAround(view: AudioViewWindow, anchorSeconds: Double, newSpanSeconds: Double, totalDurationSeconds: Double): AudioViewWindow {
      val frac = ((anchorSeconds - view.startSeconds) / view.durationSeconds).coerceIn(0.0, 1.0)
      return clampWindow(anchorSeconds - frac * newSpanSeconds, newSpanSeconds, totalDurationSeconds)
  }
  fun zoomPercent(view: AudioViewWindow, totalDurationSeconds: Double): Int =
      if (view.durationSeconds <= 0.0) 100
      else Math.round(100.0 * totalDurationSeconds / view.durationSeconds).toInt()
  ```
- `canZoomOut = view.durationSeconds < info.duration` (`clampWindow` caps the span
  at `info.duration`, so at 100% this is exactly false);
  `canZoomIn = view.durationSeconds > maxOf(0.05, info.duration / 4096.0)`.

## Transport (req 7, 8)

| Control | Action |
|---|---|
| `◀◀` Rewind | `seekToSeconds(0.0)` — keeps the current play/pause state (continues playing from 0 if it was playing) |
| `▶` / `❚❚` | `setPlaying(!isPlaying)`; pressing `▶` while `hasEnded` restarts from 0 |
| `■` Stop | `setPlaying(false)` + `seekToSeconds(0.0)` + `view = clampWindow(0.0, view.durationSeconds, total)` |
| `[-]` `[+]` | zoom (anchor = cursor) |

`seekToSeconds(t)`:
```
hasEnded = false
pipeStartSeconds = t.coerceIn(0.0, info.duration)
cursorSeconds = pipeStartSeconds
if (t outside [view.start, view.end]) view = clampWindow(t - view.durationSeconds / 2, view.durationSeconds, total)
restartTrigger++   // respawn pipe + line at -ss t; play state (isPlayingAtomic) carried across
```

### Open button (req: mockup)

`AudioInspectorUI` builds `onOpenAudio` = open a `java.awt.FileDialog(LOAD)`
(directory seeded from `appState.lastOpenedDirectory`, audio-extension filename
filter) → `appState.openFile(picked)` (existing load path; replaces / new tab per
app behaviour). Passed to `FfmpegAudioPlayer`; the button is hidden when the
callback is null.

## Time format (req 8)

New pure `formatMinSecMillis(seconds: Double): String` in `AudioWaveformView.kt`:
```kotlin
fun formatMinSecMillis(seconds: Double): String {
    val totalMs = (seconds * 1000).toLong().coerceAtLeast(0)
    return "%02d:%02d.%03d".format(totalMs / 60000, (totalMs % 60000) / 1000, totalMs % 1000)
}
```
→ `00:32.450`, `125:03.900`. `formatMmSs` / `formatMmSsMs` (used by the video
player) are left untouched.

## Testing

**Pure (unit):**
- `pageScrollView` — past-90%-right → pages forward with cursor at ~10%; cursor left of view → pages back; cursor inside → null; `view.duration >= total` → null.
- `zoomAround` — anchor time is preserved (start + frac*span == anchor, pre-clamp); clamps at 0 and at end.
- `zoomPercent` — 100 at full span, 250 at 0.4×, rounds.
- `formatMinSecMillis` — `0.0` → `00:00.000`; `32.45` → `00:32.450`; `7503.9` → `125:03.900`.
- `niceTimeStep` — monotonic, picks a step giving 4–10 ticks for spans 0.2 s … 3600 s.
- Existing `computeWaveformPeaks` / `downsamplePeaks` / `forEachPeakColumn` /
  `clampWindow` / `visibleBucketRange` tests must stay green unchanged.

**Manual (app), the user's verification list:**
- Waveform ↔ actual playback position stay in sync (driven by `microsecondPosition`).
- Pause → Play resumes the playhead at the right position.
- Click-seek lands accurately (paused → cursor only; playing → continues there).
- Long file (tens of minutes): no UI lag — downsampled draw, no per-frame recompute of peaks.
- Stereo L/R lanes render correctly; mono shows one lane, no crash.
- Playhead x-position stays correct through zoom and scroll.
- No orphan ffmpeg after quitting the app (`pgrep ffmpeg`).

## Files

| File | Action |
|---|---|
| `app/src/main/kotlin/com/multiviewer/ui/AudioSpectrogramDisplay.kt` | delete |
| `app/src/main/kotlin/com/multiviewer/ui/AudioMinimap.kt` | delete |
| `app/src/test/kotlin/com/multiviewer/ui/AudioSpectrogramDisplayTest.kt` | delete |
| `app/src/main/kotlin/com/multiviewer/ui/AudioZoomPan.kt` | remove `followWindow`; keep the rest |
| `app/src/test/kotlin/com/multiviewer/ui/AudioZoomPanTest.kt` | remove `followWindow` cases; add `pageScrollView` / `zoomAround` / `zoomPercent` cases |
| `app/src/main/kotlin/com/multiviewer/ui/AudioWaveformPeaks.kt` | keep peak data + helpers; move `WaveformDisplay` / `WaveformChannelCanvas` / `drawChannelPeaks` out |
| `app/src/main/kotlin/com/multiviewer/ui/AudioWaveformView.kt` | **create** — lanes, playhead, gestures, time axis, `formatMinSecMillis`, `niceTimeStep` |
| `app/src/main/kotlin/com/multiviewer/ui/AudioTransportBar.kt` | **create** — transport / zoom / open bar |
| `app/src/main/kotlin/com/multiviewer/ui/FfmpegAudioPlayer.kt` | rewrite: view-window state, `microsecondPosition` clock, reader thread, page-scroll follow, seek/zoom/stop/rewind, layout; delete `generateSpectrogramImage` / `renderAudioVisualization` / `ChannelMode` / `channelModeFilterArgs` / `AudioZoomScrollbar` |
| `app/src/test/kotlin/com/multiviewer/ui/FfmpegAudioPlayerTest.kt` | delete spectrogram + channelMode cases; add `formatMinSecMillis` etc. (or put those in a new `AudioWaveformViewTest.kt`) |
| `app/src/main/kotlin/com/multiviewer/ui/AudioInspectorUI.kt` | build + pass `onOpenAudio` |

## Global constraints

- Kotlin 2.0.21; do not touch `app/build.gradle.kts`.
- Every ffmpeg/ffprobe `ProcessBuilder` calls `FfmpegLocator.configureEnvironment`;
  the playback pipe registers with `com.multiviewer.util.ProcessManager` and is
  terminated on dispose (as today).
- `FfmpegAudioPlayer`'s public signature gains one nullable param
  (`onOpenAudio: (() -> Unit)? = null`) — no other call site than `AudioInspectorUI`.
- Commit messages end with:
  `Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>`
  `Claude-Session: https://claude.ai/code/session_013FCbWJi4EtnSbNjb27Rvyo`

## Branch

`feat/goldwave-audio-player` off `main` (`fa0bc8c`).
