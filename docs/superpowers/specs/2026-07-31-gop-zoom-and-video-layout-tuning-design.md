# GOP Zoom/Pan and Video Layout Ratio Tuning Design

## Goal

Three related tweaks to the video tab's layout, gathered in one request:
1. Detailed Properties panel narrower by 15%, **for video tabs only**.
2. Video player narrower (GOP wider) within their side-by-side split, to roughly a 70%-of-current player width.
3. GOP frame-analysis panel: mouse-wheel zoom in/out on the frame bars (denser/sparser view), plus click-and-drag panning without needing the scrollbar.

## Background

- `DashboardLayout.kt`'s right panel currently starts at a single shared `RIGHT_PANEL_DEFAULT_WIDTH_DP = 350f` for both image and video tabs (drag range 220-1000dp, unchanged).
- `VideoInspectorUI.kt`'s player/GOP side-by-side split (`videoGopSplit = 0.65f`, i.e. player gets 65% of the row's width) was just shipped in the previous task.
- `GopAnalysisView.kt` renders each frame as a fixed `FRAME_BAR_WIDTH_DP = 16` dp-wide bar inside a `LazyRow`, clicking a bar selects+seeks that frame. Compose's `clickable` + an ancestor `scrollable` (which `LazyRow` provides internally) already arbitrate press-vs-drag by touch slop: a press that doesn't move beyond a small threshold fires the click, a press that moves past it is treated as a scroll drag instead -- this is standard, already-relied-upon Compose gesture behavior (the file's own comment at the `HorizontalScrollbar` already says "LazyRow scrolls via drag/trackpad regardless"). **Click-to-select and drag-to-pan therefore already both work today, with no code change needed** -- confirmed by reading `GopAnalysisView.kt` and Compose's `LazyRow`/`clickable` gesture-arbitration contract, not assumed.
- Mouse-wheel zoom is genuinely new: nothing in this codebase currently intercepts `PointerEventType.Scroll`.

## Design

### A. Right panel width, video-only

Add an optional parameter to `DashboardLayout`: `rightPanelDefaultWidthDp: Float = RIGHT_PANEL_DEFAULT_WIDTH_DP` (in `DashboardLayout.kt`), used at its existing `var rightPanelWidthDp by remember { mutableStateOf(RIGHT_PANEL_DEFAULT_WIDTH_DP) }` line (becomes `mutableStateOf(rightPanelDefaultWidthDp)`). `ImageInspectorUI.kt`'s call site is unchanged (keeps the 350dp default). `VideoInspectorUI.kt`'s call site passes `rightPanelDefaultWidthDp = 298f` (350 * 0.85, rounded). The 220-1000dp drag range and all other `DashboardLayout` behavior are unchanged.

### B. Player/GOP width ratio

`VideoInspectorUI.kt`'s `videoGopSplit` initial value changes from `0.65f` to `0.455f` (65% * 0.7 = 45.5%, i.e. the player's share shrinks to 70% of what it was; GOP's share grows from 35% to 54.5%). The 0.1-0.9 drag-clamp range (enforced inside `DraggableDivider`) is unchanged.

### C. GOP mouse-wheel zoom

In `GopAnalysisView.kt`:
- Replace the fixed `FRAME_BAR_WIDTH_DP` constant's direct use with a per-composable mutable state: `var frameBarWidthDp by remember { mutableStateOf(FRAME_BAR_WIDTH_DP.toFloat()) }`, initialized from the existing constant (kept as the default/starting value, same as today's fixed 16dp). Both existing usages (`.width(FRAME_BAR_WIDTH_DP.dp)` on the item's `Column` and its `Box`) switch to `.width(frameBarWidthDp.dp)`.
- Two new constants bound the zoom range: `FRAME_BAR_MIN_WIDTH_DP = 4f` (zoomed all the way out -- many thin bars visible at once) and `FRAME_BAR_MAX_WIDTH_DP = 48f` (zoomed all the way in -- few, wide bars). A third, `FRAME_BAR_ZOOM_STEP_DP = 2f`, is the change per wheel-scroll unit.
- The `LazyRow`'s `modifier` gets a `.onPointerEvent(PointerEventType.Scroll, pass = PointerEventPass.Initial) { event -> ... }` handler, added so it intercepts the scroll event *before* `LazyRow`'s own internal horizontal-scroll handling can consume it for panning (using `PointerEventPass.Initial`, which runs on the way down the tree before descendants see the event) -- this means plain mouse-wheel over the GOP panel no longer scrolls the list horizontally at all; it only zooms. Panning still works via click-and-drag (per Background, already functional) and via the existing `HorizontalScrollbar`.
- Direction: scrolling up (away from the user) zooms **in** (bigger bars, fewer visible); scrolling down zooms **out** (smaller bars, more visible) -- the conventional direction used by map/image-viewer zoom. Implementation: `frameBarWidthDp = (frameBarWidthDp - scrollDelta.y * FRAME_BAR_ZOOM_STEP_DP).coerceIn(FRAME_BAR_MIN_WIDTH_DP, FRAME_BAR_MAX_WIDTH_DP)`, relying on Compose's convention that an upward wheel scroll reports a negative `scrollDelta.y`. Manual verification (see Testing) confirms this feels right; if it's inverted in practice, the fix is a one-line sign flip, called out explicitly in the plan.
- Bar **height** is unaffected by zoom -- it's already a fraction of the container's height based on the frame's byte size (unrelated to width), and stays that way. Only width (and therefore how many frames fit on screen) changes with zoom.

### Non-goals

- No change to `GopAnalysisView`'s click-to-select/seek behavior, keyboard arrow-key stepping, or the legend row.
- No persistence of zoom level or panel widths across app restarts (both already reset to their defaults each launch, same as every other panel size in this app).
- No modifier-key requirement (e.g. Ctrl+wheel) for zoom -- plain wheel zooms, per the user's request.

## Testing

Same category as the prior layout tasks -- Compose composable state/layout/gesture code with no automated test coverage in this project. Verification is source-level review of the changes (the modifier chain, the pointer-event interception order, the constant/state wiring) plus manual confirmation: open a video, confirm the Detailed Properties panel and player/GOP split start at their new sizes, confirm mouse wheel over the GOP panel zooms the frame bars in/out (and confirm the zoom direction feels right -- flip the sign if not), confirm frames stay clickable to select+seek at any zoom level, and confirm click-and-drag on the frame area still pans without needing to grab the scrollbar.
