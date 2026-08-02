# Frame Interval Analysis Design

## Goal

Add a "프레임 간격 분석" menu that opens a separate window showing, for the current video's frames, a scatter plot of frame interval (time between consecutive frames) over frame number, plus a data table of frame number / timestamp / interval / interval diff -- so irregular frame spacing in the file's own structure (as opposed to runtime playback performance) is visible at a glance.

## Background

`FrameTypeAnalyzer.kt`'s `probeFrameTypes(file)` already extracts, via one `ffprobe` call, a `List<FrameInfo>` (`index`, `type` I/P/B, `sizeBytes`, `ptsSeconds`) for every frame in the video stream -- this is the data source `GopAnalysisView.kt` already uses. `FfmpegVideoPlayer.kt`'s `probeVideo(file)` returns a `VideoInfo` with `fps` (already resolved through the `avg_frame_rate`/`r_frame_rate` fallback and CSV-column-order fix from a prior bug). Both are reused as-is here; no new `ffprobe` invocation is needed.

This is a file-structure analysis (does the file's own PTS spacing look regular?), not a measurement of this app's playback performance -- confirmed explicitly with the user. No "is this a drop" threshold/judgment is computed or shown; the graph and table present the raw numbers and let the user judge visually.

The app's existing top-level menus (`File`, `모션포토`, `비트스트림 추출`, `보기` in `Main.kt`) are simple actions (save-to-file, toggle) with no precedent for opening an independent resizable analysis window -- the closest existing patterns are `GopAnalysisView.kt` (Canvas-drawn per-frame visualization, click-to-seek) and `AudioMinimap.kt` (Canvas-drawn overview with click/drag interaction), both reused here for the plotting and interaction approach.

## Design

### A. Data model and computation

New file `FrameIntervalAnalysis.kt`:

```kotlin
data class FrameInterval(val frameIndex: Int, val ptsSeconds: Double, val intervalMs: Double, val intervalDiffMs: Double)

fun computeFrameIntervals(frames: List<FrameInfo>): List<FrameInterval>
```

`computeFrameIntervals` is a pure function: for each frame from the second onward, `intervalMs = (frames[i].ptsSeconds - frames[i-1].ptsSeconds) * 1000`, and `intervalDiffMs = intervalMs - previousIntervalMs` (0.0 for the first interval, since there is no interval before it to diff against). The first frame itself has no preceding interval and is excluded from the result list. Fewer than 2 input frames yields an empty list.

### B. Menu and window

`Main.kt` gets a new top-level `Menu("프레임 간격 분석")` alongside the existing menus, with a single `Item` enabled only when the current tab is a video with a probed video track (same `hasVideoTrack` check style already used by the 비트스트림 추출 menu). Clicking it opens a new independent `androidx.compose.ui.window.Window` (not a modal `Dialog` -- the table can be long and benefits from independent resizing), title "프레임 간격 분석 - <filename>", containing `FrameIntervalAnalysisView`.

On open, the window runs `probeFrameTypes` and `probeVideo` for the current tab's file on a background thread (same `runInBackground` helper already used by the track-extraction menu items), showing a loading indicator until both complete.

### C. `FrameIntervalAnalysisView.kt`

New file, new `@Composable fun FrameIntervalAnalysisView(intervals: List<FrameInterval>, fps: Double?, frameTypes: List<FrameInfo>, modifier: Modifier = Modifier)`:

- **Top: scatter plot** (`Canvas`/`DrawScope`, same pattern as `AudioWaveformPeaks.kt`/`AudioMinimap.kt`). X axis = frame index (0..last), Y axis = `intervalMs`, auto-scaled to the data's min/max (no fixed/configurable range -- YAGNI). Each frame drawn as a small filled circle (`drawCircle`), colored by frame type using the existing `AppColors.FrameTypeI/P/B` palette (`Theme.kt`, same mapping `GopAnalysisView.kt` already uses) -- no new colors introduced. A thin horizontal reference line at `1000.0 / fps` ms (when `fps` is known and positive) marks the expected interval, so visual outliers are apparent without any hard-coded threshold. No connecting lines between points (explicitly requested: points only).
- **Bottom: scrollable data table** (`LazyColumn`, same scrollbar pattern as `AudioInspectorUI.kt`'s summary list -- `VerticalScrollbar` + `rememberScrollbarAdapter`). Columns: 프레임 번호, 타임스탬프(s), 간격(ms), 간격 diff(ms).
- **Click interaction (bidirectional highlight):** a single `selectedFrameIndex: Int? by remember` drives both directions -- clicking a point in the Canvas (hit-test by nearest point within a small pixel radius of the click, same approach as the existing click-to-seek gesture's position math) sets it and the table auto-scrolls the `LazyListState` to that row; clicking a table row sets it directly. Whichever frame index is selected is drawn with a highlighted color/larger radius on the graph and a highlighted row background in the table. Selecting a new frame simply replaces the previous selection (no multi-select).

### D. Error handling

- `probeFrameTypes` returning `null`, or `computeFrameIntervals` returning an empty list (fewer than 2 frames): show a centered "간격 정보 없음" message instead of an empty graph/table.
- `probeVideo` returning `null` or `fps <= 0`: omit the reference line entirely (graph and table still render from `probeFrameTypes` alone -- the reference line is a nice-to-have, not a hard dependency).
- Large files: no caching, matching `GopAnalysisView`'s existing behavior -- every window open re-runs `ffprobe`, with a loading indicator covering the wait.

### E. Testing

- `computeFrameIntervals` is a pure function -- unit tested directly with synthetic `FrameInfo` lists: regular spacing (all `intervalDiffMs` near 0), one irregular gap (that frame's `intervalDiffMs` clearly nonzero), first-frame exclusion (result size is `input.size - 1`), and 0/1-frame inputs (empty result, no crash).
- The Canvas rendering and click-highlight interaction are not covered by automated tests, consistent with this project's existing convention for Canvas-drawn views (`GopAnalysisView`, `AudioMinimap`) -- covered by code review plus the controller's manual run of the app.

## Non-Goals

- Runtime playback-performance frame drop detection (measuring whether this app's own player fails to render frames in time) -- explicitly out of scope; this feature only analyzes the file's own encoded frame timestamps.
- Any configurable or computed "this frame is dropped" threshold/flag -- the graph and table show raw numbers only; the user judges visually.
- Audio frame/sample-level analysis -- video frames only (this reuses `probeFrameTypes`, which already selects `v:0` only).
- Any change to `GopAnalysisView.kt` or the existing GOP panel -- this is a wholly separate window, not a replacement or modification of it.
