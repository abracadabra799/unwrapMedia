# GOP Panel Width and Frame Bar Height Tuning Design

## Goal

Two more video-tab tweaks: widen the GOP panel further (shrink the player further), and shorten how tall each frame bar renders in the GOP view.

## Design

### A. Player/GOP width ratio, further adjustment

`VideoInspectorUI.kt`'s `videoGopSplit` changes from `0.455f` to `0.35f` (player 35% / GOP 65%, up from 45.5%/54.5%). Same constant, same drag-clamp range (0.1-0.9, unchanged).

### B. Frame bar height cap

In `GopAnalysisView.kt`, the tallest frame bar currently reaches 100% of the available panel height (`heightFraction = (frame.sizeBytes.toFloat() / maxSize).coerceAtLeast(0.02f)`, fed into `.fillMaxHeight(heightFraction)`). Add a new constant `FRAME_BAR_MAX_HEIGHT_FRACTION = 0.6f` and apply it as an extra scale factor: `heightFraction = (frame.sizeBytes.toFloat() / maxSize * FRAME_BAR_MAX_HEIGHT_FRACTION).coerceAtLeast(0.02f)`. The tallest bar now reaches 60% of the panel's height instead of 100%; all other bars scale down proportionally with it (relative size differences between frames are preserved, just compressed into a shorter overall range); the existing 2% minimum-visibility floor is unchanged.

## Testing

Same as the other layout/GOP tasks in this session -- no automated coverage possible for Compose layout constants. Verification is a source-level check of both values plus manual confirmation: open a video with GOP data loaded, confirm the panel is visibly wider (player narrower) than before, and confirm frame bars now top out noticeably below the panel's full height instead of touching it.
