# Video Open: Background Frame-Timestamp Probe Design

## Goal

Remove the "동영상 정보 분석 중..." freeze that blocks a video tab from becoming interactive until the entire file's frame timestamps have been scanned -- this is the concrete cause behind item 4 of a user-reported batch ("대용량/고해상도 동영상 디코딩에 시간이 많이 소요됨"), confirmed by the user to be specifically about the delay right after opening a file, before anything is visible.

## Background

`FfmpegVideoPlayer` (`app/src/main/kotlin/com/multiviewer/ui/FfmpegVideoPlayer.kt`) currently runs two ffprobe calls sequentially inside one `LaunchedEffect(file)` before flipping `probing = false` (the flag that gates the whole player UI from rendering at all):

1. `probeVideo(file)` -- cheap, single ffprobe call for width/height/fps/duration/rotation.
2. `probeFrameTimestamps(file)` -- walks every frame in the file (`-show_entries frame=pts_time`) to build an exact per-frame duration list used for accurate playback pacing.

Step 2's cost scales with the file's total frame count, so a long and/or high-frame-rate video can leave the user staring at a loading spinner for a long time before even the first frame is shown -- before they've clicked Play, before they can see anything about the file at all.

This is unrelated to item 1 (playback running slower than real duration once already playing), which remains a separate, still-open investigation. It's also unrelated to the resolution-based warnings already in `AppState.kt` (`resolutionWarningMessage`/`hardResolutionRejectionMessage`), which gate the file-open/parse/thumbnail path, not video playback pacing.

The player already has a working fallback for when per-frame timestamps aren't available: `nextFrameDurationSeconds()` falls back to `fallbackDurationSeconds = 1.0 / info.fps` (average-fps pacing) whenever the per-frame `durations` list runs out or was never populated. This fallback is exercised today whenever `probeFrameTimestamps` fails outright (returns `null`, e.g. on a corrupt file) -- it is not new or unproven.

## Design

Split the single blocking `LaunchedEffect(file)` into two sequential steps within the same coroutine:

1. Await `probeVideo(file)` only, then set `probedInfo` and flip `probing = false` immediately. The player UI (first frame, Play button, timeline) becomes visible and interactive as soon as this cheap call returns.
2. Continue in the same coroutine (no new `LaunchedEffect`, no new state-machine) to await `probeFrameTimestamps(file)` and assign the result to `frameTimestamps` whenever it finishes.

No new state variables, no size/resolution/duration threshold, no separate "background scanning" indicator. This applies uniformly to every video, regardless of size.

### Playback pacing during the transition

`DisposableEffect(file, restartTrigger)` (the block that spawns the real ffmpeg playback process) already reads `frameTimestamps` once, at the moment it starts, to build its `durations` list. This does not change. Two cases:

- **Common case after this change**: the user opens the file and presses Play before the background timestamp scan finishes. `frameTimestamps` is still `null` at that point, so `durations` is empty and every frame uses the existing average-fps fallback for that entire playthrough. This is the same fallback already used today when the probe fails outright -- no new code path, no new risk.
- If the user waits, or the file is short enough that the background scan finishes first, `frameTimestamps` is already populated by the time Play is pressed, and pacing is exact per-frame, same as today.

If the background scan finishes *while already playing*, the in-flight playback does **not** hot-swap to the newly-arrived exact timestamps -- it keeps using whatever `durations` it captured at start. The next replay or seek (both already bump `restartTrigger`, re-running `DisposableEffect`) picks up the by-then-populated `frameTimestamps` automatically. Live-swapping mid-playback was considered and rejected: it adds real complexity (mutating a list a running reader thread is indexing into, or restructuring `durations` into an observable/mutable form) for a benefit limited to the tail end of a single first playthrough of a long file -- YAGNI.

### Non-goals

- No resolution/duration/file-size cap on attempting playback or the timestamp probe -- every file gets the same treatment.
- No progress indicator for the background probe -- it's silent; pacing accuracy just improves opportunistically on the next replay/seek.
- No change to item 1 (playback-speed-once-playing) or to the existing `resolutionWarningMessage`/`hardResolutionRejectionMessage` file-open gates.

## Testing

- Existing `probeVideo`/`probeFrameTimestamps` unit tests in `FfmpegVideoPlayerTest.kt` are unaffected (both functions are unchanged; only the order/timing of when their results are *applied* to Compose state changes).
- The change is entirely inside a `@Composable`'s `LaunchedEffect`/state sequencing, which this project has no Compose UI test infrastructure for (no `compose.ui.test` dependency, no prior precedent of testing composable state timing directly). Verification is: (a) code review confirming `probing = false` is set immediately after `probeVideo` resolves and before `probeFrameTimestamps` is awaited, and (b) manual verification by opening a long/large real video and confirming the player becomes interactive quickly instead of sitting on the "분석 중" screen for the file's full timestamp-scan duration.
