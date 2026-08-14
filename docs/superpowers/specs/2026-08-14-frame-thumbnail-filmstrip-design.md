# Frame Thumbnail Filmstrip — Design Spec

**Goal:** Below the existing GOP timeline (frame-type/size bar chart), show a scrollable row of real decoded thumbnail images, one per frame — synchronized to playback position, each labeled with its frame number and type (I/P/B).

**Context:** `GopAnalysisView.kt` already renders one colored bar per frame (`FrameInfo`: index, type, size, pts), with click/arrow-key selection, auto-scroll-to-current-frame during playback, and its own zoom. `GifFilmstripPlayer.kt` already renders a real filmstrip of decoded frame images for animated GIFs — but that works by holding every GIF frame fully decoded in memory up front, which is only viable because GIFs in this app are frame-count-bounded; a video's frame count is not (many thousands of frames for a multi-minute clip), so this feature needs a different extraction strategy than either of those two precedents.

## Technical foundation (verified against real files)

Extracting one thumbnail per frame via a separate accurate-seek `ffmpeg` call per frame (the approach `CodecViewFrameDecoder.kt` uses for a single selected frame) does not scale to "every frame in the visible scroll window" — each accurate seek re-decodes from the nearest keyframe forward, so doing it once per frame is redundant when frames are contiguous.

Instead, **batched range extraction** — one `ffmpeg` call per visible window, decoding a contiguous run of frames sequentially — is fast and verified directly:

```
ffmpeg -y -i <file> -ss <startPts> -frames:v <count> -vf scale=<width>:-1 -vsync 0 <tempDir>/thumb_%05d.png
```

Confirmed against a real 1752x984 HEVC file: 95 frames (a full 3-second clip) decoded and scaled to 80px-wide thumbnails in 0.38 seconds (8.8x realtime) in a single call. Confirmed separately that seeking to a specific mid-file pts and requesting exactly N frames (`-ss 0.4 -frames:v 10`) produces exactly N sequential thumbnails starting at that point, in presentation order — the same accurate-seek behavior `CodecViewFrameDecoder.kt` already relies on for single-frame extraction, extended with `-frames:v` to grab a run instead of one frame.

## Scope

- Lazy, visible-range-triggered extraction — **not** eager whole-file decoding on open. Only the currently-visible (plus a small prefetch margin) range of frame indices gets decoded, keeping cost bounded by viewport size rather than video length regardless of how long the file is.
- Decoded thumbnails are cached per frame index for the lifetime of the tab (scrolling back to an already-seen range doesn't re-decode). No eviction/LRU cap in this iteration — acceptable given typical usage; flagged as a follow-up if it becomes a problem for very long scroll sessions.
- The filmstrip is its own independently-scrolling/zooming row, **not** horizontally synchronized with the GOP bar chart above it (same column position ≠ same frame in each row's own scroll state). Synchronizing them is a real UX improvement but a materially harder problem (shared scroll-offset + shared px-per-frame across two independent `LazyRow`s); deferred.
- Playback-position sync (current design intent, confirmed important): the filmstrip highlights and auto-scrolls to the frame at the current playback position, the same way `GopAnalysisView` already does for its own bar chart — both views independently track the same `currentFrameIndex`.
- Each cell shows its frame number and type (I/P/B) as a label in the top-left corner.
- Clicking a thumbnail selects that frame (same `tab.selectedFrame`/seek behavior the bar chart's own click handler already has) — both views stay in sync through the same `tab.selectedFrame`.

## Components

### 1. `currentFrameIndex` extracted into a shared pure function

`GopAnalysisView.kt` currently computes this inline as a private `remember` block:

```kotlin
val currentFrameIndex = remember(frames, tab.playbackElapsedSeconds) {
    if (tab.playbackElapsedSeconds <= 0.0) -1
    else frames.indexOfLast { it.ptsSeconds <= tab.playbackElapsedSeconds }
}
```

Extracted into a top-level pure function (e.g. in `FrameTypeAnalyzer.kt`, alongside `FrameInfo`/`probeFrameTypes`) so both `GopAnalysisView` and the new filmstrip composable compute it identically instead of duplicating the logic:

```kotlin
fun currentFrameIndex(frames: List<FrameInfo>, playbackElapsedSeconds: Double): Int =
    if (playbackElapsedSeconds <= 0.0) -1
    else frames.indexOfLast { it.ptsSeconds <= playbackElapsedSeconds }
```

`GopAnalysisView.kt` is refactored to call this instead of its own inline copy — no behavior change, now unit-testable where it wasn't before.

### 2. `FrameThumbnailDecoder.kt` (new file) — batch extraction

- A pure function computing what (if anything) needs fetching, given the currently-visible range and what's already cached/in-flight — e.g. `fun missingThumbnailRange(visibleRange: IntRange, prefetchMargin: Int, frameCount: Int, alreadyCachedOrPending: Set<Int>): IntRange?`, returning `null` when nothing new is needed. Pure and unit-testable (the part of this feature most prone to off-by-one bugs — worth isolating and covering with tests, e.g. empty visible range, fully-cached range, range needing clamping against `frameCount`'s bounds).
- An async batch decoder mirroring `FfmpegImageSnapshotDecoder`'s "run ffmpeg → read output → Skia decode → cleanup" shape, but for N outputs instead of 1: runs the batched-range `ffmpeg` command into a temp directory, reads back exactly `count` sequential PNGs, decodes each via Skia, maps them to their frame indices (`startIndex, startIndex+1, ..., startIndex+count-1`), and deletes the temp directory afterward.

### 3. `TabState` additions (`AppState.kt`)

```kotlin
// Frame thumbnail filmstrip (see FrameThumbnailDecoder.kt) -- keyed by frame index, populated
// lazily as the filmstrip scrolls. pendingThumbnailIndices tracks in-flight requests so a rapid
// double-trigger (e.g. two scroll events before the first batch returns) doesn't launch two
// overlapping ffmpeg calls for the same range.
var thumbnailCache: Map<Int, ImageBitmap> by mutableStateOf(emptyMap())
var pendingThumbnailIndices: Set<Int> by mutableStateOf(emptySet())
```

### 4. `FrameThumbnailFilmstrip.kt` (new file) — UI

A `LazyRow` of fixed-width cells (no zoom, per the approved scope simplification), structurally similar to `GifFilmstripPlayer.kt`'s cell/click/keyboard-step handling but reading from `tab.thumbnailCache` (nullable per index — shows a placeholder/loading state for not-yet-decoded cells) instead of a fully preloaded frame list. A `LaunchedEffect` keyed on the `LazyListState`'s visible-range (via `snapshotFlow` over `listState.layoutInfo.visibleItemsInfo`) computes `missingThumbnailRange(...)` and triggers a batch decode when non-null, marking those indices pending before the request starts and moving them from `pendingThumbnailIndices` into `thumbnailCache` on completion.

Each cell renders:
- The cached thumbnail bitmap (or a placeholder box while pending/not-yet-requested).
- A `Text` label in the top-left corner: `"#<index> <type>"` (e.g. `"#142 P"`).
- A highlighted border when `index == currentFrameIndex(frames, tab.playbackElapsedSeconds)`, and `LaunchedEffect`-driven `animateScrollToItem` to keep that cell in view during playback — same pattern `GopAnalysisView` already uses for its own bar chart, now via the shared `currentFrameIndex` function.
- `clickable { }` selecting that frame (mirrors the bar chart's own `selectFrame`).

### 5. Placement (`VideoInspectorUI.kt`)

Below the existing `GopAnalysisView` in the GOP column, as a second row with its own fixed height (not sharing a `DraggableDivider` split with `GopAnalysisView` in this iteration — a fixed reasonable height, e.g. matching typical thumbnail aspect ratio at the fixed cell width chosen during implementation).

## Error handling

Batch decode failures (ffmpeg missing, timeout, zero frames produced) leave the requested indices absent from `thumbnailCache` — those cells simply show a failure placeholder instead of crashing or blocking the rest of the filmstrip; removed from `pendingThumbnailIndices` either way so a retry can be triggered by scrolling away and back.

## Testing

- `currentFrameIndex` and `missingThumbnailRange` are pure functions — unit tested directly (the former newly testable after extraction; the latter is the highest-risk logic in this feature and gets the most test cases: empty/no visible range, fully-cached range needing nothing, range needing clamping at 0 and at `frameCount - 1`, partially-cached range).
- The actual ffmpeg batch-decode subprocess path is not unit tested, consistent with this codebase's existing convention for every other ffmpeg I/O boundary (`probeFrameTypes`, `decodeSingleFrameToBitmap`, `CodecViewFrameDecoder`).
- Manual verification: open a video with enough frames to scroll, confirm thumbnails populate as you scroll (not all at once), confirm scrolling back to an already-seen range doesn't visibly re-flicker/re-decode, confirm playing the video moves the highlighted/auto-scrolled cell in the filmstrip, confirm each cell's label matches the corresponding GOP bar's own frame number/type directly above it.

## Out of scope (deferred)

- Horizontal scroll/zoom synchronization with the GOP bar chart above it.
- Thumbnail cache eviction/memory cap for extremely long scroll sessions.
- Zoom (cell width adjustment) on the filmstrip itself.
