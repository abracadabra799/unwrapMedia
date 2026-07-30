# Image Tab Detailed Properties Panel Width Design

## Goal

Narrow the Detailed Properties panel to 280dp by default for image tabs (80% of the current shared 350dp default).

## Background

`DashboardLayout`'s `rightPanelDefaultWidthDp` parameter (added earlier this session) already lets callers override the panel's starting width; `VideoInspectorUI.kt` already passes `298f`. `ImageInspectorUI.kt` currently omits the parameter, inheriting the shared `RIGHT_PANEL_DEFAULT_WIDTH_DP = 350f`.

## Design

`ImageInspectorUI.kt`'s `DashboardLayout(...)` call adds `rightPanelDefaultWidthDp = 280f`. The 220-1000dp drag range is unchanged. No effect on video tabs (already on their own 298f override) or any other `DashboardLayout` caller (`RawPixelInspectorUI.kt`, `AudioInspectorUI.kt`, both still on the shared 350f default).

## Testing

Same as the prior panel-width tasks -- no automated coverage for this Compose composable parameter. Verification is a source-level check of the new argument plus manual confirmation that opening an image shows a visibly narrower Detailed Properties panel than before.
