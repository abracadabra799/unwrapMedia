# Pixel Grid Overlay — Design

## Background

The user wants an optional grid overlay on the image viewer's thumbnail and primary-image boxes (`PixelInspectorPreview`) and on the video player (`FfmpegVideoPlayer`), toggleable from the menu bar. "Pixel-level" means real native-pixel boundaries (like Photoshop's Pixel Grid), not a fixed on-screen measurement grid — so it's only meaningful once the content is rendered large enough on screen that individual source pixels are visually distinguishable.

The image viewer just gained mouse-wheel zoom/pan/click-to-navigate (`docs/superpowers/specs/2026-08-10-image-viewer-zoom-pan-design.md`); the video player was explicitly excluded from that work and still has none. This design accounts for that difference without special-casing it: the grid always draws at the content's `ContentScale.Fit` size, and for the image viewer, the exact same zoom `graphicsLayer` transform that already scales/pans the `Image` is applied to the grid too, so the grid tracks the image perfectly at every zoom level with no zoom-aware math of its own. The video player has no such transform, so its grid is always drawn at plain fit-scale — which will typically auto-hide (see below) for normal-resolution footage shrunk into a small panel, and simply become visible on its own if video zoom is ever added later, with no changes needed here.

## Goal

A single reusable `PixelGridOverlay` composable draws native-pixel-boundary grid lines within a box, auto-hiding when the on-screen spacing between lines would be too small to be useful. Wired into both `PixelInspectorPreview` (thumbnail + primary image, both call sites already share this composable) and `FfmpegVideoPlayer`. A single global, persisted toggle (`보기` menu, "픽셀 그리드" checkbox item, default off) controls all of them at once.

## Non-Goals

- No configurable grid color, spacing, or opacity — one fixed look.
- No per-panel toggle (thumbnail vs. primary image vs. video independently) — one global setting, matching how the theme toggle already works.
- No changes to `FfmpegVideoPlayer`'s zoom capability — still explicitly out of scope (deferred, per the image-viewer-zoom-pan design).
- No grid on any other panel (GOP graph, frame interval graph, hex viewer, etc.) — out of scope for this change.

## Design

### `PixelGridOverlay` (new file, `PixelGridOverlay.kt`)

```kotlin
private const val MIN_SCREEN_PX_PER_GRID_LINE = 8f
private val GRID_LINE_COLOR = Color.White.copy(alpha = 0.25f)

// Draws native-pixel-boundary grid lines within `boxSize`, for content of `nativeSize` shown at
// ContentScale.Fit -- i.e. exactly the lines Photoshop's "Pixel Grid" would draw. `scale` is the
// CALLER's own zoom factor (1f if the caller has no zoom, like FfmpegVideoPlayer) -- used only to
// decide whether lines would be too dense to be useful; the lines themselves are always drawn at
// plain fit-scale, in this composable's own untransformed coordinate space. Callers that zoom
// (PixelInspectorPreview) apply the exact same graphicsLayer transform to this composable as they
// apply to their Image, so the grid tracks the zoomed image with no zoom-aware drawing logic here.
fun shouldDrawPixelGrid(nativeSize: Size, boxSize: Size, scale: Float): Boolean {
    if (nativeSize.width <= 0f || nativeSize.height <= 0f || boxSize.width <= 0f || boxSize.height <= 0f) return false
    val fitScale = minOf(boxSize.width / nativeSize.width, boxSize.height / nativeSize.height)
    return (fitScale * scale) >= MIN_SCREEN_PX_PER_GRID_LINE
}

@Composable
fun PixelGridOverlay(nativeSize: Size, scale: Float, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxSize()) {
        if (!shouldDrawPixelGrid(nativeSize, size, scale)) return@Canvas
        val fitScale = minOf(size.width / nativeSize.width, size.height / nativeSize.height)
        val fittedWidth = nativeSize.width * fitScale
        val fittedHeight = nativeSize.height * fitScale
        val left = (size.width - fittedWidth) / 2f
        val top = (size.height - fittedHeight) / 2f

        var x = 0
        while (x <= nativeSize.width.toInt()) {
            val screenX = left + x * fitScale
            drawLine(GRID_LINE_COLOR, Offset(screenX, top), Offset(screenX, top + fittedHeight))
            x++
        }
        var y = 0
        while (y <= nativeSize.height.toInt()) {
            val screenY = top + y * fitScale
            drawLine(GRID_LINE_COLOR, Offset(left, screenY), Offset(left + fittedWidth, screenY))
            y++
        }
    }
}
```

`shouldDrawPixelGrid` is a pure function (unit-testable without Compose test infra, matching this project's established convention). The loop bound (`x <= nativeSize.width.toInt()`) is safe specifically *because* `shouldDrawPixelGrid` already gated out any case where `fitScale * scale` is below the 8px-per-line threshold — for any native image/video this app handles (checked elsewhere against a ~268-megapixel ceiling), that gate keeps the number of lines drawn bounded to whatever actually fits legibly in a real window, never tens of thousands of lines.

### Wiring into `PixelInspectorPreview`

Added as the last child inside the existing `Box`, after the `Image`, with **the same `graphicsLayer` modifier** (`scaleX`/`scaleY`/`translationX`/`translationY`/`transformOrigin`) so it zooms/pans in lockstep with the image it's overlaid on:

```kotlin
if (LocalShowPixelGrid.current) {
    PixelGridOverlay(
        nativeSize = Size(bitmap.width.toFloat(), bitmap.height.toFloat()),
        scale = scale,
        modifier = Modifier.graphicsLayer(
            scaleX = scale, scaleY = scale,
            translationX = offset.x, translationY = offset.y,
            transformOrigin = TransformOrigin(0f, 0f),
        ),
    )
}
```

### Wiring into `FfmpegVideoPlayer`

Added next to the existing frame `Image`, with `scale = 1f` (no zoom transform to match, since this player has none):

```kotlin
if (LocalShowPixelGrid.current) {
    PixelGridOverlay(nativeSize = Size(info.width.toFloat(), info.height.toFloat()), scale = 1f)
}
```

### Global toggle

A new `LocalShowPixelGrid = compositionLocalOf { false }` (in `PixelGridOverlay.kt`), following the exact pattern `LocalThemePalette` already establishes in `Theme.kt`. `AppTheme` (`Theme.kt`) gains a `showPixelGrid: Boolean` parameter and provides it via the same `CompositionLocalProvider` call that already provides `LocalThemePalette`, so every composable under `AppTheme` (i.e. the whole app) can read `LocalShowPixelGrid.current` without threading a new parameter through `DashboardLayout` → `ImageInspectorUI`/`VideoInspectorUI` → `PixelInspectorPreview`/`FfmpegVideoPlayer`.

Persistence mirrors `ThemePreference.kt` exactly: a new `PixelGridPreference.kt` with `loadShowPixelGrid(): Boolean` (default `false`) / `saveShowPixelGrid(Boolean)`, backed by `java.util.prefs.Preferences`, same key style.

`Main.kt`: a third `CheckboxItem("픽셀 그리드", checked = showPixelGrid, onCheckedChange = { showPixelGrid = it; saveShowPixelGrid(it) })` in the existing `Menu("보기")`, alongside the two theme items. `showPixelGrid` state (`var showPixelGrid by remember { mutableStateOf(loadShowPixelGrid()) }`) is declared next to the existing `themeMode` state and passed into `AppTheme(themeMode, showPixelGrid) { ... }`.

## Testing

`shouldDrawPixelGrid` is a pure function, unit-tested directly (no Compose, no ffmpeg, no real files):
- Returns `false` when the fit-scale-adjusted spacing is below the 8px threshold (e.g. a 4000px-wide native image fit into a 400px box at `scale = 1f`).
- Returns `true` once `scale` (zoom) pushes the effective spacing back above the threshold for that same image.
- Returns `false` for a degenerate zero-size `nativeSize` or `boxSize` (guards the division).

`PixelGridOverlay`'s actual `Canvas` drawing, and the menu/preference wiring, are verified manually, matching this project's established pattern for Compose UI code (e.g. `WaveformDisplay`, `SpectrogramDisplay`).
