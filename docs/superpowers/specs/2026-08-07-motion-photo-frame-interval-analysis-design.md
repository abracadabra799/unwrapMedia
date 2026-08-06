# Motion Photo Frame Interval Analysis — Design

## Goal

Add a "모션포토 동영상 프레임 드랍 분석" (Motion Photo video frame-drop analysis) menu item under the existing "모션포토" menu, applying the app's existing frame-interval-analysis feature (per-frame interval scatter plot + data table, currently only available for the file's own video track when a video file is open) to a Motion Photo's **embedded video** specifically — not the live preview player, not the file being viewed (which is a photo, not a video).

## Non-Goals

- Any change to the existing "프레임 간격 분석" menu/window for regular video files — that stays exactly as-is.
- Any change to the Motion Photo preview player or the "모션포토 미리보기 재생용 비디오 추출" feature — those operate on `tab.motionPhotoPreview`, a different embedded asset from `tab.embeddedVideo` (the one this feature targets), and are out of scope.
- Deep GOP/frame-type visualization changes — this reuses the existing scatter-plot/table view and its underlying pure functions (`computeFrameIntervals`, `FrameIntervalAnalysisView`) completely unchanged.

## Architecture

Three files change, each additively:

1. **`AppState.kt`** — `TabState` gains three new fields (`motionPhotoGopFrames`, `isAnalyzingMotionPhotoFrames`, `motionPhotoVideoFps`), mirroring the existing `gopFrames`/`isAnalyzingFrames` pair plus one extra field for FPS (the existing video path gets FPS from a separate `probeVideo(tab.file)` call since `tab.file` is directly usable; the Motion Photo path needs FPS from the *extracted* temp file instead, so it's fetched once alongside frame types rather than requiring a second extraction). A new `analyzeMotionPhotoFrames(tab)` function mirrors the existing `analyzeMotionPhotoCodecDetails(tab)`'s extraction pattern exactly: guard on `tab.embeddedVideo`, extract to a temp file via the existing `extractEmbeddedVideo`, run analysis, delete the temp file, guard against redundant re-runs.

2. **`FrameIntervalAnalysisView.kt`** — The existing `FrameIntervalAnalysisWindow(appState, tab, onCloseRequest)` currently owns both data-fetching (`LaunchedEffect` calling `analyzeFrames`/`probeVideo`) and the display `Window` (title, `Box`, loading/empty/graph branches) in one function. Split the display half into a new `private` composable taking plain data (`title`, `frames`, `isAnalyzing`, `fps`, `onCloseRequest`) with no `AppState`/`TabState` coupling. The existing `FrameIntervalAnalysisWindow` becomes a thin wrapper calling it with the existing video data source; a new sibling `MotionPhotoFrameIntervalAnalysisWindow(appState, tab, onCloseRequest)` wraps it with the Motion Photo data source. Both keep "owns its own data-fetching" as a `LaunchedEffect`, matching the existing function's own established comment/convention — neither dumps fetch logic into `Main.kt`.

3. **`Main.kt`** — one new `mutableStateOf(false)` window-open flag, one new `Item(...)` under the existing `Menu("모션포토") { ... }` block (not the separate `Menu("프레임 간격 분석")` block — this feature lives under Motion Photo, per explicit instruction), enabled under the same condition as the existing "모션포토 동영상 추출" item (`currentTab?.embeddedVideo != null`), and one new conditional block opening `MotionPhotoFrameIntervalAnalysisWindow` (mirroring the existing `frameIntervalWindowOpen` block's shape exactly).

## Data Flow

```
Menu("모션포토") → "모션포토 동영상 프레임 드랍 분석" click
  → motionPhotoFrameIntervalWindowOpen = true
  → MotionPhotoFrameIntervalAnalysisWindow(appState, tab, onCloseRequest) composes
    → LaunchedEffect(tab) { appState.analyzeMotionPhotoFrames(tab) }
      → extractEmbeddedVideo(tab.file, tab.embeddedVideo, tempFile)   [same extractor already used by analyzeMotionPhotoCodecDetails]
      → probeFrameTypes(tempFile)   [same ffprobe-based frame-type probe already used by the existing feature]
      → probeVideo(tempFile)        [same ffprobe-based fps probe already used by the existing feature]
      → tempFile.delete()
      → tab.motionPhotoGopFrames = frames; tab.motionPhotoVideoFps = videoInfo?.fps
    → FrameIntervalAnalysisWindowContent(title, frames = tab.motionPhotoGopFrames, isAnalyzing = tab.isAnalyzingMotionPhotoFrames, fps = tab.motionPhotoVideoFps, onCloseRequest)
      → computeFrameIntervals(frames)   [unchanged, pure function]
      → FrameIntervalAnalysisView(intervals, fps)   [unchanged, existing scatter plot + table]
```

No new ffprobe invocation shape, no new pixel-level parsing — this is entirely new wiring around three already-proven pieces (`extractEmbeddedVideo`, `probeFrameTypes`/`probeVideo`, `FrameIntervalAnalysisView`).

## Testing

`analyzeMotionPhotoFrames`'s core extraction-then-probe pattern already has no direct unit test for its sibling `analyzeMotionPhotoCodecDetails` either (it's `AppState`-coupled, background-thread, real-ffprobe-process code — this codebase's established convention for this shape of function is manual/real-file verification, not a synthetic unit test, matching `analyzeMotionPhotoCodecDetails`'s own precedent). The plan should instead:
- Add a unit test only if any new *pure* logic is introduced (none is expected here — this is pure wiring/reuse).
- End with a manual-verification task against a real Motion Photo file, confirming the new menu item is disabled without an embedded video, enabled with one, and that the opened window shows a real scatter plot/table for the embedded video's actual frame data (not the live preview, not the photo's own non-existent "frames") — the same discipline the JPEG/image-formats/video Overview sub-projects' final tasks already established.
