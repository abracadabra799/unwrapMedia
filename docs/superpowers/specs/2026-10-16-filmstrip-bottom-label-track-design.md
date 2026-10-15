# Filmstrip Bottom Label Track Design

**Date:** 2026-10-16  
**Status:** Approved  

## Problem

In `FrameThumbnailFilmstrip`, each frame thumbnail currently overlays a `PreviewCaption` in its top-left corner (`#142 P`).
Because `PreviewCaption` uses a 12sp font, horizontal and vertical padding, and a dark background scrim, it covers a significant portion of the decoded video frame in small thumbnail cells (height 60~90dp). This obscures visual video details (compression artifacts, motion blur, block distortions), which is counterproductive for codec and forensic analysis.

## Goal

Remove the overlay text from inside the video thumbnail. Separate the frame number and frame type into a dedicated, clean bottom label track underneath each thumbnail image.

## Interaction & Visual Design

1. **Cell Layout:**
   - Change each item in the filmstrip `LazyRow` from a single `Box` to a `Column`.
   - **Thumbnail Box (Top, weight 1f):**
     - Contains ONLY the clean decoded `ImageBitmap` (or placeholder).
     - Maintains aspect ratio.
     - Selected frame highlighted with `AppColors.NeonBlue` border.
     - Entire column / thumbnail is clickable (`combinedClickable`):
       - Single click: select frame and seek live player.
       - Double click: open full-size inspection popup.
   - **Label Row (Bottom, height ~14dp):**
     - Frame index: `#<index>` in 9sp `AppColors.TextSecondary` (or `AppColors.NeonBlue` when selected).
     - Frame type: `I` / `P` / `B` in 9sp bold text styled with `AppColors.FrameTypeI`, `AppColors.FrameTypeP`, or `AppColors.FrameTypeB`.
     - Center-aligned underneath the thumbnail.

2. **Resulting Visual:**
   - Video frames are 100% visible and unmasked.
   - Frame metadata is cleanly anchored right below each frame in a neat, professional timeline style.

## Verification
- `./gradlew :app:compileKotlin` and `./gradlew :app:test`.
- Manual inspection: verify thumbnails show clean video frames with metadata neatly displayed underneath.
