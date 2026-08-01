# GIF Animation Playback (Full-Width Frame Filmstrip) Design

## Goal

Make animated GIFs actually animate in the app. Today a `.gif` file is treated purely as a static image: `ImageAnalyzer.decodePrimaryBitmapAndHistogram` calls Skia's single-frame `Image.makeFromEncoded`, so only the first frame is ever shown, and `GifWalker.kt`'s structural parser exposes per-frame timing (`delay_time`, `NETSCAPE2.0` loop count) only as inspectable metadata fields in the box tree -- nothing reads them for playback. This design replaces the static view with an interactive, full-width frame filmstrip: real per-frame thumbnails you can scrub through by hand or auto-play at the GIF's own timing.

## Background

`.gif` is classified as an image (`IMAGE_EXTENSIONS`, `AppState.kt`), so it renders through `ImageInspectorUI.kt`'s `centerPanel`: a top `Row` of three equal-width boxes (EXIF thumbnail / primary image / motion-photo video), a draggable-height summary dashboard below, and a `DetailedPropertiesPanel` on the right driven by `tab.selected: BoxNode?`.

For GIF specifically, two of those three top-row boxes are always dead space: GIF has no embedded-thumbnail concept distinct from its own frames (confirmed by reading `GifWalker.kt` -- it parses `LogicalScreenDescriptor`, `GlobalColorTable`, `GraphicControlExtension`, `ApplicationExtension`, `ImageDescriptor`/`LocalColorTable`, `Trailer`, none of which represents a separate preview image), and GIF is never a motion-photo carrier. So for GIF files this design replaces the entire three-box `Row` with one full-width filmstrip panel; all other image formats keep the existing three-box layout unchanged.

The key enabling fact: this project already depends on `skiko` (`org.jetbrains.skia.*`, used today for the static single-frame decode), and skiko's `Codec` class (confirmed present in the resolved `skiko-awt` jar, `org/jetbrains/skia/Codec.class` / `AnimationFrameInfo.class`) already implements correct multi-frame animated decode: `frameCount`, `framesInfo: Array<AnimationFrameInfo>` (each with `duration` in milliseconds), `repetitionCount` (loop count, `-1` = infinite), and `readPixels(bitmap, frame, priorFrame)` which internally handles GIF's disposal-method and transparency compositing per frame. No hand-rolled GIF LZW/compositing decoder is needed, and no new dependency is added.

## Design

### A. Frame decoding: `GifFrameDecoder.kt` (new file, `app/src/main/kotlin/com/multiviewer/parser/`)

```kotlin
data class GifAnimationData(
    val frames: List<ImageBitmap>,
    val durationsMs: List<Int>,
    val loopCount: Int,       // -1 = infinite, matches Codec.repetitionCount
    val totalFrameCount: Int, // count reported by the codec, before any cap
    val truncated: Boolean,   // true if totalFrameCount > MAX_GIF_FRAMES
)

const val MAX_GIF_FRAMES = 500

fun decodeGifAnimation(file: File): GifAnimationData?
```

`decodeGifAnimation` wraps `Codec.makeFromData(Data.makeFromBytes(file.readBytes()))` in a `try`/`catch`, returning `null` on any decode failure (corrupt file, unsupported variant). On success: reads `frameCount` and `framesInfo` once, computes `truncated = frameCount > MAX_GIF_FRAMES`, then decodes frames `0 until min(frameCount, MAX_GIF_FRAMES)` sequentially, passing `priorFrame = index - 1` (or omitting it for frame 0) so the codec can reuse its own internal compositing state -- each decoded `Bitmap` is converted to `ImageBitmap` the same way the existing static path already does (`Image.makeFromBitmap(...).toComposeImageBitmap()`). `durationsMs` comes from each frame's `AnimationFrameInfo.duration`. If `frameCount <= 1`, still returns a valid single-frame `GifAnimationData` (the UI layer decides whether to show filmstrip chrome -- see below).

This runs off the UI thread (a coroutine on `Dispatchers.IO`, launched from a `LaunchedEffect` keyed on the file, following this codebase's existing async-decode conventions) since decoding up to 500 frames is real CPU work.

### B. UI: `GifFilmstripPlayer.kt` (new file, `app/src/main/kotlin/com/multiviewer/ui/`)

```kotlin
@Composable
fun GifFilmstripPlayer(tab: TabState, animation: GifAnimationData, gifFrameNodes: List<BoxNode>, modifier: Modifier = Modifier)
```

`gifFrameNodes` is the ordered list of `ImageDescriptor` `BoxNode`s from the already-parsed GIF box tree (same tree `BoxTreeView` displays) -- obtained by walking `tab.root` in document order and collecting nodes where `type == "ImageDescriptor"`, which are emitted in the same left-to-right frame order the codec decodes -- positionally matched to `animation.frames` by index (`gifFrameNodes[i]` describes `animation.frames[i]`). This is what lets frame selection drive `DetailedPropertiesPanel` (below).

- **Filmstrip**: a `LazyRow`, one cell per decoded frame, each cell sized to fill the panel's available height (so frames are genuinely viewable, not thumbnail-sized -- this is why no separate "now playing" preview is needed elsewhere in the panel) with width following the frame's aspect ratio. Horizontal scroll plus mouse-wheel zoom and click-to-select and left/right-arrow-key stepping, following `GopAnalysisView.kt`'s existing `LazyRow` interaction pattern (`FocusRequester`/`onKeyEvent`, `onPointerEvent(PointerEventType.Scroll)` for zoom, `animateScrollToItem` to keep the current frame in view). The current frame gets a colored border (matching `AppColors.NeonGreen`, consistent with this app's other "active/current" indicators).
- **Selection side effect**: selecting frame `i` (by click, arrow key, or auto-play advance) sets `tab.selected = gifFrameNodes.getOrNull(i)` -- reusing `DetailedPropertiesPanel`'s existing generic `BoxNode` rendering with zero changes to that panel, surfacing that frame's real `delay_time`/disposal fields from the structural parse.
- **Playback**: starts paused, on the first frame. A play/pause icon button (same visual treatment as `FfmpegVideoPlayer`'s / `MotionPhotoVideoPreview`'s overlay button) toggles a `LaunchedEffect(isPlaying)` loop: while playing, `delay(animation.durationsMs[current])`, then advance `current = (current + 1) % frames.size`, auto-scrolling the strip to follow. Looping honors `animation.loopCount` when finite (stops after that many repeats through the sequence) and repeats forever when `-1`. Clicking a frame or pressing an arrow key while playing pauses playback at that frame (matches the approved design: manual navigation always takes over from auto-play, never fights it).
- **Caption**: bottom-corner text, reusing the existing `PreviewCaption` composable, showing `"Frame ${current + 1}/${frames.size} · ${durationsMs[current]}ms"`.
- **Truncation notice**: if `animation.truncated`, a small warning line noting `"First $MAX_GIF_FRAMES of ${animation.totalFrameCount} frames shown"`.
- **Single-frame GIFs**: if `animation.frames.size <= 1`, render the one frame as a plain static image (no filmstrip chrome, no play/pause button, no caption) -- avoids showing playback controls with nothing to play.

### C. Wiring in `ImageInspectorUI.kt`

The existing top `Row` (three boxes: thumbnail / primary / motion-photo) is wrapped in a branch on file extension:

- `.gif` (and `animation != null`, i.e. decode succeeded): render `GifFilmstripPlayer` alone, filling the full row width.
- decode failed (`animation == null`) or any other image extension: existing three-box `Row`, completely unchanged -- for a GIF whose animated decode failed, this means falling back to today's static single-frame view via the existing `ImageAnalyzer`/`PixelInspectorPreview` path, so behavior never regresses below current behavior.

`GifAnimationData` and `gifFrameNodes` are computed once per tab (background decode triggered when a GIF tab is opened/selected, same lifecycle as other per-tab derived state already on `TabState`), not recomputed on every recomposition.

## Non-Goals

- Animated WebP or other multi-frame formats -- `Codec` could technically handle these too, but this design is scoped to GIF only, per the original request. A follow-up could extend the same `GifFrameDecoder`/`GifFilmstripPlayer` pair generically if wanted later.
- Editing/exporting frames, extracting a single frame to a file, adjusting playback speed, or reversing playback -- none of this was requested; the scope is view/scrub/play only.
- A fully general "any BoxNode can drive a filmstrip" abstraction -- this is built specifically for GIF's `ImageDescriptor` nodes; generalizing further is unnecessary speculation (YAGNI) unless a second format needs the same treatment.
