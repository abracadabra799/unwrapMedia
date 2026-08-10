# Raw Pixel Viewer and Hex Viewer Zoom — Design

## Background

`docs/superpowers/specs/2026-08-10-image-viewer-zoom-pan-design.md` added scroll-to-zoom/drag-to-pan to the image viewer's thumbnail and primary-image boxes (via a shared `PixelInspectorPreview` composable), and explicitly deferred the remaining panels that still lacked any zoom: the video player (user decided to skip it, out of scope here too), the frame interval analysis graph (user confirmed it doesn't need zoom), the Hex/Raw Data viewer, and the Raw Pixel viewer. This spec covers the latter two.

`RawPixelInspectorUI.kt`'s preview box (for headerless `.raw`/`.rgb`/`.rgba`/`.yuv` dumps) currently renders its decoded frame with a plain `Image(bitmap, contentScale = ContentScale.Fit)` — no interaction. `HexView.kt` renders a fixed-format monospace hex/ASCII grid at a hardcoded `12.sp`.

These are different enough in kind (2D spatial zoom for a bitmap vs. font-size scaling for a text grid) to need separate designs, covered as two sections below.

## Goal

1. **Raw Pixel viewer:** reuse `PixelInspectorPreview` for the same scroll-to-zoom/drag-to-pan/double-click-reset behavior the image viewer already has, with one behavioral difference: zoom/pan must persist across frame changes during multi-frame raw video playback (only resetting on file/tab switch), not reset on every single frame the way the image viewer's existing call sites do.
2. **Hex viewer:** add Cmd/Ctrl+scroll to zoom the monospace font size in and out, without disturbing the existing plain-scroll-to-scroll-the-list behavior.

## Non-Goals

- No changes to the video player, frame interval analysis graph, or Media Structure tree (confirmed out of scope with the user).
- No horizontal scrolling added to the Hex viewer. At the top of its new zoom range a row (8-digit offset + 16 hex byte groups + 16 ASCII chars, ~75 monospace characters) can exceed a narrow panel's width; the existing `softWrap = false` behavior (content clips instead of wrapping and breaking column alignment) is kept as-is. Confirmed acceptable with the user rather than adding scroll infrastructure for a narrow-panel-at-max-zoom edge case.
- No persisted zoom level across file switches for either panel — both reset per-file, matching this app's existing convention (`waveformSplit`, `selectedFrame`, the image viewer's own zoom, etc.).

## Design

### 1. Raw Pixel viewer

**`PixelInspectorPreview.kt`:** add an optional `resetKey: Any = bitmap` parameter, used everywhere `remember(bitmap)` currently keys the `scale`/`offset` state:

```kotlin
@Composable
fun PixelInspectorPreview(bitmap: ImageBitmap, modifier: Modifier = Modifier, resetKey: Any = bitmap) {
    var scale by remember(resetKey) { mutableStateOf(1f) }
    var offset by remember(resetKey) { mutableStateOf(Offset.Zero) }
    ...
}
```

Defaulting to `bitmap` preserves today's behavior exactly for every existing call site (`ImageInspectorUI.kt`'s thumbnail/primary boxes, `GifFilmstripPlayer.kt`'s two call sites) with no changes needed there — each of those already has one stable bitmap per call site (GIF filmstrip cells each show one fixed frame; they don't swap bitmaps during playback the way the raw pixel preview does).

**`RawPixelInspectorUI.kt`:** replace the plain `Image(...)` call with:

```kotlin
PixelInspectorPreview(bitmap, modifier = Modifier.fillMaxSize(), resetKey = tab.file)
```

`tab.file` is stable across frame changes (playback only mutates `tab.rawPixelFrameIndex`/`tab.imageForensic.bitmap`) but changes when the user opens a different file/tab — exactly the reset boundary wanted.

Everything else (zoom formula, pan clamping, `MAX_ZOOM_SCALE = 64f`, double-click reset) is inherited from `PixelInspectorPreview` unchanged.

### 2. Hex viewer

**`HexView.kt`:** add font-size state and a Cmd/Ctrl-gated scroll handler.

```kotlin
private const val MIN_HEX_FONT_SP = 8f
private const val MAX_HEX_FONT_SP = 28f
private const val HEX_ZOOM_STEP_FACTOR = 0.08f // matches PixelInspectorPreview's ZOOM_STEP_FACTOR

fun hexZoomFontSize(currentSp: Float, scrollDeltaY: Float): Float =
    (currentSp * (1f - scrollDeltaY * HEX_ZOOM_STEP_FACTOR)).coerceIn(MIN_HEX_FONT_SP, MAX_HEX_FONT_SP)
```

`var fontSizeSp by remember(file) { mutableStateOf(12f) }` — per-file reset, matching the rest of the app.

The existing hex/ASCII grid `Box` (the one wrapping the `LazyColumn`) gains `Modifier.onPointerEvent(PointerEventType.Scroll, pass = PointerEventPass.Initial)`, matching the pattern `FfmpegAudioPlayer.kt` already uses for its own scroll-zoom:

```kotlin
.onPointerEvent(PointerEventType.Scroll, pass = PointerEventPass.Initial) { event ->
    if (!event.keyboardModifiers.isCtrlPressed && !event.keyboardModifiers.isMetaPressed) return@onPointerEvent
    val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: return@onPointerEvent
    fontSizeSp = hexZoomFontSize(fontSizeSp, delta)
    event.changes.forEach { it.consume() }
}
```

Checking both `isCtrlPressed` (Windows/Linux convention) and `isMetaPressed` (Cmd, macOS convention) covers both platforms this app already targets (per `FfmpegVideoPlayer.kt`'s existing Windows-vs-macOS/Linux performance comments, cross-platform behavior is already a live concern in this codebase). When neither modifier is held, the handler returns early without consuming the event, so it falls through to the `LazyColumn`'s own scroll — existing scroll-to-navigate behavior is untouched.

The row `Text`'s `style` changes from the hardcoded `fontSize = 12.sp, lineHeight = 16.sp` to `fontSize = fontSizeSp.sp, lineHeight = (fontSizeSp * (16f / 12f)).sp`, keeping the current line-height-to-font-size ratio (4:3) at every zoom level. Because the grid is monospace and every row already computes column positions purely from character count (`OFFSET_PREFIX_CHARS`, `HEX_SECTION_CHARS`, `charIndexToByteIndex`), scaling `fontSize` uniformly keeps hex/ASCII column alignment correct at any size with no other layout code changes — `letterSpacing = 0.2.sp` stays a fixed absolute value, matching how it already behaves at the one size used today.

Scroll position on zoom: no special anchoring logic is added. `LazyColumn` already keeps its `firstVisibleItemIndex` stable across recompositions triggered by a style change (it's not remeasuring the item list, just re-rendering existing items at a new text size), so the row the user was looking at stays at the top of the viewport rather than the list jumping back to byte 0. This was confirmed sufficient during design discussion — no cursor-anchored zoom (unlike the image viewer) is needed for a vertically-scrolling text list.

## Testing

- `hexZoomFontSize` is a pure function (mirroring `zoomTowardPoint`'s pattern in `PixelInspectorPreviewTest.kt`) — unit tested directly: clamps at `MIN_HEX_FONT_SP`/`MAX_HEX_FONT_SP`, scales up on negative `scrollDeltaY` (scroll up = zoom in, matching the image viewer's existing sign convention) and down on positive.
- `PixelInspectorPreview`'s new `resetKey` parameter has no new pure logic (it only changes what `remember` is keyed on) — no new unit test beyond confirming existing `PixelInspectorPreviewTest.kt` tests still pass unchanged, since `zoomTowardPoint`/`clampPanOffset` themselves are untouched.
- Both changes are otherwise verified manually (matching this project's established pattern for `graphicsLayer`/gesture-wiring code, e.g. the image viewer design's own Testing section): confirm Raw Pixel zoom persists across playback frame advances and resets on file switch, and confirm Hex viewer zoom responds to Cmd/Ctrl+scroll while plain scroll still navigates the list.
