# Filmstrip Popup Native Frame View with Zoom/Pan Design

**Date:** 2026-10-16  
**Status:** Approved  

## Problem

In `FrameFullSizePreviewWindow` (the video filmstrip inspection popup), standard videos (H.264, HEVC, AV1) run inside `FfmpegVideoPlayer`. While the player supports basic wheel zoom on the paused frame, users cannot inspect the true native-resolution decoded bitmap with precise pixel-level zoom in/out, drag pan, and pixel grid examination like AV2 does.
The user requested adding an explicit "View Native Frame" button with interactive zoom in/out and pan.

## Solution

1. **Native Frame View Toggle Button in Toolbar:**
   - In the popup window header, next to the "Intervals" button:
     - When in Player mode: `[ 🖼️ 원본 프레임 보기 / View Native Frame ]`
     - When in Native Frame mode: `[ ▶ 동영상 재생기 / Video Player ]`
   - AV2 videos continue using Native Frame mode only.
2. **PixelInspectorPreview Integration:**
   - When `nativeView` is true:
     - Decode the full-resolution frame bitmap via `FrameFullSizeDecoder.decodeFrameAtPtsAsync(tab.file, frame.ptsSeconds)`.
     - Render using `PixelInspectorPreview(bitmap = bitmap, resetKey = frame, modifier = Modifier.fillMaxSize())`.
     - Provides smooth mouse-wheel zoom in/out, drag panning, double-click reset, and pixel grid overlay when zoomed in.
     - Frame stepping (← / → arrow keys) decodes and switches the native frame cleanly.
3. **Switching back to Player mode:**
   - Seamlessly returns to `FfmpegVideoPlayer` paused at the currently selected frame PTS.

## Verification
- Run `./gradlew :app:compileKotlin` and `./gradlew :app:test`.
- Rerun app: verify toggling to native frame view, zooming in/out with mouse wheel, panning with drag, and returning to player.
