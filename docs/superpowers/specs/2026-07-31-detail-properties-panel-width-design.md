# Detailed Properties Panel Default Width Design

## Goal

Widen the Detailed Properties (right) panel's default width so it's easier to read without immediately having to drag it wider.

## Background

`DashboardLayout.kt` already defines the right panel as user-resizable: `RIGHT_PANEL_DEFAULT_WIDTH_DP = 260f`, draggable between `RIGHT_PANEL_MIN_WIDTH_DP = 220f` and `RIGHT_PANEL_MAX_WIDTH_DP = 1000f` via `VerticalResizeHandle`. This change only moves the *starting* width; the drag range and mechanism are unchanged.

## Design

Change `RIGHT_PANEL_DEFAULT_WIDTH_DP` from `260f` to `350f` in `app/src/main/kotlin/com/multiviewer/ui/DashboardLayout.kt:28`. No other constants (`RIGHT_PANEL_MIN_WIDTH_DP`, `RIGHT_PANEL_MAX_WIDTH_DP`, or the left-panel constants) change.

## Testing

Same as the preview-panel-size change: a literal Compose initial-state value with no automated test coverage possible in this project. Verification is a source-level check of the new value, plus manual confirmation that the panel opens wider by default and the drag handle still works across its existing 220-1000dp range.
