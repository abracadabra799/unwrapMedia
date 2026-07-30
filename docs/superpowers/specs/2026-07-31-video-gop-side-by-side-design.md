# Video Player / GOP Analysis Side-by-Side Layout Design

## Goal

Move the GOP (frame analysis) panel from below the video player to its right, so the video preview panel can use the top region's full height instead of sharing it vertically with GOP.

## Background

`VideoInspectorUI.kt`'s top region (itself `verticalSplit` = 70% of the center column, per the just-shipped preview-panel-size change) currently stacks the player and `GopAnalysisView` **vertically** in a `Column`, split by `videoGopSplit` (0.65f) via a horizontal-line `DraggableDivider` (`Orientation.Horizontal`, dragged up/down). The user wants them **side-by-side** instead: player on the left, GOP on the right, split by a vertical-line divider (dragged left/right) -- so the player's height is no longer constrained by needing to leave room for GOP underneath it.

`GopAnalysisView` (`GopAnalysisView.kt`) already renders into whatever `modifier` it's given (`Box(modifier = modifier.fillMaxWidth()...)`, with a horizontally-scrolling frame list inside) -- it doesn't assume a wide-and-short aspect ratio, so it works in a narrower-and-taller slot too (more horizontal scrolling to see the whole GOP structure, same interaction otherwise).

## Design

In `VideoInspectorUI.kt`:

1. Change the inner `Column` (currently holding player Box → horizontal divider → `GopAnalysisView`) to a `Row`.
2. Player `Box`: change `.weight(videoGopSplit).fillMaxWidth()` to `.weight(videoGopSplit).fillMaxHeight()` (was full-width/partial-height, becomes full-height/partial-width).
3. The divider between them: `Orientation.Horizontal` → `Orientation.Vertical` (matches `DraggableDivider`'s existing convention -- `Vertical` = a vertical line, dragged left/right, used for side-by-side splits elsewhere in this codebase, e.g. `DashboardLayout`'s left/right panel handles).
4. The container-size tracking used to convert the divider's drag delta into a fraction: currently `topContainerHeightPx`, measured via `onGloballyPositioned` on the (soon-to-be) `Row`. Since the divider now moves horizontally, this needs to track the Row's **width**, not height -- rename to `topContainerWidthPx` and read `it.size.width` instead of `it.size.height`.
5. `GopAnalysisView`'s modifier: `.weight(1f - videoGopSplit)` (was an implicit height-weight in a `Column`) becomes a width-weight in the `Row`, plus `.fillMaxHeight()` added explicitly so it takes the Row's full height (previously it got full height implicitly from being the last item in a `Column` with no sibling below it; in a `Row`, height isn't automatic the same way).

`videoGopSplit`'s value (0.65f, i.e. player gets 65%) and its 0.1-0.9 drag-clamped range are unchanged -- only the axis it splits along changes, from height to width. `verticalSplit` (top region vs. bottom summary dashboard) and everything else in the file (the outer `Column`, the summary `LazyColumn`, `rightPanel`) are unaffected.

## Testing

Same as the prior two layout tasks: a Compose layout-only change with no automated test coverage possible in this project (no Compose UI test infra). Verification is a source-level check that the `Row`/`Orientation.Vertical`/`topContainerWidthPx` changes are internally consistent, plus manual confirmation: open a video, confirm the player and GOP panel now sit side-by-side (not stacked), the player visibly uses more vertical height than before, the divider between them drags left/right correctly, and GOP's own frame-list/legend/keyboard navigation (arrow-key frame stepping) still work in the narrower slot.
