# Video Frame-Type (GOP) Analysis — Design

## Background

`VideoInspectorUI` currently shows a live player and a text-only media summary. There is no way to see per-frame codec-level information (I/P/B frame type, per-frame size, GOP structure) — the kind of view tools like Elecard StreamEye provide. The container-level box tree (`stss`, `stts`, `stsz`) can identify sync samples (I-frames) but cannot distinguish P from B frames — that requires parsing the actual H.264/HEVC bitstream, which `ffprobe` (already bundled for this app's other video features) already does internally via `-show_frames`.

Two now-unused code paths already exist in this area and are being retired as part of this work: `BitrateVisualizer.kt` and `VideoAnalyzer.kt`'s `calculateBitrate` (a bitrate-over-time line graph, capped at the first ~500 samples, never wired into any UI) and `BoxBlockView.kt` (a box-type-weight treemap, also never wired in). Both are fully superseded by this feature.

## Goal

From `VideoInspectorUI`, a user can trigger a per-frame I/P/B breakdown for the whole video and see it as a horizontally scrollable, color-coded bar graph (StreamEye-style), covering every frame regardless of video length. Clicking a frame shows its details (frame number, type, size, timestamp) in the existing right-hand details panel.

## Non-Goals

- No hand-rolled H.264/HEVC bitstream parser — `ffprobe -show_frames` supplies frame types directly.
- No automatic analysis on tab open — this is a manually-triggered, on-demand action (long videos can take several seconds to probe, and not every user needs this view).
- No GOP-boundary decoration (extra markers/bands) beyond the I-frame color itself standing out — can be added later if wanted.
- No change to the box-tree / hex-view selection behavior beyond making it mutually exclusive with frame selection.

## Design

### Data extraction (`FrameTypeAnalyzer.kt`, new, `com.multiviewer.ui` package — alongside `FfmpegVideoPlayer.kt`/`FfmpegLocator.kt`, the other ffprobe/ffmpeg-invoking code)

```kotlin
data class FrameInfo(val index: Int, val type: Char, val sizeBytes: Int, val ptsSeconds: Double)

fun probeFrameTypes(file: File): List<FrameInfo>?
```

Runs `ffprobe -v error -select_streams v:0 -show_entries frame=pict_type,pkt_size,pts_time -of csv=p=0 <file>`, parses each CSV row (`pict_type,pkt_size,pts_time`) into a `FrameInfo` (frame index = row position = decode order, matching `-show_frames`'s natural output order). Returns `null` on any failure (matches `probeVideo`'s existing error-handling convention). `redirectError(ProcessBuilder.Redirect.DISCARD)` and a generous timeout (120s — this is a user-triggered, expected-to-take-a-few-seconds operation, unlike the always-on-open analyses fixed earlier today) match the same conventions already used in `FfmpegVideoPlayer.kt`/`FfmpegImageSnapshotDecoder.kt`.

### State (`AppState.kt`)

`TabState` gains:
```kotlin
var gopFrames: List<FrameInfo>? by mutableStateOf(null)
var isAnalyzingFrames: Boolean by mutableStateOf(false)
var selectedFrame: FrameInfo? by mutableStateOf(null)
```

A new function (e.g. `fun analyzeFrames(tab: TabState)`, top-level in `FrameTypeAnalyzer.kt` alongside `probeFrameTypes`) guards against double-triggering, runs `probeFrameTypes` on a background `Thread` (`isDaemon = true`), and marshals the result back via `EventQueue.invokeLater` — the same pattern `openFile()` now uses and `FfmpegImageSnapshotDecoder` already established. On completion, sets `tab.gopFrames` (empty list on failure, so the UI can distinguish "never asked" from "asked, got nothing") and `tab.isAnalyzingFrames = false`.

`VideoAnalysisData`, `BitratePoint`, and `tab.videoAnalysis` are deleted from `AppState.kt`; the `tab.videoAnalysis = VideoAnalyzer.analyze(file, root)` line is removed from `openFile()`'s `MediaType.VIDEO` branch.

### UI (`GopAnalysisView.kt`, new; `VideoInspectorUI.kt` and `ImageInspectorUI.kt`'s `DetailedPropertiesPanel`, modified)

`GopAnalysisView(tab: TabState)`, a fixed-height section (not part of the scrollable summary) inserted in `VideoInspectorUI.kt` between the player `Box` and the existing `DraggableDivider`/summary `LazyColumn`:

- `tab.gopFrames == null && !tab.isAnalyzingFrames` → a button ("프레임 분석 시작") calling `analyzeFrames(tab)`.
- `tab.isAnalyzingFrames` → "분석 중..." text, matching the existing "Analyzing..."/"Decoding via ffmpeg..." placeholder style used elsewhere in this codebase.
- `tab.gopFrames != null` (non-empty) → a `LazyRow` of fixed-width bars, one per frame: height proportional to `sizeBytes` (normalized against the max size in this video), color by `type` (`I` → `AppColors.NeonRed`, `P` → `AppColors.NeonGreen`, `B` → `AppColors.NeonBlue`, matching the palette already used for other markers in this app), `onClick` sets `tab.selectedFrame = frame` and `tab.selected = null`. `LazyRow` virtualizes off-screen items, so this scales to tens of thousands of frames without a performance issue (the exact class of problem fixed elsewhere in the app today).
- `tab.gopFrames != null` (empty, i.e. probe failed or found nothing) → a short "Could not analyze frames" message.

`DetailedPropertiesPanel` (in `ImageInspectorUI.kt`, shared by both inspectors via `rightPanel`) gains a check at the top: if `tab.selectedFrame != null`, render its fields (Frame #, Type, Size, PTS) with the same `PropertyRow` composable already used for box-tree fields, instead of the existing `selectedNode` branch. The box tree's `onSelect` callback (in `Main.kt`) additionally clears `tab.selectedFrame = null`, so the two selection sources never fight over the panel.

### Cleanup

Delete `app/src/main/kotlin/com/multiviewer/ui/BitrateVisualizer.kt`, `app/src/main/kotlin/com/multiviewer/ui/BoxBlockView.kt`, and `app/src/main/kotlin/com/multiviewer/parser/VideoAnalyzer.kt` in full — all three are fully superseded, and `boxWeights`/`BoxBlockView` were already dead code independent of this feature (confirmed via full-codebase search: no call sites beyond their own definitions).

## Testing

`probeFrameTypes` is tested the same way `probeVideo` already is: generate a real synthetic video via `ffmpeg -f lavfi -i "testsrc=..."`, run `probeFrameTypes` against it, assert a non-empty frame list, the first frame's type is `'I'`, and sizes/timestamps are sane (no mocking, matches this codebase's established testing convention for ffmpeg-backed code).

No automated test for the new/modified Composables (`GopAnalysisView`, `DetailedPropertiesPanel`'s new branch) — established convention for this project's UI layer. Manual verification: open a video, click "프레임 분석 시작", confirm the bar graph renders with I/P/B coloring, click a bar and confirm the right panel shows that frame's details, click a box-tree node afterward and confirm the panel switches back correctly, and confirm `VideoInspectorUI`/`ImageInspectorUI` (which shares `DetailedPropertiesPanel`) both still work normally with no `videoAnalysis`/`BitrateVisualizer`/`BoxBlockView` references left anywhere (full test suite compiling clean after their deletion is the automated half of this check).
