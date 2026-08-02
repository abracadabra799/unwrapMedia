# Audio Waveform/Spectrogram Zoom & Pan Design

## Goal

Let the waveform and spectrogram panels (in `FfmpegAudioPlayer`, used for every playable audio format including the just-added raw PCM) zoom in/out and pan left/right over the track's timeline, with a minimap that always shows the full track, the current zoom window, and the playhead.

## Background

The waveform (`AudioWaveformPeaks.kt`) already precomputes 4096 min/max buckets per channel up front -- far denser than any realistic panel width -- and `drawChannelPeaks` currently draws all 4096 by mapping bucket index `i` to `x = width * i / bucketCount`. Zooming only needs to draw a sub-range of the same already-computed arrays; no new computation.

The spectrogram (`FfmpegAudioPlayer.kt`'s `generateSpectrogramImage`/`renderAudioVisualization`) is fundamentally different: it's a single ffmpeg-rendered PNG covering the whole file duration, regenerated (debounced) only on panel resize. Per the approved decision, zooming regenerates this PNG for just the visible time window, using the same "prepend input-side ffmpeg flags before `-i`" pattern already established for raw PCM support (`-ss <start> -t <duration>` ahead of `-i`), so zooming in reveals genuinely more spectral detail rather than a blurrier crop of the same fixed-resolution image.

Both panels currently share one seek gesture (`pointerInput` drag → `seekToFraction`, mapping x-position directly to a whole-track time fraction) and one progress-fill overlay (`progress = elapsedSeconds / info.duration`, duplicated identically in both boxes). Zoom introduces a second, independent notion of "visible range" that this existing seek math doesn't account for.

## Design

### A. Shared zoom/pan state

A single state, e.g. `visibleStartSeconds: Double` and `visibleDurationSeconds: Double` (initialized to `0.0` / `info.duration`, i.e. fully zoomed out, matching current behavior exactly), lives in `FfmpegAudioPlayer` and is read by both the waveform and spectrogram panels -- they always show the same window, since they're two views of the same timeline. Resets to fully-zoomed-out whenever `file` changes (same `remember(file) { ... }` pattern already used for every other per-file state in this composable).

`visibleDurationSeconds` is bounded: maximum is `info.duration` (fully zoomed out), minimum is a small floor (e.g. 0.5s) preventing a degenerate zero-width window. `visibleStartSeconds` is clamped to `0..(info.duration - visibleDurationSeconds)` on every change, so panning stops cleanly at either end of the track.

### B. Waveform: windowed drawing, no recompute

`drawChannelPeaks` (and `WaveformChannelCanvas`/`WaveformDisplay`) gain `visibleStartSeconds`/`visibleDurationSeconds` parameters (or the equivalent start/end bucket indices, computed once by the caller). The draw loop changes from iterating `0 until bucketCount` to iterating only the buckets inside the visible window, remapped so that window fills the full canvas width. The underlying `WaveformPeaks` data and `computeWaveformPeaks` call are completely untouched -- this is purely a drawing-range change.

### C. Spectrogram: windowed regeneration

`generateSpectrogramImage`/`renderAudioVisualization` gain a time-window parameter. When zoomed (window narrower than the full duration), the ffmpeg invocation gets `-ss <visibleStartSeconds> -t <visibleDurationSeconds>` inserted before `-i` (composing with the raw-PCM input flags from the existing feature when both apply). The existing debounce (currently keyed on panel resize) also debounces zoom/pan changes, and the previous bitmap stays visible until the new one lands, exactly as today's resize behavior already does -- no new "loading" state needed.

### D. Zoom and pan controls

- **Zoom**: mouse wheel over either panel adjusts `visibleDurationSeconds` (shrink/grow, clamped to the min/max above), keeping the point under the cursor stationary where possible (same spirit as `GopAnalysisView`'s existing wheel-zoom, `onPointerEvent(PointerEventType.Scroll, pass = PointerEventPass.Initial)` so the gesture doesn't fall through to anything else).
- **Pan**: two-finger trackpad horizontal scroll adjusts `visibleStartSeconds` directly. A new draggable horizontal scrollbar beneath each panel (thumb width proportional to `visibleDurationSeconds / info.duration`) provides the same pan, for mouse users and as a visible affordance. Neither of these touches the existing click-drag-to-seek gesture on the panels themselves, which keeps working unchanged (seeking always operates on the whole track's real time, independent of the current zoom window).

### E. Minimap

A new composable, always showing the entire track as a low-detail waveform silhouette (reusing `WaveformPeaks`/`drawChannelPeaks` at a coarser sample of the same 4096-bucket array, e.g. every Nth bucket, rendered thin and dim -- no new computation, no ffmpeg call), with:
- A translucent rectangle showing the current zoom window (`visibleStartSeconds`/`visibleDurationSeconds`), draggable to pan (same effect as the scrollbar/trackpad-scroll, just another way to reach the same state).
- The playhead (current `elapsedSeconds`) drawn as a thin marker across the full track.
- Click-anywhere-to-seek, using the existing whole-track `seekToFraction` logic unchanged (the minimap always spans `0..info.duration`, so no windowing math applies here).

## Error Handling

- `visibleDurationSeconds` never goes below the minimum floor or above `info.duration` -- clamped on every zoom step, so a degenerate (zero-width or inverted) window is never reachable.
- `visibleStartSeconds` is always clamped to keep the full window within `0..info.duration` -- panning stops at the track boundaries rather than showing empty space.
- If a windowed spectrogram regeneration fails (ffmpeg error, timeout), the previous bitmap remains displayed, matching the existing resize-regeneration behavior -- never a blank panel.

## Non-Goals

- Independent zoom/pan per panel (waveform and spectrogram always share one window).
- Zooming/panning while a file is still probing/loading (not meaningful before `info.duration` is known).
- Changing the existing click-drag-to-seek gesture's behavior on the waveform/spectrogram panels themselves.
