# Filmstrip Zero Gap Seamless Design

**Date:** 2026-10-16  
**Status:** Approved  

## Problem

In `FrameThumbnailFilmstrip`, there is visible horizontal padding between adjacent frame thumbnails (`padding(horizontal = 1.dp)`, and inside cell bounds). Additionally, using `ContentScale.Fit` can leave minor letterboxing inside the thumbnail box.
As a result, frames appear disjointed with noticeable gaps between them, rather than reading as a continuous, seamless filmstrip.

## Solution

1. **Remove Horizontal Padding (Zero-Gap Continuous Reel):**
   - Eliminate `padding(horizontal = 1.dp)` from the thumbnail `Column` and `Box`.
   - Frames sit directly flush against each other (gap = 0dp), creating a true continuous filmstrip visual.
2. **Seamless Image Fill:**
   - Use `contentScale = ContentScale.Crop` on the thumbnail `Image` so decoded pixels completely fill the frame box edge-to-edge without any internal letterboxing margins.
3. **Selection Highlighting:**
   - Retain the `2.dp` `AppColors.NeonBlue` border for the selected frame so active selection remains distinct and immediate without introducing outer spacing.
4. **Bottom Label Row Alignment:**
   - Center `#<index>` and frame type `I`/`P`/`B` inside each cell's width cleanly with `maxLines = 1`.

## Verification
- Run `./gradlew :app:compileKotlin` and `./gradlew :app:test`.
- Rerun app: verify frames form a continuous, seamless strip with zero gap between frames.
