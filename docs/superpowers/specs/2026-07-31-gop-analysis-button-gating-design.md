# GOP Analysis Button Gating Design

## Goal

Disable the "프레임 분석 시작" (start frame analysis) button until the video's background frame-timestamp probe finishes, so it can't be clicked while a same-cost ffprobe scan is already running -- item 3 of a user-reported batch.

## Background

`GopAnalysisView`'s "프레임 분석 시작" button (shown when `tab.gopFrames == null`) is currently clickable at any time, spawning `AppState.analyzeFrames` -> `probeFrameTypes` (`FrameTypeAnalyzer.kt`), a full-file ffprobe scan. This can run concurrently with `FfmpegVideoPlayer`'s own background `probeFrameTimestamps` scan (added in the "video open background frame-timestamp probe" work earlier this session) -- both are full-file ffprobe passes over the same file, so running them at once is the actual source of CPU contention, measured at this session at roughly +22% processing time when run together (not catastrophic, but real).

`FfmpegVideoPlayer`'s own `probing` flag (gating its "동영상 정보 분석 중..." screen) already flips to `false` as soon as the cheap `probeVideo` call resolves -- well before the background timestamp scan finishes, by design (that's the whole point of the earlier fix: the player becomes interactive quickly). So gating the GOP button on that same signal would not actually prevent the contention this item is about; the background scan can still be running well after the player is already interactive. The correct signal is a new one, specific to "the background timestamp probe has finished (successfully or not)."

## Design

1. Add `var videoReadyForAnalysis: Boolean by mutableStateOf(false)` to `TabState` (`AppState.kt`) -- defaults to `false`, one instance per tab (so switching tabs/files doesn't need any explicit reset).
2. Add a new `onProbeComplete: () -> Unit = {}` parameter to `FfmpegVideoPlayer`. Call it once, unconditionally, at the end of the existing `LaunchedEffect(file)` block -- after the `if (info != null) { frameTimestamps = ... }` branch, whether or not that branch ran (a failed/unprobeable video also means nothing is left running in the background, so the button should still become usable rather than staying disabled forever).
3. `VideoInspectorUI.kt` passes `onProbeComplete = { tab.videoReadyForAnalysis = true }` to `FfmpegVideoPlayer`.
4. `GopAnalysisView`'s button (in the `frames == null` branch) gets `enabled = tab.videoReadyForAnalysis` (it already receives `tab` as a parameter, no new parameter needed there). While disabled, a small caption is shown alongside it (e.g. "동영상 분석이 끝나면 활성화됩니다") so the disabled state doesn't read as broken/unexplained -- consistent with this app's existing pattern of pairing a disabled/loading state with an explanatory caption (e.g. `DecodingIndicator`'s label text elsewhere in this file).

## Non-goals

- No change to `FfmpegVideoPlayer`'s own `probing` gate or the player's own interactivity timing -- untouched, per the earlier fix's intent.
- No change to `probeFrameTypes`/`FrameTypeAnalyzer.kt` itself, or to how frame analysis results are displayed once available.
- No attempt to actually prevent/queue/serialize the two ffprobe processes at the OS level -- gating the button so they simply don't get triggered to overlap in the first place is enough, per the measured impact (+22%, non-catastrophic) not justifying more complex coordination.

## Testing

- `TabState.videoReadyForAnalysis` defaults to `false`: can be covered by a simple unit test if `TabState` is otherwise unit-testable (check existing `AppStateTest.kt` conventions).
- `FfmpegVideoPlayer`'s `onProbeComplete` callback firing at the right time is Compose composable/coroutine-sequencing logic with no automated coverage in this project (same category as the earlier background-probe task) -- verified by source-level review (confirming the call site's position in the `LaunchedEffect` body) plus manual confirmation: open a video, confirm the "프레임 분석 시작" button is disabled immediately after opening and becomes enabled a moment later (roughly when the background scan would finish), then confirm clicking it after that still works normally.
