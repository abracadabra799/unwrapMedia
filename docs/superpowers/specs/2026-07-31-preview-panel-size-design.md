# Preview Panel Default Size Design

## Goal

Make the preview panel (thumbnail/image in `ImageInspectorUI`, video playback + GOP in `VideoInspectorUI`) bigger by default, extending further down -- item 2 of a user-reported batch, scoped down after visual-companion brainstorming from a full layout redesign to a simple default-ratio change on the existing structure.

## Background

Both `ImageInspectorUI.kt` and `VideoInspectorUI.kt` already split their center column via a user-draggable `verticalSplit` ratio (`DraggableDivider`): the top region (preview, and for video also the GOP graph) gets `verticalSplit` of the column's height, the bottom region (the scrollable summary dashboard) gets `1f - verticalSplit`. Both currently default to `0.5f`. The user can already drag this divider to any ratio they want -- this change is only about the *starting* ratio when a file is first opened.

A fuller layout redesign (tabbed right panel, collapsible drawer, etc.) was explored via the visual-companion brainstorming session but explicitly deferred -- the user asked to keep the current structure and only enlarge the default preview size.

## Design

Change the initial value of `verticalSplit` from `0.5f` to `0.7f` in both files:

- `app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt:46` -- `var verticalSplit by remember { mutableStateOf(0.5f) }` -- affects the thumbnail+main-image `Row` vs. the summary dashboard below it.
- `app/src/main/kotlin/com/multiviewer/ui/VideoInspectorUI.kt:35` -- `var verticalSplit by remember { mutableStateOf(0.5f) }` -- affects the (player + GOP graph) region vs. the summary dashboard below it. `videoGopSplit` (the split *within* that top region, between the player and the GOP graph) is unaffected -- both grow together, keeping their existing internal proportions.

No other behavior changes: `DraggableDivider` still lets the user resize freely afterward; nothing about `DashboardLayout`'s left/right panels changes.

## Testing

Both are literal Compose `remember { mutableStateOf(...) }` initial values inside `@Composable` functions -- not extractable into a pure function, and this project has no Compose UI test infrastructure (consistent with the "video open background probe" plan's testing approach). Verification is a source-level check that both values read `0.7f`, plus manual confirmation by opening an image and a video and visually comparing the preview area's size against the previous 50/50 split.
