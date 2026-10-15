# Expand Filmstrip Panel and Fix Bottom Label Clipping Design

**Date:** 2026-10-16  
**Status:** Approved  

## Problem

1. **Filmstrip panel too small / tiny thumbnails:** `gopFilmstripSplit` currently defaults to `0.7f` (GOP 70% vs Filmstrip 30%). In typical window heights, the filmstrip panel only receives ~70dp of vertical space. After subtracting the sub-header and scrollbar, thumbnails are shrunk to tiny sizes (~30-40dp tall).
2. **Bottom label clipped:** The label row under each thumbnail has `height(14.dp)` with tight padding, and in constrained vertical space the label text descenders / numbers get clipped by the item bounds or scrollbar.

## Goals

1. **Expand Default Filmstrip Panel Height:**
   - Change `gopFilmstripSplit` default from `0.7f` to `0.4f` (GOP 40% : Filmstrip 60%).
   - This gives the filmstrip ~140-180dp of vertical height by default, making thumbnails significantly larger and clearly readable.
2. **Prevent Label Clipping and Improve Readability:**
   - Increase label row height from `14.dp` to `18.dp` with proper vertical padding (`verticalAlignment = Alignment.CenterVertically`).
   - Increase font size slightly from `9.sp` to `10.sp` with clean spacing so `#<index>` and frame type `I`/`P`/`B` are crisp and completely visible without clipping.

## Verification
- Run `./gradlew :app:compileKotlin` and `./gradlew :app:test`.
- Rerun app: verify large, clear thumbnails and unclipped frame numbers/types below each thumbnail.
