# Video Top-Region Vertical Split Reduction Design

## Goal

Shrink the vertical height of the player+GOP region (video tabs only) to about 70% of its current size.

## Design

`VideoInspectorUI.kt`'s `verticalSplit` changes from `0.7f` to `0.49f` (0.7 * 0.7). The top region (player + GOP, side-by-side) now takes 49% of the center column's height instead of 70%; the summary dashboard below it grows from 30% to 51%. `videoGopSplit` (the player/GOP width split within that region) and everything else are unaffected. `ImageInspectorUI.kt`'s own `verticalSplit` (also currently `0.7f`, a separate variable) is untouched -- this request is video-only.

## Testing

Same as the other layout tasks -- no automated coverage for this Compose composable state value. Verification is a source-level check of the new value plus manual confirmation that the player+GOP region is visibly shorter and the summary dashboard is visibly taller than before.
