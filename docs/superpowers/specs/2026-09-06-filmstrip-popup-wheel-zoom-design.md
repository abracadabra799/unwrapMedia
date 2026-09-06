# Filmstrip Frame Popup — Mouse Wheel Zooms (Design)

Date: 2026-09-06
Scope: one file, `app/src/main/kotlin/com/multiviewer/ui/FrameFullSizePreviewWindow.kt`

## Problem

In the full-size frame preview popup (opened by clicking a thumbnail in the
video filmstrip), the mouse wheel steps to the previous/next frame. There is no
way to zoom into a frame. Zooming is the more natural wheel gesture for
inspecting an image; frame stepping already has the ←/→ keys and the on-screen
◀/▶ buttons.

## Change

1. Replace the raw `Image(bitmap, contentScale = Fit)` branch with the existing
   `PixelInspectorPreview(bitmap = bitmap, resetKey = frame, modifier = Modifier.fillMaxSize())`.
   That component (already used by `ImageInspectorUI`) provides cursor-anchored
   wheel zoom (scroll up = zoom in), drag-to-pan when zoomed, and double-click to
   reset — consistent with the rest of the app. `resetKey = frame` means the zoom
   resets to fit whenever the user steps to a different frame.
2. Remove the outer `Box`'s `onPointerEvent(PointerEventType.Scroll, …)` handler
   (which called `stepFrame`) and the now-unused `scrollAccumulator` state.
3. Keep unchanged: the window `onKeyEvent` frame nav (←/→, A/D, `,`/`.`) and the
   ◀/▶ overlay `IconButton`s. They remain siblings layered on top of the preview.
4. Drop the `"  (◀/▶ 방향키 또는 마우스 휠로 프레임 이동)"` hint from the bottom
   caption. No replacement text.

## Non-Goals

- Persisting zoom across frame steps (explicitly chosen: reset per frame).
- Any change to the filmstrip itself or to frame decoding.
- A zoom level indicator / zoom buttons in the popup.

## Testing

UI-only change reusing a tested component (`zoomTowardPoint`, `clampPanOffset`,
`panToPoint` in `PixelInspectorPreview.kt` already have unit coverage). Verify by
compile + a manual run: open a video, click a filmstrip frame, confirm wheel
zooms, drag pans, double-click resets, ←/→ and ◀/▶ still step frames (and reset
the zoom).
